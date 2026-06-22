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
package util;

import static mpicbg.spim.data.XmlKeys.SPIMDATA_TAG;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Date;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import org.janelia.saalfeldlab.googlecloud.GoogleCloudUtils;
import org.janelia.saalfeldlab.n5.FileSystemKeyValueAccess;
import org.janelia.saalfeldlab.n5.KeyValueAccess;
import org.janelia.saalfeldlab.n5.LockedChannel;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Reader;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer;
import org.janelia.saalfeldlab.n5.universe.N5Factory;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.CoordinateTransformation;
import org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.coordinateTransformations.CoordinateTransformationAdapter;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrReader;
import org.janelia.saalfeldlab.n5.zarr.N5ZarrWriter;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueReader;
import org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3KeyValueWriter;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializer;

import bdv.ViewerImgLoader;
import bdv.img.n5.N5ImageLoader;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.SpimDataIOException;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;

public class URITools
{
	private final static Pattern HTTPS_SCHEME = Pattern.compile( "http(s)?", Pattern.CASE_INSENSITIVE );
	private final static Pattern FILE_SCHEME = Pattern.compile( "file", Pattern.CASE_INSENSITIVE );

	public static int cloudThreads = 256;
	public static String s3Region = null;

	// caches auto-detected S3 regions per bucket so detectS3Region() probes the
	// network only once per bucket (multiple S3 handles are opened per dataset load)
	private static final java.util.Map< String, String > s3RegionCache = new java.util.concurrent.ConcurrentHashMap<>();

	public static boolean useS3CredentialsWrite = true;
	public static boolean useS3CredentialsRead = true;

	public static URI getParentURINoEx( final URI uri )
	{
		try
		{
			return getParentURI(uri);
		}
		catch (SpimDataIOException e)
		{
			e.printStackTrace();
			return null;
		}
	}

	public static URI getParentURI( final URI uri ) throws SpimDataIOException
	{
		try
		{
			final String uriPath = uri.getPath();
			final int parentSlash = uriPath.lastIndexOf( "/", uriPath.length() - 2 );
			if ( parentSlash < 0 )
			{
				throw new SpimDataIOException( "URI is already at the root" );
			}
			// NB: The "+ 1" below is *very important*, so that the resultant URI
			// ends in a trailing slash. The behaviour of URI differs depending on
			// whether this trailing slash is present; specifically:
			//
			// * new URI("file:/foo/bar/").resolve(".") -> "file:/foo/bar/"
			// * new URI("file:/foo/bar").resolve(".") -> "file:/foo/"
			//
			// That is: /foo/bar/ is considered to be in the directory /foo/bar,
			// whereas /foo/bar is considered to be in the directory /foo.
			final String parentPath = uriPath.substring( 0, parentSlash + 1 );
			return new URI( uri.getScheme(), uri.getUserInfo(), uri.getHost(),
				uri.getPort(), parentPath, uri.getQuery(), uri.getFragment() );
		}
		catch ( URISyntaxException e )
		{
			throw new SpimDataIOException( e );
		}
	}

