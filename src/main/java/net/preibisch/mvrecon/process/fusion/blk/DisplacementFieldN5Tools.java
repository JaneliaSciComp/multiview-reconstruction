package net.preibisch.mvrecon.process.fusion.blk;

import java.util.Arrays;

import org.janelia.saalfeldlab.n5.Compression;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

/**
 * Static helpers to persist displacement fields to an N5/Zarr container and
 * reload them as in-memory {@link ArrayImg}s.
 *
 * Layout: each dfield is stored as a 4D dataset of shape
 * {@code (n, gridX, gridY, gridZ)} where {@code n} is the number of vector
 * components (3 for 3D). The {@code spacing} (double[]) and {@code offset}
 * (double[]) used during sampling are written as N5 attributes on the dataset
 * so that the {@link TransformedDisplacementField} can be reconstructed exactly
 * on the read side.
 *
 * Important: per the perf contract of {@code BlkThinPlateSplineFusion}, the
 * read side returns an {@link ArrayImg}-backed dfield (not a lazy
 * {@code CachedCellImg}). The downstream block operator is tuned for
 * {@code ArrayImg} access patterns.
 */
public final class DisplacementFieldN5Tools
{
	private static final String ATTR_SPACING = "spacing";
	private static final String ATTR_OFFSET = "offset";
	private static final String ATTR_BBOX_MIN = "bbox_min";
	private static final String ATTR_BBOX_MAX = "bbox_max";

	public static final String CACHE_GROUP = "_displacement_field_cache";

	private DisplacementFieldN5Tools() {}

	/**
	 * Canonical N5/Zarr dataset path for the displacement field of a given
	 * underlying view.
	 */
	public static String datasetPath( final ViewId underlyingViewId )
	{
		return CACHE_GROUP
				+ "/setup" + underlyingViewId.getViewSetupId()
				+ "/timepoint" + underlyingViewId.getTimePointId();
	}

	/**
	 * Sample the given transform over {@code bbox} at {@code spacing} and write
	 * the resulting displacement field to an N5/Zarr dataset.
	 *
	 * @param transform
	 * 		the transform to sample (target -> source).
	 * @param bbox
	 * 		the interval in render coordinates to sample over.
	 * @param spacing
	 * 		dfield grid spacing per dim.
	 * @param n5Writer
	 * 		open writer for the destination container.
	 * @param datasetPath
	 * 		path inside the container, e.g. "_displacement_field_cache/setup0/timepoint0".
	 * @param blockSize3d
	 * 		dfield block size in grid coords (3D); will be prefixed with the
	 * 		vector-component dim to form the 4D N5 block size.
	 * @param type
	 * 		dfield component type ({@link net.imglib2.type.numeric.real.FloatType}
	 * 		or {@link net.imglib2.type.numeric.real.DoubleType}).
	 * @param compression
	 * 		N5 compression for the dataset.
	 */
	public static < D extends NativeType< D > & RealType< D > > void writeDisplacementField(
			final RealTransform transform,
			final Interval bbox,
			final double[] spacing,
			final N5Writer n5Writer,
			final String datasetPath,
			final int[] blockSize3d,
			final D type,
			final Compression compression )
	{
		final int n = transform.numSourceDimensions();
		if ( n != 3 )
			throw new IllegalArgumentException( "Only 3D displacement fields are supported (got " + n + "D)." );
		if ( spacing.length != n || blockSize3d.length != n )
			throw new IllegalArgumentException( "spacing and blockSize3d must have length " + n );

		// Compute the dfield grid dimensions exactly as DisplacementFields.sample(...) does.
		final long[] gridSize = new long[ n ];
		Arrays.setAll( gridSize, d -> ( long ) Math.ceil( bbox.dimension( d ) / spacing[ d ] ) );
		final double[] offset = bbox.minAsDoubleArray();

		// This is the expensive step: allocates the ArrayImg and evaluates the
		// transform at every grid point.
		final RandomAccessibleInterval< D > dfieldImg = DisplacementFields.createNormalized(
				transform, FinalDimensions.wrap( gridSize ), spacing, offset, type );

		// 4D N5 block size: keep all vector components together in one block.
		final int[] n5BlockSize = new int[ n + 1 ];
		n5BlockSize[ 0 ] = n;
		for ( int d = 0; d < n; ++d )
			n5BlockSize[ d + 1 ] = blockSize3d[ d ];

		N5Utils.save( dfieldImg, n5Writer, datasetPath, n5BlockSize, compression );
		n5Writer.setAttribute( datasetPath, ATTR_SPACING, spacing );
		n5Writer.setAttribute( datasetPath, ATTR_OFFSET, offset );
		n5Writer.setAttribute( datasetPath, ATTR_BBOX_MIN, bbox.minAsLongArray() );
		n5Writer.setAttribute( datasetPath, ATTR_BBOX_MAX, bbox.maxAsLongArray() );
	}

