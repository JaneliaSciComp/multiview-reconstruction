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
package net.preibisch.mvrecon.process.deconvolution.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import bdv.util.ConstantRandomAccessible;
import mpicbg.models.AffineModel1D;
import mpicbg.spim.data.generic.AbstractSpimData;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.deconvolution.MultiViewDeconvolution;
import net.preibisch.mvrecon.process.deconvolution.normalization.NormalizingRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.intensityadjust.IntensityAdjuster;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval.Fusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.TransformWeight;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval.CombineType;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class ProcessInputImages< V extends ViewId >
{
	final AbstractSpimData< ? > spimData;
	final ArrayList< Group< V > > groups;
	final Interval bb;
	Interval downsampledBB;
	final ExecutorService service;
	final double downsampling;
	final boolean useWeightsFusion, useWeightsDecon;
	final float blendingRangeFusion, blendingBorderFusion, blendingRangeDeconvolution, blendingBorderDeconvolution;

	final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > images;
	final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > unnormalizedWeights, normalizedWeights;
	final HashMap< V, AffineTransform3D > models;
	final Map< ? extends ViewId, AffineModel1D > intensityAdjustments;

	public ProcessInputImages(
			final AbstractSpimData< ? > spimData,
			final Collection< Group< V > > groups,
			final ExecutorService service,
			final Interval bb,
			final double downsampling,
			final boolean useWeightsFusion,
			final float blendingRangeFusion,
			final float blendingBorderFusion,
			final boolean useWeightsDecon,
			final float blendingRangeDeconvolution,
			final float blendingBorderDeconvolution,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments )
	{
		this.spimData = spimData;
		this.groups = SpimData2.filterGroupsForMissingViews( spimData, groups );
		this.service = service;
		this.bb = bb;
		this.downsampling = downsampling;
		this.useWeightsFusion = useWeightsFusion;
		this.blendingRangeFusion = blendingRangeFusion;
		this.blendingBorderFusion = blendingBorderFusion;
		this.useWeightsDecon = useWeightsDecon;
		this.blendingRangeDeconvolution = blendingRangeDeconvolution;
		this.blendingBorderDeconvolution = blendingBorderDeconvolution;
		this.intensityAdjustments = intensityAdjustments;

		this.images = new HashMap<>();
		this.unnormalizedWeights = new HashMap<>();
		this.normalizedWeights = new HashMap<>();
		this.models = new HashMap<>();

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Remaining groups: " + this.groups.size() );
	}

	public ProcessInputImages(
			final AbstractSpimData< ? > spimData,
			final Collection< Group< V > > groups,
			final ExecutorService service,
			final Interval bb,
			final double downsampling,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments )
	{
		this(
				spimData, groups, service, bb, downsampling,
				true, FusionTools.defaultBlendingRange, FusionTools.defaultBlendingBorder,
				true, MultiViewDeconvolution.defaultBlendingRange, MultiViewDeconvolution.defaultBlendingBorder,
				intensityAdjustments );
	}

	public ProcessInputImages(
			final AbstractSpimData< ? > spimData,
			final Collection< Group< V > > groups,
			final ExecutorService service,
			final Interval bb,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments )
	{
		this( spimData, groups, service, bb, Double.NaN, intensityAdjustments );
	}

	public ArrayList< Group< V > > getGroups() { return groups; }
	public Interval getBoundingBox() { return bb; }
	public Interval getDownsampledBoundingBox() { return downsampledBB; }
	public HashMap< V, AffineTransform3D > getDownsampledModels() { return models; }
	public HashMap< Group< V >, RandomAccessibleInterval< FloatType > > getImages() { return images; }
	public HashMap< Group< V >, RandomAccessibleInterval< FloatType > > getNormalizedWeights() { return normalizedWeights; }
	public HashMap< Group< V >, RandomAccessibleInterval< FloatType > > getUnnormalizedWeights() { return unnormalizedWeights; }

	public void fuseGroups()
	{
		this.downsampledBB = fuseGroups(
				spimData,
				images,
				unnormalizedWeights,
				models,
				groups,
				bb,
				downsampling,
				useWeightsFusion ? Util.getArrayFromValue( blendingRangeFusion, 3 ) : null,
				useWeightsFusion ? Util.getArrayFromValue( blendingBorderFusion, 3 ) : null,
				useWeightsDecon ? Util.getArrayFromValue( blendingRangeDeconvolution, 3 ) : null,
				useWeightsDecon ? Util.getArrayFromValue( blendingBorderDeconvolution, 3 ) : null,
				intensityAdjustments );
	}

	public void cacheImages( final int cellDim, final int maxCacheSize ) { cacheRandomAccessibleInterval( groups, cellDim, maxCacheSize, images ); }
	public void cacheImages() { cacheImages( MultiViewDeconvolution.cellDim, MultiViewDeconvolution.maxCacheSize ); }

	public void copyImages( final ImgFactory< FloatType > imgFactory ) { copyRandomAccessibleInterval( groups, service, imgFactory, images ); }
	public void copyImages() { copyImages( new CellImgFactory<>( MultiViewDeconvolution.cellDim ) ); }

	public void cacheUnnormalizedWeights( final int cellDim, final int maxCacheSize ) { cacheRandomAccessibleInterval( groups, cellDim, maxCacheSize, unnormalizedWeights ); }
	public void cacheUnnormalizedWeights() { cacheUnnormalizedWeights( MultiViewDeconvolution.cellDim, MultiViewDeconvolution.maxCacheSize ); }

	public void copyUnnormalizedWeights( final ImgFactory< FloatType > imgFactory ) { copyRandomAccessibleInterval( groups, service, imgFactory, unnormalizedWeights ); }
	public void copyUnnormalizedWeights() { copyUnnormalizedWeights( new CellImgFactory<>( MultiViewDeconvolution.cellDim ) ); }

	public void cacheNormalizedWeights( final int cellDim, final int maxCacheSize ) { cacheRandomAccessibleInterval( groups, cellDim, maxCacheSize, normalizedWeights ); }
	public void cacheNormalizedWeights() { cacheNormalizedWeights( MultiViewDeconvolution.cellDim, MultiViewDeconvolution.maxCacheSize ); }

	public void copyNormalizedWeights( final ImgFactory< FloatType > imgFactory ) { copyRandomAccessibleInterval( groups, service, imgFactory, normalizedWeights ); }
	public void copyNormalizedWeights() { copyNormalizedWeights( new CellImgFactory<>( MultiViewDeconvolution.cellDim ) ); }

	public void normalizeWeights() { normalizeWeights( 1.0 ); }
	public void normalizeWeights( final double osemspeedup )
	{
		normalizeWeights(
				osemspeedup,
				MultiViewDeconvolution.additionalSmoothBlending,
				MultiViewDeconvolution.maxDiffRange,
				MultiViewDeconvolution.scalingRange );
	}

	public void normalizeWeights(
			final double osemspeedup,
			final boolean additionalSmoothBlending,
			final float maxDiffRange,
			final float scalingRange )
	{
		normalizedWeights.putAll(
			normalizeWeights(
				unnormalizedWeights,
				groups,
				osemspeedup,
				additionalSmoothBlending,
				maxDiffRange,
				scalingRange ) );
	}

	public static < V extends ViewId > HashMap< Group< V >, RandomAccessibleInterval< FloatType > > normalizeWeights(
			final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > unnormalizedWeights,
			final List< Group< V > > groups,
			final double osemspeedup,
			final boolean additionalSmoothBlending,
			final float maxDiffRange,
			final float scalingRange )
	{
		final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > normalizedWeights = new HashMap<>();
		final ArrayList< RandomAccessibleInterval< FloatType > > originalWeights = new ArrayList<>();

		// ordered as in the group list
		for ( int i = 0; i < groups.size(); ++i )
			originalWeights.add( unnormalizedWeights.get( groups.get( i ) ) );

		// normalizes the weights (sum == 1) and applies osem-speedup if wanted
		for ( int i = 0; i < groups.size(); ++i )
		{
			final Group< V > group = groups.get( i );

			normalizedWeights.put( group,
				new NormalizingRandomAccessibleInterval< FloatType >(
					unnormalizedWeights.get( group ),
					i,
					originalWeights,
					osemspeedup,
					additionalSmoothBlending,
					maxDiffRange,
					scalingRange,
					new FloatType() ) );
		}

		return normalizedWeights;
	}

	public static < V extends ViewId > void cacheRandomAccessibleInterval(
			final Collection< Group< V > > groups,
			final int cellDim,
			final int maxCacheSize,
			final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > images )
	{
		for ( final Group< V > group : groups )
		{
			if ( !images.containsKey( group ) )
				continue;

			images.put( group, FusionTools.cacheRandomAccessibleInterval( images.get( group ), maxCacheSize, new FloatType(), cellDim ) );
		}
	}

	public static < V extends ViewId > void copyRandomAccessibleInterval(
			final Collection< Group< V > > groups,
			final ExecutorService service,
			final ImgFactory< FloatType > factory,
			final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > images )
	{
		for ( final Group< V > group : groups )
		{
			if ( !images.containsKey( group ) )
				continue;

			images.put( group, FusionTools.copyImg( images.get( group ), factory, new FloatType(), service ) );
		}
	}

	public static < V extends ViewId > Interval fuseGroups(
			final AbstractSpimData< ? > spimData,
			final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > tImgs,
			final HashMap< Group< V >, RandomAccessibleInterval< FloatType > > tWeights,
			final HashMap< V, AffineTransform3D > models,
			final Collection< Group< V > > groups,
			final Interval boundingBox,
			final double downsampling,
			final float[] blendingRangeFusion,
			final float[] blendingBorderFusion,
			final float[] blendingRangeDecon,
			final float[] blendingBorderDecon,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments )
	{
		int i = 0;

		// scale the bounding box if necessary
		final Interval bb;
		if ( !Double.isNaN( downsampling ) )
			bb = TransformVirtual.scaleBoundingBox( boundingBox, 1.0 / downsampling );
		else
			bb = boundingBox;

		final long[] dim = new long[ bb.numDimensions() ];
		bb.dimensions( dim );

		for ( final Group< V > group : groups )
		{
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Transforming group " + (++i) + " of " + groups.size() + " (group=" + group + ")" );

			final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();
			final ArrayList< RandomAccessibleInterval< FloatType > > weightsFusion = new ArrayList<>();
			final ArrayList< RandomAccessibleInterval< FloatType > > weightsDecon = new ArrayList<>();

			for ( final V viewId : group.getViews() )
			{
				final BasicImgLoader imgloader = spimData.getSequenceDescription().getImgLoader();
				final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
				vr.updateModel();
				AffineTransform3D model = vr.getModel();

				// adjust the model for downsampling
				if ( !Double.isNaN( downsampling ) )
				{
					model = model.copy();
					TransformVirtual.scaleTransform( model, 1.0 / downsampling );
				}

				// we need to add a copy here since below the model might be modified for downsampled opening
				models.put( viewId, model.copy() );

				// this modifies the model so it maps from a smaller image to the global coordinate space,
				// which applies for the image itself as well as the weights since they also use the smaller
				// input image as reference
				final double[] ds = new double[ 3 ];
				RandomAccessibleInterval inputImg = DownsampleTools.openDownsampled( imgloader, viewId, model, ds );

				if ( intensityAdjustments != null && intensityAdjustments.containsKey( viewId ) )
					inputImg = Converters.convert(
							FusionTools.convertInput( inputImg ),
							new IntensityAdjuster( intensityAdjustments.get( viewId ) ),
							new FloatType() );

				images.add( TransformView.transformView( inputImg, model, bb, MultiViewDeconvolution.minValueImg, MultiViewDeconvolution.outsideValueImg, 1 ) );

				System.out.println( "Used downsampling: " + Util.printCoordinates( ds ) );

				if ( blendingRangeFusion != null && blendingBorderFusion != null )
				{
					// we need a different blending when virtually fusing the images since a negative
					// value would actually lead to artifacts there
					final float[] rangeFusion = blendingRangeFusion.clone();
					final float[] borderFusion = blendingBorderFusion.clone();

					// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
					FusionTools.adjustBlending( spimData.getSequenceDescription().getViewDescriptions().get( viewId ), rangeFusion, borderFusion, model );

					weightsFusion.add( TransformWeight.transformBlending( inputImg, borderFusion, rangeFusion, model, bb ) );
				}
				else
				{
					weightsFusion.add( Views.interval( new ConstantRandomAccessible< FloatType >( new FloatType( 1 ), bb.numDimensions() ), new FinalInterval( dim ) ) );
				}

				if ( blendingRangeDecon != null && blendingBorderDecon != null )
				{
					// however, to then run the deconvolution with this data, we want negative values
					// to maximize the usage of image data
					final float[] rangeDecon = blendingRangeDecon.clone();
					final float[] borderDecon = blendingBorderDecon.clone();

					System.out.println( Util.printCoordinates( rangeDecon ) );
					System.out.println( Util.printCoordinates( borderDecon ) );

					// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
					FusionTools.adjustBlending( spimData.getSequenceDescription().getViewDescriptions().get( viewId ), rangeDecon, borderDecon, model );

					System.out.println( Util.printCoordinates( rangeDecon ) );
					System.out.println( Util.printCoordinates( borderDecon ) );
					System.out.println();

					weightsDecon.add( TransformWeight.transformBlending( inputImg, borderDecon, rangeDecon, model, bb ) );
				}
				else
				{
					weightsDecon.add( Views.interval( new ConstantRandomAccessible< FloatType >( new FloatType( 1 ), bb.numDimensions() ), new FinalInterval( dim ) ) );
				}
			}

			// the fused image per group
			final RandomAccessibleInterval< FloatType > img = new FusedRandomAccessibleInterval( new FinalInterval( dim ), Fusion.AVG, images, weightsFusion );

			// the weights used for deconvolution per group
			final RandomAccessibleInterval< FloatType > weight = new CombineWeightsRandomAccessibleInterval( new FinalInterval( dim ), weightsDecon, CombineType.SUM );

			tImgs.put( group, img );
			tWeights.put( group, weight );
		}

		return bb;
	}
}
