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

import mpicbg.models.Point;
import net.imglib2.RealLocalizable;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

public class VirtualPointNode<P extends Point> implements RealLocalizable
{

	final P p;
	final int numDimensions;
	final boolean useW;
	
	public VirtualPointNode( final P p )
	{
		this.useW = true;
		this.p = p;
		this.numDimensions = p.getL().length;
	}
	
	public P getPoint() { return p; }

	@Override
	public int numDimensions() { return numDimensions; }

	@Override
	public void localize( final float[] position )
	{
		for ( int d = 0; d < position.length; ++d )
			position[ d ] = useW? (float)p.getW()[ d ] : (float)p.getL()[ d ];
	}

	@Override
	public void localize( final double[] position )
	{
		for ( int d = 0; d < position.length; ++d )
			position[ d ] = useW? p.getW()[ d ] : p.getL()[ d ];
	}

	@Override
	public float getFloatPosition( final int d ) { return useW? (float)p.getW()[ d ] : (float)p.getL()[ d ]; }

	@Override
	public double getDoublePosition( final int d ) { return useW? p.getW()[ d ] : p.getL()[ d ]; }

	public InterestPoint newInstance( final int id, final double[] l ) { return new InterestPoint( id, l ); }
}
