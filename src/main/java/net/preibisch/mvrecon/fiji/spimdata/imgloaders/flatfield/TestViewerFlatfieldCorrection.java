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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import java.util.HashMap;

import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;

/**
 * Test class for XML-based ViewerFlatfieldCorrectionWrappedImgLoader.
 *
 * Demonstrates the full ViewerImgLoader-compatible decorator chain:
 *   N5ImageLoader (ViewerImgLoader)
 *       -> ViewerFlatfieldCorrectionWrappedImgLoader (ViewerImgLoader)
 *           -> SplitViewerImgLoader (ViewerImgLoader)
 *
 * Loads dataset_corrected_viewer.xml which has flatfield correction configured
 * directly in the ImageLoader section. No manual configuration needed!
 *
 * This maintains full BDV compatibility throughout the chain, including:
 * - Cache control delegation
 * - Volatile image support
 * - Multi-resolution mipmap levels
 */
public class TestViewerFlatfieldCorrection {
	public static void main(String[] args) throws SpimDataException {
		// Paths
		final String basePath = "/Users/innerbergerm/Projects/janelia/multiview-reconstruction/";
		final String correctedXmlPath = basePath + "data/dataset_corrected_viewer_s3.xml";
		final String uncorrectedXmlPath = basePath + "data/dataset.xml";

		// Which setup to demonstrate (0-8)
		final int setupToShow = 0;
		final int timepoint = 0;

		// ========== STEP 1: Load CORRECTED dataset (from XML with flatfield config) ==========
		System.out.println("=== STEP 1: Loading CORRECTED dataset ===");
		System.out.println("XML path: " + correctedXmlPath);

		final SpimData2 correctedData = new XmlIoSpimData2().load(correctedXmlPath);
		final SequenceDescription correctedSeqDesc = correctedData.getSequenceDescription();

		// Verify it's a ViewerFlatfieldCorrectionWrappedImgLoader
		if (!(correctedSeqDesc.getImgLoader() instanceof ViewerFlatfieldCorrectionWrappedImgLoader)) {
			System.err.println("ERROR: Expected ViewerFlatfieldCorrectionWrappedImgLoader!");
			System.err.println("Loader type: " + correctedSeqDesc.getImgLoader().getClass().getName());
			return;
		}

		final ViewerFlatfieldCorrectionWrappedImgLoader correctedLoader =
				(ViewerFlatfieldCorrectionWrappedImgLoader) correctedSeqDesc.getImgLoader();

		System.out.println("Corrected loader type: " + correctedLoader.getClass().getSimpleName());
		System.out.println("  Correction active: " + correctedLoader.isActive());
		System.out.println("  Caching enabled: " + correctedLoader.isCached());
		System.out.println("  Wrapped loader: " + correctedLoader.getWrappedImgLoader().getClass().getSimpleName());
		System.out.println("  Implements ViewerImgLoader: " + (correctedLoader instanceof ViewerImgLoader ? "YES" : "NO"));

		// ========== STEP 2: Load UNCORRECTED dataset (original XML) ==========
		System.out.println("\n=== STEP 2: Loading UNCORRECTED dataset ===");
		System.out.println("XML path: " + uncorrectedXmlPath);

		final SpimData2 uncorrectedData = new XmlIoSpimData2().load(uncorrectedXmlPath);
		final SequenceDescription uncorrectedSeqDesc = uncorrectedData.getSequenceDescription();

		// Verify the base loader is a ViewerImgLoader
		if (!(uncorrectedSeqDesc.getImgLoader() instanceof ViewerImgLoader)) {
			System.err.println("ERROR: Base loader is not a ViewerImgLoader!");
			System.err.println("Loader type: " + uncorrectedSeqDesc.getImgLoader().getClass().getName());
			return;
		}

		final ViewerImgLoader uncorrectedLoader = (ViewerImgLoader) uncorrectedSeqDesc.getImgLoader();
		System.out.println("Uncorrected loader type: " + uncorrectedLoader.getClass().getSimpleName());

		// ========== STEP 3: Create SplitViewerImgLoader wrapping the corrected loader ==========
		System.out.println("\n=== STEP 3: Creating SplitViewerImgLoader ===");

		// Get original image dimensions for the setup we're demonstrating
		final ViewSetup vs = correctedSeqDesc.getViewSetups().get(setupToShow);
		final long[] dims = new long[3];
		vs.getSize().dimensions(dims);
		System.out.println("Original image size: " + dims[0] + " x " + dims[1] + " x " + dims[2]);

		// Create a simple 2x1 split in X dimension
		final long splitX = dims[0] / 2;

		// Define the mappings for split regions
		final HashMap<Integer, Integer> new2oldSetupId = new HashMap<>();
		final HashMap<Integer, Interval> newSetupId2Interval = new HashMap<>();

		// Split region 0: left half
		new2oldSetupId.put(100, setupToShow);
		newSetupId2Interval.put(100, new FinalInterval(
			new long[] {0, 0, 0},
			new long[] {splitX - 1, dims[1] - 1, dims[2] - 1}
		));

		// Split region 1: right half
		new2oldSetupId.put(101, setupToShow);
		newSetupId2Interval.put(101, new FinalInterval(
			new long[] {splitX, 0, 0},
			new long[] {dims[0] - 1, dims[1] - 1, dims[2] - 1}
		));

		System.out.println("Created 2 split regions:");
		System.out.println("  Setup 100: X=[0, " + (splitX-1) + "] (left half)");
		System.out.println("  Setup 101: X=[" + splitX + ", " + (dims[0]-1) + "] (right half)");

		// Create the split loader wrapping the CORRECTED ViewerImgLoader
		final SplitViewerImgLoader splitLoader = new SplitViewerImgLoader(
			correctedLoader,  // <-- ViewerImgLoader compatible!
			new2oldSetupId,
			newSetupId2Interval,
			correctedSeqDesc
		);

		System.out.println("Split loader implements ViewerImgLoader: " +
			(splitLoader instanceof ViewerImgLoader ? "YES" : "NO"));

		// ========== STEP 4: Test ViewerImgLoader-specific features ==========
		System.out.println("\n=== STEP 4: Testing ViewerImgLoader features ===");

		// Test cache control delegation
		System.out.println("Cache control available: " + (splitLoader.getCacheControl() != null));

		// Test mipmap levels
		final ViewerSetupImgLoader<?, ?> setupImgLoader = splitLoader.getSetupImgLoader(100);
		System.out.println("Number of mipmap levels: " + setupImgLoader.numMipmapLevels());

		final double[][] resolutions = setupImgLoader.getMipmapResolutions();
		System.out.println("Mipmap resolutions:");
		for (int level = 0; level < resolutions.length; level++) {
			System.out.println("  Level " + level + ": " +
				resolutions[level][0] + " x " + resolutions[level][1] + " x " + resolutions[level][2]);
		}

		// ========== STEP 5: Display comparison images ==========
		System.out.println("\n=== STEP 5: Displaying images ===");
		new ImageJ();

		// Get tile ID from ViewSetup metadata
		final int tileId = correctedData.getSequenceDescription()
				.getViewSetups().get(setupToShow).getTile().getId();

		// 5a. Show UNCORRECTED original at level 0
		System.out.println("Loading uncorrected image (level 0)...");
		@SuppressWarnings("unchecked")
		final MultiResolutionSetupImgLoader<FloatType> uncorrectedSetupLoader =
			(MultiResolutionSetupImgLoader<FloatType>) uncorrectedLoader.getSetupImgLoader(setupToShow);
		final RandomAccessibleInterval<FloatType> uncorrected =
			uncorrectedSetupLoader.getFloatImage(timepoint, 0, false);
		ImageJFunctions.show(uncorrected, "1. Uncorrected - Setup " + setupToShow + " (tile " + tileId + ")");

		// 5b. Show CORRECTED at level 0
		System.out.println("Loading corrected image (level 0)...");
		final RandomAccessibleInterval<FloatType> corrected =
			correctedLoader.getSetupImgLoader(setupToShow).getFloatImage(timepoint, 0, false);
		ImageJFunctions.show(corrected, "2. Corrected - Setup " + setupToShow + " (tile " + tileId + ")");

		// 5c. Show CORRECTED + SPLIT (left half) at level 0
		System.out.println("Loading corrected + split (left half, level 0)...");
		final RandomAccessibleInterval<FloatType> splitLeft =
			splitLoader.getSetupImgLoader(100).getFloatImage(timepoint, 0, false);
		ImageJFunctions.show(splitLeft, "3. Corrected+Split LEFT - Setup 100");

		// 5d. Show at different mipmap level if available
		if (setupImgLoader.numMipmapLevels() > 1) {
			System.out.println("Loading corrected + split (left half, level 1)...");
			final RandomAccessibleInterval<FloatType> splitLeftLevel1 =
				splitLoader.getSetupImgLoader(100).getFloatImage(timepoint, 1, false);
			ImageJFunctions.show(splitLeftLevel1, "4. Corrected+Split LEFT (Level 1) - Setup 100");
		}

		// ========== Summary ==========
		System.out.println("\n=== VIEWERIMGLOADER CHAIN SUMMARY ===");
		System.out.println("Layer 1 (innermost): " + correctedLoader.getWrappedImgLoader().getClass().getSimpleName() + " [ViewerImgLoader]");
		System.out.println("Layer 2 (middle):    " + correctedLoader.getClass().getSimpleName() + " [ViewerImgLoader]");
		System.out.println("Layer 3 (outermost): " + splitLoader.getClass().getSimpleName() + " [ViewerImgLoader]");
		System.out.println();
		System.out.println("Flatfield correction is now configured in the XML!");
		System.out.println("No manual setBrightImage()/setDarkImage() calls needed.");
		System.out.println();
		System.out.println("All layers maintain ViewerImgLoader compatibility:");
		System.out.println("  - Cache control: delegated through chain");
		System.out.println("  - Volatile images: supported at all levels");
		System.out.println("  - Multi-resolution: " + setupImgLoader.numMipmapLevels() + " mipmap levels available");
		System.out.println();
		System.out.println("Compare the images to verify:");
		System.out.println("  - Image 1 vs 2: See flatfield correction effect");
		System.out.println("  - Image 2 vs 3: Verify split region matches corrected full image");
		if (setupImgLoader.numMipmapLevels() > 1)
			System.out.println("  - Image 3 vs 4: Compare different mipmap levels");
		System.out.println();
		System.out.println("Tip: Use Image > Adjust > Brightness/Contrast (Ctrl+Shift+C)");
	}
}
