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

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm.FRGLDMPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm.FRGLDMParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;

/**
 * Fast Redundant Geometric Local Descriptor Matching (RGLDM)
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class FRGLDMGUI extends PairwiseGUI
{
	public static int defaultModel = 2;
	public static boolean defaultRegularize = true;
	public static int defaultRANSACIterationChoice = 1;
	public static int defaultMinNumMatches = 12;
	public static double defaultMinInlierRatio = 0.1;
	public static boolean defaultMultiConsensus = false;

	protected TransformationModelGUI model = null;

	protected FRGLDMParameters parameters;
	protected RANSACParameters ransacParams;

	@Override
	public FRGLDMPairwise< InterestPoint > pairwiseMatchingInstance()
	{
		return new FRGLDMPairwise< InterestPoint >( ransacParams, parameters );
	}

	@Override
	public MatcherPairwise< GroupedInterestPoint< ViewId > > pairwiseGroupedMatchingInstance()
	{
		return new FRGLDMPairwise< GroupedInterestPoint< ViewId > >( ransacParams, parameters );
	}

	@Override
	public FRGLDMGUI newInstance(final SpimData2 spimData) { return new FRGLDMGUI(); }

	@Override
	public String getDescription() { return "Fast descriptor-based (translation invariant)";}

	@Override
	public void addQuery( final GenericDialog gd )
	{
		gd.addChoice( "Transformation model", TransformationModelGUI.modelChoice, TransformationModelGUI.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );

		gd.addSlider( "Redundancy for descriptor matching", 0, 10, FRGLDMParameters.redundancy );
		gd.addSlider( "Significance required for a descriptor match", 1.0, 10.0, FRGLDMParameters.ratioOfDistance );

		gd.addMessage( "" );
		gd.addMessage( "Parameters for robust model-based outlier removal (RANSAC)", new Font( Font.SANS_SERIF, Font.BOLD, 12 ) );
		gd.addMessage( "" );

		gd.addSlider( "Allowed_error_for_RANSAC (px)", 0.5, 100.0, RANSACParameters.max_epsilon );
		gd.addSlider( "Minmal_number_of_inliers", 4, 100, defaultMinNumMatches );
		gd.addSlider( "Minmal_inlier_ratio", 0.0, 1.0, defaultMinInlierRatio );
		gd.addChoice( "RANSAC_iterations", RANSACParameters.ransacChoices, RANSACParameters.ransacChoices[ defaultRANSACIterationChoice ] );
		gd.addCheckbox( "Multi_consensus_RANSAC", defaultMultiConsensus );
	}

	@Override
	public boolean parseDialog( final GenericDialog gd )
	{
		model = new TransformationModelGUI( defaultModel = gd.getNextChoiceIndex() );

		if ( defaultRegularize = gd.getNextBoolean() )
		{
			if ( !model.queryRegularizedModel() )
				return false;
		}

		final int redundancy = FRGLDMParameters.redundancy = (int)Math.round( gd.getNextNumber() );
		final double ratioOfDistance = FRGLDMParameters.ratioOfDistance = gd.getNextNumber();
		final double maxEpsilon = RANSACParameters.max_epsilon = gd.getNextNumber();
		final int minNumMatches = defaultMinNumMatches = (int)Math.round( gd.getNextNumber() );
		final double minInlierRatio = defaultMinInlierRatio = gd.getNextNumber();
		final int ransacIterations = RANSACParameters.ransacChoicesIterations[ defaultRANSACIterationChoice = gd.getNextChoiceIndex() ];
		final boolean multiConsensus = defaultMultiConsensus = gd.getNextBoolean();

		this.parameters = new FRGLDMParameters( model.getModel(), ratioOfDistance, redundancy );
		this.ransacParams = new RANSACParameters( maxEpsilon, minInlierRatio, minNumMatches, ransacIterations, multiConsensus );

		IOFunctions.println( "Selected Paramters:" );
		IOFunctions.println( "model: " + defaultModel );
		IOFunctions.println( "redundancy: " + redundancy );
		IOFunctions.println( "ratioOfDistance: " + ratioOfDistance );
		IOFunctions.println( "maxEpsilon: " + maxEpsilon );
		IOFunctions.println( "minNumMatches: " + minNumMatches );
		IOFunctions.println( "ransacIterations: " + ransacIterations );
		IOFunctions.println( "ransacMultiConsensus: " + multiConsensus );
		IOFunctions.println( "minInlierRatio: " + minInlierRatio );

		return true;
	}

	@Override
	public TransformationModelGUI getMatchingModel() { return model; }

	@Override
	public double getMaxError() { return ransacParams.getMaxEpsilon(); }

	@Override
	public double globalOptError() { return ransacParams.getMaxEpsilon(); }
}
