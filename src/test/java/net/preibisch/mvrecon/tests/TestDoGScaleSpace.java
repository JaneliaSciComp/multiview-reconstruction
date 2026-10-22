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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
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
import net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace.Candidates;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace.Octave;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.DoGScaleSpace.ScaleSpacePeak;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceParameters;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceDetectionParameters;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Detection;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.DifferenceOfGaussianGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.ScaleSpaceGUI;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.TimePoint;
import java.util.HashMap;
import java.util.Map;
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
		final List< InterestPoint > ours = DoGImgLib2.removeDuplicates( DoGScaleSpace.detectSpatialExtrema( octaves.get( 0 ), 1, p, service ) );

		service.shutdown();

		assertEquals( TestInterestPointDetection.EXPECTED_COUNT_VIEW_0, legacy.size() );
		assertEquals( legacy.size(), ours.size() );

		for ( int i = 0; i < legacy.size(); ++i )
		{
			for ( int d = 0; d < 3; ++d )
				assertEquals( legacy.get( i ).getL()[ d ], ours.get( i ).getL()[ d ], 0.0 );

			assertEquals( legacy.get( i ).getResponse(), ours.get( i ).getResponse(), 0.0 );
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
		final ArrayList< InterestPoint > withFinest = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), interval, interval, null, p, service );

		p.detectFinestLevel = false;
		final ArrayList< InterestPoint > lowe = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), interval, interval, null, p, service );

		service.shutdown();

		final double fractionWithFinest = fractionWithin( legacy, withFinest, 0.5 );
		final double fractionLowe = fractionWithin( legacy, lowe, 0.5 );

		IOFunctions.println( "legacy peaks found within 0.5 px: with finest level " + fractionWithFinest + " (" + withFinest.size() + " peaks), Lowe only " + fractionLowe + " (" + lowe.size() + " peaks)" );

		assertEquals( TestInterestPointDetection.EXPECTED_COUNT_VIEW_0, legacy.size() );
		assertTrue( fractionWithFinest >= 0.95, "with finest level: " + fractionWithFinest );
		assertTrue( fractionLowe < 0.5, "Lowe only: " + fractionLowe );

		// the finest-level points carry sigma <= sigmaMin (sigmaMin, or the fitted size below it)
		int atMostSigmaMin = 0;

		for ( final InterestPoint peak : withFinest )
			if ( peak.getSigma() <= p.sigmaMin + 1e-6 )
				++atMostSigmaMin;

		assertTrue( atMostSigmaMin >= withFinest.size() - lowe.size(), "finest-level points have sigma <= sigmaMin" );
	}

	/**
	 * The size of the finest-level detections (no extremum in scale) is fitted to the responses of the first levels:
	 * exact for model responses, and within 15% for synthetic Gaussian blobs of 0.5 .. 1.5 px in a 3d image
	 */
	@Test
	public void testFitBlobSize()
	{
		final int n = 3;
		final float k = LaPlaceFunctions.computeK( 4 );
		final float[] sigma = DoGScaleSpace.computeSigmas( 1.6, k, 4 );
		final double kMin1Inv = LaPlaceFunctions.computeKWeight( k );

		// model responses are recovered exactly, isotropic and anisotropic
		for ( final double[] anisotropy : new double[][] { null, { 1, 1, 2.5 } } )
		{
			final double[][] totalSigma = DoGScaleSpace.totalSigmaOctave0( sigma, anisotropy, 0.5, n );

			for ( final double b : new double[] { 0.3, 0.8, 1.5 } )
			{
				final double[] responses = new double[ 4 ];
				DoGScaleSpace.modelResponses( b, totalSigma, anisotropy, kMin1Inv, responses );

				for ( int i = 0; i < responses.length; ++i )
					responses[ i ] *= -3.7; // the mass of the blob

				final double[] fit = DoGScaleSpace.fitBlobSize( responses, totalSigma, anisotropy, kMin1Inv, 4.0 );

				assertEquals( b, fit[ 0 ], 0.01, "b = " + b );
				assertEquals( -3.7, fit[ 1 ], 0.05, "C for b = " + b );
				assertTrue( fit[ 2 ] < 5e-3, "residual for b = " + b + ": " + fit[ 2 ] );
			}
		}

		// the lower end of the range: the point-source profile (b = 0) is recovered exactly, and a profile that decays faster
		// than a point source ends at b = 0 as well (a boundary minimum, no size information; fitFinestSizes stores the image blur)
		{
			final double[][] totalSigma = DoGScaleSpace.totalSigmaOctave0( sigma, null, 0.5, n );
			final double[] pointSource = new double[ 4 ];
			DoGScaleSpace.modelResponses( 0, totalSigma, null, kMin1Inv, pointSource );

			final double[] fitPointSource = DoGScaleSpace.fitBlobSize( pointSource, totalSigma, null, kMin1Inv, 4.0 );
			assertEquals( 0.0, fitPointSource[ 0 ], 0.0, "a point source fits with b = 0" );
			assertTrue( fitPointSource[ 2 ] < 1e-6, "residual of the point source: " + fitPointSource[ 2 ] );

			final double[] fitFaster = DoGScaleSpace.fitBlobSize( new double[] { 1, 0.1, 0.01, 0.001 }, totalSigma, null, kMin1Inv, 4.0 );
			assertEquals( 0.0, fitFaster[ 0 ], 0.0, "a faster decay than a point source ends at the lower end of the range" );
		}

		// synthetic isotropic Gaussian blobs (the assumed image blur of 0.5 px is part of their width), all of them are
		// finest-level detections at sigmaMin 1.6 and their sigma is the fitted scale of maximal response, b * sqrt( 2 / 3 )
		final double[] sizes = new double[] { 0.5, 1.0, 1.5 };
		final double[][] centers = new double[][] { { 16.3, 20.6, 14.2 }, { 44.7, 18.4, 30.5 }, { 20.2, 50.1, 42.8 } };
		final Img< FloatType > img = ArrayImgs.floats( 64, 64, 56 );
		final Cursor< FloatType > c = img.localizingCursor();

		while ( c.hasNext() )
		{
			c.fwd();
			double v = 0;

			for ( int j = 0; j < sizes.length; ++j )
			{
				final double s2 = sizes[ j ] * sizes[ j ] + 0.25;
				double r2 = 0;

				for ( int d = 0; d < n; ++d )
					r2 += Math.pow( c.getDoublePosition( d ) - centers[ j ][ d ], 2 );

				v += Math.exp( -r2 / ( 2 * s2 ) );
			}

			c.get().set( (float)v );
		}

		final ScaleSpaceParameters p = new ScaleSpaceParameters( 1.6, 4, -1, 0.001 );
		p.minIntensity = 0;
		p.maxIntensity = 1;

		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );
		final ArrayList< ScaleSpacePeak > peaks = DoGScaleSpace.computeScaleSpacePeaks( Views.extendMirrorSingle( img ), new FinalInterval( img ), new FinalInterval( img ), null, p, service );
		service.shutdown();

		for ( int j = 0; j < sizes.length; ++j )
		{
			ScaleSpacePeak nearest = null;
			double nearestDistance = Double.MAX_VALUE;

			for ( final ScaleSpacePeak peak : peaks )
			{
				double dist = 0;

				for ( int d = 0; d < n; ++d )
					dist += Math.pow( peak.l[ d ] - centers[ j ][ d ], 2 );

				if ( dist < nearestDistance )
				{
					nearestDistance = dist;
					nearest = peak;
				}
			}

			final double expected = sizes[ j ] * Math.sqrt( 2.0 / n );

			IOFunctions.println( "blob size " + sizes[ j ] + ": peak at " + Util.printCoordinates( nearest.l ) + ", finest = " + nearest.finest + ", sigma = " + nearest.sigma + " (expected " + expected + ")" );

			assertTrue( Math.sqrt( nearestDistance ) < 1.0, "blob " + j + " found" );
			assertTrue( nearest.finest, "blob " + j + " is a finest-level detection" );
			assertEquals( expected, nearest.sigma, Math.max( 0.2, 0.15 * expected ), "fitted sigma of blob size " + sizes[ j ] );
		}

		// no finest-level peak has a sigma below the image blur
		for ( final ScaleSpacePeak peak : peaks )
			if ( peak.finest )
				assertTrue( peak.sigma >= p.imageSigma, "finest-level sigma " + peak.sigma + " below the image blur at " + Util.printCoordinates( peak.l ) );

		// a point source (one voxel), a blob smaller than the image blur and a zero-mean structure (its profile decays faster than
		// a point source) on a constant background: the first two get exactly the image blur, the third the image blur or the
		// fallback, none of them 0 (the fit runs into b = 0 for all three; a size of 0 was stored for 12-15% of the finest-level
		// points of ds1 before 2026-10-22)
		final double[] pointSource = new double[] { 16, 20, 14 }, tinyBlob = new double[] { 44.7, 18.4, 30.3 }, zeroMean = new double[] { 20, 50, 42 };
		final Img< FloatType > small = ArrayImgs.floats( 64, 64, 56 );
		final Cursor< FloatType > c2 = small.localizingCursor();

		while ( c2.hasNext() )
		{
			c2.fwd();
			double r2 = 0;

			for ( int d = 0; d < n; ++d )
				r2 += Math.pow( c2.getDoublePosition( d ) - tinyBlob[ d ], 2 );

			// background 0.5, the blob of size 0.25 (plus the image blur) with amplitude 0.5
			c2.get().set( (float)( 0.5 + 0.5 * Math.exp( -r2 / ( 2 * ( 0.25 * 0.25 + 0.25 ) ) ) ) );
		}

		final RandomAccess< FloatType > ra = small.randomAccess();
		ra.setPosition( new long[] { 16, 20, 14 } );
		ra.get().set( 1.0f );
		ra.setPosition( new long[] { 20, 50, 42 } );
		ra.get().set( 1.0f );

		for ( int d = 0; d < n; ++d )
			for ( final int step : new int[] { -1, 1 } )
			{
				ra.setPosition( new long[] { 20, 50, 42 } );
				ra.move( step, d );
				ra.get().set( 0.5f - 0.5f / 6 );
			}

		final ExecutorService service3 = Threads.createFixedExecutorService( Threads.numThreads() );
		final ArrayList< ScaleSpacePeak > smallPeaks = DoGScaleSpace.computeScaleSpacePeaks( Views.extendMirrorSingle( small ), new FinalInterval( small ), new FinalInterval( small ), null, p, service3 );
		service3.shutdown();

		for ( final ScaleSpacePeak peak : smallPeaks )
		{
			IOFunctions.println( "small structures: peak at " + Util.printCoordinates( peak.l ) + ", finest = " + peak.finest + ", sigma = " + peak.sigma + ", response = " + peak.value );

			if ( peak.finest )
				assertTrue( peak.sigma >= p.imageSigma, "finest-level sigma " + peak.sigma + " below the image blur at " + Util.printCoordinates( peak.l ) );
		}

		for ( final double[] center : new double[][] { pointSource, tinyBlob } )
		{
			final ScaleSpacePeak peak = nearestPeak( smallPeaks, center );
			assertTrue( peak != null && dist( peak.l, center ) < 1.0 && peak.finest, "a finest-level peak at " + Util.printCoordinates( center ) );
			assertEquals( p.imageSigma, peak.sigma, 0.0, "a structure at or below the image blur gets the image blur as its sigma" );
		}

		final ScaleSpacePeak peakZeroMean = nearestPeak( smallPeaks, zeroMean );
		assertTrue( peakZeroMean != null && dist( peakZeroMean.l, zeroMean ) < 1.0 && peakZeroMean.finest, "a finest-level peak at the zero-mean structure" );
		assertTrue( peakZeroMean.sigma == p.imageSigma || peakZeroMean.sigma == p.finestFallbackSigma, "the zero-mean structure gets the image blur or the fallback, not " + peakZeroMean.sigma );

		// an unreliable fit (here: no residual is accepted) gives the fallback, which is never above sigmaMin
		p.finestFitMaxResidual = -1;
		p.finestFallbackSigma = 1.4;

		final ExecutorService service2 = Threads.createFixedExecutorService( Threads.numThreads() );

		for ( final ScaleSpacePeak peak : DoGScaleSpace.computeScaleSpacePeaks( Views.extendMirrorSingle( img ), new FinalInterval( img ), new FinalInterval( img ), null, p, service2 ) )
			if ( peak.finest )
				assertEquals( 1.4, peak.sigma, 0.0 );

		p.finestFallbackSigma = 2.0;

		for ( final ScaleSpacePeak peak : DoGScaleSpace.computeScaleSpacePeaks( Views.extendMirrorSingle( img ), new FinalInterval( img ), new FinalInterval( img ), null, p, service2 ) )
			if ( peak.finest )
				assertEquals( p.sigmaMin, peak.sigma, 0.0 );

		service2.shutdown();
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

		final ArrayList< InterestPoint > peaks = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), img, img, null, p, service );

		service.shutdown();

		for ( final InterestPoint peak : peaks )
			IOFunctions.println( "sigmaMin=" + sigmaMin + " peak: " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );

		assertEquals( stds.length, peaks.size(), "number of peaks" );

		peaks.sort( Comparator.comparingDouble( peak -> peak.getL()[ 0 ] ) );

		final double[] ratios = new double[ stds.length ];

		for ( int i = 0; i < stds.length; ++i )
		{
			final InterestPoint peak = peaks.get( i );
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
	 * The candidates (computed once down to a low threshold, minima and maxima, no finest-level exclusion)
	 * filtered at a higher threshold, with or without the finest level, maxima only, are identical (in order)
	 * to a direct run at that threshold, for both localizations: the interactive preview never recomputes.
	 * The finest-level peaks, the types and the keys are marked, the cap keeps the strongest candidates.
	 */
	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void testCandidatesMatchDirectRun()
	{
		final SpimData2 spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[] { 0, 90 }, 0, 100, new double[] { 2, 2, 2 }, new FinalInterval( 128, 128, 64 ) ) );
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );
		final RandomAccessibleInterval img = DownsampleTools.openAndDownsample( spimData.getSequenceDescription().getImgLoader(), vd, new long[] { 1, 1, 1 }, false ).getA();
		final Interval interval = new FinalInterval( img );
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		// the range of the image, as the driver computes it per view
		final float[] minmax = FusionTools.minMax( img );

		for ( final int localization : new int[] { 1, 0 } )
		{
			final ScaleSpaceParameters p = new ScaleSpaceParameters( 1.5, 3, -1, 0.001 );
			p.localization = localization;
			p.minIntensity = minmax[ 0 ];
			p.maxIntensity = minmax[ 1 ];

			final Candidates c = DoGScaleSpace.computeScaleSpaceCandidates( (RandomAccessible)Views.extendMirrorSingle( img ), interval, interval, null, p, 0, service );

			assertEquals( 0.001, c.threshold, 0.0 );
			assertTrue( c.regular.size() > 0 && c.finest.size() > 0, "candidates: " + c.regular.size() + " / " + c.finest.size() );

			for ( final ScaleSpacePeak f : c.finest )
			{
				assertTrue( f.finest );
				assertEquals( 0, f.octave );
				assertTrue( f.sigma > 0 && f.sigma <= p.sigmaMin + 1e-9, "fitted sigma " + f.sigma + " <= sigmaMin" );
				assertTrue( f.level1Key >= 0 );
			}

			int keyed = 0, minima = 0;

			for ( final ScaleSpacePeak r : c.regular )
			{
				assertFalse( r.finest );

				if ( r.octave > 0 )
					assertEquals( -1, r.level1Key );
				else if ( r.level1Key >= 0 )
					++keyed;

				if ( !r.isMax )
					++minima;
			}

			assertTrue( keyed > 0, "level-1 peaks carry a key" );
			assertTrue( minima > 0, "minima are candidates, too" );

			for ( final double t : new double[] { 0.004, 0.02 } )
				for ( final boolean finest : new boolean[] { true, false } )
				{
					final ScaleSpaceParameters pd = new ScaleSpaceParameters( p );
					pd.threshold = t;
					pd.detectFinestLevel = finest;

					final ArrayList< ScaleSpacePeak > direct = DoGScaleSpace.computeScaleSpacePeaks( (RandomAccessible)Views.extendMirrorSingle( img ), interval, interval, null, pd, service );
					final ArrayList< ScaleSpacePeak > filtered = DoGScaleSpace.filterCandidates( c, t, finest, pd.findMin, pd.findMax, localization, pd.combineDistance );

					final String config = "localization " + localization + ", t = " + t + ", finest " + finest;

					IOFunctions.println( config + ": direct " + direct.size() + " peaks, filtered candidates " + filtered.size() );

					assertEquals( direct.size(), filtered.size(), config );
					assertTrue( direct.size() > 0 || !finest, config );

					for ( int i = 0; i < direct.size(); ++i )
					{
						final ScaleSpacePeak a = direct.get( i ), b = filtered.get( i );

						for ( int d = 0; d < 3; ++d )
							assertEquals( a.l[ d ], b.l[ d ], 1e-9, config + ", peak " + i );

						assertEquals( a.value, b.value, 1e-9, config + ", peak " + i );
						assertEquals( a.sigma, b.sigma, 1e-9, config + ", peak " + i );
						assertEquals( a.octave, b.octave, config + ", peak " + i );
						assertEquals( a.finest, b.finest, config + ", peak " + i );
						assertEquals( a.isMax, b.isMax, config + ", peak " + i );
						assertEquals( a.level1Key, b.level1Key, config + ", peak " + i );
					}
				}
		}

		// the cap keeps the strongest candidates and raises the threshold they are complete down to
		final ScaleSpaceParameters p = new ScaleSpaceParameters( 1.5, 3, -1, 0.001 );
		p.minIntensity = minmax[ 0 ];
		p.maxIntensity = minmax[ 1 ];
		final Candidates capped = DoGScaleSpace.computeScaleSpaceCandidates( (RandomAccessible)Views.extendMirrorSingle( img ), interval, interval, null, p, 10, service );

		assertTrue( capped.size() <= 10 && capped.size() > 0, "capped: " + capped.size() );
		assertTrue( capped.threshold > 0.001, "threshold raised to " + capped.threshold );

		for ( final ScaleSpacePeak peak : capped.regular )
			assertTrue( Math.abs( peak.value ) > capped.threshold );

		for ( final ScaleSpacePeak peak : capped.finest )
			assertTrue( Math.abs( peak.value ) > capped.threshold );

		service.shutdown();
	}

	/**
	 * The algorithm refuses a missing intensity range (the driver computes it per view)
	 */
	@Test
	public void testRangeRequired()
	{
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );
		final RandomAccessibleInterval< FloatType > img = openSimulatedView();
		final Interval interval = new FinalInterval( img );
		final ScaleSpaceParameters p = new ScaleSpaceParameters();

		assertTrue( Double.isNaN( p.minIntensity ) && Double.isNaN( p.maxIntensity ) );

		boolean thrown = false;

		try
		{
			DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), interval, interval, null, p, service );
		}
		catch ( final IllegalArgumentException e )
		{
			thrown = true;
		}

		assertTrue( thrown, "a missing intensity range is refused" );
		service.shutdown();
	}

	/**
	 * The intensity of a point is the (raw) image at its refined position, linearly interpolated: unit blobs give ~1 in
	 * the scale space and in the legacy DoG (which also stores its sigma), and readIntensities interpolates linearly
	 */
	@Test
	public void testIntensity()
	{
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final double[][] centers = new double[][] { { 40.3, 60.2, 30.6 }, { 90.7, 70.4, 33.3 } };
		final Img< FloatType > img = blobs( new long[] { 128, 128, 64 }, centers, new double[] { 4, 6 } );

		// the scale space normalizes to [0,1] internally, the intensity is the raw value
		final ScaleSpaceParameters p = new ScaleSpaceParameters( 2.0, 3, -1, 0.05 );
		p.minIntensity = 0;
		p.maxIntensity = 1;

		final ArrayList< InterestPoint > ss = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( img ), img, img, null, p, service );

		assertTrue( ss.size() >= 2 );

		for ( final InterestPoint ip : ss )
		{
			assertTrue( Double.isFinite( ip.getIntensity() ) && ip.getIntensity() >= 0 && ip.getIntensity() <= 1, "intensity " + ip.getIntensity() );
			assertTrue( Double.isFinite( ip.getResponse() ) && Double.isFinite( ip.getSigma() ) );
		}

		for ( final double[] c : centers )
		{
			final InterestPoint nearest = nearest( ss, c );
			assertTrue( dist( nearest.getL(), c ) < 1.0, "scale space found the blob at " + Util.printCoordinates( c ) );
			assertEquals( 1.0, nearest.getIntensity(), 0.05 );
		}

		// the legacy DoG: the same intensity, the sigma of the detection
		final ArrayList< InterestPoint > dog = DoGImgLib2.computeDoG( Views.extendMirrorSingle( img ), null, img, 4.0, 0.05, 1, false, true, 0, 1, service );

		final InterestPoint nearest = nearest( dog, centers[ 0 ] );
		assertTrue( dist( nearest.getL(), centers[ 0 ] ) < 1.0, "DoG found the blob" );
		assertEquals( 1.0, nearest.getIntensity(), 0.05 );

		for ( final InterestPoint ip : dog )
		{
			assertEquals( 4.0, ip.getSigma(), 0.0 );
			assertTrue( Math.abs( ip.getResponse() ) > 0.05 );
			assertTrue( Double.isFinite( ip.getIntensity() ) );
		}

		// readIntensities interpolates linearly in the image handed in
		final Img< FloatType > ramp = ArrayImgs.floats( 4, 4, 4 );
		final Cursor< FloatType > cursor = ramp.localizingCursor();

		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.get().set( cursor.getIntPosition( 0 ) + 10 * cursor.getIntPosition( 1 ) + 100 * cursor.getIntPosition( 2 ) );
		}

		final InterestPoint a = new InterestPoint( 0, new double[] { 2, 1, 0 } );
		final InterestPoint b = new InterestPoint( 1, new double[] { 1.5, 2, 3 } );

		assertTrue( Double.isNaN( a.getIntensity() ) );

		InterestPointTools.readIntensities( Views.extendBorder( ramp ), Arrays.asList( a, b ) );

		assertEquals( 12.0, a.getIntensity(), 1e-6 );
		assertEquals( 321.5, b.getIntensity(), 1e-4 );

		service.shutdown();
	}

	protected static ScaleSpacePeak nearestPeak( final List< ScaleSpacePeak > peaks, final double[] c )
	{
		ScaleSpacePeak nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for ( final ScaleSpacePeak peak : peaks )
		{
			final double d = dist( peak.l, c );

			if ( d < nearestDistance )
			{
				nearestDistance = d;
				nearest = peak;
			}
		}

		return nearest;
	}

	protected static InterestPoint nearest( final List< InterestPoint > points, final double[] c )
	{
		InterestPoint nearest = null;

		for ( final InterestPoint ip : points )
			if ( nearest == null || dist( ip.getL(), c ) < dist( nearest.getL(), c ) )
				nearest = ip;

		return nearest;
	}

	protected static double dist( final double[] a, final double[] b )
	{
		double d = 0;

		for ( int i = 0; i < a.length; ++i )
			d += ( a[ i ] - b[ i ] ) * ( a[ i ] - b[ i ] );

		return Math.sqrt( d );
	}

	/**
	 * The whole-view driver (ScaleSpace.findInterestPoints) returns the points mapped to full
	 * resolution, identical to DoGScaleSpace.computeDoGScaleSpace plus DownsampleTools.correctForDownsampling
	 */
	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void testDriver()
	{
		// isotropic beads of sigma 2 (the beads of SimulateUtil are 1x1x3 pixels, too small for a scale space at full resolution)
		final SpimData2 spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[] { 0, 90 }, 0, 100, new double[] { 2, 2, 2 }, new FinalInterval( 128, 128, 64 ) ) );
		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( 0, 0 );

		final ScaleSpaceDetectionParameters p = new ScaleSpaceDetectionParameters();
		p.imgloader = spimData.getSequenceDescription().getImgLoader();
		p.toProcess = new ArrayList<>();
		p.toProcess.add( vd );
		p.downsampleXY = 1;
		p.downsampleZ = 1;
		p.downsampling = new long[] { 1, 1, 1 };
		p.minIntensity = Double.NaN; // from the image
		p.maxIntensity = Double.NaN;
		p.scaleSpace.sigmaMin = 1.5;
		p.scaleSpace.threshold = 0.01;
		p.scaleSpace.steps = 3;
		p.scaleSpace.octaves = -1;

		final List< InterestPoint > driver = ScaleSpace.findInterestPoints( p ).get( vd );

		// the same directly through the algorithm
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final Pair< RandomAccessibleInterval, AffineTransform3D > input =
				DownsampleTools.openAndDownsample( p.imgloader, vd, new long[] { p.downsampleXY, p.downsampleXY, p.downsampleZ }, false );

		final ArrayList< InterestPoint > helper = DoGScaleSpace.computeDoGScaleSpace( (RandomAccessible)Views.extendMirrorSingle( input.getA() ), new FinalInterval( input.getA() ), new FinalInterval( input.getA() ), null, p.scaleSpace, service );

		service.shutdown();

		final double sigmaScale = Math.sqrt( Math.abs( input.getB().get( 0, 0 ) * input.getB().get( 1, 1 ) ) );
		final ArrayList< double[] > expectedSigmas = new ArrayList<>();

		for ( final InterestPoint ip : helper )
			expectedSigmas.add( new double[] { ip.getSigma() * sigmaScale } );

		DownsampleTools.correctForDownsampling( helper, input.getB() );

		assertTrue( driver.size() > 10, "found " + driver.size() + " points" );
		assertEquals( helper.size(), driver.size() );

		final long[] dim = vd.getViewSetup().getSize().dimensionsAsLongArray();

		for ( int i = 0; i < driver.size(); ++i )
		{
			assertFalse( Double.isNaN( driver.get( i ).getSigma() ) );

			final InterestPoint d = driver.get( i );
			final InterestPoint h = helper.get( i );

			assertEquals( h.getId(), d.getId() );
			assertEquals( h.getSigma(), d.getSigma(), 0.0 );
			assertEquals( expectedSigmas.get( i )[ 0 ], d.getSigma(), expectedSigmas.get( i )[ 0 ] * 1e-6 ); // the sigma is a float
			assertEquals( h.getResponse(), d.getResponse(), 0.0 );

			for ( int dd = 0; dd < 3; ++dd )
			{
				assertEquals( h.getL()[ dd ], d.getL()[ dd ], 0.0 );
				assertTrue( d.getL()[ dd ] >= -1 && d.getL()[ dd ] <= dim[ dd ], "inside the view: " + Util.printCoordinates( d.getL() ) );
			}

			// the sigma in full resolution lies in the range of the scale space (in full-resolution pixels)
			final double sigmaMinFull = DoGScaleSpace.sigmaBase( p.scaleSpace, 0, 0.5 ) * sigmaScale;
			assertTrue( d.getSigma() >= sigmaMinFull * 0.99, "sigma " + d.getSigma() + " >= " + sigmaMinFull );
		}
	}

	/**
	 * The scale space is a second detection method in the GUI, with its own parameters, params string
	 * and the same result as the driver
	 */
	@Test
	public void testGUIRegistered()
	{
		assertEquals( 0, Interest_Point_Detection.defaultAlgorithm );
		assertTrue( Interest_Point_Detection.staticAlgorithms.get( 0 ) instanceof DifferenceOfGaussianGUI );
		assertTrue( Interest_Point_Detection.staticAlgorithms.get( 1 ) instanceof ScaleSpaceGUI );
		assertEquals( "Scale-space (Difference-of-Gaussian)", Interest_Point_Detection.staticAlgorithms.get( 1 ).getDescription() );

		final SpimData2 spimData = SimulateUtil.setUp();
		final ArrayList< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final TimePoint tp = spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 );

		assertTrue( Interest_Point_Detection.staticAlgorithms.get( 1 ).newInstance( spimData, views ) instanceof ScaleSpaceGUI );

		// headless: set what the dialog would set
		final ScaleSpaceGUI gui = new ScaleSpaceGUI( spimData, views )
		{{
			localization = 1;
			downsampling = new long[] { 2, 2, 1 };
			anisotropyZ = 1.0;
			minIntensity = 0.0;
			maxIntensity = 1137.0;
			sigma = 1.6; // the initial blur is a field of the main dialog
			threshold = 0.008; // what the Advanced dialog or the preview would set (there are no presets)
			findMin = false;
			findMax = true;
			steps = 4;
			octaves = -1;
			detectFinestLevel = true;
		}};

		assertEquals( "DOG-SS s=1.6 steps=4 octaves=-1 finestLevel=true t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=1.0 minIntensity=0.0 maxIntensity=1137.0", gui.getParameters() );

		final Map< String, String > d = gui.describeParameters();
		assertEquals( "SCALE_SPACE", d.get( "detectionMethod" ) );
		assertEquals( "4", d.get( "steps" ) );
		assertEquals( "-1", d.get( "octaves" ) );
		assertEquals( "true", d.get( "finestLevel" ) );
		assertEquals( "MAX", d.get( "type" ) );
		assertEquals( "QUADRATIC", d.get( "localization" ) );
		assertEquals( "2", d.get( "downsampleXY" ) );
		assertEquals( "1.0", d.get( "anisotropy" ) );

		final HashMap< ViewId, List< InterestPoint > > viaGUI = gui.findInterestPoints( tp );

		// the same through the driver
		final ScaleSpaceDetectionParameters p = new ScaleSpaceDetectionParameters( SpimData2.getAllViewIdsForTimePointSorted( spimData, views, tp ), spimData.getSequenceDescription().getImgLoader() );
		p.downsampling = new long[] { 2, 2, 1 };
		p.anisotropyZ = 1.0;
		p.minIntensity = 0.0;
		p.maxIntensity = 1137.0;
		p.scaleSpace.sigmaMin = 1.6;
		p.scaleSpace.threshold = 0.008;

		final HashMap< ViewId, List< InterestPoint > > viaDriver = ScaleSpace.findInterestPoints( p );

		assertEquals( 3, viaGUI.size() );
		assertEquals( viaDriver.size(), viaGUI.size() );

		for ( final ViewId viewId : viaDriver.keySet() )
		{
			assertEquals( viaDriver.get( viewId ).size(), viaGUI.get( viewId ).size(), "points of view " + viewId.getViewSetupId() );

			for ( int i = 0; i < viaDriver.get( viewId ).size(); ++i )
			{
				assertFalse( Double.isNaN( viaGUI.get( viewId ).get( i ).getSigma() ) );
				assertEquals( viaDriver.get( viewId ).get( i ).getSigma(), viaGUI.get( viewId ).get( i ).getSigma(), 0.0 );
				assertArrayEquals( viaDriver.get( viewId ).get( i ).getL(), viaGUI.get( viewId ).get( i ).getL(), 0.0 );
			}
		}
	}

	/**
	 * Physically isotropic blobs on a grid with twice the z voxel size are found with the same response,
	 * sigma and position (in physical units) as on an isotropic grid when the anisotropy is given; with
	 * pixel-isotropic Gaussians the response differs. The sigmas are chosen such that the z sigmas stay
	 * above ~1.5 pixels at the detecting octave: below ~1 pixel the point-sampled Gaussian kernels
	 * overestimate the response by 10-20% (the same effect as in the finest octave of an isotropic
	 * scale space with sigmaMin ~1), which is a limit of the sampling, not of the anisotropy handling.
	 */
	@Test
	public void testAnisotropy()
	{
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

		final double a = 2.0;
		final double[] s = new double[] { 8, 16 };
		final double[][] isoCenters = new double[][] { { 90.3, 128.2, 127.6 }, { 260.7, 126.4, 128.3 } };
		final double[][] anisoCenters = new double[][] { { 90.3, 128.2, 127.6 / a }, { 260.7, 126.4, 128.3 / a } };

		// the same physical blobs; the image carries 0.5 px of blur per axis by definition, so the rendered
		// z sigma of the anisotropic grid is sqrt( ( s^2 - 0.25 ) / a^2 + 0.25 )
		final double[][] isoStds = new double[ 2 ][];
		final double[][] anisoStds = new double[ 2 ][];

		for ( int i = 0; i < 2; ++i )
		{
			isoStds[ i ] = new double[] { s[ i ], s[ i ], s[ i ] };
			anisoStds[ i ] = new double[] { s[ i ], s[ i ], Math.sqrt( ( s[ i ] * s[ i ] - 0.25 ) / ( a * a ) + 0.25 ) };
		}

		final Img< FloatType > iso = blobs( new long[] { 384, 256, 256 }, isoCenters, isoStds );
		final Img< FloatType > aniso = blobs( new long[] { 384, 256, 128 }, anisoCenters, anisoStds );

		final ScaleSpaceParameters p = new ScaleSpaceParameters( 3.0, 4, -1, 0.1 );
		p.minIntensity = 0;
		p.maxIntensity = 1;

		p.anisotropy = null;
		final ArrayList< InterestPoint > isoPeaks = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( iso ), iso, iso, null, p, service );

		p.anisotropy = new double[] { 1, 1, a };
		final ArrayList< InterestPoint > anisoPeaks = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( aniso ), aniso, aniso, null, p, service );

		p.anisotropy = null;
		final ArrayList< InterestPoint > pixelIsoPeaks = DoGScaleSpace.computeDoGScaleSpace( Views.extendMirrorSingle( aniso ), aniso, aniso, null, p, service );

		service.shutdown();

		for ( final InterestPoint peak : isoPeaks )
			IOFunctions.println( "isotropic grid: " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );
		for ( final InterestPoint peak : anisoPeaks )
			IOFunctions.println( "anisotropic grid, anisotropy " + a + ": " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );
		for ( final InterestPoint peak : pixelIsoPeaks )
			IOFunctions.println( "anisotropic grid, pixel-isotropic: " + Util.printCoordinates( peak.getL() ) + " response=" + peak.getResponse() + " sigma=" + peak.getSigma() );

		assertEquals( 2, isoPeaks.size() );
		assertEquals( 2, anisoPeaks.size() );

		isoPeaks.sort( Comparator.comparingDouble( peak -> peak.getL()[ 0 ] ) );
		anisoPeaks.sort( Comparator.comparingDouble( peak -> peak.getL()[ 0 ] ) );

		double maxSigmaDifference = 0;

		for ( int i = 0; i < 2; ++i )
		{
			final InterestPoint pi = isoPeaks.get( i );
			final InterestPoint pa = anisoPeaks.get( i );
			final double f = 1L << ( i + 1 ); // blob 8 is found in octave 1, blob 16 in octave 2

			assertEquals( pi.getResponse(), pa.getResponse(), 0.1 * Math.abs( pi.getResponse() ), "response of blob " + i );
			assertEquals( pi.getSigma(), pa.getSigma(), 0.05 * pi.getSigma(), "sigma of blob " + i );
			assertEquals( pi.getL()[ 0 ], pa.getL()[ 0 ], 0.1 * f, "x of blob " + i );
			assertEquals( pi.getL()[ 1 ], pa.getL()[ 1 ], 0.1 * f, "y of blob " + i );
			assertEquals( pi.getL()[ 2 ], pa.getL()[ 2 ] * a, 0.2 * f, "z of blob " + i );

			// pixel-isotropic Gaussians see a blob that is squeezed in z and select a smaller scale
			InterestPoint nearest = null;

			for ( final InterestPoint q : pixelIsoPeaks )
				if ( nearest == null || distance( q, pa ) < distance( nearest, pa ) )
					nearest = q;

			if ( nearest != null && distance( nearest, pa ) < 3 * f )
				maxSigmaDifference = Math.max( maxSigmaDifference, Math.abs( nearest.getSigma() - pi.getSigma() ) / pi.getSigma() );
			else
				maxSigmaDifference = 1;
		}

		assertTrue( maxSigmaDifference > 0.1, "pixel-isotropic sigma differs by " + maxSigmaDifference );
	}

	/**
	 * Few z slices do not limit the octaves when the z sigmas are small
	 */
	@Test
	public void testAutoOctavesAnisotropic()
	{
		final FinalInterval thin = new FinalInterval( 256, 256, 40 );
		final ScaleSpaceParameters p = new ScaleSpaceParameters( 1.5, 4, -1, 0.1 );

		p.anisotropy = null;
		assertEquals( 1, DoGScaleSpace.autoOctaves( thin, p ) );

		p.anisotropy = new double[] { 1, 1, 4 };
		assertEquals( 3, DoGScaleSpace.autoOctaves( thin, p ) );
	}

	/**
	 * The per-dimension sigmas: identical to the isotropic ones for anisotropy 1, the closed form of the
	 * blur the input of an octave carries, also when the hand-over level is not blurred in z at all
	 */
	@Test
	public void testSigmaDiffAnisotropic()
	{
		final float k4 = net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions.computeK( 4 );

		// anisotropy { 1, 1, 1 } is bit-identical to null
		final float[] sigma = DoGScaleSpace.computeSigmas( 1.8, k4, 4 );

		for ( int o = 0; o < 4; ++o )
		{
			final float[][] iso = DoGScaleSpace.computeSigmaDiff( sigma, null, 0.5f, o, 3 );
			final float[][] one = DoGScaleSpace.computeSigmaDiff( sigma, new double[] { 1, 1, 1 }, 0.5f, o, 3 );

			for ( int d = 0; d < 3; ++d )
				assertArrayEquals( iso[ d ], one[ d ], 0.0f );
		}

		// a strongly anisotropic z: no blur in z at octave 0 and 1 (except the top level of octave 1), standard formula from octave 3 on
		final float[] sigma1 = DoGScaleSpace.computeSigmas( 1.0, k4, 4 );
		final double[] anisotropy = new double[] { 1, 1, 8 };

		final float[][] o0 = DoGScaleSpace.computeSigmaDiff( sigma1, anisotropy, 0.5f, 0, 3 );
		final float[][] o1 = DoGScaleSpace.computeSigmaDiff( sigma1, anisotropy, 0.5f, 1, 3 );
		final float[][] o3 = DoGScaleSpace.computeSigmaDiff( sigma1, anisotropy, 0.5f, 3, 3 );

		for ( int i = 0; i < sigma1.length; ++i )
			assertEquals( 0f, o0[ 2 ][ i ], 0f, "octave 0, level " + i );

		for ( int i = 0; i < sigma1.length - 1; ++i )
			assertEquals( 0f, o1[ 2 ][ i ], 0f, "octave 1, level " + i );

		assertEquals( Math.sqrt( Math.pow( sigma1[ 6 ] / 8, 2 ) - 0.25 * 0.25 ), o1[ 2 ][ 6 ], 1e-6, "octave 1, top level" );
		assertEquals( 0.25f, DoGScaleSpace.inputSigma( sigma1, 8, 0.5f, 1 ), 0f );
		assertEquals( sigma1[ 0 ] / 8, DoGScaleSpace.inputSigma( sigma1, 8, 0.5f, 3 ), 1e-6 );

		for ( int i = 1; i < sigma1.length; ++i )
			assertEquals( Math.sqrt( Math.pow( sigma1[ i ] / 8, 2 ) - Math.pow( sigma1[ 0 ] / 8, 2 ) ), o3[ 2 ][ i ], 1e-6, "octave 3, level " + i );

		// the blur of the decimated hand-over level is the input of the next octave, and level 0 of an octave > 0 is never blurred
		for ( final double[] an : new double[][] { { 1, 1, 2 }, { 1, 1, 8 }, { 1, 1, 0.5 } } )
		{
			for ( int o = 0; o < 4; ++o )
			{
				final float[][] diff = DoGScaleSpace.computeSigmaDiff( sigma1, an, 0.5f, o, 3 );

				for ( int d = 0; d < 3; ++d )
				{
					final float in = DoGScaleSpace.inputSigma( sigma1, an[ d ], 0.5f, o );
					final float next = DoGScaleSpace.inputSigma( sigma1, an[ d ], 0.5f, o + 1 );

					assertEquals( next, Math.sqrt( in * in + diff[ d ][ 4 ] * diff[ d ][ 4 ] ) / 2, 1e-6, "hand-over of octave " + o + ", dimension " + d );

					if ( o > 0 )
						assertEquals( 0f, diff[ d ][ 0 ], 0f );
				}
			}
		}
	}

	protected static double distance( final InterestPoint a, final InterestPoint b )
	{
		double sum = 0;

		for ( int d = 0; d < 3; ++d )
			sum += ( a.getL()[ d ] - b.getL()[ d ] ) * ( a.getL()[ d ] - b.getL()[ d ] );

		return Math.sqrt( sum );
	}

	/**
	 * The drop-down of the (starting) resolution, shared by both detection methods (DifferenceOfGUI), lists the
	 * precomputed levels (with the voxel size) and the manual entry; the default is the second level if it exists
	 */
	@Test
	public void testResolutionChoices()
	{
		final String[] resolutions = new String[] { "1, 1, 1", "2, 2, 1", "4, 4, 2" };
		final String[] choices = DifferenceOfGUI.resolutionChoices( resolutions, new FinalVoxelDimensions( "um", 0.45, 0.45, 2.0 ) );

		assertEquals( 4, choices.length );
		assertTrue( choices[ 0 ].startsWith( "1, 1, 1" ) && choices[ 0 ].contains( "0.45 x 0.45 x 2.0 um" ), choices[ 0 ] );
		assertTrue( choices[ 1 ].startsWith( "2, 2, 1" ) && choices[ 1 ].contains( "0.9 x 0.9 x 2.0 um" ), choices[ 1 ] );
		assertTrue( choices[ 2 ].startsWith( "4, 4, 2" ) && choices[ 2 ].contains( "1.8 x 1.8 x 4.0 um" ), choices[ 2 ] );
		assertEquals( DifferenceOfGUI.manualResolution, choices[ 3 ] );

		assertEquals( 2, DifferenceOfGUI.resolutionChoices( new String[] { "1, 1, 1" }, null ).length );
		assertEquals( "1, 1, 1", DifferenceOfGUI.resolutionChoices( new String[] { "1, 1, 1" }, null )[ 0 ] );

		assertEquals( 1, DifferenceOfGUI.defaultResolutionChoice( 3, 1, false ) );
		assertEquals( 0, DifferenceOfGUI.defaultResolutionChoice( 1, 1, false ) );
		assertEquals( 2, DifferenceOfGUI.defaultResolutionChoice( 3, 7, false ) );
		assertEquals( 3, DifferenceOfGUI.defaultResolutionChoice( 3, 1, true ) );

		// the simulated data has no resolution levels
		final SpimData2 spimData = SimulateUtil.setUp();
		final ArrayList< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final String[] simulated = DownsampleTools.availableDownsamplings( spimData, views.get( 0 ) );

		assertEquals( 1, simulated.length );
		assertEquals( 0, DifferenceOfGUI.defaultResolutionChoice( simulated.length, DifferenceOfGUI.defaultResolutionIndex, false ) );
		assertTrue( DifferenceOfGUI.sameResolutionLevels( spimData, views ) );
	}

	public static Img< FloatType > blobs( final long[] dim, final double[][] centers, final double[] stds )
	{
		final double[][] stdsPerAxis = new double[ stds.length ][];

		for ( int i = 0; i < stds.length; ++i )
			stdsPerAxis[ i ] = new double[] { stds[ i ], stds[ i ], stds[ i ] };

		return blobs( dim, centers, stdsPerAxis );
	}

	/**
	 * Gaussian blobs of unit peak with a sigma per axis
	 */
	public static Img< FloatType > blobs( final long[] dim, final double[][] centers, final double[][] stds )
	{
		final Img< FloatType > img = ArrayImgs.floats( dim );

		for ( int i = 0; i < stds.length; ++i )
		{
			final double[] s = stds[ i ];
			final double[] c = centers[ i ];
			final long[] min = new long[ 3 ];
			final long[] max = new long[ 3 ];

			for ( int d = 0; d < 3; ++d )
			{
				min[ d ] = Math.max( 0, Math.round( c[ d ] - 4 * s[ d ] ) );
				max[ d ] = Math.min( dim[ d ] - 1, Math.round( c[ d ] + 4 * s[ d ] ) );
			}

			final Cursor< FloatType > cursor = Views.interval( img, new FinalInterval( min, max ) ).localizingCursor();

			while ( cursor.hasNext() )
			{
				cursor.fwd();

				double r2 = 0;

				for ( int d = 0; d < 3; ++d )
				{
					final double diff = ( cursor.getDoublePosition( d ) - c[ d ] ) / s[ d ];
					r2 += diff * diff;
				}

				cursor.get().set( cursor.get().get() + (float)Math.exp( -r2 / 2 ) );
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

	public static void assertIdentical( final RandomAccessibleInterval< FloatType > a, final RandomAccessibleInterval< FloatType > b )
	{
		assertEquals( new FinalInterval( a ), new FinalInterval( b ) );

		final Cursor< FloatType > ca = Views.flatIterable( a ).cursor();
		final Cursor< FloatType > cb = Views.flatIterable( b ).cursor();

		while ( ca.hasNext() )
			assertEquals( ca.next().get(), cb.next().get(), 0.0f );
	}

	/**
	 * The duplicate merge of points detected in blocks (BigStitcher-Spark): the octave from the sigma, the radius
	 * combineDistance * 2^octave in pixels of octave 0 (also for points scaled to full resolution), same sign only,
	 * the higher |response| survives, the result is sorted by |response|
	 */
	@Test
	public void testMergeDuplicatesPoints()
	{
		final double sigmaMin = 1.6;
		final float k = net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions.computeK( 4 );

		assertEquals( 0, DoGScaleSpace.octaveOf( 1.0, sigmaMin ) );
		assertEquals( 0, DoGScaleSpace.octaveOf( sigmaMin, sigmaMin ) );
		assertEquals( 0, DoGScaleSpace.octaveOf( sigmaMin * Math.pow( k, 3 ), sigmaMin ) );
		assertEquals( 1, DoGScaleSpace.octaveOf( sigmaMin * 2, sigmaMin ) );
		assertEquals( 1, DoGScaleSpace.octaveOf( (float)( sigmaMin * 2 ) * 0.9999999, sigmaMin ) );
		assertEquals( 1, DoGScaleSpace.octaveOf( sigmaMin * 2 * Math.pow( k, 3 ), sigmaMin ) );
		assertEquals( 2, DoGScaleSpace.octaveOf( (float)( sigmaMin * 4 ), sigmaMin ) );
		assertEquals( 3, DoGScaleSpace.octaveOf( sigmaMin * 8, sigmaMin ) );

		final double[] one = new double[] { 1, 1, 1 };
		final ArrayList< InterestPoint > points = new ArrayList<>();
		points.add( new InterestPoint( 0, new double[] { 10, 10, 10 }, 0.5f, (float)sigmaMin, 1f ) );
		points.add( new InterestPoint( 1, new double[] { 10.3, 10, 10 }, 0.4f, (float)sigmaMin, 1f ) ); // duplicate of 0 (0.3 < 0.5)
		points.add( new InterestPoint( 2, new double[] { 10, 10.4, 10 }, -0.45f, (float)sigmaMin, 1f ) ); // other sign, kept
		points.add( new InterestPoint( 3, new double[] { 50, 50, 50 }, 0.2f, (float)( sigmaMin * 4 ), 1f ) ); // octave 2
		points.add( new InterestPoint( 4, new double[] { 51.5, 50, 50 }, 0.3f, (float)sigmaMin, 1f ) ); // octave 0, 1.5 from 3 <= 0.5 * 4: 3 is the duplicate (weaker)
		points.add( new InterestPoint( 5, new double[] { 50, 52.5, 50 }, 0.1f, (float)( sigmaMin * 4 ), 1f ) ); // 2.5 from 3, 2.9 from 4: kept

		final ArrayList< InterestPoint > merged = DoGScaleSpace.mergeDuplicates( points, one, sigmaMin, 0.5 );
		final ArrayList< Integer > ids = new ArrayList<>();
		for ( final InterestPoint ip : merged )
			ids.add( ip.getId() );
		assertEquals( Arrays.asList( 0, 2, 4, 5 ), ids ); // sorted by |response|: 0.5, 0.45, 0.3, 0.1

		// scaled to full resolution by (2, 2, 1): the same decisions, distances in pixels of octave 0
		final double[] scale = new double[] { 2, 2, 1 };
		final ArrayList< InterestPoint > scaled = new ArrayList<>();
		for ( final InterestPoint ip : points )
			scaled.add( new InterestPoint( ip.getId(), new double[] { ip.getL()[ 0 ] * 2, ip.getL()[ 1 ] * 2, ip.getL()[ 2 ] }, ip.getResponse(), (float)( ip.getSigma() * 2 ), ip.getIntensity() ) );
		final ArrayList< Integer > idsScaled = new ArrayList<>();
		for ( final InterestPoint ip : DoGScaleSpace.mergeDuplicates( scaled, scale, sigmaMin, 0.5 ) )
			idsScaled.add( ip.getId() );
		assertEquals( ids, idsScaled );

		// positions scaled but the sigmas not (octaves unchanged): without the scale the distance of point 1 is 0.6 > 0.5 and it survives, with the scale it is a duplicate again
		final ArrayList< InterestPoint > positionsScaled = new ArrayList<>();
		for ( final InterestPoint ip : points )
			positionsScaled.add( new InterestPoint( ip.getId(), new double[] { ip.getL()[ 0 ] * 2, ip.getL()[ 1 ] * 2, ip.getL()[ 2 ] }, ip.getResponse(), ip.getSigma(), ip.getIntensity() ) );
		final ArrayList< Integer > idsUnscaled = new ArrayList<>();
		for ( final InterestPoint ip : DoGScaleSpace.mergeDuplicates( positionsScaled, one, sigmaMin, 0.5 ) )
			idsUnscaled.add( ip.getId() );
		assertTrue( idsUnscaled.contains( 1 ) );
		final ArrayList< Integer > idsRescaled = new ArrayList<>();
		for ( final InterestPoint ip : DoGScaleSpace.mergeDuplicates( positionsScaled, new double[] { 2, 2, 1 }, sigmaMin, 0.5 ) )
			idsRescaled.add( ip.getId() );
		assertTrue( !idsRescaled.contains( 1 ) && idsRescaled.contains( 4 ) );

		// no merge for one point or combineDistance 0
		assertEquals( 1, DoGScaleSpace.mergeDuplicates( points.subList( 0, 1 ), one, sigmaMin, 0.5 ).size() );
		assertEquals( points.size(), DoGScaleSpace.mergeDuplicates( points, one, sigmaMin, 0 ).size() );
	}
}
