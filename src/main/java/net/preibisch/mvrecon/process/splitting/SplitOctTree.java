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

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.splitting.OctTreeSplitCriterion.SplitCorrespondence;

/**
 * Adaptive oct-tree based image splitting with recursive correspondence partitioning.
 *
 * Recursively subdivides regions until each region contains at most
 * a specified number of cross-view corresponding interest points
 * (or cannot be split further due to minStepSize constraints).
 *
 * Uses O(n log n) recursive partitioning instead of O(n × intervals) naive approach:
 * correspondences are loaded once per view, then partitioned as intervals are split.
 *
 * All tiles overlap by minStepSize to support fake corresponding points generation.
 */
public class SplitOctTree implements SplitInterval
{
	// Available criterion types for GUI selection
	public static final String[] CRITERION_NAMES = new String[] {
		CrossViewCorrespondenceCriterion.CRITERION_NAME,
		ConsensusSetCriterion.CRITERION_NAME
	};

	// Static defaults for GUI persistence
	public static int defaultCriterionChoice = 1;
	public static long[] defaultMinTileSize = null;  // initialized in setupGUI based on minStepSize
	public static int defaultMinSplitLevels = 0;
	public static int defaultAnisotropyChoice = 1;

	public static final String[] ANISOTROPY_CHOICES = {
		"Ignore anisotropy",
		"Global anisotropy factor",
		"Per-view anisotropy"
	};

	// Instance fields
	private final long[] minStepSize;
	private final int minSizeMultiplier;
	private final OctTreeSplitCriterion criterion;
	private final int minSplitLevels;
	private final double[] anisotropy; // per-dimension voxel size ratio, {1,1,1} for isotropic

	// Current context for split() method (set per ViewSetup iteration)
	private ViewId currentViewId;

	// Statistics counters (reset per split() call)
	private int splitCount;
	private int mergeCount;
	private int leafCount;

	// Aggregate statistics (accumulated across all split() calls)
	private int totalSplitCount;
	private int totalMergeCount;
	private int totalLeafCount;
	private int totalFinalBlocks;
	private int totalViewsProcessed;

	/**
	 * Result of a split operation, including intervals and statistics.
	 * Immutable, safe to return from parallel operations.
	 */
	public static class SplitStatistics
	{
		public final ArrayList< Interval > intervals;
		public final int splitCount;
		public final int mergeCount;
		public final int leafCount;

		public SplitStatistics(
				final ArrayList< Interval > intervals,
				final int splitCount,
				final int mergeCount,
				final int leafCount )
		{
			this.intervals = intervals;
			this.splitCount = splitCount;
			this.mergeCount = mergeCount;
			this.leafCount = leafCount;
		}
	}

	/**
	 * Internal result of splitting: interval + its correspondences.
	 */
	private static class InternalSplitResult
	{
		final Interval interval;
		final List< SplitCorrespondence > correspondences;

		InternalSplitResult( final Interval interval, final List< SplitCorrespondence > correspondences )
		{
			this.interval = interval;
			this.correspondences = correspondences;
		}
	}

	/**
	 * Constructor with all parameters.
	 *
	 * @param minStepSize Alignment constraint and overlap size
	 * @param minSizeMultiplier Multiplier for minimum split size (e.g., 4 means min size = 4 * minStepSize)
	 * @param criterion The splitting criterion (determines when to stop splitting)
	 * @param minSplitLevels Minimum number of split levels to always perform (0 = fully adaptive).
	 *        Use minSplitLevels=1 for TPS-compatible splitting (guaranteed 8 non-co-planar tiles).
	 * @param anisotropy Per-dimension voxel size ratio (e.g., {1, 1, 2} for 2x anisotropy in Z), or null for isotropic
	 */
	public SplitOctTree(
			final long[] minStepSize,
			final int minSizeMultiplier,
			final OctTreeSplitCriterion criterion,
			final int minSplitLevels,
			final double[] anisotropy )
	{
		this.minStepSize = minStepSize.clone();
		this.minSizeMultiplier = minSizeMultiplier;
		this.criterion = criterion;
		this.minSplitLevels = minSplitLevels;
		this.anisotropy = ( anisotropy != null ) ? anisotropy.clone() : new double[] { 1, 1, 1 };
	}

