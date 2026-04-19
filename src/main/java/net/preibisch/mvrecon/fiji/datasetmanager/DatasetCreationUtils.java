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
package net.preibisch.mvrecon.fiji.datasetmanager;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.Tile;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.legacy.io.IOFunctions;

public class DatasetCreationUtils
{

	/**
	 * Assembles the {@link ViewRegistration} object consisting of a list of {@link ViewRegistration}s for all {@link ViewDescription}s that are present
	 * 
	 * @param viewDescriptionList - map from View IDs to View Descriptions
	 * @param minResolution - the smallest resolution in any dimension (distance between two pixels in the output image will be that wide)
	 * @return the View Registrations
	 */
	public static ViewRegistrations createViewRegistrations( final Map< ViewId, ViewDescription > viewDescriptionList, final double minResolution )
	{
		final HashMap< ViewId, ViewRegistration > viewRegistrationMap = new HashMap< ViewId, ViewRegistration >();
	
		for ( final ViewDescription viewDescription : viewDescriptionList.values() )
			if ( viewDescription.isPresent() )
			{
				final ViewRegistration viewRegistration = new ViewRegistration( viewDescription.getTimePointId(), viewDescription.getViewSetupId() );
				final VoxelDimensions voxelSize = viewDescription.getViewSetup().getVoxelSize(); 
	
				final double calX = voxelSize.dimension( 0 ) / minResolution;
				final double calY = voxelSize.dimension( 1 ) / minResolution;
				final double calZ = voxelSize.dimension( 2 ) / minResolution;
	
				// 1st view transform: calibration := scaling to isotropic resolution (units of length -> minResolution) 
				final AffineTransform3D m = new AffineTransform3D();
				m.set(  calX, 0.0f, 0.0f, 0.0f,
						0.0f, calY, 0.0f, 0.0f,
						0.0f, 0.0f, calZ, 0.0f );
				final ViewTransform vt = new ViewTransformAffine( "calibration", m );
				viewRegistration.preconcatenateTransform( vt );
	
				// 2nd view transform: translation to tile location (Tile has physical unit locations -> we transform to minResolution units)
				final Tile tile = viewDescription.getViewSetup().getAttribute( Tile.class );
				if (tile.hasLocation()){
					final double shiftX = tile.getLocation()[0] / voxelSize.dimension( 0 ) * calX;
					final double shiftY = tile.getLocation()[1] / voxelSize.dimension( 1 ) * calY;
					final double shiftZ = tile.getLocation()[2] / voxelSize.dimension( 2 ) * calZ;
	
					final AffineTransform3D m2 = new AffineTransform3D();
					m2.set( 1.0f, 0.0f, 0.0f, shiftX,
							0.0f, 1.0f, 0.0f, shiftY,
							0.0f, 0.0f, 1.0f, shiftZ );
					final ViewTransform vt2 = new ViewTransformAffine( "Translation", m2 );
					viewRegistration.preconcatenateTransform( vt2 );
				}
				viewRegistrationMap.put( viewRegistration, viewRegistration );
			}
		return new ViewRegistrations( viewRegistrationMap );
	}

	/*
	 * Finds the minimal resolution in between all view descriptions, and makes sure all data is available
	 * Should be called before registration to make sure all metadata is right
	 * 
	 * @return - minimal resolution in all dimensions
	 */
	public static double minResolution(
			final SequenceDescription sequenceDescription,
			final Collection< ? extends ViewId > viewIdsToProcess )
	{
		double minResolution = Double.MAX_VALUE;

		for ( final ViewId viewId : viewIdsToProcess )
		{
			final ViewDescription vd = sequenceDescription.getViewDescription( 
					viewId.getTimePointId(), viewId.getViewSetupId() );

			if ( !vd.isPresent() )
				continue;

			ViewSetup setup = vd.getViewSetup();

			// load metadata to update the registrations if required
			// only use calibration as defined in the metadata
			if ( !setup.hasVoxelSize() )
			{
				VoxelDimensions voxelSize = sequenceDescription.getImgLoader().getSetupImgLoader( viewId.getViewSetupId() ).getVoxelSize( viewId.getTimePointId() );
				if ( voxelSize == null )
				{
					IOFunctions.println( "An error occured. Cannot load calibration for" +
							" timepoint: " + vd.getTimePoint().getName() +
							" angle: " + vd.getViewSetup().getAngle().getName() +
							" channel: " + vd.getViewSetup().getChannel().getName() +
							" illum: " + vd.getViewSetup().getIllumination().getName() );

					IOFunctions.println( "Quitting. Please set it manually when defining the dataset or by modifying the XML" );

					return Double.NaN;
				}
				setup.setVoxelSize( voxelSize );
			}

			if ( !setup.hasVoxelSize() )
			{
				IOFunctions.println( "An error occured. No calibration available for" +
						" timepoint: " + vd.getTimePoint().getName() +
						" angle: " + vd.getViewSetup().getAngle().getName() +
						" channel: " + vd.getViewSetup().getChannel().getName() +
						" illum: " + vd.getViewSetup().getIllumination().getName() );

				IOFunctions.println( "Quitting. Please set it manually when defining the dataset or by modifying the XML." );
				IOFunctions.println( "Note: if you selected to load calibration independently for each image, it should." );
				IOFunctions.println( "      have been loaded during interest point detection." );

				return Double.NaN;
			}

			VoxelDimensions voxelSize = setup.getVoxelSize();
			final double calX = voxelSize.dimension( 0 );
			final double calY = voxelSize.dimension( 1 );
			final double calZ = voxelSize.dimension( 2 );

			minResolution = Math.min( minResolution, calX );
			minResolution = Math.min( minResolution, calY );
			minResolution = Math.min( minResolution, calZ );
		}

		return minResolution;
	}

}
