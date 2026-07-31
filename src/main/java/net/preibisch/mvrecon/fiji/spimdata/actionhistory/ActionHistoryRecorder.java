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
package net.preibisch.mvrecon.fiji.spimdata.actionhistory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
		if ( v == null )
			return null;
		final StringBuilder sb = new StringBuilder();
		for ( int i = 0; i < v.length; ++i )
		{
			if ( i > 0 ) sb.append( ',' );
			sb.append( v[ i ] );
		}
		return sb.toString();
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

		final Set<Integer> allAngle = new LinkedHashSet<>(), usedAngle = new LinkedHashSet<>();
		final Set<Integer> allTile = new LinkedHashSet<>(), usedTile = new LinkedHashSet<>();
		final Set<Integer> allIllum = new LinkedHashSet<>(), usedIllum = new LinkedHashSet<>();
		final Set<Integer> allChannel = new LinkedHashSet<>(), usedChannel = new LinkedHashSet<>();
		final Set<Integer> allTP = new LinkedHashSet<>(), usedTP = new LinkedHashSet<>();

		for ( final ViewDescription vd : present )
		{
			final int angle = vd.getViewSetup().getAngle().getId();
			final int tile = vd.getViewSetup().getTile().getId();
			final int illum = vd.getViewSetup().getIllumination().getId();
			final int channel = vd.getViewSetup().getChannel().getId();
			final int tp = vd.getTimePointId();

			allAngle.add( angle );
			allTile.add( tile );
			allIllum.add( illum );
			allChannel.add( channel );
			allTP.add( tp );

			if ( selected.contains( new ViewId( vd.getTimePointId(), vd.getViewSetupId() ) ) )
			{
				usedAngle.add( angle );
				usedTile.add( tile );
				usedIllum.add( illum );
				usedChannel.add( channel );
				usedTP.add( tp );
			}
		}

		// a dimension only needs a filter if it's actually restricted; "all values used" == default
		final Set<Integer> fAngle = usedAngle.equals( allAngle ) ? null : usedAngle;
		final Set<Integer> fTile = usedTile.equals( allTile ) ? null : usedTile;
		final Set<Integer> fIllum = usedIllum.equals( allIllum ) ? null : usedIllum;
		final Set<Integer> fChannel = usedChannel.equals( allChannel ) ? null : usedChannel;
		final Set<Integer> fTP = usedTP.equals( allTP ) ? null : usedTP;

		// verify: does the cross-product of these per-dimension filters reconstruct the selection
		// exactly? (required -- independent per-dimension filters can't express a cross-cutting
		// subset, and silently emitting them anyway would select the wrong views)
		int reconstructedCount = 0;
		boolean exact = true;
		for ( final ViewDescription vd : present )
		{
			final boolean matches =
					( fAngle == null || fAngle.contains( vd.getViewSetup().getAngle().getId() ) )
					&& ( fTile == null || fTile.contains( vd.getViewSetup().getTile().getId() ) )
					&& ( fIllum == null || fIllum.contains( vd.getViewSetup().getIllumination().getId() ) )
					&& ( fChannel == null || fChannel.contains( vd.getViewSetup().getChannel().getId() ) )
					&& ( fTP == null || fTP.contains( vd.getTimePointId() ) );
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
			if ( fAngle != null ) put( params, "angleId", joinIds( fAngle ) );
			if ( fTile != null ) put( params, "tileId", joinIds( fTile ) );
			if ( fIllum != null ) put( params, "illuminationId", joinIds( fIllum ) );
			if ( fChannel != null ) put( params, "channelId", joinIds( fChannel ) );
			if ( fTP != null ) put( params, "timepointId", joinIds( fTP ) );
		}
		else
		{
			put( params, "viewIds", joinViewIds( viewIds ) );
		}
	}

	private static String joinIds( final Collection<Integer> ids )
	{
		final StringBuilder sb = new StringBuilder();
		boolean first = true;
		for ( final int id : ids )
		{
			if ( !first ) sb.append( ',' );
			sb.append( id );
			first = false;
		}
		return sb.toString();
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
