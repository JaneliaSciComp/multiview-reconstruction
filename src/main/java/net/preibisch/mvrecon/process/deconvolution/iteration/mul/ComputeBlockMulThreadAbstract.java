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
package net.preibisch.mvrecon.process.deconvolution.iteration.mul;

import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThreadAbstract;

public abstract class ComputeBlockMulThreadAbstract extends ComputeBlockThreadAbstract implements ComputeBlockMulThread
{
	final int numViews;

	/**
	 * Instantiate a block thread
	 *
	 * @param blockFactory - which ImgFactory to use for the copy of the deconvolved image
	 * @param numViews - the number of multiview - views
	 * @param minValue - the minimum value inside the deconvolved image
	 * @param blockSize - the block size in which we process
	 * @param id - the unique id of this thread, greater or equal to 0, starting at 0 and increasing by 1 each thread
	 */
	public ComputeBlockMulThreadAbstract(
			final ImgFactory< FloatType > blockFactory,
			final int numViews,
			final float minValue,
			final int[] blockSize,
			final int id )
	{
		super( blockFactory, minValue, blockSize, id );

		this.numViews = numViews;
	}

	/**
	 * Instantiate a block thread
	 * 
	 * @param minValue - the minimum value inside the deconvolved image
	 * @param numViews - the number of multiview - views
	 * @param blockSize - the block size in which we process
	 * @param id - the unique id of this thread, greater or equal to 0, starting at 0 and increasing by 1 each thread
	 */
	public ComputeBlockMulThreadAbstract(
			final float minValue,
			final int numViews,
			final int[] blockSize,
			final int id )
	{
		this( new ArrayImgFactory<>(), numViews, minValue, blockSize, id );
	}

	/**
	 * @return the number of multiview-views
	 */
	@Override
	public int getNumViews() { return numViews; }
}
