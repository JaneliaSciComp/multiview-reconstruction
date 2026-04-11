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
package net.preibisch.mvrecon.process.boundingbox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;

public class BoundingBoxTools
{
	public static < V extends ViewId > BoundingBox maximalBoundingBox( final SpimData2 spimData, final Collection< V > viewIds, final String title )
	{
		// filter not present ViewIds
		SpimData2.filterMissingViews( spimData, viewIds );

		return new BoundingBoxMaximal( viewIds, spimData ).estimate( title );
	}

	public static BoundingBox maximalBoundingBox(
			final Collection< ? extends ViewId > views,
			final HashMap< ? extends ViewId, Dimensions > dimensions,
			final HashMap< ? extends ViewId, AffineTransform3D > registrations,
			final String title )
	{
		return new BoundingBoxMaximal( views, dimensions, registrations ).estimate( title );
	}

	public static List< BoundingBox > getAllBoundingBoxes( final SpimData2 spimData, final Collection< ViewId > currentlySelected, final boolean addBoundingBoxForAllViews )
	{
		final List< BoundingBox > bbs = new ArrayList<>();
		bbs.addAll( spimData.getBoundingBoxes().getBoundingBoxes() );

		final ArrayList< ViewId > allViews = new ArrayList<>();

		if ( currentlySelected != null && currentlySelected.size() > 0 )
		{
			allViews.addAll( currentlySelected );
			bbs.add( BoundingBoxTools.maximalBoundingBox( spimData, allViews, "Currently Selected Views" ) );
		}

		if ( addBoundingBoxForAllViews )
		{
			allViews.clear();
			allViews.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );
			bbs.add( BoundingBoxTools.maximalBoundingBox( spimData, allViews, "All Views" ) );
		}

		return bbs;
	}

	public static String printInterval( final RealInterval interval )
	{
		String out = "(Interval empty)";

		if ( interval == null || interval.numDimensions() == 0 )
			return out;

		out = "[" + interval.realMin( 0 );

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + interval.realMin( i );

		out += "] -> [" + interval.realMax( 0 );

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + interval.realMax( i );

		out += ")";

		return out;
	}

	/**
	 * Finds a bounding box by title in the SpimData2 bounding box collection.
	 *
	 * @param spimData the SpimData2 containing bounding boxes
	 * @param bbTitle the title of the bounding box to find
	 * @return the BoundingBox with the specified title, or null if not found
	 */
	public static BoundingBox getBoundingBox( final SpimData2 spimData, final String bbTitle )
	{
		BoundingBox boundingBox = null;

		for ( final BoundingBox bb : spimData.getBoundingBoxes().getBoundingBoxes() )
		{
			System.out.println( "Bounding box: " + bb.getTitle() );

			if ( bb.getTitle().equals( bbTitle ) )
				boundingBox = bb;
		}

		if ( boundingBox == null )
		{
			System.out.println( "Bounding box '" + bbTitle + "' not found." );
			return null;
		}

		return boundingBox;
	}

}
