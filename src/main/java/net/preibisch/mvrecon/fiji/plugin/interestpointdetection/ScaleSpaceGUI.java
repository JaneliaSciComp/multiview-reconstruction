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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveScaleSpace;
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
 * Difference-of-Gaussian: the initial blur (sigma, Lowe: 1.6 pixels at the starting resolution) is the
 * finest scale, the threshold the minimal response at any scale. The main dialog asks for the starting
 * resolution, the anisotropy and the initial blur (queried before the brightness is dispatched, so that
 * the interactive preview, {@link InteractiveScaleSpace}, knows them); threshold(s), steps per octave, octaves,
 * finest structures and minima/maxima are set in the preview or the "Advanced ..." dialog, the only two entries
 * of "Interest_point_specification" (no presets). "Number_of_thresholds" in the Advanced dialog asks for several
 * thresholds, which give one label per threshold ("label_t0.004", ...) from a single computation per view
 * (ScaleSpace.findInterestPoints with a list of thresholds).
 *
 * @author Stephan Preibisch
 */
public class ScaleSpaceGUI extends DifferenceOfGUI
{
	// the defaults of the scale space are defined in ScaleSpaceParameters only
	private static final ScaleSpaceParameters defaults = new ScaleSpaceParameters();

	public static double defaultSigma = defaults.sigmaMin;
	public static double defaultThreshold = defaults.threshold;
	/** the entries of "Interest_point_specification" for the scale space: no presets */
	public static final String[] specificationChoice = new String[] { "Advanced ...", "Interactive ..." };
	public static int defaultSpecification = 1;
	public static boolean defaultFindMin = defaults.findMin;
	public static boolean defaultFindMax = defaults.findMax;
	public static int defaultSteps = defaults.steps;
	public static int defaultOctaves = defaults.octaves;
	/** the finest structures as last chosen in the interactive preview or the advanced dialog, null = not chosen yet (then off at full resolution, on otherwise, see defaultDetectFinestLevel) */
	public static Boolean lastDetectFinestLevel = null;

	// the starting resolution: a precomputed resolution level (by default the second one) or manually typed factors
	public static final String manualResolution = "Manually (powers of two) ...";
	public static int defaultResolutionIndex = 1;
	public static boolean defaultManual = false;
	public static long[] defaultManualDownsampling = null;

	// anisotropy at full resolution (z voxel / x voxel), by default the calibration ratio; remembered while the calibration is the same
	public static double defaultAnisotropyZ = Double.NaN;
	public static double defaultAnisotropyCalibration = Double.NaN;

	protected double sigma;
	protected boolean findMin;
	protected boolean findMax;

	protected int steps;
	protected int octaves;
	protected boolean detectFinestLevel;

	/** the starting resolution (octave 0) as downsampling in x, y, z */
	protected long[] downsampling;

	/** z voxel / x voxel at full resolution (increase if the PSF blurs z more); converted to the starting resolution per view */
	protected double anisotropyZ;

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
		p.put( "anisotropy", Double.toString( anisotropyZ ) );
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

	/**
	 * The detections of all thresholds from one computation per view, see ScaleSpace.findInterestPoints( p, thresholds )
	 */
	@Override
	public LinkedHashMap< String, HashMap< ViewId, List< InterestPoint > > > findInterestPointsPerSuffix( final TimePoint t )
	{
		final ScaleSpaceDetectionParameters p = detectionParameters();
		final double[] thresholds = thresholds();

		final ArrayList< HashMap< ViewId, List< InterestPoint > > > perThreshold = new ArrayList<>();

		for ( int i = 0; i < thresholds.length; ++i )
			perThreshold.add( new HashMap<>() );

		for ( final ViewDescription vd : SpimData2.getAllViewIdsForTimePointSorted( spimData, viewIdsToProcess, t ) )
		{
			// make sure not everything crashes if one file is missing
			try
			{
				if ( !vd.isPresent() )
					continue;

				p.toProcess.clear();
				p.toProcess.add( vd );

				ScaleSpace.addInterestPoints( perThreshold, p, thresholds );
			}
			catch ( Exception  e )
			{
				IOFunctions.println( "An error occured (DOG-SS): " + e ); 
				IOFunctions.println( "Failed to segment viewId: " + Group.pvid( vd ) + ". Continuing with next one." );
				e.printStackTrace();
			}
		}

		return perSuffix( perThreshold );
	}

