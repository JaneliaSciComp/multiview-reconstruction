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
package net.preibisch.mvrecon.fiji.plugin;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import net.imglib2.Dimensions;
import net.imglib2.util.Intervals;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.AllenOMEZarrLoader.OMEZARREntry;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;

public class Switch_Attributes implements PlugIn
{
	public static int defaultFirst = 0;
	public static int defaultSecond = 1;

	final static ArrayList< Class< ? extends Entity > > attributes = new ArrayList<>();
	static
	{
		attributes.add( Angle.class );
		attributes.add( Channel.class );
		attributes.add( Illumination.class );
		attributes.add( Tile.class );
		attributes.add( TimePoint.class );
	}

	public static SpimData2 switchAttributes( final SpimData2 data, final int in1, final int in2 )
	{
		if ( in1 == in2 )
		{
			IOFunctions.println( "You selected the same attribute twice, we are done.");
			return null;
		}

		final int index1, index2;

		if ( in1 > in2 )
		{
			index1 = in2;
			index2 = in1;
		}
		else
		{
			index1 = in1;
			index2 = in2;
		}

		if ( data.getSequenceDescription().getMissingViews() != null && data.getSequenceDescription().getMissingViews().getMissingViews().size() > 0 )
		{
			IOFunctions.println( "Missing views are not supported (yet).");
			return null;
		}

		final SequenceDescription sd = data.getSequenceDescription();
		final Collection<? extends ViewSetup> setups = sd.getViewSetups().values();
		final ArrayList<ViewSetup> setupsNew = new ArrayList<>();

		// only switching attributes that are not time, which is easier - we will have the same amount of ViewSetups, just their properties change
		if ( index2 != 4 )
		{
			for ( final ViewSetup vs : setups )
			{
				Angle newAngle = vs.getAngle();
				Channel newChannel = vs.getChannel();
				Illumination newIllum = vs.getIllumination();
				Tile newTile = vs.getTile();

				switch ( index1 )
				{
					case 0:
						if ( index2 == 1 )
						{
							// angle-channel swap
							newAngle = new Angle( vs.getChannel().getId(), vs.getChannel().getName() );
							newChannel = new Channel( vs.getAngle().getId(), vs.getAngle().getName() );
						}
						else if ( index2 == 2 )
						{
							// angle-illumination swap
							newAngle = new Angle( vs.getIllumination().getId(), vs.getIllumination().getName() );
							newIllum = new Illumination( vs.getAngle().getId(), vs.getAngle().getName() );
						}
						else
						{
							// angle-tile swap
							newAngle = new Angle( vs.getTile().getId(), vs.getTile().getName() );
							newTile = new Tile( vs.getAngle().getId(), vs.getAngle().getName() );
						}
						break;
					case 1:
						if ( index2 == 2 )
						{
							// channel-illumination swap
							newChannel = new Channel( vs.getIllumination().getId(), vs.getIllumination().getName() );
							newIllum = new Illumination( vs.getChannel().getId(), vs.getChannel().getName() );
						}
						else
						{
							// channel-tile swap
							newChannel = new Channel( vs.getTile().getId(), vs.getTile().getName() );
							newTile = new Tile( vs.getChannel().getId(), vs.getChannel().getName() );
						}
						break;
					case 2:
						// illum-tile swap
						newIllum = new Illumination( vs.getTile().getId(), vs.getTile().getName() );
						newTile = new Tile( vs.getIllumination().getId(), vs.getIllumination().getName() );
						break;
					default:
						throw new RuntimeException( "unexpected value: " + index1 );
				}

				setupsNew.add( new ViewSetup(vs.getId(), vs.getName(), vs.getSize(), vs.getVoxelSize(), newTile, newChannel, newAngle, newIllum ) );
			}

			final SequenceDescription sdNew = new SequenceDescription( sd.getTimePoints(), setupsNew );
			sdNew.setImgLoader( sd.getImgLoader() );

			return new SpimData2( data.getBasePathURI(), sdNew, data.getViewRegistrations(),
					data.getViewInterestPoints(),
					data.getBoundingBoxes(),
					data.getPointSpreadFunctions(),
					data.getStitchingResults(),
					data.getIntensityAdjustments() );
		}
		else
		{
			// the second index refers to switching an attribute with a timepoint, this is different a problem
			if ( !AllenOMEZarrLoader.class.isInstance( data.getSequenceDescription().getImgLoader() ) )
			{
				IOFunctions.println( "Switching attributes with timepoints currently only works when the project is saved as OME-ZARR. Please re-save first.");
				return null;
			}

			// we need to make sure that all ViewSetups of the attribute we are switching into time
			// have the same size and voxel dimensions. If not, it cannot be done.
			// e.g. when switching time and tile; for all combinations of Angle, Channel and Illumination all Tiles need to have the same dimension

			boolean isPossible = true;

			for ( final ViewSetup vs1 : setups )
			{
				final Dimensions dim1 = vs1.getSize();

				final Angle a1 = vs1.getAngle();
				final Channel c1 = vs1.getChannel();
				final Illumination i1 = vs1.getIllumination();
				final Tile t1 = vs1.getTile();

				for ( final ViewSetup vs2 : setups )
				{
					final Dimensions dim2 = vs2.getSize();

					// if the dimensions are not equal we need to check if that's OK
					// it is NOT OK if the rest of the attributes are the same
					if ( !Intervals.equalDimensions( dim1, dim2 ) )
					{
						final Angle a2 = vs2.getAngle();
						final Channel c2 = vs2.getChannel();
						final Illumination i2 = vs2.getIllumination();
						final Tile t2 = vs2.getTile();

						if ( index1 == 0 ) // angle
						{
							if ( c1.getId() == c2.getId() && i1.getId() == i2.getId() && t1.getId() == t2.getId() )
								isPossible = false;
						}
						else if ( index1 == 1 ) // channel
						{
							if ( a1.getId() == a2.getId() && i1.getId() == i2.getId() && t1.getId() == t2.getId() )
								isPossible = false;
						}
						else if ( index1 == 2 ) // illum
						{
							if ( a1.getId() == a2.getId() && c1.getId() == c2.getId() && t1.getId() == t2.getId() )
								isPossible = false;
						}
						else if ( index1 == 3 ) // tile
						{
							if ( a1.getId() == a2.getId() && c1.getId() == c2.getId() && i1.getId() == i2.getId() )
								isPossible = false;
						}
						else
						{
							throw new RuntimeException( "unexpected value: " + index1 );
						}
					}
				}
			}

			if ( !isPossible )
			{
				IOFunctions.println( "Dimensions are not constant across attribute " + attributes.get( index1 ).getSimpleName() + ". Stopping." );
				return null;
			}

			// replace paths in imgloader
			final AllenOMEZarrLoader imgloader = (AllenOMEZarrLoader)sd.getImgLoader();
			final Map<ViewId, OMEZARREntry> viewIdToPath = imgloader.getViewIdToPath();
			final Map<ViewId, OMEZARREntry> viewIdToPathNew = new HashMap<>();

			final Map<ViewId, ViewRegistration> viewRegistrationsNew = new HashMap<>();

			// create new ViewSetups - we will most likely have a different amount of ViewSetups
			final List<TimePoint> tpsOld = sd.getTimePoints().getTimePointsOrdered();
			final List<TimePoint> tpsNew;

			final List<Angle> anglesOld = sd.getAllAnglesOrdered();
			final List<Channel> channelsOld = sd.getAllChannelsOrdered();
			final List<Illumination> illumsOld = sd.getAllIlluminationsOrdered();
			final List<Tile> tilesOld = sd.getAllTilesOrdered();

			int viewSetupId = 0;

			if ( index1 == 0 ) // angle - timepoint switch
			{
				tpsNew = anglesOld.stream().map( a -> new TimePoint( a.getId() )).collect( Collectors.toList() );
				final List<Angle> anglesNew = tpsOld.stream().map( tp -> new Angle( tp.getId() ) ).collect( Collectors.toList() );

				for ( final Tile t : tilesOld )
				{
					for ( final Channel c : channelsOld )
					{
						for ( final Illumination i : illumsOld )
						{
							for ( int ai = 0; ai < anglesNew.size(); ++ai )
							{
								final Angle newAngle = anglesNew.get( ai );
								final TimePoint tpOld = tpsOld.get( ai );

								// find the dimensions and path of the corresponding, old ViewSetup
								// we made sure that the dimensions are all the same, so any old Angle will do
								ViewSetup corrVS = null;
								for ( final ViewSetup vs : setups )
								{
									if ( vs.getTile().getId() == t.getId() && vs.getChannel().getId() == c.getId() && vs.getIllumination().getId() == i.getId() )
									{
										corrVS = vs;
										break;
									}
								}

								final ViewSetup vsNew = new ViewSetup( viewSetupId++, null, corrVS.getSize(), corrVS.getVoxelSize(), t, c, newAngle, i );
								setupsNew.add( vsNew );

								// now we need to update the AllenOMEZarrLoader so it loads the correct image for all switched [ViewSetup x Timepoints], i.e. ViewIds
								for ( int tp = 0; tp < tpsNew.size(); ++tp )
								{
									// each new Timepoint in a ViewId corresponds to an old AngleId
									final TimePoint tpNew = tpsNew.get( tp );
									final Angle oldAngle = anglesOld.get( tp );

									final ViewId viewIdNew = new ViewId( tpNew.getId(), vsNew.getId() );

									corrVS = null;
									for ( final ViewSetup vs : setups )
									{
										if ( vs.getTile().getId() == t.getId() && vs.getChannel().getId() == c.getId() && vs.getIllumination().getId() == i.getId() && vs.getAngle().getId() == oldAngle.getId() )
										{
											corrVS = vs;
											break;
										}
									}

									final ViewId viewIdOld = new ViewId( tpOld.getId(), corrVS.getId() );

									// now fetch the OMEZARREntry and the ViewRegistration for the old viewId
									final OMEZARREntry omeZarr = viewIdToPath.get( viewIdOld );
									final ViewRegistration reg = data.getViewRegistrations().getViewRegistration( viewIdOld );

									// which is the OMEZARREntry and the ViewRegistration for the new ViewId
									viewIdToPathNew.put( viewIdNew, omeZarr );
									viewRegistrationsNew.put( viewIdNew, new ViewRegistration( tpNew.getId() , vsNew.getId(), new ArrayList<>( reg.getTransformList() ) ) );
								}
							}
						}
					}
				}
			}
			else if ( index1 == 1 ) // channel - timepoint switch
			{
				tpsNew = channelsOld.stream().map( a -> new TimePoint( a.getId() )).collect( Collectors.toList() );
				final List<Channel> channelsNew = tpsOld.stream().map( tp -> new Channel( tp.getId() ) ).collect( Collectors.toList() );

				for ( final Angle a : anglesOld )
				{
					for ( final Tile t : tilesOld )
					{
						for ( final Illumination i : illumsOld )
						{
							for ( int ci = 0; ci < channelsNew.size(); ++ci )
							{
								final Channel newChannel = channelsNew.get( ci );
								final TimePoint tpOld = tpsOld.get( ci );

								// find the dimensions and path of the corresponding, old ViewSetup
								// we made sure that the dimensions are all the same, so any old Channel will do
								ViewSetup corrVS = null;
								for ( final ViewSetup vs : setups )
								{
									if ( vs.getAngle().getId() == a.getId() && vs.getTile().getId() == t.getId() && vs.getIllumination().getId() == i.getId() )
									{
										corrVS = vs;
										break;
									}
								}

								final ViewSetup vsNew = new ViewSetup( viewSetupId++, null, corrVS.getSize(), corrVS.getVoxelSize(), t, newChannel, a, i );
								setupsNew.add( vsNew );

								// now we need to update the AllenOMEZarrLoader so it loads the correct image for all switched [ViewSetup x Timepoints], i.e. ViewIds
								for ( int tp = 0; tp < tpsNew.size(); ++tp )
								{
									// each new Timepoint in a ViewId corresponds to an old ChannelId
									final TimePoint tpNew = tpsNew.get( tp );
									final Channel oldChannel = channelsOld.get( tp );

									final ViewId viewIdNew = new ViewId( tpNew.getId(), vsNew.getId() );

									corrVS = null;
									for ( final ViewSetup vs : setups )
									{
										if ( vs.getAngle().getId() == a.getId() && vs.getTile().getId() == t.getId() && vs.getIllumination().getId() == i.getId() && vs.getChannel().getId() == oldChannel.getId() )
										{
											corrVS = vs;
											break;
										}
									}

									final ViewId viewIdOld = new ViewId( tpOld.getId(), corrVS.getId() );

									// now fetch the OMEZARREntry and the ViewRegistration for the old viewId
									final OMEZARREntry omeZarr = viewIdToPath.get( viewIdOld );
									final ViewRegistration reg = data.getViewRegistrations().getViewRegistration( viewIdOld );

									// which is the OMEZARREntry and the ViewRegistration for the new ViewId
									viewIdToPathNew.put( viewIdNew, omeZarr );
									viewRegistrationsNew.put( viewIdNew, new ViewRegistration( tpNew.getId() , vsNew.getId(), new ArrayList<>( reg.getTransformList() ) ) );
								}
							}
						}
					}
				}
			}
			else if ( index1 == 2 ) // illum - timepoint switch
			{
				tpsNew = illumsOld.stream().map( a -> new TimePoint( a.getId() )).collect( Collectors.toList() );
				final List<Illumination> illumsNew = tpsOld.stream().map( tp -> new Illumination( tp.getId() ) ).collect( Collectors.toList() );

				for ( final Angle a : anglesOld )
				{
					for ( final Channel c : channelsOld )
					{
						for ( final Tile t : tilesOld )
						{
							for ( int ii = 0; ii < illumsNew.size(); ++ii )
							{
								final Illumination newIllum = illumsNew.get( ii );
								final TimePoint tpOld = tpsOld.get( ii );

								// find the dimensions and path of the corresponding, old ViewSetup
								// we made sure that the dimensions are all the same, so any old Illumination will do
								ViewSetup corrVS = null;
								for ( final ViewSetup vs : setups )
								{
									if ( vs.getAngle().getId() == a.getId() && vs.getChannel().getId() == c.getId() && vs.getTile().getId() == t.getId() )
									{
										corrVS = vs;
										break;
									}
								}

								final ViewSetup vsNew = new ViewSetup( viewSetupId++, null, corrVS.getSize(), corrVS.getVoxelSize(), t, c, a, newIllum );
								setupsNew.add( vsNew );

								// now we need to update the AllenOMEZarrLoader so it loads the correct image for all switched [ViewSetup x Timepoints], i.e. ViewIds
								for ( int tp = 0; tp < tpsNew.size(); ++tp )
								{
									// each new Timepoint in a ViewId corresponds to an old IlluminationId
									final TimePoint tpNew = tpsNew.get( tp );
									final Illumination oldIllum = illumsOld.get( tp );

									final ViewId viewIdNew = new ViewId( tpNew.getId(), vsNew.getId() );

									corrVS = null;
									for ( final ViewSetup vs : setups )
									{
										if ( vs.getAngle().getId() == a.getId() && vs.getChannel().getId() == c.getId() && vs.getTile().getId() == t.getId() && vs.getIllumination().getId() == oldIllum.getId() )
										{
											corrVS = vs;
											break;
										}
									}

									final ViewId viewIdOld = new ViewId( tpOld.getId(), corrVS.getId() );

									// now fetch the OMEZARREntry and the ViewRegistration for the old viewId
									final OMEZARREntry omeZarr = viewIdToPath.get( viewIdOld );
									final ViewRegistration reg = data.getViewRegistrations().getViewRegistration( viewIdOld );

									// which is the OMEZARREntry and the ViewRegistration for the new ViewId
									viewIdToPathNew.put( viewIdNew, omeZarr );
									viewRegistrationsNew.put( viewIdNew, new ViewRegistration( tpNew.getId() , vsNew.getId(), new ArrayList<>( reg.getTransformList() ) ) );
								}
							}
						}
					}
				}
			}
			else if ( index1 == 3 ) // tile - timepoint switch
			{
				tpsNew = tilesOld.stream().map( a -> new TimePoint( a.getId() )).collect( Collectors.toList() );
				final List<Tile> tilesNew = tpsOld.stream().map( tp -> new Tile( tp.getId() ) ).collect( Collectors.toList() );

				for ( final Angle a : anglesOld )
				{
					for ( final Channel c : channelsOld )
					{
						for ( final Illumination i : illumsOld )
						{
							for ( int ti = 0; ti < tilesNew.size(); ++ti )
							{
								final Tile newTile = tilesNew.get( ti );
								final TimePoint tpOld = tpsOld.get( ti );

								// find the dimensions and path of the corresponding, old ViewSetup
								// we made sure that the dimensions are all the same, so any old Tile will do
								ViewSetup corrVS = null;
								for ( final ViewSetup vs : setups )
								{
									if ( vs.getAngle().getId() == a.getId() && vs.getChannel().getId() == c.getId() && vs.getIllumination().getId() == i.getId() )
									{
										corrVS = vs;
										break;
									}
								}

								final ViewSetup vsNew = new ViewSetup( viewSetupId++, null, corrVS.getSize(), corrVS.getVoxelSize(), newTile, c, a, i );
								setupsNew.add( vsNew );

								// now we need to update the AllenOMEZarrLoader so it loads the correct image for all switched [ViewSetup x Timepoints], i.e. ViewIds
								for ( int tp = 0; tp < tpsNew.size(); ++tp )
								{
									// each new Timepoint in a ViewId corresponds to an old TileId
									final TimePoint tpNew = tpsNew.get( tp );
									final Tile oldTile = tilesOld.get( tp );

									final ViewId viewIdNew = new ViewId( tpNew.getId(), vsNew.getId() );

									corrVS = null;
									for ( final ViewSetup vs : setups )
									{
										if ( vs.getAngle().getId() == a.getId() && vs.getChannel().getId() == c.getId() && vs.getIllumination().getId() == i.getId() && vs.getTile().getId() == oldTile.getId() )
										{
											corrVS = vs;
											break;
										}
									}

									final ViewId viewIdOld = new ViewId( tpOld.getId(), corrVS.getId() );

									// now fetch the OMEZARREntry and the ViewRegistration for the old viewId
									final OMEZARREntry omeZarr = viewIdToPath.get( viewIdOld );
									final ViewRegistration reg = data.getViewRegistrations().getViewRegistration( viewIdOld );

									// which is the OMEZARREntry and the ViewRegistration for the new ViewId
									viewIdToPathNew.put( viewIdNew, omeZarr );
									viewRegistrationsNew.put( viewIdNew, new ViewRegistration( tpNew.getId() , vsNew.getId(), new ArrayList<>( reg.getTransformList() ) ) );
								}
							}
						}
					}
				}
			}
			else
			{
				throw new RuntimeException( "unexpected value: " + index1 );
			}

			final SequenceDescription sdNew = new SequenceDescription( new TimePoints( tpsNew ), setupsNew );
			final AllenOMEZarrLoader imgLoaderNew = new AllenOMEZarrLoader( imgloader.getN5URI(), sdNew, viewIdToPathNew );
			sdNew.setImgLoader( imgLoaderNew );

			// we do not want to return the objects from data, since all ViewId's are changed
			return new SpimData2(
					data.getBasePathURI(),
					sdNew,
					new ViewRegistrations( viewRegistrationsNew ), // we leave them in here because it includes the metadata transformations that are often the same for all views
					new ViewInterestPoints(),
					data.getBoundingBoxes(),
					new PointSpreadFunctions(),
					new StitchingResults(),
					new IntensityAdjustments() );
		}
	}