	/**
	 * A little hack to get a generic KeyValueAccess for a cloud store
	 * 
	 * @param uri - the full URI (even though only scheme and host/bucket will be used)
	 * @return the KeyValueStore
	 */
	public static KeyValueAccess getKeyValueAccess( final URI uri )
	{
		if ( URITools.isS3( uri ) || URITools.isGC( uri ) )
		{
			// Note: N5Factory.getKeyValueAccess() returns a (bucket-scoped) KeyValueAccess
			// without requiring an N5/Zarr container to exist at the location - unlike
			// openReader(), which validates container existence and would throw
			// "No container exists" when pointed at a bucket root / bare file. We only
			// need a handle to read the XML bytes here, not an actual container.
			// The boolean is 'createBucket' (false: never create, read-only access).
			final GsonBuilder builder = new GsonBuilder().registerTypeAdapter(
					CoordinateTransformation.class,
					new CoordinateTransformationAdapter() );

			try
			{
				//System.out.println( "Trying KeyValueAccess with credentials ..." );
				final N5Factory factory = new N5Factory();
				factory.gsonBuilder( builder );
				if ( s3Region != null )
					S3Tools.configureRegion( factory, s3Region );
				return factory.getKeyValueAccess( uri, false );
			}
			catch ( Exception e )
			{
				System.out.println( "With credentials failed (" + e + "); trying anonymous ..." );

				final String region = ( s3Region != null ) ? s3Region : detectS3Region( uri );

				final N5Factory factory = new N5Factory();
				factory.gsonBuilder( builder );
				S3Tools.configureAnonymous( factory, region );
				return factory.getKeyValueAccess( uri, false );
			}
		}
		else if ( URITools.isFile( uri ) )
		{
			return new FileSystemKeyValueAccess();
		}
		else
		{
			if ( uri.getScheme() != null )
				throw new RuntimeException( "Unsupported uri scheme: " + uri.getScheme() + " in '" + uri + "'." );
			else
				throw new RuntimeException( "Cannot get a KeyValueAccess for a relative path '" + uri + "'." );
		}
	}

	public static void saveSpimData( final SpimData2 data, final URI xmlURI, final XmlIoSpimData2 io ) throws SpimDataException
	{
		if ( URITools.isFile( xmlURI ) )
		{
			data.setBasePathURI( getParentURI( xmlURI ) );

			// old code for filesystem
			io.save( data, URITools.fromURI( xmlURI ) );
		}
		else if ( URITools.isS3( xmlURI ) || URITools.isGC( xmlURI ) )
		{
			//
			// saving the XML to s3
			//
			final KeyValueAccess kva;

			System.out.println( xmlURI );

			try
			{
				kva = URITools.getKeyValueAccess( xmlURI );
			}
			catch ( Exception e )
			{
				throw new SpimDataException( "Could not parse cloud link and setup KeyValueAccess for '" + xmlURI + "': " + e );
			}

			// fist make a copy of the XML and save it to not loose it
			try
			{
				final String xmlFile = toNormalPath( kva, xmlURI );

				if ( kva.exists( xmlFile ) )
				{
					int maxExistingBackup = 0;
					for ( int i = 1; i < XmlIoSpimData2.numBackups; ++i )
						if ( kva.exists( xmlFile + "~" + i ) )
							maxExistingBackup = i;
						else
							break;
	
					// copy the backups
					for ( int i = maxExistingBackup; i >= 1; --i )
						URITools.copy(kva, xmlFile + "~" + i, xmlFile + "~" + (i + 1) );

					URITools.copy(kva, xmlFile, xmlFile + "~1" );
				}
			}
			catch ( Exception e )
			{
				throw new SpimDataException( "Could not save backup of XML file for '" + xmlURI + "': " + e );
			}

			try
			{
				XmlIoSpimData2.saveInterestPointsInParallel( data );
			}
			catch ( Exception e )
			{
				throw new SpimDataException( "Could not interest points for '" + xmlURI + "' in paralell: " + e );
			}

			try
			{
				XmlIoSpimData2.savePSFsInParallel( data );
			}
			catch ( Exception e )
			{
				throw new SpimDataException( "Could not point spread function for '" + xmlURI + "' in paralell: " + e );
			}

			try
			{
				final Document doc = new Document( io.toXml( data, getParentURI( xmlURI ) ) );
				final XMLOutputter xout = new XMLOutputter( Format.getPrettyFormat() );
				final String xmlString = xout.outputString( doc );

				final PrintWriter pw = openFileWriteCloudWriter( kva, xmlURI );
				pw.println( xmlString );
				pw.close();
			}
			catch ( Exception e )
			{
				throw new SpimDataException( "Could not save xml '" + xmlURI + "': " + e );
			}

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Saved xml '" + xmlURI + "'." );
		}
		else
		{
			throw new RuntimeException( "Unsupported URI: " + xmlURI );
		}
	}

