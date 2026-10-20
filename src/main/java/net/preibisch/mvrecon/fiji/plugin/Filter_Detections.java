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
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

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
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * Creates a filtered label from an existing label: a label without points of its own that filters the points of the
 * base label by |response|, intensity and/or sigma on the fly and has its own correspondences (VirtualFilteredInterestPointsN5),
 * or, on request, a plain copy of the points that pass the filter (for readers that do not know filtered labels).
 * Reachable from "Manage Interest Points" of the explorer and as a plugin; the detection dialogs offer the same
 * filters right after a detection (Interest_Point_Detection, DifferenceOfGUI.queryFilters).
 *
 * @author Stephan Preibisch
 */
public class Filter_Detections implements PlugIn
{
	public static int defaultLabel = -1;
	public static String defaultResponse = "";
	public static String defaultIntensity = "";
	public static String defaultSigma = "";
	public static String defaultNewLabel = "";
	public static boolean defaultCopy = false;

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

	/**
	 * Asks for the base label, the filter and the new label and creates the filtered label for the views
	 */
	public static boolean filter( final SpimData2 data, final Collection< ? extends ViewId > viewCollection )
	{
		// filter not present ViewIds
		final ArrayList< ViewId > viewIds = new ArrayList<>();
		viewIds.addAll( viewCollection );

		final List< ViewId > removed  = SpimData2.filterMissingViews( data, viewIds );
		if ( removed.size() > 0 ) IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		final FilterParameters params = getParameters( data, viewIds );

		if ( params == null )
			return false;

		return create( data, viewIds, params.baseLabel, params.newLabel, params.filter, params.copy );
	}

	/**
	 * Creates the filtered label (or the plain copy) for the views and records it in the action history; nothing is saved
	 *
	 * @throws IllegalArgumentException if a view lacks the base label
	 */
	public static boolean create( final SpimData2 data, final Collection< ? extends ViewId > viewIds, final String baseLabel, final String newLabel, final InterestPointFilter filter, final boolean copy )
	{
		if ( copy )
			InterestPointTools.addFilteredInterestPointsCopy( data, newLabel, baseLabel, filter, viewIds );
		else
			InterestPointTools.addFilteredInterestPoints( data, newLabel, baseLabel, filter, viewIds );

		recordFilterAction( data, newLabel, baseLabel, filter, copy, viewIds );

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Created " + ( copy ? "label" : "filtered label" ) + " '" + newLabel + "' (" + filter.describe( baseLabel ) + ") for " + viewIds.size() + " views." );

		return true;
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

	public static FilterParameters getParameters( final SpimData2 spimData, final List< ViewId > viewIds )
	{
		final String[] labels = InterestPointTools.getAllInterestPointLabels( spimData, viewIds );

		if ( labels.length == 0 )
		{
			IOFunctions.println( "No interest points for the selected views." );
			return null;
		}

		// choose the first label that is complete if possible
		if ( defaultLabel < 0 || defaultLabel >= labels.length )
		{
			defaultLabel = -1;

			for ( int i = 0; i < labels.length; ++i )
				if ( !labels[ i ].contains( InterestPointTools.warningLabel ) )
				{
					defaultLabel = i;
					break;
				}

			if ( defaultLabel == -1 )
				defaultLabel = 0;
		}

		final GenericDialog gd = new GenericDialog( "Filter interest points" );

		gd.addChoice( "Interest_points", labels, labels[ defaultLabel ] );
		gd.addMessage( "A range is 'a' (keeps values >= a) or 'a-b' (keeps a <= value <= b), empty = no restriction", GUIHelper.smallStatusFont );
		gd.addStringField( "Response_range (|DoG response|)", defaultResponse, 20 );
		gd.addStringField( "Intensity_range (image units)", defaultIntensity, 20 );
		gd.addStringField( "Sigma_range (px at full resolution)", defaultSigma, 20 );
		gd.addStringField( "New_label (empty = base label + suffix of the filter)", defaultNewLabel, 30 );
		gd.addCheckbox( "Store_as_plain_label (copy the points instead of filtering on the fly)", defaultCopy );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final FilterParameters params = new FilterParameters();

		params.baseLabel = InterestPointTools.getSelectedLabel( labels, defaultLabel = gd.getNextChoiceIndex() );

		final String response = defaultResponse = gd.getNextString();
		final String intensity = defaultIntensity = gd.getNextString();
		final String sigma = defaultSigma = gd.getNextString();
		final String newLabel = defaultNewLabel = gd.getNextString();
		params.copy = defaultCopy = gd.getNextBoolean();

		try
		{
			final double[] r = range( response ), i = range( intensity ), s = range( sigma );
			params.filter = new InterestPointFilter( r[ 0 ], r[ 1 ], i[ 0 ], i[ 1 ], s[ 0 ], s[ 1 ] );
		}
		catch ( final IllegalArgumentException e )
		{
			IOFunctions.println( "Cannot parse the ranges: " + e.getMessage() );
			return null;
		}

		if ( params.filter.isEmpty() )
		{
			IOFunctions.println( "No range given, nothing to filter by (" + Axis.RESPONSE.description + ", " + Axis.INTENSITY.description + " or " + Axis.SIGMA.description + ")." );
			return null;
		}

		params.newLabel = newLabel.trim().isEmpty() ? params.baseLabel + params.filter.suffix() : newLabel.trim();

		if ( params.newLabel.equals( params.baseLabel ) )
		{
			IOFunctions.println( "The new label must differ from the base label '" + params.baseLabel + "'." );
			return null;
		}

		return params;
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
