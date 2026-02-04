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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import ij.IJ;
import ij.ImagePlus;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import org.janelia.scicomp.n5.zstandard.ZstandardCompression;
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

		// Save at root (empty string for Zarr v3)
		// Save a block manually to work around a N5Utils.save issue for now
		final Compression compression = new ZstandardCompression();
		writer.createDataset("/", img.dimensionsAsLongArray(), blockSize, DataType.FLOAT32, compression);
		N5Utils.saveBlock(img, writer, "", new long[]{0, 0});

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
	 * Generate a test XML file with Zarr-based flatfield correction by copying
	 * an existing working XML and updating the flatfield paths to point to Zarr files.
	 *
	 * @param sourceXmlPath   path to a working flatfield-corrected XML (with TIFF paths)
	 * @param outputXmlPath   path for the new XML with Zarr flatfield paths
	 * @param zarrDir         directory containing .zarr flatfield files
	 * @param convertedFiles  map of base names to Zarr files from conversion
	 * @throws IOException if reading/writing fails
	 */
	public static void generateTestXml(
			final String sourceXmlPath,
			final String outputXmlPath,
			final File zarrDir,
			final Map<String, File> convertedFiles) throws IOException {

		System.out.println("\n=== Generating Test XML ===");
		System.out.println("Source XML: " + sourceXmlPath);
		System.out.println("Output XML: " + outputXmlPath);

		// Read the source XML
		final StringBuilder content = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new FileReader(sourceXmlPath))) {
			String line;
			while ((line = reader.readLine()) != null) {
				content.append(line).append("\n");
			}
		}

		String xml = content.toString();

		// Pattern to match BrightImg and DarkImg paths
		final Pattern brightPattern = Pattern.compile("(<BrightImg>)([^<]+)(</BrightImg>)");
		final Pattern darkPattern = Pattern.compile("(<DarkImg>)([^<]+)(</DarkImg>)");

		// Replace TIFF paths with Zarr paths
		xml = replaceFlatfieldPaths(xml, brightPattern, zarrDir, convertedFiles);
		xml = replaceFlatfieldPaths(xml, darkPattern, zarrDir, convertedFiles);

		// Write output XML
		try (FileWriter writer = new FileWriter(outputXmlPath)) {
			writer.write(xml);
		}

		System.out.println("Created: " + outputXmlPath);
	}

	/**
	 * Replace flatfield TIFF paths with Zarr paths in XML content.
	 */
	private static String replaceFlatfieldPaths(
			String xml,
			final Pattern pattern,
			final File zarrDir,
			final Map<String, File> convertedFiles) {

		final Matcher matcher = pattern.matcher(xml);
		final StringBuffer result = new StringBuffer();

		while (matcher.find()) {
			final String openTag = matcher.group(1);
			final String oldPath = matcher.group(2);
			final String closeTag = matcher.group(3);

			// Extract base name from old path (remove directory and extension)
			String baseName = new File(oldPath).getName();
			baseName = baseName.replaceAll("\\.(tif|tiff)$", "");

			// Find corresponding Zarr file
			String newPath = oldPath;  // default: keep original if not found
			if (convertedFiles.containsKey(baseName)) {
				// Use relative path from zarrDir
				newPath = zarrDir.getName() + "/" + convertedFiles.get(baseName).getName();
				System.out.println("  " + oldPath + " -> " + newPath);
			} else {
				System.out.println("  WARNING: No Zarr found for: " + baseName);
			}

			matcher.appendReplacement(result, Matcher.quoteReplacement(openTag + newPath + closeTag));
		}
		matcher.appendTail(result);

		return result.toString();
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

		// Output: directory for Zarr flatfields (in data folder, next to other data)
		final File zarrDir = new File(basePath, "dark_and_flatfields_zarr");

		// Step 1: Convert all TIFFs to Zarr
		System.out.println("=== Step 1: Converting TIFFs to Zarr v3 ===\n");

		Map<String, File> convertedFiles;
		if (tiffDir.exists()) {
			convertedFiles = convertDirectory(tiffDir, zarrDir);
		} else {
			throw new IOException("Input TIFF directory does not exist: " + tiffDir.getAbsolutePath());
		}

		// Step 2: Generate test XML by copying from working TIFF-based XML
		System.out.println("\n=== Step 2: Generating Test XML ===\n");

		// Use the working flatfield-corrected XML as source
		final String sourceXml = dataPath + "dataset_corrected_viewer.xml";
		final String outputXml = dataPath + "dataset_corrected_zarr.xml";

		if (new File(sourceXml).exists()) {
			generateTestXml(
					sourceXml,
					outputXml,
					zarrDir,
					convertedFiles
			);
		} else {
			System.out.println("Source XML not found: " + sourceXml);
			System.out.println("Skipping XML generation.");
		}

		System.out.println("\n=== Done! ===");
		System.out.println("To test, load: " + outputXml);
	}
}
