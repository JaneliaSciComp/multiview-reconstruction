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
package net.preibisch.mvrecon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ij.Prefs;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public class Threads
{
	/**
	 * @return num threads for the executorService, we need at least 4 because we have hierarchical 
	 * multithreading that would stall otherwise (e.g. Stitching -- FFTConvolution)
	 */
	public static int numThreads() { return Math.max( 4, Prefs.getThreads() ); }

	public static ExecutorService createFlexibleExecutorService( final int nThreads ) { return Executors.newWorkStealingPool( nThreads ); }
	public static ExecutorService createFlexibleExecutorService() { return createFlexibleExecutorService( numThreads() ); }

	public static ExecutorService createFixedExecutorService( final int nThreads ) { return Executors.newFixedThreadPool( nThreads ); }
	public static ExecutorService createFixedExecutorService() { return createFixedExecutorService( numThreads() ); }

	public static < T > List< ArrayList< Callable< T > > > splitTasks( final List< Callable< T > > tasks, final int batchSize )
	{
		if ( tasks == null )
			return null;

		final ArrayList< ArrayList< Callable< T > > > lists = new ArrayList<>();

		if ( tasks.size() <= batchSize )
		{
			final ArrayList< Callable< T > > list = new ArrayList<>();
			list.addAll( tasks );
			lists.add( list );
		}
		else
		{
			int numBatches = tasks.size() / batchSize + Math.min( 1, tasks.size() % batchSize );

			//System.out.println( "numtasks=" + tasks.size() );
			//System.out.println( "numbatches=" + numBatches );

			for ( int i = 0; i < numBatches; ++i )
			{
				//System.out.println( "batch " + i );

				final ArrayList< Callable< T > > list = new ArrayList<>();

				for ( int j = i * batchSize; j < (i+1) * batchSize && j < tasks.size(); ++j )
				{
					//System.out.println( "adding " + j );
					list.add( tasks.get( j ) );
				}

				lists.add( list );
			}
		}

		return lists;
	}

	public static void main( String[] args )
	{
		// test if it fails on nested tasks
		final ExecutorService service = createFlexibleExecutorService();

		final ArrayList< Callable< Void > > tasks = new ArrayList< Callable< Void > >();

		for ( int i = 0; i < 50; ++i )
		{
			final int j = i;

			tasks.add( new Callable< Void >()
			{
				@Override
				public Void call() throws Exception
				{
					System.out.println( "Computing Thread (running local tasks): " + j );

					Thread.sleep( 1000 );

					final ArrayList< Callable< Void > > localtasks = new ArrayList< Callable< Void > >();

					for ( int k = 0; k < 3; ++k )
					{
						final int l = k;

						localtasks.add( new Callable< Void >()
						{
							@Override
							public Void call() throws Exception
							{
								System.out.println( "Computing local Thread: " + l + "(" + j + ")" );

								Thread.sleep( 500 );

								System.out.println( "Finished local Thread: " + l + "(" + j + ")" );

								return null;
							}
						});
					}

					FusionTools.execTasks( localtasks, service, "small thread loop " + j );

					System.out.println( "Finish Thread: " + j );

					return null;
				}
			});
		}

		FusionTools.execTasks( tasks, service, "big loop" );
	}
}
