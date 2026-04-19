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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RealInterval;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityMatcher.CoefficientMatch;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;

import static net.imglib2.util.Intervals.intersect;
import static net.imglib2.util.Intervals.isEmpty;

public class IntensityCorrection {

	public static class SerializableRealInterval implements RealInterval, Serializable {

		private static final long serialVersionUID = 1L;

		private final double[] min;
		private final double[] max;

		public SerializableRealInterval(final RealInterval interval) {
			min = interval.minAsDoubleArray();
			max = interval.maxAsDoubleArray();
		}

		public static RealInterval serializable(final RealInterval interval) {
			if (interval instanceof Serializable)
				return interval;
			else
				return new SerializableRealInterval(interval);
		}

		@Override
		public boolean equals(final Object obj) {
			return obj instanceof SerializableRealInterval && Intervals.equals(this, (RealInterval) obj, 0.0);
		}

		@Override
		public int hashCode() {
			return Util.combineHash(Arrays.hashCode(min), Arrays.hashCode(max));
		}

		@Override
		public double realMin(final int d) {
			return min[d];
		}

		@Override
		public double realMax(final int d) {
			return max[d];
		}

		@Override
		public int numDimensions() {
			return min.length;
		}

		@Override
		public String toString() {
			return "SerializableRealInterval{" +
					"min=" + Arrays.toString(min) +
					", max=" + Arrays.toString(max) +
					'}';
		}
	}

	public static RealInterval getBounds(final AbstractSpimData<?> spimData, final ViewId viewId) {
		final TileInfo tile = new TileInfo(new int[] {1, 1, 1}, spimData, viewId);
		return IntensityMatcher.getBounds(tile);
	}

	/**
	 * Given a list of {@code ViewIds} in the order they were acquired (such
	 * that views later in the list have been potentially bleached by earlier
	 * views), finds for each {@code ViewId} the potential "bleachers" (earlier
	 * views with overlapping bounds).
	 *
	 * @param views list of all {@code ViewIds} in the order they were acquired
	 * @param viewBounds maps {@code ViewId} to bounding box in world coordinates
	 * @return maps every ViewId to the ViewIds of potential bleachers
	 */
	public static Map<ViewId, List<ViewId>> getPotentialBleachers(
			final List<ViewId> views,
			final Map<ViewId, RealInterval> viewBounds
	) {
		final Map<ViewId, List<ViewId>> viewBleachers = new HashMap<>();
		for (int i = 0; i < views.size(); ++i) {
			final ViewId view0 = views.get(i);
			final RealInterval bounds0 = viewBounds.get(view0);
			final List<ViewId> bleachers = new ArrayList<>();
			viewBleachers.put(view0, bleachers);
			for (int j = 0; j < i; ++j) {
				final ViewId view1 = views.get(j);
				final RealInterval bounds1 = viewBounds.get(view1);
				if (!isEmpty(intersect(bounds0, bounds1))) {
					bleachers.add(view1);
				}
			}
		}
		return viewBleachers;
	}

    public static ViewPairCoefficientMatches matchRansac(
            final AbstractSpimData<?> spimData,
            final ViewId viewId1,
            final ViewId viewId2,
            final double renderScale,
            final int[] coefficientsSize,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates,
            final int iterations,
            final double maxEpsilon,
            final double minInlierRatio,
            final int minNumInliers,
            final double maxTrust
    ) {
        return matchRansac(spimData,
                viewId1, Collections.emptyList(),
                viewId2, Collections.emptyList(),
                (v, i) -> v,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust);
    }

