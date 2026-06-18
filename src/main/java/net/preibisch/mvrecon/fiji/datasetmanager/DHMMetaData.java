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

import ij.ImagePlus;
import ij.io.Opener;

import java.io.BufferedReader;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.io.TextFileAccess;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.ValuePair;

public class DHMMetaData
{
	File directory;
	double calX, calY, calZ;
	String calUnit;
	boolean compareAllSizes;

	String stackDir = null;
	String ampDir = null;
	String phaseDir = null;
	String holoDir = null;
	String timestampFile = null;
	String extension = null;

	int ampChannelId = 0;
	int phaseChannelId = 1;

	int width = -1;
	int height = -1;

	List< String > zPlanes;
	List< String > timepoints;

	public DHMMetaData( final File directory, final double calX, final double calY, final double calZ, final String calUnit, final boolean compareAllSizes )
	{
		this.directory = directory;
		this.calX = calX;
		this.calY = calY;
		this.calZ = calZ;
		this.calUnit = calUnit;
		this.compareAllSizes = compareAllSizes;
	}

	public boolean loadMetaData()
	{
		if ( !checkIntegrity( directory ) )
			return false;

		IOFunctions.println( "Stack directory: ./" + stackDir );
		IOFunctions.println( "Amplitude directory: ./" + stackDir + "/" + ampDir );
		IOFunctions.println( "Phase directory: ./" + stackDir + "/" + phaseDir );

		if ( !parseTimestamps( directory, timestampFile ) )
			IOFunctions.println( "Failed to parse timestamp file." );

		if ( !getTimestampsAndZPlanes( compareAllSizes ) )
			return false;

		IOFunctions.println( "Final timepoints available for all zPlanes in Phase & Amplitude:" );
		for ( final String t : timepoints )
			IOFunctions.println( t );

		IOFunctions.println( "Final zPlanes available in Phase & Amplitude:" );
		for ( final String z : zPlanes )
			IOFunctions.println( z );

		IOFunctions.println( "imgX: " + width + "px" );
		IOFunctions.println( "imgY: " + height + "px" );
		IOFunctions.println( "imgZ: " + getDepth() + "px" );
		IOFunctions.println( "calX: " + calX + " " + calUnit );
		IOFunctions.println( "calY: " + calY + " " + calUnit );
		IOFunctions.println( "calZ: " + calZ + " " + calUnit );
		IOFunctions.println( "anisotropy: " + calZ / Math.min( calX, calY ) + "X ");
		IOFunctions.println( "image plane extension: " + extension );

		return true;
	}

