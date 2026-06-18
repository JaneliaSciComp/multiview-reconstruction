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
package net.preibisch.mvrecon.fiji.spimdata.explorer.popup;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

import bdv.img.n5.N5ImageLoader;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.explorer.FilteredAndGroupedExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorerPanel;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondenceTools;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;

/**
 * Submenu for managing missing views: mark as missing, mark as present, or mark as missing with metadata removal.
 */
public class MissingViewsPopup extends JMenu implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	ExplorerWindow< ? > panel = null;

	private final JMenuItem markMissing;
	private final JMenuItem markPresent;
	private final JMenuItem markMissingRemoveMetadata;

	public MissingViewsPopup()
	{
		super( "Missing Views" );

		markMissing = new JMenuItem( "Mark Selected Views as Missing" );
		markPresent = new JMenuItem( "Mark Selected Views as Present" );
		markMissingRemoveMetadata = new JMenuItem( "Mark as Missing and Remove Metadata" );

		markMissing.addActionListener( new MarkMissingListener() );
		markPresent.addActionListener( new MarkPresentListener() );
		markMissingRemoveMetadata.addActionListener( new MarkMissingRemoveMetadataListener() );

		this.add( markMissing );
		this.add( markPresent );
		this.addSeparator();
		this.add( markMissingRemoveMetadata );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	/**
	 * Helper method to set missing views via reflection.
	 */
	private boolean setMissingViews( final SequenceDescription seq, final MissingViews newMissingViews )
	{
		try
		{
			final Method setMissingViews = AbstractSequenceDescription.class.getDeclaredMethod(
					"setMissingViews", MissingViews.class );
			setMissingViews.setAccessible( true );
			setMissingViews.invoke( seq, newMissingViews );
			return true;
		}
		catch ( final Exception ex )
		{
			IOFunctions.println( "Failed to set missing views: " + ex.getMessage() );
			ex.printStackTrace();
			return false;
		}
	}

	/**
	 * Helper method to refresh the UI after changes.
	 */
	private void refreshUI()
	{
		// Update the table display
		if ( panel instanceof FilteredAndGroupedExplorerPanel )
		{
			final FilteredAndGroupedExplorerPanel< ? > fgPanel = (FilteredAndGroupedExplorerPanel< ? >) panel;
			fgPanel.getTableModel().updateElements();
		}

		// Notify listeners
		panel.updateContent();

		// Update the "Hide Missing Views" checkbox and restart BDV if open
		if ( panel instanceof ViewSetupExplorerPanel )
		{
			final ViewSetupExplorerPanel< ? > vsPanel = (ViewSetupExplorerPanel< ? >) panel;

			// Update checkbox enabled state
			vsPanel.updateHideMissingViewsCheckbox();

			if ( vsPanel.bdvPopup() != null && vsPanel.bdvPopup().bdvRunning() )
			{
				IOFunctions.println( "Restarting BDV to reflect changes..." );
				((BDVPopup) vsPanel.bdvPopup()).reStartBDV();
			}
		}
	}

	/**
	 * Listener for "Mark Selected Views as Missing"
	 */
	public class MarkMissingListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set." );
				return;
			}

			final List< ViewId > selectedViews = ApplyTransformationPopup.getSelectedViews( panel, false );
			if ( selectedViews.isEmpty() )
			{
				IOFunctions.println( "No views selected." );
				return;
			}

			final SequenceDescription seq = (SequenceDescription) panel.getSpimData().getSequenceDescription();

			// Get current missing views
			final Set< ViewId > newMissingSet = new HashSet<>();
			if ( seq.getMissingViews() != null && seq.getMissingViews().getMissingViews() != null )
			{
				newMissingSet.addAll( seq.getMissingViews().getMissingViews() );
			}

			// Add selected views to missing set
			int added = 0;
			for ( final ViewId viewId : selectedViews )
			{
				boolean alreadyMissing = false;
				for ( final ViewId existing : newMissingSet )
				{
					if ( existing.getTimePointId() == viewId.getTimePointId() &&
						 existing.getViewSetupId() == viewId.getViewSetupId() )
					{
						alreadyMissing = true;
						break;
					}
				}
				if ( !alreadyMissing )
				{
					newMissingSet.add( viewId );
					added++;
				}
			}

			if ( added == 0 )
			{
				IOFunctions.println( "All selected views are already marked as missing." );
				return;
			}

			final MissingViews newMissingViews = new MissingViews( new ArrayList<>( newMissingSet ) );
			if ( !setMissingViews( seq, newMissingViews ) )
				return;

			IOFunctions.println( "Marked " + added + " view(s) as missing." );
			refreshUI();
			IOFunctions.println( "Remember to save the XML to persist changes." );
		}
	}

	/**
	 * Listener for "Mark Selected Views as Present"
	 */
	public class MarkPresentListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set." );
				return;
			}

			final List< ViewId > selectedViews = ApplyTransformationPopup.getSelectedViews( panel, false );
			if ( selectedViews.isEmpty() )
			{
				IOFunctions.println( "No views selected." );
				return;
			}

			final SequenceDescription seq = (SequenceDescription) panel.getSpimData().getSequenceDescription();

			if ( seq.getMissingViews() == null || seq.getMissingViews().getMissingViews() == null ||
				 seq.getMissingViews().getMissingViews().isEmpty() )
			{
				IOFunctions.println( "No views are currently marked as missing." );
				return;
			}

			final Set< ViewId > currentMissing = new HashSet<>( seq.getMissingViews().getMissingViews() );

			// Remove selected views from missing set
			int removed = 0;
			for ( final ViewId viewId : selectedViews )
			{
				ViewId toRemove = null;
				for ( final ViewId existing : currentMissing )
				{
					if ( existing.getTimePointId() == viewId.getTimePointId() &&
						 existing.getViewSetupId() == viewId.getViewSetupId() )
					{
						toRemove = existing;
						break;
					}
				}
				if ( toRemove != null )
				{
					currentMissing.remove( toRemove );
					removed++;
				}
			}

			if ( removed == 0 )
			{
				IOFunctions.println( "None of the selected views were marked as missing." );
				return;
			}

			final MissingViews newMissingViews = currentMissing.isEmpty() ?
					null : new MissingViews( new ArrayList<>( currentMissing ) );

			if ( !setMissingViews( seq, newMissingViews ) )
				return;

			IOFunctions.println( "Marked " + removed + " view(s) as present." );
			refreshUI();
			IOFunctions.println( "Remember to save the XML to persist changes." );
		}
	}

	/**
	 * Listener for "Mark as Missing and Remove Metadata"
	 */
	public class MarkMissingRemoveMetadataListener implements ActionListener
	{
		@Override
		public void actionPerformed( final ActionEvent e )
		{
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set." );
				return;
			}

			final List< ViewId > selectedViews = ApplyTransformationPopup.getSelectedViews( panel, false );
			if ( selectedViews.isEmpty() )
			{
				IOFunctions.println( "No views selected." );
				return;
			}

			// Confirm
			final int result = JOptionPane.showConfirmDialog(
					null,
					"Mark " + selectedViews.size() + " selected view(s) as missing and remove metadata?\n\n" +
					"This will:\n" +
					"- Mark views as missing\n" +
					"- Delete all interest points for these views\n" +
					"- Delete PSF entries for these views\n\n" +
					"Raw image files (N5/ZARR) will NOT be deleted automatically.\n" +
					"File paths will be listed in the log for manual deletion.",
					"Confirm",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE );

			if ( result != JOptionPane.YES_OPTION )
				return;

			final SpimData2 data = panel.getSpimData();
			final SequenceDescription seq = (SequenceDescription) data.getSequenceDescription();

			// 1. Mark views as missing
			final Set< ViewId > newMissingSet = new HashSet<>();
			if ( seq.getMissingViews() != null && seq.getMissingViews().getMissingViews() != null )
			{
				newMissingSet.addAll( seq.getMissingViews().getMissingViews() );
			}

			int markedMissing = 0;
			for ( final ViewId viewId : selectedViews )
			{
				boolean alreadyMissing = false;
				for ( final ViewId existing : newMissingSet )
				{
					if ( existing.getTimePointId() == viewId.getTimePointId() &&
						 existing.getViewSetupId() == viewId.getViewSetupId() )
					{
						alreadyMissing = true;
						break;
					}
				}
				if ( !alreadyMissing )
				{
					newMissingSet.add( viewId );
					markedMissing++;
				}
			}

			final MissingViews newMissingViews = new MissingViews( new ArrayList<>( newMissingSet ) );
			if ( !setMissingViews( seq, newMissingViews ) )
				return;

			IOFunctions.println( "Marked " + markedMissing + " view(s) as missing." );

			// 2a. Remove symmetric correspondences from other views (multithreaded)
			final Set< ViewId > viewsToRemove = new HashSet<>( selectedViews );
			final int removedCorr = CorrespondenceTools.removeCorrespondencesToViews(
					data.getViewInterestPoints(), viewsToRemove,
					Runtime.getRuntime().availableProcessors() );
			IOFunctions.println( "Removed " + removedCorr + " corresponding interest point entries from other views." );

			// 2b. Delete interest points for selected views
			int deletedIP = 0;
			for ( final ViewId viewId : selectedViews )
			{
				final ViewInterestPointLists vipl = data.getViewInterestPoints().getViewInterestPointLists( viewId );
				if ( vipl != null && vipl.getHashMap() != null && !vipl.getHashMap().isEmpty() )
				{
					final int count = vipl.getHashMap().size();
					vipl.getHashMap().clear();
					deletedIP += count;
				}
			}
			IOFunctions.println( "Deleted " + deletedIP + " interest point list(s)." );

			// 3. Delete PSF entries
			int deletedPSF = 0;
			final PointSpreadFunctions psfs = data.getPointSpreadFunctions();
			if ( psfs != null && psfs.getPointSpreadFunctions() != null )
			{
				for ( final ViewId viewId : selectedViews )
				{
					ViewId toRemove = null;
					for ( final ViewId existing : psfs.getPointSpreadFunctions().keySet() )
					{
						if ( existing.getTimePointId() == viewId.getTimePointId() &&
							 existing.getViewSetupId() == viewId.getViewSetupId() )
						{
							toRemove = existing;
							break;
						}
					}
					if ( toRemove != null )
					{
						psfs.getPointSpreadFunctions().remove( toRemove );
						deletedPSF++;
					}
				}
			}
			IOFunctions.println( "Deleted " + deletedPSF + " PSF entry(ies)." );

			// 4. List N5/ZARR file paths
			listImageFilePaths( data, selectedViews );

			refreshUI();
			IOFunctions.println( "\nDone. Remember to save the XML to persist changes." );
		}

		/**
		 * Lists the image file paths for N5/ZARR-based loaders.
		 */
		private void listImageFilePaths( final SpimData2 data, final List< ViewId > viewIds )
		{
			final BasicImgLoader imgLoader = data.getSequenceDescription().getImgLoader();

			// Handle AllenOMEZarrLoader (ZARR)
			if ( imgLoader instanceof AllenOMEZarrLoader )
			{
				final AllenOMEZarrLoader zarrLoader = (AllenOMEZarrLoader) imgLoader;
				final Map< ViewId, OMEZARREntry > viewIdToPath = zarrLoader.getViewIdToPath();
				final URI baseURI = zarrLoader.getN5URI();

				final Set< String > paths = new TreeSet<>();

				for ( final ViewId viewId : viewIds )
				{
					for ( final Map.Entry< ViewId, OMEZARREntry > entry : viewIdToPath.entrySet() )
					{
						if ( entry.getKey().getTimePointId() == viewId.getTimePointId() &&
							 entry.getKey().getViewSetupId() == viewId.getViewSetupId() )
						{
							paths.add( entry.getValue().getPath() );
							break;
						}
					}
				}

				if ( !paths.isEmpty() )
				{
					IOFunctions.println( "\n=== ZARR file paths that could be deleted ===" );
					IOFunctions.println( "Base URI: " + baseURI );
					IOFunctions.println( "Relative paths:" );
					for ( final String path : paths )
					{
						IOFunctions.println( "  " + path );
					}
					IOFunctions.println( "WARNING: These files are NOT automatically deleted." );
					IOFunctions.println( "You must delete them manually if desired." );
				}
			}
			// Handle N5ImageLoader
			else if ( imgLoader instanceof N5ImageLoader )
			{
				final N5ImageLoader n5Loader = (N5ImageLoader) imgLoader;
				final URI baseURI = n5Loader.getN5URI();

				IOFunctions.println( "\n=== N5 file paths ===" );
				IOFunctions.println( "Base URI: " + baseURI );
				IOFunctions.println( "N5 datasets for views:" );

				for ( final ViewId viewId : viewIds )
				{
					IOFunctions.println( "  setup" + viewId.getViewSetupId() + "/timepoint" + viewId.getTimePointId() );
				}

				IOFunctions.println( "WARNING: These files are NOT automatically deleted." );
				IOFunctions.println( "You must delete them manually if desired." );
			}
			else
			{
				IOFunctions.println( "\nImage loader type: " + imgLoader.getClass().getSimpleName() );
				IOFunctions.println( "File path listing not supported for this loader type." );
			}
		}
	}
}
