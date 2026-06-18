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

import java.util.Arrays;

import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;

class Masking
{
	/**
	 * Conceptually,the given {@code interval} is filled with masking weights, then transformed with {@code transform}.
	 * <p>
	 * Weights are {@code w=0} for the outermost {@code border} pixels of {@code interval}.
	 * Weights are {@code w=1} inside {@code border} from the {@code interval} bounds.
	 *
	 * @param interval
	 * @param border
	 * @param transform
	 */
	public static BlockSupplier< UnsignedByteType > create(
			final Interval interval,
			final float[] border,
			final AffineTransform3D transform )
	{
		return new MaskingBlockSupplier( interval, border, transform );
	}

	private static class MaskingBlockSupplier implements BlockSupplier< UnsignedByteType >
	{
		private final AffineTransform3D t;

		/**
		 * constant partial differential vector of t in X.
		 */
		private final double[] d0;

		private final int n = 3;

		/**
		 * min border distance.
		 * for {@code x<b0: w(x)=0}.
		 * for {@code b0<x<b3: w(x)=1}.
		 */
		private final float[] b0 = new float[ n ];

		/**
		 * max border distance.
		 * for {@code b0<x<b3: w(x)=1}.
		 * for {@code b3<x: w(x)=0}.
		 */
		private final float[] b3 = new float[ n ];

		/**
		 * Conceptually,the given {@code interval} is filled with masking weights, then transformed with {@code transform}.
		 * <p>
		 * Weights are {@code w=0} for the outermost {@code border} pixels of {@code interval}.
		 * Weights are {@code w=1} inside {@code border} from the {@code interval} bounds.
		 *
		 * @param interval
		 * @param border
		 * @param transform
		 */
		MaskingBlockSupplier(
				final Interval interval,
				final float[] border,
				final AffineTransform3D transform )
		{
			// concatenate shift-to-interval-min to transform
			t = new AffineTransform3D();
			t.translate( interval.minAsDoubleArray() );
			t.preConcatenate( transform );

			d0 = t.inverse().d( 0 ).positionAsDoubleArray();

			for ( int d = 0; d < n; ++d )
			{
				final int dim = ( int ) interval.dimension( d );
				b0[ d ] = border[ d ];
				b3[ d ] = dim - 1 - border[ d ];

				// TODO handle the case where border is so big that w=0 everywhere
			}
		}

		/**
		 * @param interval
		 * 		the block to copy
		 * @param dest
		 *        {@code float[]} array to copy into.
		 */
		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final BlockInterval blockInterval = BlockInterval.asBlockInterval( interval );
			final long[] srcPos = blockInterval.min();
			final int[] size = blockInterval.size();

			final byte[] weights = ( byte[] ) dest;
			final long x0 = srcPos[ 0 ];
			final long y0 = srcPos[ 1 ];
			final long z0 = srcPos[ 2 ];
			final int sx = size[ 0 ];
			final int sy = size[ 1 ];
			final int sz = size[ 2 ];
			final double[] p = { x0, 0, 0 };
			for ( int z = 0; z < sz; ++z )
			{
				p[ 2 ] = z + z0;
				for ( int y = 0; y < sy; ++y )
				{
					p[ 1 ] = y + y0;
					final int offset = ( z * sy + y ) * sx;
					fill_range( weights, offset, sx, p );
				}
			}
		}

		@Override
		public BlockSupplier< UnsignedByteType > threadSafe()
		{
			return this;
		}

		@Override
		public BlockSupplier< UnsignedByteType > independentCopy()
		{
			return this;
		}

		@Override
		public int numDimensions()
		{
			// TODO: do we need 2D ????
			return n;
		}

		private static final UnsignedByteType type = new UnsignedByteType();

		@Override
		public UnsignedByteType getType()
		{
			return type;
		}

		private static final float EPSILON = 0.0001f;

		private void fill_range(
				byte[] weights,
				final int offset,
				final int length,
				double[] transformed_start_pos )
		{
			final double[] pos = new double[ n ];
			t.applyInverse( pos, transformed_start_pos );
			int b0di = 0;
			int b3di = length;
			for ( int d = 0; d < 3; ++d )
			{
				final float l0 = ( float ) pos[ d ];
				final float dd = ( float ) d0[ d ];

				final float b0d;
				final float b3d;
				if ( dd > EPSILON )
				{
					b0d = ( b0[ d ] - l0 ) / dd;
					b3d = ( b3[ d ] - l0 ) / dd;
				}
				else if ( dd < -EPSILON )
				{
					b0d = ( b3[ d ] - l0 ) / dd;
					b3d = ( b0[ d ] - l0 ) / dd;
				}
				else
				{
					// this either sets everything to 0, or nothing.
					if ( l0 < b0[ d ] || l0 >= b3[ d ] )
					{
						Arrays.fill( weights, offset, offset + length, ( byte ) 0 );
						return;
					}
					continue;
				}

				b3di = Math.max( b0di, Math.min( b3di, 1 + ( int ) Math.floor( b3d ) ) );
				b0di = Math.max( b0di, Math.min( b3di, 1 + ( int ) Math.floor( b0d ) ) );
			}

			Arrays.fill( weights, offset, offset + b0di, ( byte ) 0 );
			Arrays.fill( weights, offset + b0di, offset + b3di, ( byte ) 1 );
			Arrays.fill( weights, offset + b3di, offset + length, ( byte ) 0 );
		}
	}
}
