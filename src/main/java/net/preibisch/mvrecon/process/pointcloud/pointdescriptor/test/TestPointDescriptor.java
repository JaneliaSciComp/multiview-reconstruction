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
package net.preibisch.mvrecon.process.pointcloud.pointdescriptor.test;

//import ij3d.Content;
//import ij3d.Image3DUniverse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

import org.apache.commons.math3.transform.TransformUtils;

import mpicbg.models.AffineModel3D;
import mpicbg.models.Point;
import net.imglib2.KDTree;
import net.imglib2.neighborsearch.KNearestNeighborSearch;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.LinkedPoint;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.LocalCoordinateSystemPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.ModelPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.Matcher;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SimpleMatcher;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.model.TranslationInvariantModel;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.model.TranslationInvariantRigidModel3D;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.similarity.SimilarityMeasure;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.similarity.SquareDistance;
//import customnode.CustomLineMesh;
import net.preibisch.mvrecon.vecmath.Matrix3f;
import net.preibisch.mvrecon.vecmath.Point3f;
import net.preibisch.mvrecon.vecmath.Quat4f;
import net.preibisch.mvrecon.vecmath.Transform3D;
import net.preibisch.mvrecon.vecmath.Vector3f;

public class TestPointDescriptor
{
	protected static void add( final Point p1, final Point p2 )
	{
		final double[] l1 = p1.getL();
		final double[] w1 = p1.getW();
		final double[] l2 = p2.getL();
		final double[] w2 = p2.getW();
		
		for ( int d = 0; d < l1.length; ++d )
		{
			l1[ d ] += l2[ d ];
			w1[ d ] += w2[ d ];
		}
	}
	
	protected void addSimplePoints( final ArrayList<Point> points1, final ArrayList<Point> points2 )
	{
		points1.add( new Point( new double[]{ 0, 0, 0 } ) );
		points1.add( new Point( new double[]{ 0, 0, 1.1f } ) );
		points1.add( new Point( new double[]{ 0, 1.2f, 0 } ) );
		points1.add( new Point( new double[]{ 1.3f, 0, 0 } ) );
		points1.add( new Point( new double[]{ 1.3f, 1.4f, 0 } ) );

		final Point offset = new Point( new double[]{ 1, 2, 3 } );
		
		for ( final Iterator<Point> i = points1.iterator(); i.hasNext(); )
		{
			final Point p2 = new Point( i.next().getL().clone() );
			add( p2, offset );
			points2.add( p2 );
		}

		points1.add( new Point( new double[]{ 0.1f, 0.1f ,0.1f } ) );		
	}
	
	protected void addAdvancedPoints( final ArrayList<Point> points1, final ArrayList<Point> points2 )
	{
		final int commonPoints = 10;
		final int randomPoints = 100;
		
		final int offsetX = 5;
		final int offsetY = -10;
		final int offsetZ = 7;
		
		Random rnd = new Random(325235325L);

		for ( int i = 0; i < commonPoints; ++i )
		{
			// all between 5 and 10
			double v1 = rnd.nextDouble()*5 + 5;
			double v2 = rnd.nextDouble()*5 + 5;
			double v3 = rnd.nextDouble()*5 + 5;
			double o1 = (rnd.nextDouble()-0.5f)/10;
			double o2 = (rnd.nextDouble()-0.5f)/10;
			double o3 = (rnd.nextDouble()-0.5f)/10;
						
			final Point p1 = new Point( new double[]{ v1 + o1, v2 + o2, v3 + o3 } );
			final Point p2 = new Point( new double[]{ v1 + offsetX, v2 + offsetY, v3 + offsetZ } );
			
			points1.add( p1 );
			points2.add( p2 );	
		}
		
		for ( int i = 0; i < randomPoints; ++i )		
		{
			double v1 = rnd.nextDouble()*90;
			double v2 = rnd.nextDouble()*90;
			double v3 = rnd.nextDouble()*90;

			final Point p1 = new Point( new double[]{ v1, v2, v3 } );

			v1 = rnd.nextDouble()*90;
			v2 = rnd.nextDouble()*90;
			v3 = rnd.nextDouble()*90;

			final Point p2 = new Point( new double[]{ v1, v2, v3 } );

			points1.add( p1 );
			points2.add( p2 );				
		}

		for ( int i = 0; i < randomPoints/10; ++i )		
		{
			double v1 = rnd.nextDouble()*5;
			double v2 = rnd.nextDouble()*5;
			double v3 = rnd.nextDouble()*5;

			final Point p1 = new Point( new double[]{ v1, v2, v3 } );

			v1 = rnd.nextDouble()*5;
			v2 = rnd.nextDouble()*5;
			v3 = rnd.nextDouble()*5;

			final Point p2 = new Point( new double[]{ v1, v2, v3 } );

			points1.add( p1 );
			points2.add( p2 );				
		}		
	}
	
