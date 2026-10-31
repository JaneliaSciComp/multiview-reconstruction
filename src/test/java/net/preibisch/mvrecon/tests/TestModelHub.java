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
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.plugin.Embed_Interest_Points;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionRecord;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Vectors;
import net.preibisch.mvrecon.process.interestpointembedding.EmbeddingModel;
import net.preibisch.mvrecon.process.interestpointembedding.ModelHub;
import net.preibisch.mvrecon.process.interestpointembedding.ModelHub.HubModel;
import net.preibisch.mvrecon.process.interestpointembedding.ModelHub.Spec;

/**
 * The models on the hub and the cache: {@code hf://} URIs, the listing (tagged repositories with a sidecar only), the pinned
 * resolve, the verified download into the cache (a wrong hash is refused, a cached model costs no request, a pinned URI is
 * answered from the cache), the plugin with a hub model, all against a {@link FakeHub}; and, when the real hub is reachable,
 * Stephan's repository.
 */
public class TestModelHub
{
	@TempDir
	Path tempDir;

	String hub;
	String[] namespaces;

	@AfterEach
	public void restore()
	{
		if ( hub != null )
			ModelHub.hub = hub;
		if ( namespaces != null )
			ModelHub.defaultNamespaces = namespaces;
		System.clearProperty( ModelHub.CACHE_PROPERTY );
	}

	@Test
	public void parsesUris()
	{
		assertTrue( ModelHub.isUri( "hf://a/b" ) );
		assertTrue( !ModelHub.isUri( "/tmp/x.onnx" ) && !ModelHub.isUri( null ) );

		assertEquals( new Spec( "a/b", null, null ), ModelHub.parse( "hf://a/b" ) );
		assertEquals( new Spec( "a/b", "main", null ), ModelHub.parse( "hf://a/b@main" ) );
		assertEquals( new Spec( "a/b", "d90228e", "m.onnx" ), ModelHub.parse( "hf://a/b@d90228e/m.onnx" ) );
		assertEquals( new Spec( "a/b", null, "sub/m.onnx" ), ModelHub.parse( "hf://a/b/sub/m.onnx" ) );
		for ( final String bad : new String[] { "hf://", "hf://a", "hf:///b", "hf://a/", "hf://a/b@", "hf://a/b@rev/", "a/b" } )
			assertThrows( IllegalArgumentException.class, () -> ModelHub.parse( bad ), bad );

		final HubModel m = new HubModel( "StephanPreibisch/stack64", "d90228e27803fe0675751f7adb7004d6d8ff0b97", "m.onnx", 10, null, 5 );
		assertEquals( "StephanPreibisch", m.namespace() );
		assertEquals( "stack64", m.name() );
		assertEquals( "d90228e", m.shortRevision() );
		assertEquals( "hf://StephanPreibisch/stack64@d90228e27803fe0675751f7adb7004d6d8ff0b97/m.onnx", m.uri() );
		assertEquals( new Spec( m.repo(), m.revision(), m.file() ), ModelHub.parse( m.uri() ) );
		assertEquals( tempDir.resolve( "StephanPreibisch" ).resolve( "stack64" ).resolve( m.revision() ).resolve( "m.onnx" ), m.cachePath( tempDir ) );
	}

