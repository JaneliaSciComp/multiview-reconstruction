/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveAnisotropy;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

/**
 * The headless parts of the interactive anisotropy estimation (no BigDataViewer window)
 */
public class TestInteractiveAnisotropy
{
	@Test
	public void testHelpers()
	{
		// the fixed transform scales z only
		final AffineTransform3D t = InteractiveAnisotropy.anisotropyTransform( 2.5 );
		final double[] p = new double[ 3 ];
		t.apply( new double[] { 1, 2, 3 }, p );
		assertArrayEquals( new double[] { 1, 2, 7.5 }, p, 1e-12 );

		assertEquals( 3.0, InteractiveAnisotropy.calibrationAnisotropy( new FinalVoxelDimensions( "um", 1, 1, 3 ) ), 1e-12 );
		assertEquals( 4.444, InteractiveAnisotropy.calibrationAnisotropy( new FinalVoxelDimensions( "um", 0.45, 0.45, 2.0 ) ), 1e-3 );

		// the simulated data has no pyramid: one level on the raw grid with the identity
		final SpimData2 spimData = SimulateUtil.setUp();
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );

		assertEquals( 1.0, InteractiveAnisotropy.calibrationAnisotropy( vd ), 1e-12 );

		final List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes = InteractiveAnisotropy.rawMultiResolution( spimData, vd );

		assertEquals( 1, multiRes.size() );
		assertTrue( multiRes.get( 0 ).getB().isIdentity() );
		assertArrayEquals( vd.getViewSetup().getSize().dimensionsAsLongArray(), Intervals.dimensionsAsLongArray( multiRes.get( 0 ).getA() ) );
	}
}
