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

import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.type.numeric.real.FloatType;

class MultiplicativeCombiner
{
	public static BlockSupplier< FloatType > create(
			final BlockSupplier< FloatType > bs1,
			final BlockSupplier< FloatType > bs2 )
	{
		return new MultiplicativeCombinerBlockSupplier( bs1, bs2 );
	}

	private static class MultiplicativeCombinerBlockSupplier implements BlockSupplier< FloatType >
	{
		private static final FloatType type = new FloatType();

		final BlockSupplier< FloatType > bs1, bs2;
		
		MultiplicativeCombinerBlockSupplier(
				final BlockSupplier< FloatType > bs1,
				final BlockSupplier< FloatType > bs2 )
		{
			this.bs1 = bs1;
			this.bs2 = bs2;
		}

		@Override
		public FloatType getType() { return type; }

		@Override
		public int numDimensions() { return bs1.numDimensions(); }

		@Override
		public void copy( final Interval interval, final Object dest)
		{
			final float[] weights1 = ( float[] ) dest;
			final float[] weights2 = weights1.clone();

			bs1.copy( interval, weights1 );
			bs2.copy( interval, weights2 );

			for ( int i = 0; i < weights1.length; ++i )
				weights1[ i ] = weights1[ i ] * weights2[ i ];
		}

		@Override
		public BlockSupplier<FloatType> threadSafe()
		{
			return new MultiplicativeCombinerBlockSupplier( bs1.threadSafe(), bs2.threadSafe() );
		}

		@Override
		public BlockSupplier<FloatType> independentCopy()
		{
			return new MultiplicativeCombinerBlockSupplier( bs1.independentCopy(), bs2.independentCopy() );
		}
	}
}
