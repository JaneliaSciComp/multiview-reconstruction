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
package net.preibisch.mvrecon.process.interestpointregistration;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import bdv.BigDataViewer;
import mpicbg.models.AbstractAffineModel3D;
import mpicbg.models.AbstractModel;
import mpicbg.models.Affine3D;
import mpicbg.models.AffineModel3D;
import mpicbg.models.Model;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RealInterval;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.ViewSetupUtils;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.vecmath.Matrix4d;
import net.preibisch.mvrecon.vecmath.Matrix4f;
import net.preibisch.mvrecon.vecmath.Quat4f;
import net.preibisch.mvrecon.vecmath.Transform3D;
import net.preibisch.mvrecon.vecmath.Vector3d;
import net.preibisch.mvrecon.vecmath.Vector3f;

public class TransformationTools
{
	public static NumberFormat f = new DecimalFormat("#.####");

	public static void reCenterViews(final BigDataViewer viewer, final Collection<BasicViewDescription< ? >> selectedViews, final ViewRegistrations viewRegistrations)
	{
		AffineTransform3D currentViewerTransform = viewer.getViewer().state().getViewerTransform();
		final int cX = viewer.getViewer().getWidth() / 2; // size of the display area of the frame
		final int cY = viewer.getViewer().getHeight() / 2; // size of the display area of the frame

		IOFunctions.println( viewer.getViewer().getWidth() + " " + viewer.getViewer().getHeight() );

		final HashMap< BasicViewDescription< ? >, Dimensions > dimensions = new HashMap<>();
		final HashMap< BasicViewDescription< ? >, AffineTransform3D > registrations = new HashMap<>();

		for ( final BasicViewDescription< ? > view : selectedViews )
		{
			viewRegistrations.getViewRegistration( view ).updateModel();
			registrations.put( view, viewRegistrations.getViewRegistration( view ).getModel() );
			dimensions.put( view, view.getViewSetup().getSize() );
		}

		final BoundingBox bb = new BoundingBoxMaximal( selectedViews, dimensions, registrations ).estimate( "max" );
		final double[] com = new double[] {
				( bb.max( 0 ) - bb.min( 0 ) )/2 + bb.min( 0 ),
				( bb.max( 1 ) - bb.min( 1 ) )/2 + bb.min( 1 ),
				( bb.max( 2 ) - bb.min( 2 ) )/2 + bb.min( 2 ) };

		final RealInterval bounds = currentViewerTransform.estimateBounds( bb );
		IOFunctions.println( printRealInterval( bounds ));

		double currentScale = Math.max( 
				( bounds.realMax( 0 ) - bounds.realMin( 0 ) ) / viewer.getViewer().getWidth(),
				( bounds.realMax( 1 ) - bounds.realMin( 1 ) ) / viewer.getViewer().getHeight() );

		final Scale3D scale = new Scale3D( 1.0/currentScale, 1.0/currentScale, 1.0/currentScale );

		final double oldZ = currentViewerTransform.get( 2, 3 );

		// ignore old translation
		currentViewerTransform.set( 0, 0, 3 );
		currentViewerTransform.set( 0, 1, 3 );
		currentViewerTransform.set( 0, 2, 3 );

		currentViewerTransform.preConcatenate( scale );

		// to screen units
		currentViewerTransform.apply( com, com );

		// reset translational part
		currentViewerTransform.set( -com[0] + cX , 0, 3 );
		currentViewerTransform.set( -com[1] + cY , 1, 3 );

		// check if all selected views are 2d
		boolean allViews2D = true;
		for (final BasicViewDescription< ? > vd : selectedViews)
			if (vd.isPresent() && vd.getViewSetup().hasSize() && vd.getViewSetup().getSize().dimension( 2 ) != 1)
			{
				allViews2D = false;
				break;
			}

		IOFunctions.println( "All views 2d: " + allViews2D );

		// do not move in z if we have 2d data
		if (allViews2D)
			currentViewerTransform.set( oldZ * scale.getScale( 2 ), 2, 3 );
		else
			currentViewerTransform.set( -com[2], 2, 3 );

		viewer.getViewer().setCurrentViewerTransform( currentViewerTransform );
	}

