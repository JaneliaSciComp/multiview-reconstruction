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

import java.util.List;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

/**
 * Generic criterion interface for deciding whether to continue splitting
 * an octant in the oct-tree splitting algorithm.
 *
 * Implementations determine based on some metric (e.g., interest point
 * correspondence count) whether a region should be further subdivided.
 */
public interface OctTreeSplitCriterion
{
	/**
	 * Evaluates whether the given interval should be split further.
	 *
	 * @param interval The interval (region) to evaluate in local coordinates
	 * @param viewId The ViewId being processed (for accessing view-specific data)
	 * @param timepointId The timepoint being evaluated
	 * @return true if this interval should be split further, false if it should remain as-is
	 */
	boolean shouldSplit( Interval interval, ViewId viewId, int timepointId );

	/**
	 * @return A description of this criterion and its parameters for logging/display
	 */
	String description();

	/**
	 * @return The SpimData2 used by this criterion (needed for accessing timepoints, views, etc.)
	 */
	SpimData2 getSpimData();

	/**
	 * Check if a set of intervals can be merged (combined metric below threshold).
	 * Default implementation: check if bounding box doesn't need splitting.
	 *
	 * @param intervals The intervals to potentially merge
	 * @param viewId The ViewId being processed
	 * @param timepointId The timepoint being evaluated
	 * @return true if merging is allowed (combined metric ≤ threshold)
	 */
	default boolean canMerge( List< Interval > intervals, ViewId viewId, int timepointId )
	{
		if ( intervals == null || intervals.size() <= 1 )
			return true;

		final Interval bbox = boundingBox( intervals );
		return !shouldSplit( bbox, viewId, timepointId );
	}

	/**
	 * Compute the bounding box (union) of multiple intervals.
	 *
	 * @param intervals List of intervals to compute bounding box for
	 * @return The bounding box containing all intervals
	 */
	static Interval boundingBox( final List< Interval > intervals )
	{
		if ( intervals == null || intervals.isEmpty() )
			return null;

		if ( intervals.size() == 1 )
			return intervals.get( 0 );

		final int n = intervals.get( 0 ).numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		// Initialize with first interval
		for ( int d = 0; d < n; d++ )
		{
			min[ d ] = intervals.get( 0 ).min( d );
			max[ d ] = intervals.get( 0 ).max( d );
		}

		// Expand to include all other intervals
		for ( int i = 1; i < intervals.size(); i++ )
		{
			final Interval interval = intervals.get( i );
			for ( int d = 0; d < n; d++ )
			{
				min[ d ] = Math.min( min[ d ], interval.min( d ) );
				max[ d ] = Math.max( max[ d ], interval.max( d ) );
			}
		}

		return new FinalInterval( min, max );
	}
}
