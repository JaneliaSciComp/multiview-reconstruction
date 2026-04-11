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
package net.preibisch.mvrecon.process.quality;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Plot;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import mpicbg.spim.data.SpimDataException;
import net.imglib2.Cursor;
import net.imglib2.PointSampleList;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.quality.FRC;
import net.preibisch.mvrecon.process.quality.FRC.ThresholdMethod;
import net.preibisch.mvrecon.process.quality.FRCRealRandomAccessible;
import net.preibisch.mvrecon.process.quality.FRCTools;

public class FRCExample
{
	@Test
	@Disabled("Requires specific dataset file that may not exist on all systems")
	public void testFRC()
	{
		System.out.println( "Test requires manual setup - see main() method" );
	}

	// ==================== Original main() method for manual testing ====================

	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		plot();

		SimpleMultiThreading.threadHaltUnClean();

		//final Img< FloatType > img = IOFunctions.openAs32BitArrayImg( new File( "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL18_Angle0.tif" ) );
		final Img< FloatType > img = IOFunctions.openAs32BitArrayImg( new File(
				"/Users/spreibi/Documents/BIMSB/Projects/CLARITY/Quality assessment/clarity-2.tif" ) );
		final ImagePlus imp = FusionTools.getImagePlusInstance( img, true, "brain", Double.NaN, Double.NaN, DisplayImage.service );
		imp.show();

