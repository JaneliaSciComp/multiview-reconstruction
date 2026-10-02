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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;

/**
 * The interest points and correspondences of one (view, label), stored in the dataset's {@link InterestPointsZarrStore}
 * ({@code interestpoints.zarr}). The XML text is the same as for the legacy format: {@code tpId_X_viewSetupId_Y/label}.
 *
 * An entry that is not in the store yet (a dataset that was never converted) is read from its legacy per-view group
 * through {@link InterestPointsN5ToZarr}, read-only; the next save of the entry writes it to the store.
 */
public class InterestPointsZarr extends InterestPoints
{
	/** per-point attribute holding the image intensity at each point (BigStitcher-Spark detection --storeIntensities) */
	public static final String INTENSITY = "intensity";

	final String n5dataset;
	final InterestPointsZarrStore.Key key;

	int[] ids = null;
	double[][] locations = null;
	/** named per-point attributes, one value per point in the order of ids; part of the points: loaded, set and saved with them */
	TreeMap< String, double[] > attributes = new TreeMap<>();
	ArrayList< CorrespondingInterestPoints > correspondingInterestPoints;

	/** @param n5dataset the XML text, {@code tpId_X_viewSetupId_Y/label} ({@link #supports(String)}) */
	public InterestPointsZarr( final URI basePath, final String n5dataset )
	{
		super( basePath );
		this.n5dataset = n5dataset;
		this.key = InterestPointsZarrStore.Key.parse( n5dataset );
		if ( key == null )
			throw new IllegalArgumentException( "not of the form tpId_X_viewSetupId_Y/label: " + n5dataset );
	}

	public InterestPointsZarr( final URI basePath, final ViewId viewId, final String label )
	{
		this( basePath, InterestPointsZarrStore.Key.of( viewId, label ).path() );
	}

	/** @return true if an XML entry can be addressed by (timepoint, setup, label), i.e. stored in the Zarr store */
	public static boolean supports( final String n5dataset ) { return InterestPointsZarrStore.Key.parse( n5dataset ) != null; }

	/** the store of this dataset, shared by all lists */
	InterestPointsZarrStore store() { return InterestPointsZarrStore.get( basePath ); }

	@Override
	public String getXMLRepresentation() { return n5dataset; }

	private void ensurePointsLoaded()
	{
		if ( this.locations == null || this.ids == null )
			loadInterestPoints();
	}

	@Override
	public synchronized Map< Integer, InterestPoint > getInterestPointsCopy()
	{
		ensurePointsLoaded();

		if ( ids.length == 0 )
			return new HashMap<>();

		return IntStream.range( 0, ids.length ).parallel().mapToObj( i -> new InterestPoint( ids[ i ], locations[ i ].clone() ) ).collect( Collectors.toMap( InterestPoint::getId, ip -> ip ) );
	}

	@Override
	public synchronized Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy()
	{
		if ( this.correspondingInterestPoints == null )
			loadCorrespondences();

		final ArrayList< CorrespondingInterestPoints > list = new ArrayList<>( correspondingInterestPoints.size() );
		for ( final CorrespondingInterestPoints p : this.correspondingInterestPoints )
			list.add( new CorrespondingInterestPoints( p ) );
		return list;
	}

	/** not loaded in memory: one range read of the pair from the store instead of copying the whole list */
	@Override
	public Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy( final ViewId correspondingViewId, final String correspondingLabel )
	{
		final List< CorrespondingInterestPoints > l = correspondingInterestPoints == null ? store().correspondences( key, correspondingViewId, correspondingLabel ) : null;
		return l != null ? l : super.getCorrespondingInterestPointsCopy( correspondingViewId, correspondingLabel );
	}

	@Override
	public Set< Pair< ViewId, String > > getCorrespondingViews()
	{
		final Set< Pair< ViewId, String > > s = correspondingInterestPoints == null ? store().correspondingViews( key ) : null;
		return s != null ? s : super.getCorrespondingViews();
	}

	/** @return the names of the per-point attributes of this list (loads the points if needed) */
	public synchronized Set< String > getAttributeNames()
	{
		ensurePointsLoaded();
		return new TreeSet<>( attributes.keySet() );
	}

	/** @return a copy of the values of a per-point attribute, in the order of the points, or null if the list does not have it */
	public synchronized double[] getAttributeCopy( final String name )
	{
		ensurePointsLoaded();
		final double[] v = attributes.get( name );
		return v == null ? null : v.clone();
	}

	/**
	 * Sets new points together with their per-point attributes (one value per point, in the order of {@code points}).
	 * Attributes belong to the points: they change only with the points, so there is no way to set them on their own, and
	 * {@link #setInterestPoints(Collection)} drops them. Values that are all {@link InterestPointsZarrStore#NO_VALUE} (-1)
	 * are not stored (-1 means "no value").
	 *
	 * @param attributes name -> values, may be null
	 */
	public synchronized void setInterestPoints( final Collection< InterestPoint > points, final Map< String, double[] > attributes )
	{
		final TreeMap< String, double[] > a = new TreeMap<>();
		if ( attributes != null )
		{
			InterestPointsZarrStore.checkAttributes( attributes, points == null ? 0 : points.size() );
			attributes.forEach( ( name, v ) -> a.put( name, v.clone() ) );
		}
		setInterestPoints( points );
		this.attributes = a;
	}

