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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.util.HashMap;

import mpicbg.spim.data.sequence.ViewId;

/**
 * Maps from a String label to a list of interest points for a specific viewid
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class ViewInterestPointLists extends ViewId
{
	protected final HashMap< String, InterestPoints > lookup;
	
	public ViewInterestPointLists( final int timepointId, final int setupId )
	{
		super( timepointId, setupId );
		
		this.lookup = new HashMap< String, InterestPoints >();
	}
	
	public boolean contains( final String label ) { return lookup.containsKey( label ); }
	public HashMap< String, InterestPoints > getHashMap() { return lookup; }
	public InterestPoints getInterestPointList( final String label ) { return lookup.get( label ); }
	public void addInterestPointList( final String label, final InterestPoints pointList ) { lookup.put( label, pointList ); }
}
