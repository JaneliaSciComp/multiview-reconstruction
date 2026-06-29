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
package net.preibisch.mvrecon.process.splitting;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
public class SplitOctTree implements SplitView
{
	// Available criterion types for GUI selection
	public static final String[] CRITERION_NAMES = new String[] {
		CrossViewCorrespondenceCriterion.CRITERION_NAME,
		ConsensusSetCriterion.CRITERION_NAME
	};

	// Static defaults for GUI persistence
	public static int defaultCriterionChoice = 1;
	public static long[] defaultMinTileSize = null;  // initialized in setupGUI based on minStepSize
	public static int defaultMinSplitLevels = 1;
	public static int defaultAnisotropyChoice = 1;

	// Lower bound for the tile-size multiplier accepted by the GUI
	// (min tile size = minSizeMultiplierFloor * minStepSize[d]).
	// Algorithmic floor is 2; raising this is a usability/sanity guard.
	public static int minSizeMultiplierFloor = 2;

	public static final String[] ANISOTROPY_CHOICES = {
		"Ignore anisotropy",
		"Global anisotropy factor",
		"Per-view anisotropy"
	};

	// Instance fields
	private final long[] minStepSize;
	private final int[] minSizeMultiplier; // per-dimension tile-size multiplier; min tile = minSizeMultiplier[d] * minStepSize[d]
	private final OctTreeSplitCriterion criterion;
	private final int minSplitLevels;
	private final double[] anisotropy; // per-dimension voxel size ratio, {1,1,1} for isotropic


	/**
	 * Result of a split operation, including intervals and statistics.
	 * Immutable, safe to return from parallel operations.
	 */
	/**
	 * Accumulates statistics during recursive splitting.
	 * Passed through recursion; mutable.
	 */
	static class SplitStatsAccumulator
	{
		final double tolerancePercent;

		int splitCount = 0;
		int leafCount = 0;

		// depth
		int minDepth = Integer.MAX_VALUE;
		int maxDepth = 0;
		long totalDepth = 0;

		// unique detections per leaf
		int minDetections = Integer.MAX_VALUE;
		int maxDetections = 0;
		long totalDetections = 0;

		// max consensus sets in any view pair, per leaf
		int minMaxSets = Integer.MAX_VALUE;
		int maxMaxSets = 0;
		long totalMaxSets = 0;

		// outlier ratio per leaf (fraction of correspondences not in dominant set)
		double totalOutlierRatio = 0;

		// leaves where criterion was satisfied (didn't stop just because we ran out of space)
		int criterionSatisfiedCount = 0;

		// leaves where outlier ratio <= tolerancePercent
		int withinToleranceCount = 0;

		SplitStatsAccumulator( final double tolerancePercent )
		{
			this.tolerancePercent = tolerancePercent;
		}

		void addSplit() { splitCount++; }

		void addLeaf( final int depth, final List< SplitCorrespondence > correspondences,
				final boolean criterionSatisfied )
		{
			leafCount++;

			minDepth = Math.min( minDepth, depth );
			maxDepth = Math.max( maxDepth, depth );
			totalDepth += depth;

			// Count unique detections
			final Set< Integer > uniqueDets = new HashSet<>();
			for ( final SplitCorrespondence c : correspondences )
				uniqueDets.add( c.detectionId );

			final int numDets = uniqueDets.size();
			minDetections = Math.min( minDetections, numDets );
			maxDetections = Math.max( maxDetections, numDets );
			totalDetections += numDets;

			// Max consensus sets in any single view pair
			final Map< String, Set< Integer > > setsPerView = new HashMap<>();
			for ( final SplitCorrespondence c : correspondences )
				setsPerView.computeIfAbsent( c.corrViewKey, k -> new HashSet<>() ).add( c.consensusSetId );

			int maxSets = 0;
			for ( final Set< Integer > sets : setsPerView.values() )
				maxSets = Math.max( maxSets, sets.size() );

			minMaxSets = Math.min( minMaxSets, maxSets );
			maxMaxSets = Math.max( maxMaxSets, maxSets );
			totalMaxSets += maxSets;

			// Outlier ratio (check directly against tolerance, independent of detection count)
			final double outlierRatio = ConsensusSetCriterion.computeOutlierRatio( correspondences );
			totalOutlierRatio += outlierRatio;

			if ( criterionSatisfied )
				criterionSatisfiedCount++;

			if ( outlierRatio * 100.0 <= tolerancePercent )
				withinToleranceCount++;
		}

