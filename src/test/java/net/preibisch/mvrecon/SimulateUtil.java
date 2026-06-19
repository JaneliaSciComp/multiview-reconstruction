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
package net.preibisch.mvrecon;

import net.imglib2.FinalInterval;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;

public class SimulateUtil
{
	/**
	 * delta for testing transformations, locations, etc
	 */
	public static final double delta = 0.00001;

	public static SpimData2 setUp()
	{
		// Generate 2 views with 200 beads, smaller image size for faster tests
		final int[] angles = new int[]{ 0, 45, 90 };
		final int axis = 0;
		final int numPoints = 200;
		final double[] sigma = new double[]{ 1, 1, 3 };
		final FinalInterval range = new FinalInterval( 128, 128, 50 );

		return SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( angles, axis, numPoints, sigma, range ) );
	}

	public static SpimData2 setUpLarge()
	{
		final int[] angles = new int[]{ 0, 45, 90, 135 };
		final int axis = 0;
		final int numPoints = 1000;
		final double[] sigma = new double[]{ 1, 1, 3 };
		final FinalInterval range = new FinalInterval( 512, 512, 200 );

		return SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( angles, axis, numPoints, sigma, range ) );
	}
}
