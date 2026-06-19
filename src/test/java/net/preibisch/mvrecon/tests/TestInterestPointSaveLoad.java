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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.models.AffineModel3D;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;

public class TestInterestPointSaveLoad
{
	@TempDir
	Path tempDir;

	/**
	 * Overridable save method. Default uses XmlIoSpimData2.save().
	 * Spark-based tests can override this to use distributed saving.
	 */
	protected void saveSpimData( final SpimData2 spimData, final URI xmlURI )
	{
		new XmlIoSpimData2().save( spimData, xmlURI );
	}

	@Test
	public void testInterestPointSaveLoadRoundtrip() throws SpimDataException
	{
		// 1. Setup: create data and detect interest points (no registration yet)
		final SpimData2 spimData = SimulateUtil.setUp();
		spimData.setBasePathURI( tempDir.toUri() );
		TestInterestPointDetection.testDoG( spimData, "beads" );

		// 2. Snapshot interest points before saving
		final Map< String, Map< Integer, double[] > > originalPoints = new HashMap<>();
		snapshotInterestPoints( spimData, originalPoints );
		assertFalse( originalPoints.isEmpty(), "Should have interest points" );

		// 3. Save (interest points only, no correspondences yet)
		final URI xmlURI = tempDir.resolve( "dataset.xml" ).toUri();
		saveSpimData( spimData, xmlURI );

		// 4. Load back from disk
		final SpimData2 loaded = new XmlIoSpimData2().load( xmlURI );

		// 5. Verify interest points match snapshot
		verifyInterestPoints( loaded, originalPoints );

		// 6. Verify DoG detection results on loaded data
		TestInterestPointDetection.assertDoGResults( loaded, "beads" );

		// 7. Run registration on loaded data and verify matrices (same as testRegistrationUngrouped)
		final HashMap< ViewId, AffineModel3D > models = TestRegistration.testRegistration( loaded, "beads", false );

		final double[] t0 = new double[ 12 ];
		final double[] t1 = new double[ 12 ];
		final double[] t2 = new double[ 12 ];

		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 0 ) ) ).toArray( t0 );
		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 1 ) ) ).toArray( t1 );
		TransformationTools.getAffineTransform( models.get( new ViewId( 0, 2 ) ) ).toArray( t2 );

		final double[] t0Expected = new double[] { 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0 };
		final double[] t1Expected = new double[] { 1.0005356080585022, 2.49323474194707E-4, 0.0011220806217312473, -0.08259295862724232, 3.707245398563569E-4, 0.7072946129789373, 0.7076828581581442, 1.404856820272709, -2.63984069522126E-4, -0.7068158048729596, 0.7066541400023975, 51.581476626562484 };
		final double[] t2Expected = new double[] { 0.9998575301003715, 5.069732655624262E-4, -0.0010818832033704928, -0.020219824841918498, 4.6017605438656434E-4, 0.001134937523741015, 1.0023411774701763, 38.829895250234046, -1.7586056994376082E-4, -0.9980690338273157, 9.698915385629309E-4, 86.85082710148747 };

		assertArrayEquals( t0Expected, t0, SimulateUtil.delta, "matrix after global opt should have specific values." );
		assertArrayEquals( t1Expected, t1, SimulateUtil.delta, "matrix after global opt should have specific values." );
		assertArrayEquals( t2Expected, t2, SimulateUtil.delta, "matrix after global opt should have specific values." );

		// 8. Snapshot correspondences (created by registration in step 7)
		final Map< String, List< int[] > > originalCorrespondences = new HashMap<>();
		snapshotCorrespondences( loaded, originalCorrespondences );
		assertTrue( originalCorrespondences.values().stream().anyMatch( l -> !l.isEmpty() ),
			"Should have correspondences after registration" );

		// 9. Save again (now with correspondences and registration transforms)
		final URI xmlURI2 = tempDir.resolve( "dataset2.xml" ).toUri();
		saveSpimData( loaded, xmlURI2 );

		// 10. Load and verify correspondences roundtrip
		final SpimData2 loaded2 = new XmlIoSpimData2().load( xmlURI2 );
		verifyCorrespondences( loaded2, originalCorrespondences );
	}

	protected static void snapshotInterestPoints(
			final SpimData2 spimData,
			final Map< String, Map< Integer, double[] > > originalPoints )
	{
		for ( final Entry< ViewId, ViewInterestPointLists > entry :
				spimData.getViewInterestPoints().getViewInterestPoints().entrySet() )
		{
			final ViewId viewId = entry.getKey();

			for ( final Entry< String, InterestPoints > labelEntry :
					entry.getValue().getHashMap().entrySet() )
			{
				final String label = labelEntry.getKey();
				final String key = viewId.getTimePointId() + "_" + viewId.getViewSetupId() + "_" + label;

				final Map< Integer, double[] > pts = new HashMap<>();
				for ( final Entry< Integer, InterestPoint > e : labelEntry.getValue().getInterestPointsCopy().entrySet() )
					pts.put( e.getKey(), e.getValue().getL().clone() );
				originalPoints.put( key, pts );
			}
		}
	}

	protected static void snapshotCorrespondences(
			final SpimData2 spimData,
			final Map< String, List< int[] > > originalCorrespondences )
	{
		for ( final Entry< ViewId, ViewInterestPointLists > entry :
				spimData.getViewInterestPoints().getViewInterestPoints().entrySet() )
		{
			final ViewId viewId = entry.getKey();

			for ( final Entry< String, InterestPoints > labelEntry :
					entry.getValue().getHashMap().entrySet() )
			{
				final String label = labelEntry.getKey();
				final String key = viewId.getTimePointId() + "_" + viewId.getViewSetupId() + "_" + label;

				final List< int[] > corrs = new ArrayList<>();
				for ( final CorrespondingInterestPoints cip : labelEntry.getValue().getCorrespondingInterestPointsCopy() )
					corrs.add( new int[] {
						cip.getDetectionId(),
						cip.getCorrespondingDetectionId(),
						cip.getCorrespondingViewId().getTimePointId(),
						cip.getCorrespondingViewId().getViewSetupId(),
						cip.getConsensusSetId() } );

				corrs.sort( Comparator.comparingInt( ( int[] a ) -> a[ 0 ] )
					.thenComparingInt( a -> a[ 1 ] )
					.thenComparingInt( a -> a[ 2 ] )
					.thenComparingInt( a -> a[ 3 ] ) );
				originalCorrespondences.put( key, corrs );
			}
		}
	}

	protected static void verifyInterestPoints(
			final SpimData2 loaded,
			final Map< String, Map< Integer, double[] > > originalPoints )
	{
		for ( final Entry< String, Map< Integer, double[] > > entry : originalPoints.entrySet() )
		{
			final String key = entry.getKey();
			final String[] parts = key.split( "_" );
			final ViewId viewId = new ViewId( Integer.parseInt( parts[ 0 ] ), Integer.parseInt( parts[ 1 ] ) );
			final String label = parts[ 2 ];

			final InterestPoints loadedIps = loaded.getViewInterestPoints()
				.getViewInterestPointLists( viewId ).getInterestPointList( label );
			assertNotNull( loadedIps, "Loaded IPs should exist for " + key );

			final Map< Integer, InterestPoint > loadedMap = loadedIps.getInterestPointsCopy();
			assertEquals( entry.getValue().size(), loadedMap.size(),
				"Point count for " + key );

			for ( final Entry< Integer, double[] > pt : entry.getValue().entrySet() )
			{
				final InterestPoint loadedPt = loadedMap.get( pt.getKey() );
				assertNotNull( loadedPt, "Point " + pt.getKey() + " should exist" );
				assertArrayEquals( pt.getValue(), loadedPt.getL(),
					"Coordinates for point " + pt.getKey() + " in " + key );
			}
		}
	}

	protected static void verifyCorrespondences(
			final SpimData2 loaded,
			final Map< String, List< int[] > > originalCorrespondences )
	{
		for ( final Entry< String, List< int[] > > entry : originalCorrespondences.entrySet() )
		{
			final String key = entry.getKey();
			final String[] parts = key.split( "_" );
			final ViewId viewId = new ViewId( Integer.parseInt( parts[ 0 ] ), Integer.parseInt( parts[ 1 ] ) );
			final String label = parts[ 2 ];

			final InterestPoints loadedIps = loaded.getViewInterestPoints()
				.getViewInterestPointLists( viewId ).getInterestPointList( label );

			final List< int[] > loadedCorrs = new ArrayList<>();
			for ( final CorrespondingInterestPoints cip : loadedIps.getCorrespondingInterestPointsCopy() )
				loadedCorrs.add( new int[] {
					cip.getDetectionId(),
					cip.getCorrespondingDetectionId(),
					cip.getCorrespondingViewId().getTimePointId(),
					cip.getCorrespondingViewId().getViewSetupId(),
					cip.getConsensusSetId() } );
			loadedCorrs.sort( Comparator.comparingInt( ( int[] a ) -> a[ 0 ] )
				.thenComparingInt( a -> a[ 1 ] )
				.thenComparingInt( a -> a[ 2 ] )
				.thenComparingInt( a -> a[ 3 ] ) );

			final List< int[] > origCorrs = entry.getValue();
			assertEquals( origCorrs.size(), loadedCorrs.size(),
				"Correspondence count for " + key );

			for ( int i = 0; i < origCorrs.size(); i++ )
				assertArrayEquals( origCorrs.get( i ), loadedCorrs.get( i ),
					"Correspondence " + i + " for " + key );
		}
	}
}
