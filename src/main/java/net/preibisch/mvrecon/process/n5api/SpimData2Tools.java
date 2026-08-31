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
package net.preibisch.mvrecon.process.n5api;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bigdataviewer.n5.N5CloudImageLoader;
import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.n5.N5ImageLoader;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.TimePointsPattern;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.n5api.N5ApiTools.MultiResolutionLevelInfo;
import util.URITools;

public class SpimData2Tools
{
	public static MultiResolutionLevelInfo[] writeBDVMetaData(
			final N5Writer driverVolumeWriter,
			final StorageFormat storageType,
			final DataType dataType,
			final long[] dimensions,
			final Compression compression,
			final int[] blockSize,
			final int[][] downsamplings,
			final ViewId viewId,
			final URI n5PathURI,
			final URI xmlOutPathURI,
			final InstantiateViewSetup instantiateViewSetup ) throws SpimDataException, IOException
	{
		IOFunctions.println( "Creating datasets and writing BDV-metadata ... " );

		//final String xmlPath = null;
		if ( StorageFormat.N5.equals(storageType) )
		{	
			System.out.println( "XML: " + xmlOutPathURI );

			final Pair<Boolean, Boolean> exists = writeSpimData(
					viewId,
					storageType,
					dimensions,
					n5PathURI,
					xmlOutPathURI,
					null,
					instantiateViewSetup );

			if ( exists == null )
				return null;

			return N5ApiTools.setupBdvDatasetsN5(
					driverVolumeWriter,
					viewId,
					dataType,
					dimensions,
					compression,
					blockSize,
					downsamplings );
		}
		else if ( StorageFormat.HDF5.equals(storageType) )
		{
			System.out.println( "XML: " + xmlOutPathURI );

			final Pair<Boolean, Boolean> exists = writeSpimData(
					viewId,
					storageType,
					dimensions,
					n5PathURI,
					xmlOutPathURI,
					null,
					instantiateViewSetup );

			if ( exists == null )
				return null;

			return N5ApiTools.setupBdvDatasetsHDF5(
					driverVolumeWriter,
					viewId,
					dataType,
					dimensions,
					compression,
					blockSize,
					downsamplings );
		}
		else
		{
			IOFunctions.println( "BDV-compatible dataset cannot be written for " + storageType + " (yet).");
			return null;
		}
	}
	public static Pair<Boolean, Boolean> writeSpimData(
			final ViewId viewId,
			final StorageFormat storageType,
			final long[] dimensions,
			final URI n5PathURI,
			final URI xmlOutPathURI,
			final OMEZARREntry omeZarrEntry, // only required for OME-ZARR export (for N5 and HDF5 it is implicitly defined)
			final InstantiateViewSetup instantiateViewSetup ) throws SpimDataException
	{
		SpimData2 existingSpimData;

		try
		{
			existingSpimData = new XmlIoSpimData2().load( xmlOutPathURI );
		}
		catch (Exception e )
		{
			existingSpimData = null;
		}

		if ( existingSpimData != null ) //xmlOutPath.exists() )
		{
			System.out.println( "XML exists. Parsing and adding.");

			boolean tpExists = false;
			boolean viewSetupExists = false;

			for ( final ViewDescription viewId2 : existingSpimData.getSequenceDescription().getViewDescriptions().values() )
			{
				/*
				// uncommented this because if you make a second timepoint and do not add missing views, they all exist already
				if ( viewId2.equals( viewId ) )
				{
					IOFunctions.println( "ViewId you specified (" + Group.pvid(viewId) + ") already exists in the XML, cannot continue." );
					return null;
				}
				*/

				if ( viewId2.getTimePointId() == viewId.getTimePointId() )
					tpExists = true;

				if ( viewId2.getViewSetupId() == viewId.getViewSetupId() )
				{
					viewSetupExists = true;

					// dimensions have to match
					if ( !Intervals.equalDimensions( new FinalDimensions( dimensions ), viewId2.getViewSetup().getSize() ) )
					{
						IOFunctions.println( "ViewSetup you specified ("  + Group.pvid(viewId) + ") already exists in the XML, but with different dimensions, cannot continue." );
						return null;
					}
				}
			}

			final List<ViewSetup> setups = new ArrayList<>( existingSpimData.getSequenceDescription().getViewSetups().values() );

			if ( !viewSetupExists )
				setups.add( instantiateViewSetup.instantiate( viewId, tpExists, new FinalDimensions( dimensions ), setups ) );

			final TimePoints timepoints;
			if ( !tpExists) {
				final List<TimePoint> tps = new ArrayList<>(existingSpimData.getSequenceDescription().getTimePoints().getTimePointsOrdered());
				tps.add(new TimePoint(viewId.getTimePointId()));
				timepoints = new TimePoints(tps);
			}
			else
			{
				timepoints = existingSpimData.getSequenceDescription().getTimePoints();
			}

			final Map<ViewId, ViewRegistration> registrations = existingSpimData.getViewRegistrations().getViewRegistrations();
			registrations.put( viewId, new ViewRegistration( viewId.getTimePointId(), viewId.getViewSetupId() ) );
			final ViewRegistrations viewRegistrations = new ViewRegistrations( registrations );

			final SequenceDescription sequence = new SequenceDescription(timepoints, setups, null, new MissingViews( new ArrayList<>() ));

			if ( StorageFormat.N5.equals(storageType) && URITools.isFile( n5PathURI )) // local file
				sequence.setImgLoader( new N5ImageLoader( n5PathURI, sequence) );
			else if ( StorageFormat.N5.equals(storageType) ) // some cloud location
				sequence.setImgLoader( new N5CloudImageLoader( null, n5PathURI, sequence) );
			else if ( StorageFormat.HDF5.equals(storageType) )
				sequence.setImgLoader( new Hdf5ImageLoader( new File( URITools.fromURI( n5PathURI ) ), null, sequence) );
			else if ( StorageFormat.ZARR.equals(storageType ) || StorageFormat.ZARR2.equals( storageType ) )// OME-ZARR
			{
				final AllenOMEZarrLoader oldLoader =
						(AllenOMEZarrLoader)existingSpimData.getSequenceDescription().getImgLoader();

				final Map<ViewId, OMEZARREntry> viewIdToPath = new HashMap<>( oldLoader.getViewIdToPath() );
				viewIdToPath.put( viewId, omeZarrEntry );

				sequence.setImgLoader( new AllenOMEZarrLoader( n5PathURI, storageType, sequence, viewIdToPath) );
			}
			else
				throw new RuntimeException( storageType + " not supported." );

			final SpimData2 spimDataNew =
					new SpimData2(
							existingSpimData.getBasePathURI(),
							sequence,
							viewRegistrations,
							existingSpimData.getViewInterestPoints(),
							existingSpimData.getBoundingBoxes(),
							existingSpimData.getPointSpreadFunctions(),
							existingSpimData.getStitchingResults(),
							existingSpimData.getIntensityAdjustments() );

			// we are overwriting it
			new XmlIoSpimData2().save( spimDataNew, xmlOutPathURI );//existingSpimData.getBasePathURI() );

			return new ValuePair<>(tpExists, viewSetupExists);
		}
		else
		{
			System.out.println( "New XML.");

			final ArrayList< ViewSetup > setups = new ArrayList<>();
			setups.add( instantiateViewSetup.instantiate( viewId, false, new FinalDimensions( dimensions ), setups ) );

			final ArrayList< TimePoint > tps = new ArrayList<>();
			tps.add( new TimePoint( viewId.getTimePointId() ) );

			final Map< ViewId, OMEZARREntry > viewIdToPath = new HashMap<>();

			if ( StorageFormat.ZARR.equals( storageType ) || StorageFormat.ZARR2.equals( storageType ))
				viewIdToPath.put( viewId, omeZarrEntry );

			final SpimData2 spimData =
					createNewSpimDataForFusion( storageType, n5PathURI, xmlOutPathURI, viewIdToPath, setups, tps );

			new XmlIoSpimData2().save( spimData, xmlOutPathURI );

			return new ValuePair<>(false, false);
		}
	}

