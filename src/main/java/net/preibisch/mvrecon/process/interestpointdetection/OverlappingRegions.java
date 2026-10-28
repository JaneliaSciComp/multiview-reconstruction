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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.ViewSetupUtils;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;

/**
 * The regions of a view that overlap other views, for an interest point detection that runs only there ("only in
 * regions overlapping other views" of the detection dialog, {@code --overlappingOnly} of BigStitcher-Spark): the
 * points outside the overlaps serve no registration, and the regions are usually much smaller than the view.
 *
 * Geometry: a partner's full-resolution pixel box, mapped by the current registrations into the view and then into
 * the pixels of the opened (downsampled) image, intersected with that image ({@link #intersection}); one region per
 * partner, identical regions once ({@link #intervals}). Regions of different partners may overlap each other (the
 * corners of a tile grid), so the detections of the regions of a view are combined with {@link #combine}, which drops
 * a point closer than {@link #combineDistance} to one already collected (the same pixels give the same detection).
 * The partners of a view: the GUI takes the other selected views of the same timepoint that differ in tile or angle
 * ({@link #otherVolumes}; other channels and illuminations of the same volume overlap fully and would only turn the
 * regions into the whole view) whose bounding boxes intersect the view's ({@link #overlapping}); BigStitcher-Spark
 * takes all other selected views unless told otherwise. A view without a partner has no regions and gets no points.
 * No margin is added around a region.
 *
 * The detections themselves: {@code DoG.addInterestPoints} and {@code ScaleSpace.addInterestPoints} run one call per
 * region when {@code InterestPointParameters.regions} is set (the DoG with the region expanded by one pixel, since its
 * peak finder skips the outermost pixel, and with the intensity range of the whole view, since it would otherwise
 * normalize every region on its own); BigStitcher-Spark cuts the regions into blocks of its own.
 *
 * @author Stephan Preibisch
 */
public class OverlappingRegions
{
	/** the distance (in the units of the combined points, full-resolution pixels for both callers) below which a detection of one region is a duplicate of one already collected from another region of the same view */
	public static double combineDistance = 0.5;

	private final SpimData data;

	/** the partners per view (keys are plain ViewIds) */
	private final Map< ViewId, List< ViewId > > partners;

	/**
	 * @param data - the dataset (registrations and sizes)
	 * @param partners - the partners per view; a view without an entry or with an empty list has no regions
	 */
	public OverlappingRegions( final SpimData data, final Map< ViewId, List< ViewId > > partners )
	{
		this.data = data;
		this.partners = new HashMap<>();

		for ( final Map.Entry< ViewId, List< ViewId > > entry : partners.entrySet() )
			this.partners.put( plain( entry.getKey() ), new ArrayList<>( entry.getValue() ) );
	}

	/**
	 * The regions the GUI uses: for every view the other given views of the same timepoint that differ in tile or
	 * angle ({@link #otherVolumes}) and whose bounding boxes intersect the view's ({@link #overlapping}). Views without
	 * a partner are logged (they get no points).
	 *
	 * @param data - the dataset
	 * @param views - the views the detection runs on
	 * @return the regions
	 */
	public static OverlappingRegions forOtherVolumes( final SpimData data, final Collection< ? extends ViewId > views )
	{
		final Map< ViewId, List< ViewId > > partners = new HashMap<>();
		final List< String > without = new ArrayList<>();

		for ( final ViewId view : views )
		{
			final ViewId key = plain( view );
			final List< ViewId > p = overlapping( data, key, otherVolumes( data, key, views ) );

			partners.put( key, p );

			if ( p.isEmpty() )
				without.add( Group.pvid( key ) );
		}

		if ( !without.isEmpty() )
			IOFunctions.println( "WARNING: " + without.size() + " of " + views.size() + " views overlap no other volume (another tile or angle of the same timepoint) among the selected views, they get no interest points: " + without );

		return new OverlappingRegions( data, partners );
	}

	/** @return the partners of a view, empty if it has none */
	public List< ViewId > partners( final ViewId view )
	{
		final List< ViewId > p = partners.get( plain( view ) );
		return p == null ? new ArrayList<>() : p;
	}

