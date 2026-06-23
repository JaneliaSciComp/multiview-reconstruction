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
package net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise;

import ij.gui.GenericDialog;

import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;

import mpicbg.spim.data.sequence.ViewId;

public abstract class PairwiseGUI
{
	//protected TransformationModelGUI presetModel = null;

	public static boolean defaultAdvancedRANSAC = false;

	// shared defaults for the RANSAC dialog block (see addRansacQuery/parseRansacQuery)
	public static int defaultMinNumMatches = 12;
	public static int defaultRANSACIterationChoice = 1;
	public static boolean defaultMultiConsensus = false;

	/**
	 * Adds the standard RANSAC parameters to a dialog: allowed error, minimal number of inliers, number of
	 * iterations, multi-consensus, and an "Advanced_RANSAC_options" checkbox (maxTrust / inlier ratio /
	 * filter-RANSAC). Reusable by any plugin so the RANSAC GUI is defined in exactly one place.
	 */
	public static void addRansacQuery( final GenericDialog gd )
	{
		gd.addSlider( "Allowed_error_for_RANSAC (px)", 0.5, 100.0, RANSACParameters.max_epsilon );
		gd.addSlider( "Minmal_number_of_inliers", 4, 100, defaultMinNumMatches );
		gd.addChoice( "RANSAC_iterations", RANSACParameters.ransacChoices, RANSACParameters.ransacChoices[ defaultRANSACIterationChoice ] );
		gd.addCheckbox( "Multi_consensus_RANSAC", defaultMultiConsensus );
		gd.addCheckbox( "Advanced_RANSAC_options", defaultAdvancedRANSAC );
	}

	/**
	 * Reads the fields added by {@link #addRansacQuery(GenericDialog)} (in the same order) and returns the
	 * resulting {@link RANSACParameters}, or {@code null} if the user cancels the advanced sub-dialog.
	 */
	public static RANSACParameters parseRansacQuery( final GenericDialog gd )
	{
		final double maxEpsilon = RANSACParameters.max_epsilon = gd.getNextNumber();
		final int minNumMatches = defaultMinNumMatches = (int)Math.round( gd.getNextNumber() );
		final int ransacIterations = RANSACParameters.ransacChoicesIterations[ defaultRANSACIterationChoice = gd.getNextChoiceIndex() ];
		final boolean multiConsensus = defaultMultiConsensus = gd.getNextBoolean();

		final boolean advancedRANSAC = defaultAdvancedRANSAC = gd.getNextBoolean();
		if ( !queryAdvancedRANSAC( advancedRANSAC ) )
			return null;

		final RANSACParameters rp = new RANSACParameters( maxEpsilon, RANSACParameters.min_inlier_ratio, minNumMatches, ransacIterations, multiConsensus, RANSACParameters.max_trust, RANSACParameters.filter_ransac );

		IOFunctions.println( "maxEpsilon: " + maxEpsilon );
		IOFunctions.println( "minNumMatches: " + minNumMatches );
		IOFunctions.println( "ransacIterations: " + ransacIterations );
		IOFunctions.println( "ransacMultiConsensus: " + multiConsensus );
		IOFunctions.println( "minInlierRatio: " + RANSACParameters.min_inlier_ratio );
		IOFunctions.println( "maxTrust: " + RANSACParameters.max_trust );
		IOFunctions.println( "filterRansac: " + RANSACParameters.filter_ransac );

		return rp;
	}

	/**
	 * If the user enabled "Advanced_RANSAC_options", query maxTrust / minimal inlier ratio / filter-RANSAC
	 * in a follow-up dialog, updating the {@link RANSACParameters} statics that the GUI uses to build its
	 * {@link RANSACParameters}. Returns false if the user cancels the follow-up dialog.
	 *
	 * @param advanced - whether the user ticked the advanced-options checkbox
	 * @return false if the follow-up dialog was canceled, true otherwise
	 */
	protected static boolean queryAdvancedRANSAC( final boolean advanced )
	{
		if ( !advanced )
			return true;

		final GenericDialog gd = new GenericDialog( "Advanced RANSAC options" );
		gd.addNumericField( "Maximal_trust (factor of median residual)", RANSACParameters.max_trust, 2 );
		gd.addSlider( "Minimal_inlier_ratio", 0.0, 1.0, RANSACParameters.min_inlier_ratio );
		gd.addCheckbox( "Filter_RANSAC", RANSACParameters.filter_ransac );
		gd.showDialog();

		if ( gd.wasCanceled() )
			return false;

		RANSACParameters.max_trust = gd.getNextNumber();
		RANSACParameters.min_inlier_ratio = gd.getNextNumber();
		RANSACParameters.filter_ransac = gd.getNextBoolean();

		return true;
	}

	/*
	 * adds the questions this registration wants to ask
	 * 
	 * @param gd
	 */
	public abstract void addQuery( final GenericDialog gd );
	
	/*
	 * queries the questions asked before
	 * 
	 * @param gd
	 * @return
	 */
	public abstract boolean parseDialog( final GenericDialog gd );
	
	/**
	 * @param spimData the current spimdata object (needed to load corresponding points)
	 * @return - a new instance without any special properties
	 */
	public abstract PairwiseGUI newInstance( final SpimData2 spimData );

	/**
	 * @return - to be displayed in the generic dialog
	 */
	public abstract String getDescription();

	/**
	 * @return - the object that will perform a pairwise matching and can return a result
	 */
	public abstract MatcherPairwise< InterestPoint > pairwiseMatchingInstance();

	/**
	 * This is not good style, but when creating the object we do not know which generic parameter will be required
	 * as the user specifies this later (could be a factory)
	 * 
	 * @return - the object that will perform a pairwise matching and can return a result for grouped interestpoints
	 */
	public abstract MatcherPairwise< GroupedInterestPoint< ViewId > > pairwiseGroupedMatchingInstance();

	/**
	 * @return - the model the user chose to perform the registration with
	 */
	public abstract TransformationModelGUI getMatchingModel();

	/**
	 * @return - a maximal error as selected by the user or Double.NaN if not applicable
	 */
	public abstract double getMaxError();

	/**
	 * @return - the error allowed for the global optimization
	 */
	public abstract double globalOptError();
}
