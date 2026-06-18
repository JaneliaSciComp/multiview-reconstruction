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
package net.preibisch.mvrecon.process.interestpointdetection.methods.dog;

import java.util.Collection;

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import net.preibisch.mvrecon.process.cuda.CUDADevice;
import net.preibisch.mvrecon.process.cuda.CUDASeparableConvolution;
import net.preibisch.mvrecon.process.interestpointdetection.methods.InterestPointParameters;

public class DoGParameters extends InterestPointParameters
{
	/**
	 * 0 = no subpixel localization
	 * 1 = quadratic fit
	 */
	public int localization = 1;

	public double sigma = 1.8;
	public double threshold = 0.01;
	public boolean findMin = false;
	public boolean findMax = true;

	public double percentGPUMem = 75;
	public CUDADevice deviceCUDA = null;
	public CUDASeparableConvolution cuda = null;
	public boolean accurateCUDA = false;

	public DoGParameters() { super(); }

	public DoGParameters(
			final Collection<ViewDescription> toProcess,
			final ImgLoader imgloader,
			final double sigma,
			final double threshold )
	{
		super( toProcess, imgloader );
		this.sigma = sigma;
		this.threshold = threshold;
	}

	public DoGParameters(
			final Collection<ViewDescription> toProcess,
			final ImgLoader imgloader,
			final double sigma, final int downsampleXY )
	{
		super( toProcess, imgloader );
		this.sigma = sigma;
		this.downsampleXY = downsampleXY;
	}
}
