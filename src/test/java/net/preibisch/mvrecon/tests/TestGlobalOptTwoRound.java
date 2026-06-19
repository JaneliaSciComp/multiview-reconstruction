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
import java.util.HashMap;
import java.util.HashSet;

import org.junit.jupiter.api.Test;

import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOptTwoRound;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.IterativeConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.SimpleIterativeConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.linkremoval.MaxErrorLinkRemoval;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.ImageCorrelationPointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.weak.MetaDataWeakLinkFactory;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.AllAgainstAllOverlap;

public class TestGlobalOptTwoRound
{
	@Test
	public void testGlobalOptTwoRound()
	{
		final HashMap< ViewId, TranslationModel3D > computeResults = computeGlobalOptTwoRound();

		final double[] t0 = computeResults.get( new ViewId( 0, 0 ) ).getTranslation();
		final double[] t1 = computeResults.get( new ViewId( 0, 1 ) ).getTranslation();
		final double[] t2 = computeResults.get( new ViewId( 0, 2 ) ).getTranslation();

		assertArrayEquals( new double[] { 0.0, 0.0, 0.0 } , t0, SimulateUtil.delta, "translation vector after global opt should have a specific value." );
		assertArrayEquals( new double[] { 100.0, 0.0, 0.0 } , t1, SimulateUtil.delta, "translation vector after global opt should have a specific value." );
		assertArrayEquals( new double[] { 50.0, 0.0, 0.0 } , t2, SimulateUtil.delta, "translation vector after global opt should have a specific value." );
	}

	public static HashMap< ViewId, TranslationModel3D > computeGlobalOptTwoRound()
	{
		final ViewId view0 = new ViewId( 0, 0 );
		final ViewId view1 = new ViewId( 0, 1 );
		final ViewId view2 = new ViewId( 0, 2 );

		final Group< ViewId > group0 = new Group<>( view0 );
		final Group< ViewId > group1 = new Group<>( view1 );
		final Group< ViewId > group2 = new Group<>( view2 );

		final HashSet< Group< ViewId > > groups = new HashSet<>();
		groups.add( group0 );
		groups.add( group1 );
		groups.add( group2 );

		final ArrayList< ViewId > fixed = new ArrayList<>();
		fixed.add( view0 );

		final BoundingBox bb = new BoundingBox( new int[]{ 0, 0, 0 }, new int[]{ 511, 511, 511 } );
		final ArrayList< PairwiseStitchingResult< ViewId > > pairwiseResults = new ArrayList<>();

		pairwiseResults.add( new PairwiseStitchingResult<>( new ValuePair<>( group0, group1 ), bb, new Translation3D( 100, 0, 0 ), 0.5 , 0.0 ) );
		pairwiseResults.add( new PairwiseStitchingResult<>( new ValuePair<>( group1, group2 ), bb, new Translation3D( 0, 100.25, 0 ), 0.1, 0.0 ) ); // will be discarded because r=0.1
		pairwiseResults.add( new PairwiseStitchingResult<>( new ValuePair<>( group0, group2 ), bb, new Translation3D( 100, 100.5, 0 ), 0.1, 0.0 ) ); // will be discarded because r=0.1

		final IterativeConvergenceStrategy cs = new SimpleIterativeConvergenceStrategy( 10.0, 10.0, 10.0 );
		final PointMatchCreator pmc = new ImageCorrelationPointMatchCreator( pairwiseResults, 0.3 );

		// set up metadata
		final HashMap<ViewId, ViewRegistration> vrMap = new HashMap<>();

		AffineTransform3D tr0 = new AffineTransform3D();
		tr0.translate( new double[] {0.0, 0.0, 0.0} );
		tr0 = tr0.copy();
		new ViewRegistration( 0, 0, tr0 );
		vrMap.put( view0, new ViewRegistration( 0, 0, tr0 ) );

		AffineTransform3D tr1 = new AffineTransform3D();
		tr1.translate( new double[] {0.0, 300.0, 0.0} );
		tr1 = tr1.copy();
		new ViewRegistration( 0, 1, tr1 );
		vrMap.put( view1, new ViewRegistration( 0, 1, tr1 ) );

		AffineTransform3D tr2 = new AffineTransform3D();
		tr2.translate( new double[] {300.0, 300.0, 0.0} );
		tr2 = tr2.copy();
		new ViewRegistration( 0, 2, tr2 );
		vrMap.put( view2, new ViewRegistration( 0, 2, tr2 ) );

		final HashMap< ViewId, TranslationModel3D > computeResults = GlobalOptTwoRound.computeModels(
				new TranslationModel3D(),
				true,
				pmc,
				cs,
				new MaxErrorLinkRemoval(),
				new MetaDataWeakLinkFactory( vrMap, new AllAgainstAllOverlap< ViewId >( bb.numDimensions() ) ),
				new ConvergenceStrategy( Double.MAX_VALUE ),
				fixed,
				groups );

		return computeResults;
	}

	// ==================== Original main() method for manual testing ====================
	public static void main( String[] args )
	{
		final HashMap< ViewId, TranslationModel3D > computeResults = computeGlobalOptTwoRound();

		computeResults.forEach( ( k, v ) -> {
			System.out.println( k + ": " + v );
		});
	}
}
