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

import java.util.HashMap;
import java.util.concurrent.ExecutorService;

import net.preibisch.mvrecon.process.cuda.CUDADevice;
import net.preibisch.mvrecon.process.cuda.CUDAFourierConvolution;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThreadFactory;

public class ComputeBlockSeqThreadCUDAFactory implements ComputeBlockThreadFactory< ComputeBlockSeqThread >
{
	final ExecutorService service;
	final float minValue;
	final float lambda;
	final int[] blockSize;
	final CUDAFourierConvolution cuda;
	final HashMap< Integer, CUDADevice > idToCudaDevice;

	public ComputeBlockSeqThreadCUDAFactory(
			final ExecutorService service,
			final float minValue,
			final float lambda,
			final int[] blockSize,
			final CUDAFourierConvolution cuda,
			final HashMap< Integer, CUDADevice > idToCudaDevice )
	{
		this.service = service;
		this.minValue = minValue;
		this.lambda = lambda;
		this.blockSize = blockSize.clone();
		this.cuda = cuda;
		this.idToCudaDevice = idToCudaDevice;
	}

	@Override
	public ComputeBlockSeqThread create( final int id )
	{
		return new ComputeBlockSeqThreadCUDA( service, minValue, lambda, id, blockSize, cuda, idToCudaDevice.get( id ) );
	}

	@Override
	public int numParallelBlocks() { return idToCudaDevice.keySet().size(); }

	@Override
	public String toString()
	{
		String out = "CUDA based using " + numParallelBlocks() + " devices:";

		for ( int devId = 0; devId < numParallelBlocks(); ++devId )
			out += " [" + idToCudaDevice.get( devId ) + "]";

		return out;
	}
}
