/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.process.quality;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.measure.ResultsTable;
import ij.process.FHT;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;

import org.apache.commons.math3.analysis.interpolation.LoessInterpolator;
import org.apache.commons.math3.util.FastMath;

/**
 * @author Alex Herbert
 * @author Olivier Burri
 * 
 * Copyright (C) 2016  Alex Herbert
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * Compute the Fourier Ring Correlation, a measure of the resolution of a microscopy image.
 * <p>
 * Adapted from the FIRE (Fourier Image REsolution) plugin produced as part of the paper:
 * <a href="http://www.nature.com/nmeth/journal/v10/n6/full/nmeth.2448.html">
 * Niewenhuizen, et al (2013). Measuring image resolution in optical nanoscopy. Nature Methods, 10, 557</a>
 * 
 */
 public class FRC
{
	public enum ThresholdMethod
	{
		FIXED_1_OVER_7("Fixed 1/7"), HALF_BIT("Half-bit"), THREE_SIGMA("Three sigma");

		private String name;

		private ThresholdMethod(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	};

	// Properties controlling the algorithm

	/**
	 * The correlation is computed using intervals around the circle circumference. The number of samples for half the
	 * circle is computed as: Pi * radius * sampling factor
	 */
	public double perimeterSamplingFactor = 1;

	/**
	 * The correlation is computed using intervals around the circle circumference of the Fourier transform. The Fourier
	 * image is 2-fold radially symmetric and so the calculation can use only half the circle for speed. Note: The
	 * results will differ slightly due to the implementation of the Fourier image not being exactly symmetric and the
	 * sample points used on the perimeter not matching between the two semi-circles.
	 */
	public boolean useHalfCircle = true;

	/**
	 * Calculate the Fourier Ring Correlation curve for two images.
	 * 
	 * @param ip1 - image 1
	 * @param ip2 - image 2
	 * @return An array of triplets representing [][radius,correlation,N] where correlation is the FRC at the given
	 *         radius from the centre of the Fourier transform (i.e. 1/spatial frequency) and N is the number of samples
	 *         used to compute the correlation
	 */
	public double[][] calculateFrcCurve(ImageProcessor ip1, ImageProcessor ip2)
	{
		//IJ.showStatus("Calculating complex FFT images...");

		// Pad images to the same size
		final int maxWidth = FastMath.max(ip1.getWidth(), ip2.getWidth());
		final int maxHeight = FastMath.max(ip1.getHeight(), ip2.getHeight());
		ip1 = pad(ip1, maxWidth, maxHeight);
		ip2 = pad(ip2, maxWidth, maxHeight);

		// TODO - Can this be sped up by computing both FFT transforms together?
		FloatProcessor[] fft1 = getComplexFFT(ip1);
		FloatProcessor[] fft2 = getComplexFFT(ip2);

		//IJ.showStatus("Preparing FRC curve calculation...");

		final int size = fft1[0].getWidth();

		// In-line for speed
		float[] numerator = new float[size * size];
		float[] absFFT1 = new float[numerator.length];
		float[] absFFT2 = new float[numerator.length];

		float[] dataA1 = (float[]) fft1[0].getPixels();
		float[] dataB1 = (float[]) fft1[1].getPixels();
		float[] dataA2 = (float[]) fft2[0].getPixels();
		float[] dataB2 = (float[]) fft2[1].getPixels();

		for (int y = size, i = size * size - 1; y-- > 0;)
		{
			for (int x = size; x-- > 0; i--)
			{
				numerator[i] = dataA1[i] * dataA2[i] + dataB1[i] * dataB2[i];
				absFFT1[i] = dataA1[i] * dataA1[i] + dataB1[i] * dataB1[i];
				absFFT2[i] = dataA2[i] * dataA2[i] + dataB2[i] * dataB2[i];
			}
		}

		int radius = 1;
		final double centre = size / 2;
	    final double max = (Math.max(maxWidth, maxHeight)/2) - 1;
		//IJ.showStatus("Calculating FRC curve...");

		double[][] frcCurve = new double[(int) max][3];

		// Radius zero is always 1
		frcCurve[0][0] = 0;
		frcCurve[0][1] = 1;
		frcCurve[0][2] = 1; // Avoid divide by zero errors. Not sure if this is OK

		float[][] images = new float[][] { numerator, absFFT1, absFFT2 };

		while (radius < max)
		{
			//final double progress = (1.0 * radius) / max;
			//IJ.showProgress(progress);
			//IJ.showStatus("Calculating FRC curve...[Radius = " + radius + "px]");

			// Inline the calculation for speed
			double sum1 = 0;
			double sum2 = 0;
			double sum3 = 0;

			// Circumference is 2*pi*r
		    double angleStep = 1 / (perimeterSamplingFactor * radius);

			double angle = 0D;
			int numSum = 0;

			final double limit = (useHalfCircle) ?  Math.PI :  2 * Math.PI;
			while (angle < limit)
			{
				double x = centre + radius * Math.cos(angle);
				double y = centre + radius * Math.sin(angle);

				double[] values = getInterpolatedValues(x, y, images, size);
				sum1 += values[0];
				sum2 += values[1];
				sum3 += values[2];

				numSum++;
				angle += angleStep;
			}

			double val = sum1 / Math.sqrt(sum2 * sum3);

			frcCurve[radius][0] = radius;
			frcCurve[radius][1] = val;
			frcCurve[radius][2] = numSum;

			radius++;
		}

		//IJ.showProgress(1);
		//IJ.showStatus("Finished calculating FRC curve...");

		return frcCurve;
	}

