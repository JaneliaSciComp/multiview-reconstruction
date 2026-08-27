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
package net.preibisch.mvrecon.process.fusion.blk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField;
import net.imglib2.converter.Converter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.process.fusion.tps.Landmarks;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.splitting.SplittingTools;

public class SplitImgLoaderThinPlateSplineFusion
{
	/**
	 * One TPS landmark, classified by category. Emitted to an optional visitor
	 * passed to {@link #getCoefficients}, primarily for diagnostic / overlay
	 * tooling (export to CSV, draw on top of the fused image, etc.).
	 *
	 * Source coords are in underlying-view pixel space (matching how
	 * {@link Landmarks#getSourcePoints()} stores them). Target coords are in
	 * render/global space (anisotropy already applied).
	 *
	 * For {@code TYPE_NAIL} records, {@link #underlyingViewId} is the
	 * <em>recipient</em> underlying view (whose TPS the landmark feeds) and
	 * {@link #donorViewId} is the underlying view whose split sub-view's
	 * surface produced the corner; the two coincide for "self" donations and
	 * differ for cross-view tie nails. For {@code TYPE_SPLIT_CENTER}/{@code
	 * TYPE_MIDPOINT} the donor field equals the underlying view.
	 */
	public static final class LandmarkRecord implements java.io.Serializable
	{
		private static final long serialVersionUID = 1L;

		public static final String TYPE_SPLIT_CENTER = "splitCenter";
		public static final String TYPE_MIDPOINT = "midpoint";
		public static final String TYPE_NAIL = "nail";

		public final ViewId underlyingViewId;
		public final ViewId donorViewId; // donor underlying view; equals underlyingViewId for non-nail records
		public final String type;       // one of TYPE_*
		public final double[] source;   // length 3, underlying-view pixel coords
		public final double[] target;   // length 3, render coords

		public LandmarkRecord( final ViewId underlyingViewId, final String type, final double[] source, final double[] target )
		{
			this( underlyingViewId, underlyingViewId, type, source, target );
		}

		public LandmarkRecord( final ViewId underlyingViewId, final ViewId donorViewId, final String type, final double[] source, final double[] target )
		{
			this.underlyingViewId = underlyingViewId;
			this.donorViewId = donorViewId;
			this.type = type;
			this.source = source;
			this.target = target;
		}
	}

	/**
	 * A single nail landmark donated from one split sub-view's surface onto
	 * the recipient underlying view's TPS. {@code src} is in the
	 * <em>recipient</em>'s underlying-view pixel coords (already includes the
	 * recipient's split translation); {@code target} is in render coords.
	 *
	 * @see #computeCrossViewNailDonations
	 */
	public static final class DonatedNail implements java.io.Serializable
	{
		private static final long serialVersionUID = 1L;
		public final double[] src;       // length 3, recipient underlying-view pixel coords
		public final double[] target;    // length 3, render coords
		public final ViewId donorViewId; // underlying view whose surface sample generated this nail

		public DonatedNail( final double[] src, final double[] target, final ViewId donorViewId )
		{
			this.src = src;
			this.target = target;
			this.donorViewId = donorViewId;
		}
	}


