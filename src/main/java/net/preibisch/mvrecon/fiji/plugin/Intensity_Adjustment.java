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
package net.preibisch.mvrecon.fiji.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import ij.ImageJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionPipeline;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionPipeline.Method;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionPipeline.Parameters;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionPipeline.RegularizationModel;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionPipeline.Threshold;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionPipeline.TransformationModel;

/**
 * Computes per-view intensity-correction coefficients (spatially varying gain and offset) by matching the
 * intensities of overlapping views and solving globally, as BigStitcher-Spark's {@code match-intensities} +
 * {@code solve-intensities}. The coefficients are stored with the dataset (written next to the XML on save) and
 * used by fusion ("Adjust image intensities") and the explorer's preview (key 'i').
 */
public class Intensity_Adjustment implements PlugIn
{
	private static final Parameters DEFAULTS = new Parameters();

	public static int defaultMethod = DEFAULTS.method.ordinal();
	public static String defaultNumCoefficients = "8,8,8";
	public static double defaultRenderScale = DEFAULTS.renderScale;
	public static int defaultThreshold = DEFAULTS.threshold.ordinal();
	public static double defaultMinIntensity = DEFAULTS.minIntensity;
	public static int defaultModel = DEFAULTS.model.ordinal();
	public static int defaultRegularization1 = DEFAULTS.regularization1.ordinal();
	public static double defaultLambda1 = DEFAULTS.lambda1;
	public static int defaultRegularization2 = DEFAULTS.regularization2.ordinal();
	public static double defaultLambda2 = DEFAULTS.lambda2;
	public static boolean defaultAdvanced = false;

	public static double defaultMaxIntensity = 0;
	public static int defaultMinNumCandidates = DEFAULTS.minNumCandidates;
	public static int defaultRansacIterations = DEFAULTS.ransacIterations;
	public static double defaultMaxEpsilon = DEFAULTS.maxEpsilon;
	public static double defaultMinInlierRatio = DEFAULTS.minInlierRatio;
	public static int defaultMinNumInliers = DEFAULTS.minNumInliers;
	public static double defaultMaxTrust = DEFAULTS.maxTrust;
	public static int defaultNumBins = DEFAULTS.numBins;
	public static int defaultSolverIterations = DEFAULTS.solverIterations;
	public static double defaultMinSignal = DEFAULTS.minSignal;
	public static double defaultBackgroundWeight = DEFAULTS.backgroundWeight;
	public static double defaultBleachFactor = DEFAULTS.bleachFactor;
	public static boolean defaultGainField = DEFAULTS.gainField;
	public static double defaultGainFieldLambda = DEFAULTS.gainFieldLambda;

	private static final String[] thresholdChoices = { "Fixed minimum intensity", "LI (per view)", "OTSU (per view)" };