	public static N5Writer instantiateN5Writer( final StorageFormat format, final URI uri )
	{
		final GsonBuilder builder = new GsonBuilder()
				.registerTypeAdapter(
						CoordinateTransformation.class,
						new CoordinateTransformationAdapter() )
				.registerTypeAdapter(
						Axis.class,
						(JsonSerializer<Axis>) ( src, typeOfSrc, ctx ) -> {
							// Skip "unit" when null so OME-NGFF .zattrs doesn't contain
							// {"unit":null} (breaks Neuroglancer / BDV). n5-zarr's writer Gson
							// forces serializeNulls(); without this adapter Axis is reflected
							// and the channel axis's null unit leaks through.
							final JsonObject obj = new JsonObject();
							obj.addProperty( "type", src.getType() );
							obj.addProperty( "name", src.getName() );
							if ( src.getUnit() != null )
								obj.addProperty( "unit", src.getUnit() );
							return obj;
						} );

		if ( URITools.isFile( uri ) )
		{
			if ( format.equals( StorageFormat.N5 ))
				return new N5FSWriter( URITools.fromURI( uri ) );
			else if ( format.equals( StorageFormat.ZARR ))
			{
				// Create Zarr v3 writer
				return new ZarrV3KeyValueWriter(
						new FileSystemKeyValueAccess(),
						URITools.fromURI( uri ),
						builder,
						true   // cacheAttributes
				);
			}
			else if ( format.equals( StorageFormat.ZARR2 ))
				return new N5ZarrWriter( URITools.fromURI( uri ), builder, "/", true, true );
			else if ( format.equals( StorageFormat.HDF5 ))
				return new N5HDF5Writer( URITools.fromURI( uri ) );
			else
				throw new RuntimeException( "Format: " + format + " not supported." );
		}
		else
		{
			N5Writer n5w;

			try
			{
				//System.out.println( "Trying writing with credentials ..." );
				final N5Factory factory = new N5Factory().zarrDimensionSeparator( "/" );
				factory.gsonBuilder( builder );
				if ( s3Region != null )
					S3Tools.configureRegion( factory, s3Region );
				n5w = factory.openWriter( format, uri );
			}
			catch ( Exception e )
			{
				System.out.println( "With credentials failed (" + e + "); trying anonymous ..." );

				final String region = ( s3Region != null ) ? s3Region : detectS3Region( uri );

				final N5Factory factory = new N5Factory();
				factory.gsonBuilder( builder );
				S3Tools.configureAnonymous( factory, region );
				n5w = factory.openWriter( format, uri );
			}

			return n5w;
		}
	}

	public static N5Reader instantiateN5Reader( final StorageFormat format, final URI uri )
	{
		final GsonBuilder builder = new GsonBuilder().registerTypeAdapter(
				CoordinateTransformation.class,
				new CoordinateTransformationAdapter() );

		// TODO: maybe use
		//StorageFormat.guessStorageFromKeys(uri, null);

		if ( URITools.isFile( uri ) )
		{
			if ( format.equals( StorageFormat.N5 ))
				return new N5FSReader( URITools.fromURI( uri ) );
			else if ( format.equals( StorageFormat.ZARR ))
			{
				// Create Zarr v3 reader
				return new ZarrV3KeyValueReader(
						new FileSystemKeyValueAccess(),
						URITools.fromURI( uri ),
						builder,
						true // cacheAttributes
				);
			}
			else if ( format.equals( StorageFormat.ZARR2 ))
				return new N5ZarrReader( URITools.fromURI( uri ), builder );
			else if ( format.equals( StorageFormat.HDF5 ))
				return new N5HDF5Reader( URITools.fromURI( uri ) );
			else
				throw new RuntimeException( "Format: " + format + " not supported." );
		}
		else
		{
			N5Reader n5r;

			try
			{
				//System.out.println( "Trying reading with credentials ..." );
				final N5Factory factory = new N5Factory();
				factory.gsonBuilder( builder );
				if ( s3Region != null )
					S3Tools.configureRegion( factory, s3Region );
				n5r = factory.openReader( format, uri );
			}
			catch ( Exception e )
			{
				System.out.println( "With credentials failed (" + e + "); trying anonymous with gson builder ..." );

				final String region = ( s3Region != null ) ? s3Region : detectS3Region( uri );

				final N5Factory factory = new N5Factory();
				factory.gsonBuilder( builder );
				S3Tools.configureAnonymous( factory, region );
				n5r = factory.openReader( format, uri );
			}

			return n5r;
		}
	}

