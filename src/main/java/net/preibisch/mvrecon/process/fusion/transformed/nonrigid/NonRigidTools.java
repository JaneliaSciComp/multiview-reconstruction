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
package net.preibisch.mvrecon.process.fusion.transformed.nonrigid;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import bdv.util.ConstantRandomAccessible;
import mpicbg.models.AffineModel1D;
import mpicbg.models.AffineModel3D;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converters;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.RealSum;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxReorientation;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.intensityadjust.IntensityAdjuster;
import net.preibisch.mvrecon.process.fusion.lazy.LazyFusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.TransformWeight;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval.Fusion;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval.CombineType;
import net.preibisch.mvrecon.process.fusion.transformed.weights.BlendingRealRandomAccessible;
import net.preibisch.mvrecon.process.fusion.transformed.weights.ContentBasedRealRandomAccessible;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

public class NonRigidTools
{
	public static RandomAccessibleInterval< FloatType > fuseVirtualInterpolatedNonRigid(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final ArrayList< String > labels,
			final FusionType fusionType,
			final boolean displayDistances,
			final long[] controlPointDistance,
			final double alpha,
			final boolean virtualGrid,
			final int interpolation,
			final Interval boundingBox1,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final ExecutorService service )
	{
		final BasicImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();

		final HashMap< ViewId, AffineTransform3D > viewRegistrations = new HashMap<>();

		for ( final ViewId viewId : viewsToUse )
		{
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			viewRegistrations.put( viewId, vr.getModel().copy() );
		}

		for ( final ViewId viewId : viewsToFuse )
		{
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			viewRegistrations.put( viewId, vr.getModel().copy() );
		}

		final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions = spimData.getSequenceDescription().getViewDescriptions();

		return fuseVirtualInterpolatedNonRigid(
				imgLoader,
				viewRegistrations,
				spimData.getViewInterestPoints().getViewInterestPoints(),
				viewDescriptions,
				viewsToFuse,
				viewsToUse,
				labels,
				fusionType,
				displayDistances,
				controlPointDistance,
				alpha,
				virtualGrid,
				interpolation,
				boundingBox1,
				intensityAdjustments,
				service );
	}

	public static RandomAccessibleInterval< FloatType > fuseVirtualInterpolatedNonRigid(
			final BasicImgLoader imgloader,
			final Map< ViewId, AffineTransform3D > viewRegistrations,
			final Map< ViewId, ViewInterestPointLists > viewInterestPoints,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final ArrayList< String > labels,
			final FusionType fusionType,
			final boolean displayDistances,
			final long[] controlPointDistance,
			final double alpha,
			final boolean virtualGrid,
			final int interpolation,
			final Interval boundingBox,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final ExecutorService service )
	{
		/*
		final Pair< Interval, AffineTransform3D > scaledBB = FusionTools.createDownsampledBoundingBox( boundingBox, downsampling );

		final Interval bbDS = scaledBB.getA();
		final AffineTransform3D bbTransform = scaledBB.getB();
		*/

		// finding the corresponding interest points is the same for all levels
		final HashMap< ViewId, Collection< CorrespondingIP > > annotatedIps = NonRigidTools.assembleIPsForNonRigid( viewInterestPoints, viewsToUse, labels );

		// find unique interest points in the pairs of images
		final ArrayList< HashSet< CorrespondingIP > > uniqueIPs = NonRigidTools.findUniqueInterestPoints( annotatedIps );

		// create final registrations for all views and a list of corresponding interest points
		//final HashMap< ViewId, AffineTransform3D > registrations = createRegistrations( viewsToUse, viewRegistrations );
		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						viewRegistrations,
						Double.NaN,
						Double.NaN );

		// transform unique interest points
		final ArrayList< HashSet< CorrespondingIP > > transformedUniqueIPs = NonRigidTools.transformUniqueIPs( uniqueIPs, registrations );

		// compute an average location of each unique interest point that is defined by many (2...n) corresponding interest points
		// this location in world coordinates defines where each individual point should be "warped" to
		final Pair< HashMap< ViewId, ArrayList< SimpleReferenceIP > >, Double > uniquePointsData = NonRigidTools.computeReferencePoints( annotatedIps.keySet(), transformedUniqueIPs );

