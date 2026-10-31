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
package net.preibisch.mvrecon.fiji.plugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointembedding.EmbeddingModel;
import net.preibisch.mvrecon.process.interestpointembedding.InterestPointEmbedding;
import net.preibisch.mvrecon.process.interestpointembedding.ModelHub;
import net.preibisch.mvrecon.process.interestpointembedding.ModelHub.HubModel;

/**
 * Computes the embeddings of a label's interest points with a model (pcmatch's ONNX export, run by the Java evaluator)
 * and stores them as the per-point array of the label in {@code interestpoints.zarr}, what {@code pcmatch embed} does:
 * one unit vector per point of the (base) label, which "Embeddings" of "Register using Interest Points" matches on. The blocks of
 * all selected views run on one pool of {@link Threads#numThreads()} threads, {@code defaultViewsInFlight} views at a time
 * ({@link InterestPointEmbedding#embedViews}). Two entries ({@link Source}): "Translation invariant (Hugging Face)" lists the
 * translation-invariant models of the hub ({@link ModelHub}: the repositories of the known namespaces tagged {@code bigstitcher}
 * and {@code translation-invariant}, downloaded once into the cache and pinned to their commit, recorded as
 * {@code hf://<namespace>/<name>@<revision>/<file>}; the array is {@code translation-invariant} by default), "Local model" takes a
 * file (array {@code local-embedding} by default). Reachable from the ViewSetup Explorer ("Compute Interest Point Embeddings", below
 * "Detect Interest Points") and as plugins; BigStitcher-Spark's {@code embed-interestpoints} does the same per view on the cluster
 * ({@link InterestPointEmbedding}).
 *
 * @author Stephan Preibisch
 */
public class Embed_Interest_Points implements PlugIn
{
	public static int defaultLabel = -1;
	/** the label chosen last, by name; wins over defaultLabel if present */
	public static String defaultBaseLabel = null;
	/** the local file chosen last ("Local model") */
	public static String defaultModel = "";
	/** the hub model chosen last ("Translation invariant (Hugging Face)"): its {@code hf://} URI */
	public static String defaultModelChoice = null;
	public static int defaultBlockSize = InterestPointEmbedding.defaultBlockSize;
	/** views whose blocks are in the pool at a time: as many as threads keeps them busy with single-block views; it bounds the memory (the points and opened images of that many views) */
	public static int defaultViewsInFlight = Threads.numThreads();

	/** the {@code meta.writer} of the arrays this plugin writes */
	public static final String WRITER = "BigStitcher embed (Java ONNX evaluator)";

	/** the action id of the history records (ActionToSparkCli renders them as {@code embed-interestpoints}) */
	public static final String ACTION_ID = "embed-interestpoints";

	/** the entries of "Compute Interest Point Embeddings": the hub's models of one kind, or a local file; each remembers its own array name */
	public enum Source
	{
		TRANSLATION_INVARIANT( "Translation invariant (Hugging Face)", ModelHub.TRANSLATION_INVARIANT, "translation-invariant" ),
		LOCAL( "Local model", null, "local-embedding" );

		/** the menu entry (without " ...") */
		public final String title;
		/** the tag the hub's models must carry; null = a local file */
		public final String tag;
		/** the array name chosen last for this source (its default until then) */
		public String defaultArrayName;

		Source( final String title, final String tag, final String defaultArrayName )
		{
			this.title = title;
			this.tag = tag;
			this.defaultArrayName = defaultArrayName;
		}
	}

	public static class Parameters
	{
		public String label, arrayName;
		/** a path, or a hub model {@code hf://<namespace>/<name>[@<revision>][/<file>]} */
		public String model;
		public int blockSize, viewsInFlight;
	}

	/** @param arg "local" for a local model (plugins.config), anything else for the translation-invariant models of the hub */
	@Override
	public void run( final String arg )
	{
		final Source source = arg != null && arg.trim().equalsIgnoreCase( "local" ) ? Source.LOCAL : Source.TRANSLATION_INVARIANT;

		final LoadParseQueryXML xml = new LoadParseQueryXML();

		if ( !xml.queryXML( "", true, true, true, true, true ) )
			return;

		final SpimData2 data = xml.getData();

		if ( !embed( data, SpimData2.getAllViewIdsSorted( data, xml.getViewSetupsToProcess(), xml.getTimePointsToProcess() ), source ) )
			return;

		// the arrays live in the store; the XML gets the action history record
		new XmlIoSpimData2().saveWithFilename( data, xml.getXMLFileName() );
	}

