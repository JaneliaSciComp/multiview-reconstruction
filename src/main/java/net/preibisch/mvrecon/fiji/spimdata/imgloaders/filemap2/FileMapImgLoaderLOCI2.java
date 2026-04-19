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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import com.google.common.io.Files;

import loci.formats.FileStitcher;
import loci.formats.IFormatReader;
import loci.formats.Memoizer;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.generic.sequence.ImgLoaderHint;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.Cache;
import net.imglib2.cache.ref.WeakRefLoaderCache;
import net.imglib2.converter.RealTypeConverters;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.CloseableThreadLocal;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.util.BioformatsReaderUtils;
import util.ImgLib2Tools;

public class FileMapImgLoaderLOCI2 implements ImgLoader, FileMapGettable
{
	private final Map< ViewId, FileMapEntry > fileMap;

	private final File tempDir;

	private final AbstractSequenceDescription< ?, ?, ? > sd;

	final boolean zGrouped;

	private final boolean allTimepointsInSingleFiles;

	private final Map< Integer, SetupImgLoader< ? > > setupImgLoaders = new ConcurrentHashMap<>();

	public FileMapImgLoaderLOCI2(
			final Map< ? extends ViewId, FileMapEntry > fileMap,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		this( fileMap, sequenceDescription, false );
	}

	public FileMapImgLoaderLOCI2( Map< ? extends ViewId, FileMapEntry > fileMap,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription,
			final boolean zGrouped )
	{
		this.fileMap = new HashMap<>( fileMap );
		this.tempDir = Files.createTempDir();
		this.sd = sequenceDescription;
		this.zGrouped = zGrouped;

		// populate map file -> {time points}
		final Map< File, Set< Integer > > tpsPerFile = new HashMap<>();
		boolean allTimepointsInSingleFiles = true;
		for ( final Map.Entry< ? extends ViewId, FileMapEntry > entry : fileMap.entrySet() )
		{
			final ViewId vid = entry.getKey();
			final File file = entry.getValue().file();
			final Set< Integer > tps = tpsPerFile.computeIfAbsent( file, k -> new HashSet<>() );
			tps.add( vid.getTimePointId() );

			// the current file has more than one time point
			if ( tps.size() > 1 )
			{
				allTimepointsInSingleFiles = false;
				break;
			}
		}
		this.allTimepointsInSingleFiles = allTimepointsInSingleFiles;

		this.getReader = () -> {
			// use a new ImageReader since we might be loading multi-threaded and BioFormats is not thread-save
			// use Memoizer to cache ReaderState for each File on disk
			// see: https://www-legacy.openmicroscopy.org/site/support/bio-formats5.1/developers/matlab-dev.html#reader-performance
			IFormatReader reader = null;
			if ( zGrouped )
			{
				final FileStitcher fs = new FileStitcher( true );
				fs.setCanChangePattern( false );
				reader = new Memoizer( fs, Memoizer.DEFAULT_MINIMUM_ELAPSED, tempDir );
			}
			else
			{
				reader = new Memoizer( BioformatsReaderUtils.createImageReaderWithSetupHooks(), Memoizer.DEFAULT_MINIMUM_ELAPSED, tempDir );
			}

			return reader;
		};
	}

	@Override
	public SetupImgLoader< ? > getSetupImgLoader( int setupId )
	{
		return setupImgLoaders.computeIfAbsent( setupId, FileMapSetupImgLoaderLOCI2::new );
	}

	@Override
	public Map< ViewId, FileMapEntry > getFileMap()
	{
		return fileMap;
	}

	private final Supplier< IFormatReader > getReader;

	public class FileMapSetupImgLoaderLOCI2< T extends RealType< T > & NativeType< T > > implements SetupImgLoader< T >
	{
		private final int setupId;

		private final CloseableThreadLocal< IFormatReader > threadLocalReader = CloseableThreadLocal.withInitial( getReader );

