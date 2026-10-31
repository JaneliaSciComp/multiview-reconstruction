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
package net.preibisch.mvrecon.process.interestpointembedding;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.preibisch.legacy.io.IOFunctions;

/**
 * The embedding models on the Hugging Face Hub and the local cache of them. A model is a repository of one of the
 * {@link #defaultNamespaces} tagged {@value #TAG} (the model card's {@code tags}) that holds a {@code .onnx} file with its
 * {@code .onnx.json} sidecar next to it. The hub's API lists them ({@code /api/models?author=..&filter=..}), gives the commit
 * a repository is at and its files with their sizes and, for files in LFS, their sha256 ({@code ?blobs=true}), and serves a
 * file at a commit by plain HTTPS ({@code /<repo>/resolve/<revision>/<file>}, one redirect); nothing but the JDK's HTTP client
 * is needed. A model is pinned to the commit it was listed at and downloaded once into the cache,
 * {@code <cache>/<namespace>/<name>/<revision>/<file>} with the sidecar and a {@value #RECORD}, verified by size and sha256;
 * there {@link EmbeddingModel#load} finds the sidecar next to it as for any local file. The action history and
 * BigStitcher-Spark's {@code --model} carry a hub model as {@code hf://<namespace>/<name>@<revision>/<file>}
 * ({@link HubModel#uri}), so a recorded run names the exact model; {@link #localModel} turns such a URI (or a path) into the
 * local file, without the hub when the pinned model is in the cache. The cache is {@code ~/.bigstitcher/models}, or the
 * system property {@value #CACHE_PROPERTY} (a shared directory on a cluster; a node without internet needs the file downloaded
 * once and passed as a path). Decided with Stephan 2026-10-30/31: the hub under the JaneliaSciComp organisation (the first
 * repository was under his account and moved there on 2026-10-31; the hub redirects the old name), one repository per model whose
 * name is the model's name, the revision pinned at download time.
 *
 * @author Stephan Preibisch
 */
public class ModelHub
{
	/** the hub (a test points it at a local server) */
	public static String hub = "https://huggingface.co";

	/** the users or organisations whose repositories are listed */
	public static String[] defaultNamespaces = new String[] { "StephanPreibisch", "JaneliaSciComp" };

	/** the tag a repository must carry to be listed */
	public static final String TAG = "bigstitcher";

	/** the scheme of a hub model: {@code hf://<namespace>/<name>[@<revision>][/<file>]} */
	public static final String SCHEME = "hf://";

	/** the system property that moves the cache (default {@code ~/.bigstitcher/models}) */
	public static final String CACHE_PROPERTY = "bigstitcher.models";

	/** the record of a downloaded model in its cache directory */
	public static final String RECORD = "hub.json";

	public static Duration defaultTimeout = Duration.ofSeconds( 30 );

	/** @return the cache: the system property {@value #CACHE_PROPERTY}, else {@code ~/.bigstitcher/models} */
	public static Path defaultCacheDir()
	{
		final String property = System.getProperty( CACHE_PROPERTY );
		return property != null && !property.trim().isEmpty() ? Paths.get( property.trim() ) : Paths.get( System.getProperty( "user.home" ), ".bigstitcher", "models" );
	}

	/**
	 * A model file in a repository at a commit.
	 *
	 * @param repo {@code <namespace>/<name>}
	 * @param revision the commit (40 hex characters)
	 * @param file the {@code .onnx} file (its sidecar is {@code file + ".json"})
	 * @param size bytes of the file, -1 if unknown
	 * @param sha256 sha256 of the file (hex), null if the hub does not store it (a file not in LFS)
	 * @param sidecarSize bytes of the sidecar, -1 if unknown
	 */
	public record HubModel( String repo, String revision, String file, long size, String sha256, long sidecarSize )
	{
		public String namespace() { return repo.substring( 0, repo.indexOf( '/' ) ); }

		/** @return the repository's name without the namespace: the model's name */
		public String name() { return repo.substring( repo.indexOf( '/' ) + 1 ); }

		public String shortRevision() { return revision.length() > 7 ? revision.substring( 0, 7 ) : revision; }

		/** @return {@code hf://<repo>@<revision>/<file>}, what the action history and BigStitcher-Spark's {@code --model} carry */
		public String uri() { return SCHEME + repo + "@" + revision + "/" + file; }

		/** @return where the model lives in a cache: {@code <cache>/<namespace>/<name>/<revision>/<file>} */
		public Path cachePath( final Path cacheDir ) { return cacheDir.resolve( namespace() ).resolve( name() ).resolve( revision ).resolve( file ); }
	}

