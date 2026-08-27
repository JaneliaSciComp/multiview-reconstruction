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

import java.util.Arrays;

import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;

class Distance
{
	/**
	 * Conceptually, the given {@code interval} is transformed with {@code transform}.
	 * Every pixel inside the transformed interval reports the <em>squared</em> Euclidean
	 * distance (measured in target/fused coordinates) to the transformed center of
	 * {@code interval}. Pixels outside report {@code Float.POSITIVE_INFINITY}.
	 * <p>
	 * Squared distance is monotonic in distance, so it can be compared directly to decide
	 * which view's center is closest (see {@link ClosestPixelWins}), while avoiding a
	 * per-pixel {@code Math.sqrt}.
	 *
	 * @param interval
	 * @param transform
	 */
	public static BlockSupplier< FloatType > create(
			final Interval interval,
			final AffineTransform3D transform )
	{
		return new DistanceBlockSupplier( interval, transform );
	}

	private static class DistanceBlockSupplier implements BlockSupplier< FloatType >
	{
		private final AffineTransform3D t;

		/**
		 * constant partial differential vector of t in X.
		 */
		private final double[] d0;

		private final int n = 3;

		/**
		 * min interval bound.
		 * for {@code x<b0: d(x)=Float.POSITIVE_INFINITY}.
		 * for {@code b0<x<b3: d(x)=squared distance to center}.
		 */
		private final float[] b0 = new float[ n ];

		/**
		 * max interval bound.
		 * for {@code b0<x<b3: d(x)=squared distance to center}.
		 * for {@code b3<x: d(x)=Float.POSITIVE_INFINITY}.
		 */
		private final float[] b3 = new float[ n ];

		/**
		 * center of {@code interval}, transformed to target coordinates.
		 */
		private final double[] center = new double[ n ];

		/**
		 * Conceptually, the given {@code interval} is transformed with {@code transform}.
		 * Every pixel inside the transformed interval reports the <em>squared</em> Euclidean
		 * distance (measured in target/fused coordinates) to the transformed center of
		 * {@code interval}. Pixels outside report {@code Float.POSITIVE_INFINITY}.
		 *
		 * @param interval
		 * @param transform
		 */
		DistanceBlockSupplier(
				final Interval interval,
				final AffineTransform3D transform )
		{
			// concatenate shift-to-interval-min to transform
			t = new AffineTransform3D();
			t.translate( interval.minAsDoubleArray() );
			t.preConcatenate( transform );

			d0 = t.inverse().d( 0 ).positionAsDoubleArray();

			final double[] localCenter = new double[ n ];
			for ( int d = 0; d < n; ++d )
			{
				final int dim = ( int ) interval.dimension( d );

				localCenter[ d ] = ( dim - 1 ) / 2.0;

				if ( dim <= 1 )
				{
					// degenerate (e.g. a 2d image's z dimension): the single valid pixel
					// must always be inside (b0=0 <= l0 < b3=dim). Otherwise b0=0, b3=dim-1
					// collapse and every pixel would incorrectly report infinite distance.
					b0[ d ] = 0;
					b3[ d ] = dim;
					continue;
				}

				b0[ d ] = 0;
				b3[ d ] = dim - 1;
			}

			t.apply( localCenter, center );
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
					// this either sets everything to infinity, or nothing.
					if ( l0 < b0[ d ] || l0 >= b3[ d ] )
					{
						Arrays.fill( weights, offset, offset + length, Float.POSITIVE_INFINITY );
						return;
					}
					continue;
				}

				b3di = Math.max( b0di, Math.min( b3di, 1 + ( int ) Math.floor( b3d ) ) );
				b0di = Math.max( b0di, Math.min( b3di, 1 + ( int ) Math.floor( b0d ) ) );
			}

			Arrays.fill( weights, offset, offset + b0di, Float.POSITIVE_INFINITY );
			Arrays.fill( weights, offset + b3di, offset + length, Float.POSITIVE_INFINITY );

			// pixel x sits at target position (p[0]+x, p[1], p[2]), so the squared
			// distance to the (transformed) center is quadratic in x with a per-row
			// constant contribution q of the y and z dimensions
			final float dy = ( float ) ( transformed_start_pos[ 1 ] - center[ 1 ] );
			final float dz = ( float ) ( transformed_start_pos[ 2 ] - center[ 2 ] );
			final float q = dy * dy + dz * dz;
			final float dx0 = ( float ) ( transformed_start_pos[ 0 ] - center[ 0 ] );
			for ( int x = b0di; x < b3di; ++x )
			{
				final float dx = dx0 + x;
				weights[ offset + x ] = dx * dx + q;
			}
		}
	}
}
