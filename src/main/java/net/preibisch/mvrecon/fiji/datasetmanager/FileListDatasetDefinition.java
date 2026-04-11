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
package net.preibisch.mvrecon.fiji.datasetmanager;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.TextEvent;
import java.awt.event.TextListener;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import javax.swing.JLabel;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import bdv.export.ExportMipmapInfo;
import bdv.export.ProgressWriter;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
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
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinitionUtil.AngleInfo;
import net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinitionUtil.ChannelInfo;
import net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinitionUtil.CheckResult;
import net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinitionUtil.TileInfo;
import net.preibisch.mvrecon.fiji.datasetmanager.grid.RegularTranformHelpers;
import net.preibisch.mvrecon.fiji.datasetmanager.grid.RegularTranformHelpers.RegularTranslationParameters;
import net.preibisch.mvrecon.fiji.datasetmanager.patterndetector.FilenamePatternDetector;
import net.preibisch.mvrecon.fiji.datasetmanager.patterndetector.NumericalFilenamePatternDetector;
import net.preibisch.mvrecon.fiji.plugin.Apply_Transformation;
import net.preibisch.mvrecon.fiji.plugin.resave.Generic_Resave_HDF5;
import net.preibisch.mvrecon.fiji.plugin.resave.Generic_Resave_HDF5.ParametersResaveHDF5;
import net.preibisch.mvrecon.fiji.plugin.resave.ParametersResaveN5Api;
import net.preibisch.mvrecon.fiji.plugin.resave.ProgressWriterIJ;
import net.preibisch.mvrecon.fiji.plugin.resave.Resave_HDF5;
import net.preibisch.mvrecon.fiji.plugin.resave.Resave_N5Api;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.plugin.util.PluginHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.FileMapImgLoaderLOCI;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.LegacyFileMapImgLoaderLOCI;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapEntry;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapGettable;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.filemap2.FileMapImgLoaderLOCI2;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.ColorStream;
import util.URITools;

public class FileListDatasetDefinition implements MultiViewDatasetDefinition
{
	public static final String[] GLOB_SPECIAL_CHARS = new String[] {"{", "}", "[", "]", "*", "?"};
	public static final String[] loadChoices = new String[] {
			"Re-save as multiresolution OME-Zarr v3",
			"Re-save as multiresolution OME-Zarr v2",
			"Re-save as multiresolution HDF5",
			"Re-save as multiresolution N5",
			"Load raw data directly (no resaving)"};
	public static final String Z_VARIABLE_CHOICE = "Z-Planes (experimental)";

	public static boolean windowsHack = true;

	public static int defaultLoadChoice = 0;
	public static boolean defaultVirtual = true;

	private static ArrayList<FileListChooser> fileListChoosers = new ArrayList<>();
	static
	{
		fileListChoosers.add( new WildcardFileListChooser() );
		//fileListChoosers.add( new SimpleDirectoryFileListChooser() );
	}

	@Override
	public boolean supportsRemoteXMLLocation() { return true; }

	protected static interface FileListChooser
	{
		public List<File> getFileList();
		public String getDescription();
		public FileListChooser getNewInstance();
	}

	protected static class WildcardFileListChooser implements FileListChooser
	{
		private static long KB_FACTOR = 1024;
		private static int minNumLines = 10;
		private static String info = "<html> <h1> Select files via wildcard expression </h1> <br /> "
				+ "Use the path field to specify a file or directory to process or click 'Browse...' to select one. <br /> <br />"
				+ "Wildcard (*) expressions are allowed. <br />"
				+ "e.g. '/Users/spim/data/spim_TL*_Angle*.tif' <br /><br />"
				+ "</html>";
		
		
		private static String previewFiles(List<File> files){
			StringBuilder sb = new StringBuilder();
			sb.append("<html><h2> selected files </h2>");
			for (File f : files)
				sb.append( "<br />" + f.getAbsolutePath() );
			for (int i = 0; i < minNumLines - files.size(); i++)
				sb.append( "<br />"  );
			sb.append( "</html>" );
			return sb.toString();
		}

		@Override
		public List< File > getFileList()
		{

			GenericDialogPlus gdp = new GenericDialogPlus("Pick files to include");

			addMessageAsJLabel(info, gdp);

			gdp.addDirectoryOrFileField( "path", "/", 65);
			gdp.addNumericField( "exclude files smaller than (KB)", 10, 0 );

			// preview selected files - not possible in headless
			if (!PluginHelper.isHeadless())
				{
				// add empty preview
				addMessageAsJLabel(previewFiles( new ArrayList<>()), gdp,  GUIHelper.smallStatusFont);

				JLabel lab = (JLabel)gdp.getComponent( 5 );
				TextField num = (TextField)gdp.getComponent( 4 ); 
				Panel pan = (Panel)gdp.getComponent( 2 );

				num.addTextListener( new TextListener()
				{
					@Override
					public void textValueChanged(TextEvent e)
					{
						String path = ((TextField)pan.getComponent( 0 )).getText();

						if (path.endsWith( File.separator ))
							path = path.substring( 0, path.length() - File.separator.length() );

						if(new File(path).isDirectory())
							path = String.join( File.separator, path, "*" );

						lab.setText( previewFiles( getFilesFromPattern(path , Long.parseLong( num.getText() ) * KB_FACTOR)));
						lab.setSize( lab.getPreferredSize() );
						gdp.setSize( gdp.getPreferredSize() );
						gdp.validate();
					}
				} );

				final AtomicBoolean autoset = new AtomicBoolean( false );

				((TextField)pan.getComponent( 0 )).addTextListener( new TextListener()
				{
					@Override
					public void textValueChanged(TextEvent e)
					{
						if ( autoset.get() == true )
						{
							autoset.set( false );

							return;
						}

						String path = ((TextField)pan.getComponent( 0 )).getText();

						// if macro recorder is running and we are on windows
						if ( windowsHack && ij.plugin.frame.Recorder.record && System.getProperty("os.name").toLowerCase().contains( "win" ) )
						{
							while( path.contains( "\\" ))
								path = path.replace( "\\", "/" );

							autoset.set( true );
							((TextField)pan.getComponent( 0 )).setText( path ); // will lead to a recursive call of textValueChanged(TextEvent e)
						}

						if (path.endsWith( File.separator ))
							path = path.substring( 0, path.length() - File.separator.length() );

						if(new File(path).isDirectory())
							path = String.join( File.separator, path, "*" );

						lab.setText( previewFiles( getFilesFromPattern(path , Long.parseLong( num.getText() ) * KB_FACTOR)));
						lab.setSize( lab.getPreferredSize() );
						gdp.setSize( gdp.getPreferredSize() );
						gdp.validate();
					}
				} );
			}

			if ( windowsHack && ij.plugin.frame.Recorder.record && System.getProperty("os.name").toLowerCase().contains( "win" ) )
			{
				gdp.addMessage( "Warning: we are on Windows and the Macro Recorder is on, replacing all instances of '\\' with '/'\n"
						+ "   Disable it by opening the script editor, language beanshell, call:\n"
						+ "   net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinition.windowsHack = false;", GUIHelper.smallStatusFont, Color.RED );
			}

			GUIHelper.addScrollBars( gdp );
			gdp.showDialog();

			if (gdp.wasCanceled())
				return new ArrayList<>();

			String fileInput = gdp.getNextString();

			if (fileInput.endsWith( File.separator ))
				fileInput = fileInput.substring( 0, fileInput.length() - File.separator.length() );

			if(new File(fileInput).isDirectory())
				fileInput = String.join( File.separator, fileInput, "*" );

			List<File> files = getFilesFromPattern( fileInput, (long) gdp.getNextNumber() * KB_FACTOR );

			files.forEach(f -> System.out.println( "Including file " + f + " in dataset." ));

			return files;
		}

