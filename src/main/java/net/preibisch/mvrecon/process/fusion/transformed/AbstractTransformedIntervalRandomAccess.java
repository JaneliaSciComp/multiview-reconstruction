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
package net.preibisch.mvrecon.process.fusion.transformed;

import net.imglib2.AbstractLocalizableInt;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;

public abstract class AbstractTransformedIntervalRandomAccess extends AbstractLocalizableInt implements RandomAccess< FloatType >
{
	final protected FloatType outside;

	final protected Interval interval;
	final protected InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory;

	final protected int offsetX, offsetY, offsetZ;
	final protected int imgMinX, imgMinY, imgMinZ;
	final protected int imgMaxX, imgMaxY, imgMaxZ;

	final protected FloatType v;

	public AbstractTransformedIntervalRandomAccess(
			final Interval interval, // from ImgLoader
			final InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory,
			final FloatType outside,
			final long[] offset )
	{
		super( interval.numDimensions() );

		this.outside = outside;
		this.interval = interval;
		this.interpolatorFactory = interpolatorFactory;

		this.offsetX = (int)offset[ 0 ];
		this.offsetY = (int)offset[ 1 ];
		this.offsetZ = (int)offset[ 2 ];

		this.imgMinX = (int)interval.min( 0 );
		this.imgMinY = (int)interval.min( 1 );
		this.imgMinZ = (int)interval.min( 2 );

		this.imgMaxX = (int)interval.max( 0 );
		this.imgMaxY = (int)interval.max( 1 );
		this.imgMaxZ = (int)interval.max( 2 );

		this.v = new FloatType();
	}

	protected static final boolean intersectsLinearInterpolation(
			final double x, final double y, final double z,
			final long minX, final long minY, final long minZ,
			final long maxX, final long maxY, final long maxZ )
	{
		// to avoid interpolation artifacts from the outofboundsstrategy,
		// the coordinate has to be bigger than min and smaller than max (assuming linear or NN interpolation)
		if ( x > minX && y > minY && z > minZ && x < maxX && y < maxY && z < maxZ )
			return true;
		else
			return false;
	}

	@Override
	public void fwd( final int d ) { ++position[ d ]; }

	@Override
	public void bck( final int d ) { --position[ d ]; }

	@Override
	public void move( final int distance, final int d ) { position[ d ] += distance; }

	@Override
	public void move( final long distance, final int d ) { position[ d ] += distance; }

	@Override
	public void move( final Localizable localizable )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += localizable.getIntPosition( d );
	}

	@Override
	public void move( final int[] distance )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += distance[ d ];
	}

	@Override
	public void move( final long[] distance )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] += distance[ d ];
	}

	@Override
	public void setPosition( final Localizable localizable )
	{
		localizable.localize( position );
	}

	@Override
	public void setPosition( final int[] pos )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = pos[ d ];
	}

	@Override
	public void setPosition( final long[] pos )
	{
		for ( int d = 0; d < n; ++d )
			position[ d ] = ( int ) pos[ d ];
	}

	@Override
	public void setPosition( final int pos, final int d )
	{
		position[ d ] = pos;
	}

	@Override
	public void setPosition( final long pos, final int d )
	{
		position[ d ] = ( int ) pos;
	}
}
