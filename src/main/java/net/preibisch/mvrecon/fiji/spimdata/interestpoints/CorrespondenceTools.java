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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import mpicbg.spim.data.sequence.ViewId;

/**
 * Utility methods for managing interest point correspondences.
 */
public class CorrespondenceTools
{
	/**
	 * Removes all correspondences pointing TO the specified views from ALL other views.
	 * Multithreaded - one thread per target view being cleaned.
	 * Used by MissingViewsPopup when removing entire views.
	 *
	 * @param vip ViewInterestPoints containing all interest point data
	 * @param viewsToRemove Set of ViewIds whose correspondences should be removed
	 * @param numThreads Number of threads to use
	 * @return Number of correspondence entries removed
	 */
	public static int removeCorrespondencesToViews(
			final ViewInterestPoints vip,
			final Set< ViewId > viewsToRemove,
			final int numThreads )
	{
		// 1. Collect all views that have interest points (excluding viewsToRemove)
		final List< ViewId > viewsToClean = new ArrayList<>();
		for ( final ViewId vid : vip.getViewInterestPoints().keySet() )
		{
			if ( !containsViewId( viewsToRemove, vid ) )
				viewsToClean.add( vid );
		}

		if ( viewsToClean.isEmpty() )
			return 0;

		// 2. Create tasks for parallel processing
		final ExecutorService exec = Executors.newFixedThreadPool( numThreads );
		final List< Future< Integer > > futures = new ArrayList<>();

		for ( final ViewId vid : viewsToClean )
		{
			futures.add( exec.submit( new Callable< Integer >()
			{
				@Override
				public Integer call() throws Exception
				{
					int removed = 0;
					final ViewInterestPointLists vipl = vip.getViewInterestPointLists( vid );
					if ( vipl == null || vipl.getHashMap() == null )
						return 0;

					for ( final String label : vipl.getHashMap().keySet() )
					{
						final InterestPoints ips = vipl.getInterestPointList( label );
						final List< CorrespondingInterestPoints > corrs =
								new ArrayList<>( ips.getCorrespondingInterestPointsCopy() );

						final int sizeBefore = corrs.size();
						corrs.removeIf( c -> containsViewId( viewsToRemove, c.getCorrespondingViewId() ) );

						if ( corrs.size() < sizeBefore )
						{
							ips.setCorrespondingInterestPoints( corrs );
							removed += sizeBefore - corrs.size();
						}
					}
					return removed;
				}
			} ) );
		}

		// 3. Collect results
		exec.shutdown();
		int totalRemoved = 0;
		for ( final Future< Integer > f : futures )
		{
			try
			{
				totalRemoved += f.get();
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}

		return totalRemoved;
	}

	/**
	 * Inverse of {@link #removeCorrespondencesToViews}: removes correspondences whose
	 * correspondingViewId is NOT in the given set of views to keep. Multithreaded - one thread
	 * per source view being cleaned.
	 *
	 * Useful for repair flows where some target views were deleted entirely from the SpimData
	 * (so they don't appear as orphan IP-map keys at all) — filtering by a set of "removed" views
	 * misses those refs, while filtering against the canonical set of valid views catches them.
	 *
	 * Only iterates views that are present in {@code viewsToKeep} (orphan IP-map entries, if any,
	 * are skipped — they are typically about to be deleted anyway).
	 *
	 * @param vip ViewInterestPoints containing all interest point data
	 * @param viewsToKeep Set of ViewIds whose correspondences are valid; correspondences pointing
	 *        to any other ViewId are dropped
	 * @param numThreads Number of threads to use
	 * @return Number of correspondence entries removed
	 */
	public static int removeCorrespondencesNotToViews(
			final ViewInterestPoints vip,
			final Set< ViewId > viewsToKeep,
			final int numThreads )
	{
		// 1. Collect all kept views that have interest points
		final List< ViewId > viewsToClean = new ArrayList<>();
		for ( final ViewId vid : vip.getViewInterestPoints().keySet() )
		{
			if ( containsViewId( viewsToKeep, vid ) )
				viewsToClean.add( vid );
		}

		if ( viewsToClean.isEmpty() )
			return 0;

		// 2. Create tasks for parallel processing
		final ExecutorService exec = Executors.newFixedThreadPool( numThreads );
		final List< Future< Integer > > futures = new ArrayList<>();

		for ( final ViewId vid : viewsToClean )
		{
			futures.add( exec.submit( new Callable< Integer >()
			{
				@Override
				public Integer call() throws Exception
				{
					int removed = 0;
					final ViewInterestPointLists vipl = vip.getViewInterestPointLists( vid );
					if ( vipl == null || vipl.getHashMap() == null )
						return 0;

					for ( final String label : vipl.getHashMap().keySet() )
					{
						final InterestPoints ips = vipl.getInterestPointList( label );
						final List< CorrespondingInterestPoints > corrs =
								new ArrayList<>( ips.getCorrespondingInterestPointsCopy() );

						final int sizeBefore = corrs.size();
						corrs.removeIf( c -> !containsViewId( viewsToKeep, c.getCorrespondingViewId() ) );

						if ( corrs.size() < sizeBefore )
						{
							ips.setCorrespondingInterestPoints( corrs );
							removed += sizeBefore - corrs.size();
						}
					}
					return removed;
				}
			} ) );
		}

		// 3. Collect results
		exec.shutdown();
		int totalRemoved = 0;
		for ( final Future< Integer > f : futures )
		{
			try
			{
				totalRemoved += f.get();
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}

		return totalRemoved;
	}

	/**
	 * Removes correspondences whose {@code correspondingLabel} equals the given label, from
	 * every view's every (other) interest-point list. Multithreaded - one thread per source
	 * view being cleaned.
	 *
	 * Useful when a label is being removed across the whole dataset: after dropping the
	 * (view, label) entries themselves, call this to also drop the cross-references in the
	 * remaining labels' correspondence lists.
	 *
	 * @param vip ViewInterestPoints containing all interest point data
	 * @param label The corresponding-label value whose references should be dropped
	 * @param numThreads Number of threads to use
	 * @return Number of correspondence entries removed
	 */
	public static int removeCorrespondencesToLabel(
			final ViewInterestPoints vip,
			final String label,
			final int numThreads )
	{
		final List< ViewId > viewsToClean = new ArrayList<>( vip.getViewInterestPoints().keySet() );

		if ( viewsToClean.isEmpty() )
			return 0;

		final ExecutorService exec = Executors.newFixedThreadPool( numThreads );
		final List< Future< Integer > > futures = new ArrayList<>();

		for ( final ViewId vid : viewsToClean )
		{
			futures.add( exec.submit( new Callable< Integer >()
			{
				@Override
				public Integer call() throws Exception
				{
					int removed = 0;
					final ViewInterestPointLists vipl = vip.getViewInterestPointLists( vid );
					if ( vipl == null || vipl.getHashMap() == null )
						return 0;

					for ( final String l : vipl.getHashMap().keySet() )
					{
						final InterestPoints ips = vipl.getInterestPointList( l );
						final List< CorrespondingInterestPoints > corrs =
								new ArrayList<>( ips.getCorrespondingInterestPointsCopy() );

						final int sizeBefore = corrs.size();
						corrs.removeIf( c -> label.equals( c.getCorrespodingLabel() ) );

						if ( corrs.size() < sizeBefore )
						{
							ips.setCorrespondingInterestPoints( corrs );
							removed += sizeBefore - corrs.size();
						}
					}
					return removed;
				}
			} ) );
		}

		exec.shutdown();
		int totalRemoved = 0;
		for ( final Future< Integer > f : futures )
		{
			try
			{
				totalRemoved += f.get();
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}

		return totalRemoved;
	}

	/**
	 * Removes correspondences pointing to a specific view+label from all other views.
	 * Multithreaded - one thread per target view being cleaned.
	 * Used by InterestPointExplorerPanel when deleting a single label.
	 *
	 * @param vip ViewInterestPoints containing all interest point data
	 * @param viewId The ViewId whose label is being deleted
	 * @param label The label being deleted
	 * @param numThreads Number of threads to use
	 * @return Number of correspondence entries removed
	 */
	public static int removeCorrespondencesForViewLabel(
			final ViewInterestPoints vip,
			final ViewId viewId,
			final String label,
			final int numThreads )
	{
		// 1. Get the correspondences FROM the view being deleted
		final ViewInterestPointLists vipl = vip.getViewInterestPointLists( viewId );
		if ( vipl == null || !vipl.contains( label ) )
			return 0;

		final List< CorrespondingInterestPoints > correspondences =
				new ArrayList<>( vipl.getInterestPointList( label ).getCorrespondingInterestPointsCopy() );

		if ( correspondences.isEmpty() )
			return 0;

		// 2. Group correspondences by target view
		final Map< ViewId, List< CorrespondingInterestPoints > > byTargetView = new HashMap<>();
		for ( final CorrespondingInterestPoints cip : correspondences )
		{
			final ViewId targetViewId = cip.getCorrespondingViewId();
			byTargetView.computeIfAbsent( targetViewId, k -> new ArrayList<>() ).add( cip );
		}

		// 3. Process each target view in parallel
		final ExecutorService exec = Executors.newFixedThreadPool( numThreads );
		final List< Future< Integer > > futures = new ArrayList<>();

		for ( final Map.Entry< ViewId, List< CorrespondingInterestPoints > > entry : byTargetView.entrySet() )
		{
			final ViewId targetViewId = entry.getKey();
			final List< CorrespondingInterestPoints > toRemove = entry.getValue();

			futures.add( exec.submit( new Callable< Integer >()
			{
				@Override
				public Integer call() throws Exception
				{
					final ViewInterestPointLists targetVipl = vip.getViewInterestPointLists( targetViewId );
					if ( targetVipl == null )
						return 0;

					int removed = 0;
					for ( final CorrespondingInterestPoints cip : toRemove )
					{
						final String targetLabel = cip.getCorrespodingLabel();
						if ( !targetVipl.contains( targetLabel ) )
							continue;

						final InterestPoints targetIps = targetVipl.getInterestPointList( targetLabel );
						final List< CorrespondingInterestPoints > targetCorrs =
								new ArrayList<>( targetIps.getCorrespondingInterestPointsCopy() );

						final int sizeBefore = targetCorrs.size();
						// Remove entry where: detectionId matches cip.correspondingDetectionId
						//                 AND correspondingViewId matches viewId
						//                 AND correspondingDetectionId matches cip.detectionId
						targetCorrs.removeIf( cc ->
								cc.getDetectionId() == cip.getCorrespondingDetectionId() &&
								viewIdEquals( cc.getCorrespondingViewId(), viewId ) &&
								cc.getCorrespondingDetectionId() == cip.getDetectionId() );

						if ( targetCorrs.size() < sizeBefore )
						{
							targetIps.setCorrespondingInterestPoints( targetCorrs );
							removed += sizeBefore - targetCorrs.size();
						}
					}
					return removed;
				}
			} ) );
		}

		// 4. Collect results
		exec.shutdown();
		int totalRemoved = 0;
		for ( final Future< Integer > f : futures )
		{
			try
			{
				totalRemoved += f.get();
			}
			catch ( final Exception e )
			{
				e.printStackTrace();
			}
		}

		return totalRemoved;
	}

	/**
	 * Helper to check ViewId membership (handles equals() properly across different object instances).
	 */
	private static boolean containsViewId( final Set< ViewId > set, final ViewId vid )
	{
		for ( final ViewId v : set )
			if ( v.getTimePointId() == vid.getTimePointId() &&
				 v.getViewSetupId() == vid.getViewSetupId() )
				return true;
		return false;
	}

	/**
	 * Helper to compare ViewIds by timepoint and setup ID.
	 */
	private static boolean viewIdEquals( final ViewId a, final ViewId b )
	{
		return a.getTimePointId() == b.getTimePointId() &&
			   a.getViewSetupId() == b.getViewSetupId();
	}
}
