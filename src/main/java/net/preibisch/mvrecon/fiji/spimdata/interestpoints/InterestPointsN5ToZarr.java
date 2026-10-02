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
 * Everything the Zarr store needs from the legacy per-view format ({@code interestpoints.n5/tpId_X_viewSetupId_Y/label/...},
 * see {@link InterestPointsN5}): reading entries that were not converted yet ({@link InterestPointsZarr} falls back to it),
 * removing their groups, and the conversion into the store. Delete together with {@link InterestPointsN5}; the compiler
 * then points at the few calls in {@link InterestPointsZarr}.
 *
 * <pre>java -cp &lt;fat jar&gt; net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr &lt;dataset.xml | dir&gt; [chunkPoints shardPoints]</pre>
 */
public class InterestPointsN5ToZarr
{
	/** legacy dataset of the intensities BigStitcher-Spark wrote next to id and loc: .../interestpoints/intensities */
	static final String LEGACY_INTENSITIES = "intensities";

	// one per dataset directory, like the store
	private static final ConcurrentHashMap< String, InterestPointsN5ToZarr > containers = new ConcurrentHashMap<>();

	/** the legacy container of a dataset directory (shared, its reader is opened once) */
	static InterestPointsN5ToZarr get( final URI baseDir )
	{
		return containers.computeIfAbsent( InterestPointsZarrStore.storeKey( baseDir ), k -> new InterestPointsN5ToZarr( baseDir ) );
	}

	final URI n5URI;
	private N5Reader reader = null;
	private N5Writer writer = null;
	private boolean readerTried = false;

	private InterestPointsN5ToZarr( final URI baseDir )
	{
		this.n5URI = URITools.toURI( URITools.appendName( baseDir, InterestPointsN5.baseN5 ) );
	}

	/**
	 * @return the reader, or null if the dataset has no legacy container; "no container" may be cached because nothing
	 * creates one for entries the store can hold any more
	 */
	private synchronized N5Reader reader()
	{
		if ( writer != null ) return writer;
		if ( !readerTried )
		{
			readerTried = true;
			try { reader = URITools.instantiateN5Reader( StorageFormat.N5, n5URI ); }
			catch ( final Exception e ) { reader = null; }
		}
		return reader;
	}

	private synchronized N5Writer writer()
	{
		if ( writer == null )
		{
			writer = URITools.instantiateN5Writer( StorageFormat.N5, n5URI );
			reader = null;
		}
		return writer;
	}

	/**
	 * The points of an entry ({@code <n5dataset>/interestpoints/{id,loc}}) with the intensities next to them as attribute
	 * {@link InterestPointsZarr#INTENSITY}. @return null if the legacy container does not have the entry
	 */
	InterestPointsZarrStore.Points points( final String n5dataset )
	{
		final N5Reader n5 = reader();
		final String dataset = InterestPointsN5.ipDataset( n5dataset );
		if ( n5 == null || !n5.exists( dataset ) ) return null;
		final RandomAccessibleInterval< RealType< ? > > locData = Cast.unchecked( N5Utils.open( n5, dataset + "/loc" ) );
		if ( locData.numDimensions() < 2 || locData.dimension( 0 ) == 0 ) return new InterestPointsZarrStore.Points( new int[ 0 ], new double[ 0 ] ); // empty lists are stored as [0]
		if ( locData.dimension( 0 ) != 3 ) throw new IllegalArgumentException( dataset + "/loc has " + locData.dimension( 0 ) + " coordinates per point, expected 3" );
		final double[] loc = flat( locData );
		final double[] idValues = flat( Cast.unchecked( N5Utils.open( n5, dataset + "/id" ) ) );
		if ( idValues.length * 3 != loc.length ) throw new IllegalArgumentException( dataset + ": " + idValues.length + " ids for " + loc.length / 3 + " locations" );
		final int[] ids = new int[ idValues.length ];
		for ( int i = 0; i < ids.length; ++i ) ids[ i ] = (int) idValues[ i ];
		final TreeMap< String, double[] > attributes = new TreeMap<>();
		final String intensities = dataset + "/" + LEGACY_INTENSITIES;
		if ( n5.exists( intensities ) )
		{
			final double[] v = flat( Cast.unchecked( N5Utils.open( n5, intensities ) ) );
			if ( v.length == ids.length ) attributes.put( InterestPointsZarr.INTENSITY, v );
			else IOFunctions.println( "InterestPointsN5ToZarr: WARNING ignoring " + intensities + " (" + v.length + " values for " + ids.length + " points)" );
		}
		return new InterestPointsZarrStore.Points( ids, loc, attributes );
	}

