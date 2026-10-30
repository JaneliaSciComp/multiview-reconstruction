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
import java.util.List;
import java.util.Map;

import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Group;
import util.URITools;

/**
 * Per-point arrays of the interest point store: rows that another writer keeps about the store's points (pcmatch's
 * {@code embed} writes the learned descriptor of every point), keyed by point id and independent of the generations.
 * Java only reads them; a writer puts them next to the generations of a (label, timepoint):
 *
 * <pre>
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/zarr.json                attributes: columns D, dtype "float32", meta (the writer's JSON, verbatim)
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/setup&lt;S&gt;/id           int32   [N, 1]  the ids of the points the rows belong to
 * &lt;enc label&gt;/tp&lt;T&gt;/arrays/&lt;name&gt;/setup&lt;S&gt;/values       float32 [N, D]  the rows, in no particular order
 * </pre>
 *
 * A filtered label has no rows of its own, the rows of its base label apply (same ids). An array describes the label as it
 * is: as many rows as the label has points in the view (the base label's points, for a filtered label) and a row for every
 * point. A detection replaces the points (another number of them, other ids), so an array with another number of rows or
 * without a row for a point is stale for the view and the view fails ("run pcmatch embed again") instead of being matched
 * on the vectors of other points; no stored positions are compared (Stephan, 2026-10-29). A row of NaN = no vector. Other
 * tables a writer keeps in {@code setup<S>} are ignored (pcmatch wrote a float32 {@code loc} table until 2026-10-29).
 *
 * The arrays are not part of a generation: a commit of the (label, timepoint) leaves them where they are, removing the label
 * removes them with its timepoint group. Specification: {@code SCALESPACE_TASK_arrays.md} (pcmatch, 2026-10-28).
 *
 * @author Stephan Preibisch
 */
public class InterestPointArrays
{
	public static final String ARRAYS = "arrays";
	public static final String SETUP_PREFIX = "setup";
	public static final String ID = "id";
	public static final String VALUES = "values";

