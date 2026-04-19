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
package net.preibisch.mvrecon.process.boundingbox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.Interval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.ImagePortion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;

public class BoundingBoxMinFilterThreshold implements BoundingBoxEstimation
{
	final SpimData2 spimData;
	final ExecutorService service;
	final Collection< ViewId > views;
	final ImgFactory< FloatType > imgFactory;

	final double background;
	final int radiusMin;
	final boolean displaySegmentationImage;
	final int downsampling;

	double extraSpaceFactor = 3;
	float[] minmax;

	public BoundingBoxMinFilterThreshold(
			final SpimData2 spimData,
			final ExecutorService service,
			final Collection< ? extends ViewId > views,
			final ImgFactory< FloatType > imgFactory,
			final double background,
			final int discardedObjectSize,
			final boolean displaySegmentationImage,
			final int downsampling )
	{
		this.spimData = spimData;
		this.service = service;
		this.views = new ArrayList<>();
		this.imgFactory = imgFactory;

		this.background = background;
		this.radiusMin = discardedObjectSize / 2;
		this.displaySegmentationImage = displaySegmentationImage;
		this.downsampling = downsampling;

		this.views.addAll( views );
		SpimData2.filterMissingViews( spimData, this.views );
	}

	@Override
	public BoundingBox estimate( final String title )
	{
		// defines the range for the BDV bounding box
		final BoundingBox maxBB = new BoundingBoxMaximal( views, spimData ).estimate( "Maximum bounding box used for initalization" );
		IOFunctions.println( maxBB );

		// adjust bounding box
		final Interval maxBBDS = FusionTools.createDownsampledBoundingBox( maxBB, downsampling ).getA();

		// adjust registrations
		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						views,
						spimData.getViewRegistrations().getViewRegistrations(),
						Double.NaN,
						downsampling );

		// fuse the dataset
		Img< FloatType > img =
				FusionTools.copyImgNoTranslation(
						FusionTools.fuseVirtual(
								spimData.getSequenceDescription().getImgLoader(),
								registrations,
								spimData.getSequenceDescription().getViewDescriptions(),
								views, FusionType.AVG_BLEND, 1, maxBBDS, null ),
						new ArrayImgFactory<>( new FloatType() ),
						new FloatType(),
						service );

		final float[] minmax = FusionTools.minMax( img );
		final int effR = Math.max( radiusMin / downsampling, 1 );
		final double threshold = (minmax[ 1 ] - minmax[ 0 ]) * ( background / 100.0 ) + minmax[ 0 ];

		IOFunctions.println( "Fused image minimum: " + minmax[ 0 ] );
		IOFunctions.println( "Fused image maximum: " + minmax[ 1 ] );
		IOFunctions.println( "Threshold: " + threshold );

		if ( displaySegmentationImage )
			FusionTools.getImagePlusInstance( img, false, "Fused input", minmax[ 0 ], minmax[ 1 ], DisplayImage.service ).show();

		IOFunctions.println( "Computing minimum filter with effective radius of " + effR + " (downsampling=" + downsampling + ")" );

		img = computeLazyMinFilter( img, effR );

		if ( displaySegmentationImage )
		{
			final ImagePlus imp = FusionTools.getImagePlusInstance( img, false, "Segmentation image", minmax[ 0 ], minmax[ 1 ], DisplayImage.service );
			imp.show();
		}

		final int[] min = new int[ img.numDimensions() ];
		final int[] max = new int[ img.numDimensions() ];

		if ( !computeBoundingBox( img, threshold, min, max ) )
			return null;

		IOFunctions.println( "Bounding box dim scaled: [" + Util.printCoordinates( min ) + "] >> [" + Util.printCoordinates( max ) + "]" );

		// adjust bounding box for downsampling and global coordinates
		for ( int d = 0; d < img.numDimensions(); ++d )
		{
			// downsampling
			min[ d ] *= downsampling;
			max[ d ] *= downsampling;
			
			// global coordinates
			min[ d ] += maxBB.getMin()[ d ];
			max[ d ] += maxBB.getMin()[ d ];
			
			// effect of the min filter + extra space
			min[ d ] -= radiusMin * extraSpaceFactor;
			max[ d ] += radiusMin * extraSpaceFactor;
		}

		IOFunctions.println( "Bounding box dim global: [" + Util.printCoordinates( min ) + "] >> [" + Util.printCoordinates( max ) + "]" );

		// maybe reuse it
		this.minmax = minmax.clone();

