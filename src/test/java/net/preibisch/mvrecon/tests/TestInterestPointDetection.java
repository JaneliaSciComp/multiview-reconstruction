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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

public class TestInterestPointDetection
{
	/**
	 * Standard DoG parameters for shared tests between multiview-reconstruction and BigStitcher-Spark.
	 * These should be used consistently to ensure test results match.
	 *
	 * Both tests use global min/max intensity (0.0 and 1137.0 - the max across all views)
	 * to ensure identical threshold normalization and point counts.
	 */
	public static final double STANDARD_SIGMA = 1.1;
	public static final double STANDARD_THRESHOLD = 0.01;
	public static final int STANDARD_DOWNSAMPLE_XY = 2;
	public static final int STANDARD_DOWNSAMPLE_Z = 1;
	public static final String STANDARD_LABEL = "beads";

	/**
	 * Global min/max intensity for all views.
	 * Using the max intensity from View 0 (1137.0), which is the highest across all views.
	 * This matches the GUI option "use same min/max for all views".
	 */
	public static final double GLOBAL_MIN_INTENSITY = 0.0;
	public static final double GLOBAL_MAX_INTENSITY = 1137.0;

	/** Expected point counts from SimulateUtil.setUp() with standard parameters and global intensity */
	public static final int EXPECTED_COUNT_VIEW_0 = 162;
	public static final int EXPECTED_COUNT_VIEW_1 = 91;
	public static final int EXPECTED_COUNT_VIEW_2 = 59;

	/**
	 * Position tolerance for median coordinate assertions.
	 * Virtual downsampling (LazyDownsample2x) produces slightly different subpixel positions
	 * than non-virtual downsampling (Downsample.simple2x), typically ~0.002 pixels.
	 */
	public static final double POSITION_DELTA = 0.01;

	/**
	 * Expected min/max intensity values for each view from SimulateUtil.setUp().
	 * These are the actual data ranges and should be used by BigStitcher-Spark tests
	 * to ensure identical results.
	 */
	public static final double EXPECTED_MIN_INTENSITY_VIEW_0 = 0.0;
	public static final double EXPECTED_MAX_INTENSITY_VIEW_0 = 1137.0;
	public static final double EXPECTED_MIN_INTENSITY_VIEW_1 = 0.0;
	public static final double EXPECTED_MAX_INTENSITY_VIEW_1 = 952.0;
	public static final double EXPECTED_MIN_INTENSITY_VIEW_2 = 0.0;
	public static final double EXPECTED_MAX_INTENSITY_VIEW_2 = 836.0;

	private SpimData2 spimData;

	@BeforeEach
	public void setUp()
	{
		spimData = SimulateUtil.setUp();
	}

	@Test
	public void testDoGSegmentation()
	{
		testDoG( spimData, STANDARD_LABEL );
		assertDoGResults( spimData, STANDARD_LABEL );
	}

	/**
	 * Verifies that the expected min/max intensity constants match the actual
	 * min/max values in the simulated data. This ensures BigStitcher-Spark tests
	 * use the correct intensity values.
	 */
	@Test
	public void testExpectedIntensityValues()
	{
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		// Get downsampled images for each view (same as DoG detection uses)
		final ViewDescription vd0 = spimData.getSequenceDescription().getViewDescription( 0, 0 );
		final ViewDescription vd1 = spimData.getSequenceDescription().getViewDescription( 0, 1 );
		final ViewDescription vd2 = spimData.getSequenceDescription().getViewDescription( 0, 2 );

		@SuppressWarnings( "rawtypes" )
		final Pair<RandomAccessibleInterval, AffineTransform3D> input0 =
				DownsampleTools.openAndDownsample(
						spimData.getSequenceDescription().getImgLoader(),
						vd0,
						new long[] { STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_Z },
						false );

		@SuppressWarnings( "rawtypes" )
		final Pair<RandomAccessibleInterval, AffineTransform3D> input1 =
				DownsampleTools.openAndDownsample(
						spimData.getSequenceDescription().getImgLoader(),
						vd1,
						new long[] { STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_Z },
						false );

		@SuppressWarnings( "rawtypes" )
		final Pair<RandomAccessibleInterval, AffineTransform3D> input2 =
				DownsampleTools.openAndDownsample(
						spimData.getSequenceDescription().getImgLoader(),
						vd2,
						new long[] { STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_Z },
						false );

		// Compute actual min/max for each view
		@SuppressWarnings( "unchecked" )
		final float[] minmax0 = FusionTools.minMax( input0.getA(), service );
		@SuppressWarnings( "unchecked" )
		final float[] minmax1 = FusionTools.minMax( input1.getA(), service );
		@SuppressWarnings( "unchecked" )
		final float[] minmax2 = FusionTools.minMax( input2.getA(), service );

		service.shutdown();

		// Verify View 0
		assertEquals( EXPECTED_MIN_INTENSITY_VIEW_0, minmax0[0], 0.01, "View 0 min intensity" );
		assertEquals( EXPECTED_MAX_INTENSITY_VIEW_0, minmax0[1], 0.01, "View 0 max intensity" );

		// Verify View 1
		assertEquals( EXPECTED_MIN_INTENSITY_VIEW_1, minmax1[0], 0.01, "View 1 min intensity" );
		assertEquals( EXPECTED_MAX_INTENSITY_VIEW_1, minmax1[1], 0.01, "View 1 max intensity" );

		// Verify View 2
		assertEquals( EXPECTED_MIN_INTENSITY_VIEW_2, minmax2[0], 0.01, "View 2 min intensity" );
		assertEquals( EXPECTED_MAX_INTENSITY_VIEW_2, minmax2[1], 0.01, "View 2 max intensity" );

		IOFunctions.println( "Verified intensity ranges:" );
		IOFunctions.println( "  View 0: min=" + minmax0[0] + ", max=" + minmax0[1] );
		IOFunctions.println( "  View 1: min=" + minmax1[0] + ", max=" + minmax1[1] );
		IOFunctions.println( "  View 2: min=" + minmax2[0] + ", max=" + minmax2[1] );
	}

