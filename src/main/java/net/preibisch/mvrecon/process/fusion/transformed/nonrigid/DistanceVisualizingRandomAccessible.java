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

import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.fusion.transformed.AbstractTransformedIntervalRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;

public class DistanceVisualizingRandomAccessible extends AbstractTransformedIntervalRandomAccessible
{
	final ModelGrid grid;
	final AffineTransform3D originalTransform;

	public DistanceVisualizingRandomAccessible(
		final Interval interval, // from ImgLoader
		final ModelGrid grid,
		final AffineTransform3D originalTransform,
		final FloatType outsideValue,
		final Interval boundingBox )
	{
		super( interval, outsideValue, boundingBox );

		this.grid = grid;
		this.originalTransform = originalTransform;
	}

	@Override
	public RandomAccess< FloatType > randomAccess()
	{
		return new DistanceVisualizingRandomAccess( interval, grid, originalTransform, interpolatorFactory, outsideValue, boundingBoxOffset );
	}
}