	/**
	 * Pre-create an empty dfield dataset (4D, shape {@code (n, gridX, gridY, gridZ)})
	 * with the right block size, datatype, compression, and attributes. Designed
	 * to be called once on the driver before block-level Spark tasks each sample
	 * and save one block via {@link #writeDisplacementFieldBlock}, so executors
	 * don't race on dataset creation.
	 */
	public static < D extends NativeType< D > & RealType< D > > void createEmptyDataset(
			final N5Writer n5Writer,
			final String datasetPath,
			final Interval bbox,
			final double[] spacing,
			final int[] blockSize3d,
			final D type,
			final Compression compression )
	{
		final int n = bbox.numDimensions();
		if ( n != 3 )
			throw new IllegalArgumentException( "Only 3D displacement fields are supported (got " + n + "D)." );
		if ( spacing.length != n || blockSize3d.length != n )
			throw new IllegalArgumentException( "spacing and blockSize3d must have length " + n );

		final long[] gridSize = new long[ n ];
		Arrays.setAll( gridSize, d -> ( long ) Math.ceil( bbox.dimension( d ) / spacing[ d ] ) );

		final long[] dims4d = new long[ n + 1 ];
		dims4d[ 0 ] = n;
		for ( int d = 0; d < n; ++d )
			dims4d[ d + 1 ] = gridSize[ d ];

		final int[] blocks4d = new int[ n + 1 ];
		blocks4d[ 0 ] = n;
		for ( int d = 0; d < n; ++d )
			blocks4d[ d + 1 ] = blockSize3d[ d ];

		final DatasetAttributes attrs = new DatasetAttributes(
				dims4d, blocks4d, N5Utils.dataType( type ), compression );
		n5Writer.createDataset( datasetPath, attrs );

		n5Writer.setAttribute( datasetPath, ATTR_SPACING, spacing );
		n5Writer.setAttribute( datasetPath, ATTR_OFFSET, bbox.minAsDoubleArray() );
		n5Writer.setAttribute( datasetPath, ATTR_BBOX_MIN, bbox.minAsLongArray() );
		n5Writer.setAttribute( datasetPath, ATTR_BBOX_MAX, bbox.maxAsLongArray() );
	}

