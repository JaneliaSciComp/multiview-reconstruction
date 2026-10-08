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
package net.preibisch.mvrecon.fiji.plugin.interestpointdetection;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.preibisch.mvrecon.fiji.plugin.util.DescribesParameters;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;

import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;

public abstract class InterestPointDetectionGUI implements DescribesParameters
{
	/*
	 * which viewIds to process, set in queryParameters
	 */
	final List< ViewId > viewIdsToProcess;

	final SpimData2 spimData;

	/**
	 * @param spimData - the SpimData
	 * @param viewIdsToProcess - which view id's to segment
	 */
	public InterestPointDetectionGUI(
			final SpimData2 spimData,
			final List< ViewId > viewIdsToProcess )
	{
		this.spimData = spimData;
		this.viewIdsToProcess = viewIdsToProcess;
	}

	public List< ViewId > getViewIdsToProcess() { return viewIdsToProcess; }

	/**
	 * if any preprocessing is necessary
	 */
	public abstract void preprocess();

	/*
	 * Perform the interestpoint detection for one timepoint
	 * 
	 * @return
	 */
	public abstract HashMap< ViewId, List< InterestPoint > > findInterestPoints( final TimePoint tp );

	/**
	 * The labels one run produces, as suffixes of the label of the detection dialog: a single empty suffix (the label
	 * itself) by default; a detection that produces several labels at once (e.g. the scale space with one label per
	 * threshold) overrides this together with findInterestPointsPerSuffix, getParameters( suffix ) and describeParameters( suffix )
	 */
	public List< String > getLabelSuffixes() { return Collections.singletonList( "" ); }

	/**
	 * The interest points of one timepoint for every label suffix (see getLabelSuffixes), by default the ones of
	 * findInterestPoints under the empty suffix
	 */
	public LinkedHashMap< String, HashMap< ViewId, List< InterestPoint > > > findInterestPointsPerSuffix( final TimePoint tp )
	{
		final LinkedHashMap< String, HashMap< ViewId, List< InterestPoint > > > result = new LinkedHashMap<>();
		result.put( "", findInterestPoints( tp ) );
		return result;
	}

	/**
	 * @return the parameter string stored with the label of this suffix (see getLabelSuffixes), by default getParameters()
	 */
	public String getParameters( final String suffix ) { return getParameters(); }

	/**
	 * @return the parameters recorded in the action history for the label of this suffix (see getLabelSuffixes), by default describeParameters()
	 */
	public Map< String, String > describeParameters( final String suffix ) { return describeParameters(); }
	
	/**
	 * Query the necessary parameters for the interestpoint detection
	 * 
	 * @param defineAnisotropy - whether to use/query for anisotropy in resolution of the data
	 * @param setMinMax - whether to define minimal and maximal intensity relative to whom everything is normalized to [0...1]
	 * @param limitDetections - offers the chance to select certain detections only based on their intensity
	 * @param groupTiles - if grouping of tiles is wanted
	 * @param groupIllums - if grouping of illums is wanted
	 * @return - if it was successful
	 */
	public abstract boolean queryParameters(
			final boolean defineAnisotropy,
			final boolean setMinMax,
			final boolean limitDetections,
			final boolean groupTiles,
			final boolean groupIllums );

	/*
	 * @param spimData
	 * @param viewIdsToProcess - which view id's to segment
	 * @return - a new instance without any special properties
	 */
	public abstract InterestPointDetectionGUI newInstance(
			final SpimData2 spimData,
			final List< ViewId > viewIdsToProcess );
	
	/*
	 * @return - to be displayed in the generic dialog
	 */
	public abstract String getDescription();
	
	/*
	 * @return - stored in the XML so that it is reproducible how the points were segmented
	 */
	public abstract String getParameters();
}
