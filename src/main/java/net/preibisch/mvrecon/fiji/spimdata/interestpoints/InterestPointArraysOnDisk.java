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

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.readdata.ReadData;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Attributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Rows;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.ArrayWriter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Group;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.StagedInterestPointArrays.ArrayKey;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.StagedInterestPointArrays.StagedArray;
import util.URITools;

/**
 * The per-point arrays (embeddings) as the container of a dataset holds them, one instance per
 * {@link InterestPointsZarrStore}. Since 2026-11-14 (Stephan: "save embeddings the same way we save interest points,
 * sharding across views") an array is one table for all views, written like the points of a (label, timepoint):
 *
 * <pre>
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/zarr.json         columns D, dtype "float32", meta, layout, generation K
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/gK/values  FLOAT32 [D, N]  the rows of all setups, in setup order
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/gK/id      INT32   [1, N]  the ids of the points the rows belong to
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/gK/entries INT64   [3, E]  setup, offset, count
 * staging/&lt;millis&gt;_&lt;nanos&gt;_&lt;rnd&gt;.rows                 the rows of one setup, written directly (a Spark task), folded in by the next commit
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/setup&lt;S&gt;/{id, values}  the layout until 2026-11-14 and pcmatch's: one pair of tables per setup, read only
 * </pre>
 *
 * {@code values} and {@code id} are sharded Zarr v3 arrays, raw + crc32c, {@link InterestPointArrays#defaultChunkRows}
 * rows per inner chunk and {@link InterestPointArrays#defaultShardRows} per shard (an array keeps its grid). The tables per
 * setup stored every view as an array of its own, each at least one inner chunk of 65,536 rows on disk (16.7 MB for 64
 * columns, whatever the number of rows), which is what the time of a save and the size of the store went into.
 *
 * <b>Writing</b> is the commit of the points, per array ({@link #commit}): the next generation {@code gK+1} is written
 * from the kept entries of {@code gK} (copied range by range), the rows held in memory
 * ({@link StagedInterestPointArrays}) and the rows of the staging files; the array's {@code generation} is flipped
 * under its lock file after re-reading it (a commit that another process overtook is refused, nothing is overwritten,
 * what is held stays held), then {@code gK} and the staging files are deleted. Rows of views whose points were replaced
 * or removed, and rows that were removed from the array for some views, are not copied, and an array left without rows
 * is removed.
 *
 * <b>Reading</b> a setup: its latest staging file, else its tables {@code setup<S>} (another writer's rows, or rows from
 * before this layout), else its entry of the generation. Tables per setup are never written here and are only deleted
 * when their rows are replaced or stale: a save does not move them into the generation by itself (Stephan, 2026-10-26:
 * a migration is an explicit action, not a side effect of a save), so an array may hold both.
 *
 * Every read goes to the files through a fresh reader (as before) and reads the array's generation from its
 * {@code zarr.json}; only the index of a generation, which never changes, is kept per JVM. No chunk is cached. A read
 * that a commit overtakes (the generation it was reading is gone) is repeated once.
 *
 * @author Stephan Preibisch
 */
final class InterestPointArraysOnDisk
{
	static final String ROWS_EXT = ".rows";
	static final int ROWS_MAGIC = 0x49504152; // "IPAR"
	static final int ROWS_VERSION = 1;

	static final String ATTR_GENERATION = "generation";
	static final String ENTRIES = "entries";
	static final int ENTRY_COLUMNS = 3;

	/** the header of a staging file: the rows of one setup of one array, written directly */
	record StagedRows( String file, String label, int timepoint, String name, int setup, int columns, int rows, int payload )
	{
		ArrayKey key() { return new ArrayKey( label, timepoint, name ); }
	}

	/** where the rows of a setup are in the tables of a generation */
	record Entry( long offset, int count ) {}

	/** the generation of an array: -1 = it has none (no rows committed in this layout) */
	record Index( long generation, String dataPath, Map< Integer, Entry > entries, DatasetAttributes valuesAttrs, DatasetAttributes idAttrs )
	{
		static final Index NONE = new Index( -1, null, Map.of(), null, null );

		boolean exists() { return generation >= 0; }

		/** @return the rows per inner chunk of the generation's tables, 1 if there is none */
		int chunkRows() { return exists() ? valuesAttrs.getChunkSize()[ 1 ] : 1; }
	}

	/** the rows a commit keeps are copied in pieces of this many inner chunks (8 MB for 64 columns at 4,096 rows per chunk) */
	static final int COPY_CHUNKS = 8;

	/** what a commit did, for the log */
	private static final class Summary
	{
		int arraysWritten = 0, setupsWritten = 0, setupsDropped = 0, arraysRemoved = 0;
		long rowsWritten = 0;
	}

	private final InterestPointsZarrStore store;

	private final ConcurrentHashMap< String, Index > indexes = new ConcurrentHashMap<>(); // array path -> the index of the generation it pointed at last
	private final TreeMap< String, StagedRows > stagingFiles = new TreeMap<>(); // by name = chronological, guarded by this
	private long lastListed = 0; // guarded by this
	private boolean listed = false; // guarded by this

