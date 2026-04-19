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
package net.preibisch.mvrecon.process.deconvolution.iteration.sequential;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.cuda.Block;
import net.preibisch.mvrecon.process.deconvolution.DeconView;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThread;

/**
 * Executes one Lucy-Richardson iteration on one specifc block.
 * When initialized, it is not relevant which block it will be,
 * since it only depends on the block size
 *
 * @author stephan.preibisch@gmx.de
 *
 */
public interface ComputeBlockSeqThread extends ComputeBlockThread
{
	/**
	 * run an iteration on the block
	 *
	 * @param view - the input view
	 * @param block - the Block instance
	 * @param imgBlock - the input image as block (virtual, outofbounds is zero)
	 * @param weightBlock - the weights for this image (virtual, outofbounds is zero)
	 * @param maxIntensityView - the maximum intensity of the view
	 * @param kernel1 - psf1
	 * @param kernel2 - psf2
	 * @return - statistics of this block
	 */
	public IterationStatistics runIteration(
			final DeconView view,
			final Block block,
			final RandomAccessibleInterval< FloatType > imgBlock, // out of bounds is ZERO
			final RandomAccessibleInterval< FloatType > weightBlock,
			final float maxIntensityView,
			final ArrayImg< FloatType, ? > kernel1,
			final ArrayImg< FloatType, ? > kernel2 );
	//
	// convolve psi (current guess of the image) with the PSF of the current view
	// [psi >> tmp1]
	//

	//
	// compute quotient img/psiBlurred
	// [tmp1, img >> tmp1]
	//

	//
	// blur the residuals image with the kernel
	// (this cannot be don in-place as it might be computed in blocks sequentially,
	// and the input for the n+1'th block cannot be formed by the written back output
	// of the n'th block)
	// [tmp1 >> tmp2]
	//

	//
	// compute final values
	// [psi, weights, tmp2 >> psi]
	//
}