	private ImageProcessor pad(ImageProcessor ip, int width, int height)
	{
		// Make sure width and height are even
		width = width%2 == 0 ? width : width+1;
		height = height%2 == 0 ? height : height+1;
		if (ip.getWidth() != width || ip.getHeight() != height)
		{
			ImageProcessor ip2 = ip.createProcessor(width, height);
			ip2.insert(ip, 0, 0);
			ip = ip2;
		}
		return ip;
	}

	/**
	 * Convert an image into a Fourier image with real and imaginary parts
	 * 
	 * @param ip - image
	 * @return the real and imaginary parts
	 */
	public FloatProcessor[] getComplexFFT(ImageProcessor ip)
	{
		FloatProcessor taperedDataImage = getSquareTaperedImage(ip);

		FHT fht = new FHT(taperedDataImage);
		fht.setShowProgress( false );
		fht.transform();

		FloatProcessor[] ret = new FloatProcessor[2];

		ImageStack stack1 = fht.getComplexTransform();
		ret[0] = ((FloatProcessor) stack1.getProcessor(1));
		ret[1] = ((FloatProcessor) stack1.getProcessor(2));

		return ret;
	}

	// Cache the Tukey window function
	private float[] taperX = new float[0];
	private float[] taperY = new float[0];

	/**
	 * Applies a Tukey window function to the image and then pads it to the next square size power of two.
	 * 
	 * @param dataImage - image
	 * @return The square tapered image
	 */
	public FloatProcessor getSquareTaperedImage(ImageProcessor dataImage)
	{
		// Use a Tukey window function
		taperX = getWindowFunction(taperX, dataImage.getWidth());
		taperY = getWindowFunction(taperY, dataImage.getHeight());

		final int size = FastMath.max(dataImage.getWidth(), dataImage.getHeight());

		// Pad to a power of 2
		int newSize = 0;
		for (int i = 4; i < 15; i++)
		{
			newSize = (int) Math.pow(2.0, i);
			if (size <= newSize)
			{
				break;
			}
		}

		if (size > newSize)
			return null; // Error

		dataImage = dataImage.toFloat(0, null);
		float[] data = (float[]) dataImage.getPixels();
		float[] pixels = new float[newSize * newSize];
		// Note that the limits at 0 and size-1 the taper is zero so this can be ignored
		final int maxy_1 = dataImage.getHeight() - 1;
		final int maxx_1 = dataImage.getWidth() - 1;
		final int oldWidth = dataImage.getWidth();
		for (int y = 1; y < maxy_1; y++)
		{
			final float yTmp = taperY[y];
			for (int x = 1, i = y * oldWidth + 1, ii = y * newSize + 1; x < maxx_1; x++, i++, ii++)
			{
				pixels[ii] = data[i] * taperX[x] * yTmp;
			}
		}

		return new FloatProcessor(newSize, newSize, pixels, null);
	}

