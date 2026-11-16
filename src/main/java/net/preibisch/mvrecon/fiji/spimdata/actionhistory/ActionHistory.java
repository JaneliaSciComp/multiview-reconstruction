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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import mpicbg.spim.data.sequence.ViewId;

/**
 * Ordered list of {@link ActionRecord}s attached to a SpimData2.
 * Entries are appended in execution order. Used both for audit/display and
 * for generating BigStitcher-Spark CLI equivalents.
 *
 * <p>This class has a public no-arg constructor so that
 * {@link mpicbg.spim.data.generic.base.XmlIoSingleton} can instantiate it
 * reflectively during XML load.</p>
 */
public class ActionHistory
{
	private final ArrayList<ActionRecord> records = new ArrayList<>();

	public ActionHistory() {}

	public void add( final ActionRecord r )
	{
		if ( r == null )
			return;
		// dedups at add() (chokepoint for recorder, XML loader).
        // Duplicate record: when two records have identical timestamps, actionIds, resultRefs +
		// params; recording these is never legitimate.
        // affectedViews is excluded from the comparison --> O(1) per record even for 100k+ tiles.
		for ( final ActionRecord existing : records )
			if ( sameEvent( existing, r ) )
				return;
		records.add( r );
	}

	private static boolean sameEvent( final ActionRecord a, final ActionRecord b )
	{
		return a.getTimestampMillis() == b.getTimestampMillis()
				&& a.getActionId().equals( b.getActionId() )
				&& a.getResultRef().equals( b.getResultRef() )
				&& a.getParams().equals( b.getParams() );
	}

	public List<ActionRecord> getRecords()
	{
		return Collections.unmodifiableList( records );
	}

	public int size() { return records.size(); }

	public boolean isEmpty() { return records.isEmpty(); }

	/**
	 * Convenience for data-tied removal: drop entries whose resultRef equals the given string.
	 * @return number of records removed
	 */
	public int removeByResultRef( final String resultRef )
	{
		if ( resultRef == null || resultRef.isEmpty() )
			return 0;
		final int before = records.size();
		records.removeIf( r -> resultRef.equals( r.getResultRef() ) );
		return before - records.size();
	}

	/** action id used for GUI registration; kept in sync with the recorder in Interest_Point_Registration. */
	public static final String REGISTER_INTERESTPOINTS = "register-interestpoints";

	/**
	 * Data-tied removal for registration. Each just-un-registered view is attributed to the most
	 * recent {@code register-interestpoints} entry that still claims it, and that view is dropped
	 * from the entry's affected set. An entry is purged only once <em>every</em> view it touched
	 * has been un-registered — which may happen across several removal operations (the affected-view
	 * set shrinks each time and is persisted in the XML, so progress survives save/reload).
	 *
	 * <p>Attributing each removal to the newest owning entry is what makes removing one transform
	 * layer undo exactly one registration even when a view was re-registered multiple times.</p>
	 *
	 * @param unregisteredViews the views that just had a registration transform removed
	 * @return number of records removed
	 */
	public int removeRegistrationsForViews( final Collection<? extends ViewId> unregisteredViews )
	{
		if ( unregisteredViews == null || unregisteredViews.isEmpty() )
			return 0;

		final Set<ActionRecord> touched = new HashSet<>();
		for ( final ViewId v : unregisteredViews )
		{
			for ( int i = records.size() - 1; i >= 0; --i )
			{
				final ActionRecord r = records.get( i );
				if ( REGISTER_INTERESTPOINTS.equals( r.getActionId() ) && r.getAffectedViews().contains( v ) )
				{
					r.getAffectedViews().remove( v );
					touched.add( r );
					break;
				}
			}
		}

		final int before = records.size();
		records.removeIf( r -> touched.contains( r ) && r.getAffectedViews().isEmpty() );
		return before - records.size();
	}

	/** action id of "Compute Interest Point Embeddings" ({@code Embed_Interest_Points.ACTION_ID} is this constant). */
	public static final String EMBED_INTERESTPOINTS = "embed-interestpoints";

	/**
	 * Data-tied removal for embeddings: the per-point array {@code arrayName} no longer holds rows for any view of the
	 * dataset (the Interest Point Explorer's "Remove embeddings" took the last ones), so the {@code embed-interestpoints}
	 * entries that computed it for one of the labels describe nothing that exists and go. While any view still has its
	 * rows the entries stay as they are: unlike a registration's, the views of an embedding entry are not kept in the XML
	 * (an entry that was loaded lists none), so an entry cannot be narrowed to the views that are left.
	 *
	 * @param labels the labels whose embeddings the array held: the label the rows were stored under and the filtered labels of it
	 * @param arrayName the name of the array
	 * @return number of records removed
	 */
	public int removeEmbeddings( final Collection<String> labels, final String arrayName )
	{
		if ( labels == null || arrayName == null )
			return 0;

		final int before = records.size();
		records.removeIf( r -> EMBED_INTERESTPOINTS.equals( r.getActionId() ) && arrayName.equals( r.getParams().get( "arrayName" ) ) && labels.contains( r.getParams().get( "label" ) ) );
		return before - records.size();
	}

	/**
	 * Remove the given records by identity (reference equality), not by value.
	 * Used by the Action History window to delete user-selected entries.
	 * @return number of records removed
	 */
	public int removeExact( final Collection<ActionRecord> toRemove )
	{
		if ( toRemove == null || toRemove.isEmpty() )
			return 0;
		final Set<ActionRecord> ids = new HashSet<>( toRemove );
		final int before = records.size();
		records.removeIf( ids::contains );
		return before - records.size();
	}
}