	/** Asks for the label, the model of the source and the array name, then embeds the views that have the label */
	public static boolean embed( final SpimData2 data, final Collection< ? extends ViewId > viewCollection, final Source source )
	{
		final ArrayList< ViewId > viewIds = new ArrayList<>( viewCollection );

		final List< ViewId > removed = SpimData2.filterMissingViews( data, viewIds );
		if ( removed.size() > 0 )
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " + removed.size() + " views because they are not present." );

		final Parameters p = getParameters( data, viewIds, source );
		if ( p == null )
			return false;

		return embed( data, viewIds, p.label, p.model, p.arrayName, p.blockSize, p.viewsInFlight );
	}

	public static Parameters getParameters( final SpimData2 data, final List< ViewId > viewIds, final Source source )
	{
		final String[] labels = InterestPointTools.getAllInterestPointLabels( data, viewIds );

		if ( labels.length == 0 )
		{
			IOFunctions.println( "No interest points for the selected views." );
			return null;
		}

		if ( defaultBaseLabel != null )
			for ( int i = 0; i < labels.length; ++i )
				if ( InterestPointTools.getSelectedLabel( labels, i ).equals( defaultBaseLabel ) )
					defaultLabel = i;
		if ( defaultLabel < 0 || defaultLabel >= labels.length )
			defaultLabel = 0;

		// the hub's models of the source's kind (at their current commits), then the cached ones of that kind the hub does not list
		final String hubPage = ModelHub.hub + "/" + ModelHub.defaultNamespaces[ 0 ];
		final Available available = source.tag == null ? null : availableModels( source.tag );
		if ( available != null && available.models().isEmpty() )
		{
			IOFunctions.println( "No '" + source.tag + "' model found on " + ModelHub.hub + " (" + String.join( ", ", ModelHub.defaultNamespaces ) + ") or in the cache " + ModelHub.defaultCacheDir() + "; see " + hubPage );
			return null;
		}

		final GenericDialog gd = new GenericDialog( "Compute Interest Point Embeddings: " + source.title );

		gd.addChoice( "Interest_points", labels, labels[ defaultLabel ] );

		if ( available != null )
		{
			// macros record the choice's text, so it carries nothing that changes with time (the commit, whether it is cached):
			// "name (namespace)"; only an older cached revision next to the hub's current one is told apart by its commit
			final List< HubModel > models = available.models();
			final String[] modelChoices = new String[ models.size() ];
			int defaultChoice = 0;
			for ( int i = 0; i < models.size(); ++i )
			{
				final HubModel m = models.get( i );
				boolean sameRepo = false;
				for ( int j = 0; j < models.size(); ++j )
					sameRepo |= j != i && models.get( j ).repo().equals( m.repo() );
				modelChoices[ i ] = m.name() + " (" + m.namespace() + ( sameRepo && i >= available.listed() ? ", " + m.shortRevision() : "" ) + ")";
				if ( m.uri().equals( defaultModelChoice ) )
					defaultChoice = i;
			}
			gd.addChoice( "Model", modelChoices, modelChoices[ defaultChoice ] );
		}
		else
		{
			gd.addFileField( "Model (.onnx)", defaultModel, 60 );
		}
		gd.addStringField( "Array_name", source.defaultArrayName, 20 );
		gd.addNumericField( "Points_per_block", defaultBlockSize, 0 );
		gd.addNumericField( "Views_in_parallel", defaultViewsInFlight, 0 );
		gd.addMessage(
				( available != null
						? "Computes one vector per interest point with a translation-invariant model of the Hugging Face Hub (downloaded once into\n" + ModelHub.defaultCacheDir() + " and pinned to its commit)"
						: "Computes one vector per interest point with a local model (pcmatch's 'export-onnx' file with its '.onnx.json' sidecar next to it)" )
				+ " and stores the vectors with the points\n(interestpoints.zarr, <label>/tp<T>/arrays/<array name>); 'Embedding Cosine Similarity' of 'Register using Interest Points' matches on them.\n"
				+ "A filtered label is embedded through its base label (all its points). An array of another model under the same name is replaced.\n"
				+ "The blocks of 'Views in parallel' views share the " + Threads.numThreads() + " threads; the points and the opened images of that many views are\n"
				+ "in memory at once (lower it for image loaders that read whole stacks).",
				GUIHelper.smallStatusFont );
		if ( available != null )
			GUIHelper.addHyperLink( gd, "Browse the models on Hugging Face: " + hubPage, hubPage );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final Parameters p = new Parameters();
		p.label = defaultBaseLabel = InterestPointTools.getSelectedLabel( labels, defaultLabel = gd.getNextChoiceIndex() );
		if ( available != null )
		{
			p.model = defaultModelChoice = available.models().get( gd.getNextChoiceIndex() ).uri();
		}
		else
		{
			final String model = defaultModel = gd.getNextString().trim();
			if ( model.isEmpty() || !Files.isRegularFile( Paths.get( model ) ) )
			{
				IOFunctions.println( "The model '" + model + "' is no file." );
				return null;
			}
			p.model = model;
		}
		p.arrayName = source.defaultArrayName = gd.getNextString().trim();
		p.blockSize = defaultBlockSize = (int) Math.round( gd.getNextNumber() );
		p.viewsInFlight = defaultViewsInFlight = (int) Math.round( gd.getNextNumber() );

		if ( p.arrayName.isEmpty() || p.arrayName.contains( "/" ) )
		{
			IOFunctions.println( "The array name '" + p.arrayName + "' is empty or contains '/'." );
			return null;
		}

		if ( p.blockSize < 1 )
		{
			IOFunctions.println( "The block size must be positive, got " + p.blockSize + "." );
			return null;
		}

		if ( p.viewsInFlight < 1 )
		{
			IOFunctions.println( "The number of views in parallel must be positive, got " + p.viewsInFlight + "." );
			return null;
		}

		return p;
	}

