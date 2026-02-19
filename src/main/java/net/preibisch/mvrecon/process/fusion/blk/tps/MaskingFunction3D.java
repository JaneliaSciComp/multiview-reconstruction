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

public abstract class MaskingFunction3D< D extends NativeType< D > & RealType< D >, T extends NativeType< T >, F, P > implements PositionFieldFunction< D, T, F, P >
{
	/**
	 * Weights are {@code w=0} for the outermost {@code border} pixels of {@code interval}.
	 * Weights are {@code w=1} inside {@code border} from the {@code interval} bounds.
	 *
	 * @param dfieldType
	 * @param dimensions
	 * @param border
	 */
	public static < D extends NativeType< D > & RealType< D >, T extends NativeType< T >, F, P > MaskingFunction3D< D, T, F, P > of(
			final D dfieldType,
			final T targetType,
			final Dimensions dimensions,
			final float[] border )
	{
		final PrimitiveType dfieldPrimitiveType = dfieldType.getNativeTypeFactory().getPrimitiveType();
		switch ( dfieldPrimitiveType )
		{
		case FLOAT:
			if ( targetType instanceof FloatType )
				return Cast.unchecked( new Float_to_Float( dimensions, border ) );
			else if ( targetType instanceof UnsignedByteType )
				return Cast.unchecked( new Float_to_UnsignedByte( dimensions, border ) );
			else
				throw new IllegalArgumentException();
		case DOUBLE:
			if ( targetType instanceof FloatType )
				return Cast.unchecked( new Double_to_Float( dimensions, border ) );
			else if ( targetType instanceof UnsignedByteType )
				return Cast.unchecked( new Double_to_UnsignedByte( dimensions, border ) );
			else
				throw new IllegalArgumentException();
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
	public PositionFieldFunction< D, T, F, P > independentCopy()
	{
		return this;
	}

	private static class Float_to_UnsignedByte extends MaskingFunction3D< FloatType, UnsignedByteType, float[], byte[] >
	{
		Float_to_UnsignedByte( final Dimensions dimensions, final float[] border )
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

	private static class Float_to_Float extends MaskingFunction3D< FloatType, FloatType, float[], float[] >
	{
		Float_to_Float( final Dimensions dimensions, final float[] border )
		{
			super( dimensions, border );
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
				dest[ x ] = ( sf0 >= b0d0 && sf0 < b3d0
						&& sf1 >= b0d1 && sf1 < b3d1
						&& sf2 >= b0d2 && sf2 < b3d2 )
						? 1 : 0;
			}
		}
	}

	private static class Double_to_UnsignedByte extends MaskingFunction3D< DoubleType, UnsignedByteType, double[], byte[] >
	{
		Double_to_UnsignedByte( final Dimensions dimensions, final float[] border )
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

	private static class Double_to_Float extends MaskingFunction3D< DoubleType, FloatType, double[], float[] >
	{
		Double_to_Float( final Dimensions dimensions, final float[] border )
		{
			super( dimensions, border );
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
				dest[ x ] = ( sf0 >= b0d0 && sf0 < b3d0
						&& sf1 >= b0d1 && sf1 < b3d1
						&& sf2 >= b0d2 && sf2 < b3d2 )
						? 1 : 0;
			}
		}
	}
}
