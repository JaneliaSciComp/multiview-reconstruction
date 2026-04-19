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

import java.util.ArrayList;
import java.util.List;

import net.imglib2.Interval;
import net.imglib2.Positionable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPositionable;
import net.imglib2.type.numeric.real.FloatType;

public class CombineWeightsRandomAccessibleInterval implements RandomAccessibleInterval< FloatType >
{
	public enum CombineType { SUM, MUL }
	final int n;

	final Interval interval;
	final List< ? extends RandomAccessible< FloatType > > weights;
	final CombineType combineType;

	/**
	 * Combines N randomaccessibles in a defined interval by multiplication or average
	 * 
	 * @param interval - the Interval for combining
	 * @param weights - the weights
	 * @param combineType - how to combine them
	 */
	public CombineWeightsRandomAccessibleInterval(
			final Interval interval,
			final List< ? extends RandomAccessible< FloatType > > weights,
			final CombineType combineType )
	{
		this.n = interval.numDimensions();
		this.interval = interval;
		this.weights = weights;
		this.combineType = combineType;
	}

	/**
	 * Combines two randomaccessibles in a defined interval by multiplication or average
	 * 
	 * @param interval - the Interval for combining
	 * @param weight1 - the first weight image
	 * @param weight2 - the second weight image
	 * @param combineType - how to combine them
	 */
	public CombineWeightsRandomAccessibleInterval(
			final Interval interval,
			final RandomAccessible< FloatType > weight1,
			final RandomAccessible< FloatType > weight2,
			final CombineType combineType )
	{
		this( interval, combine( weight1, weight2 ), combineType );
	}

	private static final List< ? extends RandomAccessible< FloatType > > combine(
			final RandomAccessible< FloatType > weight1,
			final RandomAccessible< FloatType > weight2 )
	{
		final ArrayList< RandomAccessible< FloatType > > list = new ArrayList<>();
		list.add( weight1 );
		list.add( weight2 );
		return list;
	}

	public List< ? extends RandomAccessible< FloatType > > getWeights() { return weights; }

	@Override
	public int numDimensions()
	{
		return n;
	}

	@Override
	public RandomAccess< FloatType > randomAccess()
	{
		if ( combineType == CombineType.SUM )
			return new CombineWeightsSumRandomAccess( n, weights );
		else
			return new CombineWeightsMulRandomAccess( n, weights );
	}

	@Override
	public RandomAccess< FloatType > randomAccess( Interval interval )
	{
		return randomAccess();
	}

	@Override
	public FloatType getType()
	{
		return new FloatType();
	}

	@Override
	public long min( final int d ) { return interval.min( d ); }

	@Override
	public void min( final long[] min ) { interval.min( min ); }

	@Override
	public void min( final Positionable min ) { interval.min( min ); }

	@Override
	public long max( final int d ) { return interval.min( d ); }

	@Override
	public void max( final long[] max ) { interval.max( max ); }

	@Override
	public void max( final Positionable max )  { interval.max( max ); }

	@Override
	public double realMin( final int d ) { return interval.realMin( d ); }

	@Override
	public void realMin( final double[] min ) { interval.realMin( min ); }

	@Override
	public void realMin( final RealPositionable min ) { interval.realMin( min ); }

	@Override
	public double realMax( final int d ) { return interval.realMax( d ); }

	@Override
	public void realMax( final double[] max ) { interval.realMax( max ); }

	@Override
	public void realMax( final RealPositionable max ) { interval.realMax( max ); }

	@Override
	public void dimensions( final long[] dimensions ) { interval.dimensions( dimensions ); }

	@Override
	public long dimension( final int d ) { return interval.dimension( d ); }
}
