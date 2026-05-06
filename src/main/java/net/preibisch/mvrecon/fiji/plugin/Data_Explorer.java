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
package net.preibisch.mvrecon.fiji.plugin;

import java.awt.Button;
import java.awt.TextField;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URI;

import net.preibisch.mvrecon.fiji.plugin.queryXML.GenericLoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.SimpleInfoBox;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ViewSetupExplorer;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.fiji.spimdata.explorer.popup.BDVPopup;
import net.preibisch.mvrecon.process.interestpointregistration.global.pointmatchcreating.strong.InterestPointMatchCreator;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;

import ij.ImageJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;

public class Data_Explorer implements PlugIn
{
	public static boolean showNote = false;

	/** View-count threshold above which the "open BDV?" warning dialog appears. */
	private static final int LARGE_DATASET_THRESHOLD = 100;

	/** View-count threshold above which the "open BDV?" warning dialog appears. */
	private static final int LARGE_DATASET_DISABLE_BDV_THRESHOLD = 10_000;

	/** View-count threshold above which the "Use lazy BDV mode" checkbox defaults to checked. */
	private static final int LARGE_DATASET_LAZY_RECOMMEND_THRESHOLD = 1_000;

	@Override
	public void run( String arg )
	{
		if ( showNote )
		{
			showNote();
			showNote = false;
		}

		final LoadParseQueryXML result = new LoadParseQueryXML();

		result.addButton( "Define a new dataset", new ActionListener()
		{
			@Override
			public void actionPerformed(ActionEvent e)
			{
				((TextField)result.getGenericDialog().getStringFields().firstElement()).setText( "define" );
				Button ok = result.getGenericDialog().getButtons()[ 0 ];

				ActionEvent ae =  new ActionEvent( ok, ActionEvent.ACTION_PERFORMED, "");
				Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(ae);
			}
		});

		result.addCheckbox( "Show_advanced_options" );

		if ( !result.queryXML( "XML Explorer", "", false, false, false, false, false ) )
			return;

		final boolean advanced = result.isCheckBoxSelected();

		long start = System.currentTimeMillis();
		final SpimData2 data = result.getData();
		final URI xml = result.getXMLURI();
		final XmlIoSpimData2 io = result.getIO();
		net.preibisch.legacy.io.IOFunctions.println( "PERF: [Data_Explorer] getData/XML/IO took " + (System.currentTimeMillis() - start) + " ms" );

		// Warn before unconditionally opening BDV on very large datasets — it's slow and often
		// not what the user wants. Below the threshold, behavior is unchanged (BDV opens).
		boolean openBDV = true;
		final int totalViews = data.getSequenceDescription().getViewSetups().size() * data.getSequenceDescription().getTimePoints().size();
		if ( advanced || totalViews > LARGE_DATASET_THRESHOLD )
		{
			final GenericDialog gd = new GenericDialog( "Large dataset" );
			gd.addMessage( "This dataset has " + totalViews + " views. Opening BigDataViewer for many views can be slow." );
			gd.addCheckbox( "Open_BigDataViewer_at_startup", totalViews < LARGE_DATASET_DISABLE_BDV_THRESHOLD );
			gd.addCheckbox( "Use_lazy_BDV_mode (adds & removes views when selected)", totalViews >= LARGE_DATASET_LAZY_RECOMMEND_THRESHOLD );
			gd.addMessage( "Alignment log settings:", GUIHelper.mediumstatusfont );
			gd.addNumericField( "Max_per-pair_connection_log_lines", InterestPointMatchCreator.maxPerPairLog, 0 );
			gd.addNumericField( "Max_per-pair_correspondence-load_log_lines", PairwiseResult.maxPerPairCorrLog, 0 );
			gd.addNumericField( "Max_per-view_transformation_log_lines", TransformationTools.maxPerViewTransformLog, 0 );
			gd.showDialog();
			if ( gd.wasCanceled() )
				return;
			openBDV = gd.getNextBoolean();
			BDVPopup.useLazyMode = gd.getNextBoolean();
			InterestPointMatchCreator.maxPerPairLog = Math.max( 0, ( int ) Math.round( gd.getNextNumber() ) );
			PairwiseResult.maxPerPairCorrLog = Math.max( 0, ( int ) Math.round( gd.getNextNumber() ) );
			TransformationTools.maxPerViewTransformLog = Math.max( 0, ( int ) Math.round( gd.getNextNumber() ) );
		}

		start = System.currentTimeMillis();
		final ViewSetupExplorer< SpimData2 > explorer = new ViewSetupExplorer<>( data, xml, io, openBDV );
		net.preibisch.legacy.io.IOFunctions.println( "PERF: [Data_Explorer] ViewSetupExplorer creation took " + (System.currentTimeMillis() - start) + " ms" );

		explorer.getFrame().toFront();
	}

	public static SimpleInfoBox showNote()
	{
		String text = "Welcome to the Multiview Reconstruction Software!\n\n";

		text += "Here are a few tips & tricks that hopefully get you started. The first thing you should do is to\n";
		text += "have a look at the online documentation, which is growing (http://fiji.sc/Multiview-Reconstruction).\n\n";

		text += "For newcomers, the basic steps you need to do are the following:\n";
		text += "1) Define a new dataset in the open dialog, which will create the XML and open an explorer window\n";
		text += "2) Select one of the views and make sure it displays right in ImageJ\n";
		text += "3) Consider converting your dataset to HDF5, as it makes it possible to use the BigDataViewer\n" + 
				"to browse the entire dataset interactively\n";
		text += "4) Detect interest points in your views (could be beads, nuclei, ...)\n";
		text += "5) Register your data using those interest points (rotation invariant)\n";
		text += "6) Fuse or deconvolve the dataset\n";
		text += "\n";

		text += "Please note that the outlined steps above should work out of the box if you have fluoresecent beads\n";
		text += "surrounding your sample. If you want to use sample features like nuclei, you need to apply approximate\n";
		text += "transformations first (known rotation axis & angles) and register using translation-invariant matching.\n";
		text += "\n";
		text += "Tip: If you get too many detections inside the sample and you just want to find beads, you can remove\n";
		text += "them based on their distance to each other (Remove Interest Points > By Distance ...) - remove all that\n";
		text += "too close to each other (e.g. less than 5 pixels)\n";

		return new SimpleInfoBox( "Getting started", text );
	}

	public static void main( String[] args )
	{
		new ImageJ();

		if ( System.getProperty("os.name").toLowerCase().contains( "mac" ) )
			GenericLoadParseQueryXML.defaultXMLURI = "/Users/preibischs/SparkTest/Stitching/dataset.xml";

		new Data_Explorer().run( null );
	}
}
