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
import java.util.HashMap;
import java.util.Map;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

/**
 * The simplest way to group interest points, just add them all (this will create problems in overlaps)
 * 
 * @author spreibi
 */
public class InterestPointGroupingAll< V extends ViewId > extends InterestPointGrouping< V >
{
	public InterestPointGroupingAll( final Map< V, HashMap< String, Collection< InterestPoint > > > interestpoints )
	{
		super( interestpoints );
	}

	@Override
	protected HashMap< String, Collection< GroupedInterestPoint< V > > > merge( final Map< V, HashMap< String, Collection< InterestPoint > > > toMerge )
	{
		return mergeAll( toMerge );
	}

	public static < V extends ViewId > HashMap< String, Collection< GroupedInterestPoint< V > > > mergeAll( final Map< V, HashMap< String, Collection< InterestPoint > > > toMerge )
	{
		final HashMap< String, Collection< GroupedInterestPoint< V > > > groupedLists = new HashMap<>();

		for ( final V view : toMerge.keySet() )
		{
			toMerge.get( view ).forEach( (label, pointList) ->
			{
				final Collection<GroupedInterestPoint<V>> groupedList = groupedLists.computeIfAbsent( label, k -> new ArrayList<>() );

				for ( final InterestPoint p : pointList )
					groupedList.add( new GroupedInterestPoint< V >( view, p.getId(), p.getL() ) );
			});
		}

		return groupedLists;
	}
}
