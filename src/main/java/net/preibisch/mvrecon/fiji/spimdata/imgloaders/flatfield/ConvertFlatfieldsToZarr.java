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
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import ij.IJ;
import ij.ImagePlus;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import util.URITools;

/**
 * Utility to convert TIFF-based flatfield images to Zarr v3 format.
 *
 * This creates single-shard Zarr containers for each flatfield image,
 * suitable for cloud storage or local chunked access.
 *
 * Also generates a test XML file with the Zarr paths configured.
 */
public class ConvertFlatfieldsToZarr {

	/**
	 * Convert a single TIFF image to Zarr v3 format.
	 *
	 * @param inputTiff  path to input TIFF file
	 * @param outputZarr path for output .zarr container
	 * @throws IOException if writing fails
	 */
	public static void convertTiffToZarr(final File inputTiff, final File outputZarr) throws IOException {
		System.out.println("Converting: " + inputTiff.getName() + " -> " + outputZarr.getName());

		// Load TIFF via ImageJ
		final ImagePlus imp = IJ.openImage(inputTiff.getAbsolutePath());
		if (imp == null)
			throw new IOException("Failed to load TIFF: " + inputTiff);

		final Img<FloatType> img = ImageJFunctions.convertFloat(imp);

		// Create Zarr v3 writer
		final N5Writer writer = URITools.instantiateN5Writer(StorageFormat.ZARR, outputZarr.toURI());

		// Use a single block/shard for the entire image (flatfields are typically small)
		final int[] blockSize = new int[img.numDimensions()];
		for (int d = 0; d < img.numDimensions(); d++)
			blockSize[d] = (int) img.dimension(d);

		// Save (N5Utils.save creates the dataset internally)
		final Compression compression = new GzipCompression();
		N5Utils.save(img, writer, "/", blockSize, compression);

		writer.close();
		System.out.println("  Created: " + outputZarr.getAbsolutePath());
	}

	/**
	 * Convert all flatfield TIFFs in a directory to Zarr format.
	 *
	 * @param inputDir  directory containing TIFF files
	 * @param outputDir directory for output .zarr containers
	 * @return map of original filename (without extension) to output Zarr file
	 * @throws IOException if conversion fails
	 */
	public static Map<String, File> convertDirectory(final File inputDir, final File outputDir) throws IOException {
		if (!outputDir.exists())
			outputDir.mkdirs();

		final Map<String, File> converted = new HashMap<>();

		final File[] tiffFiles = inputDir.listFiles((dir, name) ->
			name.toLowerCase().endsWith(".tif") || name.toLowerCase().endsWith(".tiff"));

		if (tiffFiles == null || tiffFiles.length == 0) {
			System.out.println("No TIFF files found in: " + inputDir);
			return converted;
		}

		for (final File tiff : tiffFiles) {
			final String baseName = tiff.getName().replaceAll("\\.(tif|tiff)$", "");
			final File zarrOut = new File(outputDir, baseName + ".zarr");

			convertTiffToZarr(tiff, zarrOut);
			converted.put(baseName, zarrOut);
		}

		return converted;
	}

