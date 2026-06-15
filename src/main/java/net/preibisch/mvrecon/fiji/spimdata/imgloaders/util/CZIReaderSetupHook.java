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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.util;

import ch.epfl.biop.formats.in.ZeissQuickStartCZIReader;
import loci.formats.IFormatReader;
import loci.formats.in.DynamicMetadataOptions;
import loci.formats.in.MetadataOptions;
import loci.formats.in.ZeissCZIReader;

public class CZIReaderSetupHook implements BioformatsReaderSetupHook {

	private boolean allowAutostitch = false;
	private boolean relativePositions = true;
	private static CZIReaderSetupHook instance = null;

	private CZIReaderSetupHook() {}

	public static CZIReaderSetupHook getInstance()
	{
		if (instance == null)
		{
			instance = new CZIReaderSetupHook();
		}
		return instance;
	}

	public void setAutoStitch(boolean allowAutostitch) {
		this.allowAutostitch = allowAutostitch;
	}

	public void setRelativePositions(boolean relativePositions)
	{
		this.relativePositions = relativePositions;
	}

	@Override
	public void runSetup(IFormatReader reader) {
		if (reader instanceof ZeissCZIReader) {
			configureCZIReader(
					reader,
					ZeissCZIReader.ALLOW_AUTOSTITCHING_KEY,
					ZeissCZIReader.RELATIVE_POSITIONS_KEY
			);
		} else if (reader instanceof ZeissQuickStartCZIReader) {
			configureCZIReader(
					reader,
					ZeissCZIReader.ALLOW_AUTOSTITCHING_KEY,
					ZeissCZIReader.RELATIVE_POSITIONS_KEY
			);
		}
	}

	private void configureCZIReader(
			final IFormatReader reader,
			final String autoStitchKey,
			final String relativePositionsKey)
	{
		// disable auto stitching, following solutions by @CellKai and @NicoKiaru in
		// https://forum.image.sc/t/change-in-czi-tile-info-metadata-after-upgrade-to-zeiss-lightsheet-7/49414/15
		final MetadataOptions options = reader.getMetadataOptions();
		if (options instanceof DynamicMetadataOptions) {
			final DynamicMetadataOptions dynamicOptions = (DynamicMetadataOptions) options;
			dynamicOptions.setBoolean(autoStitchKey, allowAutostitch);
			dynamicOptions.setBoolean(relativePositionsKey, relativePositions);
			reader.setMetadataOptions(dynamicOptions);
		} else {
			System.err.println("WARNING: could not set CZI metadata options for " + reader.getClass().getSimpleName() + ".");
		}
	}

}
