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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;

/**
 * The interest points and correspondences of one (view, label), stored in the dataset's {@link InterestPointsZarrStore}.
 * The XML text is the same as in the N5 layout: {@code tpId_X_viewSetupId_Y/label}.
 *
 * An entry that was not converted yet is read from its N5 group through {@link InterestPointsN5ToZarr}; a change of it is
 * saved into the store (the commit takes the points along with changed correspondences), and the explorer's "Resave as ZARR
 * store" ({@link InterestPointsN5ToZarr#moveIntoStore}) or the command-line conversion moves it unchanged. An XML entry
 * that has data in neither place is an error, not an empty list.
 *
 * @author Stephan Preibisch
 */
public class InterestPointsZarr extends InterestPoints
{
	final String path; // the XML text
	final Key key;

	/** the points in the store's layout, null until loaded; replaced as a whole, never changed */
	Points points = null;
	ArrayList< CorrespondingInterestPoints > correspondingInterestPoints = null;

	/** @param path the XML text {@code tpId_X_viewSetupId_Y/label} (see {@link #supports(String)}) */
	public InterestPointsZarr( final URI basePath, final String path )
	{
		super( basePath );
		this.path = path;
		this.key = Key.parse( path );

		if ( key == null )
			throw new IllegalArgumentException( "interest point path not of the form tpId_X_viewSetupId_Y/label: " + path );
	}

	public InterestPointsZarr( final URI basePath, final ViewId viewId, final String label )
	{
		this( basePath, Key.of( viewId, label ).path() );
	}

	/** @return true if the XML text has the form {@code tpId_X_viewSetupId_Y/label}, which the store can hold */
	public static boolean supports( final String path ) { return Key.parse( path ) != null; }

	public Key getKey() { return key; }

	public InterestPointsZarrStore store() { return InterestPointsZarrStore.get( basePath ); }

	@Override
	public String getXMLRepresentation() { return path; }

	/**
	 * @return the points (copies), loaded from the store or the N5 group if needed
	 * @throws RuntimeException if nothing is stored for this label although the XML lists it, or a filtered label is stored under its name
	 */
	@Override
	public synchronized Map< Integer, InterestPoint > getInterestPointsCopy()
	{
		if ( points == null && !loadInterestPoints() )
			throw new RuntimeException( "No interest points are stored for '" + path + "' in '" + basePath + "' (neither in " + InterestPointsZarrStore.ZARR_CONTAINER + " nor in " + InterestPointsN5.baseN5 + ") although the XML lists the label, stopping." );

		if ( points.filtered() )
			throw new RuntimeException( "'" + path + "' is stored as a filtered label, but the XML declares it as a plain one (no 'base' and 'filter' attributes), stopping." );

		return toMap( points );
	}

	/** The points of the store's layout as InterestPoints by id. */
	public static Map< Integer, InterestPoint > toMap( final Points points )
	{
		final int[] ids = points.ids();
		final float[] data = points.data();

		if ( ids.length == 0 )
			return new HashMap<>();

		return IntStream.range( 0, ids.length ).parallel().mapToObj( i -> {
			final int d = i * InterestPointsZarrStore.COLUMNS;
			return new InterestPoint( ids[ i ], new double[] { data[ d ], data[ d + 1 ], data[ d + 2 ] }, data[ d + 3 ], data[ d + 4 ], data[ d + 5 ] );
		} ).collect( Collectors.toMap( InterestPoint::getId, ip -> ip ) );
	}

	/** The store's layout of a collection of InterestPoints: ids and 6 float32 per point (x, y, z, response, sigma, intensity). */
	public static Points toPoints( final Collection< ? extends InterestPoint > list )
	{
		final int n = list == null ? 0 : list.size();
		final int[] ids = new int[ n ];
		final float[] data = new float[ n * InterestPointsZarrStore.COLUMNS ];

		int i = 0;
		if ( list != null )
			for ( final InterestPoint ip : list )
			{
				final double[] l = ip.getL();
				if ( l.length != 3 )
					throw new IllegalArgumentException( "the store holds 3-d points, point " + ip.getId() + " has " + l.length + " coordinates" );

				final int d = i * InterestPointsZarrStore.COLUMNS;
				ids[ i ] = ip.getId();
				data[ d ] = (float) l[ 0 ];
				data[ d + 1 ] = (float) l[ 1 ];
				data[ d + 2 ] = (float) l[ 2 ];
				data[ d + 3 ] = ip.getResponse();
				data[ d + 4 ] = ip.getSigma();
				data[ d + 5 ] = ip.getIntensity();
				++i;
			}

		return new Points( ids, data );
	}

