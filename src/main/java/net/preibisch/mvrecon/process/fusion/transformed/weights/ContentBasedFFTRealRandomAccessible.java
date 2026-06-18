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
package net.preibisch.mvrecon.process.fusion.transformed.weights;

import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import util.FFTConvolution;

/**
 * Computes the content-based fusion on a given image
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 * @param <T> pixel type
 */
public class ContentBasedFFTRealRandomAccessible< T extends RealType< T > > implements RealRandomAccessible< FloatType >
{
	/**
	 * The Img containing the approxmimated content-based weights
	 */
	final Img< FloatType > contentBasedImg;
	final int n;
	
	public ContentBasedFFTRealRandomAccessible(
			final RandomAccessibleInterval< T > input,
			final ImgFactory< ComplexFloatType > imgFactory,
			final double[] sigma1,
			final double[] sigma2 )
	{
		this.n = input.numDimensions();
		
		this.contentBasedImg = approximateEntropy(
				Converters.convert( input, new RealFloatConverter< T >(),  new FloatType() ),
				imgFactory,
				sigma1,
				sigma2 );
	}
	
	public Img< FloatType > getContentBasedImg() { return contentBasedImg; }
	
	protected Img< FloatType > approximateEntropy(
			final RandomAccessibleInterval< FloatType > input,
			final ImgFactory< ComplexFloatType > imgFactory,
			final double[] sigma1,
			final double[] sigma2 )

	{
		// the result
		ImgFactory<FloatType> f;
		try
		{
			f = imgFactory.imgFactory( new FloatType() );
		}
		catch (IncompatibleTypeException e)
		{
			f = new CellImgFactory< FloatType >( new FloatType() );
		}
		
		final Img< FloatType > conv = f.create( input );
		
		// compute I*sigma1
		FFTConvolution< FloatType > fftConv = new FFTConvolution<FloatType>( input, createGaussianKernel( sigma1 ), conv, imgFactory );
		fftConv.convolve();
		
		// compute ( I - I*sigma1 )^2
		final Cursor< FloatType > c = conv.cursor();
		final RandomAccess< FloatType > r = input.randomAccess();
		
		while ( c.hasNext() )
		{
			c.fwd();
			r.setPosition( c );
			
			final float diff = c.get().get() - r.get().get();
			c.get().set( diff * diff );
		}
		
		// compute ( ( I - I*sigma1 )^2 ) * sigma2
		fftConv = new FFTConvolution<FloatType>( conv, createGaussianKernel( sigma2 ), imgFactory );
		fftConv.convolve();

		// normalize to [0...1]
		FusionTools.normalizeImage( conv );

		return conv;
	}
	
	@Override
	public int numDimensions() { return contentBasedImg.numDimensions(); }

	@Override
	public RealRandomAccess<FloatType> realRandomAccess()
	{ 
		return Views.interpolate(
			Views.extendZero( this.contentBasedImg ),
			new NLinearInterpolatorFactory< FloatType >()
			).realRandomAccess();
	}

	@Override
	public RealRandomAccess<FloatType> realRandomAccess( final RealInterval interval )
	{
		return Views.interpolate(
				Views.extendZero( this.contentBasedImg ),
				new NLinearInterpolatorFactory< FloatType >()
				).realRandomAccess( interval );
	}

	final private static Img< FloatType > createGaussianKernel( final double[] sigmas )
	{
		final int numDimensions = sigmas.length;

		final long[] imageSize = new long[ numDimensions ];
		final double[][] kernel = new double[ numDimensions ][];

		for ( int d = 0; d < numDimensions; ++d )
		{
			kernel[ d ] = Util.createGaussianKernel1DDouble( sigmas[ d ], true );
			imageSize[ d ] = kernel[ d ].length;
		}

		final Img< FloatType > kernelImg = ArrayImgs.floats( imageSize );

		final Cursor< FloatType > cursor = kernelImg.localizingCursor();
		final int[] position = new int[ numDimensions ];

		while ( cursor.hasNext() )
		{
			cursor.fwd();
			cursor.localize( position );

			double value = 1;

			for ( int d = 0; d < numDimensions; ++d )
				value *= kernel[ d ][ position[ d ] ];

			cursor.get().set( ( float ) value );
		}

		return kernelImg;
	}
	
	public static void main( String[] args ) throws IncompatibleTypeException
	{
		new ImageJ();
		
		ImagePlus imp = new ImagePlus( "/Users/preibischs/workspace/TestLucyRichardson/src/resources/dros-1.tif" );
		
		Img< FloatType > img = ImageJFunctions.wrap( imp );

		final double[] sigma1 = new double[]{ 20, 20 };
		final double[] sigma2 = new double[]{ 30, 30 };
		
		ContentBasedFFTRealRandomAccessible< FloatType > cb = new ContentBasedFFTRealRandomAccessible<FloatType>( img, img.factory().imgFactory( new ComplexFloatType() ), sigma1, sigma2 );
		
		ImageJFunctions.show( cb.getContentBasedImg() );
	}

	@Override
	public FloatType getType()
	{
		return new FloatType();
	}
}
