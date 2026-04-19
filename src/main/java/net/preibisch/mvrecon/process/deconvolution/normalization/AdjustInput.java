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
package net.preibisch.mvrecon.process.deconvolution.normalization;

import java.util.ArrayList;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import mpicbg.util.RealSum;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;

public class AdjustInput
{
	public static Random rnd = new Random( 14235235 );

	/**
	 * Norms an image so that the sum over all pixels is 1.
	 * 
	 * @param img - the {@link IterableInterval} to normalize
	 */
	final public static void normToSum1( final IterableInterval< FloatType > img )
	{
		final double sum = sumImg( img );

		for ( final FloatType t : img )
			t.set( (float) ((double)t.get() / sum) );
	}
	
	/**
	 * @param img - the input {@link IterableInterval}
	 * @param <T> - the pixel type
	 * @return - the sum of all pixels using {@link RealSum}
	 */
	final public static < T extends RealType< T > > double sumImg( final IterableInterval< T > img )
	{
		final AtomicInteger ai = new AtomicInteger( 0 );

		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( img.size() );
		final RealSum[] sums = new RealSum[ portions.size() ];

		// set up executor service
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );
		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< Void >() 
			{
				@Override
				public Void call() throws Exception
				{
					final int id = ai.getAndIncrement();

					final RealSum sum = new RealSum();

					final Cursor< T > c = img.cursor();
					c.jumpFwd( portion.getStartPosition() );

					for ( long j = 0; j < portion.getLoopSize(); ++j )
						sum.add( c.next().getRealDouble() );

					sums[ id ] = sum;

					return null;
				}
			});
		}

		try
		{
			// invokeAll() returns when all tasks are complete
			taskExecutor.invokeAll( tasks );
		}
		catch ( final InterruptedException e )
		{
			IOFunctions.println( "Failed to compute sumImage: " + e );
			e.printStackTrace();
			return Double.NaN;
		}

		taskExecutor.shutdown();

		final RealSum sum = new RealSum();
		sum.add( sums[ 0 ].getSum() );
		
		for ( final RealSum s : sums )
			sum.add( s.getSum() );

		return sum.getSum();
	}
}
