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

import java.awt.Checkbox;
import java.awt.Choice;
import java.awt.Component;
import java.awt.EventQueue;
import java.lang.reflect.InvocationTargetException;
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
	public static String defaultNumCoefficients = "1,1,1";
	public static double defaultRenderScale = DEFAULTS.renderScale;
	public static int defaultThreshold = Threshold.LI.ordinal();
	public static double defaultMinIntensity = DEFAULTS.minIntensity;
	public static int defaultModel = DEFAULTS.model.ordinal();
	/** MEDIAN matches are pure gains: without the gain field they are solved with SCALE unless asked otherwise */
	public static int defaultMedianModel = TransformationModel.SCALE.ordinal();
	public static int defaultRegularization1 = DEFAULTS.regularization1.ordinal();
	public static double defaultLambda1 = DEFAULTS.lambda1;
	public static int defaultRegularization2 = DEFAULTS.regularization2.ordinal();
	public static double defaultLambda2 = DEFAULTS.lambda2;

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

	/**
	 * One dialog: the choices that decide what else applies come first (method, coefficients, render scale,
	 * threshold, gain field), and only the fields those choices use are shown, updated as they change. Every field
	 * always exists and is read in the same order, so a recorded macro replays whatever was chosen.
	 *
	 * @return the parameters, or null if canceled or invalid
	 */
	public static Parameters queryParameters()
	{
		final GenericDialog gd = new GenericDialog( "Intensity Correction" );
		final boolean[] medianModelShown = { Method.values()[ defaultMethod ] == Method.MEDIAN };

		gd.addChoice( "Matching_method", names( Method.values() ), Method.values()[ defaultMethod ].name() );
		gd.addStringField( "Number_of_coefficients (x,y,z)", defaultNumCoefficients, 10 );
		gd.addNumericField( "Render_scale (0.25 = 4x downsampled)", defaultRenderScale, 3 );
		gd.addChoice( "Foreground_threshold", thresholdChoices, thresholdChoices[ defaultThreshold ] );
		final List< Component > fixedThreshold = added( gd, () -> gd.addNumericField( "Minimum_intensity", defaultMinIntensity, 1 ) );
		final List< Component > autoThreshold = added( gd, () -> {
			gd.addCheckbox( "Correct_only_above_threshold (preview & fusion default)", FusionGUI.defaultIntensityOnlyAboveThreshold );
			gd.addNumericField( "Minimal_signal above threshold (0 = off)", defaultMinSignal, 1 );
		} );
		// MEDIAN only: the gain field needs one gain ratio per pair, which a RANSAC / HISTOGRAM line only approximates
		final List< Component > gainFieldOption = added( gd, () -> gd.addCheckbox( "Gain_field (per-view gains + one ramp across every view)", defaultGainField ) );
		final List< Component > gainField = added( gd, () -> gd.addNumericField( "Gain_field_lambda (ridge on the log gains)", defaultGainFieldLambda, 4 ) );
		final List< Component > coefficientSolve = added( gd, () -> gd.addNumericField( "Solver_iterations", defaultSolverIterations, 0 ) );
		final List< Component > backgroundWeight = added( gd, () -> gd.addNumericField( "Background_weight (0 = off)", defaultBackgroundWeight, 1 ) );

		gd.addMessage( "" );
		final List< Component > ransac = added( gd, () -> {
			gd.addNumericField( "RANSAC_iterations", defaultRansacIterations, 0 );
			gd.addNumericField( "Maximal_error (intensity)", defaultMaxEpsilon, 2 );
			gd.addNumericField( "Minimal_inlier_ratio", defaultMinInlierRatio, 2 );
			gd.addNumericField( "Minimal_number_of_inliers", defaultMinNumInliers, 0 );
			gd.addNumericField( "Maximal_trust (x median cost)", defaultMaxTrust, 1 );
		} );
		final List< Component > histogram = added( gd, () -> gd.addNumericField( "Number_of_histogram_samples", defaultNumBins, 0 ) );
		gd.addNumericField( "Minimal_number_of_overlapping_pixels", defaultMinNumCandidates, 0 );
		gd.addNumericField( "Maximum_intensity (0 = no limit)", defaultMaxIntensity, 1 );
		gd.addNumericField( "Bleach_factor (intensity lost per earlier exposure, views acquired in setup id order; 0 = off)", defaultBleachFactor, 3 );

		final List< Component > model = added( gd, () -> {
			gd.addMessage( "" );
			final int m = medianModelShown[ 0 ] ? defaultMedianModel : defaultModel;
			gd.addChoice( "Intensity_model", names( TransformationModel.values() ), TransformationModel.values()[ m ].name() );
			gd.addChoice( "Regularization_1", names( RegularizationModel.values() ), RegularizationModel.values()[ defaultRegularization1 ].name() );
			gd.addNumericField( "Lambda_1 [0..1]", defaultLambda1, 3 );
			gd.addChoice( "Regularization_2", names( RegularizationModel.values() ), RegularizationModel.values()[ defaultRegularization2 ].name() );
			gd.addNumericField( "Lambda_2 [0..1]", defaultLambda2, 3 );
		} );
		gd.addMessage( "MEDIAN: ratio of the medians of the voxels both views see (a pure gain per pair); HISTOGRAM: matched\n"
				+ "intensity quantiles; RANSAC: robust fit to voxel pairs. The model is used by RANSAC / HISTOGRAM matching\n"
				+ "and by the solve without the gain field." );

		final Choice methodChoice = ( Choice ) gd.getChoices().get( 0 );
		final Choice thresholdChoice = ( Choice ) gd.getChoices().get( 1 );
		final Choice modelChoice = ( Choice ) gd.getChoices().get( 2 );
		final Checkbox gainFieldBox = ( Checkbox ) gd.getCheckboxes().get( 1 );

		final Runnable update = () -> {
			final Method method = Method.values()[ methodChoice.getSelectedIndex() ];
			final boolean fixed = Threshold.values()[ thresholdChoice.getSelectedIndex() ] == Threshold.FIXED;
			// MEDIAN matches are pure gains: offer SCALE (or what was last chosen for MEDIAN) when switching to it
			final boolean median = method == Method.MEDIAN;
			final boolean gain = median && gainFieldBox.getState();
			if ( median != medianModelShown[ 0 ] )
			{
				if ( median )
					defaultModel = modelChoice.getSelectedIndex();
				else
					defaultMedianModel = modelChoice.getSelectedIndex();
				modelChoice.select( median ? defaultMedianModel : defaultModel );
				medianModelShown[ 0 ] = median;
			}
			show( fixedThreshold, fixed );
			show( autoThreshold, !fixed );
			show( gainFieldOption, median );
			show( gainField, gain );
			show( coefficientSolve, !gain );
			show( backgroundWeight, !fixed && !gain );
			show( ransac, method == Method.RANSAC );
			show( histogram, method == Method.HISTOGRAM );
			show( model, !gain );
		};
		update.run();
		// ImageJ calls the listener once more with a null event as the dialog closes, and the macro recorder records
		// only the fields read in that call (it stops recording right after), so that call reads them all
		final Parameters[] closing = { null };
		gd.addDialogListener( ( dialog, e ) -> {
			if ( e == null )
			{
				closing[ 0 ] = read( gd );
				return true;
			}
			update.run();
			gd.pack();
			return true;
		} );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return null;

		// after OK, the closing call runs on the event thread, possibly still after showDialog returned here
		if ( !EventQueue.isDispatchThread() )
		{
			try
			{
				EventQueue.invokeAndWait( () -> {} );
			}
			catch ( final InterruptedException | InvocationTargetException e )
			{
				throw new RuntimeException( e );
			}
		}
		final Parameters p = closing[ 0 ] != null ? closing[ 0 ] : read( gd );

		try
		{
			p.numCoefficients = Arrays.stream( defaultNumCoefficients.split( "," ) ).mapToInt( s -> Integer.parseInt( s.trim() ) ).toArray();
		}
		catch ( final NumberFormatException e )
		{
			IOFunctions.println( "Number of coefficients must be like 1,1,1 but was '" + defaultNumCoefficients + "'." );
			return null;
		}

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

	/** reads every field, shown or not, in the order they were added; what the choices do not use stays at its default */
	private static Parameters read( final GenericDialog gd )
	{
		final Parameters p = new Parameters();
		p.method = Method.values()[ defaultMethod = gd.getNextChoiceIndex() ];
		defaultNumCoefficients = gd.getNextString().trim();
		p.renderScale = defaultRenderScale = gd.getNextNumber();
		p.threshold = Threshold.values()[ defaultThreshold = gd.getNextChoiceIndex() ];
		defaultMinIntensity = gd.getNextNumber();
		final boolean onlyAboveThreshold = gd.getNextBoolean();
		defaultMinSignal = gd.getNextNumber();
		// the checkbox is remembered for MEDIAN, and is no option for the other methods
		p.gainField = ( defaultGainField = gd.getNextBoolean() ) && p.method == Method.MEDIAN;
		p.gainFieldLambda = defaultGainFieldLambda = gd.getNextNumber();
		p.solverIterations = defaultSolverIterations = ( int ) Math.round( gd.getNextNumber() );
		defaultBackgroundWeight = gd.getNextNumber();
		p.ransacIterations = defaultRansacIterations = ( int ) Math.round( gd.getNextNumber() );
		p.maxEpsilon = defaultMaxEpsilon = gd.getNextNumber();
		p.minInlierRatio = defaultMinInlierRatio = gd.getNextNumber();
		p.minNumInliers = defaultMinNumInliers = ( int ) Math.round( gd.getNextNumber() );
		p.maxTrust = defaultMaxTrust = gd.getNextNumber();
		p.numBins = defaultNumBins = ( int ) Math.round( gd.getNextNumber() );
		p.minNumCandidates = defaultMinNumCandidates = ( int ) Math.round( gd.getNextNumber() );
		defaultMaxIntensity = gd.getNextNumber();
		p.maxIntensity = defaultMaxIntensity > 0 ? defaultMaxIntensity : Double.NaN;
		p.bleachFactor = defaultBleachFactor = gd.getNextNumber();
		final int modelIndex = gd.getNextChoiceIndex();
		if ( p.method == Method.MEDIAN )
			defaultMedianModel = modelIndex;
		else
			defaultModel = modelIndex;
		p.model = TransformationModel.values()[ modelIndex ];
		p.regularization1 = RegularizationModel.values()[ defaultRegularization1 = gd.getNextChoiceIndex() ];
		p.lambda1 = defaultLambda1 = gd.getNextNumber();
		p.regularization2 = RegularizationModel.values()[ defaultRegularization2 = gd.getNextChoiceIndex() ];
		p.lambda2 = defaultLambda2 = gd.getNextNumber();

		// what the choices do not use stays at its default, whatever the hidden field holds
		if ( p.threshold == Threshold.FIXED )
			p.minIntensity = defaultMinIntensity;
		else
		{
			FusionGUI.defaultIntensityOnlyAboveThreshold = onlyAboveThreshold;
			p.minSignal = defaultMinSignal;
			if ( !p.gainField )
				p.backgroundWeight = defaultBackgroundWeight;
		}

		return p;
	}

	/** @return the components {@code adder} put into {@code gd} (labels and fields), to show or hide them together */
	private static List< Component > added( final GenericDialog gd, final Runnable adder )
	{
		final int before = gd.getComponentCount();
		adder.run();
		final List< Component > components = new ArrayList<>();
		for ( int i = before; i < gd.getComponentCount(); ++i )
			components.add( gd.getComponent( i ) );
		return components;
	}

	private static void show( final List< Component > components, final boolean visible )
	{
		components.forEach( c -> c.setVisible( visible ) );
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