	public static SpimData2 createNewSpimDataForFusion(
			final StorageFormat storageType,
			final URI n5PathURI,
			final URI xmlOutPathURI,
			final Map< ViewId, OMEZARREntry > viewIdToPath,  // only required for OME-ZARR export (for N5 and HDF5 it is implicitly defined)
			final ArrayList< ViewSetup > setups,
			final ArrayList< TimePoint > tps )
	{
		final TimePoints timepoints = new TimePoints( tps );

		final HashMap< ViewId, ViewRegistration > registrations = new HashMap<>();
		final ViewRegistrations viewRegistrations = new ViewRegistrations( registrations );	

		for ( int s = 0; s < setups.size(); ++s )
			for ( int t = 0; t < tps.size(); ++t )
			{
				final ViewId viewId = new ViewId( tps.get( t ).getId(), setups.get( s ).getId() );
				registrations.put( viewId, new ViewRegistration( viewId.getTimePointId(), viewId.getViewSetupId() ) );
			}

		final SequenceDescription sequence = new SequenceDescription(timepoints, setups, null);

		if ( StorageFormat.N5.equals(storageType) && URITools.isFile( n5PathURI )) // local file
			sequence.setImgLoader( new N5ImageLoader( n5PathURI, sequence) );
		else if ( StorageFormat.N5.equals(storageType) ) // some cloud location
			sequence.setImgLoader( new N5CloudImageLoader( null, n5PathURI, sequence) );
		else if ( StorageFormat.HDF5.equals(storageType) )
			sequence.setImgLoader( new Hdf5ImageLoader( new File( URITools.fromURI( n5PathURI ) ), null, sequence) );
		else if ( StorageFormat.ZARR.equals(storageType ) || StorageFormat.ZARR2.equals( storageType ) )// OME-ZARR
			sequence.setImgLoader( new AllenOMEZarrLoader( n5PathURI, storageType, sequence, viewIdToPath) );
		else
			throw new RuntimeException( storageType + " not supported." );

		return new SpimData2( xmlOutPathURI, sequence, viewRegistrations, new ViewInterestPoints(), new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );
	}

