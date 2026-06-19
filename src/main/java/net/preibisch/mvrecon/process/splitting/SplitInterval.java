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
package net.preibisch.mvrecon.process.splitting;

import java.util.ArrayList;
import java.util.List;

import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;

public interface SplitInterval
{
	public ArrayList< Interval > split( final Interval input );
	public String description();

	public default int maxIntervalSpread( final List< ViewSetup > oldSetups/*, final long[] overlapPx, final long[] targetSize, final long[] minStepSize, final boolean optimize */ )
	{
		int max = 1;

		//SplitDistributeEvenly split = new SplitDistributeEvenly( overlapPx, targetSize, minStepSize, optimize );

		for ( final ViewSetup oldSetup : oldSetups )
		{
			final Interval input = new FinalInterval( oldSetup.getSize() );
			final ArrayList< Interval > intervals = split( input );

			max = Math.max( max, intervals.size() );
		}

		return max;
	}

}
