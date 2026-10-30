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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;

/**
 * The matcher on stored embeddings ({@link EmbeddingsPairwise}) on a synthetic store: view B is view A shifted by a known
 * translation with other ids plus a few points of its own, the corresponding points carry one planted unit vector, the
 * rest their own. The matcher finds the pairs from the store, refuses a radius that does not reach the partners, and
 * refuses arrays of two different models.
 */
public class TestEmbeddingsPairwise
{
	@TempDir
	Path tmp;

	static final String LABEL = "beads";
	static final int COLUMNS = 16;
	static final int PAIRS = 60, EXTRA = 15;
	static final double[] SHIFT = { 7.5, -3.25, 2 };

	final Random random = new Random( 7 );

	float[] unit()
	{
		final float[] v = new float[ COLUMNS ];
		double norm = 0;
		for ( int d = 0; d < COLUMNS; ++d )
		{
			v[ d ] = (float) random.nextGaussian();
			norm += v[ d ] * v[ d ];
		}
		for ( int d = 0; d < COLUMNS; ++d )
			v[ d ] /= (float) Math.sqrt( norm );
		return v;
	}

	/** Saves the points of both views into the store and writes their arrays; @return the lists of the two views */
	ViewInterestPointLists[] setUp( final URI baseURI, final ViewId vA, final ViewId vB, final String fingerprintB )
	{
		final List< InterestPoint > pointsA = new ArrayList<>(), pointsB = new ArrayList<>();
		final List< float[] > vectorsA = new ArrayList<>(), vectorsB = new ArrayList<>();

		for ( int i = 0; i < PAIRS; ++i )
		{
			final double[] l = { 50 + random.nextDouble() * 900, 50 + random.nextDouble() * 900, 10 + random.nextDouble() * 80 };
			final float[] v = unit();
			pointsA.add( new InterestPoint( i, l ) );
			vectorsA.add( v );
			pointsB.add( new InterestPoint( 1000 + 3 * i, new double[] { l[ 0 ] + SHIFT[ 0 ], l[ 1 ] + SHIFT[ 1 ], l[ 2 ] + SHIFT[ 2 ] } ) );
			vectorsB.add( v );
		}
		for ( int i = 0; i < EXTRA; ++i )
		{
			pointsA.add( new InterestPoint( PAIRS + i, new double[] { random.nextDouble() * 1000, random.nextDouble() * 1000, random.nextDouble() * 100 } ) );
			vectorsA.add( unit() );
			pointsB.add( new InterestPoint( 5000 + i, new double[] { random.nextDouble() * 1000, random.nextDouble() * 1000, random.nextDouble() * 100 } ) );
			vectorsB.add( unit() );
		}

		final InterestPointsZarrStore store = InterestPointsZarrStore.get( baseURI );
		store.beginBatch();
		final InterestPointsZarr listA = new InterestPointsZarr( baseURI, vA, LABEL ), listB = new InterestPointsZarr( baseURI, vB, LABEL );
		listA.setInterestPoints( pointsA );
		listB.setInterestPoints( pointsB );
		listA.setCorrespondingInterestPoints( new ArrayList<>() );
		listB.setCorrespondingInterestPoints( new ArrayList<>() );
		assertTrue( listA.saveInterestPoints( false ) && listB.saveInterestPoints( false ) );
		store.commit();

		writeArray( baseURI, vA, listA.getInterestPointsCopy().values(), vectorsA, pointsA, "model-a" );
		writeArray( baseURI, vB, listB.getInterestPointsCopy().values(), vectorsB, pointsB, fingerprintB );

		final ViewInterestPointLists lA = new ViewInterestPointLists( vA.getTimePointId(), vA.getViewSetupId() ), lB = new ViewInterestPointLists( vB.getTimePointId(), vB.getViewSetupId() );
		lA.addInterestPointList( LABEL, listA );
		lB.addInterestPointList( LABEL, listB );
		return new ViewInterestPointLists[] { lA, lB };
	}

	/** The array of a view in pcmatch's layout: the vectors by the order of {@code points}, one row per stored point. */
	static void writeArray( final URI baseURI, final ViewId view, final java.util.Collection< InterestPoint > stored, final List< float[] > vectors, final List< InterestPoint > points, final String fingerprint )
	{
		final int[] ids = new int[ points.size() ];
		final float[] values = new float[ COLUMNS * points.size() ];
		for ( int r = 0; r < points.size(); ++r )
		{
			ids[ r ] = points.get( r ).getId();
			System.arraycopy( vectors.get( r ), 0, values, COLUMNS * r, COLUMNS );
		}
		assertEquals( points.size(), stored.size() );
		TestInterestPointArrays.writeArray( baseURI, LABEL, view.getTimePointId(), "embedding", view.getViewSetupId(), ids, values, COLUMNS, fingerprint );
	}

