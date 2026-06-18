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

/**
 * This BlockGenerator cares that the overlap within the image is accounted for and that
 * an outofbounds strategy is taken into account.
 * 
 * @author Stephan Preibisch
 */
public class BlockGeneratorVariableSizePrecise implements BlockGenerator< Block >
{
	final long[] numBlocks;
	final ExecutorService service;

	public BlockGeneratorVariableSizePrecise(
			final ExecutorService service,
			final long[] numBlocksDim )
	{
		this.service = service;
		this.numBlocks = numBlocksDim;
	}

	public BlockGeneratorVariableSizePrecise(
			final long[] numBlocksDim )
	{
		this.service = null;
		this.numBlocks = numBlocksDim;
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

		// now we instantiate the individual blocks iterating over all dimensions
		// we use the well-known ArrayLocalizableCursor for that
		final LocalizingZeroMinIntervalIterator cursor = new LocalizingZeroMinIntervalIterator( numBlocks );
		final ArrayList< Block > blockList = new ArrayList< Block >();

		final long[] currentBlock = new long[ numDimensions ];

		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.localize( currentBlock );

			// the blocksize
			final long[] blockSize = new long[ numDimensions ];

			// compute the current offset
			final long[] offset = new long[ numDimensions ];
			final long[] effectiveOffset = new long[ numDimensions ];
			final long[] effectiveSize = new long[ numDimensions ];
			final long[] effectiveLocalOffset = new long[ numDimensions ];

			for ( int d = 0; d < numDimensions; ++d )
			{
				effectiveLocalOffset[ d ] = kernelSize[ d ] / 2;
				effectiveSize[ d ] = currentBlock[ d ] == numBlocks[ d ] - 1 ? imgSize[ d ] / numBlocks[ d ] : imgSize[ d ] / numBlocks[ d ] + imgSize[ d ] % numBlocks[ d ];
				blockSize[ d ] = effectiveSize[ d ] + kernelSize[ d ] - 1;
				effectiveOffset[ d ] = currentBlock[ d ] * effectiveSize[ d ];
				offset[ d ] = effectiveOffset[ d ] - kernelSize[ d ]/2;

				if ( effectiveSize[ d ] <= 0 )
				{
					System.out.println( "Blocksize in dimension " + d + " (" + blockSize[ d ] + ") is smaller than the kernel (" + kernelSize[ d ] + ") which results in an negative effective size: " + effectiveSize[ d ] + ". Quitting." );
					return null;
				}
			}

			blockList.add( new Block( service, blockSize, offset, effectiveSize, effectiveOffset, effectiveLocalOffset, true ) );
			System.out.println( "block " + Util.printCoordinates( currentBlock ) + " offset: " + Util.printCoordinates( offset ) + " effectiveOffset: " + Util.printCoordinates( effectiveOffset ) + " effectiveLocalOffset: " + Util.printCoordinates( effectiveLocalOffset ) + " effectiveSize: " + Util.printCoordinates( effectiveSize )  + " blocksize: " + Util.printCoordinates( blockSize ) );
		}

		return blockList;
	}

	public static void main( String[] args )
	{
		new BlockGeneratorVariableSizePrecise( new long[]{ 3, 2, 1 } ).divideIntoBlocks( new long[] { 1025, 1024, 117 }, new long[]{ 17, 17, 5 } );
	}
}
