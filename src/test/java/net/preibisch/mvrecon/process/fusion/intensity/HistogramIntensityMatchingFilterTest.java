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

public class HistogramIntensityMatchingFilterTest {

	@Test
	public void affineModelRecoversSlopeAndOffset() {
		final Random rnd = new Random(42);
		final double[][] pq = linearData(rnd, 1000, 1.5, 20.0);

		final List<PointMatch> reduced = runFilter(new AffineModel1D(), pq);

		assertEquals(2, reduced.size());
		assertEquals(1.5, slopeOf(reduced), 1e-9);
		assertEquals(20.0, offsetOf(reduced), 1e-6);
	}

	@Test
	public void translationModelRecoversOffset() {
		final Random rnd = new Random(23);
		final double[][] pq = linearData(rnd, 1000, 1.0, 30.0);

		final List<PointMatch> reduced = runFilter(new TranslationModel1D(), pq);

		assertEquals(2, reduced.size());
		assertEquals(1.0, slopeOf(reduced), 1e-9);
		assertEquals(30.0, offsetOf(reduced), 1e-6);
	}

	@Test
	public void interpolatedModelWorks() {
		final Random rnd = new Random(7);
		final double lambda = 0.1;
		final double[][] pq = linearData(rnd, 1000, 1.5, 20.0);

		final List<PointMatch> reduced = runFilter(
				new InterpolatedAffineModel1D<>(new AffineModel1D(), new TranslationModel1D(), lambda), pq);

		assertEquals(2, reduced.size());
		assertEquals(1.5 * (1 - lambda) + 1.0 * lambda, slopeOf(reduced), 1e-9);
		final double o = offsetOf(reduced);
		assertTrue(o > 20.0 && o < 30.0, "offset biased towards translation fit, was " + o);
	}

	@Test
	public void customNumSamplesRecoversModel() {
		final Random rnd = new Random(11);
		final double[][] pq = linearData(rnd, 1000, 1.5, 20.0);

		// few samples: quantiles still lie exactly on the line
		final List<PointMatch> few = runFilter(new AffineModel1D(), pq, 10);
		assertEquals(2, few.size());
		assertEquals(1.5, slopeOf(few), 1e-9);
		assertEquals(20.0, offsetOf(few), 1e-6);

		// more samples than candidates: duplicate samples, still exact
		final List<PointMatch> many = runFilter(new AffineModel1D(), pq, 5000);
		assertEquals(2, many.size());
		assertEquals(1.5, slopeOf(many), 1e-9);
		assertEquals(20.0, offsetOf(many), 1e-6);
	}

	@Test
	public void degenerateIntensitiesLeaveMatchesEmpty() {
		// all candidates at the same intensity
		final double[] p = new double[200];
		final double[] q = new double[200];
		for (int i = 0; i < 200; i++) {
			p[i] = 50;
			q[i] = 95;
		}
		assertTrue(runFilter(new TranslationModel1D(), new double[][] { p, q }).isEmpty());
		// AffineModel1D cannot even fit identical quantile pairs (ill-defined)
		assertTrue(runFilter(new AffineModel1D(), new double[][] { p, q }).isEmpty());
	}

	@Test
	public void callerModelIsNotMutated() {
		final Random rnd = new Random(88);
		final double[][] pq = linearData(rnd, 1000, 1.5, 20.0);

		final AffineModel1D model = new AffineModel1D();
		runFilter(model, pq);

		final double[] matrix = model.getMatrix(null);
		assertEquals(1.0, matrix[0], 0.0);
		assertEquals(0.0, matrix[1], 0.0);
	}

	private static List<PointMatch> runFilter(final Model<?> model, final double[][] pq) {
		final HistogramIntensityMatchingFilter filter = new HistogramIntensityMatchingFilter(model);
		final List<PointMatch> reduced = new ArrayList<>();
		filter.filter(flatten(pq[0], pq[1]), reduced);
		return reduced;
	}

	private static List<PointMatch> runFilter(final Model<?> model, final double[][] pq, final int numSamples) {
		final HistogramIntensityMatchingFilter filter = new HistogramIntensityMatchingFilter(model, numSamples);
		final List<PointMatch> reduced = new ArrayList<>();
		filter.filter(flatten(pq[0], pq[1]), reduced);
		return reduced;
	}

	/**
	 * {@code n} samples exactly on {@code q = slope * p + offset} (p in [10, 200]),
	 * in random order (histogram matching pairs the sorted intensity distributions,
	 * so the p-q pairing itself does not matter).
	 */
	private static double[][] linearData(final Random rnd, final int n, final double slope, final double offset) {
		final double[] p = new double[n];
		final double[] q = new double[n];
		for (int i = 0; i < n; i++) {
			p[i] = 10 + rnd.nextDouble() * 190;
			q[i] = slope * p[i] + offset;
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
