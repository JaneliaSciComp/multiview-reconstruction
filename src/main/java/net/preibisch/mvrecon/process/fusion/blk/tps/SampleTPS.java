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
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.interval.IntervalSamplingMethod;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import net.imglib2.type.numeric.real.DoubleType;
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
		final RandomAccessibleInterval< DoubleType > dfieldV = DisplacementFieldTransform.createDisplacementField(
				transform,
				new FinalInterval( gridSize ),
				spacing,
				offset );

		final long[] dfieldSize = dfieldSize( gridSize );
		final Img< DoubleType > dfieldImg = ArrayImgs.doubles( dfieldSize );
		for ( int i = 0; i < origInterval.numDimensions(); i++ )
		{
			final double f = spacing[ i ];
			LoopBuilder.setImages( dfieldV.view().slice( 0, i ), dfieldImg.view().slice( 0, i ) )
					.forEachPixel( ( x, y ) -> y.set( x.get() / f ) );
		}

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

}
