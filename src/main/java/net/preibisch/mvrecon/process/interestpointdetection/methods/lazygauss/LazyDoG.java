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
package net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss;

import java.util.function.Consumer;

import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import util.Lazy;

/**
 * Lazy Difference-of-Gaussian Op, output = ( gauss2 - gauss1 ) * weight
 *
 * @author Stephan Preibisch
 * @param <T> type of input and output
 */
public class LazyDoG<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final private RandomAccessible<T> gauss1, gauss2;
	final private double weight;
	final long[] globalMin;

	public LazyDoG(
			final long[] min,
			final RandomAccessible<T> gauss1,
			final RandomAccessible<T> gauss2,
			final double weight )
	{
		this.gauss1 = gauss1;
		this.gauss2 = gauss2;
		this.weight = weight;
		this.globalMin = min;
	}

	// Note: the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage
	// (but the actual interval to process in many blocks sits somewhere else) 
	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		final RandomAccessibleInterval<T> target = Views.translate( output, globalMin );

		final Cursor< T > g1 = Views.flatIterable( Views.interval( gauss1, target ) ).cursor();
		final Cursor< T > g2 = Views.flatIterable( Views.interval( gauss2, target ) ).cursor();
		final Cursor< T > o = Views.flatIterable( target ).cursor();

		while ( o.hasNext() )
			o.next().setReal( ( g2.next().getRealDouble() - g1.next().getRealDouble() ) * weight );
	}

	public static final <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> init(
			final RandomAccessible< T > gauss1,
			final RandomAccessible< T > gauss2,
			final double weight,
			final Interval processingInterval,
			final T type,
			final int[] blockSize )
	{
		final long[] min = processingInterval.minAsLongArray();

		final LazyDoG< T > lazyDoG = new LazyDoG<>( min, gauss1, gauss2, weight );

		final RandomAccessibleInterval<T> dog =
				Views.translate(
						Lazy.process(
								processingInterval,
								blockSize,
								type.createVariable(),
								AccessFlags.setOf(),
								lazyDoG ),
						min );

		return dog;
	}
}
