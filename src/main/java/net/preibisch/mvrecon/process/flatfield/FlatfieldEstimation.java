package net.preibisch.mvrecon.process.flatfield;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Cast;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.DefaultFlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.FlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.FlatfieldImageInfo;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.MultiResolutionFlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.process.fusion.intensity.AutoThreshold;
import util.URITools;

/**
 * Estimates flatfields from the data itself and hands them to the flatfield-corrected image loader, so everything
 * downstream (BDV, intensity correction, fusion) sees the corrected images. A camera is one channel x illumination:
 * the views that share one sensor frame. Two estimators, for different data:
 * <ul>
 * <li>{@link #fitGainField} (sparse data, few tiles): a log-linear ramp per camera fitted from the tile overlaps
 * ({@link GainField}), written as the bright image of the camera (no dark image);</li>
 * <li>BaSiC (dense data): flat- and darkfield from per-pixel statistics over many tiles ({@link Basic}).</li>
 * </ul>
 */
public class FlatfieldEstimation
{
	/** name of the container (next to the XML) the estimated fields are written to */
	public static final String CONTAINER = "flatfield.zarr";

	/** voxels foreground in both tiles needed for a pair, absolute and as a fraction of the overlap (spotlight's) */
	public static int minOverlapForeground = 256;
	public static double minOverlapFraction = 0.001;
	/** a tile with less foreground than this fraction is empty and takes part in no pair (spotlight's) */
	public static double minTileFraction = 0.001;

	public static String cameraKey( final ViewDescription vd )
	{
		return "channel" + vd.getViewSetup().getChannel().getId() + "_illumination" + vd.getViewSetup().getIllumination().getId();
	}

	/** the image loader below any flatfield correction, so a new estimate never sees an old one */
	public static BasicImgLoader rawLoader( final SpimData2 data )
	{
		final BasicImgLoader il = data.getSequenceDescription().getImgLoader();
		return il instanceof FlatfieldCorrectionWrappedImgLoader ? ( ( FlatfieldCorrectionWrappedImgLoader< ? > ) il ).getWrappedImgLoder() : il;
	}

	/** one tile at the statistics level */
	static class Tile
	{
		ViewId view;
		String camera;
		RandomAccessibleInterval< ? extends RealType< ? > > img;
		long[] dims;
		/** level voxel -> world */
		AffineTransform3D toWorld;
		/** world bounding box of the level-0 image */
		double[] min, max;
		double threshold;
		boolean empty;
	}

	public static class GainFieldResult
	{
		public final List< ViewId > tiles;
		public final List< String > cameraOfTile;
		public final List< GainField.Constraint > constraints;
		public final GainField.Fit fit;
		/** camera key -> field */
		public final Map< String, GainField.Field > fields;

		GainFieldResult( final List< ViewId > tiles, final List< String > cameraOfTile, final List< GainField.Constraint > constraints,
				final GainField.Fit fit, final Map< String, GainField.Field > fields )
		{
			this.tiles = tiles; this.cameraOfTile = cameraOfTile; this.constraints = constraints; this.fit = fit; this.fields = fields;
		}
	}