	// ==================== Static Methods for Parallel Execution ====================

	/**
	 * Static method for parallel/distributed splitting of a single view.
	 * Thread-safe: no shared mutable state, all parameters passed in, result returned.
	 *
	 * Can be called from:
	 * - Parallel streams: setups.parallelStream().map(s -> splitStatic(...))
	 * - Spark: viewRDD.map(viewId -> splitStatic(...))
	 *
	 * @param input The interval to split
	 * @param viewId The ViewId for loading correspondences
	 * @param criterion The splitting criterion (must be thread-safe / stateless)
	 * @param minStepSize Alignment constraint and overlap size
	 * @param minSizeMultiplier Multiplier for minimum split size
	 * @param minSplitLevels Minimum number of split levels (use 1 for TPS-compatible splitting)
	 * @return SplitStatistics containing intervals and statistics, or null on error
	 */
	public static SplitStatistics splitStatic(
			final Interval input,
			final ViewId viewId,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int minSizeMultiplier,
			final int minSplitLevels,
			final double[] anisotropy )
	{
		final long startTime = System.currentTimeMillis();
		IOFunctions.println( Thread.currentThread().getName() + ": Starting view " +
				viewId.getTimePointId() + "_" + viewId.getViewSetupId() + " at " + startTime );

		// Validate minSplitLevels is achievable
		if ( minSplitLevels > 0 )
		{
			final int maxAchievableLevels = computeMaxSplitLevelsStatic( input, minStepSize, minSizeMultiplier );
			if ( maxAchievableLevels < minSplitLevels )
			{
				IOFunctions.printErr( "ERROR: Cannot achieve " + minSplitLevels + " split levels for view " +
						viewId.getTimePointId() + "_" + viewId.getViewSetupId() );
				return null;
			}
		}

		// Load correspondences for this view
		final List< SplitCorrespondence > correspondences = criterion.loadCorrespondences( viewId );

		// Statistics counters (passed through recursion as array to allow mutation)
		final int[] stats = new int[ 3 ];  // [splitCount, mergeCount, leafCount]

		// Recursive splitting
		final List< InternalSplitResult > results = new ArrayList<>();
		splitRecursiveStatic( input, correspondences, results, 0,
				criterion, minStepSize, minSizeMultiplier, minSplitLevels, anisotropy, stats );

		// Extract intervals
		final ArrayList< Interval > intervals = new ArrayList<>();
		for ( final InternalSplitResult sr : results )
			intervals.add( sr.interval );

		final long endTime = System.currentTimeMillis();
		IOFunctions.println( Thread.currentThread().getName() + ": Finished view " +
				viewId.getTimePointId() + "_" + viewId.getViewSetupId() + " at " + endTime +
				" (took " + ( endTime - startTime ) + " ms)" );

		return new SplitStatistics( intervals, stats[ 0 ], stats[ 1 ], stats[ 2 ] );
	}