	/**
	 * Sample one block of the displacement field and write it via
	 * {@link N5Utils#saveBlock}. Used to parallelize dfield rasterization
	 * across Spark tasks: each task computes a small sub-region of the
	 * dfield grid (typically a single N5 chunk) and writes it independently.
	 * The dataset must already exist (see {@link #createEmptyDataset}).
	 *
	 * @param transform
	 * 		the transform to sample (target -> source).
	 * @param bbox
	 * 		full-view bounding box in render coordinates (same as used in
	 * 		{@link #createEmptyDataset} for this dataset).
	 * @param spacing
	 * 		dfield grid spacing per dim.
	 * @param blockSize3d
	 * 		dfield block size in grid coordinates (same as used in
	 * 		{@link #createEmptyDataset}).
	 * @param blockGridPos3d
	 * 		block index per dim in the 3D grid of dfield blocks (i.e., 0-based
	 * 		block coordinates: {@code blockGridPos3d[d] = blockStart[d] / blockSize3d[d]}).
	 */
	public static < D extends NativeType< D > & RealType< D > > void writeDisplacementFieldBlock(
			final RealTransform transform,
			final Interval bbox,
			final double[] spacing,
			final int[] blockSize3d,
			final long[] blockGridPos3d,
			final N5Writer n5Writer,
			final String datasetPath,
			final D type )
	{
		final int n = bbox.numDimensions();
		if ( n != 3 )
			throw new IllegalArgumentException( "Only 3D displacement fields are supported (got " + n + "D)." );
		if ( spacing.length != n || blockSize3d.length != n || blockGridPos3d.length != n )
			throw new IllegalArgumentException( "spacing, blockSize3d, and blockGridPos3d must have length " + n );

		// Full grid extent so we can clip partial edge blocks.
		final long[] gridSize = new long[ n ];
		Arrays.setAll( gridSize, d -> ( long ) Math.ceil( bbox.dimension( d ) / spacing[ d ] ) );

		// Block start (in grid cells) and clipped block size.
		final long[] blockStart = new long[ n ];
		final long[] blockSize = new long[ n ];
		for ( int d = 0; d < n; ++d )
		{
			blockStart[ d ] = blockGridPos3d[ d ] * ( long ) blockSize3d[ d ];
			blockSize[ d ] = Math.min( ( long ) blockSize3d[ d ], gridSize[ d ] - blockStart[ d ] );
			if ( blockSize[ d ] <= 0 )
				throw new IllegalArgumentException( "blockGridPos3d[" + d + "]=" + blockGridPos3d[ d ]
						+ " is outside the dfield grid (gridSize=" + Arrays.toString( gridSize ) + ")." );
		}

		// Sub-region offset in render coordinates.
		final double[] subOffset = new double[ n ];
		for ( int d = 0; d < n; ++d )
			subOffset[ d ] = bbox.min( d ) + blockStart[ d ] * spacing[ d ];

		// Sample only this sub-block (allocates ~ blockSize × n × sizeof(D) bytes).
		final RandomAccessibleInterval< D > subImg = DisplacementFields.createNormalized(
				transform, FinalDimensions.wrap( blockSize ), spacing, subOffset, type );

		// 4D grid position: dim 0 (vector components) has exactly one block at index 0.
		final long[] gridPos4d = new long[ n + 1 ];
		gridPos4d[ 0 ] = 0;
		for ( int d = 0; d < n; ++d )
			gridPos4d[ d + 1 ] = blockGridPos3d[ d ];

		N5Utils.saveBlock( subImg, n5Writer, datasetPath, gridPos4d );
	}

	/**
	 * Compute the per-dim count of dfield blocks for the given bbox/spacing/blockSize.
	 * Used by the driver to enumerate {@code blockGridPos3d} values to dispatch.
	 */
	public static long[] gridBlockCount(
			final Interval bbox,
			final double[] spacing,
			final int[] blockSize3d )
	{
		final int n = bbox.numDimensions();
		final long[] gridSize = new long[ n ];
		Arrays.setAll( gridSize, d -> ( long ) Math.ceil( bbox.dimension( d ) / spacing[ d ] ) );
		final long[] numBlocks = new long[ n ];
		for ( int d = 0; d < n; ++d )
			numBlocks[ d ] = ( gridSize[ d ] + blockSize3d[ d ] - 1 ) / blockSize3d[ d ];
		return numBlocks;
	}

	/**
	 * Read the back-projected bbox (render coordinates) of the source view
	 * from a previously-written dfield dataset's attributes.
	 */
	public static Interval readBbox( final N5Reader n5Reader, final String datasetPath )
	{
		final long[] min = n5Reader.getAttribute( datasetPath, ATTR_BBOX_MIN, long[].class );
		final long[] max = n5Reader.getAttribute( datasetPath, ATTR_BBOX_MAX, long[].class );
		if ( min == null || max == null )
			throw new IllegalArgumentException( "Dataset '" + datasetPath
					+ "' is missing bbox_min/bbox_max attributes; was it written with DisplacementFieldN5Tools.writeDisplacementField?" );
		return new FinalInterval( min, max );
	}

