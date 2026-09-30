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
package net.preibisch.mvrecon.process.pointcloud;

/**
 * Helpers for vectors stored component-major ({@code matrix[ dimension ][ column ]}), the layout in which distance
 * loops run over contiguous columns and vectorize.
 */
public final class ComponentMajor
{
	private ComponentMajor() {}

	/** copies the vectors selected by {@code which} (all if null) into the columns of {@code target} */
	public static float[][] transpose( final float[][] vectors, final float[][] target, final int[] which, final int count )
	{
		for ( int col = 0; col < count; ++col )
		{
			final float[] source = vectors[ which == null ? col : which[ col ] ];
			for ( int dim = 0; dim < target.length; ++dim )
				target[ dim ][ col ] = source[ dim ];
		}
		return target;
	}

	/** out[ i ] = |query - column (from + i)|^2 for the columns [from, to) of {@code matrix} */
	public static void squaredDistances( final float[] query, final float[][] matrix, final int from, final int to, final float[] out )
	{
		final int count = to - from;
		final float query0 = query[ 0 ];
		final float[] row0 = matrix[ 0 ];
		for ( int i = 0; i < count; ++i )
		{
			final float diff = query0 - row0[ from + i ];
			out[ i ] = diff * diff;
		}
		for ( int dim = 1; dim < matrix.length; ++dim )
		{
			final float queryVal = query[ dim ];
			final float[] row = matrix[ dim ];
			for ( int i = 0; i < count; ++i )
			{
				final float diff = queryVal - row[ from + i ];
				out[ i ] += diff * diff;
			}
		}
	}
}
