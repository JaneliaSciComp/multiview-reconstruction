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
import java.util.LinkedHashMap;
import java.util.Map;

import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.TransformationModelGUI;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
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
	public void addQuery( final GenericDialog gd )
	{
		gd.addChoice( "Transformation model", TransformationModelGUI.modelChoice, TransformationModelGUI.modelChoice[ defaultModel ] );
		gd.addCheckbox( "Regularize_model", defaultRegularize );

		gd.addNumericField( "Search_radius (px, global coordinates)", EmbeddingsParameters.defaultSearchRadius, 1 );
		gd.addSlider( "Ratio_test (similarity space)", 0.5, 1.0, EmbeddingsParameters.defaultRatio );
		gd.addStringField( "Array_name", EmbeddingsParameters.defaultArrayName, 20 );
		gd.addMessage(
				"Matches on the vectors 'pcmatch embed' stored with the points (interestpoints.zarr, <label>/tp<T>/arrays/<name>).\n"
				+ "Partners are looked for within the search radius, which is the registration's uncertainty (about 20 px for a well\n"
				+ "pre-registered dataset, 300-500 px for large translations); the best partner by cosine is a candidate if\n"
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

		final double searchRadius = EmbeddingsParameters.defaultSearchRadius = gd.getNextNumber();
		final double ratio = EmbeddingsParameters.defaultRatio = gd.getNextNumber();
		final String arrayName = EmbeddingsParameters.defaultArrayName = gd.getNextString().trim();

		if ( !( searchRadius > 0 ) )
		{
			IOFunctions.println( "The search radius must be positive, got " + searchRadius + "." );
			return false;
		}

		if ( !( ratio > 0 && ratio <= 1 ) )
		{
			IOFunctions.println( "The ratio must be in (0, 1], got " + ratio + "." );
			return false;
		}

		if ( !arrayName.matches( "[A-Za-z0-9_.-]+" ) )
		{
			IOFunctions.println( "The array name must be a plain identifier ([A-Za-z0-9_.-]+), got '" + arrayName + "'." );
			return false;
		}

		final RANSACParameters rp = parseRansacQuery( gd );
		if ( rp == null )
			return false;

		this.parameters = new EmbeddingsParameters( model.getModel(), searchRadius, ratio, EmbeddingsMatcher.defaultSmin, arrayName );
		this.ransacParams = rp;

		IOFunctions.println( "Selected Paramters:" );
		IOFunctions.println( "model: " + defaultModel );
		IOFunctions.println( "searchRadius: " + searchRadius );
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
		p.put( "searchRadius", Double.toString( parameters.getSearchRadius() ) );
		p.put( "descriptorRatio", Double.toString( parameters.getRatio() ) );
		if ( !parameters.getArrayName().equals( "descriptor" ) )
			p.put( "arrayName", parameters.getArrayName() );
		putRansacParams( p, ransacParams );
		return p;
	}
}
