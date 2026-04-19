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
package net.preibisch.mvrecon.process.deconvolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.fft2.FFTConvolution;
import net.preibisch.mvrecon.Threads;

public class DeconViews
{
	final private Dimensions dimensions;
	final private ArrayList< DeconView > views;
	final private ExecutorService service;
	final private int numThreads;

	public DeconViews( final Collection< DeconView > input, final ExecutorService service )
	{
		this.views = new ArrayList<>();
		this.views.addAll( input );

		this.service = service;

		if ( ThreadPoolExecutor.class.isInstance( service ) )
			this.numThreads = ((ThreadPoolExecutor)service).getMaximumPoolSize();
		else
			this.numThreads = Threads.numThreads();

		final RandomAccessibleInterval< ? > firstImg = input.iterator().next().getImage();

		final long[] dim = new long[ firstImg.numDimensions() ];
		firstImg.dimensions( dim );

		for ( final DeconView view : views )
			for ( int d = 0; d < dim.length; ++d )
				if ( dim[ d ] != view.getImage().dimension( d ) || dim[ d ] != view.getWeight().dimension( d ) )
					throw new RuntimeException( "Dimensions of the input images & weights do not match." );

		this.dimensions = new FinalDimensions( dim );

		// init the psfs
		for ( final DeconView view : views )
			view.getPSF().init( this, view.getBlockSize() );
	}

	public Dimensions getPSIDimensions() { return dimensions; }
	public List< DeconView > getViews() { return views; }
	public ExecutorService getExecutorService() { return service; }
	public int getNumThreads() { return numThreads; }

	public static ExecutorService createExecutorService()
	{
		return FFTConvolution.createExecutorService( Threads.numThreads() );
	}
}
