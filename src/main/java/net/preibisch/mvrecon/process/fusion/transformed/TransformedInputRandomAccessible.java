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

import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

public class TransformedInputRandomAccessible< T extends RealType< T > > extends AbstractTransformedImgRandomAccessible< T >
{
	final AffineTransform3D transform;
	final boolean is2d;

	public TransformedInputRandomAccessible(
		final RandomAccessibleInterval< T > img, // from ImgLoader
		final AffineTransform3D transform,
		final boolean hasMinValue,
		final float minValue,
		final FloatType outsideValue,
		final Interval boundingBox )
	{
		super( img, hasMinValue, minValue, outsideValue, boundingBox );

		this.transform = transform;

		if ( img.min( 2 ) == 0 && img.max( 2 ) == 0 && boundingBoxOffset[ 2 ] == 0)
			is2d = true;
		else
			is2d = false;
	}

	public TransformedInputRandomAccessible(
			final RandomAccessibleInterval< T > img, // from ImgLoader
			final AffineTransform3D transform,
			final Interval boundingBox )
	{
		this( img, transform, false, 0.0f, new FloatType( 0 ), boundingBox );
	}

	@Override
	public RandomAccess< FloatType > randomAccess()
	{
		if ( is2d )
			return new TransformedInputRandomAccess2d< T >( img, transform, interpolatorFactory, hasMinValue, minValue, outsideValue, boundingBoxOffset );
		else
			return new TransformedInputRandomAccess< T >( img, transform, interpolatorFactory, hasMinValue, minValue, outsideValue, boundingBoxOffset );
	}
}
