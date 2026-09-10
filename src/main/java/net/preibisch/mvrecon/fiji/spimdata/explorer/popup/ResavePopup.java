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
package net.preibisch.mvrecon.fiji.spimdata.explorer.popup;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import bdv.export.ExportMipmapInfo;
import bdv.export.ProgressWriter;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.resave.Generic_Resave_HDF5;
import net.preibisch.mvrecon.fiji.plugin.resave.ParametersResaveN5Api;
import net.preibisch.mvrecon.fiji.plugin.resave.ProgressWriterIJ;
import net.preibisch.mvrecon.fiji.plugin.resave.Resave_HDF5;
import net.preibisch.mvrecon.fiji.plugin.resave.Resave_N5Api;
import net.preibisch.mvrecon.fiji.plugin.resave.Resave_TIFF;
import net.preibisch.mvrecon.fiji.plugin.resave.Resave_TIFF.ParametersResaveAsTIFF;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.n5api.SpimData2Tools;
import util.URITools;

public class ResavePopup extends JMenu implements ExplorerWindowSetable
{
	public static final int askWhenMoreThan = 5;
	private static final long serialVersionUID = 5234649267634013390L;

	FilteredAndGroupedExplorerPanel< ? > panel;

	protected static String[] types = new String[]{
			"As TIFF (in place) ...",
			"As compressed TIFF (in place) ...",
			"As HDF5 (in place) ...",
			"As compressed HDF5 (in place) ...",
			"As N5 (in place ) ...",
			"As N5 (local, cloud) ...",
			"As OME-ZARR v3 (in place ) ...",
			"As OME-ZARR v3 (local, cloud) ..."};

	public ResavePopup()
	{
		super( "Resave Dataset" );

		final JMenuItem tiff = new JMenuItem( types[ 0 ] );
		final JMenuItem zippedTiff = new JMenuItem( types[ 1 ] );
		final JMenuItem hdf5 = new JMenuItem( types[ 2 ] );
		final JMenuItem deflatehdf5 = new JMenuItem( types[ 3 ] );
		final JMenuItem n5 = new JMenuItem( types[ 4 ] );
		final JMenuItem n5wPath = new JMenuItem( types[ 5 ] );
		final JMenuItem omeZarr = new JMenuItem( types[ 6 ] );
		final JMenuItem omeZarrPath = new JMenuItem( types[ 7 ] );

		tiff.addActionListener( new MyActionListener( 0 ) );
		zippedTiff.addActionListener( new MyActionListener( 1 ) );
		hdf5.addActionListener( new MyActionListener( 2 ) );
		deflatehdf5.addActionListener( new MyActionListener( 3 ) );
		n5.addActionListener( new MyActionListener( 4 ) );
		n5wPath.addActionListener( new MyActionListener( 5 ) );
		omeZarr.addActionListener( new MyActionListener( 6 ) );
		omeZarrPath.addActionListener( new MyActionListener( 7 ) );

		this.add( tiff );
		this.add( zippedTiff );
		this.add( hdf5 );
		this.add( deflatehdf5 );
		this.add( n5 );
		this.add( n5wPath );
		this.add( omeZarr );
		this.add( omeZarrPath );
	}

