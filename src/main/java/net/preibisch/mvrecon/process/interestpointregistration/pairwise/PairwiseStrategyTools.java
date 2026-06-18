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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.range.AllInRange;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.range.RangeComparator;

public class PairwiseStrategyTools
{
	public static < V > List< Pair< V, V > > allToAllRange(
			final List< ? extends V > views,
			final Collection< V > fixed,
			final Collection< ? extends Collection< V > > groups,
			final RangeComparator< V > rangeComparator )
	{
		// all pairs that need to be compared
		final ArrayList< Pair< V, V > > viewPairs = new ArrayList< Pair< V, V >>();

		for ( int a = 0; a < views.size() - 1; ++a )
			for ( int b = a + 1; b < views.size(); ++b )
			{
				final V viewIdA = views.get( a );
				final V viewIdB = views.get( b );

				// only compare those to views if not both are fixed and not
				// part of the same group
				if ( validPair( viewIdA, viewIdB, fixed, groups ) && rangeComparator.inRange( viewIdA, viewIdB ) )
					viewPairs.add( new ValuePair< V, V >( viewIdA, viewIdB ) );
			}

		return viewPairs;
	}

	public static < V > List< Pair< V, V > > allToAll(
			final List< ? extends V > views, final Collection< V > fixed,
			final Collection< ? extends Collection< V > > groups )
	{
		return allToAllRange( views, fixed, groups, new AllInRange< V >() );
	}

	public static < V > boolean validPair( final V viewIdA, final V viewIdB,
			final Collection< V > fixed,
			final Collection< ? extends Collection< V >> groups )
	{
		if ( fixed.contains( viewIdA ) && fixed.contains( viewIdB ) )
			return false;

		if ( oneSetContainsBoth( viewIdA, viewIdB, groups ) )
			return false;

		return true;
	}

	public static < V > boolean oneSetContainsBoth( final V viewIdA,
			final V viewIdB, final Collection< ? extends Collection< V >> sets )
	{
		for (final Collection< V > set : sets)
			if ( set.contains( viewIdA ) && set.contains( viewIdB ) )
				return true;

		return false;
	}
}