	public File getDir() { return directory; }
	public String getStackDir() { return stackDir; }
	public String getAmplitudeDir() { return ampDir; }
	public String getPhaseDir() { return phaseDir; }
	public List< String > getZPlanes() { return zPlanes; }
	public List< String > getTimepoints() { return timepoints; }
	public int getAmpChannelId() { return ampChannelId; }
	public int getPhaseChannelId() { return phaseChannelId; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public int getDepth() { return zPlanes.size(); }
	public String getExt() { return extension; }

	/**
	 * Go through the directories and make sure all timestamps are present for all ampliude/phase-stacks
	 * @param compareAllSizes - open all 2d-planes and make sure the dimensions match?
	 * @return if succesful
	 */
	public boolean getTimestampsAndZPlanes( final boolean compareAllSizes )
	{
		final File ampDir = new File( new File( directory.getAbsolutePath(), stackDir ).getAbsolutePath(), this.ampDir );
		final File phaseDir = new File( new File( directory.getAbsolutePath(), stackDir ).getAbsolutePath(), this.phaseDir );
		
		if ( timepoints == null )
		{
			IOFunctions.println( "timestamps not know, loading from amplitude directory at z=0.00" );

			final Pair< List< String >, String > tps = loadTimepoints( new File( ampDir.getAbsolutePath(), "0.00" ) );

			if ( tps == null )
				return false;
			else
			{
				timepoints = tps.getA();
				IOFunctions.println( "Following timepoints found:" );

				for ( final String t : timepoints )
					IOFunctions.println( t );

				IOFunctions.println( "Extension: " + tps.getB() );
			}
		}

		final List< File > dirs = new ArrayList< File >();
		dirs.add( ampDir );
		dirs.add( phaseDir );

		final HashMap< String, Integer > zPlanes = new HashMap< String, Integer >();

		this.width = -1;
		this.height = -1;
		this.extension = null;
		final Opener opener = new Opener();

		for ( final File dir : dirs )
		{
			for ( final String d : dir.list() )
			{
				final File planeDir = new File( dir, d );

				if ( planeDir.exists() && planeDir.isDirectory() && d.matches( "-?[0-9]+\\.[0-9]+" ) )
				{
					final Pair< List< String >, String > tps = loadTimepoints( planeDir );

					if ( tps == null )
						return false;

					if ( width == -1 && height == -1 )
					{
						final File imgF = new File( planeDir.getAbsolutePath(), tps.getA().get( 0 ) + tps.getB() );
						final ImagePlus imp = opener.openImage( imgF.getAbsolutePath() );
						width = imp.getWidth();
						height = imp.getHeight();
						imp.close();
						IOFunctions.println( "Dimensions of plane image loaded from '" + imgF + "':" + width + "x" + height + "px." );
					}

					if ( compareAllSizes )
					{
						for ( final String imgN : tps.getA() )
						{
							final File imgF = new File( planeDir.getAbsolutePath(), imgN + tps.getB() );
							final ImagePlus imp = opener.openImage( imgF.getAbsolutePath() );
							int w = imp.getWidth();
							int h = imp.getHeight();
							imp.close();
	
							if ( w != width || h != height )
							{
								IOFunctions.println( "Dimensions for image '" + imgF + "' do not match: " + w + "x" + h + "px, before was " + width + "x" + height + "px. Stopping." );
								return false;
							}
						}
					}

					if ( this.extension == null )
						this.extension = tps.getB();

					if ( !this.extension.equals( tps.getB() ) )
					{
						IOFunctions.println( "Extension of filenames is not consistent. Was before '" + extension + "', now is '" + tps.getB() + "'. Stopping" );
						return false;
					}

					if ( tps.getA().size() != timepoints.size() )
						IOFunctions.println( "Different amount of timepoints (" + tps.getA().size() + ") for dir: " + planeDir );

					final int size = this.timepoints.size();
					this.timepoints = commonStrings( this.timepoints, tps.getA() );

					if ( size != timepoints.size() )
						IOFunctions.println( "Common amount of timepoints is now: " + timepoints.size() );

					if ( zPlanes.containsKey( d ) )
					{
						int i = zPlanes.get( d );
						zPlanes.put( d, i + 1 );
					}
					else
					{
						zPlanes.put( d, 1 );
					}
				}
			}
		}

		this.zPlanes = new ArrayList< String >();

		for ( final String z : zPlanes.keySet() )
		{
			if ( zPlanes.get( z ) != 2 )
				IOFunctions.println( "zPlane '' exists " + zPlanes.get( z ) + " times, this is wrong!" );
			else
				this.zPlanes.add( z );
		}

		// sort zPlanes by number value
		Collections.sort( this.zPlanes, new Comparator< String >()
		{
			@Override
			public int compare( final String o1, final String o2 )
			{
				final double d = Double.parseDouble( o1 ) - Double.parseDouble( o2 );

				if ( d < 0 )
					return -1;
				else if ( d > 0 )
					return 1;
				else
					return 0;
			}
		} );

		return true;
	}

	public static List< String > commonStrings( final List< String > listA, final List< String > listB )
	{
		final Set< String > setA = new HashSet< String >();
		final Set< String > setB = new HashSet< String >();
		final Set< String > setC = new HashSet< String >();

		setA.addAll( listA );
		setB.addAll( listB );

		for ( final String a : listA )
			if ( setB.contains( a ) )
				setC.add( a );

		for ( final String b : listB )
			if ( setA.contains( b ) )
				setC.add( b );

		final ArrayList< String > listC = new ArrayList< String >();
		listC.addAll( setC );
		Collections.sort( listC );

		return listC;
	}

	public static Pair< List< String >, String > loadTimepoints ( final File dataDir )
	{
		if ( !dataDir.exists() )
		{
			IOFunctions.println( dataDir.getAbsolutePath() + " does not exist" );
			return null;
		}

		final ArrayList< String > list = new ArrayList< String >();

		final String regex = "^[0-9]+\\.[tifTIF]+";

		final String[] files = dataDir.list();
		Arrays.sort( files );

		String extension = null;

		for ( final String t : files )
			if ( t.matches( regex ) )
			{
				list.add( t.split(  "\\." )[ 0 ] );

				String ext = t.substring( t.indexOf( "." ), t.length() );
				if ( extension == null )
				{
					extension = ext;
				}
				else if ( !ext.equals( extension ) )
				{
					IOFunctions.println( "Extension of filenames is not consistent. Was before '" + extension + "', now is '" + ext + "'. Stopping" );
					return null;
				}
			}

		return new ValuePair< List< String >, String >( list, extension );
	}

	public boolean parseTimestamps( final File directory, final String timestampFile )
	{
		if ( timestampFile == null )
			return false;

		final File t = new File( directory.getAbsolutePath(), timestampFile );

		if ( !t.exists() )
			return false;

		final BufferedReader in = TextFileAccess.openFileRead( t );

		try
		{
			this.timepoints = new ArrayList< String >();
			IOFunctions.println( "Following timepoints specified in timestamps.txt:" );

			while ( in.ready() )
			{
				String s = in.readLine().trim();

				if ( s.length() > 0 )
				{
					final String[] entries = s.split( " " );
					timepoints.add( entries[ 0 ] );
					IOFunctions.println( timepoints.get( timepoints.size() - 1 ) );
				}
			}
		}
		catch ( Exception e )
		{
			e.printStackTrace();
			timepoints = null;
			return false;
		}

		return true;
	}

	public boolean checkIntegrity( final File directory )
	{
		if ( !directory.exists() )
		{
			IOFunctions.println( "File '" + directory.getAbsolutePath() + "' does not exist. Stopping" );
			return false;
		}
		else
		{
			IOFunctions.println( "Investigating directory '" + directory.getAbsolutePath() + "'." );
		}

		String[] files = directory.list();

		for ( final String f : files )
		{
			if ( f.toLowerCase().equals( "stack" ) )
				stackDir = f;
			else if ( f.toLowerCase().equals( "holograms" ) )
				holoDir = f;
			else if ( f.toLowerCase().equals( "timestamps.txt" ) )
				timestampFile = f;
		}

		if ( holoDir == null )
			IOFunctions.println( "WARNING: Holograms-Directory does not exist. Continuing." );

		if ( timestampFile == null )
			IOFunctions.println( "WARNING: Timestamps.txt file missing. Continuing." );

		if ( stackDir == null )
		{
			IOFunctions.println( "Stack-Directory does not exist. Stopping" );
			return false;
		}
		else
		{
			files = new File( directory.getAbsolutePath(), stackDir ).list();

			for ( final String f : files )
			{
				if ( f.toLowerCase().equals( "amplitude" ) )
					ampDir = f;
				else if ( f.toLowerCase().equals( "phase" ) )
					phaseDir = f;
			}

			if ( ampDir == null )
			{
				IOFunctions.println( "Amplitude-Stack-Directory does not exist. Stopping" );
				return false;
			}

			if ( phaseDir == null )
			{
				IOFunctions.println( "Phase-Stack-Directory does not exist. Stopping" );
				return false;
			}
		}

		return true;
	}

	public static void main( String[] args )
	{
		//String s = "00000.TIF";
		//System.out.println( s.matches( "^[0-9]+\\.[tifTIF]+" ) );
		
		new DHMMetaData( new File( "/Users/preibischs/Downloads/2015.07.10 17-25_Ser_r" ), DHM.defaulCalX, DHM.defaulCalY, DHM.defaulCalZ, DHM.defaulCalUnit, false ).loadMetaData();
	}
}
