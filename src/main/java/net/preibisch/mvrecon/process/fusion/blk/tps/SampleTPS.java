package net.preibisch.mvrecon.process.fusion.blk.tps;

import java.util.Arrays;

import bdv.tools.boundingbox.IntervalCorners;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.DisplacementFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ScaleAndTranslation;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.interval.IntervalSamplingMethod;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;

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

		final RandomAccessibleInterval< DoubleType > dfieldImg = createDisplacementField(
				transform,
				new FinalInterval( gridSize ),
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

	private static long[] dfieldSize( final long[] gridSize )
	{
		final int n = gridSize.length;
		final long[] dfieldSize = new long[ n + 1 ];
		dfieldSize[ 0 ] = n;
		System.arraycopy( gridSize, 0, dfieldSize, 1, n );
		return dfieldSize;
	}









	public static RandomAccessibleInterval< DoubleType > createDisplacementField(
			final RealTransform transform,
			final Dimensions dimensions,
			final double[] spacing,
			final double[] offset )
	{
		final long[] gridSize = dimensions.dimensionsAsLongArray();
		final long[] dfieldSize = dfieldSize( gridSize );
		final int length = Util.safeInt( Intervals.numElements( dfieldSize ) );
		final double[] data = new double[ length ];

		// TODO: recast nested loop as recursion over dimensions

		final int n = 3;

		final int[] gridSizeI = Util.long2int( gridSize );
		final int[] gridStride = IntervalIndexer.createAllocationSteps( gridSizeI );
		Arrays.setAll( gridStride, d -> n * gridStride[ d ] );

		final double[] p = new double[ n ];
		final double[] q = new double[ n ];
		sample( data, 0, transform, spacing, offset, gridSizeI, gridStride, n - 1, p, q );

//		final int s0 = ( int ) gridSize[ 0 ];
//		final int s1 = ( int ) gridSize[ 1 ];
//		final int s2 = ( int ) gridSize[ 2 ];
//
//		for ( int x2 = 0; x2 < s2; ++x2 ) {
//			p[ 2 ] = offset[ 2 ] + spacing[ 2 ] * x2;
//			for ( int x1 = 0; x1 < s1; ++x1 ) {
//				p[ 1 ] = offset[ 1 ] + spacing[ 1 ] * x1;
//				for ( int x0 = 0; x0 < s0; ++x0 ) {
//					p[ 0 ] = offset[ 0 ] + spacing[ 0 ] * x0;
//					transform.apply( p, q );
//					data[ n * x0 + n * s0 * x1 + n * s0 * s1 * x2 + 0 ] = ( q[ 0 ] - p[ 0 ] ) / spacing[ 0 ];
//					data[ n * x0 + n * s0 * x1 + n * s0 * s1 * x2 + 1 ] = ( q[ 1 ] - p[ 1 ] ) / spacing[ 1 ];
//					data[ n * x0 + n * s0 * x1 + n * s0 * s1 * x2 + 2 ] = ( q[ 2 ] - p[ 2 ] ) / spacing[ 2 ];
//				}
//			}
//		}

		return ArrayImgs.doubles( data, dfieldSize );
	}

	private static void sample(
			final double[] data,
			final int data_offset,
			final RealTransform transform,
			final double[] spacing,
			final double[] offset,
			final int[] gridSize,
			final int[] gridStride,
			final int d,
			final double[] p,
			final double[] q )
	{
		if ( d == 0 ) {
			sample0( data, data_offset, transform, spacing, offset, gridSize, gridStride, p, q );
		} else {
			final int n = gridSize.length;
			final double o = offset[ d ];
			final double s = spacing[ d ];
			final int l = gridSize[ d ];
			final int stride = gridStride[ d ];
			for ( int x = 0; x < l; ++x )
			{
				p[ d ] = o + s * x;
				sample( data, data_offset + x * stride, transform, spacing, offset, gridSize, gridStride, d - 1, p, q );
			}
		}
	}

	private static void sample0(
			final double[] data,
			final int data_offset,
			final RealTransform transform,
			final double[] spacing,
			final double[] offset,
			final int[] gridSize,
			final int[] gridStride,
			final double[] p,
			final double[] q )
	{
		final int n = gridSize.length;
		final double o = offset[ 0 ];
		final double s = spacing[ 0 ];
		final int l = gridSize[ 0 ];
		final int stride = gridStride[ 0 ];
		for ( int x = 0; x < l; ++x )
		{
			p[ 0 ] = o + s * x;
			transform.apply( p, q );
			for ( int d = 0; d < n; ++d )
				data[ data_offset + x * stride + d ] = ( q[ d ] - p[ d ] ) / spacing[ d ];
		}
	}

}
