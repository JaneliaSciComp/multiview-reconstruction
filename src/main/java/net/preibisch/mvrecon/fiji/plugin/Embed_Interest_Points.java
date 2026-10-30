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

/**
 * Computes the embeddings of a label's interest points with a model (pcmatch's ONNX export, run by the Java evaluator)
 * and stores them as the per-point array of the label in {@code interestpoints.zarr}, what {@code pcmatch embed} does:
 * one unit vector per point of the (base) label, which "Embeddings" of "Register using Interest Points" matches on. The blocks of
 * all selected views run on one pool of {@link Threads#numThreads()} threads, {@code defaultViewsInFlight} views at a time
 * ({@link InterestPointEmbedding#embedViews}). Reachable from the ViewSetup Explorer ("Embed Interest Points (ONNX model) ...",
 * below "Detect Interest Points") and as a plugin; BigStitcher-Spark's {@code embed-interestpoints} does the same per view on the
 * cluster ({@link InterestPointEmbedding}).
 *
 * @author Stephan Preibisch
 */
public class Embed_Interest_Points implements PlugIn
{
	public static int defaultLabel = -1;
	/** the label chosen last, by name; wins over defaultLabel if present */
	public static String defaultBaseLabel = null;
	public static String defaultModel = "";
	public static String defaultArrayName = InterestPointArrays.EMBEDDING;
	public static int defaultBlockSize = InterestPointEmbedding.defaultBlockSize;
	/** views whose blocks are in the pool at a time: as many as threads keeps them busy with single-block views; it bounds the memory (the points and opened images of that many views) */
	public static int defaultViewsInFlight = Threads.numThreads();

	/** the {@code meta.writer} of the arrays this plugin writes */
	public static final String WRITER = "BigStitcher embed (Java ONNX evaluator)";

	/** the action id of the history records (ActionToSparkCli renders them as {@code embed-interestpoints}) */
	public static final String ACTION_ID = "embed-interestpoints";

	public static class Parameters
	{
		public String label, arrayName;
		public Path model;
		public int blockSize, viewsInFlight;
	}

	@Override
	public void run( final String arg )
	{
		final LoadParseQueryXML xml = new LoadParseQueryXML();

		if ( !xml.queryXML( "", true, true, true, true, true ) )
			return;

		final SpimData2 data = xml.getData();

		if ( !embed( data, SpimData2.getAllViewIdsSorted( data, xml.getViewSetupsToProcess(), xml.getTimePointsToProcess() ) ) )
			return;

		// the arrays live in the store; the XML gets the action history record
		new XmlIoSpimData2().saveWithFilename( data, xml.getXMLFileName() );
	}

	/** Asks for the label, the model and the array name, then embeds the views that have the label */
	public static boolean embed( final SpimData2 data, final Collection< ? extends ViewId > viewCollection )
	{
		final ArrayList< ViewId > viewIds = new ArrayList<>( viewCollection );

		final List< ViewId > removed = SpimData2.filterMissingViews( data, viewIds );
		if ( removed.size() > 0 )
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " + removed.size() + " views because they are not present." );

		final Parameters p = getParameters( data, viewIds );
		if ( p == null )
			return false;

		return embed( data, viewIds, p.label, p.model, p.arrayName, p.blockSize, p.viewsInFlight );
	}

	public static Parameters getParameters( final SpimData2 data, final List< ViewId > viewIds )
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

		final GenericDialog gd = new GenericDialog( "Embed interest points" );

		gd.addChoice( "Interest_points", labels, labels[ defaultLabel ] );
		gd.addFileField( "Model (.onnx)", defaultModel, 60 );
		gd.addStringField( "Array_name", defaultArrayName, 20 );
		gd.addNumericField( "Points_per_block", defaultBlockSize, 0 );
		gd.addNumericField( "Views_in_parallel", defaultViewsInFlight, 0 );
		gd.addMessage(
				"Computes one vector per interest point with the model (pcmatch's 'export-onnx' file; its '.onnx.json' sidecar must sit next to it)\n"
				+ "and stores the vectors with the points (interestpoints.zarr, <label>/tp<T>/arrays/<array name>), as 'pcmatch embed' does;\n"
				+ "'Embeddings' of 'Register using Interest Points' matches on them. A filtered label is embedded through its base label (all its points).\n"
				+ "An array of another model under the same name is replaced. The blocks of 'Views in parallel' views share the " + Threads.numThreads() + " threads;\n"
				+ "the points and the opened images of that many views are in memory at once (lower it for image loaders that read whole stacks).",
				GUIHelper.smallStatusFont );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final Parameters p = new Parameters();
		p.label = defaultBaseLabel = InterestPointTools.getSelectedLabel( labels, defaultLabel = gd.getNextChoiceIndex() );
		final String model = defaultModel = gd.getNextString().trim();
		p.arrayName = defaultArrayName = gd.getNextString().trim();
		p.blockSize = defaultBlockSize = (int) Math.round( gd.getNextNumber() );
		p.viewsInFlight = defaultViewsInFlight = (int) Math.round( gd.getNextNumber() );

		if ( model.isEmpty() || !Files.isRegularFile( Paths.get( model ) ) )
		{
			IOFunctions.println( "The model '" + model + "' is no file." );
			return null;
		}
		p.model = Paths.get( model );

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

	/** {@link #embed(SpimData2, List, String, Path, String, int, int)} with {@code defaultViewsInFlight} views in the pool at a time */
	public static boolean embed( final SpimData2 data, final List< ViewId > viewIds, final String label, final Path modelFile, final String arrayName, final int blockSize )
	{
		return embed( data, viewIds, label, modelFile, arrayName, blockSize, defaultViewsInFlight );
	}

	/**
	 * Embeds the points of a label in the views that have it and stores the rows, then records the action: the blocks of
	 * {@code viewsInFlight} views at a time on a pool of {@link Threads#numThreads()} threads, every view written as soon as its
	 * blocks are done ({@link InterestPointEmbedding#embedViews}).
	 *
	 * @param data the dataset
	 * @param viewIds the views (those without the label are skipped)
	 * @param label the label (a filtered label embeds and stores under its base)
	 * @param modelFile the ONNX file (with its sidecar)
	 * @param arrayName the array the rows go to
	 * @param blockSize points per inference call
	 * @param viewsInFlight views whose blocks are in the pool at a time (their points and opened images are in memory at once)
	 * @return whether rows were written
	 */
	public static boolean embed( final SpimData2 data, final List< ViewId > viewIds, final String label, final Path modelFile, final String arrayName, final int blockSize, final int viewsInFlight )
	{
		final EmbeddingModel model;
		try
		{
			model = EmbeddingModel.load( modelFile );
		}
		catch ( final IOException e )
		{
			IOFunctions.println( "Cannot load the model: " + e.getMessage() );
			return false;
		}
		IOFunctions.println( "Model: " + model );

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
		ActionHistoryRecorder.put( params, "model", modelFile.toAbsolutePath().toString() );
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
