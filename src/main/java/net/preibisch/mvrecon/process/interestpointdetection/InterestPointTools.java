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
package net.preibisch.mvrecon.process.interestpointdetection;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessible;
import net.imglib2.RealRandomAccess;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPointsN5;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * The type Interest point tools.
 */
public class InterestPointTools
{
	public final static String warningLabel = " (WARNING: Only available for ";

	public static String[] limitDetectionChoice = { "Brightest", "Around median (of those above threshold)", "Weakest (above threshold)" };	

	public static String getSelectedLabel( final String[] labels, final int choice )
	{
		String label = labels[ choice ];

		if ( label.contains( warningLabel ) )
			label = label.substring( 0, label.indexOf( warningLabel ) );

		return label;
	}

	/**
	 * Goes through all Views and checks all available labels for interest point detection
	 * 
	 * @param spimData - the SpimData object
	 * @param viewIdsToProcess - for which viewIds
	 *
	 * @return - labels of all interest points
	 */
	public static String[] getAllInterestPointLabels(
			final SpimData2 spimData,
			final List< ? extends ViewId > viewIdsToProcess )
	{
		final ViewInterestPoints interestPoints = spimData.getViewInterestPoints();
		final HashMap< String, Integer > labels = getAllInterestPointMap( interestPoints, viewIdsToProcess );

		return getAllInterestPointLabels( labels, viewIdsToProcess );
	}

	/**
	 * @param labels - the labels and the number of views each is present in (getAllInterestPointMap)
	 * @param viewIdsToProcess - the views, a label missing in some gets a warning appended
	 * @return the labels in natural order (sortLabels), with a warning for the incomplete ones
	 */
	public static String[] getAllInterestPointLabels(
			final HashMap< String, Integer > labels,
			final List< ? extends ViewId > viewIdsToProcess )
	{
		final String[] allLabels = new String[ labels.keySet().size() ];

		int i = 0;
		
		for ( final String label : sortLabels( labels.keySet() ) )
		{
			allLabels[ i ] = label;

			if ( labels.get( label ) != viewIdsToProcess.size() )
				allLabels[ i ] += warningLabel + labels.get( label ) + "/" + viewIdsToProcess.size() + " Views!)";

			++i;
		}

		return allLabels;
	}

	/**
	 * Natural order of labels (naturalOrder): numbers inside the labels compare as numbers (with a decimal fraction if
	 * present), the text in between case-insensitively, so "beads" &lt; "beads_t0.001" &lt; "beads_t0.0046" &lt; "beads_t0.022"
	 * &lt; "beads_t0.1" and "nuclei_t2" &lt; "nuclei_t10" (plain alphabetical order gives "nuclei_t10" first)
	 *
	 * @return the labels as a new, sorted list
	 */
	public static ArrayList< String > sortLabels( final Collection< String > labels )
	{
		final ArrayList< String > sorted = new ArrayList<>( labels );
		Collections.sort( sorted, naturalOrder );
		return sorted;
	}

	/** the comparator of sortLabels, see compareNatural */
	public static final Comparator< String > naturalOrder = InterestPointTools::compareNatural;

	/** a number (with an optional decimal fraction) or a run of anything else */
	private static final Pattern numberOrText = Pattern.compile( "\\d+(\\.\\d+)?|\\D+" );

	/**
	 * Compares two strings in natural order: token by token, where a token is a number (digits with an optional decimal
	 * fraction, compared by value) or the text in between (compared ignoring case); numbers sort before text, a string
	 * that runs out of tokens first sorts first, and strings that only differ in spelling (case, leading zeros) fall back
	 * to String.compareTo so that the order is total
	 */
	public static int compareNatural( final String a, final String b )
	{
		final Matcher ma = numberOrText.matcher( a ), mb = numberOrText.matcher( b );

		while ( true )
		{
			final boolean fa = ma.find(), fb = mb.find();

			if ( !fa || !fb )
				return fa == fb ? a.compareTo( b ) : ( fa ? 1 : -1 );

			final String ta = ma.group(), tb = mb.group();
			final boolean na = Character.isDigit( ta.charAt( 0 ) ), nb = Character.isDigit( tb.charAt( 0 ) );
			final int c;

			if ( na && nb )
				c = new BigDecimal( ta ).compareTo( new BigDecimal( tb ) );
			else if ( na != nb )
				c = na ? -1 : 1;
			else
				c = ta.compareToIgnoreCase( tb );

			if ( c != 0 )
				return c;
		}
	}

