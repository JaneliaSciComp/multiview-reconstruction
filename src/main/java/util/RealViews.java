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
package util;

import net.imglib2.RealRandomAccessible;

public class RealViews
{
	/**
	 * Add a dimension to a {@link RealRandomAccessible}. The resulting
	 * {@link RealRandomAccessible} has samples from the original dimensions
	 * continuously stacked along the added dimensions.
	 *
	 * The additional dimension is the last dimension. For example, an XYZ view
	 * is created for an XY source. When accessing an XYZ sample in the view,
	 * the final coordinate is discarded and the source XY sample is accessed.
	 *
	 * @param source
	 *            the source
	 * @param <T>
	 *            the pixel type
	 * @return stacked view with an additional last dimension
	 */
	public static < T > RealRandomAccessible< T > addDimension( final RealRandomAccessible< T > source )
	{
		return new StackingRealRandomAccessible< >( source, 1 );
	}
}