	@Override
	public JMenuItem setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = ( FilteredAndGroupedExplorerPanel< ? > ) panel;
		return this;
	}

	public class MyActionListener implements ActionListener
	{
		final int index; // 0 == TIFF, 1 == compressed TIFF, 2 == HDF5, 3 == compressed HDF5, 4 == N5

		public MyActionListener( final int index )
		{
			this.index = index;
		}

		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + this.getClass().getSimpleName() );
				return;
			}

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					final SpimData2 data = panel.getSpimData();

					final List< ViewId > viewIds = ApplyTransformationPopup.getSelectedViews( panel );
					final boolean notAllSelected = viewIds.size() < data.getSequenceDescription().getViewDescriptions().size();

					// user has not selected all views (or not all views are selectable at once)
					// ask them whether they want to expand selection to all views in SpimData
					if ( notAllSelected )
					{
						final int choice = JOptionPane.showConfirmDialog( null,
								"You have only selected " + viewIds.size() + " of " +
								data.getSequenceDescription().getViewDescriptions().size() + " views for export.\n" +
								"Do you wish to expand the selection to the whole dataset before continuing?",
								"Warning",
								JOptionPane.YES_NO_CANCEL_OPTION );
						
						if (choice == JOptionPane.CANCEL_OPTION)
						{
							return;
						}
						else if (choice == JOptionPane.YES_OPTION)
						{
							IOFunctions.println( "OK, saving ALL " + data.getSequenceDescription().getViewDescriptions().size() + "views." );
							viewIds.clear();
							viewIds.addAll( data.getSequenceDescription().getViewDescriptions().keySet() );
						}
						else
						{
							IOFunctions.println( "Saving " + viewIds.size() + " of " + data.getSequenceDescription().getViewDescriptions().size() + " views.");
						}
					}

					// filter not present ViewIds
					final List< ViewId > removed = SpimData2.filterMissingViews( panel.getSpimData(), viewIds );
					if ( removed.size() > 0 ) IOFunctions.println( "(" + new Date( System.currentTimeMillis()) + "): Removed " + removed.size() + " missing views from the list before saving." );

					final ProgressWriter progressWriter = new ProgressWriterIJ();
					progressWriter.out().println( "Resaving " + viewIds.size() + " views " + types[ index ] );

					if ( index < 2 ) // TIFF, compressed tiff
					{
						if ( !URITools.isFile( panel.xml() ) )
						{
							IOFunctions.println( "Intrinsic URI '" + panel.xml() + "' is not on a local file system. Re-saving as TIFF only works on locally mounted file systems. Please use the explicit Re-save plugin for more options." );
							return;
						}

						panel.saveXML();

						final ParametersResaveAsTIFF params = new ParametersResaveAsTIFF();
						params.compress = index != 0;
						params.xmlPath = panel.xml();

						// write the TIFF's
						Resave_TIFF.writeTIFF( data, viewIds, new File( URITools.fromURI( params.getXMLPath() ) ).getParent(), params.compress, progressWriter );
	
						// write the XML
						final SpimData2 newSpimData = Resave_TIFF.createXMLObject( data, viewIds, params );
						progressWriter.setProgress( 1.01 );

						// copy the interest points is not necessary as we overwrite the XML if they exist
						// Resave_TIFF.copyInterestPoints( data.getBasePath(), new File( params.xmlFile ).getParentFile(), result.getB() );

						// replace the spimdata object
						panel.setSpimData( newSpimData );
						panel.updateContent();
						panel.saveXML();
					}
					else if ( index == 2 || index == 3 ) // HDF5, compressed HDF5
					{
						if ( !URITools.isFile( panel.xml() ) )
						{
							IOFunctions.println( "Intrinsic URI '" + panel.xml() + "' is not on a local file system. Re-saving to HDF5 only works on locally mounted file systems. Please use the explicit Re-save plugin for more options." );
							return;
						}

						final List< ViewSetup > setups = SpimData2.getAllViewSetupsSorted( data, viewIds );

						// load all dimensions if they are not known (required for estimating the mipmap layout)
						Resave_HDF5.loadDimensions( data, setups );

						//panel.saveXML();

						final Map< Integer, ExportMipmapInfo > perSetupExportMipmapInfo = Resave_HDF5.proposeMipmaps( setups );
						final int firstviewSetupId = setups.get( 0 ).getId();
						final ExportMipmapInfo autoMipmapSettings = perSetupExportMipmapInfo.get( firstviewSetupId );

						final boolean compress = (index != 2);

						String hdf5Filename = null;
						File hdf5File;
						int i = 1;

						String xml = URITools.fromURI( panel.xml() );

						do
						{
							if ( hdf5Filename == null )
								hdf5Filename = xml.substring( 0, xml.length() - 4 ) + ".h5";
							else
								hdf5Filename = xml.substring( 0, xml.length() - 4 ) + ".v" + (i++) + ".h5";

							hdf5File = new File( hdf5Filename );
	
							if ( hdf5File.exists() )
								IOFunctions.println( "HDF5 file exists, choosing a different name: " + hdf5File.getAbsolutePath() + ". Please do not forget to the delete the old file if you want." );

						} while ( hdf5File.exists() );

						IOFunctions.println( "HDF5 file: " + hdf5File.getAbsolutePath() );

						final Generic_Resave_HDF5.ParametersResaveHDF5 params =
								new Generic_Resave_HDF5.ParametersResaveHDF5(
										false,
										autoMipmapSettings.getExportResolutions(),
										autoMipmapSettings.getSubdivisions(),
										new File( panel.xml() ),
										hdf5File,
										compress,
										false,
										1,
										0,
										false,
										0,
										0, Double.NaN, Double.NaN );

						// write hdf5
						Generic_Resave_HDF5.writeHDF5( SpimData2Tools.reduceSpimData2( data, viewIds ), params, progressWriter );

						final SpimData2 newSpimData = Resave_HDF5.createXMLObject( data, viewIds, params, progressWriter, true );

						// copy the interest points is not necessary as we overwrite the XML if they exist
						// Resave_TIFF.copyInterestPoints( xml.getData().getBasePath(), params.getSeqFile().getParentFile(), result.getB() );

						// replace the spimdata object
						panel.setSpimData( newSpimData );
						panel.updateContent();

						progressWriter.setProgress( 1.0 );
						panel.saveXML();
						progressWriter.out().println( "done" );
					}

					// --- N5 / OME-ZARR ---
					else if (index == 4 || index == 5 || index == 6 || index == 7 ) // 4 == in-place, 5 == choose path N5, 6 == in-place OME-ZARR, 7 == choose path OME-ZARR
					{
						//panel.saveXML();

						final URI n5DatasetURI = (index <= 5) ? 
								ParametersResaveN5Api.createN5URIfromXMLURI( panel.xml() ) : ParametersResaveN5Api.createOMEZARRURIfromXMLURI( panel.xml() ) ;

						StorageFormat format;

						// if you want ZARR v2, you need to go through the full options dialog
						if (index <= 5)
							format = StorageFormat.N5;
						else
							format = StorageFormat.ZARR;

						final ParametersResaveN5Api n5params = ParametersResaveN5Api.getParamtersIJ(
								panel.xml(),
								n5DatasetURI,
								viewIds.stream().map( vid -> data.getSequenceDescription().getViewSetups().get( vid.getViewSetupId() ) ).collect( Collectors.toSet() ),
								format, // do not ask for format (for now)
								index == 5 || index == 7 );

						if ( n5params == null )
							return;

						final URI basePathURI;

						if ( ( index == 5 || index == 7 ) && !n5params.xmlURI.equals( panel.xml() ) )
						{
							IOFunctions.println( "New location for XML selected: " + n5params.xmlURI );
							basePathURI = URITools.getParentURINoEx( n5params.xmlURI );
						}
						else
						{
							basePathURI = data.getBasePathURI();
						}

						final SpimData2 newSpimData = Resave_N5Api.resaveN5( data, viewIds, n5params, false, panel.xml() );

						// make sure interestpoints are saved to the new location as well
						if ( (index == 5 || index ==7) && !n5params.xmlURI.equals( panel.xml() ) )
						{
							for ( final ViewInterestPointLists vipl : data.getViewInterestPoints().getViewInterestPoints().values() )
								vipl.getHashMap().values().forEach( ipl ->
								{
									try
									{
										ipl.getInterestPointsCopy();
										ipl.getCorrespondingInterestPointsCopy();
										ipl.setBasePath( basePathURI ); // also sets 'isModified' flags
									}
									catch ( Exception e )
									{
										IOFunctions.println( "Could not load interest points for (trying to skip): " + Group.pvid( vipl ) + ", " + ipl.getXMLRepresentation()  );
									}
								});

							panel.xml = n5params.xmlURI;
							panel.xmlLabel.setText( "XML: " + n5params.xmlURI );
						}

						// replace the spimdata object
						panel.setSpimData( newSpimData );
						panel.updateContent();

						// save and finish progress
						progressWriter.setProgress( 1.0 );
						panel.saveXML();
						progressWriter.out().println( "done" );
					}

					// re-open BDV if active
					final BDVPopup bdvPop = ( panel.bdvPopup() instanceof BDVPopup ) ? (BDVPopup) panel.bdvPopup() : null;
					if ( bdvPop != null && bdvPop.bdvRunning() )
						bdvPop.reStartBDV();
				}
			} ).start();
		}
	}
}
