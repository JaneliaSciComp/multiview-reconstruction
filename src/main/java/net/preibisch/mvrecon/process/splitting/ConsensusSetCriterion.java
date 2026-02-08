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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * Criterion based on multi-consensus RANSAC sets.
 *
 * Stops splitting when BOTH conditions are met:
 * 1. Each corresponding view in the interval has correspondences from at most ONE consensus set
 * 2. AND the total number of correspondences is below a threshold (e.g., 12)
 *
 * Continues splitting if:
 * - Any corresponding view has >1 consensus sets in the interval
 * - OR total correspondences >= threshold
 *
 * Note: consensusSetId = -1 (single-consensus mode) is treated as its own set.
 */
public class ConsensusSetCriterion implements OctTreeSplitCriterion
{
	// Display name for GUI selection
	public static final String CRITERION_NAME = "Multi-consensus sets";

	// Static defaults for GUI persistence
	public static int defaultMinCorrespondences = 12;
	public static int[] defaultLabelChoices = null;

	private final SpimData2 spimData;
	private final Set< String > labels;
	private final int minCorrespondences;

	// Cached set of missing view keys for O(1) lookups
	private Set< String > missingViewKeys = null;

	// Cached pre-processed correspondence data per view: viewKey -> list of (location, corrViewKey, consensusSetId)
	private final Map< String, List< CorrespondenceEntry > > cachedCorrespondences = new HashMap<>();

	/**
	 * Pre-processed correspondence entry with just the data we need for interval queries.
	 */
	private static class CorrespondenceEntry
	{
		final double[] location;
		final String corrViewKey;
		final int consensusSetId;

		CorrespondenceEntry( final double[] location, final String corrViewKey, final int consensusSetId )
		{
			this.location = location;
			this.corrViewKey = corrViewKey;
			this.consensusSetId = consensusSetId;
		}
	}

	/**
	 * Constructor.
	 *
	 * @param spimData The SpimData2 containing interest points
	 * @param labels Set of interest point labels to consider
	 * @param minCorrespondences Threshold - regions with fewer correspondences AND single consensus sets stop splitting
	 */
	public ConsensusSetCriterion(
			final SpimData2 spimData,
			final Set< String > labels,
			final int minCorrespondences )
	{
		this.spimData = spimData;
		this.labels = labels;
		this.minCorrespondences = minCorrespondences;
	}

	/**
	 * Build cached set of missing view keys for O(1) lookups.
	 */
	private void buildMissingViewCache()
	{
		missingViewKeys = new HashSet<>();
		if ( spimData.getSequenceDescription().getMissingViews() != null &&
			 spimData.getSequenceDescription().getMissingViews().getMissingViews() != null )
		{
			for ( final ViewId missing : spimData.getSequenceDescription().getMissingViews().getMissingViews() )
			{
				missingViewKeys.add( missing.getTimePointId() + "_" + missing.getViewSetupId() );
			}
		}
	}

	/**
	 * Get or build the pre-processed correspondence list for a view.
	 */
	private List< CorrespondenceEntry > getCorrespondencesForView( final ViewId viewId )
	{
		final String viewKey = viewId.getTimePointId() + "_" + viewId.getViewSetupId();

		if ( cachedCorrespondences.containsKey( viewKey ) )
			return cachedCorrespondences.get( viewKey );

		// Build the cache for this view
		final List< CorrespondenceEntry > entries = new ArrayList<>();
		final int currentSetupId = viewId.getViewSetupId();

		final ViewInterestPointLists vipl = spimData.getViewInterestPoints().getViewInterestPointLists( viewId );
		if ( vipl != null )
		{
			for ( final String label : labels )
			{
				if ( !vipl.contains( label ) )
					continue;

				final InterestPoints ips = vipl.getInterestPointList( label );
				final Map< Integer, InterestPoint > ipMap = ips.getInterestPointsCopy();
				final Collection< CorrespondingInterestPoints > corrs = ips.getCorrespondingInterestPointsCopy();

				for ( final CorrespondingInterestPoints cip : corrs )
				{
					// Skip correspondences to same view (self)
					final ViewId corrViewId = cip.getCorrespondingViewId();
					if ( corrViewId.getViewSetupId() == currentSetupId )
						continue;

					// Skip if corresponding view is missing
					if ( !isViewPresent( corrViewId ) )
						continue;

					// Get the interest point location
					final InterestPoint ip = ipMap.get( cip.getDetectionId() );
					if ( ip == null )
						continue;

					// Store pre-processed entry
					final String corrKey = corrViewId.getTimePointId() + "_" + corrViewId.getViewSetupId();
					entries.add( new CorrespondenceEntry( ip.getL(), corrKey, cip.getConsensusSetId() ) );
				}
			}
		}

		cachedCorrespondences.put( viewKey, entries );
		IOFunctions.println( "Cached " + entries.size() + " correspondences for view " + viewKey );
		return entries;
	}

	@Override
	public boolean shouldSplit( final Interval interval, final ViewId viewId, final int timepointId )
	{
		// Don't split for missing views
		if ( !isViewPresent( viewId ) )
			return false;

		// Get pre-processed correspondence data (cached)
		final List< CorrespondenceEntry > entries = getCorrespondencesForView( viewId );

		int totalCorrespondences = 0;

		// Map: corrViewKey → Set of consensusSetIds seen
		final Map< String, Set< Integer > > consensusSetsPerView = new HashMap<>();

		// Simple iteration through pre-processed data
		for ( final CorrespondenceEntry entry : entries )
		{
			// Check if this point is within the interval
			if ( !contains( entry.location, interval ) )
				continue;

			totalCorrespondences++;
			consensusSetsPerView.computeIfAbsent( entry.corrViewKey, k -> new HashSet<>() ).add( entry.consensusSetId );
		}

		// Check stop conditions (AND logic - both must be true to STOP splitting)

		// If total >= threshold, must split
		if ( totalCorrespondences >= minCorrespondences )
			return true;

		// If any corresponding view has >1 consensus set, must split
		for ( final Set< Integer > setIds : consensusSetsPerView.values() )
		{
			if ( setIds.size() > 1 )
				return true;
		}

		// All conditions met to stop splitting (low count AND single consensus set per view)
		return false;
	}

	/**
	 * Check if a view is present (not missing).
	 * Uses cached Set for O(1) lookups.
	 */
	private boolean isViewPresent( final ViewId viewId )
	{
		// Build cache on first use
		if ( missingViewKeys == null )
			buildMissingViewCache();

		final String key = viewId.getTimePointId() + "_" + viewId.getViewSetupId();
		return !missingViewKeys.contains( key );
	}

	/**
	 * Check if a point is within an interval.
	 */
	private static boolean contains( final double[] l, final Interval interval )
	{
		for ( int d = 0; d < l.length; ++d )
			if ( l[ d ] < interval.min( d ) || l[ d ] > interval.max( d ) )
				return false;
		return true;
	}

	@Override
	public String description()
	{
		return "ConsensusSetCriterion[labels=" + labels +
				", minCorrespondences=" + minCorrespondences + "]";
	}

	// Getters for GUI display and testing
	public int getMinCorrespondences() { return minCorrespondences; }
	public Set< String > getLabels() { return labels; }
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

		gd.addNumericField( "Min_correspondences_for_further_split", defaultMinCorrespondences, 0 );
		gd.addMessage( "(Splits if >=N correspondences OR multiple consensus sets per view-pair)" );

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

		return new ConsensusSetCriterion( data, selectedLabels, minCorrespondences );
	}
}
