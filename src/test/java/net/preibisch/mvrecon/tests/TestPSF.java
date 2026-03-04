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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ij.ImageJ;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.psf.PSFCombination;
import net.preibisch.mvrecon.process.psf.PSFExtraction;

public class TestPSF
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
	public void testPSFExtraction()
	{
		final RandomAccessibleInterval<FloatType> maxAvgPSF = testPSF( spimData, "beads", false );

		assertArrayEquals( new long[] { 31, 31 }, maxAvgPSF.dimensionsAsLongArray(), "Expecting specific image size." );

		assertEquals( 0.0, maxAvgPSF.getAt( 0, 0 ).getRealDouble(), SimulateUtil.delta, "Expecting specific pixel intensities." );
		assertEquals( 0.0, maxAvgPSF.getAt( 30, 30 ).getRealDouble(), SimulateUtil.delta, "Expecting specific pixel intensities." );
		assertEquals( 1.0, maxAvgPSF.getAt( 15, 15 ).getRealDouble(), SimulateUtil.delta, "Expecting specific pixel intensities." );
		assertEquals( 0.15133176743984222, maxAvgPSF.getAt( 11, 15 ).getRealDouble(), SimulateUtil.delta, "Expecting specific pixel intensities." );
		assertEquals( 0.006437880918383598, maxAvgPSF.getAt( 23, 26 ).getRealDouble(), SimulateUtil.delta, "Expecting specific pixel intensities." );
	}

	public static RandomAccessibleInterval< FloatType > testPSF( final SpimData2 spimData, final String label, final boolean display )
	{
		// select views to process
		final List< ViewId > viewIds = new ArrayList< ViewId >();
		viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		// filter not present ViewIds
		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		final HashMap< ViewId, Img< FloatType > > psfs = new HashMap<>();

		for ( final ViewId viewId : viewIds )
		{
			final PSFExtraction< FloatType > psf = new PSFExtraction< FloatType >( spimData, viewId, label, true, new FloatType(), new long[]{ 19, 19, 25 } );

			psf.removeMinProjections();

			spimData.getViewRegistrations().getViewRegistration( viewId ).updateModel();
			psfs.put( viewId, psf.getTransformedNormalizedPSF( spimData.getViewRegistrations().getViewRegistration( viewId ).getModel() ) );

			//ImageJFunctions.show( psf.getPSF() );
			//ImageJFunctions.show( psfs.get( viewId ) );
		}

		final Img< FloatType > maxAvgPSF = PSFCombination.computeMaxAverageTransformedPSF( psfs.values(), new ArrayImgFactory<>( new FloatType() ) );

		if ( display )
		{
			final Img< FloatType > avgPSF = PSFCombination.computeAverageImage( psfs.values(), new ArrayImgFactory<>( new FloatType() ), true );

			FusionTools.getImagePlusInstance( Views.rotate( avgPSF, 0, 2 ), false, "avgPSF", 0, 1, DisplayImage.service ).show();
			FusionTools.getImagePlusInstance( maxAvgPSF, false, "maxAvgPSF", 0, 1, DisplayImage.service ).show();
		}

		return maxAvgPSF;
	}

	// ==================== Original main() method for manual testing ====================
	public static void main( String[] args )
	{
		new ImageJ();

		final SpimData2 spimData = SimulateUtil.setUpLarge();

		TestInterestPointDetection.testDoG( spimData, "beads" );
		TestRegistration.testRegistration( spimData, "beads", false );

		testPSF( spimData, "beads", true );
	}
}
