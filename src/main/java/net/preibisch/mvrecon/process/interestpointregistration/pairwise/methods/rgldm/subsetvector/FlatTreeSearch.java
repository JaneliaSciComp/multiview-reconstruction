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
import java.util.function.IntPredicate;

import net.preibisch.mvrecon.process.pointcloud.FlatKDTree;

/**
 * {@link FlatKDTree} over B's subset vectors. k = numSubsets + 1 nearest entries suffice: everything closer than the
 * second-best owner's best entry belongs to the best owner, which has numSubsets entries. A radius restriction becomes
 * an index filter: the tree skips entries whose owner was not stamped for the current A descriptor.
 */
final class FlatTreeSearch implements SubsetSearch
{
	private final float[][] vecsA;
	private final int numSubsets;
	private final FlatKDTree tree;
	private final int[] inRadiusStamp; // the A index for which each B owner was last inside the radius
	private int currentA = -1;
	private final IntPredicate ownerInRadius;

	FlatTreeSearch( final float[][] vecsA, final float[][] vecsB, final int numSubsets )
	{
		this.vecsA = vecsA;
		this.numSubsets = numSubsets;
		this.tree = new FlatKDTree( vecsB, numSubsets + 1 );
		this.inRadiusStamp = new int[ vecsB.length / numSubsets ];
		Arrays.fill( inRadiusStamp, -1 );
		this.ownerInRadius = vec -> inRadiusStamp[ vec / numSubsets ] == currentA;
	}

	@Override
	public void searchAll( final int indexA, final BestMatches out )
	{
		for ( int subset = 0; subset < numSubsets; ++subset )
		{
			tree.search( vecsA[ indexA * numSubsets + subset ], null );
			foldFound( indexA, out );
		}
	}

	@Override
	public void searchWithin( final int indexA, final int[] ownersB, final int numOwners, final BestMatches out )
	{
		for ( int i = 0; i < numOwners; ++i )
			inRadiusStamp[ ownersB[ i ] ] = indexA;
		currentA = indexA;
		for ( int subset = 0; subset < numSubsets; ++subset )
		{
			tree.search( vecsA[ indexA * numSubsets + subset ], ownerInRadius );
			foldFound( indexA, out );
		}
	}

	/** folds the entries of the last tree search into their B owners */
	private void foldFound( final int indexA, final BestMatches out )
	{
		final int found = tree.size();
		for ( int i = 0; i < found; ++i )
			out.fold( indexA, tree.index( i ) / numSubsets, tree.squareDistance( i ) );
	}
}