	protected void applyTransform( final ArrayList<Point> points )
	{
        final Transform3D trans = new Transform3D();
        trans.rotX( Math.toRadians( 30 ) );
        
        final AffineModel3D model = TransformationTools.getAffineModel3D( trans );
        
        for ( final Point p : points )
        {
        	model.apply( p.getL() );
        	model.apply( p.getW() );
        }        			
	}
	
	
	public static <P extends Point> ArrayList< VirtualPointNode< P > > createVirtualNodeList( final ArrayList<P> points )
	{
		final ArrayList< VirtualPointNode< P > > nodeList = new ArrayList< VirtualPointNode< P > >();
		
		for ( final P point : points )
			nodeList.add( new VirtualPointNode<P>( point ) );
		
		return nodeList;
	}
	
	public static < P extends Point > ArrayList< ModelPointDescriptor< P > > createModelPointDescriptors( final KDTree< VirtualPointNode< P > > tree, 
	                                                                                               final ArrayList< VirtualPointNode< P > > basisPoints, 
	                                                                                               final int numNeighbors, 
	                                                                                               final TranslationInvariantModel<?> model, 
	                                                                                               final Matcher matcher, 
	                                                                                               final SimilarityMeasure similarityMeasure )
	{
		final KNearestNeighborSearch< VirtualPointNode< P > > nnsearch = new KNearestNeighborSearchOnKDTree< VirtualPointNode< P > >( tree, numNeighbors + 1 );
		final ArrayList< ModelPointDescriptor< P > > descriptors = new ArrayList< ModelPointDescriptor< P > > ( );
		
		for ( final VirtualPointNode< P > p : basisPoints )
		{
			final ArrayList< P > neighbors = new ArrayList< P >();
			nnsearch.search( p );

			// the first hit is always the point itself
			for ( int n = 1; n <= numNeighbors + 1; ++n )
				neighbors.add( nnsearch.getSampler( n ).get().getPoint() );
				//neighbors.add( neighborList[ n ].getPoint() );
			
			try
			{
				descriptors.add( new ModelPointDescriptor<P>( p.getPoint(), neighbors, model, similarityMeasure, matcher ) );
			}
			catch ( NoSuitablePointsException e )
			{
				e.printStackTrace();
			}
		}
			
		return descriptors;
	}

	public static < P extends Point > ArrayList< LocalCoordinateSystemPointDescriptor< P > > createLocalCoordinateSystemPointDescriptors( 
			final KDTree< VirtualPointNode< P > > tree, 
            final ArrayList< VirtualPointNode< P > > basisPoints, 
            final int numNeighbors,
            final boolean normalize )
	{
		final KNearestNeighborSearch< VirtualPointNode< P > > nnsearch = new KNearestNeighborSearchOnKDTree< VirtualPointNode< P > >( tree, numNeighbors + 1 );
		final ArrayList< LocalCoordinateSystemPointDescriptor< P > > descriptors = new ArrayList< LocalCoordinateSystemPointDescriptor< P > > ( );
		
		for ( final VirtualPointNode< P > p : basisPoints )
		{
			final ArrayList< P > neighbors = new ArrayList< P >();
			nnsearch.search( p );

			// the first hit is always the point itself
			for ( int n = 1; n <= numNeighbors + 1; ++n )
				neighbors.add( nnsearch.getSampler( n ).get().getPoint() );
			
			try
			{
				descriptors.add( new LocalCoordinateSystemPointDescriptor<P>( p.getPoint(), neighbors, normalize ) );
			}
			catch ( NoSuitablePointsException e )
			{
				e.printStackTrace();
			}
		}
		
		return descriptors;
	}
	