	/**
	 * The detections at the first threshold (all of them with a single threshold), see findInterestPointsPerSuffix
	 */
	@Override
	public HashMap< ViewId, List< InterestPoint > > findInterestPoints( final TimePoint t )
	{
		return findInterestPointsPerSuffix( t ).values().iterator().next();
	}

	/**
	 * @return the parameters of the detection as set in the dialogs (the threshold is set per run by the caller)
	 */
	protected ScaleSpaceDetectionParameters detectionParameters()
	{
		final ScaleSpaceDetectionParameters p = new ScaleSpaceDetectionParameters();

		p.imgloader = spimData.getSequenceDescription().getImgLoader();
		p.toProcess = new ArrayList< ViewDescription >();

		// the starting resolution (the inherited downsampleXY/downsampleZ are not used by the scale space)
		p.downsampling = this.downsampling.clone();
		p.downsampleXY = (int)downsampling[ 0 ];
		p.downsampleZ = (int)downsampling[ 2 ];
		p.anisotropyZ = this.anisotropyZ;

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

		return p;
	}

	/**
	 * The scale space has no presets: "Advanced ..." or "Interactive ..." only, see specificationChoices
	 */
	@Override
	protected String[] specificationChoices() { return specificationChoice; }

	@Override
	protected int defaultSpecificationIndex() { return defaultSpecification; }

	@Override
	protected void setDefaultSpecificationIndex( final int index ) { defaultSpecification = index; }

	@Override
	protected boolean dispatchSpecification( final int choice )
	{
		return choice == 0 ? setAdvancedValues() : setInteractiveValues();
	}

	/**
	 * No presets for the scale space (see specificationChoices)
	 */
	@Override
	protected boolean setDefaultValues( final int brightness ) { return false; }

	/**
	 * Everything the interactive preview can set, as a dialog. "Number_of_thresholds" asks for several thresholds in a
	 * second dialog (after one for N if chosen), which give one label per threshold from a single computation; the
	 * single "Threshold" is then not used.
	 */
	@Override
	protected boolean setAdvancedValues()
	{
		final GenericDialog gd = new GenericDialog( "Advanced values" );

		gd.addChoice( "Number_of_thresholds (several give one label per threshold)", thresholdCountChoice, thresholdCountChoice[ defaultThresholdCountChoice ] );
		gd.addNumericField( "Threshold (minimal |response| at any scale)", defaultThreshold, 5 );
		gd.addNumericField( "Steps_per_octave", defaultSteps, 0 );
		gd.addNumericField( "Octaves (-1 = as many as the image allows)", defaultOctaves, 0 );
		gd.addCheckbox( "Finest_structures (also keep the extrema at the initial blur that are no extremum in scale)", defaultDetectFinestLevel( downsampling ) );
		gd.addCheckbox( "Find_minima", defaultFindMin );
		gd.addCheckbox( "Find_maxima", defaultFindMax );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return false;

		final int countChoice = gd.getNextChoiceIndex();
		this.threshold = gd.getNextNumber();
		this.steps = (int)Math.round( gd.getNextNumber() );
		this.octaves = (int)Math.round( gd.getNextNumber() );
		this.detectFinestLevel = gd.getNextBoolean();
		this.findMin = gd.getNextBoolean();
		this.findMax = gd.getNextBoolean();

		if ( !( threshold > 0 ) || Double.isInfinite( threshold ) )
		{
			IOFunctions.println( "Scale space: the threshold must be > 0, but is " + threshold + "." );
			return false;
		}

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

		defaultThreshold = threshold;
		defaultSteps = steps;
		defaultOctaves = octaves;
		lastDetectFinestLevel = detectFinestLevel;
		defaultFindMin = findMin;
		defaultFindMax = findMax;

		// several thresholds: one label per threshold from a single computation per view
		return queryMultipleThresholds( countChoice );
	}

