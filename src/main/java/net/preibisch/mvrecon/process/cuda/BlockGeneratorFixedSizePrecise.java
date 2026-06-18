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
import java.util.concurrent.ExecutorService;

import net.imglib2.iterator.LocalizingZeroMinIntervalIterator;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;

public class BlockGeneratorFixedSizePrecise implements BlockGenerator< Block >
{
	final long[] blockSize;
	final ExecutorService service;

	public BlockGeneratorFixedSizePrecise(
			final ExecutorService service,
			final long[] blockSize )
	{
		this.blockSize = blockSize;
		this.service = service;
	}

	public BlockGeneratorFixedSizePrecise(
			final long[] blockSize )
	{
		this.blockSize = blockSize;
		this.service = null;
	}

	/**
	 * Divides an image into blocks
	 * 
	 * @param imgSize - the size of the image
	 * @param kernelSize - the size of the kernel (has to be odd!)
	 * @return array of blocks
	 */
	public ArrayList< Block > divideIntoBlocks( final long[] imgSize, final long[] kernelSize )
	{
		final int numDimensions = imgSize.length;
		
		// compute the effective size & local offset of each block
		// this is the same for all blocks
		final long[] effectiveSizeGeneral = new long[ numDimensions ];
		final long[] effectiveLocalOffset = new long[ numDimensions ];
		
		for ( int d = 0; d < numDimensions; ++d )
		{
			effectiveSizeGeneral[ d ] = blockSize[ d ] - kernelSize[ d ] + 1;
			
			if ( effectiveSizeGeneral[ d ] <= 0 )
			{
				IOFunctions.println( "Blocksize in dimension " + d + " (" + blockSize[ d ] + ") is smaller than the kernel (" + kernelSize[ d ] + ") which results in an negative effective size: " + effectiveSizeGeneral[ d ] + ". Quitting." );
				return null;
			}
			
			effectiveLocalOffset[ d ] = kernelSize[ d ] / 2;
		}
		
		// compute the amount of blocks needed
		final long[] numBlocks = new long[ numDimensions ];

		for ( int d = 0; d < numDimensions; ++d )
		{
			numBlocks[ d ] = imgSize[ d ] / effectiveSizeGeneral[ d ];
			
			// if the modulo is not 0 we need one more that is only partially useful
			if ( imgSize[ d ] % effectiveSizeGeneral[ d ] != 0 )
				++numBlocks[ d ];
		}
		
		System.out.println( "imgSize " + Util.printCoordinates( imgSize ) );
		System.out.println( "kernelSize " + Util.printCoordinates( kernelSize ) );
		System.out.println( "blockSize " + Util.printCoordinates( blockSize ) );
		System.out.println( "numBlocks " + Util.printCoordinates( numBlocks ) );
		System.out.println( "effectiveSize of blocks" + Util.printCoordinates( effectiveSizeGeneral ) );
		System.out.println( "effectiveLocalOffset " + Util.printCoordinates( effectiveLocalOffset ) );
				
		// now we instantiate the individual blocks iterating over all dimensions
		// we use the well-known ArrayLocalizableCursor for that
		final LocalizingZeroMinIntervalIterator cursor = new LocalizingZeroMinIntervalIterator( numBlocks );
		final ArrayList< Block > blockList = new ArrayList< Block >();

		final int[] currentBlock = new int[ numDimensions ];

		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.localize( currentBlock );

			// compute the current offset
			final long[] offset = new long[ numDimensions ];
			final long[] effectiveOffset = new long[ numDimensions ];
			final long[] effectiveSize = effectiveSizeGeneral.clone();

			for ( int d = 0; d < numDimensions; ++d )
			{
				effectiveOffset[ d ] = currentBlock[ d ] * effectiveSize[ d ];
				offset[ d ] = effectiveOffset[ d ] - kernelSize[ d ]/2;

				if ( effectiveOffset[ d ] + effectiveSize[ d ] > imgSize[ d ] )
					effectiveSize[ d ] = imgSize[ d ] - effectiveOffset[ d ];
			}

			blockList.add( new Block( service, blockSize, offset, effectiveSize, effectiveOffset, effectiveLocalOffset, true ) );
			//System.out.println( "block " + Util.printCoordinates( currentBlock ) + " effectiveOffset: " + Util.printCoordinates( effectiveOffset ) + " effectiveSize: " + Util.printCoordinates( effectiveSize )  + " offset: " + Util.printCoordinates( offset ) + " inside: " + inside );
		}

		return blockList;
	}

}
