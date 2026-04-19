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
package net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive;

import java.awt.Color;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collection;

import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Overlay;
import net.imglib2.Point;
import net.imglib2.RealLocalizable;
import net.imglib2.algorithm.localextrema.RefinedPeak;
import net.imglib2.util.Util;

public class HelperFunctions {

	public static double computeSigma2(final double sigma1, final int stepsPerOctave) {
		final double k = Math.pow(2f, 1f / stepsPerOctave);
		return sigma1 * k;
	}

	public static double computeValueFromScrollbarPosition(final int scrollbarPosition, final double min, final double max,
			final int scrollbarSize) {
		return min + (scrollbarPosition / (float) scrollbarSize) * (max - min);
	}

	public static int computeScrollbarPositionFromValue(final double sigma, final double min, final double max,
			final int scrollbarSize) {
		return (int)Util.round(((sigma - min) / (max - min)) * scrollbarSize);
	}

	// check if peak is inside of the rectangle
	protected static <P extends RealLocalizable> boolean isInside(final P peak, final Rectangle rectangle) {
		if (rectangle == null)
			return true;

		final float x = peak.getFloatPosition(0);
		final float y = peak.getFloatPosition(1);

		boolean res = (x >= (rectangle.x) && y >= (rectangle.y) && x < (rectangle.width + rectangle.x - 1)
				&& y < (rectangle.height + rectangle.y - 1));

		return res;
	}

	public static ArrayList<RefinedPeak<Point>> filterPeaks(final ArrayList<RefinedPeak<Point>> peaks,
			final Rectangle rectangle, final double threshold) {
		final ArrayList<RefinedPeak<Point>> filtered = new ArrayList<>();

		for (final RefinedPeak<Point> peak : peaks)
			if (HelperFunctions.isInside(peak, rectangle) && (Math.abs(peak.getValue()) > threshold)) 
				// I guess the peak.getValue function returns the value in scale-space
				filtered.add(peak);

		return filtered;
	}

	// TODO: code might be reused instead of copy\pasting
	public static ArrayList<RefinedPeak<Point>> filterPeaks(final ArrayList<RefinedPeak<Point>> peaks, final double threshold) {
		final ArrayList<RefinedPeak<Point>> filtered = new ArrayList<>();
		
		for (final RefinedPeak<Point> peak : peaks)
			if (-peak.getValue() > threshold)
				filtered.add(peak);
		
		return filtered;
	}

	public static <L extends RealLocalizable> void drawRealLocalizable(final Collection<L> peaks, final ImagePlus imp,
			final double radius, final Color col, final boolean clearFirst) {
		// extract peaks to show
		// we will overlay them with RANSAC result
		Overlay overlay = imp.getOverlay();

		if (overlay == null) {
			// System.out.println("If this message pops up probably something
			// went wrong.");
			overlay = new Overlay();
			imp.setOverlay(overlay);
		}

		if (clearFirst)
			overlay.clear();

		// 'channel', 'slice' and 'frame' are one-based indexes
		final int currentSlice = imp.getZ() - 1;

		for (final L peak : peaks) {

			// determine Z distance from peak center and adjust scale radius
			final float x = peak.getFloatPosition(0);
			final float y = peak.getFloatPosition(1);
			final float zDistance = Math.abs(peak.getFloatPosition(2) - currentSlice) + 1;
			double drawRadius = 1.5 * radius / Math.sqrt( zDistance );

			// only draw nearby peaks
			if ( drawRadius > 0.67 )
			{
				final OvalRoi or = new OvalRoi(x - radius + 0.5, y - radius + 0.5, drawRadius * 2, drawRadius * 2);
				or.setStrokeColor(col);
				overlay.add(or);
			}
		}

		// this part might be useful for debugging
		// for lab meeting to show the parameters
		// final OvalRoi sigmaRoi = new OvalRoi(50, 10, 0, 0);
		// sigmaRoi.setStrokeWidth(1);
		// sigmaRoi.setStrokeColor(new Color(255, 255, 255));
		// sigmaRoi.setName("sigma : " + String.format(java.util.Locale.US,
		// "%.2f", sigma));
		//
		// final OvalRoi srRoi = new OvalRoi(72, 26, 0, 0);
		// srRoi.setStrokeWidth(1);
		// srRoi.setStrokeColor(new Color(255, 255, 255));
		// srRoi.setName("support radius : " + supportRadius);
		//
		// final OvalRoi irRoi = new OvalRoi(68, 42, 0, 0);
		// irRoi.setStrokeWidth(1);
		// irRoi.setStrokeColor(new Color(255, 255, 255));
		// irRoi.setName("inlier ratio : " + String.format(java.util.Locale.US,
		// "%.2f", inlierRatio));
		//
		// final OvalRoi meRoi = new OvalRoi(76, 58, 0, 0);
		// meRoi.setStrokeWidth(1);
		// meRoi.setStrokeColor(new Color(255, 255, 255));
		// meRoi.setName("max error : " + String.format(java.util.Locale.US,
		// "%.4f", maxError));
		//
		// // output sigma
		// // Support radius
		// // inlier ratio
		// // Max error
		// overlay.add(sigmaRoi);
		// overlay.add(srRoi);
		// overlay.add(irRoi);
		// overlay.add(meRoi);
		//
		// overlay.setLabelFont(new Font("SansSerif", Font.PLAIN, 16));
		// overlay.drawLabels(true); // allow labels
		// overlay.drawNames(true); // replace numbers with name

		imp.updateAndDraw();
	}

}