	public TestPointDescriptor()
	{
		final ArrayList<Point> points1 = new ArrayList<Point>();
		final ArrayList<Point> points2 = new ArrayList<Point>();
		
		/* add some corresponding points */
		addSimplePoints( points1, points2 );
		//addAdvancedPoints( points1, points2 );
		
		/* rotate one of the pointclouds */
		applyTransform( points2 );
		
		/* create KDTrees */
		final ArrayList< VirtualPointNode< Point > > nodeList1 = createVirtualNodeList( points1 );
		final ArrayList< VirtualPointNode< Point > > nodeList2 = createVirtualNodeList( points2 );
		
		final KDTree< VirtualPointNode< Point > > tree1 = new KDTree< VirtualPointNode< Point > >( nodeList1, nodeList1 );
		final KDTree< VirtualPointNode< Point > > tree2 = new KDTree< VirtualPointNode< Point > >( nodeList2, nodeList2 );
		
		/* extract point descriptors */						
		final int numNeighbors = 4;
		final TranslationInvariantModel<?> model = new TranslationInvariantRigidModel3D();
		final Matcher matcher = new SimpleMatcher( numNeighbors );
		final SimilarityMeasure similarityMeasure = new SquareDistance();
				
		final ArrayList< ModelPointDescriptor< Point > > descriptors1 = 
			createModelPointDescriptors( tree1, nodeList1, numNeighbors, model, matcher, similarityMeasure );
		
		final ArrayList< ModelPointDescriptor< Point > > descriptors2 = 
			createModelPointDescriptors( tree2, nodeList2, numNeighbors, model, matcher, similarityMeasure );
		
		/* compute matching */
		for ( final ModelPointDescriptor< Point > descriptorA : descriptors1 )
		{
			for ( final ModelPointDescriptor< Point > descriptorB : descriptors2 )
			{
				final double difference = descriptorA.descriptorDistance( descriptorB );
				
				//if ( difference < 0.1 )
				{
					System.out.println( "Difference " + descriptorA.getId() + " -> " + descriptorB.getId() + " : " + difference );
					System.out.println( "Position " + Util.printCoordinates( descriptorA.getBasisPoint().getL() ) + " -> " + 
					                    Util.printCoordinates( descriptorB.getBasisPoint().getL() ) + "\n" );
					
				}
			}
		}				
	}
	
	public static void testQuaternions()
	{
        final Quat4f qu = new Quat4f();
        final Matrix3f m = new Matrix3f();
        
        final Transform3D transformationPrior = new Transform3D();
        transformationPrior.rotX( Math.toRadians( 45 ) );

        transformationPrior.get( m );        
        qu.set( m );       
        Vector3f v1 = new Vector3f( qu.getX(), qu.getY(), qu.getZ() );
        v1.normalize();
        System.out.println( "Axis: " + v1  );      
        System.out.println( Math.toDegrees( Math.acos( qu.getW() ) * 2 ) );
        
        final Transform3D trans = new Transform3D();
        trans.rotY( Math.toRadians( 90 ) );
        
        trans.get( m );        
        qu.set( m );       
        Vector3f v2 = new Vector3f( qu.getX(), qu.getY(), qu.getZ() );        
        v2.normalize();
        System.out.println( "Axis: " + v2  );      
        System.out.println( Math.toDegrees( Math.acos( qu.getW() ) * 2 ) );
        
        Vector3f v3 = new Vector3f( 1.1f, 0.1f, 0.25f );
        v3.normalize();
        
       	Point3f p1 = new Point3f( 1, 0, 0 );
       	Point3f p2 = new Point3f( v3 );
              	
       	System.out.println( "Distance to: " + Math.pow( 50, p1.distance( p2 ) ) );
 
        transformationPrior.invert();        
        trans.mul( transformationPrior );                
        
        trans.get( m );        
        qu.set( m );        
        System.out.println( Math.toDegrees( Math.acos( qu.getW() ) * 2 ) );
 	}
	