	@Test
	public void listsDownloadsAndCaches() throws Exception
	{
		try ( final FakeHub fake = new FakeHub() )
		{
			hub = ModelHub.hub;
			ModelHub.hub = fake.url();
			final Path cache = tempDir.resolve( "cache" );
			final Duration timeout = Duration.ofSeconds( 10 );

			// a tagged repository with a model, one without the tag, one without a sidecar, one of another namespace
			final TestOnnxModels.Tiny tiny = fake.addModel( "lab/stack-tiny", "0123456789abcdef0123456789abcdef01234567", "tiny.onnx", true, tempDir, 11 );
			fake.addModel( "lab/not-tagged", "1123456789abcdef0123456789abcdef01234567", "tiny.onnx", false, tempDir, 12 );
			fake.add( new FakeHub.Repo( "lab/no-sidecar", "2123456789abcdef0123456789abcdef01234567", true, Map.of( "x.onnx", new byte[] { 1, 2, 3 } ) ) );
			fake.addModel( "other/stack-tiny", "3123456789abcdef0123456789abcdef01234567", "tiny.onnx", true, tempDir, 13 );

			final List< HubModel > listed = ModelHub.list( new String[] { "lab" }, timeout );
			assertEquals( 1, listed.size() );
			final HubModel m = listed.get( 0 );
			assertEquals( "lab/stack-tiny", m.repo() );
			assertEquals( "0123456789abcdef0123456789abcdef01234567", m.revision() );
			assertEquals( "tiny.onnx", m.file() );
			assertEquals( tiny.bytes().length, m.size() );
			assertEquals( ModelHub.sha256( tempDir.resolve( "lab_stack-tiny" ).resolve( "tiny.onnx" ) ), m.sha256() );
			assertEquals( 2, ModelHub.list( new String[] { "lab", "other" }, timeout ).size() );

			// download: both files and the record in the cache, loadable, verified; a second download costs no request
			final Path onnx = ModelHub.download( m, cache, timeout );
			assertEquals( m.cachePath( cache ), onnx );
			assertTrue( Files.isRegularFile( onnx ) && Files.isRegularFile( EmbeddingModel.sidecar( onnx ) ) && Files.isRegularFile( onnx.resolveSibling( ModelHub.RECORD ) ) );
			assertEquals( tiny.fingerprint(), EmbeddingModel.load( onnx ).contract().fingerprint() );
			final int requests = fake.requests.get();
			assertEquals( onnx, ModelHub.download( m, cache, timeout ) );
			assertEquals( requests, fake.requests.get() );

			// the cache lists it; a pinned URI is answered from the cache; an unpinned one asks the hub
			final List< HubModel > cached = ModelHub.cached( cache );
			assertEquals( 1, cached.size() );
			assertEquals( m, cached.get( 0 ) );
			assertEquals( m, ModelHub.resolve( m.uri(), cache, timeout ) );
			assertEquals( requests, fake.requests.get() );
			assertEquals( m, ModelHub.resolve( "hf://lab/stack-tiny", cache, timeout ) );
			assertEquals( m, ModelHub.resolve( "hf://lab/stack-tiny@main", cache, timeout ) );
			assertEquals( m, ModelHub.resolve( "hf://lab/stack-tiny/tiny.onnx", cache, timeout ) );
			assertTrue( fake.requests.get() > requests );
			assertEquals( onnx, ModelHub.localModel( "hf://lab/stack-tiny", cache, timeout ).path() );
			assertEquals( m.uri(), ModelHub.localModel( "hf://lab/stack-tiny", cache, timeout ).spec() );
			final Path local = tempDir.resolve( "lab_stack-tiny" ).resolve( "tiny.onnx" );
			assertEquals( local, ModelHub.localModel( local.toString(), cache, timeout ).path() );
			assertEquals( local.toAbsolutePath().toString(), ModelHub.localModel( local.toString(), cache, timeout ).spec() );

			// a damaged cached file is downloaded again
			Files.write( onnx, new byte[] { 0 }, java.nio.file.StandardOpenOption.APPEND );
			assertEquals( onnx, ModelHub.download( m, cache, timeout ) );
			assertEquals( tiny.fingerprint(), EmbeddingModel.load( onnx ).contract().fingerprint() );

			// what is not there, and a hub that serves wrong bytes
			assertThrows( IOException.class, () -> ModelHub.resolve( "hf://lab/nothing", cache, timeout ) );
			assertThrows( IOException.class, () -> ModelHub.resolve( "hf://lab/stack-tiny@ffffffff", cache, timeout ) );
			assertThrows( IOException.class, () -> ModelHub.resolve( "hf://lab/stack-tiny/other.onnx", cache, timeout ) );
			assertThrows( IOException.class, () -> ModelHub.resolve( "hf://lab/no-sidecar", cache, timeout ) );
			assertThrows( IOException.class, () -> ModelHub.localModel( tempDir.resolve( "missing.onnx" ).toString(), cache, timeout ) );
			fake.corrupt = true;
			final HubModel other = ModelHub.list( new String[] { "other" }, timeout ).get( 0 );
			final IOException e = assertThrows( IOException.class, () -> ModelHub.download( other, cache, timeout ) );
			assertTrue( e.getMessage().contains( "sha256" ), e.getMessage() );
			assertTrue( !Files.exists( other.cachePath( cache ) ) );
			fake.corrupt = false;
			assertEquals( other.cachePath( cache ), ModelHub.download( other, cache, timeout ) );
			assertEquals( 2, ModelHub.cached( cache ).size() );
		}
	}