	public static Pair< double[], AffineTransform3D > scaling( final Dimensions dim, final AffineTransform3D transformationModel )
	{
		final AffineTransform3D transform = transformationModel.copy();

		final AffineTransform3D mapBack = TransformationTools.computeMapBackModel(
				dim,
				new AffineTransform3D(), // identity
				TransformationTools.getModel( transform ), // current model
				new RigidModel3D() ); // what to use

		// reset translation
		mapBack.set( 0.0, 0, 3 );
		mapBack.set( 0.0, 1, 3 );
		mapBack.set( 0.0, 2, 3 );

		//System.out.println( "MapBack: " + TransformationTools.printAffine3D( mapBack ) );

		final AffineTransform3D atOrigin = transform.preConcatenate( mapBack );

		//System.out.println( "At origin: " + TransformationTools.printAffine3D( atOrigin ) );

		// there seems to be a bug in Transform3D, it does mix up the y/z dimensions sometimes
		// TransformationTools.getScaling( atOrigin, scale );

		// the scale is approximately now the diagonal entries in the matrix
		// and we are only interested in the absolute value since it could be mirrored, in which case the scaling is negative
		return new ValuePair<>( new double[]{ Math.abs( atOrigin.get( 0, 0 ) ), Math.abs( atOrigin.get( 1, 1 ) ), Math.abs( atOrigin.get( 2, 2 ) ) }, mapBack );
	}

	public static AffineTransform3D averageTransforms( final Collection< ? extends AffineGet > models )
	{
		if ( models == null || models.size() == 0 )
			return null;

		final double[] sum = new double[ 12 ];
		
		for ( AffineGet m : models )
		{
			final double[] tmp = m.getRowPackedCopy();
			for ( int i = 0; i < sum.length; ++i )
				sum[ i ] += tmp[ i ];
		}

		for ( int i = 0; i < sum.length; ++i )
			sum[ i ] /= (double)models.size();

		final AffineTransform3D affine = new AffineTransform3D();
		affine.set(
				sum[ 0 ], sum[ 1 ], sum[ 2 ], sum[ 3 ],
				sum[ 4 ], sum[ 5 ], sum[ 6 ], sum[ 7 ],
				sum[ 8 ], sum[ 9 ], sum[ 10 ], sum[ 11 ] );

		return affine;
	}

	public static double[] averageVectors( final Collection< double[] > vectors )
	{
		if ( vectors == null || vectors.size() == 0 )
			return null;

		if ( vectors.size() == 1 )
			return vectors.iterator().next();

		final double[] sum = new double[ vectors.iterator().next().length ];
		
		for ( final double[] tmp : vectors )
		{
			for ( int i = 0; i < sum.length; ++i )
				sum[ i ] += tmp[ i ];
		}

		for ( int i = 0; i < sum.length; ++i )
			sum[ i ] /= (double)vectors.size();

		return sum;
	}

	/**
	 * WARNING: This fails on older MACs, in this case remove: 
	 * 
	 * Check if Apple's out-dated Java 3D version 1.3 is installed in System/Library/Java/Extensions/ on your Mac. 
	 * Remove all Java 3D 1.3 related files including vecmath.jar (jar, jnilib), they are useless.
	 * 
	 * @param model - the model
	 * @return - String description of rot. axis
	 */
	public static String getRotationAxis( final RigidModel3D model )
	{
		try
		{
			final Matrix4f matrix = new Matrix4f();
			getTransform3D( model ).get( matrix );
			final Quat4f qu = new Quat4f();
			qu.set( matrix );
			
			final Vector3f n = new Vector3f(qu.getX(),qu.getY(), qu.getZ());
			n.normalize();
			
			return "Approx. axis: " + n + ", approx. angle: " + Math.toDegrees( Math.acos( qu.getW() ) * 2 );
		}
		catch ( Exception e )
		{
			return "Check if Apple's out-dated Java 3D version 1.3 is installed in System/Library/Java/Extensions/ on your Mac." +
					"Remove all Java 3D 1.3 related files including vecmath.jar (jar, jnilib), they are useless.";
		}
	}

	public static void getScaling( final Affine3D< ? > affine, final double[] scale ) { getScaling( getTransform3D( affine ), scale ); }

