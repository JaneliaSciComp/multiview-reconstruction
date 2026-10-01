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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointSS;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5.InterestPointData;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import util.URITools;

public class TestInterestPointSSSaveLoad
{
	@TempDir
	Path tempDir;

	public static ArrayList< InterestPoint > scaleSpacePoints( final int n, final long seed )
	{
		final Random rnd = new Random( seed );
		final ArrayList< InterestPoint > points = new ArrayList<>();

		for ( int i = 0; i < n; ++i )
			points.add( new InterestPointSS( i, new double[] { rnd.nextDouble() * 500, rnd.nextDouble() * 500, rnd.nextDouble() * 100 }, -rnd.nextDouble(), 1.8 * Math.pow( 2, i % 4 ) * ( 1 + rnd.nextDouble() ) ) );

		return points;
	}

	public static ArrayList< InterestPoint > plainPoints( final int n, final long seed )
	{
		final Random rnd = new Random( seed );
		final ArrayList< InterestPoint > points = new ArrayList<>();

		for ( int i = 0; i < n; ++i )
			points.add( new InterestPoint( i, new double[] { rnd.nextDouble() * 500, rnd.nextDouble() * 500, rnd.nextDouble() * 100 } ) );

		return points;
	}

	protected N5Reader openN5()
	{
		return URITools.instantiateN5Reader( StorageFormat.N5, URITools.toURI( URITools.appendName( tempDir.toUri(), InterestPointsN5.baseN5 ) ) );
	}

