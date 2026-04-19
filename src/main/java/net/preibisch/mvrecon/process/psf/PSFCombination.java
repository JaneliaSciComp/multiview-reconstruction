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
package net.preibisch.mvrecon.process.psf;

import java.util.Collection;
import java.util.List;

import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

public class PSFCombination
{
	public static < T extends RealType< T > > Img< T > computeMaxAverageTransformedPSF(
			final Collection< ? extends RandomAccessibleInterval< T > > imgs,
			final ImgFactory< T > imgFactory )
	{
		final Img< T > avg = computeAverageImage( imgs, imgFactory, true );

		return computeMaxProjectionPSF( avg );
	}

	public static < T extends RealType< T > > Img< T > computeMaxProjectionPSF( final Img< T > avg )
	{
		int minDim = -1;
		long minDimSize = Long.MAX_VALUE;

		for ( int d = 0; d < avg.numDimensions(); ++d )
			if ( avg.dimension( d ) < minDimSize )
			{
				minDimSize = avg.dimension( d );
				minDim = d;
			}

		return computeProjection( avg, minDim, true );
	}

	/**
	 * compute the average psf in original calibration and after applying the transformations
	 * 
	 * @param imgs - the input images
	 * @param imgFactory - the image factory for the average image
	 * @param max - if true, use the maximal size of all inputs, otherwise the minimal size of all inputs
	 * @param <T> pixel type
	 * @return averaged image
	 */
	public static < T extends RealType< T > > Img< T > computeAverageImage(
			final Collection< ? extends RandomAccessibleInterval< T > > imgs,
			final ImgFactory< T > imgFactory,
			final boolean max )
	{
		final long[] maxSize;

		if ( max )
			maxSize = computeMaxDimTransformedPSF( imgs );
		else
			maxSize = computeMinDimTransformedPSF( imgs );

		final int numDimensions = maxSize.length;

		//IJ.log( "maxSize: " + Util.printCoordinates( maxSize ) );

		Img< T > avgPSF = imgFactory.create( maxSize, Views.iterable( imgs.iterator().next() ).firstElement() );

		final long[] avgCenter = new long[ numDimensions ];
		for ( int d = 0; d < numDimensions; ++d )
			avgCenter[ d ] = avgPSF.dimension( d ) / 2;

		for ( final RandomAccessibleInterval< T > psfIn : imgs )
		{
			final IterableInterval< T > psf;

			if ( Views.isZeroMin( psfIn ) )
				psf = Views.iterable( psfIn );
			else
				psf = Views.iterable( Views.zeroMin( psfIn ) );

			// works if the kernel is even
			final RandomAccess< T > avgCursor = Views.extendZero( avgPSF ).randomAccess();
			final Cursor< T > psfCursor = psf.localizingCursor();
			
			final long[] loc = new long[ numDimensions ];
			final long[] psfCenter = new long[ numDimensions ];
			for ( int d = 0; d < numDimensions; ++d )
				psfCenter[ d ] = psf.dimension( d ) / 2;

			while ( psfCursor.hasNext() )
			{
				psfCursor.fwd();
				psfCursor.localize( loc );

				for ( int d = 0; d < numDimensions; ++d )
					loc[ d ] = avgCenter[ d ] - ( psfCenter[ d ] - loc[ d ] );

				avgCursor.setPosition( loc );
				avgCursor.get().add( psfCursor.get() );
			}
		}

		for ( final T type : avgPSF )
			type.setReal( type.getRealDouble() / (double)imgs.size() );

		return avgPSF;
	}

	/**
	 * @param imgs image dimensions
	 * @return - maximal dimensions of the transformed PSFs
	 */
	public static long[] computeMaxDimTransformedPSF( final Collection< ? extends Interval > imgs )
	{
		final int numDimensions = 3;
		
		final long[] maxSize = new long[ numDimensions ];

		for ( final Interval img : imgs )
		{
			for ( int d = 0; d < numDimensions; ++d )
				maxSize[ d ] = Math.max( maxSize[ d ], img.dimension( d ) );
		}

		return maxSize;
	}

	/**
	 * @param imgs image dimensions
	 * @return - maximal dimensions of the transformed PSFs
	 */
	public static long[] computeMinDimTransformedPSF( final Collection< ? extends Interval > imgs )
	{
		final int numDimensions = 3;

		final long[] minSize = new long[ numDimensions ];

		for ( int d = 0; d < numDimensions; ++d )
			minSize[ d ] = Long.MAX_VALUE;

		for ( final Interval img : imgs )
		{
			for ( int d = 0; d < numDimensions; ++d )
				minSize[ d ] = Math.min( minSize[ d ], img.dimension( d ) );
		}

		return minSize;
	}