	/**
	 * Fits one gain field per camera from the overlaps of the given views (spotlight's "intersection" estimator):
	 * per tile an Li threshold at the statistics level; per overlapping pair of the same timepoint, every voxel of
	 * one tile mapped to the nearest voxel of the other, kept if both are above the higher of the two thresholds;
	 * {@code d = log median_a - log median_b}, weighted by the number of such voxels.
	 *
	 * @param maxDownsampling the statistics are measured at the coarsest pyramid level no coarser than this
	 */
	public static GainFieldResult fitGainField( final SpimData2 data, final Collection< ? extends ViewId > views,
			final long maxDownsampling, final int numThreads )
	{
		final BasicImgLoader il = rawLoader( data );
		final List< Tile > tiles = new ArrayList<>();
		for ( final ViewId v : views )
			tiles.add( tile( data, il, v, maxDownsampling ) );

		final ExecutorService ex = Executors.newFixedThreadPool( Math.max( 1, numThreads ) );
		try
		{
			// Li thresholds and empty tiles
			final List< Future< ? > > fs = new ArrayList<>();
			for ( final Tile t : tiles )
				fs.add( ex.submit( () -> {
					t.threshold = AutoThreshold.compute( AutoThreshold.Method.LI, Cast.unchecked( t.img ) );
					long fg = 0, n = 0;
					for ( final RealType< ? > p : net.imglib2.view.Views.iterable( t.img ) )
					{
						++n;
						if ( p.getRealDouble() > t.threshold )
							++fg;
					}
					t.empty = Double.isNaN( t.threshold ) || fg < minTileFraction * n;
					return null;
				} ) );
			for ( final Future< ? > f : fs )
				f.get();

			// pairs
			final List< int[] > pairs = new ArrayList<>();
			for ( int a = 0; a < tiles.size(); ++a )
				for ( int b = a + 1; b < tiles.size(); ++b )
				{
					final Tile ta = tiles.get( a ), tb = tiles.get( b );
					if ( ta.view.getTimePointId() != tb.view.getTimePointId() || ta.empty || tb.empty )
						continue;
					boolean overlap = true;
					for ( int d = 0; d < 3; ++d )
						overlap &= Math.min( ta.max[ d ], tb.max[ d ] ) > Math.max( ta.min[ d ], tb.min[ d ] );
					if ( overlap )
						pairs.add( new int[] { a, b } );
				}

			final List< Future< GainField.Constraint > > cs = new ArrayList<>();
			for ( final int[] p : pairs )
				cs.add( ex.submit( () -> measure( tiles.get( p[ 0 ] ), tiles.get( p[ 1 ] ), p[ 0 ], p[ 1 ] ) ) );
			final List< GainField.Constraint > constraints = new ArrayList<>();
			for ( int i = 0; i < cs.size(); ++i )
			{
				final GainField.Constraint c = cs.get( i ).get();
				final Tile ta = tiles.get( pairs.get( i )[ 0 ] ), tb = tiles.get( pairs.get( i )[ 1 ] );
				if ( c == null )
					System.out.println( "gain field: pair " + ta.view.getViewSetupId() + "-" + tb.view.getViewSetupId() + " has too little shared foreground, skipped" );
				else
				{
					constraints.add( c );
					System.out.println( "gain field: pair " + ta.view.getViewSetupId() + "-" + tb.view.getViewSetupId() + ": " + c );
				}
			}

			final List< String > camKeys = new ArrayList<>();
			final int[] cameraOfTile = new int[ tiles.size() ];
			for ( int i = 0; i < tiles.size(); ++i )
			{
				if ( !camKeys.contains( tiles.get( i ).camera ) )
					camKeys.add( tiles.get( i ).camera );
				cameraOfTile[ i ] = camKeys.indexOf( tiles.get( i ).camera );
			}
			final GainField.Fit fit = GainField.fit( constraints, cameraOfTile );
			final Map< String, GainField.Field > fields = new LinkedHashMap<>();
			fit.fields.forEach( ( c, f ) -> fields.put( camKeys.get( c ), f ) );

			for ( int k = 0; k < GainField.AXES.length; ++k )
				System.out.println( "gain field: axis " + GainField.AXES[ k ] + ": " + fit.status[ k ] );
			fields.forEach( ( c, f ) -> System.out.println( "gain field: " + c + ": " + f ) );
			System.out.println( String.format( "gain field: tile scatter tau = %.3f, %d constraints from %d pairs", fit.tau, constraints.size(), pairs.size() ) );

			final List< ViewId > tileViews = new ArrayList<>();
			final List< String > tileCams = new ArrayList<>();
			for ( final Tile t : tiles )
			{
				tileViews.add( t.view );
				tileCams.add( t.camera );
			}
			return new GainFieldResult( tileViews, tileCams, constraints, fit, fields );
		}
		catch ( final Exception e )
		{
			throw new RuntimeException( e );
		}
		finally
		{
			ex.shutdown();
		}
	}

