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
package net.preibisch.mvrecon.headless.definedataset;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import loci.formats.FormatTools;
import loci.formats.IFormatReader;
import loci.formats.Modulo;
import loci.formats.meta.MetadataRetrieve;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.Cursor;
import net.imglib2.FinalDimensions;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.StackList;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.LegacyLightSheetZ1ImgLoader;

import ome.units.quantity.Length;

public class LightSheetZ1MetaData
{
	private String objective = "";
	private String calUnit = "um";
	private int rotationAxis = -1;
	private String channels[];
	private String angles[];
	private String illuminations[];
	private String tiles[];
	private int numT = -1;
	private int numI = -1;
	private double calX, calY, calZ, lightsheetThickness = -1;
	private String[] files;
	private HashMap< Integer, int[] > imageSizes;
	private int pixelType = -1;
	private int bytesPerPixel = -1; 
	private String pixelTypeString = "";
	private boolean isLittleEndian;
	private IFormatReader r = null;
	private boolean applyAxis = true;
	
	private List<double[]> tileLocations;
	private Map<Integer, Integer> anglesMap;

	public void setRotationAxis( final int rotAxis ) { this.rotationAxis = rotAxis; }
	public void setCalX( final double calX ) { this.calX = calX; }
	public void setCalY( final double calY ) { this.calY = calY; }
	public void setCalZ( final double calZ ) { this.calZ = calZ; }
	public void setCalUnit( final String calUnit ) { this.calUnit = calUnit; }

	public Map< Integer, Integer > getAngleMap() { return anglesMap; }
	public List< double[] > tileLocations() { return tileLocations; }
	public int numChannels() { return channels.length; }
	public int numAngles() { return angles.length; }
	public int numTiles() {return tiles.length;}
	public int numIlluminations() { return numI; }
	public int numTimepoints() { return numT; }
	public String objective() { return objective; }
	public int rotationAxis() { return rotationAxis; }
	public double calX() { return calX; }
	public double calY() { return calY; }
	public double calZ() { return calZ; }
	public String[] files() { return files; }
	public String[] channels() { return channels; }
	public String[] angles() { return angles; }
	public String[] tiles() { return tiles; }
	public String[] illuminations() { return illuminations; }
	public HashMap< Integer, int[] > imageSizes() { return imageSizes; }
	public String calUnit() { return calUnit; }
	public double lightsheetThickness() { return lightsheetThickness; }
	public int pixelType() { return pixelType; }
	public int bytesPerPixel() { return bytesPerPixel; }
	public String pixelTypeString() { return pixelTypeString; }
	public boolean isLittleEndian() { return isLittleEndian; }
	public IFormatReader getReader() { return r; }

	public String rotationAxisName()
	{
		if ( rotationAxis == 0 )
			return "X";
		else if ( rotationAxis == 1 )
			return "Y";
		else if ( rotationAxis == 2 )
			return "Z";
		else
			return "Unknown";
	}

	public boolean allImageSizesEqual()
	{
		int[] size = null;
		boolean allEqual = true;
		
		for ( final int[] sizes : imageSizes().values() )
		{
			if ( size == null )
				size = sizes.clone();
			else
			{
				for ( int d = 0; d < size.length; ++d )
					if ( size[ d ] != sizes[ d ] )
						allEqual = false;
			}
		}

		return allEqual;
	}

	public boolean applyAxis() { return this.applyAxis; }
	public void setApplyAxis( final boolean apply ) { this.applyAxis = apply; }

	public boolean loadMetaData( final File cziFile )
	{
		return loadMetaData( cziFile, false );
	}

	public boolean loadMetaData( final File cziFile, final boolean keepFileOpen )
	{
		final IFormatReader r = LegacyLightSheetZ1ImgLoader.instantiateImageReader();

		if ( !LegacyLightSheetZ1ImgLoader.createOMEXMLMetadata( r ) )
		{
			try { r.close(); } catch (IOException e) { e.printStackTrace(); }
			IOFunctions.println( "Creating MetaDataStore failed. Stopping" );
			return false;
		}

		try
		{
			r.setId( cziFile.getAbsolutePath() );

			this.pixelType = r.getPixelType();
			this.bytesPerPixel = FormatTools.getBytesPerPixel( pixelType ); 
			this.pixelTypeString = FormatTools.getPixelTypeString( pixelType );
			this.isLittleEndian = r.isLittleEndian();

			if ( !( pixelType == FormatTools.UINT8 || pixelType == FormatTools.UINT16 || pixelType == FormatTools.UINT32 || pixelType == FormatTools.FLOAT ) )
			{
				IOFunctions.println(
						"LightSheetZ1MetaData.loadMetaData(): PixelType " + pixelTypeString +
						" not supported yet. Please send me an email about this: stephan.preibisch@gmx.de - stopping." );

				r.close();

				return false;
			}

			printMetaData( r );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "File '" + cziFile.getAbsolutePath() + "' could not be opened: " + e );
			IOFunctions.println( "Stopping" );

			e.printStackTrace();
			try { r.close(); } catch (IOException e1) { e1.printStackTrace(); }
			return false;
		}

