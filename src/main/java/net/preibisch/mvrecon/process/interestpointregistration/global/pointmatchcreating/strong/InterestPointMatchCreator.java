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
package net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class InterestPointMatchCreator implements PointMatchCreator
{
	final List< ? extends Pair< ? extends Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > > pairs;
	final Map< ViewId, ? extends Map< String, Double > > labelMap;

	public InterestPointMatchCreator(
			final List< ? extends Pair< ? extends Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > > pairs,
			final Map< ViewId, ? extends Map< String, Double > > labelMap )
	{
		this.pairs = pairs;
		this.labelMap = labelMap;
	}

	@Override
	public HashSet< ViewId > getAllViews()
	{
		final HashSet< ViewId > tmpSet = new HashSet<>();

		for ( Pair< ? extends Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > pair : pairs )
		{
			tmpSet.add( pair.getA().getA() );
			tmpSet.add( pair.getA().getB() );
		}

		return tmpSet;
	}

	@Override
	public < M extends Model< M > > void assignPointMatches(
			final HashMap< ViewId, Tile< M > > tileMap,
			final ArrayList< Group< ViewId > > groups,
			final Collection< ViewId > fixedViews )
	{
		for ( Pair< ? extends Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > pair : pairs )
		{
			final Tile< ? > tileA = tileMap.get( pair.getA().getA() );
			final Tile< ? > tileB = tileMap.get( pair.getA().getB() );
			final List< ? extends PointMatchGeneric< ? > > correspondences = pair.getB().getInliers();

			if ( correspondences.size() > 0 )
			{
				// assign user-weights
				final RealSum r = new RealSum();

				for ( final PointMatchGeneric< ? > pmg : correspondences )
				{
					// TODO: it does not seem to assign the second label (?)
					final Map<String, Double> a = labelMap.get( pair.getA().getA() );
					final Map<String, Double> b = labelMap.get( pair.getA().getB() );

					final double wA = a.get( pair.getB().getLabelA() );
					final double wB = b.get( pair.getB().getLabelB() );

					final double wF = (wA + wB ) / 2;

					pmg.setWeight( 0, wF );
					r.add( wF );
				}

				final ArrayList< PointMatch > pm = new ArrayList<>( correspondences );
				final Collection< PointMatch > flippedMatches = PointMatch.flip( pm );

				tileA.addMatches( pm );
				tileB.addMatches( flippedMatches ); // Careful: weights are cloned, points not
				tileA.addConnectedTile( tileB );
				tileB.addConnectedTile( tileA );

				// therefore we need to remember them for assignWeights()
				pair.getB().setFlippedMatches( flippedMatches );

				IOFunctions.println(
						"Connecting " + Group.pvid( pair.getA().getA() ) + " (" + pair.getB().getLabelA() + ") <-> " +
						Group.pvid( pair.getA().getB() ) + " (" + pair.getB().getLabelB()+ "): " + pair.getB().getInliers().size() + " matches, |w|=" + (r.getSum()/correspondences.size()) );
			}
		}
	}

	@Override
	public < M extends Model< M > > void assignWeights(
			final HashMap< ViewId, Tile< M > > tileMap,
			final ArrayList< Group< ViewId > > groups,
			final Collection< ViewId > fixedViews )
	{
		assignWeights( pairs, groups, tileMap );
	}

	/*
	public static void addPointMatchesAndUserWeights(
			final List< ? extends PointMatchGeneric< ? > > correspondences,
			final Tile< ? > tileA,
			final Tile< ? > tileB,
			final Map< ViewId, HashMap< String, Double > > labelMap )
	{
		final ArrayList< PointMatch > pm = new ArrayList<>();
		pm.addAll( correspondences );

		if ( correspondences.size() > 0 )
		{
			// assign user-weights

			for ( final PointMatchGeneric< ? > pmg : correspondences )
			{
				pmg.getP1().g
				// TODO: it does not seem to assign the second label
				final HashMap<String, Double> a = labelMap.get( pair.getA().getA() );
				final HashMap<String, Double> b = labelMap.get( pair.getA().getB() );
	
				final double wA = a.get( pair.getB().getLabelA() );
				final double wB = b.get( pair.getB().getLabelB() );
				final double wF = (wA + wB ) / 2;
			}

			tileA.addMatches( pm );
			tileB.addMatches( PointMatch.flip( pm ) ); // weights are cloned, points not
			tileA.addConnectedTile( tileB );
			tileB.addConnectedTile( tileA );
		}
	}
	*/

	public static < M extends Model< M > >  void assignWeights(
			final List< ? extends Pair< ? extends Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > > pairs,
			final ArrayList< Group< ViewId > > groups,
			final HashMap< ViewId, Tile< M > > tileMap )
	{
		final HashMap< Group< ViewId >, Integer > groupCount = new HashMap<>();
		final HashMap< ViewId, Integer > viewCount = new HashMap<>();
		final HashMap< ViewId, Group< ViewId > > viewGroupAssign = new HashMap<>();

		for ( final Group< ViewId > group : groups )
			groupCount.put( group, 0 );

		for ( final ViewId viewId : tileMap.keySet() )
			viewCount.put( viewId, 0 );

		// find out inliers per view, and sum of inliers per group
		for ( Pair< ? extends Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > pair : pairs )
		{
			final ViewId vA = pair.getA().getA();
			final ViewId vB = pair.getA().getB();

			final int numInliers = pair.getB().getInliers().size();

			viewCount.put( vA, viewCount.get( vA ) + numInliers );
			viewCount.put( vB, viewCount.get( vB ) + numInliers );

			for ( final Group< ViewId > group : groups )
			{
				if ( group.contains( vA ) )
				{
					groupCount.put( group, groupCount.get( group ) + numInliers );
					viewGroupAssign.put( vA, group );
				}

				if ( group.contains( vB ) )
				{
					groupCount.put( group, groupCount.get( group ) + numInliers );
					viewGroupAssign.put( vB, group );
				}
			}
		}

		final HashMap< ViewId, Double > ratio = new HashMap<>();
		final HashMap< Group< ViewId >, Double > maxGroupRatio = new HashMap<>();

		// find the ratio (inliers/groupInliers) per view, or 1.0 if it is not part of a group (i.e. one view per group)
		for ( final ViewId viewId : tileMap.keySet() )
		{
			final Group< ViewId > group = viewGroupAssign.get( viewId );

			if ( group != null )
			{
				final double numCorr = viewCount.get( viewId );
				final double numCorrGroup = groupCount.get( group );
				ratio.put( viewId, numCorr / numCorrGroup );

				double maxRatio;

				if ( maxGroupRatio.containsKey( group ) )
					maxRatio = maxGroupRatio.get( group );
				else
					maxRatio = -1;

				maxGroupRatio.put( group, Math.max( maxRatio, numCorr / numCorrGroup ) );
			}
			else
			{
				ratio.put( viewId, 1.0 );
			}
		}

		final ArrayList< ViewId > views = new ArrayList<>();
		views.addAll( tileMap.keySet() );
		Collections.sort( views );

		// assign a value that reflects how much lower the ratio is per view
		// e.g. view0 = 0.5, view1 = 0.25
		// means view0 = 0.5 and view1 = 2
		for ( final ViewId viewId : views )
		{
			final Group< ViewId > group = viewGroupAssign.get( viewId );
			double maxRatio = 1;
			if ( group != null )
				maxRatio = maxGroupRatio.get( group );

			System.out.println( Group.pvid( viewId ) + ": " + ratio.get( viewId ) + " of "  + maxRatio + " >>> " + ( maxRatio/ratio.get( viewId ) ) );

			ratio.put( viewId, ( maxRatio/ratio.get( viewId ) ) );
		}

		// assign the max of each value to the pointmatches between two views
		for ( final Pair< ? extends Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > pair : pairs )
		{
			// weights from grouping
			final double ratioA = ratio.get( pair.getA().getA() );
			final double ratioB = ratio.get( pair.getA().getB() );

			final double weight = Math.max( ratioA, ratioB );

			// nothing to do if weight is 1
			if ( weight == 1.0 )
				continue;

			final RealSum r = new RealSum();

			// this does not apply to the flipped pointmatches
			for ( final PointMatchGeneric< ? > pm : pair.getB().getInliers() )
			{
				final double w = pm.getWeight();
				final double v = weight * ( w > 0 ? w : 1 );
				pm.setWeight( 0, v );

				r.add( v );
			}

			// flipped pointmatches
			for ( final PointMatch pm : pair.getB().getFlippedMatches() )
			{
				final double w = pm.getWeight();
				pm.setWeight( 0, weight * ( w > 0 ? w : 1 ) );
			}

			if ( pair.getB().getInliers().size() > 0 )
			{
				System.out.println(
					Group.pvid( pair.getA().getA() ) + " (" + pair.getB().getLabelA() + ") <-> " + 
					Group.pvid( pair.getA().getB() ) + " (" + pair.getB().getLabelB() + "): avg Weight=" +
					r.getSum() / pair.getB().getInliers().size() + " (updated with w=" + weight+ ")" );
			}
			else
			{
				System.out.println(
						Group.pvid( pair.getA().getA() ) + " (" + pair.getB().getLabelA() + ") <-> " + 
						Group.pvid( pair.getA().getB() ) + " (" + pair.getB().getLabelB() + "): No matches, no weights updated." );
			}
		}
	}
}
