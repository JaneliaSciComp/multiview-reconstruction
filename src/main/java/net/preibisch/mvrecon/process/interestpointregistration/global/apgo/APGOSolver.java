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
package net.preibisch.mvrecon.process.interestpointregistration.global.apgo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import mpicbg.models.Affine3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.Model;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOpt;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Global optimization on the affine Lie group, after bigstream's
 * <a href="https://github.com/JaneliaSciComp/bigstream/blob/499c114/bigstream/stitch.py#L483">
 * <code>stitch.py::find_tile_transforms</code></a> ("APGO").
 *
 * Instead of iterating point matches like {@link GlobalOpt} does through mpicbg's
 * {@link mpicbg.models.TileConfiguration}, this collapses each link to a single pairwise
 * affine and solves for the per-view transforms directly: a matrix-logarithm
 * initialization followed by Gauss-Newton iterations on the manifold.
 *
 * <h2>Parameterization</h2>
 *
 * The reference minimizes <code>R = T&#8315;&#185; &#183; M_b &#183; M_a</code> and inverts
 * every tile that sat in the fixed slot at the end. Substituting <code>G = M</code> for
 * movers and <code>G = M&#8315;&#185;</code> for fixers shows the model is really
 * <code>T &#8776; G_b &#183; G_a&#8315;&#185;</code> - but that only closes if each tile is
 * <em>always</em> fixed or <em>always</em> moving, which bigstream guarantees with a
 * checkerboard 2-coloring of a regular grid with face-only neighbors. This project's pair
 * graph comes from bounding-box overlap detection and includes corner overlaps, so it has
 * odd cycles and is not bipartite. We therefore use the general form.
 *
 * With <code>T_ab</code> fit from view <em>a</em>'s matches with partner <em>b</em>
 * (mpicbg fits p1 to p2, and the points are already in world coordinates), agreement after
 * applying the corrections means <code>G_a &#183; p_a = G_b &#183; p_b</code> with
 * <code>p_b = T_ab &#183; p_a</code>, hence
 *
 * <pre>
 * T_ab = G_b&#8315;&#185; G_a      R_ab = T_ab&#8315;&#185; G_b&#8315;&#185; G_a      min &#931; w_ab &#8214;log R_ab&#8214;&#178;
 * </pre>
 *
 * Under left perturbations <code>G &#8592; exp(u) G</code> this linearizes to
 *
 * <pre>
 * u_a - u_b = -Ad_{G_a}( log R_ab )
 * </pre>
 *
 * i.e. the Jacobian is the weighted signed incidence matrix of the pair graph tensored with
 * the 12x12 identity. The reference's <code>kron(conj&#8315;&#185;, conj&#7488;)</code> block
 * is the same thing expressed in its own parameterization, where <code>G_b</code> enters
 * un-inverted and so needs an extra conjugation. Because the blocks are scalar here, all 12
 * affine parameters share one operator and differ only in their right-hand side - see
 * {@link APGONormalEquations}.
 *
 * @author bigstream algorithm by Greg M. Fleishman; Java implementation for multiview-reconstruction
 */
public class APGOSolver
{
	public static enum Weighting
	{
		/**
		 * Every link counts the same, and the twelve affine parameters are solved independently.
		 * This is the reference's structure and is what the bigstream cross-check validates.
		 *
		 * Only <em>uniform</em> scalar weights are offered because nothing else was stable: on a
		 * split dataset, weighting by match count (a 10^4 spread) or by the link's own fit residual
		 * both drove the weighted Laplacian near-singular and diverged. See {@link #INFORMATION}
		 * for why no scalar can work here.
		 */
		UNIFORM,

		/**
		 * Per-link 12x12 information matrix instead of a scalar - the only weighting that can
		 * express <em>which</em> parameters a link measures.
		 *
		 * A scalar scales the whole 12-dimensional residual uniformly, so it can say "trust this
		 * link less" but not "this link determines translation but not shear". That distinction is
		 * the whole problem on a split dataset: overlap regions are thin slabs, so the affine
		 * columns along the thin axis are constrained hundreds of times more weakly than
		 * translation, within the same link.
		 *
		 * The per-link fit is a linear least squares whose design matrix has rows
		 * <code>I3 (x) [p;1]^T</code>, so its information is <code>Lambda = I3 (x) M</code> with
		 * <code>M = sum_i w_i [p_i;1][p_i;1]^T</code>, the weighted second moment of the source
		 * points - one 4x4 accumulation per link, cheaper than the fit beside it. Minimizing
		 * <code>sum r^T Lambda r</code> rather than <code>sum w |r|^2</code> makes the per-link
		 * collapse lossless to second order, because it is the same quadratic form as
		 * <code>sum_i |G_a p_i - G_b q_i|^2</code> - what GlobalOpt keeps by holding the point
		 * matches. It also subsumes the match count and the per-label weights, both of which are
		 * already inside M, so no scalar goes on top.
		 *
		 * Measured on 1678 views this turns affine links from the worst configuration into the
		 * best, and beats GlobalOpt on both labels. Uses {@link APGOBlockNormalEquations}; the
		 * twelve parameters no longer decouple.
		 */
		INFORMATION
	}



	public static class Parameters
	{
		/**
		 * Defaults to {@link Weighting#INFORMATION}, which is both the most accurate and, once the
		 * inner solve is not over-converged, the fastest useful setting.
		 */
		public Weighting weighting = Weighting.INFORMATION;

		/** Gauss-Newton iterations. The reference defaults to 10; {@link #convergenceThreshold} usually stops earlier. */
		public int maxIterations = 10;

		/**
		 * Stop Gauss-Newton once the algebra residual improves by less than this fraction.
		 *
		 * How much Gauss-Newton is worth depends entirely on how nonlinear the problem is, which
		 * varies by dataset: on synthetic data far from the solution it takes all ten iterations to
		 * reach machine precision, while on an already-close split dataset the residual moves 0.003%
		 * over ten iterations and the matrix-log initialization has effectively already solved it.
		 * Rather than pick a fixed count for one dataset, stop when iterating stops paying.
		 */
		public double convergenceThreshold = 1e-4;

