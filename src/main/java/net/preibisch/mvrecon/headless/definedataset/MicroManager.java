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
package net.preibisch.mvrecon.headless.definedataset;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;

import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.sequence.Angle;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.Illumination;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MissingViews;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.TimePoints;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.plugin.Apply_Transformation;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBoxes;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.MicroManagerImgLoader;
import net.preibisch.mvrecon.fiji.spimdata.intensityadjust.IntensityAdjustments;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.fiji.spimdata.stitchingresults.StitchingResults;

/**
 * DataSet definition for MicroManager
 */
public class MicroManager extends DefineDataSet
{
	public static SpimData2 createDataset( final String tiffFile, final DefineDataSetParameters params )
	{
		MultipageTiffReader reader = null;
		File mmFile = new File( tiffFile );

		try
		{
			reader = new MultipageTiffReader( mmFile );
		}
		catch ( IOException e )
		{
			IOFunctions.println( "Failed to analyze file '" + mmFile.getAbsolutePath() + "': " + e );
			return null;
		}
		final ArrayList< String > angles = new ArrayList< String >();

		if ( params.angles == null )
		{
			throw new NullPointerException( "angles[] are not provided." );
		}

		for ( int a = 0; a < reader.numAngles(); ++a )
			angles.add( params.angles[a] );
		reader.setAngleNames( angles );

		if ( params.channels == null )
		{
			throw new NullPointerException( "channels[] are not provided." );
		}
		final ArrayList< String > channels = new ArrayList< String >();
		for ( int c = 0; c < reader.numChannels(); ++c )
			channels.add( params.channels[c] );
		reader.setChannelNames( channels );

		reader.setCalX( params.pixelDistanceX );
		reader.setCalY( params.pixelDistanceY );
		reader.setCalZ( params.pixelDistanceZ );

		reader.setCalUnit( params.pixelUnit );

		switch ( params.rotationAround )
		{
			case X_Axis:
				reader.setRotAxis( new double[]{ 1, 0, 0 } );
				break;
			case Y_Axis:
				reader.setRotAxis( new double[]{ 0, 1, 0 } );
				break;
			case Z_Axis:
				reader.setRotAxis( new double[]{ 0, 0, 1 } );
				break;
		}

		reader.setApplyAxis( params.applyAxis );

		final String directory = mmFile.getParent();

		final SequenceDescription sequenceDescription = createSequenceDescription( reader, mmFile );

		// get the minimal resolution of all calibrations
		final double minResolution = Math.min( Math.min( reader.calX(), reader.calY() ), reader.calZ() );

		IOFunctions.println( "Minimal resolution in all dimensions is: " + minResolution );
		IOFunctions.println( "(The smallest resolution in any dimension; the distance between two pixels in the output image will be that wide)" );

		// create the initial view registrations (they are all the identity transform)
		final ViewRegistrations viewRegistrations = createViewRegistrations( sequenceDescription.getViewDescriptions(), minResolution );

		// create the initial view interest point object
		final ViewInterestPoints viewInterestPoints = new ViewInterestPoints();
		//viewInterestPoints.createViewInterestPoints( sequenceDescription.getViewDescriptions() );

		// finally create the SpimData itself based on the sequence description and the view registration
		final SpimData2 spimData = new SpimData2( new File( directory ).toURI(), sequenceDescription, viewRegistrations, viewInterestPoints, new BoundingBoxes(), new PointSpreadFunctions(), new StitchingResults(), new IntensityAdjustments() );

		if ( reader.applyAxis() )
			Apply_Transformation.applyAxis( spimData );

		try { reader.close(); } catch (IOException e) { IOFunctions.println( "Could not close file '" + mmFile.getAbsolutePath() + "': " + e ); }

		return spimData;
	}

	/**
	 * Create sequence description for MultipageTiffReader.
	 *
	 * @param meta the meta
	 * @param mmFile the micromanager file
	 * @return the sequence description
	 */
	public static SequenceDescription createSequenceDescription( final MultipageTiffReader meta, final File mmFile  )
	{
		// assemble timepints, viewsetups, missingviews and the imgloader
		final ArrayList< TimePoint > timepoints = new ArrayList< TimePoint >();

		for ( int t = 0; t < meta.numTimepoints(); ++t )
			timepoints.add( new TimePoint( t ) );


		final ArrayList< Channel > channels = new ArrayList< Channel >();
		for ( int c = 0; c < meta.numChannels(); ++c )
			channels.add( new Channel( c, meta.channelName( c ) ) );

		final ArrayList< Illumination > illuminations = new ArrayList< Illumination >();
		for ( int i = 0; i < meta.numPositions(); ++i )
			illuminations.add( new Illumination( i, String.valueOf( i ) ) );

		final ArrayList< Angle > angles = new ArrayList< Angle >();
		for ( int a = 0; a < meta.numAngles(); ++a )
		{
			final Angle angle = new Angle( a, meta.rotationAngle( a ) );

			try
			{
				final double degrees = Double.parseDouble( meta.rotationAngle( a ) );
				double[] axis = meta.rotationAxis();

				if ( axis != null && !Double.isNaN( degrees ) &&  !Double.isInfinite( degrees ) )
					angle.setRotation( axis, degrees );
			}
			catch ( Exception e ) {};

			angles.add( angle );
		}

		final ArrayList< ViewSetup > viewSetups = new ArrayList< ViewSetup >();
		for ( final Channel c : channels )
			for ( final Illumination i : illuminations )
				for ( final Angle a : angles )
				{
					final VoxelDimensions voxelSize = new FinalVoxelDimensions( meta.calUnit(), meta.calX(), meta.calY(), meta.calZ() );
					final Dimensions dim = new FinalDimensions( new long[]{ meta.width(), meta.height(), meta.depth() } );
					viewSetups.add( new ViewSetup( viewSetups.size(), null, dim, voxelSize, c, a, i ) );
				}

		final MissingViews missingViews = null;

		// instantiate the sequencedescription
		final SequenceDescription sequenceDescription = new SequenceDescription( new TimePoints( timepoints ), viewSetups, null, missingViews );
		final ImgLoader imgLoader = new MicroManagerImgLoader( mmFile, sequenceDescription );
		sequenceDescription.setImgLoader( imgLoader );

		// instantiate the sequencedescription
		return sequenceDescription;
	}
}
