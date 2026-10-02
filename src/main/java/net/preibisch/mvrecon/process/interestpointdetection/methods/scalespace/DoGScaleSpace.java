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
package net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.localextrema.RefinedPeak;
import net.imglib2.algorithm.localextrema.SubpixelLocalization;
import net.imglib2.converter.Converters;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions;
import net.preibisch.legacy.segmentation.SimplePeak;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointSS;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointValue;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.Localization;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyDoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyGauss;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyWeightedGauss;
import util.ImgLib2Tools;

/**
 * Difference-of-Gaussian interest points in a scale space, i.e. the n-dimensional, blocked version
 * of the scale space of mpicbg's SIFT (FloatArray2DScaleOctave, FloatArray2DScaleOctaveDoGDetector).
 *
 * Octave 0 is the image that is handed in, with Gaussians of sigma_i = sigmaMin * k^(i-1), i = 0..steps+2
 * (k = 2^(1/steps)) and DoG levels d_i = ( g_(i+1) - g_i ) / ( k - 1 ), i = 0..steps+1. A peak is an
 * extremum of the DoG over its 3^(n+1)-1 neighbors in space and scale, which is possible for the levels
 * 1..steps (that have both scale neighbors), so the finest detectable level d_1 is the DoG of sigmaMin
 * and k*sigmaMin, the single-scale DoG of DoGImgLib2 (level 0 exists only as its scale neighbor).
 * Octave o+1 is the Gaussian level 'steps' (sigma = 2*sigma_0) of octave o subsampled by 2 in all
 * dimensions, so pixel p of octave o sits at 2^o * p in octave 0 and the sigmas continue seamlessly.
 * Peaks are refined by a quadratic fit in space and scale (Localization.computeQuadraticLocalization).
 * Each point carries the refined DoG response and the lower sigma of its DoG level,
 * sigmaMin * k^(level-1) * 2^octave, in pixels of octave 0.
 *
 * All Gaussians and DoGs are lazy (LazyGauss, LazyDoG) and only computed where they are needed, which
 * makes it possible to process a block (processInterval) of a large image (imageInterval); the halo
 * that the coarser octaves need is derived automatically.
 *
 * @author Stephan Preibisch
 */
public class DoGScaleSpace
{
	/**
	 * One octave of the scale space, i.e. octave 0 subsampled by f = 2^o (pixel p of this octave sits
	 * at f*p in octave 0). All intervals are in the pixel grid of this octave.
	 */
	public static class Octave
	{
		final public int o;
		final public long f;

		/** the entire image at this octave */
		final public FinalInterval domain;

		/** the part of the processing interval at this octave, where peaks are searched */
		final public FinalInterval block;

		/** block expanded by 1, the input of the peak detection (which skips a 1-pixel border) */
		final public FinalInterval det;

		/** block expanded by 1 + refineMargin, the domain of the DoG levels and of the refinement */
		final public FinalInterval ref;

		/** the domain of the Gaussian level 'steps', which is handed over to the next octave */
		public FinalInterval domS;

		/** where the input of this octave has to be present */
		public FinalInterval need;

		/** sigma of the Gaussian that is applied to the input of this octave to obtain each level */
		public float[] sigmaDiff;

		public RandomAccessible< FloatType > input, mask;

		final public ArrayList< RandomAccessibleInterval< FloatType > > gauss = new ArrayList<>();
		final public ArrayList< RandomAccessibleInterval< FloatType > > dog = new ArrayList<>();

		/** the DoG levels stacked, the last dimension is the scale */
		public RandomAccessibleInterval< FloatType > stack;

		public Octave( final int o, final Interval imageInterval, final Interval processInterval, final int refineMargin )
		{
			this.o = o;
			this.f = 1L << o;
			this.domain = octaveInterval( imageInterval, o );
			this.block = Intervals.intersect( octaveInterval( processInterval, o ), domain );
			this.det = Intervals.intersect( Intervals.expand( block, 1 ), domain );
			this.ref = Intervals.intersect( Intervals.expand( block, 1 + refineMargin ), domain );
		}
	}

	/**
	 * A refined peak in pixels of octave 0, before duplicates are removed and ids are assigned
	 */
	public static class ScaleSpacePeak implements RealLocalizable
	{
		final public double[] l;
		final public double value, sigma;
		final public int octave;

		public ScaleSpacePeak( final double[] l, final double value, final double sigma, final int octave )
		{
			this.l = l;
			this.value = value;
			this.sigma = sigma;
			this.octave = octave;
		}

		@Override
		public int numDimensions() { return l.length; }

