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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;

import ij.IJ;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
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
	public static HashMap< ViewId, List< InterestPoint >> findInterestPoints( final ScaleSpaceDetectionParameters p )
	{
		final HashMap< ViewId, List< InterestPoint >> interestPoints = new HashMap< ViewId, List< InterestPoint >>();

		addInterestPoints( interestPoints, p );

		return interestPoints;
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

				// the intensity range is defined per detection (InterestPointParameters), as for the DoG
				p.scaleSpace.minIntensity = p.minIntensity;
				p.scaleSpace.maxIntensity = p.maxIntensity;

				final ArrayList< InterestPointSS > peaks = DoGScaleSpace.computeDoGScaleSpace(
						(RandomAccessible)Views.extendMirrorSingle( input.getA() ),
						new FinalInterval( input.getA() ),
						new FinalInterval( input.getA() ),
						null, // mask
						p.scaleSpace,
						service );

				service.shutdown();

				List< InterestPoint > ips = new ArrayList<>( peaks );

				if ( p.limitDetections )
					ips = InterestPointTools.limitList( p.maxDetections, p.maxDetectionsTypeIndex, ips );

				// maps positions and sigmas to full resolution
				DownsampleTools.correctForDownsampling( ips, input.getB() );

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
