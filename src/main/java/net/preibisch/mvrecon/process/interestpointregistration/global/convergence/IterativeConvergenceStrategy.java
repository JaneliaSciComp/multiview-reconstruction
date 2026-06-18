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

public abstract class IterativeConvergenceStrategy extends ConvergenceStrategy
{
	public IterativeConvergenceStrategy( 
			final double maxAllowedError,
			final int maxIterations,
			final int maxPlateauwidth )
	{
		super( maxAllowedError, maxIterations, maxPlateauwidth );
	}

	public IterativeConvergenceStrategy( final double maxAllowedError )
	{
		super( maxAllowedError );
	}

	public abstract boolean isConverged( final TileConfiguration tc );
}
