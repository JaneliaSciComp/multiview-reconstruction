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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.FloatArrayDataBlock;
import org.janelia.saalfeldlab.n5.IntArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.RawCompression;
import org.janelia.saalfeldlab.n5.codec.checksum.Crc32cChecksumCodec;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Group;
import util.URITools;

/**
 * Per-point arrays of the interest point store: rows that a writer keeps about the store's points (the learned embedding of
 * every point: {@code process.interestpointembedding} and pcmatch's {@code embed}), keyed by point id and independent of the
 * generations of the points. They sit next to the generations of a (label, timepoint). Since 2026-11-14 an array is one
 * table for all views, written like the points (Stephan: "the same way we save interest points, sharding across views";
 * the layout, the staging files and the commit are {@link InterestPointArraysOnDisk}):
 *
 * <pre>
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/zarr.json          attributes: columns D, dtype "float32", meta (the writer's JSON, verbatim), generation K
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/gK/values           float32 [N, D]  the rows of all setups, in setup order
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/gK/id               int32   [N, 1]  the ids of the points the rows belong to
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/gK/entries          int64   [E, 3]  setup, offset, count
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/setup&lt;S&gt;/{id, values}  the layout until 2026-11-14 and pcmatch's: tables per setup, read only here
 * </pre>
 *
 * A filtered label has no rows of its own, the rows of its base label apply (same ids). An array describes the label as it
 * is: as many rows as the label has points in the view (the base label's points, for a filtered label) and a row for every
 * point. A detection replaces the points (another number of them, other ids), so an array with another number of rows or
 * without a row for a point is stale for the view and the view fails ("run pcmatch embed again") instead of being matched
 * on the vectors of other points; no stored positions are compared (Stephan, 2026-10-29). A row of NaN = no vector. Other
 * tables a writer keeps in {@code setup<S>} are ignored (pcmatch wrote a float32 {@code loc} table until 2026-10-29).
 *
 * The arrays are not part of a generation of the points: a commit of the (label, timepoint) leaves them where they are,
 * removing the label removes them with its timepoint group. Specification of the tables per setup:
 * {@code SCALESPACE_TASK_arrays.md} (pcmatch, 2026-10-28); they are read where a setup has them (they are what another
 * writer wrote since the last commit, or rows from before 2026-11-14) and never written here, except for fixtures
 * ({@link #writeSetupTables}).
 *
 * Two ways to write (Stephan, 2026-11-14), both end in {@link #commit}, which the save of the dataset calls. In memory
 * until the dataset is saved, like the interest points: the explorer and the plugin ({@link #stageArray},
 * {@link #stageSetup}, {@link #stageRemove}; {@link StagedInterestPointArrays}, one per store). Directly, for a writer
 * that shares no memory with the JVM that saves the dataset (the tasks of BigStitcher-Spark): {@link #createArray}, then
 * {@link #writeSetup} writes a setup's rows as one staging file, readable at once, which the next commit (the driver's
 * save) moves into the array's tables. The holder in memory also takes the rows that became stale: when a label is
 * overwritten ({@code InterestPointTools.removeReferences}: a detection, a filter) or an entry is removed
 * ({@code InterestPointsZarrStore.remove}), the rows of these views go from every array of the label
 * ({@link #stageRemoveRows}), for the readers at once and in the store with the save. The rows of some views can also be
 * removed from one array (the Interest Point Explorer's "Remove embeddings": {@link #viewsWithRows} is what it offers,
 * {@code InterestPointTools.removeEmbeddings} what it runs), the same way. All readers here lay the holder over the store,
 * so the explorer, the registration dialog and the matcher see the embeddings as the save will leave them.
 *
 * @author Stephan Preibisch
 */
public class InterestPointArrays
{
	public static final String ARRAYS = "arrays";

	/** the array pcmatch's {@code embed} writes its embeddings to (its {@code --array-name}; {@code descriptor} until 2026-10-29), the matcher's default */
	public static final String EMBEDDING = "embedding";
	public static final String SETUP_PREFIX = "setup";
	public static final String ID = "id";
	public static final String VALUES = "values";

	static final String ATTR_COLUMNS = "columns";
	static final String ATTR_DTYPE = "dtype";
	static final String ATTR_META = "meta";
	static final String ATTR_LAYOUT = "layout";

	/** what the writer records as the array's layout */
	public static final String LAYOUT = "g<generation>/: values float32 [N, D] and id int32 [N, 1] hold the rows of all setups in setup order, entries int64 [E, 3] = setup, offset, count; "
			+ "setup<S>/{id, values}: rows another writer keeps per setup, they are the setup's rows where they exist; rows are joined to the label's points by id; one row per point of the (base) label";

	/**
	 * rows per inner chunk and per shard of an array's tables: 1 MB and 16.7 MB for 64 columns (an array keeps its grid).
	 * Smaller than the points' 65,536 rows: a row here is about ten times a point, and the last chunk of a table is stored
	 * whole.
	 */
	public static int defaultChunkRows = 4096, defaultShardRows = 1 << 16;

