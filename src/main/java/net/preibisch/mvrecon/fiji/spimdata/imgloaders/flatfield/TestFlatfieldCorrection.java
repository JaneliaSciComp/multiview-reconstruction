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

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ImgLoader;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;

/**
 * Test class for XML-based on-the-fly flatfield/darkfield correction.
 *
 * Loads dataset_corrected.xml which has flatfield correction configured
 * directly in the ImageLoader section. No manual configuration needed!
 *
 * The XML wraps the N5 loader with MultiResolutionFlatfieldCorrectionWrappedImgLoader
 * and specifies bright/dark images for each view setup.
 */
public class TestFlatfieldCorrection {

	public static void main(String[] args) throws SpimDataException {
		// Paths
		final String basePath = "/Users/innerbergerm/Projects/janelia/multiview-reconstruction/";
		final String correctedXmlPath = basePath + "data/dataset_corrected.xml";
		final String uncorrectedXmlPath = basePath + "data/dataset.xml";

		// Which setup to display (0-8)
		final int setupToShow = 0;
		final int timepoint = 0;

		// ========== Load CORRECTED dataset (from XML with flatfield config) ==========
		System.out.println("=== Loading CORRECTED dataset ===");
		System.out.println("XML path: " + correctedXmlPath);

		final SpimData2 correctedData = new XmlIoSpimData2().load(correctedXmlPath);
		final ImgLoader correctedImgLoader = correctedData.getSequenceDescription().getImgLoader();

		System.out.println("ImgLoader type: " + correctedImgLoader.getClass().getSimpleName());

		// Verify it's a flatfield-corrected loader
		if (correctedImgLoader instanceof FlatfieldCorrectionWrappedImgLoader) {
			final FlatfieldCorrectionWrappedImgLoader<?> ffcLoader =
				(FlatfieldCorrectionWrappedImgLoader<?>) correctedImgLoader;
			System.out.println("  Correction active: " + ffcLoader.isActive());
			System.out.println("  Caching enabled: " + ffcLoader.isCached());
			System.out.println("  Wrapped loader: " + ffcLoader.getWrappedImgLoder().getClass().getSimpleName());
		}

		// ========== Load UNCORRECTED dataset (original XML) ==========
		System.out.println("\n=== Loading UNCORRECTED dataset ===");
		System.out.println("XML path: " + uncorrectedXmlPath);

		final SpimData2 uncorrectedData = new XmlIoSpimData2().load(uncorrectedXmlPath);
		final ImgLoader uncorrectedImgLoader = uncorrectedData.getSequenceDescription().getImgLoader();

		System.out.println("ImgLoader type: " + uncorrectedImgLoader.getClass().getSimpleName());

		// ========== Display images for comparison ==========
		new ImageJ();

		// Get tile ID from ViewSetup metadata (no hardcoded mapping needed!)
        final int tileId = correctedData.getSequenceDescription()
			.getViewSetups().get(setupToShow).getTile().getId();

		System.out.println("\n=== Displaying setup " + setupToShow + " (tile " + tileId + ") ===");
		System.out.println("  Dimensions: " + correctedData.getSequenceDescription()
			.getViewSetups().get(setupToShow).getSize());

		// Load and display UNCORRECTED image
		System.out.println("Loading uncorrected image...");
		final RandomAccessibleInterval<FloatType> uncorrected =
			uncorrectedImgLoader.getSetupImgLoader(setupToShow).getFloatImage(timepoint, false);
		ImageJFunctions.show(uncorrected, "1. Uncorrected - Setup " + setupToShow + " (tile " + tileId + ")");

		// Load and display CORRECTED image
		System.out.println("Loading corrected image...");
		final RandomAccessibleInterval<FloatType> corrected =
			correctedImgLoader.getSetupImgLoader(setupToShow).getFloatImage(timepoint, false);
		ImageJFunctions.show(corrected, "2. Corrected - Setup " + setupToShow + " (tile " + tileId + ")");

		// ========== Summary ==========
		System.out.println("\n=== SUMMARY ===");
		System.out.println("Flatfield correction is now configured in the XML!");
		System.out.println("No manual setBrightImage()/setDarkImage() calls needed.");
		System.out.println();
		System.out.println("Compare the two images to verify correction:");
		System.out.println("  - Image 1: Raw data from N5");
		System.out.println("  - Image 2: Corrected with flatfield/darkfield");
		System.out.println();
		System.out.println("Tip: Use Image > Adjust > Brightness/Contrast (Ctrl+Shift+C)");
	}
}
