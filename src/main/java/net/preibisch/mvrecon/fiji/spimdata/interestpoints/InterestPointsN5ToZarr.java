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

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5.PointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Group;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import util.URITools;

/**
 * Everything the Zarr store needs from the N5 layout ({@code interestpoints.n5/tpId_X_viewSetupId_Y/label}): reading
 * entries that were not converted yet, removing their groups, moving the entries of selected views into the store
 * ({@link #moveIntoStore}, the ViewSetup Explorer's "Resave as ZARR store" in "Filter/Manage Interest Points", which
 * keeps the N5), and converting a whole dataset ({@link #convert}, which verifies and deletes the N5):
 *
 * <pre>java -cp &lt;fat jar&gt; net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr &lt;dataset.xml | dataset directory&gt;</pre>
 *
 * An entry stays readable where it is until it moves (InterestPointsZarr reads the store first, then the N5); the XML
 * text of a label is the same for both. Points stored without their attributes keep NaN for them in the store.
 *
 * @author Stephan Preibisch
 */
public class InterestPointsN5ToZarr
{
	private static final ConcurrentHashMap< String, InterestPointsN5ToZarr > containers = new ConcurrentHashMap<>();

	/** @return the N5 side of a dataset directory; one per directory, like the store */
	public static InterestPointsN5ToZarr get( final URI baseDir )
	{
		return containers.computeIfAbsent( InterestPointsZarrStore.storeKey( baseDir ), key -> new InterestPointsN5ToZarr( baseDir ) );
	}

	final URI containerURI;
	private final KeyValueAccess kva;
	private N5Reader reader = null; // guarded by this

	private InterestPointsN5ToZarr( final URI baseDir )
	{
		this.containerURI = URITools.toURI( URITools.appendName( baseDir, InterestPointsN5.baseN5 ) );
		this.kva = URITools.getKeyValueAccess( containerURI );
	}

	/** @return true if the dataset has an N5 interest point container */
	public boolean exists() { return kva.exists( kva.compose( containerURI, "attributes.json" ) ); }

	/** @return a reader of the N5 container, or null if there is none */
	private synchronized N5Reader reader()
	{
		if ( reader == null && exists() )
			reader = URITools.instantiateN5Reader( StorageFormat.N5, containerURI );

		return reader;
	}

	/**
	 * Reads the points of an entry with their attributes (NaN where the label was stored without them).
	 *
	 * @param path the entry, {@code tpId_X_viewSetupId_Y/label}
	 * @return the points, the filtered marker for a filtered label's marker group, or null if the N5 does not have the entry
	 * @throws RuntimeException if the group is of a type this reader does not know or inconsistent
	 */
	public Points points( final String path )
	{
		final N5Reader n5 = reader();
		final String dataset = InterestPointsN5.ipDataset( path );

		if ( n5 == null || !n5.exists( dataset ) )
			return null;

		final String type = n5.getAttribute( dataset, "type", String.class );

		if ( InterestPointsN5.filteredType.equals( type ) )
			return Points.filteredMarker();

		if ( !"list".equals( type ) )
			throw new RuntimeException( "Unsupported point cloud type '" + type + "' of '" + dataset + "' in '" + containerURI + "', stopping." );

		return toPoints( InterestPointsN5.loadPointArrays( n5, dataset ), dataset );
	}

	/** The store's layout of the arrays of an N5 entry. */
	static Points toPoints( final PointArrays a, final String dataset )
	{
		final int n = a.ids.length;
		final float[] data = new float[ n * InterestPointsZarrStore.COLUMNS ];

		for ( int i = 0; i < n; ++i )
		{
			if ( a.locations[ i ].length != 3 )
				throw new RuntimeException( "'" + dataset + "' has " + a.locations[ i ].length + " coordinates per point, the store holds 3-d points, stopping." );

			final int d = i * InterestPointsZarrStore.COLUMNS;
			data[ d ] = (float) a.locations[ i ][ 0 ];
			data[ d + 1 ] = (float) a.locations[ i ][ 1 ];
			data[ d + 2 ] = (float) a.locations[ i ][ 2 ];
			data[ d + 3 ] = a.responses == null ? Float.NaN : a.responses[ i ];
			data[ d + 4 ] = a.sigmas == null ? Float.NaN : a.sigmas[ i ];
			data[ d + 5 ] = a.intensities == null ? Float.NaN : a.intensities[ i ];
		}

		return new Points( a.ids, data );
	}

