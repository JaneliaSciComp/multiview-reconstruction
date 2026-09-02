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

import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.Model;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.PointMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FlattenedMatches;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

class HistogramIntensityMatchingFilter implements IntensityMatchingFilter {

    private static final Logger LOG = LoggerFactory.getLogger(HistogramIntensityMatchingFilter.class);

    static final int DEFAULT_NUM_SAMPLES = 100;

    private final Model<?> model;

    private final int numSamples;

    /**
     * Fit the model to matched quantiles of the two intensity distributions,
     * then reduce to two representative matches (min and max intensity,
     * mapped through the fitted model).
     * <p>
     * Not thread-safe: the model copy is re-fit on every {@code filter()} call.
     *
     * @param model the 1D model to fit ({@code AffineModel1D}, {@code TranslationModel1D},
     *              or any {@code InterpolatedAffineModel1D}); it is copied, the given
     *              instance is never modified
     * @param numSamples number of quantile samples taken from each intensity distribution
     *              for the model fit
     */
    public HistogramIntensityMatchingFilter(final Model<?> model, final int numSamples) {
        if (numSamples < 1)
            throw new IllegalArgumentException("numSamples must be >= 1, was " + numSamples);
        this.model = model.copy();
        this.numSamples = numSamples;
    }

    public HistogramIntensityMatchingFilter(final Model<?> model) {
        this(model, DEFAULT_NUM_SAMPLES);
    }

    @Override
    public Model<?> model() {
        return model;
    }

    @Override
    public void filter(final FlattenedMatches candidates, final Collection<PointMatch> reducedMatches) {
        reducedMatches.clear();

        final double[] histo1 = Arrays.copyOf(candidates.p()[0], candidates.size());
        Arrays.sort(histo1);
        final double[] histo2 = Arrays.copyOf(candidates.q()[0], candidates.size());
        Arrays.sort(histo2);

        final List<PointMatch> matches = new ArrayList<>(numSamples);
        for (int i = 0; i < numSamples; ++i) {
            final double p = histo1[histo1.length * i / numSamples];
            final double q = histo2[histo2.length * i / numSamples];
            matches.add(new PointMatch1D(new Point1D(p), new Point1D(q), 1.0));
        }
        try {
            model.fit(matches);
        } catch (NotEnoughDataPointsException | IllDefinedDataPointsException e) {
            LOG.debug("histogram fit failed", e);
            return;
        }

        final double min = histo1[0];
        final double max = histo1[histo1.length - 1];
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
