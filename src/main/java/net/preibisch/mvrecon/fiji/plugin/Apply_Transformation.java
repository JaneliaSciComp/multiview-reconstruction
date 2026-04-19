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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import bdv.BigDataViewer;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.RealPoint;
import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListImgFactory;
import net.imglib2.img.list.ListRandomAccess;
import net.imglib2.realtransform.AffineGet;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Util;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.Pair;
import net.preibisch.mvrecon.fiji.ImgLib2Temp.ValuePair;
import net.preibisch.mvrecon.fiji.datasetmanager.DatasetCreationUtils;
import net.preibisch.mvrecon.fiji.plugin.apply.ApplyParameters;
import net.preibisch.mvrecon.fiji.plugin.apply.BigDataViewerTransformationWindow;
import net.preibisch.mvrecon.fiji.plugin.apply.ModelLink;
import net.preibisch.mvrecon.fiji.plugin.queryXML.LoadParseQueryXML;
import net.preibisch.mvrecon.fiji.plugin.util.GUIHelper;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.ViewSetupUtils;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxBigDataViewer;
import net.preibisch.mvrecon.vecmath.Transform3D;

public class Apply_Transformation implements PlugIn
{
	public static boolean defaultSameModelTimePoints = true;
	public static boolean defaultSameModelChannels = true;
	public static boolean defaultSameModelIlluminations = true;
	public static boolean defaultSameModelAngles = true;
	public static boolean defaultSameModelTiles = true;
	
	public static int defaultModel = 2;
	public static int defaultDefineAs = 1;
	public static int defaultApplyTo = 2;
	
	public static String[] applyToChoice = new String[]{
		"Identity transform (removes any existing transforms)",
		"Calibration (removes any existing transforms)",
		"Current view transformations (appends to current transforms)" };	
	public static String[] modelChoice = new String[]{ "Identity (no transformation)", "Translation", "Rigid", "Affine" };
	public static String[] defineAsChoice = new String[] { "Matrix", "Rotation around axis", "Interactively using the BigDataViewer" };
	
	public String[] axesChoice = new String[] { "x-axis", "y-axis", "z-axis" };
	public static int[] defaultAxis = null;
	public static double[] defaultDegrees = null;
	public static ArrayList< double[] > defaultModels;

	@Override
	public void run( final String arg0 )
	{
		// ask for everything
		final LoadParseQueryXML result = new LoadParseQueryXML();

		if ( !result.queryXML( "applying a transformation", "Apply to", true, true, true, true, true ) )
			return;

		final SpimData2 data = result.getData();
		final List< ViewId > viewIds = SpimData2.getAllViewIdsSorted( result.getData(), result.getViewSetupsToProcess(), result.getTimePointsToProcess() );

		final ApplyParameters params = queryParams( data, viewIds );

		if ( params == null )
			return;

		final Map< ViewDescription, Pair< double[], String > > modelLinks;

		// query models and apply them
		if ( params.defineAs == 0 ) // matrix
			modelLinks = queryString( data, viewIds, params );
		else if ( params.defineAs == 1 ) //Rotation around axis
			modelLinks = queryRotationAxis( data, viewIds, params );
		else // Interactively using the BigDataViewer
			modelLinks = queryBigDataViewer( data, viewIds, params );

		if ( modelLinks == null )
			return;

		applyModels( data, params.minResolution, params.applyTo, modelLinks );

		// now save it
		new XmlIoSpimData2().saveWithFilename( result.getData(), result.getXMLFileName() );

	}

