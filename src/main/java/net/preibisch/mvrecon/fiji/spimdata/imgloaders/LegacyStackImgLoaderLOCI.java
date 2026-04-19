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
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;

import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.formats.ChannelSeparator;
import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.meta.IMetadata;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.services.OMEXMLService;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.StackListLOCI;
import ome.units.quantity.Length;
import util.ImgLib2Tools;

public class LegacyStackImgLoaderLOCI extends LegacyStackImgLoader
{
	public LegacyStackImgLoaderLOCI(
			final File path, final String fileNamePattern,
			final int layoutTP, final int layoutChannels, final int layoutIllum, final int layoutAngles, final int layoutTiles,
			final AbstractSequenceDescription< ?, ?, ? > sequenceDescription )
	{
		super( path, fileNamePattern, layoutTP, layoutChannels, layoutIllum, layoutAngles, layoutTiles, sequenceDescription );
	}

	/**
	 * Get {@link FloatType} image normalized to the range [0,1].
	 *
	 * @param view
	 *            timepoint and setup for which to retrieve the image.
	 * @param normalize
	 * 			  if the image should be normalized to [0,1] or not
	 * @return {@link FloatType} image normalized to range [0,1]
	 */
	@Override
	public RandomAccessibleInterval< FloatType > getFloatImage( final ViewId view, final boolean normalize )
	{
		if ( normalize )
			return ImgLib2Tools.normalizeVirtualRAI( getImage( view ) );
		else
			return ImgLib2Tools.convertVirtualRAI( getImage( view ) );
	}

	/**
	 * Get {@link UnsignedShortType} un-normalized image.
	 *
	 * @param view
	 *            timepoint and setup for which to retrieve the image.
	 * @return {@link UnsignedShortType} image.
	 */
	@Override
	public RandomAccessibleInterval< UnsignedShortType > getImage( final ViewId view )
	{
		final File file = getFile( view );

		if ( file == null )
			throw new RuntimeException( "Could not find file '" + file + "'." );

		try
		{
			final CalibratedImg< UnsignedShortType > img = openLOCI( file, new UnsignedShortType(), view );

			if ( img == null )
				throw new RuntimeException( "Could not load '" + file + "'" );

			// update the MetaDataCache of the AbstractImgLoader
			// this does not update the XML ViewSetup but has to be called explicitly before saving
			updateMetaDataCache( view, (int)img.getImg().dimension( 0 ), (int)img.getImg().dimension( 1 ), (int)img.getImg().dimension( 2 ),
					img.getCalX(), img.getCalY(), img.getCalZ() );

			return img.getImg();
		}
		catch ( Exception e )
		{
			e.printStackTrace();
			throw new RuntimeException( "Could not load '" + file + "': " + e );
		}
	}

