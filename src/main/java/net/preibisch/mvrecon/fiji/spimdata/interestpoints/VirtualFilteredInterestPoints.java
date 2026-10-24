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

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;

/**
 * A filtered (virtual) label: it stores no points of its own but filters the points of its base label (another label
 * of the same view) on the fly with an {@link InterestPointFilter} whenever they are requested, keeping their ids, and
 * it has its own correspondences (stored like the ones of any label). The base label is looked up in the
 * {@link ViewInterestPointLists} the label belongs to at every access, so a base label that was just detected and not
 * saved yet, or one that was detected again, is seen as it is now; a missing base label is an error. The base can be
 * a filtered label itself.
 *
 * The XML records the base label and the filter (attributes 'base' and 'filter' of the label element) and is
 * authoritative. The store holds the label's correspondences and, instead of points, a marker entry that says it is
 * filtered, so that a reader that takes the label for a plain one fails instead of loading an empty list; the other way
 * round, a plain point list stored under the name of a filtered label is an error, too.
 *
 * Filtered labels are read-only: thin out, split or "new interest points from correspondences" read them like any label
 * and create plain labels.
 *
 * @author Stephan Preibisch
 */
public class VirtualFilteredInterestPoints extends InterestPointsZarr
{
	/** longer chains of filtered labels are refused (a base that is filtered itself, ..., most likely a cycle) */
	public static int maxChainLength = 16;

	final String baseLabel;
	final InterestPointFilter filter;

	/** the labels of the view this label belongs to, set by ViewInterestPointLists.addInterestPointList */
	ViewInterestPointLists owner = null;

	boolean verified = false;

	/**
	 * A filtered label as loaded from the XML (nothing to save until something changes), see create() for a new one
	 */
	public VirtualFilteredInterestPoints( final URI basePath, final String path, final String baseLabel, final InterestPointFilter filter )
	{
		super( basePath, path );

		if ( baseLabel == null || baseLabel.isEmpty() )
			throw new IllegalArgumentException( "A filtered label needs a base label ('" + path + "')." );

		if ( filter == null )
			throw new IllegalArgumentException( "A filtered label needs a filter ('" + path + "')." );

		this.baseLabel = baseLabel;
		this.filter = filter;
	}

	/**
	 * A new filtered label of a view (its marker and its empty correspondences are written on the next save), to be
	 * added to the labels of the view with ViewInterestPointLists.addInterestPointList
	 */
	public static VirtualFilteredInterestPoints create( final URI basePath, final ViewId viewId, final String label, final String baseLabel, final InterestPointFilter filter )
	{
		if ( label.equals( baseLabel ) )
			throw new IllegalArgumentException( "A filtered label cannot be its own base ('" + label + "')." );

		final VirtualFilteredInterestPoints list = new VirtualFilteredInterestPoints( basePath, Key.of( viewId, label ).path(), baseLabel, filter );
		list.modifiedInterestPoints = true;
		list.verified = true;
		list.setCorrespondingInterestPoints( new ArrayList<>() );

		return list;
	}

	/**
	 * The copy stays a filter of the mapped base label (which the caller copies as well), the point mapping applies to the
	 * base; the correspondences follow the points of this label through the same mapping (CorrespondenceTools.mapCorrespondences)
	 */
	@Override
	public InterestPoints copyTo(
			final URI basePath,
			final ViewId viewId,
			final String label,
			final Function< String, String > labelMapping,
			final Function< InterestPoint, InterestPoint > pointMapping,
			final boolean copyCorrespondences )
	{
		final String mappedBase = labelMapping.apply( baseLabel );
		final VirtualFilteredInterestPoints copy = create( basePath, viewId, label, mappedBase, filter );
		copy.setParameters( mappedBase.equals( baseLabel ) ? getParameters() : filter.describe( mappedBase ) );

		final Collection< CorrespondingInterestPoints > correspondences = copyCorrespondences ? getCorrespondingInterestPointsCopy() : new ArrayList<>();

		if ( correspondences.isEmpty() )
		{
			copy.setCorrespondingInterestPoints( new ArrayList<>() );
		}
		else
		{
			// which of the (filtered) points survive the mapping and under which id
			final HashMap< Integer, Integer > idMapping = new HashMap<>();
			mapPoints( getInterestPointsCopy().values(), pointMapping, idMapping );
			copy.setCorrespondingInterestPoints( CorrespondenceTools.mapCorrespondences( correspondences, idMapping, labelMapping ) );
		}

		return copy;
	}

