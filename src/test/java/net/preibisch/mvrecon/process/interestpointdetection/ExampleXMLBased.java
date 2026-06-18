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
package net.preibisch.mvrecon.process.interestpointdetection;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class ExampleXMLBased
{
	public static void main( String[] args ) throws SpimDataException
	{
		int viewSetupId = 1;
		//
		// this needs the XML
		//
		SpimData2 data = new XmlIoSpimData2().load( URI.create("file:/nrs/saalfeld/john/for/keller/danio_1_488/dataset-orig-tifs/3/dataset-stephan.xml") );

		ViewId myViewId = new ViewId( 0, viewSetupId );

		// calibration etc
		SequenceDescription sd = data.getSequenceDescription();
		ViewDescription vd = sd.getViewDescription( myViewId );
		System.out.println( "voxelSize: " + Arrays.toString( vd.getViewSetup().getVoxelSize().dimensionsAsDoubleArray() ) );
		System.out.println( "tile id: " + vd.getViewSetup().getTile().getId() );

		SetupImgLoader<?> setupImgLoader = sd.getImgLoader().getSetupImgLoader( myViewId.getViewSetupId() );

		// full res
		RandomAccessibleInterval<?> img = setupImgLoader.getImage( myViewId.getTimePointId() );

		// multi-res
		if ( MultiResolutionSetupImgLoader.class.isInstance( setupImgLoader ) )
		{
			RandomAccessibleInterval imgS3 = ((MultiResolutionSetupImgLoader)setupImgLoader).getImage( myViewId.getTimePointId(), 3 );
			double[][] mipMapSteps = ((MultiResolutionSetupImgLoader)setupImgLoader).getMipmapResolutions();
			AffineTransform3D[] mipMapT = ((MultiResolutionSetupImgLoader)setupImgLoader).getMipmapTransforms();
			new ImageJ();
			ImageJFunctions.show( imgS3 );
		}

		// dealing with transforms
		Map<ViewId, ViewRegistration> rMap = data.getViewRegistrations().getViewRegistrations();
		ViewRegistration reg = rMap.get( myViewId );
		System.out.println( "num transformations: " + reg.getTransformList().size() );
		reg.updateModel();
		System.out.println( "concatenated model: "  + reg.getModel() );

		// dealing with interest points
		Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
		ViewInterestPointLists iplists = iMap.get( myViewId );

		System.out.println( "\navailable interest point labels:");
		iplists.getHashMap().keySet().forEach( k -> System.out.println( k ) );
		String myLabel = "beads8v2";
		InterestPoints ips = iplists.getInterestPointList( myLabel ); // this is net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5

		// load interest points
		Map< Integer, InterestPoint> p = ips.getInterestPointsCopy();

		for ( int j = 0; j < Math.min( 15, p.size() ) ; ++j )
		{
			InterestPoint point = p.get( j );
			System.out.println( point.getId() + ": " + Arrays.toString( point.getL() ) );
		}

		// load correspondences
		Collection<CorrespondingInterestPoints> cp = ips.getCorrespondingInterestPointsCopy();

		AffineTransform3D myModel = rMap.get( myViewId ).getModel();

		System.out.println( "\nCorrespondences" );

		for ( final CorrespondingInterestPoints cpoint : cp )
		{
			int myId = cpoint.getDetectionId();
			String otherLabel = cpoint.getCorrespodingLabel();
			ViewId otherViewId = cpoint.getCorrespondingViewId();
			int otherId = cpoint.getCorrespondingDetectionId();

			InterestPoint myPoint = p.get( myId );

			InterestPoints pOther = iMap.get( otherViewId ).getInterestPointList( otherLabel );
			InterestPoint otherPoint = pOther.getInterestPointsCopy().get( otherId );
			AffineTransform3D otherModel = rMap.get( otherViewId ).getModel();

			myModel.apply( myPoint.getL(), myPoint.getW() );
			otherModel.apply( otherPoint.getL(), otherPoint.getW() );

			System.out.println(
					Group.pvid( myViewId ) + " label=" + myLabel +" id=" + myId + ", l=" + Arrays.toString( myPoint.getL() ) + " corresponds to " + 
					Group.pvid( otherViewId ) + " label=" + otherLabel +" id=" + otherId + ", l=" + Arrays.toString( otherPoint.getL() )  );

			System.out.println( "w=" + Arrays.toString( myPoint.getW() ) + " >>> w=" + Arrays.toString( otherPoint.getW() ) );
		}

		// load and modify all interest points
		iMap.values().forEach( list ->
		{
			list.getHashMap().keySet().forEach( label -> {
				list.getInterestPointList( label ).getInterestPointsCopy();
				list.getInterestPointList( label ).getCorrespondingInterestPointsCopy();
				list.getInterestPointList( label ).setBasePath( URI.create("file:/nrs/saalfeld/john/for/keller/danio_1_488/dataset-orig-tifs/3/test/"));
			});
		});

		new XmlIoSpimData2().save( data, URI.create("file:/nrs/saalfeld/john/for/keller/danio_1_488/dataset-orig-tifs/3/test/dataset-stephan-2.xml") );
	}
}
