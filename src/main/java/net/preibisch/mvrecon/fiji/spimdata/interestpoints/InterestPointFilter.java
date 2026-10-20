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

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import net.preibisch.legacy.io.IOFunctions;

/**
 * A filter on the attributes of interest points ({@link InterestPoint}): a range per attribute (|response|, intensity,
 * sigma), both bounds inclusive and each optional (NaN = unbounded). It defines a filtered label
 * ({@link VirtualFilteredInterestPointsN5}) and is stored in the XML as its canonical string, e.g.
 * "response=0.004..;sigma=1.5..3" (the filtered axes in the order of {@link Axis}, an unbounded side is empty).
 *
 * One grammar for the dialogs and the label suffixes: an entry "a" keeps values &gt;= a, "a-b" keeps a &lt;= value &lt;= b;
 * a dialog field holds comma separated entries, one filter (and one label) each. The suffix of a label is "_" + the
 * letter of the axis + the entry, e.g. "_r0.004", "_r0.004-0.01", "_i200", "_s1.5-3".
 *
 * Units: the response in the units of the DoG (the image normalized to [0,1]), the intensity in the units of the image,
 * the sigma in full-resolution pixels (x).
 *
 * @author Stephan Preibisch
 */
public class InterestPointFilter implements Serializable
{
	private static final long serialVersionUID = 1L;

	public enum Axis
	{
		RESPONSE( "response", "r", "|response|" ),
		INTENSITY( "intensity", "i", "intensity" ),
		SIGMA( "sigma", "s", "sigma" );

		/** the key in the canonical string and the action history, the letter of the label suffix, the name in descriptions */
		public final String key, letter, description;

		private Axis( final String key, final String letter, final String description )
		{
			this.key = key;
			this.letter = letter;
			this.description = description;
		}

		public static Axis forKey( final String key )
		{
			for ( final Axis axis : values() )
				if ( axis.key.equals( key ) )
					return axis;

			throw new IllegalArgumentException( "Unknown interest point attribute '" + key + "'." );
		}
	}

	/** the bounds per axis (inclusive), NaN = unbounded */
	public final double responseMin, responseMax, intensityMin, intensityMax, sigmaMin, sigmaMax;

	public InterestPointFilter(
			final double responseMin, final double responseMax,
			final double intensityMin, final double intensityMax,
			final double sigmaMin, final double sigmaMax )
	{
		this.responseMin = responseMin;
		this.responseMax = responseMax;
		this.intensityMin = intensityMin;
		this.intensityMax = intensityMax;
		this.sigmaMin = sigmaMin;
		this.sigmaMax = sigmaMax;

		for ( final Axis axis : Axis.values() )
		{
			final double min = min( axis ), max = max( axis );

			if ( Double.isInfinite( min ) || Double.isInfinite( max ) || min < 0 || max < 0 )
				throw new IllegalArgumentException( "The bounds of " + axis.description + " must be finite and >= 0, got [" + min + ", " + max + "]." );

			if ( !Double.isNaN( min ) && !Double.isNaN( max ) && min > max )
				throw new IllegalArgumentException( "The lower bound of " + axis.description + " is above the upper one: [" + min + ", " + max + "]." );
		}
	}

	/**
	 * @return a filter on one axis (min and/or max, NaN = unbounded)
	 */
	public static InterestPointFilter of( final Axis axis, final double min, final double max )
	{
		switch ( axis )
		{
		case RESPONSE:
			return new InterestPointFilter( min, max, Double.NaN, Double.NaN, Double.NaN, Double.NaN );
		case INTENSITY:
			return new InterestPointFilter( Double.NaN, Double.NaN, min, max, Double.NaN, Double.NaN );
		default:
			return new InterestPointFilter( Double.NaN, Double.NaN, Double.NaN, Double.NaN, min, max );
		}
	}

	public double min( final Axis axis )
	{
		switch ( axis )
		{
		case RESPONSE: return responseMin;
		case INTENSITY: return intensityMin;
		default: return sigmaMin;
		}
	}

	public double max( final Axis axis )
	{
		switch ( axis )
		{
		case RESPONSE: return responseMax;
		case INTENSITY: return intensityMax;
		default: return sigmaMax;
		}
	}

	/**
	 * @return whether the filter restricts this axis
	 */
	public boolean filters( final Axis axis )
	{
		return !Double.isNaN( min( axis ) ) || !Double.isNaN( max( axis ) );
	}

	/**
	 * @return whether the filter restricts nothing
	 */
	public boolean isEmpty()
	{
		for ( final Axis axis : Axis.values() )
			if ( filters( axis ) )
				return false;

		return true;
	}

	/**
	 * @return the value of a point on an axis (|response| for the response), NaN if unknown
	 */
	public static float value( final InterestPoint ip, final Axis axis )
	{
		switch ( axis )
		{
		case RESPONSE: return Math.abs( ip.getResponse() );
		case INTENSITY: return ip.getIntensity();
		default: return ip.getSigma();
		}
	}

