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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
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
 * of "Interest_point_specification" (no presets). "Filter_interest_points" opens the range or the fixed-number dialog
 * of the explorer for the new label (DifferenceOfGUI.queryFilter).
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

	// the starting resolution and the anisotropy (and their defaults) are the fields of DifferenceOfGUI, shared with the DoG

	protected double sigma;
	protected boolean findMin;
	protected boolean findMax;

	protected int steps;
	protected int octaves;
	protected boolean detectFinestLevel;

	public ScaleSpaceGUI( final SpimData2 spimData, final List< ViewId > viewIdsToProcess )
	{
		super( spimData, viewIdsToProcess );
	}

	@Override
	public String getDescription() { return "Scale-space (Difference-of-Gaussian)"; }

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
		// only in the regions overlapping other volumes (BigStitcher-Spark's --overlappingOnly with --onlyCompareOverlapTiles, the GUI's partner rule)
		if ( overlappingOnly )
		{
			p.put( "overlappingOnly", "true" );
			p.put( "onlyCompareOverlapTiles", "true" );
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
		final ScaleSpaceDetectionParameters p = detectionParameters();
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

	/**
	 * @return the parameters of the detection as set in the dialogs
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

		p.scaleSpace.sigmaMin = this.sigma;
		p.scaleSpace.threshold = this.threshold;
		p.scaleSpace.findMin = this.findMin;
		p.scaleSpace.findMax = this.findMax;
		p.scaleSpace.localization = this.localization;
		p.scaleSpace.steps = this.steps;
		p.scaleSpace.octaves = this.octaves;
		p.scaleSpace.detectFinestLevel = this.detectFinestLevel;

		// the regions of the views that overlap other volumes, if the detection is restricted to them
		p.regions = regions();

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
	 * Everything the interactive preview can set, as a dialog
	 */
	@Override
	protected boolean setAdvancedValues()
	{
		final GenericDialog gd = new GenericDialog( "Advanced values" );

		gd.addChoice( "Threshold_type", thresholdChoices, thresholdChoices[ defaultThresholdChoice ] );
		gd.addNumericField( "Threshold (minimal |response| at any scale)", defaultThreshold, 5, 12, null ); // twice the default width
		gd.addNumericField( "Noise_factor", defaultNoiseFactor, 2 );
		gd.addNumericField( "Steps_per_octave", defaultSteps, 0 );
		gd.addNumericField( "Octaves (-1 = as many as the image allows)", defaultOctaves, 0 );
		gd.addCheckbox( "Finest_structures (also keep the extrema at the initial blur that are no extremum in scale)", defaultDetectFinestLevel( downsampling ) );
		gd.addCheckbox( "Find_minima", defaultFindMin );
		gd.addCheckbox( "Find_maxima", defaultFindMax );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return false;

		final int thresholdChoice = gd.getNextChoiceIndex();
		this.threshold = gd.getNextNumber();
		this.noiseFactor = gd.getNextNumber();
		this.thresholdFromNoiseFloor = thresholdChoice == 1;
		this.steps = (int)Math.round( gd.getNextNumber() );
		this.octaves = (int)Math.round( gd.getNextNumber() );
		this.detectFinestLevel = gd.getNextBoolean();
		this.findMin = gd.getNextBoolean();
		this.findMax = gd.getNextBoolean();

		if ( thresholdChoice == 0 && ( !( threshold > 0 ) || Double.isInfinite( threshold ) ) )
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

		// the threshold from the noise floor of all views at the starting resolution and these steps: estimated now, logged and remembered as the default threshold
		if ( thresholdChoice == 1 && !thresholdFromNoiseFloor( sigma, steps ) )
			return false;

		defaultThreshold = threshold;
		defaultThresholdChoice = thresholdChoice;
		defaultNoiseFactor = noiseFactor;
		defaultSteps = steps;
		defaultOctaves = octaves;
		lastDetectFinestLevel = detectFinestLevel;
		defaultFindMin = findMin;
		defaultFindMax = findMax;

		return true;
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

		// the "Estimate noise floor" button of the windows estimates the floor of all views to process
		final InteractiveScaleSpace.Session session = new InteractiveScaleSpace.Session( spimData, spimData.getSequenceDescription().getViewDescription( viewId ), resolutions, p, anisotropyZ, noiseEstimator() );

		session.open( downsampling, defaultManual ? -1 : defaultResolutionIndex );
		session.waitUntilFinished();

		if ( session.isCancelled() )
			return false;

		this.threshold = defaultThreshold = session.getThreshold();
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

		IOFunctions.println( "Scale space: starting resolution (downsampling x, y, z) = " + Util.printCoordinates( downsampling ) + " (interactive)" );

		return true;
	}

	/**
	 * @return the parameter string stored with a label (StoredDetectionParameters reads it): the method and its values, then
	 *         what both methods share (DifferenceOfGUI.commonParameters)
	 */
	@Override
	public String getParameters()
	{
		return "DOG-SS s=" + sigma + " steps=" + steps + " octaves=" + octaves + " finestLevel=" + detectFinestLevel + " t=" + threshold + " min=" + findMin + " max=" + findMax + commonParameters();
	}

	/**
	 * The starting resolution and the anisotropy are the fields of DifferenceOfGUI (shared with the DoG); the initial
	 * blur is added right after them because the "Advanced ..." and "Interactive ..." sub-dialogs, which DifferenceOfGUI
	 * opens right after the downsampling has been queried, need it (the additional parameters are only queried afterwards).
	 */
	@Override
	protected void addDownsamplingParameters( final GenericDialog gd )
	{
		super.addDownsamplingParameters( gd );

		gd.addMessage( "Scale space: the initial blur is the finest scale (Lowe: 1.6), the threshold the minimal |response| at any scale", GUIHelper.smallStatusFont );
		gd.addNumericField( "Initial_blur sigma (px at the starting resolution, Lowe: 1.6)", defaultSigma, 3 );
	}

	@Override
	protected String resolutionFieldLabel() { return "Starting_resolution (downsampling x, y, z)"; }

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

	@Override
	protected boolean queryDownsamplingParameters( final GenericDialog gd )
	{
		// the starting resolution and the anisotropy
		if ( !super.queryDownsamplingParameters( gd ) )
			return false;

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

		// only the interactive preview decides about the finest structures: the last choice there, otherwise off at full resolution and on elsewhere
		this.detectFinestLevel = defaultDetectFinestLevel( downsampling );

		IOFunctions.println( "Scale space: finest structures = " + detectFinestLevel );

		return true;
	}

	/**
	 * All scale-space parameters are added with the downsampling (see addDownsamplingParameters)
	 */
	@Override
	protected void addAddtionalParameters( final GenericDialog gd ) {}

	@Override
	protected boolean queryAdditionalParameters( final GenericDialog gd ) { return true; }
}
