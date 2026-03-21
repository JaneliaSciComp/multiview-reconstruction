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
package net.preibisch.mvrecon.headless.deconvolution;

import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;

import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccessible;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.deconvolution.normalization.AdjustInput;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.psf.PSFCombination;
import net.preibisch.mvrecon.process.psf.PSFExtraction;

public class SpecialDecon
{
	public static < T extends RealType< T > > double[] centerofmass( final RandomAccessibleInterval< T > img, final double threshold )
	{
		final IterableInterval< T > interval = Views.iterable( img );
		final double[] center = new double[ img.numDimensions() ];

		for ( int d = 0; d < img.numDimensions(); ++d )
		{
			final RealSum s = new RealSum();
			final RealSum i = new RealSum();
			final Cursor< T > c = interval.localizingCursor();

			while ( c.hasNext() )
			{
				final double intensity = c.next().getRealDouble();
				if ( intensity >= threshold && c.getIntPosition( 0 ) < 90 )
				{
					s.add( intensity * c.getDoublePosition( d ) );
					i.add( intensity );
				}
			}

			center[ d ] = s.getSum() / i.getSum();
		}

		return center;
	}

	public static void avgPSFs( final File dir, final String contains, final double threshold ) throws IncompatibleTypeException
	{
		final String[] psfNames = dir.list( new FilenameFilter()
		{
			
			@Override
			public boolean accept( File dir, String name )
			{
				if ( name.contains( contains ) )
					return true;
				else
					return false;
			}
		} );

		final ArrayList< ArrayImg< FloatType, ? > > psfs = new ArrayList<>();

		for ( final String psfName : psfNames )
			psfs.add( IOFunctions.openAs32BitArrayImg( new File( dir, psfName ) ) );

		IOFunctions.println( "Opened " + psfs.size() + " psfs, size = " + Util.printInterval( psfs.get( 0 ) ));

		final long[] dim = new long[] { 71, 179, 95 };

		final Img< FloatType > i0 = psfs.get( 0 );
		final double[] center0 = computeCenter( i0, threshold );
		IOFunctions.println( "com0: " + Util.printCoordinates( center0 ) );
		final Img< FloatType > psf0 = extractLI( i0, dim, center0 );

		//DisplayImage.getImagePlusInstance( psf0, false, "img0", Double.NaN, Double.NaN ).show();

		for ( int j = 1; j < 10; j++ )
		{
			final Img< FloatType > iB = psfs.get( j );
			final double[] centerB = computeCenter( iB, threshold );

			IOFunctions.println( "com: " + Util.printCoordinates( centerB ) );

			/*
			final Img< FloatType > psfB = extractNN( iB, dim, centerB );
			// do the alignment
			Align< FloatType > lkAlign = new Align<>( psf0, new ArrayImgFactory< FloatType >(), new TranslationWarp( psf0.numDimensions() ) );
			AffineTransform res = lkAlign.align( psfB, 100, 0.01 );

			if (lkAlign.didConverge())
				IOFunctions.println("(" + new Date( System.currentTimeMillis() ) + ") determined transformation:" +  Util.printCoordinates( res.getRowPackedCopy() ) );
			else
				IOFunctions.println("(" + new Date( System.currentTimeMillis() ) + ") registration did not converge" );

			centerB[ 0 ] += res.getRowPackedCopy()[ 3 ];
			centerB[ 1 ] += res.getRowPackedCopy()[ 7 ];
			centerB[ 2 ] += res.getRowPackedCopy()[ 11 ];

			IOFunctions.println( "comB: " + Util.printCoordinates( centerB ) );*/

			final Img< FloatType > psf = extractLI( iB, dim, centerB );

			addTo( psf0, psf );
			//DisplayImage.getImagePlusInstance( psf, false, "img"+j, Double.NaN, Double.NaN ).show();
		}

		for ( int d = 0; d < psf0.numDimensions(); ++d )
		{
			final Img< FloatType > minProjection = PSFCombination.computeProjection( psf0, d, false );
			PSFExtraction.subtractProjection( psf0, minProjection, d );
		}

		subtract( psf0, 15.0f );

		System.out.println( AdjustInput.sumImg( psf0 ) );
		FusionTools.getImagePlusInstance( psf0, false, "psf_" + contains, Double.NaN, Double.NaN, DisplayImage.service ).show();
	}

