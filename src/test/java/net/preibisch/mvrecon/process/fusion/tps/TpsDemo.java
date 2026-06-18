package net.preibisch.mvrecon.process.fusion.tps;


import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.util.Intervals;
import net.imglib2.view.fluent.RandomAccessibleIntervalView.Extension;
import net.imglib2.view.fluent.RandomAccessibleView.Interpolation;
import net.imglib2.view.fluent.RealRandomAccessibleView;

/* written by John Bogovic */
public class TpsDemo {

	public static void main(String[] args) {

		new ImageJ();
		ImagePlus imp = IJ.openImage("http://imagej.net/images/boats.gif");
		imp.show();

		Img< UnsignedByteType > img = ImageJFunctions.wrapByte( imp );
		System.out.println(Intervals.toString(img));

		double sx = img.dimension(0);
		double sy = img.dimension(1);
		double[][] points = new double[][]{
			{sx / 2, 0, sx, 0, sx}, // x
			{sy / 2, 0, 0, sy, sy}  // y
		};

		double[][] displacements = new double[][]{
			{50, -50, -50, -50, -50}, 		// x displacement
			{50, -100, -100, -100, -100} 	// y displacement
		};

		System.out.println( points.length + ", " + points[ 0 ].length );
		ThinplateSplineTransform transform = buildTransform(points, displacements);

		RealRandomAccessibleView< UnsignedByteType > interp = img.view().extend(Extension.zero()).interpolate(Interpolation.nLinear());
		RandomAccessibleInterval< UnsignedByteType > tformedImg = new RealTransformRealRandomAccessible<>(interp, transform).realView().raster().interval(img);
		ImageJFunctions.show(tformedImg);
	}

	public static ThinplateSplineTransform buildTransform(
			double[][] points2xN,
			double[][] displacements2xN) {

		return new ThinplateSplineTransform(
				// build corresponding points from displacements
				// if you have points already, just use them
				pointsFromDisplacements(points2xN, displacements2xN),
				points2xN);
	}

	/**
	 * Takes a set of points and displacements and outputs a set of
	 * corresponding points
	 */
	public static double[][] pointsFromDisplacements(
			double[][] points2xN,
			double[][] displacements2xN) {

		int nd = points2xN.length;
		int N = points2xN[0].length;

		double[][] pts = new double[nd][N];
		for (int d = 0; d < nd; d++) {
			for (int i = 0; i < N; i++) {
				pts[d][i] = points2xN[d][i] + displacements2xN[d][i];
			}
		}
		return pts;
	}

}