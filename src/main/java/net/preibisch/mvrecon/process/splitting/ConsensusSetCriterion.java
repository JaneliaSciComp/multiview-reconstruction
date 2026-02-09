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

	// Static defaults for GUI persistence
	public static int defaultMaxCorrespondences = 12;
	public static int[] defaultLabelChoices = null;

	private final SpimData2 spimData;
	private final Set< String > labels;
	private final int maxCorrespondences;

	/**
	 * Constructor.
	 *
	 * @param spimData The SpimData2 containing interest points
	 * @param labels Set of interest point labels to consider
	 * @param maxCorrespondences Threshold - regions with more correspondences OR multiple consensus sets should be split
	 */
	public ConsensusSetCriterion(
			final SpimData2 spimData,
			final Set< String > labels,
			final int maxCorrespondences )
	{
		this.spimData = spimData;
		this.labels = labels;
		this.maxCorrespondences = maxCorrespondences;
	}

	@Override
	public boolean shouldSplit( final List< SplitCorrespondence > correspondences )
	{
		// Count unique detections (a detection may have correspondences to multiple views)
		final Set< Integer > uniqueDetections = new HashSet<>();
		for ( final SplitCorrespondence corr : correspondences )
			uniqueDetections.add( corr.detectionId );

		// If unique detections <= threshold, don't split (few enough points)
		if ( uniqueDetections.size() <= maxCorrespondences )
			return false;

		// Too many detections - check if any view has multiple consensus sets
		// Map: corrViewKey → Set of consensusSetIds seen for that view
		final Map< String, Set< Integer > > consensusSetsPerView = new HashMap<>();
		for ( final SplitCorrespondence corr : correspondences )
		{
			consensusSetsPerView
				.computeIfAbsent( corr.corrViewKey, k -> new HashSet<>() )
				.add( corr.consensusSetId );
		}

		// Only split if any corresponding view has >1 consensus set
		for ( final Set< Integer > setIds : consensusSetsPerView.values() )
		{
			if ( setIds.size() > 1 )
				return true;
		}

		// Many detections but all from single consensus set per view - don't split
		return false;
	}

	@Override
	public String description()
	{
		return "ConsensusSetCriterion[labels=" + labels +
				", maxCorrespondences=" + maxCorrespondences + "]";
	}

	// Getters for GUI display and testing
	public int getMaxCorrespondences() { return maxCorrespondences; }
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

		gd.addNumericField( "Max_detections_per_tile", defaultMaxCorrespondences, 0 );
		gd.addMessage( "(Splits only if >N unique detections AND multiple consensus sets per view-pair)" );

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

		final int maxCorrespondences = defaultMaxCorrespondences = (int) Math.round( gd.getNextNumber() );

		return new ConsensusSetCriterion( data, selectedLabels, maxCorrespondences );
	}
}
