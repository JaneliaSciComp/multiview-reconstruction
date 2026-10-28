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
package net.preibisch.mvrecon.fiji.plugin.interestpointdetection;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Detection;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;

/**
 * The detection parameters a label stores in its parameters string ({@code InterestPoints.getParameters()}, written by
 * {@link DifferenceOfGaussianGUI#getParameters()} as {@code DOG s=.. t=.. min=.. max=.. downsampleX=.. downsampleY=..
 * downsampleZ=.. anisotropy=.. minIntensity=.. maxIntensity=..} and by {@link ScaleSpaceGUI#getParameters()} as
 * {@code DOG-SS s=.. steps=.. octaves=.. finestLevel=.. t=.. ...}), parsed back so that "Detect Interest Points" can
 * open with them preset ({@link #preset}): the ViewSetup Explorer's "Re-detect interest points with attributes"
 * for labels whose points lack their attributes (response, sigma, intensity: detected before they were stored).
 *
 * Since 2026-10-27 the strings also carry what else decides the detections (DifferenceOfGUI.commonParameters):
 * {@code localization=NONE|QUADRATIC|GAUSS_FIT groupTiles=.. groupIllums=.. thresholdType=fixed|noisefloor
 * [noiseFactor=..]}; a stored value wins over the explorer's current grouping when a label is detected again.
 *
 * Older strings are read, too: the DoG of master ({@code downsampleXYIndex=<factor> downsampleZ=<factor>}, where 0 and
 * -1 meant "match the z resolution" and give no downsampling here) and the first scale-space strings ({@code downsampleXY=}),
 * both without an anisotropy, and BigStitcher-Spark's {@code DOG (Spark) ...} with {@code overlappingOnly=} (since
 * 2026-10-27 the GUI's strings carry it too: "Only in regions overlapping other views", OverlappingRegions). A string
 * without {@code anisotropy=} gets the value its detection effectively used: the DoG of master and
 * Spark's DoG blur isotropically in pixels of the downsampled image, which is an anisotropy of downsampleXY / downsampleZ
 * at full resolution (1 if the factors are unknown); the early scale space used the ratio of the calibration. Whatever
 * else a string lacks keeps its current default. Not in any string: the filter choice (filtered labels have strings of
 * their own), GPU.
 *
 * @param scaleSpace - true for "DOG-SS" ({@link ScaleSpaceGUI}), false for "DOG" ({@link DifferenceOfGaussianGUI})
 * @param sigma - s (the initial blur for the scale space), NaN if absent
 * @param threshold - t, NaN if absent
 * @param findMin - min
 * @param findMax - max
 * @param downsampling - the x, y, z factors the detection ran with, null if absent
 * @param anisotropy - the z anisotropy the detection ran with, NaN if absent
 * @param minIntensity - the lower end of the intensity range, NaN if none was set (the string holds the entered range or the common range the detection computed)
 * @param maxIntensity - the upper end, NaN if none was set
 * @param steps - steps per octave (scale space), -1 if absent
 * @param octaves - octaves (scale space; -1 = as many as the image allows), Integer.MIN_VALUE if absent
 * @param finestLevel - "Finest structures" of the scale space, null if absent
 * @param localization - the subpixel localization (DifferenceOfGUI.localizationChoice: 0 none, 1 quadratic, 2 Gaussian mask), -1 if absent
 * @param groupTiles - whether the tiles were grouped (fused) for the detection, null if absent
 * @param groupIllums - whether the illuminations were grouped, null if absent
 * @param thresholdFromNoiseFloor - whether t was estimated from the noise floor, null if absent (fixed)
 * @param noiseFactor - the factor of that estimate, NaN if absent
 * @param overlappingOnly - whether only the regions overlapping other views were detected in, null if absent
 *
 * @author Stephan Preibisch
 */
