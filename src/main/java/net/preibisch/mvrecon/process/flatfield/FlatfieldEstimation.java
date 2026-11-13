package net.preibisch.mvrecon.process.flatfield;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Cast;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.DefaultFlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.FlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.FlatfieldImageInfo;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.MultiResolutionFlatfieldCorrectionWrappedImgLoader;
import util.URITools;

/**
 * Estimates flatfields from the data itself and hands them to the flatfield-corrected image loader, so everything
 * downstream (BDV, intensity correction, fusion) sees the corrected images. A camera is one channel x illumination:
 * the views that share one sensor frame. The estimator is BaSiC: flat- and darkfield from per-pixel statistics over
 * many tiles ({@link Basic}). (Gain fields, a log-linear ramp per camera fitted from the tile overlaps or from the
 * tiles' interiors, were removed in 2026-11: on the two datasets they were tried on, neither improved on an intensity
 * correction with 2,2,2 coefficients, and the overlap fit took bleaching for a ramp.)
 */
public class FlatfieldEstimation
{
	/** name of the container (next to the XML) the estimated fields are written to */
	public static final String CONTAINER = "flatfield.zarr";

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
	}

	/**
	 * The pyramid levels every one of {@code views} has, finest first, as the first view's (rounded) downsampling
	 * factors; a level counts if its largest factor is the largest factor of a level of every view (the estimators
	 * take that largest factor and pick each view's coarsest level within it). A dataset without a pyramid has one,
	 * {1, 1, 1}.
	 */
	public static List< long[] > commonLevels( final SpimData2 data, final Collection< ? extends ViewId > views )
	{
		final BasicImgLoader il = rawLoader( data );
		final List< long[] > levels = new ArrayList<>();
		if ( !( il instanceof MultiResolutionImgLoader ) || views.isEmpty() )
		{
			levels.add( new long[] { 1, 1, 1 } );
			return levels;
		}
		final MultiResolutionImgLoader mr = ( MultiResolutionImgLoader ) il;
		java.util.Set< Long > common = null;
		for ( final ViewId v : views )
		{
			final java.util.Set< Long > mine = new java.util.HashSet<>();
			for ( final double[] r : mr.getSetupImgLoader( v.getViewSetupId() ).getMipmapResolutions() )
				mine.add( maxFactor( r ) );
			if ( common == null )
				common = mine;
			else
				common.retainAll( mine );
		}
		for ( final double[] r : mr.getSetupImgLoader( views.iterator().next().getViewSetupId() ).getMipmapResolutions() )
			if ( common.contains( maxFactor( r ) ) )
				levels.add( Arrays.stream( r ).mapToLong( Math::round ).toArray() );
		if ( levels.isEmpty() )
			levels.add( new long[] { 1, 1, 1 } );
		return levels;
	}

	/** the largest (rounded) downsampling factor of a level */
	public static long maxFactor( final double[] resolution )
	{
		long f = 1;
		for ( final double r : resolution )
			f = Math.max( f, Math.round( r ) );
		return f;
	}

	/** the largest (rounded) downsampling factor of a level */
	public static long maxFactor( final long[] factors )
	{
		return Arrays.stream( factors ).max().orElse( 1 );
	}

	private static Tile tile( final SpimData2 data, final BasicImgLoader il, final ViewId v, final long maxDownsampling )
	{
		final Tile t = new Tile();
		t.view = v;
		final ViewDescription vd = data.getSequenceDescription().getViewDescription( v );
		t.camera = cameraKey( vd );

		int level = 0;
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
		}
		else
			t.img = Cast.unchecked( il.getSetupImgLoader( v.getViewSetupId() ).getImage( v.getTimePointId() ) );

		t.dims = t.img.dimensionsAsLongArray();
		return t;
	}

	/** a row-major (y, x) plane as a 2D float32 dataset (x by y) */
	private static FlatfieldImageInfo save( final N5Writer w, final URI container, final String dataset, final float[] plane, final long sx, final long sy )
	{
		if ( w.exists( dataset ) )
			w.remove( dataset ); // a new estimate replaces the old one
		N5Utils.save( ArrayImgs.floats( plane, sx, sy ), w, dataset, new int[] { ( int ) sx, ( int ) sy }, new org.janelia.saalfeldlab.n5.RawCompression() );
		return new FlatfieldImageInfo( container, StorageFormat.ZARR, dataset );
	}

	// ─── BaSiC ────────────────────────────────────────────────────────────────

	/** the number of quantile planes BaSiC is fitted to (q000 .. q100 in steps of 5, as spotlight) */
	public static final int NUM_QUANTILES = 21;

	/**
	 * Estimates flat- and darkfield per camera with BaSiC and links them to the views (bright = flat + dark, so that
	 * the loader's {@code (raw - dark) * mean(bright - dark) / (bright - dark)} is BaSiC's {@code (raw - dark) / flat}).
	 *
	 * The stack BaSiC is fitted to is spotlight's qstack: per pixel of the statistics level, each tile's z column
	 * sorted (in blocks of {@code p = 21 floor((Z - 1) / 21)}, averaged, the trailing slices dropped), the sorted
	 * columns averaged over the camera's tiles, and 21 R-7 quantiles of that, rounded to uint16. With fewer than 21
	 * slices, every slice of every tile is a frame instead.
	 */
	public static FlatfieldCorrectionWrappedImgLoader< ? > estimateBasic( final SpimData2 data, final Collection< ? extends ViewId > views,
			final long maxDownsampling, final Basic.Parameters params )
	{
		final BasicImgLoader il = rawLoader( data );
		final Map< String, List< Tile > > cameras = new LinkedHashMap<>();
		for ( final ViewId v : views )
		{
			final Tile t = tile( data, il, v, maxDownsampling );
			cameras.computeIfAbsent( t.camera, c -> new ArrayList<>() ).add( t );
		}

		final URI container = data.getBasePathURI().resolve( CONTAINER + "/" );
		final FlatfieldCorrectionWrappedImgLoader< ? > ffIL = flatfieldLoader( data );
		try ( N5Writer w = URITools.instantiateN5Writer( StorageFormat.ZARR, container ) )
		{
			for ( final Map.Entry< String, List< Tile > > e : cameras.entrySet() )
			{
				final List< Tile > tiles = e.getValue();
				final long[] d0 = tiles.get( 0 ).dims;
				final long[] size0 = data.getSequenceDescription().getViewDescription( tiles.get( 0 ).view ).getViewSetup().getSize().dimensionsAsLongArray();
				for ( final Tile t : tiles )
					if ( t.dims[ 0 ] != d0[ 0 ] || t.dims[ 1 ] != d0[ 1 ] )
						throw new IllegalArgumentException( "BaSiC: the views of " + e.getKey() + " differ in xy size (one camera is one sensor frame)" );

				final float[][] stack = qstack( tiles );
				System.out.println( "BaSiC: " + e.getKey() + ": " + tiles.size() + " views, stack of " + stack.length + " frames of " + d0[ 0 ] + "x" + d0[ 1 ] );
				final Basic.Fields f = Basic.estimate( stack, ( int ) d0[ 1 ], ( int ) d0[ 0 ], params, ( int ) size0[ 1 ], ( int ) size0[ 0 ] );

				final float[] bright = new float[ f.flat.length ];
				double flatMin = Double.MAX_VALUE, flatMax = 0, darkMean = 0;
				for ( int i = 0; i < bright.length; ++i )
				{
					bright[ i ] = f.flat[ i ] + f.dark[ i ];
					flatMin = Math.min( flatMin, f.flat[ i ] );
					flatMax = Math.max( flatMax, f.flat[ i ] );
					darkMean += f.dark[ i ] / bright.length;
				}
				System.out.println( String.format( "BaSiC: %s: lambda %.4g, flatfield %.3f .. %.3f, darkfield mean %.1f", e.getKey(), f.lambda, flatMin, flatMax, darkMean ) );

				final String prefix = e.getKey() + "/basic_" + size0[ 0 ] + "x" + size0[ 1 ];
				final FlatfieldImageInfo bi = save( w, container, prefix + "/bright", bright, size0[ 0 ], size0[ 1 ] );
				final boolean hasDark = params.estimateDarkfield || params.darkfieldOverride != null;
				final FlatfieldImageInfo di = hasDark ? save( w, container, prefix + "/dark", f.dark, size0[ 0 ], size0[ 1 ] ) : null;
				w.setAttribute( prefix, "basic", Map.of( "lambda", f.lambda, "frames", stack.length, "views", tiles.size() ) );
				for ( final Tile t : tiles )
				{
					ffIL.setBrightImage( t.view, bi );
					ffIL.setDarkImage( t.view, di );
				}
			}
		}
		data.getSequenceDescription().setImgLoader( ( ImgLoader ) ffIL );
		return ffIL;
	}

	/** spotlight's qstack of one camera's tiles (all of one xy size): frames as row-major (y, x) planes */
	static float[][] qstack( final List< Tile > tiles )
	{
		final int sx = ( int ) tiles.get( 0 ).dims[ 0 ], sy = ( int ) tiles.get( 0 ).dims[ 1 ];
		int nz = Integer.MAX_VALUE;
		for ( final Tile t : tiles )
			nz = ( int ) Math.min( nz, t.dims[ 2 ] );

		if ( nz < NUM_QUANTILES )
		{
			// raw mode: every slice of every tile is a frame
			final float[][] frames = new float[ tiles.size() * nz ][];
			int k = 0;
			for ( final Tile t : tiles )
				for ( int z = 0; z < nz; ++z )
				{
					final float[] f = new float[ sx * sy ];
					final RandomAccess< ? extends RealType< ? > > ra = t.img.randomAccess();
					for ( int y = 0; y < sy; ++y )
						for ( int x = 0; x < sx; ++x )
						{
							ra.setPosition( new long[] { x, y, z } );
							f[ y * sx + x ] = ra.get().getRealFloat();
						}
					frames[ k++ ] = f;
				}
			return frames;
		}

		final int p = Math.min( nz, ( ( nz - 1 ) / NUM_QUANTILES ) * NUM_QUANTILES );
		final int nblk = nz / p;
		final float[][] planes = new float[ NUM_QUANTILES ][ sx * sy ];
		final int chunk = 128;
		final float[] col = new float[ nz ];
		for ( int y0 = 0; y0 < sy; y0 += chunk )
			for ( int x0 = 0; x0 < sx; x0 += chunk )
			{
				final int cw = Math.min( chunk, sx - x0 ), ch = Math.min( chunk, sy - y0 );
				// mean over tiles of each pixel's sorted (block-averaged) column
				final double[][] merged = new double[ cw * ch ][ p ];
				for ( final Tile t : tiles )
				{
					final RandomAccess< ? extends RealType< ? > > ra = t.img.randomAccess();
					for ( int y = 0; y < ch; ++y )
						for ( int x = 0; x < cw; ++x )
						{
							ra.setPosition( x0 + x, 0 );
							ra.setPosition( y0 + y, 1 );
							for ( int z = 0; z < nz; ++z )
							{
								ra.setPosition( z, 2 );
								col[ z ] = ra.get().getRealFloat();
							}
							final double[] m = merged[ y * cw + x ];
							for ( int b = 0; b < nblk; ++b )
							{
								Arrays.sort( col, b * p, ( b + 1 ) * p );
								for ( int i = 0; i < p; ++i )
									m[ i ] += ( double ) col[ b * p + i ] / nblk / tiles.size();
							}
						}
				}
				for ( int y = 0; y < ch; ++y )
					for ( int x = 0; x < cw; ++x )
					{
						final double[] m = merged[ y * cw + x ];
						Arrays.sort( m );
						for ( int q = 0; q < NUM_QUANTILES; ++q )
							planes[ q ][ ( y0 + y ) * sx + x0 + x ] = ( float ) Math.min( Math.max( Math.rint( r7( m, q / ( double ) ( NUM_QUANTILES - 1 ) ) ), 0 ), 65535 );
					}
			}
		return planes;
	}

	/** R-7 quantile of a sorted vector, as spotlight's orderstats */
	static double r7( final double[] v, final double q )
	{
		final int n = v.length;
		final double aleph = n * q + ( 1 - q );
		final int j = ( int ) Math.min( Math.max( ( long ) aleph, 1 ), n - 1 );
		final double g = Math.min( Math.max( aleph - j, 0 ), 1 );
		return ( 1 - g ) * v[ j - 1 ] + g * v[ j ];
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
