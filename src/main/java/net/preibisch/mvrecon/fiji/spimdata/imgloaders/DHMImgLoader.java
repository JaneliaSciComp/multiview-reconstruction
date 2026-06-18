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
import java.util.List;

import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.legacy.LegacyImgLoaderWrapper;
import net.imglib2.type.numeric.integer.UnsignedShortType;

public class DHMImgLoader extends LegacyImgLoaderWrapper< UnsignedShortType, LegacyDHMImgLoader >
{
	public DHMImgLoader(
			final File directory,
			final String stackDir,
			final String amplitudeDir,
			final String phaseDir,
			final List< String > timepoints,
			final List< String > zPlanes,
			final String extension,
			final int ampChannelId,
			final int phaseChannelId,
			final AbstractSequenceDescription< ?, ?, ? > sd )
	{
		super( new LegacyDHMImgLoader( directory, stackDir, amplitudeDir, phaseDir, timepoints, zPlanes, extension, ampChannelId, phaseChannelId, sd ) );
	}

	public String getStackDir() { return legacyImgLoader.getStackDir(); }
	public String getAmplitudeDir() { return legacyImgLoader.getAmplitudeDir(); }
	public String getPhaseDir() { return legacyImgLoader.getPhaseDir(); }
	public List< String > getZPlanes() { return legacyImgLoader.getZPlanes(); }
	public List< String > getTimepoints() { return legacyImgLoader.getTimepoints(); }
	public int getAmpChannelId() { return legacyImgLoader.getAmpChannelId(); }
	public int getPhaseChannelId() { return legacyImgLoader.getPhaseChannelId(); }
	public String getExt() { return legacyImgLoader.getExt(); }
}
