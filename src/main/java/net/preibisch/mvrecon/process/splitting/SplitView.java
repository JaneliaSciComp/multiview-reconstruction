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
package net.preibisch.mvrecon.process.splitting;

import java.util.List;

import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;

/**
 * Interface for splitting views into sub-intervals.
 * Implementations look up the view's dimensions from SpimData internally.
 */
public interface SplitView
{
	/**
	 * Split a single view into sub-intervals.
	 *
	 * @param viewId the view to split
	 * @return split result containing intervals and statistics, or null on error
	 */
	SplitResult split( ViewId viewId );

	/**
	 * @return human-readable description of this splitter's configuration
	 */
	String description();

	/**
	 * Aggregate statistics from multiple split results into a summary string.
	 * Called after parallel splitting to produce a single log message.
	 *
	 * @param results list of per-view split results
	 * @return formatted summary string
	 */
	String aggregateStatistics( List< ? extends SplitResult > results );

	/**
	 * Compute the maximum number of intervals any single view could produce.
	 * Used to determine tile ID spacing.
	 *
	 * @param oldSetups the view setups to consider
	 * @return upper bound on interval count per view
	 */
	int maxIntervalSpread( List< ViewSetup > oldSetups );
}
