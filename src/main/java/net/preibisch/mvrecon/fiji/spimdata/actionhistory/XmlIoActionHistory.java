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
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_VIEW_SETUP;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.ATTR_VIEW_TP;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.PARAM_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.actionhistory.XmlKeysActionHistory.VIEW_TAG;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;

import org.jdom2.Element;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import mpicbg.spim.data.sequence.ViewId;

public class XmlIoActionHistory extends XmlIoSingleton<ActionHistory>
{
	public XmlIoActionHistory()
	{
		super( ACTION_HISTORY_TAG, ActionHistory.class );
		handledTags.add( ACTION_HISTORY_TAG );
	}

	public Element toXml( final ActionHistory history )
	{
		final Element elem = super.toXml();
		if ( history != null )
			for ( final ActionRecord r : history.getRecords() )
				elem.addContent( actionToXml( r ) );
		return elem;
	}

	public ActionHistory fromXml( final Element historyElem ) throws SpimDataException
	{
		final ActionHistory history = super.fromXml( historyElem );
		for ( final Element e : historyElem.getChildren( ACTION_TAG ) )
			history.add( actionFromXml( e ) );
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
		for ( final ViewId v : r.getAffectedViews() )
		{
			final Element ve = new Element( VIEW_TAG );
			ve.setAttribute( ATTR_VIEW_TP, Integer.toString( v.getTimePointId() ) );
			ve.setAttribute( ATTR_VIEW_SETUP, Integer.toString( v.getViewSetupId() ) );
			e.addContent( ve );
		}
		return e;
	}

	private static ActionRecord actionFromXml( final Element e )
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

		final List<ViewId> views = new ArrayList<>();
		for ( final Element ve : e.getChildren( VIEW_TAG ) )
		{
			final int tp = Integer.parseInt( ve.getAttributeValue( ATTR_VIEW_TP ) );
			final int setup = Integer.parseInt( ve.getAttributeValue( ATTR_VIEW_SETUP ) );
			views.add( new ViewId( tp, setup ) );
		}
		return new ActionRecord( actionId, ts, klass, params, views, resultRef );
	}
}
