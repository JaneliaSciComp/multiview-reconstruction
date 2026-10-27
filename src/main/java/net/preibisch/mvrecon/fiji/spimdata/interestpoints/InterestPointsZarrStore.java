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
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.FloatArrayDataBlock;
import org.janelia.saalfeldlab.n5.IntArrayDataBlock;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Exception;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.codec.checksum.Crc32cChecksumCodec;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.readdata.VolatileReadData;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import util.URITools;

/**
 * All interest points and correspondences of one dataset in a few sharded Zarr v3 arrays ({@code interestpoints.zarr}
 * next to the XML), one self-contained group per (label, timepoint). One instance per dataset directory ({@link #get(URI)}),
 * shared by all {@link InterestPointsZarr} lists of the dataset.
 *
 * <pre>
 * zarr.json                               root: interestpoints = "2.0.0"
 * &lt;label&gt;/tp&lt;T&gt;/zarr.json                 group: label (exact name), timepoint, generation K
 * &lt;label&gt;/tp&lt;T&gt;/gK/points   FLOAT32 [6, N]   x, y, z, response, sigma, intensity (NaN = unknown), all setups of the label at T in setup order
 * &lt;label&gt;/tp&lt;T&gt;/gK/id       INT32   [1, N]   detection ids
 * &lt;label&gt;/tp&lt;T&gt;/gK/corr     INT32   [3, M]   own detection, partner detection, consensus set; per setup, grouped by partner
 * &lt;label&gt;/tp&lt;T&gt;/gK/entries  INT64   [6, E]   setup, kind (0 plain, 1 filtered), points offset, point count, corr offset, corr count
 * &lt;label&gt;/tp&lt;T&gt;/gK/partners INT64   [6, P]   setup, partner tp, partner setup, partner label (index into the group attribute partnerLabels), offset, count
 * staging/*.stage                         one file per Spark task, folded in by the next commit
 * </pre>
 *
 * The group name is the label name percent-encoded for the file system; the exact name is a group attribute. A group is
 * replaced as a whole: a commit writes the arrays of the next generation gK+1 (the entries that did not change are copied),
 * flips the group's {@code generation} attribute under a lock file after checking that nobody else committed in between,
 * and deletes gK. Two jobs on different labels or timepoints never write the same object. There is no append and no
 * compaction: the cost of a commit is the size of the (label, timepoint), never the size of the dataset.
 *
 * Each (view, label) owns its correspondence list, as in the N5 layout before: a symmetric match is stored in both lists.
 *
 * Saves stay in memory during a batch ({@link #beginBatch()}, the driver's XML save) and are made durable by
 * {@link #commit()}; outside a batch every save writes a staging file (Spark executors never commit). Readers look in the
 * staged data, then in the staging files, then in the arrays.
 *
 * The decoded chunks of the arrays are cached per store, bounded by {@link #chunkCacheBytes} (system property
 * {@code mvr.interestpoints.cacheBytes}, 0 = no cache); this matters on Spark executors whose tasks re-create their lists.
 *
 * The staging file format, the chunk reading and the sharded array writing follow PR 108 of multiview-reconstruction
 * (Lila, 2026-09), whose global append-only layout this store replaces by per-(label, timepoint) groups.
 *
 * @author Stephan Preibisch
 */
public class InterestPointsZarrStore
{
	public static final String VERSION = "2.0.0";
	public static final String ZARR_CONTAINER = "interestpoints.zarr";

	/** columns of the points array: x, y, z, response, sigma, intensity */
	public static final int COLUMNS = 6;
	/** columns of the correspondence array: own detection, partner detection, consensus set */
	public static final int CORR_COLUMNS = 3;

	/** rows (points or correspondences) per inner chunk, the unit of a read; existing groups keep their grid */
	public static int defaultChunkRows = 65536;

	/** rows per shard (one file), rounded up to a multiple of the chunk; existing groups keep their grid */
	public static int defaultShardRows = 1 << 20;

	/** bytes of decoded chunks kept per store, 0 = no cache; -Dmvr.interestpoints.cacheBytes=... overrides the default of 256 MB */
	public static long chunkCacheBytes = Long.getLong( "mvr.interestpoints.cacheBytes", 256L << 20 );

	/** how long a cached group index is trusted before its generation is compared with the disk again (another JVM may have rewritten the group) */
	public static long generationCheckMillis = 2000;

	static final String STAGING = "staging";
	static final String STAGING_EXT = ".stage";
	static final int STAGING_MAGIC = 0x49505354;
	static final int STAGING_VERSION = 2;

	static final String ATTR_VERSION = "interestpoints";
	static final String ATTR_LABEL = "label";
	static final String ATTR_TIMEPOINT = "timepoint";
	static final String ATTR_GENERATION = "generation";
	static final String ATTR_PARTNER_LABELS = "partnerLabels";

	static final String POINTS = "points";
	static final String IDS = "id";
	static final String CORR = "corr";
	static final String ENTRIES = "entries";
	static final String PARTNERS = "partners";
	static final String LOCK = "commit.lock";

	static final int ENTRY_COLUMNS = 6;
	static final int PARTNER_COLUMNS = 6;

	public static final int KIND_PLAIN = 0;
	public static final int KIND_FILTERED = 1;

	/** One (timepoint, setup, label) entry, the XML text of a label is {@code tpId_X_viewSetupId_Y/label}. */
	public record Key( int tp, int setup, String label ) implements Comparable< Key >
	{
		private static final Pattern PATH = Pattern.compile( "tpId_(\\d+)_viewSetupId_(\\d+)/(.+)" );
		private static final Comparator< Key > ORDER = Comparator.comparingInt( Key::tp ).thenComparingInt( Key::setup ).thenComparing( Key::label );

		public static Key of( final ViewId viewId, final String label )
		{
			return new Key( viewId.getTimePointId(), viewId.getViewSetupId(), label );
		}

		/** @return the key of an XML text {@code tpId_X_viewSetupId_Y/label}, or null for a text of another form */
		public static Key parse( final String path )
		{
			final Matcher matcher = PATH.matcher( path );
			if ( !matcher.matches() )
				return null;

			return new Key( Integer.parseInt( matcher.group( 1 ) ), Integer.parseInt( matcher.group( 2 ) ), matcher.group( 3 ) );
		}

		/** @return the XML text {@code tpId_X_viewSetupId_Y/label} */
		public String path() { return InterestPointsN5.createN5datasetPath( tp, setup, label ); }

		public ViewId viewId() { return new ViewId( tp, setup ); }

		public Group group() { return new Group( label, tp ); }

		public Pair< ViewId, String > toPair() { return new ValuePair<>( viewId(), label ); }

		@Override
		public int compareTo( final Key other ) { return ORDER.compare( this, other ); }
	}

	/** One (label, timepoint): a group of the container with its own arrays and index. */
	public record Group( String label, int tp ) implements Comparable< Group >
	{
		private static final Comparator< Group > ORDER = Comparator.comparing( Group::label ).thenComparingInt( Group::tp );

		/** @return the group path inside the container, the label percent-encoded for the file system */
		public String path() { return encodeLabel( label ) + "/tp" + tp; }

		@Override
		public int compareTo( final Group other ) { return ORDER.compare( this, other ); }

		/** Letters, digits, '_', '-' and '.' stay, every other byte of the UTF-8 encoding becomes %XX; '.' and '..' are encoded entirely. */
		public static String encodeLabel( final String label )
		{
			final StringBuilder encoded = new StringBuilder();
			for ( final byte b : label.getBytes( StandardCharsets.UTF_8 ) )
			{
				final char c = (char) ( b & 0xff );
				if ( ( c >= 'a' && c <= 'z' ) || ( c >= 'A' && c <= 'Z' ) || ( c >= '0' && c <= '9' ) || c == '_' || c == '-' || c == '.' )
					encoded.append( c );
				else
					encoded.append( '%' ).append( String.format( "%02X", b & 0xff ) );
			}
			final String name = encoded.toString();
			if ( name.isEmpty() || name.equals( "." ) || name.equals( ".." ) )
				return name.replace( ".", "%2E" ) + ( name.isEmpty() ? "%" : "" );
			return name;
		}
	}

	/**
	 * The points of one entry in the store's layout: ids and {@link #COLUMNS} float32 values per point (x, y, z, response,
	 * sigma, intensity; NaN = unknown), row by row. A filtered label has no points of its own; its entry is a marker
	 * ({@code filtered}, no points) that tells readers so. Treated as immutable.
	 */
	public record Points( int[] ids, float[] data, boolean filtered )
	{
		public Points
		{
			if ( ids == null )
				ids = new int[ 0 ];
			if ( data == null )
				data = new float[ 0 ];
			if ( data.length != (long) COLUMNS * ids.length )
				throw new IllegalArgumentException( "the points have " + ids.length + " ids but " + data.length + " values, expected " + COLUMNS + " per point" );
			if ( filtered && ids.length != 0 )
				throw new IllegalArgumentException( "a filtered label has no points of its own" );
		}

