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

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;

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
}
