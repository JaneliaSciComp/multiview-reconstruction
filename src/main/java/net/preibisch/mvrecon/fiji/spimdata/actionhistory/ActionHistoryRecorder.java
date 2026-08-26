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
	 * Whether new actions are recorded (set from the "Enable_action_history" checkbox in Data_Explorer's
	 * advanced-options dialog; on by default). Existing history already stored in the XML is always
	 * loaded/shown regardless of this flag -- it only gates {@link #record}.
	 */
	public static boolean enabled = true;

	/**
	 * Record an action. Any field may be null/empty except {@code actionId}. No-op if {@link #enabled}
	 * is false.
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
		if ( !enabled )
			return;
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

	/** {@value ActionToSparkCli#MULTI_VALUE_DELIM}-delimited "tp,vs" pairs, for repeatKeys-expanded flags (-vi, -fv). */
	public static String joinViewIds( final Collection<? extends ViewId> views )
	{
		final List<String> parts = new ArrayList<>();
		for ( final ViewId v : views )
			parts.add( formatViewId( v ) );
		return String.join( ActionToSparkCli.MULTI_VALUE_DELIM, parts );
	}

	/**
	 * Describe a view selection as the most parsimonious BigStitcher-Spark view-selection flags,
	 * trying progressively more expensive/general tiers until one reproduces the selection exactly:
	 *
	 * <ol>
	 * <li>everything present was selected -- nothing to store</li>
	 * <li><b>the basis check</b>: do independent per-dimension id sets ({@code angleId}/{@code tileId}/
	 *     {@code illuminationId}/{@code channelId}/{@code timepointId}) reconstruct the selection via
	 *     their cross-product? (e.g. "illumination 0 AND channel 1, all tiles/angles/timepoints") --
	 *     the minimal such filter, and provably the most permissive one possible: if <em>any</em>
	 *     per-dimension filter over these same 5 dimensions could reconstruct the selection, this
	 *     minimal one does too (a wider filter can only match more views on some dimension, and every
	 *     selected view already sits inside the minimal filter's bounds on every dimension)</li>
	 * <li>the view-setup-id compaction: group the recorded views by timepoint and take the union of
	 *     view-setup ids used across all of them. If {@code {union} x {used timepoints}} reconstructs
	 *     the selection exactly (i.e. every used timepoint used that same union -- trivially true, a
	 *     no-op check, when there's only one used timepoint), store that one compact view-setup-id
	 *     encoding (see {@link #putViewSetupIdCompaction}) alongside the timepoint list. Otherwise
	 *     store each timepoint with its own compact view-setup-id list -- always exact by
	 *     construction, since each one is derived directly from what was actually selected at that
	 *     timepoint, so no further "explicit id list" fallback is needed below this tier.</li>
	 * </ol>
	 *
	 * Storing per-dimension ids/a compact view-setup encoding instead of spelling out every "tp,vs"
	 * pair is also far more robust to re-running against a slightly different dataset, and is what
	 * {@code ActionToSparkCli} expects for {@code --angleId} etc. and {@code -vi}.
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

		final int n = present.size();
		final int nDims = DIM_KEYS.length;
		final List<Set<Integer>> all = new ArrayList<>( nDims );
		final List<Set<Integer>> used = new ArrayList<>( nDims );
		for ( int d = 0; d < nDims; ++d )
		{
			all.add( new LinkedHashSet<>() );
			used.add( new LinkedHashSet<>() );
		}

		// Cache each view's per-dimension values + selected flag once here (flat n*nDims array, one
		// allocation) so the reconstruction-verify pass below never re-derives them -- avoids a second
		// round of ViewSetup getter-chain calls and HashSet lookups per view. Matters at 100k+ tiles.
		final int[] vals = new int[ n * nDims ];
		final boolean[] isSelectedArr = new boolean[ n ];
		for ( int i = 0; i < n; ++i )
		{
			// ViewDescription extends ViewId (same equals/hashCode, keyed on timepoint+setup), so it
			// probes the set directly -- no need to allocate a ViewId just to look itself up.
			final ViewDescription vd = present.get( i );
			final boolean isSelected = selected.contains( vd );
			isSelectedArr[ i ] = isSelected;
			final int base = i * nDims;
			for ( int d = 0; d < nDims; ++d )
			{
				final int v = DIM_EXTRACTORS[ d ].get( vd );
				vals[ base + d ] = v;
				all.get( d ).add( v );
				if ( isSelected )
					used.get( d ).add( v );
			}
		}

		// the basis check: filter = set(all dims that are restricted); "all values used" == default
		final List<Set<Integer>> filter = new ArrayList<>( nDims );
		for ( int d = 0; d < nDims; ++d )
			filter.add( normalizeFilter( used.get( d ), all.get( d ) ) );

		if ( reconstructsExactly( vals, isSelectedArr, n, nDims, filter, selected.size() ) )
		{
			emitDimFilter( params, filter );
			return;
		}

		putViewSetupCompaction( params, viewIds );
	}

	/** Stores each non-null per-dimension id set in {@code filter} under its {@link #DIM_KEYS} name. */
	private static void emitDimFilter( final LinkedHashMap<String,String> params, final List<Set<Integer>> filter )
	{
		for ( int d = 0; d < filter.size(); ++d )
			if ( filter.get( d ) != null )
				put( params, DIM_KEYS[ d ], joinIds( filter.get( d ) ) );
	}

	/** {@code restricted} (already {@code null} == unrestricted), collapsed to {@code null} if it covers every present value -- same convention everywhere a per-dimension filter is built. */
	private static Set<Integer> normalizeFilter( final Set<Integer> restricted, final Set<Integer> all )
	{
		return restricted == null || restricted.equals( all ) ? null : restricted;
	}

	/**
	 * View-setup-id compaction: the basis check above requires angle/tile/illumination/channel to
	 * each independently factor out. This is more permissive -- it groups the recorded views by
	 * timepoint (directly off {@code viewIds}, not the dataset's {@code present} views -- unlike the
	 * tiers above, this one can't over/under-select, so it doesn't need to be checked against the
	 * rest of the dataset) and takes the union of view-setup ids used across all of them.
	 */
	// package-private (not private) so ActionHistoryViewSetupCompactionTest can exercise it directly
	// with plain ViewIds, without needing a real dataset.
	static void putViewSetupCompaction( final LinkedHashMap<String,String> params, final Collection<? extends ViewId> viewIds )
	{
		final Map<Integer,Set<Integer>> setupIdsByTimepoint = new LinkedHashMap<>();
		final Set<Integer> unionSetupIds = new LinkedHashSet<>();
		for ( final ViewId v : viewIds )
		{
			setupIdsByTimepoint.computeIfAbsent( v.getTimePointId(), tp -> new LinkedHashSet<>() ).add( v.getViewSetupId() );
			unionSetupIds.add( v.getViewSetupId() );
		}

		// does {union} x {used timepoints} reconstruct the selection, i.e. did every used timepoint
		// use that same union? With only one used timepoint there's nothing to compare against, so
		// this is trivially true -- a no-op check, not a special case.
		boolean sameSetupIdsEveryTimepoint = true;
		for ( final Set<Integer> setupIds : setupIdsByTimepoint.values() )
		{
			if ( !setupIds.equals( unionSetupIds ) )
			{
				sameSetupIdsEveryTimepoint = false;
				break;
			}
		}

		// always record the concrete timepoint set (even if it's every present timepoint) so
		// ActionToSparkCli can cross/pair it with the compacted viewSetupId set(s) below and
		// reconstruct explicit "-vi" pairs without needing the live dataset -- translation is
		// deliberately kept a pure function of the stored params (see
		// ActionToSparkCli.expandViewSetupCompaction).
		put( params, "timepointId", joinIds( setupIdsByTimepoint.keySet() ) );

		if ( sameSetupIdsEveryTimepoint )
		{
			putViewSetupIdCompaction( params, "viewSetupId", unionSetupIds );
		}
		else
		{
			// no single view-setup-id set applies to every timepoint -- compact each timepoint's own
			// set separately (namespaced by timepoint), which by construction always reproduces the
			// selection exactly, so there's no explicit-id-list fallback left to fall back to.
			for ( final Map.Entry<Integer,Set<Integer>> e : setupIdsByTimepoint.entrySet() )
				putViewSetupIdCompaction( params, "viewSetupId@" + e.getKey(), e.getValue() );
		}
	}

	/**
	 * True iff, for every present view, "matches every non-null per-dimension filter" agrees with
	 * {@code isSelectedArr} -- i.e. the cross-product of {@code filter} reproduces the selection
	 * exactly. Used by the basis check in {@link #putViewSelection}.
	 */
	// package-private (not private) so ActionHistoryBasisCheckTest can exercise it directly
	static boolean reconstructsExactly(
			final int[] vals,
			final boolean[] isSelectedArr,
			final int n,
			final int nDims,
			final List<Set<Integer>> filter,
			final int selectedCount )
	{
		int reconstructedCount = 0;
		for ( int i = 0; i < n; ++i )
		{
			final int base = i * nDims;
			boolean matches = true;
			for ( int d = 0; d < nDims; ++d )
			{
				final Set<Integer> f = filter.get( d );
				if ( f != null && !f.contains( vals[ base + d ] ) )
				{
					matches = false;
					break;
				}
			}
			if ( matches != isSelectedArr[ i ] )
				return false;
			if ( matches )
				++reconstructedCount;
		}
		// e.g. a selected ViewId that isn't present in the dataset at all
		return reconstructedCount == selectedCount;
	}

	/**
	 * Compact encoding of an id set for the view-setup-id-compaction tier above, keyed under
	 * {@code prefix} (e.g. {@code "viewSetupId"} for the single-set-covers-every-timepoint case, or
	 * {@code "viewSetupId@<tp>"} for that one timepoint's own set): a contiguous range, else a
	 * one-hot bitset over [min,max] (so e.g. only ids 999997/999999 out of a million cost a
	 * 3-character bitset starting at 999997), else a plain id list. {@code ActionToSparkCli}
	 * ({@code decodeIdCompaction}) knows how to reverse all three forms.
	 */
	// package-private (not private) so ActionHistoryViewSetupCompactionTest can round-trip it against
	// ActionToSparkCli.decodeIdCompaction without duplicating this codec in test code.
	static void putViewSetupIdCompaction( final LinkedHashMap<String,String> params, final String prefix, final Set<Integer> ids )
	{
		final int[] sorted = ids.stream().mapToInt( Integer::intValue ).toArray();
		Arrays.sort( sorted );
		final int min = sorted[ 0 ];
		final int max = sorted[ sorted.length - 1 ];

		if ( max - min + 1 == sorted.length )
		{
			put( params, prefix + "RangeStart", min );
			put( params, prefix + "RangeEnd", max );
			return;
		}

		final int span = max - min + 1;
		// ponytail: 8x is a rough heuristic (a bitset char costs ~1 byte, an explicit id costs
		// several bytes with its delimiter) -- tune if real datasets show it's off, or if two
		// far-apart ids with nothing selected in between make the bitset the bigger of the two.
		if ( span <= sorted.length * 8 )
		{
			final StringBuilder bits = new StringBuilder( span );
			int next = 0;
			for ( int v = min; v <= max; ++v )
			{
				if ( next < sorted.length && sorted[ next ] == v )
				{
					bits.append( '1' );
					++next;
				}
				else
				{
					bits.append( '0' );
				}
			}
			put( params, prefix + "BitsetStart", min );
			put( params, prefix + "BitsetBits", bits.toString() );
			return;
		}

		put( params, prefix + "s", joinIds( ids ) );
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