	/**
	 * @return the correspondences (copies), loaded if needed
	 * @throws RuntimeException if nothing is stored for this label although the XML lists it
	 */
	@Override
	public synchronized Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy()
	{
		if ( correspondingInterestPoints == null && !loadCorrespondences() )
			throw new RuntimeException( "No correspondences are stored for '" + path + "' in '" + basePath + "' (neither in " + InterestPointsZarrStore.ZARR_CONTAINER + " nor in " + InterestPointsN5.baseN5 + ") although the XML lists the label, stopping." );

		final ArrayList< CorrespondingInterestPoints > copy = new ArrayList<>( correspondingInterestPoints.size() );
		for ( final CorrespondingInterestPoints correspondence : correspondingInterestPoints )
			copy.add( new CorrespondingInterestPoints( correspondence ) );

		return copy;
	}

	/** If the list is not loaded, reads only this partner's rows from the store; else filters the loaded list. */
	@Override
	public Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy( final ViewId partnerView, final String partnerLabel )
	{
		if ( correspondingInterestPoints == null )
		{
			final List< CorrespondingInterestPoints > fromStore = store().correspondences( key, partnerView, partnerLabel );
			if ( fromStore != null )
				return fromStore;
		}

		return super.getCorrespondingInterestPointsCopy( partnerView, partnerLabel );
	}

	/** If the list is not loaded, reads the partners from the store's index; else derives them from the loaded list. */
	@Override
	public Set< Pair< ViewId, String > > getCorrespondingViews()
	{
		if ( correspondingInterestPoints == null )
		{
			final Set< Pair< ViewId, String > > fromStore = store().correspondingViews( key );
			if ( fromStore != null )
				return fromStore;
		}

		return super.getCorrespondingViews();
	}

	@Override
	protected void setInterestPointsLocal( final Collection< InterestPoint > list )
	{
		this.points = toPoints( list );
	}

	@Override
	protected void setCorrespondingInterestPointsLocal( final Collection< CorrespondingInterestPoints > list )
	{
		if ( list instanceof ArrayList )
			this.correspondingInterestPoints = (ArrayList< CorrespondingInterestPoints >) list;
		else
			this.correspondingInterestPoints = new ArrayList<>( list );
	}

	@Override
	public InterestPoints copyTo(
			final URI basePath,
			final ViewId viewId,
			final String label,
			final Function< String, String > labelMapping,
			final Function< InterestPoint, InterestPoint > pointMapping,
			final boolean copyCorrespondences )
	{
		final HashMap< Integer, Integer > idMapping = new HashMap<>();
		final ArrayList< InterestPoint > mapped = mapPoints( getInterestPointsCopy().values(), pointMapping, idMapping );

		final InterestPoints copy = InterestPoints.newInstance( basePath, viewId, label );
		copy.setParameters( getParameters() );
		copy.setInterestPoints( mapped );
		copy.setCorrespondingInterestPoints( copyCorrespondences ? CorrespondenceTools.mapCorrespondences( getCorrespondingInterestPointsCopy(), idMapping, labelMapping ) : new ArrayList<>() );

		return copy;
	}

	/**
	 * Applies the point mapping of {@link #copyTo} to the points of a label: null drops a point
	 *
	 * @param points - the points of the original label
	 * @param pointMapping - the point of the copy for a point of the original, null drops the point
	 * @param idMapping - filled with original id to copied id for every point that survived (what CorrespondenceTools.mapCorrespondences needs)
	 * @return the points of the copy
	 */
	protected static ArrayList< InterestPoint > mapPoints(
			final Collection< InterestPoint > points,
			final Function< InterestPoint, InterestPoint > pointMapping,
			final Map< Integer, Integer > idMapping )
	{
		final ArrayList< InterestPoint > mapped = new ArrayList<>();

		for ( final InterestPoint ip : points )
		{
			final InterestPoint m = pointMapping.apply( ip );

			if ( m != null )
			{
				mapped.add( m );
				idMapping.put( ip.getId(), m.getId() );
			}
		}

		return mapped;
	}

	/** @return what a save stores as the points of this list (a filtered label its marker), null if there is nothing to store yet */
	protected Points pointsToSave() { return points; }

	/** Saves into the store of this list's base path: in memory during a batch, otherwise into a staging file (see {@link InterestPointsZarrStore#savePoints}). */
	@Override
	public boolean saveInterestPoints( final boolean forceWrite )
	{
		return saveInterestPoints( forceWrite, store() );
	}

	/**
	 * Saves into the given store, the one of the dataset this list is saved with: a list whose own base path is another
	 * (e.g. created before the dataset was given its location) is saved where the dataset is, and takes that base path over,
	 * as the N5 layout's shared writer did.
	 */
	public synchronized boolean saveInterestPoints( final boolean forceWrite, final InterestPointsZarrStore store )
	{
		if ( !modifiedInterestPoints && !forceWrite )
			return true;

		final Points toSave = pointsToSave();
		if ( toSave == null )
			return false;

		store.savePoints( key, toSave );
		basePath = store.getBaseDir();
		modifiedInterestPoints = false;
		return true;
	}

	@Override
	public boolean saveCorrespondingInterestPoints( final boolean forceWrite )
	{
		return saveCorrespondingInterestPoints( forceWrite, store() );
	}

