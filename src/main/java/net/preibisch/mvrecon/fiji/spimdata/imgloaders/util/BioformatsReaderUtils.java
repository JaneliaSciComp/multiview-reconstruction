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

import java.io.File;
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

	/**
	 * Whether Bio-Formats' {@link loci.formats.Memoizer} can save and load memo files: it serializes the reader state
	 * with Kryo 5 (com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy), which BigStitcher-Spark excludes
	 * because it clashes with Spark's Kryo 4. Without it the Memoizer crashes with a NoClassDefFoundError as soon
	 * as opening a file takes longer than its minimum elapsed time.
	 */
	public static final boolean MEMOIZER_AVAILABLE = kryoForMemoizerPresent();

	private static boolean kryoForMemoizerPresent()
	{
		try
		{
			Class.forName( "com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy" );
			return true;
		}
		catch ( Throwable t )
		{
			System.out.println( "Kryo 5 is not on the classpath, Bio-Formats memo files are disabled (readers are set up from scratch each time)." );
			return false;
		}
	}

	/**
	 * The directory for a Memoizer's memo files, or null when Kryo 5 is missing: a Memoizer constructed with
	 * (reader, minimumElapsed, null) never saves or loads a memo and therefore never touches Kryo.
	 */
	public static File memoizerDirectory( final File memoDir )
	{
		return MEMOIZER_AVAILABLE ? memoDir : null;
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
