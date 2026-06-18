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
package net.preibisch.mvrecon.fiji.spimdata.intensityadjust;

import mpicbg.models.AffineModel1D;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import mpicbg.spim.data.sequence.ViewId;

import java.util.ArrayList;
import java.util.Collections;

import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_SETUP_ATTRIBUTE_NAME;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_TIMEPOINT_ATTRIBUTE_NAME;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_VALUE_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTS_SETUP_ATTRIBUTE_NAME;
import static net.preibisch.mvrecon.fiji.spimdata.interestpoints.XmlKeysInterestPoints.VIEWINTERESTPOINTS_TIMEPOINT_ATTRIBUTE_NAME;

import org.jdom2.Element;

public class XmlIoIntensityAdjustments extends XmlIoSingleton< IntensityAdjustments >
{
	public XmlIoIntensityAdjustments()
	{
		super( INTENSITYADJ_TAG, IntensityAdjustments.class );
		handledTags.add( INTENSITYADJ_TAG );
	}

	public Element toXml( final IntensityAdjustments intensityAdustments )
	{
		final Element elem = super.toXml();

		final ArrayList< ViewId > viewIds = new ArrayList<>( intensityAdustments.getIntensityAdjustments().keySet() );
		Collections.sort( viewIds );

		for ( final ViewId viewId : viewIds )
			elem.addContent( affineModel1dToXml( viewId, intensityAdustments.getIntensityAdjustments().get( viewId ) ) );

		return elem;
	}

	public IntensityAdjustments fromXml( final Element allIntensityAdjustments ) throws SpimDataException
	{
		final IntensityAdjustments intensityAdjustments = super.fromXml( allIntensityAdjustments );

		for ( final Element intensityAdjustmentElement : allIntensityAdjustments.getChildren( INTENSITYADJ_TAG ) )
		{
			final int timepointId = Integer.parseInt( intensityAdjustmentElement.getAttributeValue( INTENSITYADJ_TIMEPOINT_ATTRIBUTE_NAME ) );
			final int setupId = Integer.parseInt( intensityAdjustmentElement.getAttributeValue( INTENSITYADJ_SETUP_ATTRIBUTE_NAME ) );

			final double[] modelArray = XmlHelpers.getDoubleArray( intensityAdjustmentElement, INTENSITYADJ_VALUE_TAG );

			final AffineModel1D model = new AffineModel1D();
			model.set( modelArray[ 0 ], modelArray[ 1 ] );

			intensityAdjustments.addIntensityAdjustments( new ViewId( timepointId, setupId ), model );
		}

		return intensityAdjustments;
	}

	protected Element affineModel1dToXml( final ViewId viewId, final AffineModel1D model )
	{
		final Element elem = new Element( INTENSITYADJ_TAG );

		elem.setAttribute( INTENSITYADJ_TIMEPOINT_ATTRIBUTE_NAME, Integer.toString( viewId.getTimePointId() ) );
		elem.setAttribute( INTENSITYADJ_SETUP_ATTRIBUTE_NAME, Integer.toString( viewId.getViewSetupId() ) );

		final double[] values = new double[ 2 ];
		model.toArray( values );
		elem.addContent( XmlHelpers.doubleArrayElement( INTENSITYADJ_VALUE_TAG, values ) );

		return elem;
	}
}
