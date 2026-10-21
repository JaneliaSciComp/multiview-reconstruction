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

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPointsN5;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * Creates a filtered label from an existing label: a label without points of its own that filters the points of the
 * base label by |response|, intensity and/or sigma on the fly and has its own correspondences (VirtualFilteredInterestPointsN5),
 * or, on request, a plain copy of the points that pass the filter (for readers that do not know filtered labels, and the
 * only way to replace the base label itself; any other existing label is overwritten by either: checkNewLabel).
 * Several entries per range field give one label per combination. Reachable from "Filter/Manage Interest Points" of the
 * explorer as "Filter interest points (response/sigma/intensity) ..." (also as "Filter interest points (threshold ramp)",
 * one entry per label, which prefills the response field with thresholds relative to the detection threshold of that
 * label, filterRamp) and as a plugin; "Filter_interest_points (response/sigma/intensity)" and "(threshold ramp)" of the
 * detection dialogs open this dialog for the label being detected (DifferenceOfGUI.queryFilter), the labels are created
 * once the points exist.
 *
 * @author Stephan Preibisch
 */
public class Filter_Detections implements PlugIn
{
	public static int defaultLabel = -1;
	/** the base label chosen last, by name (the interactive filter of the explorer sets it too); wins over defaultLabel if present */
	public static String defaultBaseLabel = null;
	public static String defaultResponse = "";
	public static String defaultIntensity = "";
	public static String defaultSigma = "";
	public static String defaultNewLabel = "";
	public static boolean defaultCopy = false;

	/** the width of the range fields: several entries must stay readable (Stephan, 2026-10-20) */
	public static int fieldColumns = 60;

	/** the threshold ramp (filterRamp, the detection dialogs): t x rampFactor^k for k = 1 .. rampSteps, and on until rampMinEnd is reached (at most rampMaxSteps steps) */
	public static double rampFactor = 2.0;
	public static int rampSteps = 6;
	public static double rampMinEnd = 0.1;
	public static int rampMaxSteps = 30;

	/** the "t=" of a parameter string of a detection, e.g. "DOG s=1.8 t=0.004 min=false ..." or "DOG-SS s=1.6 steps=4 ... t=0.008 ..." */
	protected static final Pattern thresholdPattern = Pattern.compile( "(?:^|\\s)t=([^\\s]+)" );

	public static class FilterParameters
	{
		public String baseLabel, newLabel;
		public InterestPointFilter filter;
		public boolean copy;
	}

	@Override
	public void run( final String arg )
	{
		final LoadParseQueryXML xml = new LoadParseQueryXML();

		if ( !xml.queryXML( "", true, true, true, true, true ) )
			return;

		final SpimData2 data = xml.getData();

		if ( !filter( data, SpimData2.getAllViewIdsSorted( data, xml.getViewSetupsToProcess(), xml.getTimePointsToProcess() ) ) )
			return;

		// write new xml
		new XmlIoSpimData2().saveWithFilename( data, xml.getXMLFileName() );
	}

	public static boolean filter( final SpimData2 data, final Collection< ? extends ViewId > viewCollection )
	{
		return filter( data, viewCollection, null );
	}

	/**
	 * Asks for the base label, the ranges and the new label and creates the filtered label(s) for the views
	 *
	 * @param presetResponse - what the response field is prefilled with (e.g. a threshold ramp, rampEntries), null = the last entry
	 */
	public static boolean filter( final SpimData2 data, final Collection< ? extends ViewId > viewCollection, final String presetResponse )
	{
		// filter not present ViewIds
		final ArrayList< ViewId > viewIds = new ArrayList<>();
		viewIds.addAll( viewCollection );

		final List< ViewId > removed  = SpimData2.filterMissingViews( data, viewIds );
		if ( removed.size() > 0 ) IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		final List< FilterParameters > params = getParameters( data, viewIds, null, presetResponse );

		if ( params == null )
			return false;

		for ( final FilterParameters p : params )
			create( data, viewIds, p.baseLabel, p.newLabel, p.filter, p.copy );

		return true;
	}

