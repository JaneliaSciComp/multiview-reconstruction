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

/**
 * Adaptive oct-tree based image splitting.
 *
 * Recursively subdivides regions until each region contains at most
 * a specified number of cross-view corresponding interest points
 * (or cannot be split further due to minStepSize constraints).
 *
 * All tiles overlap by minStepSize to support fake corresponding points generation.
 */
public class SplitOctTree implements SplitInterval
{
	// Available criterion types for GUI selection
	public static final String[] CRITERION_NAMES = new String[] {
		CrossViewCorrespondenceCriterion.CRITERION_NAME
		// Add more criteria here as they are implemented
	};

	// Static defaults for GUI persistence
	public static int defaultCriterionChoice = 0;
	public static int defaultMinSizeMultiplier = 4;
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
	private int currentTimepointId;

	// Statistics counters (reset per split() call)
	private int splitCount;
	private int mergeCount;
	private int leafCount;

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

	/**
	 * Set the current context before calling split().
	 * This is called by SplittingTools for each ViewSetup being processed.
	 *
	 * @param viewId The current ViewId
	 * @param timepointId The timepoint ID
	 */
	public void setCurrentContext( final ViewId viewId, final int timepointId )
	{
		this.currentViewId = viewId;
		this.currentTimepointId = timepointId;
	}

	@Override
	public int maxIntervalSpread( final List< ViewSetup > oldSetups )
	{
		int max = 1;

		// Get spimData from criterion to access timepoints
		final SpimData2 spimData = criterion.getSpimData();
		final List< TimePoint > timepoints = spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered();

		for ( final ViewSetup oldSetup : oldSetups )
		{
			// Find first present timepoint for this setup
			ViewId viewId = null;
			int timepointId = -1;

			for ( final TimePoint tp : timepoints )
			{
				final ViewId candidate = new ViewId( tp.getId(), oldSetup.getId() );
				if ( spimData.getSequenceDescription().getMissingViews() == null ||
					 spimData.getSequenceDescription().getMissingViews().getMissingViews() == null ||
					 !spimData.getSequenceDescription().getMissingViews().getMissingViews().contains( candidate ) )
				{
					viewId = candidate;
					timepointId = tp.getId();
					break;
				}
			}

			// Fallback to first timepoint if all are missing
			if ( viewId == null )
			{
				viewId = new ViewId( timepoints.get( 0 ).getId(), oldSetup.getId() );
				timepointId = timepoints.get( 0 ).getId();
			}

			setCurrentContext( viewId, timepointId );

			final Interval input = new FinalInterval( oldSetup.getSize() );
			final ArrayList< Interval > intervals = split( input );

			max = Math.max( max, intervals.size() );
		}

		return max;
	}

	@Override
	public ArrayList< Interval > split( final Interval input )
	{
		// Reset statistics
		splitCount = 0;
		mergeCount = 0;
		leafCount = 0;

		final ArrayList< Interval > result = new ArrayList<>();
		splitRecursive( input, result, 0 );

		// Log statistics
		IOFunctions.println( "Oct-tree split statistics: " + splitCount + " splits, " +
				mergeCount + " merges, " + leafCount + " leaves → " + result.size() + " final blocks" +
				( minSplitLevels > 0 ? " (minSplitLevels=" + minSplitLevels + ")" : "" ) );

		return result;
	}

