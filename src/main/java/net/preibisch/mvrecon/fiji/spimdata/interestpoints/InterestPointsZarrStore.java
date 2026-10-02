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

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.DoubleArrayDataBlock;
import org.janelia.saalfeldlab.n5.GsonKeyValueN5Reader;
import org.janelia.saalfeldlab.n5.IntArrayDataBlock;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.readdata.VolatileReadData;
import org.janelia.saalfeldlab.n5.codec.checksum.Crc32cChecksumCodec;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import util.URITools;

/**
 * All interest points and correspondences of one dataset, in a few sharded Zarr v3 arrays ({@code interestpoints.zarr}).
 * One instance per dataset directory ({@link #get(URI)}), shared by all {@link InterestPointsZarr} lists of the dataset.
 *
 * <pre>
 * zarr.json                 root attributes: version, generation G, pointsData, corrData, labels, chunk and shard size, pointAttributes
 * index/gG/entries          INT64 [5, E]      (tp, setup, labelId, offset, count) per (view, label)
 * index/gG/views            INT64 [5, E]      (tp, setup, labelId, first pair row, pair count)
 * index/gG/pairs            INT64 [6, P]      (partner tp, setup, labelId, offset, count, swapped)
 * points/gK/loc             FLOAT64 [3+A, N]  x, y, z, then one column per point attribute (-1 = no value)
 * points/gK/id              INT32 [1, N]      detection ids
 * correspondences/gK/data   INT32 [3, M]      (detection A, detection B, consensus set), once per pair, A = smaller key
 * staging/*.stage           one file per Spark task, folded in by the next commit
 * </pre>
 *
 * During a batch ({@link #beginBatch()}) saves stay in memory; otherwise each save writes a staging file. {@link #commit()}
 * appends to or rewrites the arrays, writes the next index generation, and then switches the root attributes to it.
 */
public class InterestPointsZarrStore
{
	public static final String VERSION = "1.0.0";
	public static final String ZARR_CONTAINER = "interestpoints.zarr";

	/** Points (or correspondences) per inner chunk, the unit of a read. 64K was the fastest of 1K to 64K on /nrs. */
	public static int defaultChunkPoints = 65536;

	/** Points (or correspondences) per shard file; rounded up to a multiple of the chunk size. */
	public static int defaultShardPoints = 1 << 20;

	/** A commit appends only if at least this fraction of an array stays in use; otherwise it rewrites the array. */
	public static double minLiveFractionForAppend = 0.75;

	public static int chunkCacheSize = 256;

	/** Attribute value of a point that has no value for that attribute. */
	public static final double NO_VALUE = -1;

	static final String STAGING = "staging";
	static final String STAGING_EXT = ".stage";
	static final int STAGING_MAGIC = 0x49505354;
	static final int STAGING_VERSION = 1;

	static final String ATTR_VERSION = "interestpoints";
	static final String ATTR_GENERATION = "generation";
	static final String ATTR_POINTS_DATA = "pointsData";
	static final String ATTR_CORRESPONDENCES_DATA = "corrData";
	static final String ATTR_LABELS = "labels";
	static final String ATTR_CHUNK_SIZE = "chunkPoints";
	static final String ATTR_SHARD_SIZE = "shardPoints";
	static final String ATTR_POINT_ATTRIBUTES = "pointAttributes";

	private static final Pattern ATTRIBUTE_NAME = Pattern.compile( "[A-Za-z0-9_.-]+" );

	/** One (timepoint, setup, label) entry. */
	public record Key( int tp, int setup, String label ) implements Comparable< Key >
	{
		private static final Pattern PATH = Pattern.compile( "tpId_(\\d+)_viewSetupId_(\\d+)/(.+)" );
		private static final Comparator< Key > ORDER = Comparator.comparingInt( Key::tp ).thenComparingInt( Key::setup ).thenComparing( Key::label );

		public static Key of( final ViewId viewId, final String label )
		{
			return new Key( viewId.getTimePointId(), viewId.getViewSetupId(), label );
		}

		/** @return the key of an XML path {@code tpId_X_viewSetupId_Y/label}, or null for a path of another form */
		public static Key parse( final String path )
		{
			final Matcher matcher = PATH.matcher( path );
			if ( !matcher.matches() )
				return null;

			return new Key( Integer.parseInt( matcher.group( 1 ) ), Integer.parseInt( matcher.group( 2 ) ), matcher.group( 3 ) );
		}

		/** @return the XML path {@code tpId_X_viewSetupId_Y/label} */
		public String path() { return "tpId_" + tp + "_viewSetupId_" + setup + "/" + label; }

		public ViewId viewId() { return new ViewId( tp, setup ); }

		public Pair< ViewId, String > toPair() { return new ValuePair<>( viewId(), label ); }

		@Override
		public int compareTo( final Key other ) { return ORDER.compare( this, other ); }
	}

	/** The points of one entry: ids, flat x, y, z coordinates, and named attributes with one value per point. */
	public record Points( int[] ids, double[] loc, Map< String, double[] > attributes )
	{
		public Points
		{
			if ( attributes == null )
				attributes = Map.of();

			checkAttributes( attributes, ids.length );
		}

		public Points( final int[] ids, final double[] loc ) { this( ids, loc, null ); }

		public static Points of( final int[] ids, final double[][] locations ) { return of( ids, locations, null ); }

		/** Copies all arrays. */
		public static Points of( final int[] ids, final double[][] locations, final Map< String, double[] > attributes )
		{
			final double[] loc = new double[ ids.length * 3 ];
			for ( int i = 0; i < ids.length; ++i )
				System.arraycopy( locations[ i ], 0, loc, i * 3, 3 );

			final TreeMap< String, double[] > attributesCopy = new TreeMap<>();
			if ( attributes != null )
				attributes.forEach( ( name, values ) -> attributesCopy.put( name, values.clone() ) );

			return new Points( ids.clone(), loc, attributesCopy );
		}

		public int size() { return ids.length; }

		public double[][] locations()
		{
			final double[][] locations = new double[ ids.length ][];
			for ( int i = 0; i < ids.length; ++i )
				locations[ i ] = Arrays.copyOfRange( loc, i * 3, i * 3 + 3 );

			return locations;
		}
	}

	/** Checks that names use only letters, digits, '_', '.', '-', and that every attribute has one value per point. */
	static void checkAttributes( final Map< String, double[] > attributes, final int pointCount )
	{
		for ( final Map.Entry< String, double[] > attribute : attributes.entrySet() )
		{
			final String name = attribute.getKey();
			if ( name == null || !ATTRIBUTE_NAME.matcher( name ).matches() )
				throw new IllegalArgumentException( "invalid interest point attribute name '" + name + "' (allowed: letters, digits, '_', '.', '-')" );

			final int valueCount = attribute.getValue().length;
			if ( valueCount != pointCount )
				throw new IllegalArgumentException( "attribute '" + name + "' has " + valueCount + " values for " + pointCount + " points" );
		}
	}

	/** One partner of an entry: where the pair's rows are, and whether this entry is column B of them. */
	record PairRow( Key partner, long offset, int count, boolean swapped ) {}

	/** One generation of the index (immutable). */
	record Index(
			int generation,
			List< String > labels,
			String pointsData,
			String correspondencesData,
			Map< Key, long[] > points, // key -> { offset, count }
			Map< Key, List< PairRow > > pairs,
			long pointCount,
			long correspondenceCount,
			DatasetAttributes locAttributes,
			DatasetAttributes idAttributes,
			DatasetAttributes correspondenceAttributes,
			List< String > attributeNames ) // loc columns 3, 4, ...
	{
		static Index empty() { return new Index( -1, List.of(), null, null, Map.of(), Map.of(), 0, 0, null, null, null, List.of() ); }

		boolean exists() { return generation >= 0; }
	}

	/** Where one entry's payload is inside a staging file. */
	record StagingRef( String file, long offset, int count ) {}

	/** One row of a staging file's entry table. */
	record StagingEntry( Key key, StagingRef points, StagingRef correspondences ) {}

	/** One row of an entry table while it is written: indices into the payload list, -1 = none. */
	private record StagingRow( Key key, int pointsPayload, int pointCount, int correspondencesPayload, int correspondenceCount ) {}

	/** One payload that a commit reads from a staging file. */
	private record FoldRef< T >( Key key, StagingRef ref, Side< T > side )
	{
		/**
		 * Decodes the payload and stages it. Runs on pool threads while the committing thread holds the store lock, so the
		 * puts are serialized on the map itself.
		 */
		void fold( final DataInputStream in ) throws IOException
		{
			final T value = side.decode( in, ref.count );
			synchronized ( side.staged ) { side.staged.put( key, value ); }
		}
	}

	/** An existing pair (a < b) that a commit keeps. */
	private record KeptPair( Key a, Key b, PairRow row ) {}

	/** What a commit wrote for the points. */
	private record PointsCommit( String data, boolean appended, TreeMap< Key, long[] > ranges, long total, List< String > attributeNames ) {}

	/** What a commit wrote for the correspondences. */
	private record CorrespondencesCommit( String data, boolean appended, Map< Key, List< PairRow > > pairs, long total ) {}

	/**
	 * An N5 / Zarr container opened on first use. The reader is null while the container does not exist (cached until
	 * {@link #retryReader()}); the writer, once opened, serves as the reader.
	 */
	static final class LazyN5Container
	{
		private final StorageFormat format;
		private final URI uri;
		private N5Reader reader = null;
		private N5Writer writer = null;
		private boolean readerTried = false;

		LazyN5Container( final StorageFormat format, final URI uri )
		{
			this.format = format;
			this.uri = uri;
		}

