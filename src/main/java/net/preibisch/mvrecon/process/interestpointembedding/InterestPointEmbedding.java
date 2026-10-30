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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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
 * the base as well. The GUI ("Embed Interest Points") and BigStitcher-Spark ({@code embed-interestpoints}) share this code.
 *
 * @author Stephan Preibisch
 */
public class InterestPointEmbedding
{
	/** points per inference call: the input of a block is {@code blockSize x channels x patch^3} floats (80 KB per point for 5 x 16^3), the layers of the network a few times that */
	public static int defaultBlockSize = 128;

	/** The rows of one view: the ids of the embedded points (ascending) and their vectors, {@code columns} per point. */
	public record Result( ViewId view, String baseLabel, int[] ids, float[] values, int columns, double seconds )
	{
		public int size() { return ids.length; }
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
				+ ", voxel size " + voxelSize[ 0 ] + " x " + voxelSize[ 1 ] + " x " + voxelSize[ 2 ] + ", blocks of " + blockSize );

		final long t = System.currentTimeMillis();
		final float[] values = embed( points, extractor, model, blockSize, service );
		final int[] ids = new int[ points.size() ];
		for ( int i = 0; i < ids.length; ++i )
			ids[ i ] = points.get( i ).getId();

		final double seconds = ( System.currentTimeMillis() - t ) / 1000.0;
		IOFunctions.println( "(" + new java.util.Date( System.currentTimeMillis() ) + "): " + Group.pvid( view ) + ": " + points.size() + " points -> " + model.contract().outputs() + "-D in " + String.format( "%.1f", seconds ) + " s" );

		return new Result( new ViewId( view.getTimePointId(), view.getViewSetupId() ), base, ids, values, model.contract().outputs(), seconds );
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
		final int n = points.size(), outputs = model.contract().outputs(), patchSize = model.contract().patchSize();
		final float[] values = new float[ n * outputs ];
		if ( n == 0 )
			return values;
		if ( blockSize < 1 )
			throw new IllegalArgumentException( "the block size must be positive, got " + blockSize );

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