	/**
	 * Recursive oct-tree splitting algorithm with optional bottom-up merge phase.
	 *
	 * @param interval Current interval to potentially split
	 * @param result List to collect final intervals
	 * @param depth Current recursion depth (0 = root)
	 */
	private void splitRecursive( final Interval interval, final ArrayList< Interval > result, final int depth )
	{
		// Determine if we should split:
		// 1. Force split if we haven't reached minSplitLevels yet
		// 2. Otherwise, check criterion
		final boolean forceSplit = depth < minSplitLevels;
		final boolean criterionSplit = criterion.shouldSplit( interval, currentViewId, currentTimepointId );

		if ( !forceSplit && !criterionSplit )
		{
			// Current interval is within threshold and we've reached min levels, add as leaf
			result.add( interval );
			leafCount++;
			return;
		}

		// Want to split - check if we can split further (size constraint)
		if ( !canSplitFurther( interval ) )
		{
			// Can't split further due to size constraint, add anyway
			result.add( interval );
			leafCount++;
			return;
		}

		// Split into octants
		splitCount++;
		final List< Interval > octants = createOctantsWithOverlap( interval );

		// Recurse on each octant to get their leaf sets
		final List< ArrayList< Interval > > octantLeaves = new ArrayList<>();
		for ( final Interval octant : octants )
		{
			final ArrayList< Interval > leaves = new ArrayList<>();
			splitRecursive( octant, leaves, depth + 1 );
			octantLeaves.add( leaves );
		}

		// Try to merge sibling octant results (bottom-up merge phase) if enabled
		// Note: only merge if we're past the forced split levels
		if ( enableMerge && depth >= minSplitLevels )
		{
			final List< Interval > merged = mergeOctantResults( octantLeaves, interval );
			result.addAll( merged );
		}
		else
		{
			// No merge - just collect all leaves
			for ( final ArrayList< Interval > leaves : octantLeaves )
				result.addAll( leaves );
		}
	}

	/**
	 * Attempt to merge octant leaf sets when combined count is below threshold.
	 * Evaluates all possible merge options and picks the one with fewest resulting blocks.
	 *
	 * @param octantLeaves List of leaf intervals for each octant (indexed 0-7 for 3D)
	 * @param parentInterval The parent interval that was split into these octants
	 * @return Merged intervals (could be parent, partial merges, or all leaves)
	 */
	private List< Interval > mergeOctantResults(
			final List< ArrayList< Interval > > octantLeaves,
			final Interval parentInterval )
	{
		// Flatten all leaves
		final List< Interval > allLeaves = new ArrayList<>();
		for ( final ArrayList< Interval > leaves : octantLeaves )
			allLeaves.addAll( leaves );

		final int inputCount = allLeaves.size();

		// 1. Try full merge (all octants → parent) - best possible outcome
		if ( criterion.canMerge( allLeaves, currentViewId, currentTimepointId ) )
		{
			mergeCount += inputCount - 1; // merged inputCount leaves into 1
			return java.util.Collections.singletonList( parentInterval );
		}

		// Track best result (fewest blocks)
		List< Interval > bestResult = allLeaves;
		int bestCount = allLeaves.size();

		// 2. Try all half-space merges and pick the best
		final List< Interval > halfMerged = tryMergeHalvesBest( octantLeaves, parentInterval );
		if ( halfMerged != null && halfMerged.size() < bestCount )
		{
			bestResult = halfMerged;
			bestCount = halfMerged.size();
		}

		// 3. Try all quadrant merges and pick the best
		final List< Interval > quadMerged = tryMergeQuadrantsBest( octantLeaves, parentInterval );
		if ( quadMerged != null && quadMerged.size() < bestCount )
		{
			bestResult = quadMerged;
			bestCount = quadMerged.size();
		}

		// 4. Try individual octant merges (adjacent pairs)
		final List< Interval > pairMerged = tryMergeIndividualOctants( octantLeaves, parentInterval );
		if ( pairMerged != null && pairMerged.size() < bestCount )
		{
			bestResult = pairMerged;
			bestCount = pairMerged.size();
		}

		// Count merges (reduction in block count)
		if ( bestCount < inputCount )
			mergeCount += inputCount - bestCount;

		return bestResult;
	}

