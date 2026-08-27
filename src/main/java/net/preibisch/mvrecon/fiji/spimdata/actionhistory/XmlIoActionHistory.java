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

import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ACTION_HISTORY_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ACTION_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_ACTION_ID;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_MVRECON_CLASS;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_PARAM_KEY;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_PARAM_VALUE;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_RESULT_REF;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_TIMESTAMP;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.PARAM_TAG;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import org.jdom2.Element;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;

public class XmlIoActionHistory extends XmlIoSingleton<ActionHistory>
{
	public XmlIoActionHistory()
	{
		super( ACTION_HISTORY_TAG, ActionHistory.class );
		handledTags.add( ACTION_HISTORY_TAG );
	}

	/**
	 * Affected views are not written per-view (see {@link #actionToXml}): {@code ActionHistoryRecorder
	 * .record} already adds a compact description of them to {@code params}, so
	 * {@link #fromXml(Element, Collection)} re-derives the concrete view list from that instead.
	 */
	public Element toXml( final ActionHistory history )
	{
		final Element elem = super.toXml();
		if ( history != null )
			for ( final ActionRecord r : history.getRecords() )
				elem.addContent( actionToXml( r ) );
		return elem;
	}

	/**
	 * @param viewDescriptions used to re-derive a {@code register-interestpoints} record's
	 *                         {@code affectedViews} from its {@code params} (see
	 *                         {@link ActionHistoryRecorder#expandViewSelection}) -- only the view
	 *                         descriptions are needed, not the whole live dataset. {@code
	 *                         affectedViews} is the one thing every other action id's params don't
	 *                         already fully cover for translation, and it's also the only thing
	 *                         {@link ActionHistory#removeRegistrationsForViews} ever reads back out of
	 *                         it, so it's the only action id worth expanding at all.
	 */
	public ActionHistory fromXml( final Element historyElem, final Collection<? extends ViewDescription> viewDescriptions ) throws SpimDataException
	{
		final ActionHistory history = super.fromXml( historyElem );
		// built at most once, and only if a register-interestpoints record actually shows up
		List<ViewDescription> present = null;
		for ( final Element e : historyElem.getChildren( ACTION_TAG ) )
		{
			if ( present == null && ActionHistory.REGISTER_INTERESTPOINTS.equals( e.getAttributeValue( ATTR_ACTION_ID ) ) )
				present = ActionHistoryRecorder.presentViews( viewDescriptions );
			history.add( actionFromXml( e, present ) );
		}
		return history;
	}

	private static Element actionToXml( final ActionRecord r )
	{
		final Element e = new Element( ACTION_TAG );
		e.setAttribute( ATTR_ACTION_ID, r.getActionId() );
		e.setAttribute( ATTR_TIMESTAMP, Long.toString( r.getTimestampMillis() ) );
		if ( !r.getMvreconClass().isEmpty() )
			e.setAttribute( ATTR_MVRECON_CLASS, r.getMvreconClass() );
		if ( !r.getResultRef().isEmpty() )
			e.setAttribute( ATTR_RESULT_REF, r.getResultRef() );

		for ( final Entry<String,String> p : r.getParams().entrySet() )
		{
			final Element pe = new Element( PARAM_TAG );
			pe.setAttribute( ATTR_PARAM_KEY, p.getKey() );
			pe.setAttribute( ATTR_PARAM_VALUE, p.getValue() == null ? "" : p.getValue() );
			e.addContent( pe );
		}
		return e;
	}

	private static ActionRecord actionFromXml( final Element e, final List<ViewDescription> present )
	{
		final String actionId = e.getAttributeValue( ATTR_ACTION_ID );
		final String tsStr = e.getAttributeValue( ATTR_TIMESTAMP );
		long ts = 0L;
		if ( tsStr != null )
			try { ts = Long.parseLong( tsStr ); } catch ( NumberFormatException ignore ) {}
		final String klass = e.getAttributeValue( ATTR_MVRECON_CLASS );
		final String resultRef = e.getAttributeValue( ATTR_RESULT_REF );

		final LinkedHashMap<String,String> params = new LinkedHashMap<>();
		for ( final Element pe : e.getChildren( PARAM_TAG ) )
			params.put( pe.getAttributeValue( ATTR_PARAM_KEY ), pe.getAttributeValue( ATTR_PARAM_VALUE ) );

		// affectedViews is only ever read back out for register-interestpoints (see fromXml) --
		// every other action id can skip the per-view reconstruction entirely
		final List<ViewId> views = ActionHistory.REGISTER_INTERESTPOINTS.equals( actionId )
				? ActionHistoryRecorder.expandViewSelection( present, params )
				: Collections.emptyList();

		return new ActionRecord( actionId, ts, klass, params, views, resultRef );
	}
}
