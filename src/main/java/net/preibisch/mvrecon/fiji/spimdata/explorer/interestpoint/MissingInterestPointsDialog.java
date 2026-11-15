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
package net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import ij.Macro;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.StoredDetectionParameters;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.DetectInterestPointsPopup;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.MissingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * What the explorers do about interest points the dataset lists without data ({@link MissingInterestPoints}): instead of
 * failing on the first read, the operation stops ({@link #check}) and, per label, the user is offered ({@link #offer}) to
 * remove the label of these views from the dataset, or to detect it again: "Detect Interest Points" opens for the selected
 * views with the label and the parameters the XML recorded for it preset ({@link StoredDetectionParameters}).
 * Without an explorer, without a display or from a macro there is no question, only the log.
 *
 * @author Stephan Preibisch
 */
public class MissingInterestPointsDialog
{
	public enum Choice
	{
		/** "Detect Interest Points" was opened with the label and its recorded parameters preset; the detection replaces the label */
		REDETECT,
		/** the entries were removed from the dataset (in memory, the next save writes the XML without them) */
		REMOVE,
		/** nothing was changed */
		NOTHING
	}

	public static final String REDETECT_OPTION = "Re-detect ...", REMOVE_OPTION = "Remove from XML", NOTHING_OPTION = "Not now";

	private MissingInterestPointsDialog() {}

	/**
	 * Before an operation reads labels: looks for entries without data among them. If there are none the operation goes
	 * on. Otherwise the log says what is missing and the operation has to stop; with an explorer the user is asked per
	 * label what to do about it ({@link #offer}).
	 *
	 * @param data the dataset
	 * @param views the views the operation works on
	 * @param labels the labels it reads, null = all labels of the views
	 * @param panel the explorer the operation was started from, null if none (a plugin, a macro): then nothing is asked
	 * @param operation what cannot run, for the log, e.g. "the registration"
	 * @return true if nothing is missing, false if the operation must not run
	 */
	public static boolean check( final SpimData2 data, final Collection< ? extends ViewId > views, final Collection< String > labels, final ExplorerWindow< ? > panel, final String operation )
	{
		final SortedMap< String, List< ViewId > > missing = MissingInterestPoints.find( data.getViewInterestPoints(), views, labels );

		if ( missing.isEmpty() )
			return true;

		for ( final String line : MissingInterestPoints.describe( data.getViewInterestPoints(), missing ) )
			IOFunctions.println( line );

		IOFunctions.println( "Stopping " + operation + ": these interest points cannot be read." );

		if ( panel == null || !canAsk() )
		{
			IOFunctions.println( "To repair the dataset, open it in the explorer and open the Interest Point Explorer: it offers to remove such a label from the XML or to detect it again." );
			return false;
		}

		for ( final Map.Entry< String, List< ViewId > > e : missing.entrySet() )
			offer( data, e.getKey(), e.getValue(), views, panel );

		return false;
	}

	/** @return whether a question can be put to a user: a display, and no macro running in this thread */
	public static boolean canAsk()
	{
		return !GraphicsEnvironment.isHeadless() && Macro.getOptions() == null;
	}

	/**
	 * @return the detection parameters the dataset recorded for the label in these views (those of the first view; the
	 *         log says so if the views differ), null if what it recorded is no detection (a filtered label, points made
	 *         from correspondences, a string of another tool): such a label cannot be detected again
	 */
	public static String detectionParameters( final SpimData2 data, final String label, final List< ViewId > views )
	{
		String parameters = null;

		for ( final ViewId v : views )
		{
			final ViewInterestPointLists vipl = data.getViewInterestPoints().getViewInterestPointLists( v );
			final InterestPoints list = vipl == null ? null : vipl.getInterestPointList( label );

			if ( list == null )
				continue;

			if ( list instanceof VirtualFilteredInterestPoints )
				return null;

			if ( parameters == null )
			{
				parameters = String.valueOf( list.getParameters() );
			}
			else if ( !parameters.equals( String.valueOf( list.getParameters() ) ) )
			{
				IOFunctions.println( "The recorded parameters of '" + label + "' differ between the views, using the ones of the first: " + parameters );
				break;
			}
		}

		return parameters != null && StoredDetectionParameters.parse( parameters ) != null ? parameters : null;
	}

	/**
	 * @return the text of the question
	 * @param numViews the number of views the operation or explorer looks at
	 * @param parameters the recorded detection parameters ({@link #detectionParameters}), null if the label cannot be detected again
	 */
	public static String message( final SpimData2 data, final String label, final List< ViewId > missing, final int numViews, final String parameters )
	{
		final StringBuilder text = new StringBuilder();

		text.append( "The dataset (XML) lists the interest points '" ).append( label ).append( "' for " );
		text.append( missing.size() == numViews ? "all " + numViews : missing.size() + " of the " + numViews ).append( " selected view(s),\n" );
		text.append( "but nothing is stored for them " ).append( wrap( MissingInterestPoints.where( data.getViewInterestPoints(), label, missing ), 110 ) ).append( ".\n" );
		text.append( "They cannot be shown, embedded or used for a registration.\n\n" );

		if ( parameters != null )
		{
			text.append( REDETECT_OPTION ).append( "  opens 'Detect Interest Points' for the selected views, with the label and the parameters\n" );
			text.append( "the XML recorded for it preset; the detection replaces the label:\n" );
			text.append( "      " ).append( wrap( parameters, 110 ).replace( "\n", "\n      " ) ).append( "\n\n" );
		}
		else
		{
			text.append( "It cannot be detected again from what the XML recorded for it (no detection parameters).\n\n" );
		}

		text.append( REMOVE_OPTION ).append( "  takes the label off " ).append( missing.size() == 1 ? "this view" : "these " + missing.size() + " views" );
		text.append( ", together with filtered labels of it and correspondences\n" );
		text.append( "that point to it. 'Save' then writes the XML without it." );

		return text.toString();
	}

	/** breaks a long line at spaces */
	static String wrap( final String line, final int width )
	{
		final StringBuilder wrapped = new StringBuilder();
		int length = 0;

		for ( final String word : line.split( " " ) )
		{
			if ( length > 0 && length + word.length() + 1 > width )
			{
				wrapped.append( '\n' );
				length = 0;
			}
			else if ( length > 0 )
			{
				wrapped.append( ' ' );
				++length;
			}

			wrapped.append( word );
			length += word.length();
		}

		return wrapped.toString();
	}

	/**
	 * Asks what to do about a label without data and does it.
	 *
	 * @param data the dataset
	 * @param label the label
	 * @param missing the views that list it without data
	 * @param views the views the operation or explorer looks at (the explorer's selection)
	 * @param panel the explorer: its selected views are detected again, its windows are refreshed
	 * @return what was done
	 */
	public static Choice offer( final SpimData2 data, final String label, final List< ViewId > missing, final Collection< ? extends ViewId > views, final ExplorerWindow< ? > panel )
	{
		final String parameters = detectionParameters( data, label, missing );
		final String[] options = parameters != null ? new String[] { REDETECT_OPTION, REMOVE_OPTION, NOTHING_OPTION } : new String[] { REMOVE_OPTION, NOTHING_OPTION };
		final String message = message( data, label, missing, views.size(), parameters );
		final int[] answer = new int[] { JOptionPane.CLOSED_OPTION };

		final Runnable ask = () -> answer[ 0 ] = JOptionPane.showOptionDialog(
				null, message, "Interest points '" + label + "' are missing", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[ options.length - 1 ] );

		try
		{
			if ( SwingUtilities.isEventDispatchThread() )
				ask.run();
			else
				SwingUtilities.invokeAndWait( ask );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Could not ask what to do about the missing interest points '" + label + "': " + e );
			return Choice.NOTHING;
		}

		final String chosen = answer[ 0 ] >= 0 && answer[ 0 ] < options.length ? options[ answer[ 0 ] ] : NOTHING_OPTION;

		if ( chosen.equals( REMOVE_OPTION ) )
		{
			remove( data, label, missing, panel );
			return Choice.REMOVE;
		}
		else if ( chosen.equals( REDETECT_OPTION ) )
		{
			redetect( data, label, parameters, views, panel );
			return Choice.REDETECT;
		}

		return Choice.NOTHING;
	}

	/** Removes the label of the views from the dataset ({@link MissingInterestPoints#remove}) and refreshes the explorer's windows; the XML is not saved */
	public static void remove( final SpimData2 data, final String label, final List< ViewId > missing, final ExplorerWindow< ? > panel )
	{
		MissingInterestPoints.remove( data, Collections.singletonMap( label, missing ) );
		IOFunctions.println( "The dataset on disk is unchanged until it is saved ('Save' in the explorer writes the XML without the label)." );

		if ( panel != null )
			panel.updateContent();
	}

	/**
	 * Opens "Detect Interest Points" for the selected views of the explorer with the label and its recorded parameters
	 * preset ({@link StoredDetectionParameters#preset}), as "Re-detect interest points with attributes" does; the dialog
	 * runs in its own thread, a detection replaces the label
	 */
	public static void redetect( final SpimData2 data, final String label, final String parameters, final Collection< ? extends ViewId > views, final ExplorerWindow< ? > panel )
	{
		final StoredDetectionParameters stored = StoredDetectionParameters.parse( parameters );

		if ( stored == null || panel == null )
		{
			IOFunctions.println( "'" + label + "' cannot be detected again: " + ( stored == null ? "no detection parameters recorded (" + parameters + ")." : "no explorer." ) );
			return;
		}

		final ArrayList< ViewId > sorted = new ArrayList<>();
		for ( final ViewId v : views )
			sorted.add( new ViewId( v.getTimePointId(), v.getViewSetupId() ) );
		Collections.sort( sorted );

		IOFunctions.println( "Detecting '" + label + "' again (" + sorted.size() + " selected view(s), first " + Group.pvid( sorted.get( 0 ) ) + ") with its recorded parameters: " + parameters );
		stored.preset( data, sorted, label );

		// a recorded grouping wins over the explorer's; a string without it (older labels) gets the explorer's as always
		DetectInterestPointsPopup.detect( panel, !stored.hasGrouping() );
	}
}
