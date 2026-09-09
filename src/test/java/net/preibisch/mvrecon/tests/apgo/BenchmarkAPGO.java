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
package net.preibisch.mvrecon.tests.apgo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel3D;
import mpicbg.models.Model;
import mpicbg.models.TranslationModel3D;
import mpicbg.models.InterpolatedAffineModel3D;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOpt;
import net.preibisch.mvrecon.process.interestpointregistration.global.apgo.APGOSolver;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.InterestPointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwiseTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.AllToAll;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.PairwiseSetup;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.Subset;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.loadcorrespondences.LoadCorrespondencesPairwise;
import util.URITools;

/**
 * Benchmarks {@link APGOSolver} against the current global optimization on a real dataset.
 *
 * Both solvers are handed the byte-identical {@link PointMatchCreator}: the pair graph is
 * built once and the pairwise results come from the correspondences already stored in the
 * dataset ({@link LoadCorrespondencesPairwise}), so neither pays for re-matching and neither
 * sees different input. That load is the slow part on a network share, hence one JVM run for
 * everything.
 *
 * Scoring is the weighted world-space distance between corresponding interest points after
 * applying each solver's correction - the same quantity
 * {@link net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror.AnalyzeErrorsUtil} reports,
 * but computed from the already-loaded correspondences instead of re-reading them per run.
 */
public class BenchmarkAPGO
{
	static String xml = "/Volumes/tavakoli/MirrorScope/20260819_ExpID96_S4/intensity_correct/dataset.split.xml";

	/** As used in production: real beads carry the signal, split points only hold the tiling together. */
	static final Map< String, Double > LABEL_WEIGHTS = new LinkedHashMap<>();
	static
	{
		LABEL_WEIGHTS.put( "beads_split", 1.0 );
		LABEL_WEIGHTS.put( "splitPoints_2639", 0.01 );
	}

	static final int MIN_NUM_MATCHES = 12;

	/** Production settings: 3 px allowed error, affine regularized with rigid at lambda 0.01. */
	static final double GLOBAL_OPT_MAX_ERROR = 3.0;
	static final double REGULARIZATION_LAMBDA = 0.01;

	/** How many times to repeat GlobalOpt - it is not deterministic, so one sample says little. */
	static final int GLOBAL_OPT_REPEATS = 3;

	static InterpolatedAffineModel3D< AffineModel3D, RigidModel3D > productionModel()
	{
		return new InterpolatedAffineModel3D<>( new AffineModel3D(), new RigidModel3D(), REGULARIZATION_LAMBDA );
	}

	/** Weighted and unweighted correspondence distances after a solve. */
	static class Score
	{
		double weightedMean, mean, median, p95, max;
		long numCorrespondences, numPairs;

		@Override
		public String toString()
		{
			return String.format( "weighted mean %.4f px | per-pair mean: median %.4f, p95 %.4f, max %.4f | %d pairs, %d correspondences",
					weightedMean, median, p95, max, numPairs, numCorrespondences );
		}
	}

