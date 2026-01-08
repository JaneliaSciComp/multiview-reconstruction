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

import java.io.File;
import java.util.HashMap;

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitMultiResolutionImgLoader;

/**
 * Test class demonstrating the full decorator chain:
 *
 * BaseLoader (N5/OME-Zarr)
 *     → MultiResolutionFlatfieldCorrectionWrappedImgLoader (applies correction)
 *         → SplitMultiResolutionImgLoader (splits into regions)
 *
 * This shows how to compose multiple wrapper/decorator layers while maintaining
 * the MultiResolutionImgLoader interface throughout the chain.
 */
public class TestDecoratorChain {
	// Mapping from ViewSetup ID to Tile ID (from dataset.xml)
	private static final int[][] SETUP_TO_TILE = {
		{0, 187},
		{1, 188},
		{2, 189},
		{3, 200},
		{4, 201},
		{5, 202},
		{6, 213},
		{7, 214},
		{8, 215}
	};

	public static void main(String[] args) throws SpimDataException {
		// ========== CONFIGURATION ==========
		final String basePath = "/Users/innerbergerm/Projects/janelia/multiview-reconstruction/";
		final String xmlPath = basePath + "data/dataset.xml";
		final String correctionPath = basePath + "dark_and_flatfields/";

		// Which setup to demonstrate (0-8)
		final int setupToShow = 0;
		final int timepoint = 0;

		// ========== STEP 1: Load dataset and get base loader ==========
		System.out.println("=== STEP 1: Loading dataset ===");
		System.out.println("XML path: " + xmlPath);

		final SpimData2 data = new XmlIoSpimData2().load(xmlPath);
		final SequenceDescription seqDesc = data.getSequenceDescription();

		// The base loader - this could be N5ImageLoader, AllenOMEZarrLoader, etc.
		final MultiResolutionImgLoader baseLoader =
			(MultiResolutionImgLoader) seqDesc.getImgLoader();

		System.out.println("Base loader type: " + baseLoader.getClass().getSimpleName());

		// ========== STEP 2: Wrap with flatfield correction ==========
		System.out.println("\n=== STEP 2: Wrapping with flatfield correction ===");

		final MultiResolutionFlatfieldCorrectionWrappedImgLoader correctedLoader =
			new MultiResolutionFlatfieldCorrectionWrappedImgLoader(baseLoader, true);

		// Configure correction images for each view setup
		for (int[] mapping : SETUP_TO_TILE) {
			final int setupId = mapping[0];
			final int tileId = mapping[1];

			// Find darkfield file
			final File darkfield = new File(correctionPath + "setup" + tileId + "-AVG_darkfield-fromdata.tif");

			// Find flatfield file (try both naming conventions)
			File flatfield = new File(correctionPath + "setup" + tileId + "-flatfield.tif");
			if (!flatfield.exists())
				flatfield = new File(correctionPath + "setup" + tileId + "-flatfield (fixed by mirroring).tif");

			final ViewId viewId = new ViewId(timepoint, setupId);

			if (darkfield.exists()) {
				correctedLoader.setDarkImage(viewId, darkfield);
				System.out.println("  Setup " + setupId + ": darkfield = " + darkfield.getName());
			}

			if (flatfield.exists()) {
				correctedLoader.setBrightImage(viewId, flatfield);
				System.out.println("  Setup " + setupId + ": flatfield = " + flatfield.getName());
			}
		}

		// ========== STEP 3: Wrap with splitting ==========
		System.out.println("\n=== STEP 3: Wrapping with splitting ===");

		// Get original image dimensions for the setup we're demonstrating
		final ViewSetup vs = seqDesc.getViewSetups().get(setupToShow);
		final long[] dims = new long[3];
		vs.getSize().dimensions(dims);
		System.out.println("Original image size: " + dims[0] + " x " + dims[1] + " x " + dims[2]);

		// Create a simple 2x1 split in X dimension
		// Split the 512-wide image into two 256-wide regions
		final long splitX = dims[0] / 2;

		// Define the mappings for split regions
		// New setup IDs 100, 101 will map to original setup 0, with different X intervals
		final HashMap<Integer, Integer> new2oldSetupId = new HashMap<>();
		final HashMap<Integer, Interval> newSetupId2Interval = new HashMap<>();

		// Split region 0: left half [0, splitX) x [0, dimY) x [0, dimZ)
		new2oldSetupId.put(100, setupToShow);
		newSetupId2Interval.put(100, new FinalInterval(
			new long[] {0, 0, 0},
			new long[] {splitX - 1, dims[1] - 1, dims[2] - 1}
		));

		// Split region 1: right half [splitX, dimX) x [0, dimY) x [0, dimZ)
		new2oldSetupId.put(101, setupToShow);
		newSetupId2Interval.put(101, new FinalInterval(
			new long[] {splitX, 0, 0},
			new long[] {dims[0] - 1, dims[1] - 1, dims[2] - 1}
		));

		System.out.println("Created 2 split regions:");
		System.out.println("  Setup 100: X=[0, " + (splitX-1) + "] (left half)");
		System.out.println("  Setup 101: X=[" + splitX + ", " + (dims[0]-1) + "] (right half)");

		// Create the split loader wrapping the CORRECTED loader
		// This is the key: correction is applied BEFORE splitting
		final SplitMultiResolutionImgLoader splitLoader = new SplitMultiResolutionImgLoader(
			correctedLoader,  // <-- corrected loader, not base loader!
			new2oldSetupId,
			newSetupId2Interval,
			seqDesc
		);

		// ========== STEP 4: Display comparison images ==========
		System.out.println("\n=== STEP 4: Displaying images ===");
		new ImageJ();

		final int tileId = SETUP_TO_TILE[setupToShow][1];

		// 4a. Show UNCORRECTED original (full image, level 0)
		System.out.println("Loading uncorrected image...");
		final RandomAccessibleInterval<FloatType> uncorrected =
			baseLoader.getSetupImgLoader(setupToShow).getFloatImage(timepoint, 0, false);
		ImageJFunctions.show(uncorrected, "1. Uncorrected - Setup " + setupToShow + " (tile " + tileId + ")");

		// 4b. Show CORRECTED (full image, level 0)
		System.out.println("Loading corrected image...");
		final RandomAccessibleInterval<FloatType> corrected =
			correctedLoader.getSetupImgLoader(setupToShow).getFloatImage(timepoint, 0, false);
		ImageJFunctions.show(corrected, "2. Corrected - Setup " + setupToShow + " (tile " + tileId + ")");

		// 4c. Show CORRECTED + SPLIT (left half, level 0)
		System.out.println("Loading corrected + split (left half)...");
		final RandomAccessibleInterval<FloatType> splitLeft =
			splitLoader.getSetupImgLoader(100).getFloatImage(timepoint, 0, false);
		ImageJFunctions.show(splitLeft, "3. Corrected+Split LEFT - Setup 100");

		// 4d. Show CORRECTED + SPLIT (right half, level 0)
		System.out.println("Loading corrected + split (right half)...");
		final RandomAccessibleInterval<FloatType> splitRight =
			splitLoader.getSetupImgLoader(101).getFloatImage(timepoint, 0, false);
		ImageJFunctions.show(splitRight, "4. Corrected+Split RIGHT - Setup 101");

		// ========== Summary ==========
		System.out.println("\n=== DECORATOR CHAIN SUMMARY ===");
		System.out.println("Layer 1 (innermost): " + baseLoader.getClass().getSimpleName());
		System.out.println("Layer 2 (middle):    " + correctedLoader.getClass().getSimpleName());
		System.out.println("Layer 3 (outermost): " + splitLoader.getClass().getSimpleName());
		System.out.println();
		System.out.println("Data flow:");
		System.out.println("  Request for split region 100 or 101");
		System.out.println("    → SplitMultiResolutionImgLoader maps to setup " + setupToShow + " with interval");
		System.out.println("    → MultiResolutionFlatfieldCorrectionWrappedImgLoader applies correction");
		System.out.println("    → Base loader fetches raw pixels from N5");
		System.out.println("    → Corrected pixels flow back up through the chain");
		System.out.println("    → Split interval is extracted and returned");
		System.out.println();
		System.out.println("Compare the images to verify:");
		System.out.println("  - Image 1 vs 2: See flatfield correction effect");
		System.out.println("  - Image 2 vs 3+4: Verify split regions match the corrected full image");
		System.out.println();
		System.out.println("Tip: Use Image > Adjust > Brightness/Contrast (Ctrl+Shift+C)");
	}
}
