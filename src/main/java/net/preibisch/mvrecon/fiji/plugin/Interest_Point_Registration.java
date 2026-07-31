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

import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.models.AbstractModel;
import mpicbg.models.Affine3D;
import mpicbg.models.Model;
import mpicbg.models.RigidModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.global.GlobalOptimizationParameters.GlobalOptType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.CenterOfMassGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.FRGLDMGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.GeometricHashingGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.IterativeClosestPointGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.LoadCorrespondencesGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.PairwiseGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.RGLDMGUI;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.AdvancedRegistrationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters.InterestPointOverlapType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters.OverlapType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters.RegistrationType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.FixMapBackParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.GroupParameters;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.GroupParameters.InterestpointGroupingType;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.statistics.RegistrationStatistics;
import net.preibisch.mvrecon.fiji.plugin.interestpointregistration.statistics.TimeLapseDisplay;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOpt;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOptIterative;
import net.preibisch.mvrecon.process.interestpointregistration.global.GlobalOptTwoRound;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.ConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.convergence.SimpleIterativeConvergenceStrategy;
import net.preibisch.mvrecon.process.interestpointregistration.global.linkremoval.MaxErrorLinkRemoval;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.PointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.InterestPointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.weak.MetaDataWeakLinkFactory;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwiseTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.PairwiseSetup;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.Subset;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.GroupedInterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.InterestPointGroupingMinDistance;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.OverlapDetection;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;

/**
*
* @author Stephan Preibisch (stephan.preibisch@gmx.de)
*
*/
public class Interest_Point_Registration implements PlugIn
{
	public static ArrayList< PairwiseGUI > staticPairwiseAlgorithms = new ArrayList< PairwiseGUI >();

	static
	{
		IOFunctions.printIJLog = true;
		staticPairwiseAlgorithms.add( new GeometricHashingGUI() ); // good method
		staticPairwiseAlgorithms.add( new FRGLDMGUI() ); // good method
		staticPairwiseAlgorithms.add( new RGLDMGUI() ); // good method
		staticPairwiseAlgorithms.add( new CenterOfMassGUI() );
		staticPairwiseAlgorithms.add( new IterativeClosestPointGUI() );
		staticPairwiseAlgorithms.add( new LoadCorrespondencesGUI( null ) );
	}

	// basic dialog
	public static int defaultAlgorithm = 2;
	public static int defaultRegistrationType = 0;
	public static int defaultOverlapType = 1;
	public static int defaultInterestpointOverlapType = 1;
	public static int defaultLabel = -1;
	public static boolean defaultMatchAcrossLabels = false;
	public static boolean defaultGroupTiles = true;
	public static boolean defaultGroupIllums = true;
	public static boolean defaultGroupChannels = true;
	public static boolean[] defaultLabelChoice = null;
	public static double[] defaultLabelWeights = null;

	// advanced dialog
	public static int defaultRange = 5;
	public static int defaultReferenceTimepointIndex = -1;
	public static boolean defaultGroupTimePoints = false;
	public static int defaultFixViews = 0;
	public static int defaultMapBack = 0;
	public static boolean defaultShowStatistics = true;

	// fix and map back dialog
	public static boolean defaultSameFixedViews = true;
	public static boolean defaultSameReferenceView = true;
	public static boolean[] defaultFixedViews = null;
	public static int defaultReferenceView = 0;
	public static int defaultIPGrouping = 1;
	public static double defaultIPDistance = 5;

	// Just in case we want to log statistics
	List< Pair< Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > > statistics;

