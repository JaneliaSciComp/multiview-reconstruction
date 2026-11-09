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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.position.FunctionRandomAccessible;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;

/**
 * The N5 layout of interest points, one group per (view, label): {@code interestpoints.n5/tpId_X_viewSetupId_Y/label}
 * with the datasets {@code interestpoints/{id, loc, response, sigma, intensity}} and the group {@code correspondences}.
 * Since the Zarr store ({@link InterestPointsZarrStore}) holds the interest points of a dataset, this class has no
 * instances; it keeps the readers that the conversion ({@link InterestPointsN5ToZarr}) and BigStitcher-Spark's temporary
 * block lists use, and the writers that produce the layout (temporary block lists, test fixtures).
 *
 * @author Stephan Preibisch
 */
public final class InterestPointsN5
{
	public static int defaultBlockSize = 300_000;
	public static final String baseN5 = "interestpoints.n5";

	// the attributes of the points (InterestPoint) next to 'id' and 'loc', NaN where unknown; labels stored before they existed lack them
	public static final String responseDataset = "response"; // FLOAT32, 1 x N
	public static final String sigmaDataset = "sigma"; // FLOAT32, 1 x N (FLOAT64 in labels stored before 2026-10-19, read transparently)
	public static final String intensityDataset = "intensity"; // FLOAT32, 1 x N

	// the marker group a filtered label has instead of the 'interestpoints' datasets: type 'filtered', its base label and filter
	public static final String filteredType = "filtered";
	public static final String baseAttribute = "base";
	public static final String filterAttribute = "filter";

	private InterestPointsN5() {}

	public static String createN5datasetPath( final int tpId, final int vsId, final String label )
	{
		return "tpId_" + tpId + "_viewSetupId_" + vsId + "/" + label;
	}

	public static String ipDataset( final String n5dataset ) { return n5dataset + "/interestpoints"; }
	public static String corrDataset( final String n5dataset  ) { return n5dataset + "/correspondences"; }

	/** the arrays of a stored label (loadPointArrays): ids, locations [point][dimension] and the attributes (null where not stored) */
	protected static class PointArrays
	{
		int[] ids;
		double[][] locations;
		float[] responses, sigmas, intensities;
	}

