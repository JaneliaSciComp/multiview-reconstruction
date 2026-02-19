package net.preibisch.mvrecon.process.fusion.blk;

import static net.imglib2.algorithm.blocks.dfield.DisplacementFieldTransform.displacementFieldAffine;
import static net.imglib2.algorithm.blocks.transform.Transform.Interpolation.NEARESTNEIGHBOR;
import static net.imglib2.algorithm.blocks.transform.Transform.Interpolation.NLINEAR;
import static net.imglib2.util.Util.safeInt;
import static net.imglib2.view.fluent.RandomAccessibleIntervalView.Extension.zero;
import static net.imglib2.view.fluent.RandomAccessibleView.Interpolation.clampingNLinear;
import static net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion.convertToOutputType;
import static net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion.extendInput;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import mpicbg.models.AffineModel3D;
import mpicbg.models.IllDefinedDataPointsException;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.UnaryBlockOperator;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.algorithm.blocks.dfield.DisplacementFieldBlockSupplier;
import net.imglib2.algorithm.blocks.transform.Transform;
import net.imglib2.algorithm.blocks.transform.Transform.Interpolation;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.converter.Converter;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.interval.IntervalSamplingMethod;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.imglib2.view.composite.CompositeView;
import net.imglib2.view.composite.GenericComposite;
import net.imglib2.view.fluent.RealRandomAccessibleView;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.blk.tps.BlendingFunction3D;
import net.preibisch.mvrecon.process.fusion.blk.tps.DisplacementFields;
import net.preibisch.mvrecon.process.fusion.blk.tps.DisplacementFields.TransformedDisplacementField;
import net.preibisch.mvrecon.process.fusion.blk.tps.Landmarks;
import net.preibisch.mvrecon.process.fusion.blk.tps.MaskingFunction3D;
import net.preibisch.mvrecon.process.fusion.blk.tps.SampleTPS;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.intensity.FastLinearIntensityMap;
import net.preibisch.mvrecon.process.fusion.lazy.LazyFusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.weights.BlendingRealRandomAccessible;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.splitting.SplittingTools;
import util.BlockSupplierUtils;

public class BlkThinPlateSplineFusion
{
	// TODO: the default expansion should be computed from the difference of the split overlap and underlying overlap
	public static int defaultIntervalExpansion = LazyFusionTools.defaultNonrigidExpansion;