		@Override
		public double getDoublePosition( final int d ) { return l[ d ]; }

		@Override
		public float getFloatPosition( final int d ) { return (float)l[ d ]; }

		@Override
		public void localize( final double[] position )
		{
			for ( int d = 0; d < l.length; ++d )
				position[ d ] = l[ d ];
		}

		@Override
		public void localize( final float[] position )
		{
			for ( int d = 0; d < l.length; ++d )
				position[ d ] = (float)l[ d ];
		}
	}

	/**
	 * Detects scale-space DoG peaks in processInterval of an image that lives on imageInterval.
	 *
	 * @param input - the image, extended to infinity (e.g. Views.extendMirrorSingle)
	 * @param imageInterval - the domain of the image (defines the octaves, their domains and the intensity range)
	 * @param processInterval - the block in which peaks are searched, inside imageInterval (identical for a whole image)
	 * @param mask - peaks are only accepted where mask &gt; 0 (extended to infinity, e.g. Views.extendZero), can be null
	 * @param p - the parameters
	 * @param service - for multithreading
	 * @return the peaks in pixels of octave 0 with their response and sigma (in pixels of octave 0), ids 0..n-1
	 */
	public static < T extends RealType< T >, M extends RealType< M > > ArrayList< InterestPointSS > computeDoGScaleSpace(
			final RandomAccessible< T > input,
			final Interval imageInterval,
			final Interval processInterval,
			final RandomAccessible< M > mask,
			final ScaleSpaceParameters p,
			final ExecutorService service )
	{
		final ArrayList< ScaleSpacePeak > peaks = computeScaleSpacePeaks( input, imageInterval, processInterval, mask, p, service );
		final ArrayList< InterestPointSS > result = new ArrayList<>();

		for ( int i = 0; i < peaks.size(); ++i )
		{
			final ScaleSpacePeak peak = peaks.get( i );
			result.add( new InterestPointSS( i, peak.l, peak.value, peak.sigma ) );
		}

		return result;
	}

	/**
	 * Same as computeDoGScaleSpace, but returns the merged peaks (sorted by |response|) before ids are assigned
	 */
	public static < T extends RealType< T >, M extends RealType< M > > ArrayList< ScaleSpacePeak > computeScaleSpacePeaks(
			final RandomAccessible< T > input,
			final Interval imageInterval,
			final Interval processInterval,
			final RandomAccessible< M > mask,
			final ScaleSpaceParameters p,
			final ExecutorService service )
	{
		final List< Octave > octaves = buildScaleSpace( input, imageInterval, processInterval, mask, p, service );

		final float k = LaPlaceFunctions.computeK( p.steps );
		final ArrayList< ScaleSpacePeak > peaks = new ArrayList<>();

		for ( final Octave octave : octaves )
		{
			final ArrayList< ScaleSpacePeak > octavePeaks = detectOctave( octave, p, k, service );

			if ( !DoGImgLib2.silent )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Octave " + octave.o + ": found " + octavePeaks.size() + " peaks." );

			peaks.addAll( octavePeaks );
		}

		final ArrayList< ScaleSpacePeak > merged = mergeDuplicates( peaks, p.combineDistance );

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Found " + merged.size() + " final peaks (" + ( peaks.size() - merged.size() ) + " duplicates removed)." );

		return merged;
	}

	/**
	 * Sets up the (lazy) Gaussian and DoG levels of all octaves, the input of octave o+1 is computed
	 * from the Gaussian level 'steps' of octave o where it is needed.
	 */
	public static < T extends RealType< T >, M extends RealType< M > > List< Octave > buildScaleSpace(
			final RandomAccessible< T > input,
			final Interval imageInterval,
			final Interval processInterval,
			final RandomAccessible< M > mask,
			final ScaleSpaceParameters p,
			final ExecutorService service )
	{
		if ( p.steps < 1 )
			throw new IllegalArgumentException( "steps must be >= 1" );

		final int n = imageInterval.numDimensions();

		final RandomAccessible< FloatType > maskFloat;

		if ( mask == null )
			maskFloat = null;
		else
			maskFloat = Converters.convert( mask, ( i, o ) -> o.set( i.getRealFloat() ), new FloatType() );

		//
		// intensity range (always of the entire image, never of the block)
		//
		final float min, max;
		final boolean autoDetected;

		if ( Double.isNaN( p.minIntensity ) || Double.isNaN( p.maxIntensity ) || Double.isInfinite( p.minIntensity ) || Double.isInfinite( p.maxIntensity ) || p.minIntensity == p.maxIntensity )
		{
			final float[] minmax;

			if ( mask == null )
				minmax = FusionTools.minMax( Views.interval( input, imageInterval ), service );
			else
				minmax = DoGImgLib2.minMax(
						Views.interval( Converters.convert( input, ( i, o ) -> o.set( i.getRealFloat() ), new FloatType() ), imageInterval ),
						Views.interval( maskFloat, imageInterval ),
						service );

			min = minmax[ 0 ];
			max = minmax[ 1 ];
			autoDetected = true;
		}
		else
		{
			min = (float)p.minIntensity;
			max = (float)p.maxIntensity;
			autoDetected = false;
		}

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): min intensity = " + min + ", max intensity = " + max + ( autoDetected ? " [auto-detected over the entire image]" : " [provided]" ) );

