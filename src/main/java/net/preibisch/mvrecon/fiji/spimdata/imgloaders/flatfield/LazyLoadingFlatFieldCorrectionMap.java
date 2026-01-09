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
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import util.URITools;

public abstract class LazyLoadingFlatFieldCorrectionMap<IL extends ImgLoader> implements FlatfieldCorrectionWrappedImgLoader<IL>
{
	protected final Map<URI, RandomAccessibleInterval<FloatType>> raiMap;
	protected final Map<ViewId, Pair<URI, URI>> uriMap;

	private static final Pair<URI, URI> NULL_PAIR = new ValuePair<>(null, null);

	public LazyLoadingFlatFieldCorrectionMap()
	{
		raiMap = new HashMap<>();
		uriMap = new HashMap<>();
	}

	@Override
	public void setBrightImage(ViewId vId, URI imgUri) {
		final Pair<URI, URI> oldPair = uriMap.getOrDefault(vId, NULL_PAIR);
		uriMap.put(vId, new ValuePair<>(imgUri, oldPair.getB()));
	}

	@Override
	public void setDarkImage(ViewId vId, URI imgUri) {
		final Pair<URI, URI> oldPair = uriMap.getOrDefault(vId, NULL_PAIR);
		uriMap.put(vId, new ValuePair<>(oldPair.getA(), imgUri));
	}

	protected RandomAccessibleInterval<FloatType> getBrightImg(ViewId vId) {
		return getImg(vId, Pair::getA);
	}

	protected RandomAccessibleInterval<FloatType> getDarkImg(ViewId vId) {
		return getImg(vId, Pair::getB);
	}

	/**
	 * Get image for view id; the brightfield is stored in the A element of the pair, the darkfield in B
	 * @param vId view id
	 * @param uriSelector function to select URI from pair
	 * @return image, or null if not set
	 */
	private RandomAccessibleInterval<FloatType> getImg(ViewId vId, Function<Pair<URI, URI>, URI> uriSelector) {
		if (!uriMap.containsKey(vId))
			return null;

		final URI uriToLoad = uriSelector.apply(uriMap.get(vId));
		if (uriToLoad == null)
			return null;

		return loadImageIfNecessary(uriToLoad);
	}

	/**
	 * Load an image from a URI. Supports:
	 * - Local TIFF files (via ImageJ)
	 * - Local/cloud Zarr v3 containers (via N5 API)
	 * - Local/cloud N5 containers (via N5 API)
	 *
	 * @param uri URI to the image
	 * @return the loaded image as FloatType
	 */
	private RandomAccessibleInterval<FloatType> loadImageIfNecessary(URI uri) {
		if (!raiMap.containsKey(uri)) {
			RandomAccessibleInterval<FloatType> img;

			if (isChunkedFormat(uri)) {
				// Use N5/Zarr API for chunked formats
				final StorageFormat format = detectStorageFormat(uri);
				final N5Reader reader = URITools.instantiateN5Reader(format, uri);
				final RandomAccessibleInterval<?> raw = N5Utils.open(reader, "/");
				img = RealTypeConverters.convert(Cast.unchecked(raw), new FloatType());
			} else {
				// Legacy TIFF path via ImageJ
				final File file = new File(uri);
				final ImagePlus imp = IJ.openImage(file.getAbsolutePath());
				if (imp == null)
					throw new RuntimeException("Failed to load image from: " + uri);
				img = ImageJFunctions.convertFloat(imp).copy();
			}

			raiMap.put(uri, img);
		}
		return raiMap.get(uri);
	}

	/**
	 * Determine if the URI points to a chunked format (N5/Zarr) vs TIFF.
	 */
	private static boolean isChunkedFormat(URI uri) {
		final String scheme = uri.getScheme();
		// Cloud URIs are always chunked format
		if ("s3".equals(scheme) || "gs".equals(scheme))
			return true;

		// Check path for .zarr or .n5 extension
		final String path = uri.getPath();
		if (path == null)
			return false;

		final String lowerPath = path.toLowerCase();
		return lowerPath.endsWith(".zarr") || lowerPath.endsWith(".n5")
			|| lowerPath.contains(".zarr/") || lowerPath.contains(".n5/");
	}

	/**
	 * Detect the storage format from the URI.
	 */
	private static StorageFormat detectStorageFormat(URI uri) {
		final String path = uri.getPath();
		if (path != null && path.toLowerCase().contains(".n5"))
			return StorageFormat.N5;
		// Default to Zarr v3 for cloud and .zarr paths
		return StorageFormat.ZARR;
	}

	public static void main(String[] args)
	{
		DefaultFlatfieldCorrectionWrappedImgLoader testImgLoader = new DefaultFlatfieldCorrectionWrappedImgLoader(null);
		testImgLoader.setBrightImage(new ViewId(0, 0), new File("/Users/David/Desktop/ell2.tif"));
		RandomAccessibleInterval<FloatType> brightImg = testImgLoader.getBrightImg(new ViewId(0, 0));

		ImageJFunctions.show(brightImg);
	}

	/**
	 * Get the URI map for bright/dark images per view.
	 * @return map from ViewId to (brightUri, darkUri) pair
	 */
	public Map<ViewId, Pair<URI, URI>> getUriMap()
	{
		return uriMap;
	}
}
