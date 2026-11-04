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
package net.preibisch.mvrecon.process.fusion.intensity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import Jama.CholeskyDecomposition;
import Jama.Matrix;
import mpicbg.models.PointMatch;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalRealInterval;
import net.imglib2.RealInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.IntervalIndexer;
import net.imglib2.util.Intervals;

/**
 * Per-view gains plus one log-linear ramp {@code exp(c . u)} fixed in every view's frame ({@code u} in [-0.5, 0.5] per
 * axis, see {@link Coefficients#gainField()}), solved from the pairwise intensity matches. The method of spotlight's
 * {@code gain_field = "linear"}:
 * <ol>
 * <li>the ramp: each overlap gives {@code d = log(a/b)} at a position {@code u_a} in view a and {@code u_b} in view b,
 * modelled as {@code d = c . (u_a - u_b) + (t_a - t_b) + e}, with RANDOM per-view deviations {@code t} (variance
 * {@code tau^2}) and measurement noise {@code e} (variance {@code sigma^2 / w}, {@code w} = matched voxels).
 * Generalized least squares, {@code rho = tau^2 / sigma^2} by restricted maximum likelihood. An axis is kept only if
 * its coefficient clears {@link #FIELD_SIGMA} standard errors (an axis the overlaps barely vary has a huge, meaningless
 * coefficient otherwise), and the fit is repeated with the kept axes;</li>
 * <li>the gains: {@code min sum w (l_a - l_b - d')^2 + lambda sum l^2} on what the ramp leaves, {@code l = log gain}.</li>
 * </ol>
 * Overlaps cannot tell a ramp inside every view from a trend of the gains along a chain of views (both give every
 * pair of the chain the same ratio); the random-deviation model is what decides how much goes where.
 * <p>
 * Each coefficient match (a pair of coefficient regions) is one overlap, located at the center of the intersection of
 * the two regions; its ratio is the scale {@code q = s p} fitted through its point matches, so match with a SCALE
 * model (or read the ratio of an AFFINE fit at its points). The resulting coefficients are one scale per view (all
 * regions alike, no offset) with the ramp as {@link Coefficients#withGainField}.
 * <p>
 * ponytail: one ramp for all views and dense pairs x pairs matrices, fine to a few thousand overlaps; one ramp per
 * camera / sparse Cholesky when a dataset needs either.
 */
public class GainField {

	public static final double FIELD_SIGMA = 3.0;

	/** tau^2 / sigma^2 tried by REML: 0 (no per-view scatter) and 1e-3 .. 1e6 */
	private static final double[] RHO_GRID;
	static {
		RHO_GRID = new double[38];
		for (int i = 1; i < RHO_GRID.length; ++i)
			RHO_GRID[i] = Math.pow(10, -3 + 9.0 * (i - 1) / 36);
	}

	/** One overlap: {@code d = log(a / b)} with weight {@code w} at {@code ua} (in view a) and {@code ub} (in view b). */
	static final class Constraint {
		final ViewId a, b;
		final double d, w;
		final double[] ua, ub;

		Constraint(final ViewId a, final ViewId b, final double d, final double w, final double[] ua, final double[] ub) {
			this.a = a;
			this.b = b;
			this.d = d;
			this.w = w;
			this.ua = ua;
			this.ub = ub;
		}
	}

	/** What {@link #fit} found, for the log. */
	public static final class Result {
		public final Map<ViewId, Double> gains;
		/** ramp coefficients per axis (0 where not kept); the image is divided by {@code exp(c . u)} */
		public final double[] c;
		public final double[] sigma;
		public final String[] status;
		public final double tau;

		Result(final Map<ViewId, Double> gains, final double[] c, final double[] sigma, final String[] status, final double tau) {
			this.gains = gains;
			this.c = c;
			this.sigma = sigma;
			this.status = status;
			this.tau = tau;
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder("gain field:");
			for (int k = 0; k < c.length; ++k)
				sb.append(String.format(" %s c=%.4f (ramp x%.3f, sigma %.4f, %s)", "xyz".charAt(k), c[k], Math.exp(c[k]), sigma[k], status[k]));
			final double[] g = gains.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
			sb.append(String.format("; tile scatter tau=%.4f; gains %.3f .. %.3f over %d views", tau, g[0], g[g.length - 1], g.length));
			return sb.toString();
		}
	}

	/**
	 * @param lambda ridge on the log gains (scale-free, the weights are normalized by their median); it fixes the
	 *            global level and damps views with few overlaps, 0.001 .. 0.1
	 * @return the coefficients of every view in {@code pairwiseMatches}: one scale per view, no offset, the ramp as
	 *         gain field
	 */
	public static Map<ViewId, Coefficients> solve(final AbstractSpimData<?> spimData, final int[] coefficientsSize,
			final Collection<ViewPairCoefficientMatches> pairwiseMatches, final double lambda, final StringBuilder log) {
		final Result r = fit(constraints(spimData, coefficientsSize, pairwiseMatches), lambda);
		if (log != null)
			log.append(r);
		final int numCells = (int) Intervals.numElements(coefficientsSize);
		final double[] fieldOfScale = Arrays.stream(r.c).map(v -> -v).toArray();
		final Map<ViewId, Coefficients> out = new HashMap<>();
		r.gains.forEach((view, g) -> {
			final double[] a = new double[numCells], b = new double[numCells];
			Arrays.fill(a, 1.0 / g);
			out.put(view, new Coefficients(new double[][] { a, b }, coefficientsSize).withGainField(fieldOfScale));
		});
		return out;
	}

