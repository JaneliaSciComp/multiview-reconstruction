/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DoubleArrayDataBlock;
import org.janelia.saalfeldlab.n5.GsonKeyValueN5Reader;
import org.janelia.saalfeldlab.n5.IntArrayDataBlock;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.codec.checksum.Crc32cChecksumCodec;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes;
import org.janelia.scicomp.n5.zstandard.ZstandardCompression;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import util.URITools;

/**
 * Storage of all interest points and correspondences of a dataset in a few sharded Zarr v3 arrays
 * ({@code interestpoints.zarr}), instead of one N5 group per (view, label).
 *
 * Points of all (view, label)s live in one flat array addressed through a ragged index; correspondences are stored once
 * per pair of (view, label)s and addressed through a pair index. Layout (G = index generation, k = data generation):
 *
 * <pre>
 * interestpoints.zarr/zarr.json      root attributes: "interestpoints": "1.0.0", "generation": G, "pointsData", "corrData", "labels", "chunkPoints", "shardPoints"
 *   index/gG/entries    INT64 [5, E]   (tp, setup, labelId, offset, count) per (view, label)
 *   index/gG/views      INT64 [5, E]   (tp, setup, labelId, pairStart, pairCount) into pairs
 *   index/gG/pairs      INT64 [6, P]   (tpB, setupB, labelIdB, offset, count, swapped), grouped by owner
 *   points/gk/loc       FLOAT64 [3, N] shard [3, shardPoints], chunk [3, chunkPoints], zstd, crc32c shard index
 *   points/gk/id        INT32 [1, N]   detection ids (sparse for *_split labels), same grid
 *   correspondences/gk/data INT32 [3, M] (detA, detB, consensusSetId) once per pair, A = smaller key, same grid
 * interestpoints.n5/staging/tp_setup_label.points|.corr   raw blobs from per-entry saves, folded in at the next commit
 * interestpoints.n5/tpId_X_viewSetupId_Y/label/...         legacy per-view groups, readable, removed by {@link #convertLegacy(URI)}
 * </pre>
 *
 * Writes are staged in memory ({@link #stagePoints}, {@link #stageCorrespondences}, {@link #remove}) and made durable by
 * one {@link #commit()} (called from {@code XmlIoSpimData2.saveInterestPointsInParallel}). A commit appends when at least
 * {@link #minLiveFractionForAppend} of the arrays stays live, otherwise it rewrites them (compaction). New index arrays
 * are written first and the root attributes are flipped last, so a crash leaves the previous generation intact.
 * Per-entry saves outside a batch ({@link #writePointsBlob}, {@link #writeCorrespondencesBlob}) write one raw file each
 * (durable immediately, also from other JVMs); readers prefer these blobs until the next commit folds them in.
 *
 * One store instance exists per dataset directory ({@link #get(URI)}); all {@code InterestPointsN5} of a dataset share
 * it, its open readers, its index and its chunk cache.
 */
public class PackedInterestPointStore
{
	public static final String VERSION = "1.0.0";
	public static final String ZARR_CONTAINER = "interestpoints.zarr";

	/**
	 * inner chunk = read unit (points or correspondences per chunk); used when arrays are created. 64K points (1.5 MB of
	 * coordinates) was the best of 1K..64K in the /nrs sweep of 2026-09-18: on a network file system the per-request cost
	 * dominates, so few large chunk reads beat many small ones (read-all 10x, TPS 3x faster than 16K).
	 */
	public static int defaultChunkPoints = 65536;
	/** shard = file (points or correspondences per shard); rounded up to a multiple of the chunk size */
	public static int defaultShardPoints = 1 << 20;
	public static int zstdLevel = 3;
	public static double minLiveFractionForAppend = 0.75;
	public static int chunkCacheSize = 256;

	static final String STAGING = "staging";
	static final String ATTR_VERSION = "interestpoints", ATTR_GEN = "generation", ATTR_POINTS = "pointsData", ATTR_CORR = "corrData", ATTR_LABELS = "labels",
			ATTR_CHUNK = "chunkPoints", ATTR_SHARD = "shardPoints";

	/** identifies one (timepoint, setup, label) entry */
	public record Key( int tp, int setup, String label ) implements Comparable< Key >
	{
		private static final Pattern LEGACY = Pattern.compile( "tpId_(\\d+)_viewSetupId_(\\d+)/(.+)" );

		public static Key of( final ViewId v, final String label ) { return new Key( v.getTimePointId(), v.getViewSetupId(), label ); }

		/** @return the key encoded in a legacy dataset path {@code tpId_X_viewSetupId_Y/label}, or null */
		public static Key parse( final String n5dataset )
		{
			final Matcher m = LEGACY.matcher( n5dataset );
			return m.matches() ? new Key( Integer.parseInt( m.group( 1 ) ), Integer.parseInt( m.group( 2 ) ), m.group( 3 ) ) : null;
		}

		public ViewId viewId() { return new ViewId( tp, setup ); }

		@Override
		public int compareTo( final Key o )
		{
			int c = Integer.compare( tp, o.tp );
			if ( c != 0 ) return c;
			c = Integer.compare( setup, o.setup );
			return c != 0 ? c : label.compareTo( o.label );
		}
	}

	/** points of one entry: ids and flat xyz coordinates */
	public record Points( int[] ids, double[] loc )
	{
		public int size() { return ids.length; }
		public static Points of( final int[] ids, final double[][] locations )
		{
			final double[] loc = new double[ ids.length * 3 ];
			for ( int i = 0; i < ids.length; ++i ) System.arraycopy( locations[ i ], 0, loc, i * 3, 3 );
			return new Points( ids.clone(), loc );
		}
		public double[][] locations()
		{
			final double[][] l = new double[ ids.length ][];
			for ( int i = 0; i < ids.length; ++i ) l[ i ] = Arrays.copyOfRange( loc, i * 3, i * 3 + 3 );
			return l;
		}
	}

	record PairRow( Key partner, long offset, int count, boolean swapped ) {}

	/** immutable snapshot of one generation */
	static final class Index
	{
		final int generation;
		final List< String > labels;
		final String pointsData, corrData;
		final Map< Key, long[] > points; // offset, count
		final Map< Key, List< PairRow > > pairs; // owner -> rows (every key with points has an entry)
		final long nPoints, nCorr;
		final DatasetAttributes locAttrs, idAttrs, corrAttrs;

		Index( final int generation, final List< String > labels, final String pointsData, final String corrData, final Map< Key, long[] > points,
				final Map< Key, List< PairRow > > pairs, final long nPoints, final long nCorr, final DatasetAttributes locAttrs, final DatasetAttributes idAttrs, final DatasetAttributes corrAttrs )
		{
			this.generation = generation; this.labels = labels; this.pointsData = pointsData; this.corrData = corrData; this.points = points; this.pairs = pairs;
			this.nPoints = nPoints; this.nCorr = nCorr; this.locAttrs = locAttrs; this.idAttrs = idAttrs; this.corrAttrs = corrAttrs;
		}

