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
package net.preibisch.mvrecon.process.interestpointdetection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointValue;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

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

	public static String[] getAllInterestPointLabels(
			final HashMap< String, Integer > labels,
			final List< ? extends ViewId > viewIdsToProcess )
	{
		final String[] allLabels = new String[ labels.keySet().size() ];

		int i = 0;
		
		for ( final String label : labels.keySet() )
		{
			allLabels[ i ] = label;

			if ( labels.get( label ) != viewIdsToProcess.size() )
				allLabels[ i ] += warningLabel + labels.get( label ) + "/" + viewIdsToProcess.size() + " Views!)";

			++i;
		}

		return allLabels;
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

	public static List< InterestPoint > limitList( final int maxDetections, final int maxDetectionsTypeIndex, final List< InterestPoint > list )
	{
		if ( list.size() <= maxDetections )
		{
			return list;
		}
		else
		{
			if ( !InterestPointValue.class.isInstance( list.get( 0 ) ) )
			{
				IOFunctions.println( "ERROR: Cannot limit detections to " + maxDetections + ", wrong instance." );
				return list;
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
						final double v1 = Math.abs( ((InterestPointValue)o1).getIntensity() );
						final double v2 = Math.abs( ((InterestPointValue)o2).getIntensity() );

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
						IOFunctions.println( "Medium intensity: " + Math.abs( ((InterestPointValue)list.get( median )).getIntensity() ) );
					
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