	/** Like {@link #saveInterestPoints(boolean, InterestPointsZarrStore)} for the correspondences. */
	public synchronized boolean saveCorrespondingInterestPoints( final boolean forceWrite, final InterestPointsZarrStore store )
	{
		if ( !modifiedCorrespondingInterestPoints && !forceWrite )
			return true;

		if ( correspondingInterestPoints == null )
			return false;

		store.saveCorrespondences( key, correspondingInterestPoints );
		basePath = store.getBaseDir();
		modifiedCorrespondingInterestPoints = false;
		return true;
	}

	/**
	 * Saves the modified points and correspondences of many lists into one staging file per store (one per Spark task
	 * instead of one per list). Nothing is committed; the next XML save does that.
	 */
	public static void saveStaged( final Collection< ? extends InterestPoints > lists )
	{
		final Map< InterestPointsZarrStore, Map< Key, Points > > pointsByStore = new HashMap<>();
		final Map< InterestPointsZarrStore, Map< Key, List< CorrespondingInterestPoints > > > correspondencesByStore = new HashMap<>();
		final List< InterestPointsZarr > savedPoints = new ArrayList<>();
		final List< InterestPointsZarr > savedCorrespondences = new ArrayList<>();

		for ( final InterestPoints list : lists )
		{
			if ( !( list instanceof InterestPointsZarr ) )
				throw new IllegalArgumentException( "cannot stage a " + list.getClass().getName() + " ('" + list.getXMLRepresentation() + "')" );

			final InterestPointsZarr zarrList = (InterestPointsZarr) list;
			final InterestPointsZarrStore store = zarrList.store();

			synchronized ( zarrList )
			{
				if ( zarrList.modifiedInterestPoints )
				{
					final Points toSave = zarrList.pointsToSave();
					if ( toSave != null )
					{
						pointsByStore.computeIfAbsent( store, s -> new HashMap<>() ).put( zarrList.key, toSave );
						savedPoints.add( zarrList );
					}
				}
				if ( zarrList.modifiedCorrespondingInterestPoints && zarrList.correspondingInterestPoints != null )
				{
					correspondencesByStore.computeIfAbsent( store, s -> new HashMap<>() ).put( zarrList.key, new ArrayList<>( zarrList.correspondingInterestPoints ) );
					savedCorrespondences.add( zarrList );
				}
			}
		}

		final Set< InterestPointsZarrStore > stores = new HashSet<>( pointsByStore.keySet() );
		stores.addAll( correspondencesByStore.keySet() );
		for ( final InterestPointsZarrStore store : stores )
			store.writeStagingFile( pointsByStore.getOrDefault( store, Map.of() ), correspondencesByStore.getOrDefault( store, Map.of() ) );

		for ( final InterestPointsZarr zarrList : savedPoints )
			zarrList.modifiedInterestPoints = false;
		for ( final InterestPointsZarr zarrList : savedCorrespondences )
			zarrList.modifiedCorrespondingInterestPoints = false;
	}

	/**
	 * Writes the points of one (view, label), with no correspondences, to a staging file of the dataset's store (one
	 * file per call); the next XML save commits it. For Spark tasks that detect interest points.
	 */
	public static void stagePoints( final URI baseDir, final ViewId viewId, final String label, final Collection< ? extends InterestPoint > points )
	{
		final Key key = Key.of( viewId, label );
		InterestPointsZarrStore.get( baseDir ).writeStagingFile( Map.of( key, toPoints( points ) ), Map.of( key, List.of() ) );
	}

	/** Loads from the store, else from the N5 group; false if neither has the entry. */
	@Override
	protected boolean loadInterestPoints()
	{
		Points loaded = store().points( key );
		if ( loaded == null )
			loaded = InterestPointsN5ToZarr.get( basePath ).points( path );

		if ( loaded == null )
			return false;

		points = loaded;
		modifiedInterestPoints = false;
		return true;
	}

	/** Loads from the store, else from the N5 group; false if neither has the entry. */
	@Override
	protected boolean loadCorrespondences()
	{
		List< CorrespondingInterestPoints > loaded = store().correspondences( key );
		if ( loaded == null )
			loaded = InterestPointsN5ToZarr.get( basePath ).correspondences( path );

		if ( loaded == null )
			return false;

		correspondingInterestPoints = new ArrayList<>( loaded );
		modifiedCorrespondingInterestPoints = false;
		return true;
	}

	/** Removes the whole entry (points and correspondences) at the next commit, and its N5 group now. */
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
			InterestPointsN5ToZarr.get( basePath ).remove( path );
			return true;
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "InterestPointsZarr.delete( " + path + " ): " + e );
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public String toString() { return getClass().getSimpleName() + "( " + path + " )"; }

	/** A (view, label) pair as the pair API returns it. */
	static Pair< ViewId, String > pair( final ViewId viewId, final String label ) { return new ValuePair<>( viewId, label ); }
}
