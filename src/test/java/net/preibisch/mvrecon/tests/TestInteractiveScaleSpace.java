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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.util.Intervals;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.BoundedValueSlider;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveScaleSpace;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace.ScaleSpacePeak;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceParameters;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;

/**
 * The headless parts of the interactive scale-space preview: the colors of the scales, the circles, the
 * binning per slice, the default ROI, the shared slider and the session; the windows were checked by
 * screenshots on ds1 (SCALESPACE_NOTES.md)
 */
public class TestInteractiveScaleSpace
{
	@Test
	public void testColor()
	{
		final double sigmaMin = 1.6, sigmaMax = 12.8;

		// red for the initial blur, blue for the coarsest level, green half way in log( sigma )
		assertEquals( new Color( 255, 0, 0 ), InteractiveScaleSpace.color( sigmaMin, sigmaMin, sigmaMax ) );
		assertEquals( new Color( 0, 0, 255 ), InteractiveScaleSpace.color( sigmaMax, sigmaMin, sigmaMax ) );
		assertEquals( new Color( 0, 255, 0 ), InteractiveScaleSpace.color( Math.sqrt( sigmaMin * sigmaMax ), sigmaMin, sigmaMax ) );

		// clamped outside, red if there is only one scale
		assertEquals( new Color( 255, 0, 0 ), InteractiveScaleSpace.color( 0.5, sigmaMin, sigmaMax ) );
		assertEquals( new Color( 0, 0, 255 ), InteractiveScaleSpace.color( 100, sigmaMin, sigmaMax ) );
		assertEquals( new Color( 255, 0, 0 ), InteractiveScaleSpace.color( sigmaMin, sigmaMin, sigmaMin ) );
	}

	@Test
	public void testCircles()
	{
		assertEquals( 2 * Math.sqrt( 3 ), InteractiveScaleSpace.radius( 2 ), 1e-12 );

		// the intersection of a ball with a plane
		assertEquals( 5, InteractiveScaleSpace.projectedRadius( 5, 0 ), 1e-12 );
		assertEquals( 4, InteractiveScaleSpace.projectedRadius( 5, 3 ), 1e-12 );
		assertEquals( 4, InteractiveScaleSpace.projectedRadius( 5, -3 ), 1e-12 );
		assertEquals( 0, InteractiveScaleSpace.projectedRadius( 5, 5 ), 0.0 );
		assertEquals( 0, InteractiveScaleSpace.projectedRadius( 5, 7 ), 0.0 );

		assertEquals( 1f, InteractiveScaleSpace.stroke( false ).getLineWidth(), 0f );
		assertNull( InteractiveScaleSpace.stroke( false ).getDashArray() );
		assertTrue( InteractiveScaleSpace.stroke( true ).getDashArray().length > 0 );

		// the finest level has its own color, not part of the ramp
		assertFalse( InteractiveScaleSpace.finestColor.equals( InteractiveScaleSpace.color( 1.6, 1.6, 12.8 ) ) );
	}

	@Test
	public void testBinPerSlice()
	{
		final ScaleSpacePeak a = new ScaleSpacePeak( new double[] { 1, 1, 10 }, -0.5, 1, 0, true ); // radius 1.73
		final ScaleSpacePeak b = new ScaleSpacePeak( new double[] { 5, 5, 3 }, -0.2, 4, 1, true ); // radius 6.93
		final List< ScaleSpacePeak > peaks = new ArrayList<>();
		peaks.add( a );
		peaks.add( b );

		// isotropic: a on the slices 9..11, b on 0..9 (the order of the list is kept)
		List< List< ScaleSpacePeak > > bins = InteractiveScaleSpace.binPerSlice( peaks, 12, 1.0 );

		assertEquals( 12, bins.size() );

		for ( int s = 0; s < 12; ++s )
		{
			final boolean hasA = s >= 9 && s <= 11, hasB = s <= 9;

			assertEquals( ( hasA ? 1 : 0 ) + ( hasB ? 1 : 0 ), bins.get( s ).size(), "slice " + s );

			if ( hasA )
				assertTrue( bins.get( s ).get( 0 ) == a );

			if ( hasB )
				assertTrue( bins.get( s ).get( bins.get( s ).size() - 1 ) == b );
		}

		// z voxels three times bigger: a on slice 10 only, b on 1..5
		bins = InteractiveScaleSpace.binPerSlice( peaks, 12, 3.0 );

		for ( int s = 0; s < 12; ++s )
			assertEquals( ( s == 10 ? 1 : 0 ) + ( s >= 1 && s <= 5 ? 1 : 0 ), bins.get( s ).size(), "slice " + s );
	}

