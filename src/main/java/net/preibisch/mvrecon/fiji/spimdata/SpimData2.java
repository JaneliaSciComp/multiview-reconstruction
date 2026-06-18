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

import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.URITools;

/**
 * Extends the {@link SpimData} class; has additonally detections
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
public class SpimData2 extends SpimData
{
	private ViewInterestPoints viewsInterestPoints;
	private BoundingBoxes boundingBoxes;
	private PointSpreadFunctions pointSpreadFunctions;
	private StitchingResults stitchingResults;
	private IntensityAdjustments intensityAdjustments;
	public boolean gridMoveRequested = false;

	/*
	// only for compatibility with depending packages, remove at some point
	public SpimData2(
			final File basePath,
			final SequenceDescription sequenceDescription,
			final ViewRegistrations viewRegistrations,
			final ViewInterestPoints viewsInterestPoints,
			final BoundingBoxes boundingBoxes,
			final PointSpreadFunctions pointSpreadFunctions,
			final StitchingResults stitchingResults )
	{
		this( basePath, sequenceDescription, viewRegistrations, viewsInterestPoints, boundingBoxes, pointSpreadFunctions, stitchingResults, new IntensityAdjustments() );
	}
	*/

	public SpimData2(
			final URI basePathUri,
			final SequenceDescription sequenceDescription,
			final ViewRegistrations viewRegistrations,
			final ViewInterestPoints viewsInterestPoints,
			final BoundingBoxes boundingBoxes,
			final PointSpreadFunctions pointSpreadFunctions,
			final StitchingResults stitchingResults,
			final IntensityAdjustments intensityAdjustments )
	{
		super( basePathUri, sequenceDescription, viewRegistrations );

		this.viewsInterestPoints = viewsInterestPoints;
		this.boundingBoxes = boundingBoxes;
		this.pointSpreadFunctions = pointSpreadFunctions;
		this.stitchingResults = stitchingResults;
		this.intensityAdjustments = intensityAdjustments;
	}

	protected SpimData2()
	{}

	public ViewInterestPoints getViewInterestPoints() { return viewsInterestPoints; }
	public BoundingBoxes getBoundingBoxes() { return boundingBoxes; }
	public PointSpreadFunctions getPointSpreadFunctions() { return pointSpreadFunctions; }
	public StitchingResults getStitchingResults() { return stitchingResults; }
	public  IntensityAdjustments getIntensityAdjustments() { return intensityAdjustments; }

	protected void setViewsInterestPoints( final ViewInterestPoints viewsInterestPoints )
	{
		this.viewsInterestPoints = viewsInterestPoints;
	}

	protected void setBoundingBoxes( final BoundingBoxes boundingBoxes )
	{
		this.boundingBoxes = boundingBoxes;
	}

	protected void setPointSpreadFunctions( final PointSpreadFunctions pointSpreadFunctions )
	{
		this.pointSpreadFunctions = pointSpreadFunctions;
	}

	protected void setStitchingResults( final StitchingResults sr )
	{
		this.stitchingResults = sr;
	}

	protected void setIntensityAdjustments( final IntensityAdjustments intensityAdjustments )
	{
		this.intensityAdjustments = intensityAdjustments;
	}

	/**
	 * @param seqDesc the sequence description
	 * @param t  - the timepoint
	 * @param c - the channel
	 * @param a - the angle
	 * @param i - the illumination
	 * @param x - the tile
	 * @return - the ViewId that fits to timepoint, angle, channel &amp; illumination by ID (or null if it does not exist)
	 */
	public static ViewId getViewId(
			final SequenceDescription seqDesc,
			final TimePoint t,
			final Channel c,
			final Angle a,
			final Illumination i,
			final Tile x )
	{
		final ViewSetup viewSetup = getViewSetup( seqDesc.getViewSetupsOrdered(), c, a, i, x );
		
		if ( viewSetup == null )
			return null;
		else
			return new ViewId( t.getId(), viewSetup.getId() );
	}

	public static ViewSetup getViewSetup( final List< ? extends ViewSetup > list, final Channel c, final Angle a, final Illumination i, final Tile x )
	{
		for ( final ViewSetup viewSetup : list )
		{
			if ( viewSetup.getAngle().getId() == a.getId() && 
				 viewSetup.getChannel().getId() == c.getId() && 
				 viewSetup.getIllumination().getId() == i.getId() &&
				 viewSetup.getTile().getId() == x.getId() )
			{
				return viewSetup;
			}
		}

		return null;
	}

	public static ArrayList< ViewSetup > getAllViewSetupsSorted( final SpimData data, final Collection< ? extends ViewId > viewIds )
	{
		final HashSet< ViewSetup > setups = new HashSet< ViewSetup >();

		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( viewId );
			final ViewSetup setup = vd.getViewSetup();

			if ( vd.isPresent() )
				setups.add( setup );
		}

		final ArrayList< ViewSetup > setupList = new ArrayList< ViewSetup >();
		setupList.addAll( setups );
		Collections.sort( setupList );

		return setupList;
	}

	public static ArrayList< ViewId > getAllViewIdsSorted( final SpimData data, final List< ? extends ViewSetup > setups, final List< ? extends TimePoint > tps )
	{
		final ArrayList< ViewId > viewIds = new ArrayList< ViewId >();

		for ( final TimePoint tp : tps )
			for ( final ViewSetup vs : setups )
			{
				final ViewId v = new ViewId( tp.getId(), vs.getId() );
				final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
				
				if ( vd.isPresent() )
					viewIds.add( vd );
			}

		Collections.sort( viewIds );

		return viewIds;
	}

	public static ArrayList< ViewDescription > getAllViewDescriptionsSorted( final SpimData data, final List< ? extends ViewId > viewIds )
	{
		final ArrayList< ViewDescription > vds = new ArrayList< ViewDescription >();

		for ( final ViewId v : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );

			if ( vd.isPresent() )
				vds.add( vd );
		}

		Collections.sort( vds );

		return vds;
	}

	public static ArrayList< Angle > getAllAnglesForChannelTimepointSorted( final SpimData data, final Collection< ? extends ViewId > viewIds, final Channel c, final TimePoint t )
	{
		final HashSet< Angle > angleSet = new HashSet< Angle >();

		for ( final ViewId v : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
			
			if ( vd.isPresent() && v.getTimePointId() == t.getId() && vd.getViewSetup().getChannel().getId() == c.getId() )
				angleSet.add( vd.getViewSetup().getAngle() );
		}

		final ArrayList< Angle > angles = new ArrayList< Angle >();
		angles.addAll( angleSet );
		Collections.sort( angles );

		return angles;
	}

	public static ArrayList< Angle > getAllAnglesSorted( final SpimData data, final Collection< ? extends ViewId > viewIds )
	{
		final HashSet< Angle > angleSet = new HashSet< Angle >();

		for ( final ViewId v : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
			
			if ( vd.isPresent() )
				angleSet.add( vd.getViewSetup().getAngle() );
		}

		final ArrayList< Angle > angles = new ArrayList< Angle >();
		angles.addAll( angleSet );
		Collections.sort( angles );

		return angles;
	}
	
	
	public static ArrayList< Tile > getAllTilesSorted( final SpimData data, final Collection< ? extends ViewId > viewIds )
	{
		final HashSet< Tile > tileSet = new HashSet<>();

		for ( final ViewId v : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
			
			if ( vd.isPresent() )
				tileSet.add( vd.getViewSetup().getTile() );
		}

		final ArrayList< Tile > tiles = new ArrayList<>();
		tiles.addAll( tileSet );
		Collections.sort( tiles );

		return tiles;
	}

	public static ArrayList< Illumination > getAllIlluminationsForChannelTimepointSorted( final SpimData data, final Collection< ? extends ViewId > viewIds, final Channel c, final TimePoint t )
	{
		final HashSet< Illumination > illumSet = new HashSet< Illumination >();

		for ( final ViewId v : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
			
			if ( vd.isPresent() && v.getTimePointId() == t.getId() && vd.getViewSetup().getChannel().getId() == c.getId() )
				illumSet.add( vd.getViewSetup().getIllumination() );
		}

		final ArrayList< Illumination > illums = new ArrayList< Illumination >();
		illums.addAll( illumSet );
		Collections.sort( illums );

		return illums;
	}

	public static ArrayList< Illumination > getAllIlluminationsSorted( final SpimData data, final Collection< ? extends ViewId > viewIds )
	{
		final HashSet< Illumination > illumSet = new HashSet< Illumination >();

		for ( final ViewId v : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
			
			if ( vd.isPresent() )
				illumSet.add( vd.getViewSetup().getIllumination() );
		}

		final ArrayList< Illumination > illums = new ArrayList< Illumination >();
		illums.addAll( illumSet );
		Collections.sort( illums );

		return illums;
	}
	
	public static <E extends Entity> ArrayList<E> getAllInstancesOfEntitySorted(final AbstractSpimData<?> data, final Collection< ? extends ViewId > viewIds, Class<E> cl)
	{
		final HashSet<E> resultSet = new HashSet<>();
		
		for (ViewId v : viewIds)
		{
			final BasicViewDescription<?> vd = data.getSequenceDescription().getViewDescriptions().get( v );
			
			if ( vd.isPresent() )
			{
				if (BasicViewSetup.class.isAssignableFrom( cl ))
					resultSet.add( (E) vd.getViewSetup() );
				else if (cl.equals( TimePoint.class ))
					resultSet.add( (E) vd.getTimePoint() );
				else
					resultSet.add( vd.getViewSetup().getAttribute( cl ) );
			}
		}
		
		final ArrayList< E > res = new ArrayList<>();
		res.addAll( resultSet );
		Collections.sort(res, new Comparator<E>(){

			@Override
			public int compare(E o1, E o2)
			{
				return o1.getId() - o2.getId();
			}
			
		});

		return res;
	}

	public static ArrayList< Channel > getAllChannelsSorted( final SpimData data, final Collection< ? extends ViewId > viewIds )
	{
		final HashSet< Channel > channelSet = new HashSet< Channel >();

		for ( final ViewId v : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
			
			if ( vd.isPresent() )
				channelSet.add( vd.getViewSetup().getChannel() );
		}

		final ArrayList< Channel > channels = new ArrayList< Channel >();
		channels.addAll( channelSet );
		Collections.sort( channels );

		return channels;
	}

	public static ArrayList< ViewDescription > getAllViewIdsForChannelSorted( final SpimData data, final Collection< ? extends ViewId > viewIds, final Channel channel )
	{
		final ArrayList< ViewDescription > views = new ArrayList< ViewDescription >();

		for ( final ViewId id : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( id );
			
			if ( vd.isPresent() && vd.getViewSetup().getChannel().getId() == channel.getId() )
				views.add( vd );
		}

		Collections.sort( views );

		return views;
	}

	public static ArrayList< ViewDescription > getAllViewIdsForChannelTimePointSorted( final SpimData data, final Collection< ? extends ViewId > viewIds, final Channel channel, final TimePoint timePoint )
	{
		final ArrayList< ViewDescription > views = new ArrayList< ViewDescription >();

		for ( final ViewId id : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( id );
			
			if ( vd.isPresent() && vd.getViewSetup().getChannel().getId() == channel.getId() && id.getTimePointId() == timePoint.getId() )
				views.add( vd );
		}

		Collections.sort( views );

		return views;
	}

	public static ArrayList< ViewDescription > getAllViewIdsForTimePointSorted( final SpimData data, final Collection< ? extends ViewId > viewIds, final TimePoint timepoint )
	{
		final ArrayList< ViewDescription > views = new ArrayList< ViewDescription >();

		for ( final ViewId id : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( id );
			
			if ( vd.isPresent() && vd.getTimePointId() == timepoint.getId() )
				views.add( vd );
		}

		Collections.sort( views );

		return views;
	}

	public static ArrayList< ViewSetup> getAllViewSetups( final Collection< ? extends ViewDescription >  vds )
	{
		final HashSet< ViewSetup > set = new HashSet< ViewSetup >();

		for ( final ViewDescription vd : vds )
			if ( vd.isPresent() )
				set.add( vd.getViewSetup() );

		final ArrayList< ViewSetup > setups = new ArrayList< ViewSetup >();
		setups.addAll( set );
		Collections.sort( setups );

		return setups;
	}

	public static ArrayList< Integer > getAllTimePointsSortedUnchecked( final Collection< ? extends ViewId > viewIds )
	{
		final HashSet< Integer > timepointSet = new HashSet< Integer >();

		for ( final ViewId vd : viewIds )
			timepointSet.add( vd.getTimePointId() );

		final ArrayList< Integer > timepoints = new ArrayList< Integer >();
		timepoints.addAll( timepointSet );
		Collections.sort( timepoints );

		return timepoints;
	}

	public static ArrayList< TimePoint > getAllTimePointsSorted( final SpimData data, final Collection< ? extends ViewId > viewIds )
	{
		final ArrayList< ViewDescription > vds = new ArrayList< ViewDescription >();

		for ( final ViewId v : viewIds )
			vds.add( data.getSequenceDescription().getViewDescription( v ) );

		return getAllTimePointsSorted( vds );
	}

	public static ArrayList< TimePoint > getAllTimePointsSorted( final Collection< ? extends ViewDescription > vds )
	{
		final HashSet< TimePoint > timepointSet = new HashSet< TimePoint >();

		for ( final ViewDescription vd : vds )
			if ( vd.isPresent() )
				timepointSet.add( vd.getTimePoint() );

		final ArrayList< TimePoint > timepoints = new ArrayList< TimePoint >();
		timepoints.addAll( timepointSet );
		Collections.sort( timepoints,
				new Comparator< TimePoint >()
				{
					@Override
					public int compare( final TimePoint o1, final TimePoint o2 )
					{
						return o1.getId() - o2.getId();
					}
				});

		return timepoints;
	}

	public static ArrayList< Integer > getAllTimePointsSortedWithoutPresenceCheck( final Collection< ? extends ViewId > vds )
	{
		final HashSet< Integer > timepointSet = new HashSet< Integer >();

		for ( final ViewId vd : vds )
			timepointSet.add( vd.getTimePointId() );

		final ArrayList< Integer > timepoints = new ArrayList< Integer >();
		timepoints.addAll( timepointSet );
		Collections.sort( timepoints );

		return timepoints;
	}

	public static SpimData2 convert( final SpimData data1 )
	{
		final SequenceDescription s = data1.getSequenceDescription();
		final ViewRegistrations vr = data1.getViewRegistrations();
		final ViewInterestPoints vipl = new ViewInterestPoints();
		//vipl.createViewInterestPoints( data1.getSequenceDescription().getViewDescriptions() );
		final BoundingBoxes bb = new BoundingBoxes();
		final PointSpreadFunctions psfs = new PointSpreadFunctions();
		final StitchingResults sr = new StitchingResults();
		final IntensityAdjustments ia = new IntensityAdjustments();

		return new SpimData2( data1.getBasePathURI(), s, vr, vipl, bb, psfs, sr, ia );
	}

	/**
	 * Removes all missing views from the list, be careful, it modifies the collection!
	 *
	 * @param data - the spimdata object
	 * @param viewIds - the views
	 * @param <V> - something extending ViewId
	 * @return those who were removed
	 */
	public static < V extends ViewId > List< V > filterMissingViews( final AbstractSpimData< ? > data, final Collection< V > viewIds )
	{
		return filterMissingViews( data.getSequenceDescription(), viewIds );
	}

	/**
	 * Removes all missing views from the list, be careful, it modifies the collection!
	 *
	 * @param seq - the sequence description
	 * @param viewIds - the views
	 * @param <V> - something extending ViewId
	 * @return those who were removed
	 */
	public static < V extends ViewId > List< V > filterMissingViews( final AbstractSequenceDescription<?, ?, ?> seq, final Collection< V > viewIds )
	{
		final ArrayList< V > removed = new ArrayList<>();
		final ArrayList< V > present = new ArrayList<>();

		for ( final V viewId : viewIds )
		{
			final BasicViewDescription< ? > vd = seq.getViewDescriptions().get( viewId );
			
			if ( vd.isPresent() )
				present.add( viewId );
			else
				removed.add( viewId );
		}

		viewIds.clear();
		viewIds.addAll( present );

		return removed;
	}

	/**
	 * Removes Views in Groups that are missing, and entire groups if no views are left
	 * 
	 * @param data - the SpimData object
	 * @param groupsIn - a collection of groups consisting of views
	 * @param <V> - something extending ViewId
	 * @return a filtered list, potentially of size 0
	 */
	public static < V extends ViewId > ArrayList< Group< V > > filterGroupsForMissingViews(
			final AbstractSpimData< ? > data,
			final Collection< Group< V > > groupsIn )
	{
		final ArrayList< Group< V > > groupsOut = new ArrayList<>();

		for ( final Group< V > group : groupsIn )
		{
			filterMissingViews( data, group.getViews() );

			if ( group.getViews().size() > 0 )
				groupsOut.add( group );
		}

		return groupsOut;
	}
}