	public static HashMap< String, Integer > getAllCorrespondingInterestPointMap( final ViewInterestPoints interestPoints, final Collection< ? extends ViewId > views )
	{
		final HashMap< String, Integer > labels = new HashMap< String, Integer >();

		for ( final ViewId viewId : views )
		{
			// which lists of interest points are available
			final ViewInterestPointLists lists = interestPoints.getViewInterestPointLists( viewId );

			if ( lists == null )
				continue;

			for ( final String label : lists.getHashMap().keySet() )
			{
				final InterestPoints list = lists.getInterestPointList( label );
				int count;

				if ( list.getCorrespondingInterestPointsCopy().size() > 0 )
					count = 1;
				else
					count = 0;

				if ( labels.containsKey( label ) )
					count += labels.get( label );

				labels.put( label, count );
			}
		}

		return labels;
	}

	public static HashMap< String, Integer > getAllInterestPointMap( final ViewInterestPoints interestPoints, final Collection< ? extends ViewId > views )
	{
		final HashMap< String, Integer > labels = new HashMap< String, Integer >();

		for ( final ViewId viewId : views )
		{
			// which lists of interest points are available
			final ViewInterestPointLists lists = interestPoints.getViewInterestPointLists( viewId );

			if ( lists == null )
				continue;

			for ( final String label : lists.getHashMap().keySet() )
			{
				int count = 1;

				if ( labels.containsKey( label ) )
					count += labels.get( label );

				labels.put( label, count );
			}
		}

		return labels;
	}

	/**
	 * Add interest points. Does not save the InteresPoints
	 *
	 * @param data the data
	 * @param label the label
	 * @param points the points
	 * @return the true if successful
	 */
	public static boolean addInterestPoints( final SpimData2 data, final String label, final HashMap< ViewId, List< InterestPoint > > points )
	{
		return addInterestPoints( data, label, points, "no parameters reported." );
	}

	/**
	 * Add interest points.
	 *
	 * @param data the data
	 * @param label the label
	 * @param points the points
	 * @param parameters the parameters
	 * @return the true if successful, false if interest points cannot be saved
	 */
	public static boolean addInterestPoints( final SpimData2 data, final String label, final HashMap< ViewId, List< InterestPoint > > points, final String parameters )
	{
		for ( final ViewId viewId : points.keySet() )
		{
			final InterestPoints list = InterestPoints.newInstance( data.getBasePathURI(), viewId, label );
			/*final InterestPointList list =
					new InterestPointList(
							data.getBasePath(),
							new File(
									"interestpoints", "tpId_" + viewId.getTimePointId() +
									"_viewSetupId_" + viewId.getViewSetupId() + "." + label ) );*/

			if ( parameters != null )
				list.setParameters( parameters );
			else
				list.setParameters( "" );

			list.setInterestPoints( points.get( viewId ) );
			list.setCorrespondingInterestPoints( new ArrayList< CorrespondingInterestPoints >() );

			final ViewInterestPointLists vipl = data.getViewInterestPoints().getViewInterestPointLists( viewId );
			vipl.addInterestPointList( label, list );
		}

		return true;
	}

	/**
	 * Adds a filtered label to every view: a label without points of its own that filters the base label on the fly
	 * whenever its points are requested (VirtualFilteredInterestPointsN5, the ids of the base label are kept) and has its own
	 * correspondences. An existing label of that name is replaced. Nothing is saved.
	 *
	 * @param data the data
	 * @param label the new label
	 * @param baseLabel the label it filters, must exist in every view
	 * @param filter the filter
	 * @param views the views
	 * @return true if successful
	 * @throws IllegalArgumentException if a view lacks the base label
	 */
	public static boolean addFilteredInterestPoints( final SpimData2 data, final String label, final String baseLabel, final InterestPointFilter filter, final Collection< ? extends ViewId > views )
	{
		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists vipl = data.getViewInterestPoints().getViewInterestPointLists( viewId );

			if ( !vipl.contains( baseLabel ) )
				throw new IllegalArgumentException( "Cannot create the filtered label '" + label + "': its base label '" + baseLabel + "' does not exist for view " + Group.pvid( viewId ) + "." );

			final VirtualFilteredInterestPointsN5 list = VirtualFilteredInterestPointsN5.create( data.getBasePathURI(), viewId, label, baseLabel, filter );
			list.setParameters( filter.describe( baseLabel ) );

			vipl.addInterestPointList( label, list );
		}