		double avgDepth() { return leafCount > 0 ? ( double ) totalDepth / leafCount : 0; }
		double avgDetections() { return leafCount > 0 ? ( double ) totalDetections / leafCount : 0; }
		double avgMaxSets() { return leafCount > 0 ? ( double ) totalMaxSets / leafCount : 0; }
		double avgOutlierPercent() { return leafCount > 0 ? 100.0 * totalOutlierRatio / leafCount : 0; }
		double criterionSatisfiedPercent() { return leafCount > 0 ? 100.0 * criterionSatisfiedCount / leafCount : 0; }
		double withinTolerancePercent() { return leafCount > 0 ? 100.0 * withinToleranceCount / leafCount : 0; }
	}

	/**
	 * Result of a split operation, including intervals and statistics.
	 * Immutable, safe to return from parallel operations.
	 */
	public static class OctTreeSplitResult extends SplitResult
	{
		private static final long serialVersionUID = 1L;

		public final int splitCount;
		public final int leafCount;
		public final int minDepth, maxDepth;
		public final double avgDepth;
		public final int minDetections, maxDetections;
		public final double avgDetections;
		public final int minMaxSets, maxMaxSets;
		public final double avgMaxSets;
		public final double avgOutlierPercent;
		public final double criterionSatisfiedPercent;
		public final double withinTolerancePercent;

		public OctTreeSplitResult( final ArrayList< Interval > intervals, final SplitStatsAccumulator acc )
		{
			super( intervals );
			this.splitCount = acc.splitCount;
			this.leafCount = acc.leafCount;
			this.minDepth = acc.minDepth;
			this.maxDepth = acc.maxDepth;
			this.avgDepth = acc.avgDepth();
			this.minDetections = acc.minDetections;
			this.maxDetections = acc.maxDetections;
			this.avgDetections = acc.avgDetections();
			this.minMaxSets = acc.minMaxSets;
			this.maxMaxSets = acc.maxMaxSets;
			this.avgMaxSets = acc.avgMaxSets();
			this.avgOutlierPercent = acc.avgOutlierPercent();
			this.criterionSatisfiedPercent = acc.criterionSatisfiedPercent();
			this.withinTolerancePercent = acc.withinTolerancePercent();
		}
	}