	/** The attributes of an array: its width, type and the writer's meta (pcmatch records the model there: {@code model.fingerprint}, {@code model.file}, ...). */
	public record Attributes( String label, int timepoint, String name, int columns, String dtype, JsonObject meta )
	{
		/** @return {@code meta.model.fingerprint}, the identity of the model that wrote the rows (rows of two models are not comparable), or null if the writer recorded none */
		public String fingerprint()
		{
			final JsonElement model = meta == null ? null : meta.get( "model" );
			final JsonElement fingerprint = model == null || !model.isJsonObject() ? null : model.getAsJsonObject().get( "fingerprint" );
			return fingerprint == null || !fingerprint.isJsonPrimitive() ? null : fingerprint.getAsString();
		}

		/** @return the path of the array's group within the container */
		public String path() { return path( label, timepoint, name ); }

		static String path( final String label, final int timepoint, final String name ) { return new Group( label, timepoint ).path() + "/" + ARRAYS + "/" + name; }
	}

	/** One setup's rows as stored: row {@code r} belongs to the point {@code ids[r]} and holds {@code values[columns*r .. columns*(r+1))}. */
	public record Rows( Attributes attributes, int setup, int[] ids, float[] values )
	{
		public int size() { return ids.length; }
		public int columns() { return attributes.columns(); }
	}

	/**
	 * The vectors of a view's points: {@code ids} ascending, {@code values[columns * i .. columns * (i + 1))} the vector of
	 * {@code ids[i]}; a point whose row is NaN has no vector, it is not listed and counted in {@code missing}.
	 */
	public record Vectors( Attributes attributes, int[] ids, float[] values, int missing )
	{
		public int size() { return ids.length; }
		public int columns() { return attributes.columns(); }

		/** @return the index of the vector of a point (its values start at {@code columns() * index}), or -1 if the point has none */
		public int indexOf( final int id )
		{
			final int index = Arrays.binarySearch( ids, id );
			return index < 0 ? -1 : index;
		}
	}

	/**
	 * @return the label whose rows apply to the given label of a view: the label itself, or the base of a filtered label
	 *         (a filtered label's points are the base's after the filter, same ids and positions)
	 * @throws IllegalStateException if the view has no such label or the chain of filtered labels is broken
	 */
	public static String baseLabel( final ViewInterestPointLists lists, final String label )
	{
		InterestPoints list = lists.getInterestPointList( label );
		if ( list == null )
			throw new IllegalStateException( view( lists ) + " has no label '" + label + "'." );

		String base = label;
		int chain = 0;
		while ( list instanceof VirtualFilteredInterestPoints )
		{
			if ( ++chain > VirtualFilteredInterestPoints.maxChainLength )
				throw new IllegalStateException( "The chain of filtered labels at '" + label + "' of " + view( lists ) + " is longer than " + VirtualFilteredInterestPoints.maxChainLength + " (a cycle?)." );
			base = ( (VirtualFilteredInterestPoints) list ).getBaseLabel();
			list = ( (VirtualFilteredInterestPoints) list ).getBase();
		}
		return base;
	}

	/**
	 * @return the attributes of the array {@code name} of (label, timepoint), or null if the dataset has no such array (no
	 *         store, no group); the label is the one the rows were written under (the base of a filtered label). As all
	 *         readers here: what this JVM holds for the next save ({@link StagedInterestPointArrays}) lies over the store.
	 */
	public static Attributes attributes( final URI baseDir, final String label, final int timepoint, final String name )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		final Attributes staged = store.stagedArrays.attributes( label, timepoint, name );

		if ( staged != null )
			return staged;

		if ( !store.stagedArrays.storeArrayCounts( label, timepoint, name ) || !store.exists() )
			return null;

