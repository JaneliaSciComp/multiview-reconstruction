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
package net.preibisch.mvrecon.process.pointcloud.icp;

import java.util.ArrayList;
import java.util.List;

import mpicbg.models.Point;
import net.imglib2.KDTree;
import net.imglib2.RealLocalizable;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.LinkedInterestPoint;

public class SimplePointMatchIdentification < P extends RealLocalizable > implements PointMatchIdentification< P >
{
	double distanceThresold;

	public SimplePointMatchIdentification( final double distanceThreshold )
	{
		this.distanceThresold = distanceThreshold;
	}

	public SimplePointMatchIdentification()
	{
		this.distanceThresold = Double.MAX_VALUE;
	}

	public void setDistanceThreshold( final double distanceThreshold ) { this.distanceThresold = distanceThreshold; }
	public double getDistanceThreshold() { return this.distanceThresold; }

	@Override
	public ArrayList< PointMatchGeneric< LinkedInterestPoint< P > > > assignPointMatches( final List< LinkedInterestPoint< P > > target, final List< LinkedInterestPoint< P > > reference )
	{
		final ArrayList< PointMatchGeneric< LinkedInterestPoint< P > > > pointMatches = new ArrayList<>();

		final KDTree< LinkedInterestPoint< P > > kdTreeTarget = new KDTree<>( target, target );
		final NearestNeighborSearchOnKDTree< LinkedInterestPoint< P > > nnSearchTarget = new NearestNeighborSearchOnKDTree<>( kdTreeTarget );

		for ( final LinkedInterestPoint< P > point : reference )
		{
			nnSearchTarget.search( point );
			final LinkedInterestPoint< P > correspondingPoint = nnSearchTarget.getSampler().get();

			// world coordinates of point
			if ( Point.distance( correspondingPoint, point ) <= distanceThresold )
				pointMatches.add( new PointMatchGeneric< LinkedInterestPoint< P > >( correspondingPoint, point ) );
		}

		return pointMatches;
	}
}
