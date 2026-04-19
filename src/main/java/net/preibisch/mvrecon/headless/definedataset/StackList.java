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

import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.TimePointsPattern;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;

import net.preibisch.mvrecon.fiji.spimdata.NamePattern;

/**
 * Base StackList class for various kinds of DataSet definition class
 */
public class StackList extends DefineDataSet
{
	protected static class Calibration
	{
		public double calX = 1, calY = 1, calZ = 1;
		public String calUnit = "um";

		public Calibration( final double calX, final double calY, final double calZ, final String calUnit )
		{
			this.calX = calX;
			this.calY = calY;
			this.calZ = calZ;
			this.calUnit = calUnit;
		}

		public Calibration( final double calX, final double calY, final double calZ )
		{
			this.calX = calX;
			this.calY = calY;
			this.calZ = calZ;
		}

		public Calibration() {};
	}

	protected static class ViewSetupPrecursor
	{
		final public int c, i, a, x;
		final public ArrayList< String> channelNameList, illuminationsNameList, angleNameList, tileNameList;

		public ViewSetupPrecursor( final int c, final int i, final int a, final int x, ArrayList< String> channelNameList, ArrayList< String>  illuminationsNameList, ArrayList< String>  angleNameList, ArrayList< String> tileNameList )
		{
			this.c = c;
			this.i = i;
			this.a = a;
			this.x = x;

			this.channelNameList = channelNameList;
			this.illuminationsNameList = illuminationsNameList;
			this.angleNameList = angleNameList;
			this.tileNameList = tileNameList;
		}

		@Override
		public int hashCode()
		{
			return c * illuminationsNameList.size() * angleNameList.size() * tileNameList.size() + i * angleNameList.size() * tileNameList.size() + a * tileNameList.size() + x;
		}

		@Override
		public boolean equals( final Object o )
		{
			if ( o instanceof ViewSetupPrecursor )
				return c == ((ViewSetupPrecursor)o).c && i == ((ViewSetupPrecursor)o).i && a == ((ViewSetupPrecursor)o).a && x == ((ViewSetupPrecursor)o).x;
			else
				return false;
		}

		@Override
		public String toString() { return "channel=" + channelNameList.get( c ) + ", ill.dir.=" + illuminationsNameList.get( i ) + ", angle=" + angleNameList.get( a ) + ", tile=" + tileNameList.get( x ); }
	}

	protected static String assembleDefaultPattern(final int hasMultipleAngles, final int hasMultipleTimePoints, final int hasMultipleChannels, final int hasMultipleIlluminations, final int hasMultipleTiles )
	{
		String pattern = "spim";

		if ( hasMultipleTimePoints == 1 )
			pattern += "_TL{t}";

		if ( hasMultipleChannels == 1 )
			pattern += "_Channel{c}";

		if ( hasMultipleIlluminations == 1 )
			pattern += "_Illum{i}";

		if ( hasMultipleAngles == 1 )
			pattern += "_Angle{a}";

		if ( hasMultipleTiles == 1 )
			pattern += "_Tile{x}";

		return pattern + ".tif";
	}

