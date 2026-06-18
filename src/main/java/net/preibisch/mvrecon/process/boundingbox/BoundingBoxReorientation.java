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
package net.preibisch.mvrecon.process.boundingbox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RealLocalizable;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.vecmath.AxisAngle4d;
import net.preibisch.mvrecon.vecmath.Matrix4d;
import net.preibisch.mvrecon.vecmath.Point3d;
import net.preibisch.mvrecon.vecmath.Transform3D;
import net.preibisch.mvrecon.vecmath.Vector3d;

public class BoundingBoxReorientation implements BoundingBoxEstimation
{
	public static String reorientationDescription = "Reorientation to minimize bounding box";

	final SpimData2 spimData;
	final ArrayList< RealLocalizable > points;
	final boolean testRotations;
	final List< ViewId > viewIdsToApply;
	final double percent;

	/**
	 * 
	 * @param spimData - the spimdata object
	 * @param viewIdsForEstimation - which ViewIds to use for BoundingBox determination
	 * @param points - a Map linking a list of locations of points to each ViewId used for estimation
	 * @param percent - extend/shrink the computed bounding box by the specified percentage (e.g. 10%, -%5, etc.)
	 * @param testRotations - if free rotations should be allowed in order to reorient the sample and minimize the bounding box.
	 * Warning: this will preconcatenate another rigid transform to all views listed in viewIdsToApply
	 * @param viewIdsToApply - only used if testRotations==true, these are the views the identified rotation minimizing the bounding box size will be applied to
	 */
	public BoundingBoxReorientation(
			final SpimData2 spimData,
			final List< ViewId > viewIdsForEstimation,
			final HashMap< ViewId, ? extends Collection< ? extends RealLocalizable > > points,
			final double percent,
			final boolean testRotations,
			final List< ViewId > viewIdsToApply )
	{
		this(
				spimData,
				extractPoints(
						points,
						filterMissing( spimData, viewIdsForEstimation ),
						spimData ),
				percent,
				testRotations,
				viewIdsToApply );
	}

	public BoundingBoxReorientation(
			final SpimData2 spimData,
			final List< ViewId > viewIdsForEstimation,
			final String label,
			final boolean useCorresponding,
			final double percent,
			final boolean testRotations,
			final List< ViewId > viewIdsToApply )
	{
		this(
				spimData,
				extractPoints(
						label,
						useCorresponding,
						true,
						filterMissing( spimData, viewIdsForEstimation ),
						spimData ),
				percent,
				testRotations,
				viewIdsToApply );
	}

	public BoundingBoxReorientation(
			final SpimData2 spimData,
			final ArrayList< RealLocalizable > points,
			final double percent,
			final boolean testRotations,
			final List< ViewId > viewIdsToApply )
	{
		this.spimData = spimData;
		this.percent = percent;
		this.testRotations = testRotations;
		this.viewIdsToApply = filterMissing( spimData, viewIdsToApply );
		this.points = points;
	}

	private static < V extends ViewId > ArrayList< V > filterMissing( final SpimData2 spimData, final Collection< V > views )
	{
		final ArrayList< V > filteredViews = new ArrayList<>();
		filteredViews.addAll( views );
		SpimData2.filterMissingViews( spimData, filteredViews );
		return filteredViews;
	}

