/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.process.fusion.blk;

import static net.imglib2.type.PrimitiveType.FLOAT;
import static net.imglib2.type.PrimitiveType.INT;
import static net.imglib2.util.Util.safeInt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.AbstractBlockSupplier;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.blocks.TempArray;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.preibisch.mvrecon.process.fusion.blk.tps.TpsPositionField.DistanceBlocks;

class ClosestPixelWins
{
	/**
	 * Per pixel, the view with the smallest {@code distance} (squared distance to the
	 * view center, see {@link Distance}) wins. Views report
	 * {@code Float.POSITIVE_INFINITY} outside their bounds, so they never win there.
	 */
	public static BlockSupplier< FloatType > of(
			final List< BlockSupplier< FloatType > > images,
			final List< BlockSupplier< FloatType > > distances,
			final Overlap overlap )
	{
		return new ClosestPixelWinsBlockSupplier( images, distances, overlap );
	}

	private static class ClosestPixelWinsBlockSupplier extends AbstractBlockSupplier< FloatType >
	{
		private final int numDimensions;

		private final List< BlockSupplier< FloatType > > images;

		private final List< BlockSupplier< FloatType > > distances;

		private final Overlap overlap;

		private final TempArray< float[] >[] tempArrays;

		private final TempArray< int[] > tempWinner = TempArray.forPrimitiveType( INT );

		ClosestPixelWinsBlockSupplier(
				final List< BlockSupplier< FloatType > > images,
				final List< BlockSupplier< FloatType > > distances,
				final Overlap overlap )
		{
			this.numDimensions = images.get( 0 ).numDimensions();
			this.images = images;
			this.distances = distances;
			this.overlap = overlap;
			tempArrays = Cast.unchecked( new TempArray[ 3 ] );
			Arrays.setAll( tempArrays, i -> TempArray.forPrimitiveType( FLOAT ) );
		}

		private ClosestPixelWinsBlockSupplier( final ClosestPixelWinsBlockSupplier s )
		{
			numDimensions = s.numDimensions;
			images = new ArrayList<>( s.images.size() );
			distances = new ArrayList<>( s.distances.size() );
			s.images.forEach( i -> images.add( i.independentCopy() ) );
			s.distances.forEach( i -> distances.add( i.independentCopy() ) );
			overlap = s.overlap;
			tempArrays = Cast.unchecked( new TempArray[ 3 ] );
			Arrays.setAll( tempArrays, i -> TempArray.forPrimitiveType( FLOAT ) );
		}

		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final BlockInterval blockInterval = BlockInterval.asBlockInterval( interval );
			final long[] srcPos = blockInterval.min();
			final int[] size = blockInterval.size();

			final int len = safeInt( Intervals.numElements( size ) );
			final float[] tmpI = tempArrays[ 0 ].get( len );
			final float[] tmpD = tempArrays[ 1 ].get( len );
			final float[] minD = tempArrays[ 2 ].get( len );
			final float[] fdest = Cast.unchecked( dest );

			Arrays.fill( fdest, 0 );
			Arrays.fill( minD, Float.POSITIVE_INFINITY );

			final long[] srcMax = new long[ srcPos.length ];
			Arrays.setAll( srcMax, d -> srcPos[ d ] + size[ d ] - 1 );
			final int[] overlapping = candidates( interval, overlap.getOverlappingViewIndices( srcPos, srcMax ) );

			// a single candidate that covers the interval wins every pixel
			if ( overlapping.length == 1 && covers( interval, overlapping[ 0 ] ) )
			{
				images.get( overlapping[ 0 ] ).copy( interval, fdest );
				return;
			}
			// winner per pixel first (the first view wins ties), then read only the images of views that win a pixel
			final int[] winner = tempWinner.get( len );
			Arrays.fill( winner, 0, len, -1 );
			for ( int i : overlapping )
			{
				distances.get( i ).copy( interval, tmpD );
				for ( int x = 0; x < len; ++x )
				{
					if ( tmpD[ x ] < minD[ x ] )
					{
						minD[ x ] = tmpD[ x ];
						winner[ x ] = i;
					}
				}
			}

			for ( int i : overlapping )
			{
				int x0 = 0;
				while ( x0 < len && winner[ x0 ] != i )
					++x0;
				if ( x0 == len )
					continue;
				images.get( i ).copy( interval, tmpI );
				for ( int x = x0; x < len; ++x )
					if ( winner[ x ] == i )
						fdest[ x ] = tmpI[ x ];
			}
		}

		private final double[] range = new double[ 2 ];

		/** whether the view has a finite distance at every pixel of {@code interval}, i.e. contains it */
		private boolean covers( final Interval interval, final int view )
		{
			final BlockSupplier< FloatType > d = distances.get( view );
			if ( !( d instanceof DistanceBlocks ) )
				return false;
			( ( DistanceBlocks ) d ).range( interval, range );
			return range[ 1 ] < Double.POSITIVE_INFINITY;
		}

		/**
		 * The views that can win a pixel of {@code interval}: drops views outside of it and views whose distances
		 * (bounded by {@link DistanceBlocks#range}) are all larger than all distances of another view.
		 */
		private int[] candidates( final Interval interval, final int[] overlapping )
		{
			final double[] min = new double[ overlapping.length ], max = new double[ overlapping.length ];
			double best = Double.POSITIVE_INFINITY; // smallest max
			for ( int k = 0; k < overlapping.length; ++k )
			{
				final BlockSupplier< FloatType > d = distances.get( overlapping[ k ] );
				if ( d instanceof DistanceBlocks )
				{
					( ( DistanceBlocks ) d ).range( interval, range );
					min[ k ] = range[ 0 ];
					max[ k ] = range[ 1 ];
				}
				else
					max[ k ] = Double.POSITIVE_INFINITY; // unknown: any distance
				best = Math.min( best, max[ k ] );
			}
			best *= 1 + 1e-4; // more than the rounding of the float distances
			int n = 0;
			for ( int k = 0; k < overlapping.length; ++k )
				if ( min[ k ] <= best && min[ k ] < Double.POSITIVE_INFINITY )
					overlapping[ n++ ] = overlapping[ k ];
			return n == overlapping.length ? overlapping : Arrays.copyOf( overlapping, n );
		}

		@Override
		public BlockSupplier< FloatType > independentCopy()
		{
			return new ClosestPixelWinsBlockSupplier( this );
		}

		@Override
		public int numDimensions()
		{
			return numDimensions;
		}

		private static final FloatType type = new FloatType();

		@Override
		public FloatType getType()
		{
			return type;
		}
	}
}