		final Hashtable< String, Object > metaData = r.getGlobalMetadata();
		
		// number each angle and tile has its own series
		final int numAorT = r.getSeriesCount();
		final int numTiles = numAorT;

		// make sure every angle has the same amount of timepoints, channels, illuminations
		this.numT = -1;
		this.numI = -1;
		int numC = -1;
		
		// also collect the image sizes for each angle
		this.imageSizes = new HashMap< Integer, int[] >();
		
		this.anglesMap = new HashMap<>();
		this.tileLocations = new ArrayList<>();
		
		List<Integer> anglesList = new ArrayList<>();

		final int numDigits = Integer.toString( numAorT ).length();

		try
		{
			boolean allAnglesNegative = true;

			// for each angleXtile
			for ( int at = 0; at < numAorT; ++at )
			{
				r.setSeries( at );

				final int w = r.getSizeX();
				final int h = r.getSizeY();

				Object tmp = metaData.get( "Information|Image|V|View|Offset #" + ( at+1 ) );
				if (tmp == null)
					tmp = r.getMetadataValue("Information|Image|V|View|Offset #" + StackList.leadingZeros( Integer.toString( at + 1 ), numDigits ) );

				int angleT = (tmp != null) ? (int)Math.round( Double.parseDouble( tmp.toString() ) ) : 0;
				if ( !anglesList.contains( angleT ) )
					anglesList.add( angleT );

				anglesMap.put( at, anglesList.indexOf( angleT ) );

				allAnglesNegative &= angleT < 0;

				IOFunctions.println( "Querying information for angle/tile #" + at );

				// try 4 different combinations of metadata query to get size in z (there is a bug in LOCI returning the maximum size for all angle/tile)
				double dimZ = getDouble( metaData, "Information|Image|V|View|SizeZ #" + StackList.leadingZeros( Integer.toString( at+1 ), numDigits ) );

				if ( Double.isNaN( dimZ ) )
					dimZ = getDouble( metaData, "Information|Image|V|View|SizeZ #" + Integer.toString( at+1 ) );

				if ( Double.isNaN( dimZ ) )
					dimZ = getDouble( metaData, "SizeZ|View|V|Image|Information #" + StackList.leadingZeros( Integer.toString( at+1 ), numDigits ) );

				if ( Double.isNaN( dimZ ) )
					dimZ = getDouble( metaData, "SizeZ|View|V|Image|Information #" + Integer.toString( at+1 ) );

				if ( numAorT == 1 && Double.isNaN( dimZ ) )
					dimZ = getDouble( metaData, "Information|Image|SizeZ #1" );

				if ( Double.isNaN( dimZ ) )
					throw new RuntimeException( "Could not read stack size for angle " + at + ", stopping." );

				final int d = (int)Math.round( dimZ );

				imageSizes.put( at, new int[]{ w, h, d } );

				// get the number of timepoints for the first at, otherwise check that it is still the same
				if ( numT >= 0 && numT != r.getSizeT() )
				{
					IOFunctions.println( "Number of timepoints inconsistent across angles. Stopping." );
					r.close();
					return false;
				}
				else
				{
					numT = r.getSizeT();
				}
				
				// Illuminations are contained within the channel count; to
				// find the number of illuminations for the current angle:
				Modulo moduloC = r.getModuloC();

				// TODO: channels & illuminations are mixed up somehow

				// get the number of illuminations for the first at, otherwise check that it is still the same
				if ( numI >= 0 && numI != moduloC.length() )
				{
					IOFunctions.println( "Number of illumination directions inconsistent across angles. Stopping." );
					r.close();
					return false;
				}
				else
				{
					numI = moduloC.length();
				}

				// get the number of channels for the first at, otherwise check that it is still the same
				if ( numC >= 0 && numC != r.getSizeC() / moduloC.length() )
				{
					IOFunctions.println( "Number of channels directions inconsistent across angles. Stopping." );
					r.close();
					return false;
				}
				else
				{
					numC = r.getSizeC() / moduloC.length();
				}
			}

			int numA = anglesList.size();
			this.angles = new String[ numA ];
			
			for ( int a = 0; a < anglesList.size(); ++a )
			{
				if ( allAnglesNegative )
					angles[ a ] = String.valueOf( -anglesList.get( a ) );
				else
					angles[ a ] = String.valueOf( anglesList.get( a ) );
			}
		}
		catch ( Exception e )
		{
			IOFunctions.println( "An error occured parsing the main meta data: " + e + ". Stopping." );
			e.printStackTrace();
			printMetaData( r );
			try { r.close(); } catch (IOException e1) { e1.printStackTrace(); }
			return false;
		}
		
		
		//
		// query non-essential details
		//
		this.channels = new String[ numC ];
		this.tiles = new String[ numTiles ];
		this.illuminations = new String[ numI ];
		this.files = r.getSeriesUsedFiles();