		public Points( final int[] ids, final float[] data ) { this( ids, data, false ); }

		/** @return the entry of a filtered label (no points, filters its base label on the fly) */
		public static Points filteredMarker() { return new Points( new int[ 0 ], new float[ 0 ], true ); }

		public static Points empty() { return new Points( new int[ 0 ], new float[ 0 ], false ); }

		public int size() { return ids.length; }

		public int kind() { return filtered ? KIND_FILTERED : KIND_PLAIN; }
	}

	/** One partner of an entry in a staging file: how many of the entry's rows belong to it (rows are grouped by partner). */
	record PartnerCount( Key partner, int count ) {}

	/** One partner of an entry in the arrays: where its rows are in {@code corr}. */
	record PartnerRow( Key partner, long offset, int count ) {}

	/** One setup of a group in the arrays. */
	record Entry( int kind, long pointsOffset, int pointCount, long corrOffset, int corrCount, List< PartnerRow > partners ) {}

	/** The current generation of a group (immutable); {@link #missing} if the group does not exist. */
	record GroupIndex(
			Group group,
			long generation,
			Map< Integer, Entry > entries, // by setup
			String dataPath,
			DatasetAttributes pointsAttrs,
			DatasetAttributes idAttrs,
			DatasetAttributes corrAttrs )
	{
		static GroupIndex missing( final Group group ) { return new GroupIndex( group, -1, Map.of(), null, null, null, null ); }

		boolean exists() { return generation >= 0; }

		long pointRows() { return pointsAttrs.getDimensions()[ 1 ]; }

		long corrRows() { return corrAttrs.getDimensions()[ 1 ]; }
	}

	/** Where one entry's payload is inside a staging file; {@code filtered} for points, {@code partners} for correspondences. */
	record StagingRef( String file, long offset, int count, boolean filtered, List< PartnerCount > partners ) {}

	/** One row of a staging file's entry table. */
	record StagingEntry( Key key, StagingRef points, StagingRef correspondences ) {}

	/** One row of an entry table while it is written: indices into the payload list, -1 = none. */
	private record StagingRow( Key key, int pointsPayload, Points points, int corrPayload, List< PartnerCount > partners, int corrCount ) {}

	// one store per dataset directory for the whole JVM; all SpimData2 of a base path see the same files
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

	/** key-value access to the container's location, works before the container exists */
	private final KeyValueAccess kva;

	private N5Reader reader = null; // guarded by this
	private N5Writer writer = null; // guarded by this

	private final ConcurrentHashMap< Group, GroupIndex > indexes = new ConcurrentHashMap<>();
	private final ConcurrentHashMap< Group, Long > lastChecked = new ConcurrentHashMap<>(); // when a cached index was last compared with the disk

	private final Object lock = new Object();
	private final Map< Key, Points > stagedPoints = new HashMap<>(); // guarded by lock
	private final Map< Key, List< CorrespondingInterestPoints > > stagedCorrs = new HashMap<>(); // guarded by lock
	private final Set< Key > removed = new HashSet<>(); // guarded by lock
	private final Map< String, List< StagingEntry > > parsedStagingFiles = new HashMap<>(); // file name -> entry table, guarded by lock
	private volatile Map< Key, StagingRef > stagingPoints = Map.of(); // key -> payload in a staging file, a later file wins
	private volatile Map< Key, StagingRef > stagingCorrs = Map.of();
	private volatile boolean stagingListed = false;
	private volatile boolean batchOpen = false;

	private final LinkedHashMap< String, Object > chunkCache = new LinkedHashMap<>( 64, 0.75f, true ); // guarded by itself
	private long cachedBytes = 0; // guarded by chunkCache

	/** Prefer {@link #get(URI)}: a separate instance does not share its staged data, indexes and cache with the rest of the JVM. */
	public InterestPointsZarrStore( final URI baseDir )
	{
		this.baseDir = baseDir;
		this.containerURI = URITools.toURI( URITools.appendName( baseDir, ZARR_CONTAINER ) );
		this.kva = URITools.getKeyValueAccess( containerURI );
	}

	public URI getBaseDir() { return baseDir; }

	public URI getContainerURI() { return containerURI; }

	// ------------------------------------------------------------------------------------------------
	// container
	// ------------------------------------------------------------------------------------------------

	private String path( final String... parts )
	{
		return kva.compose( containerURI, parts );
	}

	/** @return true if the container exists (its root zarr.json) */
	public boolean exists() { return kva.exists( path( "zarr.json" ) ); }

	/** @return a reader of the container, or null if there is no container yet; the writer serves as the reader once it exists */
	private synchronized N5Reader reader()
	{
		if ( writer != null )
			return writer;

		if ( reader == null && exists() )
		{
			reader = URITools.instantiateN5Reader( StorageFormat.ZARR, containerURI );
			checkVersion( reader.getAttribute( "/", ATTR_VERSION, String.class ) );
		}
		return reader;
	}

	/** @return the writer of the container, created (with the root version attribute) if needed */
	private synchronized N5Writer writer()
	{
		if ( writer == null )
		{
			final boolean existed = exists();
			writer = URITools.instantiateN5Writer( StorageFormat.ZARR, containerURI );
			reader = null;

			final String version = existed ? writer.getAttribute( "/", ATTR_VERSION, String.class ) : null;
			if ( version == null )
				writer.setAttribute( "/", ATTR_VERSION, VERSION );
			else
				checkVersion( version );
		}
		return writer;
	}

	private void checkVersion( final String version )
	{
		if ( version == null )
			throw new IllegalStateException( "'" + containerURI + "' exists but is no interest point store (no '" + ATTR_VERSION + "' attribute), stopping." );
		if ( !version.startsWith( VERSION.substring( 0, VERSION.indexOf( '.' ) + 1 ) ) )
			throw new IllegalStateException( "'" + containerURI + "' is an interest point store of version " + version + ", this reader supports " + VERSION + ", stopping." );
	}

	/** The attributes of a group read from its zarr.json, bypassing the caches of the readers, or null if there is no such group. */
	private JsonObject readAttributes( final String groupPath )
	{
		final String json = path( groupPath, "zarr.json" );
		if ( !kva.exists( json ) )
			return null;

		try ( final VolatileReadData data = kva.createReadData( json ); final InputStreamReader in = new InputStreamReader( data.inputStream(), StandardCharsets.UTF_8 ) )
		{
			final JsonElement root = JsonParser.parseReader( in );
			final JsonElement attributes = root.getAsJsonObject().get( "attributes" );
			return attributes == null || !attributes.isJsonObject() ? new JsonObject() : attributes.getAsJsonObject();
		}
		catch ( final IOException | RuntimeException e )
		{
			throw new IllegalStateException( "cannot read '" + json + "' of '" + containerURI + "': " + e, e );
		}
	}

	/**
	 * @return the current generation of a group as written on disk, -1 if the group does not exist or was created but
	 *         never flipped to a generation (a commit that is under way or failed before its flip)
	 */
	long readGeneration( final Group group )
	{
		final JsonObject attributes = readAttributes( group.path() );
		if ( attributes == null )
			return -1;

		final JsonElement generation = attributes.get( ATTR_GENERATION );
		return generation == null ? -1 : generation.getAsLong();
	}

	// ------------------------------------------------------------------------------------------------
	// group index
	// ------------------------------------------------------------------------------------------------

	/**
	 * The index of a group; a cached one is checked against the generation on disk at most every
	 * {@link #generationCheckMillis}, so that a group another JVM rewrote is picked up (its cached chunks would otherwise
	 * be served on, the files of the old generation being gone or not).
	 */
	GroupIndex index( final Group group )
	{
		final GroupIndex cached = indexes.get( group );
		if ( cached == null )
			return indexes.computeIfAbsent( group, this::loadIndex );

		final long now = System.currentTimeMillis();
		final Long checked = lastChecked.get( group );
		if ( checked != null && now - checked < generationCheckMillis )
			return cached;

		if ( readGeneration( group ) == cached.generation )
		{
			lastChecked.put( group, now );
			return cached;
		}

		return reloadIndex( group );
	}

	/** Drops the cached index of a group and loads it again from disk. */
	GroupIndex reloadIndex( final Group group )
	{
		indexes.remove( group );
		dropChunks( group );
		final GroupIndex fresh = indexes.computeIfAbsent( group, this::loadIndex );
		lastChecked.put( group, System.currentTimeMillis() );
		return fresh;
	}