		return new BoundingBox( title, min, max );
	}

	public double getExtraSpaceFactor() { return extraSpaceFactor; }
	public void setExtraSpaceFactor( final double esf ) { this.extraSpaceFactor = esf; }

	public float getMinIntensity()
	{
		if ( minmax != null && minmax.length == 2 )
			return minmax[ 0 ];
		else
			return Float.NaN;
	}

	public float getMaxIntensity()
	{
		if ( minmax != null && minmax.length == 2 )
			return minmax[ 1 ];
		else
			return Float.NaN;
	}

	final public static < T extends RealType< T > > boolean computeBoundingBox( final Img< T > img, final double threshold, final int[] min, final int[] max )
	{
		final int n = img.numDimensions();
		
		for ( int d = 0; d < n; ++d )
		{
			min[ d ] = (int)img.dimension( d );
			max[ d ] = 0;
		}

		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( img.size() );

		// set up executor service
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );
		
		final ArrayList< Callable< int[][] > > tasks = new ArrayList< Callable< int[][] > >();
		
		for ( final ImagePortion portion : portions )
		{
			tasks.add( new Callable< int[][] >() 
					{
						@Override
						public int[][] call() throws Exception
						{
							final int[] min = new int[ n ];
							final int[] max = new int[ n ];
							
							for ( int d = 0; d < n; ++d )
							{
								min[ d ] = (int)img.dimension( d );
								max[ d ] = 0;
							}
							
							final Cursor< T > c = img.localizingCursor();
							c.jumpFwd( portion.getStartPosition() );
							
							for ( long j = 0; j < portion.getLoopSize(); ++j )
							{
								final double v = c.next().getRealDouble();
								
								if ( v > threshold )
								{
									for ( int d = 0; d < n; ++d )
									{
										final int l = c.getIntPosition( d ); 
										min[ d ] = Math.min( min[ d ], l );
										max[ d ] = Math.max( max[ d ], l );
									}
								}
							}
							
							return new int[][]{ min, max };
						}
					});
			
			try
			{
				// invokeAll() returns when all tasks are complete
				final List< Future< int[][] > > futureList = taskExecutor.invokeAll( tasks );
				
				for ( final Future< int[][] > future : futureList )
				{
					final int[][] minmaxThread = future.get();
					
					for ( int d = 0; d < n; ++d )
					{
						min[ d ] = Math.min( min[ d ], minmaxThread[ 0 ][ d ] );
						max[ d ] = Math.max( max[ d ], minmaxThread[ 1 ][ d ] );
					}
				}
			}
			catch ( final Exception e )
			{
				IOFunctions.println( "Failed to compute bounding box by thresholding: " + e );
				e.printStackTrace();
				return false;
			}
		}
		
		taskExecutor.shutdown();
		
		return true;
	}
	
	/**
	 * By lazy I mean I was lazy to use a second image, one could of course implement it
	 * on a n-d line by line basis @TODO
	 * 
	 * @param tmp1 - input image (overwritten, not necessarily the result, depends if number of dimensions is even or odd)
	 * @param radius - the integer radius of the min filter
	 * @param <T> pixel type
	 * @return min filtered image
	 */
	final public static < T extends RealType< T > > Img< T > computeLazyMinFilter( final Img< T > tmp1, final int radius )
	{
		final int n = tmp1.numDimensions();
		final int filterExtent = radius*2 + 1;
		final Img< T > tmp2 = tmp1.factory().create( tmp1, tmp1.firstElement() );
		
		// split up into many parts for multithreading
		final Vector< ImagePortion > portions = FusionTools.divideIntoPortions( tmp1.size() );

		// set up executor service
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );

		for ( int dim = 0; dim < n; ++dim )
		{
			final int d = dim;
			
			final RandomAccessible< T > input;
			final Img< T > output;
			
			if ( d % 2 == 0 )
			{
				input = Views.extendZero( tmp1 );
				output = tmp2;
			}
			else
			{
				input = Views.extendZero( tmp2 );
				output = tmp1;
			}
			
			final ArrayList< Callable< String > > tasks = new ArrayList< Callable< String > >();
	
			for ( final ImagePortion portion : portions )
			{
				tasks.add( new Callable< String >() 
						{
							@Override
							public String call() throws Exception
							{
								final RandomAccess< T > r = input.randomAccess();
								final int[] tmp = new int[ n ];

								final Cursor< T > c = output.localizingCursor();
								c.jumpFwd( portion.getStartPosition() );
								
								for ( long j = 0; j < portion.getLoopSize(); ++j )
								{
									final T t = c.next();
									c.localize( tmp );
									tmp[ d ] -= radius;
									r.setPosition( tmp );
									
									float min = Float.MAX_VALUE;
									
									for ( int i = 0; i < filterExtent; ++i )
									{
										min = Math.min( min, r.get().getRealFloat() );
										r.fwd( d );
									}
									
									t.setReal( min );
								}
								return "";
							}
						});
			}
			
			try
			{
				// invokeAll() returns when all tasks are complete
				taskExecutor.invokeAll( tasks );
			}
			catch ( final InterruptedException e )
			{
				IOFunctions.println( "Failed to compute lazy min filter: " + e );
				e.printStackTrace();
				return null;
			}
		}
		
		taskExecutor.shutdown();

		if ( n % 2 == 0 )
			return tmp1;
		else
			return tmp2;
	}

	public static void main( final String[] args )
	{
		new ImageJ();
		
		ImagePlus imp = new ImagePlus( "/Users/preibischs/workspace/TestLucyRichardson/src/resources/dros-1.tif" );
		
		Img< FloatType > img = ImageJFunctions.convertFloat( imp );

		ImageJFunctions.show( img.copy() );
		ImageJFunctions.show( computeLazyMinFilter( img, 5 ) );
	}
}
