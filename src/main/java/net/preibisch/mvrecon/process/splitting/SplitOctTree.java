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
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

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
	// Static defaults for GUI persistence
	public static int defaultMaxCorrespondences = 500;
	public static int[] defaultLabelChoices = null;
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
		final SpimData2 spimData = ( (CrossViewCorrespondenceCriterion) criterion ).getSpimData();
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
		// First check if we can split further (size constraint)
		if ( !canSplitFurther( interval ) )
		{
			result.add( interval );
			return;
		}

		// Create octants with overlap
		final List< Interval > octants = createOctantsWithOverlap( interval );

		// Check if ANY octant exceeds the threshold
		boolean anyOctantExceedsThreshold = false;

		for ( final Interval octant : octants )
		{
			if ( criterion.shouldSplit( octant, currentViewId, currentTimepointId ) )
			{
				anyOctantExceedsThreshold = true;
				break;
			}
		}

		// If no octant exceeds threshold, add current interval as leaf
		if ( !anyOctantExceedsThreshold )
		{
			result.add( interval );
			return;
		}

		// Recursively split each octant
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
	 * Following the pattern of SplitDistributeEvenly.setupGUI()
	 *
	 * @param gd The GenericDialog to add components to
	 * @param data The SpimData2
	 * @param minStepSize The minimum step size constraint
	 * @return true if setup successful, false if no interest points available
	 */
	public static boolean setupGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		// Get available interest point labels
		final List< ViewId > allViewIds = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() )
				allViewIds.add( vd );

		final String[] labels = InterestPointTools.getAllInterestPointLabels( data, allViewIds );

		if ( labels.length == 0 )
		{
			IOFunctions.printErr( "No interest points available for oct-tree splitting. Please detect interest points first." );
			return false;
		}

		gd.addMessage( "Oct-tree adaptive splitting based on cross-view correspondences",
				GUIHelper.mediumstatusfont, Color.BLUE );
		gd.addMessage( "" );

		// Label selection (multi-select via checkboxes)
		gd.addMessage( "Select interest point labels to consider:" );
		final boolean[] defaultSelection = new boolean[ labels.length ];
		if ( defaultLabelChoices == null || defaultLabelChoices.length != labels.length )
		{
			// Default: select first label
			for ( int i = 0; i < labels.length; i++ )
			{
				defaultSelection[ i ] = ( i == 0 );
			}
		}
		else
		{
			for ( int i = 0; i < defaultLabelChoices.length && i < labels.length; i++ )
				defaultSelection[ i ] = ( defaultLabelChoices[ i ] == 1 );
		}

		for ( int i = 0; i < labels.length; i++ )
		{
			gd.addCheckbox( "Label:_" + labels[ i ], defaultSelection[ i ] );
		}

		gd.addMessage( "" );
		gd.addNumericField( "Max_correspondences_per_region", defaultMaxCorrespondences, 0 );
		gd.addMessage( "(Regions with more cross-view correspondences will be split further)",
				GUIHelper.smallStatusFont, Color.DARK_GRAY );

		gd.addMessage( "" );
		gd.addNumericField( "Min_size_multiplier", defaultMinSizeMultiplier, 0 );

		// Calculate and display actual minimum tile size
		final long[] defaultMinTileSize = new long[ minStepSize.length ];
		for ( int d = 0; d < minStepSize.length; d++ )
			defaultMinTileSize[ d ] = defaultMinSizeMultiplier * minStepSize[ d ];
		gd.addMessage( "Min tile size = multiplier × minStepSize = " + defaultMinSizeMultiplier + " × " +
				Arrays.toString( minStepSize ) + " = " + Arrays.toString( defaultMinTileSize ),
				GUIHelper.smallStatusFont, Color.DARK_GRAY );
		gd.addMessage( "(Increase multiplier to prevent small tiles, minimum value is 4)",
				GUIHelper.smallStatusFont, Color.DARK_GRAY );

		gd.addMessage( "" );
		gd.addMessage( "Note: Tiles overlap by minStepSize=" + Arrays.toString( minStepSize ),
				GUIHelper.mediumstatusfont, Color.DARK_GRAY );

		return true;
	}

	/**
	 * Query GUI components and create SplitOctTree instance.
	 * Following the pattern of SplitDistributeEvenly.queryGUI()
	 *
	 * @param gd The GenericDialog with user input
	 * @param data The SpimData2
	 * @param minStepSize The minimum step size constraint
	 * @return SplitOctTree instance or null if configuration invalid
	 */
	public static SplitOctTree queryGUI( final GenericDialog gd, final SpimData2 data, final long[] minStepSize )
	{
		// Get labels again for parsing
		final List< ViewId > allViewIds = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() )
				allViewIds.add( vd );

		final String[] labelsRaw = InterestPointTools.getAllInterestPointLabels( data, allViewIds );

		if ( labelsRaw.length == 0 )
		{
			IOFunctions.printErr( "No interest points available for oct-tree splitting." );
			return null;
		}

		// Parse label selections
		final Set< String > selectedLabels = new HashSet<>();
		defaultLabelChoices = new int[ labelsRaw.length ];

		for ( int i = 0; i < labelsRaw.length; i++ )
		{
			final boolean selected = gd.getNextBoolean();
			defaultLabelChoices[ i ] = selected ? 1 : 0;
			if ( selected )
			{
				// Extract clean label (remove warning text if present)
				selectedLabels.add( InterestPointTools.getSelectedLabel( labelsRaw, i ) );
			}
		}

		if ( selectedLabels.isEmpty() )
		{
			IOFunctions.printErr( "At least one interest point label must be selected." );
			return null;
		}

		final int maxCorrespondences = defaultMaxCorrespondences = (int) Math.round( gd.getNextNumber() );

		final int minSizeMultiplier = defaultMinSizeMultiplier = Math.max( 4, (int) Math.round( gd.getNextNumber() ) );

		// Create criterion
		final CrossViewCorrespondenceCriterion criterion = new CrossViewCorrespondenceCriterion(
				data, selectedLabels, maxCorrespondences );

		IOFunctions.println( "Created oct-tree splitter: " + criterion.description() );

		return new SplitOctTree( minStepSize, minSizeMultiplier, criterion );
	}

	// Getters for testing
	public long[] getMinStepSize() { return minStepSize.clone(); }
	public int getMinSizeMultiplier() { return minSizeMultiplier; }
	public OctTreeSplitCriterion getCriterion() { return criterion; }
	public ViewId getCurrentViewId() { return currentViewId; }
}
