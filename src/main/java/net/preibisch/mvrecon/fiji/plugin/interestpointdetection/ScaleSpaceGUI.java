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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveDoG;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveDoGParams;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpace;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceDetectionParameters;
import net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace.ScaleSpaceParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * The scale-space Difference-of-Gaussian as a second detection method next to the single-scale
 * Difference-of-Gaussian: sigma is the finest scale, the threshold the minimal response at any scale.
 *
 * @author Stephan Preibisch
 */
public class ScaleSpaceGUI extends DifferenceOfGUI
{
	// the defaults of the scale space are defined in ScaleSpaceParameters only
	private static final ScaleSpaceParameters defaults = new ScaleSpaceParameters();

	public static double defaultSigma = defaults.sigmaMin;
	public static double defaultThreshold = defaults.threshold;
	public static boolean defaultFindMin = defaults.findMin;
	public static boolean defaultFindMax = defaults.findMax;
	public static int defaultSteps = defaults.steps;
	public static int defaultOctaves = defaults.octaves;
	public static boolean defaultDetectFinestLevel = defaults.detectFinestLevel;

	// the starting resolution: a precomputed resolution level (by default the second one) or manually typed factors
	public static final String manualResolution = "Manually (powers of two) ...";
	public static int defaultResolutionIndex = 1;
	public static boolean defaultManual = false;
	public static long[] defaultManualDownsampling = null;

	protected double sigma;
	protected double threshold;
	protected boolean findMin;
	protected boolean findMax;

	protected int steps;
	protected int octaves;
	protected boolean detectFinestLevel;

	/** the starting resolution (octave 0) as downsampling in x, y, z */
	protected long[] downsampling;

	/** the precomputed resolution levels of the first view ("fx, fy, fz"), the entries of the drop-down */
	protected String[] resolutions;

	public ScaleSpaceGUI( final SpimData2 spimData, final List< ViewId > viewIdsToProcess )
	{
		super( spimData, viewIdsToProcess );
	}

	@Override
	public String getDescription() { return "Scale-space Difference-of-Gaussian"; }

	@Override
	public Map<String,String> describeParameters()
	{
		final LinkedHashMap<String,String> p = new LinkedHashMap<>();
		// not supported by BigStitcher-Spark yet, ActionToSparkCli skips the detection step with a warning
		p.put( "detectionMethod", "SCALE_SPACE" );
		p.put( "sigma", Double.toString( sigma ) );
		p.put( "threshold", Double.toString( threshold ) );
		p.put( "steps", Integer.toString( steps ) );
		p.put( "octaves", Integer.toString( octaves ) );
		p.put( "finestLevel", Boolean.toString( detectFinestLevel ) );
		if ( findMin && findMax )
			p.put( "type", "BOTH" );
		else if ( findMax )
			p.put( "type", "MAX" );
		else if ( findMin )
			p.put( "type", "MIN" );
		p.put( "localization", localization == 0 ? "NONE" : "QUADRATIC" );
		if ( !Double.isNaN( minIntensity ) )
			p.put( "minIntensity", Double.toString( minIntensity ) );
		if ( !Double.isNaN( maxIntensity ) )
			p.put( "maxIntensity", Double.toString( maxIntensity ) );
		// Spark knows one factor for x and y (-dsxy)
		if ( downsampling[ 0 ] == downsampling[ 1 ] )
			p.put( "downsampleXY", Long.toString( downsampling[ 0 ] ) );
		else
		{
			p.put( "downsampleX", Long.toString( downsampling[ 0 ] ) );
			p.put( "downsampleY", Long.toString( downsampling[ 1 ] ) );
		}
		p.put( "downsampleZ", Long.toString( downsampling[ 2 ] ) );
		if ( limitDetections )
		{
			final String mode;
			if ( maxDetectionsTypeIndex == 0 ) mode = "BRIGHTEST";
			else if ( maxDetectionsTypeIndex == 1 ) mode = "AROUND_MEDIAN";
			else mode = "WEAKEST";
			p.put( "limitDetectionsMode", mode );
			p.put( "maxDetections", Integer.toString( maxDetections ) );
			if ( maxDetectionsTypeIndex == 0 )
				p.put( "maxSpots", Integer.toString( maxDetections ) );
		}
		return p;
	}

	@Override
	public ScaleSpaceGUI newInstance( final SpimData2 spimData, final List< ViewId > viewIdsToProcess )
	{
		return new ScaleSpaceGUI( spimData, viewIdsToProcess );
	}