		/**
		 * Scale applied to each Gauss-Newton step before exponentiating. 1.0 is the full step.
		 *
		 * Only a diagnostic: the step is truncated at first order twice over - once by BCH, since
		 * <code>exp(-u_b) exp(u_a) != exp(u_a - u_b)</code>, and once by the linearization itself.
		 * Both errors are second order in the step, so halving the step and doubling the iteration
		 * count must converge to the same place if those truncations are harmless, and to a
		 * different one if they are not.
		 */
		public double stepDamping = 1.0;



		/** A link needs at least this many point matches to be usable at all. */
		public int minMatches = 4;

		/**
		 * Degrees of freedom of the per-link transform. The solve is always over full affines;
		 * this only limits what a single link is allowed to claim to have measured.
		 *
		 * Worth lowering. Collapsing a link to a 12-parameter affine assumes the link actually
		 * determines twelve parameters, which a thin overlap region with sparse correspondences
		 * does not - the ill-determined directions come back as noise that the global solve then
		 * propagates. bigstream's links are image-correlation alignments over large overlap
		 * volumes and do not have this problem.
		 */
		/**
		 * Prototype of the model fit to a single link's point matches; copied per link, never
		 * mutated. The plugin passes whatever transformation model the user selected.
		 *
		 * This limits only what one link is allowed to claim to have measured. The <em>solve</em> is
		 * always over full affines - that is intrinsic to the method - so selecting a lower-DOF
		 * model here does not constrain the per-view output.
		 *
		 * With {@link Weighting#INFORMATION} a full affine is the best choice: the information
		 * matrix already discounts the directions a link cannot measure, so restricting the model
		 * only throws away the directions it can. Lowering it helps only under a scalar weight.
		 */
		public Model< ? > pairwiseModel = new AffineModel3D();





		/**
		 * Weak prior toward "no change", relative to the mean diagonal of the block Laplacian.
		 * Only used by {@link Weighting#INFORMATION}, where a direction no link measures leaves a
		 * genuine null space that has to resolve to something.
		 */
		public double tikhonov = 1e-6;

		/**
		 * Order of the <code>dexp^-1</code> correction applied to the Jacobian, 0 to disable.
		 *
		 * The residual of a link is <code>rho = r + dexp^-1_{-r}( Ad_{G_a^-1}( u_a - u_b ) )</code>,
		 * because <code>log( R exp(d) ) = r + dexp^-1_{-r}(d) + O(d^2)</code> rather than
		 * <code>r + d</code>. The reference drops this and uses the bare adjoint. Note the
		 * <code>dexp</code> it does apply, to the residual, is provably a no-op: it is applied to
		 * <code>r</code> itself and <code>ad_r(r) = 0</code>. The correction only bites on the
		 * <em>solved</em> step, which is not parallel to <code>r</code> once many links compromise.
		 *
		 * Only used by {@link Weighting#INFORMATION} - it mixes the twelve parameters, which the
		 * scalar path's decoupling cannot represent.
		 */
		public int jacobianDexpOrder = 2;

		/**
		 * Inner conjugate-gradient iteration cap.
		 *
		 * The block system is a graph Laplacian, whose condition number grows with the graph
		 * diameter squared and again with the per-link information anisotropy, so on a real
		 * dataset CG cannot reach a tight tolerance at any sane cost - it ran to 10000 iterations
		 * every time. It does not need to: this is the inner solve of a Gauss-Newton step, and the
		 * outer loop absorbs an inexact one. Measured on 1678 views, going from 10000 to 50
		 * iterations changed the worst bead error by 0.05 px and saved 117 s.
		 */
		public int cgMaxIterations = 250;

		public double cgTolerance = 1e-12;

		public boolean verbose = true;
	}

	public static class Result
	{
		public HashMap< ViewId, Tile< AffineModel3D > > tiles;

		/** Per Gauss-Newton iteration: <code>{ group residual, algebra residual }</code>, as the reference reports. */
		public final ArrayList< double[] > residuals = new ArrayList<>();

		public int numViews, numTiles, numPairs, numDroppedPairs, numComponents, numUnconstrainedTiles;

		/** Links dropped because their pairwise transform was singular, mirrored, or not finite. */
		public int numDegenerateLinks;

		/** Set when the solve did not stay numerically sane; the transforms should not be trusted. */
		public String instability;

		/** Links fit with the requested model, and those that needed a lower-DOF fallback. */
		public int numRequestedFits, numRigidFallbacks, numTranslationFallbacks;
		public long runtimeMs;
	}

	/** Convenience overload mirroring {@link GlobalOpt#computeTiles}. */
	public static HashMap< ViewId, Tile< AffineModel3D > > computeTiles(
			final PointMatchCreator pmc,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn,
			final Parameters params )
	{
		return compute( pmc, fixedViews, groupsIn, params ).tiles;
	}

