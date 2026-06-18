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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import net.imglib2.Interval;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;

/**
 * We need to sort blocks in a way so consecutive ones are not influenced by their predecessors
 *
 * @author spreibi
 *
 */
public class BlockSorter
{
	/**
	 * Here are some assumptions:
	 * - the first block is in the top/left/upper... corner
	 * - the effective size of this block was used to compute the number of blocks
	 * - all blocks have the same dimensions, tightly stacked next to each other
	 * - only the effective blocksize of the last blocks in each dimension can be smaller
	 * 
	 * @param blocks - a list of blocks according to the assumptions above
	 * @param psi - the size of the underlying, blocked image
	 * @param minRequiredBlocks - the minimum number of blocks in a batch to iterate effectively (if 1, memory usage will be maximal)
	 * @return - a list of block-lists. The N'th one can be written back once the N+1's is processed
	 */
	public static List< List< Block > > sortBlocksBySmallestFootprint( final List< Block > blocks, final Interval psi, final int minRequiredBlocks )
	{
		final int n = psi.numDimensions();

		final long[] effectiveBlockSize = blocks.get( 0 ).getEffectiveSize();
		final int[] numBlocks = new int[ n ];

		for ( int d = 0; d < n; ++d )
		{
			final long dim = psi.dimension( d );
			numBlocks[ d ] = (int)( dim / blocks.get( 0 ).getEffectiveSize()[ d ] );

			// if the modulo is not 0 we need one more that is only partially useful
			if ( dim % blocks.get( 0 ).getEffectiveSize()[ d ] != 0 )
				++numBlocks[ d ];
		}

		System.out.println( "(" + new Date(System.currentTimeMillis()) + "): Number of blocks in each dimension: " + Util.printCoordinates( numBlocks ) );

		final HashMap< Long, Integer > numBlocksDimToDim = new HashMap<>();
		final ArrayList< Long > numBlocksDim = new ArrayList<>();

		for ( int d = 0; d < n; ++d )
		{
			long size = 1;

			for ( int e = 0; e < n; ++e )
				if ( e != d )
					size *= numBlocks[ e ];

			numBlocksDim.add( size );
			numBlocksDimToDim.put( size, d );
		}

		// we look for the smallest dimension in which the number of blocks it at least "minRequiredBlocks"
		Collections.sort( numBlocksDim );

		int minDim = -1;
		long minDimSize = Long.MAX_VALUE;

		for ( int i = 0; i < n && minDim == -1; ++i )
			if ( numBlocksDim.get( i ) >= minRequiredBlocks || ( i == n-1 && minDim == -1 ) )
			{
				// if this dimension fullfills the requirement or is the last (and biggest one) and nothing before did
				minDimSize = numBlocksDim.get( i );
				minDim = numBlocksDimToDim.get( minDimSize );
			}

		System.out.println( "(" + new Date(System.currentTimeMillis()) + "): Min memory (" + minDimSize + " blocks) progressing in dim=" + minDim + ", assuming a min #blocks=" + minRequiredBlocks );

		final List< List< Block > > noninterferingBlocks = new ArrayList<>();

		final long[] minOffset = new long[ n ];
		blocks.get( 0 ).min( minOffset );

		int sum = 0;

		// go layer by layer
		for ( int i = 0; i < numBlocks[ minDim ]; ++i )
		{
			final ArrayList< Block > newBlocks = new ArrayList<>();
			long offset = minOffset[ minDim ] + i * effectiveBlockSize[ minDim ];

			for ( final Block block : blocks )
			{
				if ( block.min( minDim ) == offset )
				{
					newBlocks.add( block );
					++sum;
					
				}
			}

			noninterferingBlocks.add( newBlocks );

			for ( final Block block : newBlocks )
				System.out.println( Util.printInterval( block ) );
			System.out.println();
		}

		if ( sum != blocks.size() )
		{
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): ERROR, could not sort blocks, something is wrong, must keep them all. This is not good." );
			noninterferingBlocks.clear();
			noninterferingBlocks.add( blocks );
		}

		return noninterferingBlocks;
	}
}
