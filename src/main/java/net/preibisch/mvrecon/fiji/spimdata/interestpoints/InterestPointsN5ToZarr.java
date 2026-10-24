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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5.PointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;
import util.URITools;

/**
 * Everything the Zarr store needs from the N5 layout ({@code interestpoints.n5/tpId_X_viewSetupId_Y/label}): reading
 * entries that were not converted yet, removing their groups, and converting a whole dataset.
 *
 * <pre>java -cp &lt;fat jar&gt; net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr &lt;dataset.xml | dataset directory&gt;</pre>
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
	 * Moves every entry of the N5 container into the store, then deletes the N5 view groups. An entry the store already
	 * has (saved after the store was created) is left as it is in the store; running the conversion again on a
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

		store.beginBatch();
		try
		{
			for ( final String path : paths )
			{
				final Key key = Key.parse( path );
				viewGroups.add( path.substring( 0, path.indexOf( '/' ) ) );

				if ( store.hasEntry( key ) )
				{
					IOFunctions.println( "InterestPointsN5ToZarr: '" + path + "' exists in the store already (saved after the store was created), the N5 version is dropped" );
					continue;
				}

				final Points points = legacy.points( path );
				if ( points == null )
				{
					IOFunctions.println( "InterestPointsN5ToZarr: WARNING '" + path + "' has no interest point group in the N5, skipped" );
					continue;
				}

				List< CorrespondingInterestPoints > correspondences = legacy.correspondences( path );
				if ( correspondences == null )
					correspondences = new ArrayList<>();

				store.savePoints( key, points );
				store.saveCorrespondences( key, correspondences );
				converted.add( path );
				pointCounts.add( points.size() );
				corrCounts.add( correspondences.size() );
			}

			store.commit();
		}
		catch ( final RuntimeException e )
		{
			throw new RuntimeException( "InterestPointsN5ToZarr: the conversion of '" + legacy.containerURI + "' failed, the N5 is untouched: " + e, e );
		}

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
