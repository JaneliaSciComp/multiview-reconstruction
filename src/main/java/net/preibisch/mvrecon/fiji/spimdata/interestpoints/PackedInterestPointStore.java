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

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DoubleArrayDataBlock;
import org.janelia.saalfeldlab.n5.GsonKeyValueN5Reader;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.IntArrayDataBlock;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import util.URITools;

/**
 * Packed storage of all interest points and correspondences of a dataset inside {@code interestpoints.n5}.
 *
 * Instead of one N5 group per (view, label) (~13 files and 12 directories each), all points live in one flat
 * array addressed through a ragged index, and correspondences are stored once per pair of (view, label)s and
 * addressed through a pair index. Layout (G = generation, k = data generation):
 *
 * <pre>
 * interestpoints.n5/attributes.json      "packed": "1.0.0", "generation": G, "pointsData", "corrData", "labels"
 *   packed/index_G        INT64 [5, E]   (tp, setup, labelId, offset, count) per (view, label)
 *   packed/viewIndex_G    INT64 [5, E]   (tp, setup, labelId, pairStart, pairCount) into pairIndex
 *   packed/pairIndex_G    INT64 [6, P]   (tpB, setupB, labelIdB, offset, count, swapped) grouped by owner
 *   packed/points/dk/id   INT32 [1, N]   detection ids (sparse for *_split labels)
 *   packed/points/dk/loc  FLOAT64 [3, N]
 *   packed/corr/dk/data   INT32 [3, M]   (detA, detB, consensusSetId) once per pair, A = canonical smaller key
 *   staging/tp_setup_label.points|.corr  raw blobs from per-entry saves, folded into packed/ on the next commit
 *   tpId_X_viewSetupId_Y/label/...       legacy per-view groups, readable, removed by {@link #convertLegacy(URI)}
 * </pre>
 *
 * Writes are staged in memory ({@link #stagePoints}, {@link #stageCorrespondences}, {@link #remove}) and made
 * durable by one {@link #commit()} (called from {@code XmlIoSpimData2.saveInterestPointsInParallel}). A commit
 * appends when the data that stays live is at least {@link #minLiveFractionForAppend} of the array, otherwise it
 * rewrites the arrays (compaction). New index datasets are written first and the root attributes are flipped
 * last, so a crash leaves the previous generation intact.
 *
 * Per-entry saves outside a batch ({@link #writePointsBlob}, {@link #writeCorrespondencesBlob}) write one raw
 * file each and are durable immediately; readers prefer these blobs over packed data until the next commit
 * folds them in.
 *
 * One store instance exists per container URI ({@link #get(URI)}); all {@code InterestPointsN5} of a dataset
 * share it, its open reader, its index and its block cache.
 */
public class PackedInterestPointStore
{
	public static final String VERSION = "1.0.0";
	public static int defaultBlockSize = 16384; // points / correspondences per block
	public static double minLiveFractionForAppend = 0.75;
	public static int blockCacheSize = 256; // blocks (16K points * 24 B = ~400 KB each)