		return store.arraysOnDisk.attributes( label, timepoint, name );
	}

	/**
	 * @return the arrays of (label, timepoint) by name, ascending; empty if the dataset has no store or the group has no
	 *         arrays; the label is the one the rows were written under (the base of a filtered label)
	 * @throws IllegalStateException if an array lacks its attributes or is not float32
	 */
	public static List< Attributes > list( final URI baseDir, final String label, final int timepoint )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );

		return store.stagedArrays.list( label, timepoint, store.exists() ? store.arraysOnDisk.list( label, timepoint ) : new ArrayList<>() );
	}

	/**
	 * @return the names of the arrays the labels of the given views have: the arrays of every (base label, timepoint) that
	 *         the views and their labels name, in one sorted set; empty if there are none or the dataset has no store. A
	 *         registration needs one of them (the matcher's array), the dialog offers them, the command line picks the
	 *         only one when none is named.
	 * @param vip the lists of the dataset
	 * @param labelMap the labels per view, as a registration selects them
	 */
	public static SortedSet< String > names( final ViewInterestPoints vip, final Map< ViewId, ? extends Map< String, ? > > labelMap )
	{
		final TreeSet< String > names = new TreeSet<>();
		final HashSet< String > listed = new HashSet<>(); // the (base path, base label, timepoint) groups listed already

		for ( final Map.Entry< ViewId, ? extends Map< String, ? > > e : labelMap.entrySet() )
		{
			final ViewInterestPointLists lists = vip.getViewInterestPointLists( e.getKey() );

			for ( final String label : e.getValue().keySet() )
			{
				final String base = baseLabel( lists, label );
				final URI baseDir = lists.getInterestPointList( label ).getBasePath();

				if ( listed.add( baseDir + "|" + base + "|" + lists.getTimePointId() ) )
					for ( final Attributes array : list( baseDir, base, lists.getTimePointId() ) )
						names.add( array.name() );
			}
		}

		return names;
	}

	/** where the rows of a view's label are: the (base label, timepoint) group of a dataset's store */
	private record Location( URI baseDir, String label, int timepoint ) {}

	/**
	 * @return for every label the given views have, the names of its arrays that hold rows: those of every (base label,
	 *         timepoint) a view has the label in, sorted (a filtered label has its base's); an empty set for a label
	 *         without such arrays. An array without rows for any setup (created, never written: a writer that failed)
	 *         is no embedding and not named. What {@link #names} gives for all labels in one set, per label, e.g. for a
	 *         dialog that marks the labels with stored embeddings. Every group is listed once, through one reader per store.
	 * @param vip the lists of the dataset
	 * @param views the views, those without interest points are skipped
	 * @throws IllegalStateException as {@link #baseLabel} and {@link #list}
	 */
	public static Map< String, SortedSet< String > > namesByLabel( final ViewInterestPoints vip, final Collection< ? extends ViewId > views )
	{
		final TreeMap< String, HashSet< Location > > locations = new TreeMap<>();

		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists lists = vip.getViewInterestPointLists( viewId );
			if ( lists == null )
				continue;

			for ( final String label : lists.getHashMap().keySet() )
				locations.computeIfAbsent( label, l -> new HashSet<>() ).add(
						new Location( lists.getInterestPointList( label ).getBasePath(), baseLabel( lists, label ), lists.getTimePointId() ) );
		}

		final TreeMap< String, SortedSet< String > > names = new TreeMap<>();
		final HashMap< URI, N5Reader > readers = new HashMap<>(); // per dataset directory, null = it has no store
		final HashMap< Location, List< Attributes > > listed = new HashMap<>();

		try
		{
			for ( final Map.Entry< String, HashSet< Location > > e : locations.entrySet() )
			{
				final SortedSet< String > ofLabel = names.computeIfAbsent( e.getKey(), l -> new TreeSet<>() );

				for ( final Location location : e.getValue() )
				{
					List< Attributes > arrays = listed.get( location );

					if ( arrays == null )
					{
						final InterestPointsZarrStore store = InterestPointsZarrStore.get( location.baseDir() );
						if ( !readers.containsKey( location.baseDir() ) )
							readers.put( location.baseDir(), store.arraysOnDisk.reader() );

						final N5Reader reader = readers.get( location.baseDir() );
						arrays = store.stagedArrays.list( location.label(), location.timepoint(),
								reader == null ? new ArrayList<>() : store.arraysOnDisk.list( location.label(), location.timepoint() ) );
						arrays.removeIf( array -> !hasRows( store, reader, array ) );
						listed.put( location, arrays );
					}

					for ( final Attributes array : arrays )
						ofLabel.add( array.name() );
				}
			}
		}
		finally
		{
			for ( final N5Reader reader : readers.values() )
				if ( reader != null )
					reader.close();
		}

		return names;
	}

	/**
	 * @return whether an array holds rows for any setup: rows computed here for the next save, or rows of the store (in the
	 *         array's tables, in complete tables of a setup, or written directly since the last commit) that still count
	 *         (the points of the view were not replaced)
	 * @param reader a reader of the store, null if the dataset has none
	 */
	private static boolean hasRows( final InterestPointsZarrStore store, final N5Reader reader, final Attributes array )
	{
		final StagedInterestPointArrays staged = store.stagedArrays;

		if ( staged.hasRows( array.label(), array.timepoint(), array.name() ) )
			return true;

		if ( reader == null || !staged.storeArrayCounts( array.label(), array.timepoint(), array.name() ) )
			return false;

		for ( final Integer setup : store.arraysOnDisk.rows( reader, array ).keySet() )
			if ( staged.storeRowsCount( array.label(), array.timepoint(), array.name(), setup ) )
				return true;

		return false;
	}

	/** @return the setup of a {@code setup<S>} directory of an array, null for anything else (e.g. a setup a writer is still writing under a temporary name) */
	static Integer setupOf( final String child )
	{
		if ( !child.startsWith( SETUP_PREFIX ) )
			return null;

		try
		{
			return Integer.parseInt( child.substring( SETUP_PREFIX.length() ) );
		}
		catch ( final NumberFormatException e )
		{
			return null;
		}
	}

	/**
	 * @return the number of rows an array holds per setup, by setup; empty if it has none. As all readers here: what this
	 *         JVM holds for the next save lies over the store.
	 */
	public static SortedMap< Integer, Long > rows( final URI baseDir, final Attributes array )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );

		return store.stagedArrays.rows( array, store.stagedArrays.storeArrayCounts( array.label(), array.timepoint(), array.name() ) ? rowsInStore( store, array ) : new TreeMap<>() );
	}

	/**
	 * @return the number of rows per setup of an array as the store holds it now, without what this JVM holds for the next
	 *         save (rows computed and not saved, rows that go with the save); empty if the store has no such array. For
	 *         tests and diagnostics: what a save has written.
	 */
	public static SortedMap< Integer, Long > rowsInStore( final URI baseDir, final String label, final int timepoint, final String name )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		final Attributes array = store.exists() ? store.arraysOnDisk.attributes( label, timepoint, name ) : null;

		return array == null ? new TreeMap<>() : rowsInStore( store, array );
	}

	/** @return the number of rows per setup of an array as the store holds it; empty if it has none */
	private static SortedMap< Integer, Long > rowsInStore( final InterestPointsZarrStore store, final Attributes array )
	{
		try ( final N5Reader reader = store.arraysOnDisk.reader() )
		{
			return store.arraysOnDisk.rows( reader, array );
		}
	}

	/**
	 * Reads one setup's rows of an array as stored (section 3 of the specification joins them to the points, see {@link #join}).
	 *
	 * @param baseDir the dataset directory (the store is {@code interestpoints.zarr} in it)
	 * @param label the label the rows were written under (the base of a filtered label, see {@link #baseLabel})
	 * @param timepoint the timepoint of the view
	 * @param setup the setup of the view
	 * @param name the name of the array (pcmatch's default is {@code embedding})
	 * @return the rows, or null if there is no such array or no rows for the setup (a {@code setup<S>} directory without
	 *         a complete {@code values} is a write in progress and counts as none)
	 * @throws IllegalStateException if the array is damaged (tables of different lengths, another type or chunking than specified)
	 */
	public static Rows read( final URI baseDir, final String label, final int timepoint, final int setup, final String name )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );

		// rows computed here for the next save
		final int[] stagedIds = store.stagedArrays.ids( label, timepoint, name, setup );
		final float[] stagedValues = store.stagedArrays.values( label, timepoint, name, setup );

		if ( stagedIds != null && stagedValues != null )
		{
			final Attributes attributes = attributes( baseDir, label, timepoint, name );

			if ( attributes == null )
				throw new IllegalStateException( "Rows are held for the array '" + name + "' of label '" + label + "', tpId=" + timepoint + ", but the array is neither defined nor in the store." );

			return new Rows( attributes, setup, stagedIds, stagedValues );
		}

		// the rows of the store, unless the array was removed or replaced here or the points of the view were replaced
		if ( !store.stagedArrays.storeRowsCount( label, timepoint, name, setup ) || !store.exists() )
			return null;

		final Attributes attributes = store.arraysOnDisk.attributes( label, timepoint, name );
		if ( attributes == null )
			return null;

		// a fresh reader every time: nothing an earlier read cached survives a writer that replaced the rows since
		try ( final N5Reader reader = store.arraysOnDisk.reader() )
		{
			return store.arraysOnDisk.read( reader, attributes, setup );
		}
	}

	/**
	 * Joins the rows to the points of a view by id (section 3 of the specification, without its comparison of positions
	 * since 2026-10-29: the array has to describe the label as it is, a row for every point and no others).
	 *
	 * @param rows the rows of the view's setup (of the base label, for a filtered label)
	 * @param points the points of the view and label (all of them or a selection; a filtered label's are the base's after the filter)
	 * @param pointsOfLabel the number of points the label the rows were written under has in the view (the base label's, for a filtered label)
	 * @return the vectors of the points that have one, by id; a point whose row is NaN is counted as missing
	 * @throws IllegalStateException if the array has another number of rows than the label has points, or a point has no row:
	 *         the label was detected again since the rows were written and the ids name other points, the writer has to run again
	 */
	public static Vectors join( final Rows rows, final Collection< ? extends InterestPoint > points, final int pointsOfLabel )
	{
		final int columns = rows.columns();

		if ( rows.size() != pointsOfLabel )
			throw new IllegalStateException( "The array '" + rows.attributes().name() + "' of " + where( rows ) + " has " + rows.size() + " rows, the label has " + pointsOfLabel
					+ " points: the label was detected again since the rows were written, or only some of its points were embedded; run pcmatch embed again for label '" + rows.attributes().label() + "'." );

		// row index by id: the upper 32 bits are the id, the lower the row, sorted
		final long[] keys = new long[ rows.size() ];
		for ( int r = 0; r < keys.length; ++r )
			keys[ r ] = ( (long) rows.ids()[ r ] << 32 ) | ( r & 0xffffffffL );
		Arrays.sort( keys );

		for ( int r = 1; r < keys.length; ++r )
			if ( ( keys[ r ] >> 32 ) == ( keys[ r - 1 ] >> 32 ) )
				throw new IllegalStateException( "The array '" + rows.attributes().name() + "' of " + where( rows ) + " lists the id " + ( keys[ r ] >> 32 ) + " twice, stopping." );

		final List< InterestPoint > found = new ArrayList<>();
		final List< Integer > foundRows = new ArrayList<>();
		final List< Integer > withoutRow = new ArrayList<>();
		int withoutRowCount = 0, missing = 0;

		for ( final InterestPoint point : points )
		{
			final int row = rowOf( keys, point.getId() );
			if ( row < 0 )
			{
				if ( ++withoutRowCount <= 3 )
					withoutRow.add( point.getId() );
				continue;
			}

			if ( isNaN( rows.values(), row * columns, columns ) )
			{
				++missing;
				continue;
			}

			found.add( point );
			foundRows.add( row );
		}

		if ( withoutRowCount > 0 )
			throw new IllegalStateException( withoutRowCount + " of " + points.size() + " points of " + where( rows ) + " have no row in the array '" + rows.attributes().name()
					+ "' (e.g. the ids " + withoutRow + "): the label was detected again since the rows were written, the ids name other points now; run pcmatch embed again for label '" + rows.attributes().label() + "'." );

		// by id, so that lookups are binary searches
		final Integer[] order = new Integer[ found.size() ];
		for ( int i = 0; i < order.length; ++i )
			order[ i ] = i;
		Arrays.sort( order, ( a, b ) -> Integer.compare( found.get( a ).getId(), found.get( b ).getId() ) );

		final int[] ids = new int[ order.length ];
		final float[] values = new float[ order.length * columns ];
		for ( int i = 0; i < order.length; ++i )
		{
			ids[ i ] = found.get( order[ i ] ).getId();
			System.arraycopy( rows.values(), foundRows.get( order[ i ] ) * columns, values, i * columns, columns );
		}

		return new Vectors( rows.attributes(), ids, values, missing );
	}

	/**
	 * The vectors of a view's label, all of section 3 of the specification: the rows of the base label's array for the
	 * view's setup, joined to the label's points by id, verified by count (the array must have a row for every point of the
	 * base label in the view and no others).
	 *
	 * @param lists the labels of the view
	 * @param label the label (a filtered one reads its base's rows)
	 * @param name the name of the array
	 * @return the vectors by point id
	 * @throws IllegalStateException if the dataset has no such array or no rows for the view, or if the rows are stale (another
	 *         number of rows than the base label has points, a point without a row; see {@link #join})
	 */
	public static Vectors vectors( final ViewInterestPointLists lists, final String label, final String name )
	{
		final String base = baseLabel( lists, label );
		final InterestPoints list = lists.getInterestPointList( label );
		final Rows rows = read( list.getBasePath(), base, lists.getTimePointId(), lists.getViewSetupId(), name );

		if ( rows == null )
			throw new IllegalStateException( "No stored array '" + name + "' for " + view( lists ) + ", label '" + label + "'" + ( base.equals( label ) ? "" : " (base label '" + base + "')" )
					+ " in '" + InterestPointsZarrStore.get( list.getBasePath() ).getContainerURI() + "': run pcmatch embed for label '" + base + "' first." );

		final Map< Integer, InterestPoint > points = list.getInterestPointsCopy();
		final int pointsOfBase = base.equals( label ) ? points.size() : lists.getInterestPointList( base ).getInterestPointsCopy().size();
		return join( rows, points.values(), pointsOfBase );
	}

	// ------------------------------------------------------------------------------------------------
	// writing
	// ------------------------------------------------------------------------------------------------

	/**
	 * Creates the group of an array with its attributes (columns, dtype float32, meta, layout), directly; the dataset's
	 * store must exist (the label's points are in it). An existing group is left as it is (its attributes are not touched).
	 *
	 * @param baseDir the dataset directory
	 * @param label the label the rows are written under (the base of a filtered label)
	 * @param timepoint the timepoint
	 * @param name the name of the array
	 * @param columns the width of a row
	 * @param meta the writer's meta ({@code model.fingerprint}, {@code model.file}, {@code writer}, {@code date}, ...)
	 */
	public static void createArray( final URI baseDir, final String label, final int timepoint, final String name, final int columns, final JsonObject meta )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		if ( !store.exists() )
			throw new IllegalStateException( "The dataset '" + baseDir + "' has no interest point store (" + InterestPointsZarrStore.ZARR_CONTAINER + "), the points of label '" + label + "' are not in it: save the dataset first, or, for points still in the N5 or text files of an old dataset, move them with Filter/Manage Interest Points > 'Resave as ZARR store'. (An array written directly needs the store; 'Compute Interest Point Embeddings' keeps its rows in memory and writes them when the dataset is saved, stageArray.)" );
		if ( columns < 1 )
			throw new IllegalArgumentException( "an array needs at least one column, got " + columns );

		store.arraysOnDisk.define( label, timepoint, name, columns, meta );
	}

	/**
	 * Writes one setup's rows of an array directly (the array must exist, {@link #createArray}): as one staging file of
	 * the store, which every reader takes as the setup's rows from now on and which the next {@link #commit} (the save of
	 * the dataset) moves into the array's tables. For a writer that shares no memory with the JVM that saves the dataset:
	 * one task of BigStitcher-Spark per view, the driver's save commits. Several writers may write setups of one array
	 * at the same time.
	 *
	 * @param baseDir the dataset directory
	 * @param label the label the rows are written under (the base of a filtered label)
	 * @param timepoint the timepoint
	 * @param name the name of the array
	 * @param setup the setup
	 * @param ids the ids of the points, one per row
	 * @param values the rows, {@code columns} floats per row
	 * @param columns the width of a row
	 */
	public static void writeSetup( final URI baseDir, final String label, final int timepoint, final String name, final int setup, final int[] ids, final float[] values, final int columns )
	{
		if ( columns < 1 || values.length != (long) ids.length * columns )
			throw new IllegalArgumentException( ids.length + " ids and " + values.length + " values do not make rows of " + columns + " columns" );

		InterestPointsZarrStore.get( baseDir ).arraysOnDisk.stageRows( label, timepoint, name, setup, ids, values, columns );
	}

	/**
	 * Writes one setup's rows as tables of their own, {@code setup<S>/{id, values}}: the layout of pcmatch and of this
	 * class until 2026-11-14 (the array must exist, {@link #createArray}; the setup's tables are replaced). Only for
	 * fixtures and tests of the readers, which take such tables as the setup's rows: every setup is an array of its own
	 * there, at least one inner chunk on disk each.
	 *
	 * @param chunkRows the rows per inner chunk
	 * @param shardRows the rows per shard, a multiple of {@code chunkRows}
	 */
	public static void writeSetupTables( final URI baseDir, final String label, final int timepoint, final String name, final int setup, final int[] ids, final float[] values, final int columns, final int chunkRows, final int shardRows )
	{
		if ( columns < 1 || values.length != (long) ids.length * columns )
			throw new IllegalArgumentException( ids.length + " ids and " + values.length + " values do not make rows of " + columns + " columns" );
		if ( chunkRows < 1 || shardRows < chunkRows || shardRows % chunkRows != 0 )
			throw new IllegalArgumentException( "a shard of " + shardRows + " rows must hold whole chunks of " + chunkRows + " rows" );

		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		final String path = Attributes.path( label, timepoint, name );

		try ( final N5Writer zarr = URITools.instantiateN5Writer( StorageFormat.ZARR, store.getContainerURI() ) )
		{
			if ( !zarr.exists( path ) )
				throw new IllegalStateException( "The array '" + path + "' does not exist in '" + store.getContainerURI() + "'; create it first (createArray)." );
			final Integer existingColumns = zarr.getAttribute( path, ATTR_COLUMNS, Integer.class );
			if ( existingColumns == null || existingColumns != columns )
				throw new IllegalStateException( "The array '" + path + "' of '" + store.getContainerURI() + "' has " + existingColumns + " columns, the rows have " + columns + "." );

			final String setupPath = path + "/" + SETUP_PREFIX + setup;
			if ( zarr.exists( setupPath ) )
				zarr.remove( setupPath ); // a setup's rows are replaced whole
			zarr.createGroup( setupPath );
			zarr.setAttribute( setupPath, "setup", setup );
			zarr.setAttribute( setupPath, "rows", ids.length );

			// id first: a setup without a complete values table reads as a write in progress
			writeTable( zarr, setupPath + "/" + ID, 1, ids.length, DataType.INT32, ids, chunkRows, shardRows );
			writeTable( zarr, setupPath + "/" + VALUES, columns, ids.length, DataType.FLOAT32, values, chunkRows, shardRows );
		}
	}

	/** Writes a whole [columns, rows] table (rows of {@code columns} values, columns fastest): zarr v3, sharded, raw + crc32c, {@code shardRows / chunkRows} chunks per shard. */
	public static void writeTable( final N5Writer zarr, final String dataset, final int columns, final int rows, final DataType type, final Object data, final int chunkRows, final int shardRows )
	{
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

	/**
	 * Removes an array of a (label, timepoint) directly, with everything the store holds for it (its tables, tables per
	 * setup, rows written directly and not committed yet).
	 *
	 * @return whether there was one
	 */
	public static boolean remove( final URI baseDir, final String label, final int timepoint, final String name )
	{
		return InterestPointsZarrStore.get( baseDir ).arraysOnDisk.remove( label, timepoint, name );
	}

	// ------------------------------------------------------------------------------------------------
	// in memory until the dataset is saved (the explorer; pcmatch and BigStitcher-Spark's tasks write directly, above)
	// ------------------------------------------------------------------------------------------------

	/**
	 * Defines an array of a (label, timepoint) in memory: it exists for all readers of this JVM from now on and is written
	 * when the dataset is saved ({@link #commit}), like interest points that were detected and not saved yet. The store
	 * need not exist.
	 *
	 * @param replace whether an array of that name (in the store or in memory) holds rows of another model or width: they
	 *        go, the array starts empty
	 * @return whether nothing was held in memory for the array before (a caller that fails before it adds rows can then
	 *         take the definition back, {@link #forgetStaged})
	 */
	public static boolean stageArray( final URI baseDir, final String label, final int timepoint, final String name, final int columns, final JsonObject meta, final boolean replace )
	{
		if ( columns < 1 )
			throw new IllegalArgumentException( "an array needs at least one column, got " + columns );

		return InterestPointsZarrStore.get( baseDir ).stagedArrays.define( label, timepoint, name, columns, meta == null ? new JsonObject() : meta, replace );
	}

	/**
	 * Keeps one setup's rows of an array in memory until the dataset is saved, replacing the setup's rows (those in memory
	 * now, those of the store with the save). The array must be defined ({@link #stageArray}) or exist in the store.
	 *
	 * @param ids the ids of the points the rows belong to
	 * @param values the rows, {@code columns} values each
	 */
	public static void stageSetup( final URI baseDir, final String label, final int timepoint, final String name, final int setup, final int[] ids, final float[] values, final int columns )
	{
		final Attributes array = attributes( baseDir, label, timepoint, name );

		if ( array == null )
			throw new IllegalStateException( "The array '" + name + "' of label '" + label + "', tpId=" + timepoint + " does not exist; define it first (stageArray)." );
		if ( array.columns() != columns )
			throw new IllegalStateException( "The array '" + name + "' of label '" + label + "', tpId=" + timepoint + " has " + array.columns() + " columns, the rows have " + columns + "." );
		if ( values.length != (long) ids.length * columns )
			throw new IllegalArgumentException( ids.length + " ids and " + values.length + " values do not make rows of " + columns + " columns." );

		InterestPointsZarrStore.get( baseDir ).stagedArrays.rows( label, timepoint, name, setup, ids, values );
	}

	/**
	 * Removes an array as a whole, in memory: the readers of this JVM no longer see it, the array of the store (if it has
	 * one) goes when the dataset is saved.
	 *
	 * @return whether there was one
	 */
	public static boolean stageRemove( final URI baseDir, final String label, final int timepoint, final String name )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );

		if ( attributes( baseDir, label, timepoint, name ) == null )
			return false;

		store.stagedArrays.remove( label, timepoint, name, store.exists() && store.arraysOnDisk.attributes( label, timepoint, name ) != null );
		return true;
	}

	/** Forgets what is held in memory for an array (its definition, its rows, its removal): it is again as the store has it. */
	public static void forgetStaged( final URI baseDir, final String label, final int timepoint, final String name )
	{
		InterestPointsZarrStore.get( baseDir ).stagedArrays.forget( label, timepoint, name );
	}

	/**
	 * The points of views were replaced (a detection that overwrites the label, any other new list of points) or removed:
	 * the rows of their setups go from every array of the (label, timepoint), since they describe points that no longer
	 * exist (Stephan, 2026-11-14: a re-detection deletes the old embeddings). In memory at once (the readers of this JVM
	 * no longer see them), in the store when the dataset is saved; rows computed afterwards for these views are kept.
	 *
	 * @param label the label the rows are stored under (a filtered label has none of its own)
	 * @param setups the setups of the views
	 */
	public static void stageRemoveRows( final URI baseDir, final String label, final int timepoint, final Collection< Integer > setups )
	{
		InterestPointsZarrStore.get( baseDir ).stagedArrays.removeRows( new Group( label, timepoint ), setups );
	}

	/**
	 * Removes the rows of some views from ONE array, in memory: the readers of this JVM no longer see them, what the store
	 * holds for these setups (in the array's table, in tables per setup, written directly) goes when the dataset is saved.
	 * The other arrays of the (label, timepoint) keep their rows; an array that is left without rows is removed by the save;
	 * rows computed afterwards for these views are kept.
	 *
	 * @param label the label the rows are stored under (a filtered label has none of its own, see {@link #baseLabel})
	 * @param setups the setups of the views
	 * @return the setups that had rows and lost them, ascending; empty if there is no such array or it has no rows for them
	 */
	public static SortedSet< Integer > stageRemoveRows( final URI baseDir, final String label, final int timepoint, final String name, final Collection< Integer > setups )
	{
		final TreeSet< Integer > removed = new TreeSet<>();
		final Attributes array = attributes( baseDir, label, timepoint, name );

		if ( array == null )
			return removed;

		removed.addAll( rows( baseDir, array ).keySet() );
		removed.retainAll( setups );

		InterestPointsZarrStore.get( baseDir ).stagedArrays.removeRows( label, timepoint, name, removed );

		return removed;
	}

	/**
	 * @return the embeddings a label has in the given views: per array (by name, ascending) those of the views it holds
	 *         rows for, in the order given; a filtered label has its base's; an array without rows for any of the views is
	 *         not named. Whether the rows still fit the points is not looked at (a stale array is one, too). What the
	 *         Interest Point Explorer's "Remove embeddings" offers; one {@link #list} and one {@link #rows} per array of
	 *         every (dataset directory, base label, timepoint) the views name.
	 * @param vip the lists of the dataset
	 * @param views the views, those without the label are skipped
	 * @throws IllegalStateException as {@link #baseLabel} and {@link #list}
	 */
	public static SortedMap< String, List< ViewId > > viewsWithRows( final ViewInterestPoints vip, final Collection< ? extends ViewId > views, final String label )
	{
		// the views by where their rows are, in the order given
		final HashMap< Location, List< ViewId > > byLocation = new HashMap<>();
		final List< Location > locations = new ArrayList<>();

		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists lists = vip.getViewInterestPointLists( viewId );
			if ( lists == null || !lists.contains( label ) )
				continue;

			final Location location = new Location( lists.getInterestPointList( label ).getBasePath(), baseLabel( lists, label ), lists.getTimePointId() );
			if ( !byLocation.containsKey( location ) )
				locations.add( location );
			byLocation.computeIfAbsent( location, l -> new ArrayList<>() ).add( new ViewId( viewId.getTimePointId(), viewId.getViewSetupId() ) );
		}

		final TreeMap< String, List< ViewId > > result = new TreeMap<>();

		for ( final Location location : locations )
			for ( final Attributes array : list( location.baseDir(), location.label(), location.timepoint() ) )
			{
				final SortedMap< Integer, Long > rows = rows( location.baseDir(), array );

				for ( final ViewId viewId : byLocation.get( location ) )
					if ( rows.containsKey( viewId.getViewSetupId() ) )
						result.computeIfAbsent( array.name(), n -> new ArrayList<>() ).add( viewId );
			}

		return result;
	}

	/** @return whether this JVM holds changes of the dataset's arrays that the next save writes */
	public static boolean hasStaged( final URI baseDir )
	{
		return !InterestPointsZarrStore.get( baseDir ).stagedArrays.isEmpty();
	}

	/**
	 * The commit of the arrays of a dataset, as {@link InterestPointsZarrStore#commit()} is the commit of its points: writes
	 * what this JVM holds in memory ({@link StagedInterestPointArrays}: rows computed, arrays removed or replaced, the views
	 * whose points were replaced or removed) and the rows written directly since the last commit ({@link #writeSetup}) into
	 * the arrays' tables. Per array that changed, the next generation is written from the kept rows of the old one and the
	 * new rows, the array is flipped to it under its lock file and the old generation is deleted; an array left without
	 * rows is removed; an array defined without rows is not created ({@link InterestPointArraysOnDisk#commit}). What is
	 * held is forgotten once everything is written; if writing fails it is kept and the next save writes all of it again.
	 *
	 * The save of the dataset calls it after the interest points are committed
	 * ({@code XmlIoSpimData2.saveInterestPointsInParallel}); the commit of the points alone does not ("Resave as ZARR
	 * store" commits points and must not write the embeddings of points that are not saved).
	 */
	public static void commit( final InterestPointsZarrStore store )
	{
		store.arraysOnDisk.commit( store.stagedArrays );
	}

	/** {@link #commit(InterestPointsZarrStore)} for the store of a dataset directory */
	public static void commit( final URI baseDir )
	{
		commit( InterestPointsZarrStore.get( baseDir ) );
	}

	/** @return the row of an id in the sorted keys of {@link #join}, or -1 */
	private static int rowOf( final long[] keys, final int id )
	{
		int low = 0, high = keys.length - 1;
		final long target = (long) id << 32;
		while ( low <= high )
		{
			final int mid = ( low + high ) >>> 1;
			final long key = keys[ mid ] & 0xffffffff00000000L;
			if ( key < target )
				low = mid + 1;
			else if ( key > target )
				high = mid - 1;
			else
				return (int) keys[ mid ];
		}
		return -1;
	}

	private static boolean isNaN( final float[] values, final int from, final int count )
	{
		for ( int i = from; i < from + count; ++i )
			if ( Float.isNaN( values[ i ] ) )
				return true;
		return false;
	}

	private static String where( final Rows rows ) { return "tpId=" + rows.attributes().timepoint() + " setupId=" + rows.setup() + ", label '" + rows.attributes().label() + "'"; }

	private static String view( final ViewInterestPointLists lists ) { return "tpId=" + lists.getTimePointId() + " setupId=" + lists.getViewSetupId(); }
}
