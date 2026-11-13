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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel1D;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityMatcher.CoefficientMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;

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
                viewId1, viewId2,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                new AffineModel1D(),
                iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust);
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
            final Model<?> ransacModel,
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
                ransacModel,
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
        return matchRansac(spimData,
                viewId1, viewId1Bleachers,
                viewId2, viewId2Bleachers,
                unbleachFunction,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                new AffineModel1D(),
                iterations, maxEpsilon, minInlierRatio, minNumInliers, maxTrust);
    }

    /**
     * @param ransacModel 1D model to fit with RANSAC ({@code AffineModel1D}, {@code TranslationModel1D},
     *        or any {@code InterpolatedAffineModel1D}, see {@link #regularizedAffineModel1D}); it is
     *        copied, the given instance is never modified
     */
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
            final Model<?> ransacModel,
            final int iterations,
            final double maxEpsilon,
            final double minInlierRatio,
            final int minNumInliers,
            final double maxTrust
    ) {
        final IntensityMatcher matcher = new IntensityMatcher(spimData, renderScale, coefficientsSize, unbleachFunction);
        final List<CoefficientMatch> match = matcher.match(
                viewId1, viewId1Bleachers, viewId2, viewId2Bleachers,
                minIntensity, maxIntensity, minNumCandidates,
                ransacModel, iterations, maxEpsilon,
                minInlierRatio, minNumInliers, maxTrust);
        return new ViewPairCoefficientMatches(viewId1, viewId2, match);
    }

    /**
     * Create an {@code AffineModel1D} regularized towards {@code TranslationModel1D} and
     * {@code IdentityModel}, the same composition that {@link IntensitySolver} uses for
     * the global solve.
     *
     * @param lambdaTranslation weight of the translation regularizer
     * @param lambdaIdentity weight of the identity regularizer
     */
    public static InterpolatedAffineModel1D<InterpolatedAffineModel1D<AffineModel1D, TranslationModel1D>, IdentityModel>
            regularizedAffineModel1D(final double lambdaTranslation, final double lambdaIdentity)
    {
        return new InterpolatedAffineModel1D<>(
                new InterpolatedAffineModel1D<>(new AffineModel1D(), new TranslationModel1D(), lambdaTranslation),
                new IdentityModel(), lambdaIdentity);
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
                viewId1, viewId2,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                new AffineModel1D());
    }

    public static ViewPairCoefficientMatches matchHistograms(
            final AbstractSpimData<?> spimData,
            final ViewId viewId1,
            final ViewId viewId2,
            final double renderScale,
            final int[] coefficientsSize,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates,
            final Model<?> model
    ) {
        return matchHistograms(spimData,
                viewId1, viewId2,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                model, HistogramIntensityMatchingFilter.DEFAULT_NUM_SAMPLES);
    }

    public static ViewPairCoefficientMatches matchHistograms(
            final AbstractSpimData<?> spimData,
            final ViewId viewId1,
            final ViewId viewId2,
            final double renderScale,
            final int[] coefficientsSize,
            final double minIntensity,
            final double maxIntensity,
            final int minNumCandidates,
            final Model<?> model,
            final int numSamples
    ) {
        return matchHistograms(spimData,
                viewId1, Collections.emptyList(),
                viewId2, Collections.emptyList(),
                (v, i) -> v,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                model, numSamples);
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
        return matchHistograms(spimData,
                viewId1, viewId1Bleachers,
                viewId2, viewId2Bleachers,
                unbleachFunction,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                new AffineModel1D());
    }

    /**
     * @param model 1D model to fit to the matched histogram quantiles ({@code AffineModel1D},
     *        {@code TranslationModel1D}, or any {@code InterpolatedAffineModel1D}, see
     *        {@link #regularizedAffineModel1D}); it is copied, the given instance is never modified
     */
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
            final int minNumCandidates,
            final Model<?> model
    ) {
        return matchHistograms(spimData,
                viewId1, viewId1Bleachers,
                viewId2, viewId2Bleachers,
                unbleachFunction,
                renderScale, coefficientsSize, minIntensity, maxIntensity, minNumCandidates,
                model, HistogramIntensityMatchingFilter.DEFAULT_NUM_SAMPLES);
    }

    /**
     * @param model 1D model to fit to the matched histogram quantiles ({@code AffineModel1D},
     *        {@code TranslationModel1D}, or any {@code InterpolatedAffineModel1D}, see
     *        {@link #regularizedAffineModel1D}); it is copied, the given instance is never modified
     * @param numSamples number of quantile samples taken from each intensity distribution
     *        for the model fit (default 100)
     */
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
            final int minNumCandidates,
            final Model<?> model,
            final int numSamples
    ) {
        final IntensityMatcher matcher = new IntensityMatcher(spimData, renderScale, coefficientsSize, unbleachFunction);
        final List<CoefficientMatch> match = matcher.match(
                viewId1, viewId1Bleachers, viewId2, viewId2Bleachers,
                minIntensity, maxIntensity, minNumCandidates,
                model, numSamples);
        return new ViewPairCoefficientMatches(viewId1, viewId2, match);
    }

    /**
     * Match {@code viewId1} and {@code viewId2} by the ratio of the medians of the voxels both see above
     * {@code minIntensity}, a pure gain per pair of coefficient regions; for a SCALE solve.
     */
    public static ViewPairCoefficientMatches matchMedians(
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
        return new ViewPairCoefficientMatches(viewId1, viewId2, matcher.matchMedians(
                viewId1, viewId1Bleachers, viewId2, viewId2Bleachers, minIntensity, maxIntensity, minNumCandidates));
    }

    public static Map<ViewId, Coefficients> solve(
			final int[] coefficientsSize,
			final Collection<ViewPairCoefficientMatches> pairwiseMatches,
			final int iterations
	){
		return solve(coefficientsSize, pairwiseMatches, iterations, regularizedAffineModel1D(0.01, 0.01));
	}

    /**
     * @param model 1D model to fit per sub-tile ({@code AffineModel1D}, {@code TranslationModel1D},
     *        {@code IdentityModel}, or any {@code InterpolatedAffineModel1D}, see
     *        {@link #regularizedAffineModel1D}); it is copied for every sub-tile, the given
     *        instance is never modified
     */
    public static Map<ViewId, Coefficients> solve(
			final int[] coefficientsSize,
			final Collection<ViewPairCoefficientMatches> pairwiseMatches,
			final int iterations,
			final Model<?> model
	){
		final IntensitySolver solver = new IntensitySolver(coefficientsSize, model);
		pairwiseMatches.forEach(solver::connect);
		solver.solveForGlobalCoefficients(iterations);
		final Map<ViewId, IntensityTile> intensityTiles = solver.getIntensityTiles();
		final Map<ViewId, Coefficients> coefficients = new HashMap<>();
		intensityTiles.forEach((k, v) -> coefficients.put(k, v.getCoefficients()));
		return coefficients;
	}

	/**
	 * Empties every coefficient match whose matched intensities reach less than {@code minSignal} above the
	 * threshold of either view: in tiles without specimen the threshold splits background noise, and the
	 * "intensity ratio" of a few counts above it is noise that would set that tile's gain.
	 *
	 * @param thresholds per-view threshold the matches were gated at; every view in {@code pairwiseMatches} needs one
	 */
	public static List<ViewPairCoefficientMatches> dropWeakMatches(
			final Collection<ViewPairCoefficientMatches> pairwiseMatches,
			final Map<? extends ViewId, Double> thresholds,
			final double minSignal
	) {
		return mapMatches(pairwiseMatches, (m, cm) -> {
			final double t1 = level(thresholds, m.view1()), t2 = level(thresholds, m.view2());
			double max1 = Double.NEGATIVE_INFINITY, max2 = Double.NEGATIVE_INFINITY;
			for (final PointMatch pm : cm.matches()) {
				max1 = Math.max(max1, pm.getP1().getL()[0]);
				max2 = Math.max(max2, pm.getP2().getL()[0]);
			}
			return max1 - t1 < minSignal || max2 - t2 < minSignal ? Collections.emptyList() : cm.matches();
		});
	}

	/**
	 * Adds the two views' background levels as one more match (weight {@code weight}) to every non-empty
	 * coefficient match. The matches are otherwise all foreground, which leaves where a view's model maps
	 * the background to extrapolation; this pins it, so overlapping views' backgrounds come out level.
	 *
	 * @param backgrounds per-view background level; every view in {@code pairwiseMatches} needs one
	 */
	public static List<ViewPairCoefficientMatches> addBackgroundMatches(
			final Collection<ViewPairCoefficientMatches> pairwiseMatches,
			final Map<? extends ViewId, Double> backgrounds,
			final double weight
	) {
		return mapMatches(pairwiseMatches, (m, cm) -> {
			final List<PointMatch> matches = new ArrayList<>(cm.matches());
			matches.add(new PointMatch1D(new Point1D(level(backgrounds, m.view1())), new Point1D(level(backgrounds, m.view2())), weight));
			return matches;
		});
	}

	/** Applies {@code f} to every non-empty coefficient match; empty ones (unmatched regions) are kept as they are. */
	private static List<ViewPairCoefficientMatches> mapMatches(
			final Collection<ViewPairCoefficientMatches> pairwiseMatches,
			final BiFunction<ViewPairCoefficientMatches, CoefficientMatch, Collection<PointMatch>> f
	) {
		final List<ViewPairCoefficientMatches> out = new ArrayList<>(pairwiseMatches.size());
		for (final ViewPairCoefficientMatches m : pairwiseMatches) {
			final List<CoefficientMatch> coefficientMatches = new ArrayList<>(m.coefficientMatches().size());
			for (final CoefficientMatch cm : m.coefficientMatches())
				coefficientMatches.add(cm.matches().isEmpty() ? cm : new CoefficientMatch(cm.coeff1(), cm.coeff2(), cm.numVoxels(), f.apply(m, cm)));
			out.add(new ViewPairCoefficientMatches(m.view1(), m.view2(), coefficientMatches));
		}
		return out;
	}

	private static double level(final Map<? extends ViewId, Double> levels, final ViewId view) {
		final Double level = levels.get(view);
		if (level == null)
			throw new IllegalArgumentException("no level for view (tp=" + view.getTimePointId() + ", setup=" + view.getViewSetupId() + ")");
		return level;
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
	public static String getCoefficientsDatasetPath(
			final String group,
			final String dataset,
			final int setupId,
			final int timePointId
	) {
		return String.format("%s/setup%d/timepoint%d/%s", group, setupId, timePointId, dataset);
	}

	/**
	 * {@code image} (any resolution level of its view: the coefficient field spans the whole image) with the
	 * intensity correction applied, as {@code FloatType}, computed lazily block by block. Voxels below a finite
	 * {@link Coefficients#threshold()} are left unchanged; strip it with {@code withThreshold(Double.NaN)} to
	 * correct every voxel.
	 */
	public static <T extends NativeType<T>> RandomAccessibleInterval<FloatType> correct(
			final RandomAccessibleInterval<T> image,
			final Coefficients coefficients
	) {
		final RandomAccessibleInterval<T> zeroMin = Views.zeroMin(image);
		final RandomAccessibleInterval<FloatType> corrected = BlockSupplier.of(zeroMin)
				.andThen(Convert.convert(new FloatType()))
				.andThen(FastLinearIntensityMap.linearIntensityMap(coefficients, zeroMin))
				.toCellImg(zeroMin.dimensionsAsLongArray(), 64);
		return Views.translate(corrected, image.minAsLongArray());
	}

	/** e.g. "8x8x8 regions, gain 0.91..1.12, offset -3.2..4.0, threshold 105.3" */
	public static String describe(final Coefficients c) {
		final StringBuilder sb = new StringBuilder();
		for (int d = 0; d < c.numDimensions(); ++d)
			sb.append(d == 0 ? "" : "x").append(c.size(d));
		sb.append(" regions");
		final String[] names = {"gain", "offset"};
		for (int i = 0; i < Math.min(names.length, c.numCoefficients()); ++i) {
			final double[] v = c.flattenedCoefficients[i];
			sb.append(String.format(", %s %.3g..%.3g", names[i],
					Arrays.stream(v).min().orElse(Double.NaN), Arrays.stream(v).max().orElse(Double.NaN)));
		}
		if (Double.isFinite(c.threshold()))
			sb.append(String.format(", threshold %.1f", c.threshold()));
		return sb.toString();
	}
}
