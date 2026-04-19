/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxBigDataViewer;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxEstimation;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Hybrid test class for bounding box functionality.
 * Contains both JUnit @Test methods and a main() method for manual testing.
 */
public class TestBoundingBox
{
	private SpimData2 spimData;

	@BeforeEach
	public void setUp()
	{
		spimData = SimulateUtil.setUp();
		TestInterestPointDetection.testDoG( spimData, "beads" );
		TestRegistration.testRegistration( spimData, "beads", false );
	}

	@Test
	public void testBoundingBoxMaximal()
	{
		final BoundingBox bb = testBoundingBox( spimData, false );

		assertNotNull( bb, "Bounding box should not be null" );

		// Verify bounding box has reasonable dimensions
		assertTrue( bb.dimension( 0 ) > 0, "Bounding box X dimension should be positive" );
		assertTrue( bb.dimension( 1 ) > 0, "Bounding box Y dimension should be positive" );
		assertTrue( bb.dimension( 2 ) > 0, "Bounding box Z dimension should be positive" );

		assertEquals( -1, bb.min( 0 ), "Bounding box should have a min X." );
		assertEquals( 0, bb.min( 1 ), "Bounding box should have a min Y." );
		assertEquals( -39, bb.min( 2 ), "Bounding box should have a min Z." );

		assertEquals( 127, bb.max( 0 ), "Bounding box should have a max X." );
		assertEquals( 126, bb.max( 1 ), "Bounding box should have a max Y." );
		assertEquals( 87, bb.max( 2 ), "Bounding box should have a max Z." );

		System.out.println( "✓ Maximal bounding box test passed: " + bb );
	}

	public static BoundingBox testBoundingBox( final SpimData2 spimData, final boolean bdv )
	{
		// select views to process
		final List< ViewId > viewIds = new ArrayList< ViewId >();
		viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		// filter not present ViewIds
		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		BoundingBoxEstimation estimation;

		if ( bdv )
			estimation = new BoundingBoxBigDataViewer( spimData, viewIds );
		else
			estimation = new BoundingBoxMaximal( viewIds, spimData );

		final BoundingBox bb = estimation.estimate( "Full Bounding Box" );

		return bb;
	}

	public static BoundingBox getBoundingBox( final SpimData2 spimData, final String bbTitle )
	{
		return BoundingBoxTools.getBoundingBox( spimData, bbTitle );
	}

	// ========== Manual Testing Method ==========
	public static void main( String[] args )
	{
		SpimData2 spimData = SimulateUtil.setUpLarge();

		System.out.println( "Views present:" );

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
			System.out.println( Group.pvid( viewId ) );

		TestInterestPointDetection.testDoG( spimData, "beads" );
		TestRegistration.testRegistration( spimData, "beads", false );

		final BoundingBox bb = testBoundingBox( spimData, true );
		System.out.println( bb );
	}
}
