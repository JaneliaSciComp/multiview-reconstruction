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
package net.preibisch.legacy.registration.detection;

import net.imglib2.util.Util;
import fiji.util.node.Leaf;
import mpicbg.models.Point;

public abstract class AbstractDetection< T extends AbstractDetection< T > > extends Point implements Leaf< T >
{
	private static final long serialVersionUID = 1L;
	
	final protected long id;
	protected double weight;
	protected boolean useW = false;

	// used for display
	protected double distance = -1;

	// used for recursive parsing
	protected boolean isUsed = false;		
	
	public AbstractDetection( final int id, final double[] location )
	{
		super( location );
		this.id = id;
	}

	public AbstractDetection( final int id, final double[] location, final double weight )
	{
		super( location );
		this.id = id;
		this.weight = weight;
	}

	public void setWeight( final double weight ){ this.weight = weight; }
	public double getWeight(){ return weight; }
	public long getID() { return id; }
	public void setDistance( double distance )  { this.distance = distance; }
	public double getDistance() { return distance; }
	public boolean isUsed() { return isUsed; }
	public void setUsed( final boolean isUsed ) { this.isUsed = isUsed; }

	public boolean equals( final AbstractDetection<?> otherDetection )
	{
		if ( useW )
		{
			for ( int d = 0; d < 3; ++d )
				if ( w[ d ] != otherDetection.w[ d ] )
					return false;			
		}
		else
		{
			for ( int d = 0; d < 3; ++d )
				if ( l[ d ] != otherDetection.l[ d ] )
					return false;						
		}
				
		return true;
	}
	
	public void setW( final double[] wn )
	{
		for ( int i = 0; i < w.length; ++i )
			w[ i ] = wn[ i ];
	}
	
	public void resetW()
	{
		for ( int i = 0; i < w.length; ++i )
			w[i] = l[i];
	}
	
	public double getDistance( final Point point2 )
	{
		double distance = 0;
		final double[] a = getL();
		final double[] b = point2.getW();
		
		for ( int i = 0; i < getL().length; ++i )
		{
			final double tmp = a[ i ] - b[ i ];
			distance += tmp * tmp;
		}
		
		return Math.sqrt( distance );
	}
	
	@Override
	public boolean isLeaf() { return true; }

	@Override
	public float distanceTo( final T o ) 
	{
		final double x = o.get( 0 ) - get( 0 );
		final double y = o.get( 1 ) - get( 1 );
		final double z = o.get( 2 ) - get( 2 );
		
		return (float)Math.sqrt(x*x + y*y + z*z);
	}
	
	public void setUseW( final boolean useW ) { this.useW = useW; } 
	public boolean getUseW() { return useW; } 

	@Override
	public float get( final int k ) 
	{
		if ( useW )
			return (float)w[ k ];
		else
			return (float)l[ k ];
	}
	
	@Override
	public int getNumDimensions() { return 3; }	
	
	@Override
	public String toString()
	{
		String desc = "Detection " + getID() + " l"+ Util.printCoordinates( getL() ) + "; w"+ Util.printCoordinates( getW() );
		return desc;
	}	
}
