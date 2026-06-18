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
package net.preibisch.mvrecon.process.interestpointregistration.global.convergence;

import mpicbg.models.TileConfiguration;

public class SimpleIterativeConvergenceStrategy extends IterativeConvergenceStrategy
{
	public static double minMaxError = 0.75; // three-quarters of a pixel, ok.

	final double relativeThreshold;
	final double absoluteThreshold;

	public SimpleIterativeConvergenceStrategy(
			final double maxAllowedError,
			final int maxIterations,
			final int maxPlateauwidth,
			final double relativeThreshold,
			final double absoluteThreshold )
	{
		super( maxAllowedError, maxIterations, maxPlateauwidth );

		this.relativeThreshold = relativeThreshold;
		this.absoluteThreshold = absoluteThreshold;
	}

	public SimpleIterativeConvergenceStrategy(
			final double maxAllowedError,
			final double relativeThreshold,
			final double absoluteThreshold )
	{
		super( maxAllowedError );

		this.relativeThreshold = relativeThreshold;
		this.absoluteThreshold = absoluteThreshold;
	}

	@Override
	public boolean isConverged( TileConfiguration tc )
	{
		double avgErr = tc.getError();
		double maxErr = tc.getMaxError();

		// the minMaxError makes sure that no links are dropped if the maximal error is already below a pixel
		if ( ( ( avgErr*relativeThreshold < maxErr && maxErr > minMaxError ) || avgErr > absoluteThreshold ) )
			return false;
		else
			return true;
	}
}
