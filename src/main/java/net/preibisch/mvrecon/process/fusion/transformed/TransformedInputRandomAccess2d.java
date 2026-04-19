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
package net.preibisch.mvrecon.process.fusion.transformed;

import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Virtually transforms any RandomAccessibleInterval&lt;RealType&gt; into a RandomAccess&lt;FloatType&gt; using an AffineTransformation
 * and Linear Interpolation. It will only interpolate from the actual data (no outofbounds) to avoid artifacts at the edges
 * and return 0 outside by default (can be changed).
 * 
 * This is actually a two-dimensional implementation that represents itself as 3d to be compatible
 * 
 * @author preibisch
 */
public class TransformedInputRandomAccess2d< T extends RealType< T > > implements Localizable, RandomAccess< FloatType >
{
	final boolean hasMinValue;
	final float minValue;
	final FloatType outside;

	final RandomAccessibleInterval< T > img;
	final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory;
	final AffineTransform3D transform;
	final long[] offset;
	int pX = 0, pY = 0;

	final float[] s, t, t2d;
	final FloatType v;

	final RealRandomAccess< FloatType > ir;
	final int offsetX, offsetY;
	final int imgMinX, imgMinY;
	final int imgMaxX, imgMaxY;

	@SuppressWarnings("unchecked")
	public TransformedInputRandomAccess2d(
			final RandomAccessibleInterval< T > img3d, // from ImgLoader
			final AffineTransform3D transform,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory,
			final boolean hasMinValue,
			final float minValue,
			final FloatType outside,
			final long[] offset )
	{
		this.img = Views.hyperSlice( img3d, 2, 0 );
		this.outside = outside;

		this.hasMinValue = hasMinValue;
		this.minValue = minValue;

		this.offsetX = (int)offset[ 0 ];
		this.offsetY = (int)offset[ 1 ];

		this.imgMinX = (int)img.min( 0 );
		this.imgMinY = (int)img.min( 1 );

		this.imgMaxX = (int)img.max( 0 );
		this.imgMaxY = (int)img.max( 1 );

		this.offset = offset;
		this.interpolatorFactory = interpolatorFactory;
		this.transform = transform;
		this.s = new float[ 3 ];
		this.t = new float[ 3 ];
		this.t2d = new float[ 2 ];

		this.v = new FloatType();

		// extend input image and convert to floats
		final RandomAccessible< FloatType > input;

		if ( FloatType.class.isInstance( Views.iterable( img ).cursor().next() ) )
		{
			input = (RandomAccessible< FloatType >)img;
		}
		else
		{
			input =
					Converters.convert(
						img,
						new RealFloatConverter< T >(),
						new FloatType() );
		}

		// make the interpolator
		this.ir = Views.interpolate( input, interpolatorFactory ).realRandomAccess();
	}

	@Override
	public FloatType get()
	{
		// go from PSI(Decon)_image local coordinate system to world coordinate system
		s[ 0 ] = pX + offsetX;
		s[ 1 ] = pY + offsetY;

		// go from world coordinate system to local coordinate system of input image (pixel coordinates)
		transform.applyInverse( t, s );
		t2d[ 0 ] = t[ 0 ];
		t2d[ 1 ] = t[ 1 ];

		// check if position t is inside of the input image (pixel coordinates)
		if ( intersectsLinearInterpolation( t[ 0 ], t[ 1 ], imgMinX, imgMinY, imgMaxX, imgMaxY ) )
		{
			ir.setPosition( t2d );

			return getInsideValue( v, ir, hasMinValue, minValue );
		}
		else
		{
			return outside;
		}
	}

	private static final FloatType getInsideValue( final FloatType v, final RealRandomAccess< FloatType > ir, final boolean hasMinValue, final float minValue )
	{
		if ( hasMinValue )
		{
			// do not accept 0 values in the data where image data is present, 0 means no image data is available
			// (used in MVDeconvolution.computeQuotient)
			// here return the minimal value of the lucy-richardson deconvolution = MVDeconvolution.minValue (e.g 0.0001)
			v.set( Math.max( minValue, ir.get().get() ) );

			return v;
		}
		else
		{
			return ir.get();
		}
	}

