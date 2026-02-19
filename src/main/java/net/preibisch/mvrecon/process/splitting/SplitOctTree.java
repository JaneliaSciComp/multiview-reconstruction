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
import java.util.List;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
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
	public static int defaultCriterionChoice = 0;
	public static long[] defaultMinTileSize = null;  // initialized in setupGUI based on minStepSize
	public static boolean defaultEnableMerge = true;
	public static int defaultMinSplitLevels = 0;

	// Instance fields
	private final long[] minStepSize;
	private final int minSizeMultiplier;
	private final OctTreeSplitCriterion criterion;
	private final boolean enableMerge;
	private final int minSplitLevels;

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
	 * @param enableMerge If true, attempt to merge blocks back when combined count is below threshold
	 * @param minSplitLevels Minimum number of split levels to always perform (0 = fully adaptive)
	 */
	public SplitOctTree(
			final long[] minStepSize,
			final int minSizeMultiplier,
			final OctTreeSplitCriterion criterion,
			final boolean enableMerge,
			final int minSplitLevels )
	{
		this.minStepSize = minStepSize.clone();
		this.minSizeMultiplier = minSizeMultiplier;
		this.criterion = criterion;
		this.enableMerge = enableMerge;
		this.minSplitLevels = minSplitLevels;
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
	 * @param enableMerge If true, attempt to merge blocks back
	 * @param minSplitLevels Minimum number of split levels
	 * @return SplitStatistics containing intervals and statistics, or null on error
	 */
	public static SplitStatistics splitStatic(
			final Interval input,
			final ViewId viewId,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int minSizeMultiplier,
			final boolean enableMerge,
			final int minSplitLevels )
	{
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
				criterion, minStepSize, minSizeMultiplier, enableMerge, minSplitLevels, stats );

		// Extract intervals
		final ArrayList< Interval > intervals = new ArrayList<>();
		for ( final InternalSplitResult sr : results )
			intervals.add( sr.interval );

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
			final boolean enableMerge,
			final int minSplitLevels,
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

		if ( !canSplitFurtherStatic( interval, minStepSize, minSizeMultiplier ) )
		{
			if ( forceSplit )
			{
				throw new RuntimeException( "BUG: Cannot split further at depth " + depth +
						" but minSplitLevels=" + minSplitLevels + ". Interval: " + intervalToString( interval ) );
			}
			result.add( new InternalSplitResult( interval, correspondences ) );
			stats[ 2 ]++;  // leafCount
			return;
		}

		stats[ 0 ]++;  // splitCount
		final List< Interval > octants = createOctantsWithOverlapStatic( interval, minStepSize, minSizeMultiplier );
		final List< List< SplitCorrespondence > > partitionedCorrs =
				partitionCorrespondencesStatic( correspondences, interval, minStepSize, minSizeMultiplier );

		final List< List< InternalSplitResult > > octantResults = new ArrayList<>();
		for ( int i = 0; i < octants.size(); i++ )
		{
			final List< InternalSplitResult > childResults = new ArrayList<>();
			splitRecursiveStatic( octants.get( i ), partitionedCorrs.get( i ), childResults, depth + 1,
					criterion, minStepSize, minSizeMultiplier, enableMerge, minSplitLevels, stats );
			octantResults.add( childResults );
		}

		if ( enableMerge && depth >= minSplitLevels )
		{
			final List< InternalSplitResult > merged = mergeOctantResultsStatic( octantResults, interval,
					criterion, minStepSize, minSizeMultiplier, stats );
			result.addAll( merged );
		}
		else
		{
			for ( final List< InternalSplitResult > childResults : octantResults )
				result.addAll( childResults );
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
		int maxLevels = Integer.MAX_VALUE;

		for ( int d = 0; d < interval.numDimensions(); d++ )
		{
			final long dim = interval.dimension( d );
			final long minParentSize = 2 * ( minSizeMultiplier - 1 ) * minStepSize[ d ];

			if ( dim < minParentSize )
			{
				maxLevels = 0;
			}
			else
			{
				final int levelsForDim = ( int ) Math.floor( Math.log( ( double ) dim / minParentSize ) / Math.log( 2 ) );
				maxLevels = Math.min( maxLevels, levelsForDim );
			}
		}

		return maxLevels == Integer.MAX_VALUE ? 0 : maxLevels;
	}

	/**
	 * Static version of canSplitFurther.
	 */
	private static boolean canSplitFurtherStatic(
			final Interval interval,
			final long[] minStepSize,
			final int minSizeMultiplier )
	{
		for ( int d = 0; d < interval.numDimensions(); ++d )
		{
			final long minParentSize = 2 * ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			if ( interval.dimension( d ) < minParentSize )
				return false;
		}
		return true;
	}

	/**
	 * Static version of createOctantsWithOverlap.
	 */
	private static List< Interval > createOctantsWithOverlapStatic(
			final Interval interval,
			final long[] minStepSize,
			final int minSizeMultiplier )
	{
		final int n = interval.numDimensions();
		final List< Interval > octants = new ArrayList<>();

		final long[] splitPoints = new long[ n ];
		for ( int d = 0; d < n; ++d )
		{
			long mid = interval.min( d ) + interval.dimension( d ) / 2;
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			final long margin = ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			splitPoints[ d ] = Math.max( interval.min( d ) + margin,
					Math.min( splitPoints[ d ], interval.max( d ) - margin + 1 ) );
		}

		final int numOctants = 1 << n;

		for ( int i = 0; i < numOctants; ++i )
		{
			final long[] min = new long[ n ];
			final long[] max = new long[ n ];

			for ( int d = 0; d < n; ++d )
			{
				if ( ( i & ( 1 << d ) ) == 0 )
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
	 * Static version of partitionCorrespondences.
	 */
	private static List< List< SplitCorrespondence > > partitionCorrespondencesStatic(
			final List< SplitCorrespondence > correspondences,
			final Interval interval,
			final long[] minStepSize,
			final int minSizeMultiplier )
	{
		final int n = interval.numDimensions();

		final double[] splitPoints = new double[ n ];
		for ( int d = 0; d < n; d++ )
		{
			final long mid = interval.min( d ) + interval.dimension( d ) / 2;
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			final long margin = ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			splitPoints[ d ] = Math.max( interval.min( d ) + margin,
					Math.min( splitPoints[ d ], interval.max( d ) - margin + 1 ) );
		}

		final int numChildren = 1 << n;
		final List< List< SplitCorrespondence > > children = new ArrayList<>( numChildren );
		for ( int i = 0; i < numChildren; i++ )
			children.add( new ArrayList<>() );

		for ( final SplitCorrespondence corr : correspondences )
		{
			final int[] belongsTo = new int[ n ];
			for ( int d = 0; d < n; d++ )
			{
				final double low = splitPoints[ d ] - minStepSize[ d ];
				final double high = splitPoints[ d ] + minStepSize[ d ];
				if ( corr.location[ d ] < low )
					belongsTo[ d ] = 0;
				else if ( corr.location[ d ] >= high )
					belongsTo[ d ] = 1;
				else
					belongsTo[ d ] = 2;
			}

			addToChildrenStatic( children, corr, belongsTo, 0, 0, n );
		}

		return children;
	}

	/**
	 * Static version of addToChildren.
	 */
	private static void addToChildrenStatic(
			final List< List< SplitCorrespondence > > children,
			final SplitCorrespondence corr,
			final int[] belongsTo,
			final int dim,
			final int idx,
			final int n )
	{
		if ( dim == n )
		{
			children.get( idx ).add( corr );
			return;
		}
		if ( belongsTo[ dim ] == 0 || belongsTo[ dim ] == 2 )
			addToChildrenStatic( children, corr, belongsTo, dim + 1, idx, n );
		if ( belongsTo[ dim ] == 1 || belongsTo[ dim ] == 2 )
			addToChildrenStatic( children, corr, belongsTo, dim + 1, idx | ( 1 << dim ), n );
	}

	/**
	 * Static version of mergeOctantResults.
	 */
	private static List< InternalSplitResult > mergeOctantResultsStatic(
			final List< List< InternalSplitResult > > octantResults,
			final Interval parentInterval,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int minSizeMultiplier,
			final int[] stats )
	{
		final List< InternalSplitResult > allResults = new ArrayList<>();
		final List< SplitCorrespondence > allCorrespondences = new ArrayList<>();
		for ( final List< InternalSplitResult > childResults : octantResults )
		{
			for ( final InternalSplitResult sr : childResults )
			{
				allResults.add( sr );
				allCorrespondences.addAll( sr.correspondences );
			}
		}

		final int inputCount = allResults.size();

		if ( criterion.canMerge( allCorrespondences ) )
		{
			stats[ 1 ] += inputCount - 1;  // mergeCount
			return java.util.Collections.singletonList( new InternalSplitResult( parentInterval, allCorrespondences ) );
		}

		// For simplicity in static version, just return all results without further merge attempts
		// (The instance version has more complex merge strategies)
		return allResults;
	}

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
				minStepSize, minSizeMultiplier, enableMerge, minSplitLevels );

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

	/**
	 * Recursive oct-tree splitting algorithm with correspondence partitioning.
	 *
	 * @param interval Current interval to potentially split
	 * @param correspondences Correspondences within this interval
	 * @param result List to collect final InternalSplitResults
	 * @param depth Current recursion depth (0 = root)
	 */
	private void splitRecursive(
			final Interval interval,
			final List< SplitCorrespondence > correspondences,
			final List< InternalSplitResult > result,
			final int depth )
	{
		// Determine if we should split:
		// 1. Force split if we haven't reached minSplitLevels yet
		// 2. Otherwise, check criterion
		final boolean forceSplit = depth < minSplitLevels;
		final boolean criterionSplit = criterion.shouldSplit( correspondences );

		if ( !forceSplit && !criterionSplit )
		{
			// Current interval is within threshold and we've reached min levels, add as leaf
			result.add( new InternalSplitResult( interval, correspondences ) );
			leafCount++;
			return;
		}

		// Want to split - check if we can split further (size constraint)
		if ( !canSplitFurther( interval ) )
		{
			if ( forceSplit )
			{
				throw new RuntimeException( "BUG: Cannot split further at depth " + depth +
						" but minSplitLevels=" + minSplitLevels + ". Interval: " + intervalToString( interval ) );
			}
			// Can't split further due to size constraint, add as leaf
			result.add( new InternalSplitResult( interval, correspondences ) );
			leafCount++;
			return;
		}

		// Split into octants and partition correspondences
		splitCount++;
		final List< Interval > octants = createOctantsWithOverlap( interval );
		final List< List< SplitCorrespondence > > partitionedCorrs = partitionCorrespondences( correspondences, interval );

		// Recurse on each octant with its partitioned correspondences
		final List< List< InternalSplitResult > > octantResults = new ArrayList<>();
		for ( int i = 0; i < octants.size(); i++ )
		{
			final List< InternalSplitResult > childResults = new ArrayList<>();
			splitRecursive( octants.get( i ), partitionedCorrs.get( i ), childResults, depth + 1 );
			octantResults.add( childResults );
		}

		// Try to merge sibling octant results (bottom-up merge phase) if enabled
		if ( enableMerge && depth >= minSplitLevels )
		{
			final List< InternalSplitResult > merged = mergeOctantResults( octantResults, interval );
			result.addAll( merged );
		}
		else
		{
			// No merge - just collect all results
			for ( final List< InternalSplitResult > childResults : octantResults )
				result.addAll( childResults );
		}
	}

	/**
	 * Partition correspondences into 2^n child lists based on split points.
	 * Correspondences in overlap regions are duplicated to both adjacent children.
	 *
	 * @param correspondences The correspondences to partition
	 * @param interval The interval being split
	 * @return List of correspondence lists, one per child octant
	 */
	private List< List< SplitCorrespondence > > partitionCorrespondences(
			final List< SplitCorrespondence > correspondences,
			final Interval interval )
	{
		final int n = interval.numDimensions();

		// Calculate split points (aligned to minStepSize, same as createOctantsWithOverlap)
		final double[] splitPoints = new double[ n ];
		for ( int d = 0; d < n; d++ )
		{
			final long mid = interval.min( d ) + interval.dimension( d ) / 2;
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			// Clamp to ensure each child is at least minSizeMultiplier * minStepSize
			final long margin = ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			splitPoints[ d ] = Math.max( interval.min( d ) + margin,
					Math.min( splitPoints[ d ], interval.max( d ) - margin + 1 ) );
		}

		// Initialize 2^n child lists
		final int numChildren = 1 << n;
		final List< List< SplitCorrespondence > > children = new ArrayList<>( numChildren );
		for ( int i = 0; i < numChildren; i++ )
			children.add( new ArrayList<>() );

		// Partition each correspondence
		for ( final SplitCorrespondence corr : correspondences )
		{
			// For each dimension, determine which sides this point belongs to
			// Points in overlap region (splitPoint ± minStepSize) go to BOTH sides
			final int[] belongsTo = new int[ n ];  // 0=lower, 1=upper, 2=both
			for ( int d = 0; d < n; d++ )
			{
				final double low = splitPoints[ d ] - minStepSize[ d ];
				final double high = splitPoints[ d ] + minStepSize[ d ];
				if ( corr.location[ d ] < low )
					belongsTo[ d ] = 0;  // lower only
				else if ( corr.location[ d ] >= high )
					belongsTo[ d ] = 1;  // upper only
				else
					belongsTo[ d ] = 2;  // overlap - both
			}

			// Add to all applicable children (handles overlap duplication)
			addToChildren( children, corr, belongsTo, 0, 0, n );
		}

		return children;
	}

	/**
	 * Recursively add correspondence to all applicable children based on belongsTo flags.
	 */
	private void addToChildren(
			final List< List< SplitCorrespondence > > children,
			final SplitCorrespondence corr,
			final int[] belongsTo,
			final int dim,
			final int idx,
			final int n )
	{
		if ( dim == n )
		{
			children.get( idx ).add( corr );
			return;
		}
		if ( belongsTo[ dim ] == 0 || belongsTo[ dim ] == 2 )  // lower or both
			addToChildren( children, corr, belongsTo, dim + 1, idx, n );
		if ( belongsTo[ dim ] == 1 || belongsTo[ dim ] == 2 )  // upper or both
			addToChildren( children, corr, belongsTo, dim + 1, idx | ( 1 << dim ), n );
	}

	/**
	 * Attempt to merge octant results when combined count is below threshold.
	 *
	 * @param octantResults List of InternalSplitResults for each octant
	 * @param parentInterval The parent interval that was split
	 * @return Merged results
	 */
	private List< InternalSplitResult > mergeOctantResults(
			final List< List< InternalSplitResult > > octantResults,
			final Interval parentInterval )
	{
		// Flatten all results
		final List< InternalSplitResult > allResults = new ArrayList<>();
		final List< SplitCorrespondence > allCorrespondences = new ArrayList<>();
		for ( final List< InternalSplitResult > childResults : octantResults )
		{
			for ( final InternalSplitResult sr : childResults )
			{
				allResults.add( sr );
				allCorrespondences.addAll( sr.correspondences );
			}
		}

		final int inputCount = allResults.size();

		// 1. Try full merge (all octants → parent)
		if ( criterion.canMerge( allCorrespondences ) )
		{
			mergeCount += inputCount - 1;
			return java.util.Collections.singletonList( new InternalSplitResult( parentInterval, allCorrespondences ) );
		}

		// Track best result
		List< InternalSplitResult > bestResult = allResults;
		int bestCount = allResults.size();

		// 2. Try half-space merges
		final List< InternalSplitResult > halfMerged = tryMergeHalvesBest( octantResults, parentInterval );
		if ( halfMerged != null && halfMerged.size() < bestCount )
		{
			bestResult = halfMerged;
			bestCount = halfMerged.size();
		}

		// 3. Try quadrant merges
		final List< InternalSplitResult > quadMerged = tryMergeQuadrantsBest( octantResults, parentInterval );
		if ( quadMerged != null && quadMerged.size() < bestCount )
		{
			bestResult = quadMerged;
			bestCount = quadMerged.size();
		}

		// 4. Try individual octant merges
		final List< InternalSplitResult > pairMerged = tryMergeIndividualOctants( octantResults, parentInterval );
		if ( pairMerged != null && pairMerged.size() < bestCount )
		{
			bestResult = pairMerged;
			bestCount = pairMerged.size();
		}

		if ( bestCount < inputCount )
			mergeCount += inputCount - bestCount;

		return bestResult;
	}

	/**
	 * Try all half-space merge options.
	 */
	private List< InternalSplitResult > tryMergeHalvesBest(
			final List< List< InternalSplitResult > > octantResults,
			final Interval parentInterval )
	{
		final int n = parentInterval.numDimensions();
		final int numOctants = octantResults.size();

		List< InternalSplitResult > bestResult = null;
		int bestCount = Integer.MAX_VALUE;

		for ( int splitDim = 0; splitDim < n; splitDim++ )
		{
			final List< InternalSplitResult > lowerResults = new ArrayList<>();
			final List< InternalSplitResult > upperResults = new ArrayList<>();
			final List< SplitCorrespondence > lowerCorrs = new ArrayList<>();
			final List< SplitCorrespondence > upperCorrs = new ArrayList<>();

			for ( int i = 0; i < numOctants; i++ )
			{
				if ( ( i & ( 1 << splitDim ) ) == 0 )
				{
					for ( final InternalSplitResult sr : octantResults.get( i ) )
					{
						lowerResults.add( sr );
						lowerCorrs.addAll( sr.correspondences );
					}
				}
				else
				{
					for ( final InternalSplitResult sr : octantResults.get( i ) )
					{
						upperResults.add( sr );
						upperCorrs.addAll( sr.correspondences );
					}
				}
			}

			final boolean canMergeLower = criterion.canMerge( lowerCorrs );
			final boolean canMergeUpper = criterion.canMerge( upperCorrs );

			final List< InternalSplitResult > result = new ArrayList<>();
			if ( canMergeLower )
				result.add( new InternalSplitResult( createHalfInterval( parentInterval, splitDim, false ), lowerCorrs ) );
			else
				result.addAll( lowerResults );

			if ( canMergeUpper )
				result.add( new InternalSplitResult( createHalfInterval( parentInterval, splitDim, true ), upperCorrs ) );
			else
				result.addAll( upperResults );

			if ( ( canMergeLower || canMergeUpper ) && result.size() < bestCount )
			{
				bestResult = result;
				bestCount = result.size();
			}
		}

		return bestResult;
	}

	/**
	 * Try all quadrant merge options.
	 */
	private List< InternalSplitResult > tryMergeQuadrantsBest(
			final List< List< InternalSplitResult > > octantResults,
			final Interval parentInterval )
	{
		final int n = parentInterval.numDimensions();
		if ( n < 2 )
			return null;

		final int numOctants = octantResults.size();

		List< InternalSplitResult > bestResult = null;
		int bestCount = Integer.MAX_VALUE;

		for ( int fixedDim = 0; fixedDim < n; fixedDim++ )
		{
			final int numQuadrants = 1 << ( n - 1 );
			final List< List< InternalSplitResult > > quadrantResults = new ArrayList<>();
			final List< List< SplitCorrespondence > > quadrantCorrs = new ArrayList<>();
			final boolean[] canMergeQuadrant = new boolean[ numQuadrants ];

			for ( int q = 0; q < numQuadrants; q++ )
			{
				quadrantResults.add( new ArrayList<>() );
				quadrantCorrs.add( new ArrayList<>() );
			}

			for ( int i = 0; i < numOctants; i++ )
			{
				int quadrantIdx = 0;
				int bitPos = 0;
				for ( int d = 0; d < n; d++ )
				{
					if ( d != fixedDim )
					{
						if ( ( i & ( 1 << d ) ) != 0 )
							quadrantIdx |= ( 1 << bitPos );
						bitPos++;
					}
				}
				for ( final InternalSplitResult sr : octantResults.get( i ) )
				{
					quadrantResults.get( quadrantIdx ).add( sr );
					quadrantCorrs.get( quadrantIdx ).addAll( sr.correspondences );
				}
			}

			int mergeableCount = 0;
			for ( int q = 0; q < numQuadrants; q++ )
			{
				canMergeQuadrant[ q ] = criterion.canMerge( quadrantCorrs.get( q ) );
				if ( canMergeQuadrant[ q ] )
					mergeableCount++;
			}

			if ( mergeableCount > 0 )
			{
				final List< InternalSplitResult > result = new ArrayList<>();
				for ( int q = 0; q < numQuadrants; q++ )
				{
					if ( canMergeQuadrant[ q ] )
						result.add( new InternalSplitResult( createQuadrantInterval( parentInterval, fixedDim, q, n ), quadrantCorrs.get( q ) ) );
					else
						result.addAll( quadrantResults.get( q ) );
				}

				if ( result.size() < bestCount )
				{
					bestResult = result;
					bestCount = result.size();
				}
			}
		}

		return bestResult;
	}

	/**
	 * Try to merge individual octants.
	 */
	private List< InternalSplitResult > tryMergeIndividualOctants(
			final List< List< InternalSplitResult > > octantResults,
			final Interval parentInterval )
	{
		final List< Interval > octants = createOctantsWithOverlap( parentInterval );
		final List< InternalSplitResult > result = new ArrayList<>();
		boolean anyMerged = false;

		for ( int i = 0; i < octantResults.size(); i++ )
		{
			final List< InternalSplitResult > childResults = octantResults.get( i );
			if ( childResults.size() > 1 )
			{
				final List< SplitCorrespondence > combinedCorrs = new ArrayList<>();
				for ( final InternalSplitResult sr : childResults )
					combinedCorrs.addAll( sr.correspondences );

				if ( criterion.canMerge( combinedCorrs ) )
				{
					result.add( new InternalSplitResult( octants.get( i ), combinedCorrs ) );
					anyMerged = true;
					continue;
				}
			}
			result.addAll( childResults );
		}

		return anyMerged ? result : null;
	}

	/**
	 * Create a half-interval by splitting parent along one dimension.
	 */
	private Interval createHalfInterval( final Interval parent, final int splitDim, final boolean upper )
	{
		final int n = parent.numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		long mid = parent.min( splitDim ) + parent.dimension( splitDim ) / 2;
		long splitPoint = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ splitDim ] );
		final long margin = ( minSizeMultiplier - 1 ) * minStepSize[ splitDim ];
		splitPoint = Math.max( parent.min( splitDim ) + margin,
				Math.min( splitPoint, parent.max( splitDim ) - margin + 1 ) );

		for ( int d = 0; d < n; d++ )
		{
			if ( d == splitDim )
			{
				if ( upper )
				{
					min[ d ] = splitPoint - minStepSize[ d ];
					max[ d ] = parent.max( d );
				}
				else
				{
					min[ d ] = parent.min( d );
					max[ d ] = splitPoint + minStepSize[ d ] - 1;
				}
			}
			else
			{
				min[ d ] = parent.min( d );
				max[ d ] = parent.max( d );
			}
		}

		return new FinalInterval( min, max );
	}

	/**
	 * Create a quadrant interval.
	 */
	private Interval createQuadrantInterval( final Interval parent, final int fixedDim, final int quadrantIdx, final int n )
	{
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		final long[] splitPoints = new long[ n ];
		for ( int d = 0; d < n; d++ )
		{
			long mid = parent.min( d ) + parent.dimension( d ) / 2;
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			final long margin = ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			splitPoints[ d ] = Math.max( parent.min( d ) + margin,
					Math.min( splitPoints[ d ], parent.max( d ) - margin + 1 ) );
		}

		int bitPos = 0;
		for ( int d = 0; d < n; d++ )
		{
			if ( d == fixedDim )
			{
				min[ d ] = parent.min( d );
				max[ d ] = parent.max( d );
			}
			else
			{
				final boolean upper = ( quadrantIdx & ( 1 << bitPos ) ) != 0;
				if ( upper )
				{
					min[ d ] = splitPoints[ d ] - minStepSize[ d ];
					max[ d ] = parent.max( d );
				}
				else
				{
					min[ d ] = parent.min( d );
					max[ d ] = splitPoints[ d ] + minStepSize[ d ] - 1;
				}
				bitPos++;
			}
		}

		return new FinalInterval( min, max );
	}

	/**
	 * Check if the interval can be split further.
	 *
	 * After splitting, each child has dimension ≈ parent_dim/2 + minStepSize (due to overlap).
	 * For children to be at least minSizeMultiplier * minStepSize, we need:
	 * parent_dim/2 + minStepSize >= minSizeMultiplier * minStepSize
	 * parent_dim >= 2 * (minSizeMultiplier - 1) * minStepSize
	 */
	private boolean canSplitFurther( final Interval interval )
	{
		for ( int d = 0; d < interval.numDimensions(); ++d )
		{
			// Ensure children will be at least minSizeMultiplier * minStepSize
			final long minParentSize = 2 * ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			if ( interval.dimension( d ) < minParentSize )
				return false;
		}
		return true;
	}

	/**
	 * Creates octants from the input interval with overlap.
	 */
	private List< Interval > createOctantsWithOverlap( final Interval interval )
	{
		final int n = interval.numDimensions();
		final List< Interval > octants = new ArrayList<>();

		final long[] splitPoints = new long[ n ];
		for ( int d = 0; d < n; ++d )
		{
			long mid = interval.min( d ) + interval.dimension( d ) / 2;
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			// Clamp to ensure each child is at least minSizeMultiplier * minStepSize
			// Child dimension = splitPoint - interval.min + minStepSize (for lower)
			// For child >= M * minStepSize: splitPoint >= interval.min + (M-1) * minStepSize
			final long margin = ( minSizeMultiplier - 1 ) * minStepSize[ d ];
			splitPoints[ d ] = Math.max( interval.min( d ) + margin,
					Math.min( splitPoints[ d ], interval.max( d ) - margin + 1 ) );
		}

		final int numOctants = 1 << n;

		for ( int i = 0; i < numOctants; ++i )
		{
			final long[] min = new long[ n ];
			final long[] max = new long[ n ];

			for ( int d = 0; d < n; ++d )
			{
				if ( ( i & ( 1 << d ) ) == 0 )
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

	@Override
	public String description()
	{
		return "OctTree adaptive splitting: " + criterion.description() +
				", minStepSize=" + Arrays.toString( minStepSize ) +
				", minSizeMultiplier=" + minSizeMultiplier +
				", merge=" + enableMerge +
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

		gd.addCheckbox( "Enable_block_merging (reduces tile count)", defaultEnableMerge );

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
		final boolean enableMerge = defaultEnableMerge = gd.getNextBoolean();

		IOFunctions.println( "Created oct-tree splitter: " + criterion.description() +
				", minTileSize=" + Arrays.toString( tileSizes ) +
				", multiplier=" + minSizeMultiplier +
				", merge=" + enableMerge + ", minSplitLevels=" + minSplitLevels );

		return new SplitOctTree( minStepSize, minSizeMultiplier, criterion, enableMerge, minSplitLevels );
	}

	// Getters for testing
	public long[] getMinStepSize() { return minStepSize.clone(); }
	public int getMinSizeMultiplier() { return minSizeMultiplier; }
	public OctTreeSplitCriterion getCriterion() { return criterion; }
	public ViewId getCurrentViewId() { return currentViewId; }
	public boolean isEnableMerge() { return enableMerge; }
	public int getMinSplitLevels() { return minSplitLevels; }
}