		@Override
		public String getDescription(){return "Choose via wildcard expression";}

		@Override
		public FileListChooser getNewInstance() {return new WildcardFileListChooser();}
		
	}

	protected static class SimpleDirectoryFileListChooser implements FileListChooser
	{

		@Override
		public List< File > getFileList()
		{
			List< File > res = new ArrayList<File>();
			
			DirectoryChooser dc = new DirectoryChooser ( "pick directory" );
			if (dc.getDirectory() != null)
				try
				{
					res = Files.list( Paths.get( dc.getDirectory() ))
						.filter(p -> {
							try
							{
								if ( Files.size( p ) > 10 * 1024 )
									return true;
								else
									return false;
							}
							catch ( IOException e )
							{
								// TODO Auto-generated catch block
								e.printStackTrace();
								return false;
							}
						}
						).map( p -> p.toFile() ).collect( Collectors.toList() );
					
				}
				catch ( IOException e )
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			return res;
			
			
		}
		
		

		@Override
		public String getDescription()
		{
			// TODO Auto-generated method stub
			return "select a directory manually";
		}

		@Override
		public FileListChooser getNewInstance()
		{
			// TODO Auto-generated method stub
			return new SimpleDirectoryFileListChooser();
		}
		
	}
	
	public static void addMessageAsJLabel(String msg, GenericDialog gd)
	{
		addMessageAsJLabel(msg, gd, null);
	}
	
	public static void addMessageAsJLabel(String msg, GenericDialog gd, Font font)
	{
		addMessageAsJLabel(msg, gd, font, null);
	}

	public static void addMessageAsJLabel(String msg, GenericDialog gd, Font font, Color color)
	{
		gd.addMessage( msg );
		if (!PluginHelper.isHeadless())
		{
			final Component msgC = gd.getComponent(gd.getComponentCount() - 1 );
			final JLabel msgLabel = new JLabel(msg);

			if (font!=null)
				msgLabel.setFont(font);
			if (color!=null)
				msgLabel.setForeground(color);

			gd.add(msgLabel);
			GridBagConstraints constraints = ((GridBagLayout)gd.getLayout()).getConstraints(msgC);
			((GridBagLayout)gd.getLayout()).setConstraints(msgLabel, constraints);

			gd.remove(msgC);
		}
	}
	
		
	
