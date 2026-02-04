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

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;

public interface FlatfieldCorrectionWrappedImgLoader<IL extends ImgLoader> extends ImgLoader
{
	IL getWrappedImgLoder();
	void setActive(boolean active);
	boolean isActive();
	void setCached(boolean cached);
	boolean isCached();

	/**
	 * Set the bright (flatfield) image for a view.
	 * @param vId view id
	 * @param info flatfield image info containing URI, format, and optional dataset path
	 */
	void setBrightImage(ViewId vId, FlatfieldImageInfo info);

	/**
	 * Set the dark (darkfield) image for a view.
	 * @param vId view id
	 * @param info flatfield image info containing URI, format, and optional dataset path
	 */
	void setDarkImage(ViewId vId, FlatfieldImageInfo info);
}