	/**
	 * TODO javadoc
	 *
	 *
	 *
	 * @param converter
	 * 		converts from FloatType to the output type. Maybe null,
	 * 		in which case a default converter (clamp to output type range) is used.
	 * @param splitImgLoader
	 * 		TODO
	 * @param splitViewIdsInput
	 * 		TODO
	 * @param splitViewRegistrations
	 * 		TODO
	 * @param splitViewDescriptions
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
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations, // already adjusted for anisotropy
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap, // old setupId > new setupId for fusion order, only makes sense with FusionType.FIRST_LOW or FusionType.FIRST_HIGH
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients, // from underlying viewids
			final Interval fusionInterval,  // already adjusted for anisotropy???
			final T type,
			final int[] blockSize )
	{
		// assemble all underlying viewIds (which will expand the list of splitViews to all the underlying viewids consist of)
		final List< ViewId > underlyingViewIds = underlyingViewIds( splitViewIdsInput, splitImgLoader.new2oldSetupId() );
		final Map<Integer, List<Integer>> old2newSetupId = old2newSetupId( splitImgLoader.new2oldSetupId() );
		final List< ViewId > splitViewIds = splitViewIds( underlyingViewIds, old2newSetupId );

		if ( BlkAffineFusion.is2d( splitViewIds, splitViewDescriptions ) )
			throw new UnsupportedOperationException( "BlkThinPlateSplineFusion: 2D fusion not supported." );

		final SequenceDescription underlyingSD = splitImgLoader.underlyingSequenceDescription();

		// to be able to use the "lowest ViewId" wins strategy
		final List< ? extends ViewId > sortedUnderlyingViewIds = new ArrayList<>( underlyingViewIds );

		if ( fusionMap == null || fusionMap.size() == 0 )
			Collections.sort( sortedUnderlyingViewIds );
		else
			Collections.sort( sortedUnderlyingViewIds, (c1,c2) -> Integer.compare( fusionMap.get( c1.getViewSetupId() ), fusionMap.get( c2.getViewSetupId() ) ) );

		//
		// build the overlap for the underlying views so they can be used later
		//

		// image dimensions for every underlying view
		// TODO: rename to "viewDimensions"
		final Map< ViewId, Dimensions > underlyingViewIdToDimensions = LazyFusionTools.assembleDimensions( sortedUnderlyingViewIds, underlyingSD.getViewDescriptions() );

		final Interpolation interpolation = ( interpolationMethod == 1 ) ? NLINEAR : NEARESTNEIGHBOR;

		// TODO: combine Map<ViewId, ...> maps into one single map with a value class holding everything
		//       These for all underlyingViewIds
		//         landmarks
		//         image dimensions
		//         bbox
		//       and these only for Overlap filtered ViewIds
		//         dfield
		//         approximate affine transform

		// the coefficients (source > target) for each underlying view, used to construct the TPS
		final Map< ViewId, Landmarks > underlyingViewIdToCoefficients = new HashMap<>();

		// back-projected bounding box (render coordinates) for every underlying view
		final Map< ViewId, Interval > underlyingViewIdToBoundingBox = new HashMap<>();

		for ( final ViewId underlyingViewId : sortedUnderlyingViewIds )
		{
			// ignore downsampling for now
			final Landmarks landmarks = getCoefficients( splitImgLoader, old2newSetupId, splitViewRegistrations, underlyingViewId, Double.NaN, Double.NaN );
			underlyingViewIdToCoefficients.put( underlyingViewId, landmarks );

			// TODO: we may want to put extra coefficients in the overlapping areas based on "real correspondences"

			final ThinplateSplineTransform tps = new ThinplateSplineTransform(
					// we go from output to input
					landmarks.getTargetPoints(), landmarks.getSourcePoints() );
			final Dimensions dims = underlyingViewIdToDimensions.get( underlyingViewId );
			final Interval bbox = SampleTPS.inverseTransformedBoundingBox( tps, dims );
			underlyingViewIdToBoundingBox.put( underlyingViewId, bbox );
		}

		// TODO: rename "overlap". (And also rename other "underlying..." things, if possible without causing confusion)
		final Overlap underlyingOverlap = new Overlap( sortedUnderlyingViewIds, underlyingViewIdToBoundingBox, 3 )
				.filter( fusionInterval )
				.offset( fusionInterval.minAsLongArray() );

		// TODO: should be an argument
		final double[] spacing = { 8, 8, 8 };

		final List< BlockSupplier< FloatType > > images = new ArrayList<>( underlyingOverlap.numViews() );
		final List< BlockSupplier< FloatType > > weights = new ArrayList<>( underlyingOverlap.numViews() );
		final List< BlockSupplier< UnsignedByteType > > masks = new ArrayList<>( underlyingOverlap.numViews() );


		for ( final ViewId viewId : underlyingOverlap.getViewIds() )
//		for ( final ViewId underlyingViewId : underlyingOverlap.getViewIds() )
		{
			final Landmarks landmarks = underlyingViewIdToCoefficients.get( viewId );
			final Interval bbox = underlyingViewIdToBoundingBox.get( viewId );

			final ThinplateSplineTransform tps = new ThinplateSplineTransform(
					// we go from output to input
					landmarks.getTargetPoints(), landmarks.getSourcePoints() );
			final TransformedDisplacementField< DoubleType > dfield = concatenateBoundingBoxOffset(
					DisplacementFields.sample( tps, bbox, spacing ),
					fusionInterval );

			final Coefficients coefficients = intensityAdjustmentCoefficients == null ? null : intensityAdjustmentCoefficients.get( viewId );

			// TODO: support loading downsampled images, but this means we will need to update the source[][] coefficients too
			final RandomAccessibleInterval inputImg =
					//DownsampleTools.openDownsampled( under, viewId, model, usedDownsampleFactors );
					splitImgLoader.getUnderlyingImgLoader().getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

			final BlockSupplier< FloatType > viewBlocks = transformedBlocks(
					Cast.unchecked( inputImg ),
					coefficients,
					dfield, interpolation );
			images.add( viewBlocks );

			// instantiate blending if necessary
			final float[] blending = Util.getArrayFromValue( FusionTools.defaultBlendingRange, 3 );
			final float[] border = Util.getArrayFromValue( FusionTools.defaultBlendingBorder, 3 );

			// Approximate affine transform for adjusting blending weights.
			// Note that this is from source to target points, whereas TPS is from target to source point!
			final AffineTransform3D approximateAffine = getTransform( landmarks.getSourcePoints(), landmarks.getTargetPoints() );

			// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
			FusionTools.adjustBlending( underlyingViewIdToDimensions.get( viewId ), Group.pvid( viewId ), blending, border, approximateAffine );

			// TODO support content-based
			// adjust content-based for downsampling
//			final double[] sigma1 = Util.getArrayFromValue( ContentBased.defaultContentBasedSigma1, 3 );
//			final double[] sigma2 = Util.getArrayFromValue( ContentBased.defaultContentBasedSigma2, 3 );
//			FusionTools.adjustContentBased( viewDescriptions.get( viewId ), sigma1, sigma2, usedDownsampleFactors, anisotropyFactor );

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
			case CLOSEST_PIXEL_WINS: // we need to use the blending weights, whatever weight is highest wins
				weights.add( createBlending( inputImg, border, blending, dfield ) );
				break;
			case AVG_BLEND_CONTENT:
			case AVG_CONTENT:
				// TODO support content-based
				throw new UnsupportedOperationException();
			default:
				// should never happen
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
			floatBlocks = WeightedAverage.of( images, weights, underlyingOverlap );
			break;
		case MAX_INTENSITY:
			floatBlocks = MaxIntensity.of( images, masks, underlyingOverlap );
			break;
		case LOWEST_VIEWID_WINS:
			floatBlocks = LowestViewIdWins.of( images, masks, underlyingOverlap );
			break;
		case HIGHEST_VIEWID_WINS:
			floatBlocks = HighestViewIdWins.of( images, masks, underlyingOverlap );
			break;
		case CLOSEST_PIXEL_WINS:
			floatBlocks = ClosestPixelWins.of( images, weights, underlyingOverlap );
			break;
		default:
			// should never happen
			throw new IllegalStateException();
		}

		final BlockSupplier< T > blocks = convertToOutputType(
				floatBlocks,
				converter, type )
				.tile( blockSize );

		//System.out.println( Util.printInterval( new FinalInterval( fusionInterval.dimensionsAsLongArray() ) ) );

		return blocks;
	}

	private static < D extends NativeType< D > & RealType< D > > TransformedDisplacementField< D > concatenateBoundingBoxOffset(
			final TransformedDisplacementField< D > dfield,
			final Interval boundingBoxInTarget )
	{
		final AffineTransform3D transformFromSource = new AffineTransform3D();
		final double[] translationVector = {
				-boundingBoxInTarget.min( 0 ),
				-boundingBoxInTarget.min( 1 ),
				-boundingBoxInTarget.min( 2 )
		};
		transformFromSource.setTranslation( translationVector );
		transformFromSource.concatenate( dfield.transformFromSource() );
		return new TransformedDisplacementField<>( transformFromSource, dfield.displacementField() );
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
		return blocks.andThen( displacementFieldAffine( dfield.transformFromSource(), dfield.displacementField(), interpolation ) );
	}

	private static < D extends NativeType< D > & RealType< D >, T extends NativeType< T > > BlockSupplier< T > createMasking(
			final Interval interval,
			final float[] border,
			final TransformedDisplacementField< D > transformedDisplacementField,
			final T maskType )
	{
		final AffineGet transformFromSource = transformedDisplacementField.transformFromSource();
		final DisplacementField< D > dfield = transformedDisplacementField.displacementField();
		return DisplacementFieldBlockSupplier.create( maskType, transformFromSource, dfield,
				MaskingFunction3D.of( dfield.getType(), maskType, interval, border ) );
	}

	private static < D extends NativeType< D > & RealType< D > > BlockSupplier< FloatType > createBlending(
			final Interval interval,
			final float[] border,
			final float[] blending,
			final TransformedDisplacementField< D > transformedDisplacementField )
	{
		final AffineGet transformFromSource = transformedDisplacementField.transformFromSource();
		final DisplacementField< D > dfield = transformedDisplacementField.displacementField();
		return DisplacementFieldBlockSupplier.create( new FloatType(), transformFromSource, dfield,
				BlendingFunction3D.of( dfield.getType(), interval, border, blending ) );
	}







	private static class TPSBlending implements BlockSupplier< FloatType >
	{
		final Interval sourceImageInterval, boundingBox;
		final double[][] source, target;
		final ThinplateSplineTransform transform;

		final float[] border, blending;
		final BlendingRealRandomAccessible blend;

		final ViewId viewId;

		public TPSBlending(
				final ViewId viewId,
				final Interval sourceImageInterval,
				final Interval boundingBox,
				final double[][] source,
				final double[][] target,
				final ThinplateSplineTransform transform,
				final float[] border,
				final float[] blending )
		{
			this.viewId = viewId;
			this.sourceImageInterval = sourceImageInterval;
			this.boundingBox = boundingBox;
			this.source = source;
			this.target = target;

			if ( transform == null )
				this.transform = new ThinplateSplineTransform( target, source ); // we go from output to input
			else
				this.transform = transform.copy();

			this.blending = blending;
			this.border = border;
			this.blend = new BlendingRealRandomAccessible( sourceImageInterval, border, blending );
		}

		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final BlockInterval blockInterval = blockInterval( interval, boundingBox );

			final float[] fdest = Cast.unchecked( dest );
			final int len = safeInt( Intervals.numElements( blockInterval.size() ) );

			// we do not need to check that the transformed src interval is overlapping with the input image,
			// this is done by e.g. ClosestPixelWins

			// figure out the interval we need to fetch from the src image
			//final Interval srcInterval = srcInterval( transform, blockInterval, defaultExpansion );

			// get an interpolator for the blending
			final RealRandomAccess< FloatType > rra = blend.realRandomAccess();

			final double[] loc = new double[ 3 ];

			// get a cursor over the srcInterval and a realrandomaccess for the interpolator
			final Cursor<Localizable> cursor = Views.flatIterable( Intervals.positions( blockInterval ) ).cursor();

			for ( int x = 0; x < len; ++x )
			{
				cursor.next().localize( loc );
				transform.apply( loc, loc );

				if ( contains3d( sourceImageInterval, loc ) )
				{
					rra.setPosition( loc );
					fdest[ x ] = rra.get().get();
				}
				else
				{
					// TODO: is that necessary?
					fdest[ x ] = 0;
				}
			}
		}

		private static final FloatType type = new FloatType();

		@Override
		public FloatType getType() { return type; }

		@Override
		public int numDimensions() { return 3; }

		@Override
		public BlockSupplier<FloatType> threadSafe() { return independentCopy(); }

		@Override
		public BlockSupplier<FloatType> independentCopy() { return new TPSBlending( viewId, sourceImageInterval, boundingBox, source, target, transform, border, blending );}
	}

	public static BlockInterval blockInterval( final Interval interval, final Interval boundingBox )
	{
		return BlockInterval.asBlockInterval( Intervals.translate( interval, boundingBox.minAsLongArray() ) );
	}

	public static Interval srcInterval( final ThinplateSplineTransform transform, final Interval blockInterval, final int expansion )
	{
		// figure out the interval we need to fetch from the src image
		final RealInterval srcRealInterval = transform.boundingInterval( blockInterval, IntervalSamplingMethod.CORNERS );
		final Interval srcInterval = Intervals.expand( Intervals.smallestContainingInterval( srcRealInterval ), expansion );

		return srcInterval;
	}

	private static class TPSImageTransform implements UnaryBlockOperator<FloatType, FloatType>
	{
		final Interval sourceImageInterval, boundingBox;
		final double[][] source, target;
		final ThinplateSplineTransform transform;
		final int intervalExpansion;

		final ViewId viewId;

		public TPSImageTransform(
				final ViewId viewId,
				final Interval sourceImageInterval,
				final Interval boundingBox,
				final double[][] source,
				final double[][] target,
				final ThinplateSplineTransform transform,
				final int intervalExpansion )
		{
			this.viewId = viewId;
			this.sourceImageInterval = sourceImageInterval;
			this.boundingBox = boundingBox;
			this.source = source;
			this.target = target;

			if ( transform == null )
				this.transform = new ThinplateSplineTransform( target, source ); // we go from output to input
			else
				this.transform = transform.copy();

			this.intervalExpansion = intervalExpansion;
		}

		@Override
		public void compute( final BlockSupplier<FloatType> src, final Interval interval, final Object dest )
		{
			final BlockInterval blockInterval = blockInterval( interval, boundingBox );

			final float[] fdest = Cast.unchecked( dest );
			final int len = safeInt( Intervals.numElements( blockInterval.size() ) );

			// we do not need to check that the transformed src interval is overlapping with the input image,
			// this is done by e.g. ClosestPixelWins

			// figure out the interval we need to fetch from the src image
			final Interval srcInterval = srcInterval( transform, blockInterval, intervalExpansion );

			// request the required src data as a copy and translate it to its actual position
			final RandomAccessibleInterval< FloatType > img =
					Views.translate( BlockSupplierUtils.arrayImg( src, srcInterval ), srcInterval.minAsLongArray() );

			// get an interpolator for the copied block
			final RealRandomAccessibleView< FloatType > interp =
					img.view().extend( zero()).interpolate( clampingNLinear());
			final RealRandomAccess< FloatType > rra = interp.realRandomAccess();

			final double[] loc = new double[ 3 ];

			// get a cursor over the srcInterval
			final Cursor<Localizable> cursor = Views.flatIterable( Intervals.positions( blockInterval ) ).cursor();

			final RandomAccessibleInterval< DoubleType > vectors = Views.translate(
					ArrayImgs.doubles( blockInterval.dimension( 0 ), blockInterval.dimension( 1 ), blockInterval.dimension( 2 ), 3 ),
					new long[] { blockInterval.min(0), blockInterval.min(1), blockInterval.min(2), 0 } );

			final CompositeView<DoubleType, ? extends GenericComposite<DoubleType>>.CompositeRandomAccess rv =
					Views.collapse( vectors ).randomAccess();

			for ( int x = 0; x < len; ++x )
			{
				cursor.next().localize( loc );
				transform.apply( loc, loc );

				rv.setPosition( cursor );
				rv.get().get( 0 ).set( loc[ 0 ] );
				rv.get().get( 1 ).set( loc[ 1 ] );
				rv.get().get( 2 ).set( loc[ 2 ] );

				if ( contains3d( sourceImageInterval, loc ))
				{
					rra.setPosition( loc );
					fdest[ x ] = rra.get().get();
				}
				else
				{
					// TODO: is that necessary?
					fdest[ x ] = 0;
				}
			}
		}

		private static final FloatType type = new FloatType();

		@Override
		public FloatType getSourceType() { return type; }

		@Override
		public FloatType getTargetType() { return type; }

		@Override
		public int numSourceDimensions() { return 3; }

		@Override
		public int numTargetDimensions() { return 3; }

		@Override
		public UnaryBlockOperator<FloatType, FloatType> independentCopy() { return new TPSImageTransform( viewId, sourceImageInterval, boundingBox, source, target, transform, intervalExpansion ); }
	}


	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling )
	{
		final List<Integer> splitSetupIds = old2newSetupId.get( underlyingViewId.getViewSetupId() );

		final double[][] source = new double[3][splitSetupIds.size()];
		final double[][] target = new double[3][splitSetupIds.size()];

		for ( int i = 0; i < splitSetupIds.size(); ++i )
		{
			final int splitViewSetupId = splitSetupIds.get( i );
			final ViewId splitViewId = new ViewId( underlyingViewId.getTimePointId(), splitViewSetupId );

			//System.out.println( "\tProcessing splitViewId: " + Group.pvid( splitViewId ) + ":" );

			final ViewRegistration vr = splitRegMap.get( splitViewId );
			final List<ViewTransform> vrList = vr.getTransformList();

			// just making sure this is the split transform
			if ( !vrList.get( vrList.size() - 1).getName().equals(SplittingTools.IMAGE_SPLITTING_NAME) )
				throw new RuntimeException( "First transformation is not " + SplittingTools.IMAGE_SPLITTING_NAME + " for " + Group.pvid( splitViewId ) + ", stopping." );

			// this transformation puts the Zero-Min View of the underlying image where it actually is
			final ViewTransform splitTransform = vrList.get( vrList.size() - 1);
			//System.out.println( "\t" + SplittingTools.IMAGE_SPLITTING_NAME + " transformation: " + splitTransform );

			// get the remaining model
			vr.updateModel();
			final AffineTransform3D model = vr.getModel().copy();

			// preserve anisotropy
			if ( !Double.isNaN( anisotropyFactor ) )
				TransformVirtual.scaleTransform( model, new double[] { 1.0, 1.0, 1.0/anisotropyFactor } );

			// downsampling
			if ( !Double.isNaN( downsampling ) )
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );

			// create a point in the middle of the Zero-Min View and the corresponding point in the global output space
			final Interval splitInterval = splitImgLoader.newSetupId2Interval().get( splitViewSetupId );

			final double[] p = new double[] { splitInterval.dimension( 0 ) / 2.0, splitInterval.dimension( 1 ) / 2.0, splitInterval.dimension( 2 ) / 2.0 };
			final double[] q = new double[ p.length ];
			model.apply( p, q );

			for ( int d = 0; d < p.length; ++d )
			{
				p[ d ] += splitTransform.asAffine3D().get(d, 3); // add the translation offsets of each split view
				source[ d ][ i ] = p[ d ];
				target[ d ][ i ] = q[ d ];
			}

			//System.out.println( "\tCenter point: " + Arrays.toString( p ) + " maps into global output space to: " + Arrays.toString( q ) );
		}

		return new Landmarks( source, target );
	}

	private static AffineTransform3D getTransform( final double[][] source, final double[][] target )
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

	public static boolean contains3d( final RealInterval containing, final double[] contained )
	{
		if ( contained[ 0 ] < containing.realMin( 0 ) || contained[ 0 ] > containing.realMax( 0 ) )
			return false;

		if ( contained[ 1 ] < containing.realMin( 1 ) || contained[ 1 ] > containing.realMax( 1 ) )
			return false;

		if ( contained[ 2 ] < containing.realMin( 2 ) || contained[ 2 ] > containing.realMax( 2 ) )
			return false;

		return true;
	}

	public static List<ViewId> underlyingViewIds( final Collection< ? extends ViewId > splitViewIds, final Map< Integer, Integer > new2oldSetupId )
	{
		return splitViewIds.stream()
				.map( splitViewId ->
					new ViewId( splitViewId.getTimePointId(), new2oldSetupId.get( splitViewId.getViewSetupId() ) ) )
				.distinct()
				.collect( Collectors.toList());
	}

	public static List<ViewId> splitViewIds( final Collection< ? extends ViewId > underlyingViewIds, final Map< Integer, List<Integer>> old2newSetupId )
	{
		return underlyingViewIds.stream().flatMap( underlyingViewId ->
			old2newSetupId.get( underlyingViewId.getViewSetupId() ).stream().map( splitSetupId ->
				new ViewId( underlyingViewId.getTimePointId(), splitSetupId ) )
		).collect( Collectors.toList());
	}

	public static Map<Integer, List<Integer>> old2newSetupId( final Map<Integer, Integer> new2oldSetupId )
	{
		final Map< Integer, List< Integer > > old2newSetupId = new HashMap<>();

		new2oldSetupId.forEach( ( k, v ) -> old2newSetupId.computeIfAbsent( v, newKey -> new ArrayList<>() ).add( k ) );

		return old2newSetupId;
	}

	public static ViewerImgLoader getUnderlyingImageLoader( final SpimData2 data )
	{
		if ( SplitViewerImgLoader.class.isInstance( data.getSequenceDescription().getImgLoader() ) )
			return ( ( SplitViewerImgLoader ) data.getSequenceDescription().getImgLoader() ).getUnderlyingImgLoader();
		else
			return null;
	}

}
