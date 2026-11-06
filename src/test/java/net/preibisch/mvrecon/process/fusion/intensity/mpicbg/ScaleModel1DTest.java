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
package net.preibisch.mvrecon.process.fusion.intensity.mpicbg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ScaleModel1DTest {

	@Test
	public void collectionFitRecoversScale() throws Exception {
		final Random rnd = new Random(42);
		final List<PointMatch> matches = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			final double p = 1 + rnd.nextDouble() * 200;
			matches.add(new PointMatch1D(new Point1D(p), new Point1D(2.5 * p), 1.0));
		}

		final ScaleModel1D model = new ScaleModel1D();
		model.fit(matches);

		assertEquals(2.5, model.getScale(), 1e-12);
		assertEquals(25.0, model.apply(new double[] { 10 })[0], 1e-12);
	}

	@Test
	public void arrayFitsRecoverScale() throws Exception {
		final int n = 50;
		final double[][] p = new double[1][n];
		final double[][] q = new double[1][n];
		final double[] w = new double[n];
		final float[][] pf = new float[1][n];
		final float[][] qf = new float[1][n];
		final float[] wf = new float[n];
		final Random rnd = new Random(23);
		for (int i = 0; i < n; i++) {
			p[0][i] = 1 + rnd.nextDouble() * 100;
			q[0][i] = 0.75 * p[0][i];
			w[i] = 1;
			pf[0][i] = (float) p[0][i];
			qf[0][i] = (float) q[0][i];
			wf[i] = 1;
		}

		final ScaleModel1D md = new ScaleModel1D();
		md.fit(p, q, w);
		assertEquals(0.75, md.getScale(), 1e-12);

		final ScaleModel1D mf = new ScaleModel1D();
		mf.fit(pf, qf, wf);
		assertEquals(0.75, mf.getScale(), 1e-6);
	}

	@Test
	public void weightedFitMatchesClosedForm() throws Exception {
		// s = sum(w*p*q) / sum(w*p*p), computed by hand for 3 points
		final double[] p = { 2, 5, 10 };
		final double[] q = { 5, 9, 21 };
		final double[] w = { 1, 3, 0.5 };

		double num = 0, den = 0;
		for (int i = 0; i < p.length; i++) {
			num += w[i] * p[i] * q[i];
			den += w[i] * p[i] * p[i];
		}

		final List<PointMatch> matches = new ArrayList<>();
		for (int i = 0; i < p.length; i++)
			matches.add(new PointMatch1D(new Point1D(p[i]), new Point1D(q[i]), w[i]));

		final ScaleModel1D model = new ScaleModel1D();
		model.fit(matches);

		assertEquals(num / den, model.getScale(), 1e-12);
	}

	@Test
	public void degenerateInputsThrow() {
		final ScaleModel1D model = new ScaleModel1D();

		assertThrows(NotEnoughDataPointsException.class,
				() -> model.fit(Collections.<PointMatch>emptyList()));

		// all p at 0: scale undefined
		final List<PointMatch> zeros = new ArrayList<>();
		for (int i = 0; i < 10; i++)
			zeros.add(new PointMatch1D(new Point1D(0), new Point1D(5), 1.0));
		assertThrows(IllDefinedDataPointsException.class, () -> model.fit(zeros));
	}

	@Test
	public void inverseRoundTripAndNoninvertible() throws Exception {
		final ScaleModel1D model = new ScaleModel1D();
		model.set(4.0);

		final double[] l = { 3.0 };
		final double[] applied = model.apply(l);
		assertEquals(12.0, applied[0], 1e-12);
		assertEquals(3.0, model.applyInverse(applied)[0], 1e-12);

		assertEquals(0.25, model.createInverse().getScale(), 1e-12);

		model.set(0.0);
		assertThrows(NoninvertibleModelException.class, () -> model.applyInverse(new double[] { 1 }));
	}

	@Test
	public void copySetConcatenate() {
		final ScaleModel1D a = new ScaleModel1D();
		a.set(2.0);

		final ScaleModel1D b = a.copy();
		b.set(3.0);
		assertEquals(2.0, a.getScale(), 0.0);
		assertEquals(3.0, b.getScale(), 0.0);

		a.concatenate(b);
		assertEquals(6.0, a.getScale(), 0.0);

		a.preConcatenate(b);
		assertEquals(18.0, a.getScale(), 0.0);

		final ScaleModel1D c = new ScaleModel1D();
		c.set(b);
		assertEquals(3.0, c.getScale(), 0.0);

		final double[] arr = new double[2];
		c.toArray(arr);
		assertEquals(3.0, arr[0], 0.0);
		assertEquals(0.0, arr[1], 0.0);
		assertEquals(3.0, c.getMatrix(null)[0], 0.0);
	}
}
