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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.Function;

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
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionRecord;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder;
import net.preibisch.mvrecon.fiji.plugin.Filter_Detections;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter.Axis;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsN5ToZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Key;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.VirtualFilteredInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.n5api.SpimData2Tools;
import util.URITools;

/**
 * The storage of interest points: the attributes (response, sigma, intensity) through the store, labels in the N5 layout
 * (also those stored before the attributes existed) read through the same handles and converted, and filtered labels
 * (VirtualFilteredInterestPoints) that filter their base label on the fly
 */
public class TestInterestPointStorage
{
	@TempDir
	Path tempDir;

	/**
	 * @return points with all attributes: responses of both signs in (-1, 1), sigmas 1.8..~29, intensities 100..1100
	 */
	public static ArrayList< InterestPoint > points( final int n, final long seed )
	{
		final Random rnd = new Random( seed );
		final ArrayList< InterestPoint > points = new ArrayList<>();

		for ( int i = 0; i < n; ++i )
			points.add( new InterestPoint(
					i,
					new double[] { rnd.nextDouble() * 500, rnd.nextDouble() * 500, rnd.nextDouble() * 100 },
					(float)( ( i % 3 == 0 ? -1 : 1 ) * rnd.nextDouble() ),
					(float)( 1.8 * Math.pow( 2, i % 4 ) * ( 1 + rnd.nextDouble() ) ),
					(float)( 100 + 1000 * rnd.nextDouble() ) ) );

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

	protected N5Writer openN5Writer()
	{
		return URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( tempDir.toUri(), InterestPointsN5.baseN5 ) ) );
	}

	protected InterestPointsZarrStore store()
	{
		return InterestPointsZarrStore.get( tempDir.toUri() );
	}

	/** The store keeps positions as float32: equal within what a float keeps at 500 px. */
	protected static void assertSameAttributes( final InterestPoint expected, final InterestPoint actual )
	{
		assertArrayEquals( expected.getL(), actual.getL(), 1e-3 );
		assertEquals( expected.getResponse(), actual.getResponse(), 0.0f );
		assertEquals( expected.getSigma(), actual.getSigma(), 0.0f );
		assertEquals( expected.getIntensity(), actual.getIntensity(), 0.0f );
	}

	/**
	 * Labels stored in the N5 layout between 2026-10-02 and 2026-10-19 have a FLOAT64 'sigma' dataset, it is read like the
	 * FLOAT32 one, before and after the conversion to the store
	 */
	@Test
	public void testOldFloat64Sigma()
	{
		final ViewId viewId = new ViewId( 0, 8 );
		final String label = "sigma64";
		final ArrayList< InterestPoint > points = points( 20, 8 );
		final String path = InterestPointsN5.createN5datasetPath( 0, 8, label );
		final String dataset = InterestPointsN5.ipDataset( path );

		// replace the sigma dataset by a FLOAT64 one with other values, as the old code wrote it
		final double[] sigmas64 = new double[ 20 ];
		for ( int i = 0; i < 20; ++i )
			sigmas64[ i ] = 1.5 + 0.25 * i;

		try ( final N5Writer n5 = openN5Writer() )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, path, points ) );
			assertTrue( InterestPointsN5.saveCorrespondencesStatic( n5, path, new ArrayList<>() ) );

			n5.remove( dataset + "/" + InterestPointsN5.sigmaDataset );
			final net.imglib2.img.array.ArrayImg< net.imglib2.type.numeric.real.DoubleType, ? > img = net.imglib2.img.array.ArrayImgs.doubles( sigmas64, 1, 20 );
			org.janelia.saalfeldlab.n5.imglib2.N5Utils.save( img, n5, dataset + "/" + InterestPointsN5.sigmaDataset, new int[] { 1, 20 }, new GzipCompression() );
			assertEquals( DataType.FLOAT64, n5.getDatasetAttributes( dataset + "/" + InterestPointsN5.sigmaDataset ).getDataType() );
			assertArrayEquals( new float[] { 1.5f, 1.75f, 2.0f }, Arrays.copyOf( InterestPointsN5.loadSigmas( n5, dataset ), 3 ), 0.0f );
		}

		// read through the handle (the N5 fallback)
		Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();

		for ( int i = 0; i < 20; ++i )
			assertEquals( (float)sigmas64[ i ], loaded.get( i ).getSigma(), 0.0f );

		// and from the store after the conversion
		assertEquals( 1, InterestPointsN5ToZarr.convert( tempDir.toUri() ) );
		assertFalse( new File( tempDir.toFile(), InterestPointsN5.baseN5 + "/tpId_0_viewSetupId_8" ).exists() );
		loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();

		for ( int i = 0; i < 20; ++i )
		{
			assertEquals( (float)sigmas64[ i ], loaded.get( i ).getSigma(), 0.0f );
			assertEquals( points.get( i ).getResponse(), loaded.get( i ).getResponse(), 0.0f );
			assertArrayEquals( points.get( i ).getL(), loaded.get( i ).getL(), 1e-3 );
		}
	}

	/**
	 * A list saved through its handle outside a batch is a staging file first and part of the arrays after the commit;
	 * both read back with the attributes. The N5 writer of temporary lists writes the attributes as FLOAT32 1 x N datasets.
	 */
	@Test
	public void testRoundTrip()
	{
		final ViewId viewId = new ViewId( 0, 1 );
		final String label = "all";
		final ArrayList< InterestPoint > points = points( 1000, 1 );

		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), viewId, label );
		ips.setInterestPoints( points );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );
		assertTrue( ips.saveInterestPoints( true ) );
		assertTrue( ips.saveCorrespondingInterestPoints( true ) );
		assertTrue( new File( tempDir.toFile(), InterestPointsZarrStore.ZARR_CONTAINER + "/staging" ).list().length > 0 );

		Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();
		assertEquals( 1000, loaded.size() );
		for ( final InterestPoint p : points )
			assertSameAttributes( p, loaded.get( p.getId() ) );

		store().commit();
		assertEquals( 0, new File( tempDir.toFile(), InterestPointsZarrStore.ZARR_CONTAINER + "/staging" ).list().length );
		final Points stored = store().points( Key.of( viewId, label ) );
		assertEquals( 1000, stored.size() );
		assertEquals( 1000 * InterestPointsZarrStore.COLUMNS, stored.data().length );
		assertFalse( stored.filtered() );

		loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();
		assertEquals( 1000, loaded.size() );
		for ( final InterestPoint p : points )
			assertSameAttributes( p, loaded.get( p.getId() ) );

		// the N5 layout of temporary lists
		final String dataset = InterestPointsN5.ipDataset( InterestPointsN5.createN5datasetPath( 0, 1, label ) );

		try ( final N5Writer n5 = openN5Writer() )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, InterestPointsN5.createN5datasetPath( 0, 1, label ), points ) );

			final DatasetAttributes response = n5.getDatasetAttributes( dataset + "/" + InterestPointsN5.responseDataset );
			final DatasetAttributes sigma = n5.getDatasetAttributes( dataset + "/" + InterestPointsN5.sigmaDataset );
			final DatasetAttributes intensity = n5.getDatasetAttributes( dataset + "/" + InterestPointsN5.intensityDataset );

			assertEquals( DataType.FLOAT32, response.getDataType() );
			assertEquals( DataType.FLOAT32, sigma.getDataType() );
			assertEquals( DataType.FLOAT32, intensity.getDataType() );
			assertArrayEquals( new long[] { 1, 1000 }, response.getDimensions() );
			assertArrayEquals( new long[] { 1, 1000 }, sigma.getDimensions() );
			assertArrayEquals( new long[] { 1, 1000 }, intensity.getDimensions() );
			assertArrayEquals( new int[] { 1, InterestPointsN5.defaultBlockSize }, response.getBlockSize() );
			assertTrue( response.getCompression() instanceof GzipCompression );

			// static readers, in the order of 'id'
			final float[] responses = InterestPointsN5.loadResponses( n5, dataset );
			final float[] sigmas = InterestPointsN5.loadSigmas( n5, dataset );
			final float[] intensities = InterestPointsN5.loadIntensities( n5, dataset );

			assertEquals( 1000, responses.length );
			assertEquals( 1000, sigmas.length );
			assertEquals( 1000, intensities.length );

			for ( int i = 0; i < 1000; ++i )
			{
				assertEquals( (float)points.get( i ).getResponse(), responses[ i ], 0.0f );
				assertEquals( points.get( i ).getSigma(), sigmas[ i ], 0.0 );
				assertEquals( (float)points.get( i ).getIntensity(), intensities[ i ], 0.0f );
			}
		}

		// the store has the label, so the N5 group is not consulted (and the conversion drops it)
		assertEquals( 0, InterestPointsN5ToZarr.convert( tempDir.toUri() ) );
		assertEquals( 1000, InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy().size() );
	}

	/**
	 * Points without attributes are stored as NaN and load as NaN, in the store and in the N5 layout
	 */
	@Test
	public void testPlainListStoresUnknown()
	{
		final ViewId viewId = new ViewId( 0, 2 );
		final String label = "plain";
		final ArrayList< InterestPoint > points = plainPoints( 500, 2 );

		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), viewId, label );
		ips.setInterestPoints( points );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );
		assertTrue( ips.saveInterestPoints( true ) );
		store().commit();

		final Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getInterestPointsCopy();

		assertEquals( 500, loaded.size() );

		for ( final InterestPoint p : points )
		{
			final InterestPoint l = loaded.get( p.getId() );
			assertArrayEquals( p.getL(), l.getL(), 1e-3 );
			assertTrue( Double.isNaN( l.getResponse() ) && Double.isNaN( l.getSigma() ) && Double.isNaN( l.getIntensity() ) );
		}

		final String dataset = InterestPointsN5.ipDataset( InterestPointsN5.createN5datasetPath( 0, 12, label ) );

		try ( final N5Writer n5 = openN5Writer() )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, InterestPointsN5.createN5datasetPath( 0, 12, label ), points ) );

			assertTrue( n5.datasetExists( dataset + "/" + InterestPointsN5.responseDataset ) );
			assertTrue( n5.datasetExists( dataset + "/" + InterestPointsN5.sigmaDataset ) );
			assertTrue( n5.datasetExists( dataset + "/" + InterestPointsN5.intensityDataset ) );

			for ( final float v : InterestPointsN5.loadResponses( n5, dataset ) )
				assertTrue( Float.isNaN( v ) );

			for ( final float v : InterestPointsN5.loadSigmas( n5, dataset ) )
				assertTrue( Float.isNaN( v ) );

			for ( final float v : InterestPointsN5.loadIntensities( n5, dataset ) )
				assertTrue( Float.isNaN( v ) );
		}
	}

	/**
	 * A label stored in the N5 layout before the attributes existed (only 'id' and 'loc') loads with NaN attributes, also
	 * after the conversion, and a filter that needs one refuses it
	 */
	@Test
	public void testOldStyleLabel()
	{
		final int[] ids = new int[ 10 ];
		final double[][] locations = new double[ 10 ][ 3 ];

		for ( int i = 0; i < 10; ++i )
		{
			ids[ i ] = i;
			locations[ i ] = new double[] { i, 2 * i, 3 * i };
		}

		final String n5path = InterestPointsN5.createN5datasetPath( 0, 3, "old" );
		final String dataset = InterestPointsN5.ipDataset( n5path );

		try ( final N5Writer n5 = openN5Writer() )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, n5path, ids, locations ) );

			// as the old code wrote it
			n5.remove( dataset + "/" + InterestPointsN5.responseDataset );
			n5.remove( dataset + "/" + InterestPointsN5.sigmaDataset );
			n5.remove( dataset + "/" + InterestPointsN5.intensityDataset );

			assertNull( InterestPointsN5.loadResponses( n5, dataset ) );
			assertNull( InterestPointsN5.loadSigmas( n5, dataset ) );
			assertNull( InterestPointsN5.loadIntensities( n5, dataset ) );
		}

		for ( int round = 0; round < 2; ++round )
		{
			final Map< Integer, InterestPoint > loaded = InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 3 ), "old" ).getInterestPointsCopy();

			assertEquals( 10, loaded.size() );

			for ( int i = 0; i < 10; ++i )
			{
				assertArrayEquals( locations[ i ], loaded.get( i ).getL(), 0.0 );
				assertTrue( Double.isNaN( loaded.get( i ).getResponse() ) );
				assertTrue( Double.isNaN( loaded.get( i ).getSigma() ) );
				assertTrue( Double.isNaN( loaded.get( i ).getIntensity() ) );
			}

			assertThrows( IllegalArgumentException.class, () -> InterestPointFilter.of( Axis.RESPONSE, 0.1, Double.NaN ).filter( loaded.values() ) );

			// a label without a correspondences group has none
			assertEquals( 0, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 3 ), "old" ).getCorrespondingInterestPointsCopy().size() );

			if ( round == 0 )
				assertEquals( 1, InterestPointsN5ToZarr.convert( tempDir.toUri() ) );
		}
	}

	@Test
	public void testLengthMismatchRejected()
	{
		final String n5path = InterestPointsN5.createN5datasetPath( 0, 4, "mismatch" );

		try ( final N5Writer n5 = openN5Writer() )
		{
			assertThrows( IllegalArgumentException.class, () -> InterestPointsN5.saveInterestPointsStatic( n5, n5path, new int[ 3 ], new double[ 3 ][ 3 ], new float[ 2 ], null, null ) );
			assertThrows( IllegalArgumentException.class, () -> InterestPointsN5.saveInterestPointsStatic( n5, n5path, new int[ 3 ], new double[ 3 ][ 3 ], null, null, new float[ 4 ] ) );
		}

		assertThrows( IllegalArgumentException.class, () -> new Points( new int[ 3 ], new float[ 2 * InterestPointsZarrStore.COLUMNS ] ) );
		assertThrows( IllegalArgumentException.class, () -> new Points( new int[ 1 ], new float[ InterestPointsZarrStore.COLUMNS ], true ) );
	}

	@Test
	public void testEmptyList()
	{
		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 5 ), "empty" );
		ips.setInterestPoints( new ArrayList<>() );
		ips.setCorrespondingInterestPoints( new ArrayList<>() );
		assertTrue( ips.saveInterestPoints( true ) );
		assertTrue( ips.saveCorrespondingInterestPoints( true ) );

		assertEquals( 0, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 5 ), "empty" ).getInterestPointsCopy().size() );
		store().commit();
		assertEquals( 0, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 5 ), "empty" ).getInterestPointsCopy().size() );
		assertEquals( 0, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 5 ), "empty" ).getCorrespondingInterestPointsCopy().size() );
		assertTrue( store().hasEntry( new Key( 0, 5, "empty" ) ) );

		// empty arrays are written as empty datasets in the N5 layout
		final String n5path = InterestPointsN5.createN5datasetPath( 0, 6, "emptystatic" );

		try ( final N5Writer n5 = openN5Writer() )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, n5path, new int[ 0 ], new double[ 0 ][ 0 ], new float[ 0 ], new float[ 0 ], new float[ 0 ] ) );
			assertEquals( 0, InterestPointsN5.loadResponses( n5, InterestPointsN5.ipDataset( n5path ) ).length );
			assertEquals( 0, InterestPointsN5.loadSigmas( n5, InterestPointsN5.ipDataset( n5path ) ).length );
			assertEquals( 0, InterestPointsN5.loadIntensities( n5, InterestPointsN5.ipDataset( n5path ) ).length );
		}

		assertEquals( 0, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 0, 6 ), "emptystatic" ).getInterestPointsCopy().size() );
	}

	/**
	 * The save of a Spark task (saveStaged): one staging file for many lists, readable at once, committed by the next save
	 */
	@Test
	public void testSaveStaged()
	{
		final ViewId viewId = new ViewId( 1, 7 );
		final String label = "staged";
		final ArrayList< InterestPoint > points = points( 100, 7 );
		final ArrayList< CorrespondingInterestPoints > corr = new ArrayList<>();
		corr.add( new CorrespondingInterestPoints( 3, new ViewId( 1, 8 ), label, 7, -1 ) );
		corr.add( new CorrespondingInterestPoints( 9, new ViewId( 1, 8 ), label, 1, 2 ) );
		corr.add( new CorrespondingInterestPoints( 9, new ViewId( 1, 9 ), "other", 4, 2 ) );

		final InterestPoints ips = InterestPoints.newInstance( tempDir.toUri(), viewId, label );
		ips.setInterestPoints( points );
		ips.setCorrespondingInterestPoints( corr );
		final InterestPoints other = InterestPoints.newInstance( tempDir.toUri(), new ViewId( 1, 8 ), label );
		other.setInterestPoints( points( 10, 8 ) );
		other.setCorrespondingInterestPoints( new ArrayList<>() );

		InterestPointsZarr.saveStaged( Arrays.asList( ips, other ) );
		assertFalse( ips.hasModifiedInterestPoints() || ips.hasModifiedCorrespondingInterestPoints() );
		assertEquals( 1, new File( tempDir.toFile(), InterestPointsZarrStore.ZARR_CONTAINER + "/staging" ).list().length );

		for ( int round = 0; round < 2; ++round )
		{
			final InterestPoints loaded = InterestPoints.newInstance( tempDir.toUri(), viewId, label );
			final Map< Integer, InterestPoint > map = loaded.getInterestPointsCopy();
			assertEquals( 100, map.size() );
			for ( final InterestPoint p : points )
				assertSameAttributes( p, map.get( p.getId() ) );
			assertEquals( 3, loaded.getCorrespondingInterestPointsCopy().size() );
			assertEquals( 2, InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getCorrespondingInterestPointsCopy( new ViewId( 1, 8 ), label ).size() );
			assertEquals( 1, InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getCorrespondingInterestPointsCopy( new ViewId( 1, 9 ), "other" ).size() );
			assertEquals( 2, InterestPoints.newInstance( tempDir.toUri(), viewId, label ).getCorrespondingViews().size() );
			assertEquals( 10, InterestPoints.newInstance( tempDir.toUri(), new ViewId( 1, 8 ), label ).getInterestPointsCopy().size() );

			if ( round == 0 )
				store().commit();
		}
	}

	/**
	 * The parallel save of the XML writer stores the attributes without any driver code, into the store
	 */
	@Test
	public void testPipelineSave() throws Exception
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		int seed = 0;

		for ( final ViewId viewId : spimData.getSequenceDescription().getViewDescriptions().keySet() )
			map.put( viewId, points( 50 + 10 * seed, seed++ ) );

		final String params = "DOG-SS s=1.8 steps=4 octaves=-1 finestLevel=true t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=1.0 minIntensity=0.0 maxIntensity=1137.0";
		InterestPointTools.addInterestPoints( spimData, "ss", map, params );

		final URI xmlURI = tempDir.resolve( "dataset.xml" ).toUri();
		new XmlIoSpimData2().save( spimData, xmlURI );

		assertTrue( new File( tempDir.toFile(), InterestPointsZarrStore.ZARR_CONTAINER ).isDirectory() );
		assertFalse( new File( tempDir.toFile(), InterestPointsN5.baseN5 ).exists() );
		final String[] staging = new File( tempDir.toFile(), InterestPointsZarrStore.ZARR_CONTAINER + "/staging" ).list();
		assertTrue( staging == null || staging.length == 0 );

		final SpimData2 loaded = new XmlIoSpimData2().load( xmlURI );

		for ( final ViewId viewId : map.keySet() )
		{
			final InterestPoints list = loaded.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "ss" );

			assertEquals( params, list.getParameters() );

			final Map< Integer, InterestPoint > points = list.getInterestPointsCopy();

			assertEquals( map.get( viewId ).size(), points.size() );

			for ( final InterestPoint p : map.get( viewId ) )
				assertSameAttributes( p, points.get( p.getId() ) );
		}

		// lists created while the dataset still had another base path are saved where the dataset is saved (as the N5
		// layout's shared writer did), not into a store at their old base path
		final Path elsewhere = Files.createDirectories( tempDir.resolve( "elsewhere" ) );
		final Path here = Files.createDirectories( tempDir.resolve( "here" ) );
		final SpimData2 moved = SimulateUtil.setUp();
		moved.setBasePathURI( elsewhere.toUri() );
		InterestPointTools.addInterestPoints( moved, "ss", map, params );
		moved.setBasePathURI( here.toUri() );
		new XmlIoSpimData2().save( moved, here.resolve( "dataset.xml" ).toUri() );

		assertFalse( new File( elsewhere.toFile(), InterestPointsZarrStore.ZARR_CONTAINER ).exists(), "nothing at the old base path" );
		assertTrue( new File( here.toFile(), InterestPointsZarrStore.ZARR_CONTAINER ).isDirectory() );
		final SpimData2 movedLoaded = new XmlIoSpimData2().load( here.resolve( "dataset.xml" ).toUri() );
		for ( final ViewId viewId : map.keySet() )
			assertEquals( map.get( viewId ).size(), movedLoaded.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "ss" ).getInterestPointsCopy().size() );
	}

	protected static TreeSet< Integer > detectionIds( final Collection< CorrespondingInterestPoints > correspondences )
	{
		final TreeSet< Integer > ids = new TreeSet<>();

		for ( final CorrespondingInterestPoints c : correspondences )
			ids.add( c.getDetectionId() );

		return ids;
	}

	protected static TreeSet< Integer > idsPassing( final List< InterestPoint > points, final InterestPointFilter filter )
	{
		final TreeSet< Integer > ids = new TreeSet<>();

		for ( final InterestPoint ip : points )
			if ( filter.accept( ip ) )
				ids.add( ip.getId() );

		return ids;
	}

	/**
	 * A filtered label filters its base label on the fly (also before anything is saved, also after the base was detected
	 * again), keeps the ids, has its own correspondences, survives the XML round trip (attributes 'base' and 'filter', a
	 * marker entry in the store and no points of its own), can be chained, and refuses to exist without its base
	 */
	@Test
	public void testFilteredLabel() throws Exception
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		int seed = 0;

		for ( final ViewId viewId : views )
			map.put( viewId, points( 200 + 10 * seed, 100 + seed++ ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.001" );

		// a filtered label on the unsaved base
		final InterestPointFilter byResponse = InterestPointFilter.of( Axis.RESPONSE, 0.5, Double.NaN );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_r0.5", "beads", byResponse, views ) );

		// a filter of the filter
		final InterestPointFilter bySigma = InterestPointFilter.of( Axis.SIGMA, 2, 4 );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_r0.5_s2-4", "beads_r0.5", bySigma, views ) );

		final InterestPointFilter both = new InterestPointFilter( 0.5, Double.NaN, Double.NaN, Double.NaN, 2, 4 );

		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists vipl = spimData.getViewInterestPoints().getViewInterestPointLists( viewId );
			final InterestPoints filtered = vipl.getInterestPointList( "beads_r0.5" );
			final InterestPoints chained = vipl.getInterestPointList( "beads_r0.5_s2-4" );

			assertTrue( filtered instanceof VirtualFilteredInterestPoints );
			assertEquals( "beads", ( (VirtualFilteredInterestPoints)filtered ).getBaseLabel() );
			assertEquals( byResponse, ( (VirtualFilteredInterestPoints)filtered ).getFilter() );
			assertEquals( byResponse.describe( "beads" ), filtered.getParameters() );

			final TreeSet< Integer > expected = idsPassing( map.get( viewId ), byResponse );
			final Map< Integer, InterestPoint > points = filtered.getInterestPointsCopy();

			assertTrue( expected.size() > 0 && expected.size() < map.get( viewId ).size() );
			assertEquals( expected, new TreeSet<>( points.keySet() ) );

			for ( final InterestPoint ip : points.values() )
				assertSameAttributes( map.get( viewId ).get( ip.getId() ), ip );

			final TreeSet< Integer > expectedChained = idsPassing( map.get( viewId ), both );
			assertTrue( expectedChained.size() > 0 && expectedChained.size() < expected.size() );
			assertEquals( expectedChained, new TreeSet<>( chained.getInterestPointsCopy().keySet() ) );

			// read-only
			assertThrows( UnsupportedOperationException.class, () -> filtered.setInterestPoints( new ArrayList<>() ) );

			// its own correspondences (the base keeps none)
			final ArrayList< CorrespondingInterestPoints > corr = new ArrayList<>();
			final ViewId other = views.get( ( views.indexOf( viewId ) + 1 ) % views.size() );

			for ( final int id : expected )
				if ( id % 5 == 0 )
					corr.add( new CorrespondingInterestPoints( id, other, "beads_r0.5", id + 1 ) );

			filtered.setCorrespondingInterestPoints( corr );
		}

		assertEquals( Arrays.asList( "beads_r0.5", "beads_r0.5_s2-4" ), InterestPointTools.dependentLabels( spimData.getViewInterestPoints().getViewInterestPointLists( views.get( 0 ) ), "beads" ) );
		assertEquals( Arrays.asList( "beads_r0.5_s2-4" ), InterestPointTools.dependentLabels( spimData.getViewInterestPoints().getViewInterestPointLists( views.get( 0 ) ), "beads_r0.5" ) );
		assertTrue( InterestPointTools.dependentLabels( spimData.getViewInterestPoints().getViewInterestPointLists( views.get( 0 ) ), "beads_r0.5_s2-4" ).isEmpty() );

		// the XML round trip
		final URI xmlURI = tempDir.resolve( "dataset.xml" ).toUri();
		assertTrue( new XmlIoSpimData2().save( spimData, xmlURI ) );

		final String xml = new String( Files.readAllBytes( tempDir.resolve( "dataset.xml" ) ) );
		assertTrue( xml.contains( "label=\"beads_r0.5\"" ) );
		assertTrue( xml.contains( "base=\"beads\"" ) );
		assertTrue( xml.contains( "filter=\"response=0.5..\"" ) );
		assertTrue( xml.contains( "base=\"beads_r0.5\"" ) );
		assertTrue( xml.contains( "filter=\"sigma=2..4\"" ) );

		final ViewId first = views.get( 0 );
		final String path = InterestPointsN5.createN5datasetPath( first.getTimePointId(), first.getViewSetupId(), "beads_r0.5" );
		final Key key = Key.of( first, "beads_r0.5" );

		// the marker entry instead of points, the correspondences as for any label
		assertEquals( Boolean.TRUE, store().isFiltered( key ) );
		assertEquals( 0, store().points( key ).size() );
		assertTrue( store().points( key ).filtered() );
		assertNotNull( store().correspondences( key ) );
		assertTrue( store().correspondences( key ).size() > 0 );
		assertEquals( Boolean.FALSE, store().isFiltered( Key.of( first, "beads" ) ) );

		// a plain label that does not know filtered labels fails to load it (no silent empty label)
		assertThrows( RuntimeException.class, () -> new InterestPointsZarr( tempDir.toUri(), path ).getInterestPointsCopy() );

		final SpimData2 loaded = new XmlIoSpimData2().load( xmlURI );

		for ( final ViewId viewId : views )
		{
			final ViewInterestPointLists vipl = loaded.getViewInterestPoints().getViewInterestPointLists( viewId );
			final InterestPoints filtered = vipl.getInterestPointList( "beads_r0.5" );
			final InterestPoints chained = vipl.getInterestPointList( "beads_r0.5_s2-4" );

			assertTrue( filtered instanceof VirtualFilteredInterestPoints );
			assertEquals( "beads", ( (VirtualFilteredInterestPoints)filtered ).getBaseLabel() );
			assertEquals( byResponse, ( (VirtualFilteredInterestPoints)filtered ).getFilter() );
			assertEquals( byResponse.describe( "beads" ), filtered.getParameters() );
			assertEquals( idsPassing( map.get( viewId ), byResponse ), new TreeSet<>( filtered.getInterestPointsCopy().keySet() ) );
			assertEquals( idsPassing( map.get( viewId ), both ), new TreeSet<>( chained.getInterestPointsCopy().keySet() ) );

			final TreeSet< Integer > corrIds = new TreeSet<>();

			for ( final CorrespondingInterestPoints c : filtered.getCorrespondingInterestPointsCopy() )
			{
				assertEquals( "beads_r0.5", c.getCorrespodingLabel() );
				corrIds.add( c.getDetectionId() );
			}

			final TreeSet< Integer > expectedCorr = new TreeSet<>();

			for ( final int id : idsPassing( map.get( viewId ), byResponse ) )
				if ( id % 5 == 0 )
					expectedCorr.add( id );

			assertEquals( expectedCorr, corrIds );
			assertEquals( 0, vipl.getInterestPointList( "beads" ).getCorrespondingInterestPointsCopy().size() );
		}

		// the base is detected again: the filtered label follows
		final HashMap< ViewId, List< InterestPoint > > map2 = new HashMap<>();

		for ( final ViewId viewId : views )
			map2.put( viewId, points( 50, 200 + viewId.getViewSetupId() ) );

		InterestPointTools.addInterestPoints( loaded, "beads", map2, "DOG s=1.8 t=0.01" );

		for ( final ViewId viewId : views )
			assertEquals( idsPassing( map2.get( viewId ), byResponse ), new TreeSet<>( loaded.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "beads_r0.5" ).getInterestPointsCopy().keySet() ) );

		// the base is gone: an error, not an empty label
		final ViewInterestPointLists vipl = loaded.getViewInterestPoints().getViewInterestPointLists( first );
		vipl.getHashMap().remove( "beads" );
		assertThrows( IllegalStateException.class, () -> vipl.getInterestPointList( "beads_r0.5" ).getInterestPointsCopy() );
		assertThrows( IllegalStateException.class, () -> vipl.getInterestPointList( "beads_r0.5_s2-4" ).getInterestPointsCopy() );

		// detached from its view: an error, too
		assertThrows( IllegalStateException.class, () -> new VirtualFilteredInterestPoints( tempDir.toUri(), path, "beads", byResponse ).getInterestPointsCopy() );

		// a plain point list stored under the name of the filtered label: an error
		final InterestPointsZarr plainUnderFilteredName = new InterestPointsZarr( tempDir.toUri(), path );
		plainUnderFilteredName.setInterestPoints( points( 5, 1 ) );
		assertTrue( plainUnderFilteredName.saveInterestPoints( true ) );
		store().commit();
		assertEquals( Boolean.FALSE, store().isFiltered( key ) );

		final SpimData2 loaded2 = new XmlIoSpimData2().load( xmlURI );
		assertThrows( RuntimeException.class, () -> loaded2.getViewInterestPoints().getViewInterestPointLists( first ).getInterestPointList( "beads_r0.5" ).getInterestPointsCopy() );
	}

	/**
	 * A filtered label with a count limit keeps the n brightest per view (the ids of the base), through the XML round trip, too
	 */
	@Test
	public void testFilteredLabelLimit() throws Exception
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		int seed = 0;

		for ( final ViewId viewId : views )
			map.put( viewId, points( 200 + 10 * seed, 300 + seed++ ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.001" );

		final InterestPointFilter brightest = InterestPointFilter.of( Axis.RESPONSE, InterestPointFilter.Limit.HIGHEST, 50 );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_rh50", "beads", brightest, views ) );

		final HashMap< ViewId, TreeSet< Integer > > expected = new HashMap<>();

		for ( final ViewId viewId : views )
		{
			// the 50 largest |response| of the base
			final ArrayList< InterestPoint > base = new ArrayList<>( map.get( viewId ) );
			base.sort( ( a, b ) -> Double.compare( Math.abs( b.getResponse() ), Math.abs( a.getResponse() ) ) );
			final TreeSet< Integer > ids = new TreeSet<>();

			for ( int i = 0; i < 50; ++i )
				ids.add( base.get( i ).getId() );

			expected.put( viewId, ids );

			final Map< Integer, InterestPoint > filtered = spimData.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "beads_rh50" ).getInterestPointsCopy();
			assertEquals( 50, filtered.size() );
			assertEquals( ids, new TreeSet<>( filtered.keySet() ) );
		}

		final URI xmlURI = tempDir.resolve( "dataset.xml" ).toUri();
		assertTrue( new XmlIoSpimData2().save( spimData, xmlURI ) );
		assertTrue( new String( Files.readAllBytes( tempDir.resolve( "dataset.xml" ) ) ).contains( "filter=\"limit=response:highest:50\"" ) );

		final SpimData2 loaded = new XmlIoSpimData2().load( xmlURI );

		for ( final ViewId viewId : views )
		{
			final InterestPoints ips = loaded.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "beads_rh50" );
			assertTrue( ips instanceof VirtualFilteredInterestPoints );
			assertEquals( brightest, ( (VirtualFilteredInterestPoints)ips ).getFilter() );
			assertEquals( expected.get( viewId ), new TreeSet<>( ips.getInterestPointsCopy().keySet() ) );
		}
	}

	/**
	 * Overwriting: only a filtered label that would filter itself is refused, a plain copy can replace anything (the base
	 * label included) and a filtered label anything but its own chain; the overwrite drops the correspondences of other
	 * views that pointed to the old label and the history records that produced it, keeps the dependants; a former
	 * filtered label becomes a plain one, on disk too
	 */
	@Test
	public void testOverwrite() throws Exception
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		Collections.sort( views );
		final ViewId v0 = views.get( 0 ), v1 = views.get( 1 );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();
		int seed = 0;

		for ( final ViewId viewId : views )
			map.put( viewId, points( 200 + 10 * seed, 500 + seed++ ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.001" );

		// a record that produced "beads", (symmetric) correspondences between (v0, beads) and (v1, beads), a filtered label of beads
		ActionHistoryRecorder.record( spimData, "detect-interestpoints", "test", ActionHistoryRecorder.params(), views, "interestpoints:beads" );

		final ArrayList< CorrespondingInterestPoints > corr0 = new ArrayList<>(), corr1 = new ArrayList<>();
		corr0.add( new CorrespondingInterestPoints( 5, v1, "beads", 3 ) );
		corr0.add( new CorrespondingInterestPoints( 6, v1, "beads", 4 ) );
		corr1.add( new CorrespondingInterestPoints( 3, v0, "beads", 5 ) );
		corr1.add( new CorrespondingInterestPoints( 4, v0, "beads", 6 ) );
		spimData.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads" ).setCorrespondingInterestPoints( corr0 );
		spimData.getViewInterestPoints().getViewInterestPointLists( v1 ).getInterestPointList( "beads" ).setCorrespondingInterestPoints( corr1 );

		final InterestPointFilter byResponse = InterestPointFilter.of( Axis.RESPONSE, 0.5, Double.NaN );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_r0.5", "beads", byResponse, views ) );
		final TreeSet< Integer > filteredBefore = new TreeSet<>( spimData.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads_r0.5" ).getInterestPointsCopy().keySet() );

		// only a filtered label that would filter itself is refused (its name is the base, or a label the base is filtered from); a plain copy can replace anything
		final List< ViewId > only0 = Arrays.asList( v0 );
		assertFalse( Filter_Detections.checkNewLabel( spimData, only0, "beads", "beads", false ) );
		assertFalse( Filter_Detections.checkNewLabel( spimData, only0, "beads_r0.5", "beads", false ) ); // beads_r0.5 is filtered from beads
		assertFalse( Filter_Detections.checkNewLabel( spimData, only0, "beads", " ", true ) );
		assertFalse( Filter_Detections.checkNewLabel( spimData, only0, "beads", null, true ) );
		assertTrue( Filter_Detections.checkNewLabel( spimData, only0, "beads", "fresh", false ) );
		assertTrue( Filter_Detections.checkNewLabel( spimData, only0, "beads", "beads_r0.5", false ) ); // a dependant is simply redefined
		assertTrue( Filter_Detections.checkNewLabel( spimData, only0, "beads", "beads", true ) );
		assertTrue( Filter_Detections.checkNewLabel( spimData, only0, "beads_r0.5", "beads", true ) );
		assertThrows( IllegalArgumentException.class, () -> Filter_Detections.create( spimData, only0, "beads", "beads", byResponse, false ) );
		assertThrows( IllegalArgumentException.class, () -> Filter_Detections.create( spimData, only0, "beads_r0.5", "beads", byResponse, false ) );
		assertEquals( map.get( v0 ).size(), spimData.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads" ).getInterestPointsCopy().size() );

		// a filtered label overwritten by another filtered label is redefined (the same base, the new filter)
		final InterestPointFilter byResponse07 = InterestPointFilter.of( Axis.RESPONSE, 0.7, Double.NaN );
		assertTrue( Filter_Detections.create( spimData, only0, "beads", "beads_r0.5", byResponse07, false ) );
		assertEquals( byResponse07, ( (VirtualFilteredInterestPoints)spimData.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads_r0.5" ) ).getFilter() );
		assertEquals( idsPassing( map.get( v0 ), byResponse07 ), new TreeSet<>( spimData.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads_r0.5" ).getInterestPointsCopy().keySet() ) );
		assertTrue( Filter_Detections.create( spimData, only0, "beads", "beads_r0.5", byResponse, false ) ); // and back

		// the base label of v0 replaced by its own points that pass (with their ids), v1 untouched
		final TreeSet< Integer > expected = idsPassing( map.get( v0 ), byResponse );
		assertTrue( expected.size() > 0 && expected.size() < map.get( v0 ).size() );
		assertTrue( Filter_Detections.create( spimData, only0, "beads", "beads", byResponse, true ) );

		final InterestPoints beads0 = spimData.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads" );
		assertFalse( beads0 instanceof VirtualFilteredInterestPoints );
		assertEquals( expected, new TreeSet<>( beads0.getInterestPointsCopy().keySet() ) );
		assertEquals( map.get( v1 ).size(), spimData.getViewInterestPoints().getViewInterestPointLists( v1 ).getInterestPointList( "beads" ).getInterestPointsCopy().size() );

		// the correspondences of v1 that pointed to (v0, beads) are gone (the mirrors of the old label's own), as are the old label's, the old record too (the new label has its filter record)
		assertEquals( 0, spimData.getViewInterestPoints().getViewInterestPointLists( v1 ).getInterestPointList( "beads" ).getCorrespondingInterestPointsCopy().size() );
		assertEquals( 0, beads0.getCorrespondingInterestPointsCopy().size() );
		final List< ActionRecord > records = spimData.getActionHistory().getRecords();
		assertEquals( 1, records.stream().filter( r -> "interestpoints:beads".equals( r.getResultRef() ) ).count() );
		assertEquals( "filter-interestpoints", records.stream().filter( r -> "interestpoints:beads".equals( r.getResultRef() ) ).findFirst().get().getActionId() );

		// the dependant follows the new points (the same ids as before, the filter is idempotent)
		assertEquals( filteredBefore, new TreeSet<>( spimData.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads_r0.5" ).getInterestPointsCopy().keySet() ) );

		// a filtered label overwritten by a plain copy becomes a plain label, on disk too
		final InterestPointFilter bySigma = InterestPointFilter.of( Axis.SIGMA, 2, 4 );
		assertTrue( Filter_Detections.create( spimData, views, "beads", "beads_r0.5", bySigma, true ) );

		for ( final ViewId viewId : views )
			assertFalse( spimData.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "beads_r0.5" ) instanceof VirtualFilteredInterestPoints );

		final URI xmlURI = tempDir.resolve( "dataset.xml" ).toUri();
		assertTrue( new XmlIoSpimData2().save( spimData, xmlURI ) );

		final String xml = new String( Files.readAllBytes( tempDir.resolve( "dataset.xml" ) ) );
		assertTrue( xml.contains( "label=\"beads_r0.5\"" ) );
		assertFalse( xml.contains( "base=\"beads\"" ) );
		assertFalse( xml.contains( "filter=" ) );

		final Key key = Key.of( v0, "beads_r0.5" );
		assertEquals( Boolean.FALSE, store().isFiltered( key ) );
		assertTrue( store().points( key ).size() > 0 );

		final SpimData2 loaded = new XmlIoSpimData2().load( xmlURI );
		final InterestPoints reloaded = loaded.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads_r0.5" );
		assertFalse( reloaded instanceof VirtualFilteredInterestPoints );
		assertEquals( idsPassing( new ArrayList<>( beads0.getInterestPointsCopy().values() ), bySigma ), new TreeSet<>( reloaded.getInterestPointsCopy().keySet() ) );
	}

	/**
	 * The save of a Spark task (saveStaged) treats plain and filtered labels alike: the points for one, the marker for the
	 * other, the correspondences for both; what the XML loader then sees is the filtered base and the own correspondences
	 */
	@Test
	public void testSaveStagedBothKinds()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();

		for ( final ViewId viewId : views )
			map.put( viewId, points( 60, 600 + viewId.getViewSetupId() ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.001" );

		final InterestPointFilter bySigma = InterestPointFilter.of( Axis.SIGMA, 3, 10 );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_s3-10", "beads", bySigma, views ) );

		final ViewId first = views.get( 0 ), second = views.get( 1 );
		final ArrayList< CorrespondingInterestPoints > corr = new ArrayList<>();
		corr.add( new CorrespondingInterestPoints( 3, second, "beads_s3-10", 7 ) );
		corr.add( new CorrespondingInterestPoints( 9, second, "beads_s3-10", 1 ) );
		spimData.getViewInterestPoints().getViewInterestPointLists( first ).getInterestPointList( "beads_s3-10" ).setCorrespondingInterestPoints( corr );

		final ArrayList< InterestPoints > all = new ArrayList<>();
		for ( final ViewId viewId : views )
			all.addAll( spimData.getViewInterestPoints().getViewInterestPointLists( viewId ).getHashMap().values() );
		assertEquals( 2 * views.size(), all.size() );

		InterestPointsZarr.saveStaged( all );

		final String path = InterestPointsN5.createN5datasetPath( first.getTimePointId(), first.getViewSetupId(), "beads_s3-10" );
		final String basePath = InterestPointsN5.createN5datasetPath( first.getTimePointId(), first.getViewSetupId(), "beads" );

		for ( int round = 0; round < 2; ++round )
		{
			assertEquals( Boolean.TRUE, store().isFiltered( Key.of( first, "beads_s3-10" ) ) );
			assertEquals( Boolean.FALSE, store().isFiltered( Key.of( first, "beads" ) ) );
			assertEquals( 2, store().correspondences( Key.of( first, "beads_s3-10" ) ).size() );

			// what the XML loader would see: the points of the filtered label are the filtered base, its correspondences are its own
			final ViewInterestPointLists vipl = new ViewInterestPointLists( first.getTimePointId(), first.getViewSetupId() );
			vipl.addInterestPointList( "beads", InterestPoints.instantiatefromXML( tempDir.toUri(), basePath, null, null ) );
			vipl.addInterestPointList( "beads_s3-10", InterestPoints.instantiatefromXML( tempDir.toUri(), path, "beads", bySigma.toString() ) );

			assertEquals( idsPassing( map.get( first ), bySigma ), new TreeSet<>( vipl.getInterestPointList( "beads_s3-10" ).getInterestPointsCopy().keySet() ) );
			assertEquals( 2, vipl.getInterestPointList( "beads_s3-10" ).getCorrespondingInterestPointsCopy().size() );
			assertEquals( 60, vipl.getInterestPointList( "beads" ).getInterestPointsCopy().size() );

			if ( round == 0 )
				store().commit();
		}
	}

	/**
	 * copyTo recreates a label elsewhere without the caller knowing its kind: a plain label copies its (mapped) points, a
	 * filtered label stays a filter of the mapped base label (what splitting and copying a dataset need)
	 */
	@Test
	public void testCopyTo() throws Exception
	{
		final URI base = tempDir.toUri();
		final ViewId from = new ViewId( 0, 10 ), to = new ViewId( 0, 11 );
		final ArrayList< InterestPoint > points = points( 100, 500 );

		final ViewInterestPointLists vipl = new ViewInterestPointLists( from.getTimePointId(), from.getViewSetupId() );

		final InterestPointFilter filter = InterestPointFilter.of( Axis.RESPONSE, 0.5, Double.NaN );

		// three correspondences (to the next id in another view): a point inside the split interval (x < 250) that passes the
		// filter, one inside that does not, one outside that passes; the filtered label links the two that pass its filter
		int inPass = -1, inFail = -1, outPass = -1;

		for ( final InterestPoint ip : points )
		{
			final boolean in = ip.getL()[ 0 ] < 250, pass = filter.accept( ip );

			if ( in && pass && inPass < 0 ) inPass = ip.getId();
			if ( in && !pass && inFail < 0 ) inFail = ip.getId();
			if ( !in && pass && outPass < 0 ) outPass = ip.getId();
		}

		assertTrue( inPass >= 0 && inFail >= 0 && outPass >= 0 );

		final ArrayList< CorrespondingInterestPoints > corr = new ArrayList<>();
		for ( final int id : new int[] { inPass, inFail, outPass } )
			corr.add( new CorrespondingInterestPoints( id, to, "beads", id + 1 ) );

		final InterestPoints plain = InterestPoints.newInstance( base, from, "beads" );
		plain.setParameters( "DOG s=1.8" );
		plain.setInterestPoints( points );
		plain.setCorrespondingInterestPoints( corr );
		vipl.addInterestPointList( "beads", plain );

		final VirtualFilteredInterestPoints virtual = VirtualFilteredInterestPoints.create( base, from, "beads_r0.5", "beads", filter );
		virtual.setParameters( filter.describe( "beads" ) );
		virtual.setCorrespondingInterestPoints( new ArrayList<>( Arrays.asList( corr.get( 0 ), corr.get( 2 ) ) ) );
		vipl.addInterestPointList( "beads_r0.5", virtual );

		// as splitting does: the points with x < 250 shifted by -10, labels renamed, no correspondences
		final Function< InterestPoint, InterestPoint > inside = ip -> ip.getL()[ 0 ] < 250 ? ip.derive( ip.getId(), new double[] { ip.getL()[ 0 ] - 10, ip.getL()[ 1 ], ip.getL()[ 2 ] } ) : null;
		final ViewInterestPointLists split = new ViewInterestPointLists( to.getTimePointId(), to.getViewSetupId() );

		for ( final String label : vipl.getHashMap().keySet() )
			split.addInterestPointList( label + "_split", vipl.getInterestPointList( label ).copyTo( base, to, label + "_split", l -> l + "_split", inside, false ) );

		final InterestPoints plainSplit = split.getInterestPointList( "beads_split" );
		final InterestPoints virtualSplit = split.getInterestPointList( "beads_r0.5_split" );

		assertFalse( plainSplit instanceof VirtualFilteredInterestPoints );
		assertTrue( virtualSplit instanceof VirtualFilteredInterestPoints );
		assertEquals( "beads_split", ( (VirtualFilteredInterestPoints)virtualSplit ).getBaseLabel() );
		assertEquals( filter, ( (VirtualFilteredInterestPoints)virtualSplit ).getFilter() );
		assertEquals( filter.describe( "beads_split" ), virtualSplit.getParameters() );
		assertEquals( "DOG s=1.8", plainSplit.getParameters() );
		assertEquals( 0, plainSplit.getCorrespondingInterestPointsCopy().size() );
		assertEquals( 0, virtualSplit.getCorrespondingInterestPointsCopy().size() );
		assertEquals( base, virtualSplit.getBasePath() );

		final TreeSet< Integer > expectedPlain = new TreeSet<>(), expectedVirtual = new TreeSet<>();

		for ( final InterestPoint ip : points )
			if ( ip.getL()[ 0 ] < 250 )
			{
				expectedPlain.add( ip.getId() );

				if ( filter.accept( ip ) )
					expectedVirtual.add( ip.getId() );
			}

		assertTrue( expectedVirtual.size() > 0 && expectedVirtual.size() < expectedPlain.size() && expectedPlain.size() < points.size() );

		final Map< Integer, InterestPoint > plainPoints = plainSplit.getInterestPointsCopy();
		assertEquals( expectedPlain, new TreeSet<>( plainPoints.keySet() ) );

		for ( final InterestPoint ip : plainPoints.values() )
		{
			final InterestPoint original = points.get( ip.getId() );
			assertEquals( original.getL()[ 0 ] - 10, ip.getL()[ 0 ], 1e-3 );
			assertEquals( original.getL()[ 1 ], ip.getL()[ 1 ], 1e-3 );
			assertEquals( original.getResponse(), ip.getResponse(), 0.0f );
			assertEquals( original.getSigma(), ip.getSigma(), 0.0f );
			assertEquals( original.getIntensity(), ip.getIntensity(), 0.0f );
		}

		assertEquals( expectedVirtual, new TreeSet<>( virtualSplit.getInterestPointsCopy().keySet() ) );

		// the same copy with the correspondences on: only the ones of points that survive remain (the correspondences
		// of dropped points would link to points that do not exist), their label follows the mapping, the other end is untouched
		final InterestPoints plainCorr = plain.copyTo( base, to, "beads_split", l -> l + "_split", inside, true );
		final InterestPoints virtualCorr = virtual.copyTo( base, to, "beads_r0.5_split", l -> l + "_split", inside, true );

		assertEquals( new TreeSet<>( Arrays.asList( inPass, inFail ) ), detectionIds( plainCorr.getCorrespondingInterestPointsCopy() ) );
		assertEquals( new TreeSet<>( Arrays.asList( inPass ) ), detectionIds( virtualCorr.getCorrespondingInterestPointsCopy() ) );

		for ( final InterestPoints ips : Arrays.asList( plainCorr, virtualCorr ) )
			for ( final CorrespondingInterestPoints c : ips.getCorrespondingInterestPointsCopy() )
			{
				assertEquals( "beads_split", c.getCorrespodingLabel() );
				assertEquals( to, c.getCorrespondingViewId() );
				assertEquals( c.getDetectionId() + 1, c.getCorrespondingDetectionId() );
				assertEquals( -1, c.getConsensusSetId() );
			}

		// renumbered points: the detection ids of the correspondences follow the points, nothing else changes
		final Function< InterestPoint, InterestPoint > renumber = ip -> ip.derive( ip.getId() + 1000, ip.getL() );
		final InterestPoints plainRenumbered = plain.copyTo( base, from, "beads", l -> l, renumber, true );
		final InterestPoints virtualRenumbered = virtual.copyTo( base, from, "beads_r0.5", l -> l, renumber, true );

		assertEquals( new TreeSet<>( Arrays.asList( inPass + 1000, inFail + 1000, outPass + 1000 ) ), detectionIds( plainRenumbered.getCorrespondingInterestPointsCopy() ) );
		assertEquals( new TreeSet<>( Arrays.asList( inPass + 1000, outPass + 1000 ) ), detectionIds( virtualRenumbered.getCorrespondingInterestPointsCopy() ) );

		for ( final CorrespondingInterestPoints c : plainRenumbered.getCorrespondingInterestPointsCopy() )
		{
			assertTrue( plainRenumbered.getInterestPointsCopy().containsKey( c.getDetectionId() ) );
			assertEquals( "beads", c.getCorrespodingLabel() );
			assertEquals( c.getDetectionId() - 1000 + 1, c.getCorrespondingDetectionId() );
		}

		// as a dataset copy does: identity, another base path, with correspondences
		final Path other = Files.createDirectories( tempDir.resolve( "copyTo" ) );
		final ViewInterestPointLists copy = new ViewInterestPointLists( from.getTimePointId(), from.getViewSetupId() );

		for ( final String label : vipl.getHashMap().keySet() )
			copy.addInterestPointList( label, vipl.getInterestPointList( label ).copyTo( other.toUri(), from, label, l -> l, ip -> ip, true ) );

		assertEquals( 3, copy.getInterestPointList( "beads" ).getCorrespondingInterestPointsCopy().size() );
		assertEquals( 2, copy.getInterestPointList( "beads_r0.5" ).getCorrespondingInterestPointsCopy().size() );
		assertEquals( 100, copy.getInterestPointList( "beads" ).getInterestPointsCopy().size() );
		assertEquals( idsPassing( points, filter ), new TreeSet<>( copy.getInterestPointList( "beads_r0.5" ).getInterestPointsCopy().keySet() ) );
		assertEquals( other.toUri(), copy.getInterestPointList( "beads_r0.5" ).getBasePath() );
		assertEquals( filter.describe( "beads" ), copy.getInterestPointList( "beads_r0.5" ).getParameters() );
	}

	/**
	 * A plain copy of the points that pass a filter keeps the ids and the attributes
	 */
	@Test
	public void testFilteredLabelCopy()
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();

		for ( final ViewId viewId : views )
			map.put( viewId, points( 100, 300 + viewId.getViewSetupId() ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.001" );

		final InterestPointFilter byIntensity = InterestPointFilter.of( Axis.INTENSITY, 400, 800 );
		assertTrue( InterestPointTools.addFilteredInterestPointsCopy( spimData, "bright", "beads", byIntensity, views ) );

		for ( final ViewId viewId : views )
		{
			final InterestPoints copy = spimData.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "bright" );

			assertFalse( copy instanceof VirtualFilteredInterestPoints );
			assertEquals( byIntensity.describe( "beads" ) + " (copied)", copy.getParameters() );

			final Map< Integer, InterestPoint > points = copy.getInterestPointsCopy();
			assertEquals( idsPassing( map.get( viewId ), byIntensity ), new TreeSet<>( points.keySet() ) );

			for ( final InterestPoint ip : points.values() )
				assertSameAttributes( map.get( viewId ).get( ip.getId() ), ip );
		}

		// a missing base label is an error
		assertThrows( IllegalArgumentException.class, () -> InterestPointTools.addFilteredInterestPoints( spimData, "x", "nothere", byIntensity, views ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointTools.addFilteredInterestPointsCopy( spimData, "x", "nothere", byIntensity, views ) );
		assertThrows( IllegalArgumentException.class, () -> InterestPointTools.addFilteredInterestPoints( spimData, "beads", "beads", byIntensity, views ) );
	}

	/**
	 * Copying the data to another base path (SpimData2Tools.reduceSpimData2) keeps a filtered label filtered
	 */
	@Test
	public void testFilteredLabelReduce() throws Exception
	{
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );

		final List< ViewId > views = new ArrayList<>( spimData.getSequenceDescription().getViewDescriptions().keySet() );
		final HashMap< ViewId, List< InterestPoint > > map = new HashMap<>();

		for ( final ViewId viewId : views )
			map.put( viewId, points( 100, 400 + viewId.getViewSetupId() ) );

		InterestPointTools.addInterestPoints( spimData, "beads", map, "DOG s=1.8 t=0.001" );

		final InterestPointFilter byResponse = InterestPointFilter.of( Axis.RESPONSE, 0.25, 0.75 );
		assertTrue( InterestPointTools.addFilteredInterestPoints( spimData, "beads_r0.25-0.75", "beads", byResponse, views ) );

		final Path other = Files.createDirectories( tempDir.resolve( "copy" ) );
		final SpimData2 reduced = SpimData2Tools.reduceSpimData2( spimData, views, other.toUri() );

		for ( final ViewId viewId : views )
		{
			final InterestPoints filtered = reduced.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "beads_r0.25-0.75" );

			assertTrue( filtered instanceof VirtualFilteredInterestPoints );
			assertEquals( "beads", ( (VirtualFilteredInterestPoints)filtered ).getBaseLabel() );
			assertEquals( byResponse, ( (VirtualFilteredInterestPoints)filtered ).getFilter() );
			assertEquals( byResponse.describe( "beads" ), filtered.getParameters() );
			assertEquals( other.toUri(), filtered.getBasePath() );
			assertEquals( idsPassing( map.get( viewId ), byResponse ), new TreeSet<>( filtered.getInterestPointsCopy().keySet() ) );
			assertEquals( "DOG s=1.8 t=0.001", reduced.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "beads" ).getParameters() );
		}

		// and it saves to the new place
		final URI xmlURI = other.resolve( "dataset.xml" ).toUri();
		assertTrue( new XmlIoSpimData2().save( reduced, xmlURI ) );
		assertTrue( new File( other.toFile(), InterestPointsZarrStore.ZARR_CONTAINER ).isDirectory() );
		assertFalse( new File( tempDir.toFile(), InterestPointsZarrStore.ZARR_CONTAINER ).exists() );

		final SpimData2 loaded = new XmlIoSpimData2().load( xmlURI );

		for ( final ViewId viewId : views )
			assertEquals( idsPassing( map.get( viewId ), byResponse ), new TreeSet<>( loaded.getViewInterestPoints().getViewInterestPointLists( viewId ).getInterestPointList( "beads_r0.25-0.75" ).getInterestPointsCopy().keySet() ) );
	}

	/**
	 * The static list round trip BigStitcher-Spark uses for its temporary blocks: points with attributes in, the same
	 * points in the same order out; an empty list; a missing group and a filtered marker are refused
	 */
	@Test
	public void testStaticListRoundTrip()
	{
		final ArrayList< InterestPoint > points = new ArrayList<>();
		points.add( new InterestPoint( 7, new double[] { 1.5, 2.5, 3.5 }, -0.25f, 1.8f, 120f ) );
		points.add( new InterestPoint( 3, new double[] { 10, 20, 30 }, 0.5f, Float.NaN, Float.NaN ) );
		points.add( new InterestPoint( 5, new double[] { 0, 0, 0 }, Float.NaN, 3.6f, 0f ) );

		final String n5path = "spark_tmp_test/tp0_setup1_block_0_0_0";

		try ( final N5Writer n5 = openN5Writer() )
		{
			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, n5path, points ) );

			final ArrayList< InterestPoint > loaded = InterestPointsN5.loadInterestPointsStatic( n5, n5path );
			assertEquals( points.size(), loaded.size() );

			for ( int i = 0; i < points.size(); ++i )
			{
				assertEquals( points.get( i ).getId(), loaded.get( i ).getId() );
				assertArrayEquals( points.get( i ).getL(), loaded.get( i ).getL(), 0.0 );
				assertEquals( points.get( i ).getResponse(), loaded.get( i ).getResponse(), 0.0f );
				assertEquals( points.get( i ).getSigma(), loaded.get( i ).getSigma(), 0.0f );
				assertEquals( points.get( i ).getIntensity(), loaded.get( i ).getIntensity(), 0.0f );
			}

			assertTrue( InterestPointsN5.saveInterestPointsStatic( n5, n5path + "_empty", new ArrayList< InterestPoint >() ) );
			assertEquals( 0, InterestPointsN5.loadInterestPointsStatic( n5, n5path + "_empty" ).size() );

			assertThrows( RuntimeException.class, () -> InterestPointsN5.loadInterestPointsStatic( n5, n5path + "_missing" ) );

			InterestPointsN5.saveFilteredMarkerStatic( n5, n5path + "_filtered", "beads", InterestPointFilter.fromString( "response=0.1.." ) );
			assertThrows( RuntimeException.class, () -> InterestPointsN5.loadInterestPointsStatic( n5, n5path + "_filtered" ) );
		}

		// the temporary groups are no entries of a label: the conversion ignores them
		assertEquals( 0, InterestPointsN5ToZarr.convert( tempDir.toUri() ) );
	}
}