	/**
	 * @param view - the view
	 * @param imageInterval - the interval of the opened (downsampled) image of the view
	 * @param mipmapTransform - the transform of the opened image to the full-resolution pixels of the view (DownsampleTools.openAndDownsample)
	 * @return the regions of the view in the pixels of the opened image, empty if it has no partner or none overlaps the image
	 */
	public List< Interval > intervals( final ViewId view, final Interval imageInterval, final AffineTransform3D mipmapTransform )
	{
		return intervals( data, view, partners( view ), imageInterval, mipmapTransform );
	}

	/**
	 * The partner rule of the GUI: the candidates of the same timepoint as the view whose tile or angle differs from the
	 * view's (a missing attribute counts as equal), the view itself excluded. Other channels and illuminations of the
	 * same tile and angle see the same volume.
	 *
	 * @param data - the dataset
	 * @param view - the view
	 * @param candidates - e.g. the selected views
	 * @return the other volumes among the candidates, in their order
	 */
	public static List< ViewId > otherVolumes( final SpimData data, final ViewId view, final Collection< ? extends ViewId > candidates )
	{
		final ViewSetup vs = setup( data, view );
		final Integer tile = tileId( vs ), angle = angleId( vs );
		final List< ViewId > others = new ArrayList<>();

		for ( final ViewId candidate : candidates )
		{
			if ( candidate.getTimePointId() != view.getTimePointId() || candidate.getViewSetupId() == view.getViewSetupId() )
				continue;

			final ViewSetup cs = setup( data, candidate );

			if ( Objects.equals( tileId( cs ), tile ) && Objects.equals( angleId( cs ), angle ) )
				continue;

			others.add( plain( candidate ) );
		}

		return others;
	}

	/**
	 * @param data - the dataset
	 * @param view - the view
	 * @param candidates - the views to test
	 * @return the candidates (other than the view) whose bounding box in world coordinates (the current registration,
	 *         SimpleBoundingBoxOverlap) intersects the view's, in their order; empty if the view's size is unknown
	 */
	public static List< ViewId > overlapping( final SpimData data, final ViewId view, final Collection< ? extends ViewId > candidates )
	{
		final List< ViewId > result = new ArrayList<>();
		final BoundingBox box = boundingBox( data, view );

		if ( box == null )
			return result;

		for ( final ViewId candidate : candidates )
		{
			if ( candidate.getTimePointId() == view.getTimePointId() && candidate.getViewSetupId() == view.getViewSetupId() )
				continue;

			final BoundingBox other = boundingBox( data, candidate );

			if ( other != null && SimpleBoundingBoxOverlap.overlaps( box, other ) )
				result.add( plain( candidate ) );
		}

		return result;
	}

	/**
	 * The region of a view that a partner covers: the partner's full-resolution pixel box {@code [0, dim-1]} mapped by
	 * {@code mipmap^-1 * regView^-1 * regPartner} (the current registrations, the view's mipmap transform) into the
	 * pixels of the opened image of the view, as the smallest containing integer interval, intersected with the image.
	 *
	 * @param data - the dataset
	 * @param view - the view
	 * @param partner - the other view
	 * @param imageInterval - the interval of the opened (downsampled) image of the view
	 * @param mipmapTransform - the transform of the opened image to the full-resolution pixels of the view
	 * @return the region in the pixels of the opened image, null if the partner does not reach into the image
	 */
	public static Interval intersection( final SpimData data, final ViewId view, final ViewId partner, final Interval imageInterval, final AffineTransform3D mipmapTransform )
	{
		final Dimensions dim = dimensions( data, partner );

		if ( dim == null )
			return null;

		// partner pixels -> world -> view pixels -> pixels of the opened image of the view
		final AffineTransform3D t = model( data, partner ).copy();
		t.preConcatenate( model( data, view ).inverse() );
		t.preConcatenate( mipmapTransform.inverse() );

		final Interval box = Intervals.smallestContainingInterval( t.estimateBounds( new FinalInterval( dim ) ) );
		final Interval region = Intervals.intersect( box, imageInterval );

		return Intervals.isEmpty( region ) ? null : region;
	}

