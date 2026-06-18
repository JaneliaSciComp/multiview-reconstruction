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
package net.preibisch.mvrecon.process.fusion.transformed.nonrigid;

import java.util.Collection;

import mpicbg.models.AffineModel3D;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.fusion.transformed.AbstractTransformedImgRandomAccessible;

public class NonRigidRandomAccessible< T extends RealType< T > > extends AbstractTransformedImgRandomAccessible< T >
{
	final Collection< ? extends NonrigidIP > ips;
	final double alpha;
	final AffineModel3D invertedModelOpener;

	public NonRigidRandomAccessible(
		final RandomAccessibleInterval< T > img, // from ImgLoader
		final Collection< ? extends NonrigidIP > ips,
		final double alpha,
		final AffineModel3D invertedModelOpener,
		final boolean hasMinValue,
		final float minValue,
		final FloatType outsideValue,
		final Interval boundingBox )
	{
		super( img, hasMinValue, minValue, outsideValue, boundingBox );

		this.ips = ips;
		this.alpha = alpha;
		this.invertedModelOpener = invertedModelOpener;
	}

	public NonRigidRandomAccessible(
			final RandomAccessibleInterval< T > img, // from ImgLoader
			final Collection< ? extends NonrigidIP > ips,
			final AffineModel3D invertedModelOpener,
			final Interval boundingBox )
	{
		this( img, ips, 1.0, invertedModelOpener, false, 0.0f, new FloatType( 0 ), boundingBox );
	}

	public double getAlpha() { return alpha; }

	@Override
	public RandomAccess< FloatType > randomAccess()
	{
		return new NonRigidRandomAccess< T >( img, ips, alpha, invertedModelOpener, interpolatorFactory, hasMinValue, minValue, outsideValue, boundingBoxOffset );
	}
}
