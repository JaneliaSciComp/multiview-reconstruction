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
package net.preibisch.mvrecon.process.interestpointembedding;

import java.util.HashMap;
import java.util.Map;

import mpicbg.spim.data.sequence.VoxelDimensions;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.StoredDetectionParameters;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;

/**
 * The scale a label was detected at, read from its parameters string: the detection sigma in full-resolution xy pixels
 * ({@code s x downsampleXY}: the DoG ran on the downsampled image with sigma {@code s} in its pixels, the points are stored in
 * full-resolution pixels) and the xy downsampling factor of the detection (the pyramid level it ran at). The extents of
 * the embedding's cubes are multiples of that sigma, and no cube is read from a level finer than the detection's. Exactly
 * pcmatch's {@code bigstitcher.dog_sigma_fullres_px} and {@code dog_downsample_xy} (2026-10-30), so that both sides
 * sample the same cubes:
 * <ul>
 * <li>{@code DOG ...} and {@code DOG-SS ...} strings of the GUI and of BigStitcher-Spark ({@link StoredDetectionParameters}):
 * {@code s}; the factor from {@code downsampleXY=} (Spark), the geometric mean of {@code downsampleX=}/{@code downsampleY=}
 * rounded (GUI since MVR 10), or {@code downsampleXYIndex=} of master's DoG, where 0 and -1 meant "match the z resolution":
 * {@link DownsampleTools#downsampleFactor} of the voxel size stored in the XML, as the detection computed it; no key = 1.
 * For the scale space {@code s} is the sigma of the finest level: a point's own sigma attribute is the scale of the point,
 * the label's sigma its lower bound ({@link PatchExtractor}).</li>
 * <li>{@code LEARNED ...} strings of pcmatch's detector: {@code sigma=} is already in full-resolution pixels, {@code downsampleXY=} (default 1).</li>
 * </ul>
 *
 * @param sigma - the detection sigma in full-resolution xy pixels
 * @param downsampleXY - the xy downsampling factor of the detection
 * @param fromCalibration - true if the factor was derived from the voxel size of the XML ({@code downsampleXYIndex} 0/-1 of
 *        master's DoG): it is only right while the XML still holds the calibration the detection saw; after "Specify Calibration"
 *        the string has to be pinned to the factor the detection used (e.g. {@code downsampleXYIndex=2}) or the label detected again
 *        (measured on ds1, 2026-10-30: correcting the calibration from 1.1/1.1/3.512 to 0.45/0.45/2.0 alone turned the factor from
 *        2 into 4 and the rows away from pcmatch's, cosine down to -0.26; with the factor pinned they agree to 2.5e-4)
 *
 * @author Stephan Preibisch
 */
public record DetectionScale( double sigma, int downsampleXY, boolean fromCalibration )
{
	public DetectionScale( final double sigma, final int downsampleXY ) { this( sigma, downsampleXY, false ); }

	/**
	 * @param label the label (for messages)
	 * @param parameters its parameters string ({@code InterestPoints.getParameters()})
	 * @param voxelSize the voxel size of the view as stored in the XML (only for {@code downsampleXYIndex} 0/-1 of master's DoG)
	 * @return the scale of the detection
	 * @throws IllegalArgumentException if the string is not one of a detection with a sigma (a filtered, copied or manual label: resolve the base label first)
	 */
	public static DetectionScale of( final String label, final String parameters, final VoxelDimensions voxelSize )
	{
		if ( parameters == null || parameters.trim().isEmpty() )
			throw new IllegalArgumentException( "The label '" + label + "' has no parameters string; it was not detected by a DoG, so there is no detection sigma to scale the embedding's cubes by." );

		final String[] tokens = parameters.trim().split( "\\s+" );
		final Map< String, String > values = new HashMap<>();
		for ( final String token : tokens )
		{
			final int eq = token.indexOf( '=' );
			if ( eq > 0 )
				values.put( token.substring( 0, eq ), token.substring( eq + 1 ) );
		}

		if ( tokens[ 0 ].equals( "LEARNED" ) )
		{
			final double sigma = StoredDetectionParameters.doubleValue( values, "sigma" );
			if ( !( sigma > 0 ) )
				throw new IllegalArgumentException( "The label '" + label + "' (" + parameters + ") has no 'sigma=' in full-resolution pixels." );
			return new DetectionScale( sigma, (int) StoredDetectionParameters.longValue( values, "downsampleXY", 1 ), false );
		}

		final StoredDetectionParameters p = StoredDetectionParameters.parse( parameters );
		if ( p == null )
			throw new IllegalArgumentException( "The label '" + label + "' has no DoG detection parameters (params = '" + parameters + "'); there is no detection sigma to scale the embedding's cubes by. A filtered label is embedded through its base label." );
		if ( !( p.sigma() > 0 ) )
			throw new IllegalArgumentException( "The label '" + label + "' (" + parameters + ") has no positive 's=' sigma." );

		final int downsampleXY;
		boolean fromCalibration = false;
		if ( p.downsampling() != null )
			downsampleXY = (int) Math.round( Math.sqrt( (double) p.downsampling()[ 0 ] * p.downsampling()[ 1 ] ) );
		else if ( values.containsKey( "downsampleXY" ) )
			downsampleXY = (int) StoredDetectionParameters.longValue( values, "downsampleXY", 1 );
		else if ( values.containsKey( "downsampleX" ) )
			downsampleXY = (int) Math.round( Math.sqrt( StoredDetectionParameters.doubleValue( values, "downsampleX" ) * StoredDetectionParameters.doubleValue( values, "downsampleY" ) ) );
		else
		{
			final int index = (int) StoredDetectionParameters.longValue( values, "downsampleXYIndex", 1 );
			if ( index >= 1 )
				downsampleXY = index;
			else
			{
				if ( voxelSize == null )
					throw new IllegalArgumentException( "The label '" + label + "' (" + parameters + ") was detected with downsampleXYIndex=" + index + " (matched to the z resolution), which needs the voxel size of the view, and the XML has none." );
				downsampleXY = DownsampleTools.downsampleFactor( index, (int) StoredDetectionParameters.longValue( values, "downsampleZ", 1 ), voxelSize );
				fromCalibration = true;
			}
		}

		if ( downsampleXY < 1 )
			throw new IllegalArgumentException( "The label '" + label + "' (" + parameters + ") has a downsampling of " + downsampleXY + " (an early scale-space string, 'match the z resolution'); pin the string to the factor the detection ran at, e.g. downsampleXY=2 downsampleXYIndex=2, or detect the label again." );

		return new DetectionScale( p.sigma() * downsampleXY, downsampleXY, fromCalibration );
	}

	@Override
	public String toString()
	{
		return "sigma " + sigma + " px (full resolution), detected at downsampling " + downsampleXY
				+ ( fromCalibration ? " (derived from the voxel size of the XML, the label's string says downsampleXYIndex 0/-1: only right while the XML holds the calibration the detection saw)" : "" );
	}
}