	/**
	 * Static recursive splitting algorithm.
	 */
	private static void splitRecursiveStatic(
			final Interval interval,
			final List< SplitCorrespondence > correspondences,
			final List< InternalSplitResult > result,
			final int depth,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int minSizeMultiplier,
			final int minSplitLevels,
			final double[] anisotropy,
			final int[] stats )
	{
		final boolean forceSplit = depth < minSplitLevels;
		final boolean criterionSplit = criterion.shouldSplit( correspondences );

		if ( !forceSplit && !criterionSplit )
		{
			result.add( new InternalSplitResult( interval, correspondences ) );
			stats[ 2 ]++;  // leafCount
			return;
		}

		// Check which dimensions can be split, using the same anisotropy-aware logic
		// as createOctantsWithOverlapStatic. When forceSplit is true, skip the 50%
		// physical extent threshold (only minimum size constraint applies).
		final boolean[] splitDim = computeSplitDimensions( interval, minStepSize, minSizeMultiplier, anisotropy, forceSplit );
		boolean canSplitAny = false;
		boolean canSplitAll = true;
		for ( final boolean b : splitDim )
		{
			if ( b ) canSplitAny = true;
			else canSplitAll = false;
		}

		if ( forceSplit && !canSplitAll )
		{
			throw new RuntimeException( "BUG: Cannot split all dimensions at depth " + depth +
					" but minSplitLevels=" + minSplitLevels + ". Interval: " + intervalToString( interval ) );
		}

		if ( !canSplitAny )
		{
			result.add( new InternalSplitResult( interval, correspondences ) );
			stats[ 2 ]++;  // leafCount
			return;
		}

		stats[ 0 ]++;  // splitCount

		if ( forceSplit )
		{
			// === FORCED PATH: split all eligible dimensions at once, no merging ===
			final List< Interval > octants =
					createOctantsWithOverlapStatic( interval, minStepSize, minSizeMultiplier, splitDim );
			final List< List< SplitCorrespondence > > partitionedCorrs =
					partitionCorrespondencesStatic( correspondences, octants );

			for ( int i = 0; i < octants.size(); i++ )
			{
				splitRecursiveStatic( octants.get( i ), partitionedCorrs.get( i ), result, depth + 1,
						criterion, minStepSize, minSizeMultiplier, minSplitLevels, anisotropy, stats );
			}
		}
		else
		{
			// === NON-FORCED PATH: split along one best dimension (binary split), no merging ===
			final int bestDim = chooseBestSplitDimension( interval, correspondences, splitDim,
					criterion, minStepSize, minSizeMultiplier );

			final boolean[] singleDimMask = new boolean[ interval.numDimensions() ];
			singleDimMask[ bestDim ] = true;

			final List< Interval > children =
					createOctantsWithOverlapStatic( interval, minStepSize, minSizeMultiplier, singleDimMask );
			final List< List< SplitCorrespondence > > childCorrs =
					partitionCorrespondencesStatic( correspondences, children );

			for ( int i = 0; i < children.size(); i++ )
			{
				splitRecursiveStatic( children.get( i ), childCorrs.get( i ), result, depth + 1,
						criterion, minStepSize, minSizeMultiplier, minSplitLevels, anisotropy, stats );
			}
		}
	}

	/**
	 * Static version of computeMaxSplitLevels.
	 */
	private static int computeMaxSplitLevelsStatic(
			final Interval interval,
			final long[] minStepSize,
			final int minSizeMultiplier )
	{
		// With anisotropy-aware splitting, we only need at least one dimension to be splittable,
		// so we take the maximum across dimensions
		int maxLevels = 0;

		for ( int d = 0; d < interval.numDimensions(); d++ )
		{
			final long dim = interval.dimension( d );
			final long minParentSize = 2 * ( minSizeMultiplier - 1 ) * minStepSize[ d ];

			if ( dim >= minParentSize )
			{
				final int levelsForDim = ( int ) Math.floor( Math.log( ( double ) dim / minParentSize ) / Math.log( 2 ) );
				maxLevels = Math.max( maxLevels, levelsForDim );
			}
		}

		return maxLevels;
	}


