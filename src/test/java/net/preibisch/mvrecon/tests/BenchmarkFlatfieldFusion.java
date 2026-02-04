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
package net.preibisch.mvrecon.tests;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.FlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.ViewerFlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import util.BlockSupplierUtils;

/**
 * Benchmark to measure the performance overhead of lazy flatfield correction during multi-view fusion.
 *
 * This benchmark:
 * 1. Loads a dataset with flatfield correction configured (data/dataset_corrected_viewer.xml)
 * 2. Toggles flatfield correction on/off using setActive()
 * 3. Runs fusion with and without correction
 * 4. Measures and reports timing comparison
 *
 * Run with: mvn compile exec:java -Dexec.mainClass="net.preibisch.mvrecon.tests.BenchmarkFlatfieldFusion"
 * Or run from IDE as a Java application.
 */
public class BenchmarkFlatfieldFusion {
	// Benchmark configuration
	private static final double DOWNSAMPLING = 4.0;
	private static final double ANISOTROPY_FACTOR = 3.0; // matches dataset's 1x1x3 voxel size
	private static final int WARMUP_ITERATIONS = 10;
	private static final int BENCHMARK_ITERATIONS = 50;
	private static final FusionType FUSION_TYPE = FusionType.AVG_BLEND;
	private static final int[] BLOCK_SIZE = new int[] {64, 64, 64};

	public static void main(String[] args) {
		// Path to dataset with flatfield correction
		final File xmlFile = new File("data/dataset_corrected_viewer.xml");

		if (!xmlFile.exists()) {
			System.err.println("Dataset not found: " + xmlFile.getAbsolutePath());
			System.err.println("Please ensure data/dataset_corrected_viewer.xml exists.");
			System.exit(1);
		}

		try {
			runBenchmark(xmlFile);
		} catch (Exception e) {
			System.err.println("Benchmark failed: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
		}
	}

	private static void runBenchmark(final File xmlFile) throws SpimDataException {
		System.out.println("============================================================");
		System.out.println("Flatfield Correction Benchmark During Fusion");
		System.out.println("============================================================");
		System.out.println();

		// Load the dataset
		System.out.println("Loading dataset: " + xmlFile.getAbsolutePath());
		final SpimData2 spimData = new XmlIoSpimData2().load(xmlFile.toURI());

		// Get the flatfield-wrapped image loader
		// Support both FlatfieldCorrectionWrappedImgLoader (interface) and ViewerFlatfieldCorrectionWrappedImgLoader (class)
		final FlatfieldCorrectionWrappedImgLoader<?> ffLoader;
		final ViewerFlatfieldCorrectionWrappedImgLoader viewerFfLoader;

		if (spimData.getSequenceDescription().getImgLoader() instanceof FlatfieldCorrectionWrappedImgLoader) {
			ffLoader = (FlatfieldCorrectionWrappedImgLoader<?>) spimData.getSequenceDescription().getImgLoader();
			viewerFfLoader = null;
		} else if (spimData.getSequenceDescription().getImgLoader() instanceof ViewerFlatfieldCorrectionWrappedImgLoader) {
			ffLoader = null;
			viewerFfLoader = (ViewerFlatfieldCorrectionWrappedImgLoader) spimData.getSequenceDescription().getImgLoader();
		} else {
			System.err.println("Dataset does not have a flatfield correction image loader.");
			System.err.println("ImgLoader type: " + spimData.getSequenceDescription().getImgLoader().getClass().getName());
			return;
		}

		// Create a lambda to toggle flatfield correction for either loader type
		final Consumer<Boolean> setActive = (active) -> {
			if (ffLoader != null)
				ffLoader.setActive(active);
			else
				viewerFfLoader.setActive(active);
		};

		// Get all views
		final List<ViewId> viewIds = new ArrayList<>();
		viewIds.addAll(spimData.getSequenceDescription().getViewDescriptions().values());
		SpimData2.filterMissingViews(spimData, viewIds);

		System.out.println();
		System.out.println("Dataset Information:");
		System.out.println("  Views: " + viewIds.size());
		System.out.println("  Downsampling: " + DOWNSAMPLING + ", Anisotropy: " + ANISOTROPY_FACTOR);
		System.out.println("  Fusion type: " + FUSION_TYPE);
		System.out.println("  Block size: " + BLOCK_SIZE[0] + "x" + BLOCK_SIZE[1] + "x" + BLOCK_SIZE[2]);
		System.out.println("  Warmup iterations: " + WARMUP_ITERATIONS + ", Benchmark iterations: " + BENCHMARK_ITERATIONS);

		// Compute bounding box
		final BoundingBox bb = new BoundingBoxMaximal(viewIds, spimData).estimate("benchmark");
		System.out.println("  Bounding box: [" + bb.getMin()[0] + ", " + bb.getMin()[1] + ", " + bb.getMin()[2] +
				"] to [" + bb.getMax()[0] + ", " + bb.getMax()[1] + ", " + bb.getMax()[2] + "]");

		// Apply anisotropy and downsampling to bounding box
		Interval boundingBoxFusion = FusionTools.createAnisotropicBoundingBox(bb, ANISOTROPY_FACTOR).getA();
		boundingBoxFusion = FusionTools.createDownsampledBoundingBox(boundingBoxFusion, DOWNSAMPLING).getA();

		final long[] dims = boundingBoxFusion.dimensionsAsLongArray();
		System.out.println("  Fusion dimensions: " + dims[0] + " x " + dims[1] + " x " + dims[2]);
		System.out.println();

		// Adjust view registrations for anisotropy and downsampling
		final HashMap<ViewId, AffineTransform3D> registrations =
				TransformVirtual.adjustAllTransforms(
						viewIds,
						spimData.getViewRegistrations().getViewRegistrations(),
						ANISOTROPY_FACTOR,
						DOWNSAMPLING);

		// Warmup runs
		System.out.println("--- Warmup Phase ---");

		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			System.out.print("  Warmup WITHOUT FF (" + (i + 1) + "/" + WARMUP_ITERATIONS + ")... ");
			setActive.accept(false);
			runFusion(spimData, viewIds, registrations, boundingBoxFusion);
			System.out.println("done");
		}

		for (int i = 0; i < WARMUP_ITERATIONS; i++) {
			System.out.print("  Warmup WITH FF (" + (i + 1) + "/" + WARMUP_ITERATIONS + ")... ");
			setActive.accept(true);
			runFusion(spimData, viewIds, registrations, boundingBoxFusion);
			System.out.println("done");
		}

		System.out.println();

		// Benchmark WITHOUT flatfield correction
		System.out.println("--- Benchmark Phase ---");
		System.out.println("Running WITHOUT flatfield correction...");
		setActive.accept(false);
		final long[] timesWithout = new long[BENCHMARK_ITERATIONS];

		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			System.out.print("  Iteration " + (i + 1) + "/" + BENCHMARK_ITERATIONS + ": ");
			final long start = System.currentTimeMillis();
			runFusion(spimData, viewIds, registrations, boundingBoxFusion);
			timesWithout[i] = System.currentTimeMillis() - start;
			System.out.println(timesWithout[i] + " ms");
		}

