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
package net.preibisch.mvrecon.process.fusion.blk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;

/**
 * Compute and cache the expanded bounding boxes of all transformed views for per-block overlap determination.
 * Sort order of {@code viewIds} is maintained for per-block queries. (for FIRST-WINS strategy)
 */
class Overlap
{
	private final List< ? extends ViewId > viewIds;

	private final long[] bb;

	private final int numDimensions;

	private final int numViews;

	public Overlap(
			final List< ? extends ViewId > viewIds,
			final Map< ? extends ViewId, ? extends AffineTransform3D > viewRegistrations,
			final Map< ? extends ViewId, ? extends Dimensions > viewDimensions,
			final int expandOverlap,
			final int numDimensions )
	{
		this.viewIds = viewIds;
		this.numDimensions = numDimensions;
		final List< Interval > bounds = new ArrayList<>();
		for ( final ViewId viewId : viewIds )
		{
			// expand to be conservative ...
			final AffineTransform3D t = viewRegistrations.get( viewId );
			final Dimensions dim = viewDimensions.get( viewId );
			final RealInterval ri = t.estimateBounds( new FinalInterval( dim ) );
			final Interval boundingBoxLocal = Intervals.largestContainedInterval( ri );
			bounds.add( Intervals.expand( boundingBoxLocal, expandOverlap ) );
		}

		numViews = bounds.size();
		bb = new long[ numViews * numDimensions * 2 ];
		for ( int i = 0; i < numViews; ++i )
			setBounds( i, bounds.get( i ) );
	}

	private void setBounds( int i, Interval interval )
	{
		final int o_min = numDimensions * ( 2 * i );
		final int o_max = numDimensions * ( 2 * i + 1 );
		for ( int d = 0; d < numDimensions; d++ )
		{
			bb[ o_min + d ] = interval.min( d );
			bb[ o_max + d ] = interval.max( d );
		}
	}

	private long boundsMin( int i, int d )
	{
		final int o_min = numDimensions * ( 2 * i );
		return bb[ o_min + d ];
	}

	private long boundsMax( int i, int d )
	{
		final int o_max = numDimensions * ( 2 * i + 1 );
		return bb[ o_max + d ];
	}

	int[] getOverlappingViewIndices( final Interval interval )
	{
		final long[] min = interval.minAsLongArray();
		final long[] max = interval.maxAsLongArray();
		return getOverlappingViewIndices( min, max );
	}

	int[] getOverlappingViewIndices( final long[] min, final long[] max )
	{
		final int[] indices = new int[ numViews ];
		int j = 0;
		for ( int i = 0; i < numViews; ++i )
			if ( isOverlapping( i, min, max ) )
				indices[ j++ ] = i;
		return Arrays.copyOf( indices, j );
	}

	List< ViewId > getOverlappingViewIds( final Interval interval )
	{
		final int[] indices = getOverlappingViewIndices( interval );
		final List< ViewId > views = new ArrayList<>();
		for ( final int i : indices )
			views.add( viewIds.get( i ) );
		return views;
	}

	private boolean isOverlapping( final int i, final long[] min, final long[] max )
	{
		for ( int d = 0; d < numDimensions; ++d )
			if ( min[ d ] > boundsMax( i, d ) || max[ d ] < boundsMin( i, d ) )
				return false;
		return true;
	}

	/**
	 * The ViewIds that are checked.
	 * <p>
	 * Elements of {@code int[]} array returned by {@link #getOverlappingViewIds(Interval)}
	 * correspond to indices into this list.
	 */
	public List< ? extends ViewId > getViewIds()
	{
		return viewIds;
	}

	public int numViews()
	{
		return numViews;
	}

	private Overlap(
			final List< ? extends ViewId > viewIds,
			final long[] bb,
			final int numDimensions )
	{
		this.viewIds = viewIds;
		this.bb = bb;
		this.numDimensions = numDimensions;
		numViews = viewIds.size();
	}

	/**
	 * Create a new {@code Overlap}, containing only those ViewIds of this
	 * {@code Overlap} which overlap the given {@code boundingBox}.
	 *
	 * @param boundingBox
	 * 		interval to check for overlap
	 *
	 * @return a new {@code Overlap}, containing only those ViewIds that overlap the given {@code boundingBox}.
	 */
	public Overlap filter( final Interval boundingBox )
	{
		final int[] indices = getOverlappingViewIndices( boundingBox );
		final int filteredNumViews = indices.length;
		final List< ViewId > filteredViews = new ArrayList<>( filteredNumViews );
		final long[] filteredBB = new long[ filteredNumViews * numDimensions * 2 ];
		for ( final int i : indices )
		{
			final int o = numDimensions * ( 2 * i );
			final int fo = numDimensions * ( 2 * filteredViews.size() );
			for ( int k = 0; k < 2 * numDimensions; ++k )
				filteredBB[ fo + k ] = bb[ o + k ];
			filteredViews.add( viewIds.get( i ) );
		}
		return new Overlap( filteredViews, filteredBB, numDimensions );
	}

	/**
	 * Return a new {@code Overlap}, with all bounding boxes shifted by the
	 * given {@code offset}.
	 *
	 * @param offset
	 * 		offset to apply
	 *
	 * @return a new {@code Overlap}, with shifted bounding boxes
	 */
	public Overlap offset( final long[] offset )
	{
		final long[] offsetBB = new long[ bb.length ];
		for ( int i = 0; i < 2 * numViews; ++i )
		{
			final int o = numDimensions * i;
			for ( int d = 0; d < numDimensions; ++d )
				offsetBB[ o + d ] = bb[ o + d ] - offset[ d ];
		}
		return new Overlap( viewIds, offsetBB, numDimensions );
	}
}
