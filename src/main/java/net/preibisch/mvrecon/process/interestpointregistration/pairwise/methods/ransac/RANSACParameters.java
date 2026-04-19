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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac;

/**
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class RANSACParameters
{
	public static final String[] ransacChoices = new String[]{ "Fast (1,000)", "Normal (10,000)", "Thorough (10^5)", "Very thorough (10^6)", "Ridiculous (10^7)" };
	public static final int[] ransacChoicesIterations = new int[]{ 1000, 10000, 100_000, 1_000_000, 10_000_000 };

	public static double max_epsilon = 5;
	public static double min_inlier_ratio = 0.1f;
	public static int num_iterations = 10000;
	public static int min_num_matches = 12;
	public static boolean multi_consensus = false;

	protected double maxEpsilon, minInlierRatio;
	protected int minNumMatches, numIterations;
	protected boolean multiConsensus;

	public RANSACParameters( final double maxEpsilon, final double minInlierRatio, final int minNumMatches, final int numIterations, final boolean multiConsensus )
	{
		this.maxEpsilon = maxEpsilon;
		this.minInlierRatio = minInlierRatio;
		this.minNumMatches = minNumMatches;
		this.numIterations = numIterations;
		this.multiConsensus = multiConsensus;
	}

	public RANSACParameters()
	{
		this.maxEpsilon = max_epsilon;
		this.numIterations = num_iterations;
		this.minInlierRatio = min_inlier_ratio;
		this.minNumMatches = min_num_matches;
		this.multiConsensus = multi_consensus;
	}

	public double getMaxEpsilon() { return maxEpsilon; }
	public double getMinInlierRatio() { return minInlierRatio; }
	public int getMinNumMatches() { return minNumMatches; }
	public int getNumIterations() { return numIterations; }
	public boolean multiConsensus() { return multiConsensus; }
}
