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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise;

import java.util.Collection;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

public interface MatcherPairwise< I extends InterestPoint >
{
	/**
	 * Computes a pairwise matching between two lists of interestpoints.
	 * 
	 * NOTE: If the interestpoints (local or world coordinates) are changed, you MUST duplicate them before using
	 * them using e.g. LinkedInteresPoint {@literal< I >}
	 * 
	 * @param listAIn interest point list A
	 * @param listBIn interest point list B
	 * @param viewsA the Views or Groups the listAIn interest points come from
	 * @param viewsB the Views or Groups the listBIn interest points come from
	 * @param labelA the label underlying list A
	 * @param labelB the label underlying list B
	 * @return matched pairwise results
	 */
	public <V> PairwiseResult< I > match(
			final Collection< I > listAIn,
			final Collection< I > listBIn,
			final V viewsA,
			final V viewsB,
			final String labelA,
			final String labelB );

	/**
	 * Determines if this pairwise matching requires a duplication of the input InterestPoints as these instances are ran
	 * multithreaded. So if the InterestPoints are modified in any way (e.g. fitting models to it), this method must return true, otherwise
	 * false (e.g. if only interestpoint.getL() is read).
	 *
	 * @return if duplication is necessary
	 */
	public boolean requiresInterestPointDuplication();
}
