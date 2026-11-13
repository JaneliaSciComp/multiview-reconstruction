import java.io.File;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.scicomp.n5.zstandard.ZstandardCompression;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.*;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField;
import net.imglib2.converter.RealUnsignedShortConverter;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxTools;
import net.preibisch.mvrecon.process.fusion.blk.BlkThinPlateSplineFusion;
import net.preibisch.mvrecon.process.fusion.blk.DisplacementFieldN5Tools;
import net.preibisch.mvrecon.process.fusion.blk.SplitImgLoaderThinPlateSplineFusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.tps.Landmarks;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.process.n5api.N5ApiTools;
import util.URITools;

/**
 * Runs one SparkFusion TPS task (same calls as the Spark lambda, CLOSEST_PIXEL_WINS, UINT16) on one fusion block,
 * locally. Dfields are materialized like Phase 1.5 into a local zarr, but only the dfield blocks the fusion block needs.
 *
 *   java -cp ... tools/TpsFusionBench.java XML WORKDIR plan|dfield|fuse|fuse+save gx,gy,gz [sx,sy,sz] [repeats]
 *
 * gx,gy,gz: block grid index for block size sx,sy,sz (default 4096,2560,64 = the pipeline's shard).
 * plan prints the underlying views and the input z-shards (128 z) the block reads, copy only those locally.
 * Classpath: target/classes + `mvn dependency:build-classpath`. -D options:
 *   dfield:  -DdfieldBlock=128,128,16 (dfield chunk size)  -DdfieldShard=128,128,128 (zarr v3 shards of chunks)  -DdfieldZstd=LEVEL (zstd instead of gzip 1)
 *   fuse:    -Dfusion=AVG_BLEND|MAX_INTENSITY|...
 *            -Ddump=file.raw (fused block, raw uint16 LE, for voxel-wise A/B)
 *   save:    -Dwrap5d=true (5D ArrayImg like SparkFusion now does, false = old Views.addDimension)
 */
public class TpsFusionBench
{
	public static void main( String[] args ) throws Exception
	{
		final URI xml = new File( args[ 0 ] ).toURI();
		final URI out = new File( args[ 1 ], "fused.zarr" ).toURI();
		final String mode = args[ 2 ];
		final long[] g = Arrays.stream( args[ 3 ].split( "," ) ).mapToLong( Long::parseLong ).toArray();
		final long[] bs = args.length > 4 ? Arrays.stream( args[ 4 ].split( "," ) ).mapToLong( Long::parseLong ).toArray() : new long[] { 4096, 2560, 64 };
		final int repeats = args.length > 5 ? Integer.parseInt( args[ 5 ] ) : 1;
		final int[] blockSize = Arrays.stream( System.getProperty( "tile", "64,64,64" ).split( "," ) ).mapToInt( Integer::parseInt ).toArray(); // fusion tile (SparkFusion: --blockSize)

		final SpimData2 data = load( xml );
		final List< ViewId > viewIds = new ArrayList<>( data.getSequenceDescription().getViewDescriptions().keySet() );
		viewIds.removeIf( v -> !data.getSequenceDescription().getViewDescription( v ).isPresent() || data.getSequenceDescription().getViewDescription( v ).getViewSetup().getChannel().getId() != 0 );
		Collections.sort( viewIds );

		// as CreateFusionContainer --preserveAnisotropy
		final double af = TransformationTools.getAverageAnisotropyFactor( data, viewIds );
		final BoundingBox bb0 = BoundingBoxTools.maximalBoundingBox( data, viewIds, "All Views" );
		final long[] bbMin = bb0.minAsLongArray(), bbMax = bb0.maxAsLongArray();
		bbMin[ 2 ] = Math.round( Math.floor( bbMin[ 2 ] / af ) );
		bbMax[ 2 ] = Math.round( Math.ceil( bbMax[ 2 ] / af ) );
		final FinalInterval bbox = new FinalInterval( bbMin, bbMax );
		System.out.println( "views=" + viewIds.size() + " af=" + af + " bbox=" + Util.printInterval( bbox ) + " dims=" + Util.printCoordinates( bbox.dimensionsAsLongArray() ) );

		final HashMap< ViewId, AffineTransform3D > regs = TransformVirtual.adjustAllTransforms( viewIds, data.getViewRegistrations().getViewRegistrations(), af, Double.NaN );
		final int zBlocks = Integer.getInteger( "zBlocks", 1 ); // z-neighbor blocks one after the other (-Dshare=true: like one Spark task)
		final long t0 = System.nanoTime();
		for ( int zb = 0; zb < zBlocks; ++zb, ++g[ 2 ] ) {
		// fusion block in world coordinates (as SparkFusion: superBlockOffset = gridBlock[0] + bbMin)
		final long[] min = new long[ 3 ], max = new long[ 3 ];
		for ( int d = 0; d < 3; ++d )
		{
			min[ d ] = bbMin[ d ] + g[ d ] * bs[ d ];
			max[ d ] = Math.min( bbMax[ d ], min[ d ] + bs[ d ] - 1 );
		}
		final FinalInterval fusedBlock = new FinalInterval( min, max );
		final long[] gridMin = new long[ 3 ];
		Arrays.setAll( gridMin, d -> g[ d ] * bs[ d ] );
		final FinalInterval zeroMinBlock = new FinalInterval( gridMin, new long[] { gridMin[ 0 ] + fusedBlock.dimension( 0 ) - 1, gridMin[ 1 ] + fusedBlock.dimension( 1 ) - 1, gridMin[ 2 ] + fusedBlock.dimension( 2 ) - 1 } );
		System.out.println( "block " + Util.printInterval( fusedBlock ) + " (" + Intervals.numElements( fusedBlock ) / 1e6 + " Mvox)" );

		final List< ViewId > overlapping = new ArrayList<>();
		final Interval expanded = Intervals.expand( fusedBlock, 50 );
		for ( final ViewId v : viewIds )
		{
			final Dimensions dim = data.getSequenceDescription().getViewDescription( v ).getViewSetup().getSize();
			final Interval b = Intervals.smallestContainingInterval( regs.get( v ).estimateBounds( new FinalInterval( dim ) ) );
			if ( !Intervals.isEmpty( Intervals.intersect( b, expanded ) ) )
				overlapping.add( v );
		}
		final SplitViewerImgLoader sil = ( SplitViewerImgLoader ) data.getSequenceDescription().getImgLoader();
		final List< ViewId > underlying = SplitImgLoaderThinPlateSplineFusion.underlyingViewIds( overlapping, sil.new2oldSetupId() );
		System.out.println( overlapping.size() + " split views, underlying " + underlying );

		if ( mode.equals( "plan" ) || mode.equals( "dfield" ) )
			dfields( data, sil, underlying, af, out, fusedBlock, mode.equals( "plan" ) );

		if ( mode.startsWith( "fuse" ) )
			for ( int r = 0; r < repeats; ++r )
			{
				final ArrayImg img = fuse( xml, out, viewIds, overlapping, af, bbox, zeroMinBlock, blockSize );
				if ( mode.equals( "fuse+save" ) )
					save( out, img, bbox, zeroMinBlock, bs );
			}
		}
		if ( zBlocks > 1 )
			System.out.printf( "%d z-blocks: %.2fs%n", zBlocks, ( System.nanoTime() - t0 ) / 1e9 );
	}

