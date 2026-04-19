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

import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.datasetmanager.SmartSPIM.SmartSPIMMetaData;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.smartspim.LazySmartSpimLoader;
import util.ImgLib2Tools;

public class SmartSPIMSetupImgLoader implements SetupImgLoader< UnsignedShortType >
{
	final int setupId;
	final AbstractSequenceDescription<?, ?, ?> sequenceDescription;
	final SmartSPIMMetaData metadata;
	final BasicViewSetup setup;

	private RandomAccessibleInterval< UnsignedShortType > image = null;

	public SmartSPIMSetupImgLoader(
			final int setupId,
			final SmartSPIMMetaData metadata,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription )
	{
		this.setupId = setupId;
		this.metadata = metadata;
		this.sequenceDescription = sequenceDescription;

		this.setup = sequenceDescription.getViewSetups().get( setupId );
	}

	@Override
	public RandomAccessibleInterval<UnsignedShortType> getImage( final int timepointId, final ImgLoaderHint... hints )
	{
		open();
		return image;
	}

	@Override
	public UnsignedShortType getImageType() { return new UnsignedShortType(); }

	@Override
	public RandomAccessibleInterval<FloatType> getFloatImage( final int timepointId, final boolean normalize, final ImgLoaderHint... hints)
	{
		if ( normalize )
			return ImgLib2Tools.normalizeVirtualRAI( getImage( timepointId, hints ) );
		else
			return ImgLib2Tools.convertVirtualRAI( getImage( timepointId, hints ) );
	}

	@Override
	public Dimensions getImageSize( final int timepointId )
	{
		return setup.getSize();
	}

	@Override
	public VoxelDimensions getVoxelSize( final int timepointId )
	{
		return setup.getVoxelSize();
	}

	private void open()
	{
		if ( image == null )
		{
			synchronized ( this )
			{
				if ( image != null )
					return;

				final int channel = ((ViewSetup)setup).getChannel().getId();
				final int xTile = ((ViewSetup)setup).getTile().getId() % metadata.xTileLocations.size();
				final int yTile = ((ViewSetup)setup).getTile().getId() / metadata.xTileLocations.size();

				image = LazySmartSpimLoader.init( metadata, channel, xTile, yTile );
			}
		}
	}

}