		// normalize image
		final RandomAccessible< FloatType > inputFloat = ImgLib2Tools.normalizeVirtual( input, min, max );

		//
		// sigmas: level 1 is sigmaMin, the same float arithmetic as DoGImgLib2.computeSigmas (so that steps=4 reproduces it)
		//
		final float k = LaPlaceFunctions.computeK( p.steps );
		final float[] sigma = computeSigmas( p.sigmaMin, k, p.steps );

		if ( !( sigma[ 0 ] > p.imageSigma ) )
			throw new IllegalArgumentException( "sigmaMin/k (" + sigma[ 0 ] + ") must be larger than the image sigma (" + p.imageSigma + ")" );

		final float[] sigmaDiff0 = LaPlaceFunctions.computeSigmaDiff( sigma, (float)p.imageSigma ); // octave 0: the image carries imageSigma
		final float[] sigmaDiffO = LaPlaceFunctions.computeSigmaDiff( sigma, sigma[ 0 ] ); // octave > 0: the input carries sigma_0 already

		//
		// octaves and their intervals
		//
		final int numOctaves = p.octaves > 0 ? p.octaves : autoOctaves( imageInterval, p );
		final ArrayList< Octave > octaves = new ArrayList<>();

		for ( int o = 0; o < numOctaves; ++o )
		{
			final Octave octave = new Octave( o, imageInterval, processInterval, p.refineMargin );

			// the block has no pixels at this octave (or too few for a 3x3x..x3 neighborhood)
			if ( Intervals.isEmpty( octave.block ) || minDimension( octave.det ) < 3 )
				break;

			octave.sigmaDiff = o == 0 ? sigmaDiff0 : sigmaDiffO;
			octaves.add( octave );
		}

		// from the coarsest octave down: where does each octave need its input and the hand-over level
		for ( int o = octaves.size() - 1; o >= 0; --o )
		{
			final Octave octave = octaves.get( o );

			final int haloMax = Gauss3.halfkernelsize( octave.sigmaDiff[ p.steps + 2 ] );
			final int haloS = Gauss3.halfkernelsize( octave.sigmaDiff[ p.steps ] );

			if ( o == octaves.size() - 1 )
				octave.domS = octave.ref;
			else
				octave.domS = Intervals.intersect( Intervals.union( octave.ref, scale( octaves.get( o + 1 ).need, 2 ) ), octave.domain );

			octave.need = Intervals.intersect( Intervals.union( Intervals.expand( octave.ref, haloMax ), Intervals.expand( octave.domS, haloS ) ), octave.domain );
		}

