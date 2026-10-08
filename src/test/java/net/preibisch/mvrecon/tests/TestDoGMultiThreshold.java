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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointValue;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGParameters;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;

/**
 * Several thresholds at once for the single-scale Difference-of-Gaussian (one computation) give the same points as one
 * run per threshold: for the algorithm (both localizations, maxima only and both extrema), for the driver (own positions
 * per threshold, mapped to full resolution once each, also with a limit of detections) and for the GUI (one label suffix
 * per threshold with its own parameter string)
 */
public class TestDoGMultiThreshold
{
	protected static SpimData2 simulatedBeads()
	{
		return SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[] { 0, 90 }, 0, 100, new double[] { 2, 2, 2 }, new FinalInterval( 128, 128, 64 ) ) );
	}

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void testAlgorithm()
	{
		final SpimData2 spimData = simulatedBeads();
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );
		final RandomAccessibleInterval img = DownsampleTools.openAndDownsample( spimData.getSequenceDescription().getImgLoader(), vd, new long[] { 2, 2, 1 }, false ).getA();
		final Interval interval = new FinalInterval( img );
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );
		final float[] minmax = FusionTools.minMax( img );

		// any order, the lists come back in this order
		final double[] thresholds = new double[] { 0.004, 0.02, 0.001 };

		for ( final int localization : new int[] { 0, 1 } )
			for ( final boolean findMin : new boolean[] { false, true } )
			{
				final String setup = "localization " + localization + ", findMin " + findMin;

				final ArrayList< ArrayList< InterestPoint > > multi = DoGImgLib2.computeDoG(
						(RandomAccessible)Views.extendMirrorSingle( img ), null, interval, 1.8, thresholds, localization, findMin, true, minmax[ 0 ], minmax[ 1 ], service );

				assertEquals( thresholds.length, multi.size(), setup );

				for ( int i = 0; i < thresholds.length; ++i )
				{
					final ArrayList< InterestPoint > single = DoGImgLib2.computeDoG(
							(RandomAccessible)Views.extendMirrorSingle( img ), null, interval, 1.8, thresholds[ i ], localization, findMin, true, minmax[ 0 ], minmax[ 1 ], service );

					assertTrue( single.size() > 0, setup + ", threshold " + thresholds[ i ] );
					assertSamePoints( single, multi.get( i ), setup + ", threshold " + thresholds[ i ] );
				}

				// a lower threshold never keeps fewer points, and the positions of the lists are independent (correctForDownsampling transforms in place)
				assertTrue( multi.get( 2 ).size() >= multi.get( 0 ).size() && multi.get( 0 ).size() >= multi.get( 1 ).size(), setup );
				assertTrue( multi.get( 0 ).get( 0 ).getL() != multi.get( 2 ).get( 0 ).getL(), setup );
			}

		// invalid thresholds
		assertThrows( IllegalArgumentException.class, () -> DoGImgLib2.computeDoG( (RandomAccessible)Views.extendMirrorSingle( img ), null, interval, 1.8, new double[ 0 ], 1, false, true, minmax[ 0 ], minmax[ 1 ], service ) );
		assertThrows( IllegalArgumentException.class, () -> DoGImgLib2.computeDoG( (RandomAccessible)Views.extendMirrorSingle( img ), null, interval, 1.8, new double[] { 0.01, -1 }, 1, false, true, minmax[ 0 ], minmax[ 1 ], service ) );
		assertThrows( IllegalArgumentException.class, () -> DoGImgLib2.computeDoG( (RandomAccessible)Views.extendMirrorSingle( img ), null, interval, 1.8, new double[] { Double.NaN }, 1, false, true, minmax[ 0 ], minmax[ 1 ], service ) );

		service.shutdown();
	}

	protected static DoGParameters driverParameters( final SpimData2 spimData, final ViewDescription vd, final boolean limit )
	{
		final DoGParameters dog = new DoGParameters();

		dog.imgloader = spimData.getSequenceDescription().getImgLoader();
		dog.toProcess = new ArrayList<>();
		dog.toProcess.add( vd );
		dog.downsampleXY = 2;
		dog.downsampleZ = 1;
		dog.sigma = 1.8;
		dog.localization = 1;
		dog.findMin = false;
		dog.findMax = true;
		dog.limitDetections = limit;
		dog.maxDetections = 20;
		dog.maxDetectionsTypeIndex = 0;

		return dog;
	}

	@Test
	public void testDriverAndGUI()
	{
		final SpimData2 spimData = simulatedBeads();
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );
		final double[] thresholds = new double[] { 0.004, 0.02 };

		// the driver, with and without a limit of detections
		for ( final boolean limit : new boolean[] { false, true } )
		{
			final DoGParameters dog = driverParameters( spimData, vd, limit );
			final ArrayList< HashMap< ViewId, List< InterestPoint > > > perThreshold = DoG.findInterestPoints( dog, thresholds );

			assertEquals( thresholds.length, perThreshold.size() );

			for ( int i = 0; i < thresholds.length; ++i )
			{
				dog.threshold = thresholds[ i ];
				final List< InterestPoint > single = DoG.findInterestPoints( dog ).get( vd );

				assertTrue( single.size() > 0, "limit " + limit + ", threshold " + thresholds[ i ] );
				assertSamePoints( single, perThreshold.get( i ).get( vd ), "driver, limit " + limit + ", threshold " + thresholds[ i ] );
			}
		}

		// the GUI: one label per threshold
		final ArrayList< ViewId > views = new ArrayList<>();
		views.add( vd );

		final DifferenceOfGaussianGUI gui = new DifferenceOfGaussianGUI( spimData, views )
		{{
			localization = 1;
			downsampleXYIndex = 2;
			downsampleZ = 1;
			minIntensity = Double.NaN;
			maxIntensity = Double.NaN;
			sigma = 1.8;
			findMin = false;
			findMax = true;
			thresholds = new double[] { 0.004, 0.02 };
			threshold = 0.004;
		}};

		assertEquals( Arrays.asList( "_t0.004", "_t0.02" ), gui.getLabelSuffixes() );
		assertTrue( gui.getParameters( "_t0.02" ).contains( " t=0.02 " ) );
		assertTrue( gui.getParameters( "_t0.004" ).contains( " t=0.004 " ) );
		assertTrue( gui.getParameters().contains( " t=0.004 " ) );
		assertEquals( "0.02", gui.describeParameters( "_t0.02" ).get( "threshold" ) );
		assertEquals( "0.004;0.02", gui.describeParameters( "_t0.02" ).get( "thresholds" ) );
		assertEquals( "1.8", gui.describeParameters( "_t0.02" ).get( "sigma" ) );

		final TimePoint tp = spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 );
		final LinkedHashMap< String, HashMap< ViewId, List< InterestPoint > > > perSuffix = gui.findInterestPointsPerSuffix( tp );

		assertEquals( gui.getLabelSuffixes(), new ArrayList<>( perSuffix.keySet() ) );

		final DoGParameters dog = driverParameters( spimData, vd, false );

		for ( int i = 0; i < thresholds.length; ++i )
		{
			dog.threshold = thresholds[ i ];
			assertSamePoints( DoG.findInterestPoints( dog ).get( vd ), perSuffix.get( gui.getLabelSuffixes().get( i ) ).get( vd ), "GUI, threshold " + thresholds[ i ] );
		}

		// findInterestPoints is the first label
		assertSamePoints( perSuffix.get( "_t0.004" ).get( vd ), gui.findInterestPoints( tp ).get( vd ), "GUI, first label" );

		// a single threshold keeps the plain label and records no list of thresholds
		final DifferenceOfGaussianGUI single = new DifferenceOfGaussianGUI( spimData, views )
		{{
			downsampleXYIndex = 2;
			sigma = 1.8;
			threshold = 0.02;
		}};

		assertEquals( Arrays.asList( "" ), single.getLabelSuffixes() );
		assertTrue( single.getParameters( "" ).contains( " t=0.02 " ) );
		assertEquals( "0.02", single.describeParameters( "" ).get( "threshold" ) );
		assertFalse( single.describeParameters( "" ).containsKey( "thresholds" ) );
	}

	protected static void assertSamePoints( final List< ? extends InterestPoint > expected, final List< ? extends InterestPoint > actual, final String message )
	{
		assertEquals( expected.size(), actual.size(), message );

		for ( int j = 0; j < expected.size(); ++j )
		{
			final InterestPointValue a = (InterestPointValue)expected.get( j ), b = (InterestPointValue)actual.get( j );

			assertEquals( a.getId(), b.getId(), message + ", point " + j );
			assertEquals( a.getIntensity(), b.getIntensity(), 0.0, message + ", point " + j );

			for ( int d = 0; d < 3; ++d )
				assertEquals( a.getL()[ d ], b.getL()[ d ], 0.0, message + ", point " + j );
		}
	}
}
