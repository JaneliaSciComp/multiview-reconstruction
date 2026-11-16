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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonObject;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Attributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Group;

/**
 * The per-point arrays (embeddings) of a dataset that this JVM changed and has not written yet, one instance per
 * {@link InterestPointsZarrStore}: like the interest points themselves, embeddings computed in the explorer and the removal
 * of embeddings whose points were replaced stay in memory until the dataset is saved (Stephan, 2026-11-14), and are gone
 * if it is not. Three kinds of changes:
 * <ul>
 * <li>an array defined here (created, kept for the same model, or replacing another model's array of that name) with the
 * rows computed for some setups;</li>
 * <li>an array removed as a whole;</li>
 * <li>per (label, timepoint) the setups whose rows go from every array of the group: the points of these views were
 * replaced (a detection that overwrites the label) or removed, so the rows describe points that no longer exist. Rows
 * computed afterwards for such a setup are kept.</li>
 * </ul>
 * The readers of {@link InterestPointArrays} lay this over what the store holds, so that the explorer, the registration
 * dialog and the matcher see the embeddings as they will be after the save; {@link InterestPointArrays#commit} writes it
 * into the arrays' tables ({@link InterestPointArraysOnDisk}), together with the rows that were written directly since
 * the last commit ({@link InterestPointArrays#writeSetup}: the tasks of BigStitcher-Spark, which never pass through here).
 *
 * @author Stephan Preibisch
 */
final class StagedInterestPointArrays
{
	record ArrayKey( String label, int timepoint, String name ) implements Comparable< ArrayKey >
	{
		Group group() { return new Group( label, timepoint ); }

		@Override
		public int compareTo( final ArrayKey o )
		{
			int c = label.compareTo( o.label );
			if ( c == 0 )
				c = Integer.compare( timepoint, o.timepoint );
			return c != 0 ? c : name.compareTo( o.name );
		}
	}

	static final class StagedArray
	{
		/** the array as defined here; columns &lt; 1 = not defined here (the array on disk as it is) */
		int columns = -1;
		JsonObject meta = null;

		/** what the store holds under the name is another model's and goes as a whole before the array is written */
		boolean replace = false;

		/** the array is removed as a whole */
		boolean removed = false;

		/** the rows computed here, by setup */
		final TreeMap< Integer, int[] > ids = new TreeMap<>();
		final TreeMap< Integer, float[] > values = new TreeMap<>();
	}

	final TreeMap< ArrayKey, StagedArray > arrays = new TreeMap<>();

	/** per (label, timepoint) the setups whose rows on disk go from every array of the group */
	final HashMap< Group, TreeSet< Integer > > removedSetups = new HashMap<>();

	synchronized boolean isEmpty() { return arrays.isEmpty() && removedSetups.isEmpty(); }

	synchronized void clear()
	{
		arrays.clear();
		removedSetups.clear();
	}

	// ------------------------------------------------------------------------------------------------
	// what the readers see
	// ------------------------------------------------------------------------------------------------

	/** @return the attributes of an array defined here, null if it is not (removed here, or as the store has it) */
	synchronized Attributes attributes( final String label, final int timepoint, final String name )
	{
		final StagedArray array = arrays.get( new ArrayKey( label, timepoint, name ) );

		return array == null || array.removed || array.columns < 1 ? null : new Attributes( label, timepoint, name, array.columns, "float32", array.meta );
	}

	/** @return whether the array of the store (if it has one) is still the array: not removed here and not replaced by another model's */
	synchronized boolean storeArrayCounts( final String label, final int timepoint, final String name )
	{
		final StagedArray array = arrays.get( new ArrayKey( label, timepoint, name ) );

		return array == null || !( array.removed || array.replace );
	}

	/** @return whether the rows the store holds for a setup are still its rows: the array counts and the points of the view were not replaced */
	synchronized boolean storeRowsCount( final String label, final int timepoint, final String name, final int setup )
	{
		final TreeSet< Integer > removed = removedSetups.get( new Group( label, timepoint ) );

		return storeArrayCounts( label, timepoint, name ) && ( removed == null || !removed.contains( setup ) );
	}

	/** @return the arrays of (label, timepoint) as they will be after the save: those of the store without the removed ones, those defined here with their attributes; by name */
	synchronized List< Attributes > list( final String label, final int timepoint, final List< Attributes > inStore )
	{
		final TreeMap< String, Attributes > byName = new TreeMap<>();

		for ( final Attributes array : inStore )
			if ( storeArrayCounts( label, timepoint, array.name() ) )
				byName.put( array.name(), array );

		for ( final Map.Entry< ArrayKey, StagedArray > e : arrays.entrySet() )
			if ( e.getKey().label().equals( label ) && e.getKey().timepoint() == timepoint && !e.getValue().removed && e.getValue().columns > 0 )
				byName.put( e.getKey().name(), new Attributes( label, timepoint, e.getKey().name(), e.getValue().columns, "float32", e.getValue().meta ) );

		return new ArrayList<>( byName.values() );
	}