	private float[] getWindowFunction(float[] taper, int size)
	{
		if (taper.length != size)
		{
			// Re-use cached values
			if (taperX.length == size)
				return taperX;
			if (taperY.length == size)
				return taperY;

			final int boundary = size / 8;
			final int upperBoundary = size - boundary;
			taper = new float[size];
			for (int i = 0; i < size; i++)
			{
				if ((i < boundary) || (i > size - upperBoundary))
				{
					taper[i] = (float) Math.pow(Math.sin(12.566370614359172D * i / size), 2.0D);
				}
				else
				{
					taper[i] = 1;
				}
			}
		}
		return taper;
	}

	/**
	 * Adapted from ij.process.ImageProcessor.getInterpolatedValue(int,int).
	 * <p>
	 * Removed bounds checking and compute multiple values at the same time for multiple images.
	 * 
	 * @param x - pos x
	 * @param y - pos y
	 * @return - the interpolated value
	 */
	private double[] getInterpolatedValues(final double x, final double y, float[][] images, final int maxx)
	{
		final int xbase = (int) x;
		final int ybase = (int) y;
		double xFraction = x - xbase;
		double yFraction = y - ybase;
		if (xFraction < 0.0)
			xFraction = 0.0;
		if (yFraction < 0.0)
			yFraction = 0.0;

		final int lowerLeftIndex = ybase * maxx + xbase;
		final int lowerRightIndex = lowerLeftIndex + 1;
		final int upperLeftIndex = lowerLeftIndex + maxx;
		final int upperRightIndex = upperLeftIndex + 1;

		final int noImages = 3; //images.length;
		double[] values = new double[noImages];
		for (int i = 0; i < noImages; i++)
		{
			final float[] image = images[i];
			final double lowerLeft = image[lowerLeftIndex];
			final double lowerRight = image[lowerRightIndex];
			final double upperRight = image[upperLeftIndex];
			final double upperLeft = image[upperRightIndex];

			final double upperAverage = upperLeft + xFraction * (upperRight - upperLeft);
			final double lowerAverage = lowerLeft + xFraction * (lowerRight - lowerLeft);
			values[i] = lowerAverage + yFraction * (upperAverage - lowerAverage);
		}
		return values;
	}

	/**
	 * Perform LOESS smoothing on the FRC curve.
	 * <p>
	 * The input curve is copied and then the correlation values are smoothed using a LOESS interpolation with the given
	 * parameters. If smoothing fails the original curve values are returned.
	 * 
	 * @param frcCurve - the FRC curve
	 * @param bandwidth - unknown
	 * @param robustness - unknown
	 * @return A new FRC curve
	 */
	public double[][] getSmoothedCurve(double[][] frcCurve, double bandwidth, int robustness)
	{
		double[][] sCurve = new double[frcCurve.length][3];

		double[] xVals = new double[frcCurve.length];
		double[] yVals = new double[frcCurve.length];

		for (int i = 0; i < frcCurve.length; i++)
		{
			xVals[i] = frcCurve[i][0];
			yVals[i] = frcCurve[i][1];

			sCurve[i][0] = frcCurve[i][0];
			sCurve[i][1] = frcCurve[i][1];
			sCurve[i][2] = frcCurve[i][2];
		}

		double[] ySmoothed = new double[frcCurve.length];

		//try
		{
			LoessInterpolator loess = new LoessInterpolator(bandwidth, robustness);
			ySmoothed = loess.smooth(xVals, yVals);

			for (int i = 0; i < frcCurve.length; i++)
				sCurve[i][1] = ySmoothed[i];
		}
		//catch (Exception e)
		{
		//	e.printStackTrace();
		}

		return sCurve;
	}

