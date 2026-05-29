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

import java.util.LinkedHashMap;
import java.util.List;

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
			final java.util.Map<String,String> params,
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
