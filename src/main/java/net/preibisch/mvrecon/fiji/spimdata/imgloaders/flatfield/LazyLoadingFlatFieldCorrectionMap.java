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
import java.util.Map;

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;

public abstract class LazyLoadingFlatFieldCorrectionMap<IL extends ImgLoader> implements FlatfieldCorrectionWrappedImgLoader<IL>
{
	protected final FlatfieldImageLoader imageLoader;

	public LazyLoadingFlatFieldCorrectionMap()
	{
		imageLoader = new FlatfieldImageLoader();
	}

	@Override
	public void setBrightImage(ViewId vId, URI imgUri) {
		imageLoader.setBrightImage(vId, imgUri);
	}

	@Override
	public void setDarkImage(ViewId vId, URI imgUri) {
		imageLoader.setDarkImage(vId, imgUri);
	}

	protected RandomAccessibleInterval<FloatType> getBrightImg(ViewId vId) {
		return imageLoader.getBrightImg(vId);
	}

	protected RandomAccessibleInterval<FloatType> getDarkImg(ViewId vId) {
		return imageLoader.getDarkImg(vId);
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
		return imageLoader.getUriMap();
	}
}
