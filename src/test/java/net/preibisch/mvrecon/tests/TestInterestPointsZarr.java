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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import util.URITools;

/**
 * The interest point store ({@link InterestPointsZarrStore}, {@link InterestPointsZarr}): round trips of points with
 * their attributes and of correspondences, the per-pair reads, staging files, commits that replace a (label, timepoint)
 * group, deletes, several store instances on one container (as several JVMs), odd label names, cross-label and
 * cross-timepoint correspondences, the N5 fallback and the conversion. The grid is tiny (16 rows per chunk, 64 per
 * shard) so that the entries cross chunk and shard borders.
 */
public class TestInterestPointsZarr
{
	@TempDir
	Path tmp;

	static final String[] LABELS = { "beads", "beads_split" };
	static final int VIEW_COUNT = 6;

	int chunkRows, shardRows;
	long cacheBytes;

	@BeforeEach
	public void tinyGrid()
	{
		chunkRows = InterestPointsZarrStore.defaultChunkRows;
		shardRows = InterestPointsZarrStore.defaultShardRows;
		cacheBytes = InterestPointsZarrStore.chunkCacheBytes;
		InterestPointsZarrStore.defaultChunkRows = 16;
		InterestPointsZarrStore.defaultShardRows = 64;
		InterestPointsZarrStore.generationCheckMillis = 0; // a second store instance plays another JVM: always look at the disk
	}

	@AfterEach
	public void restoreGrid()
	{
		InterestPointsZarrStore.defaultChunkRows = chunkRows;
		InterestPointsZarrStore.defaultShardRows = shardRows;
		InterestPointsZarrStore.chunkCacheBytes = cacheBytes;
		InterestPointsZarrStore.generationCheckMillis = 2000;
	}

	/** Synthetic points of a view with attributes. Ids are sparse for beads_split (like SplittingTools), dense otherwise; view 3 has none. */
	static List< InterestPoint > points( final int view, final String label )
	{
		final List< InterestPoint > points = new ArrayList<>();
		if ( view == 3 )
			return points;

		final Random random = new Random( 31 * view + label.hashCode() );
		final int count = 5 + random.nextInt( 40 );
		for ( int i = 0; i < count; ++i )
		{
			final int id = label.endsWith( "_split" ) ? 7 * i + 2 : i;
			final double[] location = { random.nextDouble() * 1000, random.nextDouble() * 1000, random.nextDouble() * 100 };
			points.add( new InterestPoint( id, location, -0.01f * ( i + 1 ), 1.5f + 0.1f * i, 100 + i ) );
		}
		return points;
	}

	/** Correspondences of a view to its neighbor views (same label); consistent in both directions. */
	static List< CorrespondingInterestPoints > correspondences( final int view, final String label )
	{
		final List< CorrespondingInterestPoints > correspondences = new ArrayList<>();
		final List< InterestPoint > own = points( view, label );
		for ( final int neighbor : new int[] { view - 1, view + 1 } )
		{
			if ( neighbor < 0 || neighbor >= VIEW_COUNT )
				continue;

			final List< InterestPoint > other = points( neighbor, label );
			final int count = Math.min( own.size(), other.size() ) / 2;
			for ( int i = 0; i < count; ++i )
			{
				final int consensusSet = i % 3 == 0 ? -1 : i % 3;
				correspondences.add( new CorrespondingInterestPoints( own.get( i ).getId(), new ViewId( 0, neighbor ), label, other.get( i ).getId(), consensusSet ) );
			}
		}
		return correspondences;
	}

	static InterestPointsZarr list( final URI baseURI, final int view, final String label )
	{
		return new InterestPointsZarr( baseURI, InterestPointsN5.createN5datasetPath( 0, view, label ) );
	}

	/** @return { number of files, number of directories } below root */
	static long[] countFilesAndDirectories( final Path root ) throws IOException
	{
		final long[] counts = new long[ 2 ];
		try ( Stream< Path > paths = Files.walk( root ) )
		{
			paths.forEach( path -> ++counts[ Files.isDirectory( path ) ? 1 : 0 ] );
		}
		return counts;
	}

	/** Positions are stored as float32: equal within what a float keeps at 1000 px. */
	static void assertSamePoints( final Collection< InterestPoint > expected, final Map< Integer, InterestPoint > actual )
	{
		assertEquals( expected.size(), actual.size() );
		for ( final InterestPoint point : expected )
		{
			final InterestPoint loaded = actual.get( point.getId() );
			assertNotNull( loaded, "missing id " + point.getId() );
			assertArrayEquals( point.getL(), loaded.getL(), 1e-3 );
			assertEquals( point.getResponse(), loaded.getResponse(), 0.0f );
			assertEquals( point.getSigma(), loaded.getSigma(), 0.0f );
			assertEquals( point.getIntensity(), loaded.getIntensity(), 0.0f );
		}
	}

