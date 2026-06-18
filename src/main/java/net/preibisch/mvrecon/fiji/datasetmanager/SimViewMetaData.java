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
package net.preibisch.mvrecon.fiji.datasetmanager;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;

import mpicbg.spim.data.sequence.IntegerPattern;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.SimView.DirectoryFilter;

public class SimViewMetaData
{
	final public static char TIMEPOINT_PATTERN = 't';
	final public static char CHANNEL_PATTERN = 'c';
	final public static char CAM_PATTERN = 'm';
	final public static char ANGLE_PATTERN = 'a';

	final public static char PLANE_PATTERN = 'p';

	public static class Pattern
	{
		public String replaceTimepoints = null, replaceChannels = null, replaceCams = null, replaceAngles = null, replacePlanes = null;
		public int numDigitsTimepoints = 0, numDigitsChannels = 0, numDigitsCams = 0, numDigitsAngles = 0, numDigitsPlanes = 0;
	}

	public String filePattern = "";
	public Pattern patternParser = null;

	boolean isPlanar = false;

	File rootDir, expDir;
	String[] baseXMLs; // relative to expDir

	int numTimePoints;
	String[] timePoints;

	int numAngles;
	String[] angles; // directories

	int numChannels;
	String[] channels;
	SimViewChannel[] metaDataChannels;

	double xStep = 0.4125, yStep = 0.4125;
	double zStep = 1;
	String unit = " um";

	int numCameras = 0;
	long[] stackSize = null;
	int type = 2; //{"8-bit", "16-bit Signed", "16-bit Unsigned" };
	boolean littleEndian = true;

	public static SimViewMetaData parseMetaData( final File rootDir, final File expDir )
	{
		final SimViewMetaData metaData = new SimViewMetaData();
		metaData.rootDir = rootDir;
		metaData.expDir = expDir;

		//
		// get #timepoints from the sorted directory list
		//
		String[] dirs = expDir.list( new DirectoryFilter( "TM" ) );

		if ( dirs.length == 0 )
		{
			IOFunctions.println( expDir.getAbsolutePath() + " contains no subdirectories with experiments." );
			return null;
		}
		else
		{
			Arrays.sort( dirs );

			metaData.numTimePoints = dirs.length;
			metaData.timePoints = dirs;

			IOFunctions.println( "Found " + metaData.numTimePoints + " timepoints: " + metaData.timePoints[ 0 ] + " >>> " + metaData.timePoints[ metaData.timePoints.length - 1] + "." );
		}

		//
		// get #channels from the XML files in the first timepoint
		//
		final File firstTP = new File( expDir, metaData.timePoints[ 0 ] );
		dirs = firstTP.list( new FilenameFilter()
		{
			@Override
			public boolean accept(final File dir, final String name)
			{
				return name.toLowerCase().startsWith( "ch" ) && name.toLowerCase().endsWith( ".xml");
			}
		});

		if ( dirs.length == 0 )
		{
			IOFunctions.println( expDir.getAbsolutePath() + " contains no XML files." );
			return null;
		}
		else
		{
			Arrays.sort( dirs );

			metaData.numChannels = dirs.length;
			metaData.metaDataChannels = new SimViewChannel[ metaData.numChannels ];
			metaData.channels = dirs;
			metaData.baseXMLs = new String[ dirs.length ];

			IOFunctions.println( "Found " + metaData.numChannels + " channels: " );

			for ( int c = 0; c < metaData.numChannels; ++c )
			{
				metaData.baseXMLs[ c ] = new File( metaData.timePoints[ 0 ], metaData.channels[ c ] ).getPath();
				metaData.channels[ c ] = metaData.channels[ c ].substring( 0, metaData.channels[ c ].toLowerCase().lastIndexOf(".xml") );

				IOFunctions.println();
				IOFunctions.println( "channel " + metaData.channels[ c ] );
				IOFunctions.println( "baseXML " + metaData.baseXMLs[ c ] );

				try
				{
					metaData.metaDataChannels[ c ] = SimViewMetaData.parseSimViewXML( new File( expDir, metaData.baseXMLs[ c ] ) );
				}
				catch (JDOMException | IOException e)
				{
					IOFunctions.println( "Failed to parse XML: " + e );
					IOFunctions.println( "Stopping." );
					e.printStackTrace();
					return null;
				}
			}

			//
			// get #rotation angles from the directory structure
			//
			dirs = firstTP.list( new FilenameFilter()
			{
				@Override
				public boolean accept(final File dir, final String name)
				{
					return name.toLowerCase().startsWith( "ang" ) && new File( dir, name ).isDirectory();
				}
			});

			Arrays.sort( dirs );
			metaData.numAngles = dirs.length;
			metaData.angles = dirs;
			
			IOFunctions.println();
			IOFunctions.println( "Found " + metaData.numAngles + " angles: " );

			for ( final String angle : metaData.angles )
				IOFunctions.println( angle );

			//
			// test if it is saved as planar images
			//
			final File angleFolder = new File( firstTP, getAngleString( 0 ) );

			dirs = angleFolder.list( new FilenameFilter()
			{
				@Override
				public boolean accept(final File dir, final String name)
				{
					return name.toLowerCase().startsWith( "spc" );
				}
			});

			if ( dirs[ 0 ].toLowerCase().contains( "_pln" ) )
			{
				metaData.isPlanar = true;
				IOFunctions.println( "Acquistion is planar: " + dirs[ 0 ] );
			}
			else
			{
				metaData.isPlanar = false;
			}
		}

		if ( metaData.assignGlobalValues() )
			return metaData;
		else
			return null;
	}

