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
package net.preibisch.mvrecon.process.fusion.transformed.weightcombination;

import java.util.List;

import net.imglib2.AbstractLocalizableInt;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

public abstract class CombineWeightsRandomAccess extends AbstractLocalizableInt implements RandomAccess< FloatType >
{
	final List< ? extends RandomAccessible< FloatType > > weights;
	final int numImages;
	final RandomAccess< ? extends RealType< ? > >[] w;

	final FloatType value = new FloatType();

	public CombineWeightsRandomAccess(
			final int n,
			final List< ? extends RandomAccessible< FloatType > > weights )
	{
		super( n );

		this.weights = weights;
		this.numImages = weights.size();
		this.w = new RandomAccess[ numImages ];

		for ( int j = 0; j < numImages; ++j )
			this.w[ j ] = weights.get( j ).randomAccess();
	}

	@Override
	public CombineWeightsRandomAccess copy()
	{
		return copyRandomAccess();
	}

	@Override
	public abstract CombineWeightsRandomAccess copyRandomAccess();

	@Override
	public void fwd( final int d )
	{
		++position[ d ];

		for ( int j = 0; j < numImages; ++j )
			w[ j ].fwd( d );
	}

	@Override
	public void bck( final int d )
	{
		--position[ d ];

		for ( int j = 0; j < numImages; ++j )
			w[ j ].bck( d );
	}

	@Override
	public void move( final int distance, final int d )
	{
		position[ d ] += distance;

		for ( int j = 0; j < numImages; ++j )
			w[ j ].move( distance, d );
	}

	@Override
	public void move( final long distance, final int d )
	{
		position[ d ] += distance;

		for ( int j = 0; j < numImages; ++j )
			w[ j ].move( distance, d );
	}

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += localizable.getIntPosition( d );

		for ( int j = 0; j < numImages; ++j )
			w[ j ].move( localizable );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += distance[ d ];

		for ( int j = 0; j < numImages; ++j )
			w[ j ].move( distance );
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += distance[ d ];

		for ( int j = 0; j < numImages; ++j )
			w[ j ].move( distance );
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		localizable.localize( position );

		for ( int j = 0; j < numImages; ++j )
			w[ j ].setPosition( localizable );
	}

	@Override
	public void setPosition( final int[] pos )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = pos[ d ];

		for ( int j = 0; j < numImages; ++j )
			w[ j ].setPosition( pos );
	}

	@Override
	public void setPosition( final long[] pos )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = ( int ) pos[ d ];

		for ( int j = 0; j < numImages; ++j )
			w[ j ].setPosition( pos );
	}

	@Override
	public void setPosition( final int pos, final int d )
	{
		position[ d ] = pos;

		for ( int j = 0; j < numImages; ++j )
			w[ j ].setPosition( pos, d );
	}

	@Override
	public void setPosition( final long pos, final int d )
	{
		position[ d ] = ( int ) pos;

		for ( int j = 0; j < numImages; ++j )
			w[ j ].setPosition( pos, d );
	}
}