	/** A parsed {@code hf://} URI: revision and file are null when not given (then "main" and the repository's only {@code .onnx}). */
	public record Spec( String repo, String revision, String file ) {}

	/** A model as a local file, and the spec to record for it: the pinned {@code hf://} URI, or the absolute path. */
	public record Local( Path path, String spec ) {}

	public static boolean isUri( final String spec ) { return spec != null && spec.startsWith( SCHEME ); }

	/**
	 * @param uri {@code hf://<namespace>/<name>[@<revision>][/<file>]}
	 * @return the parts
	 * @throws IllegalArgumentException if it is not of that form
	 */
	public static Spec parse( final String uri )
	{
		final String form = SCHEME + "<namespace>/<name>[@<revision>][/<file>]";
		if ( !isUri( uri ) )
			throw new IllegalArgumentException( "'" + uri + "' is no hub model, expected " + form );

		final String s = uri.substring( SCHEME.length() );
		final int slash1 = s.indexOf( '/' );
		if ( slash1 <= 0 )
			throw new IllegalArgumentException( "'" + uri + "' names no repository, expected " + form );

		int repoEnd = s.length();
		final int at = s.indexOf( '@', slash1 ), slash2 = s.indexOf( '/', slash1 + 1 );
		if ( at >= 0 )
			repoEnd = Math.min( repoEnd, at );
		if ( slash2 >= 0 )
			repoEnd = Math.min( repoEnd, slash2 );
		final String repo = s.substring( 0, repoEnd );
		if ( repoEnd == slash1 + 1 )
			throw new IllegalArgumentException( "'" + uri + "' names no repository, expected " + form );

		String rest = s.substring( repoEnd ), revision = null, file = null;
		if ( rest.startsWith( "@" ) )
		{
			final int slash = rest.indexOf( '/' );
			revision = slash < 0 ? rest.substring( 1 ) : rest.substring( 1, slash );
			rest = slash < 0 ? "" : rest.substring( slash );
			if ( revision.isEmpty() )
				throw new IllegalArgumentException( "'" + uri + "' has an empty revision, expected " + form );
		}
		if ( rest.startsWith( "/" ) )
		{
			file = rest.substring( 1 );
			if ( file.isEmpty() )
				throw new IllegalArgumentException( "'" + uri + "' has an empty file, expected " + form );
		}
		return new Spec( repo, revision, file );
	}

	/**
	 * Lists the models of the namespaces: every {@code .onnx} with a sidecar in a public repository tagged {@value #TAG}, at the
	 * repository's current commit (two requests per namespace plus one per repository).
	 *
	 * @param namespaces the users or organisations
	 * @param timeout per request
	 * @return the models
	 * @throws IOException if the hub cannot be reached or answers unexpectedly
	 */
	public static List< HubModel > list( final String[] namespaces, final Duration timeout ) throws IOException
	{
		final List< HubModel > models = new ArrayList<>();
		for ( final String namespace : namespaces )
		{
			final JsonElement listing = getJson( hub + "/api/models?author=" + encode( namespace ) + "&filter=" + encode( TAG ), timeout );
			if ( !listing.isJsonArray() )
				throw new IOException( "Unexpected answer of " + hub + " for the models of '" + namespace + "': " + abbreviate( listing ) );
			for ( final JsonElement e : listing.getAsJsonArray() )
			{
				final String id = e.getAsJsonObject().get( "id" ).getAsString();
				if ( !id.startsWith( namespace + "/" ) )
					continue;
				final JsonObject repo = getJson( hub + "/api/models/" + id + "?blobs=true", timeout ).getAsJsonObject();
				if ( !hasTag( repo ) )
					continue;
				models.addAll( modelsOf( repo, id ) );
			}
		}
		return models;
	}

