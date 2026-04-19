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
import java.util.List;
import mpicbg.spim.data.sequence.ViewId;

public class ViewPairCoefficientMatches implements Serializable {

	private final ViewId view1;
	private final ViewId view2;
	private final List<IntensityMatcher.CoefficientMatch> coefficientMatches;

	public ViewPairCoefficientMatches(
			final ViewId view1,
			final ViewId view2,
			final List<IntensityMatcher.CoefficientMatch> coefficientMatches
	) {
		this.view1 = view1;
		this.view2 = view2;
		this.coefficientMatches = coefficientMatches;
	}

	public ViewId view1() {
		return view1;
	}

	public ViewId view2() {
		return view2;
	}

	public List<IntensityMatcher.CoefficientMatch> coefficientMatches() {
		return coefficientMatches;
	}
}
