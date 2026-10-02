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
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import ij.ImagePlus;
import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
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

	protected double sigma;
	protected double threshold;
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
		// see DifferenceOfGaussianGUI: the "match Z resolution" modes resolve the factor per view
		final LinkedHashSet< Integer > resolved = resolvedDownsampleXYs();
		if ( resolved.size() == 1 )
			p.put( "downsampleXY", Integer.toString( resolved.iterator().next() ) );
		else if ( resolved.size() > 1 )
			p.put( "downsampleXYVaries", resolved.toString() );
		p.put( "downsampleZ", Integer.toString( downsampleZ ) );
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

		p.downsampleZ = this.downsampleZ;

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

				// downsampleXY == 0 : a bit less then z-resolution
				// downsampleXY == -1 : a bit more then z-resolution
				if ( downsampleXYIndex < 1 )
					p.downsampleXY = DownsampleTools.downsampleFactor( downsampleXYIndex, downsampleZ, vd.getViewSetup().getVoxelSize() );
				else
					p.downsampleXY = downsampleXYIndex;

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
				" downsampleXY=" + resolvedDownsampleXY() + " downsampleXYIndex=" + downsampleXYIndex +
				" downsampleZ=" + downsampleZ + " minIntensity=" + minIntensity + " maxIntensity=" + maxIntensity;
	}

	/**
	 * @return the downsampling in xy of all views being processed (one entry if they agree); the
	 * "match z resolution" modes compute it per view from the calibration
	 */
	protected LinkedHashSet< Integer > resolvedDownsampleXYs()
	{
		final LinkedHashSet< Integer > resolved = new LinkedHashSet<>();

		if ( downsampleXYIndex >= 1 )
		{
			resolved.add( downsampleXYIndex );
			return resolved;
		}

		for ( final ViewId v : viewIdsToProcess )
		{
			final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( v.getTimePointId(), v.getViewSetupId() );

			if ( vd.isPresent() )
				resolved.add( DownsampleTools.downsampleFactor( downsampleXYIndex, downsampleZ, vd.getViewSetup().getVoxelSize() ) );
		}

		return resolved;
	}

	/**
	 * @return the downsampling in xy that is applied to all views being processed, or -1 if it differs between views
	 */
	protected int resolvedDownsampleXY()
	{
		final LinkedHashSet< Integer > resolved = resolvedDownsampleXYs();

		return resolved.size() == 1 ? resolved.iterator().next() : -1;
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
