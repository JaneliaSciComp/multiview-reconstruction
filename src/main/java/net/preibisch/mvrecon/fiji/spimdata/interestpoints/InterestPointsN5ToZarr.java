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
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import util.URITools;

/**
 * Everything the Zarr store needs from the legacy per-view format ({@code interestpoints.n5/tpId_X_viewSetupId_Y/label}):
 * reading entries that were not converted yet, removing their groups, and converting a dataset. Delete this class together
 * with {@link InterestPointsN5}; the compiler then points at the calls in {@link InterestPointsZarr}.
 *
 * <pre>java -cp &lt;fat jar&gt; net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr &lt;dataset.xml | dir&gt; [chunkPoints shardPoints]</pre>
 */
public class InterestPointsN5ToZarr
{
	/** Dataset with the intensities that BigStitcher-Spark wrote next to {@code id} and {@code loc}. */
	static final String LEGACY_INTENSITIES = "intensities";

	private static final ConcurrentHashMap< String, InterestPointsN5ToZarr > containers = new ConcurrentHashMap<>();

	/** @return the legacy container of a dataset directory; one per directory, like the store */
	static InterestPointsN5ToZarr get( final URI baseDir )
	{
		return containers.computeIfAbsent( InterestPointsZarrStore.storeKey( baseDir ), key -> new InterestPointsN5ToZarr( baseDir ) );
	}

	final URI containerURI;
	/** Null if the dataset has no legacy container; decided once, because nothing creates one any more. */
	private final N5Reader reader;

	private InterestPointsN5ToZarr( final URI baseDir )
	{
		this.containerURI = URITools.toURI( URITools.appendName( baseDir, InterestPointsN5.baseN5 ) );
		N5Reader legacyReader;
		try
		{
			legacyReader = URITools.instantiateN5Reader( StorageFormat.N5, containerURI );
		}
		catch ( final Exception e )
		{
			legacyReader = null;
		}
		this.reader = legacyReader;
	}

	/** @return a writer on the existing legacy container; callers check {@link #reader} first */
	private N5Writer writer() { return URITools.instantiateN5Writer( StorageFormat.N5, containerURI ); }

	/**
	 * Reads the points of an entry, with its intensities as attribute {@link InterestPointsZarr#INTENSITY}.
	 *
	 * @param path the entry, {@code tpId_X_viewSetupId_Y/label}
	 * @return null if the legacy container does not have the entry
	 */
	InterestPointsZarrStore.Points points( final String path )
	{
		final String dataset = InterestPointsN5.ipDataset( path );
		if ( reader == null || !reader.exists( dataset ) )
			return null;

		final RandomAccessibleInterval< RealType< ? > > locData = open( reader, dataset + "/loc" );
		if ( locData.numDimensions() < 2 || locData.dimension( 0 ) == 0 )
			return new InterestPointsZarrStore.Points( new int[ 0 ], new double[ 0 ] ); // an empty list is stored as [0]

		if ( locData.dimension( 0 ) != 3 )
			throw new IllegalArgumentException( dataset + "/loc has " + locData.dimension( 0 ) + " coordinates per point, expected 3" );

		final double[] loc = flatValues( locData );
		final double[] idValues = flatValues( open( reader, dataset + "/id" ) );
		if ( idValues.length * 3 != loc.length )
			throw new IllegalArgumentException( dataset + ": " + idValues.length + " ids for " + loc.length / 3 + " locations" );

		final int[] ids = new int[ idValues.length ];
		for ( int i = 0; i < ids.length; ++i )
			ids[ i ] = (int) idValues[ i ];

		final TreeMap< String, double[] > attributes = new TreeMap<>();
		final String intensitiesDataset = dataset + "/" + LEGACY_INTENSITIES;
		if ( reader.exists( intensitiesDataset ) )
		{
			final double[] intensities = flatValues( open( reader, intensitiesDataset ) );
			if ( intensities.length == ids.length )
				attributes.put( InterestPointsZarr.INTENSITY, intensities );
			else
				IOFunctions.println( "InterestPointsN5ToZarr: WARNING ignoring " + intensitiesDataset + " (" + intensities.length + " values for " + ids.length + " points)" );
		}

		return new InterestPointsZarrStore.Points( ids, loc, attributes );
	}