	/** An order-independent checksum of correspondences, to compare lists. */
	static long checksum( final Collection< CorrespondingInterestPoints > correspondences )
	{
		long checksum = correspondences.size();
		for ( final CorrespondingInterestPoints c : correspondences )
		{
			checksum += 1_000_003L * c.getDetectionId();
			checksum += 10_007L * c.getCorrespondingDetectionId();
			checksum += 101L * c.getCorrespondingViewId().getViewSetupId();
			checksum += 1_009L * c.getCorrespondingViewId().getTimePointId();
			checksum += 7L * c.getConsensusSetId();
			checksum += c.getCorrespodingLabel().hashCode();
		}
		return checksum;
	}

	static void saveAll( final InterestPointsZarrStore store, final URI baseURI, final int viewCount, final String... labels )
	{
		store.beginBatch();
		for ( int view = 0; view < viewCount; ++view )
			for ( final String label : labels )
			{
				final InterestPointsZarr list = list( baseURI, view, label );
				list.setInterestPoints( points( view, label ) );
				list.setCorrespondingInterestPoints( new ArrayList<>( correspondences( view, label ) ) );
				assertTrue( list.saveInterestPoints( false ) );
				assertTrue( list.saveCorrespondingInterestPoints( false ) );
			}
		store.commit();
	}

	static File groupDir( final File zarr, final String label, final int tp )
	{
		return new File( zarr, InterestPointsZarrStore.Group.encodeLabel( label ) + "/tp" + tp );
	}

	@Test
	public void roundTripReplaceStagingDelete() throws Exception
	{
		final File base = tmp.resolve( "dataset" ).toFile();
		base.mkdirs();
		final URI baseURI = base.toURI();
		final File zarr = new File( base, InterestPointsZarrStore.ZARR_CONTAINER );
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseURI );
		assertFalse( store.exists() );

		// ---- 1. save everything in one batch, like XmlIoSpimData2.saveInterestPointsInParallel ----
		saveAll( store, baseURI, VIEW_COUNT, LABELS );
		assertTrue( store.exists() );
		assertTrue( groupDir( zarr, "beads", 0 ).isDirectory() );
		assertTrue( groupDir( zarr, "beads_split", 0 ).isDirectory() );

		final long[] counts = countFilesAndDirectories( zarr.toPath() );
		assertTrue( counts[ 0 ] < 50, "files in the store: " + counts[ 0 ] );

		final File[] generations = groupDir( zarr, "beads", 0 ).listFiles( f -> f.isDirectory() && f.getName().startsWith( "g" ) );
		assertEquals( 1, generations.length, "one generation" );
		final String pointsJson = Files.readString( new File( generations[ 0 ], "points/zarr.json" ).toPath() ).replaceAll( "\\s+", "" );
		int totalPoints = 0;
		for ( int view = 0; view < VIEW_COUNT; ++view )
			totalPoints += points( view, "beads" ).size();
		assertTrue( pointsJson.contains( "\"shape\":[" + totalPoints + ",6]" ), pointsJson ); // Zarr order: n5 [6, N] appears as [N, 6]
		assertTrue( pointsJson.contains( "\"data_type\":\"float32\"" ), pointsJson );
		assertTrue( pointsJson.contains( "\"chunk_shape\":[64,6]" ), pointsJson ); // the shard
		assertTrue( pointsJson.contains( "\"sharding_indexed\"" ), pointsJson );
		assertTrue( pointsJson.contains( "\"chunk_shape\":[16,6]" ), pointsJson ); // the inner chunk
		assertTrue( pointsJson.contains( "\"name\":\"crc32c\"" ), pointsJson );

		// ---- 2. read back through fresh handles, whole lists and per pair ----
		for ( int view = 0; view < VIEW_COUNT; ++view )
			for ( final String label : LABELS )
			{
				final InterestPoints list = list( baseURI, view, label );
				assertSamePoints( points( view, label ), list.getInterestPointsCopy() );
				assertEquals( checksum( correspondences( view, label ) ), checksum( list.getCorrespondingInterestPointsCopy() ), "correspondences " + view + " " + label );

				for ( int partner = 0; partner < VIEW_COUNT; ++partner )
				{
					final List< CorrespondingInterestPoints > expected = new ArrayList<>();
					for ( final CorrespondingInterestPoints c : correspondences( view, label ) )
						if ( c.getCorrespondingViewId().getViewSetupId() == partner )
							expected.add( c );

					final InterestPoints unloaded = list( baseURI, view, label );
					assertEquals( checksum( expected ), checksum( unloaded.getCorrespondingInterestPointsCopy( new ViewId( 0, partner ), label ) ), "pair " + view + "-" + partner );
				}

				final long partnerCount = correspondences( view, label ).stream().map( c -> c.getCorrespondingViewId().getViewSetupId() ).distinct().count();
				assertEquals( partnerCount, list( baseURI, view, label ).getCorrespondingViews().size() );
			}

