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

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;

public interface FlatfieldCorrectionWrappedImgLoader<IL extends ImgLoader> extends ImgLoader
{
	public IL getWrappedImgLoder();
	public void setActive(boolean active);
	public boolean isActive();
	public void setCached(boolean cached);
	public boolean isCached();

	/**
	 * Set the bright (flatfield) image for a view.
	 * @param vId view id
	 * @param imgUri URI to the bright image (supports file://, s3://, gs://, local paths)
	 */
	public void setBrightImage(ViewId vId, URI imgUri);

	/**
	 * Set the dark (darkfield) image for a view.
	 * @param vId view id
	 * @param imgUri URI to the dark image (supports file://, s3://, gs://, local paths)
	 */
	public void setDarkImage(ViewId vId, URI imgUri);

	/**
	 * Set the bright (flatfield) image for a view from a local file.
	 * @param vId view id
	 * @param imgFile local file path to the bright image
	 */
	default void setBrightImage(ViewId vId, File imgFile) {
		setBrightImage(vId, imgFile == null ? null : imgFile.toURI());
	}

	/**
	 * Set the dark (darkfield) image for a view from a local file.
	 * @param vId view id
	 * @param imgFile local file path to the dark image
	 */
	default void setDarkImage(ViewId vId, File imgFile) {
		setDarkImage(vId, imgFile == null ? null : imgFile.toURI());
	}
}
