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
package net.preibisch.mvrecon.process.deconvolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.deconvolution.DeconViewPSF.PSFTYPE;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInit.PsiInitType;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInitAvgApproxFactory;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInitAvgPreciseFactory;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInitBlurredFusedFactory;
import net.preibisch.mvrecon.process.deconvolution.init.PsiInitFactory;
import net.preibisch.mvrecon.process.deconvolution.iteration.ComputeBlockThreadFactory;
import net.preibisch.mvrecon.process.deconvolution.iteration.sequential.ComputeBlockSeqThread;
import net.preibisch.mvrecon.process.deconvolution.iteration.sequential.ComputeBlockSeqThreadCPUFactory;
import net.preibisch.mvrecon.process.deconvolution.util.PSFPreparation;
import net.preibisch.mvrecon.process.deconvolution.util.ProcessInputImages;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import net.preibisch.mvrecon.process.fusion.transformed.FusedRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval.CombineType;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.psf.PSFCombination;
import net.preibisch.simulation.imgloader.SimulatedBeadsImgLoader;
import util.URITools;

public class DeconvolutionExample
{
	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		SpimData2 spimData;
		Collection< Group< ViewDescription > > groups = new ArrayList<>();

		// Generate 2 views with 200 beads, smaller image size for faster tests
		final int[] angles = new int[]{ 0, 90 };
		final int axis = 0;
		final int numPoints = 200;
		final double[] sigma = new double[]{ 1, 1, 3 };
		final FinalInterval range = new FinalInterval( 128, 128, 50 );

		spimData = SpimData2.convert( SimulatedBeadsImgLoader.spimdataExample( angles, axis, numPoints, sigma, range ) );
		groups = Group.toGroups( spimData.getSequenceDescription().getViewDescriptions().values() );

		// load drosophila
		spimData = new XmlIoSpimData2().load( URITools.toURI( "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml" ) );
		groups = selectViews( spimData.getSequenceDescription().getViewDescriptions().values() );
		groups = Group.toGroups( spimData.getSequenceDescription().getViewDescriptions().values() );

