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
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointdetection.NoiseFloor;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

/**
 * The noise floor (NoiseFloor): the robust statistics, the estimate on white noise against the analytic response of the
 * DoG kernel, its robustness to beads, the pooled estimate over views and the dialogs that resolve a threshold from it
 */
public class TestNoiseFloor
{
	@Test
	public void testRobustStd()
	{
		assertEquals( 3.0, NoiseFloor.median( new float[] { 1, 2, 3, 4, 100 } ), 0.0 );
		assertEquals( 2.5, NoiseFloor.median( new float[] { 4, 1, 2, 3 } ), 0.0 );
		assertTrue( Double.isNaN( NoiseFloor.median( new float[ 0 ] ) ) );

		// median 3, deviations 2 1 0 1 97 -> MAD 1
		final double[] stats = NoiseFloor.robustStd( new float[] { 1, 2, 3, 4, 100 } );
		assertEquals( NoiseFloor.mad2std, stats[ 0 ], 1e-12 );
		assertEquals( 3.0, stats[ 1 ], 0.0 );

		// a normal distribution: the robust std is the std (within 2% for a million samples)
		final Random rnd = new Random( 3 );
		final float[] normal = new float[ 1000000 ];

		for ( int i = 0; i < normal.length; ++i )
			normal[ i ] = (float)( 10 + 2.5 * rnd.nextGaussian() );

		final double[] n = NoiseFloor.robustStd( normal );
		assertEquals( 2.5, n[ 0 ], 0.05 );
		assertEquals( 10.0, n[ 1 ], 0.02 );
	}

	/**
	 * White noise of a known std: the floor is the std of the DoG response, which the kernel norm gives analytically
	 * (the L2 norm of g2 - g1 for Gaussians g_a, g_b in 3D: int g_a g_b = (2 pi (a^2 + b^2))^(-3/2)), times 1 / (k - 1);
	 * sixteen random blocks reproduce the floor of all blocks; beads do not move it; the same seed gives the same estimate
	 */
	@Test
	public void testWhiteNoise()
	{
		final long[] dim = new long[] { 192, 160, 96 };
		final double sigmaNoise = 10, mean = 100;
		final Img< FloatType > noise = ArrayImgs.floats( dim );
		final Random rnd = new Random( 5 );

		for ( final FloatType t : noise )
			t.set( (float)( mean + sigmaNoise * rnd.nextGaussian() ) );

		final double min = 0, max = 200; // normalized std = 0.05
		final double sigma = 3.0;
		final float k = LaPlaceFunctions.computeK( 4 );
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final NoiseFloor.Estimate sampled = NoiseFloor.estimate( noise, min, max, sigma, k, null, service );
		final NoiseFloor.Estimate all = NoiseFloor.estimate( noise, min, max, sigma, k, null, Integer.MAX_VALUE, NoiseFloor.defaultBlockSize, 1, service );
		final NoiseFloor.Estimate again = NoiseFloor.estimate( noise, min, max, sigma, k, null, service );

		// the analytic std of the DoG of white noise with the applied sigmas
		final double[][] sigmas = DoGImgLib2.computeSigmas( (float)sigma, k, null, 3 ).getA();
		final double a = sigmas[ 0 ][ 0 ], b = sigmas[ 1 ][ 0 ];
		final double norm2 = overlap( a, a ) - 2 * overlap( a, b ) + overlap( b, b );
		final double expected = ( sigmaNoise / ( max - min ) ) * Math.sqrt( norm2 ) / ( k - 1 );

		IOFunctions.println( "white noise: floor sampled " + sampled + ", all blocks " + all + ", analytic " + expected );

		assertEquals( expected, all.floor, 0.05 * expected, "all blocks vs analytic" );
		assertEquals( all.floor, sampled.floor, 0.05 * all.floor, "sampled vs all blocks" );
		assertEquals( sampled.floor, again.floor, 0.0, "the same seed" );
		assertEquals( 16, sampled.numBlocks );
		assertTrue( sampled.numSamples > 100000 );
		assertEquals( 0.0, sampled.median, 0.002 );

		// bright beads barely move the robust estimate as long as they (with the tails of their DoG response) are a minority
		// of the voxels: 20 beads of the full range in 3M voxels touch a few percent of them with the tails of their DoG
		// response, which shifts the median of |DoG| by a few percent (300 beads of five times the range would touch
		// everything and double the floor; a robust statistic is robust to a minority, not to a texture everywhere)
		final Img< FloatType > beads = noise.copy();
		final ArrayList< double[] > centers = new ArrayList<>();

		for ( int i = 0; i < 20; ++i )
			centers.add( new double[] { 8 + rnd.nextDouble() * ( dim[ 0 ] - 16 ), 8 + rnd.nextDouble() * ( dim[ 1 ] - 16 ), 8 + rnd.nextDouble() * ( dim[ 2 ] - 16 ) } );

		addBlobs( beads, centers, 2.0, max - min );

		final NoiseFloor.Estimate withBeads = NoiseFloor.estimate( beads, min, max, sigma, k, null, service );
		IOFunctions.println( "with 20 beads: " + withBeads );
		assertEquals( sampled.floor, withBeads.floor, 0.1 * sampled.floor, "beads do not move the floor" );

		// more blur, less noise: a larger sigma lowers the floor
		final NoiseFloor.Estimate coarse = NoiseFloor.estimate( noise, min, max, 2 * sigma, k, null, service );
		assertTrue( coarse.floor < 0.5 * sampled.floor, "sigma 6: " + coarse.floor + " vs sigma 3: " + sampled.floor );

		// an anisotropy with more dimensions than the image is cut (a 2d preview), an empty range is refused
		final Img< FloatType > slice = ArrayImgs.floats( 128, 128 );
		for ( final FloatType t : slice ) t.set( (float)( mean + sigmaNoise * rnd.nextGaussian() ) );
		assertTrue( NoiseFloor.estimate( slice, min, max, sigma, k, new double[] { 1, 1, 2 }, service ).floor > 0 );
		assertThrows( IllegalArgumentException.class, () -> NoiseFloor.estimate( noise, 5, 5, sigma, k, null, service ) );

		service.shutdown();
	}

