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
import net.imglib2.type.numeric.real.FloatType;

class Blending
{
	/**
	 * Conceptually,the given {@code interval} is filled with blending weights, then transformed with {@code transform}.
	 * <p>
	 * Blending weights are {@code 0 <= w <= 1}.
	 * <p>
	 * Weights are {@code w=0} for the outermost {@code border} pixels of {@code interval} (and outside of {@code interval}).
	 * Then weights transition from {@code 0<=w<=1} over {@code blending} pixels.
	 * Weights are {@code w=1} inside {@code border+blending} from the {@code interval} bounds.
	 *
	 * @param interval
	 * @param border
	 * @param blending
	 * @param transform
	 */
	public static BlockSupplier< FloatType > create(
			final Interval interval,
			final float[] border,
			final float[] blending,
			final AffineTransform3D transform )
	{
		return new BlendingBlockSupplier( interval, border, blending, transform );
	}

	private static class BlendingBlockSupplier implements BlockSupplier< FloatType >
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
		 */
		private final float[] b0 = new float[ n ];

		/**
		 * min border+blend distance.
		 * for {@code b0<x<b1: w(x)=fn(x-b0)}.
		 */
		private final float[] b1 = new float[ n ];

		/**
		 * max border+blend distance.
		 * for {@code b1<x<b2: w(x)=1}.
		 */
		private final float[] b2 = new float[ n ];

		/**
		 * max border distance.
		 * for {@code b2<x<b3: w(x)=fn(b3-x)}.
		 * for {@code b3<x: w(x)=0}.
		 */
		private final float[] b3 = new float[ n ];

		private final float[] blending;

		/**
		 * Conceptually,the given {@code interval} is filled with blending weights, then transformed with {@code transform}.
		 * <p>
		 * Blending weights are {@code 0 <= w <= 1}.
		 * <p>
		 * Weights are {@code w=0} for the outermost {@code border} pixels of {@code interval} (and outside of {@code interval}).
		 * Then weights transition from {@code 0<=w<=1} over {@code blending} pixels.
		 * Weights are {@code w=1} inside {@code border+blending} from the {@code interval} bounds.
		 *
		 * @param interval
		 * @param border
		 * @param blending
		 * @param transform
		 */
		BlendingBlockSupplier(
				final Interval interval,
				final float[] border,
				final float[] blending,
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
				b1[ d ] = border[ d ] + blending[ d ];
				b2[ d ] = dim - 1 - border[ d ] - blending[ d ];
				b3[ d ] = dim - 1 - border[ d ];

				if ( b1[ d ] > b2[ d ] ) // there is no "inside region" where w=1
				{
					b1[ d ] = ( b1[ d ] + b2[ d ] ) / 2;
					b2[ d ] = b1[ d ];
				}

				// TODO handle the case where border is so big that w=0 everywhere
			}

			this.blending = blending.clone();
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

			final float[] weights = ( float[] ) dest;
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
		public BlockSupplier< FloatType > threadSafe()
		{
			return this;
		}

		@Override
		public BlockSupplier< FloatType > independentCopy()
		{
			return this;
		}

		@Override
		public int numDimensions()
		{
			// TODO: do we need 2D ????
			return n;
		}

		private static final FloatType type = new FloatType();

		@Override
		public FloatType getType()
		{
			return type;
		}

		private static final float EPSILON = 0.0001f;

		private void fill_range(
				float[] weights,
				final int offset,
				final int length,
				double[] transformed_start_pos )
		{
			final double[] pos = new double[ n ];
			t.applyInverse( pos, transformed_start_pos );
			Arrays.fill( weights, offset, offset + length, 1 );
			int from = 0;
			int to = length;
			for ( int d = 0; d < 3; ++d )
			{
				final float l0 = ( float ) pos[ d ];
				final float dd = ( float ) d0[ d ];

				final float blend;
				final float b0d;
				final float b1d;
				final float b2d;
				final float b3d;
				if ( dd > EPSILON )
				{
					blend = blending[ d ] / dd;
					b0d = ( b0[ d ] - l0 ) / dd;
					b1d = ( b1[ d ] - l0 ) / dd;
					b2d = ( b2[ d ] - l0 ) / dd;
					b3d = ( b3[ d ] - l0 ) / dd;
				}
				else if ( dd < -EPSILON )
				{
					blend = blending[ d ] / -dd;
					b0d = ( b3[ d ] - l0 ) / dd;
					b1d = ( b2[ d ] - l0 ) / dd;
					b2d = ( b1[ d ] - l0 ) / dd;
					b3d = ( b0[ d ] - l0 ) / dd;
				}
				else
				{
					final float const_weight = computeWeight( l0, blending[ d ], b0[ d ], b1[ d ], b2[ d ], b3[ d ] );
					for ( int x = 0; x < length; ++x )
						weights[ offset + x ] *= const_weight;
					continue;
				}

				final int b3di = Math.max( from, Math.min( to, 1 + ( int ) Math.floor( b3d ) ) );
				final int b2di = Math.max( from, Math.min( b3di, 1 + ( int ) Math.floor( b2d ) ) );
				final int b1di = Math.max( from, Math.min( b2di, 1 + ( int ) Math.floor( b1d ) ) );
				final int b0di = Math.max( from, Math.min( b1di, 1 + ( int ) Math.floor( b0d ) ) );

				for ( int x = from; x < b0di; ++x )
					weights[ offset + x ] = 0;
				for ( int x = b0di; x < b1di; ++x )
					weights[ offset + x ] *= Lookup.get( ( x - b0d ) / blend );
				for ( int x = b2di; x < b3di; ++x )
					weights[ offset + x ] *= Lookup.get( ( b3d - x ) / blend );
				for ( int x = b3di; x < to; ++x )
					weights[ offset + x ] = 0;

				from = b0di;
				to = b3di;
			}
		}

		private static float computeWeight(
				final float l,
				final float blending,
				final float b0,
				final float b1,
				final float b2,
				final float b3 )
		{
			if ( l < b0 )
				return 0;
			else if ( l < b1 )
				return Lookup.get( ( l - b0 ) / blending );
			else if ( l < b2 )
				return 1;
			else if ( l < b3 )
				return Lookup.get( ( b3 - l ) / blending );
			else
				return 0;
		}

		/**
		 * Lookup table for blending weight function
		 * {@code fn(x) = (Math.cos((1 - x) * Math.PI) + 1) / 2}
		 */
		private static final class Lookup
		{
			private static final int n = 30;

			// static lookup table for the blending function
			// size of the array is n + 2
			private static final float[] lookUp = createLookup( n );

			private static float[] createLookup( final int n )
			{
				final float[] lookup = new float[ n + 2 ];
				for ( int i = 0; i <= n; i++ )
				{
					final double d = ( double ) i / n;
					lookup[ i ] = ( float ) ( ( Math.cos( ( 1 - d ) * Math.PI ) + 1 ) / 2 );
				}
				lookup[ n + 1 ] = lookup[ n ];
				return lookup;
			}

			static float get( final float d )
			{
				final int i = ( int ) ( d * n );
				final float s = ( d * n ) - i;
				return lookUp[ i ] * (1.0f - s) + lookUp[ i + 1 ] * s;
			}
		}
	}
}
