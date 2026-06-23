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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.LinkedPoint;

/**
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class RANSAC
{
	public static < I extends InterestPoint > Pair< String, Double > computeRANSAC(
			final ArrayList< PointMatchGeneric < I > > correspondenceCandidates,
			final ArrayList< PointMatchGeneric < I > > inlierList,
			final ArrayList< Integer > setIdList,
			final Model<?> model,
			final double maxEpsilon,
			final double minInlierRatio,
			final int minNumMatches,
			final int numIterations,
			final boolean multiConsensus,
			final double maxTrust,
			final boolean filterRansac )
	{
		final int numCorrespondences = correspondenceCandidates.size();
		final int minNumCorrespondences = Math.max( model.getMinNumMatches(), minNumMatches );

		if ( minNumMatches < model.getMinNumMatches() )
			IOFunctions.println( "WARNING: Too few minNumMatches specified " + minNumMatches + ", model requires at least " + model.getMinNumMatches() + ". Adjusting to " + model.getMinNumMatches() );

		// if there are not enough correspondences for the used model
		if ( numCorrespondences < minNumCorrespondences )
			return new ValuePair< String, Double >( "Not enough correspondences found " + numCorrespondences + ", should be at least " + minNumCorrespondences, Double.NaN );

		/**
		 * The ArrayList that stores the inliers after RANSAC, contains PointMatches of LinkedPoints
		 * so that MultiThreading is possible
		 */
		List< PointMatch > candidates = new ArrayList<>();
		List< PointMatch > inliers = new ArrayList<>();
		
		// clone the beads for the RANSAC as we are working multithreaded and they will be modified
		for ( final PointMatchGeneric< I > correspondence : correspondenceCandidates )
		{
			final I detectionA = correspondence.getPoint1();
			final I detectionB = correspondence.getPoint2();

			// the LinkedPoint always clones the location array
			final LinkedPoint< I > pA = new LinkedPoint< I >( detectionA.getL(), detectionA.getW(), detectionA );
			final LinkedPoint< I > pB = new LinkedPoint< I >( detectionB.getL(), detectionB.getW(), detectionB );
			final double weight = correspondence.getWeight(); 

			candidates.add( new PointMatchGeneric< LinkedPoint< I > >( pA, pB, weight ) );
		}

		final NumberFormat nf = NumberFormat.getPercentInstance();
		final NumberFormat nfErrors = NumberFormat.getNumberInstance();
		nfErrors.setMinimumFractionDigits( 2 );
		nfErrors.setMaximumFractionDigits( 2 );

		boolean modelFound = false;

		if ( !multiConsensus )
		{
			try
			{
				modelFound = runRANSAC( model, candidates, inliers, numIterations, maxEpsilon, minInlierRatio, maxTrust, filterRansac );
			}
			catch ( NotEnoughDataPointsException e )
			{
				return new ValuePair< String, Double >( e.toString(), Double.NaN );
			}

			final double ratio = ( (double)inliers.size() / (double)candidates.size() );
			
			if ( modelFound && inliers.size() >= minNumCorrespondences )
			{
				// remove the inconsistent inliers
				final int numCorr = inliers.size();
				inliers = removeInconsistentMatches( inliers );

				if ( inliers.size() < minNumCorrespondences )
				{
					final int numRemoved = numCorr - inliers.size();

					// and try again with cleaned correspondences
					candidates = removeInconsistentMatches( candidates );
					inliers.clear();

					try
					{
						modelFound = runRANSAC( model, candidates, inliers, numIterations, maxEpsilon, minInlierRatio, maxTrust, filterRansac );
					}
					catch ( NotEnoughDataPointsException e )
					{
						return new ValuePair< String, Double >( e.toString(), Double.NaN );
					}

					if ( !modelFound || inliers.size() < minNumCorrespondences )
						return new ValuePair< String, Double >( "NO Model found after removing " + numRemoved + " inconsistent matches and re-running RANSAC using " + candidates.size(), Double.NaN );
				}

				for ( final PointMatch pointMatch : inliers )
				{
					@SuppressWarnings("unchecked")
					final PointMatchGeneric<LinkedPoint< I > > pm = (PointMatchGeneric< LinkedPoint< I > >) pointMatch;

					final I detectionA = pm.getPoint1().getLinkedObject();
					final I detectionB = pm.getPoint2().getLinkedObject();

					inlierList.add( new PointMatchGeneric< I >( detectionA, detectionB ) );
					setIdList.add( -1 );  // Single-consensus mode always uses -1
				}

				String inconsistent = "";
				if ( numCorr != inliers.size() )
					inconsistent = " [removed " + (numCorr - inliers.size() ) + " inconsistent inliers]";

				return new ValuePair< String, Double >( "Remaining inliers after RANSAC: " + inliers.size() + " of " + candidates.size() + " (" + nf.format(ratio) + ") with average error " + nfErrors.format( model.getCost() ) + "" + inconsistent, model.getCost() );
			}
			else
			{
				if ( modelFound )
					return new ValuePair< String, Double >( "Model found but not enough remaining inliers (" + inliers.size() + "/" + minNumCorrespondences + ") after RANSAC of " + candidates.size(), Double.NaN );
				else
					return new ValuePair< String, Double >( "NO Model found after RANSAC of " + candidates.size(), Double.NaN );
			}
		}
		else
		{
			// multi-consensus
			candidates = removeInconsistentMatches( candidates );

			final int sizeCandidatea = candidates.size();

			final ArrayList< ArrayList< PointMatch > > inlierSets = new ArrayList<>();
			final ArrayList< Double > errors = new ArrayList<>();

			String lastMessage = "";
			int j = 0;

			do
			{
				inliers.clear();

				try
				{
					// TODO: the inlier-ratio requests a smaller and smaller set of inliers as the candidate set size decreases
					modelFound = runRANSAC( model, candidates, inliers, numIterations, maxEpsilon, minInlierRatio, maxTrust, filterRansac );

					if ( modelFound && inliers.size() >= minNumCorrespondences )
					{
						// debug output for now
						System.out.println( j++ + ": found " + inliers.size() + "/" + candidates.size() + " inliers with model: " + model + ", error " + model.getCost() );

						inlierSets.add( new ArrayList<>( inliers ) );
						errors.add( model.getCost() );

						candidates = removeInliers( candidates, inliers );
					}
					else if ( modelFound )
					{
						lastMessage = "Model found, but not enough remaining inliers (" + inliers.size() + "/" + minNumCorrespondences + ") after RANSAC of " + candidates.size();
					}
					else
					{
						lastMessage = "NO model found after RANSAC of " + candidates.size();
					}
				}
				catch ( NotEnoughDataPointsException e )
				{
					lastMessage = "Not enough points for matching: " + candidates.size();
				}
			} while ( modelFound && inliers.size() >= minNumCorrespondences && candidates.size() >= minNumCorrespondences );

			if ( inlierSets.size() > 0 )
			{
				double minError = Double.MAX_VALUE;
				double maxError = -1.0;
				double avgError = 0.0;
				long sumInliers = 0;
				String setSizes = "";

				for ( int i = 0; i < inlierSets.size(); ++i )
				{
					final List< PointMatch > inlierSet = inlierSets.get( i );
					final double error = errors.get( i );

					sumInliers += inlierSet.size();
					avgError += inlierSet.size() * error;
					minError = Math.min( minError, error );
					maxError = Math.max( maxError, error );
					setSizes += inlierSet.size() + ",";

					for ( final PointMatch pointMatch : inlierSet )
					{
						@SuppressWarnings("unchecked")
						final PointMatchGeneric<LinkedPoint< I > > pm = (PointMatchGeneric< LinkedPoint< I > >) pointMatch;

						final I detectionA = pm.getPoint1().getLinkedObject();
						final I detectionB = pm.getPoint2().getLinkedObject();

						inlierList.add( new PointMatchGeneric< I >( detectionA, detectionB ) );
						setIdList.add( i );  // Track which consensus set this inlier belongs to
					}
				}

				avgError /= (double)sumInliers;
				setSizes = setSizes.substring( 0, setSizes.length() - 1);

				return new ValuePair< String, Double >(
						"Found " + inlierSets.size() + " set(s) containing " + sumInliers + "/" + sizeCandidatea + " inliers (" + 
						nf.format(sumInliers/(double)sizeCandidatea) + ") with min/avg/max set-error ("+nfErrors.format(minError)+"/"+
						nfErrors.format(avgError)+"/"+nfErrors.format(maxError)+") and set sizes (" + setSizes + ")." ,
						Double.NaN );
			}
			else
			{
				return new ValuePair< String, Double >( lastMessage, Double.NaN );
			}
		}

	}

	private static boolean runRANSAC(
			final Model<?> model,
			final List< PointMatch > candidates,
			final List< PointMatch > inliers,
			final int numIterations,
			final double maxEpsilon,
			final double minInlierRatio,
			final double maxTrust,
			final boolean filterRansac ) throws NotEnoughDataPointsException
	{
		return filterRansac
				? model.filterRansac( candidates, inliers, numIterations, maxEpsilon, minInlierRatio, maxTrust )
				: model.ransac( candidates, inliers, numIterations, maxEpsilon, minInlierRatio );
	}

	public static < P extends PointMatch > List< P > removeInliers( final List< P > candidates, final List< P > matches )
	{
		final HashSet< P > matchesSet = new HashSet<>( matches );

		return candidates.stream().filter( c -> !matchesSet.contains( c ) ).collect( Collectors.toList() );
	}

	/**
	 * a class that computes hash and equals only using the coordinates of a double[] array
	 */
	private static class HashableDoubleArray
	{
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + Arrays.hashCode(l);
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			HashableDoubleArray other = (HashableDoubleArray) obj;
			return Arrays.equals(l, other.l);
		}

		final double[] l;

		public HashableDoubleArray( final double[] l ) { this.l = l; }
	}

	public static < P extends PointMatch > ArrayList< P > removeInconsistentMatches( final List< P > matches )
	{
		final HashMap< HashableDoubleArray, ArrayList< Integer > > p1 = new HashMap<>();
		final HashMap< HashableDoubleArray, ArrayList< Integer > > p2 = new HashMap<>();

		for ( int i = 0; i < matches.size(); ++i )
		{
			final P pm = matches.get( i );

			// only the underlying detections are the same objects
			final HashableDoubleArray detectionA = new HashableDoubleArray( pm.getP1().getL() );
			final HashableDoubleArray detectionB = new HashableDoubleArray( pm.getP2().getL() );

			//System.out.println( Arrays.toString( detectionA.l ) + " " + Arrays.toString( detectionB.l ) + " " + detectionA.hashCode() + " = " + detectionB.hashCode() );

			if ( p1.containsKey( detectionA ) )
			{
				p1.get( detectionA ).add( i );
			}
			else
			{
				final ArrayList<Integer> list = new ArrayList<>();
				list.add( i );
				p1.put( detectionA, list );
			}

			if ( p2.containsKey( detectionB ) )
			{
				p2.get( detectionB ).add( i );
			}
			else
			{
				final ArrayList<Integer> list = new ArrayList<>();
				list.add( i );
				p2.put( detectionB, list );
			}
		}

		// build a HashSet of all indicies that collide
		final HashSet< Integer > toRemove = new HashSet<>();

		for ( final Entry< HashableDoubleArray, ArrayList< Integer > > entry : p1.entrySet() )
			if (entry.getValue().size() > 1 )
				toRemove.addAll( entry.getValue() );

		for ( final Entry< HashableDoubleArray, ArrayList< Integer > > entry : p2.entrySet() )
			if (entry.getValue().size() > 1 )
				toRemove.addAll( entry.getValue() );

		//System.out.println( "Removing " + toRemove.size() + " matches." );

		final ArrayList< P > newList = new ArrayList<>();
		for ( int i = 0; i < matches.size(); ++i )
		{
			if ( !toRemove.contains( i ) )
				newList.add( matches.get( i ) );
		}

		return newList;
	}
}
