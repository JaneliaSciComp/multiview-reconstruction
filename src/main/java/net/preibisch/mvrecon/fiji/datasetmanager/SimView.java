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
package net.preibisch.mvrecon.fiji.datasetmanager;

import java.awt.Font;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

import org.jdom2.JDOMException;

import bdv.BigDataViewer;
import bdv.viewer.ViewerOptions;
import fiji.util.gui.GenericDialogPlus;
import ij.ImageJ;
import ij.gui.GenericDialog;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.SimViewMetaData.SimViewChannel;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.SimViewImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;

public class SimView implements MultiViewDatasetDefinition
{
	public static String defaultDir = "";
	public static String defaultItem = "";
	public static boolean defaultModifyStackSize = false;
	public static boolean defaultModifyCal = false;

	public static String[] types = {"8-bit", "16-bit Signed", "16-bit Unsigned" };
	public static int defaultType = 2;
	public static boolean defaultLittleEndian = true;

	@Override
	public SpimData2 createDataset( final String xmlFileName )
	{
		final File rootDir = queryRootDir();

		//
		// Query root dir
		// 
		if ( rootDir == null )
		{
			IOFunctions.println( "Root dir not defined. stopping.");
			return null;
		}
		else
		{
			IOFunctions.println( "Root dir = '" + rootDir.getAbsolutePath() + "'.");
		}

		//
		// Query experiment dir (if necessary)
		// 
		final File expDir = getExperimentDir( rootDir );
		
		if ( expDir == null )
		{
			IOFunctions.println( "Experiment dir not defined. stopping.");
			return null;
		}
		else
		{
			IOFunctions.println( "Experiment dir = '" + expDir.getAbsolutePath() + "'.");
		}

		//
		// Parse MetaData
		// 
		final SimViewMetaData meta = SimViewMetaData.parseMetaData( rootDir, expDir );

		if ( meta == null )
		{
			IOFunctions.println( "Failed to load metadata." );
			return null;
		}

		//
		// user input
		//
		if ( !showDialogs( meta ) )
			return null;

		final String directory = meta.expDir.getAbsolutePath();

		// assemble timepints, viewsetups, missingviews and the imgloader
		final TimePoints timepoints = this.createTimePoints( meta );
		final ArrayList< ViewSetup > setups = this.createViewSetups( meta );
		final MissingViews missingViews = null;

		// instantiate the sequencedescription
		final SequenceDescription sequenceDescription = new SequenceDescription( timepoints, setups, null, missingViews );
		final ImgLoader imgLoader = new SimViewImgLoader( sequenceDescription, meta.expDir, meta.filePattern, meta.type, meta.littleEndian );
		sequenceDescription.setImgLoader( imgLoader );

		// get the minimal resolution of all calibrations
		final double minResolution = Math.min( Math.min( meta.xStep, meta.yStep ), meta.zStep );

		IOFunctions.println( "Minimal resolution in all dimensions is: " + minResolution );
		IOFunctions.println( "(The smallest resolution in any dimension; the distance between two pixels in the output image will be that wide)" );
		
		// create calibration + translation view registrations
		final ViewRegistrations viewRegistrations = DatasetCreationUtils.createViewRegistrations( sequenceDescription.getViewDescriptions(), minResolution );
		
		// create the initial view interest point object
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints();
		//viewInterestPoints.createViewInterestPoints( sequenceDescription.getViewDescriptions() );

		// finally create the SpimData itself based on the sequence description and the view registration
		final SpimData2 spimData = new SpimData2( new File( directory ).toURI(), sequenceDescription, viewRegistrations, viewInterestPoints, new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );

		return spimData;
	}

	protected TimePoints createTimePoints( final SimViewMetaData meta )
	{
		final ArrayList< TimePoint > timepoints = new ArrayList< TimePoint >();

		for ( int t = 0; t < meta.numTimePoints; ++t )
			timepoints.add( new TimePoint( SimViewMetaData.getTimepointInt( meta.timePoints[ t ] ) ) );

		return new TimePoints( timepoints );
	}