		// ---- 3. replace one view's points (more of them): the group is rewritten, the other views and all correspondences stay ----
		final List< InterestPoint > morePoints = new ArrayList<>( points( 1, "beads" ) );
		for ( int i = 0; i < 100; ++i )
			morePoints.add( new InterestPoint( 1000 + i, new double[] { i, 2 * i, 3 * i }, -0.5f, 2.5f, 7 ) );

		final InterestPointsZarr view1 = list( baseURI, 1, "beads" );
		view1.setInterestPoints( morePoints );
		store.beginBatch();
		assertTrue( view1.saveInterestPoints( false ) );
		store.commit();

		assertSamePoints( morePoints, list( baseURI, 1, "beads" ).getInterestPointsCopy() );
		assertSamePoints( points( 0, "beads" ), list( baseURI, 0, "beads" ).getInterestPointsCopy() );
		assertSamePoints( points( 2, "beads" ), list( baseURI, 2, "beads" ).getInterestPointsCopy() );
		assertEquals( checksum( correspondences( 1, "beads" ) ), checksum( list( baseURI, 1, "beads" ).getCorrespondingInterestPointsCopy() ) );
		assertEquals( checksum( correspondences( 2, "beads" ) ), checksum( list( baseURI, 2, "beads" ).getCorrespondingInterestPointsCopy() ) );
		assertEquals( 1, groupDir( zarr, "beads", 0 ).listFiles( f -> f.isDirectory() && f.getName().startsWith( "g" ) ).length, "the old generation is deleted" );
		assertSamePoints( points( 1, "beads_split" ), list( baseURI, 1, "beads_split" ).getInterestPointsCopy() );

		// ---- 4. a new label in one view: a new group; an empty list (ADD_LABEL) is an entry, too ----
		final InterestPointsZarr nuclei = list( baseURI, 2, "nuclei" );
		nuclei.setInterestPoints( points( 2, "beads_split" ) );
		nuclei.setCorrespondingInterestPoints( new ArrayList<>() );
		final InterestPointsZarr empty = list( baseURI, 4, "nuclei" );
		empty.setInterestPoints( new ArrayList<>() );
		empty.setCorrespondingInterestPoints( new ArrayList<>() );
		store.beginBatch();
		assertTrue( nuclei.saveInterestPoints( false ) );
		assertTrue( nuclei.saveCorrespondingInterestPoints( false ) );
		assertTrue( empty.saveInterestPoints( false ) );
		assertTrue( empty.saveCorrespondingInterestPoints( false ) );
		store.commit();

		assertTrue( groupDir( zarr, "nuclei", 0 ).isDirectory() );
		assertSamePoints( points( 2, "beads_split" ), list( baseURI, 2, "nuclei" ).getInterestPointsCopy() );
		assertEquals( 0, list( baseURI, 2, "nuclei" ).getCorrespondingInterestPointsCopy().size() );
		assertEquals( 0, list( baseURI, 4, "nuclei" ).getInterestPointsCopy().size() );
		assertTrue( store.hasEntry( new Key( 0, 4, "nuclei" ) ) );
		assertFalse( store.hasEntry( new Key( 0, 5, "nuclei" ) ) );

		// an XML entry with no data anywhere is an error, not an empty list
		assertThrows( RuntimeException.class, () -> list( baseURI, 5, "nuclei" ).getInterestPointsCopy() );
		assertThrows( RuntimeException.class, () -> list( baseURI, 0, "unknown" ).getCorrespondingInterestPointsCopy() );

		// ---- 5. a save outside a batch writes a staging file: readable at once, folded in by the next commit ----
		final InterestPointsZarr view4 = list( baseURI, 4, "beads" );
		final List< CorrespondingInterestPoints > fewer = new ArrayList<>( correspondences( 4, "beads" ).subList( 0, 3 ) );
		view4.setCorrespondingInterestPoints( fewer );
		assertTrue( view4.saveCorrespondingInterestPoints( true ) );
		assertEquals( 1, new File( zarr, "staging" ).list().length, "one staging file" );
		assertEquals( checksum( fewer ), checksum( list( baseURI, 4, "beads" ).getCorrespondingInterestPointsCopy() ) );
		assertSamePoints( points( 4, "beads" ), list( baseURI, 4, "beads" ).getInterestPointsCopy() ); // the points of the entry are untouched
		assertEquals( checksum( correspondences( 3, "beads" ) ), checksum( list( baseURI, 3, "beads" ).getCorrespondingInterestPointsCopy() ), "the partner's own list is its own" );

