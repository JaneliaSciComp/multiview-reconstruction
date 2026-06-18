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
package net.preibisch.mvrecon.process.cuda;

import net.imglib2.util.Util;

/**
 * Executes gaussian convolution using the native CUDA implementation
 * (https://github.com/StephanPreibisch/SeparableConvolutionCUDALib)
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 */
public class CUDASeparableConvolutionFunctions
{
	public static enum OutOfBounds{ ZERO, VALUE, EXTEND_BORDER_PIXELS };
	final static int[] supportedKernelSizes = new int[]{ 7, 15, 31, 63, 127 };

	final CUDASeparableConvolution cuda;
	final int cudaDeviceId;

	public CUDASeparableConvolutionFunctions( final CUDASeparableConvolution cuda, final int cudaDeviceId )
	{
		this.cuda = cuda;
		this.cudaDeviceId = cudaDeviceId;
	}

	/**
	 * Compute Gaussian convolution with and extend-border-pixels outofbounds strategy
	 * 
	 * @param img - the image (1d/2d/3d) as flat float array
	 * @param dim - the dimensions of the image in 1d/2d/3d
	 * @param sigma - the sigma for all dimensions
	 * @return success? true or false
	 */
	public boolean gauss( final float[] img, final int[] dim, final double sigma )
	{
		return gauss( img, dim, sigma, OutOfBounds.EXTEND_BORDER_PIXELS, 0, cuda, cudaDeviceId );
	}

	/**
	 * Compute Gaussian convolution with and extend-border-pixels outofbounds strategy
	 * 
	 * @param img - the image (1d/2d/3d) as flat float array
	 * @param dim - the dimensions of the image in 1d/2d/3d
	 * @param sigma - the sigma per dimension
	 * @return success? true or false
	 */
	public boolean gauss( final float[] img, final int[] dim, final double[] sigma )
	{
		return gauss( img, dim, sigma, OutOfBounds.EXTEND_BORDER_PIXELS, 0, cuda, cudaDeviceId );
	}

	public boolean gauss( final float[] img, final int[] dim, final float[] sigma )
	{
		final double[] sigmaD = new double[ sigma.length ];

		for ( int d = 0; d < sigmaD.length; ++d )
			sigmaD[ d ] = sigma[ d ];

		return gauss( img, dim, sigmaD, OutOfBounds.EXTEND_BORDER_PIXELS, 0, cuda, cudaDeviceId );
	}

	/**
	 * Compute Gaussian convolution
	 * 
	 * @param img - the image (1d/2d/3d) as flat float array
	 * @param dim - the dimensions of the image in 1d/2d/3d
	 * @param sigma - the sigma for all dimensions
	 * @param oobs - the OutOfBounds strategy
	 * @param oobsValue - value to use in oobs
	 * @return success? true or false
	 */
	public boolean gauss( final float[] img, final int[] dim, final double sigma, final OutOfBounds oobs, final float oobsValue )
	{
		return gauss( img, dim, sigma, oobs, oobsValue, cuda, cudaDeviceId );
	}

	/**
	 * Compute Gaussian convolution
	 * 
	 * @param img - the image (1d/2d/3d) as flat float array
	 * @param dim - the dimensions of the image in 1d/2d/3d
	 * @param sigma - the sigma per dimension
	 * @param oobs - the OutOfBounds strategy
	 * @param oobsValue - the value for out of image pixels if the OutOfBoundsStrategy is OutOfBounds.VALUE
	 * @return success? true or false
	 */
	public boolean gauss( final float[] img, final int[] dim, final double[] sigma, final OutOfBounds oobs, final float oobsValue )
	{
		return gauss( img, dim, sigma, oobs, oobsValue, cuda, cudaDeviceId );
	}

	/**
	 * Compute Gaussian convolution
	 * 
	 * @param img - the image (1d/2d/3d) as flat float array
	 * @param dim - the dimensions of the image in 1d/2d/3d
	 * @param sigma - the sigma for all dimensions
	 * @param oobs - the OutOfBounds strategy
	 * @param oobsValue - the value for out of image pixels if the OutOfBoundsStrategy is OutOfBounds.VALUE
	 * @param cuda - The {@link CUDASeparableConvolution} interface that loaded the external native library
	 * @param cudaDeviceId - which CUDA device to use, -1 means single-threaded computation on CPU using native code
	 * @return success? true or false
	 */
	final public static boolean gauss( final float[] img, final int[] dim, final double sigma, final OutOfBounds oobs, final float oobsValue, final CUDASeparableConvolution cuda, final int cudaDeviceId )
	{
		if ( dim == null || dim.length == 0 || dim.length > 3 )
			return false;

		final double[] sigmas = new double[ dim.length ];

		for ( int d = 0; d < dim.length; ++d )
			sigmas[ d ] = sigma;

		return gauss( img, dim, sigmas, oobs, oobsValue, cuda, cudaDeviceId );
	}