    public static ViewPairCoefficientMatches matchRansac(
            final AbstractSpimData<?> spimData,
            final ViewId viewId1,
            final List<ViewId> viewId1Bleachers,
            final ViewId viewId2,
            final List<ViewId> viewId2Bleachers,
            final UnbleachFunction unbleachFunction,
            final double renderScale,
            final int[] coefficientsSize,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates,
            final int iterations,
            final double maxEpsilon,
            final double minInlierRatio,
            final int minNumInliers,
            final double maxTrust
    ) {
        final IntensityMatcher matcher = new IntensityMatcher(spimData, renderScale, coefficientsSize, unbleachFunction);
        final List<CoefficientMatch> match = matcher.match(
                viewId1, viewId1Bleachers, viewId2, viewId2Bleachers,
                minIntensity, maxIntensity, minNumCandidates, iterations, maxEpsilon,
                minInlierRatio, minNumInliers, maxTrust);
        return new ViewPairCoefficientMatches(viewId1, viewId2, match);
    }

    public static ViewPairCoefficientMatches matchHistograms(
            final AbstractSpimData<?> spimData,
            final ViewId viewId1,
            final ViewId viewId2,
            final double renderScale,
            final int[] coefficientsSize,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates
    ) {
        return matchHistograms(spimData,
                viewId1, Collections.emptyList(),
                viewId2, Collections.emptyList(),
                (v, i) -> v,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates);
    }

    public static ViewPairCoefficientMatches matchHistograms(
            final AbstractSpimData<?> spimData,
            final ViewId viewId1,
            final List<ViewId> viewId1Bleachers,
            final ViewId viewId2,
            final List<ViewId> viewId2Bleachers,
            final UnbleachFunction unbleachFunction,
            final double renderScale,
            final int[] coefficientsSize,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates
    ) {
        final IntensityMatcher matcher = new IntensityMatcher(spimData, renderScale, coefficientsSize, unbleachFunction);
        final List<CoefficientMatch> match = matcher.match(
                viewId1, viewId1Bleachers, viewId2, viewId2Bleachers,
                minIntensity, maxIntensity, minNumCandidates);
        return new ViewPairCoefficientMatches(viewId1, viewId2, match);
    }

    public static Map<ViewId, Coefficients> solve(
			final int[] coefficientsSize,
			final Collection<ViewPairCoefficientMatches> pairwiseMatches,
			final int iterations
	){
		final IntensitySolver solver = new IntensitySolver(coefficientsSize);
		pairwiseMatches.forEach(solver::connect);
		solver.solveForGlobalCoefficients(iterations);
		final Map<ViewId, IntensityTile> intensityTiles = solver.getIntensityTiles();
		final Map<ViewId, Coefficients> coefficients = new HashMap<>();
		intensityTiles.forEach((k, v) -> coefficients.put(k, v.getCoefficients()));
		return coefficients;
	}

	// TODO: this should become part of SpimData2 I'd say? Ultimately this is a property of the dataset that should also be displayed and potentially used during reconstruction
	public static void writeCoefficients(
			final N5Writer n5Writer,
			final String group,
			final String dataset,
			final Map<ViewId, Coefficients> coefficients
	) {
		coefficients.forEach((viewId, tile) -> {
			final int setupId = viewId.getViewSetupId();
			final int timePointId = viewId.getTimePointId();
			final String path = getCoefficientsDatasetPath(group, dataset, setupId, timePointId);
			CoefficientsIO.save(tile, n5Writer, path);
		});
	}

	public static Coefficients readCoefficients(
			final N5Reader n5Reader,
			final String group,
			final String dataset,
			final ViewId viewId
	) {
		final String path = getCoefficientsDatasetPath(group, dataset, viewId.getViewSetupId(), viewId.getTimePointId());
		return CoefficientsIO.load(n5Reader, path);
	}

	/**
	 * Get N5 path to coefficients for the specified view, as {@code "{group}/setup{setupId}/timepoint{timepointId}/{dataset}"}.
	 */
	static String getCoefficientsDatasetPath(
			final String group,
			final String dataset,
			final int setupId,
			final int timePointId
	) {
		return String.format("%s/setup%d/timepoint%d/%s", group, setupId, timePointId, dataset);
	}
}