	InterestPointArraysOnDisk( final InterestPointsZarrStore store )
	{
		this.store = store;
	}

	/** @return a fresh reader of the container (the caller closes it), null if the dataset has no container */
	N5Reader reader()
	{
		return store.exists() ? URITools.instantiateN5Reader( StorageFormat.ZARR, store.getContainerURI() ) : null;
	}

	private URI container() { return store.getContainerURI(); }

	// ------------------------------------------------------------------------------------------------
	// reading
	// ------------------------------------------------------------------------------------------------

	/** @return the attributes of an array as its {@code zarr.json} has them now (no cache), null if there is no such array */
	Attributes attributes( final String label, final int timepoint, final String name )
	{
		final String path = Attributes.path( label, timepoint, name );
		final JsonObject json = store.readAttributes( path );

		if ( json == null )
			return null;

		final JsonElement columns = json.get( InterestPointArrays.ATTR_COLUMNS );
		final JsonElement dtype = json.get( InterestPointArrays.ATTR_DTYPE );
		final JsonElement meta = json.get( InterestPointArrays.ATTR_META );

		if ( columns == null || !columns.isJsonPrimitive() || columns.getAsInt() < 1 || dtype == null || !dtype.isJsonPrimitive() )
			throw new IllegalStateException( "'" + path + "' of '" + container() + "' is no per-point array (it needs the attributes '" + InterestPointArrays.ATTR_COLUMNS + "' and '" + InterestPointArrays.ATTR_DTYPE + "'), stopping." );
		if ( !dtype.getAsString().equals( "float32" ) )
			throw new IllegalStateException( "'" + path + "' of '" + container() + "' has dtype '" + dtype.getAsString() + "', this reader supports float32, stopping." );

		return new Attributes( label, timepoint, name, columns.getAsInt(), dtype.getAsString(), meta != null && meta.isJsonObject() ? meta.getAsJsonObject() : new JsonObject() );
	}

	/** @return the arrays of a (label, timepoint) by name, ascending; empty if it has none */
	List< Attributes > list( final String label, final int timepoint )
	{
		final List< Attributes > result = new ArrayList<>();
		final KeyValueAccess kva = store.kva();
		final String directory = store.path( new Group( label, timepoint ).path(), InterestPointArrays.ARRAYS );

		if ( !kva.exists( directory ) )
			return result;

		final String[] names = kva.listDirectories( directory );
		Arrays.sort( names );

		for ( final String name : names )
		{
			final Attributes attributes = attributes( label, timepoint, name );
			if ( attributes != null )
				result.add( attributes );
		}

		return result;
	}

	/** @return the generation of an array as its {@code zarr.json} says now, -1 if it has none (or the array is gone) */
	private long readGeneration( final String path )
	{
		final JsonObject json = store.readAttributes( path );
		final JsonElement generation = json == null ? null : json.get( ATTR_GENERATION );

		return generation == null || !generation.isJsonPrimitive() ? -1 : generation.getAsLong();
	}

	/**
	 * @return the index of the generation an array points at now: the generation is read from the array's {@code zarr.json}
	 *         every time, the index of a generation (which never changes) is kept, so that reading view after view does
	 *         not read the entries of all views every time
	 */
	private Index index( final N5Reader reader, final Attributes array )
	{
		final long generation = reader == null ? -1 : readGeneration( array.path() );
		if ( generation < 0 )
		{
			indexes.remove( array.path() );
			return Index.NONE;
		}

		final Index cached = indexes.get( array.path() );
		if ( cached != null && cached.generation() == generation )
			return cached;

		final Index loaded = loadIndex( reader, array, generation );
		indexes.put( array.path(), loaded );
		return loaded;
	}

	private Index loadIndex( final N5Reader reader, final Attributes array, final long generation )
	{
		final String data = dataPath( array.path(), generation );
		final long[] rows = InterestPointsZarrStore.readLongs( reader, data + "/" + ENTRIES, ENTRY_COLUMNS );
		final Map< Integer, Entry > entries = new HashMap<>();
		for ( int row = 0; row < rows.length; row += ENTRY_COLUMNS )
			entries.put( (int) rows[ row ], new Entry( rows[ row + 1 ], (int) rows[ row + 2 ] ) );

		return new Index( generation, data, entries, reader.getDatasetAttributes( data + "/" + InterestPointArrays.VALUES ), reader.getDatasetAttributes( data + "/" + InterestPointArrays.ID ) );
	}

	private static String dataPath( final String arrayPath, final long generation ) { return arrayPath + "/g" + generation; }

	/** Runs a read of an array's generation again if it fails: a commit may have replaced the generation in between. */
	private static < T > T retrying( final Supplier< T > read )
	{
		try
		{
			return read.get();
		}
		catch ( final RuntimeException e )
		{
			return read.get();
		}
	}

	/** @return the rows of the complete tables {@code setup<S>} of an array (the layout per setup), by setup */
	private static SortedMap< Integer, Long > perSetupRows( final N5Reader reader, final String path )
	{
		final SortedMap< Integer, Long > rows = new TreeMap<>();

		if ( reader == null || !reader.exists( path ) )
			return rows;

		for ( final String child : reader.list( path ) )
		{
			final Integer setup = InterestPointArrays.setupOf( child );

			if ( setup == null )
				continue;

			final String tables = path + "/" + child;
			if ( reader.datasetExists( tables + "/" + InterestPointArrays.VALUES ) )
				rows.put( setup, reader.getDatasetAttributes( tables + "/" + InterestPointArrays.ID ).getDimensions()[ 1 ] );
		}

		return rows;
	}

