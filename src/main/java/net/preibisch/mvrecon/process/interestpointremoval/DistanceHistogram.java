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
package net.preibisch.mvrecon.process.interestpointremoval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointremoval.histogram.Histogram;

public class DistanceHistogram
{
	public static int subsampling = 1;

	public static Histogram plotHistogram(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewIds,
			final String label,
			final String title )
	{
		final HashMap< ViewId, String > labelMap = new HashMap<>();

		for ( final ViewId view : viewIds )
			labelMap.put( view, label );

		return plotHistogram( spimData, viewIds, labelMap, title );
	}

	public static Histogram plotHistogram(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewIds,
			final HashMap< ? extends ViewId, String > labelMap,
			final String title )
	{
		final ViewInterestPoints vip = spimData.getViewInterestPoints();

		// list of all distances
		final ArrayList< Double > distances = new ArrayList< Double >();
		final Random rnd = new Random( System.currentTimeMillis() );
		String unit = null;

		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( viewId );

			final ViewInterestPointLists vipl = vip.getViewInterestPointLists( viewId );
			final InterestPoints ipl = vipl.getInterestPointList( labelMap.get( viewId ) );

			if ( ipl == null )
				continue;

			final VoxelDimensions voxelSize = vd.getViewSetup().getVoxelSize();

			if ( unit == null )
				unit = vd.getViewSetup().getVoxelSize().unit();

			// assemble the list of points
			final List< RealPoint > list = new ArrayList< RealPoint >();

			for ( final InterestPoint ip : ipl.getInterestPointsCopy().values() )
			{
				list.add ( new RealPoint(
						ip.getL()[ 0 ] * voxelSize.dimension( 0 ),
						ip.getL()[ 1 ] * voxelSize.dimension( 1 ),
						ip.getL()[ 2 ] * voxelSize.dimension( 2 ) ) );
			}

			if ( list.size() < 2 )
				continue;

			// make the KDTree
			final KDTree< RealPoint > tree = new KDTree< RealPoint >( list, list );

			// Nearest neighbor for each point
			final KNearestNeighborSearchOnKDTree< RealPoint > nn = new KNearestNeighborSearchOnKDTree< RealPoint >( tree, 2 );

			for ( final RealPoint p : list )
			{
				// every n'th point only
				if ( subsampling == 1 || rnd.nextDouble() < 1.0 / subsampling )
				{
					nn.search( p );
					
					// first nearest neighbor is the point itself, we need the second nearest
					distances.add( nn.getDistance( 1 ) );
				}
			}
		}

		final Histogram h = new Histogram( distances, 100, "Distance Histogram [" + title + "]", unit  );
		h.showHistogram();
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): min distance=" + h.getMin() + ", max distance=" + h.getMax() );

		return h;
	}

	public static String getHistogramTitle( final Collection< ? extends ViewId > views )
	{
		String title;

		if ( views.size() == 1 )
			title = Group.pvid( views.iterator().next() );
		else if ( views.size() < 5 )
			title = Group.gvids( views );
		else
			title = views.size() + " views";

		return title;
	}
}
