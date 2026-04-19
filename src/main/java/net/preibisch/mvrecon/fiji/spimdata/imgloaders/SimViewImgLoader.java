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
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import net.preibisch.mvrecon.fiji.datasetmanager.SimViewMetaData;
import net.preibisch.mvrecon.fiji.datasetmanager.SimViewMetaData.Pattern;

public class SimViewImgLoader implements ImgLoader
{
	final AbstractSequenceDescription<?, ?, ?> sequenceDescription;
	final File expDir;
	final String filePattern;
	final Pattern patternParser;
	final int type;
	final boolean littleEndian;

	public SimViewImgLoader(
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription,
			final File expDir,
			final String filePattern,
			final int type,
			final boolean littleEndian )
	{
		this.sequenceDescription = sequenceDescription;
		this.expDir = expDir;
		this.filePattern = filePattern;
		this.type = type;
		this.littleEndian = littleEndian;

		this.patternParser = SimViewMetaData.buildPatternParser( filePattern );
	}

	@Override
	public SetupImgLoader<?> getSetupImgLoader( final int setupId )
	{
		return new SimViewSetupImgLoader(
				setupId,
				sequenceDescription,
				expDir,
				filePattern,
				patternParser,
				type,
				littleEndian );
	}
}
