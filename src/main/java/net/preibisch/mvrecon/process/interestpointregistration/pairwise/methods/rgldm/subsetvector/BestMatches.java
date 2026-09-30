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
package net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.subsetvector;

import java.util.Arrays;

/** running best / second-best B owner per A descriptor */
class BestMatches
{
	final double[] bestDist, secondDist;
	final int[] bestOwner;

	BestMatches( final int numA )
	{
		bestDist = new double[ numA ];
		secondDist = new double[ numA ];
		bestOwner = new int[ numA ];
		Arrays.fill( bestDist, Double.MAX_VALUE );
		Arrays.fill( secondDist, Double.MAX_VALUE );
		Arrays.fill( bestOwner, -1 );
	}

	void fold( final int indexA, final int ownerB, final double dist )
	{
		if ( ownerB == bestOwner[ indexA ] )
		{
			if ( dist < bestDist[ indexA ] )
				bestDist[ indexA ] = dist;
		}
		else if ( dist < bestDist[ indexA ] )
		{
			secondDist[ indexA ] = bestDist[ indexA ];
			bestDist[ indexA ] = dist;
			bestOwner[ indexA ] = ownerB;
		}
		else if ( dist < secondDist[ indexA ] )
			secondDist[ indexA ] = dist;
	}
}