	/**
	 * Make image the same size as defined, center it
	 * 
	 * @param img input image
	 * @param sizeIn the size we want
	 * @param <T> pixel type
	 * @return centered image of same type
	 */
	public static < T extends RealType< T > > Img< T > makeSameSize( final Img< T > img, final long[] sizeIn )
	{
		final long[] size = sizeIn.clone();

		double min = Double.MAX_VALUE;

		for ( final T f : img )
			min = Math.min( min, f.getRealDouble() );

		final Img< T > square = img.factory().create( size, img.firstElement() );

		final Cursor< T > squareCursor = square.localizingCursor();
		final T minT = img.firstElement().createVariable();
		minT.setReal( min );

		final RandomAccess< T > inputCursor = Views.extendValue( img, minT ).randomAccess();

		while ( squareCursor.hasNext() )
		{
			squareCursor.fwd();
			squareCursor.localize( size );
			
			for ( int d = 0; d < img.numDimensions(); ++d )
				size[ d ] =  size[ d ] - square.dimension( d )/2 + img.dimension( d )/2;

			inputCursor.setPosition( size );
			squareCursor.get().set( inputCursor.get() );
		}
		
		return square;
	}

	/**
	 * Get projection along the smallest dimension (which is usually the rotation axis)
	 * 
	 * @param avgPSF - the average psf
	 * @param minDim - along which dimension to project, if set to &lt;0, the smallest dimension will be chosen
	 * @param max - if true max projection, else min projection
	 * @param <S> pixel type
	 * @return - the averaged, projected PSF
	 */
	public static < S extends RealType< S > > Img< S > computeProjection( final Img< S > avgPSF, final int minDim, final boolean max )
	{
		return computeMaxProjection( avgPSF, avgPSF.factory(), minDim, max );
	}

	public static < S extends RealType< S > > Img< S > computeMaxProjection(
			final RandomAccessibleInterval< S > avgPSF,
			final ImgFactory< S > factory,
			int minDim,
			final boolean max )
	{
		final long[] dimensions = new long[ avgPSF.numDimensions() ];
		avgPSF.dimensions( dimensions );
		
		if ( minDim < 0 )
		{
			long minSize = dimensions[ 0 ];
			minDim = 0;
			
			for ( int d = 0; d < dimensions.length; ++d )
			{
				if ( avgPSF.dimension( d ) < minSize )
				{
					minSize = avgPSF.dimension( d );
					minDim = d;
				}
			}
		}
		
		final long[] projDim = new long[ dimensions.length - 1 ];
		
		int dim = 0;
		long sizeProjection = 0;
		
		// the new dimensions
		for ( int d = 0; d < dimensions.length; ++d )
			if ( d != minDim )
				projDim[ dim++ ] = dimensions[ d ];
			else
				sizeProjection = dimensions[ d ];
		
		final Img< S > proj = factory.create( projDim, Views.iterable( avgPSF ).firstElement() );
		
		final RandomAccess< S > psfIterator = avgPSF.randomAccess();
		final Cursor< S > projIterator = proj.localizingCursor();
		
		final int[] tmp = new int[ avgPSF.numDimensions() ];
		
		while ( projIterator.hasNext() )
		{
			projIterator.fwd();

			dim = 0;
			for ( int d = 0; d < dimensions.length; ++d )
				if ( d != minDim )
					tmp[ d ] = projIterator.getIntPosition( dim++ );

			tmp[ minDim ] = -1;

			double extreme = max ? -Double.MAX_VALUE : Double.MAX_VALUE;

			psfIterator.setPosition( tmp );
			for ( int i = 0; i < sizeProjection; ++i )
			{
				psfIterator.fwd( minDim );
				final double value = psfIterator.get().getRealDouble();

				if ( max )
				{
					if ( value > extreme )
						extreme = value;
				}
				else
				{
					if ( value < extreme )
						extreme = value;
				}
			}
			
			projIterator.get().setReal( extreme );
		}
		
		return proj;
	}

	/**
	 * Returns the bounding box so that all images can fit in there
	 * or null if input is null or input.size is 0
	 * 
	 * @param images input images
	 * @param <T> pixel type
	 * @return largest size in every dimension or null
	 */
	public static < T extends Type< T > > long[] commonSize( final List< Img< T > > images )
	{
		if ( images == null || images.size() == 0 )
			return null;
		
		final long[] size = new long[ images.get( 0 ).numDimensions() ]; 
		images.get( 0 ).dimensions( size );
		
		for ( final Img< T > image : images )
			for ( int d = 0; d < image.numDimensions(); ++d )
				size[ d ] = Math.max( size[ d ], image.dimension( d ) );
		
		return size;
	}
}