	/**
	 * The interactive scale-space preview (InteractiveScaleSpace) on one view at the starting resolution,
	 * to pick the threshold, the finest structures, the sign, the steps per octave, the octaves and (by opening
	 * previews at other resolutions) the starting resolution; the initial blur and the anisotropy are the ones of the dialog
	 */
	@Override
	protected boolean setInteractiveValues()
	{
		final ViewId viewId = getViewSelection( "Interactive scale space", "Please select view to use" );

		if ( viewId == null )
			return false;

		if ( groupIllums || groupTiles )
			IOFunctions.println( "Scale space: the interactive preview always shows a single view (the detection groups the views as selected)." );

		// the intensity range: the user's, the same for all views if requested, otherwise every image (the preview's
		// and each view's in the detection) computes its own
		if ( sameMinMax || groupIllums || groupTiles )
			preprocess();

		final ScaleSpaceParameters p = new ScaleSpaceParameters();
		p.sigmaMin = sigma;
		p.steps = steps;
		p.octaves = octaves;
		p.localization = localization;
		p.threshold = defaultThreshold;
		p.findMin = defaultFindMin;
		p.findMax = defaultFindMax;
		p.detectFinestLevel = detectFinestLevel;
		p.minIntensity = minIntensity;
		p.maxIntensity = maxIntensity;

		final InteractiveScaleSpace.Session session = new InteractiveScaleSpace.Session( spimData, spimData.getSequenceDescription().getViewDescription( viewId ), resolutions, p, anisotropyZ );

		session.open( downsampling, defaultManual ? -1 : defaultResolutionIndex );
		session.waitUntilFinished();

		if ( session.isCancelled() )
			return false;

		this.threshold = defaultThreshold = session.getThreshold();
		this.thresholds = new double[] { threshold };
		this.detectFinestLevel = lastDetectFinestLevel = session.getDetectFinestLevel();
		this.findMin = defaultFindMin = session.getFindMin();
		this.findMax = defaultFindMax = session.getFindMax();
		this.steps = defaultSteps = session.getSteps();
		this.octaves = defaultOctaves = session.getOctaves();

		// the starting resolution of the window Done was pressed in
		this.downsampling = session.getDownsampling().clone();

		if ( session.getResolutionIndex() < 0 )
		{
			defaultManual = true;
			defaultManualDownsampling = downsampling.clone();
		}
		else
		{
			defaultManual = false;
			defaultResolutionIndex = session.getResolutionIndex();
		}

		downsampleXYIndex = (int)downsampling[ 0 ];
		downsampleZ = (int)downsampling[ 2 ];

		IOFunctions.println( "Scale space: starting resolution (downsampling x, y, z) = " + Util.printCoordinates( downsampling ) + " (interactive)" );

		return true;
	}

	/**
	 * @return the parameter string stored with a label, with this threshold
	 */
	@Override
	protected String parameters( final double threshold )
	{
		return "DOG-SS s=" + sigma + " steps=" + steps + " octaves=" + octaves + " finestLevel=" + detectFinestLevel + " t=" + threshold + " min=" + findMin + " max=" + findMax +
				" downsampleX=" + downsampling[ 0 ] + " downsampleY=" + downsampling[ 1 ] + " downsampleZ=" + downsampling[ 2 ] + " anisotropy=" + anisotropyZ +
				" minIntensity=" + minIntensity + " maxIntensity=" + maxIntensity;
	}

	/**
	 * The starting resolution (a drop-down of the precomputed resolution levels plus the manual entry), the
	 * anisotropy and the initial blur: they are added (and queried) here because the "Advanced ..." and
	 * "Interactive ..." sub-dialogs, which DifferenceOfGUI opens right after the downsampling has been
	 * queried, need them (the additional parameters are only queried afterwards).
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

		final double calibration = calibrationAnisotropy( voxelSize );

		if ( Double.isNaN( defaultAnisotropyZ ) || defaultAnisotropyCalibration != calibration )
		{
			defaultAnisotropyZ = calibration;
			defaultAnisotropyCalibration = calibration;
		}

		gd.addNumericField( "Anisotropy_z (z voxel / xy voxel at full resolution)", defaultAnisotropyZ, 3 );

		gd.addMessage( "Scale space: the initial blur is the finest scale (Lowe: 1.6), the threshold the minimal |response| at any scale", GUIHelper.smallStatusFont );
		gd.addNumericField( "Initial_blur sigma (px at the starting resolution, Lowe: 1.6)", defaultSigma, 3 );
	}

	/**
	 * @return the initial state of the finest structures (detectFinestLevel) for a starting resolution: the state last
	 * chosen in the interactive preview if there is one, otherwise off at full resolution (the structures are resolved
	 * there) and on at any downsampling
	 */
	public static boolean defaultDetectFinestLevel( final long[] downsampling )
	{
		if ( lastDetectFinestLevel != null )
			return lastDetectFinestLevel;

		for ( final long d : downsampling )
			if ( d > 1 )
				return true;

		return false;
	}

