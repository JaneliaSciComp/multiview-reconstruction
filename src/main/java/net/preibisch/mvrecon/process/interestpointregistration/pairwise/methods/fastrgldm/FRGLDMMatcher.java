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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.fastrgldm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

import net.imglib2.KDTree;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.TranslationInvariantLocalCoordinateSystemPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SubsetMatcher;

public class FRGLDMMatcher< I extends InterestPoint >
{
	public ArrayList< PointMatchGeneric< I > > extractCorrespondenceCandidates( 
			final ArrayList< I > nodeListA,
			final ArrayList< I > nodeListB,
			final int redundancy,
			final double ratioOfDistance )
	{
		final KDTree< I > tree1 = new KDTree<>( nodeListA, nodeListA );
		final KDTree< I > tree2 = new KDTree<>( nodeListB, nodeListB );

		final ArrayList< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > descriptors1 =
			createLocalCoordinateSystemPointDescriptors( tree1, nodeListA, redundancy );
		
		final ArrayList< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > descriptors2 =
			createLocalCoordinateSystemPointDescriptors( tree2, nodeListB, redundancy );
		
		// create lookup tree for descriptors2
		final KDTree< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > lookUpTree2 = new KDTree<>( descriptors2, descriptors2 );
		final KNearestNeighborSearchOnKDTree< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > nnsearch = new KNearestNeighborSearchOnKDTree<>( lookUpTree2, 2 );

		// store the candidates for corresponding beads
		final ArrayList< PointMatchGeneric< I > > correspondences = new ArrayList<>();
		
		/* compute matching */
		computeMatching( descriptors1, nnsearch, correspondences, ratioOfDistance );
		
		return correspondences;
	}
	
	protected void computeMatching(
			final ArrayList< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > descriptors1,
			final KNearestNeighborSearchOnKDTree< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > nnsearch2,
			final ArrayList< PointMatchGeneric< I > > correspondences,
			final double ratioOfDistance )
	{
		final HashSet< Pair< I, I > > pairs = new HashSet<>();

		int count = 0;
		
		for ( final TranslationInvariantLocalCoordinateSystemPointDescriptor< I > descriptorA : descriptors1 )
		{
			nnsearch2.search( descriptorA );

			double best = descriptorA.descriptorDistance( nnsearch2.getSampler( 0 ).get() );
			double secondBest = descriptorA.descriptorDistance( nnsearch2.getSampler( 1 ).get() );

			if ( best * ratioOfDistance <= secondBest )
			{
				final I detectionA = descriptorA.getBasisPoint();
				final I detectionB = nnsearch2.getSampler( 0 ).get().getBasisPoint();

				// twice the same pair could potentially show up due to redundancy
				pairs.add( new ValuePair<>( detectionA, detectionB ) );
				++count;
			}
		}

		for ( final Pair< I, I > pair : pairs )
			correspondences.add( new PointMatchGeneric< I >( pair.getA(), pair.getB(), 1 ) );
		
		//System.out.println( count +  " <> " + correspondences.size() );
	}

	public static < I extends InterestPoint > ArrayList< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > createLocalCoordinateSystemPointDescriptors( 
			final KDTree< I > tree,
			final Collection< I > basisPoints,
			final int redundancy )
	{
		final int[][] neighborIndicies = SubsetMatcher.computePD( 3 + redundancy, 3, 1 );

		final KNearestNeighborSearchOnKDTree< I > nnsearch = new KNearestNeighborSearchOnKDTree<>( tree, 3 + redundancy + 1 );
		final ArrayList< TranslationInvariantLocalCoordinateSystemPointDescriptor< I > > descriptors = new ArrayList<> ( );
		
		for ( final I p : basisPoints )
		{
			nnsearch.search( p );

			for ( final int[] neighbors : neighborIndicies )
			{
				final I point1 = nnsearch.getSampler( neighbors[ 0 ] ).get();
				final I point2 = nnsearch.getSampler( neighbors[ 1 ] ).get();
				final I point3 = nnsearch.getSampler( neighbors[ 2 ] ).get();

				try
				{
					descriptors.add( new TranslationInvariantLocalCoordinateSystemPointDescriptor< I >( p, point1, point2, point3 ) );
				}
				catch ( NoSuitablePointsException e )
				{
					e.printStackTrace();
				}
			}
		}

		return descriptors;
	}

	public static void main( String[] args )
	{
		final int numNeighbors = 2;
		final int redundancy = 1;

		final int[][] neighborIndicies = SubsetMatcher.computePD( numNeighbors + redundancy, numNeighbors, 1 );

		for ( final int[] neighbors : neighborIndicies )
			System.out.println( Util.printCoordinates( neighbors ) );
	}
}