	/**
	 * Reads 'id', 'loc' and the attributes of an interest point group of type 'list'
	 *
	 * @param n5 - reader of interestpoints.n5
	 * @param dataset - the interest point group, ipDataset( n5path )
	 * @throws RuntimeException if the datasets do not fit together
	 */
	protected static PointArrays loadPointArrays( final N5Reader n5, final String dataset )
	{
		final PointArrays a = new PointArrays();

		// 1 x N array (which is a 2D array)
		final RandomAccessibleInterval< UnsignedLongType > idData = N5Utils.open( n5, dataset + "/id" );

		// DIM x N array (which is a 2D array)
		final RandomAccessibleInterval< DoubleType > locData = N5Utils.open( n5, dataset + "/loc" );
		final int n = (int)locData.dimension( 0 );
		final int size = (int)idData.dimension( 1 );

		if( locData.dimension( 1 ) != size )
			throw new RuntimeException( "Sizes of N5 datasets for interest points do not match, stopping." );

		// empty list (n is correct here, it's a contract, check saveInterestPoints())
		if ( n == 0 )
		{
			a.ids = new int[0];
			a.locations = new double[0][0];
		}
		else
		{
			a.ids = new int[size];
			a.locations = new double[size][n];

			final RandomAccess< UnsignedLongType > idRA = idData.randomAccess();
			final RandomAccess< DoubleType > locRA = locData.randomAccess();

			idRA.setPosition( 0, 0 );
			idRA.setPosition( 0, 1 );
			locRA.setPosition( 0, 0 );
			locRA.setPosition( 0, 1 );

			for ( int i = 0; i < size; ++ i )
			{
				a.ids[ i ] = (int)idRA.get().get();

				for ( int d = 0; d < n; ++d )
				{
					a.locations[ i ][ d ] = locRA.get().get();

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

		// the attributes of the points (null if the label was stored before they existed, the points then have NaN)
		if ( n > 0 )
		{
			a.responses = loadResponses( n5, dataset );
			a.sigmas = loadSigmas( n5, dataset );
			a.intensities = loadIntensities( n5, dataset );

			if ( ( a.responses != null && a.responses.length != size ) || ( a.sigmas != null && a.sigmas.length != size ) || ( a.intensities != null && a.intensities.length != size ) )
				throw new RuntimeException( "Sizes of N5 datasets for response/sigma/intensity do not match the interest points of '" + dataset + "', stopping." );
		}

		return a;
	}

	/**
	 * The points of an interest point group as a list in stored order, with their attributes (NaN where not stored), e.g.
	 * the temporary blocks of BigStitcher-Spark written with saveInterestPointsStatic
	 *
	 * @param n5 - reader of the container
	 * @param n5path - the group of the label (e.g. createN5datasetPath), its points are in ipDataset( n5path )
	 * @throws RuntimeException if the group is missing, not of type 'list' or inconsistent
	 */
	public static ArrayList< InterestPoint > loadInterestPointsStatic( final N5Reader n5, final String n5path )
	{
		final String dataset = ipDataset( n5path );

		if ( !n5.exists( dataset ) )
			throw new RuntimeException( "Interest point group '" + dataset + "' does not exist." );

		final String type = n5.getAttribute( dataset, "type", String.class );

		if ( !"list".equals( type ) )
			throw new RuntimeException( "Unsupported point cloud type '" + type + "' of '" + dataset + "', expected 'list'." );

		final PointArrays a = loadPointArrays( n5, dataset );
		final ArrayList< InterestPoint > points = new ArrayList<>( a.ids.length );

		for ( int i = 0; i < a.ids.length; ++i )
			points.add( new InterestPoint(
					a.ids[ i ],
					a.locations[ i ],
					a.responses == null ? Float.NaN : a.responses[ i ],
					a.sigmas == null ? Float.NaN : a.sigmas[ i ],
					a.intensities == null ? Float.NaN : a.intensities[ i ] ) );

		return points;
	}

	/**
	 * Writes points with their attributes (saveInterestPointsStatic with the arrays of the points), ids and order as given
	 */
	public static boolean saveInterestPointsStatic( final N5Writer n5Writer, final String n5path, final List< ? extends InterestPoint > points )
	{
		final int size = points.size();
		final int[] ids = new int[ size ];
		final double[][] locations = size == 0 ? new double[ 0 ][ 0 ] : new double[ size ][];
		final float[] responses = new float[ size ], sigmas = new float[ size ], intensities = new float[ size ];

		for ( int i = 0; i < size; ++i )
		{
			final InterestPoint ip = points.get( i );
			ids[ i ] = ip.getId();
			locations[ i ] = ip.getL();
			responses[ i ] = ip.getResponse();
			sigmas[ i ] = ip.getSigma();
			intensities[ i ] = ip.getIntensity();
		}

		return saveInterestPointsStatic( n5Writer, n5path, ids, locations, responses, sigmas, intensities );
	}

	/**
	 * @param n5 - reader of interestpoints.n5
	 * @param ipDataset - the interest point group, e.g. ipDataset( createN5datasetPath( tp, setup, label ) )
	 * @return the DoG response of each point in the order of 'id' (NaN where unknown), null if not stored
	 */
	public static float[] loadResponses( final N5Reader n5, final String ipDataset )
	{
		return loadFloat1xN( n5, ipDataset + "/" + responseDataset );
	}

	/**
	 * @param n5 - reader of interestpoints.n5
	 * @param ipDataset - the interest point group, e.g. ipDataset( createN5datasetPath( tp, setup, label ) )
	 * @return the sigma of each point in the order of 'id' (NaN where unknown), null if not stored (FLOAT64 datasets of older labels are read, too)
	 */
	public static float[] loadSigmas( final N5Reader n5, final String ipDataset )
	{
		return loadFloat1xN( n5, ipDataset + "/" + sigmaDataset );
	}

	/**
	 * @param n5 - reader of interestpoints.n5
	 * @param ipDataset - the interest point group, e.g. ipDataset( createN5datasetPath( tp, setup, label ) )
	 * @return the image intensity at each point in the order of 'id' (NaN where unknown), null if not stored
	 */
	public static float[] loadIntensities( final N5Reader n5, final String ipDataset )
	{
		return loadFloat1xN( n5, ipDataset + "/" + intensityDataset );
	}

	/**
	 * @return a 1 x N dataset as floats (whatever its stored type), null if it does not exist
	 */
	protected static float[] loadFloat1xN( final N5Reader n5, final String dataset )
	{
		final double[] values = load1xN( n5, dataset, new FloatType() );

		if ( values == null )
			return null;

		final float[] floats = new float[ values.length ];

		for ( int i = 0; i < values.length; ++i )
			floats[ i ] = (float)values[ i ];

		return floats;
	}

	protected static < T extends RealType< T > & NativeType< T > > double[] load1xN( final N5Reader n5, final String dataset, final T type )
	{
		if ( !n5.datasetExists( dataset ) )
			return null;

		final DatasetAttributes attributes = n5.getDatasetAttributes( dataset );

		// empty list (see saveInterestPointsStatic)
		if ( attributes.getNumDimensions() == 1 && attributes.getDimensions()[ 0 ] == 0 )
			return new double[ 0 ];

		if ( attributes.getNumDimensions() != 2 || attributes.getDimensions()[ 0 ] != 1 )
			throw new RuntimeException( "Unexpected layout of '" + dataset + "', expected 1 x N." );

		// 1 x N array (which is a 2D array)
		final RandomAccessibleInterval< T > data = N5Utils.open( n5, dataset );
		final double[] values = new double[ (int)data.dimension( 1 ) ];
		final Cursor< T > cursor = Views.flatIterable( data ).cursor();

		for ( int i = 0; i < values.length; ++i )
			values[ i ] = cursor.next().getRealDouble();

		return values;
	}

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
	 * Writes the 'id' and 'loc' datasets of an interest point group (the attributes as NaN).
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
		return saveInterestPointsStatic( n5Writer, n5path, ids, locations, null, null, null );
	}

	/**
	 * Same as below without the intensities (stored as NaN)
	 */
	public static boolean saveInterestPointsStatic(
			final N5Writer n5Writer,
			final String n5path,
			final int[] ids,
			final double[][] locations,
			final float[] responses,
			final float[] sigmas )
	{
		return saveInterestPointsStatic( n5Writer, n5path, ids, locations, responses, sigmas, null );
	}

	/**
	 * Writes an interest point group of type 'list': 'id' (UINT64, 1 x N), 'loc' (FLOAT64, DIM x N) and the attributes of
	 * the points as 1 x N FLOAT32 datasets: the DoG response ('response'), the sigma ('sigma') and the image intensity
	 * ('intensity'). They are always written; an array that is null is written as NaN (unknown).
	 *
	 * @param responsesIn response per point in the order of ids, or null
	 * @param sigmasIn sigma per point in the order of ids, or null
	 * @param intensitiesIn image intensity per point in the order of ids, or null
	 */
	public static boolean saveInterestPointsStatic(
			final N5Writer n5Writer,
			final String n5path,
			final int[] ids,
			final double[][] locations,
			final float[] responsesIn,
			final float[] sigmasIn,
			final float[] intensitiesIn )
	{
		final String dataset = ipDataset( n5path );

		final int numPoints = ids == null ? 0 : ids.length;

		if ( ( responsesIn != null && responsesIn.length != numPoints ) || ( sigmasIn != null && sigmasIn.length != numPoints ) || ( intensitiesIn != null && intensitiesIn.length != numPoints ) )
			throw new IllegalArgumentException( "responses/sigmas/intensities and ids have different lengths." );

		final float[] responses = responsesIn == null ? nanFloats( numPoints ) : responsesIn;
		final float[] sigmas = sigmasIn == null ? nanFloats( numPoints ) : sigmasIn;
		final float[] intensities = intensitiesIn == null ? nanFloats( numPoints ) : intensitiesIn;

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

				n5Writer.createDataset( dataset + "/" + responseDataset, new long[] { 0 }, new int[] { 1 }, DataType.FLOAT32, new GzipCompression() );
				n5Writer.createDataset( dataset + "/" + sigmaDataset, new long[] { 0 }, new int[] { 1 }, DataType.FLOAT32, new GzipCompression() );
				n5Writer.createDataset( dataset + "/" + intensityDataset, new long[] { 0 }, new int[] { 1 }, DataType.FLOAT32, new GzipCompression() );

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

				// the attributes, 1 x N arrays (which are 2D arrays)
				final FunctionRandomAccessible< FloatType > response =
						new FunctionRandomAccessible<>(
								2,
								( location, value ) -> value.set( responses[ location.getIntPosition( 1 ) ] ),
								FloatType::new );

				final FunctionRandomAccessible< FloatType > sigma =
						new FunctionRandomAccessible<>(
								2,
								( location, value ) -> value.set( sigmas[ location.getIntPosition( 1 ) ] ),
								FloatType::new );

				final FunctionRandomAccessible< FloatType > intensity =
						new FunctionRandomAccessible<>(
								2,
								( location, value ) -> value.set( intensities[ location.getIntPosition( 1 ) ] ),
								FloatType::new );

				N5Utils.save( Views.interval( response, new long[] { 0, 0 }, new long[] { 0, ids.length - 1 } ), n5Writer, dataset + "/" + responseDataset, new int[] { 1, defaultBlockSize }, new GzipCompression() );
				N5Utils.save( Views.interval( sigma, new long[] { 0, 0 }, new long[] { 0, ids.length - 1 } ), n5Writer, dataset + "/" + sigmaDataset, new int[] { 1, defaultBlockSize }, new GzipCompression() );
				N5Utils.save( Views.interval( intensity, new long[] { 0, 0 }, new long[] { 0, ids.length - 1 } ), n5Writer, dataset + "/" + intensityDataset, new int[] { 1, defaultBlockSize }, new GzipCompression() );

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

	protected static float[] nanFloats( final int n )
	{
		final float[] values = new float[ n ];
		java.util.Arrays.fill( values, Float.NaN );
		return values;
	}

	/**
	 * Writes the marker group of a filtered label in place of the 'interestpoints' datasets: type 'filtered', the base label and the filter
	 */
	public static boolean saveFilteredMarkerStatic( final N5Writer n5Writer, final String n5path, final String baseLabel, final InterestPointFilter filter )
	{
		final String dataset = ipDataset( n5path );

		try
		{
			if ( n5Writer.exists( dataset ) )
				n5Writer.remove( dataset );

			n5Writer.createGroup( dataset );

			n5Writer.setAttribute( dataset, "pointcloud", "1.0.0" );
			n5Writer.setAttribute( dataset, "type", filteredType );
			n5Writer.setAttribute( dataset, baseAttribute, baseLabel );
			n5Writer.setAttribute( dataset, filterAttribute, filter.toString() );

			IOFunctions.println( "Saved: " + dataset + " (filtered label of '" + baseLabel + "': " + filter + ")" );

			return true;
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Couldn't write the marker of filtered label '" + dataset + "': " + e );
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Writes the correspondences group of a label: attribute 'correspondences' = 2.0.0, the 'idMap' attribute
	 * ({"tp,setup,label" -> id}) and the dataset 'data' (UINT64, 4 x N: detectionId_A, detectionId_B, idMap id, consensus
	 * set id with -1 as 0xFFFFFFFFFFFFFFFF); an empty list writes only the empty idMap.
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
		final String dataset = corrDataset( n5path );

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
}
