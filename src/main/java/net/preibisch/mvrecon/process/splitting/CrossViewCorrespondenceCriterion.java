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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;

/**
 * Criterion that counts cross-view corresponding interest points within a region.
 *
 * An interest point is counted as a "cross-view correspondence" if it has
 * at least one correspondence to a DIFFERENT view (not within the same original view).
 *
 * If the count of unique detections exceeds the threshold, the region should be split further.
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
	 * @param maxCorrespondences Threshold - regions with more unique detections should be split
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
	public boolean shouldSplit( final List< SplitCorrespondence > correspondences )
	{
		// Count unique detection IDs (a detection may have correspondences to multiple views)
		final Set< Integer > uniqueDetections = new HashSet<>();
		for ( final SplitCorrespondence corr : correspondences )
			uniqueDetections.add( corr.detectionId );

		return uniqueDetections.size() > maxCorrespondences;
	}

	@Override
	public double scoreSplit( final List< SplitCorrespondence > child1, final List< SplitCorrespondence > child2 )
	{
		// Score by detection imbalance: 0 = perfectly balanced, 1 = all in one side
		final Set< Integer > dets1 = new HashSet<>();
		for ( final SplitCorrespondence c : child1 )
			dets1.add( c.detectionId );

		final Set< Integer > dets2 = new HashSet<>();
		for ( final SplitCorrespondence c : child2 )
			dets2.add( c.detectionId );

		final int total = dets1.size() + dets2.size();
		return total > 0 ? ( double ) Math.abs( dets1.size() - dets2.size() ) / total : 0;
	}

	@Override
	public String description()
	{
		return "CrossViewCorrespondenceCriterion[labels=" + labels +
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
	 * Setup GUI components for cross-view correspondence criterion.
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
	 * Setup GUI components for cross-view correspondence criterion.
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
		return queryGUI( gd, data, null );
	}

	/**
	 * Query GUI components and create CrossViewCorrespondenceCriterion instance.
	 *
	 * @param driveByChannelIds must match the value passed to {@link #setupGUI} so the label
	 *        choices are parsed against the same (channel-restricted) view set.
	 */
	public static CrossViewCorrespondenceCriterion queryGUI( final GenericDialog gd, final SpimData2 data, final Set< Integer > driveByChannelIds )
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

		final int maxCorrespondences = defaultMaxCorrespondences = (int) Math.round( gd.getNextNumber() );

		return new CrossViewCorrespondenceCriterion( data, selectedLabels, maxCorrespondences );
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
