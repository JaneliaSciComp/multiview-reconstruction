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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import ij.process.AutoThresholder;

public class AutoThresholdTest
{
	// ~3.5G voxels, mostly background: AutoThresholder.Li overflowed and threw AIOOBE on this
	@Test
	public void largeHistogramMatchesSmallOne()
	{
		final long[] large = new long[ 256 ];
		final int[] small = new int[ 256 ];
		for ( int i = 0; i < 256; ++i )
		{
			large[ i ] = i < 20 ? 150_000_000L : 2_000_000L;
			small[ i ] = i < 20 ? 75 : 1;
		}

		final int[] scaled = AutoThreshold.toIntHistogram( large );
		final AutoThresholder at = new AutoThresholder();

		for ( final AutoThresholder.Method m : new AutoThresholder.Method[] { AutoThresholder.Method.Li, AutoThresholder.Method.Otsu } )
			assertEquals( at.getThreshold( m, small ), at.getThreshold( m, scaled ), m.name() );
	}
}
