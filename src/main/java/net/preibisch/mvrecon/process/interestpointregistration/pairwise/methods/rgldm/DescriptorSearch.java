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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm;

/** How RGLDM compares descriptors; the user-facing choice in the GUI, BigStitcher-Spark and {@link RGLDMParameters}. */
public enum DescriptorSearch
{
	/** flat KD-tree or brute force, chosen per view pair from the neighbor count and the in-radius fraction */
	AUTO,
	/** {@link SubsetVectorMatching} with the flat KD-tree */
	FLAT_KDTREE,
	/** {@link SubsetVectorMatching} with the blocked, vectorized brute force */
	BLOCKED_BRUTE_FORCE,
	/** the original exhaustive descriptor loop in {@link RGLDMMatcher} */
	LEGACY
}
