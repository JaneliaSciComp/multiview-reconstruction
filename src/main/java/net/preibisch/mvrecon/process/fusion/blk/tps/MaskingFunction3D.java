package net.preibisch.mvrecon.process.fusion.blk.tps;

import net.imglib2.Dimensions;
import net.imglib2.algorithm.blocks.dfield.PositionFieldFunction;
import net.imglib2.type.NativeType;
import net.imglib2.type.PrimitiveType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;

public abstract class MaskingFunction3D< D extends NativeType< D > & RealType< D >, F > implements PositionFieldFunction< D, UnsignedByteType, F, byte[] >
{
	/**
	 * Weights are {@code w=0} for the outermost {@code border} pixels of {@code interval}.
	 * Weights are {@code w=1} inside {@code border} from the {@code interval} bounds.
	 *
	 * @param dfieldPrimitiveType
	 * @param dimensions
	 * @param border
	 */
	public static < D extends NativeType< D > & RealType< D >, F > MaskingFunction3D< D, F > of(
			final PrimitiveType dfieldPrimitiveType,
			final Dimensions dimensions,
			final float[] border )
	{
		switch ( dfieldPrimitiveType )
		{
		case FLOAT:
			return Cast.unchecked( new Float_( dimensions, border ) );
		case DOUBLE:
			return Cast.unchecked( new Double_( dimensions, border ) );
		default:
			throw new IllegalArgumentException();
		}
	}

	/**
	 * min border distance.
	 * for {@code x<b0: w(x)=0}.
	 */
	final float b0d0;
	final float b0d1;
	final float b0d2;

	/**
	 * max border distance.
	 * for {@code b2<x<b3: w(x)=fn(b3-x)}.
	 * for {@code b3<x: w(x)=0}.
	 */
	final float b3d0;
	final float b3d1;
	final float b3d2;

	MaskingFunction3D(
			final Dimensions dimensions,
			final float[] border )
	{
		final int n = 3;
		final float[] b0 = new float[ n ];
		final float[] b3 = new float[ n ];
		for ( int d = 0; d < n; ++d )
		{
			final int dim = ( int ) dimensions.dimension( d );
			b0[ d ] = border[ d ];
			b3[ d ] = dim - 1 - border[ d ];

			// TODO handle the case where border is so big that w=0 everywhere
		}

		b0d0 = b0[ 0 ];
		b0d1 = b0[ 1 ];
		b0d2 = b0[ 2 ];
		b3d0 = b3[ 0 ];
		b3d1 = b3[ 1 ];
		b3d2 = b3[ 2 ];
	}

	@Override
	public PositionFieldFunction< D, UnsignedByteType, F, byte[] > independentCopy()
	{
		return this;
	}

	private static class Float_ extends MaskingFunction3D< FloatType, float[] >
	{
		Float_( final Dimensions dimensions, final float[] border )
		{
			super( dimensions, border );
		}

		@Override
		public void compute( final byte[] dest, final int length, final float[] pfield, final double[] positionOffset )
		{
			final float d0 = ( float ) positionOffset[ 0 ];
			final float d1 = ( float ) positionOffset[ 1 ];
			final float d2 = ( float ) positionOffset[ 2 ];
			for ( int x = 0; x < length; ++x )
			{
				final float sf0 = pfield[ 3 * x ] + d0;
				final float sf1 = pfield[ 3 * x + 1 ] + d1;
				final float sf2 = pfield[ 3 * x + 2 ] + d2;
				dest[ x ] = ( sf0 >= b0d0 && sf0 < b3d0
						&& sf1 >= b0d1 && sf1 < b3d1
						&& sf2 >= b0d2 && sf2 < b3d2 )
						? ( byte ) 1 : ( byte ) 0;
			}
		}
	}

	private static class Double_ extends MaskingFunction3D< DoubleType, double[] >
	{
		Double_( final Dimensions dimensions, final float[] border )
		{
			super( dimensions, border );
		}

		@Override
		public void compute( final byte[] dest, final int length, final double[] pfield, final double[] positionOffset )
		{
			final double d0 = positionOffset[ 0 ];
			final double d1 = positionOffset[ 1 ];
			final double d2 = positionOffset[ 2 ];
			for ( int x = 0; x < length; ++x )
			{
				final float sf0 = ( float ) ( pfield[ 3 * x ] + d0 );
				final float sf1 = ( float ) ( pfield[ 3 * x + 1 ] + d1 );
				final float sf2 = ( float ) ( pfield[ 3 * x + 2 ] + d2 );
				dest[ x ] = ( sf0 >= b0d0 && sf0 < b3d0
						&& sf1 >= b0d1 && sf1 < b3d1
						&& sf2 >= b0d2 && sf2 < b3d2 )
						? ( byte ) 1 : ( byte ) 0;
			}
		}
	}
}