	protected < T extends RealType< T > & NativeType< T > > CalibratedImg< T > openLOCI( final File path, final T type, final ViewId view ) throws Exception
	{
		BasicViewDescription< ? > viewDescription = sequenceDescription.getViewDescriptions().get( view );

		// read many 2d-images if it is a directory
		if ( path.isDirectory() )
		{
			final String[] files = path.list( new FilenameFilter()
			{
				@Override
				public boolean accept( final File dir, final String name)
				{
					final File newFile = new File( dir, name );

					// ignore directories and hidden files
					if ( newFile.isHidden() || newFile.isDirectory() )
						return false;
					else
						return true;
				}
			});
			Arrays.sort( files );
			final int depth = files.length;

			// get size of first image
			final Opener io = new Opener();
			ImagePlus imp2d = io.openImage( path.getAbsolutePath() + File.separator + files[ 0 ] );

			if ( imp2d.getStack().getSize() > 1 )
			{
				IOFunctions.println( "This is not a two-dimensional file: '" + path + "'" );
				imp2d.close();
				return null;
			}

			final Img< T > output = instantiateImg( new long[] { imp2d.getWidth(), imp2d.getHeight(), depth }, type );

			if ( output == null )
				throw new RuntimeException( "Could not instantiate " + getImgFactory().getClass().getSimpleName() + " for '" + path + "', most likely out of memory." );

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Opening '" + path + "' [" + imp2d.getWidth() + "x" + imp2d.getHeight() + "x" + depth + " type=" +
					imp2d.getProcessor().getClass().getSimpleName() + " image=" + output.getClass().getSimpleName() + "<" + type.getClass().getSimpleName() + ">]" );

			for ( int z = 0; z < depth; ++z )
			{
				imp2d = io.openImage( path.getAbsolutePath() + File.separator + files[ z ] );
				final ImageProcessor ip = imp2d.getProcessor();

				final Cursor< T > cursor = Views.iterable( Views.hyperSlice( output, 2, z ) ).localizingCursor();

				while ( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.get().setReal( ip.getPixelValue( cursor.getIntPosition( 0 ), cursor.getIntPosition( 1 ) ) );
				}
			}
			return new CalibratedImg<T>( output );
		}

		final IFormatReader r = new ChannelSeparator();

		if ( !createOMEXMLMetadata( r ) )
		{
			try
			{
				r.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			return null;
		}

		final String id = path.getAbsolutePath();

		r.setId( id );

		final boolean isLittleEndian = r.isLittleEndian();
		final int width = r.getSizeX();
		final int height = r.getSizeY();
		final int depth = r.getSizeZ();
		int timepoints = r.getSizeT();
		int channels = r.getSizeC();
		final int tiles = r.getSeriesCount();
		final int pixelType = r.getPixelType();
		final int bytesPerPixel = FormatTools.getBytesPerPixel( pixelType );
		final String pixelTypeString = FormatTools.getPixelTypeString( pixelType );

		final MetadataRetrieve retrieve = (MetadataRetrieve)r.getMetadataStore();

		double calX, calY, calZ;

		try
		{
			float cal = retrieve.getPixelsPhysicalSizeX( 0 ).value().floatValue();
			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "StackListLOCI: Warning, calibration for dimension X seems corrupted, setting to 1." );
			}
			calX = cal;

			cal = retrieve.getPixelsPhysicalSizeY( 0 ).value().floatValue();
			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "StackListLOCI: Warning, calibration for dimension Y seems corrupted, setting to 1." );
			}
			calY = cal;

			cal = retrieve.getPixelsPhysicalSizeZ( 0 ).value().floatValue();
			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "StackListLOCI: Warning, calibration for dimension Z seems corrupted, setting to 1." );
			}
			calZ = cal;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Failed to read calibration, setting to 1x1x1um." );
			calX = calY = calZ = 1;
		}

		// which channel and timepoint and tile to load from this file
		int t = 0;
		int c = 0;
		int ti = 0;

		if ( layoutTP == 2 )
		{
			t = Integer.parseInt( viewDescription.getTimePoint().getName() );

			if ( t >= timepoints )
			{
				r.close();
				throw new RuntimeException( "File '" + path + "' has only timepoints [0 ... " + (timepoints-1) + "], but you want to open timepoint " + t + ". Stopping.");
			}
		}

		if ( layoutChannels == 2 )
		{
			c = Integer.parseInt( viewDescription.getViewSetup().getAttribute( Channel.class ).getName() );

			if ( c >= channels )
			{
				r.close();
				throw new RuntimeException( "File '" + path + "' has only channels [0 ... " + (channels-1) + "], but you want to open channel " + c + ". Stopping.");
			}
		}
		
		if ( layoutTiles == 2 )
		{
			ti = Integer.parseInt( viewDescription.getViewSetup().getAttribute( Tile.class ).getName() );

			if ( ti >= tiles )
			{
				r.close();
				throw new RuntimeException( "File '" + path + "' has only tiles [0 ... " + (tiles-1) + "], but you want to open tile " + ti + ". Stopping.");
			}
		}

		if (!(pixelType == FormatTools.UINT8 || pixelType == FormatTools.UINT16 || pixelType == FormatTools.UINT32 || pixelType == FormatTools.FLOAT))
		{
			IOFunctions.println( "StackImgLoaderLOCI.openLOCI(): PixelType " + pixelTypeString + " not supported by " +
					type.getClass().getSimpleName() + ", returning. ");

			r.close();

			return null;
		}

		final Img< T > img;

		img = instantiateImg( new long[] { width, height, depth }, type );

		if ( img == null )
		{
			r.close();
			throw new RuntimeException( "Could not instantiate " + getImgFactory().getClass().getSimpleName() + " for '" + path + "', most likely out of memory." );
		}
		else
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Opening '" + path + "' [" + width + "x" + height + "x" + depth +
					" ch=" + c + " tp=" + t + " tile=" + ti + " type=" + pixelTypeString + " image=" + img.getClass().getSimpleName() + "<" + type.getClass().getSimpleName() + ">]" );

		final byte[] b = new byte[width * height * bytesPerPixel];

		final int planeX = 0;
		final int planeY = 1;

		// Tiles are series in the File, move to the corresponding series
		if (layoutTiles == 2)
			r.setSeries( ti );
		
		for ( int z = 0; z < depth; ++z )
		{
			IJ.showProgress( (double)z / (double)depth );

			final Cursor< T > cursor = Views.iterable( Views.hyperSlice( img, 2, z ) ).localizingCursor();

			r.openBytes( r.getIndex( z, c, t ), b );

			if ( pixelType == FormatTools.UINT8 )
			{
				while( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.get().setReal( b[ cursor.getIntPosition( planeX )+ cursor.getIntPosition( planeY )*width ] & 0xff );
				}
			}
			else if ( pixelType == FormatTools.UINT16 )
			{
				while( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.get().setReal( getShortValueInt( b, ( cursor.getIntPosition( planeX )+ cursor.getIntPosition( planeY )*width ) * 2, isLittleEndian ) );
				}
			}
			else if ( pixelType == FormatTools.INT16 )
			{
				while( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.get().setReal( getShortValue( b, ( cursor.getIntPosition( planeX )+ cursor.getIntPosition( planeY )*width ) * 2, isLittleEndian ) );
				}
			}
			else if ( pixelType == FormatTools.UINT32 )
			{
				//TODO: Untested
				while( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.get().setReal( getIntValue( b, ( cursor.getIntPosition( planeX )+ cursor.getIntPosition( planeY )*width )*4, isLittleEndian ) );
				}
			}
			else if ( pixelType == FormatTools.FLOAT )
			{
				while( cursor.hasNext() )
				{
					cursor.fwd();
					cursor.get().setReal( getFloatValue( b, ( cursor.getIntPosition( planeX )+ cursor.getIntPosition( planeY )*width )*4, isLittleEndian ) );
				}
			}
		}

		r.close();

		IJ.showProgress( 1 );

		return new CalibratedImg<T>( img, calX, calY, calZ );
	}

	public static final float getFloatValue( final byte[] b, final int i, final boolean isLittleEndian )
	{
		if ( isLittleEndian )
			return Float.intBitsToFloat( ((b[i+3] & 0xff) << 24)  + ((b[i+2] & 0xff) << 16)  +  ((b[i+1] & 0xff) << 8)  + (b[i] & 0xff) );
		else
			return Float.intBitsToFloat( ((b[i] & 0xff) << 24)  + ((b[i+1] & 0xff) << 16)  +  ((b[i+2] & 0xff) << 8)  + (b[i+3] & 0xff) );
	}

	public static final int getIntValue( final byte[] b, final int i, final boolean isLittleEndian )
	{
		if ( isLittleEndian )
			return ( ((b[i+3] & 0xff) << 24)  + ((b[i+2] & 0xff) << 16)  +  ((b[i+1] & 0xff) << 8)  + (b[i] & 0xff) );
		else
			return ( ((b[i] & 0xff) << 24)  + ((b[i+1] & 0xff) << 16)  +  ((b[i+2] & 0xff) << 8)  + (b[i+3] & 0xff) );
	}

	public static final short getShortValue( final byte[] b, final int i, final boolean isLittleEndian )
	{
		return (short)getShortValueInt( b, i, isLittleEndian );
	}

	public static final int getShortValueInt( final byte[] b, final int i, final boolean isLittleEndian )
	{
		if ( isLittleEndian )
			return ((((b[i+1] & 0xff) << 8)) + (b[i] & 0xff));
		else
			return ((((b[i] & 0xff) << 8)) + (b[i+1] & 0xff));
	}

	protected static String checkPath( String path )
	{
		if (path.length() > 1)
		{
			path = path.replace('\\', '/');
			if (!path.endsWith("/"))
				path = path + "/";
		}

		return path;
	}

	public static boolean createOMEXMLMetadata( final IFormatReader r )
	{
		try
		{
			final ServiceFactory serviceFactory = new ServiceFactory();
			final OMEXMLService service = serviceFactory.getInstance( OMEXMLService.class );
			final IMetadata omexmlMeta = service.createOMEXMLMetadata();
			r.setMetadataStore(omexmlMeta);
		}
		catch (final ServiceException e)
		{
			e.printStackTrace();
			return false;
		}
		catch (final DependencyException e)
		{
			e.printStackTrace();
			return false;
		}

		return true;
	}

	@Override
	protected void loadMetaData( final ViewId view )
	{
		final File file = getFile( view );

		final Calibration cal = loadMetaData( file );

		if ( cal != null )
		{
			// update the MetaDataCache of the AbstractImgLoader
			// this does not update the XML ViewSetup but has to be called explicitly before saving
			updateMetaDataCache( view, cal.getWidth(), cal.getHeight(), cal.getDepth(),
					cal.getCalX(), cal.getCalY(), cal.getCalZ() );
		}
	}

	
	public static double[] loadTileLocation( final File file, final int seriesOffset )
	{
		final IFormatReader r = new ChannelSeparator();

		if ( !LegacyStackImgLoaderLOCI.createOMEXMLMetadata( r ) )
		{
			try
			{
				r.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			return null;
		}
		

		try
		{
			r.setId( file.getAbsolutePath() );

			final MetadataRetrieve retrieve = (MetadataRetrieve)r.getMetadataStore();
			double[] loc = new double[3];
						
			Length f = retrieve.getPlanePositionX( seriesOffset, 0 );
			if ( f != null )
				loc[0] = - f.value().doubleValue();
			
			// TODO: y-axis is inverted in ND2-images? is there a way to find out if this is the case in any format?
			f = retrieve.getPlanePositionY( seriesOffset, 0 );
			if ( f != null )
				loc[1] = - f.value().doubleValue();
			
			f = retrieve.getPlanePositionZ( seriesOffset, 0 );
			if ( f != null )
				loc[2] = f.value().doubleValue();
			
			
			r.close();
			return loc;
			
		}
		catch ( Exception e)
		{
			IOFunctions.println( "Could not read metadata for file: '" + file.getAbsolutePath() + "'" );
			//e.printStackTrace();
			return null;
		}
	}
	
	public static Calibration loadMetaData( final File file )
	{
		final IFormatReader r = new ChannelSeparator();

		if ( !LegacyStackImgLoaderLOCI.createOMEXMLMetadata( r ) )
		{
			try
			{
				r.close();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
			return null;
		}
		

		try
		{
			r.setId( file.getAbsolutePath() );

			final MetadataRetrieve retrieve = (MetadataRetrieve)r.getMetadataStore();

			float cal = 0;

			Length f = retrieve.getPixelsPhysicalSizeX( 0 );
			if ( f != null )
				cal = f.value().floatValue();

			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "StackListLOCI: Warning, calibration for dimension X seems corrupted, setting to 1." );
			}
			final double calX = cal;

			f = retrieve.getPixelsPhysicalSizeY( 0 );
			if ( f != null )
				cal = f.value().floatValue();

			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "StackListLOCI: Warning, calibration for dimension Y seems corrupted, setting to 1." );
			}
			final double calY = cal;

			f = retrieve.getPixelsPhysicalSizeZ( 0 );
			if ( f != null )
				cal = f.value().floatValue();

			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "StackListLOCI: Warning, calibration for dimension Z seems corrupted, setting to 1." );
			}
			final double calZ = cal;

			IOFunctions.println( "Image stack size of first stack: " + r.getSizeX() + "x" + r.getSizeY() + "x" + r.getSizeZ() );

			final Calibration calibration = new Calibration( r.getSizeX(), r.getSizeY(), r.getSizeZ(), calX, calY, calZ );

			r.close();

			return calibration;
		}
		catch ( Exception e)
		{
			IOFunctions.println( "Could not open file: '" + file.getAbsolutePath() + "'" );
			e.printStackTrace();
			return null;
		}
	}

	@Override
	public String toString()
	{
		return new StackListLOCI().getTitle() + ", ImgFactory=" + getImgFactory().getClass().getSimpleName();
	}

}