	/**
	 * @param data - the dataset
	 * @param view - the view
	 * @param partners - its partners
	 * @param imageInterval - the interval of the opened (downsampled) image of the view
	 * @param mipmapTransform - the transform of the opened image to the full-resolution pixels of the view
	 * @return the regions ({@link #intersection}) of the partners that reach into the image, identical ones once, in the partners' order
	 */
	public static List< Interval > intervals( final SpimData data, final ViewId view, final Collection< ? extends ViewId > partners, final Interval imageInterval, final AffineTransform3D mipmapTransform )
	{
		final List< Interval > regions = new ArrayList<>();

		for ( final ViewId partner : partners )
		{
			final Interval region = intersection( data, view, partner, imageInterval, mipmapTransform );

			if ( region == null )
				continue;

			boolean known = false;

			for ( final Interval r : regions )
				if ( Intervals.equals( r, region ) )
					known = true;

			if ( !known )
				regions.add( region );
		}

		return regions;
	}

	/**
	 * Combines the detections of the regions of one view: the points of the first non-empty list, then of every further
	 * list the points that are not within {@code combineDistance} of a point already collected (a KD tree over the
	 * collected points per list, as BigStitcher-Spark does); the result gets ids 0..n-1 in that order, the attributes
	 * (response, sigma, intensity) travel with the points. The lists are not changed.
	 *
	 * @param perRegion - the detections per region, in the same units (e.g. full-resolution pixels)
	 * @param combineDistance - the distance below which a point is a duplicate, in those units
	 * @return the combined points with new ids
	 */
	public static ArrayList< InterestPoint > combine( final List< ? extends List< InterestPoint > > perRegion, final double combineDistance )
	{
		final ArrayList< InterestPoint > collected = new ArrayList<>();

		for ( final List< InterestPoint > points : perRegion )
		{
			if ( points.isEmpty() )
				continue;

			if ( collected.isEmpty() )
			{
				collected.addAll( points );
				continue;
			}

			final NearestNeighborSearchOnKDTree< InterestPoint > search = new NearestNeighborSearchOnKDTree<>( new KDTree<>( collected, collected ) );
			final ArrayList< InterestPoint > kept = new ArrayList<>();

			for ( final InterestPoint point : points )
			{
				search.search( point );

				if ( search.getDistance() > combineDistance )
					kept.add( point );
			}

			collected.addAll( kept );
		}

		final ArrayList< InterestPoint > result = new ArrayList<>( collected.size() );

		for ( int id = 0; id < collected.size(); ++id )
			result.add( collected.get( id ).derive( id, collected.get( id ).getL() ) );

		return result;
	}

	/** @return the number of points of all lists */
	public static int size( final List< ? extends List< InterestPoint > > perRegion )
	{
		int n = 0;

		for ( final List< InterestPoint > points : perRegion )
			n += points.size();

		return n;
	}

	static ViewId plain( final ViewId view )
	{
		return new ViewId( view.getTimePointId(), view.getViewSetupId() );
	}

	static ViewSetup setup( final SpimData data, final ViewId view )
	{
		return data.getSequenceDescription().getViewDescription( view ).getViewSetup();
	}

	static Integer tileId( final ViewSetup vs )
	{
		final Tile tile = vs.getTile();
		return tile == null ? null : tile.getId();
	}

	static Integer angleId( final ViewSetup vs )
	{
		final Angle angle = vs.getAngle();
		return angle == null ? null : angle.getId();
	}

	/** @return the full-resolution size of a view (the setup's, else loaded), null if unknown */
	static Dimensions dimensions( final SpimData data, final ViewId view )
	{
		final ViewDescription vd = data.getSequenceDescription().getViewDescription( view );
		return ViewSetupUtils.getSizeOrLoad( vd.getViewSetup(), vd.getTimePoint(), data.getSequenceDescription().getImgLoader() );
	}

	/** @return the current model of a view (full-resolution pixels to world) */
	static AffineTransform3D model( final SpimData data, final ViewId view )
	{
		final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( view );
		vr.updateModel();
		return vr.getModel();
	}

	/** @return the bounding box of a view in world coordinates (SimpleBoundingBoxOverlap), null if its size is unknown */
	static BoundingBox boundingBox( final SpimData data, final ViewId view )
	{
		final Dimensions dim = dimensions( data, view );
		return dim == null ? null : SimpleBoundingBoxOverlap.getBoundingBox( dim, model( data, view ) );
	}
}