		/** @return the reader, or null if there is no container */
		synchronized N5Reader reader()
		{
			if ( writer != null )
				return writer;

			if ( !readerTried )
			{
				readerTried = true;
				try
				{
					reader = URITools.instantiateN5Reader( format, uri );
				}
				catch ( final Exception e )
				{
					reader = null; // no container
				}
			}
			return reader;
		}

		/** Forgets a cached "no container", so one that another JVM created meanwhile is found. */
		synchronized void retryReader()
		{
			if ( reader == null && writer == null )
				readerTried = false;
		}

		/** Creates the container if needed. */
		synchronized N5Writer writer()
		{
			if ( writer == null )
			{
				writer = URITools.instantiateN5Writer( format, uri );
				reader = null;
			}
			return writer;
		}

		/**
		 * @return a new reader that sees the current state of the container (readers cache root attributes), or null if
		 * this JVM writes the container itself or the container does not exist
		 */
		synchronized N5Reader freshReader()
		{
			if ( writer != null )
				return null;
			try
			{
				return URITools.instantiateN5Reader( format, uri );
			}
			catch ( final Exception e )
			{
				return null;
			}
		}

		synchronized void replaceReader( final N5Reader fresh ) { reader = fresh; }
	}

	// ponytail: one store per dataset directory for the whole JVM; fine as long as all SpimData2 of a base path see the same files
	private static final ConcurrentHashMap< String, InterestPointsZarrStore > stores = new ConcurrentHashMap<>();

	/** @param baseDir the dataset directory, {@code SpimData2.getBasePathURI()} */
	public static InterestPointsZarrStore get( final URI baseDir )
	{
		return stores.computeIfAbsent( storeKey( baseDir ), key -> new InterestPointsZarrStore( baseDir ) );
	}

	/** One key per directory: {@code file:/x/}, {@code file:///x}, and {@code file:///x/} are the same store. */
	static String storeKey( final URI baseDir )
	{
		if ( URITools.isFile( baseDir ) )
			return java.nio.file.Paths.get( baseDir ).toAbsolutePath().normalize().toString();

		final String uri = baseDir.toString();
		return uri.endsWith( "/" ) ? uri.substring( 0, uri.length() - 1 ) : uri;
	}

	final URI baseDir;
	final URI containerURI;

	private final LazyN5Container container;
	private volatile Index index = null;

	private final Object lock = new Object();
	private volatile boolean batchOpen = false;

	private final Map< String, List< StagingEntry > > parsedStagingFiles = new HashMap<>(); // file name -> entry table
	private boolean stagingListed = false;

	/**
	 * Everything that exists once for the points and once for the correspondences: the changes staged in this JVM, the
	 * payloads in staging files, how a staging payload is decoded, and where the index keeps the entry.
	 */
	private abstract static class Side< T >
	{
		final Map< Key, T > staged = new HashMap<>(); // guarded by lock
		final Set< Key > removed = new HashSet<>(); // guarded by lock
		volatile Map< Key, StagingRef > staging = Map.of(); // key -> payload in a staging file, a later file wins

		abstract StagingRef ref( StagingEntry entry );
		abstract boolean indexed( Index index, Key key );
		/** @return what a reader gets of a staged value: the value itself if immutable, else a shallow copy */
		abstract T snapshot( T value );
		abstract T decode( DataInputStream in, int count ) throws IOException;
		/** @return the entry read again after its staging file disappeared */
		abstract T reload( Key key );
	}

	private final Side< Points > pointSide = new Side<>()
	{
		@Override StagingRef ref( final StagingEntry entry ) { return entry.points; }
		@Override boolean indexed( final Index index, final Key key ) { return index.points.containsKey( key ); }
		@Override Points snapshot( final Points value ) { return value; }
		@Override Points decode( final DataInputStream in, final int count ) throws IOException { return decodePoints( in, count ); }
		@Override Points reload( final Key key ) { return points( key ); }
	};

	private final Side< List< CorrespondingInterestPoints > > correspondenceSide = new Side<>()
	{
		@Override StagingRef ref( final StagingEntry entry ) { return entry.correspondences; }
		@Override boolean indexed( final Index index, final Key key ) { return index.pairs.containsKey( key ); }
		@Override List< CorrespondingInterestPoints > snapshot( final List< CorrespondingInterestPoints > value ) { return new ArrayList<>( value ); }
		@Override List< CorrespondingInterestPoints > decode( final DataInputStream in, final int count ) throws IOException { return decodeCorrespondences( in, count ); }
		@Override List< CorrespondingInterestPoints > reload( final Key key ) { return correspondences( key ); }
	};

	private final List< Side< ? > > sides = List.of( pointSide, correspondenceSide );

	private final LinkedHashMap< String, Object > chunkCache = new LinkedHashMap<>( 64, 0.75f, true )
	{
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry( final Map.Entry< String, Object > eldest ) { return size() > chunkCacheSize; }
	};

	/** Prefer {@link #get(URI)}: a separate instance does not share its index and cache with the rest of the JVM. */
	public InterestPointsZarrStore( final URI baseDir )
	{
		this.baseDir = baseDir;
		this.containerURI = URITools.toURI( URITools.appendName( baseDir, ZARR_CONTAINER ) );
		this.container = new LazyN5Container( StorageFormat.ZARR, containerURI );
	}

	// ------------------------------------------------------------------------------------------------
	// container
	// ------------------------------------------------------------------------------------------------


	private static KeyValueAccess keyValueAccess( final N5Reader n5 )
	{
		return ( (GsonKeyValueN5Reader) n5 ).getKeyValueAccess();
	}

	/** @return true if the store exists */
	public boolean exists() { return index().exists(); }

	Index index()
	{
		Index current = index;
		if ( current == null )
		{
			synchronized ( this )
			{
				if ( index == null )
					index = loadIndex();
				current = index;
			}
		}
		return current;
	}

	private Index loadIndex()
	{
		listStagingFiles();
		final N5Reader zarr = container.reader();
		if ( zarr == null || zarr.getAttribute( "/", ATTR_VERSION, String.class ) == null )
			return Index.empty();

		final int generation = zarr.getAttribute( "/", ATTR_GENERATION, Integer.class );
		@SuppressWarnings( "unchecked" )
		final List< String > labels = new ArrayList<>( zarr.getAttribute( "/", ATTR_LABELS, List.class ) );
		final String pointsData = zarr.getAttribute( "/", ATTR_POINTS_DATA, String.class );
		final String correspondencesData = zarr.getAttribute( "/", ATTR_CORRESPONDENCES_DATA, String.class );
		@SuppressWarnings( "unchecked" )
		final List< String > attributeNames = zarr.getAttribute( "/", ATTR_POINT_ATTRIBUTES, List.class );

		final long[] entryRows = readLongs( zarr, indexGroup( generation ) + "/entries", 5 );
		final Map< Key, long[] > pointRanges = new HashMap<>();
		for ( int row = 0; row < entryRows.length; row += 5 )
			pointRanges.put( readKey( entryRows, row, labels ), new long[] { entryRows[ row + 3 ], entryRows[ row + 4 ] } );

		final long[] viewRows = readLongs( zarr, indexGroup( generation ) + "/views", 5 );
		final long[] pairRows = readLongs( zarr, indexGroup( generation ) + "/pairs", 6 );
		final Map< Key, List< PairRow > > pairs = new HashMap<>();
		for ( int row = 0; row < viewRows.length; row += 5 )
		{
			final long firstPair = viewRows[ row + 3 ];
			final long pairCount = viewRows[ row + 4 ];

			final List< PairRow > partners = new ArrayList<>();
			for ( long pair = firstPair; pair < firstPair + pairCount; ++pair )
			{
				final int first = (int) ( 6 * pair );
				partners.add( new PairRow( readKey( pairRows, first, labels ), pairRows[ first + 3 ], (int) pairRows[ first + 4 ], pairRows[ first + 5 ] != 0 ) );
			}
			pairs.put( readKey( viewRows, row, labels ), partners );
		}

		final DatasetAttributes locAttributes = zarr.getDatasetAttributes( pointsData + "/loc" );
		final DatasetAttributes idAttributes = zarr.getDatasetAttributes( pointsData + "/id" );
		final DatasetAttributes correspondenceAttributes = zarr.getDatasetAttributes( correspondencesData + "/data" );

		return new Index(
				generation, labels, pointsData, correspondencesData, pointRanges, pairs,
				locAttributes.getDimensions()[ 1 ], correspondenceAttributes.getDimensions()[ 1 ],
				locAttributes, idAttributes, correspondenceAttributes,
				List.copyOf( attributeNames ) );
	}

	static String indexGroup( final int generation ) { return "index/g" + generation; }

	/** The key in the three index columns (tp, setup, label id) at {@code position}. */
	private static Key readKey( final long[] rows, final int position, final List< String > labels )
	{
		return new Key( (int) rows[ position ], (int) rows[ position + 1 ], labels.get( (int) rows[ position + 2 ] ) );
	}

	/** Writes the three index columns of a key at {@code position}. @return the next position */
	private static int writeKey( final long[] rows, final int position, final Key key, final Map< String, Integer > labelIds )
	{
		rows[ position ] = key.tp;
		rows[ position + 1 ] = key.setup;
		rows[ position + 2 ] = labelIds.get( key.label );
		return position + 3;
	}

