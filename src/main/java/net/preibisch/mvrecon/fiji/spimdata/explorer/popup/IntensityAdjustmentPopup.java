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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
import net.preibisch.mvrecon.process.fusion.intensity.IntensityCorrectionViewer;
import net.preibisch.mvrecon.process.fusion.intensity.IntensityPreview;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import util.URITools;

public class IntensityAdjustmentPopup extends JMenu implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 1L;

	ExplorerWindow< ? > panel;

	protected static String[] types = new String[]{ "Compute ...", "List all", "Remove", "Preview Intensity Correction Coefficients ..." };

	// remembered between invocations of the preview dialog
	public static String defaultCoefficientsPath = "";
	public static boolean defaultOnlyAboveThreshold = false;

	public IntensityAdjustmentPopup()
	{
		super( "Intensity Adjustment" );

		final JMenuItem compute = new JMenuItem( types[ 0 ] );
		final JMenuItem list = new JMenuItem( types[ 1 ] );
		final JMenuItem remove = new JMenuItem( types[ 2 ] );
		final JMenuItem preview = new JMenuItem( types[ 3 ] );

		compute.addActionListener( new MyActionListener( 0 ) );
		list.addActionListener( new MyActionListener( 1 ) );
		remove.addActionListener( new MyActionListener( 2 ) );
		preview.addActionListener( new MyActionListener( 3 ) );

		this.add( compute );
		this.add( list );
		this.add( remove );
		this.addSeparator();
		this.add( preview );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	public class MyActionListener implements ActionListener
	{
		final int index; // 0 == Compute, 1 == List, 2 == Remove, 3 == Preview coefficients

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

			if ( !SpimData2.class.isInstance( panel.getSpimData() ) )
			{
				IOFunctions.println( "Only supported for SpimData2 objects: " + this.getClass().getSimpleName() );
				return;
			}

			final SpimData2 sd = (SpimData2)panel.getSpimData();

			new Thread( new Runnable()
			{
				@Override
				public void run()
				{
					final List< ViewId > viewIds = ApplyTransformationPopup.getSelectedViews( panel );

					if ( index == 0 )
						new Intensity_Adjustment().intensityAdjustment( sd, viewIds );
					else if ( index == 1 )
					{
						String text = "Intensity Adjustments:\n";

						if ( sd.getIntensityAdjustments().getIntensityAdjustments().size() == 0 )
						{
							text += "None defined\n";
						}
						else
						{
							final ArrayList< ViewId > views = new ArrayList<>( sd.getIntensityAdjustments().getIntensityAdjustments().keySet() );
							Collections.sort( views );
							for ( final ViewId v : views )
								text += Group.pvid( v ) + ": " + sd.getIntensityAdjustments().getIntensityAdjustments().get( v ) + "\n";
						}

						IOFunctions.println( text );
					}
					else if ( index == 2 )
						new RemoveIntensity_Adjustment().removeAdjustment( sd, viewIds );
					else
						previewCoefficients( panel, sd );
				}
			} ).start();
		}
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