	/*
	 * @param data
	 * @param viewIds
	 * @return - transformation model and explanation for each viewid, also sets global variables applyTo and minResolution
	 */
	public final ApplyParameters queryParams( final SpimData data, final List< ViewId > viewIds )
	{
		final List< Angle > angles = SpimData2.getAllAnglesSorted( data, viewIds );
		final List< Channel > channels = SpimData2.getAllChannelsSorted( data, viewIds );
		final List< Illumination > illums = SpimData2.getAllIlluminationsSorted( data, viewIds );
		final List< TimePoint > tps = SpimData2.getAllTimePointsSorted( data, viewIds );
		final List< Tile > tiles = SpimData2.getAllInstancesOfEntitySorted( data, viewIds, Tile.class );

		final boolean multipleTimePoints = tps.size() > 1;
		final boolean multipleChannels = channels.size() > 1;
		final boolean multipleIlluminations = illums.size() > 1;
		final boolean multipleAngles = angles.size() > 1;
		final boolean multipleTiles = tiles.size() > 1;

		final GenericDialog gd = new GenericDialog( "Choose transformation model" );

		gd.addChoice( "Transformation model", modelChoice, modelChoice[ defaultModel ] );
		gd.addChoice( "Apply on top of", applyToChoice, applyToChoice[ defaultApplyTo ] );
		
		if ( multipleTimePoints )
			gd.addCheckbox( "Same_transformation_for_all_timepoints", defaultSameModelTimePoints );

		if ( multipleChannels )
			gd.addCheckbox( "Same_transformation_for_all_channels", defaultSameModelChannels );

		if ( multipleIlluminations )
			gd.addCheckbox( "Same_transformation_for_all_illuminations", defaultSameModelIlluminations );

		if ( multipleAngles )
			gd.addCheckbox( "Same_transformation_for_all_angles", defaultSameModelAngles );
		
		if ( multipleTiles )
			gd.addCheckbox( "Same_transformation_for_all_tiles", defaultSameModelTiles );

		gd.addMessage(
				"Interactive application of transformations using the BigDataViewer is available when selecting a Rigid Model.",
				GUIHelper.mediumstatusfont );

		gd.showDialog();
		
		if ( gd.wasCanceled() )
			return null;

		final ApplyParameters params = new ApplyParameters();

		params.model = defaultModel = gd.getNextChoiceIndex();
		params.applyTo = defaultApplyTo = gd.getNextChoiceIndex();
		
		if ( params.model == 2 ) // if rigid, ask how to define the rigid model
		{
			final GenericDialog gd2 = new GenericDialog( "Choose application for transformation model" );
			gd2.addChoice( "Define as", defineAsChoice, defineAsChoice[ defaultDefineAs ] );

			gd2.showDialog();
			
			if ( gd2.wasCanceled() )
				return null;

			params.defineAs = defaultDefineAs = gd2.getNextChoiceIndex();
		}
		else
		{
			params.defineAs = 0; // for all others there is only matrix
		}

		if ( multipleTimePoints )
			params.sameModelTimePoints = defaultSameModelTimePoints = gd.getNextBoolean();
		else
			params.sameModelTimePoints = true;

		if ( multipleChannels )
			params.sameModelChannels = defaultSameModelChannels = gd.getNextBoolean();
		else
			params.sameModelChannels = true;

		if ( multipleIlluminations )
			params.sameModelIlluminations = defaultSameModelIlluminations = gd.getNextBoolean();
		else
			params.sameModelIlluminations = true;

		if ( multipleAngles )
			params.sameModelAngles = defaultSameModelAngles = gd.getNextBoolean();
		else
			params.sameModelAngles = true;
		
		if ( multipleTiles )
			params.sameModelTiles = defaultSameModelTiles = gd.getNextBoolean();
		else
			params.sameModelTiles = true;

		// reset the transform to the calibration (x,y,z resolution), in this case we need to make sure the calibration is actually available
		if ( params.applyTo == 1 )
		{
			params.minResolution = DatasetCreationUtils.minResolution( data.getSequenceDescription(), viewIds );

			if ( Double.isNaN( params.minResolution ) )
			{
				IOFunctions.println( "Could not assemble the calibration, quitting." );
				return null;
			}
		}
		else
		{
			params.minResolution = 1;
		}

		return params;
	}

	public Map< ViewDescription, Pair< double[], String > > queryBigDataViewer(
			final SpimData data,
			final List< ViewId > viewIds,
			final ApplyParameters params )
	{
		if ( !params.sameModelAngles || !params.sameModelChannels || !params.sameModelIlluminations || !params.sameModelIlluminations || !params.sameModelTiles )
		{
			IOFunctions.println( "You selected to not have the same transformation model for all views in the" );
			IOFunctions.println( "previous dialog. This is not supported using the interactive setup using the" );
			IOFunctions.println( "BigDataViewer. You can select single views in the xml open dialog though." );

			return null;
		}

		try
		{
			final Pair< BigDataViewer, Boolean > bdvPair = BoundingBoxBigDataViewer.getBDV( data, viewIds );
			
			if ( bdvPair == null || bdvPair.getA() == null )
				return null;

			final BigDataViewerTransformationWindow bdvw = new BigDataViewerTransformationWindow( bdvPair.getA() );

			do
			{
				try
				{
					Thread.sleep( 100 );
				}
				catch (InterruptedException e)
				{
					e.printStackTrace();
				}
			}
			while ( bdvw.isRunning() );

			// was locally launched?
			if ( bdvPair.getB() )
				BigDataViewerTransformationWindow.disposeViewerWindow( bdvPair.getA() );

			if ( bdvw.wasCancelled() )
				return null;

			final HashMap< ViewDescription, Pair< double[], String > > modelLinks = new HashMap< ViewDescription, Pair< double[], String > >();

			final AffineTransform3D t = bdvw.getTransform();
			final double[] m = t.getRowPackedCopy();
			final String d = "Rigid transform defined by BigDataViewer";

			defaultModels = new ArrayList< double[] >();
			defaultModels.add( m );

			for ( final ViewId viewId : viewIds )
			{
				final ViewDescription vd = data.getSequenceDescription().getViewDescription( viewId );
				modelLinks.put( vd, new ValuePair< double[], String >( m, d ) );
			}

			return modelLinks;
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Failed to run the BigDataViewer ... " );
			e.printStackTrace();
			return null;
		}
	}

