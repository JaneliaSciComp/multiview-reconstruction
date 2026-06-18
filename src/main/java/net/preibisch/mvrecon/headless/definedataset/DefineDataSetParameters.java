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
 * DefineDataSet Parameters class
 */
public class DefineDataSetParameters
{
	public enum RotationAxis
	{
		X_Axis, Y_Axis, Z_Axis
	}

	// LightSheetZ1 case
	// MicroManager
	public String channels[];
	public String angles[];
	public String illuminations[];

	public double pixelDistanceX;
	public double pixelDistanceY;
	public double pixelDistanceZ;

	public String pixelUnit;

	public RotationAxis rotationAround = RotationAxis.X_Axis;

	// Only MicroManager
	public boolean applyAxis;

	public void parseProperties( final Properties props )
	{
		for ( int a = 0; a < channels.length; ++a )
			channels[ a ] = props.getProperty( "angle_" + ( a + 1 ) );

		for ( int c = 0; c < channels.length; ++c )
			channels[ c ] = props.getProperty( "channel_" + ( c + 1 ) );

		for ( int i = 0; i < channels.length; ++i )
			channels[ i ] = props.getProperty( "illumination_" + ( i + 1 ) );

		pixelDistanceX = Double.parseDouble( props.getProperty( "pixel_distance_x" ) );
		pixelDistanceY = Double.parseDouble( props.getProperty( "pixel_distance_y" ) );
		pixelDistanceZ = Double.parseDouble( props.getProperty( "pixel_distance_z" ) );

		pixelUnit = props.getProperty( "pixel_unit" );

		rotationAround = DefineDataSetParameters.RotationAxis.valueOf( props.getProperty( "rotation_around", "X_Axis" ) );

		applyAxis = Boolean.parseBoolean( props.getProperty( "apply_axis" ) );
	}
}
