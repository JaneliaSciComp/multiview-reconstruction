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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

import mpicbg.models.Model;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Vectors;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSAC;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsMatcher.Candidates;

/**
 * A {@link MatcherPairwise} that matches two views on the embeddings stored with their interest points: the per-point
 * array (default {@code embedding}) of the dataset's {@code interestpoints.zarr}, one float32 unit vector per point,
 * written by pcmatch's {@code embed} ({@link InterestPointArrays}). The candidates are computed by
 * {@link EmbeddingsMatcher} (a radius query in the registered frame, a cosine per candidate pair, a ratio test in
 * similarity space) and handed to RANSAC as BigStitcher-Spark's {@code LoadCandidatesPairwise} hands loaded candidates;
 * no images, no network, no Python at match time. {@code EmbeddingsGUI} offers it in Fiji, BigStitcher-Spark as
 * {@code match-interestpoints -m EMBEDDINGS}.
 *
 * The rows of a view are read inside {@link #match} through the labels' handles: a filtered label reads its base
 * label's rows, the rows are joined to the points by id, and the array must have a row for every point of the (base)
 * label and no others (a re-detected label fails the view with "run pcmatch embed again"). Points whose row is NaN
 * take no part.
 * The arrays of the two views must come from one model (equal {@code meta.model.fingerprint}) and have one width.
 *
 * @param <I> InterestPoint (grouped interest points are not supported)
 */
public class EmbeddingsPairwise< I extends InterestPoint > implements MatcherPairwise< I >
{
	final RANSACParameters rp;
	final EmbeddingsParameters parameters;
	final ViewInterestPoints interestPoints;

	/**
	 * @param rp RANSAC parameters (epsilon, min inlier ratio, min matches, iterations, multi-consensus)
	 * @param parameters the model to fit, the search radius, the ratio, smin and the array's name
	 * @param interestPoints the interest point lists of the dataset; the stored embeddings and the base labels are read through them
	 */
	public EmbeddingsPairwise( final RANSACParameters rp, final EmbeddingsParameters parameters, final ViewInterestPoints interestPoints )
	{
		if ( rp == null || parameters == null )
			throw new IllegalArgumentException( "The embeddings matcher needs RANSAC parameters and its own parameters." );
		if ( interestPoints == null )
			throw new IllegalArgumentException( "The embeddings matcher needs the interest point lists of the dataset." );

		this.rp = rp;
		this.parameters = parameters;
		this.interestPoints = interestPoints;
	}

