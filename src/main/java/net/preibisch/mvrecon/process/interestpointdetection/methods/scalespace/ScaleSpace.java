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
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointdetection.OverlappingRegions;
import net.preibisch.mvrecon.process.fusion.FusionTools;
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
	 * @return the interest points per view (with response, sigma and intensity; positions and sigma in full-resolution pixels)
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
	 * @return the voxel size of the OPENED image per dimension relative to x (ScaleSpaceParameters.anisotropy), see DownsampleTools.anisotropy (shared with the DoG)
	 */
	public static double[] anisotropy( final ViewDescription vd, final AffineTransform3D mipmapTransform, final double anisotropyZ )
	{
		return DownsampleTools.anisotropy( vd, mipmapTransform, anisotropyZ );
	}

	/**
	 * Detects the interest points of every present view in p.toProcess and adds them to the map: the view is opened at
	 * the starting resolution p.downsampling (DownsampleTools.openAndDownsample), the intensity range is the user's or
	 * the one of the opened view, the anisotropy of the opened image is derived from the calibration and p.anisotropyZ,
	 * DoGScaleSpace.computeDoGScaleSpace runs on the entire view, the detections are mapped
	 * (positions and sigma) to full resolution with the mipmap transform. A failing view is logged and skipped.
	 * p.scaleSpace is mutated per view (intensity range, anisotropy).
	 *
	 * @param interestPoints - the map the detections are added to, one list per view
	 * @param p - the parameters (views, loader, starting resolution, anisotropy, intensity range, limit, the scale space)
	 */
	/**
	 * The detection of a view restricted to the regions that overlap other views (InterestPointParameters.regions): one
	 * computeDoGScaleSpace call per region (the image interval stays the whole image: the octaves and their domains are
	 * the same as in a run on the whole view; the scale space finds the peaks on the region border itself), the lists
	 * mapped to full resolution and combined (OverlappingRegions.combine: the regions of different partners overlap each
	 * other), then the merge of near-identical peaks across the regions that a run on the whole image does within its
	 * image (DoGScaleSpace.mergeDuplicates). The intensity range is the view's (p.scaleSpace, set by the caller). A view
	 * without a region gets an empty list.
	 *
	 * @param vd - the view
	 * @param img - the opened (downsampled) image
	 * @param mipmapTransform - its transform to full resolution
	 * @param p - the parameters (regions set, scaleSpace.minIntensity/maxIntensity/anisotropy set for this view)
	 * @param service - the executor
	 * @return the detections of the view at full resolution, ids 0..n-1
	 */
	@SuppressWarnings({ "rawtypes", "unchecked" })
	static List< InterestPoint > detectInRegions( final ViewDescription vd, final RandomAccessibleInterval img, final AffineTransform3D mipmapTransform, final ScaleSpaceDetectionParameters p, final ExecutorService service )
	{
		final FinalInterval image = new FinalInterval( img );
		final List< Interval > regions = p.regions.intervals( vd, image, mipmapTransform );

		if ( regions.isEmpty() )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): " + Group.pvid( vd ) + " has no region overlapping another view, no interest points" );
			return new ArrayList<>();
		}

		final RandomAccessible extended = Views.extendMirrorSingle( img );
		final List< List< InterestPoint > > perRegion = new ArrayList<>();

		for ( final Interval region : regions )
		{
			final List< InterestPoint > points = DoGScaleSpace.computeDoGScaleSpace( extended, image, region, null, p.scaleSpace, service );

			// maps positions and sigmas to full resolution
			if ( !points.isEmpty() )
				DownsampleTools.correctForDownsampling( points, mipmapTransform );

			perRegion.add( points );
		}

		ArrayList< InterestPoint > combined = OverlappingRegions.combine( perRegion, OverlappingRegions.combineDistance );
		final int duplicates = OverlappingRegions.size( perRegion ) - combined.size();
		int merged = 0;

		if ( combined.size() > 1 )
		{
			final double[] scale = new double[] { Math.abs( mipmapTransform.get( 0, 0 ) ), Math.abs( mipmapTransform.get( 1, 1 ) ), Math.abs( mipmapTransform.get( 2, 2 ) ) };
			final int before = combined.size();
			final ArrayList< InterestPoint > mergedPoints = DoGScaleSpace.mergeDuplicates( combined, scale, p.scaleSpace.sigmaMin, p.scaleSpace.combineDistance );
			merged = before - mergedPoints.size();

			combined = new ArrayList<>( mergedPoints.size() );
			for ( int id = 0; id < mergedPoints.size(); ++id )
				combined.add( mergedPoints.get( id ).derive( id, mergedPoints.get( id ).getL() ) );
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): " + Group.pvid( vd ) + ": " + combined.size() + " interest points in " + regions.size() + " region(s) overlapping other views (" + duplicates + " duplicate(s) where the regions overlap each other dropped, " + merged + " near-identical peak(s) merged across regions)" );

		return combined;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public static void addInterestPoints( final HashMap< ViewId, List< InterestPoint > > interestPoints, final ScaleSpaceDetectionParameters p )
	{
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

				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Scale space of " + Group.pvid( vd ) + " starting at downsampling " + Util.printCoordinates( p.downsampling ) );

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

				List< InterestPoint > ips;

				try
				{
					if ( p.regions == null )
					{
						ips = DoGScaleSpace.computeDoGScaleSpace(
							(RandomAccessible)Views.extendMirrorSingle( input.getA() ),
							new FinalInterval( input.getA() ),
							new FinalInterval( input.getA() ),
							null, // mask
							p.scaleSpace,
							service );

						// maps positions and sigmas to full resolution
						DownsampleTools.correctForDownsampling( ips, input.getB() );
					}
					else
					{
						ips = detectInRegions( vd, input.getA(), input.getB(), p, service );
					}
				}
				finally
				{
					service.shutdown();
				}

				interestPoints.put( vd, ips );
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