	/**
	 * Compute Gaussian convolution
	 * 
	 * @param img - the image (1d/2d/3d) as flat float array
	 * @param dim - the dimensions of the image in 1d/2d/3d
	 * @param sigma - the sigma per dimension
	 * @param oobs - the OutOfBounds strategy
	 * @param oobsValue - the value for out of image pixels if the OutOfBoundsStrategy is OutOfBounds.VALUE
	 * @param cuda - The {@link CUDASeparableConvolution} interface that loaded the external native library
	 * @param cudaDeviceId - which CUDA device to use, -1 means single-threaded computation on CPU using native code
	 * @return success? true or false
	 */
	final public static boolean gauss( final float[] img, final int[] dim, final double[] sigma, final OutOfBounds oobs, final float oobsValue, final CUDASeparableConvolution cuda, final int cudaDeviceId )
	{
		if ( dim == null || img == null || sigma == null )
		{
			System.out.println( "Input(s) are null." );
			return false;
		}

		final int n = dim.length;

		if ( sigma.length != n || n > 3 || n == 0 )
		{
			System.out.println( "Inputs inconsistent or wrong dimensionality." );
			return false;
		}

		final float[][] kernelsCUDA = getCUDAKernels( sigma, supportedKernelSizes );
		final int size = kernelsCUDA[ 0 ].length;

		// query the common parameters
		final int w,h,d;
		final float[] kernelX, kernelY, kernelZ;

		if ( n == 1 )
		{
			w = dim[ 0 ];
			h = d = 1;
			kernelX = kernelsCUDA[ 0 ];
			kernelY = kernelZ = null;
		}
		else if ( n == 2 )
		{
			w = dim[ 0 ];
			h = dim[ 1 ];
			d = 1;
			kernelX = kernelsCUDA[ 0 ];
			kernelY = kernelsCUDA[ 1 ];
			kernelZ = null;
		}
		else
		{
			w = dim[ 0 ];
			h = dim[ 1 ];
			d = dim[ 2 ];
			kernelX = kernelsCUDA[ 0 ];
			kernelY = kernelsCUDA[ 1 ];
			kernelZ = kernelsCUDA[ 2 ];
		}
		
		switch ( size )
		{
			case 7:
				cuda.convolve_7( img, kernelX, kernelY, kernelZ, w, h, d, kernelX != null, kernelY != null, kernelZ != null, oobs.ordinal(), oobsValue, cudaDeviceId );
				break;
			case 15:
				cuda.convolve_15( img, kernelX, kernelY, kernelZ, w, h, d, kernelX != null, kernelY != null, kernelZ != null, oobs.ordinal(), oobsValue, cudaDeviceId );
				break;
			case 31:
				cuda.convolve_31( img, kernelX, kernelY, kernelZ, w, h, d, kernelX != null, kernelY != null, kernelZ != null, oobs.ordinal(), oobsValue, cudaDeviceId );
				break;
			case 63:
				cuda.convolve_63( img, kernelX, kernelY, kernelZ, w, h, d, kernelX != null, kernelY != null, kernelZ != null, oobs.ordinal(), oobsValue, cudaDeviceId );
				break;
			case 127:
				cuda.convolve_127( img, kernelX, kernelY, kernelZ, w, h, d, kernelX != null, kernelY != null, kernelZ != null, oobs.ordinal(), oobsValue, cudaDeviceId );
				break;
			default:
				return false;
		}
		return true;
	}

	public static float[][] getCUDAKernels( final double[] sigma, final int[] supportedKernelSizes )
	{
		final int n = sigma.length;
		
		final double[][] kernels = new double[ n ][];
		int maxLength = -1;
		
		for ( int d = 0; d < n; ++d )
		{
			kernels[ d ] = Util.createGaussianKernel1DDouble( sigma[ d ], true );
			maxLength = Math.max( maxLength, kernels[ d ].length );
		}

		int size = Integer.MAX_VALUE;

		for ( final int s : supportedKernelSizes )
			if ( maxLength <= s )
				size = Math.min( s,  size );

		if ( size == Integer.MAX_VALUE )
		{
			System.out.println( "Kernel bigger than maximally supported size. Quitting." );
			return null;
		}

		final float[][] kernelsCUDA = new float[ kernels.length ][];

		for ( int d = 0; d < kernels.length; ++d )
			kernelsCUDA[ d ] = getFloatKernelPadded( kernels[ d ], size );
		
		return kernelsCUDA;
	}
	
	public static float[] getFloatKernelPadded( final double[] kernel, final int size )
	{
		if ( kernel.length > size )
			return null;

		final float[] k = new float[ size ];

		final int s = ( size - kernel.length )/2;

		for ( int i = 0; i < kernel.length; ++i )
			k[ s + i ] = (float)kernel[ i ];

		return k;
	}

}
