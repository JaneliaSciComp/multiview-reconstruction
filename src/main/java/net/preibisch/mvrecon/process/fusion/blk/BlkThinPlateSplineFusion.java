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
import java.util.Arrays;
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
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
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
import net.imglib2.realtransform.ThinplateSplineTransform;
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

		// Pre-fit per-view approximate affines from the landmarks (used for the bounding
		// boxes below, and downstream for blending-weight adjustment). Done once here so
		// initWithLoadedDfields no longer needs the full landmark map and can be driven
		// from a cached-affines container instead.
		final Map< ViewId, AffineTransform3D > approximateAffines = new HashMap<>();
		for ( final ViewId viewId : sortedViewIds )
		{
			final Landmarks landmarks = viewLandmarks.get( viewId );
			approximateAffines.put( viewId,
					fitAffineTransform( landmarks.getSourcePoints(), landmarks.getTargetPoints() ) );
		}

		// back-projected bounding box (render coordinates) for every underlying view
		final Map< ViewId, Interval > viewBounds = new HashMap<>();
		for ( final ViewId viewId : sortedViewIds )
		{
			final Landmarks landmarks = viewLandmarks.get( viewId );
			final ThinplateSplineTransform tps = new ThinplateSplineTransform(
					// we go from output to input
					landmarks.getTargetPoints(), landmarks.getSourcePoints() );
			final Dimensions dims = viewDimensions.get( viewId );
			viewBounds.put( viewId,
					inverseTransformedBoundingBox( tps, dims, approximateAffines.get( viewId ) ) );
		}

		// Sample the dfield (eager ArrayImg allocation) only for views that overlap
		// the fusionInterval. This preserves the historical optimization where
		// non-overlapping views never trigger a TPS rasterization.
		final Overlap preFilterOverlap = new Overlap( sortedViewIds, viewBounds, 3 ).filter( fusionInterval );
		final Map< ViewId, TransformedDisplacementField< DoubleType > > rawDfields = new HashMap<>();
		for ( final ViewId viewId : preFilterOverlap.getViewIds() )
		{
			final Landmarks landmarks = viewLandmarks.get( viewId );
			final ThinplateSplineTransform tps = new ThinplateSplineTransform(
					landmarks.getTargetPoints(), landmarks.getSourcePoints() );
			rawDfields.put( viewId, DisplacementFields.sample( tps, viewBounds.get( viewId ), spacing ) );
		}

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
	 * 		produced by {@link #inverseTransformedBoundingBox(ThinplateSplineTransform, Dimensions, AffineTransform3D)}.
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
		final Overlap overlap = new Overlap( sortedViewIds, viewBounds, 3 )
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
	 * Get the bounding box in render coordinates of an image of the given
	 * {@code dimension} when back-projected through the inverse of the given
	 * {@code transform} from rendered to image coordinates.
	 * <p>
	 * The inverse is imglib2's {@code InverseRealTransformGradientDescent}, seeded per
	 * corner with {@code approxAffine}. We drive it corner by corner instead of calling
	 * {@code boundingInterval(interval, CORNERS)}, because that convenience wrapper goes
	 * through {@code apply(s,t)}, which seeds the descent with the query point itself --
	 * an image coordinate, while the answer is in render space, potentially tens of
	 * thousands of pixels away. The descent then converges onto a spurious preimage and
	 * the bogus box silently drops the view from every block it should have contributed
	 * to. There is no other way to pass a guess: {@code setGuess} is a deprecated no-op.
	 * <p>
	 * A TPS from few landmarks is non-injective where the corners extrapolate, so a
	 * converged corner is not always the right one. A corner is used only if it converged
	 * and stayed near the affine estimate. If not, the affine estimate is used.
	 *
	 * @param transform
	 * 		forward transform (from rendered to image coordinates)
	 * @param dimensions
	 * 		image dimensions
	 * @param approxAffine
	 * 		affine approximation of the view's TPS, from {@link #fitAffineTransform};
	 * 		seeds the inverse at each corner and is the fallback for corners that fail
	 * 		the checks above
	 *
	 * @return bounding box in render coordinates
	 */
	public static Interval inverseTransformedBoundingBox(
			final ThinplateSplineTransform transform,
			final Dimensions dimensions,
			final AffineTransform3D approxAffine )
	{
		final InverseRealTransformGradientDescent optimizer =
				new WrappedIterativeInvertibleRealTransform<>( transform ).getOptimzer();

		// The imglib2 defaults, on purpose. A wider backtracking range (beta,
		// stepSizeMaxTries) converges more corners but lets the descent reach the
		// spurious preimages, which moved measured boxes by tens of thousands of pixels.
		final double tolerance = 0.5;
		final int maxIters = 100;

		final int n = dimensions.numDimensions();

		// A corner cannot be further from the affine estimate than the image is wide;
		// beyond that it is a different branch of the inverse. Measured: real deviations
		// reach ~266px on a ~4100px tile, wrong-branch ones start at ~46000px.
		double maxDeviation = 0;
		for ( int d = 0; d < n; ++d )
			maxDeviation = Math.max( maxDeviation, dimensions.dimension( d ) );

		final double[] min = new double[ n ];
		final double[] max = new double[ n ];
		Arrays.fill( min, Double.POSITIVE_INFINITY );
		Arrays.fill( max, Double.NEGATIVE_INFINITY );

		final double[] corner = new double[ n ];
		final double[] affine = new double[ n ];
		final double[] guess = new double[ n ];
		int numFallbacks = 0;

		for ( int c = 0; c < ( 1 << n ); ++c )
		{
			for ( int d = 0; d < n; ++d )
				corner[ d ] = ( ( c >> d ) & 1 ) == 0 ? 0 : dimensions.dimension( d ) - 1;

			// Hand the optimizer its own copy: affine is both the reference for the
			// distance check below and the fallback value, and how inverseTol treats its
			// guess argument is not part of imglib2's documented contract.
			approxAffine.apply( corner, affine );
			System.arraycopy( affine, 0, guess, 0, n );

			// the result is left in getEstimate()
			final double error = optimizer.inverseTol( corner, guess, tolerance, maxIters );
			final double[] estimate = optimizer.getEstimate();

			double deviation = 0;
			for ( int d = 0; d < n; ++d )
				deviation = Math.max( deviation, Math.abs( estimate[ d ] - affine[ d ] ) );

			final boolean trust = error < tolerance && deviation <= maxDeviation;
			if ( !trust )
				++numFallbacks;

			for ( int d = 0; d < n; ++d )
			{
				final double p = trust ? estimate[ d ] : affine[ d ];
				min[ d ] = Math.min( min[ d ], p );
				max[ d ] = Math.max( max[ d ], p );
			}
		}

		// System.out, not IOFunctions: IOFunctions.println defaults to routing through
		// SwingUtilities/IJ.log, which on a headless Spark executor touches AWT and can
		// drop the message.
		if ( numFallbacks > 0 )
			System.out.println( "[TPS] inverseTransformedBoundingBox: " + numFallbacks + " of "
					+ ( 1 << n ) + " corner(s) did not yield a trustworthy inverse; "
					+ "used the approximate affine for those." );

		return Intervals.smallestContainingInterval( new FinalRealInterval( min, max ) );
	}
}
