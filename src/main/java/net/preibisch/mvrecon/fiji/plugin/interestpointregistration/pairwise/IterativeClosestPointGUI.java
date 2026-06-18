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

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointParameters;

/**
 * Iterative closest point implementation
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class IterativeClosestPointGUI extends PairwiseGUI
{
	public static int defaultModel = 2;
	public static boolean defaultRegularize = true;
	protected TransformationModelGUI model = null;

	protected IterativeClosestPointParameters parameters;

	@Override
	public IterativeClosestPointPairwise< InterestPoint > pairwiseMatchingInstance()
	{
		final IterativeClosestPointParameters ip = new IterativeClosestPointParameters( model.getModel() );
		return new IterativeClosestPointPairwise< InterestPoint >( ip );
	}

	@Override
	public MatcherPairwise< GroupedInterestPoint< ViewId > > pairwiseGroupedMatchingInstance()
	{
		final IterativeClosestPointParameters ip = new IterativeClosestPointParameters( model.getModel() );
		return new IterativeClosestPointPairwise< GroupedInterestPoint< ViewId > >( ip );
	}

	@Override
	public void addQuery( final GenericDialog gd )
	{
		gd.addChoice( "Transformation model", TransformationModelGUI.modelChoice, TransformationModelGUI.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );

		gd.addSlider( "Maximal_distance for correspondence (px)", 0.25, 40.0, IterativeClosestPointParameters.maxDistance );
		gd.addNumericField( "Maximal_number of iterations", IterativeClosestPointParameters.maxIterations, 0 );
		gd.addCheckbox( "Use_RANSAC (at every iteration)", IterativeClosestPointParameters.defaultUseRANSAC);
		gd.addSlider( "Allowed_error_for_RANSAC (px)", 0.5, 100.0, IterativeClosestPointParameters.defaultMaxEpsionRANSAC );
		gd.addNumericField( "RANSAC_iterations", IterativeClosestPointParameters.defaultNumIterationsRANSAC, 0 );
		gd.addNumericField( "Minimal_number of required points", IterativeClosestPointParameters.defaultMinNumPoints, 0 );
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

		final double maxDistance = IterativeClosestPointParameters.maxDistance = gd.getNextNumber();
		final int maxIterations = IterativeClosestPointParameters.maxIterations = (int)Math.round( gd.getNextNumber() );
		final boolean useRANSAC = IterativeClosestPointParameters.defaultUseRANSAC = gd.getNextBoolean();
		final double maxEpsilonRANSAC = IterativeClosestPointParameters.defaultMaxEpsionRANSAC = gd.getNextNumber();
		final int numIterationsRANSAC = IterativeClosestPointParameters.defaultNumIterationsRANSAC = (int)Math.round( gd.getNextNumber() );
		final int minNumPoints = IterativeClosestPointParameters.defaultMinNumPoints = (int)Math.round( gd.getNextNumber() );

		this.parameters = new IterativeClosestPointParameters( model.getModel(), maxDistance, maxIterations, useRANSAC, 0.0, maxEpsilonRANSAC, numIterationsRANSAC, minNumPoints );

		return true;
	}

	@Override
	public IterativeClosestPointGUI newInstance(final SpimData2 spimData) { return new IterativeClosestPointGUI(); }

	@Override
	public String getDescription() { return "Assign closest-points with ICP (no invariance)";}

	@Override
	public TransformationModelGUI getMatchingModel() { return model; }

	@Override
	public double getMaxError() { return parameters.getMaxDistance(); }

	@Override
	public double globalOptError() { return parameters.getMaxDistance(); }
}