	static List<Constraint> constraints(final AbstractSpimData<?> spimData, final int[] coefficientsSize,
			final Collection<ViewPairCoefficientMatches> pairwiseMatches) {
		final Map<ViewId, TileInfo> tiles = new HashMap<>();
		final List<Constraint> out = new ArrayList<>();
		for (final ViewPairCoefficientMatches pair : pairwiseMatches) {
			final TileInfo t1 = tiles.computeIfAbsent(pair.view1(), v -> new TileInfo(coefficientsSize, spimData, v));
			final TileInfo t2 = tiles.computeIfAbsent(pair.view2(), v -> new TileInfo(coefficientsSize, spimData, v));
			for (final IntensityMatcher.CoefficientMatch m : pair.coefficientMatches()) {
				final double s = scale(m.matches());
				if (!(s > 0) || Double.isInfinite(s))
					continue;
				final RealInterval overlap = Intervals.intersect(cellBounds(t1, m.coeff1()), cellBounds(t2, m.coeff2()));
				final double[] center = new double[3];
				Arrays.setAll(center, d -> 0.5 * (overlap.realMin(d) + overlap.realMax(d)));
				// q = s p maps view1's intensities onto view2's: log(view1 / view2) = -log s
				out.add(new Constraint(pair.view1(), pair.view2(), -Math.log(s), m.numVoxels(),
						frameCoordinates(t1, center), frameCoordinates(t2, center)));
			}
		}
		return out;
	}

	/** least squares {@code q = s p} through the point matches */
	static double scale(final Collection<PointMatch> matches) {
		double pq = 0, pp = 0;
		for (final PointMatch pm : matches) {
			final double p = pm.getP1().getL()[0], q = pm.getP2().getL()[0], w = pm.getWeight();
			pq += w * p * q;
			pp += w * p * p;
		}
		return pq / pp;
	}

	private static RealInterval cellBounds(final TileInfo tile, final int flatIndex) {
		final long[] pos = new long[3];
		IntervalIndexer.indexToPosition(flatIndex, tile.numCoeffs, pos);
		final double[] min = { pos[0], pos[1], pos[2] }, max = { pos[0] + 1, pos[1] + 1, pos[2] + 1 };
		return tile.coeffBoundsToWorldTransform.estimateBounds(new FinalRealInterval(min, max));
	}

	/** world position to [-0.5, 0.5] per axis of the view's image */
	private static double[] frameCoordinates(final TileInfo tile, final double[] world) {
		final double[] local = new double[3];
		final AffineTransform3D toLocal = tile.model.inverse();
		toLocal.apply(world, local);
		final double[] u = new double[3];
		Arrays.setAll(u, d -> (local[d] + 0.5) / tile.dimensions.dimension(d) - 0.5);
		return u;
	}

