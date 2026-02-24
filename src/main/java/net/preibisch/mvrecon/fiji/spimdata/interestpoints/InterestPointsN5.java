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
package net.preibisch.mvrecon.fiji.spimdata.interestpoints;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

public class InterestPointsN5 extends InterestPoints
{
	public static int defaultBlockSize = 300_000;
	public static final String baseN5 = "interestpoints.n5";

	// Thread-safe statistics for debugging correspondence loading performance
	private static final AtomicInteger corrCopyCallCount = new AtomicInteger(0);
	private static final AtomicInteger corrCacheMissCount = new AtomicInteger(0);
	private static final AtomicLong corrLoadTime = new AtomicLong(0);
	private static final AtomicLong corrCopyTime = new AtomicLong(0);
	private static final AtomicLong corrTotalCount = new AtomicLong(0);

	/**
	 * Reset correspondence loading statistics
	 */
	public static void resetCorrespondenceStatistics()
	{
		corrCopyCallCount.set(0);
		corrCacheMissCount.set(0);
		corrLoadTime.set(0);
		corrCopyTime.set(0);
		corrTotalCount.set(0);
	}

	/**
	 * Print correspondence loading statistics
	 */
	public static void printCorrespondenceStatistics()
	{
		final int calls = corrCopyCallCount.get();
		if (calls == 0) return;

		final int cacheMisses = corrCacheMissCount.get();
		final int cacheHits = calls - cacheMisses;

		System.out.println("[TIMING] InterestPointsN5 Correspondence Statistics:");
		System.out.println("[TIMING]   getCorrespondingInterestPointsCopy() calls: " + calls);
		System.out.println("[TIMING]   - Cache hits: " + cacheHits + " (" + String.format("%.1f", 100.0 * cacheHits / calls) + "%)");
		System.out.println("[TIMING]   - Cache misses (I/O): " + cacheMisses + " (" + String.format("%.1f", 100.0 * cacheMisses / calls) + "%)");
		System.out.println("[TIMING]   - loadCorrespondences() time: " + corrLoadTime.get() + " ms" + (cacheMisses > 0 ? " (avg " + String.format("%.2f", (double)corrLoadTime.get()/cacheMisses) + " ms/load)" : ""));
		System.out.println("[TIMING]   - Deep copy time: " + corrCopyTime.get() + " ms (avg " + String.format("%.2f", (double)corrCopyTime.get()/calls) + " ms/call)");
		System.out.println("[TIMING]   Total correspondences returned: " + corrTotalCount.get() + " (avg " + (corrTotalCount.get()/calls) + "/call)");
	}

	final String n5path;

	int[] ids = null;
	double[][] locations = null;

	ArrayList< CorrespondingInterestPoints > correspondingInterestPoints;

	public InterestPointsN5( final URI baseDir, final String n5path )
	{
		super(baseDir);
		this.n5path = n5path;
	}

	public String getN5path() { return n5path; }

	@Override
	public String getXMLRepresentation() {
		// a hack so that windows does not put its backslashes in
		return getN5path().toString().replace( "\\", "/" );
	}

	@Override
	public String createXMLRepresentation( final ViewId viewId, final String label )
	{
		return new File( "tpId_" + viewId.getTimePointId() + "_viewSetupId_" + viewId.getViewSetupId() + "/" + label ).getPath();
	}

	/**
	 * @return - a list of interest points (copied), tries to load from disc if null
	 */
	@Override
	public synchronized Map< Integer, InterestPoint > getInterestPointsCopy()
	{
		if ( this.locations == null || this.ids == null )
			loadInterestPoints();

		if ( ids.length == 0 )
			return new HashMap<>();

		return IntStream.range( 0, ids.length ).parallel().mapToObj( i -> new InterestPoint( ids[ i ], locations[ i ].clone() ) ).collect( Collectors.toMap( InterestPoint::getId, ip -> ip ) );
	}