	@FunctionalInterface
	public static interface InstantiateViewSetup
	{
		public ViewSetup instantiate( final ViewId viewId, final boolean tpExists, final Dimensions d, final List<ViewSetup> existingSetups );
	}

	public static class InstantiateViewSetupBigStitcher implements InstantiateViewSetup
	{
		final int splittingType;

		// count the fusion groups
		int count = 0;

		// new indicies
		int newA = -1;
		int newC = -1;
		int newI = -1;
		int newT = -1;

		public InstantiateViewSetupBigStitcher(
				final int splittingType)
		{
			this.splittingType = splittingType;
		}

		@Override
		public ViewSetup instantiate( final ViewId viewId, final boolean tpExists, final Dimensions d, final List<ViewSetup> existingSetups )
		{
			if ( existingSetups == null || existingSetups.size() == 0 )
			{
				newA = 0;
				newC = 0;
				newI = 0;
				newT = 0;
				
			}
			else
			{
				final Iterator<ViewSetup> i = existingSetups.iterator();
				ViewSetup tmp = i.next();
	
				Channel c0 = tmp.getChannel();
				Angle a0 = tmp.getAngle();
				Illumination i0 = tmp.getIllumination();
				Tile t0 = tmp.getTile();
	
				// get the highest id for all entities
				while ( i.hasNext() )
				{
					tmp = i.next();
					if ( tmp.getChannel().getId() > c0.getId() )
						c0 = tmp.getChannel();
					if ( tmp.getAngle().getId() > a0.getId() )
						a0 = tmp.getAngle();
					if ( tmp.getIllumination().getId() > i0.getId() )
						i0 = tmp.getIllumination();
					if ( tmp.getTile().getId() > t0.getId() )
						t0 = tmp.getTile();
				}
	
				// new unique id's for all, initialized once
				if ( newA < 0 )
				{
					newA = a0.getId() + 1;
					newC = c0.getId() + 1;
					newI = i0.getId() + 1;
					newT = t0.getId() + 1;
				}
			}

			Angle a0;
			Channel c0;
			Illumination i0;
			Tile t0;

			// 0 == "Each timepoint &amp; channel",
			// 1 == "Each timepoint, channel &amp; illumination",
			// 2 == "All views together",
			// 3 == "Each view"
			if ( splittingType == 0 )
			{
				// a new channel for each fusion group
				c0 = new Channel( newC + count );

				a0 = new Angle( newA );
				i0 = new Illumination( newI );
				t0 = new Tile( newT );
			}
			else if ( splittingType == 1 )
			{
				// TODO: we need to know what changed
				// a new channel and illumination for each fusion group
				c0 = new Channel( newC + count );
				i0 = new Illumination( newI + count );

				a0 = new Angle( newA );
				t0 = new Tile( newT );
			}
			else if ( splittingType == 2 )
			{
				// a new channel, angle, tile and illumination for the single fusion group
				a0 = new Angle( newA );
				c0 = new Channel( newC );
				i0 = new Illumination( newI );
				t0 = new Tile( newT );
			}
			else if ( splittingType == 3 )
			{
				// TODO: use previous ones
				// TODO: we need to know what changed
				c0 = new Channel( newC + count );
				i0 = new Illumination( newI + count );
				a0 = new Angle( newA + count );
				t0 = new Tile( newT + count );
			}
			else
			{
				IOFunctions.println( "SplittingType " + splittingType + " unknown. Stopping.");
				return null;
			}

			final VoxelDimensions vd0 = new FinalVoxelDimensions( "px", 1, 1, 1 );

			++count;

			return new ViewSetup( viewId.getViewSetupId(), "setup " + viewId.getViewSetupId(), d, vd0, t0, c0, a0, i0 );
		}
	}