	/**
	 * The model a URI names, pinned: the repository's commit at the revision ("main" if none), the file (the repository's
	 * only {@code .onnx} if none). A pinned URI whose model is in the cache is answered from the cache's record, without the hub.
	 * A repository that was moved or renamed on the hub is answered through the hub's redirect, and the model carries the new name
	 * (so it is cached and recorded under one name only).
	 *
	 * @param uri {@code hf://<namespace>/<name>[@<revision>][/<file>]}
	 * @param cacheDir the cache (null = no cache lookup)
	 * @param timeout per request
	 * @return the model
	 * @throws IOException if the repository, revision or file does not exist, or the hub cannot be reached
	 */
	public static HubModel resolve( final String uri, final Path cacheDir, final Duration timeout ) throws IOException
	{
		final Spec spec = parse( uri );

		if ( cacheDir != null && spec.revision() != null && spec.file() != null )
		{
			final HubModel cached = readRecord( cacheDir.resolve( spec.repo() ).resolve( spec.revision() ).resolve( RECORD ) );
			if ( cached != null && cached.file().equals( spec.file() ) )
				return cached;
		}

		final String revision = spec.revision() == null ? "main" : spec.revision();
		final JsonObject repo = getJson( hub + "/api/models/" + spec.repo() + "/revision/" + encode( revision ) + "?blobs=true", timeout ).getAsJsonObject();
		// the hub's name of the repository: a moved or renamed one is answered through a redirect, its models carry the new name
		final String id = repo.has( "id" ) && repo.get( "id" ).isJsonPrimitive() ? repo.get( "id" ).getAsString() : spec.repo();
		if ( !id.equals( spec.repo() ) )
			IOFunctions.println( "The repository '" + spec.repo() + "' is '" + id + "' on " + hub + " now; the model is named after it." );
		final List< HubModel > models = modelsOf( repo, id );

		if ( spec.file() == null )
		{
			if ( models.size() == 1 )
				return models.get( 0 );
			final StringBuilder names = new StringBuilder();
			for ( final HubModel m : models )
				names.append( names.length() == 0 ? "" : ", " ).append( m.file() );
			throw new IOException( "The repository '" + spec.repo() + "' at " + revision + " has " + models.size() + " models with a sidecar" + ( models.isEmpty() ? "" : " (" + names + "); name one: " + SCHEME + spec.repo() + "/<file>" ) + "." );
		}
		for ( final HubModel m : models )
			if ( m.file().equals( spec.file() ) )
				return m;
		throw new IOException( "The repository '" + spec.repo() + "' at " + revision + " has no '" + spec.file() + "' with its sidecar '" + spec.file() + EmbeddingModel.SIDECAR_SUFFIX + "'." );
	}

	/**
	 * Downloads a model and its sidecar into the cache unless they are there and verify (size, sha256 when the hub knows it),
	 * then checks that the sidecar is a contract. Safe for several processes on one cache: a file is written next to its
	 * place and moved in atomically.
	 *
	 * @param model the model
	 * @param cacheDir the cache
	 * @param timeout per request
	 * @return the local {@code .onnx}
	 * @throws IOException if the download fails or the files do not verify
	 */
	public static Path download( final HubModel model, final Path cacheDir, final Duration timeout ) throws IOException
	{
		final Path onnx = model.cachePath( cacheDir ), sidecar = EmbeddingModel.sidecar( onnx ), record = onnx.getParent().resolve( RECORD );

		if ( Files.isRegularFile( onnx ) && Files.isRegularFile( sidecar ) && Files.isRegularFile( record ) && verifies( onnx, model.size(), model.sha256() ) && verifies( sidecar, model.sidecarSize(), null ) )
			return onnx;

		IOFunctions.println( "Downloading " + model.uri() + ( model.size() >= 0 ? " (" + model.size() + " bytes)" : "" ) + " from " + hub + " into " + onnx.getParent() );
		Files.createDirectories( onnx.getParent() );
		fetch( hub + "/" + model.repo() + "/resolve/" + model.revision() + "/" + model.file(), onnx, model.size(), model.sha256(), timeout );
		fetch( hub + "/" + model.repo() + "/resolve/" + model.revision() + "/" + model.file() + EmbeddingModel.SIDECAR_SUFFIX, sidecar, model.sidecarSize(), null, timeout );
		EmbeddingModel.readContract( onnx ); // the sidecar is a contract

		final JsonObject json = new JsonObject();
		json.addProperty( "repo", model.repo() );
		json.addProperty( "revision", model.revision() );
		json.addProperty( "file", model.file() );
		json.addProperty( "size", model.size() );
		if ( model.sha256() != null )
			json.addProperty( "sha256", model.sha256() );
		json.addProperty( "sidecarSize", model.sidecarSize() );
		json.addProperty( "hub", hub );
		json.addProperty( "downloaded", LocalDateTime.now().format( DateTimeFormatter.ofPattern( "yyyy-MM-dd'T'HH:mm:ss" ) ) );
		final Path tmp = record.resolveSibling( RECORD + ".part-" + UUID.randomUUID() );
		Files.writeString( tmp, json.toString() );
		Files.move( tmp, record, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );

		return onnx;
	}