	private static final boolean intersectsLinearInterpolation(
			final double x, final double y,
			final long minX, final long minY,
			final long maxX, final long maxY )
	{
		// to avoid interpolation artifacts from the outofboundsstrategy,
		// the coordinate has to be bigger than min and smaller than max (assuming linear or NN interpolation)
		if ( x > minX && y > minY && x < maxX && y < maxY )
			return true;
		else
			return false;
	}

	@Override
	public TransformedInputRandomAccess2d< T > copy()
	{
		return copyRandomAccess();
	}

	@Override
	public TransformedInputRandomAccess2d< T > copyRandomAccess()
	{
		final TransformedInputRandomAccess2d< T > r = new TransformedInputRandomAccess2d< T >( img, transform, interpolatorFactory, hasMinValue, minValue, outside, offset );
		r.setPosition( this );
		return r;
	}

	@Override
	public void fwd( final int d )
	{
		if ( d == 0 )
			++pX;
		else if ( d == 1 )
			++pY;
	}

	@Override
	public void bck( final int d )
	{
			if ( d == 0 )
			--pX;
		else if ( d == 1 )
			--pY;
	}

	@Override
	public void move( final int distance, final int d )
	{
		if ( d == 0 )
			pX += distance;
		else if ( d == 1 )
			pY += distance;
	}

	@Override
	public void move( final long distance, final int d )
	{
		if ( d == 0 )
			pX += distance;
		else if ( d == 1 )
			pY += distance;
	}

	@Override
	public void move( final Localizable localizable )
	{
		pX += localizable.getIntPosition( 0 );
		pY += localizable.getIntPosition( 1 );
	}

	@Override
	public void move( final int[] distance )
	{
		pX += distance[ 0 ];
		pY += distance[ 1 ];
	}

	@Override
	public void move( final long[] distance )
	{
		pX += distance[ 0 ];
		pY += distance[ 1 ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		pX = localizable.getIntPosition( 0 );
		pY = localizable.getIntPosition( 1 );
	}

	@Override
	public void setPosition( final int[] pos )
	{
		pX = pos[ 0 ];
		pY = pos[ 1 ];
	}

	@Override
	public void setPosition( final long[] pos )
	{
		pX = (int)pos[ 0 ];
		pY = (int)pos[ 1 ];
	}

	@Override
	public void setPosition( final int pos, final int d )
	{
		if ( d == 0 )
			pX = pos;
		else if ( d == 1 )
			pY = pos;
	}

	@Override
	public void setPosition( final long pos, final int d )
	{
		if ( d == 0 )
			pX = (int)pos;
		else if ( d == 1 )
			pY = (int)pos;
	}

	@Override
	public void localize( final float[] position )
	{
		position[ 0 ] = pX;
		position[ 1 ] = pY;
	}

	@Override
	public void localize( final double[] position )
	{
		position[ 0 ] = pX;
		position[ 1 ] = pY;
	}

	@Override
	public float getFloatPosition( final int d )
	{
		if ( d == 0 )
			return pX;
		else if ( d == 1 )
			return pY;
		else
			return 0;
	}

	@Override
	public double getDoublePosition( final int d )
	{
		if ( d == 0 )
			return pX;
		else if ( d == 1 )
			return pY;
		else
			return 0;
	}

	@Override
	public int numDimensions()
	{
		return 3;
	}

	@Override
	public void localize( final int[] position )
	{
		position[ 0 ] = pX;
		position[ 1 ] = pY;
	}

	@Override
	public void localize( final long[] position )
	{
		position[ 0 ] = pX;
		position[ 1 ] = pY;
	}

	@Override
	public int getIntPosition( final int d )
	{
		if ( d == 0 )
			return pX;
		else if ( d == 1 )
			return pY;
		else
			return 0;
	}

	@Override
	public long getLongPosition( int d )
	{
		if ( d == 0 )
			return pX;
		else if ( d == 1 )
			return pY;
		else
			return 0;
	}
}