		System.out.println();

		// Benchmark WITH flatfield correction
		System.out.println("Running WITH flatfield correction...");
		setActive.accept(true);
		final long[] timesWith = new long[BENCHMARK_ITERATIONS];

		for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
			System.out.print("  Iteration " + (i + 1) + "/" + BENCHMARK_ITERATIONS + ": ");
			final long start = System.currentTimeMillis();
			runFusion(spimData, viewIds, registrations, boundingBoxFusion);
			timesWith[i] = System.currentTimeMillis() - start;
			System.out.println(timesWith[i] + " ms");
		}

		System.out.println();

		// Calculate and report statistics
		final Stats statsWithout = calculateStats(timesWithout);
		final Stats statsWith = calculateStats(timesWith);

		System.out.println("--- Benchmark Results ---");
		System.out.printf("Without FF: avg %d ms (min %d, max %d, stddev %.1f)%n",
				statsWithout.avg, statsWithout.min, statsWithout.max, statsWithout.stddev);
		System.out.printf("With FF:    avg %d ms (min %d, max %d, stddev %.1f)%n",
				statsWith.avg, statsWith.min, statsWith.max, statsWith.stddev);

		final long overhead = statsWith.avg - statsWithout.avg;
		final double overheadPercent = 100.0 * overhead / statsWithout.avg;

		System.out.println();
		System.out.printf("Overhead: %+d ms (%+.1f%%)%n", overhead, overheadPercent);
		System.out.println("============================================================");
	}

	/**
	 * Run fusion and force full computation by copying to ArrayImg.
	 */
	private static void runFusion(
			final SpimData2 spimData,
			final List<ViewId> viewIds,
			final HashMap<ViewId, AffineTransform3D> registrations,
			final Interval boundingBoxFusion) {
		// Create fusion BlockSupplier
		final BlockSupplier<UnsignedShortType> blocks = BlkAffineFusion.init(
				(i, o) -> o.set(Math.round(i.get())),
				spimData.getSequenceDescription().getImgLoader(),
				viewIds,
				registrations,
				spimData.getSequenceDescription().getViewDescriptions(),
				FUSION_TYPE,
				ANISOTROPY_FACTOR,
				1, // interpolation: linear
				null, // no intensity adjustments
				boundingBoxFusion,
				new UnsignedShortType(),
				BLOCK_SIZE);

		// Force full computation by copying to ArrayImg (not lazy CellImg)
		// This ensures all flatfield calculations are performed
		BlockSupplierUtils.arrayImg(blocks, new FinalInterval(boundingBoxFusion.dimensionsAsLongArray()));
	}

	private static Stats calculateStats(final long[] times) {
		long sum = 0;
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;

		for (final long t : times) {
			sum += t;
			min = Math.min(min, t);
			max = Math.max(max, t);
		}

		final long avg = sum / times.length;

		double variance = 0;
		for (final long t : times) {
			variance += (t - avg) * (t - avg);
		}
		variance /= times.length;
		final double stddev = Math.sqrt(variance);

		return new Stats(avg, min, max, stddev);
	}

	private static class Stats {
		final long avg;
		final long min;
		final long max;
		final double stddev;

		Stats(long avg, long min, long max, double stddev) {
			this.avg = avg;
			this.min = min;
			this.max = max;
			this.stddev = stddev;
		}
	}
}
