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
package net.preibisch.mvrecon.process.fusion.intensity.mpicbg;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.Point;

/**
 * A 1D point. This is used in the intensity matching algorithm and adds an optimized method for applying a
 * transformation a 1D point. The method doesn't overwrite {@link Point#apply(CoordinateTransform)} since this method
 * is marked as final in the superclass.
 */
public class Point1D extends Point {
	public Point1D(final double l, final double w) {
		super(new double[] { l }, new double[] { w });
	}

	public Point1D(final double l) {
		this(l, l);
	}

	public void applyFast(final CoordinateTransform t) {
		w[0] = l[0];
		t.applyInPlace(w);
	}
}