	@Override
	public BoundingBox estimate( final String title )
	{
		final double[] minF, maxF;

		if ( !this.testRotations )
		{
			final Pair< double[], double[] > minmax = determineSizeSimple();

			if ( minmax == null )
				return null;

			minF = minmax.getA();
			maxF = minmax.getB();
		}
		else
		{
			final Pair< AffineTransform3D, double[] > pair = determineOptimalBoundingBox();

			if ( pair == null )
				return null;

			//
			// set the registration for all or some of the views
			//
			IOFunctions.println( "Final transformation model: " + pair.getA() );

			for ( final ViewId viewId : viewIdsToApply )
			{
				final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( viewId );
				
				if ( !vd.isPresent() )
					continue;

				// get the registration
				final ViewRegistration r = spimData.getViewRegistrations().getViewRegistration( viewId );
				r.preconcatenateTransform( new ViewTransformAffine( reorientationDescription, pair.getA() ) );
				r.updateModel();
			}

			minF = new double[]{ pair.getB()[ 0 ], pair.getB()[ 1 ], pair.getB()[ 2 ] };
			maxF = new double[]{ pair.getB()[ 3 ], pair.getB()[ 4 ], pair.getB()[ 5 ] };
		}

		IOFunctions.println( "Min (without addition): " + Util.printCoordinates( minF ) );
		IOFunctions.println( "Max (without addition): " + Util.printCoordinates( maxF ) );

		final int[] min = new int[ 3 ];
		final int[] max = new int[ 3 ];

		double addX = (maxF[ 0 ] - minF[ 0 ]) * ( percent/100.0 ) / 2;
		double addY = (maxF[ 1 ] - minF[ 1 ]) * ( percent/100.0 ) / 2;
		double addZ = (maxF[ 2 ] - minF[ 2 ]) * ( percent/100.0 ) / 2;

		final double add = Math.max( addX, Math.max( addY, addZ ) );

		min[ 0 ] = (int)Math.round( minF[ 0 ] - add );
		min[ 1 ] = (int)Math.round( minF[ 1 ] - add );
		min[ 2 ] = (int)Math.round( minF[ 2 ] - add );

		max[ 0 ] = (int)Math.round( maxF[ 0 ] + add );
		max[ 1 ] = (int)Math.round( maxF[ 1 ] + add );
		max[ 2 ] = (int)Math.round( maxF[ 2 ] + add );

		IOFunctions.println( "Min (with addition): " + Util.printCoordinates( min ) );
		IOFunctions.println( "Max (with addition): " + Util.printCoordinates( max ) );

		return new BoundingBox( title, min, max );
	}

	protected Pair< AffineTransform3D, double[] > determineOptimalBoundingBox()
	{
		if ( points.size() < 1 )
		{
			IOFunctions.println( "At least one point is required. Stopping" );
			return null;
		}

		// identify most distant points
		RealLocalizable p1 = points.get( 0 );
		RealLocalizable p2 = points.get( 1 );
		double maxDist = squareDistance(
				p1.getDoublePosition( 0 ),
				p1.getDoublePosition( 1 ),
				p1.getDoublePosition( 2 ),
				p2.getDoublePosition( 0 ),
				p2.getDoublePosition( 1 ),
				p2.getDoublePosition( 2 ) );

		for ( int i = 0; i < points.size() - 1; ++i )
			for ( int j = i + 1; j < points.size(); ++j )
			{
				final RealLocalizable a = points.get( i );
				final RealLocalizable b = points.get( j );

				final double d = squareDistance(
						a.getDoublePosition( 0 ),
						a.getDoublePosition( 1 ),
						a.getDoublePosition( 2 ),
						b.getDoublePosition( 0 ),
						b.getDoublePosition( 1 ),
						b.getDoublePosition( 2 ) );

				if ( d > maxDist )
				{
					maxDist = d;
					if ( a.getDoublePosition( 0 ) <= b.getDoublePosition( 0 ) )
					{
						p1 = a;
						p2 = b;
					}
					else
					{
						p1 = b;
						p2 = a;
					}
				}
			}

		final Vector3d sv = new Vector3d(
				p2.getDoublePosition( 0 ) - p1.getDoublePosition( 0 ),
				p2.getDoublePosition( 1 ) - p1.getDoublePosition( 1 ),
				p2.getDoublePosition( 2 ) - p1.getDoublePosition( 2 ) );
		sv.normalize();

		IOFunctions.println(
				"Maximum distance: " + Math.sqrt( maxDist ) + "px between points " + Util.printCoordinates( p1 ) +
				" and " + Util.printCoordinates( p2 ) + ", vector=" + sv + ", volume=" +
				testAxis( sv, points ).getA() / (1024*1024) + "MiPixels." );

		final double[] vectorStep = new double[]{ 0.4, 0.2, 0.1, 0.05, 0.025, 0.01, 0.005, 0.001 };

		double minVolume = Double.MAX_VALUE;
		double[] minBoundingBox = null;

		for ( int stepIndex = 0; stepIndex < vectorStep.length; ++stepIndex )
		{
			final double step = vectorStep[ stepIndex ];

			// the best search vector found so far on this scale
			final Vector3d bestSV = new Vector3d( sv );

			for ( int zi = -1; zi <= 1; ++zi )
				for ( int yi = -1; yi <= 1; ++yi )
					for ( int xi = -1; xi <= 1; ++xi )
					{
						// compute the test vector
						final Vector3d v = new Vector3d(
								sv.x + xi * step,
								sv.y + yi * step,
								sv.z + zi * step );

						v.normalize();

						final Pair< Double, double[] > volume = testAxis( v, points );

						if ( volume.getA() < minVolume )
						{
							minVolume = volume.getA();
							minBoundingBox = volume.getB();
							bestSV.set( v );

							IOFunctions.println( "Scale: " + step + " --- Min Volume: " + minVolume / (1024*1024) + "MiPixels, vector=" + bestSV );
						}
					}

			// update the search vector to the best solution from this scale
			sv.set( bestSV );
		}

		// final mapping v onto the x axis
		final Vector3d xAxis = new Vector3d( 1, 0, 0 );
		final Matrix4d m = new Matrix4d();
		getRotation( sv, xAxis ).get( m );

		final AffineTransform3D a = new AffineTransform3D();
		a.set( m.m00, m.m01, m.m02, m.m03, m.m10, m.m11, m.m12, m.m13, m.m20, m.m21, m.m22, m.m23 );

		return new ValuePair< AffineTransform3D, double[] >( a, minBoundingBox );
	}

	