		static Index empty() { return new Index( -1, List.of(), null, null, Map.of(), Map.of(), 0, 0, null, null, null ); }
		boolean exists() { return generation >= 0; }
	}

	// ponytail: one store per dataset directory for the whole JVM; fine as long as all SpimData2 of a base path see the same files
	private static final ConcurrentHashMap< String, PackedInterestPointStore > stores = new ConcurrentHashMap<>();

	/** @param baseDir the dataset directory (containing interestpoints.zarr / interestpoints.n5), i.e. {@code SpimData2.getBasePathURI()} */
	public static PackedInterestPointStore get( final URI baseDir )
	{
		return stores.computeIfAbsent( baseDir.toString(), k -> new PackedInterestPointStore( baseDir ) );
	}

	final URI baseDir, zarrURI, n5URI;
	private N5Writer zarrWriter = null, n5Writer = null;
	private N5Reader zarrReader = null, n5Reader = null;
	private boolean zarrReaderTried = false, n5ReaderTried = false;
	private volatile Index index = null;

	private final Object lock = new Object();
	private final Map< Key, Points > stagedPoints = new HashMap<>();
	private final Map< Key, List< CorrespondingInterestPoints > > stagedCorr = new HashMap<>();
	private final Set< Key > removedPoints = new HashSet<>(), removedCorr = new HashSet<>();
	private volatile Set< Key > blobPoints = Set.of(), blobCorr = Set.of();
	private boolean blobsListed = false;

	private final LinkedHashMap< String, Object > chunkCache = new LinkedHashMap<>( 64, 0.75f, true )
	{
		private static final long serialVersionUID = 1L;
		@Override protected boolean removeEldestEntry( final Map.Entry< String, Object > e ) { return size() > chunkCacheSize; }
	};

	/** prefer {@link #get(URI)}; a private instance does not share index and cache with the rest of the JVM */
	public PackedInterestPointStore( final URI baseDir )
	{
		this.baseDir = baseDir;
		this.zarrURI = URITools.toURI( URITools.appendName( baseDir, ZARR_CONTAINER ) );
		this.n5URI = URITools.toURI( URITools.appendName( baseDir, InterestPointsN5.baseN5 ) );
	}

	// ------------------------------------------------------------------------------------------------
	// containers
	// ------------------------------------------------------------------------------------------------

	private synchronized N5Reader zarrReader()
	{
		if ( zarrWriter != null ) return zarrWriter;
		if ( !zarrReaderTried )
		{
			zarrReaderTried = true;
			try { zarrReader = URITools.instantiateN5Reader( StorageFormat.ZARR, zarrURI ); }
			catch ( final Exception e ) { zarrReader = null; } // no store yet
		}
		return zarrReader;
	}

	private synchronized N5Writer zarrWriter()
	{
		if ( zarrWriter == null )
		{
			zarrWriter = URITools.instantiateN5Writer( StorageFormat.ZARR, zarrURI );
			zarrReader = null;
		}
		return zarrWriter;
	}

	private synchronized N5Reader n5Reader()
	{
		if ( n5Writer != null ) return n5Writer;
		if ( !n5ReaderTried )
		{
			n5ReaderTried = true;
			try { n5Reader = URITools.instantiateN5Reader( StorageFormat.N5, n5URI ); }
			catch ( final Exception e ) { n5Reader = null; }
		}
		return n5Reader;
	}