	/**
	 * Try all half-space merge options and return the best one (fewest blocks).
	 *
	 * @param octantLeaves List of leaf intervals for each octant
	 * @param parentInterval The parent interval
	 * @return Best merge result, or null if no improvement over all leaves
	 */
	private List< Interval > tryMergeHalvesBest(
			final List< ArrayList< Interval > > octantLeaves,
			final Interval parentInterval )
	{
		final int n = parentInterval.numDimensions();
		final int numOctants = octantLeaves.size();

		List< Interval > bestResult = null;
		int bestCount = Integer.MAX_VALUE;

		// Try each dimension for half-space merging
		for ( int splitDim = 0; splitDim < n; splitDim++ )
		{
			// Collect leaves for lower half (bit splitDim = 0) and upper half (bit splitDim = 1)
			final List< Interval > lowerLeaves = new ArrayList<>();
			final List< Interval > upperLeaves = new ArrayList<>();

			for ( int i = 0; i < numOctants; i++ )
			{
				if ( ( i & ( 1 << splitDim ) ) == 0 )
					lowerLeaves.addAll( octantLeaves.get( i ) );
				else
					upperLeaves.addAll( octantLeaves.get( i ) );
			}

			// Check if halves can be merged
			final boolean canMergeLower = criterion.canMerge( lowerLeaves, currentViewId, currentTimepointId );
			final boolean canMergeUpper = criterion.canMerge( upperLeaves, currentViewId, currentTimepointId );

			// Build result for this dimension
			final List< Interval > result = new ArrayList<>();
			if ( canMergeLower )
				result.add( createHalfInterval( parentInterval, splitDim, false ) );
			else
				result.addAll( lowerLeaves );

			if ( canMergeUpper )
				result.add( createHalfInterval( parentInterval, splitDim, true ) );
			else
				result.addAll( upperLeaves );

			// Track best
			if ( ( canMergeLower || canMergeUpper ) && result.size() < bestCount )
			{
				bestResult = result;
				bestCount = result.size();
			}
		}

		return bestResult;
	}

