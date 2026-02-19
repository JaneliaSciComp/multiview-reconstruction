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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.splitting.OctTreeSplitCriterion.SplitCorrespondence;

/**
 * Criterion based on multi-consensus RANSAC sets.
 *
 * Stops splitting when EITHER:
 * 1. The number of unique detections is at or below a threshold (e.g., 12), OR
 * 2. All corresponding views have correspondences from only ONE consensus set
 *    (even if many detections, they belong to a single coherent transformation)
 *
 * Continues splitting only if:
 * - Unique detections > threshold AND any view has >1 consensus set
 *
 * Note: consensusSetId = -1 (single-consensus mode) is treated as its own set.
 */
public class ConsensusSetCriterion implements OctTreeSplitCriterion
{
	// Display name for GUI selection
	public static final String CRITERION_NAME = "Multi-consensus sets";

	// Tolerance modes for multi-set detection
	public static final int TOLERANCE_NONE = 0;
	public static final int TOLERANCE_PERCENTAGE = 1;
	public static final int TOLERANCE_COUNT = 2;
	public static final String[] TOLERANCE_CHOICES = new String[] {
		"Not tolerant (any other set triggers split)",
		"Percentage tolerance",
		"Count tolerance"
	};

	// Static defaults for GUI persistence
	public static int defaultMinCorrespondences = 12;
	public static int[] defaultLabelChoices = null;
	public static int defaultToleranceMode = TOLERANCE_NONE;
	public static double defaultToleranceValue = 10.0;  // 10% or 10 correspondences

	private final SpimData2 spimData;
	private final Set< String > labels;
	private final int minCorrespondences;
	private final int toleranceMode;
	private final double toleranceValue;

	/**
	 * Constructor.
	 *
	 * @param spimData The SpimData2 containing interest points
	 * @param labels Set of interest point labels to consider
	 * @param minCorrespondences Threshold - regions with more unique detections should be split
	 * @param toleranceMode How to handle multiple consensus sets (TOLERANCE_NONE, TOLERANCE_PERCENTAGE, TOLERANCE_COUNT)
	 * @param toleranceValue The tolerance value (percentage or count, depending on mode)
	 */
	public ConsensusSetCriterion(
			final SpimData2 spimData,
			final Set< String > labels,
			final int minCorrespondences,
			final int toleranceMode,
			final double toleranceValue )
	{
		this.spimData = spimData;
		this.labels = labels;
		this.minCorrespondences = minCorrespondences;
		this.toleranceMode = toleranceMode;
		this.toleranceValue = toleranceValue;
	}

	/*
	default boolean canMerge( List< SplitCorrespondence > correspondences )
	{
		return !shouldSplit( correspondences );
	}
	*/

	@Override
	public boolean shouldSplit( final List< SplitCorrespondence > correspondences )
	{
		// Count unique detections (a detection may have correspondences to multiple views)
		final Set< Integer > uniqueDetections = new HashSet<>();
		for ( final SplitCorrespondence corr : correspondences )
			uniqueDetections.add( corr.detectionId );

		// If unique detections <= threshold, don't split (few enough points)
		if ( uniqueDetections.size() <= minCorrespondences )
			return false;

		// Too many detections - check if any view has multiple consensus sets
		// Map: corrViewKey → Map of consensusSetId → count
		final Map< String, Map< Integer, Integer > > consensusSetCountsPerView = new HashMap<>();
		for ( final SplitCorrespondence corr : correspondences )
		{
			consensusSetCountsPerView
				.computeIfAbsent( corr.corrViewKey, k -> new HashMap<>() )
				.merge( corr.consensusSetId, 1, Integer::sum );
		}

		// Check each view for multiple consensus sets, applying tolerance
		for ( final Map< Integer, Integer > setCounts : consensusSetCountsPerView.values() )
		{
			if ( setCounts.size() <= 1 )
				continue;  // Only one set for this view - no problem

			// Multiple sets - check if within tolerance
			if ( toleranceMode == TOLERANCE_NONE )
			{
				// Any other set triggers split
				return true;
			}

			// Find the dominant set and count outliers
			int totalCount = 0;
			int maxCount = 0;
			for ( final int count : setCounts.values() )
			{
				totalCount += count;
				maxCount = Math.max( maxCount, count );
			}
			final int outlierCount = totalCount - maxCount;

			if ( toleranceMode == TOLERANCE_PERCENTAGE )
			{
				final double outlierPercentage = ( 100.0 * outlierCount ) / totalCount;
				if ( outlierPercentage > toleranceValue )
					return true;
			}
			else if ( toleranceMode == TOLERANCE_COUNT )
			{
				if ( outlierCount > toleranceValue )
					return true;
			}
		}

		// Within tolerance for all views - don't split
		return false;
	}