	/**
	 * Create child intervals by splitting the parent along the dimensions marked in splitDim.
	 *
	 * For each split dimension, computes a midpoint (snapped to minStepSize alignment)
	 * and clamps it so both halves have at least (minSizeMultiplier - 1) * minStepSize margin.
	 *
	 * Generates 2^numSplitDims children. Each child:
	 *   - Non-split dimensions: inherits the full parent range
	 *   - Split dimensions: gets lower or upper half, selected by bit index
	 *     - Lower: [parent.min, splitPoint + minStepSize - 1]
	 *     - Upper: [splitPoint - minStepSize, parent.max]
	 *   - The two halves overlap by 2 * minStepSize - 1 pixels around the split point
	 *
	 * Examples for 3D: all dims split → 8 children, two dims split → 4 children, one dim split → 2 children.
	 */
	private static List< Interval > createOctantsWithOverlapStatic(
			final Interval interval,
			final long[] minStepSize,
			final int minSizeMultiplier,
			final boolean[] splitDim )
	{
		final int n = interval.numDimensions();
		final List< Interval > octants = new ArrayList<>();

		// Build mapping from bit index to split dimensions
		final int[] splitDimIndices = new int[ n ];
		int numSplitDims = 0;
		for ( int d = 0; d < n; ++d )
			if ( splitDim[ d ] )
				splitDimIndices[ numSplitDims++ ] = d;

		final long[] splitPoints = new long[ n ];
		for ( int d = 0; d < n; ++d )
		{
			if ( !splitDim[ d ] )
				continue;

			long mid = interval.min( d ) + interval.dimension( d ) / 2;
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			final long margin = ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			splitPoints[ d ] = Math.max( interval.min( d ) + margin,
					Math.min( splitPoints[ d ], interval.max( d ) - margin + 1 ) );
		}

		final int numChildren = 1 << numSplitDims;

		for ( int i = 0; i < numChildren; ++i )
		{
			final long[] min = new long[ n ];
			final long[] max = new long[ n ];

			// Non-split dimensions: inherit full range
			for ( int d = 0; d < n; ++d )
			{
				min[ d ] = interval.min( d );
				max[ d ] = interval.max( d );
			}

			// Split dimensions: assign low or high half based on bit index
			for ( int bit = 0; bit < numSplitDims; ++bit )
			{
				final int d = splitDimIndices[ bit ];
				if ( ( i & ( 1 << bit ) ) == 0 )
				{
					min[ d ] = interval.min( d );
					max[ d ] = splitPoints[ d ] + minStepSize[ d ] - 1;
				}
				else
				{
					min[ d ] = splitPoints[ d ] - minStepSize[ d ];
					max[ d ] = interval.max( d );
				}
			}

			octants.add( new FinalInterval( min, max ) );
		}

		return octants;
	}

	/**
	 * Determine which dimensions should be split at this level based on physical extent and constraints.
	 * A dimension is split if:
	 *   1. Its physical extent is at least 50% of the largest dimension's physical extent
	 *      (skipped when forceSplit is true — only the minimum size constraint applies)
	 *   2. It is large enough to be split (per minStepSize/minSizeMultiplier constraint).
	 */
	static boolean[] computeSplitDimensions(
			final Interval interval,
			final long[] minStepSize,
			final int minSizeMultiplier,
			final double[] anisotropy,
			final boolean forceSplit )
	{
		final int n = interval.numDimensions();
		final boolean[] splitDim = new boolean[ n ];

		// Find maximum physical extent (only needed when not forcing)
		double maxPhysical = 0;
		if ( !forceSplit )
		{
			for ( int d = 0; d < n; ++d )
			{
				final double physicalExtent = interval.dimension( d ) * anisotropy[ d ];
				if ( physicalExtent > maxPhysical )
					maxPhysical = physicalExtent;
			}
		}

		for ( int d = 0; d < n; ++d )
		{
			// Check physical extent threshold (50% of largest), unless forcing
			if ( forceSplit )
				splitDim[ d ] = true;
			else
			{
				final double physicalExtent = interval.dimension( d ) * anisotropy[ d ];
				splitDim[ d ] = physicalExtent >= maxPhysical * 0.5;
			}

			// Always check minimum size constraint
			if ( splitDim[ d ] )
			{
				final long minParentSize = 2 * ( minSizeMultiplier - 1 ) * minStepSize[ d ];
				if ( interval.dimension( d ) < minParentSize )
					splitDim[ d ] = false;
			}
		}

		return splitDim;
	}

