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
package net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher;

import java.util.ArrayList;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.AbstractPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.LinkedPoint;

public interface Matcher
{
	/**
	 * @param pd1 - pointdescriptor 1
	 * @param pd2 - pointdescriptor 2
	 * @return An {@link ArrayList} of corresponding set of {@link PointMatch}es which contain {@link LinkedPoint}s linking to the actual {@link Point} instance they are created from
	 */
	public ArrayList<ArrayList<PointMatch>> createCandidates( AbstractPointDescriptor<?, ?> pd1, AbstractPointDescriptor<?, ?> pd2 );
	
	/**
	 * Computes a normalization factor for the case that the different set of {@link PointMatch}es are not comparable 
	 * (for example number of neighbors used is not constant)
	 * 
	 * @param fitResult - potential data needed to compute it
	 * @param matches the set of {@link PointMatch}es
	 * @return The normalization factor for a certain set of {@link PointMatch}es 
	 */
	public double getNormalizationFactor( final ArrayList<PointMatch> matches, Object fitResult );
	
	/**
	 * @return The number of nearest neighbors required for this {@link Matcher} 
	 */
	public int getRequiredNumNeighbors();
}
