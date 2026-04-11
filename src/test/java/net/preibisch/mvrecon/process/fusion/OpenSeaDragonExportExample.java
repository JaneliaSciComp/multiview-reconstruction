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

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.WindowConstants;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import gov.nist.isg.archiver.DirectoryArchiver;
import gov.nist.isg.archiver.FilesArchiver;
import gov.nist.isg.pyramidio.PartialImageReader;
import gov.nist.isg.pyramidio.ScalablePyramidBuilder;
import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.converter.ColorChannelOrder;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.export.OpenSeaDragon.OpenSeaDragonImgLib2;
import net.preibisch.mvrecon.process.fusion.FusionTools;
import util.URITools;

public class OpenSeaDragonExportExample
{
	@Test
	@Disabled("Requires specific dataset file that may not exist on all systems")
	public void testOpenSeaDragonExport()
	{
		System.out.println( "Test requires manual setup - see main() method" );
	}

	// ==================== Original main() method for manual testing ====================

	public static void displayImage(final String windowTitle, final BufferedImage image) {
		new JFrame(windowTitle) {
			private static final long serialVersionUID = 1L;

			{
				final JLabel label = new JLabel("", new ImageIcon(image), 0);
				add(label);
				pack();
				setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
				setVisible(true);
			}
		};
	}

	public static void main( String[] args ) throws SpimDataException, IOException
	{
		/*
		File dziFile = new File("/Users/preibischs/Downloads/troy eberhardt/dzi/troy.dzi");
		DeepZoomImageReader reader = new DeepZoomImageReader(dziFile);
		BufferedImage img = reader.getWholeImage(0.05);
		System.out.println( img.getType() );

		displayImage( "0.1", img );

		SimpleMultiThreading.threadHaltUnClean();
		*/

		new ImageJ();

		SpimData2 spimData = new XmlIoSpimData2().load( URITools.toURI( "/Users/preibischs/Documents/Janelia/Projects/DHS - OpenSeaDragon/troy eberhardt/dataset.xml" ) );

		testOpenSeaDragonExport( spimData, 0, 1, 2 );
	}

	public static void testOpenSeaDragonExport( final SpimData2 spimData, final int channelR, final int channelG, final int channelB ) throws IOException
	{

		// select views to process
		//final List< ViewId > viewIds = new ArrayList< ViewId >();
		//viewIds.addAll( spimData.getSequenceDescription().getViewDescriptions().values() );
		//viewIds.add( new ViewId( 0, 0 ) );

		final List< ViewId > viewIdsRed = new ArrayList<>();
		final List< ViewId > viewIdsGreen = new ArrayList<>();
		final List< ViewId > viewIdsBlue = new ArrayList<>();

		for ( final ViewDescription view : spimData.getSequenceDescription().getViewDescriptions().values() )
			if ( view.getViewSetup().getChannel().getId() == channelR )
				viewIdsRed.add( view );

		for ( final ViewDescription view : spimData.getSequenceDescription().getViewDescriptions().values() )
			if ( view.getViewSetup().getChannel().getId() == channelG )
				viewIdsGreen.add( view );

		for ( final ViewDescription view : spimData.getSequenceDescription().getViewDescriptions().values() )
			if ( view.getViewSetup().getChannel().getId() == channelB )
				viewIdsBlue.add( view );

		final Interval bb =  new BoundingBoxMaximal( spimData.getSequenceDescription().getViewDescriptions().values(), spimData ).estimate("test");

		System.out.println( "Interval: " + Util.printInterval( bb ));

		//
		// display virtually fused
		//
		final RandomAccessibleInterval< FloatType > virtualRed = FusionTools.fuseVirtual( spimData, viewIdsRed, FusionType.AVG_BLEND, bb );
		final RandomAccessibleInterval< FloatType > virtualGreen = FusionTools.fuseVirtual( spimData, viewIdsGreen, FusionType.AVG_BLEND, bb );
		final RandomAccessibleInterval< FloatType > virtualBlue = FusionTools.fuseVirtual( spimData, viewIdsBlue, FusionType.AVG_BLEND, bb );

		final RandomAccessibleInterval< UnsignedByteType > r8bit = Converters.convertRAI( virtualRed, (i,o) -> o.set( Math.min( 255, Math.max( 0, (int)i.get() ) ) ), new UnsignedByteType() );
		final RandomAccessibleInterval< UnsignedByteType > g8bit = Converters.convertRAI( virtualGreen, (i,o) -> o.set( Math.min( 255, Math.max( 0, (int)i.get() ) ) ), new UnsignedByteType() );
		final RandomAccessibleInterval< UnsignedByteType > b8bit = Converters.convertRAI( virtualBlue, (i,o) -> o.set( Math.min( 255, Math.max( 0, (int)i.get() ) ) ), new UnsignedByteType() );

		final RandomAccessibleInterval<ARGBType> rgb = Converters.mergeARGB( Views.stack( r8bit, g8bit, b8bit ) , ColorChannelOrder.RGB );

		//final OpenSeaDragonImgLib2 osd = new OpenSeaDragonImgLib2( rgb );
		final OpenSeaDragonImgLib2 osd =
				new OpenSeaDragonImgLib2( BlockSupplier.of( r8bit ), BlockSupplier.of( g8bit ), BlockSupplier.of( b8bit ), new FinalInterval( virtualRed ));

		//osd.read( new Rectangle(5000, 100, 1024, 768 ));
		//SimpleMultiThreading.threadHaltUnClean();

		//ImageJFunctions.show( rgb, DeconViews.createExecutorService() );

		//DisplayImage.getImagePlusInstance( virtualRed, true, "Fused, Virtual red", 0, 255 ).show();
		//DisplayImage.getImagePlusInstance( virtualGreen, true, "Fused, Virtual green", 0, 255 ).show();
		//DisplayImage.getImagePlusInstance( virtualBlue, true, "Fused, Virtual blue", 0, 255 ).show();

		final int tileSize = 254;
		final int tileOverlap = 1;
		final String tileFormat = "jpg";
		final File outputFolder = new File("/Users/preibischs/Downloads/troy eberhardt/dzi");

		long time = System.currentTimeMillis();

		ScalablePyramidBuilder spb = new ScalablePyramidBuilder(tileSize, tileOverlap, tileFormat, "dzi");
		FilesArchiver archiver = new DirectoryArchiver(outputFolder);
		PartialImageReader pir = osd;
		spb.buildPyramid(pir, "troy", archiver, Runtime.getRuntime().availableProcessors() );

		System.out.println( "done. [" + (System.currentTimeMillis() - time ) + " ms]" );
	}

}
