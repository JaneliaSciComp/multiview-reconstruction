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

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections.FilterParameters;
import net.preibisch.mvrecon.fiji.plugin.Limit_Detections;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.ViewSetupUtils;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.DisplayFusedImagesPopup;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxTools;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointdetection.OverlappingRegions;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointdetection.NoiseFloor;
import net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public abstract class DifferenceOfGUI extends InterestPointDetectionGUI
{
	public static String[] localizationChoice = { "None", "3-dimensional quadratic fit", "Gaussian mask localization fit" };	
	public static String[] brightnessChoice = { "Very weak & small (beads)", "Weak & small (beads)", "Comparable to Sample & small (beads)", "Strong & small (beads)", "Advanced ...", "Interactive ..." };

	// the resolution the detection runs at (the starting resolution of the scale space): a precomputed resolution level (by default the second one) or manually typed factors
	public static final String manualResolution = "Manually (powers of two) ...";
	public static int defaultResolutionIndex = 1;
	public static boolean defaultManual = false;
	public static long[] defaultManualDownsampling = null;

	// the anisotropy at full resolution (z voxel / x voxel): by default the estimate from the calibration (estimateAnisotropy, the PSF
	// blurs z more than the calibration says), remembered while the calibration is the same; the "Estimate Anisotropy (BDV)" tool sets it, too
	public static double anisotropyEstimateFactor = 0.8;
	public static double defaultAnisotropyZ = Double.NaN;
	public static double defaultAnisotropyCalibration = Double.NaN;

	public static int defaultLocalization = 1;
	public static int defaultBrightness = 5;


	public static int defaultViewChoice = 0;
	public static int defaultGroupChoice = 0;

	public static double defaultMinIntensity = 0.0;
	public static double defaultMaxIntensity = 65535.0;
	public static boolean defaultSameMinMax = false;

	/** "Only in regions overlapping other views": detect only where other volumes (another tile or angle of the same timepoint, among the selected views) overlap by the current registrations (OverlappingRegions) */
	public static boolean defaultOverlappingOnly = false;

	protected double minIntensity, maxIntensity;

	protected int localization;

	/** the resolution the detection runs at, as downsampling in x, y, z of the full-resolution views (the starting resolution of the scale space) */
	protected long[] downsampling;

	/** z voxel / x voxel at full resolution as estimated by the user (the PSF blurs z more than the calibration says, see estimateAnisotropy); converted to the opened resolution per view (DownsampleTools.anisotropy) */
	protected double anisotropyZ;

	/** the precomputed resolution levels of the first view ("fx, fy, fz"), the entries of the drop-down */
	protected String[] resolutions;

	/** the anisotropy of the image the interactive preview was opened with (getImagePlusForInteractive, getGroupedImagePlusForInteractive), for the preview's detection */
	protected double[] previewAnisotropy = null;

	public static boolean useAverageMapBack = true;

	boolean groupTiles, groupIllums, sameMinMax;

	/** detect only in the regions of a view that overlap other volumes (OverlappingRegions.forOtherVolumes), see defaultOverlappingOnly */
	protected boolean overlappingOnly = false;

	/** the regions of this run once computed (regions()) */
	private OverlappingRegions regions = null;
	public static int defaultFuseFrom = 0;
	public static int defaultFuseTo = 100;
	protected int fuseFrom = 0, fuseTo = 100;
	public static boolean defaultUseMinMaxForAll = false;
	boolean useMinMaxForAll = false;

	/** the threshold of the detection */
	protected double threshold;

	/** the factor of "Estimate from noise floor": the threshold becomes noiseFactor x the pooled noise floor of all views (thresholdFromNoiseFloor) */
	protected double noiseFactor = defaultNoiseFactor;

	/** the intensity ranges of the views at the dialog's resolution once computed for a noise floor (a full pass per view), so the previews' estimates at other sigmas skip it */
	private final HashMap< ViewId, double[] > noiseRangeCache = new HashMap<>();
	private long[] noiseRangeDownsampling = null;

	/** the entries of "Filter_interest_points": the range dialog (Filter_Detections), the fixed-number dialog (Limit_Detections), the threshold ramp (the range dialog prefilled) or none; the last choice is remembered, initially none */
	public static final String[] filterChoice = new String[] { "Filter interest points (response/sigma/intensity)", "Filter interest points (keep fixed number)", "Filter interest points (threshold ramp)", "Do not filter interest points" };
	public static int defaultFilterChoice = filterChoice.length - 1;

	/** the entries of "Threshold_type" of the Advanced dialogs: the threshold as typed, or noiseFactor x the noise floor of all views (NoiseFloor), estimated when the dialog closes; the last choice is remembered */
	public static final String[] thresholdChoices = new String[] { "Fixed threshold", "Estimate from noise floor (noise factor x the noise floor of all views)" };
	public static int defaultThresholdChoice = 0;
	public static double defaultNoiseFactor = 2.5;

	/** the filters chosen for the detections of this run (one, or the labels of a ramp), filtered labels or plain copies that Interest_Point_Detection creates once the points exist; empty = none */
	protected final ArrayList< FilterParameters > filters = new ArrayList<>();

	/** whether the threshold of this run was estimated from the noise floor (the "Advanced ..." dialog), stored with the label as provenance (getParameters) */
	protected boolean thresholdFromNoiseFloor = false;

	/** the names of the subpixel localizations in the parameters string (localizationChoice: none, quadratic fit, Gaussian mask fit) */
	public static final String[] localizationKeys = new String[] { "NONE", "QUADRATIC", "GAUSS_FIT" };

	/** @return the name of a subpixel localization in the parameters string, see localizationKeys */
	public static String localizationKey( final int localization )
	{
		return localization >= 0 && localization < localizationKeys.length ? localizationKeys[ localization ] : "NONE";
	}

	/** @return the index (localizationChoice) of a name in the parameters string, also a plain index; -1 if unknown */
	public static int localizationIndex( final String key )
	{
		for ( int i = 0; i < localizationKeys.length; ++i )
			if ( localizationKeys[ i ].equals( key ) )
				return i;

		try
		{
			final int index = Integer.parseInt( key );
			return index >= 0 && index < localizationKeys.length ? index : -1;
		}
		catch ( final NumberFormatException e )
		{
			return -1;
		}
	}

	/**
	 * The part of the parameters string stored with a label (getParameters) that both methods share, after their own
	 * values: the resolution and the anisotropy the detection ran with, the intensity range (NaN if none was set; the
	 * entered one or the common one the detection computed), and what else decides the detections: the subpixel
	 * localization, the grouping of tiles and illuminations, whether only the regions overlapping other views were
	 * detected in, the threshold type (fixed, or estimated from the noise floor with the noise factor; the estimated
	 * value itself is in t). StoredDetectionParameters reads it back to detect a label again with the same settings.
	 */
	protected String commonParameters()
	{
		return " downsampleX=" + downsampling[ 0 ] + " downsampleY=" + downsampling[ 1 ] + " downsampleZ=" + downsampling[ 2 ] + " anisotropy=" + anisotropyZ +
				" minIntensity=" + minIntensity + " maxIntensity=" + maxIntensity +
				" localization=" + localizationKey( localization ) + " groupTiles=" + groupTiles + " groupIllums=" + groupIllums + " overlappingOnly=" + overlappingOnly +
				( thresholdFromNoiseFloor ? " thresholdType=noisefloor noiseFactor=" + noiseFactor : " thresholdType=fixed" );
	}

	/**
	 * @return the regions of the views that overlap other volumes (OverlappingRegions.forOtherVolumes over the views of
	 *         this run, computed once) if the detection is restricted to them, null otherwise (the whole views)
	 */
	protected OverlappingRegions regions()
	{
		if ( !overlappingOnly )
			return null;

		if ( regions == null )
			regions = OverlappingRegions.forOtherVolumes( spimData, viewIdsToProcess );

		return regions;
	}

	public DifferenceOfGUI( final SpimData2 spimData, final List< ViewId > viewIdsToProcess )
	{
		super( spimData, viewIdsToProcess );
	}

	protected abstract void addAddtionalParameters( final GenericDialog gd );
	protected abstract boolean queryAdditionalParameters( final GenericDialog gd );

	protected abstract boolean setDefaultValues( final int brightness );
	protected abstract boolean setAdvancedValues();
	protected abstract boolean setInteractiveValues();

	@Override
	public void preprocess()
	{
		if ( sameMinMax || groupIllums || groupTiles )
		{
			// we set the min & max intensity for all individual views
			if ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) )
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Determining it approximate Min & Max for all views at lowest resolution levels ... " );

				IJ.showProgress( 0 );

				final ImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();

				double min = Double.MAX_VALUE;
				double max = -Double.MAX_VALUE;

				int count = 0;
				for ( final ViewId view : viewIdsToProcess )
				{
					final double[] minmax = FusionTools.minMaxApprox1( DownsampleTools.openAtLowestLevel( imgLoader, view ) );
					min = Math.min( min, minmax[ 0 ] );
					max = Math.max( max, minmax[ 1 ] );

					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): View " + Group.pvid( view ) + ", Min=" + minmax[ 0 ] + " max=" + minmax[ 1 ] );

					IJ.showProgress( (double)++count / viewIdsToProcess.size() );
				}

				this.minIntensity = min;
				this.maxIntensity = max;

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Total Min=" + this.minIntensity + " max=" + this.maxIntensity );
			}
		}
	}

	@Override
	public boolean queryParameters(
			final String label,
			final boolean setMinMax,
			final boolean groupTiles,
			final boolean groupIllums )
	{
		this.groupTiles = groupTiles;
		this.groupIllums = groupIllums;

		final GenericDialog gd = new GenericDialog( getDescription() );

		gd.addChoice( "Subpixel_localization", localizationChoice, localizationChoice[ defaultLocalization ] );
		final String[] specification = specificationChoices();
		final int defaultSpecification = defaultSpecificationIndex() >= 0 && defaultSpecificationIndex() < specification.length ? defaultSpecificationIndex() : 0;
		gd.addChoice( "Interest_point_specification", specification, specification[ defaultSpecification ] );

		addDownsamplingParameters( gd );

		if ( setMinMax )
		{
			gd.addNumericField( "Minimal_intensity", defaultMinIntensity, 1 );
			gd.addNumericField( "Maximal_intensity", defaultMaxIntensity, 1 );
		}
		else
		{
			if ( !FusionGUI.isMultiResolution( spimData ) )
				gd.addMessage( "Warning: You are not using multiresolution image data, this could take!", GUIHelper.smallStatusFont, GUIHelper.warning );
			gd.addCheckbox( "Use_same_min & max intensity for all views", defaultSameMinMax );
		}

		// only where other volumes overlap, by the current registrations (OverlappingRegions)
		gd.addCheckbox( "Only_in_regions_overlapping_other_views", defaultOverlappingOnly );
		gd.addMessage( "(other tiles or angles of the same timepoint among the selected views; a view without one gets no points)", GUIHelper.smallStatusFont );

		// a shortcut to the filter dialogs of the explorer for the label of this detection (the chosen dialog opens after this one)
		gd.addChoice( "Filter_interest_points", filterChoice, filterChoice[ defaultFilterChoice ] );

		addAddtionalParameters( gd );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return false;
		
		this.localization = defaultLocalization = gd.getNextChoiceIndex();

		final int brightness = gd.getNextChoiceIndex();
		setDefaultSpecificationIndex( brightness );

		if ( !queryDownsamplingParameters( gd ) )
			return false;

		if ( setMinMax )
		{
			minIntensity = defaultMinIntensity = gd.getNextNumber();
			maxIntensity = defaultMaxIntensity = gd.getNextNumber();
			sameMinMax = false;
		}
		else
		{
			minIntensity = maxIntensity = Double.NaN;
			sameMinMax = defaultSameMinMax = gd.getNextBoolean();
		}

		overlappingOnly = defaultOverlappingOnly = gd.getNextBoolean();

		if ( !dispatchSpecification( brightness ) )
			return false;

		final int filterIndex = defaultFilterChoice = gd.getNextChoiceIndex();

		if ( !queryAdditionalParameters( gd ) )
			return false;

		// the dialog of the chosen filter opens last, when the main dialog is complete
		return queryFilter( label, filterIndex );
	}

	/**
	 * The noise floor (NoiseFloor) of the finest DoG level in every view to process, pooled as the highest of them: at a resolution
	 * (null = the one chosen in the dialog), with the sigma of the finest level, k = 2^(1/steps), the method's anisotropy
	 * and the intensity range the detection will use (preprocess computes a common range first if one is wanted). Logs
	 * every view's floor, the pooled one and a warning if they spread by more than a factor of two, because the views are
	 * normalized with their own ranges unless a range is given.
	 *
	 * @return the pooled floors, null if none could be estimated (logged)
	 */
	public NoiseFloor.Pooled estimateNoiseFloor( final long[] downsampling, final double sigma, final int steps )
	{
		preprocess();

		final long[] ds = downsampling == null ? this.downsampling : downsampling;

		// the cached ranges belong to one resolution
		if ( !Arrays.equals( ds, noiseRangeDownsampling ) )
		{
			noiseRangeCache.clear();
			noiseRangeDownsampling = ds.clone();
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): " + getDescription() + ": estimating the noise floor of " + viewIdsToProcess.size() + " view(s) at downsampling " + Util.printCoordinates( ds ) + ", sigma " + sigma + ", k = 2^(1/" + steps + "), " + NoiseFloor.defaultNumBlocks + " random blocks of " + Util.printCoordinates( NoiseFloor.defaultBlockSize ) + " each ..." );

		final long time = System.currentTimeMillis();
		final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );
		final NoiseFloor.Pooled pooled;

		try
		{
			pooled = NoiseFloor.estimate(
					spimData.getSequenceDescription().getImgLoader(),
					NoiseFloor.presentViews( spimData, viewIdsToProcess ),
					ds,
					sigma,
					LaPlaceFunctions.computeK( steps ),
					( vd, t ) -> DownsampleTools.anisotropy( vd, t, anisotropyZ ),
					minIntensity,
					maxIntensity,
					noiseRangeCache,
					NoiseFloor.defaultNumBlocks,
					NoiseFloor.defaultBlockSize,
					NoiseFloor.defaultSeed,
					service );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "ERROR: Could not estimate the noise floor: " + e );
			return null;
		}
		finally
		{
			service.shutdown();
		}

		if ( pooled.perView.isEmpty() || !( pooled.floor() > 0 ) )
		{
			IOFunctions.println( "ERROR: Could not estimate the noise floor (" + pooled.describe() + ")." );
			return null;
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): " + pooled.describe() + " (" + ( System.currentTimeMillis() - time ) + " ms)" );

		if ( pooled.max() > 2 * pooled.min() )
			IOFunctions.println( "WARNING: the noise floors of the views spread by more than a factor of two [" + InterestPointFilter.plain( pooled.min() ) + " .. " + InterestPointFilter.plain( pooled.max() ) +
					"]: the views are normalized with their own intensity ranges, so one threshold is not equally selective for all of them ('Use_same_min & max intensity for all views' or a fixed range makes them comparable)." );

		return pooled;
	}

	/**
	 * @return estimateNoiseFloor for the previews, which know only their own view
	 */
	public NoiseFloor.Estimator noiseEstimator()
	{
		return new NoiseFloor.Estimator()
		{
			@Override
			public int numViews() { return viewIdsToProcess.size(); }

			@Override
			public NoiseFloor.Pooled estimate( final long[] downsampling, final double sigma, final int steps ) { return estimateNoiseFloor( downsampling, sigma, steps ); }
		};
	}

	/**
	 * "Estimate from noise floor" of the Advanced dialogs: the threshold becomes noiseFactor x the pooled noise floor of all
	 * views at the dialog's resolution (estimateNoiseFloor), logged; the caller remembers it as the default threshold
	 *
	 * @param sigma - the sigma of the finest level (the DoG sigma, the initial blur of the scale space)
	 * @param steps - k = 2^(1/steps), 4 for the single-scale DoG
	 * @return false if no floor could be estimated
	 */
	protected boolean thresholdFromNoiseFloor( final double sigma, final int steps )
	{
		if ( !( noiseFactor > 0 ) || Double.isInfinite( noiseFactor ) )
		{
			IOFunctions.println( "The noise factor must be > 0, but is " + noiseFactor + "." );
			return false;
		}

		final NoiseFloor.Pooled pooled = estimateNoiseFloor( null, sigma, steps );

		if ( pooled == null )
			return false;

		threshold = noiseFactor * pooled.floor();

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): threshold = " + noiseFactor + " x " + InterestPointFilter.plain( pooled.floor() ) + " (the noise floor) = " + InterestPointFilter.plain( threshold ) );

		return true;
	}

	/**
	 * Opens the dialog of the chosen entry of "Filter_interest_points" for the label of this detection: the ranges
	 * (Filter_Detections, several entries per field give several labels), the fixed number per view (Limit_Detections) or
	 * the threshold ramp (the range dialog prefilled with thresholds relative to the threshold of this detection,
	 * Filter_Detections.rampEntries); the labels are created by Interest_Point_Detection once the points exist
	 * (getFilters), so this is only a shortcut to the dialogs of the explorer
	 *
	 * @return false if the dialog was cancelled
	 */
	protected boolean queryFilter( final String label, final int choice )
	{
		filters.clear();

		if ( choice == 0 || choice == 2 )
		{
			// the threshold ramp is the range dialog with the response field prefilled relative to the threshold of this detection
			final String preset = choice == 2 ? Filter_Detections.rampEntries( threshold, Filter_Detections.rampFactor, Filter_Detections.rampSteps, Filter_Detections.rampMinEnd ) : null;
			final List< FilterParameters > ranges = Filter_Detections.getParameters( spimData, viewIdsToProcess, label, preset );

			if ( ranges == null )
				return false;

			filters.addAll( ranges );
		}
		else if ( choice == 1 )
		{
			final FilterParameters p = Limit_Detections.getParameters( spimData, viewIdsToProcess, label );

			if ( p == null )
				return false;

			filters.add( p );
		}

		for ( final FilterParameters p : filters )
			IOFunctions.println( getDescription() + ": also creating the " + ( p.copy ? "label" : "filtered label" ) + " '" + p.newLabel + "' (" + p.filter.describe( label ) + ")" );

		return true;
	}

	@Override
	public List< FilterParameters > getFilters() { return filters; }

	/**
	 * @return the entries of "Interest_point_specification" (the presets, "Advanced ...", "Interactive ..."); a subclass
	 * can offer another list and must then dispatch it in dispatchSpecification
	 */
	protected String[] specificationChoices() { return brightnessChoice; }

	/**
	 * @return the index of the entry that is preselected (the last choice)
	 */
	protected int defaultSpecificationIndex() { return defaultBrightness; }

	/**
	 * Remembers the chosen entry for the next dialog
	 */
	protected void setDefaultSpecificationIndex( final int index ) { defaultBrightness = index; }

	/**
	 * Applies the chosen entry of "Interest_point_specification": the presets (setDefaultValues), "Advanced ..."
	 * (setAdvancedValues) or "Interactive ..." (setInteractiveValues)
	 *
	 * @return false if the dialog should be aborted
	 */
	protected boolean dispatchSpecification( final int choice )
	{
		if ( choice <= 3 )
			return setDefaultValues( choice );
		else if ( choice == 4 )
			return setAdvancedValues();
		else
			return setInteractiveValues();
	}

	/**
	 * Adds the resolution the detection runs at (a drop-down of the precomputed resolution levels of the first view with
	 * the resulting voxel size, by default the second level, and a manual entry for powers of two) and the anisotropy
	 * (z voxel / x voxel at full resolution, by default the estimate from the calibration, estimateAnisotropy, kept while
	 * the calibration stays the same); the scale space adds its initial blur after them. queryDownsamplingParameters
	 * reads them back in the same order
	 */
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

		gd.addChoice( resolutionFieldLabel(), choices, choices[ defaultResolutionChoice( resolutions.length, defaultResolutionIndex, defaultManual ) ] );

		final double calibration = calibrationAnisotropy( voxelSize );

		if ( Double.isNaN( defaultAnisotropyZ ) || defaultAnisotropyCalibration != calibration )
		{
			defaultAnisotropyZ = estimateAnisotropy( calibration );
			defaultAnisotropyCalibration = calibration;
		}

		gd.addNumericField( "Anisotropy_z (z voxel / xy voxel at full resolution; estimate: calibration x " + anisotropyEstimateFactor + ")", defaultAnisotropyZ, 3 );
	}

	/**
	 * @return the label of the resolution drop-down (the scale space calls it the starting resolution)
	 */
	protected String resolutionFieldLabel() { return "Resolution (downsampling x, y, z)"; }

	/**
	 * Reads the resolution (opens the manual dialog if that entry was chosen) and the anisotropy into downsampling and anisotropyZ
	 *
	 * @return false if the dialog should be aborted
	 */
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

		IOFunctions.println( getDescription() + ": resolution (downsampling x, y, z) = " + Util.printCoordinates( downsampling ) + ", anisotropy z = " + anisotropyZ );

		return true;
	}

	/**
	 * The dialog for manually typed resolutions (powers of two)
	 *
	 * @param initial - the pre-filled factors
	 * @return the factors, null if cancelled or not powers of two
	 */
	public static long[] queryManualResolution( final long[] initial )
	{
		final GenericDialog gd = new GenericDialog( "Resolution" );

		gd.addMessage( "Downsampling of the image that the detection runs on (powers of two)", GUIHelper.smallStatusFont );
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

	/**
	 * @return z voxel / x voxel of the calibration of the view (1 if unknown)
	 */
	public static double calibrationAnisotropy( final ViewDescription vd )
	{
		return calibrationAnisotropy( vd.getViewSetup().hasVoxelSize() ? vd.getViewSetup().getVoxelSize() : null );
	}

	/**
	 * @return the estimate of the anisotropy (z voxel / x voxel at full resolution) from the calibration: the ratio of the
	 * calibration times anisotropyEstimateFactor (0.8, Stephan 2026-10-20), because the PSF blurs z more than xy, so structures
	 * appear less squeezed in z than the voxel ratio says
	 */
	public static double estimateAnisotropy( final double calibration )
	{
		return anisotropyEstimateFactor * calibration;
	}

	/**
	 * @return the estimate of the anisotropy from the calibration, see estimateAnisotropy( double ) (0.8 if unknown)
	 */
	public static double estimateAnisotropy( final VoxelDimensions voxelSize )
	{
		return estimateAnisotropy( calibrationAnisotropy( voxelSize ) );
	}

	protected static boolean isPowerOfTwo( final long value )
	{
		return value >= 1 && ( value & ( value - 1 ) ) == 0;
	}

	protected static double round( final double value )
	{
		return Math.round( value * 100.0 ) / 100.0;
	}

	/*
	 * Figure out which view to use for the interactive preview
	 * 
	 * @param dialogHeader
	 * @param text
	 * @return
	 */
	protected ViewId getViewSelection( final String dialogHeader, final String text )
	{
		final ArrayList< ViewDescription > views = SpimData2.getAllViewDescriptionsSorted( spimData, viewIdsToProcess );
		final String[] viewChoice = new String[ views.size() ];

		for ( int i = 0; i < views.size(); ++i )
		{
			final ViewDescription vd = views.get( i );
			viewChoice[ i ] =
					"Timepoint " + vd.getTimePointId() +
					", ViewSetupId " + vd.getViewSetupId();
		}

		if ( defaultViewChoice >= views.size() )
			defaultViewChoice = 0;

		final GenericDialog gd = new GenericDialog( dialogHeader );

		gd.addMessage( text );
		gd.addChoice( "View", viewChoice, viewChoice[ defaultViewChoice ] );
		
		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;

		final ViewId viewId = views.get( defaultViewChoice = gd.getNextChoiceIndex() );

		return viewId;
	}

	protected List< Group< ViewDescription > > getGroups()
	{
		final ArrayList< ViewDescription > vds = new ArrayList<>();

		for ( final ViewId viewId : viewIdsToProcess )
			vds.add( spimData.getSequenceDescription().getViewDescription( viewId ) );

		final HashSet< Class< ? extends Entity > > groupingFactor = new HashSet<>();
		String end = "";

		if ( groupTiles )
		{
			groupingFactor.add( Tile.class );
			end = "tile";
		}

		if ( groupIllums )
		{
			groupingFactor.add( Illumination.class );
			if ( end.length() > 0 )
				end += ", illumination";
			else
				end = "illumination";
		}

		final List< Group< ViewDescription > > groups = Group.getGroupsSorted( Group.combineBy( vds, groupingFactor ) );

		IOFunctions.println( "Identified: " + groups.size() + " groups when grouping by " + end + "." );
		int i = 0;
		for ( final Group< ViewDescription > group : groups )
			IOFunctions.println( end + "-Group " + (i++) + ":" + group );

		return groups;
	}

	protected String nameForGroup( final Group< ViewDescription > group )
	{
		final ViewDescription vd1 = group.iterator().next();

		String name =
				"TP=" + vd1.getTimePointId() +
				" Angle=" + vd1.getViewSetup().getAngle().getName() +
				" Channel=" + vd1.getViewSetup().getChannel().getName();

		if ( !groupIllums )
			name += " Illum=" + vd1.getViewSetup().getIllumination().getName();

		if ( !groupTiles )
			name += " Tile=" + vd1.getViewSetup().getTile().getName();

		return name;
	}

	/*
	 * Figure out which view to use for the interactive preview
	 * 
	 * @param dialogHeader
	 * @param text
	 * @return
	 */
	protected Group< ViewDescription > getGroupSelection( final String dialogHeader, final String text, final List< Group< ViewDescription > > groups )
	{
		final String[] groupChoice = new String[ groups.size() ];

		for ( int i = 0; i < groups.size(); ++i )
			groupChoice[ i ] = nameForGroup( groups.get( i ) );

		if ( defaultGroupChoice >= groups.size() )
			defaultGroupChoice = 0;

		final GenericDialog gd = new GenericDialog( dialogHeader );

		gd.addMessage( text );
		gd.addChoice( "Group", groupChoice, groupChoice[ defaultGroupChoice ] );

		gd.addMessage( "" );
		gd.addSlider( "Fuse_image_from (in z) [%]", 0, 49, defaultFuseFrom );
		gd.addSlider( "Fuse_image_to (in z) [%]", 51, 100, defaultFuseTo );
		gd.addMessage( "Note: you can select just a central portion of the", GUIHelper.smallStatusFont );
		gd.addMessage( "fused image for testing the interactive parameters.", GUIHelper.smallStatusFont );

		// we set the min & max intensity for all individual views
		if ( groups.size() > 1 && ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) ) )
		{
			gd.addMessage( "" );
			gd.addCheckbox( "Use_min/max of this fusion for all groups", defaultUseMinMaxForAll );
			gd.addMessage( "Min & Max intensity not set manually, use min/max from fused image for all group?", GUIHelper.smallStatusFont );
		}

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final Group< ViewDescription > group = groups.get( defaultGroupChoice = gd.getNextChoiceIndex() );
		fuseFrom = defaultFuseFrom = Math.max( 0, Math.min( 49, (int)Math.round( gd.getNextNumber() ) ) );
		fuseTo = defaultFuseTo = Math.max( 51, Math.min( 100, (int)Math.round( gd.getNextNumber() ) ) );

		if ( groups.size() > 1 && ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) ) )
			useMinMaxForAll = defaultUseMinMaxForAll = gd.getNextBoolean();

		if ( fuseFrom == 0 && fuseTo == 100 )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Fusing entire volume." );
		else
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Fusing volume with a range of " + fuseFrom + " to " + fuseTo + "% in z." );

		return group;
	}

	protected ImagePlus getImagePlusForInteractive( final String dialogHeader )
	{
		final ViewId view = getViewSelection( dialogHeader, "Please select view to use" );
		
		if ( view == null )
			return null;

		final ViewDescription viewDescription = spimData.getSequenceDescription().getViewDescription( view.getTimePointId(), view.getViewSetupId() );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Opening and downsampling ... " );

		@SuppressWarnings("rawtypes")
		final Pair< RandomAccessibleInterval, AffineTransform3D > opened = DownsampleTools.openAndDownsample(
			spimData.getSequenceDescription().getImgLoader(),
			viewDescription,
			downsampling,
			true );

		@SuppressWarnings("unchecked")
		RandomAccessibleInterval<RealType<?>> imgGeneric = opened.getA();

		// the anisotropy of the opened image, the preview detects with it (DifferenceOfGaussianGUI.setInteractiveValues)
		previewAnisotropy = DownsampleTools.anisotropy( viewDescription, opened.getB(), anisotropyZ );

		if ( imgGeneric == null )
		{
			IOFunctions.println( "View not found: " + viewDescription );
			return null;
		}

		final RandomAccessibleInterval< FloatType > img =
				Converters.convertRAI( imgGeneric, (i,o) -> o.set( i.getRealFloat() ), new FloatType() );

		// TODO: in the future when the interactive DoG does not copy & normalize anyways
		//IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Caching input image ... "  );
		//img = FusionTools.cacheRandomAccessibleInterval( img, FusionGUI.maxCacheSize, new FloatType(), FusionGUI.cellDim );

		final double min, max;

		if ( sameMinMax )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Determining same Min & Max for all views... " );
			preprocess();
			min = minIntensity;
			max = maxIntensity;
		}
		else if ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) )
		{
			// the preview needs a range when the user did not provide one: approximate it from this view, but the
			// fields stay NaN so that the detection computes the range of every view itself
			final double[] minmax = FusionTools.minMaxApprox1( img );
			min = minmax[ 0 ];
			max = minmax[ 1 ];
		}
		else
		{
			min = minIntensity;
			max = maxIntensity;
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Wrapping ImagePlus around input image ... " );

		return FusionTools.getImagePlusInstance(
				img, false,
				"tp: " + viewDescription.getTimePoint().getName() + " viewSetup: " + viewDescription.getViewSetupId(),
				min, max, null );
	}

	protected ImagePlus getGroupedImagePlusForInteractive( final String dialogHeader )
	{
		final List< Group< ViewDescription > > groups = getGroups();
		final Group< ViewDescription > group = getGroupSelection( dialogHeader, "Please select grouped views to use", groups );

		if ( group == null )
			return null;

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Using group " + group );

		// the resolution of the dialog (one factor for x and y)
		final long downsampleXY = downsampling[ 0 ];
		final long downsampleZ = downsampling[ 2 ];

		// the fused group is isotropic in physical units, what remains of the anisotropy is the user's estimate relative to the calibration (the PSF)
		previewAnisotropy = new double[] { 1, 1, anisotropyZ / calibrationAnisotropy( group.iterator().next() ) };

		final NumberFormat f = TransformationTools.f;

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Desired downsampling: (" +
				f.format( downsampleXY ) + ", " + f.format( downsampleXY ) + ", " + f.format( downsampleZ ) + ") " +
				"relative to the un-transformed input image." );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() )  + "): Identifying corresponding scaling of fused dataset" );

		final ArrayList< double[] > scales = new ArrayList<>();
		final ArrayList< AffineTransform3D > mapBackModels = new ArrayList<>();

		for ( final ViewId viewId : group )
		{
			final Dimensions dim = spimData.getSequenceDescription().getViewDescription( viewId ).getViewSetup().getSize();
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			final AffineTransform3D transform = vr.getModel().copy();

			final Pair< double[], AffineTransform3D > scaling = TransformationTools.scaling( dim, transform );
			final double[] scale = scaling.getA();

			IOFunctions.println( "View " + Group.pvid( viewId ) + " is currently scaled by: (" +
					f.format( scale[ 0 ] ) + ", " + f.format( scale[ 1 ] ) + ", " + f.format( scale[ 2 ] ) + ")" );

			scales.add( scale );
			mapBackModels.add( scaling.getB() );
		}

		final AffineTransform3D mapBack;
		final double[] scale;

		if ( useAverageMapBack )
		{
			mapBack = TransformationTools.averageTransforms( mapBackModels );
			scale = TransformationTools.averageVectors( scales );

			IOFunctions.println( "Using average scaling: (" + f.format( scale[ 0 ] ) + ", " + f.format( scale[ 1 ] ) + ", " + f.format( scale[ 2 ] ) + ")" +
					", should be (" + f.format( 1.0 / downsampleXY ) + ", " + f.format( 1.0 / downsampleXY ) + ", " + f.format( 1.0 / downsampleZ ) + ")" );
			IOFunctions.println( "Using average Mapback Model: " + TransformationTools.printAffine3D( mapBack ) );
		}
		else
		{
			mapBack = mapBackModels.get( 0 );
			scale = scales.get( 0 );

			IOFunctions.println( "Using scaling of first view: (" + f.format( scale[ 0 ] ) + ", " + f.format( scale[ 1 ] ) + ", " + f.format( scale[ 2 ] ) + ")" +
					", should be (" + f.format( 1.0 / downsampleXY ) + ", " + f.format( 1.0 / downsampleXY ) + ", " + f.format( 1.0 / downsampleZ ) + ")" );
			IOFunctions.println( "Using Mapback Model of first view: " + TransformationTools.printAffine3D( mapBack ) );
		}

		final double[] targetDS = new double[ 3 ];
		targetDS[ 0 ] = ( 1.0 / downsampleXY ) / ( ( scale[ 0 ] + scale[ 1 ] ) / 2.0 );
		targetDS[ 1 ] = ( 1.0 / downsampleXY ) / ( ( scale[ 0 ] + scale[ 1 ] ) / 2.0 );
		targetDS[ 2 ] = ( 1.0 / downsampleZ ) / scale[ 2 ];

		IOFunctions.println( "Fused image must be downsampled by: (" +
				f.format( 1.0/targetDS[ 0 ] ) + ", " + f.format( 1.0/targetDS[ 1 ] ) + ", " + f.format( 1.0/targetDS[ 2 ] ) + ") after applying mapback." );

		// now scale z so we have one common downsample factor
		final double ds = 1.0 / targetDS[ 0 ];
		targetDS[ 0 ] = 1.0;
		targetDS[ 1 ] = 1.0;
		targetDS[ 2 ] = ( 1.0 / downsampleZ ) / (scale[ 2 ] / ds);

		final AffineTransform3D scalingTransform = new AffineTransform3D();
		scalingTransform.set( targetDS[ 0 ], 0, 0 );
		scalingTransform.set( targetDS[ 1 ], 1, 1 );
		scalingTransform.set( targetDS[ 2 ], 2, 2 );

		IOFunctions.println( "Using downsampling " + f.format( ds ) + " and scaling of : (" +
				f.format( targetDS[ 0 ] ) + ", " + f.format( targetDS[ 1 ] ) + ", " + f.format( targetDS[ 2 ] ) + ") resulting in downsampling (" +
				f.format( 1.0/((scale[ 0 ] * targetDS[ 0 ])/ds) ) + ", " + f.format( 1.0/((scale[ 1 ] * targetDS[ 1 ])/ds) ) + ", " + f.format( 1.0/((scale[ 2 ] * targetDS[ 2 ])/ds) ) + ")" );

		// assemble temporary registrations and dimensions
		// for fusion and bounding box
		final HashMap< ViewId, AffineTransform3D > registrations = new HashMap<>();
		final HashMap< ViewId, Dimensions > dimensions = new HashMap<>();

		for ( final ViewDescription viewId : group )
		{
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			final AffineTransform3D model = vr.getModel();
			model.preConcatenate( mapBack );
			model.preConcatenate( scalingTransform );
			registrations.put( viewId, model );

			dimensions.put( viewId, ViewSetupUtils.getSizeOrLoad( viewId.getViewSetup(), viewId.getTimePoint(), spimData.getSequenceDescription().getImgLoader() ) );
		}

		final ImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();
		final Map< ViewId, ViewDescription > viewDescriptions = spimData.getSequenceDescription().getViewDescriptions();

		BoundingBox bb = BoundingBoxTools.maximalBoundingBox( group.getViews(), dimensions, registrations, "max bounding box" );
		long megabytes = Math.round( ( FusionTools.numPixels( bb, ds ) * 4) / (1024.0*1024.0) );
		IOFunctions.println( "Effective boundingbox: " + Util.printInterval( TransformVirtual.scaleBoundingBox( bb, 1.0 / ds ) ) + " estimated size=" + megabytes + " MB" );

		if ( fuseFrom > 0 || fuseTo < 100 )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Determining cropped bounding box " + fuseFrom + " to " + fuseTo + "% in z." );

			final double size = bb.dimension( 2 );

			final int[] min = bb.getMin().clone();
			final int[] max = bb.getMax().clone();

			min[ 2 ] = (int)Math.round( bb.min( 2 ) + size * (fuseFrom / 100.0) );
			max[ 2 ] = (int)Math.round( bb.min( 2 ) + size * (fuseTo / 100.0) );

			bb = new BoundingBox( min, max );

			megabytes = Math.round( ( FusionTools.numPixels( bb, ds ) * 4) / (1024.0*1024.0) );
			IOFunctions.println( "Cropped Effective boundingbox: " + Util.printInterval( TransformVirtual.scaleBoundingBox( bb, 1.0 / ds ) ) + " estimated size=" + megabytes + " MB" );
		}

		// adjust bounding box
		final Interval bbDS = FusionTools.createDownsampledBoundingBox( bb, ds ).getA();

		// adjust registrations
		final HashMap< ViewId, AffineTransform3D > registrationsAdjusted =
				TransformVirtual.adjustAllTransforms(
						registrations,
						Double.NaN,
						ds );

		// TODO: change to block processing
		RandomAccessibleInterval< FloatType > img = FusionTools.fuseVirtual(
				imgLoader,
				registrationsAdjusted,
				viewDescriptions,
				group.getViews(),
				FusionType.AVG_BLEND,
				DisplayFusedImagesPopup.defaultInterpolation,
				bbDS,
				null );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Fusing temporary image ... " );

		img = FusionTools.copyImg( img, new ImagePlusImgFactory< FloatType >( new FloatType() ), new FloatType(), null, true );

		// we set the min & max intensity for all individual views
		if ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) )
		{
			if ( useMinMaxForAll )
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Min & Max intensity not set, determining it from fused image for all groups... " );

				final float[] minmax = FusionTools.minMax( img );
				this.minIntensity = minmax[ 0 ];
				this.maxIntensity = minmax[ 1 ];

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Total Min=" + this.minIntensity + " max=" + this.maxIntensity );
			}
			else
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Min & Max intensity not set, determining it approximately using all views... " );

				preprocess();
			}
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Wrapping ImagePlus around fused image ... " );

		return FusionTools.getImagePlusInstance( img, true, nameForGroup( group ), this.minIntensity, this.maxIntensity, DisplayImage.service );
	}
}
