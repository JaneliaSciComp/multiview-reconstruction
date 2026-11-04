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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.swing.JMenu;
import javax.swing.JMenuItem;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;

import fiji.util.gui.GenericDialogPlus;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Intensity_Adjustment;
import net.preibisch.mvrecon.fiji.plugin.RemoveIntensity_Adjustment;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrection;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionViewer;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityPreview;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.URITools;

/**
 * Intensity correction of the selected views: compute coefficients, list, remove, or import them from
 * BigStitcher-Spark (they are saved with the XML); preview a coefficients container in BigDataViewer.
 */
public class IntensityAdjustmentPopup extends JMenu implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	public static String defaultImportPath = "";

	// remembered between invocations of the preview dialog
	public static String defaultCoefficientsPath = "";
	public static boolean defaultOnlyAboveThreshold = false;

	ExplorerWindow< ? > panel;

	public IntensityAdjustmentPopup()
	{
		super( "Intensity Adjustment" );

		add( item( "Compute ...", this::compute ) );
		add( item( "List all", this::list ) );
		add( item( "Remove", this::remove ) );
		addSeparator();
		add( item( "Import coefficients (BigStitcher-Spark) ...", this::importCoefficients ) );
		add( item( "Preview Intensity Correction Coefficients ...", ( data, selected ) -> previewCoefficients( panel, data ) ) );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	private JMenuItem item( final String name, final Action action )
	{
		final JMenuItem item = new JMenuItem( name );
		item.addActionListener( ( final ActionEvent e ) -> {
			if ( panel == null )
			{
				IOFunctions.println( "Panel not set for " + getClass().getSimpleName() );
				return;
			}
			if ( !( panel.getSpimData() instanceof SpimData2 ) )
			{
				IOFunctions.println( "Only supported for SpimData2 objects: " + getClass().getSimpleName() );
				return;
			}
			new Thread( () -> action.run( ( SpimData2 ) panel.getSpimData(), ApplyTransformationPopup.getSelectedViews( panel ) ) ).start();
		} );
		return item;
	}

	private interface Action { void run( SpimData2 data, List< ViewId > selected ); }

	private void compute( final SpimData2 data, final List< ViewId > selected )
	{
		if ( new Intensity_Adjustment().intensityAdjustment( data, selected ) )
			panel.updateContent();
	}

	private void list( final SpimData2 data, final List< ViewId > selected )
	{
		final Map< ViewId, Coefficients > coefficients = data.getIntensityAdjustments().getCoefficients();
		if ( coefficients.isEmpty() )
		{
			IOFunctions.println( "Intensity correction: none defined." );
			return;
		}
		final ArrayList< ViewId > views = new ArrayList<>( coefficients.keySet() );
		Collections.sort( views );
		IOFunctions.println( "Intensity correction coefficients (" + views.size() + " views):" );
		for ( final ViewId v : views )
			IOFunctions.println( Group.pvid( v ) + ": " + IntensityCorrection.describe( coefficients.get( v ) ) );
	}

	private void remove( final SpimData2 data, final List< ViewId > selected )
	{
		new RemoveIntensity_Adjustment().removeAdjustment( data, selected );
		panel.updateContent();
	}

	/** reads coefficients written by BigStitcher-Spark's solve-intensities into the dataset */
	private void importCoefficients( final SpimData2 data, final List< ViewId > selected )
	{
		final GenericDialogPlus gd = new GenericDialogPlus( "Import Intensity Correction Coefficients" );
		gd.addDirectoryOrFileField( "Coefficients_container", defaultImportPath, 50 );
		gd.addMessage( "From solve-intensities on this dataset (not a split version of it); format guessed from the ending.\n"
				+ "Replaces the coefficients of the views it has; they are saved next to the XML on the next save." );
		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		final String path = defaultImportPath = gd.getNextString().trim();
		final StorageFormat format = IntensityCorrectionViewer.guessFormat( path );
		if ( format == null )
		{
			IOFunctions.println( "Intensity correction: cannot guess the storage format of '" + path + "' (expected .n5, .zarr, .h5 or .hdf5)." );
			return;
		}

		final IntensityAdjustments imported = new IntensityAdjustments();
		imported.setContainer( URITools.toURI( path ), format, data.getSequenceDescription().getViewDescriptions().keySet() );
		final Map< ViewId, Coefficients > coefficients = imported.getCoefficients();
		if ( coefficients.isEmpty() )
		{
			IOFunctions.println( "Intensity correction: no coefficients found in " + path + " (expected setup<s>/timepoint<t>/intensity datasets)." );
			return;
		}

		data.getIntensityAdjustments().putAll( coefficients );
		IOFunctions.println( "Imported intensity correction coefficients for " + coefficients.size() + " views." );
		panel.updateContent();
	}

	/**
	 * Asks for an intensity-correction coefficients container (as written by BigStitcher-Spark's
	 * {@code solve-intensities}) and shows the correction in the explorer's BigDataViewer, every voxel from the
	 * closest view as fused with {@code CLOSEST_PIXEL_WINS}. Key 'i' in the explorer toggles it.
	 */
	public static void previewCoefficients( final ExplorerWindow< ? > panel, final SpimData2 sd )
	{
		final GenericDialogPlus gd = new GenericDialogPlus( "Preview Intensity Correction Coefficients" );
		gd.addDirectoryOrFileField( "Coefficients_container", defaultCoefficientsPath, 50 );
		gd.addCheckbox( "Correct_only_above_stored_threshold (as fusion with --intensityMaskBelowThreshold)", defaultOnlyAboveThreshold );
		gd.addMessage( "Coefficients from solve-intensities on this dataset (not a split version of it); format guessed from the ending.\n"
				+ "Views without coefficients stay uncorrected. Press 'i' in the explorer to toggle the preview." );

		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		final String path = defaultCoefficientsPath = gd.getNextString().trim();
		final boolean onlyAboveThreshold = defaultOnlyAboveThreshold = gd.getNextBoolean();

		final StorageFormat format = IntensityCorrectionViewer.guessFormat( path );
		if ( format == null )
		{
			IOFunctions.println( "Intensity preview: cannot guess the storage format of '" + path + "' (expected .n5, .zarr, .h5 or .hdf5)." );
			return;
		}

		final int numSetups;
		try
		{
			IOFunctions.println( "Intensity preview: loading coefficients from " + path + " (" + format + ") ..." );
			numSetups = IntensityPreview.configure( sd, URITools.instantiateN5Reader( format, URITools.toURI( path ) ), "", "intensity", onlyAboveThreshold );
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Intensity preview: could not load coefficients from '" + path + "': " + e );
			e.printStackTrace();
			return;
		}

		IOFunctions.println( "Intensity preview: coefficients for " + numSetups + " of " + sd.getSequenceDescription().getViewSetupsOrdered().size()
				+ " setups" + ( onlyAboveThreshold ? ", correcting only above each view's stored threshold" : "" )
				+ ". Every voxel is shown from the closest view (CLOSEST_PIXEL_WINS). Press 'i' to toggle." );
		if ( numSetups == 0 )
			IOFunctions.println( "Intensity preview: WARNING, no coefficients found (expected setup<s>/timepoint<t>/intensity datasets in " + path + ")." );

		showPreviewState( panel, true );
	}

	/** Repaints the explorer's BigDataViewer, if open, and says whether the intensity preview is on. */
	public static void showPreviewState( final ExplorerWindow< ? > panel, final boolean on )
	{
		final String message = "Intensity correction preview " + ( on ? "ON" : "OFF" );
		final BasicBDVPopup p = panel.runningBdvPopup();
		if ( p != null && p.getBDV() != null && p.getBDV().getViewerFrame().isVisible() )
		{
			p.getBDV().getViewer().showMessage( message );
			p.getBDV().getViewer().requestRepaint();
		}
		else
			IOFunctions.println( message + " (it shows in BigDataViewer once that is open)." );
	}
}