	@Override
	public void run( final String arg0 )
	{
		final LoadParseQueryXML result = new LoadParseQueryXML();

		if ( !result.queryXML( "intensity correction of overlapping views", true, true, true, true, true ) )
			return;

		intensityAdjustment(
			result.getData(),
			SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() ),
			result.getXMLFileName(),
			true );
	}

	public boolean intensityAdjustment( final SpimData2 data, final List< ViewId > viewIds )
	{
		return intensityAdjustment( data, viewIds, null, false );
	}

	/**
	 * Asks for the parameters, computes the coefficients for {@code views} and stores them in {@code data},
	 * replacing those of these views.
	 */
	public boolean intensityAdjustment(
			final SpimData2 data,
			final List< ViewId > views,
			final String xmlFileName,
			final boolean saveXML )
	{
		final List< ViewId > viewIds = new ArrayList<>( views );
		final List< ViewId > removed = SpimData2.filterMissingViews( data, viewIds );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " + removed.size() + " views because they are not present." );

		final Parameters p = queryParameters();
		if ( p == null )
			return false;

		final Map< ViewId, Coefficients > coefficients;
		try
		{
			coefficients = IntensityCorrectionPipeline.compute( data, viewIds, p, Threads.numThreads() );
		}
		catch ( final RuntimeException e )
		{
			IOFunctions.println( "Intensity correction failed: " + e );
			e.printStackTrace();
			return false;
		}

		viewIds.forEach( data.getIntensityAdjustments()::remove );
		data.getIntensityAdjustments().putAll( coefficients );
		IOFunctions.println( "Intensity correction coefficients for " + coefficients.size() + " of " + viewIds.size()
				+ " views (views without an overlapping neighbor stay uncorrected)." );

		if ( saveXML )
			new XmlIoSpimData2().saveWithFilename( data, xmlFileName );

		return true;
	}

	/** @return the parameters, or null if canceled or invalid */
	public static Parameters queryParameters()
	{
		final GenericDialog gd = new GenericDialog( "Intensity Correction" );

		gd.addChoice( "Matching_method", names( Method.values() ), Method.values()[ defaultMethod ].name() );
		gd.addStringField( "Number_of_coefficients (x,y,z)", defaultNumCoefficients, 10 );
		gd.addNumericField( "Render_scale (0.25 = 4x downsampled)", defaultRenderScale, 3 );
		gd.addMessage( "" );
		gd.addChoice( "Foreground_threshold", thresholdChoices, thresholdChoices[ defaultThreshold ] );
		gd.addNumericField( "Minimum_intensity (fixed threshold only)", defaultMinIntensity, 1 );
		gd.addCheckbox( "Correct_only_above_threshold (preview & fusion default; LI/OTSU only)", FusionGUI.defaultIntensityOnlyAboveThreshold );
		gd.addMessage( "" );
		gd.addChoice( "Intensity_model", names( TransformationModel.values() ), TransformationModel.values()[ defaultModel ].name() );
		gd.addChoice( "Regularization_1", names( RegularizationModel.values() ), RegularizationModel.values()[ defaultRegularization1 ].name() );
		gd.addNumericField( "Lambda_1 [0..1]", defaultLambda1, 3 );
		gd.addChoice( "Regularization_2", names( RegularizationModel.values() ), RegularizationModel.values()[ defaultRegularization2 ].name() );
		gd.addNumericField( "Lambda_2 [0..1]", defaultLambda2, 3 );
		gd.addMessage( "" );
		gd.addCheckbox( "Gain_field (per-view gains + one ramp across every view, instead of the model above; use MEDIAN)", defaultGainField );
		gd.addNumericField( "Gain_field_lambda (ridge on the log gains)", defaultGainFieldLambda, 4 );
		gd.addMessage( "" );
		gd.addCheckbox( "Show_advanced_parameters", defaultAdvanced );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return null;

		final Parameters p = new Parameters();
		p.method = Method.values()[ defaultMethod = gd.getNextChoiceIndex() ];
		defaultNumCoefficients = gd.getNextString().trim();
		p.renderScale = defaultRenderScale = gd.getNextNumber();
		p.threshold = Threshold.values()[ defaultThreshold = gd.getNextChoiceIndex() ];
		p.minIntensity = defaultMinIntensity = gd.getNextNumber();
		FusionGUI.defaultIntensityOnlyAboveThreshold = gd.getNextBoolean();
		p.model = TransformationModel.values()[ defaultModel = gd.getNextChoiceIndex() ];
		p.regularization1 = RegularizationModel.values()[ defaultRegularization1 = gd.getNextChoiceIndex() ];
		p.lambda1 = defaultLambda1 = gd.getNextNumber();
		p.regularization2 = RegularizationModel.values()[ defaultRegularization2 = gd.getNextChoiceIndex() ];
		p.lambda2 = defaultLambda2 = gd.getNextNumber();
		p.gainField = defaultGainField = gd.getNextBoolean();
		p.gainFieldLambda = defaultGainFieldLambda = gd.getNextNumber();
		final boolean advanced = defaultAdvanced = gd.getNextBoolean();

		try
		{
			p.numCoefficients = Arrays.stream( defaultNumCoefficients.split( "," ) ).mapToInt( s -> Integer.parseInt( s.trim() ) ).toArray();
		}
		catch ( final NumberFormatException e )
		{
			IOFunctions.println( "Number of coefficients must be like 8,8,8 but was '" + defaultNumCoefficients + "'." );
			return null;
		}

		if ( advanced )
		{
			final GenericDialog gda = new GenericDialog( "Intensity Correction: advanced" );
			if ( p.method == Method.RANSAC )
			{
				gda.addNumericField( "RANSAC_iterations", defaultRansacIterations, 0 );
				gda.addNumericField( "Maximal_error (intensity)", defaultMaxEpsilon, 2 );
				gda.addNumericField( "Minimal_inlier_ratio", defaultMinInlierRatio, 2 );
				gda.addNumericField( "Minimal_number_of_inliers", defaultMinNumInliers, 0 );
				gda.addNumericField( "Maximal_trust (x median cost)", defaultMaxTrust, 1 );
			}
			else if ( p.method == Method.HISTOGRAM )
			{
				gda.addNumericField( "Number_of_histogram_samples", defaultNumBins, 0 );
			}
			gda.addNumericField( "Minimal_number_of_overlapping_pixels", defaultMinNumCandidates, 0 );
			gda.addNumericField( "Maximum_intensity (0 = no limit)", defaultMaxIntensity, 1 );
			gda.addNumericField( "Solver_iterations", defaultSolverIterations, 0 );
			gda.addNumericField( "Bleach_factor (intensity lost per earlier exposure, views acquired in setup id order; 0 = off)", defaultBleachFactor, 3 );
			if ( p.threshold != Threshold.FIXED )
			{
				gda.addNumericField( "Minimal_signal above threshold (0 = off)", defaultMinSignal, 1 );
				gda.addNumericField( "Background_weight (0 = off)", defaultBackgroundWeight, 1 );
			}

			gda.showDialog();
			if ( gda.wasCanceled() )
				return null;

			if ( p.method == Method.RANSAC )
			{
				p.ransacIterations = defaultRansacIterations = ( int ) Math.round( gda.getNextNumber() );
				p.maxEpsilon = defaultMaxEpsilon = gda.getNextNumber();
				p.minInlierRatio = defaultMinInlierRatio = gda.getNextNumber();
				p.minNumInliers = defaultMinNumInliers = ( int ) Math.round( gda.getNextNumber() );
				p.maxTrust = defaultMaxTrust = gda.getNextNumber();
			}
			else if ( p.method == Method.HISTOGRAM )
			{
				p.numBins = defaultNumBins = ( int ) Math.round( gda.getNextNumber() );
			}
			p.minNumCandidates = defaultMinNumCandidates = ( int ) Math.round( gda.getNextNumber() );
			defaultMaxIntensity = gda.getNextNumber();
			p.solverIterations = defaultSolverIterations = ( int ) Math.round( gda.getNextNumber() );
			p.bleachFactor = defaultBleachFactor = gda.getNextNumber();
			if ( p.threshold != Threshold.FIXED )
			{
				p.minSignal = defaultMinSignal = gda.getNextNumber();
				p.backgroundWeight = defaultBackgroundWeight = gda.getNextNumber();
			}
		}
		else
		{
			p.ransacIterations = defaultRansacIterations;
			p.maxEpsilon = defaultMaxEpsilon;
			p.minInlierRatio = defaultMinInlierRatio;
			p.minNumInliers = defaultMinNumInliers;
			p.maxTrust = defaultMaxTrust;
			p.numBins = defaultNumBins;
			p.minNumCandidates = defaultMinNumCandidates;
			p.solverIterations = defaultSolverIterations;
			p.bleachFactor = defaultBleachFactor;
			if ( p.threshold != Threshold.FIXED )
			{
				p.minSignal = defaultMinSignal;
				p.backgroundWeight = defaultBackgroundWeight;
			}
		}
		p.maxIntensity = defaultMaxIntensity > 0 ? defaultMaxIntensity : Double.NaN;

		try
		{
			p.validate();
		}
		catch ( final IllegalArgumentException e )
		{
			IOFunctions.println( "Intensity correction: " + e.getMessage() );
			return null;
		}

		return p;
	}

	private static String[] names( final Enum< ? >[] values )
	{
		return Arrays.stream( values ).map( Enum::name ).toArray( String[]::new );
	}

	public static void main( final String[] args )
	{
		new ImageJ();

		if ( !System.getProperty("os.name").toLowerCase().contains( "mac" ) )
			GenericLoadParseQueryXML.defaultXMLURI = "/home/preibisch/Documents/Microscopy/SPIM/HisYFP-SPIM//dataset_tp18.xml";
		else
			GenericLoadParseQueryXML.defaultXMLURI = "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM//dataset.xml";

		new Intensity_Adjustment().run( null );
	}
}