	/**
	 * @param spec a path, or {@code hf://<namespace>/<name>[@<revision>][/<file>]}
	 * @param cacheDir the cache for hub models
	 * @param timeout per request
	 * @return the local file and the spec to record: the pinned URI for a hub model, the absolute path for a file
	 * @throws IOException if the file does not exist or the hub model cannot be resolved or downloaded
	 */
	public static Local localModel( final String spec, final Path cacheDir, final Duration timeout ) throws IOException
	{
		if ( isUri( spec ) )
		{
			final HubModel model = resolve( spec, cacheDir, timeout );
			return new Local( download( model, cacheDir, timeout ), model.uri() );
		}

		final Path path = Paths.get( spec );
		if ( !Files.isRegularFile( path ) )
			throw new IOException( "The model '" + spec + "' is no file (and no " + SCHEME + " URI)." );
		return new Local( path, path.toAbsolutePath().toString() );
	}

	/**
	 * @param cacheDir the cache
	 * @return the models in the cache (their records), in no particular order; a record that cannot be read is reported and skipped
	 * @throws IOException if the cache cannot be listed
	 */
	public static List< HubModel > cached( final Path cacheDir ) throws IOException
	{
		final List< HubModel > models = new ArrayList<>();
		if ( !Files.isDirectory( cacheDir ) )
			return models;
		try ( final Stream< Path > records = Files.find( cacheDir, 4, ( p, a ) -> a.isRegularFile() && p.getFileName().toString().equals( RECORD ) ) )
		{
			for ( final Path record : (Iterable< Path >) records::iterator )
			{
				final HubModel m = readRecord( record );
				if ( m != null && Files.isRegularFile( m.cachePath( cacheDir ) ) && Files.isRegularFile( EmbeddingModel.sidecar( m.cachePath( cacheDir ) ) ) )
					models.add( m );
			}
		}
		return models;
	}

