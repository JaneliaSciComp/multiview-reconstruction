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
