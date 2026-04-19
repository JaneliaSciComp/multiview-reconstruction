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

import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

/**
 * Virtually transforms any RandomAccessibleInterval&lt;RealType&gt; into a RandomAccess&lt;FloatType&gt; using an AffineTransformation
 * and Linear Interpolation. It will only interpolate from the actual data (no outofbounds) to avoid artifacts at the edges
 * and return 0 outside by default (can be changed).
 * 
 * @author preibisch
 */
public class TransformedInputRandomAccess< T extends RealType< T > > extends AbstractTransformedImgRandomAccess< T >
{
	final AffineTransform3D transform;
	final double[] s, t;

	public TransformedInputRandomAccess(
			final RandomAccessibleInterval< T > img, // from ImgLoader
			final AffineTransform3D transform,
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory,
			final boolean hasMinValue,
			final float minValue,
			final FloatType outside,
			final long[] offset )
	{
		super( img, interpolatorFactory, hasMinValue, minValue, outside, offset );

		this.transform = transform;
		this.s = new double[ n ];
		this.t = new double[ n ];
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

		// check if position t is inside of the input image (pixel coordinates)
		if ( intersectsLinearInterpolation( t[ 0 ], t[ 1 ], t[ 2 ], imgMinX, imgMinY, imgMinZ, imgMaxX, imgMaxY, imgMaxZ ) )
		{
			ir.setPosition( t );

			return getInsideValue( v, ir, hasMinValue, minValue );
		}
		else
		{
			return outside;
		}
	}

	@Override
	public TransformedInputRandomAccess< T > copy()
	{
		return copyRandomAccess();
	}

	@Override
	public TransformedInputRandomAccess< T > copyRandomAccess()
	{
		final TransformedInputRandomAccess< T > r = new TransformedInputRandomAccess< T >(
				img, transform, interpolatorFactory, hasMinValue, minValue, outside, new long[] { offsetX, offsetY, offsetZ } );
		r.setPosition( this );
		return r;
	}
}
