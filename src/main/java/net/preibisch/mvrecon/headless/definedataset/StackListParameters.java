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

import java.util.Properties;

/**
 * Created by moon on 7/2/15.
 */
public class StackListParameters
{
	// StackListImageJ
	// StackListLOCI
	public enum Container { ArrayImg, CellImg }
	public Container container = Container.ArrayImg;

	public String timepoints = "18,19,30";
	public String channels = "1,2";
	public String illuminations = "0,1";
	public String angles = "0-315:45";
	public String tiles = "0,1";
	public String directory = ".";

	public enum AngleOption { OneAngle, OneFilePerAngle, AllAnglesInOneFile }
	public AngleOption multipleAngleOption = AngleOption.OneFilePerAngle;

	public enum TimePointOption { OneTimePoint, OneFilePerTimePoint, AllTimePointsInOneFile }
	public TimePointOption multipleTimePointOption = TimePointOption.OneFilePerTimePoint;

	public enum ChannelOption { OneChannel, OneFilePerChannel, AllChannelsInOneFile }
	public ChannelOption multipleChannelOption = ChannelOption.OneChannel;

	public enum IlluminationOption { OneIllumination, OneFilePerIllumination, AllIlluminationsInOneFile }
	public IlluminationOption multipleIlluminationOption = IlluminationOption.OneIllumination;

	public enum TileOption { OneTile, OneFilePerTile, AllTilesInOneFile }
	public TileOption multipleTileOption = TileOption.OneTile;

	public void parseProperties( final Properties props )
	{
		container = Container.valueOf( props.getProperty( "container", "ArrayImg" ) );

		timepoints = props.getProperty( "timepoints" );

		channels = props.getProperty( "channels" );

		illuminations = props.getProperty( "illuminations" );

		tiles = props.getProperty( "tiles" );

		angles = props.getProperty( "angles" );

		directory = props.getProperty( "directory" );

		multipleAngleOption = AngleOption.valueOf( props.getProperty( "has_multiple_angle", "OneFilePerAngle" ) );

		multipleTimePointOption = TimePointOption.valueOf( props.getProperty( "has_multiple_timepoints", "OneFilePerTimePoint" ) );

		multipleChannelOption = ChannelOption.valueOf( props.getProperty( "has_multiple_channels", "OneChannel" ) );

		multipleIlluminationOption = IlluminationOption.valueOf( props.getProperty( "has_multiple_illuminations", "OneIllumination" ) );

		multipleTileOption = TileOption.valueOf( props.getProperty( "has_multiple_tiles", "OneTile" ) );
	}
}
