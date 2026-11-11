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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The brightness settings of the view setups as last adjusted in BigDataViewer, stored in the XML
 * ({@link XmlIoDisplaySettings}) so that the dataset opens the way it was left: per view setup the displayed
 * intensity range (min, max) and the limits of the brightness sliders (rangeMin, rangeMax). No colors: the
 * explorer colors the sources itself. BigDataViewer has one converter per view setup, so there is nothing per timepoint.
 *
 * The explorer's "Save" takes the values from the open BigDataViewer (explorer.bdv.BDVDisplaySettings.capture),
 * opening BigDataViewer applies them instead of the histogram estimate (apply). A setup without an entry is estimated as before.
 *
 * @author Stephan Preibisch
 */
public class DisplaySettings
{
	/**
	 * @param min - the lower end of the displayed intensity range
	 * @param max - the upper end
	 * @param rangeMin - the lower limit of the brightness sliders
	 * @param rangeMax - the upper limit
	 */
	public record DisplayRange( double min, double max, double rangeMin, double rangeMax )
	{
		/** @return this range with limits that contain min..max and are not empty (what BigDataViewer's sliders need) */
		public DisplayRange valid()
		{
			final double lo = Math.min( min, max ), hi = Math.max( min, max );
			final double rMin = Double.isNaN( rangeMin ) ? lo : Math.min( rangeMin, lo );
			double rMax = Double.isNaN( rangeMax ) ? hi : Math.max( rangeMax, hi );

			if ( !( rMax > rMin ) )
				rMax = rMin + 1;

			return new DisplayRange( lo, hi, rMin, rMax );
		}
	}

	private final TreeMap< Integer, DisplayRange > ranges = new TreeMap<>();

	public DisplaySettings() {}

	/** @return the range of a view setup, null if none is stored */
	public synchronized DisplayRange get( final int setupId ) { return ranges.get( setupId ); }

	/** @return whether the range of the setup changed */
	public synchronized boolean set( final int setupId, final DisplayRange range )
	{
		if ( range == null || Double.isNaN( range.min() ) || Double.isNaN( range.max() ) )
			return false;

		return !range.equals( ranges.put( setupId, range ) );
	}

	public synchronized void remove( final int setupId ) { ranges.remove( setupId ); }
	public synchronized boolean isEmpty() { return ranges.isEmpty(); }
	public synchronized int size() { return ranges.size(); }

	/** @return a copy: view setup id to range, sorted by id */
	public synchronized SortedMap< Integer, DisplayRange > getRanges() { return new TreeMap<>( ranges ); }

	/** @return the setups of each distinct range (sorted), in the order of the first setup that has it: what the XML holds */
	public synchronized Map< DisplayRange, List< Integer > > setupsPerRange()
	{
		final LinkedHashMap< DisplayRange, List< Integer > > map = new LinkedHashMap<>();

		for ( final Map.Entry< Integer, DisplayRange > e : ranges.entrySet() )
			map.computeIfAbsent( e.getValue(), r -> new ArrayList<>() ).add( e.getKey() );

		return map;
	}

	/** drops the ranges of setups the dataset does not have (any more) */
	public synchronized void retain( final Collection< Integer > setupIds )
	{
		ranges.keySet().retainAll( setupIds );
	}

	/** @return sorted ids as runs, e.g. "0-99,104,110-112" */
	public static String formatSetups( final Collection< Integer > setupIds )
	{
		final ArrayList< Integer > ids = new ArrayList<>( setupIds );
		Collections.sort( ids );

		final StringBuilder s = new StringBuilder();

		for ( int i = 0; i < ids.size(); )
		{
			int j = i;
			while ( j + 1 < ids.size() && ids.get( j + 1 ) <= ids.get( j ) + 1 )
				++j;

			if ( s.length() > 0 )
				s.append( ',' );
			s.append( ids.get( i ) );
			if ( !ids.get( j ).equals( ids.get( i ) ) )
				s.append( '-' ).append( ids.get( j ) );

			i = j + 1;
		}

		return s.toString();
	}

	/**
	 * @return the ids of {@link #formatSetups}
	 * @throws IllegalArgumentException if the text is not a list of ids and runs of ids
	 */
	public static List< Integer > parseSetups( final String text )
	{
		final ArrayList< Integer > ids = new ArrayList<>();

		if ( text == null || text.trim().isEmpty() )
			return ids;

		for ( final String part : text.trim().split( "[,\\s]+" ) )
		{
			try
			{
				final int dash = part.indexOf( '-', 1 );

				if ( dash < 0 )
				{
					ids.add( Integer.parseInt( part ) );
				}
				else
				{
					final int from = Integer.parseInt( part.substring( 0, dash ) ), to = Integer.parseInt( part.substring( dash + 1 ) );

					if ( to < from )
						throw new IllegalArgumentException( "Not a list of view setup ids: '" + text + "' (" + part + ")" );

					for ( int id = from; id <= to; ++id )
						ids.add( id );
				}
			}
			catch ( final NumberFormatException e )
			{
				throw new IllegalArgumentException( "Not a list of view setup ids: '" + text + "' (" + part + ")" );
			}
		}

		return ids;
	}
}
