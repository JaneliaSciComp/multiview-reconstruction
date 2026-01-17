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
package net.preibisch.mvrecon.process.interestpointdetection.methods.dog;


import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import bdv.util.ConstantRandomAccessible;
import ij.ImageJ;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.algorithm.neighborhood.Neighborhood;
import net.imglib2.algorithm.neighborhood.RectangleShape;
import net.imglib2.converter.BiConverter;
import net.imglib2.converter.Converters;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.FloatArray;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.legacy.registration.bead.laplace.LaPlaceFunctions;
import net.preibisch.legacy.segmentation.SimplePeak;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.cuda.Block;
import net.preibisch.mvrecon.process.cuda.BlockGenerator;
import net.preibisch.mvrecon.process.cuda.BlockGeneratorVariableSizePrecise;
import net.preibisch.mvrecon.process.cuda.BlockGeneratorVariableSizeSimple;
import net.preibisch.mvrecon.process.cuda.CUDADevice;
import net.preibisch.mvrecon.process.cuda.CUDASeparableConvolution;
import net.preibisch.mvrecon.process.cuda.CUDASeparableConvolutionFunctions;
import net.preibisch.mvrecon.process.cuda.CUDASeparableConvolutionFunctions.OutOfBounds;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;
import net.preibisch.mvrecon.process.interestpointdetection.Localization;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyGauss;
import net.preibisch.mvrecon.process.interestpointdetection.methods.lazygauss.LazyWeightedGauss;
import util.ImgLib2Tools;

public class DoGImgLib2
{
	public static boolean silent = false;
	public static int[] blockSize = new int[] {96, 96, 64};
	public static enum SpecialPoint { INVALID, MIN, MAX };

	public static void main ( String[] args )
	{
		new ImageJ();

		final RandomAccessibleInterval< FloatType > input =
				IOFunctions.openAs32BitArrayImg( new File( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/spim_TL18_Angle0.tif"));

		final RandomAccessibleInterval< FloatType > inputCropped = Views.interval( input, Intervals.expand(input, new long[] {-200, -200, -20}) );

		ImageJFunctions.show( inputCropped );

		final RandomAccessibleInterval< FloatType > mask =
				Views.interval(new ConstantRandomAccessible< FloatType >( new FloatType( 1 ), input.numDimensions() ), input );

		//computeDoG(input, mask, 1.8015, 0.007973356, 1/*localization*/, false /*findMin*/, true /*findMax*/, Double.NaN, Double.NaN, DeconViews.createExecutorService(), Threads.numThreads() );

		//final RandomAccessibleInterval< FloatType > input2d = Views.hyperSlice(input, 2, 50 );

		long time = System.currentTimeMillis();

		// 1388x1040x81 = 116925120
		final ArrayList<InterestPoint> points = 
				computeDoG( Views.extendMirrorDouble( inputCropped ), Views.extendZero( mask ), new FinalInterval( inputCropped ), 2.000, 0.03, 1/*localization*/, false /*findMin*/, true /*findMax*/, 0, 255, Executors.newFixedThreadPool( 8 ) );

		System.out.println( System.currentTimeMillis() - time );

		ImageJFunctions.show( input ).setRoi( mpicbg.ij.util.Util.pointsToPointRoi(points) );
	}

	public static < T extends RealType< T > > int radiusDoG( final double sigma )
	{
		final float k = LaPlaceFunctions.computeK( 4 );
		final int steps = 3;

		//
		// Compute the Sigmas for the gaussian convolution
		//
		final float imageSigma = 0.5f;

		final float[] sigmaStepsX = LaPlaceFunctions.computeSigma( steps, k, (float)sigma );
		final float[] sigmaStepsDiffX = LaPlaceFunctions.computeSigmaDiff( sigmaStepsX, imageSigma );

		final double sigma1 = sigmaStepsDiffX[0];
		final double sigma2 = sigmaStepsDiffX[1];

		final double[][] halfkernels = Gauss3.halfkernels( new double[] { Math.max( sigma1, sigma2 ) });

		return halfkernels[ 0 ].length - 1;
	}

	public static < T extends RealType< T > > ArrayList< InterestPoint > computeDoG(
			final RandomAccessible< T > input,
			final RandomAccessible< T > mask,
			final Interval interval,
			final double sigma,
			final double threshold,
			final int localization,
			final boolean findMin,
			final boolean findMax,
			final double minIntensity,
			final double maxIntensity,
			final ExecutorService service )
	{
		return computeDoG(input, mask, interval, sigma, threshold, localization, findMin, findMax, minIntensity, maxIntensity, blockSize, service, null, null, false, 0.0 );
	}

	public static < T extends RealType< T > > ArrayList< InterestPoint > computeDoG(
			final RandomAccessible< T > input,
			final RandomAccessible< T > mask,
			final Interval interval,
			final double sigma,
			final double threshold,
			final int localization,
			final boolean findMin,
			final boolean findMax,
			final double minIntensity,
			final double maxIntensity,
			final int[] blockSize,
			final ExecutorService service,
			final CUDASeparableConvolution cuda,
			final CUDADevice cudaDevice,
			final boolean accurateCUDA,
			final double percentGPUMem )
	{
		float initialSigma = (float)sigma;
		
		final float minPeakValue = (float)threshold;
		final float minInitialPeakValue;
		
		if ( localization == 0 )
			minInitialPeakValue = minPeakValue;
		else
			minInitialPeakValue = (float)threshold/3.0f;

		final float min, max;
		final boolean autoDetected;

		if ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) || Double.isInfinite( minIntensity ) || Double.isInfinite( maxIntensity ) || minIntensity == maxIntensity )
		{
			final float[] minmax;

			if ( mask == null )
				minmax = FusionTools.minMax( Views.interval( input, interval ), service );
			else
				minmax = minMax( Views.interval( input, interval ), Views.interval( mask, interval ), service );

			min = minmax[ 0 ];
			max = minmax[ 1 ];
			autoDetected = true;
		}
		else
		{
			min = (float)minIntensity;
			max = (float)maxIntensity;
			autoDetected = false;
		}

		if ( !silent )
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): min intensity = " + min + ", max intensity = " + max + (autoDetected ? " [auto-detected]" : " [provided]") );

