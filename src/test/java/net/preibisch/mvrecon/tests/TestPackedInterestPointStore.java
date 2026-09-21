package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.PackedInterestPointStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.PackedInterestPointStore.Key;
import util.URITools;

public class TestPackedInterestPointStore
{
	@TempDir
	Path tmp;

	static final String[] LABELS = { "beads", "beads_split" };
	static final int N_VIEWS = 6;

	/** synthetic points: view v, label l; ids sparse for beads_split (like SplittingTools), dense otherwise; view 3 has no points */
	static List< InterestPoint > points( final int v, final String label )
	{
		final List< InterestPoint > l = new ArrayList<>();
		if ( v == 3 ) return l;
		final Random rnd = new Random( 31 * v + label.hashCode() );
		final int n = 5 + rnd.nextInt( 40 );
		for ( int i = 0; i < n; ++i )
			l.add( new InterestPoint( label.endsWith( "_split" ) ? 7 * i + 2 : i, new double[] { rnd.nextDouble() * 1000, rnd.nextDouble() * 1000, rnd.nextDouble() * 100 } ) );
		return l;
	}

	/** correspondences of view v (label l) to views v-1 and v+1 (same label), both directions consistent */
	static List< CorrespondingInterestPoints > corrs( final int v, final String label )
	{
		final List< CorrespondingInterestPoints > c = new ArrayList<>();
		final List< InterestPoint > mine = points( v, label );
		for ( final int o : new int[] { v - 1, v + 1 } )
		{
			if ( o < 0 || o >= N_VIEWS ) continue;
			final List< InterestPoint > other = points( o, label );
			final int n = Math.min( mine.size(), other.size() ) / 2;
			for ( int i = 0; i < n; ++i )
				c.add( new CorrespondingInterestPoints( mine.get( i ).getId(), new ViewId( 0, o ), label, other.get( i ).getId(), i % 3 == 0 ? -1 : i % 3 ) );
		}
		return c;
	}

	static long[] count( final Path root ) throws IOException
	{
		final long[] c = new long[ 2 ];
		try ( Stream< Path > s = Files.walk( root ) ) { s.forEach( p -> { if ( Files.isDirectory( p ) ) c[ 1 ]++; else c[ 0 ]++; } ); }
		return c;
	}

	static void assertSame( final Collection< InterestPoint > expected, final Map< Integer, InterestPoint > actual )
	{
		assertEquals( expected.size(), actual.size() );
		for ( final InterestPoint p : expected )
		{
			final InterestPoint q = actual.get( p.getId() );
			assertNotNull( q, "missing id " + p.getId() );
			assertArrayEquals( p.getL(), q.getL(), 0.0 );
		}
	}

	static long sig( final Collection< CorrespondingInterestPoints > c )
	{
		long s = c.size();
		for ( final CorrespondingInterestPoints x : c )
			s += 1_000_003L * x.getDetectionId() + 10_007L * x.getCorrespondingDetectionId() + 101L * x.getCorrespondingViewId().getViewSetupId() + 7L * x.getConsensusSetId() + x.getCorrespodingLabel().hashCode();
		return s;
	}

