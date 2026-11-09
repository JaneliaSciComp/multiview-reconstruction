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

import java.awt.Dimension;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import javax.swing.JComponent;
import javax.swing.JMenuItem;

import fiji.util.gui.GenericDialogPlus;
import ij.gui.GenericDialog;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.Threads;
import net.preibisch.mvrecon.process.flatfield.Basic;
import net.preibisch.mvrecon.process.flatfield.FlatfieldEstimation;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.DefaultFlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.FlatfieldCorrectionWrappedImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.FlatfieldImageInfo;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.LazyLoadingFlatFieldCorrectionMap;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield.MultiResolutionFlatfieldCorrectionWrappedImgLoader;

public class FlatFieldCorrectionPopup extends JMenuItem implements ExplorerWindowSetable
{

	private static final long serialVersionUID = 950277697000203629L;
	private ExplorerWindow< ? > panel;

	public FlatFieldCorrectionPopup()
	{
		super( "Flatfield Correction (experimental) ..." );
		addActionListener( new MyActionListener() );
	}

	@Override
	public JComponent setExplorerWindow( ExplorerWindow< ? > panel )
	{
		this.panel = panel;
		return this;
	}

	private class MyActionListener implements ActionListener
	{

		@Override
		public void actionPerformed(ActionEvent e)
		{
			new Thread( () -> {
				SpimData2 data = null;

				if ( panel.getSpimData() instanceof SpimData2 )
					data = (SpimData2) panel.getSpimData();

				if ( data == null )
					return;

				final boolean alreadyFF = ( data.getSequenceDescription()
						.getImgLoader() instanceof FlatfieldCorrectionWrappedImgLoader< ? > );

				final ArrayList< Illumination > illums = SpimData2.getAllInstancesOfEntitySorted( data,
						data.getSequenceDescription().getViewDescriptions().keySet(), Illumination.class );
				final ArrayList< Channel > channels = SpimData2.getAllInstancesOfEntitySorted( data,
						data.getSequenceDescription().getViewDescriptions().keySet(), Channel.class );

				final GenericDialog gd0 = new GenericDialog( "Flatfield Correction" );
				gd0.addChoice( "Flatfield", SOURCES, SOURCES[ defaultSource ] );
				gd0.addMessage( "Gain field: a log-linear ramp per camera (channel x illumination) fitted from the tile\n"
						+ "overlaps, for sparse data with few tiles. BaSiC: flat- and darkfield from per-pixel statistics\n"
						+ "over many tiles, for dense data. Both use the selected views and write the fields to\n"
						+ FlatfieldEstimation.CONTAINER + " next to the XML; the images themselves are corrected on the fly.\n"
						+ "Run Adjust Intensities afterwards for the remaining per-tile gains." );
				gd0.showDialog();
				if ( gd0.wasCanceled() )
					return;
				defaultSource = gd0.getNextChoiceIndex();

				if ( defaultSource != 0 )
				{
					estimate( data, defaultSource == 1 );
					return;
				}

				final GenericDialogPlus gdp = new GenericDialogPlus( "Flatfield Correction" );

				gdp.addCheckbox( "set_active", alreadyFF
						? ( (FlatfieldCorrectionWrappedImgLoader< ? >) data.getSequenceDescription().getImgLoader() )
								.isActive()
						: true );
				gdp.addCheckbox( "cache_corrected_images", alreadyFF
						? ( (FlatfieldCorrectionWrappedImgLoader< ? >) data.getSequenceDescription().getImgLoader() )
								.isCached()
						: true );

				Map<ViewId, Pair<FlatfieldImageInfo, FlatfieldImageInfo>> infoMap = null;
				if ( alreadyFF )
					infoMap = ((LazyLoadingFlatFieldCorrectionMap<ImgLoader>) data.getSequenceDescription()
							.getImgLoader()).getInfoMap();

				for ( Channel c : channels )
					for ( Illumination ill : illums )
					{
						String bright = "";
						String dark = "";
						if ( alreadyFF )
						{
							ViewId anyViewId = data.getSequenceDescription().getViewDescriptions().values().stream()
									.filter( vd -> {
										return vd.getViewSetup().getChannel() == c
												&& vd.getViewSetup().getIllumination() == ill;
									} ).findAny().orElseGet( null );

							if ( anyViewId != null )
								if (infoMap.containsKey(anyViewId)) {
									Pair<FlatfieldImageInfo, FlatfieldImageInfo> infos = infoMap.get(anyViewId);
									if (infos.getA() != null)
										bright = new File(infos.getA().getUri()).getAbsolutePath();
									if (infos.getB() != null)
										dark = new File(infos.getB().getUri()).getAbsolutePath();
								}
						}
						gdp.addMessage( "Channel: " + c.getName() + ", Illumination: " + ill.getName() + ":" );
						gdp.addFileField( "Bright_Image", bright );
						gdp.addFileField( "Dark_Image", dark );
					}

				gdp.showDialog();
				if ( gdp.wasCanceled() )
					return;

				final boolean activate = gdp.getNextBoolean();
				final boolean cache = gdp.getNextBoolean();

				final FlatfieldCorrectionWrappedImgLoader< ? extends ImgLoader > ffIL;
				if ( alreadyFF )
					ffIL = (FlatfieldCorrectionWrappedImgLoader< ImgLoader >) data.getSequenceDescription()
							.getImgLoader();
				else if ( data.getSequenceDescription().getImgLoader() instanceof MultiResolutionImgLoader )
					ffIL = new MultiResolutionFlatfieldCorrectionWrappedImgLoader(
							(MultiResolutionImgLoader) data.getSequenceDescription().getImgLoader(), cache );
				else
					ffIL = new DefaultFlatfieldCorrectionWrappedImgLoader( data.getSequenceDescription().getImgLoader(),
							cache );

				for ( Channel c : channels )
					for ( Illumination ill : illums )
					{
						String lightPath = gdp.getNextString();
						String darkPath = gdp.getNextString();
						File lightFile = !lightPath.equals( "" ) ? new File( lightPath ) : null;
						File darkFile = !darkPath.equals( "" ) ? new File( darkPath ) : null;

						final File finalLightFile = lightFile;
						final File finalDarkFile = darkFile;
						data.getSequenceDescription().getViewDescriptions().entrySet().forEach( el -> {
							if ( el.getValue().getViewSetup().getChannel() == c
									&& el.getValue().getViewSetup().getIllumination() == ill )
							{
								if (finalLightFile != null)
									ffIL.setBrightImage( el.getKey(), new FlatfieldImageInfo(finalLightFile.toURI(), null) );
								if (finalDarkFile != null)
									ffIL.setDarkImage( el.getKey(), new FlatfieldImageInfo(finalDarkFile.toURI(), null) );
							}
						} );
					}

				ffIL.setActive( activate );
				ffIL.setCached( cache );

				data.getSequenceDescription().setImgLoader( ffIL );
				gdp.dispose();

				reopenBDV();
				panel.updateContent();

			} ).start();

//			WrapBasicImgLoader.wrapImgLoaderIfNecessary( data );
//			BigDataViewer.initSetups( data,bdv.getSetupAssignments().getConverterSetups(), (List< SourceAndConverter<?> >) (Object) bdv.getViewer().getState().getSources() );
		}
	}

