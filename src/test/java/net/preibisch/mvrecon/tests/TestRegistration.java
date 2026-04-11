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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mpicbg.models.AffineModel3D;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
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

public class TestRegistration
{
	private SpimData2 spimData;

	@BeforeEach
	public void setUp()
	{
		System.out.println( "SETTING UP ..." );
		spimData = SimulateUtil.setUp();

		// run DoG
		TestInterestPointDetection.testDoG( spimData, "beads" );
	}

	@Test
	public void testRegistrationUngrouped()
	{
		System.out.println( "\nUNGROUPED - views present:" );

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
			System.out.println( Group.pvid( viewId ) );

		final HashMap<ViewId, AffineModel3D> models = testRegistration( spimData, "beads", false );

		final double[] t0 = new double[ 12 ];
		final double[] t1 = new double[ 12 ];
		final double[] t2 = new double[ 12 ];

		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 0 ) ) ).toArray( t0 );
		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 1 ) ) ).toArray( t1 );
		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 2 ) ) ).toArray( t2 );

		final double[] t0Expected = new double[] { 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0 };
		final double[] t1Expected = new double[] { 1.0005356080585022, 2.49323474194707E-4, 0.0011220806217312473, -0.08259295862724232, 3.707245398563569E-4, 0.7072946129789373, 0.7076828581581442, 1.404856820272709, -2.63984069522126E-4, -0.7068158048729596, 0.7066541400023975, 51.581476626562484 };
		final double[] t2Expected = new double[] { 0.9998575301003715, 5.069732655624262E-4, -0.0010818832033704928, -0.020219824841918498, 4.6017605438656434E-4, 0.001134937523741015, 1.0023411774701763, 38.829895250234046, -1.7586056994376082E-4, -0.9980690338273157, 9.698915385629309E-4, 86.85082710148747 };

		assertArrayEquals( t0Expected, t0, SimulateUtil.delta, "matrix after global opt should have specific values." );
		assertArrayEquals( t1Expected, t1, SimulateUtil.delta, "matrix after global opt should have specific values." );
		assertArrayEquals( t2Expected, t2, SimulateUtil.delta, "matrix after global opt should have specific values." );
	}

	@Test
	public void testRegistrationGrouped()
	{
		System.out.println( "\nGROUPED - views present:" );

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
			System.out.println( Group.pvid( viewId ) );

		final HashMap<ViewId, AffineModel3D> models = testRegistration( spimData, "beads", true );

		final double[] t0 = new double[ 12 ];
		final double[] t1 = new double[ 12 ];
		final double[] t2 = new double[ 12 ];

		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 0 ) ) ).toArray( t0 );
		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 1 ) ) ).toArray( t1 );
		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 2 ) ) ).toArray( t2 );

		final double[] t0Expected = new double[] { 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0 };
		final double[] t1Expected = new double[] { 1.0005356080585022, 2.49323474194707E-4, 0.0011220806217312473, -0.08259295862724232, 3.707245398563569E-4, 0.7072946129789373, 0.7076828581581442, 1.404856820272709, -2.63984069522126E-4, -0.7068158048729596, 0.7066541400023975, 51.581476626562484 };
		final double[] t2Expected = new double[] { 0.9998575301003715, 5.069732655624262E-4, -0.0010818832033704928, -0.020219824841918498, 4.6017605438656434E-4, 0.001134937523741015, 1.0023411774701763, 38.829895250234046, -1.7586056994376082E-4, -0.9980690338273157, 9.698915385629309E-4, 86.85082710148747 };

		assertArrayEquals( t0Expected, t0, SimulateUtil.delta, "matrix after grouped global opt should have specific values." );
		assertArrayEquals( t1Expected, t1, SimulateUtil.delta, "matrix after grouped global opt should have specific values." );
		assertArrayEquals( t2Expected, t2, SimulateUtil.delta, "matrix after grouped global opt should have specific values." );
	}

	public static HashMap< ViewId, AffineModel3D > testRegistration( final SpimData2 spimData, final String label, final boolean grouped )
	{
		// select views to process
		final List< ViewId > viewIds = new ArrayList< ViewId >();
		viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		// filter not present ViewIds
		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
		System.out.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

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
		System.out.println( "before filtering:" );

		for ( final Entry< ViewId, HashMap< String, Collection< InterestPoint > > > element: interestpoints.entrySet() )
			for ( final Entry< String, Collection< InterestPoint > > subelement: element.getValue().entrySet() )
				System.out.println( element.getKey() + ", " + subelement.getKey() + ": " + subelement.getValue().size() );

		TransformationTools.filterForOverlappingInterestPoints( interestpoints, groups, spimData.getViewRegistrations().getViewRegistrations(), spimData.getSequenceDescription().getViewDescriptions() );

		System.out.println( "after filtering:" );

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

		final HashMap< ViewId, AffineModel3D > modelsReturn = new HashMap<>();

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

				modelsReturn.put( viewId, tile.getModel().copy() );
				TransformationTools.storeTransformation( vr, viewId, tile, mapBack, "Scripted AffineModel3D" );
			}
		}

		return modelsReturn;
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

			final String labelA = p.getB().getLabelA();
			final String labelB = p.getB().getLabelB();

			final InterestPoints listA = spimData.getViewInterestPoints().getViewInterestPoints().get( vA ).getInterestPointList( labelA );
			final InterestPoints listB = spimData.getViewInterestPoints().getViewInterestPoints().get( vB ).getInterestPointList( labelB );

			MatcherPairwiseTools.addCorrespondences( p.getB().getInliers(), vA, vB, labelA, labelB, listA, listB );

			//System.out.println( p.getB().getFullDesc() );
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

	// ==================== Original main() method for manual testing ====================
	public static void main( String[] args ) throws SpimDataException
	{
		final SpimData2 spimData = SimulateUtil.setUpLarge();

		System.out.println( "Views present:" );

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
			System.out.println( Group.pvid( viewId ) );

		// run DoG
		TestInterestPointDetection.testDoG( spimData, "beads" );

		HashMap< ViewId, AffineModel3D > models = testRegistration( spimData, "beads", false );

		System.out.println( "\nFINAL RESULTS: " );

		models.forEach( ( k, v ) -> {
			System.out.println( k + ": " + v );
		});
	}
}