	/**
	 * Static assertion method for DoG interest point detection results.
	 * Can be called from both multiview-reconstruction and BigStitcher-Spark tests
	 * to ensure consistent verification.
	 *
	 * Expected results (using SimulateUtil.setUp() with 3 views, 200 beads):
	 * - View 0: 162 points, median X = 58.37869967778258
	 * - View 1: 91 points, median Y = 61.81804601215178
	 * - View 2: 59 points, median Z = 25.192354308314773
	 *
	 * @param spimData the SpimData2 containing detected interest points
	 * @param label the interest point label to verify
	 */
	public static void assertDoGResults( final SpimData2 spimData, final String label )
	{
		final InterestPoints list0 = spimData.getViewInterestPoints().getViewInterestPointLists( new ViewId( 0, 0 ) ).getInterestPointList( label );
		final InterestPoints list1 = spimData.getViewInterestPoints().getViewInterestPointLists( new ViewId( 0, 1 ) ).getInterestPointList( label );
		final InterestPoints list2 = spimData.getViewInterestPoints().getViewInterestPointLists( new ViewId( 0, 2 ) ).getInterestPointList( label );

		final Map<Integer, InterestPoint> points0 = list0.getInterestPointsCopy();
		final Map<Integer, InterestPoint> points1 = list1.getInterestPointsCopy();
		final Map<Integer, InterestPoint> points2 = list2.getInterestPointsCopy();

		assertEquals( EXPECTED_COUNT_VIEW_0, points0.size(), "View 0 interest point count" );
		assertEquals( EXPECTED_COUNT_VIEW_1, points1.size(), "View 1 interest point count" );
		assertEquals( EXPECTED_COUNT_VIEW_2, points2.size(), "View 2 interest point count" );

		// test x coordinates for first, y coordinates for second, and z coordinates for third view
		final double[] x0 = points0.values().stream().map( ip -> ip.getL()[ 0 ] ).mapToDouble( Double::doubleValue ).toArray();
		final double[] y1 = points1.values().stream().map( ip -> ip.getL()[ 1 ] ).mapToDouble( Double::doubleValue ).toArray();
		final double[] z2 = points2.values().stream().map( ip -> ip.getL()[ 2 ] ).mapToDouble( Double::doubleValue ).toArray();

		final double medianX0 = Util.median( x0 );
		final double medianY1 = Util.median( y1 );
		final double medianZ2 = Util.median( z2 );

		assertEquals( 58.37869967778258, medianX0, POSITION_DELTA, "View 0 X median" );
		assertEquals( 61.81804601215178, medianY1, POSITION_DELTA, "View 1 Y median" );
		assertEquals( 25.192354308314773, medianZ2, POSITION_DELTA, "View 2 Z median" );
	}

	/**
	 * Runs DoG interest point detection using standard test parameters.
	 * Can be called from both multiview-reconstruction and BigStitcher-Spark tests.
	 *
	 * Uses global min/max intensity for all views (matching the GUI option
	 * "use same min/max for all views") to ensure identical results with Spark.
	 *
	 * Uses single-threaded execution to match Spark's per-task processing and
	 * ensure deterministic floating-point results.
	 *
	 * @param spimData the SpimData2 to process
	 * @param label the interest point label to use
	 */
	public static void testDoG( SpimData2 spimData, final String label )
	{
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Running DoG detection with standard test parameters" );

		for ( final ViewDescription vd : spimData.getSequenceDescription().getViewDescriptions().values() )
		{
			if ( !vd.isPresent() )
				continue;

			// Use single thread to match Spark's per-task processing
			final ExecutorService service = Threads.createFixedExecutorService( 1 );

			// downsampling
			@SuppressWarnings({ "rawtypes" })
			final Pair< RandomAccessibleInterval, AffineTransform3D > input =
					DownsampleTools.openAndDownsample(
							spimData.getSequenceDescription().getImgLoader(),
							vd,
							new long[] { STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_XY, STANDARD_DOWNSAMPLE_Z },
							false );

			@SuppressWarnings("unchecked")
			List< InterestPoint > ips = DoGImgLib2.computeDoG(
					(RandomAccessible) Views.extendMirrorSingle( input.getA() ),
					null, // mask
					new FinalInterval( input.getA() ),
					STANDARD_SIGMA,
					STANDARD_THRESHOLD,
					1, // localization: quadratic
					false, // findMin
					true, // findMax
					GLOBAL_MIN_INTENSITY,
					GLOBAL_MAX_INTENSITY,
					DoGImgLib2.blockSize,
					service,
					null, // cuda
					null, // deviceCUDA
					false, // accurateCUDA
					0.0 ); // percentGPUMem

			service.shutdown();

			DownsampleTools.correctForDownsampling( ips, input.getB() );

			InterestPointTools.addInterestPoints( spimData, label,
					new HashMap< ViewId, List< InterestPoint > >() {{ put( vd, ips ); }},
					"DoG, sigma=" + STANDARD_SIGMA + ", downsampleXY=" + STANDARD_DOWNSAMPLE_XY );
		}
	}

	public static void main( String[] args ) throws SpimDataException
	{
		final SpimData2 spimData = SimulateUtil.setUpLarge();

		testDoG( spimData, STANDARD_LABEL );
	}

}
