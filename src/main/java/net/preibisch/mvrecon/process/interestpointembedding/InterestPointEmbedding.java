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

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Computes the embeddings of the interest points of a view and stores them as the per-point array of the label (what
 * {@code pcmatch embed} does, now in Java): all points of the (base) label, sampled by a {@link PatchExtractor} and run
 * through an {@link EmbeddingModel} block by block, one row per point, written by {@link InterestPointArrays#write} under
 * the base label with pcmatch's meta ({@code model.fingerprint}, {@code model.config}, {@code model.file}, the writer and
 * date). The array then satisfies the matcher's rule of one row per point of the base label, for the filtered labels of
 * the base as well. The GUI ("Embed Interest Points") and BigStitcher-Spark ({@code embed-interestpoints}) share this code: the
 * GUI runs the blocks of several views on one pool ({@link #embedViews}), a Spark task embeds its one view ({@link #embedView}).
 *
 * @author Stephan Preibisch
 */
public class InterestPointEmbedding
{
	/** points per inference call: the input of a block is {@code blockSize x channels x patch^3} floats (80 KB per point for 5 x 16^3), the layers of the network a few times that */
	public static int defaultBlockSize = 128;

	/**
	 * The rows of one view: the ids of the embedded points (ascending) and their vectors, {@code columns} per point; {@code seconds}
	 * from the start of the sampling (on a pool: from the view's admission) to its last block.
	 */
	public record Result( ViewId view, String baseLabel, int[] ids, float[] values, int columns, double seconds )
	{
		public int size() { return ids.length; }
	}

	/** A view ready for the model: the points of its base label (ascending ids) and the sampler of their cubes. */
	public record Prepared( ViewId view, String baseLabel, List< InterestPoint > points, PatchExtractor extractor )
	{
		public int[] ids()
		{
			final int[] ids = new int[ points.size() ];
			for ( int i = 0; i < ids.length; ++i )
				ids[ i ] = points.get( i ).getId();
			return ids;
		}
	}

	/**
	 * Embeds the points of one view's label with a model.
	 *
	 * @param data the dataset
	 * @param view the view
	 * @param label the label (a filtered label is embedded through its base: all points of the base, under the base)
	 * @param model the model
	 * @param blockSize points per inference call
	 * @param service threads for the blocks, null = the calling thread
	 * @return the rows, by id
	 * @throws IllegalArgumentException if the label has no detection scale, the view no voxel size, or the view's images cannot be sampled
	 */
	public static Result embedView( final SpimData2 data, final ViewId view, final String label, final EmbeddingModel model, final int blockSize, final ExecutorService service )
	{
		final Prepared prepared = prepare( data, view, label, model );
		final long t = System.currentTimeMillis();
		final float[] values = embed( prepared.points(), prepared.extractor(), model, blockSize, service );
		return result( prepared, values, model.contract().outputs(), t );
	}

	/**
	 * Loads the points of a view's (base) label and sets up the sampler of their cubes.
	 *
	 * @param data the dataset
	 * @param view the view
	 * @param label the label (a filtered label is embedded through its base: all points of the base, under the base)
	 * @param model the model (its contract sets the extents and the patch)
	 * @return the view, ready for {@link #embed}
	 * @throws IllegalArgumentException if the label has no detection scale, the view no voxel size, or the view's images cannot be sampled
	 */
	public static Prepared prepare( final SpimData2 data, final ViewId view, final String label, final EmbeddingModel model )
	{
		final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( view );
		if ( lists == null || !lists.contains( label ) )
			throw new IllegalArgumentException( Group.pvid( view ) + " has no interest points '" + label + "'." );

		final String base = InterestPointArrays.baseLabel( lists, label );
		final InterestPoints list = lists.getInterestPointList( base );

		final ViewDescription vd = data.getSequenceDescription().getViewDescription( view );
		final VoxelDimensions voxelDimensions = vd.getViewSetup().getVoxelSize();
		if ( voxelDimensions == null )
			throw new IllegalArgumentException( Group.pvid( view ) + " has no voxel size in the XML; the cubes are isotropic in physical units and need the ratios." );
		final double[] voxelSize = new double[] { voxelDimensions.dimension( 0 ), voxelDimensions.dimension( 1 ), voxelDimensions.dimension( 2 ) };

		final DetectionScale detection = DetectionScale.of( base, list.getParameters(), voxelDimensions );

		final List< InterestPoint > points = new ArrayList<>( list.getInterestPointsCopy().values() );
		points.sort( ( a, b ) -> Integer.compare( a.getId(), b.getId() ) );

		final PatchExtractor.Pyramid pyramid = PatchExtractor.Pyramid.of( data.getSequenceDescription().getImgLoader(), view );
		final PatchExtractor extractor = new PatchExtractor( pyramid, voxelSize, detection, model.contract(), hasSigma( points ) );

		IOFunctions.println( "(" + new java.util.Date( System.currentTimeMillis() ) + "): Embedding " + points.size() + " points of " + Group.pvid( view ) + ", label '" + base + "'" + ( base.equals( label ) ? "" : " (base of '" + label + "')" )
				+ ": " + detection + ", " + ( extractor.usesPointSigma() ? "per-point sigma" : "the label's sigma" ) + ", pyramid of " + pyramid.numLevels() + " level(s), read floor level " + extractor.getFloorLevel()
				+ ", voxel size " + voxelSize[ 0 ] + " x " + voxelSize[ 1 ] + " x " + voxelSize[ 2 ] );

		return new Prepared( new ViewId( view.getTimePointId(), view.getViewSetupId() ), base, points, extractor );
	}

	/** logs the line of a finished view and builds its rows */
	private static Result result( final Prepared prepared, final float[] values, final int columns, final long start )
	{
		final double seconds = ( System.currentTimeMillis() - start ) / 1000.0;
		IOFunctions.println( "(" + new java.util.Date( System.currentTimeMillis() ) + "): " + Group.pvid( prepared.view() ) + ": " + prepared.points().size() + " points -> " + columns + "-D in " + String.format( "%.1f", seconds ) + " s" );
		return new Result( prepared.view(), prepared.baseLabel(), prepared.ids(), values, columns, seconds );
	}

	/** @return whether every point carries a positive, finite sigma (then the extents are per point, as pcmatch's {@code has("sigma")}) */
	public static boolean hasSigma( final List< ? extends InterestPoint > points )
	{
		if ( points.isEmpty() )
			return false;
		for ( final InterestPoint p : points )
			if ( !( Float.isFinite( p.getSigma() ) && p.getSigma() > 0 ) )
				return false;
		return true;
	}

	/**
	 * Samples and embeds points in blocks.
	 *
	 * @param points the points
	 * @param extractor the sampler of the view
	 * @param model the model
	 * @param blockSize points per inference call
	 * @param service threads for the blocks, null = the calling thread
	 * @return {@code points.size() x outputs} floats, the vector of point i at {@code i * outputs}
	 */
	public static float[] embed( final List< ? extends InterestPoint > points, final PatchExtractor extractor, final EmbeddingModel model, final int blockSize, final ExecutorService service )
	{
		final float[] values = new float[ points.size() * model.contract().outputs() ];
		if ( points.isEmpty() )
			return values;

		final List< Callable< Void > > tasks = blocks( points, extractor, model, blockSize, values );

		try
		{
			if ( service == null )
				for ( final Callable< Void > task : tasks )
					task.call();
			else
				for ( final Future< Void > f : service.invokeAll( tasks ) )
					f.get();
		}
		catch ( final InterruptedException e )
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException( "interrupted while embedding", e );
		}
		catch ( final ExecutionException e )
		{
			throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new RuntimeException( e.getCause() );
		}
		catch ( final Exception e )
		{
			throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException( e );
		}

		return values;
	}

	/**
	 * @param points the points
	 * @param extractor the sampler of the view
	 * @param model the model
	 * @param blockSize points per inference call
	 * @param values {@code points.size() x outputs} floats the vectors are stored in, the vector of point i at {@code i * outputs}
	 * @return one task per block of {@code blockSize} points: it samples the block's cubes with a {@link PatchExtractor.Sampler} of its own, runs the model and stores the vectors
	 */
	public static List< Callable< Void > > blocks( final List< ? extends InterestPoint > points, final PatchExtractor extractor, final EmbeddingModel model, final int blockSize, final float[] values )
	{
		final int n = points.size(), outputs = model.contract().outputs(), patchSize = model.contract().patchSize();
		if ( blockSize < 1 )
			throw new IllegalArgumentException( "the block size must be positive, got " + blockSize );
		if ( values.length != n * outputs )
			throw new IllegalArgumentException( n + " points x " + outputs + " outputs need " + ( n * outputs ) + " floats, got " + values.length );

		final List< Callable< Void > > tasks = new ArrayList<>();
		for ( int start = 0; start < n; start += blockSize )
		{
			final int from = start, to = Math.min( n, start + blockSize );
			tasks.add( () ->
			{
				final PatchExtractor.Sampler sampler = extractor.sampler();
				final float[] patches = new float[ ( to - from ) * patchSize ];
				for ( int i = from; i < to; ++i )
					sampler.extract( points.get( i ), patches, ( i - from ) * patchSize );
				final float[] vectors = model.embed( patches, to - from );
				System.arraycopy( vectors, 0, values, from * outputs, ( to - from ) * outputs );
				return null;
			} );
		}

		return tasks;
	}

	/**
	 * Embeds several views on one pool: the blocks of up to {@code viewsInFlight} views are in the pool at a time, so that no thread
	 * idles at the end of a view (a view of a few hundred points is a single block, and a dataset has many of them); the rows of a
	 * view are handed to {@code consumer} on the calling thread as soon as its last block is done, and the next view is admitted.
	 * {@code viewsInFlight} bounds the memory: the points and the opened pyramid levels of that many views are alive at once (a lazily
	 * loaded image costs its touched cells, a loader that reads whole stacks a stack per view), while the number of threads keeps them
	 * all busy with single-block views. The first view that fails fails the call, after the blocks not yet started are cancelled; the
	 * views already handed to the consumer stay as they are.
	 *
	 * @param data the dataset
	 * @param views the views, each with the label
	 * @param label the label (a filtered label is embedded through its base)
	 * @param model the model
	 * @param blockSize points per inference call
	 * @param service the pool; null = one view after the other on the calling thread
	 * @param viewsInFlight how many views have blocks in the pool at a time (at least 1)
	 * @param consumer receives the rows of every view, on the calling thread, in the order the views finish
	 * @return the number of points embedded
	 */
	public static long embedViews( final SpimData2 data, final List< ? extends ViewId > views, final String label, final EmbeddingModel model, final int blockSize, final ExecutorService service, final int viewsInFlight, final Consumer< Result > consumer )
	{
		if ( viewsInFlight < 1 )
			throw new IllegalArgumentException( "at least one view in flight, got " + viewsInFlight );

		long total = 0;

		if ( service == null )
		{
			for ( final ViewId view : views )
			{
				final Result result = embedView( data, view, label, model, blockSize, null );
				consumer.accept( result );
				total += result.size();
			}
			return total;
		}

		final int outputs = model.contract().outputs();
		final ExecutorCompletionService< InFlight > completion = new ExecutorCompletionService<>( service );
		final List< InFlight > inFlight = new ArrayList<>(); // the views with blocks in the pool
		boolean done = false;
		try
		{
			for ( final ViewId view : views )
			{
				while ( inFlight.size() >= viewsInFlight )
					total += finishOne( completion, inFlight, consumer );

				final Prepared prepared = prepare( data, view, label, model );
				final InFlight f = new InFlight( prepared, new float[ prepared.points().size() * outputs ], outputs );
				for ( final Callable< Void > block : blocks( prepared.points(), prepared.extractor(), model, blockSize, f.values ) )
					f.futures.add( completion.submit( () -> { block.call(); return f; } ) );
				f.remaining = f.futures.size();

				if ( f.remaining == 0 ) // no points, nothing to wait for
					consumer.accept( f.result() );
				else
					inFlight.add( f );
			}

			while ( !inFlight.isEmpty() )
				total += finishOne( completion, inFlight, consumer );

			done = true;
		}
		finally
		{
			if ( !done )
				for ( final InFlight f : inFlight )
					for ( final Future< InFlight > future : f.futures )
						future.cancel( false );
		}

		return total;
	}

	/** waits for the next block of any view in flight; if it was the view's last, hands the rows to the consumer and returns their count, else 0 */
	private static long finishOne( final ExecutorCompletionService< InFlight > completion, final List< InFlight > inFlight, final Consumer< Result > consumer )
	{
		final InFlight f;
		try
		{
			f = completion.take().get();
		}
		catch ( final InterruptedException e )
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException( "interrupted while embedding", e );
		}
		catch ( final ExecutionException e )
		{
			throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause() : new RuntimeException( e.getCause() );
		}

		if ( --f.remaining > 0 )
			return 0;

		inFlight.remove( f );
		final Result result = f.result();
		consumer.accept( result );
		return result.size();
	}

	/** A view whose blocks are in the pool: the rows they fill and how many blocks are still to come (touched by the admitting thread only). */
	private static final class InFlight
	{
		final Prepared prepared;
		final float[] values;
		final int columns;
		final long start = System.currentTimeMillis();
		final List< Future< InFlight > > futures = new ArrayList<>();
		int remaining;

		InFlight( final Prepared prepared, final float[] values, final int columns )
		{
			this.prepared = prepared;
			this.values = values;
			this.columns = columns;
		}

		Result result() { return InterestPointEmbedding.result( prepared, values, columns, start ); }
	}

	/**
	 * @param contract the model
	 * @param writer who writes the rows (e.g. "BigStitcher embed", "BigStitcher-Spark embed-interestpoints")
	 * @return the meta of the array, as pcmatch writes it: {@code model {config, file, fingerprint, onnx}, writer, date, point_sigma}
	 */
	public static JsonObject meta( final EmbeddingModel.Contract contract, final String writer )
	{
		final JsonObject meta = new JsonObject();
		meta.add( "model", contract.metaModel() );
		meta.addProperty( "writer", writer );
		meta.addProperty( "date", LocalDateTime.now().format( DateTimeFormatter.ofPattern( "yyyy-MM-dd'T'HH:mm:ss" ) ) );
		meta.addProperty( "point_sigma", true );
		return meta;
	}

	/**
	 * Prepares the array of a (base label, timepoint) for the rows of a model: creates it with its attributes, keeps it if it
	 * exists for the same model and width, and replaces it (all its setups) if another model or width wrote it, since the
	 * matcher refuses to compare rows of two models and a half-replaced array would fail every pair across the boundary.
	 * On BigStitcher-Spark the driver calls this once before the tasks write their setups.
	 *
	 * @param baseDir the dataset directory
	 * @param baseLabel the label the rows are stored under
	 * @param timepoint the timepoint
	 * @param name the array
	 * @param contract the model
	 * @param writer who writes
	 * @return the meta written or kept
	 */
	public static JsonObject prepareArray( final URI baseDir, final String baseLabel, final int timepoint, final String name, final EmbeddingModel.Contract contract, final String writer )
	{
		final InterestPointArrays.Attributes existing = InterestPointArrays.attributes( baseDir, baseLabel, timepoint, name );
		final JsonObject meta = meta( contract, writer );

		if ( existing != null )
		{
			if ( existing.columns() == contract.outputs() && contract.fingerprint().equals( existing.fingerprint() ) )
			{
				IOFunctions.println( "The array '" + name + "' of label '" + baseLabel + "', tpId=" + timepoint + " exists for this model (fingerprint " + contract.fingerprint() + "); its setups are replaced one by one." );
				return existing.meta();
			}

			IOFunctions.println( "The array '" + name + "' of label '" + baseLabel + "', tpId=" + timepoint + " was written by another model (fingerprint " + existing.fingerprint() + ", " + existing.columns() + " columns) and is replaced as a whole: rows of two models cannot be matched against each other." );
			InterestPointArrays.remove( baseDir, baseLabel, timepoint, name );
		}

		InterestPointArrays.createArray( baseDir, baseLabel, timepoint, name, contract.outputs(), meta );
		return meta;
	}

	/**
	 * Writes the rows of a view into the (prepared) array of its base label, replacing the setup's rows.
	 *
	 * @param baseDir the dataset directory
	 * @param name the array
	 * @param result the rows
	 */
	public static void write( final URI baseDir, final String name, final Result result )
	{
		InterestPointArrays.writeSetup( baseDir, result.baseLabel(), result.view().getTimePointId(), name, result.view().getViewSetupId(), result.ids(), result.values(), result.columns() );
	}
}
