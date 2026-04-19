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
package net.preibisch.mvrecon.fiji.plugin.resave;

import static mpicbg.spim.data.generic.sequence.ImgLoaderHints.LOAD_COMPLETELY;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Date;
import java.util.List;

import bdv.export.ProgressWriter;
import fiji.util.gui.GenericDialogPlus;
import ij.plugin.PlugIn;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.PluginHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.StackImgLoaderIJ;
import net.preibisch.mvrecon.process.export.Save3dTIFF;
import net.preibisch.mvrecon.process.n5api.SpimData2Tools;
import util.URITools;

public class Resave_TIFF implements PlugIn
{
	public static String defaultPath = null;
	public static boolean defaultCompress = false;

	public static void main( final String[] args )
	{
		new Resave_TIFF().run( null );
	}

	public static class ParametersResaveAsTIFF
	{
		public URI xmlPath;
		public boolean compress;
		
		public boolean compress() { return compress; }
		public URI getXMLPath() { return xmlPath; }
	}

	@Override
	public void run( final String arg0 )
	{
		final LoadParseQueryXML lpq = new LoadParseQueryXML();

		if ( !lpq.queryXML( "Resaving as TIFF", "Resave", true, true, true, true, true ) )
			return;

		final ProgressWriter progressWriter = new ProgressWriterIJ();
		progressWriter.out().println( "starting export..." );

		final ParametersResaveAsTIFF params = getParameters();

		if ( params == null )
			return;

		final SpimData2 data = lpq.getData();
		final List< ViewId > viewIds = SpimData2.getAllViewIdsSorted( data, lpq.getViewSetupsToProcess(), lpq.getTimePointsToProcess() );

		final File file = new File( URITools.fromURI( params.getXMLPath() ) );

		// write the TIFF's
		writeTIFF( data, viewIds, file.getParent(), params.compress, progressWriter );

		// write the XML
		try
		{
			final SpimData2 newSpimData = createXMLObject( data, viewIds, params );
			progressWriter.setProgress( 0.95 );

			// write the XML
			lpq.getIO().save( newSpimData, file.getAbsolutePath() );
		}
		catch ( SpimDataException e )
		{
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Could not save xml '" + params.getXMLPath() + "'." );
			e.printStackTrace();
		}
		finally
		{
			progressWriter.setProgress( 1.00 );
			IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Saved xml '" + params.getXMLPath() + "'." );
		}
	}

	/**
	 * @return - the parameters if it is specifying a locally mounted share, otherwise null
	 */
	public static ParametersResaveAsTIFF getParameters()
	{
		final GenericDialogPlus gd = new GenericDialogPlus( "Resave dataset as TIFF" );

		if ( defaultPath == null )
			defaultPath = LoadParseQueryXML.defaultXMLURI;

		PluginHelper.addSaveAsFileField( gd, "Export_path for XML", defaultPath, 80 );
		//gd.addCheckbox( "Lossless compression of TIFF files (ZIP)", defaultCompress );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;

		final ParametersResaveAsTIFF params = new ParametersResaveAsTIFF();

		String fullPath = gd.getNextString();

		if ( !fullPath.endsWith( ".xml" ) )
			fullPath += ".xml";

		final URI uri = URITools.toURI( fullPath );

		if ( !URITools.isFile( uri ) )
		{
			IOFunctions.println( "Provided URI '" + fullPath + "' is not on a local file system. Re-saving as TIFF only works on locally mounted file systems. Stopping." );
			return null;
		}

		LoadParseQueryXML.defaultXMLURI = defaultPath = fullPath;
		params.xmlPath = uri;

		params.compress = false; //defaultCompress = gd.getNextBoolean();

		return params;
	}

