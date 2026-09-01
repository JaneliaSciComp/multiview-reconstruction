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
import java.util.List;
import java.util.Random;

import mpicbg.models.AffineModel1D;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FlattenedMatches;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RansacIntensityMatchingFilterTest {

	private static final int ITERATIONS = 1000;

	@Test
	public void affineModelRecoversSlopeAndOffset() {
		final Random rnd = new Random(42);
		final double slope = 1.5;
		final double offset = 20.0;
		final double[][] pq = linearData(rnd, 800, 200, slope, offset);

		final List<PointMatch> reduced = runFilter(
				new AffineModel1D(), pq, 0.1, 0.5, 10, 3.0);

		assertEquals(2, reduced.size());
		assertEquals(slope, slopeOf(reduced), 1e-9);
		assertEquals(offset, offsetOf(reduced), 1e-6);

		// the two representative matches span (most of) the inlier intensity range [10, 200];
		// exact-equality with the true min/max is too strict: on noise-free data the trust
		// filter prunes on floating-point-noise errors and may drop boundary inliers
		final double l0 = reduced.get(0).getP1().getL()[0];
		final double l1 = reduced.get(1).getP1().getL()[0];
		assertTrue(l0 < l1, "matches ordered min, max");
		assertTrue(l0 >= 10 && l1 <= 200, "matches within the inlier range");
		assertTrue(l1 - l0 > 150, "matches span most of the inlier range");
	}

	@Test
	public void translationModelRecoversOffset() {
		final Random rnd = new Random(23);
		final double[][] pq = linearData(rnd, 800, 200, 1.0, 30.0);

		final List<PointMatch> reduced = runFilter(
				new TranslationModel1D(), pq, 0.1, 0.5, 10, 3.0);

		assertEquals(2, reduced.size());
		assertEquals(1.0, slopeOf(reduced), 1e-9);
		assertEquals(30.0, offsetOf(reduced), 1e-6);
	}

	@Test
	public void interpolatedModelWorks() {
		final Random rnd = new Random(7);
		final double lambda = 0.1;
		final double[][] pq = linearData(rnd, 800, 200, 1.5, 20.0);

		// the interpolated fit biases the slope towards 1, so the residuals of the exact
		// affine data reach ~0.05 * p; maxEpsilon must accommodate that
		final List<PointMatch> reduced = runFilter(
				new InterpolatedAffineModel1D<>(new AffineModel1D(), new TranslationModel1D(), lambda),
				pq, 6.0, 0.5, 10, 3.0);

		assertEquals(2, reduced.size());
		assertEquals(1.5 * (1 - lambda) + 1.0 * lambda, slopeOf(reduced), 1e-9);
		final double o = offsetOf(reduced);
		assertTrue(o > 20.0 && o < 30.0, "offset biased towards translation fit, was " + o);
	}

	@Test
	public void noModelFoundLeavesMatchesEmpty() {
		final Random rnd = new Random(5);

		// pure noise
		final double[] p = new double[500];
		final double[] q = new double[500];
		for (int i = 0; i < 500; i++) {
			p[i] = rnd.nextDouble() * 1000;
			q[i] = rnd.nextDouble() * 1000;
		}
		assertTrue(runFilter(new AffineModel1D(), new double[][] { p, q }, 0.001, 0.5, 10, 3.0).isEmpty());

		// fewer candidates than minNumInliers
		final double[][] few = linearData(rnd, 5, 0, 1.5, 20.0);
		assertTrue(runFilter(new AffineModel1D(), few, 0.1, 0.5, 10, 3.0).isEmpty());

		// fewer candidates than the model's minimum number of matches
		final double[][] one = linearData(rnd, 1, 0, 1.5, 20.0);
		assertTrue(runFilter(new AffineModel1D(), one, 0.1, 0.5, 1, 3.0).isEmpty());

		// degenerate: all inliers at the same intensity
		final double[] pc = new double[100];
		final double[] qc = new double[100];
		for (int i = 0; i < 100; i++) {
			pc[i] = 50;
			qc[i] = 95;
		}
		assertTrue(runFilter(new TranslationModel1D(), new double[][] { pc, qc }, 1.0, 0.5, 10, 3.0).isEmpty());
	}

	@Test
	public void callerModelIsNotMutated() {
		final Random rnd = new Random(88);
		final double[][] pq = linearData(rnd, 800, 200, 1.5, 20.0);

		final AffineModel1D model = new AffineModel1D();
		runFilter(model, pq, 0.1, 0.5, 10, 3.0);

		final double[] matrix = model.getMatrix(null);
		assertEquals(1.0, matrix[0], 0.0);
		assertEquals(0.0, matrix[1], 0.0);
	}

	private static List<PointMatch> runFilter(
			final Model<?> model,
			final double[][] pq,
			final double maxEpsilon,
			final double minInlierRatio,
			final int minNumInliers,
			final double maxTrust) {
		final RansacIntensityMatchingFilter filter = new RansacIntensityMatchingFilter(
				model, ITERATIONS, maxEpsilon, minInlierRatio, minNumInliers, maxTrust);
		final List<PointMatch> reduced = new ArrayList<>();
		filter.filter(flatten(pq[0], pq[1]), reduced);
		return reduced;
	}

	/**
	 * {@code numInliers} samples exactly on {@code q = slope * p + offset} (p in [10, 200]),
	 * followed by {@code numOutliers} samples at least 30 off the line.
	 */
	private static double[][] linearData(
			final Random rnd,
			final int numInliers,
			final int numOutliers,
			final double slope,
			final double offset) {
		final int n = numInliers + numOutliers;
		final double[] p = new double[n];
		final double[] q = new double[n];
		for (int i = 0; i < n; i++) {
			p[i] = 10 + rnd.nextDouble() * 190;
			q[i] = slope * p[i] + offset;
			if (i >= numInliers)
				q[i] += (30 + rnd.nextDouble() * 100) * (rnd.nextBoolean() ? 1 : -1);
		}
		return new double[][] { p, q };
	}

	private static FlattenedMatches flatten(final double[] p, final double[] q) {
		final FlattenedMatches matches = new FlattenedMatches(1, p.length);
		matches.setWeighted(false);
		for (int i = 0; i < p.length; i++)
			matches.put(p[i], q[i], 1);
		matches.flip();
		return matches;
	}

	private static double slopeOf(final List<PointMatch> reduced) {
		final double l1 = reduced.get(0).getP1().getL()[0];
		final double w1 = reduced.get(0).getP2().getL()[0];
		final double l2 = reduced.get(1).getP1().getL()[0];
		final double w2 = reduced.get(1).getP2().getL()[0];
		return (w2 - w1) / (l2 - l1);
	}

	private static double offsetOf(final List<PointMatch> reduced) {
		final double l1 = reduced.get(0).getP1().getL()[0];
		final double w1 = reduced.get(0).getP2().getL()[0];
		return w1 - slopeOf(reduced) * l1;
	}
}
