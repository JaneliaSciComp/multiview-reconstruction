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

import static net.imglib2.algorithm.blocks.transform.Transform.Interpolation.NEARESTNEIGHBOR;
import static net.imglib2.algorithm.blocks.transform.Transform.Interpolation.NLINEAR;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mpicbg.models.AffineModel1D;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.ClampType;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.algorithm.blocks.transform.Transform;
import net.imglib2.algorithm.blocks.transform.Transform.Interpolation;
import net.imglib2.converter.Converter;
import net.imglib2.converter.RealUnsignedByteConverter;
import net.imglib2.converter.RealUnsignedShortConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Util;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.intensity.FastLinearIntensityMap;
import net.preibisch.mvrecon.process.fusion.lazy.LazyAffineFusion;
import net.preibisch.mvrecon.process.fusion.lazy.LazyFusionTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class BlkAffineFusion
{
	/**
	 * TODO javadoc
	 *
	 *
	 *
	 * @param converter
	 * 		converts from FloatType to the output type. Maybe null,
	 * 		in which case a default converter (clamp to output type range) is used.
	 * @param imgloader
	 * 		the imgloader to fetch raw data
	 * @param viewIds
	 * 		which viewids to fuse
	 * @param viewRegistrations
	 * 		the registrations (must include anisotropy and downsampling if desired)
	 * @param viewDescriptions
	 * 		TODO
	 * @param fusionType
	 * 		how to combine pixels
	 * @param anisotropyFactor
	 * @param interpolationMethod
	 * 		1==linear, 0==nearest neighbor
	 * @param intensityAdjustments
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
			final BasicImgLoader imgloader,
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends AffineTransform3D > viewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final FusionType fusionType,
			final double anisotropyFactor, // can be Double.NAN, only for content-based fusion
			final int interpolationMethod,
			final Map< ViewId, AffineModel1D > intensityAdjustments,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize )
	{
		return init( converter, imgloader, viewIds, viewRegistrations, viewDescriptions, fusionType, anisotropyFactor, null, interpolationMethod,
				intensityAdjustments, null,
				fusionInterval, type, blockSize );
	}

	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > initWithIntensityCoefficients(
			final Converter< FloatType, T > converter,
			final BasicImgLoader imgloader,
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends AffineTransform3D > viewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final FusionType fusionType,
			final double anisotropyFactor, // can be Double.NAN, only for content-based fusion
			final Map< Integer, Integer > fusionMap, // old setupId > new setupId for fusion order, only makes sense with FusionType.FIRST_LOW or FusionType.FIRST_HIGH
			final int interpolationMethod,
			final Map< ViewId, Coefficients > intensityAdjustments,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize )
	{
		return init( converter, imgloader, viewIds, viewRegistrations, viewDescriptions, fusionType, anisotropyFactor, fusionMap, interpolationMethod,
				null, intensityAdjustments,
				fusionInterval, type, blockSize );
	}

	private static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final BasicImgLoader imgloader,
			final Collection< ? extends ViewId > viewIds,
			final Map< ViewId, ? extends AffineTransform3D > viewRegistrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final FusionType fusionType,
			final double anisotropyFactor, // can be Double.NAN, only for content-based fusion
			// TODO: replace with Comparator<Integer>
			final Map< Integer, Integer > fusionMap, // old setupId > new setupId for fusion order, only makes sense with FusionType.FIRST_LOW or FusionType.FIRST_HIGH
			final int interpolationMethod,
			final Map< ViewId, AffineModel1D > intensityAdjustmentModels,
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize )
	{
		// go through the views and check if they are all 2-dimensional
		final boolean is2d = is2d( viewIds, viewDescriptions );

		if ( !supports( intensityAdjustmentModels ) )
		{
			if ( intensityAdjustmentCoefficients != null )
				// TODO: support intensity adjustmen with Coefficients in LazyAffineFusion
				throw new UnsupportedOperationException( "BlkAffineFusion: Fusion method not supported (yet)." );

			//throw new UnsupportedOperationException( "BlkAffineFusion: Fusion method not supported (yet)." );
			IOFunctions.println( "BlkAffineFusion: Fusion method not supported (yet). Falling back to LazyAffineFusion." );
			return BlockSupplier.of(
					Views.zeroMin( LazyAffineFusion.init( converter, imgloader, viewIds, viewRegistrations, viewDescriptions, fusionType, interpolationMethod, intensityAdjustmentModels, fusionInterval, type, blockSize ) ) );
		}

		final HashMap< ViewId, Dimensions > viewDimensions = LazyFusionTools.assembleDimensions( viewIds, viewDescriptions );
		final Interpolation interpolation = ( interpolationMethod == 1 ) ? NLINEAR : NEARESTNEIGHBOR;

		// to be able to use the "lowest ViewId" wins strategy
		final List< ? extends ViewId > sortedViewIds = new ArrayList<>( viewIds );

		if ( fusionMap == null || fusionMap.size() == 0 )
			Collections.sort( sortedViewIds );
		else
			Collections.sort( sortedViewIds, (c1,c2) -> Integer.compare( fusionMap.get( c1.getViewSetupId() ), fusionMap.get( c2.getViewSetupId() ) ) );

		// Which views to process (use un-altered bounding box and registrations).
		// Final filtering happens per Cell.
		// Here we just pre-filter everything outside the fusionInterval.
		final Overlap overlap = new Overlap(
				sortedViewIds,
				viewRegistrations,
				viewDimensions,
				LazyFusionTools.defaultAffineExpansion,
				is2d ? 2 : 3 )
				.filter( fusionInterval )
				.offset( fusionInterval.minAsLongArray() );

		final List< BlockSupplier< FloatType > > images = new ArrayList<>( overlap.numViews() );
		final List< BlockSupplier< FloatType > > weights = new ArrayList<>( overlap.numViews() );
		final List< BlockSupplier< UnsignedByteType > > masks = new ArrayList<>( overlap.numViews() );

		for ( final ViewId viewId : overlap.getViewIds() )
		{
			final AffineTransform3D model = viewRegistrations.get( viewId ).copy();

			// this modifies the model so it maps from a smaller image to the global coordinate space,
			// which applies for the image itself as well as the weights since they also use the smaller
			// input image as reference
			final double[] usedDownsampleFactors = new double[ 3 ];
			RandomAccessibleInterval inputImg = DownsampleTools.openDownsampled( imgloader, viewId, model, usedDownsampleFactors );

			final AffineTransform3D transform = concatenateBoundingBoxOffset( model, fusionInterval );

			final Coefficients coefficients = intensityAdjustmentCoefficients == null ? null : intensityAdjustmentCoefficients.get( viewId );

			final BlockSupplier< FloatType > viewBlocks = transformedBlocks(
					Cast.unchecked( inputImg ),
					coefficients,
					transform, interpolation );
			images.add( viewBlocks );

			// instantiate blending if necessary
			final float[] blending = Util.getArrayFromValue( FusionTools.defaultBlendingRange, 3 );
			final float[] border = Util.getArrayFromValue( FusionTools.defaultBlendingBorder, 3 );

			// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
			FusionTools.adjustBlending( viewDimensions.get( viewId ), Group.pvid( viewId ), blending, border, model );

			// adjust content-based for downsampling
			final double[] sigma1 = Util.getArrayFromValue( ContentBased.defaultContentBasedSigma1, 3 );
			final double[] sigma2 = Util.getArrayFromValue( ContentBased.defaultContentBasedSigma2, 3 );
			FusionTools.adjustContentBased( viewDescriptions.get( viewId ), sigma1, sigma2, usedDownsampleFactors, anisotropyFactor );

			switch ( fusionType )
			{
			case AVG:
				weights.add( Masking.create( inputImg, border, transform ).andThen( Convert.convert( new FloatType() ) ) );
				break;
			case AVG_BLEND:
				weights.add( Blending.create( inputImg, border, blending, transform ) );
				break;
			case MAX_INTENSITY:
			case LOWEST_VIEWID_WINS:
				masks.add( Masking.create( inputImg, border, transform ) );
				break;
			case HIGHEST_VIEWID_WINS:
				masks.add( Masking.create( inputImg, border, transform ) );
				break;
			case CLOSEST_PIXEL_WINS:
				// squared distance to the view center in fused coordinates; smallest distance wins
				weights.add( Distance.create( inputImg, transform ) );
				break;
			case AVG_BLEND_CONTENT:
				final BlockSupplier< FloatType > cb1 = ContentBased.create( inputImg, sigma1, sigma2, ContentBased.defaultScale );

				final BlockSupplier< FloatType > cbTransformed1 =
						cb1.andThen( Transform.affine( transform, Interpolation.NLINEAR ) );

				final BlockSupplier<FloatType> blend =
						Blending.create( inputImg, border, blending, transform );

				weights.add( MultiplicativeCombiner.create( cbTransformed1, blend ));
				break;
			case AVG_CONTENT:
				final BlockSupplier< FloatType > cb2 = ContentBased.create( inputImg, sigma1, sigma2, ContentBased.defaultScale );

				final BlockSupplier< FloatType > cbTransformed2 =
						cb2.andThen( Transform.affine( transform, Interpolation.NLINEAR ) );

				final BlockSupplier<FloatType> avg =
						Masking.create( inputImg, border, transform ).andThen( Convert.convert( new FloatType() ) );

				weights.add( MultiplicativeCombiner.create( cbTransformed2, avg ));
				break;
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
			// should never happen
			throw new IllegalStateException();
		}

		final BlockSupplier< T > blocks = convertToOutputType(
				floatBlocks,
				converter, type )
				.tile( 32 );

		System.out.println( Util.printInterval( new FinalInterval( fusionInterval.dimensionsAsLongArray() ) ) );

		return blocks;
		//return BlockAlgoUtils.cellImg( blocks, fusionInterval.dimensionsAsLongArray(), blockSize );
	}

	protected static < T extends NativeType< T > > BlockSupplier< T > convertToOutputType(
			final BlockSupplier< FloatType > floatBlocks,
			final Converter< FloatType, T > converter,
			final T type )
	{
		if ( converter == null )
		{
			return floatBlocks.andThen( Convert.convert( type, ClampType.CLAMP ) );
		}
		else if ( converter instanceof RealUnsignedByteConverter )
		{
			final RealUnsignedByteConverter< ? > c = Cast.unchecked( converter );
			final double min = c.getMin();
			final double max = c.getMax();
			final float scale = ( float ) ( 255.0 / ( max - min ) );
			final float offset = ( float ) ( -min / ( max - min ) );
			return floatBlocks
					.andThen( LinearRange.linearRange( scale, offset ) )
					.andThen( Convert.convert( type, ClampType.CLAMP ) );
		}
		else if ( converter instanceof RealUnsignedShortConverter )
		{
			final RealUnsignedShortConverter< ? > c = Cast.unchecked( converter );
			final double min = c.getMin();
			final double max = c.getMax();
			final float scale = ( float ) ( 65535.0 / ( max - min ) );
			final float offset = ( float ) ( -min / ( max - min ) );
			return floatBlocks
					.andThen( LinearRange.linearRange( scale, offset ) )
					.andThen( Convert.convert( type, ClampType.CLAMP ) );
		}
		else
		{
			return floatBlocks.andThen( Convert.convert( type, () -> converter ) );
		}
	}

	private static < T extends NativeType< T > > BlockSupplier< FloatType > transformedBlocks(
			final RandomAccessibleInterval< T > inputImg,
			final Coefficients coefficients,
			final AffineTransform3D transform,
			final Interpolation interpolation )
	{
		BlockSupplier< FloatType > blocks = BlockSupplier.of( extendInput( inputImg ) )
				.andThen( Convert.convert( new FloatType() ) );
		if ( coefficients != null )
			blocks = blocks.andThen( FastLinearIntensityMap.linearIntensityMap( coefficients, inputImg ) );
		return blocks.andThen( Transform.affine( transform, interpolation ) );
	}

	protected static < T extends NativeType< T > > RandomAccessible< T > extendInput(
			final RandomAccessible< T > input )
	{
		if ( input instanceof IntervalView )
		{
			return extendInput( ( ( IntervalView< T > ) input ).getSource() );
		}
//		else if ( input instanceof MixedTransformView )
//		{
//			final MixedTransformView< T > view = ( MixedTransformView< T > ) input;
//			return new MixedTransformView<>( extendInput( view.getSource() ), view.getTransformToSource() );
//		}
		else if ( input instanceof RandomAccessibleInterval )
		{
			return Views.extendBorder( ( RandomAccessibleInterval< T > ) input );
		}
		else
		{
			// must already be extended then ...
			return input;
		}
	}

	private static AffineTransform3D concatenateBoundingBoxOffset(
			final AffineTransform3D transformFromSource,
			final Interval boundingBoxInTarget )
	{
		final AffineTransform3D t = new AffineTransform3D();
		t.setTranslation(
				-boundingBoxInTarget.min( 0 ),
				-boundingBoxInTarget.min( 1 ),
				-boundingBoxInTarget.min( 2 ) );
		t.concatenate( transformFromSource );
		return t;
	}

	public static boolean is2d( final Collection< ? extends ViewId > viewIds, final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions )
	{
		return viewIds.stream()
		.map( viewDescriptions::get )
		.map( BasicViewDescription::getViewSetup )
		.filter( BasicViewSetup::hasSize )
		.allMatch( vs -> vs.getSize().dimension( 2 ) == 1 );
	}

	private static boolean supports( final Map< ViewId, AffineModel1D > intensityAdjustments )
	{
		if ( intensityAdjustments != null )
			return false; // TODO

		return true;
	}
}
