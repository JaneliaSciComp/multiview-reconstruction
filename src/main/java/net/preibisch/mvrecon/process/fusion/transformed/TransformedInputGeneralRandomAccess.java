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

import net.imglib2.AbstractLocalizableInt;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.outofbounds.OutOfBoundsFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

/**
 * Virtually transforms any RandomAccessibleInterval&lt;RealType&gt; into a RandomAccess&lt;FloatType&gt; using an AffineTransformation
 * 
 * @author preibisch
 *
 * @param <T> pixel type
 */
public class TransformedInputGeneralRandomAccess< T extends RealType< T > > extends AbstractLocalizableInt implements RandomAccess< FloatType >
{
	final RandomAccessibleInterval< T > img;
	final OutOfBoundsFactory< T, RandomAccessible< T > > outOfBoundsFactory;
	final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory;
	final AffineTransform3D transform;
	final long[] offset;
	final float[] s, t;
	final FloatType v;

	final RealRandomAccess< FloatType > ir;
	final int offsetX, offsetY, offsetZ;
	final int imgMinX, imgMinY, imgMinZ;
	final int imgMaxX, imgMaxY, imgMaxZ;

	@SuppressWarnings("unchecked")
	public TransformedInputGeneralRandomAccess(
			final RandomAccessibleInterval< T > img, // from ImgLoader
			final AffineTransform3D transform,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory,
			final OutOfBoundsFactory< T, RandomAccessible< T > > outOfBoundsFactory,
			final long[] offset )
	{
		super( img.numDimensions() );

		this.offsetX = (int)offset[ 0 ];
		this.offsetY = (int)offset[ 1 ];
		this.offsetZ = (int)offset[ 2 ];

		this.imgMinX = (int)img.min( 0 );
		this.imgMinY = (int)img.min( 1 );
		this.imgMinZ = (int)img.min( 2 );

		this.imgMaxX = (int)img.max( 0 );
		this.imgMaxY = (int)img.max( 1 );
		this.imgMaxZ = (int)img.max( 2 );

		this.img = img;
		this.outOfBoundsFactory = outOfBoundsFactory;
		this.interpolatorFactory = interpolatorFactory;
		this.transform = transform;
		this.offset = offset;
		this.s = new float[ n ];
		this.t = new float[ n ];
		this.v = new FloatType();

		// extend input image and convert to floats
		final RandomAccessible< FloatType > input;

		if ( FloatType.class.isInstance( Views.iterable( img ).cursor().next() ) )
		{
			input = (RandomAccessible< FloatType >)Views.extend( img, outOfBoundsFactory );
		}
		else
		{
			input =
					Converters.convert(
						Views.extend( img, outOfBoundsFactory ),
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
		s[ 0 ] = position[ 0 ] + offsetX;
		s[ 1 ] = position[ 1 ] + offsetY;
		s[ 2 ] = position[ 2 ] + offsetZ;

		// go from world coordinate system to local coordinate system of input image (pixel coordinates)
		transform.applyInverse( t, s );

		ir.setPosition( t );
		return ir.get();
	}

	@Override
	public TransformedInputGeneralRandomAccess< T > copy()
	{
		return copyRandomAccess();
	}

	@Override
	public TransformedInputGeneralRandomAccess< T > copyRandomAccess()
	{
		final TransformedInputGeneralRandomAccess< T > r = new TransformedInputGeneralRandomAccess< T >( img, transform, interpolatorFactory, outOfBoundsFactory, offset );
		r.setPosition( this );
		return r;
	}

	@Override
	public void fwd( final int d ) { ++position[ d ]; }

	@Override
	public void bck( final int d ) { --position[ d ]; }

	@Override
	public void move( final int distance, final int d ) { position[ d ] += distance; }

	@Override
	public void move( final long distance, final int d ) { position[ d ] += distance; }

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += localizable.getIntPosition( d );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += distance[ d ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		localizable.localize( position );
	}

	@Override
	public void setPosition( final int[] pos )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = pos[ d ];
	}

	@Override
	public void setPosition( final long[] pos )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = ( int ) pos[ d ];
	}

	@Override
	public void setPosition( final int pos, final int d )
	{
		position[ d ] = pos;
	}

	@Override
	public void setPosition( final long pos, final int d )
	{
		position[ d ] = ( int ) pos;
	}
}