	public static void testStability( final int numNeighbors, final int numTrueMatches, final int numRandomPoints, final double nTimesBetter, final double stdev, boolean fastMatching, boolean showPoints )
	{
		final ArrayList<Point> truepoints1 = new ArrayList<Point>();
		final ArrayList<Point> falsepoints1 = new ArrayList<Point>();

		final ArrayList<Point> points1 = new ArrayList<Point>();
		final ArrayList<Point> points2 = new ArrayList<Point>();
		
		final Random rnd = new Random( 4353451 );
		
		// ensure contstant points per volume
		final double cubeSize = 326508; //2 * 2 * 2;
		final double pointsPercubePixel = 1;
		
		//
		// Add point descriptors that are easy to find
		//
		final double cubeSizeTrue = ( cubeSize / pointsPercubePixel ) * numTrueMatches;
		final double cubeTrueKantenLength = Math.pow( cubeSizeTrue, 1.0/3.0 );
		
		Point offset = new Point( new double[]{ -cubeTrueKantenLength/2, -cubeTrueKantenLength/2, -cubeTrueKantenLength/2 } );
		//Point offset = new Point( new double[]{ 1.5f*cubeTrueKantenLength, 1.5f*cubeTrueKantenLength, 1.5f*cubeTrueKantenLength } );
		
		for ( int n = 0; n < numTrueMatches; ++n )
		{
			final Point p = new Point( new double[]{ rnd.nextDouble()*cubeTrueKantenLength, rnd.nextDouble()*cubeTrueKantenLength, rnd.nextDouble()*cubeTrueKantenLength } );
			add( p, offset );
			points1.add( p );
			truepoints1.add( p );
			
			final LinkedPoint<Point> p2 = new LinkedPoint<Point>( p.getL().clone(), p );
			
			p2.getL()[ 0 ] += stdev * rnd.nextGaussian();
			p2.getL()[ 1 ] += stdev * rnd.nextGaussian();
			p2.getL()[ 2 ] += 3 * stdev * rnd.nextGaussian();
			points2.add( p2 );
		}
		
		//
		// Add Random Points around the true one's
		//
		final double cubeSizeFalse = ( cubeSize / pointsPercubePixel ) * numRandomPoints + cubeSizeTrue;
		//final double cubeSizeFalse = ( cubeSize / pointsPercubePixel ) * numRandomPoints;
		final double cubeFalseKantenLength = Math.pow( cubeSizeFalse, 1.0/3.0 );
		final double o = -cubeFalseKantenLength/2;
		//final double o = -cubeFalseKantenLength;

		for ( int n = 0; n < numRandomPoints; ++n )
		{
			double l[][] = new double[ 2 ][ 3 ];
			
			for ( int i = 0; i < l.length; ++i )
			{
				double[] li = l[ i ];
				boolean worked = true;
				do
				{
					worked = true;
					li[0] = rnd.nextDouble()*cubeFalseKantenLength + o;
					li[1] = rnd.nextDouble()*cubeFalseKantenLength + o;
					li[2] = rnd.nextDouble()*cubeFalseKantenLength + o;
					
					if ( ( li[0] >= -cubeTrueKantenLength/2 && li[0] < cubeTrueKantenLength/2 ) &&
						 ( li[1] >= -cubeTrueKantenLength/2 && li[1] < cubeTrueKantenLength/2 ) &&
						 ( li[2] >= -cubeTrueKantenLength/2 && li[2] < cubeTrueKantenLength/2 ) ) 
						worked = false;
				}
				while ( !worked );
			}
			
			final Point p1 = new Point( new double[]{ l[ 0 ][ 0 ], l[ 0 ][ 1 ], l[ 0 ][ 2 ] } );
			final Point p2 = new Point( new double[]{ l[ 1 ][ 0 ], l[ 1 ][ 1 ], l[ 1 ][ 2 ] } );
			points1.add( p1 );
			falsepoints1.add( p1 );
			points2.add( p2 );
		}
		
		/*
		if ( showPoints )
		{
			Image3DUniverse univ = VisualizeBeads.initUniverse();
			//final Image3DUniverse univ = new Image3DUniverse( 800, 600 );
			
			Color3f colorTrue = new Color3f( 38f/255f, 140f/255f, 0.1f );
			Color3f colorFalse = new Color3f( 1f, 0.1f, 0.1f );
			float size = .1f;
						
			CustomLineMesh c = new CustomLineMesh( getBoundingBox( -(float)cubeFalseKantenLength/2, (float)cubeFalseKantenLength/2 ), CustomLineMesh.PAIRWISE );
			c.setLineWidth( 2 );
			c.setColor( colorFalse );
			final Content content = univ.addCustomMesh( c, "BoundingBoxFalse" );			
			content.showCoordinateSystem(false);			

			CustomLineMesh c2 = new CustomLineMesh( getBoundingBox( -(float)cubeTrueKantenLength/2, (float)cubeTrueKantenLength/2 ), CustomLineMesh.PAIRWISE );
			//CustomLineMesh c2 = new CustomLineMesh( getBoundingBox( 1.5f*cubeTrueKantenLength, 2.5f*cubeTrueKantenLength ), CustomLineMesh.PAIRWISE );
			c2.setLineWidth( 2 );
			c2.setColor( colorTrue );
			final Content content2 = univ.addCustomMesh( c2, "BoundingBoxTrue" );			
			content2.showCoordinateSystem(false);			

			VisualizationFunctions.drawPoints( univ, truepoints1, new Transform3D(), colorTrue, size+0.05f, 0.1f );
			VisualizationFunctions.drawPoints( univ, falsepoints1, new Transform3D(), colorFalse, size, 0.3f );
			
			//univ.show();
			return;
		}
		*/

		final long time = System.currentTimeMillis();
		
		/* create KDTrees */
		final ArrayList< VirtualPointNode< Point > > nodeList1 = createVirtualNodeList( points1 );
		final ArrayList< VirtualPointNode< Point > > nodeList2 = createVirtualNodeList( points2 );
		
		final KDTree< VirtualPointNode< Point > > tree1 = new KDTree< VirtualPointNode< Point > >( nodeList1, nodeList1 );
		final KDTree< VirtualPointNode< Point > > tree2 = new KDTree< VirtualPointNode< Point > >( nodeList2, nodeList2 );
	
		int detectedRight = 0;
		int detectedWrong = 0;
		
		final boolean foundByNeighbor[] = new boolean[ numTrueMatches ];
		
		for ( int i = 0; i < numTrueMatches; ++i )
			foundByNeighbor[ i ] = false;
		
		if ( fastMatching )
		{
			final ArrayList< LocalCoordinateSystemPointDescriptor< Point > > descriptors1 = 
				createLocalCoordinateSystemPointDescriptors( tree1, nodeList1, numNeighbors, false );
			
			final ArrayList< LocalCoordinateSystemPointDescriptor< Point > > descriptors2 = 
				createLocalCoordinateSystemPointDescriptors( tree2, nodeList2, numNeighbors, false );
			
			// create lookup tree for descriptors2
			final KDTree< LocalCoordinateSystemPointDescriptor< Point > > lookUpTree2 = new KDTree< LocalCoordinateSystemPointDescriptor< Point > >( descriptors2, descriptors2 );
			final KNearestNeighborSearch< LocalCoordinateSystemPointDescriptor< Point > > nnsearch = new KNearestNeighborSearchOnKDTree< LocalCoordinateSystemPointDescriptor< Point > >( lookUpTree2, 2 );
		
			/* compute matching */
			for ( final LocalCoordinateSystemPointDescriptor< Point > descriptorA : descriptors1 )
			{
				nnsearch.search( descriptorA );

				double best = descriptorA.descriptorDistance( nnsearch.getSampler( 0 ).get() );
				double secondBest = descriptorA.descriptorDistance( nnsearch.getSampler( 1 ).get() );

				if ( best * nTimesBetter < secondBest )
				{
					if ( isCorrect( descriptorA.getBasisPoint(), nnsearch.getSampler( 0 ).get().getBasisPoint() ) )
					{
						++detectedRight;
						ArrayList< Point > neighbors = descriptorA.getOrderedNearestNeighboringPoints();
						
						for ( Point p : neighbors )
							for ( int i = 0; i < numTrueMatches; ++i )
								if ( isCorrect(p, points1.get( i )) )
									foundByNeighbor[ i ] = true;
					}
					else
						++detectedWrong;
				}				
			}			
			
		}
		else
		{
			/* extract point descriptors */						
			final TranslationInvariantModel<?> model = new TranslationInvariantRigidModel3D();
			final Matcher matcher = new SimpleMatcher( numNeighbors );
			//final Matcher matcher = new SubsetMatcher( numNeighbors, numNeighbors+2 ); 
			final SimilarityMeasure similarityMeasure = new SquareDistance();
					
			final ArrayList< ModelPointDescriptor< Point > > descriptors1 = 
				createModelPointDescriptors( tree1, nodeList1, numNeighbors, model, matcher, similarityMeasure );
			
			final ArrayList< ModelPointDescriptor< Point > > descriptors2 = 
				createModelPointDescriptors( tree2, nodeList2, numNeighbors, model, matcher, similarityMeasure );
		
			/* compute matching */
			for ( final ModelPointDescriptor< Point > descriptorA : descriptors1 )
			{
				double best = Double.MAX_VALUE;
				double secondBest = Double.MAX_VALUE;
				
				boolean correct = true;
				
				for ( final ModelPointDescriptor< Point > descriptorB : descriptors2 )
				{
					final double difference = descriptorA.descriptorDistance( descriptorB );
									
					if ( difference < secondBest )
					{
						if ( difference < best )
						{
							secondBest = best;
							best = difference;
							
							correct = isCorrect( descriptorA.getBasisPoint(), descriptorB.getBasisPoint() );
						}
						else
						{
							secondBest = difference;
						}
					}
				}
				
				if ( best * nTimesBetter < secondBest )
				{
					if ( correct )
					{
						++detectedRight;
						ArrayList< Point > neighbors = descriptorA.getOrderedNearestNeighboringPoints();
						
						for ( Point p : neighbors )
							for ( int i = 0; i < numTrueMatches; ++i )
								if ( isCorrect(p, points1.get( i )) )
									foundByNeighbor[ i ] = true;
					}
					else
						++detectedWrong;
				}
			}		
		}
		
		final long duration = System.currentTimeMillis() - time;
		
		int countAll = 0;

		for ( int i = 0; i < numTrueMatches; ++i )
			if ( foundByNeighbor[ i ] )
				++countAll;

		System.out.println( numNeighbors + "\t" + numRandomPoints + "\t" + detectedRight + "\t" + countAll + "\t" + detectedWrong + "\t" + duration );
	}

