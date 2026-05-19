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
	 */
	public static final class LandmarkRecord
	{
		public static final String TYPE_CENTER = "center";
		public static final String TYPE_MIDPOINT = "midpoint";
		public static final String TYPE_NAIL = "nail";

		public final ViewId underlyingViewId;
		public final String type;       // one of TYPE_*
		public final double[] source;   // length 3, underlying-view pixel coords
		public final double[] target;   // length 3, render coords

		public LandmarkRecord( final ViewId underlyingViewId, final String type, final double[] source, final double[] target )
		{
			this.underlyingViewId = underlyingViewId;
			this.type = type;
			this.source = source;
			this.target = target;
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

		// the coefficients (source > target) for each underlying view, used to construct the TPS
		final Map< ViewId, Landmarks > underlyingViewLandmarks = new HashMap<>();
		for ( final ViewId underlyingViewId : underlyingViewIds )
		{
			final Landmarks landmarks = getCoefficients( splitImgLoader, old2newSetupId, splitViewRegistrations,
					underlyingViewId, anisotropyFactor, Double.NaN,
					viewInterestPoints, correspondenceLabel, minNumCorrespondences,
					anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis,
					seamSamplesScheduleThresholds, seamSamplesScheduleValues, landmarkVisitor );
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
	 * Backward-compatible overload — visitor only, no per-split schedule.
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
				anchorOverlapCorners, cornerCoverageRadius, seamSamplesPerAxis, null, null, landmarkVisitor );
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
			final int[] seamSamplesScheduleThresholds,            // nullable; sorted ascending. count <= thresholds[i] -> values[i]
			final int[] seamSamplesScheduleValues,                 // nullable; parallel to thresholds; fallback = seamSamplesPerAxis
			final Consumer< LandmarkRecord > landmarkVisitor )    // nullable; invoked once per emitted landmark
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
				landmarkVisitor.accept( new LandmarkRecord( underlyingViewId, LandmarkRecord.TYPE_CENTER, src.clone(), q.clone() ) );
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

			if ( midpointCount > 0 )
				IOFunctions.println( "[TPS] " + Group.pvid( underlyingViewId )
						+ " landmarks: " + splitSetupIds.size() + " centers + "
						+ midpointCount + " correspondence midpoints (label='" + correspondenceLabel
						+ "', minN=" + minNumCorrespondences + ")" );
		}

		// ----- Pass 3: corner-anchor "nail" landmarks (optional). -----
		int cornerNailCount = 0;
		int minNailN = Integer.MAX_VALUE;
		int maxNailN = Integer.MIN_VALUE;
		if ( anchorOverlapCorners && !validatedPartnersBySplit.isEmpty() )
		{
			for ( final int splitViewSetupId : splitSetupIds )
			{
				final List< ViewId > validatedPartners = validatedPartnersBySplit.get( splitViewSetupId );
				if ( validatedPartners == null || validatedPartners.isEmpty() )
					continue;

				final Integer totalCount = cmGlobalCountBySplit.get( splitViewSetupId );
				if ( totalCount == null || totalCount.intValue() == 0 )
					continue;
				final double[] sum = cmGlobalSumBySplit.get( splitViewSetupId );
				final double[] cm_S_global = new double[ n ];
				for ( int d = 0; d < n; ++d )
					cm_S_global[ d ] = sum[ d ] / totalCount;

				final AffineTransform3D model_S = modelByLocalSplitSetupId.get( splitViewSetupId );
				final double[] splitTranslation = splitTranslationByLocalSetupId.get( splitViewSetupId );

				// CoM in render coords.
				final double[] r_CoM = new double[ n ];
				model_S.apply( cm_S_global, r_CoM );

				final Interval splitInterval_S = splitImgLoader.newSetupId2Interval().get( splitViewSetupId );

				// Per-split N: optional schedule maps total correspondence count -> N.
				// Schedule entries (sorted ascending by threshold): count <= thresholds[i] -> values[i].
				final int nSamples = pickSamplesPerAxis(
						totalCount.intValue(),
						seamSamplesScheduleThresholds,
						seamSamplesScheduleValues,
						nSamplesFallback );
				if ( nSamples < minNailN ) minNailN = nSamples;
				if ( nSamples > maxNailN ) maxNailN = nSamples;

				// Iterate the surface of S sampled at nSamples points per axis.
				// A point is on the surface iff at least one of (i, j, k) is at the boundary
				// (0 or nSamples-1). At nSamples=2, this collapses to the 8 corners exactly.
				for ( int i = 0; i < nSamples; ++i )
					for ( int j = 0; j < nSamples; ++j )
						for ( int k = 0; k < nSamples; ++k )
						{
							if ( i != 0 && i != nSamples - 1 && j != 0 && j != nSamples - 1 && k != 0 && k != nSamples - 1 )
								continue; // interior — not a surface sample
							final double[] sampleZeroMin = new double[ n ];
							final long[] dims = splitInterval_S.dimensionsAsLongArray();
							final int[] idx = { i, j, k };
							for ( int d = 0; d < n; ++d )
								sampleZeroMin[ d ] = idx[ d ] * ( dims[ d ] - 1 ) / ( double ) ( nSamples - 1 );
							final double[] r_C = new double[ n ];
							model_S.apply( sampleZeroMin, r_C );

							// "Shared with another underlying view?" check — short-circuit.
							boolean sharedWithPartner = false;
							for ( final ViewId partnerVid : validatedPartners )
							{
								final AffineTransform3D model_Sp = partnerModelCache.get( partnerVid );
								if ( model_Sp == null ) continue;
								final Interval splitInterval_Sp = splitImgLoader.newSetupId2Interval().get( partnerVid.getViewSetupId() );
								if ( splitInterval_Sp == null ) continue;
								final double[] sampleInSp = new double[ n ];
								model_Sp.applyInverse( sampleInSp, r_C );
								boolean inside = true;
								for ( int d = 0; d < n; ++d )
									if ( sampleInSp[ d ] < 0.0 || sampleInSp[ d ] >= splitInterval_Sp.dimension( d ) )
									{
										inside = false;
										break;
									}
								if ( inside )
								{
									sharedWithPartner = true;
									break;
								}
							}
							if ( !sharedWithPartner )
								continue;

							// Coverage check in render coords.
							double sq = 0.0;
							for ( int d = 0; d < n; ++d )
							{
								final double diff = r_C[ d ] - r_CoM[ d ];
								sq += diff * diff;
							}
							if ( Math.sqrt( sq ) <= cornerCoverageRadius )
								continue;

							// Emit nail: source = sampleZeroMin + splitTranslation, target = r_C.
							final double[] src = new double[ n ];
							for ( int d = 0; d < n; ++d )
								src[ d ] = sampleZeroMin[ d ] + splitTranslation[ d ];
							sourceList.add( src );
							targetList.add( r_C );
							++cornerNailCount;

							if ( landmarkVisitor != null )
								landmarkVisitor.accept( new LandmarkRecord( underlyingViewId, LandmarkRecord.TYPE_NAIL, src.clone(), r_C.clone() ) );
						}
			}

			if ( cornerNailCount > 0 )
			{
				final String label = ( minNailN == maxNailN )
						? ( minNailN == 2 ? " corner nails" : " surface nails (samplesPerAxis=" + minNailN + ")" )
						: " surface nails (samplesPerAxis in [" + minNailN + ".." + maxNailN + "] per schedule)";
				IOFunctions.println( "[TPS] " + Group.pvid( underlyingViewId )
						+ " landmarks: + " + cornerNailCount + label
						+ " (radius=" + cornerCoverageRadius + ")" );
			}
		}

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
