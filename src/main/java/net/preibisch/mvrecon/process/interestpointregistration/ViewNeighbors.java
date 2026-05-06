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
package net.preibisch.mvrecon.process.interestpointregistration;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;

/**
 * Reusable per-view neighbour queries:
 * <ul>
 *   <li>{@link #connectedViews(SpimData2, Collection, Map)} — interest-point correspondence neighbours</li>
 *   <li>{@link #overlappingViews(SpimData2, Collection)} — geometric (bounding-box) neighbours</li>
 * </ul>
 *
 * Both restrict their results to the {@code universe} set passed in (typically the views the
 * caller is already analysing). "Connected" follows the same same-label / canonical-pair
 * semantics as {@code AnalyzeErrorsUtil.getErrors} but skips transforms and distance — it's
 * just the connection graph.
 */
public class ViewNeighbors
{
	private ViewNeighbors() {}

	/**
	 * For each view in {@code universe}, return the set of other views in {@code universe}
	 * that share at least one same-label correspondence with it. Symmetric.
	 */
	public static Map< ViewId, Set< ViewId > > connectedViews(
			final SpimData2 data,
			final Collection< ? extends ViewId > universe,
			final Map< String, Double > labelAndWeights )
	{
		final Set< ViewId > selected = new HashSet<>( universe );
		final ConcurrentHashMap< ViewId, Set< ViewId > > out = new ConcurrentHashMap<>();
		for ( final ViewId v : selected )
			out.put( v, ConcurrentHashMap.newKeySet() );

		selected.parallelStream().forEach( viewA -> {
			final ViewInterestPointLists vip = data.getViewInterestPoints().getViewInterestPointLists( viewA );
			if ( vip == null )
				return;
			for ( final String label : labelAndWeights.keySet() )
			{
				if ( vip.getInterestPointList( label ) == null )
					continue;
				final Collection< CorrespondingInterestPoints > corrs =
						vip.getInterestPointList( label ).getCorrespondingInterestPointsCopy();
				for ( final CorrespondingInterestPoints c : corrs )
				{
					if ( !c.getCorrespodingLabel().equals( label ) )
						continue;
					final ViewId viewB = c.getCorrespondingViewId();
					if ( !selected.contains( viewB ) )
						continue;
					out.get( viewA ).add( viewB );
				}
			}
		} );

		// freeze
		final HashMap< ViewId, Set< ViewId > > frozen = new HashMap<>();
		for ( final Map.Entry< ViewId, Set< ViewId > > e : out.entrySet() )
			frozen.put( e.getKey(), Collections.unmodifiableSet( new HashSet<>( e.getValue() ) ) );
		return frozen;
	}

	/** Convenience: connected set for a single view (returns empty set if {@code v} not in universe). */
	public static Set< ViewId > connectedTo(
			final ViewId v,
			final SpimData2 data,
			final Collection< ? extends ViewId > universe,
			final Map< String, Double > labelAndWeights )
	{
		final Set< ViewId > all = connectedViews( data, universe, labelAndWeights ).get( v );
		return all == null ? Collections.emptySet() : all;
	}

	/**
	 * For each view in {@code universe}, return the set of other views in {@code universe}
	 * whose registered bounding-box overlaps it. Symmetric.
	 */
	public static Map< ViewId, Set< ViewId > > overlappingViews(
			final SpimData2 data,
			final Collection< ? extends ViewId > universe )
	{
		final HashSet< ViewId > universeSet = new HashSet<>( universe );
		final HashMap< ViewId, Set< ViewId > > out = new HashMap<>();
		for ( final ViewId v : universeSet )
			out.put( v, new HashSet<>() );

		final SimpleBoundingBoxOverlap< ViewId > detector = new SimpleBoundingBoxOverlap<>(
				data.getSequenceDescription().getViewSetups(),
				data.getViewRegistrations().getViewRegistrations() );

		// Use generateOverlappingPairs over the universe (no groups → all-pair check restricted to universe).
		final java.util.List< ViewId > vidList = new java.util.ArrayList<>( universeSet );
		final java.util.List< Pair< ViewId, ViewId > > pairs =
				detector.generateOverlappingPairs( vidList, Collections.emptyList() );
		for ( final Pair< ViewId, ViewId > p : pairs )
		{
			out.get( p.getA() ).add( p.getB() );
			out.get( p.getB() ).add( p.getA() );
		}

		final HashMap< ViewId, Set< ViewId > > frozen = new HashMap<>();
		for ( final Map.Entry< ViewId, Set< ViewId > > e : out.entrySet() )
			frozen.put( e.getKey(), Collections.unmodifiableSet( e.getValue() ) );
		return frozen;
	}

	/** Convenience: overlap set for a single view. */
	public static Set< ViewId > overlappingWith(
			final ViewId v,
			final SpimData2 data,
			final Collection< ? extends ViewId > universe )
	{
		final Set< ViewId > all = overlappingViews( data, universe ).get( v );
		return all == null ? Collections.emptySet() : all;
	}
}
