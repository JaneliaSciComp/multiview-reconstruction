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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting;

import bdv.ViewerSetupImgLoader;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.view.Views;

public class SplitViewerSetupImgLoader< T extends NativeType< T >, V extends Volatile< T > & NativeType< V > > implements ViewerSetupImgLoader< T, V >, MultiResolutionSetupImgLoader< T >
{
	final ViewerSetupImgLoader< T, V > underlyingSetupImgLoader;
	final Interval interval;
	final Dimensions size;
	final int n;

	final double[][] mipmapResolutions;
	final AffineTransform3D[] mipmapTransforms;
	final Dimensions[] sizes;
	final Interval[] scaledIntervals;

	public SplitViewerSetupImgLoader( final ViewerSetupImgLoader< T, V > underlyingSetupImgLoader, final Interval interval, final long[][] levelDims )
	{
		this.underlyingSetupImgLoader = underlyingSetupImgLoader;
		this.interval = interval;
		this.n = interval.numDimensions();

		final long[] dim = new long[ interval.numDimensions() ];
		interval.dimensions( dim );

		this.size = new FinalDimensions( dim );

		final int levels = underlyingSetupImgLoader.numMipmapLevels();
		this.sizes = new Dimensions[ levels ];
		this.scaledIntervals = new Interval[ levels ];
		this.mipmapResolutions = underlyingSetupImgLoader.getMipmapResolutions();
		this.mipmapTransforms = underlyingSetupImgLoader.getMipmapTransforms();//new AffineTransform3D[ levels ];

		setUpMultiRes( levels, n, interval, mipmapResolutions, /*mipmapTransforms,*/ sizes, scaledIntervals/*, underlyingSetupImgLoader.getMipmapTransforms()*/ );

		// Eagerly clamp scaledIntervals[level] against the underlying image's
		// per-level dims (passed in from SplitViewerImgLoader's central cache).
		// Replaces the previous lazy-DCL updateScaledIntervals() path. Trailing
		// splits whose precomputed max would exceed the level image's last
		// index get clipped here once; sizes[level] is updated to match.
		for ( int level = 0; level < levels; ++level )
		{
			final Interval s = scaledIntervals[ level ];
			final long[] dims = levelDims[ level ];
			boolean clamp = false;
			for ( int d = 0; d < n; ++d )
				if ( s.max( d ) > dims[ d ] - 1 ) { clamp = true; break; }
			if ( clamp )
			{
				final long[] minL = new long[ n ];
				final long[] maxL = new long[ n ];
				final long[] sizeL = new long[ n ];
				for ( int d = 0; d < n; ++d )
				{
					minL[ d ] = s.min( d );
					maxL[ d ] = Math.min( s.max( d ), dims[ d ] - 1 );
					sizeL[ d ] = maxL[ d ] - minL[ d ] + 1;
				}
				scaledIntervals[ level ] = new FinalInterval( minL, maxL );
				sizes[ level ] = new FinalDimensions( sizeL );
			}
		}
	}

	protected static final void setUpMultiRes(
			final int levels,
			final int n,
			final Interval interval,
			final double[][] mipmapResolutions,
			//final AffineTransform3D[] mipmapTransforms,
			final Dimensions[] sizes,
			final Interval[] scaledIntervals
			//final AffineTransform3D[] oldmipmapTransforms
			)
	{
		// precompute intervals and new mipmaptransforms (because of rounding of interval borders)
		for ( int level = 0; level < levels; ++level )
		{
			final double[] min = new double[ n ];
			final double[] max = new double[ n ];
	
			final long[] minL = new long[ n ];
			final long[] maxL = new long[ n ];
			final long[] size = new long[ n ];

			for ( int d = 0; d < n; ++d )
			{
				min[ d ] = interval.realMin( d ) / mipmapResolutions[ level ][ d ];
				max[ d ] = (interval.realMax( d ) - 0.01) / mipmapResolutions[ level ][ d ];

				minL[ d ] = Math.round( Math.floor( min[ d ] ) );
				maxL[ d ] = Math.round( Math.floor( max[ d ] ) );

				size[ d ] = maxL[ d ] - minL[ d ] + 1;
			}

			sizes[ level ] = new FinalDimensions( size );
			scaledIntervals[ level ] = new FinalInterval( minL, maxL );

			/*
			final AffineTransform3D mipMapTransform = oldmipmapTransforms[ level ].copy();

			// the additional downsampling (performed below)
			final AffineTransform3D additonalTranslation = new AffineTransform3D();
			additonalTranslation.set(
					1.0, 0.0, 0.0, (minL[ 0 ] - min[ 0 ]),
					0.0, 1.0, 0.0, (minL[ 1 ] - min[ 1 ]),
					0.0, 0.0, 1.0, (minL[ 2 ] - min[ 2 ]) );

			System.out.println( additonalTranslation );

			mipMapTransform.concatenate( additonalTranslation );
			mipmapTransforms[ level ] = mipMapTransform;
			*/
		}
	}

