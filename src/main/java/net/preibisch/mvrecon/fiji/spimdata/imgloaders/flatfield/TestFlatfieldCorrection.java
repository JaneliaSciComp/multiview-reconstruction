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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import java.io.File;

import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;

/**
 * Test class for on-the-fly flatfield/darkfield correction.
 *
 * Loads dataset.xml, wraps the ImgLoader with flatfield correction,
 * and displays corrected vs uncorrected images for comparison.
 */
public class TestFlatfieldCorrection
{
	// Mapping from ViewSetup ID to Tile ID (from dataset.xml)
	private static final int[][] SETUP_TO_TILE = {
		{ 0, 187 },
		{ 1, 188 },
		{ 2, 189 },
		{ 3, 200 },
		{ 4, 201 },
		{ 5, 202 },
		{ 6, 213 },
		{ 7, 214 },
		{ 8, 215 }
	};

	public static void main( String[] args ) throws SpimDataException
	{
		// Paths - adjust these as needed
		final String basePath = "/Users/innerbergerm/Projects/janelia/multiview-reconstruction/";
		final String xmlPath = basePath + "data/dataset.xml";
		final String correctionPath = basePath + "dark_and_flatfields/";

		// Which setup to display (0-8)
		final int setupToShow = 0;
		final int timepoint = 0;

		// Load the dataset
		System.out.println( "Loading dataset from: " + xmlPath );
		final SpimData2 data = new XmlIoSpimData2().load( xmlPath );

		// Get the original ImgLoader
		final ImgLoader originalImgLoader = data.getSequenceDescription().getImgLoader();

		// Create the flatfield-corrected wrapper
		final DefaultFlatfieldCorrectionWrappedImgLoader ffcImgLoader =
			new DefaultFlatfieldCorrectionWrappedImgLoader( originalImgLoader, true );

		// Configure correction images for each view setup
		System.out.println( "\nConfiguring flatfield correction:" );
		for ( int[] mapping : SETUP_TO_TILE )
		{
			final int setupId = mapping[ 0 ];
			final int tileId = mapping[ 1 ];

			// Find darkfield file
			final File darkfield = new File( correctionPath + "setup" + tileId + "-AVG_darkfield-fromdata.tif" );

			// Find flatfield file (try both naming conventions)
			File flatfield = new File( correctionPath + "setup" + tileId + "-flatfield.tif" );
			if ( !flatfield.exists() )
				flatfield = new File( correctionPath + "setup" + tileId + "-flatfield (fixed by mirroring).tif" );

			// Set the correction images
			final ViewId viewId = new ViewId( timepoint, setupId );

			if ( darkfield.exists() )
			{
				ffcImgLoader.setDarkImage( viewId, darkfield );
				System.out.println( "  Setup " + setupId + " (tile " + tileId + "): darkfield = " + darkfield.getName() );
			}
			else
			{
				System.out.println( "  Setup " + setupId + " (tile " + tileId + "): WARNING - darkfield not found: " + darkfield.getAbsolutePath() );
			}

			if ( flatfield.exists() )
			{
				ffcImgLoader.setBrightImage( viewId, flatfield );
				System.out.println( "  Setup " + setupId + " (tile " + tileId + "): flatfield = " + flatfield.getName() );
			}
			else
			{
				System.out.println( "  Setup " + setupId + " (tile " + tileId + "): WARNING - flatfield not found" );
			}
		}

		// Start ImageJ
		new ImageJ();

		// Get tile ID for display title
		int tileId = SETUP_TO_TILE[ setupToShow ][ 1 ];
		final ViewSetup vs = data.getSequenceDescription().getViewSetups().get( setupToShow );
		System.out.println( "\nDisplaying setup " + setupToShow + " (tile " + tileId + ")" );
		System.out.println( "  Dimensions: " + vs.getSize() );

		// Load and display UNCORRECTED image
		System.out.println( "Loading uncorrected image..." );
		ffcImgLoader.setActive( false );
		data.getSequenceDescription().setImgLoader( ffcImgLoader );

		final RandomAccessibleInterval< FloatType > uncorrected =
			data.getSequenceDescription().getImgLoader()
				.getSetupImgLoader( setupToShow )
				.getFloatImage( timepoint, false );
		ImageJFunctions.show( uncorrected, "Uncorrected - Setup " + setupToShow + " (tile " + tileId + ")" );

		// Load and display CORRECTED image
		System.out.println( "Loading corrected image..." );
		ffcImgLoader.setActive( true );

		final RandomAccessibleInterval< FloatType > corrected =
			data.getSequenceDescription().getImgLoader()
				.getSetupImgLoader( setupToShow )
				.getFloatImage( timepoint, false );
		ImageJFunctions.show( corrected, "Corrected - Setup " + setupToShow + " (tile " + tileId + ")" );

		System.out.println( "\nDone! Compare the two images to verify correction." );
		System.out.println( "Tip: Use Image > Adjust > Brightness/Contrast to compare intensity distributions." );
	}
}
