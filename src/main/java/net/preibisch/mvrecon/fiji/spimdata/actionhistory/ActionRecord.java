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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.sequence.ViewId;

/**
 * One recorded user action — a successful invocation of an mvrecon GUI command.
 * Stored as a neutral structured form (action id + parameter map)
 * --> translation to BigStitcher-Spark CLI is decoupled from XML persistence
 */
public class ActionRecord
{
	private final String actionId;
	private final long timestampMillis;
	private final String mvreconClass;
	private final LinkedHashMap<String,String> params;
	private final ArrayList<ViewId> affectedViews;
	private final String resultRef;

	public ActionRecord(
			final String actionId,
			final long timestampMillis,
			final String mvreconClass,
			final Map<String,String> params,
			final List<? extends ViewId> affectedViews,
			final String resultRef )
	{
		if ( actionId == null || actionId.isEmpty() )
			throw new IllegalArgumentException( "actionId must be non-empty" );
		this.actionId = actionId;
		this.timestampMillis = timestampMillis;
		this.mvreconClass = mvreconClass == null ? "" : mvreconClass;
		this.params = new LinkedHashMap<>();
		if ( params != null )
			this.params.putAll( params );
		this.affectedViews = new ArrayList<>();
		if ( affectedViews != null )
			for ( final ViewId v : affectedViews )
				this.affectedViews.add( new ViewId( v.getTimePointId(), v.getViewSetupId() ) );
		this.resultRef = resultRef == null ? "" : resultRef;
	}

	public String getActionId() { return actionId; }
	public long getTimestampMillis() { return timestampMillis; }
	public String getMvreconClass() { return mvreconClass; }
	public Map<String,String> getParams() { return params; }
	public List<ViewId> getAffectedViews() { return affectedViews; }
	public String getResultRef() { return resultRef; }
}