	/**
	 * @param reader a reader of the container ({@link #reader()}), null if the dataset has none
	 * @return the number of rows the container holds per setup of an array, wherever they are (generation, tables per
	 *         setup, staging files); empty if it holds none
	 */
	SortedMap< Integer, Long > rows( final N5Reader reader, final Attributes array )
	{
		final TreeMap< Integer, Long > rows = new TreeMap<>();

		if ( reader == null )
			return rows;

		for ( final Map.Entry< Integer, Entry > e : retrying( () -> index( reader, array ) ).entries().entrySet() )
			rows.put( e.getKey(), (long) e.getValue().count() );

		rows.putAll( perSetupRows( reader, array.path() ) );

		for ( final StagedRows staged : staged( false ) ) // chronological: a later file wins
			if ( staged.key().equals( new ArrayKey( array.label(), array.timepoint(), array.name() ) ) && staged.columns() == array.columns() )
				rows.put( staged.setup(), (long) staged.rows() );

		return rows;
	}

	/**
	 * @param reader a reader of the container ({@link #reader()}), null if the dataset has none
	 * @return the rows the container holds for a setup of an array, null if it holds none (tables {@code setup<S>} without
	 *         a complete {@code values} are a write in progress and count as none)
	 */
	Rows read( final N5Reader reader, final Attributes array, final int setup )
	{
		if ( reader == null )
			return null;

		// rows written directly since the last commit
		StagedRows latest = null;
		for ( final StagedRows staged : staged( false ) )
			if ( staged.setup() == setup && staged.columns() == array.columns() && staged.key().equals( new ArrayKey( array.label(), array.timepoint(), array.name() ) ) )
				latest = staged;

		if ( latest != null )
		{
			final Rows rows = readStagingFile( latest, array );
			if ( rows != null )
				return rows;
		}

		// the tables of the setup: another writer's rows, or rows from before the arrays had generations
		final String tables = array.path() + "/" + InterestPointArrays.SETUP_PREFIX + setup;
		if ( reader.datasetExists( tables + "/" + InterestPointArrays.VALUES ) )
		{
			final long rows = reader.getDatasetAttributes( tables + "/" + InterestPointArrays.ID ).getDimensions()[ 1 ];

			return new Rows( array, setup,
					readTable( reader, tables + "/" + InterestPointArrays.ID, 1, 0, rows, rows, DataType.INT32 ),
					readTable( reader, tables + "/" + InterestPointArrays.VALUES, array.columns(), 0, rows, rows, DataType.FLOAT32 ) );
		}

		// the generation
		return retrying( () ->
		{
			final Index index = index( reader, array );
			final Entry entry = index.entries().get( setup );

			return entry == null ? null : read( reader, array, index, setup, entry );
		} );
	}

	private Rows read( final N5Reader reader, final Attributes array, final Index index, final int setup, final Entry entry )
	{
		return new Rows( array, setup,
				readTable( reader, index.dataPath() + "/" + InterestPointArrays.ID, 1, entry.offset(), entry.count(), -1, DataType.INT32 ),
				readTable( reader, index.dataPath() + "/" + InterestPointArrays.VALUES, array.columns(), entry.offset(), entry.count(), -1, DataType.FLOAT32 ) );
	}

