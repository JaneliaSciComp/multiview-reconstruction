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
package net.preibisch.mvrecon.process.pointcloud.pointdescriptor.test;

import fiji.util.node.Leaf;
import mpicbg.models.Point;

public class PointNode extends Point implements Leaf<PointNode>
{
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	final int numDimensions;
	
	public PointNode( final Point p )
	{
		super( p.getL().clone(), p.getW().clone() );
		this.numDimensions = l.length;
	}
	
	public PointNode( final double[] l )
	{
		super( l );
		this.numDimensions = l.length;
	}

	public PointNode( final double[] l, final double[] w )
	{
		super( l, w );
		this.numDimensions = l.length;
	}

	@Override
	public PointNode[] createArray( final int n ) { return new PointNode[ n ]; }

	@Override
	public float distanceTo( final PointNode other ) { return (float)Point.distance( this, other ); }

	@Override
	public float get( final int k ) { return (float)w[ k ]; }

	@Override
	public int getNumDimensions() { return numDimensions; }

	@Override
	public boolean isLeaf() { return true; }	
}