	static final String PACKED = "packed", STAGING = "staging";
	static final String ATTR_VERSION = "packed", ATTR_GEN = "generation", ATTR_POINTS = "pointsData", ATTR_CORR = "corrData", ATTR_LABELS = "labels";

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
		boolean isPacked() { return generation >= 0; }
	}

	// ponytail: one store per container URI for the whole JVM; fine as long as all SpimData2 of a base path see the same files
	private static final ConcurrentHashMap< String, PackedInterestPointStore > stores = new ConcurrentHashMap<>();

	/** @param baseDir the dataset directory (containing interestpoints.n5), i.e. {@code SpimData2.getBasePathURI()} */
	public static PackedInterestPointStore get( final URI baseDir )
	{
		final URI container = URITools.toURI( URITools.appendName( baseDir, InterestPointsN5.baseN5 ) );
		return stores.computeIfAbsent( container.toString(), k -> new PackedInterestPointStore( container ) );
	}

	final URI containerURI;
	private N5Writer writer = null;
	private N5Reader reader = null;
	private boolean readerTried = false;
	private volatile Index index = null;

	private final Object lock = new Object();
	private final Map< Key, Points > stagedPoints = new HashMap<>();
	private final Map< Key, List< CorrespondingInterestPoints > > stagedCorr = new HashMap<>();
	private final Set< Key > removedPoints = new HashSet<>(), removedCorr = new HashSet<>();
	private volatile Set< Key > blobPoints = Set.of(), blobCorr = Set.of();
	private boolean blobsListed = false;

	private final LinkedHashMap< String, Object > blockCache = new LinkedHashMap<>( 64, 0.75f, true )
	{
		private static final long serialVersionUID = 1L;
		@Override protected boolean removeEldestEntry( final Map.Entry< String, Object > e ) { return size() > blockCacheSize; }
	};

	/** prefer {@link #get(URI)}; a private instance does not share index and cache with the rest of the JVM */
	public PackedInterestPointStore( final URI containerURI ) { this.containerURI = containerURI; }

	// ------------------------------------------------------------------------------------------------
	// opening
	// ------------------------------------------------------------------------------------------------

	private synchronized N5Reader reader()
	{
		if ( writer != null ) return writer;
		if ( !readerTried )
		{
			readerTried = true;
			try { reader = URITools.instantiateN5Reader( StorageFormat.N5, containerURI ); }
			catch ( final Exception e ) { reader = null; } // no container yet
		}
		return reader;
	}

	private synchronized N5Writer writer()
	{
		if ( writer == null )
		{
			writer = URITools.instantiateN5Writer( StorageFormat.N5, containerURI );
			reader = null;
		}
		return writer;
	}

	private KeyValueAccess kva( final N5Reader n5 ) { return ( (GsonKeyValueN5Reader) n5 ).getKeyValueAccess(); }

	public boolean isPacked() { return index().isPacked(); }

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
		final N5Reader n5 = reader();
		listBlobs( n5 );
		if ( n5 == null || n5.getAttribute( "/", ATTR_VERSION, String.class ) == null )
			return Index.empty();

		final int gen = n5.getAttribute( "/", ATTR_GEN, Integer.class );
		@SuppressWarnings( "unchecked" )
		final List< String > labels = new ArrayList<>( n5.getAttribute( "/", ATTR_LABELS, List.class ) );
		final String pointsData = n5.getAttribute( "/", ATTR_POINTS, String.class );
		final String corrData = n5.getAttribute( "/", ATTR_CORR, String.class );

		final long[] idx = readLongs( n5, PACKED + "/index_" + gen, 5 );
		final Map< Key, long[] > points = new HashMap<>();
		for ( int r = 0; r < idx.length / 5; ++r )
			points.put( new Key( (int) idx[ 5 * r ], (int) idx[ 5 * r + 1 ], labels.get( (int) idx[ 5 * r + 2 ] ) ), new long[] { idx[ 5 * r + 3 ], idx[ 5 * r + 4 ] } );

		final long[] vidx = readLongs( n5, PACKED + "/viewIndex_" + gen, 5 );
		final long[] pidx = readLongs( n5, PACKED + "/pairIndex_" + gen, 6 );
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

		final DatasetAttributes loc = n5.getDatasetAttributes( pointsData + "/loc" ), id = n5.getDatasetAttributes( pointsData + "/id" ), corr = n5.getDatasetAttributes( corrData + "/data" );
		return new Index( gen, labels, pointsData, corrData, points, pairs, loc.getDimensions()[ 1 ], corr.getDimensions()[ 1 ], loc, id, corr );
	}

	private void listBlobs( final N5Reader n5 )
	{
		if ( blobsListed ) return;
		blobsListed = true;
		final Set< Key > p = new HashSet<>(), c = new HashSet<>();
		if ( n5 != null )
		{
			final KeyValueAccess kva = kva( n5 );
			final String dir = kva.compose( containerURI, STAGING );
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

	/** @return true if this store knows the entry (staged, staging blob or packed); false means: try the legacy group */
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
		final int B = idx.locAttrs.getBlockSize()[ 1 ];
		for ( long b = off / B; b * B < off + n; ++b )
		{
			final double[] lblk = (double[]) block( idx.pointsData + "/loc", idx.locAttrs, b );
			final int[] iblk = (int[]) block( idx.pointsData + "/id", idx.idAttrs, b );
			final long bs = b * B, cs = Math.max( bs, off ), ce = Math.min( bs + iblk.length, off + n );
			System.arraycopy( lblk, (int) ( cs - bs ) * 3, loc, (int) ( cs - off ) * 3, (int) ( ce - cs ) * 3 );
			System.arraycopy( iblk, (int) ( cs - bs ), ids, (int) ( cs - off ), (int) ( ce - cs ) );
		}
		return new Points( ids, loc );
	}

	private void appendRange( final Index idx, final PairRow row, final List< CorrespondingInterestPoints > out )
	{
		final int B = idx.corrAttrs.getBlockSize()[ 1 ];
		final long off = row.offset;
		final ViewId partner = row.partner.viewId();
		for ( long b = off / B; b * B < off + row.count; ++b )
		{
			final int[] blk = (int[]) block( idx.corrData + "/data", idx.corrAttrs, b );
			final long bs = b * B, cs = Math.max( bs, off ), ce = Math.min( bs + blk.length / 3, off + row.count );
			for ( long j = cs; j < ce; ++j )
			{
				final int o = (int) ( j - bs ) * 3;
				final int a = blk[ o ], bb = blk[ o + 1 ], set = blk[ o + 2 ];
				out.add( row.swapped
						? new CorrespondingInterestPoints( bb, partner, row.partner.label, a, set )
						: new CorrespondingInterestPoints( a, partner, row.partner.label, bb, set ) );
			}
		}
	}

	private Object block( final String dataset, final DatasetAttributes attrs, final long b )
	{
		final String key = dataset + "#" + b;
		synchronized ( blockCache )
		{
			final Object o = blockCache.get( key );
			if ( o != null ) return o;
		}
		final Object data = reader().readBlock( dataset, attrs, 0, b ).getData();
		synchronized ( blockCache ) { blockCache.put( key, data ); }
		return data;
	}

	private void clearCache() { synchronized ( blockCache ) { blockCache.clear(); } }

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

	/** durable per-entry save: one raw file under staging/, folded into the packed arrays by the next commit */
	public void writePointsBlob( final Key k, final int[] ids, final double[][] locations )
	{
		final Points p = Points.of( ids, locations );
		final N5Writer w = writer();
		final KeyValueAccess kva = kva( w );
		kva.createDirectories( kva.compose( containerURI, STAGING ) );
		try ( final LockedChannel ch = kva.lockForWriting( kva.compose( containerURI, STAGING, blobName( k ) + ".points" ) );
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
		final N5Writer w = writer();
		final KeyValueAccess kva = kva( w );
		kva.createDirectories( kva.compose( containerURI, STAGING ) );
		try ( final LockedChannel ch = kva.lockForWriting( kva.compose( containerURI, STAGING, blobName( k ) + ".corr" ) );
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
		final KeyValueAccess kva = kva( reader() );
		try ( final LockedChannel ch = kva.lockForReading( kva.compose( containerURI, STAGING, blobName( k ) + ".points" ) );
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
		final KeyValueAccess kva = kva( reader() );
		try ( final LockedChannel ch = kva.lockForReading( kva.compose( containerURI, STAGING, blobName( k ) + ".corr" ) );
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
	 * Makes all staged changes and staging blobs durable in the packed arrays: appends if enough of the current
	 * arrays stays live, otherwise rewrites them; writes the next-generation indices; flips the root attributes.
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
			final N5Writer w = writer();
			final List< String > labels = new ArrayList<>( old.labels );
			final Map< String, Integer > labelId = new HashMap<>();
			for ( int i = 0; i < labels.size(); ++i ) labelId.put( labels.get( i ), i );

			// ---------------- points ----------------
			final TreeMap< Key, long[] > newPoints = new TreeMap<>(); // final index
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
				pointsData = PACKED + "/points/d" + ( old.generation + 1 );
				ptsStart = 0;
				Collections.sort( kept );
				toWrite.addAll( 0, kept ); // kept first, then new
			}
			for ( final Key k : toWrite )
			{
				final Points p = stagedPoints.containsKey( k ) ? stagedPoints.get( k ) : readPoints( old, old.points.get( k )[ 0 ], (int) old.points.get( k )[ 1 ] );
				toWriteData.add( p );
			}
			long off = ptsStart;
			for ( int i = 0; i < toWrite.size(); ++i )
			{
				newPoints.put( toWrite.get( i ), new long[] { off, toWriteData.get( i ).size() } );
				off += toWriteData.get( i ).size();
			}
			final long nPointsNew = off;
			writePoints( w, pointsData, appendPts ? old.locAttrs : null, appendPts ? old.idAttrs : null, ptsStart, toWriteData, nPointsNew );

			// ---------------- correspondences ----------------
			// pair data keyed by canonical (a < b); rows oriented so column 0 = a's detection
			final TreeMap< Key, TreeMap< Key, int[][] > > pairData = new TreeMap<>(); // a -> b -> data (new or copied)
			final Set< Key > authoritative = new HashSet<>( stagedCorr.keySet() );
			authoritative.addAll( removedCorr );
			// old pairs where neither side changed: keep (copied out of the old data if rewriting)
			long keptCorr = 0;
			final List< Object[] > keptRows = new ArrayList<>(); // {a, b, PairRow}
			for ( final Map.Entry< Key, List< PairRow > > e : old.pairs.entrySet() )
				for ( final PairRow row : e.getValue() )
					if ( !row.swapped && !authoritative.contains( e.getKey() ) && !authoritative.contains( row.partner ) )
					{ keptRows.add( new Object[] { e.getKey(), row.partner, row } ); keptCorr += row.count; }
			// staged sides
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
			final String corrData = appendCorr ? old.corrData : PACKED + "/corr/d" + ( old.generation + 1 );
			// rows of the new pair index: owner -> rows
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
			writeCorr( w, corrData, appendCorr ? old.corrAttrs : null, appendCorr ? old.nCorr : 0, corrToWrite, nCorrNew );

			// every entry with points (or staged correspondences) gets a viewIndex row, even with 0 pairs
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

			writeLongs( w, PACKED + "/index_" + gen, 5, idx );
			writeLongs( w, PACKED + "/viewIndex_" + gen, 5, vidx );
			writeLongs( w, PACKED + "/pairIndex_" + gen, 6, pidx );

			final Map< String, Object > attrs = new HashMap<>();
			attrs.put( ATTR_VERSION, VERSION ); attrs.put( ATTR_GEN, gen ); attrs.put( ATTR_POINTS, pointsData ); attrs.put( ATTR_CORR, corrData ); attrs.put( ATTR_LABELS, labels );
			w.setAttributes( "/", attrs ); // the commit point

			// ---------------- cleanup of the previous generation ----------------
			if ( old.isPacked() )
			{
				for ( final String ds : new String[] { PACKED + "/index_" + old.generation, PACKED + "/viewIndex_" + old.generation, PACKED + "/pairIndex_" + old.generation } )
					if ( w.exists( ds ) ) w.remove( ds );
				if ( !appendPts && w.exists( old.pointsData ) ) w.remove( old.pointsData );
				if ( !appendCorr && w.exists( old.corrData ) ) w.remove( old.corrData );
			}
			final KeyValueAccess kva = kva( w );
			for ( final Key k : blobPoints ) { final String p = kva.compose( containerURI, STAGING, blobName( k ) + ".points" ); if ( kva.exists( p ) ) kva.delete( p ); }
			for ( final Key k : blobCorr ) { final String p = kva.compose( containerURI, STAGING, blobName( k ) + ".corr" ); if ( kva.exists( p ) ) kva.delete( p ); }
			blobPoints = Set.of(); blobCorr = Set.of();

			final int nStaged = stagedPoints.size(), nStagedCorr = stagedCorr.size();
			stagedPoints.clear(); stagedCorr.clear(); removedPoints.clear(); removedCorr.clear();
			clearCache();
			final DatasetAttributes loc = w.getDatasetAttributes( pointsData + "/loc" ), id = w.getDatasetAttributes( pointsData + "/id" ), corr = w.getDatasetAttributes( corrData + "/data" );
			index = new Index( gen, labels, pointsData, corrData, newPoints, newPairs, nPointsNew, nCorrNew, loc, id, corr );

			IOFunctions.println( "PackedInterestPointStore: committed generation " + gen + " (" + nStaged + " point entries, " + nStagedCorr + " correspondence entries, points "
					+ ( appendPts ? "appended" : "rewritten" ) + ", correspondences " + ( appendCorr ? "appended" : "rewritten" ) + ", " + newPoints.size() + " entries / " + nPointsNew
					+ " points / " + nCorrNew + " correspondences total) in " + ( System.currentTimeMillis() - t0 ) + " ms" );
		}
	}

	private static void addPair( final Map< Key, List< PairRow > > pairs, final Key a, final Key b, final long offset, final int count )
	{
		pairs.computeIfAbsent( a, x -> new ArrayList<>() ).add( new PairRow( b, offset, count, false ) );
		if ( !a.equals( b ) ) pairs.computeIfAbsent( b, x -> new ArrayList<>() ).add( new PairRow( a, offset, count, true ) );
	}

	/** writes entries consecutively from {@code start}; creates the datasets if {@code oldLoc == null}, else appends (read-modify-write of the first partial block) */
	private void writePoints( final N5Writer w, final String group, final DatasetAttributes oldLoc, final DatasetAttributes oldId, final long start, final List< Points > entries, final long total )
	{
		final int B = oldLoc != null ? oldLoc.getBlockSize()[ 1 ] : defaultBlockSize;
		final DatasetAttributes loc = new DatasetAttributes( new long[] { 3, total }, new int[] { 3, B }, DataType.FLOAT64, new GzipCompression() );
		final DatasetAttributes id = new DatasetAttributes( new long[] { 1, total }, new int[] { 1, B }, DataType.INT32, new GzipCompression() );
		if ( oldLoc == null )
		{
			if ( w.exists( group ) ) w.remove( group );
			w.createDataset( group + "/loc", loc );
			w.createDataset( group + "/id", id );
		}
		final long[] offsets = new long[ entries.size() ];
		long o = start;
		for ( int i = 0; i < entries.size(); ++i ) { offsets[ i ] = o; o += entries.get( i ).size(); }
		final long end = o;
		if ( end > start )
		{
			final long b0 = start / B, b1 = ( end - 1 ) / B;
			final ForkJoinPool pool = new ForkJoinPool( Threads.numThreads() );
			try
			{
				pool.submit( () -> LongStream.rangeClosed( b0, b1 ).parallel().forEach( b -> {
					final long bs = b * B, be = Math.min( total, bs + B );
					final double[] lbuf = new double[ (int) ( be - bs ) * 3 ];
					final int[] ibuf = new int[ (int) ( be - bs ) ];
					if ( bs < start ) // first block is partial: keep its existing content
					{
						final double[] ol = (double[]) w.readBlock( group + "/loc", oldLoc, 0, b ).getData();
						final int[] oi = (int[]) w.readBlock( group + "/id", oldId, 0, b ).getData();
						System.arraycopy( ol, 0, lbuf, 0, Math.min( ol.length, lbuf.length ) );
						System.arraycopy( oi, 0, ibuf, 0, Math.min( oi.length, ibuf.length ) );
					}
					int e = Arrays.binarySearch( offsets, bs );
					if ( e < 0 ) e = Math.max( 0, -e - 2 );
					for ( ; e < entries.size() && offsets[ e ] < be; ++e )
					{
						final Points p = entries.get( e );
						final long es = offsets[ e ], ee = es + p.size();
						final long cs = Math.max( es, bs ), ce = Math.min( ee, be );
						if ( ce > cs )
						{
							System.arraycopy( p.loc, (int) ( cs - es ) * 3, lbuf, (int) ( cs - bs ) * 3, (int) ( ce - cs ) * 3 );
							System.arraycopy( p.ids, (int) ( cs - es ), ibuf, (int) ( cs - bs ), (int) ( ce - cs ) );
						}
					}
					w.writeBlock( group + "/loc", loc, new DoubleArrayDataBlock( new int[] { 3, (int) ( be - bs ) }, new long[] { 0, b }, lbuf ) );
					w.writeBlock( group + "/id", id, new IntArrayDataBlock( new int[] { 1, (int) ( be - bs ) }, new long[] { 0, b }, ibuf ) );
				}) ).get();
			}
			catch ( InterruptedException | ExecutionException e ) { throw new RuntimeException( "writing packed points failed", e ); }
			finally { pool.shutdown(); }
		}
		w.setDatasetAttributes( group + "/loc", loc );
		w.setDatasetAttributes( group + "/id", id );
	}

	private void writeCorr( final N5Writer w, final String group, final DatasetAttributes oldAttrs, final long start, final List< int[][] > entries, final long total )
	{
		final int B = oldAttrs != null ? oldAttrs.getBlockSize()[ 1 ] : defaultBlockSize;
		final DatasetAttributes attrs = new DatasetAttributes( new long[] { 3, total }, new int[] { 3, B }, DataType.INT32, new GzipCompression() );
		if ( oldAttrs == null )
		{
			if ( w.exists( group ) ) w.remove( group );
			w.createDataset( group + "/data", attrs );
		}
		final long[] offsets = new long[ entries.size() ];
		long o = start;
		for ( int i = 0; i < entries.size(); ++i ) { offsets[ i ] = o; o += entries.get( i )[ 0 ].length; }
		final long end = o;
		if ( end > start )
		{
			final long b0 = start / B, b1 = ( end - 1 ) / B;
			final ForkJoinPool pool = new ForkJoinPool( Threads.numThreads() );
			try
			{
				pool.submit( () -> LongStream.rangeClosed( b0, b1 ).parallel().forEach( b -> {
					final long bs = b * B, be = Math.min( total, bs + B );
					final int[] buf = new int[ (int) ( be - bs ) * 3 ];
					if ( bs < start )
					{
						final int[] old = (int[]) w.readBlock( group + "/data", oldAttrs, 0, b ).getData();
						System.arraycopy( old, 0, buf, 0, Math.min( old.length, buf.length ) );
					}
					int e = Arrays.binarySearch( offsets, bs );
					if ( e < 0 ) e = Math.max( 0, -e - 2 );
					for ( ; e < entries.size() && offsets[ e ] < be; ++e )
					{
						final int[][] d = entries.get( e );
						final long es = offsets[ e ], ee = es + d[ 0 ].length;
						for ( long j = Math.max( es, bs ); j < Math.min( ee, be ); ++j )
							for ( int c = 0; c < 3; ++c ) buf[ (int) ( j - bs ) * 3 + c ] = d[ c ][ (int) ( j - es ) ];
					}
					w.writeBlock( group + "/data", attrs, new IntArrayDataBlock( new int[] { 3, (int) ( be - bs ) }, new long[] { 0, b }, buf ) );
				}) ).get();
			}
			catch ( InterruptedException | ExecutionException e ) { throw new RuntimeException( "writing packed correspondences failed", e ); }
			finally { pool.shutdown(); }
		}
		w.setDatasetAttributes( group + "/data", attrs );
	}

	private static void writeLongs( final N5Writer w, final String ds, final int cols, final long[] v )
	{
		final int rows = v.length / cols;
		final int rowsBlock = Math.max( 1, Math.min( rows, 1 << 20 ) );
		final DatasetAttributes a = new DatasetAttributes( new long[] { cols, rows }, new int[] { cols, rowsBlock }, DataType.INT64, new GzipCompression() );
		if ( w.exists( ds ) ) w.remove( ds );
		w.createDataset( ds, a );
		for ( long b = 0; b * rowsBlock < rows; ++b )
		{
			final int r0 = (int) ( b * rowsBlock ), r1 = (int) Math.min( rows, r0 + rowsBlock );
			w.writeBlock( ds, a, new LongArrayDataBlock( new int[] { cols, r1 - r0 }, new long[] { 0, b }, Arrays.copyOfRange( v, r0 * cols, r1 * cols ) ) );
		}
	}

	private static long[] readLongs( final N5Reader n5, final String ds, final int cols )
	{
		final DatasetAttributes a = n5.getDatasetAttributes( ds );
		final long rows = a.getDimensions()[ 1 ];
		final long[] out = new long[ (int) ( cols * rows ) ];
		final int rowsBlock = a.getBlockSize()[ 1 ];
		for ( long b = 0; b * rowsBlock < rows; ++b )
		{
			final long[] blk = (long[]) n5.readBlock( ds, a, 0, b ).getData();
			System.arraycopy( blk, 0, out, (int) ( b * rowsBlock * cols ), blk.length );
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------
	// legacy conversion
	// ------------------------------------------------------------------------------------------------

	/**
	 * Packs every legacy per-view group ({@code tpId_X_viewSetupId_Y/label}) of the container into the packed
	 * arrays and deletes the groups. Safe to run again on a partially converted container.
	 *
	 * @param baseDir the dataset directory containing interestpoints.n5
	 * @return number of converted entries
	 */
	public static int convertLegacy( final URI baseDir )
	{
		final PackedInterestPointStore store = get( baseDir );
		final N5Writer w = store.writer();
		final List< String > groups = new ArrayList<>();
		for ( final String g : w.list( "/" ) )
			if ( g.startsWith( "tpId_" ) && g.contains( "_viewSetupId_" ) )
				for ( final String label : w.list( g ) )
					groups.add( g + "/" + label );
		Collections.sort( groups );
		if ( groups.isEmpty() ) return 0;
		IOFunctions.println( "PackedInterestPointStore: converting " + groups.size() + " legacy interest point groups in " + store.containerURI );

		final ForkJoinPool pool = new ForkJoinPool( Threads.numThreads() );
		try
		{
			pool.submit( () -> IntStream.range( 0, groups.size() ).parallel().forEach( i -> {
				final String path = groups.get( i );
				final Key k = Key.parse( path );
				if ( k == null ) return;
				final InterestPointsN5 ip = new InterestPointsN5( baseDir, path );
				if ( w.exists( InterestPointsN5.ipDataset( path ) ) && ip.loadLegacyInterestPoints() )
					store.stagePoints( k, ip.ids, ip.locations );
				if ( w.exists( InterestPointsN5.corrDataset( path ) ) && ip.loadLegacyCorrespondences() )
					store.stageCorrespondences( k, ip.correspondingInterestPoints );
			}) ).get();
		}
		catch ( InterruptedException | ExecutionException e ) { throw new RuntimeException( "reading legacy interest points failed", e ); }
		finally { pool.shutdown(); }

		store.commit();

		final Set< String > viewGroups = new HashSet<>();
		for ( final String g : groups ) viewGroups.add( g.substring( 0, g.indexOf( '/' ) ) );
		final ForkJoinPool pool2 = new ForkJoinPool( Threads.numThreads() );
		try { pool2.submit( () -> viewGroups.parallelStream().forEach( g -> w.remove( g ) ) ).get(); }
		catch ( InterruptedException | ExecutionException e ) { throw new RuntimeException( "removing legacy interest point groups failed", e ); }
		finally { pool2.shutdown(); }
		IOFunctions.println( "PackedInterestPointStore: removed " + viewGroups.size() + " legacy view groups" );
		return groups.size();
	}

	/** java ... PackedInterestPointStore &lt;dataset.xml | dataset directory&gt; */
	public static void main( final String[] args )
	{
		File dir = new File( args[ 0 ] );
		if ( dir.isFile() ) dir = dir.getParentFile();
		final long t0 = System.currentTimeMillis();
		final int n = convertLegacy( dir.toURI() );
		System.out.println( "converted " + n + " entries in " + ( System.currentTimeMillis() - t0 ) + " ms" );
	}
}
