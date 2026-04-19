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
package net.preibisch.mvrecon.process.deconvolution.normalization;

import java.util.ArrayList;

import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Positionable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPositionable;
import net.imglib2.RealRandomAccess;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.fusion.transformed.weights.BlendingRealRandomAccess;

public class NormalizingRandomAccessibleInterval< T extends RealType< T > > implements RandomAccessibleInterval< T >
{
	final int index;
	final RandomAccessibleInterval< T > interval;
	final ArrayList< RandomAccessibleInterval< FloatType > > originalWeights;
	double osemspeedup;
	final boolean additionalSmoothBlending;
	final float maxDiffRange;
	final float scalingRange;
	final T type;

	public NormalizingRandomAccessibleInterval(
			final RandomAccessibleInterval< T > interval,
			final int index,
			final ArrayList< RandomAccessibleInterval< FloatType > > originalWeights,
			final double osemspeedup,
			final boolean additionalSmoothBlending,
			final float maxDiffRange,
			final float scalingRange,
			final T type )
	{
		// the assumption is that dimensionality & size matches, we do not test it tough
		this.interval = interval;
		this.index = index;
		this.originalWeights = originalWeights;
		this.osemspeedup = osemspeedup;
		this.additionalSmoothBlending = additionalSmoothBlending;
		this.maxDiffRange = maxDiffRange;
		this.scalingRange = scalingRange;
		this.type = type;
	}

	public void setOSEMspeedup( final double osemspeedup ) { this.osemspeedup = osemspeedup; }
	public double getOSEMspeedup() { return osemspeedup; }

	@Override
	public RandomAccess< T > randomAccess()
	{
		return new NormalizingRandomAccess< T >(
				index,
				originalWeights,
				osemspeedup,
				additionalSmoothBlending,
				maxDiffRange,
				scalingRange,
				type );
	}

	@Override
	public int numDimensions() { return interval.numDimensions(); }

	@Override
	public RandomAccess< T > randomAccess( final Interval interval ) { return randomAccess(); }

	@Override
	public T getType()
	{
		return interval.getType();
	}

	@Override
	public long min( final int d ){ return interval.min( 0 ); }

	@Override
	public void min( final long[] min ) { interval.min( min ); }

	@Override
	public void min( final Positionable min ) { interval.min( min ); }

	@Override
	public long max( final int d ) { return interval.max( d ); }

	@Override
	public void max( final long[] max ) { interval.max( max ); }

	@Override
	public void max( final Positionable max ) { interval.max( max ); }

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

	public static void main( String[] args )
	{
		new ImageJ();
		int size = 1000;

		final ArrayList< Img< FloatType > > imgs = new ArrayList<Img< FloatType >>();
		final ArrayList< Cursor< FloatType > > cursors = new ArrayList<Cursor<FloatType>>();
		final ArrayList< Interval > intervals = new ArrayList<Interval>();
		final ArrayList< RealRandomAccess< FloatType > > blends = new ArrayList<RealRandomAccess<FloatType>>();

		final float blending[] = new float[]{ 30, 10 };
		final float border[] = new float[]{ 0, 0 };

		intervals.add( new FinalInterval( new long[]{ 50, 0 }, new long[]{ 450, 300 } ) );
		intervals.add( new FinalInterval( new long[]{ 250, 150 }, new long[]{ 700, 800 } ) );
		intervals.add( new FinalInterval( new long[]{ 350, 50 }, new long[]{ 800, 400 } ) );
/*
		Random rnd = new Random( 35467 );
		for ( int i = 0; i < 5; ++i )
		{
			final int x1 = rnd.nextInt( size )- size/2;
			final int y1 = rnd.nextInt( size )- size/2;

			int x2, y2;
			
			do 
			{
				x2 = rnd.nextInt( size * 2 );
			} while ( x2 <= x1 );
			
			do
			{
				y2 = rnd.nextInt( size * 2  );
			} while ( y2 <= y1 );

			System.out.println( x1 + ", " + y1 + " >>> " + x2 + ", " + y2 );
			intervals.add( new FinalInterval( new long[]{ x1, y1 }, new long[]{ x2, y2 } ) );
		}
*/
		for ( int i = 0; i < intervals.size(); ++i )
		{
			imgs.add( ArrayImgs.floats( size, size ) );
			cursors.add( imgs.get( i ).localizingCursor() );
		}

		for ( final Interval i : intervals )
			blends.add( new BlendingRealRandomAccess( i, border, blending ) );

		while ( cursors.get( 0 ).hasNext() )
		{
			double sumW = 0;

			for ( int i = 0; i < intervals.size(); ++i )
			{
				final Cursor< FloatType > c = cursors.get( i );
				final RealRandomAccess< FloatType > b = blends.get( i );
	
				c.fwd();
				b.setPosition( c );
				final float w = b.get().getRealFloat();
				c.get().set( w );

				if ( w > 0 )
					sumW += w;
			}

			// something in between ... I would say, now we have hard edges where the image stacks end
			NormalizingRandomAccess.applyHard( cursors, sumW );
			//applySmooth( cursors, sumW, maxDiffRange, scalingRange );
		}

		for ( final Img< FloatType > img : imgs )
		{
			ImagePlus imp = ImageJFunctions.show( img );
			imp.resetDisplayRange();
			imp.updateAndDraw();
		}
	}

}