	public Map< ViewDescription, Pair< double[], String > > queryRotationAxis(
			final SpimData data,
			final List< ViewId > viewIds,
			final ApplyParameters params )
	{
		if ( params.model != 2 )
		{
			IOFunctions.println( "No rigid model selected." );
			return null;
		}

		// build a sparse four-dimensional table containing all entries that need to be queried
		// assign indices 0...n-1 to individual TimePoints, Channels, Illums, Angles
		// if same model for a type of entry (e.g. timepoint), than all Entries will link to the same index
		final HashMap< TimePoint, Integer > mapT = new HashMap< TimePoint, Integer >();
		final HashMap< Channel, Integer > mapC = new HashMap< Channel, Integer >();
		final HashMap< Illumination, Integer > mapI = new HashMap< Illumination, Integer >();
		final HashMap< Angle, Integer > mapA = new HashMap< Angle, Integer >();

		// iterate first angles, then illums, then channels, then timepoints
		final ListImg< ModelLink > img = createTable(
				data, viewIds,
				params.sameModelTimePoints,
				params.sameModelChannels,
				params.sameModelIlluminations,
				params.sameModelAngles,
				mapT, mapC, mapI, mapA );

		final ListRandomAccess< ModelLink > ra = img.randomAccess();
		final int[] l = new int[ img.numDimensions() ];

		// populate the table
		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( viewId );

			locationForViewDescription( l, vd, mapT, mapC, mapI, mapA );

			ra.setPosition( l );

			if ( ra.get() == null )
				ra.set( new ModelLink( vd ) );
			else
				ra.get().add( vd );
		}

		final int numEntries = numEntries( img );

		IOFunctions.println( "Querying " + numEntries + " different rotation axis models." );

		if ( defaultAxis == null || defaultDegrees == null || defaultAxis.length != numEntries || defaultDegrees.length != numEntries )
		{
			defaultAxis = new int[ numEntries ];
			defaultDegrees = new double[ numEntries ];

			int j = 0;

			for ( final ModelLink ml : img )
				if ( ml != null )
				{
					defaultDegrees[ j ] = 0;
					defaultAxis[ j ] = 0;

					try
					{
						final Angle a = ml.viewDescriptions().get( 0 ).getViewSetup().getAngle();

						final double[] axis = a.getRotationAxis();
						if ( axis != null )
						{
							if ( axis[ 0 ] == 1 && axis[ 1 ] == 0 && axis[ 2 ] == 0 )
								defaultAxis[ j ] = 0;
							else if ( axis[ 0 ] == 0 && axis[ 1 ] == 1 && axis[ 2 ] == 0 )
								defaultAxis[ j ] = 1;
							else if ( axis[ 0 ] == 0 && axis[ 1 ] == 0 && axis[ 2 ] == 2 )
								defaultAxis[ j ] = 2;
						}

						if ( !Double.isNaN( a.getRotationAngleDegrees() ) )
							defaultDegrees[ j ] = a.getRotationAngleDegrees();
						else
							defaultDegrees[ j ] = Double.parseDouble( a.getName() );
					}
					catch ( Exception e ) {};

					++j;
				}
		}
		
		final GenericDialog gd = new GenericDialog( "Parameters for rigid model 3d" );

		int j = 0;
		
		for ( final ModelLink ml : img )
			if ( ml != null )
			{
				gd.addChoice( "Axis_" + ml.dialogName(), axesChoice, axesChoice[ defaultAxis[ j ] ] );
				gd.addSlider( "Rotation_" + ml.dialogName(), -360, 360, defaultDegrees[ j ] );

				++j;
			}

		if ( numEntries > 5 )
			GUIHelper.addScrollBars( gd );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;

