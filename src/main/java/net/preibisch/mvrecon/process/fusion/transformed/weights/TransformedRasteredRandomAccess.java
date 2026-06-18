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
package net.preibisch.mvrecon.process.fusion.transformed.weights;

import net.imglib2.AbstractLocalizableInt;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.realtransform.AffineTransform3D;

public class TransformedRasteredRandomAccess< T > extends AbstractLocalizableInt implements RandomAccess< T >
{
	final RealRandomAccessible< T > realRandomAccessible;
	final RealRandomAccess< T > realRandomAccess;
	final AffineTransform3D transform;
	final int[] offset;
	final T zero;

	/*
	 * Inverse coefficients of the transformation matrix
	 */
	final double i00, i01, i02, i03, i10, i11, i12, i13, i20, i21, i22, i23;

	final float[] tmp;

	public TransformedRasteredRandomAccess(
			final RealRandomAccessible< T > realRandomAccessible,
			final T zero,
			final AffineTransform3D transform,
			final int[] offset )
	{
		super( realRandomAccessible.numDimensions() );

		this.zero = zero;
		this.realRandomAccessible = realRandomAccessible;
		this.transform = transform;
		this.offset = new int[ offset.length ];

		for ( int d = 0; d < n; ++d )
			this.offset[ d ] = offset[ d ];

		this.realRandomAccess = realRandomAccessible.realRandomAccess();

		final double[] imatrix = transform.inverse().getRowPackedCopy();

		this.i00 = imatrix[ 0 ];
		this.i01 = imatrix[ 1 ];
		this.i02 = imatrix[ 2 ];
		this.i03 = imatrix[ 3 ];

		this.i10 = imatrix[ 4 ];
		this.i11 = imatrix[ 5 ];
		this.i12 = imatrix[ 6 ];
		this.i13 = imatrix[ 7 ];

		this.i20 = imatrix[ 8 ];
		this.i21 = imatrix[ 9 ];
		this.i22 = imatrix[ 10 ];
		this.i23 = imatrix[ 11 ];

		this.tmp = new float[ n ];
	}

	@Override
	public T get()
	{
		applyInverse( i00, i01, i02, i03, i10, i11, i12, i13, i20, i21, i22, i23, tmp, position, offset );
		realRandomAccess.setPosition( tmp );
		return realRandomAccess.get();
	}

	private static final void applyInverse(
			final double i00, final double i01, final double i02, final double i03,
			final double i10, final double i11, final double i12, final double i13,
			final double i20, final double i21, final double i22, final double i23,
			final float[] source,
			final int[] target,
			final int[] offset )
	{
		final double t0 = (double)( target[ 0 ] + offset[ 0 ] );
		final double t1 = (double)( target[ 1 ] + offset[ 1 ] );
		final double t2 = (double)( target[ 2 ] + offset[ 2 ] );

		final double s0 = t0 * i00 + t1 * i01 + t2 * i02 + i03;
		final double s1 = t0 * i10 + t1 * i11 + t2 * i12 + i13;
		final double s2 = t0 * i20 + t1 * i21 + t2 * i22 + i23;

		source[ 0 ] = (float)s0;
		source[ 1 ] = (float)s1;
		source[ 2 ] = (float)s2;
	}

	@Override
	public void fwd( final int d ) { ++this.position[ d ]; }

	@Override
	public void bck( final int d ) { --this.position[ d ]; }

	@Override
	public void move( final int distance, final int d ) { this.position[ d ] += distance; }

	@Override
	public void move( final long distance, final int d ) { this.position[ d ] += (int)distance; }

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += localizable.getIntPosition( d );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] += (int)distance[ d ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = localizable.getIntPosition( d );
	}

	@Override
	public void setPosition( final int[] position )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = position[ d ];
	}

	@Override
	public void setPosition( final long[] position )
	{
		for ( int d = 0; d < n; ++d )
			this.position[ d ] = (int)position[ d ];
	}

	@Override
	public void setPosition( final int position, final int d ) { this.position[ d ] = position; }

	@Override
	public void setPosition( final long position, final int d ) { this.position[ d ] = (int)position; }

	@Override
	public TransformedRasteredRandomAccess< T > copy() { return new TransformedRasteredRandomAccess< T >( realRandomAccessible, zero, transform, offset ); }

	@Override
	public TransformedRasteredRandomAccess<T> copyRandomAccess() { return copy(); }
}
