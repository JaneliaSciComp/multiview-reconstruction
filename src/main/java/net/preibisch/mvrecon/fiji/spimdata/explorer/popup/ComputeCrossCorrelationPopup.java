/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.fiji.spimdata.explorer.popup;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Date;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import ij.gui.GenericDialog;

import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.GroupedRowWindow;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;
import net.preibisch.mvrecon.process.phasecorrelation.PhaseCorrelationPeak2;

public class ComputeCrossCorrelationPopup extends JMenuItem implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;
	ExplorerWindow<?> panel;

	public ComputeCrossCorrelationPopup()
	{
		super("Compute Cross-Correlation for Selected Tiles");
		this.addActionListener(new MyActionListener());
	}

	@Override
	public JComponent setExplorerWindow(ExplorerWindow<?> panel)
	{
		this.panel = panel;
		return this;
	}

	private class MyActionListener implements ActionListener
	{
		@Override
		public void actionPerformed(ActionEvent e)
		{
			if (panel == null)
			{
				IOFunctions.println("Panel not set for " + this.getClass().getSimpleName());
				return;
			}

			new Thread(new Runnable()
			{
				@Override
				public void run()
				{
					try
					{
						// Get SpimData
						final SpimData2 spimData = panel.getSpimData();

						// Step 1: Validate selection - exactly 2 tiles
						List<List<ViewId>> selectedGroups = ((GroupedRowWindow) panel).selectedRowsViewIdGroups();

						if (selectedGroups.size() != 2)
						{
							IOFunctions.println(new Date(System.currentTimeMillis()) +
									": ERROR: Please select exactly 2 tiles. Currently selected: " +
									selectedGroups.size());
							return;
						}

						// Filter missing views
						for (List<ViewId> group : selectedGroups)
						{
							SpimData2.filterMissingViews(spimData, group);
						}

						// Check that each group has at least one view
						if (selectedGroups.get(0).isEmpty() || selectedGroups.get(1).isEmpty())
						{
							IOFunctions.println(new Date(System.currentTimeMillis()) +
									": ERROR: Selected groups contain no valid views");
							return;
						}

						// Get first ViewId from each group
						ViewId viewId1 = selectedGroups.get(0).get(0);
						ViewId viewId2 = selectedGroups.get(1).get(0);

//						IOFunctions.println(new Date(System.currentTimeMillis()) +
//								": Computing cross-correlation between tiles...");
//						IOFunctions.println("  Tile 1: ViewId(" + viewId1.getTimePointId() + ", " +
//								viewId1.getViewSetupId() + ")");
//						IOFunctions.println("  Tile 2: ViewId(" + viewId2.getTimePointId() + ", " +
//								viewId2.getViewSetupId() + ")");

						// Step 2: Calculate overlap using SimpleBoundingBoxOverlap
						SimpleBoundingBoxOverlap<ViewId> overlapDetection = new SimpleBoundingBoxOverlap<>(spimData);

						RealInterval globalOverlap = overlapDetection.getOverlapInterval(viewId1, viewId2);

						if (globalOverlap == null)
						{
							IOFunctions.println(new Date(System.currentTimeMillis()) +
									": ERROR: Selected tiles do not overlap");
							return;
						}

                        // excess debug
//						// Print global overlap region
//					StringBuilder sb = new StringBuilder("(");
//					for (int d = 0; d < globalOverlap.numDimensions(); d++)
//					{
//						if (d > 0) sb.append(", ");
//						sb.append(String.format("%.1f", globalOverlap.realMin(d)));
//						sb.append(" - ");
//						sb.append(String.format("%.1f", globalOverlap.realMax(d)));
//					}
//					sb.append(")");
//					IOFunctions.println("  Global overlap region: " + sb);

						// Step 3: Get bounding boxes and convert overlap to local coordinates
						BasicViewSetup vs1 = spimData.getSequenceDescription().getViewSetups().get(viewId1.getViewSetupId());
						BasicViewSetup vs2 = spimData.getSequenceDescription().getViewSetups().get(viewId2.getViewSetupId());
						ViewRegistration vr1 = spimData.getViewRegistrations().getViewRegistration(viewId1);
						ViewRegistration vr2 = spimData.getViewRegistrations().getViewRegistration(viewId2);

						vr1.updateModel();
						vr2.updateModel();

						RealInterval bbox1 = SimpleBoundingBoxOverlap.getBoundingBoxReal(vs1, vr1);
						RealInterval bbox2 = SimpleBoundingBoxOverlap.getBoundingBoxReal(vs2, vr2);

						// Convert global overlap to local coordinates
						double[] localMin1 = new double[globalOverlap.numDimensions()];
						double[] localMax1 = new double[globalOverlap.numDimensions()];
						double[] localMin2 = new double[globalOverlap.numDimensions()];
						double[] localMax2 = new double[globalOverlap.numDimensions()];

						for (int d = 0; d < globalOverlap.numDimensions(); d++)
						{
							localMin1[d] = globalOverlap.realMin(d) - bbox1.realMin(d);
							localMax1[d] = globalOverlap.realMax(d) - bbox1.realMin(d);
							localMin2[d] = globalOverlap.realMin(d) - bbox2.realMin(d);
							localMax2[d] = globalOverlap.realMax(d) - bbox2.realMin(d);
						}

						// Convert to raster coordinates with bounds checking
						long[] dims1 = new long[globalOverlap.numDimensions()];
						long[] dims2 = new long[globalOverlap.numDimensions()];
						vs1.getSize().dimensions(dims1);
						vs2.getSize().dimensions(dims2);

						long[] rasterMin1 = new long[globalOverlap.numDimensions()];
						long[] rasterMax1 = new long[globalOverlap.numDimensions()];
						long[] rasterMin2 = new long[globalOverlap.numDimensions()];
						long[] rasterMax2 = new long[globalOverlap.numDimensions()];

						for (int d = 0; d < globalOverlap.numDimensions(); d++)
						{
							rasterMin1[d] = Math.max(0, (long) Math.ceil(localMin1[d]));
							rasterMax1[d] = Math.min(dims1[d] - 1, (long) Math.floor(localMax1[d]));
							rasterMin2[d] = Math.max(0, (long) Math.ceil(localMin2[d]));
							rasterMax2[d] = Math.min(dims2[d] - 1, (long) Math.floor(localMax2[d]));
						}

						// Step 4: Ask user for downsampling level
						// Get available downsampling levels from the data pyramid
						String[] availableDownsamplings = DownsampleTools.availableDownsamplings(spimData, viewId1);

						final GenericDialog gd = new GenericDialog("Cross-Correlation Parameters");
						gd.addChoice("Downsampling", availableDownsamplings, availableDownsamplings[0]);
						gd.addMessage("Choose downsampling level:");
						gd.addMessage("1, 1, 1 = full resolution (slower, more accurate)");
						gd.addMessage("Higher values = faster computation (less accurate)");
						gd.showDialog();

						if (gd.wasCanceled())
						{
							IOFunctions.println(new Date(System.currentTimeMillis()) +
									": Cross-correlation computation cancelled by user");
							return;
						}

						final String downsamplingChoice = gd.getNextChoice();
						final long[] downsampleFactors = DownsampleTools.parseDownsampleChoice(downsamplingChoice);

						IOFunctions.println("  Using downsampling: " + downsamplingChoice);

						// Adjust overlap intervals for downsampling
						long[] dsRasterMin1 = new long[rasterMin1.length];
						long[] dsRasterMax1 = new long[rasterMax1.length];
						long[] dsRasterMin2 = new long[rasterMin2.length];
						long[] dsRasterMax2 = new long[rasterMax2.length];

						for (int d = 0; d < rasterMin1.length; d++)
						{
							dsRasterMin1[d] = rasterMin1[d] / downsampleFactors[d];
							dsRasterMax1[d] = rasterMax1[d] / downsampleFactors[d];
							dsRasterMin2[d] = rasterMin2[d] / downsampleFactors[d];
							dsRasterMax2[d] = rasterMax2[d] / downsampleFactors[d];
						}

						Interval dsInterval1 = new FinalInterval(dsRasterMin1, dsRasterMax1);
						Interval dsInterval2 = new FinalInterval(dsRasterMin2, dsRasterMax2);

					// Step 5: Load image data at specified downsampling level using pyramid
					final BasicImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();

					// Open images at the specified downsampling level using pre-computed pyramid
					net.imglib2.util.Pair<RandomAccessibleInterval, AffineTransform3D> opened1 =
							DownsampleTools.openAndDownsample(imgLoader, viewId1, downsampleFactors, false);
					net.imglib2.util.Pair<RandomAccessibleInterval, AffineTransform3D> opened2 =
							DownsampleTools.openAndDownsample(imgLoader, viewId2, downsampleFactors, false);

					RandomAccessibleInterval<FloatType> img1 = opened1.getA();
					RandomAccessibleInterval<FloatType> img2 = opened2.getA();

						// Extract overlap regions and convert to FloatType
						RandomAccessibleInterval<FloatType> overlap1 = Views.zeroMin(
								Views.interval(Views.zeroMin(img1), dsInterval1));
						RandomAccessibleInterval<FloatType> overlap2 = Views.zeroMin(
								Views.interval(Views.zeroMin(img2), dsInterval2));

						// Step 6: Compute cross-correlation directly
						IOFunctions.println("  Computing cross-correlation...");

						// Create a peak with zero shift to compute correlation at current alignment
						Point zeroShift = new Point(overlap1.numDimensions());
						PhaseCorrelationPeak2 peak = new PhaseCorrelationPeak2(zeroShift, 0.0);

						// Set the shift field (required by calculateCrossCorr)
						peak.setShift(zeroShift);

						// Calculate cross-correlation at zero shift (current alignment)
						peak.calculateCrossCorr(overlap1, overlap2);

						// Step 7: Log results
						double correlationCoefficient = peak.getCrossCorr();
						long nPixels = peak.getnPixel();

						IOFunctions.println(new Date(System.currentTimeMillis()) +
                                String.format(" %d-%d <> %d-%d", viewId1.getTimePointId(), viewId1.getViewSetupId(),
                                        viewId2.getTimePointId(), viewId2.getViewSetupId())
                                + String.format(": r=%.4f", correlationCoefficient));

					}
					catch (Exception ex)
					{
						IOFunctions.println(new Date(System.currentTimeMillis()) +
								": ERROR: Exception during correlation computation: " + ex.getMessage());
						ex.printStackTrace();
					}
				}
			}).start();
		}
	}
}