	/**
	 * Perform LOESS smoothing on the FRC curve
	 * <p>
	 * The input curve is copied and then the correlation values are smoothed using a LOESS interpolation with a
	 * bandwidth of 0.0707 and robustness of 0. If smoothing fails the original curve values are returned.
	 * 
	 * @param frcCurve - the FRC curve
	 * @return A new FRC curve
	 */
	public double[][] getSmoothedCurve(double[][] frcCurve)
	{
		double bandwidth = 0.0707;
		int robustness = 0;
		return getSmoothedCurve(frcCurve, bandwidth, robustness);
	}

	/**
	 * Calculate the curve representing the minimum correlation required to distinguish two images for each resolution
	 * in the input FRC curve.
	 * 
	 * @param frcCurve - the FRC curve
	 * @param method - one of the methods
	 * @return The threshold curve representing the threshold for each input spatial frequency
	 */
	public double[] calculateThresholdCurve(double[][] frcCurve, ThresholdMethod method)
	{
		double[] threshold = new double[frcCurve.length];

		for (int i = 0; i < threshold.length; i++)
		{
			switch (method)
			{
				case HALF_BIT:
					threshold[i] = ((0.2071 * Math.sqrt(frcCurve[i][2]) + 1.9102) / (1.2071 * Math.sqrt(frcCurve[i][2]) + 0.9102));
					break;
				case THREE_SIGMA:
					threshold[i] = (3.0 / Math.sqrt(frcCurve[i][2] / 2.0));
					break;
				case FIXED_1_OVER_7:
				default:
					threshold[i] = 0.1428;
			}
		}

		return threshold;
	}

	/**
	 * Computes the crossing points of the FRC curve and the threshold curve. The intersections can be used to determine
	 * the image resolution using {getCorrectIntersection(ArrayList, ThresholdMethod)}
	 * 
	 * @param frcCurve - the FRC curve
	 * @param thresholdCurve - where to find thresholds
	 * @return The crossing points
	 */
	public double[] getIntersections(double[][] frcCurve, double[] thresholdCurve)
	{
		if (frcCurve.length != thresholdCurve.length)
		{
			IJ.error("Error", "Unable to calculate FRC curve intersections due to input length mismatch.");
			return null;
		}

		double[] intersections = new double[frcCurve.length - 1];
		int count = 0;

		for (int i = 1; i < frcCurve.length; i++)
		{
			// http://en.wikipedia.org/wiki/Line-line_intersection
			//
			//     x1,y1            x4,y4      
			//         **        ++ 
			//           **    ++
			//             **++ P(x,y)
			//            ++ **
			//          ++     **
			//        ++         **
			//    x3,y3            ** 
			//                       x2,y2  

			final double y1 = frcCurve[i - 1][1];
			final double y2 = frcCurve[i][1];
			final double y3 = thresholdCurve[i - 1];
			final double y4 = thresholdCurve[i];

			// Check if they cross
			if (!((y3 >= y1 && y4 < y2) || (y1 >= y3 && y2 < y4)))
			{
				continue;
			}			

			final double x1 = frcCurve[i - 1][0];
			final double x2 = frcCurve[i][0];
			final double x3 = x1;
			final double x4 = x2;
			
			final double x1_x2 = x1 - x2;
			final double x3_x4 = x3 - x4;
			final double y1_y2 = y1 - y2;
			final double y3_y4 = y3 - y4;

			// Check if lines are parallel
			if (x1_x2 * y3_y4 - y1_y2 * x3_x4 == 0)
			{
				if (y1 == y3)
					// The lines are the same
					intersections[count++] = x1;
			}
			else
			{
				// Find intersection
				double px = ((x1 * y2 - y1 * x2) * x3_x4 - x1_x2 * (x3 * y4 - y3 * x4)) /
						(x1_x2 * y3_y4 - y1_y2 * x3_x4);
				//double px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) /
				//		((x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4));
				//double py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) /
				//		((x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4));

				// Check if the intersection is within the two points
				// Q. Is this necessary given the intersection check above?
				if (px >= x1 && px < x2)
				{
					intersections[count++] = px;
				}
			}
		}

		return Arrays.copyOf(intersections, count);
	}

