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
package net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters;

import java.util.HashMap;

import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.PairwiseGUI;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.AllAgainstAllOverlap;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.OverlapDetection;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;

import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.sequence.ViewId;

public class BasicRegistrationParameters
{
	public static String[] registrationTypeChoices = {
			"Register timepoints individually", 
			"Match against one reference timepoint (no global optimization)", 
			"All-to-all timepoints matching (global optimization)", 
			"All-to-all timepoints matching with range ('reasonable' global optimization)" };

	public static String[] overlapChoices = {
			"Compare all views against each other",
			"Only compare overlapping views (according to current transformations)" };

	public static String[] interestpointOverlapChoices = {
			"Compare all interest point of overlapping views",
			"Only compare interest points that overlap between views (according to current transformations)" };

	public enum RegistrationType { TIMEPOINTS_INDIVIDUALLY, TO_REFERENCE_TIMEPOINT, ALL_TO_ALL, ALL_TO_ALL_WITH_RANGE };
	public enum OverlapType { ALL_AGAINST_ALL, OVERLAPPING_ONLY };
	public enum InterestPointOverlapType { ALL, OVERLAPPING_ONLY };

	public PairwiseGUI pwr;
	public RegistrationType registrationType;
	public OverlapType overlapType;
	public InterestPointOverlapType interestPointOverlapType;
	public HashMap< ViewId, HashMap< String, Double > > labelMap;
	public boolean groupTiles, groupIllums, groupChannels;

	public boolean matchAcrossLabels;

	public OverlapDetection< ViewId > getOverlapDetection( final SpimData spimData )
	{
		if ( overlapType == OverlapType.ALL_AGAINST_ALL )
			return new AllAgainstAllOverlap<>( 3 );
		else
			return new SimpleBoundingBoxOverlap<>( spimData );
	}
}
