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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ij.Macro;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.SimulateUtil;
import net.preibisch.mvrecon.fiji.plugin.Embed_Interest_Points;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.RGLDMGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.InterestPointTableModel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint.MissingInterestPointsDialog;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondenceTools;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointFilter;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarr;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.MissingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointembedding.InterestPointEmbedding;

/**
 * Interest points the dataset lists without data ({@link MissingInterestPoints}): an XML that outlived its
 * {@code interestpoints.zarr}, or the entries of some views. Reading them throws; they are found without reading, the
 * Interest Point Explorer's table shows them as missing instead of failing while it is painted, the embedding and the
 * registration stop with a message, and they can be removed from the dataset together with what refers to them.
 */
public class TestMissingInterestPoints
{
	@TempDir
	Path tempDir;

	static final String DOG = "DOG s=1.8 t=0.008 min=false max=true downsampleX=2 downsampleY=2 downsampleZ=1 anisotropy=1.0 minIntensity=0.0 maxIntensity=255.0";

	static List< ViewId > views( final SpimData2 data )
	{
		final ArrayList< ViewId > views = new ArrayList<>();
		for ( final ViewId v : data.getSequenceDescription().getViewDescriptions().keySet() )
			views.add( new ViewId( v.getTimePointId(), v.getViewSetupId() ) );
		Collections.sort( views );
		return views;
	}

	/** a dataset with the labels "beads" (a detection) and "manual" (no detection parameters) in every view, not saved */
	static SpimData2 dataset( final Path dir )
	{
		final SpimData2 data = SimulateUtil.setUp();
		data.setBasePathURI( dir.toUri() );

		final HashMap< ViewId, List< InterestPoint > > beads = new HashMap<>(), manual = new HashMap<>();
		int seed = 0;

		for ( final ViewId v : views( data ) )
		{
			beads.put( v, TestInterestPointStorage.points( 40, seed++ ) );
			manual.put( v, TestInterestPointStorage.points( 10, seed++ ) );
		}

		InterestPointTools.addInterestPoints( data, "beads", beads, DOG );
		InterestPointTools.addInterestPoints( data, "manual", manual, "manually placed" );

		return data;
	}

	static void connect( final SpimData2 data, final ViewId a, final ViewId b, final int n )
	{
		final InterestPoints listA = data.getViewInterestPoints().getViewInterestPointLists( a ).getInterestPointList( "beads" );
		final InterestPoints listB = data.getViewInterestPoints().getViewInterestPointLists( b ).getInterestPointList( "beads" );
		final List< CorrespondingInterestPoints > corrA = new ArrayList<>( listA.getCorrespondingInterestPointsCopy() );
		final List< CorrespondingInterestPoints > corrB = new ArrayList<>( listB.getCorrespondingInterestPointsCopy() );

		for ( int i = 0; i < n; ++i )
		{
			corrA.add( new CorrespondingInterestPoints( i, b, "beads", i + 1 ) );
			corrB.add( new CorrespondingInterestPoints( i + 1, a, "beads", i ) );
		}

		listA.setCorrespondingInterestPoints( corrA );
		listB.setCorrespondingInterestPoints( corrB );
	}

	static Set< String > labels( final SpimData2 data, final ViewId view )
	{
		return new TreeSet<>( data.getViewInterestPoints().getViewInterestPointLists( view ).getHashMap().keySet() );
	}

	/** the dataset saved in one directory, its XML alone copied to another and loaded from there: every label is listed, nothing is stored */
	SpimData2 xmlWithoutStore() throws Exception
	{
		final Path complete = Files.createDirectories( tempDir.resolve( "complete" ) );
		final Path xmlOnly = Files.createDirectories( tempDir.resolve( "xmlonly" ) );

		final SpimData2 data = dataset( complete );
		InterestPointTools.addFilteredInterestPoints( data, "beads_f", "beads", InterestPointFilter.fromString( "intensity=500.." ), views( data ) );
		new XmlIoSpimData2().save( data, complete.resolve( "dataset.xml" ).toUri() );

		Files.copy( complete.resolve( "dataset.xml" ), xmlOnly.resolve( "dataset.xml" ) );
		return new XmlIoSpimData2().load( xmlOnly.resolve( "dataset.xml" ).toUri() );
	}