	/**
	 * Test one major axis for the minimal bounding box volume required
	 * 
	 * @param v - normalized vector
	 * @param points - all points
	 * @return - the volume and minX, minY, minZ, maxX, maxY, maxZ as Pair
	 */
	protected Pair< Double, double[] > testAxis( final Vector3d v, final List< RealLocalizable > points )
	{
		// mapping v onto the x axis
		final Vector3d xAxis = new Vector3d( 1, 0, 0 );
		final Transform3D t = getRotation( v, xAxis );

		final Point3d tmp = new Point3d();

		double minX = Double.MAX_VALUE;
		double minY = Double.MAX_VALUE;
		double minZ = Double.MAX_VALUE;

		double maxX = -Double.MAX_VALUE;
		double maxY = -Double.MAX_VALUE;
		double maxZ = -Double.MAX_VALUE;

		for ( final RealLocalizable p : points )
		{
			// transform onto the x-axis
			tmp.set( p.getDoublePosition( 0 ), p.getDoublePosition( 1 ), p.getDoublePosition( 2 ) );
			t.transform( tmp );

			minX = Math.min( minX, tmp.x );
			minY = Math.min( minY, tmp.y );
			minZ = Math.min( minZ, tmp.z );

			maxX = Math.max( maxX, tmp.x );
			maxY = Math.max( maxY, tmp.y );
			maxZ = Math.max( maxZ, tmp.z );
		}

		return new ValuePair< Double, double[] >(
				( maxX - minX ) * ( maxY - minY ) * ( maxZ - minZ ),
				new double[]{ minX, minY, minZ, maxX, maxY, maxZ } );
	}

	/**
	 * Computes a Transform3D that will rotate vector v0 into the direction of vector v1.
	 * Note: vectors MUST be normalized for this to work!
	 *
	 * @param v0 - vector 0
	 * @param v1 - vector 1
	 * @return the transform
	 */
	public static Transform3D getRotation( final Vector3d v0, final Vector3d v1 )
	{
		// the rotation axis is defined by the cross product
		final Vector3d rotAxis = new Vector3d();
		rotAxis.cross( v0, v1 );
		rotAxis.normalize();

		// if the cross product returns NaN, the vectors already point in the same direction,
		// so we return the identity transform
		if ( Double.isNaN( rotAxis.x ) )
			return new Transform3D();

		// the rotation angle is defined by the dot product (if normalized)
		final double angle = v0.dot( v1 );

		// Do an axis/angle 3d transformation
		final Transform3D t = new Transform3D();
		t.set( new AxisAngle4d( rotAxis, Math.acos( angle ) ) );

		return t;
	}

