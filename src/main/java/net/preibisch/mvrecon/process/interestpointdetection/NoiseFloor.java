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
package net.preibisch.mvrecon.process.interestpointdetection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyGauss;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.ImgLib2Tools;

/**
 * The noise floor of a detection: the robust standard deviation (1.4826 x the median absolute deviation) of the finest
 * Difference-of-Gaussian level of an image, i.e. of the very filter the detection uses (its sigma, its k = 2^(1/steps)
 * and the anisotropy of the opened image), read in random blocks of the image. Beads and tissue are a minority of the
 * voxels, so the median absolute deviation is not pulled by them. The threshold that follows is factor x floor (the
 * brief: a factor around 2 .. 3, default 2.5). It is the same quantity for the single-scale DoG and the scale space:
 * the finest scale-space level is that DoG at the initial blur, and it is the noisiest level (a blob gives the same
 * normalized response at every level while white noise shrinks with the scale), so one floor bounds all levels.
 *
 * Per dataset one floor: the median of the floors of all processed views ({@link Pooled}). Every view is normalized with
 * its own intensity range unless a range is given, so the floors are comparable only as far as the normalization is;
 * the spread is reported and should be small (or the range fixed for all views). Never per block: a threshold has to be
 * a continuous global function of the data.
 *
 * The same code serves the detection dialogs (all views, once before the detection, DifferenceOfGUI) and the interactive
 * previews (one view, once per sigma, InteractiveDoG and InteractiveScaleSpace).
 *
 * @author Stephan Preibisch
 */
public class NoiseFloor
{
	/**
	 * Estimates the pooled floor of all views of a detection (DifferenceOfGUI.noiseEstimator) for the previews, which know
	 * only their own view, at a resolution (null = the one chosen in the dialog), the sigma of the finest level and k = 2^(1/steps)
	 */
	public interface Estimator
	{
		int numViews();

		Pooled estimate( final long[] downsampling, final double sigma, final int steps );
	}

	/** the random blocks per image (the cells of the lazy Gaussians, so only these cells are computed) */
	public static int defaultNumBlocks = 16;
	public static int[] defaultBlockSize = new int[] { 64, 64, 32 };
	public static long defaultSeed = 42;

	/** at most this many voxels enter the median absolute deviation (the blocks are subsampled with a stride beyond it) */
	public static int maxSamples = 2000000;

	/** the robust standard deviation of a normal distribution is 1.4826 x its median absolute deviation */
	public static final double mad2std = 1.4826;

	/**
	 * The noise floor of one image
	 */
	public static class Estimate
	{
		/** the robust standard deviation of the finest DoG level (the noise floor; NaN if no voxel carries data), its median (~0 for noise) */
		public final double floor, median;
		/** the voxels that entered, and the ones left out because their raw value is the minimum of all sampled raw values (padding, a background clipped to a constant) */
		public final long numSamples, numExcluded;
		public final int numBlocks;

		public Estimate( final double floor, final double median, final long numSamples, final long numExcluded, final int numBlocks )
		{
			this.floor = floor;
			this.median = median;
			this.numSamples = numSamples;
			this.numExcluded = numExcluded;
			this.numBlocks = numBlocks;
		}

		@Override
		public String toString()
		{
			return InterestPointFilter.plain( floor ) + " (median " + InterestPointFilter.plain( median ) + ", " + numSamples + " voxels in " + numBlocks + " blocks" + ( numExcluded > 0 ? ", " + numExcluded + " at the minimum left out" : "" ) + ")";
		}
	}

	/**
	 * The noise floors of the views of a dataset and the one floor derived from them: their median
	 */
	public static class Pooled
	{
		public final LinkedHashMap< ViewId, Estimate > perView = new LinkedHashMap<>();

		/**
		 * The pooled floor: the HIGHEST floor of the views that have one (a view without data has none), NaN if no view has
		 * one. A view's floor measures what fills most of its voxels, camera noise in an empty view and sample texture in a
		 * filled one, and these differ by an order of magnitude between the tiles of one channel (ds1: 1.5e-4 .. 1.5e-3 with
		 * the same intensity range). One threshold for all views has to clear the texture of the most textured view, and a
		 * multiple of the camera noise is not selective in an image of millions of voxels (2.5 x the floor of the empty ds1
		 * tile gave 9000 noise detections), so the median or mean, which depend on how many views are empty, are no option
		 * (Stephan, 2026-10-21). The price is that one odd view sets the threshold for all; the per-view floors are logged.
		 */
		public double floor()
		{
			return max();
		}

		/** @return the median of the floors of the views that have one, NaN if no view has one (information, the pooled floor is the maximum) */
		public double median()
		{
			final ArrayList< Float > floors = new ArrayList<>();

			for ( final Estimate e : perView.values() )
				if ( !Double.isNaN( e.floor ) )
					floors.add( (float)e.floor );

			final float[] f = new float[ floors.size() ];

			for ( int i = 0; i < f.length; ++i )
				f[ i ] = floors.get( i );

			return f.length == 0 ? Double.NaN : NoiseFloor.median( f );
		}

