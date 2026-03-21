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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.io.Files;

import ij.IJ;
import loci.formats.AxisGuesser;
import loci.formats.FileStitcher;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.Memoizer;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapGettable;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapEntry;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.util.BioformatsReaderUtils;
import util.ImgLib2Tools;


public class LegacyFileMapImgLoaderLOCI extends AbstractImgFactoryImgLoader
{

	private Map< ViewId, FileMapEntry > fileMap;
	private AbstractSequenceDescription< ?, ?, ? > sd;
	private boolean allTimepointsInSingleFiles;
	private boolean zGrouped;
	private File tempDir;

	public LegacyFileMapImgLoaderLOCI(
			Map< ? extends ViewId, FileMapEntry > fileMap,
			final AbstractSequenceDescription<?, ?, ?> sequenceDescription)
	{
		this(fileMap, sequenceDescription, false);
	}

	public LegacyFileMapImgLoaderLOCI(
			Map< ? extends ViewId, FileMapEntry > fileMap,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription,
			final boolean zGrouped )
	{
		super();

		this.tempDir = Files.createTempDir();

		this.fileMap = new HashMap<>( fileMap );

		this.sd = sequenceDescription;

		allTimepointsInSingleFiles = true;
		this.zGrouped = zGrouped;

		// populate map file -> {time points}
		Map< File, Set< Integer > > tpsPerFile = new HashMap<>();
		for ( ViewId vId : fileMap.keySet() )
		{

			final File fileForVd = fileMap.get( vId ).file();
			if ( !tpsPerFile.containsKey( fileForVd ) )
				tpsPerFile.put( fileForVd, new HashSet<>() );

			tpsPerFile.get( fileForVd ).add( vId.getTimePointId() );

			// the current file has more than one time point
			if ( tpsPerFile.get( fileForVd ).size() > 1 )
			{
				allTimepointsInSingleFiles = false;
				break;
			}

		}

		System.out.println( allTimepointsInSingleFiles );
		
	}
	
	
	@Override
	public RandomAccessibleInterval< FloatType > getFloatImage(ViewId view, boolean normalize)
	{
		if ( normalize )
			return ImgLib2Tools.normalizeVirtualRAI( getImage( view ) );
		else
			return ImgLib2Tools.convertVirtualRAI( getImage( view ) );
	}

	
	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage(ViewId view)
	{
		// TODO should the Type here be fixed to UnsignedShortTyppe?
		try
		{
			final RandomAccessibleInterval< UnsignedShortType > img = openImg( new UnsignedShortType(), view );

			if ( img == null )
				throw new RuntimeException( "Could not load '" + fileMap.get( sd.getViewDescriptions( ).get( view ) ).file() + "' viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() );


			return img;
		}
		catch ( Exception e )
		{
			e.printStackTrace();
			throw new RuntimeException( "Could not load '" + fileMap.get( sd.getViewDescriptions( ).get( view ) ).file() + "' viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() + ": " + e );
		}
	}

	@Override
	protected void loadMetaData(ViewId view)
	{
		BasicViewDescription< ? > vd = sd.getViewDescriptions().get( view );

		// we already read view sizes and voxel dimensions when setting up sd
		// here, we pass them to the ImgLoaders metaDataCache, so that that knows the sizes as well		
		int d0 = (int) vd.getViewSetup().getSize().dimension( 0 );
		int d1 = (int) vd.getViewSetup().getSize().dimension( 1 );
		int d2 = (int) vd.getViewSetup().getSize().dimension( 2 );
		
		double vox0 = vd.getViewSetup().getVoxelSize().dimension( 0 );
		double vox1 = vd.getViewSetup().getVoxelSize().dimension( 1 );
		double vox2 = vd.getViewSetup().getVoxelSize().dimension( 2 );
		updateMetaDataCache( view, d0, d1, d2, vox0, vox1, vox2 );
	}

	protected < T extends RealType< T > & NativeType< T > > RandomAccessibleInterval< T > openImg( final T type, final ViewId view ) throws Exception
	{
		// load dimensions
		loadMetaData( view );

		final File fileForVId = fileMap.get( view ).file();

		// use a new ImageReader since we might be loading multi-threaded and BioFormats is not thread-save
		// use Memoizer to cache ReaderState for each File on disk
		// see: https://www-legacy.openmicroscopy.org/site/support/bio-formats5.1/developers/matlab-dev.html#reader-performance
		IFormatReader reader = null;
		if (zGrouped)
		{
			final FileStitcher fs = new FileStitcher(true);
			fs.setCanChangePattern( false );
			reader = new Memoizer( fs , Memoizer.DEFAULT_MINIMUM_ELAPSED, tempDir);
		}
		else
		{
			reader = new Memoizer( BioformatsReaderUtils.createImageReaderWithSetupHooks(), Memoizer.DEFAULT_MINIMUM_ELAPSED, tempDir );
		}

		reader.setId( fileForVId.getAbsolutePath() );
		if (zGrouped)
			( (FileStitcher) ( (Memoizer) reader).getReader() ).setAxisTypes( new int[] {AxisGuesser.Z_AXIS} );
		reader.setSeries( fileMap.get( view ).series() );

		final BasicViewDescription< ? > vd = sd.getViewDescriptions().get( view );
		final BasicViewSetup vs = vd.getViewSetup();
		final File file = fileMap.get( vd).file();

		final TimePoint t = vd.getTimePoint();
		final Angle a = getAngle( vd );
		final Channel c = getChannel( vd );
		final Illumination i = getIllumination( vd );
		final Tile tile = getTile( vd );

		// we assume the size to have been set beforehand
		final int[] dim;
		dim = new int[vs.getSize().numDimensions()];
		for ( int d = 0; d < vs.getSize().numDimensions(); ++d )
			dim[d] = (int) vs.getSize().dimension( d );

		final Img< T > img = getImgFactory().imgFactory( type ).create( dim );

		if ( img == null )
		{
			try { reader.close(); } catch (IOException e1) { e1.printStackTrace(); }
			throw new RuntimeException( "Could not instantiate " + getImgFactory().getClass().getSimpleName() + " for '" + file + "' viewId=" + view.getViewSetupId() + ", tpId=" + view.getTimePointId() + ", most likely out of memory." );
		}
		final boolean isLittleEndian = reader.isLittleEndian();
		final boolean isArray = ArrayImg.class.isInstance( img );
		final int pixelType = reader.getPixelType();
		final int width = dim[ 0 ];
		final int height = dim[ 1 ];
		final int depth = dim[ 2 ];
		final int numPx = width * height;

		final byte[] b = new byte[ numPx * reader.getBitsPerPixel() / 8 ];

		try
		{
			// we have already openend the file

			IOFunctions.println(
					new Date( System.currentTimeMillis() ) + ": Reading image data from '" + file.getName() + "' [" + dim[ 0 ] + "x" + dim[ 1 ] + "x" + dim[ 2 ] +
					" angle=" + a.getName() + " ch=" + c.getName() + " illum=" + i.getName() + " tp=" + t.getName() + " tile=" + tile.getName() + 
					" type=" + FormatTools.getPixelTypeString( reader.getPixelType()) +
					" img=" + img.getClass().getSimpleName() + "<" + type.getClass().getSimpleName() + ">]" );


			int ch = fileMap.get( vd ).channel();
			int tpNo = allTimepointsInSingleFiles ? 0 : t.getId();

			System.out.println( "allTimepointsInSingleFiles " + allTimepointsInSingleFiles );
			for ( int z = 0; z < depth; ++z )
			{
				IJ.showProgress( (double)z / (double)depth );

				final Cursor< T > cursor = Views.iterable( Views.hyperSlice( img, 2, z ) ).localizingCursor();

				// FIX for XYZ <-> XYT mixup in rare cases
				int actualTP = (!reader.isOrderCertain() && reader.getSizeZ() <= 1 && reader.getSizeT() > 1 ) ? z : tpNo;
				int actualZ = (!reader.isOrderCertain() && reader.getSizeZ() <= 1 && reader.getSizeT() > 1 ) ? tpNo : z;

				reader.openBytes( reader.getIndex( actualZ, ch, actualTP ), b );

				if ( pixelType == FormatTools.UINT8 )
				{
					if ( isArray )
						LegacyLightSheetZ1ImgLoader.readBytesArray( b, cursor, numPx );
					else
						LegacyLightSheetZ1ImgLoader.readBytes( b, cursor, width );
				}
				else if ( pixelType == FormatTools.UINT16 )
				{
					if ( isArray )
						LegacyLightSheetZ1ImgLoader.readUnsignedShortsArray( b, cursor, numPx, isLittleEndian );
					else
						LegacyLightSheetZ1ImgLoader.readUnsignedShorts( b, cursor, width, isLittleEndian );
				}
				else if ( pixelType == FormatTools.INT16 )
				{
					if ( isArray )
						LegacyLightSheetZ1ImgLoader.readSignedShortsArray( b, cursor, numPx, isLittleEndian );
					else
						LegacyLightSheetZ1ImgLoader.readSignedShorts( b, cursor, width, isLittleEndian );
				}
				else if ( pixelType == FormatTools.UINT32 )
				{
					//TODO: Untested
					if ( isArray )
						LegacyLightSheetZ1ImgLoader.readUnsignedIntsArray( b, cursor, numPx, isLittleEndian );
					else
						LegacyLightSheetZ1ImgLoader.readUnsignedInts( b, cursor, width, isLittleEndian );
				}
				else if ( pixelType == FormatTools.FLOAT )
				{
					if ( isArray )
						LegacyLightSheetZ1ImgLoader.readFloatsArray( b, cursor, numPx, isLittleEndian );
					else
						LegacyLightSheetZ1ImgLoader.readFloats( b, cursor, width, isLittleEndian );
				}
			}

			IJ.showProgress( 1 );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "File '" + file.getAbsolutePath() + "' could not be opened: " + e );
			IOFunctions.println( "Stopping" );

			e.printStackTrace();
			try { reader.close(); } catch (IOException e1) { e1.printStackTrace(); }
			return null;
		}

		try { reader.close(); } catch (IOException e1) { e1.printStackTrace(); }
		return img;
	}
	
	protected static Angle getAngle( final AbstractSequenceDescription< ?, ?, ? > seqDesc, final ViewId view )
	{
		return getAngle( seqDesc.getViewDescriptions().get( view ) );
	}

	protected static Angle getAngle( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Angle angle = vs.getAttribute( Angle.class );

		if ( angle == null )
			throw new RuntimeException( "This XML does not have the 'Angle' attribute for their ViewSetup. Cannot continue." );

		return angle;
	}

	protected static Channel getChannel( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Channel channel = vs.getAttribute( Channel.class );

		if ( channel == null )
			throw new RuntimeException( "This XML does not have the 'Channel' attribute for their ViewSetup. Cannot continue." );

		return channel;
	}

	protected static Illumination getIllumination( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Illumination illumination = vs.getAttribute( Illumination.class );

		if ( illumination == null )
			throw new RuntimeException( "This XML does not have the 'Illumination' attribute for their ViewSetup. Cannot continue." );

		return illumination;
	}
	
	protected static Tile getTile( final BasicViewDescription< ? > vd )
	{
		final BasicViewSetup vs = vd.getViewSetup();
		final Tile tile = vs.getAttribute( Tile.class );

		if ( tile == null )
			throw new RuntimeException( "This XML does not have the 'Illumination' attribute for their ViewSetup. Cannot continue." );

		return tile;
	}


	public Map< ViewId, FileMapEntry > getFileMap()
	{
		return fileMap;
	}

	/**
	 * for every file in the dataset file list check if the z-size is equal for every series.
	 * @param spimData the dataset
	 * @param loader the associated loader
	 * @return true if z-sizes are equal in every file, false if they differ inside any file
	 */
	public static boolean isZSizeEqualInEveryFile(final SpimData2 spimData, final FileMapGettable loader)
	{
		// for every file: collect a vd for every series
		final Map< File, Map< Integer, ViewId > > invertedMap = new HashMap<>();
		loader.getFileMap().entrySet().forEach( e -> {

			final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( e.getKey() );
			// ignore missing views
			if (!vd.isPresent())
				return;

			final File invKey = e.getValue().file();
			if (!invertedMap.containsKey( invKey ))
				invertedMap.put( invKey, new HashMap<>() );

			final Integer series = e.getValue().series();

			invertedMap.get( invKey ).put( series, vd );
		});

		// filter all files that do no have equal z-size for all series
		final List< Entry< File, Map< Integer, ViewId > > > mapFiltered = invertedMap.entrySet().stream().filter( e -> {
			final Map< Integer, ViewId > seriesMap = e.getValue();

			Long zSize = null;
			for (Integer series : seriesMap.keySet())
			{
				final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( seriesMap.get( series ) );
				if (zSize == null)
					zSize = vd.getViewSetup().getSize().dimension( 2 );
				if (zSize != vd.getViewSetup().getSize().dimension( 2 ))
					return false;
			}
			return true;
		}).collect( Collectors.toList() );

		// we did not filter out any file -> all files have equally sized series
		return mapFiltered.size() == invertedMap.size();
	}

	/**
	 * check if views in spimData contain zero-valued planes at the end of the z-axis.
	 * re-set ViewSetup dimensions to ignore the zero-volume
	 * (use this to fix "the BioFormats bug")
	 * @param spimData the SpimData to correct
	 * @param loader the imgLoader to use
	 * @param filesArePatterns whether file names contain patterns and should be grouped when opening
	 * @param <T> pixel type
	 * @param <IL> ImgLoader type
	 */
	public static <T extends RealType< T > & NativeType< T >, IL extends ImgLoader & FileMapGettable > void checkAndRemoveZeroVolume(final SpimData2 spimData, final IL loader, boolean filesArePatterns)
	{
		// collect vds for every (file, series) combo
		final Map< Pair< File, Integer >, List< BasicViewDescription< ViewSetup > > > invertedMap = new HashMap<>();
		loader.getFileMap().entrySet().forEach( e -> {

			final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( e.getKey() );

			// ignore if we cannot cast to ViewSetup
			if (!ViewSetup.class.isInstance( vd.getViewSetup() ) )
				return;

			// ignore missing views
			if (!vd.isPresent())
				return;

			final Pair< File, Integer > invKey = new ValuePair<>( e.getValue().file(), e.getValue().series() );
			if (!invertedMap.containsKey( invKey ))
				invertedMap.put( invKey, new ArrayList<>() );

			invertedMap.get( invKey ).add( vd );
		});

		// use an FileStitcher if we have grouped files (single z-planes per file)
		final IFormatReader reader = filesArePatterns ? new FileStitcher( true ) : BioformatsReaderUtils.createImageReaderWithSetupHooks();
		if (filesArePatterns)
			( (FileStitcher) reader ).setCanChangePattern( false );

		// collect corrected dimensions
		final Map< Pair< File, Integer >, Dimensions > dimensionMap = new HashMap<>();
		invertedMap.entrySet().forEach( e -> {

			IOFunctions.println(new Date(System.currentTimeMillis()) +  ": Checking z size in file: " + e.getKey().getA().getAbsolutePath() + ", series " + e.getKey().getB() );

			final BasicViewDescription< ViewSetup > firstVD = e.getValue().iterator().next();
			//final RandomAccessibleInterval< FloatType > img = loader.getSetupImgLoader( firstVD.getViewSetupId() ).getFloatImage( firstVD.getTimePointId(), false );
			final Dimensions sizeOld = firstVD.getViewSetup().getSize();

			if (reader.getCurrentFile() == null || !reader.getCurrentFile().equals( e.getKey().getA().getAbsolutePath() ))
			{
				try 
				{ 
					reader.setId( e.getKey().getA().getAbsolutePath() );

					// make sure grouped axis is interpreted as z
					if (filesArePatterns)
						( (FileStitcher)  reader).setAxisTypes( new int[] {AxisGuesser.Z_AXIS} );
				}
				catch ( FormatException | IOException e1 ) { e1.printStackTrace(); }
			}
			reader.setSeries( e.getKey().getB() );

			long max = getMaxNonzero( reader );

			IOFunctions.println(new Date(System.currentTimeMillis()) + ": Corrected size is " + (max + 1) + " (was " + sizeOld.dimension( 2 ) + ")");

			dimensionMap.put( e.getKey(), new FinalDimensions( new long[] {sizeOld.dimension( 0 ), sizeOld.dimension( 1 ), max + 1} ) );
		});

		// correct dimensions for all views
		invertedMap.entrySet().forEach( e -> {
			final Dimensions newDims = dimensionMap.get( e.getKey() );
			e.getValue().forEach( vd -> {
				vd.getViewSetup().setSize( newDims );
			});
		});

		try
		{
			reader.close();
		}
		catch ( IOException e1 )
		{
			e1.printStackTrace();
		}

	}
	
	public static <T extends RealType< T > & NativeType< T >, IL extends ImgLoader & FileMapGettable > void checkAndRemoveZeroVolume(final SpimData2 spimData, final IL loader)
	{
		checkAndRemoveZeroVolume( spimData, loader, false );
	}

	/**
	 * get the highest index in dimension dim where a hyperslice of img in that dimension contains nonzero values.
	 * NB: the result is in local image coordinates (i.e. we zero-min the input)
	 * @param img the input image
	 * @param dim the dimension along which to check
	 * @param <T> pixel type
	 * @return the highest index with nonzero pixels
	 */
	public static <T extends RealType< T >> long getMaxNonzero(RandomAccessibleInterval< T > img, int dim)
	{
		final RandomAccessibleInterval< T > imgLocal = Views.isZeroMin( img ) ? img :Views.zeroMin( img );
		long i = imgLocal.dimension( dim ) - 1;
		for (; i >= 0; i--)
		{
			if (isNonezero( Views.hyperSlice( imgLocal, dim, i ) ))
				return i;
		}
		return 0l;
	}

	public static long getMaxNonzero(IFormatReader reader)
	{
		final int siz = reader.getBitsPerPixel() / 8 * reader.getRGBChannelCount() * reader.getSizeX()
				* reader.getSizeY();
		final byte[] buffer = new byte[siz];

		final int nC = reader.getSizeC() == reader.getRGBChannelCount() ? 1 : reader.getSizeC();
		final int nT = reader.getSizeT();

		int z = reader.getSizeZ() - 1;
		for (;z>=0;z--)
			for (int c = 0; c < nC; c++ )
				for (int t = 0; t < nT; t++)
				{
					try { reader.openBytes( reader.getIndex( z, c, t ), buffer ); }
					catch ( FormatException | IOException e ) { e.printStackTrace(); }
					if (isNonzero( buffer ))
						return z;
				}
		return 0l;
	}

	public static boolean isNonzero(byte[] buf)
	{
		for (byte b : buf)
			if (b != (byte)0)
				return true;
		return false;
	}

	/**
	 * check whether any pixel in slice is nonzero.
	 * NB: we do a raw floating point comparison here, so we might get false results if the image was
	 * operated on in any way due to numerical inaccuracies.
	 * @param slice the input image
	 * @param <T> pixel type
	 * @return true if slice has nonzero entries else false
	 */
	public static <T extends RealType< T >> boolean isNonezero(IterableInterval< T > slice)
	{
		for (T val : slice)
			if (val.getRealDouble() != 0.0)
				return true;
		return false;
	}

}