		return true;
	}

	/**
	 * Adds a plain label to every view that holds copies of the points of the base label that pass the filter (with the
	 * ids of the base label), e.g. for readers that do not know filtered labels. Nothing is saved.
	 *
	 * @throws IllegalArgumentException if a view lacks the base label
	 */
	public static boolean addFilteredInterestPointsCopy( final SpimData2 data, final String label, final String baseLabel, final InterestPointFilter filter, final Collection< ? extends ViewId > views )
	{
		final HashMap< ViewId, List< InterestPoint > > points = new HashMap<>();

		for ( final ViewId viewId : views )
		{
			final InterestPoints base = data.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( baseLabel );

			if ( base == null )
				throw new IllegalArgumentException( "Cannot create the label '" + label + "': its base label '" + baseLabel + "' does not exist for view " + Group.pvid( viewId ) + "." );

			points.put( viewId, filter.filter( base.getInterestPointsCopy().values() ) );
		}

		return addInterestPoints( data, label, points, filter.describe( baseLabel ) + " (copied)" );
	}

	/**
	 * @return the filtered labels of the view that depend on the label, directly or through other filtered labels (a filter of a filter), without the label itself
	 */
	public static List< String > dependentLabels( final ViewInterestPointLists vipl, final String label )
	{
		final ArrayList< String > dependants = new ArrayList<>();
		collectDependentLabels( vipl, label, dependants );
		return dependants;
	}

	protected static void collectDependentLabels( final ViewInterestPointLists vipl, final String label, final List< String > dependants )
	{
		for ( final Entry< String, InterestPoints > entry : vipl.getHashMap().entrySet() )
		{
			if ( entry.getValue() instanceof VirtualFilteredInterestPointsN5 && label.equals( ( (VirtualFilteredInterestPointsN5)entry.getValue() ).getBaseLabel() ) && !dependants.contains( entry.getKey() ) && !entry.getKey().equals( label ) )
			{
				dependants.add( entry.getKey() );
				collectDependentLabels( vipl, entry.getKey(), dependants );
			}
		}
	}

	/**
	 * Sets the intensity of every point to the value of the image at its position (getL, linearly interpolated), in the
	 * units of the image: the (raw) image the points were detected in, at the resolution of the detection
	 *
	 * @param image - the image, extended beyond its interval by the caller (a refined position can lie outside)
	 * @param points - the points, with positions in the coordinates of the image
	 */
	public static < T extends RealType< T > > void readIntensities( final RandomAccessible< T > image, final Collection< ? extends InterestPoint > points )
	{
		final RealRandomAccess< T > ra = Views.interpolate( image, new NLinearInterpolatorFactory<>() ).realRandomAccess();

		for ( final InterestPoint ip : points )
		{
			ra.setPosition( ip.getL() );
			ip.setIntensity( ra.get().getRealFloat() );
		}
	}

	/**
	 * Keeps at most maxDetections detections by |response|: the strongest (type 0), the ones around the median (1) or the
	 * weakest (2); sorts the list in place by descending |response|
	 *
	 * @throws IllegalArgumentException if the detections carry no response (NaN)
	 */
	public static List< InterestPoint > limitList( final int maxDetections, final int maxDetectionsTypeIndex, final List< InterestPoint > list )
	{
		if ( list.size() <= maxDetections )
		{
			return list;
		}
		else
		{
			if ( Double.isNaN( list.get( 0 ).getResponse() ) )
			{
				throw new IllegalArgumentException( "Cannot limit detections to " + maxDetections + ", the detections carry no response." );
			}
			else
			{
				if ( !DoGImgLib2.silent )
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Limiting detections to " + maxDetections + ", type = " + limitDetectionChoice[ maxDetectionsTypeIndex ] );

				Collections.sort( list, new Comparator< InterestPoint >()
				{

					@Override
					public int compare( final InterestPoint o1, final InterestPoint o2 )
					{
						final double v1 = Math.abs( o1.getResponse() );
						final double v2 = Math.abs( o2.getResponse() );

						if ( v1 < v2 )
							return 1;
						else if ( v1 == v2 )
							return 0;
						else
							return -1;
					}
				} );

				final ArrayList< InterestPoint > listNew = new ArrayList< InterestPoint >();

				if ( maxDetectionsTypeIndex == 0 )
				{
					// max
					for ( int i = 0; i < maxDetections; ++i )
						listNew.add( list.get( i ) );
				}
				else if ( maxDetectionsTypeIndex == 2 )
				{
					// min
					for ( int i = 0; i < maxDetections; ++i )
						listNew.add( list.get( list.size() - 1 - i ) );
				}
				else
				{
					// median
					final int median = list.size() / 2;
					
					if ( !DoGImgLib2.silent )
						IOFunctions.println( "Median |response|: " + Math.abs( list.get( median ).getResponse() ) );
					
					final int from = median - maxDetections/2;
					final int to = median + maxDetections/2;

					for ( int i = from; i <= to; ++i )
						listNew.add( list.get( list.size() - 1 - i ) );
				}
				return listNew;
			}
		}
	}

}