	public void buildDefaultFilePattern()
	{
		if ( isPlanar )
			builFilePattern( "SPC00_TM{ttttt}_ANG{aaa}_CM{m}_CHN{cc}_PH0_PLN{pppp}.tif" );
		else
			builFilePattern( "SPC00_TM{ttttt}_ANG{aaa}_CM{m}_CHN{cc}_PH0.stack" );
	}
	
	public void builFilePattern( final String filePattern )
	{
		this.filePattern = filePattern;
		this.patternParser = buildPatternParser( filePattern );
	}

	public static Pattern buildPatternParser( final String filePattern )
	{
		final Pattern p = new Pattern();

		p.replaceCams = IntegerPattern.getReplaceString( filePattern, CAM_PATTERN );
		p.numDigitsCams = p.replaceCams.length() - 2;

		p.replaceChannels = IntegerPattern.getReplaceString( filePattern, CHANNEL_PATTERN );
		p.numDigitsChannels = p.replaceChannels.length() - 2;

		p.replaceTimepoints = IntegerPattern.getReplaceString( filePattern, TIMEPOINT_PATTERN );
		p.numDigitsTimepoints = p.replaceTimepoints.length() - 2;

		p.replaceAngles = IntegerPattern.getReplaceString( filePattern, ANGLE_PATTERN );
		p.numDigitsAngles = p.replaceAngles.length() - 2;

		p.replacePlanes = IntegerPattern.getReplaceString( filePattern, PLANE_PATTERN );
		if ( p.replacePlanes != null )
			p.numDigitsPlanes = p.replacePlanes.length() - 2;

		return p;
	}

	public static String[] getFileNamesFor( String fileNames, 
			final String replaceTimepoints, final String replaceChannels, final String replaceCams, final String replaceAngles, final String replacePlanes,
			final int tpName, final int chName, final int camName, final int angleName, final int planeName,
			final int numDigitsTP, final int numDigitsCh, final int numDigitsCam, final int numDigitsAngle, final int numDigitsPlane )
	{
		String[] fileName = fileNames.split( ";" );
		
		for ( int i = 0; i < fileName.length; ++i )
		{
			if ( replaceTimepoints != null )
				fileName[ i ] = fileName[ i ].replace( replaceTimepoints, StackList.leadingZeros( Integer.toString( tpName ), numDigitsTP ) );
	
			if ( replaceChannels != null )
				fileName[ i ] = fileName[ i ].replace( replaceChannels, StackList.leadingZeros( Integer.toString( chName ), numDigitsCh ) );
	
			if ( replaceCams != null )
				fileName[ i ] = fileName[ i ].replace( replaceCams, StackList.leadingZeros( Integer.toString( camName ), numDigitsCam ) );
	
			if ( replaceAngles != null )
				fileName[ i ] = fileName[ i ].replace( replaceAngles, StackList.leadingZeros( Integer.toString( angleName ), numDigitsAngle ) );

			if ( replacePlanes != null )
				fileName[ i ] = fileName[ i ].replace( replacePlanes, StackList.leadingZeros( Integer.toString( planeName ), numDigitsPlane ) );
		}
		return fileName;
	}