	/** @return the record's model, null if there is none; a damaged record is reported */
	static HubModel readRecord( final Path record )
	{
		if ( !Files.isRegularFile( record ) )
			return null;
		try
		{
			final JsonObject json = JsonParser.parseString( Files.readString( record ) ).getAsJsonObject();
			return new HubModel( json.get( "repo" ).getAsString(), json.get( "revision" ).getAsString(), json.get( "file" ).getAsString(),
					json.has( "size" ) ? json.get( "size" ).getAsLong() : -1, json.has( "sha256" ) ? json.get( "sha256" ).getAsString() : null, json.has( "sidecarSize" ) ? json.get( "sidecarSize" ).getAsLong() : -1 );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "The cache record " + record + " cannot be read (" + e.getMessage() + "); the model is downloaded again when chosen." );
			return null;
		}
	}

	/** @return the models of a repository's API answer: every .onnx with a sidecar, at the answer's commit; none for a gated repository */
	static List< HubModel > modelsOf( final JsonObject repo, final String id ) throws IOException
	{
		final List< HubModel > models = new ArrayList<>();
		if ( repo.has( "gated" ) && !( repo.get( "gated" ).isJsonPrimitive() && repo.get( "gated" ).getAsJsonPrimitive().isBoolean() && !repo.get( "gated" ).getAsBoolean() ) )
		{
			IOFunctions.println( "The repository '" + id + "' is gated (needs a token) and is skipped." );
			return models;
		}
		if ( !repo.has( "sha" ) || !repo.has( "siblings" ) )
			throw new IOException( "Unexpected answer of " + hub + " for the repository '" + id + "': " + abbreviate( repo ) );

		final String sha = repo.get( "sha" ).getAsString();
		final JsonArray siblings = repo.getAsJsonArray( "siblings" );
		for ( final JsonElement e : siblings )
		{
			final JsonObject sibling = e.getAsJsonObject();
			final String file = sibling.get( "rfilename" ).getAsString();
			if ( !file.endsWith( ".onnx" ) )
				continue;
			final JsonObject sidecar = sibling( siblings, file + EmbeddingModel.SIDECAR_SUFFIX );
			if ( sidecar == null )
				continue;
			final String sha256 = sibling.has( "lfs" ) && sibling.get( "lfs" ).isJsonObject() && sibling.getAsJsonObject( "lfs" ).has( "sha256" ) ? sibling.getAsJsonObject( "lfs" ).get( "sha256" ).getAsString() : null;
			models.add( new HubModel( id, sha, file, size( sibling ), sha256, size( sidecar ) ) );
		}
		return models;
	}

	static boolean hasTag( final JsonObject repo )
	{
		if ( !repo.has( "tags" ) || !repo.get( "tags" ).isJsonArray() )
			return false;
		for ( final JsonElement t : repo.getAsJsonArray( "tags" ) )
			if ( t.isJsonPrimitive() && TAG.equals( t.getAsString() ) )
				return true;
		return false;
	}

	static JsonObject sibling( final JsonArray siblings, final String file )
	{
		for ( final JsonElement e : siblings )
			if ( file.equals( e.getAsJsonObject().get( "rfilename" ).getAsString() ) )
				return e.getAsJsonObject();
		return null;
	}

	static long size( final JsonObject sibling ) { return sibling.has( "size" ) && sibling.get( "size" ).isJsonPrimitive() ? sibling.get( "size" ).getAsLong() : -1; }

	/** downloads a URL to a file: into a part file next to it, verified, then moved in atomically */
	static void fetch( final String url, final Path target, final long size, final String sha256, final Duration timeout ) throws IOException
	{
		final Path tmp = target.resolveSibling( target.getFileName() + ".part-" + UUID.randomUUID() );
		try
		{
			final HttpResponse< Path > response = send( HttpRequest.newBuilder( URI.create( url ) ).timeout( timeout ).header( "User-Agent", USER_AGENT ).GET().build(), HttpResponse.BodyHandlers.ofFile( tmp ) );
			if ( response.statusCode() != 200 )
				throw new IOException( "GET " + url + ": HTTP " + response.statusCode() + ( response.statusCode() == 404 ? " (no such repository, revision or file)" : "" ) );
			if ( size >= 0 && Files.size( tmp ) != size )
				throw new IOException( "GET " + url + ": " + Files.size( tmp ) + " bytes, the hub lists " + size + "." );
			if ( sha256 != null && !sha256.equalsIgnoreCase( sha256( tmp ) ) )
				throw new IOException( "GET " + url + ": sha256 " + sha256( tmp ) + ", the hub lists " + sha256 + "." );
			Files.move( tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
		}
		finally
		{
			Files.deleteIfExists( tmp );
		}
	}

	static boolean verifies( final Path file, final long size, final String sha256 )
	{
		try
		{
			return ( size < 0 || Files.size( file ) == size ) && ( sha256 == null || sha256.equalsIgnoreCase( sha256( file ) ) );
		}
		catch ( final IOException e )
		{
			return false;
		}
	}

	/** @return the sha256 of a file, lower-case hex */
	public static String sha256( final Path file ) throws IOException
	{
		try
		{
			return HexFormat.of().formatHex( MessageDigest.getInstance( "SHA-256" ).digest( Files.readAllBytes( file ) ) );
		}
		catch ( final NoSuchAlgorithmException e )
		{
			throw new IOException( e );
		}
	}

	static final String USER_AGENT = "BigStitcher (multiview-reconstruction)";

	private static HttpClient client = null;

	private static synchronized HttpClient client()
	{
		if ( client == null )
			client = HttpClient.newBuilder().followRedirects( HttpClient.Redirect.NORMAL ).connectTimeout( defaultTimeout ).build();
		return client;
	}

	private static < T > HttpResponse< T > send( final HttpRequest request, final HttpResponse.BodyHandler< T > handler ) throws IOException
	{
		try
		{
			return client().send( request, handler );
		}
		catch ( final InterruptedException e )
		{
			Thread.currentThread().interrupt();
			throw new IOException( "interrupted while talking to " + request.uri(), e );
		}
		catch ( final IOException e )
		{
			// the JDK's client reports a refused connection without a message: name the host and the kind of failure
			throw new IOException( "Cannot reach " + request.uri().getHost() + " (" + request.uri() + "): " + e.getClass().getSimpleName() + ( e.getMessage() == null ? "" : ", " + e.getMessage() ), e );
		}
	}

	static JsonElement getJson( final String url, final Duration timeout ) throws IOException
	{
		final HttpResponse< String > response = send( HttpRequest.newBuilder( URI.create( url ) ).timeout( timeout ).header( "Accept", "application/json" ).header( "User-Agent", USER_AGENT ).GET().build(), HttpResponse.BodyHandlers.ofString( StandardCharsets.UTF_8 ) );
		if ( response.statusCode() != 200 )
			throw new IOException( "GET " + url + ": HTTP " + response.statusCode() + ( response.statusCode() == 404 ? " (no such repository or revision)" : "" ) );
		try
		{
			return JsonParser.parseString( response.body() );
		}
		catch ( final RuntimeException e )
		{
			throw new IOException( "GET " + url + ": no JSON (" + e.getMessage() + ")" );
		}
	}

	static String encode( final String s ) { return URLEncoder.encode( s, StandardCharsets.UTF_8 ); }

	static String abbreviate( final JsonElement e )
	{
		final String s = e.toString();
		return s.length() > 200 ? s.substring( 0, 200 ) + "..." : s;
	}
}
