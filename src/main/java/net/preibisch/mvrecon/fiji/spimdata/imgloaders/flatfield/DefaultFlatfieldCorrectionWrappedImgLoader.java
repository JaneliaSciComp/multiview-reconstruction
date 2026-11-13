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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import java.io.File;

import ij.ImageJ;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;


public class DefaultFlatfieldCorrectionWrappedImgLoader extends LazyLoadingFlatFieldCorrectionMap< ImgLoader >
		implements ImgLoader
{
	private final ImgLoader wrappedImgLoader;
	private boolean active;
	/** unused: read from old XMLs only */
	private boolean cacheResult;

	public DefaultFlatfieldCorrectionWrappedImgLoader(ImgLoader wrappedImgLoader)
	{
		this( wrappedImgLoader, true );
	}

	public DefaultFlatfieldCorrectionWrappedImgLoader(ImgLoader wrappedImgLoader, boolean cacheResult)
	{
		super();
		this.wrappedImgLoader = wrappedImgLoader;
		this.active = true;
		this.cacheResult = cacheResult;
	}

	@Override
	public ImgLoader getWrappedImgLoder()
	{
		return wrappedImgLoader;
	}

	@Override
	public void setActive(boolean active)
	{
		this.active = active;
	}

	@Override
	public boolean isActive()
	{
		return active;
	}

	@Override
	public SetupImgLoader< ? > getSetupImgLoader(int setupId)
	{
		return new DefaultFlatfieldCorrectionWrappedSetupImgLoader<>( setupId );
	}

	class DefaultFlatfieldCorrectionWrappedSetupImgLoader<T extends RealType< T > & NativeType< T >>
			implements SetupImgLoader< T >
	{
		private final int setupId;

		DefaultFlatfieldCorrectionWrappedSetupImgLoader(int setupId)
		{
			this.setupId = setupId;
		}

		@Override
		public RandomAccessibleInterval< T > getImage(int timepointId, ImgLoaderHint... hints) {
			if (!active)
				return wrapped().getImage(timepointId, hints);
			return corrected(timepointId, getImageType(), false, hints);
		}

		@SuppressWarnings("unchecked")
		private SetupImgLoader< T > wrapped()
		{
			return (SetupImgLoader< T >) wrappedImgLoader.getSetupImgLoader(setupId);
		}

		private < O extends RealType< O > & NativeType< O > > RandomAccessibleInterval< O > corrected(
				int timepointId, O type, boolean normalize, ImgLoaderHint... hints)
		{
			final ViewId view = new ViewId(timepointId, setupId);
			return FlatFieldCorrectedRandomAccessibleIntervals.create(
					wrapped().getImage(timepointId, hints), getBrightImg(view), getDarkImg(view), type, normalize, hints);
		}

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage(int timepointId, boolean normalize,
				ImgLoaderHint... hints)
		{
			if (!active)
				return wrapped().getFloatImage(timepointId, false, hints);
			return corrected(timepointId, new FloatType(), normalize, hints);
		}

		@Override
		public T getImageType()
		{
			@SuppressWarnings("unchecked")
			final T res = (T) wrappedImgLoader.getSetupImgLoader( setupId ).getImageType();
			return res;
		}

		@Override
		public Dimensions getImageSize(int timepointId)
		{
			return wrappedImgLoader.getSetupImgLoader( setupId ).getImageSize( timepointId );
		}

		@Override
		public VoxelDimensions getVoxelSize(int timepointId)
		{
			return wrappedImgLoader.getSetupImgLoader( setupId ).getVoxelSize( timepointId );
		}
	}

	public static void main(String[] args)
	{
		LoadParseQueryXML lpq = new LoadParseQueryXML();
		lpq.queryXML();
		SpimData2 data = lpq.getData();

		ImgLoader il = data.getSequenceDescription().getImgLoader();
		DefaultFlatfieldCorrectionWrappedImgLoader ffcil = new DefaultFlatfieldCorrectionWrappedImgLoader( il );
		ffcil.setDarkImage(new ViewId(0, 0), new FlatfieldImageInfo(new File("/Users/david/desktop/ff.tif").toURI()));

		data.getSequenceDescription().setImgLoader( ffcil );

		new ImageJ();

		RandomAccessibleInterval< FloatType > image = data.getSequenceDescription().getImgLoader()
				.getSetupImgLoader( 0 ).getFloatImage( 0, false );

		RandomAccessibleInterval< FloatType > downsampleBlock = MultiResolutionFlatfieldCorrectionWrappedImgLoader
				.downsampleHDF5( image, new int[] { 3, 3, 2 } );
		ImageJFunctions.show( downsampleBlock, "" );

	}

	@Override
	public boolean isCached()
	{
		return cacheResult;
	}

	@Override
	public void setCached(boolean cached)
	{
		cacheResult = cached;
	}

}