	private GroupIndex loadIndex( final Group group )
	{
		final long generation = readGeneration( group );
		if ( generation < 0 )
			return GroupIndex.missing( group );

		final N5Reader n5 = reader();
		final String data = dataPath( group, generation );

		final JsonObject dataAttrs = readAttributes( data );
		if ( dataAttrs == null )
			throw new IllegalStateException( "group '" + group.path() + "' of '" + containerURI + "' points at generation " + generation + ", which does not exist, stopping." );

		final List< String > partnerLabels = new ArrayList<>();
		final JsonElement labels = dataAttrs.get( ATTR_PARTNER_LABELS );
		if ( labels != null )
			for ( final JsonElement label : labels.getAsJsonArray() )
				partnerLabels.add( label.getAsString() );

		final long[] partnerRows = readLongs( n5, data + "/" + PARTNERS, PARTNER_COLUMNS );
		final Map< Integer, List< PartnerRow > > partners = new HashMap<>();
		for ( int row = 0; row < partnerRows.length; row += PARTNER_COLUMNS )
		{
			final Key partner = new Key( (int) partnerRows[ row + 1 ], (int) partnerRows[ row + 2 ], partnerLabels.get( (int) partnerRows[ row + 3 ] ) );
			partners.computeIfAbsent( (int) partnerRows[ row ], setup -> new ArrayList<>() ).add( new PartnerRow( partner, partnerRows[ row + 4 ], (int) partnerRows[ row + 5 ] ) );
		}

		final long[] entryRows = readLongs( n5, data + "/" + ENTRIES, ENTRY_COLUMNS );
		final Map< Integer, Entry > entries = new HashMap<>();
		for ( int row = 0; row < entryRows.length; row += ENTRY_COLUMNS )
		{
			final int setup = (int) entryRows[ row ];
			entries.put( setup, new Entry(
					(int) entryRows[ row + 1 ],
					entryRows[ row + 2 ], (int) entryRows[ row + 3 ],
					entryRows[ row + 4 ], (int) entryRows[ row + 5 ],
					Collections.unmodifiableList( partners.getOrDefault( setup, List.of() ) ) ) );
		}

		return new GroupIndex(
				group, generation, Collections.unmodifiableMap( entries ), data,
				n5.getDatasetAttributes( data + "/" + POINTS ),
				n5.getDatasetAttributes( data + "/" + IDS ),
				n5.getDatasetAttributes( data + "/" + CORR ) );
	}

	static String dataPath( final Group group, final long generation ) { return group.path() + "/g" + generation; }

	// ------------------------------------------------------------------------------------------------
	// staging files: one per Spark task, read by everybody, folded in by the next commit
	// ------------------------------------------------------------------------------------------------

	/** Lists the staging directory and reads the entry tables of files not seen before. */
	private void listStagingFiles()
	{
		if ( stagingListed )
			return;

		final String directory = path( STAGING );
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

		final Map< String, List< StagingEntry > > parsed = new ConcurrentHashMap<>();
		if ( newNames.size() == 1 )
		{
			final List< StagingEntry > entries = readHeader( newNames.get( 0 ) );
			if ( entries != null )
				parsed.put( newNames.get( 0 ), entries );
		}
		else if ( !newNames.isEmpty() )
		{
			parallel( "reading staging file headers", () -> newNames.parallelStream().forEach( name -> {
				final List< StagingEntry > entries = readHeader( name );
				if ( entries != null )
					parsed.put( name, entries );
			} ) );
		}

		synchronized ( lock )
		{
			parsedStagingFiles.putAll( parsed );
			rebuildStagingMaps();
			stagingListed = true;
		}
	}

	/** Rebuilds the key -> payload maps (under lock). Names sort chronologically, so a later file wins. */
	private void rebuildStagingMaps()
	{
		final Map< Key, StagingRef > points = new HashMap<>();
		final Map< Key, StagingRef > corrs = new HashMap<>();
		for ( final List< StagingEntry > entries : new TreeMap<>( parsedStagingFiles ).values() )
			for ( final StagingEntry entry : entries )
			{
				if ( entry.points != null )
					points.put( entry.key, entry.points );
				if ( entry.correspondences != null )
					corrs.put( entry.key, entry.correspondences );
			}
		stagingPoints = points;
		stagingCorrs = corrs;
	}

	/** Lists the staging directory again, so files that other JVMs wrote or deleted since are picked up. */
	private void relistStagingFiles()
	{
		stagingListed = false;
		listStagingFiles();
	}

	/** Milliseconds (zero-padded, so names sort chronologically), nanoTime, and a random number: unique across JVMs. */
	private static String stagingFileName()
	{
		return String.format( "%013d_%016x_%08x%s", System.currentTimeMillis(), System.nanoTime(), new Random().nextInt(), STAGING_EXT );
	}

	/** @return the entry table of a staging file, or null if it cannot be read (for example, still being written) */
	private List< StagingEntry > readHeader( final String name )
	{
		try ( final DataInputStream in = openStagingFile( name ) )
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
			throw new IOException( "unsupported staging file version " + version + " (this reader writes and reads " + STAGING_VERSION + ")" );

		final int entryCount = in.readInt();
		final List< StagingEntry > entries = new ArrayList<>( entryCount );
		for ( int i = 0; i < entryCount; ++i )
		{
			final Key key = new Key( in.readInt(), in.readInt(), in.readUTF() );

			StagingRef points = null;
			if ( in.readBoolean() )
			{
				final long offset = in.readLong();
				final int count = in.readInt();
				final boolean filtered = in.readBoolean();
				points = new StagingRef( name, offset, count, filtered, null );
			}

			StagingRef correspondences = null;
			if ( in.readBoolean() )
			{
				final long offset = in.readLong();
				final int count = in.readInt();
				final int partnerCount = in.readInt();
				final List< PartnerCount > partners = new ArrayList<>( partnerCount );
				for ( int p = 0; p < partnerCount; ++p )
					partners.add( new PartnerCount( new Key( in.readInt(), in.readInt(), in.readUTF() ), in.readInt() ) );
				correspondences = new StagingRef( name, offset, count, false, Collections.unmodifiableList( partners ) );
			}

			entries.add( new StagingEntry( key, points, correspondences ) );
		}
		return entries;
	}

	/**
	 * Writes one staging file with all given entries, without a commit. Other JVMs can read it at once; the next commit
	 * folds it into the arrays. This is how Spark tasks save (one file per task, see {@link InterestPointsZarr#saveStaged}).
	 *
	 * @param points the points per entry (a filtered label's marker is {@link Points#filteredMarker()})
	 * @param correspondences the correspondence list per entry
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
				final List< CorrespondingInterestPoints > entryCorrs = correspondences.get( key );

				int pointsPayload = -1;
				if ( entryPoints != null )
				{
					pointsPayload = payloads.size();
					payloads.add( encodePoints( entryPoints ) );
				}

				int corrPayload = -1;
				List< PartnerCount > partners = null;
				if ( entryCorrs != null )
				{
					final TreeMap< Key, List< CorrespondingInterestPoints > > byPartner = groupByPartner( entryCorrs );
					partners = new ArrayList<>( byPartner.size() );
					for ( final Map.Entry< Key, List< CorrespondingInterestPoints > > partner : byPartner.entrySet() )
						partners.add( new PartnerCount( partner.getKey(), partner.getValue().size() ) );
					corrPayload = payloads.size();
					payloads.add( encodeCorrs( byPartner ) );
				}

				rows.add( new StagingRow( key, pointsPayload, entryPoints, corrPayload, partners, entryCorrs == null ? 0 : entryCorrs.size() ) );
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

			if ( offset > Integer.MAX_VALUE )
				throw new IllegalArgumentException( "a staging file must stay below 2 GB (" + keys.size() + " entries, " + offset + " bytes); save fewer lists per task" );

			final String name = stagingFileName();
			writer(); // creates the container if needed
			kva.createDirectories( path( STAGING ) );
			final ByteArrayOutputStream file = new ByteArrayOutputStream( (int) offset );
			file.write( header );
			for ( final byte[] payload : payloads )
				file.write( payload );
			kva.write( path( STAGING, name ), ReadData.from( file.toByteArray() ) );

			// the file now has these entries: drop older versions staged in memory and add the file to the listing
			final List< StagingEntry > entries = parseHeader( name, new DataInputStream( new ByteArrayInputStream( header ) ) );
			listStagingFiles();
			synchronized ( lock )
			{
				for ( final StagingEntry entry : entries )
				{
					if ( entry.points != null )
						stagedPoints.remove( entry.key );
					if ( entry.correspondences != null )
						stagedCorrs.remove( entry.key );
					removed.remove( entry.key );
				}
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

			out.writeBoolean( row.pointsPayload >= 0 );
			if ( row.pointsPayload >= 0 )
			{
				out.writeLong( offsets[ row.pointsPayload ] );
				out.writeInt( row.points.size() );
				out.writeBoolean( row.points.filtered );
			}

			out.writeBoolean( row.corrPayload >= 0 );
			if ( row.corrPayload >= 0 )
			{
				out.writeLong( offsets[ row.corrPayload ] );
				out.writeInt( row.corrCount );
				out.writeInt( row.partners.size() );
				for ( final PartnerCount partner : row.partners )
				{
					out.writeInt( partner.partner.tp );
					out.writeInt( partner.partner.setup );
					out.writeUTF( partner.partner.label );
					out.writeInt( partner.count );
				}
			}
		}
		out.flush();
		return bytes.toByteArray();
	}

	/** ids (int32), then the {@link #COLUMNS} float32 values per point; big-endian. */
	private static byte[] encodePoints( final Points points )
	{
		final ByteBuffer buffer = ByteBuffer.allocate( points.size() * 4 + points.data.length * 4 );
		buffer.asIntBuffer().put( points.ids );
		buffer.position( points.size() * 4 );
		buffer.asFloatBuffer().put( points.data );
		return buffer.array();
	}