	public static void writeTIFF( final SpimData spimData, final List< ViewId > viewIds, final String path, final boolean compress, final ProgressWriter progressWriter )
	{
		if ( compress )
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Saving compressed TIFFS to directory '" + path + "'" );
		else
			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Saving TIFFS to directory '" + path + "'" );
		
		final Save3dTIFF save = new Save3dTIFF( path, compress );
		
		final int numAngles = SpimData2.getAllAnglesSorted( spimData, viewIds ).size();
		final int numChannels = SpimData2.getAllChannelsSorted( spimData, viewIds ).size();
		final int numIlluminations = SpimData2.getAllIlluminationsSorted( spimData, viewIds ).size();
		final int numTimepoints =  SpimData2.getAllTimePointsSorted( spimData, viewIds ).size();
		final int numTiles =  SpimData2.getAllTilesSorted( spimData, viewIds ).size();

		int i = 0;

		for ( final ViewId viewId : viewIds )
		{
			i++;

			final ViewDescription viewDescription = spimData.getSequenceDescription().getViewDescription( 
					viewId.getTimePointId(), viewId.getViewSetupId() );

			if ( !viewDescription.isPresent() )
				continue;

			final RandomAccessibleInterval img = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( viewId.getViewSetupId() ).getImage( viewId.getTimePointId() );

			String filename = "img";

			if ( numTimepoints > 1 )
				filename += "_TL" + viewId.getTimePointId();

			if ( numChannels > 1 )
				filename += "_Ch" + viewDescription.getViewSetup().getChannel().getName();

			if ( numIlluminations > 1 )
				filename += "_Ill" + viewDescription.getViewSetup().getIllumination().getName();

			if ( numAngles > 1 )
				filename += "_Angle" + viewDescription.getViewSetup().getAngle().getName();
			
			if ( numTiles > 1 )
				filename += "_Tile" + viewDescription.getViewSetup().getTile().getName();

			save.exportImage(
					BlockSupplier.of( img ),
					new FinalInterval( img ),
					Double.NaN,
					Double.NaN,
					filename,
					null );

			progressWriter.setProgress( ((i-1) / (double)viewIds.size()) * 95.00  );
		}
	}

	public static SpimData2 createXMLObject( final SpimData2 spimData, final List< ViewId > viewIds, final ParametersResaveAsTIFF params )
	{
		int layoutTP = 0, layoutChannels = 0, layoutIllum = 0, layoutAngles = 0, layoutTiles = 0;
		String filename = "img";

		final int numAngles = SpimData2.getAllAnglesSorted( spimData, viewIds ).size();
		final int numChannels = SpimData2.getAllChannelsSorted( spimData, viewIds ).size();
		final int numIlluminations = SpimData2.getAllIlluminationsSorted( spimData, viewIds ).size();
		final int numTimepoints =  SpimData2.getAllTimePointsSorted( spimData, viewIds ).size();
		final int numTiles =  SpimData2.getAllTilesSorted( spimData, viewIds ).size();

		if ( numTimepoints > 1 )
		{
			filename += "_TL{t}";
			layoutTP = 1;
		}
		
		if ( numChannels > 1 )
		{
			filename += "_Ch{c}";
			layoutChannels = 1;
		}
		
		if ( numIlluminations > 1 )
		{
			filename += "_Ill{i}";
			layoutIllum = 1;
		}
		
		if ( numAngles > 1 )
		{
			filename += "_Angle{a}";
			layoutAngles = 1;
		}
		
		if ( numTiles > 1 )
		{
			filename += "_Tile{x}";
			layoutTiles = 1;
		}

		filename += ".tif";

		if ( params.compress )
			filename += ".zip";

		// Re-assemble a new SpimData object containing the subset of viewsetups and timepoints selected
		final SpimData2 newSpimData;
		final URI newBasePath = URITools.toURI( new File( URITools.fromURI( params.getXMLPath() ) ).getParentFile().toString() );

		boolean isEqual = false;

		try
		{
			isEqual = spimData.getBasePathURI().equals( newBasePath );
		}
		catch ( Exception e )
		{
			isEqual = false;
		}

		if ( isEqual )
			newSpimData = SpimData2Tools.reduceSpimData2( spimData, viewIds );
		else
			newSpimData = SpimData2Tools.reduceSpimData2( spimData, viewIds, newBasePath );

		final StackImgLoaderIJ imgLoader = new StackImgLoaderIJ(
				new File( URITools.fromURI( params.getXMLPath() ) ).getParentFile(),
				filename,
				layoutTP, layoutChannels, layoutIllum, layoutAngles, layoutTiles, newSpimData.getSequenceDescription() );
		newSpimData.getSequenceDescription().setImgLoader( imgLoader );

		return newSpimData;
	}
}