		testFRCOld( img );
	}

	public static void plot()
	{
		final File inputFile = new File( "/Users/stephanpreibisch/Desktop/Results_WEKA/input-full.tif" );
		final Img< FloatType > input = IOFunctions.openAs32Bit( inputFile, new CellImgFactory< FloatType >( new FloatType() ) );
		final RandomAccessibleInterval< FloatType > img = Views.interval( Views.extendMirrorSingle( input ), input );

		final int zMinDist = FRCRealRandomAccessible.relativeFRCDist;
		final int distanceZ = 1;

		final FRCRealRandomAccessible< FloatType > frcList = FRCTools.distributeGridFRC( input, 0.1, distanceZ, 256, true, zMinDist, null );
		final PointSampleList< FloatType > qualityList = frcList.getQualityList();

		final HashMap<Integer, Double> zLocations = new HashMap<Integer, Double>();
		final HashMap<Integer, Integer> zLocationsCount = new HashMap<Integer, Integer>();

		final Cursor< FloatType > cursor = qualityList.localizingCursor();
		while (cursor.hasNext())
		{
			cursor.fwd();
			final int z = cursor.getIntPosition(2);

			if ( zLocations.containsKey(z) )
			{
				double v = zLocations.get(z);
				int c = zLocationsCount.get(z);

				zLocations.put(z, v + cursor.get().get());
				zLocationsCount.put(z, c + 1);
			}
			else
			{
				zLocations.put(z, cursor.get().getRealDouble());
				zLocationsCount.put(z, 1);
			}
		}

		final ArrayList<Integer> z = new ArrayList<Integer>( zLocations.keySet() );
		Collections.sort(z );
		for ( final int zl : z )
		{

			System.out.println( zl + "\t" + zLocations.get(zl) / (double)zLocationsCount.get(zl));
		}

		/*
		for ( int z = zMinDist; z < input.dimension( 2 ) - zMinDist; z += distanceZ )
		{
			final int dimX = (int)img.dimension( 0 );
			final int dimY = (int)img.dimension( 1 );
			final int dim = Math.min( dimX, dimY );

			final FloatProcessor fp0 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z, dim );
			final FloatProcessor fp1 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z + 1, dim );
			final FloatProcessor fp2 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z - 1, dim );

			final FRC frc = new FRC();

			double[][] frcCurveA = frc.calculateFrcCurve( fp0, fp1 );
			double[][] frcCurveB = frc.calculateFrcCurve( fp0, fp2 );

			final double integralA = FRCRealRandomAccessible.integral( frcCurveA );
			final double integralB = FRCRealRandomAccessible.integral( frcCurveB );

			final double integral =  ( integralA + integralB ) / 2.0;

			final double[][] frcCurve = frcCurveA.clone();
			for ( int i = 0; i < frcCurve.length; ++i )
				frcCurve[ i ][ 1 ] = ( frcCurveA[ i ][ 1 ] + frcCurveB[ i ][ 1 ] ) / 2.0;

			final FloatProcessor fpD0 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z - zMinDist, dim );
			final FloatProcessor fpD1 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z + zMinDist, dim );

			final double[][] frcCurveDist =  frc.getSmoothedCurve( frc.calculateFrcCurve( fpD0, fpD1 ) );

			boolean fail = false;

			for ( int i = 0; i < frcCurve.length; ++i )
			{
				if ( !Double.isFinite( frcCurveDist[ i ][ 1 ] ) || !Double.isFinite( frcCurve[ i ][ 1 ] ) )
					fail = true;

				frcCurve[ i ][ 1 ] = frcCurve[ i ][ 1 ] - frcCurveDist[ i ][ 1 ];
			}

			final double relintegral = fail ? 0 : FRCRealRandomAccessible.integral( frcCurve );

			System.out.println( z + "\t" + integral+ "\t" + relintegral );
		}*/

		final ImagePlus imp = FusionTools.getImagePlusInstance( img, true, "brain", Double.NaN, Double.NaN, DisplayImage.service );
		imp.show();
	}

	public static void testFRC( final Img< FloatType > input )
	{
		final FRCRealRandomAccessible< FloatType > frc = FRCTools.distributeGridFRC( input, 0.1, 10, 256, true, FRCRealRandomAccessible.relativeFRCDist, null );
		//final FRCRealRandomAccessible< FloatType > frc = FRCRealRandomAccessible.fixedGridFRC( input, 50, 5, 256, false, false, FRCRealRandomAccessible.relativeFRCDist, null );

		FusionTools.getImagePlusInstance( frc.getRandomAccessibleInterval(), false, "frc", Double.NaN, Double.NaN, DisplayImage.service ).show();
	}

	public static void testFRCOld( final Img< FloatType > input )
	{
		final RandomAccessibleInterval< FloatType > img = Views.interval( Views.extendMirrorSingle( input ), input );

		ThresholdMethod tm = ThresholdMethod.FIXED_1_OVER_7;
		ImageStack stack = null;

		for ( int z = 10/2; z < img.dimension( 2 ) - 10/2; z += 10 )
		{
			final int dimX = (int)img.dimension( 0 );
			final int dimY = (int)img.dimension( 1 );
			final int dim = Math.min( dimX, dimY );

			final FloatProcessor fp0 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z, dim );
			final FloatProcessor fp1 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z + 1, dim );
			//final FloatProcessor fp2 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z - 1, dim );

			final FloatProcessor fpD0 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z - 5, dim );
			final FloatProcessor fpD1 = FRCRealRandomAccessible.getFloatProcessor( img, dimX/2, dimY/2, z + 5, dim );

			final FRC frc = new FRC();

			double[][] frcCurveA = frc.calculateFrcCurve( fp0, fp1 );
			//double[][] frcCurveB = frc.calculateFrcCurve( fp0, fp2 );

			final double[][] frcCurve1 = frcCurveA.clone();
			for ( int i = 0; i < frcCurve1.length; ++i )
				frcCurve1[ i ][ 1 ] = ( frcCurveA[ i ][ 1 ] );// + frcCurveB[ i ][ 1 ] ) / 2.0;

			double[][] frcCurve10 =  frc.getSmoothedCurve( frc.calculateFrcCurve( fpD0, fpD1 ) );

			/*
			final Pair< FloatProcessor, FloatProcessor > fps1 = getTwoImagesA( img, z, 1 );
			final Pair< FloatProcessor, FloatProcessor > fps10 = getTwoImagesA( img, z - 5, 10 );

			//new ImagePlus( "a", fps.getA() ).show();
			//new ImagePlus( "b", fps.getB() ).show();
			//SimpleMultiThreading.threadHaltUnClean();

			final FRC frc = new FRC();

			// Get FIRE Number, assumes you have access to the two image processors.
			double[][] frcCurve1 = frc.calculateFrcCurve( fps1.getA(), fps1.getB() );
			double[][] frcCurve10 =  frc.getSmoothedCurve( frc.calculateFrcCurve( fps10.getA(), fps10.getB() ) );
			*/

			final double[][] frcCurve = frcCurve1.clone();
			for ( int i = 0; i < frcCurve.length; ++i )
				frcCurve[ i ][ 1 ] = frcCurve1[ i ][ 1 ] - frcCurve10[ i ][ 1 ];//Math.max( 0, frcCurve1[ i ][ 1 ] - frcCurve10[ i ][ 1 ] );

			double integral = FRCRealRandomAccessible.integral( frcCurve );

			double fire = frc.calculateFireNumber( frcCurve, tm );
			System.out.println( z + ": " + fire + " " + integral);

			//if ( z== 41 || z== 42 )
			{
			Plot p = frc.doPlot( frcCurve, frc.getSmoothedCurve( frcCurve ), tm, fire, "" + z );
			ImageProcessor ip = p.getImagePlus().getProcessor();
			if ( stack == null )
				stack = new ImageStack( ip.getWidth(), ip.getHeight() );
			stack.addSlice( ip );
			//p.show();
			}



			//break;
		}

		new ImagePlus( "fd", stack ).show();
	}

	public static Pair< FloatProcessor, FloatProcessor > getTwoImagesA( final Img< FloatType > imgIn, final int z, final int dist )
	{
		final RandomAccessible< FloatType > r = Views.extendMirrorSingle( imgIn );
		final RandomAccessibleInterval< FloatType > img = Views.interval( r, imgIn );

		final RandomAccessibleInterval< FloatType > s0 = Views.hyperSlice( img, 2, z );
		final RandomAccessibleInterval< FloatType > s1 = Views.hyperSlice( img, 2, z + dist );

		final FloatProcessor fp0 = new FloatProcessor( (int)s0.dimension( 0 ), (int)s0.dimension( 1 ) );
		final FloatProcessor fp1 = new FloatProcessor( (int)s0.dimension( 0 ), (int)s0.dimension( 1 ) );

		final Cursor< FloatType > c0 = Views.iterable( s0 ).localizingCursor();
		final Cursor< FloatType > c1 = Views.iterable( s1 ).localizingCursor();

		while ( c0.hasNext() )
		{
			c0.fwd();
			c1.fwd();

			fp0.setf( c0.getIntPosition( 0 ), c0.getIntPosition( 1 ), c0.get().get() );
			fp1.setf( c1.getIntPosition( 0 ), c1.getIntPosition( 1 ), c1.get().get() );
		}

		return new ValuePair< FloatProcessor, FloatProcessor >( fp0, fp1 );
	}

	public static Pair< FloatProcessor, FloatProcessor > getTwoImagesB( final Img< FloatType > img, final int z )
	{
		//Random rnd = new Random( System.currentTimeMillis() );
		final RandomAccessibleInterval< FloatType > s0 = Views.hyperSlice( img, 2, z );

		final FloatProcessor fp0 = new FloatProcessor( (int)s0.dimension( 0 ), (int)s0.dimension( 1 ) );
		final FloatProcessor fp1 = new FloatProcessor( (int)s0.dimension( 0 ), (int)s0.dimension( 1 ) );

		final Cursor< FloatType > c0 = Views.iterable( s0 ).localizingCursor();

		while ( c0.hasNext() )
		{
			c0.fwd();

			final int x = c0.getIntPosition( 0 );
			final int y = c0.getIntPosition( 1 );

			if ( (x+y) % 2 == 0 )
			//if ( rnd.nextInt() % 2 == 0 )
				fp0.setf( x, y, c0.get().get() );
			else
				fp1.setf( x, y, c0.get().get() );
		}

		return new ValuePair< FloatProcessor, FloatProcessor >( fp0, fp1 );
	}
}
