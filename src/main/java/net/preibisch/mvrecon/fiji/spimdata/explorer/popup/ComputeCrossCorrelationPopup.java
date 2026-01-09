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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
		super("Compute Cross-Correlation for Overlapping Tiles");
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

						// Get selected groups for potential use
						List<List<ViewId>> selectedGroups = ((GroupedRowWindow) panel).selectedRowsViewIdGroups();

						// Get available downsampling levels
						List<ViewId> allViewIds = new ArrayList<>(spimData.getSequenceDescription().getViewDescriptions().keySet());
						if (allViewIds.isEmpty())
						{
							IOFunctions.println(new Date(System.currentTimeMillis()) +
									": ERROR: No views found in dataset");
							return;
						}

						ViewId sampleViewId = allViewIds.get(0);
						String[] availableDownsamplings = DownsampleTools.availableDownsamplings(spimData, sampleViewId);

						// Show dialog with checkbox and downsampling choice
						final GenericDialog gd = new GenericDialog("Cross-Correlation Parameters");
						gd.addCheckbox("Only compute for selected tiles (requires at least 2 selected)", false);
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

						final boolean selectedOnly = gd.getNextBoolean();
						final String downsamplingChoice = gd.getNextChoice();
						final long[] downsampleFactors = DownsampleTools.parseDownsampleChoice(downsamplingChoice);

						IOFunctions.println(new Date(System.currentTimeMillis()) +
								": Computing cross-correlations...");
						IOFunctions.println("  Using downsampling: " + downsamplingChoice);

						List<ViewId[]> pairsToProcess = new ArrayList<>();

						if (selectedOnly)
						{
							// Validate at least 2 tiles selected
							if (selectedGroups.size() < 2)
							{
								IOFunctions.println(new Date(System.currentTimeMillis()) +
										": ERROR: Please select at least 2 tiles. Currently selected: " +
										selectedGroups.size());
								return;
							}

							// Filter missing views and collect valid ViewIds from selected groups
							List<ViewId> selectedViewIds = new ArrayList<>();
							for (List<ViewId> group : selectedGroups)
							{
								SpimData2.filterMissingViews(spimData, group);
								if (!group.isEmpty())
								{
									// Get first ViewId from each group
									selectedViewIds.add(group.get(0));
								}
							}

							if (selectedViewIds.size() < 2)
							{
								IOFunctions.println(new Date(System.currentTimeMillis()) +
										": ERROR: Selected groups contain fewer than 2 valid views");
								return;
							}

							IOFunctions.println("  Processing " + selectedViewIds.size() + " selected views");

							SimpleBoundingBoxOverlap<ViewId> overlapDetection = new SimpleBoundingBoxOverlap<>(spimData);

							// Find all overlapping pairs among selected views
							for (int i = 0; i < selectedViewIds.size(); i++)
							{
								for (int j = i + 1; j < selectedViewIds.size(); j++)
								{
									ViewId viewId1 = selectedViewIds.get(i);
									ViewId viewId2 = selectedViewIds.get(j);

									RealInterval overlap = overlapDetection.getOverlapInterval(viewId1, viewId2);
									if (overlap != null)
									{
										pairsToProcess.add(new ViewId[] { viewId1, viewId2 });
									}
								}
							}

							IOFunctions.println("  Found " + pairsToProcess.size() + " overlapping pairs among selected views");
						}
						else
						{
							// Get all views and find overlapping pairs
							List<ViewId> viewIds = new ArrayList<>();
							for (ViewId viewId : allViewIds)
							{
								if (!spimData.getSequenceDescription().getMissingViews().getMissingViews().contains(viewId))
								{
									viewIds.add(viewId);
								}
							}

							IOFunctions.println("  Found " + viewIds.size() + " valid views");

							SimpleBoundingBoxOverlap<ViewId> overlapDetection = new SimpleBoundingBoxOverlap<>(spimData);

							// Find all overlapping pairs
							for (int i = 0; i < viewIds.size(); i++)
							{
								for (int j = i + 1; j < viewIds.size(); j++)
								{
									ViewId viewId1 = viewIds.get(i);
									ViewId viewId2 = viewIds.get(j);

									RealInterval overlap = overlapDetection.getOverlapInterval(viewId1, viewId2);
									if (overlap != null)
									{
										pairsToProcess.add(new ViewId[] { viewId1, viewId2 });
									}
								}
							}

							IOFunctions.println("  Found " + pairsToProcess.size() + " overlapping pairs");
						}

						// Process all pairs in parallel
						final int numThreads = Math.min(pairsToProcess.size(), Runtime.getRuntime().availableProcessors());
						final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
						final AtomicInteger successCount = new AtomicInteger(0);

						IOFunctions.println("  Using " + numThreads + " threads for parallel processing");

						List<Future<?>> futures = new ArrayList<>();

						for (final ViewId[] pair : pairsToProcess)
						{
							Future<?> future = executor.submit(new Runnable()
							{
								@Override
								public void run()
								{
									try
									{
										computeCorrelationForPair(spimData, pair[0], pair[1], downsampleFactors, downsamplingChoice);
										successCount.incrementAndGet();
									}
									catch (Exception ex)
									{
										// Silently skip pairs with errors (e.g., no overlap after downsampling)
									}
								}
							});
							futures.add(future);
						}

						// Wait for all tasks to complete
						for (Future<?> future : futures)
						{
							try
							{
								future.get();
							}
							catch (Exception ex)
							{
								// Already handled in the task
							}
						}

						// Shut down executor
						executor.shutdown();


						IOFunctions.println(new Date(System.currentTimeMillis()) +
								": Completed " + successCount.get() + " of " + pairsToProcess.size() + " correlations");
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

	/**
	 * Compute cross-correlation for a specific pair of views
	 */
	private static void computeCorrelationForPair(
			SpimData2 spimData,
			ViewId viewId1,
			ViewId viewId2,
			long[] downsampleFactors,
			String downsamplingChoice) throws Exception
	{
		// Calculate overlap using SimpleBoundingBoxOverlap
		SimpleBoundingBoxOverlap<ViewId> overlapDetection = new SimpleBoundingBoxOverlap<>(spimData);
		RealInterval globalOverlap = overlapDetection.getOverlapInterval(viewId1, viewId2);

		if (globalOverlap == null)
		{
			throw new Exception("No overlap found");
		}

		// Get bounding boxes and convert overlap to local coordinates
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


		// Validate that intervals are non-empty (have positive size in all dimensions)
		for (int d = 0; d < dsInterval1.numDimensions(); d++)
		{
			if (dsInterval1.min(d) > dsInterval1.max(d) || dsInterval2.min(d) > dsInterval2.max(d))
			{
				throw new Exception("Empty overlap interval after downsampling");
			}
		}

		// Calculate number of overlapping pixels to validate
		long numPixels = 1;
		for (int d = 0; d < dsInterval1.numDimensions(); d++)
		{
			numPixels *= (dsInterval1.max(d) - dsInterval1.min(d) + 1);
		}

		if (numPixels == 0)
		{
			throw new Exception("No overlapping pixels after downsampling");
		}

		// Load image data at specified downsampling level using pyramid
		final BasicImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();

		// Open images at the specified downsampling level using pre-computed pyramid
		net.imglib2.util.Pair<RandomAccessibleInterval, AffineTransform3D> opened1 =
				DownsampleTools.openAndDownsample(imgLoader, viewId1, downsampleFactors, false);
		net.imglib2.util.Pair<RandomAccessibleInterval, AffineTransform3D> opened2 =
				DownsampleTools.openAndDownsample(imgLoader, viewId2, downsampleFactors, false);

		RandomAccessibleInterval<?> img1 = opened1.getA();
		RandomAccessibleInterval<?> img2 = opened2.getA();

		// Extract overlap regions and convert to FloatType
		RandomAccessibleInterval<FloatType> overlap1 = Views.zeroMin(
				Views.interval(
						Views.zeroMin((RandomAccessibleInterval<FloatType>) img1),
						dsInterval1));
		RandomAccessibleInterval<FloatType> overlap2 = Views.zeroMin(
				Views.interval(
						Views.zeroMin((RandomAccessibleInterval<FloatType>) img2),
						dsInterval2));

		// Compute cross-correlation directly
		// Create a peak with zero shift to compute correlation at current alignment
		Point zeroShift = new Point(overlap1.numDimensions());
		PhaseCorrelationPeak2 peak = new PhaseCorrelationPeak2(zeroShift, 0.0);

		// Set the shift field (required by calculateCrossCorr)
		peak.setShift(zeroShift);

		// Calculate cross-correlation at zero shift (current alignment)
		peak.calculateCrossCorr(overlap1, overlap2);

		// Log results
		double correlationCoefficient = peak.getCrossCorr();
		long nPixels = peak.getnPixel();


		// Validate results before logging
		if (nPixels == 0)
		{
			throw new Exception("No overlapping pixels found");
		}

		if (!Double.isFinite(correlationCoefficient))
		{
			throw new Exception("Invalid correlation coefficient: " + correlationCoefficient);
		}

		IOFunctions.println(new Date(System.currentTimeMillis()) +
				String.format(" %d-%d <> %d-%d",
						viewId1.getTimePointId(), viewId1.getViewSetupId(),
						viewId2.getTimePointId(), viewId2.getViewSetupId()) +
				String.format(": r=%.4f (n=%d)", correlationCoefficient, nPixels));
	}
}