		// only one debug ouput
		boolean printMetadata = false;

		for ( int i = 0; i < numI; ++i )
			illuminations[ i ] = String.valueOf( i );

		Object tmp;

		try
		{
			tmp = metaData.get( "Experiment|AcquisitionBlock|AcquisitionModeSetup|Objective #1" );
			objective = (tmp != null) ? tmp.toString() : "Unknown Objective";
		}
		catch ( Exception e )
		{
			IOFunctions.println( "An error occured parsing the objective used: " + e + "\n. Proceeding." );
			objective = "Unknown Objective";
			printMetaData( r );
		}

		try
		{
			for ( int c = 0; c < numC; ++c )
			{
				tmp = metaData.get( "Information|Image|Channel|IlluminationWavelength|SinglePeak #" + ( c+1 ) );
				//tmp = metaData.get( "Information|Image|Channel|Wavelength #" + ( c+1 ) );
				//tmp = metaData.get( "Experiment|AcquisitionBlock|MultiTrackSetup|TrackSetup|Attenuator|Laser #" + ( c+1 ) );

				channels[ c ] = (tmp != null) ? tmp.toString() : String.valueOf( c );

				if ( channels[ c ].contains( "-" ) )
					channels[ c ] = channels[ c ].substring( 0, channels[ c ].indexOf( "-" ) );

				if ( channels[ c ].toLowerCase().startsWith( "laser" ) )
					channels[ c ] = channels[ c ].substring( channels[ c ].toLowerCase().indexOf( "laser" ) + 5, channels[ c ].length() );

				if ( channels[ c ].toLowerCase().startsWith( "laser " ) )
					channels[ c ] = channels[ c ].substring( channels[ c ].toLowerCase().indexOf( "laser " ) + 6, channels[ c ].length() );

				channels[ c ] = channels[ c ].trim();

				if ( channels[ c ].length() == 0 )
					channels[ c ] = String.valueOf( c );

				try
				{
					channels[ c ] = Integer.toString( (int)Double.parseDouble( channels[ c ] ) );
				}
				catch ( NumberFormatException e ) {}
			}
		}
		catch ( Exception e )
		{
			IOFunctions.println( "An error occured parsing the channels: " + e + "\n. Proceeding." );
			for ( int c = 0; c < numC; ++c )
				channels[ c ] = String.valueOf( c );
			printMetadata = true;
		}

		// get the tile locations (every angleXtile has a local location)
		try
		{
			final double[] pos = new double[3];

			for ( int at = 0; at < numAorT; ++at )
			{
				tmp = metaData.get( "Information|Image|V|View|PositionX #" + StackList.leadingZeros( Integer.toString( at+1 ), numDigits ) );
				if ( tmp == null )
					tmp = metaData.get( "Information|Image|V|View|PositionX #" + ( at+1 ) );
				pos[ 0 ] = (tmp != null) ?  Double.parseDouble( tmp.toString() )  : 0.0;

				tmp = metaData.get( "Information|Image|V|View|PositionY #" + StackList.leadingZeros( Integer.toString( at+1 ), numDigits ) );
				if ( tmp == null )
					tmp = metaData.get( "Information|Image|V|View|PositionY #"  + ( at+1 ) );
				pos[ 1 ] = (tmp != null) ?  Double.parseDouble( tmp.toString() )  : 0.0;

				tmp = metaData.get( "Information|Image|V|View|PositionZ #" + StackList.leadingZeros( Integer.toString( at+1 ), numDigits ) );
				if ( tmp == null )
					tmp = metaData.get( "Information|Image|V|View|PositionZ #" + ( at+1 ) );
				pos[ 2 ] = (tmp != null) ?  Double.parseDouble( tmp.toString() )  : 0.0;

				tileLocations.add( pos.clone() );

				tiles[ at ] = "Tile" + at;
			}
		}
		catch ( Exception e )
		{
			IOFunctions.println( "An error occured parsing the tile locations: " + e + "\n. Proceeding." );
			tileLocations.clear();

			for ( int at = 0; at < numAorT; ++at )
			{
				tileLocations.add( new double[]{ 0, 0, 0 } );
				tiles[ at ] = "Tile" + at;
			}
			printMetadata = true;
		}