	@Override
	public RandomAccessibleInterval< T > getImage( final int timepointId, final ImgLoaderHint... hints )
	{
		return Views.zeroMin( Views.interval( underlyingSetupImgLoader.getImage( timepointId, hints ), interval ) );
	}

	@Override
	public T getImageType()
	{
		return underlyingSetupImgLoader.getImageType();
	}

	@Override
	public Dimensions getImageSize( final int timepointId )
	{
		return size;
	}

	@Override
	public VoxelDimensions getVoxelSize( final int timepointId )
	{
		throw new RuntimeException( "not supported." );
	}

	@Override
	public RandomAccessibleInterval< T > getImage( final int timepointId, final int level, final ImgLoaderHint... hints )
	{
		/*
		System.out.println( "requesting: " + level );

		for ( int l = 0; l < mipmapResolutions.length; ++l )
		{
			System.out.println( "level " + l + ": " + mipmapTransforms[ l ] );
			System.out.println( "level " + l + ": " + Util.printInterval( scaledIntervals[ l ] ) );
			System.out.print( "level " + l + ": " );
			for ( int d = 0; d < mipmapResolutions[ l ].length; ++d )
				System.out.print( mipmapResolutions[ l ][ d ] + "x" );
			System.out.println();
		}

		// 164 is a problem
		final RandomAccessibleInterval< UnsignedShortType > full = underlyingSetupImgLoader.getImage( timepointId, level, hints );

		updateScaledIntervals( this.scaledIntervals, level, n, full );

		final RandomAccessibleInterval img = Views.zeroMin( Views.interval( full, scaledIntervals[ level ] ) );

		if ( level == 3 && img.dimension( 0  ) == 33 )
		{
			DisplayImage.getImagePlusInstance( full, false, "levefull=" + level, 0.0, 255.0 ).show();;
			DisplayImage.getImagePlusInstance( img, false, "level=" + level, 0.0, 255.0 ).show();;
		}

		System.out.println( "size: " + Util.printInterval( img ) );
		System.out.println( "interval: " + Util.printInterval( scaledIntervals[ level ] ) ); */

		return Views.zeroMin( Views.interval(
				underlyingSetupImgLoader.getImage( timepointId, level, hints ),
				scaledIntervals[ level ] ) );
	}

	@Override
	public RandomAccessibleInterval< V > getVolatileImage( final int timepointId, final int level, final ImgLoaderHint... hints )
	{
		return Views.zeroMin( Views.interval(
				underlyingSetupImgLoader.getVolatileImage( timepointId, level, hints ),
				scaledIntervals[ level ] ) );
	}

	@Override
	public double[][] getMipmapResolutions()
	{
		return mipmapResolutions;
	}

	@Override
	public AffineTransform3D[] getMipmapTransforms()
	{
		return mipmapTransforms;
	}

	@Override
	public int numMipmapLevels()
	{
		return underlyingSetupImgLoader.numMipmapLevels();
	}

	@Override
	public Dimensions getImageSize( final int timepointId, final int level )
	{
		return sizes[ level ];
	}

	@Override
	public V getVolatileImageType()
	{
		return underlyingSetupImgLoader.getVolatileImageType();
	}
}
