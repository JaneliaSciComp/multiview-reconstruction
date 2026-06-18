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
package net.preibisch.mvrecon.process.fusion.blk;

import java.util.Arrays;

import ij.ImageJ;
import ij.ImagePlus;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.parallel.Parallelization;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import util.BlockSupplierUtils;

public class ContentBased
{
	public static float defaultScale = 2048;
	public static double defaultContentBasedSigma1 = 20;
	public static double defaultContentBasedSigma2 = 40;

	/*
	 * Conceptually, the given {@code interval} is filled with content-based weights, then transformed with {@code transform}.
	 * <p>
	 * Content weights are {@code w => 0}.
	 * <p>
	 *
	 * @param interval
	 * @param border
	 * @param blending
	 * @param transform
	 */
	public static < T extends RealType< T > > BlockSupplier< FloatType > create(
			final RandomAccessibleInterval< T > input,
			final double[] sigma1,
			final double[] sigma2,
			final float scale )
	{
		// convert to float
		final RandomAccessibleInterval< FloatType > inputImg;

		if ( FloatType.class.isInstance( input.firstElement() ) )
			inputImg = (RandomAccessibleInterval<FloatType>) input;
		else
			inputImg = Converters.convertRAI(
				input,
				(i,o) -> o.set( i.getRealFloat() ),
				new FloatType() );

		return new ContentBasedBlockSupplier(inputImg, sigma1, sigma2, scale );
	}

	/*
	 * compute ( I - gauss(I,sigma1) )^2
	 */
	private static class Step1BlockSupplier implements BlockSupplier< FloatType >
	{
		private static final FloatType type = new FloatType();

		final RandomAccessible< FloatType > source;
		final double[] sigma;

		Step1BlockSupplier( final RandomAccessible< FloatType > img, final double[] sigma )
		{
			this.source = img;
			this.sigma = sigma.clone();
		}

		@Override
		public FloatType getType() { return type; }

		@Override
		public int numDimensions() { return source.numDimensions(); }

		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final float[] output = ( float[] ) dest;

			final RandomAccessibleInterval<FloatType> target =
					Views.translate(
							ArrayImgs.floats( output, interval.dimensionsAsLongArray() ),
							interval.minAsLongArray() );

			Parallelization.runSingleThreaded( () -> Gauss3.gauss( sigma, source, target ) );

			int i = 0;
			for ( final FloatType t : Views.flatIterable( Views.interval( source, interval ) ) )
			{
				final float diff = t.get() - output[ i ];
				output[ i++ ] = diff * diff;
			}
		}

		@Override
		public BlockSupplier<FloatType> threadSafe() { return this; }

		@Override
		public BlockSupplier<FloatType> independentCopy() { return this; }
	}

	private static class ContentBasedBlockSupplier implements BlockSupplier< FloatType >
	{
		private final int n = 3;
		private static final FloatType type = new FloatType();

		final double[] sigma2;
		final float scale;
		final Step1BlockSupplier step1BlockSupplier;

		ContentBasedBlockSupplier(
				final RandomAccessibleInterval< FloatType > inputImg,
				final double[] sigma1,
				final double[] sigma2,
				final float scale )
		{
			this.sigma2 = sigma2;
			this.scale = scale;
			this.step1BlockSupplier = new Step1BlockSupplier( Views.extendMirrorSingle( inputImg ), sigma1 ); // computes ( I - gauss(I,sigma1) )^2
		}

		@Override
		public FloatType getType() { return type; }

		@Override
		public int numDimensions() { return n; }

		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final float[] weights = ( float[] ) dest;

			final long[] kernelSize = new long[ n ];
			Arrays.setAll( kernelSize, d -> Gauss3.halfkernelsize( sigma2[ d ] ) - 1 ); // -1 because it includes the center pixel

			final Interval requiredInputInterval = Intervals.expand( interval, kernelSize );

			// compute ( ( I - I*sigma1 )^2 )
			final RandomAccessibleInterval<FloatType> step1 = Views.translate(
					BlockSupplierUtils.arrayImg( step1BlockSupplier, requiredInputInterval ),
					requiredInputInterval.minAsLongArray() );

			final RandomAccessibleInterval<FloatType> target = Views.translate(
					ArrayImgs.floats(weights, interval.dimensionsAsLongArray() ),
					interval.minAsLongArray() );

			// compute ( ( I - I*sigma1 )^2 ) * sigma2
			Parallelization.runSingleThreaded( () -> Gauss3.gauss( sigma2, step1, target ) );

			// put the weights into a "reasonable" range, since we cannot normalize the entire image [0...1]
			for ( int i = 0; i < weights.length; ++i )
				weights[ i ] /= scale;
		}

		@Override
		public BlockSupplier<FloatType> threadSafe() { return this; }//return new ContentBasedBlockSupplier(inputImg, transform); }

		@Override
		public BlockSupplier<FloatType> independentCopy() { return this; }//return new ContentBasedBlockSupplier(inputImg, transform); }
		
	}

	public static void main( String[] args )
	{
		new ImageJ();

		final ImagePlus imp = new ImagePlus( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL19_Angle0_cropped.tif" );

		final Img< UnsignedByteType > img = ImageJFunctions.wrap( imp );

		final RandomAccessibleInterval< FloatType > inputImg =
				Converters.convertRAI(
						img,
						(i,o) -> o.set( i.getRealFloat() ), // fake 16-bit range 
						new FloatType() );

		ImageJFunctions.show( inputImg ).setDisplayRange(0, 255);

		// test step1
		//Step1BlockSupplier step1Blocks = new Step1BlockSupplier( Views.extendMirrorSingle( inputImg ), new double[]{ 10, 10, 10 } );
		//CachedCellImg<FloatType, ?> step1 = BlockSupplierUtils.cellImgBoundedCache( step1Blocks, inputImg.dimensionsAsLongArray(), new int[] { 100, 100, 10 }, 1000);
		//ImageJFunctions.show( step1 ).setDisplayRange(0, 50);

		// test ContentBasedBlockSupplier

		ContentBasedBlockSupplier cbBlocks = new ContentBasedBlockSupplier( inputImg, new double[]{ 10, 10, 10 }, new double[]{ 20, 20, 20 }, 1 );
		CachedCellImg<FloatType, ?> cb = BlockSupplierUtils.cellImgBoundedCache( cbBlocks, inputImg.dimensionsAsLongArray(), new int[] { 100, 100, 10 }, 1000);
		
		ImageJFunctions.show( cb ).setDisplayRange(0, 50);

	}
}