	public static void getScaling( final AffineGet affine, final double[] scale ) { getScaling( getTransform3D( affine ), scale ); }

	public static String getScaling( final Affine3D< ? > affine ) { return getScaling( getTransform3D( affine ) ); }

	public static String getScaling( final AffineGet affine ) { return getScaling( getTransform3D( affine ) ); }

	public static String getScaling( final Transform3D t )
	{
		final double[] scale = new double[ 3 ];
		getScaling( t, scale );

		return "Scaling: " + f.format( scale[ 0 ] ) + ", " + f.format( scale[ 1 ] ) + ", " + f.format( scale[ 2 ] );
	}

	public static void getScaling( final Transform3D t, double[] scale )
	{
		final Vector3d x = new Vector3d( 1, 0, 0 );
		final Vector3d y = new Vector3d( 0, 1, 0 );
		final Vector3d z = new Vector3d( 0, 0, 1 );

		t.transform( x );
		t.transform( y );
		t.transform( z );

		scale[ 0 ] = x.length();
		scale[ 1 ] = y.length();
		scale[ 2 ] = z.length();
	}

	public static Pair< double[], String > computeAverageCalibration(
			final Iterable< ? extends  BasicViewDescription< ? > > group,
			final ViewRegistrations vrs)
	{
		String unit = null;
		double avgCalX = 0;
		double avgCalY = 0;
		double avgCalZ = 0;
		int count = 0;

		for ( final BasicViewDescription< ? > vd : group )
		{
			System.out.println( "\n" + Group.pvid( vd ) );

			final Pair< double[], String > transformedCal = TransformationTools.computeCalibration( vd, vrs );

			avgCalX += transformedCal.getA()[ 0 ];
			avgCalY += transformedCal.getA()[ 1 ];
			avgCalZ += transformedCal.getA()[ 2 ];
			++count;

			if ( unit == null )
				unit = transformedCal.getB();
			else if ( unit.equalsIgnoreCase( transformedCal.getB() ) )
				unit = transformedCal.getB();
			else
				unit = "inconsistent";

			System.out.println( "Calibration (transformed): " + Util.printCoordinates( transformedCal.getA() ) + " " + transformedCal.getB() );
		}

		if ( count == 0 )
			return new ValuePair<>( new double[] { 1, 1, 1 }, "micrometer" );
		else
			return new ValuePair<>( new double[] { avgCalX / (double)count, avgCalY / (double)count, avgCalZ / (double)count }, unit );
	}
	
	public static Pair< double[], String > computeCalibration( final BasicViewDescription< ? > vd, final ViewRegistrations vrs )
	{
		final VoxelDimensions vs = ViewSetupUtils.getVoxelSize( vd.getViewSetup() );
		final double[] cal, scale = new double[ 3 ];
		final String unit;

		if ( vs == null )
		{
			cal = new double[] { 1, 1, 1 };
			unit = "px";
		}
		else
		{
			cal = new double[ 3 ];
			vs.dimensions( cal );
			unit = vs.unit();
		}

		final ViewRegistration vr = vrs.getViewRegistration( vd );
		vr.updateModel();
		TransformationTools.getScaling( vr.getModel(), scale );

		// e.g. in z it is 3 um/px, if we scale 3x, it is 1 um/px
		cal[ 0 ] /= scale[ 0 ];
		cal[ 1 ] /= scale[ 1 ];
		cal[ 2 ] /= scale[ 2 ];

		return new ValuePair<>( cal, unit );
	}

	public static Transform3D getTransform3D( final Affine3D< ? > affine )
	{
		final double[][] m = new double[ 3 ][ 4 ];
		((Affine3D<?>)affine).toMatrix( m );

		final Transform3D transform = new Transform3D();
		final double[] m2 = new double[ 16 ];
		transform.get( m2 );
		
		m2[ 0 ] = m[0][0];
		m2[ 1 ] = m[0][1];
		m2[ 2 ] = m[0][2];
		m2[ 3 ] = m[0][3];

		m2[ 4 ] = m[1][0];
		m2[ 5 ] = m[1][1];
		m2[ 6 ] = m[1][2];
		m2[ 7 ] = m[1][3];

		m2[ 8 ] = m[2][0];
		m2[ 9 ] = m[2][1];
		m2[ 10] = m[2][2];
		m2[ 11] = m[2][3];

		transform.set( m2 );

		return transform;
	}

