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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp;

import mpicbg.models.Model;

public class IterativeClosestPointParameters
{
	public static double maxDistance = 5;
	public static int maxIterations = 100;
	public static boolean defaultUseRANSAC = true;
	public static double defaultMaxEpsionRANSAC = maxDistance / 2.0;
	public static int defaultNumIterationsRANSAC = 200;
	public static int defaultMinNumPoints = 12;
	public static double defaultMinInlierRatio = 0.1;

	final private double d;
	final private int maxIt;
	final private boolean useRANSAC;
	final private double maxEpsilonRANSAC;
	final private double minInlierRatio;
	final private int maxIterationsRANSAC;
	final private int minNumPoints;

	private Model< ? > model = null;

	public IterativeClosestPointParameters(
			final Model< ? > model,
			final double maxDistance,
			final int maxIterations,
			final boolean useRANSAC,
			final double minInlierRatio,
			final double maxEpsilonRANSAC,
			final int maxIterationsRANSAC,
			final int minNumPoints )
	{
		this.model = model;
		this.d = maxDistance;
		this.maxIt = maxIterations;
		this.useRANSAC = useRANSAC;
		this.maxEpsilonRANSAC = maxEpsilonRANSAC;
		this.minInlierRatio = minInlierRatio;
		this.maxIterationsRANSAC = maxIterationsRANSAC;
		this.minNumPoints = minNumPoints;
	}

	public IterativeClosestPointParameters(
			final Model< ? > model,
			IterativeClosestPointParameters p )
	{
		this( model, p.getMaxDistance(), p.getMaxNumIterations(), p.useRANSAC(), p.getMinInlierRatio(), p.getMaxEpsilonRANSAC(), p.getMaxIterationsRANSAC(), p.getMinNumPoints() );
	}

	public IterativeClosestPointParameters( final Model< ? > model )
	{
		this( model, maxDistance, maxIterations, defaultUseRANSAC, defaultMinInlierRatio, defaultMaxEpsionRANSAC, defaultNumIterationsRANSAC, defaultMinNumPoints );
	}

	public Model< ? > getModel() { return model.copy(); }
	public double getMaxDistance() { return d; }
	public double getMinInlierRatio() { return minInlierRatio; }
	public int getMaxNumIterations() { return maxIt; }
	public boolean useRANSAC() { return useRANSAC; }
	public double getMaxEpsilonRANSAC() { return maxEpsilonRANSAC; }
	public int getMaxIterationsRANSAC() { return maxIterationsRANSAC; }
	public int getMinNumPoints() { return minNumPoints; }
}