	private static Points decodePoints( final DataInputStream in, final int count, final boolean filtered ) throws IOException
	{
		final int[] ids = new int[ count ];
		ByteBuffer.wrap( readFully( in, count * 4, "ids" ) ).asIntBuffer().get( ids );
		final float[] data = new float[ count * COLUMNS ];
		ByteBuffer.wrap( readFully( in, count * COLUMNS * 4, "points" ) ).asFloatBuffer().get( data );
		return new Points( ids, data, filtered );
	}

	/** Per correspondence, grouped by partner in the header's order: own detection, partner detection, consensus set (int32). */
	private static byte[] encodeCorrs( final TreeMap< Key, List< CorrespondingInterestPoints > > byPartner )
	{
		int count = 0;
		for ( final List< CorrespondingInterestPoints > list : byPartner.values() )
			count += list.size();

		final ByteBuffer buffer = ByteBuffer.allocate( count * CORR_COLUMNS * 4 );
		for ( final List< CorrespondingInterestPoints > list : byPartner.values() )
			for ( final CorrespondingInterestPoints correspondence : list )
			{
				buffer.putInt( correspondence.getDetectionId() );
				buffer.putInt( correspondence.getCorrespondingDetectionId() );
				buffer.putInt( correspondence.getConsensusSetId() );
			}
		return buffer.array();
	}

	/** Decodes the rows of the partners [from, to) of a staged correspondence list; the stream is positioned at the payload. */
	private static List< CorrespondingInterestPoints > decodeCorrs( final DataInputStream in, final StagingRef ref, final int from, final int to ) throws IOException
	{
		long skip = 0;
		for ( int p = 0; p < from; ++p )
			skip += (long) ref.partners.get( p ).count * CORR_COLUMNS * 4;
		in.skipNBytes( skip );

		final ArrayList< CorrespondingInterestPoints > correspondences = new ArrayList<>();
		for ( int p = from; p < to; ++p )
		{
			final PartnerCount partner = ref.partners.get( p );
			final int[] rows = new int[ partner.count * CORR_COLUMNS ];
			ByteBuffer.wrap( readFully( in, rows.length * 4, "correspondences" ) ).asIntBuffer().get( rows );
			addRows( rows, 0, partner.count, partner.partner, correspondences );
		}
		return correspondences;
	}

	/** Appends {@code count} rows (own detection, partner detection, consensus set) starting at row {@code first} of {@code rows} as correspondences to {@code partner}. */
	private static void addRows( final int[] rows, final int first, final int count, final Key partner, final List< CorrespondingInterestPoints > result )
	{
		final ViewId partnerView = partner.viewId();
		for ( int i = 0; i < count; ++i )
		{
			final int r = ( first + i ) * CORR_COLUMNS;
			result.add( new CorrespondingInterestPoints( rows[ r ], partnerView, partner.label, rows[ r + 1 ], rows[ r + 2 ] ) );
		}
	}

	private static byte[] readFully( final DataInputStream in, final int length, final String what ) throws IOException
	{
		final byte[] bytes = in.readNBytes( length );
		if ( bytes.length < length )
			throw new EOFException( "truncated " + what + " payload (" + bytes.length + " of " + length + " bytes)" );

		return bytes;
	}

	/** Opens a staging file for reading; closing the stream also releases the underlying data. */
	private DataInputStream openStagingFile( final String name ) throws IOException
	{
		final VolatileReadData data = kva.createReadData( path( STAGING, name ) );
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
		final DataInputStream in = openStagingFile( ref.file );
		in.skipNBytes( ref.offset );
		return in;
	}

	private static boolean isMissingFile( final Throwable error )
	{
		for ( Throwable cause = error; cause != null; cause = cause.getCause() )
			if ( cause instanceof java.nio.file.NoSuchFileException || cause instanceof N5Exception.N5NoSuchKeyException )
				return true;

		return false;
	}

	/** A staging file is gone: another JVM committed and deleted it. Forget it, so the next lookup goes to the arrays. */
	private void stagingFileGone( final String file )
	{
		synchronized ( lock )
		{
			parsedStagingFiles.remove( file );
			rebuildStagingMaps();
		}
		stagingListed = false;
	}

	private Points readStagedPoints( final StagingRef ref, final Key key )
	{
		try ( final DataInputStream in = openPayload( ref ) )
		{
			return decodePoints( in, ref.count, ref.filtered );
		}
		catch ( final IOException | N5Exception e )
		{
			if ( !isMissingFile( e ) )
				throw new RuntimeException( "could not read staging file " + ref.file + " for " + key, e );

			stagingFileGone( ref.file );
			return points( key );
		}
	}

	private List< CorrespondingInterestPoints > readStagedCorrs( final StagingRef ref, final Key key, final Key partner )
	{
		int from = 0, to = ref.partners.size();
		if ( partner != null )
		{
			from = -1;
			for ( int p = 0; p < ref.partners.size(); ++p )
				if ( ref.partners.get( p ).partner.equals( partner ) )
					from = p;
			if ( from < 0 )
				return new ArrayList<>();
			to = from + 1;
		}

		try ( final DataInputStream in = openPayload( ref ) )
		{
			return decodeCorrs( in, ref, from, to );
		}
		catch ( final IOException | N5Exception e )
		{
			if ( !isMissingFile( e ) )
				throw new RuntimeException( "could not read staging file " + ref.file + " for " + key, e );

			stagingFileGone( ref.file );
			return correspondences( key, partner );
		}
	}

	// ------------------------------------------------------------------------------------------------
	// reading
	// ------------------------------------------------------------------------------------------------

	/** The entry of a key in the arrays, null if the group or the setup does not exist. */
	private Entry entry( final Key key )
	{
		return index( key.group() ).entries.get( key.setup );
	}

	/**
	 * Called when an entry is in none of the known places: another JVM may have written a staging file or committed since.
	 * Lists the staging directory again and reloads the group's index if its generation changed on disk.
	 */
	private void refresh( final Key key )
	{
		relistStagingFiles();

		final GroupIndex cached = indexes.get( key.group() );
		if ( cached == null || readGeneration( key.group() ) != cached.generation )
			reloadIndex( key.group() );
	}

	/** @return whether the store knows the entry (staged, in a staging file, or in the arrays) and it is not removed */
	public boolean hasEntry( final Key key )
	{
		synchronized ( lock )
		{
			if ( removed.contains( key ) )
				return false;
			if ( stagedPoints.containsKey( key ) || stagedCorrs.containsKey( key ) )
				return true;
		}
		listStagingFiles();
		if ( stagingPoints.containsKey( key ) || stagingCorrs.containsKey( key ) || entry( key ) != null )
			return true;

		refresh( key );
		return stagingPoints.containsKey( key ) || stagingCorrs.containsKey( key ) || entry( key ) != null;
	}

	/**
	 * The keys among the given ones the store neither holds (staged, in a staging file, or in the arrays) nor has scheduled
	 * for removal: the entries that are still only in the N5 layout (or nowhere). One listing of the staging directory and
	 * the cached indexes (their generation compared with the disk at most every {@link #generationCheckMillis}), where
	 * {@link #hasEntry} would list the staging directory and read the group's generation again for every key it does not
	 * find. For many keys at once (the explorer's "Resave as ZARR store", the conversion).
	 */
	public Set< Key > missingEntries( final Collection< Key > keys )
	{
		relistStagingFiles();

		final TreeSet< Key > missing = new TreeSet<>();
		synchronized ( lock )
		{
			for ( final Key key : keys )
				if ( !removed.contains( key ) && !stagedPoints.containsKey( key ) && !stagedCorrs.containsKey( key ) && !stagingPoints.containsKey( key ) && !stagingCorrs.containsKey( key ) )
					missing.add( key );
		}

		missing.removeIf( key -> entry( key ) != null );
		return missing;
	}

