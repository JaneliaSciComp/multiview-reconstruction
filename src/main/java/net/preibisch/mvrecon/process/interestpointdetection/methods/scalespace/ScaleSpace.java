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
package net.preibisch.mvrecon.process.interestpointdetection.methods.scalespace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;

import ij.IJ;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointSS;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Scale-space DoG detection on views (the counterpart of DoG for the single-scale DoG): opens each
 * view at the requested downsampling, runs {@link DoGScaleSpace} on the entire view and maps the
 * points (and their sigma) back to full resolution.
 *
 * @author Stephan Preibisch
 */
public class ScaleSpace
{
	/**
	 * Detects the interest points of all views in p.toProcess, see addInterestPoints
	 *
	 * @return the interest points per view (InterestPointSS with response and sigma, in full-resolution pixels)
	 */
	public static HashMap< ViewId, List< InterestPoint >> findInterestPoints( final ScaleSpaceDetectionParameters p )
	{
		final HashMap< ViewId, List< InterestPoint >> interestPoints = new HashMap< ViewId, List< InterestPoint >>();

		addInterestPoints( interestPoints, p );

		return interestPoints;
	}

	/**
	 * @param vd - the view
	 * @param mipmapTransform - the transform of the opened (downsampled) image to full resolution (DownsampleTools.openAndDownsample)
	 * @param anisotropyZ - z voxel / x voxel at FULL resolution, NaN = the ratio of the calibration
	 * @return the voxel size of the OPENED image per dimension relative to x (ScaleSpaceParameters.anisotropy)
	 */
	public static double[] anisotropy( final ViewDescription vd, final AffineTransform3D mipmapTransform, final double anisotropyZ )
	{
		final double vx, vy, vz;

		if ( vd.getViewSetup().hasVoxelSize() )
		{
			final VoxelDimensions voxelSize = vd.getViewSetup().getVoxelSize();
			vx = voxelSize.dimension( 0 );
			vy = voxelSize.dimension( 1 );
			vz = voxelSize.dimension( 2 );
		}
		else
		{
			vx = vy = vz = 1.0;
		}

		final double a = Double.isNaN( anisotropyZ ) ? vz / vx : anisotropyZ;

		final double sx = Math.abs( mipmapTransform.get( 0, 0 ) );
		final double sy = Math.abs( mipmapTransform.get( 1, 1 ) );
		final double sz = Math.abs( mipmapTransform.get( 2, 2 ) );

		return new double[] { 1.0, ( vy * sy ) / ( vx * sx ), ( a * sz ) / sx };
	}

	/**
	 * Detects the interest points of all views in p.toProcess for several thresholds at once (one computation per
	 * view), see addInterestPoints( perThreshold, p, thresholds )
	 *
	 * @return one map (the interest points per view) per threshold, in the order of the thresholds
	 */
	public static ArrayList< HashMap< ViewId, List< InterestPoint > > > findInterestPoints( final ScaleSpaceDetectionParameters p, final double[] thresholds )
	{
		final ArrayList< HashMap< ViewId, List< InterestPoint > > > perThreshold = new ArrayList<>();

		for ( int i = 0; i < thresholds.length; ++i )
			perThreshold.add( new HashMap<>() );

		addInterestPoints( perThreshold, p, thresholds );

		return perThreshold;
	}

	/**
	 * Detects the interest points of every present view in p.toProcess at the threshold p.scaleSpace.threshold and adds
	 * them to the map, see addInterestPoints( perThreshold, p, thresholds )
	 *
	 * @param interestPoints - the map the detections are added to, one list per view
	 * @param p - the parameters (views, loader, starting resolution, anisotropy, intensity range, limit, the scale space)
	 */
	public static void addInterestPoints( final HashMap< ViewId, List< InterestPoint > > interestPoints, final ScaleSpaceDetectionParameters p )
	{
		final ArrayList< HashMap< ViewId, List< InterestPoint > > > perThreshold = new ArrayList<>();
		perThreshold.add( interestPoints );

		addInterestPoints( perThreshold, p, new double[] { p.scaleSpace.threshold } );
	}

