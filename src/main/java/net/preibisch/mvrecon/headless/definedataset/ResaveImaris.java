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
package net.preibisch.mvrecon.headless.definedataset;

import java.io.File;
import java.io.IOException;
import java.util.List;

import bdv.img.imaris.Imaris;
import bdv.spimdata.SpimDataMinimal;
import bdv.spimdata.XmlIoSpimDataMinimal;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;

public class ResaveImaris
{
	public static String defaultDir = "/Users/spreibi/Desktop/";
	public static String defaultFile = "/Users/spreibi/Documents/Microscopy/SPIM/HisYFP-SPIM/dataset.xml";

	public static void main( String args[] ) throws SpimDataException
	{
		new ImageJ();

		GenericDialogPlus gd = new GenericDialogPlus( "Save options" );

		gd.addFileField( "Select Imaris file", defaultFile, 100 );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		final File file = new File( gd.getNextString() );

		if ( !file.exists() )
		{
			IJ.log( "File " + file + " does not exist." );
			return;
		}
		else
		{
			IJ.log( "Opening " + file );
		}

		SpimDataMinimal spimData = null;
		try
		{
			spimData = Imaris.openIms( file.getAbsolutePath() );
		}
		catch ( IOException e ) { e.printStackTrace(); }
		//final SpimDataMinimal spimData = new XmlIoSpimDataMinimal().load( file.getAbsolutePath() );

		if ( spimData == null )
		{
			IJ.log( "File " + file + " could not be opened." );
			return;
		}

		// display
		//BigDataViewer.open( spimData, "test", new ProgressWriterIJ(), ViewerOptions.options() );

		final List< BasicViewSetup > viewSetups = spimData.getSequenceDescription().getViewSetupsOrdered();
		final List< TimePoint > timepoints = spimData.getSequenceDescription().getTimePoints().getTimePointsOrdered();

		IJ.log( "Found " + viewSetups.size() + " viewsetups." );
		IJ.log( "Found " + timepoints.size() + " timepoints." );

		gd = new GenericDialogPlus( "Save options" );

		gd.addDirectoryField( "Directory", file.getPath(), 50 );

		if ( timepoints.size() > 1 )
		{
			gd.addSlider( "Start timepoint", timepoints.get( 0 ).getId(), timepoints.get( timepoints.size() - 1 ).getId(), 0 );
			gd.addSlider( "End timepoint", timepoints.get( 0 ).getId(), timepoints.get( timepoints.size() - 1 ).getId(), timepoints.get( timepoints.size() - 1 ).getId() );
		}

		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		final String saveDir = defaultDir = gd.getNextString();

		final int startTp, endTp;

		if ( timepoints.size() > 1 )
		{
			startTp = (int)Math.round( gd.getNextNumber() );
			endTp = (int)Math.round( gd.getNextNumber() );
		}
		else
		{
			startTp = endTp = timepoints.get( 0 ).getId();
		}

		final BasicImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();

		for ( final TimePoint tp : timepoints )
		{
			if ( tp.getId() >= startTp && tp.getId() <= endTp )
			{
				IJ.log( "Exporting timepoint " + tp.getId() );
	
				for ( final BasicViewSetup vs : viewSetups )
				{
					IJ.log( "Exporting viewsetup " + vs.getId() );
					final ViewId v = new ViewId( tp.getId(), vs.getId() );
	
					if ( spimData.getSequenceDescription().getViewDescriptions().get( v ).isPresent() )
					{
						final RandomAccessibleInterval img = imgLoader.getSetupImgLoader( vs.getId() ).getImage( tp.getId() );
						final ImagePlus imp = ImageJFunctions.wrap( img, "tp_" + tp.getId() + "_vs_" + vs.getId() );
						final String saveFile =  new File( saveDir, imp.getTitle() ).getAbsolutePath() + ".tif";
						new FileSaver( imp ).saveAsTiffStack( saveFile );
	
						IJ.log( "Saved file " + saveFile );
	
						//SimpleMultiThreading.threadHaltUnClean();
					}
					else
					{
						IJ.log( "Exporting viewsetup " + vs.getName() + " IS NOT PRESENT." );
					}
				}
			}
			else
			{
				IJ.log( "Skipping timepoint " + tp.getId() );
			}
		}
	}
}