	/** @return the rows per setup as they will be after the save: those of the store that still count, and those computed here */
	synchronized SortedMap< Integer, Long > rows( final Attributes array, final SortedMap< Integer, Long > inStore )
	{
		final TreeMap< Integer, Long > rows = new TreeMap<>();

		for ( final Map.Entry< Integer, Long > e : inStore.entrySet() )
			if ( storeRowsCount( array.label(), array.timepoint(), array.name(), e.getKey() ) )
				rows.put( e.getKey(), e.getValue() );

		final StagedArray staged = arrays.get( new ArrayKey( array.label(), array.timepoint(), array.name() ) );

		if ( staged != null && !staged.removed )
			for ( final Map.Entry< Integer, int[] > e : staged.ids.entrySet() )
				rows.put( e.getKey(), (long) e.getValue().length );

		return rows;
	}

	/** @return whether rows were computed here for the array */
	synchronized boolean hasRows( final String label, final int timepoint, final String name )
	{
		final StagedArray array = arrays.get( new ArrayKey( label, timepoint, name ) );

		return array != null && !array.removed && !array.ids.isEmpty();
	}

	/** @return the ids of the rows computed here for a setup, null if there are none */
	synchronized int[] ids( final String label, final int timepoint, final String name, final int setup )
	{
		final StagedArray array = arrays.get( new ArrayKey( label, timepoint, name ) );

		return array == null || array.removed ? null : array.ids.get( setup );
	}

	/** @return the values of the rows computed here for a setup, null if there are none */
	synchronized float[] values( final String label, final int timepoint, final String name, final int setup )
	{
		final StagedArray array = arrays.get( new ArrayKey( label, timepoint, name ) );

		return array == null || array.removed ? null : array.values.get( setup );
	}

	// ------------------------------------------------------------------------------------------------
	// changes
	// ------------------------------------------------------------------------------------------------

	/**
	 * Defines an array: it will exist with these attributes after the save.
	 *
	 * @param replace whether an array of that name (in the store or defined here before) is another model's: its rows go
	 * @return whether nothing was held here for the array before (so that a caller that fails can take its definition back, {@link #forget})
	 */
	synchronized boolean define( final String label, final int timepoint, final String name, final int columns, final JsonObject meta, final boolean replace )
	{
		final ArrayKey key = new ArrayKey( label, timepoint, name );
		final boolean fresh = !arrays.containsKey( key );
		final StagedArray array = arrays.computeIfAbsent( key, k -> new StagedArray() );

		// an array removed here before is written anew: what the store holds under the name still goes first
		if ( array.removed || replace )
		{
			array.replace = true;
			array.ids.clear();
			array.values.clear();
		}

		array.removed = false;
		array.columns = columns;
		array.meta = meta;

		return fresh;
	}

	/** Keeps the rows of a setup, replacing those kept before; the array has to be defined here or exist in the store (the caller checks). */
	synchronized void rows( final String label, final int timepoint, final String name, final int setup, final int[] ids, final float[] values )
	{
		final StagedArray array = arrays.computeIfAbsent( new ArrayKey( label, timepoint, name ), k -> new StagedArray() );

		if ( array.removed )
			throw new IllegalStateException( "The array '" + name + "' of label '" + label + "', tpId=" + timepoint + " was removed, define it again before rows are added." );

		array.ids.put( setup, ids );
		array.values.put( setup, values );
	}

	/** Removes an array as a whole: what is held here for it, and (with the save) the array of the store if it has one. */
	synchronized void remove( final String label, final int timepoint, final String name, final boolean inStore )
	{
		final ArrayKey key = new ArrayKey( label, timepoint, name );

		if ( !inStore )
		{
			arrays.remove( key );
			return;
		}

		final StagedArray array = arrays.computeIfAbsent( key, k -> new StagedArray() );
		array.removed = true;
		array.replace = false;
		array.columns = -1;
		array.meta = null;
		array.ids.clear();
		array.values.clear();
	}

	/** Forgets everything held here for an array: it is again as the store has it. */
	synchronized void forget( final String label, final int timepoint, final String name )
	{
		arrays.remove( new ArrayKey( label, timepoint, name ) );
	}

	/**
	 * The points of these views were replaced or removed: the rows of their setups go from every array of the (label,
	 * timepoint), those computed here at once, those of the store with the save.
	 */
	synchronized void removeRows( final Group group, final Collection< Integer > setups )
	{
		if ( setups.isEmpty() )
			return;

		removedSetups.computeIfAbsent( group, g -> new TreeSet<>() ).addAll( setups );

		for ( final Map.Entry< ArrayKey, StagedArray > e : arrays.entrySet() )
			if ( e.getKey().group().equals( group ) )
				for ( final Integer setup : setups )
				{
					e.getValue().ids.remove( setup );
					e.getValue().values.remove( setup );
				}
	}
}
