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
package net.preibisch.legacy.registration.bead.laplace;

import Jama.Matrix;

public class DoGMaximum
{
    public DoGMaximum(int x, int y, int z, int iteration)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.iteration = iteration;
    }

  	/**
  	 * Computes the distance between two DoG Maxima
  	 * @param target - Destination DoG Maxima
  	 * @return Distance to target or NaN if target null
  	 */
  	public float getDistanceTo(DoGMaximum target)
  	{
  		if (target == null)
  			return Float.NaN;
  		
  		float difference = 0;
  		
		difference += Math.pow(this.x - target.x,2); 
		difference += Math.pow(this.y - target.y,2);
		difference += Math.pow(this.z - target.z,2);
		difference += Math.pow(this.sigma - target.sigma,2);    		
  		
		return (float)Math.sqrt(difference);
  	}
  	

    public int x, y, z;
    public int iteration;
    //public double[][] hessianMatrix;
    public double[] hessianMatrix3x3;
    public double[] derivativeVector;
    public double[] eigenValues;     
    public double[][] eigenVectors; 

    public Matrix A = null, B = null, X = null;
    public double xd, yd, zd;

    public double EVratioA, EVratioB, EVratioC, minEVratio;

    public double laPlaceValue, quadrFuncValue, sumValue, sigma;

}