	@Test
	public void xmlThatOutlivedItsStore() throws Exception
	{
		final SpimData2 data = xmlWithoutStore();
		final ViewInterestPoints vip = data.getViewInterestPoints();
		final List< ViewId > views = views( data );
		final URI xml = tempDir.resolve( "xmlonly" ).resolve( "dataset.xml" ).toUri();

		// what every reader ran into
		final InterestPoints first = vip.getViewInterestPointLists( views.get( 0 ) ).getInterestPointList( "beads" );
		assertTrue( assertThrows( RuntimeException.class, () -> first.getInterestPointsCopy() ).getMessage().contains( "although the XML lists the label" ) );
		assertThrows( RuntimeException.class, () -> first.getCorrespondingInterestPointsCopy() );

		// found without reading: every label in every view; a filtered label by its own entry
		SortedMap< String, List< ViewId > > missing = MissingInterestPoints.find( vip, views, null );
		assertEquals( Arrays.asList( "beads", "beads_f", "manual" ), new ArrayList<>( missing.keySet() ) );
		for ( final List< ViewId > ofLabel : missing.values() )
			assertEquals( views, ofLabel );

		// a filtered label is looked at together with the label it filters; one view alone
		assertEquals( Set.of( "beads", "beads_f" ), MissingInterestPoints.find( vip, views, Collections.singletonList( "beads_f" ) ).keySet() );
		assertEquals( Map.of( "manual", views.subList( 1, 2 ) ), MissingInterestPoints.find( vip, views.subList( 1, 2 ), Collections.singletonList( "manual" ) ) );
		assertTrue( MissingInterestPoints.find( vip, views, Collections.singletonList( "no such label" ) ).isEmpty() );

		final List< String > lines = MissingInterestPoints.describe( vip, MissingInterestPoints.find( vip, views, Collections.singletonList( "beads" ) ) );
		assertEquals( 1, lines.size() );
		assertTrue( lines.get( 0 ).startsWith( "No interest points are stored for 'beads' in " + views.size() + " view(s) (neither in interestpoints.zarr nor in interestpoints.n5 of '" ), lines.get( 0 ) );

		// a label that only exists in memory so far is not missing
		final HashMap< ViewId, List< InterestPoint > > fresh = new HashMap<>();
		fresh.put( views.get( 0 ), TestInterestPointStorage.points( 5, 99 ) );
		InterestPointTools.addInterestPoints( data, "fresh", fresh, DOG );
		assertFalse( MissingInterestPoints.find( vip, views, null ).containsKey( "fresh" ) );
		assertTrue( InterestPointsZarr.withoutData( Collections.singletonList( vip.getViewInterestPointLists( views.get( 0 ) ).getInterestPointList( "fresh" ) ) ).isEmpty() );

		// what can be detected again: a label with recorded detection parameters, not a filtered one or one without
		assertEquals( DOG, MissingInterestPointsDialog.detectionParameters( data, "beads", views ) );
		assertNull( MissingInterestPointsDialog.detectionParameters( data, "manual", views ) );
		assertNull( MissingInterestPointsDialog.detectionParameters( data, "beads_f", views ) );

		final String question = MissingInterestPointsDialog.message( data, "beads", views, views.size(), DOG );
		assertTrue( question.contains( "'beads' for all " + views.size() + " selected view(s)" ), question );
		// the recorded parameters are shown (a long string is wrapped)
		assertTrue( question.contains( MissingInterestPointsDialog.REDETECT_OPTION ) && question.replaceAll( "\\s+", " " ).contains( DOG ) && question.contains( MissingInterestPointsDialog.REMOVE_OPTION ), question );
		final String noDetection = MissingInterestPointsDialog.message( data, "manual", views.subList( 0, 1 ), views.size(), null );
		assertTrue( noDetection.contains( "for 1 of the " + views.size() + " selected view(s)" ) && noDetection.contains( "cannot be detected again" ) && !noDetection.contains( MissingInterestPointsDialog.REDETECT_OPTION ), noDetection );

		// an operation without an explorer stops with the log
		assertFalse( MissingInterestPointsDialog.check( data, views, Collections.singletonList( "beads" ), null, "the test" ) );
		assertTrue( MissingInterestPointsDialog.check( data, views, Collections.singletonList( "fresh" ), null, "the test" ) );

		// what a detection that overwrites the label does first no longer fails
		InterestPointTools.removeReferences( data, "beads", views );

		// removing the label takes its filtered label along; the XML is then written without them
		final SortedMap< String, List< ViewId > > removed = MissingInterestPoints.remove( data, Collections.singletonMap( "beads", views ) );
		assertEquals( Set.of( "beads", "beads_f" ), removed.keySet() );
		assertEquals( views, removed.get( "beads_f" ) );
		assertEquals( Set.of( "fresh", "manual" ), labels( data, views.get( 0 ) ) );
		assertEquals( Set.of( "manual" ), labels( data, views.get( 1 ) ) );
		assertEquals( Set.of( "manual" ), MissingInterestPoints.find( vip, views, null ).keySet() );

		MissingInterestPoints.remove( data, MissingInterestPoints.find( vip, views, null ) );
		new XmlIoSpimData2().save( data, xml );

		final SpimData2 repaired = new XmlIoSpimData2().load( xml );
		assertEquals( Set.of( "fresh" ), labels( repaired, views.get( 0 ) ) );
		assertEquals( Set.of(), labels( repaired, views.get( 1 ) ) );
		assertTrue( MissingInterestPoints.find( repaired.getViewInterestPoints(), views, null ).isEmpty() );
		assertEquals( 5, repaired.getViewInterestPoints().getViewInterestPointLists( views.get( 0 ) ).getInterestPointList( "fresh" ).getInterestPointsCopy().size() );
	}