	/** the models of one kind: the hub's (at their current commits) first, then the cached ones the hub does not list; {@code listed} = how many came from the hub */
	public record Available( List< HubModel > models, int listed ) {}

	/**
	 * @param tag the kind of model ({@link ModelHub#TRANSLATION_INVARIANT})
	 * @return the models with that tag on the hub ({@link ModelHub#defaultNamespaces}; a note when it cannot be reached) and in the cache
	 */
	public static Available availableModels( final String tag )
	{
		final List< HubModel > models = new ArrayList<>();
		for ( final HubModel m : hubModels() )
			if ( m.hasTag( tag ) )
				models.add( m );
		final int listed = models.size();
		try
		{
			for ( final HubModel m : ModelHub.cached( ModelHub.defaultCacheDir() ) )
			{
				boolean known = false;
				for ( final HubModel l : models )
					known |= l.uri().equals( m.uri() );
				if ( !known && m.hasTag( tag ) )
					models.add( m );
			}
		}
		catch ( final IOException e )
		{
			IOFunctions.println( "Cannot list the model cache " + ModelHub.defaultCacheDir() + ": " + e.getMessage() );
		}
		return new Available( models, listed );
	}

	/** @return the models on the hub ({@link ModelHub#defaultNamespaces}), empty with a note when the hub cannot be reached */
	public static List< HubModel > hubModels()
	{
		final List< HubModel > models = new ArrayList<>();
		try
		{
			models.addAll( ModelHub.list( ModelHub.defaultNamespaces, ModelHub.defaultTimeout ) );
		}
		catch ( final IOException e )
		{
			IOFunctions.println( "Cannot list the models on " + ModelHub.hub + " (" + e.getMessage() + "); the cached models are available." );
		}
		return models;
	}

	/** {@link #embed(SpimData2, List, String, String, String, int, int)} with {@code defaultViewsInFlight} views in the pool at a time */
	public static boolean embed( final SpimData2 data, final List< ViewId > viewIds, final String label, final Path modelFile, final String arrayName, final int blockSize )
	{
		return embed( data, viewIds, label, modelFile.toString(), arrayName, blockSize, defaultViewsInFlight );
	}

	/** {@link #embed(SpimData2, List, String, String, String, int, int)} with a local file */
	public static boolean embed( final SpimData2 data, final List< ViewId > viewIds, final String label, final Path modelFile, final String arrayName, final int blockSize, final int viewsInFlight )
	{
		return embed( data, viewIds, label, modelFile.toString(), arrayName, blockSize, viewsInFlight );
	}

