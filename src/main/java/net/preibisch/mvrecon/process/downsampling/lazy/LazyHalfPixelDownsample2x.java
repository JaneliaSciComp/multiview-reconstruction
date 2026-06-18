/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.process.downsampling.lazy;

import java.io.File;
import java.util.function.Consumer;

import ij.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import util.Lazy;

public class LazyHalfPixelDownsample2x<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final T type;
	final private RandomAccessible<T> source;
	final int d,n;
	final long[] globalMin;

	public LazyHalfPixelDownsample2x(
			final long[] min,
			final RandomAccessible<T> source,
			final Interval sourceInterval,
			final int d, // in which dimension
			final T type )
	{
		this.globalMin = min;
		this.source = source;
		this.type = type;
		this.d = d;
		this.n = source.numDimensions();
	}

	// Note: the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage
	// (but the actual interval to process in many blocks sits somewhere else) 
	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		try
		{
			// no global min, only zero-min is supported for now 

			//long[] min= new long[ output.numDimensions() ];
			//for ( int d = 0; d < min.length; ++d )
			//	min[ d ] = globalMin[ d ] + output.min( d );

			// iterate all dimensions but the one we are processing int
			final long[] iterateMin = new long[ n ];
			final long[] iterateMax = new long[ n ];

			for ( int e = 0; e < n; ++e )
			{
				if ( e == d )
				{
					iterateMin[ e ] = output.min( e );
					iterateMax[ e ] = output.min( e );
				}
				else
				{
					iterateMin[ e ] = output.min( e );
					iterateMax[ e ] = output.max( e );
				}
			}

			final IntervalIterator cursorDim = new IntervalIterator( new FinalInterval(iterateMin, iterateMax));
			final long[] pos = new long[ n ];

			final RandomAccess< T > in = source.randomAccess();
			final RandomAccess< T > out = output.randomAccess();
			final long size = output.max( d ) - output.min( d );

			while (cursorDim.hasNext())
			{
				cursorDim.fwd();
				cursorDim.localize( pos );

				out.setPosition( pos );

				// the first pixel
				in.setPosition( pos );
				in.setPosition( pos[d]*2, d);
				in.move( globalMin );

				double v0, v1;

				for ( long p = 0; p < size; ++p )
					mainLoop(in, out, d);

				// last pixel to not set the randomaccesses out of bounds
				v0 = in.get().getRealDouble();
				in.fwd( d );
				v1 = in.get().getRealDouble();

				out.get().setReal( average(v0, v1) );
			}
		}
		catch (final IncompatibleTypeException e)
		{
			throw new RuntimeException(e);
		}
	}

	private static final <T extends RealType<T> & NativeType<T>> void mainLoop(
			final RandomAccess< T > in,
			final RandomAccess< T > out,
			final int d )
	{
		final double v0 = in.get().getRealDouble();
		in.fwd( d );
		final double v1 = in.get().getRealDouble();
		in.fwd( d );

		out.get().setReal( average(v0, v1) );
		out.fwd( d );
	}
	
	private static final double average( final double v0, final double v1 )
	{
		return ( v0 + v1 ) / 2.0;
	}

	/*
	 * Convenient set up of the Lazy Downsampling
	 *
	 * @param <T>
	 * @param input
	 * @param downsampleInterval
	 * @param type
	 * @param blockSize
	 * @param d
	 * @return
	 */
	public static final <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> init(
			final RandomAccessible< T > input,
			final Interval downsampleInterval,
			final T type,
			final int[] blockSize,
			final int d )
	{
		final long dim[] = new long[ input.numDimensions() ];

		for ( int e = 0; e < input.numDimensions(); ++e )
		{
			if ( e == d )
				dim[ e ] = downsampleInterval.dimension( e ) / 2;
			else
				dim[ e ] = downsampleInterval.dimension( e );
		}

		final long[] min = downsampleInterval.minAsLongArray();

		final LazyHalfPixelDownsample2x< T > downsampling =
				new LazyHalfPixelDownsample2x< T >(
						min,
						input,
						downsampleInterval,
						d,
						type.createVariable() );

		final RandomAccessibleInterval<T> downsampled =
				Views.translate( Lazy.process( new FinalInterval( dim ), blockSize, type.createVariable(), AccessFlags.setOf(), downsampling ), min );

		return downsampled;
	}

	public static void main( String[] args )
	{
		new ImageJ();

		final RandomAccessibleInterval< FloatType > raw =
				IOFunctions.openAs32BitArrayImg( new File( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL18_Angle0.tif"));

		final RandomAccessibleInterval< FloatType > inputCropped = Views.interval( raw, Intervals.expand(raw, new long[] {-200, -200, -20}) );

		ImageJFunctions.show( inputCropped );

		// downsample in X
		final RandomAccessibleInterval<FloatType> downsampledX =
				LazyHalfPixelDownsample2x.init(
						inputCropped,
						new FinalInterval( inputCropped ),
						new FloatType(),
						DoGImgLib2.blockSize,
						0);

		ImageJFunctions.show( downsampledX ).setTitle("downsampleX");

		RandomAccessibleInterval<FloatType> downsampled = inputCropped;

		// downsample in all dimensions
		for ( int d = 0; d < inputCropped.numDimensions(); ++d )
			downsampled = LazyHalfPixelDownsample2x.init(
					downsampled,
					new FinalInterval( downsampled ),
					new FloatType(),
					DoGImgLib2.blockSize,
					d);

		ImageJFunctions.show( downsampled ).setTitle("downsampleAllDimsHalfPixel");

		downsampled = inputCropped;

		for ( int d = 0; d < inputCropped.numDimensions(); ++d )
			downsampled = LazyDownsample2x.init(
					Views.extendBorder( downsampled ),
					new FinalInterval( downsampled ),
					new FloatType(),
					DoGImgLib2.blockSize,
					d);

		ImageJFunctions.show( downsampled ).setTitle("downsampleAllDimsCentered");

	}
}