	/**
	 * Attempts to detect the AWS region of an S3 bucket without any credentials.
	 *
	 * Issues an unauthenticated HTTP HEAD request (path-style) against the global
	 * S3 endpoint and reads the {@code x-amz-bucket-region} response header, which
	 * S3 returns even for 301/403 responses. Path-style against {@code s3.amazonaws.com}
	 * is used so that bucket names containing dots are handled and TLS still validates.
	 *
	 * @param uri an {@code s3://} URI; the bucket is taken from the host component
	 * @return the region string (e.g. "us-west-2"), or null if it could not be determined
	 */
	public static String detectS3Region( final URI uri )
	{
		final String bucket = ( uri == null ) ? null : uri.getHost();

		if ( bucket == null || bucket.isEmpty() )
			return null;

		final String cached = s3RegionCache.get( bucket );
		if ( cached != null )
			return cached;

		HttpURLConnection conn = null;

		try
		{
			final URL url = new URL( "https://s3.amazonaws.com/" + bucket );
			conn = (HttpURLConnection)url.openConnection();
			conn.setRequestMethod( "HEAD" );
			conn.setInstanceFollowRedirects( false );
			conn.setConnectTimeout( 10000 );
			conn.setReadTimeout( 10000 );
			conn.getResponseCode(); // trigger the request

			final String region = conn.getHeaderField( "x-amz-bucket-region" );

			if ( region != null && !region.isEmpty() )
			{
				IOFunctions.println( "Auto-detected S3 region '" + region + "' for bucket '" + bucket + "'." );
				s3RegionCache.put( bucket, region );
				return region;
			}
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Could not auto-detect S3 region for bucket '" + bucket + "': " + e );
		}
		finally
		{
			if ( conn != null )
				conn.disconnect();
		}

		return null;
	}

	public static SpimData2 loadSpimData( final URI xmlURI, final XmlIoSpimData2 io ) throws SpimDataException
	{
		if ( URITools.isFile( xmlURI ) )
		{
			return io.load( URITools.fromURI( xmlURI ) ); // method from XmlIoAbstractSpimData
		}
		else if ( HTTPS_SCHEME.asPredicate().test( xmlURI.getScheme() ) )
		{
			// Plain HTTP/HTTPS web server - fetch XML directly via URL
			// Note: this also handles public S3/GC data served over https://
			final SAXBuilder sax = new SAXBuilder();
			Document doc;

			try
			{
				final InputStream is = xmlURI.toURL().openStream();
				doc = sax.build( is );
				is.close();
			}
			catch ( final Exception e )
			{
				throw new SpimDataIOException( e );
			}

			final Element docRoot = doc.getRootElement();

			if ( docRoot.getName() != SPIMDATA_TAG )
				throw new RuntimeException( "expected <" + SPIMDATA_TAG + "> root element. wrong file?" );

			final SpimData2 data = io.fromXml( docRoot, xmlURI );

			return data;
		}
		else if ( URITools.isS3( xmlURI ) || URITools.isGC( xmlURI ) )
		{
			final KeyValueAccess kva = getKeyValueAccess( xmlURI );
			final SAXBuilder sax = new SAXBuilder();
			Document doc;

			try
			{
				final InputStream is = openFileReadCloudStream(kva, xmlURI);
				doc = sax.build( is );
				is.close();
			}
			catch ( final Exception e )
			{
				throw new SpimDataIOException( e );
			}

			final Element docRoot = doc.getRootElement();

			if ( docRoot.getName() != SPIMDATA_TAG )
				throw new RuntimeException( "expected <" + SPIMDATA_TAG + "> root element. wrong file?" );

			final SpimData2 data = io.fromXml( docRoot, xmlURI );

			return data;
		}
		else
		{
			throw new RuntimeException( "Unsupported URI: " + xmlURI );
		}
	}

