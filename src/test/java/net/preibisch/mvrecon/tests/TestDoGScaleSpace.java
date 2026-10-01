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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewDescription;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointSS;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointValue;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGParameters;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGScaleSpace.Octave;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGScaleSpace.ScaleSpacePeak;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.ScaleSpaceParameters;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyGauss;
import util.ImgLib2Tools;

public class TestDoGScaleSpace
{
	/**
	 * The partition rule: pixel p of octave o belongs to the interval iff 2^o * p lies inside the
	 * interval of octave 0, adjacent intervals stay adjacent at every octave
	 */
	@Test
	public void testOctaveInterval()
	{
		final FinalInterval image = new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 511, 511, 85 } );
		final FinalInterval block = new FinalInterval( new long[] { 256, 0, 0 }, new long[] { 511, 255, 85 } );
		final FinalInterval other = new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 255, 255, 85 } );

		assertEquals( image, DoGScaleSpace.octaveInterval( image, 0 ) );
		assertEquals( new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 255, 255, 42 } ), DoGScaleSpace.octaveInterval( image, 1 ) );
		assertEquals( new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 127, 127, 21 } ), DoGScaleSpace.octaveInterval( image, 2 ) );
		assertEquals( new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 63, 63, 10 } ), DoGScaleSpace.octaveInterval( image, 3 ) );

		assertEquals( new FinalInterval( new long[] { 128, 0, 0 }, new long[] { 255, 127, 42 } ), DoGScaleSpace.octaveInterval( block, 1 ) );
		assertEquals( new FinalInterval( new long[] { 64, 0, 0 }, new long[] { 127, 63, 21 } ), DoGScaleSpace.octaveInterval( block, 2 ) );
		assertEquals( new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 127, 127, 42 } ), DoGScaleSpace.octaveInterval( other, 1 ) );
		assertEquals( new FinalInterval( new long[] { 0, 0, 0 }, new long[] { 63, 63, 21 } ), DoGScaleSpace.octaveInterval( other, 2 ) );

		// random adjacent 1d intervals [a,b] and [b+1,c] tile at every octave
		final Random rnd = new Random( 35 );

		for ( int i = 0; i < 1000; ++i )
		{
			final long a = rnd.nextInt( 100 ) - 50;
			final long b = a + rnd.nextInt( 300 );
			final long c = b + 1 + rnd.nextInt( 300 );

			for ( int o = 0; o < 6; ++o )
			{
				final FinalInterval i1 = DoGScaleSpace.octaveInterval( new FinalInterval( new long[] { a }, new long[] { b } ), o );
				final FinalInterval i2 = DoGScaleSpace.octaveInterval( new FinalInterval( new long[] { b + 1 }, new long[] { c } ), o );
				final FinalInterval both = DoGScaleSpace.octaveInterval( new FinalInterval( new long[] { a }, new long[] { c } ), o );

				assertEquals( both.min( 0 ), i1.min( 0 ) );
				assertEquals( both.max( 0 ), i2.max( 0 ) );
				assertEquals( i1.max( 0 ) + 1, i2.min( 0 ) );
			}
		}
	}

	/**
	 * With steps = 4 (k = 2^(1/4)) the Gaussian levels 1 and 2 and the DoG level 1 of octave 0 are the
	 * images of the single-scale DoG (DoGImgLib2.computeDoG), bit by bit
	 */
	@Test
	public void testLegacyImagesIdentical()
	{
		final ExecutorService service = Threads.createFixedExecutorService( 1 );
		final RandomAccessibleInterval< FloatType > img = openSimulatedView();
		final Interval interval = new FinalInterval( img );

		// the single-scale DoG, as in DoGImgLib2.computeDoG
		final RandomAccessible< FloatType > inputFloat = ImgLib2Tools.normalizeVirtual( Views.extendMirrorSingle( img ), 0, 1137 );
		final Pair< double[][], Float > sigmas = DoGImgLib2.computeSigmas( 1.1f, 3 );
		final float kMin1Inv = sigmas.getB();

		final RandomAccessibleInterval< FloatType > gauss1 = LazyGauss.init( inputFloat, interval, new FloatType(), sigmas.getA()[ 0 ], DoGImgLib2.blockSize );
		final RandomAccessibleInterval< FloatType > gauss2 = LazyGauss.init( inputFloat, interval, new FloatType(), sigmas.getA()[ 1 ], DoGImgLib2.blockSize );
		final RandomAccessibleInterval< FloatType > dog = Converters.convert( gauss2, gauss1, ( iA, iB, o ) -> o.setReal( ( iA.getRealDouble() - iB.getRealDouble() ) * kMin1Inv ), new FloatType() );

		// octave 0 of the scale space, with a different cell size
		final ScaleSpaceParameters p = new ScaleSpaceParameters( 1.1, 4, 1, 0.01 );
		p.minIntensity = 0;
		p.maxIntensity = 1137;
		p.cellSize = new int[] { 37, 41, 23 };

		final List< Octave > octaves = DoGScaleSpace.buildScaleSpace( Views.extendMirrorSingle( img ), interval, interval, null, p, service );

		assertEquals( 1, octaves.size() );

		assertIdentical( gauss1, octaves.get( 0 ).gauss.get( 1 ) );
		assertIdentical( gauss2, octaves.get( 0 ).gauss.get( 2 ) );
		assertIdentical( dog, octaves.get( 0 ).dog.get( 1 ) );

		service.shutdown();
	}

	/**
	 * The spatial extrema of DoG level 1 of octave 0 (steps = 4) are the peaks of the single-scale DoG;
	 * the only difference is the duplicate filter of computeDoG, which we apply to our list
	 */
	@Test
	public void testLegacyPeaksIdentical()
	{
		final ExecutorService service = Threads.createFixedExecutorService( 1 );
		final RandomAccessibleInterval< FloatType > img = openSimulatedView();
		final Interval interval = new FinalInterval( img );

		final List< InterestPoint > legacy = DoGImgLib2.computeDoG(
				Views.extendMirrorSingle( img ), null, interval,
				TestInterestPointDetection.STANDARD_SIGMA, TestInterestPointDetection.STANDARD_THRESHOLD, 1, false, true,
				TestInterestPointDetection.GLOBAL_MIN_INTENSITY, TestInterestPointDetection.GLOBAL_MAX_INTENSITY,
				DoGImgLib2.blockSize, service, null, null, false, 0 );

		final ScaleSpaceParameters p = new ScaleSpaceParameters( TestInterestPointDetection.STANDARD_SIGMA, 4, 1, TestInterestPointDetection.STANDARD_THRESHOLD );
		p.minIntensity = TestInterestPointDetection.GLOBAL_MIN_INTENSITY;
		p.maxIntensity = TestInterestPointDetection.GLOBAL_MAX_INTENSITY;

		final List< Octave > octaves = DoGScaleSpace.buildScaleSpace( Views.extendMirrorSingle( img ), interval, interval, null, p, service );
		final List< InterestPoint > ours = legacyDuplicateFilter( DoGScaleSpace.detectSpatialExtrema( octaves.get( 0 ), 1, p, service ) );

		service.shutdown();

		assertEquals( TestInterestPointDetection.EXPECTED_COUNT_VIEW_0, legacy.size() );
		assertEquals( legacy.size(), ours.size() );

		for ( int i = 0; i < legacy.size(); ++i )
		{
			for ( int d = 0; d < 3; ++d )
				assertEquals( legacy.get( i ).getL()[ d ], ours.get( i ).getL()[ d ], 0.0 );

			assertEquals( ( (InterestPointValue)legacy.get( i ) ).getIntensity(), ( (InterestPointValue)ours.get( i ) ).getIntensity(), 0.0 );
		}
	}

	/**
	 * With detectFinestLevel (the default) the single-scale detections are a subset of the scale-space
	 * detections also for structures smaller than sigmaMin (the simulated beads are 1x1x3 pixels at
	 * full resolution), without it only few of them are found
	 */
	@Test
	public void testFinestLevelContainsLegacy()
	{
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );
		final RandomAccessibleInterval< FloatType > img = openSimulatedView();
		final Interval interval = new FinalInterval( img );

		final List< InterestPoint > legacy = DoGImgLib2.computeDoG(
				Views.extendMirrorSingle( img ), null, interval,
				TestInterestPointDetection.STANDARD_SIGMA, TestInterestPointDetection.STANDARD_THRESHOLD, 1, false, true,
				TestInterestPointDetection.GLOBAL_MIN_INTENSITY, TestInterestPointDetection.GLOBAL_MAX_INTENSITY,
				DoGImgLib2.blockSize, service, null, null, false, 0 );

		final ScaleSpaceParameters p = new ScaleSpaceParameters( TestInterestPointDetection.STANDARD_SIGMA, 3, -1, TestInterestPointDetection.STANDARD_THRESHOLD );
		p.minIntensity = TestInterestPointDetection.GLOBAL_MIN_INTENSITY;
		p.maxIntensity = TestInterestPointDetection.GLOBAL_MAX_INTENSITY;

		p.detectFinestLevel = true;
		final ArrayList< InterestPointSS > withFinest = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), interval, interval, null, p, service );

		p.detectFinestLevel = false;
		final ArrayList< InterestPointSS > lowe = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), interval, interval, null, p, service );

		service.shutdown();

		final double fractionWithFinest = fractionWithin( legacy, withFinest, 0.5 );
		final double fractionLowe = fractionWithin( legacy, lowe, 0.5 );

		IOFunctions.println( "legacy peaks found within 0.5 px: with finest level " + fractionWithFinest + " (" + withFinest.size() + " peaks), Lowe only " + fractionLowe + " (" + lowe.size() + " peaks)" );

		assertEquals( TestInterestPointDetection.EXPECTED_COUNT_VIEW_0, legacy.size() );
		assertTrue( fractionWithFinest >= 0.95, "with finest level: " + fractionWithFinest );
		assertTrue( fractionLowe < 0.5, "Lowe only: " + fractionLowe );

		// the finest-level points carry sigma = sigmaMin
		int atSigmaMin = 0;

		for ( final InterestPointSS peak : withFinest )
			if ( Math.abs( peak.getSigma() - p.sigmaMin ) < 1e-6 )
				++atSigmaMin;

		assertTrue( atSigmaMin >= withFinest.size() - lowe.size(), "finest-level points have sigma = sigmaMin" );
	}

	/**
	 * @return the fraction of points in a that have a point in b within distance
	 */
	public static double fractionWithin( final List< ? extends InterestPoint > a, final List< ? extends InterestPoint > b, final double distance )
	{
		if ( a.isEmpty() || b.isEmpty() )
			return 0;

		final NearestNeighborSearchOnKDTree< InterestPoint > search = new NearestNeighborSearchOnKDTree<>( new KDTree<>( new ArrayList< InterestPoint >( b ), new ArrayList< InterestPoint >( b ) ) );
		int found = 0;

		for ( final InterestPoint p : a )
		{
			search.search( p );

			if ( search.getDistance() <= distance )
				++found;
		}

		return (double)found / a.size();
	}

	/**
	 * Gaussian blobs of sigma 2, 4, 8, 16 in one image: each is found exactly once, in successive octaves,
	 * with the same response, the same ratio of detected sigma to blob sigma, and at the right position.
	 * At sigmaMin = 1.0 the finest octave works with Gaussian kernels of sigma ~1 pixel, where the sampled
	 * kernels overestimate the DoG response by up to ~10% (verified against the continuous solution),
	 * so the response tolerance is 15% here and 10% for sigmaMin = 1.5 in the second configuration.
	 */
	@Test
	public void testScaleInvariance()
	{
		testScaleInvariance(
				1.0,
				new long[] { 384, 160, 160 },
				new double[] { 2, 4, 8, 16 },
				new double[][] { { 50.3, 80.2, 79.6 }, { 120.7, 79.4, 80.3 }, { 200.2, 80.6, 79.8 }, { 300.4, 79.7, 80.4 } },
				0.15 );

		testScaleInvariance(
				1.5,
				new long[] { 384, 192, 192 },
				new double[] { 3, 6, 12 },
				new double[][] { { 50.3, 96.2, 95.6 }, { 130.7, 95.4, 96.3 }, { 250.2, 96.6, 95.8 } },
				0.10 );
	}

	public static void testScaleInvariance( final double sigmaMin, final long[] dim, final double[] stds, final double[][] centers, final double responseTolerance )
	{
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final Img< FloatType > img = blobs( dim, centers, stds );

		final ScaleSpaceParameters p = new ScaleSpaceParameters( sigmaMin, 3, -1, 0.1 );
		p.minIntensity = 0;
		p.maxIntensity = 1;

		assertTrue( DoGScaleSpace.autoOctaves( img, p ) >= stds.length, "number of octaves" );

		final ArrayList< InterestPointSS > peaks = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), img, img, null, p, service );

		service.shutdown();

		for ( final InterestPointSS peak : peaks )
			IOFunctions.println( "sigmaMin=" + sigmaMin + " peak: " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );

		assertEquals( stds.length, peaks.size(), "number of peaks" );

		peaks.sort( Comparator.comparingDouble( peak -> peak.getL()[ 0 ] ) );

		final double[] ratios = new double[ stds.length ];

		for ( int i = 0; i < stds.length; ++i )
		{
			final InterestPointSS peak = peaks.get( i );
			final double f = 1L << i; // blob i is expected in octave i

			for ( int d = 0; d < 3; ++d )
				assertEquals( centers[ i ][ d ], peak.getL()[ d ], 0.1 * f, "position of blob " + i );

			// the image carries imageSigma (0.5) blur by definition, so a rendered sigma s is a scene sigma sqrt( s^2 - 0.25 )
			ratios[ i ] = peak.getSigma() / Math.sqrt( stds[ i ] * stds[ i ] - 0.25 );

			final double sigmaMinOctave = DoGScaleSpace.sigmaBase( p, i, 0.5 );
			final double sigmaMaxOctave = DoGScaleSpace.sigmaBase( p, i, p.steps + 0.5 );
			assertTrue( peak.getSigma() >= sigmaMinOctave && peak.getSigma() <= sigmaMaxOctave, "blob " + i + " detected in octave " + i + ": sigma=" + peak.getSigma() );

			assertTrue( peak.getResponse() < -0.1, "bright blob is a DoG minimum" );
		}

		for ( int i = 1; i < stds.length; ++i )
		{
			assertEquals( peaks.get( 0 ).getResponse(), peaks.get( i ).getResponse(), responseTolerance * Math.abs( peaks.get( 0 ).getResponse() ), "response of blob " + i );
			assertEquals( ratios[ 0 ], ratios[ i ], 0.15 * ratios[ 0 ], "sigma ratio of blob " + i );
		}
	}

	/**
	 * Processing an image in blocks gives the same peaks as processing it at once
	 */
	@Test
	public void testBlockTiling()
	{
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final double[] stds = new double[] { 2, 4, 8, 16 };
		final double[][] centers = new double[][] { { 50.3, 80.2, 79.6 }, { 120.7, 79.4, 80.3 }, { 200.2, 80.6, 79.8 }, { 300.4, 79.7, 80.4 } };
		final Img< FloatType > img = blobs( new long[] { 384, 160, 160 }, centers, stds );

		final ScaleSpaceParameters p = new ScaleSpaceParameters( 1.0, 3, -1, 0.1 );
		p.minIntensity = 0;
		p.maxIntensity = 1;

		final ArrayList< ScaleSpacePeak > whole = DoGScaleSpace.computeScaleSpacePeaks( Views.extendMirrorSingle( img ), img, img, null, p, service );

		final long[][] splits = new long[][] { { 191 }, { 130, 250 }, { 63, 64, 300 } };

		for ( final long[] split : splits )
		{
			final ArrayList< ScaleSpacePeak > blocks = new ArrayList<>();
			long from = 0;

			for ( int b = 0; b <= split.length; ++b )
			{
				final long to = b < split.length ? split[ b ] : img.max( 0 );
				final Interval block = new FinalInterval( new long[] { from, 0, 0 }, new long[] { to, img.max( 1 ), img.max( 2 ) } );

				blocks.addAll( DoGScaleSpace.computeScaleSpacePeaks( Views.extendMirrorSingle( img ), img, block, null, p, service ) );
				from = to + 1;
			}

			final ArrayList< ScaleSpacePeak > merged = DoGScaleSpace.mergeDuplicates( blocks, p.combineDistance );

			assertEquals( whole.size(), merged.size(), "number of peaks for split " + Util.printCoordinates( split ) );

			whole.sort( Comparator.comparingDouble( peak -> peak.l[ 0 ] ) );
			merged.sort( Comparator.comparingDouble( peak -> peak.l[ 0 ] ) );

			for ( int i = 0; i < whole.size(); ++i )
			{
				for ( int d = 0; d < 3; ++d )
					assertEquals( whole.get( i ).l[ d ], merged.get( i ).l[ d ], 1e-6 );

				assertEquals( whole.get( i ).sigma, merged.get( i ).sigma, 1e-6 );
				assertEquals( whole.get( i ).value, merged.get( i ).value, 1e-6 );
				assertEquals( whole.get( i ).octave, merged.get( i ).octave );
			}
		}

		service.shutdown();
	}

	/**
	 * The whole-view driver (DoG.findInterestPoints with scaleSpace = true) returns InterestPointSS mapped
	 * to full resolution, identical to the shared helper plus DownsampleTools.correctForDownsampling
	 */
	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void testDriver()
	{
		// isotropic beads of sigma 2 (the beads of SimulateUtil are 1x1x3 pixels, too small for a scale space at full resolution)
		final SpimData2 spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[] { 0, 90 }, 0, 100, new double[] { 2, 2, 2 }, new FinalInterval( 128, 128, 64 ) ) );
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );

		final DoGParameters dog = new DoGParameters();
		dog.imgloader = spimData.getSequenceDescription().getImgLoader();
		dog.toProcess = new ArrayList<>();
		dog.toProcess.add( vd );
		dog.sigma = 1.5;
		dog.threshold = 0.01;
		dog.downsampleXY = 1;
		dog.downsampleZ = 1;
		dog.minIntensity = Double.NaN; // from the image
		dog.maxIntensity = Double.NaN;
		dog.scaleSpace = true;
		dog.scaleSpaceParameters.steps = 3;
		dog.scaleSpaceParameters.octaves = -1;

		final List< InterestPoint > driver = DoG.findInterestPoints( dog ).get( vd );

		// the same through the shared helper
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final Pair< RandomAccessibleInterval, AffineTransform3D > input =
				DownsampleTools.openAndDownsample( dog.imgloader, vd, new long[] { dog.downsampleXY, dog.downsampleXY, dog.downsampleZ }, false );

		final ArrayList< InterestPointSS > helper = DoG.computeScaleSpace( (RandomAccessible)Views.extendMirrorSingle( input.getA() ), null, new FinalInterval( input.getA() ), new FinalInterval( input.getA() ), dog, service );

		service.shutdown();

		final double sigmaScale = Math.sqrt( Math.abs( input.getB().get( 0, 0 ) * input.getB().get( 1, 1 ) ) );
		final ArrayList< double[] > expectedSigmas = new ArrayList<>();

		for ( final InterestPointSS ip : helper )
			expectedSigmas.add( new double[] { ip.getSigma() * sigmaScale } );

		DownsampleTools.correctForDownsampling( helper, input.getB() );

		assertTrue( driver.size() > 10, "found " + driver.size() + " points" );
		assertEquals( helper.size(), driver.size() );

		final long[] dim = vd.getViewSetup().getSize().dimensionsAsLongArray();

		for ( int i = 0; i < driver.size(); ++i )
		{
			assertTrue( InterestPointSS.class.isInstance( driver.get( i ) ) );

			final InterestPointSS d = (InterestPointSS)driver.get( i );
			final InterestPointSS h = helper.get( i );

			assertEquals( h.getId(), d.getId() );
			assertEquals( h.getSigma(), d.getSigma(), 0.0 );
			assertEquals( expectedSigmas.get( i )[ 0 ], d.getSigma(), 1e-12 );
			assertEquals( h.getResponse(), d.getResponse(), 0.0 );

			for ( int dd = 0; dd < 3; ++dd )
			{
				assertEquals( h.getL()[ dd ], d.getL()[ dd ], 0.0 );
				assertTrue( d.getL()[ dd ] >= -1 && d.getL()[ dd ] <= dim[ dd ], "inside the view: " + Util.printCoordinates( d.getL() ) );
			}

			// the sigma in full resolution lies in the range of the scale space (in full-resolution pixels)
			final double sigmaMinFull = DoGScaleSpace.sigmaBase( DoG.toScaleSpaceParameters( dog ), 0, 0.5 ) * sigmaScale;
			assertTrue( d.getSigma() >= sigmaMinFull * 0.99, "sigma " + d.getSigma() + " >= " + sigmaMinFull );
		}
	}

	public static Img< FloatType > blobs( final long[] dim, final double[][] centers, final double[] stds )
	{
		final Img< FloatType > img = ArrayImgs.floats( dim );
		final RandomAccess< FloatType > ra = img.randomAccess();

		for ( int i = 0; i < stds.length; ++i )
		{
			final double s = stds[ i ];
			final double[] c = centers[ i ];
			final long[] min = new long[ 3 ];
			final long[] max = new long[ 3 ];

			for ( int d = 0; d < 3; ++d )
			{
				min[ d ] = Math.max( 0, Math.round( c[ d ] - 4 * s ) );
				max[ d ] = Math.min( dim[ d ] - 1, Math.round( c[ d ] + 4 * s ) );
			}

			final Cursor< FloatType > cursor = Views.interval( img, new FinalInterval( min, max ) ).localizingCursor();

			while ( cursor.hasNext() )
			{
				cursor.fwd();

				double r2 = 0;

				for ( int d = 0; d < 3; ++d )
				{
					final double diff = cursor.getDoublePosition( d ) - c[ d ];
					r2 += diff * diff;
				}

				cursor.get().set( cursor.get().get() + (float)Math.exp( -r2 / ( 2 * s * s ) ) );
			}
		}

		return img;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static RandomAccessibleInterval< FloatType > openSimulatedView()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );

		final Pair< RandomAccessibleInterval, AffineTransform3D > input =
				DownsampleTools.openAndDownsample(
						spimData.getSequenceDescription().getImgLoader(),
						vd,
						new long[] { TestInterestPointDetection.STANDARD_DOWNSAMPLE_XY, TestInterestPointDetection.STANDARD_DOWNSAMPLE_XY, TestInterestPointDetection.STANDARD_DOWNSAMPLE_Z },
						false );

		// copy into a float image so that both detectors see identical values
		final Img< FloatType > img = ArrayImgs.floats( Intervals.dimensionsAsLongArray( input.getA() ) );
		final Cursor< FloatType > out = img.cursor();
		final Cursor< FloatType > in = Views.flatIterable( Converters.convertRAI( input.getA(), ( i, o ) -> ( (FloatType)o ).set( ( (net.imglib2.type.numeric.RealType)i ).getRealFloat() ), new FloatType() ) ).cursor();

		while ( out.hasNext() )
			out.next().set( in.next() );

		return img;
	}

	/**
	 * The duplicate filter of DoGImgLib2.computeDoG (it keeps the later of two near-identical points and never the last point)
	 */
	public static List< InterestPoint > legacyDuplicateFilter( final List< InterestPoint > peaks )
	{
		final ArrayList< InterestPoint > filtered = new ArrayList<>();

		for ( int i = 0; i < peaks.size() - 1; ++i )
		{
			final double[] v1 = peaks.get( i ).getL();
			boolean different = true;

			for ( int j = i + 1; different && j < peaks.size(); ++j )
			{
				final double[] v2 = peaks.get( j ).getL();
				different = false;

				for ( int d = 0; d < v1.length; ++d )
					if ( Math.abs( v1[ d ] - v2[ d ] ) > 0.001 )
						different = true;
			}

			if ( different )
				filtered.add( peaks.get( i ) );
		}

		return filtered;
	}

	public static void assertIdentical( final RandomAccessibleInterval< FloatType > a, final RandomAccessibleInterval< FloatType > b )
	{
		assertEquals( new FinalInterval( a ), new FinalInterval( b ) );

		final Cursor< FloatType > ca = Views.flatIterable( a ).cursor();
		final Cursor< FloatType > cb = Views.flatIterable( b ).cursor();

		while ( ca.hasNext() )
			assertEquals( ca.next().get(), cb.next().get(), 0.0f );
	}
}
