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
package net.preibisch.mvrecon.process.downsampling;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.downsampling.lazy.LazyDownsample2x;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

public class Downsample
{
	public static < T extends RealType<T> & NativeType<T> > RandomAccessibleInterval< T > downsample(
			RandomAccessibleInterval< T > input,
			final long[] downsampleFactors )
	{
		long dsx = downsampleFactors[0];
		long dsy = downsampleFactors[1];
		long dsz = (downsampleFactors.length > 2) ? downsampleFactors[ 2 ] : 1;

		for ( ;dsx > 1; dsx /= 2 )
			input = Downsample.simple2x( input, new boolean[]{ true, false, false } );

		for ( ;dsy > 1; dsy /= 2 )
			input = Downsample.simple2x( input, new boolean[]{ false, true, false } );

		for ( ;dsz > 1; dsz /= 2 )
			input = Downsample.simple2x( input, new boolean[]{ false, false, true } );

		return input;
	}

	public static < T extends RealType< T >& NativeType<T> > RandomAccessibleInterval< T > simple2x( final RandomAccessibleInterval<T> input )
	{
		final boolean[] downsampleInDim = new boolean[ input.numDimensions() ];

		for ( int d = 0; d < downsampleInDim.length; ++d )
			downsampleInDim[ d ] = true;

		return simple2x( input, downsampleInDim );
	}

	public static < T extends RealType< T >& NativeType<T> > RandomAccessibleInterval< T > simple2x( final RandomAccessibleInterval<T> input, final boolean[] downsampleInDim )
	{
		RandomAccessibleInterval<T> downsampled = input;

		final T type = Util.getTypeFromInterval( input );

		// downsample in all dimensions
		for ( int d = 0; d < input.numDimensions(); ++d )
			if ( downsampleInDim[ d ] )
				downsampled = LazyDownsample2x.init(
						Views.extendBorder( downsampled ),
						downsampled,
						type,
						DoGImgLib2.blockSize,
						d);

		return downsampled;
	}

	/*
	public static < T extends RealType< T > > void simple2x( final RandomAccessibleInterval<T> input, final RandomAccessibleInterval<T> output, final int d, final ExecutorService taskExecutor )
	{
		final int n = input.numDimensions();

		// iterate all dimensions but the one we are processing int
		final long[] iterateD = new long[ n ];
		long numLines = 1;

		for ( int e = 0; e < n; ++e )
		{
			if ( e == d )
				iterateD[ e ] = 1;
			else
				iterateD[ e ] = output.dimension( e );
			
			numLines *= iterateD[ e ];
		}
		
		//final IterableInterval< T > iterable = new ZeroMinIntervalIterator( iterateD );
				//Views.iterable( Views.hyperSlice( Views.zeroMin( output ), d, 0 ) );

		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( numLines );

		// set up executor service
		//final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );
		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Void >() 
			{
				@Override
				public Void call() throws Exception
				{
					final long[] pos = new long[ n ];
					final IntervalIterator cursorDim = new ZeroMinIntervalIterator( iterateD );
					final RandomAccess< T > in = Views.zeroMin( input ).randomAccess();
					final RandomAccess< T > out = Views.zeroMin( output ).randomAccess();
					final long size = output.dimension( d ) - 1;

					cursorDim.jumpFwd( portion.getStartPosition() );

					for ( long j = 0; j < portion.getLoopSize(); ++j )
					{
						cursorDim.fwd();
						cursorDim.localize( pos );

						out.setPosition( pos );

						// the first pixel (avoid outofbounds)
						in.setPosition( pos );
						double v0, v1, v2;

						v1 = in.get().getRealDouble();
						in.fwd( d );
						v0 = v2 = in.get().getRealDouble();
						out.get().setReal( ( v1 + v2 * 0.5 ) / 1.5 );

						// other pixels
						for ( int p = 1; p < size; ++p )
						{
							v0 = v2;
							in.fwd( d );
							v1 = in.get().getRealDouble();
							in.fwd( d );
							v2 = in.get().getRealDouble();
							out.fwd( d );
							out.get().setReal( ( v0 * 0.5 + v1 + v2 * 0.5 ) / 2.0 );
						}

						// last pixel
						in.fwd( d );
						v1 = in.get().getRealDouble();
						out.fwd( d );
						out.get().setReal( ( v1 + v2 * 0.5 ) / 1.5 );
					}
					return null;
				}
			});
		}

		try
		{
			// invokeAll() returns when all tasks are complete
			taskExecutor.invokeAll( tasks );
		}
		catch ( final InterruptedException e )
		{
			IOFunctions.println( "Failed to compute downsampling: " + e );
			e.printStackTrace();
			return;
		}

		//taskExecutor.shutdown();

		return;
	} */

	public static void main( String[] args )
	{
		final Img< FloatType > img;
		
		//img = OpenImg.open( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/img_Angle0.tif", new ArrayImgFactory< FloatType >() );
		img = new ArrayImgFactory< FloatType >( new FloatType() ).create( new long[]{ 515,  231, 15 } );
		
		final Cursor< FloatType > c = img.localizingCursor();
		
		while ( c.hasNext() )
		{
			c.next().set( c.getIntPosition( 0 ) % 10 + c.getIntPosition( 1 ) % 13 + c.getIntPosition( 2 ) % 3 );
		}
		
		new ImageJ();
		ImageJFunctions.show( img );
		ImageJFunctions.show( simple2x( img, /*img.factory(),*/ new boolean[]{ true, true, true }/*, taskExecutor*/ ) );
	}
}
