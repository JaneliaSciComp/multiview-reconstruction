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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import ij.gui.GenericDialog;

import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.converter.Converters;
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

	/**
	 * Class to store correlation result for a pair of views
	 */
	private static class CorrelationResult
	{
		final ViewId viewId1;
		final ViewId viewId2;
		final double correlation;
		final double avgIntensity1;
		final double avgIntensity2;

		CorrelationResult(ViewId viewId1, ViewId viewId2, double correlation, double avgIntensity1, double avgIntensity2)
		{
			this.viewId1 = viewId1;
			this.viewId2 = viewId2;
			this.correlation = correlation;
			this.avgIntensity1 = avgIntensity1;
			this.avgIntensity2 = avgIntensity2;
		}
	}

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
                        List<Integer> allChannels = new ArrayList<>(spimData.getSequenceDescription().getAllChannels().keySet());
                        List<String> allChannelsStr = allChannels.stream()
                                .map(i -> String.format("Channel %d", i))
                                .collect(Collectors.toList());
                        allChannelsStr.add("Compare across all channels");
                        String[] allChannelsArr = allChannelsStr.toArray(new String[0]);


						// Show dialog with checkbox and downsampling choice
						final GenericDialog gd = new GenericDialog("Cross-Correlation Parameters");
						gd.addCheckbox("Only compute for selected tiles (requires at least 2 selected)", false);
						gd.addMessage("Choose downsampling level:");
						gd.addMessage("1, 1, 1 = full resolution (slower, more accurate)");
						gd.addMessage("Higher values = faster computation (less accurate)");
                        gd.addChoice("Downsampling", availableDownsamplings, availableDownsamplings[0]);
                        gd.addChoice("Channel", allChannelsArr, allChannelsArr[0]);
						gd.showDialog();

						if (gd.wasCanceled())
						{
							IOFunctions.println(new Date(System.currentTimeMillis()) +
									": Cross-correlation computation cancelled by user");
							return;
						}

						final boolean selectedOnly = gd.getNextBoolean();
						final String downsamplingChoice = gd.getNextChoice();
                        final String chChoiceStr = gd.getNextChoice();
						final long[] downsampleFactors = DownsampleTools.parseDownsampleChoice(downsamplingChoice);

                        final int chChoice;
                        if (chChoiceStr.equals("Compare across all channels")) {
                            chChoice = -1;
                        }
                        else {
                            String[] parts = chChoiceStr.split(" ");
                            chChoice = Integer.parseInt(parts[parts.length - 1]);
                        }

						IOFunctions.println(new Date(System.currentTimeMillis()) +
								": Computing cross-correlations...");
						IOFunctions.println("  Using downsampling: " + downsamplingChoice);
                        IOFunctions.println("  Using channel: " + chChoiceStr);

						// Get the list of views to process
						List<ViewId> viewIdsToProcess;

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
							viewIdsToProcess = new ArrayList<>();
							for (List<ViewId> group : selectedGroups)
							{
								SpimData2.filterMissingViews(spimData, group);
								if (!group.isEmpty())
								{
									// Get first ViewId from each group
									viewIdsToProcess.add(group.get(0));
								}
							}

							if (viewIdsToProcess.size() < 2)
							{
								IOFunctions.println(new Date(System.currentTimeMillis()) +
										": ERROR: Selected groups contain fewer than 2 valid views");
								return;
							}

							IOFunctions.println("  Processing " + viewIdsToProcess.size() + " selected views");
						}
						else
						{
							// Get all views
							viewIdsToProcess = new ArrayList<>();
							for (ViewId viewId : allViewIds)
							{
								if (!spimData.getSequenceDescription().getMissingViews().getMissingViews().contains(viewId))
								{
									viewIdsToProcess.add(viewId);
								}
							}

							IOFunctions.println("  Found " + viewIdsToProcess.size() + " valid views");
						}

						// Find all overlapping pairs
						List<ViewId[]> pairsToProcess = findOverlappingPairs(
								spimData, viewIdsToProcess, chChoice);

						IOFunctions.println("  Found " + pairsToProcess.size() + " overlapping pairs" +
								(selectedOnly ? " among selected views" : ""));

						// Process all pairs in parallel
						final int numThreads = Math.min(pairsToProcess.size(), Runtime.getRuntime().availableProcessors());
						final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
						final AtomicInteger successCount = new AtomicInteger(0);
						final List<CorrelationResult> results = Collections.synchronizedList(new ArrayList<>());

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
										CorrelationResult result = computeCorrelationForPair(spimData, pair[0], pair[1], downsampleFactors);
										if (result != null)
										{
											results.add(result);
											successCount.incrementAndGet();
										}
									}
									catch (Exception ex)
									{
//										// Temporarily log first error to diagnose issue
//										if (successCount.get() == 0)
//										{
//											IOFunctions.println(new Date(System.currentTimeMillis()) +
//													": ERROR (first failure): " + ex.getMessage());
//											ex.printStackTrace();
//										}
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

						// Print summary table sorted by correlation (ascending)
						if (!results.isEmpty())
						{
							IOFunctions.println("\n" + new Date(System.currentTimeMillis()) +
									": Summary of correlations (sorted by correlation):");
							IOFunctions.println("================================================================================");
							IOFunctions.println(String.format("%-20s %-20s %12s %12s %12s",
									"ViewId 1", "ViewId 2", "Correlation", "Avg Int 1", "Avg Int 2"));
							IOFunctions.println("================================================================================");

							// Sort by correlation (ascending)
							Collections.sort(results, new Comparator<CorrelationResult>()
							{
								@Override
								public int compare(CorrelationResult r1, CorrelationResult r2)
								{
									return Double.compare(r1.correlation, r2.correlation);
								}
							});

							// Print each result
							for (CorrelationResult result : results)
							{
								String viewId1Str = String.format("%d-%d",
										result.viewId1.getTimePointId(), result.viewId1.getViewSetupId());
								String viewId2Str = String.format("%d-%d",
										result.viewId2.getTimePointId(), result.viewId2.getViewSetupId());
								IOFunctions.println(String.format("%-20s %-20s %12.4f %12.1f %12.1f",
										viewId1Str, viewId2Str, result.correlation,
										result.avgIntensity1, result.avgIntensity2));
							}
							IOFunctions.println("================================================================================");
						}
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
	private static CorrelationResult computeCorrelationForPair(
			SpimData2 spimData,
			ViewId viewId1,
			ViewId viewId2,
			long[] downsampleFactors
			) throws Exception
	{
		// Get view information
		BasicViewSetup vs1 = spimData.getSequenceDescription().getViewSetups().get(viewId1.getViewSetupId());
		BasicViewSetup vs2 = spimData.getSequenceDescription().getViewSetups().get(viewId2.getViewSetupId());
		ViewRegistration vr1 = spimData.getViewRegistrations().getViewRegistration(viewId1);
		ViewRegistration vr2 = spimData.getViewRegistrations().getViewRegistration(viewId2);

		// thread-safe update of the model and creation of a copy for processing
		AffineTransform3D m1, m2;

		synchronized (vr1)
		{
			vr1.updateModel();
			m1 = vr1.getModel().copy();
		}

		synchronized (vr2)
		{
			vr2.updateModel();
			m2 = vr2.getModel().copy();
		}

		// Calculate local overlaps using pixel validation that handles rotations correctly
		RealInterval[] localOverlaps = SimpleBoundingBoxOverlap.getLocalOverlapsUsingPixelValidation(
				vs1.getSize(), vs2.getSize(),
				m1, m2);

		if (localOverlaps == null)
		{
			throw new Exception("No overlap found");
		}

		RealInterval localOverlap1 = localOverlaps[0];
		RealInterval localOverlap2 = localOverlaps[1];

		// Get image dimensions
		long[] dims1 = new long[3];
		long[] dims2 = new long[3];
		vs1.getSize().dimensions(dims1);
		vs2.getSize().dimensions(dims2);

		// Debug: Print local overlaps
		IOFunctions.println(String.format("DEBUG: Analyzing %d-%d <> %d-%d",
				viewId1.getTimePointId(), viewId1.getViewSetupId(),
				viewId2.getTimePointId(), viewId2.getViewSetupId()));
		IOFunctions.println(String.format("  Image sizes: view1=[%d,%d,%d] view2=[%d,%d,%d]",
				dims1[0], dims1[1], dims1[2], dims2[0], dims2[1], dims2[2]));
		for (int d = 0; d < 3; d++)
		{
			IOFunctions.println(String.format("  Dim %d local: view1=[%.1f,%.1f] (size=%.1f) view2=[%.1f,%.1f] (size=%.1f)",
					d, localOverlap1.realMin(d), localOverlap1.realMax(d),
					localOverlap1.realMax(d) - localOverlap1.realMin(d),
					localOverlap2.realMin(d), localOverlap2.realMax(d),
					localOverlap2.realMax(d) - localOverlap2.realMin(d)));
		}

		// Convert to raster coordinates with bounds checking
		long[] rasterMin1 = new long[3];
		long[] rasterMax1 = new long[3];
		long[] rasterMin2 = new long[3];
		long[] rasterMax2 = new long[3];

		for (int d = 0; d < 3; d++)
		{
			rasterMin1[d] = Math.max(0, (long) Math.ceil(localOverlap1.realMin(d)));
			rasterMax1[d] = Math.min(dims1[d] - 1, (long) Math.floor(localOverlap1.realMax(d)));
			rasterMin2[d] = Math.max(0, (long) Math.ceil(localOverlap2.realMin(d)));
			rasterMax2[d] = Math.min(dims2[d] - 1, (long) Math.floor(localOverlap2.realMax(d)));
		}

		// Debug: Print raster coordinates
		for (int d = 0; d < 3; d++)
		{
			IOFunctions.println(String.format("  Dim %d raster: view1=[%d,%d] (size=%d) view2=[%d,%d] (size=%d)",
					d, rasterMin1[d], rasterMax1[d], rasterMax1[d] - rasterMin1[d] + 1,
					rasterMin2[d], rasterMax2[d], rasterMax2[d] - rasterMin2[d] + 1));
		}

		// Calculate downsampled image dimensions
		long[] dsDims1 = new long[3];
		long[] dsDims2 = new long[3];
		for (int d = 0; d < 3; d++)
		{
			dsDims1[d] = dims1[d] / downsampleFactors[d];
			dsDims2[d] = dims2[d] / downsampleFactors[d];
		}

		// Adjust overlap intervals for downsampling
		// Apply downsampling to real coordinates first to maintain alignment
		long[] dsRasterMin1 = new long[rasterMin1.length];
		long[] dsRasterMax1 = new long[rasterMax1.length];
		long[] dsRasterMin2 = new long[rasterMin2.length];
		long[] dsRasterMax2 = new long[rasterMax2.length];

		for (int d = 0; d < rasterMin1.length; d++)
		{
			// Calculate directly from real coordinates to preserve alignment
			dsRasterMin1[d] = Math.max(0, (long) Math.ceil(localOverlap1.realMin(d) / downsampleFactors[d]));
			dsRasterMax1[d] = Math.min(dsDims1[d] - 1, (long) Math.floor(localOverlap1.realMax(d) / downsampleFactors[d]));
			dsRasterMin2[d] = Math.max(0, (long) Math.ceil(localOverlap2.realMin(d) / downsampleFactors[d]));
			dsRasterMax2[d] = Math.min(dsDims2[d] - 1, (long) Math.floor(localOverlap2.realMax(d) / downsampleFactors[d]));
		}

		Interval dsInterval1 = new FinalInterval(dsRasterMin1, dsRasterMax1);
		Interval dsInterval2 = new FinalInterval(dsRasterMin2, dsRasterMax2);

        // check whether we have 0-sized (or negative sized)
        // ignore this pair in that case
        for ( int d = 0; d < dsInterval1.numDimensions(); ++d )
        {
            if ( dsInterval1.dimension( d ) <= 0 || dsInterval2.dimension( d ) <= 0)
            {
				// Debug logging
				IOFunctions.println(String.format("DEBUG: Zero overlap for %d-%d <> %d-%d",
						viewId1.getTimePointId(), viewId1.getViewSetupId(),
						viewId2.getTimePointId(), viewId2.getViewSetupId()));
				IOFunctions.println(String.format("  Dimension %d: view1=[%d,%d] (%d px), view2=[%d,%d] (%d px)",
						d, dsRasterMin1[d], dsRasterMax1[d], dsInterval1.dimension(d),
						dsRasterMin2[d], dsRasterMax2[d], dsInterval2.dimension(d)));
				IOFunctions.println(String.format("  Original raster: view1=[%d,%d], view2=[%d,%d]",
						rasterMin1[d], rasterMax1[d], rasterMin2[d], rasterMax2[d]));
				IOFunctions.println(String.format("  Downsampling factor: %d", downsampleFactors[d]));
                throw new Exception("Rastered overlap volume is zero, skipping." );
            }
        }


		// Load image data at specified downsampling level using pyramid
		final BasicImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();

		// Open images at the specified downsampling level using pre-computed pyramid
		net.imglib2.util.Pair<RandomAccessibleInterval, AffineTransform3D> opened1 =
				DownsampleTools.openAndDownsample(imgLoader, viewId1, downsampleFactors, false);
		net.imglib2.util.Pair<RandomAccessibleInterval, AffineTransform3D> opened2 =
				DownsampleTools.openAndDownsample(imgLoader, viewId2, downsampleFactors, false);

		RandomAccessibleInterval<?> img1Raw = opened1.getA();
		RandomAccessibleInterval<?> img2Raw = opened2.getA();

		// Convert to FloatType (handles any numeric type)
		@SuppressWarnings("unchecked")
		RandomAccessibleInterval<FloatType> img1 = Converters.convert(
				(RandomAccessibleInterval<RealType<?>>) img1Raw,
				(in, out) -> out.setReal(in.getRealDouble()),
				new FloatType());
		@SuppressWarnings("unchecked")
		RandomAccessibleInterval<FloatType> img2 = Converters.convert(
				(RandomAccessibleInterval<RealType<?>>) img2Raw,
				(in, out) -> out.setReal(in.getRealDouble()),
				new FloatType());

		// Extract overlap regions
		RandomAccessibleInterval<FloatType> overlap1 = Views.zeroMin(
				Views.interval(
						Views.zeroMin(img1),
						dsInterval1));
		RandomAccessibleInterval<FloatType> overlap2 = Views.zeroMin(
				Views.interval(
						Views.zeroMin(img2),
						dsInterval2));

		// Compute average intensities in the overlapping regions (with sampling for speed)
		double avgIntensity1 = computeAverageIntensity(overlap1, 10);
		double avgIntensity2 = computeAverageIntensity(overlap2, 10);

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

		// Log individual result
		IOFunctions.println(new Date(System.currentTimeMillis()) +
				String.format(" %d-%d <> %d-%d",
						viewId1.getTimePointId(), viewId1.getViewSetupId(),
						viewId2.getTimePointId(), viewId2.getViewSetupId()) +
				String.format(": r=%.4f avg int=[%.1f, %.1f]",
						correlationCoefficient, avgIntensity1, avgIntensity2));

		// Return result for summary table
		return new CorrelationResult(viewId1, viewId2, correlationCoefficient, avgIntensity1, avgIntensity2);
	}

	/**
	 * Get the channel ID for a given ViewId.
	 *
	 * @param spimData The SpimData2 object
	 * @param viewId The ViewId to query
	 * @return The channel ID, or -1 if channel information is not available
	 */
	private static int getChannelId(SpimData2 spimData, ViewId viewId)
	{
		mpicbg.spim.data.sequence.ViewDescription vd = spimData.getSequenceDescription().getViewDescriptions().get(viewId);
		if (vd != null && vd.getViewSetup().getChannel() != null)
		{
			return vd.getViewSetup().getChannel().getId();
		}
		return -1;
	}

	/**
	 * Compute average intensity of an image using sampling for efficiency.
	 * Samples every stepSize-th pixel in each dimension.
	 *
	 * @param img The image to sample
	 * @param stepSize Sampling step (e.g., 10 means sample every 10th pixel)
	 * @return Average intensity of sampled pixels
	 */
	private static double computeAverageIntensity(RandomAccessibleInterval<FloatType> img, int stepSize)
	{
		double sum = 0;
		long count = 0;

		// Create a cursor that samples at regular intervals
		final net.imglib2.Cursor<FloatType> cursor = Views.iterable(img).cursor();
		final long[] position = new long[img.numDimensions()];

		while (cursor.hasNext())
		{
			cursor.fwd();
			cursor.localize(position);

			// Sample only at regular intervals
			boolean shouldSample = true;
			for (int d = 0; d < position.length; d++)
			{
				if (position[d] % stepSize != 0)
				{
					shouldSample = false;
					break;
				}
			}

			if (shouldSample)
			{
				sum += cursor.get().get();
				count++;
			}
		}

		return count > 0 ? sum / count : 0.0;
	}

	/**
	 * Find all overlapping pairs among a list of views, with optional channel filtering.
	 *
	 * @param spimData The SpimData2 object
	 * @param viewIds List of views to check for overlaps
	 * @param chChoice Channel to filter by, or -1 for all channels
	 * @return List of overlapping view pairs
	 */
	private static List<ViewId[]> findOverlappingPairs(
			SpimData2 spimData,
			List<ViewId> viewIds,
			int chChoice)
	{
		List<ViewId[]> pairsToProcess = new ArrayList<>();
		SimpleBoundingBoxOverlap<ViewId> overlapDetection = new SimpleBoundingBoxOverlap<>(spimData);

		for (int i = 0; i < viewIds.size(); i++)
		{
			for (int j = i + 1; j < viewIds.size(); j++)
			{
				ViewId viewId1 = viewIds.get(i);
				ViewId viewId2 = viewIds.get(j);

				// Skip comparisons between different timepoints
				if (viewId1.getTimePointId() != viewId2.getTimePointId())
					continue;

				// Skip cross-channel comparisons if specific channel selected
				if (chChoice != -1)
				{
					int ch1 = getChannelId(spimData, viewId1);
					int ch2 = getChannelId(spimData, viewId2);
					if (ch1 != chChoice || ch2 != chChoice)
						continue;
				}

				RealInterval overlap = overlapDetection.getOverlapInterval(viewId1, viewId2);
				if (overlap != null)
				{
					pairsToProcess.add(new ViewId[] { viewId1, viewId2 });
				}
			}
		}

		return pairsToProcess;
	}
}
