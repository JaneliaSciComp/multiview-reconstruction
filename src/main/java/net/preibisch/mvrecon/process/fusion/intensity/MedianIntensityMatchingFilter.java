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
 * spotlight's "intersection" gain estimator; robust to the bright tail that dominates a scale fit to all voxels or
 * quantiles. Use with a SCALE solve.
 */
class MedianIntensityMatchingFilter implements IntensityMatchingFilter {

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
		reducedMatches.add(new PointMatch1D(new Point1D(p[0]), new Point1D(s * p[0]), 1.0));
		reducedMatches.add(new PointMatch1D(new Point1D(p[n - 1]), new Point1D(s * p[n - 1]), 1.0));
	}

	private static double median(final double[] sorted) {
		final int n = sorted.length;
		return n % 2 == 1 ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
	}
}
