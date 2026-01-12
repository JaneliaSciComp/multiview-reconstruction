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
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
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

								return new net.imglib2.util.ValuePair<>(viewIdA, viewIdB);
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

	/**
	 * Compute overlap between two views by sampling points and validating they fall
	 * within both views. Returns two local intervals - one for each view.
	 *
	 * This correctly handles rotations by validating pixel-by-pixel.
	 *
	 * @param dims1 Dimensions of view 1
	 * @param dims2 Dimensions of view 2
	 * @param transform1 Local-to-global transform for view 1
	 * @param transform2 Local-to-global transform for view 2
	 * @return Array of [localOverlap1, localOverlap2], or null if no overlap
	 */
	public static RealInterval[] getLocalOverlapsUsingPixelValidation(
			final Dimensions dims1,
			final Dimensions dims2,
			final AffineTransform3D transform1,
			final AffineTransform3D transform2 )
	{
		final AffineTransform3D invTransform1 = transform1.inverse();
		final AffineTransform3D invTransform2 = transform2.inverse();

		// Sample in view 1's local space and validate pixels are in view 2
		final RealInterval localOverlap1 = sampleLocalSpaceAndValidate(
				dims1, dims2, transform1, invTransform2 );

		// Sample in view 2's local space and validate pixels are in view 1
		final RealInterval localOverlap2 = sampleLocalSpaceAndValidate(
				dims2, dims1, transform2, invTransform1 );

		if ( localOverlap1 == null || localOverlap2 == null )
			return null;

		return new RealInterval[] { localOverlap1, localOverlap2 };
	}

	/**
	 * Sample pixels in a view's local space and validate they map to valid pixels in the other view.
	 * Builds bounding box directly in local space from valid pixels.
	 * Includes refinement to find tighter boundaries.
	 *
	 * @param dimsLocal Dimensions of this view
	 * @param dimsOther Dimensions of other view
	 * @param transformLocal Local-to-global transform for this view
	 * @param invTransformOther Global-to-local transform for other view
	 * @return Bounding box of valid pixels in local space, or null if none found
	 */
	private static RealInterval sampleLocalSpaceAndValidate(
			final Dimensions dimsLocal,
			final Dimensions dimsOther,
			final AffineTransform3D transformLocal,
			final AffineTransform3D invTransformOther )
	{
		final int n = dimsLocal.numDimensions();
		final double[] minValid = new double[ n ];
		final double[] maxValid = new double[ n ];

		// Store the points that generated the min/max values to use as seeds for refinement
		final double[][] minPoints = new double[ n ][ n ];
		final double[][] maxPoints = new double[ n ][ n ];

		// Initialize with invalid values
		for ( int d = 0; d < n; d++ )
		{
			minValid[ d ] = Double.MAX_VALUE;
			maxValid[ d ] = -Double.MAX_VALUE;
		}

		final int stride = 10;
		final double[] localPoint = new double[ n ];
		final double[] globalPoint = new double[ n ];
		final double[] otherLocalPoint = new double[ n ];

		boolean foundValid = false;

		// Sample in this view's local space
		for ( long z = 0; z < dimsLocal.dimension( 2 ); z += stride )
		{
			for ( long y = 0; y < dimsLocal.dimension( 1 ); y += stride )
			{
				for ( long x = 0; x < dimsLocal.dimension( 0 ); x += stride )
				{
					localPoint[ 0 ] = x;
					localPoint[ 1 ] = y;
					localPoint[ 2 ] = z;

					if ( isValid( localPoint, dimsOther, transformLocal, invTransformOther, globalPoint, otherLocalPoint ) )
					{
						foundValid = true;
						for ( int d = 0; d < n; d++ )
						{
							if ( localPoint[ d ] < minValid[ d ] )
							{
								minValid[ d ] = localPoint[ d ];
								System.arraycopy( localPoint, 0, minPoints[ d ], 0, n );
							}
							if ( localPoint[ d ] > maxValid[ d ] )
							{
								maxValid[ d ] = localPoint[ d ];
								System.arraycopy( localPoint, 0, maxPoints[ d ], 0, n );
							}
						}
					}
				}
			}
		}

		if ( !foundValid )
			return null;

		// Refine boundaries
		// For each dimension, search outwards from the min/max points found
		// First with step 1, then step 0.1
		refineBoundaries( minValid, maxValid, minPoints, maxPoints, dimsOther, transformLocal, invTransformOther, stride );

		return new FinalRealInterval( minValid, maxValid );
	}

	private static void refineBoundaries(
			final double[] minValid,
			final double[] maxValid,
			final double[][] minPoints,
			final double[][] maxPoints,
			final Dimensions dimsOther,
			final AffineTransform3D transformLocal,
			final AffineTransform3D invTransformOther,
			final int initialStride )
	{
		final int n = minValid.length;
		final double[] globalPoint = new double[ n ];
		final double[] otherLocalPoint = new double[ n ];
		final double[] testPoint = new double[ n ];

		// Refinement steps
		final double[] steps = { 1.0, 0.1 };

		for ( int d = 0; d < n; d++ )
		{
			// Refine Min
			System.arraycopy( minPoints[ d ], 0, testPoint, 0, n );
			// Search backwards from current min
			// We search up to 'initialStride' distance because that's the max error from coarse scan
			// But we do it in two passes (step 1, then step 0.1)

			double currentMin = minValid[ d ];
			double range = initialStride;

			for ( double step : steps )
			{
				boolean improved = false;
				// Search backwards
				for ( double val = currentMin - step; val >= currentMin - range; val -= step )
				{
					testPoint[ d ] = val;
					if ( isValid( testPoint, dimsOther, transformLocal, invTransformOther, globalPoint, otherLocalPoint ) )
					{
						minValid[ d ] = val;
						// Update the point to this new valid location for next pass
						// (though for 1D search on this dim, just keeping the coordinate is enough,
						// but keeping other coords fixed is correct behavior for local refinement)
						improved = true;
					}
					else
					{
						// Once we hit invalid, we stop this pass
						// The next finer pass will start from the last valid point (minValid[d])
						break;
					}
				}
				// For the next finer pass, we only need to search a small range around the new min
				// specifically, we just searched with 'step', so error is at most 'step'.
				// So set range = step for next pass.
				// Also update currentMin to the new best found.
				currentMin = minValid[ d ];
				range = step;
			}


			// Refine Max
			System.arraycopy( maxPoints[ d ], 0, testPoint, 0, n );
			double currentMax = maxValid[ d ];
			range = initialStride;

			for ( double step : steps )
			{
				boolean improved = false;
				// Search forwards
				for ( double val = currentMax + step; val <= currentMax + range; val += step )
				{
					testPoint[ d ] = val;
					if ( isValid( testPoint, dimsOther, transformLocal, invTransformOther, globalPoint, otherLocalPoint ) )
					{
						maxValid[ d ] = val;
						improved = true;
					}
					else
					{
						break;
					}
				}
				currentMax = maxValid[ d ];
				range = step;
			}
		}
	}

	private static boolean isValid(
			final double[] localPoint,
			final Dimensions dimsOther,
			final AffineTransform3D transformLocal,
			final AffineTransform3D invTransformOther,
			final double[] globalPoint, // temp buffer
			final double[] otherLocalPoint // temp buffer
	)
	{
		// Transform to global, then to other view's local space
		transformLocal.apply( localPoint, globalPoint );
		invTransformOther.apply( globalPoint, otherLocalPoint );

		// Check if it falls within other view's valid bounds
		for ( int d = 0; d < localPoint.length; d++ )
		{
			if ( otherLocalPoint[ d ] < 0 || otherLocalPoint[ d ] >= dimsOther.dimension( d ) )
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Sample points in global overlap region and validate they map to valid pixels in both views.
	 * Returns refined global overlap bounding box containing only valid pixels.
	 *
	 * @param globalOverlap Candidate global overlap region
	 * @param dims1 Dimensions of view 1
	 * @param dims2 Dimensions of view 2
	 * @param invTransform1 Global-to-local transform for view 1
	 * @param invTransform2 Global-to-local transform for view 2
	 * @return Refined global overlap, or null if no valid pixels
	 */
	private static RealInterval sampleAndRefineGlobalOverlap(
			final RealInterval globalOverlap,
			final Dimensions dims1,
			final Dimensions dims2,
			final AffineTransform3D invTransform1,
			final AffineTransform3D invTransform2 )
	{
		final int n = globalOverlap.numDimensions();
		final double[] minValid = new double[ n ];
		final double[] maxValid = new double[ n ];

		// Initialize with invalid values
		for ( int d = 0; d < n; d++ )
		{
			minValid[ d ] = Double.MAX_VALUE;
			maxValid[ d ] = -Double.MAX_VALUE;
		}

		// Sample stride
		final int stride = 10;

		// Get sampling bounds in global space
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];
		for ( int d = 0; d < n; d++ )
		{
			min[ d ] = (long) Math.ceil( globalOverlap.realMin( d ) );
			max[ d ] = (long) Math.floor( globalOverlap.realMax( d ) );
		}

		// Sample and validate points
		final double[] globalPoint = new double[ n ];
		final double[] local1Point = new double[ n ];
		final double[] local2Point = new double[ n ];

		boolean foundValid = false;

		// Sample in 3D grid in global space
		for ( long z = min[ 2 ]; z <= max[ 2 ]; z += stride )
		{
			for ( long y = min[ 1 ]; y <= max[ 1 ]; y += stride )
			{
				for ( long x = min[ 0 ]; x <= max[ 0 ]; x += stride )
				{
					globalPoint[ 0 ] = x;
					globalPoint[ 1 ] = y;
					globalPoint[ 2 ] = z;

					// Transform to both views' local spaces
					invTransform1.apply( globalPoint, local1Point );
					invTransform2.apply( globalPoint, local2Point );

					// Check if it falls within both views' valid bounds
					boolean validInView1 = true;
					boolean validInView2 = true;
					for ( int d = 0; d < n; d++ )
					{
						if ( local1Point[ d ] < 0 || local1Point[ d ] >= dims1.dimension( d ) )
							validInView1 = false;
						if ( local2Point[ d ] < 0 || local2Point[ d ] >= dims2.dimension( d ) )
							validInView2 = false;
					}

					if ( validInView1 && validInView2 )
					{
						foundValid = true;
						for ( int d = 0; d < n; d++ )
						{
							minValid[ d ] = Math.min( minValid[ d ], globalPoint[ d ] );
							maxValid[ d ] = Math.max( maxValid[ d ], globalPoint[ d ] );
						}
					}
				}
			}
		}

		if ( !foundValid )
			return null;

		return new FinalRealInterval( minValid, maxValid );
	}

	/**
	 * Compute overlap between two views by transforming each view's bounding box corners
	 * into the other view's local space, computing overlaps there, transforming back to
	 * global space, and intersecting.
	 *
	 * This correctly handles rotations by working in each view's local coordinate system.
	 *
	 * @param dims1 Dimensions of view 1
	 * @param dims2 Dimensions of view 2
	 * @param transform1 Local-to-global transform for view 1
	 * @param transform2 Local-to-global transform for view 2
	 * @return The overlap interval in global coordinates, or null if no overlap
	 */
	public static RealInterval getOverlapIntervalUsingCorners(
			final Dimensions dims1,
			final Dimensions dims2,
			final AffineTransform3D transform1,
			final AffineTransform3D transform2 )
	{
		// Get bounding boxes in global space
		final RealInterval bbox1Global = getBoundingBoxReal( dims1, transform1 );
		final RealInterval bbox2Global = getBoundingBoxReal( dims2, transform2 );

		// Generate corners of each view's global bounding box
		final double[][] corners1Global = generateCornersFromInterval( bbox1Global );
		final double[][] corners2Global = generateCornersFromInterval( bbox2Global );

		// Transform bbox1's corners to view 2's local space
		final AffineTransform3D invTransform2 = transform2.inverse();
		final double[][] corners1InLocal2 = transformCorners( corners1Global, invTransform2 );

		// Compute bounding box of transformed corners in view 2's local space
		final RealInterval bbox1InLocal2 = getBoundingBoxFromCorners( corners1InLocal2 );

		// Intersect with view 2's valid image bounds [0, dims2-1]
		final RealInterval overlapInLocal2 = intersectWithImageBounds( bbox1InLocal2, dims2 );
		if ( overlapInLocal2 == null )
			return null;

		// Transform just the min/max points of this overlap back to global space
		final RealInterval overlap2Global = transformMinMaxToGlobal( overlapInLocal2, transform2 );

		// Do the same for view 2 → view 1
		final AffineTransform3D invTransform1 = transform1.inverse();
		final double[][] corners2InLocal1 = transformCorners( corners2Global, invTransform1 );
		final RealInterval bbox2InLocal1 = getBoundingBoxFromCorners( corners2InLocal1 );
		final RealInterval overlapInLocal1 = intersectWithImageBounds( bbox2InLocal1, dims1 );
		if ( overlapInLocal1 == null )
			return null;

		// Transform just the min/max points of this overlap back to global space
		final RealInterval overlap1Global = transformMinMaxToGlobal( overlapInLocal1, transform1 );

		// Intersect the two global overlaps
		return intersectIntervals( overlap1Global, overlap2Global );
	}

	/**
	 * Generate corner points from an existing RealInterval.
	 *
	 * @param interval The interval to generate corners from
	 * @return Array of corner points [numCorners][numDimensions]
	 */
	private static double[][] generateCornersFromInterval( final RealInterval interval )
	{
		final int n = interval.numDimensions();
		final int numCorners = (int) Math.pow( 2, n );
		final double[][] corners = new double[ numCorners ][ n ];

		final double[] min = new double[ n ];
		final double[] max = new double[ n ];
		interval.realMin( min );
		interval.realMax( max );

		// Generate all 2^n corner combinations using bit pattern
		for ( int i = 0; i < numCorners; i++ )
		{
			int j = i;
			for ( int d = 0; d < n; d++ )
			{
				corners[ i ][ d ] = ( j % 2 == 0 ) ? min[ d ] : max[ d ];
				j /= 2;
			}
		}

		return corners;
	}

	/**
	 * Intersect an interval with valid image bounds [0, dims[d]-1] for each dimension.
	 *
	 * @param interval The interval to intersect
	 * @param dims The image dimensions defining the bounds
	 * @return The intersected interval, or null if no overlap
	 */
	private static RealInterval intersectWithImageBounds( final RealInterval interval, final Dimensions dims )
	{
		final int n = interval.numDimensions();
		final double[] min = new double[ n ];
		final double[] max = new double[ n ];

		for ( int d = 0; d < n; d++ )
		{
			min[ d ] = Math.max( 0, interval.realMin( d ) );
			max[ d ] = Math.min( dims.dimension( d ) - 1, interval.realMax( d ) );

			// Check for no overlap
			if ( max[ d ] < min[ d ] )
				return null;
		}

		return new FinalRealInterval( min, max );
	}

	/**
	 * Transform just the min and max corner points of an interval to local space.
	 * This avoids the expansion that would occur if all corners were transformed.
	 *
	 * @param globalInterval The interval in global coordinates
	 * @param invTransform The global-to-local transform
	 * @return The interval in local coordinates
	 */
	private static RealInterval transformMinMaxToLocal( final RealInterval globalInterval, final AffineTransform3D invTransform )
	{
		final int n = globalInterval.numDimensions();
		final double[] globalMin = new double[ n ];
		final double[] globalMax = new double[ n ];
		final double[] localMin = new double[ n ];
		final double[] localMax = new double[ n ];

		globalInterval.realMin( globalMin );
		globalInterval.realMax( globalMax );

		// Transform min and max points
		invTransform.apply( globalMin, localMin );
		invTransform.apply( globalMax, localMax );

		// Ensure min < max (transform might swap them)
		final double[] resultMin = new double[ n ];
		final double[] resultMax = new double[ n ];
		for ( int d = 0; d < n; d++ )
		{
			resultMin[ d ] = Math.min( localMin[ d ], localMax[ d ] );
			resultMax[ d ] = Math.max( localMin[ d ], localMax[ d ] );
		}

		return new FinalRealInterval( resultMin, resultMax );
	}

	/**
	 * Transform just the min and max corner points of an interval to global space.
	 * This avoids the expansion that would occur if all corners were transformed.
	 *
	 * @param localInterval The interval in local coordinates
	 * @param transform The local-to-global transform
	 * @return The interval in global coordinates
	 */
	private static RealInterval transformMinMaxToGlobal( final RealInterval localInterval, final AffineTransform3D transform )
	{
		final int n = localInterval.numDimensions();
		final double[] localMin = new double[ n ];
		final double[] localMax = new double[ n ];
		final double[] globalMin = new double[ n ];
		final double[] globalMax = new double[ n ];

		localInterval.realMin( localMin );
		localInterval.realMax( localMax );

		// Transform min and max points
		transform.apply( localMin, globalMin );
		transform.apply( localMax, globalMax );

		// Ensure min < max (transform might swap them)
		final double[] resultMin = new double[ n ];
		final double[] resultMax = new double[ n ];
		for ( int d = 0; d < n; d++ )
		{
			resultMin[ d ] = Math.min( globalMin[ d ], globalMax[ d ] );
			resultMax[ d ] = Math.max( globalMin[ d ], globalMax[ d ] );
		}

		return new FinalRealInterval( resultMin, resultMax );
	}

	/**
	 * Intersect two intervals by taking max of mins and min of maxs.
	 *
	 * @param interval1 First interval
	 * @param interval2 Second interval
	 * @return The intersection, or null if no overlap
	 */
	private static RealInterval intersectIntervals( final RealInterval interval1, final RealInterval interval2 )
	{
		final int n = interval1.numDimensions();
		final double[] min = new double[ n ];
		final double[] max = new double[ n ];

		for ( int d = 0; d < n; d++ )
		{
			min[ d ] = Math.max( interval1.realMin( d ), interval2.realMin( d ) );
			max[ d ] = Math.min( interval1.realMax( d ), interval2.realMax( d ) );

			// Check for no overlap
			if ( max[ d ] < min[ d ] )
				return null;
		}

		return new FinalRealInterval( min, max );
	}

	/**
	 * Generate all 2^n corner points of an n-dimensional bounding box in local coordinates.
	 * For a 3D box with dimensions (width, height, depth), generates 8 corners from
	 * (0,0,0) to (width-1, height-1, depth-1).
	 *
	 * @param dims The dimensions of the view in local coordinates
	 * @return Array of corner points [numCorners][numDimensions]
	 */
	private static double[][] generateCorners( final Dimensions dims )
	{
		final int n = dims.numDimensions();
		final int numCorners = (int) Math.pow( 2, n );
		final double[][] corners = new double[ numCorners ][ n ];

		final double[] min = new double[ n ];
		final double[] max = new double[ n ];

		// min is always [0, 0, 0, ...] in local coordinates
		// max is [dim0-1, dim1-1, dim2-1, ...]
		for ( int d = 0; d < n; d++ )
		{
			min[ d ] = 0;
			max[ d ] = dims.dimension( d ) - 1;
		}

		// Generate all 2^n corner combinations using bit pattern
		for ( int i = 0; i < numCorners; i++ )
		{
			int j = i;
			for ( int d = 0; d < n; d++ )
			{
				corners[ i ][ d ] = ( j % 2 == 0 ) ? min[ d ] : max[ d ];
				j /= 2;
			}
		}

		return corners;
	}

	/**
	 * Transform an array of corner points using the given affine transformation.
	 *
	 * @param corners Array of corner points to transform [numCorners][numDimensions]
	 * @param transform The affine transformation to apply
	 * @return Array of transformed corner points [numCorners][numDimensions]
	 */
	private static double[][] transformCorners( final double[][] corners, final AffineTransform3D transform )
	{
		final int numCorners = corners.length;
		final int n = corners[ 0 ].length;
		final double[][] transformed = new double[ numCorners ][ n ];

		for ( int i = 0; i < numCorners; i++ )
		{
			transform.apply( corners[ i ], transformed[ i ] );
		}

		return transformed;
	}

	/**
	 * Calculate the axis-aligned bounding box that encompasses all given corner points.
	 * Finds the minimum and maximum coordinate values across all corners for each dimension.
	 *
	 * @param transformedCorners Array of corner points [numCorners][numDimensions]
	 * @return RealInterval representing the axis-aligned bounding box
	 */
	private static RealInterval getBoundingBoxFromCorners( final double[][] transformedCorners )
	{
		final int n = transformedCorners[ 0 ].length;
		final double[] min = new double[ n ];
		final double[] max = new double[ n ];

		// Initialize with extreme values
		for ( int d = 0; d < n; d++ )
		{
			min[ d ] = Double.MAX_VALUE;
			max[ d ] = -Double.MAX_VALUE;
		}

		// Find min and max across all corners
		for ( double[] corner : transformedCorners )
		{
			for ( int d = 0; d < n; d++ )
			{
				min[ d ] = Math.min( min[ d ], corner[ d ] );
				max[ d ] = Math.max( max[ d ], corner[ d ] );
			}
		}

		return new FinalRealInterval( min, max );
	}

	/**
	 * Transform a global space overlap interval to local coordinates by transforming
	 * all corners of the overlap box and finding the bounding box of transformed corners.
	 * This correctly handles non-axis-aligned transformations like rotations.
	 *
	 * @param globalOverlap The overlap interval in global coordinates
	 * @param invTransform The inverse transform (global-to-local)
	 * @return RealInterval in local coordinates
	 */
	public static RealInterval transformOverlapToLocal(
			final RealInterval globalOverlap,
			final AffineTransform3D invTransform )
	{
		// Generate corners of the global overlap box
		final int n = globalOverlap.numDimensions();
		final int numCorners = (int) Math.pow( 2, n );
		final double[][] globalCorners = new double[ numCorners ][ n ];

		final double[] gMin = new double[ n ];
		final double[] gMax = new double[ n ];
		globalOverlap.realMin( gMin );
		globalOverlap.realMax( gMax );

		// Generate all corners of global overlap box
		for ( int i = 0; i < numCorners; i++ )
		{
			int j = i;
			for ( int d = 0; d < n; d++ )
			{
				globalCorners[ i ][ d ] = ( j % 2 == 0 ) ? gMin[ d ] : gMax[ d ];
				j /= 2;
			}
		}

		// Transform all corners to local space
		final double[][] localCorners = transformCorners( globalCorners, invTransform );

		// Find bounding box of transformed corners
		return getBoundingBoxFromCorners( localCorners );
	}

	/**
	 * Convert real-valued local overlap coordinates to integer raster coordinates
	 * with proper bounds checking. Clamps to [0, dims[d]-1] for each dimension.
	 *
	 * @param localOverlap The overlap in local real coordinates
	 * @param dims The dimensions of the view (for bounds checking)
	 * @return FinalInterval with integer raster coordinates, or null if no valid overlap
	 */
	public static FinalInterval getRasterOverlap(
			final RealInterval localOverlap,
			final Dimensions dims )
	{
		final int n = localOverlap.numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		for ( int d = 0; d < n; d++ )
		{
			// Use ceiling for min, floor for max to be conservative
			min[ d ] = Math.max( 0, (long) Math.ceil( localOverlap.realMin( d ) ) );
			max[ d ] = Math.min( dims.dimension( d ) - 1, (long) Math.floor( localOverlap.realMax( d ) ) );

			// Validate that we have positive size
			if ( max[ d ] < min[ d ] )
			{
				return null; // No valid overlap
			}
		}

		return new FinalInterval( min, max );
	}
}