	public static List<File> getFilesFromPattern(String pattern, final long fileMinSize)
	{		
		Pair< String, String > pAndp = splitIntoPathAndPattern( pattern, GLOB_SPECIAL_CHARS );		
		String path = pAndp.getA();
		String justPattern = pAndp.getB();
		
		PathMatcher pm;
		try
		{
		pm = FileSystems.getDefault().getPathMatcher( "glob:" + 
				((justPattern.length() == 0) ? path : String.join("/", path, justPattern )) );
		}
		catch (PatternSyntaxException e) {
			// malformed pattern, return empty list (for now)
			// if we do not catch this, we will keep logging exceptions e.g. while user is typing something like [0-9]
			return new ArrayList<>();
		}
		
		List<File> paths = new ArrayList<>();
		
		if (!new File( path ).exists())
			return paths;
		
		int numLevels = justPattern.split( "/" ).length;
						
		try
		{
			Files.walk( Paths.get( path ), numLevels ).filter( p -> pm.matches( p ) ).filter( new Predicate< Path >()
			{

				@Override
				public boolean test(Path t)
				{
					// ignore directories
					if (Files.isDirectory( t ))
						return false;
					
					try
					{
						return Files.size( t ) > fileMinSize;
					}
					catch ( IOException e )
					{
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					return false;
				}
			} )
			.forEach( p -> paths.add( new File(p.toString() )) );

		}
		catch ( IOException e )
		{
			
		}
		
		Collections.sort( paths );
		return paths;
	}
	
	private static SpimData2 buildSpimData( FileListViewDetectionState state, boolean withVirtualLoader )
	{

		//final Map< Integer, List< FileMapEntry > > fm = tileIdxMap;
		//fm.forEach( (k,v ) -> {System.out.println( k ); v.forEach( p -> {System.out.print(p.getA() + ""); System.out.print(p.getB().getA().toString() + " "); System.out.println(p.getB().getB().toString());} );});


		Map< Integer, List< FileMapEntry > > tpIdxMap = state.getIdMap().get( TimePoint.class );
		Map< Integer, List< FileMapEntry > > channelIdxMap = state.getIdMap().get( Channel.class );
		Map< Integer, List< FileMapEntry > > illumIdxMap = state.getIdMap().get( Illumination.class );
		Map< Integer, List< FileMapEntry > > tileIdxMap = state.getIdMap().get( Tile.class );
		Map< Integer, List< FileMapEntry > > angleIdxMap = state.getIdMap().get( Angle.class );


		List<Integer> timepointIndexList = new ArrayList<>(tpIdxMap.keySet());
		List<Integer> channelIndexList = new ArrayList<>(channelIdxMap.keySet());
		List<Integer> illuminationIndexList = new ArrayList<>(illumIdxMap.keySet());
		List<Integer> tileIndexList = new ArrayList<>(tileIdxMap.keySet());
		List<Integer> angleIndexList = new ArrayList<>(angleIdxMap.keySet());
		
		Collections.sort( timepointIndexList );
		Collections.sort( channelIndexList );
		Collections.sort( illuminationIndexList );
		Collections.sort( tileIndexList );
		Collections.sort( angleIndexList );
		
		int nTimepoints = timepointIndexList.size();
		int nChannels = channelIndexList.size();
		int nIlluminations = illuminationIndexList.size();
		int nTiles = tileIndexList.size();
		int nAngles = angleIndexList.size();
		
		List<ViewSetup> viewSetups = new ArrayList<>();
		List<ViewId> missingViewIds = new ArrayList<>();
		List<TimePoint> timePoints = new ArrayList<>();

		Map< Pair< Integer, Integer >, FileMapEntry > ViewIDfileMap = new HashMap<>();
		Integer viewSetupId = 0;
		for (Integer c = 0; c < nChannels; c++)
			for (Integer i = 0; i < nIlluminations; i++)
				for (Integer ti = 0; ti < nTiles; ti++)
					for (Integer a = 0; a < nAngles; a++)
					{
						// remember if we already added a vs in the tp loop
						boolean addedViewSetup = false;
						for (Integer tp = 0; tp < nTimepoints; tp++)
						{

							List< FileMapEntry > viewList;
							viewList = FileListDatasetDefinitionUtil.listIntersect( channelIdxMap.get( channelIndexList.get( c ) ), angleIdxMap.get( angleIndexList.get( a ) ) );
							viewList = FileListDatasetDefinitionUtil.listIntersect( viewList, tileIdxMap.get( tileIndexList.get( ti ) ) );
							viewList = FileListDatasetDefinitionUtil.listIntersect( viewList, illumIdxMap.get( illuminationIndexList.get( i ) ) );
							
							// we only consider combinations of angle, illum, channel, tile that are in at least one timepoint
							if (viewList.size() == 0)
								continue;
							
							viewList = FileListDatasetDefinitionUtil.listIntersect( viewList, tpIdxMap.get( timepointIndexList.get( tp ) ) );

														
							Integer tpId = timepointIndexList.get( tp );
							Integer channelId = channelIndexList.get( c );
							Integer illuminationId = illuminationIndexList.get( i );
							Integer angleId = angleIndexList.get( a );
							Integer tileId = tileIndexList.get( ti );
							
							IOFunctions.println( "Finalizing ViewSetup: " + viewSetupId );
							
							if (viewList.size() < 1)
							{
								IOFunctions.println( "Missing View: ch" + c +" a"+ a + " ti" + ti + " tp"+ tp + " i" + i );
								int missingSetup = addedViewSetup ? viewSetupId - 1 : viewSetupId;
								missingViewIds.add( new ViewId( tpId, missingSetup ) );
								
							}
							else if (viewList.size() > 1)
								IOFunctions.println( "Error: more than one View: ch" + c +" a"+ a + " ti" + ti + " tp"+ tp + " i" + i );
							else
							{
								IOFunctions.println( "Found View: ch" + c +" a"+ a + " ti" + ti + " tp"+ tp + " i" + i + " in file " + viewList.get( 0 ).file().getAbsolutePath());

								TimePoint tpI = new TimePoint( tpId );
								if (!timePoints.contains( tpI ))
									timePoints.add( tpI );
								
								if (!addedViewSetup)
									ViewIDfileMap.put( new ValuePair< Integer, Integer >( tpId, viewSetupId ), viewList.get( 0 ) );
								else
									ViewIDfileMap.put( new ValuePair< Integer, Integer >( tpId, viewSetupId - 1 ), viewList.get( 0 ) );
								
								
								// we have not visited this combination before
								if (!addedViewSetup)
								{
									Illumination illumI = new Illumination( illuminationId, illuminationId.toString() );
									
									Channel chI = new Channel( channelId, channelId.toString() );
									
									if (state.getDetailMap().get( Channel.class ) != null && state.getDetailMap().get( Channel.class ).containsKey( channelId))
									{
										ChannelInfo chInfoI = (ChannelInfo) state.getDetailMap().get( Channel.class ).get( channelId );
										if (chInfoI.wavelength != null)
											chI.setName( Integer.toString( (int)Math.round( chInfoI.wavelength )));
										if (chInfoI.fluorophore != null)
											chI.setName( chInfoI.fluorophore );
										if (chInfoI.name != null)
											chI.setName( chInfoI.name );
									}

									Angle aI = new Angle( angleId, angleId.toString() );
									
									if (state.getDetailMap().get( Angle.class ) != null && state.getDetailMap().get( Angle.class ).containsKey( angleId ))
									{
										AngleInfo aInfoI = (AngleInfo) state.getDetailMap().get( Angle.class ).get( angleId );
										
										if (aInfoI.angle != null && aInfoI.axis != null)
										{
											try
											{
												double[] axis = null;
												if ( aInfoI.axis == 0 )
													axis = new double[]{ 1, 0, 0 };
												else if ( aInfoI.axis == 1 )
													axis = new double[]{ 0, 1, 0 };
												else if ( aInfoI.axis == 2 )
													axis = new double[]{ 0, 0, 1 };

												if ( axis != null && !Double.isNaN( aInfoI.angle ) &&  !Double.isInfinite( aInfoI.angle ) )
													aI.setRotation( axis, aInfoI.angle );
											}
											catch ( Exception e ) {};
										}
									}

									Tile tI = new Tile( tileId, tileId.toString() );

									if (state.getDetailMap().get( Tile.class ) != null && state.getDetailMap().get( Tile.class ).containsKey( tileId ))
									{
										TileInfo tInfoI = (TileInfo) state.getDetailMap().get( Tile.class ).get( tileId );

										// check if we have at least one location != null
										// in the case that location in one dimension (e.g. z) is null, it is set to 0
										final boolean hasLocation = (tInfoI.locationX != null) || (tInfoI.locationY != null) || (tInfoI.locationZ != null);
										if (hasLocation)
											tI.setLocation( new double[] {
													tInfoI.locationX != null ? tInfoI.locationX : 0,
													tInfoI.locationY != null ? tInfoI.locationY : 0,
													tInfoI.locationZ != null ? tInfoI.locationZ : 0} );
									}

									ViewSetup vs = new ViewSetup( viewSetupId, 
													viewSetupId.toString(), 
													state.getDimensionMap().get( (viewList.get( 0 ))).getA(),
													state.getDimensionMap().get( (viewList.get( 0 ))).getB(),
													tI, chI, aI, illumI );

									viewSetups.add( vs );
									viewSetupId++;
									addedViewSetup = true;
								
								}
								
							}
						}
					}
		
		
		
		SequenceDescription sd = new SequenceDescription( new TimePoints( timePoints ), viewSetups , null, new MissingViews( missingViewIds ));

		Map< BasicViewDescription< ? >, FileMapEntry > fileMap = new HashMap<>();
		for (Pair<Integer, Integer> k : ViewIDfileMap.keySet())
		{
			System.out.println( k.getA() + " " + k.getB() );
			ViewDescription vdI = sd.getViewDescription( k.getA(), k.getB() );
			System.out.println( vdI );
			if (vdI != null && vdI.isPresent()){
				fileMap.put( vdI, ViewIDfileMap.get( k ) );
			}
		}

		final ImgLoader imgLoader;
		if (withVirtualLoader)
			imgLoader = new FileMapImgLoaderLOCI2( fileMap, sd, state.getWasZGrouped() );
		else
			imgLoader = new FileMapImgLoaderLOCI( fileMap, sd, state.getWasZGrouped() );
		sd.setImgLoader( imgLoader );

		double minResolution = Double.MAX_VALUE;
		for ( VoxelDimensions d : state.getDimensionMap().values().stream().map( p -> p.getB() ).collect( Collectors.toList() ) )
		{
			for (int di = 0; di < d.numDimensions(); di++)
				minResolution = Math.min( minResolution, d.dimension( di ) );
		}

		// create calibration + translation view registrations
		ViewRegistrations vrs = DatasetCreationUtils.createViewRegistrations( sd.getViewDescriptions(), minResolution );

		// create the initial view interest point object
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints();
		//viewInterestPoints.createViewInterestPoints( sd.getViewDescriptions() );

		SpimData2 data = new SpimData2( new File("/").toURI(), sd, vrs, viewInterestPoints, new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );
		return data;
	}


	@Override
	public SpimData2 createDataset( final String xmlFileName )
	{

		FileListChooser chooser = fileListChoosers.get( 0 );

		// only ask how we want to choose files if there are multiple ways
		if (fileListChoosers.size() > 1)
		{
			String[] fileListChooserChoices = new String[fileListChoosers.size()];
			for (int i = 0; i< fileListChoosers.size(); i++)
				fileListChooserChoices[i] = fileListChoosers.get( i ).getDescription();

			GenericDialog gd1 = new GenericDialog( "How to select files" );
			gd1.addChoice( "file chooser", fileListChooserChoices, fileListChooserChoices[0] );
			gd1.showDialog();

			if (gd1.wasCanceled())
				return null;

			chooser = fileListChoosers.get( gd1.getNextChoiceIndex() );
		}

		List<File> files = chooser.getFileList();
		// exit here if files is empty, e.g. when there was a typo in path
		if (files.size() < 1)
		{
			IJ.log("" + new Date(System.currentTimeMillis()) + " - ERROR: No file(s) found at the specified location, quitting.");
			return null;
		}

		FileListViewDetectionState state = new FileListViewDetectionState();
		FileListDatasetDefinitionUtil.detectViewsInFiles( files, state);

		Map<Class<? extends Entity>, List<Integer>> fileVariableToUse = new HashMap<>();
		List<String> choices = new ArrayList<>();

		FilenamePatternDetector patternDetector = new NumericalFilenamePatternDetector();
		patternDetector.detectPatterns( files.stream().map( File::getAbsolutePath ).collect( Collectors.toList() ) );
		int numVariables = patternDetector.getNumVariables();

		StringBuilder inFileSummarySB = new StringBuilder();
		inFileSummarySB.append( "<html> <h2> Views detected in files </h2>" );

		// summary timepoints
		if (state.getMultiplicityMap().get( TimePoint.class ) == CheckResult.SINGLE)
		{
//			inFileSummarySB.append( "<p> No timepoints detected within files </p>" );
			choices.add( "TimePoints" );
		}
		else if (state.getMultiplicityMap().get( TimePoint.class ) == CheckResult.MULTIPLE_INDEXED)
		{
			int numTPs = (Integer) state.getAccumulateMap( TimePoint.class ).keySet().stream().reduce(0, (x,y) -> Math.max( (Integer) x, (Integer) y) );
			inFileSummarySB.append( "<p style=\"color:green\">" + numTPs+ " timepoints detected within files </p>" );
			if (state.getAccumulateMap( TimePoint.class ).size() > 1)
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: Number of timepoints is not the same for all views </p>" );
		}

		inFileSummarySB.append( "<br />" );

		// we might want to know how many channels/illums or tiles/angles to expect even though we have no metadata
		// NB: dont use these results if there IS metadata
		final Pair< Integer, Integer > minMaxNumCannelsIndexed = FileListViewDetectionState.getMinMaxNumChannelsIndexed( state );
		final Pair< Integer, Integer > minMaxNumSeriesIndexed = FileListViewDetectionState.getMinMaxNumSeriesIndexed( state );

		// summary channel
		if (state.getMultiplicityMap().get( Channel.class ) == CheckResult.SINGLE)
		{
			inFileSummarySB.append( !state.getAmbiguousIllumChannel() ? "" : "<p>"+ getRangeRepresentation( minMaxNumCannelsIndexed ) + " Channels OR Illuminations detected within files </p>");
			choices.add( "Channels" );
		}
		else if (state.getMultiplicityMap().get( Channel.class ) == CheckResult.MULTIPLE_INDEXED)
		{

			inFileSummarySB.append( "<p > " + getRangeRepresentation( minMaxNumCannelsIndexed ) + " Channels detected within files </p>" );
			inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no metadata was found for Channels </p>" );
			if (state.getMultiplicityMap().get( Illumination.class ) == CheckResult.MULTIPLE_INDEXED)
			{
				choices.add( "Channels" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no matadata for Illuminations found either, cannot distinguish </p>" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: choose manually whether files contain Channels or Illuminations below </p>" );
			}
		} else if (state.getMultiplicityMap().get( Channel.class ) == CheckResult.MUlTIPLE_NAMED)
		{
			int numChannels = state.getAccumulateMap( Channel.class ).size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numChannels + " Channels found within files </p>" );
		}

		//inFileSummarySB.append( "<br />" );

		// summary illum
		if ( state.getMultiplicityMap().get( Illumination.class ) == CheckResult.SINGLE )
		{
			//if (!state.getAmbiguousIllumChannel())
			//	inFileSummarySB.append( "<p> No illuminations detected within files </p>" );
			choices.add( "Illuminations" );
		}
		else if ( state.getMultiplicityMap().get( Illumination.class ) == CheckResult.MULTIPLE_INDEXED )
		{
			if (state.getMultiplicityMap().get( Channel.class ).equals( CheckResult.MULTIPLE_INDEXED ))
				choices.add( "Illuminations" );
			else
				inFileSummarySB.append( "<p > " + getRangeRepresentation( minMaxNumCannelsIndexed ) + " Illuminations detected within files </p>" );
		}
		else if ( state.getMultiplicityMap().get( Illumination.class ) == CheckResult.MUlTIPLE_NAMED )
		{
			int numIllum = state.getAccumulateMap( Illumination.class ).size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numIllum + " Illuminations found within files </p>" );
		}