	/**
	 * Try all quadrant merge options and return the best one (fewest blocks).
	 *
	 * @param octantLeaves List of leaf intervals for each octant
	 * @param parentInterval The parent interval
	 * @return Best merge result, or null if no improvement over all leaves
	 */
	private List< Interval > tryMergeQuadrantsBest(
			final List< ArrayList< Interval > > octantLeaves,
			final Interval parentInterval )
	{
		final int n = parentInterval.numDimensions();
		if ( n < 2 )
			return null; // Need at least 2D for quadrants

		final int numOctants = octantLeaves.size();

		List< Interval > bestResult = null;
		int bestCount = Integer.MAX_VALUE;

		// For 3D: try XY, XZ, YZ quadrant groupings
		// fixedDim is the dimension that varies within each quadrant group
		for ( int fixedDim = 0; fixedDim < n; fixedDim++ )
		{
			// 4 quadrant groups (for 3D), each containing 2 octants
			final int numQuadrants = 1 << ( n - 1 ); // 4 for 3D
			final List< List< Interval > > quadrantLeaves = new ArrayList<>();
			final boolean[] canMergeQuadrant = new boolean[ numQuadrants ];

			for ( int q = 0; q < numQuadrants; q++ )
				quadrantLeaves.add( new ArrayList<>() );

			// Assign octants to quadrants
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
				quadrantLeaves.get( quadrantIdx ).addAll( octantLeaves.get( i ) );
			}

			// Check which quadrants can be merged
			int mergeableCount = 0;
			for ( int q = 0; q < numQuadrants; q++ )
			{
				canMergeQuadrant[ q ] = criterion.canMerge( quadrantLeaves.get( q ), currentViewId, currentTimepointId );
				if ( canMergeQuadrant[ q ] )
					mergeableCount++;
			}

			// Build result for this fixed dimension
			if ( mergeableCount > 0 )
			{
				final List< Interval > result = new ArrayList<>();
				for ( int q = 0; q < numQuadrants; q++ )
				{
					if ( canMergeQuadrant[ q ] )
						result.add( createQuadrantInterval( parentInterval, fixedDim, q, n ) );
					else
						result.addAll( quadrantLeaves.get( q ) );
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
	 * Try to merge individual octants (each octant's leaves as a group).
	 * This handles the case where individual octants can be merged even if larger groups can't.
	 *
	 * @param octantLeaves List of leaf intervals for each octant
	 * @param parentInterval The parent interval
	 * @return Merged result with individual octants merged where possible
	 */
	private List< Interval > tryMergeIndividualOctants(
			final List< ArrayList< Interval > > octantLeaves,
			final Interval parentInterval )
	{
		final List< Interval > octants = createOctantsWithOverlap( parentInterval );
		final List< Interval > result = new ArrayList<>();
		boolean anyMerged = false;

		for ( int i = 0; i < octantLeaves.size(); i++ )
		{
			final ArrayList< Interval > leaves = octantLeaves.get( i );
			if ( leaves.size() > 1 && criterion.canMerge( leaves, currentViewId, currentTimepointId ) )
			{
				// Merge this octant's leaves back into the octant
				result.add( octants.get( i ) );
				anyMerged = true;
			}
			else
			{
				// Keep individual leaves
				result.addAll( leaves );
			}
		}

		return anyMerged ? result : null;
	}

	/**
	 * Create a half-interval by splitting parent along one dimension.
	 *
	 * @param parent The parent interval
	 * @param splitDim The dimension to split along
	 * @param upper If true, return upper half; if false, return lower half
	 * @return The half-interval with overlap
	 */
	private Interval createHalfInterval( final Interval parent, final int splitDim, final boolean upper )
	{
		final int n = parent.numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		// Calculate split point (same logic as createOctantsWithOverlap)
		long mid = parent.min( splitDim ) + parent.dimension( splitDim ) / 2;
		long splitPoint = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ splitDim ] );
		splitPoint = Math.max( parent.min( splitDim ) + 2 * minStepSize[ splitDim ],
				Math.min( splitPoint, parent.max( splitDim ) - 2 * minStepSize[ splitDim ] + 1 ) );

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
	 * Create a quadrant interval (slice along one dimension, spans two octants).
	 *
	 * @param parent The parent interval
	 * @param fixedDim The dimension that varies within the quadrant
	 * @param quadrantIdx The quadrant index (bits for non-fixed dimensions)
	 * @param n Number of dimensions
	 * @return The quadrant interval with overlap
	 */
	private Interval createQuadrantInterval( final Interval parent, final int fixedDim, final int quadrantIdx, final int n )
	{
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];

		// Calculate split points for all dimensions
		final long[] splitPoints = new long[ n ];
		for ( int d = 0; d < n; d++ )
		{
			long mid = parent.min( d ) + parent.dimension( d ) / 2;
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			splitPoints[ d ] = Math.max( parent.min( d ) + 2 * minStepSize[ d ],
					Math.min( splitPoints[ d ], parent.max( d ) - 2 * minStepSize[ d ] + 1 ) );
		}

		int bitPos = 0;
		for ( int d = 0; d < n; d++ )
		{
			if ( d == fixedDim )
			{
				// Fixed dimension spans full range
				min[ d ] = parent.min( d );
				max[ d ] = parent.max( d );
			}
			else
			{
				// Use quadrant index bit for this dimension
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
	 * Check if the interval can be split further while respecting minStepSize.
	 * Each half after splitting needs to be at least 2 * minStepSize to allow
	 * for overlap (minStepSize on each side).
	 *
	 * @param interval The interval to check
	 * @return true if splitting is possible
	 */
	private boolean canSplitFurther( final Interval interval )
	{
		for ( int d = 0; d < interval.numDimensions(); ++d )
		{
			// After splitting with overlap, each half needs to be at least:
			// minStepSize (content) + minStepSize (overlap) = 2 * minStepSize
			// So total dimension needs to be at least minSizeMultiplier * minStepSize to split
			if ( interval.dimension( d ) < minSizeMultiplier * minStepSize[ d ] )
				return false;
		}
		return true;
	}

	/**
	 * Creates octants (8 for 3D, 4 for 2D, 2 for 1D) from the input interval.
	 * Each octant overlaps with its neighbors by minStepSize.
	 *
	 * @param interval The interval to split
	 * @return List of overlapping octants
	 */
	private List< Interval > createOctantsWithOverlap( final Interval interval )
	{
		final int n = interval.numDimensions();
		final List< Interval > octants = new ArrayList<>();

		// Calculate split points aligned to minStepSize
		final long[] splitPoints = new long[ n ];
		for ( int d = 0; d < n; ++d )
		{
			long mid = interval.min( d ) + interval.dimension( d ) / 2;
			// Align to minStepSize
			splitPoints[ d ] = SplitDistributeEvenly.closestLongDivisableBy( mid, minStepSize[ d ] );
			// Ensure split point is within valid range (leave room for overlap)
			splitPoints[ d ] = Math.max( interval.min( d ) + 2 * minStepSize[ d ],
					Math.min( splitPoints[ d ], interval.max( d ) - 2 * minStepSize[ d ] + 1 ) );
		}

		// Generate all 2^n combinations
		final int numOctants = 1 << n; // 2^n

		for ( int i = 0; i < numOctants; ++i )
		{
			final long[] min = new long[ n ];
			final long[] max = new long[ n ];

			for ( int d = 0; d < n; ++d )
			{
				// Check bit d of i to determine which half
				if ( ( i & ( 1 << d ) ) == 0 )
				{
					// Lower half: from interval.min to splitPoint + overlap - 1
					min[ d ] = interval.min( d );
					max[ d ] = splitPoints[ d ] + minStepSize[ d ] - 1;
				}
				else
				{
					// Upper half: from splitPoint - overlap to interval.max
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
	 * First shows criterion selection dialog, then delegates to criterion-specific GUI.
	 *
	 * @param gd The GenericDialog to add components to
	 * @param data The SpimData2
	 * @param minStepSize The minimum step size constraint
	 * @return true if setup successful, false if cancelled or criterion setup fails
	 */
	public static boolean setupGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		// First dialog: select criterion type
		final GenericDialog gdCriterion = new GenericDialog( "Oct-Tree Split Criterion" );
		gdCriterion.addChoice( "Split_criterion", CRITERION_NAMES, CRITERION_NAMES[ defaultCriterionChoice ] );
		gdCriterion.showDialog();

		if ( gdCriterion.wasCanceled() )
			return false;

		defaultCriterionChoice = gdCriterion.getNextChoiceIndex();
		final String selectedCriterion = CRITERION_NAMES[ defaultCriterionChoice ];

		// Main dialog: criterion-specific options + common parameters
		gd.addMessage( "Oct-tree adaptive splitting: " + selectedCriterion,
				GUIHelper.mediumstatusfont, Color.BLACK );

		// Delegate to selected criterion's GUI
		boolean success = false;
		if ( selectedCriterion.equals( CrossViewCorrespondenceCriterion.CRITERION_NAME ) )
		{
			success = CrossViewCorrespondenceCriterion.setupGUI( gd, data );
		}
		// Add more criteria here as they are implemented

		if ( !success )
			return false;

		// Common oct-tree parameters
		gd.addSlider( "Min_size_multiplier", 4, 32, defaultMinSizeMultiplier );

		// Calculate and display actual minimum tile size
		final long[] defaultMinTileSize = new long[ minStepSize.length ];
		for ( int d = 0; d < minStepSize.length; d++ )
			defaultMinTileSize[ d ] = defaultMinSizeMultiplier * minStepSize[ d ];
		gd.addMessage(
				"Min split tile size = multiplier × minStepSize = " + defaultMinSizeMultiplier + " × " +
				Arrays.toString( minStepSize ) + " = " + Arrays.toString( defaultMinTileSize ) +
				"\n(Increase multiplier to prevent small tiles, minimum value is 4)",
				GUIHelper.smallStatusFont, Color.DARK_GRAY );

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
	 *
	 * @param gd The GenericDialog with user input
	 * @param data The SpimData2
	 * @param minStepSize The minimum step size constraint
	 * @return SplitOctTree instance or null if configuration invalid
	 */
	public static SplitOctTree queryGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		// Criterion was already selected in setupGUI and stored in defaultCriterionChoice
		final String criterionName = CRITERION_NAMES[ defaultCriterionChoice ];

		// Create criterion based on selection
		OctTreeSplitCriterion criterion = null;

		if ( criterionName.equals( CrossViewCorrespondenceCriterion.CRITERION_NAME ) )
		{
			criterion = CrossViewCorrespondenceCriterion.queryGUI( gd, data );
		}
		// Add more criteria here as they are implemented

		if ( criterion == null )
			return null;

		// Get common oct-tree parameters
		final int minSizeMultiplier = defaultMinSizeMultiplier = Math.max( 4, (int) Math.round( gd.getNextNumber() ) );
		final int minSplitLevels = defaultMinSplitLevels = Math.max( 0, (int) Math.round( gd.getNextNumber() ) );
		final boolean enableMerge = defaultEnableMerge = gd.getNextBoolean();

		IOFunctions.println( "Created oct-tree splitter: " + criterion.description() +
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
