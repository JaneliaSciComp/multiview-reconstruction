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
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

public abstract class AbstractTransformedImgRandomAccessible< T extends RealType< T > > extends AbstractTransformedIntervalRandomAccessible
{
	final protected RandomAccessibleInterval< T > img;

	final protected boolean hasMinValue;
	final protected float minValue;

	public AbstractTransformedImgRandomAccessible(
			final RandomAccessibleInterval< T > img, // from ImgLoader
			final boolean hasMinValue,
			final float minValue,
			final FloatType outsideValue,
			final Interval boundingBox )
	{
		super( img, outsideValue, boundingBox );

		this.img = img;
		this.hasMinValue = hasMinValue;
		this.minValue = minValue;
	}
}
