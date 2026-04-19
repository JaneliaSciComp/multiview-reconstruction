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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.PairwiseStitchingResult;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOpt;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.Link;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.QualityPointMatch;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class ImageCorrelationPointMatchCreator implements PointMatchCreator
{
	final Collection< ? extends PairwiseStitchingResult< ? extends ViewId > > pairwiseResults;
	final double correlationT;

	public ImageCorrelationPointMatchCreator(
			final Collection< ? extends PairwiseStitchingResult< ? extends ViewId > > pairwiseResults,
			final double correlationT )
	{
		this.pairwiseResults = pairwiseResults;
		this.correlationT = correlationT;
	}

	public ImageCorrelationPointMatchCreator(
			final Collection< ? extends PairwiseStitchingResult< ? extends ViewId > > pairwiseResults )
	{
		// TODO: new class that doesn't check
		this( pairwiseResults, -Double.MAX_VALUE );
	}

	@Override
	public HashSet< ViewId > getAllViews()
	{
		final HashSet< ViewId > tmpSet = new HashSet<>();

		for ( PairwiseStitchingResult< ? extends ViewId > pair : pairwiseResults )
		{
			for ( final ViewId v : pair.pair().getA() )
				tmpSet.add( v );

			for ( final ViewId v : pair.pair().getB() )
				tmpSet.add( v );
		}

		return tmpSet;
	}

	@Override
	public < M extends Model< M > > void assignWeights(
			final HashMap< ViewId, Tile< M > > tileMap,
			final ArrayList< Group< ViewId > > groups,
			final Collection< ViewId > fixedViews )
	{
		return;
	}

	@Override
	public < M extends Model< M > > void assignPointMatches(
			final HashMap< ViewId, Tile< M > > tileMap,
			final ArrayList< Group< ViewId > > groups,
			final Collection< ViewId > fixedViews )
	{
		final List< Link< Group< ? extends ViewId > > > strongLinks = new ArrayList<>();

		for ( final PairwiseStitchingResult< ? extends ViewId > res : pairwiseResults )
		{
			// only consider Pairs that were selected and that have high enough correlation
			if ( res.r() >= correlationT )
			{
				strongLinks.add( new Link< Group< ? extends ViewId > >( res.pair().getA(), res.pair().getB(), res.getBoundingBox(), res.getTransform(), res.r() ) );
				System.out.println( "added strong link between " + res.pair().getA() + " and " + res.pair().getB() + ": " + res.getTransform() + " " + res.r() );
			}
		}

		// assign the pointmatches to all the tiles
		// we just need one of the views, since they all map to the same tile
		for ( Link< Group< ? extends ViewId > > link : strongLinks )
			addPointMatches( link, tileMap.get( link.getFirst().iterator().next() ), tileMap.get( link.getSecond().iterator().next() ) );
	}

	public static <M extends Model< M >> void addPointMatches( 
			final Link<Group<? extends ViewId>> link,
			final Tile<M> tileA,
			final Tile<M> tileB )
	{
		final ArrayList< PointMatch > pm = new ArrayList< PointMatch >();
		final List<Point> pointsA = new ArrayList<>();
		final List<Point> pointsB = new ArrayList<>();
		final List<Double> quality = new ArrayList<>();

		final RealInterval bb = link.getBoundingBox();

		// we use the vertices of the unit cube and their transformations as point matches 
		final double[][] pa = new double[][]{
			{ bb.realMin( 0 ), bb.realMin( 1 ), bb.realMin( 2 ) },
			{ bb.realMax( 0 ), bb.realMin( 1 ), bb.realMin( 2 ) },
			{ bb.realMin( 0 ), bb.realMax( 1 ), bb.realMin( 2 ) },
			{ bb.realMax( 0 ), bb.realMax( 1 ), bb.realMin( 2 ) },
			{ bb.realMin( 0 ), bb.realMin( 1 ), bb.realMax( 2 ) },
			{ bb.realMax( 0 ), bb.realMin( 1 ), bb.realMax( 2 ) },
			{ bb.realMin( 0 ), bb.realMax( 1 ), bb.realMax( 2 ) },
			{ bb.realMax( 0 ), bb.realMax( 1 ), bb.realMax( 2 ) }};

		final double[][] pb = new double[8][3];

		// the transformed bounding boxes are our corresponding features
		for (int i = 0; i < pa.length; ++i)
		{
			link.getTransform().applyInverse( pb[i], pa[i] );
			pointsA.add( new Point( pa[i] ) );
			pointsB.add( new Point( pb[i] ) );
			quality.add( link.getLinkQuality() );
		}

		// create PointMatches and connect Tiles
		for (int i = 0; i < pointsA.size(); ++i)
			pm.add( new QualityPointMatch( pointsA.get( i ) , pointsB.get( i ), quality.get( i ) ) );

		tileA.addMatches( pm );
		tileB.addMatches( QualityPointMatch.flipQ( pm ) ); //TODO: PointMatch needs a clone() method
		tileA.addConnectedTile( tileB );
		tileB.addConnectedTile( tileA );
	}

	public static void main( String[] args )
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

		pairwiseResults.add( new PairwiseStitchingResult<>( new ValuePair<>( group0, group1 ), bb,  new Translation3D( 100, 0, 0 ), 0.5 , 0.0) );
		pairwiseResults.add( new PairwiseStitchingResult<>( new ValuePair<>( group1, group2 ), bb,  new Translation3D( 0, 100.25, 0 ), 0.5 , 0.0) );
		pairwiseResults.add( new PairwiseStitchingResult<>( new ValuePair<>( group0, group2 ), bb,  new Translation3D( 100, 100.5, 0 ), 0.5 , 0.0) );

		final ConvergenceStrategy cs = new ConvergenceStrategy( 10.0 );
		final PointMatchCreator pmc = new ImageCorrelationPointMatchCreator( pairwiseResults, 0.3 );

		GlobalOpt.computeTiles( new TranslationModel3D(), true, pmc, cs, fixed, groups );
	}
}
