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

	/**
	 * z voxel / finer lateral voxel (x or y) at FULL resolution as estimated by the user (the PSF blurs z more than the
	 * calibration says): the Gaussians are then isotropic in physical units, the sigma in dimension d of the opened image
	 * is sigma / anisotropy[ d ] (DownsampleTools.anisotropy) and sigma itself is the sigma of the finer lateral axis of
	 * the opened image. NaN (the default) = no z anisotropy: the Gaussians are as wide in z pixels as in pixels of that
	 * axis, laterally they still follow the calibration (DownsampleTools.anisotropyDoG); for equal pixel sizes in x and
	 * y that is isotropic in pixels, what every caller got before 2026-10 (e.g. BigStitcher-Spark)
	 */
	public double anisotropyZ = Double.NaN;

	/**
	 * The downsampling of the views in x, y, z (powers of two, x and y may differ); null (the default) = the
	 * inherited downsampleXY for x and y and downsampleZ, see {@link #downsampling()}
	 */
	public long[] downsampling = null;

	/** @return the downsampling in x, y, z the detection runs at */
	public long[] downsampling()
	{
		return downsampling != null ? downsampling.clone() : new long[] { downsampleXY, downsampleXY, downsampleZ };
	}

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
