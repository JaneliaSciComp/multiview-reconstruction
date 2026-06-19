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
package net.preibisch.mvrecon.process.fusion;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.process.downsampling.DownsampleTools;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.transformed.TransformView;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.fusion.transformed.TransformWeight;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval;
import net.preibisch.mvrecon.process.fusion.transformed.weightcombination.CombineWeightsRandomAccessibleInterval.CombineType;
import net.preibisch.mvrecon.process.fusion.transformed.weights.ContentBasedRealRandomAccessible;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;
import util.URITools;

public class WeightsExample
{
	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		// test a real scenario
		final SpimData2 spimData = new XmlIoSpimData2().load( URITools.toURI( "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml" ) );

		final List< ViewId > viewIds = new ArrayList< ViewId >();
		viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );

		// filter not present ViewIds
		final List< ViewId > removed = SpimData2.filterMissingViews( spimData, viewIds );
		IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Removed " +  removed.size() + " views because they are not present." );

		System.out.println( viewIds.size() + " views in total." );

		final BoundingBox boundingBox = spimData.getBoundingBoxes().getBoundingBoxes().get( 0 );

		System.out.println( "Using Bounding box: " + boundingBox );

		final double downsampling = 2;
		final Interval bb;

		if ( !Double.isNaN( downsampling ) )
			bb = TransformVirtual.scaleBoundingBox( boundingBox, 1.0 / downsampling );
		else
			bb = boundingBox;

		for ( int i = 1; i <= 1; ++i )
		{
			final ViewId viewId = viewIds.get( i );

			final ImgLoader imgloader = spimData.getSequenceDescription().getImgLoader();
			final ViewRegistration vr = spimData.getViewRegistrations().getViewRegistration( viewId );
			vr.updateModel();
			AffineTransform3D model = vr.getModel();

			if ( !Double.isNaN( downsampling ) )
			{
				model = model.copy();
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );
			}

			// this modifies the model so it maps from a smaller image to the global coordinate space,
			// which applies for the image itself as well as the weights since they also use the smaller
			// input image as reference
			final double[] usedDownsamplingFactors = new double[ 3 ];
			final RandomAccessibleInterval inputImg = DownsampleTools.openDownsampled( imgloader, viewId, model, usedDownsamplingFactors );
			final RandomAccessibleInterval transformedInput = TransformView.transformView( inputImg, model, bb, 0, 1 );

			final float[] blending =  Util.getArrayFromValue( FusionTools.defaultBlendingRange, 3 );
			final float[] border = Util.getArrayFromValue( FusionTools.defaultBlendingBorder, 3 );
			System.out.println( "Default blending = " + Util.printCoordinates( blending ) );
			System.out.println( "Default border = " + Util.printCoordinates( border ) );
			// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
			FusionTools.adjustBlending( spimData.getSequenceDescription().getViewDescription( viewId ), blending, border, model );
			System.out.println( "Adjusted blending = " + Util.printCoordinates( blending ) );
			System.out.println( "Adjusted border = " + Util.printCoordinates( border ) );
			final RandomAccessibleInterval< FloatType > transformedBlending = TransformWeight.transformBlending( inputImg, border, blending, model, bb );

			final double[] sigma1 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma1, 3 );
			final double[] sigma2 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma2, 3 );
			System.out.println( "Default sigma1 = " + Util.printCoordinates( sigma1 ) );
			System.out.println( "Default sigma2 = " + Util.printCoordinates( sigma2 ) );
			// adjust both for z-scaling (anisotropy), downsampling, and registrations itself
			FusionTools.adjustContentBased( spimData.getSequenceDescription().getViewDescription( viewId ), sigma1, sigma2, usedDownsamplingFactors, Double.NaN );
			System.out.println( "Adjusted sigma1 = " + Util.printCoordinates( sigma1 ) );
			System.out.println( "Adjusted sigma2 = " + Util.printCoordinates( sigma2 ) );

			final RandomAccessibleInterval< FloatType > transformedContentBased = TransformWeight.transformContentBased(
					inputImg,
					sigma1, sigma2,
					DoGImgLib2.blockSize, ContentBasedRealRandomAccessible.defaultScale,
					model, bb );

			final RandomAccessibleInterval< FloatType > combinedWeights =
					new CombineWeightsRandomAccessibleInterval(
							new FinalInterval( transformedBlending ),
							transformedBlending,
							transformedContentBased,
							CombineType.MUL );

			FusionTools.getImagePlusInstance( transformedInput, true, "inputImg", 0, 255, DisplayImage.service ).show();
			FusionTools.getImagePlusInstance( transformedBlending, true, "blending", 0, 1, DisplayImage.service ).show();
			FusionTools.getImagePlusInstance( transformedContentBased, true, "content", 0, 1, DisplayImage.service ).show();
			FusionTools.getImagePlusInstance( combinedWeights, true, "combinedWeights", 0, 1, DisplayImage.service ).show();
		}
	}
}