	/**
	 * Reads the rows [offset, offset + count) of a [columns, rows] table (rows of {@code columns} values, columns fastest)
	 * chunk by chunk through the shard index.
	 *
	 * @param expectedRows the number of rows the table must have, -1 = any that holds the range
	 */
	@SuppressWarnings( "unchecked" )
	private < T > T readTable( final N5Reader reader, final String dataset, final int columns, final long offset, final long count, final long expectedRows, final DataType type )
	{
		final DatasetAttributes attributes = reader.getDatasetAttributes( dataset );

		if ( attributes == null )
			throw new IllegalStateException( "'" + dataset + "' of '" + container() + "' is missing, stopping." );

		final long[] dimensions = attributes.getDimensions();

		if ( dimensions.length != 2 || dimensions[ 0 ] != columns || ( expectedRows >= 0 && dimensions[ 1 ] != expectedRows ) )
			throw new IllegalStateException( "'" + dataset + "' of '" + container() + "' is " + Arrays.toString( dimensions ) + ", expected [" + columns + ", " + ( expectedRows >= 0 ? "" + expectedRows : "rows" ) + "] (columns, rows), stopping." );
		if ( offset + count > dimensions[ 1 ] )
			throw new IllegalStateException( "the index of '" + dataset + "' of '" + container() + "' refers to rows [" + offset + ", " + ( offset + count ) + ") but the table has " + dimensions[ 1 ] + " rows, stopping." );
		if ( attributes.getDataType() != type )
			throw new IllegalStateException( "'" + dataset + "' of '" + container() + "' is " + attributes.getDataType() + ", expected " + type + ", stopping." );
		if ( count * columns > Integer.MAX_VALUE - 8 )
			throw new IllegalStateException( "'" + dataset + "' of '" + container() + "' has " + count + " rows of " + columns + " values for one view, more than can be held in memory, stopping." );

		final int chunkRows = attributes.getChunkSize()[ 1 ];
		if ( attributes.getChunkSize()[ 0 ] != columns )
			throw new IllegalStateException( "'" + dataset + "' of '" + container() + "' is chunked along the columns, this reader expects whole rows per chunk, stopping." );

		final T data = (T) ( type == DataType.INT32 ? new int[ (int) ( count * columns ) ] : new float[ (int) ( count * columns ) ] );

		if ( count == 0 )
			return data;

		final long end = offset + count;

		for ( long chunkIndex = offset / chunkRows; chunkIndex * chunkRows < end; ++chunkIndex )
		{
			final DataBlock< ? > block = reader.readChunk( dataset, attributes, 0, chunkIndex );
			if ( block == null )
				throw new IllegalStateException( "chunk " + chunkIndex + " of '" + dataset + "' of '" + container() + "' is missing, stopping." );

			final long chunkStart = chunkIndex * chunkRows;
			final long from = Math.max( chunkStart, offset );
			final long to = Math.min( chunkStart + chunkRows, end );

			if ( java.lang.reflect.Array.getLength( block.getData() ) < ( to - chunkStart ) * columns )
				throw new IllegalStateException( "chunk " + chunkIndex + " of '" + dataset + "' of '" + container() + "' holds fewer values than the array's shape says, stopping." );

			System.arraycopy( block.getData(), (int) ( ( from - chunkStart ) * columns ), data, (int) ( ( from - offset ) * columns ), (int) ( ( to - from ) * columns ) );
		}

		return data;
	}

	// ------------------------------------------------------------------------------------------------
	// staging files: the rows of one setup, written directly (one per Spark task), folded in by the next commit
	// ------------------------------------------------------------------------------------------------

	/**
	 * @param force list the staging directory now; otherwise at most every {@link InterestPointsZarrStore#generationCheckMillis}
	 * @return the staging files of all arrays, chronological (the headers of files seen before are kept)
	 */
	private synchronized List< StagedRows > staged( final boolean force )
	{
		final long now = System.currentTimeMillis();

		if ( force || !listed || now - lastListed >= InterestPointsZarrStore.generationCheckMillis )
		{
			final KeyValueAccess kva = store.kva();
			final String directory = store.path( InterestPointsZarrStore.STAGING );
			final Set< String > names = new HashSet<>();

			if ( kva.exists( directory ) )
				for ( final String name : kva.list( directory ) )
					if ( name.endsWith( ROWS_EXT ) )
						names.add( name );

			stagingFiles.keySet().retainAll( names );

			for ( final String name : names )
				if ( !stagingFiles.containsKey( name ) )
				{
					final StagedRows header = readHeader( name );
					if ( header != null )
						stagingFiles.put( name, header );
				}

			listed = true;
			lastListed = now;
		}

		return new ArrayList<>( stagingFiles.values() );
	}

	/** @return the header of a staging file, null if it cannot be read (still being written, or gone) */
	private StagedRows readHeader( final String file )
	{
		try ( final DataInputStream in = store.openStagingFile( file ) )
		{
			return parseHeader( file, in );
		}
		catch ( final IOException | RuntimeException e )
		{
			if ( !InterestPointsZarrStore.isMissingFile( e ) )
				IOFunctions.println( "InterestPointArrays: WARNING cannot read staging file " + file + ": " + e );
			return null;
		}
	}

	private static StagedRows parseHeader( final String file, final DataInputStream in ) throws IOException
	{
		final int magic = in.readInt();
		final int version = in.readInt();

		if ( magic != ROWS_MAGIC || version != ROWS_VERSION )
			throw new IOException( "'" + file + "' is no staging file of per-point rows of version " + ROWS_VERSION + " (magic " + Integer.toHexString( magic ) + ", version " + version + ")" );

		final String label = in.readUTF();
		final int timepoint = in.readInt();
		final String name = in.readUTF();
		final int setup = in.readInt();
		final int columns = in.readInt();
		final int rows = in.readInt();
		final int payload = in.readInt();

		if ( columns < 1 || rows < 0 )
			throw new IOException( "'" + file + "' announces " + rows + " rows of " + columns + " columns" );

		return new StagedRows( file, label, timepoint, name, setup, columns, rows, payload );
	}

	/** @return the rows of a staging file, null if the file is gone (another JVM committed and deleted it) */
	private Rows readStagingFile( final StagedRows staged, final Attributes array )
	{
		try ( final DataInputStream in = store.openStagingFile( staged.file() ) )
		{
			in.skipNBytes( staged.payload() );

			final int[] ids = new int[ staged.rows() ];
			final float[] values = new float[ staged.rows() * staged.columns() ];

			ByteBuffer.wrap( readFully( in, 4 * ids.length, staged.file() ) ).asIntBuffer().get( ids );
			ByteBuffer.wrap( readFully( in, 4 * values.length, staged.file() ) ).asFloatBuffer().get( values );

			return new Rows( array, staged.setup(), ids, values );
		}
		catch ( final IOException | RuntimeException e )
		{
			if ( InterestPointsZarrStore.isMissingFile( e ) )
			{
				synchronized ( this )
				{
					stagingFiles.remove( staged.file() );
				}
				return null;
			}

			throw new IllegalStateException( "cannot read the rows of setup " + staged.setup() + " of the array '" + staged.name() + "' from the staging file '" + staged.file() + "' of '" + container() + "': " + e, e );
		}
	}

