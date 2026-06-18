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

import java.io.File;
import java.util.Arrays;

import ij.ImageJ;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.generic.sequence.ImgLoaderHints;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapImgLoaderLOCI2;
import net.preibisch.mvrecon.process.fusion.FusionTools;


public class DefaultFlatfieldCorrectionWrappedImgLoader extends LazyLoadingFlatFieldCorrectionMap< ImgLoader >
		implements ImgLoader
{
	private ImgLoader wrappedImgLoader;
	private boolean active;
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
		public RandomAccessibleInterval< T > getImage(int timepointId, ImgLoaderHint... hints)
		{
			if (!active)
				return (RandomAccessibleInterval< T >) wrappedImgLoader.getSetupImgLoader( setupId ).getImage( timepointId,
						hints );

			@SuppressWarnings("unchecked")
			RandomAccessibleInterval< T > rai = FlatFieldCorrectedRandomAccessibleIntervals.create(
					(RandomAccessibleInterval< T >) wrappedImgLoader.getSetupImgLoader( setupId ).getImage( timepointId,
							hints ),
					getBrightImg( new ViewId( timepointId, setupId ) ),
					getDarkImg( new ViewId( timepointId, setupId ) ) );

			boolean loadCompletelyRequested = false;
			for (ImgLoaderHint hint : hints)
				if (hint == ImgLoaderHints.LOAD_COMPLETELY)
					loadCompletelyRequested = true;

			if (loadCompletelyRequested)
			{
				long numPx = 1;
				for (int d = 0; d < rai.numDimensions(); d++)
					numPx *= rai.dimension( d );

				final ImgFactory< T > imgFactory;
				if (Math.log(numPx) / Math.log( 2 ) < 31)
					imgFactory = new ArrayImgFactory<T>();
				else
					imgFactory = new CellImgFactory<T>();

				Img< T > loadedImg = imgFactory.create( rai, getImageType() );
				RealTypeConverters.copyFromTo( Views.extendZero( rai ), loadedImg );

				rai = loadedImg;
			}

			else if ( cacheResult )
			{
				final int[] cellSize = new int[rai.numDimensions()];
				Arrays.fill( cellSize, 1 );
				for ( int d = 0; d < rai.numDimensions() - 1; d++ )
					cellSize[d] = (int) rai.dimension( d );
				rai = FusionTools.cacheRandomAccessibleInterval( rai, Long.MAX_VALUE,
						Views.iterable( rai ).firstElement().createVariable(), cellSize );
			}

			return rai;

		}

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage(int timepointId, boolean normalize,
				ImgLoaderHint... hints)
		{
			if (!active)
				return (RandomAccessibleInterval< FloatType >) wrappedImgLoader.getSetupImgLoader( setupId ).getFloatImage( timepointId,
						false, hints );

			@SuppressWarnings("unchecked")
			RandomAccessibleInterval< FloatType > rai = FlatFieldCorrectedRandomAccessibleIntervals.create(
					(RandomAccessibleInterval< T >) wrappedImgLoader.getSetupImgLoader( setupId ).getImage( timepointId,
							hints ),
					getBrightImg( new ViewId( timepointId, setupId ) ),
					getDarkImg( new ViewId( timepointId, setupId ) ), new FloatType() );

			if ( normalize )
			{
				RandomAccessibleInterval< FloatType > raiNormalized = new VirtuallyNormalizedRandomAccessibleInterval<>(
						rai );
				boolean loadCompletelyRequested = false;
				for (ImgLoaderHint hint : hints)
					if (hint == ImgLoaderHints.LOAD_COMPLETELY)
						loadCompletelyRequested = true;

				if (loadCompletelyRequested)
				{
					long numPx = 1;
					for (int d = 0; d < raiNormalized.numDimensions(); d++)
						numPx *= raiNormalized.dimension( d );

					final ImgFactory< FloatType > imgFactory;
					if (Math.log(numPx) / Math.log( 2 ) < 31)
						imgFactory = new ArrayImgFactory<FloatType>();
					else
						imgFactory = new CellImgFactory<FloatType>();

					Img< FloatType > loadedImg = imgFactory.create( raiNormalized, new FloatType() );
					FileMapImgLoaderLOCI2.copy(Views.extendZero( raiNormalized ), loadedImg);

					raiNormalized = loadedImg;
				}
				else if ( cacheResult )
				{
					final int[] cellSize = new int[raiNormalized.numDimensions()];
					Arrays.fill( cellSize, 1 );
					for ( int d = 0; d < raiNormalized.numDimensions() - 1; d++ )
						cellSize[d] = (int) raiNormalized.dimension( d );
					raiNormalized = FusionTools.cacheRandomAccessibleInterval( raiNormalized, Long.MAX_VALUE,
							Views.iterable( rai ).firstElement().createVariable(), cellSize );
				}
				rai = raiNormalized;
			}
			else
			{
				boolean loadCompletelyRequested = false;
				for (ImgLoaderHint hint : hints)
					if (hint == ImgLoaderHints.LOAD_COMPLETELY)
						loadCompletelyRequested = true;

				if (loadCompletelyRequested)
				{
					long numPx = 1;
					for (int d = 0; d < rai.numDimensions(); d++)
						numPx *= rai.dimension( d );

					final ImgFactory< FloatType > imgFactory;
					if (Math.log(numPx) / Math.log( 2 ) < 31)
						imgFactory = new ArrayImgFactory<FloatType>();
					else
						imgFactory = new CellImgFactory<FloatType>();

					Img< FloatType > loadedImg = imgFactory.create( rai, new FloatType() );
					FileMapImgLoaderLOCI2.copy(Views.extendZero( rai ), loadedImg);

					rai = loadedImg;
				}
				else if ( cacheResult )
				{
					final int[] cellSize = new int[rai.numDimensions()];
					Arrays.fill( cellSize, 1 );
					for ( int d = 0; d < rai.numDimensions() - 1; d++ )
						cellSize[d] = (int) rai.dimension( d );
					rai = FusionTools.cacheRandomAccessibleInterval( rai, Long.MAX_VALUE,
							Views.iterable( rai ).firstElement().createVariable(), cellSize );
				}

			}
			return rai;
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
		ffcil.setDarkImage( new ViewId( 0, 0 ), new File( "/Users/david/desktop/ff.tif" ) );

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
