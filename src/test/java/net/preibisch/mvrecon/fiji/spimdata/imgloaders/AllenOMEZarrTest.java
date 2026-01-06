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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders;

import java.io.File;
import java.util.concurrent.Future;

import bdv.BigDataViewer;
import bdv.export.ProgressWriterConsole;
import bdv.img.n5.N5ImageLoader;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.InitializeViewerState;
import bdv.ui.UIUtils;
import bdv.viewer.ViewerOptions;
import mpicbg.spim.data.SpimDataException;

public class AllenOMEZarrTest
{
	public static void main( String[] args ) throws SpimDataException
	{
		final String fn = "/Users/pietzsch/Desktop/data/Allen/704522.xml";

		System.setProperty( "apple.laf.useScreenMenuBar", "true" );
		UIUtils.installFlatLafInfos();

		final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( fn );
		final N5ImageLoader n5ImageLoader = ( N5ImageLoader ) spimData.getSequenceDescription().getImgLoader();
		final Future< Void > f = n5ImageLoader.prefetch( 256 );
		n5ImageLoader.setNumFetcherThreads( 64 );

		final BigDataViewer bdv = BigDataViewer.open( spimData, new File( fn ).getName(), new ProgressWriterConsole(), ViewerOptions.options() );
		InitializeViewerState.initBrightness( 0, 1, bdv.getViewerFrame() );
	}
}
