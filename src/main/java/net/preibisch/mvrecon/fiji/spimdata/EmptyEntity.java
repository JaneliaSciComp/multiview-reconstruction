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
package net.preibisch.mvrecon.fiji.spimdata;

import java.util.Map;

import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.base.NamedEntity;

public class EmptyEntity extends NamedEntity
{
	private int id;
	private String name;
	final String tag;

	public EmptyEntity( final int id, final String tag )
	{
		this.id = id;
		this.tag = tag;
		this.name = Integer.toString( id );
	}
	/**
	 * Get the unique id of this entity. This unique id is used for example
	 * as a key in {@link Map}s and for cross-referencing in XML files.
	 *
	 * @return unique id.
	 */
	public int getId()
	{
		return id;
	}

	public String getTag() { return tag; }

	@Override
	public int hashCode()
	{
		return getId();
	}

	public String getName()
	{
		return name;
	}

	protected void setName( final String name )
	{
		this.name = name;
	}

	/**
	 * Two {@link Entity}s are equal if they have the same {@link #getId() and the same tag as both are unknown types of entities}.
	 */
	@Override
	public boolean equals( final Object o )
	{
		if ( o == null )
		{
			return false;
		}
		else if ( getClass().isInstance( o ) )
		{
			if ( ( ( Entity ) o ).getId() == getId()  )
			{
				if ( ((EmptyEntity)o).getTag().equals( getTag() ))
					return true;
				else
					return false;
			}
			else
			{
				return false;
			}
		}
		else
		{
			return false;
		}
	}
}