		public double min()
		{
			double min = Double.NaN;

			for ( final Estimate e : perView.values() )
				if ( !Double.isNaN( e.floor ) )
					min = Double.isNaN( min ) ? e.floor : Math.min( min, e.floor );

			return min;
		}

		public double max()
		{
			double max = Double.NaN;

			for ( final Estimate e : perView.values() )
				if ( !Double.isNaN( e.floor ) )
					max = Double.isNaN( max ) ? e.floor : Math.max( max, e.floor );

			return max;
		}

		/** @return factor x the pooled floor */
		public double threshold( final double factor )
		{
			return factor * floor();
		}

		public String describe()
		{
			final StringBuilder sb = new StringBuilder( "noise floor (robust std of the finest DoG level): the highest of " + perView.size() + " view(s) = " + InterestPointFilter.plain( floor() ) + " [" + InterestPointFilter.plain( min() ) + " .. " + InterestPointFilter.plain( max() ) + ", median " + InterestPointFilter.plain( median() ) + "]" );

			for ( final Map.Entry< ViewId, Estimate > e : perView.entrySet() )
				sb.append( "\n  " ).append( Group.pvid( e.getKey() ) ).append( ": " ).append( e.getValue() );

			return sb.toString();
		}
	}

	/**
	 * @return the median of the values (the array is sorted in place)
	 */
	public static double median( final float[] values )
	{
		if ( values.length == 0 )
			return Double.NaN;

		Arrays.sort( values );

		final int n = values.length;

		return n % 2 == 1 ? values[ n / 2 ] : 0.5 * ( (double)values[ n / 2 - 1 ] + values[ n / 2 ] );
	}

	/**
	 * @return { 1.4826 x the median absolute deviation, the median } of the values (the array is sorted in place)
	 */
	public static double[] robustStd( final float[] values )
	{
		if ( values.length == 0 )
			return new double[] { Double.NaN, Double.NaN };

		final double median = median( values );
		final float[] deviations = new float[ values.length ];

		for ( int i = 0; i < values.length; ++i )
			deviations[ i ] = (float)Math.abs( values[ i ] - median );

		return new double[] { mad2std * median( deviations ), median };
	}

	/**
	 * The noise floor of an image with the default blocks and seed, see estimate( ..., numBlocks, blockSize, seed, ... )
	 */
	public static < T extends RealType< T > > Estimate estimate( final RandomAccessibleInterval< T > image, final double min, final double max, final double sigma, final double k, final double[] anisotropy, final ExecutorService service )
	{
		return estimate( image, min, max, sigma, k, anisotropy, defaultNumBlocks, defaultBlockSize, defaultSeed, service );
	}