	/**
	 * Choose the best single dimension to split along by evaluating each eligible dimension.
	 *
	 * Stage 1: For each dimension, simulate a binary split and count how many children
	 * don't need further splitting (cleanCount: 0, 1, or 2). Higher is better.
	 *
	 * Stage 2 (tiebreaker): Sum the outlier ratios of both children. Lower is better.
	 *
	 * @return The dimension index of the best split dimension
	 */
	private static int chooseBestSplitDimension(
			final Interval interval,
			final List< SplitCorrespondence > correspondences,
			final boolean[] splitDim,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int minSizeMultiplier )
	{
		final int n = interval.numDimensions();
		int bestDim = -1;
		int bestCleanCount = -1;
		double bestOutlierSum = Double.MAX_VALUE;

		for ( int d = 0; d < n; d++ )
		{
			if ( !splitDim[ d ] )
				continue;

			// Create single-dim mask
			final boolean[] testSplitDim = new boolean[ n ];
			testSplitDim[ d ] = true;

			// Simulate split along this dimension → 2 children
			final List< Interval > children =
					createOctantsWithOverlapStatic( interval, minStepSize, minSizeMultiplier, testSplitDim );
			final List< List< SplitCorrespondence > > childCorrs =
					partitionCorrespondencesStatic( correspondences, children );

			// Stage 1: count clean children
			int cleanCount = 0;
			for ( int i = 0; i < children.size(); i++ )
				if ( !criterion.shouldSplit( childCorrs.get( i ) ) )
					cleanCount++;

			// Stage 2: sum outlier ratios (tiebreaker)
			double outlierSum = 0;
			for ( int i = 0; i < children.size(); i++ )
				outlierSum += ConsensusSetCriterion.computeOutlierRatio( childCorrs.get( i ) );

			// Pick best: highest cleanCount, then lowest outlierSum
			if ( cleanCount > bestCleanCount ||
				( cleanCount == bestCleanCount && outlierSum < bestOutlierSum ) )
			{
				bestDim = d;
				bestCleanCount = cleanCount;
				bestOutlierSum = outlierSum;
			}
		}

		return bestDim;
	}

	/**
	 * Partition correspondences into the child intervals (octants) created by createOctantsWithOverlapStatic.
	 * Each correspondence is added to every octant that contains its location.
	 * Correspondences in overlap zones are added to multiple octants.
	 */
	private static List< List< SplitCorrespondence > > partitionCorrespondencesStatic(
			final List< SplitCorrespondence > correspondences,
			final List< Interval > octants )
	{
		final List< List< SplitCorrespondence > > children = new ArrayList<>( octants.size() );
		for ( int i = 0; i < octants.size(); i++ )
			children.add( new ArrayList<>() );

		for ( final SplitCorrespondence corr : correspondences )
			for ( int i = 0; i < octants.size(); i++ )
				if ( contains( corr.location, octants.get( i ) ) )
					children.get( i ).add( corr );

		return children;
	}

	private static boolean contains( final double[] location, final Interval interval )
	{
		for ( int d = 0; d < location.length; d++ )
			if ( location[ d ] < interval.min( d ) || location[ d ] > interval.max( d ) )
				return false;
		return true;
	}

	// ==================== Instance Methods ====================

	/**
	 * Set the current context before calling split().
	 * This is called by SplittingTools for each ViewSetup being processed.
	 *
	 * @param viewId The current ViewId
	 * @param timepointId The timepoint ID (unused but kept for API compatibility)
	 */
	public void setCurrentContext( final ViewId viewId, final int timepointId )
	{
		this.currentViewId = viewId;
	}


	@Override
	public int maxIntervalSpread( final List< ViewSetup > oldSetups )
	{
		int max = 1;

		for ( final ViewSetup oldSetup : oldSetups )
		{
			final Interval input = new FinalInterval( oldSetup.getSize() );
			final int maxDepth = computeMaxSplitLevels( input );
			final int n = input.numDimensions();

			// Upper bound: maximum possible intervals = (2^n)^depth
			// For 3D: 8^depth, for 2D: 4^depth
			final int maxIntervals = ( int ) Math.pow( 1 << n, maxDepth );
			max = Math.max( max, maxIntervals );
		}

		return max;
	}