	public static ArrayList<Point3f> getBoundingBox( final float start, final float end )
	{
		final ArrayList<Point3f> boundingBox = new ArrayList<Point3f>();
		
		boundingBox.add( new Point3f(start,start,start) );
		boundingBox.add( new Point3f(end, start, start) );
		
		boundingBox.add( new Point3f(end, start, start) );
		boundingBox.add( new Point3f(end, end, start) );

		boundingBox.add( new Point3f(end, end, start) );
		boundingBox.add( new Point3f(start, end, start) );
		
		boundingBox.add( new Point3f(start, end, start) );
		boundingBox.add( new Point3f(start, start, start) );

		boundingBox.add( new Point3f(start, start, end) );
		boundingBox.add( new Point3f(end, start, end) );

		boundingBox.add( new Point3f(end, start,  end) );
		boundingBox.add( new Point3f(end, end,  end) );

		boundingBox.add( new Point3f(end, end,  end) );
		boundingBox.add( new Point3f(start, end, end) );

		boundingBox.add( new Point3f(start, end,  end) );
		boundingBox.add( new Point3f(start, start, end) );

		boundingBox.add( new Point3f(start, start, start) );
		boundingBox.add( new Point3f(start, start, end) );

		boundingBox.add( new Point3f(end, start, start) );
		boundingBox.add( new Point3f(end, start, end) );

		boundingBox.add( new Point3f(end, end, start) );
		boundingBox.add( new Point3f(end, end, end) );

		boundingBox.add( new Point3f(start, end, start) );
		boundingBox.add( new Point3f(start, end, end) );
		
		return boundingBox;
	}
	
