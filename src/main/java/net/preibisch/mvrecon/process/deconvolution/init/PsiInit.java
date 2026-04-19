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
package net.preibisch.mvrecon.process.deconvolution.init;

import java.util.List;
import java.util.concurrent.ExecutorService;

import net.imglib2.img.Img;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.deconvolution.DeconView;

public interface PsiInit
{
	public enum PsiInitType { FUSED_BLURRED, AVG, APPROX_AVG, FROM_FILE, FROM_RAI };

	public boolean runInitialization( final Img< FloatType > psi, final List< DeconView > views, final ExecutorService service );

	/**
	 * @return the average in the overlapping area
	 */
	public double getAvg();

	/**
	 * @return the maximal intensities (maybe approximated) of the views, in the same order as the list of DeconView
	 */
	public float[] getMax();
}
