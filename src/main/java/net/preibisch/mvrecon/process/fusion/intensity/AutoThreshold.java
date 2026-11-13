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

import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

/**
 * Per-image foreground threshold from a {@value #NUM_BINS}-bin histogram over the image's [min, max] range, in double
 * precision (no count limit): Li by the iteration of scikit-image's {@code threshold_li}, Otsu by maximal
 * between-class variance.
 */
public class AutoThreshold
{
	public enum Method { LI, OTSU }

	/**
	 * Histogram resolution: fine enough that a few bright outliers cannot quantize the foreground / background split
	 * away (256 bins over [0, 83887] made every bin 328 wide, and LI could not return less than that).
	 */
	static final int NUM_BINS = 65536;

	public static Pair< RandomAccessibleInterval< ? >, Long > openForHistogram(
			final BasicImgLoader imgLoader,
			final ViewId viewId,
			final long maxDownsampling )
	{
		if ( imgLoader instanceof MultiResolutionImgLoader )
		{
			final MultiResolutionImgLoader mr = ( MultiResolutionImgLoader ) imgLoader;
			final double[][] resolutions = mr.getSetupImgLoader( viewId.getViewSetupId() ).getMipmapResolutions();

			int bestLevel = 0;
			long bestFactor = 1;

			for ( int level = 0; level < resolutions.length; ++level )
			{
				final long[] f = new long[ resolutions[ level ].length ];
				for ( int d = 0; d < f.length; ++d )
					f[ d ] = Math.round( resolutions[ level ][ d ] );

				// all dimensions no coarser than requested, and the coarsest such level
				boolean ok = true;
				for ( final long fd : f )
					ok &= fd <= maxDownsampling;

				if ( ok && f[ 0 ] >= bestFactor )
				{
					bestLevel = level;
					bestFactor = f[ 0 ];
				}
			}

			return new ValuePair<>( mr.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId(), bestLevel ), bestFactor );
		}

		return new ValuePair<>( imgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() ), 1L );
	}

	/**
	 * @return the lowest intensity that counts as foreground, or {@link Double#NaN}
	 *         if the image is constant (nothing to split)
	 */
	public static < T extends RealType< T > > double compute(
			final Method method,
			final RandomAccessibleInterval< T > img )
	{
		double min = Double.POSITIVE_INFINITY;
		double max = Double.NEGATIVE_INFINITY;

		for ( final T t : Views.iterable( img ) )
		{
			final double v = t.getRealDouble();
			if ( v < min )
				min = v;
			if ( v > max )
				max = v;
		}

		if ( !( max > min ) )
			return Double.NaN;

		final double binWidth = ( max - min ) / NUM_BINS;
		final long[] counts = new long[ NUM_BINS ];

		for ( final T t : Views.iterable( img ) )
			++counts[ Math.min( NUM_BINS - 1, ( int ) ( ( t.getRealDouble() - min ) / binWidth ) ) ];

		return method == Method.LI ? li( counts, min, binWidth ) : otsu( counts, min, binWidth );
	}

	/**
	 * Minimum cross-entropy threshold (Li & Tam 1998, the iteration of skimage's {@code threshold_li}) of a histogram
	 * whose bin {@code i} holds the values around {@code min + (i + 0.5) * binWidth}; foreground is above it.
	 */
	static double li( final long[] counts, final double min, final double binWidth )
	{
		// shifted so that every value is positive (the iteration takes logs of the class means)
		double sum = 0, n = 0;
		for ( int i = 0; i < counts.length; ++i )
		{
			sum += counts[ i ] * ( i + 0.5 ) * binWidth;
			n += counts[ i ];
		}
		final double tolerance = binWidth / 2;
		double next = sum / n, current = -2 * tolerance;
		for ( int iteration = 0; iteration < 1000 && Math.abs( next - current ) > tolerance; ++iteration )
		{
			current = next;
			double sb = 0, nb = 0, sf = 0, nf = 0;
			for ( int i = 0; i < counts.length; ++i )
			{
				final double v = ( i + 0.5 ) * binWidth;
				if ( v > current )
				{
					sf += counts[ i ] * v;
					nf += counts[ i ];
				}
				else
				{
					sb += counts[ i ] * v;
					nb += counts[ i ];
				}
			}
			if ( nb == 0 || nf == 0 )
				break;
			final double meanBack = sb / nb, meanFore = sf / nf;
			next = ( meanBack - meanFore ) / ( Math.log( meanBack ) - Math.log( meanFore ) );
		}
		return min + next;
	}

	/** Otsu's threshold (maximal between-class variance) of the same histogram: the lower edge of the first foreground bin */
	static double otsu( final long[] counts, final double min, final double binWidth )
	{
		double n = 0, sum = 0;
		for ( int i = 0; i < counts.length; ++i )
		{
			n += counts[ i ];
			sum += counts[ i ] * ( double ) i;
		}
		double nb = 0, sb = 0, best = -1;
		int split = -1;
		for ( int i = 0; i < counts.length - 1; ++i )
		{
			nb += counts[ i ];
			sb += counts[ i ] * ( double ) i;
			final double nf = n - nb;
			if ( nb == 0 || nf == 0 )
				continue;
			final double d = sb / nb - ( sum - sb ) / nf;
			final double between = nb * nf * d * d;
			if ( between > best )
			{
				best = between;
				split = i;
			}
		}
		return split < 0 ? Double.NaN : min + ( split + 1 ) * binWidth;
	}

}
