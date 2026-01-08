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
import java.util.HashMap;

import bdv.ViewerImgLoader;
import bdv.ViewerSetupImgLoader;
import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;

/**
 * Test class for ViewerFlatfieldCorrectionWrappedImgLoader.
 *
 * Demonstrates the full ViewerImgLoader-compatible decorator chain:
 *   N5ImageLoader (ViewerImgLoader)
 *       -> ViewerFlatfieldCorrectionWrappedImgLoader (ViewerImgLoader)
 *           -> SplitViewerImgLoader (ViewerImgLoader)
 *
 * This maintains full BDV compatibility throughout the chain, including:
 * - Cache control delegation
 * - Volatile image support
 * - Multi-resolution mipmap levels
 */
public class TestViewerFlatfieldCorrection
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
		// ========== CONFIGURATION ==========
		final String basePath = "/Users/innerbergerm/Projects/janelia/multiview-reconstruction/";
		final String xmlPath = basePath + "data/dataset.xml";
		final String correctionPath = basePath + "dark_and_flatfields/";

		// Which setup to demonstrate (0-8)
		final int setupToShow = 0;
		final int timepoint = 0;

		// ========== STEP 1: Load dataset and get base ViewerImgLoader ==========
		System.out.println( "=== STEP 1: Loading dataset ===" );
		System.out.println( "XML path: " + xmlPath );

		final SpimData2 data = new XmlIoSpimData2().load( xmlPath );
		final SequenceDescription seqDesc = data.getSequenceDescription();

		// Verify the base loader is a ViewerImgLoader
		if ( !( seqDesc.getImgLoader() instanceof ViewerImgLoader ) )
		{
			System.err.println( "ERROR: Base loader is not a ViewerImgLoader!" );
			System.err.println( "Loader type: " + seqDesc.getImgLoader().getClass().getName() );
			return;
		}

		final ViewerImgLoader baseLoader = (ViewerImgLoader) seqDesc.getImgLoader();
		System.out.println( "Base loader type: " + baseLoader.getClass().getSimpleName() );
		System.out.println( "Base loader implements ViewerImgLoader: YES" );

		// ========== STEP 2: Wrap with ViewerFlatfieldCorrectionWrappedImgLoader ==========
		System.out.println( "\n=== STEP 2: Wrapping with ViewerFlatfieldCorrectionWrappedImgLoader ===" );

		final ViewerFlatfieldCorrectionWrappedImgLoader correctedLoader =
			new ViewerFlatfieldCorrectionWrappedImgLoader( baseLoader, true );

		// Configure correction images for each view setup
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

			final ViewId viewId = new ViewId( timepoint, setupId );

			if ( darkfield.exists() )
			{
				correctedLoader.setDarkImage( viewId, darkfield );
				System.out.println( "  Setup " + setupId + ": darkfield = " + darkfield.getName() );
			}

			if ( flatfield.exists() )
			{
				correctedLoader.setBrightImage( viewId, flatfield );
				System.out.println( "  Setup " + setupId + ": flatfield = " + flatfield.getName() );
			}
		}

		System.out.println( "Corrected loader implements ViewerImgLoader: " +
			( correctedLoader instanceof ViewerImgLoader ? "YES" : "NO" ) );

		// ========== STEP 3: Wrap with SplitViewerImgLoader ==========
		System.out.println( "\n=== STEP 3: Wrapping with SplitViewerImgLoader ===" );

		// Get original image dimensions for the setup we're demonstrating
		final ViewSetup vs = seqDesc.getViewSetups().get( setupToShow );
		final long[] dims = new long[ 3 ];
		vs.getSize().dimensions( dims );
		System.out.println( "Original image size: " + dims[0] + " x " + dims[1] + " x " + dims[2] );

		// Create a simple 2x1 split in X dimension
		final long splitX = dims[ 0 ] / 2;

		// Define the mappings for split regions
		final HashMap< Integer, Integer > new2oldSetupId = new HashMap<>();
		final HashMap< Integer, Interval > newSetupId2Interval = new HashMap<>();

		// Split region 0: left half
		new2oldSetupId.put( 100, setupToShow );
		newSetupId2Interval.put( 100, new FinalInterval(
			new long[] { 0, 0, 0 },
			new long[] { splitX - 1, dims[1] - 1, dims[2] - 1 }
		));

		// Split region 1: right half
		new2oldSetupId.put( 101, setupToShow );
		newSetupId2Interval.put( 101, new FinalInterval(
			new long[] { splitX, 0, 0 },
			new long[] { dims[0] - 1, dims[1] - 1, dims[2] - 1 }
		));

		System.out.println( "Created 2 split regions:" );
		System.out.println( "  Setup 100: X=[0, " + (splitX-1) + "] (left half)" );
		System.out.println( "  Setup 101: X=[" + splitX + ", " + (dims[0]-1) + "] (right half)" );

		// Create the split loader wrapping the CORRECTED ViewerImgLoader
		final SplitViewerImgLoader splitLoader = new SplitViewerImgLoader(
			correctedLoader,  // <-- ViewerImgLoader compatible!
			new2oldSetupId,
			newSetupId2Interval,
			seqDesc
		);

		System.out.println( "Split loader implements ViewerImgLoader: " +
			( splitLoader instanceof ViewerImgLoader ? "YES" : "NO" ) );

		// ========== STEP 4: Test ViewerImgLoader-specific features ==========
		System.out.println( "\n=== STEP 4: Testing ViewerImgLoader features ===" );

		// Test cache control delegation
		System.out.println( "Cache control available: " + ( splitLoader.getCacheControl() != null ) );

		// Test mipmap levels
		final ViewerSetupImgLoader< ?, ? > setupImgLoader = splitLoader.getSetupImgLoader( 100 );
		System.out.println( "Number of mipmap levels: " + setupImgLoader.numMipmapLevels() );

		final double[][] resolutions = setupImgLoader.getMipmapResolutions();
		System.out.println( "Mipmap resolutions:" );
		for ( int level = 0; level < resolutions.length; level++ )
		{
			System.out.println( "  Level " + level + ": " +
				resolutions[level][0] + " x " + resolutions[level][1] + " x " + resolutions[level][2] );
		}

		// ========== STEP 5: Display comparison images ==========
		System.out.println( "\n=== STEP 5: Displaying images ===" );
		new ImageJ();

		final int tileId = SETUP_TO_TILE[ setupToShow ][ 1 ];

		// 5a. Show UNCORRECTED original at level 0
		System.out.println( "Loading uncorrected image (level 0)..." );
		correctedLoader.setActive( false );
		final RandomAccessibleInterval< FloatType > uncorrected =
			correctedLoader.getSetupImgLoader( setupToShow ).getFloatImage( timepoint, 0, false );
		ImageJFunctions.show( uncorrected, "1. Uncorrected - Setup " + setupToShow + " (tile " + tileId + ")" );

		// 5b. Show CORRECTED at level 0
		System.out.println( "Loading corrected image (level 0)..." );
		correctedLoader.setActive( true );
		final RandomAccessibleInterval< FloatType > corrected =
			correctedLoader.getSetupImgLoader( setupToShow ).getFloatImage( timepoint, 0, false );
		ImageJFunctions.show( corrected, "2. Corrected - Setup " + setupToShow + " (tile " + tileId + ")" );

		// 5c. Show CORRECTED + SPLIT (left half) at level 0
		System.out.println( "Loading corrected + split (left half, level 0)..." );
		final RandomAccessibleInterval< FloatType > splitLeft =
			splitLoader.getSetupImgLoader( 100 ).getFloatImage( timepoint, 0, false );
		ImageJFunctions.show( splitLeft, "3. Corrected+Split LEFT - Setup 100" );

		// 5d. Show at different mipmap level if available
		if ( setupImgLoader.numMipmapLevels() > 1 )
		{
			System.out.println( "Loading corrected + split (left half, level 1)..." );
			final RandomAccessibleInterval< FloatType > splitLeftLevel1 =
				splitLoader.getSetupImgLoader( 100 ).getFloatImage( timepoint, 1, false );
			ImageJFunctions.show( splitLeftLevel1, "4. Corrected+Split LEFT (Level 1) - Setup 100" );
		}

		// ========== Summary ==========
		System.out.println( "\n=== VIEWERIMGLOADER CHAIN SUMMARY ===" );
		System.out.println( "Layer 1 (innermost): " + baseLoader.getClass().getSimpleName() + " [ViewerImgLoader]" );
		System.out.println( "Layer 2 (middle):    " + correctedLoader.getClass().getSimpleName() + " [ViewerImgLoader]" );
		System.out.println( "Layer 3 (outermost): " + splitLoader.getClass().getSimpleName() + " [ViewerImgLoader]" );
		System.out.println( "" );
		System.out.println( "All layers maintain ViewerImgLoader compatibility:" );
		System.out.println( "  - Cache control: delegated through chain" );
		System.out.println( "  - Volatile images: supported at all levels" );
		System.out.println( "  - Multi-resolution: " + setupImgLoader.numMipmapLevels() + " mipmap levels available" );
		System.out.println( "" );
		System.out.println( "Compare the images to verify:" );
		System.out.println( "  - Image 1 vs 2: See flatfield correction effect" );
		System.out.println( "  - Image 2 vs 3: Verify split region matches corrected full image" );
		if ( setupImgLoader.numMipmapLevels() > 1 )
			System.out.println( "  - Image 3 vs 4: Compare different mipmap levels" );
		System.out.println( "" );
		System.out.println( "Tip: Use Image > Adjust > Brightness/Contrast (Ctrl+Shift+C)" );
	}
}