	protected static boolean isCorrect( Point a, Point b )
	{
		if ( a instanceof LinkedPoint )
		{
			if ( ((LinkedPoint<Point>)a).getLinkedObject() == b  )
				return true;
			else
				return false;
		}
		else if ( b instanceof LinkedPoint )
		{
			if ( ((LinkedPoint<Point>)b).getLinkedObject() == a  )
				return true;
			else
				return false;
		}
		else
		{
			return false;
		}
		
		/*
		double[] a1 = a.getL();
		double[] b1 = b.getL();
		
		if ( a1[ 0 ] == b1[ 0 ] && a1[ 1 ] == b1[ 1 ] && a1[ 2 ] == b1[ 2 ] )
			return true;
		else
			return false;
		*/
	}
	
	public static void main( String args[] )
	{
		boolean showPoints = false;
		
		if ( showPoints )
		{
			final String params[] = { "-ijpath ." };
			ij.ImageJ.main( params );
		}
		
		//testQuaternions();
		//new TestPointDescriptor();
		
		double stdev = 0.2f;
		
		//testStability( 3, 100, 0, 10.0, stdev, true, showPoints );
						
		for ( int n = 2; n <= 10000000; n *= 1.5 )
			testStability( 3, 100, n, 10.0, stdev, false, showPoints );
		
		//for ( int neighbors = 3; neighbors <= 8; ++neighbors )
		//	for ( int n = 1; n <= 10000; n *= 10 )
		//		testStability( neighbors, 100, n, 10.0, false, showPoints );
	}
}
