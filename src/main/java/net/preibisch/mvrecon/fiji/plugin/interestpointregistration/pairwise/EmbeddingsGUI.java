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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointArrays;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwise;
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
	public String getDescription() { return "Embeddings (stored per-point vectors, pcmatch)"; }

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

	@Override
	public void prepareQuery( final SpimData2 data, final List< ViewId > viewIds, final Map< ViewId, HashMap< String, Double > > labelMap )
	{
		final SortedSet< String > names = InterestPointArrays.names( data.getViewInterestPoints(), labelMap );
		arrayNames = names.toArray( new String[ 0 ] );

		if ( arrayNames.length == 0 )
			IOFunctions.println( "The selected label(s) have no stored embeddings (no per-point array in interestpoints.zarr): run 'pcmatch embed' first." );
	}

	@Override
	public void addQuery( final GenericDialog gd )
	{
		gd.addChoice( "Transformation model", TransformationModelGUI.modelChoice, TransformationModelGUI.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );

		gd.addCheckbox( "Limit_search_radius", EmbeddingsParameters.defaultLimitSearchRadius );
		gd.addSlider( "Search_radius (px, global coordinates)", 10.0, 1000.0, EmbeddingsParameters.defaultSearchRadius );
		gd.addSlider( "Ratio_test (similarity space)", 0.5, 1.0, EmbeddingsParameters.defaultRatio );

		// the stored embeddings of the selected labels (prepareQuery): a choice even if it is one; none means the dialog cannot be completed
		if ( arrayNames == null || arrayNames.length == 0 )
			gd.addMessage( "The selected label(s) have no stored embeddings in interestpoints.zarr: run 'pcmatch embed' first (OK will not proceed).", GUIHelper.smallStatusFont );
		else
			gd.addChoice( "Embeddings", arrayNames, Arrays.asList( arrayNames ).contains( EmbeddingsParameters.defaultArrayName ) ? EmbeddingsParameters.defaultArrayName : arrayNames[ 0 ] );
		gd.addMessage(
				"Matches on the vectors 'pcmatch embed' stored with the points (interestpoints.zarr, <label>/tp<T>/arrays/<name>).\n"
				+ "Partners are looked for within the search radius, which is the registration's uncertainty (about 20 px for a well\n"
				+ "pre-registered dataset, 300-500 px for large translations; without a limit every point of the other view is a\n"
				+ "possible partner, which is slow on large views); the best partner by cosine is a candidate if\n"
				+ "(1 - best) < ratio x (1 - second best), or alone within the radius; the smallest cosine is " + EmbeddingsMatcher.defaultSmin + ".",
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

		final RANSACParameters rp = parseRansacQuery( gd );
		if ( rp == null )
			return false;

		this.parameters = new EmbeddingsParameters( model.getModel(), limitSearchRadius ? searchRadius : Double.POSITIVE_INFINITY, ratio, EmbeddingsMatcher.defaultSmin, arrayName );
		this.ransacParams = rp;

		IOFunctions.println( "Selected Paramters:" );
		IOFunctions.println( "model: " + defaultModel );
		IOFunctions.println( "searchRadius: " + ( limitSearchRadius ? Double.toString( searchRadius ) : "no limit" ) );
		IOFunctions.println( "ratio: " + ratio );
		IOFunctions.println( "smin: " + EmbeddingsMatcher.defaultSmin );
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
		p.put( "arrayName", parameters.getArrayName() ); // always: the command line picks an array by itself only when a label has exactly one
		putRansacParams( p, ransacParams );
		return p;
	}
}
