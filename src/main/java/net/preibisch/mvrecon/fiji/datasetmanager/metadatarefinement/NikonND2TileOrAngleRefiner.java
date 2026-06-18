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
package net.preibisch.mvrecon.fiji.datasetmanager.metadatarefinement;

import java.util.List;

import loci.formats.IFormatReader;
import net.imglib2.realtransform.AffineTransform;
import net.imglib2.realtransform.AffineTransform2D;
import net.preibisch.mvrecon.fiji.datasetmanager.FileListDatasetDefinitionUtil.TileOrAngleInfo;

import ome.xml.meta.MetadataRetrieve;

public class NikonND2TileOrAngleRefiner implements TileOrAngleRefiner
{

	@Override
	public void refineTileOrAngleInfo(IFormatReader r, List< TileOrAngleInfo > infos)
	{
		Double rotation = null;
		Object tmp = r.getGlobalMetadata().get( "Rotate" );
		if (tmp != null)
			rotation = (Double) tmp;
		
		if (rotation != null)
		{
			rotation -= 90;
			AffineTransform2D tr = new AffineTransform2D();
			tr.rotate( -1.0 * rotation / 360.0 * 2 * Math.PI );
			
			for (TileOrAngleInfo info: infos)
			{
				
				double[] loc = new double[] {info.locationX == null ? 0 : info.locationX, info.locationY == null ? 0 : info.locationY};
				tr.apply( loc, loc );
				
				info.locationX = loc[0];
				info.locationY = loc[1];
			}
		}
		
	}

}