		store.commit();
		final String[] leftover = new File( zarr, "staging" ).list();
		assertTrue( leftover == null || leftover.length == 0, "staging files left after commit: " + Arrays.toString( leftover ) );
		assertEquals( checksum( fewer ), checksum( list( baseURI, 4, "beads" ).getCorrespondingInterestPointsCopy() ) );
		assertSamePoints( points( 4, "beads" ), list( baseURI, 4, "beads" ).getInterestPointsCopy() );

		// ---- 6. delete: one view of a label, then all views of a label ----
		final InterestPointsZarr view5 = list( baseURI, 5, "beads" );
		assertTrue( view5.deleteInterestPoints() );
		assertTrue( view5.deleteCorrespondingInterestPoints() );
		assertFalse( store.hasEntry( new Key( 0, 5, "beads" ) ) );
		store.commit();
		assertFalse( store.hasEntry( new Key( 0, 5, "beads" ) ) );
		assertThrows( RuntimeException.class, () -> list( baseURI, 5, "beads" ).getInterestPointsCopy() );
		assertSamePoints( points( 4, "beads" ), list( baseURI, 4, "beads" ).getInterestPointsCopy() );
		assertSamePoints( points( 5, "beads_split" ), list( baseURI, 5, "beads_split" ).getInterestPointsCopy() );

		list( baseURI, 2, "nuclei" ).deleteInterestPoints();
		list( baseURI, 4, "nuclei" ).deleteInterestPoints();
		store.commit();
		assertFalse( groupDir( zarr, "nuclei", 0 ).exists(), "a group without entries is removed" );

		// ---- 7. a second store instance (another JVM): sees staging files written after its listing and foreign commits ----
		final InterestPointsZarrStore storeB = new InterestPointsZarrStore( baseURI );
		assertSamePoints( points( 0, "beads" ), InterestPointsZarr.toMap( storeB.points( new Key( 0, 0, "beads" ) ) ) );
		final Key late9 = new Key( 0, 9, "late" );
		final Key late10 = new Key( 0, 10, "late" );
		assertFalse( storeB.hasEntry( late9 ) ); // B has listed the staging directory now

		final InterestPointsZarr lateList9 = list( baseURI, 9, "late" );
		lateList9.setInterestPoints( points( 2, "beads" ) );
		lateList9.setCorrespondingInterestPoints( new ArrayList<>() );
		final InterestPointsZarr lateList10 = list( baseURI, 10, "late" );
		lateList10.setInterestPoints( points( 3, "beads" ) );
		lateList10.setCorrespondingInterestPoints( new ArrayList<>( correspondences( 3, "beads" ) ) );
		InterestPointsZarr.saveStaged( Arrays.asList( lateList9, lateList10 ) ); // through the shared store A, one file

		assertEquals( 1, new File( zarr, "staging" ).list().length, "one staging file per saveStaged call" );
		assertTrue( storeB.hasEntry( late9 ), "B must find a staging file written after its listing" );
		assertEquals( checksum( correspondences( 3, "beads" ) ), checksum( storeB.correspondences( late10 ) ) );
		assertSamePoints( points( 2, "beads" ), InterestPointsZarr.toMap( storeB.points( late9 ) ) );

		store.commit(); // A folds the staging file into the group 'late' and deletes it
		assertEquals( 0, new File( zarr, "staging" ).list().length );
		assertSamePoints( points( 3, "beads" ), InterestPointsZarr.toMap( storeB.points( late10 ) ) );
		assertEquals( 0, storeB.correspondences( late9 ).size() );

		// B holds the index of 'beads'; A rewrites the group (and deletes the old generation): B's next read must follow
		final InterestPointsZarr view0 = list( baseURI, 0, "beads" );
		view0.setInterestPoints( morePoints );
		store.beginBatch();
		view0.saveInterestPoints( false );
		store.commit();
		assertSamePoints( morePoints, InterestPointsZarr.toMap( storeB.points( new Key( 0, 0, "beads" ) ) ) );
		assertSamePoints( points( 2, "beads" ), InterestPointsZarr.toMap( storeB.points( new Key( 0, 2, "beads" ) ) ) );

