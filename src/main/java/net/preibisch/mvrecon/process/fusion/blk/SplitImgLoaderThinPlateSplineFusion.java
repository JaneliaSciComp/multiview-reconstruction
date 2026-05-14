package net.preibisch.mvrecon.process.fusion.blk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
				type, blockSize, null, null, 0 );
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
			final int minNumCorrespondences )            // ignored when label/vip is null
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
					viewInterestPoints, correspondenceLabel, minNumCorrespondences );
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
	 * Same as {@link #init} but accepts pre-built per-underlying-view dfields
	 * (e.g. loaded from disk after a distributed Phase-1.5 materialization).
	 * Skips per-task TPS rasterization.
	 *
	 * @param viewBounds
	 * 		back-projected bounding box (render coordinates) per underlying view.
	 * @param rawDfields
	 * 		un-offset displacement fields per underlying view, as produced by
	 * 		{@code DisplacementFields.sample(tps, viewBounds.get(viewId), spacing)}.
	 * 		Must contain entries at least for all underlying views overlapping
	 * 		{@code fusionInterval}; extra entries are ignored. Must be backed by
	 * 		{@code ArrayImg} (perf requirement of {@code BlkThinPlateSplineFusion}).
	 */
	public static < T extends RealType< T > & NativeType< T >, D extends NativeType< D > & RealType< D > > BlockSupplier< T > initWithLoadedDfields(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final Map< ViewId, Interval > viewBounds,
			final Map< ViewId, net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField< D > > rawDfields,
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
		return initWithLoadedDfields( converter, splitImgLoader, splitViewIdsInput, splitViewRegistrations,
				splitViewDescriptions, viewBounds, rawDfields, fusionType, intervalExpansion,
				anisotropyFactor, interpolationMethod, fusionMap, intensityAdjustmentCoefficients,
				fusionInterval, type, blockSize, null, null, 0 );
	}

	public static < T extends RealType< T > & NativeType< T >, D extends NativeType< D > & RealType< D > > BlockSupplier< T > initWithLoadedDfields(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final Map< ViewId, Interval > viewBounds,
			final Map< ViewId, net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField< D > > rawDfields,
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
		final List< ViewId > underlyingViewIds = underlyingViewIds( splitViewIdsInput, splitImgLoader.new2oldSetupId() );
		final Map< Integer, List< Integer > > old2newSetupId = old2newSetupId( splitImgLoader.new2oldSetupId() );
		final List< ViewId > splitViewIds = splitViewIds( underlyingViewIds, old2newSetupId );

		if ( BlkAffineFusion.is2d( splitViewIds, splitViewDescriptions ) )
			throw new UnsupportedOperationException( "BlkThinPlateSplineFusion: 2D fusion not supported." );

		final ViewerImgLoader underlyingImgLoader = splitImgLoader.getUnderlyingImgLoader();
		final SequenceDescription underlyingSD = splitImgLoader.underlyingSequenceDescription();
		final Map< ViewId, ViewDescription > underlyingViewDescription = underlyingSD.getViewDescriptions();

		// Landmarks per underlying view (still cheap to recompute on each executor).
		// Pass anisotropyFactor through so the Landmarks (and the downstream
		// fitAffineTransform / adjustBlending) match the dfield produced at Phase 1.5.
		// Pass viewInterestPoints + correspondenceLabel to add cross-view midpoint landmarks
		// at view boundaries (helps TPS converge across underlying-view seams).
		final Map< ViewId, Landmarks > underlyingViewLandmarks = new HashMap<>();
		for ( final ViewId underlyingViewId : underlyingViewIds )
		{
			final Landmarks landmarks = getCoefficients( splitImgLoader, old2newSetupId, splitViewRegistrations,
					underlyingViewId, anisotropyFactor, Double.NaN,
					viewInterestPoints, correspondenceLabel, minNumCorrespondences );
			underlyingViewLandmarks.put( underlyingViewId, landmarks );
		}

		final Comparator< ViewId > fusionOrder = fusionMap == null
				? null
				: Comparator.comparingInt( c -> fusionMap.get( c.getViewSetupId() ) );

		return BlkThinPlateSplineFusion.initWithLoadedDfields(
				converter,
				underlyingImgLoader,
				underlyingViewIds,
				underlyingViewDescription,
				underlyingViewLandmarks,
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
				anisotropyFactor, downsampling, null, null, 0 );
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
			final int minNumCorrespondences )
	{
		final int n = 3;
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
		}

		// ----- Pass 2: cross-view correspondence midpoint landmarks. -----
		int midpointCount = 0;
		if ( viewInterestPoints != null && correspondenceLabel != null && minNumCorrespondences > 0 )
		{
			final Set< Integer > siblingSetupIds = new HashSet<>( splitSetupIds );

			// Caches for partner-view interest-point maps so each partner is hit once.
			final Map< ViewId, Map< Integer, InterestPoint > > partnerIpsCache = new HashMap<>();
			final Map< ViewId, AffineTransform3D > partnerModelCache = new HashMap<>();

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
						}
						++count;
					}
					if ( count < minNumCorrespondences ) continue;
					for ( int d = 0; d < n; ++d )
					{
						cm_S[ d ] /= count;
						cm_Sp[ d ] /= count;
					}

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
				}
			}

			if ( midpointCount > 0 )
				IOFunctions.println( "[TPS] " + Group.pvid( underlyingViewId )
						+ " landmarks: " + splitSetupIds.size() + " centers + "
						+ midpointCount + " correspondence midpoints (label='" + correspondenceLabel
						+ "', minN=" + minNumCorrespondences + ")" );
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
}
