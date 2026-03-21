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
package net.preibisch.mvrecon.headless.registration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import mpicbg.models.AffineModel3D;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.headless.interestpointdetection.TestSegmentation;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOpt;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.InterestPointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwiseTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.AllToAll;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.PairwiseSetup;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.Subset;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.InterestPointGrouping;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.InterestPointGroupingAll;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.geometrichashing.GeometricHashingPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.geometrichashing.GeometricHashingParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;

public class TestRegistration
{
	public static void main( String[] args )
	{
		// generate 4 views with 1000 corresponding beads, single timepoint
		SpimData2 spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[]{ 0, 90, 135 } ) );

		System.out.println( "Views present:" );

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
			System.out.println( Group.pvid( viewId ) );

		testRegistration( spimData, false );
	}

	public static void testRegistration( final SpimData2 spimData, final boolean grouped )
	{
		// run DoG
		TestSegmentation.testDoG( spimData );

		// select views to process
		final List< ViewId > viewIds = new ArrayList< ViewId >();
		viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		// filter not present ViewIds
		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		//
		// get interest point lists for "beads" and store a map ViewId >> InterestPointLabel
		//
		final String label = "beads"; // this could be different for each ViewId

		/*
		final Map< ViewId, String > labelMap = new HashMap<>();

		for ( final ViewId viewId : viewIds )
			labelMap.put( viewId, label );
		*/

		final Map< ViewId, HashMap< String, Double > > labelMap = new HashMap<>();

		for ( final ViewId viewId : viewIds )
		{
			final HashMap< String, Double > map = new HashMap<>();
			map.put( label, 1.0 );
			labelMap.put( viewId, map );
		}

		// load & transform all interest points
		final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints =
				TransformationTools.getAllTransformedInterestPoints(
					viewIds,
					spimData.getViewRegistrations().getViewRegistrations(),
					spimData.getViewInterestPoints().getViewInterestPoints(),
					labelMap );

		// first we need to know the groups
		final Set< Group< ViewId > > groups = new HashSet<>();

		// only keep those interestpoints that currently overlap with a view to register against
		for ( final Entry< ViewId, HashMap< String, Collection< InterestPoint > > > element: interestpoints.entrySet() )
			for ( final Entry< String, Collection< InterestPoint > > subelement: element.getValue().entrySet() )
				System.out.println( element.getKey() + ", " + subelement.getKey() + ": " + subelement.getValue().size() );

		TransformationTools.filterForOverlappingInterestPoints( interestpoints, groups, spimData.getViewRegistrations().getViewRegistrations(), spimData.getSequenceDescription().getViewDescriptions() );

		for ( final Entry< ViewId, HashMap< String, Collection< InterestPoint > > > element: interestpoints.entrySet() )
			for ( final Entry< String, Collection< InterestPoint > > subelement: element.getValue().entrySet() )
			System.out.println( element.getKey() + ", " + subelement.getKey() + ": " + subelement.getValue().size() );

		// setup pairwise registration
		final PairwiseSetup< ViewId > setup = new AllToAll<>( viewIds, groups );

		System.out.println( "Defined pairs, removed " + setup.definePairs().size() + " redundant view pairs." );
		System.out.println( "Removed " + setup.removeNonOverlappingPairs( new SimpleBoundingBoxOverlap<>( spimData ) ).size() + " pairs because they do not overlap." );
		setup.reorderPairs();
		setup.detectSubsets();
		setup.sortSubsets();
		final ArrayList< Subset< ViewId > > subsets = setup.getSubsets();
		System.out.println( "Identified " + subsets.size() + " subsets " );

		for ( final Subset< ViewId > subset : subsets )
		{
			// parameters
			final RANSACParameters rp = new RANSACParameters();
			final GeometricHashingParameters gp = new GeometricHashingParameters( new AffineModel3D() );

			// fix view(s)
			final List< ViewId > fixedViews = setup.getDefaultFixedViews();
			final ViewId fixedView = subset.getViews().iterator().next();
			fixedViews.add( fixedView );
			System.out.println( "Removed " + subset.fixViews( fixedViews ).size() + " views due to fixing view tpId=" + fixedView.getTimePointId() + " setupId=" + fixedView.getViewSetupId() );

			final HashMap< ViewId, Tile< AffineModel3D > > models;

			if ( grouped )
				models = groupedSubsetTest( spimData, subset, interestpoints, labelMap, rp, gp, fixedViews, false );
			else
				models = pairSubsetTest( spimData, subset, interestpoints, labelMap, rp, gp, fixedViews, false );

			final ViewId firstView = subset.getViews().iterator().next();
			final AffineTransform3D mapBack = TransformationTools.computeMapBackModel(
					spimData.getSequenceDescription().getViewDescription( firstView ).getViewSetup().getSize(),
					spimData.getViewRegistrations().getViewRegistrations().get( firstView ).getModel(),
					models.get( firstView ).getModel(),
					new RigidModel3D() );

			// pre-concatenate models to spimdata2 viewregistrations (from SpimData(2))
			for ( final ViewId viewId : subset.getViews() )
			{
				final Tile< AffineModel3D > tile = models.get( viewId );
				final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistrations().get( viewId );

				TransformationTools.storeTransformation( vr, viewId, tile, mapBack, "Scripted AffineModel3D" );
			}
		}
	}

	public static final HashMap< ViewId, Tile< AffineModel3D > > pairSubsetTest(
			final SpimData2 spimData,
			final Subset< ViewId > subset,
			final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints,
			final Map< ViewId, HashMap< String, Double > > labelMap,
			final RANSACParameters rp,
			final GeometricHashingParameters gp,
			final List< ViewId > fixedViews,
			final boolean matchAcrossLabels )
	{
		final List< Pair< ViewId, ViewId > > pairs = subset.getPairs();

		for ( final Pair< ViewId, ViewId > pair : pairs )
			System.out.println( Group.pvid( pair.getA() ) + " <=> " + Group.pvid( pair.getB() ) );

		// compute all pairwise matchings
		final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > result =
				MatcherPairwiseTools.computePairs( pairs, interestpoints, new GeometricHashingPairwise< InterestPoint >( rp, gp ), matchAcrossLabels );

		// clear correspondences
		MatcherPairwiseTools.clearCorrespondences( subset.getViews(), spimData.getViewInterestPoints().getViewInterestPoints(), labelMap );

		// add the corresponding detections and output result
		for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > p : result )
		{
			final ViewId vA = p.getA().getA();
			final ViewId vB = p.getA().getB();

			/*
			final InterestPoints listA = spimData.getViewInterestPoints().getViewInterestPoints().get( vA ).getInterestPointList( labelMap.get( vA ) );
			final InterestPoints listB = spimData.getViewInterestPoints().getViewInterestPoints().get( vB ).getInterestPointList( labelMap.get( vB ) );

			MatcherPairwiseTools.addCorrespondences( p.getB().getInliers(), vA, vB, labelMap.get( vA ), labelMap.get( vB ), listA, listB );
			*/

			final String labelA = p.getB().getLabelA();
			final String labelB = p.getB().getLabelB();

			final InterestPoints listA = spimData.getViewInterestPoints().getViewInterestPoints().get( vA ).getInterestPointList( labelA );
			final InterestPoints listB = spimData.getViewInterestPoints().getViewInterestPoints().get( vB ).getInterestPointList( labelB );

			MatcherPairwiseTools.addCorrespondences( p.getB().getInliers(), vA, vB, labelA, labelB, listA, listB );

			System.out.println( p.getB().getFullDesc() );
		}

		final ConvergenceStrategy cs = new ConvergenceStrategy( 10.0 );
		final PointMatchCreator pmc = new InterestPointMatchCreator( result, labelMap );

		// run global optimization
		return GlobalOpt.computeTiles( new AffineModel3D(), true, pmc, cs, fixedViews, subset.getGroups() );
	}

	public static final HashMap< ViewId, Tile< AffineModel3D > > groupedSubsetTest(
			final SpimData2 spimData,
			final Subset< ViewId > subset,
			final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints,
			final Map< ViewId, HashMap< String, Double > > labelMap,
			final RANSACParameters rp,
			final GeometricHashingParameters gp,
			final List< ViewId > fixedViews,
			final boolean matchAcrossLabels )
	{
		final List< Pair< Group< ViewId >, Group< ViewId > > > groupedPairs = subset.getGroupedPairs();
		final Map< Group< ViewId >, HashMap< String, Collection< GroupedInterestPoint< ViewId > > > > groupedInterestpoints = new HashMap<>();
		final InterestPointGrouping< ViewId > ipGrouping = new InterestPointGroupingAll<>( interestpoints );

		// which groups exist
		final Set< Group< ViewId > > groups = new HashSet<>();

		for ( final Pair< Group< ViewId >, Group< ViewId > > pair : groupedPairs )
		{
			groups.add( pair.getA() );
			groups.add( pair.getB() );

			System.out.print( "[" + pair.getA() + "] <=> [" + pair.getB() + "]" );

			if ( !groupedInterestpoints.containsKey( pair.getA() ) )
			{
				System.out.print( ", grouping interestpoints for " + pair.getA() );

				groupedInterestpoints.put( pair.getA(), ipGrouping.group( pair.getA() ) );
			}

			if ( !groupedInterestpoints.containsKey( pair.getB() ) )
			{
				System.out.print( ", grouping interestpoints for " + pair.getB() );

				groupedInterestpoints.put( pair.getB(), ipGrouping.group( pair.getB() ) );
			}

			System.out.println();
		}

		final List< Pair< Pair< Group< ViewId >, Group< ViewId > >, PairwiseResult< GroupedInterestPoint< ViewId > > > > resultGroup =
				MatcherPairwiseTools.computePairs( groupedPairs, groupedInterestpoints, new GeometricHashingPairwise<>( rp, gp ), matchAcrossLabels );

		// clear correspondences and get a map linking ViewIds to the correspondence lists
		final Map< ViewId, HashMap< String, List< CorrespondingInterestPoints > > > cMap =
				MatcherPairwiseTools.clearCorrespondences( subset.getViews(), spimData.getViewInterestPoints().getViewInterestPoints(), labelMap );

		// add the corresponding detections and output result
		final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< GroupedInterestPoint< ViewId > > > > resultG =
				MatcherPairwiseTools.addCorrespondencesFromGroups( resultGroup, spimData.getViewInterestPoints().getViewInterestPoints(), cMap );

		// run global optimization
		final ConvergenceStrategy cs = new ConvergenceStrategy( 10.0 );
		final PointMatchCreator pmc = new InterestPointMatchCreator( resultG, labelMap );

		return GlobalOpt.computeTiles( new AffineModel3D(), true, pmc, cs, fixedViews, groups );
	}
}