	@Test
	public void testDefaultRoi()
	{
		final Interval small = new FinalInterval( 100, 100, 10 );
		assertTrue( Intervals.equals( small, InteractiveScaleSpace.defaultRoi( small ) ) );

		// a big slice: 200 x 200 in the middle, all z if there are few slices
		final Interval big = new FinalInterval( 4096, 4096, 10 );
		Interval roi = InteractiveScaleSpace.defaultRoi( big );

		assertEquals( InteractiveScaleSpace.defaultRoiSize, roi.dimension( 0 ) );
		assertEquals( InteractiveScaleSpace.defaultRoiSize, roi.dimension( 1 ) );
		assertEquals( 1948, roi.min( 0 ) );
		assertEquals( 1948, roi.min( 1 ) );
		assertEquals( 0, roi.min( 2 ) );
		assertEquals( 9, roi.max( 2 ) );
		assertTrue( Intervals.contains( big, roi ) );

		// many slices: a z range around the current slice that fits the voxel budget for 200 x 200
		final Interval tall = new FinalInterval( 512, 512, 5000 );
		final long zHalf = InteractiveScaleSpace.defaultZHalf( 200L * 200L, InteractiveScaleSpace.defaultMaxVoxels );

		assertEquals( 209, zHalf );

		roi = InteractiveScaleSpace.defaultRoi( tall, 2500, InteractiveScaleSpace.defaultMaxVoxels );

		assertEquals( 200, roi.dimension( 0 ) );
		assertEquals( 156, roi.min( 0 ) );
		assertEquals( 2500 - zHalf, roi.min( 2 ) );
		assertEquals( 2500 + zHalf, roi.max( 2 ) );
		assertTrue( Intervals.numElements( roi ) <= InteractiveScaleSpace.defaultMaxVoxels );

		// at the ends of the stack the range is cut, the minimal range is kept for huge areas
		roi = InteractiveScaleSpace.defaultRoi( tall, 3, InteractiveScaleSpace.defaultMaxVoxels );
		assertEquals( 0, roi.min( 2 ) );
		assertEquals( 3 + zHalf, roi.max( 2 ) );
		assertEquals( InteractiveScaleSpace.minZHalf, InteractiveScaleSpace.defaultZHalf( 4096L * 4096L, InteractiveScaleSpace.defaultMaxVoxels ) );

		// a rectangle around a slice
		roi = InteractiveScaleSpace.roiInterval( tall, 10, 20, 109, 219, 100, 5 );
		assertEquals( 10, roi.min( 0 ) );
		assertEquals( 109, roi.max( 0 ) );
		assertEquals( 200, roi.dimension( 1 ) );
		assertEquals( 95, roi.min( 2 ) );
		assertEquals( 105, roi.max( 2 ) );

		// the region of a window at (2, 2, 1) scaled to (1, 1, 1): x and y doubled, the central slice the same, the z range from the budget
		final Interval from = new FinalInterval( new long[] { 10, 20, 30 }, new long[] { 59, 79, 50 } );
		final Interval full = new FinalInterval( 512, 512, 86 );
		roi = InteractiveScaleSpace.scaledRoi( from, new long[] { 2, 2, 1 }, new long[] { 1, 1, 1 }, full, InteractiveScaleSpace.defaultMaxVoxels );

		assertEquals( 20, roi.min( 0 ) );
		assertEquals( 119, roi.max( 0 ) );
		assertEquals( 40, roi.min( 1 ) );
		assertEquals( 159, roi.max( 1 ) );
		assertEquals( 0, roi.min( 2 ) ); // 40 +/- 698 fills the stack
		assertEquals( 85, roi.max( 2 ) );

		// and to (4, 4, 2): halved in x and y, the central slice too
		roi = InteractiveScaleSpace.scaledRoi( from, new long[] { 2, 2, 1 }, new long[] { 4, 4, 2 }, new FinalInterval( 128, 128, 43 ), InteractiveScaleSpace.defaultMaxVoxels );

		assertEquals( 5, roi.min( 0 ) );
		assertEquals( 29, roi.max( 0 ) );
		assertEquals( 10, roi.min( 1 ) );
		assertEquals( 39, roi.max( 1 ) );
		assertTrue( roi.min( 2 ) <= 20 && roi.max( 2 ) >= 20 );
	}