		// summary tile
		if ( state.getMultiplicityMap().get( Tile.class ) == CheckResult.SINGLE )
		{
			//inFileSummarySB.append( "<p> No tiles detected within files </p>" );
			choices.add( "Tiles" );
		}
		else if ( state.getMultiplicityMap().get( Tile.class ) == CheckResult.MULTIPLE_INDEXED )
		{
			inFileSummarySB.append( "<p > " + getRangeRepresentation( minMaxNumSeriesIndexed ) + " Tiles detected within files </p>" );
			inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no metadata was found for Tiles </p>" );
			if (state.getMultiplicityMap().get( Angle.class ) == CheckResult.MULTIPLE_INDEXED)
			{
				choices.add( "Tiles" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: no metadata for Angles found either, cannot distinguish </p>" );
				inFileSummarySB.append( "<p style=\"color:orange\">WARNING: choose manually wether files contain Tiles or Angles below </p>" );
			}
		}
		else if ( state.getMultiplicityMap().get( Tile.class ) == CheckResult.MUlTIPLE_NAMED )
		{
			int numTile = state.getAccumulateMap( Tile.class ).size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numTile + " Tiles found within files </p>" );
			
		}
		
		//inFileSummarySB.append( "<br />" );
		
		// summary angle
		if ( state.getMultiplicityMap().get( Angle.class ) == CheckResult.SINGLE )
		{
			//inFileSummarySB.append( "<p> No angles detected within files </p>" );
			choices.add( "Angles" );
		}
		else if ( state.getMultiplicityMap().get( Angle.class ) == CheckResult.MULTIPLE_INDEXED )
		{
			if (state.getMultiplicityMap().get( Tile.class ) == CheckResult.MULTIPLE_INDEXED)
				choices.add( "Angles" );
			else
				inFileSummarySB.append( "<p > " + getRangeRepresentation( minMaxNumSeriesIndexed ) + " Angles detected within files </p>" );
		}
		else if ( state.getMultiplicityMap().get( Angle.class ) == CheckResult.MUlTIPLE_NAMED )
		{
			int numAngle = state.getAccumulateMap( Angle.class ).size();
			inFileSummarySB.append( "<p style=\"color:green\">" + numAngle + " Angles found within files </p>" );
		}

		inFileSummarySB.append( "</html>" );

		GenericDialogPlus gd = new GenericDialogPlus("Define Metadata for Views");
		
		//gd.addMessage( "<html> <h1> View assignment </h1> </html> ");
		//addMessageAsJLabel( "<html> <h1> View assignment </h1> </html> ", gd);
		
		//gd.addMessage( inFileSummarySB.toString() );
		addMessageAsJLabel(inFileSummarySB.toString(), gd);
		
		String[] choicesAngleTile = new String[] {"Angles", "Tiles"};
		String[] choicesChannelIllum = new String[] {"Channels", "Illuminations"};

		//if (state.getAmbiguousAngleTile())
		String preferedAnglesOrTiles = state.getMultiplicityMap().get( Angle.class ) == CheckResult.MULTIPLE_INDEXED ? "Angles" : "Tiles";
		if (state.getAmbiguousAngleTile() || state.getMultiplicityMap().get( Tile.class) == CheckResult.MUlTIPLE_NAMED)
			gd.addChoice( "BioFormats_Series_are?", choicesAngleTile, preferedAnglesOrTiles );
		if (state.getAmbiguousIllumChannel())
			gd.addChoice( "BioFormats_Channels_are?", choicesChannelIllum, choicesChannelIllum[0] );


