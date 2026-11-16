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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Attributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Rows;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Vectors;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import util.URITools;

/**
 * The per-point arrays of the store ({@link InterestPointArrays}): rows keyed by point id, read and joined to the points
 * of a view by id, checked by count. The arrays are written here as a writer without shared memory writes them (the array,
 * then one staging file per setup) and committed into the array's tables ({@code <label>/tp<T>/arrays/<name>/gK}: one
 * sharded zarr v3 table {@code values} and {@code id} for all setups), with tiny chunks so that a table spans several
 * chunks and shards; the tables per setup that pcmatch writes are read as well.
 */
public class TestInterestPointArrays
{
	@TempDir
	Path tmp;

	static final String LABEL = "beads";
	static final String NAME = InterestPointArrays.EMBEDDING;
	static final int COLUMNS = 5;
	static final String FINGERPRINT = "6844e0e3f80c";

	/** The vector of a point: a deterministic function of its id and the column, so that a mix-up of rows shows. */
	static float value( final int id, final int column ) { return (float) Math.sin( 0.37 * id + column ); }

	/** The store with the points of views 0 and 1 of {@link #LABEL}, as the other tests build it. */
	URI store()
	{
		final URI baseURI = tmp.toUri();
		TestInterestPointsZarr.saveAll( InterestPointsZarrStore.get( baseURI ), baseURI, 2, LABEL );
		return baseURI;
	}

	ViewInterestPointLists lists( final URI baseURI, final int view )
	{
		final ViewInterestPointLists lists = new ViewInterestPointLists( 0, view );
		lists.addInterestPointList( LABEL, TestInterestPointsZarr.list( baseURI, view, LABEL ) );
		return lists;
	}

	/** Writes the rows of {@code points} (in the given order) as the array {@code NAME} of setup {@code view}; {@code nanFirst} writes NaN for the first point. */
	static void writeArray( final URI baseURI, final int view, final List< InterestPoint > points, final boolean nanFirst )
	{
		writeArray( baseURI, LABEL, 0, NAME, view, ids( points ), values( points, nanFirst ), COLUMNS, FINGERPRINT );
	}

	static int[] ids( final List< InterestPoint > points )
	{
		final int[] ids = new int[ points.size() ];
		for ( int r = 0; r < points.size(); ++r )
			ids[ r ] = points.get( r ).getId();
		return ids;
	}

	/** The rows of {@code points} by {@link #value}, the first one NaN if asked. */
	static float[] values( final List< InterestPoint > points, final boolean nanFirst )
	{
		final float[] values = new float[ COLUMNS * points.size() ];
		for ( int r = 0; r < points.size(); ++r )
			for ( int c = 0; c < COLUMNS; ++c )
				values[ COLUMNS * r + c ] = r == 0 && nanFirst ? Float.NaN : value( points.get( r ).getId(), c );
		return values;
	}

	/** The rows of a setup written and committed into the array's tables (public: BigStitcher-Spark's tests plant embeddings with it). */
	public static void writeArray( final URI baseURI, final String label, final int tp, final String name, final int setup,
			final int[] ids, final float[] values, final int columns, final String fingerprint )
	{
		// the production writer (InterestPointArrays.createArray / writeSetup / commit) with small chunks and shards, so that the reads cross them
		InterestPointArrays.createArray( baseURI, label, tp, name, columns, meta( fingerprint ) );
		InterestPointArrays.writeSetup( baseURI, label, tp, name, setup, ids, values, columns );
		commit( InterestPointsZarrStore.get( baseURI ) );
	}

	static JsonObject meta( final String fingerprint )
	{
		final JsonObject model = new JsonObject();
		model.addProperty( "fingerprint", fingerprint );
		model.addProperty( "file", "test.pt" );
		final JsonObject meta = new JsonObject();
		meta.add( "model", model );
		meta.addProperty( "writer", "TestInterestPointArrays" );
		return meta;
	}

	/** The commit of the arrays, a new array with 4 rows per chunk and 12 per shard. */
	static void commit( final InterestPointsZarrStore store )
	{
		final int chunkRows = InterestPointArrays.defaultChunkRows, shardRows = InterestPointArrays.defaultShardRows;
		InterestPointArrays.defaultChunkRows = 4;
		InterestPointArrays.defaultShardRows = 12;

		try
		{
			InterestPointArrays.commit( store );
		}
		finally
		{
			InterestPointArrays.defaultChunkRows = chunkRows;
			InterestPointArrays.defaultShardRows = shardRows;
		}
	}

	/** The rows of a setup as tables of their own, as pcmatch writes them (and this class did until 2026-11-14). */
	static void writeTables( final URI baseURI, final String name, final int setup, final int[] ids, final float[] values, final int columns )
	{
		InterestPointArrays.createArray( baseURI, LABEL, 0, name, columns, meta( FINGERPRINT ) );
		InterestPointArrays.writeSetupTables( baseURI, LABEL, 0, name, setup, ids, values, columns, 4, 12 );
	}

