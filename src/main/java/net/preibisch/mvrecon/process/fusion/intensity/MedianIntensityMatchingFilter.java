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

import java.util.Arrays;
import java.util.Collection;

import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FlattenedMatches;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.ScaleModel1D;

/**
 * A pure gain from the voxels both views see: {@code s = median(q) / median(p)} over the candidates (which the matcher
 * has already gated at the threshold in BOTH views, so the two medians describe one population), reduced to two
 * matches on {@code q = s p} at the smallest and largest {@code p}, like {@link HistogramIntensityMatchingFilter}.
 * Robust to the bright tail that dominates a scale fit to all voxels or quantiles. Use with a SCALE solve.
 */
class MedianIntensityMatchingFilter implements IntensityMatchingFilter {

	/**
	 * Where the two matches of a pair are placed: quantiles of the first view's intensities in the overlap. Not its
	 * minimum and maximum: with the maximum, a few outlier voxels decided how much a pair counts (on a chain of 8 tiles
	 * with outliers up to 58,000 the seams were 13% instead of 8%, as bad as without a hand-set maximum intensity).
	 */
	static final double LOW_QUANTILE = 0.05, HIGH_QUANTILE = 0.95;

	private final Model<?> model = new ScaleModel1D();

	@Override
	public Model<?> model() {
		return model;
	}

	@Override
	public void filter(final FlattenedMatches candidates, final Collection<PointMatch> reducedMatches) {
		reducedMatches.clear();
		final int n = candidates.size();
		if (n == 0)
			return;
		final double[] p = Arrays.copyOf(candidates.p()[0], n), q = Arrays.copyOf(candidates.q()[0], n);
		Arrays.sort(p);
		Arrays.sort(q);
		final double mp = median(p), mq = median(q);
		// two identical matches would make the downstream fit ill-defined
		if (!(mp > 0 && mq > 0 && p[n - 1] > p[0]))
			return;
		final double s = mq / mp;
		// the ratio at two intensities of the overlap; the solver weights a match by its intensity squared, so not at the
		// extremes: a few outlier voxels would set how much this pair counts against the others
		final double lo = p[(int) (LOW_QUANTILE * (n - 1))], hi = p[(int) (HIGH_QUANTILE * (n - 1))];
		if (!(hi > lo))
			return;
		reducedMatches.add(new PointMatch1D(new Point1D(lo), new Point1D(s * lo), 1.0));
		reducedMatches.add(new PointMatch1D(new Point1D(hi), new Point1D(s * hi), 1.0));
	}

	private static double median(final double[] sorted) {
		final int n = sorted.length;
		return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
	}
}