	/** the correspondences of an entry ({@code <n5dataset>/correspondences}); null if the legacy container does not have them */
	List< CorrespondingInterestPoints > correspondences( final String n5dataset )
	{
		final N5Reader n5 = reader();
		final String dataset = InterestPointsN5.corrDataset( n5dataset );
		return n5 == null || !n5.exists( dataset ) ? null : InterestPointsN5.readCorrespondences( n5, dataset );
	}

	/** removes the legacy group of an entry ({@code tpId_X_viewSetupId_Y/label}), if there is one */
	void remove( final String n5dataset )
	{
		final N5Reader n5 = reader();
		if ( n5 != null && n5.exists( n5dataset ) )
			writer().remove( n5dataset );
	}

	/** all values in flat iteration order (dimension 0 fastest) */
	private static double[] flat( final RandomAccessibleInterval< RealType< ? > > img )
	{
		final double[] out = new double[ (int) Intervals.numElements( img ) ];
		int i = 0;
		for ( final RealType< ? > t : Views.flatIterable( img ) ) out[ i++ ] = t.getRealDouble();
		return out;
	}

	/**
	 * Packs every legacy per-view group ({@code interestpoints.n5/tpId_X_viewSetupId_Y/label}) into the store and deletes
	 * the groups. Safe to run again on a partially converted dataset.
	 *
	 * @param baseDir the dataset directory
	 * @return number of converted entries
	 */
	public static int convert( final URI baseDir )
	{
		final InterestPointsN5ToZarr legacy = get( baseDir );
		final N5Reader n5 = legacy.reader();
		if ( n5 == null ) return 0;
		final List< String > groups = new ArrayList<>();
		for ( final String g : n5.list( "/" ) )
			if ( g.startsWith( "tpId_" ) && g.contains( "_viewSetupId_" ) )
				for ( final String label : n5.list( g ) )
					groups.add( g + "/" + label );
		Collections.sort( groups );
		if ( groups.isEmpty() ) return 0;
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		IOFunctions.println( "InterestPointsN5ToZarr: converting " + groups.size() + " legacy interest point groups of " + legacy.n5URI + " into " + store.n5URI );

		store.beginBatch(); // the saves below stage in memory, one commit for all
		InterestPointsZarrStore.parallel( "reading legacy interest points", () -> groups.parallelStream().forEach( path -> {
				final InterestPointsZarrStore.Key k = InterestPointsZarrStore.Key.parse( path );
				if ( k == null ) return;
				final InterestPointsZarrStore.Points p = legacy.points( path ); // incl. intensities
				if ( p != null ) store.savePoints( k, p );
				final List< CorrespondingInterestPoints > c = legacy.correspondences( path );
				if ( c != null ) store.saveCorrespondences( k, c );
			}) );
		store.commit();

		final N5Writer w = legacy.writer();
		final Set< String > viewGroups = new HashSet<>();
		for ( final String g : groups ) viewGroups.add( g.substring( 0, g.indexOf( '/' ) ) );
		InterestPointsZarrStore.parallel( "removing legacy interest point groups", () -> viewGroups.parallelStream().forEach( g -> w.remove( g ) ) );
		IOFunctions.println( "InterestPointsN5ToZarr: removed " + viewGroups.size() + " legacy view groups" );
		return groups.size();
	}

	/** java ... InterestPointsN5ToZarr &lt;dataset.xml | dataset directory&gt; [chunkPoints shardPoints] */
	public static void main( final String[] args )
	{
		File dir = new File( args[ 0 ] );
		if ( dir.isFile() ) dir = dir.getParentFile();
		if ( args.length > 2 ) { InterestPointsZarrStore.defaultChunkPoints = Integer.parseInt( args[ 1 ] ); InterestPointsZarrStore.defaultShardPoints = Integer.parseInt( args[ 2 ] ); }
		final long t0 = System.currentTimeMillis();
		final int n = convert( dir.toURI() );
		System.out.println( "converted " + n + " entries in " + ( System.currentTimeMillis() - t0 ) + " ms" );
	}
}