	/** @return whether the store knows the entry and it is the marker of a filtered label (no points of its own); null if it does not know the entry */
	public Boolean isFiltered( final Key key )
	{
		synchronized ( lock )
		{
			if ( removed.contains( key ) )
				return null;
			final Points staged = stagedPoints.get( key );
			if ( staged != null )
				return staged.filtered;
		}
		listStagingFiles();
		StagingRef ref = stagingPoints.get( key );
		Entry entry = ref == null ? entry( key ) : null;
		if ( ref == null && entry == null )
		{
			refresh( key );
			ref = stagingPoints.get( key );
			entry = ref == null ? entry( key ) : null;
		}
		if ( ref != null )
			return ref.filtered;
		return entry == null ? null : entry.kind == KIND_FILTERED;
	}

	/** @return the points of an entry (for a filtered label the marker, see {@link Points#filtered}), or null if the store does not know it */
	public Points points( final Key key )
	{
		synchronized ( lock )
		{
			if ( removed.contains( key ) )
				return null;
			final Points staged = stagedPoints.get( key );
			if ( staged != null )
				return staged;
		}

		listStagingFiles();
		StagingRef ref = stagingPoints.get( key );
		if ( ref != null )
			return readStagedPoints( ref, key );

		Entry entry = entry( key );
		if ( entry == null )
		{
			refresh( key );
			ref = stagingPoints.get( key );
			if ( ref != null )
				return readStagedPoints( ref, key );
			entry = entry( key );
			if ( entry == null )
				return null;
		}

		return readPointsRetrying( key, entry );
	}

	/** Reads from the arrays; if the group was rewritten by another JVM in the meantime (a missing chunk), once again from the new generation. */
	private Points readPointsRetrying( final Key key, final Entry entry )
	{
		final GroupIndex index = index( key.group() );
		try
		{
			return readPoints( index, entry );
		}
		catch ( final N5Exception | IllegalStateException e )
		{
			final GroupIndex fresh = reloadIndex( key.group() );
			if ( fresh.generation == index.generation )
				throw e;

			final Entry current = fresh.entries.get( key.setup );
			return current == null ? null : readPoints( fresh, current );
		}
	}

	private Points readPoints( final GroupIndex index, final Entry entry )
	{
		final int count = entry.pointCount;
		final int[] ids = new int[ count ];
		final float[] data = new float[ count * COLUMNS ];

		forChunks( index.dataPath + "/" + IDS, index.idAttrs, index.pointRows(), entry.pointsOffset, count, ( final int[] chunk, final long chunkStart, final long from, final long to ) ->
				System.arraycopy( chunk, (int) ( from - chunkStart ), ids, (int) ( from - entry.pointsOffset ), (int) ( to - from ) ) );

		forChunks( index.dataPath + "/" + POINTS, index.pointsAttrs, index.pointRows(), entry.pointsOffset, count, ( final float[] chunk, final long chunkStart, final long from, final long to ) ->
				System.arraycopy( chunk, (int) ( from - chunkStart ) * COLUMNS, data, (int) ( from - entry.pointsOffset ) * COLUMNS, (int) ( to - from ) * COLUMNS ) );

		return new Points( ids, data, entry.kind == KIND_FILTERED );
	}

	/** @return all correspondences of an entry, to all partners; null if the store does not know the entry */
	public List< CorrespondingInterestPoints > correspondences( final Key key ) { return correspondences( key, (Key) null ); }

	/** @return the correspondences of an entry to one partner (view, label); null if the store does not know the entry */
	public List< CorrespondingInterestPoints > correspondences( final Key key, final ViewId partnerView, final String partnerLabel )
	{
		return correspondences( key, Key.of( partnerView, partnerLabel ) );
	}

	/** @param partner only correspondences to this partner, or null for all */
	private List< CorrespondingInterestPoints > correspondences( final Key key, final Key partner )
	{
		synchronized ( lock )
		{
			if ( removed.contains( key ) )
				return null;
			final List< CorrespondingInterestPoints > staged = stagedCorrs.get( key );
			if ( staged != null )
				return copy( staged, partner );
		}

		listStagingFiles();
		StagingRef ref = stagingCorrs.get( key );
		if ( ref != null )
			return readStagedCorrs( ref, key, partner );

		Entry entry = entry( key );
		if ( entry == null )
		{
			refresh( key );
			ref = stagingCorrs.get( key );
			if ( ref != null )
				return readStagedCorrs( ref, key, partner );
			entry = entry( key );
			if ( entry == null )
				return null;
		}

		final GroupIndex index = index( key.group() );
		try
		{
			return readCorrs( index, entry, partner );
		}
		catch ( final N5Exception | IllegalStateException e )
		{
			final GroupIndex fresh = reloadIndex( key.group() );
			if ( fresh.generation == index.generation )
				throw e;

			final Entry current = fresh.entries.get( key.setup );
			return current == null ? null : readCorrs( fresh, current, partner );
		}
	}

	private static List< CorrespondingInterestPoints > copy( final List< CorrespondingInterestPoints > list, final Key partner )
	{
		final ArrayList< CorrespondingInterestPoints > copy = new ArrayList<>( partner == null ? list.size() : 16 );
		for ( final CorrespondingInterestPoints correspondence : list )
			if ( partner == null || partnerOf( correspondence ).equals( partner ) )
				copy.add( new CorrespondingInterestPoints( correspondence ) );
		return copy;
	}

	private List< CorrespondingInterestPoints > readCorrs( final GroupIndex index, final Entry entry, final Key partner )
	{
		final ArrayList< CorrespondingInterestPoints > result = new ArrayList<>();
		for ( final PartnerRow row : entry.partners )
		{
			if ( partner != null && !row.partner.equals( partner ) )
				continue;

			forChunks( index.dataPath + "/" + CORR, index.corrAttrs, index.corrRows(), row.offset, row.count, ( final int[] chunk, final long chunkStart, final long from, final long to ) ->
					addRows( chunk, (int) ( from - chunkStart ), (int) ( to - from ), row.partner, result ) );
		}
		return result;
	}

	static Key partnerOf( final CorrespondingInterestPoints correspondence )
	{
		return Key.of( correspondence.getCorrespondingViewId(), correspondence.getCorrespodingLabel() );
	}

	/** @return the (view, label)s an entry has correspondences with; null if the store does not know the entry */
	public Set< Pair< ViewId, String > > correspondingViews( final Key key )
	{
		synchronized ( lock )
		{
			if ( removed.contains( key ) )
				return null;
			final List< CorrespondingInterestPoints > staged = stagedCorrs.get( key );
			if ( staged != null )
			{
				final Set< Pair< ViewId, String > > partners = new HashSet<>();
				for ( final CorrespondingInterestPoints correspondence : staged )
					partners.add( partnerOf( correspondence ).toPair() );
				return partners;
			}
		}

		listStagingFiles();
		StagingRef ref = stagingCorrs.get( key );
		Entry entry = ref == null ? entry( key ) : null;
		if ( ref == null && entry == null )
		{
			refresh( key );
			ref = stagingCorrs.get( key );
			entry = ref == null ? entry( key ) : null;
		}

		final Set< Pair< ViewId, String > > partners = new HashSet<>();
		if ( ref != null )
			for ( final PartnerCount partner : ref.partners )
				partners.add( partner.partner.toPair() );
		else if ( entry != null )
			for ( final PartnerRow row : entry.partners )
				partners.add( row.partner.toPair() );
		else
			return null;

		return partners;
	}

	/** Receives one chunk of an array and the rows [from, to) of it that are wanted. */
	private interface ChunkVisitor< C > { void visit( C chunk, long chunkStart, long from, long to ); }

	/** Calls the visitor for every chunk that holds rows of [offset, offset + count); a range beyond the array is an error. */
	private < C > void forChunks( final String dataset, final DatasetAttributes attributes, final long rows, final long offset, final int count,
			final ChunkVisitor< C > visitor )
	{
		final long end = offset + count;
		if ( end > rows )
			throw new IllegalStateException( "the index of '" + dataset + "' refers to rows [" + offset + ", " + end + ") but the array has " + rows + " rows, stopping." );

		final int chunkSize = attributes.getChunkSize()[ 1 ];
		for ( long chunkIndex = offset / chunkSize; chunkIndex * chunkSize < end; ++chunkIndex )
		{
			final long chunkStart = chunkIndex * chunkSize;
			final long from = Math.max( chunkStart, offset );
			final long to = Math.min( chunkStart + chunkSize, end );
			visitor.visit( readChunk( dataset, attributes, chunkIndex ), chunkStart, from, to );
		}
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

		final DataBlock< ? > block = reader().readChunk( dataset, attributes, 0, chunkIndex );
		if ( block == null )
			throw new IllegalStateException( "chunk " + chunkIndex + " of '" + dataset + "' is missing, stopping." );

		final Object data = block.getData();
		cache( cacheKey, data );
		return (C) data;
	}