	public static class InstantiateViewSetupBigStitcherSpark implements InstantiateViewSetup
	{
		final String angleIds;
		final String illuminationIds;
		final String channelIds;
		final String tileIds;

		public InstantiateViewSetupBigStitcherSpark(
				final String angleIds,
				final String illuminationIds,
				final String channelIds,
				final String tileIds )
		{
			this.angleIds = angleIds;
			this.illuminationIds = illuminationIds;
			this.channelIds = channelIds;
			this.tileIds = tileIds;
		}

		@Override
		public ViewSetup instantiate( final ViewId viewId, final boolean tpExists, final Dimensions d, final List<ViewSetup> existingSetups )
		{
			Angle a0;
			Channel c0;
			Illumination i0;
			Tile t0;

			if ( existingSetups == null || existingSetups.size() == 0 )
			{
				a0 = new Angle( 0 );
				c0 = new Channel( 0 );
				i0 = new Illumination( 0 );
				t0 = new Tile( 0 );
			}
			else
			{
				final Iterator<ViewSetup> i = existingSetups.iterator();
				ViewSetup tmp = i.next();
	
				c0 = tmp.getChannel();
				a0 = tmp.getAngle();
				i0 = tmp.getIllumination();
				t0 = tmp.getTile();

				// get the highest id for all entities
				while ( i.hasNext() )
				{
					tmp = i.next();
					if ( tmp.getChannel().getId() > c0.getId() )
						c0 = tmp.getChannel();
					if ( tmp.getAngle().getId() > a0.getId() )
						a0 = tmp.getAngle();
					if ( tmp.getIllumination().getId() > i0.getId() )
						i0 = tmp.getIllumination();
					if ( tmp.getTile().getId() > t0.getId() )
						t0 = tmp.getTile();
				}

				if ( angleIds != null )
					a0 = new Angle( a0.getId() + 1 );
				if ( illuminationIds != null )
					i0 = new Illumination( i0.getId() + 1 );
				if ( tileIds != null )
					t0 = new Tile( t0.getId() + 1 );
				if ( tileIds != null || ( angleIds == null && illuminationIds == null && tileIds == null && tpExists ) ) // nothing was defined, then increase channel
					c0 = new Channel( c0.getId() + 1 );
			}

			//final Dimensions d0 = new FinalDimensions( dimensions );
			final VoxelDimensions vd0 = new FinalVoxelDimensions( "px", 1, 1, 1 );

			return new ViewSetup( viewId.getViewSetupId(), "setup " + viewId.getViewSetupId(), d, vd0, t0, c0, a0, i0 );
		}
	}

