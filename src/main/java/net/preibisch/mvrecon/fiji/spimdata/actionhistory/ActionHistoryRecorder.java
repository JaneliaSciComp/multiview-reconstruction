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
package net.preibisch.mvrecon.fiji.spimdata.actionhistory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;

/**
 * Static helper used by GUI plugins to record a successful command into the
 * dataset's {@link ActionHistory}.
 *
 * <p>All calls are best-effort: a null dataset, null history, or any thrown
 * exception is swallowed so that recording can never break the GUI command
 * that triggered it.</p>
 */
public final class ActionHistoryRecorder
{
	private ActionHistoryRecorder() {}

	/**
	 * Record an action. Any field may be null/empty except {@code actionId}.
	 *
	 * @param data         dataset to attach the record to
	 * @param actionId     stable identifier matching the translator registry, e.g. "register-interestpoints"
	 * @param mvreconClass the FQN of the originating mvrecon plugin class
	 * @param params       parameter key/value pairs (use a LinkedHashMap to preserve order)
	 * @param affectedViews views the action operated on (may be null)
	 * @param resultRef    pointer used by data-tied removal (see ActionHistory.removeByResultRef)
	 */
	public static void record(
			final SpimData2 data,
			final String actionId,
			final String mvreconClass,
			final Map<String,String> params,
			final List<? extends ViewId> affectedViews,
			final String resultRef )
	{
		try
		{
			if ( data == null )
				return;
			final ActionHistory history = data.getActionHistory();
			if ( history == null )
				return;
			history.add( new ActionRecord(
					actionId,
					System.currentTimeMillis(),
					mvreconClass,
					params,
					affectedViews,
					resultRef ) );
		}
		catch ( final Throwable t )
		{
			// never let history recording break a plugin
			System.err.println( "ActionHistoryRecorder: failed to record '" + actionId + "': " + t );
		}
	}

	/** Convenience: start a new ordered param map. */
	public static LinkedHashMap<String,String> params() { return new LinkedHashMap<>(); }

	/** Format an int vector as a bare "x,y,z" string, as the BigStitcher-Spark CLI expects (no brackets). */
	public static String csv( final int[] v )
	{
		return v == null ? null : Arrays.stream( v ).mapToObj( String::valueOf ).collect( Collectors.joining( "," ) );
	}

	/** Convenience: put non-null value (skip nulls). */
	public static void put( final LinkedHashMap<String,String> map, final String key, final Object value )
	{
		if ( map == null || key == null || value == null )
			return;
		map.put( key, value.toString() );
	}

	/**
	 * Merge a sub-component's {@code describeParameters()} output into the action's param map,
	 * skipping null values. Used by callers that pull exporter/matcher-specific params in on top of
	 * their own (e.g. Image_Fusion + its ImgExport, Interest_Point_Registration + its PairwiseGUI).
	 */
	public static void merge( final Map<String,String> dest, final Map<String,String> src )
	{
		if ( dest == null || src == null )
			return;
		for ( final Map.Entry<String,String> e : src.entrySet() )
			if ( e.getValue() != null )
				dest.put( e.getKey(), e.getValue() );
	}

	/**
	 * Best-effort variant of {@link #merge(Map, Map)}: calls {@code src} and merges its result,
	 * swallowing any exception the sub-component's {@code describeParameters()} throws (mirrors the
	 * best-effort contract of {@link #record}). Note: unlike {@link #merge(Map, Map)}, {@code src} is
	 * a supplier so the call itself (not just the merge) is covered by the try/catch.
	 */
	public static void mergeSafe( final Map<String,String> dest, final java.util.function.Supplier<? extends Map<String,String>> src )
	{
		try
		{
			merge( dest, src.get() );
		}
		catch ( final Throwable t )
		{
			System.err.println( "ActionHistoryRecorder: failed to merge params: " + t );
		}
	}

	/** "tp,vs" — the ViewId format every Spark CLI view-selection flag (-vi, -fv) expects. */
	public static String formatViewId( final ViewId v )
	{
		return v.getTimePointId() + "," + v.getViewSetupId();
	}

	/** {@value #MULTI_VALUE_DELIM}-delimited "tp,vs" pairs, for repeatKeys-expanded flags (-vi, -fv). */
	public static String joinViewIds( final Collection<? extends ViewId> views )
	{
		final List<String> parts = new ArrayList<>();
		for ( final ViewId v : views )
			parts.add( formatViewId( v ) );
		return String.join( ActionToSparkCli.MULTI_VALUE_DELIM, parts );
	}

