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
import mpicbg.models.PointMatch;

/**
 * A match of two 1D points. This is used in the intensity matching algorithm and adds an optimized method for computing
 * the distance of two 1D points.
 */
public class PointMatch1D extends PointMatch {
	public PointMatch1D(final Point1D p1, final Point1D p2) {
		super(p1, p2);
	}

	public PointMatch1D(final Point1D p1, final Point1D p2, final double weight) {
		super(p1, p2, weight);
	}

	@Override
	public void apply(final CoordinateTransform t) {
		final Point1D p1 = (Point1D) this.p1;
		p1.applyFast(t);
	}

	@Override
	public double getDistance(){
		final Point1D p1 = (Point1D) this.p1;
		final Point1D p2 = (Point1D) this.p2;
		return Math.abs(p1.getW()[0] - p2.getW()[0]);
	}
}
