/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
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

import mpicbg.models.PointMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FastAffineModel1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FlattenedMatches;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.RansacRegressionReduceFilter;

import java.util.Collection;

class RansacIntensityMatchingFilter implements IntensityMatchingFilter {

    private final RansacRegressionReduceFilter filter;

    private final FastAffineModel1D model;

    /**
     * @param model          the model to fit
     * @param iterations     number of RANSAC iterations
     * @param maxEpsilon     maximal allowed transfer error
     * @param minInlierRatio minimal number of inliers to number of candidates
     * @param minNumInliers  minimally required absolute number of inliers
     * @param maxTrust       reject candidates with a cost larger than maxTrust * median cost
     */
    public RansacIntensityMatchingFilter(
            final FastAffineModel1D model,
            final int iterations,
            final double maxEpsilon,
            final double minInlierRatio,
            final int minNumInliers,
            final double maxTrust
    ) {
        this.model = model;
        this.filter = new RansacRegressionReduceFilter(model, iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust);
    }

    @Override
    public FastAffineModel1D model() {
        return model;
    }

    @Override
    public void filter(FlattenedMatches candidates, Collection<PointMatch> reducedMatches) {
        filter.filter(candidates, reducedMatches);
    }
}