	private void cache( final String key, final Object data )
	{
		final long bytes = bytesOf( data );
		if ( bytes > chunkCacheBytes )
			return;

		synchronized ( chunkCache )
		{
			final Object previous = chunkCache.put( key, data );
			cachedBytes += bytes - ( previous == null ? 0 : bytesOf( previous ) );
			final java.util.Iterator< Map.Entry< String, Object > > eldest = chunkCache.entrySet().iterator();
			while ( cachedBytes > chunkCacheBytes && eldest.hasNext() )
			{
				cachedBytes -= bytesOf( eldest.next().getValue() );
				eldest.remove();
			}
		}
	}

	private static long bytesOf( final Object array )
	{
		if ( array instanceof float[] )
			return 4L * ( (float[]) array ).length;
		if ( array instanceof int[] )
			return 4L * ( (int[]) array ).length;
		if ( array instanceof long[] )
			return 8L * ( (long[]) array ).length;
		if ( array instanceof double[] )
			return 8L * ( (double[]) array ).length;
		return 0;
	}

	/** Forgets the cached chunks of a group (its generation changed). */
	private void dropChunks( final Group group )
	{
		final String prefix = group.path() + "/g";
		synchronized ( chunkCache )
		{
			final java.util.Iterator< Map.Entry< String, Object > > it = chunkCache.entrySet().iterator();
			while ( it.hasNext() )
			{
				final Map.Entry< String, Object > entry = it.next();
				if ( entry.getKey().startsWith( prefix ) )
				{
					cachedBytes -= bytesOf( entry.getValue() );
					it.remove();
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------------
	// saving
	// ------------------------------------------------------------------------------------------------

	/** Saves the points of an entry: in memory during a batch ({@link #beginBatch()}), otherwise in a staging file. */
	public void savePoints( final Key key, final Points value )
	{
		if ( batchOpen )
			stage( key, value, null );
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
			stage( key, null, copy );
		else
			writeStagingFile( Map.of(), Map.of( key, copy ) );
	}

	private void stage( final Key key, final Points points, final List< CorrespondingInterestPoints > correspondences )
	{
		synchronized ( lock )
		{
			if ( points != null )
				stagedPoints.put( key, points );
			if ( correspondences != null )
				stagedCorrs.put( key, correspondences );
			removed.remove( key );
		}
	}

	/** Removes the points and correspondences of an entry at the next commit. */
	public void remove( final Key key )
	{
		synchronized ( lock )
		{
			stagedPoints.remove( key );
			stagedCorrs.remove( key );
			removed.add( key );
		}
	}

	/**
	 * Starts a batch that this JVM commits right away: saves stay in memory until {@link #commit()}. Outside a batch (for
	 * example on Spark executors, which never commit) every save writes a staging file, so nothing is lost.
	 */
	public void beginBatch() { batchOpen = true; }

	/** Ends a batch without a commit: what is staged stays staged (the next commit writes it), later saves write staging files again. */
	public void endBatch() { batchOpen = false; }

	public boolean isBatchOpen() { return batchOpen; }

	// ------------------------------------------------------------------------------------------------
	// commit
	// ------------------------------------------------------------------------------------------------

	/** What a commit takes from the staged data and the staging files: a consistent snapshot. */
	private record Snapshot(
			Map< Key, Points > points,
			Map< Key, List< CorrespondingInterestPoints > > correspondences,
			Set< Key > removed,
			Map< Key, StagingRef > filePoints,
			Map< Key, StagingRef > fileCorrs,
			Set< String > files ) {}

	/**
	 * Makes all staged changes and staging files durable, group by group: writes the next generation of every group that
	 * has changes, flips it, deletes the old generation and the staging files. Ends the batch, also when it fails.
	 *
	 * @throws IllegalStateException if another process committed one of the groups in the meantime (nothing of that
	 *         group is overwritten; the changes stay staged, save again)
	 */
	public void commit()
	{
		try
		{
			commitAll();
		}
		finally
		{
			batchOpen = false;
		}
	}

	private void commitAll()
	{
		final long startTime = System.currentTimeMillis();
		relistStagingFiles();

		final Snapshot snapshot;
		synchronized ( lock )
		{
			snapshot = new Snapshot(
					new HashMap<>( stagedPoints ), new HashMap<>( stagedCorrs ), new HashSet<>( removed ),
					stagingPoints, stagingCorrs, new HashSet<>( parsedStagingFiles.keySet() ) );
		}

		final TreeSet< Group > groups = new TreeSet<>();
		for ( final Key key : snapshot.points.keySet() )
			groups.add( key.group() );
		for ( final Key key : snapshot.correspondences.keySet() )
			groups.add( key.group() );
		for ( final Key key : snapshot.removed )
			groups.add( key.group() );
		for ( final Key key : snapshot.filePoints.keySet() )
			groups.add( key.group() );
		for ( final Key key : snapshot.fileCorrs.keySet() )
			groups.add( key.group() );

		if ( groups.isEmpty() )
			return;

		int entries = 0;
		for ( final Group group : groups )
			entries += commitGroup( group, snapshot );

		// what this commit wrote is no longer staged; a value staged again in the meantime (another object) stays
		synchronized ( lock )
		{
			for ( final Map.Entry< Key, Points > entry : snapshot.points.entrySet() )
				if ( stagedPoints.get( entry.getKey() ) == entry.getValue() )
					stagedPoints.remove( entry.getKey() );
			for ( final Map.Entry< Key, List< CorrespondingInterestPoints > > entry : snapshot.correspondences.entrySet() )
				if ( stagedCorrs.get( entry.getKey() ) == entry.getValue() )
					stagedCorrs.remove( entry.getKey() );
			removed.removeAll( snapshot.removed );
			parsedStagingFiles.keySet().removeAll( snapshot.files );
			rebuildStagingMaps();
		}

		deleteStagingFiles( snapshot.files );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): InterestPointsZarrStore: committed " + groups.size() + " group(s), " + entries + " entries, folded " + snapshot.files.size() + " staging file(s) in " + ( System.currentTimeMillis() - startTime ) + " ms" );
	}

	/** Where the points or the correspondences of one setup come from in a commit. */
	private record Source( Key key, Points points, StagingRef filePoints, List< CorrespondingInterestPoints > correspondences, StagingRef fileCorrs, Entry old ) {}

	/**
	 * Writes the next generation of a group from the kept entries of the old one and the staged ones, flips the group to it
	 * under its lock file, and deletes the old generation. Removes the group if no entry is left.
	 *
	 * @return the number of entries of the new generation
	 */
	private int commitGroup( final Group group, final Snapshot snapshot )
	{
		final long startTime = System.currentTimeMillis();
		final GroupIndex old = reloadIndex( group );

		// the setups of the new generation and where their data comes from
		final TreeMap< Integer, Source > sources = new TreeMap<>();
		for ( final Map.Entry< Integer, Entry > entry : old.entries.entrySet() )
		{
			final Key key = new Key( group.tp, entry.getKey(), group.label );
			if ( !snapshot.removed.contains( key ) )
				sources.put( entry.getKey(), new Source( key, null, null, null, null, entry.getValue() ) );
		}
		final Set< Key > keys = new HashSet<>();
		for ( final Key key : snapshot.points.keySet() )
			if ( key.group().equals( group ) )
				keys.add( key );
		for ( final Key key : snapshot.correspondences.keySet() )
			if ( key.group().equals( group ) )
				keys.add( key );
		for ( final Key key : snapshot.filePoints.keySet() )
			if ( key.group().equals( group ) && !snapshot.removed.contains( key ) )
				keys.add( key );
		for ( final Key key : snapshot.fileCorrs.keySet() )
			if ( key.group().equals( group ) && !snapshot.removed.contains( key ) )
				keys.add( key );
		for ( final Key key : keys )
		{
			final Source kept = sources.get( key.setup );
			final Points points = snapshot.points.get( key );
			final List< CorrespondingInterestPoints > correspondences = snapshot.correspondences.get( key );
			sources.put( key.setup, new Source(
					key,
					points,
					points == null ? snapshot.filePoints.get( key ) : null,
					correspondences,
					correspondences == null ? snapshot.fileCorrs.get( key ) : null,
					kept == null ? null : kept.old ) );
		}

		if ( sources.isEmpty() )
		{
			if ( old.exists() )
				flip( group, old, -1, null );
			return 0;
		}

		// sizes and offsets of the new generation
		final List< Integer > setups = new ArrayList<>( sources.keySet() );
		final int[] kinds = new int[ setups.size() ];
		final long[] pointOffsets = new long[ setups.size() ];
		final int[] pointCounts = new int[ setups.size() ];
		final long[] corrOffsets = new long[ setups.size() ];
		final List< List< PartnerCount > > partnerCounts = new ArrayList<>( setups.size() );
		long pointRows = 0, corrRows = 0;
		for ( int i = 0; i < setups.size(); ++i )
		{
			final Source source = sources.get( setups.get( i ) );
			if ( source.points != null )
			{
				kinds[ i ] = source.points.kind();
				pointCounts[ i ] = source.points.size();
			}
			else if ( source.filePoints != null )
			{
				kinds[ i ] = source.filePoints.filtered ? KIND_FILTERED : KIND_PLAIN;
				pointCounts[ i ] = source.filePoints.count;
			}
			else if ( source.old != null )
			{
				kinds[ i ] = source.old.kind;
				pointCounts[ i ] = source.old.pointCount;
			}
			else
			{
				// correspondences of a label whose points are still in the N5 (a dataset not converted yet): its points move into the store with them
				final Points legacy = InterestPointsN5ToZarr.get( baseDir ).points( source.key.path() );

				if ( legacy == null )
					throw new IllegalStateException( "correspondences are saved for '" + source.key.path() + "' but the label has no points there (not detected, not a filtered label), stopping." );

				IOFunctions.println( "InterestPointsZarrStore: the points of '" + source.key.path() + "' move from " + InterestPointsN5.baseN5 + " into the store with their correspondences" );
				sources.put( setups.get( i ), new Source( source.key, legacy, null, source.correspondences, source.fileCorrs, null ) );
				kinds[ i ] = legacy.kind();
				pointCounts[ i ] = legacy.size();
			}

			pointOffsets[ i ] = pointRows;
			pointRows += pointCounts[ i ];

			final List< PartnerCount > partners;
			if ( source.correspondences != null )
			{
				partners = new ArrayList<>();
				for ( final Map.Entry< Key, List< CorrespondingInterestPoints > > partner : groupByPartner( source.correspondences ).entrySet() )
					partners.add( new PartnerCount( partner.getKey(), partner.getValue().size() ) );
			}
			else if ( source.fileCorrs != null )
			{
				partners = source.fileCorrs.partners;
			}
			else if ( source.old != null )
			{
				partners = new ArrayList<>();
				for ( final PartnerRow row : source.old.partners )
					partners.add( new PartnerCount( row.partner, row.count ) );
			}
			else
				partners = List.of();

			partnerCounts.add( partners );
			corrOffsets[ i ] = corrRows;
			for ( final PartnerCount partner : partners )
				corrRows += partner.count;
		}

		final long generation = Math.max( old.generation + 1, System.currentTimeMillis() );
		final String data = dataPath( group, generation );
		final N5Writer zarr = writer();
		final int chunkRows = old.exists() ? old.pointsAttrs.getChunkSize()[ 1 ] : Math.max( 1, defaultChunkRows );
		final int shardRows = old.exists() ? old.pointsAttrs.getBlockSize()[ 1 ] : roundUp( Math.max( chunkRows, defaultShardRows ), chunkRows );

		zarr.createGroup( group.path() );
		zarr.createGroup( data );

		try
		{
			// points and ids, entry by entry in setup order, streamed into shards
			final DatasetAttributes pointsAttrs = arrayAttrs( COLUMNS, pointRows, DataType.FLOAT32, shardRows, chunkRows );
			final DatasetAttributes idAttrs = arrayAttrs( 1, pointRows, DataType.INT32, shardRows, chunkRows );
			zarr.createDataset( data + "/" + POINTS, pointsAttrs );
			zarr.createDataset( data + "/" + IDS, idAttrs );
			final ArrayWriter pointsWriter = new ArrayWriter( zarr, data + "/" + POINTS, pointsAttrs, float[].class );
			final ArrayWriter idWriter = new ArrayWriter( zarr, data + "/" + IDS, idAttrs, int[].class );
			for ( int i = 0; i < setups.size(); ++i )
			{
				if ( pointCounts[ i ] == 0 )
					continue;

				final Source source = sources.get( setups.get( i ) );
				final Points points;
				if ( source.points != null )
					points = source.points;
				else if ( source.filePoints != null )
					points = readStagedPoints( source.filePoints, source.key );
				else
					points = readPoints( old, source.old );

				if ( points.size() != pointCounts[ i ] )
					throw new IllegalStateException( "'" + source.key.path() + "' has " + points.size() + " points, " + pointCounts[ i ] + " were announced, stopping." );

				pointsWriter.append( points.data, points.size() );
				idWriter.append( points.ids, points.size() );
			}
			pointsWriter.finish();
			idWriter.finish();

			// correspondences, per entry grouped by partner
			final DatasetAttributes corrAttrs = arrayAttrs( CORR_COLUMNS, corrRows, DataType.INT32, shardRows, chunkRows );
			zarr.createDataset( data + "/" + CORR, corrAttrs );
			final ArrayWriter corrWriter = new ArrayWriter( zarr, data + "/" + CORR, corrAttrs, int[].class );
			for ( int i = 0; i < setups.size(); ++i )
			{
				final List< PartnerCount > partners = partnerCounts.get( i );
				if ( partners.isEmpty() )
					continue;

				final Source source = sources.get( setups.get( i ) );
				final List< CorrespondingInterestPoints > correspondences;
				if ( source.correspondences != null )
					correspondences = source.correspondences;
				else if ( source.fileCorrs != null )
					correspondences = readStagedCorrs( source.fileCorrs, source.key, null );
				else
					correspondences = readCorrs( old, source.old, null );

				int count = 0;
				for ( final PartnerCount partner : partners )
					count += partner.count;
				if ( correspondences.size() != count )
					throw new IllegalStateException( "'" + source.key.path() + "' has " + correspondences.size() + " correspondences, " + count + " were announced, stopping." );

				corrWriter.append( toRows( correspondences, partners ), correspondences.size() );
			}
			corrWriter.finish();

			// the index of the generation
			final List< String > partnerLabels = new ArrayList<>();
			final Map< String, Integer > partnerLabelIds = new HashMap<>();
			final long[] entryRows = new long[ ENTRY_COLUMNS * setups.size() ];
			final List< Long > partnerRows = new ArrayList<>();
			for ( int i = 0; i < setups.size(); ++i )
			{
				final List< PartnerCount > partners = partnerCounts.get( i );
				int corrCount = 0;
				long offset = corrOffsets[ i ];
				for ( final PartnerCount partner : partners )
				{
					Integer labelId = partnerLabelIds.get( partner.partner.label );
					if ( labelId == null )
					{
						labelId = partnerLabels.size();
						partnerLabels.add( partner.partner.label );
						partnerLabelIds.put( partner.partner.label, labelId );
					}
					partnerRows.add( (long) setups.get( i ) );
					partnerRows.add( (long) partner.partner.tp );
					partnerRows.add( (long) partner.partner.setup );
					partnerRows.add( (long) labelId );
					partnerRows.add( offset );
					partnerRows.add( (long) partner.count );
					offset += partner.count;
					corrCount += partner.count;
				}

				final int e = ENTRY_COLUMNS * i;
				entryRows[ e ] = setups.get( i );
				entryRows[ e + 1 ] = kinds[ i ];
				entryRows[ e + 2 ] = pointOffsets[ i ];
				entryRows[ e + 3 ] = pointCounts[ i ];
				entryRows[ e + 4 ] = corrOffsets[ i ];
				entryRows[ e + 5 ] = corrCount;
			}
			final long[] partnerArray = new long[ partnerRows.size() ];
			for ( int i = 0; i < partnerArray.length; ++i )
				partnerArray[ i ] = partnerRows.get( i );
			writeLongs( zarr, data + "/" + ENTRIES, ENTRY_COLUMNS, entryRows );
			writeLongs( zarr, data + "/" + PARTNERS, PARTNER_COLUMNS, partnerArray );
			zarr.setAttribute( data, ATTR_PARTNER_LABELS, partnerLabels );
		}
		catch ( final RuntimeException e )
		{
			removeIfExists( zarr, data ); // nothing points at it yet
			throw e;
		}

		flip( group, old, generation, data );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): InterestPointsZarrStore: '" + group.label + "' tp " + group.tp + ": generation " + generation + " (" + setups.size() + " views, " + pointRows + " points, " + corrRows + " correspondences, chunk " + chunkRows + ", shard " + shardRows + ") in " + ( System.currentTimeMillis() - startTime ) + " ms" );

		return setups.size();
	}

	/**
	 * The commit point of a group: under its lock file, checks that the generation on disk is still the one this commit
	 * started from, then writes the group attributes pointing at the new generation (or removes the group if
	 * {@code generation} is -1), and deletes the old generation afterwards.
	 */
	private void flip( final Group group, final GroupIndex old, final long generation, final String data )
	{
		final N5Writer zarr = writer();
		final String lockPath = path( group.path(), LOCK );

		try ( final LockedChannel lockFile = kva.lockForWriting( lockPath ) )
		{
			final long current = readGeneration( group );
			if ( current != old.generation )
			{
				if ( data != null )
					removeIfExists( zarr, data );
				throw new IllegalStateException( "another process committed '" + group.label + "' at timepoint " + group.tp + " (generation " + current + ") while this one was writing on top of generation " + old.generation + "; nothing was overwritten, the changes are still staged: save again." );
			}

			if ( generation < 0 )
			{
				zarr.remove( group.path() );
			}
			else
			{
				final Map< String, Object > attributes = new HashMap<>();
				attributes.put( ATTR_VERSION, VERSION );
				attributes.put( ATTR_LABEL, group.label );
				attributes.put( ATTR_TIMEPOINT, group.tp );
				attributes.put( ATTR_GENERATION, generation );
				zarr.setAttributes( group.path(), attributes ); // the commit point
			}
		}
		catch ( final IOException e )
		{
			throw new RuntimeException( "could not lock '" + lockPath + "'", e );
		}

		if ( old.exists() && generation >= 0 )
			removeIfExists( zarr, old.dataPath );

		indexes.remove( group );
		dropChunks( group );
	}

	/** Correspondences as rows (own detection, partner detection, consensus set), grouped by partner in the given order. */
	private static int[] toRows( final List< CorrespondingInterestPoints > correspondences, final List< PartnerCount > partners )
	{
		final TreeMap< Key, List< CorrespondingInterestPoints > > byPartner = groupByPartner( correspondences );
		final int[] rows = new int[ correspondences.size() * CORR_COLUMNS ];
		int r = 0;
		for ( final PartnerCount partner : partners )
		{
			final List< CorrespondingInterestPoints > list = byPartner.get( partner.partner );
			if ( list == null || list.size() != partner.count )
				throw new IllegalStateException( "the correspondences to " + partner.partner.path() + " do not match their announced count " + partner.count + ", stopping." );

			for ( final CorrespondingInterestPoints correspondence : list )
			{
				rows[ r++ ] = correspondence.getDetectionId();
				rows[ r++ ] = correspondence.getCorrespondingDetectionId();
				rows[ r++ ] = correspondence.getConsensusSetId();
			}
		}
		return rows;
	}

	static TreeMap< Key, List< CorrespondingInterestPoints > > groupByPartner( final Collection< CorrespondingInterestPoints > correspondences )
	{
		final TreeMap< Key, List< CorrespondingInterestPoints > > byPartner = new TreeMap<>();
		for ( final CorrespondingInterestPoints correspondence : correspondences )
			byPartner.computeIfAbsent( partnerOf( correspondence ), partner -> new ArrayList<>() ).add( correspondence );
		return byPartner;
	}

	/** Deletes staging files after a commit folded them in. */
	private void deleteStagingFiles( final Set< String > files )
	{
		if ( files.isEmpty() )
			return;

		final Runnable body = () -> files.parallelStream().forEach( file -> {
			final String p = path( STAGING, file );
			if ( kva.exists( p ) )
				kva.delete( p );
		} );

		if ( files.size() == 1 )
			body.run();
		else
			parallel( "deleting staging files", body );
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

	private static int roundUp( final int value, final int multiple ) { return ( ( value + multiple - 1 ) / multiple ) * multiple; }

	// ------------------------------------------------------------------------------------------------
	// Zarr v3 arrays
	// ------------------------------------------------------------------------------------------------

	/** A sharded array [columns, rows]: raw bytes, with a crc32c on every chunk and on the shard index. */
	static DatasetAttributes arrayAttrs( final int columns, final long rows, final DataType type, final int shardRows, final int chunkRows )
	{
		return rawChecksummed( columns, rows, type, shardRows )
				.chunkSize( new int[] { columns, chunkRows } )
				.shardIndexDataCodecInfos( new Crc32cChecksumCodec() )
				.build();
	}

	/** A [columns, rows] array of raw bytes with a crc32c on every chunk: damaged chunks fail instead of decoding to garbage. */
	private static ZarrV3DatasetAttributes.Builder rawChecksummed( final int columns, final long rows, final DataType type, final int blockRows )
	{
		return ZarrV3DatasetAttributes.builder( new long[] { columns, rows }, type )
				.blockSize( new int[] { columns, blockRows } )
				.compression( new RawCompression() )
				.dataCodecInfos( new Crc32cChecksumCodec() );
	}

	/**
	 * Writes the rows of a sharded [columns, rows] array one after another: rows are appended, complete chunks are
	 * collected per shard and written when the shard is complete (or at {@link #finish()}), so that at most one shard
	 * of chunk buffers is in memory.
	 */
	private static final class ArrayWriter
	{
		private final N5Writer zarr;
		private final String dataset;
		private final DatasetAttributes attributes;
		private final Class< ? > arrayType;
		private final int columns, chunkRows, chunksPerShard;
		private final long rows;

		private Object chunk = null; // the chunk being filled
		private int chunkFill = 0; // rows in it
		private long nextRow = 0; // the next row of the array to be written
		private final List< DataBlock< ? > > shardChunks = new ArrayList<>();

		ArrayWriter( final N5Writer zarr, final String dataset, final DatasetAttributes attributes, final Class< ? > arrayType )
		{
			this.zarr = zarr;
			this.dataset = dataset;
			this.attributes = attributes;
			this.arrayType = arrayType;
			this.columns = attributes.getBlockSize()[ 0 ];
			this.chunkRows = attributes.getChunkSize()[ 1 ];
			this.chunksPerShard = attributes.getBlockSize()[ 1 ] / chunkRows;
			this.rows = attributes.getDimensions()[ 1 ];
		}

		/** Appends {@code count} rows from {@code values} (columns fastest). */
		void append( final Object values, final int count )
		{
			int done = 0;
			while ( done < count )
			{
				if ( chunk == null )
				{
					final int size = (int) Math.min( chunkRows, rows - nextRow );
					chunk = java.lang.reflect.Array.newInstance( arrayType.getComponentType(), size * columns );
					chunkFill = 0;
				}

				final int space = (int) Math.min( chunkRows, rows - ( nextRow - chunkFill ) ) - chunkFill;
				final int n = Math.min( space, count - done );
				System.arraycopy( values, done * columns, chunk, chunkFill * columns, n * columns );
				chunkFill += n;
				done += n;
				nextRow += n;

				if ( chunkFill == (int) Math.min( chunkRows, rows - ( nextRow - chunkFill ) ) )
					closeChunk();
			}
		}

		private void closeChunk()
		{
			final long chunkStart = nextRow - chunkFill;
			final long chunkIndex = chunkStart / chunkRows;
			final int[] size = new int[] { columns, chunkFill };
			final long[] position = new long[] { 0, chunkIndex };
			if ( chunk instanceof float[] )
				shardChunks.add( new FloatArrayDataBlock( size, position, (float[]) chunk ) );
			else
				shardChunks.add( new IntArrayDataBlock( size, position, (int[]) chunk ) );
			chunk = null;
			chunkFill = 0;

			if ( ( chunkIndex + 1 ) % chunksPerShard == 0 )
				writeShard();
		}

		@SuppressWarnings( { "unchecked", "rawtypes" } )
		private void writeShard()
		{
			if ( shardChunks.isEmpty() )
				return;

			zarr.writeChunks( dataset, attributes, shardChunks.toArray( new DataBlock[ 0 ] ) );
			shardChunks.clear();
		}

		void finish()
		{
			if ( nextRow != rows )
				throw new IllegalStateException( "'" + dataset + "' got " + nextRow + " rows, " + rows + " were announced, stopping." );
			if ( chunk != null )
				closeChunk();
			writeShard();
		}
	}

	/** Writes a small unsharded index table [columns, rows] as one chunk. */
	private static void writeLongs( final N5Writer zarr, final String dataset, final int columns, final long[] values )
	{
		final int rows = values.length / columns;
		final DatasetAttributes attributes = rawChecksummed( columns, rows, DataType.INT64, Math.max( 1, rows ) ).build();

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

		final DataBlock< ? > block = zarr.readBlock( dataset, attributes, 0, 0 );
		if ( block == null )
			throw new IllegalStateException( "the index table '" + dataset + "' is missing, stopping." );

		final long[] values = new long[ (int) ( columns * rows ) ];
		System.arraycopy( block.getData(), 0, values, 0, values.length );
		return values;
	}
}