	public static boolean setNumFetcherThreads( final BasicImgLoader loader, final int threads )
	{
		if ( ViewerImgLoader.class.isInstance( loader ) )
		{
			( (ViewerImgLoader) loader ).setNumFetcherThreads( threads );
			return true;
		}
		else
		{
			return false;
		}
	}

	public static boolean prefetch( final BasicImgLoader loader, final int threads )
	{
		if ( N5ImageLoader.class.isInstance( loader ) )
		{
			( ( N5ImageLoader ) loader ).prefetch( threads );
			return true;
		}
		else if ( SplitViewerImgLoader.class.isInstance( loader ) )
		{
			if ( ( ( SplitViewerImgLoader ) loader ).prefetch( threads ) != null )
				return true;
			else
				return false;
		}
		else
			return false;
	}

	public static BufferedReader openFileReadCloudReader( final KeyValueAccess kva, final URI uri ) throws IOException
	{
		return new BufferedReader(new InputStreamReader( openFileReadCloudStream( kva, uri )));
	}

	public static InputStream openFileReadCloudStream( final KeyValueAccess kva, final URI uri ) throws IOException
	{
		return kva.lockForReading( toNormalPath( kva, uri ) ).newInputStream();
	}

	/**
	 * Get the "normalPath" of the given {@code URI} for the given {@code KeyValueAccess}.
	 * <p>
	 * The {@code URI} can be absolute or relative.
	 * Relative {@code URI} are interpreted as relative to the root of the {@code KeyValueAccess}
	 * (e.g. {@code 'file:/'} for {@code FileSystemKeyValueAccess},
	 * {@code 's3://bucket-name/'} for {@code AmazonS3KeyValueAccess}, etc).
	 *
	 * @param kva a KeyValueAccess
	 * @param uri absolute or relative URI
	 * @return normalPath of {@code uri} for the given {@code KeyValueAccess}
	 * @throws IOException
	 */
	static String toNormalPath( final KeyValueAccess kva, final URI uri ) throws IOException
	{
		try
		{
			final URI root = kva.uri( "/" );
			final URI relativeURI = uri.isAbsolute()
					? new URI( "/" ).resolve( root.relativize( uri ) )
					: uri;
			return kva.compose( root, relativeURI.getPath() );
		}
		catch ( URISyntaxException e )
		{
			throw new IOException( e );
		}
	}

	public static PrintWriter openFileWriteCloudWriter( final KeyValueAccess kva, final URI uri ) throws IOException
	{
		return new PrintWriter( openFileWriteCloudStream( kva, uri ) );
	}

	public static OutputStream openFileWriteCloudStream( final KeyValueAccess kva, final URI uri ) throws IOException
	{
		final LockedChannel channel = kva.lockForWriting( toNormalPath( kva, uri ) );
		final OutputStream inner = channel.newOutputStream();
		// In n5 alpha-10+, lockForWriting returns a BufferedKvaLockedChannel whose
		// newOutputStream() returns an in-memory ByteArrayOutputStream. The actual
		// disk write only happens in channel.close(). Wrap the stream so that
		// closing it also closes the channel (flushing to disk).
		return new FilterOutputStream(inner) {
			@Override
			public void close() throws IOException {
				try {
					super.close();
				} finally {
					channel.close();
				}
			}
		};
	}