	public static Transform3D getTransform3D( final AffineGet affine )
	{
		final double[] m = affine.getRowPackedCopy();

		final Transform3D transform = new Transform3D();
		final double[] m2 = new double[ 16 ];
		transform.get( m2 );
		
		m2[ 0 ] = m[ 0 ];
		m2[ 1 ] = m[ 1 ];
		m2[ 2 ] = m[ 2 ];
		m2[ 3 ] = m[ 3 ];

		m2[ 4 ] = m[ 4 ];
		m2[ 5 ] = m[ 5 ];
		m2[ 6 ] = m[ 6 ];
		m2[ 7 ] = m[ 7 ];

		m2[ 8 ] = m[ 8 ];
		m2[ 9 ] = m[ 9 ];
		m2[ 10] = m[ 10];
		m2[ 11] = m[ 11];

		transform.set( m2 );

		return transform;
	}

	public static <M extends AbstractAffineModel3D<M>> Transform3D getTransform3D( final M model )
	{
		final Transform3D transform = new Transform3D();
		final double[] m = model.getMatrix( null );

		final double[] m2 = new double[ 16 ];
		transform.get( m2 );

		for ( int i = 0; i < m.length; ++i )
			m2[ i ] = m[ i ];

		transform.set( m2 );

		return transform;
	}

	public static AffineModel3D getAffineModel3D( Transform3D transform )
	{
		final double[] m = new double[16];
		transform.get( m );

		AffineModel3D model = new AffineModel3D();
		model.set( m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11] );