		// compute all grids, if it does not contain a grid we use the old affine model
		final HashMap< ViewId, ModelGrid > nonrigidGrids = NonRigidTools.computeGrids( viewsToFuse, uniquePointsData.getA(), controlPointDistance, alpha, boundingBox, virtualGrid, service );

		// create virtual images
		final Pair< ArrayList< RandomAccessibleInterval< FloatType > >, ArrayList< RandomAccessibleInterval< FloatType > > > virtual =
				createNonRigidVirtualImages(
						imgloader,
						viewDescriptions,
						viewsToFuse,
						registrations,
						nonrigidGrids,
						boundingBox,
						fusionType,
						displayDistances,
						interpolation,
						intensityAdjustments,
						defaultOverlapExpansion( uniquePointsData.getB() ) );

		final Fusion fusion;

		if ( fusionType == FusionType.AVG || fusionType == FusionType.AVG_BLEND || fusionType == FusionType.AVG_BLEND_CONTENT || fusionType == FusionType.AVG_CONTENT )
			fusion = Fusion.AVG;
		else if ( fusionType == FusionType.MAX_INTENSITY )
			fusion = Fusion.MAX;
		else if ( fusionType == FusionType.LOWEST_VIEWID_WINS )
			fusion = Fusion.FIRST_WINS;
		else
			throw new RuntimeException("fusion type " + fusionType + " not supported.");