	/**
	 * Detects the interest points of every present view in p.toProcess for several thresholds at once and adds them to
	 * the map of each threshold: the view is opened at the starting resolution p.downsampling
	 * (DownsampleTools.openAndDownsample), the intensity range is the user's or the one of the opened view, the
	 * anisotropy of the opened image is derived from the calibration and p.anisotropyZ, DoGScaleSpace.computeDoGScaleSpace
	 * runs once on the entire view for all thresholds, and per threshold the detections are limited if requested and
	 * mapped (positions and sigma) to full resolution with the mipmap transform. A failing view is logged and skipped.
	 * p.scaleSpace is mutated per view (intensity range, anisotropy); p.scaleSpace.threshold is not used.
	 *
	 * @param perThreshold - one map per threshold the detections are added to, one list per view each
	 * @param p - the parameters (views, loader, starting resolution, anisotropy, intensity range, limit, the scale space)
	 * @param thresholds - the thresholds, in the order of perThreshold
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static void addInterestPoints( final List< HashMap< ViewId, List< InterestPoint > > > perThreshold, final ScaleSpaceDetectionParameters p, final double[] thresholds )
	{
		if ( perThreshold.size() != thresholds.length )
			throw new IllegalArgumentException( "One map per threshold is needed, got " + perThreshold.size() + " maps for " + thresholds.length + " thresholds." );

		if ( p.showProgress() )
			IJ.showProgress( p.showProgressMin );

		int count = 1;

		for ( final ViewDescription vd : p.toProcess )
		{
			// make sure not everything crashes if one file is missing
			try
			{
				if ( !vd.isPresent() )
					continue;

				final ExecutorService service = Threads.createFixedExecutorService( Threads.numThreads() );

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Scale space of " + Group.pvid( vd ) + " starting at downsampling " + Util.printCoordinates( p.downsampling ) + ( thresholds.length > 1 ? ", thresholds " + Arrays.toString( thresholds ) : "" ) );


				final Pair< RandomAccessibleInterval, AffineTransform3D > input =
						DownsampleTools.openAndDownsample(
								p.imgloader,
								vd,
								p.downsampling,
								false );

				// the intensity range is the user's (InterestPointParameters, as for the DoG), otherwise the one of this view
				if ( Double.isNaN( p.minIntensity ) || Double.isNaN( p.maxIntensity ) )
				{
					final float[] minmax = FusionTools.minMax( input.getA(), service );

					p.scaleSpace.minIntensity = minmax[ 0 ];
					p.scaleSpace.maxIntensity = minmax[ 1 ];

					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): intensity range of the view = [" + minmax[ 0 ] + ", " + minmax[ 1 ] + "] (computed, not set by the user)" );
				}
				else
				{
					p.scaleSpace.minIntensity = p.minIntensity;
					p.scaleSpace.maxIntensity = p.maxIntensity;
				}

				// the voxel size of the opened image per dimension relative to x, the Gaussians become isotropic in physical units
				p.scaleSpace.anisotropy = anisotropy( vd, input.getB(), p.anisotropyZ );

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): anisotropy of the opened image (voxel size per dimension relative to x) = " + Util.printCoordinates( p.scaleSpace.anisotropy ) +
						", sigma of the finest level in pixels = (" + p.scaleSpace.sigmaMin / p.scaleSpace.anisotropy[ 0 ] + ", " + p.scaleSpace.sigmaMin / p.scaleSpace.anisotropy[ 1 ] + ", " + p.scaleSpace.sigmaMin / p.scaleSpace.anisotropy[ 2 ] + ")" );

				final ArrayList< ArrayList< InterestPointSS > > peaksPerThreshold;

				try
				{
					peaksPerThreshold = DoGScaleSpace.computeDoGScaleSpace(
						(RandomAccessible)Views.extendMirrorSingle( input.getA() ),
						new FinalInterval( input.getA() ),
						new FinalInterval( input.getA() ),
						null, // mask
						p.scaleSpace,
						thresholds,
						service );
				}
				finally
				{
					service.shutdown();
				}

				for ( int i = 0; i < thresholds.length; ++i )
				{
					List< InterestPoint > ips = new ArrayList<>( peaksPerThreshold.get( i ) );

					if ( p.limitDetections )
						ips = InterestPointTools.limitList( p.maxDetections, p.maxDetectionsTypeIndex, ips );

					// maps positions and sigmas to full resolution (every threshold has its own positions)
					DownsampleTools.correctForDownsampling( ips, input.getB() );

					perThreshold.get( i ).put( vd, ips );
				}
			}
			catch ( Exception e )
			{
				IOFunctions.println( "An error occured (DOG-SS): " + e );
				IOFunctions.println( "Failed to segment angleId: "
						+ vd.getViewSetup().getAngle().getId() + " channelId: "
						+ vd.getViewSetup().getChannel().getId() + " illumId: "
						+ vd.getViewSetup().getIllumination().getId()
						+ ". Continuing with next one." );
				e.printStackTrace();
			}

			if ( p.showProgress() )
				IJ.showProgress( p.showProgressMin +
						( (double)(count++) / (double)p.toProcess.size() ) / ( p.showProgressMax - p.showProgressMin ) );
		}

		if ( p.showProgress() )
			IJ.showProgress( p.showProgressMax );
	}
}
