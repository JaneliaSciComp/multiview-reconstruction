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
package net.preibisch.mvrecon.process.fusion.intensity;

import static net.imglib2.util.Intervals.intersect;
import static net.imglib2.util.Intervals.isEmpty;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.TranslationModel1D;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.util.Cast;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.ScaleModel1D;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Intensity correction of overlapping views: per-view thresholds, pairwise matching and the global solve for
 * per-view coefficients. {@link #compute} runs it in memory (GUI); BigStitcher-Spark's {@code match-intensities}
 * and {@code solve-intensities} run the same steps ({@link #viewLevels}, {@link #matchPair}, {@link #solve})
 * distributed, with the matches on disk in between.
 */
public class IntensityCorrectionPipeline
{
	/** MEDIAN: the ratio of the medians of the voxels both views see (a pure gain; for a SCALE solve) */
	public enum Method { RANSAC, HISTOGRAM, MEDIAN }

	public enum TransformationModel { AFFINE, TRANSLATION, SCALE, IDENTITY }

	public enum RegularizationModel { NONE, AFFINE, TRANSLATION, SCALE, IDENTITY }

	/** How the per-pair foreground threshold is chosen. */
	public enum Threshold { FIXED, LI, OTSU }

	public static class Parameters implements Serializable
	{
		private static final long serialVersionUID = 1L;

		public Method method = Method.RANSAC;
		public int[] numCoefficients = { 8, 8, 8 };
		/** at which scale images are sampled, 0.25 = 4x downsampled */
		public double renderScale = 0.25;

		/** FIXED: {@link #minIntensity}; LI / OTSU: per view, each pair uses the higher of its two views' thresholds */
		public Threshold threshold = Threshold.FIXED;
		public double minIntensity = 1;
		/** NaN = no upper limit */
		public double maxIntensity = Double.NaN;
		/** minimum number of overlapping foreground pixels to match two coefficient regions */
		public int minNumCandidates = 1000;

		/**
		 * fraction of intensity lost each time a voxel was imaged before: a voxel covered by n earlier views (same
		 * timepoint, earlier = lower setup id) is matched as v / (1 - bleachFactor)^n; 0 = no bleaching
		 */
		public double bleachFactor = 0;

		/**
		 * weight of the pull of every view toward a common level (the median of the views' foreground medians), scaled
		 * by (median partners / partners)^2 so that weakly connected views follow it, 0 = off (solve only; see
		 * {@link IntensitySolver#setEquilibration})
		 */
		public double equilibration = 0;

		public TransformationModel model = TransformationModel.AFFINE;
		public RegularizationModel regularization1 = RegularizationModel.TRANSLATION;
		public double lambda1 = 0.01;
		public RegularizationModel regularization2 = RegularizationModel.IDENTITY;
		public double lambda2 = 0.01;

		// RANSAC
		public int ransacIterations = 1000;
		public double maxEpsilon = 0.02 * 255;
		public double minInlierRatio = 0.1;
		public int minNumInliers = 10;
		public double maxTrust = 3.0;

		// HISTOGRAM
		public int numBins = 100;

		// solve
		public int solverIterations = 1000;
		/** LI / OTSU only: ignore matches less than this above either view's threshold, 0 = off */
		public double minSignal = 0;
		/** LI / OTSU only: weight of each pair's background levels as an extra match, 0 = off */
		public double backgroundWeight = 0;

		/** sets {@link #threshold} and {@link #minIntensity} from a number, "LI" or "OTSU" */
		public Parameters threshold( final String minThreshold )
		{
			final String s = minThreshold.trim();
			for ( final Threshold t : Threshold.values() )
				if ( t != Threshold.FIXED && t.name().equalsIgnoreCase( s ) )
				{
					threshold = t;
					return this;
				}
			try
			{
				minIntensity = Double.parseDouble( s );
				threshold = Threshold.FIXED;
				return this;
			}
			catch ( final NumberFormatException e )
			{
				throw new IllegalArgumentException( "minimum threshold must be a number, LI or OTSU, but was '" + minThreshold + "'" );
			}
		}

		/** a new instance of the (regularized) 1D model, for matching and solving */
		@SuppressWarnings( { "rawtypes", "unchecked" } )
		public Model< ? > createModel()
		{
			Model< ? > m = createModel( model.name() );
			if ( regularization1 != RegularizationModel.NONE )
				m = new InterpolatedAffineModel1D( m, createModel( regularization1.name() ), checkLambda( "lambda1", lambda1 ) );
			if ( regularization2 != RegularizationModel.NONE )
				m = new InterpolatedAffineModel1D( m, createModel( regularization2.name() ), checkLambda( "lambda2", lambda2 ) );
			return m;
		}

		public void validate()
		{
			createModel();
			if ( numBins < 2 )
				throw new IllegalArgumentException( "number of histogram bins must be >= 2 (a single sample only ever matches each distribution's minimum), but was " + numBins );
			if ( !( renderScale > 0 && renderScale <= 1 ) )
				throw new IllegalArgumentException( "render scale must be in (0, 1], but was " + renderScale );
			if ( !( bleachFactor >= 0 && bleachFactor < 1 ) )
				throw new IllegalArgumentException( "bleach factor must be in [0, 1), but was " + bleachFactor );
		}

		/** the matching parameters; also recorded with BigStitcher-Spark's matches ("matching-done.txt") */
		@Override
		public String toString()
		{
			return "method=" + method + " numCoefficients=" + Arrays.toString( numCoefficients ) + " renderScale=" + renderScale
					+ " minThreshold=" + ( threshold == Threshold.FIXED ? minIntensity : threshold ) + " maxThreshold=" + maxIntensity
					+ " model=" + model + "+" + regularization1 + "(" + lambda1 + ")+" + regularization2 + "(" + lambda2 + ")"
					+ " bleachFactor=" + bleachFactor;
		}

		/** @return the bleach factor recorded by {@link #toString()}, 0 if there is none */
		public static double bleachFactor( final String recorded )
		{
			final java.util.regex.Matcher m = java.util.regex.Pattern.compile( "bleachFactor=([-+0-9.eE]+)" ).matcher( recorded );
			return m.find() ? Double.parseDouble( m.group( 1 ) ) : 0;
		}

		private static Model< ? > createModel( final String name )
		{
			switch ( name )
			{
			case "TRANSLATION": return new TranslationModel1D();
			case "SCALE": return new ScaleModel1D();
			case "IDENTITY": return new IdentityModel();
			default: return new AffineModel1D();
			}
		}

		private static double checkLambda( final String name, final double lambda )
		{
			if ( !( lambda >= 0.0 && lambda <= 1.0 ) ) // the negated form also rejects NaN
				throw new IllegalArgumentException( name + " must be in [0..1], but was " + lambda );
			return lambda;
		}
	}

	public static Map< ViewId, RealInterval > bounds( final AbstractSpimData< ? > data, final List< ? extends ViewId > views )
	{
		final Map< ViewId, RealInterval > bounds = new HashMap<>();
		views.forEach( v -> bounds.put( v, IntensityCorrection.SerializableRealInterval.serializable( IntensityCorrection.getBounds( data, v ) ) ) );
		return bounds;
	}

	/** all pairs of {@code views} whose bounds overlap */
	public static List< ViewId[] > overlappingPairs( final List< ? extends ViewId > views, final Map< ViewId, RealInterval > bounds )
	{
		final List< ViewId[] > pairs = new ArrayList<>();
		for ( int i = 0; i < views.size(); ++i )
			for ( int j = i + 1; j < views.size(); ++j )
				if ( !isEmpty( intersect( bounds.get( views.get( i ) ), bounds.get( views.get( j ) ) ) ) )
					pairs.add( new ViewId[] { views.get( i ), views.get( j ) } );
		return pairs;
	}

	/**
	 * Per view, the earlier views (same timepoint, lower setup id = acquired before) that overlap it, for unbleaching;
	 * empty if there is no bleaching. Bleaching across timepoints is not modelled.
	 */
	public static Map< ViewId, List< ViewId > > bleachers( final List< ? extends ViewId > views, final Map< ViewId, RealInterval > bounds, final double bleachFactor )
	{
		final Map< ViewId, List< ViewId > > bleachers = new HashMap<>();
		if ( bleachFactor > 0 )
			views.stream().collect( Collectors.groupingBy( ViewId::getTimePointId ) ).values().forEach( v -> {
				final List< ViewId > ordered = new ArrayList<>( v );
				ordered.sort( Comparator.comparingInt( ViewId::getViewSetupId ) );
				bleachers.putAll( IntensityCorrection.getPotentialBleachers( ordered, bounds ) );
			} );
		return bleachers;
	}

	/**
	 * Threshold (LI / OTSU, 1 if the view is constant at this scale) and background level (a low percentile) of one
	 * view, both from the same stored resolution level, at most {@code 1 / renderScale} downsampled.
	 *
	 * @return { threshold, background }
	 */
	public static double[] viewLevels( final BasicImgLoader imgLoader, final ViewId view, final Parameters p )
	{
		final AutoThreshold.Method method = p.threshold == Threshold.LI ? AutoThreshold.Method.LI : AutoThreshold.Method.OTSU;
		final long downsampling = Math.max( 1, Math.round( 1.0 / p.renderScale ) );
		final Pair< RandomAccessibleInterval< ? >, Long > img = AutoThreshold.openForHistogram( imgLoader, view, downsampling );
		final double t = AutoThreshold.compute( method, Cast.unchecked( img.getA() ) );
		if ( Double.isNaN( t ) )
			IOFunctions.println( "WARNING: " + Group.pvid( view ) + " is constant at this scale, " + method + " threshold undefined, using 1." );
		final double threshold = Double.isNaN( t ) ? 1.0 : t;
		return new double[] {
				threshold,
				IntensityThresholds.percentile( Cast.unchecked( img.getA() ), IntensityThresholds.BACKGROUND_PERCENTILE ),
				IntensityThresholds.medianAbove( Cast.unchecked( img.getA() ), threshold ) };
	}

	/**
	 * Matches one pair of overlapping views, only where both are foreground.
	 *
	 * @param thresholds per-view thresholds (LI / OTSU), null for the fixed {@link Parameters#minIntensity}
	 * @param bleachers see {@link #bleachers}
	 */
	public static ViewPairCoefficientMatches matchPair(
			final AbstractSpimData< ? > data,
			final ViewId view1,
			final ViewId view2,
			final Map< ViewId, Double > thresholds,
			final Map< ViewId, List< ViewId > > bleachers,
			final Parameters p )
	{
		final double minIntensity = thresholds == null ? p.minIntensity : Math.max( thresholds.get( view1 ), thresholds.get( view2 ) );

		if ( p.method == Method.MEDIAN )
		{
			final Map< ViewId, List< ViewId > > b = bleachers == null ? Collections.emptyMap() : bleachers;
			return IntensityCorrection.matchMedians( data, view1, b.getOrDefault( view1, Collections.emptyList() ), view2,
					b.getOrDefault( view2, Collections.emptyList() ), UnbleachFunction.bleachFactor( p.bleachFactor ), p.renderScale,
					p.numCoefficients, minIntensity, p.maxIntensity, p.minNumCandidates );
		}

		if ( p.bleachFactor > 0 )
		{
			final List< ViewId > b1 = bleachers.getOrDefault( view1, Collections.emptyList() );
			final List< ViewId > b2 = bleachers.getOrDefault( view2, Collections.emptyList() );
			final UnbleachFunction unbleach = UnbleachFunction.bleachFactor( p.bleachFactor );
			return p.method == Method.RANSAC
					? IntensityCorrection.matchRansac( data, view1, b1, view2, b2, unbleach, p.renderScale, p.numCoefficients,
							minIntensity, p.maxIntensity, p.minNumCandidates, p.createModel(), p.ransacIterations, p.maxEpsilon, p.minInlierRatio, p.minNumInliers, p.maxTrust )
					: IntensityCorrection.matchHistograms( data, view1, b1, view2, b2, unbleach, p.renderScale, p.numCoefficients,
							minIntensity, p.maxIntensity, p.minNumCandidates, p.createModel(), p.numBins );
		}

		return p.method == Method.RANSAC
				? IntensityCorrection.matchRansac( data, view1, view2, p.renderScale, p.numCoefficients,
						minIntensity, p.maxIntensity, p.minNumCandidates, p.createModel(), p.ransacIterations, p.maxEpsilon, p.minInlierRatio, p.minNumInliers, p.maxTrust )
				: IntensityCorrection.matchHistograms( data, view1, view2, p.renderScale, p.numCoefficients,
						minIntensity, p.maxIntensity, p.minNumCandidates, p.createModel(), p.numBins );
	}

	/**
	 * Global solve. Drops weak matches ({@link Parameters#minSignal}) and adds background matches
	 * ({@link Parameters#backgroundWeight}) if asked for, stores each view's threshold (fusion can then correct only
	 * above it) and the bleach factor with its coefficients.
	 *
	 * @param thresholds per-view thresholds the matches were gated at, null for a fixed threshold
	 * @param backgrounds per-view background levels, needed for {@link Parameters#backgroundWeight}
	 */
	public static Map< ViewId, Coefficients > solve(
			final List< ViewPairCoefficientMatches > matches,
			final Map< ViewId, Double > thresholds,
			final Map< ViewId, Double > backgrounds,
			final Parameters p )
	{
		return solve( matches, thresholds, backgrounds, null, p );
	}

	/**
	 * @param foregroundMedians per-view median above the threshold, needed for {@link Parameters#equilibration} (null = none)
	 */
	public static Map< ViewId, Coefficients > solve(
			final List< ViewPairCoefficientMatches > matches,
			final Map< ViewId, Double > thresholds,
			final Map< ViewId, Double > backgrounds,
			final Map< ViewId, Double > foregroundMedians,
			final Parameters p )
	{
		if ( p.equilibration > 0 && ( foregroundMedians == null || foregroundMedians.isEmpty() ) )
			IOFunctions.println( "WARNING: equilibration needs the views' foreground medians, which are not available here; solving without it." );
		List< ViewPairCoefficientMatches > toSolve = matches;
		if ( p.minSignal > 0 )
		{
			if ( thresholds == null )
				throw new IllegalArgumentException( "minimal signal needs per-view thresholds (LI or OTSU)" );
			toSolve = IntensityCorrection.dropWeakMatches( toSolve, thresholds, p.minSignal );
		}
		if ( p.backgroundWeight > 0 )
		{
			if ( backgrounds == null )
				throw new IllegalArgumentException( "background weight needs per-view background levels (LI or OTSU)" );
			toSolve = IntensityCorrection.addBackgroundMatches( toSolve, backgrounds, p.backgroundWeight );
		}

		final Map< ViewId, Coefficients > coefficients = IntensityCorrection.solve( p.numCoefficients, toSolve, p.solverIterations, p.createModel(),
				p.equilibration, p.equilibration > 0 ? foregroundMedians : null );

		coefficients.replaceAll( ( v, c ) -> {
			Coefficients r = thresholds != null && thresholds.containsKey( v ) ? c.withThreshold( thresholds.get( v ) ) : c;
			return p.bleachFactor > 0 ? r.withBleachFactor( p.bleachFactor ) : r;
		} );
		return coefficients;
	}

	/**
	 * Matches all overlapping pairs of {@code views} and solves for their coefficients, in memory.
	 *
	 * @return coefficients of every view that overlaps another one
	 */
	public static Map< ViewId, Coefficients > compute( final AbstractSpimData< ? > data, final List< ? extends ViewId > views, final Parameters p, final int numThreads )
	{
		p.validate();
		IOFunctions.println( new Date() + ": Intensity correction: " + p );

		final Map< ViewId, RealInterval > bounds = bounds( data, views );
		final List< ViewId[] > pairs = overlappingPairs( views, bounds );
		IOFunctions.println( new Date() + ": " + pairs.size() + " overlapping pairs of " + views.size() + " views." );
		if ( pairs.isEmpty() )
			return new HashMap<>();

		final Map< ViewId, List< ViewId > > bleachers = bleachers( views, bounds, p.bleachFactor );

		final ForkJoinPool pool = new ForkJoinPool( Math.max( 1, numThreads ) );
		try
		{
			final Map< ViewId, Double > thresholds, backgrounds;
			final Map< ViewId, Double > medians = new ConcurrentHashMap<>();
			if ( p.threshold == Threshold.FIXED )
			{
				thresholds = backgrounds = null;
				if ( p.equilibration > 0 )
				{
					final long downsampling = Math.max( 1, Math.round( 1.0 / p.renderScale ) );
					pool.submit( () -> pairs.stream().flatMap( Stream::of ).distinct().collect( Collectors.toList() ).parallelStream().forEach( v -> {
						final double m = IntensityThresholds.medianAbove( Cast.unchecked( AutoThreshold.openForHistogram(
								data.getSequenceDescription().getImgLoader(), v, downsampling ).getA() ), p.minIntensity );
						if ( Double.isFinite( m ) )
							medians.put( v, m );
					} ) ).get();
				}
			}
			else
			{
				final List< ViewId > toThreshold = pairs.stream().flatMap( Stream::of ).distinct().collect( Collectors.toList() );
				IOFunctions.println( new Date() + ": " + p.threshold + " threshold for " + toThreshold.size() + " views ..." );
				thresholds = new ConcurrentHashMap<>();
				backgrounds = new ConcurrentHashMap<>();
				pool.submit( () -> toThreshold.parallelStream().forEach( v -> {
					final double[] levels = viewLevels( data.getSequenceDescription().getImgLoader(), v, p );
					thresholds.put( v, levels[ 0 ] );
					backgrounds.put( v, levels[ 1 ] );
					if ( Double.isFinite( levels[ 2 ] ) )
						medians.put( v, levels[ 2 ] );
				} ) ).get();
				final double[] sorted = thresholds.values().stream().mapToDouble( Double::doubleValue ).sorted().toArray();
				IOFunctions.println( String.format( "Per-view thresholds: min %.2f, median %.2f, max %.2f", sorted[ 0 ], sorted[ sorted.length / 2 ], sorted[ sorted.length - 1 ] ) );
			}

			IOFunctions.println( new Date() + ": matching " + pairs.size() + " pairs (" + p.method + ") ..." );
			final List< ViewPairCoefficientMatches > matches = Collections.synchronizedList( new ArrayList<>() );
			final AtomicInteger done = new AtomicInteger();
			pool.submit( () -> pairs.parallelStream().forEach( pair -> {
				matches.add( matchPair( data, pair[ 0 ], pair[ 1 ], thresholds, bleachers, p ) );
				final int n = done.incrementAndGet();
				if ( n % Math.max( 1, pairs.size() / 10 ) == 0 || n == pairs.size() )
					IOFunctions.println( new Date() + ": matched " + n + " / " + pairs.size() + " pairs" );
			} ) ).get();

			IOFunctions.println( new Date() + ": solving ..." );
			final Map< ViewId, Coefficients > coefficients = solve( matches, thresholds, backgrounds, medians, p );
			IOFunctions.println( new Date() + ": intensity correction computed for " + coefficients.size() + " views." );
			return coefficients;
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( "intensity correction failed: " + e, e );
		}
		finally
		{
			pool.shutdown();
		}
	}
}
