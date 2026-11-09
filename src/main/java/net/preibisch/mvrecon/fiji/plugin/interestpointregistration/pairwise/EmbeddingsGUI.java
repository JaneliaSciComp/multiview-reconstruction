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
package net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise;

import java.awt.Font;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays.Attributes;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.embeddings.EmbeddingsParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;

/**
 * Matching on the embeddings stored with the interest points (pcmatch's {@code embed} writes one unit vector per point
 * into the interest point store), {@link EmbeddingsPairwise}: partners within a search radius in the registered frame,
 * one cosine per candidate pair, a ratio test, then RANSAC. BigStitcher-Spark: {@code match-interestpoints -m EMBEDDINGS}.
 *
 * @author Stephan Preibisch
 */
public class EmbeddingsGUI extends PairwiseGUI
{
	public static int defaultModel = 2;
	public static boolean defaultRegularize = true;

	protected TransformationModelGUI model = null;
	protected EmbeddingsParameters parameters;
	protected RANSACParameters ransacParams;

	/** the stored embeddings (per-point arrays) the selected labels have, found by {@link #prepareQuery}; the dialog offers them as a choice */
	protected String[] arrayNames = null;

	final SpimData2 spimData;

	public EmbeddingsGUI( final SpimData2 spimData )
	{
		this.spimData = spimData;
	}

	@Override
	public EmbeddingsGUI newInstance( final SpimData2 spimData ) { return new EmbeddingsGUI( spimData ); }

	@Override
	public String getDescription() { return "Embedding Cosine Similarity"; }

	/**
	 * @param data the dataset
	 * @param viewIds the selected views
	 * @return whether any label of the selected views has stored embeddings (a per-point array, {@link InterestPointArrays#names});
	 *         then this is the registration dialog's algorithm until the user picks one ({@code Interest_Point_Registration.defaultAlgorithm})
	 */
	public static boolean hasEmbeddings( final SpimData2 data, final List< ViewId > viewIds )
	{
		final HashMap< ViewId, HashMap< String, Double > > labelMap = new HashMap<>();
		for ( final ViewId viewId : viewIds )
		{
			final ViewInterestPointLists lists = data.getViewInterestPoints().getViewInterestPointLists( viewId );
			if ( lists == null )
				continue;
			final HashMap< String, Double > labels = new HashMap<>();
			for ( final String label : lists.getHashMap().keySet() )
				labels.put( label, 1.0 );
			labelMap.put( viewId, labels );
		}
		return !InterestPointArrays.names( data.getViewInterestPoints(), labelMap ).isEmpty();
	}

	@Override
	public MatcherPairwise< InterestPoint > pairwiseMatchingInstance()
	{
		return new EmbeddingsPairwise<>( ransacParams, parameters, spimData.getViewInterestPoints() );
	}

	@Override
	public MatcherPairwise< GroupedInterestPoint< ViewId > > pairwiseGroupedMatchingInstance()
	{
		final String message = "Embeddings matching does not support grouped views (group tiles/illuminations/channels, split timepoints): every view keeps its own stored vectors.";
		IOFunctions.println( message );
		throw new IllegalArgumentException( message );
	}

	/** a selected view with one of its labels: the (base label, timepoint) group its arrays live in and the number of points the rows must cover */
	private record Need( ViewId viewId, String label, String group, URI baseDir, int points ) {}

