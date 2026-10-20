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

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import util.URITools;

/**
 * A filtered (virtual) label: it stores no points of its own but filters the points of its base label (another label
 * of the same view) on the fly with an {@link InterestPointFilter} whenever they are requested, keeping their ids, and
 * it has its own correspondences (stored like the ones of any label). The base label is looked up in the
 * {@link ViewInterestPointLists} the label belongs to at every access, so a base label that was just detected and not
 * saved yet, or one that was detected again, is seen as it is now; a missing base label is an error. The base can be
 * a filtered label itself.
 *
 * The XML records the base label and the filter (attributes 'base' and 'filter' of the label element); the N5 holds the
 * correspondences and, instead of the 'interestpoints' datasets, a marker group of type 'filtered' with the same base and
 * filter, so that readers that do not know filtered labels fail instead of loading an empty label. The XML is
 * authoritative, a marker that disagrees with it is an error.
 *
 * Filtered labels are read-only: thin out, split or "new interest points from correspondences" read them like any label
 * and create plain labels.
 *
 * @author Stephan Preibisch
 */
public class VirtualFilteredInterestPointsN5 extends InterestPointsN5
{
	public static final String filteredType = "filtered";
	public static final String baseAttribute = "base";
	public static final String filterAttribute = "filter";

	/** longer chains of filtered labels are refused (a base that is filtered itself, ..., most likely a cycle) */
	public static int maxChainLength = 16;

	final String baseLabel;
	final InterestPointFilter filter;

	/** the labels of the view this label belongs to, set by ViewInterestPointLists.addInterestPointList */
	ViewInterestPointLists owner = null;

	boolean markerVerified = false;

	/**
	 * A filtered label as loaded from the XML (nothing to save until something changes), see create() for a new one
	 */
	public VirtualFilteredInterestPointsN5( final URI basePath, final String n5dataset, final String baseLabel, final InterestPointFilter filter )
	{
		super( basePath, n5dataset );

		if ( baseLabel == null || baseLabel.isEmpty() )
			throw new IllegalArgumentException( "A filtered label needs a base label ('" + n5dataset + "')." );

		if ( filter == null )
			throw new IllegalArgumentException( "A filtered label needs a filter ('" + n5dataset + "')." );

		this.baseLabel = baseLabel;
		this.filter = filter;
	}

	/**
	 * A new filtered label of a view (its marker and its empty correspondences are written on the next save), to be
	 * added to the labels of the view with ViewInterestPointLists.addInterestPointList
	 */
	public static VirtualFilteredInterestPointsN5 create( final URI basePath, final ViewId viewId, final String label, final String baseLabel, final InterestPointFilter filter )
	{
		if ( label.equals( baseLabel ) )
			throw new IllegalArgumentException( "A filtered label cannot be its own base ('" + label + "')." );

		final VirtualFilteredInterestPointsN5 list = new VirtualFilteredInterestPointsN5( basePath, createN5datasetPath( viewId.getTimePointId(), viewId.getViewSetupId(), label ), baseLabel, filter );
		list.modifiedInterestPoints = true;
		list.markerVerified = true;
		list.setCorrespondingInterestPoints( new ArrayList<>() );

		return list;
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
			throw new IllegalStateException( "Filtered label '" + n5dataset + "' is not attached to the labels of its view, cannot find its base label '" + baseLabel + "'." );

		final InterestPoints base = owner.getInterestPointList( baseLabel );

		if ( base == null )
			throw new IllegalStateException( "Filtered label '" + n5dataset + "' refers to the base label '" + baseLabel + "', which does not exist (any more) in its view." );

		if ( base == this )
			throw new IllegalStateException( "Filtered label '" + n5dataset + "' is its own base label." );

		return base;
	}

	/**
	 * @return the points of the base label that pass the filter (copies, with the ids of the base label)
	 */
	@Override
	public synchronized Map< Integer, InterestPoint > getInterestPointsCopy()
	{
		if ( !markerVerified )
		{
			verifyMarker();
			markerVerified = true;
		}

		return getInterestPointsCopy( 1 );
	}