	@Test
	public void pluginWithAHubModel() throws Exception
	{
		try ( final FakeHub fake = new FakeHub() )
		{
			hub = ModelHub.hub;
			ModelHub.hub = fake.url();
			System.setProperty( ModelHub.CACHE_PROPERTY, tempDir.resolve( "cache" ).toString() );
			final TestOnnxModels.Tiny tiny = fake.addModel( "lab/stack-tiny", "0123456789abcdef0123456789abcdef01234567", "tiny.onnx", true, tempDir, 21 );

			final File xml = tempDir.resolve( "dataset.xml" ).toFile();
			final SpimData2 data = TestInterestPointEmbedding.beads( xml );
			final List< ViewId > views = TestInterestPointEmbedding.viewsWith( data, TestInterestPointEmbedding.LABEL );

			assertTrue( Embed_Interest_Points.embed( data, views, TestInterestPointEmbedding.LABEL, "hf://lab/stack-tiny", "embedding", 50, 2 ) );
			for ( final ViewId v : views )
			{
				final Vectors vectors = InterestPointArrays.vectors( data.getViewInterestPoints().getViewInterestPointLists( v ), TestInterestPointEmbedding.LABEL, "embedding" );
				assertEquals( tiny.fingerprint(), vectors.attributes().fingerprint() );
			}
			final List< ActionRecord > records = data.getActionHistory().getRecords();
			assertEquals( "hf://lab/stack-tiny@0123456789abcdef0123456789abcdef01234567/tiny.onnx", records.get( records.size() - 1 ).getParams().get( "model" ) );
			assertEquals( 1, ModelHub.cached( tempDir.resolve( "cache" ) ).size() );

			// a hub model that does not exist: no exception, nothing written
			assertTrue( !Embed_Interest_Points.embed( data, views, TestInterestPointEmbedding.LABEL, "hf://lab/nothing", "other", 50, 2 ) );
			assertNull( InterestPointArrays.attributes( data.getBasePathURI(), TestInterestPointEmbedding.LABEL, 0, "other" ) );
		}
	}

	/** Stephan's repository on the real hub, when it is reachable: listed, downloaded, loadable, the fingerprint of pcmatch's stack64 export. */
	@Test
	public void stephansRepository() throws Exception
	{
		final Duration timeout = Duration.ofSeconds( 15 );
		List< HubModel > models;
		try
		{
			models = ModelHub.list( new String[] { "StephanPreibisch" }, timeout );
		}
		catch ( final IOException e )
		{
			assumeTrue( false, "the hub is not reachable: " + e.getMessage() );
			return;
		}
		HubModel stack64 = null;
		for ( final HubModel m : models )
			if ( m.repo().equals( "StephanPreibisch/bigstitcher-embedding-translation-stack64" ) )
				stack64 = m;
		assertNotNull( stack64, "the repository is listed: " + models );
		assertNotNull( stack64.sha256() );
		assertEquals( 40, stack64.revision().length() );

		final Path onnx = ModelHub.download( stack64, tempDir.resolve( "cache" ), timeout );
		final EmbeddingModel model = EmbeddingModel.load( onnx );
		assertEquals( 64, model.contract().outputs() );
		assertEquals( 5, model.contract().channels() );
		assertEquals( "6844e0e3f80c8eb2217a2021ab0c3215cddc49e11cd78269e47c47fc2ff0cb75", model.contract().fingerprint() );
		assertEquals( stack64, ModelHub.resolve( stack64.uri(), tempDir.resolve( "cache" ), timeout ) );
	}