		// a store instance created after everything reads the final state
		final InterestPointsZarrStore fresh = new InterestPointsZarrStore( baseURI );
		assertTrue( fresh.exists() );
		assertSamePoints( morePoints, InterestPointsZarr.toMap( fresh.points( new Key( 0, 0, "beads" ) ) ) );
		assertFalse( fresh.hasEntry( new Key( 0, 5, "beads" ) ) );
	}

	@Test
	public void timepointsLabelsAndNames() throws Exception
	{
		final File base = tmp.resolve( "names" ).toFile();
		base.mkdirs();
		final URI baseURI = base.toURI();
		final File zarr = new File( base, InterestPointsZarrStore.ZARR_CONTAINER );
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseURI );

		// correspondences across labels and timepoints are just partner keys
		final String odd = "my label/with:odd (chars)";
		final InterestPointsZarr beadsTp1 = new InterestPointsZarr( baseURI, new ViewId( 1, 0 ), "beads" );
		beadsTp1.setInterestPoints( points( 0, "beads" ) );
		final List< CorrespondingInterestPoints > cross = new ArrayList<>();
		cross.add( new CorrespondingInterestPoints( 0, new ViewId( 0, 0 ), odd, 2, -1 ) );
		cross.add( new CorrespondingInterestPoints( 1, new ViewId( 0, 0 ), odd, 9, 1 ) );
		cross.add( new CorrespondingInterestPoints( 1, new ViewId( 1, 1 ), "beads", 3, 0 ) );
		beadsTp1.setCorrespondingInterestPoints( cross );

		final InterestPointsZarr oddList = new InterestPointsZarr( baseURI, new ViewId( 0, 0 ), odd );
		oddList.setInterestPoints( points( 0, "beads_split" ) );
		oddList.setCorrespondingInterestPoints( new ArrayList<>() );

		store.beginBatch();
		assertTrue( beadsTp1.saveInterestPoints( false ) );
		assertTrue( beadsTp1.saveCorrespondingInterestPoints( false ) );
		assertTrue( oddList.saveInterestPoints( false ) );
		assertTrue( oddList.saveCorrespondingInterestPoints( false ) );
		store.commit();

		assertTrue( groupDir( zarr, "beads", 1 ).isDirectory() );
		assertFalse( groupDir( zarr, "beads", 0 ).exists() );
		assertEquals( "my%20label%2Fwith%3Aodd%20%28chars%29", InterestPointsZarrStore.Group.encodeLabel( odd ) );
		assertTrue( groupDir( zarr, odd, 0 ).isDirectory() );

		final InterestPointsZarr reloaded = new InterestPointsZarr( baseURI, new ViewId( 1, 0 ), "beads" );
		assertEquals( checksum( cross ), checksum( reloaded.getCorrespondingInterestPointsCopy() ) );
		final Set< Pair< ViewId, String > > partners = new InterestPointsZarr( baseURI, new ViewId( 1, 0 ), "beads" ).getCorrespondingViews();
		assertEquals( 2, partners.size() );
		assertEquals( 2, new InterestPointsZarr( baseURI, new ViewId( 1, 0 ), "beads" ).getCorrespondingInterestPointsCopy( new ViewId( 0, 0 ), odd ).size() );
		assertEquals( 1, new InterestPointsZarr( baseURI, new ViewId( 1, 0 ), "beads" ).getCorrespondingInterestPointsCopy( new ViewId( 1, 1 ), "beads" ).size() );
		assertEquals( 0, new InterestPointsZarr( baseURI, new ViewId( 1, 0 ), "beads" ).getCorrespondingInterestPointsCopy( new ViewId( 0, 3 ), "beads" ).size() );
		assertSamePoints( points( 0, "beads_split" ), new InterestPointsZarr( baseURI, new ViewId( 0, 0 ), odd ).getInterestPointsCopy() );

		// a store of another version is refused
		final File other = tmp.resolve( "otherVersion" ).toFile();
		other.mkdirs();
		try ( final N5Writer writer = URITools.instantiateN5Writer( StorageFormat.ZARR, new File( other, InterestPointsZarrStore.ZARR_CONTAINER ).toURI() ) )
		{
			writer.setAttribute( "/", "interestpoints", "1.0.0" );
		}
		final InterestPointsZarr otherList = new InterestPointsZarr( other.toURI(), new ViewId( 0, 0 ), "beads" );
		otherList.setInterestPoints( points( 0, "beads" ) );
		assertThrows( IllegalStateException.class, () -> otherList.saveInterestPoints( true ) );
	}

	@Test
	public void filteredLabelEntries() throws Exception
	{
		final File base = tmp.resolve( "filtered" ).toFile();
		base.mkdirs();
		final URI baseURI = base.toURI();
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseURI );
		final InterestPointFilter filter = InterestPointFilter.fromString( "response=0.05.." );

		// a filtered label stages a marker and its own correspondences
		final ViewInterestPointLists vipl = new ViewInterestPointLists( 0, 0 );
		final InterestPointsZarr beads = list( baseURI, 0, "beads" );
		beads.setInterestPoints( points( 0, "beads" ) );
		beads.setCorrespondingInterestPoints( new ArrayList<>() );
		final VirtualFilteredInterestPoints strong = VirtualFilteredInterestPoints.create( baseURI, new ViewId( 0, 0 ), "beads_r0.05", "beads", filter );
		strong.setCorrespondingInterestPoints( new ArrayList<>( correspondences( 0, "beads" ).subList( 0, 2 ) ) );
		vipl.addInterestPointList( "beads", beads );
		vipl.addInterestPointList( "beads_r0.05", strong );

		store.beginBatch();
		assertTrue( beads.saveInterestPoints( false ) );
		assertTrue( beads.saveCorrespondingInterestPoints( false ) );
		assertTrue( strong.saveInterestPoints( false ) );
		assertTrue( strong.saveCorrespondingInterestPoints( false ) );
		store.commit();

		assertEquals( Boolean.TRUE, store.isFiltered( new Key( 0, 0, "beads_r0.05" ) ) );
		assertEquals( Boolean.FALSE, store.isFiltered( new Key( 0, 0, "beads" ) ) );
		assertNull( store.isFiltered( new Key( 0, 1, "beads" ) ) );
		assertEquals( 0, store.points( new Key( 0, 0, "beads_r0.05" ) ).size() );
		assertEquals( 2, store.correspondences( new Key( 0, 0, "beads_r0.05" ) ).size() );

		// loaded again: the filtered label filters its base, keeps its correspondences; the two consistency checks fire
		final ViewInterestPointLists loaded = new ViewInterestPointLists( 0, 0 );
		final InterestPoints beadsLoaded = InterestPoints.instantiatefromXML( baseURI, beads.getXMLRepresentation(), null, null );
		final InterestPoints strongLoaded = InterestPoints.instantiatefromXML( baseURI, strong.getXMLRepresentation(), "beads", filter.toString() );
		loaded.addInterestPointList( "beads", beadsLoaded );
		loaded.addInterestPointList( "beads_r0.05", strongLoaded );
		assertTrue( strongLoaded instanceof VirtualFilteredInterestPoints );
		assertEquals( filter.filter( points( 0, "beads" ) ).size(), strongLoaded.getInterestPointsCopy().size() );
		assertEquals( 2, strongLoaded.getCorrespondingInterestPointsCopy().size() );
		assertSamePoints( points( 0, "beads" ), beadsLoaded.getInterestPointsCopy() );

		assertThrows( RuntimeException.class, () -> InterestPoints.instantiatefromXML( baseURI, strong.getXMLRepresentation(), null, null ).getInterestPointsCopy(), "stored as filtered, XML says plain" );
		final ViewInterestPointLists wrong = new ViewInterestPointLists( 0, 0 );
		final InterestPoints plainAsFiltered = InterestPoints.instantiatefromXML( baseURI, beads.getXMLRepresentation(), "beads_r0.05", filter.toString() );
		wrong.addInterestPointList( "beads", plainAsFiltered );
		wrong.addInterestPointList( "beads_r0.05", strongLoaded );
		assertThrows( RuntimeException.class, () -> plainAsFiltered.getInterestPointsCopy(), "stored as plain, XML says filtered" );

		// staged through saveStaged (Spark): the marker goes along
		final VirtualFilteredInterestPoints staged = VirtualFilteredInterestPoints.create( baseURI, new ViewId( 0, 1 ), "beads_r0.05", "beads", filter );
		final InterestPointsZarr beads1 = list( baseURI, 1, "beads" );
		beads1.setInterestPoints( points( 1, "beads" ) );
		beads1.setCorrespondingInterestPoints( new ArrayList<>() );
		InterestPointsZarr.saveStaged( Arrays.asList( beads1, staged ) );
		assertEquals( Boolean.TRUE, store.isFiltered( new Key( 0, 1, "beads_r0.05" ) ) );
		store.commit();
		assertEquals( Boolean.TRUE, store.isFiltered( new Key( 0, 1, "beads_r0.05" ) ) );
		assertEquals( Boolean.FALSE, store.isFiltered( new Key( 0, 1, "beads" ) ) );

		// deleting the filtered label removes its entry
		strongLoaded.deleteInterestPoints();
		store.commit();
		assertFalse( store.hasEntry( new Key( 0, 0, "beads_r0.05" ) ) );
		assertTrue( store.hasEntry( new Key( 0, 1, "beads_r0.05" ) ) );
	}

	static N5Writer legacyWriter( final File base )
	{
		return URITools.instantiateN5Writer( StorageFormat.N5, new File( base, InterestPointsN5.baseN5 ).toURI() );
	}

	/** Writes a view's points and correspondences in the N5 layout. */
	static void writeLegacy( final N5Writer writer, final int view, final String label )
	{
		final String path = InterestPointsN5.createN5datasetPath( 0, view, label );
		InterestPointsN5.saveInterestPointsStatic( writer, path, points( view, label ) );
		InterestPointsN5.saveCorrespondencesStatic( writer, path, correspondences( view, label ) );
	}

	@Test
	public void legacyFallbackAndConversion() throws Exception
	{
		final File base = tmp.resolve( "legacy" ).toFile();
		base.mkdirs();
		final URI baseURI = base.toURI();
		final File zarr = new File( base, InterestPointsZarrStore.ZARR_CONTAINER );
		final File legacy = new File( base, InterestPointsN5.baseN5 );
		final InterestPointFilter filter = InterestPointFilter.fromString( "sigma=2.." );

		// ---- 1. N5 groups: current ones, one with float64 sigma, one old-style without attributes, one filtered marker ----
		try ( final N5Writer writer = legacyWriter( base ) )
		{
			for ( int view = 0; view < VIEW_COUNT; ++view )
				for ( final String label : LABELS )
					writeLegacy( writer, view, label );

			// view 1, beads: sigma as FLOAT64 (labels before 2026-10-19)
			final String sigma1 = InterestPointsN5.ipDataset( InterestPointsN5.createN5datasetPath( 0, 1, "beads" ) ) + "/" + InterestPointsN5.sigmaDataset;
			final List< InterestPoint > view1 = points( 1, "beads" );
			final double[] sigmas = new double[ view1.size() ];
			for ( int i = 0; i < sigmas.length; ++i )
				sigmas[ i ] = view1.get( i ).getSigma();
			writer.remove( sigma1 );
			N5Utils.save( ArrayImgs.doubles( sigmas, 1, sigmas.length ), writer, sigma1, new int[] { 1, sigmas.length }, new GzipCompression() );

			// view 2, beads: no attribute datasets at all
			final String ip2 = InterestPointsN5.ipDataset( InterestPointsN5.createN5datasetPath( 0, 2, "beads" ) );
			writer.remove( ip2 + "/" + InterestPointsN5.responseDataset );
			writer.remove( ip2 + "/" + InterestPointsN5.sigmaDataset );
			writer.remove( ip2 + "/" + InterestPointsN5.intensityDataset );

			// view 0: a filtered label as a marker group with its own correspondences
			final String filteredPath = InterestPointsN5.createN5datasetPath( 0, 0, "beads_s2" );
			InterestPointsN5.saveFilteredMarkerStatic( writer, filteredPath, "beads", filter );
			InterestPointsN5.saveCorrespondencesStatic( writer, filteredPath, correspondences( 0, "beads" ).subList( 0, 1 ) );
			assertEquals( DataType.FLOAT64, writer.getDatasetAttributes( sigma1 ).getDataType() );
		}
		final long[] legacyCounts = countFilesAndDirectories( legacy.toPath() );
		assertFalse( InterestPointsZarrStore.get( baseURI ).exists() );

		// ---- 2. readable through the handles before the conversion ----
		for ( int view = 0; view < VIEW_COUNT; ++view )
			for ( final String label : LABELS )
			{
				final InterestPoints list = list( baseURI, view, label );
				final Map< Integer, InterestPoint > loaded = list.getInterestPointsCopy();
				if ( view == 2 && label.equals( "beads" ) )
				{
					assertEquals( points( view, label ).size(), loaded.size() );
					for ( final InterestPoint ip : loaded.values() )
						assertTrue( Float.isNaN( ip.getResponse() ) && Float.isNaN( ip.getSigma() ) && Float.isNaN( ip.getIntensity() ) );
				}
				else
					assertSamePoints( points( view, label ), loaded );
				assertEquals( checksum( correspondences( view, label ) ), checksum( list.getCorrespondingInterestPointsCopy() ) );
			}

		final ViewInterestPointLists vipl = new ViewInterestPointLists( 0, 0 );
		final InterestPoints beads0 = list( baseURI, 0, "beads" );
		final InterestPoints filtered0 = InterestPoints.instantiatefromXML( baseURI, InterestPointsN5.createN5datasetPath( 0, 0, "beads_s2" ), "beads", filter.toString() );
		vipl.addInterestPointList( "beads", beads0 );
		vipl.addInterestPointList( "beads_s2", filtered0 );
		assertEquals( filter.filter( points( 0, "beads" ) ).size(), filtered0.getInterestPointsCopy().size() );
		assertEquals( 1, filtered0.getCorrespondingInterestPointsCopy().size() );
		assertThrows( RuntimeException.class, () -> list( baseURI, 0, "beads_s2" ).getInterestPointsCopy(), "a marker read as a plain label" );

		// ---- 3. a save into the store before the conversion: the store's version wins ----
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseURI );
		final List< InterestPoint > newer = points( 4, "beads" ).subList( 0, 3 );
		final InterestPointsZarr view4 = list( baseURI, 4, "beads" );
		view4.setInterestPoints( newer );
		view4.setCorrespondingInterestPoints( new ArrayList<>() );
		store.beginBatch();
		view4.saveInterestPoints( false );
		view4.saveCorrespondingInterestPoints( false );
		store.commit();
		assertSamePoints( newer, list( baseURI, 4, "beads" ).getInterestPointsCopy() );

		// ---- 4. convert ----
		assertEquals( VIEW_COUNT * LABELS.length, InterestPointsN5ToZarr.convert( baseURI ) ); // view 4 beads is skipped, beads_s2 is converted
		assertTrue( store.exists() );
		assertFalse( new File( legacy, "tpId_0_viewSetupId_0" ).exists(), "N5 view groups are removed" );
		final long[] zarrCounts = countFilesAndDirectories( zarr.toPath() );
		assertTrue( zarrCounts[ 0 ] < legacyCounts[ 0 ] / 3, "files " + zarrCounts[ 0 ] + " vs N5 " + legacyCounts[ 0 ] );

		for ( int view = 0; view < VIEW_COUNT; ++view )
			for ( final String label : LABELS )
			{
				final InterestPoints list = list( baseURI, view, label );
				if ( view == 4 && label.equals( "beads" ) )
				{
					assertSamePoints( newer, list.getInterestPointsCopy() );
					assertEquals( 0, list.getCorrespondingInterestPointsCopy().size() );
					continue;
				}
				final Map< Integer, InterestPoint > loaded = list.getInterestPointsCopy();
				if ( view == 2 && label.equals( "beads" ) )
					for ( final InterestPoint ip : loaded.values() )
						assertTrue( Float.isNaN( ip.getSigma() ) );
				else
					assertSamePoints( points( view, label ), loaded );
				assertEquals( checksum( correspondences( view, label ) ), checksum( list.getCorrespondingInterestPointsCopy() ), "after conversion " + view + " " + label );
			}
		assertEquals( Boolean.TRUE, store.isFiltered( new Key( 0, 0, "beads_s2" ) ) );
		assertEquals( 1, store.correspondences( new Key( 0, 0, "beads_s2" ) ).size() );
		final ViewInterestPointLists converted = new ViewInterestPointLists( 0, 0 );
		final InterestPoints filteredConverted = InterestPoints.instantiatefromXML( baseURI, InterestPointsN5.createN5datasetPath( 0, 0, "beads_s2" ), "beads", filter.toString() );
		converted.addInterestPointList( "beads", list( baseURI, 0, "beads" ) );
		converted.addInterestPointList( "beads_s2", filteredConverted );
		assertEquals( filter.filter( points( 0, "beads" ) ).size(), filteredConverted.getInterestPointsCopy().size() );

		assertEquals( 0, InterestPointsN5ToZarr.convert( baseURI ), "nothing left to convert" );
	}

	/** The staging format survives the 2 GB check for small lists and refuses to stage nothing. */
	@Test
	public void stagingFileOfNothing()
	{
		final File base = tmp.resolve( "nothing" ).toFile();
		base.mkdirs();
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( base.toURI() );
		store.writeStagingFile( Map.of(), Map.of() );
		assertFalse( store.exists(), "nothing to stage creates no container" );
		assertFalse( store.hasEntry( new Key( 0, 0, "beads" ) ) );
		assertNull( store.points( new Key( 0, 0, "beads" ) ) );
		assertNull( store.correspondences( new Key( 0, 0, "beads" ) ) );
		assertNull( store.correspondingViews( new Key( 0, 0, "beads" ) ) );
		store.commit(); // nothing staged, no container: a no-op
		assertFalse( store.exists() );
		assertEquals( 0, InterestPointsZarr.toPoints( new ArrayList< InterestPoint >() ).size() );
		assertEquals( InterestPointsZarrStore.COLUMNS * 2, InterestPointsZarr.toPoints( points( 0, "beads" ).subList( 0, 2 ) ).data().length );
	}
}