	@Test
	public void testBoundedValueSlider()
	{
		// logarithmic (the threshold): the slider is linear in log( value )
		final BoundedValueSlider log = new BoundedValueSlider( 0.008, 0.001, 0.3, true, "0.00000", 0.0005 );
		final double[] lastValue = new double[] { Double.NaN };
		final double[] lastBounds = new double[] { Double.NaN, Double.NaN };

		log.addListener( new BoundedValueSlider.Listener()
		{
			@Override
			public void valueChanged( final double value ) { lastValue[ 0 ] = value; }

			@Override
			public void boundsChanged( final double min, final double max ) { lastBounds[ 0 ] = min; lastBounds[ 1 ] = max; }
		} );

		assertEquals( 0.008, log.getValue(), 0.0 );
		assertEquals( 0.001, log.getLowerBound(), 0.0 );
		assertEquals( 0.3, log.getUpperBound(), 0.0 );

		log.setValue( 0.01 );
		assertEquals( 0.01, lastValue[ 0 ], 0.0 );
		assertEquals( 0.01, log.getValue(), 0.0 );

		// a value typed outside the bounds extends them
		log.setValue( 0.5 );
		assertEquals( 0.5, log.getValue(), 0.0 );
		assertEquals( 0.5, log.getUpperBound(), 0.0 );
		assertEquals( 0.5, lastBounds[ 1 ], 0.0 );

		// new bounds clamp the value
		log.setBounds( 0.002, 0.1 );
		assertEquals( 0.1, log.getValue(), 0.0 );
		assertEquals( 0.1, lastValue[ 0 ], 0.0 );
		assertEquals( 0.002, lastBounds[ 0 ], 0.0 );

		// the lower bound stays positive
		log.setBounds( -1, 0.1 );
		assertTrue( log.getLowerBound() > 0 );

		// linear (the anisotropy): 0 is a valid lower bound
		final BoundedValueSlider linear = new BoundedValueSlider( 3, 0.25, 5, false, "0.000", 0.01 );
		linear.setBounds( -1, 4 );
		assertEquals( 0, linear.getLowerBound(), 0.0 );
		assertEquals( 4, linear.getUpperBound(), 0.0 );
		assertEquals( 3, linear.getValue(), 0.0 );
		linear.setValue( 10 );
		assertEquals( 10, linear.getUpperBound(), 0.0 );
	}

	@Test
	public void testDefaultFinestStructures()
	{
		final Boolean before = ScaleSpaceGUI.lastDetectFinestLevel;

		try
		{
			// not chosen yet: off at full resolution, on at any downsampling
			ScaleSpaceGUI.lastDetectFinestLevel = null;
			assertFalse( ScaleSpaceGUI.defaultDetectFinestLevel( new long[] { 1, 1, 1 } ) );
			assertTrue( ScaleSpaceGUI.defaultDetectFinestLevel( new long[] { 2, 2, 1 } ) );
			assertTrue( ScaleSpaceGUI.defaultDetectFinestLevel( new long[] { 1, 1, 2 } ) );

			// the last choice wins
			ScaleSpaceGUI.lastDetectFinestLevel = Boolean.TRUE;
			assertTrue( ScaleSpaceGUI.defaultDetectFinestLevel( new long[] { 1, 1, 1 } ) );
			ScaleSpaceGUI.lastDetectFinestLevel = Boolean.FALSE;
			assertFalse( ScaleSpaceGUI.defaultDetectFinestLevel( new long[] { 4, 4, 2 } ) );
		}
		finally
		{
			ScaleSpaceGUI.lastDetectFinestLevel = before;
		}
	}

	@Test
	public void testSession()
	{
		final SpimData2 spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[] { 0, 90 }, 0, 20, new double[] { 2, 2, 2 }, new FinalInterval( 32, 32, 16 ) ) );
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );

		final ScaleSpaceParameters p = new ScaleSpaceParameters();
		p.threshold = 0.02;
		p.detectFinestLevel = false;
		p.steps = 3;
		p.octaves = 2;

		final InteractiveScaleSpace.Session session = new InteractiveScaleSpace.Session( spimData, vd, new String[] { "1, 1, 1" }, p, 1.0 );

		assertFalse( session.isFinished() );
		assertFalse( session.isCancelled() );
		assertEquals( 0.02, session.getThreshold(), 0.0 );
		assertFalse( session.getDetectFinestLevel() );
		assertEquals( 3, session.getSteps() );
		assertEquals( 2, session.getOctaves() );
		assertEquals( "all", InteractiveScaleSpace.octaveChoices[ 0 ] );
		assertEquals( "3", InteractiveScaleSpace.octaveChoices[ 3 ] );
		assertNull( session.getDownsampling() );

		// without windows cancel finishes the session, waitUntilFinished returns
		session.cancel();
		session.waitUntilFinished();

		assertTrue( session.isFinished() );
		assertTrue( session.isCancelled() );
		assertNull( session.getDownsampling() );
	}
}