	/**
	 * A hub on the loopback interface: the three API answers {@link ModelHub} reads (the listing of a namespace, a repository
	 * at its current commit or at a revision, with blobs) and the file downloads with their redirect, for the repositories added.
	 */
	public static class FakeHub implements AutoCloseable
	{
		/** @param files file name -> bytes; a .onnx is reported as an LFS file with its sha256 */
		public record Repo( String id, String sha, boolean tagged, Map< String, byte[] > files ) {}

		final HttpServer server;
		final Map< String, Repo > repos = new LinkedHashMap<>();
		public final AtomicInteger requests = new AtomicInteger();
		/** serves every file with its first byte flipped */
		public volatile boolean corrupt = false;

		public FakeHub() throws IOException
		{
			server = HttpServer.create( new InetSocketAddress( InetAddress.getLoopbackAddress(), 0 ), 0 );
			server.createContext( "/", this::handle );
			server.start();
		}

		public String url() { return "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort(); }

		public void add( final Repo repo ) { repos.put( repo.id(), repo ); }

		/** adds a repository with a tiny model (written by {@link TestOnnxModels} into {@code dir/<id with / as _>}) and its sidecar */
		public TestOnnxModels.Tiny addModel( final String id, final String sha, final String file, final boolean tagged, final Path dir, final long seed ) throws IOException
		{
			final Path onnx = dir.resolve( id.replace( '/', '_' ) ).resolve( file );
			Files.createDirectories( onnx.getParent() );
			final TestOnnxModels.Tiny tiny = TestOnnxModels.write( onnx, new double[] { 3, 6 }, 4, 3, 5, seed );
			final Map< String, byte[] > files = new LinkedHashMap<>();
			files.put( "README.md", "---\ntags:\n  - bigstitcher\n---\n".getBytes( StandardCharsets.UTF_8 ) );
			files.put( file, Files.readAllBytes( onnx ) );
			files.put( file + EmbeddingModel.SIDECAR_SUFFIX, Files.readAllBytes( EmbeddingModel.sidecar( onnx ) ) );
			add( new Repo( id, sha, tagged, files ) );
			return tiny;
		}

		@Override
		public void close() { server.stop( 0 ); }

		void handle( final HttpExchange x ) throws IOException
		{
			requests.incrementAndGet();
			final URI uri = x.getRequestURI();
			final String path = uri.getPath();
			final Map< String, String > query = new LinkedHashMap<>();
			if ( uri.getRawQuery() != null )
				for ( final String kv : uri.getRawQuery().split( "&" ) )
				{
					final int eq = kv.indexOf( '=' );
					query.put( URLDecoder.decode( kv.substring( 0, eq ), StandardCharsets.UTF_8 ), URLDecoder.decode( kv.substring( eq + 1 ), StandardCharsets.UTF_8 ) );
				}

			if ( path.equals( "/api/models" ) )
			{
				final JsonArray listing = new JsonArray();
				for ( final Repo r : repos.values() )
					if ( r.id().startsWith( query.get( "author" ) + "/" ) && ( query.get( "filter" ) == null || ( r.tagged() && query.get( "filter" ).equals( ModelHub.TAG ) ) ) )
					{
						final JsonObject o = new JsonObject();
						o.addProperty( "id", r.id() );
						o.add( "tags", tags( r ) );
						listing.add( o );
					}
				json( x, 200, listing.toString() );
			}
			else if ( path.startsWith( "/api/models/" ) )
			{
				String rest = path.substring( "/api/models/".length() ), revision = "main";
				final int rev = rest.indexOf( "/revision/" );
				if ( rev >= 0 )
				{
					revision = rest.substring( rev + "/revision/".length() );
					rest = rest.substring( 0, rev );
				}
				final Repo r = repos.get( rest );
				if ( r == null || !( revision.equals( "main" ) || revision.equals( r.sha() ) ) )
				{
					json( x, 404, "{\"error\":\"not found\"}" );
					return;
				}
				final JsonObject o = new JsonObject();
				o.addProperty( "id", r.id() );
				o.addProperty( "sha", r.sha() );
				o.addProperty( "gated", false );
				o.addProperty( "private", false );
				o.add( "tags", tags( r ) );
				final JsonArray siblings = new JsonArray();
				for ( final Map.Entry< String, byte[] > f : r.files().entrySet() )
				{
					final JsonObject s = new JsonObject();
					s.addProperty( "rfilename", f.getKey() );
					s.addProperty( "size", f.getValue().length );
					if ( f.getKey().endsWith( ".onnx" ) )
					{
						final JsonObject lfs = new JsonObject();
						lfs.addProperty( "sha256", sha256( f.getValue() ) );
						lfs.addProperty( "size", f.getValue().length );
						s.add( "lfs", lfs );
					}
					siblings.add( s );
				}
				o.add( "siblings", siblings );
				json( x, 200, o.toString() );
			}
			else if ( path.contains( "/resolve/" ) )
			{
				// /<ns>/<name>/resolve/<rev>/<file> -> 307 -> /files/<ns>/<name>/<rev>/<file>, as the hub redirects
				final int i = path.indexOf( "/resolve/" );
				final String id = path.substring( 1, i ), tail = path.substring( i + "/resolve/".length() );
				final int slash = tail.indexOf( '/' );
				final Repo r = repos.get( id );
				if ( r == null || slash < 0 || !( tail.substring( 0, slash ).equals( "main" ) || tail.substring( 0, slash ).equals( r.sha() ) ) || !r.files().containsKey( tail.substring( slash + 1 ) ) )
				{
					json( x, 404, "{\"error\":\"not found\"}" );
					return;
				}
				x.getResponseHeaders().add( "Location", "/files/" + id + "/" + tail );
				x.sendResponseHeaders( 307, -1 );
				x.close();
			}
			else if ( path.startsWith( "/files/" ) )
			{
				final String[] parts = path.substring( "/files/".length() ).split( "/", 4 );
				final Repo r = repos.get( parts[ 0 ] + "/" + parts[ 1 ] );
				final byte[] bytes = r == null || parts.length < 4 ? null : r.files().get( parts[ 3 ] );
				if ( bytes == null )
				{
					json( x, 404, "{\"error\":\"not found\"}" );
					return;
				}
				final byte[] served = bytes.clone();
				if ( corrupt )
					served[ 0 ] ^= 1;
				x.sendResponseHeaders( 200, served.length );
				try ( final OutputStream out = x.getResponseBody() )
				{
					out.write( served );
				}
			}
			else
				json( x, 404, "{\"error\":\"not found\"}" );
		}

		static JsonArray tags( final Repo r )
		{
			final JsonArray tags = new JsonArray();
			tags.add( "onnx" );
			if ( r.tagged() )
				tags.add( ModelHub.TAG );
			return tags;
		}

		static void json( final HttpExchange x, final int status, final String body ) throws IOException
		{
			final byte[] bytes = body.getBytes( StandardCharsets.UTF_8 );
			x.getResponseHeaders().add( "Content-Type", "application/json" );
			x.sendResponseHeaders( status, bytes.length );
			try ( final OutputStream out = x.getResponseBody() )
			{
				out.write( bytes );
			}
		}

		static String sha256( final byte[] bytes )
		{
			try
			{
				return java.util.HexFormat.of().formatHex( java.security.MessageDigest.getInstance( "SHA-256" ).digest( bytes ) );
			}
			catch ( final java.security.NoSuchAlgorithmException e )
			{
				throw new RuntimeException( e );
			}
		}
	}
}