	/**
	 * Constructor with all parameters.
	 *
	 * @param minStepSize Alignment constraint and overlap size
	 * @param minSizeMultiplier Per-dimension multiplier for minimum split size, length must equal minStepSize.length
	 *        (e.g., {4, 4, 2} means min tile size = {4*minStepSize[0], 4*minStepSize[1], 2*minStepSize[2]})
	 * @param criterion The splitting criterion (determines when to stop splitting)
	 * @param minSplitLevels Minimum number of split levels to always perform (0 = fully adaptive).
	 *        Use minSplitLevels=1 for TPS-compatible splitting (guaranteed 8 non-co-planar tiles).
	 * @param anisotropy Per-dimension voxel size ratio (e.g., {1, 1, 2} for 2x anisotropy in Z), or null for isotropic
	 */
	public SplitOctTree(
			final long[] minStepSize,
			final int[] minSizeMultiplier,
			final OctTreeSplitCriterion criterion,
			final int minSplitLevels,
			final double[] anisotropy )
	{
		if ( minSizeMultiplier.length != minStepSize.length )
			throw new IllegalArgumentException( "minSizeMultiplier length (" + minSizeMultiplier.length +
					") must equal minStepSize length (" + minStepSize.length + ")" );

		this.minStepSize = minStepSize.clone();
		this.minSizeMultiplier = minSizeMultiplier.clone();
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
	 * @return OctTreeSplitResult containing intervals and statistics, or null on error
	 */
	private static OctTreeSplitResult splitStatic(
			final Interval input,
			final ViewId viewId,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int[] minSizeMultiplier,
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

		// Determine tolerance for outlier ratio stats
		final double tolerancePercent;
		if ( criterion instanceof ConsensusSetCriterion )
			tolerancePercent = ( ( ConsensusSetCriterion ) criterion ).getToleranceValue();
		else
			tolerancePercent = Double.MAX_VALUE;  // no tolerance concept for other criteria

		// Statistics accumulator (passed through recursion)
		final SplitStatsAccumulator acc = new SplitStatsAccumulator( tolerancePercent );

		// Recursive splitting
		final ArrayList< Interval > intervals = new ArrayList<>();
		splitRecursiveStatic( input, correspondences, intervals, 0,
				criterion, minStepSize, minSizeMultiplier, minSplitLevels, anisotropy, acc );

		final long endTime = System.currentTimeMillis();
		IOFunctions.println( Thread.currentThread().getName() + ": Finished view " +
				viewId.getTimePointId() + "_" + viewId.getViewSetupId() + " at " + endTime +
				" (took " + ( endTime - startTime ) + " ms)" );

		return new OctTreeSplitResult( intervals, acc );
	}

	/**
	 * Static recursive splitting algorithm.
	 */
	private static void splitRecursiveStatic(
			final Interval interval,
			final List< SplitCorrespondence > correspondences,
			final List< Interval > result,
			final int depth,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int[] minSizeMultiplier,
			final int minSplitLevels,
			final double[] anisotropy,
			final SplitStatsAccumulator acc )
	{
		final boolean forceSplit = depth < minSplitLevels;
		final boolean criterionSplit = criterion.shouldSplit( correspondences );

		if ( !forceSplit && !criterionSplit )
		{
			result.add( interval );
			acc.addLeaf( depth, correspondences, true );
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
			result.add( interval );
			acc.addLeaf( depth, correspondences, !criterionSplit );
			return;
		}

		acc.addSplit();

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
						criterion, minStepSize, minSizeMultiplier, minSplitLevels, anisotropy, acc );
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
						criterion, minStepSize, minSizeMultiplier, minSplitLevels, anisotropy, acc );
			}
		}
	}

	/**
	 * Static version of computeMaxSplitLevels.
	 */
	private static int computeMaxSplitLevelsStatic(
			final Interval interval,
			final long[] minStepSize,
			final int[] minSizeMultiplier )
	{
		// With anisotropy-aware splitting, we only need at least one dimension to be splittable,
		// so we take the maximum across dimensions
		int maxLevels = 0;

		for ( int d = 0; d < interval.numDimensions(); d++ )
		{
			final long dim = interval.dimension( d );
			final long minParentSize = 2 * ( minSizeMultiplier[ d ] - 1 ) * minStepSize[ d ];

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
			final int[] minSizeMultiplier,
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
			final long margin = ( minSizeMultiplier[ d ] - 1 ) * minStepSize[ d ];
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
			final int[] minSizeMultiplier,
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
				final long minParentSize = 2 * ( minSizeMultiplier[ d ] - 1 ) * minStepSize[ d ];
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
	 * Stage 2 (tiebreaker): criterion.scoreSplit() on the two children. Lower is better.
	 *
	 * @return The dimension index of the best split dimension
	 */
	private static int chooseBestSplitDimension(
			final Interval interval,
			final List< SplitCorrespondence > correspondences,
			final boolean[] splitDim,
			final OctTreeSplitCriterion criterion,
			final long[] minStepSize,
			final int[] minSizeMultiplier )
	{
		final int n = interval.numDimensions();
		int bestDim = -1;
		int bestCleanCount = -1;
		double bestScore = Double.MAX_VALUE;

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

			// Stage 2: criterion-specific score (tiebreaker, lower is better)
			final double score = criterion.scoreSplit( childCorrs.get( 0 ), childCorrs.get( 1 ) );

			// Pick best: highest cleanCount, then lowest score
			if ( cleanCount > bestCleanCount ||
				( cleanCount == bestCleanCount && score < bestScore ) )
			{
				bestDim = d;
				bestCleanCount = cleanCount;
				bestScore = score;
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

	// ==================== Instance Methods (SplitView interface) ====================

	@Override
	public SplitResult split( final ViewId viewId )
	{
		final ViewSetup setup = criterion.getSpimData().getSequenceDescription().getViewSetups().get( viewId.getViewSetupId() );
		final Interval input = new FinalInterval( setup.getSize() );

		final OctTreeSplitResult result = splitStatic( input, viewId, criterion,
				minStepSize, minSizeMultiplier, minSplitLevels, anisotropy );

		if ( result == null )
		{
			IOFunctions.println( "  Min child tile size: " + Arrays.toString( getMinTileSize() ) );
			IOFunctions.println( "  Min parent size needed for split: " + Arrays.toString( getMinParentSizeForSplit() ) );
			IOFunctions.println( "  Please reduce minSplitLevels or decrease min tile size." );
		}

		return result;
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
	public String aggregateStatistics( final List< ? extends SplitResult > results )
	{
		int totalSplitCount = 0, totalLeafCount = 0, totalFinalBlocks = 0;
		int globalMinDepth = Integer.MAX_VALUE, globalMaxDepth = 0;
		int globalMinDetections = Integer.MAX_VALUE, globalMaxDetections = 0;
		int globalMinMaxSets = Integer.MAX_VALUE, globalMaxMaxSets = 0;
		double totalDepthSum = 0, totalDetectionsSum = 0, totalMaxSetsSum = 0;
		double totalOutlierSum = 0;
		long totalCriterionOk = 0, totalWithinTolerance = 0;

		for ( final SplitResult r : results )
		{
			final OctTreeSplitResult oct = (OctTreeSplitResult) r;
			totalSplitCount += oct.splitCount;
			totalLeafCount += oct.leafCount;
			totalFinalBlocks += oct.numIntervals;

			if ( oct.leafCount > 0 )
			{
				globalMinDepth = Math.min( globalMinDepth, oct.minDepth );
				globalMaxDepth = Math.max( globalMaxDepth, oct.maxDepth );
				totalDepthSum += oct.avgDepth * oct.leafCount;
				globalMinDetections = Math.min( globalMinDetections, oct.minDetections );
				globalMaxDetections = Math.max( globalMaxDetections, oct.maxDetections );
				totalDetectionsSum += oct.avgDetections * oct.leafCount;
				globalMinMaxSets = Math.min( globalMinMaxSets, oct.minMaxSets );
				globalMaxMaxSets = Math.max( globalMaxMaxSets, oct.maxMaxSets );
				totalMaxSetsSum += oct.avgMaxSets * oct.leafCount;
				totalOutlierSum += oct.avgOutlierPercent * oct.leafCount;
				totalCriterionOk += Math.round( oct.criterionSatisfiedPercent * oct.leafCount / 100.0 );
				totalWithinTolerance += Math.round( oct.withinTolerancePercent * oct.leafCount / 100.0 );
			}
		}

		final StringBuilder sb = new StringBuilder();
		sb.append( "===== Oct-tree splitting summary =====\n" );
		sb.append( "Total views processed: " ).append( results.size() ).append( "\n" );
		sb.append( "Total splits: " ).append( totalSplitCount ).append( "\n" );
		sb.append( "Total leaves: " ).append( totalLeafCount ).append( "\n" );
		sb.append( "Total final blocks: " ).append( totalFinalBlocks ).append( "\n" );
		if ( totalLeafCount > 0 )
		{
			sb.append( "Depth: " ).append( globalMinDepth ).append( "/" ).append( String.format( "%.1f", totalDepthSum / totalLeafCount ) ).append( "/" ).append( globalMaxDepth ).append( "\n" );
			sb.append( "Detections/leaf: " ).append( globalMinDetections ).append( "/" ).append( String.format( "%.0f", totalDetectionsSum / totalLeafCount ) ).append( "/" ).append( globalMaxDetections ).append( "\n" );
			sb.append( "MaxSets/leaf: " ).append( globalMinMaxSets ).append( "/" ).append( String.format( "%.1f", totalMaxSetsSum / totalLeafCount ) ).append( "/" ).append( globalMaxMaxSets ).append( "\n" );
			sb.append( "Avg outlier: " ).append( String.format( "%.1f", totalOutlierSum / totalLeafCount ) ).append( "%\n" );
			sb.append( "Criterion OK: " ).append( String.format( "%.0f", 100.0 * totalCriterionOk / totalLeafCount ) ).append( "%\n" );
			sb.append( "Within tolerance: " ).append( String.format( "%.0f", 100.0 * totalWithinTolerance / totalLeafCount ) ).append( "%" );
		}
		sb.append( "\n======================================" );
		return sb.toString();
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
			final long minParentSize = 2 * ( minSizeMultiplier[ d ] - 1 ) * minStepSize[ d ];

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
			minTileSize[ d ] = minSizeMultiplier[ d ] * minStepSize[ d ];
		return minTileSize;
	}

	/**
	 * Get the minimum parent size needed to allow a split.
	 */
	private long[] getMinParentSizeForSplit()
	{
		final long[] minParentSize = new long[ minStepSize.length ];
		for ( int d = 0; d < minStepSize.length; d++ )
			minParentSize[ d ] = 2 * ( minSizeMultiplier[ d ] - 1 ) * minStepSize[ d ];
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
				", minSizeMultiplier=" + Arrays.toString( minSizeMultiplier ) +
				", minSplitLevels=" + minSplitLevels;
	}

	// ==================== Static GUI Methods ====================

	/**
	 * Setup GUI components for oct-tree splitting parameters.
	 */
	public static boolean setupGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		return setupGUI( gd, data, minStepSize, null );
	}

	/**
	 * Setup GUI components for oct-tree splitting parameters.
	 *
	 * @param driveByChannelIds if non-null, interest-point label statistics (e.g. the
	 *        "available for X/Y Views" counts) are restricted to views of these channels,
	 *        which are the only ones whose own correspondences drive the split.
	 */
	public static boolean setupGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize, final Set< Integer > driveByChannelIds )
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
			success = CrossViewCorrespondenceCriterion.setupGUI( gd, data, driveByChannelIds );
		else if ( selectedCriterion.equals( ConsensusSetCriterion.CRITERION_NAME ) )
			success = ConsensusSetCriterion.setupGUI( gd, data, driveByChannelIds );

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
		gd.addMessage( "Minimum tile size per dimension (must be >= " + minSizeMultiplierFloor + " × minStepSize and divisible by minStepSize):",
				GUIHelper.smallStatusFont, Color.DARK_GRAY );

		for ( int d = 0; d < minStepSize.length; d++ )
		{
			final long step = minStepSize[ d ];
			final long minVal = minSizeMultiplierFloor * step;
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
		return queryGUI( gd, data, minStepSize, null );
	}

	/**
	 * Query GUI components and create SplitOctTree instance.
	 *
	 * @param driveByChannelIds must match the value passed to {@link #setupGUI} so the label
	 *        choices are parsed against the same (channel-restricted) view set.
	 */
	public static SplitOctTree queryGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize, final Set< Integer > driveByChannelIds )
	{
		final String criterionName = CRITERION_NAMES[ defaultCriterionChoice ];

		OctTreeSplitCriterion criterion = null;

		if ( criterionName.equals( CrossViewCorrespondenceCriterion.CRITERION_NAME ) )
			criterion = CrossViewCorrespondenceCriterion.queryGUI( gd, data, driveByChannelIds );
		else if ( criterionName.equals( ConsensusSetCriterion.CRITERION_NAME ) )
			criterion = ConsensusSetCriterion.queryGUI( gd, data, driveByChannelIds );

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

			// Validate: must be >= minSizeMultiplierFloor * minStepSize
			final long minAllowed = minSizeMultiplierFloor * minStepSize[ d ];
			if ( tileSizes[ d ] < minAllowed )
			{
				IOFunctions.printErr( "ERROR: Min tile size " + dimNames[ d ] + " (" + tileSizes[ d ] +
						") must be >= " + minAllowed + " (" + minSizeMultiplierFloor + " × minStepSize)" );
				return null;
			}

			// Store as default for next time
			defaultMinTileSize[ d ] = tileSizes[ d ];
		}

		// Per-dimension multiplier; each axis is honored independently downstream
		final int[] minSizeMultiplier = new int[ minStepSize.length ];
		for ( int d = 0; d < minStepSize.length; d++ )
			minSizeMultiplier[ d ] = ( int ) ( tileSizes[ d ] / minStepSize[ d ] );

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
				", multiplier=" + Arrays.toString( minSizeMultiplier ) +
				", minSplitLevels=" + minSplitLevels +
				", anisotropy=" + Arrays.toString( anisotropy ) );

		return new SplitOctTree( minStepSize, minSizeMultiplier, criterion, minSplitLevels, anisotropy );
	}

	// Getters for testing
	public long[] getMinStepSize() { return minStepSize.clone(); }
	public int[] getMinSizeMultiplier() { return minSizeMultiplier.clone(); }
	public OctTreeSplitCriterion getCriterion() { return criterion; }
	public int getMinSplitLevels() { return minSplitLevels; }
	public double[] getAnisotropy() { return anisotropy.clone(); }
}