	public static void addTo( final Img< FloatType > img1, final Img< FloatType > img2 )
	{
		final Cursor< FloatType > c1 = img1.cursor();
		final Cursor< FloatType > c2 = img2.cursor();

		while ( c1.hasNext() )
			c1.next().add( c2.next() );
	}

	public static void subtract( final Img< FloatType > img1, final float value )
	{
		for ( final FloatType f : img1 )
			f.set( Math.max( 0, f.get() - value ) );
	}

	public static double[] computeCenter( final Img< FloatType > i, final double threshold ) throws IncompatibleTypeException
	{
		final Img<FloatType> copy = i.copy();
		Gauss3.gauss( 2, Views.extendMirrorSingle( copy ), copy );
		
		return centerofmass( copy, threshold );
	}

	public static Img< FloatType > extractLI( final Img< FloatType > img, final long[] dim, final double[] center )
	{
		final Img< FloatType > psf = new ArrayImgFactory< FloatType >().create( dim, new FloatType() );
		final ArrayList< RealLocalizable > locations = new ArrayList<>();
		locations.add( new RealPoint( center ) );
		//locations.add( new RealPoint( new double[] { i.dimension( 0 ) / 2, i.dimension( 1 ) / 2, i.dimension( 2 ) / 2 } ) );
		final RealRandomAccessible< FloatType > rra = Views.interpolate( Views.extendMirrorSingle( img ), new NLinearInterpolatorFactory<>() ); 
		PSFExtraction.extractPSFLocal( rra, locations, psf );
		return psf;
	}

	public static Img< FloatType > extractNN( final Img< FloatType > img, final long[] dim, final double[] center )
	{
		final Img< FloatType > psf = new ArrayImgFactory< FloatType >().create( dim, new FloatType() );
		final ArrayList< RealLocalizable > locations = new ArrayList<>();
		locations.add( new RealPoint( center ) );
		//locations.add( new RealPoint( new double[] { i.dimension( 0 ) / 2, i.dimension( 1 ) / 2, i.dimension( 2 ) / 2 } ) );
		final RealRandomAccessible< FloatType > rra = Views.interpolate( Views.extendMirrorSingle( img ), new NearestNeighborInterpolatorFactory<>());
		PSFExtraction.extractPSFLocal( rra, locations, psf );
		return psf;
	}

	public static double sumImg( final File dir, final String fileName )
	{
		final Img< FloatType > img = IOFunctions.openAs32BitArrayImg( new File( dir, fileName ) );
		//subtract( img, 105 );
		//DisplayImage.getImagePlusInstance( img, false, fileName, Double.NaN, Double.NaN ).show();
		return AdjustInput.sumImg( img );
	}

	public static void main( String[] args ) throws IncompatibleTypeException
	{
		new ImageJ();

		final File dir = new File( "/Users/spreibi/Documents/BIMSB/Projects/Betzig/testDataforStephen_2017_12_14/PSF/z150nm" );
		final File dir2 = new File( "/Users/spreibi/Documents/BIMSB/Projects/Betzig/testDataforStephen_2017_12_14/cell" );

		//avgPSFs( dir, "img_CamA_ch0", 115 );
		double i0 = sumImg (dir2, "img_Iter_000_CamA_ch0_CAM1_stack0000_488nm_0000000msec_0003092664msecAbs_000x_000y_000z_0000t.tif" );
		IOFunctions.println();
		
		//avgPSFs( dir, "img_CamA_ch1", 115 );
		double i1 = sumImg (dir2, "img_Iter_000_CamA_ch1_CAM1_stack0000_488nm_0000000msec_0003092664msecAbs_000x_000y_000z_0000t.tif" );
		IOFunctions.println();
		
		//avgPSFs( dir, "img_CamA_ch2", 120 );
		double i2 = sumImg (dir2, "img_Iter_000_CamA_ch2_CAM1_stack0000_488nm_0000000msec_0003092664msecAbs_000x_000y_000z_0000t.tif" );
		
		System.out.println( "i1/i0: " + i1/i0 );
		System.out.println( "i1/i2: " + i1/i2 );
	}
}
