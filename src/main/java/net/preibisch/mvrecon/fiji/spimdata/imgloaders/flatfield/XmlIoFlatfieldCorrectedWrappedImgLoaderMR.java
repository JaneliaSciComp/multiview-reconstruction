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

import mpicbg.spim.data.generic.sequence.ImgLoaderIo;

/**
 * Dummy class so we can use XmlIoFlatfieldCorrectedWrappedImgLoader for both
 * multiresolution and non-multiresolution ImgLoaders with flatfield correction
 * 
 * @author david
 *
 */

@ImgLoaderIo(format = "spimreconstruction.wrapped.flatfield.multiresolution", type = MultiResolutionFlatfieldCorrectionWrappedImgLoader.class)
public class XmlIoFlatfieldCorrectedWrappedImgLoaderMR extends XmlIoFlatfieldCorrectedWrappedImgLoader
{
}