		// We have grouped files -> detect patterns again, using only master files for group
		// that way, we automatically ignore patterns BioFormats has already grouped
		// e.g. MicroManager _MMSTack_Pos{?}.ome.tif -> positions are treated as series by BF
		// we have to keep the old pattern detector (for all files) -> it will be used for final view assignment
		FilenamePatternDetector patternDetectorOld = null;
		if (state.getGroupedFormat() )
		{
			patternDetectorOld = patternDetector;
			patternDetector = new NumericalFilenamePatternDetector();
			// detect in all unique master files in groupUsageMap := actual file -> (master file, series)
			List< File > f = state.getGroupUsageMap().values().stream().map( p -> p.getA() ).collect( Collectors.toSet() ).stream().collect( Collectors.toList() );
			patternDetector.detectPatterns( f.stream().map( File::getAbsolutePath ).collect( Collectors.toList() ) );
			numVariables = patternDetector.getNumVariables();
		}

		if (numVariables >= 1)
//		sbfilePatterns.append( "<p> No numerical patterns found in filenames</p>" );
//		else
		{
			final Pair< String, String > prefixAndPattern = splitIntoPrefixAndPattern( patternDetector );
			final StringBuilder sbfilePatterns = new StringBuilder();
			sbfilePatterns.append(  "<html> <h2> Patterns in filenames </h2> " );
			sbfilePatterns.append( "<h3 style=\"color:green\"> " + numVariables + ""
					+ " numerical pattern" + ((numVariables > 1) ? "s": "") + " found in filenames</h3>" );
			sbfilePatterns.append( "</br><p> Patterns: " + getColoredHtmlFromPattern( prefixAndPattern.getB(), false ) + "</p>" );
			sbfilePatterns.append( "</html>" );
			addMessageAsJLabel(sbfilePatterns.toString(), gd);
		}

		//gd.addMessage( sbfilePatterns.toString() );

		choices.add( "-- ignore this pattern --" );
		choices.add( Z_VARIABLE_CHOICE );
		String[] choicesAll = choices.toArray( new String[]{} );

		for (int i = 0; i < numVariables; i++)
		{
			gd.addChoice( "Pattern_" + i + " represents", choicesAll, choicesAll[0] );
			//do not fail just due to coloring
			try
			{
				((Label) gd.getComponent( gd.getComponentCount() - 2 )).setForeground( getColorN( i ) );
			}
			catch (Exception e) {}
		}

		addMessageAsJLabel(  "<html> <h2> Voxel Size calibration </h2> </html> ", gd );
		final boolean allVoxelSizesTheSame = FileListViewDetectionState.allVoxelSizesTheSame( state );
		if(!allVoxelSizesTheSame)
			addMessageAsJLabel(  "<html> <p style=\"color:orange\">WARNING: Voxel Sizes are not the same for all views, modify them at your own risk! </p> </html> ", gd );

		final VoxelDimensions someCalib = state.getDimensionMap().values().iterator().next().getB();

		gd.addCheckbox( "Modify_voxel_size?", false );
		gd.addNumericField( "Voxel_size_X", someCalib.dimension( 0 ), 4 );
		gd.addNumericField( "Voxel_size_Y", someCalib.dimension( 1 ), 4 );
		gd.addNumericField( "Voxel_size_Z", someCalib.dimension( 2 ), 4 );
		gd.addStringField( "Voxel_size_unit", someCalib.unit(), 20 );

		// try to guess if we need to move to grid
		// we suggest move if: we have no tile metadata
		addMessageAsJLabel(  "<html> <h2> Move to Grid </h2> </html> ", gd );
		boolean haveTileLoc = state.getAccumulateMap( Tile.class ).keySet().stream().filter( t -> ((TileInfo)t).locationX != null && ((TileInfo)t).locationX != 0.0 ).findAny().isPresent();

		String[] choicesGridMove = new String[] {"Do not move Tiles to Grid (use Metadata if available)",
				"Move Tiles to Grid (interactive)", "Move Tile to Grid (Macro-scriptable)"};
		gd.addChoice( "Move_Tiles_to_Grid_(per_Angle)?", choicesGridMove, choicesGridMove[!haveTileLoc ? 1 : 0] );

		gd.showDialog();

		if (gd.wasCanceled())
			return null;

		boolean preferAnglesOverTiles = true;
		boolean preferChannelsOverIlluminations = true;
		if (state.getAmbiguousAngleTile() || state.getMultiplicityMap().get( Tile.class) ==  CheckResult.MUlTIPLE_NAMED)
			preferAnglesOverTiles = gd.getNextChoiceIndex() == 0;
		if (state.getAmbiguousIllumChannel())
			preferChannelsOverIlluminations = gd.getNextChoiceIndex() == 0;

		fileVariableToUse.put( TimePoint.class, new ArrayList<>() );
		fileVariableToUse.put( Channel.class, new ArrayList<>() );
		fileVariableToUse.put( Illumination.class, new ArrayList<>() );
		fileVariableToUse.put( Tile.class, new ArrayList<>() );
		fileVariableToUse.put( Angle.class, new ArrayList<>() );

		final List<Integer> zVariables = new ArrayList<>();
		for (int i = 0; i < numVariables; i++)
		{
			String choice = gd.getNextChoice();
			if (choice.equals( "TimePoints" ))
				fileVariableToUse.get( TimePoint.class ).add( i );
			else if (choice.equals( "Channels" ))
				fileVariableToUse.get( Channel.class ).add( i );
			else if (choice.equals( "Illuminations" ))
				fileVariableToUse.get( Illumination.class ).add( i );
			else if (choice.equals( "Tiles" ))
				fileVariableToUse.get( Tile.class ).add( i );
			else if (choice.equals( "Angles" ))
				fileVariableToUse.get( Angle.class ).add( i );
			else if (choice.equals( Z_VARIABLE_CHOICE ))
				zVariables.add( i );
		}

		// TODO handle Angle-Tile swap here	
		FileListDatasetDefinitionUtil.resolveAmbiguity( state.getMultiplicityMap(), state.getAmbiguousIllumChannel(), preferChannelsOverIlluminations, state.getAmbiguousAngleTile(), !preferAnglesOverTiles );

		// if we have used a grouped pattern
		// we will still have to use the old pattern detector (containing all files) in the next step
		// update fileVariableToUse so all grouped patterns are ignored
		if (patternDetectorOld != null)
		{
			// ungrouped variables have more than one match in master files
			final boolean[] ungroupedVariable = new boolean[patternDetectorOld.getNumVariables()];
			final String[] variableInstances = new String[patternDetectorOld.getNumVariables()];
			for (final File masterFile : state.getGroupUsageMap().values().stream().map( p -> p.getA() ).collect( Collectors.toSet() ).stream().collect( Collectors.toList() ))
			{
				final Matcher m = patternDetectorOld.getPatternAsRegex().matcher( masterFile.getAbsolutePath() );
				m.matches();
				for (int i = 0; i<patternDetectorOld.getNumVariables(); i++)
				{
					final String variableInstance = m.group( i + 1 );
					if (variableInstances[i] == null)
						variableInstances[i] = variableInstance;
					// we found an instance != first
					if (!variableInstances[i].equals( variableInstance ) )
						ungroupedVariable[i] = true;
				}
			}

			// update fileVariablesToUse
			// idx of pattern in grouped files -> idx in all files
			for (final AtomicInteger oldIdx = new AtomicInteger(); oldIdx.get()<patternDetectorOld.getNumVariables(); oldIdx.incrementAndGet())
			{
				if (!ungroupedVariable[oldIdx.get()])
					fileVariableToUse.forEach( (k, v) -> {
							fileVariableToUse.put( k, v.stream().map( (idx) -> ((idx >= oldIdx.get()) ? idx + 1 : idx )).collect( Collectors.toList() ) );
					});
			}
		}