		final HashMap< ViewDescription, Pair< double[], String > > modelLinks = new HashMap< ViewDescription, Pair< double[], String > >();
		defaultModels = new ArrayList< double[] >();
		final int[] axes = new int[ numEntries ];
		final double[] degrees = new double[ numEntries ];

		final double[] tmp = new double[ 16 ];

		j = 0;

		for ( final ModelLink ml : img )
		{
			if ( ml != null )
			{
				axes[ j ] = gd.getNextChoiceIndex();
				degrees[ j ] = gd.getNextNumber();

				final String d;
				final Transform3D t = new Transform3D();
				if ( axes[ j ] == 0 )
				{
					t.rotX( Math.toRadians( degrees[ j ] ) );
					d = "Rotation around x-axis by " + degrees[ j ] + " degrees";
				}
				else if ( axes[ j ] == 1 )
				{
					t.rotY( Math.toRadians( degrees[ j ] ) );
					d = "Rotation around y-axis by " + degrees[ j ] + " degrees";
				}
				else
				{
					t.rotZ( Math.toRadians( degrees[ j ] ) );
					d = "Rotation around z-axis by " + degrees[ j ] + " degrees";
				}

				t.get( tmp );

				final double[] m = new double[]{
						tmp[ 0 ], tmp[ 1 ], tmp[ 2 ], tmp[ 3 ],
						tmp[ 4 ], tmp[ 5 ], tmp[ 6 ], tmp[ 7 ],
						tmp[ 8 ], tmp[ 9 ], tmp[ 10 ], tmp[ 11 ] };

				defaultModels.add( m );
				ml.setModel( m, d );

				for ( final ViewDescription vd : ml.viewDescriptions() )
					modelLinks.put( vd, new ValuePair< double[], String >( ml.model(), ml.modelDescription() ) );
			}
		}

		// set defaults
		defaultAxis = axes;
		defaultDegrees = degrees;