public record StoredDetectionParameters(
		boolean scaleSpace,
		double sigma,
		double threshold,
		boolean findMin,
		boolean findMax,
		long[] downsampling,
		double anisotropy,
		double minIntensity,
		double maxIntensity,
		int steps,
		int octaves,
		Boolean finestLevel,
		int localization,
		Boolean groupTiles,
		Boolean groupIllums,
		Boolean thresholdFromNoiseFloor,
		double noiseFactor,
		Boolean overlappingOnly )
{
	/** the first token of the parameters string of the two detection methods */
	public static final String DOG = "DOG", SCALE_SPACE = "DOG-SS";

	/** the index of "Advanced ..." in DifferenceOfGUI.brightnessChoice (the legacy DoG) and in ScaleSpaceGUI.specificationChoice */
	static final int ADVANCED_BRIGHTNESS = 4, ADVANCED_SPECIFICATION = 0;

	/**
	 * @return the parameters of a detection string, null if the string is not one (filtered, copied, thinned-out, manual
	 *         labels and the ones from correspondences have other texts); a value that does not parse counts as absent
	 */
	public static StoredDetectionParameters parse( final String parameters )
	{
		if ( parameters == null )
			return null;

		final String[] tokens = parameters.trim().split( "\\s+" );

		if ( tokens.length == 0 || !( tokens[ 0 ].equals( DOG ) || tokens[ 0 ].equals( SCALE_SPACE ) ) )
			return null;

		final boolean scaleSpace = tokens[ 0 ].equals( SCALE_SPACE );
		final HashMap< String, String > values = new HashMap<>();

		for ( int i = 1; i < tokens.length; ++i )
		{
			final int eq = tokens[ i ].indexOf( '=' );

			if ( eq > 0 )
				values.put( tokens[ i ].substring( 0, eq ), tokens[ i ].substring( eq + 1 ) );
		}

		// the current strings have the three factors; master's DoG had downsampleXYIndex (the factor, or 0/-1 for "match z"), the
		// first scale-space strings downsampleXY next to it
		final long x = longValue( values, "downsampleX", -1 ), y = longValue( values, "downsampleY", -1 ), z = longValue( values, "downsampleZ", -1 );
		final long xy = longValue( values, "downsampleXY", longValue( values, "downsampleXYIndex", -1 ) );

		final long[] downsampling;
		if ( x >= 1 && y >= 1 && z >= 1 )
			downsampling = new long[] { x, y, z };
		else if ( xy >= 1 && z >= 1 )
			downsampling = new long[] { xy, xy, z };
		else
			downsampling = null;

		return new StoredDetectionParameters(
				scaleSpace,
				doubleValue( values, "s" ),
				doubleValue( values, "t" ),
				booleanValue( values, "min", false ),
				booleanValue( values, "max", true ),
				downsampling,
				doubleValue( values, "anisotropy" ),
				doubleValue( values, "minIntensity" ),
				doubleValue( values, "maxIntensity" ),
				(int) longValue( values, "steps", -1 ),
				(int) longValue( values, "octaves", Integer.MIN_VALUE ),
				values.containsKey( "finestLevel" ) ? Boolean.valueOf( values.get( "finestLevel" ) ) : null,
				values.containsKey( "localization" ) ? DifferenceOfGUI.localizationIndex( values.get( "localization" ) ) : -1,
				values.containsKey( "groupTiles" ) ? Boolean.valueOf( values.get( "groupTiles" ) ) : null,
				values.containsKey( "groupIllums" ) ? Boolean.valueOf( values.get( "groupIllums" ) ) : null,
				values.containsKey( "thresholdType" ) ? Boolean.valueOf( "noisefloor".equals( values.get( "thresholdType" ) ) ) : null,
				doubleValue( values, "noiseFactor" ),
				values.containsKey( "overlappingOnly" ) ? Boolean.valueOf( values.get( "overlappingOnly" ) ) : null );
	}

	/** @return whether the string says how tiles and illuminations were grouped (then that wins over the explorer's grouping when the label is detected again) */
	public boolean hasGrouping() { return groupTiles != null && groupIllums != null; }

	/**
	 * @return the anisotropy to detect with: the stored one, or for a string without it the value its detection
	 *         effectively used (see the class comment): the calibration ratio of the view for the early scale space,
	 *         downsampleXY / downsampleZ for the DoG of master and of BigStitcher-Spark (1 if the factors are unknown)
	 */
	public double effectiveAnisotropy( final double calibrationAnisotropy )
	{
		if ( !Double.isNaN( anisotropy ) )
			return anisotropy;

		if ( scaleSpace )
			return calibrationAnisotropy;

		return downsampling == null ? 1.0 : (double) downsampling[ 0 ] / downsampling[ 2 ];
	}

	/**
	 * Sets the defaults of "Detect Interest Points" ({@link Interest_Point_Detection} and the method GUIs) to these
	 * parameters and the label, so that the dialog opens with them: the method, the label (an existing one is replaced by
	 * the detection), the resolution (the matching level of the first view, else a manual entry of the factors), the
	 * anisotropy ({@link #effectiveAnisotropy}, with the first view's calibration, which the dialog compares before it
	 * estimates a default of its own), the intensity range if one is stored, the subpixel localization, the grouping of
	 * tiles and illuminations, whether only the regions overlapping other views are detected in (if the string says), the
	 * threshold type with its noise factor (fixed if the string has none), no filter, and the method's "Advanced" specification with its sigma, threshold, minima/maxima and, for the scale space, steps, octaves
	 * and finest level.
	 *
	 * @param data - the dataset
	 * @param views - the views the detection will run on (the first gives the resolution choices and the calibration), may be empty
	 * @param label - the label to detect (into)
	 */
	public void preset( final SpimData2 data, final List< ? extends ViewId > views, final String label )
	{
		Interest_Point_Detection.defaultLabel = label;
		Interest_Point_Detection.defaultAlgorithm = scaleSpace ? 1 : 0;

		final boolean minMax = !Double.isNaN( minIntensity ) && !Double.isNaN( maxIntensity );
		Interest_Point_Detection.defaultSetMinMax = minMax;

		if ( minMax )
		{
			DifferenceOfGUI.defaultMinIntensity = minIntensity;
			DifferenceOfGUI.defaultMaxIntensity = maxIntensity;
		}

		final ViewId first = views == null || views.isEmpty() ? null : views.get( 0 );

		if ( downsampling != null && first != null )
		{
			final String[] resolutions = DownsampleTools.availableDownsamplings( data, first );
			int index = -1;

			for ( int i = 0; i < resolutions.length && index < 0; ++i )
				if ( Arrays.equals( downsampling, DownsampleTools.parseDownsampleChoice( resolutions[ i ] ) ) )
					index = i;

			if ( index >= 0 )
			{
				DifferenceOfGUI.defaultResolutionIndex = index;
				DifferenceOfGUI.defaultManual = false;
			}
			else
			{
				DifferenceOfGUI.defaultManual = true;
				DifferenceOfGUI.defaultManualDownsampling = downsampling.clone();
			}
		}

		if ( first != null )
		{
			final double calibration = DifferenceOfGUI.calibrationAnisotropy( data.getSequenceDescription().getViewDescription( first ) );
			DifferenceOfGUI.defaultAnisotropyZ = effectiveAnisotropy( calibration );
			DifferenceOfGUI.defaultAnisotropyCalibration = calibration;
		}

		if ( localization >= 0 )
			DifferenceOfGUI.defaultLocalization = localization;

		if ( hasGrouping() )
		{
			Interest_Point_Detection.defaultGroupTiles = groupTiles;
			Interest_Point_Detection.defaultGroupIllums = groupIllums;
		}

		DifferenceOfGUI.defaultThresholdChoice = thresholdFromNoiseFloor != null && thresholdFromNoiseFloor ? 1 : 0;
		if ( !Double.isNaN( noiseFactor ) )
			DifferenceOfGUI.defaultNoiseFactor = noiseFactor;
		DifferenceOfGUI.defaultFilterChoice = DifferenceOfGUI.filterChoice.length - 1;

		if ( overlappingOnly != null )
			DifferenceOfGUI.defaultOverlappingOnly = overlappingOnly;

		if ( scaleSpace )
		{
			if ( !Double.isNaN( sigma ) )
				ScaleSpaceGUI.defaultSigma = sigma;
			if ( !Double.isNaN( threshold ) )
				ScaleSpaceGUI.defaultThreshold = threshold;
			if ( steps >= 1 )
				ScaleSpaceGUI.defaultSteps = steps;
			if ( octaves != Integer.MIN_VALUE )
				ScaleSpaceGUI.defaultOctaves = octaves;
			if ( finestLevel != null )
				ScaleSpaceGUI.lastDetectFinestLevel = finestLevel;

			ScaleSpaceGUI.defaultFindMin = findMin;
			ScaleSpaceGUI.defaultFindMax = findMax;
			ScaleSpaceGUI.defaultSpecification = ADVANCED_SPECIFICATION;
		}
		else
		{
			if ( !Double.isNaN( sigma ) )
				DifferenceOfGaussianGUI.defaultSigma = sigma;
			if ( !Double.isNaN( threshold ) )
				DifferenceOfGaussianGUI.defaultThreshold = threshold;

			DifferenceOfGaussianGUI.defaultFindMin = findMin;
			DifferenceOfGaussianGUI.defaultFindMax = findMax;
			DifferenceOfGUI.defaultBrightness = ADVANCED_BRIGHTNESS;
		}
	}

	static double doubleValue( final Map< String, String > values, final String key )
	{
		try
		{
			return values.containsKey( key ) ? Double.parseDouble( values.get( key ) ) : Double.NaN;
		}
		catch ( final NumberFormatException e )
		{
			return Double.NaN;
		}
	}

	static long longValue( final Map< String, String > values, final String key, final long fallback )
	{
		try
		{
			return values.containsKey( key ) ? Long.parseLong( values.get( key ) ) : fallback;
		}
		catch ( final NumberFormatException e )
		{
			return fallback;
		}
	}

	static boolean booleanValue( final Map< String, String > values, final String key, final boolean fallback )
	{
		return values.containsKey( key ) ? Boolean.parseBoolean( values.get( key ) ) : fallback;
	}

	@Override
	public String toString()
	{
		return ( scaleSpace ? SCALE_SPACE : DOG ) + " s=" + sigma + " t=" + threshold + " min=" + findMin + " max=" + findMax +
				( scaleSpace ? " steps=" + steps + " octaves=" + octaves + " finestLevel=" + finestLevel : "" ) +
				" downsampling=" + ( downsampling == null ? "?" : Arrays.toString( downsampling ) ) + " anisotropy=" + anisotropy +
				" intensities=" + minIntensity + ".." + maxIntensity +
				" localization=" + ( localization < 0 ? "?" : DifferenceOfGUI.localizationKey( localization ) ) + " groupTiles=" + groupTiles + " groupIllums=" + groupIllums +
				" thresholdType=" + ( thresholdFromNoiseFloor == null ? "?" : thresholdFromNoiseFloor ? "noisefloor (factor " + noiseFactor + ")" : "fixed" ) +
				" overlappingOnly=" + ( overlappingOnly == null ? "?" : overlappingOnly );
	}
}
