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
package net.preibisch.mvrecon.process.fusion.blk.tps;

import net.imglib2.Dimensions;
import net.imglib2.algorithm.blocks.dfield.PositionFieldFunction;
import net.imglib2.type.NativeType;
import net.imglib2.type.PrimitiveType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;

public abstract class DistanceFunction3D< D extends NativeType< D > & RealType< D >, F > implements PositionFieldFunction< D, FloatType, F, float[] >
{
	/**
	 * Every pixel inside {@code dimensions} reports the <em>squared</em> Euclidean
	 * distance to the center of {@code dimensions}. Pixels outside report
	 * {@code Float.POSITIVE_INFINITY}.
	 * <p>
	 * Unlike the affine-path {@code Distance} (which measures in target/fused
	 * coordinates), the {@link PositionFieldFunction} interface only provides
	 * <em>local</em> (source) coordinates, so the distance is measured in local pixel
	 * units. For split tiles of the same image all views share the same local scale, so
	 * winner selection matches the affine path.
	 *
	 * @param dfieldType
	 * @param dimensions
	 */
	public static < D extends NativeType< D > & RealType< D >, F > DistanceFunction3D< D, F > of(
			final D dfieldType,
			final Dimensions dimensions )
	{
		final PrimitiveType dfieldPrimitiveType = dfieldType.getNativeTypeFactory().getPrimitiveType();
		switch ( dfieldPrimitiveType )
		{
		case FLOAT:
			return Cast.unchecked( new Float_( dimensions ) );
		case DOUBLE:
			return Cast.unchecked( new Double_( dimensions ) );
		default:
			throw new IllegalArgumentException();
		}
	}

	/**
	 * min interval bound.
	 * for {@code x<b0: d(x)=Float.POSITIVE_INFINITY}.
	 */
	final float b0d0;
	final float b0d1;
	final float b0d2;

	/**
	 * max interval bound.
	 * for {@code b3<=x: d(x)=Float.POSITIVE_INFINITY}.
	 */
	final float b3d0;
	final float b3d1;
	final float b3d2;

	/**
	 * center of {@code dimensions} in local coordinates.
	 */
	final float c0;
	final float c1;
	final float c2;

	DistanceFunction3D( final Dimensions dimensions )
	{
		final int n = 3;
		final float[] b0 = new float[ n ];
		final float[] b3 = new float[ n ];
		final float[] c = new float[ n ];
		for ( int d = 0; d < n; ++d )
		{
			final int dim = ( int ) dimensions.dimension( d );

			c[ d ] = ( dim - 1 ) / 2f;

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

		b0d0 = b0[ 0 ];
		b0d1 = b0[ 1 ];
		b0d2 = b0[ 2 ];
		b3d0 = b3[ 0 ];
		b3d1 = b3[ 1 ];
		b3d2 = b3[ 2 ];
		c0 = c[ 0 ];
		c1 = c[ 1 ];
		c2 = c[ 2 ];
	}

	@Override
	public PositionFieldFunction< D, FloatType, F, float[] > independentCopy()
	{
		return this;
	}

	@Override
	public FloatType getType()
	{
		return new FloatType();
	}

	private static class Float_ extends DistanceFunction3D< FloatType, float[] >
	{
		Float_( final Dimensions dimensions )
		{
			super( dimensions );
		}

		@Override
		public void compute( final float[] dest, final int length, final float[] pfield, final double[] positionOffset )
		{
			final float d0 = ( float ) positionOffset[ 0 ];
			final float d1 = ( float ) positionOffset[ 1 ];
			final float d2 = ( float ) positionOffset[ 2 ];
			for ( int x = 0; x < length; ++x )
			{
				final float sf0 = pfield[ 3 * x ] + d0;
				final float sf1 = pfield[ 3 * x + 1 ] + d1;
				final float sf2 = pfield[ 3 * x + 2 ] + d2;
				if ( sf0 >= b0d0 && sf0 < b3d0
						&& sf1 >= b0d1 && sf1 < b3d1
						&& sf2 >= b0d2 && sf2 < b3d2 )
				{
					final float df0 = sf0 - c0;
					final float df1 = sf1 - c1;
					final float df2 = sf2 - c2;
					dest[ x ] = df0 * df0 + df1 * df1 + df2 * df2;
				}
				else
				{
					dest[ x ] = Float.POSITIVE_INFINITY;
				}
			}
		}
	}

	private static class Double_ extends DistanceFunction3D< DoubleType, double[] >
	{
		Double_( final Dimensions dimensions )
		{
			super( dimensions );
		}

		@Override
		public void compute( final float[] dest, final int length, final double[] pfield, final double[] positionOffset )
		{
			final double d0 = positionOffset[ 0 ];
			final double d1 = positionOffset[ 1 ];
			final double d2 = positionOffset[ 2 ];
			for ( int x = 0; x < length; ++x )
			{
				final float sf0 = ( float ) ( pfield[ 3 * x ] + d0 );
				final float sf1 = ( float ) ( pfield[ 3 * x + 1 ] + d1 );
				final float sf2 = ( float ) ( pfield[ 3 * x + 2 ] + d2 );
				if ( sf0 >= b0d0 && sf0 < b3d0
						&& sf1 >= b0d1 && sf1 < b3d1
						&& sf2 >= b0d2 && sf2 < b3d2 )
				{
					final float df0 = sf0 - c0;
					final float df1 = sf1 - c1;
					final float df2 = sf2 - c2;
					dest[ x ] = df0 * df0 + df1 * df1 + df2 * df2;
				}
				else
				{
					dest[ x ] = Float.POSITIVE_INFINITY;
				}
			}
		}
	}
}
