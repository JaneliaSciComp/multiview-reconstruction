package net.preibisch.mvrecon.process.fusion.blk.tps;

import java.util.Arrays;

import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.interval.IntervalSamplingMethod;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;

public class SampleTPS
{
	// TODO: rename, getter, javadoc
	public final AffineTransform3D transformFromSource;

	// TODO: rename, getter, javadoc
	public final DisplacementField< DoubleType > dfield;

	// TODO: rename, getter, javadoc
	// estimated bounding box of the source img transformed to render coordinates
	public final Interval transformedInterval;

	SampleTPS( final AffineTransform3D transformFromSource, final DisplacementField< DoubleType > dfield, final Interval transformedInterval )
	{
		this.transformFromSource = transformFromSource;
		this.dfield = dfield;
		this.transformedInterval = transformedInterval;
	}

	// TODO: instead of TPS take RealTransform
	//       and check for known-good subtypes
	public static SampleTPS sample(


			// transforms from render coordinates to source img pixels
			final ThinplateSplineTransform transform, // TODO rename

			// dimensions of the source img (in pixels)
			final Interval origInterval, // TODO rename

			// spacing of the grid on which the transform is sampled (in render coordinates)
			final double[] spacing
	)
	{
		// transforms from source img pixels to render coordinates
		final RealTransform invTransform = new WrappedIterativeInvertibleRealTransform<>( transform ).inverse();

		// estimated bounding box of the source img transformed to render coordinates
		final Interval transformedInterval = Intervals.smallestContainingInterval(
				invTransform.boundingInterval( origInterval, IntervalSamplingMethod.CORNERS ) );

		// smaller / downsampled interval over which to rasterize the TPS
		final long[] gridSize = gridSize( transformedInterval, spacing );
		final double[] offset = transformedInterval.minAsDoubleArray();
//		final RandomAccessibleInterval< DoubleType > dfieldV = DisplacementFieldTransform.createDisplacementField(
//				transform,
//				new FinalInterval( gridSize ),
//				spacing,
//				offset );
//
//		final long[] dfieldSize = dfieldSize( gridSize );
//		final Img< DoubleType > dfieldImg = ArrayImgs.doubles( dfieldSize );
//		for ( int i = 0; i < origInterval.numDimensions(); i++ )
//		{
//			final double f = spacing[ i ];
//			LoopBuilder.setImages( dfieldV.view().slice( 0, i ), dfieldImg.view().slice( 0, i ) )
//					.forEachPixel( ( x, y ) -> y.set( x.get() / f ) );
//		}

		final RandomAccessibleInterval< DoubleType > dfieldImg = Sample.createDisplacementField(
				transform,
				FinalDimensions.wrap( gridSize ),
				spacing,
				offset );


		final AffineTransform3D transformFromSource = new AffineTransform3D();
		transformFromSource.set(
				spacing[ 0 ], 0, 0, offset[ 0 ],
				0, spacing[ 1 ], 0, offset[ 1 ],
				0, 0, spacing[ 2 ], offset[ 2 ]
		);

		final DisplacementField< DoubleType > dfield = new DisplacementField<>( BlockSupplier.of( dfieldImg ), spacing, offset );

		return new SampleTPS( transformFromSource, dfield, transformedInterval );
	}

	private static long[] gridSize( final Dimensions size, final double[] spacing )
	{
		final int n = size.numDimensions();
		final long[] gridSize = new long[ n ];
		Arrays.setAll( gridSize, d -> ( long ) Math.ceil( size.dimension( d ) / spacing[ d ] ) );
		return gridSize;
	}




	static final class Sample {

	public static RandomAccessibleInterval< DoubleType > createDisplacementField(
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
		return ArrayImgs.doubles( new Sample( transform, spacing, offset, gridSize ).data, dfieldSize );
	}

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
			if ( d == 0 ) {
				for ( int x = 0; x < l; ++x )
				{
					p[ d ] = o + s * x;
					transform.apply( p, q );
					for ( int i = 0; i < n; ++i )
						data[ data_offset + x * stride + i ] = ( q[ i ] - p[ i ] ) / spacing[ i ];
				}
			} else {
				for ( int x = 0; x < l; ++x )
				{
					p[ d ] = o + s * x;
					sample( data_offset + x * stride, d - 1 );
				}
			}
		}
	}

}
