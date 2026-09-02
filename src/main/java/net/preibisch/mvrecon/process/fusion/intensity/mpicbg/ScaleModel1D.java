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

import java.util.Collection;

import mpicbg.models.AbstractAffineModel1D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NoninvertibleModelException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;

/**
 * 1D scale-only model {@code q = s * p} (multiplicative gain, no offset), fit by closed-form
 * weighted least squares: minimizing {@code sum_i w_i * (s * p_i - q_i)^2} yields
 * {@code s = sum_i(w_i * p_i * q_i) / sum_i(w_i * p_i^2)}. Unlike the affine and translation
 * fits, no mean-centering is required since there is no offset to factor out.
 * <p>
 * Not named {@code SimilarityModel1D}: 1D similarity (uniform scale + translation, rotation
 * degenerates in 1D) would be identical to {@code AffineModel1D}, so a scale-only model is
 * not the 1D analog of {@code SimilarityModel2D}/{@code SimilarityModel3D}.
 */
public class ScaleModel1D extends AbstractAffineModel1D<ScaleModel1D> {

	private static final long serialVersionUID = 1L;

	static final protected int MIN_NUM_MATCHES = 1;

	protected double s = 1;

	final public double getScale() {
		return s;
	}

	@Override
	final public int getMinNumMatches() {
		return MIN_NUM_MATCHES;
	}

	@Override
	final public double[] apply(final double[] l) {
		assert l.length >= 1 : "1d scale transformations can be applied to 1d points only.";

		return new double[] { l[0] * s };
	}

	@Override
	final public void applyInPlace(final double[] l) {
		assert l.length >= 1 : "1d scale transformations can be applied to 1d points only.";

		l[0] *= s;
	}

	@Override
	final public double[] applyInverse(final double[] l) throws NoninvertibleModelException {
		assert l.length >= 1 : "1d scale transformations can be applied to 1d points only.";

		if (s == 0)
			throw new NoninvertibleModelException("Model not invertible.");

		return new double[] { l[0] / s };
	}

	@Override
	final public void applyInverseInPlace(final double[] l) throws NoninvertibleModelException {
		assert l.length >= 1 : "1d scale transformations can be applied to 1d points only.";

		if (s == 0)
			throw new NoninvertibleModelException("Model not invertible.");

		l[0] /= s;
	}

	@Override
	final public void fit(
			final double[][] p,
			final double[][] q,
			final double[] w)
			throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		assert
			p.length >= 1 &&
			q.length >= 1 : "1d scale transformations can be applied to 1d points only.";

		assert
			p[0].length == q[0].length &&
			p[0].length == w.length : "Array lengths do not match.";

		final int l = p[0].length;

		if (l < MIN_NUM_MATCHES)
			throw new NotEnoughDataPointsException(l + " data points are not enough to estimate a 1d scale model, at least " + MIN_NUM_MATCHES + " data points required.");

		final double[] pX = p[0];
		final double[] qX = q[0];

		double a = 0;
		double b = 0;
		for (int i = 0; i < l; ++i) {
			final double wwpx = w[i] * pX[i];
			a += wwpx * pX[i];
			b += wwpx * qX[i];
		}

		if (a == 0)
			throw new IllDefinedDataPointsException();

		s = b / a;
	}

	@Override
	final public void fit(
			final float[][] p,
			final float[][] q,
			final float[] w)
			throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		assert
			p.length >= 1 &&
			q.length >= 1 : "1d scale transformations can be applied to 1d points only.";

		assert
			p[0].length == q[0].length &&
			p[0].length == w.length : "Array lengths do not match.";

		final int l = p[0].length;

		if (l < MIN_NUM_MATCHES)
			throw new NotEnoughDataPointsException(l + " data points are not enough to estimate a 1d scale model, at least " + MIN_NUM_MATCHES + " data points required.");

		final float[] pX = p[0];
		final float[] qX = q[0];

		double a = 0;
		double b = 0;
		for (int i = 0; i < l; ++i) {
			final double wwpx = w[i] * pX[i];
			a += wwpx * pX[i];
			b += wwpx * qX[i];
		}

		if (a == 0)
			throw new IllDefinedDataPointsException();

		s = b / a;
	}

	@Override
	final public <P extends PointMatch> void fit(final Collection<P> matches)
			throws NotEnoughDataPointsException, IllDefinedDataPointsException {
		if (matches.size() < MIN_NUM_MATCHES)
			throw new NotEnoughDataPointsException(matches.size() + " data points are not enough to estimate a 1d scale model, at least " + MIN_NUM_MATCHES + " data points required.");

		double a = 0;
		double b = 0;
		for (final P m : matches) {
			final double px = m.getP1().getL()[0];
			final double qx = m.getP2().getW()[0];
			final double wwpx = m.getWeight() * px;
			a += wwpx * px;
			b += wwpx * qx;
		}

		if (a == 0)
			throw new IllDefinedDataPointsException();

		s = b / a;
	}

	@Override
	public ScaleModel1D copy() {
		final ScaleModel1D m = new ScaleModel1D();
		m.s = s;
		m.cost = cost;
		return m;
	}

	@Override
	final public void set(final ScaleModel1D m) {
		s = m.s;
		cost = m.getCost();
	}

	/**
	 * Initialize the model with a scale factor
	 *
	 * @param s
	 */
	final public void set(final double s) {
		this.s = s;
	}

	@Override
	final public void preConcatenate(final ScaleModel1D m) {
		s *= m.s;
	}

	@Override
	final public void concatenate(final ScaleModel1D m) {
		s *= m.s;
	}

	/**
	 * Note: undefined (infinite scale) if {@code s == 0}.
	 */
	@Override
	public ScaleModel1D createInverse() {
		final ScaleModel1D ict = new ScaleModel1D();

		ict.s = 1.0 / s;

		ict.cost = cost;

		return ict;
	}

	@Override
	public void toArray(final double[] data) {
		data[0] = s;
		data[1] = 0;
	}

	@Override
	public void toMatrix(final double[][] data) {
		data[0][0] = s;
		data[0][1] = 0;
	}

	@Override
	public double[] getMatrix(final double[] m) {
		final double[] a;
		if (m == null || m.length != 2)
			a = new double[2];
		else
			a = m;

		a[0] = s;
		a[1] = 0;

		return a;
	}

	@Override
	public String toString() {
		return "1d-scale: (" + s + ")";
	}
}