	/**
	 * Backward-compatible overload — center landmarks only (no
	 * correspondence-based midpoint landmarks).
	 */
	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionGUI.FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize )
	{
		return init( converter, splitImgLoader, splitViewIdsInput, splitViewRegistrations,
				splitViewDescriptions, fusionType, intervalExpansion, anisotropyFactor,
				interpolationMethod, fusionMap, intensityAdjustmentCoefficients, fusionInterval,
				type, blockSize, null, null, 0, false, 0.0 );
	}

	/**
	 * Backward-compatible overload — correspondence midpoints, no corner anchors.
	 */
	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionGUI.FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences )
	{
		return init( converter, splitImgLoader, splitViewIdsInput, splitViewRegistrations,
				splitViewDescriptions, fusionType, intervalExpansion, anisotropyFactor,
				interpolationMethod, fusionMap, intensityAdjustmentCoefficients, fusionInterval,
				type, blockSize, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				false, 0.0 );
	}

	/**
	 * Backward-compatible overload — corner anchors with N=2 (corners only).
	 */
	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionGUI.FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,
			final double cornerCoverageRadius )
	{
		return init( converter, splitImgLoader, splitViewIdsInput, splitViewRegistrations,
				splitViewDescriptions, fusionType, intervalExpansion, anisotropyFactor,
				interpolationMethod, fusionMap, intensityAdjustmentCoefficients, fusionInterval,
				type, blockSize, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				anchorOverlapCorners, cornerCoverageRadius, 2, null );
	}

	/**
	 * Backward-compatible overload — no landmark visitor.
	 */
	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionGUI.FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,
			final double cornerCoverageRadius,
			final int seamSamplesPerAxis )
	{
		return init( converter, splitImgLoader, splitViewIdsInput, splitViewRegistrations,
				splitViewDescriptions, fusionType, intervalExpansion, anisotropyFactor,
				interpolationMethod, fusionMap, intensityAdjustmentCoefficients, fusionInterval,
				type, blockSize, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis, null, null, null );
	}

	/**
	 * Backward-compatible overload — visitor only, no per-split schedule.
	 */
	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionGUI.FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,
			final double cornerCoverageRadius,
			final int seamSamplesPerAxis,
			final Consumer< LandmarkRecord > landmarkVisitor )
	{
		return init( converter, splitImgLoader, splitViewIdsInput, splitViewRegistrations,
				splitViewDescriptions, fusionType, intervalExpansion, anisotropyFactor,
				interpolationMethod, fusionMap, intensityAdjustmentCoefficients, fusionInterval,
				type, blockSize, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis, null, null, landmarkVisitor );
	}

	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations, // raw registrations; anisotropy is applied internally via getCoefficients(anisotropyFactor, ...)
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionGUI.FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap, // old setupId > new setupId for fusion order, only makes sense with FusionType.FIRST_LOW or FusionType.FIRST_HIGH
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients, // from underlying viewids
			final Interval fusionInterval,  // already adjusted for anisotropy???
			final T type,
			final int[] blockSize,
			final ViewInterestPoints viewInterestPoints, // nullable: when non-null + label non-null, append cross-view midpoint landmarks
			final String correspondenceLabel,            // nullable
			final int minNumCorrespondences,             // ignored when label/vip is null
			final boolean anchorOverlapCorners,          // when true, add corner-anchor landmarks (requires non-null vip+label)
			final double cornerCoverageRadius,           // render-space radius; 0 = nail every candidate, larger = only nail far-from-CoM corners
			final int seamSamplesPerAxis,                // surface samples per axis fallback; 2 = corners only
			final int[] seamSamplesScheduleThresholds,   // nullable; sorted ascending. Per-split: count <= thresholds[i] -> values[i]
			final int[] seamSamplesScheduleValues,       // nullable; parallel to thresholds; fallback = seamSamplesPerAxis
			final Consumer< LandmarkRecord > landmarkVisitor )  // nullable; invoked once per emitted landmark
	{
		// assemble all underlying viewIds (which will expand the list of splitViews to all the underlying viewids consist of)
		final List< ViewId > underlyingViewIds = underlyingViewIds( splitViewIdsInput, splitImgLoader.new2oldSetupId() );
		final Map<Integer, List<Integer>> old2newSetupId = old2newSetupId( splitImgLoader.new2oldSetupId() );
		final List< ViewId > splitViewIds = splitViewIds( underlyingViewIds, old2newSetupId );

		// TODO: do something more like this:
		//  // go through the views and check if they are all 2-dimensional
		//     final boolean is2d = is2d( viewIds, viewDescriptions );
		//  and reuse is2d for Overlap numDimensions (see BlkAffineFusion)
		if ( BlkAffineFusion.is2d( splitViewIds, splitViewDescriptions ) )
			throw new UnsupportedOperationException( "BlkThinPlateSplineFusion: 2D fusion not supported." );

		final ViewerImgLoader underlyingImgLoader = splitImgLoader.getUnderlyingImgLoader();
		final SequenceDescription underlyingSD = splitImgLoader.underlyingSequenceDescription();
		final Map< ViewId, ViewDescription > underlyingViewDescription = underlyingSD.getViewDescriptions();

		// Cross-view nail donations are computed globally so both views' TPS see the same
		// render-space target at each shared corner. The legacy per-view nail logic placed
		// single-view anchors at each split's own affine corner, which broke the tie.
		final Map< ViewId, List< DonatedNail > > nailDonations = anchorOverlapCorners
				? computeCrossViewNailDonations(
						splitImgLoader, old2newSetupId, splitViewRegistrations, underlyingViewIds,
						anisotropyFactor, Double.NaN,
						viewInterestPoints, correspondenceLabel, minNumCorrespondences,
						cornerCoverageRadius, seamSamplesPerAxis,
						seamSamplesScheduleThresholds, seamSamplesScheduleValues, landmarkVisitor )
				: java.util.Collections.emptyMap();

		// the coefficients (source > target) for each underlying view, used to construct the TPS
		final Map< ViewId, Landmarks > underlyingViewLandmarks = new HashMap<>();
		for ( final ViewId underlyingViewId : underlyingViewIds )
		{
			final Landmarks landmarks = getCoefficients( splitImgLoader, old2newSetupId, splitViewRegistrations,
					underlyingViewId, anisotropyFactor, Double.NaN,
					viewInterestPoints, correspondenceLabel, minNumCorrespondences,
					anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis,
					seamSamplesScheduleThresholds, seamSamplesScheduleValues, landmarkVisitor,
					nailDonations.get( underlyingViewId ) );
			underlyingViewLandmarks.put( underlyingViewId, landmarks );
		}

		final Comparator< ViewId > fusionOrder = fusionMap == null
				? null
				: Comparator.comparingInt( c -> fusionMap.get( c.getViewSetupId() ) );

		return BlkThinPlateSplineFusion.init(
				converter,
				underlyingImgLoader,
				underlyingViewIds,
				underlyingViewDescription,
				underlyingViewLandmarks,
				fusionType,
				anisotropyFactor,
				interpolationMethod,
				fusionOrder,
				intensityAdjustmentCoefficients,
				fusionInterval,
				type,
				blockSize );
	}

	/**
	 * Same as {@link #init} but accepts pre-built per-underlying-view dfields and approximate
	 * affines (e.g. loaded from disk after a distributed Phase-1.5 materialization).
	 * Skips per-task TPS rasterization and landmark computation.
	 *
	 * @param viewBounds
	 * 		back-projected bounding box (render coordinates) per underlying view.
	 * @param rawDfields
	 * 		un-offset displacement fields per underlying view, as produced by
	 * 		{@code DisplacementFields.sample(tps, viewBounds.get(viewId), spacing)}.
	 * 		Must contain entries at least for all underlying views overlapping
	 * 		{@code fusionInterval}; extra entries are ignored. Must be backed by
	 * 		{@code ArrayImg} (perf requirement of {@code BlkThinPlateSplineFusion}).
	 * @param approximateAffines
	 * 		per-underlying-view affine that approximates the TPS, used downstream for
	 * 		blending-weight adjustment. Typically read from the dfield dataset's
	 * 		{@code approx_affine_row_major} attribute (populated by Phase 1.5).
	 */
	public static < T extends RealType< T > & NativeType< T >, D extends NativeType< D > & RealType< D > > BlockSupplier< T > initWithLoadedDfields(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final Map< ViewId, Interval > viewBounds,
			final Map< ViewId, TransformedDisplacementField< D > > rawDfields,
			final FusionGUI.FusionType fusionType,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize,
			final Map< ViewId, AffineTransform3D > approximateAffines )
	{
		if ( approximateAffines == null )
			throw new IllegalArgumentException(
					"approximateAffines must be non-null; SparkFusion Phase 1.5 is expected to have populated "
					+ "the 'approx_affine_row_major' attribute on each dfield dataset." );

		final List< ViewId > underlyingViewIds = underlyingViewIds( splitViewIdsInput, splitImgLoader.new2oldSetupId() );
		final Map< Integer, List< Integer > > old2newSetupId = old2newSetupId( splitImgLoader.new2oldSetupId() );
		final List< ViewId > splitViewIds = splitViewIds( underlyingViewIds, old2newSetupId );

		if ( BlkAffineFusion.is2d( splitViewIds, splitViewDescriptions ) )
			throw new UnsupportedOperationException( "BlkThinPlateSplineFusion: 2D fusion not supported." );

		final ViewerImgLoader underlyingImgLoader = splitImgLoader.getUnderlyingImgLoader();
		final SequenceDescription underlyingSD = splitImgLoader.underlyingSequenceDescription();
		final Map< ViewId, ViewDescription > underlyingViewDescription = underlyingSD.getViewDescriptions();

		final Comparator< ViewId > fusionOrder = fusionMap == null
				? null
				: Comparator.comparingInt( c -> fusionMap.get( c.getViewSetupId() ) );

		return BlkThinPlateSplineFusion.initWithLoadedDfields(
				converter,
				underlyingImgLoader,
				underlyingViewIds,
				underlyingViewDescription,
				approximateAffines,
				viewBounds,
				rawDfields,
				fusionType,
				anisotropyFactor,
				interpolationMethod,
				fusionOrder,
				intensityAdjustmentCoefficients,
				fusionInterval,
				type,
				blockSize );
	}

	public static List<ViewId> underlyingViewIds( final Collection< ? extends ViewId > splitViewIds, final Map< Integer, Integer > new2oldSetupId )
	{
		return splitViewIds.stream()
				.map( splitViewId ->
					new ViewId( splitViewId.getTimePointId(), new2oldSetupId.get( splitViewId.getViewSetupId() ) ) )
				.distinct()
				.collect( Collectors.toList());
	}

	public static Map<Integer, List<Integer>> old2newSetupId( final Map<Integer, Integer> new2oldSetupId )
	{
		final Map< Integer, List< Integer > > old2newSetupId = new HashMap<>();

		new2oldSetupId.forEach( ( k, v ) -> old2newSetupId.computeIfAbsent( v, newKey -> new ArrayList<>() ).add( k ) );

		return old2newSetupId;
	}

	public static List<ViewId> splitViewIds( final Collection< ? extends ViewId > underlyingViewIds, final Map< Integer, List<Integer>> old2newSetupId )
	{
		return underlyingViewIds.stream().flatMap( underlyingViewId ->
			old2newSetupId.get( underlyingViewId.getViewSetupId() ).stream().map( splitSetupId ->
				new ViewId( underlyingViewId.getTimePointId(), splitSetupId ) )
		).collect( Collectors.toList());
	}

	public static ViewerImgLoader getUnderlyingImageLoader( final SpimData2 data )
	{
		if ( SplitViewerImgLoader.class.isInstance( data.getSequenceDescription().getImgLoader() ) )
			return ( ( SplitViewerImgLoader ) data.getSequenceDescription().getImgLoader() ).getUnderlyingImgLoader();
		else
			return null;
	}

	/**
	 * Backward-compatible overload — center landmarks only (no
	 * correspondence-based midpoint landmarks).
	 */
	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling )
	{
		return getCoefficients( splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId,
				anisotropyFactor, downsampling, null, null, 0, false, 0.0, 2, null );
	}

	/**
	 * Backward-compatible overload — correspondence midpoints but no corner anchors.
	 */
	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences )
	{
		return getCoefficients( splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId,
				anisotropyFactor, downsampling, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				false, 0.0, 2, null );
	}

	/**
	 * Backward-compatible overload — corner anchors with N=2 (corners only).
	 */
	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,
			final double cornerCoverageRadius )
	{
		return getCoefficients( splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId,
				anisotropyFactor, downsampling, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				anchorOverlapCorners, cornerCoverageRadius, 2, null );
	}

	/**
	 * Backward-compatible overload — no landmark visitor.
	 */
	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,
			final double cornerCoverageRadius,
			final int seamSamplesPerAxis )
	{
		return getCoefficients( splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId,
				anisotropyFactor, downsampling, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis, null, null, null );
	}

	/**
	 * Backward-compatible overload — visitor only, no per-split schedule, no
	 * donated nails. The {@code anchorOverlapCorners}/{@code cornerCoverageRadius}/
	 * {@code seamSamplesPerAxis} params are accepted for signature stability
	 * but no longer cause per-view nail emission here — call
	 * {@link #computeCrossViewNailDonations} at the driver and pass the result
	 * via the new overload's {@code donatedNails} parameter.
	 */
	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,
			final double cornerCoverageRadius,
			final int seamSamplesPerAxis,
			final Consumer< LandmarkRecord > landmarkVisitor )
	{
		return getCoefficients( splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId,
				anisotropyFactor, downsampling, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis, null, null, landmarkVisitor, null );
	}

	/**
	 * Backward-compatible overload — no donated nails. Centers + correspondence
	 * midpoints only. Cross-view nails must be supplied via the new overload's
	 * {@code donatedNails} parameter (see {@link #computeCrossViewNailDonations}).
	 */
	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,
			final double cornerCoverageRadius,
			final int seamSamplesPerAxis,
			final int[] seamSamplesScheduleThresholds,
			final int[] seamSamplesScheduleValues,
			final Consumer< LandmarkRecord > landmarkVisitor )
	{
		return getCoefficients( splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId,
				anisotropyFactor, downsampling, viewInterestPoints, correspondenceLabel, minNumCorrespondences,
				anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis,
				seamSamplesScheduleThresholds, seamSamplesScheduleValues, landmarkVisitor, null );
	}

	/**
	 * Build TPS Landmarks for one underlying view {@code U}. Always emits one
	 * center landmark per split sub-view of {@code U} (positions in
	 * underlying-view pixel coords; targets in render coords).
	 *
	 * If {@code viewInterestPoints != null} and {@code correspondenceLabel != null}
	 * additional cross-view "tie" landmarks are appended: for each split
	 * sub-view {@code S} of {@code U} and each partner split sub-view
	 * {@code S'} (from a different underlying view) with at least
	 * {@code minNumCorrespondences} corresponding interest points under
	 * {@code correspondenceLabel}, one midpoint landmark is added at
	 * {@code source = mean(p_S) + splitTranslation_S}, {@code target =
	 * 0.5*(model_S.apply(mean(p_S)) + model_S'.apply(mean(p_S')))}.
	 * The symmetric counterpart is added to {@code S'}'s underlying view in
	 * its own pass.
	 *
	 * <p>If {@code donatedNails} is non-null, each entry is appended as an
	 * additional landmark on U's TPS. Nail donations are computed globally by
	 * {@link #computeCrossViewNailDonations} so that the partner view's TPS
	 * sees a paired landmark pointing at the same render-space target —
	 * something the legacy per-view nail logic could not guarantee. The
	 * {@code anchorOverlapCorners}/{@code cornerCoverageRadius}/{@code
	 * seamSamplesPerAxis}/{@code seamSamplesScheduleXxx} parameters are kept
	 * in the signature for API stability but are no longer consulted here;
	 * the driver controls them via the donation pass.
	 */
	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final boolean anchorOverlapCorners,                    // no-op here; controls computeCrossViewNailDonations at the driver
			final double cornerCoverageRadius,                     // no-op here; see above
			final int seamSamplesPerAxis,                          // no-op here; see above
			final int[] seamSamplesScheduleThresholds,             // no-op here; see above
			final int[] seamSamplesScheduleValues,                 // no-op here; see above
			final Consumer< LandmarkRecord > landmarkVisitor,      // nullable; invoked once per emitted SPLIT_CENTER/MIDPOINT landmark
			final List< DonatedNail > donatedNails )               // nullable; per-recipient list from computeCrossViewNailDonations
	{
		final int n = 3;
		final int nSamplesFallback = Math.max( 2, seamSamplesPerAxis );
		final List<Integer> splitSetupIds = old2newSetupId.get( underlyingViewId.getViewSetupId() );

		final List< double[] > sourceList = new ArrayList<>( splitSetupIds.size() );
		final List< double[] > targetList = new ArrayList<>( splitSetupIds.size() );

		// Cache per-split-sub-view state we'll need again in the correspondence pass.
		final Map< Integer, AffineTransform3D > modelByLocalSplitSetupId = new HashMap<>();
		final Map< Integer, double[] > splitTranslationByLocalSetupId = new HashMap<>();

		// ----- Pass 1: one center landmark per split sub-view of U (existing logic). -----
		for ( final int splitViewSetupId : splitSetupIds )
		{
			final ViewId splitViewId = new ViewId( underlyingViewId.getTimePointId(), splitViewSetupId );

			final ViewRegistration vr = splitRegMap.get( splitViewId );
			final List< ViewTransform > vrList = vr.getTransformList();

			// just making sure this is the split transform
			if ( !vrList.get( vrList.size() - 1).getName().equals( SplittingTools.IMAGE_SPLITTING_NAME) )
				throw new RuntimeException( "Last transformation is not " + SplittingTools.IMAGE_SPLITTING_NAME + " for " + Group.pvid( splitViewId ) + ", stopping." );

			// this transformation puts the Zero-Min View of the underlying image where it actually is
			final ViewTransform splitTransform = vrList.get( vrList.size() - 1);

			// get the remaining model
			vr.updateModel();
			final AffineTransform3D model = vr.getModel().copy();

			// preserve anisotropy
			if ( !Double.isNaN( anisotropyFactor ) )
				TransformVirtual.scaleTransform( model, new double[] { 1.0, 1.0, 1.0/anisotropyFactor } );

			// downsampling
			if ( !Double.isNaN( downsampling ) )
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );

			modelByLocalSplitSetupId.put( splitViewSetupId, model );
			final double[] splitTranslation = new double[ n ];
			for ( int d = 0; d < n; ++d )
				splitTranslation[ d ] = splitTransform.asAffine3D().get( d, 3 );
			splitTranslationByLocalSetupId.put( splitViewSetupId, splitTranslation );

			// create a point in the middle of the Zero-Min View and the corresponding point in the global output space
			final Interval splitInterval = splitImgLoader.newSetupId2Interval().get( splitViewSetupId );

			final double[] p = new double[] { splitInterval.dimension( 0 ) / 2.0, splitInterval.dimension( 1 ) / 2.0, splitInterval.dimension( 2 ) / 2.0 };
			final double[] q = new double[ p.length ];
			model.apply( p, q );

			final double[] src = new double[ n ];
			for ( int d = 0; d < n; ++d )
				src[ d ] = p[ d ] + splitTranslation[ d ];
			sourceList.add( src );
			targetList.add( q );

			if ( landmarkVisitor != null )
				landmarkVisitor.accept( new LandmarkRecord( underlyingViewId, LandmarkRecord.TYPE_SPLIT_CENTER, src.clone(), q.clone() ) );
		}

		// ----- Pass 2: cross-view correspondence midpoint landmarks. -----
		int midpointCount = 0;
		// Per-split state needed by the optional Pass 3 (corner anchors).
		final Map< Integer, double[] > cmGlobalSumBySplit = new HashMap<>();      // running sum of p_S over all validated partner correspondences
		final Map< Integer, Integer > cmGlobalCountBySplit = new HashMap<>();     // count of contributing correspondences
		final Map< Integer, List< ViewId > > validatedPartnersBySplit = new HashMap<>();
		final Map< ViewId, AffineTransform3D > partnerModelCache = new HashMap<>();
		if ( viewInterestPoints != null && correspondenceLabel != null && minNumCorrespondences > 0 )
		{
			final Set< Integer > siblingSetupIds = new HashSet<>( splitSetupIds );

			// Caches for partner-view interest-point maps so each partner is hit once.
			final Map< ViewId, Map< Integer, InterestPoint > > partnerIpsCache = new HashMap<>();

			for ( final int splitViewSetupId : splitSetupIds )
			{
				final ViewId splitViewId = new ViewId( underlyingViewId.getTimePointId(), splitViewSetupId );
				final ViewInterestPointLists vipl_S = viewInterestPoints.getViewInterestPointLists( splitViewId );
				if ( vipl_S == null ) continue;
				final InterestPoints splitIps_S = vipl_S.getInterestPointList( correspondenceLabel );
				if ( splitIps_S == null ) continue;

				final Map< Integer, InterestPoint > ips_S = splitIps_S.getInterestPointsCopy();
				final Collection< CorrespondingInterestPoints > corrs = splitIps_S.getCorrespondingInterestPointsCopy();
				if ( corrs == null || corrs.isEmpty() ) continue;

				// Group correspondences by partner split-view id, skipping sibling splits of U.
				final Map< ViewId, List< CorrespondingInterestPoints > > byPartner = new HashMap<>();
				for ( final CorrespondingInterestPoints cip : corrs )
				{
					final ViewId partnerVid = cip.getCorrespondingViewId();
					if ( partnerVid.getTimePointId() == splitViewId.getTimePointId()
							&& siblingSetupIds.contains( partnerVid.getViewSetupId() ) )
						continue;
					byPartner.computeIfAbsent( partnerVid, k -> new ArrayList<>() ).add( cip );
				}

				for ( final Map.Entry< ViewId, List< CorrespondingInterestPoints > > entry : byPartner.entrySet() )
				{
					final List< CorrespondingInterestPoints > group = entry.getValue();
					if ( group.size() < minNumCorrespondences ) continue;
					final ViewId partnerVid = entry.getKey();
					final String partnerLabel = group.get( 0 ).getCorrespodingLabel();

					// Resolve partner IP map and model (cached).
					final Map< Integer, InterestPoint > ips_Sp = partnerIpsCache.computeIfAbsent( partnerVid, vid -> {
						final ViewInterestPointLists vipl_p = viewInterestPoints.getViewInterestPointLists( vid );
						if ( vipl_p == null ) return null;
						final InterestPoints ips = vipl_p.getInterestPointList( partnerLabel );
						return ips == null ? null : ips.getInterestPointsCopy();
					} );
					if ( ips_Sp == null ) continue;

					final AffineTransform3D model_Sp = partnerModelCache.computeIfAbsent( partnerVid, vid ->
							buildPartnerModel( splitRegMap, vid, anisotropyFactor, downsampling ) );
					if ( model_Sp == null ) continue;

					// CoM of correspondences in each split's zero-min coords.
					final double[] cm_S = new double[ n ];
					final double[] cm_Sp = new double[ n ];
					int count = 0;
					// Track the partner's contribution to S's global CoM separately so we can
					// roll it back if the group ends up below threshold after null-filtering.
					final double[] partnerSumOnS = new double[ n ];
					for ( final CorrespondingInterestPoints cip : group )
					{
						final InterestPoint p_S = ips_S.get( cip.getDetectionId() );
						final InterestPoint p_Sp = ips_Sp.get( cip.getCorrespondingDetectionId() );
						if ( p_S == null || p_Sp == null ) continue;
						final double[] l_S = p_S.getL();
						final double[] l_Sp = p_Sp.getL();
						for ( int d = 0; d < n; ++d )
						{
							cm_S[ d ] += l_S[ d ];
							cm_Sp[ d ] += l_Sp[ d ];
							partnerSumOnS[ d ] += l_S[ d ];
						}
						++count;
					}
					if ( count < minNumCorrespondences ) continue;
					for ( int d = 0; d < n; ++d )
					{
						cm_S[ d ] /= count;
						cm_Sp[ d ] /= count;
					}

					// Accumulate this partner's contribution into S's global CoM (for Pass 3).
					final double[] sumAccum = cmGlobalSumBySplit.computeIfAbsent( splitViewSetupId, k -> new double[ n ] );
					for ( int d = 0; d < n; ++d )
						sumAccum[ d ] += partnerSumOnS[ d ];
					cmGlobalCountBySplit.merge( splitViewSetupId, count, Integer::sum );
					validatedPartnersBySplit.computeIfAbsent( splitViewSetupId, k -> new ArrayList<>() ).add( partnerVid );

					// Map to render coords via each split's full model (incl. split transform).
					final AffineTransform3D model_S = modelByLocalSplitSetupId.get( splitViewSetupId );
					final double[] r_S = new double[ n ];
					final double[] r_Sp = new double[ n ];
					model_S.apply( cm_S, r_S );
					model_Sp.apply( cm_Sp, r_Sp );

					// Midpoint in render space.
					final double[] midpoint = new double[ n ];
					for ( int d = 0; d < n; ++d )
						midpoint[ d ] = 0.5 * ( r_S[ d ] + r_Sp[ d ] );

					// Source point in underlying-view pixel coords (same convention as centers).
					final double[] splitTranslation = splitTranslationByLocalSetupId.get( splitViewSetupId );
					final double[] src = new double[ n ];
					for ( int d = 0; d < n; ++d )
						src[ d ] = cm_S[ d ] + splitTranslation[ d ];

					sourceList.add( src );
					targetList.add( midpoint );
					++midpointCount;

					if ( landmarkVisitor != null )
						landmarkVisitor.accept( new LandmarkRecord( underlyingViewId, LandmarkRecord.TYPE_MIDPOINT, src.clone(), midpoint.clone() ) );
				}
			}

		}

		// ----- Pass 3: append cross-view nail donations (computed globally; see computeCrossViewNailDonations). -----
		// The legacy in-line Pass 3 emitted single-view nails at each split's own affine corners.
		// That broke the cross-view tie: each underlying view's pass placed its corner at its OWN
		// affine position, so two overlapping splits never agreed on the corner in render space.
		// Donations are now built globally so both views' TPS see paired landmarks at the same target.
		// LandmarkRecord visitor emission for TYPE_NAIL happens inside computeCrossViewNailDonations
		// (so the visitor sees each emitted nail exactly once); here we just append into U's lists.
		int nailCount = 0;
		if ( donatedNails != null && !donatedNails.isEmpty() )
		{
			for ( final DonatedNail nail : donatedNails )
			{
				sourceList.add( nail.src.clone() );
				targetList.add( nail.target.clone() );
				++nailCount;
			}
		}

		// Consolidated per-underlying-view landmark summary: always print, with explicit counts
		// for all three landmark categories. Makes it obvious from the log whether nails were
		// added (and how many), rather than silently omitting the line when count is zero.
		final StringBuilder sb = new StringBuilder( "[TPS] " ).append( Group.pvid( underlyingViewId ) )
				.append( " landmarks: " ).append( splitSetupIds.size() ).append( " centers" );
		if ( correspondenceLabel != null && minNumCorrespondences > 0 )
			sb.append( " + " ).append( midpointCount ).append( " correspondence midpoints (label='" )
					.append( correspondenceLabel ).append( "', minN=" ).append( minNumCorrespondences ).append( ")" );
		if ( donatedNails != null )
			sb.append( " + " ).append( nailCount ).append( " cross-view nail donations" );
		IOFunctions.println( sb.toString() );

		// Pack lists into the [n][N] layout expected by Landmarks.
		final int N = sourceList.size();
		final double[][] source = new double[ n ][ N ];
		final double[][] target = new double[ n ][ N ];
		for ( int i = 0; i < N; ++i )
		{
			final double[] srcI = sourceList.get( i );
			final double[] tgtI = targetList.get( i );
			for ( int d = 0; d < n; ++d )
			{
				source[ d ][ i ] = srcI[ d ];
				target[ d ][ i ] = tgtI[ d ];
			}
		}

		return new Landmarks( source, target );
	}

	/**
	 * Compute cross-view nail donations across all underlying views in one pass.
	 *
	 * <p>For each underlying view {@code U} and each split sub-view {@code S}
	 * of {@code U}, sample {@code S}'s surface (corners at {@code N=2}, edge
	 * midpoints + face centers at {@code N=3}, denser at higher {@code N})
	 * and, for each accepted sample that overlaps a partner split {@code S'}
	 * of {@code U'} (where {@code S} has at least {@code minNumCorrespondences}
	 * validated correspondences with {@code S'}), emit <em>two</em> donations
	 * pointing at the same render-space target {@code r_C = model_S(sampleZeroMin)}:
	 * <ul>
	 *   <li>One into {@code U}'s TPS: {@code src = sampleZeroMin + splitTr_S}.</li>
	 *   <li>One into {@code U'}'s TPS: {@code src = sampleInSp + splitTr_S'},
	 *       where {@code sampleInSp = model_S'.applyInverse(r_C)} — the same
	 *       physical render-space position expressed in S''s zero-min coords.</li>
	 * </ul>
	 *
	 * <p>This is the fix for the legacy per-view nail logic, which placed
	 * single-view anchors at each split's own affine corner and therefore did
	 * not pull overlapping underlying views to the same render-space point.
	 *
	 * @return per-recipient-underlying-view donations. Pass the entry for U
	 *         into {@code getCoefficients}'s {@code donatedNails} parameter.
	 */
	public static Map< ViewId, List< DonatedNail > > computeCrossViewNailDonations(
			final SplitViewerImgLoader splitImgLoader,
			final Map< Integer, List< Integer > > old2newSetupId,
			final Map< ViewId, ViewRegistration > splitRegMap,
			final Collection< ? extends ViewId > underlyingViewIds,
			final double anisotropyFactor,
			final double downsampling,
			final ViewInterestPoints viewInterestPoints,
			final String correspondenceLabel,
			final int minNumCorrespondences,
			final double cornerCoverageRadius,
			final int seamSamplesPerAxis,
			final int[] seamSamplesScheduleThresholds,
			final int[] seamSamplesScheduleValues,
			final Consumer< LandmarkRecord > landmarkVisitor )
	{
		final int n = 3;
		final int nSamplesFallback = Math.max( 2, seamSamplesPerAxis );
		final Map< ViewId, List< DonatedNail > > donations = new HashMap<>();

		if ( viewInterestPoints == null || correspondenceLabel == null || minNumCorrespondences <= 0 )
		{
			IOFunctions.println( "[TPS] cross-view nail donations: skipped"
					+ " (viewInterestPoints=" + ( viewInterestPoints == null ? "null" : "present" )
					+ ", correspondenceLabel=" + correspondenceLabel
					+ ", minNumCorrespondences=" + minNumCorrespondences + ")" );
			return donations;
		}

		// Per-split (= split sub-view setup id) caches, shared across all underlying-view passes.
		final Map< Integer, AffineTransform3D > modelBySplitSetupId = new HashMap<>();
		final Map< Integer, double[] > splitTranslationBySplitSetupId = new HashMap<>();
		final Map< Integer, ViewId > splitSetupIdToUnderlyingView = new HashMap<>();
		final Map< Integer, Interval > splitInterval = new HashMap<>();

		for ( final ViewId U : underlyingViewIds )
		{
			final List< Integer > splitSetupIds = old2newSetupId.get( U.getViewSetupId() );
			if ( splitSetupIds == null ) continue;
			for ( final int splitSetupId : splitSetupIds )
			{
				splitSetupIdToUnderlyingView.put( splitSetupId, U );
				final ViewId splitViewId = new ViewId( U.getTimePointId(), splitSetupId );

				final ViewRegistration vr = splitRegMap.get( splitViewId );
				if ( vr == null ) continue;
				final List< ViewTransform > vrList = vr.getTransformList();
				if ( !vrList.get( vrList.size() - 1 ).getName().equals( SplittingTools.IMAGE_SPLITTING_NAME ) )
					throw new RuntimeException( "Last transformation is not " + SplittingTools.IMAGE_SPLITTING_NAME
							+ " for " + Group.pvid( splitViewId ) + ", stopping." );
				final ViewTransform splitTransform = vrList.get( vrList.size() - 1 );
				vr.updateModel();
				final AffineTransform3D model = vr.getModel().copy();
				if ( !Double.isNaN( anisotropyFactor ) )
					TransformVirtual.scaleTransform( model, new double[] { 1.0, 1.0, 1.0 / anisotropyFactor } );
				if ( !Double.isNaN( downsampling ) )
					TransformVirtual.scaleTransform( model, 1.0 / downsampling );
				modelBySplitSetupId.put( splitSetupId, model );

				final double[] tr = new double[ n ];
				for ( int d = 0; d < n; ++d )
					tr[ d ] = splitTransform.asAffine3D().get( d, 3 );
				splitTranslationBySplitSetupId.put( splitSetupId, tr );

				final Interval iv = splitImgLoader.newSetupId2Interval().get( splitSetupId );
				if ( iv != null )
					splitInterval.put( splitSetupId, iv );
			}
		}

		final Map< ViewId, Map< Integer, InterestPoint > > partnerIpsCache = new HashMap<>();
		int totalDonations = 0;
		int totalNailSites = 0;

		// For each underlying view U: replicate the Pass 2 accumulation (to derive
		// global per-split correspondence CoMs + validated partners), then walk the
		// surface of each split and emit symmetric donations.
		for ( final ViewId U : underlyingViewIds )
		{
			final List< Integer > splitSetupIds = old2newSetupId.get( U.getViewSetupId() );
			if ( splitSetupIds == null ) continue;
			final Set< Integer > siblingSetupIds = new HashSet<>( splitSetupIds );

			final Map< Integer, double[] > cmGlobalSumBySplit = new HashMap<>();
			final Map< Integer, Integer > cmGlobalCountBySplit = new HashMap<>();
			final Map< Integer, List< ViewId > > validatedPartnersBySplit = new HashMap<>();

			for ( final int splitViewSetupId : splitSetupIds )
			{
				final ViewId splitViewId = new ViewId( U.getTimePointId(), splitViewSetupId );
				final ViewInterestPointLists vipl_S = viewInterestPoints.getViewInterestPointLists( splitViewId );
				if ( vipl_S == null ) continue;
				final InterestPoints splitIps_S = vipl_S.getInterestPointList( correspondenceLabel );
				if ( splitIps_S == null ) continue;

				final Map< Integer, InterestPoint > ips_S = splitIps_S.getInterestPointsCopy();
				final Collection< CorrespondingInterestPoints > corrs = splitIps_S.getCorrespondingInterestPointsCopy();
				if ( corrs == null || corrs.isEmpty() ) continue;

				final Map< ViewId, List< CorrespondingInterestPoints > > byPartner = new HashMap<>();
				for ( final CorrespondingInterestPoints cip : corrs )
				{
					final ViewId partnerVid = cip.getCorrespondingViewId();
					if ( partnerVid.getTimePointId() == splitViewId.getTimePointId()
							&& siblingSetupIds.contains( partnerVid.getViewSetupId() ) )
						continue;
					byPartner.computeIfAbsent( partnerVid, k -> new ArrayList<>() ).add( cip );
				}

				for ( final Map.Entry< ViewId, List< CorrespondingInterestPoints > > entry : byPartner.entrySet() )
				{
					final List< CorrespondingInterestPoints > group = entry.getValue();
					if ( group.size() < minNumCorrespondences ) continue;
					final ViewId partnerVid = entry.getKey();
					if ( modelBySplitSetupId.get( partnerVid.getViewSetupId() ) == null ) continue;
					final String partnerLabel = group.get( 0 ).getCorrespodingLabel();

					final Map< Integer, InterestPoint > ips_Sp = partnerIpsCache.computeIfAbsent( partnerVid, vid -> {
						final ViewInterestPointLists vipl_p = viewInterestPoints.getViewInterestPointLists( vid );
						if ( vipl_p == null ) return null;
						final InterestPoints ips = vipl_p.getInterestPointList( partnerLabel );
						return ips == null ? null : ips.getInterestPointsCopy();
					} );
					if ( ips_Sp == null ) continue;

					int count = 0;
					final double[] partnerSumOnS = new double[ n ];
					for ( final CorrespondingInterestPoints cip : group )
					{
						final InterestPoint p_S = ips_S.get( cip.getDetectionId() );
						final InterestPoint p_Sp = ips_Sp.get( cip.getCorrespondingDetectionId() );
						if ( p_S == null || p_Sp == null ) continue;
						final double[] l_S = p_S.getL();
						for ( int d = 0; d < n; ++d )
							partnerSumOnS[ d ] += l_S[ d ];
						++count;
					}
					if ( count < minNumCorrespondences ) continue;

					final double[] sumAccum = cmGlobalSumBySplit.computeIfAbsent( splitViewSetupId, k -> new double[ n ] );
					for ( int d = 0; d < n; ++d )
						sumAccum[ d ] += partnerSumOnS[ d ];
					cmGlobalCountBySplit.merge( splitViewSetupId, count, Integer::sum );
					validatedPartnersBySplit.computeIfAbsent( splitViewSetupId, k -> new ArrayList<>() ).add( partnerVid );
				}
			}

			// Surface sampling per split of U.
			for ( final int splitViewSetupId : splitSetupIds )
			{
				final List< ViewId > validatedPartners = validatedPartnersBySplit.get( splitViewSetupId );
				if ( validatedPartners == null || validatedPartners.isEmpty() ) continue;

				final Integer totalCount = cmGlobalCountBySplit.get( splitViewSetupId );
				if ( totalCount == null || totalCount.intValue() == 0 ) continue;
				final double[] sum = cmGlobalSumBySplit.get( splitViewSetupId );
				final double[] cm_S_global = new double[ n ];
				for ( int d = 0; d < n; ++d )
					cm_S_global[ d ] = sum[ d ] / totalCount;

				final AffineTransform3D model_S = modelBySplitSetupId.get( splitViewSetupId );
				final double[] splitTr_S = splitTranslationBySplitSetupId.get( splitViewSetupId );
				if ( model_S == null || splitTr_S == null ) continue;
				final Interval splitInterval_S = splitInterval.get( splitViewSetupId );
				if ( splitInterval_S == null ) continue;

				final double[] r_CoM = new double[ n ];
				model_S.apply( cm_S_global, r_CoM );

				final int nSamples = pickSamplesPerAxis(
						totalCount.intValue(),
						seamSamplesScheduleThresholds,
						seamSamplesScheduleValues,
						nSamplesFallback );

				for ( int i = 0; i < nSamples; ++i )
					for ( int j = 0; j < nSamples; ++j )
						for ( int k = 0; k < nSamples; ++k )
						{
							if ( i != 0 && i != nSamples - 1 && j != 0 && j != nSamples - 1 && k != 0 && k != nSamples - 1 )
								continue;
							final double[] sampleZeroMin = new double[ n ];
							final long[] dims = splitInterval_S.dimensionsAsLongArray();
							final int[] idx = { i, j, k };
							for ( int d = 0; d < n; ++d )
								sampleZeroMin[ d ] = idx[ d ] * ( dims[ d ] - 1 ) / ( double ) ( nSamples - 1 );
							final double[] r_C = new double[ n ];
							model_S.apply( sampleZeroMin, r_C );

							// Find a partner containing r_C; capture its sampleInSp for the
							// symmetric donation.
							ViewId sharedPartner = null;
							double[] sampleInSp = null;
							for ( final ViewId partnerVid : validatedPartners )
							{
								final AffineTransform3D model_Sp = modelBySplitSetupId.get( partnerVid.getViewSetupId() );
								if ( model_Sp == null ) continue;
								final Interval splitInterval_Sp = splitInterval.get( partnerVid.getViewSetupId() );
								if ( splitInterval_Sp == null ) continue;
								final double[] candidate = new double[ n ];
								model_Sp.applyInverse( candidate, r_C );
								boolean inside = true;
								for ( int d = 0; d < n; ++d )
									if ( candidate[ d ] < 0.0 || candidate[ d ] >= splitInterval_Sp.dimension( d ) )
									{
										inside = false;
										break;
									}
								if ( inside )
								{
									sharedPartner = partnerVid;
									sampleInSp = candidate;
									break;
								}
							}
							if ( sharedPartner == null ) continue;

							// Coverage check (skip nails too close to the correspondence CoM).
							double sq = 0.0;
							for ( int d = 0; d < n; ++d )
							{
								final double diff = r_C[ d ] - r_CoM[ d ];
								sq += diff * diff;
							}
							if ( Math.sqrt( sq ) <= cornerCoverageRadius )
								continue;

							++totalNailSites;

							// Self donation: U gets src = sampleZeroMin + splitTr_S, target = r_C.
							final double[] srcInU = new double[ n ];
							for ( int d = 0; d < n; ++d )
								srcInU[ d ] = sampleZeroMin[ d ] + splitTr_S[ d ];
							donations.computeIfAbsent( U, vid -> new ArrayList<>() )
									.add( new DonatedNail( srcInU, r_C.clone(), U ) );
							++totalDonations;
							if ( landmarkVisitor != null )
								landmarkVisitor.accept( new LandmarkRecord(
										U, U, LandmarkRecord.TYPE_NAIL,
										srcInU.clone(), r_C.clone() ) );

							// Cross-view donation: U' gets src = sampleInSp + splitTr_S', target = r_C.
							final ViewId partnerU = splitSetupIdToUnderlyingView.get( sharedPartner.getViewSetupId() );
							final double[] splitTr_Sp = splitTranslationBySplitSetupId.get( sharedPartner.getViewSetupId() );
							if ( partnerU != null && splitTr_Sp != null )
							{
								final double[] srcInPartnerU = new double[ n ];
								for ( int d = 0; d < n; ++d )
									srcInPartnerU[ d ] = sampleInSp[ d ] + splitTr_Sp[ d ];
								donations.computeIfAbsent( partnerU, vid -> new ArrayList<>() )
										.add( new DonatedNail( srcInPartnerU, r_C.clone(), U ) );
								++totalDonations;
								if ( landmarkVisitor != null )
									landmarkVisitor.accept( new LandmarkRecord(
											partnerU, U, LandmarkRecord.TYPE_NAIL,
											srcInPartnerU.clone(), r_C.clone() ) );
							}
						}
			}
		}

		IOFunctions.println( "[TPS] cross-view nail donations: " + totalNailSites
				+ " sites -> " + totalDonations + " landmarks across "
				+ donations.size() + " recipient view(s) (radius=" + cornerCoverageRadius
				+ ", samplesPerAxis fallback=" + nSamplesFallback + ")" );

		return donations;
	}

	private static AffineTransform3D buildPartnerModel(
			final Map< ViewId, ViewRegistration > splitRegMap,
			final ViewId splitViewId,
			final double anisotropyFactor,
			final double downsampling )
	{
		final ViewRegistration vr = splitRegMap.get( splitViewId );
		if ( vr == null ) return null;
		vr.updateModel();
		final AffineTransform3D model = vr.getModel().copy();
		if ( !Double.isNaN( anisotropyFactor ) )
			TransformVirtual.scaleTransform( model, new double[] { 1.0, 1.0, 1.0 / anisotropyFactor } );
		if ( !Double.isNaN( downsampling ) )
			TransformVirtual.scaleTransform( model, 1.0 / downsampling );
		return model;
	}

	/**
	 * Map a correspondence count to a samples-per-axis using the schedule.
	 * Schedule entries (sorted ascending by threshold): count <= thresholds[i] -> values[i].
	 * If no threshold matches (or schedule is null/empty), returns {@code fallback}.
	 */
	private static int pickSamplesPerAxis(
			final int count,
			final int[] thresholds,
			final int[] values,
			final int fallback )
	{
		if ( thresholds == null || values == null || thresholds.length == 0 )
			return fallback;
		final int len = Math.min( thresholds.length, values.length );
		for ( int i = 0; i < len; ++i )
			if ( count <= thresholds[ i ] )
				return Math.max( 2, values[ i ] );
		return fallback;
	}
}
