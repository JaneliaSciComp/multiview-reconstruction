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

import net.preibisch.mvrecon.imglib2.st.render.util.SimpleRealLocalizable;
import net.preibisch.mvrecon.imglib2.st.render.util.SimpleSampler;

import net.imglib2.KDTree;
import net.imglib2.RealLocalizable;
import net.imglib2.Sampler;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;

public class NearestNeighborMaxDistanceSearchOnKDTree< T > extends NearestNeighborSearchOnKDTree< T >
{
	final KDTree< T > tree;          // shadow of super (now private)
	final T outofbounds;
	final SimpleSampler< T > oobsSampler;
	final double[] pos;              // current query position; shadow of super (now private)
	final SimpleRealLocalizable position;
	final double maxSqDistance, maxDistance;

	Sampler< T > value;
	RealLocalizable point;
	double newbestSquDistance;

	public NearestNeighborMaxDistanceSearchOnKDTree( final KDTree< T > tree, final T outofbounds, final double maxDistance )
	{
		super( tree );

		this.tree = tree;
		this.pos = new double[ tree.numDimensions() ];
		this.oobsSampler = new SimpleSampler< T >( outofbounds );
		this.position = new SimpleRealLocalizable( pos );
		this.maxDistance = maxDistance;
		this.maxSqDistance = maxDistance * maxDistance;
		this.outofbounds = outofbounds;
	}

	@Override
	public void search( final RealLocalizable p )
	{
		super.search( p );
		p.localize( pos );

		final double sq = super.getSquareDistance();
		if ( sq > maxSqDistance )
		{
			value = oobsSampler;
			point = position;
			newbestSquDistance = 0;
		}
		else
		{
			value = super.getSampler();
			point = super.getPosition();
			newbestSquDistance = sq;
		}
	}

	@Override
	public Sampler< T > getSampler()
	{
		return value;
	}

	@Override
	public RealLocalizable getPosition()
	{
		return point;
	}

	@Override
	public double getSquareDistance()
	{
		return newbestSquDistance;
	}

	@Override
	public double getDistance()
	{
		return Math.sqrt( newbestSquDistance );
	}

	@Override
	public NearestNeighborMaxDistanceSearchOnKDTree< T > copy()
	{
		// Note: the original hot-knife copy copied the package/private bestPoint and bestSquDistance
		// of the super class. In current imglib2 those are private; rely on lazy re-population
		// via the next search(...) call instead. Caller must call search() on the returned copy
		// before using it (same as fresh instances).
		final NearestNeighborMaxDistanceSearchOnKDTree< T > copy = new NearestNeighborMaxDistanceSearchOnKDTree< T >( tree, outofbounds, maxDistance );
		copy.newbestSquDistance = newbestSquDistance;
		copy.point = point;
		copy.value = value;
		return copy;
	}
}
