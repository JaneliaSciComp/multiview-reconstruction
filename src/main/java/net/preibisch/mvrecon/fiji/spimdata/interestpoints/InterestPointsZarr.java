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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;

/**
 * The interest points and correspondences of one (view, label), stored in the dataset's {@link InterestPointsZarrStore}.
 * The XML text is the same as in the legacy format: {@code tpId_X_viewSetupId_Y/label}.
 *
 * An entry that was not converted yet is read from its legacy group through {@link InterestPointsN5ToZarr}; its next save
 * writes it to the store.
 */
public class InterestPointsZarr extends InterestPoints
{
	/** Point attribute with the image intensity at each point (BigStitcher-Spark detection with --storeIntensities). */
	public static final String INTENSITY = "intensity";

	final String path; // XML text
	final InterestPointsZarrStore.Key key;

	/** The points in the store's layout (ids, flat x y z, attributes); null until loaded. Replaced as a whole, never mutated. */
	InterestPointsZarrStore.Points points = null;
	ArrayList< CorrespondingInterestPoints > correspondingInterestPoints;

	/** @param path the XML text {@code tpId_X_viewSetupId_Y/label} (see {@link #supports(String)}) */
	public InterestPointsZarr( final URI basePath, final String path )
	{
		super( basePath );
		this.path = path;
		this.key = InterestPointsZarrStore.Key.parse( path );
		if ( key == null )
			throw new IllegalArgumentException( "not of the form tpId_X_viewSetupId_Y/label: " + path );
	}

	public InterestPointsZarr( final URI basePath, final ViewId viewId, final String label )
	{
		this( basePath, InterestPointsZarrStore.Key.of( viewId, label ).path() );
	}

	/** @return true if the XML text has the form {@code tpId_X_viewSetupId_Y/label}, which the store can hold */
	public static boolean supports( final String path ) { return InterestPointsZarrStore.Key.parse( path ) != null; }

	InterestPointsZarrStore store() { return InterestPointsZarrStore.get( basePath ); }

	@Override
	public String getXMLRepresentation() { return path; }

	private void ensurePointsLoaded()
	{
		if ( points == null )
			loadInterestPoints();
	}

	@Override
	public synchronized Map< Integer, InterestPoint > getInterestPointsCopy()
	{
		ensurePointsLoaded();
		final int[] ids = points.ids();
		final double[] loc = points.loc();
		if ( ids.length == 0 )
			return new HashMap<>();

		return IntStream.range( 0, ids.length )
				.parallel()
				.mapToObj( i -> new InterestPoint( ids[ i ], Arrays.copyOfRange( loc, 3 * i, 3 * i + 3 ) ) )
				.collect( Collectors.toMap( InterestPoint::getId, point -> point ) );
	}

	@Override
	public synchronized Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy()
	{
		if ( correspondingInterestPoints == null )
			loadCorrespondences();

		final ArrayList< CorrespondingInterestPoints > copy = new ArrayList<>( correspondingInterestPoints.size() );
		for ( final CorrespondingInterestPoints correspondence : correspondingInterestPoints )
			copy.add( new CorrespondingInterestPoints( correspondence ) );

		return copy;
	}

	/** If the list is not loaded, reads only this pair's rows from the store; else filters the loaded list. */
	@Override
	public Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy( final ViewId partnerView, final String partnerLabel )
	{
		return fromStoreOrLoaded(
				() -> store().correspondences( key, partnerView, partnerLabel ),
				loaded -> loaded.stream()
						.filter( c -> c.getCorrespondingViewId().equals( partnerView ) && c.getCorrespodingLabel().equals( partnerLabel ) )
						.collect( Collectors.toList() ) );
	}

	/** If the list is not loaded, reads the partners from the store index; else derives them from the loaded list. */
	@Override
	public Set< Pair< ViewId, String > > getCorrespondingViews()
	{
		return fromStoreOrLoaded(
				() -> store().correspondingViews( key ),
				loaded -> loaded.stream()
						.map( c -> (Pair< ViewId, String >) new ValuePair<>( c.getCorrespondingViewId(), c.getCorrespodingLabel() ) )
						.collect( Collectors.toSet() ) );
	}