		/*
		final ArrayList< ViewDescription > two = new ArrayList<>();
		two.add( spimData.getSequenceDescription().getViewDescriptions().get( new ViewId( 0,0 ) ) );
		two.add( spimData.getSequenceDescription().getViewDescriptions().get( new ViewId( 0,1 ) ) );
		groups = oneGroupPerView( two );
		*/
		testDeconvolution( spimData, groups, "My Bounding Box111" );
		// for bounding box1111 test 128,128,128 vs 256,256,256 (no blocks), there are differences at the edges
		// 
	}

	public static < V extends ViewId > void testDeconvolution(
			final SpimData2 spimData,
			final Collection< Group< V > > groups,
			final String bbTitle )
	{
		// one common ExecutorService for all
		final ExecutorService service = DeconViews.createExecutorService();

		BoundingBox boundingBox = null;

		for ( final BoundingBox bb : spimData.getBoundingBoxes().getBoundingBoxes() )
			if ( bb.getTitle().equals( bbTitle ) )
				boundingBox = bb;

		if ( boundingBox == null )
		{
			System.out.println( "Bounding box '" + bbTitle + "' not found." );
			return;
		}

		IOFunctions.println( BoundingBox.getBoundingBoxDescription( boundingBox ) );

		final double osemSpeedUp = 1.0;
		final double downsampling = 3.0;

		final ProcessInputImages< V > fusion = new ProcessInputImages<>(
				spimData,
				groups,
				service,
				boundingBox,
				downsampling,
				true,
				FusionTools.defaultBlendingRange,
				FusionTools.defaultBlendingBorder,
				true,
				MultiViewDeconvolution.defaultBlendingRange,
				MultiViewDeconvolution.defaultBlendingBorder / ( Double.isNaN( downsampling ) ? 1.0f : (float)downsampling ),
				spimData.getIntensityAdjustments().getIntensityAdjustments() );

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Virtual Fusion of groups " );
		fusion.fuseGroups();

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Normalizing weights ... " );
		fusion.normalizeWeights( osemSpeedUp, false, MultiViewDeconvolution.maxDiffRange, MultiViewDeconvolution.scalingRange );

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): De-virtualization ... " );
		fusion.cacheImages();
		fusion.cacheNormalizedWeights();
		fusion.cacheUnnormalizedWeights();

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Displaying " );
		//displayDebug( fusion );
		//SimpleMultiThreading.threadHaltUnClean();

		IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Loading, grouping, and transforming PSF's " );

		final HashMap< Group< V >, ArrayImg< FloatType, ? > > psfs =
				PSFPreparation.loadGroupTransformPSFs( spimData.getPointSpreadFunctions(), fusion, false );

		//final Img< FloatType > avgPSF = PSFCombination.computeAverageImage( psfs.values(), new ArrayImgFactory< FloatType >(), true );
		//DisplayImage.getImagePlusInstance( Views.rotate( avgPSF, 0, 2 ), false, "avgPSF", 0, 1 ).show();

		final Img< FloatType > maxAvgPSF = PSFCombination.computeMaxAverageTransformedPSF( psfs.values(), new ArrayImgFactory< FloatType >() );
		FusionTools.getImagePlusInstance( maxAvgPSF, false, "maxAvgPSF", 0, 1, DisplayImage.service ).show();

		final ImgFactory< FloatType > blockFactory = new ArrayImgFactory<>();
		final ImgFactory< FloatType > psiFactory = new ArrayImgFactory<>();
		final int[] blockSize = new int[]{ 128, 256, 256 };
		final int numIterations = 1;
		final float lambda = 0.0006f;
		final PSFTYPE psfType = PSFTYPE.INDEPENDENT;
		final boolean filterBlocksForContent = true;
		final PsiInitType psiInitType = PsiInitType.FUSED_BLURRED;
		final boolean debug = true;
		final int debugInterval = 1;

		try
		{
			final ComputeBlockThreadFactory< ComputeBlockSeqThread > cptf = new ComputeBlockSeqThreadCPUFactory(
					service,
					lambda,
					blockSize,
					blockFactory );

			final PsiInitFactory psiInitFactory;

			if ( psiInitType == PsiInitType.FUSED_BLURRED )
				psiInitFactory = new PsiInitBlurredFusedFactory();
			else if ( psiInitType == PsiInitType.AVG )
				psiInitFactory = new PsiInitAvgPreciseFactory();
			else if ( psiInitType == PsiInitType.APPROX_AVG )
				psiInitFactory = new PsiInitAvgApproxFactory();
			else
				throw new RuntimeException( "PsiInitFactory '" + psiInitType + "' needs more parameters, please add the code." );

			if ( filterBlocksForContent )
				IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Setting up blocks for deconvolution and testing for empty ones that can be dropped." );
			else
				IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Setting up blocks for deconvolution." );

			final ArrayList< DeconView > deconViews = new ArrayList<>();

			for ( final Group< V > group : fusion.getGroups() )
			{
				final DeconView view = new DeconView(
						service,
						fusion.getImages().get( group ),
						fusion.getNormalizedWeights().get( group ),
						psfs.get( group ),
						psfType,
						blockSize,
						cptf.numParallelBlocks(),
						filterBlocksForContent );

				if ( view.getNumBlocks() <= 0 )
					return;

				view.setTitle( Group.gvids( group ) );
				deconViews.add( view );

				IOFunctions.println( "(" + new Date(System.currentTimeMillis()) + "): Added " + view );
			}

			final DeconViews views = new DeconViews( deconViews, service );

			final MultiViewDeconvolution< ? > decon = new MultiViewDeconvolutionSeq( views, numIterations, psiInitFactory, cptf, psiFactory );
			if ( !decon.initWasSuccessful() )
				return;
			decon.setDebug( debug );
			decon.setDebugInterval( debugInterval );
			decon.runIterations();
			

			ImagePlus imp = FusionTools.getImagePlusInstance( decon.getPSI(), false, "Deconvolved", Double.NaN, Double.NaN, DisplayImage.service );
			imp.getCalibration().xOrigin = -(boundingBox.min( 0 ) / downsampling);
			imp.getCalibration().yOrigin = -(boundingBox.min( 1 ) / downsampling);
			imp.getCalibration().zOrigin = -(boundingBox.min( 2 ) / downsampling);
			imp.getCalibration().pixelWidth = imp.getCalibration().pixelHeight = imp.getCalibration().pixelDepth = downsampling;
			imp.show();

			service.shutdown();
		}
		catch ( OutOfMemoryError oome )
		{
			IJ.log( "Out of Memory" );
			IJ.error("Multi-View Deconvolution", "Out of memory.  Check \"Edit > Options > Memory & Threads\"");

			service.shutdown();

			return;
		}
	}

	public static < V extends ViewId > void displayDebug( final ProcessInputImages< V > fusion )
	{
		int i = 0;

		final ArrayList< RandomAccessibleInterval< FloatType > > allWeightsNormed = new ArrayList<>();

		for ( final Group< V > group : fusion.getGroups() )
		{
			System.out.println( "Img Instance: " + fusion.getImages().get( group ).getClass().getSimpleName() );
			System.out.println( "Raw Weight Instance: " + fusion.getUnnormalizedWeights().get( group ).getClass().getSimpleName() );
			System.out.println( "Normalized Weight Instance: " + fusion.getNormalizedWeights().get( group ).getClass().getSimpleName() );

			FusionTools.getImagePlusInstance( fusion.getImages().get( group ), true, "g=" + i + " image", 0, 255, DisplayImage.service ).show();
			FusionTools.getImagePlusInstance( fusion.getUnnormalizedWeights().get( group ), true, "g=" + i + " weightsRawDecon", 0, 2, DisplayImage.service ).show();
			FusionTools.getImagePlusInstance( fusion.getNormalizedWeights().get( group ), true, "g=" + i + " weightsNormDecon", 0, 2, DisplayImage.service ).show();

			allWeightsNormed.add( fusion.getNormalizedWeights().get( group ) );

			// might not work if caching/copying is done before calling this method
			if ( FusedRandomAccessibleInterval.class.isInstance( fusion.getImages().get( group ) ) )
			{
				final long[] dim = new long[ fusion.getDownsampledBoundingBox().numDimensions() ];
				fusion.getDownsampledBoundingBox().dimensions( dim );
	
				FusionTools.getImagePlusInstance(
						new CombineWeightsRandomAccessibleInterval(
								new FinalInterval( dim ),
								((FusedRandomAccessibleInterval)fusion.getImages().get( group )).getWeights(),
								CombineType.SUM ),
						true,
						"g=" + i + " weightsFusion",
						0, 1, DisplayImage.service ).show();
			}

			++i;
		}

		// display the sum of all normed weights
		final long[] dim = new long[ fusion.getDownsampledBoundingBox().numDimensions() ];
		fusion.getDownsampledBoundingBox().dimensions( dim );

		FusionTools.getImagePlusInstance(
				new CombineWeightsRandomAccessibleInterval(
						new FinalInterval( dim ),
						allWeightsNormed,
						CombineType.SUM ),
				true,
				"sum of all normed weights",
				0, 1, DisplayImage.service ).show();
	}

	public static ArrayList< Group< ViewDescription > > selectViews( final Collection< ViewDescription > views )
	{
		final ArrayList< Group< ViewDescription > > groups = new ArrayList<>();

		final Group< ViewDescription > angle0and180 = new Group<>();
		final Group< ViewDescription > angle45and225 = new Group<>();
		final Group< ViewDescription > angle90and270 = new Group<>();

		for ( final ViewDescription vd : views )
		{
			final int angle = Integer.parseInt( vd.getViewSetup().getAngle().getName() );

			if ( angle == 0 || angle == 180 )
				angle0and180.getViews().add( vd );

			if ( angle == 45 || angle == 225 )
				angle45and225.getViews().add( vd );

			if ( angle == 90 || angle == 270 )
				angle90and270.getViews().add( vd );
		}

		groups.add( angle0and180 );
		groups.add( angle45and225 );
		groups.add( angle90and270 );

		System.out.println( "Views remaining:" );
		for ( final Group< ViewDescription > group : groups )
			System.out.println( group );

		return groups;
	}
}
