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

import net.imglib2.algorithm.blocks.AbstractDimensionlessBlockProcessor;
import net.imglib2.algorithm.blocks.BlockProcessor;
import net.imglib2.algorithm.blocks.DefaultUnaryBlockOperator;
import net.imglib2.algorithm.blocks.UnaryBlockOperator;
import net.imglib2.type.PrimitiveType;
import net.imglib2.type.numeric.real.FloatType;

class LinearRange
{
	public static UnaryBlockOperator< FloatType, FloatType > linearRange( final float scale, final float offset )
	{
		final FloatType type = new FloatType();
		return new DefaultUnaryBlockOperator<>( type, type, 0, 0, new LinearRangeBlockProcessor( scale, offset ) );
	}

	private static class LinearRangeBlockProcessor extends AbstractDimensionlessBlockProcessor< float[], float[] >
	{
		private final float scale;

		private final float offset;

		LinearRangeBlockProcessor( final float scale, final float offset )
		{
			super( PrimitiveType.FLOAT );
			this.scale = scale;
			this.offset = offset;
		}

		protected LinearRangeBlockProcessor( LinearRangeBlockProcessor proc )
		{
			super( proc );
			this.scale = proc.scale;
			this.offset = proc.offset;
		}

		@Override
		public BlockProcessor< float[], float[] > independentCopy()
		{
			return new LinearRangeBlockProcessor( this );
		}

		@Override
		public void compute( final float[] src, final float[] dest )
		{
			final int len = sourceLength();
			for ( int i = 0; i < len; i++ )
				dest[ i ] = src[ i ] * scale + offset;
		}
	}
}