	/**
	 * The noise floor of an image: the image is normalized to [min, max] (as the detection normalizes it), the two Gaussians
	 * of the finest DoG level are set up lazily with the sigmas of the detection (DoGImgLib2.computeSigmas: sigma and sigma x k
	 * per dimension through the anisotropy, the 0.5 px image blur subtracted) and (g2 - g1) / (k - 1) is read in numBlocks
	 * random blocks, which are cells of the lazy Gaussians, so only these cells are computed. Voxels whose raw value is the
	 * minimum of all sampled raw values are left out: a constant padding (a rotated, cropped or partly covered view is often
	 * more than half such voxels, and the median of the DoG over them is 0, not the noise of the data) or a background
	 * clipped to a constant (which carries no noise either; what is left of such a view is mostly signal, so its floor is
	 * high, and the count of the left-out voxels tells). In a noisy image only a handful of voxels sit at the minimum. The
	 * floor is NaN if nothing is left (a constant image).
	 *
	 * @param image - the image as the detection sees it (opened at the detection resolution, not normalized)
	 * @param min - the lower end of the intensity range the detection normalizes with (the user's or the one of this image)
	 * @param max - the upper end
	 * @param sigma - the sigma of the DoG (x pixels at this resolution; the initial blur of the scale space)
	 * @param k - the ratio of the two Gaussians, 2^(1/4) for the DoG, 2^(1/steps) for the scale space
	 * @param anisotropy - the voxel size per dimension relative to x (DownsampleTools.anisotropy), null = isotropic in pixels
	 * @param numBlocks - the random blocks (all blocks if the image has fewer)
	 * @param blockSize - the block size per dimension (clamped to the image, the last entry repeats for further dimensions)
	 * @param seed - of the random blocks (the same seed picks the same blocks)
	 * @param service - the executor the blocks are read with (one task per block)
	 * @return the floor, the median and how much was sampled
	 */
	public static < T extends RealType< T > > Estimate estimate(
			final RandomAccessibleInterval< T > image,
			final double min,
			final double max,
			final double sigma,
			final double k,
			final double[] anisotropy,
			final int numBlocks,
			final int[] blockSize,
			final long seed,
			final ExecutorService service )
	{
		final int n = image.numDimensions();

		if ( !( max > min ) )
			throw new IllegalArgumentException( "The intensity range [" + min + ", " + max + "] is empty, cannot normalize the image." );

		// the image as the detection sees it: extended, normalized to [0, 1]
		final RandomAccessible< FloatType > normalized = ImgLib2Tools.normalizeVirtual( Views.extendMirrorSingle( image ), min, max );

		final Pair< double[][], Float > sigmas = DoGImgLib2.computeSigmas( (float)sigma, (float)k, anisotropy, n );
		final double[] sigma1 = sigmas.getA()[ 0 ], sigma2 = sigmas.getA()[ 1 ];
		final float kMin1Inv = sigmas.getB();

		// the cells of the lazy Gaussians are the blocks that are read
		final int[] cell = new int[ n ];
		final long[] cellsPerDim = new long[ n ];
		long numCells = 1;

		for ( int d = 0; d < n; ++d )
		{
			cell[ d ] = (int)Math.max( 1, Math.min( blockSize[ Math.min( d, blockSize.length - 1 ) ], image.dimension( d ) ) );
			cellsPerDim[ d ] = ( image.dimension( d ) + cell[ d ] - 1 ) / cell[ d ];
			numCells *= cellsPerDim[ d ];
		}

		final RandomAccessibleInterval< FloatType > gauss1 = LazyGauss.init( normalized, image, new FloatType(), sigma1, cell );
		final RandomAccessibleInterval< FloatType > gauss2 = LazyGauss.init( normalized, image, new FloatType(), sigma2, cell );
		final RandomAccessibleInterval< FloatType > dog = Converters.convert( gauss2, gauss1, ( a, b, o ) -> o.setReal( ( a.getRealDouble() - b.getRealDouble() ) * kMin1Inv ), new FloatType() );

		// the random cells (all if there are not more than asked for), the same for the same seed
		final ArrayList< Long > chosen = new ArrayList<>();

		if ( numCells <= numBlocks )
		{
			for ( long c = 0; c < numCells; ++c )
				chosen.add( c );
		}
		else
		{
			final Random rnd = new Random( seed );
			final HashSet< Long > set = new HashSet<>();

			while ( set.size() < numBlocks )
				set.add( (long)( rnd.nextDouble() * numCells ) );

			chosen.addAll( set );
		}

		long numVoxels = 0;
		final ArrayList< Interval > blocks = new ArrayList<>();

		for ( final long c : chosen )
		{
			final long[] bmin = new long[ n ], bmax = new long[ n ];
			long rest = c;

			for ( int d = 0; d < n; ++d )
			{
				final long index = rest % cellsPerDim[ d ];
				rest /= cellsPerDim[ d ];
				bmin[ d ] = image.min( d ) + index * cell[ d ];
				bmax[ d ] = Math.min( image.max( d ), bmin[ d ] + cell[ d ] - 1 );
			}

			final Interval block = new FinalInterval( bmin, bmax );
			blocks.add( block );
			numVoxels += numElements( block );
		}

		// every stride-th voxel, so that at most maxSamples enter the median
		final long stride = Math.max( 1, ( numVoxels + maxSamples - 1 ) / maxSamples );
		final ArrayList< Future< float[][] > > futures = new ArrayList<>();

		for ( final Interval block : blocks )
		{
			futures.add( service.submit( () ->
			{
				// the DoG and the raw value of every stride-th voxel
				final int count = (int)( ( numElements( block ) + stride - 1 ) / stride );
				final float[] values = new float[ count ], raws = new float[ count ];
				final Cursor< FloatType > cursor = Views.flatIterable( Views.interval( dog, block ) ).cursor();
				final Cursor< T > raw = Views.flatIterable( Views.interval( image, block ) ).cursor();
				int i = 0;
				long j = 0;

				while ( cursor.hasNext() )
				{
					final float v = cursor.next().get();
					final float r = raw.next().getRealFloat();

					if ( j++ % stride == 0 )
					{
						values[ i ] = v;
						raws[ i++ ] = r;
					}
				}

				return new float[][] { values, raws };
			} ) );
		}

		int total = 0;
		float rawMin = Float.POSITIVE_INFINITY;
		final ArrayList< float[][] > parts = new ArrayList<>();

		try
		{
			for ( final Future< float[][] > f : futures )
			{
				final float[][] part = f.get();
				parts.add( part );
				total += part[ 0 ].length;

				for ( final float r : part[ 1 ] )
					rawMin = Math.min( rawMin, r );
			}
		}
		catch ( final InterruptedException | ExecutionException e )
		{
			throw new RuntimeException( "Could not read the DoG of the random blocks: " + e, e );
		}

		// the DoG where the raw value is above the sampled minimum (the rest is padding or a clipped background, no noise)
		final float[] all = new float[ total ];
		int kept = 0;

		for ( final float[][] part : parts )
			for ( int i = 0; i < part[ 0 ].length; ++i )
				if ( part[ 1 ][ i ] > rawMin )
					all[ kept++ ] = part[ 0 ][ i ];

		final double[] stats = robustStd( Arrays.copyOf( all, kept ) );

		return new Estimate( stats[ 0 ], stats[ 1 ], kept, total - kept, blocks.size() );
	}

