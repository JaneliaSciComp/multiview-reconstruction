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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import util.URITools;

/**
 * The legacy per-view interest point layout, {@code interestpoints.n5/tpId_X_viewSetupId_Y/label}: dataset names, the readers
 * that {@link InterestPointsN5ToZarr} converts with, and the writers that produce the layout for tests. Interest points are
 * stored in {@link InterestPointsZarrStore}; this class has no instances.
 */
public final class InterestPointsN5
{
	private InterestPointsN5() {}

public static int defaultBlockSize = 300_000;
public static final String baseN5 = "interestpoints.n5";
public static String createN5datasetPath( final int tpId, final int vsId, final String label )
	{
		return "tpId_" + tpId + "_viewSetupId_" + vsId + "/" + label;
	}
public static String ipDataset( final String n5dataset ) { return n5dataset + "/interestpoints"; }
public static String corrDataset( final String n5dataset  ) { return n5dataset + "/correspondences"; }
/**
	 * Read the correspondences stored in any {@code .../correspondences} group of an N5 store, independent of a
	 * SpimData2/InterestPoints instance (e.g. to read externally computed match candidates stored in the same layout).
	 * Dispatches on the {@code correspondences} version attribute (null / 1.x = 3xN legacy, 2.x = 4xN with consensusSetId).
	 *
	 * @param n5 an open reader on the store
	 * @param dataset the group path, e.g. {@code corrDataset( createN5datasetPath( tp, setup, label ) )}
	 * @return the list of corresponding interest points (empty if the idMap is empty)
	 * @throws IllegalArgumentException if the group does not exist, the version is unsupported or the data has the wrong shape
	 */
	public static ArrayList< CorrespondingInterestPoints > readCorrespondences( final N5Reader n5, final String dataset )
	{
		if ( !n5.exists( dataset ) )
			throw new IllegalArgumentException( "correspondences group '" + dataset + "' does not exist." );

		final String version = n5.getAttribute( dataset, "correspondences", String.class );

		if ( version == null || version.startsWith( "1." ) )
			return readCorrespondencesV1( n5, dataset );
		else if ( version.startsWith( "2." ) )
			return readCorrespondencesV2( n5, dataset );
		else
			throw new IllegalArgumentException( "unsupported correspondences version '" + version + "' at '" + dataset + "'." );
	}
/**
	 * Read correspondences in v1.x format (3xN array: detectionId_A, detectionId_B, metadataId); consensusSetId = -1.
	 */
	public static ArrayList< CorrespondingInterestPoints > readCorrespondencesV1( final N5Reader n5, final String dataset )
	{
		final Map< Long, Pair< ViewId, String > > quickLookup = parseIdMap( n5, dataset );
		final ArrayList< CorrespondingInterestPoints > correspondingInterestPoints = new ArrayList<>();

		if ( quickLookup.isEmpty() )
			return correspondingInterestPoints;

		// 3 x N array (which is a 2D array, ID_a, ID_b, ID)
		final RandomAccessibleInterval< UnsignedLongType > corrData = N5Utils.open( n5, dataset + "/data" );

		if ( corrData.numDimensions() != 2 || corrData.dimension( 0 ) != 3 )
			throw new IllegalArgumentException( "Expected 3xN array for v1.x correspondences at '" + dataset + "', got " + corrData.dimension( 0 ) + "xN" );

		final RandomAccess< UnsignedLongType > corrRA = corrData.randomAccess();

		for ( long i = 0; i < corrData.dimension( 1 ); ++i )
		{
			corrRA.setPosition( i, 1 );
			corrRA.setPosition( 0, 0 );
			final long idA = corrRA.get().get();
			corrRA.fwd( 0 );
			final long idB = corrRA.get().get();
			corrRA.fwd( 0 );
			final long id = corrRA.get().get();

			final Pair< ViewId, String > value = quickLookup.get( id );
			correspondingInterestPoints.add( new CorrespondingInterestPoints( (int)idA, value.getA(), value.getB(), (int)idB ) );
		}

		return correspondingInterestPoints;
	}
/**
	 * Read correspondences in v2.x format (4xN array: detectionId_A, detectionId_B, metadataId, consensusSetId),
	 * decoding a consensusSetId of 0xFFFFFFFFFFFFFFFF as -1.
	 */
	public static ArrayList< CorrespondingInterestPoints > readCorrespondencesV2( final N5Reader n5, final String dataset )
	{
		final Map< Long, Pair< ViewId, String > > quickLookup = parseIdMap( n5, dataset );
		final ArrayList< CorrespondingInterestPoints > correspondingInterestPoints = new ArrayList<>();

		if ( quickLookup.isEmpty() )
			return correspondingInterestPoints;

		// 4 x N array (detectionId_A, detectionId_B, metadataId, consensusSetId)
		final RandomAccessibleInterval< UnsignedLongType > corrData = N5Utils.open( n5, dataset + "/data" );

		if ( corrData.numDimensions() != 2 || corrData.dimension( 0 ) != 4 )
			throw new IllegalArgumentException( "Expected 4xN array for v2.x correspondences at '" + dataset + "', got " + corrData.dimension( 0 ) + "xN" );

		final RandomAccess< UnsignedLongType > corrRA = corrData.randomAccess();

		for ( long i = 0; i < corrData.dimension( 1 ); ++i )
		{
			corrRA.setPosition( i, 1 );
			corrRA.setPosition( 0, 0 );
			final long idA = corrRA.get().get();
			corrRA.fwd( 0 );
			final long idB = corrRA.get().get();
			corrRA.fwd( 0 );
			final long id = corrRA.get().get();
			corrRA.fwd( 0 );
			final long setIdRaw = corrRA.get().get(); // 4th element: consensus set ID

			// Decode setId: max uint64 represents -1
			final int setId = ( setIdRaw == 0xFFFFFFFFFFFFFFFFL ) ? -1 : (int)setIdRaw;

			final Pair< ViewId, String > value = quickLookup.get( id );
			correspondingInterestPoints.add( new CorrespondingInterestPoints( (int)idA, value.getA(), value.getB(), (int)idB, setId ) );
		}

		return correspondingInterestPoints;
	}
/**
	 * Parse the {@code idMap} attribute ({"tp,setup,label" -> id}) of a correspondences group into id -> (ViewId, label).
	 * Labels may themselves contain commas (only the first two commas are separators). Empty map if the attribute is
	 * missing or empty. Note: Gson may hand the ids back as Double.
	 */
	public static Map< Long, Pair< ViewId, String > > parseIdMap( final N5Reader n5, final String dataset )
	{
		@SuppressWarnings("unchecked")
		final Map< String, Object > idMap = n5.getAttribute( dataset, "idMap", Map.class );

		final Map< Long, Pair< ViewId, String > > quickLookup = new HashMap<>();

		if ( idMap == null )
			return quickLookup;

		for ( final Entry< String, Object > entry : idMap.entrySet() )
		{
			final int firstComma = entry.getKey().indexOf( "," );
			final String tp = entry.getKey().substring( 0, firstComma );
			final String remaining = entry.getKey().substring( firstComma + 1, entry.getKey().length() );
			final int secondComma = remaining.indexOf( "," );
			final String setup = remaining.substring( 0, secondComma );
			final String label = remaining.substring( secondComma + 1, remaining.length() );

			final long id;

			if ( entry.getValue() instanceof Number )
				id = Math.round( ( (Number)entry.getValue() ).doubleValue() ); // a long may be loaded as a double
			else
				id = Long.parseLong( entry.getValue().toString() );

			quickLookup.put( id, new ValuePair<>( new ViewId( Integer.parseInt( tp ), Integer.parseInt( setup ) ), label ) );
		}

		return quickLookup;
	}
/**
	 * Core static method for saving interest points to N5 using an already-open N5Writer.
	 * The caller is responsible for opening and closing the writer.
	 * Use this overload when saving many views to avoid the per-view open/close overhead
	 * (e.g. in BigStitcher-Spark interest point detection).
	 *
	 * @param n5Writer an already-open N5Writer for interestpoints.n5
	 * @param n5path Relative path within interestpoints.n5 (e.g., "tpId_0_viewSetupId_1/beads")
	 * @param ids Array of detection IDs
	 * @param locations Array of coordinates (numPoints x numDimensions)
	 * @return true if successful
	 */
	public static boolean saveInterestPointsStatic(
			final N5Writer n5Writer,
			final String n5path,
			final int[] ids,
			final double[][] locations )
	{
		final String dataset = ipDataset( n5path );// new File( n5path, "interestpoints" ).getPath();

		try
		{
			if ( n5Writer.exists( dataset ) )
				n5Writer.remove( dataset );

			n5Writer.createGroup( dataset );

			n5Writer.setAttribute( dataset, "pointcloud", "1.0.0" );
			n5Writer.setAttribute( dataset, "type", "list" );
			n5Writer.setAttribute( dataset, "list version", "1.0.0" );

			final String idDataset = dataset + "/id";
			final String locDataset = dataset + "/loc";

			if ( ids == null || ids.length == 0 )
			{
				n5Writer.createDataset(
						idDataset,
						new long[] { 0 },
						new int[] { 1 },
						DataType.UINT64,
						new GzipCompression() );

				n5Writer.createDataset(
						locDataset,
						new long[] { 0 },
						new int[] { 1 },
						DataType.FLOAT64,
						new GzipCompression() );

				IOFunctions.println( "Saved: " + dataset + " (was empty)" );
			}
			else
			{
				final int n = locations[ 0 ].length;

				// 1 x N array (which is a 2D array)
				final FunctionRandomAccessible< UnsignedLongType > id =
						new FunctionRandomAccessible<>(
								2,
								( location, value ) -> value.set( ids[ location.getIntPosition( 1 ) ] ),
								UnsignedLongType::new );

				// DIM x N array (which is a 2D array)
				final FunctionRandomAccessible< DoubleType > loc =
						new FunctionRandomAccessible<>(
								2,
								( location, value ) -> value.set( locations[ location.getIntPosition( 1 ) ][ location.getIntPosition( 0 ) ] ),
								DoubleType::new );

				final RandomAccessibleInterval< UnsignedLongType > idData =
						Views.interval( id, new long[] { 0, 0 }, new long[] { 0, ids.length - 1 } );

				final RandomAccessibleInterval< DoubleType > locData =
						Views.interval( loc, new long[] { 0, 0 }, new long[] { n - 1, ids.length - 1 } );

				N5Utils.save( idData, n5Writer, idDataset, new int[] { 1, defaultBlockSize }, new GzipCompression() );
				N5Utils.save( locData, n5Writer, locDataset, new int[] { (int) locData.dimension( 0 ), defaultBlockSize }, new GzipCompression() );

				IOFunctions.println( "Saved: " + dataset );
			}

			return true;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Couldn't write interestpoints to N5 '" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}
/**
	 * Core static method for saving interest points to N5.
	 * Opens and closes its own N5Writer. For saving many views, prefer
	 * {@link #saveInterestPointsStatic(N5Writer, String, int[], double[][])} with a
	 * shared writer to avoid the per-view open/close overhead.
	 *
	 * @param baseDir Base URI for N5 storage (parent of interestpoints.n5)
	 * @param n5path Relative path within interestpoints.n5 (e.g., "tpId_0_viewSetupId_1/beads")
	 * @param ids Array of detection IDs
	 * @param locations Array of coordinates (numPoints x numDimensions)
	 * @return true if successful
	 */
	public static boolean saveInterestPointsStatic(
			final URI baseDir,
			final String n5path,
			final int[] ids,
			final double[][] locations )
	{
		try ( final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) ) )
		{
			return saveInterestPointsStatic( n5Writer, n5path, ids, locations );
		}
		catch ( Exception e )
		{
			final String dataset = ipDataset( n5path );// new File( n5path, "interestpoints" ).getPath();
			IOFunctions.println( "Couldn't write interestpoints to N5 '" + URITools.appendName( baseDir, baseN5 ) + "/" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}
/**
	 * Convenience overload that constructs n5path from timepoint/setup/label.
	 * Suitable for Spark where ViewId is not available.
	 */
	public static boolean saveInterestPointsStatic(
			final URI baseDir,
			final int timepointId,
			final int setupId,
			final String label,
			final int[] ids,
			final double[][] locations )
	{
		return saveInterestPointsStatic( baseDir,
				createN5datasetPath( timepointId, setupId, label ),
				ids, locations );
	}
/**
	 * Core static method for saving correspondences to N5 using an already-open N5Writer.
	 * The caller is responsible for opening and closing the writer.
	 * Use this overload when saving many views to avoid the per-view open/close overhead
	 * (e.g. in BigStitcher-Spark interest point detection).
	 *
	 * @param n5Writer an already-open N5Writer for interestpoints.n5
	 * @param n5path Relative path within interestpoints.n5 (e.g., "tpId_0_viewSetupId_1/beads")
	 * @param list List of corresponding interest points (can be empty, not null)
	 * @return true if successful
	 */
	public static boolean saveCorrespondencesStatic(
			final N5Writer n5Writer,
			final String n5path,
			final List< CorrespondingInterestPoints > list )
	{
		final String dataset = corrDataset( n5path );// new File( n5path, "correspondences" ).getPath();

		try
		{
			if ( n5Writer.exists( dataset ) )
				n5Writer.remove( dataset );

			n5Writer.createGroup( dataset );

			n5Writer.setAttribute( dataset, "correspondences", "2.0.0" );  // Version 2 for consensusSetId support

			final String corrDataset = dataset + "/data";

			if ( list == null || list.size() == 0 )
			{
				n5Writer.setAttribute( dataset, "idMap", new HashMap< String, Long >() );
				return true;
			}

			//
			// assemble all ViewIds+Labels that there are correspondences with
			// each combination of (ViewId, label) is assigned an ID, this mapping is stored in the attributes
			// the dataset itself only stores the ID as UINT64
			//
			final HashMap<ViewId, HashSet<String>> viewidToLabels = new HashMap<>();

			for ( final CorrespondingInterestPoints cip : list )
			{
				final ViewId viewId = cip.getCorrespondingViewId();
				final String label = cip.getCorrespodingLabel();
				viewidToLabels.computeIfAbsent( viewId, id -> new HashSet<>() ).add( label );
			}

			final HashMap< String, Long > idMap = new HashMap<>(); // to store ID
			final HashMap< ViewId, HashMap<String, Long>> quickLookup = new HashMap<>(); // to quickly lookup ID while saving
			long id = 0;

			for ( final ViewId viewId : viewidToLabels.keySet() )
			{
				final HashMap<String, Long > map = new HashMap<>();
				quickLookup.put( viewId, map );

				for ( final String label : viewidToLabels.get( viewId ) )
				{
					idMap.put( viewId.getTimePointId() + "," + viewId.getViewSetupId() + "," + label, id );
					map.put( label, id );
					id++;
				}
			}

			n5Writer.setAttribute( dataset, "idMap", idMap );

			// 4 x N array (which is a 2D array: ID_a, ID_b, metadataID, consensusSetId)
			final FunctionRandomAccessible< UnsignedLongType > corrId =
					new FunctionRandomAccessible<>(
							2,
							(location, value) -> {
								final CorrespondingInterestPoints cip = list.get( location.getIntPosition( 1 ) );
								final int x = location.getIntPosition( 0 );
								if ( x == 0 )
									value.set( cip.getDetectionId() );
								else if ( x == 1 )
									value.set( cip.getCorrespondingDetectionId() );
								else if ( x == 2 )
									value.set( quickLookup.get( cip.getCorrespondingViewId() ).get( cip.getCorrespodingLabel() ) );
								else // x == 3: consensus set ID
								{
									// Encode -1 as max uint64 to distinguish from valid set ID 0
									final long setIdValue = cip.getConsensusSetId() == -1
											? 0xFFFFFFFFFFFFFFFFL
											: (long)cip.getConsensusSetId();
									value.set( setIdValue );
								}
							},
							UnsignedLongType::new );

			final RandomAccessibleInterval< UnsignedLongType > corrIdData =
					Views.interval( corrId, new long[] { 0, 0 }, new long[] { 3, list.size() - 1 } );  // 3 instead of 2 for 4xN

			N5Utils.save( corrIdData, n5Writer, corrDataset, new int[] { 1, defaultBlockSize }, new GzipCompression() );

			IOFunctions.println( "Saved: " + dataset );

			return true;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Couldn't write corresponding interestpoints to N5 '" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}
/**
	 * Core static method for saving correspondences to N5.
	 * Works directly with List&lt;CorrespondingInterestPoints&gt; (the native internal format).
	 * Opens and closes its own N5Writer. For saving many views, prefer
	 * {@link #saveCorrespondencesStatic(N5Writer, String, List)} with a shared writer
	 * to avoid the per-view open/close overhead.
	 *
	 * @param baseDir Base URI for N5 storage (parent of interestpoints.n5)
	 * @param n5path Relative path within interestpoints.n5 (e.g., "tpId_0_viewSetupId_1/beads")
	 * @param list List of corresponding interest points (can be empty, not null)
	 * @return true if successful
	 */
	public static boolean saveCorrespondencesStatic(
			final URI baseDir,
			final String n5path,
			final List< CorrespondingInterestPoints > list )
	{
		try ( final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) ) )
		{
			return saveCorrespondencesStatic( n5Writer, n5path, list );
		}
		catch ( Exception e )
		{
			final String dataset = corrDataset( n5path );//new File( n5path, "correspondences" ).getPath();
			IOFunctions.println( "Couldn't write corresponding interestpoints to N5 '" + URITools.appendName( baseDir, baseN5 ) + "/" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}
/**
	 * Convenience overload that constructs n5path from timepoint/setup/label.
	 * Suitable for Spark where ViewId is not available.
	 */
	public static boolean saveCorrespondencesStatic(
			final URI baseDir,
			final int timepointId,
			final int setupId,
			final String label,
			final List< CorrespondingInterestPoints > list )
	{
		return saveCorrespondencesStatic( baseDir,
				createN5datasetPath( timepointId, setupId, label ),
				list );
	}
}
