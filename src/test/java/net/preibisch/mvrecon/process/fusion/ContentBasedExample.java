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

import java.io.File;

import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.spim.data.SpimDataException;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.transformed.weights.ContentBasedRealRandomAccessible;
import net.preibisch.mvrecon.process.interestpointdetection.methods.dog.DoGImgLib2;

public class ContentBasedExample
{
	public static void main( String[] args ) throws SpimDataException
	{
		new ImageJ();

		final Img< FloatType > img = IOFunctions.openAs32BitArrayImg( new File( "/Volumes/home/Data/brain/fused_tp_0_ch_0_correct4.tif" ) );
		//final ImagePlus imp = DisplayImage.getImagePlusInstance( img, true, "brain", Double.NaN, Double.NaN );
		//imp.show();

		testContentBased( img );
	}

	public static void testContentBased( final Img< FloatType > img )
	{
		final double[] sigma1 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma1, 3 );
		final double[] sigma2 = Util.getArrayFromValue( FusionTools.defaultContentBasedSigma2 / 10, 3 );

		final double scaling = 2.0;

		sigma1[ 0 ] /= scaling;
		sigma2[ 0 ] /= scaling;
		sigma1[ 1 ] /= scaling;
		sigma2[ 1 ] /= scaling;

		sigma1[ 2 ] /= scaling * 5.0;
		sigma2[ 2 ] /= scaling * 5.0;

		IOFunctions.println( "Computing ... " + Util.printCoordinates( sigma1 ) + ", " + Util.printCoordinates( sigma2 ) );

		ContentBasedRealRandomAccessible cb = new ContentBasedRealRandomAccessible( img, sigma1, sigma2, DoGImgLib2.blockSize, ContentBasedRealRandomAccessible.defaultScale );

		IOFunctions.println( "Done ... " );

		final ImagePlus imp = FusionTools.getImagePlusInstance( cb.getEntropy(), true, "brain", Double.NaN, Double.NaN, DisplayImage.service );
		imp.show();
	}
}