	@Override
	public ArrayList< Interval > split( final Interval input )
	{
		// Use static method for the actual splitting
		final SplitStatistics result = splitStatic( input, currentViewId, criterion,
				minStepSize, minSizeMultiplier, minSplitLevels, anisotropy );

		if ( result == null )
		{
			// Error already logged by splitStatic
			IOFunctions.printErr( "  Min child tile size: " + Arrays.toString( getMinTileSize() ) );
			IOFunctions.printErr( "  Min parent size needed for split: " + Arrays.toString( getMinParentSizeForSplit() ) );
			IOFunctions.printErr( "  Please reduce minSplitLevels or decrease min tile size." );
			return null;
		}

		// Copy statistics to instance fields for backward compatibility
		splitCount = result.splitCount;
		mergeCount = result.mergeCount;
		leafCount = result.leafCount;

		// Log statistics for this split
		IOFunctions.println( "Oct-tree split statistics: " + splitCount + " splits, " +
				mergeCount + " merges, " + leafCount + " leaves → " + result.intervals.size() + " final blocks" +
				( minSplitLevels > 0 ? " (minSplitLevels=" + minSplitLevels + ")" : "" ) );

		// Accumulate to totals
		totalSplitCount += splitCount;
		totalMergeCount += mergeCount;
		totalLeafCount += leafCount;
		totalFinalBlocks += result.intervals.size();
		totalViewsProcessed++;

		return result.intervals;
	}

	/**
	 * Reset aggregate statistics. Call before starting a batch of split() calls.
	 */
	public void resetTotalStatistics()
	{
		totalSplitCount = 0;
		totalMergeCount = 0;
		totalLeafCount = 0;
		totalFinalBlocks = 0;
		totalViewsProcessed = 0;
	}

	/**
	 * Print aggregate statistics summary.
	 */
	public void printTotalStatistics()
	{
		IOFunctions.println( "===== Oct-tree splitting summary =====" );
		IOFunctions.println( "Total views processed: " + totalViewsProcessed );
		IOFunctions.println( "Total splits: " + totalSplitCount );
		IOFunctions.println( "Total merges: " + totalMergeCount );
		IOFunctions.println( "Total leaves: " + totalLeafCount );
		IOFunctions.println( "Total final blocks: " + totalFinalBlocks );
		IOFunctions.println( "======================================" );
	}

	/**
	 * Compute the maximum number of split levels achievable for an interval
	 * given the minimum tile size constraints.
	 *
	 * Each split produces children with dimension ≈ parent_dim/2 + minStepSize.
	 * We need parent_dim >= 2 * (minSizeMultiplier - 1) * minStepSize to split.
	 *
	 * @param interval The input interval
	 * @return Maximum number of split levels (0 if can't split at all)
	 */
	private int computeMaxSplitLevels( final Interval interval )
	{
		int maxLevels = Integer.MAX_VALUE;

		for ( int d = 0; d < interval.numDimensions(); d++ )
		{
			final long dim = interval.dimension( d );
			// Minimum parent size to allow a split (ensures children >= minSizeMultiplier * minStepSize)
			final long minParentSize = 2 * ( minSizeMultiplier - 1 ) * minStepSize[ d ];

			if ( dim < minParentSize )
			{
				maxLevels = 0;
			}
			else
			{
				// Approximate: each level roughly halves the dimension (ignoring overlap for simplicity)
				final int levelsForDim = (int) Math.floor( Math.log( (double) dim / minParentSize ) / Math.log( 2 ) );
				maxLevels = Math.min( maxLevels, levelsForDim );
			}
		}

		return maxLevels == Integer.MAX_VALUE ? 0 : maxLevels;
	}

	/**
	 * Get the minimum child tile size array (the actual minimum tile size after splitting).
	 */
	private long[] getMinTileSize()
	{
		final long[] minTileSize = new long[ minStepSize.length ];
		for ( int d = 0; d < minStepSize.length; d++ )
			minTileSize[ d ] = minSizeMultiplier * minStepSize[ d ];
		return minTileSize;
	}

	/**
	 * Get the minimum parent size needed to allow a split.
	 */
	private long[] getMinParentSizeForSplit()
	{
		final long[] minParentSize = new long[ minStepSize.length ];
		for ( int d = 0; d < minStepSize.length; d++ )
			minParentSize[ d ] = 2 * ( minSizeMultiplier - 1 ) * minStepSize[ d ];
		return minParentSize;
	}

