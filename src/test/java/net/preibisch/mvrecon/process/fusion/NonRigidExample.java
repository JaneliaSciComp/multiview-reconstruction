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
package net.preibisch.mvrecon.process.fusion;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Sets;

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidTools;
import net.preibisch.mvrecon.tests.TestBoundingBox;
import util.URITools;

public class NonRigidExample
{
	@Test
	@Disabled("Requires specific dataset file that may not exist on all systems")
	public void testNonRigid()
	{
		System.out.println( "Test requires manual setup - see main() method" );
	}

	// ==================== Original main() method for manual testing ====================

	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		SpimData2 spimData;

		// load drosophila
		spimData = new XmlIoSpimData2( ).load( URITools.toURI( "/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml" ) );

		Pair< List< ViewId >, Interval > fused = testInterpolation( spimData, "My Bounding Box" );
		// for bounding box1111 test 128,128,128 vs 256,256,256 (no blocks), there are differences at the edges

		compareToFusion( spimData, fused.getA(), fused.getB() );
	}

	public static void compareToFusion(
			final SpimData2 spimData,
			final List< ViewId > fused,
			Interval boundingBox )
	{
		// downsampling
		double downsampling = Double.NaN;

		//
		// display virtually fused
		//

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": starting with affine" );

		// adjust bounding box
		boundingBox = FusionTools.createDownsampledBoundingBox( boundingBox, downsampling ).getA();

		// adjust registrations
		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						fused,
						spimData.getViewRegistrations().getViewRegistrations(),
						Double.NaN,
						downsampling );

		final RandomAccessibleInterval< FloatType > virtual =
				FusionTools.fuseVirtual(
						spimData.getSequenceDescription().getImgLoader(),
						registrations,
						spimData.getSequenceDescription().getViewDescriptions(),
						fused, FusionType.AVG_BLEND, boundingBox );

		FusionTools.getImagePlusInstance( virtual, false, "Fused Affine", 0, 255, DisplayImage.service ).show();

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": done with affine" );
	}

	public static Pair< List< ViewId >, Interval > testInterpolation(
			final SpimData2 spimData,
			final String bbTitle )
	{
		Interval boundingBox = TestBoundingBox.getBoundingBox( spimData, bbTitle );

		if ( boundingBox == null )
			return null;

		IOFunctions.println( BoundingBox.getBoundingBoxDescription( (BoundingBox)boundingBox ) );

		// select views to process
		final List< ViewId > viewsToFuse = new ArrayList< ViewId >(); // fuse
		final List< ViewId > viewsToUse = new ArrayList< ViewId >(); // used to compute the non-rigid transform

		viewsToUse.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		viewsToFuse.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );
		//viewsToFuse.add( new ViewId( 0, 0 ) );
		//viewsToFuse.add( new ViewId( 0, 1 ) );
		//viewsToFuse.add( new ViewId( 0, 2 ) );
		//viewsToFuse.add( new ViewId( 0, 3 ) );

		// filter not present ViewIds
		List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewsToUse );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		removed = SpimData2.filterMissingViews( spimData, viewsToFuse );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		// downsampling
		final double downsampling = Double.NaN;
		final double ds = Double.isNaN( downsampling ) ? 1.0 : downsampling;
		final int cpd = Math.max( 1, (int)Math.round( 10 / ds ) );
		//
		// display virtually fused
		//
		final ArrayList< String > labels = new ArrayList<>();

		//labels.add( "beads" );

		//labels.add( "beads13" );
		labels.add( "nuclei" );

		final int interpolation = 1;
		final long[] controlPointDistance = new long[] { cpd, cpd, cpd };
		final double alpha = 1.0;
		final boolean virtualGrid = false;

		final boolean displayDistances = false;

		final ExecutorService service = DeconViews.createExecutorService();

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": controlPointDistance = " + Util.printCoordinates( controlPointDistance ) );

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": starting with non-rigid" );

		// adjust bounding box
		boundingBox = FusionTools.createDownsampledBoundingBox( boundingBox, downsampling ).getA();

		// adjust registrations
		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						Sets.union( new HashSet<>( viewsToFuse ), new HashSet<>( viewsToUse ) ),
						spimData.getViewRegistrations().getViewRegistrations(),
						Double.NaN,
						downsampling );

		final RandomAccessibleInterval< FloatType > virtual =
				NonRigidTools.fuseVirtualInterpolatedNonRigid(
						spimData.getSequenceDescription().getImgLoader(),
						registrations,
						spimData.getViewInterestPoints().getViewInterestPoints(),
						spimData.getSequenceDescription().getViewDescriptions(),
						viewsToFuse,
						viewsToUse,
						labels,
						FusionType.AVG_BLEND,
						displayDistances,
						controlPointDistance,
						alpha,
						virtualGrid,
						interpolation,
						boundingBox,
						null,
						service );

		service.shutdown();

		//final RandomAccessibleInterval< FloatType > out = FusionTools.copyImgByPlane3d( virtual, new ImagePlusImgFactory< FloatType >( new FloatType() ), service, true );
		//final RandomAccessibleInterval< FloatType > out = FusionTools.copyImg( virtual, new ImagePlusImgFactory< FloatType >(), new FloatType(), service, true );
		//final RandomAccessibleInterval< FloatType > out = ImageJFunctions.wrapFloat( DisplayImage.getImagePlusInstance( virtual, false, "Fused Non-rigid", 0, 255 ) );

		// Non-rigid fusion took: 314887 ms.
		long time = System.currentTimeMillis();
		FusionTools.getImagePlusInstance( virtual, false, "Fused Non-rigid", 0, 255, DisplayImage.service ).show();
		System.out.println( "Non-rigid fusion took: " + (System.currentTimeMillis() - time) + " ms.");

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": done with non-rigid" );

		return new ValuePair<>( viewsToFuse, boundingBox );
	}
}
