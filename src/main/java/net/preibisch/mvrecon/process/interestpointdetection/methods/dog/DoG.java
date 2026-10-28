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
package net.preibisch.mvrecon.process.interestpointdetection.methods.dog;

import java.util.Date;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;

import ij.IJ;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.OverlappingRegions;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class DoG
{
	final DoGParameters dog;

	public DoG( final DoGParameters dog )
	{
		this.dog = dog;
	}

	public static HashMap< ViewId, List< InterestPoint >> findInterestPoints( final DoGParameters dog )
	{
		final HashMap< ViewId, List< InterestPoint >> interestPoints = new HashMap< ViewId, List< InterestPoint >>();

		addInterestPoints( interestPoints, dog );

		return interestPoints;
	}

	/*
	 * finds all interest points and returns them as InterestPoints (which is a mpicbg Point and implements RealLocalizable),
	 * by default returns world coordinates
	 *
	 * more parameters are omitted
	 *
	 * @param <T> - the type, RealType and NativeType
	 * @param input - any RandomAccessibleInterval (Img will be casted only otherwise copied), non-FloatType will be converted, everything is normalized to 0...1 for processing
	 * @param sigma - sigma for the DoG detection (try InteractiveDoG to figure out the right parameters)
	 * @param threshold - threshold for the DoG detection (try InteractiveDoG to figure out the right parameters)
	 * @param findMin - find intensity minima
	 * @param findMax - fina intensity maxima
	 * @param minIntensity - the min intensity for normalization to 0...1, if Double.NaN the value will be looked up
	 * @param maxIntensity - the max intensity for normalization to 0...1, if Double.NaN the value will be looked up
	 * @param service - the ExecutorService to use
	 *
	 * @return a list of interest points
	 */
	public static < T extends RealType< T > & NativeType< T > > List< InterestPoint > findInterestPoints(
			final RandomAccessibleInterval< T > input,
			final double sigma,
			final double threshold,
			final boolean findMin,
			final boolean findMax,
			final double minIntensity,
			final double maxIntensity,
			final ExecutorService service )
	{
		//
		// compute Difference-of-Gaussian (includes normalization)
		//
		List< InterestPoint > ips = DoGImgLib2.computeDoG(
				Views.extendMirrorSingle( Views.zeroMin( input ) ),
				null,
				new FinalInterval( Views.zeroMin( input ) ),
				sigma,
				threshold,
				1, // 0 = no subpixel localization, 1 = quadratic fit
				findMin,
				findMax,
				minIntensity,
				maxIntensity,
				service );

		// adjust detections for min coordinates of the RandomAccessibleInterval
		for ( final InterestPoint ip : ips )
		{
			for ( int d = 0; d < input.numDimensions(); ++d )
			{
				ip.getL()[ d ] += input.min( d );
				ip.getW()[ d ] += input.min( d );
			}
		}

		return ips;
	}

	/**
	 * The detection of a view restricted to the regions that overlap other views (InterestPointParameters.regions): one
	 * computeDoG call per region, expanded by one pixel and clipped to the image (findPeaks skips the outermost pixel of
	 * its interval), with the intensity range of the whole view when none is set (computeDoG would otherwise normalize
	 * every region on its own); the lists are mapped to full resolution and combined (OverlappingRegions.combine: the
	 * regions of different partners overlap each other, the same pixels give the same detection). A view without a
	 * region gets an empty list.
	 *
	 * @param vd - the view
	 * @param img - the opened (downsampled) image
	 * @param mipmapTransform - its transform to full resolution
	 * @param anisotropy - the anisotropy of the opened image (DownsampleTools.anisotropy), null = isotropic in pixels
	 * @param dog - the parameters (regions set)
	 * @param service - the executor
	 * @return the detections of the view at full resolution, ids 0..n-1
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	static List< InterestPoint > detectInRegions( final ViewDescription vd, final RandomAccessibleInterval img, final AffineTransform3D mipmapTransform, final double[] anisotropy, final DoGParameters dog, final ExecutorService service )
	{
		final FinalInterval image = new FinalInterval( img );
		final List< Interval > regions = dog.regions.intervals( vd, image, mipmapTransform );

		if ( regions.isEmpty() )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): " + Group.pvid( vd ) + " has no region overlapping another view, no interest points" );
			return new ArrayList<>();
		}

		double minIntensity = dog.minIntensity, maxIntensity = dog.maxIntensity;

		if ( Double.isNaN( minIntensity ) || Double.isNaN( maxIntensity ) || Double.isInfinite( minIntensity ) || Double.isInfinite( maxIntensity ) || minIntensity == maxIntensity )
		{
			final float[] minmax = FusionTools.minMax( img, service );
			minIntensity = minmax[ 0 ];
			maxIntensity = minmax[ 1 ];

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): intensity range of the view = [" + minIntensity + ", " + maxIntensity + "] (computed once for all its regions, not set by the user)" );
		}

		final RandomAccessible extended = Views.extendMirrorSingle( img );
		final List< List< InterestPoint > > perRegion = new ArrayList<>();

		for ( final Interval region : regions )
		{
			final Interval expanded = Intervals.intersect( Intervals.expand( region, 1 ), image );

			final List< InterestPoint > points = DoGImgLib2.computeDoG(
					extended,
					null, // mask
					expanded,
					dog.sigma,
					anisotropy,
					dog.threshold,
					dog.localization,
					dog.findMin,
					dog.findMax,
					minIntensity,
					maxIntensity,
					DoGImgLib2.blockSize,
					service,
					dog.cuda,
					dog.deviceCUDA,
					dog.accurateCUDA,
					dog.percentGPUMem );

			// maps the positions to full resolution
			if ( !points.isEmpty() )
				DownsampleTools.correctForDownsampling( points, mipmapTransform );

			perRegion.add( points );
		}

		final ArrayList< InterestPoint > combined = OverlappingRegions.combine( perRegion, OverlappingRegions.combineDistance );

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): " + Group.pvid( vd ) + ": " + combined.size() + " interest points in " + regions.size() + " region(s) overlapping other views (" + ( OverlappingRegions.size( perRegion ) - combined.size() ) + " duplicate(s) where the regions overlap each other dropped)" );

		return combined;
	}

	/**
	 * Detects the interest points of every present view in dog.toProcess and adds them to the map: the view is opened
	 * at the downsampling (DownsampleTools.openAndDownsample, not virtual), DoGImgLib2.computeDoG runs on the entire
	 * view, the detections are mapped to full resolution with the mipmap transform. A failing
	 * view is logged and skipped.
	 *
	 * @param interestPoints - the map the detections are added to, one list per view
	 * @param dog - the parameters (views, loader, downsampling, sigma, anisotropy, threshold, localization, intensity range, limit, CUDA)
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static void addInterestPoints( final HashMap< ViewId, List< InterestPoint > > interestPoints, final DoGParameters dog )
	{
		if ( dog.showProgress() )
			IJ.showProgress( dog.showProgressMin );

		int count = 1;

		// TODO: special iterator that takes into account missing views
		for ( final ViewDescription vd : dog.toProcess )
		{
			// make sure not everything crashes if one file is missing
			try
			{
				//
				// open the corresponding image (if present at this timepoint)
				//
				if ( !vd.isPresent() )
					continue;

				final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

				// downsampling is not virtual!
				final Pair<RandomAccessibleInterval, AffineTransform3D> input =
						DownsampleTools.openAndDownsample(
								dog.imgloader,
								vd,
								new long[] { dog.downsampleXY, dog.downsampleXY, dog.downsampleZ },
								false );

				// the voxel size of the opened image per dimension relative to x, the Gaussians become isotropic in physical units (null = isotropic in pixels)
				final double[] anisotropy = Double.isNaN( dog.anisotropyZ ) ? null : DownsampleTools.anisotropy( vd, input.getB(), dog.anisotropyZ );

				if ( anisotropy != null )
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): anisotropy of the opened image (voxel size per dimension relative to x) = " + Util.printCoordinates( anisotropy ) +
							", sigma in pixels = (" + dog.sigma / anisotropy[ 0 ] + ", " + dog.sigma / anisotropy[ 1 ] + ", " + dog.sigma / anisotropy[ 2 ] + ")" );

				List< InterestPoint > ips;

				try
				{
					if ( dog.regions == null )
					{
						ips = DoGImgLib2.computeDoG(
								(RandomAccessible)Views.extendMirrorSingle( input.getA() ),
								null, // mask
								new FinalInterval( input.getA() ),
								dog.sigma,
								anisotropy,
								dog.threshold,
								dog.localization,
								dog.findMin,
								dog.findMax,
								dog.minIntensity,
								dog.maxIntensity,
								DoGImgLib2.blockSize,
								service,
								dog.cuda,
								dog.deviceCUDA,
								dog.accurateCUDA,
								dog.percentGPUMem );

						// maps the positions to full resolution
						DownsampleTools.correctForDownsampling( ips, input.getB() );
					}
					else
					{
						ips = detectInRegions( vd, input.getA(), input.getB(), anisotropy, dog, service );
					}
				}
				finally
				{
					service.shutdown();
				}

				interestPoints.put( vd, ips );
			} catch ( Exception e )
			{
				IOFunctions.println( "An error occured (DOG): " + e );
				IOFunctions.println( "Failed to segment angleId: "
						+ vd.getViewSetup().getAngle().getId() + " channelId: "
						+ vd.getViewSetup().getChannel().getId() + " illumId: "
						+ vd.getViewSetup().getIllumination().getId()
						+ ". Continuing with next one." );
				e.printStackTrace();
			}

			if ( dog.showProgress() )
				IJ.showProgress( dog.showProgressMin + 
						( (double)(count++) / (double)dog.toProcess.size() ) / ( dog.showProgressMax - dog.showProgressMin ) );
		}

		if ( dog.showProgress() )
			IJ.showProgress( dog.showProgressMax );
	}

}
