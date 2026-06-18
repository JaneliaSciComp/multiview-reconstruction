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
package net.preibisch.mvrecon.process.deconvolution.iteration;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.cuda.Block;
import net.preibisch.mvrecon.process.deconvolution.DeconView;

/**
 * Executes one Lucy-Richardson iteration on one specifc block.
 * When initialized, it is not relevant which block it will be,
 * since it only depends on the block size
 *
 * @author stephan.preibisch@gmx.de
 *
 */
public interface ComputeBlockThread
{
	/**
	 * @return the block size in which we process
	 */
	public int[] getBlockSize();

	/**
	 * @return the minimum value inside the deconvolved image
	 */
	public float getMinValue();

	/**
	 * @return the unique id of this thread, greater or equal to 0, starting at 0 and increasing by 1 each thread
	 */
	public int getId();

	/**
	 * contains the deconvolved image at the current iteration, copied into a block from outside (outofbounds is mirror)
	 *
	 * @return the Img to use in order to provide the copied psiBlock
	 */
	public Img< FloatType > getPsiBlockTmp();

	public class IterationStatistics
	{
		public double sumChange = 0;
		public double maxChange = -1;
	}
}
