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
package net.preibisch.mvrecon.fiji.plugin.interactive;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvOptions;
import bdv.util.BdvPointsSource;
import bdv.util.volatiles.VolatileViews;
import mpicbg.models.AffineModel1D;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.type.volatiles.VolatileFloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.lazy.LazyFusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval.Fusion;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.CorrespondingIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidTools;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.SimpleReferenceIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;

public class MultiResolutionTools
{
	public static void updateBDV( final Bdv bdv )
	{
		final BdvOptions options = Bdv.options();
		options.addTo( bdv );
		final BdvPointsSource p = BdvFunctions.showPoints( new ArrayList<>(), "empty", options );
		p.removeFromBdv();
	}

	public static ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > createMultiResolutionNonRigid(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final ArrayList< String > labels,
			final long controlPointDistance,
			final Interval boundingBox,
			final ExecutorService service,
			final int minDS,
			final int maxDS,
			final int dsInc )
	{
		return createMultiResolutionNonRigid( spimData, viewsToFuse, viewsToUse, labels, FusionType.AVG_BLEND, false, controlPointDistance, 1.0, 1, boundingBox, null, service, minDS, maxDS, dsInc );
	}

	public static ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > createMultiResolutionNonRigid(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final ArrayList< String > labels,
			final FusionType fusionType,
			final boolean displayDistances,
			final long controlPointDistance,
			final double alpha,
			final int interpolation,
			final Interval boundingBox,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final ExecutorService service,
			final int minDS,
			final int maxDS,
			final int dsInc )
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