	@Test
	public void legacyConvertAppendRewriteBlobDelete() throws Exception
	{
		final File base = tmp.resolve( "dataset" ).toFile();
		base.mkdirs();
		final URI baseURI = base.toURI();
		PackedInterestPointStore.defaultChunkPoints = 16; // tiny grid: entries (5..45 points) span chunk and shard borders
		PackedInterestPointStore.defaultShardPoints = 64;
		final File zarr = new File( base, PackedInterestPointStore.ZARR_CONTAINER );

		// ---- 1. legacy per-view groups, written with the legacy static writers ----
		try ( final N5Writer w = URITools.instantiateN5Writer( StorageFormat.N5, new File( base, InterestPointsN5.baseN5 ).toURI() ) )
		{
			for ( int v = 0; v < N_VIEWS; ++v )
				for ( final String label : LABELS )
				{
					final List< InterestPoint > pts = points( v, label );
					final int[] ids = pts.stream().mapToInt( InterestPoint::getId ).toArray();
					final double[][] loc = pts.stream().map( InterestPoint::getL ).toArray( double[][]::new );
					final String path = InterestPointsN5.createN5datasetPath( 0, v, label );
					InterestPointsN5.saveInterestPointsStatic( w, path, ids, loc );
					InterestPointsN5.saveCorrespondencesStatic( w, path, corrs( v, label ) );
				}
		}
		final long[] legacyCount = count( new File( base, InterestPointsN5.baseN5 ).toPath() );
		assertFalse( PackedInterestPointStore.get( baseURI ).exists() );

		// legacy read path still works
		for ( int v = 0; v < N_VIEWS; ++v )
			for ( final String label : LABELS )
			{
				final InterestPoints ip = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, v, label ) );
				assertSame( points( v, label ), ip.getInterestPointsCopy() );
				assertEquals( sig( corrs( v, label ) ), sig( ip.getCorrespondingInterestPointsCopy() ) );
			}

		// ---- 2. convert ----
		assertEquals( N_VIEWS * LABELS.length, PackedInterestPointStore.convertLegacy( baseURI ) );
		final PackedInterestPointStore store = PackedInterestPointStore.get( baseURI );
		assertTrue( store.exists() );
		final long[] packedCount = count( zarr.toPath() );
		final long[] n5Count = count( new File( base, InterestPointsN5.baseN5 ).toPath() );
		assertTrue( packedCount[ 0 ] + n5Count[ 0 ] < legacyCount[ 0 ] / 3, "files " + packedCount[ 0 ] + "+" + n5Count[ 0 ] + " vs legacy " + legacyCount[ 0 ] );
		// 187 points / 64 per shard = 3 shards for loc and id, 1 for correspondences, 3 index arrays, zarr.json per group/array
		assertTrue( packedCount[ 0 ] < 30, "files in zarr " + packedCount[ 0 ] );
		final String locJson = Files.readString( zarr.toPath().resolve( "points/g0/loc/zarr.json" ) ).replaceAll( "\\s+", "" );
		assertTrue( locJson.contains( "\"shape\":[187,3]" ), locJson ); // standard zarr order: n5 [3, N] appears as [N, 3]
		assertTrue( locJson.contains( "\"chunk_shape\":[64,3]" ) && locJson.contains( "\"sharding_indexed\"" ) && locJson.contains( "\"chunk_shape\":[16,3]" ), locJson );
		assertTrue( locJson.contains( "\"name\":\"crc32c\"" ) && !locJson.contains( "zstd" ), locJson );
		try ( final N5Writer w = URITools.instantiateN5Writer( StorageFormat.N5, new File( base, InterestPointsN5.baseN5 ).toURI() ) )
		{
			assertFalse( w.exists( InterestPointsN5.createN5datasetPath( 0, 0, "beads" ) ) );
		}

		for ( int v = 0; v < N_VIEWS; ++v )
			for ( final String label : LABELS )
			{
				final InterestPoints ip = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, v, label ) );
				assertSame( points( v, label ), ip.getInterestPointsCopy() );
				assertEquals( sig( corrs( v, label ) ), sig( ip.getCorrespondingInterestPointsCopy() ), "corrs " + v + " " + label );
				// pair range API equals the filtered full list
				for ( int o = 0; o < N_VIEWS; ++o )
				{
					final List< CorrespondingInterestPoints > expected = new ArrayList<>();
					for ( final CorrespondingInterestPoints c : corrs( v, label ) ) if ( c.getCorrespondingViewId().getViewSetupId() == o ) expected.add( c );
					final InterestPoints fresh = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, v, label ) );
					assertEquals( sig( expected ), sig( fresh.getCorrespondingInterestPointsCopy( new ViewId( 0, o ), label ) ), "pair " + v + "-" + o );
				}
				assertEquals( corrs( v, label ).stream().map( c -> c.getCorrespondingViewId().getViewSetupId() ).distinct().count(), ip.getCorrespondingViews().size() );
			}

		// ---- 3. append path: replace one view's points (bigger), add a new label; batch save through the writer variant + commit ----
		final List< InterestPoint > bigger = new ArrayList<>( points( 1, "beads" ) );
		for ( int i = 0; i < 100; ++i ) bigger.add( new InterestPoint( 1000 + i, new double[] { i, 2 * i, 3 * i } ) );
		final InterestPointsN5 v1 = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 1, "beads" ) );
		v1.setInterestPoints( bigger );
		final InterestPointsN5 newLabel = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 2, "nuclei" ) );
		newLabel.setInterestPoints( points( 2, "beads_split" ) );
		newLabel.setCorrespondingInterestPoints( new ArrayList<>() );
		store.beginBatch(); // like XmlIoSpimData2.saveInterestPointsInParallel: writer-variant saves stage in memory until commit()
		try ( final N5Writer w = URITools.instantiateN5Writer( StorageFormat.N5, new File( base, InterestPointsN5.baseN5 ).toURI() ) )
		{
			assertTrue( v1.saveInterestPoints( false, w ) );
			assertTrue( newLabel.saveInterestPoints( false, w ) );
			assertTrue( newLabel.saveCorrespondingInterestPoints( false, w ) );
		}
		store.commit();
		assertSame( bigger, new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 1, "beads" ) ).getInterestPointsCopy() );
		assertSame( points( 2, "beads_split" ), new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 2, "nuclei" ) ).getInterestPointsCopy() );
		assertEquals( 0, new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 2, "nuclei" ) ).getCorrespondingInterestPointsCopy().size() );
		// untouched entries and their correspondences (incl. pairs with view 1) survive
		assertSame( points( 0, "beads" ), new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 0, "beads" ) ).getInterestPointsCopy() );
		assertEquals( sig( corrs( 1, "beads" ) ), sig( new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 1, "beads" ) ).getCorrespondingInterestPointsCopy() ) );

		// ---- 4. rewrite path: replace every entry's points (live fraction of the old array drops to 0) ----
		final long[] before = count( zarr.toPath() );
		store.beginBatch();
		try ( final N5Writer w = URITools.instantiateN5Writer( StorageFormat.N5, new File( base, InterestPointsN5.baseN5 ).toURI() ) )
		{
			for ( int v = 0; v < N_VIEWS; ++v )
				for ( final String label : LABELS )
				{
					final InterestPointsN5 ip = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, v, label ) );
					ip.setInterestPoints( points( v, label ) ); // original sizes again
					ip.saveInterestPoints( false, w );
				}
		}
		store.commit();
		final long[] after = count( zarr.toPath() );
		assertTrue( after[ 0 ] <= before[ 0 ], "rewrite must not grow the store: " + after[ 0 ] + " vs " + before[ 0 ] );
		for ( int v = 0; v < N_VIEWS; ++v )
			for ( final String label : LABELS )
			{
				final InterestPoints ip = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, v, label ) );
				assertSame( points( v, label ), ip.getInterestPointsCopy() );
				assertEquals( sig( corrs( v, label ) ), sig( ip.getCorrespondingInterestPointsCopy() ), "after rewrite " + v + " " + label );
			}

		// ---- 5. per-entry save outside a batch -> staging blob, readable at once, folded by the next commit ----
		final InterestPointsN5 v4 = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 4, "beads" ) );
		final List< CorrespondingInterestPoints > fewer = new ArrayList<>( corrs( 4, "beads" ).subList( 0, 3 ) );
		v4.setCorrespondingInterestPoints( fewer );
		assertTrue( v4.saveCorrespondingInterestPoints( true ) );
		assertTrue( new File( zarr, "staging" ).isDirectory() );
		assertEquals( sig( fewer ), sig( new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 4, "beads" ) ).getCorrespondingInterestPointsCopy() ) );
		store.commit();
		final String[] leftover = new File( zarr, "staging" ).list();
		assertTrue( leftover == null || leftover.length == 0, "staging files left after commit: " + Arrays.toString( leftover ) );
		assertEquals( sig( fewer ), sig( new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 4, "beads" ) ).getCorrespondingInterestPointsCopy() ) );
		// the partner side (view 3/5) was not staged, so view 4 is authoritative for those pairs: partners now see only what view 4 lists
		final List< CorrespondingInterestPoints > seenBy3 = new ArrayList<>( new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 3, "beads" ) ).getCorrespondingInterestPointsCopy( new ViewId( 0, 4 ), "beads" ) );
		long expected3 = fewer.stream().filter( c -> c.getCorrespondingViewId().getViewSetupId() == 3 ).count();
		assertEquals( expected3, seenBy3.size() );

		// ---- 6. delete ----
		final InterestPointsN5 v5 = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 5, "beads" ) );
		assertTrue( v5.deleteInterestPoints() );
		assertTrue( v5.deleteCorrespondingInterestPoints() );
		store.commit();
		assertFalse( store.hasPoints( new Key( 0, 5, "beads" ) ) );
		assertEquals( 0, new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 5, "beads" ) ).getInterestPointsCopy().size() );
		assertEquals( 0, new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 4, "beads" ) ).getCorrespondingInterestPointsCopy( new ViewId( 0, 5 ), "beads" ).size() );
		assertSame( points( 5, "beads_split" ), new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 5, "beads_split" ) ).getInterestPointsCopy() );

		// ---- 7. two JVMs: instance B lists the store, then instance A writes a blob and later commits; B must see both ----
		final PackedInterestPointStore b = new PackedInterestPointStore( baseURI );
		assertFalse( b.hasPoints( new Key( 0, 9, "late" ) ) ); // B has listed now: no such entry
		final InterestPointsN5 late = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 9, "late" ) );
		late.setInterestPoints( points( 2, "beads" ) );
		late.setCorrespondingInterestPoints( new ArrayList<>() );
		final InterestPointsN5 late2 = new InterestPointsN5( baseURI, InterestPointsN5.createN5datasetPath( 0, 10, "late" ) );
		late2.setInterestPoints( points( 3, "beads" ) );
		late2.setCorrespondingInterestPoints( new ArrayList<>( corrs( 3, "beads" ) ) );
		InterestPointsN5.saveStaged( Arrays.asList( late, late2 ) ); // ONE staging file for both entries, via the registry instance (A)
		assertEquals( 1, new File( zarr, "staging" ).list().length, "one staging file per saveStaged call" );
		assertTrue( b.hasPoints( new Key( 0, 9, "late" ) ), "second instance must find a staging file written after its listing" );
		assertEquals( sig( corrs( 3, "beads" ) ), sig( b.correspondences( new Key( 0, 10, "late" ) ) ) );
		assertArrayEquals( PackedInterestPointStore.Points.of( points( 2, "beads" ).stream().mapToInt( InterestPoint::getId ).toArray(), points( 2, "beads" ).stream().map( InterestPoint::getL ).toArray( double[][]::new ) ).loc(),
				b.points( new Key( 0, 9, "late" ) ).loc(), 0.0 );
		store.commit(); // A folds the staging file into a new generation and deletes it
		assertEquals( 0, new File( zarr, "staging" ).list().length );
		assertArrayEquals( points( 3, "beads" ).stream().mapToInt( InterestPoint::getId ).toArray(), new PackedInterestPointStore( baseURI ).points( new Key( 0, 10, "late" ) ).ids() );
		final PackedInterestPointStore c = new PackedInterestPointStore( baseURI ); // fresh instance sees the new generation
		assertNotNull( c.points( new Key( 0, 9, "late" ) ) );
		assertEquals( 0, b.correspondences( new Key( 0, 9, "late" ) ).size(), "B must reload the index after the generation changed" );

		// re-open from disk in a fresh store instance (bypass the registry) and check the final state once more
		final PackedInterestPointStore fresh = new PackedInterestPointStore( baseURI );
		assertTrue( fresh.exists() );
		assertArrayEquals( store.points( new Key( 0, 0, "beads" ) ).loc(), fresh.points( new Key( 0, 0, "beads" ) ).loc(), 0.0 );
		assertFalse( fresh.hasPoints( new Key( 0, 5, "beads" ) ) );
	}
}