	/**
	 * Local cache of the pairwise results.
	 *
	 * Assembling them costs ~2.5 minutes against the SMB share and the answer is deterministic,
	 * so caching makes solver iterations cheap and the benchmark reproducible. Only the
	 * correspondences and their world coordinates are stored - everything downstream is derived.
	 */
	static void writeCache( final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > results,
			final String path ) throws Exception
	{
		try ( java.io.DataOutputStream out = new java.io.DataOutputStream(
				new java.io.BufferedOutputStream( new java.io.FileOutputStream( path ), 1 << 20 ) ) )
		{
			out.writeInt( results.size() );
			for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > r : results )
			{
				out.writeInt( r.getA().getA().getTimePointId() );
				out.writeInt( r.getA().getA().getViewSetupId() );
				out.writeInt( r.getA().getB().getTimePointId() );
				out.writeInt( r.getA().getB().getViewSetupId() );
				out.writeUTF( r.getB().getLabelA() == null ? "" : r.getB().getLabelA() );
				out.writeUTF( r.getB().getLabelB() == null ? "" : r.getB().getLabelB() );

				final List< ? extends PointMatchGeneric< InterestPoint > > inliers = r.getB().getInliers();
				out.writeInt( inliers.size() );
				for ( final PointMatchGeneric< InterestPoint > pm : inliers )
				{
					final InterestPoint a = pm.getPoint1(), b = pm.getPoint2();
					out.writeInt( a.getId() );
					for ( int d = 0; d < 3; ++d )
						out.writeDouble( a.getL()[ d ] );
					out.writeInt( b.getId() );
					for ( int d = 0; d < 3; ++d )
						out.writeDouble( b.getL()[ d ] );
				}
			}
		}
	}

	static List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > readCache( final String path )
			throws Exception
	{
		final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > results = new ArrayList<>();

		try ( java.io.DataInputStream in = new java.io.DataInputStream(
				new java.io.BufferedInputStream( new java.io.FileInputStream( path ), 1 << 20 ) ) )
		{
			final int n = in.readInt();
			for ( int i = 0; i < n; ++i )
			{
				final ViewId va = new ViewId( in.readInt(), in.readInt() );
				final ViewId vb = new ViewId( in.readInt(), in.readInt() );

				final PairwiseResult< InterestPoint > pr = new PairwiseResult<>( false );
				pr.setLabelA( in.readUTF() );
				pr.setLabelB( in.readUTF() );

				final int numInliers = in.readInt();
				final ArrayList< PointMatchGeneric< InterestPoint > > inliers = new ArrayList<>( numInliers );
				for ( int k = 0; k < numInliers; ++k )
				{
					final int idA = in.readInt();
					final double[] la = { in.readDouble(), in.readDouble(), in.readDouble() };
					final int idB = in.readInt();
					final double[] lb = { in.readDouble(), in.readDouble(), in.readDouble() };
					inliers.add( new PointMatchGeneric<>( new InterestPoint( idA, la ), new InterestPoint( idB, lb ) ) );
				}

				pr.setInliers( inliers, 0.0 );
				results.add( new net.imglib2.util.ValuePair<>( new net.imglib2.util.ValuePair<>( va, vb ), pr ) );
			}
		}

		return results;
	}

	public static void main( final String[] args ) throws Exception
	{
		if ( args.length > 0 )
			xml = args[ 0 ];

		XmlIoSpimData2.initN5Writing = false;

		final long tLoad = System.currentTimeMillis();
		final SpimData2 data = new XmlIoSpimData2().load( URITools.toURI( xml ) );
		System.out.println( "loaded " + xml + " in " + ( System.currentTimeMillis() - tLoad ) + " ms" );

		final Map< ViewId, ViewDescription > viewDescriptions = new HashMap<>();
		final List< ViewId > viewIds = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
			if ( vd.isPresent() )
			{
				viewIds.add( vd );
				viewDescriptions.put( vd, vd );
			}
		java.util.Collections.sort( viewIds );
		System.out.println( viewIds.size() + " present views" );

		final Map< ViewId, ViewRegistration > registrations = new HashMap<>();
		for ( final ViewId v : viewIds )
			registrations.put( v, data.getViewRegistrations().getViewRegistration( v ) );

		// Drop the previous solve so both solvers start from the pre-registration state.
		//
		// Without this the benchmark is asking each solver to improve on an already-optimal
		// answer, which neither can do - it only measures how much they perturb it.
		// preconcatenateTransform inserts at index 0, so index 0 is the most recent solve.
		final int strip = Integer.getInteger( "apgo.stripLast", 0 );
		if ( strip > 0 )
		{
			final Map< String, Integer > removed = new java.util.TreeMap<>();
			for ( final ViewId v : viewIds )
			{
				final ViewRegistration vr = registrations.get( v );
				for ( int k = 0; k < strip; ++k )
				{
					if ( vr.getTransformList().size() < 2 )
						throw new IllegalStateException( "refusing to strip the only transform of " + Group.pvid( v ) );

					removed.merge( vr.getTransformList().get( 0 ).getName(), 1, Integer::sum );
					vr.getTransformList().remove( 0 );
				}
				vr.updateModel();
			}
			System.out.println( "stripped " + strip + " leading ViewTransform(s) from " + viewIds.size()
					+ " views: " + removed );
		}

		final Map< ViewId, ViewInterestPointLists > interestPointLists = new HashMap<>();
		for ( final ViewId v : viewIds )
			interestPointLists.put( v, data.getViewInterestPoints().getViewInterestPoints().get( v ) );

		// only ask for the labels a view actually has
		final Map< ViewId, HashMap< String, Double > > labelMap = new HashMap<>();
		for ( final ViewId v : viewIds )
		{
			final HashMap< String, Double > labels = new HashMap<>();
			for ( final Map.Entry< String, Double > e : LABEL_WEIGHTS.entrySet() )
				if ( interestPointLists.get( v ).getHashMap().containsKey( e.getKey() ) )
					labels.put( e.getKey(), e.getValue() );
			labelMap.put( v, labels );
		}

		// --- interest points in world coordinates -------------------------------------------

		final long tIp = System.currentTimeMillis();
		final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints =
				TransformationTools.getAllTransformedInterestPoints( viewIds, registrations, interestPointLists, labelMap );
		System.out.println( "loaded interest points in " + ( System.currentTimeMillis() - tIp ) + " ms" );

		// --- pair graph ----------------------------------------------------------------------

		final long tPairs = System.currentTimeMillis();
		final PairwiseSetup< ViewId > setup = new AllToAll<>( viewIds, new java.util.HashSet< Group< ViewId > >() );
		System.out.println( "defined " + setup.definePairs().size() + " pairs" );
		System.out.println( "removed " + setup.removeNonOverlappingPairs( new SimpleBoundingBoxOverlap<>( data ) ).size()
				+ " non-overlapping pairs, " + setup.getPairs().size() + " remain" );
		setup.reorderPairs();
		setup.detectSubsets();
		setup.sortSubsets();

		final ArrayList< Subset< ViewId > > subsets = setup.getSubsets();
		System.out.println( subsets.size() + " subset(s)" );

		// fixed views: first view of each subset, as fixViewsIndex == 0 does in production
		final List< ViewId > fixedViews = setup.getDefaultFixedViews();
		for ( final Subset< ViewId > subset : subsets )
			fixedViews.add( Subset.getViewsSorted( subset.getViews() ).get( 0 ) );
		System.out.println( "fixing " + fixedViews.size() + " view(s)" );

		final String cachePath = System.getProperty( "apgo.cache" );

		final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > results;

		if ( cachePath != null && new java.io.File( cachePath ).exists() )
		{
			results = readCache( cachePath );
			System.out.println( "read " + results.size() + " pairwise results from cache in "
					+ ( System.currentTimeMillis() - tPairs ) + " ms" );
		}
		else
		{
			results = new ArrayList<>();
			for ( final Subset< ViewId > subset : subsets )
				results.addAll( MatcherPairwiseTools.computePairs(
						subset.getPairs(), interestpoints,
						new LoadCorrespondencesPairwise< InterestPoint >( data, MIN_NUM_MATCHES ), false ) );

			System.out.println( "loaded " + results.size() + " pairwise results in "
					+ ( System.currentTimeMillis() - tPairs ) + " ms" );

			if ( cachePath != null )
			{
				writeCache( results, cachePath );
				System.out.println( "cached them to " + cachePath );
			}
		}

		// world-coordinate extent, which drives the conditioning of the solve
		final double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE };
		final double[] max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > r : results )
			for ( final mpicbg.models.PointMatch pm : r.getB().getInliers() )
				for ( int d = 0; d < 3; ++d )
				{
					min[ d ] = Math.min( min[ d ], pm.getP1().getL()[ d ] );
					max[ d ] = Math.max( max[ d ], pm.getP1().getL()[ d ] );
				}
		System.out.println( "world extent x [" + ( long ) min[ 0 ] + ", " + ( long ) max[ 0 ] + "] y ["
				+ ( long ) min[ 1 ] + ", " + ( long ) max[ 1 ] + "] z [" + ( long ) min[ 2 ] + ", " + ( long ) max[ 2 ] + "]" );

		long totalCorrespondences = 0;
		int pairsWithMatches = 0;
		for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > r : results )
			if ( r.getB().getInliers().size() > 0 )
			{
				++pairsWithMatches;
				totalCorrespondences += r.getB().getInliers().size();
			}
		System.out.println( pairsWithMatches + " pairs carry correspondences, " + totalCorrespondences + " in total" );

		// --- baseline -------------------------------------------------------------------------

		System.out.println();
		System.out.println( "=== before any solve ===" );
		System.out.println( score( results, null ) );

		// --- weight-variant pass ----------------------------------------------------------------

		System.out.println();
		System.out.println( "=== APGO variants: per-link degrees of freedom x weighting ===" );

		final Map< String, Score > variantScores = new LinkedHashMap<>();
		final Map< String, Long > variantTimes = new LinkedHashMap<>();

		APGOSolver.Parameters best = null;
		HashMap< ViewId, Tile< AffineModel3D > > bestTiles = null;
		Score bestScore = null;
		long bestMs = 0;

		for ( final Model< ? > pairwiseModel : new Model< ? >[] { new AffineModel3D(), new RigidModel3D(), new TranslationModel3D() } )
			for ( final APGOSolver.Weighting weighting : APGOSolver.Weighting.values() )
			{
				final APGOSolver.Parameters params = new APGOSolver.Parameters();
				params.pairwiseModel = pairwiseModel;
				params.weighting = weighting;
				params.verbose = false;

				final APGOSolver.Result result = APGOSolver.compute(
						new InterestPointMatchCreator( results, labelMap ), fixedViews, new ArrayList<>(), params );

				final String key = pairwiseModel.getClass().getSimpleName() + " " + weighting;
				variantTimes.put( key, result.runtimeMs );

				Score s = null;
				if ( result.instability != null )
				{
					// Do not score it: the transforms are singular, and even converting them to an
					// AffineTransform3D throws, because that inverts on construction.
					System.out.println( "  " + key + " UNSTABLE: " + result.instability );
				}
				else
				{
					s = score( results, toTransforms( result.tiles ) );
				}
				variantScores.put( key, s );

				// Selected on the worst per-pair error, not the mean: past roughly 2 px a better
				// registration is not visible, so a lower maximum is worth more than a lower average.
				if ( s != null && ( bestScore == null || s.max < bestScore.max ) )
				{
					best = params;
					bestScore = s;
					bestTiles = result.tiles;
					bestMs = result.runtimeMs;
				}
			}

		System.out.println();
		for ( final Map.Entry< String, Score > e : variantScores.entrySet() )
			System.out.println( String.format( "APGO %-20s %6d ms | %s", e.getKey(), variantTimes.get( e.getKey() ),
					e.getValue() == null ? "unstable, not scored" : e.getValue() ) );

		System.out.println( "best APGO config (lowest max per-pair error): "
				+ best.pairwiseModel.getClass().getSimpleName() + " " + best.weighting );

		// --- the two solvers ----------------------------------------------------------------------

		// The production configuration: affine regularized with rigid at lambda 0.01, 3 px
		// allowed error, one round. Repeated, because GlobalOpt is not deterministic -
		// TileUtil.optimizeConcurrently is multi-threaded with a plateau stop, and repeats on
		// byte-identical input differ by more than the gap being measured.
		System.out.println();
		System.out.println( "=== GlobalOpt, affine regularized with rigid (lambda " + REGULARIZATION_LAMBDA
				+ "), maxError " + GLOBAL_OPT_MAX_ERROR + ", " + GLOBAL_OPT_REPEATS + " repeats ===" );

		final List< Score > globalOptScores = new ArrayList<>();
		final List< Long > globalOptTimes = new ArrayList<>();
		HashMap< ViewId, Tile< InterpolatedAffineModel3D< AffineModel3D, RigidModel3D > > > globalOptTiles = null;

		for ( int rep = 0; rep < GLOBAL_OPT_REPEATS; ++rep )
		{
			final long t0 = System.currentTimeMillis();
			final HashMap< ViewId, Tile< InterpolatedAffineModel3D< AffineModel3D, RigidModel3D > > > tiles =
					GlobalOpt.computeTiles( productionModel(), true,
							new InterestPointMatchCreator( results, labelMap ),
							new ConvergenceStrategy( GLOBAL_OPT_MAX_ERROR ), fixedViews,
							new ArrayList< Group< ViewId > >() );

			globalOptTimes.add( System.currentTimeMillis() - t0 );
			final Score s = score( results, toTransforms( tiles ) );
			globalOptScores.add( s );
			System.out.println( String.format( "GlobalOpt rep %d %6d ms | %s", rep, globalOptTimes.get( rep ), s ) );

			// keep the best repeat, judged the same way as APGO: lowest max per-pair error
			if ( globalOptTiles == null || s.max < java.util.Collections.min( globalOptScores,
					java.util.Comparator.comparingDouble( x -> x.max ) ).max + 1e-12 )
				globalOptTiles = tiles;
		}

		final Score globalOptScore = java.util.Collections.min( globalOptScores,
				java.util.Comparator.comparingDouble( s -> s.max ) );
		final long globalOptMs = java.util.Collections.min( globalOptTimes );

		// --- report -----------------------------------------------------------------------------

		System.out.println();
		System.out.println( "==================================================================" );
		System.out.println( String.format( "%-22s %10s  %s", "solver", "solve ms", "residual" ) );
		System.out.println( String.format( "%-22s %10s  %s", "none", "-", score( results, null ) ) );
		for ( int rep = 0; rep < globalOptScores.size(); ++rep )
			System.out.println( String.format( "%-22s %10d  %s", "GlobalOpt rep " + rep,
					globalOptTimes.get( rep ), globalOptScores.get( rep ) ) );
		System.out.println( String.format( "%-22s %10d  %s",
				"APGO " + best.pairwiseModel.getClass().getSimpleName() + "/" + best.weighting, bestMs, bestScore ) );
		System.out.println( "==================================================================" );

		// --- write both results for visual comparison ---------------------------------------------

		// Bare file names, not paths: saveWithFilename assembles them against the dataset's own
		// basePath. Handing it an absolute path appends it and builds a nested directory tree.
		String name = xml.substring( xml.lastIndexOf( '/' ) + 1 );
		if ( name.endsWith( ".xml" ) )
			name = name.substring( 0, name.length() - 4 );

		final String suffix = strip > 0 ? "_strip" + strip : "";

		save( data, viewIds, globalOptTiles, name + suffix + "_globalopt.xml",
				"GlobalOpt AffineModel3D regularized with an RigidModel3D, lambda = " + REGULARIZATION_LAMBDA );
		save( data, viewIds, bestTiles, name + suffix + "_apgo.xml",
				"APGO " + best.pairwiseModel.getClass().getSimpleName() + " " + best.weighting );
	}

	static Map< ViewId, AffineTransform3D > toTransforms( final HashMap< ViewId, ? extends Tile< ? > > tiles )
	{
		final Map< ViewId, AffineTransform3D > out = new HashMap<>();
		if ( tiles != null )
			for ( final Map.Entry< ViewId, ? extends Tile< ? > > e : tiles.entrySet() )
				out.put( e.getKey(),
						TransformationTools.getAffineTransform( ( mpicbg.models.Affine3D< ? > ) e.getValue().getModel() ) );
		return out;
	}

	/**
	 * Distance between corresponding interest points once each view's correction is applied.
	 *
	 * Reads <code>getL()</code> rather than <code>getW()</code> deliberately: the points are
	 * already in world coordinates, and mpicbg's optimization overwrites the world field, so
	 * <code>getW()</code> would carry whichever solver ran last.
	 *
	 * @param corrections per-view correction, or null to score the dataset as it stands
	 */
	static Score score(
			final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > results,
			final Map< ViewId, AffineTransform3D > corrections )
	{
		final Score score = new Score();
		final ArrayList< Double > perPair = new ArrayList<>();

		double weightedSum = 0, weightSum = 0, sum = 0;

		for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > r : results )
		{
			if ( r.getB().getInliers().isEmpty() )
				continue;

			final AffineTransform3D ta = corrections == null ? null : corrections.get( r.getA().getA() );
			final AffineTransform3D tb = corrections == null ? null : corrections.get( r.getA().getB() );

			double pairSum = 0;
			int pairCount = 0;

			for ( final mpicbg.models.PointMatch pm : r.getB().getInliers() )
			{
				final double[] a = pm.getP1().getL().clone();
				final double[] b = pm.getP2().getL().clone();

				if ( ta != null )
					ta.apply( a, a );
				if ( tb != null )
					tb.apply( b, b );

				final double d = Math.sqrt(
						( a[ 0 ] - b[ 0 ] ) * ( a[ 0 ] - b[ 0 ] ) +
						( a[ 1 ] - b[ 1 ] ) * ( a[ 1 ] - b[ 1 ] ) +
						( a[ 2 ] - b[ 2 ] ) * ( a[ 2 ] - b[ 2 ] ) );

				final double w = pm.getWeight();
				weightedSum += d * w;
				weightSum += w;
				sum += d;

				pairSum += d;
				++pairCount;
				++score.numCorrespondences;
			}

			perPair.add( pairSum / pairCount );
		}

		java.util.Collections.sort( perPair );

		score.numPairs = perPair.size();
		score.weightedMean = weightSum > 0 ? weightedSum / weightSum : Double.NaN;
		score.mean = score.numCorrespondences > 0 ? sum / score.numCorrespondences : Double.NaN;
		score.median = perPair.isEmpty() ? Double.NaN : perPair.get( perPair.size() / 2 );
		score.p95 = perPair.isEmpty() ? Double.NaN : perPair.get( Math.min( perPair.size() - 1, ( int ) ( perPair.size() * 0.95 ) ) );
		score.max = perPair.isEmpty() ? Double.NaN : perPair.get( perPair.size() - 1 );

		return score;
	}

	/**
	 * Store a solve into the dataset, write it out, then undo it so the next solve starts from
	 * the same state. {@code preconcatenateTransform} inserts at the head of the list, so
	 * dropping index 0 reverses it exactly.
	 */
	static void save(
			final SpimData2 data,
			final List< ViewId > viewIds,
			final HashMap< ViewId, ? extends Tile< ? > > tiles,
			final String xmlFileName,
			final String description ) throws Exception
	{
		if ( tiles == null )
		{
			System.out.println( "no result to write for " + description );
			return;
		}

		for ( final ViewId v : viewIds )
			TransformationTools.storeTransformation(
					data.getViewRegistrations().getViewRegistration( v ), v, tiles.get( v ), null, description );

		System.out.println( "wrote " + new XmlIoSpimData2().saveWithFilename( data, xmlFileName ) );

		for ( final ViewId v : viewIds )
		{
			final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( v );
			vr.getTransformList().remove( 0 );
			vr.updateModel();
		}
	}

	@SuppressWarnings( "unused" )
	private static String join( final double[] v )
	{
		return Arrays.toString( v );
	}
}
