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
package net.preibisch.mvrecon.process.splitting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;

/**
 * Generic criterion interface for deciding whether to continue splitting
 * an octant in the oct-tree splitting algorithm.
 *
 * Implementations determine based on some metric (e.g., interest point
 * correspondence count) whether a region should be further subdivided.
 *
 * The splitting algorithm uses recursive correspondence partitioning:
 * correspondences are loaded once per view, then partitioned as intervals
 * are split, so each recursive call only processes its subset.
 */
public interface OctTreeSplitCriterion
{
	/**
	 * Lightweight structure for correspondence data during splitting.
	 * Contains only the information needed for partitioning and evaluation.
	 */
	public static class SplitCorrespondence
	{
		/** Detection location in local coordinates (for spatial partitioning) */
		public final double[] location;

		/** Detection ID (for counting unique detections) */
		public final int detectionId;

		/** Key identifying the corresponding view: "timepointId_setupId" */
		public final String corrViewKey;

		/** Consensus set ID (-1 for single-consensus mode) */
		public final int consensusSetId;

		public SplitCorrespondence( final double[] location, final int detectionId, final String corrViewKey, final int consensusSetId )
		{
			this.location = location;
			this.detectionId = detectionId;
			this.corrViewKey = corrViewKey;
			this.consensusSetId = consensusSetId;
		}
	}

	/**
	 * Load all correspondences for a view. Called once at the start of splitting.
	 * The returned list will be partitioned as the oct-tree recurses.
	 *
	 * Default implementation loads cross-view correspondences for all labels.
	 *
	 * @param viewId The ViewId to load correspondences for
	 * @return List of correspondences for this view
	 */
	default List< SplitCorrespondence > loadCorrespondences( final ViewId viewId )
	{
		final List< SplitCorrespondence > result = new ArrayList<>();

		final ViewInterestPointLists vipl = getSpimData().getViewInterestPoints().getViewInterestPointLists( viewId );
		if ( vipl == null )
			return result;

		final int currentSetupId = viewId.getViewSetupId();

		// Offset consensusSetId per label to avoid collisions across labels
		int consensusSetOffset = 0;

		for ( final String label : getLabels() )
		{
			if ( !vipl.contains( label ) )
				continue;

			final InterestPoints ips = vipl.getInterestPointList( label );
			final Map< Integer, InterestPoint > ipMap = ips.getInterestPointsCopy();
			final Collection< CorrespondingInterestPoints > corrs = ips.getCorrespondingInterestPointsCopy();

			int maxConsensusSetId = 0;

			for ( final CorrespondingInterestPoints cip : corrs )
			{
				// Only include correspondences to DIFFERENT views
				final ViewId corrViewId = cip.getCorrespondingViewId();
				if ( corrViewId.getViewSetupId() == currentSetupId )
					continue;

				// Get the detection location
				final InterestPoint ip = ipMap.get( cip.getDetectionId() );
				if ( ip == null )
					continue;

				// Track max consensusSetId for offset calculation
				maxConsensusSetId = Math.max( maxConsensusSetId, cip.getConsensusSetId() );

				// Create SplitCorrespondence with offset consensusSetId
				final String corrViewKey = corrViewId.getTimePointId() + "_" + corrViewId.getViewSetupId();
				final int adjustedConsensusSetId = cip.getConsensusSetId() + consensusSetOffset;
				result.add( new SplitCorrespondence( ip.getL(), cip.getDetectionId(), corrViewKey, adjustedConsensusSetId ) );
			}

			// Offset for next label
			consensusSetOffset += maxConsensusSetId + 1;
		}

		IOFunctions.println( "Loaded " + result.size() + " correspondences for view " +
				viewId.getTimePointId() + "_" + viewId.getViewSetupId() );

		return result;
	}

	/**
	 * Evaluates whether to continue splitting based on pre-filtered correspondences.
	 * The correspondences have already been filtered to only include those
	 * within the current interval.
	 *
	 * @param correspondences The correspondences within the current interval
	 * @return true if this region should be split further, false if it should remain as-is
	 */
	boolean shouldSplit( List< SplitCorrespondence > correspondences );

	/**
	 * Score a candidate binary split. Lower is better.
	 * Used as tiebreaker in chooseBestSplitDimension when multiple
	 * dimensions produce the same number of clean children.
	 *
	 * @param child1 Correspondences in the first child
	 * @param child2 Correspondences in the second child
	 * @return Score (lower = better split). Default: 0 (no preference).
	 */
	default double scoreSplit( List< SplitCorrespondence > child1, List< SplitCorrespondence > child2 )
	{
		return 0;
	}

	/**
	 * @return A description of this criterion and its parameters for logging/display
	 */
	String description();

	/**
	 * @return The SpimData2 used by this criterion (needed for accessing timepoints, views, etc.)
	 */
	SpimData2 getSpimData();

	/**
	 * @return The set of interest point labels to consider for correspondences
	 */
	Set< String > getLabels();
}
