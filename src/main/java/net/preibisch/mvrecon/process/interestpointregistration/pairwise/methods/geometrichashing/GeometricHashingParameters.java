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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.geometrichashing;

import mpicbg.models.Model;

public class GeometricHashingParameters
{
	public static double differenceThreshold = Float.MAX_VALUE;
	public static double ratioOfDistance = 10;
	public static int redundancy = 0;

	protected final double dt, rod;
	protected final int re;
	private Model< ? > model = null;

	public GeometricHashingParameters( final Model< ? > model )
	{
		this.dt = differenceThreshold;
		this.rod = ratioOfDistance;
		this.model = model;
		this.re = redundancy;
	}
	
	public GeometricHashingParameters(
			final Model< ? > model,
			final double differenceThreshold,
			final double ratioOfDistance,
			final int redundancy )
	{
		this.dt = differenceThreshold;
		this.rod = ratioOfDistance;
		this.model = model;
		this.re = redundancy;
	}

	public Model< ? > getModel() { return model.copy(); }
	public double getDifferenceThreshold() { return dt; }
	public double getRatioOfDistance() { return rod; }
	public int getRedundancy() { return re; }
}