	/** a saved dataset in which the store lost the "beads" of the second view, loaded again; the other views' correspondences still point to it */
	SpimData2 secondViewWithoutData( final URI xml ) throws Exception
	{
		final SpimData2 data = dataset( tempDir );
		final List< ViewId > views = views( data );
		assertTrue( views.size() >= 3 );

		connect( data, views.get( 0 ), views.get( 1 ), 3 );
		connect( data, views.get( 0 ), views.get( 2 ), 2 );
		connect( data, views.get( 1 ), views.get( 2 ), 4 );
		new XmlIoSpimData2().save( data, xml );

		// the entry leaves the store with the next commit, the XML keeps listing it
		data.getViewInterestPoints().getViewInterestPointLists( views.get( 1 ) ).getInterestPointList( "beads" ).deleteInterestPoints();
		new XmlIoSpimData2().save( data, xml );

		return new XmlIoSpimData2().load( xml );
	}

	@Test
	public void someViewsWithoutData() throws Exception
	{
		final URI xml = tempDir.resolve( "dataset.xml" ).toUri();
		SpimData2 data = secondViewWithoutData( xml );
		final List< ViewId > views = views( data );
		final ViewId v0 = views.get( 0 ), v1 = views.get( 1 ), v2 = views.get( 2 );

		assertEquals( Map.of( "beads", Collections.singletonList( v1 ) ), MissingInterestPoints.find( data.getViewInterestPoints(), views, null ) );
		assertEquals( 40, data.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads" ).getInterestPointsCopy().size() );
		assertEquals( 5, data.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads" ).getCorrespondingInterestPointsCopy().size() );

		// the entry's own correspondences are gone, those of the other views that point to it are found by looking at them
		assertEquals( 3 + 4, CorrespondenceTools.removeCorrespondencesForViewLabel( data.getViewInterestPoints(), v1, "beads", 2 ) );
		assertEquals( 2, data.getViewInterestPoints().getViewInterestPointLists( v0 ).getInterestPointList( "beads" ).getCorrespondingInterestPointsCopy().size() );
		assertEquals( 2, data.getViewInterestPoints().getViewInterestPointLists( v2 ).getInterestPointList( "beads" ).getCorrespondingInterestPointsCopy().size() );

		// a second time there is nothing left that points to it
		assertEquals( 0, CorrespondenceTools.removeCorrespondencesForViewLabel( data.getViewInterestPoints(), v1, "beads", 2 ) );

		// the same through the repair, on the dataset as it is on disk
		data = new XmlIoSpimData2().load( xml );
		final SortedMap< String, List< ViewId > > removed = MissingInterestPoints.remove( data, MissingInterestPoints.find( data.getViewInterestPoints(), views, null ) );
		assertEquals( Map.of( "beads", Collections.singletonList( v1 ) ), removed );
		assertEquals( Set.of( "manual" ), labels( data, v1 ) );
		assertEquals( Set.of( "beads", "manual" ), labels( data, v0 ) );
		new XmlIoSpimData2().save( data, xml );

		final SpimData2 repaired = new XmlIoSpimData2().load( xml );
		assertTrue( MissingInterestPoints.find( repaired.getViewInterestPoints(), views, null ).isEmpty() );
		assertEquals( Set.of( "manual" ), labels( repaired, v1 ) );

		for ( final ViewId v : Arrays.asList( v0, v2 ) )
		{
			final InterestPoints list = repaired.getViewInterestPoints().getViewInterestPointLists( v ).getInterestPointList( "beads" );
			assertEquals( 40, list.getInterestPointsCopy().size() );
			assertEquals( 2, list.getCorrespondingInterestPointsCopy().size() );
			for ( final CorrespondingInterestPoints c : list.getCorrespondingInterestPointsCopy() )
				assertEquals( v == v0 ? v2 : v0, c.getCorrespondingViewId() );
		}
	}

	/** the Interest Point Explorer's table is painted from these values: a label without data says so in its cells, the others are counted */
	@Test
	public void explorerTableShowsMissingLabels() throws Exception
	{
		final SpimData2 data = secondViewWithoutData( tempDir.resolve( "dataset.xml" ).toUri() );
		final List< ViewId > views = views( data );
		final List< BasicViewDescription< ? > > vds = new ArrayList<>();
		for ( final ViewId v : views )
			vds.add( data.getSequenceDescription().getViewDescription( v ) );

		final InterestPointTableModel model = new InterestPointTableModel( data.getViewInterestPoints(), null );
		model.updateViewDescription( vds );
		assertEquals( 2, model.getRowCount() );

		// "beads": the second view has no data
		assertEquals( "beads", model.getValueAt( 0, 0 ) );
		for ( int column = 1; column <= 3; ++column )
			assertEquals( InterestPointTableModel.MISSING_TEXT, model.getValueAt( 0, column ) );
		assertEquals( views.size() + "/" + views.size(), model.getValueAt( 0, 4 ) );
		assertEquals( "no data stored", model.getValueAt( 0, InterestPointTableModel.ATTRIBUTES_COLUMN ) );
		assertFalse( model.attributes( 0 ).complete );
		assertTrue( model.attributes( 0 ).tooltip.contains( "'beads' in 1 of the selected view(s)" ), model.attributes( 0 ).tooltip );
		assertEquals( DOG, model.getValueAt( 0, InterestPointTableModel.PARAMETERS_COLUMN ) );
		assertTrue( model.isMissing( 0 ) );
		assertEquals( Map.of( "beads", views.subList( 1, 2 ) ), model.missingEntries() );

		// "manual" is complete
		assertEquals( "manual", model.getValueAt( 1, 0 ) );
		assertEquals( 10 * views.size(), model.getValueAt( 1, 1 ) );
		assertEquals( 0, model.getValueAt( 1, 2 ) );
		assertEquals( 0, model.getValueAt( 1, 3 ) );
		assertFalse( model.isMissing( 1 ) );

		// the views that have their data alone: nothing is missing (their correspondences to the second view are still stored: 3 + 2 and 2 + 4)
		model.updateViewDescription( Arrays.asList( vds.get( 0 ), vds.get( 2 ) ) );
		assertEquals( 80, model.getValueAt( 0, 1 ) );
		assertEquals( 11, model.getValueAt( 0, 3 ) );
		assertTrue( model.missingEntries().isEmpty() );
	}

	/** the embedding stops with a message for a label without data, and a failed embedding leaves no empty array behind (it would count as an embedding of the label) */
	@Test
	public void embeddingStopsAndLeavesNoEmptyArray() throws Exception
	{
		final Path onnx = tempDir.resolve( "tiny.onnx" );
		TestOnnxModels.write( onnx, new double[] { 3, 6 }, 4, 3, 5, 21 );

		final URI xml = tempDir.resolve( "dataset.xml" ).toUri();
		final SpimData2 data = secondViewWithoutData( xml );
		final List< ViewId > views = views( data );
		final URI baseDir = data.getBasePathURI();

		// "beads" lacks the data of a view: nothing is embedded, no array is created
		assertFalse( Embed_Interest_Points.embed( data, views, "beads", onnx, "embedding", 50 ) );
		assertTrue( InterestPointArrays.list( baseDir, "beads", 0 ).isEmpty() );

		// "manual" is no detection: the embedding fails as before, but the array it had created is gone again
		assertThrows( IllegalArgumentException.class, () -> Embed_Interest_Points.embed( data, views, "manual", onnx, "embedding", 50 ) );
		assertTrue( InterestPointArrays.list( baseDir, "manual", 0 ).isEmpty() );

		// an array without rows (left by an earlier version) is not an embedding of the label
		InterestPointEmbedding.prepareArray( baseDir, "manual", 0, "left-over", TestInterestPointEmbedding.contract( new double[] { 3 }, 4, 2, "model", "detection" ), "test" );
		assertEquals( 1, InterestPointArrays.list( baseDir, "manual", 0 ).size() );
		assertTrue( InterestPointArrays.namesByLabel( data.getViewInterestPoints(), views ).get( "manual" ).isEmpty() );
		assertEquals( 0, Interest_Point_Registration.numEmbeddings( data, views, new String[] { "manual" } )[ 0 ] );
		// neither for the registration dialog nor for the Interest Point Explorer, which lists the embeddings after a "+"
		assertEquals( "resp, int, sigma", InterestPointTableModel.computeAttributes( data.getViewInterestPoints(), views, "manual" ).text );
		assertTrue( new java.io.File( new java.io.File( baseDir ), InterestPointsZarrStore.ZARR_CONTAINER ).isDirectory() );
	}

	/** the registration, run by a macro (ImageJ's dialogs need a display): it stops after the first dialog instead of failing on the first read */
	@Test
	public void registrationStops() throws Exception
	{
		assumeFalse( GraphicsEnvironment.isHeadless() );

		final SpimData2 data = secondViewWithoutData( tempDir.resolve( "dataset.xml" ).toUri() );
		final List< ViewId > views = views( data );

		final int algorithm = Interest_Point_Registration.defaultAlgorithm, label = Interest_Point_Registration.defaultLabel;
		final int overlap = Interest_Point_Registration.defaultOverlapType, ipOverlap = Interest_Point_Registration.defaultInterestpointOverlapType;
		final boolean tiles = Interest_Point_Registration.defaultGroupTiles, illums = Interest_Point_Registration.defaultGroupIllums, channels = Interest_Point_Registration.defaultGroupChannels;

		final Boolean[] result = new Boolean[ 1 ];
		final Throwable[] failure = new Throwable[ 1 ];

		// ImageJ hands macro options to threads named "Run$_..." and shows no dialog
		final Thread thread = new Thread( () ->
		{
			try
			{
				Macro.setOptions( "registration_algorithm=[" + new RGLDMGUI().getDescription() + "] interest_points=beads" );
				result[ 0 ] = new Interest_Point_Registration().register( data, views );
			}
			catch ( final Throwable t )
			{
				failure[ 0 ] = t;
			}
			finally
			{
				Macro.setOptions( null );
			}
		}, "Run$_registration" );

		try
		{
			thread.start();
			thread.join();
		}
		finally
		{
			Interest_Point_Registration.defaultAlgorithm = algorithm;
			Interest_Point_Registration.defaultLabel = label;
			Interest_Point_Registration.defaultOverlapType = overlap;
			Interest_Point_Registration.defaultInterestpointOverlapType = ipOverlap;
			Interest_Point_Registration.defaultGroupTiles = tiles;
			Interest_Point_Registration.defaultGroupIllums = illums;
			Interest_Point_Registration.defaultGroupChannels = channels;
		}

		assertNull( failure[ 0 ], "the registration failed instead of stopping: " + failure[ 0 ] );
		assertEquals( Boolean.FALSE, result[ 0 ] );
	}
}