	/**
	 * Read a dfield dataset (previously written by
	 * {@link #writeDisplacementField}) into a full {@link ArrayImg} and
	 * reconstruct the {@link TransformedDisplacementField}. The returned
	 * dfield is backed by an {@code ArrayImg}, not a lazy cell image, so
	 * downstream {@code BlkThinPlateSplineFusion} operations meet their perf
	 * expectations.
	 */
	public static < D extends NativeType< D > & RealType< D > > TransformedDisplacementField< D > readDisplacementFieldAsArrayImg(
			final N5Reader n5Reader,
			final String datasetPath,
			final D type )
	{
		final double[] spacing = n5Reader.getAttribute( datasetPath, ATTR_SPACING, double[].class );
		final double[] offset = n5Reader.getAttribute( datasetPath, ATTR_OFFSET, double[].class );
		if ( spacing == null || offset == null )
			throw new IllegalArgumentException( "Dataset '" + datasetPath
					+ "' is missing spacing/offset attributes; was it written with DisplacementFieldN5Tools.writeDisplacementField?" );

		// Open as lazy CachedCellImg, then materialize into an ArrayImg.
		final RandomAccessibleInterval< D > lazy = Views.zeroMin( N5Utils.<D>open( n5Reader, datasetPath ) );

		final long[] dims = lazy.dimensionsAsLongArray();
		final ArrayImg< D, ? > arr = new ArrayImgFactory<>( lazy.firstElement() ).create( dims );
		LoopBuilder.setImages( lazy, arr ).forEachPixel( ( s, d ) -> d.set( s ) );

		final int n = spacing.length;
		if ( n != 3 )
			throw new IllegalArgumentException( "Only 3D displacement fields are supported (got " + n + "D)." );

		final AffineTransform3D transformFromField = new AffineTransform3D();
		transformFromField.set(
				spacing[ 0 ], 0, 0, offset[ 0 ],
				0, spacing[ 1 ], 0, offset[ 1 ],
				0, 0, spacing[ 2 ], offset[ 2 ] );

		final DisplacementField< D > dfield = new DisplacementField<>( BlockSupplier.of( arr ), spacing, offset );
		return new TransformedDisplacementField<>( transformFromField, dfield );
	}

	/**
	 * Read a dfield dataset (previously written by
	 * {@link #writeDisplacementField} or
	 * {@link #createEmptyDataset}+{@link #writeDisplacementFieldBlock}) as a
	 * lazy {@link net.imglib2.img.cell.CellImg CellImg} and reconstruct the
	 * {@link TransformedDisplacementField}. The returned dfield is backed by a
	 * {@code CachedCellImg} — chunks are loaded from N5 on demand, so per-task
	 * heap is bounded by the cells actually touched by the current Spark block
	 * (typically a few MB) instead of the full grid (typically GB).
	 *
	 * Prefer this over {@link #readDisplacementFieldAsArrayImg} when the dfield
	 * is large enough that holding the full grid in heap is impractical, and
	 * accept the per-cell load overhead.
	 */
	public static < D extends NativeType< D > & RealType< D > > TransformedDisplacementField< D > readDisplacementFieldAsCellImg(
			final N5Reader n5Reader,
			final String datasetPath,
			final D type )
	{
		final double[] spacing = n5Reader.getAttribute( datasetPath, ATTR_SPACING, double[].class );
		final double[] offset = n5Reader.getAttribute( datasetPath, ATTR_OFFSET, double[].class );
		if ( spacing == null || offset == null )
			throw new IllegalArgumentException( "Dataset '" + datasetPath
					+ "' is missing spacing/offset attributes; was it written with DisplacementFieldN5Tools.writeDisplacementField?" );

		final int n = spacing.length;
		if ( n != 3 )
			throw new IllegalArgumentException( "Only 3D displacement fields are supported (got " + n + "D)." );

		// Open as lazy CachedCellImg backed by N5; do NOT materialize.
		final RandomAccessibleInterval< D > lazy = Views.zeroMin( N5Utils.<D>open( n5Reader, datasetPath ) );

		final AffineTransform3D transformFromField = new AffineTransform3D();
		transformFromField.set(
				spacing[ 0 ], 0, 0, offset[ 0 ],
				0, spacing[ 1 ], 0, offset[ 1 ],
				0, 0, spacing[ 2 ], offset[ 2 ] );

		final DisplacementField< D > dfield = new DisplacementField<>( BlockSupplier.of( lazy ), spacing, offset );
		return new TransformedDisplacementField<>( transformFromField, dfield );
	}

	/**
	 * Check whether a dfield dataset already exists at the given path with the
	 * expected {@code spacing} and {@code offset} (= {@code bbox.min}). Used to
	 * skip recomputation across repeated runs that use the same output
	 * container.
	 */
	public static boolean datasetMatches(
			final N5Reader n5Reader,
			final String datasetPath,
			final Interval expectedBbox,
			final double[] expectedSpacing )
	{
		if ( !n5Reader.datasetExists( datasetPath ) )
			return false;
		final double[] spacing = n5Reader.getAttribute( datasetPath, ATTR_SPACING, double[].class );
		final double[] offset = n5Reader.getAttribute( datasetPath, ATTR_OFFSET, double[].class );
		if ( spacing == null || offset == null )
			return false;
		if ( !Arrays.equals( spacing, expectedSpacing ) )
			return false;
		final double[] expectedOffset = expectedBbox.minAsDoubleArray();
		return Arrays.equals( offset, expectedOffset );
	}
}