	/** int g_a g_b over 3D for unit-integral Gaussians */
	protected static double overlap( final double a, final double b )
	{
		return Math.pow( 2 * Math.PI * ( a * a + b * b ), -1.5 );
	}

	protected static void addBlobs( final Img< FloatType > img, final ArrayList< double[] > centers, final double s, final double amplitude )
	{
		final Cursor< FloatType > cursor = img.localizingCursor();

		while ( cursor.hasNext() )
		{
			cursor.fwd();

			for ( final double[] c : centers )
			{
				final double dx = cursor.getDoublePosition( 0 ) - c[ 0 ], dy = cursor.getDoublePosition( 1 ) - c[ 1 ], dz = cursor.getDoublePosition( 2 ) - c[ 2 ];
				final double r2 = dx * dx + dy * dy + dz * dz;

				if ( r2 < 36 * s * s )
					cursor.get().set( cursor.get().get() + (float)( amplitude * Math.exp( -r2 / ( 2 * s * s ) ) ) );
			}
		}
	}

	/**
	 * The views of a dataset: one floor per view, the pooled one is their median, the threshold a multiple of it; and the
	 * dialogs resolve their threshold from it (both methods)
	 */
	@Test
	public void testViewsAndDialogs()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		final ArrayList< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final NoiseFloor.Pooled pooled = NoiseFloor.estimate(
				spimData.getSequenceDescription().getImgLoader(),
				NoiseFloor.presentViews( spimData, views ),
				new long[] { 1, 1, 1 },
				1.8,
				LaPlaceFunctions.computeK( 4 ),
				( vd, t ) -> DownsampleTools.anisotropy( vd, t, 1.0 ),
				Double.NaN,
				Double.NaN,
				NoiseFloor.defaultNumBlocks,
				NoiseFloor.defaultBlockSize,
				NoiseFloor.defaultSeed,
				service );

		service.shutdown();

		IOFunctions.println( pooled.describe() );

		assertEquals( views.size(), pooled.perView.size() );

		// the simulated views have (almost) no noise: a floor can be 0 (a view without any), the pooled floor is still > 0 here
		for ( final Map.Entry< ViewId, NoiseFloor.Estimate > e : pooled.perView.entrySet() )
			assertTrue( e.getValue().floor >= 0 && Double.isFinite( e.getValue().floor ), e.getKey() + ": " + e.getValue() );

