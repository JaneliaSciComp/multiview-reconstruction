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
import java.util.List;

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections.FilterParameters;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Limit;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * Keeps a number of points per view of an existing label: a filtered label (VirtualFilteredInterestPointsN5, or a plain
 * copy on request) that keeps the n highest, the n around the median or the n lowest points by intensity, |response| or
 * sigma of every view. This is the former "Limit amount of detections" of the detection dialog as a filter: no
 * re-detection, the base label stays, and the limit can be stacked on a range label (a filter of a filter). Reachable
 * from "Manage Interest Points" of the explorer ("Keep n points per view ...") and as a plugin, and "Filter_interest_points
 * (fixed number)" of the detection dialogs opens it for the label being detected (DifferenceOfGUI.queryFilter); the ranges
 * have their own dialog (Filter_Detections, "New filtered interest points ...").
 *
 * @author Stephan Preibisch
 */
public class Limit_Detections implements PlugIn
{
	/** the entries of "Filter_by" and the attributes they stand for */
	public static final String[] axisChoice = new String[] { "intensity", "response", "sigma" };
	public static final Axis[] axes = new Axis[] { Axis.INTENSITY, Axis.RESPONSE, Axis.SIGMA };

	/** the entries of "Keep", in the order of InterestPointFilter.Limit */
	public static final String[] modeChoice = new String[] { "highest", "around median", "lowest" };

	public static int defaultCount = 3000;
	public static int defaultAxis = 1;
	public static int defaultMode = 0;
	public static String defaultNewLabel = "";

	@Override
	public void run( final String arg )
	{
		final LoadParseQueryXML xml = new LoadParseQueryXML();

		if ( !xml.queryXML( "", true, true, true, true, true ) )
			return;

		final SpimData2 data = xml.getData();

		if ( !limit( data, SpimData2.getAllViewIdsSorted( data, xml.getViewSetupsToProcess(), xml.getTimePointsToProcess() ) ) )
			return;

		// write new xml
		new XmlIoSpimData2().saveWithFilename( data, xml.getXMLFileName() );
	}

	/**
	 * Asks for the base label, the number of points, the attribute, the mode and the new label and creates the filtered label for the views
	 */
	public static boolean limit( final SpimData2 data, final Collection< ? extends ViewId > viewCollection )
	{
		// filter not present ViewIds
		final ArrayList< ViewId > viewIds = new ArrayList<>();
		viewIds.addAll( viewCollection );

		final List< ViewId > removed  = SpimData2.filterMissingViews( data, viewIds );
		if ( removed.size() > 0 ) IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		final FilterParameters params = getParameters( data, viewIds );

		if ( params == null )
			return false;

		return Filter_Detections.create( data, viewIds, params.baseLabel, params.newLabel, params.filter, params.copy );
	}

	public static FilterParameters getParameters( final SpimData2 spimData, final List< ViewId > viewIds )
	{
		return getParameters( spimData, viewIds, null );
	}

	/**
	 * @param fixedBaseLabel - the base label if it is given (the detection dialogs limit the label they are about to detect,
	 * which does not exist yet), null = ask for one of the existing labels of the views
	 */
	public static FilterParameters getParameters( final SpimData2 spimData, final List< ViewId > viewIds, final String fixedBaseLabel )
	{
		final String[] labels = fixedBaseLabel != null ? new String[ 0 ] : InterestPointTools.getAllInterestPointLabels( spimData, viewIds );

		if ( fixedBaseLabel == null && labels.length == 0 )
		{
			IOFunctions.println( "No interest points for the selected views." );
			return null;
		}

		// the base label chosen last in this or the range dialog, otherwise the first complete one
		int defaultLabel = fixedBaseLabel != null ? -1 : Filter_Detections.defaultLabelIndex( labels, Filter_Detections.defaultBaseLabel );

		if ( fixedBaseLabel == null && defaultLabel < 0 )
		{
			defaultLabel = 0;

			for ( int i = 0; i < labels.length; ++i )
				if ( !labels[ i ].contains( InterestPointTools.warningLabel ) )
				{
					defaultLabel = i;
					break;
				}
		}

		final GenericDialog gd = new GenericDialog( "Keep n points per view" );

		if ( fixedBaseLabel == null )
			gd.addChoice( "Interest_points", labels, labels[ defaultLabel ] );
		else
			gd.addMessage( "Keeps n of the interest points '" + fixedBaseLabel + "' of this detection per view once they are detected", GUIHelper.smallStatusFont );
		gd.addNumericField( "Number_of_points to keep per view", defaultCount, 0 );
		gd.addChoice( "Filter_by", axisChoice, axisChoice[ defaultAxis ] );
		gd.addChoice( "Keep", modeChoice, modeChoice[ defaultMode ] );
		gd.addMessage( "The points are ranked per view by the attribute (|response| for the response), a point without it does not pass.", GUIHelper.smallStatusFont );
		gd.addStringField( "New_label (empty = base label + suffix, e.g. _rh3000)", defaultNewLabel, 30 );
		gd.addCheckbox( "Store_as_plain_label (copy the points instead of filtering on the fly; needed to overwrite an existing label)", Filter_Detections.defaultCopy );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final FilterParameters params = new FilterParameters();

		if ( fixedBaseLabel == null )
			params.baseLabel = Filter_Detections.defaultBaseLabel = InterestPointTools.getSelectedLabel( labels, gd.getNextChoiceIndex() );
		else
			params.baseLabel = fixedBaseLabel;

		final int count = (int)Math.round( gd.getNextNumber() );
		final int axis = defaultAxis = gd.getNextChoiceIndex();
		final int mode = defaultMode = gd.getNextChoiceIndex();
		final String newLabel = defaultNewLabel = gd.getNextString();
		params.copy = Filter_Detections.defaultCopy = gd.getNextBoolean();

		if ( count < 1 )
		{
			IOFunctions.println( "The number of points to keep must be >= 1, but is " + count + "." );
			return null;
		}

		defaultCount = count;
		params.filter = filterFor( count, axis, mode );
		params.newLabel = newLabel.trim().isEmpty() ? params.baseLabel + params.filter.suffix() : newLabel.trim();

		if ( !Filter_Detections.checkNewLabel( spimData, viewIds, params.baseLabel, params.newLabel, params.copy ) )
			return null;

		return params;
	}

	/**
	 * @return the filter of the dialog's choices: keep count points per view, the highest / around the median / lowest (modeIndex into modeChoice) by the attribute (axisIndex into axisChoice)
	 */
	public static InterestPointFilter filterFor( final int count, final int axisIndex, final int modeIndex )
	{
		return InterestPointFilter.of( axes[ axisIndex ], Limit.values()[ modeIndex ], count );
	}

	public static void main( final String[] args )
	{
		new Limit_Detections().run( null );
	}
}
