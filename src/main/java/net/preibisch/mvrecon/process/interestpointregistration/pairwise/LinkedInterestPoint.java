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

import net.imglib2.util.Util;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

public class LinkedInterestPoint< P > extends InterestPoint
{
	private static final long serialVersionUID = 1L;

	final P link;

	public LinkedInterestPoint( final int id, final double[] l, final P link )
	{
		super( id, l.clone() );
		this.link = link;
	}

	public P getLinkedObject() { return link; }
	
	public String toString() { return "LinkedInterestPoint " + Util.printCoordinates( l ); }
}