		// the pooled floor is the highest one (one threshold has to clear the most textured view), the median is only reported
		assertTrue( pooled.floor() > 0 && pooled.floor() == pooled.max() && pooled.median() >= pooled.min() && pooled.median() <= pooled.max(), pooled.describe() );
		assertEquals( 2.5 * pooled.floor(), pooled.threshold( 2.5 ), 1e-12 );

		// the DoG dialog, headless: "Estimate from noise floor" sets the threshold to the factor x the pooled floor at the
		// dialog's sigma; only the threshold is recorded (the detection and Spark see a fixed number)
		final TimePoint tp = spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 );
		final double[] resolved = new double[ 1 ];

		final DifferenceOfGaussianGUI dog = new DifferenceOfGaussianGUI( spimData, views )
		{{
			localization = 1;
			downsampling = new long[] { 1, 1, 1 };
			anisotropyZ = 1.0;
			minIntensity = Double.NaN;
			maxIntensity = Double.NaN;
			sigma = 1.8;
			threshold = 0.123;
			noiseFactor = 2.5;
			findMin = false;
			findMax = true;
			assertTrue( thresholdFromNoiseFloor( sigma, 4 ) );
			resolved[ 0 ] = threshold;

			// the previews ask the same estimator for all views (null = the resolution of the dialog)
			assertEquals( views.size(), noiseEstimator().numViews() );
			assertEquals( pooled.floor(), noiseEstimator().estimate( null, 1.8, 4 ).floor(), 1e-12 );
		}};

		assertEquals( 2.5 * pooled.floor(), resolved[ 0 ], 1e-9, "the DoG dialog resolves 2.5 x the pooled floor" );
		assertFalse( dog.describeParameters().containsKey( "noiseFactor" ) );
		assertEquals( Double.toString( resolved[ 0 ] ), dog.describeParameters().get( "threshold" ) );
		assertTrue( dog.getParameters().contains( " t=" + resolved[ 0 ] + " " ) && !dog.getParameters().contains( "noise" ), dog.getParameters() );
		assertTrue( dog.findInterestPoints( tp ).get( views.get( 0 ) ).size() > 0 );

		// the scale space: its finest level at steps 4 is the DoG at the same sigma, so the same floor
		final double[] resolvedSS = new double[ 1 ];

		final ScaleSpaceGUI ss = new ScaleSpaceGUI( spimData, views )
		{{
			localization = 1;
			downsampling = new long[] { 1, 1, 1 };
			anisotropyZ = 1.0;
			minIntensity = Double.NaN;
			maxIntensity = Double.NaN;
			sigma = 1.8;
			threshold = 0.123;
			noiseFactor = 3.0;
			steps = 4;
			octaves = -1;
			detectFinestLevel = true;
			findMin = false;
			findMax = true;
			assertTrue( thresholdFromNoiseFloor( sigma, steps ) );
			resolvedSS[ 0 ] = threshold;
		}};

		assertEquals( 3.0 * pooled.floor(), resolvedSS[ 0 ], 1e-9, "the scale-space dialog resolves 3 x the same floor" );
		assertFalse( ss.describeParameters().containsKey( "noiseFactor" ) );
		assertEquals( Double.toString( resolvedSS[ 0 ] ), ss.describeParameters().get( "threshold" ) );

		// a factor that is not > 0 is refused and the threshold stays
		final DifferenceOfGaussianGUI bad = new DifferenceOfGaussianGUI( spimData, views )
		{{
			localization = 1;
			downsampling = new long[] { 1, 1, 1 };
			anisotropyZ = 1.0;
			sigma = 1.8;
			threshold = 0.123;
			noiseFactor = 0;
			assertFalse( thresholdFromNoiseFloor( sigma, 4 ) );
			assertEquals( 0.123, threshold, 0.0 );
		}};

		assertEquals( "0.123", bad.describeParameters().get( "threshold" ) );
		assertEquals( 2, DifferenceOfGUI.thresholdChoices.length );
		assertEquals( 2.5, DifferenceOfGUI.defaultNoiseFactor, 0.0 );
	}
}
