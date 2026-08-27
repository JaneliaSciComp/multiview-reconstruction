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
			final int[] overlapping = overlap.getOverlappingViewIndices( srcPos, srcMax );
			for ( int i : overlapping )
			{
				images.get( i ).copy( interval, tmpI );
				distances.get( i ).copy( interval, tmpD );
				for ( int x = 0; x < len; ++x )
				{
					if ( tmpD[ x ] < minD[ x ] )
					{
						minD[ x ] = tmpD[ x ];
						fdest[ x ] = tmpI[ x ];
					}
				}
			}
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
