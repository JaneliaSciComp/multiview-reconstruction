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
package net.preibisch.mvrecon.fiji.spimdata;

import java.text.ParseException;
import java.util.ArrayList;

public class NamePattern
{
	/**
	 * Parse a pattern provided by the user defining a range of integers. Allowed are enumerations seperated by
	 * commas, each entry can be a single number, a range e.g. 4-100 or a range in intervals e.g. 0-30:10 - which
	 * is equivalent to 0,10,20,30. Enumerations can now also contain letters or entire names!
	 *
	 * @param codedEnum - the input
	 * @param allowNonNumeric - if non-numeric entries are allowed or not
	 * @return a list of integers that were described, an empty list with the entry 0 if the String is "" or null
	 * @throws ParseException if the input string was illegal
	 */
    public static ArrayList< String > parseNameString( final String codedEnum, final boolean allowNonNumeric ) throws ParseException
    {
    	ArrayList< String > tmp = null;

    	if ( codedEnum == null || codedEnum.trim().length() == 0 )
    	{
    		tmp = new ArrayList< String >();
    		tmp.add( "0" );
    		return tmp;
    	}

		try
		{
	    	tmp = new ArrayList< String >();
	    	final String[] entries = codedEnum.split( "," );

	    	for ( String s : entries )
	    	{
	    		s = s.trim();
	    		s = s.replaceAll( " ", "" );

	    		if ( s.contains( "-" ) )
	    		{
	    			// this has to be integers, otherwise the range cannot be defined
	    			int start = 0, end = 0, step;
	    			start = Integer.parseInt( s.substring( 0, s.indexOf("-") ) );

	    			if ( s.indexOf( ":" ) < 0 )
	    			{
	    				end = Integer.parseInt( s.substring( s.indexOf("-") + 1, s.length() ) );
	    				step = 1;
	    			}
	    			else
	    			{
	    				end = Integer.parseInt( s.substring( s.indexOf("-") + 1, s.indexOf(":") ) );
	    				step = Integer.parseInt( s.substring( s.indexOf(":") + 1, s.length() ) );
	    			}

	    			if ( end > start )
	    				for ( int i = start; i <= end; i += step )
	    					tmp.add( "" + i );
	    			else
	    				for ( int i = start; i >= end; i -= step )
	    					tmp.add( "" + i );
	    		}
	    		else
	    		{
	    			// this can be anything
	    			if ( allowNonNumeric )
	    				tmp.add( s );
	    			else
	    				tmp.add( "" + Integer.parseInt( s ) );
	    		}
	    	}
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
			throw new ParseException( "Cannot parse: '" + codedEnum + "'", 0 );
		}

		return tmp;
    }
}