	public boolean isValidFilePattern()
	{
		boolean present = true;

		IOFunctions.println( "Testing if all files are present ... " );

		for ( int tp = 0; tp < numTimePoints; ++tp )
		{
			IOFunctions.println( "Testing timepoint " + getTimepointString( tp ) );

			final File tpFolder = new File( expDir, getTimepointString( tp ) );

			for ( int angle = 0; angle < numAngles; ++angle )
			{
				IOFunctions.println( "Testing timepoint " + getAngleString( angle ) );

				final File angleFolder = new File( tpFolder, getAngleString( angle ) );

				for ( int cam = 0; cam < numCameras; ++cam )
				{
					for ( int ch = 0; ch < numChannels; ++ch )
					{
						if ( isPlanar )
						{
							for ( int plane = 0; plane < stackSize[ 2 ]; ++plane )
							{
								final File rawFile = new File( angleFolder, 
										getFileNamesFor(
												this.filePattern,
												patternParser.replaceTimepoints, patternParser.replaceChannels, patternParser.replaceCams, patternParser.replaceAngles, patternParser.replacePlanes,
												tp,ch, cam, angle, plane,
												patternParser.numDigitsTimepoints, patternParser.numDigitsChannels, patternParser.numDigitsCams, patternParser.numDigitsAngles, patternParser.numDigitsPlanes )[0] );
								
								if ( !rawFile.exists() )
								{
									IOFunctions.println( "File MISSING: " + rawFile.getAbsolutePath() );
									present = false;
								}
							}
						}
						else
						{
							final File rawFile = new File( angleFolder, 
									getFileNamesFor(
											this.filePattern,
											patternParser.replaceTimepoints, patternParser.replaceChannels, patternParser.replaceCams, patternParser.replaceAngles, null,
											tp,ch, cam, angle, 0,
											patternParser.numDigitsTimepoints, patternParser.numDigitsChannels, patternParser.numDigitsCams, patternParser.numDigitsAngles, 0 )[0] );
							
							if ( !rawFile.exists() )
							{
								IOFunctions.println( "File MISSING: " + rawFile.getAbsolutePath() );
								present = false;
							}
						}
					}
				}
			}
		}

		if ( present )
			IOFunctions.println( "All files found." );

		return present;
	}

	public static int getTimepointInt( final String tp )
	{
		return Integer.parseInt(tp.substring(2)); //e.g. TM00012
	}

	public static int getAngleInt( final String angle )
	{
		return Integer.parseInt(angle.substring(3)); //e.g. ANG000
	}

	public static String getTimepointString( final int tp )
	{
		return "TM" + StackList.leadingZeros( Integer.toString( tp ), 5 ); //e.g. TM00012
	}

	public static String getAngleString( final int angle )
	{
		return "ANG" + StackList.leadingZeros( Integer.toString( angle ), 3 ); //e.g. ANG000
	}

	/**
	 * assigns global metadata
	 * @return true if metadata is consistent, otherwise false
	 */
	public boolean assignGlobalValues()
	{
		this.zStep = metaDataChannels[ 0 ].z_step;

		for ( int i = 1; i < numChannels; ++i )
			if ( metaDataChannels[ i ].z_step != this.zStep )
			{
				IOFunctions.println( "z-stepping inconsistent, " + this.zStep + "!= " + metaDataChannels[ i ].z_step + ".");
				return false;
			}

		this.numCameras = metaDataChannels[ 0 ].numCameras;

		for ( int i = 1; i < numChannels; ++i )
			if ( metaDataChannels[ i ].numCameras != this.numCameras )
			{
				IOFunctions.println( "numCameras inconsistent, " + this.numCameras + "!= " + metaDataChannels[ i ].numCameras + ".");
				return false;
			}

		this.stackSize = new long[]{ metaDataChannels[ 0 ].stackSizes[ 0 ][ 0 ], metaDataChannels[ 0 ].stackSizes[ 0 ][ 1 ], metaDataChannels[ 0 ].stackSizes[ 0 ][ 2 ] };

		for ( int i = 0; i < numChannels; ++i )
			for ( int c = 0; c < metaDataChannels[ i ].stackSizes.length; ++c )
				for ( int d = 0; d < 3; ++d  )
					if ( stackSize[ d ] != metaDataChannels[ i ].stackSizes[ c ][ d ] )
					{
						IOFunctions.println( "stackSize inconsistent, " + Util.printCoordinates( this.stackSize ) + "!= " + Util.printCoordinates(  metaDataChannels[ i ].stackSizes[ c ] ) + ".");
						return false;
					}

		buildDefaultFilePattern();

		return true;
	}