	final static public double squareDistance( final double p1x, final double p1y, final double p1z, final double p2x, final double p2y, final double p2z )
	{
		final double dx = p1x - p2x;
		final double dy = p1y - p2y;
		final double dz = p1z - p2z;
		
		return dx*dx + dy*dy + dz*dz;
	}

	protected Pair< double[], double[] > determineSizeSimple()
	{
		if ( points.size() < 1 )
		{
			IOFunctions.println( "At least one point is required. Stopping" );
			return null;
		}

		final int n = points.get( 0 ).numDimensions();

		final double[] min = new double[ n ];
		final double[] max = new double[ n ];

		for ( int d = 0; d < n; ++d )
			min[ d ] = max[ d ] = points.get( 0 ).getDoublePosition( d );

		for ( final RealLocalizable p : points )
			for ( int d = 0; d < n; ++d )
			{
				min[ d ] = Math.min( min[ d ], p.getDoublePosition( d ) );
				max[ d ] = Math.max( max[ d ], p.getDoublePosition( d ) );
			}

		IOFunctions.println( "Min (direct): " + Util.printCoordinates( min ) );
		IOFunctions.println( "Max (direct): " + Util.printCoordinates( max ) );

		return new ValuePair< double[], double[] >( min, max );
	}

	/**
	 * Gets point locations in local or global coordinates
	 * 
	 * @param label - which label to use
	 * @param useCorresponding - use only corresponding points?
	 * @param transform - transform the points? Yes for Bounding box, no for PSF extraction
	 * @param viewIds - which viewIds to process
	 * @param data - the SpimData object
	 * @return - a list of RealLocalizable objects
	 */
	public static ArrayList< RealLocalizable > extractPoints(
			final String label,
			final boolean useCorresponding,
			final boolean transform,
			final List< ViewId > viewIds,
			final SpimData2 data )
	{
		final Map< ViewId, ViewRegistration > registrations = new HashMap<>();
		final Map< ViewId, ViewInterestPointLists > interestpoints = new HashMap<>();
		final Map< ViewId, String > labelMap1 = new HashMap<>();
		final Map< ViewId, HashMap< String, Double > > labelMap2 = new HashMap<>();

		for ( final ViewId viewId : viewIds )
		{
			final HashMap< String, Double > map = new HashMap<>();
			map.put(label, 1.0 );
			labelMap2.put( viewId, map );

			labelMap1.put( viewId, label );
			registrations.put( viewId, data.getViewRegistrations().getViewRegistration( viewId ) );
			interestpoints.put( viewId, data.getViewInterestPoints().getViewInterestPointLists( viewId ) );
		}

		final ArrayList< RealLocalizable > points = new ArrayList<>();

		for ( final ViewId viewId : viewIds )
		{
			if ( useCorresponding )
			{
				points.addAll( TransformationTools.getCorrespondingInterestPoints(
					viewId,
					registrations,
					interestpoints,
					labelMap1,
					transform ) );
			}
			else
			{
				points.addAll( TransformationTools.getInterestPoints(
					viewId,
					registrations,
					interestpoints,
					labelMap2,
					transform ).get( label ) );
			}
		}

		return points;
	}

	public static ArrayList< RealLocalizable > extractPoints(
			final HashMap< ViewId, ? extends Collection< ? extends RealLocalizable > > pointsIn,
			final List< ViewId > viewIdsForEstimation,
			final SpimData2 spimData )
	{
		final ArrayList< RealLocalizable > points = new ArrayList<>();

		for ( final ViewId viewId : filterMissing( spimData, viewIdsForEstimation ) )
			points.addAll( pointsIn.get( viewId ) );

		return points;
	}
}
