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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalRealInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class SimpleBoundingBoxOverlap< V extends ViewId > implements OverlapDetection< V >
{
	final Map< ViewId, ViewRegistration > vrs;
	final Map< Integer, ? extends BasicViewSetup > vss;

	public SimpleBoundingBoxOverlap( final AbstractSpimData< ? extends AbstractSequenceDescription< ? extends BasicViewSetup, ?, ? > > spimData )
	{
		this.vss = spimData.getSequenceDescription().getViewSetups();
		this.vrs = spimData.getViewRegistrations().getViewRegistrations();
	}

	public SimpleBoundingBoxOverlap( final SequenceDescription sd, final ViewRegistrations vrs )
	{
		this.vss = sd.getViewSetups();
		this.vrs = vrs.getViewRegistrations();
	}

	public SimpleBoundingBoxOverlap( final Map< Integer, ? extends BasicViewSetup > viewSetups, final Map< ViewId, ViewRegistration >  vrs )
	{
		this.vss = viewSetups;
		this.vrs = vrs;
	}

	@Override
	public boolean overlaps( final V view1, final V view2 )
	{
		final BoundingBox bb1 = getBoundingBox( vss.get( view1.getViewSetupId() ), vrs.get( view1 ) );
		final BoundingBox bb2 = getBoundingBox( vss.get( view2.getViewSetupId() ), vrs.get( view2 ) );

		if ( bb1 == null )
			throw new RuntimeException( "view1 has no image size" );

		if ( bb2 == null )
			throw new RuntimeException( "view2 has no image size" );

		return overlaps( bb1, bb2 );
	}

	@Override
	public RealInterval getOverlapInterval( final V view1, final V view2 )
	{
		final RealInterval bb1 = getBoundingBoxReal( vss.get( view1.getViewSetupId() ), vrs.get( view1 ) );
		final RealInterval bb2 = getBoundingBoxReal( vss.get( view2.getViewSetupId() ), vrs.get( view2 ) );

		if ( bb1 == null )
			throw new RuntimeException( "view1 has no image size" );

		if ( bb2 == null )
			throw new RuntimeException( "view2 has no image size" );

		double[] min = new double[ bb1.numDimensions() ];
		double[] max = new double[ bb1.numDimensions() ];

		if ( overlaps( getBoundingBox( vss.get( view1.getViewSetupId() ), vrs.get( view1 ) ), getBoundingBox( vss.get( view2.getViewSetupId() ), vrs.get( view2 ) ) ) )
		{
			for ( int d = 0; d < bb1.numDimensions(); ++d )
			{
				min[ d ] = Math.max( bb1.realMin( d ), bb2.realMin( d ) );
				max[ d ] = Math.min( bb1.realMax( d ), bb2.realMax( d ) );

				// is 2d?
				if ( min[ d ] == max[ d ] && d == 2 && min[ d ] == 0 && vss.get( view1.getViewSetupId() ).getSize().dimension( 2 ) == 1 && vss.get( view2.getViewSetupId() ).getSize().dimension( 2 ) == 1)
				{
					min[ d ] = 0.0;
					max[ d ] = 1.0;
				}
				else if ( min[ d ] == max[ d ] || max[ d ] < min[ d ] )
				{
					return null;
				}
			}

			return new FinalRealInterval( min, max );
		}
		else
		{
			return null;
		}
	}

	public static boolean overlaps( final BoundingBox bb1, final BoundingBox bb2 )
	{
		for ( int d = 0; d < bb1.numDimensions(); ++d )
		{
			if (
				bb1.getMin()[ d ] < bb2.getMin()[ d ] && bb1.getMax()[ d ] < bb2.getMin()[ d ] ||
				bb1.getMin()[ d ] > bb2.getMax()[ d ] && bb1.getMax()[ d ] > bb2.getMax()[ d ] )
			{
				return false;
			}
		}

		return true;
	}

	public static < V extends ViewId > BoundingBox getBoundingBox(
			final V view,
			final Map< Integer, ? extends BasicViewSetup > vss,
			final ViewRegistrations vrs )
	{
		return getBoundingBox( vss.get( view.getViewSetupId() ), vrs.getViewRegistration( view ) );
	}

	public static < V extends ViewId > RealInterval getBoundingBoxReal(
			final V view,
			final Map< Integer, ? extends BasicViewSetup > vss,
			final ViewRegistrations vrs )
	{
		return getBoundingBoxReal( vss.get( view.getViewSetupId() ), vrs.getViewRegistration( view ) );
	}

	public static BoundingBox getBoundingBox(
			final BasicViewSetup vs,
			final ViewRegistration vr )
	{
		if ( !vs.hasSize() )
			return null;

		vr.updateModel();

		return getBoundingBox( vs.getSize(), vr.getModel() );
	}

	public static BoundingBox getBoundingBox( final Dimensions dims, final AffineTransform3D transform )
	{
		final RealInterval interval = getBoundingBoxReal( dims, transform );

		final int[] minInt = new int[ 3 ];
		final int[] maxInt = new int[ 3 ];

		for ( int d = 0; d < dims.numDimensions(); ++d )
		{
			minInt[ d ] = (int)Math.round( interval.realMin( d ) ) - 1;
			maxInt[ d ] = (int)Math.round( interval.realMax( d ) ) + 1;
		}

		return new BoundingBox( minInt, maxInt );
	}

	public static RealInterval getBoundingBoxReal(
			final BasicViewSetup vs,
			final ViewRegistration vr )
	{
		if ( !vs.hasSize() )
			return null;

		vr.updateModel();

		return getBoundingBoxReal( vs.getSize(), vr.getModel() );
	}

	public static RealInterval getBoundingBoxReal( final Dimensions dims, final AffineTransform3D transform )
	{
		final double[] min = new double[]{ 0, 0, 0 };
		final double[] max = new double[]{
				dims.dimension( 0 ) - 1,
				dims.dimension( 1 ) - 1,
				dims.dimension( 2 ) - 1 };

		return transform.estimateBounds( new FinalRealInterval( min, max ) );
	}

	// ==================== OPTIMIZED PARALLEL OVERLAP DETECTION ====================

	/**
	 * Pre-compute all bounding boxes for a collection of views in parallel.
	 * This is much faster than computing bounding boxes on-demand for each pair.
	 *
	 * @param views - the views to compute bounding boxes for
	 * @return a Map from ViewId to its BoundingBox
	 */
	public Map<V, BoundingBox> precomputeBoundingBoxes(final Collection<V> views)
	{
		final long start = System.currentTimeMillis();

		final ForkJoinPool pool = new ForkJoinPool(Threads.numThreads());
		try
		{
			final Map<V, BoundingBox> result = pool.submit(() ->
				views.parallelStream().collect(Collectors.toConcurrentMap(
					view -> view,
					view -> {
						final BasicViewSetup vs = vss.get(view.getViewSetupId());
						final ViewRegistration vr = vrs.get(view);
						return getBoundingBox(vs, vr);
					}
				))
			).get();

			System.out.println("[TIMING] precomputeBoundingBoxes(): " + (System.currentTimeMillis() - start) + " ms (" + views.size() + " views)");
			return result;
		}
		catch (final Exception e)
		{
			throw new RuntimeException("Failed to precompute bounding boxes", e);
		}
		finally
		{
			pool.shutdown();
		}
	}

	/**
	 * Filter overlapping pairs using pre-computed bounding boxes in parallel.
	 * This removes non-overlapping pairs from the list and returns the removed pairs.
	 *
	 * @param pairs - the pairs to check (will be modified to contain only overlapping pairs)
	 * @param boundingBoxes - pre-computed bounding boxes for all views
	 * @return the list of removed (non-overlapping) pairs
	 */
	public ArrayList<Pair<V, V>> removeNonOverlappingPairsParallel(
			final List<Pair<V, V>> pairs,
			final Map<V, BoundingBox> boundingBoxes)
	{
		final long start = System.currentTimeMillis();
		final int originalSize = pairs.size();

		final ForkJoinPool pool = new ForkJoinPool(Threads.numThreads());
		try
		{
			// Parallel filter: identify overlapping pairs
			final List<Pair<V, V>> overlappingPairs = pool.submit(() ->
				pairs.parallelStream()
					.filter(pair -> {
						final BoundingBox bb1 = boundingBoxes.get(pair.getA());
						final BoundingBox bb2 = boundingBoxes.get(pair.getB());
						return bb1 != null && bb2 != null && overlaps(bb1, bb2);
					})
					.collect(Collectors.toList())
			).get();

			// Compute removed pairs (the non-overlapping ones)
			final ArrayList<Pair<V, V>> removed = new ArrayList<>(originalSize - overlappingPairs.size());

			// Clear and replace with overlapping pairs
			// Use a set for O(1) lookup
			final java.util.Set<Pair<V, V>> overlappingSet = new java.util.HashSet<>(overlappingPairs);
			for (final Pair<V, V> pair : pairs)
			{
				if (!overlappingSet.contains(pair))
					removed.add(pair);
			}

			pairs.clear();
			pairs.addAll(overlappingPairs);

			System.out.println("[TIMING] removeNonOverlappingPairsParallel(): " + (System.currentTimeMillis() - start) +
					" ms (checked " + originalSize + " pairs, removed " + removed.size() + ", kept " + pairs.size() + ")");

			return removed;
		}
		catch (final Exception e)
		{
			throw new RuntimeException("Failed to filter overlapping pairs", e);
		}
		finally
		{
			pool.shutdown();
		}
	}

	/**
	 * Generate overlapping pairs directly without creating all n^2 pairs first.
	 * This is MUCH faster for large datasets where most pairs don't overlap.
	 *
	 * @param views - list of views to generate pairs from
	 * @param groups - groups (views in same group are skipped)
	 * @return list of overlapping pairs only
	 */
	public List<Pair<V, V>> generateOverlappingPairs(
			final List<V> views,
			final Collection<? extends Group<V>> groups)
	{
		final int n = views.size();
		if (n < 2) return new ArrayList<>();

		final long totalStart = System.currentTimeMillis();

		// Step 1: Pre-compute all bounding boxes
		final Map<V, BoundingBox> boundingBoxes = precomputeBoundingBoxes(views);

		// Step 2: Pre-compute group membership for O(1) lookup
		final Map<V, Set<Integer>> viewToGroups = new ConcurrentHashMap<>();
		int groupIdx = 0;
		for (final Group<V> group : groups)
		{
			final int idx = groupIdx++;
			for (final V view : group.getViews())
			{
				viewToGroups.computeIfAbsent(view, k -> new java.util.HashSet<>()).add(idx);
			}
		}
		final Set<Integer> emptySet = Collections.emptySet();

		// Step 3: Generate only overlapping pairs in parallel
		final long pairStart = System.currentTimeMillis();
		final ForkJoinPool pool = new ForkJoinPool(Threads.numThreads());
		try
		{
			final List<Pair<V, V>> overlappingPairs = pool.submit(() ->
				IntStream.range(0, n - 1).parallel()
					.boxed()
					.<Pair<V, V>>flatMap(a -> {
						final V viewIdA = views.get(a);
						final BoundingBox bbA = boundingBoxes.get(viewIdA);
						if (bbA == null) return java.util.stream.Stream.empty();

						final Set<Integer> groupsA = viewToGroups.getOrDefault(viewIdA, emptySet);

						return IntStream.range(a + 1, n)
							.<Pair<V, V>>mapToObj(b -> {
								final V viewIdB = views.get(b);

								// Check if both in same group
								if (!groupsA.isEmpty())
								{
									final Set<Integer> groupsB = viewToGroups.getOrDefault(viewIdB, emptySet);
									for (final Integer g : groupsA)
									{
										if (groupsB.contains(g))
											return null;
									}
								}

								// Check bounding box overlap
								final BoundingBox bbB = boundingBoxes.get(viewIdB);
								if (bbB == null || !overlaps(bbA, bbB))
									return null;

								return new ValuePair<>(viewIdA, viewIdB);
							})
							.filter(p -> p != null);
					})
					.collect(Collectors.toList())
			).get();

			System.out.println("[TIMING] generateOverlappingPairs(): " + (System.currentTimeMillis() - totalStart) +
					" ms total (" + n + " views, " + overlappingPairs.size() + " overlapping pairs, pair generation: " +
					(System.currentTimeMillis() - pairStart) + " ms)");

			return overlappingPairs;
		}
		catch (final Exception e)
		{
			throw new RuntimeException("Failed to generate overlapping pairs", e);
		}
		finally
		{
			pool.shutdown();
		}
	}

	/**
	 * Combined method: pre-compute bounding boxes and filter pairs in parallel.
	 * This is the most efficient approach for large datasets.
	 *
	 * @param pairs - the pairs to check (will be modified to contain only overlapping pairs)
	 * @param views - all views involved (used for pre-computing bounding boxes)
	 * @return the list of removed (non-overlapping) pairs
	 */
	public ArrayList<Pair<V, V>> removeNonOverlappingPairsOptimized(
			final List<Pair<V, V>> pairs,
			final Collection<V> views)
	{
		// Step 1: Pre-compute all bounding boxes in parallel
		final Map<V, BoundingBox> boundingBoxes = precomputeBoundingBoxes(views);

		// Step 2: Filter pairs in parallel using pre-computed bounding boxes
		return removeNonOverlappingPairsParallel(pairs, boundingBoxes);
	}
}