	/**
	 * @return - the list of corresponding interest points (copied), tries to load from disc if null
	 */
	public synchronized Collection< CorrespondingInterestPoints > getCorrespondingInterestPointsCopy()
	{
		corrCopyCallCount.incrementAndGet();

		if ( this.correspondingInterestPoints == null )
		{
			corrCacheMissCount.incrementAndGet();
			final long loadStart = System.currentTimeMillis();
			loadCorrespondences();
			corrLoadTime.addAndGet(System.currentTimeMillis() - loadStart);
		}

		final long copyStart = System.currentTimeMillis();
		final ArrayList< CorrespondingInterestPoints > list = new ArrayList< CorrespondingInterestPoints >();

		for ( final CorrespondingInterestPoints p : this.correspondingInterestPoints )
			list.add( new CorrespondingInterestPoints( p ) );

		corrCopyTime.addAndGet(System.currentTimeMillis() - copyStart);
		corrTotalCount.addAndGet(list.size());

		return list;
	}

	@Override
	protected void setInterestPointsLocal( final Collection< InterestPoint > collection )
	{
		if ( collection == null || collection.size() == 0 )
		{
			this.ids = new int[0];
			this.locations = new double[0][0];

			return;
		}

		this.ids = new int[ collection.size() ];
		this.locations = new double[ collection.size() ][];

		final Iterator< InterestPoint > it = collection.iterator();

		IntStream.range( 0, ids.length ).forEach( i -> {
			final InterestPoint ip = it.next();
			ids[ i ] = ip.getId();
			locations[ i ] = ip.getL().clone();
		});
	}

	@Override
	protected void setCorrespondingInterestPointsLocal( final Collection< CorrespondingInterestPoints > list )
	{
		if ( ArrayList.class.isInstance( list ))
			this.correspondingInterestPoints = (ArrayList<CorrespondingInterestPoints>)list;
		else
			this.correspondingInterestPoints = new ArrayList<>( list );
	}

	public String ipDataset() { return new File( getN5path(), "interestpoints" ).getPath(); }
	public String corrDataset() { return new File( getN5path(), "correspondences" ).getPath(); }

	@Override
	public boolean saveInterestPoints( final boolean forceWrite )
	{
		if ( !modifiedInterestPoints && !forceWrite )
			return true;

		if ( ids == null || locations == null )
			return false;

		final String dataset = ipDataset();

		try
		{
			final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) );

			if (n5Writer.exists(dataset))
				n5Writer.remove(dataset);

			n5Writer.createGroup(dataset);

			n5Writer.setAttribute(dataset, "pointcloud", "1.0.0");
			n5Writer.setAttribute(dataset, "type", "list");
			n5Writer.setAttribute(dataset, "list version", "1.0.0");

			final String idDataset = dataset + "/id";
			final String locDataset = dataset + "/loc";

			if ( ids.length == 0 )
			{
				n5Writer.createDataset(
						idDataset,
						new long[] {0},
						new int[] {1},
						DataType.UINT64,
						new GzipCompression());

				n5Writer.createDataset(
						locDataset,
						new long[] {0},
						new int[] {1},
						DataType.FLOAT64,
						new GzipCompression());

				IOFunctions.println( "Saved: " + URITools.appendName( baseDir, baseN5 ) + "/" + dataset + " (was empty)" );
			}
			else
			{
				final int n = locations[ 0 ].length;

				// 1 x N array (which is a 2D array)
				final FunctionRandomAccessible< UnsignedLongType > id =
						new FunctionRandomAccessible<>(
								2,
								(location, value) -> value.set( this.ids[ location.getIntPosition( 1 ) ] ),
								UnsignedLongType::new );

				// DIM x N array (which is a 2D array)
				final FunctionRandomAccessible< DoubleType > loc =
						new FunctionRandomAccessible<>(
								2,
								(location, value) -> value.set( this.locations[ location.getIntPosition( 1 ) ][ location.getIntPosition( 0 ) ] ),
								DoubleType::new );

				final RandomAccessibleInterval< UnsignedLongType > idData =
						Views.interval( id, new long[] { 0, 0 }, new long[] { 0, ids.length - 1 } );

				final RandomAccessibleInterval< DoubleType > locData =
						Views.interval( loc, new long[] { 0, 0 }, new long[] { n - 1, ids.length - 1 } );

				N5Utils.save( idData, n5Writer, idDataset, new int[] { 1, defaultBlockSize }, new GzipCompression() );
				N5Utils.save( locData, n5Writer, locDataset, new int[] { (int)locData.dimension( 0 ), defaultBlockSize }, new GzipCompression() );

				IOFunctions.println( "Saved: " + URITools.appendName( baseDir, baseN5 ) + "/" + dataset );
			}