	/**
	 * Describe a view selection as the most parsimonious BigStitcher-Spark view-selection flags.
	 *
	 * <p>BigStitcher-Spark's {@code AbstractSelectableViews} takes independent per-dimension id
	 * lists ({@code --angleId}, {@code --tileId}, {@code --illuminationId}, {@code --channelId},
	 * {@code --timepointId}) plus an explicit {@code -vi 'tp,vs'} fallback. If the selected views
	 * are exactly the cross-product of a handful of per-dimension ids (e.g. "illumination 0 AND
	 * channel 1, all tiles/angles/timepoints"), that is far more readable — and far more robust to
	 * re-running against a slightly different dataset — than spelling out every view id, so this
	 * reconstructs and prefers that form. Only falls back to the explicit {@code viewIds} list when
	 * the selection is a genuine cross-cutting subset (e.g. "tile 0 for channel 0, tile 1 for
	 * channel 1") that no combination of independent per-dimension filters can reproduce exactly —
	 * emitting the per-dimension filters in that case would silently over- or under-select views.
	 */
	public static void putViewSelection(
			final LinkedHashMap<String,String> params,
			final SpimData2 data,
			final Collection<? extends ViewId> viewIds )
	{
		if ( params == null || data == null || viewIds == null || viewIds.isEmpty() )
			return;

		final List<ViewDescription> present = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() )
				present.add( vd );

		final Set<ViewId> selected = new HashSet<>( viewIds );

		// fast path: everything present was selected (the common "process all loaded views" case) --
		// no filter is needed at all, and skips the per-dimension pass + reconstruction-verify pass
		// below entirely. Matters on datasets with tens of thousands of tiles.
		if ( selected.size() == present.size() )
			return;

		final int nDims = DIM_KEYS.length;
		final List<Set<Integer>> all = new ArrayList<>( nDims );
		final List<Set<Integer>> used = new ArrayList<>( nDims );
		for ( int d = 0; d < nDims; ++d )
		{
			all.add( new LinkedHashSet<>() );
			used.add( new LinkedHashSet<>() );
		}

		for ( final ViewDescription vd : present )
		{
			final boolean isSelected = selected.contains( new ViewId( vd.getTimePointId(), vd.getViewSetupId() ) );
			for ( int d = 0; d < nDims; ++d )
			{
				final int v = DIM_EXTRACTORS[ d ].get( vd );
				all.get( d ).add( v );
				if ( isSelected )
					used.get( d ).add( v );
			}
		}

        // filter = set(all dims that are restircted); "all values used" == default
		final List<Set<Integer>> filter = new ArrayList<>( nDims );
		for ( int d = 0; d < nDims; ++d )
			filter.add( used.get( d ).equals( all.get( d ) ) ? null : used.get( d ) );

		// verify: does the cross-product of these per-dimension filters reconstruct the selection
		// exactly? (required -- independent per-dimension filters can't express a cross-cutting
		// subset, and silently emitting them anyway would select the wrong views)
		int reconstructedCount = 0;
		boolean exact = true;
		for ( final ViewDescription vd : present )
		{
			boolean matches = true;
			for ( int d = 0; d < nDims; ++d )
			{
				final Set<Integer> f = filter.get( d );
				if ( f != null && !f.contains( DIM_EXTRACTORS[ d ].get( vd ) ) )
				{
					matches = false;
					break;
				}
			}
			final boolean isSelected = selected.contains( new ViewId( vd.getTimePointId(), vd.getViewSetupId() ) );
			if ( matches != isSelected )
			{
				exact = false;
				break;
			}
			if ( matches )
				++reconstructedCount;
		}
		if ( exact && reconstructedCount != selected.size() )
			exact = false; // e.g. a selected ViewId that isn't present in the dataset at all

		if ( exact )
		{
			for ( int d = 0; d < nDims; ++d )
				if ( filter.get( d ) != null )
					put( params, DIM_KEYS[ d ], joinIds( filter.get( d ) ) );
		}
		else
		{
			put( params, "viewIds", joinViewIds( viewIds ) );
		}
	}

	/** Per-dimension {@code ViewDescription} accessor, paired with {@link #DIM_KEYS} by index. */
	@FunctionalInterface
	private interface DimExtractor
	{
		int get( ViewDescription vd );
	}

	// order defines the order params are emitted in putViewSelection()
	private static final String[] DIM_KEYS = { "angleId", "tileId", "illuminationId", "channelId", "timepointId" };
	private static final DimExtractor[] DIM_EXTRACTORS = {
			vd -> vd.getViewSetup().getAngle().getId(),
			vd -> vd.getViewSetup().getTile().getId(),
			vd -> vd.getViewSetup().getIllumination().getId(),
			vd -> vd.getViewSetup().getChannel().getId(),
			vd -> vd.getTimePointId()
	};

	private static String joinIds( final Collection<Integer> ids )
	{
		return ids.stream().map( String::valueOf ).collect( Collectors.joining( "," ) );
	}

	/**
	 * Map an N5 {@code Compression} object to the name BigStitcher-Spark's {@code Compressions} enum
	 * expects (Lz4, Gzip, Zstandard, Blosc, Bzip2, Xz, Raw). The N5 implementation classes are named
	 * {@code <Name>Compression} (e.g. {@code ZstandardCompression}), so we strip the trailing
	 * "Compression" from the simple class name. Returns null for null input.
	 */
	public static String sparkCompression( final Object compression )
	{
		if ( compression == null )
			return null;
		final String simple = compression.getClass().getSimpleName();
		return simple.endsWith( "Compression" ) && simple.length() > "Compression".length()
				? simple.substring( 0, simple.length() - "Compression".length() )
				: simple;
	}
}