	@Override
	protected void setInterestPointsLocal( final Collection< InterestPoint > collection )
	{
		this.attributes = new TreeMap<>(); // per-point values do not survive new points

		if ( collection == null || collection.size() == 0 )
		{
			this.ids = new int[ 0 ];
			this.locations = new double[ 0 ][ 0 ];
			return;
		}

		this.ids = new int[ collection.size() ];
		this.locations = new double[ collection.size() ][];

		final Iterator< InterestPoint > it = collection.iterator();
		for ( int i = 0; i < ids.length; ++i )
		{
			final InterestPoint ip = it.next();
			ids[ i ] = ip.getId();
			locations[ i ] = ip.getL().clone();
		}
	}

	@Override
	protected void setCorrespondingInterestPointsLocal( final Collection< CorrespondingInterestPoints > list )
	{
		this.correspondingInterestPoints = list instanceof ArrayList ? (ArrayList< CorrespondingInterestPoints >) list : new ArrayList<>( list );
	}

	/**
	 * Saves the modified points and correspondences of many lists into ONE staging file per store (one file per Spark task
	 * instead of one per entry, see {@link InterestPointsZarrStore#writeStagingFile}). Other lists are saved one by one.
	 * Nothing is committed: the next XML save folds the file in.
	 */
	public static void saveStaged( final Collection< ? extends InterestPoints > lists )
	{
		final Map< InterestPointsZarrStore, Map< InterestPointsZarrStore.Key, InterestPointsZarrStore.Points > > pts = new HashMap<>();
		final Map< InterestPointsZarrStore, Map< InterestPointsZarrStore.Key, List< CorrespondingInterestPoints > > > corr = new HashMap<>();
		final List< InterestPointsZarr > written = new ArrayList<>();
		for ( final InterestPoints ip : lists )
		{
			if ( !( ip instanceof InterestPointsZarr ) )
			{
				ip.saveInterestPoints( false );
				ip.saveCorrespondingInterestPoints( false );
				continue;
			}
			final InterestPointsZarr l = (InterestPointsZarr) ip;
			final InterestPointsZarrStore store = l.store();
			if ( l.modifiedInterestPoints && l.ids != null && l.locations != null )
				pts.computeIfAbsent( store, x -> new HashMap<>() ).put( l.key, InterestPointsZarrStore.Points.of( l.ids, l.locations, l.attributes ) );
			if ( l.modifiedCorrespondingInterestPoints && l.correspondingInterestPoints != null )
				corr.computeIfAbsent( store, x -> new HashMap<>() ).put( l.key, new ArrayList<>( l.correspondingInterestPoints ) );
			written.add( l );
		}
		final Set< InterestPointsZarrStore > stores = new HashSet<>( pts.keySet() );
		stores.addAll( corr.keySet() );
		for ( final InterestPointsZarrStore store : stores )
			store.writeStagingFile( pts.getOrDefault( store, Map.of() ), corr.getOrDefault( store, Map.of() ) );
		for ( final InterestPointsZarr l : written ) { l.modifiedInterestPoints = false; l.modifiedCorrespondingInterestPoints = false; }
	}

	/**
	 * In memory while a batch is open (XmlIoSpimData2.saveInterestPointsInParallel commits right after its loop), else one
	 * durable staging file (no commit will follow in this JVM, e.g. a Spark executor).
	 */
	@Override
	public synchronized boolean saveInterestPoints( final boolean forceWrite )
	{
		if ( !modifiedInterestPoints && !forceWrite )
			return true;
		if ( ids == null || locations == null )
			return false;
		store().savePoints( key, InterestPointsZarrStore.Points.of( ids, locations, attributes ) );
		modifiedInterestPoints = false;
		return true;
	}

	/** correspondences; same dispatch as {@link #saveInterestPoints(boolean)} */
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

	/** from the store, else (entry not converted yet) read-only from the legacy container; nothing anywhere = empty */
	@Override
	protected boolean loadInterestPoints()
	{
		InterestPointsZarrStore.Points p = store().points( key );
		if ( p == null )
			p = InterestPointsN5ToZarr.get( basePath ).points( n5dataset ); // legacy: goes with InterestPointsN5
		final boolean found = p != null;
		if ( !found )
			p = new InterestPointsZarrStore.Points( new int[ 0 ], new double[ 0 ] );
		this.ids = p.ids().clone();
		this.locations = p.locations(); // ponytail: keep the double[][] layout of InterestPoints for now, flat arrays would halve memory
		this.attributes = new TreeMap<>();
		p.attributes().forEach( ( name, v ) -> attributes.put( name, v.clone() ) );
		modifiedInterestPoints = false;
		return found;
	}

	/** from the store, else read-only from the legacy container; nothing anywhere = empty */
	@Override
	protected boolean loadCorrespondences()
	{
		List< CorrespondingInterestPoints > l = store().correspondences( key );
		if ( l == null )
			l = InterestPointsN5ToZarr.get( basePath ).correspondences( n5dataset ); // legacy: goes with InterestPointsN5
		this.correspondingInterestPoints = l == null ? new ArrayList<>() : new ArrayList<>( l );
		modifiedCorrespondingInterestPoints = false;
		return l != null;
	}

	/** points AND correspondences of the entry go at the next commit (they are one entry in the store), and so does its legacy group */
	@Override
	public boolean deleteInterestPoints() { return delete(); }

	@Override
	public boolean deleteCorrespondingInterestPoints() { return delete(); }

	private boolean delete()
	{
		try
		{
			store().remove( key ); // committed with the next save
			InterestPointsN5ToZarr.get( basePath ).remove( n5dataset ); // legacy: goes with InterestPointsN5
			return true;
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "InterestPointsZarr.delete(" + n5dataset + "): " + e );
			e.printStackTrace();
			return false;
		}
	}
}