	/**
	 * Convert interval to readable string.
	 */
	private static String intervalToString( final Interval interval )
	{
		final StringBuilder sb = new StringBuilder( "[" );
		for ( int d = 0; d < interval.numDimensions(); d++ )
		{
			if ( d > 0 ) sb.append( ", " );
			sb.append( interval.dimension( d ) );
		}
		sb.append( "]" );
		return sb.toString();
	}

	@Override
	public String description()
	{
		return "OctTree adaptive splitting: " + criterion.description() +
				", minStepSize=" + Arrays.toString( minStepSize ) +
				", minSizeMultiplier=" + minSizeMultiplier +
				", minSplitLevels=" + minSplitLevels;
	}

	// ==================== Static GUI Methods ====================

	/**
	 * Setup GUI components for oct-tree splitting parameters.
	 */
	public static boolean setupGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		final GenericDialog gdCriterion = new GenericDialog( "Oct-Tree Split Criterion" );
		gdCriterion.addChoice( "Split_criterion", CRITERION_NAMES, CRITERION_NAMES[ defaultCriterionChoice ] );
		gdCriterion.showDialog();

		if ( gdCriterion.wasCanceled() )
			return false;

		defaultCriterionChoice = gdCriterion.getNextChoiceIndex();
		final String selectedCriterion = CRITERION_NAMES[ defaultCriterionChoice ];

		gd.addMessage( "Oct-tree adaptive splitting: " + selectedCriterion,
				GUIHelper.mediumstatusfont, Color.BLACK );

		boolean success = false;
		if ( selectedCriterion.equals( CrossViewCorrespondenceCriterion.CRITERION_NAME ) )
			success = CrossViewCorrespondenceCriterion.setupGUI( gd, data );
		else if ( selectedCriterion.equals( ConsensusSetCriterion.CRITERION_NAME ) )
			success = ConsensusSetCriterion.setupGUI( gd, data );

		if ( !success )
			return false;

		// Initialize defaults if not set or wrong dimension
		if ( defaultMinTileSize == null || defaultMinTileSize.length != minStepSize.length )
		{
			defaultMinTileSize = new long[ minStepSize.length ];
			for ( int d = 0; d < minStepSize.length; d++ )
				defaultMinTileSize[ d ] = 4 * minStepSize[ d ];  // default multiplier = 4
		}

		// Add per-dimension sliders for minimum tile size
		final String[] dimNames = { "X", "Y", "Z" };
		gd.addMessage( "Minimum tile size per dimension (must be >= 4 × minStepSize and divisible by minStepSize):",
				GUIHelper.smallStatusFont, Color.DARK_GRAY );

		for ( int d = 0; d < minStepSize.length; d++ )
		{
			final long step = minStepSize[ d ];
			final long minVal = 4 * step;
			final long maxVal = 32 * step;
			final String label = "Min_tile_size_" + dimNames[ d ] + " (step=" + step + ")";
			gd.addSlider( label, minVal, maxVal, defaultMinTileSize[ d ], step );
		}

		gd.addSlider( "Min_split_levels", 0, 5, defaultMinSplitLevels );
		gd.addMessage(
				"Minimum split levels: 0=fully adaptive, 1=always split at least once (8 tiles),\n" +
				"2=always split twice (up to 64 tiles), etc. Overridden by tile size constraint.",
				GUIHelper.smallStatusFont, Color.DARK_GRAY );

		// Anisotropy
		final List< ViewId > allViewIds = new ArrayList<>();
		for ( final mpicbg.spim.data.sequence.ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() )
				allViewIds.add( vd );

		final double avgAnisoF = TransformationTools.getAverageAnisotropyFactor( data, allViewIds );

		gd.addChoice( "Anisotropy", ANISOTROPY_CHOICES, ANISOTROPY_CHOICES[ defaultAnisotropyChoice ] );
		gd.addMessage(
				"Average anisotropy factor: " + TransformationTools.f.format( avgAnisoF ) + " (Z / avg(XY))\n" +
				"Skips splitting dimensions whose physical extent is < 50% of the largest.",
				GUIHelper.smallStatusFont, Color.DARK_GRAY );

