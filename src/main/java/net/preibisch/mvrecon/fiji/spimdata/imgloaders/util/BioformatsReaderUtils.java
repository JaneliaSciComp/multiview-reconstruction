/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.util;

import java.util.HashMap;

import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.in.ZeissCZIReader;

public class BioformatsReaderUtils {

	static HashMap<Class<? extends IFormatReader>, BioformatsReaderSetupHook> setupHooks = new HashMap<>();
	static {
		setupHooks.put(ZeissCZIReader.class, CZIReaderSetupHook.getInstance());
	}

	public static ImageReader createImageReaderWithSetupHooks()
	{
		final ImageReader reader = new ImageReader();

		// go through all specific format readers and run setup if necessary
		for(final IFormatReader formatReader : reader.getReaders())
			if (setupHooks.containsKey(formatReader.getClass()))
				setupHooks.get(formatReader.getClass()).runSetup(formatReader);

		return reader;
	}

	public static int getBytesPerPixel(final IFormatReader reader)
	{
		final int bytesPerPixel = FormatTools.getBytesPerPixel(reader.getPixelType());

		if (bytesPerPixel <= 0)
		{
			final int bitsPerPixel = reader.getBitsPerPixel();

			if (bitsPerPixel > 0 && bitsPerPixel % 8 == 0)
				return bitsPerPixel / 8;

			throw new IllegalArgumentException(
					"Could not determine bytes per pixel for pixelType=" + reader.getPixelType() +
					", bitsPerPixel=" + bitsPerPixel);
		}

		return bytesPerPixel;
	}

	public static int getPlaneSizeInBytes(final IFormatReader reader)
	{
		return Math.multiplyExact(
				Math.multiplyExact(getBytesPerPixel(reader), reader.getSizeX()),
				reader.getSizeY());
	}
}
