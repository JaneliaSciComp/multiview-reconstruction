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
package net.preibisch.mvrecon.process.deconvolution;

import java.util.concurrent.ExecutorService;

import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.complex.ComplexFloatType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.deconvolution.normalization.AdjustInput;
import net.preibisch.mvrecon.process.deconvolution.util.Mirror;

import util.FFTConvolution;

/**
 * Handles the PSF for a specific view for the deconvolution
 *
 * @author stephan.preibisch@gmx.de
 *
 */
public class DeconViewPSF
{
	public static enum PSFTYPE { OPTIMIZATION_II, OPTIMIZATION_I, EFFICIENT_BAYESIAN, INDEPENDENT };

	final private PSFTYPE psfType;
	final private ArrayImg< FloatType, ? > kernel1;

	// will be initialized in the init() call
	private ArrayImg< FloatType, ? > kernel2 = null;
	private int[] blockSize = null;
	private ExecutorService service = null;

	// will be populated if getKernel1FFT() or getKernel2FFT() are called, only possible after init()
	private Img< ComplexFloatType > fftKernel1 = null, fftKernel2 = null;

	public DeconViewPSF( final ArrayImg< FloatType, ? > kernel )
	{
		this( kernel, PSFTYPE.INDEPENDENT );
	}

	public DeconViewPSF( final ArrayImg< FloatType, ? > kernel, final PSFTYPE psfType )
	{
		this.kernel1 = kernel;
		this.psfType = psfType;
	}

	public ArrayImg< FloatType, ? > getKernel1() { return kernel1; }
	public ArrayImg< FloatType, ? > getKernel2() { return kernel2; }

	public Img< ComplexFloatType > getKernel1FFT()
	{
		if ( fftKernel1 == null )
		{
			if ( service == null )
				throw new RuntimeException( "getKernel1FFT can only be called after calling init().");

			final long[] min = new long[ kernel1.numDimensions() ];
			final long[] max = new long[ kernel1.numDimensions() ];

			final Pair< Interval, Interval > fftIntervals = FFTConvolution.setupFFTs( new FinalInterval( Util.int2long( blockSize ) ), kernel1, min, max );
			fftKernel1 = FFTConvolution.computeKernelFFT( fftIntervals.getB(), min, max, false, Views.extendValue( kernel1, new FloatType() ), new ArrayImgFactory< ComplexFloatType >(), service );
		}

		return fftKernel1;
	}
	
	public Img< ComplexFloatType > getKernel2FFT()
	{
		if ( fftKernel2 == null )
		{
			if ( service == null )
				throw new RuntimeException( "getKernel2FFT can only be called after calling init().");

			final long[] min = new long[ kernel2.numDimensions() ];
			final long[] max = new long[ kernel2.numDimensions() ];

			final Pair< Interval, Interval > fftIntervals = FFTConvolution.setupFFTs( new FinalInterval( Util.int2long( blockSize ) ), kernel2, min, max );
			fftKernel2 = FFTConvolution.computeKernelFFT( fftIntervals.getB(), min, max, false, Views.extendValue( kernel2, new FloatType() ), new ArrayImgFactory< ComplexFloatType >(), service );
		}

		return fftKernel2;
	}

