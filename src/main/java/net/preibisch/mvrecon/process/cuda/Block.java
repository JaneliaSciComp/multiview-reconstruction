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

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.imglib2.AbstractInterval;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.iterator.LocalizingZeroMinIntervalIterator;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;

public class Block extends AbstractInterval
{
	/**
	 * the number of dimensions of this block
	 */
	final int numDimensions;
	
	/**
	 * The dimensions of the block
	 */
	final long[] blockSize;
	
	/**
	 * The offset in coordinates (coordinate system of the original image) 
	 */
	final long[] offset;
	
	/**
	 * The effective size that can be convolved (depends on the kernelsize)
	 */
	final long[] effectiveSize;
	
	/**
	 * The effective offset, i.e. where the useful convolved data starts (coordinate system of the original image)
	 */
	final long[] effectiveOffset;
	
	/**
	 * The effective offset, i.e. where the useful convoved data starts (local coordinate system)
	 */
	final long[] effectiveLocalOffset;

	/**
	 * If the blocks that cover the image are precise or an approximation
	 */
	final boolean isPrecise;

	final Vector< ImagePortion > portionsCopy, portionsPaste;
	final ExecutorService taskExecutor;
	final boolean executorServiceProvided;

	public Block(
			final ExecutorService service,
			final long[] blockSize,
			final long[] offset,
			final long[] effectiveSize,
			final long[] effectiveOffset,
			final long[] effectiveLocalOffset,
			final boolean isPrecise )
	{
		super( offset, max( offset, blockSize ) );
		this.numDimensions = blockSize.length;
		this.blockSize = blockSize.clone();
		this.offset = offset.clone();
		this.effectiveSize = effectiveSize.clone();
		this.effectiveOffset = effectiveOffset.clone();
		this.effectiveLocalOffset = effectiveLocalOffset.clone();
		this.isPrecise = isPrecise;

		long n = blockSize[ 0 ];
		for ( int d = 1; d < numDimensions; ++d )
			n *= blockSize[ d ];

		// split up into many parts for multithreading
		this.portionsCopy = FusionTools.divideIntoPortions( n );

		n = effectiveSize[ 0 ];
		for ( int d = 1; d < numDimensions; ++d )
			n *= effectiveSize[ d ];

		// split up into many parts for multithreading
		this.portionsPaste = FusionTools.divideIntoPortions( n );

		if ( service == null )
		{
			this.taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );
			this.executorServiceProvided = false;
		}
		else
		{
			this.taskExecutor = service;
			this.executorServiceProvided = true;
		}
	}

	public long[] getBlockSize() { return blockSize.clone(); }
	public long[] getEffectiveSize() { return effectiveSize.clone(); }

	/** WARNING: this method may never get invoked, unless the garbage collector processes this instance;
	 *           what is likely to happen is that the native memory consumed by the {@link ExecutorService}
	 *           is never released.
	 *           It would be preferable to declare a "destroy()" method that does so explictly. */
	@Override
	public void finalize()
	{
		if ( !executorServiceProvided )
			taskExecutor.shutdown();
	}

	/**
	 * @return - if the blocks that cover an area/volume/... are precise, i.e. if they are identical to performing the convolution on the entire image. Non-precise blocks do not need an outofbounds, they will not query data from outside of the blocked area.
	 */
	public boolean isPrecise() { return isPrecise; }

	/**
	 * @param source - needs to be extended with an OutOfBounds in case the block extends past the boundaries of the RandomAccessibleInterval
	 * @param block - the Block to copy it to
	 */
	public void copyBlock( final RandomAccessible< FloatType > source, final RandomAccessibleInterval< FloatType > block )
	{
		// set up threads
		final ArrayList< Callable< Boolean > > tasks = new ArrayList< Callable< Boolean > >();

		for ( int i = 0; i < portionsCopy.size(); ++i )
		{
			final int threadIdx = i;

			tasks.add( new Callable< Boolean >()
			{
				@SuppressWarnings("unchecked")
				@Override
				public Boolean call() throws Exception
				{
					if ( source.numDimensions() == 3 && ArrayImg.class.isInstance( block ) )
						copy3dArray( threadIdx, portionsCopy.size(), source, (ArrayImg< FloatType, ?>)block, offset );
					else
					{
						final ImagePortion portion = portionsCopy.get( threadIdx );
						copy( portion.getStartPosition(), portion.getLoopSize(), source, block, offset);
					}
					
					return true;
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
			IOFunctions.println( "Failed to copy block: " + e );
			e.printStackTrace();
			return;
		}
	}

	public void pasteBlock( final RandomAccessibleInterval< FloatType > target, final RandomAccessibleInterval< FloatType > block )
	{
		// set up threads
		final ArrayList< Callable< Boolean > > tasks = new ArrayList< Callable< Boolean > >();

		for ( int i = 0; i < portionsPaste.size(); ++i )
		{
			final int threadIdx = i;
			
			tasks.add( new Callable< Boolean >()
			{
				@SuppressWarnings("unchecked")
				@Override
				public Boolean call() throws Exception
				{
					if ( target.numDimensions() == 3 && ArrayImg.class.isInstance( target ) && ArrayImg.class.isInstance( block ) )
						paste3d( threadIdx, portionsPaste.size(), (ArrayImg< FloatType, ?>)target, (ArrayImg< FloatType, ?>)block, effectiveOffset, effectiveSize, effectiveLocalOffset );
					else
					{
						final ImagePortion portion = portionsPaste.get( threadIdx );
						paste( portion.getStartPosition(), portion.getLoopSize(), target, block, effectiveOffset, effectiveSize, effectiveLocalOffset );
					}
					
					return true;
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
			IOFunctions.println( "Failed to paste block: " + e );
			e.printStackTrace();
			return;
		}
	}

	private static final void copy( final long start, final long loopSize, final RandomAccessible< FloatType > source, final RandomAccessibleInterval< FloatType > block, final long[] offset )
	{
		final int numDimensions = source.numDimensions();
		final Cursor< FloatType > cursor = Views.iterable( block ).localizingCursor();

		// define where we will query the RandomAccess on the source
		// (we say it is the entire block, although it is just a part of it,
		// but which part depends on the underlying container)
		final long[] min = new long[ numDimensions ];
		final long[] max = new long[ numDimensions ];
		
		for ( int d = 0; d < numDimensions; ++d )
		{
			min[ d ] = offset[ d ];
			max[ d ] = offset[ d ] + block.dimension( d ) - 1;
		}

		final RandomAccess< FloatType > randomAccess = source.randomAccess( new FinalInterval( min, max ) );

		cursor.jumpFwd( start );

		final long[] tmp = new long[ numDimensions ];

		for ( long l = 0; l < loopSize; ++l )
		{
			cursor.fwd();
			cursor.localize( tmp );
			
			for ( int d = 0; d < numDimensions; ++d )
				tmp[ d ] += offset[ d ];
			
			randomAccess.setPosition( tmp );
			cursor.get().set( randomAccess.get() );
		}
	}

	private static final void copy3dArray( final int threadIdx, final int numThreads, final RandomAccessible< FloatType > source, final ArrayImg< FloatType, ? > block, final long[] offset )
	{
		final int w = (int)block.dimension( 0 );
		final int h = (int)block.dimension( 1 );
		final int d = (int)block.dimension( 2 );

		final long offsetX = offset[ 0 ];
		final long offsetY = offset[ 1 ];
		final long offsetZ = offset[ 2 ];
		final float[] blockArray = ((FloatArray)block.update( null ) ).getCurrentStorageArray();

		// define where we will query the RandomAccess on the source
		final FinalInterval interval = new FinalInterval( new long[] { offsetX, offsetY, offsetZ }, new long[] { offsetX + w - 1, offsetY + h - 1, offsetZ + d - 1 } );
		final RandomAccess< FloatType > randomAccess = source.randomAccess( interval );

		final long[] tmp = new long[]{ offsetX, offsetY, 0 };

		for ( int z = threadIdx; z < d; z += numThreads )
		{
			tmp[ 2 ] = z + offsetZ;
			randomAccess.setPosition( tmp );

			int i = z * h * w;

			for ( int y = 0; y < h; ++y )
			{
				randomAccess.setPosition( offsetX, 0 );

				for ( int x = 0; x < w; ++x )
				{
					blockArray[ i++ ] = randomAccess.get().get();
					randomAccess.fwd( 0 );
				}

				randomAccess.move( -w, 0 );
				randomAccess.fwd( 1 );
			}
		}
	}

	private static final void paste( final long start, final long loopSize, final RandomAccessibleInterval< FloatType > target, final RandomAccessibleInterval< FloatType > block, 
			final long[] effectiveOffset, final long[] effectiveSize, final long[] effectiveLocalOffset )
	{
		final int numDimensions = target.numDimensions();
		
		// iterate over effective size
		final LocalizingZeroMinIntervalIterator cursor = new LocalizingZeroMinIntervalIterator( effectiveSize );
		
		// read from block
		final RandomAccess<FloatType> blockRandomAccess  = block.randomAccess();
		
		// write to target		
		final RandomAccess<FloatType> targetRandomAccess  = target.randomAccess();
		
		cursor.jumpFwd( start );
		
		final long[] tmp = new long[ numDimensions ];
		
		for ( long l = 0; l < loopSize; ++l )
		{
			cursor.fwd();
			cursor.localize( tmp );
			
			// move to the relative local offset where the real data starts
			for ( int d = 0; d < numDimensions; ++d )
				tmp[ d ] += effectiveLocalOffset[ d ];
			
			blockRandomAccess.setPosition( tmp );
			
			// move to the right position in the image
			for ( int d = 0; d < numDimensions; ++d )
				tmp[ d ] += effectiveOffset[ d ] - effectiveLocalOffset[ d ];

			targetRandomAccess.setPosition( tmp );

			// write the pixel
			targetRandomAccess.get().set( blockRandomAccess.get() );
		}
	}

	private static final void paste3d( final int threadIdx, final int numThreads, final ArrayImg< FloatType, ? > target, final ArrayImg< FloatType, ? > block, 
			final long[] effectiveOffset, final long[] effectiveSize, final long[] effectiveLocalOffset )
	{
		// min position in the output
		final int minX = (int)effectiveOffset[ 0 ];
		final int minY = (int)effectiveOffset[ 1 ];
		final int minZ = (int)effectiveOffset[ 2 ];

		// max+1 of the output area
		final int maxY = (int)effectiveSize[ 1 ] + minY;
		final int maxZ = (int)effectiveSize[ 2 ] + minZ;

		// size of the output area
		final int sX = (int)effectiveSize[ 0 ];

		// min position in the output
		final int minXb = (int)effectiveLocalOffset[ 0 ];
		final int minYb = (int)effectiveLocalOffset[ 1 ];
		final int minZb = (int)effectiveLocalOffset[ 2 ];

		// size of the target image
		final int w = (int)target.dimension( 0 );
		final int h = (int)target.dimension( 1 );

		// size of the block image
		final int wb = (int)block.dimension( 0 );
		final int hb = (int)block.dimension( 1 );

		final float[] blockArray = ((FloatArray)block.update( null ) ).getCurrentStorageArray();
		final float[] targetArray = ((FloatArray)target.update( null ) ).getCurrentStorageArray();
				
		for ( int z = minZ + threadIdx; z < maxZ; z += numThreads )
		{
			final int zBlock = z - minZ + minZb;
			
			int iTarget = z * h * w + minY * w + minX;
			int iBlock = zBlock * hb * wb + minYb * wb + minXb;
			
			for ( int y = minY; y < maxY; ++y )
			{
				copyX( blockArray, targetArray, sX, iTarget, iBlock );

				iTarget += w;
				iBlock += wb;
			}
		}
	}
	
	private static final void copyX( final float[] blockArray, final float[] targetArray, final int count, int iTarget, int iBlock )
	{
		for ( int x = 0; x < count; ++x )
			targetArray[ iTarget++ ] = blockArray[ iBlock++ ];
	}

	public static final long[] max( final long[] min, final long[] size )
	{
		final long[] max = new long[ min.length ];

		for ( int d = 0; d < min.length; ++d )
			max[ d ] = min[ d ] + size[ d ] - 1;

		return max;
	}

	public static void main( String[] args )
	{
		// define the blocksize so that it is one single block
		final RandomAccessibleInterval< FloatType > block = ArrayImgs.floats( 384, 384 );
		final long[] blockSize = new long[ block.numDimensions() ];
		block.dimensions( blockSize );

		final RandomAccessibleInterval< FloatType > image = ArrayImgs.floats( 1024, 1024 );
		final long[] imgSize = new long[ image.numDimensions() ];
		image.dimensions( imgSize );

		// whatever the kernel size is (extra size/2 in general)
		final long[] kernelSize = new long[]{ 16, 32 };

		final BlockGeneratorFixedSizePrecise blockGenerator = new BlockGeneratorFixedSizePrecise( blockSize );
		final List< Block > blocks = blockGenerator.divideIntoBlocks( imgSize, kernelSize );

		int i = 0;

		for ( final Block b : blocks )
		{
			// copy data from the image to the block (including extra space for outofbounds/real image data depending on kernel size)
			b.copyBlock( Views.extendMirrorDouble( image ), block );

			// do something with the block (e.g. also multithreaded, cluster, ...)
			for ( final FloatType f : Views.iterable( block ) )
				f.set( i );

			++i;

			// write the block back (use a temporary image if multithreaded or in general not all are copied first)
			b.pasteBlock( image, block );
		}

		ImageJFunctions.show( image );
	}
}
