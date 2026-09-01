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

import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FlattenedMatches;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class RansacIntensityMatchingFilter implements IntensityMatchingFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RansacIntensityMatchingFilter.class);

    private final Model<?> model;
    private final int iterations;
    private final double maxEpsilon;
    private final double minInlierRatio;
    private final int minNumInliers;
    private final double maxTrust;

    /**
     * Filter candidate matches with classic mpicbg RANSAC, then reduce the
     * inliers to two representative matches (min and max inlier intensity,
     * mapped through the fitted model).
     * <p>
     * Not thread-safe: the model copy is re-fit on every {@code filter()} call.
     *
     * @param model          the 1D model to fit ({@code AffineModel1D}, {@code TranslationModel1D},
     *                       or any {@code InterpolatedAffineModel1D}); it is copied, the given
     *                       instance is never modified
     * @param iterations     number of RANSAC iterations
     * @param maxEpsilon     maximal allowed transfer error
     * @param minInlierRatio minimal number of inliers to number of candidates
     * @param minNumInliers  minimally required absolute number of inliers
     * @param maxTrust       reject candidates with a cost larger than maxTrust * median cost
     */
    public RansacIntensityMatchingFilter(
            final Model<?> model,
            final int iterations,
            final double maxEpsilon,
            final double minInlierRatio,
            final int minNumInliers,
            final double maxTrust
    ) {
        this.model = model.copy();
        this.iterations = iterations;
        this.maxEpsilon = maxEpsilon;
        this.minInlierRatio = minInlierRatio;
        this.minNumInliers = minNumInliers;
        this.maxTrust = maxTrust;
    }

    @Override
    public Model<?> model() {
        return model;
    }

    @Override
    public void filter(final FlattenedMatches candidates, final Collection<PointMatch> reducedMatches) {
        reducedMatches.clear();

        final int size = candidates.size();
        final double[] p = candidates.p()[0];
        final double[] q = candidates.q()[0];
        final double[] w = candidates.w();
        final boolean weighted = candidates.weighted();
        final List<PointMatch> matches = new ArrayList<>(size);
        for (int i = 0; i < size; ++i)
            matches.add(new PointMatch1D(new Point1D(p[i]), new Point1D(q[i]), weighted ? w[i] : 1.0));

        final List<PointMatch> inliers = new ArrayList<>();
        try {
            // filterRansac may return false while leaving survivors in inliers, so gate on the return value
            if (!model.filterRansac(matches, inliers, iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust))
                return;
        } catch (final NotEnoughDataPointsException e) {
            return;
        }
        LOG.debug("inliers/candidates: {}/{}", inliers.size(), size);

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (final PointMatch match : inliers) {
            final double x = match.getP1().getL()[0];
            if (x < min)
                min = x;
            if (x > max)
                max = x;
        }
        // two identical matches would make the downstream model fit ill-defined
        if (!(max > min))
            return;

        reducedMatches.add(new PointMatch1D(new Point1D(min), new Point1D(apply(min)), 1.0));
        reducedMatches.add(new PointMatch1D(new Point1D(max), new Point1D(apply(max)), 1.0));
    }

    private double apply(final double x) {
        return model.apply(new double[] { x })[0];
    }
}
