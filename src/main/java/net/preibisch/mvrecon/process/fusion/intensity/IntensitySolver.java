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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.PointMatch;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel1D;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.iterator.IntervalIterator;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityMatcher.CoefficientMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class IntensitySolver {

	private static final Logger LOG = LoggerFactory.getLogger(IntensitySolver.class);

	public static double lambda1 = 0.01;
	public static double lambda2 = 0.01;

	private final int[] numCoefficients;

	private final Map<ViewId, IntensityTile> intensityTiles = new ConcurrentHashMap<>();

	IntensitySolver(final int[] coefficientsSize) {
		this.numCoefficients = coefficientsSize;
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
					st1.connect(st2, matches);
				}
			}
		}
		p1IntensityTile.connectTo(p2IntensityTile);
	}

	IntensityTile getIntensityTile(final ViewId viewId) {
		final int nFittingCycles = 1; // TODO: expose parameter (?)
		return intensityTiles.computeIfAbsent(
				viewId,
				v -> new IntensityTile(
						() -> new InterpolatedAffineModel1D<>( new InterpolatedAffineModel1D<>( new AffineModel1D(), new TranslationModel1D(), lambda1), new IdentityModel(), lambda2),//FastAffineModel1D::new,
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
		final double equilibrationWeight = 0.0; // TODO (equilibration)
		// final double equilibrationWeight = blockData.solveTypeParameters().equilibrationWeight();
		if (equilibrationWeight > 0.0) {
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
			// TODO (equilibration)
//			final double equilibrationWeight = blockData.solveTypeParameters().equilibrationWeight();
//			if (equilibrationWeight > 0.0) {
//				final List<Double> averages = results.getAveragesFor(p.getTileId());
//				coefficientTile.connectTo(equilibrationTile);
//				for (int i = 0; i < coefficientTile.nSubTiles(); i++) {
//					equilibrateIntensity(coefficientTile.getSubTile(i),
//							equilibrationTile.getSubTile(0),
//							averages.get(i),
//							equilibrationWeight);
//				}
//			}
		}
	}

	private static void equilibrateIntensity(final Tile<?> coefficientTile,
			final Tile<?> equilibrationTile,
			final Double average,
			final double weight) {
		final PointMatch eqMatch = new PointMatch1D(new Point1D(average), new Point1D(0.5), weight);
		coefficientTile.connect(equilibrationTile, Collections.singletonList(eqMatch));
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
