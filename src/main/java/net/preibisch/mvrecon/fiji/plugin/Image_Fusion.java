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
package net.preibisch.mvrecon.fiji.plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import com.google.common.collect.Sets;

import ij.ImageJ;
import ij.plugin.PlugIn;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.converter.Converter;
import net.imglib2.converter.RealUnsignedByteConverter;
import net.imglib2.converter.RealUnsignedShortConverter;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.multithreading.SimpleMultiThreading;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.process.export.Calibrateable;
import net.preibisch.mvrecon.process.export.ExportN5Api;
import net.preibisch.mvrecon.process.export.ImgExport;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.blk.BlkAffineFusion;
import net.preibisch.mvrecon.process.fusion.lazy.LazyAffineFusion;
import net.preibisch.mvrecon.process.fusion.lazy.LazyNonRigidFusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.NonRigidTools;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * Plugin to fuse images using transformations from the SpimData object
 * 
 * @author Stephan Preibisch (stephan.preibisch@gmx.de)
 *
 */
public class Image_Fusion implements PlugIn
{
	@Override
	public void run( String arg )
	{
		// ask for everything but the channels
		final LoadParseQueryXML result = new LoadParseQueryXML();

		if ( !result.queryXML( "Dataset Fusion", true, true, true, true, true ) )
			return;

		fuse( result.getData(), SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() ) );
	}

	public static boolean fuse(
			final SpimData2 spimData,
			final List< ViewId > viewsToProcess )
	{
		final FusionGUI fusion = new FusionGUI( spimData, viewsToProcess );

		if ( !fusion.queryDetails() )
			return false;

		final List< Group< ViewDescription > > groups = fusion.getFusionGroups();
		int i = 0;

		// adjust bounding box for preserve anisotropy
		if ( !Double.isNaN( fusion.getAnisotropyFactor() ) )
		{
			final Pair<Interval, AffineTransform3D> scaledBB =
					FusionTools.createAnisotropicBoundingBox(
							fusion.getBoundingBox(),
							fusion.getAnisotropyFactor() );

			// we need to update the bounding box here
			fusion.setBoundingBox( scaledBB.getA() );
		}

		// adjust bounding box for downsampling
		if ( !Double.isNaN( fusion.getDownsampling() ) )
		{
			final Pair< Interval, AffineTransform3D > scaledBB =
					FusionTools.createDownsampledBoundingBox( fusion.getBoundingBox(), fusion.getDownsampling() );
			// final AffineTransform3D bbTransform = scaledBB.getB();

			// we need to update the bounding box here
			fusion.setBoundingBox( scaledBB.getA() );
		}

		// query exporter parameters
		final ImgExport exporter = fusion.getNewExporterInstance();

		// query exporter parameters
		if ( !exporter.queryParameters( fusion ) )
			return false;

		// one common executerservice
		final ExecutorService taskExecutor = Executors.newFixedThreadPool( Threads.numThreads() );

		for ( final Group< ViewDescription > group : Group.getGroupsSorted( groups ) )
		{
			IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Fusing group " + (++i) + "/" + groups.size() + " (group=" + group + ")" );

			final Pair< double[], String > transformedCal = TransformationTools.computeAverageCalibration( group, spimData.getViewRegistrations() );
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Approximate pixel size of fused image (without downsampling): " + Arrays.toString( transformedCal.getA() ) + " " + transformedCal.getB() );

			if ( Calibrateable.class.isInstance( exporter ) )
				((Calibrateable)exporter).setCalibration( transformedCal.getA(), transformedCal.getB() );

			final ArrayList< ViewId > viewsToUse;

			if ( fusion.getNonRigidParameters().isActive() )
			{
				viewsToUse = NonRigidTools.assembleViewsToUse( spimData, group.getViews(), fusion.getNonRigidParameters().nonRigidAcrossTime() );

				if ( viewsToUse == null )
					return false;

				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Non-Rigid Views being used for current group" );

				for ( final ViewId v : viewsToUse )
					IOFunctions.println( "\t" + Group.pvid( v ) );
			}
			else
			{
				viewsToUse = null;
			}

			final int[] blocksize = exporter.blocksize();
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): block size used during fusion: " + Util.printCoordinates( blocksize ) );

			final Converter conv;
			final Type type;

			if ( fusion.getPixelType() == 2 )
			{
				conv = new RealUnsignedByteConverter<>( fusion.minIntensity(), fusion.maxIntensity() );
				type = new UnsignedByteType();
			}
			else if ( fusion.getPixelType() == 1 )
			{
				conv = new RealUnsignedShortConverter<>( fusion.minIntensity(), fusion.maxIntensity() );
				type = new UnsignedShortType();
			}
			else
			{
				conv = null;
				type = new FloatType();
			}

			// get, and update the transformations with anisotropy, downsampling
			final Set< ? extends ViewId > views =
					fusion.getNonRigidParameters().isActive() ?
							Sets.union( group.getViews(), viewsToUse.stream().collect( Collectors.toSet() ) ) : group.getViews();

			final HashMap< ViewId, AffineTransform3D > registrations =
					TransformVirtual.adjustAllTransforms(
							views,
							spimData.getViewRegistrations().getViewRegistrations(),
							fusion.getAnisotropyFactor(),
							fusion.getDownsampling() );

			//final RandomAccessibleInterval lazy;
			final BlockSupplier supplier;

			if ( fusion.getNonRigidParameters().isActive() )
			{
				supplier = BlockSupplier.of(
						LazyNonRigidFusion.init(
							conv,
							spimData.getSequenceDescription().getImgLoader(),
							registrations,
							spimData.getViewInterestPoints().getViewInterestPoints(),
							spimData.getSequenceDescription().getViewDescriptions(),
							group.getViews(),
							viewsToUse,
							fusion.getNonRigidParameters().getLabels(),
							fusion.getFusionType(),
							fusion.getNonRigidParameters().showDistanceMap(),
							Util.getArrayFromValue( fusion.getNonRigidParameters().getControlPointDistance(), 3 ),
							fusion.getNonRigidParameters().getAlpha(),
							false,
							fusion.getInterpolation(),
							fusion.adjustIntensities() ? spimData.getIntensityAdjustments().getIntensityAdjustments() : null,
							taskExecutor,
							fusion.getBoundingBox(),
							(RealType & NativeType)type,
							blocksize ) );

				// TODO: replace with LazyAffineFusion and varying blocksizes depending on the task
				/*
				virtual = NonRigidTools.fuseVirtualInterpolatedNonRigid(
								spimData.getSequenceDescription().getImgLoader(),
								registrations,
								spimData.getViewInterestPoints().getViewInterestPoints(),
								spimData.getSequenceDescription().getViewDescriptions(),
								group.getViews(),
								viewsToUse,
								fusion.getNonRigidParameters().getLabels(),
								fusion.useBlending(),
								fusion.useContentBased(),
								fusion.getNonRigidParameters().showDistanceMap(),
								Util.getArrayFromValue( fusion.getNonRigidParameters().getControlPointDistance(), 3 ),
								fusion.getNonRigidParameters().getAlpha(),
								false,
								fusion.getInterpolation(),
								fusion.getBoundingBox(),
								fusion.adjustIntensities() ? spimData.getIntensityAdjustments().getIntensityAdjustments() : null,
								taskExecutor );
				*/
			}
			else
			{
				System.out.println( "Image_Fusion.fuse" );
//				lazy = LazyAffineFusion.init(

				supplier = BlkAffineFusion.init(
						conv,
						spimData.getSequenceDescription().getImgLoader(),
						group.getViews(),
						registrations,
						spimData.getSequenceDescription().getViewDescriptions(),
						fusion.getFusionType(),
						fusion.getAnisotropyFactor(),
						fusion.getInterpolation(), // linear interpolatio
						fusion.adjustIntensities() ? spimData.getIntensityAdjustments().getIntensityAdjustments() : null,
						fusion.getBoundingBox(),
						(RealType & NativeType)type,
						blocksize );

				System.out.println( Util.printInterval( new FinalInterval( fusion.getBoundingBox().dimensionsAsLongArray() ) ) );
				System.out.println( Util.printInterval( fusion.getBoundingBox() ) );

				//ArrayImg img = ExportN5Api.arrayImg( supplier, new FinalInterval( fusion.getBoundingBox().dimensionsAsLongArray() ) );
				//ArrayImg img = ExportN5Api.arrayImg( supplier, new FinalInterval( new long[] { 128, 128, 64 } ) );
				//ImageJFunctions.show( img );
				//SimpleMultiThreading.threadHaltUnClean();
			}

			final String title = getTitle( fusion.getSplittingType(), group );
	
			if ( !exporter.exportImage(
					supplier,
					fusion.getBoundingBox(),
					fusion.getDownsampling(),
					fusion.getAnisotropyFactor(),
					title,
					group  ) )
				return false;
		}

		exporter.finish();
		
		taskExecutor.shutdown();

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): DONE." );

		return true;
	}

	public static double[] determineInputBitDepth( final Iterable< ? extends ViewDescription > group, final SpimData2 spimData )
	{
		SetupImgLoader< ? > loader = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( group.iterator().next().getViewSetupId() );
		Object type = loader.getImageType();

		if ( UnsignedByteType.class.isInstance( type ) )
			return new double[] { 0, 255 };
		else if ( UnsignedShortType.class.isInstance( type ) )
			return new double[] { 0, 65535 };
		else
		{
			IOFunctions.println( "WARNING: You are saving a non-8/16 bit input as 16bit, have to manually determine min/max of the fused image." );
			return null;
		}
	}

	public static double[] determineInputBitDepth( final Group< ViewDescription > group, final SpimData2 spimData, final RandomAccessibleInterval< FloatType > virtual )
	{
		SetupImgLoader< ? > loader = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( group.iterator().next().getViewSetupId() );
		Object type = loader.getImageType();

		if ( UnsignedByteType.class.isInstance( type ) )
			return new double[] { 0, 255 };
		else if ( UnsignedShortType.class.isInstance( type ) )
			return new double[] { 0, 65535 };
		else
		{
			IOFunctions.println( "WARNING: You are saving a non-8/16 bit input as 16bit, have to manually determine min/max of the fused image." );

			final float[] minmax = FusionTools.minMax( virtual );
			return new double[]{ minmax[ 0 ], minmax[ 1 ] };
		}
	}

	/*
	protected static < T extends RealType< T > & NativeType< T > > boolean cacheAndExport(
			final RandomAccessibleInterval< T > output,
			final ExecutorService taskExecutor,
			final T type,
			final FusionGUI fusion,
			final ImgExport exporter,
			final Group< ViewDescription > group,
			final double[] minmax )
	{
		RandomAccessibleInterval< T > processedOutput = null;

		if ( fusion.getCacheType() == 0 ) // Virtual
			processedOutput = output;
		else if ( fusion.getCacheType() == 1 ) // Cached
			processedOutput = FusionTools.cacheRandomAccessibleInterval( output, FusionGUI.maxCacheSize, type, FusionGUI.cellDim );
		else // Precomputed
		{
			if ( FloatType.class.isInstance( type ) )
			{
				//IJ.log( "fast float" );
				processedOutput = (RandomAccessibleInterval)ImagePlusAdapter.wrapFloat( DisplayImage.getImagePlusInstance( output, false, "Fused", 0, 255, taskExecutor ) );
			}
			else if ( UnsignedShortType.class.isInstance( type ) )
			{
				//IJ.log( "fast short" );
				processedOutput = (RandomAccessibleInterval)ImagePlusAdapter.wrapShort( DisplayImage.getImagePlusInstance( output, false, "Fused", 0, 255, taskExecutor ) );
			}

			if ( processedOutput == null )
			{
				IOFunctions.println( "WARNING: fall-back to slower fusion." );
				processedOutput = FusionTools.copyImg( output, new ImagePlusImgFactory< T >(), type, taskExecutor, true );
			}
		}
			

		final String title = getTitle( fusion.getSplittingType(), group );

		return exporter.exportImage( processedOutput, fusion.getBoundingBox(), fusion.getDownsampling(), fusion.getAnisotropyFactor(), title, group, minmax[ 0 ], minmax[ 1 ] );
	}
	*/

	public static String getTitle( final int splittingType, final Group< ViewDescription > group )
	{
		String title;
		final ViewDescription vd0 = group.iterator().next();

		if ( splittingType == 0 ) // "Each timepoint & channel"
			title = "fused_tp_" + vd0.getTimePointId() + "_ch_" + vd0.getViewSetup().getChannel().getId();
		else if ( splittingType == 1 ) // "Each timepoint, channel & illumination"
			title = "fused_tp_" + vd0.getTimePointId() + "_ch_" + vd0.getViewSetup().getChannel().getId() + "_illum_" + vd0.getViewSetup().getIllumination().getId();
		else if ( splittingType == 2 ) // "All views together"
			title = "fused";
		else // "All views"
			title = "fused_tp_" + vd0.getTimePointId() + "_vs_" + vd0.getViewSetupId();

		return title;
	}

	public static void main( String[] args )
	{
		IOFunctions.printIJLog = true;
		new ImageJ();

		if ( !System.getProperty("os.name").toLowerCase().contains( "mac" ) )
			GenericLoadParseQueryXML.defaultXMLURI = "/home/preibisch/Documents/Microscopy/SPIM/HisYFP-SPIM//dataset_tp18.xml";
		else
			GenericLoadParseQueryXML.defaultXMLURI = "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM//dataset.xml";

		new Image_Fusion().run( null );
	}
}