		return true;
	}

	/**
	 * Query GUI components and create SplitOctTree instance.
	 */
	public static SplitOctTree queryGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		final String criterionName = CRITERION_NAMES[ defaultCriterionChoice ];

		OctTreeSplitCriterion criterion = null;

		if ( criterionName.equals( CrossViewCorrespondenceCriterion.CRITERION_NAME ) )
			criterion = CrossViewCorrespondenceCriterion.queryGUI( gd, data );
		else if ( criterionName.equals( ConsensusSetCriterion.CRITERION_NAME ) )
			criterion = ConsensusSetCriterion.queryGUI( gd, data );

		if ( criterion == null )
			return null;

		// Read per-dimension tile sizes
		final long[] tileSizes = new long[ minStepSize.length ];
		final String[] dimNames = { "X", "Y", "Z" };

		for ( int d = 0; d < minStepSize.length; d++ )
		{
			tileSizes[ d ] = Math.round( gd.getNextNumber() );

			// Validate: must be divisible by minStepSize
			if ( tileSizes[ d ] % minStepSize[ d ] != 0 )
			{
				IOFunctions.printErr( "ERROR: Min tile size " + dimNames[ d ] + " (" + tileSizes[ d ] +
						") must be divisible by minStepSize (" + minStepSize[ d ] + ")" );
				return null;
			}

			// Validate: must be >= 4 * minStepSize
			final long minAllowed = 4 * minStepSize[ d ];
			if ( tileSizes[ d ] < minAllowed )
			{
				IOFunctions.printErr( "ERROR: Min tile size " + dimNames[ d ] + " (" + tileSizes[ d ] +
						") must be >= " + minAllowed + " (4 × minStepSize)" );
				return null;
			}

			// Store as default for next time
			defaultMinTileSize[ d ] = tileSizes[ d ];
		}

		// Compute multiplier as minimum ratio across dimensions
		int minSizeMultiplier = Integer.MAX_VALUE;
		for ( int d = 0; d < minStepSize.length; d++ )
		{
			final int multiplier = ( int ) ( tileSizes[ d ] / minStepSize[ d ] );
			minSizeMultiplier = Math.min( minSizeMultiplier, multiplier );
		}

		final int minSplitLevels = defaultMinSplitLevels = Math.max( 0, (int) Math.round( gd.getNextNumber() ) );

		// Anisotropy
		final int anisotropyChoice = defaultAnisotropyChoice = gd.getNextChoiceIndex();
		final double[] anisotropy;

		if ( anisotropyChoice == 0 )
		{
			// Ignore anisotropy
			anisotropy = new double[] { 1, 1, 1 };
		}
		else
		{
			// Global or per-view: use average anisotropy factor for now
			final List< ViewId > allViewIds = new ArrayList<>();
			for ( final mpicbg.spim.data.sequence.ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
				if ( vd.isPresent() )
					allViewIds.add( vd );

			final double avgAnisoF = TransformationTools.getAverageAnisotropyFactor( data, allViewIds );
			anisotropy = new double[] { 1, 1, avgAnisoF };

			IOFunctions.println( "Using anisotropy factor: " + Arrays.toString( anisotropy ) +
					" (" + ANISOTROPY_CHOICES[ anisotropyChoice ] + ")" );
		}

		IOFunctions.println( "Created oct-tree splitter: " + criterion.description() +
				", minTileSize=" + Arrays.toString( tileSizes ) +
				", multiplier=" + minSizeMultiplier +
				", minSplitLevels=" + minSplitLevels +
				", anisotropy=" + Arrays.toString( anisotropy ) );

		return new SplitOctTree( minStepSize, minSizeMultiplier, criterion, minSplitLevels, anisotropy );
	}

	// Getters for testing
	public long[] getMinStepSize() { return minStepSize.clone(); }
	public int getMinSizeMultiplier() { return minSizeMultiplier; }
	public OctTreeSplitCriterion getCriterion() { return criterion; }
	public ViewId getCurrentViewId() { return currentViewId; }
	public int getMinSplitLevels() { return minSplitLevels; }
	public double[] getAnisotropy() { return anisotropy.clone(); }
}