	/**
	 * Embeds the points of a label in the views that have it and stores the rows, then records the action: the blocks of
	 * {@code viewsInFlight} views at a time on a pool of {@link Threads#numThreads()} threads, every view written as soon as its
	 * blocks are done ({@link InterestPointEmbedding#embedViews}).
	 *
	 * @param data the dataset
	 * @param viewIds the views (those without the label are skipped)
	 * @param label the label (a filtered label embeds and stores under its base)
	 * @param modelSpec the ONNX file (with its sidecar next to it), or a hub model {@code hf://<namespace>/<name>[@<revision>][/<file>]}
	 *        (downloaded into {@link ModelHub#defaultCacheDir()} unless cached, pinned to its commit)
	 * @param arrayName the array the rows go to
	 * @param blockSize points per inference call
	 * @param viewsInFlight views whose blocks are in the pool at a time (their points and opened images are in memory at once)
	 * @return whether rows were written
	 */
	public static boolean embed( final SpimData2 data, final List< ViewId > viewIds, final String label, final String modelSpec, final String arrayName, final int blockSize, final int viewsInFlight )
	{
		final EmbeddingModel model;
		final ModelHub.Local local;
		try
		{
			local = ModelHub.localModel( modelSpec, ModelHub.defaultCacheDir(), ModelHub.defaultTimeout );
			model = EmbeddingModel.load( local.path() );
		}
		catch ( final IOException e )
		{
			IOFunctions.println( "Cannot load the model '" + modelSpec + "': " + e.getMessage() );
			return false;
		}
		IOFunctions.println( "Model: " + local.spec() + " = " + model );

		final List< ViewId > views = InterestPointTools.viewsWithLabel( data, label, viewIds );
		if ( views.isEmpty() )
		{
			IOFunctions.println( "None of the " + viewIds.size() + " selected view(s) has interest points '" + label + "'." );
			return false;
		}
		if ( views.size() < viewIds.size() )
			IOFunctions.println( "NOTE: " + ( viewIds.size() - views.size() ) + " of the selected views have no interest points '" + label + "' and are skipped." );

		// the arrays of the (base label, timepoint) groups the views belong to, once each
		final HashSet< String > prepared = new HashSet<>();
		for ( final ViewId view : views )
		{
			final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( view );
			final String base = InterestPointArrays.baseLabel( lists, label );
			final URI baseDir = lists.getInterestPointList( base ).getBasePath();
			if ( prepared.add( baseDir + "|" + base + "|" + view.getTimePointId() ) )
				InterestPointEmbedding.prepareArray( baseDir, base, view.getTimePointId(), arrayName, model.contract(), WRITER );
		}

		final ExecutorService service = Threads.createFixedExecutorService();
		final long t = System.currentTimeMillis();
		final long points;
		try
		{
			points = InterestPointEmbedding.embedViews( data, views, label, model, blockSize, service, viewsInFlight, result ->
					InterestPointEmbedding.write( data.getViewInterestPoints().getViewInterestPointLists( result.view() ).getInterestPointList( result.baseLabel() ).getBasePath(), arrayName, result ) );
		}
		finally
		{
			service.shutdown();
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Embedded " + points + " points of " + views.size() + " view(s), label '" + label + "', as array '" + arrayName + "' with " + Threads.numThreads() + " threads, " + viewsInFlight + " view(s) in parallel, in " + ( ( System.currentTimeMillis() - t ) / 1000 ) + " s." );

		final LinkedHashMap< String, String > params = ActionHistoryRecorder.params();
		ActionHistoryRecorder.put( params, "label", label );
		ActionHistoryRecorder.put( params, "model", local.spec() );
		ActionHistoryRecorder.put( params, "arrayName", arrayName );
		ActionHistoryRecorder.put( params, "blockSize", blockSize );
		ActionHistoryRecorder.put( params, "fingerprint", model.contract().fingerprint() );
		ActionHistoryRecorder.record( data, ACTION_ID, Embed_Interest_Points.class.getName(), params, new ArrayList<>( views ), "interestpoints:" + label );

		return true;
	}

	public static void main( final String[] args )
	{
		new ij.ImageJ();
		new Embed_Interest_Points().run( null );
	}
}