	static final String[] SOURCES = { "Load bright / dark images from files", "Estimate gain field (sparse data: few tiles)", "Estimate with BaSiC (dense data: many tiles)" };
	static final String[] DARKFIELD = { "Estimate", "None", "Fixed value" };
	public static int defaultSource = 0;
	public static long defaultDownsampling = 4;
	public static int defaultDarkfield = 0;
	public static double defaultDarkfieldValue = 100;
	public static double defaultLambda = 0;
	public static boolean defaultAutotune = true;
	public static int defaultWorkingSize = 0;

	private void estimate( final SpimData2 data, final boolean gainField )
	{
		final List< ViewId > views = ApplyTransformationPopup.getSelectedViews( panel, true );
		if ( views.isEmpty() )
		{
			IOFunctions.println( "Flatfield estimation: no views selected." );
			return;
		}

		final GenericDialog gd = new GenericDialog( gainField ? "Estimate Gain Field" : "Estimate Flatfield (BaSiC)" );
		gd.addNumericField( "Downsampling_for_statistics", defaultDownsampling, 0 );
		gd.addMessage( "the coarsest pyramid level that is downsampled no more than this (in every dimension)" );
		if ( !gainField )
		{
			gd.addChoice( "Darkfield", DARKFIELD, DARKFIELD[ defaultDarkfield ] );
			gd.addNumericField( "Fixed_darkfield (counts)", defaultDarkfieldValue, 1 );
			gd.addNumericField( "Lambda (0 = automatic)", defaultLambda, 4 );
			gd.addCheckbox( "Autotune_lambda (if automatic)", defaultAutotune );
			gd.addNumericField( "Working_size (0 = statistics frame)", defaultWorkingSize, 0 );
		}
		gd.addMessage( views.size() + " selected views." );
		gd.showDialog();
		if ( gd.wasCanceled() )
			return;

		defaultDownsampling = Math.max( 1, Math.round( gd.getNextNumber() ) );
		final Basic.Parameters p = new Basic.Parameters();
		if ( !gainField )
		{
			defaultDarkfield = gd.getNextChoiceIndex();
			defaultDarkfieldValue = gd.getNextNumber();
			defaultLambda = gd.getNextNumber();
			defaultAutotune = gd.getNextBoolean();
			defaultWorkingSize = ( int ) Math.max( 0, Math.round( gd.getNextNumber() ) );
			p.estimateDarkfield = defaultDarkfield == 0;
			p.darkfieldOverride = defaultDarkfield == 2 ? defaultDarkfieldValue : null;
			p.lambda = defaultLambda;
			p.autotune = defaultAutotune;
			p.workingSize = defaultWorkingSize;
		}

		try
		{
			if ( gainField )
			{
				IOFunctions.println( "Estimating the gain field of " + views.size() + " views ..." );
				final FlatfieldEstimation.GainFieldResult r = FlatfieldEstimation.fitGainField( data, views, defaultDownsampling, Threads.numThreads() );
				FlatfieldEstimation.writeGainFields( data, views, r.fields );
				r.fields.forEach( ( c, f ) -> IOFunctions.println( "Gain field " + c + ": " + f ) );
			}
			else
			{
				IOFunctions.println( "Estimating flat- and darkfields (BaSiC) of " + views.size() + " views ..." );
				FlatfieldEstimation.estimateBasic( data, views, defaultDownsampling, p );
			}
		}
		catch ( final Exception e )
		{
			IOFunctions.println( "Flatfield estimation failed: " + e );
			e.printStackTrace();
			return;
		}

		final FlatfieldCorrectionWrappedImgLoader< ? > ffIL = (FlatfieldCorrectionWrappedImgLoader< ? >) data.getSequenceDescription().getImgLoader();
		ffIL.setActive( true );
		IOFunctions.println( "Flatfield correction is active; save the XML to keep it." );
		reopenBDV();
		panel.updateContent();
	}

