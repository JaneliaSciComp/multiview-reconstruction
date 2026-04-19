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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.range.RangeComparator;

public class AllToAllRange< V extends Comparable< V >, R extends RangeComparator< V > > extends PairwiseSetup< V >
{
	final R rangeComparator;

	public AllToAllRange(
			final List< V > views,
			final Set< Group< V > > groups,
			final R rangeComparator )
	{
		super( views, groups );
		this.rangeComparator = rangeComparator;
	}

	@Override
	protected List< Pair< V, V > > definePairsAbstract()
	{
		return allPairs( views, groups, rangeComparator );
	}

	@Override
	public List< V > getDefaultFixedViews() { return new ArrayList<>(); }

	public static < V > List< Pair< V, V > > allPairs(
			final List< ? extends V > views,
			final Collection< ? extends Group< V > > groups,
			final RangeComparator< V > rangeComparator )
	{
		final int n = views.size();

		// For small number of views, use sequential version
		if (n < 100)
		{
			return allPairsSequential(views, groups, rangeComparator);
		}

		// For large datasets, use parallel version
		final long start = System.currentTimeMillis();

		// Pre-compute group membership for O(1) lookup: view -> set of group indices
		final Map<V, Set<Integer>> viewToGroups = new ConcurrentHashMap<>();
		int groupIdx = 0;
		for (final Group<V> group : groups)
		{
			final int idx = groupIdx++;
			for (final V view : group.getViews())
			{
				viewToGroups.computeIfAbsent(view, k -> new HashSet<>()).add(idx);
			}
		}

		final Set<Integer> emptySet = Collections.emptySet();
		final ForkJoinPool pool = new ForkJoinPool(Threads.numThreads());
		try
		{
			// Parallel pair generation: each thread handles a range of 'a' indices
			final List<Pair<V, V>> viewPairs = pool.submit(() ->
				IntStream.range(0, n - 1).parallel()
					.boxed()
					.<Pair<V, V>>flatMap(a -> {
						final V viewIdA = views.get(a);
						final Set<Integer> groupsA = viewToGroups.getOrDefault(viewIdA, emptySet);

						return IntStream.range(a + 1, n)
							.<Pair<V, V>>mapToObj(b -> {
								final V viewIdB = views.get(b);

								// Check if in range
								if (!rangeComparator.inRange(viewIdA, viewIdB))
									return null;

								// Check if both in same group (O(1) with pre-computed sets)
								if (!groupsA.isEmpty())
								{
									final Set<Integer> groupsB = viewToGroups.getOrDefault(viewIdB, emptySet);
									for (final Integer g : groupsA)
									{
										if (groupsB.contains(g))
											return null; // Both in same group, skip
									}
								}

								return new ValuePair<>(viewIdA, viewIdB);
							})
							.filter(p -> p != null);
					})
					.collect(Collectors.toList())
			).get();

			System.out.println("[TIMING] allPairs() parallel: " + (System.currentTimeMillis() - start) +
					" ms (" + n + " views, " + viewPairs.size() + " pairs)");

			return viewPairs;
		}
		catch (final Exception e)
		{
			throw new RuntimeException("Failed to generate pairs in parallel", e);
		}
		finally
		{
			pool.shutdown();
		}
	}

	/**
	 * Sequential version for small datasets or fallback
	 */
	private static < V > List< Pair< V, V > > allPairsSequential(
			final List< ? extends V > views,
			final Collection< ? extends Group< V > > groups,
			final RangeComparator< V > rangeComparator )
	{
		final ArrayList< Pair< V, V > > viewPairs = new ArrayList< Pair< V, V >>();

		for ( int a = 0; a < views.size() - 1; ++a )
			for ( int b = a + 1; b < views.size(); ++b )
			{
				final V viewIdA = views.get( a );
				final V viewIdB = views.get( b );

				if ( !Group.containsBoth( viewIdA, viewIdB, groups ) && rangeComparator.inRange( viewIdA, viewIdB ) )
					viewPairs.add( new ValuePair< V, V >( viewIdA, viewIdB ) );
			}

		return viewPairs;
	}
}