	@Override
	public void run( final String arg )
	{
		// ask for everything but the channels
		final LoadParseQueryXML result = new LoadParseQueryXML();

		if ( !result.queryXML( "for performing interest point registration", true, true, true, true, true ) )
			return;

		register(
			result.getData(),
			SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() ),
			result.getXMLFileName(),
			true );
	}

	public boolean register(
			final SpimData2 data,
			final Collection< ? extends ViewId > viewCollection )
	{
		return register( data, viewCollection, null, false );
	}

	public boolean register(
			final SpimData2 data,
			final Collection< ? extends ViewId > viewCollection,
			final String xmlFileName,
			final boolean saveXML )
	{
		// filter not present ViewIds
		final ArrayList< ViewId > viewIds = new ArrayList<>();
		viewIds.addAll( viewCollection );

		List< ViewId > removed  = SpimData2.filterMissingViews( data, viewIds );
		if ( removed.size() > 0 ) IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		// which timepoints are part of the 
		final List< TimePoint > timepointToProcess = SpimData2.getAllTimePointsSorted( data, viewIds );
		final int nAllTimepoints = data.getSequenceDescription().getTimePoints().size();

		// query basic registration parameters
		final BasicRegistrationParameters brp = basicRegistrationParameters( timepointToProcess, nAllTimepoints, data, viewIds );

		if ( brp == null )
			return false;

		removed = filterRemainingViewIdsAndLabels( viewIds, brp.labelMap, data.getViewInterestPoints().getViewInterestPoints() );
		for ( int i = 0; i < removed.size(); ++i )
			IOFunctions.println( "Removed view " + Group.pvid( removed.get( i ) ) + " as the label '" + brp.labelMap.get( removed.get( i ) ) + "' was not present." );

		// query advanced parameters including global optimization now
		final AdvancedRegistrationParameters arp = advancedRegistrationParameters( brp, timepointToProcess, data, viewIds );

		if ( arp == null )
			return false;

		// identify groups/subsets
		final Set< Group< ViewId > > groups = arp.getGroups( data, viewIds, brp.groupTiles, brp.groupIllums, brp.groupChannels );

		final PairwiseSetup< ViewId > setup = arp.pairwiseSetupInstance( brp.registrationType, viewIds, groups );

		IOFunctions.println( "[TIMING] identifySubsets() START" );
		final long identifySubsetsStart = System.currentTimeMillis();
		identifySubsets( setup, brp.getOverlapDetection( data ) ); // uses brp.overlapType
		IOFunctions.println( "[TIMING] identifySubsets() TOTAL: " + (System.currentTimeMillis() - identifySubsetsStart) + " ms" );

		// query fixed and reference views for mapping back if necessary
		final FixMapBackParameters fmbp = fixMapBackParameters( data.getSequenceDescription(), setup, arp.fixViewsIndex, arp.mapBackIndex, brp.registrationType );

		if ( fmbp == null )
			return false;

		// get the grouping parameters
		final GroupParameters gp = groupingParameters( setup.getSubsets() );

		if ( gp == null )
			return false;

		// run the registration
		if ( !processRegistration(
				setup,
				data.getSequenceDescription().getViewSetups(),
				brp.pwr,
				gp.grouping,
				brp.interestPointOverlapType,
				gp.mergeDistance,
				fmbp.fixedViews,
				fmbp.model,
				fmbp.mapBackViews,
				data.getViewRegistrations().getViewRegistrations(),
				data.getSequenceDescription().getViewDescriptions(),
				data.getViewInterestPoints().getViewInterestPoints(),
				brp.labelMap,
				arp.globalOptParams,
				brp.matchAcrossLabels,
				arp.showStatistics ) )
			return false;

		// record action history
		{
			final java.util.LinkedHashMap<String,String> params = net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.params();
			// collapse the per-view label map to a comma-separated unique label list
			final java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
			if ( brp.labelMap != null )
				for ( final java.util.HashMap<String,Double> m : brp.labelMap.values() )
					if ( m != null ) labels.addAll( m.keySet() );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "label", String.join( ",", labels ) );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "registrationType", brp.registrationType );
			// timepoint-registration sub-parameters only apply to specific registration types
			if ( arp != null && brp.registrationType == net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters.RegistrationType.TO_REFERENCE_TIMEPOINT )
				net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "referenceTP", arp.referenceTimePoint );
			if ( arp != null && brp.registrationType == net.preibisch.mvrecon.fiji.plugin.interestpointregistration.parameters.BasicRegistrationParameters.RegistrationType.ALL_TO_ALL_WITH_RANGE )
				net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "rangeTP", arp.range );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "overlapType", brp.overlapType );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "interestpointsForReg", brp.interestPointOverlapType );
			// matchingMethod (e.g. FAST_TRANSLATION, ICP) is set by pwr.describeParameters() below
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "groupTiles", brp.groupTiles );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "groupIllums", brp.groupIllums );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "groupChannels", brp.groupChannels );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "matchAcrossLabels", brp.matchAcrossLabels );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "interestPointMergeDistance", gp == null ? null : gp.mergeDistance );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "sourcePoints", "IP" );
			// LoadCorrespondencesGUI reuses existing correspondences and skips matching entirely (see
			// the "!LoadCorrespondencesGUI.class.isInstance(pairwiseMatching)" guard around line 429/551
			// below); the Spark translator uses this to emit only "solver", not "match-interestpoints"
			// (which would otherwise recompute matches with no matching method at all).
			final boolean skipMatching = net.preibisch.mvrecon.fiji.plugin.interestpointregistration.pairwise.LoadCorrespondencesGUI.class.isInstance( brp.pwr );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "skipMatching", skipMatching );
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "clearCorrespondences", Boolean.toString( !skipMatching ) );
			if ( arp != null && arp.globalOptParams != null )
			{
				net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "globalOptMethod", arp.globalOptParams.method );
				net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "preAlign", arp.globalOptParams.preAlign ? "PREALIGN" : "NO_PREALIGN" );
				// thresholds are only meaningful for ITERATIVE strategies; SIMPLE/NO_OPTIMIZATION
				// encode them as Double.MAX_VALUE, which would render as a garbage CLI value — skip those
				final double relTh = arp.globalOptParams.relativeThreshold;
				final double absTh = arp.globalOptParams.absoluteThreshold;
				if ( Double.isFinite( relTh ) && relTh < Double.MAX_VALUE )
					net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "relativeThreshold", relTh );
				if ( Double.isFinite( absTh ) && absTh < Double.MAX_VALUE )
					net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.put( params, "absoluteThreshold", absTh );
			}
			// pull matcher-specific params (transformation model, regularization, RANSAC, descriptor params, ICP params, …)
			try
			{
				if ( brp.pwr != null )
					for ( final java.util.Map.Entry<String,String> e : brp.pwr.describeParameters().entrySet() )
						if ( e.getValue() != null )
							params.put( e.getKey(), e.getValue() );
			}
			catch ( final Throwable ignore ) {}
			final String resultRef = labels.isEmpty()
					? "registrations"
					: "interestpoints:" + labels.iterator().next();
			net.preibisch.mvrecon.fiji.spimdata.actionhistory.ActionHistoryRecorder.record(
					data,
					"register-interestpoints",
					Interest_Point_Registration.class.getName(),
					params,
					viewIds,
					resultRef );
		}

		// save the XML including transforms and correspondences
		if ( saveXML )
			new XmlIoSpimData2().saveWithFilename( data, xmlFileName );

		if ( arp.showStatistics )
		{
			final ArrayList< RegistrationStatistics > rsData = new ArrayList< RegistrationStatistics >();
			for ( final TimePoint t : timepointToProcess )
				rsData.add( new RegistrationStatistics( t.getId(), statistics ) );
			TimeLapseDisplay.plotData( data.getSequenceDescription().getTimePoints(), rsData, TimeLapseDisplay.getOptimalTimePoint( rsData ), true );
		}

		return true;
	}

	public < M extends AbstractModel<M> & Affine3D<M>> boolean processRegistration(
			final PairwiseSetup< ViewId > setup,
			final Map< Integer, ? extends BasicViewSetup > viewSetups,
			final PairwiseGUI pairwiseMatching,
			final InterestpointGroupingType groupingType,
			final InterestPointOverlapType interestPointOverlapType,
			final double interestPointMergeDistance,
			final Set< ViewId > viewsToFix,
			final Model< ? > mapBackModel,
			final Map< Subset< ViewId >, Pair< ViewId, Dimensions > > mapBackViews,
			final Map< ViewId, ViewRegistration > registrations,
			final Map< ViewId, ViewDescription > viewDescriptions,
			final Map< ViewId, ViewInterestPointLists > interestpointLists,
			final Map< ViewId, HashMap< String, Double > > labelMap,
			final GlobalOptimizationParameters globalOptParameters,
			final boolean matchAcrossLabels,
			final boolean collectStatistics )
	{
		final List< ViewId > viewIds = setup.getViews();
		final ArrayList< Subset< ViewId > > subsets = setup.getSubsets();

		final long processRegistrationStart = System.currentTimeMillis();

		// load & transform all interest points
		IOFunctions.println( "[TIMING] getAllTransformedInterestPoints() START (" + viewIds.size() + " views)" );
		long start = System.currentTimeMillis();
		final Map< ViewId, HashMap< String, Collection< InterestPoint > > > interestpoints =
				TransformationTools.getAllTransformedInterestPoints(
					viewIds,
					registrations,
					interestpointLists,
					labelMap );
		IOFunctions.println( "[TIMING] getAllTransformedInterestPoints(): " + (System.currentTimeMillis() - start) + " ms (" + viewIds.size() + " views)" );

		// only keep those interestpoints that currently overlap with a view to register against
		if ( interestPointOverlapType == InterestPointOverlapType.OVERLAPPING_ONLY )
		{
			final Set< Group< ViewId > > groups = new HashSet<>();

			if ( groupingType == InterestpointGroupingType.ADD_ALL )
				for ( final Subset< ViewId > subset : subsets )
					groups.addAll( subset.getGroups() );

			start = System.currentTimeMillis();
			TransformationTools.filterForOverlappingInterestPoints( interestpoints, groups, registrations, viewDescriptions );
			IOFunctions.println( "[TIMING] filterForOverlappingInterestPoints(): " + (System.currentTimeMillis() - start) + " ms" );

			IOFunctions.println( "Remaining interest points for alignment: " );
			for ( final Entry< ViewId, HashMap< String, Collection< InterestPoint > > > element: interestpoints.entrySet() )
				for ( final Entry< String, Collection< InterestPoint > > subElement : element.getValue().entrySet() )
					IOFunctions.println( Group.pvid( element.getKey() ) + ", '" + subElement.getKey() + "' : " + subElement.getValue().size() );
		}

		// statistics?
		if ( collectStatistics )
			this.statistics = new ArrayList<>();

		int subsetIdx = 0;
		for ( final Subset< ViewId > subset : subsets )
		{
			subsetIdx++;
			IOFunctions.println( "[TIMING] === Processing subset " + subsetIdx + "/" + subsets.size() + " ===" );

			// fix view(s)
			final List< ViewId > fixedViews = setup.getDefaultFixedViews();
			IOFunctions.println( "By default #fixed views for strategy " + setup.getClass().getSimpleName() + " = " + fixedViews.size() );
			fixedViews.addAll( viewsToFix );
			IOFunctions.println( "Removed " + subset.fixViews( fixedViews ).size() + " views due to fixing all views (in total " + fixedViews.size() + ")" );

			HashMap< ViewId, Tile< M > > models;
			final Collection< Pair< Group< ViewId >, Group< ViewId > > > removedInconsistentPairs = new ArrayList<>();

			if ( groupingType == InterestpointGroupingType.DO_NOT_GROUP )
			{
				// get all pairs to be compared (either that XOR grouped pairs)
				start = System.currentTimeMillis();
				final List< Pair< ViewId, ViewId > > pairs = subset.getPairs();
				IOFunctions.println( "[TIMING] subset.getPairs(): " + (System.currentTimeMillis() - start) + " ms (" + pairs.size() + " pairs)" );

				// Print first 10 pairs as sample
				final int pairsToShow = Math.min( 10, pairs.size() );
				for ( int i = 0; i < pairsToShow; i++ )
				{
					final Pair< ViewId, ViewId > pair = pairs.get( i );
					System.out.println( Group.pvid( pair.getA() ) + " <=> " + Group.pvid( pair.getB() ) );
				}
				if ( pairs.size() > 10 )
					System.out.println( "... and " + (pairs.size() - 10) + " more pairs" );

				// compute all pairwise matchings
				start = System.currentTimeMillis();
				final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > > result =
						MatcherPairwiseTools.computePairs( pairs, interestpoints, pairwiseMatching.pairwiseMatchingInstance(), matchAcrossLabels );
				final long computePairsTime = System.currentTimeMillis() - start;
				IOFunctions.println( "[TIMING] computePairs(): " + computePairsTime + " ms (" + pairs.size() + " pairs, " +
						(pairs.size() > 0 ? String.format("%.2f", (double)computePairsTime / pairs.size()) : "0") + " ms/pair avg)" );

				// clear correspondences
				if ( !LoadCorrespondencesGUI.class.isInstance( pairwiseMatching ) )
				{
					start = System.currentTimeMillis();
					MatcherPairwiseTools.clearCorrespondences( subset.getViews(), interestpointLists, labelMap );
					IOFunctions.println( "[TIMING] clearCorrespondences(): " + (System.currentTimeMillis() - start) + " ms (" + subset.getViews().size() + " views)" );

					// add the corresponding detections and output result
					start = System.currentTimeMillis();
					for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< InterestPoint > > p : result )
					{
						final ViewId vA = p.getA().getA();
						final ViewId vB = p.getA().getB();

						final String labelA = p.getB().getLabelA();
						final String labelB = p.getB().getLabelB();

						final InterestPoints listA = interestpointLists.get( vA ).getInterestPointList( labelA );
						final InterestPoints listB = interestpointLists.get( vB ).getInterestPointList( labelB );

						MatcherPairwiseTools.addCorrespondences( p.getB().getInliers(), p.getB().getInlierSetIds(), vA, vB, labelA, labelB, listA, listB );
					}
					IOFunctions.println( "[TIMING] addCorrespondences(): " + (System.currentTimeMillis() - start) + " ms (" + result.size() + " results)" );
				}

				if ( collectStatistics )
					statistics.addAll( result );

				if ( globalOptParameters.method == GlobalOptType.NO_OPTIMIZATION )
				{
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): DONE matching only (no global optimization)." );
					return true;
				}

				// run global optimization
				final PointMatchCreator pmc = new InterestPointMatchCreator( result, labelMap ); // TODO: Add weights!!!
				final M model = pairwiseMatching.getMatchingModel().getModel();

				IOFunctions.println( "[TIMING] === Starting GlobalOpt (" + globalOptParameters.method + ") ===" );
				IOFunctions.println( "[TIMING] Total time before GlobalOpt: " + (System.currentTimeMillis() - processRegistrationStart) + " ms" );
				start = System.currentTimeMillis();

				if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_SIMPLE )
				{
					final ConvergenceStrategy cs = new ConvergenceStrategy( Double.isNaN( globalOptParameters.maxError ) ? pairwiseMatching.globalOptError() : globalOptParameters.maxError, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth );

					models = GlobalOpt.computeTiles(
									model,
									globalOptParameters.preAlign,
									pmc,
									cs,
									fixedViews,
									subset.getGroups() );
				}
				else if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_ITERATIVE )
				{
					models = GlobalOptIterative.computeTiles(
									model,
									globalOptParameters.preAlign,
									pmc,
									new SimpleIterativeConvergenceStrategy( Double.MAX_VALUE, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth, globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ),
									new MaxErrorLinkRemoval(),
									removedInconsistentPairs,
									fixedViews,
									subset.getGroups() );
				}
				else //if ( globalOptParameters.method == GlobalOptType.TWO_ROUND_SIMPLE || globalOptParameters.method == GlobalOptType.TWO_ROUND_ITERATIVE )
				{
					models = GlobalOptTwoRound.computeTiles(
							model,
							globalOptParameters.preAlign,
							pmc,
							new SimpleIterativeConvergenceStrategy( Double.MAX_VALUE, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth, globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ), // if it's simple, both will be Double.MAX
							new MaxErrorLinkRemoval(),
							removedInconsistentPairs,
							new MetaDataWeakLinkFactory(
									registrations,
									new SimpleBoundingBoxOverlap<>( viewSetups, registrations ) ),
							new ConvergenceStrategy( Double.MAX_VALUE, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth ),
							fixedViews,
							subset.getGroups() );
				}
			}
			else
			{
				// test grouped registration
				final List< Pair< Group< ViewId >, Group< ViewId > > > groupedPairs = subset.getGroupedPairs();
				final Map< Group< ViewId >, HashMap< String, Collection< GroupedInterestPoint< ViewId > > > > groupedInterestpoints = new HashMap<>();
				
				final double maxError = interestPointMergeDistance;
				final InterestPointGroupingMinDistance< ViewId > ipGrouping;

				if ( Double.isNaN( maxError ) )
					ipGrouping = new InterestPointGroupingMinDistance<>( interestpoints );
				else
					ipGrouping = new InterestPointGroupingMinDistance<>( maxError, interestpoints );

				IOFunctions.println( "Using a maximum radius of " + ipGrouping.getRadius() + " to filter interest points from overlapping views." );

				// which groups exist
				final Set< Group< ViewId > > groups = new HashSet<>();

				for ( final Pair< Group< ViewId >, Group< ViewId > > pair : groupedPairs )
				{
					groups.add( pair.getA() );
					groups.add( pair.getB() );

					if ( !groupedInterestpoints.containsKey( pair.getA() ) )
					{
						groupedInterestpoints.put( pair.getA(), ipGrouping.group( pair.getA() ) );
						IOFunctions.println( "Grouping interestpoints for " + pair.getA() + " (" + ipGrouping.countBefore() + " >>> " + ipGrouping.countAfter() + ")" );
					}

					if ( !groupedInterestpoints.containsKey( pair.getB() ) )
					{
						groupedInterestpoints.put( pair.getB(), ipGrouping.group( pair.getB() ) );
						IOFunctions.println( "Grouping interestpoints for " + pair.getB() + " (" + ipGrouping.countBefore() + " >>> " + ipGrouping.countAfter() + ")" );
					}
				}

				final List< Pair< Pair< Group< ViewId >, Group< ViewId > >, PairwiseResult< GroupedInterestPoint< ViewId > > > > resultGroup =
						MatcherPairwiseTools.computePairs( groupedPairs, groupedInterestpoints, pairwiseMatching.pairwiseGroupedMatchingInstance(), matchAcrossLabels );

				if ( LoadCorrespondencesGUI.class.isInstance( pairwiseMatching ) )
					throw new RuntimeException( "we need to implement that correspondences are not cleared and stored when loading them below." );

				// clear correspondences and get a map linking ViewIds to the correspondence lists
				final Map< ViewId, HashMap< String, List< CorrespondingInterestPoints > > > cMap =
					MatcherPairwiseTools.clearCorrespondences( subset.getViews(), interestpointLists, labelMap );

				// add the corresponding detections and transform HashMap< Pair< Group < V >, Group< V > >, PairwiseResult > to HashMap< Pair< V, V >, PairwiseResult >
				final List< Pair< Pair< ViewId, ViewId >, PairwiseResult< GroupedInterestPoint< ViewId > > > > resultTransformed =
						MatcherPairwiseTools.addCorrespondencesFromGroups( resultGroup, interestpointLists, cMap );

				if ( collectStatistics )
					for ( final Pair< Pair< ViewId, ViewId >, PairwiseResult< GroupedInterestPoint< ViewId > > > p : resultTransformed )
					{
						System.out.println( Group.pvid( p.getA().getA() ) + " " + Group.pvid( p.getA().getB() ) + ": " + p.getB().getInliers().size() +"/" + p.getB().getCandidates().size() + " with " + p.getB().getError() + " px." );
						statistics.add( p );
					}

				if ( globalOptParameters.method == GlobalOptType.NO_OPTIMIZATION )
				{
					IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): DONE matching only (no global optimization)." );
					return true;
				}

				// run global optimization
				final PointMatchCreator pmc = new InterestPointMatchCreator( resultTransformed, labelMap );
				final M model = pairwiseMatching.getMatchingModel().getModel();

				//models = (HashMap< ViewId, Tile< ? extends AbstractModel< ? > > >)(Object)GlobalOpt.compute( pairwiseMatching.getMatchingModel().getModel(), pmc, cs, fixedViews, groups );

				if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_SIMPLE )
				{
					final ConvergenceStrategy cs = new ConvergenceStrategy( Double.isNaN( globalOptParameters.maxError ) ? pairwiseMatching.globalOptError() : globalOptParameters.maxError, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth );

					models = GlobalOpt.computeTiles(
									model,
									globalOptParameters.preAlign,
									pmc,
									cs,
									fixedViews,
									groups );
				}
				else if ( globalOptParameters.method == GlobalOptType.ONE_ROUND_ITERATIVE )
				{
					models = GlobalOptIterative.computeTiles(
									model,
									globalOptParameters.preAlign,
									pmc,
									new SimpleIterativeConvergenceStrategy( Double.MAX_VALUE, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth, globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ),
									new MaxErrorLinkRemoval(),
									removedInconsistentPairs,
									fixedViews,
									groups );
				}
				else //if ( globalOptParameters.method == GlobalOptType.TWO_ROUND_SIMPLE || globalOptParameters.method == GlobalOptType.TWO_ROUND_ITERATIVE )
				{
					// TODO: returns HashMap< ViewId, AffineModel3D>????
					models = GlobalOptTwoRound.computeTiles(
							model,
							globalOptParameters.preAlign,
							pmc,
							new SimpleIterativeConvergenceStrategy( Double.MAX_VALUE, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth, globalOptParameters.relativeThreshold, globalOptParameters.absoluteThreshold ), // if it's simple, both will be Double.MAX
							new MaxErrorLinkRemoval(),
							removedInconsistentPairs,
							new MetaDataWeakLinkFactory(
									registrations,
									new SimpleBoundingBoxOverlap<>( viewSetups, registrations ) ),
							new ConvergenceStrategy( Double.MAX_VALUE, globalOptParameters.maxIterations, globalOptParameters.maxPlateauWidth ),
							fixedViews,
							groups );
				}
			}

			AffineTransform3D mapBack = null;

			// global opt failed
			if ( models == null || models.keySet().size() == 0 )
			{
				models = new HashMap<>();

				for ( final ViewId viewId : subset.getViews() )
					models.put( viewId, new Tile< M >( pairwiseMatching.getMatchingModel().getModel() ) );

				IOFunctions.println( "No transformations could be found, setting all models to identity transformation." );
			}
			else
			{
				if ( mapBackModel != null )
				{
					final ViewId mapBackView = mapBackViews.get( subset ).getA();
					mapBack = TransformationTools.computeMapBackModel(
							mapBackViews.get( subset ).getB(),
							registrations.get( mapBackView ).getModel(),
							models.get( mapBackView ).getModel(),
							mapBackModel );

					IOFunctions.println( "Mapback model: " + mapBack );
				}
			}

			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Final transformation models (without mapback model):" );

			// TODO: sort by time and viewid
			// pre-concatenate models to spimdata2 viewregistrations (from SpimData(2))
			for ( final ViewId viewId : subset.getViews() )
			{
				final Tile< ? extends AbstractModel< ? > > tile = models.get( viewId );
				final ViewRegistration vr = registrations.get( viewId );

				TransformationTools.storeTransformation( vr, viewId, tile, mapBack, pairwiseMatching.getMatchingModel().getDescription() );
			}

			// print per-view transformations + identity-vs-non-identity summary,
			// gated by TransformationTools.maxPerViewTransformLog
			TransformationTools.printAndSummarizeTransformations( subset.getViews(), models );
		}

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): DONE." );

		return true;
	}

	// filtering viewIds and labelMap
	public List< ViewId > filterRemainingViewIdsAndLabels( final List< ViewId > viewIds, final Map< ViewId, HashMap< String, Double > > labelMap, final Map< ViewId, ViewInterestPointLists > interestpointLists )
	{
		final HashSet< ViewId > keep = new HashSet<>();
		final Map< ViewId, HashMap< String, Double > > labelMapKeep = new HashMap<>();

		labelMap.forEach( (viewId, labelAndWeight) -> labelAndWeight.forEach( (label, weight) ->
		{
			// does it exist for this viewId?
			final ViewInterestPointLists lists = interestpointLists.get( viewId );

			if ( lists.getHashMap().keySet().contains( label ) && lists.getHashMap().get( label ) != null )
			{
				keep.add( viewId );

				if ( !labelMapKeep.containsKey( viewId ) )
					labelMapKeep.put( viewId, new HashMap<>() );

				labelMapKeep.get( viewId ).put( label, weight );
			}
		} ) );

		final List< ViewId > remove =
				viewIds.stream().filter( viewId -> !keep.contains( viewId ) ).collect( Collectors.toList() );

		/*
		final List< ViewId > remove = new ArrayList<>();
		viewIds.forEach( viewId -> {
			if ( !keep.contains( viewId ) )
				remove.add( viewId );
		} );
		*/

		/*
		for ( final ViewId viewId : viewIds )
		{
			for ( final Entry< String, Double > labelAndWeight : labelMap.get( viewId ).entrySet() )
			{
				final String label = labelMap.get( viewId );
		
				// does it exist for this viewId?
				final ViewInterestPointLists lists = interestpointLists.get( viewId );
	
				if ( lists.getHashMap().keySet().contains( label ) && lists.getHashMap().get( label ) != null )
					keep.add( viewId );
				else
					remove.add( viewId );
			}
		}
		*/

		viewIds.clear();
		viewIds.addAll( keep );

		labelMap.clear();
		labelMap.putAll( labelMapKeep );

		return remove;
	}

	@SuppressWarnings("unchecked")
	public void identifySubsets( final PairwiseSetup< ViewId > setup, final OverlapDetection< ViewId > overlapDetection )
	{
		long start;

		// Use combined optimized approach for SimpleBoundingBoxOverlap (avoids creating millions of pairs just to discard them)
		if (overlapDetection instanceof SimpleBoundingBoxOverlap)
		{
			start = System.currentTimeMillis();
			final SimpleBoundingBoxOverlap<ViewId> sbbo = (SimpleBoundingBoxOverlap<ViewId>) overlapDetection;

			// Generate only overlapping pairs directly (combines definePairs + removeNonOverlappingPairs)
			final List<Pair<ViewId, ViewId>> overlappingPairs = sbbo.generateOverlappingPairs(
					setup.getViews(),
					(Collection<? extends Group<ViewId>>) (Collection<?>) setup.getGroups());

			// Set the pairs directly in the setup
			setup.setPairs(overlappingPairs);

			// Calculate statistics: total possible pairs minus overlapping = non-overlapping (skipped)
			final long n = setup.getViews().size();
			final long totalPossiblePairs = n * (n - 1) / 2;
			final long nonOverlapping = totalPossiblePairs - overlappingPairs.size();

			IOFunctions.println( "[TIMING]   definePairs+removeNonOverlapping (combined): " + (System.currentTimeMillis() - start) +
					" ms (removed " + nonOverlapping + " non-overlapping, Strategy='SimpleBoundingBoxOverlap', kept " + overlappingPairs.size() + " pairs)" );
		}
		else
		{
			// Fallback to sequential approach for other OverlapDetection types
			start = System.currentTimeMillis();
			final int removedRedundant = setup.definePairs().size();
			IOFunctions.println( "[TIMING]   definePairs(): " + (System.currentTimeMillis() - start) + " ms (removed " + removedRedundant + " redundant view pairs)" );

			start = System.currentTimeMillis();
			final int removedNonOverlapping = setup.removeNonOverlappingPairs( overlapDetection ).size();
			IOFunctions.println( "[TIMING]   removeNonOverlappingPairs(): " + (System.currentTimeMillis() - start) + " ms (removed " + removedNonOverlapping + " non-overlapping, Strategy='" + overlapDetection.getClass().getSimpleName() + "')" );
		}

		start = System.currentTimeMillis();
		setup.reorderPairs();
		IOFunctions.println( "[TIMING]   reorderPairs(): " + (System.currentTimeMillis() - start) + " ms" );

		start = System.currentTimeMillis();
		setup.detectSubsets();
		IOFunctions.println( "[TIMING]   detectSubsets(): " + (System.currentTimeMillis() - start) + " ms" );

		start = System.currentTimeMillis();
		setup.sortSubsets();
		IOFunctions.println( "[TIMING]   sortSubsets(): " + (System.currentTimeMillis() - start) + " ms" );

		IOFunctions.println( "Identified " + setup.getSubsets().size() + " subsets " );
	}

	public AdvancedRegistrationParameters advancedRegistrationParameters(
			final BasicRegistrationParameters brp,
			final List< TimePoint > timepointToProcess,
			final SpimData2 data,
			final List< ViewId > viewIds )
	{
		final GenericDialog gd = new GenericDialog( "Register: " + BasicRegistrationParameters.registrationTypeChoices[ brp.registrationType.ordinal() ] );

		if ( brp.registrationType == RegistrationType.TO_REFERENCE_TIMEPOINT )
		{
			// assemble all timepoints, each one could be a reference
			final String[] tpList = assembleTimepoints( data.getSequenceDescription().getTimePoints() );
			
			// by default, the reference timepoint is the first one
			if ( defaultReferenceTimepointIndex < 0 || defaultReferenceTimepointIndex >= tpList.length )
				defaultReferenceTimepointIndex = 0;

			gd.addChoice( "Reference timepoint", tpList, tpList[ defaultReferenceTimepointIndex ] );
			gd.addMessage( "" );
		}
		else if ( brp.registrationType == RegistrationType.ALL_TO_ALL_WITH_RANGE )
		{
			gd.addSlider( "Range for all-to-all timepoint matching", 2, 10, defaultRange );
		}

		// for all registrations that include multiple timepointss
		if ( brp.registrationType != RegistrationType.TIMEPOINTS_INDIVIDUALLY )
		{
			gd.addCheckbox( "Consider_each_timepoint_as_rigid_unit", defaultGroupTimePoints );
			gd.addMessage( "Note: This option applies the same transformation model to all views of one timepoint. This makes for example\n" +
					"sense if all timepoints are individually pre-registered using an affine transformation model, and for the timeseries\n" +
					"stabilization a translation model should be used.\n ", GUIHelper.smallStatusFont );
		}

		// whenever it is not a registration to a reference timepoint choose potentially fixed views
		// (otherwise all views of the reference are fixed)
		if ( brp.registrationType != RegistrationType.TO_REFERENCE_TIMEPOINT )
		{
			gd.addChoice( "Fix_views", FixMapBackParameters.fixViewsChoice, FixMapBackParameters.fixViewsChoice[ defaultFixViews ] );
			gd.addChoice( "Map_back_views", FixMapBackParameters.mapBackChoice, FixMapBackParameters.mapBackChoice[ defaultMapBack ] );
		}

		gd.addMessage( "" );
		gd.addMessage( "Algorithm parameters [" + brp.pwr.getDescription() + "]", new Font( Font.SANS_SERIF, Font.BOLD, 12 ) );
		gd.addMessage( "" );

		brp.pwr.addQuery( gd );

		GlobalOptimizationParameters.addSimpleParametersToDialog( gd );

		if ( timepointToProcess.size() > 1 )
			gd.addCheckbox( "Show_timeseries_statistics", defaultShowStatistics );

		// display the dialog
		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final AdvancedRegistrationParameters arp = new AdvancedRegistrationParameters();

		// assign default numbers even if not necessary
		arp.referenceTimePoint = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( 0 ).getId();
		arp.range = defaultRange;

		if ( brp.registrationType == RegistrationType.TO_REFERENCE_TIMEPOINT )
		{
			arp.referenceTimePoint = data.getSequenceDescription().getTimePoints().getTimePointsOrdered().get( defaultReferenceTimepointIndex = gd.getNextChoiceIndex() ).getId();

			// check that at least one of the views of the reference timepoint is part of the viewdescriptions
			boolean contains = false;

			for ( final ViewId viewId : viewIds )
				if ( viewId.getTimePointId() == arp.referenceTimePoint )
					contains = true;

			if ( !contains )
			{
				IOFunctions.println( "No views of the reference timepoint are part of the registration." );
				IOFunctions.println( "Please re-run and select the corresponding views that should be used as reference." );

				return null;
			}
		}

		if ( brp.registrationType == RegistrationType.ALL_TO_ALL_WITH_RANGE )
			arp.range = defaultRange = (int)Math.round( gd.getNextNumber() );

		if ( brp.registrationType != RegistrationType.TIMEPOINTS_INDIVIDUALLY )
			arp.groupTimePoints = defaultGroupTimePoints = gd.getNextBoolean();
		else
			arp.groupTimePoints = false;

		if ( brp.registrationType != RegistrationType.TO_REFERENCE_TIMEPOINT )
		{
			arp.fixViewsIndex = defaultFixViews = gd.getNextChoiceIndex();
			arp.mapBackIndex = defaultMapBack = gd.getNextChoiceIndex();
		}
		else
		{
			arp.fixViewsIndex = arp.mapBackIndex = -1;
		}

		if ( !brp.pwr.parseDialog( gd ) )
			return null;

		arp.globalOptParams = GlobalOptimizationParameters.parseSimpleParametersFromDialog( gd );

		// honor a cancel of the "Show full options dialog" (parse returns null on cancel)
		if ( arp.globalOptParams == null )
			return null;

		if ( timepointToProcess.size() > 1 )
			defaultShowStatistics = arp.showStatistics = gd.getNextBoolean();
		else
			arp.showStatistics = false;

		return arp;
	}

	public BasicRegistrationParameters basicRegistrationParameters(
			final List< TimePoint > timepointToProcess,
			final int nAllTimepoints,
			final SpimData2 data,
			final List< ViewId > viewIds )
	{
		final GenericDialog gd = new GenericDialog( "Basic Registration Parameters" );

		// the GenericDialog needs a list[] of String for the algorithms that can register
		final String[] descriptions = new String[ ( staticPairwiseAlgorithms.size() ) ];

		for ( int i = 0; i < descriptions.length; ++i )
			descriptions[ i ] = staticPairwiseAlgorithms.get( i ).getDescription();
		
		if ( defaultAlgorithm >= descriptions.length )
			defaultAlgorithm = 0;

		gd.addChoice( "Registration_algorithm", descriptions, descriptions[ defaultAlgorithm ] );

		if ( timepointToProcess.size() > 1 )
		{
			if ( defaultRegistrationType >= BasicRegistrationParameters.registrationTypeChoices.length )
				defaultRegistrationType = 0;
	
			gd.addChoice( "Registration_over_time", BasicRegistrationParameters.registrationTypeChoices, BasicRegistrationParameters.registrationTypeChoices[ defaultRegistrationType ] );
		}

		gd.addChoice( "Registration_in_between_views", BasicRegistrationParameters.overlapChoices, BasicRegistrationParameters.overlapChoices[ defaultOverlapType ] );
		gd.addChoice( "Interest_point_inclusion", BasicRegistrationParameters.interestpointOverlapChoices, BasicRegistrationParameters.interestpointOverlapChoices[ defaultInterestpointOverlapType ] );

		// check which channels and labels are available and build the choices
		final String[] labelsRaw = InterestPointTools.getAllInterestPointLabels( data, viewIds );

		if ( labelsRaw.length == 0 )
		{
			IOFunctions.printErr( "No interest points available, stopping. Please run Interest Point Detection first" );
			return null;
		}

		final String[] labels = new String[ labelsRaw.length + 1 ];
		for ( int i = 0; i < labelsRaw.length; ++i )
			labels[ i ] = labelsRaw[ i ];
		labels[ labelsRaw.length ] = "Select multiple interestpoints [extra dialog]";

		// choose the first label that is complete if possible
		if ( defaultLabel < 0 || defaultLabel >= labels.length )
		{
			defaultLabel = -1;

			for ( int i = 0; i < labels.length; ++i )
				if ( !labels[ i ].contains( InterestPointTools.warningLabel ) && !labels[ i ].startsWith( "Select multiple interestpoints") )
				{
					defaultLabel = i;
					break;
				}

			if ( defaultLabel == -1 )
				defaultLabel = 0;
		}

		gd.addChoice( "Interest_points" , labels, labels[ defaultLabel ] );

		final HashSet< Integer > tiles = new HashSet<>();
		for ( final ViewId viewId : viewIds )
			tiles.add( data.getSequenceDescription().getViewDescription( viewId ).getViewSetup().getTile().getId() );

		final HashSet< Integer > illums = new HashSet<>();
		for ( final ViewId viewId : viewIds )
			illums.add( data.getSequenceDescription().getViewDescription( viewId ).getViewSetup().getIllumination().getId() );

		final HashSet< Integer > channels = new HashSet<>();
		for ( final ViewId viewId : viewIds )
			channels.add( data.getSequenceDescription().getViewDescription( viewId ).getViewSetup().getChannel().getId() );

		if ( tiles.size() > 1 )
			gd.addCheckbox( "Group_tiles", defaultGroupTiles );

		if ( illums.size() > 1 )
			gd.addCheckbox( "Group_illuminations", defaultGroupIllums );

		if ( channels.size() > 1 )
			gd.addCheckbox( "Group_channels", defaultGroupChannels );

		// assemble the last registration names of all viewsetups involved
		final HashMap< String, Integer > names = GUIHelper.assembleRegistrationNames( data, viewIds );
		gd.addMessage( "" );
		GUIHelper.displayRegistrationNames( gd, names );
		gd.addMessage( "" );

		GUIHelper.addWebsite( gd );

		if ( names.keySet().size() > 5 )
			GUIHelper.addScrollBars( gd );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;

		// which pairwise algorithm
		final int algorithm = defaultAlgorithm = gd.getNextChoiceIndex();

		// time registration
		final RegistrationType registrationType;

		if ( timepointToProcess.size() > 1 )
		{
			switch ( defaultRegistrationType = gd.getNextChoiceIndex() )
			{
				case 0:
					registrationType = RegistrationType.TIMEPOINTS_INDIVIDUALLY;
					break;
				case 1:
					registrationType = RegistrationType.TO_REFERENCE_TIMEPOINT;
					break;
				case 2:
					registrationType = RegistrationType.ALL_TO_ALL;
					break;
				case 3:
					registrationType = RegistrationType.ALL_TO_ALL_WITH_RANGE;
					break;
				default:
					return null;
			}
		}
		else
		{
			registrationType = RegistrationType.TIMEPOINTS_INDIVIDUALLY;
		}

		// view registration
		final OverlapType overlapType;
		switch ( defaultOverlapType = gd.getNextChoiceIndex() )
		{
			case 0:
				overlapType = OverlapType.ALL_AGAINST_ALL;
				break;
			case 1:
				overlapType = OverlapType.OVERLAPPING_ONLY;
				break;
			default:
				return null;
		}

		// interest point overlap
		final InterestPointOverlapType interestPointOverlapType;
		switch ( defaultInterestpointOverlapType = gd.getNextChoiceIndex() )
		{
			case 0:
				interestPointOverlapType = InterestPointOverlapType.ALL;
				break;
			case 1:
				interestPointOverlapType = InterestPointOverlapType.OVERLAPPING_ONLY;
				break;
			default:
				return null;
		}

		// assemble which label has been selected
		final int labelChoice = defaultLabel = gd.getNextChoiceIndex();
		final HashMap<String, Double > labelAndWeight = new HashMap<>();

		boolean matchAcrossLabels = false;

		if ( labelChoice < labels.length - 1 )
		{
			labelAndWeight.put( InterestPointTools.getSelectedLabel( labels, labelChoice ), 1.0 );
		}
		else
		{
			/*
			final GenericDialog gdLabel1 = new GenericDialog( "Select multiple labels" );

			if ( defaultLabelChoice == null || defaultLabelChoice.length != labels.length - 1 )
				defaultLabelChoice = new boolean[ labels.length - 1 ];

			for ( int i = 0; i < labels.length - 1; ++i )
				gdLabel1.addCheckbox( labels[ i ], defaultLabelChoice[ i ] );

			gdLabel1.showDialog();
			if ( gdLabel1.wasCanceled() )
				return null;

			final ArrayList< String > labelChoices = new ArrayList<>();
			for ( int i = 0; i < labels.length - 1; ++i )
				if ( defaultLabelChoice[ i ] = gdLabel1.getNextBoolean() )
					labelChoices.add( InterestPointTools.getSelectedLabel( labels, i ) );

			if ( labelChoices.size() == 0 )
			{
				IOFunctions.println( "No interestpoints selected, stopping.");
				return null;
			}
			*/

			final ArrayList< String > labelChoices = multipleInterestPointsGUI( labels );

			final GenericDialog gdLabel2 = new GenericDialog( "Select weights and other options" );

			if ( defaultLabelWeights == null || defaultLabelWeights.length != labelChoices.size() )
			{
				defaultLabelWeights = new double[ labelChoices.size() ];
				Arrays.setAll( defaultLabelWeights, d -> 1.0 );
			}
			
			gdLabel2.addCheckbox( "Match_different_labels" , defaultMatchAcrossLabels );
			gdLabel2.addMessage( "(if 'not selected' only labels with the same name will be matched with each other.)", GUIHelper.smallStatusFont);
			gdLabel2.addMessage( "" );
			gdLabel2.addMessage( "Weights for interest point labels:", GUIHelper.largefont );
			gdLabel2.addMessage( "Note: to give a very low weight to a label we suggest w = 1.0E-6 (0.000001)", GUIHelper.smallStatusFont);

			for ( int i = 0; i < labelChoices.size(); ++i )
				gdLabel2.addNumericField( labelChoices.get( i ) + " w=", defaultLabelWeights[ i ], 2 );

			gdLabel2.showDialog();
			if ( gdLabel2.wasCanceled() )
				return null;

			matchAcrossLabels = defaultMatchAcrossLabels = gdLabel2.getNextBoolean();

			for ( int i = 0; i < labelChoices.size(); ++i )
			{
				labelAndWeight.put( labelChoices.get( i ), defaultLabelWeights[ i ] = gdLabel2.getNextNumber() );
				IOFunctions.println( labelChoices.get( i ) + ", weight=" + labelAndWeight.get( labelChoices.get( i ) ) );
			}
		}

		boolean groupTiles = false;
		if ( tiles.size() > 1 )
			groupTiles = defaultGroupTiles = gd.getNextBoolean();

		boolean groupIllums = false;
		if ( illums.size() > 1 )
			groupIllums = defaultGroupIllums = gd.getNextBoolean();

		boolean groupChannels = false;
		if ( channels.size() > 1 )
			groupChannels = defaultGroupChannels = gd.getNextBoolean();

		final PairwiseGUI pwr = staticPairwiseAlgorithms.get( algorithm ).newInstance( data );

		IOFunctions.println( "Registration algorithm: " + pwr.getDescription() );
		IOFunctions.println( "Registration type: " + registrationType.name() );

		final BasicRegistrationParameters brp = new BasicRegistrationParameters();
		brp.pwr = pwr;
		brp.registrationType = registrationType;
		brp.overlapType = overlapType;
		brp.interestPointOverlapType = interestPointOverlapType;
		brp.labelMap = new HashMap<>();
		brp.groupTiles = groupTiles;
		brp.groupIllums = groupIllums;
		brp.groupChannels = groupChannels;
		brp.matchAcrossLabels = matchAcrossLabels;

		for ( final ViewId viewId : viewIds )
			brp.labelMap.put( viewId, labelAndWeight );

		return brp;
	}

	public static ArrayList< String > multipleInterestPointsGUI( final String[] labels )
	{
		final GenericDialog gdLabel1 = new GenericDialog( "Select multiple labels" );

		if ( defaultLabelChoice == null || defaultLabelChoice.length != labels.length - 1 )
			defaultLabelChoice = new boolean[ labels.length - 1 ];

		for ( int i = 0; i < labels.length - 1; ++i )
			gdLabel1.addCheckbox( labels[ i ], defaultLabelChoice[ i ] );

		gdLabel1.showDialog();
		if ( gdLabel1.wasCanceled() )
			return null;

		final ArrayList< String > labelChoices = new ArrayList<>();
		for ( int i = 0; i < labels.length - 1; ++i )
			if ( defaultLabelChoice[ i ] = gdLabel1.getNextBoolean() )
				labelChoices.add( InterestPointTools.getSelectedLabel( labels, i ) );

		if ( labelChoices.size() == 0 )
		{
			IOFunctions.println( "No interestpoints selected, stopping.");
			return null;
		}

		return labelChoices;
	}

	public GroupParameters groupingParameters( final Collection< Subset< ViewId > > subsets  )
	{
		final GroupParameters gp = new GroupParameters();

		//
		// ask for what to do with groups
		//
		if ( hasGroups( subsets ) )
		{
			IOFunctions.println( "Registration configuration has groups." );

			final GenericDialog gd = new GenericDialog( "Select interest point grouping" );
			gd.addChoice( "Interestpoint_Grouping" , GroupParameters.ipGroupChoice, GroupParameters.ipGroupChoice[ defaultIPGrouping ] );
			gd.addSlider( "Interest point merge distance", 0.0, 100.0, defaultIPDistance );

			gd.showDialog();
			if ( gd.wasCanceled() )
				return null;

			final int group = defaultIPGrouping = gd.getNextChoiceIndex();
			gp.mergeDistance = defaultIPDistance = gd.getNextNumber();
	
			if ( group == 0 )
				gp.grouping = InterestpointGroupingType.DO_NOT_GROUP;
			else
				gp.grouping = InterestpointGroupingType.ADD_ALL;
		}
		else
		{
			IOFunctions.println( "Registration configuration has no groups." );
			gp.grouping = InterestpointGroupingType.DO_NOT_GROUP;
		}

		IOFunctions.println( "Interestpoint grouping type: " + gp.grouping );
		IOFunctions.println( "Interestpoint merge distance: " + gp.mergeDistance );

		return gp;
	}

	/*
	 * Assign the right fixed views and reference views for this type of optimization
	 *
	 * @param sd - the sequencedescription to fetch the image dimensions of views
	 * @param subsets - which subsets exist
	 * @param fixViewsIndex - "Fix first views", "Select fixed view", "Do not fix views"
	 * @param mapBackIndex - 
	 * 				"Do not map back (use this if views are fixed)",
	 * 				"Map back to first view using translation model",
	 * 				"Map back to first view using rigid model",
	 * 				"Map back to user defined view using translation model",
	 * 				"Map back to user defined view using rigid model"
	 * @param type - the type of registration used
	 */
	public FixMapBackParameters fixMapBackParameters(
			final SequenceDescription sd,
			final PairwiseSetup< ViewId > setup,
			final int fixViewsIndex,
			final int mapBackIndex,
			final RegistrationType type )
	{
		final List< Subset< ViewId > > subsets = setup.getSubsets();
		final FixMapBackParameters fmbp = new FixMapBackParameters();

		//
		// define fixed views
		//
		fmbp.fixedViews = new HashSet< ViewId >();
		fmbp.mapBackViews = new HashMap< Subset< ViewId >, Pair< ViewId, Dimensions > >();

		if ( type == RegistrationType.TO_REFERENCE_TIMEPOINT )
		{
			fmbp.model = null;
			return fmbp;
		}

		if ( fixViewsIndex == 0 ) // all first views
		{
			for ( final Subset< ViewId > subset : subsets )
			{
				if ( subset.getViews().size() == 0 )
					IOFunctions.println( "Nothing to do for: " + subset + ". No views fixed." );
				else
					fmbp.fixedViews.add( Subset.getViewsSorted( subset.getViews() ).get( 0 ) );
			}
		}
		else if ( fixViewsIndex == 1 )
		{
			// TODO: select fixed views (this assumes that one subset consists of one timepoint)
			if ( subsets.size() > 1 )
			{
				final GenericDialog gd1 = new GenericDialog( "Type of manual choice" );
				gd1.addCheckbox( "Same_fixed_view(s) for each timepoint", defaultSameFixedViews );

				gd1.showDialog();
				if ( gd1.wasCanceled() )
					return null;

				if ( defaultSameFixedViews = gd1.getNextBoolean() )
				{
					// check which viewsetups are available in all subsets
					final ArrayList< Integer > setupList = getListOfViewSetupIdsPresentInAllSubsets( subsets );

					if ( setupList.size() == 0 )
					{
						IOFunctions.println( "No Viewsetup is available in all Timepoints." );
						return null;
					}

					if ( defaultFixedViews == null || defaultFixedViews.length != setupList.size() )
						defaultFixedViews = new boolean[ setupList.size() ];

					final GenericDialog gd2 = new GenericDialog( "Select ViewSetups to be fixed for each of the timepoints" );

					for ( int i = 0; i < setupList.size(); ++i )
						gd2.addCheckbox( "ViewSetupId_" + setupList.get( i ), defaultFixedViews[ i ] );

					GUIHelper.addScrollBars( gd2 );

					gd2.showDialog();
					if ( gd2.wasCanceled() )
						return null;

					for ( int i = 0; i < setupList.size(); ++i )
						if ( defaultFixedViews[ i ] = gd2.getNextBoolean() )
							for ( final Subset< ViewId > subset : subsets )
								fmbp.fixedViews.add( new ViewId( subset.getViews().iterator().next().getTimePointId(), setupList.get( i ) ) );
				}
				else
				{
					for ( final Subset< ViewId > subset : subsets )
						if ( !askForFixedViews( subset, fmbp.fixedViews, "Select fixed ViewIds for timepoint " + subset.getViews().iterator().next().getTimePointId() ) )
							return null;
				}
			}
			else
			{
				// there is just one subset
				if ( !askForFixedViews( subsets.get( 0 ), fmbp.fixedViews, "Select fixed ViewIds" ) )
					return null;
			}
		}
		else
		{
			// no fixed views or reference timepoint
		}

		IOFunctions.println( "Following views are fixed:" );
		for ( final ViewId id : fmbp.fixedViews )
			IOFunctions.println( "ViewSetupId:" + id.getViewSetupId() + " TimePoint:" + id.getTimePointId() );

		//
		// now the reference view(s)
		//
		if ( mapBackIndex == 0 )
			fmbp.model = null;
		else if ( mapBackIndex == 1 || mapBackIndex == 3 )
			fmbp.model = new TranslationModel3D();
		else
			fmbp.model = new RigidModel3D();

		if ( mapBackIndex == 1 || mapBackIndex == 2 )
		{
			for ( final Subset< ViewId > subset : subsets )
			{
				final ViewId mapBackView = Subset.getViewsSorted( subset.getViews() ).get( 0 );
				final Dimensions mapBackViewDims = sd.getViewDescription( mapBackView ).getViewSetup().getSize();
				fmbp.mapBackViews.put( subset, new ValuePair< ViewId, Dimensions >( mapBackView, mapBackViewDims ) );
			}
		}
		else if ( mapBackIndex == 3 || mapBackIndex == 4 )
		{
			// select reference view
			// select fixed views (this assumes that one subset consists of one timepoint)
			if ( subsets.size() > 1 )
			{
				final GenericDialog gd1 = new GenericDialog( "Type of manual choice" );
				gd1.addCheckbox( "Same_reference_view(s) for each timepoint", defaultSameReferenceView );

				gd1.showDialog();
				if ( gd1.wasCanceled() )
					return null;

				if ( defaultSameReferenceView = gd1.getNextBoolean() )
				{
					// check which viewsetups are available in all subsets
					final ArrayList< Integer > setupList = getListOfViewSetupIdsPresentInAllSubsets( subsets );

					if ( setupList.size() == 0 )
					{
						IOFunctions.println( "No Viewsetup is available in all Timepoints." );
						return null;
					}

					final GenericDialog gd2 = new GenericDialog( "Select Reference ViewSetup each of the timepoints" );

					final String[] choices = new String[ setupList.size() ];
					
					for ( int i = 0; i < setupList.size(); ++ i )
						choices[ i ] = "ViewSetupId_" + setupList.get( i );

					if ( defaultReferenceView >= choices.length )
						defaultReferenceView = 0;

					gd2.addChoice( "Select_Reference_ViewSetup", choices, choices[ defaultReferenceView ] );

					gd2.showDialog();
					if ( gd2.wasCanceled() )
						return null;

					final int index = defaultReferenceView = gd2.getNextChoiceIndex();

					for ( final Subset< ViewId > subset : subsets )
					{
						final ViewId mapBackView = new ViewId( subset.getViews().iterator().next().getTimePointId(), setupList.get( index ) );
						final Dimensions mapBackViewDims = sd.getViewDescription( mapBackView ).getViewSetup().getSize();
						fmbp.mapBackViews.put( subset, new ValuePair< ViewId, Dimensions >( mapBackView, mapBackViewDims ) );
					}
				}
				else
				{
					for ( final Subset< ViewId > subset : subsets )
					{
						final ViewId ref = askForReferenceView( subset, "Select Reference Views" );

						if ( ref == null )
							return null;
						else
						{
							final Dimensions mapBackViewDims = sd.getViewDescription( ref ).getViewSetup().getSize();
							fmbp.mapBackViews.put( subset, new ValuePair< ViewId, Dimensions >( ref, mapBackViewDims ) );
						}
					}
				}
			}
			else
			{
				final ViewId ref = askForReferenceView( subsets.get( 0 ), "Select Reference Views" );
				
				if ( ref == null )
					return null;
				else
				{
					final Dimensions mapBackViewDims = sd.getViewDescription( ref ).getViewSetup().getSize();
					fmbp.mapBackViews.put( subsets.get( 0 ), new ValuePair< ViewId, Dimensions >( ref, mapBackViewDims ) );
				}
			}
		}
		else
		{
			// no reference view
		}

		IOFunctions.println( "Following views are references (for mapping back if there are no fixed views):" );
		for ( final Subset< ViewId > subset : subsets )
		{
			if ( fmbp.mapBackViews != null )
			{
				final Pair< ViewId, Dimensions > view = fmbp.mapBackViews.get( subset );

				if ( view != null )
				{
					final ViewId id = fmbp.mapBackViews.get( subset ).getA();
					if ( id != null )
						IOFunctions.println( "ViewSetupId: " + id.getViewSetupId() + " TimePoint:" + id.getTimePointId() );
				}
			}
		}

		return fmbp;
	}

	public static boolean hasGroups( final Collection< ? extends Subset< ? > > subsets )
	{
		for ( final Subset< ? > subset : subsets )
			if ( subset.getGroups().size() > 0 )
				return true;

		return false;
	}

	protected ArrayList< Integer > getListOfViewSetupIdsPresentInAllSubsets( final List< Subset< ViewId > > subsets )
	{
		final HashMap< Integer, Integer > viewsetups = new HashMap< Integer, Integer >();
		
		for ( final Subset< ViewId > subset : subsets )
			for ( final ViewId viewId : subset.getViews() )
			{
				if ( viewsetups.containsKey( viewId.getViewSetupId() ) )
					viewsetups.put( viewId.getViewSetupId(), viewsetups.get( viewId.getViewSetupId() ) + 1 );
				else
					viewsetups.put( viewId.getViewSetupId(), 1 );
			}

		final ArrayList< Integer > setupList = new ArrayList<>();

		for ( final int viewSetupId : viewsetups.keySet() )
			if ( viewsetups.get( viewSetupId ) == subsets.size() )
				setupList.add( viewSetupId );

		Collections.sort( setupList );

		return setupList;
	}

	protected boolean askForFixedViews( final Subset< ViewId > subset, final Set< ViewId > fixedViews, final String title )
	{
		final GenericDialog gd = new GenericDialog( title );

		final List< ViewId > views = Subset.getViewsSorted( subset.getViews() );

		if ( defaultFixedViews == null || defaultFixedViews.length != subset.getViews().size() )
			defaultFixedViews = new boolean[ subset.getViews().size() ];

		for ( int i = 0; i < subset.getViews().size(); ++i )
		{
			final ViewId viewId = views.get( i );
			gd.addCheckbox( "ViewSetupId_" + viewId.getViewSetupId() + "_Timepoint_" + viewId.getTimePointId(), defaultFixedViews[ i ] );
		}

		GUIHelper.addScrollBars( gd );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return false;

		for ( int i = 0; i < subset.getViews().size(); ++i )
			if ( defaultFixedViews[ i ] = gd.getNextBoolean() )
				fixedViews.add( views.get( i ) );
		
		return true;
	}

	protected ViewId askForReferenceView( final Subset< ViewId > subset, final String title )
	{
		final GenericDialog gd = new GenericDialog( title );

		final List< ViewId > views = Subset.getViewsSorted( subset.getViews() );

		final String[] choice = new String[ subset.getViews().size() ];

		for ( int i = 0; i < choice.length; ++i )
			choice[ i ] = "ViewSetupId:" + views.get( i ).getViewSetupId() + " Timepoint:" + views.get( i ).getTimePointId();

		if ( defaultReferenceView >= choice.length )
			defaultReferenceView = 0;

		gd.addChoice( title.replace( " ", "_" ), choice, choice[ defaultReferenceView ] );
		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;

		return views.get( defaultReferenceView = gd.getNextChoiceIndex() );
	}

	protected static String[] assembleTimepoints( final TimePoints timepoints )
	{
		final String[] tps = new String[ timepoints.size() ];

		for ( int t = 0; t < tps.length; ++t )
			tps[ t ] = timepoints.getTimePointsOrdered().get( t ).getName();

		return tps;
	}

	public List< Pair< Pair< ViewId, ViewId >, ? extends PairwiseResult< ? > > > getStatistics()
	{
		return statistics;
	}

	/*
	 * Registers all timepoints. No matter which matching is done it is always the same principle.
	 * 
	 * First all pairwise correspondences are established, and then a global optimization is computed.
	 * The global optimization can is done in subsets, where the number of subsets &gt;= 1.
	 * 
	 * @param registrationType - which kind of registration
	 * @param save - if you want to save the correspondence files
	 * @return
	public boolean register( final GlobalOptimizationType registrationType, final boolean save, final boolean collectStatistics )
	{
		final SpimData2 spimData = getSpimData();

		IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Starting registration" );

		if ( collectStatistics )
			this.statistics = new ArrayList<>();

		// get a list of all pairs for this specific GlobalOptimizationType
		final List< GlobalOptimizationSubset > list = registrationType.getAllViewPairs();

		int successfulRuns = 0;

		for ( final GlobalOptimizationSubset subset : list )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Finding correspondences for subset: " + subset.getDescription() );

			final List< PairwiseMatch > pairs = subset.getViewPairs();

			final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );
			// *** @@@ NOTE @@@ *** this taskExecutor is never shutdown()
			final ArrayList< Callable< PairwiseMatch > > tasks = new ArrayList< Callable< PairwiseMatch > >(); // your tasks

			for ( final PairwiseMatch pair : pairs )
			{
				// just for logging the names and results of pairwise comparison
				final ViewDescription viewA = spimData.getSequenceDescription().getViewDescription( pair.getViewIdA() );
				final ViewDescription viewB = spimData.getSequenceDescription().getViewDescription( pair.getViewIdB() );

				final String description = "[TP=" + viewA.getTimePoint().getName() + 
						" angle=" + viewA.getViewSetup().getAngle().getName() + ", ch=" + viewA.getViewSetup().getChannel().getName() +
						", illum=" + viewA.getViewSetup().getIllumination().getName() + " >>> TP=" + viewB.getTimePoint().getName() +
						" angle=" + viewB.getViewSetup().getAngle().getName() + ", ch=" + viewB.getViewSetup().getChannel().getName() +
						", illum=" + viewB.getViewSetup().getIllumination().getName() + "]";
				
				tasks.add( pairwiseMatchingInstance( pair, description ) );
			}
			try
			{
				// invokeAll() returns when all tasks are complete
				taskExecutor.invokeAll( tasks );
			}
			catch ( final InterruptedException e )
			{
				IOFunctions.println( "Failed to compute registrations for " + subset.getDescription() );
				e.printStackTrace();
			}
			
			
			// some statistics
			int sumCandidates = 0;
			int sumInliers = 0;
			for ( final PairwiseMatch pair : pairs )
			{
				sumCandidates += pair.getCandidates().size();
				sumInliers += pair.getInliers().size();
			}
			
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Number of Candidates: " + sumCandidates );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Number of Inliers: " + sumInliers );

			if ( collectStatistics )
				statistics.add( pairs );

			//
			// set and store correspondences
			//
			
			// first remove existing correspondences
			registrationType.clearExistingCorrespondences( subset );

			// now add all corresponding interest points
			registrationType.addCorrespondences( pairs );

			// save the files
			if ( save )
				registrationType.saveCorrespondences( subset );

			if ( runGlobalOpt( subset, registrationType ) )
				++successfulRuns;
		}
		
		if ( successfulRuns > 0 )
			return true;
		else
			return false;
	}
}
	 */
}