	/** Lists the staging directory and reads the entry tables of files not seen before. */
	private void listStagingFiles()
	{
		if ( stagingListed )
			return;
		stagingListed = true;

		final N5Reader zarr = container.reader();
		if ( zarr == null )
		{
			synchronized ( lock )
			{
				parsedStagingFiles.clear();
				rebuildStagingMaps();
			}
			return;
		}

		final KeyValueAccess kva = keyValueAccess( zarr );
		final String directory = kva.compose( containerURI, STAGING );
		final List< String > names = new ArrayList<>();
		if ( kva.exists( directory ) )
			for ( final String name : kva.list( directory ) )
				if ( name.endsWith( STAGING_EXT ) )
					names.add( name );

		final List< String > newNames = new ArrayList<>();
		synchronized ( lock )
		{
			parsedStagingFiles.keySet().retainAll( new HashSet<>( names ) );
			for ( final String name : names )
				if ( !parsedStagingFiles.containsKey( name ) )
					newNames.add( name );
		}

		if ( !newNames.isEmpty() )
		{
			final Map< String, List< StagingEntry > > parsed = new ConcurrentHashMap<>();
			parallel( "reading staging file headers", () -> newNames.parallelStream().forEach( name -> {
				final List< StagingEntry > entries = readHeader( kva, name );
				if ( entries != null )
					parsed.put( name, entries );
			} ) );

			synchronized ( lock ) { parsedStagingFiles.putAll( parsed ); }
		}

		synchronized ( lock ) { rebuildStagingMaps(); }
	}

	/** Rebuilds the key -> payload maps. Names sort chronologically, so a later file wins. */
	private void rebuildStagingMaps()
	{
		final Collection< List< StagingEntry > > chronological = new TreeMap<>( parsedStagingFiles ).values();
		for ( final Side< ? > side : sides )
		{
			final Map< Key, StagingRef > refs = new HashMap<>();
			for ( final List< StagingEntry > entries : chronological )
				for ( final StagingEntry entry : entries )
				{
					final StagingRef ref = side.ref( entry );
					if ( ref != null )
						refs.put( entry.key, ref );
				}
			side.staging = refs;
		}
	}

	/** Lists the staging directory again, so files that other JVMs wrote or deleted since are picked up. */
	private void relistStagingFiles()
	{
		stagingListed = false;
		listStagingFiles();
	}

	private String stagingPath( final KeyValueAccess kva, final String name ) { return kva.compose( containerURI, STAGING, name ); }

	/** Milliseconds (zero-padded, so names sort chronologically), nanoTime, and a random number: unique across JVMs. */
	private static String stagingFileName()
	{
		return String.format( "%013d_%016x_%08x%s", System.currentTimeMillis(), System.nanoTime(), new Random().nextInt(), STAGING_EXT );
	}

	/** @return the entry table of a staging file, or null if it cannot be read (for example, still being written) */
	private List< StagingEntry > readHeader( final KeyValueAccess kva, final String name )
	{
		try ( final DataInputStream in = openStagingFile( kva, name ) )
		{
			return parseHeader( name, in );
		}
		catch ( final IOException | RuntimeException e )
		{
			IOFunctions.println( "InterestPointsZarrStore: WARNING cannot read staging file " + name + ": " + e );
			return null;
		}
	}

	private static List< StagingEntry > parseHeader( final String name, final DataInputStream in ) throws IOException
	{
		if ( in.readInt() != STAGING_MAGIC )
			throw new IOException( "not a staging file" );

		final int version = in.readInt();
		if ( version != STAGING_VERSION )
			throw new IOException( "unsupported staging file version " + version );

		final int entryCount = in.readInt();
		final List< StagingEntry > entries = new ArrayList<>( entryCount );
		for ( int i = 0; i < entryCount; ++i )
		{
			final Key key = new Key( in.readInt(), in.readInt(), in.readUTF() );
			final StagingRef points = readStagingRef( in, name );
			final StagingRef correspondences = readStagingRef( in, name );
			entries.add( new StagingEntry( key, points, correspondences ) );
		}
		return entries;
	}

	/** Reads (present, offset, count). @return null if not present */
	private static StagingRef readStagingRef( final DataInputStream in, final String file ) throws IOException
	{
		final boolean present = in.readBoolean();
		final long offset = in.readLong();
		final int count = in.readInt();
		return present ? new StagingRef( file, offset, count ) : null;
	}

	// ------------------------------------------------------------------------------------------------
	// reading
	// ------------------------------------------------------------------------------------------------

	/** Where an entry's data is: exactly one field is set. */
	private record Where< T >( T staged, StagingRef stagingRef, Index index ) {}

	/**
	 * Looks in the data staged in this JVM, then in the staging files, then in the arrays. On a miss it refreshes once,
	 * because another JVM may have written the entry since.
	 *
	 * @return null if this store does not know the entry
	 */
	private < T > Where< T > where( final Side< T > side, final Key key )
	{
		synchronized ( lock )
		{
			if ( side.removed.contains( key ) )
				return null;

			final T staged = side.staged.get( key );
			if ( staged != null )
				return new Where<>( side.snapshot( staged ), null, null );
		}

		final Index current = index();
		final StagingRef ref = side.staging.get( key );
		if ( ref != null )
			return new Where<>( null, ref, null );

		if ( side.indexed( current, key ) )
			return new Where<>( null, null, current );

		if ( refreshOnMiss( side, key ) )
			return new Where<>( null, side.staging.get( key ), null );

		final Index refreshed = index();
		return side.indexed( refreshed, key ) ? new Where<>( null, null, refreshed ) : null;
	}

	/** @return true if this store knows the entry (staged, in a staging file, or in the arrays) */
	public boolean hasPoints( final Key key ) { return where( pointSide, key ) != null; }

	/** @return the points of an entry, or null if this store does not know it */
	public Points points( final Key key )
	{
		final Where< Points > where = where( pointSide, key );
		if ( where == null )
			return null;
		if ( where.staged != null )
			return where.staged;
		if ( where.stagingRef != null )
			return readStaged( pointSide, where.stagingRef, key );
		return readPoints( where.index, key );
	}

	/** @return all correspondences of an entry, to all partners; null if this store does not know it */
	public List< CorrespondingInterestPoints > correspondences( final Key key ) { return correspondences( key, null ); }

	/** @return the correspondences of an entry to one partner (view, label); null if this store does not know the entry */
	public List< CorrespondingInterestPoints > correspondences( final Key key, final ViewId partnerView, final String partnerLabel )
	{
		return correspondences( key, Key.of( partnerView, partnerLabel ) );
	}

	/** @param partner only correspondences to this partner, or null for all */
	private List< CorrespondingInterestPoints > correspondences( final Key key, final Key partner )
	{
		final Where< List< CorrespondingInterestPoints > > where = where( correspondenceSide, key );
		if ( where == null )
			return null;
		if ( where.index != null )
		{
			final ArrayList< CorrespondingInterestPoints > result = new ArrayList<>();
			for ( final PairRow row : where.index.pairs.get( key ) )
				if ( partner == null || row.partner.equals( partner ) )
					readPairRows( where.index, row, result );

			return result;
		}

		final List< CorrespondingInterestPoints > all = where.staged != null ? where.staged : readStaged( correspondenceSide, where.stagingRef, key );
		if ( all == null || partner == null )
			return all;

		final ArrayList< CorrespondingInterestPoints > result = new ArrayList<>();
		for ( final CorrespondingInterestPoints correspondence : all )
			if ( partnerOf( correspondence ).equals( partner ) )
				result.add( correspondence );

		return result;
	}

	private static Key partnerOf( final CorrespondingInterestPoints correspondence )
	{
		return Key.of( correspondence.getCorrespondingViewId(), correspondence.getCorrespodingLabel() );
	}

	/** @return the (view, label)s this entry has correspondences with; null if this store does not know the entry */
	public Set< Pair< ViewId, String > > correspondingViews( final Key key )
	{
		final Where< List< CorrespondingInterestPoints > > where = where( correspondenceSide, key );
		if ( where == null )
			return null;
		final Set< Pair< ViewId, String > > partners = new HashSet<>();
		if ( where.index != null )
		{
			for ( final PairRow row : where.index.pairs.get( key ) )
				partners.add( row.partner.toPair() );

			return partners;
		}

		final List< CorrespondingInterestPoints > all = correspondences( key, (Key) null );
		if ( all == null )
			return null;

		for ( final CorrespondingInterestPoints correspondence : all )
			partners.add( partnerOf( correspondence ).toPair() );

		return partners;
	}

	/**
	 * Called when the entry is in none of the cached places. Another JVM may have written a staging file or committed a new
	 * generation since: list the staging directory again and reload the index if the generation changed.
	 *
	 * @return true if a staging file now has the entry
	 */
	private boolean refreshOnMiss( final Side< ? > side, final Key key )
	{
		container.retryReader();
		if ( container.reader() == null )
			return false;

		relistStagingFiles(); // parses only files not seen before
		if ( side.staging.containsKey( key ) )
			return true;

		refreshGeneration();
		return false;
	}

	/** Readers cache root attributes: asks a new reader for the generation and reloads if another JVM committed. */
	private void refreshGeneration()
	{
		synchronized ( this )
		{
			final N5Reader freshReader = container.freshReader();
			final Integer generation = freshReader == null ? null : freshReader.getAttribute( "/", ATTR_GENERATION, Integer.class );
			if ( generation != null && generation != index().generation )
			{
				container.replaceReader( freshReader );
				index = null;
				stagingListed = false;
				clearCache();
			}
		}
		index();
	}

	/** A staging file is gone: another JVM committed and deleted it. Forget it and pick up the new generation. */
	private void stagingFileGone( final String file )
	{
		synchronized ( lock )
		{
			parsedStagingFiles.remove( file );
			rebuildStagingMaps();
		}
		relistStagingFiles();
		refreshGeneration();
	}

	private static boolean isMissingFile( final Throwable error )
	{
		for ( Throwable cause = error; cause != null; cause = cause.getCause() )
			if ( cause instanceof java.nio.file.NoSuchFileException || cause instanceof N5Exception.N5NoSuchKeyException )
				return true;

		return false;
	}

