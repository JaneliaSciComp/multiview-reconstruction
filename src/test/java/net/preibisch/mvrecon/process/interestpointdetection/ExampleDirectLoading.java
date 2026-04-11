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
package net.preibisch.mvrecon.process.interestpointdetection;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

import mpicbg.spim.data.SpimDataException;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5;

public class ExampleDirectLoading
{
	public static void main( String[] args ) throws SpimDataException
	{
		int viewSetupId = 1;

		//
		// works without the XML, just loads the N5 directly
		//
		final InterestPointsN5 ip = new InterestPointsN5(
				URI.create("/nrs/saalfeld/john/for/keller/danio_1_488/dataset-orig-tifs/3/"),
				"tpId_0_viewSetupId_" + viewSetupId  + "/beads8v2" );

		final Map< Integer, InterestPoint> points = ip.getInterestPointsCopy();

		for ( final InterestPoint p : points.values() )
		{
			System.out.println( p.getId() + " " + Arrays.toString( p.getL() ));
		}
	}
}