	@Override
	public HashMap< ViewId, List< InterestPoint > > findInterestPoints( final TimePoint t )
	{
		final ScaleSpaceDetectionParameters p = new ScaleSpaceDetectionParameters();

		p.imgloader = spimData.getSequenceDescription().getImgLoader();
		p.toProcess = new ArrayList< ViewDescription >();

		// the starting resolution (the inherited downsampleXY/downsampleZ are not used by the scale space)
		p.downsampling = this.downsampling.clone();
		p.downsampleXY = (int)downsampling[ 0 ];
		p.downsampleZ = (int)downsampling[ 2 ];

		p.minIntensity = this.minIntensity;
		p.maxIntensity = this.maxIntensity;

		p.limitDetections = this.limitDetections;
		p.maxDetections = this.maxDetections;
		p.maxDetectionsTypeIndex = this.maxDetectionsTypeIndex;

		p.scaleSpace.sigmaMin = this.sigma;
		p.scaleSpace.threshold = this.threshold;
		p.scaleSpace.findMin = this.findMin;
		p.scaleSpace.findMax = this.findMax;
		p.scaleSpace.localization = this.localization;
		p.scaleSpace.steps = this.steps;
		p.scaleSpace.octaves = this.octaves;
		p.scaleSpace.detectFinestLevel = this.detectFinestLevel;

		final HashMap< ViewId, List< InterestPoint > > interestPoints = new HashMap< ViewId, List< InterestPoint > >();

		for ( final ViewDescription vd : SpimData2.getAllViewIdsForTimePointSorted( spimData, viewIdsToProcess, t ) )
		{
			// make sure not everything crashes if one file is missing
			try
			{
				if ( !vd.isPresent() )
					continue;

				p.toProcess.clear();
				p.toProcess.add( vd );

				ScaleSpace.addInterestPoints( interestPoints, p );
			}
			catch ( Exception  e )
			{
				IOFunctions.println( "An error occured (DOG-SS): " + e ); 
				IOFunctions.println( "Failed to segment viewId: " + Group.pvid( vd ) + ". Continuing with next one." );
				e.printStackTrace();
			}
		}

		return interestPoints;
	}

	@Override
	protected boolean setDefaultValues( final int brightness )
	{
		this.sigma = defaultSigma;
		this.findMin = false;
		this.findMax = true;

		if ( brightness == 0 )
			this.threshold = 0.001;
		else if ( brightness == 1 )
			this.threshold = 0.008;
		else if ( brightness == 2 )
			this.threshold = 0.03;
		else if ( brightness == 3 )
			this.threshold = 0.1;
		else
			return false;

		return true;
	}

	@Override
	protected boolean setAdvancedValues()
	{
		final GenericDialog gd = new GenericDialog( "Advanced values" );

		gd.addNumericField( "Sigma (finest scale)", defaultSigma, 5 );
		gd.addNumericField( "Threshold (minimal response)", defaultThreshold, 5 );
		gd.addCheckbox( "Find_minima", defaultFindMin );
		gd.addCheckbox( "Find_maxima", defaultFindMax );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return false;

		this.sigma = defaultSigma = gd.getNextNumber();
		this.threshold = defaultThreshold = gd.getNextNumber();
		this.findMin = defaultFindMin = gd.getNextBoolean();
		this.findMax = defaultFindMax = gd.getNextBoolean();

		return true;
	}