	/** @return the correspondences of an entry, or null if the legacy container does not have them */
	List< CorrespondingInterestPoints > correspondences( final String path )
	{
		final String dataset = InterestPointsN5.corrDataset( path );
		if ( reader == null || !reader.exists( dataset ) )
			return null;

		return InterestPointsN5.readCorrespondences( reader, dataset );
	}

	/** Removes the legacy group of an entry, if there is one. */
	void remove( final String path )
	{
		if ( reader != null && reader.exists( path ) )
			writer().remove( path );
	}

	private static RandomAccessibleInterval< RealType< ? > > open( final N5Reader n5, final String dataset )
	{
		return Cast.unchecked( N5Utils.open( n5, dataset ) );
	}

	/** @return all values in flat iteration order (dimension 0 fastest) */
	private static double[] flatValues( final RandomAccessibleInterval< RealType< ? > > image )
	{
		final double[] values = new double[ (int) Intervals.numElements( image ) ];
		int i = 0;
		for ( final RealType< ? > value : Views.flatIterable( image ) )
			values[ i++ ] = value.getRealDouble();

		return values;
	}

	/**
	 * Moves every legacy group of a dataset into the store, then deletes the groups. Running it again on a partially
	 * converted dataset is safe.
	 *
	 * @param baseDir the dataset directory
	 * @return the number of converted entries
	 */
	public static int convert( final URI baseDir )
	{
		final InterestPointsN5ToZarr legacy = get( baseDir );
		final N5Reader reader = legacy.reader;
		if ( reader == null )
			return 0;

		final List< String > paths = new ArrayList<>();
		for ( final String viewGroup : reader.list( "/" ) )
			if ( viewGroup.startsWith( "tpId_" ) && viewGroup.contains( "_viewSetupId_" ) )
				for ( final String label : reader.list( viewGroup ) )
					paths.add( viewGroup + "/" + label );

		if ( paths.isEmpty() )
			return 0;
		Collections.sort( paths );

		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		IOFunctions.println( "InterestPointsN5ToZarr: converting " + paths.size() + " legacy interest point groups of " + legacy.containerURI + " into " + store.containerURI );

		store.beginBatch(); // the saves below stay in memory until the one commit
		InterestPointsZarrStore.parallel( "reading legacy interest points", () -> paths.parallelStream().forEach( path -> {
			final InterestPointsZarrStore.Key key = InterestPointsZarrStore.Key.parse( path );
			if ( key == null )
				return;

			final InterestPointsZarrStore.Points points = legacy.points( path );
			if ( points != null )
				store.savePoints( key, points );

			final List< CorrespondingInterestPoints > correspondences = legacy.correspondences( path );
			if ( correspondences != null )
				store.saveCorrespondences( key, correspondences );
		} ) );
		store.commit();

		final Set< String > viewGroups = new HashSet<>();
		for ( final String path : paths )
			viewGroups.add( path.substring( 0, path.indexOf( '/' ) ) );

		final N5Writer n5Writer = legacy.writer();
		InterestPointsZarrStore.parallel( "removing legacy interest point groups", () -> viewGroups.parallelStream().forEach( n5Writer::remove ) );
		IOFunctions.println( "InterestPointsN5ToZarr: removed " + viewGroups.size() + " legacy view groups" );
		return paths.size();
	}

	/** Converts one dataset: {@code <dataset.xml | dataset directory> [chunkPoints shardPoints]}. */
	public static void main( final String[] args )
	{
		File directory = new File( args[ 0 ] );
		if ( directory.isFile() )
			directory = directory.getParentFile();

		if ( args.length > 2 )
		{
			InterestPointsZarrStore.defaultChunkPoints = Integer.parseInt( args[ 1 ] );
			InterestPointsZarrStore.defaultShardPoints = Integer.parseInt( args[ 2 ] );
		}

		final long startTime = System.currentTimeMillis();
		final int converted = convert( directory.toURI() );
		System.out.println( "converted " + converted + " entries in " + ( System.currentTimeMillis() - startTime ) + " ms" );
	}
}
