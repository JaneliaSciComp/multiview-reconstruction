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
package net.preibisch.mvrecon.process.interestpointregistration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Intervals;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSAC;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;

public class ExamplePairwiseRegistration
{
	public static void main( String[] args ) throws SpimDataException
	{
		// please download example from here: https://drive.google.com/file/d/1OxEcgXhxbr2lgmbnHRqxbUxXDdC-2oHR/view?usp=sharing
		// load XML
		final SpimData2 data = 
				new XmlIoSpimData2().load(
						URI.create("file:/Users/preibischs/SparkTest/Stitching/dataset.xml") );

		// calibration etc
		final SequenceDescription sd = data.getSequenceDescription();

		// specify 2 views
		final ViewId viewId1 = new ViewId( 0, 0 );
		final ViewId viewId2 = new ViewId( 0, 2 );

		// get interest points
		final Map< Integer, InterestPoint> ipListLocal1 = getInterestPoints( data, viewId1, "beadsL" );
		final Map< Integer, InterestPoint> ipListLocal2 = getInterestPoints( data, viewId2, "beadsL" );

		System.out.println( "Loaded " + ipListLocal1.size() + " and " + ipListLocal2.size() + " interest points (in local coordinates of each view)." );

		// current transforms for the views
		final AffineTransform3D t1 = getTransform( data, viewId1 );
		final AffineTransform3D t2 = getTransform( data, viewId2 );

		System.out.println( "Transform1: " + t1 );
		System.out.println( "Transform2: " + t2 );

		// transform interest points into global coordinate space
		final Collection<InterestPoint> ip1 = TransformationTools.applyTransformation( ipListLocal1.values(), t1 );
		final Collection<InterestPoint> ip2 = TransformationTools.applyTransformation( ipListLocal2.values(), t2 );

		// find which points currently overlap with the other view
		final Dimensions dim1 = sd.getViewDescription( viewId1 ).getViewSetup().getSize();
		final Dimensions dim2 = sd.getViewDescription( viewId2 ).getViewSetup().getSize();

		// expanding the interval a bit to be more robust
		final List<InterestPoint> ipOverlap1 =
				overlappingPoints( ip1, Intervals.expand( new FinalInterval( dim2 ), 10 ), t2 );
		final List<InterestPoint> ipOverlap2 =
				overlappingPoints( ip2, Intervals.expand( new FinalInterval( dim1 ), 10 ), t1 );

		System.out.println( "Overlapping points 1: " + ipOverlap1.size() );
		System.out.println( "Overlapping points 2: " + ipOverlap2.size() );

		// perform matching
		final int numNeighbors = 3; // number of neighbors the descriptor is built from
		final int redundancy = 0; // redundancy of the descriptor (adds more neighbors and tests all combinations)
		final float ratioOfDistance = 3.0f; // how much better the best than the second best descriptor need to be
		final boolean limitSearchRadius = true; // limit search to a radius
		final float searchRadius = 200.0f; // the search radius

		List< PointMatchGeneric< InterestPoint > > candidates = new RGLDMMatcher<>().extractCorrespondenceCandidates(
				ipOverlap1,
				ipOverlap2,
				numNeighbors,
				redundancy,
				ratioOfDistance,
				Float.MAX_VALUE,
				limitSearchRadius,
				searchRadius );

		System.out.println( "Found " + candidates.size() + " correspondence candidates." );

		// perform RANSAC (warning: not safe for multi-threaded over pairs of images, this needs point duplication)
		ArrayList< PointMatchGeneric< InterestPoint > > inliers = new ArrayList<>();

		final TranslationModel3D model = new TranslationModel3D();

		final int minNumCorrespondences = 5;
		final int numIterations = 10_000;
		final double maxEpsilon = 0.5; // setting this very low so we get multi-consensus
		final double minInlierRatio = 0.1;

		boolean multiConsenus = true;
		boolean modelFound = false;

		do
		{
			inliers.clear();

			try
			{
				modelFound = model.filterRansac(
						candidates,
						inliers,
						numIterations,
						maxEpsilon, minInlierRatio ); 
			}
			catch ( NotEnoughDataPointsException e )
			{
				System.out.println( "Not enough points for matching. stopping.");
				System.exit( 1 );
			}
	
			if ( modelFound && inliers.size() >= minNumCorrespondences )
			{
				// highly suggested in general
				// inliers = RANSAC.removeInconsistentMatches( inliers );

				System.out.println( "Found " + inliers.size() + "/" + candidates.size() + " inliers with model: " + model );

				if ( multiConsenus )
					candidates = RANSAC.removeInliers( candidates, inliers );
			}
			else if ( modelFound )
			{
				System.out.println( "Model found, but not enough points (" + inliers.size() + "/" + minNumCorrespondences + ").");
			}
			else
			{
				System.out.println( "NO model found.");
			}
		} while ( multiConsenus && modelFound && inliers.size() >= minNumCorrespondences );
	}

	public static AffineTransform3D getTransform( final SpimData data, final ViewId viewId )
	{
		final Map<ViewId, ViewRegistration> rMap = data.getViewRegistrations().getViewRegistrations();
		final ViewRegistration reg = rMap.get( viewId );
		reg.updateModel();
		return reg.getModel();
	}

	public static Map< Integer, InterestPoint> getInterestPoints( final SpimData2 data, final ViewId viewId, final String label )
	{
		final Map<ViewId, ViewInterestPointLists> iMap = data.getViewInterestPoints().getViewInterestPoints();
		final ViewInterestPointLists iplists = iMap.get( viewId );

		// this is net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5
		final InterestPoints ips = iplists.getInterestPointList( label );

		// load interest points
		return ips.getInterestPointsCopy();
	}

	public static List<InterestPoint> overlappingPoints( final Collection<InterestPoint> ip1, final Interval intervalImg2, final AffineTransform3D t2 )
	{
		// use the inverse affine transform of the other view to map the points into the local interval of img2
		final AffineTransform3D t2inv = t2.inverse();

		final RealPoint p = new RealPoint( intervalImg2.numDimensions() );

		return ip1.stream().filter( ip -> {
			ip.localize( p );
			t2inv.apply( p, p );
			return Intervals.contains( intervalImg2 , p );
		} ).collect( Collectors.toList() );
	}
}