	/**
	 * A correspondence query answered by the store while the list is not loaded (the store's index serves it without
	 * reading the whole list), else derived from the loaded list, which may hold unsaved changes.
	 */
	private < T > T fromStoreOrLoaded( final Supplier< T > fromStore, final Function< Collection< CorrespondingInterestPoints >, T > fromLoaded )
	{
		if ( correspondingInterestPoints == null )
		{
			final T answer = fromStore.get();
			if ( answer != null )
				return answer;
		}
		return fromLoaded.apply( getCorrespondingInterestPointsCopy() );
	}

	public synchronized Set< String > getAttributeNames()
	{
		ensurePointsLoaded();
		return new TreeSet<>( points.attributes().keySet() );
	}

	/** @return a copy of an attribute's values, in the order of the points, or null if the list does not have it */
	public synchronized double[] getAttributeCopy( final String name )
	{
		ensurePointsLoaded();
		final double[] values = points.attributes().get( name );
		return values == null ? null : values.clone();
	}

	/**
	 * Sets new points with their attributes (one value per point, in the order of {@code points}). Attributes can only be
	 * set together with the points; {@link #setInterestPoints(Collection)} drops them. An attribute whose values are all -1
	 * ({@link InterestPointsZarrStore#NO_VALUE}) is not stored.
	 *
	 * @param attributes name -> values, may be null
	 */
	public synchronized void setInterestPoints( final Collection< InterestPoint > points, final Map< String, double[] > attributes )
	{
		modifiedInterestPoints = true;
		this.points = toPoints( points, attributes );
	}

	@Override
	protected void setInterestPointsLocal( final Collection< InterestPoint > points )
	{
		this.points = toPoints( points, null ); // attributes do not survive new points
	}

	/** Copies the points (local coordinates) and the attribute arrays into the store's layout. */
	static InterestPointsZarrStore.Points toPoints( final Collection< InterestPoint > points, final Map< String, double[] > attributes )
	{
		final int n = points == null ? 0 : points.size();
		final int[] ids = new int[ n ];
		final double[] loc = new double[ 3 * n ];
		int i = 0;
		if ( points != null )
			for ( final InterestPoint point : points )
			{
				ids[ i ] = point.getId();
				System.arraycopy( point.getL(), 0, loc, 3 * i, 3 );
				++i;
			}

		final TreeMap< String, double[] > attrsCopy = new TreeMap<>();
		if ( attributes != null )
			attributes.forEach( ( name, values ) -> attrsCopy.put( name, values.clone() ) );

		return new InterestPointsZarrStore.Points( ids, loc, attrsCopy );
	}

	@Override
	protected void setCorrespondingInterestPointsLocal( final Collection< CorrespondingInterestPoints > correspondences )
	{
		if ( correspondences instanceof ArrayList )
			correspondingInterestPoints = (ArrayList< CorrespondingInterestPoints >) correspondences;
		else
			correspondingInterestPoints = new ArrayList<>( correspondences );
	}

