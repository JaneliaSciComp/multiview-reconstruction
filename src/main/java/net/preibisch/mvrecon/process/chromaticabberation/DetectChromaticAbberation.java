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
package net.preibisch.mvrecon.process.chromaticabberation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import ij.ImageJ;
import mpicbg.models.AffineModel3D;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.sequence.Channel;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.util.ValuePair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.MatcherPairwiseTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.geometrichashing.GeometricHashingPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.icp.IterativeClosestPointParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSACParameters;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMPairwise;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMParameters;

public class DetectChromaticAbberation
{
	public static < V extends ViewId > ArrayList< Pair< V, V > > identifyViewIdPairs(
			final SpimData2 spimData,
			final List< V > viewIds,
			final Pair< Channel, Channel > channelPair )
	{
		final SequenceDescription seq = spimData.getSequenceDescription();

		final Channel cA = channelPair.getA();
		final Channel cB = channelPair.getB();

		final ArrayList< Pair< V, V > > pairs = new ArrayList<>();

		for ( int v1 = 0; v1 < viewIds.size() - 1; ++v1 )
			for ( int v2 = v1 + 1; v2 < viewIds.size(); ++v2 )
			{
				final V view1 = viewIds.get( v1 );
				final V view2 = viewIds.get( v2 );

				final ViewDescription vd1 = seq.getViewDescription( view1 );
				final ViewDescription vd2 = seq.getViewDescription( view2 );

				final Channel c1 = vd1.getViewSetup().getChannel();
				final Channel c2 = vd2.getViewSetup().getChannel();

				if ( c1.getId() == cA.getId() && c2.getId() == cB.getId() || c1.getId() == cB.getId() && c2.getId() == cA.getId() )
				{
					boolean misMatch = false;

					for ( final Entity e1 : vd1.getViewSetup().getAttributes().values() )
						for ( final Entity e2 : vd2.getViewSetup().getAttributes().values() )
							if ( e1.getClass().equals( e2.getClass() ) && !e1.getClass().equals( Channel.class ) && e1.getId() != e2.getId() )
								misMatch = true;

					if ( !misMatch )
						pairs.add( new ValuePair<>( view1, view2 ) );
				}
			}

		return pairs;
	}

	/*
	 * This is very similar to Group.combineBy( Channel ), but returns pairs of channels (not all together)
	 */
	public static < V extends ViewId > ArrayList< Pair< Channel, Channel > > identifyChannelPairs( final SpimData2 spimData, final List< V > viewIds )
	{
		final SequenceDescription seq = spimData.getSequenceDescription();

		final HashSet< Channel > channelSet = new HashSet<>();

		for ( final V viewId : viewIds )
			channelSet.add( seq.getViewDescription( viewId ).getViewSetup().getChannel() );

		final ArrayList< Channel > channels = new ArrayList<>( channelSet );
		Collections.sort( channels );

		final ArrayList< Pair< Channel, Channel > > channelPairs = new ArrayList<>();

		for ( int c1 = 0; c1 < channels.size() - 1; ++c1 )
			for ( int c2 = c1 + 1; c2 < channels.size(); ++c2 )
				channelPairs.add( new ValuePair< Channel, Channel >( channels.get( c1 ), channels.get( c2 ) ) );

		return channelPairs;
	}

}