		FileListDatasetDefinitionUtil.expandAccumulatedViewInfos(
				fileVariableToUse, 
				patternDetectorOld == null ? patternDetector : patternDetectorOld,
				state);

		// here, we concatenate Z-grouped files
		if (zVariables.size() > 0)
			FileListDatasetDefinitionUtil.groupZPlanes( state, patternDetector, zVariables );

		// query modified calibration
		final boolean modifyCalibration = gd.getNextBoolean();
		if (modifyCalibration)
		{
			final double calX = gd.getNextNumber();
			final double calY = gd.getNextNumber();
			final double calZ = gd.getNextNumber();
			final String calUnit = gd.getNextString();

			for (final FileMapEntry key : state.getDimensionMap().keySet())
			{
				final Pair< Dimensions, VoxelDimensions > pairOld = state.getDimensionMap().get( key );
				final Pair< Dimensions, VoxelDimensions > pairNew = new ValuePair< Dimensions, VoxelDimensions >( pairOld.getA(), new FinalVoxelDimensions( calUnit, calX, calY, calZ ) );
				state.getDimensionMap().put( key, pairNew );
			}
		}

		final int gridMoveType = gd.getNextChoiceIndex();


		// we have multiple Angles defined by a file pattern, but not by metadata
		// give user the option to interpret pattern values as angles
		// and query rotation axis manually 
		if (state.getMultiplicityMap().get( Angle.class ).equals( CheckResult.SINGLE ) && fileVariableToUse.get( Angle.class ).size() == 1)
		{
			final GenericDialogPlus gdAnglesFromPattern = new GenericDialogPlus( "Parse Angles from file pattern" );
			final List<String> seenAngles = new ArrayList<>();

			StringBuilder sbAngleInfo = new StringBuilder();
			sbAngleInfo.append( "<html>No metadata for sample rotation found, but numeric patterns for Angle in filenames:" );
			sbAngleInfo.append( "<ul>" );
			for (String s: patternDetector.getValuesForVariable( fileVariableToUse.get( Angle.class ).get( 0 ) ) )
			{
				if(!seenAngles.contains(s))
				{
    				seenAngles.add(s);
    				sbAngleInfo.append( "<li>" + s + "</li>" );
				}
			}
			sbAngleInfo.append( "</ul>You can choose to interpret the pattern as rotation angles."
					+ "<br/>Please check \"apply angle rotation\" in the next step to apply rotations to data immediately" );
			sbAngleInfo.append( "</html>" );
			addMessageAsJLabel( sbAngleInfo.toString(), gdAnglesFromPattern );

			gdAnglesFromPattern.addCheckbox( "use_pattern_as_rotation", true );
			gdAnglesFromPattern.addChoice( "rotation_axis", new String[] {"X", "Y", "Z"},  "Y" );
			gdAnglesFromPattern.showDialog();

			if (gdAnglesFromPattern.wasCanceled())
				return null;

			boolean usePatternRotation = gdAnglesFromPattern.getNextBoolean();
			int rotationAxis = gdAnglesFromPattern.getNextChoiceIndex();

			if (usePatternRotation)
			{
				// build new AngleInfo map (since we have no metadata, all values might point to the same instance)
				HashMap< Integer, Object > angleInfosFromFilePattern = new HashMap<Integer, Object>();

				// go through ids of AngleInfos and create new AngleInfo based on id and manually given rotation axis
				state.getDetailMap().get(Angle.class).keySet().forEach( k -> {
					AngleInfo angleInfoI = new AngleInfo();
					angleInfoI.angle = (double) k;
					angleInfoI.axis = rotationAxis;
					angleInfosFromFilePattern.put( k, angleInfoI );
				});

				// clear old detail map and add updated AngleInfos
				state.getDetailMap().get(Angle.class).clear();
				state.getDetailMap().get(Angle.class).putAll( angleInfosFromFilePattern );

			}
		}

		// we create a virtual SpimData at first
		SpimData2 data = buildSpimData( state, true );

		// we move to grid, collect parameters first
		final List<RegularTranslationParameters> gridParams = new ArrayList<>();
		if (gridMoveType == 2)
		{
			final ArrayList<ViewDescription> vds = new ArrayList<>(data.getSequenceDescription().getViewDescriptions().values());

			final Set<Class<? extends Entity>> angleClassSet = new HashSet<>();
			angleClassSet.add( Angle.class );
			final Set<Class<? extends Entity>> tileClassSet = new HashSet<>();
			tileClassSet.add( Tile.class );

			// first, split by angles (we process each angle separately)
			final List< Group< ViewDescription > > vdsAngleGrouped = Group.splitBy( vds , angleClassSet );
			for (Group<ViewDescription> vdsAngle : vdsAngleGrouped)
			{
				// second, we split by tiles (all channels/illums/tps of a tile are grouped)
				final List< Group< ViewDescription > > tilesGrouped = Group.splitBy( new ArrayList<>( vdsAngle.getViews() ), tileClassSet );
				final String angleName = vdsAngle.getViews().iterator().next().getViewSetup().getAngle().getName();
				if (tilesGrouped.size() < 2)
					continue;

				final RegularTranslationParameters params = RegularTranformHelpers.queryParameters( "Move Tiles of Angle " + angleName, tilesGrouped.size() );
				
				if ( params == null )
					return null;

				gridParams.add( params );
			}
		}

		GenericDialogPlus gdSave = new GenericDialogPlus( "Rs-save dataset definition" );

		addMessageAsJLabel("<html> <h1> Input image	 storage options </h1> <br /> </html>", gdSave);
		gdSave.addChoice( "how_to_store_input_images", loadChoices, loadChoices[defaultLoadChoice] );
		gdSave.addMessage( "Note: use the load raw data directly option when planning to use BigStitcher-Spark for resaving, this only produces an XML.", GUIHelper.mediumstatusfont, null);	
		gdSave.addCheckbox( "load_raw_data_virtually (supports large stacks; required to work with BigSticher-Spark and efficient re-saving to OME-ZARR/HDF5/N5)", defaultVirtual );

		addMessageAsJLabel("<html><h2> Save paths for XML & Data</h2></html>", gdSave);

		// get default save path := deepest parent directory of all files in dataset
		final Set<String> filenames = new HashSet<>();
		((FileMapGettable)data.getSequenceDescription().getImgLoader() ).getFileMap().values().stream().forEach(
				p -> 
				{
					filenames.add( p.file().getAbsolutePath());
				});
		final File prefixPath;
		if (filenames.size() > 1)
			prefixPath = getLongestPathPrefix( filenames );
		else
		{
			String fi = filenames.iterator().next();
			prefixPath = new File((String)fi.subSequence( 0, fi.lastIndexOf( File.separator )));
		}

		gdSave.addDirectoryField( "metadata_save_path (XML)", prefixPath.getAbsolutePath(), 65 );
		gdSave.addDirectoryField( "image_data_save_path", prefixPath.getAbsolutePath(), 65 );
		gdSave.addMessage( "Note: image data save path will be ignored if not re-saved as N5/HDF5.\nOnly provide the path, the actual .zarr, .n5 or .h5 path/file will be appended!", GUIHelper.smallStatusFont );

