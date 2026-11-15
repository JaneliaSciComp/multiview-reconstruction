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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * Interest points the dataset lists without data: the XML names a label for a view, but nothing is stored for it, neither
 * in {@code interestpoints.zarr} nor in {@code interestpoints.n5} nor as text files ({@link InterestPointsZarr#withoutData}).
 * It happens when an XML outlives its interest point containers (deleted, not copied along), or the entries of some views.
 * Every read of such a label throws, so what works on a label asks here first ({@link #find}) and stops with a message
 * ({@link #describe}) instead of a stack trace; the explorers offer to remove the entries ({@link #remove}) or to detect
 * the label again (MissingInterestPointsDialog).
 *
 * @author Stephan Preibisch
 */
public class MissingInterestPoints
{
	private MissingInterestPoints() {}

	/**
	 * @param vip the lists of the dataset
	 * @param views the views to look at (those without interest points are skipped)
	 * @param labels the labels to look at, null = every label of the views; the labels a filtered label filters (its base
	 *        labels) are looked at with it, since it has no points without them
	 * @return the entries without data: per label, in the order of the label drop-downs, the views (sorted) that list it
	 *         although nothing is stored for it; empty if there are none
	 */
	public static SortedMap< String, List< ViewId > > find( final ViewInterestPoints vip, final Collection< ? extends ViewId > views, final Collection< String > labels )
	{
		final LinkedHashMap< InterestPoints, Pair< String, ViewId > > lists = new LinkedHashMap<>();

		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists vipl = vip.getViewInterestPointLists( viewId );

			if ( vipl == null )
				continue;

			for ( final String selected : labels == null ? new ArrayList<>( vipl.getHashMap().keySet() ) : labels )
			{
				// the label and, for a filtered label, the chain of labels it filters
				final HashSet< String > chain = new HashSet<>();
				String label = selected;

				while ( label != null && chain.add( label ) )
				{
					final InterestPoints list = vipl.getInterestPointList( label );

					if ( list == null )
						break;

					lists.put( list, new ValuePair<>( label, new ViewId( viewId.getTimePointId(), viewId.getViewSetupId() ) ) );
					label = list instanceof VirtualFilteredInterestPoints ? ( (VirtualFilteredInterestPoints) list ).getBaseLabel() : null;
				}
			}
		}

		final TreeMap< String, List< ViewId > > missing = new TreeMap<>( InterestPointTools.naturalOrder );

		for ( final InterestPointsZarr list : InterestPointsZarr.withoutData( lists.keySet() ) )
			missing.computeIfAbsent( lists.get( list ).getA(), l -> new ArrayList<>() ).add( lists.get( list ).getB() );

		missing.values().forEach( Collections::sort );

		return missing;
	}

	/**
	 * @return what is missing, for the log: one line per label, e.g. "No interest points are stored for 'beads' in 7
	 *         view(s) (neither in interestpoints.zarr nor in interestpoints.n5 of 'file:/data/') although the dataset lists them."
	 */
	public static List< String > describe( final ViewInterestPoints vip, final Map< String, ? extends Collection< ? extends ViewId > > missing )
	{
		final List< String > lines = new ArrayList<>();

		for ( final Map.Entry< String, ? extends Collection< ? extends ViewId > > e : missing.entrySet() )
			lines.add( "No interest points are stored for '" + e.getKey() + "' in " + e.getValue().size() + " view(s) " + where( vip, e.getKey(), e.getValue() ) + " although the dataset (XML) lists them." );

		return lines;
	}

	/** @return where a read of the label looked, e.g. "(neither in interestpoints.zarr nor in interestpoints.n5 of 'file:/data/')" */
	public static String where( final ViewInterestPoints vip, final String label, final Collection< ? extends ViewId > views )
	{
		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists vipl = vip.getViewInterestPointLists( viewId );
			final InterestPoints list = vipl == null ? null : vipl.getInterestPointList( label );

			if ( list instanceof InterestPointsZarr )
			{
				final String places = ( (InterestPointsZarr) list ).storedNowhere();
				return places.substring( 0, places.length() - 1 ) + " of '" + list.getBasePath() + "')";
			}
		}

		return "(nowhere)";
	}

	/**
	 * Takes entries out of the dataset, in memory; the next save of the XML writes it without them (and removes what the
	 * store still holds of them, e.g. the marker of a filtered label). Removed are: the label in each of the views, the
	 * filtered labels of that view that filter it (they have no points without it), the correspondences of all other lists
	 * that point to one of them ({@link CorrespondenceTools#removeCorrespondencesTo}; the entries' own correspondences
	 * are gone with their data, so the partners cannot be followed from them), and the records of the action history that
	 * produced a label which no view has any more.
	 *
	 * @param data the dataset
	 * @param entries the entries to remove, the views per label (as {@link #find} returns them)
	 * @return what was removed: the views per label, the filtered labels that went with their base included
	 */
	public static SortedMap< String, List< ViewId > > remove( final SpimData2 data, final Map< String, ? extends Collection< ? extends ViewId > > entries )
	{
		final ViewInterestPoints vip = data.getViewInterestPoints();

		// the labels to remove per view: the entries and their dependants
		final HashMap< ViewId, Set< String > > targets = new HashMap<>();

		for ( final Map.Entry< String, ? extends Collection< ? extends ViewId > > e : entries.entrySet() )
			for ( final ViewId v : e.getValue() )
			{
				final ViewInterestPointLists vipl = vip.getViewInterestPointLists( v );

				if ( vipl == null || !vipl.contains( e.getKey() ) )
					continue;

				final Set< String > ofView = targets.computeIfAbsent( new ViewId( v.getTimePointId(), v.getViewSetupId() ), k -> new HashSet<>() );
				ofView.add( e.getKey() );
				ofView.addAll( InterestPointTools.dependentLabels( vipl, e.getKey() ) );
			}

		final TreeMap< String, List< ViewId > > removed = new TreeMap<>( InterestPointTools.naturalOrder );

		if ( targets.isEmpty() )
			return removed;

		final int correspondences = CorrespondenceTools.removeCorrespondencesTo( vip, targets, Threads.numThreads() );

		for ( final Map.Entry< ViewId, Set< String > > e : targets.entrySet() )
		{
			final ViewInterestPointLists vipl = vip.getViewInterestPointLists( e.getKey() );

			for ( final String label : e.getValue() )
			{
				final InterestPoints list = vipl.getHashMap().remove( label );

				if ( list == null )
					continue;

				// whatever the store or the N5 still holds under the name goes with the next commit
				list.deleteInterestPoints();
				removed.computeIfAbsent( label, l -> new ArrayList<>() ).add( e.getKey() );
			}
		}

		removed.values().forEach( Collections::sort );

		// the history of a label that is gone everywhere
		final Set< String > remaining = new HashSet<>();
		for ( final ViewInterestPointLists vipl : vip.getViewInterestPoints().values() )
			remaining.addAll( vipl.getHashMap().keySet() );

		for ( final Map.Entry< String, List< ViewId > > e : removed.entrySet() )
		{
			IOFunctions.println( "Removed the interest points '" + e.getKey() + "' from " + e.getValue().size() + " view(s) of the dataset." );

			if ( !remaining.contains( e.getKey() ) && data.getActionHistory() != null )
				data.getActionHistory().removeByResultRef( "interestpoints:" + e.getKey() );
		}

		if ( correspondences > 0 )
			IOFunctions.println( "Removed " + correspondences + " correspondences of other interest points that pointed to them." );

		return removed;
	}
}
