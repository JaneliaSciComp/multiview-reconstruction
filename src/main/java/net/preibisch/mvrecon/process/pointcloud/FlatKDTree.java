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
 * Exact k-nearest-neighbor search over fixed-length float vectors. Nodes are primitive arrays, leaves hold up to
 * {@link #LEAF_SIZE} vectors component-major so the leaf scan vectorizes; when pruning fails (high dimension) it degrades
 * into a fast linear scan rather than pointer chasing. Immutable; use one {@link Query} per thread.
 */
public class FlatKDTree
{
	private static final int LEAF_SIZE = 32;

	private final int dim;
	private final float[][] coords; // coords[ d ][ slot ], slots permuted so each leaf is contiguous
	private final int[] index;      // original vector index per slot
	private final int[] splitDim, left, right, from, to; // per node; left < 0 marks a leaf
	private final float[] splitValue;
	private int numNodes = 0;

	public FlatKDTree( final float[][] vectors )
	{
		final int n = vectors.length;
		if ( n == 0 )
			throw new IllegalArgumentException( "FlatKDTree needs at least one vector" );
		dim = vectors[ 0 ].length;
		index = new int[ n ];
		for ( int i = 0; i < n; ++i )
			index[ i ] = i;

		final int maxNodes = 4 * ( n / LEAF_SIZE + 2 ); // median splits: leaves hold > LEAF_SIZE/2
		splitDim = new int[ maxNodes ];
		left = new int[ maxNodes ];
		right = new int[ maxNodes ];
		from = new int[ maxNodes ];
		to = new int[ maxNodes ];
		splitValue = new float[ maxNodes ];
		build( vectors, 0, n );

		coords = new float[ dim ][ n ];
		for ( int slot = 0; slot < n; ++slot )
			for ( int d = 0; d < dim; ++d )
				coords[ d ][ slot ] = vectors[ index[ slot ] ][ d ];
	}

	private int build( final float[][] v, final int lo, final int hi )
	{
		final int node = numNodes++;
		from[ node ] = lo;
		to[ node ] = hi;
		left[ node ] = -1;
		if ( hi - lo <= LEAF_SIZE )
			return node;

		// split the dimension of largest extent at its median
		final float[] min = new float[ dim ], max = new float[ dim ];
		Arrays.fill( min, Float.MAX_VALUE );
		Arrays.fill( max, -Float.MAX_VALUE );
		for ( int i = lo; i < hi; ++i )
			for ( int d = 0; d < dim; ++d )
			{
				min[ d ] = Math.min( min[ d ], v[ index[ i ] ][ d ] );
				max[ d ] = Math.max( max[ d ], v[ index[ i ] ][ d ] );
			}
		int sd = 0;
		for ( int d = 1; d < dim; ++d )
			if ( max[ d ] - min[ d ] > max[ sd ] - min[ sd ] )
				sd = d;

		final int mid = ( lo + hi ) >>> 1;
		select( v, lo, hi - 1, mid, sd );
		splitDim[ node ] = sd;
		splitValue[ node ] = v[ index[ mid ] ][ sd ];
		left[ node ] = build( v, lo, mid );
		right[ node ] = build( v, mid, hi );
		return node;
	}

	/** Hoare selection on index[l..r]: afterwards index[k] is the k-th smallest along d, smaller left, larger right */
	private void select( final float[][] v, int l, int r, final int k, final int d )
	{
		while ( l < r )
		{
			final float pivot = v[ index[ ( l + r ) >>> 1 ] ][ d ];
			int i = l, j = r;
			while ( i <= j )
			{
				while ( v[ index[ i ] ][ d ] < pivot ) ++i;
				while ( v[ index[ j ] ][ d ] > pivot ) --j;
				if ( i <= j )
				{
					final int t = index[ i ]; index[ i ] = index[ j ]; index[ j ] = t;
					++i; --j;
				}
			}
			if ( k <= j ) r = j;
			else if ( k >= i ) l = i;
			else return;
		}
	}

	/** k-nearest query state for one thread; results sorted by squared distance, {@link #size()} may be below k with a filter */
	public final class Query
	{
		private final int k;
		private final float[] dist, offset = new float[ dim ], buf = new float[ LEAF_SIZE ];
		private final int[] found;
		private float[] q;
		private IntPredicate accept;

		public Query( final int k )
		{
			this.k = k;
			dist = new float[ k ];
			found = new int[ k ];
		}

		public void search( final float[] query ) { search( query, null ); }

		/** @param acceptIndex optional filter on the original vector index */
		public void search( final float[] query, final IntPredicate acceptIndex )
		{
			q = query;
			accept = acceptIndex;
			Arrays.fill( dist, Float.MAX_VALUE );
			Arrays.fill( found, -1 );
			Arrays.fill( offset, 0f );
			descend( 0, 0f );
		}

		public int size()
		{
			int n = 0;
			while ( n < k && found[ n ] >= 0 ) ++n;
			return n;
		}

		public float squareDistance( final int i ) { return dist[ i ]; }
		public int index( final int i ) { return found[ i ]; }

		private void descend( final int node, final float cellDistance )
		{
			if ( left[ node ] < 0 )
			{
				scanLeaf( node );
				return;
			}
			final int d = splitDim[ node ];
			final float diff = q[ d ] - splitValue[ node ];
			descend( diff < 0 ? left[ node ] : right[ node ], cellDistance );

			// the far cell lies beyond the split plane in d: its lower bound replaces this dimension's offset
			final float old = offset[ d ], gap = Math.max( Math.abs( diff ), old );
			final float farDistance = cellDistance - old * old + gap * gap;
			if ( farDistance < dist[ k - 1 ] )
			{
				offset[ d ] = gap;
				descend( diff < 0 ? right[ node ] : left[ node ], farDistance );
				offset[ d ] = old;
			}
		}

		private void scanLeaf( final int node )
		{
			final int lo = from[ node ], n = to[ node ] - lo;
			final float q0 = q[ 0 ];
			final float[] c0 = coords[ 0 ];
			for ( int j = 0; j < n; ++j ) { final float x = q0 - c0[ lo + j ]; buf[ j ] = x * x; }
			for ( int d = 1; d < dim; ++d ) // vectorized: one component of all leaf vectors per pass
			{
				final float qd = q[ d ];
				final float[] cd = coords[ d ];
				for ( int j = 0; j < n; ++j ) { final float x = qd - cd[ lo + j ]; buf[ j ] += x * x; }
			}
			for ( int j = 0; j < n; ++j )
			{
				final float dd = buf[ j ];
				if ( dd >= dist[ k - 1 ] || ( accept != null && !accept.test( index[ lo + j ] ) ) )
					continue;
				int t = k - 1;
				for ( ; t > 0 && dist[ t - 1 ] > dd; --t )
				{
					dist[ t ] = dist[ t - 1 ];
					found[ t ] = found[ t - 1 ];
				}
				dist[ t ] = dd;
				found[ t ] = index[ lo + j ];
			}
		}
	}
}
