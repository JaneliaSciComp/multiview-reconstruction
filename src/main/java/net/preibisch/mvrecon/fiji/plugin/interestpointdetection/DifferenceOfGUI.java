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

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
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
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.ViewSetupUtils;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.DisplayFusedImagesPopup;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxTools;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public abstract class DifferenceOfGUI extends InterestPointDetectionGUI
{
	protected static final int[] ds = { 1, 2, 4, 8, 16, 32, 64 };

	public static String[] downsampleChoiceXY = { ds[ 0 ] + "x", ds[ 1 ] + "x", ds[ 2 ] + "x", ds[ 3 ] + "x", ds[ 4 ] + "x", ds[ 5 ] + "x", ds[ 6 ] + "x", "Match Z Resolution (less downsampling)", "Match Z Resolution (more downsampling)"  };
	public static String[] downsampleChoiceZ = { ds[ 0 ] + "x", ds[ 1 ] + "x", ds[ 2 ] + "x", ds[ 3 ] + "x" };
	public static String[] localizationChoice = { "None", "3-dimensional quadratic fit", "Gaussian mask localization fit" };	
	public static String[] brightnessChoice = { "Very weak & small (beads)", "Weak & small (beads)", "Comparable to Sample & small (beads)", "Strong & small (beads)", "Advanced ...", "Interactive ..." };

	public static int defaultDownsampleXYIndex = 7;
	public static int defaultDownsampleZIndex = 0;

	public static int defaultLocalization = 1;
	public static int defaultBrightness = 5;

	public static double defaultImageSigmaX = 0.5;
	public static double defaultImageSigmaY = 0.5;
	public static double defaultImageSigmaZ = 0.5;

	public static int defaultViewChoice = 0;
	public static int defaultGroupChoice = 0;

	public static double defaultAdditionalSigmaX = 0.0;
	public static double defaultAdditionalSigmaY = 0.0;
	public static double defaultAdditionalSigmaZ = 0.0;

	public static double defaultMinIntensity = 0.0;
	public static double defaultMaxIntensity = 65535.0;
	public static boolean defaultSameMinMax = false;

	public static int defaultMaxDetections = 3000;
	public static int defaultMaxDetectionsTypeIndex = 0;

	protected boolean limitDetections = false;
	protected double imageSigmaX, imageSigmaY, imageSigmaZ;
	protected double minIntensity, maxIntensity;
	protected int maxDetections, maxDetectionsTypeIndex;

	// downsampleXYIndex == 0 : a bit less then z-resolution
	// downsampleXYIndex == -1 : a bit more then z-resolution
	protected int localization, downsampleXYIndex, downsampleZ;

	public static boolean useAverageMapBack = true;

	boolean groupTiles, groupIllums, sameMinMax;
	public static int defaultFuseFrom = 0;
	public static int defaultFuseTo = 100;
	protected int fuseFrom = 0, fuseTo = 100;
	public static boolean defaultUseMinMaxForAll = false;
	boolean useMinMaxForAll = false;

	/** the entries of "Number_of_thresholds" in the Advanced dialogs: one, 2..6 or N thresholds (several give one label per threshold, label_t<threshold>) */
	public static final String[] thresholdCountChoice = new String[] { "Single threshold", "2 thresholds", "3 thresholds", "4 thresholds", "5 thresholds", "6 thresholds", "N thresholds" };
	public static int defaultThresholdCountChoice = 0;
	public static int defaultThresholdCountN = 8;

	/** the default thresholds of a multi-threshold run span this range geometrically (very low .. very high); from wideFromCount thresholds on the lower end is lowestDefaultThresholdWide */
	public static double lowestDefaultThreshold = 0.001, highestDefaultThreshold = 0.1;
	public static double lowestDefaultThresholdWide = 0.0001;
	public static int wideFromCount = 6;

	/** the thresholds last typed into the multi-threshold dialog, per number of thresholds */
	protected static final HashMap< Integer, double[] > lastThresholds = new HashMap<>();

	/** the threshold of the detection (the first one of a multi-threshold run) */
	protected double threshold;
	/** all thresholds of this run (ascending, unique), threshold is the first one; null = only threshold */
	protected double[] thresholds = null;

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
			final boolean defineAnisotropy,
			final boolean setMinMax,
			final boolean limitDetections,
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

		if ( defineAnisotropy )
		{
			gd.addNumericField( "Image_Sigma_X", defaultImageSigmaX, 5 );
			gd.addNumericField( "Image_Sigma_Y", defaultImageSigmaY, 5 );
			gd.addNumericField( "Image_Sigma_Z", defaultImageSigmaZ, 5 );
			
			gd.addMessage( "Please consider that usually the lower resolution in z is compensated by a lower sampling rate in z.\n" +
					"Only adjust the initial sigma's if this is not the case.", GUIHelper.mediumstatusfont );
		}

		this.limitDetections = limitDetections;
		if ( limitDetections )
		{
			gd.addNumericField( "Maximum_number of detections (highest n)", defaultMaxDetections, 0 );
			gd.addChoice( "Type_of_detections_to_use", InterestPointTools.limitDetectionChoice, InterestPointTools.limitDetectionChoice[ defaultMaxDetectionsTypeIndex ] );
		}

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

		if ( !dispatchSpecification( brightness ) )
			return false;

		if ( defineAnisotropy )
		{
			imageSigmaX = defaultImageSigmaX = gd.getNextNumber();
			imageSigmaY = defaultImageSigmaY = gd.getNextNumber();
			imageSigmaZ = defaultImageSigmaZ = gd.getNextNumber();
		}
		else
		{
			imageSigmaX = imageSigmaY = imageSigmaZ = 0.5;
		}

		if ( limitDetections )
		{
			maxDetections = defaultMaxDetections = (int)Math.round( gd.getNextNumber() );
			maxDetectionsTypeIndex = defaultMaxDetectionsTypeIndex = gd.getNextChoiceIndex();
		}

		if ( !queryAdditionalParameters( gd ) )
			return false;
		else
			return true;
	}

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
	 * The thresholds of this run: the ones of the multi-threshold dialog, or the single one otherwise
	 */
	protected double[] thresholds()
	{
		return thresholds == null || thresholds.length == 0 ? new double[] { threshold } : thresholds;
	}

	/**
	 * @return "" for a single threshold, otherwise "_t" + threshold per threshold (ascending), see thresholdSuffix
	 */
	@Override
	public List< String > getLabelSuffixes()
	{
		final double[] thresholds = thresholds();
		final ArrayList< String > suffixes = new ArrayList<>();

		if ( thresholds.length == 1 )
			suffixes.add( "" );
		else
			for ( final double t : thresholds )
				suffixes.add( thresholdSuffix( t ) );

		return suffixes;
	}

	/**
	 * @return the threshold of a label suffix ("" = the first one)
	 */
	protected double thresholdOf( final String suffix )
	{
		for ( final double t : thresholds() )
			if ( thresholdSuffix( t ).equals( suffix ) )
				return t;

		return threshold;
	}

	/**
	 * @return the parameter string stored with a label (getParameters) for this threshold
	 */
	protected abstract String parameters( final double threshold );

	@Override
	public String getParameters()
	{
		return parameters( threshold );
	}

	/**
	 * @return the parameter string of the label of this suffix, with its threshold
	 */
	@Override
	public String getParameters( final String suffix )
	{
		return parameters( thresholdOf( suffix ) );
	}

	/**
	 * @return describeParameters() with the threshold of this suffix, plus all thresholds of the run if there are several
	 */
	@Override
	public Map< String, String > describeParameters( final String suffix )
	{
		final Map< String, String > p = describeParameters();
		final double[] thresholds = thresholds();

		p.put( "threshold", Double.toString( thresholdOf( suffix ) ) );

		if ( thresholds.length > 1 )
		{
			final StringBuilder all = new StringBuilder();

			for ( final double t : thresholds )
				all.append( all.length() == 0 ? "" : ";" ).append( t );

			p.put( "thresholds", all.toString() );
		}

		return p;
	}

	/**
	 * @param perThreshold - the detections per threshold, in the order of thresholds()
	 * @return the detections per label suffix (getLabelSuffixes), in that order
	 */
	protected LinkedHashMap< String, HashMap< ViewId, List< InterestPoint > > > perSuffix( final List< HashMap< ViewId, List< InterestPoint > > > perThreshold )
	{
		final List< String > suffixes = getLabelSuffixes();
		final LinkedHashMap< String, HashMap< ViewId, List< InterestPoint > > > result = new LinkedHashMap<>();

		for ( int i = 0; i < suffixes.size(); ++i )
			result.put( suffixes.get( i ), perThreshold.get( i ) );

		return result;
	}

	/**
	 * The thresholds of this run from the "Number_of_thresholds" entry of an Advanced dialog (thresholdCountChoice):
	 * the single threshold (already set), 2..6 thresholds or N after a dialog for N, typed into a dialog with one
	 * field per threshold (queryThresholds); sets thresholds and threshold (the first one) and remembers the entry
	 *
	 * @param countChoice - the index of the chosen entry of thresholdCountChoice
	 * @return false if a dialog was cancelled or a value is invalid
	 */
	protected boolean queryMultipleThresholds( final int countChoice )
	{
		defaultThresholdCountChoice = countChoice;

		if ( countChoice == 0 )
		{
			this.thresholds = new double[] { threshold };
			return true;
		}

		// several thresholds: 2..6 directly, N after asking for N
		int count = countChoice + 1;

		if ( countChoice == thresholdCountChoice.length - 1 )
		{
			final GenericDialog gdN = new GenericDialog( "Number of thresholds" );

			gdN.addNumericField( "Number_of_thresholds", defaultThresholdCountN, 0 );
			gdN.showDialog();

			if ( gdN.wasCanceled() )
				return false;

			count = (int)Math.round( gdN.getNextNumber() );

			if ( count < 2 )
			{
				IOFunctions.println( getDescription() + ": at least two thresholds are needed for a multi-threshold run, " + count + " were requested." );
				return false;
			}

			defaultThresholdCountN = count;
		}

		final double[] thresholds = queryThresholds( count );

		if ( thresholds == null )
			return false;

		this.thresholds = thresholds;
		this.threshold = thresholds[ 0 ];

		IOFunctions.println( getDescription() + ": " + thresholds.length + " thresholds, one label per threshold: " + Arrays.toString( thresholds ) );

		return true;
	}

	/**
	 * The dialog for the thresholds of a multi-threshold run, pre-filled with the ones last typed for this count or the
	 * default thresholds (geometric from very low to very high)
	 *
	 * @return the thresholds ascending without duplicates, null if cancelled or a value is not &gt; 0
	 */
	protected static double[] queryThresholds( final int count )
	{
		final double[] initial = lastThresholds.containsKey( count ) && lastThresholds.get( count ).length == count ? lastThresholds.get( count ) : defaultThresholds( count );

		final GenericDialog gd = new GenericDialog( "Thresholds" );

		gd.addMessage( "Minimal |response|, one label per threshold (label_t<threshold>)", GUIHelper.smallStatusFont );

		for ( int i = 0; i < count; ++i )
			gd.addNumericField( "Threshold_" + ( i + 1 ), initial[ i ], 5 );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final double[] typed = new double[ count ];
		final TreeSet< Double > values = new TreeSet<>();

		for ( int i = 0; i < count; ++i )
		{
			typed[ i ] = gd.getNextNumber();

			if ( !( typed[ i ] > 0 ) || Double.isInfinite( typed[ i ] ) )
			{
				IOFunctions.println( "Thresholds must be > 0, but threshold " + ( i + 1 ) + " is " + typed[ i ] + "." );
				return null;
			}

			values.add( typed[ i ] );
		}

		lastThresholds.put( count, typed );

		if ( values.size() < count )
			IOFunctions.println( ( count - values.size() ) + " duplicate threshold(s) dropped." );

		return values.stream().mapToDouble( Double::doubleValue ).toArray();
	}

	/**
	 * @param count - the number of thresholds, at least two
	 * @return count thresholds spaced geometrically from lowestDefaultThreshold (very low) to highestDefaultThreshold (very high),
	 * rounded to two significant digits, e.g. 0.001, 0.01, 0.1 for three; from wideFromCount thresholds on the range starts at
	 * lowestDefaultThresholdWide (0.0001, below the floor where the threshold still selects anything, but it finds some more matches),
	 * e.g. 0.0001, 0.0004, 0.0016, 0.0063, 0.025, 0.1 for six
	 */
	public static double[] defaultThresholds( final int count )
	{
		if ( count < 2 )
			throw new IllegalArgumentException( "The default thresholds of a multi-threshold run need at least two thresholds, " + count + " requested." );

		final double[] thresholds = new double[ count ];
		final double logMin = Math.log( count >= wideFromCount ? lowestDefaultThresholdWide : lowestDefaultThreshold ), logMax = Math.log( highestDefaultThreshold );

		for ( int i = 0; i < count; ++i )
			thresholds[ i ] = BigDecimal.valueOf( Math.exp( logMin + ( logMax - logMin ) * i / ( count - 1 ) ) ).round( new MathContext( 2 ) ).doubleValue();

		return thresholds;
	}

	/**
	 * @return the label suffix of a threshold, "_t" + the plain number, e.g. "_t0.004" (several thresholds give one label per threshold)
	 */
	public static String thresholdSuffix( final double threshold )
	{
		return "_t" + plain( threshold );
	}

	/**
	 * @return the shortest plain decimal representation (no exponent, no trailing zeros), e.g. 1.0E-4 as "0.0001"
	 */
	protected static String plain( final double value )
	{
		return BigDecimal.valueOf( value ).stripTrailingZeros().toPlainString();
	}

	/**
	 * Adds the downsampling choices to the dialog (overridden by the scale space, which lets the user
	 * pick a resolution level); queryDownsamplingParameters reads them back in the same order
	 */
	protected void addDownsamplingParameters( final GenericDialog gd )
	{
		final String[] ds = DownsampleTools.availableDownsamplings( spimData, viewIdsToProcess.get( 0 ) );
		String out = "(" + ds[ 0 ].replaceAll( " ", "" ) + ")";
		for ( int i = 1; i < ds.length; ++i )
			out += ", (" + ds[ i ].replaceAll( " ", "" ) + ")";
		gd.addMessage( "Precomputed Resolutions:       " + out, GUIHelper.smallStatusFont );

		gd.addChoice( "Downsample_XY", downsampleChoiceXY, downsampleChoiceXY[ defaultDownsampleXYIndex ] );
		gd.addChoice( "Downsample_Z", downsampleChoiceZ, downsampleChoiceZ[ defaultDownsampleZIndex ] );
	}

	/**
	 * Reads the downsampling choices and sets downsampleXYIndex (the factor, 0 or -1 for the "match z"
	 * modes) and downsampleZ
	 *
	 * @return false if the dialog should be aborted
	 */
	protected boolean queryDownsamplingParameters( final GenericDialog gd )
	{
		int dsxy = defaultDownsampleXYIndex = gd.getNextChoiceIndex();
		int dsz = defaultDownsampleZIndex = gd.getNextChoiceIndex();

		if ( dsz == 0 )
			downsampleZ = 1;
		else if ( dsz == 1 )
			downsampleZ = 2;
		else if ( dsz == 2 )
			downsampleZ = 4;
		else
			downsampleZ = 8;

		if ( dsxy == 0 )
			downsampleXYIndex = 1;
		else if ( dsxy == 1 )
			downsampleXYIndex = 2;
		else if ( dsxy == 2 )
			downsampleXYIndex = 4;
		else if ( dsxy == 3 )
			downsampleXYIndex = 8;
		else if ( dsxy == 4 )
			downsampleXYIndex = 16;
		else if ( dsxy == 5 )
			downsampleXYIndex = 32;
		else if ( dsxy == 6 )
			downsampleXYIndex = 64;
		else if ( dsxy == 7 )
			downsampleXYIndex = 0;
		else
			downsampleXYIndex = -1;

		return true;
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

		// downsampleXY == 0 : a bit less then z-resolution
		// downsampleXY == -1 : a bit more then z-resolution
		final int downsampleXY;

		if ( downsampleXYIndex < 1 )
			downsampleXY = DownsampleTools.downsampleFactor( downsampleXYIndex, downsampleZ, viewDescription.getViewSetup().getVoxelSize() );
		else
			downsampleXY = downsampleXYIndex;

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Opening and downsampling ... " );

		@SuppressWarnings("unchecked")
		RandomAccessibleInterval<RealType<?>> imgGeneric = DownsampleTools.openAndDownsample(
			spimData.getSequenceDescription().getImgLoader(),
			viewDescription,
			new long[] { downsampleXY, downsampleXY, downsampleZ },
			true ).getA();

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

		// determine desired downsampling
		final HashMap< Integer, Integer > dsXY = new HashMap<>();

		for ( final ViewDescription vd : group )
		{
			// downsampleXY == 0 : a bit less then z-resolution
			// downsampleXY == -1 : a bit more then z-resolution
			final int downsampleXY;

			if ( downsampleXYIndex < 1 )
				downsampleXY = DownsampleTools.downsampleFactor( downsampleXYIndex, downsampleZ, vd.getViewSetup().getVoxelSize() );
			else
				downsampleXY = downsampleXYIndex;

			if ( dsXY.containsKey( downsampleXY ) )
				dsXY.put( downsampleXY, dsXY.get( downsampleXY ) + 1 );
			else
				dsXY.put( downsampleXY, 1 );
		}

		int downsampleXY = -1;
		int maxCount = -1;

		for ( final int ds : dsXY.keySet() )
		{
			final int count = dsXY.get( ds );

			if ( count > maxCount )
			{
				downsampleXY = ds;
				maxCount = count;
			}
		}

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
