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
package net.preibisch.mvrecon.fiji.plugin.fusion;

import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Label;
import java.awt.TextField;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import ij.IJ;
import ij.gui.GenericDialog;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Image_Fusion;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.plugin.util.PluginHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxTools;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.export.ExportLarge2DTIFF;
import net.preibisch.mvrecon.process.export.ExportN5Api;
import net.preibisch.mvrecon.process.export.ImgExport;
import net.preibisch.mvrecon.process.export.OpenSeaDragon;
import net.preibisch.mvrecon.process.export.Save3dTIFF;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.intensityadjust.IntensityAdjustmentTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class FusionGUI implements FusionExportInterface
{
	//public static int defaultCache = 2;
	public static int[] cellDim = new int[]{ 10, 10, 10 };
	public static int maxCacheSize = Integer.MAX_VALUE;

	public static double defaultDownsampling = 1.0;
	public static int defaultBB = 0;

	public static String[] interpolationTypes = new String[]{ "Nearest Neighbor", "Linear Interpolation" };
	public static int defaultInterpolation = 1;

	public enum FusionType {
		AVG, AVG_BLEND, AVG_CONTENT, AVG_BLEND_CONTENT, MAX_INTENSITY, LOWEST_VIEWID_WINS, HIGHEST_VIEWID_WINS,
		CLOSEST_PIXEL_WINS
	}

	public static String[] fusionTypes = new String[] { "Avg", "Avg, Blending", "Avg, Content Based",
			"Avg, Blending & Content Based", "Max intensity", "First Tile Wins (lowest ViewSetupId)",
			"Last Tile Wins (highest ViewSetupId)", "Pixel Closest To Its Tile Center Wins" };
	public static int defaultFusionType = 1;

	public static String[] pixelTypes1 = new String[]{ "32-bit floating point", "16-bit unsigned integer", "8-bit unsigned integer" };
	public static int defaultPixelType = 0;

	public static int defaultDefineMinMax = 0;
	public static double defaultMin = 0;
	public static double defaultMax = 65535;

	public static String[] splittingTypes = new String[]{
			"Each timepoint & channel",
			"Each timepoint, channel & illumination",
			"All views together",
			"Each view" };

	public static int defaultSplittingType = 0;

	public static boolean defaultAdjustIntensities = false;
	public static boolean defaultPreserveAnisotropy = false;

	public final static ArrayList< ImgExport > staticImgExportAlgorithms = new ArrayList< ImgExport >();
	public final static String[] imgExportDescriptions;
	public static int defaultImgExportAlgorithm = 0;

	protected int interpolation = defaultInterpolation;
	protected int boundingBox = defaultBB;
	protected int pixelType = defaultPixelType;
	protected int defineMinMax = defaultDefineMinMax;
	protected double min = defaultMin;
	protected double max = defaultMax;
	protected int splittingType = defaultSplittingType;
	protected double downsampling = defaultDownsampling;
	protected int fusionType = defaultFusionType;
	protected boolean adjustIntensities = defaultAdjustIntensities;
	protected boolean preserveAnisotropy = defaultPreserveAnisotropy;
	protected double avgAnisoF;
	protected int imgExport = defaultImgExportAlgorithm;

	protected NonRigidParametersGUI nrgui;

	static
	{
		IOFunctions.printIJLog = true;

		staticImgExportAlgorithms.add( new DisplayImage() );
		staticImgExportAlgorithms.add( new ExportN5Api() );
		staticImgExportAlgorithms.add( new Save3dTIFF( null ) );
		staticImgExportAlgorithms.add( new OpenSeaDragon() );
		staticImgExportAlgorithms.add( new ExportLarge2DTIFF() );

		//staticImgExportAlgorithms.add( new ExportSpimData2TIFF() );
		//staticImgExportAlgorithms.add( new ExportSpimData2HDF5() );
		//staticImgExportAlgorithms.add( new AppendSpimData2HDF5() );

		imgExportDescriptions = new String[ staticImgExportAlgorithms.size() ];

		for ( int i = 0; i < staticImgExportAlgorithms.size(); ++i )
			imgExportDescriptions[ i ] = staticImgExportAlgorithms.get( i ).getDescription();
	}

	final protected SpimData2 spimData;
	final List< ViewId > views;
	final List< BoundingBox > allBoxes;

	public FusionGUI( final SpimData2 spimData, final List< ViewId > views )
	{
		this.spimData = spimData;
		this.views = new ArrayList<>();
		this.views.addAll( views );

		// filter not present ViewIds
		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, views );
		if ( removed.size() > 0 ) IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		// get all bounding boxes and two extra ones
		this.allBoxes = BoundingBoxTools.getAllBoundingBoxes( spimData, views, true );

		// average anisotropy of input views
		this.avgAnisoF = TransformationTools.getAverageAnisotropyFactor( spimData, views );
	}

	@Override
	public SpimData2 getSpimData() { return spimData; }

	@Override
	public List< ViewId > getViews() { return views; }

	public Interval getBoundingBox() { return allBoxes.get( boundingBox ); }
	public void setBoundingBox( final Interval bb ) { this.allBoxes.set( boundingBox, new BoundingBox( bb ) ); }

	@Override
	public Interval getDownsampledBoundingBox()
	{
		Pair<Interval, AffineTransform3D> scaledBB =
				FusionTools.createAnisotropicBoundingBox(
						getBoundingBox(),
						getAnisotropyFactor() );

		return FusionTools.createDownsampledBoundingBox( scaledBB.getA(), getDownsampling() ).getA();

		/*
		if ( !Double.isNaN( downsampling ) )
			return TransformVirtual.scaleBoundingBox( getBoundingBox(), 1.0 / downsampling );
		else
			return getBoundingBox();
		*/
	}
	public int getInterpolation() { return interpolation; }

	@Override
	public int getPixelType() { return pixelType; }

	//public int getCacheType() { return cacheType; }

	public boolean manuallyDefinedMinMax() { return defineMinMax==1; }
	public double minIntensity() { return min; }
	public double maxIntensity() { return max; }

	public NonRigidParametersGUI getNonRigidParameters() { return nrgui; }

	@Override
	public double getDownsampling(){ return downsampling; }

	public FusionType getFusionType() { return FusionType.values()[ fusionType ]; }

	public boolean adjustIntensities() { return adjustIntensities; }

	@Override
	public double getAnisotropyFactor() { return avgAnisoF; }

	@Override
	public int getSplittingType() { return splittingType; }

	@Override
	public ImgExport getNewExporterInstance() { return staticImgExportAlgorithms.get( imgExport ).newInstance(); }

	public double[] defineMinMax( final double[] autoValues )
	{
		final String[] values = new String[] {
				"Auto-load from input data (values shown below)",
				"Manually define range of input data (in next dialog)"};

		final GenericDialog gd = new GenericDialog( "Define min/max values for image export" );

		gd.addMessage( "Note: you are exporting images to a bounded range (e.g. 8-bit, 16-bit),\n"
				+ "thus the fused values need to be scaled to the respective range.\n"
				+ "We can try to load the range from the input data automatically.\n"
				+ "(those values are displayed below -- except you previously changed them).\n"
				+ "You can override these by selecting 'manual' and providing the values.", GUIHelper.smallStatusFont, GUIHelper.neutral );

		gd.addMessage( "Tip: 8-bit range [0..255], 16-bit range [0..65535]", GUIHelper.smallStatusFont, GUIHelper.neutral );
		gd.addMessage( "Tip: Usually you can leave everthing as-is, but if your original data\n"
				+ "was 8-bit and you export as 8-bit you should specify [0..255] here.", GUIHelper.smallStatusFont, GUIHelper.warning );

		gd.addChoice(
				"Define_input range",
				values,
				values[ defaultDefineMinMax ] );

		if ( autoValues == null )
		{
			gd.addMessage( "Min & max could not be found automatically, please specify it in the next dialog\n(we only show this dialog so it stays macro-recordable)", GUIHelper.mediumstatusNonItalicfont, GUIHelper.neutral );
		}
		else
		{
			gd.addMessage(
					"Automatically found min="+autoValues[ 0 ]+"\n" +
					"Automatically found max="+autoValues[ 1 ], GUIHelper.mediumstatusNonItalicfont, GUIHelper.neutral );
		}

		gd.showDialog();
		if ( gd.wasCanceled() )
			return null;

		defineMinMax = defaultDefineMinMax = gd.getNextChoiceIndex();

		double[] minmax = new double[ 2 ];

		if ( autoValues != null && defineMinMax == 0 )
		{
			defineMinMax = 0;
			minmax[ 0 ] = defaultMin = autoValues[ 0 ];
			minmax[ 1 ] = defaultMax = autoValues[ 1 ];
		}
		else
		{
			defineMinMax = 1;
			final GenericDialog gd2 = new GenericDialog( "Define min/max values for image export (2)" );
			gd2.addNumericField( "min (input intensities)", autoValues == null ? defaultMin : autoValues[ 0 ] );
			gd2.addNumericField( "max (input intensities)", autoValues == null ? defaultMax : autoValues[ 1 ] );

			gd2.showDialog();
			if ( gd2.wasCanceled() )
				return null;

			minmax[ 0 ] = defaultMin = gd2.getNextNumber();
			minmax[ 1 ] = defaultMax = gd2.getNextNumber();
		}

		return minmax;
	}

	public boolean queryDetails()
	{
		final boolean enableNonRigid = NonRigidParametersGUI.enableNonRigid;
		final Choice boundingBoxChoice, pixelTypeChoice, /*cachingChoice, */nonrigidChoice, splitChoice;
		final TextField downsampleField;
		final Checkbox anisoCheckbox;

		final String[] choices = FusionGUI.getBoundingBoxChoices( allBoxes );
		final String[] choicesForMacro = FusionGUI.getBoundingBoxChoices( allBoxes, false );

		if ( defaultBB >= choices.length )
			defaultBB = 0;

		final boolean hasIntensityAdjustments = IntensityAdjustmentTools.containsAdjustments( spimData.getIntensityAdjustments(), views );

		final GenericDialog gd = new GenericDialog( "Image Fusion" );
		Label label1 = null, label2 = null;

		// use macro-compatible choice names in headless mode
		if ( !PluginHelper.isHeadless() )
			gd.addChoice( "Bounding_Box", choices, choices[ defaultBB ] );
		else
			gd.addChoice( "Bounding_Box", choicesForMacro, choicesForMacro[ defaultBB ] );
		boundingBoxChoice = PluginHelper.isHeadless() ? null : (Choice)gd.getChoices().lastElement();

		gd.addSlider( "Downsampling", 1.0, 16.0, defaultDownsampling );
		downsampleField = PluginHelper.isHeadless() ? null : (TextField)gd.getNumericFields().lastElement();

		gd.addChoice( "Interpolation", interpolationTypes, interpolationTypes[ defaultInterpolation ] );

		gd.addChoice( "Fusion_type", fusionTypes, fusionTypes[ defaultFusionType ] );

		gd.addChoice( "Pixel_type", pixelTypes1, pixelTypes1[ defaultPixelType ] );
		pixelTypeChoice = PluginHelper.isHeadless() ? null : (Choice)gd.getChoices().lastElement();
		//gd.addCheckbox( "Manually_define_min_max intensity for fusion (only relevant for 16-bit)", defaultDefineMinMax );
		//gd.addMessage( "Note: if if unchecked, you may be asked to define min/max if it cannot be determined from the input data.", GUIHelper.smallStatusFont, GUIHelper.neutral );

		//gd.addChoice( "Image ", FusionTools.imgDataTypeChoice, FusionTools.imgDataTypeChoice[ defaultCache ] );
		//cachingChoice = PluginHelper.isHeadless() ? null : (Choice)gd.getChoices().lastElement();
		//gd.addMessage( "We advise using VIRTUAL for saving at TIFF, and CACHED for saving as HDF5 if memory is low", GUIHelper.smallStatusFont, GUIHelper.neutral );

		this.nrgui = new NonRigidParametersGUI( spimData, views );
		if ( enableNonRigid )
		{
			this.nrgui.addQuery( gd );
			nonrigidChoice = PluginHelper.isHeadless() ? null : (Choice)gd.getChoices().lastElement();
		}
		else
		{
			this.nrgui.isActive = false;
			nonrigidChoice = null;
		}

		//gd.addCheckbox( "Blend images smoothly", defaultUseBlending );
		//gd.addCheckbox( "Use content based fusion (warning, slow)", defaultUseContentBased );
		//contentbasedCheckbox = PluginHelper.isHeadless() ? null : (Checkbox)gd.getCheckboxes().lastElement();

		if ( hasIntensityAdjustments )
			gd.addCheckbox( "Adjust_image_intensities (only use with 32-bit output)", defaultAdjustIntensities );

		IJ.log( "avgAnisoF: " + avgAnisoF );
		if ( avgAnisoF > 1.01 || avgAnisoF < 0.99 ) // for numerical instabilities (computed upon instantiation)
		{
			gd.addCheckbox( "Preserve_original data anisotropy (shrink image " + TransformationTools.f.format( avgAnisoF ) + " times in z) ", defaultPreserveAnisotropy );
			anisoCheckbox = PluginHelper.isHeadless() ? null : (Checkbox)gd.getCheckboxes().lastElement();
			gd.addMessage(
					"WARNING: Enabling this means to 'shrink' (or in rate case 'stretch') the dataset in z the same way the input\n" +
					"images were scaled. Only use this if this is not a multiview dataset.", GUIHelper.smallStatusFont, GUIHelper.warning );
		}
		else
		{
			anisoCheckbox = null;
		}

		gd.addChoice( "Produce one fused image for", splittingTypes, splittingTypes[ defaultSplittingType ] );
		splitChoice = PluginHelper.isHeadless() ? null : (Choice)gd.getChoices().lastElement();

		gd.addChoice( "Fused_image", imgExportDescriptions, imgExportDescriptions[ defaultImgExportAlgorithm ] );

		gd.addMessage( "Estimated size: ", GUIHelper.largestatusfont, GUIHelper.good );
		if ( !PluginHelper.isHeadless() )  label1 = (Label)gd.getMessage();
		gd.addMessage( "???x???x??? pixels", GUIHelper.smallStatusFont, GUIHelper.good );
		if ( !PluginHelper.isHeadless() )  label2 = (Label)gd.getMessage();

		if ( !PluginHelper.isHeadless() )
		{
			final ManageFusionDialogListeners m = new ManageFusionDialogListeners(
					gd,
					boundingBoxChoice,
					downsampleField,
					pixelTypeChoice,
					//cachingChoice,
					nonrigidChoice,
					//contentbasedCheckbox,
					anisoCheckbox,
					splitChoice,
					label1,
					label2,
					this );
	
			m.update();
		}

		gd.showDialog();

		if ( gd.wasCanceled() )
			return false;

		// Hacky: replace the bounding box choices with a macro-compatible version
		// i.e. choices that do not contain the dimensions (AxBxC px), which we do not know in advance
		if ( !PluginHelper.isHeadless() )
		{
			final Choice bboxChoice = (Choice) gd.getChoices().get( 0 );
			final int selectedBbox = bboxChoice.getSelectedIndex();
			for (int i = 0; i<choices.length; i++)
			{
				bboxChoice.remove( 0 );
				bboxChoice.addItem( choicesForMacro[i] );
			}
			bboxChoice.select( selectedBbox );
		}

		boundingBox = defaultBB = gd.getNextChoiceIndex();
		downsampling = defaultDownsampling = gd.getNextNumber();

		if ( downsampling == 1.0 )
			downsampling = Double.NaN;

		interpolation = defaultInterpolation = gd.getNextChoiceIndex();
		fusionType = defaultFusionType = gd.getNextChoiceIndex();
		pixelType = defaultPixelType = gd.getNextChoiceIndex();
		//defineMinMax = defaultDefineMinMax = gd.getNextBoolean();
		//cacheType = defaultCache = gd.getNextChoiceIndex();

		if ( enableNonRigid )
		{
			if ( !this.nrgui.parseQuery( gd, false ) )
				return false;
		}

		if ( hasIntensityAdjustments )
			adjustIntensities = defaultAdjustIntensities = gd.getNextBoolean();
		else
			adjustIntensities = false;
		if ( avgAnisoF > 1.01 || avgAnisoF < 0.99 )
			preserveAnisotropy = defaultPreserveAnisotropy = gd.getNextBoolean();
		else
			preserveAnisotropy = defaultPreserveAnisotropy = false;

		if ( !preserveAnisotropy )
			avgAnisoF = Double.NaN;

		splittingType = defaultSplittingType = gd.getNextChoiceIndex();
		imgExport = defaultImgExportAlgorithm = gd.getNextChoiceIndex();

		if ( this.nrgui.isActive() && this.nrgui.userSelectedAdvancedParameters() )
			if ( !this.nrgui.advancedParameters() )
				return false;

		if ( pixelType > 0 )
		{
			final double[] autominmax = Image_Fusion.determineInputBitDepth(
					views.stream().map( v -> spimData.getSequenceDescription().getViewDescriptions().get( v ) ).collect( Collectors.toList() ),
					spimData );

			final double[] minmax = defineMinMax( autominmax );

			if ( minmax == null )
				return false;

			this.min = minmax[ 0 ];
			this.max = minmax[ 1 ];
		}

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Selected Fusion Parameters: " );
		IOFunctions.println( "Downsampling: " + DownsampleTools.printDownsampling( getDownsampling() ) );
		IOFunctions.println( "BoundingBox: " + getBoundingBox() );
		IOFunctions.println( "DownsampledBoundingBox: " + getDownsampledBoundingBox() );
		IOFunctions.println( "PixelType: " + pixelTypes1[ getPixelType() ] );
		IOFunctions.println( "FusionType: " + fusionTypes[ getFusionType().ordinal() ] );
		IOFunctions.println( "Manually defined min/max: " + manuallyDefinedMinMax() );
		if ( manuallyDefinedMinMax() ) {
			IOFunctions.println( "Min: " + minIntensity() );
			IOFunctions.println( "Max: " + maxIntensity() );
		}
		IOFunctions.println( "Interpolation: " + interpolationTypes[ getInterpolation() ] );
		//IOFunctions.println( "CacheType: " + FusionTools.imgDataTypeChoice[ getCacheType() ] );
		//IOFunctions.println( "Blending: " + useBlending );
		IOFunctions.println( "Adjust intensities: " + adjustIntensities );
		//IOFunctions.println( "Content-based: " + useContentBased );
		IOFunctions.println( "AnisotropyFactor: " + avgAnisoF );
		IOFunctions.println( "Split by: " + splittingTypes[ getSplittingType() ] );
		IOFunctions.println( "Image Export: " + imgExportDescriptions[ imgExport ] );
		IOFunctions.println( "ImgLoader.isVirtual(): " + isImgLoaderVirtual() );
		IOFunctions.println( "ImgLoader.isMultiResolution(): " + isMultiResolution() );

		IOFunctions.println( "Non-Rigid active: " + this.nrgui.isActive() );
		if ( this.nrgui.isActive() )
		{
			IOFunctions.println( "Non-Rigid alpha: " + this.nrgui.getAlpha() );
			IOFunctions.println( "Non-Rigid cpd: " + this.nrgui.getControlPointDistance() );
			IOFunctions.println( "Non-Rigid showDistanceMap: " + this.nrgui.showDistanceMap() );
			IOFunctions.println( "Non-Rigid nonRigidAcrossTime: " + this.nrgui.nonRigidAcrossTime() );
			for ( final String label : this.nrgui.getLabels() )
				IOFunctions.println( "Non-Rigid Label: " + label );
		}

		return true;
	}

	public boolean isImgLoaderVirtual() { return isImgLoaderVirtual( spimData ); }

	public static boolean isImgLoaderVirtual( final SpimData spimData )
	{
		if ( MultiResolutionImgLoader.class.isInstance( spimData.getSequenceDescription().getImgLoader() ) )
			return true;

		// TODO: check for Davids virtual implementation of the normal imgloader
		return false;
	}

	public boolean isMultiResolution() { return isMultiResolution( spimData ); }

	public static boolean isMultiResolution( final SpimData spimData )
	{
		if ( MultiResolutionImgLoader.class.isInstance( spimData.getSequenceDescription().getImgLoader() ) )
			return true;
		else
			return false;
	}

	public static String[] getBoundingBoxChoices( final List< BoundingBox > allBoxes)
	{
		return getBoundingBoxChoices( allBoxes, true );
	}

	public static String[] getBoundingBoxChoices( final List< BoundingBox > allBoxes, boolean showDimensions )
	{
		final String[] choices = new String[ allBoxes.size() ];

		int i = 0;
		for ( final BoundingBox b : allBoxes )
			choices[ i++ ] = showDimensions 
								? b.getTitle() + " (" + b.dimension( 0 ) + "x" + b.dimension( 1 ) + "x" + b.dimension( 2 ) + "px)"
								: b.getTitle();

		return choices;
	}

	@Override
	public List< Group< ViewDescription > > getFusionGroups()
	{
		return getFusionGroups( getSpimData(), getViews(), getSplittingType() );
	}

	public static List< Group< ViewDescription > > getFusionGroups( final SpimData2 spimData, final List< ViewId > views, final int splittingType )
	{
		final ArrayList< ViewDescription > vds = SpimData2.getAllViewDescriptionsSorted( spimData, views );
		final List< Group< ViewDescription > > grouped;

		if ( splittingType < 2 ) // "Each timepoint & channel" or "Each timepoint, channel & illumination"
		{
			final HashSet< Class< ? extends Entity > > groupingFactors = new HashSet<>();

			groupingFactors.add( TimePoint.class );
			groupingFactors.add( Channel.class );

			if ( splittingType == 1 ) // "Each timepoint, channel & illumination"
				groupingFactors.add( Illumination.class );

			grouped = Group.splitBy( vds, groupingFactors );
		}
		else if ( splittingType == 2 ) // "All views together"
		{
			final Group< ViewDescription > allViews = new Group<>( vds );
			grouped = new ArrayList<>();
			grouped.add( allViews );
		}
		else // "All views"
		{
			grouped = new ArrayList<>();
			for ( final ViewDescription vd : vds )
				grouped.add( new Group<>( vd ) );
		}

		return grouped;
	}

	public static long maxNumInputPixelsPerInputGroup( final SpimData2 spimData, final List< ViewId > views, final int splittingType )
	{
		long maxNumPixels = 0;

		for ( final Group< ViewDescription > group : getFusionGroups( spimData, views, splittingType ) )
		{
			long numpixels = 0;

			for ( final ViewDescription vd : group )
				numpixels += Intervals.numElements( vd.getViewSetup().getSize() );

			maxNumPixels = Math.max( maxNumPixels, numpixels );
		}

		return maxNumPixels;
	}

	public static int inputBytePerPixel( final ViewId viewId, final SpimData2 spimData )
	{
		SetupImgLoader< ? > loader = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( viewId.getViewSetupId() );
		Object type = loader.getImageType();

		if ( UnsignedByteType.class.isInstance( type ) )
			return 1;
		else if ( UnsignedShortType.class.isInstance( type ) )
			return 2;
		else
			return 4;
	}
}
