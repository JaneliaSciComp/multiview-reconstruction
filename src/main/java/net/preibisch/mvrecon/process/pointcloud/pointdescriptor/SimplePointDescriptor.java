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
package net.preibisch.mvrecon.process.pointcloud.pointdescriptor;

import java.util.ArrayList;

import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.Matcher;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.similarity.SimilarityMeasure;

public class SimplePointDescriptor < P extends Point > extends AbstractPointDescriptor< P, SimplePointDescriptor<P> >
{
	public SimplePointDescriptor( final P basisPoint, final ArrayList<P> orderedNearestNeighboringPoints, final SimilarityMeasure similarityMeasure, final Matcher matcher ) throws NoSuitablePointsException
	{
		super( basisPoint, orderedNearestNeighboringPoints, similarityMeasure, matcher );
		
		/* check that number of nearest neighbors is at least ONE, otherwise relative distances are useless */
		if ( numNeighbors() < 1 )
			throw new NoSuitablePointsException( "At least 1 nearest neighbor is required for matching descriptors." );
		
	}

	@Override
	public Object fitMatches( final ArrayList<PointMatch> matches ) { return null; }

	@Override
	public boolean resetWorldCoordinatesAfterMatching() { return false; }

	@Override
	public boolean useWorldCoordinatesForDescriptorBuildUp() { return true; }
}
