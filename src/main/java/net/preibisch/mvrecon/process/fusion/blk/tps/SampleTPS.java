package net.preibisch.mvrecon.process.fusion.blk.tps;

import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.interval.IntervalSamplingMethod;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import net.imglib2.util.Intervals;

public class SampleTPS
{
	/**
	 * Get the bounding box in render coordinates of an image of the given
	 * {@code dimension} when back-projected through the inverse of the given
	 * {@code transform} from rendered to image coordinates.
	 *
	 * @param transform
	 * 		forward transform (from rendered to image coordinates)
	 * @param dimensions
	 * 		image dimensions
	 *
	 * @return bounding box in render coordinates
	 */
	public static Interval inverseTransformedBoundingBox(
			final ThinplateSplineTransform transform,
			final Dimensions dimensions )
	{
		// transforms from source img pixels to render coordinates
		final RealTransform invTransform = new WrappedIterativeInvertibleRealTransform<>( transform ).inverse();

		// estimated bounding box of the source img transformed to render coordinates
		return Intervals.smallestContainingInterval(
				invTransform.boundingInterval(
						new FinalInterval( dimensions ),
						IntervalSamplingMethod.CORNERS ) );
	}
}
