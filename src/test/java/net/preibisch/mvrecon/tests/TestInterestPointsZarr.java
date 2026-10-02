package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.img.array.ArrayImgs;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import util.URITools;

public class TestInterestPointsZarr
{
	@TempDir
	Path tmp;

	static final String[] LABELS = { "beads", "beads_split" };
	static final int VIEW_COUNT = 6;

	/** Synthetic points of a view. Ids are sparse for beads_split (like SplittingTools), dense otherwise; view 3 has none. */
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
			points.add( new InterestPoint( id, location ) );
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

	/** Writes a view's points and correspondences in the legacy per-view format. */
	static void writeLegacy( final N5Writer writer, final int view, final String label )
	{
		final List< InterestPoint > points = points( view, label );
		final int[] ids = points.stream().mapToInt( InterestPoint::getId ).toArray();
		final double[][] locations = points.stream().map( InterestPoint::getL ).toArray( double[][]::new );
		final String path = InterestPointsN5.createN5datasetPath( 0, view, label );
		InterestPointsN5.saveInterestPointsStatic( writer, path, ids, locations );
		InterestPointsN5.saveCorrespondencesStatic( writer, path, correspondences( view, label ) );
	}

	static N5Writer legacyWriter( final File base )
	{
		return URITools.instantiateN5Writer( StorageFormat.N5, new File( base, InterestPointsN5.baseN5 ).toURI() );
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

	static void assertSamePoints( final Collection< InterestPoint > expected, final Map< Integer, InterestPoint > actual )
	{
		assertEquals( expected.size(), actual.size() );
		for ( final InterestPoint point : expected )
		{
			final InterestPoint loaded = actual.get( point.getId() );
			assertNotNull( loaded, "missing id " + point.getId() );
			assertArrayEquals( point.getL(), loaded.getL(), 0.0 );
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
			checksum += 7L * c.getConsensusSetId();
			checksum += c.getCorrespodingLabel().hashCode();
		}
		return checksum;
	}

	@Test
	public void legacyConvertAppendRewriteStagingDelete() throws Exception
	{
		final File base = tmp.resolve( "dataset" ).toFile();
		base.mkdirs();
		final URI baseURI = base.toURI();
		InterestPointsZarrStore.defaultChunkPoints = 16; // a tiny grid: entries (5 to 45 points) cross chunk and shard borders
		InterestPointsZarrStore.defaultShardPoints = 64;
		final File zarr = new File( base, InterestPointsZarrStore.ZARR_CONTAINER );
		final File legacy = new File( base, InterestPointsN5.baseN5 );

		// ---- 1. legacy groups, readable before the conversion ----
		try ( final N5Writer writer = legacyWriter( base ) )
		{
			for ( int view = 0; view < VIEW_COUNT; ++view )
				for ( final String label : LABELS )
					writeLegacy( writer, view, label );
		}
		final long[] legacyCounts = countFilesAndDirectories( legacy.toPath() );
		assertFalse( InterestPointsZarrStore.get( baseURI ).exists() );

		for ( int view = 0; view < VIEW_COUNT; ++view )
			for ( final String label : LABELS )
			{
				final InterestPoints list = list( baseURI, view, label );
				assertSamePoints( points( view, label ), list.getInterestPointsCopy() );
				assertEquals( checksum( correspondences( view, label ) ), checksum( list.getCorrespondingInterestPointsCopy() ) );
			}

		// ---- 2. convert ----
		assertEquals( VIEW_COUNT * LABELS.length, InterestPointsN5ToZarr.convert( baseURI ) );
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseURI );
		assertTrue( store.exists() );

		final long[] zarrCounts = countFilesAndDirectories( zarr.toPath() );
		final long[] legacyCountsAfter = countFilesAndDirectories( legacy.toPath() );
		assertTrue( zarrCounts[ 0 ] + legacyCountsAfter[ 0 ] < legacyCounts[ 0 ] / 3, "files " + zarrCounts[ 0 ] + "+" + legacyCountsAfter[ 0 ] + " vs legacy " + legacyCounts[ 0 ] );
		// 187 points / 64 per shard = 3 shards each for loc and id, 1 for correspondences, 3 index arrays, one zarr.json per group or array
		assertTrue( zarrCounts[ 0 ] < 30, "files in zarr " + zarrCounts[ 0 ] );

		final String locJson = Files.readString( zarr.toPath().resolve( "points/g0/loc/zarr.json" ) ).replaceAll( "\\s+", "" );
		assertTrue( locJson.contains( "\"shape\":[187,3]" ), locJson ); // Zarr order: N5 dimensions [3, N] appear as [N, 3]
		assertTrue( locJson.contains( "\"chunk_shape\":[64,3]" ), locJson );
		assertTrue( locJson.contains( "\"sharding_indexed\"" ), locJson );
		assertTrue( locJson.contains( "\"chunk_shape\":[16,3]" ), locJson );
		assertTrue( locJson.contains( "\"name\":\"crc32c\"" ), locJson );
		assertFalse( locJson.contains( "zstd" ), locJson );

		try ( final N5Writer writer = legacyWriter( base ) )
		{
			assertFalse( writer.exists( InterestPointsN5.createN5datasetPath( 0, 0, "beads" ) ) );
		}

		for ( int view = 0; view < VIEW_COUNT; ++view )
			for ( final String label : LABELS )
			{
				final InterestPoints list = list( baseURI, view, label );
				assertSamePoints( points( view, label ), list.getInterestPointsCopy() );
				assertEquals( checksum( correspondences( view, label ) ), checksum( list.getCorrespondingInterestPointsCopy() ), "correspondences " + view + " " + label );

				// reading one pair gives the same as filtering the full list
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
				assertEquals( partnerCount, list.getCorrespondingViews().size() );
			}

		// ---- 3. append: more points for one view and a new label, saved in a batch ----
		final List< InterestPoint > morePoints = new ArrayList<>( points( 1, "beads" ) );
		for ( int i = 0; i < 100; ++i )
			morePoints.add( new InterestPoint( 1000 + i, new double[] { i, 2 * i, 3 * i } ) );

		final InterestPointsZarr view1 = list( baseURI, 1, "beads" );
		view1.setInterestPoints( morePoints );
		final InterestPointsZarr newLabel = list( baseURI, 2, "nuclei" );
		newLabel.setInterestPoints( points( 2, "beads_split" ) );
		newLabel.setCorrespondingInterestPoints( new ArrayList<>() );

		store.beginBatch(); // like XmlIoSpimData2.saveInterestPointsInParallel
		assertTrue( view1.saveInterestPoints( false ) );
		assertTrue( newLabel.saveInterestPoints( false ) );
		assertTrue( newLabel.saveCorrespondingInterestPoints( false ) );
		store.commit();

		assertSamePoints( morePoints, list( baseURI, 1, "beads" ).getInterestPointsCopy() );
		assertSamePoints( points( 2, "beads_split" ), list( baseURI, 2, "nuclei" ).getInterestPointsCopy() );
		assertEquals( 0, list( baseURI, 2, "nuclei" ).getCorrespondingInterestPointsCopy().size() );
		// untouched entries and their correspondences, including the pairs with view 1, are kept
		assertSamePoints( points( 0, "beads" ), list( baseURI, 0, "beads" ).getInterestPointsCopy() );
		assertEquals( checksum( correspondences( 1, "beads" ) ), checksum( list( baseURI, 1, "beads" ).getCorrespondingInterestPointsCopy() ) );

		// ---- 4. rewrite: new points for every entry, so nothing of the old array stays in use ----
		final long[] countsBefore = countFilesAndDirectories( zarr.toPath() );
		store.beginBatch();
		for ( int view = 0; view < VIEW_COUNT; ++view )
			for ( final String label : LABELS )
			{
				final InterestPointsZarr list = list( baseURI, view, label );
				list.setInterestPoints( points( view, label ) ); // the original sizes again
				list.saveInterestPoints( false );
			}
		store.commit();

		final long[] countsAfter = countFilesAndDirectories( zarr.toPath() );
		assertTrue( countsAfter[ 0 ] <= countsBefore[ 0 ], "a rewrite must not grow the store: " + countsAfter[ 0 ] + " vs " + countsBefore[ 0 ] );
		for ( int view = 0; view < VIEW_COUNT; ++view )
			for ( final String label : LABELS )
			{
				final InterestPoints list = list( baseURI, view, label );
				assertSamePoints( points( view, label ), list.getInterestPointsCopy() );
				assertEquals( checksum( correspondences( view, label ) ), checksum( list.getCorrespondingInterestPointsCopy() ), "after rewrite " + view + " " + label );
			}

		// ---- 5. a save outside a batch writes a staging file: readable at once, folded in by the next commit ----
		final InterestPointsZarr view4 = list( baseURI, 4, "beads" );
		final List< CorrespondingInterestPoints > fewer = new ArrayList<>( correspondences( 4, "beads" ).subList( 0, 3 ) );
		view4.setCorrespondingInterestPoints( fewer );
		assertTrue( view4.saveCorrespondingInterestPoints( true ) );
		assertTrue( new File( zarr, "staging" ).isDirectory() );
		assertEquals( checksum( fewer ), checksum( list( baseURI, 4, "beads" ).getCorrespondingInterestPointsCopy() ) );

		store.commit();
		final String[] leftover = new File( zarr, "staging" ).list();
		assertTrue( leftover == null || leftover.length == 0, "staging files left after commit: " + Arrays.toString( leftover ) );
		assertEquals( checksum( fewer ), checksum( list( baseURI, 4, "beads" ).getCorrespondingInterestPointsCopy() ) );

		// view 4 was saved and its partners were not, so view 4 decides those pairs: view 3 sees only what view 4 lists
		final Collection< CorrespondingInterestPoints > seenByView3 = list( baseURI, 3, "beads" ).getCorrespondingInterestPointsCopy( new ViewId( 0, 4 ), "beads" );
		final long expectedForView3 = fewer.stream().filter( c -> c.getCorrespondingViewId().getViewSetupId() == 3 ).count();
		assertEquals( expectedForView3, seenByView3.size() );

		// ---- 6. delete ----
		final InterestPointsZarr view5 = list( baseURI, 5, "beads" );
		assertTrue( view5.deleteInterestPoints() );
		assertTrue( view5.deleteCorrespondingInterestPoints() );
		store.commit();
		assertFalse( store.hasPoints( new Key( 0, 5, "beads" ) ) );
		assertEquals( 0, list( baseURI, 5, "beads" ).getInterestPointsCopy().size() );
		assertEquals( 0, list( baseURI, 4, "beads" ).getCorrespondingInterestPointsCopy( new ViewId( 0, 5 ), "beads" ).size() );
		assertSamePoints( points( 5, "beads_split" ), list( baseURI, 5, "beads_split" ).getInterestPointsCopy() );

		// ---- 7. two JVMs: store B lists, then store A writes a staging file and later commits; B must see both ----
		final InterestPointsZarrStore storeB = new InterestPointsZarrStore( baseURI );
		final Key late9 = new Key( 0, 9, "late" );
		final Key late10 = new Key( 0, 10, "late" );
		assertFalse( storeB.hasPoints( late9 ) ); // B has listed the staging directory now

		final InterestPointsZarr lateList9 = list( baseURI, 9, "late" );
		lateList9.setInterestPoints( points( 2, "beads" ) );
		lateList9.setCorrespondingInterestPoints( new ArrayList<>() );
		final InterestPointsZarr lateList10 = list( baseURI, 10, "late" );
		lateList10.setInterestPoints( points( 3, "beads" ) );
		lateList10.setCorrespondingInterestPoints( new ArrayList<>( correspondences( 3, "beads" ) ) );
		InterestPointsZarr.saveStaged( Arrays.asList( lateList9, lateList10 ) ); // through the shared store A

		assertEquals( 1, new File( zarr, "staging" ).list().length, "one staging file per saveStaged call" );
		assertTrue( storeB.hasPoints( late9 ), "B must find a staging file written after its listing" );
		assertEquals( checksum( correspondences( 3, "beads" ) ), checksum( storeB.correspondences( late10 ) ) );
		final double[] expectedLoc = InterestPointsZarrStore.Points.of(
				points( 2, "beads" ).stream().mapToInt( InterestPoint::getId ).toArray(),
				points( 2, "beads" ).stream().map( InterestPoint::getL ).toArray( double[][]::new ) ).loc();
		assertArrayEquals( expectedLoc, storeB.points( late9 ).loc(), 0.0 );

		store.commit(); // A folds the staging file into a new generation and deletes it
		assertEquals( 0, new File( zarr, "staging" ).list().length );
		assertArrayEquals( points( 3, "beads" ).stream().mapToInt( InterestPoint::getId ).toArray(), new InterestPointsZarrStore( baseURI ).points( late10 ).ids() );
		assertNotNull( new InterestPointsZarrStore( baseURI ).points( late9 ) );
		assertEquals( 0, storeB.correspondences( late9 ).size(), "B must reload the index after the generation changed" );

		// a new store instance (not from the registry) reads the final state from disk
		final InterestPointsZarrStore fresh = new InterestPointsZarrStore( baseURI );
		assertTrue( fresh.exists() );
		assertArrayEquals( store.points( new Key( 0, 0, "beads" ) ).loc(), fresh.points( new Key( 0, 0, "beads" ) ).loc(), 0.0 );
		assertFalse( fresh.hasPoints( new Key( 0, 5, "beads" ) ) );
	}