	static final String ATTR_COLUMNS = "columns";
	static final String ATTR_DTYPE = "dtype";
	static final String ATTR_META = "meta";

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
	 *         store, no group); the label is the one the rows were written under (the base of a filtered label)
	 */
	public static Attributes attributes( final URI baseDir, final String label, final int timepoint, final String name )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		if ( !store.exists() )
			return null;

		try ( final N5Reader reader = URITools.instantiateN5Reader( StorageFormat.ZARR, store.getContainerURI() ) )
		{
			return attributes( reader, store.getContainerURI(), label, timepoint, name );
		}
	}

	private static Attributes attributes( final N5Reader reader, final URI container, final String label, final int timepoint, final String name )
	{
		final String path = Attributes.path( label, timepoint, name );
		if ( !reader.exists( path ) )
			return null;

		final Integer columns = reader.getAttribute( path, ATTR_COLUMNS, Integer.class );
		final String dtype = reader.getAttribute( path, ATTR_DTYPE, String.class );
		final JsonObject meta = reader.getAttribute( path, ATTR_META, JsonObject.class );

		if ( columns == null || columns < 1 || dtype == null )
			throw new IllegalStateException( "'" + path + "' of '" + container + "' is no per-point array (it needs the attributes '" + ATTR_COLUMNS + "' and '" + ATTR_DTYPE + "'), stopping." );
		if ( !dtype.equals( "float32" ) )
			throw new IllegalStateException( "'" + path + "' of '" + container + "' has dtype '" + dtype + "', this reader supports float32, stopping." );

		return new Attributes( label, timepoint, name, columns, dtype, meta == null ? new JsonObject() : meta );
	}

	/**
	 * Reads one setup's rows of an array as stored (section 3 of the specification joins them to the points, see {@link #join}).
	 *
	 * @param baseDir the dataset directory (the store is {@code interestpoints.zarr} in it)
	 * @param label the label the rows were written under (the base of a filtered label, see {@link #baseLabel})
	 * @param timepoint the timepoint of the view
	 * @param setup the setup of the view
	 * @param name the name of the array (pcmatch's default is {@code descriptor})
	 * @return the rows, or null if there is no such array or no rows for the setup (a {@code setup<S>} directory without
	 *         a complete {@code values} is a write in progress and counts as none)
	 * @throws IllegalStateException if the array is damaged (tables of different lengths, another type or chunking than specified)
	 */
	public static Rows read( final URI baseDir, final String label, final int timepoint, final int setup, final String name )
	{
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseDir );
		if ( !store.exists() )
			return null;

		final URI container = store.getContainerURI();

		// a fresh reader every time: the writer replaces a setup's tables in place (under a temporary name, then swapped),
		// so cached metadata or chunks of an earlier read must not survive into this one
		try ( final N5Reader reader = URITools.instantiateN5Reader( StorageFormat.ZARR, container ) )
		{
			final Attributes attributes = attributes( reader, container, label, timepoint, name );
			if ( attributes == null )
				return null;

			final String path = attributes.path() + "/" + SETUP_PREFIX + setup;
			if ( !reader.datasetExists( path + "/" + VALUES ) )
				return null;

			final DatasetAttributes idAttrs = reader.getDatasetAttributes( path + "/" + ID );
			final long rows = idAttrs.getDimensions()[ 1 ];

			final int[] ids = readTable( reader, container, path + "/" + ID, 1, rows, DataType.INT32 );
			final float[] values = readTable( reader, container, path + "/" + VALUES, attributes.columns(), rows, DataType.FLOAT32 );

			return new Rows( attributes, setup, ids, values );
		}
	}

	/** Reads a whole [columns, rows] table (rows of {@code columns} values, columns fastest) chunk by chunk through the shard index. */
	@SuppressWarnings( "unchecked" )
	private static < T > T readTable( final N5Reader reader, final URI container, final String dataset, final int columns, final long rows, final DataType type )
	{
		final DatasetAttributes attributes = reader.getDatasetAttributes( dataset );
		final long[] dimensions = attributes.getDimensions();

		if ( dimensions.length != 2 || dimensions[ 0 ] != columns || dimensions[ 1 ] != rows )
			throw new IllegalStateException( "'" + dataset + "' of '" + container + "' is " + Arrays.toString( dimensions ) + ", expected [" + columns + ", " + rows + "] (columns, rows), stopping." );
		if ( attributes.getDataType() != type )
			throw new IllegalStateException( "'" + dataset + "' of '" + container + "' is " + attributes.getDataType() + ", expected " + type + ", stopping." );
		if ( rows * columns > Integer.MAX_VALUE - 8 )
			throw new IllegalStateException( "'" + dataset + "' of '" + container + "' has " + rows + " rows of " + columns + " values, more than one view can hold in memory, stopping." );

		final int chunkRows = attributes.getChunkSize()[ 1 ];
		if ( attributes.getChunkSize()[ 0 ] != columns )
			throw new IllegalStateException( "'" + dataset + "' of '" + container + "' is chunked along the columns, this reader expects whole rows per chunk, stopping." );

		final T data = (T) ( type == DataType.INT32 ? new int[ (int) ( rows * columns ) ] : new float[ (int) ( rows * columns ) ] );

		for ( long chunkIndex = 0; chunkIndex * chunkRows < rows; ++chunkIndex )
		{
			final DataBlock< ? > block = reader.readChunk( dataset, attributes, 0, chunkIndex );
			if ( block == null )
				throw new IllegalStateException( "chunk " + chunkIndex + " of '" + dataset + "' of '" + container + "' is missing, stopping." );

			final long chunkStart = chunkIndex * chunkRows;
			final int count = (int) Math.min( chunkRows, rows - chunkStart );
			if ( java.lang.reflect.Array.getLength( block.getData() ) < count * columns )
				throw new IllegalStateException( "chunk " + chunkIndex + " of '" + dataset + "' of '" + container + "' holds fewer values than the array's shape says, stopping." );

			System.arraycopy( block.getData(), 0, data, (int) ( chunkStart * columns ), count * columns );
		}

		return data;
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
					+ " points: the label was detected again since the rows were written (or the writer did not finish); run pcmatch embed again for label '" + rows.attributes().label() + "'." );

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
