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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.FloatArrayDataBlock;
import org.janelia.saalfeldlab.n5.IntArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.codec.checksum.Crc32cChecksumCodec;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes;
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
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import util.URITools;

/**
 * The per-point arrays of the store ({@link InterestPointArrays}): another writer's rows keyed by point id, read
 * and joined to the points of a view by id, checked by count. The arrays are written here as pcmatch writes them (sharded
 * zarr v3 tables {@code id} and {@code values} per setup under {@code <label>/tp<T>/arrays/<name>}), with tiny chunks so
 * that a table spans several chunks and shards.
 */
public class TestInterestPointArrays
{
	@TempDir
	Path tmp;

	static final String LABEL = "beads";
	static final String NAME = "descriptor";
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

	/** An array in the layout of pcmatch's {@code write_point_array} (public: BigStitcher-Spark's tests plant descriptors with it). */
	public static void writeArray( final URI baseURI, final String label, final int tp, final String name, final int setup,
			final int[] ids, final float[] values, final int columns, final String fingerprint )
	{
		final URI container = new File( new File( baseURI ), InterestPointsZarrStore.ZARR_CONTAINER ).toURI();

		try ( final N5Writer zarr = URITools.instantiateN5Writer( StorageFormat.ZARR, container ) )
		{
			final String path = InterestPointsZarrStore.Group.encodeLabel( label ) + "/tp" + tp + "/" + InterestPointArrays.ARRAYS + "/" + name;
			zarr.createGroup( path );
			zarr.setAttribute( path, "columns", columns );
			zarr.setAttribute( path, "dtype", "float32" );

			final JsonObject model = new JsonObject();
			model.addProperty( "fingerprint", fingerprint );
			model.addProperty( "file", "test.pt" );
			final JsonObject meta = new JsonObject();
			meta.add( "model", model );
			meta.addProperty( "writer", "TestInterestPointArrays" );
			zarr.setAttribute( path, "meta", meta );

			final String setupPath = path + "/" + InterestPointArrays.SETUP_PREFIX + setup;
			zarr.createGroup( setupPath );
			zarr.setAttribute( setupPath, "setup", setup );
			zarr.setAttribute( setupPath, "rows", ids.length );

			writeTable( zarr, setupPath + "/" + InterestPointArrays.ID, 1, ids.length, DataType.INT32, ids );
			writeTable( zarr, setupPath + "/" + InterestPointArrays.VALUES, columns, values.length / columns, DataType.FLOAT32, values );
		}
	}

	/** A sharded [columns, rows] table in the store's codecs with 4 rows per chunk and 3 chunks per shard. */
	public static void writeTable( final N5Writer zarr, final String dataset, final int columns, final int rows, final DataType type, final Object data )
	{
		final int chunkRows = 4, shardRows = 12;
		final DatasetAttributes attributes = ZarrV3DatasetAttributes.builder( new long[] { columns, rows }, type )
				.blockSize( new int[] { columns, shardRows } )
				.chunkSize( new int[] { columns, chunkRows } )
				.compression( new RawCompression() )
				.dataCodecInfos( new Crc32cChecksumCodec() )
				.shardIndexDataCodecInfos( new Crc32cChecksumCodec() )
				.build();

		zarr.createDataset( dataset, attributes );

		final List< DataBlock< ? > > shard = new ArrayList<>();
		for ( int start = 0; start < rows; start += chunkRows )
		{
			final int count = Math.min( chunkRows, rows - start );
			final int[] size = { columns, count };
			final long[] position = { 0, start / chunkRows };
			if ( type == DataType.INT32 )
			{
				final int[] chunk = new int[ columns * count ];
				System.arraycopy( data, start * columns, chunk, 0, chunk.length );
				shard.add( new IntArrayDataBlock( size, position, chunk ) );
			}
			else
			{
				final float[] chunk = new float[ columns * count ];
				System.arraycopy( data, start * columns, chunk, 0, chunk.length );
				shard.add( new FloatArrayDataBlock( size, position, chunk ) );
			}

			if ( ( start / chunkRows + 1 ) % ( shardRows / chunkRows ) == 0 || start + count == rows )
			{
				zarr.writeChunks( dataset, attributes, shard.toArray( new DataBlock[ 0 ] ) );
				shard.clear();
			}
		}
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

		// tables of different lengths
		writeArray( baseURI, LABEL, 0, "short", 0, ids, new float[ COLUMNS * ( n - 1 ) ], COLUMNS, FINGERPRINT );
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
}