		// get the axis of rotation
		try
		{
			tmp = metaData.get( "Information|Image|V|AxisOfRotation #1" );
			if ( tmp != null && tmp.toString().trim().length() >= 5 )
			{
				IOFunctions.println( "Rotation axis: " + tmp );
				final String[] axes = tmp.toString().split( " " );

				if ( Double.parseDouble( axes[ 0 ] ) == 1.0 )
					rotationAxis = 0;
				else if ( Double.parseDouble( axes[ 1 ] ) == 1.0 )
					rotationAxis = 1;
				else if ( Double.parseDouble( axes[ 2 ] ) == 1.0 )
					rotationAxis = 2;
				else
				{
					rotationAxis = -1;
					printMetadata = true;
				}
			}
			else
			{
				rotationAxis = -1;
				printMetadata = true;
			}
		}
		catch ( Exception e )
		{
			IOFunctions.println( "An error occured parsing the rotation axis: " + e + "\n. Proceeding." );
			rotationAxis = -1;
			printMetadata = true;
		}

		try
		{
			for ( final String key : metaData.keySet() )
			{
				if ( key.startsWith( "LsmTag|Name #" ) && metaData.get( key ).toString().trim().equals( "LightSheetThickness" ) )
				{
					String lookup = "LsmTag " + key.substring( key.indexOf( '#' ), key.length() );
					tmp = metaData.get( lookup );

					if ( tmp != null )
						lightsheetThickness = Double.parseDouble( tmp.toString() );
					else
						lightsheetThickness = -1;
				}
			}
		}
		catch ( Exception e )
		{
			IOFunctions.println( "An error occured parsing the lightsheet thickness: " + e + "\n. Proceeding." );
			lightsheetThickness = -1;
			printMetadata = true;
		}

