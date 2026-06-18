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
package net.preibisch.mvrecon.process.fusion.lazy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.google.common.collect.Sets;

import ij.ImageJ;
import mpicbg.models.AffineModel1D;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.Converter;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxTools;
import net.preibisch.mvrecon.process.deconvolution.DeconViews;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval.Fusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.CorrespondingIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidTools;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.SimpleReferenceIP;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.ModelGrid;
import util.URITools;

public class LazyNonRigidFusion <T extends RealType<T> & NativeType<T>> implements Consumer<RandomAccessibleInterval<T>>
{
	final Converter<FloatType, T> converter;
	final T type;
	final long[] globalMin;

	final BasicImgLoader imgloader;
	final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions;
	final Collection< ? extends ViewId > viewsToFuse;
	final HashMap< ViewId, AffineTransform3D > registrations;
	final HashMap< ViewId, ModelGrid > nonrigidGrids;
	final FusionType fusionType;
	final boolean displayDistances;
	final int interpolation;
	final Map< ? extends ViewId, AffineModel1D > intensityAdjustments;

	final double maxDist;

	/*
	 * Creates a consumer that will fill the requested RandomAccessibleInterval single-threaded, using common
	 * grids that are computed multi-threaded in the constructor
	 */
	public LazyNonRigidFusion(
			final Converter<FloatType, T> converter,
			final BasicImgLoader imgloader,
			final Map< ViewId, AffineTransform3D > viewRegistrations,
			final Map< ViewId, ViewInterestPointLists > viewInterestPoints,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final List< String > labels,
			final FusionType fusionType,
			final boolean displayDistances,
			final long[] controlPointDistance,
			final double alpha,
			final boolean virtualGrid,
			final int interpolation,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final ExecutorService service,
			final Interval boundingBox,
			final long[] globalMin,
			final T type )
	{
		// TODO: share cache for content-based fusion if wanted
		this.imgloader = imgloader;
		this.viewDescriptions = viewDescriptions;
		this.viewsToFuse = viewsToFuse;
		this.fusionType = fusionType;
		this.displayDistances = displayDistances;
		this.interpolation = interpolation;
		this.intensityAdjustments = intensityAdjustments;

		this.converter = converter;
		this.type = type;
		this.globalMin = globalMin;

		// finding the corresponding interest points is the same for all levels
		final HashMap< ViewId, Collection< CorrespondingIP > > annotatedIps = NonRigidTools.assembleIPsForNonRigid( viewInterestPoints, viewsToUse, labels );

		// find unique interest points in the pairs of images
		final ArrayList< HashSet< CorrespondingIP > > uniqueIPs = NonRigidTools.findUniqueInterestPoints( annotatedIps );

		// create final registrations for all views and a list of corresponding interest points
		//final HashMap< ViewId, AffineTransform3D > registrations = createRegistrations( viewsToUse, viewRegistrations );
		this.registrations =
				TransformVirtual.adjustAllTransforms(
						viewRegistrations,
						Double.NaN,
						Double.NaN );

		// transform unique interest points
		final ArrayList< HashSet< CorrespondingIP > > transformedUniqueIPs = NonRigidTools.transformUniqueIPs( uniqueIPs, registrations );

		// compute an average location of each unique interest point that is defined by many (2...n) corresponding interest points
		// this location in world coordinates defines where each individual point should be "warped" to
		final Pair< HashMap< ViewId, ArrayList< SimpleReferenceIP > >, Double > uniquePointsData = NonRigidTools.computeReferencePoints( annotatedIps.keySet(), transformedUniqueIPs );
		this.maxDist = uniquePointsData.getB();

		// compute all grids, if it does not contain a grid we use the old affine model
		this.nonrigidGrids = NonRigidTools.computeGrids( viewsToFuse, uniquePointsData.getA(), controlPointDistance, alpha, boundingBox, virtualGrid, service );
	}

	// Note: the output RAI typically sits at 0,0...0 because it usually is a CachedCellImage
	// (but the actual interval to process in many blocks sits somewhere else) 
	@Override
	public void accept( final RandomAccessibleInterval<T> output )
	{
		// in world coordinates
		final Interval targetBlock = Intervals.translate( new FinalInterval( output ), globalMin );

		// create virtual images
		final Pair< ArrayList< RandomAccessibleInterval< FloatType > >, ArrayList< RandomAccessibleInterval< FloatType > > > virtual =
				NonRigidTools.createNonRigidVirtualImages(
						imgloader,
						viewDescriptions,
						viewsToFuse,
						registrations,
						nonrigidGrids,
						targetBlock,
						fusionType,
						displayDistances,
						interpolation,
						intensityAdjustments,
						NonRigidTools.defaultOverlapExpansion( maxDist ) );

		final Fusion fusion;

		if ( fusionType == FusionType.AVG || fusionType == FusionType.AVG_BLEND || fusionType == FusionType.AVG_BLEND_CONTENT || fusionType == FusionType.AVG_CONTENT )
			fusion = Fusion.AVG;
		else if ( fusionType == FusionType.MAX_INTENSITY )
			fusion = Fusion.MAX;
		else if ( fusionType == FusionType.LOWEST_VIEWID_WINS )
			fusion = Fusion.FIRST_WINS;
		else
			throw new RuntimeException("fusion type " + fusionType + " not supported.");

		final RandomAccessibleInterval<FloatType> fused =
				new FusedRandomAccessibleInterval(
						FusionTools.getFusedZeroMinInterval( targetBlock ),
						fusion,
						virtual.getA(),
						virtual.getB() );

		LazyAffineFusion.finish( fused, output, converter, type );
	}

