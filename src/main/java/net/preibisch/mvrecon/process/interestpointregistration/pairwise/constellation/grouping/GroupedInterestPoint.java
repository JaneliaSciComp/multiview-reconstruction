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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

/**
 * Single interest point, extends InterestPoint to remember where it came from for grouping
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class GroupedInterestPoint< V > extends InterestPoint
{
	private static final long serialVersionUID = -7473168262289156212L;
	final V view;

	public GroupedInterestPoint( final V view, final int id, final double[] l )
	{
		super( id, l );

		this.view = view;
	}

	public V getV() { return view; }

	@Override
	public GroupedInterestPoint< V > duplicate() { return clone(); }

	@Override
	public GroupedInterestPoint< V > clone() { return new GroupedInterestPoint< V >( this.view, this.id, this.l.clone() ); }

	// TODO: this is also a hack, but we need to assign some view to it
	@Override
	public GroupedInterestPoint< V > newInstance( final int id, final double[] l ) { return new GroupedInterestPoint< V > ( this.view, id, l ); }

	@Override
	public boolean equals( final Object obj )
	{
		if ( this == obj )
			return true;
		if ( obj == null )
			return false;
		if ( getClass() != obj.getClass() )
			return false;
		GroupedInterestPoint< ? > other = (GroupedInterestPoint< ? >) obj;
		if ( view == null )
		{
			if ( other.view != null )
				return false;
		}
		else if ( !view.equals( other.view ) )
		{
			return false;
		}
		else if ( id != other.id )
		{
			return false;
		}

		return true;
	}
}