	private static Tile tile( final SpimData2 data, final BasicImgLoader il, final ViewId v, final long maxDownsampling )
	{
		final Tile t = new Tile();
		t.view = v;
		final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
		t.camera = cameraKey( vd );

		int level = 0;
		AffineTransform3D mip = new AffineTransform3D();
		if ( il instanceof MultiResolutionImgLoader )
		{
			final MultiResolutionImgLoader mr = ( MultiResolutionImgLoader ) il;
			final double[][] res = mr.getSetupImgLoader( v.getViewSetupId() ).getMipmapResolutions();
			long best = 0;
			for ( int l = 0; l < res.length; ++l )
			{
				boolean ok = true;
				for ( final double r : res[ l ] )
					ok &= Math.round( r ) <= maxDownsampling;
				if ( ok && Math.round( res[ l ][ 0 ] ) >= best )
				{
					best = Math.round( res[ l ][ 0 ] );
					level = l;
				}
			}
			t.img = Cast.unchecked( mr.getSetupImgLoader( v.getViewSetupId() ).getImage( v.getTimePointId(), level ) );
			mip = mr.getSetupImgLoader( v.getViewSetupId() ).getMipmapTransforms()[ level ];
		}
		else
			t.img = Cast.unchecked( il.getSetupImgLoader( v.getViewSetupId() ).getImage( v.getTimePointId() ) );

		t.dims = t.img.dimensionsAsLongArray();
		final AffineTransform3D reg = data.getViewRegistrations().getViewRegistration( v ).getModel();
		t.toWorld = reg.copy();
		t.toWorld.concatenate( mip );

		// world box of the level-0 pixel box [0, size]^3, as spotlight
		final long[] size = vd.getViewSetup().getSize().dimensionsAsLongArray();
		t.min = new double[] { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
		t.max = new double[] { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
		final double[] p = new double[ 3 ], q = new double[ 3 ];
		for ( int c = 0; c < 8; ++c )
		{
			for ( int d = 0; d < 3; ++d )
				p[ d ] = ( c >> d & 1 ) == 0 ? 0 : size[ d ];
			reg.apply( p, q );
			for ( int d = 0; d < 3; ++d )
			{
				t.min[ d ] = Math.min( t.min[ d ], q[ d ] );
				t.max[ d ] = Math.max( t.max[ d ], q[ d ] );
			}
		}
		return t;
	}

	/** the constraint of one pair, or null if it shares too little foreground */
	static GainField.Constraint measure( final Tile a, final Tile b, final int ia, final int ib )
	{
		// a's voxels whose world position falls in the intersection of the two boxes
		final double[] lo = new double[ 3 ], hi = new double[ 3 ];
		for ( int d = 0; d < 3; ++d )
		{
			lo[ d ] = Math.max( a.min[ d ], b.min[ d ] );
			hi[ d ] = Math.min( a.max[ d ], b.max[ d ] );
		}
		final long[] from = new long[ 3 ], to = new long[ 3 ];
		Arrays.fill( from, Long.MAX_VALUE );
		Arrays.fill( to, Long.MIN_VALUE );
		final double[] p = new double[ 3 ], q = new double[ 3 ];
		for ( int c = 0; c < 8; ++c )
		{
			for ( int d = 0; d < 3; ++d )
				p[ d ] = ( c >> d & 1 ) == 0 ? lo[ d ] : hi[ d ];
			a.toWorld.applyInverse( q, p );
			for ( int d = 0; d < 3; ++d )
			{
				from[ d ] = Math.min( from[ d ], ( long ) Math.floor( q[ d ] ) );
				to[ d ] = Math.max( to[ d ], ( long ) Math.ceil( q[ d ] ) );
			}
		}
		for ( int d = 0; d < 3; ++d )
		{
			from[ d ] = Math.max( from[ d ], 0 );
			to[ d ] = Math.min( to[ d ], a.dims[ d ] - 1 );
			if ( to[ d ] < from[ d ] )
				return null;
		}

		// a level voxel -> b level voxel
		final AffineTransform3D aToB = b.toWorld.inverse().copy(); // inverse() is the cached object, never modify it
		aToB.concatenate( a.toWorld );

		final double thr = Math.max( a.threshold, b.threshold );
		final RandomAccess< ? extends RealType< ? > > ra = a.img.randomAccess(), rb = b.img.randomAccess();
		float[] va = new float[ 1 << 16 ], vb = new float[ 1 << 16 ];
		int n = 0;
		long mapped = 0;
		double sya = 0, sxa = 0, syb = 0, sxb = 0;
		final long[] posB = new long[ 3 ];
		for ( long z = from[ 2 ]; z <= to[ 2 ]; ++z )
			for ( long y = from[ 1 ]; y <= to[ 1 ]; ++y )
				for ( long x = from[ 0 ]; x <= to[ 0 ]; ++x )
				{
					p[ 0 ] = x; p[ 1 ] = y; p[ 2 ] = z;
					aToB.apply( p, q );
					boolean inside = true;
					for ( int d = 0; d < 3; ++d )
					{
						posB[ d ] = Math.round( q[ d ] );
						inside &= posB[ d ] >= 0 && posB[ d ] < b.dims[ d ];
					}
					if ( !inside )
						continue;
					++mapped;
					ra.setPosition( x, 0 ); ra.setPosition( y, 1 ); ra.setPosition( z, 2 );
					final double fa = ra.get().getRealDouble();
					if ( fa <= thr )
						continue;
					rb.setPosition( posB );
					final double fb = rb.get().getRealDouble();
					if ( fb <= thr )
						continue;
					if ( n == va.length )
					{
						va = Arrays.copyOf( va, 2 * n );
						vb = Arrays.copyOf( vb, 2 * n );
					}
					va[ n ] = ( float ) fa;
					vb[ n ] = ( float ) fb;
					++n;
					sya += GainField.frameCoord( y, ( int ) a.dims[ 1 ] );
					sxa += GainField.frameCoord( x, ( int ) a.dims[ 0 ] );
					syb += GainField.frameCoord( posB[ 1 ], ( int ) b.dims[ 1 ] );
					sxb += GainField.frameCoord( posB[ 0 ], ( int ) b.dims[ 0 ] );
				}

		if ( n < minOverlapForeground || n < minOverlapFraction * mapped )
			return null;
		final double d = Math.log( median( va, n ) ) - Math.log( median( vb, n ) );
		return new GainField.Constraint( ia, ib, d, n, new double[] { sya / n, sxa / n, syb / n, sxb / n } );
	}

	/** numpy's median of the first n values (sorts them) */
	static double median( final float[] v, final int n )
	{
		Arrays.sort( v, 0, n );
		return n % 2 == 1 ? v[ n / 2 ] : 0.5 * ( ( double ) v[ n / 2 - 1 ] + v[ n / 2 ] );
	}

	/**
	 * Writes each camera's field as a 2D float32 bright image (level-0 frame, x by y) into {@link #CONTAINER} next to
	 * the XML and links it to every view of that camera; returns the flatfield-corrected loader (now the dataset's).
	 * A camera whose field has no kept axis (all coefficients zero) gets no image.
	 */
	public static FlatfieldCorrectionWrappedImgLoader< ? > writeGainFields( final SpimData2 data, final Collection< ? extends ViewId > views,
			final Map< String, GainField.Field > fields )
	{
		final URI container = data.getBasePathURI().resolve( CONTAINER + "/" );
		final FlatfieldCorrectionWrappedImgLoader< ? > ffIL = flatfieldLoader( data );
		final Map< String, FlatfieldImageInfo > written = new HashMap<>();
		try ( N5Writer w = URITools.instantiateN5Writer( StorageFormat.ZARR, container ) )
		{
			for ( final ViewId v : views )
			{
				final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
				final String cam = cameraKey( vd );
				final GainField.Field f = fields.get( cam );
				if ( f == null || ( f.c[ 0 ] == 0 && f.c[ 1 ] == 0 ) )
					continue;
				final long[] size = vd.getViewSetup().getSize().dimensionsAsLongArray();
				final String dataset = cam + "/gainfield_" + size[ 0 ] + "x" + size[ 1 ];
				final FlatfieldImageInfo info = written.computeIfAbsent( dataset, ds -> {
					final float[] plane = f.plane( ( int ) size[ 1 ], ( int ) size[ 0 ] );   // row-major (y, x) = x fastest
					N5Utils.save( ArrayImgs.floats( plane, size[ 0 ], size[ 1 ] ), w, ds, new int[] { ( int ) size[ 0 ], ( int ) size[ 1 ] },
							new org.janelia.saalfeldlab.n5.RawCompression() );
					w.setAttribute( ds, "gainField", Map.of( "c_y", f.c[ 0 ], "c_x", f.c[ 1 ], "sigma_y", f.sigma[ 0 ], "sigma_x", f.sigma[ 1 ] ) );
					return new FlatfieldImageInfo( container, StorageFormat.ZARR, ds );
				} );
				ffIL.setBrightImage( v, info );
			}
		}
		data.getSequenceDescription().setImgLoader( ( ImgLoader ) ffIL );
		return ffIL;
	}

	/** the dataset's flatfield-corrected loader, wrapping the raw one if there is none yet */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	public static FlatfieldCorrectionWrappedImgLoader< ? > flatfieldLoader( final SpimData2 data )
	{
		final BasicImgLoader il = data.getSequenceDescription().getImgLoader();
		if ( il instanceof FlatfieldCorrectionWrappedImgLoader )
			return ( FlatfieldCorrectionWrappedImgLoader< ? > ) il;
		if ( il instanceof MultiResolutionImgLoader )
			return new MultiResolutionFlatfieldCorrectionWrappedImgLoader( ( MultiResolutionImgLoader ) il, true );
		return new DefaultFlatfieldCorrectionWrappedImgLoader( ( ImgLoader ) il, true );
	}
}