	private static byte[] readFully( final DataInputStream in, final int length, final String file ) throws IOException
	{
		final byte[] bytes = in.readNBytes( length );
		if ( bytes.length < length )
			throw new IOException( "'" + file + "' is truncated (" + bytes.length + " of " + length + " bytes)" );

		return bytes;
	}

	private void deleteStaged( final Collection< StagedRows > files )
	{
		final KeyValueAccess kva = store.kva();

		for ( final StagedRows file : files )
		{
			final String path = store.path( InterestPointsZarrStore.STAGING, file.file() );
			if ( kva.exists( path ) )
				kva.delete( path );
		}

		synchronized ( this )
		{
			for ( final StagedRows file : files )
				stagingFiles.remove( file.file() );
		}
	}

	private List< StagedRows > stagedOf( final String label, final int timepoint, final String name, final boolean force )
	{
		final ArrayKey key = new ArrayKey( label, timepoint, name );
		final List< StagedRows > files = new ArrayList<>();

		for ( final StagedRows staged : staged( force ) )
			if ( staged.key().equals( key ) )
				files.add( staged );

		return files;
	}

	// ------------------------------------------------------------------------------------------------
	// writing directly
	// ------------------------------------------------------------------------------------------------

	private N5Writer writer()
	{
		store.ensureContainer();
		return URITools.instantiateN5Writer( StorageFormat.ZARR, container() );
	}

	private static Map< String, Object > attributeMap( final int columns, final JsonObject meta, final Long generation )
	{
		final Map< String, Object > attributes = new HashMap<>();
		attributes.put( InterestPointArrays.ATTR_COLUMNS, columns );
		attributes.put( InterestPointArrays.ATTR_DTYPE, "float32" );
		attributes.put( InterestPointArrays.ATTR_META, meta == null ? new JsonObject() : meta );
		attributes.put( InterestPointArrays.ATTR_LAYOUT, InterestPointArrays.LAYOUT );
		if ( generation != null )
			attributes.put( ATTR_GENERATION, generation );
		return attributes;
	}

	private static void create( final N5Writer zarr, final String path, final int columns, final JsonObject meta )
	{
		zarr.createGroup( path );
		zarr.setAttributes( path, attributeMap( columns, meta, null ) );
	}

	/** Creates the group of an array with its attributes; an existing one is left as it is. */
	void define( final String label, final int timepoint, final String name, final int columns, final JsonObject meta )
	{
		try ( final N5Writer zarr = writer() )
		{
			final String path = Attributes.path( label, timepoint, name );
			if ( !zarr.exists( path ) )
				create( zarr, path, columns, meta );
		}
	}

	/**
	 * Writes the rows of one setup as a staging file: they are the setup's rows for every reader from now on (replacing
	 * those the array holds) and move into the array's tables with the next commit.
	 */
	void stageRows( final String label, final int timepoint, final String name, final int setup, final int[] ids, final float[] values, final int columns )
	{
		final Attributes array = attributes( label, timepoint, name );

		if ( array == null )
			throw new IllegalStateException( "The array '" + Attributes.path( label, timepoint, name ) + "' does not exist in '" + container() + "'; create it first (createArray)." );
		if ( array.columns() != columns )
			throw new IllegalStateException( "The array '" + array.path() + "' of '" + container() + "' has " + array.columns() + " columns, the rows have " + columns + "." );

		try
		{
			final ByteArrayOutputStream header = new ByteArrayOutputStream();

			try ( final DataOutputStream out = new DataOutputStream( header ) )
			{
				out.writeInt( ROWS_MAGIC );
				out.writeInt( ROWS_VERSION );
				out.writeUTF( label );
				out.writeInt( timepoint );
				out.writeUTF( name );
				out.writeInt( setup );
				out.writeInt( columns );
				out.writeInt( ids.length );
				out.writeInt( header.size() + 4 ); // where the rows start: after this field
			}

			final long size = header.size() + 4L * ids.length + 4L * values.length;
			if ( size > Integer.MAX_VALUE - 8 )
				throw new IllegalArgumentException( "the rows of one view must stay below 2 GB (" + ids.length + " rows of " + columns + " values = " + size + " bytes)" );

			final ByteBuffer file = ByteBuffer.allocate( (int) size );
			file.put( header.toByteArray() );
			file.asIntBuffer().put( ids );
			file.position( file.position() + 4 * ids.length );
			file.asFloatBuffer().put( values );

			final String fileName = String.format( "%013d_%016x_%08x%s", System.currentTimeMillis(), System.nanoTime(), new Random().nextInt(), ROWS_EXT );
			final KeyValueAccess kva = store.kva();
			kva.createDirectories( store.path( InterestPointsZarrStore.STAGING ) );
			kva.write( store.path( InterestPointsZarrStore.STAGING, fileName ), ReadData.from( file.array() ) );

			synchronized ( this )
			{
				stagingFiles.put( fileName, new StagedRows( fileName, label, timepoint, name, setup, columns, ids.length, header.size() ) );
			}
		}
		catch ( final IOException e )
		{
			throw new RuntimeException( "could not write the rows of setup " + setup + " of the array '" + name + "' as a staging file", e );
		}
	}

