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

import java.io.Serializable;

import mpicbg.models.Point;
import net.imglib2.RealLocalizable;

/**
 * Single interest point, extends mpicbg Point by an id
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class InterestPoint extends Point implements RealLocalizable, Serializable
{
	private static final long serialVersionUID = 5615112297702152070L;

	protected final int id;

	public InterestPoint( final int id, final double[] l )
	{
		super( l );
		this.id = id;
	}

	public int getId() { return id; }

	@Override
	public int numDimensions() { return l.length; }

	@Override
	public void localize( final float[] position )
	{
		for ( int d = 0; d < l.length; ++d )
			position[ d ] = (float)w[ d ];
	}

	@Override
	public void localize( final double[] position )
	{
		for ( int d = 0; d < l.length; ++d )
			position[ d ] = w[ d ];
	}

	@Override
	public float getFloatPosition( final int d ) { return (float)w[ d ]; }

	@Override
	public double getDoublePosition( final int d ) { return w[ d ]; }

	public InterestPoint newInstance( final int id, final double[] l ) { return new InterestPoint( id, l ); }

	public InterestPoint duplicate() { return clone(); }

	@Override
	public InterestPoint clone() { return new InterestPoint( this.id, this.l.clone() ); }

	@Override
	public int hashCode()
	{
		return Integer.hashCode( id );
	}

	@Override
	public boolean equals( Object obj )
	{
		if ( this == obj )
			return true;
		if ( obj == null )
			return false;
		if ( getClass() != obj.getClass() )
			return false;
		final InterestPoint other = (InterestPoint) obj;

		if ( other.id != id )
			return false;

		for ( int d = 0; d < numDimensions(); ++d )
			if ( other.getDoublePosition( d ) != getDoublePosition( d ) )
				return false;

		return true;
	}

}
