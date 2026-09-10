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

import static net.imglib2.algorithm.blocks.dfield.DisplacementFieldTransform.displacementFieldAffine;
import static net.imglib2.algorithm.blocks.transform.Transform.Interpolation.NEARESTNEIGHBOR;
import static net.imglib2.algorithm.blocks.transform.Transform.Interpolation.NLINEAR;
import static net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion.convertToOutputType;
import static net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion.extendInput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPositionable;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.algorithm.blocks.dfield.DisplacementFieldBlockSupplier;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields;
import net.imglib2.algorithm.blocks.dfield.DisplacementFields.TransformedDisplacementField;
import net.imglib2.algorithm.blocks.transform.Transform.Interpolation;
import net.imglib2.converter.Converter;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.interval.IntervalSamplingMethod;
import net.imglib2.realtransform.inverse.InverseRealTransformGradientDescent;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.ConstantUtils;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.blk.tps.BlendingFunction3D;
import net.preibisch.mvrecon.process.fusion.blk.tps.DistanceFunction3D;
import net.preibisch.mvrecon.process.fusion.tps.Landmarks;
import net.preibisch.mvrecon.process.fusion.blk.tps.MaskingFunction3D;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.intensity.FastLinearIntensityMap;
import net.preibisch.mvrecon.process.fusion.lazy.LazyFusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class BlkThinPlateSplineFusion
{
	// TODO: the default expansion should be computed from the difference of the split overlap and underlying overlap
	public static int defaultIntervalExpansion = LazyFusionTools.defaultNonrigidExpansion;

	// TODO: this should probably be settable somewhere
	public static final double[] defaultDisplacementFieldSpacing = { 8, 8, 8 };

	/**
	 * TODO javadoc
	 *
	 * @param converter
	 * 		converts from FloatType to the output type. Maybe null,
	 * 		in which case a default converter (clamp to output type range) is used.
	 * @param imgLoader
	 * 		TODO
	 * @param viewIds
	 * 		TODO
	 * @param viewDescriptions
	 * 		TODO
	 * @param viewLandmarks
	 * 		TODO
	 * @param fusionType
	 * 		how to combine pixels
	 * @param anisotropyFactor
	 * @param interpolationMethod
	 * 		1==linear, 0==nearest neighbor
	 * @param intensityAdjustmentCoefficients
	 * 		intensity adjustments, can be null
	 * @param fusionInterval
	 * 		TODO
	 * @param type
	 * 		instance of the output type
	 * @param blockSize
	 * 		TODO
	 * @param <T>
	 * 		output type
	 *
	 * @return
	 */
	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final BasicImgLoader imgLoader,
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Map< ViewId, Landmarks > viewLandmarks,
			final FusionType fusionType,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Comparator< ViewId > fusionOrder, // old setupId > new setupId for fusion order, only makes sense with FusionType.FIRST_LOW or FusionType.FIRST_HIGH
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients, // from underlying viewids
			final Interval fusionInterval,  // already adjusted for anisotropy???
			final T type,
			final int[] blockSize )
	{
		final Map< ViewId, Dimensions > viewDimensions = LazyFusionTools.assembleDimensions( viewIds, viewDescriptions );
		final double[] spacing = defaultDisplacementFieldSpacing;

		final List< ? extends ViewId > sortedViewIds = new ArrayList<>( viewIds );
		sortedViewIds.sort( fusionOrder != null ? fusionOrder : Comparator.naturalOrder() );

		// Per view: TPS (render -> image), approximate affine (image -> render) and the
		// back-projected bounding box (render coordinates). The affines seed the iterative
		// TPS inverse for the bounding boxes and are used downstream for blending-weight
		// adjustment. Done once here so initWithLoadedDfields no longer needs the full
		// landmark map and can be driven from a cached-affines container instead.
		final Map< ViewId, ThinplateSplineTransform > tpsMap = new HashMap<>();
		final Map< ViewId, AffineTransform3D > approximateAffines = new HashMap<>();
		final Map< ViewId, Interval > viewBounds = new HashMap<>();
		for ( final ViewId viewId : sortedViewIds )
		{
			final TpsSetup setup = setupTps( viewLandmarks.get( viewId ), viewDimensions.get( viewId ) );
			tpsMap.put( viewId, setup.tps );
			approximateAffines.put( viewId, setup.approximateAffine );
			viewBounds.put( viewId, setup.boundingBox );
		}

		// Sample the dfield (eager ArrayImg allocation) only for views that overlap
		// the fusionInterval. This preserves the historical optimization where
		// non-overlapping views never trigger a TPS rasterization.
		final Overlap preFilterOverlap = new Overlap( sortedViewIds, viewBounds, defaultIntervalExpansion, 3 ).filter( fusionInterval );
		final Map< ViewId, TransformedDisplacementField< DoubleType > > rawDfields = new HashMap<>();
		for ( final ViewId viewId : preFilterOverlap.getViewIds() )
			rawDfields.put( viewId, DisplacementFields.sample( tpsMap.get( viewId ), viewBounds.get( viewId ), spacing ) );

		return initWithLoadedDfields(
				converter, imgLoader, viewIds, viewDescriptions, approximateAffines,
				viewBounds, rawDfields,
				fusionType, anisotropyFactor, interpolationMethod,
				fusionOrder, intensityAdjustmentCoefficients, fusionInterval,
				type, blockSize );
	}

	/**
	 * Entry point that accepts pre-sampled (un-offset) displacement fields
	 * instead of building them from landmarks. Use this when the dfields are
	 * computed elsewhere (e.g. distributed via Spark and cached on disk) and
	 * loaded back here as full {@code ArrayImg}s. The per-block evaluation
	 * downstream is perf-tuned for {@code ArrayImg}-backed dfields, so always
	 * pass arrays (not lazy {@code CachedCellImg}s).
	 *
	 * @param viewBounds
	 * 		back-projected bounding box (render coordinates) per view, as
	 * 		produced by {@link #setupTps(Landmarks, Dimensions)} (i.e. by
	 * 		{@link #inverseTransformedBoundingBox(ThinplateSplineTransform, RealTransform, Dimensions)}).
	 * @param rawDfields
	 * 		un-offset displacement fields per view, as produced by
	 * 		{@code DisplacementFields.sample(tps, viewBounds.get(viewId), spacing)}.
	 * 		Must contain entries at least for all views overlapping {@code fusionInterval};
	 * 		extra entries (for non-overlapping views) are ignored.
	 */
	public static < T extends RealType< T > & NativeType< T >, D extends NativeType< D > & RealType< D > > BlockSupplier< T > initWithLoadedDfields(
			final Converter< FloatType, T > converter,
			final BasicImgLoader imgLoader,
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Map< ViewId, AffineTransform3D > approximateAffines,
			final Map< ViewId, Interval > viewBounds,
			final Map< ViewId, TransformedDisplacementField< D > > rawDfields,
			final FusionType fusionType,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Comparator< ViewId > fusionOrder,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize )
	{
		final Map< ViewId, Dimensions > viewDimensions = LazyFusionTools.assembleDimensions( viewIds, viewDescriptions );
		final Interpolation interpolation = ( interpolationMethod == 1 ) ? NLINEAR : NEARESTNEIGHBOR;

		final List< ? extends ViewId > sortedViewIds = new ArrayList<>( viewIds );
		sortedViewIds.sort( fusionOrder != null ? fusionOrder : Comparator.naturalOrder() );

		// Which views to process (use un-altered bounding box and registrations).
		// Final filtering happens per Cell. Here we just pre-filter everything
		// outside the fusionInterval.
		final Overlap overlap = new Overlap( sortedViewIds, viewBounds, defaultIntervalExpansion, 3 )
				.filter( fusionInterval )
				.offset( fusionInterval.minAsLongArray() );

		// No view's displacement-field bounds intersect the fusionInterval. This can happen
		// when an upstream (affine + overlapExpansion) pre-filter selects a view for a block,
		// but the precise per-view dfield bounds filtered here drop all of them. The reducers
		// (MaxIntensity/WeightedAverage/...) assume >=1 image and would throw on get(0), so
		// return a constant-zero (background) block supplier instead.
		if ( overlap.numViews() == 0 )
			return emptyBlockSupplier( type, fusionInterval, blockSize );

		final List< BlockSupplier< FloatType > > images = new ArrayList<>( overlap.numViews() );
		final List< BlockSupplier< FloatType > > weights = new ArrayList<>( overlap.numViews() );
		final List< BlockSupplier< UnsignedByteType > > masks = new ArrayList<>( overlap.numViews() );

		for ( final ViewId viewId : overlap.getViewIds() )
		{
			final TransformedDisplacementField< D > dfield = concatenateBoundingBoxOffset( rawDfields.get( viewId ), fusionInterval );

			final Coefficients coefficients = intensityAdjustmentCoefficients == null ? null : intensityAdjustmentCoefficients.get( viewId );

			// TODO: support loading downsampled images, but this means we will need to update the source[][] coefficients too
			final RandomAccessibleInterval inputImg =
					imgLoader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

			final BlockSupplier< FloatType > viewBlocks = transformedBlocks(
					Cast.unchecked( inputImg ),
					coefficients,
					dfield, interpolation );
			images.add( viewBlocks );

			final float[] blending = Util.getArrayFromValue( FusionTools.defaultBlendingRange, 3 );
			final float[] border = Util.getArrayFromValue( FusionTools.defaultBlendingBorder, 3 );

			// Approximate affine transform for adjusting blending weights — supplied by caller
			// (either fit from landmarks by init(), or loaded from the dfield N5 cache by Spark
			// Phase 2). Saves re-loading correspondences for every fusion block.
			// Note: this is from source to target points, whereas TPS is from target to source.
			final AffineTransform3D approximateAffine = approximateAffines.get( viewId );

			FusionTools.adjustBlending( viewDimensions.get( viewId ), Group.pvid( viewId ), blending, border, approximateAffine );

			switch ( fusionType )
			{
			case AVG:
				weights.add( createMasking( inputImg, border, dfield, new FloatType() ) );
				break;
			case MAX_INTENSITY:
			case LOWEST_VIEWID_WINS:
			case HIGHEST_VIEWID_WINS:
				masks.add( createMasking( inputImg, border, dfield, new UnsignedByteType() ) );
				break;
			case AVG_BLEND:
				weights.add( createBlending( inputImg, border, blending, dfield ) );
				break;
			case CLOSEST_PIXEL_WINS:
				// squared distance to the view center; smallest distance wins
				weights.add( createDistance( inputImg, dfield ) );
				break;
			case AVG_BLEND_CONTENT:
			case AVG_CONTENT:
				throw new UnsupportedOperationException();
			default:
				throw new IllegalStateException();
			}
		}

		final BlockSupplier< FloatType > floatBlocks;
		switch ( fusionType )
		{
		case AVG:
		case AVG_CONTENT:
		case AVG_BLEND_CONTENT:
		case AVG_BLEND:
			floatBlocks = WeightedAverage.of( images, weights, overlap );
			break;
		case MAX_INTENSITY:
			floatBlocks = MaxIntensity.of( images, masks, overlap );
			break;
		case LOWEST_VIEWID_WINS:
			floatBlocks = LowestViewIdWins.of( images, masks, overlap );
			break;
		case HIGHEST_VIEWID_WINS:
			floatBlocks = HighestViewIdWins.of( images, masks, overlap );
			break;
		case CLOSEST_PIXEL_WINS:
			floatBlocks = ClosestPixelWins.of( images, weights, overlap );
			break;
		default:
			throw new IllegalStateException();
		}

		return convertToOutputType( floatBlocks, converter, type ).tile( blockSize );
	}

	/**
	 * A {@link BlockSupplier} that returns zero (background) everywhere over the
	 * (zero-min) {@code fusionInterval}. Used when no view's displacement-field bounds
	 * intersect the fusion interval, so there is nothing to render.
	 */
	private static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > emptyBlockSupplier(
			final T type,
			final Interval fusionInterval,
			final int[] blockSize )
	{
		final T zero = type.createVariable();
		zero.setZero();
		return BlockSupplier.of(
				Views.zeroMin( ConstantUtils.constantRandomAccessibleInterval( zero, fusionInterval ) ) )
				.tile( blockSize );
	}

	public static < D extends NativeType< D > & RealType< D > > TransformedDisplacementField< D > concatenateBoundingBoxOffset(
			final TransformedDisplacementField< D > dfield,
			final Interval boundingBoxInTarget )
	{
		final AffineTransform3D transformFromField = new AffineTransform3D();
		final double[] translationVector = {
				-boundingBoxInTarget.min( 0 ),
				-boundingBoxInTarget.min( 1 ),
				-boundingBoxInTarget.min( 2 )
		};
		transformFromField.setTranslation( translationVector );
		transformFromField.concatenate( dfield.transformFromField() );
		return new TransformedDisplacementField<>( transformFromField, dfield.displacementField() );
	}

	private static < T extends NativeType< T > > BlockSupplier< FloatType > transformedBlocks(
			final RandomAccessibleInterval< T > inputImg,
			final Coefficients coefficients,
			TransformedDisplacementField< ? > dfield,
			final Interpolation interpolation )
	{
		BlockSupplier< FloatType > blocks = BlockSupplier.of( extendInput( inputImg ) )
				.andThen( Convert.convert( new FloatType() ) );
		if ( coefficients != null )
			blocks = blocks.andThen( FastLinearIntensityMap.linearIntensityMap( coefficients, inputImg ) );
		return blocks.andThen( displacementFieldAffine( dfield.transformFromField(), dfield.displacementField(), interpolation ) );
	}

	private static < D extends NativeType< D > & RealType< D >, T extends NativeType< T > > BlockSupplier< T > createMasking(
			final Interval interval,
			final float[] border,
			final TransformedDisplacementField< D > transformedDisplacementField,
			final T maskType )
	{
		final AffineGet transformFromField = transformedDisplacementField.transformFromField();
		final DisplacementField< D > dfield = transformedDisplacementField.displacementField();
		return DisplacementFieldBlockSupplier.create( transformFromField, dfield,
				MaskingFunction3D.of( dfield.getType(), maskType, interval, border ) );
	}

	private static < D extends NativeType< D > & RealType< D > > BlockSupplier< FloatType > createBlending(
			final Interval interval,
			final float[] border,
			final float[] blending,
			final TransformedDisplacementField< D > transformedDisplacementField )
	{
		final AffineGet transformFromField = transformedDisplacementField.transformFromField();
		final DisplacementField< D > dfield = transformedDisplacementField.displacementField();
		return DisplacementFieldBlockSupplier.create( transformFromField, dfield,
				BlendingFunction3D.of( dfield.getType(), interval, border, blending ) );
	}

	private static < D extends NativeType< D > & RealType< D > > BlockSupplier< FloatType > createDistance(
			final Interval interval,
			final TransformedDisplacementField< D > transformedDisplacementField )
	{
		final AffineGet transformFromField = transformedDisplacementField.transformFromField();
		final DisplacementField< D > dfield = transformedDisplacementField.displacementField();
		return DisplacementFieldBlockSupplier.create( transformFromField, dfield,
				DistanceFunction3D.of( dfield.getType(), interval ) );
	}

	/**
	 * Fit an affine that approximates the TPS mapping from {@code source} to {@code target}
	 * landmarks. Used to derive the per-view "approximate affine" that drives blending-weight
	 * adjustment in {@link FusionTools#adjustBlending}. The same fit is invoked by
	 * {@link #init init} on the driver and (when caching is wired up) the result is persisted
	 * to N5 so that distributed block-fusion tasks don't have to recompute landmarks.
	 */
	public static AffineTransform3D fitAffineTransform( final double[][] source, final double[][] target )
	{
		final ArrayList< PointMatch > matches = new ArrayList<>();

		//final double[][] source = new double[3][splitSetupIds.size()];
		//final double[][] target = new double[3][splitSetupIds.size()];

		for ( int i = 0; i < source[ 0 ].length; ++i )
		{
			final Point s = new Point( new double[] { source[ 0 ][ i ], source[ 1 ][ i ], source[ 2 ][ i ] } );
			final Point t = new Point( new double[] { target[ 0 ][ i ], target[ 1 ][ i ], target[ 2 ][ i ] } );

			matches.add( new PointMatch( s, t ) );
		}

		final AffineModel3D m = new AffineModel3D();
		try
		{
			m.fit( matches );
		} catch (NotEnoughDataPointsException | IllDefinedDataPointsException e)
		{
			e.printStackTrace();
			throw new RuntimeException( e );
		}

		final double[][] mm = new double[ 3 ][ 4 ];
		m.toMatrix( mm );

		final AffineTransform3D t = new AffineTransform3D();

		t.set(
				mm[0][0], mm[0][1], mm[0][2], mm[0][3],
				mm[1][0], mm[1][1], mm[1][2], mm[1][3],
				mm[2][0], mm[2][1], mm[2][2], mm[2][3] );

		return t;
	}

	/**
	 * Everything fusion needs up front from one view's landmarks: the forward TPS
	 * (render to image coordinates), the affine approximating its inverse (image to
	 * render coordinates) and the back-projected bounding box in render coordinates.
	 * <p>
	 * Create instances with {@link #setupTps(Landmarks, Dimensions)}, so that all callers
	 * (the local {@link #init init} and the Spark displacement-field cache) agree on the
	 * transform directions and on seeding the iterative TPS inverse.
	 */
	public static final class TpsSetup
	{
		/** forward transform, from render to image coordinates */
		public final ThinplateSplineTransform tps;

		/** affine approximation of the <em>inverse</em> TPS, from image to render coordinates */
		public final AffineTransform3D approximateAffine;

		/** bounding box of the image in render coordinates */
		public final Interval boundingBox;

		TpsSetup( final ThinplateSplineTransform tps, final AffineTransform3D approximateAffine, final Interval boundingBox )
		{
			this.tps = tps;
			this.approximateAffine = approximateAffine;
			this.boundingBox = boundingBox;
		}
	}

	/**
	 * Build the per-view transforms from landmarks: the TPS from the landmark target
	 * points (render coordinates) to the source points (image coordinates), the affine
	 * fitted the other way round (image to render, see {@link #fitAffineTransform}), and
	 * the bounding box of the image in render coordinates from
	 * {@link #inverseTransformedBoundingBox(ThinplateSplineTransform, RealTransform, Dimensions)},
	 * with the affine seeding the iterative TPS inverse.
	 *
	 * @param landmarks
	 * 		source (image) and target (render) landmark coordinates of the view
	 * @param dimensions
	 * 		image dimensions of the view
	 *
	 * @return TPS, approximate affine and bounding box
	 */
	public static TpsSetup setupTps( final Landmarks landmarks, final Dimensions dimensions )
	{
		// we go from output to input
		final ThinplateSplineTransform tps = new ThinplateSplineTransform(
				landmarks.getTargetPoints(), landmarks.getSourcePoints() );

		// the approximate affine maps image -> render, i.e. it approximates the *inverse* TPS
		final AffineTransform3D approximateAffine = fitAffineTransform(
				landmarks.getSourcePoints(), landmarks.getTargetPoints() );

		final Interval boundingBox = inverseTransformedBoundingBox( tps, approximateAffine, dimensions );

		return new TpsSetup( tps, approximateAffine, boundingBox );
	}

	/**
	 * Get the bounding box in render coordinates of an image of the given
	 * {@code dimension} when back-projected through the inverse of the given
	 * {@code transform} from rendered to image coordinates.
	 * <p>
	 * The inverse is imglib2's iterative {@link InverseRealTransformGradientDescent}.
	 * Its {@code apply(s, t)} is implemented as {@code inverseTol(s, s, ...)}, i.e. the
	 * descent is seeded with the query point itself: an image coordinate, while the
	 * answer is a render coordinate that can be tens of thousands of pixels away. From
	 * there the descent converges onto a spurious preimage, and the resulting bogus box
	 * silently drops the view from every block it should have contributed to.
	 * {@code setGuess} is a deprecated no-op, so the only way to provide a seed is to
	 * call {@code inverseTol(target, guess, ...)} directly. That is what
	 * {@link GuessingRealTransform} does, using {@code invGuess} (the per-view
	 * approximate affine) to compute the seed for every corner.
	 * <p>
	 * A TPS from few landmarks is non-injective where the corners extrapolate, so a
	 * converged corner is not always the right one. A corner is used only if it
	 * converged and stayed within {@code max(dimensions)} of the guess; otherwise the
	 * guess itself is used.
	 *
	 * @param transform
	 * 		forward transform (from rendered to image coordinates)
	 * @param invGuess
	 * 		approximation of the <em>inverse</em> transform (from image to render
	 * 		coordinates), e.g. the affine from {@link #fitAffineTransform}. Seeds the
	 * 		iterative inverse at each corner and is the fallback for corners that fail
	 * 		the checks above.
	 * @param dimensions
	 * 		image dimensions
	 *
	 * @return bounding box in render coordinates
	 */
	public static Interval inverseTransformedBoundingBox(
			final ThinplateSplineTransform transform,
			final RealTransform invGuess,
			final Dimensions dimensions )
	{
		// A corner cannot be further from the guess than the image is wide; beyond
		// that it is a different branch of the inverse. Measured: real deviations
		// reach ~266px on a ~4100px tile, wrong-branch ones start at ~46000px.
		double maxDeviation = 0;
		for ( int d = 0; d < dimensions.numDimensions(); ++d )
			maxDeviation = Math.max( maxDeviation, dimensions.dimension( d ) );

		// transforms from source img pixels to render coordinates, seeded with invGuess
		final GuessingRealTransform invTransform = new GuessingRealTransform(
				new WrappedIterativeInvertibleRealTransform<>( transform ), invGuess, maxDeviation );

		// estimated bounding box of the source img transformed to render coordinates
		final Interval bb = Intervals.smallestContainingInterval(
				invTransform.boundingInterval(
						new FinalInterval( dimensions ),
						IntervalSamplingMethod.CORNERS ) );

		if ( invTransform.numFallbacks() > 0 )
			IOFunctions.println( "[TPS] inverseTransformedBoundingBox: " + invTransform.numFallbacks() + " of "
					+ invTransform.numApplied() + " corner(s) did not yield a trustworthy inverse; "
					+ "used the approximate affine for those." );

		return bb;
	}

	/**
	 * The inverse of an iteratively invertible transform (e.g. a TPS), where every
	 * inversion is seeded with a guess computed by a second transform instead of with
	 * the query point itself.
	 * <p>
	 * For a point {@code p}, {@link #apply} computes {@code guess = invGuess(p)} and then
	 * runs {@link InverseRealTransformGradientDescent#inverseTol(double[], double[], double, int)
	 * inverseTol(p, guess, tolerance, maxIters)}: the descent solves {@code forward(x) = p}
	 * starting at {@code guess}. Note that this is <em>not</em> the same as composing
	 * {@code invGuess} with {@code WrappedIterativeInvertibleRealTransform.inverse()}.
	 * That would end up in {@code inverseTol(guess, guess, ...)} and solve
	 * {@code forward(x) = guess}, i.e. invert a different point, still seeded with itself.
	 * <p>
	 * The solution is used only if the descent converged ({@code error < tolerance}) and
	 * it lies within {@code maxDeviation} (per dimension) of the guess; otherwise the
	 * guess itself is returned and {@link #numFallbacks()} is incremented.
	 * <p>
	 * Not thread-safe (the underlying optimizer is stateful); use {@link #copy()} per thread.
	 */
	public static class GuessingRealTransform implements RealTransform
	{
		// imglib2's InverseRealTransformGradientDescent defaults, on purpose. A wider
		// backtracking range (beta, stepSizeMaxTries) converges more corners but lets the
		// descent reach the spurious preimages, which moved measured boxes by tens of
		// thousands of pixels.
		public static final double DEFAULT_TOLERANCE = 0.5;

		public static final int DEFAULT_MAX_ITERS = 100;

		private final WrappedIterativeInvertibleRealTransform< ? > forward;

		private final InverseRealTransformGradientDescent optimizer;

		private final RealTransform invGuess;

		private final double maxDeviation;

		private final double tolerance;

		private final int maxIters;

		private final int n;

		private final double[] guess;

		private final double[] tmpSource;

		private final double[] tmpTarget;

		private int numApplied = 0;

		private int numFallbacks = 0;

		/**
		 * @param forward
		 * 		the (wrapped) forward transform to invert
		 * @param invGuess
		 * 		approximation of the inverse of {@code forward}; provides the seed and the fallback
		 * @param maxDeviation
		 * 		a solution further than this (in any dimension) from the guess is rejected
		 */
		public GuessingRealTransform(
				final WrappedIterativeInvertibleRealTransform< ? > forward,
				final RealTransform invGuess,
				final double maxDeviation )
		{
			this( forward, invGuess, maxDeviation, DEFAULT_TOLERANCE, DEFAULT_MAX_ITERS );
		}

		public GuessingRealTransform(
				final WrappedIterativeInvertibleRealTransform< ? > forward,
				final RealTransform invGuess,
				final double maxDeviation,
				final double tolerance,
				final int maxIters )
		{
			n = forward.numSourceDimensions();
			if ( forward.numTargetDimensions() != n )
				throw new IllegalArgumentException( "iterative inversion requires numSourceDimensions == numTargetDimensions" );
			if ( invGuess.numSourceDimensions() != n || invGuess.numTargetDimensions() != n )
				throw new IllegalArgumentException( "invGuess dimensionality does not match the forward transform" );

			this.forward = forward;
			this.optimizer = forward.getOptimzer();
			this.invGuess = invGuess;
			this.maxDeviation = maxDeviation;
			this.tolerance = tolerance;
			this.maxIters = maxIters;

			guess = new double[ n ];
			tmpSource = new double[ n ];
			tmpTarget = new double[ n ];
		}

		/** @return number of points inverted so far */
		public int numApplied()
		{
			return numApplied;
		}

		/** @return number of points for which the guess was returned instead of the descent result */
		public int numFallbacks()
		{
			return numFallbacks;
		}

		@Override
		public int numSourceDimensions()
		{
			return forward.numTargetDimensions();
		}

		@Override
		public int numTargetDimensions()
		{
			return forward.numSourceDimensions();
		}

		@Override
		public void apply( final double[] source, final double[] target )
		{
			// seed: where the guess transform puts this point
			invGuess.apply( source, guess );

			// solve forward(x) = source, starting at guess. inverseTol copies the guess but
			// keeps a reference to its target argument, so hand it source directly (source
			// is not modified here) and read the estimate before touching target.
			final double error = optimizer.inverseTol( source, guess, tolerance, maxIters );
			final double[] estimate = optimizer.getEstimate();

			double deviation = 0;
			for ( int d = 0; d < n; ++d )
				deviation = Math.max( deviation, Math.abs( estimate[ d ] - guess[ d ] ) );

			final boolean trust = error < tolerance && deviation <= maxDeviation;
			++numApplied;
			if ( !trust )
				++numFallbacks;

			// write last: source and target may be the same array (Corners.bounds calls apply(pt, pt))
			System.arraycopy( trust ? estimate : guess, 0, target, 0, n );
		}

		@Override
		public void apply( final RealLocalizable source, final RealPositionable target )
		{
			source.localize( tmpSource );
			apply( tmpSource, tmpTarget );
			target.setPosition( tmpTarget );
		}

		@Override
		public GuessingRealTransform copy()
		{
			return new GuessingRealTransform( forward.copy(), invGuess.copy(), maxDeviation, tolerance, maxIters );
		}
	}
}
