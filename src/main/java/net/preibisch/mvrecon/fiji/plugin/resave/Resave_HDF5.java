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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import bdv.export.ExportMipmapInfo;
import bdv.export.ProgressWriter;
import bdv.export.ProposeMipmaps;
import bdv.img.hdf5.Hdf5ImageLoader;
import bdv.img.hdf5.Partition;
import ij.plugin.PlugIn;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.Dimensions;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.resave.Generic_Resave_HDF5.ParametersResaveHDF5;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.n5api.SpimData2Tools;

public class Resave_HDF5 implements PlugIn
{
	public static void main( final String[] args )
	{
		new Resave_HDF5().run( null );
	}

	@Override
	public void run( final String arg0 )
	{
		final LoadParseQueryXML xml = new LoadParseQueryXML();

		if ( !xml.queryXML( "Resaving as HDF5", "Resave", true, true, true, true, true ) )
			return;

		// load all dimensions if they are not known (required for estimating the mipmap layout)
		if ( loadDimensions( xml.getData(), xml.getViewSetupsToProcess() ) )
		{
			// save the XML again with the dimensions loaded
			new XmlIoSpimData2().saveWithFilename( xml.getData(), xml.getXMLFileName() );
		}

		final Map< Integer, ExportMipmapInfo > perSetupExportMipmapInfo = proposeMipmaps( xml.getViewSetupsToProcess() );

		Generic_Resave_HDF5.lastExportPath = LoadParseQueryXML.defaultXMLURI;

		final int firstviewSetupId = xml.getData().getSequenceDescription().getViewSetupsOrdered().get( 0 ).getId();
		final ParametersResaveHDF5 params = Generic_Resave_HDF5.getParameters( perSetupExportMipmapInfo.get( firstviewSetupId ), true, true );

		if ( params == null )
			return;

		LoadParseQueryXML.defaultXMLURI = params.getSeqFile().toString();

		final ProgressWriter progressWriter = new ProgressWriterIJ();
		progressWriter.out().println( "starting export..." );

		final SpimData2 data = xml.getData();
		final List< ViewId > viewIds = SpimData2.getAllViewIdsSorted( data, xml.getViewSetupsToProcess(), xml.getTimePointsToProcess() );

		// write hdf5
		Generic_Resave_HDF5.writeHDF5( SpimData2Tools.reduceSpimData2( data, viewIds ), params, progressWriter );

		// write xml sequence description
		if ( !params.onlyRunSingleJob || params.jobId == 0 )
		{
			try
			{
				final SpimData2 newSpimData = createXMLObject( data, viewIds, params, progressWriter, false );

				xml.getIO().save( newSpimData, params.seqFile.getAbsolutePath() );
				progressWriter.setProgress( 0.95 );
			}
			catch ( SpimDataException e )
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Could not save xml '" + params.getSeqFile() + "': " + e );
				throw new RuntimeException( e );
			}
			finally
			{
				IOFunctions.println( "(" + new Date( System.currentTimeMillis() ) + "): Saved xml '" + params.getSeqFile() + "'." );
			}
		}
		progressWriter.setProgress( 1.0 );
		progressWriter.out().println( "done" );
	}

	public static Map< Integer, ExportMipmapInfo > proposeMipmaps( final Collection< ? extends BasicViewSetup > viewsetups )
	{
		final HashMap< Integer, ExportMipmapInfo > perSetupExportMipmapInfo = new HashMap< Integer, ExportMipmapInfo >();
		for ( final BasicViewSetup setup : viewsetups )
		{
			final ExportMipmapInfo mi = ProposeMipmaps.proposeMipmaps( setup );

			// Sometimes this is negative (not sure what's going on there)
			for ( int l = 0; l < mi.getNumLevels(); ++l )
				if ( mi.getSubdivisions()[l][2] < 0 )
					mi.getSubdivisions()[l][2] = 32;

			// 2d case
			if ( setup.hasSize() && setup.getSize().dimension( 2 ) == 1 )
				for ( int l = 0; l < mi.getNumLevels(); ++l )
					mi.getSubdivisions()[l][2] = 1;

			perSetupExportMipmapInfo.put( setup.getId(), mi );
		}

		return perSetupExportMipmapInfo;
	}


	public static boolean loadDimensions( final SpimData2 spimData, final List< ViewSetup > viewsetups )
	{
		boolean loadedDimensions = false;

		for ( final ViewSetup vs : viewsetups )
		{
			if ( vs.getSize() == null )
			{
				IOFunctions.println( "Dimensions of viewsetup " + vs.getId() + " unknown. Loading them ... " );

				for ( final TimePoint t : spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered() )
				{
					final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( t.getId(), vs.getId() );

					if ( vd.isPresent() )
					{
						Dimensions dim = spimData.getSequenceDescription().getImgLoader().getSetupImgLoader( vd.getViewSetupId() ).getImageSize( vd.getTimePointId() );

						IOFunctions.println(
								"Dimensions: " + dim.dimension( 0 ) + "x" + dim.dimension( 1 ) + "x" + dim.dimension( 2 ) +
								", loaded from tp:" + t.getId() + " vs: " + vs.getId() );

						vs.setSize( dim );
						loadedDimensions = true;
						break;
					}
					else
					{
						IOFunctions.println( "ViewSetup: " + vs.getId() + " not present in timepoint: " + t.getId() );
					}
				}
			}
		}

		return loadedDimensions;
	}


	public static SpimData2 createXMLObject(
			final SpimData2 spimData,
			final List< ViewId > viewIds,
			final ParametersResaveHDF5 params,
			final ProgressWriter progressWriter,
			final boolean useRightAway )
	{
		// Re-assemble a new SpimData object containing the subset of viewsetups and timepoints selected
		final SpimData2 newSpimData;

		boolean isEqual = false;

		try
		{
			isEqual = spimData.getBasePathURI().equals( params.seqFile.getParentFile().toURI() ) || params.seqFile.getParent().equals( spimData.getBasePath().getName() );
		}
		catch ( Exception e )
		{
			isEqual = false;
		}

		if ( isEqual )
			newSpimData = SpimData2Tools.reduceSpimData2( spimData, viewIds );
		else
			newSpimData = SpimData2Tools.reduceSpimData2( spimData, viewIds, params.seqFile.getParentFile().toURI() );

		final ArrayList< Partition > partitions = Generic_Resave_HDF5.getPartitions( newSpimData, params );

		final Hdf5ImageLoader hdf5Loader;

		if ( useRightAway )
			hdf5Loader = new Hdf5ImageLoader( params.hdf5File, partitions, newSpimData.getSequenceDescription(), true );
		else
			hdf5Loader = new Hdf5ImageLoader( params.hdf5File, partitions, null, false );
		
		newSpimData.getSequenceDescription().setImgLoader( hdf5Loader );
		newSpimData.setBasePath( params.seqFile.getParentFile() );

		return newSpimData;
	}
}