	/*
	 * Note: it is up to you to create the correct relative paths using toNormalPath()
	 *
	 * @param kva
	 * @param relativeSrc
	 * @param relativeDst
	 * @throws IOException
	 */
	public static void copy( final KeyValueAccess kva, final String relativeSrc, final String relativeDst ) throws IOException
	{
		final InputStream is = kva.lockForReading( relativeSrc ).newInputStream();
		final OutputStream os = kva.lockForWriting( relativeDst ).newOutputStream();

		final byte[] buffer = new byte[32768];
		int len;
		while ((len = is.read(buffer)) != -1)
			os.write(buffer, 0, len);

		is.close();
		os.close();
	}

	// TODO: does not work for Windows
	public static boolean isKnownScheme( URI uri )
	{
		return isFile( uri ) || isS3( uri ) || isGC( uri )
				|| ( uri.getScheme() != null && HTTPS_SCHEME.asPredicate().test( uri.getScheme() ) );
	}

	public static boolean isGC( URI uri )
	{
		final String scheme = uri.getScheme();
		final boolean hasScheme = scheme != null;
		if ( !hasScheme )
			return false;
		if ( GoogleCloudUtils.GS_SCHEME.asPredicate().test( scheme ) )
			return true;
		return uri.getHost() != null && HTTPS_SCHEME.asPredicate().test( scheme ) && GoogleCloudUtils.GS_HOST.asPredicate().test( uri.getHost() );
	}

	public static boolean isS3( URI uri )
	{
		final String scheme = uri.getScheme();
		final boolean hasScheme = scheme != null;
		if ( !hasScheme )
			return false;
		if ( "s3".equalsIgnoreCase( scheme ) )
			return true;
		return uri.getHost() != null && HTTPS_SCHEME.asPredicate().test( scheme );
	}

	public static boolean isFile( URI uri )
	{
		final String scheme = uri.getScheme();
		final boolean hasScheme = scheme != null;

		if ( !hasScheme )
			return false;
		else 
			return FILE_SCHEME.asPredicate().test( scheme );
	}

	/**
	 * @param uriString - if relative we assume it's a local path and file:/ scheme will be added
	 * @return the URI of the String
	 */
	public static URI toURI( final String uriString )
	{
		URI uri;

		try
		{
			uri = new URI( uriString );
		}
		catch (URISyntaxException e)
		{
			// e.g. a space was in there, which is allowed for filepaths, but not other resources (must be %20)
			uri = null;
		}

		try
		{
			// maybe it works if we assume it is a file
			if ( uri == null )
				uri = new File( uriString ).toURI();

			if ( !uri.isAbsolute() )
				uri = new URI( "file", null, uriString, null );

			return uri;
		}
		catch (URISyntaxException e)
		{
			e.printStackTrace();
			throw new RuntimeException( "URI couldn't be created from '" + uriString + "'. stopping: " + e );
		}
	}

	/**
	 * 
	 * @param uri a URI
	 * @return a String representation of a URI, if it starts with file:/ it will be removed
	 */
	public static String fromURI( final URI uri )
	{
		final String scheme = uri.getScheme();

		if ( scheme == null )
			throw new RuntimeException( "URI '" + uri + "' has no scheme. stopping." );

		if ( FILE_SCHEME.asPredicate().test( uri.getScheme() ) )
		{
			try
			{
				return new File( uri ).toString();
			}
			catch (Exception e)
			{
				e.printStackTrace();
				throw new RuntimeException( "Error converting file-URI '" + uri + "' to a path. stopping." );
			}
		}
		else
		{
			return uri.toString();
		}
	}

	public static String getFileName( final URI uri )
	{
		int l1 = uri.toString().length();
		int l2 = l1;
		try
		{
			l2 = getParentURI( uri ).toString().length();
		}
		catch (SpimDataIOException e)
		{
			IOFunctions.println( "Error getting the parent URI for '" + uri + "' in order to extract the filename. Returning entire URI as filename, even though this is most likely wrong: " + e );
			e.printStackTrace();
		}

		return uri.toString().substring( l2, l1 );
	}

