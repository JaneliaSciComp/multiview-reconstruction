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
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import ij.IJ;
import ij.ImagePlus;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import util.URITools;

/**
 * Helper class for loading flatfield correction images from various sources.
 *
 * This class handles:
 * - Storage of bright/dark image info per view (URI, format, dataset path)
 * - Lazy loading and caching of images
 * - Support for TIF, N5, Zarr (v2/v3), and HDF5 formats
 * - Support for cloud storage (S3, GCS) for N5/Zarr formats
 */
public class FlatfieldImageLoader {

	protected final Map<FlatfieldImageInfo, RandomAccessibleInterval<FloatType>> raiMap;
	protected final Map<ViewId, Pair<FlatfieldImageInfo, FlatfieldImageInfo>> infoMap;

	private static final Pair<FlatfieldImageInfo, FlatfieldImageInfo> NULL_PAIR = new ValuePair<>(null, null);

	public FlatfieldImageLoader() {
		raiMap = new HashMap<>();
		infoMap = new HashMap<>();
	}

	public void setBrightImage(ViewId vId, FlatfieldImageInfo info) {
		final Pair<FlatfieldImageInfo, FlatfieldImageInfo> oldPair = infoMap.getOrDefault(vId, NULL_PAIR);
		infoMap.put(vId, new ValuePair<>(info, oldPair.getB()));
	}

	public void setDarkImage(ViewId vId, FlatfieldImageInfo info) {
		final Pair<FlatfieldImageInfo, FlatfieldImageInfo> oldPair = infoMap.getOrDefault(vId, NULL_PAIR);
		infoMap.put(vId, new ValuePair<>(oldPair.getA(), info));
	}

	public RandomAccessibleInterval<FloatType> getBrightImg(ViewId vId) {
		return getImg(vId, Pair::getA);
	}

	public RandomAccessibleInterval<FloatType> getDarkImg(ViewId vId) {
		return getImg(vId, Pair::getB);
	}

	/**
	 * Get image for view id; the brightfield is stored in the A element of the pair, the darkfield in B
	 * @param vId view id
	 * @param infoSelector function to select info from pair
	 * @return image, or null if not set
	 */
	private RandomAccessibleInterval<FloatType> getImg(ViewId vId, Function<Pair<FlatfieldImageInfo, FlatfieldImageInfo>, FlatfieldImageInfo> infoSelector) {
		if (!infoMap.containsKey(vId))
			return null;

		final FlatfieldImageInfo info = infoSelector.apply(infoMap.get(vId));
		if (info == null)
			return null;

		return loadImageIfNecessary(info);
	}

	/**
	 * Load an image using the specified format. Supports:
	 * - TIF files (local only, via ImageJ)
	 * - N5 containers (local + cloud)
	 * - Zarr v2/v3 containers (local + cloud)
	 * - HDF5 files (local only)
	 *
	 * @param info the flatfield image info containing URI, format, and dataset path
	 * @return the loaded image as FloatType
	 */
	public RandomAccessibleInterval<FloatType> loadImageIfNecessary(FlatfieldImageInfo info) {
		if (!raiMap.containsKey(info)) {
			RandomAccessibleInterval<FloatType> img;

			if (info.isTif()) {
				// TIF format via ImageJ
				final File file = new File(info.getUri());
				final ImagePlus imp = IJ.openImage(file.getAbsolutePath());
				if (imp == null)
					throw new RuntimeException("Failed to load TIF image from: " + info.getUri());
				img = ImageJFunctions.convertFloat(imp).copy();
			} else {
				// N5, Zarr, or HDF5 via N5 API
				final StorageFormat format = info.getFormat();
				final URI uri = info.getUri();

				// Validate HDF5 is not used with cloud storage
				if (format == StorageFormat.HDF5) {
					final String scheme = uri.getScheme();
					if ("s3".equals(scheme) || "gs".equals(scheme)) {
						throw new RuntimeException("HDF5 format does not support cloud storage (s3/gs). URI: " + uri);
					}
				}

				final N5Reader reader = URITools.instantiateN5Reader(format, uri);
				final String dataset = info.getEffectiveDataset();
				final RandomAccessibleInterval<?> raw = N5Utils.open(reader, dataset);
				img = RealTypeConverters.convert(Cast.unchecked(raw), new FloatType());
			}

			raiMap.put(info, img);
		}
		return raiMap.get(info);
	}

	/**
	 * Get the info map for bright/dark images per view.
	 * @return map from ViewId to (brightInfo, darkInfo) pair
	 */
	public Map<ViewId, Pair<FlatfieldImageInfo, FlatfieldImageInfo>> getInfoMap() {
		return infoMap;
	}

	// ==================== Format Parsing Utilities ====================

	/**
	 * Parse a format string to StorageFormat.
	 * @param formatStr the format string (tif, n5, zarr, zarr2, hdf5)
	 * @return StorageFormat, or null for TIF format
	 * @throws IllegalArgumentException if format string is unknown
	 */
	public static StorageFormat parseFormat(String formatStr) {
		if (formatStr == null || formatStr.isEmpty())
			throw new IllegalArgumentException("Format attribute is required");

		switch (formatStr.toLowerCase()) {
			case "tif":
			case "tiff":
				return null; // null indicates TIF format
			case "n5":
				return StorageFormat.N5;
			case "zarr":
			case "zarr3":
				return StorageFormat.ZARR;
			case "zarr2":
				return StorageFormat.ZARR2;
			case "hdf5":
			case "h5":
				return StorageFormat.HDF5;
			default:
				throw new IllegalArgumentException("Unknown flatfield format: " + formatStr
						+ ". Supported formats: tif, n5, zarr, zarr2, hdf5");
		}
	}

	/**
	 * Convert StorageFormat to format string for XML serialization.
	 * @param format the StorageFormat (null for TIF)
	 * @return format string
	 */
	public static String formatToString(StorageFormat format) {
		if (format == null)
			return "tif";

		switch (format) {
			case N5:
				return "n5";
			case ZARR:
				return "zarr";
			case ZARR2:
				return "zarr2";
			case HDF5:
				return "hdf5";
			default:
				throw new IllegalArgumentException("Unsupported format for flatfield: " + format);
		}
	}
}