	public static class SimViewChannel
	{
		int wavelength;
		double z_step = 1;

		int numCameras;
		int[][] stackSizes;

		// non-essential metadata
		String specimen_name;
		String timestamp;
		String specimen_XYZT;
		double angle = 0;

		HashMap<String , String > metadataHash;
	}
	
	public static SimViewChannel parseSimViewXML( final File file ) throws JDOMException, IOException
	{
		final SimViewChannel metaData = new SimViewChannel();
		metaData.metadataHash = new HashMap<String, String>();

		final SAXBuilder sax = new SAXBuilder();
		Document doc = sax.build( file );

		final Element root = doc.getRootElement();
		
		final List< Element > children = root.getChildren();
		
		for ( final Element e : children )
			metaData.metadataHash.put( e.getAttributes().get(0).getName(), e.getAttributes().get(0).getValue() );

		// parse common metadata
		String dimensions = metaData.metadataHash.getOrDefault( "dimensions", null );

		if ( dimensions == null )
		{
			IOFunctions.println( "dimensions tag not defined in XML " + file.getAbsolutePath() );
			IOFunctions.println( "cannot load dimensions & camera settings. quitting." );
		}
		else
		{
			//928x1000x21 or 2048x2048x131,2048x2048x131
			//defines number of cameras used
			String[] dims = dimensions.split( "," );
			metaData.numCameras = dims.length;
			
			IOFunctions.println( "num cameras: " + metaData.numCameras );
			
			metaData.stackSizes = new int[ metaData.numCameras ][ 3 ];
			
			for ( int cam = 0; cam < metaData.numCameras; ++cam )
			{
				String[] perDim = dims[ cam ].split( "x" );
				metaData.stackSizes[ cam ][ 0 ] = Integer.parseInt( perDim[ 0 ] );
				metaData.stackSizes[ cam ][ 1 ] = Integer.parseInt( perDim[ 1 ] );
				metaData.stackSizes[ cam ][ 2 ] = Integer.parseInt( perDim[ 2 ] );
				
				IOFunctions.println( "camera " + cam + ": " + Util.printCoordinates( metaData.stackSizes[ cam ] ) );
			}
		}

		metaData.specimen_name = metaData.metadataHash.getOrDefault( "specimen_name", null );
		metaData.timestamp = metaData.metadataHash.getOrDefault( "timestamp", null );
		metaData.specimen_XYZT = metaData.metadataHash.getOrDefault( "specimen_XYZT", null );
		if ( metaData.metadataHash.containsKey( "angle" ) )
			metaData.angle = Double.parseDouble( metaData.metadataHash.get( "angle" ) );
		if ( metaData.metadataHash.containsKey( "wavelength" ) )
			metaData.wavelength = Integer.parseInt( metaData.metadataHash.get( "wavelength" ) );
		if ( metaData.metadataHash.containsKey( "z_step" ) )
			metaData.z_step = Double.parseDouble( metaData.metadataHash.get( "z_step" ) );

		IOFunctions.println( "specimen_name: " + metaData.specimen_name );
		IOFunctions.println( "timestamp: " + metaData.timestamp );
		IOFunctions.println( "angle: " + metaData.angle );
		IOFunctions.println( "wavelength: " + metaData.wavelength );
		IOFunctions.println( "z_step: " + metaData.z_step );
		IOFunctions.println( "specimen_XYZT: " + metaData.specimen_XYZT );

		return metaData;
	}

}