	/**
	 * The noise floors of views, each opened as the detection opens it (DownsampleTools.openAndDownsample at the downsampling,
	 * not virtual) and normalized as the detection normalizes it (the given range, or the range of the view itself), pooled as
	 * the median of the per-view floors
	 *
	 * @param anisotropy - the anisotropy of an opened view from its description and mipmap transform (DownsampleTools.anisotropy with the method's convention), may return null for isotropic
	 * @param minIntensity - the user's intensity range, NaN = the range of every view itself (FusionTools.minMax)
	 */
	public static Pooled estimate(
			final ImgLoader imgloader,
			final Collection< ViewDescription > views,
			final long[] downsampling,
			final double sigma,
			final double k,
			final BiFunction< ViewDescription, AffineTransform3D, double[] > anisotropy,
			final double minIntensity,
			final double maxIntensity,
			final int numBlocks,
			final int[] blockSize,
			final long seed,
			final ExecutorService service )
	{
		return estimate( imgloader, views, downsampling, sigma, k, anisotropy, minIntensity, maxIntensity, null, numBlocks, blockSize, seed, service );
	}

	/**
	 * As above with a cache of the intensity ranges of the views (keyed by view id, filled when a view's own range has to be
	 * computed, which is a full pass over the view): the previews estimate again at every sigma, the ranges do not change
	 *
	 * @param rangeCache - { min, max } per view at this downsampling, read and filled when no range is given; null = no cache
	 */
	public static Pooled estimate(
			final ImgLoader imgloader,
			final Collection< ViewDescription > views,
			final long[] downsampling,
			final double sigma,
			final double k,
			final BiFunction< ViewDescription, AffineTransform3D, double[] > anisotropy,
			final double minIntensity,
			final double maxIntensity,
			final Map< ViewId, double[] > rangeCache,
			final int numBlocks,
			final int[] blockSize,
			final long seed,
			final ExecutorService service )
	{
		final Pooled pooled = new Pooled();

		for ( final ViewDescription vd : views )
		{
			if ( !vd.isPresent() )
				continue;

			@SuppressWarnings( "rawtypes" )
			final Pair< RandomAccessibleInterval, AffineTransform3D > input = DownsampleTools.openAndDownsample( imgloader, vd, downsampling, false );

			@SuppressWarnings( "rawtypes" )
			final RandomAccessibleInterval image = input.getA();

			final ViewId viewId = new ViewId( vd.getTimePointId(), vd.getViewSetupId() );
			final double min, max;

			if ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) )
			{
				double[] range = rangeCache == null ? null : rangeCache.get( viewId );

				if ( range == null )
				{
					@SuppressWarnings( "unchecked" )
					final float[] minmax = FusionTools.minMax( image, service );
					range = new double[] { minmax[ 0 ], minmax[ 1 ] };

					if ( rangeCache != null )
						rangeCache.put( viewId, range );
				}

				min = range[ 0 ];
				max = range[ 1 ];
			}
			else
			{
				min = minIntensity;
				max = maxIntensity;
			}

			@SuppressWarnings( "unchecked" )
			final Estimate estimate = estimate( image, min, max, sigma, k, anisotropy.apply( vd, input.getB() ), numBlocks, blockSize, seed, service );

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): noise floor of " + Group.pvid( vd ) + " at downsampling " + Util.printCoordinates( downsampling ) + ", range [" + min + ", " + max + "]: " + estimate );

			pooled.perView.put( viewId, estimate );
		}

		return pooled;
	}

	protected static long numElements( final Interval interval )
	{
		long n = 1;

		for ( int d = 0; d < interval.numDimensions(); ++d )
			n *= interval.dimension( d );

		return n;
	}

	/** the views of the ids that are present */
	public static List< ViewDescription > presentViews( final net.preibisch.mvrecon.fiji.spimdata.SpimData2 data, final Collection< ? extends ViewId > viewIds )
	{
		final ArrayList< ViewDescription > views = new ArrayList<>();

		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( viewId );

			if ( vd != null && vd.isPresent() )
				views.add( vd );
		}

		return views;
	}
}