	/** Receives one chunk of an array and the rows [from, to) of it that are wanted. */
	private interface ChunkVisitor< C > { void visit( C chunk, long chunkStart, long from, long to ); }

	/** Calls the visitor for every chunk that holds rows of [offset, offset + count), clipped to the array's {@code rows}. */
	private < C > void forChunks( final String dataset, final DatasetAttributes attributes, final long rows, final long offset, final int count,
			final ChunkVisitor< C > visitor )
	{
		final int chunkSize = attributes.getChunkSize()[ 1 ];
		final long end = offset + count;
		for ( long chunkIndex = offset / chunkSize; chunkIndex * chunkSize < end; ++chunkIndex )
		{
			final long chunkStart = chunkIndex * chunkSize;
			final long from = Math.max( chunkStart, offset );
			final long to = Math.min( Math.min( chunkStart + chunkSize, rows ), end );
			visitor.visit( readChunk( dataset, attributes, chunkIndex ), chunkStart, from, to );
		}
	}

	/** Reads an entry's points from the arrays of a generation. */
	private Points readPoints( final Index index, final Key key )
	{
		final long[] range = index.points.get( key );
		return readPoints( index, range[ 0 ], (int) range[ 1 ] );
	}

	/** Reads {@code count} points from {@code offset} in the arrays of a generation. */
	private Points readPoints( final Index index, final long offset, final int count )
	{
		final int[] ids = new int[ count ];
		final double[] loc = new double[ count * 3 ];
		final int attributeCount = index.attributeNames.size();
		final int columns = 3 + attributeCount;
		final double[][] attributeValues = new double[ attributeCount ][ count ];

		forChunks( index.pointsData + "/id", index.idAttributes, index.pointCount, offset, count, ( final int[] chunk, final long chunkStart, final long from, final long to ) ->
				System.arraycopy( chunk, (int) ( from - chunkStart ), ids, (int) ( from - offset ), (int) ( to - from ) ) );

		forChunks( index.pointsData + "/loc", index.locAttributes, index.pointCount, offset, count, ( final double[] chunk, final long chunkStart, final long from, final long to ) -> {
			if ( attributeCount == 0 )
			{
				System.arraycopy( chunk, (int) ( from - chunkStart ) * 3, loc, (int) ( from - offset ) * 3, (int) ( to - from ) * 3 );
				return;
			}
			for ( long point = from; point < to; ++point )
			{
				final int source = (int) ( point - chunkStart ) * columns;
				final int target = (int) ( point - offset );
				System.arraycopy( chunk, source, loc, target * 3, 3 );
				for ( int a = 0; a < attributeCount; ++a )
					attributeValues[ a ][ target ] = chunk[ source + 3 + a ];
			}
		} );

		final TreeMap< String, double[] > attributes = new TreeMap<>();
		for ( int a = 0; a < attributeCount; ++a )
			if ( hasValue( attributeValues[ a ] ) )
				attributes.put( index.attributeNames.get( a ), attributeValues[ a ] );

		return new Points( ids, loc, attributes );
	}

	private static boolean hasValue( final double[] values )
	{
		for ( final double value : values )
			if ( value != NO_VALUE )
				return true;

		return false;
	}

	/** Appends the correspondences of one pair row, seen from the row's owner. */
	private void readPairRows( final Index index, final PairRow row, final List< CorrespondingInterestPoints > result )
	{
		final ViewId partnerView = row.partner.viewId();
		forChunks( index.correspondencesData + "/data", index.correspondenceAttributes, index.correspondenceCount, row.offset, row.count, ( final int[] data, final long chunkStart, final long from, final long to ) -> {
			for ( long rowIndex = from; rowIndex < to; ++rowIndex )
			{
				final int first = (int) ( rowIndex - chunkStart ) * 3;
				final int detectionA = data[ first ];
				final int detectionB = data[ first + 1 ];
				final int consensusSet = data[ first + 2 ];
				final int own = row.swapped ? detectionB : detectionA;
				final int other = row.swapped ? detectionA : detectionB;
				result.add( new CorrespondingInterestPoints( own, partnerView, row.partner.label, other, consensusSet ) );
			}
		} );
	}

	/** One inner chunk (a primitive array of the dataset's type), read through the shard index and cached. */
	@SuppressWarnings( "unchecked" )
	private < C > C readChunk( final String dataset, final DatasetAttributes attributes, final long chunkIndex )
	{
		final String cacheKey = dataset + "#" + chunkIndex;
		synchronized ( chunkCache )
		{
			final Object cached = chunkCache.get( cacheKey );
			if ( cached != null )
				return (C) cached;
		}

		final Object data = container.reader().readChunk( dataset, attributes, 0, chunkIndex ).getData();
		synchronized ( chunkCache ) { chunkCache.put( cacheKey, data ); }
		return (C) data;
	}

	private void clearCache()
	{
		synchronized ( chunkCache ) { chunkCache.clear(); }
	}

	// ------------------------------------------------------------------------------------------------
	// saving
	// ------------------------------------------------------------------------------------------------

	/** Saves the points of an entry: in memory during a batch ({@link #beginBatch()}), otherwise in a staging file. */
	public void savePoints( final Key key, final Points value )
	{
		if ( batchOpen )
			stage( pointSide, key, value );
		else
			writeStagingFile( Map.of( key, value ), Map.of() );
	}

	/** Saves the correspondences of an entry, like {@link #savePoints}. The list is copied. */
	public void saveCorrespondences( final Key key, final Collection< CorrespondingInterestPoints > value )
	{
		final ArrayList< CorrespondingInterestPoints > copy = new ArrayList<>( value.size() );
		for ( final CorrespondingInterestPoints correspondence : value )
			copy.add( new CorrespondingInterestPoints( correspondence ) );

		if ( batchOpen )
			stage( correspondenceSide, key, copy );
		else
			writeStagingFile( Map.of(), Map.of( key, copy ) );
	}

	/** Stages a value this store now owns. */
	private < T > void stage( final Side< T > side, final Key key, final T value )
	{
		synchronized ( lock )
		{
			side.staged.put( key, value );
			side.removed.remove( key );
		}
	}

	/** Removes the points and correspondences of an entry at the next commit. */
	public void remove( final Key key )
	{
		synchronized ( lock )
		{
			for ( final Side< ? > side : sides )
			{
				side.staged.remove( key );
				side.removed.add( key );
			}
		}
	}

	/**
	 * Starts a batch that this JVM commits right away: saves stay in memory until {@link #commit()}. Outside a batch (for
	 * example on Spark executors, which never commit) every save writes a staging file, so nothing is lost.
	 */
	public void beginBatch() { batchOpen = true; }

	/**
	 * Writes one staging file with all given entries, without a commit. Other JVMs can read it at once; the next commit
	 * folds it into the arrays.
	 */
	public void writeStagingFile( final Map< Key, Points > points, final Map< Key, List< CorrespondingInterestPoints > > correspondences )
	{
		final TreeSet< Key > keys = new TreeSet<>( points.keySet() );
		keys.addAll( correspondences.keySet() );
		if ( keys.isEmpty() )
			return;

		try
		{
			final List< byte[] > payloads = new ArrayList<>();
			final List< StagingRow > rows = new ArrayList<>();
			for ( final Key key : keys )
			{
				final Points entryPoints = points.get( key );
				final List< CorrespondingInterestPoints > entryCorrespondences = correspondences.get( key );

				int pointsPayload = -1;
				if ( entryPoints != null )
				{
					pointsPayload = payloads.size();
					payloads.add( encodePoints( entryPoints ) );
				}

				int correspondencesPayload = -1;
				if ( entryCorrespondences != null )
				{
					correspondencesPayload = payloads.size();
					payloads.add( encodeCorrespondences( entryCorrespondences ) );
				}

				rows.add( new StagingRow(
						key,
						pointsPayload, entryPoints == null ? 0 : entryPoints.size(),
						correspondencesPayload, entryCorrespondences == null ? 0 : entryCorrespondences.size() ) );
			}

			// the header length does not depend on the offsets, so a first encoding measures it
			final long[] offsets = new long[ payloads.size() ];
			long offset = encodeHeader( rows, offsets ).length;
			for ( int i = 0; i < payloads.size(); ++i )
			{
				offsets[ i ] = offset;
				offset += payloads.get( i ).length;
			}
			final byte[] header = encodeHeader( rows, offsets );

			final String name = stagingFileName();
			final KeyValueAccess kva = keyValueAccess( container.writer() );
			kva.createDirectories( kva.compose( containerURI, STAGING ) );
			final ByteArrayOutputStream file = new ByteArrayOutputStream( (int) offset );
			file.write( header );
			for ( final byte[] payload : payloads )
				file.write( payload );
			kva.write( stagingPath( kva, name ), ReadData.from( file.toByteArray() ) );

			// the file now has these entries: drop older versions staged in memory and add the file to the listing
			final List< StagingEntry > entries = parseHeader( name, new DataInputStream( new ByteArrayInputStream( header ) ) );
			synchronized ( lock )
			{
				for ( final StagingEntry entry : entries )
					for ( final Side< ? > side : sides )
						if ( side.ref( entry ) != null )
						{
							side.staged.remove( entry.key );
							side.removed.remove( entry.key );
						}
				index(); // the directory must be listed before the file is added
				parsedStagingFiles.put( name, entries );
				rebuildStagingMaps();
			}
		}
		catch ( final IOException e )
		{
			throw new RuntimeException( "could not write staging file", e );
		}
	}

