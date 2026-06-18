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
package net.preibisch.mvrecon.process.cuda;

/**
 * Interface to load the native library for separable convolution using CUDA/CPU
 * (https://github.com/StephanPreibisch/SeparableConvolutionCUDALib)
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
public interface CUDASeparableConvolution extends CUDAStandardFunctions
{
	// In-place convolution with a maximal kernel diameter of 31
	// outofbounds: 0 == zero, 1 == value, 2 == extendlastpixel
	public boolean convolve_127( float[] image, float[] kernelX, float[] kernelY, float[] kernelZ, int imageW, int imageH, int imageD, boolean convolveX, boolean convolveY, boolean convolveZ, int outofbounds, float outofboundsvalue, int devCUDA );
	public boolean convolve_63( float[] image, float[] kernelX, float[] kernelY, float[] kernelZ, int imageW, int imageH, int imageD, boolean convolveX, boolean convolveY, boolean convolveZ, int outofbounds, float outofboundsvalue, int devCUDA );
	public boolean convolve_31( float[] image, float[] kernelX, float[] kernelY, float[] kernelZ, int imageW, int imageH, int imageD, boolean convolveX, boolean convolveY, boolean convolveZ, int outofbounds, float outofboundsvalue, int devCUDA );
	public boolean convolve_15( float[] image, float[] kernelX, float[] kernelY, float[] kernelZ, int imageW, int imageH, int imageD, boolean convolveX, boolean convolveY, boolean convolveZ, int outofbounds, float outofboundsvalue, int devCUDA );
	public boolean convolve_7( float[] image, float[] kernelX, float[] kernelY, float[] kernelZ, int imageW, int imageH, int imageD, boolean convolveX, boolean convolveY, boolean convolveZ, int outofbounds, float outofboundsvalue, int devCUDA );

	// CPU implementation
	// outofbounds: 0 == zero, 1 == value, 2 == extendlastpixel
	public void convolutionCPU( float[] image, float[] kernelX, float[] kernelY, float[] kernelZ, int kernelRX, int kernelRY, int kernelRZ, int imageW, int imageH, int imageD, int outofbounds, float outofboundsvalue );
}