	/**
	 * Generate a test XML file with Zarr-based flatfield correction.
	 *
	 * @param baseXmlPath     path to the original dataset XML
	 * @param outputXmlPath   path for the new XML with flatfield correction
	 * @param flatfieldDir    directory containing .zarr flatfield files
	 * @param brightPattern   pattern for bright image names (e.g., "flatfield_tile%d")
	 * @param darkPattern     pattern for dark image names (e.g., "darkfield_tile%d")
	 * @throws SpimDataException if XML loading fails
	 * @throws IOException       if XML writing fails
	 */
	public static void generateTestXml(
			final String baseXmlPath,
			final String outputXmlPath,
			final File flatfieldDir,
			final String brightPattern,
			final String darkPattern) throws SpimDataException, IOException {

		System.out.println("\n=== Generating Test XML ===");
		System.out.println("Base XML: " + baseXmlPath);
		System.out.println("Output XML: " + outputXmlPath);

		// Load original dataset to get view setup info
		final SpimData2 data = new XmlIoSpimData2().load(baseXmlPath);

		// Build XML content manually to wrap the original loader with flatfield correction
		final StringBuilder xml = new StringBuilder();
		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		xml.append("<SpimData version=\"0.2\">\n");
		xml.append("  <BasePath type=\"relative\">.</BasePath>\n");
		xml.append("  <SequenceDescription>\n");

		// ImageLoader section with flatfield wrapper
		xml.append("    <ImageLoader format=\"spimreconstruction.wrapped.flatfield.default\" ");
		xml.append("Active=\"true\" Cached=\"true\">\n");

		// Reference the original loader - read from original XML
		xml.append("      <WrappedImgLoader>\n");
		xml.append("        <!-- Original loader from base dataset -->\n");
		xml.append("        <ImageLoader format=\"bdv.n5\" version=\"1.0\">\n");
		xml.append("          <n5 type=\"relative\">dataset.n5</n5>\n");
		xml.append("        </ImageLoader>\n");
		xml.append("      </WrappedImgLoader>\n");

		// Flatfield configuration for each view setup
		xml.append("      <FlatFields>\n");

		for (final ViewSetup vs : data.getSequenceDescription().getViewSetupsOrdered()) {
			final int setupId = vs.getId();
			final int tileId = vs.getTile().getId();

			// Construct Zarr file names based on patterns
			final String brightName = String.format(brightPattern, tileId) + ".zarr";
			final String darkName = String.format(darkPattern, tileId) + ".zarr";

			final File brightFile = new File(flatfieldDir, brightName);
			final File darkFile = new File(flatfieldDir, darkName);

			// Only include if files exist
			final boolean hasBright = brightFile.exists();
			final boolean hasDark = darkFile.exists();

			if (hasBright || hasDark) {
				xml.append("        <FlatField timepoint=\"0\" setup=\"").append(setupId).append("\">\n");
				if (hasBright)
					xml.append("          <BrightImg>").append(brightFile.getName()).append("</BrightImg>\n");
				if (hasDark)
					xml.append("          <DarkImg>").append(darkFile.getName()).append("</DarkImg>\n");
				xml.append("        </FlatField>\n");

				System.out.println("  Setup " + setupId + " (tile " + tileId + "): " +
						(hasBright ? "bright=" + brightName : "") +
						(hasDark ? " dark=" + darkName : ""));
			}
		}

		xml.append("      </FlatFields>\n");
		xml.append("    </ImageLoader>\n");

		// Copy ViewSetups from original
		xml.append("    <!-- ViewSetups copied from original dataset -->\n");
		xml.append("    <ViewSetups>\n");
		for (final ViewSetup vs : data.getSequenceDescription().getViewSetupsOrdered()) {
			xml.append("      <ViewSetup>\n");
			xml.append("        <id>").append(vs.getId()).append("</id>\n");
			xml.append("        <name>").append(vs.getName()).append("</name>\n");
			xml.append("        <size>").append(vs.getSize().dimension(0)).append(" ")
					.append(vs.getSize().dimension(1)).append(" ")
					.append(vs.getSize().dimension(2)).append("</size>\n");
			xml.append("        <voxelSize>\n");
			xml.append("          <unit>").append(vs.getVoxelSize().unit()).append("</unit>\n");
			xml.append("          <size>").append(vs.getVoxelSize().dimension(0)).append(" ")
					.append(vs.getVoxelSize().dimension(1)).append(" ")
					.append(vs.getVoxelSize().dimension(2)).append("</size>\n");
			xml.append("        </voxelSize>\n");
			xml.append("        <attributes>\n");
			xml.append("          <channel>").append(vs.getChannel().getId()).append("</channel>\n");
			xml.append("          <tile>").append(vs.getTile().getId()).append("</tile>\n");
			xml.append("          <illumination>").append(vs.getIllumination().getId()).append("</illumination>\n");
			xml.append("          <angle>").append(vs.getAngle().getId()).append("</angle>\n");
			xml.append("        </attributes>\n");
			xml.append("      </ViewSetup>\n");
		}
		xml.append("    </ViewSetups>\n");

		// Timepoints
		xml.append("    <Timepoints type=\"range\">\n");
		xml.append("      <first>0</first>\n");
		xml.append("      <last>0</last>\n");
		xml.append("    </Timepoints>\n");

		xml.append("  </SequenceDescription>\n");

		// ViewRegistrations (identity transforms)
		xml.append("  <ViewRegistrations>\n");
		for (final ViewSetup vs : data.getSequenceDescription().getViewSetupsOrdered()) {
			xml.append("    <ViewRegistration timepoint=\"0\" setup=\"").append(vs.getId()).append("\">\n");
			xml.append("      <ViewTransform type=\"affine\">\n");
			xml.append("        <affine>1.0 0.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 1.0 0.0</affine>\n");
			xml.append("      </ViewTransform>\n");
			xml.append("    </ViewRegistration>\n");
		}
		xml.append("  </ViewRegistrations>\n");

		xml.append("</SpimData>\n");

		// Write XML file
		try (FileWriter writer = new FileWriter(outputXmlPath)) {
			writer.write(xml.toString());
		}

		System.out.println("\nCreated: " + outputXmlPath);
	}

	/**
	 * Example main method demonstrating conversion and XML generation.
	 */
	public static void main(String[] args) throws Exception {
		// Configuration - adjust these paths for your setup
		final String basePath = "/Users/innerbergerm/Projects/janelia/multiview-reconstruction/";
		final String dataPath = basePath + "data/";

		// Input: directory with TIFF flatfields
		final File tiffDir = new File(basePath, "dark_and_flatfields");

		// Output: directory for Zarr flatfields
		final File zarrDir = new File(basePath, "dark_and_flatfields_zarr");

		// Step 1: Convert all TIFFs to Zarr
		System.out.println("=== Step 1: Converting TIFFs to Zarr v3 ===\n");

		if (tiffDir.exists()) {
			convertDirectory(tiffDir, zarrDir);
		} else {
			throw new IOException("Input TIFF directory does not exist: " + tiffDir.getAbsolutePath());
		}

		// Step 2: Generate test XML
		System.out.println("\n=== Step 2: Generating Test XML ===\n");

		final String baseXml = dataPath + "dataset.xml";
		final String outputXml = dataPath + "dataset_corrected_zarr.xml";

		if (new File(baseXml).exists()) {
			generateTestXml(
					baseXml,
					outputXml,
					zarrDir,
					"flatfield_tile%d",  // pattern for bright images
					"darkfield_tile%d"   // pattern for dark images
			);
		} else {
			System.out.println("Base XML not found: " + baseXml);
			System.out.println("Skipping XML generation.");
		}

		System.out.println("\n=== Done! ===");
		System.out.println("To test, load: " + outputXml);
	}
}
