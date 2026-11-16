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
package net.preibisch.mvrecon.fiji.plugin.interestpointdetection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;

/**
 * Which detection the points of a label were found with, for a label that was not detected itself but made from the
 * points of another one: thinned out (ThinOut, RelativeThinOut), cleaned manually (InteractiveProjections), the points
 * with correspondences (CreateInterestPointsFromCorrespondences), or a copy of the points that pass a filter
 * (InterestPointTools.addFilteredInterestPointsCopy). Such a label holds copies of the points, with their positions and
 * attributes; what needs the scale of their detection (the embedding, {@code DetectionScale}) has to look it up.
 *
 * Two sources (Stephan, 2026-11-16), in this order:
 * <ul>
 * <li>the label itself: since 2026-11-16 these tools write the parameters of the detection of the points behind what
 * they say about the label ({@link #derivedParameters}), e.g.
 * {@code thinned-out 'nuclei', kept range from 0.0 to 25.0 | detection: DOG s=1.8 t=0.008 ...}. The label then does not
 * depend on what happens to the label it was made from (detected again, removed);</li>
 * <li>the label that its parameters name ({@link #sourceLabel}), in the same view, and so on until a label is reached
 * that names none: the labels made before that only say "thinned-out 'nuclei', ...". A name is not a detection: the
 * named label may have been detected again since, with other parameters and other points, so parameters that were found
 * through a name ({@link #byName}) only count while the points of the label are still points of the label they were
 * taken from ({@link #pointsAmong}; the embedding refuses otherwise, and a tool then records nothing).</li>
 * </ul>
 * A filtered label (VirtualFilteredInterestPoints) is neither: it has no points of its own and filters its base label
 * whenever it is read, so it always has the detection of its base.
 *
 * @param label the label the chain ends in: the first one, starting from the label asked for, that records the detection
 *        of its points or names no other label
 * @param parameters the parameters of the detection if that label records them, otherwise its parameters string (the
 *        one of a detection if it was detected, see {@link #isDetection})
 * @param derived the labels from the one that was asked for up to, not including, {@code label}; empty if it is the label itself
 * @param byName whether a label on the way was followed by the name its parameters give (not only filtered labels to their base)
 *
 * @author Stephan Preibisch
 */
public record DetectionOrigin( String label, String parameters, List< String > derived, boolean byName )
{
	/** what separates what a derived label says about itself from the parameters of the detection of its points */
	public static final String DETECTION = " | detection: ";

	/** the first token of the parameters of pcmatch's learned detector (DetectionScale reads its {@code sigma=}) */
	public static final String LEARNED = "LEARNED";

	/**
	 * What the tools say about a label they made out of the points of another one, the label they name is the group: thinned
	 * out (ThinOut, RelativeThinOut), cleaned manually (InteractiveProjections), the points with correspondences
	 * (CreateInterestPointsFromCorrespondences), and a filtered label or its plain copy (InterestPointFilter.describe)
	 */
	private static final Pattern[] DERIVED = {
			Pattern.compile( "thinned-out '(.*)', (?:kept|removed) range from .*", Pattern.DOTALL ),
			Pattern.compile( "manually removed detections from '(.*)'", Pattern.DOTALL ),
			Pattern.compile( "correspondences from '(.*)'", Pattern.DOTALL ),
			Pattern.compile( "filtered from '(.*?)': .*", Pattern.DOTALL ) };

	/** @return what a parameters string says about the label itself: the string without the detection it records ({@link #DETECTION}) */
	public static String description( final String parameters )
	{
		if ( parameters == null )
			return null;

		final int at = parameters.indexOf( DETECTION );

		return at < 0 ? parameters : parameters.substring( 0, at );
	}

	/** @return the parameters of the detection that a derived label records for its points (what follows {@link #DETECTION}), null if it records none */
	public static String recordedDetection( final String parameters )
	{
		if ( parameters == null )
			return null;

		final int at = parameters.indexOf( DETECTION );

		return at < 0 ? null : parameters.substring( at + DETECTION.length() );
	}

	/**
	 * @return the label that the parameters string of a derived label names as the one its points were taken from (e.g.
	 *         nuclei for "thinned-out 'nuclei', kept range from 0.0 to 25.0"), null if the string names none (a
	 *         detection, a manual label, anything else)
	 */
	public static String sourceLabel( final String parameters )
	{
		if ( parameters == null )
			return null;

		final String description = description( parameters ).trim();

		for ( final Pattern pattern : DERIVED )
		{
			final Matcher matcher = pattern.matcher( description );

			if ( matcher.matches() )
				return matcher.group( 1 );
		}

		return null;
	}

	/** @return whether a parameters string is one of a detection: the DoG, the scale space ({@link StoredDetectionParameters#parse}) or pcmatch's learned detector */
	public static boolean isDetection( final String parameters )
	{
		return parameters != null && ( StoredDetectionParameters.parse( parameters ) != null || parameters.trim().split( "\\s+" )[ 0 ].equals( LEARNED ) );
	}

	/** @return the chain as text, e.g. "'thinned-out' <- 'nuclei'"; just the label if nothing was followed */
	public String chain()
	{
		final StringBuilder sb = new StringBuilder();
		for ( final String d : derived )
			sb.append( "'" ).append( d ).append( "' <- " );
		return sb.append( "'" ).append( label ).append( "'" ).toString();
	}