	protected ArrayList< ViewSetup > createViewSetups( final SimViewMetaData meta )
	{
		final ArrayList< Channel > channels = new ArrayList< Channel >();
		for ( int c = 0; c < meta.numChannels; ++c )
			channels.add( new Channel( c, Integer.toString( meta.metaDataChannels[ c ].wavelength ) ) );
		
		final ArrayList< Angle > angles = new ArrayList< Angle >();
		int count = 0;
		for ( int a = 0; a < meta.numAngles; ++a )
			for ( int cam = 0; cam < meta.numCameras; ++cam )
				angles.add( new Angle( count++, a + "-" + cam ) );

		final Tile t = new Tile( 0 );
		final Illumination i = new Illumination( 0 );
		
		final ArrayList< ViewSetup > viewSetups = new ArrayList< ViewSetup >();
		for ( final Channel c : channels )
			for ( final Angle a : angles )
				{
					final VoxelDimensions voxelSize = new FinalVoxelDimensions( meta.unit, meta.xStep, meta.yStep, meta.zStep );
					final Dimensions dim = new FinalDimensions( meta.stackSize );
					viewSetups.add( new ViewSetup( viewSetups.size(), null, dim, voxelSize, t, c, a, i ) );
				}

		return viewSetups;
	}

	protected boolean showDialogs( final SimViewMetaData meta )
	{
		final boolean validFilePattern = meta.isValidFilePattern();
	
		final GenericDialog gd = new GenericDialog( "SimView Properties" );

		gd.addMessage( "Angles (" + meta.numAngles + " present)", new Font( Font.SANS_SERIF, Font.BOLD, 13 ) );
		gd.addMessage( "Cameras (" + meta.numCameras + " present)", new Font( Font.SANS_SERIF, Font.BOLD, 13 ) );
		gd.addMessage( "Channels (" + meta.numChannels + " present)", new Font( Font.SANS_SERIF, Font.BOLD, 13 ) );
		gd.addMessage( "" );

		for ( int c = 0; c < meta.numChannels; ++c )
			gd.addNumericField( "Channel_" + meta.channels[ c ] + ":", meta.metaDataChannels[ c ].wavelength, 0 );

		gd.addMessage( "Timepoints (" + meta.numTimePoints + " present)", new Font( Font.SANS_SERIF, Font.BOLD, 13 ) );

		gd.addMessage( "Image loading", new Font( Font.SANS_SERIF, Font.BOLD, 13 ) );
		gd.addNumericField("X_size", meta.stackSize[ 0 ], 0 );
		gd.addNumericField("Y_size", meta.stackSize[ 1 ], 0 );
		gd.addNumericField("Z_size", meta.stackSize[ 2 ], 0 );
		gd.addChoice("Image_type:", types, types[ defaultType ]);
		gd.addCheckbox("Little-endian byte order", defaultLittleEndian );
		
		if ( validFilePattern )
			gd.addStringField("Filepattern (confirmed)", meta.filePattern, 45 );
		else
			gd.addStringField("Filepattern (NOT CORRECT)", meta.filePattern, 45 );
		
		gd.addMessage( "" );

		gd.addMessage( "Calibration", new Font( Font.SANS_SERIF, Font.BOLD, 13 ) );
		gd.addNumericField( "Pixel_distance_X (guessed): ", meta.xStep, 5 );
		gd.addNumericField( "Pixel_distance_Y (guessed): ", meta.yStep, 5 );
		gd.addNumericField( "Pixel_distance_Z: ", meta.zStep, 5  );
		gd.addStringField( "Pixel_unit", meta.unit );

		gd.addMessage( "Additional Meta Data", new Font( Font.SANS_SERIF, Font.BOLD, 13 ) );
		gd.addMessage( "Acquisition Objective: " + meta.metaDataChannels[ 0 ].metadataHash.getOrDefault("detection_objective", "<not stored>"), new Font( Font.SANS_SERIF, Font.ITALIC, 11 ) );
		gd.addMessage( "Specimen Name: " + meta.metaDataChannels[ 0 ].specimen_name, new Font( Font.SANS_SERIF, Font.ITALIC, 11 ) );
		gd.addMessage( "Time Stamp: " + meta.metaDataChannels[ 0 ].timestamp, new Font( Font.SANS_SERIF, Font.ITALIC, 11 ) );

		GUIHelper.addScrollBars( gd );
		
		gd.showDialog();
		if ( gd.wasCanceled() )
			return false;

		for ( int c = 0; c < meta.numChannels; ++c )
			meta.metaDataChannels[ c ].wavelength = (int)Math.round( gd.getNextNumber() );

		meta.stackSize[ 0 ] = (int)Math.round( gd.getNextNumber() );
		meta.stackSize[ 1 ] = (int)Math.round( gd.getNextNumber() );
		meta.stackSize[ 2 ] = (int)Math.round( gd.getNextNumber() );
		meta.type = defaultType = gd.getNextChoiceIndex();
		meta.littleEndian = defaultLittleEndian = gd.getNextBoolean();

		meta.filePattern = gd.getNextString();

		meta.xStep = gd.getNextNumber();
		meta.yStep = gd.getNextNumber();
		meta.zStep = gd.getNextNumber();
		meta.unit = gd.getNextString();
		
		return true;
	}

