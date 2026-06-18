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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.KDTree;
import net.imglib2.neighborsearch.RadiusNeighborSearch;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

/**
 * The simplest way to group interest points, just add them all (this will create problems in overlaps)
 * 
 * @author spreibi
 */
public class InterestPointGroupingMinDistance< V extends ViewId > extends InterestPointGrouping< V >
{
	public static double DEFAULT_RADIUS = 2.5;

	final double radius;

	public InterestPointGroupingMinDistance( final double radius, final Map< V, HashMap< String, Collection< InterestPoint > > > interestpoints )
	{
		super( interestpoints );

		this.radius = radius;
	}

	public InterestPointGroupingMinDistance( final Map< V, HashMap< String, Collection< InterestPoint > > > interestpoints )
	{
		this( DEFAULT_RADIUS, interestpoints );
	}

	public double getRadius() { return radius; }

	@Override
	protected HashMap< String, Collection< GroupedInterestPoint< V > > > merge( final Map< V, HashMap< String, Collection< InterestPoint > > > toMerge )
	{
		final HashMap< String, Collection< GroupedInterestPoint< V > > > groupedLists = new HashMap<>();

		InterestPointGroupingAll.mergeAll( toMerge ).forEach( ( label, groupedCollection ) ->
		{
			// nothing to do if there is no or one point in there
			if ( groupedCollection.size() > 1 )
			{
				final ArrayList< GroupedInterestPoint< V > > groupedList = new ArrayList<>( groupedCollection );

				// pseudo-random shuffling to not give an advantage due to the order the views are in
				Collections.shuffle( groupedList, new Random( 234 ) );

				//
				// make a list and a tree at the same time, use the tree to mark points in close proximity as false
				//

				// if a certain interestpoint is still valid
				// (grouped and markedPoints MUST be in the same order for this to work)
				final ArrayList< Pair< GroupedInterestPoint< V >, Bool > > markedPoints = new ArrayList<>();

				// all points are true initially, and will be set false if they were within the radius of a selected point
				for ( final GroupedInterestPoint< V > p : groupedList )
					markedPoints.add( new ValuePair<>( p, new Bool( true ) ) );

				final KDTree< Pair< GroupedInterestPoint< V >, Bool > > tree = new KDTree<>( markedPoints, groupedList );
				final RadiusNeighborSearch< Pair< GroupedInterestPoint< V >, Bool > > search =
						new RadiusNeighborSearchOnKDTree<>( tree );

				// go over all points
				for ( final Pair< GroupedInterestPoint< V >, Bool > p : markedPoints )
				{
					if ( p.getB().state )
					{
						// radius neighbor search
						search.search( p.getA(), radius, false );

						// make sure by comparing ViewId and Id that it is not the one we currently look at
						for ( int i = 0; i < search.numNeighbors(); ++i )
						{
							final Pair< GroupedInterestPoint< V >, Bool > neighbor = search.getSampler( i ).get();
							final GroupedInterestPoint< V > neighborpoint = neighbor.getA();

							if ( !neighborpoint.getV().equals( p.getA().getV() )) // do not set false if it is from the same view
								if ( !neighborpoint.equals( p.getA() ) ) // do not set false if it is the point we searched for
									neighbor.getB().state = false;
						}
					}
				}

				groupedList.clear();

				for ( final Pair< GroupedInterestPoint< V >, Bool > p : markedPoints )
					if ( p.getB().state )
						groupedList.add( p.getA() );

				groupedLists.put( label, groupedList );
			}
			else
			{
				groupedLists.put( label, groupedCollection );
			}
		} );

		return groupedLists;
	}
}