		return model;
	}

	public static RigidModel3D getRigidModel3D( Transform3D transform )
	{
		final double[] m = new double[16];
		transform.get( m );

		RigidModel3D model = new RigidModel3D();
		model.set( m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8], m[9], m[10], m[11] );

		return model;
	}

	public static Matrix4d getMatrix4d( final AffineModel3D model )
	{
		final Matrix4d matrix = new Matrix4d();

		final double[] m = new double[ 12 ];
		model.getMatrix( m );

		matrix.m00 = m[ 0 ];
		matrix.m01 = m[ 1 ];
		matrix.m02 = m[ 2 ];
		matrix.m03 = m[ 3 ];
		matrix.m10 = m[ 4 ];
		matrix.m11 = m[ 5 ];
		matrix.m12 = m[ 6 ];
		matrix.m13 = m[ 7 ];
		matrix.m20 = m[ 8 ];
		matrix.m21 = m[ 9 ];
		matrix.m22 = m[ 10 ];
		matrix.m23 = m[ 11 ];
		matrix.m30 = 0;
		matrix.m31 = 0;
		matrix.m32 = 0;
		matrix.m33 = 1;

		return matrix;
	}

	public static Matrix4d getMatrix4d( final RigidModel3D model )
	{
		final Matrix4d matrix = new Matrix4d();

		final double[] m = new double[ 12 ];
		model.getMatrix( m );

		matrix.m00 = m[ 0 ];
		matrix.m01 = m[ 1 ];
		matrix.m02 = m[ 2 ];
		matrix.m03 = m[ 3 ];
		matrix.m10 = m[ 4 ];
		matrix.m11 = m[ 5 ];
		matrix.m12 = m[ 6 ];
		matrix.m13 = m[ 7 ];
		matrix.m20 = m[ 8 ];
		matrix.m21 = m[ 9 ];
		matrix.m22 = m[ 10 ];
		matrix.m23 = m[ 11 ];
		matrix.m30 = 0;
		matrix.m31 = 0;
		matrix.m32 = 0;
		matrix.m33 = 1;

		return matrix;
	}

	public static String printAffine3D( final Affine3D< ? > model )
	{
		final double[][] m = new double[ 3 ][ 4 ];
		model.toMatrix( m );
		
		return
			"(" + f.format( m[0][0] ) + ", " + f.format( m[0][1] ) + ", " + f.format( m[0][2] ) + ", " + f.format( m[0][3] ) + "), " +
			"(" + f.format( m[1][0] ) + ", " + f.format( m[1][1] ) + ", " + f.format( m[1][2] ) + ", " + f.format( m[1][3] ) + "), " +
			"(" + f.format( m[2][0] ) + ", " + f.format( m[2][1] ) + ", " + f.format( m[2][2] ) + ", " + f.format( m[2][3] ) + ")";
	}

	public static String printAffine3D( final AffineGet model )
	{
		final double[] m = model.getRowPackedCopy();

		return
			"(" + f.format( m[ 0 ] ) + ", " + f.format( m[ 1 ] ) + ", " + f.format( m[ 2 ] ) + ", " + f.format( m[ 3 ] ) + "), " +
			"(" + f.format( m[ 4 ] ) + ", " + f.format( m[ 5 ] ) + ", " + f.format( m[ 6 ] ) + ", " + f.format( m[ 7 ] ) + "), " +
			"(" + f.format( m[ 8 ] ) + ", " + f.format( m[ 9 ] ) + ", " + f.format( m[ 10 ] )+ ", " + f.format( m[ 11 ] )+ ")";
	}

	public static AffineModel3D getModel( final AffineGet affine )
	{
		final double[] m = affine.getRowPackedCopy();
		final AffineModel3D model = new AffineModel3D();
		model.set( m[ 0 ], m[ 1 ], m[ 2 ], m[ 3 ], m[ 4 ], m[ 5 ], m[ 6 ], m[ 7 ], m[ 8 ], m[ 9 ], m[ 10 ], m[ 11 ] );
		return model;
	}

	/**
	 * 
	 * @param size - size of view which is used to map back
	 * @param mapBackViewRegistration - the registration model of this view before computing the new registration
	 * @param computedModel - the new model
	 * @param mapBackModel - which model to use to map back (e.g. rigid, translation)
	 * @return the transformation to map back, pre-concatenate this to all views that are registered
	 */
	public static AffineTransform3D computeMapBackModel(
			final Dimensions size,
			final AffineTransform3D mapBackViewRegistration,
			final AbstractModel< ? > computedModel,
			final Model< ? > mapBackModel )
	{
		if ( mapBackModel.getMinNumMatches() > 4 )
		{
			IOFunctions.println( "Cannot map back using a model that needs more than 4 points: " + mapBackModel.getClass().getSimpleName() );

			return null;
		}
		else
		{
			long w = size.dimension( 0 );
			long h = size.dimension( 1 );

			// the top 4 corner points of the stack
			final double[][] p = new double[][]{
					{ 0, 0, 0 },
					{ w, 0, 0 },
					{ 0, h, 0 },
					{ w, h, 0 } };

			// original coordinates == pa (from mapBackViewRegistration)
			final double[][] pa = new double[ 4 ][ 3 ];

			// map coordinates to the actual input coordinates
			for ( int i = 0; i < p.length; ++i )
				mapBackViewRegistration.apply( p[ i ], pa[ i ] );

			// transformed coordinates == pb (from mapBackViewRegistration+computedModel)
			final double[][] pb = new double[ 4 ][ 3 ];

			for ( int i = 0; i < p.length; ++i )
				pb[ i ] = computedModel.apply( pa[ i ] );

			// compute the model that maps pb >> pa
			try
			{
				final ArrayList< PointMatch > pm = new ArrayList< PointMatch >();
				
				for ( int i = 0; i < p.length; ++i )
					pm.add( new PointMatch( new Point( pb[ i ] ), new Point( pa[ i ] ) ) );
				
				mapBackModel.fit( pm );
			} catch ( Exception e )
			{
				IOFunctions.println( "Could not compute model for mapping back: " + e );
				e.printStackTrace();
				return null;
			}

			final AffineTransform3D mapBack = new AffineTransform3D();
			final double[][] m = new double[ 3 ][ 4 ];
			((Affine3D<?>)mapBackModel).toMatrix( m );
			
			mapBack.set( m[0][0], m[0][1], m[0][2], + m[0][3],
						m[1][0], m[1][1], m[1][2], m[1][3], 
						m[2][0], m[2][1], m[2][2], m[2][3] );

			return mapBack;
		}
	}

	public static void filterForOverlappingInterestPoints(
			final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints,
			final Collection< ? extends Group< ViewId > > groups,
			final Map< ViewId, ViewRegistration > registrations,
			final Map< ViewId, ViewDescription > viewDescriptions )
	{
		for ( final Entry< ViewId, HashMap< String, Collection< InterestPoint > > > element: interestpoints.entrySet() )
		{
			final ViewId viewId = element.getKey();

			for ( final Entry< String, Collection< InterestPoint > > subElement : element.getValue().entrySet() )
			{
				final List< InterestPoint > points = new ArrayList<>( subElement.getValue() );
				final List< InterestPoint > overlappingPoints = new ArrayList<>();

				// for each pair (if it's not part of a group), test
				// if there are any points that currently overlap with another view
	A:			for ( final ViewId otherViewId : interestpoints.keySet() )
				{
					// if it's the same view continue
					if ( otherViewId.equals( viewId ) )
						continue;

					// if they are part of the same group, continue
					for ( final Group< ViewId > group : groups )
						if ( group.contains( viewId ) && group.contains( otherViewId ) )
							continue A;

					// use the inverse affine transform of the other view
					final AffineTransform3D tinv = TransformationTools.getTransform( otherViewId, registrations ).inverse();

					// to map all interestpoints into the bounding box
					final ViewDescription otherVD = viewDescriptions.get( otherViewId );
					final Dimensions dim = otherVD.getViewSetup().getSize();
					final Interval interval = new FinalInterval( dim );

					final int n = tinv.numDimensions();
					final RealPoint p = new RealPoint( n );

					// and check if they do intersect
					for ( int i = points.size() - 1; i >= 0; --i )
					{
						final InterestPoint ip = points.get( i );
						ip.localize( p );
						tinv.apply(p, p);
						if ( Intervals.contains( interval , p ) )
						{
							overlappingPoints.add( ip );
							points.remove( i );
						}
					}
				}

				// replace the list
				subElement.setValue( overlappingPoints );
			}
		}
	}

	/* call this method to load interestpoints and apply current transformation */
	public static <V> Map< V, HashMap< String, Collection< InterestPoint > > > getAllTransformedInterestPoints(
			final Collection< ? extends V > viewIds,
			final Map< V, ViewRegistration > registrations,
			final Map< V, ViewInterestPointLists > interestpoints,
			final Map< V, HashMap< String, Double > > labelMap )
	{
		return getAllInterestPoints( viewIds, registrations, interestpoints, labelMap, true );
	}

	/* call this method to load interestpoints and apply current transformation */
	public static <V> Map< V, HashMap< String, Collection< InterestPoint > > > getAllInterestPoints(
			final Collection< ? extends V > viewIds,
			final Map< V, ViewRegistration > registrations,
			final Map< V, ViewInterestPointLists > interestpoints,
			final Map< V, HashMap< String, Double > > labelMap,
			final boolean transform )
	{
		final HashMap< V, HashMap< String, Collection< InterestPoint > > > transformedInterestpoints = new HashMap<>();

		for ( final V viewId : viewIds )
			transformedInterestpoints.put( viewId, getInterestPoints( viewId, registrations, interestpoints, labelMap, transform ) );

		return transformedInterestpoints;
	}

	/* call this method to load interestpoints and apply current transformation if necessary */
	public static <V> HashMap< String, Collection< InterestPoint > > getInterestPoints(
			final V viewId,
			final Map< V, ViewRegistration > registrations,
			final Map< V, ViewInterestPointLists > interestpoints,
			final Map< V, HashMap< String, Double > > labelMap,
			final boolean transform )
	{
		final HashMap< String, Collection< InterestPoint > > collections = new HashMap<>();

		labelMap.get( viewId ).forEach( ( label, weight ) -> {

			final Map< Integer, InterestPoint > mapLocal = interestpoints.get( viewId ).getInterestPointList( label ).getInterestPointsCopy();

			collections.put( label, mapLocal.values() );

			if ( mapLocal.size() == 0 )
			{
				if ( ViewId.class.isInstance( viewId ))
					IOFunctions.println( "WARNING: no interestpoints available for " + Group.pvid( (ViewId)viewId ) + ", label '" + label + "'" );
				else
					IOFunctions.println( "WARNING: no interestpoints available for " + viewId + ", label '" + label + "'" );
			}
		});

		if ( transform )
		{
			final AffineTransform3D t = getTransform( viewId, registrations );
			return applyTransformation( collections, t );
		}
		else
		{
			return collections;
		}
	}

	/* call this method to load interestpoints and apply current transformation */
	public static <V> Collection< InterestPoint > getTransformedCorrespondingInterestPoints(
			final V viewId,
			final Map< V, ViewRegistration > registrations,
			final Map< V, ViewInterestPointLists > interestpoints,
			final Map< V, String > labelMap )
	{
		return getCorrespondingInterestPoints( viewId, registrations, interestpoints, labelMap, true );
	}

	/* call this method to load interestpoints and apply current transformation */
	public static <V> Collection< InterestPoint > getCorrespondingInterestPoints(
			final V viewId,
			final Map< V, ViewRegistration > registrations,
			final Map< V, ViewInterestPointLists > interestpoints,
			final Map< V, String > labelMap,
			final boolean transform )
	{
		final InterestPoints ipList = interestpoints.get( viewId ).getInterestPointList( labelMap.get( viewId ) );
		final Map< Integer, InterestPoint > allPoints = ipList.getInterestPointsCopy();
		final ArrayList< InterestPoint > corrPoints = new ArrayList<>();

		if ( allPoints == null )
		{
			if ( ViewId.class.isInstance( viewId  ))
				IOFunctions.println( "WARNING: no interestpoints could be loaded for " + Group.pvid( (ViewId)viewId ) + ", label '" + labelMap.get( viewId ) + "'" );
			else
				IOFunctions.println( "WARNING: no interestpoints could be loaded for " + viewId + ", label '" + labelMap.get( viewId ) + "'" );

			return new ArrayList<>();
		}

		// keep only those interest points who have correspondences
		final HashSet< Integer > idSet = new HashSet<>();

		for ( final CorrespondingInterestPoints cip : ipList.getCorrespondingInterestPointsCopy() )
			idSet.add( cip.getDetectionId() );

		for ( final InterestPoint ip : allPoints.values() )
			if ( idSet.contains( ip.getId() ) )
				corrPoints.add( ip );

		if ( transform )
		{
			final AffineTransform3D t = getTransform( viewId, registrations );
			return applyTransformation( corrPoints, t );
		}
		else
		{
			return corrPoints;
		}
	}

	public static <V> AffineTransform3D getTransform( final V viewId, final Map< V, ViewRegistration > registrations )
	{
		final ViewRegistration r = registrations.get( viewId );
		r.updateModel();
		return r.getModel();
	}

	public static HashMap< String, Collection< InterestPoint > > applyTransformation( final HashMap< String, Collection< InterestPoint > > lists, final AffineTransform3D m )
	{
		final HashMap< String, Collection< InterestPoint > > transformedLists = new HashMap<>();

		lists.forEach( (label,list) -> transformedLists.put(label, applyTransformation( list, m ) ) );

		return transformedLists;
	}

	public static Collection< InterestPoint > applyTransformation( final Collection< InterestPoint > list, final AffineTransform3D m )
	{
		return list.parallelStream().map( ip ->
		{
			final double[] l = new double[ 3 ];
			m.apply( ip.getL(), l );
			return new InterestPoint( ip.getId(), l );
		}).collect( Collectors.toList() );
	}

	public static <V> void storeTransformation(
			final ViewRegistration vr,
			final V viewId,
			final Tile< ? > tile,
			final AffineGet mapBackModel,
			final String modelDescription )
	{
		final AffineTransform3D t;

		if ( tile != null )
		{
			// TODO: we assume that M is an Affine3D, which is not necessarily true
			t = getAffineTransform( (Affine3D< ? >)tile.getModel() );
		}
		else
		{
			// it might have been removed prior to global optimization because it has no links
			t = new AffineTransform3D();
		}

		if ( mapBackModel != null )
			t.preConcatenate( mapBackModel );

		final ViewTransform vt = new ViewTransformAffine( modelDescription, t );
		vr.preconcatenateTransform( vt );
		vr.updateModel();
	}

	public static AffineTransform3D getAffineTransform( final Affine3D< ? > model )
	{
		final double[][] m = new double[ 3 ][ 4 ];
		model.toMatrix( m );

		final AffineTransform3D t = new AffineTransform3D();
		t.set( m[0][0], m[0][1], m[0][2], m[0][3],
			   m[1][0], m[1][1], m[1][2], m[1][3],
			   m[2][0], m[2][1], m[2][2], m[2][3] );

		return t;
	}

	public static boolean affineTransformsEqual( final AffineTransform3D tA, final AffineTransform3D tB )
	{
		if ( tA == tB )
			return true;
		else
		{
			final double[] a = tA.getRowPackedCopy();
			final double[] b = tB.getRowPackedCopy();

			for ( int i = 0; i < a.length; ++i )
				if ( a[ i ] != b[ i ] )
					return false;

			return true;
		}
	}

	public static double getAverageAnisotropyFactor( final SpimData spimData, final Collection< ? extends ViewId > views )
	{
		final SequenceDescription seq = spimData.getSequenceDescription();

		double avgFactor = 0;
		int count = 0;

		for ( final ViewId vd : views )
		{
			final ViewSetup vs = seq.getViewSetups().get( vd.getViewSetupId() );

			final VoxelDimensions vx = vs.getVoxelSize();

			if ( vx != null )
			{
				final double x = vx.dimension( 0 );
				final double y = vx.dimension( 1 );
				final double z = vx.dimension( 2 );

				avgFactor += z / (( x + y ) / 2 );
				++count;

				if ( x != y )
					IOFunctions.println( "Warning: Voxelsize in x and y is different.");
			}
		}

		if ( count > 0 )
			avgFactor /= (double)count;
		else
			avgFactor = 1.0;

		return avgFactor;
	}

	public static double[][] unitCube()
	{
		return new double[][]{
			{ 0, 0, 0 },
			{ 0, 0, 1 },
			{ 0, 1, 0 },
			{ 0, 1, 1 },
			{ 1, 0, 0 },
			{ 1, 0, 1 },
			{ 1, 1, 0 },
			{ 1, 1, 1 }};
	}

	public static double[][] cubeFor( final RealInterval r )
	{
		if ( r.numDimensions() == 1 )
		{
			return new double[][]{
				{ r.realMin( 0 ) },
				{ r.realMax( 0 ) } };
		}
		else if ( r.numDimensions() == 2 )
		{
			return new double[][]{
				{ r.realMin( 0 ), r.realMin( 1 ) },
				{ r.realMin( 0 ), r.realMax( 1 ) },
				{ r.realMax( 0 ), r.realMin( 1 ) },
				{ r.realMax( 0 ), r.realMax( 1 ) } };
		}
		else if ( r.numDimensions() == 3 )
		{
			return new double[][]{
				{ r.realMin( 0 ), r.realMin( 1 ), r.realMin( 2 ) },
				{ r.realMin( 0 ), r.realMin( 1 ), r.realMax( 2 ) },
				{ r.realMin( 0 ), r.realMax( 1 ), r.realMin( 2 ) },
				{ r.realMin( 0 ), r.realMax( 1 ), r.realMax( 2 ) },
				{ r.realMax( 0 ), r.realMin( 1 ), r.realMin( 2 ) },
				{ r.realMax( 0 ), r.realMin( 1 ), r.realMax( 2 ) },
				{ r.realMax( 0 ), r.realMax( 1 ), r.realMin( 2 ) },
				{ r.realMax( 0 ), r.realMax( 1 ), r.realMax( 2 ) }};
		}
		else
		{
			throw new RuntimeException( "TransformationTools.cubeFor( r ): dimensionality " + r.numDimensions() + " not supported." );
		}
	}

	/**
	 * get String representation of RealInterval TODO: PR to ImgLib2 ({@link Util}) ?
	 * @param interval the interval
	 * @return String representation
	 */
	public static String printRealInterval( final RealInterval interval )
	{
		String out = "(Interval empty)";

		if ( interval == null || interval.numDimensions() == 0 )
			return out;

		out = "[" + interval.realMin( 0 );

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + interval.realMin( i );

		out += "] -> [" + interval.realMax( 0 );

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + interval.realMax( i );

		out += "], dimensions (" + (interval.realMax( 0 ) - interval.realMin( 0 ));

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + (interval.realMax( i ) - interval.realMin( i ));

		out += ")";

		return out;
	}
}
