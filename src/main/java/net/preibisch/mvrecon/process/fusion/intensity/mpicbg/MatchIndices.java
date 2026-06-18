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
package net.preibisch.mvrecon.process.fusion.intensity.mpicbg;

import java.util.Collection;
import java.util.List;
import java.util.Random;

public class MatchIndices
{
	private final int[] indices;

	private int size;

	public MatchIndices( final int capacity )
	{
		indices = new int[ capacity ];
		size = 0;
	}

	public int capacity() {
		return indices.length;
	}

	public int size()
	{
		return size;
	}

	/**
	 * Get the internal index array.
	 * <p>
	 * Note that the length of the returned array may be larger than the current {@code size()} of this {@code MatchIndices}.
	 *
	 * @return index array
	 */
	public int[] indices()
	{
		return indices;
	}

	public void setSize( final int size )
	{
		if ( size > capacity() )
			throw new IllegalArgumentException( "Given size exceeds the capacity" );
		this.size = size;
	}

	public void set( final MatchIndices indices )
	{
		if ( indices.size() > capacity() )
			throw new IllegalArgumentException( "Given indices exceed the capacity" );
		System.arraycopy( indices.indices, 0, this.indices, 0, indices.size );
		this.size = indices.size;
	}

	public void sample( final Random rnd, final int bound )
	{
		distinctRandomInts( rnd, bound, indices );
		size = indices.length;
	}

	public < T > void addSelected( final List< T > elements, final Collection< T > selectedElements )
	{
		for ( int i = 0; i < size; i++ )
			selectedElements.add( elements.get( indices[ i ] ) );
	}

	public void copySelected( final double[] src, final double[] dest )
	{
		for ( int i = 0; i < size; i++ )
			dest[ i ] = src[ indices[ i ] ];
	}

	public void copySelected( final FlattenedMatches elements, final FlattenedMatches selectedElements )
	{
		for ( int d = 0; d < elements.numDimensions(); d++ )
		{
			copySelected( elements.p()[ d ], selectedElements.p()[ d ] );
			copySelected( elements.q()[ d ], selectedElements.q()[ d ] );
		}
		copySelected( elements.w(), selectedElements.w() );
	}

	private static void distinctRandomInts( final Random rnd, final int bound, int[] ints )
	{
		if ( ints.length > bound )
		{
			throw new IllegalArgumentException( "not enough candidates" );
		}
		for ( int count = 0; count < ints.length; )
		{
			final int value = rnd.nextInt( bound );
			if ( !contains( ints, count, value ) )
			{
				ints[ count++ ] = value;
			}
		}
	}

	private static boolean contains( final int[] ints, final int bound, final int value )
	{
		for ( int i = 0; i < bound; i++ )
		{
			if ( ints[ i ] == value )
			{
				return true;
			}
		}
		return false;
	}
}
