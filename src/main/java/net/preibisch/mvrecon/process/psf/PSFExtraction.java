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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.concurrent.Callable;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayLocalizingCursor;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxReorientation;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public class PSFExtraction< T extends RealType< T > & NativeType< T > >
{
	final ArrayImg< T, ? > psf;
	final boolean hadDetections;

	public PSFExtraction(
			final RealRandomAccessible< T > img,
			final Collection< RealLocalizable > locations,
			final T type,
			final long[] size,
			final boolean multithreaded )
	{
		psf = new ArrayImgFactory< T >().create( size, type );

		if ( locations.size() == 0 )
		{
			hadDetections = false;
		}
		else
		{
			hadDetections = true;

			if ( multithreaded )
				extractPSFMultiThreaded( img, locations, psf );
			else
				extractPSFLocal( img, locations, psf );
		}
	}

	public PSFExtraction(
			final RealRandomAccessible< T > img,
			final Collection< RealLocalizable > locations,
			final T type,
			final long[] size )
	{
		this( img, locations, type, size, false );
	}

	public PSFExtraction(
			final RandomAccessible< T > img,
			final Collection< RealLocalizable > locations,
			final T type,
			final long[] size,
			final boolean multithreaded )
	{
		this( Views.interpolate( img, new NLinearInterpolatorFactory< T >() ), locations, type, size, multithreaded );
	}

	public PSFExtraction(
			final RandomAccessible< T > img,
			final Collection< RealLocalizable > locations,
			final T type,
			final long[] size )
	{
		this( img, locations, type, size, false );
	}

	public PSFExtraction(
			final RandomAccessibleInterval< T > img,
			final Collection< RealLocalizable > locations,
			final T type,
			final long[] size,
			final boolean multithreaded )
	{
		// Mirror produces some artifacts ... so we use periodic
		this( Views.extendPeriodic( img ), locations, type, size, multithreaded );
	}

	public PSFExtraction(
			final RandomAccessibleInterval< T > img,
			final Collection< RealLocalizable > locations,
			final T type,
			final long[] size )
	{
		this( img, locations, type, size, false );
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public PSFExtraction(
			final SpimData2 data,
			final ViewId viewId,
			final String label,
			final boolean useCorresponding,
			final T type,
			final long[] size,
			final boolean multithreaded )
	{
		this(
				(RandomAccessibleInterval)data.getSequenceDescription().getImgLoader().getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() ),
				getPoints( data, viewId, label, useCorresponding ),
				type,
				size,
				multithreaded );
	}

	public PSFExtraction(
			final SpimData2 data,
			final ViewId viewId,
			final String label,
			final boolean useCorresponding,
			final T type,
			final long[] size )
	{
		this( data, viewId, label, useCorresponding, type, size, false );
	}

	public static ArrayList< RealLocalizable > getPoints(
			final SpimData2 data,
			final ViewId viewId,
			final String label,
			final boolean useCorresponding )
	{
		final ArrayList< ViewId > list = new ArrayList< ViewId >();
		list.add( viewId );
		final ArrayList< RealLocalizable > points = BoundingBoxReorientation.extractPoints( label, useCorresponding, false, list, data );
		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Found " + points.size() + " locations for PSF extraction" );
	
		return points;
	}

	public boolean hadDetections() { return hadDetections; }
	public ArrayImg< T, ? > getPSF() { return psf; }
	public ArrayImg< T, ? > getTransformedNormalizedPSF( final AffineTransform3D model )
	{
		return getTransformedNormalizedPSF( psf.copy(), model );
	}

	public static < T extends RealType< T > & NativeType< T > > ArrayImg< T, ? > getTransformedNormalizedPSF( final ArrayImg< T, ? > psf, final AffineTransform3D model )
	{
		// normalize PSF
		normalize( psf );

		return transformPSF( psf, model );
	}

	public void removeMinProjections()
	{
		removeMinProjections( psf );
	}

	public static < T extends RealType< T > > void removeMinProjections( final Img< T > psf )
	{
		for ( int d = 0; d < psf.numDimensions(); ++d )
		{
			final Img< T > minProjection = PSFCombination.computeProjection( psf, d, false );
			subtractProjection( psf, minProjection, d );
		}
	}

	public static < T extends RealType< T > > void subtractProjection(
			final RandomAccessibleInterval< T > img,
			final RandomAccessibleInterval< T > proj,
			final int projDim )
	{
		final int n0 = img.numDimensions();

		final Cursor< T > cursor = Views.iterable( img ).localizingCursor();
		final RandomAccess< T > ra = proj.randomAccess();

		while ( cursor.hasNext() )
		{
			final T type = cursor.next();

			int dim = 0;
			for ( int d = 0; d < n0; ++d )
				if ( d != projDim )
					ra.setPosition( cursor.getLongPosition( d ), dim++ );

			type.sub( ra.get() );
		}
	}

	/**
	 * Extracts the PSF by summing up the local neighborhood of locations (most likely RANSAC correspondences)
	 * @param img - the source image
	 * @param locations - the locations inside the source image
	 * @param psf - RAI to write result to
	 * @param <T> pixel type
	 */
	public static < T extends RealType< T > > void extractPSFLocal(
			final RealRandomAccessible< T > img,
			final Collection< RealLocalizable > locations,
			final RandomAccessibleInterval< T > psf )
	{
		final int n = img.numDimensions();

		final RealRandomAccess< T > interpolator = img.realRandomAccess();

		final Cursor< T > psfCursor = Views.iterable( Views.zeroMin( psf ) ).localizingCursor();

		final long[] sizeHalf = new long[ n ];
		for ( int d = 0; d < n; ++d )
			sizeHalf[ d ] = psf.dimension( d ) / 2;

		final int[] tmpI = new int[ n ];
		final double[] tmpD = new double[ n ];

		for ( final RealLocalizable position : locations )
		{
			psfCursor.reset();
			
			while ( psfCursor.hasNext() )
			{
				psfCursor.fwd();
				psfCursor.localize( tmpI );

				for ( int d = 0; d < n; ++d )
					tmpD[ d ] = tmpI[ d ] - sizeHalf[ d ] + position.getDoublePosition( d );

				interpolator.setPosition( tmpD );

				psfCursor.get().setReal( psfCursor.get().getRealDouble() + interpolator.get().getRealDouble() );
			}
		}
	}

	public static < T extends RealType< T > & NativeType< T > > void extractPSFMultiThreaded(
			final RealRandomAccessible< T > img,
			final Collection< RealLocalizable > locations,
			final RandomAccessibleInterval< T > psfGlobal )
	{
		final int n = img.numDimensions();
		final IterableInterval< T > psfSource = Views.iterable( Views.zeroMin( psfGlobal ) );

		final int nThreads = Threads.numThreads();
		final int nPortions = nThreads * 2;
		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();

		// every task gets its own PSF image to fill, we add up later
		final ArrayList< Img< T > > psfLocal = new ArrayList<>();

		for ( int task = 0; task < nPortions; ++task )
		{
			final int myTask = task;

			psfLocal.add( new ArrayImgFactory< T >().create( psfSource, psfSource.firstElement() ) );

			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					final Img< T > psf = psfLocal.get( myTask );

					final RealRandomAccess< T > interpolator = img.realRandomAccess();

					final Cursor< T > psfCursor = Views.iterable( Views.zeroMin( psf ) ).localizingCursor();

					final long[] sizeHalf = new long[ n ];
					for ( int d = 0; d < n; ++d )
						sizeHalf[ d ] = psf.dimension( d ) / 2;

					final int[] tmpI = new int[ n ];
					final double[] tmpD = new double[ n ];

					int j = 0;
					for ( final RealLocalizable position : locations )
					{
						if ( j % myTask == 0 )
						{
							psfCursor.reset();
							
							while ( psfCursor.hasNext() )
							{
								psfCursor.fwd();
								psfCursor.localize( tmpI );
	
								for ( int d = 0; d < n; ++d )
									tmpD[ d ] = tmpI[ d ] - sizeHalf[ d ] + position.getDoublePosition( d );
	
								interpolator.setPosition( tmpD );
	
								psfCursor.get().setReal( psfCursor.get().getRealDouble() + interpolator.get().getRealDouble() );
							}
						}
						++j;
					}

					return null;
				}
			});
		}

		FusionTools.execTasks( tasks, nThreads, "extract PSF's" );

		final ArrayList< RandomAccess< T > > ras = new ArrayList<>();

		for ( final Img< T > psf : psfLocal )
			ras.add( psf.randomAccess() );

		final Cursor< T > cursor = psfSource.localizingCursor();

		while ( cursor.hasNext() )
		{
			final T type = cursor.next();

			for ( final RandomAccess< T > ra : ras )
			{
				ra.setPosition( cursor );
				type.add( ra.get() );
			}
		}
	}

	/**
	 * Transforms the extracted PSF using the affine transformation of the corresponding view
	 * 
	 * @param psf - the extracted psf (NOT z-scaling corrected)
	 * @param model - the transformation model
	 * @param <T> pixel type
	 * @return the transformed psf which has odd sizes and where the center of the psf is also the center of the transformed psf
	 */
	public static < T extends RealType< T > & NativeType< T > > ArrayImg< T, ? > transformPSF(
			final RandomAccessibleInterval< T > psf,
			final AffineTransform3D model )
	{
		// here we compute a slightly different transformation than the ImageTransform does
		// two things are necessary:
		// a) the center pixel stays the center pixel
		// b) the transformed psf has a odd size in all dimensions
		
		final int numDimensions = psf.numDimensions();
		
		final RealInterval minMaxDim = model.estimateBounds( psf );
		
		final double[] size = new double[ numDimensions ];		
		final long[] newSize = new long[ numDimensions ];		
		final double[] offset = new double[ numDimensions ];
		
		// the center of the psf has to be the center of the transformed psf as well
		// this is important!
		final double[] center = new double[ numDimensions ];
		final double[] tmp = new double[ numDimensions ];

		for ( int d = 0; d < numDimensions; ++d )
			center[ d ] = psf.dimension( d ) / 2;
		
		model.apply( center, tmp );

		for ( int d = 0; d < numDimensions; ++d )
		{
			size[ d ] = minMaxDim.realMax( d ) - minMaxDim.realMin( d );
			
			newSize[ d ] = (int)size[ d ] + 1;
			if ( newSize[ d ] % 2 == 0 )
				++newSize[ d ];
				
			// the offset is defined like this:
			// the transformed coordinates of the center of the psf
			// are the center of the transformed psf
			offset[ d ] = tmp[ d ] - newSize[ d ]/2;
		}
		
		return transform( psf, model, newSize, offset );
	}

	public static < T extends RealType< T > & NativeType< T > > ArrayImg< T, ? > transform(
			final RandomAccessibleInterval< T > image,
			final AffineTransform3D transformIn,
			final long[] newDim,
			final double[] offset )
	{
		final int numDimensions = image.numDimensions();
		final AffineTransform3D transform = transformIn.inverse(); 

		// create the new output image
		final ArrayImg< T, ? > transformed = new ArrayImgFactory< T >().create( newDim, Views.iterable( image ).firstElement() );

		final ArrayLocalizingCursor<T> transformedIterator = transformed.localizingCursor();
		final RealRandomAccess<T> interpolator = Views.interpolate( Views.extendZero( image ), new NLinearInterpolatorFactory<T>() ).realRandomAccess();
		
		final double[] tmp1 = new double[ numDimensions ];
		final double[] tmp2 = new double[ numDimensions ];

		while (transformedIterator.hasNext())
		{
			transformedIterator.fwd();

			// we have to add the offset of our new image
			// relative to it's starting point (0,0,0)
			for ( int d = 0; d < numDimensions; ++d )
				tmp1[ d ] = transformedIterator.getIntPosition( d ) + offset[ d ];
			
			// transform back into the original image
			// 
			// in order to compute the voxels in the new object we have to apply
			// the inverse transform to all voxels of the new array and interpolate
			// the position in the original image
			transform.apply( tmp1, tmp2 );
			
			interpolator.setPosition( tmp2 );

			transformedIterator.get().set( interpolator.get() );
		}

		return transformed;
	}

	public static < T extends RealType< T > > void normalize( final IterableInterval< T > img )
	{
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;

		for ( final T t : img )
		{
			final double v = t.getRealDouble();
			
			if ( v < min )
				min = v;
			
			if ( v > max )
				max = v;
		}

		for ( final T t : img )
			t.setReal( ( t.getRealDouble() - min ) / ( max - min ) );
	}
}
