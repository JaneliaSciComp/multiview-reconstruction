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

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.RandomAccessibleInterval;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.explorer.ExplorerWindow;
import net.preibisch.mvrecon.process.export.DisplayImage;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public class DisplayRawImagesPopup extends JMenu implements ExplorerWindowSetable
{
	private static final long serialVersionUID = 5234649262342301390L;

	ExplorerWindow< ? > panel = null;

	public DisplayRawImagesPopup()
	{
		super( "Display Raw Image(s)" );

		final JMenuItem asFullStack = new JMenuItem( "Full-resolution as ImageJ Stack" );
		asFullStack.addActionListener( new MyActionListener( 0 ) );
		this.add( asFullStack );

		final JMenu asStack = new JMenu( "As ImageJ Stack at level" );

		asStack.addMenuListener( new MenuListener()
		{
			@Override
			public void menuSelected( MenuEvent e )
			{
				asStack.removeAll();

				final SpimData2 spimData = (SpimData2)panel.getSpimData();

				final ArrayList< ViewId > views = new ArrayList<>();
				views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

				// filter not present ViewIds
				SpimData2.filterMissingViews( spimData, views );

				if ( views.size() == 0 )
				{
					JMenuItem item = new JMenuItem( "No views selected." );
					item.setForeground( Color.GRAY );
					asStack.add( item );
					return;
				}

				if ( MultiResolutionImgLoader.class.isInstance( spimData.getSequenceDescription().getImgLoader() ))
				{
					final MultiResolutionImgLoader mrLoader = (MultiResolutionImgLoader)spimData.getSequenceDescription().getImgLoader();

					HashMap< Integer, long[] > levelToSize = null;

					boolean consistent = true;
					boolean consistentSizes = true;

					for ( final ViewId v : views )
					{
						if ( !consistent )
							break;

						final MultiResolutionSetupImgLoader<?> il = mrLoader.getSetupImgLoader( v.getViewSetupId() );
						final int levels = il.getMipmapTransforms().length;

						if ( levelToSize == null )
						{
							levelToSize = new HashMap<>();

							for ( int level = 0; level < levels; ++level )
								levelToSize.put( level, il.getImageSize( v.getTimePointId(), level ).dimensionsAsLongArray() );
						}
						else
						{
							if ( levels != levelToSize.size() )
								consistent = false;
							else
								for ( int level = 0; level < levels; ++level )
									if ( !Arrays.equals( levelToSize.get( level ), il.getImageSize( v.getTimePointId(), level ).dimensionsAsLongArray() ) )
										consistentSizes = false;
						}
					}

					if ( levelToSize != null && consistent )
					{
						for ( int level = 0; level < levelToSize.size(); ++level )
						{
							JMenuItem item = new JMenuItem( "Level " + level + (consistentSizes ? " " + Arrays.toString( levelToSize.get( level ) ) : " [image sizes vary]" ) );
							item.addActionListener( new MyActionListener( level ) );
							asStack.add( item );
						}
					}
					else
					{
						JMenuItem item = new JMenuItem( "MultiResolution levels vary across selected views" );
						item.setForeground( Color.GRAY );
						asStack.add( item );
					}
					//JMenuItem item = new JMenuItem( labels[ i ] );
					//item.addActionListener( new HistogramListener( spimData, views, InterestPointTools.getSelectedLabel( labels, i ), i ) );
					//asStack.add( item );
				}
				else
				{
					JMenuItem item = new JMenuItem( "No MultiResolutionImgLoader" );
					item.setForeground( Color.GRAY );
					asStack.add( item );
				}
			}

			@Override
			public void menuDeselected( MenuEvent e ) {}

			@Override
			public void menuCanceled( MenuEvent e ) {}
		} );
		this.add( asStack );
	}

	@Override
	public JMenuItem setExplorerWindow( final ExplorerWindow< ? > panel )
	{
		this.panel = panel;

		return this;
	}

	public class MyActionListener implements ActionListener
	{
		final int level;
		public MyActionListener( final int level )
		{
			this.level = level;
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
					final ArrayList< ViewId > views = new ArrayList<>();
					views.addAll( ApplyTransformationPopup.getSelectedViews( panel ) );

					final SpimData2 data = panel.getSpimData();
					final ImgLoader il = data.getSequenceDescription().getImgLoader();

					// filter not present ViewIds
					SpimData2.filterMissingViews( data, views );

					if ( views.size() == 0 )
					{
						IOFunctions.println( "No views selected" );
						return;
					}

					IOFunctions.println( "ImageLoader: " + il.getClass().getSimpleName() );

					for ( final ViewId view : views )
					{
						IOFunctions.println( "Loading timepoint: " + view.getTimePointId() + " ViewSetup: " + view.getViewSetupId() );
						final String name = "Timepoint: " + view.getTimePointId() + " ViewSetup: " + view.getViewSetupId();

						final RandomAccessibleInterval image;

						if ( level <= 0 )
							image = il.getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId() );
						else
							image = ((MultiResolutionImgLoader)il).getSetupImgLoader( view.getViewSetupId() ).getImage( view.getTimePointId(), level );

						FusionTools.getImagePlusInstance( image, true, name, 0, 255, DisplayImage.service ).show();
					}
				}
			} ).start();
		}
	}

}