	/**
	 * Init the PSF, once the Views object contains all views
	 *
	 * @param views - contains all views and their respective PSFs
	 * @param blockSize - if necessary precompute the FFT of the PSFs given the input size
	 */
	public void init( final DeconViews views, final int[] blockSize )
	{
		this.blockSize = blockSize;
		this.service = views.getExecutorService();

		// normalize kernel so that sum of all pixels == 1
		AdjustInput.normToSum1( kernel1 );

		if ( views.getViews().size() == 1 || psfType == PSFTYPE.INDEPENDENT )
		{
			// compute the inverted kernel (switch dimensions)
			this.kernel2 = computeInvertedKernel( this.kernel1 );
		}
		else if ( psfType == PSFTYPE.EFFICIENT_BAYESIAN )
		{
			// compute the compound kernel P_v^compound of the efficient bayesian multi-view deconvolution
			// for the current view \phi_v(x_v)
			//
			// P_v^compound = P_v^{*} prod{w \in W_v} P_v^{*} \ast P_w \ast P_w^{*}
			
			// we first get P_v^{*} -> {*} refers to the inverted coordinates
			final ArrayImg< FloatType, ? > tmp = computeInvertedKernel( this.kernel1.copy() );

			// now for each view: w \in W_v
			for ( final DeconView view : views.getViews() )
			{
				if ( view.getPSF() != this )
				{
					// convolve first P_v^{*} with P_w
					Img< FloatType > input = computeInvertedKernel( this.kernel1 );
					Img< FloatType > kernel = view.getPSF().getKernel1();
					Img< FloatType > output = input.factory().create( input, input.firstElement() );

					final FFTConvolution< FloatType > conv1 = new FFTConvolution< FloatType >(
							Views.extendZero( input ),
							input,
							Views.extendZero( kernel ),
							kernel,
							output,
							new ArrayImgFactory< ComplexFloatType >() );

					conv1.setExecutorService( views.getExecutorService() );
					conv1.setKeepImgFFT( false );
					conv1.convolve();

					// and now convolve the result with P_w^{*}
					input = output;
					kernel = computeInvertedKernel( view.getPSF().getKernel1() );
					output = input.factory().create( input, input.firstElement() );
					final FFTConvolution< FloatType > conv2 = new FFTConvolution< FloatType >(
							Views.extendZero( input ),
							input,
							Views.extendZero( kernel ),
							kernel,
							output,
							new ArrayImgFactory< ComplexFloatType >() );

					conv2.setExecutorService( views.getExecutorService() );
					conv2.setKeepImgFFT( false );
					conv2.convolve();

					// multiply the result with P_v^{*} yielding the compound kernel
					final Cursor< FloatType > cursor = tmp.cursor();
					for ( final FloatType t : output )
					{
						cursor.fwd();
						cursor.get().set( t.get() * cursor.get().get() );
					}
				}
			}

			// norm the compound kernel
			AdjustInput.normToSum1( tmp );

			// set it as kernel2 of the deconvolution
			this.kernel2 = ( tmp );
		}
		else if ( psfType == PSFTYPE.OPTIMIZATION_I )
		{
			// compute the simplified compound kernel P_v^compound of the efficient bayesian multi-view deconvolution
			// for the current view \phi_v(x_v)
			//
			// P_v^compound = P_v^{*} prod{w \in W_v} P_v^{*} \ast P_w

			// we first get P_v^{*} -> {*} refers to the inverted coordinates
			final ArrayImg< FloatType, ? > tmp = ( this.kernel1.copy() );

			// now for each view: w \in W_v
			for ( final DeconView view : views.getViews() )
			{
				if ( view.getPSF() != this )
				{
					Img< FloatType > input = this.kernel1;
					Img< FloatType > kernel = computeInvertedKernel( view.getPSF().getKernel1() );
					Img< FloatType > output = input.factory().create( input, input.firstElement() );

					final FFTConvolution< FloatType > conv = new FFTConvolution< FloatType >(
							Views.extendZero( input ),
							input,
							Views.extendZero( kernel ),
							kernel,
							output,
							new ArrayImgFactory< ComplexFloatType >() );

					conv.setExecutorService( views.getExecutorService() );
					conv.setKeepImgFFT( false );
					conv.convolve();

					// multiply with the kernel
					final Cursor< FloatType > cursor = tmp.cursor();
					for ( final FloatType t : output )
					{
						cursor.fwd();
						cursor.get().set( t.get() * cursor.get().get() );
					}
				}
			}

			// norm the compound kernel
			AdjustInput.normToSum1( tmp );

			// compute the inverted kernel
			this.kernel2 = computeInvertedKernel( tmp );
		}
		else //if ( iterationType == PSFTYPE.OPTIMIZATION_II )
		{
			// compute the squared kernel and its inverse
			final ArrayImg< FloatType, ? > exponentialKernel = computeExponentialKernel( this.kernel1, views.getViews().size() );

			// norm the squared kernel
			AdjustInput.normToSum1( exponentialKernel );

			// compute the inverted squared kernel
			this.kernel2 = computeInvertedKernel( exponentialKernel );	
		}
	}

	public static ArrayImg< FloatType, ? > computeExponentialKernel( final ArrayImg< FloatType, ? > kernel, final int numViews )
	{
		final ArrayImg< FloatType, ? > exponentialKernel = kernel.copy();

		for ( final FloatType f : exponentialKernel )
			f.set( pow( f.get(), numViews ) );

		return exponentialKernel;
	}

	public static ArrayImg< FloatType, ? > computeInvertedKernel( final ArrayImg< FloatType, ? > kernel )
	{
		final ArrayImg< FloatType, ? > invKernel = kernel.copy();

		for ( int d = 0; d < invKernel.numDimensions(); ++d )
			Mirror.mirror( invKernel, d, Threads.numThreads() );

		return invKernel;
	}

	final private static float pow( final float value, final int power )
	{
		float result = value;

		for ( int i = 1; i < power; ++i )
			result *= value;

		return result;
	}

}
