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

import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.interpolation.InterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.type.numeric.real.FloatType;

public abstract class AbstractTransformedIntervalRandomAccessible implements RandomAccessible< FloatType >
{
	final protected Interval interval;

	final protected FloatType outsideValue;
	final protected long[] boundingBoxOffset;
	final protected Interval boundingBox;

	final protected int n;

	protected InterpolatorFactory< FloatType, RandomAccessible< FloatType > > interpolatorFactory = new NLinearInterpolatorFactory<>();

	public AbstractTransformedIntervalRandomAccessible(
			final Interval interval, // from ImgLoader
			final FloatType outsideValue,
			final Interval boundingBox )
	{
		this.interval = interval;
		this.outsideValue = outsideValue;

		this.n = interval.numDimensions();
		this.boundingBox = boundingBox;
		this.boundingBoxOffset = new long[ n ];

		for ( int d = 0; d < n; ++d )
			this.boundingBoxOffset[ d ] = boundingBox.min( d );
	}

	public void setLinearInterpolation()
	{
		this.interpolatorFactory = new NLinearInterpolatorFactory<>();
	}

	public void setNearestNeighborInterpolation()
	{
		this.interpolatorFactory = new NearestNeighborInterpolatorFactory<>();
	}

	@Override
	public RandomAccess< FloatType > randomAccess( final Interval arg0 )
	{
		return randomAccess();
	}

	@Override
	public int numDimensions()
	{
		return n;
	}

	@Override
	public FloatType getType()
	{
		return new FloatType();
	}
}
