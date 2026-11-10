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
package net.preibisch.mvrecon.process.fusion.intensity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.janelia.saalfeldlab.n5.N5Exception;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;

import mpicbg.spim.data.sequence.ViewId;
import util.URITools;

/**
 * The sidecar files match-intensities writes next to the pairwise matches:
 * <ul>
 * <li>"thresholds.txt" -- the per-view thresholds the gains were measured on
 *     ("timepointId setupId threshold" per line), so the solve and the fusion can pick up
 *     what the matching used;</li>
 * <li>"backgrounds.txt" -- each view's background level, same format, a low percentile of the
 *     same image the threshold is computed from; the solve can add it as a match so that
 *     overlapping views' backgrounds come out level;</li>
 * <li>"matching-done.txt" -- written only once matching has finished, recording the
 *     parameters. Without it, a crashed or killed matching run leaves a directory that
 *     still looks complete (the per-pair files from a PREVIOUS run are all still there),
 *     and the solve silently produces coefficients from stale matches.</li>
 * </ul>
 */
public class IntensityThresholds
{
	private static final String FILENAME = "thresholds.txt";
	private static final String BACKGROUNDS = "backgrounds.txt";

	/**
	 * Percentile of the background a view's background level is taken at. Low rather than the median because
	 * tissue only ever pulls the estimate up (spotlight's emptiness stage uses the same).
	 */
	public static final double BACKGROUND_PERCENTILE = 5;

	private static final String DONE = "matching-done.txt";

	/** Records that matching finished, and with which parameters. */
	public static void writeDone( final URI matchesURI, final String parameters ) throws IOException
	{
		final URI fn = matchesURI.resolve( DONE );

		try ( final PrintWriter pw = URITools.openFileWriteCloudWriter( URITools.getKeyValueAccess( matchesURI ), fn ) )
		{
			pw.println( new java.util.Date( System.currentTimeMillis() ).toString() );
			pw.println( parameters );
		}

		System.out.println( "Wrote " + fn );
	}

	/**
	 * @return the parameters matching was run with, or null if matching never finished
	 *         (crashed, was killed, or predates this marker)
	 */
	public static String readDone( final URI matchesURI ) throws IOException
	{
		final URI fn = matchesURI.resolve( DONE );

		try (
				final InputStream is = URITools.openFileReadCloudStream( URITools.getKeyValueAccess( matchesURI ), fn );
				final BufferedReader br = new BufferedReader( new InputStreamReader( is ) ) )
		{
			final String when = br.readLine();
			final String parameters = br.readLine();
			return ( when == null ? "" : when ) + ( parameters == null ? "" : " | " + parameters );
		}
		catch ( final N5Exception.N5NoSuchKeyException e )
		{
			return null;
		}
	}

	public static void write( final URI matchesURI, final Map< ViewId, Double > thresholds ) throws IOException
	{
		write( matchesURI, FILENAME, thresholds );
	}

	public static void writeBackgrounds( final URI matchesURI, final Map< ViewId, Double > backgrounds ) throws IOException
	{
		write( matchesURI, BACKGROUNDS, backgrounds );
	}

	private static void write( final URI matchesURI, final String filename, final Map< ViewId, Double > thresholds ) throws IOException
	{
		final URI fn = matchesURI.resolve( filename );

		try ( final PrintWriter pw = URITools.openFileWriteCloudWriter( URITools.getKeyValueAccess( matchesURI ), fn ) )
		{
			thresholds.entrySet().stream()
					.sorted( Comparator.comparingInt( ( Map.Entry< ViewId, Double > e ) -> e.getKey().getTimePointId() )
							.thenComparingInt( e -> e.getKey().getViewSetupId() ) )
					.forEach( e -> pw.println( e.getKey().getTimePointId() + " " + e.getKey().getViewSetupId() + " " + e.getValue() ) );
		}

		System.out.println( "Wrote " + fn );
	}

	/**
	 * @return the stored thresholds, or null if the matching ran with a fixed threshold
	 *         (i.e. no thresholds.txt was written)
	 */
	public static Map< ViewId, Double > read( final URI matchesURI ) throws IOException
	{
		return read( matchesURI, FILENAME );
	}

	/**
	 * @return the stored background levels, or null if the matching did not write them
	 *         (fixed --minThreshold, or matches from before backgrounds were recorded)
	 */
	public static Map< ViewId, Double > readBackgrounds( final URI matchesURI ) throws IOException
	{
		return read( matchesURI, BACKGROUNDS );
	}

	private static Map< ViewId, Double > read( final URI matchesURI, final String filename ) throws IOException
	{
		final URI fn = matchesURI.resolve( filename );

		try (
				final InputStream is = URITools.openFileReadCloudStream( URITools.getKeyValueAccess( matchesURI ), fn );
				final BufferedReader br = new BufferedReader( new InputStreamReader( is ) ) )
		{
			final Map< ViewId, Double > thresholds = new HashMap<>();

			for ( String line = br.readLine(); line != null; line = br.readLine() )
			{
				final String[] tokens = line.trim().split( "\\s+" );

				if ( tokens.length != 3 )
					throw new IOException( "Cannot parse '" + line + "' in " + fn + ", expected 'timepointId setupId threshold'" );

				thresholds.put(
						new ViewId( Integer.parseInt( tokens[ 0 ] ), Integer.parseInt( tokens[ 1 ] ) ),
						Double.parseDouble( tokens[ 2 ] ) );
			}

			return thresholds;
		}
		catch ( final N5Exception.N5NoSuchKeyException e )
		{
			return null;
		}
	}

	/**
	 * The {@code percentile}-th percentile of {@code img}, from an exact histogram of the values rounded to
	 * integers in [0, 65535] (the 256 bins between min and max that the thresholds use are far too coarse
	 * for a background level when a few bright voxels stretch the range).
	 */
	public static < T extends RealType< T > > double percentile( final RandomAccessibleInterval< T > img, final double percentile )
	{
		final long[] counts = new long[ 65536 ];
		long n = 0;
		for ( final T t : Views.iterable( img ) )
		{
			++counts[ ( int ) Math.max( 0, Math.min( 65535, Math.round( t.getRealDouble() ) ) ) ];
			++n;
		}
		final long rank = ( long ) Math.ceil( percentile / 100.0 * n );
		long cumulative = 0;
		for ( int v = 0; v < counts.length; ++v )
		{
			cumulative += counts[ v ];
			if ( cumulative >= rank && cumulative > 0 )
				return v;
		}
		return Double.NaN;
	}

	/** The median of the voxels of {@code img} above {@code threshold}, from the same exact histogram, NaN if there are none. */
	public static < T extends RealType< T > > double medianAbove( final RandomAccessibleInterval< T > img, final double threshold )
	{
		final long[] counts = new long[ 65536 ];
		long n = 0;
		for ( final T t : Views.iterable( img ) )
		{
			final double v = t.getRealDouble();
			if ( v > threshold )
			{
				++counts[ ( int ) Math.max( 0, Math.min( 65535, Math.round( v ) ) ) ];
				++n;
			}
		}
		final long rank = ( n + 1 ) / 2;
		long cumulative = 0;
		for ( int v = 0; v < counts.length && n > 0; ++v )
		{
			cumulative += counts[ v ];
			if ( cumulative >= rank )
				return v;
		}
		return Double.NaN;
	}
}