	/**
	 * @return whether the point passes the filter
	 * @throws IllegalArgumentException if the point lacks an attribute the filter needs (NaN, e.g. a label stored before the attributes existed)
	 */
	public boolean accept( final InterestPoint ip )
	{
		for ( final Axis axis : Axis.values() )
		{
			if ( !filters( axis ) )
				continue;

			final float v = value( ip, axis );

			if ( Float.isNaN( v ) )
				throw new IllegalArgumentException( "Interest point " + ip.getId() + " has no " + axis.key + " stored, cannot filter by it (detect the base label again to store it)." );

			// the attributes are floats, the bounds are compared at that precision (a bound of 0.7 keeps a stored 0.7)
			if ( !Double.isNaN( min( axis ) ) && v < (float)min( axis ) )
				return false;

			if ( !Double.isNaN( max( axis ) ) && v > (float)max( axis ) )
				return false;
		}

		return true;
	}

	/**
	 * @return the points that pass the filter (the same objects), in the order of the collection
	 */
	public < P extends InterestPoint > ArrayList< P > filter( final Collection< P > points )
	{
		final ArrayList< P > filtered = new ArrayList<>();

		for ( final P ip : points )
			if ( accept( ip ) )
				filtered.add( ip );

		return filtered;
	}

	/**
	 * @return the canonical string, e.g. "response=0.004..;sigma=1.5..3" (the filtered axes in order, an unbounded side is empty), parsed by fromString
	 */
	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder();

		for ( final Axis axis : Axis.values() )
		{
			if ( !filters( axis ) )
				continue;

			if ( sb.length() > 0 )
				sb.append( ";" );

			sb.append( axis.key ).append( "=" ).append( plainOrEmpty( min( axis ) ) ).append( ".." ).append( plainOrEmpty( max( axis ) ) );
		}