	/**
	 * The matcher needs one row per point of the (base) label in every selected view ({@link InterestPointArrays#vectors}), so
	 * only the arrays that are complete for all of them are offered. If none is, the registration stops here and the log names
	 * the views whose rows are missing or stale (Stephan 2026-10-31: no dialog that cannot be completed).
	 */
	@Override
	public boolean prepareQuery( final SpimData2 data, final List< ViewId > viewIds, final Map< ViewId, HashMap< String, Double > > labelMap )
	{
		final ViewInterestPoints vip = data.getViewInterestPoints();

		final ArrayList< Need > needs = new ArrayList<>();
		final HashMap< String, List< Attributes > > listed = new HashMap<>(); // the arrays of every (base path, base label, timepoint) group
		final TreeSet< String > names = new TreeSet<>(), labels = new TreeSet<>();

		for ( final ViewId viewId : viewIds )
		{
			final HashMap< String, Double > viewLabels = labelMap.get( viewId );
			final ViewInterestPointLists lists = vip.getViewInterestPointLists( viewId );
			if ( viewLabels == null || lists == null )
				continue;

			for ( final String label : viewLabels.keySet() )
			{
				final String base = InterestPointArrays.baseLabel( lists, label );
				final URI baseDir = lists.getInterestPointList( label ).getBasePath();
				final String group = baseDir + "|" + base + "|" + lists.getTimePointId();
				final List< Attributes > arrays = listed.computeIfAbsent( group, g -> InterestPointArrays.list( baseDir, base, lists.getTimePointId() ) );
				for ( final Attributes array : arrays )
					names.add( array.name() );
				labels.add( label );
				needs.add( new Need( viewId, label, group, baseDir, lists.getInterestPointList( base ).getInterestPointsCopy().size() ) );
			}
		}

		// per array, the views it does not cover
		final TreeMap< String, ArrayList< String > > gaps = new TreeMap<>();
		final HashMap< String, SortedMap< Integer, Long > > rowsOf = new HashMap<>(); // the rows per setup of every (group, array)
		for ( final String name : names )
		{
			final ArrayList< String > missing = gaps.computeIfAbsent( name, n -> new ArrayList<>() );
			for ( final Need need : needs )
			{
				Attributes array = null;
				for ( final Attributes a : listed.get( need.group() ) )
					if ( a.name().equals( name ) )
						array = a;
				final Attributes found = array;
				final Long rows = found == null ? null : rowsOf.computeIfAbsent( need.group() + "|" + name, k -> InterestPointArrays.rows( need.baseDir(), found ) ).get( need.viewId().getViewSetupId() );
				if ( rows == null ? need.points() > 0 : rows != need.points() )
					missing.add( Group.pvid( need.viewId() ) + " '" + need.label() + "': " + ( rows == null ? "no rows" : rows + " rows" ) + ", " + need.points() + " points" );
			}
		}

		final ArrayList< String > complete = new ArrayList<>();
		for ( final Map.Entry< String, ArrayList< String > > e : gaps.entrySet() )
			if ( e.getValue().isEmpty() )
				complete.add( e.getKey() );
			else
				IOFunctions.println( "The stored embeddings '" + e.getKey() + "' do not cover every selected view (one row per point of the base label is needed): " + String.join( "; ", e.getValue() ) );
		arrayNames = complete.toArray( new String[ 0 ] );

		if ( arrayNames.length == 0 )
		{
			if ( names.isEmpty() )
				IOFunctions.println( "The label(s) " + labels + " of the selected views have no stored embeddings (no per-point array in interestpoints.zarr)." );
			IOFunctions.println( "Run 'Compute interest point embeddings (ONNX)' on the selected views first (it embeds all points of the label), or deselect the views without embeddings." );
			return false;
		}
		return true;
	}

	@Override
	public void addQuery( final GenericDialog gd )
	{
		gd.addChoice( "Transformation model", TransformationModelGUI.modelChoice, TransformationModelGUI.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );

		gd.addCheckbox( "Limit_search_radius", EmbeddingsParameters.defaultLimitSearchRadius );
		gd.addSlider( "Search_radius (px, global coordinates)", 10.0, 1000.0, EmbeddingsParameters.defaultSearchRadius );
		gd.addSlider( "Ratio_test (similarity space)", 0.5, 1.0, EmbeddingsParameters.defaultRatio );
		gd.addSlider( "Minimal_cosine_similarity", -1.0, 1.0, EmbeddingsParameters.defaultSmin );

		// the stored embeddings of the selected labels (prepareQuery): a choice even if it is one; none means the dialog cannot be completed
		if ( arrayNames == null || arrayNames.length == 0 )
			gd.addMessage( "The selected label(s) have no stored embeddings in interestpoints.zarr: run 'pcmatch embed' first (OK will not proceed).", GUIHelper.smallStatusFont );
		else
			gd.addChoice( "Embeddings", arrayNames, Arrays.asList( arrayNames ).contains( EmbeddingsParameters.defaultArrayName ) ? EmbeddingsParameters.defaultArrayName : arrayNames[ 0 ] );
		gd.addMessage(
				"Matches on the embeddings 'pcmatch embed' stored with the points (interestpoints.zarr, <label>/tp<T>/arrays/<name>):\n"
				+ "for every point, the partner with the highest cosine similarity is a candidate if (1 - best) < ratio x (1 - second best),\n"
				+ "or if it is the only one; a candidate needs at least the minimal cosine similarity. RANSAC then fits the model.",
				GUIHelper.smallStatusFont );

		gd.addMessage( "" );
		gd.addMessage( "Parameters for robust model-based outlier removal (RANSAC)", new Font( Font.SANS_SERIF, Font.BOLD, 12 ) );
		gd.addMessage( "" );

		addRansacQuery( gd );
	}

