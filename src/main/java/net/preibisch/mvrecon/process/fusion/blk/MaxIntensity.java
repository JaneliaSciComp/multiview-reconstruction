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
package net.preibisch.mvrecon.process.fusion.blk;

import static net.imglib2.type.PrimitiveType.BYTE;
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
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;

class MaxIntensity
{
	public static BlockSupplier< FloatType > of(
			final List< BlockSupplier< FloatType > > images,
			final List< BlockSupplier< UnsignedByteType > > masks,
			final Overlap overlap )
	{
		return new MaxIntensityBlockSupplier( images, masks, overlap );
	}

	private static class MaxIntensityBlockSupplier extends AbstractBlockSupplier< FloatType >
	{
		private final int numDimensions;

		private final List< BlockSupplier< FloatType > > images;

		private final List< BlockSupplier< UnsignedByteType > > masks;

		private final Overlap overlap;

		private final TempArray< byte[] > tempArrayM;

		private final TempArray< float[] > tempArrayI;

		MaxIntensityBlockSupplier(
				final List< BlockSupplier< FloatType > > images,
				final List< BlockSupplier< UnsignedByteType > > masks,
				final Overlap overlap )
		{
			this.numDimensions = images.get( 0 ).numDimensions();
			this.images = images;
			this.masks = masks;
			this.overlap = overlap;
			tempArrayM = TempArray.forPrimitiveType( BYTE );
			tempArrayI = TempArray.forPrimitiveType( FLOAT );
		}

		private MaxIntensityBlockSupplier( final MaxIntensityBlockSupplier s )
		{
			numDimensions = s.numDimensions;
			images = new ArrayList<>( s.images.size() );
			masks = new ArrayList<>( s.masks.size() );
			s.images.forEach( i -> images.add( i.independentCopy() ) );
			s.masks.forEach( i -> masks.add( i.independentCopy() ) );
			overlap = s.overlap;
			tempArrayM = TempArray.forPrimitiveType( BYTE );
			tempArrayI = TempArray.forPrimitiveType( FLOAT );
		}

		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final BlockInterval blockInterval = BlockInterval.asBlockInterval( interval );
			final long[] srcPos = blockInterval.min();
			final int[] size = blockInterval.size();

			final int len = safeInt( Intervals.numElements( size ) );

			final byte[] tmpM = tempArrayM.get( len );
			final float[] tmpI = tempArrayI.get( len );
			final float[] fdest = Cast.unchecked( dest );

			final long[] srcMax = new long[ srcPos.length ];
			Arrays.setAll( srcMax, d -> srcPos[ d ] + size[ d ] - 1 );
			final int[] overlapping = overlap.getOverlappingViewIndices( srcPos, srcMax );
			boolean first = true;
			for ( int i : overlapping )
			{
				masks.get( i ).copy( blockInterval, tmpM );
				images.get( i ).copy( blockInterval, tmpI );
				if ( first )
				{
					first = false;
					for ( int x = 0; x < len; ++x )
						fdest[ x ] = tmpM[ x ] == 1 ? tmpI[ x ] : 0;
				}
				else
				{
					for ( int x = 0; x < len; ++x )
						if ( tmpM[ x ] == 1 )
							fdest[ x ] = Math.max( fdest[ x ], tmpI[ x ] );
				}
			}
		}

		@Override
		public BlockSupplier< FloatType > independentCopy()
		{
			return new MaxIntensityBlockSupplier( this );
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
