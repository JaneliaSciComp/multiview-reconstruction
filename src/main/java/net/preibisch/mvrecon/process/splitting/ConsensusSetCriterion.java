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

import java.util.ArrayList;
import java.util.Collection;
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
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

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
	public enum ToleranceMode
	{
		NONE( "Not tolerant (any other set triggers split)" ),
		PERCENTAGE( "Percentage tolerance" ),
		COUNT( "Count tolerance" );

		private final String description;

		ToleranceMode( final String description ) { this.description = description; }

		public String getDescription() { return description; }
	}

	public static final String[] TOLERANCE_CHOICES = new String[] {
		ToleranceMode.NONE.getDescription(),
		ToleranceMode.PERCENTAGE.getDescription(),
		ToleranceMode.COUNT.getDescription()
	};

	// Static defaults for GUI persistence
	public static int defaultMinCorrespondences = 12;
	public static int[] defaultLabelChoices = null;
	public static ToleranceMode defaultToleranceMode = ToleranceMode.PERCENTAGE;
	public static double defaultToleranceValue = 10.0;  // 10% or 10 correspondences

	private final SpimData2 spimData;
	private final Set< String > labels;
	private final int minCorrespondences;
	private final ToleranceMode toleranceMode;
	private final double toleranceValue;

	/**
	 * Constructor.
	 *
	 * @param spimData The SpimData2 containing interest points
	 * @param labels Set of interest point labels to consider
	 * @param minCorrespondences Threshold - regions with more unique detections should be split
	 * @param toleranceMode How to handle multiple consensus sets (NONE, PERCENTAGE, COUNT)
	 * @param toleranceValue The tolerance value (percentage or count, depending on mode)
	 */
	public ConsensusSetCriterion(
			final SpimData2 spimData,
			final Set< String > labels,
			final int minCorrespondences,
			final ToleranceMode toleranceMode,
			final double toleranceValue )
	{
		this.spimData = spimData;
		this.labels = labels;
		this.minCorrespondences = minCorrespondences;
		this.toleranceMode = toleranceMode;
		this.toleranceValue = toleranceValue;
	}

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

		// Count consensus set distribution per view pair
		// Map: corrViewKey → Map of consensusSetId → count
		final Map< String, Map< Integer, Integer > > consensusSetCountsPerView = new HashMap<>();
		for ( final SplitCorrespondence corr : correspondences )
		{
			consensusSetCountsPerView
				.computeIfAbsent( corr.corrViewKey, k -> new HashMap<>() )
				.merge( corr.consensusSetId, 1, Integer::sum );
		}

		// Aggregate across all view pairs: sum dominant counts (biggest set each)
		// and outlier counts (all other consensus sets)
		int totalDominant = 0;
		int totalOutliers = 0;
		int maxNumSets = 0;
		int totalNumSets = 0;
		int numPairs = 0;

		for ( final Map< Integer, Integer > setCounts : consensusSetCountsPerView.values() )
		{
			int pairTotal = 0;
			int pairMax = 0;
			for ( final int count : setCounts.values() )
			{
				pairTotal += count;
				pairMax = Math.max( pairMax, count );
			}
			totalDominant += pairMax;
			totalOutliers += pairTotal - pairMax;
			maxNumSets = Math.max( maxNumSets, setCounts.size() );
			totalNumSets += setCounts.size();
			numPairs++;
		}

		final double avgNumSets = numPairs > 0 ? ( double ) totalNumSets / numPairs : 0;

		// If no outliers at all, don't split
		if ( totalOutliers == 0 )
			return false;

		// Apply tolerance check on aggregated counts
		switch ( toleranceMode )
		{
			case NONE:
				return true;
			case PERCENTAGE:
				final double outlierPercentage = ( 100.0 * totalOutliers ) / ( totalDominant + totalOutliers );
				return outlierPercentage > toleranceValue;
			case COUNT:
				return totalOutliers > toleranceValue;
			default:
				return true;
		}
	}

	/**
	 * Compute the aggregate outlier ratio across all view pairs.
	 * For each pair, the dominant consensus set (highest count) is "inlier",
	 * everything else is "outlier". Returns totalOutliers / (totalDominant + totalOutliers),
	 * or 0.0 if there are no correspondences.
	 */
	public static double computeOutlierRatio( final List< SplitCorrespondence > correspondences )
	{
		// Map: corrViewKey → Map of consensusSetId → count
		final Map< String, Map< Integer, Integer > > consensusSetCountsPerView = new HashMap<>();
		for ( final SplitCorrespondence corr : correspondences )
		{
			consensusSetCountsPerView
				.computeIfAbsent( corr.corrViewKey, k -> new HashMap<>() )
				.merge( corr.consensusSetId, 1, Integer::sum );
		}

		int totalDominant = 0;
		int totalOutliers = 0;

		for ( final Map< Integer, Integer > setCounts : consensusSetCountsPerView.values() )
		{
			int pairMax = 0;
			int pairTotal = 0;
			for ( final int count : setCounts.values() )
			{
				pairTotal += count;
				pairMax = Math.max( pairMax, count );
			}
			totalDominant += pairMax;
			totalOutliers += pairTotal - pairMax;
		}

		final int total = totalDominant + totalOutliers;
		return total > 0 ? ( double ) totalOutliers / total : 0.0;
	}

	@Override
	public double scoreSplit( final List< SplitCorrespondence > child1, final List< SplitCorrespondence > child2 )
	{
		return computeOutlierRatio( child1 ) + computeOutlierRatio( child2 );
	}

	@Override
	public String description()
	{
		final String toleranceDesc;
		switch ( toleranceMode )
		{
			case NONE:
				toleranceDesc = "no tolerance";
				break;
			case PERCENTAGE:
				toleranceDesc = "tolerance=" + toleranceValue + "%";
				break;
			case COUNT:
				toleranceDesc = "tolerance=" + (int) toleranceValue + " correspondences";
				break;
			default:
				toleranceDesc = "unknown";
		}

		return "ConsensusSetCriterion[labels=" + labels +
				", minCorrespondences=" + minCorrespondences +
				", " + toleranceDesc + "]";
	}

	/**
	 * Override to validate that correspondences have multi-consensus information.
	 * Throws RuntimeException if consensusSetId is -1 (single-consensus mode).
	 */
	@Override
	public List< SplitCorrespondence > loadCorrespondences( final ViewId viewId )
	{
		final List< SplitCorrespondence > result = OctTreeSplitCriterion.super.loadCorrespondences( viewId );

		if ( result.isEmpty() )
			return result;

		// Check first correspondence from raw data - if one is -1, all are -1
		final ViewInterestPointLists vipl = spimData.getViewInterestPoints().getViewInterestPointLists( viewId );
		if ( vipl != null )
		{
			for ( final String label : labels )
			{
				if ( !vipl.contains( label ) )
					continue;

				final InterestPoints ips = vipl.getInterestPointList( label );
				final Collection< CorrespondingInterestPoints > corrs = ips.getCorrespondingInterestPointsCopy();
				if ( !corrs.isEmpty() )
				{
					final CorrespondingInterestPoints firstCorr = corrs.iterator().next();
					if ( firstCorr.getConsensusSetId() < 0 )
					{
						throw new RuntimeException(
								"ConsensusSetCriterion requires multi-consensus correspondences, but view " +
								viewId.getTimePointId() + "_" + viewId.getViewSetupId() +
								" has single-consensus correspondences (consensusSetId=-1). " +
								"Please use 'Cross-view correspondences' criterion instead, or run multi-consensus RANSAC first." );
					}
					return result;  // Found valid multi-consensus data
				}
			}
		}

		return result;
	}

	// Getters for GUI display and testing
	public int getMinCorrespondences() { return minCorrespondences; }
	public ToleranceMode getToleranceMode() { return toleranceMode; }
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
		return setupGUI( gd, data, null );
	}

	/**
	 * Setup GUI components for consensus set criterion.
	 *
	 * @param driveByChannelIds if non-null, label availability counts are computed only over
	 *        views of these driving channels (so labels that exist in every driving view are
	 *        not flagged as "only available for X/Y Views" because of mirrored channels).
	 */
	public static boolean setupGUI( final GenericDialog gd, final SpimData2 data, final Set< Integer > driveByChannelIds )
	{
		// Get available interest point labels (restricted to the driving channels if requested)
		final List< ViewId > allViewIds = collectViewIds( data, driveByChannelIds );

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

		gd.addChoice( "Tolerance_for_other_sets", TOLERANCE_CHOICES, TOLERANCE_CHOICES[ defaultToleranceMode.ordinal() ] );

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
		return queryGUI( gd, data, null );
	}

	/**
	 * Query GUI components and create ConsensusSetCriterion instance.
	 *
	 * @param driveByChannelIds must match the value passed to {@link #setupGUI} so the label
	 *        choices are parsed against the same (channel-restricted) view set.
	 */
	public static ConsensusSetCriterion queryGUI( final GenericDialog gd, final SpimData2 data, final Set< Integer > driveByChannelIds )
	{
		// Get labels again for parsing (must use the same view set as setupGUI)
		final List< ViewId > allViewIds = collectViewIds( data, driveByChannelIds );

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
		final ToleranceMode toleranceMode = defaultToleranceMode = ToleranceMode.values()[ gd.getNextChoiceIndex() ];

		// If tolerance mode requires a value, show second dialog
		double toleranceValue = 0;
		if ( toleranceMode != ToleranceMode.NONE )
		{
			final GenericDialog gdTolerance = new GenericDialog( "Consensus Set Tolerance" );

			if ( toleranceMode == ToleranceMode.PERCENTAGE )
			{
				gdTolerance.addNumericField( "Max_percentage_from_other_sets", defaultToleranceValue, 1 );
				gdTolerance.addMessage( "(e.g., 10 means up to 10% of correspondences can be from other sets)" );
			}
			else // COUNT
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

	/**
	 * Collect all present view ids, optionally restricted to the given driving channels.
	 *
	 * @param driveByChannelIds if non-null, only views whose channel id is contained are returned
	 */
	private static List< ViewId > collectViewIds( final SpimData2 data, final Set< Integer > driveByChannelIds )
	{
		final List< ViewId > viewIds = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() && ( driveByChannelIds == null || driveByChannelIds.contains( vd.getViewSetup().getChannel().getId() ) ) )
				viewIds.add( vd );
		return viewIds;
	}
}