	public static class DirectoryFilter implements FilenameFilter
	{
		private final String startsWith;

		public DirectoryFilter() { this.startsWith = null; }

		public DirectoryFilter( final String startsWith ) { this.startsWith = startsWith; }

		@Override
		public boolean accept( final File dir, final String name )
		{
			final File f = new File( dir, name );

			if ( f.isDirectory() && ( startsWith == null || name.startsWith(startsWith) ) )
				return true;
			else
				return false;
		}	
	}
	
	protected File getExperimentDir( final File rootDir )
	{
		final String[] dirs = rootDir.list( new DirectoryFilter() );

		if ( dirs.length == 0 )
		{
			IOFunctions.println( rootDir.getAbsolutePath() + " contains no subdirectories with experiments." );
			return null;
		}
		else if ( dirs.length == 1 )
		{
			return new File( rootDir, dirs[ 0 ] );
		}
		else
		{
			Arrays.sort( dirs );

			GenericDialog gd = new GenericDialog( "Select experiment to import" );

			boolean contains = false;

			for ( final String dir : dirs )
				if ( dir.equals( defaultItem ) )
					contains = true;

			if ( defaultItem.length() == 0 || !contains )
				defaultItem = dirs[ 0 ];
			
			gd.addChoice( "Experiment", dirs, defaultItem );
			//gd.addRadioButtonGroup("Experiment", dirs, 1, dirs.length, defaultItem );
			
			gd.showDialog();
			
			if ( gd.wasCanceled() )
				return null;

			return new File( rootDir, defaultItem = gd.getNextChoice() );
		}
	}

	protected File queryRootDir()
	{
		GenericDialogPlus gd = new GenericDialogPlus( "SimView Data Directory" );
	
		gd.addDirectoryField( "SimView data directory", defaultDir, 50);
	
		gd.showDialog();
	
		if ( gd.wasCanceled() )
			return null;
	
		final File dir = new File( defaultDir = gd.getNextString() );
	
		if ( !dir.exists() )
		{
			IOFunctions.println( "Directory '" + dir.getAbsolutePath() + "' does not exist. Stopping" );
			return null;
		}
		else
		{
			IOFunctions.println( "Investigating directory '" + dir.getAbsolutePath() + "'." );
			return dir;
		}
	}


	@Override
	public String getExtendedDescription()
	{
		return "This dataset definition parses a directory structure\n" +
			   "saved by SimView-like microscopes from LabView.";
	}

	@Override
	public String getTitle() { return "SimView Dataset Loader (Raw)"; }

	@Override
	public MultiViewDatasetDefinition newInstance() { return new SimView(); }

	public static void main( String[] args )
	{
		defaultDir = "/nrs/aic/Wait/for_stephan/Run2_20190909_155416";

		SpimData2 sd = new SimView().createDataset( "dataset.xml");

		if ( sd == null )
			IOFunctions.println( "Failed to define dataset.");
		else
		{
			new ImageJ();
			ImageJFunctions.show( (RandomAccessibleInterval)sd.getSequenceDescription().getImgLoader().getSetupImgLoader( 0 ).getImage( 0 ) );
			ImageJFunctions.show( (RandomAccessibleInterval)sd.getSequenceDescription().getImgLoader().getSetupImgLoader( 0 ).getImage( 100 ) );
			ImageJFunctions.show( (RandomAccessibleInterval)sd.getSequenceDescription().getImgLoader().getSetupImgLoader( 1 ).getImage( 0 ) );

			BigDataViewer.open(  sd, "", null, ViewerOptions.options() );
		}
	}

}