	private synchronized N5Writer n5Writer()
	{
		if ( n5Writer == null )
		{
			n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, n5URI );
			n5Reader = null;
		}
		return n5Writer;
	}

	private static KeyValueAccess kva( final N5Reader n5 ) { return ( (GsonKeyValueN5Reader) n5 ).getKeyValueAccess(); }

	/** @return true if the Zarr store exists (a dataset that was detected/converted with this version) */
	public boolean exists() { return index().exists(); }

	Index index()
	{
		Index i = index;
		if ( i == null )
		{
			synchronized ( this )
			{
				if ( index == null ) index = loadIndex();
				i = index;
			}
		}
		return i;
	}

	private Index loadIndex()
	{
		listBlobs();
		final N5Reader z = zarrReader();
		if ( z == null || z.getAttribute( "/", ATTR_VERSION, String.class ) == null )
			return Index.empty();

		final int gen = z.getAttribute( "/", ATTR_GEN, Integer.class );
		@SuppressWarnings( "unchecked" )
		final List< String > labels = new ArrayList<>( z.getAttribute( "/", ATTR_LABELS, List.class ) );
		final String pointsData = z.getAttribute( "/", ATTR_POINTS, String.class );
		final String corrData = z.getAttribute( "/", ATTR_CORR, String.class );

		final long[] idx = readLongs( z, indexGroup( gen ) + "/entries", 5 );
		final Map< Key, long[] > points = new HashMap<>();
		for ( int r = 0; r < idx.length / 5; ++r )
			points.put( new Key( (int) idx[ 5 * r ], (int) idx[ 5 * r + 1 ], labels.get( (int) idx[ 5 * r + 2 ] ) ), new long[] { idx[ 5 * r + 3 ], idx[ 5 * r + 4 ] } );

		final long[] vidx = readLongs( z, indexGroup( gen ) + "/views", 5 );
		final long[] pidx = readLongs( z, indexGroup( gen ) + "/pairs", 6 );
		final Map< Key, List< PairRow > > pairs = new HashMap<>();
		for ( int r = 0; r < vidx.length / 5; ++r )
		{
			final List< PairRow > rows = new ArrayList<>();
			for ( long p = vidx[ 5 * r + 3 ]; p < vidx[ 5 * r + 3 ] + vidx[ 5 * r + 4 ]; ++p )
			{
				final int o = (int) ( 6 * p );
				rows.add( new PairRow( new Key( (int) pidx[ o ], (int) pidx[ o + 1 ], labels.get( (int) pidx[ o + 2 ] ) ), pidx[ o + 3 ], (int) pidx[ o + 4 ], pidx[ o + 5 ] != 0 ) );
			}
			pairs.put( new Key( (int) vidx[ 5 * r ], (int) vidx[ 5 * r + 1 ], labels.get( (int) vidx[ 5 * r + 2 ] ) ), rows );
		}

		final DatasetAttributes loc = z.getDatasetAttributes( pointsData + "/loc" ), id = z.getDatasetAttributes( pointsData + "/id" ), corr = z.getDatasetAttributes( corrData + "/data" );
		return new Index( gen, labels, pointsData, corrData, points, pairs, loc.getDimensions()[ 1 ], corr.getDimensions()[ 1 ], loc, id, corr );
	}

	static String indexGroup( final int gen ) { return "index/g" + gen; }

	private void listBlobs()
	{
		if ( blobsListed ) return;
		blobsListed = true;
		final Set< Key > p = new HashSet<>(), c = new HashSet<>();
		final N5Reader n5 = n5Reader();
		if ( n5 != null )
		{
			final KeyValueAccess kva = kva( n5 );
			final String dir = kva.compose( n5URI, STAGING );
			if ( kva.exists( dir ) )
			{
				for ( final String name : kva.list( dir ) )
				{
					final int dot = name.lastIndexOf( '.' );
					if ( dot < 0 ) continue;
					final Key k = blobKey( name.substring( 0, dot ) );
					if ( k == null ) continue;
					if ( name.endsWith( ".points" ) ) p.add( k );
					else if ( name.endsWith( ".corr" ) ) c.add( k );
				}
			}
		}
		blobPoints = p;
		blobCorr = c;
	}

	static String blobName( final Key k ) { return k.tp + "_" + k.setup + "_" + k.label; }

	static Key blobKey( final String name )
	{
		final int a = name.indexOf( '_' ), b = name.indexOf( '_', a + 1 );
		if ( a < 0 || b < 0 ) return null;
		try { return new Key( Integer.parseInt( name.substring( 0, a ) ), Integer.parseInt( name.substring( a + 1, b ) ), name.substring( b + 1 ) ); }
		catch ( final NumberFormatException e ) { return null; }
	}

	// ------------------------------------------------------------------------------------------------
	// reading
	// ------------------------------------------------------------------------------------------------

	/** @return true if this store knows the entry (staged, staging blob or stored); false means: try the legacy group */
	public boolean hasPoints( final Key k )
	{
		synchronized ( lock )
		{
			if ( removedPoints.contains( k ) ) return false;
			if ( stagedPoints.containsKey( k ) ) return true;
		}
		return blobPointsSet().contains( k ) || index().points.containsKey( k );
	}

	public boolean hasCorrespondences( final Key k )
	{
		synchronized ( lock )
		{
			if ( removedCorr.contains( k ) ) return false;
			if ( stagedCorr.containsKey( k ) ) return true;
		}
		return blobCorrSet().contains( k ) || index().pairs.containsKey( k );
	}

	private Set< Key > blobPointsSet() { index(); return blobPoints; }
	private Set< Key > blobCorrSet() { index(); return blobCorr; }

	/** @return the points of an entry, or null if unknown to this store */
	public Points points( final Key k )
	{
		synchronized ( lock )
		{
			if ( removedPoints.contains( k ) ) return null;
			final Points s = stagedPoints.get( k );
			if ( s != null ) return s;
		}
		if ( blobPointsSet().contains( k ) ) return readPointsBlob( k );
		final Index idx = index();
		final long[] r = idx.points.get( k );
		if ( r == null ) return null;
		return readPoints( idx, r[ 0 ], (int) r[ 1 ] );
	}

	/** @return all correspondences of an entry (both directions, like the legacy per-view list), or null if unknown */
	public List< CorrespondingInterestPoints > correspondences( final Key k )
	{
		synchronized ( lock )
		{
			if ( removedCorr.contains( k ) ) return null;
			final List< CorrespondingInterestPoints > s = stagedCorr.get( k );
			if ( s != null ) return new ArrayList<>( s );
		}
		if ( blobCorrSet().contains( k ) ) return readCorrBlob( k );
		final Index idx = index();
		final List< PairRow > rows = idx.pairs.get( k );
		if ( rows == null ) return null;
		final ArrayList< CorrespondingInterestPoints > out = new ArrayList<>();
		for ( final PairRow row : rows ) appendRange( idx, row, out );
		return out;
	}

	/** @return the correspondences of an entry to one partner (view, label) only; null if the entry is unknown */
	public List< CorrespondingInterestPoints > correspondences( final Key k, final ViewId partner, final String partnerLabel )
	{
		final Key p = Key.of( partner, partnerLabel );
		synchronized ( lock )
		{
			if ( removedCorr.contains( k ) ) return null;
			final List< CorrespondingInterestPoints > s = stagedCorr.get( k );
			if ( s != null ) return filter( s, p );
		}
		if ( blobCorrSet().contains( k ) ) return filter( readCorrBlob( k ), p );
		final Index idx = index();
		final List< PairRow > rows = idx.pairs.get( k );
		if ( rows == null ) return null;
		final ArrayList< CorrespondingInterestPoints > out = new ArrayList<>();
		for ( final PairRow row : rows )
			if ( row.partner.equals( p ) ) appendRange( idx, row, out );
		return out;
	}

	/** @return the (view, label)s this entry has correspondences with; null if the entry is unknown */
	public Set< Pair< ViewId, String > > correspondingViews( final Key k )
	{
		List< CorrespondingInterestPoints > full = null;
		synchronized ( lock )
		{
			if ( removedCorr.contains( k ) ) return null;
			full = stagedCorr.get( k );
		}
		if ( full == null && blobCorrSet().contains( k ) ) full = readCorrBlob( k );
		final Set< Pair< ViewId, String > > out = new HashSet<>();
		if ( full != null )
		{
			for ( final CorrespondingInterestPoints c : full ) out.add( new ValuePair<>( c.getCorrespondingViewId(), c.getCorrespodingLabel() ) );
			return out;
		}
		final List< PairRow > rows = index().pairs.get( k );
		if ( rows == null ) return null;
		for ( final PairRow row : rows ) out.add( new ValuePair<>( row.partner.viewId(), row.partner.label ) );
		return out;
	}

	private static List< CorrespondingInterestPoints > filter( final List< CorrespondingInterestPoints > l, final Key partner )
	{
		final ArrayList< CorrespondingInterestPoints > out = new ArrayList<>();
		for ( final CorrespondingInterestPoints c : l )
			if ( c.getCorrespondingViewId().getTimePointId() == partner.tp && c.getCorrespondingViewId().getViewSetupId() == partner.setup && c.getCorrespodingLabel().equals( partner.label ) )
				out.add( c );
		return out;
	}

	private Points readPoints( final Index idx, final long off, final int n )
	{
		final int[] ids = new int[ n ];
		final double[] loc = new double[ n * 3 ];
		final int C = idx.locAttrs.getChunkSize()[ 1 ];
		for ( long c = off / C; c * C < off + n; ++c )
		{
			final double[] lchunk = (double[]) chunk( idx.pointsData + "/loc", idx.locAttrs, c );
			final int[] ichunk = (int[]) chunk( idx.pointsData + "/id", idx.idAttrs, c );
			final long cs0 = c * C, cs = Math.max( cs0, off ), ce = Math.min( Math.min( cs0 + C, idx.nPoints ), off + n );
			System.arraycopy( lchunk, (int) ( cs - cs0 ) * 3, loc, (int) ( cs - off ) * 3, (int) ( ce - cs ) * 3 );
			System.arraycopy( ichunk, (int) ( cs - cs0 ), ids, (int) ( cs - off ), (int) ( ce - cs ) );
		}
		return new Points( ids, loc );
	}

	private void appendRange( final Index idx, final PairRow row, final List< CorrespondingInterestPoints > out )
	{
		final int C = idx.corrAttrs.getChunkSize()[ 1 ];
		final long off = row.offset;
		final ViewId partner = row.partner.viewId();
		for ( long c = off / C; c * C < off + row.count; ++c )
		{
			final int[] chunk = (int[]) chunk( idx.corrData + "/data", idx.corrAttrs, c );
			final long cs0 = c * C, cs = Math.max( cs0, off ), ce = Math.min( Math.min( cs0 + C, idx.nCorr ), off + row.count );
			for ( long j = cs; j < ce; ++j )
			{
				final int o = (int) ( j - cs0 ) * 3;
				final int a = chunk[ o ], b = chunk[ o + 1 ], set = chunk[ o + 2 ];
				out.add( row.swapped
						? new CorrespondingInterestPoints( b, partner, row.partner.label, a, set )
						: new CorrespondingInterestPoints( a, partner, row.partner.label, b, set ) );
			}
		}
	}

	/** one inner chunk (read through the shard index), cached */
	private Object chunk( final String dataset, final DatasetAttributes attrs, final long c )
	{
		final String key = dataset + "#" + c;
		synchronized ( chunkCache )
		{
			final Object o = chunkCache.get( key );
			if ( o != null ) return o;
		}
		final Object data = zarrReader().readChunk( dataset, attrs, 0, c ).getData();
		synchronized ( chunkCache ) { chunkCache.put( key, data ); }
		return data;
	}

	private void clearCache() { synchronized ( chunkCache ) { chunkCache.clear(); } }

	// ------------------------------------------------------------------------------------------------
	// staging (in memory) and per-entry blobs (durable)
	// ------------------------------------------------------------------------------------------------

	public void stagePoints( final Key k, final int[] ids, final double[][] locations )
	{
		final Points p = Points.of( ids, locations );
		synchronized ( lock ) { stagedPoints.put( k, p ); removedPoints.remove( k ); }
	}

	public void stageCorrespondences( final Key k, final Collection< CorrespondingInterestPoints > list )
	{
		final ArrayList< CorrespondingInterestPoints > l = new ArrayList<>( list.size() );
		for ( final CorrespondingInterestPoints c : list ) l.add( new CorrespondingInterestPoints( c ) );
		synchronized ( lock ) { stagedCorr.put( k, l ); removedCorr.remove( k ); }
	}

	/** marks points and correspondences of an entry for removal at the next commit */
	public void remove( final Key k )
	{
		synchronized ( lock )
		{
			stagedPoints.remove( k ); stagedCorr.remove( k );
			removedPoints.add( k ); removedCorr.add( k );
		}
	}

	public boolean hasPendingChanges()
	{
		synchronized ( lock ) { return !stagedPoints.isEmpty() || !stagedCorr.isEmpty() || !removedPoints.isEmpty() || !removedCorr.isEmpty(); }
	}

	private String blobPath( final KeyValueAccess kva, final Key k, final String ext ) { return kva.compose( n5URI, STAGING, blobName( k ) + ext ); }

	/** durable per-entry save: one raw file under interestpoints.n5/staging/, folded into the arrays by the next commit */
	public void writePointsBlob( final Key k, final int[] ids, final double[][] locations )
	{
		final Points p = Points.of( ids, locations );
		final KeyValueAccess kva = kva( n5Writer() );
		kva.createDirectories( kva.compose( n5URI, STAGING ) );
		try ( final LockedChannel ch = kva.lockForWriting( blobPath( kva, k, ".points" ) );
				final DataOutputStream out = new DataOutputStream( ch.newOutputStream() ) )
		{
			out.writeInt( p.size() );
			for ( final int id : p.ids ) out.writeInt( id );
			for ( final double v : p.loc ) out.writeDouble( v );
		}
		catch ( final IOException e ) { throw new RuntimeException( "could not write staging blob for " + k, e ); }
		synchronized ( lock )
		{
			stagedPoints.remove( k ); removedPoints.remove( k );
			final Set< Key > s = new HashSet<>( blobPointsSet() ); s.add( k ); blobPoints = s;
		}
	}

	public void writeCorrespondencesBlob( final Key k, final Collection< CorrespondingInterestPoints > list )
	{
		final KeyValueAccess kva = kva( n5Writer() );
		kva.createDirectories( kva.compose( n5URI, STAGING ) );
		try ( final LockedChannel ch = kva.lockForWriting( blobPath( kva, k, ".corr" ) );
				final DataOutputStream out = new DataOutputStream( ch.newOutputStream() ) )
		{
			final List< String > labels = new ArrayList<>();
			for ( final CorrespondingInterestPoints c : list ) if ( !labels.contains( c.getCorrespodingLabel() ) ) labels.add( c.getCorrespodingLabel() );
			out.writeInt( labels.size() );
			for ( final String l : labels ) out.writeUTF( l );
			out.writeInt( list.size() );
			for ( final CorrespondingInterestPoints c : list )
			{
				out.writeInt( c.getDetectionId() );
				out.writeInt( c.getCorrespondingViewId().getTimePointId() );
				out.writeInt( c.getCorrespondingViewId().getViewSetupId() );
				out.writeInt( labels.indexOf( c.getCorrespodingLabel() ) );
				out.writeInt( c.getCorrespondingDetectionId() );
				out.writeInt( c.getConsensusSetId() );
			}
		}
		catch ( final IOException e ) { throw new RuntimeException( "could not write staging blob for " + k, e ); }
		synchronized ( lock )
		{
			stagedCorr.remove( k ); removedCorr.remove( k );
			final Set< Key > s = new HashSet<>( blobCorrSet() ); s.add( k ); blobCorr = s;
		}
	}

	private Points readPointsBlob( final Key k )
	{
		final KeyValueAccess kva = kva( n5Reader() );
		try ( final LockedChannel ch = kva.lockForReading( blobPath( kva, k, ".points" ) );
				final DataInputStream in = new DataInputStream( ch.newInputStream() ) )
		{
			final int n = in.readInt();
			final int[] ids = new int[ n ];
			final double[] loc = new double[ n * 3 ];
			for ( int i = 0; i < n; ++i ) ids[ i ] = in.readInt();
			for ( int i = 0; i < loc.length; ++i ) loc[ i ] = in.readDouble();
			return new Points( ids, loc );
		}
		catch ( final IOException e ) { throw new RuntimeException( "could not read staging blob for " + k, e ); }
	}

	private List< CorrespondingInterestPoints > readCorrBlob( final Key k )
	{
		final KeyValueAccess kva = kva( n5Reader() );
		try ( final LockedChannel ch = kva.lockForReading( blobPath( kva, k, ".corr" ) );
				final DataInputStream in = new DataInputStream( ch.newInputStream() ) )
		{
			final String[] labels = new String[ in.readInt() ];
			for ( int i = 0; i < labels.length; ++i ) labels[ i ] = in.readUTF();
			final int n = in.readInt();
			final ArrayList< CorrespondingInterestPoints > out = new ArrayList<>( n );
			for ( int i = 0; i < n; ++i )
			{
				final int det = in.readInt(), tp = in.readInt(), setup = in.readInt(), l = in.readInt(), cdet = in.readInt(), set = in.readInt();
				out.add( new CorrespondingInterestPoints( det, tp, setup, labels[ l ], cdet, set ) );
			}
			return out;
		}
		catch ( final IOException e ) { throw new RuntimeException( "could not read staging blob for " + k, e ); }
	}

	// ------------------------------------------------------------------------------------------------
	// commit
	// ------------------------------------------------------------------------------------------------

	/**
	 * Makes all staged changes and staging blobs durable: appends if enough of the current arrays stays live, otherwise
	 * rewrites them; writes the next-generation indices; flips the root attributes.
	 */
	public void commit()
	{
		synchronized ( lock )
		{
			final Index old = index();

			// fold staging blobs (per-entry saves) into the staged maps; in-memory staging wins if both exist
			for ( final Key k : blobPointsSet() ) if ( !stagedPoints.containsKey( k ) && !removedPoints.contains( k ) ) stagedPoints.put( k, readPointsBlob( k ) );
			for ( final Key k : blobCorrSet() ) if ( !stagedCorr.containsKey( k ) && !removedCorr.contains( k ) ) stagedCorr.put( k, readCorrBlob( k ) );

			if ( stagedPoints.isEmpty() && stagedCorr.isEmpty() && removedPoints.isEmpty() && removedCorr.isEmpty() )
				return;

			final long t0 = System.currentTimeMillis();
			final N5Writer w = zarrWriter();
			final List< String > labels = new ArrayList<>( old.labels );
			final Map< String, Integer > labelId = new HashMap<>();
			for ( int i = 0; i < labels.size(); ++i ) labelId.put( labels.get( i ), i );
			// grid of existing arrays, or the defaults for a new store
			final int chunk = old.exists() ? old.locAttrs.getChunkSize()[ 1 ] : Math.max( 1, defaultChunkPoints );
			final int shard = old.exists() ? old.locAttrs.getBlockSize()[ 1 ] : roundUp( Math.max( chunk, defaultShardPoints ), chunk );

			// ---------------- points ----------------
			final TreeMap< Key, long[] > newPoints = new TreeMap<>();
			final List< Key > kept = new ArrayList<>();
			long keptCount = 0, newCount = 0;
			for ( final Map.Entry< Key, long[] > e : old.points.entrySet() )
				if ( !stagedPoints.containsKey( e.getKey() ) && !removedPoints.contains( e.getKey() ) ) { kept.add( e.getKey() ); keptCount += e.getValue()[ 1 ]; }
			for ( final Points p : stagedPoints.values() ) newCount += p.size();

			final boolean appendPts = old.pointsData != null && ( keptCount + newCount ) >= minLiveFractionForAppend * ( old.nPoints + newCount );
			final String pointsData;
			final long ptsStart;
			final List< Key > toWrite = new ArrayList<>( stagedPoints.keySet() );
			Collections.sort( toWrite );
			final List< Points > toWriteData = new ArrayList<>();
			if ( appendPts )
			{
				pointsData = old.pointsData;
				ptsStart = old.nPoints;
				for ( final Key k : kept ) newPoints.put( k, old.points.get( k ) );
			}
			else
			{
				pointsData = "points/g" + ( old.generation + 1 );
				ptsStart = 0;
				Collections.sort( kept );
				toWrite.addAll( 0, kept ); // kept first, then new
			}
			for ( final Key k : toWrite )
				toWriteData.add( stagedPoints.containsKey( k ) ? stagedPoints.get( k ) : readPoints( old, old.points.get( k )[ 0 ], (int) old.points.get( k )[ 1 ] ) );
			long off = ptsStart;
			for ( int i = 0; i < toWrite.size(); ++i )
			{
				newPoints.put( toWrite.get( i ), new long[] { off, toWriteData.get( i ).size() } );
				off += toWriteData.get( i ).size();
			}
			final long nPointsNew = off;
			writePoints( w, pointsData, appendPts ? old : null, ptsStart, toWriteData, nPointsNew, shard, chunk );

			// ---------------- correspondences ----------------
			// pair data keyed by canonical (a < b); rows oriented so column 0 = a's detection
			final TreeMap< Key, TreeMap< Key, int[][] > > pairData = new TreeMap<>();
			final Set< Key > authoritative = new HashSet<>( stagedCorr.keySet() );
			authoritative.addAll( removedCorr );
			long keptCorr = 0;
			final List< Object[] > keptRows = new ArrayList<>(); // {a, b, PairRow}
			for ( final Map.Entry< Key, List< PairRow > > e : old.pairs.entrySet() )
				for ( final PairRow row : e.getValue() )
					if ( !row.swapped && !authoritative.contains( e.getKey() ) && !authoritative.contains( row.partner ) )
					{ keptRows.add( new Object[] { e.getKey(), row.partner, row } ); keptCorr += row.count; }
			long newCorr = 0;
			final Map< Key, Map< Key, List< CorrespondingInterestPoints > > > stagedByPartner = new HashMap<>();
			for ( final Map.Entry< Key, List< CorrespondingInterestPoints > > e : stagedCorr.entrySet() )
			{
				final Map< Key, List< CorrespondingInterestPoints > > byPartner = new HashMap<>();
				for ( final CorrespondingInterestPoints c : e.getValue() )
					byPartner.computeIfAbsent( Key.of( c.getCorrespondingViewId(), c.getCorrespodingLabel() ), x -> new ArrayList<>() ).add( c );
				stagedByPartner.put( e.getKey(), byPartner );
			}
			for ( final Map.Entry< Key, Map< Key, List< CorrespondingInterestPoints > > > e : stagedByPartner.entrySet() )
			{
				final Key k = e.getKey();
				for ( final Map.Entry< Key, List< CorrespondingInterestPoints > > pe : e.getValue().entrySet() )
				{
					final Key partner = pe.getKey();
					if ( removedCorr.contains( partner ) ) continue;
					final boolean canonical = k.compareTo( partner ) <= 0;
					if ( !canonical && stagedCorr.containsKey( partner ) ) continue; // both staged: the canonical side defines the pair
					final Key a = canonical ? k : partner, b = canonical ? partner : k;
					final List< CorrespondingInterestPoints > l = pe.getValue();
					final int[][] d = new int[ 3 ][ l.size() ];
					for ( int i = 0; i < l.size(); ++i )
					{
						d[ canonical ? 0 : 1 ][ i ] = l.get( i ).getDetectionId();
						d[ canonical ? 1 : 0 ][ i ] = l.get( i ).getCorrespondingDetectionId();
						d[ 2 ][ i ] = l.get( i ).getConsensusSetId();
					}
					pairData.computeIfAbsent( a, x -> new TreeMap<>() ).put( b, d );
					newCorr += l.size();
					if ( !labelId.containsKey( a.label ) ) { labelId.put( a.label, labels.size() ); labels.add( a.label ); }
					if ( !labelId.containsKey( b.label ) ) { labelId.put( b.label, labels.size() ); labels.add( b.label ); }
				}
			}
			final boolean appendCorr = old.corrData != null && ( keptCorr + newCorr ) >= minLiveFractionForAppend * ( old.nCorr + newCorr );
			final String corrData = appendCorr ? old.corrData : "correspondences/g" + ( old.generation + 1 );
			final Map< Key, List< PairRow > > newPairs = new HashMap<>();
			final List< int[][] > corrToWrite = new ArrayList<>();
			long coff = appendCorr ? old.nCorr : 0;
			if ( !appendCorr )
				for ( final Object[] kr : keptRows )
				{
					final PairRow row = (PairRow) kr[ 2 ];
					final ArrayList< CorrespondingInterestPoints > tmp = new ArrayList<>( row.count );
					appendRange( old, row, tmp );
					final int[][] d = new int[ 3 ][ row.count ];
					for ( int i = 0; i < row.count; ++i ) { d[ 0 ][ i ] = tmp.get( i ).getDetectionId(); d[ 1 ][ i ] = tmp.get( i ).getCorrespondingDetectionId(); d[ 2 ][ i ] = tmp.get( i ).getConsensusSetId(); }
					corrToWrite.add( d );
					addPair( newPairs, (Key) kr[ 0 ], (Key) kr[ 1 ], coff, row.count );
					coff += row.count;
				}
			else
				for ( final Object[] kr : keptRows )
					addPair( newPairs, (Key) kr[ 0 ], (Key) kr[ 1 ], ( (PairRow) kr[ 2 ] ).offset, ( (PairRow) kr[ 2 ] ).count );
			for ( final Map.Entry< Key, TreeMap< Key, int[][] > > e : pairData.entrySet() )
				for ( final Map.Entry< Key, int[][] > pe : e.getValue().entrySet() )
				{
					corrToWrite.add( pe.getValue() );
					addPair( newPairs, e.getKey(), pe.getKey(), coff, pe.getValue()[ 0 ].length );
					coff += pe.getValue()[ 0 ].length;
				}
			final long nCorrNew = coff;
			writeCorr( w, corrData, appendCorr ? old : null, appendCorr ? old.nCorr : 0, corrToWrite, nCorrNew, shard, chunk );

			// every entry with points (or staged correspondences) gets a views row, even with 0 pairs
			for ( final Key k : newPoints.keySet() ) newPairs.putIfAbsent( k, new ArrayList<>() );
			for ( final Key k : stagedCorr.keySet() ) if ( !removedCorr.contains( k ) ) newPairs.putIfAbsent( k, new ArrayList<>() );
			for ( final Key k : removedCorr ) newPairs.remove( k );
			for ( final Key k : newPairs.keySet() ) if ( !labelId.containsKey( k.label ) ) { labelId.put( k.label, labels.size() ); labels.add( k.label ); }

			// ---------------- indices + flip ----------------
			final int gen = old.generation + 1;
			final long[] idx = new long[ 5 * newPoints.size() ];
			int r = 0;
			for ( final Map.Entry< Key, long[] > e : newPoints.entrySet() )
			{
				idx[ 5 * r ] = e.getKey().tp; idx[ 5 * r + 1 ] = e.getKey().setup; idx[ 5 * r + 2 ] = labelId.get( e.getKey().label );
				idx[ 5 * r + 3 ] = e.getValue()[ 0 ]; idx[ 5 * r + 4 ] = e.getValue()[ 1 ];
				++r;
			}
			final TreeMap< Key, List< PairRow > > sortedPairs = new TreeMap<>( newPairs );
			final long[] vidx = new long[ 5 * sortedPairs.size() ];
			final ArrayList< Long > pidxList = new ArrayList<>();
			r = 0;
			for ( final Map.Entry< Key, List< PairRow > > e : sortedPairs.entrySet() )
			{
				vidx[ 5 * r ] = e.getKey().tp; vidx[ 5 * r + 1 ] = e.getKey().setup; vidx[ 5 * r + 2 ] = labelId.get( e.getKey().label );
				vidx[ 5 * r + 3 ] = pidxList.size() / 6; vidx[ 5 * r + 4 ] = e.getValue().size();
				for ( final PairRow row : e.getValue() )
				{
					pidxList.add( (long) row.partner.tp ); pidxList.add( (long) row.partner.setup ); pidxList.add( (long) labelId.get( row.partner.label ) );
					pidxList.add( row.offset ); pidxList.add( (long) row.count ); pidxList.add( row.swapped ? 1L : 0L );
				}
				++r;
			}
			final long[] pidx = new long[ pidxList.size() ];
			for ( int i = 0; i < pidx.length; ++i ) pidx[ i ] = pidxList.get( i );

			writeLongs( w, indexGroup( gen ) + "/entries", 5, idx );
			writeLongs( w, indexGroup( gen ) + "/views", 5, vidx );
			writeLongs( w, indexGroup( gen ) + "/pairs", 6, pidx );

			final Map< String, Object > attrs = new HashMap<>();
			attrs.put( ATTR_VERSION, VERSION ); attrs.put( ATTR_GEN, gen ); attrs.put( ATTR_POINTS, pointsData ); attrs.put( ATTR_CORR, corrData ); attrs.put( ATTR_LABELS, labels );
			attrs.put( ATTR_CHUNK, chunk ); attrs.put( ATTR_SHARD, shard );
			w.setAttributes( "/", attrs ); // the commit point

			// ---------------- cleanup of the previous generation ----------------
			if ( old.exists() )
			{
				if ( w.exists( indexGroup( old.generation ) ) ) w.remove( indexGroup( old.generation ) );
				if ( !appendPts && w.exists( old.pointsData ) ) w.remove( old.pointsData );
				if ( !appendCorr && w.exists( old.corrData ) ) w.remove( old.corrData );
			}
			if ( !blobPoints.isEmpty() || !blobCorr.isEmpty() )
			{
				final KeyValueAccess kva = kva( n5Writer() );
				for ( final Key k : blobPoints ) { final String p = blobPath( kva, k, ".points" ); if ( kva.exists( p ) ) kva.delete( p ); }
				for ( final Key k : blobCorr ) { final String p = blobPath( kva, k, ".corr" ); if ( kva.exists( p ) ) kva.delete( p ); }
				blobPoints = Set.of(); blobCorr = Set.of();
			}

			final int nStaged = stagedPoints.size(), nStagedCorr = stagedCorr.size();
			stagedPoints.clear(); stagedCorr.clear(); removedPoints.clear(); removedCorr.clear();
			clearCache();
			final DatasetAttributes loc = w.getDatasetAttributes( pointsData + "/loc" ), id = w.getDatasetAttributes( pointsData + "/id" ), corr = w.getDatasetAttributes( corrData + "/data" );
			index = new Index( gen, labels, pointsData, corrData, newPoints, newPairs, nPointsNew, nCorrNew, loc, id, corr );

			IOFunctions.println( "PackedInterestPointStore: committed generation " + gen + " (" + nStaged + " point entries, " + nStagedCorr + " correspondence entries, points "
					+ ( appendPts ? "appended" : "rewritten" ) + ", correspondences " + ( appendCorr ? "appended" : "rewritten" ) + ", " + newPoints.size() + " entries / " + nPointsNew
					+ " points / " + nCorrNew + " correspondences total, chunk " + chunk + " / shard " + shard + ") in " + ( System.currentTimeMillis() - t0 ) + " ms" );
		}
	}

	private static int roundUp( final int v, final int multiple ) { return ( ( v + multiple - 1 ) / multiple ) * multiple; }

	private static void addPair( final Map< Key, List< PairRow > > pairs, final Key a, final Key b, final long offset, final int count )
	{
		pairs.computeIfAbsent( a, x -> new ArrayList<>() ).add( new PairRow( b, offset, count, false ) );
		if ( !a.equals( b ) ) pairs.computeIfAbsent( b, x -> new ArrayList<>() ).add( new PairRow( a, offset, count, true ) );
	}

	// ------------------------------------------------------------------------------------------------
	// Zarr v3 arrays
	// ------------------------------------------------------------------------------------------------

	/** sharded array [cols, n]: shard [cols, shard], inner chunk [cols, chunk], zstd, crc32c shard index */
	static DatasetAttributes arrayAttrs( final int cols, final long n, final DataType type, final int shard, final int chunk )
	{
		return ZarrV3DatasetAttributes.builder( new long[] { cols, n }, type )
				.blockSize( new int[] { cols, shard } )
				.chunkSize( new int[] { cols, chunk } )
				.compression( new ZstandardCompression( zstdLevel ) )
				.shardIndexDataCodecInfos( new Crc32cChecksumCodec() )
				.build();
	}

	interface ChunkFiller< T > { DataBlock< T > chunk( long cs, int n ); }

	/**
	 * Writes the range [start, end) of a sharded array shard by shard (the inner chunks of one shard in one writeChunks
	 * call; the library merges into an existing partial shard). {@code filler} produces the complete chunk [cs, cs + n);
	 * for a first chunk that also holds old data ({@code cs < start}) the caller includes that data.
	 */
	private static < T > void writeRange( final N5Writer w, final String ds, final DatasetAttributes attrs, final long start, final long end, final ChunkFiller< T > filler )
	{
		if ( end <= start ) return;
		final int shard = attrs.getBlockSize()[ 1 ], chunk = attrs.getChunkSize()[ 1 ];
		final long total = attrs.getDimensions()[ 1 ];
		final long s0 = start / shard, s1 = ( end - 1 ) / shard;
		final ForkJoinPool pool = new ForkJoinPool( Threads.numThreads() );
		try
		{
			pool.submit( () -> LongStream.rangeClosed( s0, s1 ).parallel().forEach( s -> {
				final List< DataBlock< T > > chunks = new ArrayList<>();
				final long shardStart = s * shard, shardEnd = Math.min( total, shardStart + shard );
				for ( long c = Math.max( shardStart, ( start / chunk ) * chunk ); c < Math.min( shardEnd, end ); c += chunk )
					chunks.add( filler.chunk( c, (int) ( Math.min( c + chunk, shardEnd ) - c ) ) );
				@SuppressWarnings( "unchecked" )
				final DataBlock< T >[] arr = chunks.toArray( new DataBlock[ 0 ] );
				w.writeChunks( ds, attrs, arr );
			}) ).get();
		}
		catch ( InterruptedException | ExecutionException e ) { throw new RuntimeException( "writing " + ds + " failed", e ); }
		finally { pool.shutdown(); }
	}

	/** writes entries consecutively from {@code start}; creates the arrays if {@code old == null}, else appends */
	private void writePoints( final N5Writer w, final String group, final Index old, final long start, final List< Points > entries, final long total, final int shard, final int chunk )
	{
		final DatasetAttributes loc = arrayAttrs( 3, total, DataType.FLOAT64, shard, chunk ), id = arrayAttrs( 1, total, DataType.INT32, shard, chunk );
		if ( old == null )
		{
			if ( w.exists( group ) ) w.remove( group );
			w.createDataset( group + "/loc", loc );
			w.createDataset( group + "/id", id );
		}
		else
		{
			w.setDatasetAttributes( group + "/loc", loc );
			w.setDatasetAttributes( group + "/id", id );
		}
		final long[] offsets = new long[ entries.size() ];
		long o = start;
		for ( int i = 0; i < entries.size(); ++i ) { offsets[ i ] = o; o += entries.get( i ).size(); }
		final long end = o;
		// old content of the first (partial) chunk
		final long firstChunk = ( start / chunk ) * chunk;
		final boolean partial = old != null && firstChunk < start;
		final double[] oldLoc = partial ? (double[]) chunk( old.pointsData + "/loc", old.locAttrs, firstChunk / chunk ) : null;
		final int[] oldId = partial ? (int[]) chunk( old.pointsData + "/id", old.idAttrs, firstChunk / chunk ) : null;

		writeRange( w, group + "/loc", loc, start, end, ( cs, n ) -> {
			final double[] buf = new double[ n * 3 ];
			if ( cs < start ) System.arraycopy( oldLoc, 0, buf, 0, (int) ( start - cs ) * 3 );
			forEntries( entries, offsets, cs, n, ( e, es, from, to ) -> System.arraycopy( e.loc, (int) ( from - es ) * 3, buf, (int) ( from - cs ) * 3, (int) ( to - from ) * 3 ) );
			return new DoubleArrayDataBlock( new int[] { 3, n }, new long[] { 0, cs / chunk }, buf );
		} );
		writeRange( w, group + "/id", id, start, end, ( cs, n ) -> {
			final int[] buf = new int[ n ];
			if ( cs < start ) System.arraycopy( oldId, 0, buf, 0, (int) ( start - cs ) );
			forEntries( entries, offsets, cs, n, ( e, es, from, to ) -> System.arraycopy( e.ids, (int) ( from - es ), buf, (int) ( from - cs ), (int) ( to - from ) ) );
			return new IntArrayDataBlock( new int[] { 1, n }, new long[] { 0, cs / chunk }, buf );
		} );
	}

	interface EntryVisitor { void visit( Points e, long entryStart, long from, long to ); }

	/** calls the visitor for every entry overlapping the chunk [cs, cs + n) */
	private static void forEntries( final List< Points > entries, final long[] offsets, final long cs, final int n, final EntryVisitor v )
	{
		int e = Arrays.binarySearch( offsets, cs );
		if ( e < 0 ) e = Math.max( 0, -e - 2 );
		for ( ; e < entries.size() && offsets[ e ] < cs + n; ++e )
		{
			final long es = offsets[ e ], ee = es + entries.get( e ).size();
			final long from = Math.max( es, cs ), to = Math.min( ee, cs + n );
			if ( to > from ) v.visit( entries.get( e ), es, from, to );
		}
	}

	private void writeCorr( final N5Writer w, final String group, final Index old, final long start, final List< int[][] > entries, final long total, final int shard, final int chunk )
	{
		final DatasetAttributes attrs = arrayAttrs( 3, total, DataType.INT32, shard, chunk );
		if ( old == null )
		{
			if ( w.exists( group ) ) w.remove( group );
			w.createDataset( group + "/data", attrs );
		}
		else
			w.setDatasetAttributes( group + "/data", attrs );
		final long[] offsets = new long[ entries.size() ];
		long o = start;
		for ( int i = 0; i < entries.size(); ++i ) { offsets[ i ] = o; o += entries.get( i )[ 0 ].length; }
		final long end = o;
		final long firstChunk = ( start / chunk ) * chunk;
		final int[] oldData = ( old != null && firstChunk < start ) ? (int[]) chunk( old.corrData + "/data", old.corrAttrs, firstChunk / chunk ) : null;

		writeRange( w, group + "/data", attrs, start, end, ( cs, n ) -> {
			final int[] buf = new int[ n * 3 ];
			if ( cs < start ) System.arraycopy( oldData, 0, buf, 0, (int) ( start - cs ) * 3 );
			int e = Arrays.binarySearch( offsets, cs );
			if ( e < 0 ) e = Math.max( 0, -e - 2 );
			for ( ; e < entries.size() && offsets[ e ] < cs + n; ++e )
			{
				final int[][] d = entries.get( e );
				final long es = offsets[ e ], ee = es + d[ 0 ].length;
				for ( long j = Math.max( es, cs ); j < Math.min( ee, cs + n ); ++j )
					for ( int c = 0; c < 3; ++c ) buf[ (int) ( j - cs ) * 3 + c ] = d[ c ][ (int) ( j - es ) ];
			}
			return new IntArrayDataBlock( new int[] { 3, n }, new long[] { 0, cs / chunk }, buf );
		} );
	}

	/** small unsharded index array [cols, rows] in one chunk */
	private static void writeLongs( final N5Writer w, final String ds, final int cols, final long[] v )
	{
		final int rows = v.length / cols;
		final DatasetAttributes a = ZarrV3DatasetAttributes.builder( new long[] { cols, rows }, DataType.INT64 )
				.blockSize( new int[] { cols, Math.max( 1, rows ) } ).compression( new ZstandardCompression( zstdLevel ) ).build();
		if ( w.exists( ds ) ) w.remove( ds );
		w.createDataset( ds, a );
		if ( rows > 0 )
			w.writeBlock( ds, a, new LongArrayDataBlock( new int[] { cols, rows }, new long[] { 0, 0 }, v ) );
	}

	private static long[] readLongs( final N5Reader n5, final String ds, final int cols )
	{
		final DatasetAttributes a = n5.getDatasetAttributes( ds );
		final long rows = a.getDimensions()[ 1 ];
		if ( rows == 0 ) return new long[ 0 ];
		final long[] out = new long[ (int) ( cols * rows ) ];
		final long[] blk = (long[]) n5.readBlock( ds, a, 0, 0 ).getData();
		System.arraycopy( blk, 0, out, 0, out.length );
		return out;
	}

	// ------------------------------------------------------------------------------------------------
	// legacy conversion
	// ------------------------------------------------------------------------------------------------

	/**
	 * Packs every legacy per-view group ({@code interestpoints.n5/tpId_X_viewSetupId_Y/label}) into the store and deletes
	 * the groups. Safe to run again on a partially converted dataset.
	 *
	 * @param baseDir the dataset directory
	 * @return number of converted entries
	 */
	public static int convertLegacy( final URI baseDir )
	{
		final PackedInterestPointStore store = get( baseDir );
		final N5Reader n5 = store.n5Reader();
		if ( n5 == null ) return 0;
		final List< String > groups = new ArrayList<>();
		for ( final String g : n5.list( "/" ) )
			if ( g.startsWith( "tpId_" ) && g.contains( "_viewSetupId_" ) )
				for ( final String label : n5.list( g ) )
					groups.add( g + "/" + label );
		Collections.sort( groups );
		if ( groups.isEmpty() ) return 0;
		IOFunctions.println( "PackedInterestPointStore: converting " + groups.size() + " legacy interest point groups of " + store.n5URI + " into " + store.zarrURI );

		final ForkJoinPool pool = new ForkJoinPool( Threads.numThreads() );
		try
		{
			pool.submit( () -> IntStream.range( 0, groups.size() ).parallel().forEach( i -> {
				final String path = groups.get( i );
				final Key k = Key.parse( path );
				if ( k == null ) return;
				final InterestPointsN5 ip = new InterestPointsN5( baseDir, path );
				if ( n5.exists( InterestPointsN5.ipDataset( path ) ) && ip.loadLegacyInterestPoints() )
					store.stagePoints( k, ip.ids, ip.locations );
				if ( n5.exists( InterestPointsN5.corrDataset( path ) ) && ip.loadLegacyCorrespondences() )
					store.stageCorrespondences( k, ip.correspondingInterestPoints );
			}) ).get();
		}
		catch ( InterruptedException | ExecutionException e ) { throw new RuntimeException( "reading legacy interest points failed", e ); }
		finally { pool.shutdown(); }

		store.commit();

		final N5Writer w = store.n5Writer();
		final Set< String > viewGroups = new HashSet<>();
		for ( final String g : groups ) viewGroups.add( g.substring( 0, g.indexOf( '/' ) ) );
		final ForkJoinPool pool2 = new ForkJoinPool( Threads.numThreads() );
		try { pool2.submit( () -> viewGroups.parallelStream().forEach( g -> w.remove( g ) ) ).get(); }
		catch ( InterruptedException | ExecutionException e ) { throw new RuntimeException( "removing legacy interest point groups failed", e ); }
		finally { pool2.shutdown(); }
		IOFunctions.println( "PackedInterestPointStore: removed " + viewGroups.size() + " legacy view groups" );
		return groups.size();
	}

	/** java ... PackedInterestPointStore &lt;dataset.xml | dataset directory&gt; [chunkPoints shardPoints] */
	public static void main( final String[] args )
	{
		File dir = new File( args[ 0 ] );
		if ( dir.isFile() ) dir = dir.getParentFile();
		if ( args.length > 2 ) { defaultChunkPoints = Integer.parseInt( args[ 1 ] ); defaultShardPoints = Integer.parseInt( args[ 2 ] ); }
		final long t0 = System.currentTimeMillis();
		final int n = convertLegacy( dir.toURI() );
		System.out.println( "converted " + n + " entries in " + ( System.currentTimeMillis() - t0 ) + " ms" );
	}
}