		// normalize image
		final RandomAccessible< FloatType > inputFloat = ImgLib2Tools.normalizeVirtual( input, min, max );

		// compute sigmas
		final Pair< double[][], Float > sigmas = computeSigmas( initialSigma, inputFloat.numDimensions() );
		final double[] sigma1 = sigmas.getA()[ 0 ];
		final double[] sigma2 = sigmas.getA()[ 1 ];
		final float K_MIN1_INV = sigmas.getB();

		if ( !silent )
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): computing DoG with (sigma=" + initialSigma + ", " +
				"threshold=" + minPeakValue + ", sigma1=" + Util.printCoordinates( sigma1 ) + ", sigma2=" + Util.printCoordinates( sigma2 ) + ")" );

		final long[] minInterval = new long[ inputFloat.numDimensions() ];
		interval.min( minInterval );

		final RandomAccessibleInterval< FloatType > gauss1, gauss2;
		final RandomAccessible< FloatType > maskFloat;

		if ( mask == null )
		{
			maskFloat = null;

			if ( cuda == null )
			{
				gauss1 = LazyGauss.init( inputFloat, interval, new FloatType(), sigma1, blockSize );
				gauss2 = LazyGauss.init( inputFloat, interval, new FloatType(), sigma2, blockSize );
			}
			else
			{
				// TODO: untested
				gauss1 = computeGaussCUDA( Views.interval( inputFloat, interval ), sigma1, cuda, cudaDevice, accurateCUDA, percentGPUMem );
				gauss2 = computeGaussCUDA( Views.interval( inputFloat, interval ), sigma2, cuda, cudaDevice, accurateCUDA, percentGPUMem );
			}
		}
		else
		{
			maskFloat = Converters.convert( mask, (i,o) -> o.set( i.getRealFloat() ), new FloatType());//ImgLib2Tools.convertVirtual( mask );

			gauss1 = LazyWeightedGauss.init( inputFloat, maskFloat, interval, new FloatType(), sigma1, blockSize ); // mask zero oobs
			gauss2 = LazyWeightedGauss.init( inputFloat, maskFloat, interval, new FloatType(), sigma2, blockSize );
		}

		final RandomAccessibleInterval< FloatType > dog = Converters.convert(gauss2, gauss1, (iA,iB,o) -> o.setReal( ( iA.getRealDouble() - iB.getRealDouble() ) * K_MIN1_INV ), new FloatType() );

		// no caching since it is a simple subtraction operation, the underlying Gauss is expensive
		final RandomAccessibleInterval< FloatType > dogCached = dog;

		if ( !silent )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Detecting peaks." );

		final ArrayList< SimplePeak > peaks = findPeaks( dogCached, maskFloat == null ? null : Views.interval( maskFloat, interval ), minInitialPeakValue, service );

		if ( !silent )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Found " + peaks.size() + " initial peaks (before refinement)." );

		final ArrayList< InterestPoint > finalPeaks;

		if ( localization == 0 )
		{
			finalPeaks = Localization.noLocalization( peaks, findMin, findMax, true );
		}
		else if ( localization == 1 )
		{
			for ( final SimplePeak peak : peaks )
				for ( int d = 0; d < peak.location.length; ++d )
					peak.location[ d ] -= minInterval[ d ];

			if ( !silent )
				IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Quadratic localization." );

			// TODO: something requires a zeroMin dataset in here
			finalPeaks = Localization.computeQuadraticLocalization( peaks, Views.extendMirrorDouble( Views.zeroMin( dogCached ) ), new FinalInterval( Views.zeroMin( dogCached ) ), findMin, findMax, minPeakValue, true, service );

			// adjust detections for min coordinates of the RandomAccessibleInterval
			for ( final InterestPoint ip : finalPeaks )
			{
				for ( int d = 0; d < input.numDimensions(); ++d )
				{
					ip.getL()[ d ] += minInterval[ d ];
					ip.getW()[ d ] += minInterval[ d ];
				}
			}
		}
		else
		{
			finalPeaks = Localization.computeGaussLocalization( peaks, null, sigma, findMin, findMax, minPeakValue, true );
		}

		// remove potential duplicates (happens because during localization it can move around)
		//final HashSet< >
		final ArrayList< InterestPoint > filteredFinalPeaks = new ArrayList<>();

		for ( int i = 0; i < finalPeaks.size() - 1; ++i )
		{
			final double[] v1 = finalPeaks.get( i ).getL();
			boolean approxEqual = true;

			for ( int j = i + 1; approxEqual && j < finalPeaks.size(); ++j )
			{
				final double[] v2 = finalPeaks.get( j ).getL();
				approxEqual = isApproxEqual( v1, v2, 0.001 );
			}

			if ( approxEqual )
				filteredFinalPeaks.add( finalPeaks.get( i ) );
		}

		if ( !silent )
			IOFunctions.println("(" + new Date(System.currentTimeMillis()) + "): Found " + filteredFinalPeaks.size() + " final peaks." );

		return filteredFinalPeaks;
	}

	private static final boolean isApproxEqual( final double[] v1, final double[] v2, final double epsilon )
	{
		for ( int d = 0; d < v1.length; ++d )
			if ( Math.abs( v1[ d ] - v2[ d ] ) > epsilon )
				return true;

		return false;
	}

	public static Pair<double[][], Float > computeSigmas( final float initialSigma, final int n )
	{
		final float k = LaPlaceFunctions.computeK( 4 );
		final float K_MIN1_INV = LaPlaceFunctions.computeKWeight(k);
		final int steps = 3;
		
		//
		// Compute the Sigmas for the gaussian convolution
		//
		final double[][] sigma = new double[2][ n ];
		
		for ( int d = 0; d < n; ++d )
		{
			final float[] sigmaStepsX = LaPlaceFunctions.computeSigma( steps, k, initialSigma );
			final float[] sigmaStepsDiffX = LaPlaceFunctions.computeSigmaDiff( sigmaStepsX, 0.5f );
			
			sigma[0][ d ] = sigmaStepsDiffX[0];
			sigma[1][ d ] = sigmaStepsDiffX[1];
		}

		return new ValuePair<>( sigma, K_MIN1_INV );
	}
	public static ArrayList<SimplePeak> findPeaks( final RandomAccessibleInterval< FloatType > laPlace, final RandomAccessibleInterval< FloatType > laPlaceMask, final float minValue, final ExecutorService service )
	{
		final Interval interval = Intervals.expand( laPlace, -1 );

		// create a view on the source with this interval
		final RandomAccessibleInterval< FloatType > source = Views.interval( laPlace, interval );
		final RandomAccessibleInterval< FloatType > mask;

		if ( laPlaceMask == null )
			mask = null;
		else
			mask = Views.interval( laPlaceMask, interval );

		long numPixels = 1;

		for ( int d = 0; d < source.numDimensions(); ++d )
			numPixels *= source.dimension( d );

		final int numDimensions = source.numDimensions();
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( numPixels );
		final ArrayList< Callable< ArrayList< SimplePeak > > > tasks = new ArrayList<>();

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< ArrayList< SimplePeak > >()
			{
				@Override
				public ArrayList< SimplePeak > call() throws Exception
				{
	            	final ArrayList<SimplePeak> myPeaks = new ArrayList<>();

					final Cursor< FloatType > center = Views.flatIterable( source ).localizingCursor();
					final Cursor< FloatType > centerMask;
					
					if ( mask == null )
						centerMask = null;
					else
						centerMask = Views.flatIterable( mask ).localizingCursor();

					 // instantiate a RectangleShape to access rectangular local neighborhoods
			        // of radius 1 (that is 3x3x...x3 neighborhoods), skipping the center pixel
			        // (this corresponds to an 8-neighborhood in 2d or 26-neighborhood in 3d, ...)
			        final RectangleShape shape = new RectangleShape( 1, true );

			        final Cursor< Neighborhood< FloatType >> neighborhoodCursor = shape.neighborhoods( source ).cursor();

			        final Cursor< Neighborhood< FloatType >> neighborhoodCursorMask;
			        if ( mask == null )
			        	neighborhoodCursorMask = null;
			        else
			        	neighborhoodCursorMask = shape.neighborhoods( mask ).cursor();

					center.jumpFwd( portion.getStartPosition() );
					neighborhoodCursor.jumpFwd( portion.getStartPosition() );
					if ( centerMask != null )
					{
						centerMask.jumpFwd( portion.getStartPosition() );
						neighborhoodCursorMask.jumpFwd( portion.getStartPosition() );
					}

	            	final int[] position = new int[ numDimensions ];

			        for ( int l = 0; l < portion.getLoopSize(); ++l )
	                {
			        	final FloatType centerValue = center.next();
			        	final Neighborhood< FloatType > neighborhood = neighborhoodCursor.next();
			        	
			        	// it can never be a desired peak if its outside the mask (or any pixel of the 3x3..3 neighborhood
                		if ( centerMask != null )
                		{
			        		centerMask.fwd();
			        		final Neighborhood< FloatType > neighborhoodMask = neighborhoodCursorMask.next();

			        		if ( centerMask.get().get() <= 0.0 )
                				continue;
                			
                			boolean outside = false;
                			for ( final FloatType value : neighborhoodMask )
                				if ( value.get() <= 0 )
                				{
                					outside = true;
                					break;
                				}

                			if ( outside )
                				continue;
                		}

			        	// it can never be a desired peak as it is too low
                		if ( Math.abs( centerValue.get() ) < minValue )
            				continue;

            			// we have to compare for example 26 neighbors in the 3d case (3^3 - 1) relative to the current position
            			final SpecialPoint specialPoint = isSpecialPoint( neighborhood.cursor(), centerValue.get() ); 

			        	center.localize( position );

            			if ( specialPoint == SpecialPoint.MIN )
            				myPeaks.add( new SimplePeak( position, Math.abs( centerValue.get() ), true, false ) ); //( position, currentValue, specialPoint ) );
            			else if ( specialPoint == SpecialPoint.MAX )
            				myPeaks.add( new SimplePeak( position, Math.abs( centerValue.get() ), false, true ) ); //( position, currentValue, specialPoint ) );
	                }

            		return myPeaks;
	    		}
			} );
		}

		// put together the list from the various threads	
		final ArrayList<SimplePeak> dogPeaks = new ArrayList<SimplePeak>();

		//final ExecutorService taskExecutor = DeconViews.createExecutorService();

		try
		{
			for ( final Future< ArrayList< SimplePeak > > future : service.invokeAll( tasks ) )
				dogPeaks.addAll( future.get() );
		}
		catch ( InterruptedException | ExecutionException e )
		{
			e.printStackTrace();
		}

		//taskExecutor.shutdown();

		return dogPeaks;
	}

	final protected static SpecialPoint isSpecialPoint( final Cursor< FloatType > neighborhoodCursor, final float centerValue )
	{
		boolean isMin = true;
		boolean isMax = true;
				
		while ( (isMax || isMin) && neighborhoodCursor.hasNext() )
		{			
			neighborhoodCursor.fwd();
			
			final double value = neighborhoodCursor.get().getRealDouble(); 
			
			// it can still be a minima if the current value is bigger/equal to the center value
			isMin &= (value >= centerValue);
			
			// it can still be a maxima if the current value is smaller/equal to the center value
			isMax &= (value <= centerValue);
		}		
		
		// this mixup is intended, a minimum in the 2nd derivation is a maxima in image space and vice versa
		if ( isMin )
			return SpecialPoint.MAX;
		else if ( isMax )
			return SpecialPoint.MIN;
		else
			return SpecialPoint.INVALID;
	}

	public static final <T extends Type<T>> void copy(
			final RandomAccessible<? extends T> source,
			final RandomAccessibleInterval<T> target) {

		Views.flatIterable(Views.interval(Views.pair(source, target), target)).forEach(
				pair -> pair.getB().set(pair.getA()));
	}

	public static < T extends RealType< T > > float[] minMax( final RandomAccessibleInterval< T > img, final RandomAccessibleInterval< T > mask, final ExecutorService service )
	{
		final IterableInterval< T > iterable = Views.iterable( img );

		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( iterable.size() );

		// set up executor service
		//final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );
		final ArrayList< Callable< float[] > > tasks = new ArrayList< Callable< float[] > >();

		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< float[] >() 
					{
						@Override
						public float[] call() throws Exception
						{
							float min = Float.MAX_VALUE;
							float max = -Float.MAX_VALUE;
							
							final Cursor< T > c = iterable.cursor();
							c.jumpFwd( portion.getStartPosition() );
							final RandomAccess< T > r = mask.randomAccess();

							for ( long j = 0; j < portion.getLoopSize(); ++j )
							{
								final float v = c.next().getRealFloat();
								r.setPosition( c );
								
								if ( r.get().getRealDouble() > 0 )
								{
									min = Math.min( min, v );
									max = Math.max( max, v );
								}
							}
							
							// min & max of this portion
							return new float[]{ min, max };
						}
					});
		}
		
		// run threads and combine results
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;
		
		try
		{
			// invokeAll() returns when all tasks are complete
			final List< Future< float[] > > futures = service.invokeAll( tasks );
			
			for ( final Future< float[] > future : futures )
			{
				final float[] minmax = future.get();
				min = Math.min( min, minmax[ 0 ] );
				max = Math.max( max, minmax[ 1 ] );
			}
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute masked min/max: " + e );
			e.printStackTrace();
			return null;
		}

		//taskExecutor.shutdown();
		
		return new float[]{ min, max };
	}

	public static RandomAccessibleInterval< FloatType > computeGaussCUDA(
			final RandomAccessibleInterval< FloatType > inputFloatNonZeroMin,
			final double[] sigma,
			final CUDASeparableConvolution cuda,
			final CUDADevice cudaDevice,
			final boolean accurateCUDA,
			final double percentGPUMem )
	{
		final long[] offset = inputFloatNonZeroMin.minAsLongArray();
		final RandomAccessibleInterval< FloatType > inputFloat = Views.zeroMin( inputFloatNonZeroMin );
		final Img<FloatType> result;
		final CUDASeparableConvolutionFunctions cudaconvolve =  new CUDASeparableConvolutionFunctions( cuda, cudaDevice.getDeviceId() );

		// do not operate at the edge, 80% of the memory is a good idea I think
		final long memAvail = Math.round( cudaDevice.getFreeDeviceMemory() * ( percentGPUMem / 100.0 ) );
		final long imgBytes = numPixels( inputFloat, accurateCUDA, sigma ) * 4 * 2; // float, two images on the card at once

		final long[] numBlocksDim = net.imglib2.util.Util.int2long( computeNumBlocksDim( memAvail, imgBytes, percentGPUMem, inputFloat.numDimensions(), "CUDA-Device " + cudaDevice.getDeviceId() ) );
		final BlockGenerator< Block > generator;

		if ( accurateCUDA )
			generator = new BlockGeneratorVariableSizePrecise( numBlocksDim );
		else
			generator = new BlockGeneratorVariableSizeSimple( numBlocksDim );

		final List< Block > blocks = generator.divideIntoBlocks( inputFloat.dimensionsAsLongArray(), getKernelSize( sigma ) );

		if ( !accurateCUDA && blocks.size() == 1 /*&& ArrayImg.class.isInstance( inputFloat )*/ )
		{
			result =  new ArrayImgFactory<FloatType>( new FloatType() ).create( inputFloat );

			IOFunctions.println( "Conovlving image as one single block." );
			long time = System.currentTimeMillis();

			// copy the only directly into the result
			blocks.get( 0 ).copyBlock( inputFloat, result );
			long copy = System.currentTimeMillis();
			IOFunctions.println( "Copying data took " + ( copy - time ) + "ms" );

			// convolve
			final float[] resultF = ((FloatArray)((ArrayImg< net.imglib2.type.numeric.real.FloatType, ? > )result).update( null ) ).getCurrentStorageArray();
			cudaconvolve.gauss( resultF, getImgSizeInt( result ), sigma, OutOfBounds.EXTEND_BORDER_PIXELS, 0 );
			IOFunctions.println( "Convolution took " + ( System.currentTimeMillis() - copy ) + "ms using device=" + cudaDevice.getDeviceName() + " (id=" + cudaDevice.getDeviceId() + ")" );

			// no copy back required
		}
		else
		{
			final RandomAccessible< FloatType > input;

			if ( accurateCUDA )
				input = Views.extendMirrorSingle( inputFloat );
			else
				input = inputFloat;

			result =  new CellImgFactory<FloatType>( new FloatType() ).create( inputFloat );

			for( final Block block : blocks )
			{
				//long time = System.currentTimeMillis();
				final ArrayImg< FloatType, FloatArray > imgBlock = ArrayImgs.floats( block.getBlockSize() );

				// copy the block
				block.copyBlock( input, imgBlock );
				//long copy = System.currentTimeMillis();
				//IOFunctions.println( "Copying block took " + ( copy - time ) + "ms" );

				// convolve
				final float[] imgBlockF = ((FloatArray)((ArrayImg< net.imglib2.type.numeric.real.FloatType, ? > )imgBlock).update( null ) ).getCurrentStorageArray();
				cudaconvolve.gauss( imgBlockF, getImgSizeInt( imgBlock ), sigma, OutOfBounds.EXTEND_BORDER_PIXELS, 0 );
				//long convolve = System.currentTimeMillis();
				//IOFunctions.println( "Convolution took " + ( convolve - copy ) + "ms using device=" + cudaDevice.getDeviceName() + " (id=" + cudaDevice.getDeviceId() + ")" );

				// no copy back required
				block.pasteBlock( result, imgBlock );
				//IOFunctions.println( "Pasting block took " + ( System.currentTimeMillis() - convolve ) + "ms" );
			}
		}

		if ( Views.isZeroMin( inputFloatNonZeroMin ) )
			return result;
		else
			return Views.translate( result, offset );
	}

	public static int[] getImgSizeInt( final Interval img )
	{
		final int[] dim = new int[ img.numDimensions() ];
		for ( int d = 0; d < img.numDimensions(); ++d )
			dim[ d ] = (int)img.dimension( d );
		return dim;
	}


	protected static long[] getKernelSize( final double[] sigma )
	{
		final long[] dim = new long[ sigma.length ];
		for ( int d = 0; d < sigma.length; ++d )
			dim[ d ] = Util.createGaussianKernel1DDouble( sigma[ d ], false ).length;
		return dim;
	}

	public static int[] computeNumBlocksDim( final long memAvail, final long memReq, final double percentGPUMem, final int n, final String start )
	{
		final int numBlocks = (int)( memReq / memAvail + Math.min( 1, memReq % memAvail ) );
		final double blocksPerDim = Math.pow( numBlocks, 1 / n );

		final int[] numBlocksDim = new int[ n ];

		for ( int d = 0; d < numBlocksDim.length; ++d )
			numBlocksDim[ d ] = (int)Math.round( Math.floor( blocksPerDim ) ) + 1;

		int numBlocksCurrent;
		
		do
		{
			numBlocksCurrent = numBlocks( numBlocksDim );

			for ( int d = 0; d < numBlocksDim.length; ++d )
			{
				++numBlocksDim[ d ];
				reduceBlockNumbers( numBlocksDim, numBlocks );
			}
			
			
		}
		while ( numBlocks( numBlocksDim ) < numBlocksCurrent );

		if ( start != null )
		{
			String out =
					start + ", mem=" + memAvail / (1024*1024) + 
					"MB (" + Math.round( percentGPUMem / 100 ) + "%), required mem=" + memReq / (1024*1024) + "MB, need to split up into " + numBlocks + " blocks: ";

			for ( int d = 0; d < numBlocksDim.length; ++d )
			{
				out += numBlocksDim[ d ];
				if ( d != numBlocksDim.length - 1 )
					out += "x";
			}

			IOFunctions.println( out );
		}
		return numBlocksDim;
	}

	protected static void reduceBlockNumbers( final int[] numBlocksDim, final int numBlocks )
	{
		boolean reduced;

		do
		{
			reduced = false;

			for ( int d = numBlocksDim.length - 1; d >= 0 ; --d )
			{
				if ( numBlocksDim[ d ] > 1 )
				{
					--numBlocksDim[ d ];

					if ( numBlocks( numBlocksDim ) < numBlocks )
						++numBlocksDim[ d ];
					else
						reduced = true;
				}
			}
		}
		while ( reduced );
	}

	protected static int numBlocks( final int[] numBlocksDim )
	{
		int numBlocks = 1;

		for ( int d = 0; d < numBlocksDim.length; ++d )
			numBlocks *= numBlocksDim[ d ];

		return numBlocks;
	}

	protected static long numPixels( final Dimensions dim, final boolean accurate, final double[] sigma )
	{
		if ( accurate )
		{
			long size = 1;

			for ( int d = 0; d < dim.numDimensions(); ++d )
				size *= dim.dimension( d ) + Util.createGaussianKernel1DDouble( sigma[ d ], false ).length - 1;

			return size;
		}
		else
		{
			long numPixels = dim.dimension( 0 );

			for ( int d = 1; d <= dim.numDimensions(); ++d )
				numPixels *= dim.dimension( d );

			return numPixels;
		}
	}
}
