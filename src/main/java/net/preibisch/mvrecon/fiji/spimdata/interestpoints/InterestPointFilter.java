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
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
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
 * One grammar for the dialogs and the label suffixes: an entry "a" keeps values &gt;= a, "a-b" keeps a &lt;= value &lt;= b
 * (parseRange); a dialog field holds entries separated by ',', one filter (and one label) each (parseEntries). The suffix
 * of a label is "_" + the letter of the axis + the entry, e.g. "_r0.004", "_r0.004-0.01", "_i200", "_s1.5-3".
 *
 * Units: the response in the units of the DoG (the image normalized to [0,1]), the intensity in the units of the image,
 * the sigma in full-resolution pixels (x).
 *
 * A count limit ({@link Limit}, the former "Limit amount of detections" of the detection dialog) keeps only n points per
 * view: the n highest, the n around the median or the n lowest by one attribute (intensity, |response| or sigma), applied
 * after the ranges; it needs the points of the whole view, so it is applied by {@link #filter(Collection)} (called per view
 * by every data path) and not by {@link #accept(InterestPoint)}. Canonical "limit=response:highest:3000", suffix "_rh3000"
 * (the letters of the axis and of the mode), set in the dialog "Filter interest points (keep fixed number)" (Limit_Detections).
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

	/**
	 * Which points a count limit keeps, by the value of one attribute within one view and after the ranges: the highest n,
	 * the n around the median or the lowest n (the modes of the former "Limit amount of detections" of the detection dialog)
	 */
	public enum Limit
	{
		HIGHEST( "highest", "h", "highest" ),
		AROUND_MEDIAN( "median", "m", "around the median" ),
		LOWEST( "lowest", "l", "lowest" );

		/** the key in the canonical string and the dialog entries, the letter of the label suffix, the words of a description */
		public final String key, letter, description;

		private Limit( final String key, final String letter, final String description )
		{
			this.key = key;
			this.letter = letter;
			this.description = description;
		}

		public static Limit forKey( final String key )
		{
			for ( final Limit limit : values() )
				if ( limit.key.equals( key ) )
					return limit;

			throw new IllegalArgumentException( "Unknown limit '" + key + "', expected highest, median or lowest." );
		}
	}

	/** the key of the count limit in the canonical string and the action history */
	public static final String limitKey = "limit";

	/** the bounds per axis (inclusive), NaN = unbounded */
	public final double responseMin, responseMax, intensityMin, intensityMax, sigmaMin, sigmaMax;

	/** the count limit per view (applied after the ranges): keep limitCount points by the value of limitAxis (|response| for the response), null = none (then limitCount is 0) */
	public final Axis limitAxis;
	public final Limit limit;
	public final int limitCount;

	public InterestPointFilter(
			final double responseMin, final double responseMax,
			final double intensityMin, final double intensityMax,
			final double sigmaMin, final double sigmaMax )
	{
		this( responseMin, responseMax, intensityMin, intensityMax, sigmaMin, sigmaMax, null, null, 0 );
	}

	/**
	 * @param limitAxis - the attribute a count limit ranks by (|response| for the response), null = no limit
	 * @param limit - keep only the limitCount highest / around the median / lowest points of a view that pass the ranges, null = no limit
	 * @param limitCount - the number of points to keep, &gt;= 1 if a limit is set (0 otherwise)
	 */
	public InterestPointFilter(
			final double responseMin, final double responseMax,
			final double intensityMin, final double intensityMax,
			final double sigmaMin, final double sigmaMax,
			final Axis limitAxis, final Limit limit, final int limitCount )
	{
		this.responseMin = responseMin;
		this.responseMax = responseMax;
		this.intensityMin = intensityMin;
		this.intensityMax = intensityMax;
		this.sigmaMin = sigmaMin;
		this.sigmaMax = sigmaMax;
		this.limitAxis = limit == null ? null : limitAxis;
		this.limit = limit;
		this.limitCount = limit == null ? 0 : limitCount;

		for ( final Axis axis : Axis.values() )
		{
			final double min = min( axis ), max = max( axis );

			if ( Double.isInfinite( min ) || Double.isInfinite( max ) || min < 0 || max < 0 )
				throw new IllegalArgumentException( "The bounds of " + axis.description + " must be finite and >= 0, got [" + min + ", " + max + "]." );

			if ( !Double.isNaN( min ) && !Double.isNaN( max ) && min > max )
				throw new IllegalArgumentException( "The lower bound of " + axis.description + " is above the upper one: [" + min + ", " + max + "]." );
		}

		if ( limit != null && limitAxis == null )
			throw new IllegalArgumentException( "The limit needs the attribute to rank by." );

		if ( limit != null && limitCount < 1 )
			throw new IllegalArgumentException( "The count of the limit must be >= 1, but is " + limitCount + "." );
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

	/**
	 * @return a filter with only a count limit: the count highest / around the median / lowest points of a view by the attribute
	 */
	public static InterestPointFilter of( final Axis axis, final Limit limit, final int count )
	{
		return new InterestPointFilter( Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, axis, limit, count );
	}

	/**
	 * @return this filter with the count limit (replacing any), a null limit = none
	 */
	public InterestPointFilter withLimit( final Axis axis, final Limit limit, final int count )
	{
		return new InterestPointFilter( responseMin, responseMax, intensityMin, intensityMax, sigmaMin, sigmaMax, axis, limit, count );
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
	 * @return whether the filter has a count limit
	 */
	public boolean hasLimit()
	{
		return limit != null;
	}

	/**
	 * @return whether the filter restricts nothing (no range, no count limit)
	 */
	public boolean isEmpty()
	{
		for ( final Axis axis : Axis.values() )
			if ( filters( axis ) )
				return false;

		return limit == null;
	}

	/**
	 * @return the attributes the filter needs: the axes it restricts and the one a count limit ranks by
	 */
	public EnumSet< Axis > axes()
	{
		final EnumSet< Axis > axes = EnumSet.noneOf( Axis.class );

		for ( final Axis axis : Axis.values() )
			if ( filters( axis ) || axis == limitAxis )
				axes.add( axis );

		return axes;
	}

	/**
	 * @return the attributes this filter needs (axes) that any of the points lacks, see missingAttributes( points, axes )
	 */
	public EnumSet< Axis > missingAttributes( final Collection< ? extends InterestPoint > points )
	{
		return missingAttributes( points, axes() );
	}

	/**
	 * @return the attributes (of the given ones) that any of the points lacks (NaN, e.g. a label stored before the
	 * attributes existed); filtering by them throws (filter, accept), so the dialogs check this before creating a label
	 */
	public static EnumSet< Axis > missingAttributes( final Collection< ? extends InterestPoint > points, final Collection< Axis > axes )
	{
		final EnumSet< Axis > missing = EnumSet.noneOf( Axis.class );

		for ( final Axis axis : axes )
			for ( final InterestPoint ip : points )
				if ( Float.isNaN( value( ip, axis ) ) )
				{
					missing.add( axis );
					break;
				}

		return missing;
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
	 * @return whether the point passes the ranges of the filter
	 * @throws IllegalArgumentException if the point lacks an attribute the filter needs (NaN, e.g. a label stored before the attributes existed)
	 * @throws IllegalStateException if the filter has a count limit, which needs the points of the whole view (filter( Collection ))
	 */
	public boolean accept( final InterestPoint ip )
	{
		requireNoLimit();

		return acceptRanges( ip, true );
	}

	/**
	 * @return whether the point passes the ranges of the filter; a point lacking an attribute the filter needs does not pass (no exception, for previews)
	 * @throws IllegalStateException if the filter has a count limit, which needs the points of the whole view (filterKnown( Collection ))
	 */
	public boolean acceptKnown( final InterestPoint ip )
	{
		requireNoLimit();

		return acceptRanges( ip, false );
	}

	protected void requireNoLimit()
	{
		if ( limit != null )
			throw new IllegalStateException( "The filter '" + this + "' has a count limit, which needs the points of the whole view: use filter( Collection ) or filterKnown( Collection )." );
	}

	protected boolean acceptRanges( final InterestPoint ip, final boolean throwOnUnknown )
	{
		for ( final Axis axis : Axis.values() )
		{
			if ( !filters( axis ) )
				continue;

			final float v = value( ip, axis );

			if ( Float.isNaN( v ) )
			{
				if ( throwOnUnknown )
					throw new IllegalArgumentException( "Interest point " + ip.getId() + " has no " + axis.key + " stored, cannot filter by it (detect the base label again to store it)." );
				else
					return false;
			}

			// the attributes are floats, the bounds are compared at that precision (a bound of 0.7 keeps a stored 0.7)
			if ( !Double.isNaN( min( axis ) ) && v < (float)min( axis ) )
				return false;

			if ( !Double.isNaN( max( axis ) ) && v > (float)max( axis ) )
				return false;
		}

		return true;
	}

	/**
	 * @param points - the points of ONE view (a count limit keeps the limitCount highest / around the median / lowest by its attribute of the points that pass the ranges)
	 * @return the points that pass the filter (the same objects), in the order of the collection
	 * @throws IllegalArgumentException if a point lacks an attribute the filter needs (the one a count limit ranks by, too)
	 */
	public < P extends InterestPoint > ArrayList< P > filter( final Collection< P > points )
	{
		return filter( points, true );
	}

	/**
	 * As filter( Collection ), but a point lacking an attribute the filter needs does not pass (no exception, for previews)
	 */
	public < P extends InterestPoint > ArrayList< P > filterKnown( final Collection< P > points )
	{
		return filter( points, false );
	}

	protected < P extends InterestPoint > ArrayList< P > filter( final Collection< P > points, final boolean throwOnUnknown )
	{
		final ArrayList< P > filtered = new ArrayList<>();

		for ( final P ip : points )
			if ( acceptRanges( ip, throwOnUnknown ) )
				filtered.add( ip );

		return limit == null ? filtered : limit( filtered, limitAxis, limit, limitCount, throwOnUnknown );
	}

	/**
	 * Keeps the count highest / around the median / lowest points by the value of the axis (|response| for the response),
	 * the result in the order of the input; at most count points are returned as they are. The kept points are the block
	 * [from, to) (keepRange) of the points ordered by descending value with ties in the order of the list (a stable sort);
	 * computed without sorting the objects: the values are sorted as a float[], which gives the values at both ends of the
	 * block and how many values lie above each, then one pass over the points keeps everything strictly between the two
	 * and, of the ties at either end, the occurrences whose position in that order falls into the block (a million points
	 * cost about as much as copying them, a comparator sort of the objects five times more)
	 *
	 * @param throwOnUnknown - a point without the attribute (NaN) throws if true, otherwise it does not pass
	 */
	public static < P extends InterestPoint > ArrayList< P > limit( final List< P > points, final Axis axis, final Limit limit, final int count, final boolean throwOnUnknown )
	{
		final ArrayList< P > ranked = new ArrayList<>();

		for ( final P ip : points )
		{
			if ( Float.isNaN( value( ip, axis ) ) )
			{
				if ( throwOnUnknown )
					throw new IllegalArgumentException( "Interest point " + ip.getId() + " has no " + axis.key + " stored, cannot limit by it (detect the base label again to store it)." );
				else
					continue;
			}

			ranked.add( ip );
		}

		final int m = ranked.size();

		if ( m <= count )
			return ranked;

		final float[] values = new float[ m ];

		for ( int i = 0; i < m; ++i )
			values[ i ] = value( ranked.get( i ), axis );

		final float[] sorted = values.clone();
		Arrays.sort( sorted );

		final int[] range = keepRange( m, limit, count );
		final int from = range[ 0 ], to = range[ 1 ];

		// the values at the first and the last kept position of the descending order, and how many values lie above each
		final float hiCut = sorted[ m - 1 - from ], loCut = sorted[ m - to ];
		final int aboveHi = m - upperIndex( sorted, hiCut ), aboveLo = m - upperIndex( sorted, loCut );
		int seenHi = 0, seenLo = 0;

		final ArrayList< P > kept = new ArrayList<>( to - from );

		for ( int i = 0; i < m; ++i )
		{
			final float v = values[ i ];
			final boolean keep;

			if ( v > hiCut || v < loCut )
			{
				keep = false;
			}
			else if ( v == hiCut )
			{
				// the position of this occurrence in the descending order (ties in the order of the list)
				final int position = aboveHi + seenHi++;
				keep = position >= from && position < to;
			}
			else if ( v == loCut )
			{
				final int position = aboveLo + seenLo++;
				keep = position >= from && position < to;
			}
			else
			{
				keep = true;
			}

			if ( keep )
				kept.add( ranked.get( i ) );
		}

		return kept;
	}

	/**
	 * @return the index after the last value &lt;= v in an ascending array
	 */
	protected static int upperIndex( final float[] sorted, final float v )
	{
		int lo = 0, hi = sorted.length;

		while ( lo < hi )
		{
			final int mid = ( lo + hi ) >>> 1;

			if ( sorted[ mid ] <= v )
				lo = mid + 1;
			else
				hi = mid;
		}

		return lo;
	}

	/**
	 * @return { from, to } (to exclusive) of the points to keep in a list of size points sorted by descending value:
	 * the first count (highest), the last count (lowest) or the count in the middle (around the median); at most size
	 */
	public static int[] keepRange( final int size, final Limit limit, final int count )
	{
		final int n = Math.min( count, size );

		switch ( limit )
		{
		case HIGHEST:
			return new int[] { 0, n };
		case LOWEST:
			return new int[] { size - n, size };
		default:
			final int from = ( size - n ) / 2;
			return new int[] { from, from + n };
		}
	}

	/**
	 * @return the canonical string, e.g. "response=0.004..;sigma=1.5..3;limit=response:highest:3000" (the filtered axes in order, an unbounded side is empty, the count limit last as axis:mode:count), parsed by fromString
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

		if ( limit != null )
		{
			if ( sb.length() > 0 )
				sb.append( ";" );

			sb.append( limitKey ).append( "=" ).append( limitAxis.key ).append( ":" ).append( limit.key ).append( ":" ).append( limitCount );
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
		Arrays.fill( min, Double.NaN );
		Arrays.fill( max, Double.NaN );

		Axis limitAxis = null;
		Limit limit = null;
		int limitCount = 0;

		if ( s != null && s.trim().length() > 0 )
		{
			for ( final String entry : s.trim().split( ";" ) )
			{
				final int eq = entry.indexOf( '=' );

				if ( eq < 0 )
					throw new IllegalArgumentException( "Malformed interest point filter '" + s + "', expected key=min..max (or limit=mode:count) entries separated by ';'." );

				final String key = entry.substring( 0, eq ).trim(), value = entry.substring( eq + 1 ).trim();

				if ( key.equals( limitKey ) )
				{
					// axis:mode:count
					final String[] parts = value.split( ":" );

					if ( parts.length != 3 )
						throw new IllegalArgumentException( "Malformed count limit '" + value + "' in interest point filter '" + s + "', expected axis:mode:count." );

					try
					{
						limitAxis = Axis.forKey( parts[ 0 ].trim() );
						limit = Limit.forKey( parts[ 1 ].trim() );
						limitCount = Integer.parseInt( parts[ 2 ].trim() );
					}
					catch ( final IllegalArgumentException e )
					{
						throw new IllegalArgumentException( "Malformed count limit '" + value + "' in interest point filter '" + s + "': " + e.getMessage() );
					}

					continue;
				}

				final int dots = value.indexOf( ".." );

				if ( dots < 0 )
					throw new IllegalArgumentException( "Malformed interest point filter '" + s + "', expected key=min..max entries separated by ';'." );

				final Axis axis = Axis.forKey( key );
				final String lo = value.substring( 0, dots ).trim(), hi = value.substring( dots + 2 ).trim();

				min[ axis.ordinal() ] = lo.isEmpty() ? Double.NaN : parseBound( lo, s );
				max[ axis.ordinal() ] = hi.isEmpty() ? Double.NaN : parseBound( hi, s );
			}
		}

		return new InterestPointFilter(
				min[ Axis.RESPONSE.ordinal() ], max[ Axis.RESPONSE.ordinal() ],
				min[ Axis.INTENSITY.ordinal() ], max[ Axis.INTENSITY.ordinal() ],
				min[ Axis.SIGMA.ordinal() ], max[ Axis.SIGMA.ordinal() ],
				limitAxis, limit, limitCount );
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
	 * @return the label suffix, "_" + letter + entry per filtered axis, e.g. "_r0.004", "_r0.004-0.01", "_s1.5-3", "_r0.004_s1.5-3" (a missing lower bound is written as 0), a count limit last as "_" + the letters of its axis and mode + the count ("_rh3000" = the 3000 highest by |response|, "_im", "_sl", ...)
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

		if ( limit != null )
			sb.append( "_" ).append( limitAxis.letter ).append( limit.letter ).append( limitCount );

		return sb.toString();
	}

	/**
	 * @return the parameter string of a filtered label, e.g. "filtered from 'beads': |response| >= 0.004, sigma in [1.5, 3]" or "filtered from 'beads': the 3000 highest by |response| per view"
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

		if ( limit != null )
		{
			if ( !first )
				sb.append( ", " );

			first = false;
			sb.append( "the " ).append( limitCount ).append( " " ).append( limit.description ).append( " by " ).append( limitAxis.description ).append( " per view" );
		}

		if ( first )
			sb.append( "everything" );

		return sb.toString();
	}

	/**
	 * @return the filter per axis for the action history, e.g. { response: "0.004..", sigma: "1.5..3" } (the filtered axes only), plus { limit: "response:highest:3000" } for a count limit
	 */
	public Map< String, String > describeParameters()
	{
		final LinkedHashMap< String, String > p = new LinkedHashMap<>();

		for ( final Axis axis : Axis.values() )
			if ( filters( axis ) )
				p.put( axis.key, plainOrEmpty( min( axis ) ) + ".." + plainOrEmpty( max( axis ) ) );

		if ( limit != null )
			p.put( limitKey, limitAxis.key + ":" + limit.key + ":" + limitCount );

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
	 * @param entry - a range as a dialog entry ("a" or "a-b", parseRange) or as the canonical string of one axis ("a..b",
	 *        "a.." or "..b", toString / describeParameters), e.g. on the command line of BigStitcher-Spark
	 * @return { min, max }, NaN for an open side
	 * @throws IllegalArgumentException if malformed
	 */
	public static double[] parseAnyRange( final String entry )
	{
		final String s = entry.trim();
		final int dots = s.indexOf( ".." );

		if ( dots < 0 )
			return parseRange( s );

		final String lo = s.substring( 0, dots ).trim(), hi = s.substring( dots + 2 ).trim();
		final double min = lo.isEmpty() ? Double.NaN : parseBound( lo, s );
		final double max = hi.isEmpty() ? Double.NaN : parseBound( hi, s );

		if ( !Double.isNaN( min ) && !Double.isNaN( max ) && min > max )
			throw new IllegalArgumentException( "the lower bound " + plain( min ) + " is above the upper bound " + plain( max ) + " in '" + s + "'" );

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

		return limitAxis == other.limitAxis && limit == other.limit && limitCount == other.limitCount;
	}
}