		return sb.toString();
	}

	/**
	 * @param s - the canonical string (toString), e.g. "response=0.004..;sigma=1.5..3"
	 * @return the filter
	 * @throws IllegalArgumentException if the string is malformed
	 */
	public static InterestPointFilter fromString( final String s )
	{
		final double[] min = new double[ Axis.values().length ], max = new double[ Axis.values().length ];
		java.util.Arrays.fill( min, Double.NaN );
		java.util.Arrays.fill( max, Double.NaN );

		if ( s != null && s.trim().length() > 0 )
		{
			for ( final String entry : s.trim().split( ";" ) )
			{
				final int eq = entry.indexOf( '=' );
				final int dots = entry.indexOf( ".." );

				if ( eq < 0 || dots < eq )
					throw new IllegalArgumentException( "Malformed interest point filter '" + s + "', expected key=min..max entries separated by ';'." );

				final Axis axis = Axis.forKey( entry.substring( 0, eq ).trim() );
				final String lo = entry.substring( eq + 1, dots ).trim(), hi = entry.substring( dots + 2 ).trim();

				min[ axis.ordinal() ] = lo.isEmpty() ? Double.NaN : parseBound( lo, s );
				max[ axis.ordinal() ] = hi.isEmpty() ? Double.NaN : parseBound( hi, s );
			}
		}

		return new InterestPointFilter(
				min[ Axis.RESPONSE.ordinal() ], max[ Axis.RESPONSE.ordinal() ],
				min[ Axis.INTENSITY.ordinal() ], max[ Axis.INTENSITY.ordinal() ],
				min[ Axis.SIGMA.ordinal() ], max[ Axis.SIGMA.ordinal() ] );
	}

	protected static double parseBound( final String value, final String whole )
	{
		try
		{
			return Double.parseDouble( value );
		}
		catch ( final NumberFormatException e )
		{
			throw new IllegalArgumentException( "Malformed bound '" + value + "' in interest point filter '" + whole + "'." );
		}
	}

	/**
	 * @return the label suffix, "_" + letter + entry per filtered axis, e.g. "_r0.004", "_r0.004-0.01", "_s1.5-3", "_r0.004_s1.5-3" (a missing lower bound is written as 0)
	 */
	public String suffix()
	{
		final StringBuilder sb = new StringBuilder();

		for ( final Axis axis : Axis.values() )
		{
			if ( !filters( axis ) )
				continue;

			sb.append( "_" ).append( axis.letter ).append( plain( Double.isNaN( min( axis ) ) ? 0 : min( axis ) ) );

			if ( !Double.isNaN( max( axis ) ) )
				sb.append( "-" ).append( plain( max( axis ) ) );
		}

		return sb.toString();
	}

	/**
	 * @return the parameter string of a filtered label, e.g. "filtered from 'beads': |response| >= 0.004, sigma in [1.5, 3]"
	 */
	public String describe( final String baseLabel )
	{
		final StringBuilder sb = new StringBuilder( "filtered from '" + baseLabel + "': " );
		boolean first = true;

		for ( final Axis axis : Axis.values() )
		{
			if ( !filters( axis ) )
				continue;

			if ( !first )
				sb.append( ", " );

			first = false;

			final double min = min( axis ), max = max( axis );

			if ( Double.isNaN( max ) )
				sb.append( axis.description ).append( " >= " ).append( plain( min ) );
			else if ( Double.isNaN( min ) )
				sb.append( axis.description ).append( " <= " ).append( plain( max ) );
			else
				sb.append( axis.description ).append( " in [" ).append( plain( min ) ).append( ", " ).append( plain( max ) ).append( "]" );
		}

		if ( first )
			sb.append( "everything" );

		return sb.toString();
	}

	/**
	 * @return the filter per axis for the action history, e.g. { response: "0.004..", sigma: "1.5..3" } (the filtered axes only)
	 */
	public Map< String, String > describeParameters()
	{
		final LinkedHashMap< String, String > p = new LinkedHashMap<>();

		for ( final Axis axis : Axis.values() )
			if ( filters( axis ) )
				p.put( axis.key, plainOrEmpty( min( axis ) ) + ".." + plainOrEmpty( max( axis ) ) );

		return p;
	}

	/**
	 * Parses the entries of a dialog field into one filter per entry on the axis: entries separated by ',' (or ';'),
	 * each "a" (&gt;= a) or "a-b" (a..b), sorted by the lower then the upper bound, duplicates dropped
	 *
	 * @return the filters (empty for an empty field), null with a log line if an entry is malformed
	 */
	public static List< InterestPointFilter > parseEntries( final String field, final Axis axis )
	{
		final ArrayList< InterestPointFilter > filters = new ArrayList<>();

		if ( field == null || field.trim().isEmpty() )
			return filters;

		final TreeSet< double[] > ranges = new TreeSet<>( ( a, b ) -> {
			final int c = Double.compare( a[ 0 ], b[ 0 ] );
			return c != 0 ? c : Double.compare( a[ 1 ], b[ 1 ] );
		} );

		for ( final String entry : field.split( "[,;]" ) )
		{
			if ( entry.trim().isEmpty() )
				continue;

			try
			{
				ranges.add( parseRange( entry ) );
			}
			catch ( final IllegalArgumentException e )
			{
				IOFunctions.println( "Cannot parse the " + axis.description + " entry '" + entry.trim() + "' of '" + field + "': " + e.getMessage() );
				return null;
			}
		}

		for ( final double[] range : ranges )
			filters.add( of( axis, range[ 0 ], range[ 1 ] ) );

		return filters;
	}

	/**
	 * @param entry - "a" (&gt;= a) or "a-b" (a..b), a and b finite and &gt;= 0 (exponents like 1e-4 are fine)
	 * @return { min, max }, max is NaN for "a"
	 * @throws IllegalArgumentException if malformed
	 */
	public static double[] parseRange( final String entry )
	{
		final String s = entry.trim();

		// the '-' between the bounds (not the one of an exponent, not a leading sign)
		int dash = -1;

		for ( int i = 1; i < s.length() && dash < 0; ++i )
			if ( s.charAt( i ) == '-' && s.charAt( i - 1 ) != 'e' && s.charAt( i - 1 ) != 'E' )
				dash = i;

		final double min, max;

		try
		{
			min = Double.parseDouble( dash < 0 ? s : s.substring( 0, dash ).trim() );
			max = dash < 0 ? Double.NaN : Double.parseDouble( s.substring( dash + 1 ).trim() );
		}
		catch ( final NumberFormatException e )
		{
			throw new IllegalArgumentException( "expected a number 'a' or a range 'a-b'" );
		}

		if ( !( min >= 0 ) || Double.isInfinite( min ) || ( !Double.isNaN( max ) && ( !( max >= 0 ) || Double.isInfinite( max ) ) ) )
			throw new IllegalArgumentException( "the bounds must be finite and >= 0" );

		if ( !Double.isNaN( max ) && min > max )
			throw new IllegalArgumentException( "the lower bound " + plain( min ) + " is above the upper bound " + plain( max ) );

		return new double[] { min, max };
	}

	/**
	 * @return the shortest plain decimal representation (no exponent, no trailing zeros), e.g. 1.0E-4 as "0.0001"
	 */
	public static String plain( final double value )
	{
		return BigDecimal.valueOf( value ).stripTrailingZeros().toPlainString();
	}

	protected static String plainOrEmpty( final double value )
	{
		return Double.isNaN( value ) ? "" : plain( value );
	}

	@Override
	public int hashCode()
	{
		return toString().hashCode();
	}

	@Override
	public boolean equals( final Object obj )
	{
		if ( this == obj )
			return true;

		if ( obj == null || getClass() != obj.getClass() )
			return false;

		final InterestPointFilter other = (InterestPointFilter)obj;

		for ( final Axis axis : Axis.values() )
			if ( Double.compare( min( axis ), other.min( axis ) ) != 0 || Double.compare( max( axis ), other.max( axis ) ) != 0 )
				return false;

		return true;
	}
}