	/**
	 * Follows a label of a view to where the detection of its points is found: a filtered label leads to its base label;
	 * a label that records the detection of its points ends the chain; a label whose parameters only name the label it
	 * was made from ({@link #sourceLabel}) leads to that label of the same view; a label that does none of this ends the
	 * chain, too (its string is the detection's if it was detected; a manual label or the fake points of a split are for
	 * the caller to refuse, {@link #isDetection}). See the class description for what {@link #byName} means for the caller.
	 *
	 * @param lists the labels of the view
	 * @param label the label
	 * @throws IllegalArgumentException if the view has no such label, a label on the way is not in the view (any more),
	 *         or the labels name each other in a circle
	 */
	public static DetectionOrigin of( final ViewInterestPointLists lists, final String label )
	{
		final String view = "tpId=" + lists.getTimePointId() + " setupId=" + lists.getViewSetupId();
		final ArrayList< String > derived = new ArrayList<>();
		boolean byName = false;
		String current = label;

		while ( true )
		{
			final InterestPoints list = lists.getInterestPointList( current );

			if ( list == null )
			{
				if ( derived.isEmpty() )
					throw new IllegalArgumentException( view + " has no interest points '" + label + "'." );

				final String last = derived.get( derived.size() - 1 );
				throw new IllegalArgumentException( "The interest points '" + last + "' were derived from '" + current + "' (parameters of '" + last + "': '" + lists.getInterestPointList( last ).getParameters()
						+ "'), and " + view + " has no interest points '" + current + "' (any more): the parameters of the detection they come from cannot be looked up." );
			}

			final String next;

			if ( list instanceof VirtualFilteredInterestPoints )
			{
				next = ( (VirtualFilteredInterestPoints) list ).getBaseLabel();
			}
			else
			{
				final String recorded = recordedDetection( list.getParameters() );

				if ( recorded != null )
					return new DetectionOrigin( current, recorded, derived, byName );

				next = sourceLabel( list.getParameters() );

				if ( next == null )
					return new DetectionOrigin( current, list.getParameters(), derived, byName );

				byName = true;
			}

			derived.add( current );

			if ( derived.contains( next ) )
				throw new IllegalArgumentException( "The interest points '" + label + "' of " + view + " are derived from each other in a circle (" + new DetectionOrigin( next, null, derived, byName ).chain() + "): there is no detection they come from." );

			current = next;
		}
	}

	/** a position as the store keeps it (float32) */
	private record Position( float x, float y, float z )
	{
		Position( final InterestPoint p ) { this( (float) p.getL()[ 0 ], (float) p.getL()[ 1 ], (float) p.getL()[ 2 ] ); }
	}

	/**
	 * @return how many of the points are points of the other label: found at the same position (as the store keeps it,
	 *         float32). The tools that derive a label keep the positions, so all points of a derived label are among
	 *         those of the label they were taken from, unless that label holds other points by now.
	 */
	public static int pointsAmong( final Collection< ? extends InterestPoint > points, final Collection< ? extends InterestPoint > others )
	{
		final HashSet< Position > positions = new HashSet<>();
		for ( final InterestPoint p : others )
			positions.add( new Position( p ) );

		int found = 0;
		for ( final InterestPoint p : points )
			if ( positions.contains( new Position( p ) ) )
				++found;

		return found;
	}

	/**
	 * @return the parameters of the detection that the points of a label of a view were found with, null if that is not
	 *         known: the chain ends in a label that was not detected or is broken, or it was followed by a name and the
	 *         points are not (all) points of the label it ends in any more (that label was detected again since). Loads
	 *         the points of both labels in that case.
	 */
	public static String detection( final ViewInterestPointLists lists, final String label )
	{
		try
		{
			final DetectionOrigin origin = of( lists, label );

			if ( !isDetection( origin.parameters() ) )
				return null;

			if ( origin.byName() )
			{
				final Collection< InterestPoint > points = lists.getInterestPointList( label ).getInterestPointsCopy().values();

				if ( pointsAmong( points, lists.getInterestPointList( origin.label() ).getInterestPointsCopy().values() ) < points.size() )
					return null;
			}

			return origin.parameters();
		}
		catch ( final RuntimeException e )
		{
			// a label on the way is gone or cannot be read: there is nothing reliable to say
			return null;
		}
	}

	/**
	 * The parameters string of a label that is made from points of another label of the view: what the tool says about it
	 * (which names that label, e.g. "thinned-out 'nuclei', kept range from 0.0 to 25.0"), followed by the parameters of
	 * the detection these points were found with ({@link #DETECTION}), if that is known ({@link #detection}). Call it
	 * before the new label is added to the view (it may replace the label it is made from).
	 *
	 * @param lists the labels of the view
	 * @param sourceLabel the label the points are taken from
	 * @param description what the tool says about the new label
	 */
	public static String derivedParameters( final ViewInterestPointLists lists, final String sourceLabel, final String description )
	{
		final String detection = detection( lists, sourceLabel );

		return detection == null ? description : description + DETECTION + detection;
	}
}