		return modelLinks;
	}

	public Map< ViewDescription, Pair< double[], String > > queryString(
			final SpimData data,
			final List< ViewId > viewIds,
			final ApplyParameters params )
	{
		// build a sparse four-dimensional table containing all entries that need to be queried
		// assign indices 0...n-1 to individual TimePoints, Channels, Illums, Angles
		// if same model for a type of entry (e.g. timepoint), than all Entries will link to the same index
		final HashMap< TimePoint, Integer > mapT = new HashMap< TimePoint, Integer >();
		final HashMap< Channel, Integer > mapC = new HashMap< Channel, Integer >();
		final HashMap< Illumination, Integer > mapI = new HashMap< Illumination, Integer >();
		final HashMap< Angle, Integer > mapA = new HashMap< Angle, Integer >();

		// iterate first angles, then illums, then channels, then timepoints
		final ListImg< ModelLink > img = createTable(
				data, viewIds,
				params.sameModelTimePoints,
				params.sameModelChannels,
				params.sameModelIlluminations,
				params.sameModelAngles,
				mapT, mapC, mapI, mapA );
		final ListRandomAccess< ModelLink > ra = img.randomAccess();
		final int[] l = new int[ img.numDimensions() ];

		// populate the table
		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd = data.getSequenceDescription().getViewDescription( viewId );

			locationForViewDescription( l, vd, mapT, mapC, mapI, mapA );

			ra.setPosition( l );

			if ( ra.get() == null )
				ra.set( new ModelLink( vd ) );
			else
				ra.get().add( vd );
		}

		final int numEntries = numEntries( img );

		IOFunctions.println( "Querying " + numEntries + " different transformation models." );

		if ( defaultModels == null || defaultModels.size() == 0 || defaultModels.size() != numEntries )
		{
			defaultModels = new ArrayList< double[] >();

			for ( int i = 0; i < numEntries; ++i )
				defaultModels.add( new double[]{ 1, 0, 0, 0,    0, 1, 0, 0,    0, 0, 1, 0 } );
		}

		final GenericDialog gd;
		
		if ( params.model == 0 ) // identity transform
		{
			gd = null;
		}
		else if ( params.model == 1 ) // translation model
		{
			gd = new GenericDialog( "Model parameters for translation model 3d" );

			gd.addMessage( "t(x) = m03, t(y) = m13, t(z) = m23" );
			gd.addMessage( "" );
			gd.addMessage( "Please provide 3d translation in this form (any brackets will be ignored): m03, m13, m23" );

			int j = 0;

			for ( final ModelLink ml : img )
				if ( ml != null )
					gd.addStringField(
						ml.dialogName(),
						defaultModels.get( j )[ 3 ] + ", " +
						defaultModels.get( j )[ 7 ] + ", " +
						defaultModels.get( j++ )[ 11 ], 30 );
		}
		else // rigid/affine model
		{
			gd = new GenericDialog( "Model parameters for rigid/affine model 3d" );

			gd.addMessage(
				"| m00 m01 m02 m03 |\n" +
				"| m10 m11 m12 m13 |\n" +
				"| m20 m21 m22 m23 |" );

			gd.addMessage( "Please provide 3d rigid/affine in this form (any brackets will be ignored):\n" +
				"m00, m01, m02, m03, m10, m11, m12, m13, m20, m21, m22, m23" );
			
			int j = 0;
			
			for ( final ModelLink ml : img )
				if ( ml != null )
				{
					final double[] m = defaultModels.get( j++ );
					gd.addStringField(
						ml.dialogName(),
						m[ 0 ] + ", " + m[ 1 ] + ", " + m[ 2 ] + ", " + m[ 3 ] + ", " +
						m[ 4 ] + ", " + m[ 5 ] + ", " + m[ 6 ] + ", " + m[ 7 ] + ", " +
						m[ 8 ] + ", " + m[ 9 ] + ", " + m[ 10 ] + ", " + m[ 11 ], 80 );
				}
		}

		if ( gd != null )
		{
			if ( numEntries > 10 )
				GUIHelper.addScrollBars( gd );
			
			gd.showDialog();
			
			if ( gd.wasCanceled() )
				return null;
		}

		final HashMap< ViewDescription, Pair< double[], String > > modelLinks = new HashMap< ViewDescription, Pair< double[], String > >();
		defaultModels = new ArrayList< double[] >();

		for ( final ModelLink ml : img )
		{
			if ( ml != null )
			{
				final double[] m;
				final String d;
				
				if ( params.model == 0 )
				{
					m = null;
					d = "Identity";
				}
				else if ( params.model == 1 )
				{
					final double[] v = parseString( gd.getNextString(), 3 );
					
					if ( v == null )
						return null;
					else
					{
						m = new double[]{ 1, 0, 0, v[ 0 ], 0, 1, 0, v[ 1 ], 0, 0, 1, v[ 2 ] };
						d = "Translation [" + v[ 0 ] + "," + v[ 1 ] + "," + v[ 2 ] + "]";
					}
				}
				else
				{
					m = parseString( gd.getNextString(), 12 );

					if ( m == null )
						return null;
					else
						d = "Rigid/Affine by matrix";
				}

				if ( m == null )
					defaultModels.add( new double[]{ 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0 } );
				else
					defaultModels.add( m );

				ml.setModel( m, d );

				for ( final ViewDescription vd : ml.viewDescriptions() )
					modelLinks.put( vd, new ValuePair< double[], String >( ml.model(), ml.modelDescription() ) );
			}
		}

		return modelLinks;
	}

	public void applyModels( final SpimData spimData, final double minResolution, final int applyTo, final Map< ViewDescription, Pair< double[], String > > modelLinks )
	{
		for ( final ViewDescription vd : modelLinks.keySet() )
		{
			final double[] v = modelLinks.get( vd ).getA();
			final String modelDesc = modelLinks.get( vd ).getB();

			final TimePoint t = vd.getTimePoint();
			final Channel c = vd.getViewSetup().getChannel();
			final Illumination i = vd.getViewSetup().getIllumination();
			final Angle a = vd.getViewSetup().getAngle();

			if ( applyTo == 0 )
			{
				IOFunctions.println( "Reseting model to identity transform for timepoint " + t.getName() + ", channel " + c.getName() + ", illum " + i.getName() + ", angle " + a.getName() );
				setModelToIdentity( spimData, vd );
			}
			else if ( applyTo == 1 )
			{
				IOFunctions.println( "Reseting model to calibration for timepoint " + t.getName() + ", channel " + c.getName() + ", illum " + i.getName() + ", angle " + a.getName() );
				setModelToCalibration( spimData, vd, minResolution );
			}
			
			if ( v != null )
			{
				IOFunctions.println( "Applying model " + Util.printCoordinates( v ) + " (" + modelDesc + ") to timepoint " + t.getName() + ", channel " + c.getName() + ", illum " + i.getName() + ", angle " + a.getName() );
				
				final AffineTransform3D model = new AffineTransform3D();
				model.set( v );
				
				preConcatenateTransform( spimData, vd, model, "Manually defined transformation (" + modelDesc + ")" );
			}
		}
	}

	private static final void locationForViewDescription(
			final int[] l,
			final ViewDescription vd,
			final HashMap< TimePoint, Integer > mapT,
			final HashMap< Channel, Integer > mapC,
			final HashMap< Illumination, Integer > mapI,
			final HashMap< Angle, Integer > mapA )
	{
		final TimePoint t = vd.getTimePoint();
		final Channel c = vd.getViewSetup().getChannel();
		final Illumination i = vd.getViewSetup().getIllumination();
		final Angle a = vd.getViewSetup().getAngle();

		l[ 0 ] = mapA.get( a );
		l[ 1 ] = mapI.get( i );
		l[ 2 ] = mapC.get( c );
		l[ 3 ] = mapT.get( t );
	}

	private static final int numEntries( final ListImg< ModelLink > img )
	{
		int numEntries = 0;

		for ( final ModelLink l : img )
			if ( l != null )
				++numEntries;

		return numEntries;
	}

	private static final ListImg< ModelLink > createTable(
			final SpimData data,
			final List< ViewId > viewIds,
			final boolean sameModelTimePoints,
			final boolean sameModelChannels,
			final boolean sameModelIlluminations,
			final boolean sameModelAngles,
			final HashMap< TimePoint, Integer > mapT,
			final HashMap< Channel, Integer > mapC,
			final HashMap< Illumination, Integer > mapI,
			final HashMap< Angle, Integer > mapA )
	{
		final List< TimePoint > ts = SpimData2.getAllTimePointsSorted( data, viewIds );
		final List< Channel > cs = SpimData2.getAllChannelsSorted( data, viewIds );
		final List< Illumination > is = SpimData2.getAllIlluminationsSorted( data, viewIds );
		final List< Angle > as = SpimData2.getAllAnglesSorted( data, viewIds );

		final int nT = sameModelTimePoints ? 1 : ts.size();
		for ( int i = 0; i < ts.size(); ++i )
			mapT.put( ts.get( i ), sameModelTimePoints ? 0 : i );

		final int nC = sameModelChannels ? 1 : cs.size();
		for ( int i = 0; i < cs.size(); ++i )
			mapC.put( cs.get( i ), sameModelChannels ? 0 : i );

		final int nI = sameModelIlluminations ? 1 : is.size();
		for ( int i = 0; i < is.size(); ++i )
			mapI.put( is.get( i ), sameModelIlluminations ? 0 : i );

		final int nA = sameModelAngles ? 1 : as.size();
		for ( int i = 0; i < as.size(); ++i )
			mapA.put( as.get( i ), sameModelAngles ? 0 : i );

		// iterate first angles, then illums, then channels, then timepoints
		return new ListImgFactory< ModelLink >().create( new long[]{ nA, nI, nC, nT }, new ModelLink( null ) );
	}


	protected static final double[] parseString( String entry, final int numValues )
	{
		while ( entry.contains( "(" ) )
			entry.replace( "(", "" );

		while ( entry.contains( ")" ) )
			entry.replace( ")", "" );

		while ( entry.contains( "[" ) )
			entry.replace( "[", "" );

		while ( entry.contains( "]" ) )
			entry.replace( "]", "" );

		entry = entry.replaceAll( " ", "" ).trim();
		
		String[] entries = entry.split( "," );

		if ( entries.length != numValues )
		{
			IOFunctions.println( "Cannot parse: '" + entry + "', has " + entries.length + " numbers, but should be " + numValues );
			return null;
		}
		
		final double[] v = new double[ numValues ];
		
		for ( int j = 0; j < numValues; ++j )
			v[ j ] = Double.parseDouble( entries[ j ].trim() );
		
		return v;
	}

	public static void preConcatenateTransform( final SpimData spimData, final ViewId viewId, final AffineTransform3D model, final String name )
	{
		final ViewRegistrations viewRegistrations = spimData.getViewRegistrations();

		// update the view registration
		final ViewRegistration vr = viewRegistrations.getViewRegistration( viewId );
		final ViewTransform vt = new ViewTransformAffine( name, model );
		vr.preconcatenateTransform( vt );
	}
	
	public static void setModelToCalibration( final SpimData spimData, final ViewId viewId, final double minResolution )
	{
		setModelToIdentity( spimData, viewId );
		
		final ViewRegistrations viewRegistrations = spimData.getViewRegistrations();
		final ViewRegistration r = viewRegistrations.getViewRegistration( viewId );
		
		final ViewDescription viewDescription = spimData.getSequenceDescription().getViewDescription( 
				viewId.getTimePointId(), viewId.getViewSetupId() );

		VoxelDimensions voxelSize = ViewSetupUtils.getVoxelSizeOrLoad( viewDescription.getViewSetup(), viewDescription.getTimePoint(), spimData.getSequenceDescription().getImgLoader() );
		final double calX = voxelSize.dimension( 0 ) / minResolution;
		final double calY = voxelSize.dimension( 1 ) / minResolution;
		final double calZ = voxelSize.dimension( 2 ) / minResolution;
		
		final AffineTransform3D m = new AffineTransform3D();
		m.set( calX, 0.0f, 0.0f, 0.0f, 
			   0.0f, calY, 0.0f, 0.0f,
			   0.0f, 0.0f, calZ, 0.0f );
		final ViewTransform vt = new ViewTransformAffine( "calibration", m );
		r.preconcatenateTransform( vt );
	}

	public static void setModelToIdentity( final SpimData spimData, final ViewId viewId )
	{
		final ViewRegistrations viewRegistrations = spimData.getViewRegistrations();
		final ViewRegistration r = viewRegistrations.getViewRegistration( viewId );
		r.identity();
	}

	public static void applyAxis( final SpimData data )
	{
		ViewRegistrations viewRegistrations = data.getViewRegistrations();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
		{
			if ( vd.isPresent() )
			{
				final Angle a = vd.getViewSetup().getAngle();
				
				if ( a.hasRotation() )
				{
					final ViewRegistration vr = viewRegistrations.getViewRegistration( vd );

					final Dimensions dim = vd.getViewSetup().getSize();

					AffineTransform3D model = new AffineTransform3D();
					model.set(
							1, 0, 0, -dim.dimension( 0 )/2,
							0, 1, 0, -dim.dimension( 1 )/2,
							0, 0, 1, -dim.dimension( 2 )/2 );
					ViewTransform vt = new ViewTransformAffine( "Center view", model );
					vr.preconcatenateTransform( vt );

					final double[] tmp = new double[ 16 ];
					final double[] axis = a.getRotationAxis();
					final double degrees = -a.getRotationAngleDegrees();
					final Transform3D t = new Transform3D();
					final String d;

					if ( axis[ 0 ] == 1 && axis[ 1 ] == 0 && axis[ 2 ] == 0 )
					{
						t.rotX( Math.toRadians( degrees ) );
						d = "Rotation around x-axis by " + (-degrees) + " degrees";
					}
					else if ( axis[ 0 ] == 0 && axis[ 1 ] == 1 && axis[ 2 ] == 0 )
					{
						t.rotY( Math.toRadians( degrees ) );
						d = "Rotation around y-axis by " + (-degrees) + " degrees";
					}
					else if ( axis[ 0 ] == 1 && axis[ 0 ] == 0 && axis[ 2 ] == 1 )
					{
						t.rotZ( Math.toRadians( degrees ) );
						d = "Rotation around z-axis by " + (-degrees) + " degrees";
					}
					else
					{
						IOFunctions.println( "Arbitrary rotation axis not supported yet." );
						continue;
					}

					t.get( tmp );

					model = new AffineTransform3D();
					model.set( tmp[ 0 ], tmp[ 1 ], tmp[ 2 ], tmp[ 3 ],
							   tmp[ 4 ], tmp[ 5 ], tmp[ 6 ], tmp[ 7 ],
							   tmp[ 8 ], tmp[ 9 ], tmp[ 10 ], tmp[ 11 ] );

					vt = new ViewTransformAffine( d, model );
					vr.preconcatenateTransform( vt );
					vr.updateModel();
				}
			}
		}
	}

	public static AffineTransform3D rotationAngleAxis(double angle, double[] axis)
	{
		double[] u = toUnit( axis );
		double[] a = new double[12];

		// https://en.wikipedia.org/wiki/Rotation_formalisms_in_three_dimensions
		a[0] = (1.0 - Math.cos( angle )) * Math.pow( u[0], 2 ) + Math.cos( angle );
		a[1] = (1.0 - Math.cos( angle )) * u[0] * u[1] - u[2] * Math.sin( angle );
		a[2] = (1.0 - Math.cos( angle )) * u[0] * u[2] + u[1] * Math.sin( angle );

		a[4] = (1.0 - Math.cos( angle )) * u[0] * u[1] + u[2] * Math.sin( angle );
		a[5] = (1.0 - Math.cos( angle )) * Math.pow( u[1], 2 ) + Math.cos( angle );
		a[6] = (1.0 - Math.cos( angle )) * u[1] * u[2] - u[0] * Math.sin( angle );

		a[8] = (1.0 - Math.cos( angle )) * u[0] * u[2] - u[1] * Math.sin( angle );
		a[9] = (1.0 - Math.cos( angle )) * u[1] * u[2] + u[0] * Math.sin( angle );
		a[10] = (1.0 - Math.cos( angle )) * Math.pow( u[2], 2 ) + Math.cos( angle );

		final AffineTransform3D res = new AffineTransform3D();
		res.set( a );
		return res.copy();
		
	}

	public static double[] toUnit(double[] vec)
	{
		double len = 0;
		for (int i=0; i<vec.length; i++)
			len += Math.pow( vec[i], 2 );
		len = Math.sqrt( len );
		final double[] unit = new double[vec.length];
		for (int i=0; i<vec.length; i++)
			unit[i] = vec[i] / len;
		return unit;
	}

	public static void applyAxisGrouped(final SpimData data)
	{
		applyAxisGrouped( data, true );
	}

	public static void applyAxisGrouped(final SpimData data, boolean moveToOrigin)
	{
		final Map< Angle, Collection< Pair< Dimensions, AffineGet > > > viewsGroupedByAngle = new HashMap<>();
		final Map< Angle, double[] > angleCentersOfMasss = new HashMap<>();
		final ViewRegistrations viewRegistrations = data.getViewRegistrations();

		// Group by Angle
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
		{
			if ( vd.isPresent() )
			{
				final Angle a = vd.getViewSetup().getAngle();
				final ViewRegistration vr = viewRegistrations.getViewRegistration( vd );
				final Dimensions dim = vd.getViewSetup().getSize();

				if (!viewsGroupedByAngle.containsKey( a ))
					viewsGroupedByAngle.put( a, new ArrayList<>() );

				viewsGroupedByAngle.get( a ).add( new ValuePair<>( dim, vr.getModel() ) );
			}
		}

		viewsGroupedByAngle.forEach( (a, v) -> {angleCentersOfMasss.put( a, getCenterOfMass( v ));});

		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
		{
			if ( vd.isPresent() )
			{
				final Angle a = vd.getViewSetup().getAngle();
				final ViewRegistration vr = viewRegistrations.getViewRegistration( vd );
				final double[] axis = a.getRotationAxis();
				final double degrees = -a.getRotationAngleDegrees();

				// no axis defined
				if ( axis == null || Util.distance( new RealPoint( axis ), new RealPoint( new double[axis.length] ) ) < Double.MIN_VALUE )
					continue;
				final double[] u = toUnit( axis );
				double angle = Math.toRadians( degrees );

				// get center
				double[] center = angleCentersOfMasss.get( a );
				if (center == null)
					continue;

				// center translation
				AffineTransform3D translateToCenter = new AffineTransform3D();
				translateToCenter.set(
						1, 0, 0, center[0],
						0, 1, 0, center[1],
						0, 0, 1, center[2] );

				// (1) move CoM to origin
				final AffineTransform3D res = new AffineTransform3D();
				res.preConcatenate( translateToCenter.inverse() );

				// (2) rotate
				AffineTransform3D rot = rotationAngleAxis( angle, u );
				res.preConcatenate( rot );

				// (3) move back to old CoM (usless we want to stay at origin)
				if (!moveToOrigin)
					res.preConcatenate( translateToCenter );

				final String desc = "Rotation around axis " + Util.printCoordinates( u ) + " by " + (-degrees) + " degrees";
				final ViewTransform vt = new ViewTransformAffine( desc, res );
				vr.preconcatenateTransform( vt );
				vr.updateModel();
			}
		}
	}

	public static double[] getCenterOfMass(Collection< Pair< Dimensions, AffineGet > > views)
	{
		if (views.size() < 1)
			return null;

		final int nDims = views.iterator().next().getA().numDimensions();
		final double[] center = new double[nDims];
		final double[] vertex = new double[nDims];
		final double[] vertexTransformed = new double[nDims];
		int count = 0;

		for (final Pair< Dimensions, AffineGet > view : views)
		{
			for (int i = 0; i< (int) Math.pow( 2, nDims ); i++)
			{

				// get i'th vertex of (0, 0, ...) - (dim_0, dim_1, ...)
				int ii = i;
				for (int j = 0; j<nDims; j++)
				{
					vertex[j] = ii%2 == 0 ? 0 : view.getA().dimension( j );
					ii /= 2;
				}

				view.getB().apply( vertex, vertexTransformed );

				for (int j = 0; j<nDims; j++)
					center[j] += vertexTransformed[j];

				count++;
			}
		}

		for (int i = 0; i<nDims; i++)
			center[i] /= count;

		return center;
	}

	/*
	 * @param args
	 */
	public static void main( final String[] args )
	{
		//new BigDataViewerTransformationWindow( null );
		new Apply_Transformation().run( null );
	}
}