	private static byte[] encodeHeader( final List< StagingRow > rows, final long[] offsets ) throws IOException
	{
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final DataOutputStream out = new DataOutputStream( bytes );
		out.writeInt( STAGING_MAGIC );
		out.writeInt( STAGING_VERSION );
		out.writeInt( rows.size() );
		for ( final StagingRow row : rows )
		{
			out.writeInt( row.key.tp );
			out.writeInt( row.key.setup );
			out.writeUTF( row.key.label );
			writeStagingRef( out, row.pointsPayload, offsets, row.pointCount );
			writeStagingRef( out, row.correspondencesPayload, offsets, row.correspondenceCount );
		}
		out.flush();
		return bytes.toByteArray();
	}

	/** Writes (present, offset, count) of a payload; payload -1 = not present. */
	private static void writeStagingRef( final DataOutputStream out, final int payload, final long[] offsets, final int count ) throws IOException
	{
		out.writeBoolean( payload >= 0 );
		out.writeLong( payload >= 0 ? offsets[ payload ] : 0L );
		out.writeInt( count );
	}

	/** ids (int32), locations (float64), the number of attributes, then per attribute its name and values; big-endian. */
	private static byte[] encodePoints( final Points points ) throws IOException
	{
		final int count = points.size();
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream( count * 28 + 4 + points.attributes().size() * ( 16 + count * 8 ) );

		final ByteBuffer ids = ByteBuffer.allocate( count * 4 );
		ids.asIntBuffer().put( points.ids );
		bytes.write( ids.array() );
		bytes.write( doubleBytes( points.loc ) );

		final DataOutputStream out = new DataOutputStream( bytes );
		out.writeInt( points.attributes().size() );
		for ( final Map.Entry< String, double[] > attribute : points.attributes().entrySet() )
		{
			out.writeUTF( attribute.getKey() );
			out.write( doubleBytes( attribute.getValue() ) );
		}
		out.flush();
		return bytes.toByteArray();
	}

	private static Points decodePoints( final DataInputStream in, final int count ) throws IOException
	{
		final int[] ids = new int[ count ];
		ByteBuffer.wrap( readFully( in, count * 4, "ids" ) ).asIntBuffer().get( ids );
		final double[] loc = readDoubles( in, count * 3, "locations" );

		final TreeMap< String, double[] > attributes = new TreeMap<>();
		final int attributeCount = in.readInt();
		for ( int a = 0; a < attributeCount; ++a )
		{
			final String name = in.readUTF();
			attributes.put( name, readDoubles( in, count, "attribute " + name ) );
		}
		return new Points( ids, loc, attributes );
	}

	private static byte[] doubleBytes( final double[] values )
	{
		final ByteBuffer buffer = ByteBuffer.allocate( values.length * 8 );
		buffer.asDoubleBuffer().put( values );
		return buffer.array();
	}

	private static double[] readDoubles( final DataInputStream in, final int count, final String what ) throws IOException
	{
		final double[] values = new double[ count ];
		ByteBuffer.wrap( readFully( in, count * 8, what ) ).asDoubleBuffer().get( values );
		return values;
	}

	private static byte[] readFully( final DataInputStream in, final int length, final String what ) throws IOException
	{
		final byte[] bytes = in.readNBytes( length );
		if ( bytes.length < length )
			throw new EOFException( "truncated " + what + " payload (" + bytes.length + " of " + length + " bytes)" );

		return bytes;
	}

	/** The partner labels once, then per correspondence: detection, partner tp, setup, label index, detection, consensus set. */
	private static byte[] encodeCorrespondences( final List< CorrespondingInterestPoints > correspondences ) throws IOException
	{
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream( 64 + correspondences.size() * 24 );
		final DataOutputStream out = new DataOutputStream( bytes );

		final List< String > labels = new ArrayList<>();
		for ( final CorrespondingInterestPoints correspondence : correspondences )
			if ( !labels.contains( correspondence.getCorrespodingLabel() ) )
				labels.add( correspondence.getCorrespodingLabel() );

		out.writeInt( labels.size() );
		for ( final String label : labels )
			out.writeUTF( label );

		for ( final CorrespondingInterestPoints correspondence : correspondences )
		{
			out.writeInt( correspondence.getDetectionId() );
			out.writeInt( correspondence.getCorrespondingViewId().getTimePointId() );
			out.writeInt( correspondence.getCorrespondingViewId().getViewSetupId() );
			out.writeInt( labels.indexOf( correspondence.getCorrespodingLabel() ) );
			out.writeInt( correspondence.getCorrespondingDetectionId() );
			out.writeInt( correspondence.getConsensusSetId() );
		}
		out.flush();
		return bytes.toByteArray();
	}

	private static List< CorrespondingInterestPoints > decodeCorrespondences( final DataInputStream in, final int count ) throws IOException
	{
		final String[] labels = new String[ in.readInt() ];
		for ( int i = 0; i < labels.length; ++i )
			labels[ i ] = in.readUTF();

		final ArrayList< CorrespondingInterestPoints > correspondences = new ArrayList<>( count );
		for ( int i = 0; i < count; ++i )
		{
			final int detection = in.readInt();
			final int partnerTp = in.readInt();
			final int partnerSetup = in.readInt();
			final int partnerLabel = in.readInt();
			final int partnerDetection = in.readInt();
			final int consensusSet = in.readInt();
			correspondences.add( new CorrespondingInterestPoints( detection, partnerTp, partnerSetup, labels[ partnerLabel ], partnerDetection, consensusSet ) );
		}
		return correspondences;
	}

	/** Opens a staging file for reading; closing the stream also releases the underlying data. */
	private DataInputStream openStagingFile( final KeyValueAccess kva, final String name ) throws IOException
	{
		final VolatileReadData data = kva.createReadData( stagingPath( kva, name ) );
		return new DataInputStream( new BufferedInputStream( data.inputStream() ) )
		{
			@Override
			public void close() throws IOException
			{
				try
				{
					super.close();
				}
				finally
				{
					data.close();
				}
			}
		};
	}

	/** Opens a staging file at a payload. */
	private DataInputStream openPayload( final StagingRef ref ) throws IOException
	{
		final DataInputStream in = openStagingFile( keyValueAccess( container.reader() ), ref.file );
		in.skipNBytes( ref.offset );
		return in;
	}

	/** Reads one payload from a staging file; if the file is gone (another JVM committed), reads the entry again. */
	private < T > T readStaged( final Side< T > side, final StagingRef ref, final Key key )
	{
		try ( final DataInputStream in = openPayload( ref ) )
		{
			return side.decode( in, ref.count );
		}
		catch ( final IOException | N5Exception e )
		{
			if ( !isMissingFile( e ) )
				throw new RuntimeException( "could not read staging file " + ref.file + " for " + key, e );

			stagingFileGone( ref.file );
			return side.reload( key );
		}
	}