	/**
	 * Create sequence description for StackList.
	 *
	 * @param timepoints the timepoints
	 * @param channels the channels
	 * @param illuminations the illuminations
	 * @param angles the angles
	 * @param tiles the tiles
	 * @param calibration the calibration
	 * @return the sequence description
	 */
	public static SequenceDescription createSequenceDescription( final String timepoints, final String channels, final String illuminations, final String angles, final String tiles, Calibration calibration )
	{
		// assemble timepints, viewsetups, missingviews and the imgloader
		TimePoints timePoints = null;
		try
		{
			timePoints = new TimePointsPattern( timepoints );
		}
		catch ( ParseException e )
		{
			e.printStackTrace();
		}

		ArrayList< String > timepointNameList = null, channelNameList = null, illuminationsNameList = null, angleNameList = null, tileNameList = null;

		try
		{
			timepointNameList = ( NamePattern.parseNameString( timepoints, false ) );
			channelNameList = ( NamePattern.parseNameString( channels, true ) );
			illuminationsNameList = ( NamePattern.parseNameString( illuminations, true ) );
			angleNameList = ( NamePattern.parseNameString( angles, true ) );
			tileNameList = ( NamePattern.parseNameString( tiles, true ) );
		}
		catch ( ParseException e )
		{
			e.printStackTrace();
		}

		final ArrayList< Channel > channelList = new ArrayList< Channel >();
		for ( int c = 0; c < channelNameList.size(); ++c )
			channelList.add( new Channel( c, channelNameList.get( c ) ) );

		final ArrayList< Illumination > illuminationList = new ArrayList< Illumination >();
		for ( int i = 0; i < illuminationsNameList.size(); ++i )
			illuminationList.add( new Illumination( i, illuminationsNameList.get( i ) ) );

		final ArrayList< Angle > angleList = new ArrayList< Angle >();
		for ( int a = 0; a < angleNameList.size(); ++a )
			angleList.add( new Angle( a, angleNameList.get( a ) ) );

		final ArrayList< Tile > tileList = new ArrayList< Tile >();
		for ( int x = 0; x < tileNameList.size(); ++x )
			tileList.add( new Tile( x, tileNameList.get( x ) ) );

		HashMap< ViewSetupPrecursor, Calibration > calibrations = new HashMap< ViewSetupPrecursor, Calibration >();

		final ArrayList< ViewSetup > viewSetups = new ArrayList< ViewSetup >();
		for ( final Channel c : channelList )
			for ( final Illumination i : illuminationList )
				for ( final Angle a : angleList )
					for ( final Tile x : tileList )
					{
						final Calibration cal = calibrations.get( new ViewSetupPrecursor( c.getId(), i.getId(), a.getId(), x.getId(), channelNameList, illuminationsNameList, angleNameList, tileNameList ) );
						final VoxelDimensions voxelSize = new FinalVoxelDimensions( cal.calUnit, cal.calX, cal.calY, cal.calZ );
						viewSetups.add( new ViewSetup( viewSetups.size(), null, null, voxelSize, x, c, a, i ) );
					}

		ArrayList< int[] > exceptionIds = new ArrayList< int[] >();

		// Add exceptionIDs
		//
		// exceptionIds.add( new int[]{ t, c, i, a, x } );
		// System.out.println( "adding missing views t:" + t + " c:" + c + " i:" + i + " a:" + a + " x:" + x );

		if ( exceptionIds.size() == 0 )
			return null;

		final ArrayList< ViewId > missingViewArray = new ArrayList< ViewId >();

		if ( exceptionIds.size() > 0 )
		{
			for ( int t = 0; t < timepointNameList.size(); ++t )
			{
				// assemble a subset of exceptions for the current timepoint
				final ArrayList< int[] > tmp = new ArrayList< int[] >();

				for ( int[] exceptions : exceptionIds )
					if ( exceptions[ 0 ] == t )
						tmp.add( exceptions );

				if ( tmp.size() > 0 )
				{
					int setupId = 0;

					for ( int c = 0; c < channelNameList.size(); ++c )
						for ( int i = 0; i < illuminationsNameList.size(); ++i )
							for ( int a = 0; a < angleNameList.size(); ++a )
								for ( int x = 0; x < tileNameList.size(); ++x)
								{
									for ( int[] exceptions : tmp )
										if ( exceptions[ 1 ] == c && exceptions[ 2 ] == i && exceptions[ 3 ] == a && exceptions[ 4 ] == x )
										{
											missingViewArray.add( new ViewId( Integer.parseInt( timepointNameList.get( t ) ), setupId ) );
											System.out.println( "creating missing views t:" + Integer.parseInt( timepointNameList.get( t ) ) + " c:" + c + " i:" + i + " a:" + a + " x:" + x + " setupid: " + setupId );
										}
	
									++setupId;
								}
				}
			}
		}

		final MissingViews missingViews = new MissingViews( missingViewArray );		// instantiate the sequencedescription
		return new SequenceDescription( timePoints, viewSetups, null, missingViews );
	}

	public static String leadingZeros( String s, final int numDigits )
	{
		while ( s.length() < numDigits )
			s = "0" + s;

		return s;
	}
}
