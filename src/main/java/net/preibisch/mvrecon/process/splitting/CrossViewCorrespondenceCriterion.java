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
 * Criterion that counts cross-view corresponding interest points within a region.
 *
 * An interest point is counted as a "cross-view correspondence" if:
 * 1. It is within the spatial bounding box being evaluated
 * 2. It has at least one correspondence to a DIFFERENT view (not within the same original view)
 *
 * If the count exceeds the threshold, the region should be split further.
 */
public class CrossViewCorrespondenceCriterion implements OctTreeSplitCriterion
{
	// Display name for GUI selection
	public static final String CRITERION_NAME = "Cross-view correspondences";

	// Static defaults for GUI persistence
	public static int defaultMaxCorrespondences = 20;
	public static int[] defaultLabelChoices = null;

	private final SpimData2 spimData;
	private final Set< String > labels;
	private final int maxCorrespondences;

	/**
	 * Constructor.
	 *
	 * @param spimData The SpimData2 containing interest points
	 * @param labels Set of interest point labels to consider
	 * @param maxCorrespondences Threshold - regions with more correspondences should be split
	 */
	public CrossViewCorrespondenceCriterion(
			final SpimData2 spimData,
			final Set< String > labels,
			final int maxCorrespondences )
	{
		this.spimData = spimData;
		this.labels = labels;
		this.maxCorrespondences = maxCorrespondences;
	}

	@Override
	public boolean shouldSplit( final Interval interval, final ViewId viewId, final int timepointId )
	{
		// Don't split for missing views
		if ( !isViewPresent( viewId ) )
			return false;

		final int crossViewCorrespondenceCount = countCrossViewCorrespondences( interval, viewId, timepointId );
		return crossViewCorrespondenceCount > maxCorrespondences;
	}

	/**
	 * Check if a view is present (not missing).
	 */
	private boolean isViewPresent( final ViewId viewId )
	{
		if ( spimData.getSequenceDescription().getMissingViews() == null ||
			 spimData.getSequenceDescription().getMissingViews().getMissingViews() == null )
			return true;

		for ( final ViewId missing : spimData.getSequenceDescription().getMissingViews().getMissingViews() )
		{
			if ( missing.getTimePointId() == viewId.getTimePointId() &&
				 missing.getViewSetupId() == viewId.getViewSetupId() )
				return false;
		}
		return true;
	}

	/**
	 * Counts interest points that have correspondences to OTHER views within the interval.
	 *
	 * @param interval The spatial region to count within (local coordinates)
	 * @param viewId The ViewId being evaluated
	 * @param timepointId The timepoint
	 * @return Number of interest points with cross-view correspondences in this region
	 */
	public int countCrossViewCorrespondences( final Interval interval, final ViewId viewId, final int timepointId )
	{
		final ViewInterestPointLists vipl = spimData.getViewInterestPoints().getViewInterestPointLists( viewId );
		if ( vipl == null )
			return 0;

		int count = 0;
		final int currentSetupId = viewId.getViewSetupId();

		for ( final String label : labels )
		{
			if ( !vipl.contains( label ) )
				continue;

			final InterestPoints ips = vipl.getInterestPointList( label );
			final Map< Integer, InterestPoint > ipMap = ips.getInterestPointsCopy();
			final Collection< CorrespondingInterestPoints > corrs = ips.getCorrespondingInterestPointsCopy();

			// Build set of detection IDs that have cross-view correspondences
			final Set< Integer > crossViewDetectionIds = new HashSet<>();

			for ( final CorrespondingInterestPoints cip : corrs )
			{
				// Only count if correspondence is to a DIFFERENT view (different ViewSetup)
				// and that corresponding view is not missing
				final ViewId correspondingViewId = cip.getCorrespondingViewId();
				if ( correspondingViewId.getViewSetupId() != currentSetupId && isViewPresent( correspondingViewId ) )
				{
					crossViewDetectionIds.add( cip.getDetectionId() );
				}
			}

			// Count interest points with cross-view correspondences that are within the interval
			for ( final Integer detId : crossViewDetectionIds )
			{
				final InterestPoint ip = ipMap.get( detId );
				if ( ip != null && contains( ip.getL(), interval ) )
				{
					count++;
				}
			}
		}

		return count;
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
		return "CrossViewCorrespondenceCriterion[labels=" + labels +
				", maxCorrespondences=" + maxCorrespondences + "]";
	}

	// Getters for GUI display and testing
	public int getMaxCorrespondences() { return maxCorrespondences; }
	public Set< String > getLabels() { return labels; }
	public SpimData2 getSpimData() { return spimData; }

	// ==================== Static GUI Methods ====================

	/**
	 * Setup GUI components for cross-view correspondence criterion.
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

		gd.addNumericField( "Correspondences_required_for_further_split", defaultMaxCorrespondences, 0 );

		return true;
	}

	/**
	 * Query GUI components and create CrossViewCorrespondenceCriterion instance.
	 *
	 * @param gd The GenericDialog with user input
	 * @param data The SpimData2
	 * @return CrossViewCorrespondenceCriterion instance or null if configuration invalid
	 */
	public static CrossViewCorrespondenceCriterion queryGUI( final GenericDialog gd, final SpimData2 data )
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

		return new CrossViewCorrespondenceCriterion( data, selectedLabels, maxCorrespondences );
	}
}
