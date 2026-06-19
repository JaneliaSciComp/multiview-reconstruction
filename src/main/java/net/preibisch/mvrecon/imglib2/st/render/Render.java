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
package net.preibisch.mvrecon.imglib2.st.render;

import net.preibisch.mvrecon.imglib2.st.filter.RadiusSearchFilterFactory;

import net.imglib2.Interval;
import net.imglib2.IterableRealInterval;
import net.imglib2.KDTree;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.interpolation.neighborsearch.NearestNeighborSearchInterpolatorFactory;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

public class Render
{
	public static < T extends RealType< T > > RandomAccessibleInterval< T > raster( final RealRandomAccessible< T > realRandomAccessible, final Interval interval )
	{
		return Views.interval(
				Views.raster( realRandomAccessible ),
				interval );
	}

	public static < T extends RealType< T > > RealRandomAccessible< T > renderNN( final IterableRealInterval< T > data )
	{
		return Views.interpolate(
				new NearestNeighborSearchOnKDTree< T >( new KDTree< T > ( data ) ),
				new NearestNeighborSearchInterpolatorFactory< T >() );
	}

	public static < T extends RealType< T > > RealRandomAccessible< T > renderNN( final IterableRealInterval< T > data, final T outofbounds, final double maxRadius )
	{
		return Views.interpolate(
				new NearestNeighborMaxDistanceSearchOnKDTree< T >(
						new KDTree< T > ( data ),
						outofbounds,
						maxRadius ),
				new NearestNeighborSearchInterpolatorFactory< T >() );
	}

	public static < S, T > RealRandomAccessible< T > render( final IterableRealInterval< S > data, final RadiusSearchFilterFactory< S, T > filterFactory )
	{
		return Views.interpolate(
				new FilteredMaxDistanceSearchOnKDTree< S, T >(
						new KDTree<> ( data ),
						filterFactory ),
				new IntegratingNeighborSearchInterpolatorFactory< T >() );
	}
}
