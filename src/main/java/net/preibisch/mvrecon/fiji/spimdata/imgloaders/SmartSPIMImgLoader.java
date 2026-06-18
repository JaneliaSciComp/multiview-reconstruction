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

import java.util.HashMap;
import java.util.Map;

import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.preibisch.mvrecon.fiji.datasetmanager.SmartSPIM.SmartSPIMMetaData;

public class SmartSPIMImgLoader implements ImgLoader
{
	final SmartSPIMMetaData metadata;
	final AbstractSequenceDescription<?, ?, ?> sequenceDescription;;

	/**
	 * Maps setup id to {@link SetupImgLoader}.
	 */
	private final Map< Integer, SmartSPIMSetupImgLoader > setupImgLoaders = new HashMap<>();

	public SmartSPIMImgLoader(
			final SmartSPIMMetaData metadata,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		this.metadata = metadata;
		this.sequenceDescription = sequenceDescription;
	}

	@Override
	public SetupImgLoader<?> getSetupImgLoader( final int setupId )
	{
		return open( setupId );
	}

	private SetupImgLoader< UnsignedShortType > open( final int setupId )
	{
		if ( !setupImgLoaders.containsKey( setupId ) )
		{
			synchronized ( this )
			{
				if ( !setupImgLoaders.containsKey( setupId ) )
				{
					setupImgLoaders.put( setupId, new SmartSPIMSetupImgLoader( setupId, metadata, sequenceDescription ) );
				}
			}
		}

		return setupImgLoaders.get( setupId );
	}

}