		return new FusedRandomAccessibleInterval( FusionTools.getFusedZeroMinInterval( boundingBox ), fusion, virtual.getA(), virtual.getB() );
		//return new ValuePair<>( new FusedRandomAccessibleInterval( FusionTools.getFusedZeroMinInterval( bbDS ), virtual.getA(), virtual.getB() ), bbTransform );
	}

	public static int defaultOverlapExpansion( final double maxDistance )
	{
		return Math.max( LazyFusionTools.defaultNonrigidExpansion, (int)Math.round( maxDistance * 1.5 ) );
	}

	public static ArrayList< ViewId > assembleViewsToUse(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewsToFuse,
			final boolean nonRigidAcrossTime )
	{
		final ArrayList< ViewId > viewsToUse = new ArrayList<>();

		final ArrayList< TimePoint > tps = SpimData2.getAllTimePointsSorted( spimData, viewsToFuse );

		if ( tps.size() > 1 && nonRigidAcrossTime == false )
		{
			IOFunctions.println( "ERROR: You selected to not have non-rigid across time but you fuse views from different timepoints. Stopping." );
			return null;
		}

		if ( tps.size() == 1 )
		{
			final int tpId = tps.get( 0 ).getId();

			for ( final ViewDescription vd : spimData.getSequenceDescription().getViewDescriptions().values() )
				if ( vd.isPresent() && vd.getTimePointId() == tpId )
					viewsToUse.add( vd );
		}
		else
		{
			for ( final ViewDescription vd : spimData.getSequenceDescription().getViewDescriptions().values() )
				if ( vd.isPresent() && tps.contains( vd.getTimePoint() ) )
					viewsToUse.add( vd );
		}

		return viewsToUse;
	}
	public static HashMap< ViewId, Collection< CorrespondingIP > > assembleIPsForNonRigid(
			final Map< ViewId, ViewInterestPointLists > viewInterestPoints,
			final Collection< ? extends ViewId > viewsToUse,
			final List< String > labels )
	{
		final HashMap< ViewId, Collection< CorrespondingIP > > annotatedIps = new HashMap<>();

		for ( final ViewId viewId : viewsToUse )
		{
			final Collection< CorrespondingIP > aips = new ArrayList<>();

			for ( final String label : labels )
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loading corresponding interest points for " + Group.pvid( viewId ) + ", label '" + label + "'" );
				
				if ( viewInterestPoints.get( viewId ).contains( label ) )
				{
					final InterestPoints ipList = viewInterestPoints.get( viewId ).getInterestPointList( label );

					final Collection< CorrespondingInterestPoints > cipList = ipList.getCorrespondingInterestPointsCopy();
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": There are " + cipList.size() + " corresponding interest points in total (to all views)." );

					final Collection< CorrespondingIP > aipsTmp = NonRigidTools.assembleAllCorrespondingPoints( viewId, ipList, cipList, viewsToUse, viewInterestPoints );

					if ( aipsTmp == null )
						IOFunctions.println( new Date( System.currentTimeMillis() ) + ": FAILED to assemble pairs of corresponding interest points for label " + label + " in view " + Group.pvid( viewId ) );
					else
						aips.addAll( aipsTmp );
				}
				else
				{
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Label '" + label + "' does not exist in view " + Group.pvid( viewId ) );
				}
			}

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Loaded " + aips.size() + " pairs of corresponding interest points." );

			if ( aips.size() < 12 )
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": This number is not sufficient for non-rigid, using pre-computed affine." );
			}
			else
			{
				annotatedIps.put( viewId, aips );
			}
		}

		return annotatedIps;
	}

	public static Pair< ArrayList< RandomAccessibleInterval< FloatType > >, ArrayList< RandomAccessibleInterval< FloatType > > > createNonRigidVirtualImages(
			final BasicImgLoader imgloader,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Collection< ? extends ViewId > viewsToFuse,
			final Map< ViewId, AffineTransform3D > downsampledRegistrations,
			final HashMap< ViewId, ModelGrid > nonrigidGrids,
			final Interval bbDS,
			final FusionType fusionType,
			final boolean displayDistances,
			final int interpolation,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final int overlapExpansion )
	{
		final ArrayList< RandomAccessibleInterval< FloatType > > images = new ArrayList<>();
		final ArrayList< RandomAccessibleInterval< FloatType > > weights = new ArrayList<>();

		final ArrayList< ViewId > viewIdsToProcess =
				LazyFusionTools.overlappingViewIds(
						bbDS,
						viewsToFuse,
						downsampledRegistrations,
						LazyFusionTools.assembleDimensions( viewsToFuse, viewDescriptions ),
						overlapExpansion );

		// nothing to save...
		if ( viewIdsToProcess.size() == 0 )
		{
			images.add(
					Views.interval(
							new ConstantRandomAccessible< FloatType >( new FloatType( 0 ), 3 ),
							new FinalInterval( FusionTools.getFusedZeroMinInterval( bbDS ) ) ) );

			weights.add(
					Views.interval(
							new ConstantRandomAccessible< FloatType >( new FloatType( 1 ), 3 ),
							new FinalInterval( FusionTools.getFusedZeroMinInterval( bbDS ) ) ) );

			return new ValuePair<>( images, weights );
		}

		for ( final ViewId viewId : viewIdsToProcess )
		{
			final ModelGrid grid = nonrigidGrids.get( viewId );
			final AffineTransform3D modelAffine = downsampledRegistrations.get( viewId ).copy(); // will be modified potentially
			final AffineModel3D invertedModelOpener;
			RandomAccessibleInterval inputImg;

			final double[] usedDownsamplingFactors = new double[] { 1, 1, 1 };

			if ( !displayDistances )
			{
				//
				// Display images (open smaller images if it makes sense)
				//

				// the model necessary to map to the image opened at a reduced resolution level
				final Pair< RandomAccessibleInterval, AffineTransform3D > inputData =
						DownsampleTools.openDownsampled2( imgloader, viewId, modelAffine, usedDownsamplingFactors );
	
				// concatenate the downsampling transformation model to the affine transform
				if ( inputData.getB() != null )
				{
					modelAffine.concatenate( inputData.getB() );
					invertedModelOpener = TransformationTools.getModel( inputData.getB() ).createInverse();
				}
				else
				{
					invertedModelOpener = null;
				}
	
				inputImg = inputData.getA();

				if ( intensityAdjustments != null && intensityAdjustments.containsKey( viewId ) )
					inputImg = Converters.convert(
							FusionTools.convertInput( inputImg ),
							new IntensityAdjuster( intensityAdjustments.get( viewId ) ),
							new FloatType() );

				if ( grid == null )
					images.add( TransformView.transformView( inputImg, modelAffine, bbDS, 0, interpolation ) );
				else
					images.add( NonRigidTools.transformViewNonRigidInterpolated( inputImg, grid, invertedModelOpener, bbDS, 0, interpolation ) );
			}
			else
			{
				//
				// Display distances (open at full resolution)
				//

				invertedModelOpener = null;
				inputImg = imgloader.getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

				if ( grid == null )
					images.add( Views.interval( new ConstantRandomAccessible< FloatType >( new FloatType( 0 ), 3 ), new FinalInterval( bbDS ) ) );
				else
					images.add( NonRigidTools.visualizeDistancesViewNonRigidInterpolated( inputImg, grid, modelAffine, bbDS, 0, interpolation ) );
			}

			//
			// weights
			//
			if ( fusionType == FusionType.AVG_BLEND || fusionType == FusionType.AVG_BLEND_CONTENT || fusionType == FusionType.AVG_CONTENT )
			{
				RandomAccessibleInterval< FloatType > transformedBlending = null, transformedContentBased = null;

				// instantiate blending if necessary
				if ( fusionType == FusionType.AVG_BLEND || fusionType == FusionType.AVG_BLEND_CONTENT )
				{
					final float[] blending = Util.getArrayFromValue( FusionTools.defaultBlendingRange, 3 );
					final float[] border = Util.getArrayFromValue( FusionTools.defaultBlendingBorder, 3 );

					// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
					FusionTools.adjustBlending( viewDescriptions.get( viewId ), blending, border, modelAffine );

					if ( grid == null )
						transformedBlending = TransformWeight.transformBlending( inputImg, border, blending, modelAffine, bbDS );
					else
						transformedBlending = NonRigidWeightTools.transformWeightNonRigidInterpolated(
								new BlendingRealRandomAccessible(
										new FinalInterval( inputImg ), border, blending ),
										grid,
										invertedModelOpener,
										bbDS );
				}

				// instantiate content based if necessary
				if ( fusionType == FusionType.AVG_BLEND_CONTENT || fusionType == FusionType.AVG_CONTENT )
				{
					final double[] sigma1 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma1, 3 );
					final double[] sigma2 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma2, 3 );

					// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
					FusionTools.adjustContentBased( viewDescriptions.get( viewId ), sigma1, sigma2, usedDownsamplingFactors, Double.NaN );

					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Estimating Entropy for " + Group.pvid( viewId ) );

					if ( grid == null )
						transformedContentBased = TransformWeight.transformContentBased( inputImg, sigma1, sigma2, DoGImgLib2.blockSize, ContentBasedRealRandomAccessible.defaultScale, modelAffine, bbDS );
					else
						transformedContentBased = 
								NonRigidWeightTools.transformWeightNonRigidInterpolated(
									new ContentBasedRealRandomAccessible(
											inputImg,
											sigma1,
											sigma2,
											DoGImgLib2.blockSize,
											ContentBasedRealRandomAccessible.defaultScale ),
									grid,
									invertedModelOpener,
									bbDS );
				}

				if ( fusionType == FusionType.AVG_BLEND_CONTENT )
				{
					weights.add( new CombineWeightsRandomAccessibleInterval(
									new FinalInterval( transformedBlending ),
									transformedBlending,
									transformedContentBased,
									CombineType.MUL ) );
				}
				else if ( fusionType == FusionType.AVG_BLEND )
				{
					weights.add( transformedBlending );
				}
				else if ( fusionType == FusionType.AVG_CONTENT )
				{
					weights.add( transformedContentBased );
				}
			}
			else
			{
				final RandomAccessibleInterval< FloatType > imageArea =
						Views.interval( new ConstantRandomAccessible< FloatType >( new FloatType( 1 ), 3 ), new FinalInterval( inputImg ) );

				if ( grid == null )
					weights.add( TransformView.transformView( imageArea, modelAffine, bbDS, 0, 0 ) );
				else
					weights.add( NonRigidTools.transformViewNonRigidInterpolated( imageArea, grid, invertedModelOpener, bbDS, 0, 0 ) );
			}
		}

		return new ValuePair<>( images, weights );
	}

	/*
	public static HashMap< ViewId, AffineTransform3D > createRegistrations(
			final Collection< ? extends ViewId > viewsToUse,
			final Map< ViewId, AffineTransform3D > viewRegistrations )
	{
		final HashMap< ViewId, AffineTransform3D > registrations = new HashMap<>();

		for ( final ViewId viewId : viewsToUse )
		{
			// we must copy the model and not modify the existing one
			final AffineTransform3D model = viewRegistrations.get( viewId ).copy();

			registrations.put( viewId, model );
		}

		return registrations;
	}*/

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformViewNonRigidInterpolated(
			final RandomAccessibleInterval< T > input,
			final ModelGrid grid,
			final AffineModel3D invertedModelOpener,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final long[] size = new long[ input.numDimensions() ];

		for ( int d = 0; d < size.length; ++d )
			size[ d ] = boundingBox.dimension( d );

		final InterpolatingNonRigidRandomAccessible< T > virtual = new InterpolatingNonRigidRandomAccessible< T >( input, grid, invertedModelOpener, false, 0.0f, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( size ) );
	}

	public static < T extends RealType< T > > RandomAccessibleInterval< FloatType > transformViewNonRigid(
			final RandomAccessibleInterval< T > input,
			final Collection< ? extends NonrigidIP > ips,
			final double alpha,
			final AffineModel3D invertedModelOpener,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final long[] size = new long[ input.numDimensions() ];

		for ( int d = 0; d < size.length; ++d )
			size[ d ] = boundingBox.dimension( d );

		final NonRigidRandomAccessible< T > virtual = new NonRigidRandomAccessible< T >( input, ips, alpha, invertedModelOpener, false, 0.0f, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( size ) );
	}

	public static RandomAccessibleInterval< FloatType > visualizeDistancesViewNonRigidInterpolated(
			final Interval input,
			final ModelGrid grid,
			final AffineTransform3D originalTransform,
			final Interval boundingBox,
			final float outsideValue,
			final int interpolation )
	{
		final long[] size = new long[ input.numDimensions() ];

		for ( int d = 0; d < size.length; ++d )
			size[ d ] = boundingBox.dimension( d );

		final DistanceVisualizingRandomAccessible virtual = new DistanceVisualizingRandomAccessible( input, grid, originalTransform, new FloatType( outsideValue ), boundingBox );

		if ( interpolation == 0 )
			virtual.setNearestNeighborInterpolation();
		else
			virtual.setLinearInterpolation();

		return Views.interval( virtual, new FinalInterval( size ) );
	}

	public static HashMap< ViewId, ModelGrid > computeGrids(
			final Collection< ? extends ViewId > viewsToFuse,
			final HashMap< ? extends ViewId, ? extends Collection< ? extends NonrigidIP > > uniquePoints,
			final long[] controlPointDistance,
			final double alpha,
			final Interval boundingBox,
			final boolean virtual,
			final ExecutorService service )
	{
		final ArrayList< Callable< Pair< ViewId, ModelGrid > > > tasks = new ArrayList<>();

		for ( final ViewId viewId : viewsToFuse )
		{
			tasks.add( new Callable< Pair< ViewId, ModelGrid > >()
			{
				@Override
				public Pair< ViewId, ModelGrid > call() throws Exception
				{
					final Collection< ? extends NonrigidIP > ips = uniquePoints.get( viewId );

					if ( ips == null )
					{
						IOFunctions.println( new Date( System.currentTimeMillis() ) + ": NO POINTS to interpolate non-rigid model for " + Group.pvid( viewId ) + " - using affine model" );
						return new ValuePair< ViewId, ModelGrid >( null, null );
					}

					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Interpolating non-rigid model (a=" + alpha + ") for " + Group.pvid( viewId ) + " using " + ips.size() + " points and stepsize " + Util.printCoordinates( controlPointDistance ) + " Interval: " + Util.printInterval( boundingBox ) );

					try
					{
						final ModelGrid grid = new ModelGrid( controlPointDistance, boundingBox, ips, alpha, virtual );
						return new ValuePair< ViewId, ModelGrid >( viewId, grid );
					}
					catch ( Exception e )
					{
						IOFunctions.println( new Date( System.currentTimeMillis() ) + ": FAILED to interpolate non-rigid model for " + Group.pvid( viewId ) + ": " + e + " - using affine model" );
						e.printStackTrace();
						return new ValuePair< ViewId, ModelGrid >( null, null );
					}
				}
			});
		}

		final HashMap< ViewId, ModelGrid > nonrigidGrids = new HashMap<>();

		try
		{
			// invokeAll() returns when all tasks are complete
			final List< Future< Pair< ViewId, ModelGrid > > > futures = service.invokeAll( tasks );

			for ( final Future< Pair< ViewId, ModelGrid > > f : futures )
			{
				final Pair< ViewId, ModelGrid > p = f.get();

				if ( p.getA() != null && p.getB() != null )
					nonrigidGrids.put( p.getA(), p.getB() );
			}

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": In total " + nonrigidGrids.keySet().size() + "/" + viewsToFuse.size() + " views are fused non-rigidly," );

			return nonrigidGrids;
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Failed to compute non-rigid grids: " + e );
			e.printStackTrace();
			return null;
		}
	}

	public static Pair< HashMap< ViewId, ArrayList< SimpleReferenceIP > >, Double > computeReferencePoints(
			final Collection< ViewId > views,
			final ArrayList< HashSet< CorrespondingIP > > uniqueIPs )
	{
		final RealSum sum = new RealSum();
		int countDist = 0;
		double maxDist = 0;

		// compute the centers
		for ( final HashSet< CorrespondingIP > uniqueIP : uniqueIPs )
		{
			final double[] avgPosW = new double[ 3 ];

			for ( final CorrespondingIP aip : uniqueIP )
			{
				for ( int d = 0; d < avgPosW.length; ++d )
					avgPosW[ d ] += aip.w[ d ];
			}

			for ( int d = 0; d < avgPosW.length; ++d )
				avgPosW[ d ] /= (double)uniqueIP.size();
	
			for ( final CorrespondingIP aip : uniqueIP )
			{
				aip.setTargetW( avgPosW );

				final double dist = Math.sqrt( BoundingBoxReorientation.squareDistance( aip.w[ 0 ], aip.w[ 1 ], aip.w[ 2 ], aip.targetW[ 0 ], aip.targetW[ 1 ], aip.targetW[ 2 ] ) );
				sum.add( dist );
				maxDist = Math.max( maxDist, dist );
				++countDist;
			}
		}

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Avg/Max distance from unique interest point: " + ( sum.getSum() / (double)countDist) + "/" + maxDist );

		final ArrayList< ViewId > viewIds = new ArrayList<>( views );
		Collections.sort( viewIds );

		//final ArrayList< HashSet< CorrespondingIP > > uniqueIPs = findUniqueInterestPoints( aips );

		final HashMap< ViewId, ArrayList< SimpleReferenceIP > > uniquePointsPerView = new HashMap<>();

		//
		// go over all groups, find the points that belong to this view, make one point each
		//
		for ( final ViewId viewId : viewIds )
		{
			final ArrayList< SimpleReferenceIP > myIPs = new ArrayList<>();

			for ( final HashSet< CorrespondingIP > uniqueIP : uniqueIPs )
			{
				CorrespondingIP myIp = null;

				for ( final CorrespondingIP ip : uniqueIP )
				{
					if ( ip.getViewId().equals( viewId ) )
					{
						myIp = ip;
						break; // TODO: there could be more than one if there are inconsistencies ...
					}
				}

				if ( myIp != null )
					myIPs.add( new SimpleReferenceIP( myIp.getL(), myIp.getW(), myIp.getTargetW() ) );
			}

			uniquePointsPerView.put( viewId, myIPs );
			//IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Unique interest points for " + Group.pvid( viewId ) + ": " + myIPs.size() );
		}

		return new ValuePair<>( uniquePointsPerView, maxDist );
	}

	public static ArrayList< HashSet< CorrespondingIP > > findUniqueInterestPoints( final Map< ViewId, Collection< CorrespondingIP > > annotatedIps )
	{
		final ArrayList< CorrespondingIP > aips = new ArrayList<>();

		for ( final Collection< CorrespondingIP > aipl : annotatedIps.values() )
			aips.addAll( aipl );

		// find unique interest points in the pairs of images
		final ArrayList< HashSet< CorrespondingIP > > uniqueIPs = findUniqueInterestPoints( aips );

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Found " + uniqueIPs.size() + " unique interest points." );

		// some statistics
		final int[] count = uniqueInterestPointCounts( uniqueIPs );

		System.out.println( "Structure: " );
		for ( int i = 0; i < count.length; ++i )
			if ( count[ i ] > 0 )
				System.out.println( i + ": " + count[ i ] );

		return uniqueIPs;
	}

	public static ArrayList< HashSet< CorrespondingIP > > findUniqueInterestPoints( final Collection< CorrespondingIP > pairs)
	{
		final ArrayList< HashSet< CorrespondingIP > > groups = new ArrayList<>();
		final HashSet< CorrespondingIP > unassignedCorr = new HashSet<>( pairs );

		while ( unassignedCorr.size() > 0 )
		{
			final HashSet< CorrespondingIP > group = new HashSet<CorrespondingIP>();
			final CorrespondingIP start = unassignedCorr.iterator().next();
			group.add( start );
			unassignedCorr.remove( start );

			final LinkedList< CorrespondingIP > potentialCorrespondences = new LinkedList<>();
			potentialCorrespondences.add( start );

			final HashSet< CorrespondingIP > visited = new HashSet<>();

			while ( potentialCorrespondences.size() > 0 )
			{
				final CorrespondingIP pc = potentialCorrespondences.remove();
				visited.add( pc );

				final ArrayList< CorrespondingIP > toRemove = new ArrayList<>();

				for ( final CorrespondingIP newCorr : unassignedCorr )
				{
					if ( pc.ip.equals( newCorr.ip ) || pc.ip.equals( newCorr.corrIp ) || pc.corrIp.equals( newCorr.ip ) || pc.corrIp.equals( newCorr.corrIp ))
					{
						group.add( newCorr );
						toRemove.add( newCorr );
						potentialCorrespondences.add( newCorr );
					}
				}

				unassignedCorr.removeAll( toRemove );
			}

			groups.add( group );

		}
		return groups;
	}

	public static ArrayList< CorrespondingIP > copyIPs( final List< CorrespondingIP > in )
	{
		final ArrayList< CorrespondingIP > out = new ArrayList<>();

		for ( final CorrespondingIP ip : in )
			out.add( ip.copy() );

		return out;
	}
	public static HashSet< CorrespondingIP > copyIPs( final Set< CorrespondingIP > in )
	{
		final HashSet< CorrespondingIP > out = new HashSet<>();

		for ( final CorrespondingIP ip : in )
			out.add( ip.copy() );

		return out;
	}

	public static ArrayList< HashSet< CorrespondingIP > > transformUniqueIPs(
			final ArrayList< HashSet< CorrespondingIP > > uniqueIPs,
			final Map< ViewId, AffineTransform3D > downsampledRegistrations)
	{
		final ArrayList< HashSet< CorrespondingIP > > transformedUniqueIPs = new ArrayList<>();

		for ( final HashSet< CorrespondingIP > uniqueIP : uniqueIPs )
		{
			final HashSet< CorrespondingIP > transformedUniqueIP = copyIPs( uniqueIP );

			NonRigidTools.transformCorrespondingIPs( transformedUniqueIP, downsampledRegistrations );

			transformedUniqueIPs.add( transformedUniqueIP );
		}

		return transformedUniqueIPs;
	}

	/*
	public static HashMap< ViewId, ArrayList< CorrespondingIP > > transformAllAnnotatedIPs(
			final HashMap< ViewId, ArrayList< CorrespondingIP > > annotatedIps,
			final Map< ViewId, AffineTransform3D > downsampledRegistrations )
	{
		final HashMap< ViewId, ArrayList< CorrespondingIP > > transformedAnnotatedIps = new HashMap<>();

		for ( final ViewId viewId : annotatedIps.keySet() )
		{
			// they need to be copied since they might be used for multiple resolution levels
			final ArrayList< CorrespondingIP > aips = copyIPs( annotatedIps.get( viewId ) );

			final double dist = NonRigidTools.transformCorrespondingIPs( aips, downsampledRegistrations );
			transformedAnnotatedIps.put( viewId, aips );

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Average distance of " + Group.pvid( viewId ) + " = " + dist );
		}

		return transformedAnnotatedIps;
	}*/

	public static double transformCorrespondingIPs( final Collection< CorrespondingIP > aips, final Map< ViewId, AffineTransform3D > models )
	{
		final RealSum sum = new RealSum( aips.size() );

		for ( final CorrespondingIP aip : aips )
		{
			aip.transform( models.get( aip.viewId ), models.get( aip.corrViewId ) );

			sum.add( Math.sqrt( BoundingBoxReorientation.squareDistance( aip.w[ 0 ], aip.w[ 1 ], aip.w[ 2 ], aip.corrW[ 0 ], aip.corrW[ 1 ], aip.corrW[ 2 ] ) ) );
			//RealPoint.wrap( aip.w );
		}

		return sum.getSum() / (double)aips.size();
	}

	public static Collection< CorrespondingIP > assembleAllCorrespondingPoints(
			final ViewId viewId,
			final InterestPoints ipList,
			final Collection< ? extends CorrespondingInterestPoints > cipList,
			final Collection< ? extends ViewId > viewsToUse,
			final Map< ? extends ViewId, ? extends ViewInterestPointLists > interestPointLists )
	{
		// result
		final ArrayList< CorrespondingIP > ipPairs = new ArrayList<>();

		// sort all ViewIds into a set
		final HashSet< ViewId > views = new HashSet<>( viewsToUse );

		// sort all interest points into a HashMap
		final Map< Integer, InterestPoint > ips = ipList.getInterestPointsCopy();

		// sort all corresponding interest points into a HashMap
		final HashMap< ViewId, List< IPL > > loadedIps = new HashMap<>();

		for ( final CorrespondingInterestPoints cip : cipList )
		{
			// local interest point
			final InterestPoint ip = ips.get( cip.getDetectionId() );

			if ( ip == null )
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Instance for id=" + ip + " of local interest point does not exist. Stopping." );
				return null;
			}

			// get corresponding interest point instance without reloading all the time
			final ViewId corrViewId = cip.getCorrespondingViewId();
			final String corrLabel = cip.getCorrespodingLabel();

			// only processing those views that are requested
			if ( !views.contains( corrViewId ) )
				continue;

			final List< IPL > ipls;

			// were there ever any interest points for this corresponding ViewId loaded?
			if ( loadedIps.containsKey( corrViewId )  )
			{
				ipls = loadedIps.get( corrViewId );
			}
			else
			{
				ipls = new ArrayList<>();
				loadedIps.put( corrViewId, ipls );
			}

			// were the interest points for this corresponding label of this corresponding ViewId loaded?
			IPL ipl = getIPL( ipls, corrLabel );

			if ( ipl == null )
			{
				// load corresponding interest points
				final ViewInterestPointLists vipl = interestPointLists.get( corrViewId );

				if ( vipl == null )
				{
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": No interestpoints for " +  Group.pvid( corrViewId ) + " exist. Stopping." );
					return null;
				}

				final InterestPoints corrIpList = vipl.getInterestPointList( corrLabel );

				if ( corrIpList == null )
				{
					IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Corresponding Label '" + corrLabel + "' does not exist for " +  Group.pvid( corrViewId ) + ". Stopping." );
					return null;
				}

				// get all corresponding interest points as a HashMap
				final Map< Integer, InterestPoint > corrIps = corrIpList.getInterestPointsCopy();

				ipl = new IPL( corrLabel, corrIps );

				ipls.add( ipl );
			}

			final int corrId = cip.getCorrespondingDetectionId();
			final InterestPoint corrIp = ipl.map.get( corrId );

			if ( corrIp == null )
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Instance for id=" + corrId + " of corresponding Label '" + corrLabel + "' does not exist for " +  Group.pvid( corrViewId ) + ". Stopping." );
				return null;
			}
	
			ipPairs.add( new CorrespondingIP( ip, viewId, corrIp, corrViewId ) );
		}

		return ipPairs;
	}

	public static int[] uniqueInterestPointCounts( final List< HashSet< CorrespondingIP > > groups )
	{
		if ( groups == null || groups.size() == 0 )
			return new int[ 1 ];

		int maxSize = 0;

		for ( final HashSet< ? > group : groups )
			maxSize = Math.max( group.size(), maxSize );

		final int[] counts = new int[ maxSize + 1 ];

		for ( final HashSet< ? > group : groups )
			++counts[ group.size() ];

		return counts;
	}

	protected static IPL getIPL( final Collection< IPL > ipls, final String label )
	{
		for ( final IPL ipl : ipls )
			if ( ipl.label.equals( label ) )
				return ipl;

		return null;
	}

}
