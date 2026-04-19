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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm;

import mpicbg.models.Model;

public class RGLDMParameters
{
	public static double differenceThreshold = Float.MAX_VALUE;
	public static double ratioOfDistance = 3; 

	public static int numNeighbors = 3;
	public static int redundancy = 1;

	public static boolean defaultLimitSearchRadius = false;
	public static double defaultSearchRadius = 100;

	protected final boolean limitSearchRadius;
	protected final double searchRadius;
	protected final double dt, rod;
	protected final int nn, re;

	private Model< ? > model = null;
	public Model< ? > getModel() { return model.copy(); }

	public RGLDMParameters( final Model< ? > model )
	{
		this.dt = differenceThreshold;
		this.rod = ratioOfDistance;
		this.nn = numNeighbors;
		this.re = redundancy;
		this.limitSearchRadius = defaultLimitSearchRadius;
		this.searchRadius = defaultSearchRadius;
		this.model = model;
	}
	
	public RGLDMParameters(
			final Model<?> model,
			final double differenceThreshold,
			final double ratioOfDistance,
			final boolean limitSearchRadius,
			final double searchRadius,
			final int numNeighbors,
			final int redundancy)
	{
		this.model = model;
		this.dt = differenceThreshold;
		this.rod = ratioOfDistance;
		this.nn = numNeighbors;
		this.re = redundancy;
		this.limitSearchRadius = limitSearchRadius;
		this.searchRadius = searchRadius;
	}

	public boolean limitSearchRadius() { return limitSearchRadius; }
	public double searchRadius() { return searchRadius; }
	public double getDifferenceThreshold() { return dt; }
	public double getRatioOfDistance() { return rod; }
	public int getNumNeighbors() { return nn; }
	public int getRedundancy() { return re; }
}