	static double[] randomValues( final int view, final String name, final int count )
	{
		final Random random = new Random( 17 * view + name.hashCode() );
		final double[] values = new double[ count ];
		for ( int i = 0; i < count; ++i )
			values[ i ] = random.nextDouble() * 1000;

		return values;
	}

	static String pointsData( final File base ) { return rootAttribute( base, "pointsData", String.class ); }

	static List< ? > pointAttributes( final File base ) { return rootAttribute( base, "pointAttributes", List.class ); }

	static < T > T rootAttribute( final File base, final String name, final Class< T > type )
	{
		try ( final N5Reader reader = URITools.instantiateN5Reader( StorageFormat.ZARR, new File( base, InterestPointsZarrStore.ZARR_CONTAINER ).toURI() ) )
		{
			return reader.getAttribute( "/", name, type );
		}
	}

	@Test
	public void pointAttributes() throws Exception
	{
		final File base = tmp.resolve( "attributes" ).toFile();
		base.mkdirs();
		final URI baseURI = base.toURI();
		InterestPointsZarrStore.defaultChunkPoints = 16; // entries cross chunk borders
		InterestPointsZarrStore.defaultShardPoints = 64;
		final double defaultMinLive = InterestPointsZarrStore.minLiveFractionForAppend;
		final String intensity = InterestPointsZarr.INTENSITY;

		// ---- 1. legacy groups; view 0 has the float32 intensities that BigStitcher-Spark wrote ----
		final double[] legacyIntensities = new double[ points( 0, "beads" ).size() ];
		try ( final N5Writer writer = legacyWriter( base ) )
		{
			for ( int view = 0; view < VIEW_COUNT; ++view )
				writeLegacy( writer, view, "beads" );

			final float[] values = new float[ legacyIntensities.length ];
			for ( int i = 0; i < values.length; ++i )
			{
				values[ i ] = 10.5f * i;
				legacyIntensities[ i ] = values[ i ];
			}
			final String dataset = InterestPointsN5.ipDataset( InterestPointsN5.createN5datasetPath( 0, 0, "beads" ) ) + "/intensities";
			N5Utils.save( ArrayImgs.floats( values, 1, values.length ), writer, dataset, new int[] { 1, values.length }, new GzipCompression() );
		}
		assertArrayEquals( legacyIntensities, list( baseURI, 0, "beads" ).getAttributeCopy( intensity ), 0.0, "legacy intensities are readable" );

		InterestPointsN5ToZarr.convert( baseURI );
		final Key view0 = new Key( 0, 0, "beads" );
		final Key view1 = new Key( 0, 1, "beads" );
		final Key view2 = new Key( 0, 2, "beads" );
		assertEquals( List.of( intensity ), pointAttributes( base ), "one attribute column" );
		assertArrayEquals( legacyIntensities, new InterestPointsZarrStore( baseURI ).points( view0 ).attributes().get( intensity ), 0.0, "conversion keeps intensities" );
		assertTrue( new InterestPointsZarrStore( baseURI ).points( view1 ).attributes().isEmpty(), "-1 everywhere = no attribute" );
		assertSamePoints( points( 1, "beads" ), list( baseURI, 1, "beads" ).getInterestPointsCopy() );

		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseURI );
		try
		{
			// ---- 2. a staging file with an existing column: readable before the commit, appended by it ----
			InterestPointsZarrStore.minLiveFractionForAppend = 0; // append whenever the columns allow it
			final double[] view2Intensities = randomValues( 2, intensity, points( 2, "beads" ).size() );
			final InterestPointsZarr view2List = list( baseURI, 2, "beads" );
			view2List.setInterestPoints( points( 2, "beads" ), Map.of( intensity, view2Intensities ) );
			InterestPointsZarr.saveStaged( List.of( view2List ) );
			assertArrayEquals( view2Intensities, new InterestPointsZarrStore( baseURI ).points( view2 ).attributes().get( intensity ), 0.0, "from the staging file" );

			final String pointsDataBefore = pointsData( base );
			store.commit();
			assertEquals( pointsDataBefore, pointsData( base ), "same columns: appended" );
			assertArrayEquals( view2Intensities, new InterestPointsZarrStore( baseURI ).points( view2 ).attributes().get( intensity ), 0.0, "from the arrays" );

			// ---- 3. a new attribute name changes the shape of loc: rewritten, although appending is allowed ----
			final double[] view1Sizes = randomValues( 1, "size", points( 1, "beads" ).size() );
			final InterestPointsZarr view1List = list( baseURI, 1, "beads" );
			view1List.setInterestPoints( points( 1, "beads" ), Map.of( "size", view1Sizes ) );
			store.beginBatch();
			view1List.saveInterestPoints( false );
			store.commit();

			assertNotEquals( pointsDataBefore, pointsData( base ), "new column: rewritten" );
			assertEquals( List.of( intensity, "size" ), pointAttributes( base ) );
			InterestPointsZarrStore fresh = new InterestPointsZarrStore( baseURI );
			assertEquals( Set.of( "size" ), fresh.points( view1 ).attributes().keySet() );
			assertArrayEquals( view1Sizes, fresh.points( view1 ).attributes().get( "size" ), 0.0 );
			assertEquals( Set.of( intensity ), fresh.points( view0 ).attributes().keySet() );
			assertArrayEquals( legacyIntensities, fresh.points( view0 ).attributes().get( intensity ), 0.0 );
			assertArrayEquals( view2Intensities, fresh.points( view2 ).attributes().get( intensity ), 0.0 );
			assertSamePoints( points( 1, "beads" ), list( baseURI, 1, "beads" ).getInterestPointsCopy() );
			assertSamePoints( points( 4, "beads" ), list( baseURI, 4, "beads" ).getInterestPointsCopy() );

			// ---- 4. a rewrite drops columns that no entry uses ----
			InterestPointsZarrStore.minLiveFractionForAppend = defaultMinLive;
			store.remove( view0 );
			store.remove( view2 );
			store.commit();

			assertEquals( List.of( "size" ), pointAttributes( base ) );
			fresh = new InterestPointsZarrStore( baseURI );
			assertFalse( fresh.hasPoints( view0 ) );
			assertArrayEquals( view1Sizes, fresh.points( view1 ).attributes().get( "size" ), 0.0 );
			assertSamePoints( points( 5, "beads" ), list( baseURI, 5, "beads" ).getInterestPointsCopy() );
		}
		finally
		{
			InterestPointsZarrStore.minLiveFractionForAppend = defaultMinLive;
		}

		// ---- 5. new points drop the attributes; wrong input is rejected ----
		final InterestPointsZarr view1List = list( baseURI, 1, "beads" );
		assertEquals( Set.of( "size" ), view1List.getAttributeNames() );
		view1List.setInterestPoints( points( 1, "beads" ) );
		assertTrue( view1List.getAttributeNames().isEmpty() );

		final int view1Count = points( 1, "beads" ).size();
		assertThrows( IllegalArgumentException.class, () -> view1List.setInterestPoints( points( 1, "beads" ), Map.of( "size", new double[ 1 ] ) ) );
		assertThrows( IllegalArgumentException.class, () -> view1List.setInterestPoints( points( 1, "beads" ), Map.of( "a/b", new double[ view1Count ] ) ) );
	}
}
