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
package net.preibisch.mvrecon.fiji.spimdata.displaysettings;

import java.util.List;
import java.util.Map;

import org.jdom2.Element;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import net.preibisch.mvrecon.fiji.spimdata.displaysettings.DisplaySettings.DisplayRange;

/**
 * {@link DisplaySettings} in the XML: one element per distinct range with the view setups that have it, so that a
 * dataset whose setups share a few ranges costs a few lines however many setups it has:
 *
 * <pre>
 * &lt;DisplaySettings&gt;
 *   &lt;DisplayRange min="90.0" max="1200.0" rangeMin="0.0" rangeMax="65535.0" setups="0-99,104" /&gt;
 * &lt;/DisplaySettings&gt;
 * </pre>
 */
public class XmlIoDisplaySettings extends XmlIoSingleton< DisplaySettings >
{
	public static final String DISPLAYSETTINGS_TAG = "DisplaySettings";
	public static final String DISPLAYRANGE_TAG = "DisplayRange";
	public static final String MIN = "min", MAX = "max", RANGE_MIN = "rangeMin", RANGE_MAX = "rangeMax", SETUPS = "setups";

	public XmlIoDisplaySettings()
	{
		super( DISPLAYSETTINGS_TAG, DisplaySettings.class );
		handledTags.add( DISPLAYRANGE_TAG );
	}

	public Element toXml( final DisplaySettings displaySettings )
	{
		final Element elem = super.toXml();

		for ( final Map.Entry< DisplayRange, List< Integer > > e : displaySettings.setupsPerRange().entrySet() )
		{
			final Element range = new Element( DISPLAYRANGE_TAG );

			range.setAttribute( MIN, Double.toString( e.getKey().min() ) );
			range.setAttribute( MAX, Double.toString( e.getKey().max() ) );
			range.setAttribute( RANGE_MIN, Double.toString( e.getKey().rangeMin() ) );
			range.setAttribute( RANGE_MAX, Double.toString( e.getKey().rangeMax() ) );
			range.setAttribute( SETUPS, DisplaySettings.formatSetups( e.getValue() ) );

			elem.addContent( range );
		}

		return elem;
	}

	public DisplaySettings fromXml( final Element allDisplaySettings ) throws SpimDataException
	{
		final DisplaySettings displaySettings = super.fromXml( allDisplaySettings );

		for ( final Element range : allDisplaySettings.getChildren( DISPLAYRANGE_TAG ) )
		{
			try
			{
				final DisplayRange r = new DisplayRange( value( range, MIN ), value( range, MAX ), value( range, RANGE_MIN ), value( range, RANGE_MAX ) );

				for ( final int setupId : DisplaySettings.parseSetups( range.getAttributeValue( SETUPS ) ) )
					displaySettings.set( setupId, r );
			}
			catch ( final IllegalArgumentException e )
			{
				throw new SpimDataException( "Cannot read <" + DISPLAYRANGE_TAG + "> of <" + DISPLAYSETTINGS_TAG + ">: " + e.getMessage() );
			}
		}

		return displaySettings;
	}

	/** the limits may be missing (NaN: derived from min and max when applied), min and max may not */
	private static double value( final Element range, final String attribute )
	{
		final String text = range.getAttributeValue( attribute );

		if ( text == null )
		{
			if ( attribute.equals( RANGE_MIN ) || attribute.equals( RANGE_MAX ) )
				return Double.NaN;

			throw new IllegalArgumentException( "attribute '" + attribute + "' is missing" );
		}

		return Double.parseDouble( text.trim() );
	}
}