	/**
	 * Saves the modified points and correspondences of many lists into one staging file per store (one per Spark task
	 * instead of one per list). Nothing is committed.
	 */
	public static void saveStaged( final Collection< ? extends InterestPoints > lists )
	{
		final Map< InterestPointsZarrStore, Map< InterestPointsZarrStore.Key, InterestPointsZarrStore.Points > > pointsByStore = new HashMap<>();
		final Map< InterestPointsZarrStore, Map< InterestPointsZarrStore.Key, List< CorrespondingInterestPoints > > > correspondencesByStore = new HashMap<>();
		final List< InterestPointsZarr > saved = new ArrayList<>();

		for ( final InterestPoints list : lists )
		{
			final InterestPointsZarr zarrList = (InterestPointsZarr) list; // the only InterestPoints implementation
			final InterestPointsZarrStore store = zarrList.store();
			if ( zarrList.modifiedInterestPoints && zarrList.points != null )
				pointsByStore.computeIfAbsent( store, s -> new HashMap<>() ).put( zarrList.key, zarrList.points );
			if ( zarrList.modifiedCorrespondingInterestPoints && zarrList.correspondingInterestPoints != null )
			{
				final List< CorrespondingInterestPoints > correspondences = new ArrayList<>( zarrList.correspondingInterestPoints );
				correspondencesByStore.computeIfAbsent( store, s -> new HashMap<>() ).put( zarrList.key, correspondences );
			}
			saved.add( zarrList );
		}

		final Set< InterestPointsZarrStore > stores = new HashSet<>( pointsByStore.keySet() );
		stores.addAll( correspondencesByStore.keySet() );
		for ( final InterestPointsZarrStore store : stores )
			store.writeStagingFile( pointsByStore.getOrDefault( store, Map.of() ), correspondencesByStore.getOrDefault( store, Map.of() ) );

		for ( final InterestPointsZarr zarrList : saved )
		{
			zarrList.modifiedInterestPoints = false;
			zarrList.modifiedCorrespondingInterestPoints = false;
		}
	}

	/** Saves into the store: in memory during a batch, otherwise into a staging file (see {@link InterestPointsZarrStore#savePoints}). */
	@Override
	public synchronized boolean saveInterestPoints( final boolean forceWrite )
	{
		if ( !modifiedInterestPoints && !forceWrite )
			return true;
		if ( points == null )
			return false;

		store().savePoints( key, points );
		modifiedInterestPoints = false;
		return true;
	}

	@Override
	public synchronized boolean saveCorrespondingInterestPoints( final boolean forceWrite )
	{
		if ( !modifiedCorrespondingInterestPoints && !forceWrite )
			return true;
		if ( correspondingInterestPoints == null )
			return false;

		store().saveCorrespondences( key, correspondingInterestPoints );
		modifiedCorrespondingInterestPoints = false;
		return true;
	}

	/** Loads from the store, else from the legacy group; empty if neither has the entry. */
	@Override
	protected boolean loadInterestPoints()
	{
		InterestPointsZarrStore.Points loaded = store().points( key );
		if ( loaded == null )
			loaded = InterestPointsN5ToZarr.get( basePath ).points( path ); // legacy: goes with InterestPointsN5

		final boolean found = loaded != null;
		points = found ? loaded : new InterestPointsZarrStore.Points( new int[ 0 ], new double[ 0 ] );
		modifiedInterestPoints = false;
		return found;
	}

	/** Loads from the store, else from the legacy group; empty if neither has the entry. */
	@Override
	protected boolean loadCorrespondences()
	{
		List< CorrespondingInterestPoints > correspondences = store().correspondences( key );
		if ( correspondences == null )
			correspondences = InterestPointsN5ToZarr.get( basePath ).correspondences( path ); // legacy: goes with InterestPointsN5

		correspondingInterestPoints = correspondences == null ? new ArrayList<>() : new ArrayList<>( correspondences );
		modifiedCorrespondingInterestPoints = false;
		return correspondences != null;
	}

	/** Removes the whole entry (points and correspondences) at the next commit, and its legacy group now. */
	@Override
	public boolean deleteInterestPoints() { return delete(); }

	/** Same as {@link #deleteInterestPoints()}: the store keeps points and correspondences as one entry. */
	@Override
	public boolean deleteCorrespondingInterestPoints() { return delete(); }

	private boolean delete()
	{
		try
		{
			store().remove( key );
			InterestPointsN5ToZarr.get( basePath ).remove( path ); // legacy: goes with InterestPointsN5
			return true;
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "InterestPointsZarr.delete(" + path + "): " + e );
			e.printStackTrace();
			return false;
		}
	}
}