	/**
	 * Get the correction intersection representing the image resolution. The intersection chosen depends on the method
	 * used to calculate the threshold curve using {calculateThresholdCurve(double[][], ThresholdMethod)}
	 * <p>
	 * The intersection corresponds the lowest spatial frequency at which there is no significant correlation between
	 * the images.
	 * 
	 * @param intersections - unknown
	 * @param method - one of the methods
	 * @return The intersection (or zero if no crossings)
	 */
	public double getCorrectIntersection(double[] intersections, ThresholdMethod method)
	{
		if (intersections == null || intersections.length == 0)
			return 0;

		switch (method)
		{
		// The half-bit and 3-sigma curves are often above 1 at close to zero spatial frequency.
		// This means that any FRC curve starting at 1 may cross the line twice. 
		// If so the second crossing is the one that is desired.
			case HALF_BIT:
			case THREE_SIGMA:
				return (intersections.length > 1) ? intersections[1] : intersections[0];

			case FIXED_1_OVER_7:
			default:
				return intersections[0];
		}
	}

	/**
	 * Utility function that calculates the Fourier Image Resolution (FIRE) number using the provided images.
	 * 
	 * @param ip1 - image 1
	 * @param ip2 - image 2
	 * @param method - one of the methods
	 * @return The FIRE number (in pixels)
	 */
	public double calculateFireNumber(ImageProcessor ip1, ImageProcessor ip2, ThresholdMethod method)
	{
		double[][] frcCurve = calculateFrcCurve(ip1, ip2);
		return calculateFireNumber(frcCurve, method);
	}

	/**
	 * Utility function that calculates the Fourier Image Resolution (FIRE) number using the provided FRC curve data.
	 * 
	 * @param frcCurve- the FRC curve
	 * @param method - one of the methods
	 * @return The FIRE number (in pixels)
	 */
	public double calculateFireNumber(double[][] frcCurve, ThresholdMethod method)
	{
		double[] thresholdCurve = calculateThresholdCurve(frcCurve, method);
		double[] intersections = getIntersections(frcCurve, thresholdCurve);

		double fire = Double.NaN;
		if (intersections == null || intersections.length != 0)
		{
			double spatialFrequency = getCorrectIntersection(intersections, method);
			// Since the Fourier calculation only uses half of the image (from centre to the edge) 
			// we must double the curve length to get the original maximum image width. In addition
			// the computation was up to the edge-1 pixels so add back a pixel to the curve length.
			fire = 2 * (frcCurve.length + 1) / spatialFrequency;
		}
		return fire;
	}
	


	/**
	 * Convenience function to batch calculate the FRC by going though file on two separate directories. 
	 * When one file name is identical in both directories, it opens them and computes the FRC.
	 * If is_save_plot is true, then a plot of the FRC and FIRE calculations is saved in the parent of 
	 * directory1 in a folder named "Graphs"
	 * NOTE: This only works for TIFF files at the moment.
	 * 
	 * @param directory1 is the first directory containing data
	 * @param directory2 is the first directory containing data
	 * @param method is the {ThresholdMethod} used
	 * @param rt is the {ij.measure.ResultsTable} where you want the batch to write the FIRE number to
	 * @param is_save_plot defines whether the plot will be saved, as described above.
	 */
	public void batchCalculateFireNumber(File directory1, File directory2, ThresholdMethod method, ResultsTable rt, boolean is_save_plot)
	{
		// Navigate folder for tiffs
		String[] the_files = directory1.list(new FilenameFilter() {
			
			@Override
			public boolean accept(File dir, String name) {
				// check extensions
				return true;				
			}
		});
		
		// Prepare saving if necessary
		File save_dir = new File(directory1.getParentFile(), "Graphs");
		save_dir.mkdir();
		
		// For each file, open one in each directory
		for(String the_file : the_files) {
			File f1 = new File(directory1.getAbsolutePath()+File.separator+the_file);
			File f2 = new File(directory2.getAbsolutePath()+File.separator+the_file);
			
			if(f2.exists()) {
				ImagePlus i1 = IJ.openImage(f1.getAbsolutePath());
				ImagePlus i2 = IJ.openImage(f2.getAbsolutePath());
				
				// Finally calculate FRC
				double[][] frc_curve = calculateFrcCurve(i1.getProcessor(), i2.getProcessor());
				
				double[][] smooth_frc = getSmoothedCurve(frc_curve);

				// Fourier Image REsolution number ("FIRE")
				double fire = calculateFireNumber(smooth_frc, method);
				
				rt.incrementCounter();
				rt.addLabel(i1.getTitle());
				rt.addValue("FRC ["+method+"]", fire);
				rt.addValue("FRC ["+method+"] Calibrated ["+i1.getCalibration().getUnit()+"]", fire*i1.getCalibration().pixelHeight);
				rt.show("FRC Results");
				
				if(is_save_plot) {
					Plot p = doPlot(frc_curve, smooth_frc, method, fire, i1.getTitle());
					
					ImagePlus plot_image = p.makeHighResolution("FRC", 3, true, false);
					String plot_name = save_dir.getAbsolutePath()+File.separator+f1.getName().substring(0,f1.getName().lastIndexOf("."));
					plot_name += "_"+method.toString().replaceAll("/", " over ")+".tif";
					IJ.save(plot_image, plot_name);
				}
			}
			
		}
		
	}
	
