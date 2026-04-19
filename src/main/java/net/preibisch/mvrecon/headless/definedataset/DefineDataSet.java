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

import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewRegistrations;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.registration.ViewTransformAffine;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.realtransform.AffineTransform3D;

import java.util.HashMap;
import java.util.Map;

/**
 * General class for LightSheetZ1 and MicroManager
 */
public class DefineDataSet
{
	/**
	 * Assembles the {@link mpicbg.spim.data.registration.ViewRegistration} object consisting of a list of {@link mpicbg.spim.data.registration.ViewRegistration}s for all {@link mpicbg.spim.data.sequence.ViewDescription}s that are present
	 *
	 * @param viewDescriptionList - the view description map
	 * @param minResolution - the smallest resolution in any dimension (distance between two pixels in the output image will be that wide)
	 * @return - created ViewRegsitrations
	 */
	protected static ViewRegistrations createViewRegistrations( final Map< ViewId, ViewDescription > viewDescriptionList, final double minResolution )
	{
		final HashMap< ViewId, ViewRegistration > viewRegistrationList = new HashMap< ViewId, ViewRegistration >();

		for ( final ViewDescription viewDescription : viewDescriptionList.values() )
			if ( viewDescription.isPresent() )
			{
				final ViewRegistration viewRegistration = new ViewRegistration( viewDescription.getTimePointId(), viewDescription.getViewSetupId() );

				final VoxelDimensions voxelSize = viewDescription.getViewSetup().getVoxelSize();

				final double calX = voxelSize.dimension( 0 ) / minResolution;
				final double calY = voxelSize.dimension( 1 ) / minResolution;
				final double calZ = voxelSize.dimension( 2 ) / minResolution;

				final AffineTransform3D m = new AffineTransform3D();
				m.set( calX, 0.0f, 0.0f, 0.0f,
						0.0f, calY, 0.0f, 0.0f,
						0.0f, 0.0f, calZ, 0.0f );
				final ViewTransform vt = new ViewTransformAffine( "calibration", m );
				viewRegistration.preconcatenateTransform( vt );

				viewRegistrationList.put( viewRegistration, viewRegistration );
			}

		return new ViewRegistrations( viewRegistrationList );
	}
}