	@Override
	public String description()
	{
		String toleranceDesc;
		if ( toleranceMode == TOLERANCE_NONE )
			toleranceDesc = "no tolerance";
		else if ( toleranceMode == TOLERANCE_PERCENTAGE )
			toleranceDesc = "tolerance=" + toleranceValue + "%";
		else
			toleranceDesc = "tolerance=" + (int) toleranceValue + " correspondences";

		return "ConsensusSetCriterion[labels=" + labels +
				", minCorrespondences=" + minCorrespondences +
				", " + toleranceDesc + "]";
	}

	// Getters for GUI display and testing
	public int getMinCorrespondences() { return minCorrespondences; }
	public int getToleranceMode() { return toleranceMode; }
	public double getToleranceValue() { return toleranceValue; }
	@Override
	public Set< String > getLabels() { return labels; }
	@Override
	public SpimData2 getSpimData() { return spimData; }

	// ==================== Static GUI Methods ====================

	/**
	 * Setup GUI components for consensus set criterion.
	 *
	 * @param gd The GenericDialog to add components to
	 * @param data The SpimData2
	 * @return true if setup successful, false if no interest points available
	 */
	public static boolean setupGUI( final GenericDialog gd, final SpimData2 data )
	{
		// Get available interest point labels
		final List< ViewId > allViewIds = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() )
				allViewIds.add( vd );

		final String[] labels = InterestPointTools.getAllInterestPointLabels( data, allViewIds );

		if ( labels.length == 0 )
		{
			IOFunctions.printErr( "No interest points available. Please detect interest points first." );
			return false;
		}

		// Label selection (multi-select via checkboxes)
		gd.addMessage( "Select interest point labels to consider:" );
		final boolean[] defaultSelection = new boolean[ labels.length ];

		if ( defaultLabelChoices == null || defaultLabelChoices.length != labels.length )
			for ( int i = 0; i < labels.length; i++ )
				defaultSelection[ i ] = ( i == 0 );
		else
			for ( int i = 0; i < defaultLabelChoices.length && i < labels.length; i++ )
				defaultSelection[ i ] = ( defaultLabelChoices[ i ] == 1 );

		for ( int i = 0; i < labels.length; i++ )
			gd.addCheckbox( labels[ i ], defaultSelection[ i ] );

		gd.addNumericField( "Min_detections/correspondences per tile", defaultMinCorrespondences, 0 );
		gd.addMessage( "(Splits only if >N unique detections AND multiple consensus sets per view-pair)" );

		gd.addChoice( "Tolerance_for_other_sets", TOLERANCE_CHOICES, TOLERANCE_CHOICES[ defaultToleranceMode ] );

		return true;
	}

	/**
	 * Query GUI components and create ConsensusSetCriterion instance.
	 *
	 * @param gd The GenericDialog with user input
	 * @param data The SpimData2
	 * @return ConsensusSetCriterion instance or null if configuration invalid
	 */
	public static ConsensusSetCriterion queryGUI( final GenericDialog gd, final SpimData2 data )
	{
		// Get labels again for parsing
		final List< ViewId > allViewIds = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() )
				allViewIds.add( vd );

		final String[] labelsRaw = InterestPointTools.getAllInterestPointLabels( data, allViewIds );

		if ( labelsRaw.length == 0 )
		{
			IOFunctions.printErr( "No interest points available." );
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

		final int minCorrespondences = defaultMinCorrespondences = (int) Math.round( gd.getNextNumber() );
		final int toleranceMode = defaultToleranceMode = gd.getNextChoiceIndex();

		// If tolerance mode requires a value, show second dialog
		double toleranceValue = 0;
		if ( toleranceMode != TOLERANCE_NONE )
		{
			final GenericDialog gdTolerance = new GenericDialog( "Consensus Set Tolerance" );

			if ( toleranceMode == TOLERANCE_PERCENTAGE )
			{
				gdTolerance.addNumericField( "Max_percentage_from_other_sets", defaultToleranceValue, 1 );
				gdTolerance.addMessage( "(e.g., 10 means up to 10% of correspondences can be from other sets)" );
			}
			else // TOLERANCE_COUNT
			{
				gdTolerance.addNumericField( "Max_count_from_other_sets", defaultToleranceValue, 0 );
				gdTolerance.addMessage( "(e.g., 5 means up to 5 correspondences can be from other sets)" );
			}

			gdTolerance.showDialog();
			if ( gdTolerance.wasCanceled() )
				return null;

			toleranceValue = defaultToleranceValue = gdTolerance.getNextNumber();
		}

		return new ConsensusSetCriterion( data, selectedLabels, minCorrespondences, toleranceMode, toleranceValue );
	}
}