	public static String appendName( final URI uri, final String name )
	{
		return uri.toString() + ( uri.toString().endsWith( "/" ) ? "" : "/") + name;
	}

	public static URI xmlFilenameToFullPath( final AbstractSpimData<?> data, final String xmlFileName )
	{
		return toURI( appendName( data.getBasePathURI(), xmlFileName ) );
	}

	public static void copyFile( final File inputFile, final File outputFile ) throws IOException
	{
		InputStream input = null;
		OutputStream output = null;
		
		try
		{
			input = new FileInputStream( inputFile );
			output = new FileOutputStream( outputFile );

			final byte[] buf = new byte[ 65536 ];
			int bytesRead;
			while ( ( bytesRead = input.read( buf ) ) > 0 )
				output.write( buf, 0, bytesRead );

		}
		finally
		{
			if ( input != null )
				input.close();
			if ( output != null )
				output.close();
		}
	}

	public static void main( String[] args ) throws SpimDataException, IOException, URISyntaxException
	{
		final KeyValueAccess kva1 = URITools.getKeyValueAccess( URITools.toURI( "s3://janelia-bigstitcher-spark/Stitching/dataset.xml" ) );

		URI uri1 = URITools.toURI( "s3://aind-open-data/exaSPIM_708373_2024-04-02_19-49-38/SPIM.ome.zarr/" );

		System.out.println( uri1.getHost() );
		System.out.println( uri1.getPath() );
		System.out.println( getFileName( uri1 ) );

		System.exit( 0 );

		URI gcURI = URITools.toURI( "gs://janelia-spark-test/I2K-test/dataset.xml" );
		System.out.println( "isGC: " + isGC(gcURI) + " [" + gcURI + "]" );
		SpimData2 sdGC = loadSpimData(gcURI, new XmlIoSpimData2() );
		System.out.println( sdGC.getBasePathURI() + ", " + sdGC.getSequenceDescription().getAllTilesOrdered() );

		URI s3URI = URITools.toURI( "s3://janelia-bigstitcher-spark/Stitching/dataset.xml" );
		System.out.println( "isS3: " + isS3(s3URI) + " [" + s3URI + "]" );
		SpimData2 sdS3 = loadSpimData(s3URI, new XmlIoSpimData2() );
		System.out.println( sdS3.getBasePathURI() + ", " + sdS3.getSequenceDescription().getAllTilesOrdered() );

		System.exit( 0 );

		// Fails:
		//URI uri = new URI( "/Users/preibischs/SparkTest/IP raw/spim_TL18_Angle0.tif" );
		URI uri = new File( "/Users/preibischs/SparkTest/IP raw/spim_TL18_Angle0.tif" ).toURI();

		System.out.println( new File( uri ) );
		System.out.println( URITools.fromURI( uri ) );

		System.out.println( uri );

		String file = "/home/preibisch/test.xml";
		String s3 = "s3://preibisch/test.xml";

		System.out.println( toURI( file ) );
		System.out.println( toURI( s3 ) );

		System.out.println();

		System.out.println( fromURI( toURI( file ) ) );
		System.out.println( fromURI( toURI( s3 ) ) );

		System.out.println();

		KeyValueAccess kva = getKeyValueAccess( URITools.toURI( "s3://janelia-bigstitcher-spark/Stitching/dataset.xml" ) );

		try
		{
			BufferedReader reader = openFileReadCloudReader(kva, URITools.toURI( "s3://janelia-bigstitcher-spark/Stitching/dataset.xml" ) );
			reader.lines().forEach( s -> System.out.println( s ) );
			reader.close();

			/*
			String path = pb.protocol + pb.bucket + "/" + pb.rootDir + "/" + "test_" + System.currentTimeMillis() + ".txt";

			final PrintWriter pw = openFileWriteCloud( kva, path );
			pw.println( "hallo " + System.currentTimeMillis() );
			pw.close();
			*/
		}
		catch ( Exception e )
		{
			throw new SpimDataException( "Could not save xml '" + "': " + e );
		}	
	}
}