	static File container( final URI baseURI ) { return new File( new File( baseURI ), InterestPointsZarrStore.ZARR_CONTAINER ); }

	static File arrayDirectory( final URI baseURI, final String name )
	{
		return new File( container( baseURI ), InterestPointsZarrStore.Group.encodeLabel( LABEL ) + "/tp0/" + InterestPointArrays.ARRAYS + "/" + name );
	}

	/** @return the generations an array has on disk (directories g<number>), sorted */
	static List< String > generations( final File array )
	{
		final List< String > generations = new ArrayList<>();
		final String[] children = array.list();
		if ( children != null )
			for ( final String child : children )
				if ( child.matches( "g\\d+" ) )
					generations.add( child );
		Collections.sort( generations );
		return generations;
	}

	/** @return the number of staging files of rows written directly and not committed yet */
	static int stagingFiles( final URI baseURI )
	{
		final String[] files = new File( container( baseURI ), "staging" ).list( ( dir, file ) -> file.endsWith( ".rows" ) );
		return files == null ? 0 : files.length;
	}

	@Test
	public void readsRowsAndJoinsById()
	{
		final URI baseURI = store();
		final List< InterestPoint > points = new ArrayList<>( TestInterestPointsZarr.points( 0, LABEL ) );
		Collections.reverse( points ); // rows in another order than the points
		writeArray( baseURI, 0, points, false );

		final Attributes attributes = InterestPointArrays.attributes( baseURI, LABEL, 0, NAME );
		assertNotNull( attributes );
		assertEquals( COLUMNS, attributes.columns() );
		assertEquals( "float32", attributes.dtype() );
		assertEquals( FINGERPRINT, attributes.fingerprint() );
		assertEquals( "TestInterestPointArrays", attributes.meta().get( "writer" ).getAsString() );

		final Rows rows = InterestPointArrays.read( baseURI, LABEL, 0, 0, NAME );
		assertNotNull( rows );
		assertEquals( points.size(), rows.size() );
		assertEquals( points.get( 0 ).getId(), rows.ids()[ 0 ] );

		final ViewInterestPointLists lists = lists( baseURI, 0 );
		final Vectors vectors = InterestPointArrays.vectors( lists, LABEL, NAME );
		assertEquals( points.size(), vectors.size() );
		assertEquals( 0, vectors.missing() );
		assertEquals( COLUMNS, vectors.columns() );

		for ( final InterestPoint point : points )
		{
			final int index = vectors.indexOf( point.getId() );
			assertTrue( index >= 0, "no vector for id " + point.getId() );
			for ( int c = 0; c < COLUMNS; ++c )
				assertEquals( value( point.getId(), c ), vectors.values()[ index * COLUMNS + c ], 0.0f );
		}

		// the other setup has no rows, another name no array
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 1, NAME ) );
		assertNull( InterestPointArrays.attributes( baseURI, LABEL, 0, "other" ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, "other" ) );
		assertNull( InterestPointArrays.read( baseURI, "nolabel", 0, 0, NAME ) );
	}

	@Test
	public void rowsOfNaNAreMissing()
	{
		final URI baseURI = store();
		final List< InterestPoint > points = TestInterestPointsZarr.points( 0, LABEL );
		writeArray( baseURI, 0, points, true ); // the first row is NaN: no vector

		final Vectors vectors = InterestPointArrays.vectors( lists( baseURI, 0 ), LABEL, NAME );
		assertEquals( points.size() - 1, vectors.size() );
		assertEquals( 1, vectors.missing() );
		assertEquals( -1, vectors.indexOf( points.get( 0 ).getId() ) );
		assertTrue( vectors.indexOf( points.get( 1 ).getId() ) >= 0 );
	}

	@Test
	public void anotherNumberOfRowsFailsTheView()
	{
		final URI baseURI = store();
		final List< InterestPoint > points = TestInterestPointsZarr.points( 0, LABEL );
		final List< InterestPoint > half = points.subList( 0, points.size() / 2 );
		writeArray( baseURI, 0, half, false ); // rows for half of the points: the label was detected again since

		final IllegalStateException e = assertThrows( IllegalStateException.class, () -> InterestPointArrays.vectors( lists( baseURI, 0 ), LABEL, NAME ) );
		assertTrue( e.getMessage().contains( "pcmatch embed" ), e.getMessage() );
		assertTrue( e.getMessage().contains( half.size() + " rows" ), e.getMessage() );
		assertTrue( e.getMessage().contains( points.size() + " points" ), e.getMessage() );
	}

	@Test
	public void otherIdsFailTheView()
	{
		final URI baseURI = store();
		final List< InterestPoint > points = TestInterestPointsZarr.points( 0, LABEL );
		final int[] ids = ids( points );
		int unused = 0;
		for ( final int id : ids )
			unused = Math.max( unused, id + 1 );
		ids[ ids.length - 1 ] = unused; // as many rows as points, but one row names a point the label does not have
		writeArray( baseURI, LABEL, 0, NAME, 0, ids, values( points, false ), COLUMNS, FINGERPRINT );

		final IllegalStateException e = assertThrows( IllegalStateException.class, () -> InterestPointArrays.vectors( lists( baseURI, 0 ), LABEL, NAME ) );
		assertTrue( e.getMessage().contains( "pcmatch embed" ), e.getMessage() );
		assertTrue( e.getMessage().contains( "1 of " + points.size() ), e.getMessage() );
		assertTrue( e.getMessage().contains( "[" + points.get( points.size() - 1 ).getId() + "]" ), e.getMessage() );
	}

	@Test
	public void namesOfTheLabels()
	{
		final URI baseURI = store();
		final ViewInterestPoints vip = new ViewInterestPoints( List.of( lists( baseURI, 0 ), lists( baseURI, 1 ) ) );
		final Map< ViewId, HashMap< String, Double > > labelMap = new HashMap<>();
		for ( int view = 0; view < 2; ++view )
			labelMap.put( new ViewId( 0, view ), new HashMap<>( Map.of( LABEL, 1.0 ) ) );

		assertTrue( InterestPointArrays.names( vip, labelMap ).isEmpty() );

		writeArray( baseURI, 0, TestInterestPointsZarr.points( 0, LABEL ), false );
		assertEquals( List.of( NAME ), new ArrayList<>( InterestPointArrays.names( vip, labelMap ) ) );

		writeArray( baseURI, LABEL, 0, "another", 1, ids( TestInterestPointsZarr.points( 1, LABEL ) ), new float[ 2 * TestInterestPointsZarr.points( 1, LABEL ).size() ], 2, FINGERPRINT );
		assertEquals( List.of( "another", NAME ), new ArrayList<>( InterestPointArrays.names( vip, labelMap ) ) );

		// a filtered label has its base label's arrays
		final String filtered = LABEL + "_r0.05";
		for ( final ViewId v : labelMap.keySet() )
			vip.getViewInterestPointLists( v ).addInterestPointList( filtered, VirtualFilteredInterestPoints.create( baseURI, v, filtered, LABEL, InterestPointFilter.fromString( "response=0.05.." ) ) );
		labelMap.values().forEach( m -> { m.clear(); m.put( filtered, 1.0 ); } );
		assertEquals( List.of( "another", NAME ), new ArrayList<>( InterestPointArrays.names( vip, labelMap ) ) );
	}

	@Test
	public void noArrayFailsLoudly()
	{
		final URI baseURI = store();
		final IllegalStateException e = assertThrows( IllegalStateException.class, () -> InterestPointArrays.vectors( lists( baseURI, 0 ), LABEL, NAME ) );
		assertTrue( e.getMessage().contains( "No stored array '" + NAME + "'" ), e.getMessage() );
		assertTrue( e.getMessage().contains( "setupId=0" ), e.getMessage() );
		assertTrue( e.getMessage().contains( "pcmatch embed" ), e.getMessage() );

		// an array without rows for the view fails the same way
		writeArray( baseURI, 1, TestInterestPointsZarr.points( 1, LABEL ), false );
		assertThrows( IllegalStateException.class, () -> InterestPointArrays.vectors( lists( baseURI, 0 ), LABEL, NAME ) );
		assertEquals( TestInterestPointsZarr.points( 1, LABEL ).size(), InterestPointArrays.vectors( lists( baseURI, 1 ), LABEL, NAME ).size() );
	}

	@Test
	public void filteredLabelReadsTheBase()
	{
		final URI baseURI = store();
		final List< InterestPoint > points = TestInterestPointsZarr.points( 0, LABEL );
		writeArray( baseURI, 0, points, false ); // the rows of the base label, which the filtered label reads

		final InterestPointFilter filter = InterestPointFilter.fromString( "response=0.05.." );
		final ViewInterestPointLists lists = lists( baseURI, 0 );
		final VirtualFilteredInterestPoints strong = VirtualFilteredInterestPoints.create( baseURI, new ViewId( 0, 0 ), LABEL + "_r0.05", LABEL, filter );
		lists.addInterestPointList( strong.getXMLRepresentation().substring( strong.getXMLRepresentation().lastIndexOf( '/' ) + 1 ), strong );
		final String filtered = LABEL + "_r0.05";
		assertNotNull( lists.getInterestPointList( filtered ) );

		assertEquals( LABEL, InterestPointArrays.baseLabel( lists, filtered ) );
		assertEquals( LABEL, InterestPointArrays.baseLabel( lists, LABEL ) );

		final Map< Integer, InterestPoint > kept = strong.getInterestPointsCopy();
		assertTrue( kept.size() > 0 && kept.size() < points.size() );

		final Vectors vectors = InterestPointArrays.vectors( lists, filtered, NAME );
		assertEquals( kept.size(), vectors.size() );
		assertEquals( 0, vectors.missing() );
		for ( final InterestPoint point : kept.values() )
			assertEquals( value( point.getId(), 1 ), vectors.values()[ vectors.indexOf( point.getId() ) * COLUMNS + 1 ], 0.0f );

		assertThrows( IllegalStateException.class, () -> InterestPointArrays.baseLabel( lists, "nolabel" ) );
	}

	@Test
	public void damagedArraysFail()
	{
		final URI baseURI = store();
		final List< InterestPoint > points = TestInterestPointsZarr.points( 0, LABEL );
		final int n = points.size();

		// the same id twice
		final int[] ids = ids( points );
		final float[] values = new float[ COLUMNS * n ];
		ids[ n - 1 ] = ids[ 0 ];
		writeArray( baseURI, LABEL, 0, NAME, 0, ids, values, COLUMNS, FINGERPRINT );
		final Rows rows = InterestPointArrays.read( baseURI, LABEL, 0, 0, NAME );
		assertTrue( assertThrows( IllegalStateException.class, () -> InterestPointArrays.join( rows, points, n ) ).getMessage().contains( "twice" ) );

		// tables of different lengths (the writers refuse to produce them, so the values table of a setup is replaced by a shorter one)
		assertThrows( IllegalArgumentException.class, () -> writeArray( baseURI, LABEL, 0, "short", 0, ids, new float[ COLUMNS * ( n - 1 ) ], COLUMNS, FINGERPRINT ) );
		assertThrows( IllegalArgumentException.class, () -> writeTables( baseURI, "short", 0, ids, new float[ COLUMNS * ( n - 1 ) ], COLUMNS ) );
		writeTables( baseURI, "short", 0, ids, new float[ COLUMNS * n ], COLUMNS );
		assertEquals( n, InterestPointArrays.read( baseURI, LABEL, 0, 0, "short" ).size() );
		try ( final N5Writer zarr = URITools.instantiateN5Writer( StorageFormat.ZARR, new File( new File( baseURI ), InterestPointsZarrStore.ZARR_CONTAINER ).toURI() ) )
		{
			final String shortValues = InterestPointsZarrStore.Group.encodeLabel( LABEL ) + "/tp0/" + InterestPointArrays.ARRAYS + "/short/" + InterestPointArrays.SETUP_PREFIX + "0/" + InterestPointArrays.VALUES;
			zarr.remove( shortValues );
			InterestPointArrays.writeTable( zarr, shortValues, COLUMNS, n - 1, DataType.FLOAT32, new float[ COLUMNS * ( n - 1 ) ], 4, 12 );
		}
		assertThrows( IllegalStateException.class, () -> InterestPointArrays.read( baseURI, LABEL, 0, 0, "short" ) );

		// a width that does not match the attribute
		writeArray( baseURI, LABEL, 0, "narrow", 0, ids, new float[ 2 * n ], 2, FINGERPRINT );
		try ( final N5Writer zarr = URITools.instantiateN5Writer( StorageFormat.ZARR, new File( new File( baseURI ), InterestPointsZarrStore.ZARR_CONTAINER ).toURI() ) )
		{
			zarr.setAttribute( InterestPointsZarrStore.Group.encodeLabel( LABEL ) + "/tp0/" + InterestPointArrays.ARRAYS + "/narrow", "columns", 3 );
		}
		assertThrows( IllegalStateException.class, () -> InterestPointArrays.read( baseURI, LABEL, 0, 0, "narrow" ) );

		// the points themselves are untouched by all of this
		assertEquals( n, new InterestPointsZarr( baseURI, new ViewId( 0, 0 ), LABEL ).getInterestPointsCopy().size() );
	}

	/** Checks that the vectors of a view are those of its points by {@link #value}, {@code missing} of them without a vector. */
	void checkVectors( final URI baseURI, final int view, final int missing )
	{
		final List< InterestPoint > points = TestInterestPointsZarr.points( view, LABEL );
		final Vectors vectors = InterestPointArrays.vectors( lists( baseURI, view ), LABEL, NAME );

		assertEquals( points.size() - missing, vectors.size() );
		assertEquals( missing, vectors.missing() );

		for ( final InterestPoint point : points )
		{
			final int index = vectors.indexOf( point.getId() );
			if ( index >= 0 )
				for ( int c = 0; c < COLUMNS; ++c )
					assertEquals( value( point.getId(), c ), vectors.values()[ index * COLUMNS + c ], 0.0f );
		}
	}

	/**
	 * A writer without shared memory (a Spark task) writes a setup's rows as one staging file: every reader has them at
	 * once, the commit moves them into the one table the array has for all views, and a setup written again replaces its
	 * rows there while the other views keep theirs.
	 */
	@Test
	public void rowsWrittenDirectlyAreCommitted()
	{
		final URI baseURI = store();
		final List< InterestPoint > p0 = TestInterestPointsZarr.points( 0, LABEL ), p1 = TestInterestPointsZarr.points( 1, LABEL );
		final File array = arrayDirectory( baseURI, NAME );

		InterestPointArrays.createArray( baseURI, LABEL, 0, NAME, COLUMNS, meta( FINGERPRINT ) );
		final Attributes attributes = InterestPointArrays.attributes( baseURI, LABEL, 0, NAME );
		assertTrue( InterestPointArrays.rows( baseURI, attributes ).isEmpty() );

		// rows of another width or for an array that does not exist are refused
		assertThrows( IllegalStateException.class, () -> InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 0, ids( p0 ), new float[ 2 * p0.size() ], 2 ) );
		assertThrows( IllegalStateException.class, () -> InterestPointArrays.writeSetup( baseURI, LABEL, 0, "nothere", 0, ids( p0 ), values( p0, false ), COLUMNS ) );
		assertEquals( 0, stagingFiles( baseURI ) );

		InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 0, ids( p0 ), values( p0, false ), COLUMNS );
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 1, ids( p1 ), values( p1, false ), COLUMNS );

		// two staging files, nothing in the array yet, and the rows are there for every reader
		assertEquals( 2, stagingFiles( baseURI ) );
		assertTrue( generations( array ).isEmpty() );
		assertEquals( Map.of( 0, (long) p0.size(), 1, (long) p1.size() ), InterestPointArrays.rows( baseURI, attributes ) );
		assertEquals( Map.of( 0, (long) p0.size(), 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ) );
		checkVectors( baseURI, 0, 0 );
		checkVectors( baseURI, 1, 0 );

		commit( InterestPointsZarrStore.get( baseURI ) );

		// one table for both views in the first generation, in chunks of 4 rows; no staging file, no tables per setup
		assertEquals( 0, stagingFiles( baseURI ) );
		assertEquals( 1, generations( array ).size() );
		final String first = generations( array ).get( 0 );
		assertFalse( new File( array, InterestPointArrays.SETUP_PREFIX + 0 ).exists() );
		try ( final N5Reader zarr = URITools.instantiateN5Reader( StorageFormat.ZARR, container( baseURI ).toURI() ) )
		{
			final String values = InterestPointsZarrStore.Group.encodeLabel( LABEL ) + "/tp0/" + InterestPointArrays.ARRAYS + "/" + NAME + "/" + first + "/" + InterestPointArrays.VALUES;
			assertArrayEquals( new long[] { COLUMNS, p0.size() + p1.size() }, zarr.getDatasetAttributes( values ).getDimensions() );
			assertEquals( 4, zarr.getDatasetAttributes( values ).getChunkSize()[ 1 ] );
			assertEquals( 12, zarr.getDatasetAttributes( values ).getBlockSize()[ 1 ] );
		}
		assertEquals( Map.of( 0, (long) p0.size(), 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ) );
		checkVectors( baseURI, 0, 0 );
		checkVectors( baseURI, 1, 0 );

		// a commit without changes writes nothing
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertEquals( List.of( first ), generations( array ) );

		// the first view written again (its first row NaN now): its rows are replaced at once, and in the table with the commit
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 0, ids( p0 ), values( p0, true ), COLUMNS );
		checkVectors( baseURI, 0, 1 );
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertEquals( 0, stagingFiles( baseURI ) );
		assertEquals( 1, generations( array ).size() ); // the old generation is gone
		assertNotEquals( first, generations( array ).get( 0 ) );
		checkVectors( baseURI, 0, 1 );
		checkVectors( baseURI, 1, 0 );

		// another process commits (a store object of its own) rows this JVM still lists as a staging file: they are read from the table
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 1, ids( p1 ), values( p1, true ), COLUMNS );
		commit( new InterestPointsZarrStore( baseURI ) );
		assertEquals( 0, stagingFiles( baseURI ) );
		checkVectors( baseURI, 0, 1 );
		checkVectors( baseURI, 1, 1 );

		// removing the array takes everything, also rows that are not committed
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 0, ids( p0 ), values( p0, false ), COLUMNS );
		assertEquals( 1, stagingFiles( baseURI ) );
		assertTrue( InterestPointArrays.remove( baseURI, LABEL, 0, NAME ) );
		assertEquals( 0, stagingFiles( baseURI ) );
		assertFalse( array.exists() );
		assertNull( InterestPointArrays.attributes( baseURI, LABEL, 0, NAME ) );
		assertFalse( InterestPointArrays.remove( baseURI, LABEL, 0, NAME ) );
	}

	/** The rows of a setup with {@code count} rows: ids 0..count-1, the values a function of setup, id, column and {@code version}. */
	static float[] rowsOf( final int setup, final int count, final int version )
	{
		final float[] values = new float[ COLUMNS * count ];
		for ( int r = 0; r < count; ++r )
			for ( int c = 0; c < COLUMNS; ++c )
				values[ COLUMNS * r + c ] = value( 1000 * setup + r + 100000 * version, c );
		return values;
	}

	static int[] idsOf( final int count )
	{
		final int[] ids = new int[ count ];
		for ( int r = 0; r < count; ++r )
			ids[ r ] = r;
		return ids;
	}

	/**
	 * A commit copies the rows it keeps from the old generation into the new one (in ranges that span setups and pieces of
	 * several chunks): every view has its own rows afterwards, whichever views around it were written again, removed or empty.
	 */
	@Test
	public void keptRowsAreCopied()
	{
		final URI baseURI = store();
		final int[] counts = { 0, 7, 33, 5, 40, 1, 0, 61, 3 }; // 4 rows per chunk, 12 per shard, kept rows copied in pieces of 32
		final int[] versions = new int[ counts.length ];

		InterestPointArrays.createArray( baseURI, LABEL, 0, NAME, COLUMNS, meta( FINGERPRINT ) );
		for ( int setup = 0; setup < counts.length; ++setup )
			InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, setup, idsOf( counts[ setup ] ), rowsOf( setup, counts[ setup ], 0 ), COLUMNS );
		commit( InterestPointsZarrStore.get( baseURI ) );

		final Runnable check = () ->
		{
			final Map< Integer, Long > stored = InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME );
			for ( int setup = 0; setup < counts.length; ++setup )
			{
				final Rows rows = InterestPointArrays.read( baseURI, LABEL, 0, setup, NAME );
				if ( counts[ setup ] < 0 )
				{
					assertNull( rows, "setup " + setup );
					assertFalse( stored.containsKey( setup ) );
					continue;
				}
				assertEquals( counts[ setup ], rows.size(), "setup " + setup );
				assertEquals( (long) counts[ setup ], stored.get( setup ) );
				assertArrayEquals( idsOf( counts[ setup ] ), rows.ids(), "setup " + setup );
				assertArrayEquals( rowsOf( setup, counts[ setup ], versions[ setup ] ), rows.values(), "setup " + setup );
			}
		};
		check.run();

		// a view in the middle written again with another number of rows, in memory
		counts[ 2 ] = 9;
		versions[ 2 ] = 1;
		InterestPointArrays.stageSetup( baseURI, LABEL, 0, NAME, 2, idsOf( 9 ), rowsOf( 2, 9, 1 ), COLUMNS );
		commit( InterestPointsZarrStore.get( baseURI ) );
		check.run();

		// the points of two views replaced, one of them embedded again; a view added behind the last one
		InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, List.of( 4, 7 ) );
		counts[ 4 ] = -1;
		counts[ 7 ] = 2;
		versions[ 7 ] = 2;
		InterestPointArrays.stageSetup( baseURI, LABEL, 0, NAME, 7, idsOf( 2 ), rowsOf( 7, 2, 2 ), COLUMNS );
		commit( InterestPointsZarrStore.get( baseURI ) );
		check.run();
		assertEquals( 1, generations( arrayDirectory( baseURI, NAME ) ).size() );
	}

	/**
	 * Tables per setup (pcmatch's layout, and this class's until 2026-11-14) are a setup's rows where they exist; a save
	 * leaves them where they are, and they go when the setup's rows are written anew.
	 */
	@Test
	public void tablesPerSetupAreReadAndReplaced()
	{
		final URI baseURI = store();
		final List< InterestPoint > p0 = TestInterestPointsZarr.points( 0, LABEL ), p1 = TestInterestPointsZarr.points( 1, LABEL );
		final File array = arrayDirectory( baseURI, NAME );
		final File tables = new File( array, InterestPointArrays.SETUP_PREFIX + 0 );

		writeTables( baseURI, NAME, 0, ids( p0 ), values( p0, false ), COLUMNS );
		assertTrue( tables.isDirectory() );
		assertEquals( Map.of( 0, (long) p0.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ) );
		checkVectors( baseURI, 0, 0 );

		// another view written and committed: the array has both, the tables stay where they are
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 1, ids( p1 ), values( p1, false ), COLUMNS );
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertTrue( tables.isDirectory() );
		assertEquals( 1, generations( array ).size() );
		assertEquals( Map.of( 0, (long) p0.size(), 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ) );
		checkVectors( baseURI, 0, 0 );
		checkVectors( baseURI, 1, 0 );

		// the rows of the setup written anew: its tables go with the commit
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, NAME, 0, ids( p0 ), values( p0, true ), COLUMNS );
		checkVectors( baseURI, 0, 1 );
		assertTrue( tables.isDirectory() );
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertFalse( tables.exists() );
		checkVectors( baseURI, 0, 1 );
		checkVectors( baseURI, 1, 0 );

		// tables another writer writes after that are the setup's rows again
		writeTables( baseURI, NAME, 0, ids( p0 ), values( p0, false ), COLUMNS );
		checkVectors( baseURI, 0, 0 );
		checkVectors( baseURI, 1, 0 );
	}

	/**
	 * The points of a view were replaced: its rows are gone for the readers at once and from every array of the label with
	 * the commit (the array's table, tables per setup, rows not committed yet); an array left without rows is removed.
	 */
	@Test
	public void rowsOfReplacedPointsGoWithTheCommit()
	{
		final URI baseURI = store();
		final List< InterestPoint > p0 = TestInterestPointsZarr.points( 0, LABEL ), p1 = TestInterestPointsZarr.points( 1, LABEL );

		writeArray( baseURI, 0, p0, false );
		writeArray( baseURI, 1, p1, false );
		writeArray( baseURI, LABEL, 0, "another", 0, ids( p0 ), new float[ 2 * p0.size() ], 2, FINGERPRINT ); // only the first view
		writeTables( baseURI, "tables", 0, ids( p0 ), values( p0, false ), COLUMNS );
		writeTables( baseURI, "tables", 1, ids( p1 ), values( p1, false ), COLUMNS );
		InterestPointArrays.createArray( baseURI, LABEL, 0, "pending", COLUMNS, meta( FINGERPRINT ) );
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, "pending", 0, ids( p0 ), values( p0, false ), COLUMNS );
		assertEquals( 4, InterestPointArrays.list( baseURI, LABEL, 0 ).size() );

		InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, List.of( 0 ) );

		// gone for the readers, still in the store
		assertTrue( InterestPointArrays.hasStaged( baseURI ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, NAME ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, "tables" ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, "pending" ) );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rows( baseURI, InterestPointArrays.attributes( baseURI, LABEL, 0, NAME ) ) );
		assertEquals( 2, InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ).size() );
		assertEquals( 1, stagingFiles( baseURI ) );

		commit( InterestPointsZarrStore.get( baseURI ) );

		assertFalse( InterestPointArrays.hasStaged( baseURI ) );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ) );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, "tables" ) );
		assertFalse( new File( arrayDirectory( baseURI, "tables" ), InterestPointArrays.SETUP_PREFIX + 0 ).exists() );
		assertTrue( new File( arrayDirectory( baseURI, "tables" ), InterestPointArrays.SETUP_PREFIX + 1 ).isDirectory() );
		assertFalse( arrayDirectory( baseURI, "another" ).exists() ); // its only rows went
		assertFalse( arrayDirectory( baseURI, "pending" ).exists() );
		assertEquals( 0, stagingFiles( baseURI ) );
		checkVectors( baseURI, 1, 0 );
		assertEquals( 2, InterestPointArrays.list( baseURI, LABEL, 0 ).size() );

		// rows computed for the new points after that are kept (in memory until the commit)
		InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, List.of( 0, 1 ) );
		InterestPointArrays.stageSetup( baseURI, LABEL, 0, NAME, 1, ids( p1 ), values( p1, true ), COLUMNS );
		checkVectors( baseURI, 1, 1 );
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ) );
		checkVectors( baseURI, 1, 1 );
		assertFalse( arrayDirectory( baseURI, "tables" ).exists() );
		assertEquals( 1, InterestPointArrays.list( baseURI, LABEL, 0 ).size() );
	}

	/**
	 * The rows of some views removed from ONE array (the Interest Point Explorer's "Remove embeddings"): gone for the
	 * readers at once, for that array only, and from the store with the commit (the array's table, tables per setup, rows
	 * not committed yet); an array left without rows is removed; rows computed after the removal are kept; rows that were
	 * only held in memory go without the store being touched.
	 */
	@Test
	public void rowsOfOneArrayAreRemovedWithTheCommit()
	{
		final URI baseURI = store();
		final List< InterestPoint > p0 = TestInterestPointsZarr.points( 0, LABEL ), p1 = TestInterestPointsZarr.points( 1, LABEL );

		writeArray( baseURI, 0, p0, false );
		writeArray( baseURI, 1, p1, false );
		writeArray( baseURI, LABEL, 0, "another", 0, ids( p0 ), new float[ 2 * p0.size() ], 2, FINGERPRINT );
		writeArray( baseURI, LABEL, 0, "another", 1, ids( p1 ), new float[ 2 * p1.size() ], 2, FINGERPRINT );
		writeTables( baseURI, "tables", 0, ids( p0 ), values( p0, false ), COLUMNS );
		writeTables( baseURI, "tables", 1, ids( p1 ), values( p1, false ), COLUMNS );
		InterestPointArrays.createArray( baseURI, LABEL, 0, "pending", COLUMNS, meta( FINGERPRINT ) );
		InterestPointArrays.writeSetup( baseURI, LABEL, 0, "pending", 0, ids( p0 ), values( p0, false ), COLUMNS );

		// nothing to remove: no such array, no rows for the view
		assertTrue( InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, "nothing", List.of( 0 ) ).isEmpty() );
		assertTrue( InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, NAME, List.of( 5 ) ).isEmpty() );
		assertFalse( InterestPointArrays.hasStaged( baseURI ) );

		assertEquals( Set.of( 0 ), InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, NAME, List.of( 0, 5 ) ) );
		assertEquals( Set.of( 0 ), InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, "tables", List.of( 0 ) ) );
		assertEquals( Set.of( 0 ), InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, "pending", List.of( 0 ) ) );

		// gone for the readers, in these arrays only; the store is as it was
		assertTrue( InterestPointArrays.hasStaged( baseURI ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, NAME ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, "tables" ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, "pending" ) );
		assertEquals( p1.size(), InterestPointArrays.read( baseURI, LABEL, 0, 1, NAME ).size() );
		assertEquals( p1.size(), InterestPointArrays.read( baseURI, LABEL, 0, 1, "tables" ).size() );
		assertEquals( p0.size(), InterestPointArrays.read( baseURI, LABEL, 0, 0, "another" ).size() );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rows( baseURI, InterestPointArrays.attributes( baseURI, LABEL, 0, NAME ) ) );
		assertTrue( InterestPointArrays.rows( baseURI, InterestPointArrays.attributes( baseURI, LABEL, 0, "pending" ) ).isEmpty() );
		assertEquals( 2, InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ).size() );
		assertEquals( 2, InterestPointArrays.rowsInStore( baseURI, LABEL, 0, "tables" ).size() );
		assertEquals( 1, stagingFiles( baseURI ) );

		commit( InterestPointsZarrStore.get( baseURI ) );

		assertFalse( InterestPointArrays.hasStaged( baseURI ) );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, NAME ) );
		assertEquals( Map.of( 0, (long) p0.size(), 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, "another" ) );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, "tables" ) );
		assertFalse( new File( arrayDirectory( baseURI, "tables" ), InterestPointArrays.SETUP_PREFIX + 0 ).exists() );
		assertTrue( new File( arrayDirectory( baseURI, "tables" ), InterestPointArrays.SETUP_PREFIX + 1 ).isDirectory() );
		assertFalse( arrayDirectory( baseURI, "pending" ).exists() ); // its only rows went
		assertEquals( 0, stagingFiles( baseURI ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, NAME ) );
		checkVectors( baseURI, 1, 0 );
		assertEquals( 3, InterestPointArrays.list( baseURI, LABEL, 0 ).size() );

		// the last view with rows: the array is still there until the commit, which removes it
		assertEquals( Set.of( 1 ), InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, NAME, List.of( 0, 1 ) ) );
		assertTrue( InterestPointArrays.rows( baseURI, InterestPointArrays.attributes( baseURI, LABEL, 0, NAME ) ).isEmpty() );
		assertTrue( arrayDirectory( baseURI, NAME ).isDirectory() );
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertFalse( arrayDirectory( baseURI, NAME ).exists() );
		assertEquals( 2, InterestPointArrays.list( baseURI, LABEL, 0 ).size() );

		// rows computed after a removal are the rows of the view
		final float[] again = new float[ 2 * p1.size() ];
		Arrays.fill( again, 3f );
		assertEquals( Set.of( 0, 1 ), InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, "another", List.of( 0, 1 ) ) );
		InterestPointArrays.stageSetup( baseURI, LABEL, 0, "another", 1, ids( p1 ), again, 2 );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, "another" ) );
		assertArrayEquals( again, InterestPointArrays.read( baseURI, LABEL, 0, 1, "another" ).values() );
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertEquals( Map.of( 1, (long) p1.size() ), InterestPointArrays.rowsInStore( baseURI, LABEL, 0, "another" ) );
		assertArrayEquals( again, InterestPointArrays.read( baseURI, LABEL, 0, 1, "another" ).values() );

		// rows that were only held in memory: nothing of them reaches the store
		InterestPointArrays.stageArray( baseURI, LABEL, 0, "memory", COLUMNS, meta( FINGERPRINT ), false );
		InterestPointArrays.stageSetup( baseURI, LABEL, 0, "memory", 0, ids( p0 ), values( p0, false ), COLUMNS );
		assertEquals( Set.of( 0 ), InterestPointArrays.stageRemoveRows( baseURI, LABEL, 0, "memory", List.of( 0, 1 ) ) );
		assertNull( InterestPointArrays.read( baseURI, LABEL, 0, 0, "memory" ) );
		commit( InterestPointsZarrStore.get( baseURI ) );
		assertFalse( arrayDirectory( baseURI, "memory" ).exists() );
		assertEquals( 2, InterestPointArrays.list( baseURI, LABEL, 0 ).size() );
	}
}