	public static SpimData2 switchAttributesGUI( final SpimData2 data )
	{
		if ( data.getSequenceDescription().getMissingViews() != null && data.getSequenceDescription().getMissingViews().getMissingViews().size() > 0 )
		{
			IOFunctions.println( "Missing views are not supported (yet).");
			return null;
		}

		final String[] choices = attributes.stream().map( e -> e.getSimpleName() ).toArray( String[]::new );

		final GenericDialog gd1 = new GenericDialog( "Attributes to switch" );
		gd1.addChoice( "Attribute to switch", choices, choices[ defaultFirst ] );
		gd1.addChoice( "Switch_with", choices, choices[ defaultSecond ] );

		gd1.addMessage( "Switching TIMEPOINTS is only supported for OME-ZARR datasets.", GUIHelper.smallStatusFont, Color.black );

		gd1.addMessage( "If you switch with TIMEPOINTS, this operation will not carry \n"
					  + "over most of metadata to the new XML \n(interest points, PSFs, \n"
					  + "stitching results and intensity adjustments).", GUIHelper.smallStatusFont, Color.red );

		gd1.showDialog();

		if ( gd1.wasCanceled() )
			return null;

		final int index1 = defaultFirst = gd1.getNextChoiceIndex();
		final int index2 = defaultSecond = gd1.getNextChoiceIndex();

		if ( index1 == index2 )
		{
			IOFunctions.println( "You selected the same attribute twice, we are done.");
			return null;
		}

		IOFunctions.println( "Switching " + attributes.get( index1 ).getSimpleName() + " <> " + attributes.get( index2 ).getSimpleName() );

		return switchAttributes(data, index1, index2);
	}

	@Override
	public void run(String arg)
	{
		final LoadParseQueryXML xml = new LoadParseQueryXML();

		//LoadParseQueryXML.defaultXMLURI = "/Users/preibischs/SparkTest/Stitching/dataset.xml";

		if ( !xml.queryXML( "", false, false, false, false, false ) )
			return;

		final SpimData2 data = xml.getData();

		final SpimData2 result = switchAttributesGUI( data );
		if ( result == null )
			return;

		// write new xml
		new XmlIoSpimData2().saveWithFilename( result, xml.getXMLFileName() );
	}

	public static void main( String[] args )
	{
		new Switch_Attributes().run(null);
	}
}