		// check if all stack sizes are the same (in each file)
		boolean zSizeEqualInEveryFile = LegacyFileMapImgLoaderLOCI.isZSizeEqualInEveryFile( data, (FileMapGettable)data.getSequenceDescription().getImgLoader() );
		// only consider if there are actually multiple angles/tiles
		zSizeEqualInEveryFile = zSizeEqualInEveryFile && !(data.getSequenceDescription().getAllAnglesOrdered().size() == 1 && data.getSequenceDescription().getAllTilesOrdered().size() == 1);
		// notify user if all stacks are equally size (in every file)
		if (zSizeEqualInEveryFile)
		{
			addMessageAsJLabel( "<html><p style=\"color:orange\">WARNING: all stacks have the same size, this might be caused by a bug"
					+ " in BioFormats. </br> Please re-check stack sizes if necessary.</p></html>", gdSave );
			// default choice for size re-check: do it if all stacks are the same size
			gdSave.addCheckbox( "check_stack_sizes", zSizeEqualInEveryFile );
		}


		boolean multipleAngles = data.getSequenceDescription().getAllAnglesOrdered().size() > 1;
		if (multipleAngles)
			gdSave.addCheckbox( "apply_angle_rotation", true );

		gdSave.showDialog();

		if ( gdSave.wasCanceled() )
			return null;

		final int loadChoice = defaultLoadChoice = gdSave.getNextChoiceIndex();
		final boolean useVirtualLoader = defaultVirtual = gdSave.getNextBoolean();

		// re-build the SpimData if user explicitly doesn't want virtual loading (the first one was created as virtual)
		if (!useVirtualLoader)
			data = buildSpimData( state, false );

		// by default, both are identical
		final String chosenPathXML = gdSave.getNextString(); // where the XML and interest points live
		final String chosenPathData;

		if ( loadChoice == 4 ) // Load raw data directly (no resaving)
		{
			gdSave.getNextString(); // << goes to void
			chosenPathData = prefixPath.getAbsolutePath(); // the data is where it is if not resaved
			IOFunctions.println( "Ignoring selection for 'image data save path' since data is not resaved. Data is in '" + prefixPath.getAbsolutePath() + "'" );
		}
		else
		{
			chosenPathData = gdSave.getNextString(); // will be stored in the img loader (if identical to chosenPathXML then relative, otherwise absolute)
		}

		final boolean resaveAsOMEZARRv3 = (loadChoice == 0);
		final boolean resaveAsOMEZARRv2 = (loadChoice == 1);
		final boolean resaveAsHDF5 = (loadChoice == 2);
		final boolean resaveAsN5 = (loadChoice == 3);

		URI chosenPathXMLURI, chosenPathDataURI;

		chosenPathXMLURI = URITools.toURI( chosenPathXML );
		chosenPathDataURI = URITools.toURI( chosenPathData );

		if ( !URITools.isKnownScheme( chosenPathDataURI ) )
		{
			IOFunctions.println( "The scheme of the image data path you selected '" + chosenPathDataURI + "' is unknown." );
			return null;
		}

		if ( !URITools.isKnownScheme( chosenPathXMLURI ) )
		{
			IOFunctions.println( "The scheme of the  XML path you selected '" + chosenPathXMLURI + "' is unknown." );
			return null;
		}

		IOFunctions.println( "XML & metadata path: " + chosenPathXMLURI );
		IOFunctions.println( "XML: " + URITools.appendName( chosenPathXMLURI, xmlFileName ) );
		IOFunctions.println( "Image data path: " + chosenPathDataURI );

		data.setBasePathURI( chosenPathXMLURI );

		// check and correct stack sizes (the "BioFormats bug")
		// TODO: remove once the bug is fixed upstream
		if (zSizeEqualInEveryFile)
		{
			final boolean checkSize = gdSave.getNextBoolean();
			if (checkSize)
			{
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Checking file sizes ... " );
				LegacyFileMapImgLoaderLOCI.checkAndRemoveZeroVolume( data, (ImgLoader & FileMapGettable) data.getSequenceDescription().getImgLoader(), zVariables.size() > 0 );
				IOFunctions.println( new Date( System.currentTimeMillis() ) + ": Finished." );
			}
		}

		// now, we have a working SpimData and have corrected for unequal z sizes -> do grid move if necessary
		if (gridMoveType == 2)
		{
			final ArrayList<ViewDescription> vds = new ArrayList<>(data.getSequenceDescription().getViewDescriptions().values());

			final Set<Class<? extends Entity>> angleClassSet = new HashSet<>();
			angleClassSet.add( Angle.class );
			final Set<Class<? extends Entity>> tileClassSet = new HashSet<>();
			tileClassSet.add( Tile.class );

			// first, split by angles (we process each angle separately)
			final List< Group< ViewDescription > > vdsAngleGrouped = Group.splitBy( vds , angleClassSet );
			int i = 0;
			for (Group<ViewDescription> vdsAngle : vdsAngleGrouped)
			{
				// second, we split by tiles (all channels/illums/tps of a tile are grouped)
				final List< Group< ViewDescription > > tilesGrouped = Group.splitBy( new ArrayList<>( vdsAngle.getViews() ), tileClassSet );
				if (tilesGrouped.size() < 2)
					continue;

				// sort by tile id of first view in groups
				Collections.sort( tilesGrouped, new Comparator< Group< ViewDescription > >()
				{
					@Override
					public int compare(Group< ViewDescription > o1, Group< ViewDescription > o2)
					{
						if (o1.size() == 0)
							return -o2.size();
						return o1.getViews().iterator().next().getViewSetup().getTile().getId() - o2.getViews().iterator().next().getViewSetup().getTile().getId();
					}
				} );

				RegularTranslationParameters gridParamsI = gridParams.get( i++ );
				RegularTranformHelpers.applyToSpimData( data, tilesGrouped, gridParamsI, true );
			}
		}

		boolean applyAxis = false;
		if (multipleAngles)
			applyAxis = gdSave.getNextBoolean();

		// View Registrations should now be complete
		// with translated tiles, we also have to take the center of rotation into account
		if (applyAxis)
			Apply_Transformation.applyAxisGrouped( data );

