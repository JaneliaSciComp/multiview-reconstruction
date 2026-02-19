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
package net.preibisch.mvrecon.process.splitting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.KDTree;
import net.imglib2.neighborsearch.RadiusNeighborSearch;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.Split_Views;
import net.preibisch.mvrecon.fiji.plugin.Split_Views.InterestPointAdding;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitMultiResolutionImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunction;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class SplittingTools
{
	public static final String IMAGE_SPLITTING_NAME = "Image Splitting";
	public static boolean roundMipmapResolutions = false;

	/**
	 * Result of processing a single ViewSetup's split intervals.
	 * Immutable, safe to return from parallel operations.
	 * Can be used with multi-threaded execution or Spark.
	 */
	public static class SetupSplitResult
	{
		public final int oldSetupId;
		public final List< ViewSetup > newSetups;
		public final Map< Integer, Integer > new2oldSetupId;
		public final Map< Integer, Interval > newSetupId2Interval;
		public final List< Integer > newSetupIds;
		public final Map< ViewId, ViewRegistration > newRegistrations;
		public final Map< ViewId, ViewInterestPointLists > newInterestpoints;

		public SetupSplitResult(
				final int oldSetupId,
				final List< ViewSetup > newSetups,
				final Map< Integer, Integer > new2oldSetupId,
				final Map< Integer, Interval > newSetupId2Interval,
				final List< Integer > newSetupIds,
				final Map< ViewId, ViewRegistration > newRegistrations,
				final Map< ViewId, ViewInterestPointLists > newInterestpoints )
		{
			this.oldSetupId = oldSetupId;
			this.newSetups = newSetups;
			this.new2oldSetupId = new2oldSetupId;
			this.newSetupId2Interval = newSetupId2Interval;
			this.newSetupIds = newSetupIds;
			this.newRegistrations = newRegistrations;
			this.newInterestpoints = newInterestpoints;
		}
	}

	//public static boolean assingIlluminationsFromTileIds = false;
	//public static double error = 0.5;
	//public static int minPoints = 20;
	//public static int maxPoints = 500;

	/*
	 * 
	 * @param spimData - the spimdata object to split up
	 * @param overlapPx - the expected overlap
	 * @param targetSize - roughly the expected size of each subdivided tile
	 * @param minStepSize - step size or multipleOf for coordinates and sizes (except size of last tile), e.g. 8 - defined by the lowest downsampling step
	 * @param assingIlluminationsFromTileIds - use illumination attribute to remember former tiles
	 * @param optimize - whether to optimize overlap size
	 * @param ipAdding - if fake (corresponding) interest points should be added
	 * @param pointDensity - how many points per 100x100x100 volume
	 * @param minPoints - min number of generated fake points per pair
	 * @param maxPoints - max number of generated fake points per pair
	 * @param error - artifical error for matching points
	 * @param excludeRadius - the radius around existing points in which fake points cannot be put
	 * @return
	 */
	public static SpimData2 splitImages(
			final SpimData2 spimData,
			final SplitInterval splitting,
			final boolean assingIlluminationsFromTileIds,
			final InterestPointAdding ipAdding,
			final double pointDensity,
			final int minPoints,
			final int maxPoints,
			final double error,
			final double excludeRadius )
	{
		final TimePoints timepoints = spimData.getSequenceDescription().getTimePoints();

		final List< ViewSetup > oldSetups = new ArrayList<>();
		oldSetups.addAll( spimData.getSequenceDescription().getViewSetups().values() );
		Collections.sort( oldSetups );

		final ViewRegistrations oldRegistrations = spimData.getViewRegistrations();

		final ImgLoader underlyingImgLoader = spimData.getSequenceDescription().getImgLoader();
		spimData.getSequenceDescription().setImgLoader( null ); // we don't need it anymore there as we save it later

		final HashMap< Integer, Integer > new2oldSetupId = new HashMap<>();
		final HashMap< Integer, Interval > newSetupId2Interval = new HashMap<>();

		// for setting up the corresponding interest points
		final Map< Integer, ArrayList< Integer > > old2NewSetups = new HashMap<>();

		final ArrayList< ViewSetup > newSetups = new ArrayList<>();
		final Map< ViewId, ViewRegistration > newRegistrations = new HashMap<>();
		final Map< ViewId, ViewInterestPointLists > newInterestpoints = new HashMap<>();

		// new tileId is locally computed based on the old tile ids
		// by multiplying it with maxspread and then +1 for each new tile
		// so each new one has to be the same across channel & illumination!
		final int maxIntervalSpread = splitting.maxIntervalSpread( oldSetups );

		// check that there is only one illumination
		if ( assingIlluminationsFromTileIds )
			if ( spimData.getSequenceDescription().getAllIlluminationsOrdered().size() > 1 )
				throw new IllegalArgumentException( "Cannot SplittingTools.assingIlluminationsFromTileIds because more than one Illumination exists." );

		// only relevant if addIPs is selected
		final String fakeLabel = "splitPoints_" + System.currentTimeMillis();

		// TODO: in order to assign existing corresponding points, we need to build the Map from old to new viewId's and their transformations/sizes first
		// TODO: because we need to know what the new corresponding point(s) is/are, 1:1 correspondence mappings become 1:n since the corresponding point
		// TODO: may be in an overlapping area and are thus multiplied

		// ==================== Parallel Splitting ====================
		// For SplitOctTree, we use the static method for thread-safe parallel execution.
		// For other splitters, we fall back to sequential execution.

		final Map< ViewSetup, ArrayList< Interval > > splitResults = new HashMap<>();

		if ( splitting instanceof SplitOctTree )
		{
			final SplitOctTree octTreeSplitter = (SplitOctTree) splitting;
			final int nThreads = Threads.numThreads();
			IOFunctions.println( "Parallel oct-tree splitting with " + nThreads + " threads for " + oldSetups.size() + " setups..." );

			// Prepare tasks: for each setup, find ViewId and create splitting task
			final List< Callable< SplitOctTree.SplitStatistics > > tasks = new ArrayList<>();
			final List< ViewSetup > taskSetups = new ArrayList<>();  // parallel list to track which setup each task belongs to
			final List< Interval > taskInputs = new ArrayList<>();   // parallel list for input intervals

			for ( final ViewSetup oldSetup : oldSetups )
			{
				final Interval input = new FinalInterval( oldSetup.getSize() );

				// Find first present (non-missing) timepoint for this setup
				ViewId viewId = null;
				for ( final TimePoint tp : timepoints.getTimePointsOrdered() )
				{
					final ViewId candidate = new ViewId( tp.getId(), oldSetup.getId() );
					if ( spimData.getSequenceDescription().getMissingViews() == null ||
						 spimData.getSequenceDescription().getMissingViews().getMissingViews() == null ||
						 !spimData.getSequenceDescription().getMissingViews().getMissingViews().contains( candidate ) )
					{
						viewId = candidate;
						break;
					}
				}

				// If all timepoints are missing for this setup, use the first timepoint anyway
				if ( viewId == null )
				{
					final TimePoint firstTP = timepoints.getTimePointsOrdered().get( 0 );
					viewId = new ViewId( firstTP.getId(), oldSetup.getId() );
				}

				final ViewId finalViewId = viewId;
				tasks.add( () -> SplitOctTree.splitStatic(
						input,
						finalViewId,
						octTreeSplitter.getCriterion(),
						octTreeSplitter.getMinStepSize(),
						octTreeSplitter.getMinSizeMultiplier(),
						octTreeSplitter.isEnableMerge(),
						octTreeSplitter.getMinSplitLevels() ) );
				taskSetups.add( oldSetup );
				taskInputs.add( input );
			}

			// Execute all tasks in parallel
			final ExecutorService taskExecutor = Executors.newFixedThreadPool( nThreads );
			try
			{
				final List< Future< SplitOctTree.SplitStatistics > > futures = taskExecutor.invokeAll( tasks );

				// Collect results and aggregate statistics
				int totalSplitCount = 0, totalMergeCount = 0, totalLeafCount = 0, totalFinalBlocks = 0;

				for ( int i = 0; i < futures.size(); i++ )
				{
					final ViewSetup setup = taskSetups.get( i );
					final Interval input = taskInputs.get( i );
					final SplitOctTree.SplitStatistics result = futures.get( i ).get();

					if ( result == null )
					{
						IOFunctions.printErr( "ERROR: Splitting failed for ViewSetup " + setup.getId() );
						taskExecutor.shutdown();
						return null;
					}

					IOFunctions.println( "ViewId " + setup.getId() + " with interval " + Util.printInterval( input ) +
							": " + result.intervals.size() + " tiles (" + result.splitCount + " splits, " +
							result.mergeCount + " merges, " + result.leafCount + " leaves)" );

					splitResults.put( setup, result.intervals );

					totalSplitCount += result.splitCount;
					totalMergeCount += result.mergeCount;
					totalLeafCount += result.leafCount;
					totalFinalBlocks += result.intervals.size();
				}

				// Print aggregate statistics
				IOFunctions.println( "===== Oct-tree splitting summary =====" );
				IOFunctions.println( "Total views processed: " + oldSetups.size() );
				IOFunctions.println( "Total splits: " + totalSplitCount );
				IOFunctions.println( "Total merges: " + totalMergeCount );
				IOFunctions.println( "Total leaves: " + totalLeafCount );
				IOFunctions.println( "Total final blocks: " + totalFinalBlocks );
				IOFunctions.println( "======================================" );
			}
			catch ( final Exception e )
			{
				IOFunctions.printErr( "ERROR during parallel splitting: " + e.getMessage() );
				e.printStackTrace();
				taskExecutor.shutdown();
				return null;
			}
			finally
			{
				taskExecutor.shutdown();
			}
		}
		else
		{
			// Sequential splitting for non-OctTree splitters
			for ( final ViewSetup oldSetup : oldSetups )
			{
				final Interval input = new FinalInterval( oldSetup.getSize() );
				IOFunctions.println( "ViewId " + oldSetup.getId() + " with interval " + Util.printInterval( input ) + " will be split as follows: " );

				final ArrayList< Interval > intervals = splitting.split( input );
				if ( intervals == null )
				{
					IOFunctions.printErr( "ERROR: Splitting failed for ViewSetup " + oldSetup.getId() );
					return null;
				}

				splitResults.put( oldSetup, intervals );
			}
		}

		// ==================== Process Split Results in Parallel ====================
		// Pre-compute ID ranges per setup for parallel assignment
		final Map< ViewSetup, Integer > setupIdStart = new LinkedHashMap<>();
		int cumulativeId = 0;
		for ( final ViewSetup setup : oldSetups )
		{
			setupIdStart.put( setup, cumulativeId );
			cumulativeId += splitResults.get( setup ).size();
		}

		final int nThreads = Threads.numThreads();
		IOFunctions.println( "Parallel post-processing with " + nThreads + " threads for " + oldSetups.size() + " setups..." );

		// Prepare parallel tasks
		final List< Callable< SetupSplitResult > > postProcessTasks = new ArrayList<>();
		final long baseSeed = 23424459;

		for ( final ViewSetup oldSetup : oldSetups )
		{
			final int startId = setupIdStart.get( oldSetup );
			final ArrayList< Interval > intervals = splitResults.get( oldSetup );
			final long setupSeed = baseSeed + oldSetup.getId();

			postProcessTasks.add( () -> processSetupStatic(
					oldSetup,
					intervals,
					startId,
					maxIntervalSpread,
					timepoints,
					oldRegistrations,
					spimData,
					assingIlluminationsFromTileIds,
					ipAdding,
					fakeLabel,
					pointDensity,
					minPoints,
					maxPoints,
					error,
					excludeRadius,
					setupSeed,
					splitting.description() ) );
		}

		// Execute in parallel and collect results
		final ExecutorService postProcessExecutor = Executors.newFixedThreadPool( nThreads );
		try
		{
			final List< Future< SetupSplitResult > > postProcessFutures = postProcessExecutor.invokeAll( postProcessTasks );

			// Merge results from all setups
			for ( int idx = 0; idx < postProcessFutures.size(); idx++ )
			{
				final SetupSplitResult result = postProcessFutures.get( idx ).get();
				final ViewSetup oldSetup = oldSetups.get( idx );

				// Merge into shared collections
				newSetups.addAll( result.newSetups );
				new2oldSetupId.putAll( result.new2oldSetupId );
				newSetupId2Interval.putAll( result.newSetupId2Interval );
				old2NewSetups.put( result.oldSetupId, new ArrayList<>( result.newSetupIds ) );
				newRegistrations.putAll( result.newRegistrations );
				newInterestpoints.putAll( result.newInterestpoints );
			}

			// Sort newSetups by ID to maintain consistent ordering
			newSetups.sort( ( a, b ) -> Integer.compare( a.getId(), b.getId() ) );

			IOFunctions.println( "Post-processing complete: " + newSetups.size() + " new setups created." );
		}
		catch ( final Exception e )
		{
			IOFunctions.printErr( "ERROR during parallel post-processing: " + e.getMessage() );
			e.printStackTrace();
			postProcessExecutor.shutdown();
			return null;
		}
		finally
		{
			postProcessExecutor.shutdown();
		}

		// missing views
		final MissingViews oldMissingViews = spimData.getSequenceDescription().getMissingViews();
		final HashSet< ViewId > missingViews = new HashSet< ViewId >();

		if ( oldMissingViews != null && oldMissingViews.getMissingViews() != null )
			for ( final ViewId id : oldMissingViews.getMissingViews() )
				for ( final int newSetupId : new2oldSetupId.keySet() )
					if ( new2oldSetupId.get( newSetupId ) == id.getViewSetupId() )
						missingViews.add( new ViewId( id.getTimePointId(), newSetupId ) );

		//
		// add existing corresponding interest points (parallelized)
		//
		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Processing corresponding interest points in parallel..." );

		// Build task list for all (oldSetup, newSetupId, timepoint) combinations
		final List< Callable< Void > > corrTasks = new ArrayList<>();
		for ( final ViewSetup oldSetup : oldSetups )
		{
			for ( final int newSetupId : old2NewSetups.get( oldSetup.getId() ) )
			{
				for ( final TimePoint t : timepoints.getTimePointsOrdered() )
				{
					final ViewSetup capturedSetup = oldSetup;
					final int capturedNewSetupId = newSetupId;
					final TimePoint capturedT = t;
					corrTasks.add( () -> {
						processCorrespondingInterestPointsStatic(
								capturedSetup, capturedNewSetupId, capturedT,
								spimData, old2NewSetups, newInterestpoints,
								null, 0 );  // progress tracking done at task wrapper level
						return null;
					} );
				}
			}
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Created " + corrTasks.size() + " correspondence tasks, executing with " + nThreads + " threads..." );

		final ExecutorService corrExecutor = Executors.newFixedThreadPool( nThreads );
		try
		{
			// Use progress tracking
			final AtomicInteger corrCompleted = new AtomicInteger( 0 );
			final int totalCorrTasks = corrTasks.size();

			// Wrap tasks with progress tracking
			final List< Callable< Void > > trackedTasks = new ArrayList<>();
			for ( final Callable< Void > task : corrTasks )
			{
				trackedTasks.add( () -> {
					task.call();
					final int done = corrCompleted.incrementAndGet();
					if ( done % 100 == 0 || done == totalCorrTasks )
						IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Processed " + done + "/" + totalCorrTasks + " correspondence tasks" );
					return null;
				} );
			}

			corrExecutor.invokeAll( trackedTasks );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Corresponding interest points processing complete." );
		}
		catch ( final InterruptedException e )
		{
			IOFunctions.printErr( "ERROR: Corresponding interest points processing interrupted: " + e.getMessage() );
			Thread.currentThread().interrupt();
			corrExecutor.shutdown();
			return null;
		}
		finally
		{
			corrExecutor.shutdown();
		}

		// instantiate the sequencedescription
		final SequenceDescription sequenceDescription = new SequenceDescription( timepoints, newSetups, null, new MissingViews( missingViews ) );
		final ImgLoader imgLoader;

		if ( ViewerImgLoader.class.isInstance( underlyingImgLoader ) )
		{
			imgLoader = new SplitViewerImgLoader( (ViewerImgLoader)underlyingImgLoader, new2oldSetupId, newSetupId2Interval, spimData.getSequenceDescription() );
		}
		else if ( MultiResolutionImgLoader.class.isInstance( underlyingImgLoader ) )
		{
			imgLoader = new SplitMultiResolutionImgLoader( (MultiResolutionImgLoader)underlyingImgLoader, new2oldSetupId, newSetupId2Interval, spimData.getSequenceDescription()  );
		}
		else
		{
			imgLoader = new SplitImgLoader( underlyingImgLoader, new2oldSetupId, newSetupId2Interval, spimData.getSequenceDescription()  );
		}

		sequenceDescription.setImgLoader( imgLoader );

		// interest points
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints( newInterestpoints );

		// view registrations
		final ViewRegistrations viewRegistrations = new ViewRegistrations( newRegistrations );

		// add point spread functions
		final HashMap< ViewId, PointSpreadFunction > newPsfs = new HashMap<>();

		/*
		final HashMap< ViewId, PointSpreadFunction > oldPsfs = spimData.getPointSpreadFunctions().getPointSpreadFunctions();

		for ( final ViewDescription newViewId : sequenceDescription.getViewDescriptions().values() )
		{
			if ( newViewId.isPresent() )
			{
				final ViewId oldViewId = new ViewId( newViewId.getTimePointId(), new2oldSetupId.get( newViewId.getViewSetupId() ) );
				if ( oldPsfs.containsKey( oldViewId ) )
				{
					final PointSpreadFunction oldPsf = oldPsfs.get( oldViewId );
					final Img< FloatType > img = oldPsf.getPSFCopy();
					final PointSpreadFunction newPsf = new PointSpreadFunction( spimData.getBasePath(), PointSpreadFunction.createPSFFileName( newViewId ), img );
					newPsfs.put( newViewId, newPsf );
				}
			}
		}*/

		final PointSpreadFunctions psfs = new PointSpreadFunctions( newPsfs );

		// TODO: fix intensity adjustments?

		// finally create the SpimData itself based on the sequence description and the view registration
		final SpimData2 spimDataNew = new SpimData2( spimData.getBasePathURI(), sequenceDescription, viewRegistrations, viewInterestPoints, spimData.getBoundingBoxes(), psfs, new StitchingResults(), new IntensityAdjustments() );

		return spimDataNew;
	}

	private static final boolean contains( final double[] l, final Interval interval )
	{
		for ( int d = 0; d < l.length; ++d )
			if ( l[ d ] < interval.min( d ) || l[ d ] > interval.max( d ) )
				return false;

		return true;
	}

	/**
	 * Process a single ViewSetup's split intervals in isolation.
	 * Thread-safe: no shared mutable state, all parameters passed in, result returned.
	 * Can be called from parallel streams, ExecutorService, or Spark.
	 *
	 * @param oldSetup The original ViewSetup to process
	 * @param intervals The split intervals for this setup
	 * @param idRangeStart Pre-computed starting ID for this setup's new ViewSetups
	 * @param maxIntervalSpread Maximum spread for tile ID calculation
	 * @param timepoints All timepoints to process
	 * @param oldRegistrations Original view registrations
	 * @param spimData The SpimData2 for accessing interest points and missing views
	 * @param assingIlluminationsFromTileIds Whether to use illumination attribute for old tiles
	 * @param ipAdding Mode for adding fake interest points
	 * @param fakeLabel Label for fake interest points
	 * @param pointDensity Density for fake point generation
	 * @param minPoints Minimum fake points per overlap
	 * @param maxPoints Maximum fake points per overlap
	 * @param error Error for fake point positions
	 * @param excludeRadius Radius to exclude around existing points
	 * @param rndSeed Seed for random number generator (per-setup for determinism)
	 * @param splittingDescription Description of splitting method
	 * @return SetupSplitResult containing all generated data for this setup
	 */
	public static SetupSplitResult processSetupStatic(
			final ViewSetup oldSetup,
			final ArrayList< Interval > intervals,
			final int idRangeStart,
			final int maxIntervalSpread,
			final TimePoints timepoints,
			final ViewRegistrations oldRegistrations,
			final SpimData2 spimData,
			final boolean assingIlluminationsFromTileIds,
			final Split_Views.InterestPointAdding ipAdding,
			final String fakeLabel,
			final double pointDensity,
			final int minPoints,
			final int maxPoints,
			final double error,
			final double excludeRadius,
			final long rndSeed,
			final String splittingDescription )
	{
		final int oldID = oldSetup.getId();
		final Tile oldTile = oldSetup.getTile();
		int localNewTileId = 0;

		final Angle angle = oldSetup.getAngle();
		final Channel channel = oldSetup.getChannel();
		final Illumination illum = oldSetup.getIllumination();
		final VoxelDimensions voxDim = oldSetup.getVoxelSize();

		// Local collections for this setup's results
		final List< ViewSetup > newSetups = new ArrayList<>();
		final Map< Integer, Integer > new2oldSetupId = new HashMap<>();
		final Map< Integer, Interval > newSetupId2Interval = new HashMap<>();
		final List< Integer > newSetupIds = new ArrayList<>();
		final Map< ViewId, ViewRegistration > newRegistrations = new HashMap<>();
		final Map< ViewId, ViewInterestPointLists > newInterestpoints = new HashMap<>();

		// Per-setup Random for thread safety and determinism
		final Random rnd = new Random( rndSeed );

		// Local map for fake IP generation within this setup
		final HashMap< Integer, ViewSetup > intervalId2ViewSetup = new HashMap<>();

		final boolean verboseIntervals = intervals.size() <= 200;

		if ( !verboseIntervals )
			IOFunctions.println( "Processing " + intervals.size() + " intervals for ViewId " + oldSetup.getId() + "..." );

		for ( int i = 0; i < intervals.size(); ++i )
		{
			final Interval interval = intervals.get( i );
			final int newId = idRangeStart + i;

			if ( verboseIntervals )
				IOFunctions.println( "Interval " + (i+1) + ": " + Util.printInterval( interval ) );
			else if ( (i+1) % 100 == 0 )
				IOFunctions.println( "  processed " + (i+1) + "/" + intervals.size() + " intervals..." );

			// from the new ID get the old ID and the corresponding interval
			new2oldSetupId.put( newId, oldID );
			newSetupId2Interval.put( newId, interval );

			final long[] size = new long[ interval.numDimensions() ];
			interval.dimensions( size );
			final Dimensions newDim = new FinalDimensions( size );

			final double[] location = oldTile.getLocation() == null ? new double[ interval.numDimensions() ] : oldTile.getLocation().clone();
			for ( int d = 0; d < interval.numDimensions(); ++d )
				location[ d ] += interval.min( d );

			final int newTileId = oldTile.getId() * maxIntervalSpread + localNewTileId;
			localNewTileId++;
			final Tile newTile = new Tile( newTileId, Integer.toString( newTileId ), location );
			final Illumination newIllum = assingIlluminationsFromTileIds ? new Illumination( oldTile.getId(), "old_tile_" + oldTile.getId() ) : illum;
			final ViewSetup newSetup = new ViewSetup( newId, null, newDim, voxDim, newTile, channel, angle, newIllum );
			newSetups.add( newSetup );

			intervalId2ViewSetup.put( i, newSetup );
			newSetupIds.add( newSetup.getId() );

			// update registrations and interest points for all timepoints
			for ( final TimePoint t : timepoints.getTimePointsOrdered() )
			{
				final ViewId oldViewId = new ViewId( t.getId(), oldSetup.getId() );
				final ViewRegistration oldVR = oldRegistrations.getViewRegistration( oldViewId );
				final ArrayList< ViewTransform > transformList = new ArrayList<>( oldVR.getTransformList() );

				final AffineTransform3D translation = new AffineTransform3D();
				translation.set(
						1.0f, 0.0f, 0.0f, interval.min( 0 ),
						0.0f, 1.0f, 0.0f, interval.min( 1 ),
						0.0f, 0.0f, 1.0f, interval.min( 2 ) );

				// very first transform is for splitting
				final ViewTransformAffine transform = new ViewTransformAffine( IMAGE_SPLITTING_NAME, translation );
				transformList.add( transform );

				final ViewId newViewId = new ViewId( t.getId(), newSetup.getId() );
				final ViewRegistration newVR = new ViewRegistration( newViewId.getTimePointId(), newViewId.getViewSetupId(), transformList );
				newRegistrations.put( newViewId, newVR );

				// Interest points: we need to add them here so we can later link them when we re-iterate
				final ViewInterestPointLists newVipl = new ViewInterestPointLists( newViewId.getTimePointId(), newViewId.getViewSetupId() );
				final ViewInterestPointLists oldVipl = spimData.getViewInterestPoints().getViewInterestPointLists( oldViewId );

				// only update interest points for present views
				// oldVipl may be null for missing views
				if ( spimData.getSequenceDescription().getMissingViews() != null && !spimData.getSequenceDescription().getMissingViews().getMissingViews().contains( oldViewId ) )
				{
					for ( final String label : oldVipl.getHashMap().keySet() )
					{
						final String newLabel = label + "_split";

						final List< InterestPoint > newIpList = new ArrayList<>();
						final InterestPoints oldIps = oldVipl.getInterestPointList( label );
						final Map< Integer, InterestPoint > oldIpList = oldIps.getInterestPointsCopy();

						for ( final InterestPoint ip : oldIpList.values() )
						{
							if ( contains( ip.getL(), interval ) )
							{
								final double[] l = ip.getL().clone();
								for ( int d = 0; d < interval.numDimensions(); ++d )
									l[ d ] -= interval.min( d );

								newIpList.add( new InterestPoint( ip.getId(), l ) );
							}
						}

						final InterestPoints newIps = InterestPoints.newInstance( oldIps.getBaseDir(), newViewId, newLabel );
						newIps.setInterestPoints( newIpList );
						newIps.setParameters( oldIps.getParameters() );
						newIps.setCorrespondingInterestPoints( new ArrayList<>() );
						newVipl.addInterestPointList( newLabel, newIps );
					}

					// adding random [corresponding] interest points in overlapping areas of introduced split views
					if ( ipAdding != Split_Views.InterestPointAdding.NONE )
					{
						final ArrayList< InterestPoint > newIp = new ArrayList<>();
						final ArrayList< CorrespondingInterestPoints > newCorrIp = new ArrayList<>();
						int id = 0;

						// for each overlapping tile that has not been processed yet
						for ( int j = 0; j < i; ++j )
						{
							final Interval otherInterval = intervals.get( j );
							final Interval intersection = Intervals.intersect( interval, otherInterval );

							// find the overlap
							if ( !Intervals.isEmpty( intersection ) )
							{
								final ViewSetup otherSetup = intervalId2ViewSetup.get( j );
								final ViewId otherViewId = new ViewId( t.getId(), otherSetup.getId() );
								final ViewInterestPointLists otherIPLists = newInterestpoints.get( otherViewId );

								// add points as function of the area
								final int n = intersection.numDimensions();
								long numPixels = 1;
								for ( int d = 0; d < n; ++d )
									numPixels *= intersection.dimension( d );

								final int numPoints = Math.min( maxPoints, Math.max( minPoints, (int)Math.round( Math.ceil( pointDensity * numPixels / (100.0*100.0*100.0) ) ) ) );

								final ArrayList< InterestPoint > otherPoints = new ArrayList<>( otherIPLists.getInterestPointList( fakeLabel ).getInterestPointsCopy().values() );
								final ArrayList< CorrespondingInterestPoints > otherCorrIp = new ArrayList<>( otherIPLists.getInterestPointList( fakeLabel ).getCorrespondingInterestPointsCopy() );

								int otherId = otherPoints.size() > 0 ? otherPoints.get( otherPoints.size() - 1 ).getId() + 1 : 0;

								final KDTree< InterestPoint > tree2;
								final RadiusNeighborSearch< InterestPoint > search2;

								if ( excludeRadius > 0 && ipAdding == Split_Views.InterestPointAdding.IP )
								{
									final List< InterestPoint > otherIPglobal = new ArrayList<>();
									for ( final InterestPoint ip : otherPoints )
									{
										final double[] l = ip.getL().clone();
										for ( int d = 0; d < n; ++d )
											l[ d ] += otherInterval.min( d );
										otherIPglobal.add( new InterestPoint( ip.getId(), l ) );
									}

									if ( otherIPglobal.size() > 0 )
									{
										tree2 = new KDTree<>( otherIPglobal, otherIPglobal );
										search2 = new RadiusNeighborSearchOnKDTree<>( tree2 );
									}
									else
									{
										tree2 = null;
										search2 = null;
									}
								}
								else
								{
									tree2 = null;
									search2 = null;
								}

								final double[] tmp = new double[ n ];

								for ( int k = 0; k < numPoints; ++k )
								{
									final double[] p = new double[ n ];
									final double[] op = new double[ n ];

									for ( int d = 0; d < n; ++d )
									{
										final double l = rnd.nextDouble() * intersection.dimension( d ) + intersection.min( d );
										p[ d ] = (l + (rnd.nextDouble()-0.5)*error ) - interval.min( d );
										op[ d ] = (l + (rnd.nextDouble()-0.5)*error ) - otherInterval.min( d );
										tmp[ d ] = l;
									}

									int numNeighbors = 0;

									if ( excludeRadius > 0 && ipAdding == Split_Views.InterestPointAdding.IP )
									{
										final InterestPoint tmpIP = new InterestPoint( 0, tmp );
										if ( search2 != null )
										{
											search2.search( tmpIP, excludeRadius, false );
											numNeighbors += search2.numNeighbors();
										}
									}

									if ( numNeighbors == 0 )
									{
										final InterestPoint myNewIp = new InterestPoint( id++, p );
										final InterestPoint otherNewIp = new InterestPoint( otherId++, op );

										newIp.add( myNewIp );
										otherPoints.add( otherNewIp );

										if ( ipAdding == Split_Views.InterestPointAdding.CORR )
										{
											newCorrIp.add( new CorrespondingInterestPoints( myNewIp.getId(), otherViewId, fakeLabel, otherNewIp.getId() ) );
											otherCorrIp.add( new CorrespondingInterestPoints( otherNewIp.getId(), newViewId, fakeLabel, myNewIp.getId() ) );
										}
									}
								}

								otherIPLists.getInterestPointList( fakeLabel ).setInterestPoints( otherPoints );

								if ( ipAdding == Split_Views.InterestPointAdding.CORR )
									otherIPLists.getInterestPointList( fakeLabel ).setCorrespondingInterestPoints( otherCorrIp );
							}
						}

						final InterestPoints newIpl = InterestPoints.newInstance( spimData.getBasePathURI(), newViewId, fakeLabel );
						newIpl.setInterestPoints( newIp );
						newIpl.setParameters(
								( ipAdding == Split_Views.InterestPointAdding.CORR ? "Fake corresponding points " : "Fake points " ) +
								"for image splitting: " + splittingDescription +
								", pointDensity=" + pointDensity +
								", minPoints=" + minPoints +
								", maxPoints=" + maxPoints +
								", error=" + error +
								( ipAdding == Split_Views.InterestPointAdding.CORR ? "" : ", excludeRadius=" + excludeRadius ) );

						if ( ipAdding == Split_Views.InterestPointAdding.CORR )
							newIpl.setCorrespondingInterestPoints( newCorrIp );
						else
							newIpl.setCorrespondingInterestPoints( new ArrayList<>() );
						newVipl.addInterestPointList( fakeLabel, newIpl );
					}
				}
				newInterestpoints.put( newViewId, newVipl );
			}
		}

		return new SetupSplitResult( oldID, newSetups, new2oldSetupId, newSetupId2Interval,
				newSetupIds, newRegistrations, newInterestpoints );
	}

	public static long[] findMinStepSize( final AbstractSpimData< ? > data )
	{
		final BasicImgLoader imgLoader = data.getSequenceDescription().getImgLoader();

		final long[] minStepSize = new long[] { 1, 1, 1 };

		if ( MultiResolutionImgLoader.class.isInstance( imgLoader ) )
		{
			IOFunctions.println( "We have a multi-resolution image loader: " + imgLoader.getClass().getName() + ", finding resolution steps");

			final MultiResolutionImgLoader mrImgLoader = ( MultiResolutionImgLoader ) imgLoader;

			for ( final BasicViewSetup vs : data.getSequenceDescription().getViewSetupsOrdered() )
			{
				final double[][] mipmapResolutions = mrImgLoader.getSetupImgLoader( vs.getId() ).getMipmapResolutions();

				IOFunctions.println( "ViewSetup: " + vs.getName() + " (id=" + vs.getId() + "): " + Arrays.deepToString( mipmapResolutions ) );

				// lowest resolution defines the minimal steps size 
				final double[] lowestResolution = mipmapResolutions[ mipmapResolutions.length - 1 ];

				IOFunctions.println( "lowest resolution: " + Arrays.toString( lowestResolution ) );

				for ( int d = 0; d < minStepSize.length; ++d )
				{
					if ( Math.abs( lowestResolution[ d ] % 1 ) > 0.001 && ( 1.0 - Math.abs( lowestResolution[ d ] % 1 ) ) > 0.001 )
						if ( !roundMipmapResolutions )
							throw new RuntimeException( "Downsampling has a fraction > 0.001, cannot split dataset since it does not seem to be a rounding error." );

					minStepSize[ d ] = lowestCommonMultiplier( minStepSize[ d ], Math.round( lowestResolution[ d ] ) );
				}

				IOFunctions.println( "updated min step size: " + Arrays.toString( minStepSize ) );

			}
		}
		else
		{
			IOFunctions.println( "Not a multi-resolution image loader, all data splits are possible." );
		}

		IOFunctions.println( "Final minimal step size: " + Arrays.toString( minStepSize ) );

		return minStepSize;
	}

	public static long greatestCommonDivisor( long a, long b )
	{
		while (b > 0)
		{
			long temp = b;
			b = a % b;
			a = temp;
		}
		return a;
	}

	public static long lowestCommonMultiplier( final long a, final long b )
	{
		return a * (b / greatestCommonDivisor(a, b));
	}

	public static Pair< HashMap< String, Integer >, long[] > collectImageSizes( final AbstractSpimData< ? > data )
	{
		final HashMap< String, Integer > sizes = new HashMap<>();

		long[] minSize = null;

		for ( final BasicViewSetup vs : data.getSequenceDescription().getViewSetupsOrdered() )
		{
			final Dimensions dim = vs.getSize();

			String size = Long.toString( dim.dimension( 0 ) );
			for ( int d = 1; d < dim.numDimensions(); ++d )
				size += "x" + dim.dimension( d );

			if ( sizes.containsKey( size ) )
				sizes.put( size, sizes.get( size ) + 1 );
			else
				sizes.put( size, 1 );

			if ( minSize == null )
			{
				minSize = new long[ dim.numDimensions() ];
				dim.dimensions( minSize );
			}
			else
			{
				for ( int d = 0; d < dim.numDimensions(); ++d )
					minSize[ d ] = Math.min( minSize[ d ], dim.dimension( d ) );
			}
		}

		return new ValuePair<HashMap<String,Integer>, long[]>( sizes, minSize );
	}

	/**
	 * Process corresponding interest points for a single (oldSetup, newSetupId, timepoint) combination.
	 * Thread-safe: each call writes to a unique newViewId.
	 * Can be called from parallel streams, ExecutorService, or Spark.
	 *
	 * @param oldSetup The original ViewSetup
	 * @param newSetupId The new setup ID after splitting
	 * @param t The timepoint
	 * @param spimData The SpimData2 for accessing original interest points
	 * @param old2NewSetups Mapping from old setup IDs to list of new setup IDs
	 * @param newInterestpoints Map of new ViewId to ViewInterestPointLists (modified in place)
	 * @param completed Optional AtomicInteger for progress tracking (can be null)
	 * @param totalTasks Total number of tasks for progress logging (ignored if completed is null)
	 */
	public static void processCorrespondingInterestPointsStatic(
			final ViewSetup oldSetup,
			final int newSetupId,
			final TimePoint t,
			final SpimData2 spimData,
			final Map< Integer, ? extends List< Integer > > old2NewSetups,
			final Map< ViewId, ViewInterestPointLists > newInterestpoints,
			final AtomicInteger completed,
			final int totalTasks )
	{
		final ViewId oldViewId = new ViewId( t.getId(), oldSetup.getId() );
		final ViewId newViewId = new ViewId( t.getId(), newSetupId );

		final ViewInterestPointLists oldVipl = spimData.getViewInterestPoints().getViewInterestPointLists( oldViewId );
		final ViewInterestPointLists newVipl = newInterestpoints.get( newViewId );

		// only update interest points for present views
		// oldVipl may be null for missing views
		if ( spimData.getSequenceDescription().getMissingViews() != null && !spimData.getSequenceDescription().getMissingViews().getMissingViews().contains( oldViewId ) )
		{
			for ( final String label : oldVipl.getHashMap().keySet() )
			{
				final Collection<CorrespondingInterestPoints> corr = oldVipl.getInterestPointList( label ).getCorrespondingInterestPointsCopy();

				final InterestPoints newIpl = newVipl.getInterestPointList( label + "_split" );
				final Map< Integer, InterestPoint > newIpList = newIpl.getInterestPointsCopy();

				newIpl.setCorrespondingInterestPoints(
						// for each corresponding interest point entry
						corr.stream()
							.parallel()
							.filter( c -> newIpList.containsKey( c.getDetectionId() ) ) // only look at those that are in the current new viewid
							.map( c ->
								// find all new setups we have correspondences with,
								// this could be in more than one of the new views if it falls into an overlapping area
								old2NewSetups.get( c.getCorrespondingViewId().getViewSetupId() ).stream().map( corrNewSetupId ->
								{
									final String newCorrLabel = c.getCorrespodingLabel() + "_split";
									final ViewId newCorrViewId = new ViewId( t.getId(), corrNewSetupId );

									if ( newInterestpoints.get( newCorrViewId ).getInterestPointList( newCorrLabel ).getInterestPointsCopy().containsKey( c.getCorrespondingDetectionId() ) )
									{
										return new CorrespondingInterestPoints(
												c.getDetectionId(),
												newCorrViewId,
												newCorrLabel,
												c.getCorrespondingDetectionId() );
									}
									else
									{
										return null;
									}
								})
								.filter( Objects::nonNull ) )
							.flatMap( Function.identity() )
							.collect( Collectors.toList() ) );
			}
		}

		// Progress logging
		if ( completed != null )
		{
			final int done = completed.incrementAndGet();
			if ( done % 100 == 0 || done == totalTasks )
				IOFunctions.println( "Processed " + done + "/" + totalTasks + " correspondence tasks" );
		}
	}
}