	public static final <T extends RealType<T> & NativeType<T>> RandomAccessibleInterval<T> init(
			final Converter<FloatType, T> converter,
			final BasicImgLoader imgloader,
			final Map< ViewId, AffineTransform3D > viewRegistrations,
			final Map< ViewId, ViewInterestPointLists > viewInterestPoints,
			final Map< ViewId, ? extends BasicViewDescription< ? > > viewDescriptions,
			final Collection< ? extends ViewId > viewsToFuse,
			final Collection< ? extends ViewId > viewsToUse,
			final List< String > labels,
			final FusionType fusionType,
			final boolean displayDistances,
			final long[] controlPointDistance,
			final double alpha,
			final boolean virtualGrid,
			final int interpolation,
			final Map< ? extends ViewId, AffineModel1D > intensityAdjustments,
			final ExecutorService service,
			final Interval fusionInterval,
			final T type,
			final int[] blockSize )
	{
		final LazyNonRigidFusion< T > lazyNonRigidFusion =
				new LazyNonRigidFusion<>(
						converter,
						imgloader,
						viewRegistrations,
						viewInterestPoints,
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
						intensityAdjustments,
						service,
						fusionInterval,
						fusionInterval.minAsLongArray(),
						type.createVariable() );

		return LazyFusionTools.initLazy( lazyNonRigidFusion, fusionInterval, blockSize, type );
	}

	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		final SpimData2 spimData = new XmlIoSpimData2().load( URITools.toURI("/Users/preibischs/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml") );

		Interval boundingBox = BoundingBoxTools.getBoundingBox( spimData, "My Bounding Box" );
		IOFunctions.println( BoundingBox.getBoundingBoxDescription( (BoundingBox)boundingBox ) );

		// select views to process
		final List< ViewId > viewsToFuse = new ArrayList< ViewId >(); // fuse
		final List< ViewId > viewsToUse = new ArrayList< ViewId >(); // used to compute the non-rigid transform

		viewsToUse.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );
		viewsToFuse.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		final double anisotropy = Double.NaN;
		final double downsampling = Double.NaN;
		final double ds = Double.isNaN( downsampling ) ? 1.0 : downsampling;
		final int cpd = Math.max( 1, (int)Math.round( 10 / ds ) );
		final List< String > labels = Arrays.asList("nuclei"); //"beads13", "beads" 
		final int interpolation = 1;
		final long[] controlPointDistance = new long[] { cpd, cpd, cpd };
		final double alpha = 1.0;
		final boolean virtualGrid = false;
		final FusionType fusionType = FusionType.AVG_BLEND;
		final boolean displayDistances = false;
		final ExecutorService service = DeconViews.createExecutorService();

		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": controlPointDistance = " + Util.printCoordinates( controlPointDistance ) );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": starting with non-rigid" );

		// adjust bounding box
		boundingBox = FusionTools.createAnisotropicBoundingBox( boundingBox, anisotropy ).getA();
		boundingBox = FusionTools.createDownsampledBoundingBox( boundingBox, downsampling ).getA();

		// adjust registrations
		final HashMap< ViewId, AffineTransform3D > registrations =
				TransformVirtual.adjustAllTransforms(
						Sets.union( new HashSet<>( viewsToFuse ), new HashSet<>( viewsToUse ) ),
						spimData.getViewRegistrations().getViewRegistrations(),
						anisotropy,
						downsampling );

		RandomAccessibleInterval<FloatType> lazyFused = LazyNonRigidFusion.init(
				null,//(i,o) -> o.set(i),
				spimData.getSequenceDescription().getImgLoader(),
				registrations,
				spimData.getViewInterestPoints().getViewInterestPoints(),
				spimData.getSequenceDescription().getViewDescriptions(),
				viewsToFuse,
				viewsToUse,
				labels,
				fusionType,
				displayDistances,
				controlPointDistance,
				alpha,
				virtualGrid,
				interpolation,
				null,
				service,
				boundingBox,
				new FloatType(),
				new int[] { 128, 128, 1 } // good blocksize for displaying
				);

		service.shutdown();

		// Lazy Non-rigid fusion took: 263447 ms.
		long time = System.currentTimeMillis();
		ImageJFunctions.show( lazyFused, DisplayImage.service ).show();
		System.out.println( "Lazy Non-rigid fusion took: " + (System.currentTimeMillis() - time) + " ms.");

		//ImageJFunctions.show( lazyFused, service );
	}
}