	// -Dshare=true: data and dfields shared by consecutive fuse() calls, as within one Spark task that fuses several blocks
	static final boolean share = Boolean.getBoolean( "share" );
	static SpimData2 shared;
	static final Map< String, TransformedDisplacementField< ? > > sharedDfields = new HashMap<>();

	// SparkFusion's write of the s0 shard into a 5D OME-ZARR v3 container (block 64^3, shard = compute block, zstd 3)
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	static void save( URI out, ArrayImg img, Interval bbox, Interval block, long[] shard ) throws Exception
	{
		try ( N5Writer w = URITools.instantiateN5Writer( StorageFormat.ZARR, out ) )
		{
			if ( !w.datasetExists( "s0" ) )
				N5ApiTools.setupMultiResolutionPyramid( w, l -> "s" + l, DataType.UINT16,
						new long[] { bbox.dimension( 0 ), bbox.dimension( 1 ), bbox.dimension( 2 ), 1, 1 },
						new ZstandardCompression( Integer.getInteger( "zstdLevel", 3 ) ), new int[] { 64, 64, 64, 1, 1 }, new int[][] { { 1, 1, 1, 1, 1 } },
						true, new int[] { ( int ) shard[ 0 ], ( int ) shard[ 1 ], ( int ) shard[ 2 ], 1, 1 } );
			final long t0 = System.nanoTime();
			final RandomAccessibleInterval image = Boolean.getBoolean( "wrap5d" )
					? net.imglib2.img.array.ArrayImgs.unsignedShorts( ( short[] ) ( ( net.imglib2.img.basictypeaccess.array.ShortArray ) img.update( null ) ).getCurrentStorageArray(),
							block.dimension( 0 ), block.dimension( 1 ), block.dimension( 2 ), 1, 1 )
					: Views.interval( Views.addDimension( Views.addDimension( img ) ),
							new FinalInterval( new long[] { block.dimension( 0 ), block.dimension( 1 ), block.dimension( 2 ), 1, 1 } ) );
			final long[] gridOffset = { block.min( 0 ) / 64, block.min( 1 ) / 64, block.min( 2 ) / 64, 0, 0 };
			N5Utils.saveBlock( image, w, "s0", gridOffset );
			System.out.printf( "save %.2fs%n", ( System.nanoTime() - t0 ) / 1e9 );
		}
	}