		try
		{
			final MetadataRetrieve retrieve = (MetadataRetrieve)r.getMetadataStore();

			float cal = 0;

			Length f = retrieve.getPixelsPhysicalSizeX( 0 );
			if ( f != null )
				cal = f.value().floatValue();

			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "LightSheetZ1: Warning, calibration for dimension X seems corrupted, setting to 1." );
			}
			calX = cal;

			f = retrieve.getPixelsPhysicalSizeY( 0 );
			if ( f != null )
				cal = f.value().floatValue();

			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "LightSheetZ1: Warning, calibration for dimension Y seems corrupted, setting to 1." );
			}
			calY = cal;

			f = retrieve.getPixelsPhysicalSizeZ( 0 );
			if ( f != null )
				cal = f.value().floatValue();

			if ( cal == 0 )
			{
				cal = 1;
				IOFunctions.println( "LightSheetZ1: Warning, calibration for dimension Z seems corrupted, setting to 1." );
			}
			calZ = cal;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "An error occured parsing the calibration: " + e + "\n. Proceeding." );
			calX = calY = calZ = 1;
			printMetadata = true;
		}

		if ( printMetadata )
			printMetaData( r );

		if ( !keepFileOpen )
			try { r.close(); } catch (IOException e) { e.printStackTrace(); }
		else
			this.r = r;

		return true;
	}

	private final static boolean allZero( final Img< FloatType > slice )
	{
		for ( final FloatType t : slice )
			if ( t.get() != 0.0f )
				return false;

		return true;
	}

	public static boolean fixBioformats( final SpimData2 spimData, final File cziFile, final LightSheetZ1MetaData meta )
	{
		final IFormatReader r;

		// if we already loaded the metadata in this run, use the opened file
		if ( meta.getReader() == null )
			r = LegacyLightSheetZ1ImgLoader.instantiateImageReader();
		else
			r = meta.getReader();

		try
		{
			final boolean isLittleEndian = meta.isLittleEndian();
			final int pixelType = meta.pixelType();

			// open the file if not already done
			try
			{
				if ( meta.getReader() == null )
				{
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Opening '" + cziFile.getName() + "' for reading image data." );
					r.setId( cziFile.getAbsolutePath() );
				}
			}
			catch ( IllegalStateException e )
			{
				r.setId( cziFile.getAbsolutePath() );
			}

			// collect all Tiles as their id defines the seriesId of Bioformats
			final HashMap< Tile, ViewDescription > map = new HashMap<>();
			final SequenceDescription sd = spimData.getSequenceDescription();

			for ( final ViewSetup vs : sd.getViewSetupsOrdered() )
			{
				for ( final TimePoint t : sd.getTimePoints().getTimePointsOrdered() )
				{
					final ViewDescription vd = sd.getViewDescription( t.getId(), vs.getId() );
	
					if ( vd.isPresent() )
						map.put( vd.getViewSetup().getTile(), vd );
				}
			}

			for ( final Tile t : map.keySet() )
			{
				final ViewDescription vd = map.get( t );
				
				final int width = (int)vd.getViewSetup().getSize().dimension( 0 );
				final int height = (int)vd.getViewSetup().getSize().dimension( 1 );
				final int depth = (int)vd.getViewSetup().getSize().dimension( 2 );
				final int numPx = width * height;

				// set the right tile
				r.setSeries( t.getId() );

				final byte[] b = new byte[ numPx * meta.bytesPerPixel() ];

				final Img< FloatType > slice = ArrayImgs.floats( width, height );

				int z = depth - 1;
				for ( z = depth - 1; z >= 0; --z )
				{
					final Cursor< FloatType > cursor = slice.localizingCursor();

					r.openBytes( r.getIndex( z, 0, vd.getTimePointId() ), b );

					if ( pixelType == FormatTools.UINT8 )
						LegacyLightSheetZ1ImgLoader.readBytesArray( b, cursor, numPx );
					else if ( pixelType == FormatTools.UINT16 )
						LegacyLightSheetZ1ImgLoader.readUnsignedShortsArray( b, cursor, numPx, isLittleEndian );
					else if ( pixelType == FormatTools.INT16 )
						LegacyLightSheetZ1ImgLoader.readSignedShortsArray( b, cursor, numPx, isLittleEndian );
					else if ( pixelType == FormatTools.UINT32 )
						LegacyLightSheetZ1ImgLoader.readUnsignedIntsArray( b, cursor, numPx, isLittleEndian );
					else if ( pixelType == FormatTools.FLOAT )
						LegacyLightSheetZ1ImgLoader.readFloatsArray( b, cursor, numPx, isLittleEndian );

					if ( !allZero( slice ) )
						break;
				}

				// size is one bigger than the last z-slice
				z++;

				meta.imageSizes().put( t.getId(), new int[]{ width, height, z } );
				for ( final ViewSetup vs : sd.getViewSetupsOrdered() )
				{
					if ( vs.getTile().getId() == t.getId() )
					{
						vs.setSize( new FinalDimensions( meta.imageSizes().get( t.getId() ) ) );
						IOFunctions.println( "Resetting image size for viewSetup: " + vs.getId() + ", old: " + width + "x" + height + "x" + depth + ", new: " + width + "x" + height + "x" + z );
					}
				}
			}
		}
		catch ( Exception e )
		{
			IOFunctions.println( "File '" + cziFile.getAbsolutePath() + "' could not be opened: " + e );
			IOFunctions.println( "Stopping" );

			e.printStackTrace();
			try { r.close(); } catch (IOException e1) { e1.printStackTrace(); }
			return false;
		}

		return true;
	}

	protected static double getDouble( final Hashtable< String, Object > metadata, final String key )
	{
		if ( metadata == null )
			throw new RuntimeException( "Missing metadata while looking for: " + key );

		final Object o = metadata.get( key );

		if ( o == null )
		{
			final StringBuilder builder = new StringBuilder();
			for ( final String candidate : metadata.keySet() )
				builder.append( "\n" + candidate );
			//System.out.println( "Available keys:" + builder );

			IOFunctions.println( "Missing key " + key + " in LZ1 metadata" );
			return Double.NaN;
		}

		return Double.parseDouble( o.toString() );
	}

	public static void printMetaData( final IFormatReader r )
	{
		printMetaData( r.getGlobalMetadata() );
	}

	public static void printMetaData( final Hashtable< String, Object > metaData )
	{
		ArrayList< String > entries = new ArrayList<String>();

		for ( final String s : metaData.keySet() )
			entries.add( "'" + s + "': " + metaData.get( s ) );

		Collections.sort( entries );

		for ( final String s : entries )
			System.out.println( s );
	}
}
