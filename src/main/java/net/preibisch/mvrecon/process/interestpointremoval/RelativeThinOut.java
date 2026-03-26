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
import java.util.List;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class RelativeThinOut
{
	public static boolean thinOut( final SpimData2 spimData, final Collection< ? extends ViewId > viewIds, final RelativeThinOutParameters rtop )
	{
		final ViewInterestPoints vip = spimData.getViewInterestPoints();

		final double minDistance = rtop.getMin();
		final double maxDistance = rtop.getMax();
		final boolean keepRange = rtop.keepRange();

		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( viewId );

			final ViewInterestPointLists vipl = vip.getViewInterestPointLists( viewId );
			final InterestPoints oldIpl = vipl.getInterestPointList( rtop.getLabel() );

			if ( oldIpl == null )
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): "
						+ "No interestpoints for " + Group.pvid( viewId ) + " label '" + rtop.getLabel() + "'" );
				continue;
			}

			final InterestPoints iplRelative = vipl.getInterestPointList( rtop.getRelativeLabel() );

			if ( iplRelative == null )
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): "
						+ "No interestpoints for " + Group.pvid( viewId ) + " label '" + rtop.getRelativeLabel() + "'" );
				continue;
			}

			final VoxelDimensions voxelSize = vd.getViewSetup().getVoxelSize();

			final List< RealPoint > list = new ArrayList< RealPoint >();
			final List< double[] > points = new ArrayList< double[] >();

			for ( final InterestPoint ip : oldIpl.getInterestPointsCopy().values() )
			{
				list.add ( new RealPoint(
						ip.getL()[ 0 ] * voxelSize.dimension( 0 ),
						ip.getL()[ 1 ] * voxelSize.dimension( 1 ),
						ip.getL()[ 2 ] * voxelSize.dimension( 2 ) ) );

				points.add( ip.getL() );
			}

			// assemble the list of relative
			final List< RealPoint > listRelative = new ArrayList< RealPoint >();

			for ( final InterestPoint ip : iplRelative.getInterestPointsCopy().values() )
			{
				listRelative.add ( new RealPoint(
						ip.getL()[ 0 ] * voxelSize.dimension( 0 ),
						ip.getL()[ 1 ] * voxelSize.dimension( 1 ),
						ip.getL()[ 2 ] * voxelSize.dimension( 2 ) ) );
			}

			if ( list.size() < 1 || listRelative.size() < 1 )
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): "
						+ "Not enough interestpoints for " + Group.pvid( viewId ) );

				continue;
			}

			// make the KDTree
			final KDTree< RealPoint > tree = new KDTree< RealPoint >( listRelative, listRelative );

			// Nearest neighbor for each point, populate the new list
			final NearestNeighborSearchOnKDTree< RealPoint > nn = new NearestNeighborSearchOnKDTree< RealPoint >( tree );
			final InterestPoints newIpl = InterestPoints.newInstance( oldIpl.getBasePath(), viewId, rtop.getNewLabel() );
			/*final InterestPointList newIpl = new InterestPointList(
					oldIpl.getBaseDir(),
					new File(
							oldIpl.getFile().getParentFile(),
							"tpId_" + viewId.getTimePointId() + "_viewSetupId_" + viewId.getViewSetupId() + "." + rtop.getNewLabel() ) );*/

			final ArrayList< InterestPoint > newIPs = new ArrayList<>();

			int id = 0;
			for ( int j = 0; j < list.size(); ++j )
			{
				final RealPoint p = list.get( j );
				nn.search( p );
				
				// first nearest neighbor is the point itself, we need the second nearest
				final double d = nn.getDistance();
				
				if ( ( keepRange && d >= minDistance && d <= maxDistance ) || ( !keepRange && ( d < minDistance || d > maxDistance ) ) )
					newIPs.add( new InterestPoint( id++, points.get( j ).clone() ) );
			}

			newIpl.setInterestPoints( newIPs );
			newIpl.setCorrespondingInterestPoints( new ArrayList<>() );

			if ( keepRange )
				newIpl.setParameters( "thinned-out '" + rtop.getLabel() + "', kept range from " + minDistance + " to " + maxDistance );
			else
				newIpl.setParameters( "thinned-out '" + rtop.getLabel() + "', removed range from " + minDistance + " to " + maxDistance );

			vipl.addInterestPointList( rtop.getNewLabel(), newIpl );

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": TP=" + vd.getTimePointId() + " ViewSetup=" + vd.getViewSetupId() + 
					", Detections: " + oldIpl.getInterestPointsCopy().size() + " >>> " + newIpl.getInterestPointsCopy().size() );
		}

			return true;
	}
}
