/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
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
package net.preibisch.mvrecon.fiji.spimdata.explorer.analyzeerror;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ij.gui.GenericDialog;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BasicBDVPopup;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointdetection.InterestPointTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class AnalyzeErrorsUtil
{
	// Tile/Channel/Illumination defaults are shared with Interest_Point_Registration
	// (the same statics, mirroring the registration popup convention). Angles isn't
	// exposed by the explorer, so it gets its own session-static here.
	public static boolean defaultGroupAngles = false;

	public static int defaultTopNWorst  = 50;
	public static int defaultBottomMBest = 50;
	public static int defaultMiddleK    = 50;
	public static boolean defaultPrintAll = true;

	private AnalyzeErrorsUtil() {}

	/** Bundle of dialog choices returned by {@link #getParametersExtended}. */
	public static class Parameters
	{
		public HashMap< String, Double > labelAndWeights;
		public boolean groupTiles, groupChannels, groupIlluminations, groupAngles;
		public int topN, bottomM, middleK;
		/** When true, the caller should analyse all views in the dataset, not just the explorer's selection. */
		public boolean useAllViews;

		public boolean anyGroupingSelected()
		{
			return groupTiles || groupChannels || groupIlluminations || groupAngles;
		}
	}

	public static boolean defaultUseAllViews = false;

	/** Aggregated error statistics for one (groupA, groupB) bucket. Returned by {@link #computeGroupPairs}. */
	public static class GroupPairResult
	{
		public final Group< ViewDescription > groupA, groupB;
		public final int count;
		public final int numCorr;
		public final double min, avg, max;

		public GroupPairResult( final Group< ViewDescription > groupA, final Group< ViewDescription > groupB,
				final int count, final int numCorr, final double min, final double avg, final double max )
		{
			this.groupA = groupA;
			this.groupB = groupB;
			this.count = count;
			this.numCorr = numCorr;
			this.min = min;
			this.avg = avg;
			this.max = max;
		}
	}

	/** Per-(viewA, viewB) error result returned by {@link #getErrors}. */
	public static final class PairError
	{
		public final ViewId a, b;
		public final double errorPx;
		public final int numCorr;

		public PairError( final ViewId a, final ViewId b, final double errorPx, final int numCorr )
		{
			this.a = a;
			this.b = b;
			this.errorPx = errorPx;
			this.numCorr = numCorr;
		}
	}

	/**
	 * Bucket per-pair errors into per-group-pair buckets using {@link Group#combineBy}
	 * (collapse semantics — same as ViewSetupExplorer / Interest_Point_Registration grouping).
	 * Returns the buckets sorted descending by average error. Empty if no grouping is selected.
	 *
	 * Shared by the printed-log section in {@link #printResults} and the
	 * AnalyzeErrorsResultsWindow GUI.
	 */
	public static ArrayList< GroupPairResult > computeGroupPairs(
			final SpimData2 data,
			final ArrayList< PairError > errors,
			final Parameters p )
	{
		if ( !p.anyGroupingSelected() )
			return new ArrayList<>();

		final Set< Class< ? extends Entity > > factors = new HashSet<>();
		if ( p.groupTiles )         factors.add( Tile.class );
		if ( p.groupChannels )      factors.add( Channel.class );
		if ( p.groupIlluminations ) factors.add( Illumination.class );
		if ( p.groupAngles )        factors.add( Angle.class );

		final HashSet< ViewId > seen = new HashSet<>();
		for ( final PairError e : errors )
		{
			seen.add( e.a );
			seen.add( e.b );
		}
		final List< ViewDescription > vds = new ArrayList<>();
		for ( final ViewId vid : seen )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescriptions().get( vid );
			if ( vd != null )
				vds.add( vd );
		}

		final List< Group< ViewDescription > > groups = Group.combineBy( vds, factors );

		final HashMap< ViewId, Group< ViewDescription > > viewToGroup = new HashMap<>();
		final HashMap< Group< ViewDescription >, ViewId > groupRep = new HashMap<>();
		for ( final Group< ViewDescription > g : groups )
		{
			for ( final ViewDescription vd : g.getViews() )
				viewToGroup.put( vd, g );
			groupRep.put( g, Group.getViewsSorted( g.getViews() ).get( 0 ) );
		}

		final HashMap< Pair< Group< ViewDescription >, Group< ViewDescription > >, GroupAcc > groupPairAcc = new HashMap<>();
		for ( final PairError e : errors )
		{
			final Group< ViewDescription > gA = viewToGroup.get( e.a );
			final Group< ViewDescription > gB = viewToGroup.get( e.b );
			if ( gA == null || gB == null )
				continue;
			final Group< ViewDescription > g1, g2;
			if ( compareViewIds( groupRep.get( gA ), groupRep.get( gB ) ) <= 0 ) { g1 = gA; g2 = gB; }
			else                                                                 { g1 = gB; g2 = gA; }
			groupPairAcc.computeIfAbsent( new ValuePair<>( g1, g2 ), k -> new GroupAcc() ).add( e.errorPx, e.numCorr );
		}

		final ArrayList< GroupPairResult > result = new ArrayList<>( groupPairAcc.size() );
		for ( final Map.Entry< Pair< Group< ViewDescription >, Group< ViewDescription > >, GroupAcc > e : groupPairAcc.entrySet() )
		{
			final GroupAcc a = e.getValue();
			result.add( new GroupPairResult( e.getKey().getA(), e.getKey().getB(), (int) a.count, a.numCorr, a.min, a.avg(), a.max ) );
		}
		result.sort( ( o1, o2 ) -> Double.compare( o2.avg, o1.avg ) );
		return result;
	}

	/**
	 * Compute weighted error per (viewA, viewB) pair, returning the list sorted desc by error.
	 *
	 * Inverted iteration: walks each view's correspondences exactly once and accumulates into a
	 * canonical-pair map. Avoids the all-pairs Cartesian product that OOMs on large datasets.
	 * Complexity: O(N · C_avg) instead of O(N² · C_avg).
	 *
	 * Same-label only (matching the previous code's filter): correspondences with a different
	 * label on the other side are skipped.
	 */
	public static ArrayList< PairError > getErrors(
			final SpimData2 data,
			final List< ViewId > viewIds,
			final Map< String, Double > labelAndWeights )
	{
		// load all ViewRegistrations
		final HashMap< ViewId, AffineTransform3D > viewToModel = new HashMap<>();
		viewIds.forEach( viewId -> {
			final ViewRegistration vr = data.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			viewToModel.put( viewId, vr.getModel() );
		});

		// pre-cache IPs and correspondences per (view, label) once, in parallel
		final ConcurrentHashMap< ViewId, Map< String, Map< Integer, InterestPoint > > > ipsCache = new ConcurrentHashMap<>();
		final ConcurrentHashMap< ViewId, Map< String, Collection< CorrespondingInterestPoints > > > corrsCache = new ConcurrentHashMap<>();

		viewIds.parallelStream().forEach( viewId -> {
			final ViewInterestPointLists vip = data.getViewInterestPoints().getViewInterestPointLists( viewId );
			final HashMap< String, Map< Integer, InterestPoint > > ipsByLabel = new HashMap<>();
			final HashMap< String, Collection< CorrespondingInterestPoints > > corrsByLabel = new HashMap<>();
			for ( final String label : labelAndWeights.keySet() )
			{
				if ( vip.getInterestPointList( label ) != null )
				{
					ipsByLabel.put( label, vip.getInterestPointList( label ).getInterestPointsCopy() );
					corrsByLabel.put( label, vip.getInterestPointList( label ).getCorrespondingInterestPointsCopy() );
				}
			}
			ipsCache.put( viewId, ipsByLabel );
			corrsCache.put( viewId, corrsByLabel );
		});

		// O(1) lookup of "is this view in the user-selected set"
		final Set< ViewId > selected = new HashSet<>( viewIds );

		// Canonical pair map: only one side accumulates per pair (viewA < viewB).
		final ConcurrentHashMap< Pair< ViewId, ViewId >, ErrorAcc > pairErrors = new ConcurrentHashMap<>();

		viewIds.parallelStream().forEach( viewA -> {
			final AffineTransform3D mA = viewToModel.get( viewA );
			final Map< String, Map< Integer, InterestPoint > > aIps = ipsCache.get( viewA );
			final Map< String, Collection< CorrespondingInterestPoints > > aCorrs = corrsCache.get( viewA );

			for ( final Entry< String, Double > e : labelAndWeights.entrySet() )
			{
				final String label = e.getKey();
				final double w = e.getValue();

				final Map< Integer, InterestPoint > ipsA = aIps.get( label );
				final Collection< CorrespondingInterestPoints > corrs = aCorrs.get( label );
				if ( ipsA == null || corrs == null )
					continue;

				final double[] tA = new double[ 3 ];
				final double[] tB = new double[ 3 ];

				for ( final CorrespondingInterestPoints cpA : corrs )
				{
					// same-label only — preserves prior semantics
					if ( !cpA.getCorrespodingLabel().equals( label ) )
						continue;
					final ViewId viewB = cpA.getCorrespondingViewId();
					if ( !selected.contains( viewB ) )
						continue;
					if ( compareViewIds( viewA, viewB ) >= 0 )
						continue; // canonical: only A < B side accumulates

					final Map< Integer, InterestPoint > ipsB = ipsCache.getOrDefault( viewB, Collections.emptyMap() ).get( label );
					if ( ipsB == null )
						continue;
					final InterestPoint pA = ipsA.get( cpA.getDetectionId() );
					final InterestPoint pB = ipsB.get( cpA.getCorrespondingDetectionId() );
					if ( pA == null || pB == null )
						continue;

					final AffineTransform3D mB = viewToModel.get( viewB );
					mA.apply( pA.getL(), tA );
					mB.apply( pB.getL(), tB );
					final double dx = tA[ 0 ] - tB[ 0 ], dy = tA[ 1 ] - tB[ 1 ], dz = tA[ 2 ] - tB[ 2 ];
					final double d = Math.sqrt( dx * dx + dy * dy + dz * dz );

					final Pair< ViewId, ViewId > key = new ValuePair<>( viewA, viewB );
					pairErrors.compute( key, ( k, acc ) -> {
						if ( acc == null )
							acc = new ErrorAcc();
						acc.weightedDistanceSum += d * w;
						acc.weightSum += w;
						acc.numCorr++;
						return acc;
					});
				}
			}
		});

		// materialise + sort desc by error
		final ArrayList< PairError > pairResults = new ArrayList<>( pairErrors.size() );
		for ( final Entry< Pair< ViewId, ViewId >, ErrorAcc > e : pairErrors.entrySet() )
		{
			final ErrorAcc acc = e.getValue();
			if ( acc.weightSum > 0 )
				pairResults.add( new PairError( e.getKey().getA(), e.getKey().getB(),
						acc.weightedDistanceSum / acc.weightSum, acc.numCorr ) );
		}
		Collections.sort( pairResults, ( o1, o2 ) -> Double.compare( o2.errorPx, o1.errorPx ) );
		return pairResults;
	}

	/** Mutable accumulator for the canonical-pair map. Updated under {@code ConcurrentHashMap.compute}. */
	private static final class ErrorAcc
	{
		double weightedDistanceSum = 0.0;
		double weightSum = 0.0;
		int numCorr = 0;
	}

	/** ViewId comparison: timepointId, then viewSetupId. Mirrors the in-repo CorrespondingInterestPoints.compareTo. */
	private static int compareViewIds( final ViewId a, final ViewId b )
	{
		final int t = Integer.compare( a.getTimePointId(), b.getTimePointId() );
		if ( t != 0 )
			return t;
		return Integer.compare( a.getViewSetupId(), b.getViewSetupId() );
	}

	/** Backwards-compatible wrapper that returns just the labels-and-weights map. */
	public static HashMap< String, Double > getParameters(
			final SpimData2 data,
			final List< ViewId > viewIds )
	{
		final Parameters p = getParametersExtended( data, viewIds );
		return ( p != null ) ? p.labelAndWeights : null;
	}

	/** Extended dialog returning the full {@link Parameters} bundle (labels+weights, grouping, top/bottom caps). */
	public static Parameters getParametersExtended(
			final SpimData2 data,
			final List< ViewId > viewIds )
	{
		final GenericDialog gd = new GenericDialog( "Analyze Interest Point Errors" );

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
		if ( Interest_Point_Registration.defaultLabel < 0 || Interest_Point_Registration.defaultLabel >= labels.length )
		{
			Interest_Point_Registration.defaultLabel = -1;

			for ( int i = 0; i < labels.length; ++i )
				if ( !labels[ i ].contains( InterestPointTools.warningLabel ) && !labels[ i ].startsWith( "Select multiple interestpoints") )
				{
					Interest_Point_Registration.defaultLabel = i;
					break;
				}

			if ( Interest_Point_Registration.defaultLabel == -1 )
				Interest_Point_Registration.defaultLabel = 0;
		}

		gd.addChoice( "Interest_points" , labels, labels[ Interest_Point_Registration.defaultLabel ] );

		gd.addCheckbox( "Include_all_views_(ignore_explorer_selection)", defaultUseAllViews );

		gd.addMessage( "Group by (defines what counts as one 'old tile'):", GUIHelper.largefont );
		gd.addCheckbox( "Group_by_Tile",         Interest_Point_Registration.defaultGroupTiles );
		gd.addCheckbox( "Group_by_Channel",      Interest_Point_Registration.defaultGroupChannels );
		gd.addCheckbox( "Group_by_Illumination", Interest_Point_Registration.defaultGroupIllums );
		gd.addCheckbox( "Group_by_Angle",        defaultGroupAngles );

		gd.addMessage( "Per-pair listing (top-worst + bottom-best + middle-K):", GUIHelper.largefont );
		gd.addCheckbox( "Print_everything_(ignore_Top/Bottom/Middle)", defaultPrintAll );
		gd.addNumericField( "Top_N_worst_pairs",   defaultTopNWorst,   0 );
		gd.addNumericField( "Bottom_M_best_pairs", defaultBottomMBest, 0 );
		gd.addNumericField( "Middle_K_pairs_(closest_to_median_and_average)", defaultMiddleK, 0 );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		// assemble which label has been selected
		final int labelChoice = Interest_Point_Registration.defaultLabel = gd.getNextChoiceIndex();

		final Parameters p = new Parameters();
		p.useAllViews        = defaultUseAllViews                                = gd.getNextBoolean();
		p.groupTiles         = Interest_Point_Registration.defaultGroupTiles    = gd.getNextBoolean();
		p.groupChannels      = Interest_Point_Registration.defaultGroupChannels = gd.getNextBoolean();
		p.groupIlluminations = Interest_Point_Registration.defaultGroupIllums   = gd.getNextBoolean();
		p.groupAngles        = defaultGroupAngles                                = gd.getNextBoolean();
		final boolean printAll = defaultPrintAll = gd.getNextBoolean();
		final int topN_raw    = defaultTopNWorst   = Math.max( 0, ( int ) Math.round( gd.getNextNumber() ) );
		final int bottomM_raw = defaultBottomMBest = Math.max( 0, ( int ) Math.round( gd.getNextNumber() ) );
		final int middleK_raw = defaultMiddleK     = Math.max( 0, ( int ) Math.round( gd.getNextNumber() ) );

		// "Print everything" prints the full desc-sorted list once via the Top section
		// and skips Bottom + Middle to avoid emitting the same rows three times.
		if ( printAll )
		{
			p.topN    = Integer.MAX_VALUE;
			p.bottomM = 0;
			p.middleK = 0;
		}
		else
		{
			p.topN    = topN_raw;
			p.bottomM = bottomM_raw;
			p.middleK = middleK_raw;
		}

		final HashMap< String, Double > labelAndWeight = new HashMap<>();
		p.labelAndWeights = labelAndWeight;

		if ( labelChoice < labels.length - 1 )
		{
			labelAndWeight.put( InterestPointTools.getSelectedLabel( labels, labelChoice ), 1.0 );
		}
		else
		{
			final ArrayList< String > labelChoices = Interest_Point_Registration.multipleInterestPointsGUI( labels );

			final GenericDialog gdLabel2 = new GenericDialog( "Select error weights" );

			if ( Interest_Point_Registration.defaultLabelWeights == null || Interest_Point_Registration.defaultLabelWeights.length != labelChoices.size() )
			{
				Interest_Point_Registration.defaultLabelWeights = new double[ labelChoices.size() ];
				Arrays.setAll( Interest_Point_Registration.defaultLabelWeights, d -> 1.0 );
			}

			gdLabel2.addMessage( "Weights for interest point labels:", GUIHelper.largefont );

			for ( int i = 0; i < labelChoices.size(); ++i )
				gdLabel2.addNumericField( labelChoices.get( i ) + " w=", Interest_Point_Registration.defaultLabelWeights[ i ], 2 );

			gdLabel2.showDialog();
			if ( gdLabel2.wasCanceled() )
				return null;

			for ( int i = 0; i < labelChoices.size(); ++i )
			{
				labelAndWeight.put( labelChoices.get( i ), Interest_Point_Registration.defaultLabelWeights[ i ] = gdLabel2.getNextNumber() );
				IOFunctions.println( labelChoices.get( i ) + ", weight=" + labelAndWeight.get( labelChoices.get( i ) ) );
			}
		}

		return p;
	}

	/**
	 * Print top-N worst, bottom-M best, and the per-old-tile-pair / per-old-tile rollup
	 * summary blocks that the {@link Parameters} dialog choices request.
	 */
	public static void printResults(
			final SpimData2 data,
			final ArrayList< PairError > errors,
			final Parameters p )
	{
		if ( errors.isEmpty() )
		{
			IOFunctions.println( "No view pairs with correspondences found; nothing to report." );
			return;
		}

		final int total = errors.size();

		// 1) Top-N worst (errors is desc-sorted)
		final int topN = Math.min( p.topN, total );
		if ( topN > 0 )
		{
			IOFunctions.println( "Top " + topN + " of " + total + " pair(s) by error (worst first):" );
			for ( int i = 0; i < topN; i++ )
			{
				final PairError e = errors.get( i );
				IOFunctions.println( "  " + Group.pvid( e.a ) + " <-> " + Group.pvid( e.b ) + ": " + String.format( Locale.ROOT, "%.4f", e.errorPx ) + " px" );
			}
		}

		// 2) Bottom-M best
		final int bottomM = Math.min( p.bottomM, total );
		if ( bottomM > 0 )
		{
			IOFunctions.println( "Bottom " + bottomM + " of " + total + " pair(s) by error (best first):" );
			for ( int i = total - 1, k = 0; i >= 0 && k < bottomM; i--, k++ )
			{
				final PairError e = errors.get( i );
				IOFunctions.println( "  " + Group.pvid( e.a ) + " <-> " + Group.pvid( e.b ) + ": " + String.format( Locale.ROOT, "%.4f", e.errorPx ) + " px" );
			}
		}

		// 2b) Middle-K nearest median and nearest average
		final int middleK = Math.min( p.middleK, total );
		if ( middleK > 0 )
		{
			// errors is desc-sorted; median = middle of sorted-asc, equivalent to index total - 1 - (total-1)/2
			final double median = ( total % 2 == 1 )
					? errors.get( total / 2 ).errorPx
					: 0.5 * ( errors.get( total / 2 - 1 ).errorPx + errors.get( total / 2 ).errorPx );

			double sum = 0.0;
			for ( final PairError e : errors )
				sum += e.errorPx;
			final double average = sum / total;

			// helper: pick K entries with smallest |error - reference|, in original sort order
			IOFunctions.println( "Middle " + middleK + " of " + total + " pair(s) closest to median (median=" + fmt( median ) + " px):" );
			for ( final PairError e : pickClosest( errors, median, middleK ) )
				IOFunctions.println( "  " + Group.pvid( e.a ) + " <-> " + Group.pvid( e.b ) + ": " + fmt( e.errorPx ) + " px" );

			IOFunctions.println( "Middle " + middleK + " of " + total + " pair(s) closest to average (avg=" + fmt( average ) + " px):" );
			for ( final PairError e : pickClosest( errors, average, middleK ) )
				IOFunctions.println( "  " + Group.pvid( e.a ) + " <-> " + Group.pvid( e.b ) + ": " + fmt( e.errorPx ) + " px" );
		}

		if ( !p.anyGroupingSelected() )
			return;

		// Build the per-group-pair buckets via the shared helper (single Group.combineBy call).
		final ArrayList< GroupPairResult > groupPairs = computeGroupPairs( data, errors, p );
		final int totalGP = groupPairs.size();

		// Re-derive factors (cheap) and the unique groups + ViewId→Group index from the result,
		// so the rollup section below doesn't need a second Group.combineBy walk.
		final Set< Class< ? extends Entity > > factors = new HashSet<>();
		if ( p.groupTiles )         factors.add( Tile.class );
		if ( p.groupChannels )      factors.add( Channel.class );
		if ( p.groupIlluminations ) factors.add( Illumination.class );
		if ( p.groupAngles )        factors.add( Angle.class );

		final HashSet< Group< ViewDescription > > uniqueGroups = new HashSet<>();
		for ( final GroupPairResult r : groupPairs )
		{
			uniqueGroups.add( r.groupA );
			uniqueGroups.add( r.groupB );
		}
		final HashMap< ViewId, Group< ViewDescription > > viewToGroup = new HashMap<>();
		for ( final Group< ViewDescription > g : uniqueGroups )
			for ( final ViewDescription vd : g.getViews() )
				viewToGroup.put( vd, g );

		IOFunctions.println( "Grouping by " + describeFactors( factors ) + " (collapse): " + uniqueGroups.size() + " group(s) over " + viewToGroup.size() + " view(s)." );

		final int gpTopN = Math.min( p.topN, totalGP );
		if ( gpTopN > 0 )
		{
			IOFunctions.println( "Top " + gpTopN + " of " + totalGP + " group-pair(s) by avg error (worst first):" );
			for ( int i = 0; i < gpTopN; i++ )
				printGroupPair( groupPairs.get( i ) );
		}
		final int gpBottomM = Math.min( p.bottomM, totalGP );
		if ( gpBottomM > 0 )
		{
			IOFunctions.println( "Bottom " + gpBottomM + " of " + totalGP + " group-pair(s) by avg error (best first):" );
			for ( int i = totalGP - 1, k = 0; i >= 0 && k < gpBottomM; i--, k++ )
				printGroupPair( groupPairs.get( i ) );
		}

		// Per-group rollup: each pair contributes once to each endpoint group (or once total if intra-group).
		final HashMap< Group< ViewDescription >, GroupAcc > groupAcc = new HashMap<>();
		for ( final PairError e : errors )
		{
			final Group< ViewDescription > gA = viewToGroup.get( e.a );
			final Group< ViewDescription > gB = viewToGroup.get( e.b );
			if ( gA != null )
				groupAcc.computeIfAbsent( gA, k -> new GroupAcc() ).add( e.errorPx, e.numCorr );
			if ( gB != null && gB != gA )
				groupAcc.computeIfAbsent( gB, k -> new GroupAcc() ).add( e.errorPx, e.numCorr );
		}
		final ArrayList< Map.Entry< Group< ViewDescription >, GroupAcc > > groupRollup = new ArrayList<>( groupAcc.entrySet() );
		groupRollup.sort( ( o1, o2 ) -> Double.compare( o2.getValue().avg(), o1.getValue().avg() ) );
		final int totalGR = groupRollup.size();

		final int grTopN = Math.min( p.topN, totalGR );
		if ( grTopN > 0 )
		{
			IOFunctions.println( "Top " + grTopN + " of " + totalGR + " group(s) by avg error across involving pairs (worst first):" );
			for ( int i = 0; i < grTopN; i++ )
				printGroupRollup( groupRollup.get( i ) );
		}
		final int grBottomM = Math.min( p.bottomM, totalGR );
		if ( grBottomM > 0 )
		{
			IOFunctions.println( "Bottom " + grBottomM + " of " + totalGR + " group(s) by avg error across involving pairs (best first):" );
			for ( int i = totalGR - 1, k = 0; i >= 0 && k < grBottomM; i--, k++ )
				printGroupRollup( groupRollup.get( i ) );
		}
	}

	/**
	 * Bring the explorer into the grouping state AnalyzeErrors was run with (only
	 * if it isn't already there), then select rows whose view-set intersects the
	 * given views, update BDV coloring, and recenter on the selection.
	 *
	 * The fast path (explorer grouping already matches {@code params}) skips the
	 * re-grouping and the table-update waits — no flicker. Channel/Angle grouping
	 * isn't expressible via the explorer's checkboxes, so when {@code params} asked
	 * for those, the explorer is ungrouped instead and selection happens on the
	 * individual views.
	 *
	 * Must NOT be called on the EDT — uses {@link SimpleMultiThreading#threadWait}
	 * to let the explorer's table updates settle when re-grouping is needed.
	 */
	public static void selectViewsAndRecenter(
			final ViewSetupExplorerPanel< ? > panel,
			final Parameters params,
			final Collection< ? extends ViewId > views )
	{
		final BasicBDVPopup pop = panel.runningBdvPopup();
		final boolean bdvOpen = pop != null && pop.getBDV() != null && pop.getBDV().getViewerFrame().isVisible();
		// updateBDV does eager-style visibility batching; harmful (or no-op) for the
		// lazy popup which manages its own source list via the explorer's selection
		// listener. Skip it in lazy mode.
		final boolean canEagerUpdate = bdvOpen && pop instanceof BDVPopup;

		// target grouping = the params grouping, but Channel/Angle aren't UI-toggleable
		// in the explorer (no checkbox), so treat those as "not compatible" and ungroup.
		final boolean incompatible = params.groupChannels || params.groupAngles;
		final Set< Class< ? extends Entity > > target = new HashSet<>();
		if ( !incompatible )
		{
			if ( params.groupTiles )         target.add( Tile.class );
			if ( params.groupIlluminations ) target.add( Illumination.class );
		}
		final Set< Class< ? extends Entity > > current = new HashSet<>( panel.getTableModel().getGroupingFactors() );

		if ( !target.equals( current ) )
		{
			// disable coloring during the regroup so stale row-views don't drive BDV
			if ( canEagerUpdate )
				panel.updateBDV( pop.getBDV(), false, panel.getSpimData(), null, panel.selectedRows );

			if ( panel.groupTilesCheckbox != null )
				panel.groupTilesCheckbox.setSelected( target.contains( Tile.class ) );
			if ( panel.groupIllumsCheckbox != null )
				panel.groupIllumsCheckbox.setSelected( target.contains( Illumination.class ) );
			panel.getTableModel().clearGroupingFactors();
			for ( final Class< ? extends Entity > f : target )
				panel.getTableModel().addGroupingFactor( f );
			panel.updateContent();

			// wait until the table is updated (otherwise there might be an exception thrown)
			SimpleMultiThreading.threadWait( 100 );
		}

		// build (timepointId, viewSetupId) target lookup
		final HashSet< Long > targets = new HashSet<>();
		for ( final ViewId v : views )
			targets.add( ( ( long ) v.getTimePointId() << 32 ) | ( v.getViewSetupId() & 0xffffffffL ) );

		// select rows whose view-set intersects the target — works in any grouping state
		final List< ? extends List< ? extends BasicViewDescription< ? > > > elements =
				panel.getTableModel().getElements();
		boolean setFirst = false;
		int firstMatchRow = -1;
		for ( int r = 0; r < elements.size(); r++ )
		{
			boolean matches = false;
			for ( final BasicViewDescription< ? > vd : elements.get( r ) )
			{
				final long key = ( ( long ) vd.getTimePointId() << 32 ) | ( vd.getViewSetupId() & 0xffffffffL );
				if ( targets.contains( key ) ) { matches = true; break; }
			}
			if ( matches )
			{
				if ( firstMatchRow < 0 )
					firstMatchRow = r;
				if ( setFirst )
					panel.table.addRowSelectionInterval( r, r );
				else
				{
					setFirst = true;
					panel.table.setRowSelectionInterval( r, r );
				}
			}
		}

		// wait until the table is updated (otherwise there might be an exception thrown)
		SimpleMultiThreading.threadWait( 100 );

		// scroll so the first matched row is visible (mirrors FilteredAndGroupedExplorerPanel.selectViews)
		if ( firstMatchRow >= 0 )
		{
			final int row = firstMatchRow;
			javax.swing.SwingUtilities.invokeLater( () ->
					panel.table.scrollRectToVisible( panel.table.getCellRect( row, 0, true ) ) );
		}

		// recenter BDV on the selection
		if ( bdvOpen )
		{
			TransformationTools.reCenterViews( pop.getBDV(),
					panel.selectedRows.stream().collect(
							HashSet< BasicViewDescription< ? > >::new,
							( a, b ) -> a.addAll( b ), ( a, b ) -> a.addAll( b ) ),
					panel.getSpimData().getViewRegistrations() );
		}
	}

	private static void printGroupPair( final GroupPairResult r )
	{
		IOFunctions.println( "  [" + Group.gvids( r.groupA ) + "] <-> [" + Group.gvids( r.groupB ) + "]: "
				+ r.count + " pair(s), error min=" + fmt( r.min ) + " avg=" + fmt( r.avg ) + " max=" + fmt( r.max ) );
	}

	private static void printGroupRollup(
			final Map.Entry< Group< ViewDescription >, GroupAcc > entry )
	{
		final GroupAcc a = entry.getValue();
		IOFunctions.println( "  [" + Group.gvids( entry.getKey() ) + "]: "
				+ a.count + " pair(s), error min=" + fmt( a.min ) + " avg=" + fmt( a.avg() ) + " max=" + fmt( a.max ) );
	}

	private static String describeFactors( final Set< Class< ? extends Entity > > factors )
	{
		if ( factors.isEmpty() )
			return "(none)";
		final ArrayList< String > names = new ArrayList<>();
		if ( factors.contains( Tile.class ) )         names.add( "Tile" );
		if ( factors.contains( Channel.class ) )      names.add( "Channel" );
		if ( factors.contains( Illumination.class ) ) names.add( "Illumination" );
		if ( factors.contains( Angle.class ) )        names.add( "Angle" );
		return String.join( ", ", names );
	}

	private static String fmt( final double v )
	{
		return String.format( Locale.ROOT, "%.4f", v );
	}

	/**
	 * Return the K entries from {@code errors} with the smallest |error - reference|,
	 * preserving the original (desc-by-error) order in the output. O(N log K) using a
	 * max-heap keyed by absolute distance.
	 */
	private static ArrayList< PairError > pickClosest(
			final ArrayList< PairError > errors,
			final double reference,
			final int k )
	{
		final int kk = Math.min( k, errors.size() );
		// max-heap on |distance| so the worst-fit at the top can be evicted
		final java.util.PriorityQueue< int[] > heap = new java.util.PriorityQueue<>( kk,
				( a, b ) -> Double.compare(
						Math.abs( errors.get( b[ 0 ] ).errorPx - reference ),
						Math.abs( errors.get( a[ 0 ] ).errorPx - reference ) ) );
		for ( int i = 0; i < errors.size(); i++ )
		{
			if ( heap.size() < kk )
				heap.offer( new int[] { i } );
			else
			{
				final double cur = Math.abs( errors.get( i ).errorPx - reference );
				final double worst = Math.abs( errors.get( heap.peek()[ 0 ] ).errorPx - reference );
				if ( cur < worst )
				{
					heap.poll();
					heap.offer( new int[] { i } );
				}
			}
		}
		final ArrayList< Integer > indices = new ArrayList<>( heap.size() );
		for ( final int[] e : heap ) indices.add( e[ 0 ] );
		Collections.sort( indices ); // restore original (desc-by-error) order
		final ArrayList< PairError > out = new ArrayList<>( indices.size() );
		for ( final int i : indices ) out.add( errors.get( i ) );
		return out;
	}

	/** Mutable accumulator for the per-group summary blocks. */
	private static final class GroupAcc
	{
		long count = 0;
		int numCorr = 0;
		double sum = 0.0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;

		void add( final double v, final int corrAdd )
		{
			count++;
			numCorr += corrAdd;
			sum += v;
			if ( v < min ) min = v;
			if ( v > max ) max = v;
		}
		double avg() { return count == 0 ? 0.0 : sum / count; }
	}
}