	/**
	 * @return the correspondences of an entry; an empty list if the N5 has the entry's points but no correspondences group
	 *         (labels written by other software); null if the N5 does not have the entry at all
	 */
	public List< CorrespondingInterestPoints > correspondences( final String path )
	{
		final N5Reader n5 = reader();
		final String dataset = InterestPointsN5.corrDataset( path );

		if ( n5 == null )
			return null;

		if ( !n5.exists( dataset ) )
			return n5.exists( InterestPointsN5.ipDataset( path ) ) ? new ArrayList<>() : null;

		return InterestPointsN5.readCorrespondences( n5, dataset );
	}

	/** One N5 entry as the store takes it: the points (the marker of a filtered label) and the correspondences (empty if the N5 has no group of them). */
	public record LegacyEntry( Key key, Points points, List< CorrespondingInterestPoints > correspondences ) {}

	/**
	 * @return the entry as the store takes it, null if the N5 does not have it
	 * @throws RuntimeException if the group is of a type this reader does not know or inconsistent
	 */
	public LegacyEntry read( final Key key )
	{
		final Points points = points( key.path() );

		if ( points == null )
			return null;

		List< CorrespondingInterestPoints > correspondences = correspondences( key.path() );

		if ( correspondences == null )
			correspondences = new ArrayList<>();

		return new LegacyEntry( key, points, correspondences );
	}

	/** Removes the N5 group of an entry, if there is one. */
	public void remove( final String path )
	{
		final N5Reader n5 = reader();

		if ( n5 == null || !n5.exists( path ) )
			return;

		try ( final N5Writer writer = URITools.instantiateN5Writer( StorageFormat.N5, containerURI ) )
		{
			writer.remove( path );
		}
	}

	/** @return the entries ({@code tpId_X_viewSetupId_Y/label}) of the N5 container, sorted; empty if there is none */
	public List< String > entries()
	{
		final N5Reader n5 = reader();
		final List< String > paths = new ArrayList<>();

		if ( n5 == null )
			return paths;

		for ( final String viewGroup : n5.list( "/" ) )
			if ( viewGroup.startsWith( "tpId_" ) && viewGroup.contains( "_viewSetupId_" ) )
				for ( final String label : n5.list( viewGroup ) )
					if ( Key.parse( viewGroup + "/" + label ) != null )
						paths.add( viewGroup + "/" + label );

		Collections.sort( paths );
		return paths;
	}

	/**
	 * The lists of the views (of one label, or of every label if the label is null) whose entry is not in the dataset's
	 * store, i.e. still only in the N5 layout: what "Resave as ZARR store" of the ViewSetup Explorer offers for the
	 * selected views. Empty if the dataset has no N5 container. A key the store lacks counts without looking into the N5
	 * (one stat per key would be too slow for a menu); {@link #moveIntoStore} reports an entry that has no data anywhere.
	 *
	 * @param data - the dataset
	 * @param views - the views (missing ones are skipped)
	 * @param label - the label, null for all labels
	 * @return the lists, sorted by (timepoint, setup, label)
	 */
	public static List< InterestPointsZarr > legacyLists( final SpimData2 data, final Collection< ? extends ViewId > views, final String label )
	{
		final URI baseDir = data.getBasePathURI();

		if ( baseDir == null || !get( baseDir ).exists() )
			return new ArrayList<>();

		final TreeMap< Key, InterestPointsZarr > candidates = new TreeMap<>();

		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( viewId );

			if ( lists == null )
				continue;

			for ( final Map.Entry< String, InterestPoints > entry : lists.getHashMap().entrySet() )
				if ( ( label == null || label.equals( entry.getKey() ) ) && entry.getValue() instanceof InterestPointsZarr )
					candidates.put( ( (InterestPointsZarr) entry.getValue() ).getKey(), (InterestPointsZarr) entry.getValue() );
		}

		final ArrayList< InterestPointsZarr > legacy = new ArrayList<>();

		if ( candidates.isEmpty() )
			return legacy;

		for ( final Key key : InterestPointsZarrStore.get( baseDir ).missingEntries( candidates.keySet() ) )
			legacy.add( candidates.get( key ) );