	@Override
	public < V > PairwiseResult< I > match(
			final Collection< I > listAIn,
			final Collection< I > listBIn,
			final V viewsA,
			final V viewsB,
			final String labelA,
			final String labelB )
	{
		final PairwiseResult< I > result = new PairwiseResult< I >( true );
		final Model< ? > model = parameters.getModel();
		final double radius = parameters.getSearchRadius();
		final String arrayName = parameters.getArrayName();

		if ( Group.class.isInstance( viewsA ) || Group.class.isInstance( viewsB ) ||
			 !( viewsA instanceof ViewId ) || !( viewsB instanceof ViewId ) ||
			 ( !listAIn.isEmpty() && GroupedInterestPoint.class.isInstance( listAIn.iterator().next() ) ) ||
			 ( !listBIn.isEmpty() && GroupedInterestPoint.class.isInstance( listBIn.iterator().next() ) ) )
		{
			throw new RuntimeException( "EMBEDDINGS does not support grouped views (--groupTiles/--groupIllums/--groupChannels/--splitTimepoints): every view keeps its own stored vectors." );
		}

		final ViewId vA = (ViewId)viewsA;
		final ViewId vB = (ViewId)viewsB;
		final int minNumCandidates = Math.max( model.getMinNumMatches(), rp.getMinNumMatches() );

		if ( listAIn.size() < minNumCandidates || listBIn.size() < minNumCandidates )
		{
			result.setCandidates( new ArrayList< PointMatchGeneric< I > >() );
			result.setInliers( new ArrayList< PointMatchGeneric< I > >(), Double.NaN );
			result.setResult( System.currentTimeMillis(), "EMBEDDINGS: not enough interest points (" + listAIn.size() + "/" + listBIn.size() + ", need >= " + minNumCandidates + ")." );
			return result;
		}

		// the stored embeddings of the two views by point id, through the labels' handles: the join checks the rows against
		// the label's points (the base label's, for a filtered label), which the lists we were given do not tell
		final Vectors vectorsA = InterestPointArrays.vectors( interestPoints.getViewInterestPointLists( vA ), labelA, arrayName );
		final Vectors vectorsB = InterestPointArrays.vectors( interestPoints.getViewInterestPointLists( vB ), labelB, arrayName );

		if ( vectorsA.columns() != vectorsB.columns() )
			throw new RuntimeException( "The stored embeddings '" + arrayName + "' of " + Group.pvid( vA ) + " ('" + labelA + "', " + vectorsA.columns() + " values per point) and " + Group.pvid( vB ) + " ('" + labelB + "', " + vectorsB.columns() + ") differ in width, they are not comparable." );

		if ( !Objects.equals( vectorsA.attributes().fingerprint(), vectorsB.attributes().fingerprint() ) )
			throw new RuntimeException( "The stored embeddings '" + arrayName + "' of " + Group.pvid( vA ) + " ('" + labelA + "', model " + vectorsA.attributes().fingerprint() + ") and " + Group.pvid( vB ) + " ('" + labelB + "', model " + vectorsB.attributes().fingerprint() + ") were written by different models, they are not comparable: run pcmatch embed with one model for both." );

		final int columns = vectorsA.columns();

		// the points that have a vector, in the registered frame as given
		final ArrayList< I > pointsA = new ArrayList<>(), pointsB = new ArrayList<>();
		final float[] valuesA = select( listAIn, vectorsA, pointsA );
		final float[] valuesB = select( listBIn, vectorsB, pointsB );

		final Candidates< I > found = EmbeddingsMatcher.match( pointsA, valuesA, pointsB, valuesB, columns, radius, parameters.getRatio(), parameters.getSmin() );
		final ArrayList< PointMatchGeneric< I > > candidates = found.candidates();

		final String prefix = "EMBEDDINGS: " + listAIn.size() + "/" + listBIn.size() + " points, " + pointsA.size() + "/" + pointsB.size() + " with a " + columns + "-D embedding, "
				+ found.withinRadiusA() + "/" + found.withinRadiusB() + ( Double.isInfinite( radius ) ? " with a partner (no search radius limit), " : " with a partner within " + radius + " px, " ) + candidates.size() + " candidates -> ";

		result.setCandidates( candidates );

		if ( found.withinRadiusA() < minNumCandidates || found.withinRadiusB() < minNumCandidates )
		{
			result.setInliers( new ArrayList< PointMatchGeneric< I > >(), Double.NaN );
			result.setResult( System.currentTimeMillis(), prefix + "not enough interest points within the search radius (need >= " + minNumCandidates + ")." );
			return result;
		}

		if ( candidates.size() < minNumCandidates )
		{
			result.setInliers( new ArrayList< PointMatchGeneric< I > >(), Double.NaN );
			result.setResult( System.currentTimeMillis(), prefix + "not enough candidates (need >= " + minNumCandidates + ")." );
			return result;
		}

		final ArrayList< PointMatchGeneric< I > > inliers = new ArrayList<>();
		final ArrayList< Integer > setIds = new ArrayList<>();

		final Pair< String, Double > ransacResult = RANSAC.computeRANSAC(
				candidates, inliers, setIds, model,
				rp.getMaxEpsilon(), rp.getMinInlierRatio(), rp.getMinNumMatches(), rp.getNumIterations(),
				rp.multiConsensus(), rp.getMaxTrust(), rp.getFilterRansac() );

		result.setInliers( inliers, ransacResult.getB(), setIds );
		result.setResult( System.currentTimeMillis(), prefix + ransacResult.getA() );

		return result;
	}

	/**
	 * @param points the points of a view as given to the matcher
	 * @param vectors their stored vectors by id
	 * @param selected receives the points that have a vector, in the order of {@code points}
	 * @return the vectors of {@code selected}, {@code vectors.columns()} values per point
	 */
	static < I extends InterestPoint > float[] select( final Collection< I > points, final Vectors vectors, final ArrayList< I > selected )
	{
		final int columns = vectors.columns();
		final int[] rows = new int[ points.size() ];
		int n = 0;

		for ( final I point : points )
		{
			final int row = vectors.indexOf( point.getId() );
			if ( row < 0 )
				continue;
			selected.add( point );
			rows[ n++ ] = row;
		}

		final float[] values = new float[ n * columns ];
		for ( int i = 0; i < n; ++i )
			System.arraycopy( vectors.values(), rows[ i ] * columns, values, i * columns, columns );

		return values;
	}

	@Override
	public boolean requiresInterestPointDuplication() { return false; } // RANSAC clones into LinkedPoints itself
}