	@Test
	public void testScaleSpaceRoundTrip()
	{
		final ViewId viewId = new ViewId( 0, 1 );
		final String label = "ss";
		final ArrayList< InterestPoint > points = scaleSpacePoints( 1000, 1 );

		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), viewId, label );
		ips.setInterestPoints( points );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );
		assertTrue( ips.saveInterestPoints( true ) );

		// datasets
		final String dataset = InterestPointsN5.ipDataset( InterestPointsN5.createN5datasetPath( 0, 1, label ) );

		try ( final N5Reader n5 = openN5() )
		{
			final DatasetAttributes response = n5.getDatasetAttributes( dataset + "/" + InterestPointsN5.responseDataset );
			final DatasetAttributes sigma = n5.getDatasetAttributes( dataset + "/" + InterestPointsN5.sigmaDataset );

			assertEquals( DataType.FLOAT32, response.getDataType() );
			assertEquals( DataType.FLOAT64, sigma.getDataType() );
			assertArrayEquals( new long[] { 1, 1000 }, response.getDimensions() );
			assertArrayEquals( new long[] { 1, 1000 }, sigma.getDimensions() );
			assertArrayEquals( new int[] { 1, InterestPointsN5.defaultBlockSize }, response.getBlockSize() );
			assertTrue( response.getCompression() instanceof GzipCompression );

			// static readers, in the order of 'id'
			final float[] responses = InterestPointsN5.loadResponses( n5, dataset );
			final double[] sigmas = InterestPointsN5.loadSigmas( n5, dataset );

			assertEquals( 1000, responses.length );
			assertEquals( 1000, sigmas.length );

			for ( int i = 0; i < 1000; ++i )
			{
				assertEquals( (float)( (InterestPointSS)points.get( i ) ).getResponse(), responses[ i ], 0.0f );
				assertEquals( ( (InterestPointSS)points.get( i ) ).getSigma(), sigmas[ i ], 0.0 );
			}
		}

		// loading creates InterestPointSS transparently
		final Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();

		assertEquals( 1000, loaded.size() );

		for ( final InterestPoint p : points )
		{
			final InterestPoint l = loaded.get( p.getId() );

			assertTrue( InterestPointSS.class.isInstance( l ), "loaded point is an InterestPointSS" );
			assertArrayEquals( p.getL(), l.getL(), 0.0 );
			assertEquals( (float)( (InterestPointSS)p ).getResponse(), ( (InterestPointSS)l ).getResponse(), 0.0 );
			assertEquals( ( (InterestPointSS)p ).getSigma(), ( (InterestPointSS)l ).getSigma(), 0.0 );
			assertEquals( ( (InterestPointSS)l ).getResponse(), ( (InterestPointSS)l ).getIntensity(), 0.0 );
		}
	}

	@Test
	public void testPlainListUnchanged()
	{
		final ViewId viewId = new ViewId( 0, 2 );
		final String label = "plain";
		final ArrayList< InterestPoint > points = plainPoints( 500, 2 );

		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), viewId, label );
		ips.setInterestPoints( points );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );
		assertTrue( ips.saveInterestPoints( true ) );

		final String dataset = InterestPointsN5.ipDataset( InterestPointsN5.createN5datasetPath( 0, 2, label ) );

		try ( final N5Reader n5 = openN5() )
		{
			assertFalse( n5.datasetExists( dataset + "/" + InterestPointsN5.responseDataset ) );
			assertFalse( n5.datasetExists( dataset + "/" + InterestPointsN5.sigmaDataset ) );
			assertNull( InterestPointsN5.loadResponses( n5, dataset ) );
			assertNull( InterestPointsN5.loadSigmas( n5, dataset ) );
		}

		final Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();

		assertEquals( 500, loaded.size() );

		for ( final InterestPoint p : points )
		{
			assertEquals( InterestPoint.class, loaded.get( p.getId() ).getClass() );
			assertArrayEquals( p.getL(), loaded.get( p.getId() ).getL(), 0.0 );
		}
	}

	@Test
	public void testOldStyleLabel()
	{
		// written with the old static method (as existing datasets were)
		final int[] ids = new int[ 10 ];
		final double[][] locations = new double[ 10 ][ 3 ];

		for ( int i = 0; i < 10; ++i )
		{
			ids[ i ] = i;
			locations[ i ] = new double[] { i, 2 * i, 3 * i };
		}

		final String n5path = InterestPointsN5.createN5datasetPath( 0, 3, "old" );

		try ( final N5Writer n5 = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( tempDir.toUri(), InterestPointsN5.baseN5 ) ) ) )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, n5path, ids, locations ) );
			assertNull( InterestPointsN5.loadResponses( n5, InterestPointsN5.ipDataset( n5path ) ) );
		}

		final Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 3 ), "old" ).getInterestPointsCopy();

		assertEquals( 10, loaded.size() );

		for ( int i = 0; i < 10; ++i )
		{
			assertEquals( InterestPoint.class, loaded.get( i ).getClass() );
			assertArrayEquals( locations[ i ], loaded.get( i ).getL(), 0.0 );
		}
	}

	@Test
	public void testMixedListRejected()
	{
		final ArrayList< InterestPoint > points = scaleSpacePoints( 10, 4 );
		points.add( new InterestPoint( 10, new double[] { 1, 2, 3 } ) );

		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 4 ), "mixed" );

		assertThrows( IllegalArgumentException.class, () -> ips.setInterestPoints( points ) );
	}

	@Test
	public void testEmptyScaleSpaceList()
	{
		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 5 ), "empty" );
		ips.setInterestPoints( new ArrayList<>() );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );
		assertTrue( ips.saveInterestPoints( true ) );

		assertEquals( 0, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 5 ), "empty" ).getInterestPointsCopy().size() );

		// empty arrays are written as empty datasets
		final String n5path = InterestPointsN5.createN5datasetPath( 0, 6, "emptyss" );

		try ( final N5Writer n5 = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( tempDir.toUri(), InterestPointsN5.baseN5 ) ) ) )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, n5path, new int[ 0 ], new double[ 0 ][ 0 ], new float[ 0 ], new double[ 0 ] ) );
			assertEquals( 0, InterestPointsN5.loadResponses( n5, InterestPointsN5.ipDataset( n5path ) ).length );
			assertEquals( 0, InterestPointsN5.loadSigmas( n5, InterestPointsN5.ipDataset( n5path ) ).length );
		}

		assertEquals( 0, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 6 ), "emptyss" ).getInterestPointsCopy().size() );
	}

	@Test
	public void testInterestPointDataRoundTrip()
	{
		final ViewId viewId = new ViewId( 1, 7 );
		final String label = "dto";
		final ArrayList< InterestPoint > points = scaleSpacePoints( 100, 7 );

		final InterestPointsN5 ips = (InterestPointsN5)InterestPoints.newInstance( tempDir.toUri(), viewId, label );
		ips.setInterestPoints( points );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );

		final InterestPointData data = InterestPointData.from( viewId, label, ips );

		assertEquals( 100, data.responses.length );
		assertEquals( 100, data.sigmas.length );

		try ( final N5Writer n5 = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( tempDir.toUri(), InterestPointsN5.baseN5 ) ) ) )
		{
			assertTrue( InterestPointsN5.saveInterestPointDataStatic( n5, data ) );
		}

		final Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();

		assertEquals( 100, loaded.size() );

		for ( final InterestPoint p : points )
		{
			assertTrue( InterestPointSS.class.isInstance( loaded.get( p.getId() ) ) );
			assertEquals( ( (InterestPointSS)p ).getSigma(), ( (InterestPointSS)loaded.get( p.getId() ) ).getSigma(), 0.0 );
		}
	}

	/**
	 * The parallel save of the XML writer stores response and sigma without any driver code
	 */
	@Test
	public void testPipelineSave() throws SpimDataException
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		int seed = 0;

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().keySet() )
			map.put( viewId, scaleSpacePoints( 50 + 10 * seed, seed++ ) );

		final String params = "DOG-SS s=1.8 steps=3 octaves=-1 t=0.008 min=false max=true downsampleXY=2 downsampleXYIndex=0 downsampleZ=1 minIntensity=0.0 maxIntensity=1137.0";
		InterestPointTools.addInterestPoints( spimData, "ss", map, params );

		final URI xmlURI = tempDir.resolve( "dataset.xml" ).toUri();
		new XmlIoSpimData2().save( spimData, xmlURI );

		final SpimData2 loaded = new XmlIoSpimData2().load( xmlURI );

		for ( final ViewId viewId : map.keySet() )
		{
			final InterestPoints list = loaded.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "ss" );

			assertEquals( params, list.getParameters() );

			final Map< Integer, InterestPoint > points = list.getInterestPointsCopy();

			assertEquals( map.get( viewId ).size(), points.size() );

			for ( final InterestPoint p : map.get( viewId ) )
			{
				final InterestPoint l = points.get( p.getId() );

				assertTrue( InterestPointSS.class.isInstance( l ) );
				assertArrayEquals( p.getL(), l.getL(), 0.0 );
				assertEquals( (float)( (InterestPointSS)p ).getResponse(), ( (InterestPointSS)l ).getResponse(), 0.0 );
				assertEquals( ( (InterestPointSS)p ).getSigma(), ( (InterestPointSS)l ).getSigma(), 0.0 );
			}
		}
	}
}