		if ( !DoGImgLib2.silent )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): computing scale space DoG with (sigmaMin=" + p.sigmaMin + ", steps=" + p.steps + ", k=" + k + ", octaves=" + octaves.size() + ", threshold=" + p.threshold + ")" );

			for ( final Octave octave : octaves )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): octave " + octave.o + ": domain=" + Util.printInterval( octave.domain ) + ", block=" + Util.printInterval( octave.block ) +
						", sigmas (in pixels of octave 0)=" + Util.printCoordinates( sigmasInBasePixels( sigma, octave.f ) ) );
		}

		//
		// Gaussian and DoG levels
		//
		RandomAccessible< FloatType > inputO = inputFloat;
		RandomAccessible< FloatType > maskO = maskFloat;

		for ( int o = 0; o < octaves.size(); ++o )
		{
			final Octave octave = octaves.get( o );

			octave.input = inputO;
			octave.mask = maskO;

			buildLevels( octave, p, k );

			if ( o < octaves.size() - 1 )
			{
				final Octave next = octaves.get( o + 1 );

				// every second pixel of the Gaussian with sigma = 2*sigmaMin, which then has sigma = sigmaMin again
				final RandomAccessibleInterval< FloatType > nextInput =
						materialize( Views.subsample( Views.extendMirrorSingle( octave.gauss.get( p.steps ) ), 2 ), next.need, p.cellSize, service );

				inputO = Views.extendMirrorSingle( nextInput );
				maskO = maskFloat == null ? null : Views.subsample( maskFloat, next.f );
			}
		}

		return octaves;
	}

	/**
	 * Creates the lazy Gaussian levels, DoG levels and the stacked DoG of an octave (input and mask must be set)
	 */
	public static void buildLevels( final Octave octave, final ScaleSpaceParameters p, final float k )
	{
		final int n = octave.domain.numDimensions();
		final float kMin1Inv = LaPlaceFunctions.computeKWeight( k );

		for ( int i = 0; i < p.steps + 3; ++i )
		{
			final Interval region = i == p.steps ? octave.domS : octave.ref;
			final RandomAccessibleInterval< FloatType > gauss;

			if ( octave.o > 0 && i == 0 )
			{
				// the input of octave > 0 has sigma = sigmaMin already
				gauss = Views.interval( octave.input, region );
			}
			else
			{
				final double[] sigma = new double[ n ];
				Arrays.fill( sigma, octave.sigmaDiff[ i ] );

				if ( octave.mask == null )
					gauss = LazyGauss.init( octave.input, region, new FloatType(), sigma, p.cellSize );
				else
					gauss = LazyWeightedGauss.init( octave.input, octave.mask, region, new FloatType(), sigma, p.cellSize ); // mask zero oobs
			}

			octave.gauss.add( gauss );
		}

		for ( int i = 0; i < p.steps + 2; ++i )
			octave.dog.add( LazyDoG.init( octave.gauss.get( i ), octave.gauss.get( i + 1 ), kMin1Inv, octave.ref, new FloatType(), p.cellSize ) );

		octave.stack = Views.stack( octave.dog );
	}

	/**
	 * Extrema in space and scale of one octave, refined, mapped to pixels of octave 0
	 */
	public static ArrayList< ScaleSpacePeak > detectOctave( final Octave octave, final ScaleSpaceParameters p, final float k, final ExecutorService service )
	{
		final int n = octave.domain.numDimensions();

		// compute the DoG cells in parallel (findPeaks would compute them on demand, one cell at a time per thread)
		forEachTile( octave.ref, p.cellSize, service, tile -> {
			for ( final RandomAccessibleInterval< FloatType > dog : octave.dog )
			{
				final RandomAccess< FloatType > ra = dog.randomAccess();
				ra.setPosition( tile.minAsLongArray() );
				ra.get();
			}
		} );

		final Interval det = Intervals.addDimension( octave.det, 0, p.steps + 1 );
		final RandomAccessibleInterval< FloatType > mask = octave.mask == null ? null : Views.interval( Views.addDimension( octave.mask ), det );

		final float minPeakValue = (float)p.threshold;
		final float minInitialPeakValue = p.localization == 0 ? minPeakValue : minPeakValue / 3.0f;

		// scans block x levels 1..steps, as findPeaks skips a 1-pixel border in every dimension
		final ArrayList< SimplePeak > peaks = DoGImgLib2.findPeaks( Views.interval( octave.stack, det ), mask, minInitialPeakValue, service );

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Octave " + octave.o + ": found " + peaks.size() + " initial peaks (before refinement)." );

		final ArrayList< ScaleSpacePeak > result = new ArrayList<>();

		// the extrema in space and scale at level 1 that made it into the result (keyed by their integer position)
		final HashSet< Long > level1 = new HashSet<>();

		if ( p.localization == 0 )
		{
			final RandomAccess< FloatType > ra = octave.stack.randomAccess();

			for ( final InterestPoint ip : Localization.noLocalization( peaks, p.findMin, p.findMax, true ) )
			{
				final double[] l = ip.getL();

				// noLocalization stores |value|, we want the sign
				for ( int d = 0; d <= n; ++d )
					ra.setPosition( Math.round( l[ d ] ), d );

				result.add( toScaleSpacePeak( l, ra.get().get(), octave, p, k ) );

				if ( Math.round( l[ n ] ) == 1 )
					level1.add( key( l, octave.det ) );
			}
		}
		else
		{
			final long[] min = octave.stack.minAsLongArray();

			for ( final SimplePeak peak : peaks )
				for ( int d = 0; d < peak.location.length; ++d )
					peak.location[ d ] -= min[ d ];

			final RandomAccessibleInterval< FloatType > stackZeroMin = Views.zeroMin( octave.stack );

			// quadratic fit in space and scale (same settings as Localization.computeQuadraticLocalization), invalid fits are returned, too
			final ArrayList< RefinedPeak< Point > > refined = refinePeaks( peaks, Views.extendMirrorDouble( stackZeroMin ), new FinalInterval( stackZeroMin ), p.findMin, p.findMax, service );

			for ( final RefinedPeak< Point > r : refined )
			{
				if ( !r.isValid() || !( Math.abs( r.getValue() ) > minPeakValue ) )
					continue; // a failed fit at level 1 (e.g. a structure smaller than sigmaMin) is picked up by detectFinestLevel

				final double[] l = new double[ n + 1 ];

				for ( int d = 0; d <= n; ++d )
					l[ d ] = r.getDoublePosition( d ) + min[ d ];

				result.add( toScaleSpacePeak( l, r.getValue(), octave, p, k ) );

				if ( r.getOriginalPeak().getIntPosition( n ) == 1 )
				{
					final double[] original = new double[ n ];

					for ( int d = 0; d < n; ++d )
						original[ d ] = r.getOriginalPeak().getIntPosition( d ) + min[ d ];

					level1.add( key( original, octave.det ) );
				}
			}
		}

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Octave " + octave.o + ": " + result.size() + " peaks after refinement." );

		// structures smaller than sigmaMin are no extremum in scale, but we might want them anyways
		if ( octave.o == 0 && p.detectFinestLevel )
		{
			final ArrayList< ScaleSpacePeak > finest = detectFinestLevel( octave, level1, p, k, service );

			if ( !DoGImgLib2.silent )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Octave " + octave.o + ": " + finest.size() + " additional peaks at the finest level (structures smaller than sigmaMin)." );

			result.addAll( finest );
		}

		return result;
	}

	/**
	 * Quadratic fit in space and scale with the settings of Localization.computeQuadraticLocalization,
	 * but also returning the peaks whose fit failed (RefinedPeak.isValid() == false)
	 */
	public static ArrayList< RefinedPeak< Point > > refinePeaks( final ArrayList< SimplePeak > peaks, final RandomAccessible< FloatType > img, final Interval validInterval, final boolean findMin, final boolean findMax, final ExecutorService service )
	{
		final ArrayList< Point > peakList = new ArrayList<>();

		for ( final SimplePeak peak : peaks )
			if ( ( peak.isMax && findMax ) || ( peak.isMin && findMin ) )
				peakList.add( new Point( peak.location ) );

		if ( peakList.isEmpty() )
			return new ArrayList<>();

		final SubpixelLocalization< Point, FloatType > spl = new SubpixelLocalization<>( img.numDimensions() );
		spl.setAllowMaximaTolerance( true );
		spl.setMaxNumMoves( 10 );
		spl.setReturnInvalidPeaks( true );

		return SubpixelLocalization.refinePeaks(
				peakList,
				img,
				validInterval,
				spl.getReturnInvalidPeaks(),
				spl.getMaxNumMoves(),
				spl.getAllowMaximaTolerance(),
				spl.getMaximaTolerance(),
				spl.getAllowedToMoveInDim(),
				(int)Math.min( peakList.size(), Threads.numThreads() * 20 ),
				service );
	}

	/**
	 * @param l - refined position in the octave (n dimensions) and the refined level (index n)
	 */
	protected static ScaleSpacePeak toScaleSpacePeak( final double[] l, final double value, final Octave octave, final ScaleSpaceParameters p, final float k )
	{
		final int n = l.length - 1;
		final double[] pos = new double[ n ];

		for ( int d = 0; d < n; ++d )
			pos[ d ] = l[ d ] * octave.f;

		return new ScaleSpacePeak( pos, value, sigmaBase( p.sigmaMin, k, octave.o, l[ n ] ), octave.o );
	}

	/**
	 * The spatial extrema of the finest detectable level (DoG level 1 = sigmaMin) that are no extremum
	 * in space and scale, i.e. the detections of the single-scale DoG at sigmaMin that a scale space
	 * would not report: structures smaller than sigmaMin (the level below is stronger) and structures
	 * that merge with a neighbor at the next coarser level (e.g. two beads close to each other). They
	 * are refined in space only (exactly as the single-scale DoG does it) and reported with sigma = sigmaMin.
	 *
	 * @param level1 - keys (see key()) of the integer positions of the level-1 extrema in space and scale that are in the result already
	 */
	public static ArrayList< ScaleSpacePeak > detectFinestLevel( final Octave octave, final HashSet< Long > level1, final ScaleSpaceParameters p, final float k, final ExecutorService service )
	{
		final int n = octave.domain.numDimensions();
		final RandomAccessibleInterval< FloatType > dog1 = octave.dog.get( 1 );
		final RandomAccessibleInterval< FloatType > mask = octave.mask == null ? null : Views.interval( octave.mask, octave.det );

		final float minPeakValue = (float)p.threshold;
		final float minInitialPeakValue = p.localization == 0 ? minPeakValue : minPeakValue / 3.0f;

		final ArrayList< SimplePeak > peaks = DoGImgLib2.findPeaks( Views.interval( dog1, octave.det ), mask, minInitialPeakValue, service );

		final ArrayList< SimplePeak > candidates = new ArrayList<>();

		for ( final SimplePeak peak : peaks )
			if ( !level1.contains( key( peak.location, octave.det ) ) )
				candidates.add( peak );

		// refine in space only, as the single-scale DoG does
		final ArrayList< InterestPoint > refined;

		if ( p.localization == 0 )
		{
			refined = Localization.noLocalization( candidates, p.findMin, p.findMax, true );
		}
		else
		{
			final long[] min = dog1.minAsLongArray();

			for ( final SimplePeak peak : candidates )
				for ( int d = 0; d < n; ++d )
					peak.location[ d ] -= min[ d ];

			final RandomAccessibleInterval< FloatType > dogZeroMin = Views.zeroMin( dog1 );

			refined = Localization.computeQuadraticLocalization( candidates, Views.extendMirrorDouble( dogZeroMin ), new FinalInterval( dogZeroMin ), p.findMin, p.findMax, minPeakValue, true, service );

			for ( final InterestPoint ip : refined )
			{
				for ( int d = 0; d < n; ++d )
				{
					ip.getL()[ d ] += min[ d ];
					ip.getW()[ d ] += min[ d ];
				}
			}
		}

		final ArrayList< ScaleSpacePeak > result = new ArrayList<>();
		final double sigma = sigmaBase( p.sigmaMin, k, octave.o, 1 );
		final RandomAccess< FloatType > ra1 = dog1.randomAccess();

		for ( final InterestPoint ip : refined )
		{
			final double[] l = ip.getL();
			final double[] pos = new double[ n ];

			for ( int d = 0; d < n; ++d )
				pos[ d ] = l[ d ] * octave.f;

			final double value;

			if ( p.localization == 0 )
			{
				for ( int d = 0; d < n; ++d )
					ra1.setPosition( Math.round( l[ d ] ), d );

				value = ra1.get().get();
			}
			else
			{
				value = ( (InterestPointValue)ip ).getIntensity();
			}

			result.add( new ScaleSpacePeak( pos, value, sigma, octave.o ) );
		}

		return result;
	}

	protected static long key( final int[] location, final Interval interval )
	{
		long key = 0;

		for ( int d = 0; d < interval.numDimensions(); ++d )
			key = key * interval.dimension( d ) + ( location[ d ] - interval.min( d ) );

		return key;
	}

	protected static long key( final double[] location, final Interval interval )
	{
		final int[] l = new int[ interval.numDimensions() ];

		for ( int d = 0; d < l.length; ++d )
			l[ d ] = (int)Math.round( location[ d ] );

		return key( l, interval );
	}

	/**
	 * Spatial extrema of a single DoG level of an octave, the way DoGImgLib2.computeDoG detects them
	 * (no scale neighbors are considered). The positions are in pixels of the octave. For testing
	 * that octave 0 reproduces the single-scale DoG.
	 */
	public static ArrayList< InterestPoint > detectSpatialExtrema( final Octave octave, final int level, final ScaleSpaceParameters p, final ExecutorService service )
	{
		final RandomAccessibleInterval< FloatType > dog = octave.dog.get( level );
		final RandomAccessibleInterval< FloatType > mask = octave.mask == null ? null : Views.interval( octave.mask, dog );

		final float minPeakValue = (float)p.threshold;
		final float minInitialPeakValue = p.localization == 0 ? minPeakValue : minPeakValue / 3.0f;

		final ArrayList< SimplePeak > peaks = DoGImgLib2.findPeaks( dog, mask, minInitialPeakValue, service );

		if ( p.localization == 0 )
			return Localization.noLocalization( peaks, p.findMin, p.findMax, true );

		final long[] min = dog.minAsLongArray();

		for ( final SimplePeak peak : peaks )
			for ( int d = 0; d < peak.location.length; ++d )
				peak.location[ d ] -= min[ d ];

		final RandomAccessibleInterval< FloatType > dogZeroMin = Views.zeroMin( dog );

		final ArrayList< InterestPoint > refined = Localization.computeQuadraticLocalization( peaks, Views.extendMirrorDouble( dogZeroMin ), new FinalInterval( dogZeroMin ), p.findMin, p.findMax, minPeakValue, true, service );

		for ( final InterestPoint ip : refined )
		{
			for ( int d = 0; d < min.length; ++d )
			{
				ip.getL()[ d ] += min[ d ];
				ip.getW()[ d ] += min[ d ];
			}
		}

		return refined;
	}

	/**
	 * Removes duplicates (same sign, closer than combineDistance * 2^octave in pixels of octave 0),
	 * keeping the peak with the higher |value|. The result is sorted by |value|, descending.
	 */
	public static ArrayList< ScaleSpacePeak > mergeDuplicates( final List< ScaleSpacePeak > peaks, final double combineDistance )
	{
		final ArrayList< ScaleSpacePeak > sorted = new ArrayList<>( peaks );
		sorted.sort( ( a, b ) -> Double.compare( Math.abs( b.value ), Math.abs( a.value ) ) );

		if ( sorted.size() < 2 || combineDistance <= 0 )
			return sorted;

		int maxOctave = 0;
		final ArrayList< Integer > indices = new ArrayList<>();

		for ( int i = 0; i < sorted.size(); ++i )
		{
			maxOctave = Math.max( maxOctave, sorted.get( i ).octave );
			indices.add( i );
		}

		final double maxRadius = combineDistance * ( 1L << maxOctave );
		final RadiusNeighborSearchOnKDTree< Integer > search = new RadiusNeighborSearchOnKDTree<>( new KDTree<>( indices, sorted ) );
		final boolean[] duplicate = new boolean[ sorted.size() ];
		final ArrayList< ScaleSpacePeak > result = new ArrayList<>();

		for ( int i = 0; i < sorted.size(); ++i )
		{
			if ( duplicate[ i ] )
				continue;

			final ScaleSpacePeak a = sorted.get( i );
			result.add( a );

			search.search( a, maxRadius, false );

			for ( int j = 0; j < search.numNeighbors(); ++j )
			{
				final int index = search.getSampler( j ).get();

				// stronger peaks were handled before
				if ( index <= i || duplicate[ index ] )
					continue;

				final ScaleSpacePeak b = sorted.get( index );

				if ( Math.signum( a.value ) != Math.signum( b.value ) )
					continue;

				if ( distance( a, b ) <= combineDistance * ( 1L << Math.max( a.octave, b.octave ) ) )
					duplicate[ index ] = true;
			}
		}

		return result;
	}

	/**
	 * @return how many octaves the image allows, i.e. as long as the octave is larger than its longest Gaussian kernel in every dimension (at least 1)
	 */
	public static int autoOctaves( final Interval imageInterval, final ScaleSpaceParameters p )
	{
		final float k = LaPlaceFunctions.computeK( p.steps );
		final float[] sigma = computeSigmas( p.sigmaMin, k, p.steps );
		final float[] sigmaDiff0 = LaPlaceFunctions.computeSigmaDiff( sigma, (float)p.imageSigma );
		final float[] sigmaDiffO = LaPlaceFunctions.computeSigmaDiff( sigma, sigma[ 0 ] );

		int o = 0;

		while ( true )
		{
			final FinalInterval domain = octaveInterval( imageInterval, o );
			final int kernelSize = 2 * Gauss3.halfkernelsize( ( o == 0 ? sigmaDiff0 : sigmaDiffO )[ p.steps + 2 ] ) - 1;

			if ( Intervals.isEmpty( domain ) || minDimension( domain ) <= kernelSize )
				break;

			++o;
		}

		return Math.max( 1, o );
	}

	/**
	 * The interval of octave o that corresponds to an interval of octave 0: pixel p of octave o belongs
	 * to it iff 2^o * p lies inside the interval. Adjacent intervals of octave 0 therefore remain
	 * adjacent (no gaps, no overlaps) at every octave.
	 */
	public static FinalInterval octaveInterval( final Interval interval, final int o )
	{
		final long f = 1L << o;
		final long[] min = new long[ interval.numDimensions() ];
		final long[] max = new long[ interval.numDimensions() ];

		for ( int d = 0; d < min.length; ++d )
		{
			min[ d ] = ceilDiv( interval.min( d ), f );
			max[ d ] = ceilDiv( interval.max( d ) + 1, f ) - 1;
		}

		return new FinalInterval( min, max );
	}

	/**
	 * @return the sigmas of the steps+3 Gaussian levels of an octave (in pixels of the octave), sigma[ 1 ] = sigmaMin
	 */
	public static float[] computeSigmas( final double sigmaMin, final float k, final int steps )
	{
		final float[] fromSigmaMin = LaPlaceFunctions.computeSigma( steps + 1, k, (float)sigmaMin ); // sigmaMin * k^i, i = 0..steps+1
		final float[] sigma = new float[ steps + 3 ];

		sigma[ 0 ] = (float)sigmaMin / k;

		for ( int i = 1; i < sigma.length; ++i )
			sigma[ i ] = fromSigmaMin[ i - 1 ];

		return sigma;
	}

	/**
	 * @return the lower sigma of DoG level 'level' (can be fractional, level 1 is sigmaMin) of an octave in pixels of octave 0
	 */
	public static double sigmaBase( final double sigmaMin, final float k, final int octave, final double level )
	{
		return sigmaMin * Math.pow( k, level - 1 ) * ( 1L << octave );
	}

	public static double sigmaBase( final ScaleSpaceParameters p, final int octave, final double level )
	{
		return sigmaBase( p.sigmaMin, LaPlaceFunctions.computeK( p.steps ), octave, level );
	}

	/**
	 * Copies source over interval into memory, in parallel over tiles
	 */
	public static RandomAccessibleInterval< FloatType > materialize( final RandomAccessible< FloatType > source, final Interval interval, final int[] tileSize, final ExecutorService service )
	{
		final RandomAccessibleInterval< FloatType > img =
				Views.translate(
						Util.getSuitableImgFactory( interval, new FloatType() ).create( interval ),
						interval.minAsLongArray() );

		forEachTile( interval, tileSize, service, tile -> {
			final Cursor< FloatType > in = Views.flatIterable( Views.interval( source, tile ) ).cursor();
			final Cursor< FloatType > out = Views.flatIterable( Views.interval( img, tile ) ).cursor();

			while ( out.hasNext() )
				out.next().set( in.next() );
		} );

		return img;
	}

	/**
	 * Runs op for every tile of interval (tiles start at the min of interval), in parallel
	 */
	public static void forEachTile( final Interval interval, final int[] tileSize, final ExecutorService service, final Consumer< Interval > op )
	{
		final int n = interval.numDimensions();
		final long[] numTiles = new long[ n ];

		for ( int d = 0; d < n; ++d )
			numTiles[ d ] = ( interval.dimension( d ) + tileSize[ d ] - 1 ) / tileSize[ d ];

		final ArrayList< Callable< Void > > tasks = new ArrayList<>();
		final IntervalIterator it = new IntervalIterator( numTiles );

		while ( it.hasNext() )
		{
			it.fwd();

			final long[] min = new long[ n ];
			final long[] max = new long[ n ];

			for ( int d = 0; d < n; ++d )
			{
				min[ d ] = interval.min( d ) + it.getLongPosition( d ) * tileSize[ d ];
				max[ d ] = Math.min( interval.max( d ), min[ d ] + tileSize[ d ] - 1 );
			}

			final FinalInterval tile = new FinalInterval( min, max );

			tasks.add( () -> { op.accept( tile ); return null; } );
		}

		try
		{
			for ( final Future< Void > future : service.invokeAll( tasks ) )
				future.get();
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
	}

	protected static double[] sigmasInBasePixels( final float[] sigma, final long f )
	{
		final double[] s = new double[ sigma.length ];

		for ( int i = 0; i < s.length; ++i )
			s[ i ] = sigma[ i ] * f;

		return s;
	}

	protected static FinalInterval scale( final Interval interval, final long f )
	{
		final long[] min = new long[ interval.numDimensions() ];
		final long[] max = new long[ interval.numDimensions() ];

		for ( int d = 0; d < min.length; ++d )
		{
			min[ d ] = interval.min( d ) * f;
			max[ d ] = interval.max( d ) * f;
		}

		return new FinalInterval( min, max );
	}

	protected static long minDimension( final Interval interval )
	{
		long min = Long.MAX_VALUE;

		for ( int d = 0; d < interval.numDimensions(); ++d )
			min = Math.min( min, interval.dimension( d ) );

		return min;
	}

	protected static long ceilDiv( final long a, final long b )
	{
		return -Math.floorDiv( -a, b );
	}

	protected static double distance( final RealLocalizable a, final RealLocalizable b )
	{
		double sum = 0;

		for ( int d = 0; d < a.numDimensions(); ++d )
		{
			final double diff = a.getDoublePosition( d ) - b.getDoublePosition( d );
			sum += diff * diff;
		}

		return Math.sqrt( sum );
	}
}
