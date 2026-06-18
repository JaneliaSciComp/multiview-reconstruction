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

import java.util.List;

import net.imglib2.Interval;
import net.imglib2.Positionable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPositionable;
import net.imglib2.type.numeric.real.FloatType;

public class FusedRandomAccessibleInterval implements RandomAccessibleInterval< FloatType >
{
	public enum Fusion {AVG, MAX, FIRST_WINS};

	final int n;

	final Interval interval;
	final List< ? extends RandomAccessible< FloatType > > images;
	final List< ? extends RandomAccessible< FloatType > > weights;

	public Fusion fusion = Fusion.AVG;

	public FusedRandomAccessibleInterval(
			final Interval interval,
			final Fusion fusion,
			final List< ? extends RandomAccessible< FloatType > > images,
			final List< ? extends RandomAccessible< FloatType > > weights )
	{
		this.n = interval.numDimensions();
		this.interval = interval;
		this.images = images;
		this.fusion = fusion;

		if ( weights == null || weights.size() == 0 )
		{
			this.weights = null;
		}
		else
		{
			this.weights = weights;
			if ( this.images.size() != this.weights.size() )
				throw new RuntimeException( "Images and weights do not have the same size: " + images.size() + " != " + weights.size() );
		}
	}

	public FusedRandomAccessibleInterval(
			final Interval interval,
			final Fusion fusion,
			final List< ? extends RandomAccessible< FloatType > > images )
	{
		this( interval, fusion, images, null );
	}

	public Interval getInterval() { return interval; }
	public List< ? extends RandomAccessible< FloatType > > getImages() { return images; }
	public List< ? extends RandomAccessible< FloatType > > getWeights() { return weights; }

	@Override
	public int numDimensions()
	{
		return n;
	}

	public void setFusion( final Fusion fusion ) { this.fusion = fusion; }
	public Fusion getFusion() { return fusion; }

	@Override
	public RandomAccess< FloatType > randomAccess()
	{
		if( fusion == Fusion.AVG )
		{
			if ( weights == null )
				return new FusedRandomAccessNoWeights( n, images );
			else
				return new FusedRandomAccess( n, images, weights );
		}
		else if ( fusion == Fusion.MAX )
		{
			return new FusedRandomAccessMax( n, images );
		}
		else
		{
			return new FusedRandomAccessFirstWins( n, images, weights );
		}
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
	public long max( final int d ) { return interval.max( d ); }

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