	private void reopenBDV()
	{
		final BDVPopup bdvPop = ( panel.bdvPopup() instanceof BDVPopup ) ? (BDVPopup) panel.bdvPopup() : null;
		if ( bdvPop != null && bdvPop.bdvRunning() )
		{
			// we have to close and re-open BDV for the changed
			// ImgLoader to be recognized
			Dimension oldSize = bdvPop.getBDV().getViewerFrame().getSize();
			final Point oldLoc = bdvPop.getBDV().getViewerFrame().getLocation();
			final CountDownLatch latch = new CountDownLatch( 1 );

			final Thread closeTask = new Thread( () -> {
				bdvPop.closeBDV();
				latch.countDown();
			} );
			closeTask.start();

			try
			{
				latch.await();
			}
			catch ( InterruptedException e1 )
			{
				e1.printStackTrace();
			}

			final CountDownLatch latch2 = new CountDownLatch( 1 );
			new Thread( () -> {
				bdvPop.setBDV( BDVPopup.createBDV( panel ) );
				latch2.countDown();
			} ).start();

			try
			{
				latch2.await();
			}
			catch ( InterruptedException e1 )
			{
				e1.printStackTrace();
			}

			bdvPop.getBDV().getViewerFrame().setSize( oldSize );
			bdvPop.getBDV().getViewerFrame().setLocation( oldLoc );
		}
	}
}
