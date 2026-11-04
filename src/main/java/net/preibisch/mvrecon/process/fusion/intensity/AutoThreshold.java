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

import ij.process.AutoThresholder;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;

/**
 * Per-image foreground threshold from a 256-bin histogram over the image's
 * [min, max] range, handed to ImageJ's {@link AutoThresholder}. Same recipe as
 * scikit-image's {@code threshold_li} / {@code threshold_otsu}, which the
 * reference Python pipeline uses.
 */
public class AutoThreshold
{
	public enum Method { LI, OTSU }

	private static final int NUM_BINS = 256;

	// Σ bin * count stays below Integer.MAX_VALUE
	private static final long MAX_TOTAL = Integer.MAX_VALUE / NUM_BINS;

	/**
	 * The coarsest STORED resolution level that is no coarser than {@code maxDownsampling},
	 * read as-is.
	 * <p>
	 * Deliberately not BigStitcher-Spark's {@code openAndDownsample}: that refines the
	 * best stored level with extra factors of 2 through {@code LazyDownsample2x}, which is both
	 * needless here (a histogram does not need an exact scale) and throws
	 * {@code ArrayIndexOutOfBoundsException} on production-sized views. Reading a stored level
	 * streams cell by cell, so it also needs no extra memory.
	 *
	 * @return the image, and the downsampling factor it actually corresponds to
	 */
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

		final int[] histogram = toIntHistogram( counts );

		// AutoThresholder returns the index of the last background bin (or -1 if it
		// cannot split); intensity matching discards anything strictly below the
		// threshold, so the floor is the next bin's lower edge
		final int bin = new AutoThresholder().getThreshold( ijMethod( method ), histogram );

		if ( bin < 0 )
			return Double.NaN;

		return min + ( bin + 1 ) * binWidth;
	}

	/**
	 * AutoThresholder.Li sums {@code bin * count} over the histogram in {@code int}s, which
	 * overflows past ~8M voxels and on large views ends in a negative bin index. Both methods
	 * only depend on relative counts, so scale them down until that sum fits.
	 */
	static int[] toIntHistogram( final long[] counts )
	{
		long total = 0;
		for ( final long c : counts )
			total += c;

		final double scale = Math.min( 1.0, ( double ) MAX_TOTAL / total );
		final int[] histogram = new int[ counts.length ];
		for ( int i = 0; i < counts.length; ++i )
			histogram[ i ] = ( int ) Math.round( counts[ i ] * scale );

		return histogram;
	}

	private static AutoThresholder.Method ijMethod( final Method method )
	{
		return method == Method.LI ? AutoThresholder.Method.Li : AutoThresholder.Method.Otsu;
	}
}
