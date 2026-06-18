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

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

import mpicbg.models.PointMatch;

public class FlattenedMatches
{
	private final int n;
	private final int capacity;
	private final double[][] p;
	private final double[][] q;
	private final double[] w;

	private boolean weighted = true;

	private int position;
	private int limit;

	public FlattenedMatches( final int numDimensions, final int capacity )
	{
		this.n = numDimensions;
		this.capacity = capacity;
		p = new double[ numDimensions ][ capacity ];
		q = new double[ numDimensions ][ capacity ];
		w = new double[ capacity ];
		position = 0;
		limit = capacity;
	}

	public < P extends PointMatch > FlattenedMatches( final Collection< P > matches )
	{
		this( numDimensions( matches ), matches.size() );
		matches.forEach( this::put );
		flip();
	}

	public int size()
	{
		return limit;
	}

	public int capacity()
	{
		return capacity;
	}

	public int numDimensions()
	{
		return n;
	}

	public double[][] p()
	{
		return p;
	}

	public double[][] q()
	{
		return q;
	}

	public double[] w()
	{
		return w;
	}

	private static < P extends PointMatch > int numDimensions( final Collection< P > matches )
	{
		if ( matches.isEmpty() )
			throw new IllegalArgumentException( "There must be at least one match to determine number of dimensions." );

		return matches.iterator().next().getP1().getL().length;
	}


	// --- weighted() ---

	/**
	 * Returns {@code true} if the weights of the matches should be considered
	 * for model fitting. Returns {@code false} if all weights are (or should be
	 * assumed) {@code =1}.
	 *
	 * @return whether weights should be considered for model fitting
	 */
	public boolean weighted()
	{
		return weighted;
	}

	/**
	 * @param weighted
	 * 		whether weights should be considered for model fitting
	 */
	public void setWeighted( final boolean weighted )
	{
		this.weighted = weighted;
	}


	// --- java.nio.Buffer-like API ---

	public void put( final PointMatch match )
	{
		final double[] l1 = match.getP1().getL();
		final double[] l2 = match.getP2().getL();
		for ( int d = 0; d < n; d++ )
		{
			p[ d ][ position ] = l1[ d ];
			q[ d ][ position ] = l2[ d ];
		}
		w[ position ] = match.getWeight();
		position++;
	}

	// 1D. TODO: should this move to sub-class?
	public void put( final double p, final double q, final double w )
	{
		this.p[ 0 ][ position ] = p;
		this.q[ 0 ][ position ] = q;
		this.w[ position ] = w;
		position++;
	}

	public void flip()
	{
		limit = position;
		position = 0;
	}


	// --- copyOf() ---

	public static FlattenedMatches copyOf( final FlattenedMatches matches, final int newSize )
	{
		return new FlattenedMatches( matches, newSize );
	}

	private FlattenedMatches( final FlattenedMatches other, final int capacity )
	{
		n = other.n;
		weighted = other.weighted;

		this.capacity = capacity;
		p = new double[ n ][];
		Arrays.setAll( p, d -> Arrays.copyOf( other.p[ d ], capacity ) );
		q = new double[ n ][];
		Arrays.setAll( q, d -> Arrays.copyOf( other.q[ d ], capacity ) );
		w = Arrays.copyOf( other.w, capacity );
	}
}
