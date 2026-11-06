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
package net.preibisch.mvrecon.fiji.spimdata.intensityadjust;

import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_CONTAINER_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_FORMAT_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_SETUP_ATTRIBUTE_NAME;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_TAG;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_TIMEPOINT_ATTRIBUTE_NAME;
import static net.preibisch.mvrecon.fiji.spimdata.intensityadjust.XmlKeysIntensityAdjustments.INTENSITYADJ_VALUE_TAG;

import java.net.URI;
import java.util.Collection;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.jdom2.Element;

import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.base.XmlIoSingleton;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;

/**
 * {@code <IntensityAdjustments>} holds the path (relative to the XML) and format of the coefficients container.
 * Older XMLs list one affine intensity mapping per view instead; those are read as coefficients with a single
 * region (the same mapping) and written to a container on the next save.
 */
public class XmlIoIntensityAdjustments extends XmlIoSingleton< IntensityAdjustments >
{
	public XmlIoIntensityAdjustments()
	{
		super( INTENSITYADJ_TAG, IntensityAdjustments.class );
		handledTags.add( INTENSITYADJ_TAG );
	}

	/** call after {@link IntensityAdjustments#save}, which decides where the coefficients are */
	public Element toXml( final IntensityAdjustments intensityAdjustments, final URI basePath )
	{
		final Element elem = super.toXml();

		final URI container = intensityAdjustments.getContainer();
		if ( container != null )
		{
			elem.addContent( XmlHelpers.pathElementURI( INTENSITYADJ_CONTAINER_TAG, container, basePath ) );
			elem.addContent( XmlHelpers.textElement( INTENSITYADJ_FORMAT_TAG, intensityAdjustments.getFormat().name() ) );
		}

		return elem;
	}

	/**
	 * @param views the views whose coefficients may be in the container
	 */
	public IntensityAdjustments fromXml( final Element allIntensityAdjustments, final URI basePath, final Collection< ? extends ViewId > views ) throws SpimDataException
	{
		final IntensityAdjustments intensityAdjustments = super.fromXml( allIntensityAdjustments );

		if ( allIntensityAdjustments.getChild( INTENSITYADJ_CONTAINER_TAG ) != null )
		{
			final URI container = XmlHelpers.loadPathURI( allIntensityAdjustments, INTENSITYADJ_CONTAINER_TAG, basePath );
			final StorageFormat format = StorageFormat.valueOf( XmlHelpers.getText( allIntensityAdjustments, INTENSITYADJ_FORMAT_TAG ) );
			intensityAdjustments.setContainer( container, format, views );
		}

		// legacy: y = m * x + t per view
		for ( final Element legacy : allIntensityAdjustments.getChildren( INTENSITYADJ_TAG ) )
		{
			final int timepointId = Integer.parseInt( legacy.getAttributeValue( INTENSITYADJ_TIMEPOINT_ATTRIBUTE_NAME ) );
			final int setupId = Integer.parseInt( legacy.getAttributeValue( INTENSITYADJ_SETUP_ATTRIBUTE_NAME ) );
			final double[] m = XmlHelpers.getDoubleArray( legacy, INTENSITYADJ_VALUE_TAG );
			intensityAdjustments.put( new ViewId( timepointId, setupId ), new Coefficients( new double[][] { { m[ 0 ] }, { m[ 1 ] } }, 1, 1, 1 ) );
		}

		return intensityAdjustments;
	}
}
