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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.subsetvector;

/**
 * Finds the best and second-best B owner for one A descriptor, over all of B or over the B descriptors inside the
 * search radius. Built once per view pair over A's and B's subset vectors.
 */
interface SubsetSearch
{
	/** folds the best and second-best B owner over all of B into {@code out} */
	void searchAll( int indexA, BestMatches out );

	/** same, restricted to the B descriptors {@code ownersB[ 0 .. numOwners - 1 ]} (those inside the search radius) */
	void searchWithin( int indexA, int[] ownersB, int numOwners, BestMatches out );
}