	/**
	 * The threshold ramp of a label, a convenience entry: the range dialog for that label (the label is given, the dialog
	 * says which threshold it was detected with) with the response field prefilled with thresholds relative to its detection
	 * threshold t (rampEntries: t x rampFactor^k for k = 1 .. rampSteps; t is the lower response bound of a filtered label
	 * or the "t=" of the parameter string of a detection, thresholdOf); if t is unknown the field is prefilled as last time
	 * and the dialog and a log line say so
	 */
	public static boolean filterRamp( final SpimData2 data, final Collection< ? extends ViewId > viewCollection, final String label )
	{
		final ArrayList< ViewId > viewIds = new ArrayList<>( viewCollection );
		SpimData2.filterMissingViews( data, viewIds );

		final double t = thresholdOf( data, viewIds, label );
		final String preset, message;

		if ( Double.isNaN( t ) )
		{
			preset = null;
			message = "Threshold ramp of the interest points '" + label + "' (no detection threshold found in its parameters, type the thresholds yourself)";
			IOFunctions.println( "No detection threshold found in the parameters of '" + label + "', type the thresholds of the ramp yourself." );
		}
		else
		{
			preset = rampEntries( t, rampFactor, rampSteps, rampMinEnd );
			message = "Threshold ramp of the interest points '" + label + "' (detected with threshold " + InterestPointFilter.plain( t ) + "), one label per entry";
			IOFunctions.println( "Threshold ramp of '" + label + "' (t = " + InterestPointFilter.plain( t ) + ", x " + rampFactor + " per step): " + preset );
		}

		final List< FilterParameters > params = getParameters( data, viewIds, label, preset, message );

		if ( params == null )
			return false;

		defaultBaseLabel = label;

		for ( final FilterParameters p : params )
			create( data, viewIds, p.baseLabel, p.newLabel, p.filter, p.copy );

		return true;
	}

	/**
	 * Creates the filtered label (or the plain copy) for the views and records it in the action history; nothing is saved
	 *
	 * @throws IllegalArgumentException if a view lacks the base label, or if its points do not store an attribute the filter needs (checkAttributes)
	 */
	public static boolean create( final SpimData2 data, final Collection< ? extends ViewId > viewIds, final String baseLabel, final String newLabel, final InterestPointFilter filter, final boolean copy )
	{
		final EnumSet< Axis > missing = missingAttributes( data, viewIds, baseLabel, Collections.singletonList( filter ) );

		if ( !missing.isEmpty() )
			throw new IllegalArgumentException( missingAttributesMessage( baseLabel, missing ) );

		// an existing label is overwritten (its references removed first); only a filtered label that would filter itself cannot be
		if ( !copy && !InterestPointTools.cyclingViews( data, viewIds, newLabel, baseLabel ).isEmpty() )
			throw new IllegalArgumentException( "A filtered label '" + newLabel + "' of '" + baseLabel + "' would filter itself (it is the base or one of the labels the base is filtered from), store a plain copy of the points that pass instead." );

		final List< ViewId > existing = InterestPointTools.viewsWithLabel( data, newLabel, viewIds );

		if ( !existing.isEmpty() )
			InterestPointTools.removeReferences( data, newLabel, existing );

		if ( copy )
			InterestPointTools.addFilteredInterestPointsCopy( data, newLabel, baseLabel, filter, viewIds );
		else
			InterestPointTools.addFilteredInterestPoints( data, newLabel, baseLabel, filter, viewIds );

		recordFilterAction( data, newLabel, baseLabel, filter, copy, viewIds );

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": " + ( existing.isEmpty() ? "Created " : "Overwrote " ) + ( copy ? "label" : "filtered label" ) + " '" + newLabel + "' (" + filter.describe( baseLabel ) + ") for " + viewIds.size() + " views." );

