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

	// Instance fields
	private final long[] minStepSize;
	private final int minSizeMultiplier;
	private final OctTreeSplitCriterion criterion;

	// Current context for split() method (set per ViewSetup iteration)
	private ViewId currentViewId;
	private int currentTimepointId;

	/**
	 * Constructor with all parameters.
	 *
	 * @param minStepSize Alignment constraint and overlap size
	 * @param minSizeMultiplier Multiplier for minimum split size (e.g., 4 means min size = 4 * minStepSize)
	 * @param criterion The splitting criterion (determines when to stop splitting)
	 */
	public SplitOctTree(
			final long[] minStepSize,
			final int minSizeMultiplier,
			final OctTreeSplitCriterion criterion )
	{
		this.minStepSize = minStepSize.clone();
		this.minSizeMultiplier = minSizeMultiplier;
		this.criterion = criterion;
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
		final ArrayList< Interval > result = new ArrayList<>();
		splitRecursive( input, result );
		return result;
	}

	/**
	 * Recursive oct-tree splitting algorithm.
	 *
	 * @param interval Current interval to potentially split
	 * @param result List to collect final intervals
	 */
	private void splitRecursive( final Interval interval, final ArrayList< Interval > result )
	{
		// Check if CURRENT interval exceeds threshold (not octants!)
		if ( !criterion.shouldSplit( interval, currentViewId, currentTimepointId ) )
		{
			// Current interval is within threshold, add as leaf
			result.add( interval );
			return;
		}

		// Current interval exceeds threshold - check if we can split further
		if ( !canSplitFurther( interval ) )
		{
			// Can't split further due to size constraint, add anyway
			result.add( interval );
			return;
		}

		// Split into octants and recurse
		final List< Interval > octants = createOctantsWithOverlap( interval );
		for ( final Interval octant : octants )
		{
			splitRecursive( octant, result );
		}
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
				", minSizeMultiplier=" + minSizeMultiplier;
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

		IOFunctions.println( "Created oct-tree splitter: " + criterion.description() );

		return new SplitOctTree( minStepSize, minSizeMultiplier, criterion );
	}

	// Getters for testing
	public long[] getMinStepSize() { return minStepSize.clone(); }
	public int getMinSizeMultiplier() { return minSizeMultiplier; }
	public OctTreeSplitCriterion getCriterion() { return criterion; }
	public ViewId getCurrentViewId() { return currentViewId; }
}
