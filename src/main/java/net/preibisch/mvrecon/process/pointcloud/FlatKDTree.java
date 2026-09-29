/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.process.pointcloud;

import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Exact k-nearest-neighbor search over fixed-length float vectors. Nodes are primitive arrays and leaves hold up to
 * {@link #LEAF_SIZE} vectors component-major, so the leaf scan vectorizes. Immutable; use one {@link Query} per thread.
 */
public class FlatKDTree
{
	private static final int LEAF_SIZE = 32;

	private final int numDims;
	private final float[][] coords; // coords[ dimension ][ slot ], slots permuted so each leaf is contiguous
	private final int[] vecIdx;     // original vector index per slot
	private final int[] splitDim, childLeft, childRight, rangeFrom, rangeTo; // per node; childLeft < 0 marks a leaf
	private final float[] splitValue;
	private int numNodes = 0;

	public FlatKDTree( final float[][] vectors )
	{
		final int numVecs = vectors.length;
		if ( numVecs == 0 )
			throw new IllegalArgumentException( "FlatKDTree needs at least one vector" );
		numDims = vectors[ 0 ].length;
		vecIdx = new int[ numVecs ];
		for ( int i = 0; i < numVecs; ++i )
			vecIdx[ i ] = i;

		final int maxNodes = 4 * ( numVecs / LEAF_SIZE + 2 ); // median splits: leaves hold > LEAF_SIZE/2
		splitDim = new int[ maxNodes ];
		childLeft = new int[ maxNodes ];
		childRight = new int[ maxNodes ];
		rangeFrom = new int[ maxNodes ];
		rangeTo = new int[ maxNodes ];
		splitValue = new float[ maxNodes ];
		build( vectors, 0, numVecs );

		coords = new float[ numDims ][ numVecs ];
		for ( int slot = 0; slot < numVecs; ++slot )
			for ( int dim = 0; dim < numDims; ++dim )
				coords[ dim ][ slot ] = vectors[ vecIdx[ slot ] ][ dim ];
	}

	private int build( final float[][] vectors, final int from, final int to )
	{
		final int node = numNodes++;
		rangeFrom[ node ] = from;
		rangeTo[ node ] = to;
		childLeft[ node ] = -1;
		if ( to - from <= LEAF_SIZE )
			return node;

		// split the dimension of largest extent at its median
		final float[] min = new float[ numDims ], max = new float[ numDims ];
		Arrays.fill( min, Float.MAX_VALUE );
		Arrays.fill( max, -Float.MAX_VALUE );
		for ( int slot = from; slot < to; ++slot )
			for ( int dim = 0; dim < numDims; ++dim )
			{
				min[ dim ] = Math.min( min[ dim ], vectors[ vecIdx[ slot ] ][ dim ] );
				max[ dim ] = Math.max( max[ dim ], vectors[ vecIdx[ slot ] ][ dim ] );
			}
		int widestDim = 0;
		for ( int dim = 1; dim < numDims; ++dim )
			if ( max[ dim ] - min[ dim ] > max[ widestDim ] - min[ widestDim ] )
				widestDim = dim;

		final int median = ( from + to ) >>> 1;
		select( vectors, from, to - 1, median, widestDim );
		splitDim[ node ] = widestDim;
		splitValue[ node ] = vectors[ vecIdx[ median ] ][ widestDim ];
		childLeft[ node ] = build( vectors, from, median );
		childRight[ node ] = build( vectors, median, to );
		return node;
	}

	/** Hoare selection on vecIdx[left..right]: afterwards slot k holds the k-th smallest value along the dimension, smaller left, larger right */
	private void select( final float[][] vectors, int left, int right, final int k, final int dim )
	{
		while ( left < right )
		{
			final float pivot = vectors[ vecIdx[ ( left + right ) >>> 1 ] ][ dim ];
			int i = left, j = right;
			while ( i <= j )
			{
				while ( vectors[ vecIdx[ i ] ][ dim ] < pivot )
					++i;
				while ( vectors[ vecIdx[ j ] ][ dim ] > pivot )
					--j;
				if ( i <= j )
				{
					final int swap = vecIdx[ i ];
					vecIdx[ i ] = vecIdx[ j ];
					vecIdx[ j ] = swap;
					++i;
					--j;
				}
			}
			if ( k <= j )
				right = j;
			else if ( k >= i )
				left = i;
			else
				return;
		}
	}

	/** k-nearest query state for one thread; results sorted by squared distance, {@link #size()} may be below k with a filter */
	public final class Query
	{
		private final int k;
		private final float[] sqDists;
		private final int[] foundIdx;
		private final float[] cellOffset = new float[ numDims ];
		private final float[] leafDists = new float[ LEAF_SIZE ];
		private float[] query;
		private IntPredicate accept;

		public Query( final int k )
		{
			this.k = k;
			sqDists = new float[ k ];
			foundIdx = new int[ k ];
		}

		public void search( final float[] query ) { search( query, null ); }

		/** @param acceptIndex optional filter on the original vector index */
		public void search( final float[] query, final IntPredicate acceptIndex )
		{
			this.query = query;
			this.accept = acceptIndex;
			Arrays.fill( sqDists, Float.MAX_VALUE );
			Arrays.fill( foundIdx, -1 );
			Arrays.fill( cellOffset, 0f );
			descend( 0, 0f );
		}

		public int size()
		{
			int found = 0;
			while ( found < k && foundIdx[ found ] >= 0 )
				++found;
			return found;
		}

		public float squareDistance( final int i ) { return sqDists[ i ]; }
		public int index( final int i ) { return foundIdx[ i ]; }

		private void descend( final int node, final float cellDist )
		{
			if ( childLeft[ node ] < 0 )
			{
				scanLeaf( node );
				return;
			}
			final int dim = splitDim[ node ];
			final float diff = query[ dim ] - splitValue[ node ];
			descend( diff < 0 ? childLeft[ node ] : childRight[ node ], cellDist );

			// the far cell lies beyond the split plane in this dimension: its lower bound replaces the dimension's offset
			final float oldOffset = cellOffset[ dim ];
			final float gap = Math.max( Math.abs( diff ), oldOffset );
			final float farDist = cellDist - oldOffset * oldOffset + gap * gap;
			if ( farDist < sqDists[ k - 1 ] )
			{
				cellOffset[ dim ] = gap;
				descend( diff < 0 ? childRight[ node ] : childLeft[ node ], farDist );
				cellOffset[ dim ] = oldOffset;
			}
		}

		private void scanLeaf( final int node )
		{
			final int firstSlot = rangeFrom[ node ];
			final int count = rangeTo[ node ] - firstSlot;
			final float query0 = query[ 0 ];
			final float[] coords0 = coords[ 0 ];
			for ( int j = 0; j < count; ++j )
			{
				final float diff = query0 - coords0[ firstSlot + j ];
				leafDists[ j ] = diff * diff;
			}
			for ( int dim = 1; dim < numDims; ++dim ) // vectorized: one component of all leaf vectors per pass
			{
				final float queryValue = query[ dim ];
				final float[] coordRow = coords[ dim ];
				for ( int j = 0; j < count; ++j )
				{
					final float diff = queryValue - coordRow[ firstSlot + j ];
					leafDists[ j ] += diff * diff;
				}
			}
			for ( int j = 0; j < count; ++j )
			{
				final float sqDist = leafDists[ j ];
				if ( sqDist >= sqDists[ k - 1 ] || ( accept != null && !accept.test( vecIdx[ firstSlot + j ] ) ) )
					continue;
				int insertAt = k - 1;
				for ( ; insertAt > 0 && sqDists[ insertAt - 1 ] > sqDist; --insertAt )
				{
					sqDists[ insertAt ] = sqDists[ insertAt - 1 ];
					foundIdx[ insertAt ] = foundIdx[ insertAt - 1 ];
				}
				sqDists[ insertAt ] = sqDist;
				foundIdx[ insertAt ] = vecIdx[ firstSlot + j ];
			}
		}
	}
}