	/**
	 * Removes an array with everything it holds (generation, tables per setup, staging files).
	 *
	 * @return whether there was one
	 */
	boolean remove( final String label, final int timepoint, final String name )
	{
		if ( !store.exists() )
			return false;

		deleteStaged( stagedOf( label, timepoint, name, true ) );

		try ( final N5Writer zarr = writer() )
		{
			final String path = Attributes.path( label, timepoint, name );
			return zarr.exists( path ) && zarr.remove( path );
		}
	}

	// ------------------------------------------------------------------------------------------------
	// commit
	// ------------------------------------------------------------------------------------------------

	/**
	 * Writes what this JVM holds for the arrays of the dataset and what was written directly since the last commit
	 * (staging files) into the arrays' tables, array by array, see the class description. What is held is forgotten
	 * once every array is written; if an array fails, all of it is kept and the next commit writes it again.
	 */
	void commit( final StagedInterestPointArrays staged )
	{
		synchronized ( staged )
		{
			final List< StagedRows > files = staged( true );

			if ( staged.isEmpty() && files.isEmpty() )
				return;

			// nothing in a container that does not exist can be replaced or removed; only rows held here need one
			if ( !store.exists() )
			{
				boolean rows = false;
				for ( final StagedArray array : staged.arrays.values() )
					rows |= !array.removed && !array.ids.isEmpty();

				if ( !rows )
				{
					staged.clear();
					return;
				}
			}

			final long startTime = System.currentTimeMillis();

			final TreeMap< ArrayKey, List< StagedRows > > filesOf = new TreeMap<>();
			for ( final StagedRows file : files )
				filesOf.computeIfAbsent( file.key(), k -> new ArrayList<>() ).add( file );

			// the arrays to look at: those changed here, those with rows written directly, and every array of a (label,
			// timepoint) some of whose views got other points
			final TreeSet< ArrayKey > targets = new TreeSet<>( staged.arrays.keySet() );
			targets.addAll( filesOf.keySet() );
			for ( final Group group : staged.removedSetups.keySet() )
				for ( final Attributes array : list( group.label(), group.tp() ) )
					targets.add( new ArrayKey( group.label(), group.tp(), array.name() ) );

			final Summary summary = new Summary();

			if ( !targets.isEmpty() )
			{
				try ( final N5Writer zarr = writer() )
				{
					for ( final ArrayKey key : targets )
					{
						// the setups whose rows go: their points were replaced or removed (every array of the group), or the rows were removed from this array
						final StagedArray held = staged.arrays.get( key );
						final TreeSet< Integer > stale = new TreeSet<>();
						if ( staged.removedSetups.containsKey( key.group() ) )
							stale.addAll( staged.removedSetups.get( key.group() ) );
						if ( held != null )
							stale.addAll( held.removedSetups );

						commitArray( zarr, key, held, stale, filesOf.getOrDefault( key, List.of() ), summary );
					}
				}
			}

			staged.clear();

			if ( summary.arraysWritten > 0 || summary.setupsDropped > 0 || summary.arraysRemoved > 0 )
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Embeddings: wrote " + summary.rowsWritten + " rows of " + summary.setupsWritten + " view(s) into " + summary.arraysWritten + " array(s); dropped the rows of " + summary.setupsDropped
						+ " view(s) (removed, or their points were replaced or removed); removed " + summary.arraysRemoved + " array(s), in " + ( System.currentTimeMillis() - startTime ) + " ms" );
		}
	}

	/** the rows of a setup held in memory, as a source of the next generation */
	private static final Object MEMORY = new Object();

	private void commitArray( final N5Writer zarr, final ArrayKey key, final StagedArray held, final Set< Integer > stale, List< StagedRows > files, final Summary summary )
	{
		final long startTime = System.currentTimeMillis();
		final String label = key.label(), name = key.name();
		final int timepoint = key.timepoint();
		final String path = Attributes.path( label, timepoint, name );

		// the array goes as a whole: removed here, or another model's rows under a name that is written again
		if ( held != null && ( held.removed || held.replace ) )
		{
			deleteStaged( files );
			files = List.of();

			if ( zarr.exists( path ) )
			{
				zarr.remove( path );
				++summary.arraysRemoved;
			}

			if ( held.removed )
				return;
		}

		final boolean rowsHeld = held != null && !held.ids.isEmpty();
		Attributes array = attributes( label, timepoint, name );

		if ( array == null )
		{
			// rows written directly for an array that is gone since (its label was removed): there is nothing to add them to
			deleteStaged( files );
			files = List.of();

			if ( !rowsHeld )
				return; // an array defined without rows is not created
			if ( held.columns < 1 )
				throw new IllegalStateException( "Rows are held for the array '" + name + "' of label '" + label + "', tpId=" + timepoint + ", but the array is neither defined nor in the store." );

			create( zarr, path, held.columns, held.meta );
			array = new Attributes( label, timepoint, name, held.columns, "float32", held.meta == null ? new JsonObject() : held.meta );
		}
		else if ( held != null && held.columns > 0 && held.columns != array.columns() )
		{
			throw new IllegalStateException( "The array '" + name + "' of label '" + label + "', tpId=" + timepoint + " has " + array.columns() + " columns in '" + container() + "', the rows held for it have " + held.columns + "." );
		}

		final int columns = array.columns();
		final Index old = index( zarr, array );
		final SortedMap< Integer, Long > tables = perSetupRows( zarr, path );

		// the latest rows written directly, per setup (rows of another width belong to an array that was replaced since)
		final TreeMap< Integer, StagedRows > direct = new TreeMap<>();
		for ( final StagedRows file : files )
			if ( file.columns() == columns )
				direct.put( file.setup(), file );

		// where the rows of the next generation come from, by setup: an Entry of the old one, a staging file, or MEMORY
		final TreeMap< Integer, Object > sources = new TreeMap<>();
		final TreeSet< Integer > dropped = new TreeSet<>(); // setups whose rows describe points that are gone
		boolean changed = false;

		for ( final Map.Entry< Integer, Entry > e : old.entries().entrySet() )
		{
			if ( stale.contains( e.getKey() ) )
			{
				dropped.add( e.getKey() );
				changed = true;
			}
			else if ( tables.containsKey( e.getKey() ) )
				changed = true; // tables of the setup were written since: they are its rows
			else
				sources.put( e.getKey(), e.getValue() );
		}

		for ( final Map.Entry< Integer, StagedRows > e : direct.entrySet() )
		{
			if ( stale.contains( e.getKey() ) )
			{
				dropped.add( e.getKey() );
			}
			else
			{
				sources.put( e.getKey(), e.getValue() );
				changed = true;
			}
		}

		// rows computed here after the points of a view were replaced are the rows of the new points
		if ( rowsHeld )
		{
			for ( final Integer setup : held.ids.keySet() )
				sources.put( setup, MEMORY );
			changed = true;
		}

		// tables per setup go when their rows are replaced by this commit or describe points that are gone
		final List< Integer > tablesToDelete = new ArrayList<>();
		for ( final Integer setup : tables.keySet() )
		{
			if ( stale.contains( setup ) )
			{
				if ( !sources.containsKey( setup ) )
					dropped.add( setup );
				tablesToDelete.add( setup );
			}
			else if ( sources.containsKey( setup ) )
				tablesToDelete.add( setup );
		}

		dropped.removeAll( sources.keySet() );
		summary.setupsDropped += dropped.size();

		final boolean rowsLeft = !sources.isEmpty() || tables.size() > tablesToDelete.size();

		if ( !rowsLeft )
		{
			// an array left without rows (or that never had any) is removed
			flip( zarr, array, old, -1, null, true );
			deleteStaged( files );
			++summary.arraysRemoved;
			return;
		}

		if ( changed )
		{
			if ( sources.isEmpty() )
			{
				// only tables per setup are left
				flip( zarr, array, old, -1, null, false );
			}
			else
			{
				final long generation = Math.max( old.generation() + 1, System.currentTimeMillis() );
				final String data = dataPath( path, generation );
				final int chunkRows = old.exists() ? old.valuesAttrs().getChunkSize()[ 1 ] : Math.max( 1, InterestPointArrays.defaultChunkRows );
				final int shardRows = old.exists() ? old.valuesAttrs().getBlockSize()[ 1 ] : InterestPointsZarrStore.roundUp( Math.max( chunkRows, InterestPointArrays.defaultShardRows ), chunkRows );

				final long[] entries = new long[ ENTRY_COLUMNS * sources.size() ];
				long rows = 0;
				int i = 0;
				for ( final Map.Entry< Integer, Object > e : sources.entrySet() )
				{
					final int count = e.getValue() instanceof Entry ? ( (Entry) e.getValue() ).count() : e.getValue() instanceof StagedRows ? ( (StagedRows) e.getValue() ).rows() : held.ids.get( e.getKey() ).length;
					entries[ i++ ] = e.getKey();
					entries[ i++ ] = rows;
					entries[ i++ ] = count;
					rows += count;
				}

				zarr.createGroup( data );

				try
				{
					final DatasetAttributes valuesAttrs = InterestPointsZarrStore.arrayAttrs( columns, rows, DataType.FLOAT32, shardRows, chunkRows );
					final DatasetAttributes idAttrs = InterestPointsZarrStore.arrayAttrs( 1, rows, DataType.INT32, shardRows, chunkRows );
					zarr.createDataset( data + "/" + InterestPointArrays.VALUES, valuesAttrs );
					zarr.createDataset( data + "/" + InterestPointArrays.ID, idAttrs );
					final ArrayWriter valuesWriter = new ArrayWriter( zarr, data + "/" + InterestPointArrays.VALUES, valuesAttrs, float[].class );
					final ArrayWriter idWriter = new ArrayWriter( zarr, data + "/" + InterestPointArrays.ID, idAttrs, int[].class );

					// setup by setup in setup order, streamed into shards
					final List< Map.Entry< Integer, Object > > ordered = new ArrayList<>( sources.entrySet() );
					final int copyRows = COPY_CHUNKS * old.chunkRows();

					for ( int k = 0; k < ordered.size(); )
					{
						final int setup = ordered.get( k ).getKey();
						final Object source = ordered.get( k ).getValue();

						if ( source instanceof Entry )
						{
							// kept rows: setups that follow each other in the old tables are copied as one range, piece by piece
							final long from = ( (Entry) source ).offset();
							long to = from + ( (Entry) source ).count();

							for ( ++k; k < ordered.size() && ordered.get( k ).getValue() instanceof Entry && ( (Entry) ordered.get( k ).getValue() ).offset() == to; ++k )
								to += ( (Entry) ordered.get( k ).getValue() ).count();

							for ( long offset = from; offset < to; offset += copyRows )
							{
								final int count = (int) Math.min( copyRows, to - offset );
								valuesWriter.append( readTable( zarr, old.dataPath() + "/" + InterestPointArrays.VALUES, columns, offset, count, -1, DataType.FLOAT32 ), count );
								idWriter.append( readTable( zarr, old.dataPath() + "/" + InterestPointArrays.ID, 1, offset, count, -1, DataType.INT32 ), count );
							}

							continue;
						}

						final int[] ids;
						final float[] values;

						if ( source instanceof StagedRows )
						{
							final Rows written = readStagingFile( (StagedRows) source, array );
							if ( written == null )
								throw new IllegalStateException( "the staging file '" + ( (StagedRows) source ).file() + "' of '" + container() + "' is gone: another process committed the array '" + name + "' of label '" + label + "' while this one was writing; nothing was overwritten, save again." );
							ids = written.ids();
							values = written.values();
						}
						else
						{
							ids = held.ids.get( setup );
							values = held.values.get( setup );
						}

						if ( values.length != (long) ids.length * columns )
							throw new IllegalStateException( "setup " + setup + " of the array '" + name + "' of label '" + label + "' has " + ids.length + " ids and " + values.length + " values, which are no rows of " + columns + " columns, stopping." );

						if ( ids.length > 0 )
						{
							valuesWriter.append( values, ids.length );
							idWriter.append( ids, ids.length );
						}

						++summary.setupsWritten;
						summary.rowsWritten += ids.length;
						++k;
					}

					valuesWriter.finish();
					idWriter.finish();
					InterestPointsZarrStore.writeLongs( zarr, data + "/" + ENTRIES, ENTRY_COLUMNS, entries );
				}
				catch ( final RuntimeException e )
				{
					InterestPointsZarrStore.removeIfExists( zarr, data ); // nothing points at it yet
					throw e;
				}

				flip( zarr, array, old, generation, data, false );

				++summary.arraysWritten;

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): InterestPointArrays: '" + label + "' tp " + timepoint + ", array '" + name + "': generation " + generation + " (" + sources.size() + " views, " + rows + " rows of " + columns
						+ ", chunk " + chunkRows + ", shard " + shardRows + ") in " + ( System.currentTimeMillis() - startTime ) + " ms" );
			}
		}

		for ( final Integer setup : tablesToDelete )
			InterestPointsZarrStore.removeIfExists( zarr, path + "/" + InterestPointArrays.SETUP_PREFIX + setup );

		deleteStaged( files );
	}

	/**
	 * The commit point of an array: under its lock file, checks that the generation on disk is still the one this commit
	 * started from, then writes the array's attributes pointing at the new generation (-1 = none), or removes the array;
	 * the old generation is deleted afterwards. If another process committed in between, the new generation ({@code data})
	 * is removed and nothing else is touched.
	 */
	private void flip( final N5Writer zarr, final Attributes array, final Index old, final long generation, final String data, final boolean removeArray )
	{
		final String path = array.path();
		final String lockPath = store.path( path, InterestPointsZarrStore.LOCK );

		try ( final LockedChannel lockFile = store.kva().lockForWriting( lockPath ) )
		{
			final long current = readGeneration( path );
			if ( current != old.generation() )
			{
				if ( data != null )
					InterestPointsZarrStore.removeIfExists( zarr, data );

				throw new IllegalStateException( "another process committed the array '" + array.name() + "' of label '" + array.label() + "' at timepoint " + array.timepoint() + " (generation " + current + ") while this one was writing on top of generation "
						+ old.generation() + "; nothing was overwritten, the rows are still held: save again." );
			}

			if ( removeArray )
				zarr.remove( path );
			else if ( generation != old.generation() )
				zarr.setAttributes( path, attributeMap( array.columns(), array.meta(), generation ) ); // the commit point
		}
		catch ( final IOException e )
		{
			throw new RuntimeException( "could not lock '" + lockPath + "'", e );
		}

		if ( old.exists() && !removeArray )
			InterestPointsZarrStore.removeIfExists( zarr, old.dataPath() );
	}
}