	/**
	 * @return z voxel / x voxel of the calibration (1 if unknown)
	 */
	public static double calibrationAnisotropy( final VoxelDimensions voxelSize )
	{
		if ( voxelSize == null )
			return 1.0;
		else
			return voxelSize.dimension( 2 ) / voxelSize.dimension( 0 );
	}

	@Override
	protected boolean queryDownsamplingParameters( final GenericDialog gd )
	{
		final int choice = gd.getNextChoiceIndex();

		this.anisotropyZ = gd.getNextNumber();

		if ( !( anisotropyZ > 0 ) || Double.isInfinite( anisotropyZ ) )
		{
			IOFunctions.println( "ERROR: The anisotropy must be a positive number, but is " + anisotropyZ + "." );
			return false;
		}

		defaultAnisotropyZ = anisotropyZ;

		this.sigma = gd.getNextNumber();

		if ( !( sigma > 0 ) || Double.isInfinite( sigma ) )
		{
			IOFunctions.println( "Scale space: the initial blur must be a positive number, but is " + sigma + "." );
			return false;
		}

		defaultSigma = sigma;

		// steps and octaves are set in the interactive preview or the advanced dialog, otherwise the last choice is kept
		this.steps = defaultSteps;
		this.octaves = defaultOctaves;

		if ( localization == 2 )
		{
			IOFunctions.println( "Scale space: the Gaussian mask localization is not supported, using the quadratic fit in space and scale." );
			localization = defaultLocalization = 1;
		}

		if ( choice == resolutions.length )
		{
			// the manual entry, pre-filled with the last manual choice or the default level
			if ( defaultManualDownsampling == null )
				defaultManualDownsampling = DownsampleTools.parseDownsampleChoice( resolutions[ defaultResolutionChoice( resolutions.length, defaultResolutionIndex, false ) ] );

			final long[] ds = queryManualResolution( defaultManualDownsampling );

			if ( ds == null )
				return false;

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

		// only the interactive preview decides about the finest structures: the last choice there, otherwise off at full resolution and on elsewhere
		this.detectFinestLevel = defaultDetectFinestLevel( downsampling );

		IOFunctions.println( "Scale space: starting resolution (downsampling x, y, z) = " + Util.printCoordinates( downsampling ) + ", finest structures = " + detectFinestLevel );

		// the interactive previews (DifferenceOfGUI) open the views with these
		downsampleXYIndex = (int)downsampling[ 0 ];
		downsampleZ = (int)downsampling[ 2 ];

		return true;
	}

	/**
	 * The dialog for manually typed starting resolutions (powers of two)
	 *
	 * @param initial - the pre-filled factors
	 * @return the factors, null if cancelled or not powers of two
	 */
	public static long[] queryManualResolution( final long[] initial )
	{
		final GenericDialog gd = new GenericDialog( "Starting resolution" );

		gd.addMessage( "Downsampling of the image that the scale space starts at (powers of two)", GUIHelper.smallStatusFont );
		gd.addNumericField( "Downsample_X", initial[ 0 ], 0 );
		gd.addNumericField( "Downsample_Y", initial[ 1 ], 0 );
		gd.addNumericField( "Downsample_Z", initial[ 2 ], 0 );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final long[] ds = new long[ 3 ];

		for ( int d = 0; d < 3; ++d )
		{
			ds[ d ] = Math.round( gd.getNextNumber() );

			if ( !isPowerOfTwo( ds[ d ] ) )
			{
				IOFunctions.println( "ERROR: The downsampling factors must be powers of two >= 1, but the factor for dimension " + d + " is " + ds[ d ] + "." );
				return null;
			}
		}

		return ds;
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

	/**
	 * All scale-space parameters are added with the downsampling (see addDownsamplingParameters)
	 */
	@Override
	protected void addAddtionalParameters( final GenericDialog gd ) {}

	@Override
	protected boolean queryAdditionalParameters( final GenericDialog gd )
	{
		// the image sigmas are queried after the sub-dialogs, so the note stays here
		if ( imageSigmaX != 0.5 || imageSigmaY != 0.5 || imageSigmaZ != 0.5 )
			IOFunctions.println( "Scale space: the image sigmas are ignored, the scale space assumes an isotropic image blur of " + defaults.imageSigma + " px." );

		return true;
	}
}
