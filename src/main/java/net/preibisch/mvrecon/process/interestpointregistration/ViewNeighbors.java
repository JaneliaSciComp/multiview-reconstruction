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
package net.preibisch.mvrecon.process.interestpointregistration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.ParallelepipedOverlap;
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

	/** Run {@code task} on a fresh ForkJoinPool sized to {@link Threads#numThreads()} so any
	 *  {@code parallelStream()} inside uses that pool instead of the JVM-wide common one. */
	private static < T > T runInPool( final Callable< T > task )
	{
		final ForkJoinPool pool = new ForkJoinPool( Threads.numThreads() );
		try
		{
			return pool.submit( task ).get();
		}
		catch ( final InterruptedException | ExecutionException e )
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException( e );
		}
		finally
		{
			pool.shutdown();
		}
	}

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

		runInPool( () -> {
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
			return null;
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
	 * Per-click connectivity query. Returns the views in {@code universe \ actual}
	 * that share at least one same-label correspondence with any view in {@code actual}.
	 *
	 * Walks only the {@code actual} set's correspondence lists — much cheaper than
	 * {@link #connectedViews(SpimData2, Collection, Map)} which materialises the full
	 * universe-by-universe graph.
	 *
	 * Weight values in {@code labelAndWeights} are ignored (connectedness is binary);
	 * only the key set matters.
	 */
	public static Set< ViewId > connectedFor(
			final SpimData2 data,
			final Collection< ? extends ViewId > actual,
			final Collection< ? extends ViewId > universe,
			final Map< String, Double > labelAndWeights )
	{
		final HashSet< ViewId > actualSet = new HashSet<>( actual );
		if ( actualSet.isEmpty() )
			return Collections.emptySet();
		final HashSet< ViewId > universeSet = new HashSet<>( universe );
		final Set< String > labels = labelAndWeights.keySet();

		return runInPool( () -> actualSet.parallelStream()
				.flatMap( viewA -> {
					final ViewInterestPointLists vip =
							data.getViewInterestPoints().getViewInterestPointLists( viewA );
					if ( vip == null )
						return java.util.stream.Stream.empty();
					final HashSet< ViewId > hits = new HashSet<>();
					for ( final String label : labels )
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
							if ( actualSet.contains( viewB ) )
								continue;
							if ( !universeSet.contains( viewB ) )
								continue;
							hits.add( viewB );
						}
					}
					return hits.stream();
				} )
				.collect( Collectors.toSet() ) );
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

	/**
	 * Per-click overlap query. Returns the views in {@code universe \ actual} whose
	 * affine-transformed body intersects any view in {@code actual}.
	 *
	 * Two-stage pipeline:
	 * <ol>
	 *   <li>AABB broad phase — one union AABB over {@code actual}, then a parallel scan
	 *       over {@code universe} reusing {@link SimpleBoundingBoxOverlap#overlaps} to
	 *       prune.</li>
	 *   <li>SAT narrow phase — for each AABB-positive candidate, check 3-D SAT against
	 *       each view in {@code actual}; first hit wins.</li>
	 * </ol>
	 *
	 * Avoids the O(N²) full-graph precompute that
	 * {@link #overlappingViews(SpimData2, Collection)} performs. Suitable for interactive
	 * use over universes with hundreds of thousands of views.
	 */
	public static Set< ViewId > overlappingFor(
			final SpimData2 data,
			final Collection< ? extends ViewId > actual,
			final Collection< ? extends ViewId > universe )
	{
		final HashSet< ViewId > actualSet = new HashSet<>( actual );
		if ( actualSet.isEmpty() )
			return Collections.emptySet();

		final Map< Integer, ? extends BasicViewSetup > vss =
				data.getSequenceDescription().getViewSetups();
		final Map< ViewId, ViewRegistration > vrs =
				data.getViewRegistrations().getViewRegistrations();

		// Pre-compute corners + per-view bbox for the (small) actual set; build the union AABB.
		final Map< ViewId, double[][] > actualCorners = new HashMap<>();
		int[] unionMin = null;
		int[] unionMax = null;
		for ( final ViewId v : actualSet )
		{
			final BasicViewSetup vs = vss.get( v.getViewSetupId() );
			final ViewRegistration vr = vrs.get( v );
			if ( vs == null || vr == null || !vs.hasSize() )
				continue;
			final BoundingBox bb = SimpleBoundingBoxOverlap.getBoundingBox( vs, vr );
			actualCorners.put( v, ParallelepipedOverlap.corners( vs, vr ) );
			if ( unionMin == null )
			{
				unionMin = bb.getMin().clone();
				unionMax = bb.getMax().clone();
			}
			else
			{
				for ( int d = 0; d < bb.numDimensions(); ++d )
				{
					if ( bb.getMin()[ d ] < unionMin[ d ] ) unionMin[ d ] = bb.getMin()[ d ];
					if ( bb.getMax()[ d ] > unionMax[ d ] ) unionMax[ d ] = bb.getMax()[ d ];
				}
			}
		}
		if ( unionMin == null )
			return Collections.emptySet();
		final BoundingBox unionAABB = new BoundingBox( unionMin, unionMax );

		// Snapshot universe so parallelStream uses a stable order. Filter actual out up-front.
		final List< ViewId > candidates = new ArrayList<>();
		for ( final ViewId u : universe )
			if ( !actualSet.contains( u ) )
				candidates.add( u );

		return runInPool( () -> candidates.parallelStream()
				.filter( u -> {
					final BasicViewSetup vs = vss.get( u.getViewSetupId() );
					final ViewRegistration vr = vrs.get( u );
					if ( vs == null || vr == null || !vs.hasSize() )
						return false;
					final BoundingBox bbU = SimpleBoundingBoxOverlap.getBoundingBox( vs, vr );
					if ( !SimpleBoundingBoxOverlap.overlaps( unionAABB, bbU ) )
						return false;
					final double[][] cornersU = ParallelepipedOverlap.corners( vs, vr );
					for ( final double[][] cornersA : actualCorners.values() )
						if ( ParallelepipedOverlap.intersects( cornersA, cornersU ) )
							return true;
					return false;
				} )
				.collect( Collectors.toSet() ) );
	}
}
