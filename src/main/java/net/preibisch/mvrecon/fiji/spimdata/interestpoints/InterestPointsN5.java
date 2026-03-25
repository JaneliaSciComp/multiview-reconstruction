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
import java.util.List;
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

		final boolean success = saveInterestPointsStatic( baseDir, n5path, ids, locations );

		if ( success )
			modifiedInterestPoints = false;

		return success;
	}

	@Override
	public boolean saveCorrespondingInterestPoints(boolean forceWrite)
	{
		if ( !modifiedCorrespondingInterestPoints && !forceWrite )
			return true;

		if ( correspondingInterestPoints == null )
			return false;

		final boolean success = saveCorrespondencesStatic( baseDir, n5path, correspondingInterestPoints );

		if ( success )
			modifiedCorrespondingInterestPoints = false;

		return success;
	}

	/**
	 * Save interest points using an already-open N5Writer, clearing the modified flag on success.
	 * Use when saving many views to avoid per-view open/close overhead.
	 */
	public boolean saveInterestPoints( final boolean forceWrite, final N5Writer n5Writer )
	{
		if ( !modifiedInterestPoints && !forceWrite )
			return true;

		if ( ids == null || locations == null )
			return false;

		final boolean success = saveInterestPointsStatic( n5Writer, n5path, ids, locations );

		if ( success )
			modifiedInterestPoints = false;

		return success;
	}

	/**
	 * Save correspondences using an already-open N5Writer, clearing the modified flag on success.
	 * Use when saving many views to avoid per-view open/close overhead.
	 */
	public boolean saveCorrespondingInterestPoints( final boolean forceWrite, final N5Writer n5Writer )
	{
		if ( !modifiedCorrespondingInterestPoints && !forceWrite )
			return true;

		if ( correspondingInterestPoints == null )
			return false;

		final boolean success = saveCorrespondencesStatic( n5Writer, n5path, correspondingInterestPoints );

		if ( success )
			modifiedCorrespondingInterestPoints = false;

		return success;
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

	// ==================== Static Methods for Spark-Compatible Saving ====================

	/**
	 * Serializable data container for interest points and correspondences.
	 * Enables Spark-compatible parallel saving by containing only primitive/serializable data.
	 * Uses zero-copy references to internal arrays of InterestPointsN5.
	 */
	public static class InterestPointData implements java.io.Serializable
	{
		private static final long serialVersionUID = 1L;

		public final int timepointId;
		public final int setupId;
		public final String label;

		// Interest points data (direct references to InterestPointsN5 internal arrays)
		public final int[] ids;
		public final double[][] locations;

		// Correspondences (direct reference to InterestPointsN5 internal list)
		public final ArrayList< CorrespondingInterestPoints > correspondences;

		public InterestPointData(
				final int timepointId,
				final int setupId,
				final String label,
				final int[] ids,
				final double[][] locations,
				final ArrayList< CorrespondingInterestPoints > correspondences )
		{
			this.timepointId = timepointId;
			this.setupId = setupId;
			this.label = label;
			this.ids = ids;
			this.locations = locations;
			this.correspondences = correspondences;
		}

		/**
		 * Extract data from an InterestPointsN5 object by direct reference (zero-copy).
		 * Triggers lazy loading if data hasn't been loaded from disk yet.
		 *
		 * @param viewId The ViewId
		 * @param label Interest point label
		 * @param ips InterestPointsN5 object to reference data from
		 * @return InterestPointData referencing the internal arrays directly
		 */
		public static InterestPointData from( final ViewId viewId, final String label, final InterestPointsN5 ips )
		{
			// Trigger lazy loading if needed (results discarded, we reference internal fields directly)
			if ( ips.ids == null || ips.locations == null )
				ips.getInterestPointsCopy();

			if ( ips.correspondingInterestPoints == null )
				ips.getCorrespondingInterestPointsCopy();

			return new InterestPointData(
					viewId.getTimePointId(), viewId.getViewSetupId(), label,
					ips.ids, ips.locations, ips.correspondingInterestPoints );
		}

		public boolean hasInterestPoints() { return ids != null && ids.length > 0; }
		public boolean hasCorrespondences() { return correspondences != null && !correspondences.isEmpty(); }
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
		final String dataset = new File( n5path, "interestpoints" ).getPath();

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
			final String dataset = new File( n5path, "interestpoints" ).getPath();
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
				"tpId_" + timepointId + "_viewSetupId_" + setupId + "/" + label,
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
		final String dataset = new File( n5path, "correspondences" ).getPath();

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
			final String dataset = new File( n5path, "correspondences" ).getPath();
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
				"tpId_" + timepointId + "_viewSetupId_" + setupId + "/" + label,
				list );
	}

	/**
	 * Static wrapper to save all data from an InterestPointData object using an already-open N5Writer.
	 * The caller is responsible for opening and closing the writer.
	 * Use this overload when saving many views to avoid the per-view open/close overhead
	 * (e.g. in BigStitcher-Spark interest point detection).
	 *
	 * @param n5Writer an already-open N5Writer for interestpoints.n5
	 * @param data InterestPointData containing all data
	 * @return true if successful
	 */
	public static boolean saveInterestPointDataStatic( final N5Writer n5Writer, final InterestPointData data )
	{
		final String n5path = "tpId_" + data.timepointId + "_viewSetupId_" + data.setupId + "/" + data.label;

		boolean success = saveInterestPointsStatic( n5Writer, n5path, data.ids, data.locations );

		if ( success && data.hasCorrespondences() )
			success = saveCorrespondencesStatic( n5Writer, n5path, data.correspondences );

		return success;
	}

	/**
	 * Static wrapper to save all data from an InterestPointData object.
	 * Suitable for Spark RDD.foreach() operations.
	 * Opens and closes its own N5Writer. For saving many views, prefer
	 * {@link #saveInterestPointDataStatic(N5Writer, InterestPointData)} with a shared writer
	 * to avoid the per-view open/close overhead.
	 *
	 * @param baseDir Base URI for N5 storage (parent of interestpoints.n5)
	 * @param data InterestPointData containing all data
	 * @return true if successful
	 */
	public static boolean saveInterestPointDataStatic( final URI baseDir, final InterestPointData data )
	{
		final String n5path = "tpId_" + data.timepointId + "_viewSetupId_" + data.setupId + "/" + data.label;

		boolean success = saveInterestPointsStatic( baseDir, n5path, data.ids, data.locations );

		if ( success && data.hasCorrespondences() )
			success = saveCorrespondencesStatic( baseDir, n5path, data.correspondences );

		return success;
	}

}