		return legacy;
	}

	/**
	 * @return the labels of {@link #legacyLists}( data, views, null ) in the order of the label drop-downs, each with the
	 *         number of the given views whose entry of it is still only in the N5; empty if there are none
	 */
	public static LinkedHashMap< String, Integer > legacyLabels( final SpimData2 data, final Collection< ? extends ViewId > views )
	{
		final HashMap< String, Integer > counts = new HashMap<>();

		for ( final InterestPointsZarr list : legacyLists( data, views, null ) )
			counts.merge( list.getKey().label(), 1, Integer::sum );

		final LinkedHashMap< String, Integer > sorted = new LinkedHashMap<>();

		for ( final String label : InterestPointTools.sortLabels( counts.keySet() ) )
			sorted.put( label, counts.get( label ) );

		return sorted;
	}

	/**
	 * Moves the entries of the given lists that are still only in the N5 layout into the store, (label, timepoint) by
	 * (label, timepoint): per group one batch, the N5 entries read in parallel, one commit, so that no more than one
	 * group is held in memory and every group is rewritten once. The N5 is kept: the store wins on every read,
	 * {@link #convert} verifies and deletes it. A list whose entry has no data in the N5 either, or whose N5 group cannot
	 * be read, is logged and skipped (reads keep falling back to the N5, the next run tries again). The lists themselves
	 * are not touched apart from their base path, which becomes the store's; what they hold in memory (loaded or changed
	 * and not saved yet) stays, and a change is written by the next XML save as always. "Resave as ZARR store" of the
	 * ViewSetup Explorer calls this for the selected views.
	 *
	 * Note: a staging file of another JVM (a Spark task) for one of the entries written between the lookup and the
	 * commit loses to the moved N5 data, as every in-memory save does; the explorer does not run next to a Spark job.
	 *
	 * @param lists - the lists; those with an entry in the store are ignored
	 * @param store - the dataset's store
	 * @param numThreads - the parallelism of the N5 reads
	 * @return the number of moved entries
	 * @throws RuntimeException if a commit fails (the batch is closed, what was staged stays staged)
	 */
	public static int moveIntoStore( final Collection< InterestPointsZarr > lists, final InterestPointsZarrStore store, final int numThreads )
	{
		final long startTime = System.currentTimeMillis();

		final HashMap< Key, InterestPointsZarr > byKey = new HashMap<>();
		for ( final InterestPointsZarr list : lists )
			byKey.put( list.getKey(), list );

		final TreeMap< Group, List< InterestPointsZarr > > groups = new TreeMap<>();
		for ( final Key key : store.missingEntries( byKey.keySet() ) )
			groups.computeIfAbsent( key.group(), g -> new ArrayList<>() ).add( byKey.get( key ) );

		if ( groups.isEmpty() )
			return 0;

		final AtomicInteger moved = new AtomicInteger(), skipped = new AtomicInteger();
		final ForkJoinPool pool = new ForkJoinPool( Math.max( 1, numThreads ) );

		try
		{
			for ( final Map.Entry< Group, List< InterestPointsZarr > > group : groups.entrySet() )
			{
				IOFunctions.println( "InterestPointsN5ToZarr: moving " + group.getValue().size() + " entries of '" + group.getKey().label() + "' tp " + group.getKey().tp() + " from " + InterestPointsN5.baseN5 + " into the store" );

				store.beginBatch();

				pool.submit( () -> group.getValue().parallelStream().forEach( list ->
				{
					final Key key = list.getKey();

					try
					{
						final LegacyEntry entry = get( list.getBasePath() ).read( key );

						if ( entry == null )
						{
							IOFunctions.println( "InterestPointsN5ToZarr: WARNING '" + key.path() + "' has no data in " + InterestPointsN5.baseN5 + " either although the XML lists it, skipped" );
							skipped.incrementAndGet();
							return;
						}

						store.savePoints( key, entry.points() );
						store.saveCorrespondences( key, entry.correspondences() );

						// the same field write as saveInterestPoints: the entry now lives in this store
						synchronized ( list ) { list.basePath = store.getBaseDir(); }

						moved.incrementAndGet();
					}
					catch ( final RuntimeException e )
					{
						IOFunctions.println( "InterestPointsN5ToZarr: WARNING cannot read '" + key.path() + "' from " + InterestPointsN5.baseN5 + ", skipped: " + e );
						skipped.incrementAndGet();
					}
				} ) ).get();

				store.commit();
			}
		}
		catch ( final InterruptedException | ExecutionException e )
		{
			throw new RuntimeException( "InterestPointsN5ToZarr: moving entries into the store failed: " + e, e );
		}
		finally
		{
			store.endBatch(); // a failed commit must not leave this JVM in batch mode
			pool.shutdown();
		}

		IOFunctions.println( "InterestPointsN5ToZarr: moved " + moved.get() + " entries (" + groups.size() + " label/timepoint group" + ( groups.size() == 1 ? "" : "s" ) + ") from " + InterestPointsN5.baseN5 + " into " + InterestPointsZarrStore.ZARR_CONTAINER + " in " + ( System.currentTimeMillis() - startTime ) + " ms; the N5 is kept, 'InterestPointsN5ToZarr <dataset.xml>' verifies and deletes it" + ( skipped.get() == 0 ? "" : "; " + skipped.get() + " entries skipped (see the warnings above)" ) );

		return moved.get();
	}

	/**
	 * Moves every entry of the N5 container into the store, then deletes the N5 view groups. An entry the store already
	 * has (saved or moved after the store was created) is left as it is in the store; running the conversion again on a
	 * partially converted dataset is safe. Every converted entry is read back from the store and compared with the N5
	 * before anything is deleted.
	 *
	 * @param baseDir the dataset directory
	 * @return the number of converted entries
	 */
	public static int convert( final URI baseDir )
	{
		final InterestPointsN5ToZarr legacy = get( baseDir );
		final List< String > paths = legacy.entries();

		if ( paths.isEmpty() )
		{
			IOFunctions.println( "InterestPointsN5ToZarr: nothing to convert in '" + legacy.containerURI + "'" );
			return 0;
		}

		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		IOFunctions.println( "InterestPointsN5ToZarr: converting " + paths.size() + " interest point groups of '" + legacy.containerURI + "' into '" + store.getContainerURI() + "'" );

		final List< String > converted = new ArrayList<>();
		final List< Integer > pointCounts = new ArrayList<>();
		final List< Integer > corrCounts = new ArrayList<>();
		final Set< String > viewGroups = new HashSet<>();

		// one lookup for all entries (hasEntry per entry lists the staging directory for every entry the store lacks)
		final List< Key > keys = new ArrayList<>();
		for ( final String path : paths )
			keys.add( Key.parse( path ) );
		final Set< Key > missing = store.missingEntries( keys );
		int existing = 0;

		store.beginBatch();
		try
		{
			for ( int i = 0; i < paths.size(); ++i )
			{
				final String path = paths.get( i );
				final Key key = keys.get( i );
				viewGroups.add( path.substring( 0, path.indexOf( '/' ) ) );

				if ( !missing.contains( key ) )
				{
					++existing;
					continue;
				}

				final LegacyEntry entry = legacy.read( key );
				if ( entry == null )
				{
					IOFunctions.println( "InterestPointsN5ToZarr: WARNING '" + path + "' has no interest point group in the N5, skipped" );
					continue;
				}

				store.savePoints( key, entry.points() );
				store.saveCorrespondences( key, entry.correspondences() );
				converted.add( path );
				pointCounts.add( entry.points().size() );
				corrCounts.add( entry.correspondences().size() );
			}

			store.commit();
		}
		catch ( final RuntimeException e )
		{
			throw new RuntimeException( "InterestPointsN5ToZarr: the conversion of '" + legacy.containerURI + "' failed, the N5 is untouched: " + e, e );
		}
		finally
		{
			store.endBatch(); // a failed conversion must not leave this JVM in batch mode
		}

		if ( existing > 0 )
			IOFunctions.println( "InterestPointsN5ToZarr: " + existing + " entries exist in the store already (saved or moved after the store was created), their N5 version is dropped" );

		// read back before anything is deleted
		for ( int i = 0; i < converted.size(); ++i )
		{
			final Key key = Key.parse( converted.get( i ) );
			final Points points = store.points( key );
			final List< CorrespondingInterestPoints > correspondences = store.correspondences( key );

			if ( points == null || points.size() != pointCounts.get( i ) || correspondences == null || correspondences.size() != corrCounts.get( i ) )
				throw new RuntimeException( "InterestPointsN5ToZarr: '" + converted.get( i ) + "' reads back with " + ( points == null ? "no points" : points.size() + " points" ) + " and " + ( correspondences == null ? "no correspondences" : correspondences.size() + " correspondences" ) + " instead of " + pointCounts.get( i ) + " / " + corrCounts.get( i ) + ", the N5 is untouched, stopping." );
		}

		try ( final N5Writer writer = URITools.instantiateN5Writer( StorageFormat.N5, legacy.containerURI ) )
		{
			for ( final String viewGroup : viewGroups )
				writer.remove( viewGroup );
		}
		synchronized ( legacy )
		{
			legacy.reader = null;
		}

		IOFunctions.println( "InterestPointsN5ToZarr: converted " + converted.size() + " entries, removed " + viewGroups.size() + " N5 view groups" );
		return converted.size();
	}

	/** Converts one dataset: {@code <dataset.xml | dataset directory>}. */
	public static void main( final String[] args )
	{
		if ( args.length != 1 )
		{
			System.out.println( "usage: InterestPointsN5ToZarr <dataset.xml | dataset directory>" );
			System.exit( 1 );
		}

		File directory = new File( args[ 0 ] );
		if ( directory.isFile() )
			directory = directory.getParentFile();

		final long startTime = System.currentTimeMillis();
		final int converted = convert( directory.toURI() );
		System.out.println( "converted " + converted + " entries in " + ( System.currentTimeMillis() - startTime ) + " ms" );
	}
}
