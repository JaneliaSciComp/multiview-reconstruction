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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.range.ReferenceTimepointRange;

public class ReferenceTimepoint extends AllToAllRange< ViewId, ReferenceTimepointRange< ViewId > >
{
	public ReferenceTimepoint(
			final List< ViewId > views,
			final Set< Group< ViewId > > groups,
			final int referenceTimepointId )
	{
		super( views, groups, new ReferenceTimepointRange<>( referenceTimepointId ) );
	}

	public ReferenceTimepoint(
			final List< ViewId > views,
			final Set< Group< ViewId > > groups,
			final TimePoint referenceTimepoint )
	{
		this( views, groups, referenceTimepoint.getId() );
	}

	/**
	 * Given a list of pairs of views that need to be compared, find subsets that are not overlapping
	 */
	@Override
	public void detectSubsets()
	{
		final int referenceTimepointId = rangeComparator.getReferenceTimepointId();

		if ( groupsSpanTimepoints( groups ) || pairsSpanTimepoints( pairs, referenceTimepointId ) )
		{
			// if there are groups that span across multiple timepoints or pairs
			// that span accross different timepoints (except the reference), we 
			// cannot make subsets per timepoint as things are more interconnected
			super.detectSubsets();
		}
		else
		{
			// we can make one subset per timepoint since they are only connected
			// through the fixed reference timepoint (like the French rail system to Paris)
			this.subsets = new ArrayList<>();

			// make one set per timepoint, then look for more subsets in each of them
			for ( final int tpId : SpimData2.getAllTimePointsSortedWithoutPresenceCheck( views ) )
			{
				if ( tpId != referenceTimepointId )
				{
					// make local list of views, pairs, and groups
					final List< ViewId > viewsL = new ArrayList<>();
					final List< Pair< ViewId, ViewId > > pairsL = new ArrayList<>();
					final Set< Group< ViewId > > groupsL = new HashSet<>();

					for ( final ViewId viewId : this.views )
						if ( viewId.getTimePointId() == tpId || viewId.getTimePointId() == referenceTimepointId )
							viewsL.add( viewId );

					// we made sure the pairs do not span across timepoints except reference
					for ( final Pair< ViewId, ViewId > pair : this.pairs )
						if ( pair.getA().getTimePointId() == tpId || pair.getB().getTimePointId() == tpId )
							pairsL.add( pair );

					// we made sure the groups do not span across timepoints
					for ( final Group< ViewId > group : this.groups )
						if ( group.iterator().next().getTimePointId() == tpId )
							groupsL.add( group );

					// add all local subsets to the list of subsets
					this.subsets.addAll( detectSubsets( viewsL, pairsL, groupsL ) );
				}
			}
		}
	}

	@Override
	public ArrayList< ViewId > getDefaultFixedViews()
	{
		final ArrayList< ViewId > fixed = new ArrayList<>();

		for ( final ViewId viewId : views )
			if ( viewId.getTimePointId() == rangeComparator.getReferenceTimepointId() )
				fixed.add( viewId );

		return fixed;
	}

	/**
	 * Tests if pairs span over different timepoints (that are not the reference timepoint)
	 * 
	 * @param pairs list if view pairs
	 * @param referenceTimepointId reference timepoint id
	 * @param <V> view id type
	 * @return test result
	 */
	public static < V extends ViewId > boolean pairsSpanTimepoints( final List< Pair< V, V > > pairs, final int referenceTimepointId )
	{
		for ( final Pair< V, V > pair : pairs )
		{
			// if the timepoints are different and at the same time non of each is the reference timepoint, something is wrong
			if (
				pair.getA().getTimePointId() != pair.getB().getTimePointId() &&
				pair.getA().getTimePointId() != referenceTimepointId &&
				pair.getB().getTimePointId() != referenceTimepointId )
					return true;
		}

		return false;
	}

	/**
	 * Checks if groups exist than span multiple timepoints
	 * 
	 * @param groups the view groups
	 * @param <V> view id type
	 * @return test result
	 */
	public static < V extends ViewId > boolean groupsSpanTimepoints( final Set< Group< V > > groups )
	{
		for ( final Group< V > group : groups )
		{
			final int timepointId = group.iterator().next().getTimePointId();

			for ( final V viewId : group )
				if ( timepointId != viewId.getTimePointId() )
					return true;
		}
		
		return false;
	}

}