	protected Map< Integer, InterestPoint > getInterestPointsCopy( final int chainLength )
	{
		if ( chainLength > maxChainLength )
			throw new IllegalStateException( "The chain of filtered labels at '" + n5dataset + "' is longer than " + maxChainLength + " (a cycle?)." );

		final InterestPoints base = getBase();

		final Map< Integer, InterestPoint > points =
				base instanceof VirtualFilteredInterestPointsN5 ?
						( (VirtualFilteredInterestPointsN5)base ).getInterestPointsCopy( chainLength + 1 ) :
						base.getInterestPointsCopy();

		final HashMap< Integer, InterestPoint > filtered = new HashMap<>();

		for ( final InterestPoint ip : points.values() )
			if ( filter.accept( ip ) )
				filtered.put( ip.getId(), ip );

		return filtered;
	}

	@Override
	protected void setInterestPointsLocal( final Collection< InterestPoint > list )
	{
		throw new UnsupportedOperationException( "Filtered label '" + n5dataset + "' is read-only, it filters '" + baseLabel + "' on the fly; create a plain label instead." );
	}

	/**
	 * Nothing to load (the points are the filtered ones of the base label), checks that the marker in the N5, if there is one, agrees with the XML
	 *
	 * @throws RuntimeException if the N5 holds a point list or a marker with another base or filter
	 */
	@Override
	protected boolean loadInterestPoints()
	{
		verifyMarker();
		markerVerified = true;
		return true;
	}

	protected void verifyMarker()
	{
		try ( final N5Reader n5 = URITools.instantiateN5Reader( StorageFormat.N5, URITools.toURI( URITools.appendName( basePath, baseN5 ) ) ) )
		{
			final String dataset = ipDataset();

			if ( !n5.exists( dataset ) )
				return;

			final String type = n5.getAttribute( dataset, "type", String.class );
			final String base = n5.getAttribute( dataset, baseAttribute, String.class );
			final String f = n5.getAttribute( dataset, filterAttribute, String.class );

			if ( !filteredType.equals( type ) || !baseLabel.equals( base ) || !filter.toString().equals( f ) )
				throw new RuntimeException( "The N5 marker of filtered label '" + n5dataset + "' (type=" + type + ", base=" + base + ", filter=" + f + ") does not agree with the XML (base=" + baseLabel + ", filter=" + filter + "), stopping." );
		}
	}

	@Override
	public boolean saveInterestPoints( final boolean forceWrite )
	{
		if ( !modifiedInterestPoints && !forceWrite )
			return true;

		try ( final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( basePath, baseN5 ) ) ) )
		{
			return saveInterestPoints( forceWrite, n5Writer );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Couldn't write the marker of filtered label '" + n5dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public boolean saveInterestPoints( final boolean forceWrite, final N5Writer n5Writer )
	{
		if ( !modifiedInterestPoints && !forceWrite )
			return true;

		final boolean success = saveMarkerStatic( n5Writer, n5dataset, baseLabel, filter );

		if ( success )
			modifiedInterestPoints = false;

		return success;
	}

	/**
	 * Writes the marker group of a filtered label in place of the 'interestpoints' datasets: type 'filtered', the base label and the filter
	 */
	public static boolean saveMarkerStatic( final N5Writer n5Writer, final String n5path, final String baseLabel, final InterestPointFilter filter )
	{
		final String dataset = ipDataset( n5path );

		try
		{
			if ( n5Writer.exists( dataset ) )
				n5Writer.remove( dataset );

			n5Writer.createGroup( dataset );

			n5Writer.setAttribute( dataset, "pointcloud", "1.0.0" );
			n5Writer.setAttribute( dataset, "type", filteredType );
			n5Writer.setAttribute( dataset, baseAttribute, baseLabel );
			n5Writer.setAttribute( dataset, filterAttribute, filter.toString() );

			IOFunctions.println( "Saved: " + dataset + " (filtered label of '" + baseLabel + "': " + filter + ")" );

			return true;
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Couldn't write the marker of filtered label '" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}
}