	public String getBaseLabel() { return baseLabel; }
	public InterestPointFilter getFilter() { return filter; }
	public ViewInterestPointLists getOwner() { return owner; }
	public void setOwner( final ViewInterestPointLists owner ) { this.owner = owner; }

	/**
	 * @return the base label of this label in its view
	 * @throws IllegalStateException if the label is not attached to a view or the base label does not exist (any more)
	 */
	public InterestPoints getBase()
	{
		if ( owner == null )
			throw new IllegalStateException( "Filtered label '" + path + "' is not attached to the labels of its view, cannot find its base label '" + baseLabel + "'." );

		final InterestPoints base = owner.getInterestPointList( baseLabel );

		if ( base == null )
			throw new IllegalStateException( "Filtered label '" + path + "' refers to the base label '" + baseLabel + "', which does not exist (any more) in its view." );

		if ( base == this )
			throw new IllegalStateException( "Filtered label '" + path + "' is its own base label." );

		return base;
	}

	/**
	 * @return the points of the base label that pass the filter (copies, with the ids of the base label)
	 */
	@Override
	public synchronized Map< Integer, InterestPoint > getInterestPointsCopy()
	{
		if ( !verified )
		{
			verifyStored();
			verified = true;
		}

		return getInterestPointsCopy( 1 );
	}

	protected Map< Integer, InterestPoint > getInterestPointsCopy( final int chainLength )
	{
		if ( chainLength > maxChainLength )
			throw new IllegalStateException( "The chain of filtered labels at '" + path + "' is longer than " + maxChainLength + " (a cycle?)." );

		final InterestPoints base = getBase();

		final Map< Integer, InterestPoint > points =
				base instanceof VirtualFilteredInterestPoints ?
						( (VirtualFilteredInterestPoints)base ).getInterestPointsCopy( chainLength + 1 ) :
						base.getInterestPointsCopy();

		final HashMap< Integer, InterestPoint > filtered = new HashMap<>();

		// the ranges per point and the count limit per view (this is one view)
		for ( final InterestPoint ip : filter.filter( points.values() ) )
			filtered.put( ip.getId(), ip );

		return filtered;
	}

	@Override
	protected void setInterestPointsLocal( final Collection< InterestPoint > list )
	{
		throw new UnsupportedOperationException( "Filtered label '" + path + "' is read-only, it filters '" + baseLabel + "' on the fly; create a plain label instead." );
	}

	/** Nothing to load (the points are the filtered ones of the base label), checks that what is stored agrees with the XML */
	@Override
	protected boolean loadInterestPoints()
	{
		verifyStored();
		verified = true;
		return true;
	}

	/**
	 * @throws RuntimeException if the store or the N5 holds a plain point list under the name of this filtered label
	 */
	protected void verifyStored()
	{
		final Boolean filteredInStore = store().isFiltered( key );

		if ( filteredInStore != null )
		{
			if ( !filteredInStore )
				throw new RuntimeException( "The XML declares '" + path + "' as a filtered label (base '" + baseLabel + "', filter " + filter + "), but a plain point list is stored under its name in " + InterestPointsZarrStore.ZARR_CONTAINER + ", stopping." );
			return;
		}

		final Points legacy = InterestPointsN5ToZarr.get( basePath ).points( path );
		if ( legacy != null && !legacy.filtered() )
			throw new RuntimeException( "The XML declares '" + path + "' as a filtered label (base '" + baseLabel + "', filter " + filter + "), but a plain point list is stored under its name in " + InterestPointsN5.baseN5 + ", stopping." );
	}

	/** The marker entry of a filtered label: no points of its own. */
	@Override
	protected Points pointsToSave() { return Points.filteredMarker(); }
}
