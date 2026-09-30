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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.subsetvector;

import java.util.Arrays;

import net.preibisch.mvrecon.process.pointcloud.ComponentMajor;

/**
 * Vectorized sweep over B's subset vectors, stored component-major so the distance loop runs over contiguous columns.
 * Without a radius the full matrix is swept in cache-sized blocks; with a radius the in-radius owners' columns are
 * gathered into a scratch matrix first.
 */
final class BruteForceSearch implements SubsetSearch
{
	/**
	 * Width, in subset vectors, of the blocks in which {@link BruteForceSearch#searchAll} sweeps B's transposed matrix.
	 * Each block is compared against all subset vectors of the current A descriptor before the next block is touched, so
	 * a block is read from memory once per A descriptor instead of once per A subset vector. 4096 columns of 9 to 15
	 * floats (3 to 5 neighbors) are 147 to 245 KB, sized for a 256 KB L2 cache; the {@code dists} scratch for one block
	 * (16 KB) stays in L1. The use site rounds it down to a multiple of the subset count so a block holds whole owners.
	 */
	private static final int BRUTE_FORCE_BLOCK = 4096;

	private final float[][] vecsA, vecsB;
	private final int numSubsets, numVecsB, vecLen;
	private float[][] transposedB, gatheredB;
	private final float[] dists;
	private final float[] minDistPerOwner;
	private int[] gatheredCols;

	BruteForceSearch( final float[][] vecsA, final float[][] vecsB, final int numSubsets )
	{
		this.vecsA = vecsA;
		this.vecsB = vecsB;
		this.numSubsets = numSubsets;
		this.numVecsB = vecsB.length;
		this.vecLen = vecsB[ 0 ].length;
		this.dists = new float[ numVecsB ];
		this.minDistPerOwner = new float[ numVecsB / numSubsets ];
	}

	@Override
	public void searchAll( final int indexA, final BestMatches out )
	{
		if ( transposedB == null )
			transposedB = ComponentMajor.transpose( vecsB, new float[ vecLen ][ numVecsB ], null, numVecsB );
		final int blockSize = Math.max( numSubsets, BRUTE_FORCE_BLOCK / numSubsets * numSubsets ); // whole owners per block
		Arrays.fill( minDistPerOwner, Float.MAX_VALUE );
		for ( int blockStart = 0; blockStart < numVecsB; blockStart += blockSize )
		{
			final int blockEnd = Math.min( numVecsB, blockStart + blockSize );
			for ( int subset = 0; subset < numSubsets; ++subset )
			{
				ComponentMajor.squaredDistances( vecsA[ indexA * numSubsets + subset ], transposedB, blockStart, blockEnd, dists );
				minPerOwner( dists, blockEnd - blockStart, numSubsets, blockStart / numSubsets, minDistPerOwner );
			}
		}
		for ( int ownerB = 0; ownerB < minDistPerOwner.length; ++ownerB )
			out.fold( indexA, ownerB, minDistPerOwner[ ownerB ] );
	}

	@Override
	public void searchWithin( final int indexA, final int[] ownersB, final int numOwners, final BestMatches out )
	{
		if ( gatheredB == null )
		{
			gatheredB = new float[ vecLen ][ numVecsB ];
			gatheredCols = new int[ numVecsB ];
		}
		for ( int owner = 0; owner < numOwners; ++owner )
			for ( int subset = 0; subset < numSubsets; ++subset )
				gatheredCols[ owner * numSubsets + subset ] = ownersB[ owner ] * numSubsets + subset;
		final int numCols = numOwners * numSubsets;
		ComponentMajor.transpose( vecsB, gatheredB, gatheredCols, numCols );
		for ( int subset = 0; subset < numSubsets; ++subset )
		{
			ComponentMajor.squaredDistances( vecsA[ indexA * numSubsets + subset ], gatheredB, 0, numCols, dists );
			for ( int owner = 0, col = 0; owner < numOwners; ++owner )
				for ( int subB = 0; subB < numSubsets; ++subB, ++col )
					out.fold( indexA, ownersB[ owner ], dists[ col ] );
		}
	}

	/** minDistPerOwner[firstOwner + n] = minimum over the n-th group of numSubsets consecutive distances (its own method: the JIT compiles it much better than inline in the caller's loop) */
	private static void minPerOwner( final float[] dists, final int count, final int numSubsets, final int firstOwner, final float[] minDistPerOwner )
	{
		for ( int col = 0, owner = firstOwner; col < count; ++owner )
		{
			float min = minDistPerOwner[ owner ];
			for ( int subset = 0; subset < numSubsets; ++subset, ++col )
				if ( dists[ col ] < min )
					min = dists[ col ];
			minDistPerOwner[ owner ] = min;
		}
	}
}