		return createMultiResolutionNonRigid( imgLoader, viewRegistrations, spimData.getViewInterestPoints().getViewInterestPoints(), viewDescriptions, viewsToFuse, viewsToUse, labels, fusionType, displayDistances, controlPointDistance, alpha, interpolation, boundingBox, intensityAdjustments, service, minDS, maxDS, dsInc );
	}

	public static ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > createMultiResolutionNonRigid(
			final BasicImgLoader imgloader,
			final Map< ViewId, AffineTransform3D > viewRegistrations,
			final Map< ViewId, ViewInterestPointLists > viewInterestPoints,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final ArrayList< String > labels,
			final FusionType fusionType,
			final boolean displayDistances,
			final long controlPointDistance,
			final double alpha,
			final int interpolation,
			final Interval boundingBox,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final ExecutorService service,
			final int minDS,
			final int maxDS,
			final int dsInc )
	{
		final ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes = new ArrayList<>();

		// finding the corresponding interest points is the same for all levels
		final HashMap< ViewId, Collection< CorrespondingIP > > annotatedIps = NonRigidTools.assembleIPsForNonRigid( viewInterestPoints, viewsToUse, labels );

		// find unique interest points in the pairs of images
		final ArrayList< HashSet< CorrespondingIP > > uniqueIPs = NonRigidTools.findUniqueInterestPoints( annotatedIps );

		for ( int downsampling = minDS; downsampling <= maxDS; downsampling *= dsInc )
		{
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Assembling Non-Rigid Multiresolution pyramid for downsampling=" + downsampling );

			final Pair< Interval, AffineTransform3D > scaledBB = FusionTools.createDownsampledBoundingBox( boundingBox, downsampling );

			final Interval bbDS = scaledBB.getA();
			final AffineTransform3D bbTransform = scaledBB.getB();

			// create final registrations for all views and a list of corresponding interest points
			//final HashMap< ViewId, AffineTransform3D > downsampledRegistrations = NonRigidTools.createDownsampledRegistrations( viewsToUse, viewRegistrations, downsampling );

			final HashMap< ViewId, AffineTransform3D > downsampledRegistrations =
					TransformVirtual.adjustAllTransforms(
							viewRegistrations,
							Double.NaN,
							downsampling );

			// transform unique interest points
			final ArrayList< HashSet< CorrespondingIP > > transformedUniqueIPs = NonRigidTools.transformUniqueIPs( uniqueIPs, downsampledRegistrations );

			// compute an average location of each unique interest point that is defined by many (2...n) corresponding interest points
			// this location in world coordinates defines where each individual point should be "warped" to
			final Pair< HashMap< ViewId, ArrayList< SimpleReferenceIP > >, Double > uniquePointsData = NonRigidTools.computeReferencePoints( annotatedIps.keySet(), transformedUniqueIPs );

			// compute all grids, if it does not contain a grid we use the old affine model
			final long cpd = Math.max( 2, (long)Math.round( controlPointDistance / downsampling ) );
			final HashMap< ViewId, ModelGrid > nonrigidGrids = NonRigidTools.computeGrids( viewsToFuse, uniquePointsData.getA(), new long[] { cpd, cpd, cpd }, alpha, bbDS, true, service );

			// create virtual images
			final Pair< ArrayList< RandomAccessibleInterval< FloatType > >, ArrayList< RandomAccessibleInterval< FloatType > > > virtual =
					NonRigidTools.createNonRigidVirtualImages(
							imgloader,
							viewDescriptions,
							viewsToFuse,
							downsampledRegistrations,
							nonrigidGrids,
							bbDS,
							fusionType,
							displayDistances,
							interpolation,
							intensityAdjustments,
							NonRigidTools.defaultOverlapExpansion( uniquePointsData.getB() ) );

			final Fusion fusion;

			if ( fusionType == FusionType.AVG || fusionType == FusionType.AVG_BLEND || fusionType == FusionType.AVG_BLEND_CONTENT || fusionType == FusionType.AVG_CONTENT )
				fusion = Fusion.AVG;
			else if ( fusionType == FusionType.MAX_INTENSITY )
				fusion = Fusion.MAX;
			else if ( fusionType == FusionType.LOWEST_VIEWID_WINS )
				fusion = Fusion.FIRST_WINS;
			else
				throw new RuntimeException("fusion type " + fusionType + " not supported.");


			multiRes.add( new ValuePair<>( new FusedRandomAccessibleInterval( FusionTools.getFusedZeroMinInterval( bbDS ), fusion, virtual.getA(), virtual.getB() ), bbTransform ) );
		}

		return multiRes;
	}

	public static ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > createMultiResolutionAffine(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewIds,
			final Interval boundingBox,
			final int minDS,
			final int maxDS,
			final int dsInc )
	{
		final HashMap< ViewId, AffineTransform3D > registrations = new HashMap<>();

		for ( final ViewId viewId : viewIds )
		{
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			registrations.put( viewId, vr.getModel().copy() );
		}

		return createMultiResolutionAffine(
				spimData.getSequenceDescription().getImgLoader(),
				registrations,
				spimData.getSequenceDescription().getViewDescriptions(),
				viewIds, FusionType.AVG_BLEND, 1, boundingBox, null, minDS, maxDS, dsInc );
	}

	public static ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > createMultiResolutionAffine(
			final BasicImgLoader imgloader,
			final Map< ViewId, AffineTransform3D > registrations,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Collection< ? extends ViewId > views,
			final FusionType fusionType,
			final int interpolation,
			final Interval boundingBox,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final int minDS,
			final int maxDS,
			final int dsInc )
	{
		final ArrayList< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes = new ArrayList<>();

		for ( int downsampling = minDS; downsampling <= maxDS; downsampling *= dsInc )
		{
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Assembling Affine Multiresolution pyramid for downsampling=" + downsampling );

			// adjust bounding box
			final Pair<Interval, AffineTransform3D> pair = FusionTools.createDownsampledBoundingBox( boundingBox, downsampling );

			// adjust registrations
			final HashMap< ViewId, AffineTransform3D > registrationsAdjusted =
					TransformVirtual.adjustAllTransforms(
							registrations,
							Double.NaN,
							downsampling );

			multiRes.add(
					new ValuePair<>(
							FusionTools.fuseVirtual(
									imgloader,
									registrationsAdjusted,
									viewDescriptions,
									views,
									fusionType,
									interpolation,
									pair.getA(), // bounding box
									intensityAdjustments ),
							pair.getB() ) );
		}

		return multiRes;
	}

	public static ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > createVolatileRAIs(
			final List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes )
	{
		return createVolatileRAIs( multiRes, FusionGUI.maxCacheSize, FusionGUI.cellDim );
	}

	public static ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > createVolatileRAIs(
			final List< Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > > multiRes,
			final long maxCacheSize,
			final int[] cellDim )
	{
		final ArrayList< Pair< RandomAccessibleInterval< VolatileFloatType >, AffineTransform3D > > volatileMultiRes = new ArrayList<>();

		for ( final Pair< RandomAccessibleInterval< FloatType >, AffineTransform3D > virtualImg : multiRes )
		{
			final RandomAccessibleInterval< FloatType > cachedImg = FusionTools.cacheRandomAccessibleInterval(
					virtualImg.getA(),
					FusionGUI.maxCacheSize,
					new FloatType(),
					FusionGUI.cellDim );

			final RandomAccessibleInterval< VolatileFloatType > volatileImg = VolatileViews.wrapAsVolatile( cachedImg );
			//DisplayImage.getImagePlusInstance( virtual, true, "ds="+ds, 0, 255 ).show();
			//ImageJFunctions.show( virtualVolatile );

			volatileMultiRes.add( new ValuePair<>( volatileImg, virtualImg.getB() ) );
		}

		return volatileMultiRes;
	}
}