	static SpimData2 load( final URI xml ) throws Exception
	{
		final SpimData2 data = new XmlIoSpimData2().load( xml );
		( ( bdv.ViewerImgLoader ) data.getSequenceDescription().getImgLoader() ).setNumFetcherThreads( 0 );
		return data;
	}

	// Phase 1.5 for the given underlying views, restricted to dfield blocks that intersect the (expanded) fusion block.
	static void dfields( SpimData2 data, SplitViewerImgLoader sil, List< ViewId > underlying, double af, URI out, Interval fusedBlock, boolean planOnly ) throws Exception
	{
		final double[] spacing = { 8, 8, 8 };
		final int[] dbs = Arrays.stream( System.getProperty( "dfieldBlock", "128,128,128" ).split( "," ) ).mapToInt( Integer::parseInt ).toArray();
		final int[] shard = System.getProperty( "dfieldShard" ) == null ? null : Arrays.stream( System.getProperty( "dfieldShard" ).split( "," ) ).mapToInt( Integer::parseInt ).toArray();
		final int[] unit = shard == null ? dbs : shard; // one task writes one chunk, or one whole shard
		final Map< Integer, List< Integer > > o2n = SplitImgLoaderThinPlateSplineFusion.old2newSetupId( sil.new2oldSetupId() );
		final Map< ViewId, ViewRegistration > regMap = data.getViewRegistrations().getViewRegistrations();
		final SequenceDescription uSD = sil.underlyingSequenceDescription();
		final Interval need = Intervals.expand( fusedBlock, 50 + 8 );
		final ExecutorService ex = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
		final List< Future< ? > > fs = new ArrayList<>();
		try ( N5Writer w = URITools.instantiateN5Writer( StorageFormat.ZARR, out ) )
		{
			for ( final ViewId u : underlying )
			{
				final Landmarks lm = SplitImgLoaderThinPlateSplineFusion.getCoefficients( sil, o2n, regMap, u, af, Double.NaN,
						data.getViewInterestPoints(), "beads_split", 6, false, 0.0, 2, null, null, null, null );
				final ThinplateSplineTransform tps = new ThinplateSplineTransform( lm.getTargetPoints(), lm.getSourcePoints() );
				final Dimensions dims = uSD.getViewDescriptions().get( u ).getViewSetup().getSize();
				final AffineTransform3D aff = BlkThinPlateSplineFusion.fitAffineTransform( lm.getSourcePoints(), lm.getTargetPoints() );
				final Interval vb = BlkThinPlateSplineFusion.inverseTransformedBoundingBox( tps, aff, dims );

				// input region the block needs (approx. via the inverse of the approximate affine) -> input shards (128 z)
				final RealInterval in = aff.inverse().estimateBounds( Intervals.intersect( vb, fusedBlock ) );
				System.out.println( u + " landmarks=" + lm.getSourcePoints()[ 0 ].length + " viewBox=" + Util.printInterval( vb )
						+ " input z " + ( long ) in.realMin( 2 ) + ".." + ( long ) in.realMax( 2 ) + " -> shards " + ( ( long ) Math.max( 0, in.realMin( 2 ) - 10 ) / 128 ) + ".." + ( ( long ) ( in.realMax( 2 ) + 10 ) / 128 ) );
				if ( planOnly || Intervals.isEmpty( Intervals.intersect( vb, need ) ) )
					continue;

				final String ds = DisplacementFieldN5Tools.datasetPath( u );
				if ( w.exists( ds ) )
					w.remove( ds );
				DisplacementFieldN5Tools.createEmptyDataset( w, ds, vb, spacing, dbs, new DoubleType(), Integer.getInteger( "dfieldZstd", 0 ) > 0 ? new ZstandardCompression( Integer.getInteger( "dfieldZstd" ) ) : new GzipCompression( 1 ), "beads_split", 6, false, 0.0, 2, null, null, shard );
				DisplacementFieldN5Tools.writeApproxAffine( w, ds, aff );
				final long[] nb = DisplacementFieldN5Tools.gridBlockCount( vb, spacing, unit );
				for ( long z = 0; z < nb[ 2 ]; ++z )
					for ( long y = 0; y < nb[ 1 ]; ++y )
						for ( long x = 0; x < nb[ 0 ]; ++x )
						{
							final long[] p = { x, y, z };
							final long[] bmin = new long[ 3 ], bmax = new long[ 3 ];
							for ( int d = 0; d < 3; ++d )
							{
								bmin[ d ] = vb.min( d ) + p[ d ] * unit[ d ] * 8;
								bmax[ d ] = bmin[ d ] + unit[ d ] * 8 - 1;
							}
							if ( Intervals.isEmpty( Intervals.intersect( new FinalInterval( bmin, bmax ), need ) ) )
								continue;
							fs.add( ex.submit( () -> DisplacementFieldN5Tools.writeDisplacementFieldBlock( tps, vb, spacing, unit, p, w, ds, new DoubleType() ) ) );
						}
			}
			for ( final Future< ? > f : fs )
				f.get();
		}
		ex.shutdown();
		System.out.println( "wrote " + fs.size() + " dfield blocks" );
	}