	/**
	 * Reduces a given SpimData2 to the subset of timepoints and viewsetups as selected by the user,
	 * including the original imgloader and keeping the basepath (i.e. interest points still work)
	 * 
	 * Note: PSF's will be lost.
	 *
	 * @param oldSpimData - the original SpimData
	 * @param viewIds - the views to keep
	 * @return - reduced SpimData2
	 */
	public static SpimData2 reduceSpimData2( final SpimData2 oldSpimData, final List< ViewId > viewIds )
	{
		return reduceSpimData2( oldSpimData, viewIds, null );
	}

	/**
	 * Reduces a given SpimData2 to the subset of timepoints and viewsetups as selected by the user,
	 * including the original imgloader and changing the base path (you still need to save to materialize the returned object!)
	 *
	 * Note: PSF's will be lost.
	 *
	 * @param oldSpimData - the original SpimData
	 * @param viewIds - the views to keep
	 * @param basePath - the new base path (can be null); if you set a new base path it will load all interest points so the new SpimData2 object can be saved including these points
	 * @return - reduced SpimData2
	 */
	public static SpimData2 reduceSpimData2( final SpimData2 oldSpimData, final List< ViewId > viewIds, final URI basePath )
	{
		final TimePoints timepoints;

		try
		{
			timepoints = new TimePointsPattern( listAllTimePoints( SpimData2.getAllTimePointsSorted( oldSpimData, viewIds ) ) );
		}
		catch (ParseException e)
		{
			IOFunctions.println( "Automatically created list of timepoints failed to parse. This should not happen, really :) -- " + e );
			IOFunctions.println( "Here is the list: " + listAllTimePoints( SpimData2.getAllTimePointsSorted( oldSpimData, viewIds ) ) );
			e.printStackTrace();
			return null;
		}

		final List< ViewSetup > viewSetupsToProcess = SpimData2.getAllViewSetupsSorted( oldSpimData, viewIds );

		// a hashset for all viewsetups that remain
		final Set< ViewId > views = new HashSet< ViewId >();

		for ( final ViewId viewId : viewIds )
			views.add( new ViewId( viewId.getTimePointId(), viewId.getViewSetupId() ) );

		final MissingViews oldMissingViews = oldSpimData.getSequenceDescription().getMissingViews();
		final HashSet< ViewId > missingViews = new HashSet< ViewId >();

		if( oldMissingViews != null && oldMissingViews.getMissingViews() != null )
			for ( final ViewId id : oldMissingViews.getMissingViews() )
				if ( views.contains( id ) )
					missingViews.add( id );

		// add the new missing views!!!
		for ( final TimePoint t : timepoints.getTimePointsOrdered() )
			for ( final ViewSetup v : viewSetupsToProcess )
			{
				final ViewId viewId = new ViewId( t.getId(), v.getId() );

				if ( !views.contains( viewId ) )
					missingViews.add( viewId );
			}

		// instantiate the sequencedescription
		final SequenceDescription sequenceDescription = new SequenceDescription( timepoints, viewSetupsToProcess, oldSpimData.getSequenceDescription().getImgLoader(), new MissingViews( missingViews ) );

		// re-assemble the registrations
		final Map< ViewId, ViewRegistration > oldRegMap = oldSpimData.getViewRegistrations().getViewRegistrations();
		final Map< ViewId, ViewRegistration > newRegMap = new HashMap< ViewId, ViewRegistration >();

		for ( final ViewId viewId : oldRegMap.keySet() )
			if ( views.contains( viewId ) )
				newRegMap.put( viewId, oldRegMap.get( viewId ) );

		final ViewRegistrations viewRegistrations = new ViewRegistrations( newRegMap );

		// re-assemble the interestpoints and a list of filenames to copy
		final Map< ViewId, ViewInterestPointLists > oldInterestPoints = oldSpimData.getViewInterestPoints().getViewInterestPoints();
		final Map< ViewId, ViewInterestPointLists > newInterestPoints = new HashMap< ViewId, ViewInterestPointLists >();

		oldInterestPoints.forEach( (viewId, ipLists) ->
		{
			if ( views.contains( viewId ) )
			{
				if ( basePath != null )
				{
					final ViewInterestPointLists ipListsNew = new ViewInterestPointLists(viewId.getTimePointId(), viewId.getViewSetupId() );

					ipLists.getHashMap().forEach( (label,interestpoints) ->
					{
						final Map<Integer, InterestPoint> points = interestpoints.getInterestPointsCopy();
						final Collection<CorrespondingInterestPoints> corr = interestpoints.getCorrespondingInterestPointsCopy();

						final InterestPoints interestpointsNew = InterestPoints.newInstance( basePath, viewId, label );
						interestpointsNew.setInterestPoints( points.values() );
						interestpointsNew.setCorrespondingInterestPoints( corr );

						ipListsNew.addInterestPointList( label, interestpointsNew );
					} );

					newInterestPoints.put( viewId, ipListsNew );
				}
				else
				{
					// if the basepath doesn't change we can keep interestpoints as-is
					newInterestPoints.put( viewId, ipLists );
				}
			}
		});

		final ViewInterestPoints viewsInterestPoints = new ViewInterestPoints( newInterestPoints );

		//TODO: copy PSFs?

		final SpimData2 newSpimData = new SpimData2(
				basePath == null ? oldSpimData.getBasePathURI() : basePath,
				sequenceDescription,
				viewRegistrations,
				viewsInterestPoints,
				oldSpimData.getBoundingBoxes(),
				new PointSpreadFunctions(), //oldSpimData.getPointSpreadFunctions()
				oldSpimData.getStitchingResults(),
				oldSpimData.getIntensityAdjustments() );
		// preserve the action history across the resave-reduce so the new XML keeps the provenance
		newSpimData.setActionHistory( oldSpimData.getActionHistory() );

		return newSpimData;
	}

	public static String listAllTimePoints( final List<TimePoint> timePointsToProcess )
	{
		String t = "" + timePointsToProcess.get( 0 ).getId();

		for ( int i = 1; i < timePointsToProcess.size(); ++i )
			t += ", " + timePointsToProcess.get( i ).getId();

		return t;
	}

	private static void copyFolder( final File src, final File dest, final List< String > filesToCopy ) throws IOException
	{
		if ( src.isDirectory() )
		{
			if( !dest.exists() )
				dest.mkdir();

			for ( final String file : src.list() )
				copyFolder( new File( src, file ), new File( dest, file ), filesToCopy );
		}
		else
		{
			boolean contains = false;
			
			for ( int i = 0; i < filesToCopy.size() && !contains; ++i )
				if ( src.getName().contains( filesToCopy.get( i ) ) )
					contains = true;
			
			if ( contains )
			{
				final InputStream in = new FileInputStream( src );
				final OutputStream out = new FileOutputStream( dest ); 

				final byte[] buffer = new byte[ 65535 ];

				int length;

				while ( ( length = in.read(buffer) ) > 0 )
					out.write(buffer, 0, length);

				in.close();
				out.close();
			}
		}
	}
}
