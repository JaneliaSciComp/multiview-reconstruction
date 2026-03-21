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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.loadcorrespondences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;

public class LoadCorrespondencesPairwise< I extends InterestPoint > implements MatcherPairwise< I >
{
	final SpimData2 spimData;
	final int minNumMatches;

	public LoadCorrespondencesPairwise( final SpimData2 spimData, final int minNumMatches )
	{
		this.spimData = spimData;
		this.minNumMatches = minNumMatches;
	}

	// I is either InterestPoint or GroupedInterestPoint<ViewId>
	@Override
	public <V> PairwiseResult<I> match(
			final Collection<I> listA,
			final Collection<I> listB,
			final V viewsA,
			final V viewsB,
			final String labelA,
			final String labelB )
	{
		final PairwiseResult< I > result = new PairwiseResult< I >( false );

		if ( listA.size() < Math.max( 1, minNumMatches) || listB.size() < Math.max( 1, minNumMatches)  )
		{
			result.setResult( System.currentTimeMillis(), "Not enough detections to load corresponding interest points." );
			result.setCandidates( new ArrayList< PointMatchGeneric< I > >() );
			result.setInliers( new ArrayList< PointMatchGeneric< I > >(), Double.NaN );
			return result;
		}

		final Map<ViewId, ViewInterestPointLists> lists = spimData.getViewInterestPoints().getViewInterestPoints();

		if ( Group.class.isInstance( viewsA ) || Group.class.isInstance( viewsB ) ||
			 GroupedInterestPoint.class.isInstance( listA.iterator().next() ) || GroupedInterestPoint.class.isInstance( listB.iterator().next() ) )
		{
			throw new RuntimeException( "Grouped views not yet supported for loading correspondences.");
		}
		else
		{
			final ViewInterestPointLists iplA = lists.get( viewsA );
			final InterestPoints ipA = iplA.getInterestPointList( labelA );

			// note: we could use loaded points here, but we do not want to in case they got filtered somehow (e.g. overlapping only)
			final Map<Integer, I> mapA =
					listA.stream().collect( Collectors.toMap(
							ip -> ip.getId(),
							ip -> ip ) );

			final Map<Integer, I> mapB =
					listB.stream().collect( Collectors.toMap(
							ip -> ip.getId(),
							ip -> ip ) );

			final Collection<CorrespondingInterestPoints> corrA =
					ipA.getCorrespondingInterestPointsCopy();

			System.out.println( "Loaded " + corrA.size() + " corresponding interest points.");

			final List<CorrespondingInterestPoints> corrAFiltered = corrA.stream().filter( c ->
				c.getCorrespodingLabel().equals( labelB ) &&
				c.getCorrespondingViewId().equals( viewsB ) &&
				mapA.containsKey( c.getDetectionId() ) &&
				mapB.containsKey( c.getCorrespondingDetectionId() )
			).collect( Collectors.toList() );

			System.out.println( "After filtering " + corrAFiltered.size() + " corresponding interest points remain.");

			if ( corrAFiltered.size() < minNumMatches )
			{
				result.setResult( System.currentTimeMillis(), "Not enough corresponding interest points ("+corrAFiltered.size() +") were loaded." );
				result.setCandidates( new ArrayList< PointMatchGeneric< I > >() );
				result.setInliers( new ArrayList< PointMatchGeneric< I > >(), Double.NaN );
				return result;
			}

			final List< PointMatchGeneric< I > > inliers = corrAFiltered.stream().map( c ->
				new PointMatchGeneric<>(
					mapA.get( c.getDetectionId() ),
					mapB.get( c.getCorrespondingDetectionId() ) ) ).collect( Collectors.toList() );

			result.setCandidates( inliers );
			result.setInliers( inliers, 0.0 );

			result.setResult( System.currentTimeMillis(), "Loaded " + inliers.size() + " corresponding interest points." );

			return result;
		}
	}

	@Override
	public boolean requiresInterestPointDuplication() { return false; }
}
