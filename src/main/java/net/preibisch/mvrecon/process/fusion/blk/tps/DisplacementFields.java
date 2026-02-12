package net.preibisch.mvrecon.process.fusion.blk.tps;

import java.util.Arrays;

import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;

public final class DisplacementFields
{
	/**
	 * Sample the given {@link RealTransform} to create a normalized
	 * displacement field.
	 * <p>
	 * A "normalized" field expresses displacements in units relative to the
	 * displacement grid's own pixel spacing. Consequently, downsampling the
	 * grid by a factor of N requires scaling the displacement vectors by 1/N to
	 * maintain normalization.
	 * <p>
	 * Components of the displacements are in the 0th dimension, the extents of
	 * the field are given by {@code dimensions}. The output interval will
	 * therefore be of size: <br> [ transform.numTargetDimensions(),
	 * interval.dimension(0), ..., interval.dimension( N-1 )]
	 *
	 * @param transform
	 *            the transform to sampled
	 * @param dimensions
	 *            dimensions of the displacement field to create (dimension 0 for the vector components will be prepended)
	 * @param spacing
	 *            spacing of the samples
	 * @param offset
	 *            offset of the samples
	 * @return the displacement field
	 */
	public static RandomAccessibleInterval< DoubleType > createNormalized(
			final RealTransform transform,
			final Dimensions dimensions,
			final double[] spacing,
			final double[] offset )
	{
		final int n = dimensions.numDimensions();
		final long dataSize = n * Intervals.numElements( dimensions );
		if ( dataSize > Integer.MAX_VALUE - 8 )
			throw new IllegalArgumentException( "requested interval is too large to sample into an ArrayImg" );

		final int[] gridSize = new int[ n ];
		final long[] dfieldSize = new long[ n + 1 ];
		for ( int d = 0; d < n; d++ )
			dfieldSize[ d + 1 ] = gridSize[ d ] = ( int ) dimensions.dimension( d );
		dfieldSize[ 0 ] = n;
		final double[] data = new Sample( transform, spacing, offset, gridSize ).data;
		return ArrayImgs.doubles( data, dfieldSize );
	}

	private static final class Sample
	{
		private final RealTransform transform;
		private final double[] spacing;
		private final double[] offset;
		private final int[] gridSize;
		private final int[] gridStride;
		private final int n;
		private final double[] data;
		private final double[] p;
		private final double[] q;

		private Sample(
				final RealTransform transform,
				final double[] spacing,
				final double[] offset,
				final int[] gridSize )
		{
			this.transform = transform;
			this.spacing = spacing;
			this.offset = offset;
			this.gridSize = gridSize;
			n = gridSize.length;
			gridStride = IntervalIndexer.createAllocationSteps( gridSize );
			Arrays.setAll( gridStride, d -> n * gridStride[ d ] );
			data = new double[ gridStride[ n - 1 ] * gridSize[ n - 1 ] ];
			p = new double[ n ];
			q = new double[ n ];
			sample( 0, n - 1 );
		}

		private void sample( final int data_offset, final int d )
		{
			final double o = offset[ d ];
			final double s = spacing[ d ];
			final int l = gridSize[ d ];
			final int stride = gridStride[ d ];
			if ( d == 0 )
			{
				for ( int x = 0; x < l; ++x )
				{
					p[ d ] = o + s * x;
					transform.apply( p, q );
					for ( int i = 0; i < n; ++i )
						data[ data_offset + x * stride + i ] = ( q[ i ] - p[ i ] ) / spacing[ i ];
				}
			}
			else
			{
				for ( int x = 0; x < l; ++x )
				{
					p[ d ] = o + s * x;
					sample( data_offset + x * stride, d - 1 );
				}
			}
		}
	}

	private DisplacementFields()
	{
		// utility class, don't instantiate
	}
}
