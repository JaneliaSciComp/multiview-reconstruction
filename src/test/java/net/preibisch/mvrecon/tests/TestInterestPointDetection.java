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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoG;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGParameters;

public class TestInterestPointDetection
{
	private SpimData2 spimData;

	@BeforeEach
	public void setUp()
	{
		spimData = SimulateUtil.setUp();
	}

	@Test
	public void testDoGSegmentation()
	{
		testDoG( spimData, "beads" );

		final InterestPoints list0 = spimData.getViewInterestPoints().getViewInterestPointLists( new ViewId( 0, 0 ) ).getInterestPointList( "beads" );
		final InterestPoints list1 = spimData.getViewInterestPoints().getViewInterestPointLists( new ViewId( 0, 1 ) ).getInterestPointList( "beads" );
		final InterestPoints list2 = spimData.getViewInterestPoints().getViewInterestPointLists( new ViewId( 0, 2 ) ).getInterestPointList( "beads" );

		final Map<Integer, InterestPoint> points0 = list0.getInterestPointsCopy();
		final Map<Integer, InterestPoint> points1 = list1.getInterestPointsCopy();
		final Map<Integer, InterestPoint> points2 = list2.getInterestPointsCopy();

		assertEquals( 162, points0.size(), "number of interest points is expected to be of a certain size." );
		assertEquals( 91, points1.size(), "number of interest points is expected to be of a certain size." );
		assertEquals( 59, points2.size(), "number of interest points is expected to be of a certain size." );

		// test x coordinates for first, y coordinates for second, and z coordinates for third view
		final double[] x0 = points0.values().stream().map( ip -> ip.getL()[ 0 ] ).mapToDouble( Double::doubleValue ).toArray();
		final double[] y1 = points1.values().stream().map( ip -> ip.getL()[ 1 ] ).mapToDouble( Double::doubleValue ).toArray();
		final double[] z2 = points2.values().stream().map( ip -> ip.getL()[ 2 ] ).mapToDouble( Double::doubleValue ).toArray();

		final double medianX0 = Util.median( x0 );
		final double medianY1 = Util.median( y1 );
		final double medianZ2 = Util.median( z2 );

		assertEquals( 58.37869967778258, medianX0, SimulateUtil.delta, "median of x values of first view should be a certain value." );
		assertEquals( 61.81804601215178, medianY1, SimulateUtil.delta, "median of y values of second view should be a certain value." );
		assertEquals( 25.192354308314773, medianZ2, SimulateUtil.delta, "median of z values of third view should be a certain value." );

	}

	// ==================== Original main() method for manual testing ====================
	public static void testDoG( SpimData2 spimData, final String label )
	{
		DoGParameters dog = new DoGParameters();

		dog.imgloader = spimData.getSequenceDescription().getImgLoader();
		dog.toProcess = new ArrayList< ViewDescription >();
		dog.toProcess.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		// filter not present ViewIds
		final List< ViewDescription > removed = SpimData2.filterMissingViews( spimData, dog.toProcess );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		dog.downsampleXY = 2;
		dog.downsampleZ = 1;
		dog.sigma = 1.1;

		//dog.deviceList = spim.headless.cuda.CUDADevice.getSeparableCudaList( "lib/libSeparableConvolutionCUDALib.so" );
		//dog.cuda = spim.headless.cuda.CUDADevice.separableConvolution;

		//		DoG.findInterestPoints( dog );
		// TODO: make cuda headless
		//dog.deviceList = spim.headless.cuda.CUDADevice.getSeparableCudaList( "lib/libSeparableConvolutionCUDALib.so" );
		//dog.cuda = spim.headless.cuda.CUDADevice.separableConvolution;

		final HashMap< ViewId, List< InterestPoint > > points = DoG.findInterestPoints( dog );

		InterestPointTools.addInterestPoints( spimData, label, points, "DoG, sigma=" + dog.sigma + ", downsampleXY=" + dog.downsampleXY );
	}

	public static void main( String[] args ) throws SpimDataException
	{
		final SpimData2 spimData = SimulateUtil.setUpLarge();

		testDoG( spimData, "beads" );
	}

}
