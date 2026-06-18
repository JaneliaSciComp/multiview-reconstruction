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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.io.File;

import mpicbg.spim.data.legacy.LegacyImgLoaderWrapper;
import net.imglib2.img.ImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.integer.UnsignedShortType;

public abstract class StackImgLoader< I extends LegacyStackImgLoader > extends LegacyImgLoaderWrapper< UnsignedShortType, I >
{
	public StackImgLoader( I legacyImgLoader )
	{
		super( legacyImgLoader );
	}

	public File getPath() { return legacyImgLoader.path; }
	public String getFileNamePattern() { return legacyImgLoader.fileNamePattern; }
	public int getLayoutTimePoints() { return legacyImgLoader.layoutTP; }
	public int getLayoutChannels() { return legacyImgLoader.layoutChannels; }
	public int getLayoutIlluminations() { return legacyImgLoader.layoutIllum; }
	public int getLayoutAngles() { return legacyImgLoader.layoutAngles; }
	public int getLayoutTiles() { return legacyImgLoader.layoutTiles; }
	public ImgFactory< ? extends NativeType< ? > > getImgFactory() { return legacyImgLoader.getImgFactory(); }

	@Override
	public String toString() {
		return legacyImgLoader.toString();
	}
}
