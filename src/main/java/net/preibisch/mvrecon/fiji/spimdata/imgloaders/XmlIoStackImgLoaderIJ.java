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

import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import net.imglib2.img.ImgFactory;
import net.imglib2.type.NativeType;

@ImgLoaderIo( format = "spimreconstruction.stack.ij", type = StackImgLoaderIJ.class )
public class XmlIoStackImgLoaderIJ extends XmlIoStackImgLoader< StackImgLoaderIJ >
{
	@Override
	protected StackImgLoaderIJ createImgLoader( File path, String fileNamePattern, 
			int layoutTP, int layoutChannels, int layoutIllum, int layoutAngles, int layoutTiles,
			AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		return new StackImgLoaderIJ( path, fileNamePattern, layoutTP, layoutChannels, layoutIllum, layoutAngles, layoutTiles, sequenceDescription );
	}
}