	static Result fit(final List<Constraint> constraints, final double lambda) {
		final TreeSet<ViewId> sorted = new TreeSet<>();
		constraints.forEach(c -> {
			sorted.add(c.a);
			sorted.add(c.b);
		});
		final List<ViewId> views = new ArrayList<>(sorted);
		final Map<ViewId, Integer> index = new HashMap<>();
		for (int i = 0; i < views.size(); ++i)
			index.put(views.get(i), i);

		final int n = constraints.size();
		final double[] w = constraints.stream().mapToDouble(c -> c.w).toArray();
		final double wMedian = median(w);
		Arrays.setAll(w, i -> w[i] / wMedian);
		final double[] d = constraints.stream().mapToDouble(c -> c.d).toArray();
		final Matrix D = new Matrix(n, views.size());
		for (int r = 0; r < n; ++r) {
			D.set(r, index.get(constraints.get(r).a), 1);
			D.set(r, index.get(constraints.get(r).b), -1);
		}

		// stage 1: the ramp on every axis the overlaps vary, then again on the significant ones
		final String[] status = new String[3];
		final List<Integer> live = new ArrayList<>();
		for (int k = 0; k < 3; ++k) {
			final int axis = k;
			if (constraints.stream().anyMatch(con -> Math.abs(con.ua[axis] - con.ub[axis]) > 1e-12))
				live.add(k);
			else
				status[k] = "no lever arm: the overlaps never vary it";
		}
		final Reml first = Reml.fit(live, constraints, D, d, w);
		final List<Integer> keep = new ArrayList<>();
		for (int j = 0; j < live.size(); ++j) {
			final int k = live.get(j);
			final double z = first.dof < 1 ? 0 : Math.abs(first.beta[j]) / first.sigma[j];
			if (first.dof < 1)
				status[k] = "too few overlaps";
			else if (z > FIELD_SIGMA) {
				status[k] = "kept";
				keep.add(k);
			} else
				status[k] = String.format("below %.0f sigma (%.1f)", FIELD_SIGMA, z);
		}
		final Reml fitted = keep.equals(live) ? first : Reml.fit(keep, constraints, D, d, w);
		final double[] c = new double[3], sigma = new double[3];
		for (int j = 0; j < keep.size(); ++j) {
			c[keep.get(j)] = fitted.beta[j];
			sigma[keep.get(j)] = fitted.sigma[j];
		}
		final double tau = fitted.tau;

		// stage 2: per-view log gains on what the ramp leaves, ridge lambda
		final Matrix A = new Matrix(views.size(), views.size());
		final Matrix rhs = new Matrix(views.size(), 1);
		for (int r = 0; r < n; ++r) {
			final Constraint con = constraints.get(r);
			double dr = con.d;
			for (int k = 0; k < 3; ++k)
				dr -= c[k] * (con.ua[k] - con.ub[k]);
			final int ia = index.get(con.a), ib = index.get(con.b);
			A.set(ia, ia, A.get(ia, ia) + w[r]);
			A.set(ib, ib, A.get(ib, ib) + w[r]);
			A.set(ia, ib, A.get(ia, ib) - w[r]);
			A.set(ib, ia, A.get(ib, ia) - w[r]);
			rhs.set(ia, 0, rhs.get(ia, 0) + w[r] * dr);
			rhs.set(ib, 0, rhs.get(ib, 0) - w[r] * dr);
		}
		for (int i = 0; i < views.size(); ++i)
			A.set(i, i, A.get(i, i) + lambda);
		final Matrix l = A.solve(rhs);
		final Map<ViewId, Double> gains = new HashMap<>();
		for (int i = 0; i < views.size(); ++i)
			gains.put(views.get(i), Math.exp(l.get(i, 0)));
		return new Result(gains, c, sigma, status, tau);
	}

	private static double median(final double[] values) {
		final double[] s = values.clone();
		Arrays.sort(s);
		return s.length % 2 == 1 ? s[s.length / 2] : 0.5 * (s[s.length / 2 - 1] + s[s.length / 2]);
	}

	/** GLS of {@code d = X beta + D t + e}, {@code rho} by restricted maximum likelihood over {@link #RHO_GRID}. */
	private static final class Reml {
		double[] beta = new double[0], sigma = new double[0];
		double tau, dof;

		static Reml fit(final List<Integer> axes, final List<Constraint> constraints, final Matrix D, final double[] d, final double[] w) {
			final int n = d.length, p = axes.size();
			final Reml out = new Reml();
			out.dof = n - p;
			if (out.dof < 1 || p == 0)
				return out;
			final Matrix X = new Matrix(n, p);
			for (int r = 0; r < n; ++r)
				for (int j = 0; j < p; ++j) {
					final int k = axes.get(j);
					X.set(r, j, constraints.get(r).ua[k] - constraints.get(r).ub[k]);
				}
			final Matrix y = new Matrix(d, n);
			final Matrix DDt = D.times(D.transpose());
			double best = Double.NEGATIVE_INFINITY;
			for (final double rho : RHO_GRID) {
				final Matrix V = DDt.times(rho);
				for (int r = 0; r < n; ++r)
					V.set(r, r, V.get(r, r) + 1.0 / w[r]);
				final CholeskyDecomposition chol = V.chol();
				if (!chol.isSPD())
					continue;
				final Matrix L = chol.getL();
				final Matrix Xs = forward(L, X), ys = forward(L, y);
				final Matrix XtX = Xs.transpose().times(Xs);
				final Matrix b = XtX.solve(Xs.transpose().times(ys));
				final Matrix res = ys.minus(Xs.times(b));
				final double q = Math.max(res.transpose().times(res).get(0, 0), 1e-300);
				double logDetL = 0;
				for (int r = 0; r < n; ++r)
					logDetL += Math.log(L.get(r, r));
				final double ll = -0.5 * (out.dof * Math.log(q / out.dof) + 2 * logDetL + Math.log(XtX.det()));
				if (ll > best) {
					best = ll;
					final double s2 = q / out.dof;
					final Matrix cov = XtX.inverse().times(s2);
					out.beta = b.getColumnPackedCopy();
					out.sigma = new double[p];
					Arrays.setAll(out.sigma, j -> Math.sqrt(cov.get(j, j)));
					out.tau = Math.sqrt(rho * s2);
				}
			}
			return out;
		}

		/** L^-1 B for lower triangular L */
		private static Matrix forward(final Matrix L, final Matrix B) {
			final int n = L.getRowDimension(), m = B.getColumnDimension();
			final double[][] l = L.getArray(), x = B.getArrayCopy();
			for (int c = 0; c < m; ++c)
				for (int i = 0; i < n; ++i) {
					double s = x[i][c];
					for (int k = 0; k < i; ++k)
						s -= l[i][k] * x[k][c];
					x[i][c] = s / l[i][i];
				}
			return new Matrix(x);
		}
	}
}
