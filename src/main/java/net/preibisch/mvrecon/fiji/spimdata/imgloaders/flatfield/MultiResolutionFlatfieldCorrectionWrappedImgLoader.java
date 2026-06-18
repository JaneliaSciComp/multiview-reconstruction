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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import bdv.export.WriteSequenceToHdf5;
import ij.ImageJ;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.generic.sequence.ImgLoaderHints;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapImgLoaderLOCI2;
import net.preibisch.mvrecon.process.fusion.FusionTools;


public class MultiResolutionFlatfieldCorrectionWrappedImgLoader
		extends LazyLoadingFlatFieldCorrectionMap< MultiResolutionImgLoader > implements MultiResolutionImgLoader
{

	private MultiResolutionImgLoader wrappedImgLoader;
	private boolean active;
	private boolean cacheResult;

	/* downsampled bright/dark images */
	private final Map< Pair< File, List< Integer > >, RandomAccessibleInterval< FloatType > > dsRaiMap;

	public MultiResolutionFlatfieldCorrectionWrappedImgLoader(MultiResolutionImgLoader wrappedImgLoader)
	{
		this( wrappedImgLoader, true );
	}

	public MultiResolutionFlatfieldCorrectionWrappedImgLoader(MultiResolutionImgLoader wrappedImgLoader,
			boolean cacheResult)
	{
		super();
		this.wrappedImgLoader = wrappedImgLoader;
		this.active = true;
		this.cacheResult = cacheResult;

		dsRaiMap = new HashMap<>();
	}

	protected RandomAccessibleInterval< FloatType > getOrCreateBrightImgDownsampled(ViewId vId,
			int[] downsamplingFactors)
	{
		ArrayList< Integer > dsFactorList = new ArrayList< Integer >();
		for ( int i : downsamplingFactors )
			dsFactorList.add( i );

		final ValuePair< File, List< Integer > > key = new ValuePair<>( fileMap.get( vId ).getA(), dsFactorList );

		if ( !dsRaiMap.containsKey( key ) )
		{
			final RandomAccessibleInterval< FloatType > brightImg = getBrightImg( vId );

			if ( brightImg == null )
				return null;

			// NB: we add a singleton z-dimension here for downsampleHDF5 to
			// work
			final RandomAccessibleInterval< FloatType > downsampled = downsampleHDF5(
					Views.addDimension( brightImg, 0, 0 ), downsamplingFactors );
			dsRaiMap.put( key, downsampled );
		}

		return dsRaiMap.get( key );
	}

	protected RandomAccessibleInterval< FloatType > getOrCreateDarkImgDownsampled(ViewId vId, int[] downsamplingFactors)
	{
		ArrayList< Integer > dsFactorList = new ArrayList< Integer >();
		for ( int i : downsamplingFactors )
			dsFactorList.add( i );

		final ValuePair< File, List< Integer > > key = new ValuePair<>( fileMap.get( vId ).getB(), dsFactorList );

		if ( !dsRaiMap.containsKey( key ) )
		{
			final RandomAccessibleInterval< FloatType > darkImg = getDarkImg( vId );

			if ( darkImg == null )
				return null;

			// NB: we add a singleton z-dimension here for downsampleHDF5 to
			// work
			final RandomAccessibleInterval< FloatType > downsampled = downsampleHDF5(
					Views.addDimension( darkImg, 0, 0 ), downsamplingFactors );
			dsRaiMap.put( key, downsampled );
		}

		return dsRaiMap.get( key );
	}

	@Override
	public MultiResolutionSetupImgLoader< ? > getSetupImgLoader(int setupId)
	{
		return new MultiResolutionFlatfieldCorrectionWrappedSetupImgLoader<>( setupId );
	}

	@Override
	public MultiResolutionImgLoader getWrappedImgLoder()
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

	class MultiResolutionFlatfieldCorrectionWrappedSetupImgLoader<T extends RealType< T > & NativeType< T >>
			implements MultiResolutionSetupImgLoader< T >
	{

		private final int setupId;

		public MultiResolutionFlatfieldCorrectionWrappedSetupImgLoader(int setupId)
		{
			this.setupId = setupId;
		}

		@Override
		public RandomAccessibleInterval< T > getImage(int timepointId, int level, ImgLoaderHint... hints)
		{
			/*
			 * TODO: should we care about the MipmapTransform here? are there
			 * other MultiresolutionImgLoaders that do pyramid differently?
			 */

			final MultiResolutionSetupImgLoader< ? > wrpSetupIL = wrappedImgLoader.getSetupImgLoader( setupId );

			if(!active)
				return (RandomAccessibleInterval< T >) wrpSetupIL.getImage( timepointId, level, hints );

			final int n = wrpSetupIL.getImageSize( timepointId ).numDimensions();

			final int[] dsFactors = new int[n];
			final double[] dsD = wrpSetupIL.getMipmapResolutions()[level];
			for ( int d = 0; d < n; d++ )
				// NB: we might need to round here -> test!
				dsFactors[d] = (int) dsD[d];
			// we should not need the last dimension
			dsFactors[n - 1] = 1;

			@SuppressWarnings("unchecked")
			RandomAccessibleInterval< T > rai = FlatFieldCorrectedRandomAccessibleIntervals.create(
					(RandomAccessibleInterval< T >) wrpSetupIL.getImage( timepointId, level, hints ),
					getOrCreateBrightImgDownsampled( new ViewId( timepointId, setupId ), dsFactors ),
					getOrCreateDarkImgDownsampled( new ViewId( timepointId, setupId ), dsFactors ) );

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
				rai =  FusionTools.cacheRandomAccessibleInterval( rai, Long.MAX_VALUE,
						Views.iterable( rai ).firstElement().createVariable(), cellSize );
			}
			return rai;
		}

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage(int timepointId, int level, boolean normalize,
				ImgLoaderHint... hints)
		{
			final MultiResolutionSetupImgLoader< ? > wrpSetupIL = wrappedImgLoader.getSetupImgLoader( setupId );

			if(!active)
				return wrpSetupIL.getFloatImage( timepointId, level, normalize, hints );

			final int n = wrpSetupIL.getImageSize( timepointId ).numDimensions();

			final int[] dsFactors = new int[n];
			final double[] dsD = wrpSetupIL.getMipmapResolutions()[level];
			for ( int d = 0; d < n; d++ )
				// NB: we might need to round here -> test!
				dsFactors[d] = (int) dsD[d];
			// we should not need the last dimension
			dsFactors[n - 1] = 1;

			@SuppressWarnings("unchecked")
			RandomAccessibleInterval< FloatType > rai = FlatFieldCorrectedRandomAccessibleIntervals.create(
					(RandomAccessibleInterval< T >) wrpSetupIL.getImage( timepointId, level, hints ),
					getOrCreateBrightImgDownsampled( new ViewId( timepointId, setupId ), dsFactors ),
					getOrCreateDarkImgDownsampled( new ViewId( timepointId, setupId ), dsFactors ), new FloatType() );

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
					rai =  FusionTools.cacheRandomAccessibleInterval( raiNormalized, Long.MAX_VALUE,
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
		public RandomAccessibleInterval< T > getImage(int timepointId, ImgLoaderHint... hints)
		{
			return getImage( timepointId, 0, hints );
		}

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage(int timepointId, boolean normalize,
				ImgLoaderHint... hints)
		{
			return getFloatImage( timepointId, 0, normalize, hints );
		}

		@Override
		public double[][] getMipmapResolutions()
		{
			return wrappedImgLoader.getSetupImgLoader( setupId ).getMipmapResolutions();
		}

		@Override
		public AffineTransform3D[] getMipmapTransforms()
		{
			return wrappedImgLoader.getSetupImgLoader( setupId ).getMipmapTransforms();
		}

		@Override
		public int numMipmapLevels()
		{
			return wrappedImgLoader.getSetupImgLoader( setupId ).numMipmapLevels();
		}

		@Override
		public T getImageType()
		{
			@SuppressWarnings("unchecked")
			T res = (T) wrappedImgLoader.getSetupImgLoader( setupId ).getImageType();
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

		@Override
		public Dimensions getImageSize(int timepointId, int level)
		{
			return wrappedImgLoader.getSetupImgLoader( setupId ).getImageSize( timepointId, level );
		}

	}

	/**
	 * downsampling code form {@link WriteSequenceToHdf5}, distilled into one
	 * method
	 * 
	 * @param input
	 *            image to downsample
	 * @param dsFactor
	 *            factors to downsample by
	 * @param <T>
	 *            the image type
	 * @return downsampled image
	 */
	public static <T extends RealType< T > & NativeType< T >> RandomAccessibleInterval< T > downsampleHDF5(
			RandomAccessibleInterval< T > input, final int[] dsFactor)
	{
		final long[] blockMin = new long[input.numDimensions()];

		final long[] outDim = new long[input.numDimensions()];
		for ( int d = 0; d < input.numDimensions(); d++ )
			outDim[d] = Math.max( input.dimension( d ) / dsFactor[d], 1 );

		final Img< T > downsampled = new ArrayImgFactory< T >().create( new FinalDimensions( outDim ),
				Views.iterable( input ).firstElement().createVariable() );
		final RandomAccess< T > randomAccess = Views.extendBorder( input ).randomAccess();

		final Cursor< T > out = downsampled.cursor();

		double scale = 1;
		for ( int f : dsFactor )
			scale *= f;
		scale = 1.0 / scale;

		final int numBlockPixels = (int) ( outDim[0] * outDim[1] * outDim[2] );
		final double[] accumulator = new double[numBlockPixels];

		randomAccess.setPosition( blockMin );

		final int ox = (int) outDim[0];
		final int oy = (int) outDim[1];
		final int oz = (int) outDim[2];

		final int sx = ox * dsFactor[0];
		final int sy = oy * dsFactor[1];
		final int sz = oz * dsFactor[2];

		int i = 0;
		for ( int z = 0, bz = 0; z < sz; ++z )
		{
			for ( int y = 0, by = 0; y < sy; ++y )
			{
				for ( int x = 0, bx = 0; x < sx; ++x )
				{
					accumulator[i] += randomAccess.get().getRealDouble();
					randomAccess.fwd( 0 );
					if ( ++bx == dsFactor[0] )
					{
						bx = 0;
						++i;
					}
				}
				randomAccess.move( -sx, 0 );
				randomAccess.fwd( 1 );
				if ( ++by == dsFactor[1] )
					by = 0;
				else
					i -= ox;
			}
			randomAccess.move( -sy, 1 );
			randomAccess.fwd( 2 );
			if ( ++bz == dsFactor[2] )
				bz = 0;
			else
				i -= ox * oy;
		}

		for ( int j = 0; j < numBlockPixels; ++j )
			out.next().setReal( accumulator[j] * scale );

		return downsampled;
	}

	public static void main(String[] args)
	{
		LoadParseQueryXML lpq = new LoadParseQueryXML();
		lpq.queryXML();
		SpimData2 data = lpq.getData();

		// this will crash if il is not multires
		MultiResolutionImgLoader il = (MultiResolutionImgLoader) data.getSequenceDescription().getImgLoader();
		MultiResolutionFlatfieldCorrectionWrappedImgLoader ffcil = new MultiResolutionFlatfieldCorrectionWrappedImgLoader(
				il );
		ffcil.setDarkImage( new ViewId( 0, 0 ), new File( "/Users/david/desktop/ff.tif" ) );

		data.getSequenceDescription().setImgLoader( ffcil );

		new ImageJ();

		RandomAccessibleInterval< FloatType > image = ( (MultiResolutionImgLoader) data.getSequenceDescription()
				.getImgLoader() ).getSetupImgLoader( 0 ).getFloatImage( 0, 1, false );
		ImageJFunctions.show( image );
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