		if (resaveAsHDF5)
		{
			if ( !URITools.isFile( chosenPathDataURI ) )
			{
				IOFunctions.println( "The image data path you selected '" + chosenPathDataURI + "' is not on a local file system. Re-saving to HDF5 only works on locally mounted file systems." );
				return null;
			}

			if ( !URITools.isFile( chosenPathXMLURI ) )
			{
				IOFunctions.println( "The XML path you selected '" + chosenPathXMLURI + "' is not on a local file system. Re-saving to HDF5 only works on locally mounted file systems." );
				return null;
			}

			final Map< Integer, ExportMipmapInfo > perSetupExportMipmapInfo = Resave_HDF5.proposeMipmaps( data.getSequenceDescription().getViewSetupsOrdered() );
			final int firstviewSetupId = data.getSequenceDescription().getViewSetupsOrdered().get( 0 ).getId();
			//Generic_Resave_HDF5.lastExportPath = String.join( File.separator, chosenPath.getAbsolutePath(), "dataset");
			final ParametersResaveHDF5 params = Generic_Resave_HDF5.getParameters( perSetupExportMipmapInfo.get( firstviewSetupId ), false, true );

			// HDF5 options dialog was cancelled
			if (params == null)
				return null;

			String dataPath = URITools.fromURI( chosenPathDataURI );
			String xmlPath = URITools.fromURI( chosenPathXMLURI );

			if ( !new File( dataPath ).exists() )
			{
				IOFunctions.println( "Path for HDF5 does not exist, trying to create folder: " + dataPath );
				new File( dataPath ).mkdirs();
			}

			params.setHDF5File(new File( dataPath, xmlFileName.subSequence( 0, xmlFileName.length() - 4 ) + ".h5" ) );
			params.setSeqFile(new File( xmlPath, xmlFileName ) );

			final ProgressWriter progressWriter = new ProgressWriterIJ();
			progressWriter.out().println( "starting export..." );

			Generic_Resave_HDF5.writeHDF5( data, params, progressWriter );

			IOFunctions.println( "(" + new Date(  System.currentTimeMillis() ) + "): HDF5 resave finished." );

			data = Resave_HDF5.createXMLObject( data, new ArrayList<>(data.getSequenceDescription().getViewDescriptions().keySet()), params, progressWriter, true );

			// ensure progressbar is gone
			progressWriter.setProgress( 1.0 );
		}
		else if (resaveAsN5 || resaveAsOMEZARRv3 || resaveAsOMEZARRv2 )
		{
			final ArrayList< ViewDescription > viewIds = new ArrayList<>( data.getSequenceDescription().getViewDescriptions().values() );
			Collections.sort( viewIds );

			final SequenceDescription sd = data.getSequenceDescription();

			final URI xmlURI = URITools.toURI( URITools.appendName(chosenPathXMLURI, xmlFileName ) );
			final URI n5DatasetURI = URITools.toURI( URITools.appendName(chosenPathDataURI, xmlFileName.subSequence( 0, xmlFileName.length() - 4 ) + (resaveAsN5 ? ".n5" : ".ome.zarr" ) ) );

			final StorageFormat format;
			final String formatString;

			if ( resaveAsN5 )
			{
				format = StorageFormat.N5;
				formatString = "N5";
			}
			else if ( resaveAsOMEZARRv2 )
			{
				format = StorageFormat.ZARR2;
				formatString = "OME-Zarr v2";
			}
			else
			{
				format = StorageFormat.ZARR;
				formatString = "OME-Zarr v3";
			}

			IOFunctions.println( formatString + " path: " + n5DatasetURI );

			final ParametersResaveN5Api n5params = ParametersResaveN5Api.getParamtersIJ(
					xmlURI,
					n5DatasetURI,
					viewIds.stream().map( vid -> sd.getViewSetups().get( vid.getViewSetupId() ) ).collect( Collectors.toSet() ),
					format, // do not ask for format
					false ); // do not ask for paths again

			if ( n5params == null )
				return null;

			data = Resave_N5Api.resaveN5( data, viewIds, n5params, false );

			IOFunctions.println( "(" + new Date(  System.currentTimeMillis() ) + "): " + formatString +" resave finished." );
		}

		if (gridMoveType == 1)
		{
			data.gridMoveRequested = true;
		}
		
		return data;
	}
	
	public static File getLongestPathPrefix(Collection<String> paths)
	{
		String prefixPath = paths.stream().reduce( paths.iterator().next(), 
				(a,b) -> {
					List<String> aDirs = Arrays.asList( a.split(Pattern.quote(File.separator) ));
					List<String> bDirs = Arrays.asList( b.split( Pattern.quote(File.separator) ));
					List<String> res = new ArrayList<>();
					for (int i = 0; i< Math.min( aDirs.size(), bDirs.size() ); i++)
					{
						if (aDirs.get( i ).equals( bDirs.get( i ) ))
							res.add(aDirs.get( i ));
						else {
							break;
						}
					}
					return String.join(File.separator, res );
				});
		return new File(prefixPath);
		
	}

	@Override
	public String getTitle() { return "Automatic Loader (Bioformats based)"; }
	
	@Override
	public String getExtendedDescription()
	{
		return "This datset definition tries to automatically detect views in a\n" +
				"list of files openable by BioFormats. \n" +
				"If there are multiple Images in one file, it will try to guess which\n" +
				"views they belong to from meta data or ask the user for advice.\n";
	}


	@Override
	public MultiViewDatasetDefinition newInstance()
	{
		return new FileListDatasetDefinition();
	}
	
	
	public static boolean containsAny(String s, String ... templates)
	{
		for (int i = 0; i < templates.length; i++)
			if (s.contains( templates[i] ))
				return true;
		return false;
	}


	public static String getColoredHtmlFromPattern(String pattern, boolean withRootTag)
	{
		final StringBuilder sb = new StringBuilder();
		if (withRootTag)
			sb.append( "<html>" );
		int n = 0;
		for (int i = 0; i<pattern.length(); i++)
		{
			if (pattern.charAt( i ) == '{')
			{
				Color col = getColorN( n++ );
				sb.append( "<span style=\"color: rgb("+ col.getRed() + "," + col.getGreen() + "," + col.getBlue()   +")\">{" );
			}
			else if (pattern.charAt( i ) == '}')
				sb.append( "}</span>");
			else
				sb.append( pattern.charAt( i ) );
		}
		if (withRootTag)
			sb.append( "</html>" );
		return sb.toString();
	}
	
	public static Color getColorN(long n)
	{
		Iterator< ARGBType > iterator = ColorStream.iterator();
		ARGBType c = new ARGBType();
		for (int i = 0; i<n+43; i++)
			for (int j = 0; j<3; j++)
				c = iterator.next();
		return new Color( ARGBType.red( c.get() ), ARGBType.green( c.get() ), ARGBType.blue( c.get() ) );
	}
	
	public static Pair<String, String> splitIntoPrefixAndPattern(FilenamePatternDetector detector)
	{
		final String stringRepresentation = detector.getStringRepresentation();
		final List< String > beforePattern = new ArrayList<>();
		final List< String > afterPattern = new ArrayList<>();
		
		boolean found = false;
		for (String s : Arrays.asList( stringRepresentation.split(Pattern.quote(File.separator) )))
		{
			if (!found && s.contains( "{" ))
				found = true;
			if (found)
				afterPattern.add( s );
			else
				beforePattern.add( s );
		}
		String prefix = String.join( File.separator, beforePattern );
		String pattern = String.join( File.separator, afterPattern );
		return new ValuePair< String, String >( prefix, pattern );
	}

	public static String getRangeRepresentation(Pair<Integer, Integer> range)
	{
		if (range.getA().equals( range.getB() ))
			return Integer.toString( range.getA() );
		else
			if (range.getA() < range.getB())
				return range.getA() + "-" + range.getB();
			else
				return range.getB() + "-" + range.getA();
	}

	public static Pair<String, String> splitIntoPathAndPattern(String s, String ... templates)
	{
		String[] subpaths = s.split( Pattern.quote(File.separator) );
		ArrayList<String> path = new ArrayList<>(); 
		ArrayList<String> pattern = new ArrayList<>();
		boolean noPatternFound = true;

		for (int i = 0; i < subpaths.length; i++){
			if (noPatternFound && !containsAny( subpaths[i], templates ))
			{
				path.add( subpaths[i] );
			}
			else
			{
				noPatternFound = false;
				pattern.add(subpaths[i]);
			}
		}
		
		String sPath = String.join( "/", path );
		String sPattern = String.join( "/", pattern );
		
		return new ValuePair< String, String >( sPath, sPattern );
	}
	
	
	public static void main(String[] args)
	{
		//new FileListDatasetDefinition().createDataset();
		//new WildcardFileListChooser().getFileList().forEach( f -> System.out.println( f.getAbsolutePath() ) );
		GenericDialog gd = new GenericDialog( "A" );
		gd.addMessage( getColoredHtmlFromPattern( "a{b}c{d}e{aaaaaaaaaa}aa{bbbbbbbbbbbb}ccccc{ddddddd}", true ) );
		System.out.println( getColoredHtmlFromPattern( "a{b}c{d}e", false ) );
		gd.showDialog();
	}

}
