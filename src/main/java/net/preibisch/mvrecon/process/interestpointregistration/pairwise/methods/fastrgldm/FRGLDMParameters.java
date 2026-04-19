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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm;

import mpicbg.models.Model;

public class FRGLDMParameters
{
	public static double ratioOfDistance = 10;

	public static int redundancy = 1;

	protected final double rod;
	protected final int nn, re;

	private Model< ? > model = null;
	public Model< ? > getModel() { return model.copy(); }

	public FRGLDMParameters( final Model< ? > model )
	{
		this.rod = ratioOfDistance;
		this.nn = 3;
		this.re = redundancy;
		this.model = model;
	}
	
	public FRGLDMParameters( final Model< ? > model, final double ratioOfDistance, final int redundancy )
	{
		this.model = model;
		this.rod = ratioOfDistance;
		this.nn = 3;
		this.re = redundancy;
	}

	public double getRatioOfDistance() { return rod; }
	public int getNumNeighbors() { return nn; }
	public int getRedundancy() { return re; }
}