			n5Writer.close();
			modifiedInterestPoints = false;
			return true;
		}
		catch (Exception e)
		{
			IOFunctions.println("Couldn't write interestpoints to N5 '" + URITools.appendName( baseDir, baseN5 ) + "/" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public boolean saveCorrespondingInterestPoints(boolean forceWrite)
	{
		if ( !modifiedCorrespondingInterestPoints && !forceWrite )
			return true;

		final ArrayList< CorrespondingInterestPoints > list = this.correspondingInterestPoints;

		if ( list == null )
			return false;

		final String dataset = corrDataset();

		try
		{
			final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) );

			/*
			if ( URITools.isFile( baseDir ) )
				n5Writer = new N5FSWriter( new File( URITools.removeFilePrefix( baseDir ), baseN5 ).getAbsolutePath() );
			else
				n5Writer = new N5Factory().openWriter( URITools.appendName( baseDir, baseN5 ) ); // cloud support, avoid dependency hell if it is a local file
			*/

			if (n5Writer.exists(dataset))
				n5Writer.remove(dataset);

			n5Writer.createGroup(dataset);

			n5Writer.setAttribute( dataset, "correspondences", "2.0.0");  // Version bump for consensusSetId support

			final String corrDataset = dataset + "/data";

			if ( list.size() == 0 )
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

			/*
			n5Writer.createDataset(
					dataset,
					new long[] {1},
					new int[] {1},
					DataType.OBJECT,
					new GzipCompression());

			final DatasetAttributes datasetAttributes = n5Writer.getDatasetAttributes(dataset);

			n5Writer.writeSerializedBlock(
					list,
					dataset,
					datasetAttributes,
					new long[] {0});
			*/

			IOFunctions.println( "Saved: " + URITools.appendName( baseDir, baseN5 ) + "/" + dataset );

			n5Writer.close();

			modifiedCorrespondingInterestPoints = false;
		}
		catch (Exception e)
		{
			IOFunctions.println("Couldn't write corresponding interestpoints to N5 '" + URITools.appendName( baseDir, baseN5 ) + "/" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}

		return true;
	}

	@Override
	protected boolean loadInterestPoints()
	{
		try
		{
			final N5Reader n5 = URITools.instantiateN5Reader( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) );

			final String dataset = ipDataset();

			if (!n5.exists(dataset))
			{
				IOFunctions.println( "InterestPointsN5.loadInterestPoints(): dataset '" + URITools.appendName( baseDir, baseN5 ) + "/" + dataset + "' does not exist, cannot load interestpoints." );
				return false;
			}

			//final String version = n5.getAttribute(dataset, "pointcloud", String.class );
			final String type = n5.getAttribute(dataset, "type", String.class );

			if ( !type.equals("list") )
			{
				IOFunctions.println( "unsupported point cloud type: " + type );
				return false;
			}

			final String idDataset = dataset + "/id";
			final String locDataset = dataset + "/loc";

			// 1 x N array (which is a 2D array)
			final RandomAccessibleInterval< UnsignedLongType > idData = N5Utils.open( n5, idDataset );

			// DIM x N array (which is a 2D array)
			final RandomAccessibleInterval< DoubleType > locData = N5Utils.open( n5, locDataset );
			final int n = (int)locData.dimension( 0 );
			final int size = (int)idData.dimension( 1 );

			if( locData.dimension( 1 ) != size )
				throw new RuntimeException( "Sizes of N5 datasets for interest points do not match, stopping." );

			//System.out.println( "Version: " + version + ", type: " + type + " loading: " + URITools.appendName( baseDir, baseN5 ) + "/" + dataset + " " + n + " " + size );

			// empty list (n is correct here, it's a contract, check saveInterestPoints())
			if ( n == 0 )
			{
				this.ids = new int[0];
				this.locations = new double[0][0];
			}
			else
			{
				this.ids = new int[size];
				this.locations = new double[size][n];

				final RandomAccess< UnsignedLongType > idRA = idData.randomAccess();
				final RandomAccess< DoubleType > locRA = locData.randomAccess();

				idRA.setPosition( 0, 0 );
				idRA.setPosition( 0, 1 );
				locRA.setPosition( 0, 0 );
				locRA.setPosition( 0, 1 );

				for ( int i = 0; i < size; ++ i )
				{
					ids[ i ] = (int)idRA.get().get();

					for ( int d = 0; d < n; ++d )
					{
						locations[ i ][ d ] = locRA.get().get();

						if ( d != n - 1 )
							locRA.fwd( 0 );
					}

					for ( int d = 0; d < n - 1; ++d )
						locRA.bck( 0 );

					if ( i != idData.dimension( 1 ) - 1 )
					{
						idRA.fwd( 1 );
						locRA.fwd( 1 );
					}
				}
			}

			n5.close();
			modifiedInterestPoints = false;
			return true;
		} 
		catch ( final Exception e )
		{
			this.ids = new int[0];
			this.locations = new double[0][0];
			IOFunctions.println( "InterestPointsN5.loadInterestPoints(): " + e );
			e.printStackTrace();
			return false;
		}
	}

	@Override
	protected boolean loadCorrespondences()
	{
		try
		{
			final N5Reader n5 = URITools.instantiateN5Reader( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) );;

			final String dataset = corrDataset();

			if (!n5.exists(dataset))
			{
				IOFunctions.println( "InterestPointsN5.loadCorrespondences(): dataset '" + baseDir + ":/" + baseN5 + "/" + dataset + "' does not exist, cannot load interestpoints." );
				return false;
			}

			// Version detection for backward compatibility
			final String version = n5.getAttribute(dataset, "correspondences", String.class );

			if ( version == null || version.startsWith("1.") )
			{
				//IOFunctions.println( "Loading correspondences v1.x format (3xN array)" );
				return loadCorrespondencesV1( n5, dataset );
			}
			else if ( version.startsWith("2.") )
			{
				//IOFunctions.println( "Loading correspondences v2.x format (4xN array with consensusSetId)" );
				return loadCorrespondencesV2( n5, dataset );
			}
			else
			{
				throw new RuntimeException("Version " + version + " not supported." );
				//IOFunctions.println( "Unknown correspondences version: " + version + ", attempting v1.x loader" );
				//return loadCorrespondencesV1( n5, dataset );
			}
		}
		catch ( final Exception e )
		{
			this.correspondingInterestPoints = null;
			modifiedCorrespondingInterestPoints = false;
			IOFunctions.println( "InterestPointsN5.loadCorrespondences(): " + e );
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Load correspondences in v1.x format (3xN array: detectionId_A, detectionId_B, metadataId)
	 * Sets consensusSetId to -1 for all correspondences (single-consensus mode)
	 */
	protected boolean loadCorrespondencesV1( final N5Reader n5, final String dataset )
	{
		try
		{

			@SuppressWarnings("unchecked")
			final Map< String, Long > idMap = n5.getAttribute(dataset, "idMap", Map.class ); // to store ID (viewId.getTimePointId() + "," + viewId.getViewSetupId() + "," + label)

			if ( idMap.size() == 0 )
			{
				this.correspondingInterestPoints = new ArrayList<>();
				modifiedCorrespondingInterestPoints = false;

				return true;
			}

			final Map< Long, Pair<ViewId, String> > quickLookup = new HashMap<>();
			for ( final Entry<String, Long> entry : idMap.entrySet() )
			{
				final int firstComma = entry.getKey().indexOf( "," );
				final String tp = entry.getKey().substring( 0, firstComma );
				String remaining = entry.getKey().substring( firstComma + 1, entry.getKey().length() );
				final int secondComma = remaining.indexOf( "," );
				final String setup = remaining.substring( 0, secondComma );
				final String label = remaining.substring( secondComma + 1, remaining.length() );

				final int tpInt = Integer.parseInt(tp);
				final int setupInt = Integer.parseInt(setup);

				final long id;

				if ( Double.class.isInstance((Object)entry.getValue()))
					id = Math.round( (Double)(Object)entry.getValue() ); // TODO: bug, a long maybe loaded as a double
				else
					id = entry.getValue();

				final Pair<ViewId, String> value = new ValuePair<>( new ViewId( tpInt, setupInt ), label );
				quickLookup.put( id , value );
			}
			
			final String corrDataset = dataset + "/data";

			// 3 x N array (which is a 2D array, ID_a, ID_b, ID)
			final RandomAccessibleInterval< UnsignedLongType > corrData = N5Utils.open( n5, corrDataset );

			final RandomAccess< UnsignedLongType > corrRA = corrData.randomAccess();

			final ArrayList< CorrespondingInterestPoints > correspondingInterestPoints = new ArrayList<>();

			corrRA.setPosition( 0, 0 );
			corrRA.setPosition( 0, 1 );

			for ( int i = 0; i < corrData.dimension( 1 ); ++ i )
			{
				final long idA = corrRA.get().get();
				corrRA.fwd(0);
				final long idB = corrRA.get().get();
				corrRA.fwd(0);
				final long id = corrRA.get().get();

				corrRA.bck(0);
				corrRA.bck(0);

				if ( i != corrData.dimension( 1 ) - 1 )
					corrRA.fwd( 1 );

				// final int detectionId, final ViewId correspondingViewId, final String correspondingLabel, final int correspondingDetectionId
				final Pair<ViewId, String> value = quickLookup.get( id );
				final CorrespondingInterestPoints cip = new CorrespondingInterestPoints( (int)idA, value.getA(), value.getB(), (int)idB );

				correspondingInterestPoints.add( cip );
			}

			this.correspondingInterestPoints = correspondingInterestPoints;
			modifiedCorrespondingInterestPoints = false;

			return true;
		}
		catch ( final Exception e )
		{
			this.correspondingInterestPoints = null;
			modifiedCorrespondingInterestPoints = false;
			IOFunctions.println( "InterestPointsN5.loadCorrespondencesV1(): " + e );
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Load correspondences in v2.x format (4xN array: detectionId_A, detectionId_B, metadataId, consensusSetId)
	 * Reads consensusSetId from 4th column, decoding 0xFFFFFFFFFFFFFFFF as -1
	 */
	protected boolean loadCorrespondencesV2( final N5Reader n5, final String dataset )
	{
		try
		{
			@SuppressWarnings("unchecked")
			final Map< String, Long > idMap = n5.getAttribute(dataset, "idMap", Map.class );

			if ( idMap.size() == 0 )
			{
				this.correspondingInterestPoints = new ArrayList<>();
				modifiedCorrespondingInterestPoints = false;
				return true;
			}

			final Map< Long, Pair<ViewId, String> > quickLookup = new HashMap<>();
			for ( final Entry<String, Long> entry : idMap.entrySet() )
			{
				final int firstComma = entry.getKey().indexOf( "," );
				final String tp = entry.getKey().substring( 0, firstComma );
				String remaining = entry.getKey().substring( firstComma + 1, entry.getKey().length() );
				final int secondComma = remaining.indexOf( "," );
				final String setup = remaining.substring( 0, secondComma );
				final String label = remaining.substring( secondComma + 1, remaining.length() );

				final int tpInt = Integer.parseInt(tp);
				final int setupInt = Integer.parseInt(setup);

				final long id;

				if ( Double.class.isInstance((Object)entry.getValue()))
					id = Math.round( (Double)(Object)entry.getValue() );
				else
					id = entry.getValue();

				final Pair<ViewId, String> value = new ValuePair<>( new ViewId( tpInt, setupInt ), label );
				quickLookup.put( id , value );
			}

			final String corrDataset = dataset + "/data";

			// 4 x N array (detectionId_A, detectionId_B, metadataId, consensusSetId)
			final RandomAccessibleInterval< UnsignedLongType > corrData = N5Utils.open( n5, corrDataset );

			// Verify it's 4xN format
			if ( corrData.dimension(0) != 4 )
			{
				IOFunctions.println( "Error: Expected 4xN array for v2.x, got " + corrData.dimension(0) + "xN" );
				return false;
			}

			final RandomAccess< UnsignedLongType > corrRA = corrData.randomAccess();
			final ArrayList< CorrespondingInterestPoints > correspondingInterestPoints = new ArrayList<>();

			corrRA.setPosition( 0, 0 );
			corrRA.setPosition( 0, 1 );

			for ( int i = 0; i < corrData.dimension( 1 ); ++ i )
			{
				final long idA = corrRA.get().get();
				corrRA.fwd(0);
				final long idB = corrRA.get().get();
				corrRA.fwd(0);
				final long id = corrRA.get().get();
				corrRA.fwd(0);
				final long setIdRaw = corrRA.get().get();  // 4th element: consensus set ID

				corrRA.bck(0);
				corrRA.bck(0);
				corrRA.bck(0);

				if ( i != corrData.dimension( 1 ) - 1 )
					corrRA.fwd( 1 );

				// Decode setId: max uint64 represents -1
				final int setId = (setIdRaw == 0xFFFFFFFFFFFFFFFFL) ? -1 : (int)setIdRaw;

				final Pair<ViewId, String> value = quickLookup.get( id );
				final CorrespondingInterestPoints cip = new CorrespondingInterestPoints( (int)idA, value.getA(), value.getB(), (int)idB, setId );

				correspondingInterestPoints.add( cip );
			}

			this.correspondingInterestPoints = correspondingInterestPoints;
			modifiedCorrespondingInterestPoints = false;

			return true;
		}
		catch ( final Exception e )
		{
			this.correspondingInterestPoints = null;
			modifiedCorrespondingInterestPoints = false;
			IOFunctions.println( "InterestPointsN5.loadCorrespondencesV2(): " + e );
			e.printStackTrace();
			return false;
		}
	}

	// Old commented-out code kept for reference
	/*
	protected boolean loadCorrespondences_OLD()
	{
		try
		{
			final N5FSReader n5 = new N5FSReader( new File( baseDir.getAbsolutePath(), baseN5 ).getAbsolutePath() );
			final String dataset = corrDataset();

			if (!n5.exists(dataset))
			{
				IOFunctions.println( "InterestPointsN5.loadCorrespondingInterestPoints(): dataset '" + baseDir + ":/" + dataset + "' does not exist, cannot load interestpoints." );
				return false;
			}

			final DatasetAttributes datasetAttributes = n5.getDatasetAttributes(dataset);

			this.correspondingInterestPoints = n5.readSerializedBlock( dataset, datasetAttributes, 0 );
			modifiedCorrespondingInterestPoints = false;

			n5.close();

			return true;
		}
		catch ( final Exception e )
		{
			this.ids = new int[0];
			this.locations = new double[0][0];
			IOFunctions.println( "InterestPointsN5.loadCorrespondingInterestPoints(): " + e );
			e.printStackTrace();
			return false;
		}
	}
	*/

	@Override
	public boolean deleteInterestPoints()
	{
		try
		{
			final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) );

			/*
			if ( URITools.isFile( baseDir ) )
				n5Writer = new N5FSWriter( new File( URITools.removeFilePrefix( baseDir ), baseN5 ).getAbsolutePath() );
			else
				n5Writer = new N5Factory().openWriter( URITools.appendName( baseDir, baseN5 ) ); // cloud support, avoid dependency hell if it is a local file
			*/

			if (n5Writer.exists(ipDataset()))
				n5Writer.remove(ipDataset());
	
			n5Writer.close();

			return true;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "InterestPointsN5.deleteInterestPoints(): " + e );
			e.printStackTrace();

			return false;
		}
	}

	@Override
	public boolean deleteCorrespondingInterestPoints()
	{
		try
		{
			final N5Writer n5Writer = URITools.instantiateN5Writer( StorageFormat.N5, URITools.toURI( URITools.appendName( baseDir, baseN5 ) ) );

			/*
			if ( URITools.isFile( baseDir ) )
				n5Writer = new N5FSWriter( new File( URITools.removeFilePrefix( baseDir ), baseN5 ).getAbsolutePath() );
			else
				n5Writer = new N5Factory().openWriter( URITools.appendName( baseDir, baseN5 ) ); // cloud support, avoid dependency hell if it is a local file
			*/

			if (n5Writer.exists(corrDataset()))
				n5Writer.remove(corrDataset());
	
			n5Writer.close();

			return true;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "InterestPointsN5.deleteCorrespondingInterestPoints(): " + e );
			e.printStackTrace();

			return false;
		}
	}

}
