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
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.cuda.Block;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInitFactory;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThread.IterationStatistics;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThreadFactory;
import net.preibisch.mvrecon.process.deconvolution.iteration.mul.ComputeBlockMulThread;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public class MultiViewDeconvolutionMul extends MultiViewDeconvolution< ComputeBlockMulThread >
{
	public MultiViewDeconvolutionMul(
			final DeconViews views,
			final int numIterations,
			final PsiInitFactory psiInitFactory,
			final ComputeBlockThreadFactory< ComputeBlockMulThread > computeBlockFactory,
			final ImgFactory< FloatType > psiFactory )
	{
		super( views, numIterations, psiInitFactory, computeBlockFactory, psiFactory );
	}

	public boolean initWasSuccessful()
	{
		return max != null && testBlockIntegrity();
	}

	public boolean testBlockIntegrity()
	{
		final int totalNumBlocks = views.getViews().get( 0 ).getNumBlocks();
		final List< List< Block > > blocks = views.getViews().get( 0 ).getNonInterferingBlocks();

		for ( final DeconView view : views.getViews() )
		{
			if ( view.getNumBlocks() != totalNumBlocks )
			{
				IOFunctions.println( "only a constant number of blocks is supported." );
				return false;
			}

			if ( view.getNonInterferingBlocks().size() != blocks.size() )
			{
				IOFunctions.println( "only a constant number of block batches is supported." );
				return false;
			}

			for ( int i = 0; i < blocks.size(); ++i )
			{
				if ( blocks.get( i ).size() != view.getNonInterferingBlocks().get( i ).size() )
				{
					IOFunctions.println( "only a constant number of blocks within batches is supported." );
					return false;
				}

				for ( int j = 0; j < blocks.get( i ).size(); ++j )
				{
					final Block blockA = blocks.get( i ).get( j );
					final Block blockB = view.getNonInterferingBlocks().get( i ).get( j );

					for ( int d = 0; d < blockA.numDimensions(); ++d )
					{
						if (
								blockA.getBlockSize()[ d ] != blockB.getBlockSize()[ d ] ||
								blockA.getEffectiveSize()[ d ] != blockB.getEffectiveSize()[ d ] ||
								blockA.min( d ) != blockB.min( d ) ||
								blockA.max( d ) != blockB.max( d ) )
						{
							IOFunctions.println( "Block dimensions/offset/effective sizes not compatible, stopping." );
							return false;
						}
					}
				}
			}
		}

		return true;
	}

	public void runNextIteration()
	{
		if ( this.max == null )
			return;

		++it;

		IOFunctions.println( "iteration: " + it + " (" + new Date(System.currentTimeMillis()) + ")" );

		final int totalNumBlocks = views.getViews().get( 0 ).getNumBlocks();
		final List< List< Block > > blocks = views.getViews().get( 0 ).getNonInterferingBlocks();

		final Vector< IterationStatistics > stats = new Vector<>();

		int currentTotalBlock = 0;

		// keep thelast blocks to be written back to the global psi image once it is not overlapping anymore
		final Vector< Pair< Pair< Integer, Block >, Img< FloatType > > > previousBlockWritebackQueue = new Vector<>();
		final Vector< Pair< Pair< Integer, Block >, Img< FloatType > > > currentBlockWritebackQueue = new Vector<>();

		int batch = 0;
		for ( final List< Block > blocksBatch : blocks )
		{
			final int numBlocksBefore = currentTotalBlock;
			final int numBlocksBatch = blocksBatch.size();
			currentTotalBlock += numBlocksBatch;

			System.out.println( "Processing " + numBlocksBatch + " blocks from batch " + (++batch) + "/" + blocks.size() );

			final AtomicInteger ai = new AtomicInteger();
			final Thread[] threads = new Thread[ computeBlockThreads.size() ];

			for ( int t = 0; t < computeBlockThreads.size(); ++t )
			{
				final int threadId = t;

				threads[ threadId ] = new Thread( new Runnable()
				{
					public void run()
					{
						// one ComputeBlockThread creates a temporary image for I/O, valid throughout the whole cycle
						final ComputeBlockMulThread blockThread = computeBlockThreads.get( threadId );

						int blockId;

						while ( ( blockId = ai.getAndIncrement() ) < numBlocksBatch )
						{
							final int blockIdOut = blockId + numBlocksBefore;

							final Block blockStruct = blocksBatch.get( blockId );
							System.out.println( " block " + blockIdOut + ", " + Util.printInterval( blockStruct ) );

							long time = System.currentTimeMillis();
							blockStruct.copyBlock( Views.extendMirrorSingle( psi ), blockThread.getPsiBlockTmp() );
							System.out.println( " block " + blockIdOut + ", thread (" + (threadId+1) + "/" + threads.length + "), (CPU): copy " + (System.currentTimeMillis() - time) );

							final List< DeconView > view = new ArrayList<>();
							final List< RandomAccessibleInterval< FloatType > > imgBlock = new ArrayList<>();
							final List< RandomAccessibleInterval< FloatType > > weightBlock = new ArrayList<>();
							final List< Float > maxIntensityView = new ArrayList<>();
							final List< ArrayImg< FloatType, ? > > kernel1 = new ArrayList<>();
							final List< ArrayImg< FloatType, ? > > kernel2 = new ArrayList<>();

							for ( int i = 0; i < views.getViews().size(); ++i )
							{
								view.add( views.getViews().get( i ) );
								imgBlock.add( Views.zeroMin( Views.interval( Views.extendZero( views.getViews().get( i ).getImage() ), blockStruct ) ) );
								weightBlock.add( Views.zeroMin( Views.interval( Views.extendZero( views.getViews().get( i ).getWeight() ), blockStruct ) ) );
								maxIntensityView.add( max[ i ] );
								kernel1.add( views.getViews().get( i ).getPSF().getKernel1() );
								kernel2.add( views.getViews().get( i ).getPSF().getKernel2() );
							}

							time = System.currentTimeMillis();
							stats.add( blockThread.runIteration(
									view,
									imgBlock,//imgBlock,
									weightBlock,//weightBlock,
									maxIntensityView,
									kernel1,
									kernel2 ) );
							System.out.println( " block " + blockIdOut + ", thread (" + (threadId+1) + "/" + threads.length + "), (CPU): compute " + (System.currentTimeMillis() - time) );

							time = System.currentTimeMillis();
							if ( totalNumBlocks == 1 )
							{
								blockStruct.pasteBlock( psi, blockThread.getPsiBlockTmp() );
								System.out.println( " block " + blockIdOut + ", thread (" + (threadId+1) + "/" + threads.length + "), (CPU): paste " + (System.currentTimeMillis() - time) );
							}
							else
							{
								// copy to the writequeue
								final Img< FloatType > tmp = blockThread.getPsiBlockTmp().factory().create( blockThread.getPsiBlockTmp(), new FloatType() );
								FusionTools.copyImg( blockThread.getPsiBlockTmp(), tmp, views.getExecutorService(), false );
								currentBlockWritebackQueue.add( new ValuePair<>( new ValuePair<>( blockIdOut, blockStruct ), tmp ) );

								System.out.println( " block " + blockIdOut + ", thread (" + (threadId+1) + "/" + threads.length + "), (CPU): saving for later pasting " + (System.currentTimeMillis() - time) );
							}
						}
					}
				});
			}

			// run the threads that process all blocks of this batch in parallel (often, this will be just one thread)
			FusionTools.runThreads( threads );

			// write back previous list of blocks
			writeBack( psi, previousBlockWritebackQueue );

			previousBlockWritebackQueue.clear();
			previousBlockWritebackQueue.addAll( currentBlockWritebackQueue );
			currentBlockWritebackQueue.clear();

		} // finish one block batch

		// write back last list of blocks
		writeBack( psi, previousBlockWritebackQueue );

		// accumulate the results from the individual blocks
		final IterationStatistics is = new IterationStatistics();

		for ( int i = 0; i < stats.size(); ++i )
		{
			is.sumChange += stats.get( i ).sumChange;
			is.maxChange = Math.max( is.maxChange, stats.get( i ).maxChange );
		}

		IOFunctions.println( "iteration: " + it + " --- sum change: " + is.sumChange + " --- max change per pixel: " + is.maxChange );

	}
}
