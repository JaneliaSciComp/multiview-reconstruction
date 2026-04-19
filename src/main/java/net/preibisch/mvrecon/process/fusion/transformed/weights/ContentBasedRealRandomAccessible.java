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
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.Img;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyGauss;

public class ContentBasedRealRandomAccessible implements RealRandomAccessible< FloatType >
{
	public static float defaultScale = 2048;

	final RandomAccessibleInterval< FloatType > entropy;
	final RealRandomAccessible< FloatType > entropyRRA;

	public < T extends RealType< T > >  ContentBasedRealRandomAccessible(
			final RandomAccessibleInterval< T > input,
			final double[] sigma1,
			final double[] sigma2,
			final int[] blocksize,
			final float scale )
	{
		// convert to float
		final RandomAccessibleInterval< FloatType > inputImg =
				Converters.convertRAI(
						input,
						(i,o) -> o.set( i.getRealFloat() ),
						new FloatType() );

		// compute gauss(I,sigma1)
		final RandomAccessibleInterval< FloatType > s1 =
				LazyGauss.init(
						Views.extendMirrorDouble( inputImg ),
						new FinalInterval( inputImg ),
						new FloatType(),
						sigma1,
						blocksize );

		// compute compute ( I - gauss(I,sigma1) )^2
		final RandomAccessibleInterval< FloatType > tmp =
				Converters.convertRAI(
						inputImg,
						s1,
						(i1,i2,o) -> {
							final float diff = i1.get() - i2.get();
							o.set( diff * diff );
						},
						new FloatType() );

		// compute ( ( I - I*sigma1 )^2 ) * sigma2
		final RandomAccessibleInterval< FloatType > tmp2 =
				LazyGauss.init( Views.extendMirrorDouble( tmp ), new FinalInterval( inputImg), new FloatType(), sigma2, blocksize );

		// put the weights into a "reasonable" range, since we cannot normalize the entire image [0...1]
		this.entropy = Converters.convertRAI(
				tmp2,
				(i,o) -> o.set( (float)/*Math.sqrt*/( i.getRealFloat() ) / scale ),
				new FloatType() );

		this.entropyRRA = 
				Views.interpolate( Views.extendZero( entropy ), new NLinearInterpolatorFactory< FloatType >() );
	}

	@Override
	public RealRandomAccess<FloatType> realRandomAccess()
	{ 
		return entropyRRA.realRandomAccess();
	}

	@Override
	public RealRandomAccess<FloatType> realRandomAccess( final RealInterval interval )
	{
		return entropyRRA.realRandomAccess( interval );
	}

	@Override
	public FloatType getType()
	{
		return new FloatType();
	}

	@Override
	public int numDimensions() { return entropy.numDimensions(); }

	public RandomAccessibleInterval< FloatType > getEntropy() { return entropy; }
	public RealRandomAccessible< FloatType > entropyRRA() { return entropyRRA; }

	public static void main( String[] args ) throws IncompatibleTypeException
	{
		new ImageJ();

		final ImagePlus imp = new ImagePlus( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL19_Angle0_cropped.tif" );

		final Img< UnsignedByteType > img = ImageJFunctions.wrap( imp );

		final RandomAccessibleInterval< FloatType > inputImg =
				Converters.convertRAI(
						img,
						(i,o) -> o.set( i.getRealFloat() ), // fake 16-bit range 
						new FloatType() );

		ImageJFunctions.show( inputImg );

		final double[] sigma1 = new double[]{ 10, 10, 10 };
		final double[] sigma2 = new double[]{ 20, 20, 20 };

		final ContentBasedRealRandomAccessible cb =
				new ContentBasedRealRandomAccessible(inputImg, sigma1, sigma2, new int[] { 64, 64, 16 }, defaultScale );

		ImageJFunctions.show( cb.getEntropy(), DeconViews.createExecutorService() );
	}
}