	static PairwiseResult< InterestPoint > match( final ViewInterestPointLists[] lists, final double radius )
	{
		// no trust filter: the planted pairs fit to float32 rounding, so 3 x the median residual would reject exact pairs
		final RANSACParameters rp = new RANSACParameters( 0.5, 0.1, 12, 1000, false, 3, false );
		final EmbeddingsParameters parameters = new EmbeddingsParameters( new TranslationModel3D(), radius, EmbeddingsMatcher.defaultRatio, EmbeddingsMatcher.defaultSmin, "embedding" );
		final EmbeddingsPairwise< InterestPoint > matcher = new EmbeddingsPairwise<>( rp, parameters, new ViewInterestPoints( List.of( lists ) ) );

		// the lists as the registration hands them: here the stored points themselves (identity registration), the shift is the misregistration
		final List< InterestPoint > a = new ArrayList<>( lists[ 0 ].getInterestPointList( LABEL ).getInterestPointsCopy().values() );
		final List< InterestPoint > b = new ArrayList<>( lists[ 1 ].getInterestPointList( LABEL ).getInterestPointsCopy().values() );
		return matcher.match( a, b, new ViewId( lists[ 0 ].getTimePointId(), lists[ 0 ].getViewSetupId() ), new ViewId( lists[ 1 ].getTimePointId(), lists[ 1 ].getViewSetupId() ), LABEL, LABEL );
	}

	@Test
	public void findsThePlantedPairs()
	{
		final URI baseURI = tmp.toUri();
		final ViewInterestPointLists[] lists = setUp( baseURI, new ViewId( 0, 0 ), new ViewId( 0, 1 ), "model-a" );

		final PairwiseResult< InterestPoint > result = match( lists, 30 );
		assertTrue( result.getCandidates().size() >= PAIRS, result.getFullDesc() );
		assertEquals( PAIRS, result.getInliers().size(), result.getFullDesc() );
		for ( final PointMatchGeneric< InterestPoint > inlier : result.getInliers() )
			assertEquals( 1000 + 3 * inlier.getPoint1().getId(), inlier.getPoint2().getId() );
		assertTrue( result.getFullDesc().contains( "EMBEDDINGS: " + ( PAIRS + EXTRA ) + "/" + ( PAIRS + EXTRA ) + " points" ), result.getFullDesc() );

		// no radius limit: the vectors alone tell the partners apart
		final PairwiseResult< InterestPoint > unlimited = match( lists, Double.POSITIVE_INFINITY );
		assertEquals( PAIRS, unlimited.getInliers().size(), unlimited.getFullDesc() );
		assertTrue( unlimited.getFullDesc().contains( "no search radius limit" ), unlimited.getFullDesc() );

		// a radius that does not reach the shifted partners: nothing to match
		final PairwiseResult< InterestPoint > none = match( lists, 5 );
		assertEquals( 0, none.getInliers().size() );
		assertTrue( none.getFullDesc().contains( "not enough interest points within the search radius" ), none.getFullDesc() );
	}

	@Test
	public void refusesArraysOfDifferentModels()
	{
		final URI baseURI = tmp.toUri();
		// another timepoint, so that the second view has an array group (and a model) of its own
		final ViewInterestPointLists[] lists = setUp( baseURI, new ViewId( 0, 0 ), new ViewId( 1, 0 ), "model-b" );

		final RuntimeException e = assertThrows( RuntimeException.class, () -> match( lists, 30 ) );
		assertTrue( e.getMessage().contains( "different models" ), e.getMessage() );
		assertTrue( e.getMessage().contains( "model-a" ) && e.getMessage().contains( "model-b" ), e.getMessage() );
	}

	@Test
	public void refusesBadParameters()
	{
		assertThrows( IllegalArgumentException.class, () -> new EmbeddingsParameters( new TranslationModel3D(), 0, 0.85, 0.3, "embedding" ) );
		assertThrows( IllegalArgumentException.class, () -> new EmbeddingsParameters( new TranslationModel3D(), 20, 1.5, 0.3, "embedding" ) );
		assertThrows( IllegalArgumentException.class, () -> new EmbeddingsParameters( new TranslationModel3D(), 20, 0.85, 0.3, "" ) );
		assertThrows( IllegalArgumentException.class, () -> new EmbeddingsPairwise<>( new RANSACParameters(), new EmbeddingsParameters( new TranslationModel3D(), 20, 0.85, 0.3, "embedding" ), null ) );
	}
}
