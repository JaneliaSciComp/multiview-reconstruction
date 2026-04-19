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
package net.preibisch.mvrecon.process.interestpointremoval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPointLists;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.ViewInterestPoints;

public class CreateInterestPointsFromCorrespondences
{
	public static boolean createFor(
			final SpimData2 spimData,
			final Collection< ? extends ViewId > viewIds,
			final CreateFromCorrespondencesParameters params )
	{
		final ViewInterestPoints vip = spimData.getViewInterestPoints();

		for ( final ViewId viewId : viewIds )
		{
			final ViewDescription vd = spimData.getSequenceDescription().getViewDescription( viewId );

			final ViewInterestPointLists vipl = vip.getViewInterestPointLists( viewId );
			final InterestPoints oldIpl = vipl.getInterestPointList( params.getCorrespondingLabel() );

			if ( oldIpl == null )
				continue;

			final ArrayList< InterestPoint > newIPs = new ArrayList<>();

			final Map< Integer, InterestPoint > oldIPs = oldIpl.getInterestPointsCopy();

			final HashSet< Integer > existingPoints = new HashSet<>();

			int id = 0;
			for ( final CorrespondingInterestPoints cp : oldIpl.getCorrespondingInterestPointsCopy() )
			{
				final int oldId = cp.getDetectionId();

				if ( !existingPoints.contains( oldId ) )
				{
					existingPoints.add( oldId );
					final InterestPoint ip = oldIPs.get( oldId );
					newIPs.add( new InterestPoint( id++, ip.getL() ) );
				}
			}

			final InterestPoints newIpl = InterestPoints.newInstance( oldIpl.getBasePath(), viewId, params.getNewLabel() );
			/*final InterestPointList newIpl = new InterestPointList(
					oldIpl.getBaseDir(),
					new File(
							oldIpl.getFile().getParentFile(),
							"tpId_" + viewId.getTimePointId() + "_viewSetupId_" + viewId.getViewSetupId() + "." + params.getNewLabel() ) );*/

			newIpl.setInterestPoints( newIPs );
			newIpl.setCorrespondingInterestPoints( new ArrayList<>() );
			newIpl.setParameters( "correspondences from '" + params.getCorrespondingLabel() + "'" );

			vipl.addInterestPointList( params.getNewLabel(), newIpl );

			IOFunctions.println( new Date( System.currentTimeMillis() ) + ": TP=" + vd.getTimePointId() + " ViewSetup=" + vd.getViewSetupId() + 
					", Detections: " + oldIpl.getInterestPointsCopy().size() + " >>> " + newIpl.getInterestPointsCopy().size() );
		}

		return true;
	}
}