	public static Result compute(
			final PointMatchCreator pmc,
			final Collection< ViewId > fixedViews,
			final Collection< Group< ViewId > > groupsIn,
			final Parameters params )
	{
		final long start = System.currentTimeMillis();
		final Result result = new Result();

		// exactly the same setup GlobalOpt does: group merging, one Tile per group,
		// assignWeights, assignPointMatches, connectivity
		final Pair< HashMap< ViewId, Tile< AffineModel3D > >, ArrayList< Group< ViewId > > > init =
				GlobalOpt.initGlobalOpt( new AffineModel3D(), pmc, fixedViews, groupsIn );

		final HashMap< ViewId, Tile< AffineModel3D > > map = init.getA();
		result.tiles = map;

		final ArrayList< ViewId > views = new ArrayList<>( map.keySet() );
		Collections.sort( views );
		result.numViews = views.size();

		// grouped views share one Tile, so index by identity in a deterministic order
		final IdentityHashMap< Tile< AffineModel3D >, Integer > index = new IdentityHashMap<>();
		final ArrayList< Tile< AffineModel3D > > tiles = new ArrayList<>();
		for ( final ViewId v : views )
		{
			final Tile< AffineModel3D > t = map.get( v );
			if ( !index.containsKey( t ) )
			{
				index.put( t, tiles.size() );
				tiles.add( t );
			}
		}
		final int numTiles = tiles.size();
		result.numTiles = numTiles;

		final HashSet< Integer > fixedTiles = new HashSet<>();
		if ( fixedViews != null )
			for ( final ViewId v : fixedViews )
			{
				final Tile< AffineModel3D > t = map.get( v );
				if ( t != null )
					fixedTiles.add( index.get( t ) );
			}

		// --- collapse each link to one pairwise affine ------------------------------------

		final ArrayList< int[] > pairList = new ArrayList<>();
		final ArrayList< double[][] > transformList = new ArrayList<>();
		final ArrayList< double[][] > logList = new ArrayList<>();
		final ArrayList< Double > weightList = new ArrayList<>();

		/** Per link, the weighted second moment of its source points, in raw world coordinates. */
		final ArrayList< double[][] > momentList = new ArrayList<>();
		final boolean useInformation = params.weighting == Weighting.INFORMATION;

		final double[] pointSum = new double[ 3 ];
		double pointSqSum = 0;
		long pointCount = 0;

		// Which tile owns each point.
		//
		// mpicbg's Tile.findConnectedTile(pm) answers this by scanning every match of every
		// connected tile looking for an identity hit, i.e. O(partners x their matches) per call.
		// Over 2M matches that dominated the whole solve. A tile's own matches always carry p1 on
		// that tile (PointMatch.flip keeps the Point objects, only swapping the roles), so one
		// identity map built in a single pass answers the same question in O(1).
		final IdentityHashMap< Point, Integer > owner = new IdentityHashMap<>();
		for ( int i = 0; i < numTiles; ++i )
			for ( final PointMatch pm : tiles.get( i ).getMatches() )
				owner.put( pm.getP1(), i );

		for ( int i = 0; i < numTiles; ++i )
		{
			final Tile< AffineModel3D > ti = tiles.get( i );

			// bucket this tile's matches by partner; TreeMap keeps the order deterministic
			final TreeMap< Integer, ArrayList< PointMatch > > buckets = new TreeMap<>();

			for ( final PointMatch pm : ti.getMatches() )
			{
				final Integer j = owner.get( pm.getP2() );

				// each link shows up from both sides; keep the one where p1 belongs to the
				// lower-indexed tile so that T maps a -> b
				if ( j == null || j <= i )
					continue;

				buckets.computeIfAbsent( j, k -> new ArrayList<>() ).add( pm );
			}

			for ( final Map.Entry< Integer, ArrayList< PointMatch > > e : buckets.entrySet() )
			{
				final ArrayList< PointMatch > matches = e.getValue();

				if ( matches.size() < params.minMatches )
				{
					++result.numDroppedPairs;
					continue;
				}

				final double[][] t = fitPairwise( matches, result, params.pairwiseModel );

				if ( t == null )
				{
					++result.numDegenerateLinks;
					++result.numDroppedPairs;
					continue;
				}

				// Take the logarithm here rather than in stage 1, so a link whose transform the
				// logarithm cannot handle is dropped like any other unusable link. Every view is
				// coupled through the incidence matrix, so letting one NaN through would silently
				// turn the whole solution into NaN.
				double[][] logT;
				try
				{
					logT = Mat4.log( t );
				}
				catch ( final RuntimeException degenerate )
				{
					logT = null;
				}

				if ( logT == null || !isFinite( logT ) )
				{
					++result.numDegenerateLinks;
					++result.numDroppedPairs;
					continue;
				}

				final double weight = 1;

				for ( final PointMatch pm : matches )
				{
					final double[] p1 = pm.getP1().getL(), p2 = pm.getP2().getL();
					for ( int d = 0; d < 3; ++d )
					{
						pointSum[ d ] += p1[ d ] + p2[ d ];
						pointSqSum += p1[ d ] * p1[ d ] + p2[ d ] * p2[ d ];
					}
					pointCount += 2;
				}

				if ( useInformation )
				{
					// M = sum_i w_i [p;1][p;1]^T, accumulated in world coordinates and mapped into
					// the normalized frame below by M' = S M S^T
					final double[][] m = new double[ 4 ][ 4 ];
					for ( final PointMatch pm : matches )
					{
						final double[] l = pm.getP1().getL();
						final double[] ph = { l[ 0 ], l[ 1 ], l[ 2 ], 1 };
						final double pw = pm.getWeight();
						for ( int r = 0; r < 4; ++r )
							for ( int c = 0; c < 4; ++c )
								m[ r ][ c ] += pw * ph[ r ] * ph[ c ];
					}
					momentList.add( m );
				}

				pairList.add( new int[] { i, e.getKey() } );
				transformList.add( t );
				logList.add( logT );
				weightList.add( weight );
			}
		}

		// Normalize the coordinate frame (translate to the centroid, scale to unit RMS radius)
		// before solving, and undo it afterwards. Conjugation is exact - R' = S R S^-1 is the
		// identity exactly when R is - so this changes conditioning and nothing else.
		//
		// What it does and does not buy, measured rather than assumed:
		//
		//  - Stage 1 is unaffected. Conjugation acts triangularly on the algebra: the linear
		//    block of log T is invariant and only the translation block shifts. Since the twelve
		//    parameters are solved as twelve independent scalar problems, their relative scaling
		//    never enters, and the stage-1 transforms come out bit-identical either way.
		//  - Gauss-Newton is affected, because Ad_{G_a} mixes the linear and translation blocks.
		//    On synthetic data at this dataset's world offset, the un-normalized residual is flat
		//    across all ten iterations while the normalized one falls ~3x.
		//  - Reported residuals become interpretable, since they are then in units of the data
		//    extent rather than ~1e4 world units.
		//
		// bigstream never needs this: it aligns each pair in tile-local coordinates (its
		// fix_origin / mov_origin arguments), which are already centered and O(tile size).
		final double[] centroid = new double[ 3 ];
		double radius = 1;
		if ( pointCount > 0 )
		{
			for ( int d = 0; d < 3; ++d )
				centroid[ d ] = pointSum[ d ] / pointCount;

			final double meanSq = pointSqSum / pointCount
					- ( centroid[ 0 ] * centroid[ 0 ] + centroid[ 1 ] * centroid[ 1 ] + centroid[ 2 ] * centroid[ 2 ] );
			radius = meanSq > 0 ? Math.sqrt( meanSq ) : 1;
		}

		final double[][] center = Mat4.identity();
		for ( int d = 0; d < 3; ++d )
		{
			center[ d ][ d ] = 1.0 / radius;
			center[ d ][ 3 ] = -centroid[ d ] / radius;
		}
		final double[][] centerInv = Mat4.inverse( center );

		if ( params.verbose )
			IOFunctions.println( String.format( "APGO: normalizing on centroid [%.1f, %.1f, %.1f], RMS radius %.1f",
					centroid[ 0 ], centroid[ 1 ], centroid[ 2 ], radius ) );

		for ( int p = 0; p < transformList.size(); ++p )
		{
			final double[][] recentered = Mat4.mult( center, Mat4.mult( transformList.get( p ), centerInv ) );
			transformList.set( p, recentered );
			logList.set( p, Mat4.log( recentered ) );

			// points transform as p' = S p, so the second moment transforms as M' = S M S^T
			if ( useInformation )
				momentList.set( p, Mat4.mult( center, Mat4.mult( momentList.get( p ), transpose( center ) ) ) );
		}

		// Unusable links were already dropped while bucketing (too few matches, or a transform the
		// logarithm cannot take); everything that reached here is kept.
		final int numPairs = pairList.size();
		result.numPairs = numPairs;

		final int[] pairA = new int[ numPairs ];
		final int[] pairB = new int[ numPairs ];
		final double[] w = new double[ numPairs ];
		final double[][][] transforms = new double[ numPairs ][][];
		final double[][][] logT = new double[ numPairs ][][];
		final double[][][] moments = new double[ numPairs ][][];

		for ( int p = 0; p < numPairs; ++p )
		{
			pairA[ p ] = pairList.get( p )[ 0 ];
			pairB[ p ] = pairList.get( p )[ 1 ];
			w[ p ] = weightList.get( p );
			transforms[ p ] = transformList.get( p );
			logT[ p ] = logList.get( p );
			if ( useInformation )
				moments[ p ] = momentList.get( p );
		}

		if ( numPairs == 0 )
		{
			IOFunctions.println( "APGO: no usable links, nothing to optimize." );
			result.runtimeMs = System.currentTimeMillis() - start;
			return result;
		}

		// --- gauge: fix one tile per connected component -----------------------------------

		final int[] parent = new int[ numTiles ];
		for ( int i = 0; i < numTiles; ++i )
			parent[ i ] = i;
		for ( int p = 0; p < numPairs; ++p )
			union( parent, pairA[ p ], pairB[ p ] );

		final boolean[] constrained = new boolean[ numTiles ];
		for ( int p = 0; p < numPairs; ++p )
			constrained[ pairA[ p ] ] = constrained[ pairB[ p ] ] = true;

		// prefer a caller-requested fixed view, otherwise the lowest index in the component
		final HashMap< Integer, Integer > gauge = new HashMap<>();
		for ( int i = 0; i < numTiles; ++i )
		{
			if ( !constrained[ i ] )
			{
				++result.numUnconstrainedTiles;
				continue;
			}

			final int root = find( parent, i );
			final Integer current = gauge.get( root );

			if ( current == null || ( fixedTiles.contains( i ) && !fixedTiles.contains( current ) ) )
				gauge.put( root, i );
		}
		result.numComponents = gauge.size();

		final int[] column = new int[ numTiles ];
		int numFree = 0;
		for ( int i = 0; i < numTiles; ++i )
			column[ i ] = ( !constrained[ i ] || gauge.get( find( parent, i ) ) == i ) ? -1 : numFree++;

		final APGONormalEquations ne = new APGONormalEquations(
				pairA, pairB, w, column, numFree, params.cgMaxIterations, params.cgTolerance );

		if ( params.verbose )
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): APGO: " + numTiles + " view-tiles, "
					+ numPairs + " links (" + result.numDroppedPairs + " dropped, of which "
					+ result.numDegenerateLinks + " degenerate), " + result.numComponents
					+ " connected component(s), " + numFree + " free views, "
					+ result.numUnconstrainedTiles + " unconstrained. Per-link fits: "
					+ result.numRequestedFits + " " + params.pairwiseModel.getClass().getSimpleName()
					+ ", " + result.numRigidFallbacks + " rigid fallback, "
					+ result.numTranslationFallbacks + " translation fallback." );

		double maxLin = 0, maxTrans = 0, minWeight = Double.MAX_VALUE, maxWeight = 0;
		for ( int p = 0; p < numPairs; ++p )
		{
			for ( int r = 0; r < 3; ++r )
			{
				for ( int c = 0; c < 3; ++c )
					maxLin = Math.max( maxLin, Math.abs( logT[ p ][ r ][ c ] ) );
				maxTrans = Math.max( maxTrans, Math.abs( logT[ p ][ r ][ 3 ] ) );
			}
			minWeight = Math.min( minWeight, w[ p ] );
			maxWeight = Math.max( maxWeight, w[ p ] );
		}

		if ( params.verbose )
			IOFunctions.println( String.format( "APGO: observations max |log T| linear %.4g, translation %.4g; link weights %.3e .. %.3e",
					maxLin, maxTrans, minWeight, maxWeight ) );

		// --- stage 1: zeroth order BCH initialization ---------------------------------------

		final double[] obs = new double[ numPairs ];
		final double[][][] tangents = new double[ numTiles ][ 4 ][ 4 ];

		final APGOBlockNormalEquations block = useInformation
				? new APGOBlockNormalEquations( pairA, pairB,
						informationBlocks( moments, null ), column, numFree,
						params.tikhonov, params.cgMaxIterations, params.cgTolerance )
				: null;

		if ( useInformation )
		{
			// x_a - x_b ~ log T, weighted by Lambda: sum Lambda (x_a - x_b) = sum Lambda log T
			final double[] rhs = new double[ numFree * APGOBlockNormalEquations.D ];
			for ( int p = 0; p < numPairs; ++p )
			{
				final double[] g = applyInformation( moments[ p ], vec( logT[ p ] ) );
				scatter( rhs, column[ pairA[ p ] ], g, +1 );
				scatter( rhs, column[ pairB[ p ] ], g, -1 );
			}
			unvec( block.solve( rhs ), column, tangents );
		}
		else
		{
			solvePerParameter( ne, logT, obs, column, tangents );
		}

		if ( params.verbose )
			IOFunctions.println( String.format( "APGO: stage 1 linear solve, worst relative residual %.3e, %d solve(s) hit the iteration cap",
					useInformation ? block.worstRelativeResidual() : ne.worstRelativeResidual(),
					useInformation ? block.numNotConverged() : ne.numNotConverged() ) );

		final double[][][] g = new double[ numTiles ][][];
		double worstLinear = 0, worstTranslation = 0;
		for ( int i = 0; i < numTiles; ++i )
		{
			g[ i ] = Mat4.exp( tangents[ i ] );
			for ( int r = 0; r < 3; ++r )
			{
				for ( int c = 0; c < 3; ++c )
					worstLinear = Math.max( worstLinear, Math.abs( g[ i ][ r ][ c ] - ( r == c ? 1 : 0 ) ) );
				worstTranslation = Math.max( worstTranslation, Math.abs( g[ i ][ r ][ 3 ] ) );
			}
		}

		// A per-view correction is meant to be a small adjustment. Anything past this is the
		// weighted Laplacian being near-singular, not a real measurement, and every downstream
		// number will be meaningless - so say so loudly rather than returning it quietly.
		//
		// Both parts have to be checked. A translation-only pairwise model keeps every linear
		// block at the identity no matter how badly the solve diverges, so a linear-only test
		// waves through a result whose translations are 1e18. The translation bound is in units
		// of the normalized frame, where the data has unit RMS radius by construction.
		if ( worstLinear > 1.0 || worstTranslation > 100.0 )
		{
			result.instability = String.format(
					"initialization is implausible: worst linear deviation %.4g, worst translation %.4g "
							+ "in units of the data radius (link weights span %.1fx)",
					worstLinear, worstTranslation, maxWeight / Math.max( minWeight, Double.MIN_NORMAL ) );
			IOFunctions.println( "APGO: " + result.instability );
		}
		else if ( params.verbose )
		{
			IOFunctions.println( String.format(
					"APGO: initialization worst linear deviation %.4g, worst translation %.4g data radii",
					worstLinear, worstTranslation ) );
		}

		// --- stage 2: Gauss-Newton -----------------------------------------------------------

		final double[][][] tInv = new double[ numPairs ][][];
		for ( int p = 0; p < numPairs; ++p )
			tInv[ p ] = Mat4.inverse( transforms[ p ] );

		final double[][][] rhs = new double[ numPairs ][][];
		// the dexp-corrected residual before Ad and negation; the block path applies those itself
		final double[][][] residualTangent = new double[ numPairs ][][];
		final double[][][] update = new double[ numTiles ][ 4 ][ 4 ];

		for ( int iteration = 0; iteration < params.maxIterations; ++iteration )
		{
			double groupResidual = 0, algebraResidual = 0;

			double[][][] gInvShared = null;

			try
			{
				final double[][][] gInv = new double[ numTiles ][][];
				for ( int i = 0; i < numTiles; ++i )
					gInv[ i ] = Mat4.inverse( g[ i ] );
				gInvShared = gInv;

				for ( int p = 0; p < numPairs; ++p )
				{
					final double[][] a = g[ pairA[ p ] ];
					final double[][] r = Mat4.mult( tInv[ p ], Mat4.mult( gInv[ pairB[ p ] ], a ) );
					final double[][] tangent = Mat4.log( r );

					groupResidual += w[ p ] * Mat4.normFrobenius( r );
					algebraResidual += w[ p ] * Mat4.normFrobenius( tangent );

					// u_a - u_b = -Ad_{G_a}( dexp-corrected log R )
					final double[][] corrected = dexp( r, tangent, 2 );
					residualTangent[ p ] = corrected;
					rhs[ p ] = Mat4.scale( ad( a, corrected ), -1 );
				}
			}
			catch ( final RuntimeException e )
			{
				// A transform or a link residual is no longer invertible, which means the estimate
				// has already run away - almost always because the weighted graph is near-singular.
				// Stop here and say so rather than throwing: the caller can still score the result
				// and see for itself how bad it is.
				result.instability = "matrix became singular at Gauss-Newton iteration " + iteration
						+ " (" + e.getMessage() + ")";
				IOFunctions.println( "APGO: " + result.instability + " - stopping early." );
				break;
			}

			final double previousResidual = result.residuals.isEmpty()
					? Double.MAX_VALUE : result.residuals.get( result.residuals.size() - 1 )[ 1 ];

			result.residuals.add( new double[] { groupResidual, algebraResidual } );

			if ( Math.abs( previousResidual - algebraResidual ) < params.convergenceThreshold * algebraResidual )
			{
				if ( params.verbose )
					IOFunctions.println( String.format(
							"APGO: converged after %d Gauss-Newton iteration(s), residual %.6f", iteration, algebraResidual ) );
				break;
			}

			if ( params.verbose )
				IOFunctions.println( String.format( "(%s): APGO iteration %2d: group residual %.6f, algebra residual %.6f",
						new Date( System.currentTimeMillis() ), iteration, groupResidual, algebraResidual ) );

			for ( final double[][] u : update )
				for ( final double[] row : u )
					java.util.Arrays.fill( row, 0 );

			if ( useInformation )
			{
				// rho = r + Ad_{G_a^-1}( u_a - u_b );  minimize rho^T Lambda rho
				// => sum A^T Lambda A ( u_a - u_b ) = - sum A^T Lambda r
				// Ad depends only on G_a, so build one per tile and share it across that tile's
				// links - 1678 instead of 13370, and gInv is already to hand
				final double[][][] adjointPerTile = new double[ numTiles ][][];
				final double[][][] adjoints = new double[ numPairs ][][];
				for ( int p = 0; p < numPairs; ++p )
				{
					final int t = pairA[ p ];
					if ( adjointPerTile[ t ] == null )
						adjointPerTile[ t ] = adjointMatrix( gInvShared[ t ], g[ t ] );

					// Ad depends only on G_a and is shared across that tile's links; dexp^-1
					// depends on this link's own residual, so it has to be applied per link.
					adjoints[ p ] = params.jacobianDexpOrder > 0
							? mult12( dexpInvMatrix( residualTangent[ p ], params.jacobianDexpOrder ),
									adjointPerTile[ t ] )
							: adjointPerTile[ t ];
				}

				final double[][][] blocks = informationBlocks( moments, adjoints );

				final APGOBlockNormalEquations gn = new APGOBlockNormalEquations( pairA, pairB,
						blocks, column, numFree,
						params.tikhonov, params.cgMaxIterations, params.cgTolerance );

				final double[] b = new double[ numFree * APGOBlockNormalEquations.D ];
				for ( int p = 0; p < numPairs; ++p )
				{
					final double[] lr = applyInformation( moments[ p ], vec( residualTangent[ p ] ) );
					final double[] gvec = new double[ APGOBlockNormalEquations.D ];
					for ( int i = 0; i < APGOBlockNormalEquations.D; ++i )
					{
						double acc = 0;
						for ( int j = 0; j < APGOBlockNormalEquations.D; ++j )
							acc += adjoints[ p ][ j ][ i ] * lr[ j ];
						gvec[ i ] = acc;
					}
					scatter( b, column[ pairA[ p ] ], gvec, -1 );
					scatter( b, column[ pairB[ p ] ], gvec, +1 );
				}
				unvec( gn.solve( b ), column, update );
			}
			else
			{
				solvePerParameter( ne, rhs, obs, column, update );
			}

			for ( int i = 0; i < numTiles; ++i )
				g[ i ] = Mat4.mult( Mat4.exp( params.stepDamping == 1.0
						? update[ i ] : Mat4.scale( update[ i ], params.stepDamping ) ), g[ i ] );
		}

		for ( int i = 0; i < numTiles; ++i )
		{
			// back out of the normalized frame
			final double[][] m = Mat4.mult( centerInv, Mat4.mult( g[ i ], center ) );
			final AffineModel3D model = new AffineModel3D();
			model.set( m[ 0 ][ 0 ], m[ 0 ][ 1 ], m[ 0 ][ 2 ], m[ 0 ][ 3 ],
					m[ 1 ][ 0 ], m[ 1 ][ 1 ], m[ 1 ][ 2 ], m[ 1 ][ 3 ],
					m[ 2 ][ 0 ], m[ 2 ][ 1 ], m[ 2 ][ 2 ], m[ 2 ][ 3 ] );
			tiles.get( i ).setModel( model );
		}

		result.runtimeMs = System.currentTimeMillis() - start;
		return result;
	}

	/**
	 * The 12 affine parameters are independent right-hand sides over one operator, so loop
	 * over them and scatter each solution back into the per-tile 4x4.
	 */
	private static void solvePerParameter(
			final APGONormalEquations ne,
			final double[][][] perPair,
			final double[] obs,
			final int[] column,
			final double[][][] perTile )
	{
		for ( int row = 0; row < 3; ++row )
			for ( int col = 0; col < 4; ++col )
			{
				for ( int p = 0; p < obs.length; ++p )
					obs[ p ] = perPair[ p ][ row ][ col ];

				final double[] solution = ne.solve( obs );

				for ( int i = 0; i < column.length; ++i )
					if ( column[ i ] >= 0 )
						perTile[ i ][ row ][ col ] = solution[ column[ i ] ];
			}
	}

	/**
	 * Pairwise transform from a link's point matches, mapping p1 to p2.
	 *
	 * Fresh {@link Point}s are built from the local coordinates because mpicbg's
	 * <code>fit(Collection)</code> reads <code>p1.getL()</code> against
	 * <code>p2.getW()</code>, and a previous solver run over the same
	 * {@link PointMatchCreator} will have overwritten the world coordinates.
	 *
	 * Falls back from affine to rigid to translation: correspondences generated at a split
	 * boundary are coplanar, which an affine fit rejects as ill-defined.
	 *
	 * @return the 4x4 transform, or null if even a translation could not be fit
	 */
	private static double[][] fitPairwise( final List< PointMatch > matches, final Result result,
			final Model< ? > prototype )
	{
		final ArrayList< PointMatch > clean = new ArrayList<>( matches.size() );
		for ( final PointMatch pm : matches )
			clean.add( new PointMatch(
					new Point( pm.getP1().getL().clone() ),
					new Point( pm.getP2().getL().clone() ),
					pm.getWeight() ) );

		Object fitted = null;

		try
		{
			fitted = fit( prototype, clean );
			++result.numRequestedFits;
		}
		catch ( NotEnoughDataPointsException | IllDefinedDataPointsException e1 )
		{
			// Correspondences generated at a split boundary are coplanar, which anything above
			// rigid rejects as ill-defined. Without this the link would simply vanish.
			try
			{
				fitted = fit( new RigidModel3D(), clean );
				++result.numRigidFallbacks;
			}
			catch ( NotEnoughDataPointsException | IllDefinedDataPointsException e2 )
			{
				try
				{
					fitted = fit( new TranslationModel3D(), clean );
					++result.numTranslationFallbacks;
				}
				catch ( NotEnoughDataPointsException | IllDefinedDataPointsException e3 )
				{
					return null;
				}
			}
		}

		final double[][] m3x4 = new double[ 3 ][ 4 ];
		( ( Affine3D< ? > ) fitted ).toMatrix( m3x4 );

		final double[][] m = Mat4.identity();
		for ( int r = 0; r < 3; ++r )
			System.arraycopy( m3x4[ r ], 0, m[ r ], 0, 4 );

		return isUsable( m ) ? m : null;
	}

	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static Object fit( final Model< ? > prototype, final List< PointMatch > clean )
			throws NotEnoughDataPointsException, IllDefinedDataPointsException
	{
		final Model copy = ( ( Model ) prototype ).copy();
		copy.fit( clean );
		return copy;
	}

	/**
	 * A degenerate link has to be dropped rather than fed to the solver. The matrix logarithm
	 * of a singular or wildly scaled transform produces NaN, and because every view is coupled
	 * through the incidence matrix, one NaN link turns the entire solution into NaN with no
	 * other symptom.
	 */
	private static boolean isUsable( final double[][] m )
	{
		if ( !isFinite( m ) )
			return false;

		final double det =
				m[ 0 ][ 0 ] * ( m[ 1 ][ 1 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 1 ] )
				- m[ 0 ][ 1 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 2 ] - m[ 1 ][ 2 ] * m[ 2 ][ 0 ] )
				+ m[ 0 ][ 2 ] * ( m[ 1 ][ 0 ] * m[ 2 ][ 1 ] - m[ 1 ][ 1 ] * m[ 2 ][ 0 ] );

		// a relative transform between two overlapping views should be close to the identity;
		// anything outside this is a broken fit, not a real measurement
		return det > 0.1 && det < 10.0;
	}

	private static boolean isFinite( final double[][] m )
	{
		for ( int r = 0; r < 4; ++r )
			for ( int c = 0; c < 4; ++c )
				if ( !Double.isFinite( m[ r ][ c ] ) )
					return false;
		return true;
	}

	// --- helpers for the information-matrix path ---------------------------------------------

	private static double[][] transpose( final double[][] m )
	{
		final double[][] t = new double[ 4 ][ 4 ];
		for ( int r = 0; r < 4; ++r )
			for ( int c = 0; c < 4; ++c )
				t[ r ][ c ] = m[ c ][ r ];
		return t;
	}

	/** 3x4 algebra element to a 12-vector, row-major. */
	private static double[] vec( final double[][] m )
	{
		final double[] v = new double[ APGOBlockNormalEquations.D ];
		for ( int r = 0; r < 3; ++r )
			for ( int c = 0; c < 4; ++c )
				v[ r * 4 + c ] = m[ r ][ c ];
		return v;
	}

	/** Scatter a solved 12-vector per view back into per-tile 4x4 algebra elements. */
	private static void unvec( final double[] x, final int[] column, final double[][][] perTile )
	{
		for ( int i = 0; i < column.length; ++i )
			if ( column[ i ] >= 0 )
				for ( int r = 0; r < 3; ++r )
					for ( int c = 0; c < 4; ++c )
						perTile[ i ][ r ][ c ] = x[ column[ i ] * APGOBlockNormalEquations.D + r * 4 + c ];
	}

	private static void scatter( final double[] rhs, final int col, final double[] g, final double sign )
	{
		if ( col < 0 )
			return;
		for ( int i = 0; i < APGOBlockNormalEquations.D; ++i )
			rhs[ col * APGOBlockNormalEquations.D + i ] += sign * g[ i ];
	}

	/** Lambda v, with Lambda = I3 (x) M: each of the three rows of v is multiplied by M. */
	private static double[] applyInformation( final double[][] m, final double[] v )
	{
		final double[] out = new double[ APGOBlockNormalEquations.D ];
		for ( int r = 0; r < 3; ++r )
			for ( int i = 0; i < 4; ++i )
			{
				double acc = 0;
				for ( int j = 0; j < 4; ++j )
					acc += m[ i ][ j ] * v[ r * 4 + j ];
				out[ r * 4 + i ] = acc;
			}
		return out;
	}

	/**
	 * Per link, <code>A^T Lambda A</code> (or <code>Lambda</code> when <code>adjoints</code> is
	 * null, as in stage 1 where the Jacobian is the identity).
	 */
	private static double[][][] informationBlocks( final double[][][] moments, final double[][][] adjoints )
	{
		final int d = APGOBlockNormalEquations.D;
		final double[][][] blocks = new double[ moments.length ][][];

		for ( int p = 0; p < moments.length; ++p )
		{
			final double[][] block = new double[ d ][ d ];

			if ( adjoints == null )
			{
				// Lambda itself, column by column
				for ( int j = 0; j < d; ++j )
				{
					final double[] e = new double[ d ];
					e[ j ] = 1;
					final double[] col = applyInformation( moments[ p ], e );
					for ( int i = 0; i < d; ++i )
						block[ i ][ j ] = col[ i ];
				}
			}
			else
			{
				final double[][] a = adjoints[ p ];
				// Lambda A, column by column, then premultiply by A^T
				final double[][] la = new double[ d ][ d ];
				for ( int j = 0; j < d; ++j )
				{
					final double[] colA = new double[ d ];
					for ( int i = 0; i < d; ++i )
						colA[ i ] = a[ i ][ j ];
					final double[] col = applyInformation( moments[ p ], colA );
					for ( int i = 0; i < d; ++i )
						la[ i ][ j ] = col[ i ];
				}
				for ( int i = 0; i < d; ++i )
					for ( int j = 0; j < d; ++j )
					{
						double acc = 0;
						for ( int k = 0; k < d; ++k )
							acc += a[ k ][ i ] * la[ k ][ j ];
						block[ i ][ j ] = acc;
					}
			}

			blocks[ p ] = block;
		}

		return blocks;
	}

	/** Bernoulli coefficients B_k / k! of the dexp^-1 series; B_3 and every odd term past the first vanish. */
	private static final double[] DEXP_INV_COEFFICIENTS = { 1.0, 0.5, 1.0 / 12.0, 0.0, -1.0 / 720.0 };

	/**
	 * The 12x12 matrix of <code>d -&gt; dexp^-1_{-r}(d)</code>, the derivative of
	 * <code>d -&gt; log( exp(r) exp(d) )</code> at <code>d = 0</code>.
	 *
	 * <code>dexp^-1_{-r}(d) = d + 1/2 [r,d] + 1/12 [r,[r,d]] - 1/720 [r,[r,[r,[r,d]]]] + ...</code>
	 *
	 * Built by applying the series to the twelve basis elements, same as {@link #adjointMatrix}.
	 * {@link APGOSyntheticTest} checks it against central finite differences of the real thing.
	 */
	public static double[][] dexpInvMatrix( final double[][] r, final int order )
	{
		final int d = APGOBlockNormalEquations.D;
		final double[][] m = new double[ d ][ d ];

		for ( int j = 0; j < d; ++j )
		{
			final double[][] basis = new double[ 4 ][ 4 ];
			basis[ j / 4 ][ j % 4 ] = 1;

			double[][] term = basis;
			double[][] sum = Mat4.copy( basis );

			for ( int k = 1; k <= Math.min( order, DEXP_INV_COEFFICIENTS.length - 1 ); ++k )
			{
				term = Mat4.sub( Mat4.mult( r, term ), Mat4.mult( term, r ) );
				if ( DEXP_INV_COEFFICIENTS[ k ] != 0 )
					sum = Mat4.add( sum, Mat4.scale( term, DEXP_INV_COEFFICIENTS[ k ] ) );
			}

			for ( int i = 0; i < d; ++i )
				m[ i ][ j ] = sum[ i / 4 ][ i % 4 ];
		}

		return m;
	}

	private static double[][] mult12( final double[][] a, final double[][] b )
	{
		final int d = APGOBlockNormalEquations.D;
		final double[][] m = new double[ d ][ d ];
		for ( int i = 0; i < d; ++i )
			for ( int k = 0; k < d; ++k )
			{
				final double aik = a[ i ][ k ];
				if ( aik == 0 )
					continue;
				for ( int j = 0; j < d; ++j )
					m[ i ][ j ] += aik * b[ k ][ j ];
			}
		return m;
	}

	/**
	 * The 12x12 matrix of <code>X -&gt; C X C^-1</code> on the affine algebra, built by applying the
	 * map to the twelve basis elements. Done numerically rather than by index arithmetic - it is
	 * twelve cheap 4x4 conjugations per link and leaves no room for a transposition slip.
	 */
	private static double[][] adjointMatrix( final double[][] c, final double[][] cInv )
	{
		final int d = APGOBlockNormalEquations.D;
		final double[][] a = new double[ d ][ d ];

		for ( int j = 0; j < d; ++j )
		{
			final double[][] basis = new double[ 4 ][ 4 ];
			basis[ j / 4 ][ j % 4 ] = 1;

			final double[][] mapped = Mat4.mult( c, Mat4.mult( basis, cInv ) );
			for ( int i = 0; i < d; ++i )
				a[ i ][ j ] = mapped[ i / 4 ][ i % 4 ];
		}

		return a;
	}

	/** [A,B] applied <code>order</code> times, as the reference's <code>lie_bracket</code>. */
	private static double[][] bracket( final double[][] a, double[][] b, final int order )
	{
		if ( order > 1 )
			b = bracket( a, b, order - 1 );
		return Mat4.sub( Mat4.mult( a, b ), Mat4.mult( b, a ) );
	}

	/** The reference's <code>dexp</code>: <code>B + &#931; [A,B]&#8305; / (i+1)!</code>. */
	private static double[][] dexp( final double[][] a, final double[][] b, final int order )
	{
		double[][] out = Mat4.copy( b );
		double factorial = 1;
		for ( int i = 1; i <= order; ++i )
		{
			factorial *= ( i + 1 );
			out = Mat4.add( out, Mat4.scale( bracket( a, b, i ), 1.0 / factorial ) );
		}
		return out;
	}

	/** Adjoint action <code>A B A&#8315;&#185;</code>. */
	private static double[][] ad( final double[][] a, final double[][] b )
	{
		return Mat4.mult( a, Mat4.mult( b, Mat4.inverse( a ) ) );
	}

	private static int find( final int[] parent, int i )
	{
		while ( parent[ i ] != i )
			i = parent[ i ] = parent[ parent[ i ] ];
		return i;
	}

	private static void union( final int[] parent, final int a, final int b )
	{
		final int ra = find( parent, a ), rb = find( parent, b );
		if ( ra != rb )
			parent[ Math.max( ra, rb ) ] = Math.min( ra, rb );
	}
}