	/**
	 * The interactive single-scale DoG at the finest scale (these are exactly the detections of the
	 * finest level), to pick sigma and threshold
	 */
	@Override
	protected boolean setInteractiveValues()
	{
		final ImagePlus imp;

		if ( !groupIllums && !groupTiles )
			imp = getImagePlusForInteractive( "Interactive Difference-of-Gaussian (finest scale)" );
		else
			imp = getGroupedImagePlusForInteractive( "Interactive Difference-of-Gaussian (finest scale)" );

		if ( imp == null )
			return false;

		imp.setDimensions( 1, imp.getStackSize(), 1 );
		imp.show();
		imp.setSlice( imp.getStackSize() / 2 );
		imp.setRoi( 0, 0, imp.getWidth()/3, imp.getHeight()/3 );

		final InteractiveDoGParams params = new InteractiveDoGParams();
		params.sigma = (float)defaultSigma;
		params.threshold = (float)defaultThreshold;
		params.findMaxima = defaultFindMax;
		params.findMinima = defaultFindMin;

		final double min, max;

		if ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) )
		{
			min = imp.getDisplayRangeMin();
			max = imp.getDisplayRangeMax();

			IOFunctions.println( "(" + new Date(System.currentTimeMillis() ) + "): Using approximate min [" + min + "]/max[" + max + "] intensity values ... to have a more accurate preview your can manually set min/max intensity." );
		}
		else
		{
			min = minIntensity;
			max = maxIntensity;
		}

		final InteractiveDoG idog = new InteractiveDoG( imp, params, min, max );
		do
		{
			try
			{
				Thread.sleep( 100 );
			} catch (InterruptedException e) {}
		}
		while (!idog.isFinished());

		imp.close();

		if (idog.wasCanceled())
			return false;

		this.sigma = defaultSigma = params.sigma;
		this.threshold = defaultThreshold = params.threshold;
		this.findMax = defaultFindMax = params.findMaxima;
		this.findMin = defaultFindMin = params.findMinima;

		return true;
	}

	@Override
	public String getParameters()
	{
		return "DOG-SS s=" + sigma + " steps=" + steps + " octaves=" + octaves + " finestLevel=" + detectFinestLevel + " t=" + threshold + " min=" + findMin + " max=" + findMax +
				" downsampleX=" + downsampling[ 0 ] + " downsampleY=" + downsampling[ 1 ] + " downsampleZ=" + downsampling[ 2 ] +
				" minIntensity=" + minIntensity + " maxIntensity=" + maxIntensity;
	}

	/**
	 * The starting resolution: a drop-down of the precomputed resolution levels plus the manual entry
	 */
	@Override
	protected void addDownsamplingParameters( final GenericDialog gd )
	{
		final ViewId firstView = viewIdsToProcess.get( 0 );

		resolutions = DownsampleTools.availableDownsamplings( spimData, firstView );

		if ( !sameResolutionLevels( spimData, viewIdsToProcess ) )
		{
			IOFunctions.println( "WARNING: The resolution levels differ between the views to process, listing those of " + Group.pvid( firstView ) + ". Each view is opened at its closest level with factors <= the chosen ones." );
			gd.addMessage( "The resolution levels differ between the views, listing those of the first view", GUIHelper.smallStatusFont, GUIHelper.warning );
		}

		final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( firstView.getTimePointId(), firstView.getViewSetupId() );
		final VoxelDimensions voxelSize = vd.getViewSetup().hasVoxelSize() ? vd.getViewSetup().getVoxelSize() : null;
		final String[] choices = resolutionChoices( resolutions, voxelSize );

		gd.addChoice( "Starting_resolution (downsampling x, y, z)", choices, choices[ defaultResolutionChoice( resolutions.length, defaultResolutionIndex, defaultManual ) ] );
	}

	@Override
	protected boolean queryDownsamplingParameters( final GenericDialog gd )
	{
		final int choice = gd.getNextChoiceIndex();

		if ( choice == resolutions.length )
		{
			// the manual entry, pre-filled with the last manual choice or the default level
			if ( defaultManualDownsampling == null )
				defaultManualDownsampling = DownsampleTools.parseDownsampleChoice( resolutions[ defaultResolutionChoice( resolutions.length, defaultResolutionIndex, false ) ] );

			final GenericDialog gdManual = new GenericDialog( "Starting resolution" );

			gdManual.addMessage( "Downsampling of the image that the scale space starts at (powers of two)", GUIHelper.smallStatusFont );
			gdManual.addNumericField( "Downsample_X", defaultManualDownsampling[ 0 ], 0 );
			gdManual.addNumericField( "Downsample_Y", defaultManualDownsampling[ 1 ], 0 );
			gdManual.addNumericField( "Downsample_Z", defaultManualDownsampling[ 2 ], 0 );

			gdManual.showDialog();

			if ( gdManual.wasCanceled() )
				return false;

			final long[] ds = new long[ 3 ];

			for ( int d = 0; d < 3; ++d )
			{
				ds[ d ] = Math.round( gdManual.getNextNumber() );

				if ( !isPowerOfTwo( ds[ d ] ) )
				{
					IOFunctions.println( "ERROR: The downsampling factors must be powers of two >= 1, but the factor for dimension " + d + " is " + ds[ d ] + "." );
					return false;
				}
			}

			downsampling = ds;
			defaultManualDownsampling = ds.clone();
			defaultManual = true;
		}
		else
		{
			downsampling = DownsampleTools.parseDownsampleChoice( resolutions[ choice ] );
			defaultResolutionIndex = choice;
			defaultManual = false;

			for ( int d = 0; d < 3; ++d )
				if ( !isPowerOfTwo( downsampling[ d ] ) )
					IOFunctions.println( "WARNING: The resolution level (" + resolutions[ choice ] + ") is not a power of two, the closest level with powers of two <= these factors is used." );
		}

		IOFunctions.println( "Scale space: starting resolution (downsampling x, y, z) = " + Util.printCoordinates( downsampling ) );

		// the interactive previews (DifferenceOfGUI) open the views with these
		downsampleXYIndex = (int)downsampling[ 0 ];
		downsampleZ = (int)downsampling[ 2 ];

		return true;
	}

	/**
	 * @return the entries of the drop-down: one per precomputed resolution level ("fx, fy, fz" plus the
	 * resulting voxel size if known) and the manual entry last
	 */
	public static String[] resolutionChoices( final String[] resolutions, final VoxelDimensions voxelSize )
	{
		final String[] choices = new String[ resolutions.length + 1 ];

		for ( int i = 0; i < resolutions.length; ++i )
		{
			choices[ i ] = resolutions[ i ];

			if ( voxelSize != null )
			{
				final long[] ds = DownsampleTools.parseDownsampleChoice( resolutions[ i ] );

				choices[ i ] += "  (" + round( ds[ 0 ] * voxelSize.dimension( 0 ) ) + " x " + round( ds[ 1 ] * voxelSize.dimension( 1 ) ) + " x " + round( ds[ 2 ] * voxelSize.dimension( 2 ) ) + " " + voxelSize.unit() + ")";
			}
		}

		choices[ resolutions.length ] = manualResolution;

		return choices;
	}

	/**
	 * @return the index of the default entry: the manual entry if it was used last, otherwise the last
	 * used level (initially the second one), limited to the levels that exist
	 */
	public static int defaultResolutionChoice( final int numResolutions, final int defaultIndex, final boolean manual )
	{
		if ( manual )
			return numResolutions;
		else
			return Math.max( 0, Math.min( defaultIndex, numResolutions - 1 ) );
	}

	/**
	 * @return whether all views have the same precomputed resolution levels (true if not multi-resolution)
	 */
	public static boolean sameResolutionLevels( final SpimData2 spimData, final List< ViewId > views )
	{
		if ( !MultiResolutionImgLoader.class.isInstance( spimData.getSequenceDescription().getImgLoader() ) )
			return true;

		final MultiResolutionImgLoader loader = (MultiResolutionImgLoader)spimData.getSequenceDescription().getImgLoader();
		final double[][] first = loader.getSetupImgLoader( views.get( 0 ).getViewSetupId() ).getMipmapResolutions();

		for ( final ViewId view : views )
			if ( !Arrays.deepEquals( first, loader.getSetupImgLoader( view.getViewSetupId() ).getMipmapResolutions() ) )
				return false;

		return true;
	}

	protected static boolean isPowerOfTwo( final long value )
	{
		return value >= 1 && ( value & ( value - 1 ) ) == 0;
	}

	protected static double round( final double value )
	{
		return Math.round( value * 100.0 ) / 100.0;
	}

	@Override
	protected void addAddtionalParameters( final GenericDialog gd )
	{
		gd.addMessage( "Scale space: sigma is the finest scale, the threshold the minimal |response| at any scale", GUIHelper.smallStatusFont );
		gd.addNumericField( "Steps_per_octave", defaultSteps, 0 );
		gd.addNumericField( "Octaves (-1 = as many as the image allows)", defaultOctaves, 0 );
		gd.addCheckbox( "Detect_finest_level (also keep all single-scale detections at sigma)", defaultDetectFinestLevel );
	}

	@Override
	protected boolean queryAdditionalParameters( final GenericDialog gd )
	{
		this.steps = defaultSteps = (int)Math.round( gd.getNextNumber() );
		this.octaves = defaultOctaves = (int)Math.round( gd.getNextNumber() );
		this.detectFinestLevel = defaultDetectFinestLevel = gd.getNextBoolean();

		if ( steps < 1 )
		{
			IOFunctions.println( "Scale space: the steps per octave must be >= 1." );
			return false;
		}

		if ( octaves == 0 || octaves < -1 )
		{
			IOFunctions.println( "Scale space: the number of octaves must be -1 (as many as the image allows) or >= 1." );
			return false;
		}

		if ( localization == 2 )
		{
			IOFunctions.println( "Scale space: the Gaussian mask localization is not supported, using the quadratic fit in space and scale." );
			localization = defaultLocalization = 1;
		}

		if ( imageSigmaX != 0.5 || imageSigmaY != 0.5 || imageSigmaZ != 0.5 )
			IOFunctions.println( "Scale space: the image sigmas are ignored, the scale space assumes an isotropic image blur of " + defaults.imageSigma + " px." );

		return true;
	}
}
