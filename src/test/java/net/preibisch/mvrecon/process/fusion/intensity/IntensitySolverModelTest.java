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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import mpicbg.models.IdentityModel;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel1D;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityMatcher.CoefficientMatch;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IntensitySolverModelTest {

	private static final int[] COEFFICIENTS_SIZE = { 1, 1, 1 };
	private static final int ITERATIONS = 500;
	private static final double OFFSET = 30.0;

	/**
	 * Two views, one coefficient region each, view2 intensities = view1 intensities + 30.
	 */
	private static List<ViewPairCoefficientMatches> twoViewMatches() {
		final List<PointMatch> matches = new ArrayList<>();
		matches.add(new PointMatch1D(new Point1D(50), new Point1D(50 + OFFSET), 1.0));
		matches.add(new PointMatch1D(new Point1D(200), new Point1D(200 + OFFSET), 1.0));
		final CoefficientMatch coefficientMatch = new CoefficientMatch(0, 0, 1000, matches);
		return Collections.singletonList(new ViewPairCoefficientMatches(
				new ViewId(0, 0), new ViewId(0, 1), Collections.singletonList(coefficientMatch)));
	}

	@Test
	public void defaultModelSolves() {
		final Map<ViewId, Coefficients> coefficients =
				IntensityCorrection.solve(COEFFICIENTS_SIZE, twoViewMatches(), ITERATIONS);
		assertEquals(2, coefficients.size());
		coefficients.values().forEach(c -> {
			assertTrue(Double.isFinite(c.flattenedCoefficients[0][0]));
			assertTrue(Double.isFinite(c.flattenedCoefficients[1][0]));
		});
	}

	@Test
	public void translationModelKeepsUnitScaleAndReconcilesOffset() {
		final Map<ViewId, Coefficients> coefficients = IntensityCorrection.solve(
				COEFFICIENTS_SIZE, twoViewMatches(), ITERATIONS, new TranslationModel1D());
		assertEquals(2, coefficients.size());

		// translation cannot scale: m00 stays exactly 1 (also the regression test
		// for coefficient extraction from non-AffineModel1D sub-tile models)
		coefficients.values().forEach(c -> assertEquals(1.0, c.flattenedCoefficients[0][0], 0.0));

		// the two per-view offsets must compensate the intensity difference of 30
		final double[] offsets = coefficients.values().stream()
				.mapToDouble(c -> c.flattenedCoefficients[1][0]).toArray();
		assertEquals(OFFSET, Math.abs(offsets[0] - offsets[1]), 0.1);
	}

	@Test
	public void identityModelLeavesCoefficientsUntouched() {
		final Map<ViewId, Coefficients> coefficients = IntensityCorrection.solve(
				COEFFICIENTS_SIZE, twoViewMatches(), ITERATIONS, new IdentityModel());
		assertEquals(2, coefficients.size());
		coefficients.values().forEach(c -> {
			assertEquals(1.0, c.flattenedCoefficients[0][0], 0.0);
			assertEquals(0.0, c.flattenedCoefficients[1][0], 0.0);
		});
	}

	@Test
	public void regularizedFactoryModelMatchesDefault() {
		final Map<ViewId, Coefficients> viaFactory = IntensityCorrection.solve(
				COEFFICIENTS_SIZE, twoViewMatches(), ITERATIONS,
				IntensityCorrection.regularizedAffineModel1D(0.01, 0.01));
		final Map<ViewId, Coefficients> viaDefault =
				IntensityCorrection.solve(COEFFICIENTS_SIZE, twoViewMatches(), ITERATIONS);
		assertEquals(viaDefault.size(), viaFactory.size());
		viaFactory.forEach((view, c) -> {
			assertEquals(viaDefault.get(view).flattenedCoefficients[0][0], c.flattenedCoefficients[0][0], 1e-9);
			assertEquals(viaDefault.get(view).flattenedCoefficients[1][0], c.flattenedCoefficients[1][0], 1e-9);
		});
	}
}
