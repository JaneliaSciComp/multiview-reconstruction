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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;

import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore.Points;
import util.URITools;

/**
 * The text-file layout of interest points, the one before the N5 layout. The XML text of such a label is a file name
 * without extension, {@code interestpoints/<name>} (any name, e.g. {@code interestpoints/tpId_0_viewSetupId_0.beads} or
 * {@code interestpoints/new_tpId_0_viewSetupId_0.beads}; the view and the label are the attributes of the XML element):
 * the points are {@code <base>/<name>.ip.txt} (header {@code id x y z}, tab-separated, one point per line, no attributes)
 * and the correspondences {@code <base>/<name>.corr.txt} (header {@code id corresponding_timepoint_id
 * corresponding_viewsetup_id corresponding_label corresponding_id}), a file that does not exist until the label was matched.
 *
 * Read-only: nothing is written in this layout anymore. {@link InterestPointsZarr} reads a label from here when neither the
 * store nor the N5 has its entry (the XML text stays the file name until then), saves a change into the store, and the
 * explorer's "Resave as ZARR store" moves such labels ({@link InterestPointsN5ToZarr#moveIntoStore}). The files are never
 * deleted by the code.
 *
 * @author Stephan Preibisch
 */
public class InterestPointsTextFiles
{
	public static final String IP_EXT = ".ip.txt";
	public static final String CORR_EXT = ".corr.txt";

	/** the directory the XML text of such a label starts with */
	public static final String DIRECTORY = "interestpoints";

	private InterestPointsTextFiles() {}

	/** @return true if the XML text names text files: {@code interestpoints/...} (or with a backslash, from an old Windows save) */
	public static boolean isTextFilePath( final String xmlText )
	{
		return xmlText != null && normalize( xmlText ).toLowerCase().startsWith( DIRECTORY + "/" );
	}

	/** @return the XML text trimmed, backslashes replaced by slashes */
	public static String normalize( final String xmlText )
	{
		return xmlText.trim().replace( '\\', '/' );
	}

	/** @return true if the points file ({@code <base>/<file>.ip.txt}) exists */
	public static boolean exists( final URI baseDir, final String file )
	{
		final KeyValueAccess kva = URITools.getKeyValueAccess( baseDir );
		return kva.exists( kva.compose( baseDir, file + IP_EXT ) );
	}

	/**
	 * Reads the points of a label; the attributes (response, sigma, intensity) are NaN, the layout never stored them.
	 *
	 * @param baseDir the dataset directory
	 * @param file the XML text of the label, {@code interestpoints/<name>}
	 * @return the points, or null if {@code <base>/<file>.ip.txt} does not exist
	 * @throws RuntimeException if the file cannot be read or parsed
	 */
	public static Points points( final URI baseDir, final String file )
	{
		final KeyValueAccess kva = URITools.getKeyValueAccess( baseDir );
		final String path = kva.compose( baseDir, file + IP_EXT );

		if ( !kva.exists( path ) )
			return null;

		final ArrayList< int[] > ids = new ArrayList<>();
		final ArrayList< double[] > locations = new ArrayList<>();

		try ( final LockedChannel channel = kva.lockForReading( path ); final BufferedReader in = new BufferedReader( channel.newReader() ) )
		{
			int lineNumber = skipHeader( in, path );
			String line;

			while ( ( line = in.readLine() ) != null )
			{
				++lineNumber;

				if ( line.isBlank() )
					continue;

				final String[] p = line.split( "\t" );

				if ( p.length < 4 )
					throw new RuntimeException( "Cannot parse line " + lineNumber + " of '" + path + "', expected 'id x y z': '" + line + "'" );

				ids.add( new int[] { Integer.parseInt( p[ 0 ].trim() ) } );
				locations.add( new double[] { Double.parseDouble( p[ 1 ].trim() ), Double.parseDouble( p[ 2 ].trim() ), Double.parseDouble( p[ 3 ].trim() ) } );
			}
		}
		catch ( final IOException | NumberFormatException e )
		{
			throw new RuntimeException( "Cannot read the interest points of '" + path + "': " + e, e );
		}

		final int n = ids.size();
		final int[] idArray = new int[ n ];
		final float[] data = new float[ n * InterestPointsZarrStore.COLUMNS ];

		for ( int i = 0; i < n; ++i )
		{
			final double[] l = locations.get( i );
			final int d = i * InterestPointsZarrStore.COLUMNS;
			idArray[ i ] = ids.get( i )[ 0 ];
			data[ d ] = (float) l[ 0 ];
			data[ d + 1 ] = (float) l[ 1 ];
			data[ d + 2 ] = (float) l[ 2 ];
			data[ d + 3 ] = Float.NaN;
			data[ d + 4 ] = Float.NaN;
			data[ d + 5 ] = Float.NaN;
		}

		return new Points( idArray, data );
	}

	/**
	 * Reads the correspondences of a label.
	 *
	 * @param baseDir the dataset directory
	 * @param file the XML text of the label, {@code interestpoints/<name>}
	 * @return the correspondences; an empty list if the label has points but no {@code .corr.txt} (it was never matched);
	 *         null if the label has no points file either
	 * @throws RuntimeException if the file cannot be read or parsed
	 */
	public static List< CorrespondingInterestPoints > correspondences( final URI baseDir, final String file )
	{
		final KeyValueAccess kva = URITools.getKeyValueAccess( baseDir );
		final String path = kva.compose( baseDir, file + CORR_EXT );

		if ( !kva.exists( path ) )
			return kva.exists( kva.compose( baseDir, file + IP_EXT ) ) ? new ArrayList<>() : null;

		final ArrayList< CorrespondingInterestPoints > correspondences = new ArrayList<>();

		try ( final LockedChannel channel = kva.lockForReading( path ); final BufferedReader in = new BufferedReader( channel.newReader() ) )
		{
			int lineNumber = skipHeader( in, path );
			String line;

			while ( ( line = in.readLine() ) != null )
			{
				++lineNumber;

				if ( line.isBlank() )
					continue;

				final String[] p = line.split( "\t" );

				if ( p.length < 5 )
					throw new RuntimeException( "Cannot parse line " + lineNumber + " of '" + path + "', expected 'id corresponding_timepoint_id corresponding_viewsetup_id corresponding_label corresponding_id': '" + line + "'" );

				correspondences.add( new CorrespondingInterestPoints(
						Integer.parseInt( p[ 0 ].trim() ),
						Integer.parseInt( p[ 1 ].trim() ),
						Integer.parseInt( p[ 2 ].trim() ),
						p[ 3 ],
						Integer.parseInt( p[ 4 ].trim() ) ) );
			}
		}
		catch ( final IOException | NumberFormatException e )
		{
			throw new RuntimeException( "Cannot read the correspondences of '" + path + "': " + e, e );
		}

		return correspondences;
	}

	/**
	 * Skips to the header line (the first line that starts with "id"), as the old reader did.
	 *
	 * @return the number of lines read
	 */
	private static int skipHeader( final BufferedReader in, final String path ) throws IOException
	{
		int lineNumber = 0;
		String line;

		while ( ( line = in.readLine() ) != null )
		{
			++lineNumber;

			if ( line.startsWith( "id" ) )
				return lineNumber;
		}

		throw new RuntimeException( "'" + path + "' has no header line starting with 'id'" );
	}
}
