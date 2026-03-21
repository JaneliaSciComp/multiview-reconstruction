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
package net.preibisch.mvrecon.headless.quality;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.quality.FRCRealRandomAccessible;
import net.preibisch.mvrecon.process.quality.FRCTools;
import util.URITools;

public class TestQuality
{
	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		SpimData2 spimData;
		
		// generate 4 views with 1000 corresponding beads, single timepoint
		// spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[]{ 0, 90, 135 } ) );

		for ( int run = 2; run <= 3; ++run )
		{
			// load drosophila
			spimData = new XmlIoSpimData2().load( URITools.toURI( "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml" ) );

			for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
				System.out.println( Group.pvid( viewId ) );

			// select views to process
			final List< ViewId > viewIds = new ArrayList< ViewId >();
	
			if ( run == 2 )
				for ( int i = 0; i <= 55; ++i  )  // angle 1 view A
					viewIds.add( new ViewId( 0, i ) );

			if ( run == 3 )
				for ( int i = 119; i <=174; ++i  )  // angle 1 view B
					viewIds.add( new ViewId( 0, i ) );
	
			if ( run == 0 )
				for ( int i = 56; i <= 118; ++i  ) // angle 2 view A
					viewIds.add( new ViewId( 0, i ) );

			if ( run == 1 )
				for ( int i = 175; i <=237; ++i  )  // angle 2 view B
					viewIds.add( new ViewId( 0, i ) );
			
	
			//for ( int i = 0; i <= 5; ++i  )
			//	viewIds.add( new ViewId( 0, i ) );
	
			//viewIds.add( new ViewId( 0, 10 ) );
			//viewIds.add( new ViewId( 0, 0 ) );
	
			// filter not present ViewIds
			//final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
			//IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );
	
			// re-populate not present ViewIds
			updateMissingViews( spimData, viewIds );
			BoundingBoxMaximal.ignoreMissingViews = true;
	
			final boolean relativeFRC = true;
			final boolean smoothLocalFRC = false;
			final int fftSize = 512;
	
			testQuality( spimData, viewIds, relativeFRC, smoothLocalFRC, fftSize );

			spimData = null;
			
			System.gc();
			Runtime.getRuntime().runFinalization();
		}
	}

	public static void testQuality( final SpimData2 spimData, final List< ViewId > viewIds, final boolean relative, final boolean smooth, final int fftSize )
	{
		final boolean isBrain = spimData.getBasePath().getAbsolutePath().contains( "/Volumes/home/Data/brain/" );
		Interval bb;

		if ( !isBrain )
		{
			bb = new BoundingBoxMaximal( viewIds, spimData ).estimate( "Full Bounding Box" );
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": bounding box = " + bb );
	
			bb = trimInterval( bb );
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": cropped bounding box = " + Util.printInterval( bb ) );
		}
		else
		{
			// full brain bounding box
			// [-3378, -2334, -3814] -> [8062, 13137, 3054], dimensions (11441, 15472, 6869)
			bb = new FinalInterval( new long[] { -3378, -2334, -3814 }, new long[] { 8062, 13137, 3054 } );
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Predefined bounding box = " + Util.printInterval( bb ) );
		}

		// img loading and registrations
		final ImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();
		final ViewRegistrations registrations = spimData.getViewRegistrations();

		final ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > data = new ArrayList<>();

		for ( final ViewId viewId : viewIds )
		{
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading view " +  Group.pvid( viewId ) + " ..." );

			final RandomAccessibleInterval input = imgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );
			//DisplayImage.getImagePlusInstance( input, true, "Fused, Virtual", 0, 255 ).show();

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Computing FRC for " +  Group.pvid( viewId ) + " ..." );

			//final FRCRealRandomAccessible< FloatType > frc = FRCTools.distributeGridFRC( input, 0.1, 20, fftSize, relative, smooth, FRCRealRandomAccessible.relativeFRCDist, null );
			//DisplayImage.getImagePlusInstance( frc.getRandomAccessibleInterval(), true, "Fused, Virtual", Double.NaN, Double.NaN ).show();

			final ViewRegistration vr = registrations.getViewRegistration( viewId );
			vr.updateModel();

			data.add( new ValuePair<>( FRCRealRandomAccessible.getFloatRAI( input ), vr.getModel() ) );
		}

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": FRC done." );

		// downsampling
		double downsampling = 4; //16; //Double.NaN;

		if ( isBrain )
			downsampling = 16;

		//
		// display virtually fused
		//

		final RandomAccessibleInterval< FloatType > virtual = FRCTools.fuseRAIs( data, downsampling, bb, 1 );
		FusionTools.getImagePlusInstance( virtual, false, "Fused, Virtual" + viewIds.get( 0 ).getViewSetupId(), Double.NaN, Double.NaN, DisplayImage.service ).show();

	}

	public static void updateMissingViews( final SpimData2 spimData, final List< ViewId > viewIds )
	{
		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd  = spimData.getSequenceDescription().getViewDescription( viewId );

			if ( !vd.isPresent() )
			{
				for ( final ViewDescription vdc : spimData.getSequenceDescription().getViewDescriptions().values() )
				{
					if ( vd.getViewSetup().getAngle() == vdc.getViewSetup().getAngle() &&
							vd.getViewSetup().getChannel() == vdc.getViewSetup().getChannel() &&
							vd.getViewSetup().getTile() == vdc.getViewSetup().getTile() &&
							vdc.getViewSetupId() != vd.getViewSetupId() )
					{
						System.out.println( "Missing view " + Group.pvid( vd ) + " compensated from " + Group.pvid( vdc ) );

						final ViewRegistration vrc = spimData.getViewRegistrations().getViewRegistration( vdc );
						vrc.updateModel();

						final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( vd );
						vr.getTransformList().clear();
						vr.getTransformList().addAll( vrc.getTransformList() );
						vr.updateModel();
					}
				}
			}
		}
	}

	public static Interval trimInterval( final Interval interval )
	{
		final long[] min = new long[ interval.numDimensions() ];
		final long[] max = new long[ interval.numDimensions() ];

		for ( int d = 0; d < interval.numDimensions(); ++d )
		{
			min[ d ] = interval.min( d ) + 10;
			max[ d ] = interval.max( d ) - 10;
		}

		return new FinalInterval( min, max );
	}
}
