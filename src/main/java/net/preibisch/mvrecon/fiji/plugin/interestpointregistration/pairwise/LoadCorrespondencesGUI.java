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
package net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise;

import java.awt.Font;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.loadcorrespondences.LoadCorrespondencesPairwise;

public class LoadCorrespondencesGUI extends PairwiseGUI
{
	public static int defaultModel = 2;
	public static boolean defaultRegularize = true;
	public static int defaultMinNumMatches = 12;

	protected TransformationModelGUI model = null;
	protected int minNumMatches = defaultMinNumMatches;

	final SpimData2 spimData;

	public LoadCorrespondencesGUI( final SpimData2 spimData )
	{
		this.spimData = spimData;
	}

	@Override
	public void addQuery(GenericDialog gd)
	{
		gd.addChoice( "Transformation model", TransformationModelGUI.modelChoice, TransformationModelGUI.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );
		gd.addSlider( "Minmal_number_of_inliers", 4, 100, defaultMinNumMatches );
		gd.addMessage( "Note: Corresponding points will be loaded according to the selected interest point label and pre-selection of interest points.", GUIHelper.smallStatusFont );
		gd.addMessage( "");
	}

	@Override
	public boolean parseDialog(GenericDialog gd)
	{
		model = new TransformationModelGUI( defaultModel = gd.getNextChoiceIndex() );

		if ( defaultRegularize = gd.getNextBoolean() )
		{
			if ( !model.queryRegularizedModel() )
				return false;
		}

		minNumMatches = defaultMinNumMatches = (int)Math.round( gd.getNextNumber() );

		return true;
	}

	@Override
	public PairwiseGUI newInstance( final SpimData2 spimData ) { return new LoadCorrespondencesGUI( spimData ); }

	@Override
	public String getDescription() { return "Load existing correspondences"; }

	@Override
	public MatcherPairwise<InterestPoint> pairwiseMatchingInstance() {
		return new LoadCorrespondencesPairwise<>( spimData, minNumMatches );
	}

	@Override
	public MatcherPairwise<GroupedInterestPoint<ViewId>> pairwiseGroupedMatchingInstance() {
		return new LoadCorrespondencesPairwise<>( spimData, minNumMatches );
	}

	@Override
	public TransformationModelGUI getMatchingModel() { return model; }

	@Override
	public double getMaxError() { return 0.0; }

	@Override
	public double globalOptError() { return 0.0; }
}
