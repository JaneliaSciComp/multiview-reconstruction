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
import java.util.Date;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.DoubleConsumer;
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
import net.imglib2.RealRandomAccess;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.localextrema.RefinedPeak;
import net.imglib2.algorithm.localextrema.SubpixelLocalization;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
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
 * sigmaMin * k^(level-1) * 2^octave, in x pixels of octave 0.
 *
 * Anisotropic voxels: with ScaleSpaceParameters.anisotropy (voxel size per dimension relative to x)
 * the sigma of every level in dimension d is sigma_i / anisotropy[ d ], i.e. the less resolved
 * dimensions are blurred less so that the Gaussians are isotropic in physical units. The input of
 * octave o carries, per dimension, max( sigma_0 / anisotropy[ d ], imageSigma / 2^o ) of blur and
 * only the difference to the target is applied; where the target is below that, nothing is applied
 * in that dimension (the finest levels then act slice-wise).
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
		/** the index of the octave, 0 is the image that was handed in */
		final public int o;

		/** the subsampling factor of this octave relative to octave 0, 2^o */
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

		/** sigma of the Gaussian that is applied to the input of this octave to obtain each level, per dimension and level */
		public float[][] sigmaDiff;

		/** the (normalized) image of this octave extended to infinity, and the mask (null if none), both subsampled by f */
		public RandomAccessible< FloatType > input, mask;

		/** the lazy Gaussian levels 0..steps+2 (sigma_i = sigmaMin * k^(i-1)) and the lazy DoG levels 0..steps+1 (d_i = (g_(i+1) - g_i) / (k-1)) */
		final public ArrayList< RandomAccessibleInterval< FloatType > > gauss = new ArrayList<>();
		final public ArrayList< RandomAccessibleInterval< FloatType > > dog = new ArrayList<>();

		/** the DoG levels stacked, the last dimension is the scale */
		public RandomAccessibleInterval< FloatType > stack;

		/**
		 * Sets up the intervals of the octave (domain, block, det, ref); the levels are added by buildLevels, domS and need by buildScaleSpace
		 *
		 * @param o - the octave
		 * @param imageInterval - the entire image in pixels of octave 0
		 * @param processInterval - the block in which peaks are searched, in pixels of octave 0
		 * @param refineMargin - how far (in pixels of this octave) the refinement may move a peak out of the block
		 */
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

		/** a maximum of the image (a minimum of the DoG, as DoGImgLib2.findPeaks reports it), otherwise a minimum; the refined value can have either sign */
		final public boolean isMax;

		/** found by the finest-level pass (detectFinestLevel), i.e. a spatial extremum of DoG level 1 that is no extremum in scale */
		final public boolean finest;

		/** key() of the integer position at which the peak was found at DoG level 1 of octave 0 (extrema in space and scale of level 1 and all finest-level peaks), -1 otherwise */
		final public long level1Key;

		/**
		 * @param isMax - whether the peak is a maximum of the image (a minimum of the DoG, see DoGImgLib2.findPeaks), otherwise a minimum
		 */
		public ScaleSpacePeak( final double[] l, final double value, final double sigma, final int octave, final boolean isMax )
		{
			this( l, value, sigma, octave, isMax, false, -1 );
		}

		/**
		 * @param l - the refined position in pixels of octave 0 (kept by reference)
		 * @param value - the refined DoG response (negative for maxima of the image, usually)
		 * @param sigma - the lower sigma of the DoG level in pixels of octave 0, see sigmaBase (fitted for the finest level)
		 * @param octave - the octave the peak was found in
		 * @param isMax - whether the peak is a maximum of the image (a minimum of the DoG), otherwise a minimum
		 * @param finest - whether the peak comes from the finest-level pass (no extremum in scale)
		 * @param level1Key - key() of the integer position at DoG level 1 of octave 0 if the peak was found there, -1 otherwise
		 */
		public ScaleSpacePeak( final double[] l, final double value, final double sigma, final int octave, final boolean isMax, final boolean finest, final long level1Key )
		{
			this.l = l;
			this.value = value;
			this.sigma = sigma;
			this.octave = octave;
			this.isMax = isMax;
			this.finest = finest;
			this.level1Key = level1Key;
		}

		/** @return the number of spatial dimensions of the position (the scale is not a dimension here) */
		@Override
		public int numDimensions() { return l.length; }

		/** @return the position in dimension d in pixels of octave 0 */
		@Override
		public double getDoublePosition( final int d ) { return l[ d ]; }

		/** @return the position in dimension d in pixels of octave 0 */
		@Override
		public float getFloatPosition( final int d ) { return (float)l[ d ]; }

		/** Copies the position (pixels of octave 0) into the array */
		@Override
		public void localize( final double[] position )
		{
			for ( int d = 0; d < l.length; ++d )
				position[ d ] = l[ d ];
		}

		/** Copies the position (pixels of octave 0) into the array */
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
		return toInterestPoints( computeScaleSpacePeaks( input, imageInterval, processInterval, mask, p, service ), false );
	}

	/**
	 * Detects the peaks for several thresholds at once: the candidates are computed once down to the lowest threshold
	 * (computeScaleSpaceCandidates) and filtered per threshold (filterCandidates), so the cost is that of one run at the
	 * lowest threshold plus a filter and a duplicate merge per threshold. The result for a threshold is the one of
	 * computeDoGScaleSpace at that threshold, up to the initial pre-filter of the extrema, which is min( thresholds ) / 3
	 * instead of threshold / 3 (identical on all data tested). BigStitcher-Spark calls this per block and merges the
	 * blocks per threshold; the interactive preview is not involved.
	 *
	 * @param thresholds - the thresholds (finite, &gt; 0), the results are returned in this order
	 * @return one list per threshold, each with its own InterestPointSS objects and positions (ids 0..n-1 per list)
	 */
	public static < T extends RealType< T >, M extends RealType< M > > ArrayList< ArrayList< InterestPointSS > > computeDoGScaleSpace(
			final RandomAccessible< T > input,
			final Interval imageInterval,
			final Interval processInterval,
			final RandomAccessible< M > mask,
			final ScaleSpaceParameters p,
			final double[] thresholds,
			final ExecutorService service )
	{
		if ( thresholds == null || thresholds.length == 0 )
			throw new IllegalArgumentException( "No thresholds given." );

		double min = Double.POSITIVE_INFINITY;

		for ( final double t : thresholds )
		{
			if ( !( t > 0 ) || Double.isInfinite( t ) )
				throw new IllegalArgumentException( "Thresholds must be finite and > 0, but one of them is " + t + "." );

			min = Math.min( min, t );
		}

		final ScaleSpaceParameters pMin = new ScaleSpaceParameters( p );
		pMin.threshold = min;

		// all candidates down to the lowest threshold (no cap, a raised floor would leave the lowest threshold incomplete)
		final Candidates candidates = computeScaleSpaceCandidates( input, imageInterval, processInterval, mask, pMin, 0, service );

		if ( candidates.threshold > min )
			throw new IllegalStateException( "The candidates are complete down to " + candidates.threshold + " only, not to " + min + "." );

		final ArrayList< ArrayList< InterestPointSS > > result = new ArrayList<>();

		for ( final double t : thresholds )
			result.add( toInterestPoints( filterCandidates( candidates, t, p.detectFinestLevel, p.findMin, p.findMax, p.localization, p.combineDistance ), true ) );

		return result;
	}

	/**
	 * @param peaks - merged peaks, sorted by |response|
	 * @param clonePositions - whether to copy the positions: the lists of several thresholds share their ScaleSpacePeak objects,
	 * and DownsampleTools.correctForDownsampling transforms the positions of InterestPoints in place
	 * @return the peaks as InterestPointSS with ids 0..n-1 in the order of the list
	 */
	protected static ArrayList< InterestPointSS > toInterestPoints( final ArrayList< ScaleSpacePeak > peaks, final boolean clonePositions )
	{
		final ArrayList< InterestPointSS > result = new ArrayList<>();

		for ( int i = 0; i < peaks.size(); ++i )
		{
			final ScaleSpacePeak peak = peaks.get( i );
			result.add( new InterestPointSS( i, clonePositions ? peak.l.clone() : peak.l, peak.value, peak.sigma ) );
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
	 * All candidate peaks of a scale space down to one threshold, before duplicates are removed: the
	 * extrema in space and scale (regular) and all spatial extrema of the finest level (finest, whether
	 * they are an extremum in scale or not), both for minima and maxima. filterCandidates() turns them
	 * into the result of computeScaleSpacePeaks for any higher threshold, with or without the finest
	 * level and for either type without computing anything again (the interactive preview).
	 */
	public static class Candidates
	{
		/** the extrema in space and scale of all octaves (regular) and all spatial extrema of DoG level 1 of octave 0 (finest), unmerged, minima and maxima */
		final public ArrayList< ScaleSpacePeak > regular, finest;

		/** the threshold the candidates are complete down to: the one they were computed with, raised to the cut-off |value| if there were more than allowed */
		final public double threshold;

		/** sigma of the coarsest detectable level in pixels of octave 0 */
		final public double sigmaMax;

		/** the number of octaves of the scale space */
		final public int octaves;

		/**
		 * @param regular - the extrema in space and scale, unmerged
		 * @param finest - all spatial extrema of DoG level 1 of octave 0, unmerged
		 * @param threshold - the threshold the candidates are complete down to
		 * @param sigmaMax - sigma of the coarsest detectable level in pixels of octave 0
		 * @param octaves - the number of octaves
		 */
		public Candidates( final ArrayList< ScaleSpacePeak > regular, final ArrayList< ScaleSpacePeak > finest, final double threshold, final double sigmaMax, final int octaves )
		{
			this.regular = regular;
			this.finest = finest;
			this.threshold = threshold;
			this.sigmaMax = sigmaMax;
			this.octaves = octaves;
		}

		/** @return the number of candidates, regular and finest together */
		public int size() { return regular.size() + finest.size(); }
	}

	/**
	 * Computes the candidates (see Candidates) down to p.threshold, minima and maxima.
	 *
	 * @param maxCandidates - if more candidates are found only the strongest maxCandidates are kept and Candidates.threshold is raised accordingly (0 = all)
	 */
	public static < T extends RealType< T >, M extends RealType< M > > Candidates computeScaleSpaceCandidates(
			final RandomAccessible< T > input,
			final Interval imageInterval,
			final Interval processInterval,
			final RandomAccessible< M > mask,
			final ScaleSpaceParameters p,
			final int maxCandidates,
			final ExecutorService service )
	{
		return computeScaleSpaceCandidates( input, imageInterval, processInterval, mask, p, maxCandidates, service, null );
	}

	/**
	 * @param progress - called with the progress 0..1 after the scale space is set up, after every octave and at the end (e.g. IJ::showProgress), can be null
	 */
	public static < T extends RealType< T >, M extends RealType< M > > Candidates computeScaleSpaceCandidates(
			final RandomAccessible< T > input,
			final Interval imageInterval,
			final Interval processInterval,
			final RandomAccessible< M > mask,
			final ScaleSpaceParameters p,
			final int maxCandidates,
			final ExecutorService service,
			final DoubleConsumer progress )
	{
		final ScaleSpaceParameters pBoth = new ScaleSpaceParameters( p );
		pBoth.findMin = pBoth.findMax = true;

		final List< Octave > octaves = buildScaleSpace( input, imageInterval, processInterval, mask, pBoth, service );

		if ( progress != null )
			progress.accept( 0.05 );

		final float k = LaPlaceFunctions.computeK( pBoth.steps );
		final ArrayList< ScaleSpacePeak > regular = new ArrayList<>();

		// the finest octave dominates the work, the coarser ones are 1/8 each
		double work = 0, totalWork = 0;

		for ( final Octave octave : octaves )
			totalWork += 1.0 / ( 1L << ( 3 * octave.o ) );

		for ( final Octave octave : octaves )
		{
			regular.addAll( detectOctave( octave, pBoth, k, service, false ) );

			work += 1.0 / ( 1L << ( 3 * octave.o ) );

			if ( progress != null )
				progress.accept( 0.05 + 0.85 * work / totalWork );
		}

		// no exclusion: all spatial extrema of the finest level, filterCandidates excludes those that are extrema in scale at a given threshold
		final ArrayList< ScaleSpacePeak > finest = detectFinestLevel( octaves.get( 0 ), new HashSet<>(), pBoth, k, service );

		if ( progress != null )
			progress.accept( 0.95 );

		final double sigmaMax = sigmaBase( pBoth.sigmaMin, k, octaves.size() - 1, pBoth.steps );
		double threshold = pBoth.threshold;

		if ( maxCandidates > 0 && regular.size() + finest.size() > maxCandidates )
		{
			final double[] values = new double[ regular.size() + finest.size() ];
			int i = 0;

			for ( final ScaleSpacePeak peak : regular )
				values[ i++ ] = Math.abs( peak.value );

			for ( final ScaleSpacePeak peak : finest )
				values[ i++ ] = Math.abs( peak.value );

			Arrays.sort( values );

			// everything at or below the cut-off is dropped, so the candidates are complete down to it
			threshold = values[ values.length - 1 - maxCandidates ];

			final double cutoff = threshold;
			regular.removeIf( peak -> !( Math.abs( peak.value ) > cutoff ) );
			finest.removeIf( peak -> !( Math.abs( peak.value ) > cutoff ) );

			if ( !DoGImgLib2.silent )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): More than " + maxCandidates + " candidates, keeping the strongest ones, |response| > " + threshold );
		}

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): " + regular.size() + " candidates in space and scale, " + finest.size() + " at the finest level (|response| > " + threshold + ")." );

		if ( progress != null )
			progress.accept( 1.0 );

		return new Candidates( regular, finest, threshold, sigmaMax, octaves.size() );
	}

	/**
	 * The result of computeScaleSpacePeaks at a threshold &gt;= candidates.threshold, computed from the candidates
	 * only (the only difference: the initial peaks were pre-filtered at candidates.threshold / 3 instead of threshold / 3).
	 */
	public static ArrayList< ScaleSpacePeak > filterCandidates( final Candidates candidates, final double threshold, final boolean detectFinestLevel, final boolean findMin, final boolean findMax, final int localization, final double combineDistance )
	{
		// the order of computeScaleSpacePeaks: octave 0, its finest level, the coarser octaves (matters for equal |values| in mergeDuplicates)
		final ArrayList< ScaleSpacePeak > list = new ArrayList<>();
		final ArrayList< ScaleSpacePeak > coarser = new ArrayList<>();
		final HashSet< Long > level1 = new HashSet<>();

		for ( final ScaleSpacePeak peak : candidates.regular )
		{
			if ( !accept( peak, threshold, findMin, findMax, localization ) )
				continue;

			if ( peak.octave == 0 )
			{
				list.add( peak );

				if ( peak.level1Key >= 0 )
					level1.add( peak.level1Key );
			}
			else
			{
				coarser.add( peak );
			}
		}

		if ( detectFinestLevel )
			for ( final ScaleSpacePeak peak : candidates.finest )
				if ( accept( peak, threshold, findMin, findMax, localization ) && !level1.contains( peak.level1Key ) )
					list.add( peak );

		list.addAll( coarser );

		return mergeDuplicates( list, combineDistance );
	}

	/**
	 * @return whether a candidate passes the type and threshold tests of the detection (the type is the one of the extremum as found, the refined value can have either sign)
	 */
	protected static boolean accept( final ScaleSpacePeak peak, final double threshold, final boolean findMin, final boolean findMax, final int localization )
	{
		if ( ( peak.isMax && !findMax ) || ( !peak.isMax && !findMin ) )
			return false;

		// findPeaks keeps |value| >= threshold, the refinement > threshold
		if ( localization == 0 )
			return Math.abs( peak.value ) >= threshold;
		else
			return Math.abs( peak.value ) > threshold;
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
		// intensity range: given per view (the driver computes it over the whole view if the user did not set it), never computed here
		// so that a block never scans the entire image and all blocks of a view normalize identically
		//
		if ( Double.isNaN( p.minIntensity ) || Double.isNaN( p.maxIntensity ) || Double.isInfinite( p.minIntensity ) || Double.isInfinite( p.maxIntensity ) || p.minIntensity == p.maxIntensity )
			throw new IllegalArgumentException( "The intensity range (minIntensity, maxIntensity) must be set, it is [" + p.minIntensity + ", " + p.maxIntensity + "]; compute it per view (e.g. FusionTools.minMax) before calling the scale space." );

		final float min = (float)p.minIntensity;
		final float max = (float)p.maxIntensity;

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): intensity range [" + min + ", " + max + "], the image is normalized with it." );

		final RandomAccessible< FloatType > inputFloat = ImgLib2Tools.normalizeVirtual( input, min, max );

		//
		// sigmas: level 1 is sigmaMin, the same float arithmetic as DoGImgLib2.computeSigmas (so that steps=4 reproduces it)
		//
		final float k = LaPlaceFunctions.computeK( p.steps );
		final float[] sigma = computeSigmas( p.sigmaMin, k, p.steps );

		if ( !( sigma[ 0 ] > p.imageSigma ) )
			throw new IllegalArgumentException( "sigmaMin/k (" + sigma[ 0 ] + ") must be larger than the image sigma (" + p.imageSigma + ")" );

		final double[] anisotropy = validateAnisotropy( p, n );

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

			octave.sigmaDiff = computeSigmaDiff( sigma, anisotropy, (float)p.imageSigma, o, n );
			octaves.add( octave );
		}

		// from the coarsest octave down: where does each octave need its input and the hand-over level
		for ( int o = octaves.size() - 1; o >= 0; --o )
		{
			final Octave octave = octaves.get( o );

			final long[] haloMax = halfKernelSizes( octave.sigmaDiff, p.steps + 2 );
			final long[] haloS = halfKernelSizes( octave.sigmaDiff, p.steps );

			if ( o == octaves.size() - 1 )
				octave.domS = octave.ref;
			else
				octave.domS = Intervals.intersect( Intervals.union( octave.ref, scale( octaves.get( o + 1 ).need, 2 ) ), octave.domain );

			octave.need = Intervals.intersect( Intervals.union( Intervals.expand( octave.ref, haloMax ), Intervals.expand( octave.domS, haloS ) ), octave.domain );
		}

		if ( !DoGImgLib2.silent )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): computing scale space DoG with (sigmaMin=" + p.sigmaMin + ", steps=" + p.steps + ", k=" + k + ", octaves=" + octaves.size() + ", threshold=" + p.threshold +
					( anisotropy == null ? ", isotropic in pixels)" : ", anisotropy (voxel size per dimension relative to x)=" + Util.printCoordinates( anisotropy ) + ")" ) );

			for ( final Octave octave : octaves )
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): octave " + octave.o + ": domain=" + Util.printInterval( octave.domain ) + ", block=" + Util.printInterval( octave.block ) +
						", sigmas (in x pixels of octave 0)=" + Util.printCoordinates( sigmasInBasePixels( sigma, octave.f ) ) );

				// levels that are not blurred at all in a dimension (the input carries more blur than the target)
				for ( int d = 0; d < n; ++d )
				{
					String clamped = "";

					for ( int i = octave.o == 0 ? 0 : 1; i < sigma.length; ++i )
						if ( octave.sigmaDiff[ d ][ i ] == 0 )
							clamped += ( clamped.isEmpty() ? "" : ", " ) + i;

					if ( !clamped.isEmpty() )
						IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): octave " + octave.o + ": no additional blur in dimension " + d + " for the Gaussian levels " + clamped + " (the image carries more blur than the target there)." );
				}
			}
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

				for ( int d = 0; d < n; ++d )
					sigma[ d ] = octave.sigmaDiff[ d ][ i ];

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
	 * Extrema in space and scale of one octave, refined, mapped to pixels of octave 0 (plus the finest level for octave 0 if p.detectFinestLevel)
	 */
	public static ArrayList< ScaleSpacePeak > detectOctave( final Octave octave, final ScaleSpaceParameters p, final float k, final ExecutorService service )
	{
		return detectOctave( octave, p, k, service, p.detectFinestLevel );
	}

	/**
	 * @param finestLevel - whether to add the finest level (detectFinestLevel) for octave 0, overrides p.detectFinestLevel
	 */
	public static ArrayList< ScaleSpacePeak > detectOctave( final Octave octave, final ScaleSpaceParameters p, final float k, final ExecutorService service, final boolean finestLevel )
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

		// the minima and/or maxima (the type of each peak is kept, the refined value can have either sign)
		final ArrayList< SimplePeak > accepted = filterPolarity( peaks, p.findMin, p.findMax );

		if ( p.localization == 0 )
		{
			final RandomAccess< FloatType > ra = octave.stack.randomAccess();
			final ArrayList< InterestPoint > ips = Localization.noLocalization( accepted, true, true, true );

			for ( int i = 0; i < ips.size(); ++i )
			{
				final double[] l = ips.get( i ).getL();

				// noLocalization stores |value|, we want the sign
				for ( int d = 0; d <= n; ++d )
					ra.setPosition( Math.round( l[ d ] ), d );

				final long key = Math.round( l[ n ] ) == 1 ? key( l, octave.det ) : -1;

				if ( key >= 0 )
					level1.add( key );

				result.add( toScaleSpacePeak( l, ra.get().get(), accepted.get( i ).isMax, octave, p, k, octave.o == 0 ? key : -1 ) );
			}
		}
		else
		{
			final long[] min = octave.stack.minAsLongArray();

			for ( final SimplePeak peak : accepted )
				for ( int d = 0; d < peak.location.length; ++d )
					peak.location[ d ] -= min[ d ];

			final RandomAccessibleInterval< FloatType > stackZeroMin = Views.zeroMin( octave.stack );

			// quadratic fit in space and scale (same settings as Localization.computeQuadraticLocalization), invalid fits are returned, too (one per peak)
			final ArrayList< RefinedPeak< Point > > refined = refinePeaks( accepted, Views.extendMirrorDouble( stackZeroMin ), new FinalInterval( stackZeroMin ), true, true, service );

			for ( int i = 0; i < refined.size(); ++i )
			{
				final RefinedPeak< Point > r = refined.get( i );

				if ( !r.isValid() || !( Math.abs( r.getValue() ) > minPeakValue ) )
					continue; // a failed fit at level 1 (e.g. a structure smaller than sigmaMin) is picked up by detectFinestLevel

				final double[] l = new double[ n + 1 ];

				for ( int d = 0; d <= n; ++d )
					l[ d ] = r.getDoublePosition( d ) + min[ d ];

				long key = -1;

				if ( r.getOriginalPeak().getIntPosition( n ) == 1 )
				{
					final double[] original = new double[ n ];

					for ( int d = 0; d < n; ++d )
						original[ d ] = r.getOriginalPeak().getIntPosition( d ) + min[ d ];

					key = key( original, octave.det );
					level1.add( key );
				}

				result.add( toScaleSpacePeak( l, r.getValue(), accepted.get( i ).isMax, octave, p, k, octave.o == 0 ? key : -1 ) );
			}
		}

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Octave " + octave.o + ": " + result.size() + " peaks after refinement." );

		// structures smaller than sigmaMin are no extremum in scale, but we might want them anyways
		if ( octave.o == 0 && finestLevel )
		{
			final ArrayList< ScaleSpacePeak > finest = detectFinestLevel( octave, level1, p, k, service );

			if ( !DoGImgLib2.silent )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Octave " + octave.o + ": " + finest.size() + " additional peaks at the finest level (structures smaller than sigmaMin)." );

			result.addAll( finest );
		}

		return result;
	}

	/**
	 * @return the peaks of the requested type(s) in their order
	 */
	public static ArrayList< SimplePeak > filterPolarity( final ArrayList< SimplePeak > peaks, final boolean findMin, final boolean findMax )
	{
		final ArrayList< SimplePeak > accepted = new ArrayList<>();

		for ( final SimplePeak peak : peaks )
			if ( ( peak.isMax && findMax ) || ( peak.isMin && findMin ) )
				accepted.add( peak );

		return accepted;
	}

	/**
	 * Quadratic fit in space and scale with the settings of Localization.computeQuadraticLocalization,
	 * but also returning the peaks whose fit failed (RefinedPeak.isValid() == false), so the result has
	 * one entry per peak of the requested type(s), in their order
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
	protected static ScaleSpacePeak toScaleSpacePeak( final double[] l, final double value, final boolean isMax, final Octave octave, final ScaleSpaceParameters p, final float k )
	{
		return toScaleSpacePeak( l, value, isMax, octave, p, k, -1 );
	}

	/**
	 * @param level1Key - key() of the integer position if the peak was found at level 1 of octave 0, -1 otherwise
	 */
	protected static ScaleSpacePeak toScaleSpacePeak( final double[] l, final double value, final boolean isMax, final Octave octave, final ScaleSpaceParameters p, final float k, final long level1Key )
	{
		final int n = l.length - 1;
		final double[] pos = new double[ n ];

		for ( int d = 0; d < n; ++d )
			pos[ d ] = l[ d ] * octave.f;

		return new ScaleSpacePeak( pos, value, sigmaBase( p.sigmaMin, k, octave.o, l[ n ] ), octave.o, isMax, false, level1Key );
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

		// refine in space only, as the single-scale DoG does, keeping the type of each peak and the key of the integer position it was found at
		final ArrayList< SimplePeak > accepted = filterPolarity( candidates, p.findMin, p.findMax );
		final ArrayList< ScaleSpacePeak > result = new ArrayList<>();
		final double sigma = sigmaBase( p.sigmaMin, k, octave.o, 1 );

		if ( p.localization == 0 )
		{
			final RandomAccess< FloatType > ra1 = dog1.randomAccess();
			final ArrayList< InterestPoint > ips = Localization.noLocalization( accepted, true, true, true );

			for ( int i = 0; i < ips.size(); ++i )
			{
				final double[] l = ips.get( i ).getL();

				// noLocalization stores |value|, we want the sign
				for ( int d = 0; d < n; ++d )
					ra1.setPosition( Math.round( l[ d ] ), d );

				result.add( new ScaleSpacePeak( scale( l, octave.f ), ra1.get().get(), sigma, octave.o, accepted.get( i ).isMax, true, key( l, octave.det ) ) );
			}
		}
		else
		{
			final long[] min = dog1.minAsLongArray();

			for ( final SimplePeak peak : accepted )
				for ( int d = 0; d < n; ++d )
					peak.location[ d ] -= min[ d ];

			final RandomAccessibleInterval< FloatType > dogZeroMin = Views.zeroMin( dog1 );

			// the quadratic fit of Localization.computeQuadraticLocalization (same settings), failed fits are dropped as there
			final ArrayList< RefinedPeak< Point > > refined = refinePeaks( accepted, Views.extendMirrorDouble( dogZeroMin ), new FinalInterval( dogZeroMin ), true, true, service );

			for ( int i = 0; i < refined.size(); ++i )
			{
				final RefinedPeak< Point > r = refined.get( i );

				if ( !r.isValid() || !( Math.abs( r.getValue() ) > minPeakValue ) )
					continue;

				final double[] l = new double[ n ];
				final int[] original = new int[ n ];

				for ( int d = 0; d < n; ++d )
				{
					l[ d ] = r.getDoublePosition( d ) + min[ d ];
					original[ d ] = r.getOriginalPeak().getIntPosition( d ) + (int)min[ d ];
				}

				result.add( new ScaleSpacePeak( scale( l, octave.f ), r.getValue(), sigma, octave.o, accepted.get( i ).isMax, true, key( original, octave.det ) ) );
			}
		}

		// sigmaMin is only an upper bound of their scale, estimate it from the responses of the finest levels
		if ( p.fitFinestSize && !result.isEmpty() )
			fitFinestSizes( result, octave, p, k );

		return result;
	}

	/**
	 * Replaces the sigma of the finest-level peaks (sigmaMin, an upper bound as they are no extremum in scale) by the
	 * scale of maximal response of a Gaussian blob fitted to the responses of the first p.finestFitLevels DoG levels at
	 * the (refined) peak position, see fitBlobSize, or by p.finestFallbackSigma where the fit is unreliable; never above sigmaMin.
	 */
	protected static void fitFinestSizes( final ArrayList< ScaleSpacePeak > peaks, final Octave octave, final ScaleSpaceParameters p, final float k )
	{
		final int n = octave.domain.numDimensions();
		final int m = Math.max( 2, Math.min( p.finestFitLevels, octave.dog.size() ) );
		final float[] sigma = computeSigmas( p.sigmaMin, k, p.steps );
		final double[][] totalSigma = totalSigmaOctave0( sigma, p.anisotropy, p.imageSigma, n );
		final double kMin1Inv = LaPlaceFunctions.computeKWeight( k );
		final double bMax = 2.5 * p.sigmaMin;

		// the scale of maximal response of a Gaussian blob of size b in n dimensions is b * sqrt( 2 / n )
		final double sigmaPerSize = Math.sqrt( 2.0 / n );

		final ArrayList< RealRandomAccess< FloatType > > access = new ArrayList<>();

		for ( int i = 0; i < m; ++i )
			access.add( Views.interpolate( Views.extendMirrorSingle( octave.dog.get( i ) ), new NLinearInterpolatorFactory<>() ).realRandomAccess() );

		final double[] responses = new double[ m ];
		final double[] pos = new double[ n ];
		int fitted = 0;

		for ( int j = 0; j < peaks.size(); ++j )
		{
			final ScaleSpacePeak peak = peaks.get( j );

			for ( int d = 0; d < n; ++d )
				pos[ d ] = peak.l[ d ] / octave.f;

			for ( int i = 0; i < m; ++i )
			{
				access.get( i ).setPosition( pos );
				responses[ i ] = access.get( i ).get().get();
			}

			final double[] fit = fitBlobSize( responses, totalSigma, p.anisotropy, kMin1Inv, bMax );
			final boolean reliable = fit[ 0 ] < 0.98 * bMax && fit[ 2 ] <= p.finestFitMaxResidual;
			final double sigmaFit = Math.min( peak.sigma, reliable ? fit[ 0 ] * sigmaPerSize : p.finestFallbackSigma );

			if ( reliable )
				++fitted;

			if ( sigmaFit != peak.sigma )
				peaks.set( j, new ScaleSpacePeak( peak.l, peak.value, sigmaFit, peak.octave, peak.isMax, peak.finest, peak.level1Key ) );
		}

		if ( !DoGImgLib2.silent )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Octave " + octave.o + ": size fitted for " + fitted + " of " + peaks.size() + " finest-level peaks (" + m + " levels), the others get sigma = " + Math.min( p.sigmaMin, p.finestFallbackSigma ) );
	}

	/**
	 * @return the total blur (the image blur included) of the Gaussian levels of octave 0 per level and dimension, in
	 * pixels of the respective dimension: max( sigma_i / anisotropy[ d ], imageSigma ), see computeSigmaDiff
	 */
	public static double[][] totalSigmaOctave0( final float[] sigma, final double[] anisotropy, final double imageSigma, final int n )
	{
		final double[][] t = new double[ sigma.length ][ n ];

		for ( int i = 0; i < sigma.length; ++i )
			for ( int d = 0; d < n; ++d )
				t[ i ][ d ] = Math.max( sigma[ i ] / ( anisotropy == null ? 1.0 : anisotropy[ d ] ), imageSigma );

		return t;
	}

	/**
	 * The DoG responses at the center of a Gaussian blob of size b (in x pixels, isotropic in physical units, i.e.
	 * b / anisotropy[ d ] in pixels of dimension d) up to a factor: the Gaussian level with total blur t has the
	 * value prod_d ( b_d^2 + t_d^2 )^(-1/2) there (times the mass of the blob), the DoG is their difference / ( k - 1 )
	 *
	 * @param totalSigma - [level][dimension], one level more than responses are computed
	 */
	public static void modelResponses( final double b, final double[][] totalSigma, final double[] anisotropy, final double kMin1Inv, final double[] responses )
	{
		final int n = totalSigma[ 0 ].length;
		final double[] g = new double[ responses.length + 1 ];

		for ( int i = 0; i < g.length; ++i )
		{
			double v = 1;

			for ( int d = 0; d < n; ++d )
			{
				final double bd = b / ( anisotropy == null ? 1.0 : anisotropy[ d ] );
				v /= Math.sqrt( bd * bd + totalSigma[ i ][ d ] * totalSigma[ i ][ d ] );
			}

			g[ i ] = v;
		}

		for ( int i = 0; i < responses.length; ++i )
			responses[ i ] = ( g[ i + 1 ] - g[ i ] ) * kMin1Inv;
	}

	/**
	 * Fits a Gaussian blob to the responses of the finest DoG levels at a point: the finest-level detections are no
	 * extremum in scale, so their scale is below the sampled levels, but the decay of the response over the first
	 * levels still tells their size. Model: responses_i = C * modelResponses( b )_i; C is linear and solved for every
	 * b, b is found by a grid search over [0, bMax] with a parabolic refinement of the minimum.
	 *
	 * @param responses - the DoG responses of levels 0..m-1 at the point
	 * @param totalSigma - [level 0..m][dimension] total blur of the Gaussian levels (see totalSigmaOctave0)
	 * @param anisotropy - voxel size per dimension relative to x (null = isotropic)
	 * @param kMin1Inv - the weight of the DoG, 1 / ( k - 1 )
	 * @param bMax - the largest size tried
	 * @return { b (in x pixels), C, relative residual sqrt( sum( ( d - C m )^2 ) / sum( d^2 ) ) }; b == bMax means the minimum is at the end of the range
	 */
	public static double[] fitBlobSize( final double[] responses, final double[][] totalSigma, final double[] anisotropy, final double kMin1Inv, final double bMax )
	{
		final int m = responses.length;
		final int grid = 250;
		final double[] model = new double[ m ];
		final double[] residual = new double[ grid + 1 ];

		double sumD2 = 0;

		for ( int i = 0; i < m; ++i )
			sumD2 += responses[ i ] * responses[ i ];

		int bestG = 0;
		double bestC = 0;

		for ( int g = 0; g <= grid; ++g )
		{
			modelResponses( bMax * g / grid, totalSigma, anisotropy, kMin1Inv, model );

			double num = 0, den = 0;

			for ( int i = 0; i < m; ++i )
			{
				num += responses[ i ] * model[ i ];
				den += model[ i ] * model[ i ];
			}

			final double c = den > 0 ? num / den : 0;
			double r = 0;

			for ( int i = 0; i < m; ++i )
			{
				final double diff = responses[ i ] - c * model[ i ];
				r += diff * diff;
			}

			residual[ g ] = r;

			if ( r < residual[ bestG ] )
			{
				bestG = g;
				bestC = c;
			}
		}

		double b = bMax * bestG / grid;
		double c = bestC;
		double r = residual[ bestG ];

		// parabolic refinement between the neighbors of the best grid point, C and the residual are recomputed there
		if ( bestG > 0 && bestG < grid )
		{
			final double y0 = residual[ bestG - 1 ], y1 = residual[ bestG ], y2 = residual[ bestG + 1 ];
			final double denom = y0 - 2 * y1 + y2;

			if ( denom > 0 )
			{
				b += 0.5 * ( y0 - y2 ) / denom * bMax / grid;

				modelResponses( b, totalSigma, anisotropy, kMin1Inv, model );

				double num = 0, den = 0;

				for ( int i = 0; i < m; ++i )
				{
					num += responses[ i ] * model[ i ];
					den += model[ i ] * model[ i ];
				}

				c = den > 0 ? num / den : 0;
				r = 0;

				for ( int i = 0; i < m; ++i )
				{
					final double diff = responses[ i ] - c * model[ i ];
					r += diff * diff;
				}
			}
		}

		return new double[] { b, c, sumD2 > 0 ? Math.sqrt( r / sumD2 ) : 1.0 };
	}

	/**
	 * @return the position in pixels of octave 0
	 */
	protected static double[] scale( final double[] l, final long f )
	{
		final double[] pos = new double[ l.length ];

		for ( int d = 0; d < l.length; ++d )
			pos[ d ] = l[ d ] * f;

		return pos;
	}

	/**
	 * @param location - an integer position inside the interval
	 * @param interval - the interval the position is keyed in (octave.det for the level-1 keys)
	 * @return the row-major linear index of the position in the interval, a unique key per integer position
	 */
	protected static long key( final int[] location, final Interval interval )
	{
		long key = 0;

		for ( int d = 0; d < interval.numDimensions(); ++d )
			key = key * interval.dimension( d ) + ( location[ d ] - interval.min( d ) );

		return key;
	}

	/**
	 * @return key() of the position rounded to integers (only the first interval.numDimensions() entries are used, e.g. not the level)
	 */
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
		final int n = imageInterval.numDimensions();
		final float k = LaPlaceFunctions.computeK( p.steps );
		final float[] sigma = computeSigmas( p.sigmaMin, k, p.steps );
		final double[] anisotropy = validateAnisotropy( p, n );

		int o = 0;

		while ( true )
		{
			final FinalInterval domain = octaveInterval( imageInterval, o );

			if ( Intervals.isEmpty( domain ) )
				break;

			// the octave must be larger than its longest Gaussian kernel in every dimension
			final float[][] sigmaDiff = computeSigmaDiff( sigma, anisotropy, (float)p.imageSigma, o, n );
			boolean fits = true;

			for ( int d = 0; d < n; ++d )
				if ( domain.dimension( d ) <= 2 * Gauss3.halfkernelsize( sigmaDiff[ d ][ p.steps + 2 ] ) - 1 )
					fits = false;

			if ( !fits )
				break;

			++o;
		}

		return Math.max( 1, o );
	}

	/**
	 * @return p.anisotropy (null = isotropic in pixels), checked to have n positive, finite entries
	 */
	public static double[] validateAnisotropy( final ScaleSpaceParameters p, final int n )
	{
		if ( p.anisotropy == null )
			return null;

		if ( p.anisotropy.length != n )
			throw new IllegalArgumentException( "anisotropy has " + p.anisotropy.length + " entries, the image " + n + " dimensions." );

		for ( int d = 0; d < n; ++d )
			if ( !( p.anisotropy[ d ] > 0 ) || Double.isInfinite( p.anisotropy[ d ] ) )
				throw new IllegalArgumentException( "anisotropy must be positive and finite, but is " + Util.printCoordinates( p.anisotropy ) );

		return p.anisotropy;
	}

	/**
	 * @return the blur that the input of an octave carries in a dimension (in pixels of the octave): the image
	 * blur for octave 0, otherwise what the decimated hand-over level of the previous octave carries,
	 * max( sigma_0 / anisotropy, imageSigma / 2^octave )
	 */
	public static float inputSigma( final float[] sigma, final double anisotropy, final float imageSigma, final int octave )
	{
		if ( octave == 0 )
			return imageSigma;
		else
			return Math.max( (float)( sigma[ 0 ] / anisotropy ), imageSigma / ( 1L << octave ) );
	}

	/**
	 * The Gaussian blur that is applied to the input of an octave to obtain each level, per dimension
	 * and level (0 = none, the input carries that much blur in that dimension already).
	 *
	 * @param sigma - the sigmas of the levels in x pixels (computeSigmas)
	 * @param anisotropy - voxel size per dimension relative to x, null = isotropic in pixels
	 * @param imageSigma - the blur the image carries (octave 0)
	 * @param octave - the octave
	 * @param n - number of dimensions
	 */
	public static float[][] computeSigmaDiff( final float[] sigma, final double[] anisotropy, final float imageSigma, final int octave, final int n )
	{
		final float[][] sigmaDiff = new float[ n ][];

		if ( anisotropy == null )
		{
			// the same float arithmetic as DoGImgLib2.computeSigmas (bit-identical to the single-scale DoG)
			final float[] diff = LaPlaceFunctions.computeSigmaDiff( sigma, octave == 0 ? imageSigma : sigma[ 0 ] );

			for ( int d = 0; d < n; ++d )
				sigmaDiff[ d ] = diff;
		}
		else
		{
			for ( int d = 0; d < n; ++d )
			{
				final float in = inputSigma( sigma, anisotropy[ d ], imageSigma, octave );

				sigmaDiff[ d ] = new float[ sigma.length ];

				for ( int i = 0; i < sigma.length; ++i )
				{
					final float target = (float)( sigma[ i ] / anisotropy[ d ] );
					final float diff = target * target - in * in;

					sigmaDiff[ d ][ i ] = diff > 0 ? (float)Math.sqrt( diff ) : 0f;
				}
			}
		}

		return sigmaDiff;
	}

	/**
	 * @return the half kernel size of the Gaussian of a level per dimension (the halo that it reads)
	 */
	public static long[] halfKernelSizes( final float[][] sigmaDiff, final int level )
	{
		final long[] hks = new long[ sigmaDiff.length ];

		for ( int d = 0; d < hks.length; ++d )
			hks[ d ] = Gauss3.halfkernelsize( sigmaDiff[ d ][ level ] );

		return hks;
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

	/**
	 * @return sigmaBase( p.sigmaMin, k, octave, level ) with k = 2^(1/p.steps)
	 */
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

	/**
	 * @return the sigmas of the levels of an octave with subsampling f, multiplied by f, i.e. in pixels of octave 0
	 */
	protected static double[] sigmasInBasePixels( final float[] sigma, final long f )
	{
		final double[] s = new double[ sigma.length ];

		for ( int i = 0; i < s.length; ++i )
			s[ i ] = sigma[ i ] * f;

		return s;
	}

	/**
	 * @return the interval with min and max multiplied by f (an interval of an octave in pixels of octave 0; the max is the first pixel of the last octave pixel)
	 */
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

	/**
	 * @return the smallest dimension of the interval
	 */
	protected static long minDimension( final Interval interval )
	{
		long min = Long.MAX_VALUE;

		for ( int d = 0; d < interval.numDimensions(); ++d )
			min = Math.min( min, interval.dimension( d ) );

		return min;
	}

	/**
	 * @return a / b rounded up (also for negative a)
	 */
	protected static long ceilDiv( final long a, final long b )
	{
		return -Math.floorDiv( -a, b );
	}

	/**
	 * @return the Euclidean distance of two positions (used by mergeDuplicates, in pixels of octave 0)
	 */
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
