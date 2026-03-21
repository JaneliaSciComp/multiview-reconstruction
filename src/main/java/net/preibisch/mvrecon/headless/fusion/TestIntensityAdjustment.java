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
package net.preibisch.mvrecon.headless.fusion;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import ij.ImageJ;
import mpicbg.models.AffineModel1D;
import mpicbg.models.IdentityModel;
import mpicbg.models.InterpolatedAffineModel1D;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.TranslationModel1D;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.intensityadjust.IntensityAdjustmentTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.URITools;

public class TestIntensityAdjustment
{
	public static void main( String[] args ) throws SpimDataException
	{
		final HashMap< Pair< Integer, Integer >, ArrayList< PointMatch > > intensityMatches = new HashMap<>();

		final ArrayList< PointMatch > pm01 = new ArrayList<>();

		pm01.add( new PointMatch( new Point( new double[] { 5 } ), new Point( new double[] { 10 } ) ) );
		pm01.add( new PointMatch( new Point( new double[] { 10 } ), new Point( new double[] { 20 } ) ) );
		pm01.add( new PointMatch( new Point( new double[] { 50 } ), new Point( new double[] { 100 } ) ) );

		final ArrayList< PointMatch > pm02 = new ArrayList<>();

		pm02.add( new PointMatch( new Point( new double[] { 5 } ), new Point( new double[] { 15 } ) ) );
		pm02.add( new PointMatch( new Point( new double[] { 10 } ), new Point( new double[] { 30 } ) ) );
		pm02.add( new PointMatch( new Point( new double[] { 50 } ), new Point( new double[] { 150 } ) ) );

		final ArrayList< PointMatch > pm12 = new ArrayList<>();

		pm12.add( new PointMatch( new Point( new double[] { 1 } ), new Point( new double[] { 1.5f } ) ) );
		pm12.add( new PointMatch( new Point( new double[] { 10 } ), new Point( new double[] { 15 } ) ) );

		intensityMatches.put( new ValuePair< Integer, Integer >( 0, 1 ), pm01 );
		intensityMatches.put( new ValuePair< Integer, Integer >( 0, 2 ), pm02 );
		intensityMatches.put( new ValuePair< Integer, Integer >( 1, 2 ), pm12 );

		final HashMap< Integer, ViewId > viewMap = new HashMap<>();
		viewMap.put( 0, new ViewId( 0, 1 ) );
		viewMap.put( 1, new ViewId( 0, 2 ) );
		viewMap.put( 2, new ViewId( 0, 3 ) );

		//IntensityAdjustmentTools.runGlobal( intensityMatches, viewMap, new AffineModel1D() );
		//System.exit(  0 );

		new ImageJ();

		// generate 4 views with 1000 corresponding beads, single timepoint
		// 
		//SpimData2 spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( new int[]{ 0, 90, 135 } ) );
		SpimData2 spimData = new XmlIoSpimData2().load( URITools.toURI( "/Users/spreibi/Documents/BIMSB/Projects/CLARITY/Big Data Sticher/Neubias_preibisch/GridBalance/dataset.xml" ) );

		System.out.println( "Views present:" );

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().values() )
			System.out.println( Group.pvid( viewId ) );

		testBalance( spimData );
	}

	public static void testBalance( final SpimData2 spimData )
	{
		//Interval bb = TestBoundingBox.testBoundingBox( spimData, false );
		
		Interval bb = spimData.getBoundingBoxes().getBoundingBoxes().get( 0 );
		System.out.println( bb );

		// select views to process
		final List< ViewId > viewIds = new ArrayList< ViewId >();
		viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		// filter not present ViewIds
		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		// downsampling
		double downsampling = 2;
		double downsamplingEstimation = 20;

		//
		// display virtually fused
		//
		final InterpolatedAffineModel1D< InterpolatedAffineModel1D< AffineModel1D, TranslationModel1D >, IdentityModel > model =
				new InterpolatedAffineModel1D<>(
						new InterpolatedAffineModel1D<>( new AffineModel1D(), new TranslationModel1D(), 0.1 ),
						new IdentityModel(), 0.1 );

		final HashMap< ViewId, AffineModel1D > intensityMapping =
				IntensityAdjustmentTools.computeIntensityAdjustment( spimData, viewIds, model, bb, downsamplingEstimation, Integer.MAX_VALUE, null );

		// adjust bounding box
		bb = FusionTools.createDownsampledBoundingBox( bb, downsampling ).getA();

		// adjust registrations
		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						viewIds,
						spimData.getViewRegistrations().getViewRegistrations(),
						Double.NaN,
						downsampling );

		final RandomAccessibleInterval< FloatType > virtualBalanced =
				FusionTools.fuseVirtual(
						spimData.getSequenceDescription().getImgLoader(),
						registrations,
						spimData.getSequenceDescription().getViewDescriptions(),
						viewIds, FusionType.AVG, 1, bb, intensityMapping );

		final RandomAccessibleInterval< FloatType > virtual =
				FusionTools.fuseVirtual(
						spimData.getSequenceDescription().getImgLoader(),
						registrations,
						spimData.getSequenceDescription().getViewDescriptions(),
						viewIds, FusionType.AVG, 1, bb, null );

		//
		// actually fuse into an image multithreaded
		//
		final RandomAccessibleInterval< FloatType > fusedImg = FusionTools.copyImg( virtual, new ImagePlusImgFactory<>( new FloatType() ), new FloatType(), null, true );
		final RandomAccessibleInterval< FloatType > fusedBalancedImg = FusionTools.copyImg( virtualBalanced, new ImagePlusImgFactory<>( new FloatType() ), new FloatType(), null, true );

		FusionTools.getImagePlusInstance( fusedImg, false, "Fused", 0, 255, DisplayImage.service ).show();
		FusionTools.getImagePlusInstance( fusedBalancedImg, false, "Fused Balanced", 0, 255, DisplayImage.service ).show();
	}
}