	/** Reads a whole staging file once and decodes the requested payloads from memory. */
	private void readStagingFile( final String file, final List< FoldRef< ? > > refs )
	{
		final byte[] bytes;
		try ( final DataInputStream in = openStagingFile( keyValueAccess( container.reader() ), file ) )
		{
			bytes = in.readAllBytes();
		}
		catch ( final IOException e )
		{
			throw new RuntimeException( "could not read staging file " + file, e );
		}

		for ( final FoldRef< ? > fold : refs )
		{
			final StagingRef ref = fold.ref;
			if ( ref.offset > bytes.length )
				throw new RuntimeException( "staging file " + file + " is truncated (" + bytes.length + " bytes, entry " + fold.key + " at " + ref.offset + ")" );

			try ( final DataInputStream in = new DataInputStream( new ByteArrayInputStream( bytes, (int) ref.offset, bytes.length - (int) ref.offset ) ) )
			{
				fold.fold( in );
			}
			catch ( final IOException e )
			{
				throw new RuntimeException( "could not decode " + fold.key + " from staging file " + file, e );
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// commit
	// ------------------------------------------------------------------------------------------------

	/**
	 * Makes all staged changes and staging files durable: appends to or rewrites the arrays, writes the next index
	 * generation, and then switches the root attributes to it. A crash before the switch leaves the old generation intact.
	 */
	public void commit()
	{
		try
		{
			commitLocked();
		}
		finally
		{
			batchOpen = false; // a failed commit must not leave this JVM in batch mode
		}
	}

	private void commitLocked()
	{
		synchronized ( lock )
		{
			final long startTime = System.currentTimeMillis();

			// pick up staging files and a newer generation from other JVMs
			container.retryReader();
			relistStagingFiles();
			final Index old = index();
			final Set< String > stagingFiles = new HashSet<>( parsedStagingFiles.keySet() ); // all obsolete after this commit

			final long foldStart = System.currentTimeMillis();
			final int foldedEntries = foldStagingFiles();
			final long foldMs = System.currentTimeMillis() - foldStart;

			if ( sides.stream().allMatch( side -> side.staged.isEmpty() && side.removed.isEmpty() ) )
				return;

			final N5Writer zarr = container.writer();
			final List< String > labels = new ArrayList<>( old.labels );
			final Map< String, Integer > labelIds = new HashMap<>();
			for ( int i = 0; i < labels.size(); ++i )
				labelIds.put( labels.get( i ), i );

			// existing arrays keep their grid, a new store gets the defaults
			final int chunkSize = old.exists() ? old.locAttributes.getChunkSize()[ 1 ] : Math.max( 1, defaultChunkPoints );
			final int shardSize = old.exists() ? old.locAttributes.getBlockSize()[ 1 ] : roundUp( Math.max( chunkSize, defaultShardPoints ), chunkSize );

			final long pointsStart = System.currentTimeMillis();
			final PointsCommit points = commitPoints( zarr, old, chunkSize, shardSize );
			final long pointsMs = System.currentTimeMillis() - pointsStart;

			final long correspondencesStart = System.currentTimeMillis();
			final CorrespondencesCommit correspondences = commitCorrespondences( zarr, old, chunkSize, shardSize, labels, labelIds );
			final long correspondencesMs = System.currentTimeMillis() - correspondencesStart;

			// every entry with points or staged correspondences gets a views row, even without pairs
			final Map< Key, List< PairRow > > pairs = correspondences.pairs;
			for ( final Key key : points.ranges.keySet() )
				pairs.putIfAbsent( key, new ArrayList<>() );
			for ( final Key key : correspondenceSide.staged.keySet() )
				if ( !correspondenceSide.removed.contains( key ) )
					pairs.putIfAbsent( key, new ArrayList<>() );
			for ( final Key key : correspondenceSide.removed )
				pairs.remove( key );
			for ( final Key key : pairs.keySet() )
				addLabel( labels, labelIds, key.label );

			final long indexStart = System.currentTimeMillis();
			final int generation = old.generation + 1;
			writeIndex( zarr, generation, points.ranges, pairs, labelIds );

			final Map< String, Object > rootAttributes = new HashMap<>();
			rootAttributes.put( ATTR_VERSION, VERSION );
			rootAttributes.put( ATTR_GENERATION, generation );
			rootAttributes.put( ATTR_POINTS_DATA, points.data );
			rootAttributes.put( ATTR_CORRESPONDENCES_DATA, correspondences.data );
			rootAttributes.put( ATTR_LABELS, labels );
			rootAttributes.put( ATTR_CHUNK_SIZE, chunkSize );
			rootAttributes.put( ATTR_SHARD_SIZE, shardSize );
			rootAttributes.put( ATTR_POINT_ATTRIBUTES, points.attributeNames );
			zarr.setAttributes( "/", rootAttributes ); // the commit point
			final long indexMs = System.currentTimeMillis() - indexStart;

			final long cleanupStart = System.currentTimeMillis();
			removeOldGeneration( zarr, old, points.appended, correspondences.appended );
			deleteStagingFiles( zarr, stagingFiles );
			final long cleanupMs = System.currentTimeMillis() - cleanupStart;

			final int stagedPointEntries = pointSide.staged.size();
			final int stagedCorrespondenceEntries = correspondenceSide.staged.size();
			for ( final Side< ? > side : sides )
			{
				side.staged.clear();
				side.removed.clear();
			}
			batchOpen = false;
			clearCache();

			index = new Index(
					generation, labels, points.data, correspondences.data, points.ranges, pairs, points.total, correspondences.total,
					zarr.getDatasetAttributes( points.data + "/loc" ),
					zarr.getDatasetAttributes( points.data + "/id" ),
					zarr.getDatasetAttributes( correspondences.data + "/data" ),
					List.copyOf( points.attributeNames ) );

			IOFunctions.println( "InterestPointsZarrStore: committed generation " + generation
					+ " (" + stagedPointEntries + " point entries, " + stagedCorrespondenceEntries + " correspondence entries"
					+ ", points " + ( points.appended ? "appended" : "rewritten" )
					+ ", correspondences " + ( correspondences.appended ? "appended" : "rewritten" )
					+ ", " + points.ranges.size() + " entries / " + points.total + " points / " + correspondences.total + " correspondences total"
					+ ", chunk " + chunkSize + " / shard " + shardSize + ") in " + ( System.currentTimeMillis() - startTime ) + " ms"
					+ " [fold " + foldedEntries + " entries " + foldMs + " ms, points " + pointsMs + " ms, correspondences " + correspondencesMs
					+ " ms, index " + indexMs + " ms, cleanup of old generation and " + stagingFiles.size() + " staging files " + cleanupMs + " ms]" );
		}
	}

	/**
	 * Reads the staging payloads that no in-memory change replaces into the staged maps, every file once.
	 *
	 * @return the number of entries read
	 */
	private int foldStagingFiles()
	{
		final Map< String, List< FoldRef< ? > > > byFile = new HashMap<>();
		int count = 0;
		for ( final Side< ? > side : sides )
			for ( final Map.Entry< Key, StagingRef > staging : side.staging.entrySet() )
			{
				final Key key = staging.getKey();
				if ( !side.staged.containsKey( key ) && !side.removed.contains( key ) )
				{
					byFile.computeIfAbsent( staging.getValue().file, file -> new ArrayList<>() ).add( new FoldRef<>( key, staging.getValue(), side ) );
					++count;
				}
			}

		if ( !byFile.isEmpty() )
			parallel( "reading staging files", () -> byFile.entrySet().parallelStream().forEach(
					file -> readStagingFile( file.getKey(), file.getValue() ) ) );

		return count;
	}

	/** Writes the points of all staged entries; a rewrite also copies the kept entries. */
	private PointsCommit commitPoints( final N5Writer zarr, final Index old, final int chunkSize, final int shardSize )
	{
		final List< Key > kept = new ArrayList<>();
		long keptPoints = 0;
		for ( final Map.Entry< Key, long[] > entry : old.points.entrySet() )
		{
			final Key key = entry.getKey();
			if ( !pointSide.staged.containsKey( key ) && !pointSide.removed.contains( key ) )
			{
				kept.add( key );
				keptPoints += entry.getValue()[ 1 ];
			}
		}

		long newPoints = 0;
		boolean newColumns = false; // a new attribute name changes the shape of loc
		for ( final Points entry : pointSide.staged.values() )
		{
			newPoints += entry.size();
			newColumns |= !old.attributeNames.containsAll( entry.attributes().keySet() );
		}

		final boolean append = !newColumns && canAppend( old.pointsData, keptPoints, newPoints, old.pointCount );

		final TreeMap< Key, long[] > ranges = new TreeMap<>();
		final List< Key > toWrite = new ArrayList<>( pointSide.staged.keySet() );
		Collections.sort( toWrite );
		final String data;
		final long start;
		if ( append )
		{
			data = old.pointsData;
			start = old.pointCount;
			for ( final Key key : kept )
				ranges.put( key, old.points.get( key ) );
		}
		else
		{
			data = "points/g" + ( old.generation + 1 );
			start = 0;
			Collections.sort( kept );
			toWrite.addAll( 0, kept ); // kept entries first, then the staged ones
		}

		final List< Points > toWritePoints = new ArrayList<>();
		long end = start;
		for ( final Key key : toWrite )
		{
			final Points entry = pointSide.staged.containsKey( key ) ? pointSide.staged.get( key ) : readPoints( old, key );
			toWritePoints.add( entry );
			ranges.put( key, new long[] { end, entry.size() } );
			end += entry.size();
		}

		// loc columns 3, 4, ...: an append keeps the columns, a rewrite uses those that some entry has (sorted)
		final List< String > attributeNames;
		if ( append )
		{
			attributeNames = old.attributeNames;
		}
		else
		{
			final TreeSet< String > used = new TreeSet<>();
			for ( final Points points : toWritePoints )
				used.addAll( points.attributes().keySet() );
			attributeNames = new ArrayList<>( used );
		}

		writePoints( zarr, data, append ? old : null, start, toWritePoints, end, shardSize, chunkSize, attributeNames );
		return new PointsCommit( data, append, ranges, end, attributeNames );
	}

	/**
	 * Writes the correspondences of all staged entries; a rewrite also copies the kept pairs. A staged or removed entry
	 * replaces every pair it is part of.
	 */
	private CorrespondencesCommit commitCorrespondences( final N5Writer zarr, final Index old, final int chunkSize, final int shardSize,
			final List< String > labels, final Map< String, Integer > labelIds )
	{
		final Set< Key > replaced = new HashSet<>( correspondenceSide.staged.keySet() );
		replaced.addAll( correspondenceSide.removed );

		// existing pairs (each once, from the A side) that no staged or removed entry is part of
		final List< KeptPair > keptPairs = new ArrayList<>();
		long keptRows = 0;
		for ( final Map.Entry< Key, List< PairRow > > entry : old.pairs.entrySet() )
			for ( final PairRow row : entry.getValue() )
				if ( !row.swapped && !replaced.contains( entry.getKey() ) && !replaced.contains( row.partner ) )
				{
					keptPairs.add( new KeptPair( entry.getKey(), row.partner, row ) );
					keptRows += row.count;
				}

		// staged pairs by A < B, as rows with A's detection in column 0
		final TreeMap< Key, TreeMap< Key, int[][] > > stagedPairs = new TreeMap<>();
		long stagedRows = 0;
		for ( final Map.Entry< Key, List< CorrespondingInterestPoints > > entry : correspondenceSide.staged.entrySet() )
		{
			final Key key = entry.getKey();
			for ( final Map.Entry< Key, List< CorrespondingInterestPoints > > byPartner : groupByPartner( entry.getValue() ).entrySet() )
			{
				final Key partner = byPartner.getKey();
				if ( correspondenceSide.removed.contains( partner ) )
					continue;

				final boolean keyIsA = key.compareTo( partner ) <= 0;
				if ( !keyIsA && correspondenceSide.staged.containsKey( partner ) )
					continue; // both sides are staged: the A side defines the pair

				final Key a = keyIsA ? key : partner;
				final Key b = keyIsA ? partner : key;
				final int[][] rows = toRows( byPartner.getValue(), !keyIsA );
				stagedPairs.computeIfAbsent( a, x -> new TreeMap<>() ).put( b, rows );
				stagedRows += rows[ 0 ].length;
				addLabel( labels, labelIds, a.label );
				addLabel( labels, labelIds, b.label );
			}
		}

		final boolean append = canAppend( old.correspondencesData, keptRows, stagedRows, old.correspondenceCount );
		final String data = append ? old.correspondencesData : "correspondences/g" + ( old.generation + 1 );
		final long start = append ? old.correspondenceCount : 0;

		final Map< Key, List< PairRow > > pairs = new HashMap<>();
		final List< int[][] > toWrite = new ArrayList<>();
		long end = start;
		for ( final KeptPair kept : keptPairs )
		{
			if ( append )
			{
				addPair( pairs, kept.a, kept.b, kept.row.offset, kept.row.count ); // stays where it is
			}
			else
			{
				toWrite.add( readRows( old, kept.row ) );
				addPair( pairs, kept.a, kept.b, end, kept.row.count );
				end += kept.row.count;
			}
		}

		for ( final Map.Entry< Key, TreeMap< Key, int[][] > > byA : stagedPairs.entrySet() )
			for ( final Map.Entry< Key, int[][] > byB : byA.getValue().entrySet() )
			{
				final int count = byB.getValue()[ 0 ].length;
				toWrite.add( byB.getValue() );
				addPair( pairs, byA.getKey(), byB.getKey(), end, count );
				end += count;
			}

		writeCorrespondences( zarr, data, append ? old : null, start, toWrite, end, shardSize, chunkSize );
		return new CorrespondencesCommit( data, append, pairs, end );
	}

	private static Map< Key, List< CorrespondingInterestPoints > > groupByPartner( final List< CorrespondingInterestPoints > correspondences )
	{
		final Map< Key, List< CorrespondingInterestPoints > > byPartner = new HashMap<>();
		for ( final CorrespondingInterestPoints correspondence : correspondences )
			byPartner.computeIfAbsent( partnerOf( correspondence ), partner -> new ArrayList<>() ).add( correspondence );

		return byPartner;
	}

	/** Correspondences as rows [3][n]: own detection, partner detection, consensus set; {@code swapped} puts the partner first. */
	private static int[][] toRows( final List< CorrespondingInterestPoints > correspondences, final boolean swapped )
	{
		final int[][] rows = new int[ 3 ][ correspondences.size() ];
		for ( int i = 0; i < correspondences.size(); ++i )
		{
			final CorrespondingInterestPoints correspondence = correspondences.get( i );
			rows[ swapped ? 1 : 0 ][ i ] = correspondence.getDetectionId();
			rows[ swapped ? 0 : 1 ][ i ] = correspondence.getCorrespondingDetectionId();
			rows[ 2 ][ i ] = correspondence.getConsensusSetId();
		}
		return rows;
	}

	/** The rows of an existing pair, A's detection in column 0. */
	private int[][] readRows( final Index index, final PairRow row )
	{
		final List< CorrespondingInterestPoints > correspondences = new ArrayList<>( row.count );
		readPairRows( index, row, correspondences );
		return toRows( correspondences, false );
	}

	/** Writes the index tables of a generation: entries, views, and pairs. */
	private static void writeIndex( final N5Writer zarr, final int generation, final TreeMap< Key, long[] > ranges,
			final Map< Key, List< PairRow > > pairs, final Map< String, Integer > labelIds )
	{
		final long[] entryRows = new long[ 5 * ranges.size() ];
		int entryPosition = 0;
		for ( final Map.Entry< Key, long[] > entry : ranges.entrySet() )
		{
			entryPosition = writeKey( entryRows, entryPosition, entry.getKey(), labelIds );
			entryRows[ entryPosition++ ] = entry.getValue()[ 0 ];
			entryRows[ entryPosition++ ] = entry.getValue()[ 1 ];
		}

		final TreeMap< Key, List< PairRow > > sortedPairs = new TreeMap<>( pairs );
		int pairCount = 0;
		for ( final List< PairRow > rows : sortedPairs.values() )
			pairCount += rows.size();

		final long[] viewRows = new long[ 5 * sortedPairs.size() ];
		final long[] pairRows = new long[ 6 * pairCount ];
		int viewPosition = 0;
		int pairPosition = 0;
		for ( final Map.Entry< Key, List< PairRow > > entry : sortedPairs.entrySet() )
		{
			viewPosition = writeKey( viewRows, viewPosition, entry.getKey(), labelIds );
			viewRows[ viewPosition++ ] = pairPosition / 6; // first pair row
			viewRows[ viewPosition++ ] = entry.getValue().size();

			for ( final PairRow row : entry.getValue() )
			{
				pairPosition = writeKey( pairRows, pairPosition, row.partner, labelIds );
				pairRows[ pairPosition++ ] = row.offset;
				pairRows[ pairPosition++ ] = row.count;
				pairRows[ pairPosition++ ] = row.swapped ? 1 : 0;
			}
		}

		writeLongs( zarr, indexGroup( generation ) + "/entries", 5, entryRows );
		writeLongs( zarr, indexGroup( generation ) + "/views", 5, viewRows );
		writeLongs( zarr, indexGroup( generation ) + "/pairs", 6, pairRows );
	}

	/** Deletes the previous generation's index, and its arrays where this commit rewrote them. */
	private static void removeOldGeneration( final N5Writer zarr, final Index old, final boolean pointsAppended, final boolean correspondencesAppended )
	{
		if ( !old.exists() )
			return;

		removeIfExists( zarr, indexGroup( old.generation ) );
		if ( !pointsAppended )
			removeIfExists( zarr, old.pointsData );
		if ( !correspondencesAppended )
			removeIfExists( zarr, old.correspondencesData );
	}

	/** Deletes the staging files that this commit folded in or replaced. */
	private void deleteStagingFiles( final N5Writer zarr, final Set< String > files )
	{
		if ( files.isEmpty() )
			return;

		final KeyValueAccess kva = keyValueAccess( zarr );
		parallel( "deleting staging files", () -> files.parallelStream().forEach( file -> {
			final String path = stagingPath( kva, file );
			if ( kva.exists( path ) )
				kva.delete( path );
		} ) );

		parsedStagingFiles.keySet().removeAll( files );
		rebuildStagingMaps();
	}

	private static void removeIfExists( final N5Writer zarr, final String path )
	{
		if ( zarr.exists( path ) )
			zarr.remove( path );
	}

	/** Runs {@code body} (which uses parallel streams) in a pool of {@link Threads#numThreads()} threads and waits for it. */
	static void parallel( final String what, final Runnable body )
	{
		final ForkJoinPool pool = new ForkJoinPool( Threads.numThreads() );
		try
		{
			pool.submit( body ).get();
		}
		catch ( final InterruptedException | ExecutionException e )
		{
			throw new RuntimeException( what + " failed", e );
		}
		finally
		{
			pool.shutdown();
		}
	}

	private static void addLabel( final List< String > labels, final Map< String, Integer > labelIds, final String label )
	{
		if ( !labelIds.containsKey( label ) )
		{
			labelIds.put( label, labels.size() );
			labels.add( label );
		}
	}

	private static int roundUp( final int value, final int multiple ) { return ( ( value + multiple - 1 ) / multiple ) * multiple; }

	/** An existing array is appended to if, after the commit, at least {@link #minLiveFractionForAppend} of its rows are still in use. */
	private static boolean canAppend( final String existingData, final long keptRows, final long newRows, final long existingRows )
	{
		return existingData != null && keptRows + newRows >= minLiveFractionForAppend * ( existingRows + newRows );
	}

	/** Adds the pair to both entries: to a as is, to b as swapped. */
	private static void addPair( final Map< Key, List< PairRow > > pairs, final Key a, final Key b, final long offset, final int count )
	{
		pairs.computeIfAbsent( a, key -> new ArrayList<>() ).add( new PairRow( b, offset, count, false ) );
		if ( !a.equals( b ) )
			pairs.computeIfAbsent( b, key -> new ArrayList<>() ).add( new PairRow( a, offset, count, true ) );
	}

	// ------------------------------------------------------------------------------------------------
	// Zarr v3 arrays
	// ------------------------------------------------------------------------------------------------

	/** A sharded array [columns, rows]: raw bytes, with a crc32c on every chunk and on the shard index. */
	static DatasetAttributes arrayAttributes( final int columns, final long rows, final DataType type, final int shardSize, final int chunkSize )
	{
		return rawChecksummed( columns, rows, type, shardSize )
				.chunkSize( new int[] { columns, chunkSize } )
				.shardIndexDataCodecInfos( new Crc32cChecksumCodec() )
				.build();
	}

	/** A [columns, rows] array of raw bytes with a crc32c on every chunk: compression gained about 2 % and cost a JNI decoder on every read. */
	private static ZarrV3DatasetAttributes.Builder rawChecksummed( final int columns, final long rows, final DataType type, final int blockRows )
	{
		return ZarrV3DatasetAttributes.builder( new long[] { columns, rows }, type )
				.blockSize( new int[] { columns, blockRows } )
				.compression( new RawCompression() )
				.dataCodecInfos( new Crc32cChecksumCodec() ); // damaged chunks fail instead of decoding to garbage
	}

	/** Creates the array (replacing a stale one), or only sets the new (longer) shape of an existing one. */
	private static void prepareDataset( final N5Writer zarr, final String dataset, final DatasetAttributes attributes, final boolean create )
	{
		if ( !create )
		{
			zarr.setDatasetAttributes( dataset, attributes );
			return;
		}
		removeIfExists( zarr, dataset );
		zarr.createDataset( dataset, attributes );
	}

	/** @return the existing chunk that an append from {@code start} lands in, or null if {@code start} is on a chunk border */
	private < C > C partialFirstChunk( final String dataset, final DatasetAttributes attributes, final long start )
	{
		final int chunkSize = attributes.getChunkSize()[ 1 ];
		final long firstChunk = start / chunkSize;
		return firstChunk * chunkSize < start ? readChunk( dataset, attributes, firstChunk ) : null;
	}

	/** Copies the rows before {@code start} of a partly filled first chunk into the new chunk buffer. */
	private static void keepOldRows( final Object oldChunk, final Object buffer, final long chunkStart, final long start, final int columns )
	{
		if ( chunkStart < start )
			System.arraycopy( oldChunk, 0, buffer, 0, (int) ( start - chunkStart ) * columns );
	}

	/** Returns the complete chunk of {@code count} rows from {@code chunkStart}. */
	interface ChunkFiller< T > { DataBlock< T > chunk( long chunkStart, int count ); }

	/**
	 * Writes the rows [start, end) of a sharded array, one shard per call (the library merges into an existing partial
	 * shard). For a first chunk that starts before {@code start}, {@code filler} includes the existing rows.
	 */
	private static < T > void writeRange( final N5Writer zarr, final String dataset, final DatasetAttributes attributes,
			final long start, final long end, final ChunkFiller< T > filler )
	{
		if ( end <= start )
			return;

		final int shardSize = attributes.getBlockSize()[ 1 ];
		final int chunkSize = attributes.getChunkSize()[ 1 ];
		final long rows = attributes.getDimensions()[ 1 ];
		final long firstShard = start / shardSize;
		final long lastShard = ( end - 1 ) / shardSize;

		parallel( "writing " + dataset, () -> LongStream.rangeClosed( firstShard, lastShard ).parallel().forEach( shard -> {
			final long shardStart = shard * shardSize;
			final long shardEnd = Math.min( rows, shardStart + shardSize );
			final long writeEnd = Math.min( shardEnd, end );

			final List< DataBlock< T > > chunks = new ArrayList<>();
			for ( long chunkStart = Math.max( shardStart, ( start / chunkSize ) * chunkSize ); chunkStart < writeEnd; chunkStart += chunkSize )
				chunks.add( filler.chunk( chunkStart, (int) ( Math.min( chunkStart + chunkSize, shardEnd ) - chunkStart ) ) );

			@SuppressWarnings( "unchecked" )
			final DataBlock< T >[] chunkArray = chunks.toArray( new DataBlock[ 0 ] );
			zarr.writeChunks( dataset, attributes, chunkArray );
		} ) );
	}

	/**
	 * Writes the entries one after another from {@code start}. Creates the arrays if {@code old} is null, else appends to
	 * them. loc gets x, y, z and then the {@code attributeNames} columns.
	 */
	private void writePoints( final N5Writer zarr, final String group, final Index old, final long start, final List< Points > entries,
			final long end, final int shardSize, final int chunkSize, final List< String > attributeNames )
	{
		final int columns = 3 + attributeNames.size();
		final DatasetAttributes locAttributes = arrayAttributes( columns, end, DataType.FLOAT64, shardSize, chunkSize );
		final DatasetAttributes idAttributes = arrayAttributes( 1, end, DataType.INT32, shardSize, chunkSize );
		prepareDataset( zarr, group + "/loc", locAttributes, old == null );
		prepareDataset( zarr, group + "/id", idAttributes, old == null );

		final long[] offsets = entryOffsets( entries, start, Points::size );
		final double[] oldLoc = old == null ? null : partialFirstChunk( old.pointsData + "/loc", old.locAttributes, start );
		final int[] oldIds = old == null ? null : partialFirstChunk( old.pointsData + "/id", old.idAttributes, start );

		writeRange( zarr, group + "/loc", locAttributes, start, end, ( chunkStart, count ) -> {
			final double[] buffer = new double[ count * columns ];
			keepOldRows( oldLoc, buffer, chunkStart, start, columns );

			forEntries( entries, offsets, chunkStart, count, Points::size, ( points, entryStart, from, to ) ->
					copyLocRows( points, entryStart, from, to, chunkStart, buffer, attributeNames ) );

			return new DoubleArrayDataBlock( new int[] { columns, count }, new long[] { 0, chunkStart / chunkSize }, buffer );
		} );

		writeRange( zarr, group + "/id", idAttributes, start, end, ( chunkStart, count ) -> {
			final int[] buffer = new int[ count ];
			keepOldRows( oldIds, buffer, chunkStart, start, 1 );

			forEntries( entries, offsets, chunkStart, count, Points::size, ( points, entryStart, from, to ) ->
					System.arraycopy( points.ids, (int) ( from - entryStart ), buffer, (int) ( from - chunkStart ), (int) ( to - from ) ) );

			return new IntArrayDataBlock( new int[] { 1, count }, new long[] { 0, chunkStart / chunkSize }, buffer );
		} );
	}

	/** Copies the rows [from, to) of an entry into a loc chunk: x, y, z, then the attributes (-1 where the entry has none). */
	private static void copyLocRows( final Points points, final long entryStart, final long from, final long to, final long chunkStart,
			final double[] buffer, final List< String > attributeNames )
	{
		final int attributeCount = attributeNames.size();
		if ( attributeCount == 0 )
		{
			System.arraycopy( points.loc, (int) ( from - entryStart ) * 3, buffer, (int) ( from - chunkStart ) * 3, (int) ( to - from ) * 3 );
			return;
		}

		final int columns = 3 + attributeCount;
		final double[][] values = new double[ attributeCount ][];
		for ( int a = 0; a < attributeCount; ++a )
			values[ a ] = points.attributes().get( attributeNames.get( a ) );

		for ( long row = from; row < to; ++row )
		{
			final int source = (int) ( row - entryStart );
			final int target = (int) ( row - chunkStart ) * columns;
			System.arraycopy( points.loc, source * 3, buffer, target, 3 );
			for ( int a = 0; a < attributeCount; ++a )
				buffer[ target + 3 + a ] = values[ a ] == null ? NO_VALUE : values[ a ][ source ];
		}
	}

	/** Receives the rows [from, to) of an entry that starts at {@code entryStart}. */
	interface EntryVisitor< E > { void visit( E entry, long entryStart, long from, long to ); }

	/** @return the first row of every entry when they are written one after another from {@code start} */
	private static < E > long[] entryOffsets( final List< E > entries, final long start, final ToLongFunction< E > size )
	{
		final long[] offsets = new long[ entries.size() ];
		long offset = start;
		for ( int i = 0; i < entries.size(); ++i )
		{
			offsets[ i ] = offset;
			offset += size.applyAsLong( entries.get( i ) );
		}
		return offsets;
	}

	/** Calls the visitor for every entry that overlaps the chunk [chunkStart, chunkStart + count). */
	private static < E > void forEntries( final List< E > entries, final long[] offsets, final long chunkStart, final int count,
			final ToLongFunction< E > size, final EntryVisitor< E > visitor )
	{
		int i = Arrays.binarySearch( offsets, chunkStart );
		if ( i < 0 )
			i = Math.max( 0, -i - 2 ); // the entry that starts before the chunk

		final long chunkEnd = chunkStart + count;
		for ( ; i < entries.size() && offsets[ i ] < chunkEnd; ++i )
		{
			final long entryStart = offsets[ i ];
			final long entryEnd = entryStart + size.applyAsLong( entries.get( i ) );
			final long from = Math.max( entryStart, chunkStart );
			final long to = Math.min( entryEnd, chunkEnd );
			if ( to > from )
				visitor.visit( entries.get( i ), entryStart, from, to );
		}
	}

	/** Writes pairs (each as rows [3][n]) one after another from {@code start}, like {@link #writePoints}. */
	private void writeCorrespondences( final N5Writer zarr, final String group, final Index old, final long start, final List< int[][] > pairs,
			final long end, final int shardSize, final int chunkSize )
	{
		final DatasetAttributes attributes = arrayAttributes( 3, end, DataType.INT32, shardSize, chunkSize );
		prepareDataset( zarr, group + "/data", attributes, old == null );

		final long[] offsets = entryOffsets( pairs, start, rows -> rows[ 0 ].length );
		final int[] oldData = old == null ? null : partialFirstChunk( old.correspondencesData + "/data", old.correspondenceAttributes, start );

		writeRange( zarr, group + "/data", attributes, start, end, ( chunkStart, count ) -> {
			final int[] buffer = new int[ count * 3 ];
			keepOldRows( oldData, buffer, chunkStart, start, 3 );

			forEntries( pairs, offsets, chunkStart, count, rows -> rows[ 0 ].length, ( rows, entryStart, from, to ) -> {
				for ( long row = from; row < to; ++row )
					for ( int column = 0; column < 3; ++column )
						buffer[ (int) ( row - chunkStart ) * 3 + column ] = rows[ column ][ (int) ( row - entryStart ) ];
			} );

			return new IntArrayDataBlock( new int[] { 3, count }, new long[] { 0, chunkStart / chunkSize }, buffer );
		} );
	}

	/** Writes a small unsharded index table [columns, rows] as one chunk. */
	private static void writeLongs( final N5Writer zarr, final String dataset, final int columns, final long[] values )
	{
		final int rows = values.length / columns;
		final DatasetAttributes attributes = rawChecksummed( columns, rows, DataType.INT64, Math.max( 1, rows ) ).build();

		removeIfExists( zarr, dataset );
		zarr.createDataset( dataset, attributes );
		if ( rows > 0 )
			zarr.writeBlock( dataset, attributes, new LongArrayDataBlock( new int[] { columns, rows }, new long[] { 0, 0 }, values ) );
	}

	private static long[] readLongs( final N5Reader zarr, final String dataset, final int columns )
	{
		final DatasetAttributes attributes = zarr.getDatasetAttributes( dataset );
		final long rows = attributes.getDimensions()[ 1 ];
		if ( rows == 0 )
			return new long[ 0 ];

		final long[] values = new long[ (int) ( columns * rows ) ];
		final long[] block = (long[]) zarr.readBlock( dataset, attributes, 0, 0 ).getData();
		System.arraycopy( block, 0, values, 0, values.length );
		return values;
	}
}
