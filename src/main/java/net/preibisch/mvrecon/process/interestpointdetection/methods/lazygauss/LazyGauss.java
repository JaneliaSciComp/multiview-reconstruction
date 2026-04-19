/*
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

package net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss;

import java.io.File;
import java.util.function.Consumer;

import ij.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.exception.IncompatibleTypeException;
import net.imglib2.img.basictypeaccess.AccessFlags;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import util.Lazy;

/**
 * Simple Gaussian filter Op
 *
 * @author Stephan Saalfeld
 * @author Stephan Preibisch
 * @param <T> type of input and output
 */
public class LazyGauss<T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final T type;
	final private double[] sigmas;
	final long[] globalMin;
	final private RandomAccessible<T> source;

	public LazyGauss(
			final long[] min,
			final RandomAccessible<T> source,
			final T type,
			final double[] sigmas)
	{
		this.source = source;
		this.globalMin = min;
		this.type = type;
		this.sigmas = sigmas;
	}

	// Note: the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage
	// (but the actual interval to process in many blocks sits somewhere else) 
	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		try
		{
			Gauss3.gauss(sigmas, source, Views.translate( output, globalMin ), 1 );
		}
		catch (final IncompatibleTypeException e)
		{
			throw new RuntimeException(e);
		}
	}

	public static final <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> init(
			final RandomAccessible< T > input,
			final Interval processingInterval,
			final T type,
			final double[] sigma,
			final int[] blockSize )
	{
		final long[] min = processingInterval.minAsLongArray();

		final LazyGauss< T > lazyGauss =
				new LazyGauss<>(
						min,
						input,
						type.createVariable(),
						sigma );

		final RandomAccessibleInterval<T> gauss =
				Views.translate(
						Lazy.process(
								processingInterval,
								blockSize,
								type.createVariable(),
								AccessFlags.setOf(),
								lazyGauss ),
						min );

		return gauss;
	}

	public static void main( String[] args )
	{
		new ImageJ();

		final RandomAccessibleInterval< FloatType > raw =
				IOFunctions.openAs32BitArrayImg( new File( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL18_Angle0.tif"));

		final RandomAccessibleInterval< FloatType > inputCropped = Views.interval( raw, Intervals.expand(raw, new long[] {-200, -200, -20}) );

		ImageJFunctions.show( inputCropped );

		RandomAccessibleInterval<FloatType> gauss = LazyGauss.init(
				inputCropped,
				new FinalInterval(inputCropped),
				new FloatType(),
				new double[] { 2.0, 2.0, 2.0},
				DoGImgLib2.blockSize );

		ImageJFunctions.show( gauss );
	}
}