	// the Spark lambda of SparkFusion for FusionMethod.THIN_PLATE_SPLINE, without saveBlock
	@SuppressWarnings( { "unchecked", "rawtypes" } )
	static ArrayImg fuse( URI xml, URI out, List< ViewId > viewIds, List< ViewId > overlappingViews, double af, Interval interval, Interval zeroMinBlock, int[] blockSize ) throws Exception
	{
		long t0 = System.nanoTime();
		final SpimData2 dataLocal = share ? ( shared != null ? shared : ( shared = load( xml ) ) ) : load( xml );
		final SplitViewerImgLoader sil = ( SplitViewerImgLoader ) dataLocal.getSequenceDescription().getImgLoader();
		final List< ViewId > overlappingUnderlying = SplitImgLoaderThinPlateSplineFusion.underlyingViewIds( overlappingViews, sil.new2oldSetupId() );

		final Map< ViewId, Interval > viewBounds = new HashMap<>();
		final Map rawDfields = new HashMap<>();
		final Map< ViewId, AffineTransform3D > approx = new HashMap<>();
		try ( N5Reader r = URITools.instantiateN5Reader( StorageFormat.ZARR, out ) )
		{
			for ( final ViewId u : overlappingUnderlying )
			{
				final String ds = DisplacementFieldN5Tools.datasetPath( u );
				if ( !r.exists( ds ) )
					continue; // its dfield bbox does not reach the block
				viewBounds.put( u, DisplacementFieldN5Tools.readBbox( r, ds ) );
				final TransformedDisplacementField< ? > df = share
						? sharedDfields.computeIfAbsent( ds, k -> DisplacementFieldN5Tools.readDisplacementFieldAsCellImg( r, k, new DoubleType() ) )
						: DisplacementFieldN5Tools.readDisplacementFieldAsCellImg( r, ds, new DoubleType() );
				rawDfields.put( u, df );
				approx.put( u, DisplacementFieldN5Tools.readApproxAffine( r, ds ) );
			}
		}
		final List< ViewId > ov = new ArrayList<>( overlappingViews );
		ov.removeIf( v -> !viewBounds.containsKey( new ViewId( v.getTimePointId(), sil.new2oldSetupId().get( v.getViewSetupId() ) ) ) );

		final BlockSupplier supplier = SplitImgLoaderThinPlateSplineFusion.initWithLoadedDfields(
				new RealUnsignedShortConverter<>( 0, 65535 ), sil, ov, dataLocal.getSequenceDescription().getViewDescriptions(),
				viewBounds, rawDfields, FusionType.valueOf( System.getProperty( "fusion", "CLOSEST_PIXEL_WINS" ) ), af, 1, null, null, new BoundingBox( interval ),
				new UnsignedShortType(), blockSize, approx );
		final long tInit = System.nanoTime();

		final ArrayImg img = ( ArrayImg ) BlockAlgoUtils.arrayImg( supplier, zeroMinBlock );
		final long tFuse = System.nanoTime();

		long sum = 0;
		for ( final Object o : img )
			sum += ( ( UnsignedShortType ) o ).get();
		System.out.printf( "init %.2fs  fuse %.2fs  (%.1f Mvox/s)  checksum %d%n", ( tInit - t0 ) / 1e9, ( tFuse - tInit ) / 1e9,
				Intervals.numElements( zeroMinBlock ) / ( ( tFuse - tInit ) / 1e3 ), sum );
		final String dump = System.getProperty( "dump" );
		if ( dump != null ) // raw little-endian uint16 for voxel-wise comparisons
		{
			final short[] a = ( short[] ) ( ( net.imglib2.img.basictypeaccess.array.ShortArray ) img.update( null ) ).getCurrentStorageArray();
			final java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate( 2 * a.length ).order( java.nio.ByteOrder.LITTLE_ENDIAN );
			b.asShortBuffer().put( a );
			java.nio.file.Files.write( java.nio.file.Paths.get( dump ), b.array() );
		}
		return img;
	}
}