		private final Cache< Integer, RandomAccessibleInterval< T > > images = new WeakRefLoaderCache< Integer, RandomAccessibleInterval< T > >().withLoader( this::createImage );

		private final Supplier< T > type;

		public FileMapSetupImgLoaderLOCI2( int setupId )
		{
			this.setupId = setupId;
			this.type = lazyInit( () -> {
				final BasicViewDescription< ? > aVd = getAnyPresentViewDescriptionForViewSetup( sd, setupId );
				if ( aVd == null )
					return null;
				final FileMapEntry entry = fileMap.get( aVd );
				return VirtualRAIFactoryLOCI.getType( threadLocalReader::get, entry.file(), entry.series() );
			} );
		}

		private RandomAccessibleInterval< T > createImage( final int timepointId )
		{
			final FileMapEntry entry = fileMap.get( new ViewId( timepointId, setupId ) );
			return VirtualRAIFactoryLOCI.createVirtualCached(
					threadLocalReader::get,
					entry.file(),
					entry.series(),
					entry.channel(),
					allTimepointsInSingleFiles ? 0 : timepointId );
		}

		@Override
		public RandomAccessibleInterval< T > getImage( final int timepointId, final ImgLoaderHint... hints )
		{
			try
			{
				return images.get( timepointId );
			}
			catch ( ExecutionException e )
			{
				throw new RuntimeException( e );
			}
		}

		@Override
		public T getImageType()
		{
			return type.get();
		}

		@Override
		public RandomAccessibleInterval< FloatType > getFloatImage( int timepointId, boolean normalize,
				ImgLoaderHint... hints )
		{
			if ( normalize )
				return ImgLib2Tools.normalizeVirtualRAI( getImage( timepointId, hints ) );
			else
				return ImgLib2Tools.convertVirtualRAI( getImage( timepointId, hints ) );
		}

		@Override
		public Dimensions getImageSize( int timepointId )
		{
			// NB: in all current uses we should have size information in the sd
			return getViewSetup( timepointId ).getSize();
		}

		@Override
		public VoxelDimensions getVoxelSize( int timepointId )
		{
			// NB: in all current uses we should have size information in the sd
			return getViewSetup( timepointId ).getVoxelSize();
		}

		private BasicViewSetup getViewSetup( int timepointId )
		{
			BasicViewDescription< ? > vd = sd.getViewDescriptions().get( new ViewId( timepointId, setupId ) );
			return vd.getViewSetup();
		}
	}

	private static BasicViewDescription< ? > getAnyPresentViewDescriptionForViewSetup( AbstractSequenceDescription< ?, ?, ? > sd, int viewSetupId )
	{
		for ( final ViewId vid : sd.getViewDescriptions().keySet() )
			if ( vid.getViewSetupId() == viewSetupId )
				if ( !sd.getMissingViews().getMissingViews().contains( vid ) )
					return sd.getViewDescriptions().get( vid );

		return null;
	}

	private static < T > Supplier< T > lazyInit( final Supplier< T > supplier )
	{
		return new Supplier< T >()
		{
			T value = null;

			@Override
			public synchronized T get()
			{
				if ( value == null )
					value = supplier.get();
				return value;
			}
		};
	}

	/**
	 * copy src to dest
	 *
	 * @deprecated Use {@link RealTypeConverters#copyFromTo(RandomAccessible, RandomAccessibleInterval)}.
	 *
	 * @param src
	 * 		source, will not be modified
	 * @param dest
	 * 		destiantion, will be modified
	 * @param <T>
	 * 		pixel type source
	 * @param <S>
	 * 		pixel type destination
	 */
	@Deprecated
	public static < T extends RealType< T >, S extends RealType< S > > void copy( RandomAccessible< T > src, RandomAccessibleInterval< S > dest )
	{
		RealTypeConverters.copyFromTo( src, dest );
	}
}