	@Override
	public boolean parseDialog( final GenericDialog gd )
	{
		model = new TransformationModelGUI( defaultModel = gd.getNextChoiceIndex() );

		if ( defaultRegularize = gd.getNextBoolean() )
		{
			if ( !model.queryRegularizedModel() )
				return false;
		}

		final boolean limitSearchRadius = EmbeddingsParameters.defaultLimitSearchRadius = gd.getNextBoolean();
		final double searchRadius = EmbeddingsParameters.defaultSearchRadius = gd.getNextNumber();
		final double ratio = EmbeddingsParameters.defaultRatio = gd.getNextNumber();
		final double smin = EmbeddingsParameters.defaultSmin = gd.getNextNumber();

		if ( arrayNames == null || arrayNames.length == 0 )
		{
			IOFunctions.println( "The selected label(s) have no stored embeddings: run 'pcmatch embed' first." );
			return false;
		}

		final String arrayName = EmbeddingsParameters.defaultArrayName = arrayNames[ gd.getNextChoiceIndex() ];

		if ( limitSearchRadius && !( searchRadius > 0 ) )
		{
			IOFunctions.println( "The search radius must be positive, got " + searchRadius + "." );
			return false;
		}

		if ( !( ratio > 0 && ratio <= 1 ) )
		{
			IOFunctions.println( "The ratio must be in (0, 1], got " + ratio + "." );
			return false;
		}

		if ( !( smin >= -1 && smin <= 1 ) )
		{
			IOFunctions.println( "The minimal cosine similarity must be in [-1, 1], got " + smin + "." );
			return false;
		}

		final RANSACParameters rp = parseRansacQuery( gd );
		if ( rp == null )
			return false;

		this.parameters = new EmbeddingsParameters( model.getModel(), limitSearchRadius ? searchRadius : Double.POSITIVE_INFINITY, ratio, smin, arrayName );
		this.ransacParams = rp;

		IOFunctions.println( "Selected Paramters:" );
		IOFunctions.println( "model: " + defaultModel );
		IOFunctions.println( "searchRadius: " + ( limitSearchRadius ? Double.toString( searchRadius ) : "no limit" ) );
		IOFunctions.println( "ratio: " + ratio );
		IOFunctions.println( "minSimilarity: " + smin );
		IOFunctions.println( "arrayName: " + arrayName );

		return true;
	}

	@Override
	public TransformationModelGUI getMatchingModel() { return model; }

	@Override
	public double getMaxError() { return ransacParams.getMaxEpsilon(); }

	@Override
	public double globalOptError() { return ransacParams.getMaxEpsilon(); }

	@Override
	public Map< String, String > describeParameters()
	{
		final LinkedHashMap< String, String > p = new LinkedHashMap<>();
		p.put( "matchingMethod", "EMBEDDINGS" );
		putModelParams( p, model );
		if ( parameters.limitSearchRadius() )
			p.put( "searchRadius", Double.toString( parameters.getSearchRadius() ) ); // absent = no limit, as for the geometric descriptors
		p.put( "descriptorRatio", Double.toString( parameters.getRatio() ) );
		p.put( "minSimilarity", Double.toString( parameters.getSmin() ) );
		p.put( "arrayName", parameters.getArrayName() ); // always: the command line picks an array by itself only when a label has exactly one
		putRansacParams( p, ransacParams );
		return p;
	}
}
