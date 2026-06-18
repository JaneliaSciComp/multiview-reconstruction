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

import mpicbg.models.PointMatch;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.AbstractPointDescriptor;

public class SimpleMatcher implements Matcher
{
	final int numNeighbors;
	
	public SimpleMatcher( final int numNeighbors )
	{
		this.numNeighbors = numNeighbors;
	}
	
	@Override
	public ArrayList<ArrayList<PointMatch>> createCandidates( final AbstractPointDescriptor<?, ?> pd1, final AbstractPointDescriptor<?, ?> pd2 )
	{
		final ArrayList<PointMatch> matches = new ArrayList<PointMatch>( numNeighbors );		
		
		for ( int i = 0; i < numNeighbors; ++i )
		{
			final PointMatch pointMatch = new PointMatch( pd1.getDescriptorPoint( i ), pd2.getDescriptorPoint( i ) );
			matches.add( pointMatch );
		}		

		final ArrayList<ArrayList<PointMatch>> matchesList = new ArrayList<ArrayList<PointMatch>>();		
		matchesList.add( matches );
		
		return matchesList;
	}

	@Override
	public int getRequiredNumNeighbors() { return numNeighbors; }

	@Override
	public double getNormalizationFactor( final ArrayList<PointMatch> matches, final Object fitResult ) { return 1;	}
}