		return true;
	}

	/**
	 * Whether a filtered label (or a plain copy) may be created under the name for the views: the name must be set, and a
	 * filtered label must not filter itself (InterestPointTools.wouldCycle: the name is the base label or one of the labels
	 * the base is filtered from), which only a plain copy of the points that pass can do. Anything else is overwritten
	 * (create removes the references to the old label first), like a re-detection overwrites its label.
	 *
	 * @return true if it may, otherwise false with a log line saying why
	 */
	public static boolean checkNewLabel( final SpimData2 data, final Collection< ? extends ViewId > viewIds, final String baseLabel, final String newLabel, final boolean copy )
	{
		if ( newLabel == null || newLabel.trim().isEmpty() )
		{
			IOFunctions.println( "The new label must be set." );
			return false;
		}

		if ( !copy && !InterestPointTools.cyclingViews( data, viewIds, newLabel, baseLabel ).isEmpty() )
		{
			IOFunctions.println( "A filtered label '" + newLabel + "' of '" + baseLabel + "' would filter itself (it is '" + baseLabel + "'" + ( newLabel.equals( baseLabel ) ? "" : " or one of the labels '" + baseLabel + "' is filtered from" ) + "). To replace it with the points that pass, tick 'Store_as_plain_label'." );
			return false;
		}

		final List< ViewId > existing = InterestPointTools.viewsWithLabel( data, newLabel, viewIds );

		if ( !existing.isEmpty() )
			IOFunctions.println( "Label '" + newLabel + "' exists in " + existing.size() + " of the views and will be overwritten" + ( copy ? " with the points that pass" : " by the filtered label" ) + ( newLabel.equals( baseLabel ) ? " (its own filtered points)" : "" ) + "." );

		return true;
	}

	/**
	 * Whether the points of the base label store the attributes the filters need (response, intensity, sigma): a label
	 * stored before the attributes existed has none, and a filtered label of it would throw whenever its points are read
	 * (Stephan ran the ramp on such a label on 2026-10-21 and the explorer threw at every repaint), so nothing is created.
	 * A base label that no view has yet (a detection about to run) passes, its points will store everything.
	 *
	 * @return true if they do, otherwise false with a log line saying what is missing
	 */
	public static boolean checkAttributes( final SpimData2 data, final Collection< ? extends ViewId > viewIds, final String baseLabel, final Collection< InterestPointFilter > filters )
	{
		final EnumSet< Axis > missing = missingAttributes( data, viewIds, baseLabel, filters );

		if ( missing.isEmpty() )
			return true;

		IOFunctions.println( missingAttributesMessage( baseLabel, missing ) );
		return false;
	}

	/**
	 * @return the attributes any of the filters needs that the points of the base label lack in any of the views that have it (InterestPointTools.missingAttributes)
	 */
	protected static EnumSet< Axis > missingAttributes( final SpimData2 data, final Collection< ? extends ViewId > viewIds, final String baseLabel, final Collection< InterestPointFilter > filters )
	{
		final EnumSet< Axis > axes = EnumSet.noneOf( Axis.class );

		for ( final InterestPointFilter filter : filters )
			axes.addAll( filter.axes() );

		return InterestPointTools.missingAttributes( data, InterestPointTools.viewsWithLabel( data, baseLabel, viewIds ), baseLabel, axes );
	}

	protected static String missingAttributesMessage( final String baseLabel, final EnumSet< Axis > missing )
	{
		return "The interest points '" + baseLabel + "' store no " + missing.stream().map( a -> a.key ).collect( Collectors.joining( ", " ) ) + " (they were stored before the attributes existed), cannot filter by it; detect them again to store it.";
	}

	/**
	 * Records a "filter-interestpoints" action, one per label so that removing the label removes its record (resultRef "interestpoints:" + label)
	 */
	public static void recordFilterAction( final SpimData2 data, final String label, final String baseLabel, final InterestPointFilter filter, final boolean copy, final Collection< ? extends ViewId > viewIds )
	{
		final LinkedHashMap< String, String > params = ActionHistoryRecorder.params();
		ActionHistoryRecorder.put( params, "label", label );
		ActionHistoryRecorder.put( params, "base", baseLabel );
		ActionHistoryRecorder.merge( params, filter.describeParameters() );

		if ( copy )
			ActionHistoryRecorder.put( params, "copy", true );

		ActionHistoryRecorder.record(
				data,
				"filter-interestpoints",
				Filter_Detections.class.getName(),
				params,
				new ArrayList<>( viewIds ),
				"interestpoints:" + label );
	}

	public static List< FilterParameters > getParameters( final SpimData2 spimData, final List< ViewId > viewIds )
	{
		return getParameters( spimData, viewIds, null, null );
	}

	/**
	 * Asks for the base label (unless it is given), the ranges and the new label; several entries per range field (separated
	 * by ',') give one label per combination of the entries, named base label + suffix
	 *
	 * @param fixedBaseLabel - the base label if it is given (the detection dialogs filter the label they are about to detect, which does not exist yet), null = ask for one of the existing labels of the views
	 * @param presetResponse - what the response field is prefilled with (e.g. a threshold ramp, rampEntries), null = the last entry
	 * @return the labels to create (base label, filter, new label, copy), null if cancelled or invalid
	 */
	public static List< FilterParameters > getParameters( final SpimData2 spimData, final List< ViewId > viewIds, final String fixedBaseLabel, final String presetResponse )
	{
		return getParameters( spimData, viewIds, fixedBaseLabel, presetResponse, fixedBaseLabel == null ? null : "Filters the interest points '" + fixedBaseLabel + "' of this detection once they are detected" );
	}

	/**
	 * @param fixedBaseMessage - what the dialog says instead of the label choice when the base label is given (which label and why)
	 * @return see getParameters( spimData, viewIds, fixedBaseLabel, presetResponse )
	 */
	public static List< FilterParameters > getParameters( final SpimData2 spimData, final List< ViewId > viewIds, final String fixedBaseLabel, final String presetResponse, final String fixedBaseMessage )
	{
		final String[] labels = fixedBaseLabel != null ? new String[ 0 ] : InterestPointTools.getAllInterestPointLabels( spimData, viewIds );

		if ( fixedBaseLabel == null && labels.length == 0 )
		{
			IOFunctions.println( "No interest points for the selected views." );
			return null;
		}

		if ( fixedBaseLabel == null )
			defaultLabel = defaultLabelIndex( labels );

		final GenericDialog gd = new GenericDialog( "Filter interest points" );

		if ( fixedBaseLabel == null )
			gd.addChoice( "Interest_points", labels, labels[ defaultLabel ] );
		else
			gd.addMessage( fixedBaseMessage, GUIHelper.smallStatusFont );

		gd.addMessage( "A range is 'a' (keeps values >= a) or 'a-b' (keeps a <= value <= b), empty = no restriction;\n" +
				"several entries separated by ',' give one label per entry (per combination across the fields), named base label + suffix", GUIHelper.smallStatusFont );
		gd.addStringField( "Response_range (|DoG response|)", presetResponse != null ? presetResponse : defaultResponse, fieldColumns );
		gd.addStringField( "Intensity_range (image units)", defaultIntensity, fieldColumns );
		gd.addStringField( "Sigma_range (px at full resolution)", defaultSigma, fieldColumns );
		gd.addStringField( "New_label (empty = base label + suffix of the filter)", defaultNewLabel, fieldColumns );
		gd.addCheckbox( "Store_as_plain_label (copy the points instead of filtering on the fly; the only way to replace the base label itself)", defaultCopy );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final String baseLabel;

		if ( fixedBaseLabel == null )
			baseLabel = defaultBaseLabel = InterestPointTools.getSelectedLabel( labels, defaultLabel = gd.getNextChoiceIndex() );
		else
			baseLabel = fixedBaseLabel;

		final String response = defaultResponse = gd.getNextString();
		final String intensity = defaultIntensity = gd.getNextString();
		final String sigma = defaultSigma = gd.getNextString();
		final String newLabel = defaultNewLabel = gd.getNextString().trim();
		final boolean copy = defaultCopy = gd.getNextBoolean();

		final List< InterestPointFilter > filters = filtersFor( response, intensity, sigma );

		if ( filters == null )
			return null;

		if ( filters.isEmpty() )
		{
			IOFunctions.println( "No range given, nothing to filter by (" + Axis.RESPONSE.description + ", " + Axis.INTENSITY.description + " or " + Axis.SIGMA.description + ")." );
			return null;
		}

		if ( !checkAttributes( spimData, viewIds, baseLabel, filters ) )
			return null;

		final ArrayList< FilterParameters > params = new ArrayList<>();

		for ( final InterestPointFilter filter : filters )
		{
			final FilterParameters p = new FilterParameters();
			p.baseLabel = baseLabel;
			p.filter = filter;
			p.copy = copy;
			// one label: the typed name as it is; several: the typed name (or the base label) + the suffix of each
			p.newLabel = newLabel.isEmpty() ? baseLabel + filter.suffix() : filters.size() == 1 ? newLabel : newLabel + filter.suffix();

			if ( !checkNewLabel( spimData, viewIds, baseLabel, p.newLabel, copy ) )
				return null;

			params.add( p );
		}

		if ( params.size() > 1 )
			IOFunctions.println( params.size() + " filtered labels of '" + baseLabel + "': " + params.stream().map( p -> p.newLabel ).collect( Collectors.joining( ", " ) ) );

		return params;
	}

	/**
	 * The filters of the three range fields: every combination of their entries (InterestPointFilter.parseEntries; an
	 * empty field = no restriction on that axis), in the order response, intensity, sigma
	 *
	 * @return the filters (empty if all fields are empty), null with a log line if an entry is malformed
	 */
	public static List< InterestPointFilter > filtersFor( final String responseField, final String intensityField, final String sigmaField )
	{
		final List< InterestPointFilter > r = InterestPointFilter.parseEntries( responseField, Axis.RESPONSE );
		final List< InterestPointFilter > i = InterestPointFilter.parseEntries( intensityField, Axis.INTENSITY );
		final List< InterestPointFilter > s = InterestPointFilter.parseEntries( sigmaField, Axis.SIGMA );

		if ( r == null || i == null || s == null )
			return null;

		final ArrayList< InterestPointFilter > filters = new ArrayList<>();

		if ( r.isEmpty() && i.isEmpty() && s.isEmpty() )
			return filters;

		for ( final InterestPointFilter fr : orNone( r ) )
			for ( final InterestPointFilter fi : orNone( i ) )
				for ( final InterestPointFilter fs : orNone( s ) )
					filters.add( new InterestPointFilter( fr.responseMin, fr.responseMax, fi.intensityMin, fi.intensityMax, fs.sigmaMin, fs.sigmaMax ) );

		return filters;
	}

	/**
	 * @return the entries of an axis, or one unrestricted entry for an empty field
	 */
	protected static List< InterestPointFilter > orNone( final List< InterestPointFilter > entries )
	{
		return entries.isEmpty() ? Collections.singletonList( InterestPointFilter.fromString( "" ) ) : entries;
	}

	/**
	 * @return the index of the label to preselect: the one chosen last by name (defaultBaseLabel) if it is there, else the index chosen last if it still exists, else the first complete label
	 */
	protected static int defaultLabelIndex( final String[] labels )
	{
		final int byName = defaultLabelIndex( labels, defaultBaseLabel );

		if ( byName >= 0 )
			return byName;

		if ( defaultLabel >= 0 && defaultLabel < labels.length )
			return defaultLabel;

		for ( int i = 0; i < labels.length; ++i )
			if ( !labels[ i ].contains( InterestPointTools.warningLabel ) )
				return i;

		return 0;
	}

	/**
	 * @return the entries of a threshold ramp for the response field: t x factor^k for k = 1 .. steps (nothing below the
	 * detection threshold t was detected, so the ramp only goes up), rounded to three significant digits, a step that rounds
	 * onto the previous one dropped; e.g. "0.008, 0.016, 0.032, 0.064" for t = 0.004, factor 2, four steps
	 */
	public static String rampEntries( final double t, final double factor, final int steps )
	{
		return rampEntries( t, factor, steps, 0 );
	}

	/**
	 * @param minEnd - the ramp goes on beyond the steps until its last threshold is &gt;= minEnd (Stephan, 2026-10-20: at least
	 * up to 0.1, which a low detection threshold would not reach in six steps), at most rampMaxSteps steps in all
	 * @return the entries, see rampEntries( t, factor, steps ); e.g. "0.001, 0.002, 0.004, 0.008, 0.016" for t = 0.0005, factor 2, four steps, minEnd 0.01
	 */
	public static String rampEntries( final double t, final double factor, final int steps, final double minEnd )
	{
		final ArrayList< String > entries = new ArrayList<>();
		double last = 0;

		for ( int k = 1; ( k <= steps || last < minEnd ) && k <= rampMaxSteps; ++k )
		{
			last = round3( t * Math.pow( factor, k ) );
			final String entry = InterestPointFilter.plain( last );

			if ( !entries.contains( entry ) )
				entries.add( entry );
		}

		return String.join( ", ", entries );
	}

	/**
	 * @return the detection threshold of a label in the first of the views that has it: the lower response bound of a
	 * filtered label, otherwise the "t=" of its parameter string (DoG and scale space); NaN if unknown
	 */
	public static double thresholdOf( final SpimData2 data, final Collection< ? extends ViewId > viewIds, final String label )
	{
		for ( final ViewId viewId : viewIds )
		{
			final InterestPoints ips = data.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( label );

			if ( ips != null )
				return thresholdOf( ips );
		}

		return Double.NaN;
	}

	/**
	 * @return the lower response bound of a filtered label if it has one, otherwise the "t=" of its parameter string (parseThreshold); NaN if neither
	 */
	public static double thresholdOf( final InterestPoints ips )
	{
		if ( ips instanceof VirtualFilteredInterestPointsN5 )
		{
			final double min = ( (VirtualFilteredInterestPointsN5)ips ).getFilter().min( Axis.RESPONSE );

			if ( !Double.isNaN( min ) )
				return min;
		}

		return parseThreshold( ips.getParameters() );
	}

	/**
	 * @return the value of "t=" in the parameter string of a detection (e.g. "DOG s=1.8 t=0.004 min=false ..." or "DOG-SS ... t=0.008 ..."), NaN if absent or not a number
	 */
	public static double parseThreshold( final String parameters )
	{
		if ( parameters == null )
			return Double.NaN;

		final Matcher m = thresholdPattern.matcher( parameters );

		if ( !m.find() )
			return Double.NaN;

		try
		{
			return Double.parseDouble( m.group( 1 ) );
		}
		catch ( final NumberFormatException e )
		{
			return Double.NaN;
		}
	}

	/**
	 * @return the value rounded to three significant digits
	 */
	public static double round3( final double value )
	{
		if ( value == 0 || Double.isNaN( value ) || Double.isInfinite( value ) )
			return value;

		return new BigDecimal( value ).round( new MathContext( 3 ) ).doubleValue();
	}

	/**
	 * @return the index of the label (ignoring the warning suffix of incomplete labels) in the choices, -1 if null or absent
	 */
	public static int defaultLabelIndex( final String[] labels, final String label )
	{
		if ( label == null )
			return -1;

		for ( int i = 0; i < labels.length; ++i )
			if ( InterestPointTools.getSelectedLabel( labels, i ).equals( label ) )
				return i;

		return -1;
	}

	/**
	 * Remembers the base label and the ranges of a filter as the defaults of the dialog (the interactive filter of the
	 * explorer calls it on every change, so that the ranges found there can be applied to any views)
	 */
	public static void remember( final String baseLabel, final InterestPointFilter filter )
	{
		defaultBaseLabel = baseLabel;
		defaultResponse = rangeString( filter, Axis.RESPONSE );
		defaultIntensity = rangeString( filter, Axis.INTENSITY );
		defaultSigma = rangeString( filter, Axis.SIGMA );
	}

	/**
	 * @return the range of the axis in the grammar of the dialog fields: "" (unbounded), "a" (&gt;= a) or "a-b"; a missing lower bound is written as 0
	 */
	public static String rangeString( final InterestPointFilter filter, final Axis axis )
	{
		if ( !filter.filters( axis ) )
			return "";

		final double min = filter.min( axis ), max = filter.max( axis );
		final String lower = InterestPointFilter.plain( Double.isNaN( min ) ? 0 : min );

		return Double.isNaN( max ) ? lower : lower + "-" + InterestPointFilter.plain( max );
	}

	/**
	 * @return { min, max } of a dialog range field, { NaN, NaN } if empty
	 */
	protected static double[] range( final String field )
	{
		return field == null || field.trim().isEmpty() ? new double[] { Double.NaN, Double.NaN } : InterestPointFilter.parseRange( field );
	}

	public static void main( final String[] args )
	{
		new Filter_Detections().run( null );
	}
}
