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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import mpicbg.models.IdentityModel;
import mpicbg.models.Model;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.iterator.IntervalIterator;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityMatcher.CoefficientMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class IntensitySolver {

	private static final Logger LOG = LoggerFactory.getLogger(IntensitySolver.class);

	private final int[] numCoefficients;

	private final Model<?> modelTemplate;

	private final Map<ViewId, IntensityTile> intensityTiles = new ConcurrentHashMap<>();

	/** the tiles each tile shares real matches with (pairs without matches do not count) */
	private final Map<IntensityTile, java.util.Set<IntensityTile>> partners = new ConcurrentHashMap<>();


	/** views whose foreground median is below this fraction of the common level are not equilibrated (empty views) */
	public static double MIN_EQUILIBRATED_LEVEL = 0.25;

	/** see {@link #setEquilibration} */
	private Map<ViewId, Double> equilibrationMedians = null;
	private double equilibrationWeight = 0;

	/** per tile: its matched intensities' sum of squares and count; their rms is where its equilibration is placed */
	private final Map<IntensityTile, double[]> intensitySq = new ConcurrentHashMap<>();

	/**
	 * Pull every tile toward a common level: one match per sub-tile from the view's foreground median to the median of
	 * all views' foreground medians, weighted {@code weight (median partners / partners)^2}, partners = tiles it shares
	 * real matches with. Weakly connected tiles (the end of a chain) then follow the common level instead of their own
	 * raw level, which is all an IDENTITY regularizer can pull them toward (on the Ciona dataset, 2,2,2 with IDENTITY
	 * 0.01: the last tile 138 against 78-136, before 252; seams 10% from 15%). The match is the ratio median -&gt;
	 * target placed at the rms of the tile's own matched intensities, so that its weight is relative to one of the
	 * tile's data points: an L2 fit weights a point by its intensity squared, so at the median itself it would vanish
	 * next to the matches at the overlaps' brightest voxels. It assumes the specimen is
	 * about equally bright in every tile, hence a soft prior. With it, the equilibration tile is the fixed one.
	 *
	 * @param medians per-view foreground median; views without one are not equilibrated
	 * @param weight 0 = off
	 */
	public void setEquilibration(final Map<ViewId, Double> medians, final double weight) {
		this.equilibrationMedians = medians;
		this.equilibrationWeight = weight;
	}

	IntensitySolver(final int[] coefficientsSize) {
		this(coefficientsSize, IntensityCorrection.regularizedAffineModel1D(0.01, 0.01));
	}

	/**
	 * @param modelTemplate 1D model to fit per sub-tile ({@code AffineModel1D}, {@code TranslationModel1D},
	 *        {@code IdentityModel}, or any {@code InterpolatedAffineModel1D}); it is copied for every
	 *        sub-tile, the given instance is never modified
	 */
	IntensitySolver(final int[] coefficientsSize, final Model<?> modelTemplate) {
		this.numCoefficients = coefficientsSize;
		this.modelTemplate = modelTemplate.copy();
	}

	public void connect(final ViewPairCoefficientMatches matches) {
		connect(matches.view1(), matches.view2(), matches.coefficientMatches());
	}

	public void connect(
			final ViewId p1,
			final ViewId p2,
			final List<CoefficientMatch> coefficientMatches
	) {
		if (coefficientMatches.isEmpty())
			return;

		final boolean disconnected = coefficientMatches.stream()
				.map(CoefficientMatch::matches)
				.allMatch(Collection::isEmpty);

		final IntensityTile p1IntensityTile = getIntensityTile(p1);
		final IntensityTile p2IntensityTile = getIntensityTile(p2);
		for (final CoefficientMatch coefficientMatch : coefficientMatches) {
			final Tile<?> st1 = p1IntensityTile.getSubTileAtIndex(coefficientMatch.coeff1());
			final Tile<?> st2 = p2IntensityTile.getSubTileAtIndex(coefficientMatch.coeff2());
			if (disconnected) {
				identityConnect(st1, st2, 1E-6);
			} else {
				final Collection<PointMatch> matches = coefficientMatch.matches();
				if (!matches.isEmpty()) {
					for (final PointMatch pm : matches) {
							final double p = pm.getP1().getL()[0], q = pm.getP2().getL()[0];
							addSquare(p1IntensityTile, p * p);
							addSquare(p2IntensityTile, q * q);
						}
						st1.connect(st2, matches);
				}
			}
		}
		p1IntensityTile.connectTo(p2IntensityTile);
		if (!disconnected) {
			partners.computeIfAbsent(p1IntensityTile, k -> ConcurrentHashMap.newKeySet()).add(p2IntensityTile);
			partners.computeIfAbsent(p2IntensityTile, k -> ConcurrentHashMap.newKeySet()).add(p1IntensityTile);
		}
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	IntensityTile getIntensityTile(final ViewId viewId) {
		final int nFittingCycles = 1; // TODO: expose parameter (?)
		return intensityTiles.computeIfAbsent(
				viewId,
				v -> new IntensityTile(
						(Supplier) () -> modelTemplate.copy(),
						numCoefficients,
						nFittingCycles));
	}

	Map<ViewId, IntensityTile> getIntensityTiles() {
		return intensityTiles;
	}

	public void solveForGlobalCoefficients(final int iterations) {

		final IntensityTile equilibrationTile = new IntensityTile(IdentityModel::new, new int[] {1, 1, 1}, 1);
		connectTilesWithinPatches(intensityTiles, equilibrationTile);

		/* optimize */
		final List<IntensityTile> tiles = new ArrayList<>(intensityTiles.values());

		// anchor the equilibration tile if it is used, otherwise anchor a random tile (the first one)
		final IntensityTile fixedTile;
		final boolean equilibrate = equilibrationWeight > 0 && equilibrationMedians != null && connectToEquilibration(equilibrationTile);
		if (equilibrate) {
			tiles.add(equilibrationTile);
			fixedTile = equilibrationTile;
		} else {
			fixedTile = tiles.get(0);
		}

		final int numThreads = Runtime.getRuntime().availableProcessors(); // TODO: expose parameter

		LOG.info("solveForGlobalCoefficients: optimizing {} tiles with {} threads", tiles.size(), numThreads);
		final IntensityTileOptimizer optimizer = new IntensityTileOptimizer(0.01, iterations, iterations, 0.75, numThreads);
		optimizer.optimize(tiles, fixedTile);

		intensityTiles.forEach((tileId, tile) -> {
			tile.updateDistance();

			// TODO (???)
//			final double error = tile.getDistance();
//			final Map<ViewId, Double> errorMap = new HashMap<>();
//			errorMap.put(tileId, error);
//			blockData.getResults().recordAllErrors(tileId, errorMap);
		});

		LOG.info("solveForGlobalCoefficients: exit, returning intensity coefficients for {} tiles", intensityTiles.size());
	}

	private void addSquare(final IntensityTile tile, final double square) {
		intensitySq.compute(tile, (k, v) -> {
			final double[] a = v == null ? new double[2] : v;
			a[0] += square;
			a[1] += 1;
			return a;
		});
	}

	/** @return whether any tile was connected to the equilibration tile */
	private boolean connectToEquilibration(final IntensityTile equilibrationTile) {
		final double[] medians = intensityTiles.keySet().stream().map(equilibrationMedians::get).filter(m -> m != null && m > 0)
				.mapToDouble(Double::doubleValue).sorted().toArray();
		if (medians.length == 0)
			return false;
		final double target = medians[medians.length / 2];
		final double[] counts = intensityTiles.values().stream()
				.mapToDouble(t -> partners.getOrDefault(t, java.util.Collections.emptySet()).size()).sorted().toArray();
		final double medianPartners = counts[counts.length / 2];
		LOG.info("equilibration toward {} (median of the views' foreground medians), weight {}", target, equilibrationWeight);
		for (final Map.Entry<ViewId, IntensityTile> e : intensityTiles.entrySet()) {
			final Double m = equilibrationMedians.get(e.getKey());
			if (m == null || !(m > 0))
				continue;
			// a view far below the common level has no specimen to speak of (its "foreground" is noise above a threshold
			// that found nothing): pulling it up would only break its seams and drag its neighbours along
			if (m < MIN_EQUILIBRATED_LEVEL * target) {
				LOG.info("equilibration: view {} is left out, its foreground median {} is below {} of the common level", e.getKey().getViewSetupId(), m, MIN_EQUILIBRATED_LEVEL);
				continue;
			}
			final int n = Math.max(1, partners.getOrDefault(e.getValue(), java.util.Collections.emptySet()).size());
			final double w = equilibrationWeight * (medianPartners / n) * (medianPartners / n);
			final double[] sq = intensitySq.get(e.getValue());
			if (sq == null || sq[1] == 0)
				continue; // no matches: nothing to scale the pull to, and nothing for it to compete with
			final double rms = Math.sqrt(sq[0] / sq[1]);
			e.getValue().connectTo(equilibrationTile);
			for (int i = 0; i < e.getValue().nSubTiles(); ++i)
				e.getValue().getSubTileAtIndex(i).connect(equilibrationTile.getSubTileAtIndex(0),
						Collections.singletonList(new PointMatch1D(new Point1D(rms), new Point1D(rms * target / m), w)));
		}
		return true;
	}

	private void connectTilesWithinPatches(
			final Map<ViewId, IntensityTile> coefficientTiles,
			final IntensityTile equilibrationTile) {

		for (final IntensityTile tile : coefficientTiles.values()) {
			final int[] gridSize = tile.getSubTileGridSize();
			final int n = gridSize.length;
			final int[] pos = new int[n];
			final IntervalIterator iter = new IntervalIterator(gridSize);
			while (iter.hasNext()) {
				iter.fwd();
				iter.localize(pos);
				final Tile<?> st0 = tile.getSubTileAt(pos);
				for (int d = 0; d < n; d++) {
					if (pos[d] > 0) {
						pos[d]--;
						final Tile<?> st1 = tile.getSubTileAt(pos);
						pos[d]++;
						identityConnect(st0, st1);
					}
				}
			}
		}
	}

	// TODO: Here we use (0 -> 0) and (1 -> 1) as point matches that represent
	//       the identity transform. This made sense in Render (where this code
	//       is copied from), because image intensities are normalized to [0,1].
	//       However, we don't normalize intensities in multiview-reconstruciton,
	//       so probably something else should be used here? Dataset min/max?
	//       Tile min/max?
	private static void identityConnect(final Tile<?> t1, final Tile<?> t2, final double weight) {
		final ArrayList<PointMatch> matches = new ArrayList<>();
		matches.add(new PointMatch1D(new Point1D(0), new Point1D(0), weight));
		matches.add(new PointMatch1D(new Point1D(1), new Point1D(1), weight));
		t1.connect(t2, matches);
	}

	private static void identityConnect(final Tile<?> t1, final Tile<?> t2) {
		identityConnect(t1, t2, 1.0);
	}

}