	/**
	 * Simple method to plot the data using {ij.gui.Plot #Plot}
	 * @param frc_curve is the raw FRC curve
	 * @param smooth_frc is the smoothed curve computed using {getSmoothedCurve(double[][])} or {getSmoothedCurve(double[][], double, int)}
	 * @param tm is the {ThresholdMethod} used
	 * @param fire is the Fourier Image Resolution given by {calculateFireNumber(double[][], ThresholdMethod)}
	 * @param name is the title of the image that was used for the FRC calculations
	 * @return a Plot object to use at your convenience
	 */
	public Plot doPlot(double[][] frc_curve, double[][] smooth_frc, ThresholdMethod tm, double fire, String name) {
		
		FRC frc = new FRC();
		
		
		// Prepare arrays for Plot class
		double[] x  = new double[frc_curve.length];
		double[] y  = new double[frc_curve.length];
		double[] sy = new double[frc_curve.length];
		
		
		for(int i=0; i<frc_curve.length; i++) {
			// Since the Fourier calculation only uses half of the image (from centre to the edge) 
			// we must double the curve length to get the original maximum image width. In addition
			// the computation was up to the edge-1 pixels so add back a pixel to the curve length.
			// If we divide the value of the x axes by the highest spatial frequency (representing 1 pixel^-1)
			// we can get a calibrated frequency axis.
			x[i]  =  frc_curve[i][0]/(2*(frc_curve.length+1));
			
			// Original FRC curve
			y[i]  =  frc_curve[i][1];
			
			// Smoothed FRC Curve
			sy[i] = smooth_frc[i][1];
		}
		// Get Curve of Threshold
		
		// Curve representing the Threshold method calculation that should intercept with the FRC Curve
		double[] thr_curve = frc.calculateThresholdCurve(smooth_frc, tm);
		
		// Plot the data
		Plot p = new Plot("FRC Of "+name, "Spatial Frequency", "Correlation");

		p.setLineWidth(1);

		// Set Limits
		p.setLimits(0, x[x.length-1], 0, 1);
		
		// Add Original Data in black
		p.setColor(new Color(0, 0, 0));
		p.addPoints(x, y, PlotWindow.LINE);
		
		// Add Smoothed Curve in clear red
		p.setColor(new Color(255, 120, 120));
		p.addPoints(x, sy, PlotWindow.LINE);

		// Add Threshold Curve in clear blue
		p.setColor(new Color(120, 120, 255));
		p.addPoints(x, thr_curve, PlotWindow.LINE);

		// Add FIRE number line in gray
		p.setColor(new Color(69, 69, 69));
		p.drawLine(1/fire, 0, 1/fire, 1);
		
		// Add the FIRE number on the graph
		p.addLabel(0.02, 0.3, "FIRE = "+String.format("%.3f", fire));
		
		// Add legend to the plot
		p.addLegend("FRC\nSmoothed FRC\nThreshold");
		
		return p;
	}
	
}
