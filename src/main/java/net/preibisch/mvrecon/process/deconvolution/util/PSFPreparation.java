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
package net.preibisch.mvrecon.process.deconvolution.util;

import java.util.ArrayList;
import java.util.HashMap;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunction;
import net.preibisch.mvrecon.fiji.spimdata.pointspreadfunctions.PointSpreadFunctions;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.psf.PSFCombination;
import net.preibisch.mvrecon.process.psf.PSFExtraction;

public class PSFPreparation
{
	public static < V extends ViewId > HashMap< Group< V >, ArrayImg< FloatType, ? > > loadGroupTransformPSFs(
			final PointSpreadFunctions pointSpreadFunctions,
			final ProcessInputImages< V > fusion,
			final boolean sameSizeForAll )
	{
		final HashMap< ViewId, PointSpreadFunction > rawPSFs = pointSpreadFunctions.getPointSpreadFunctions();
		final HashMap< Group< V >, ArrayImg< FloatType, ? > > psfs = new HashMap<>();

		for ( final Group< V > virtualView : fusion.getGroups() )
		{
			final ArrayList< Img< FloatType > > viewPsfs = new ArrayList<>();
	
			for ( final V view : virtualView )
			{
				// load PSF
				final ArrayImg< FloatType, ? > psf = rawPSFs.get( view ).getPSFCopyArrayImg();

				// remember the normalized, transformed version (including downsampling!)
				viewPsfs.add( PSFExtraction.getTransformedNormalizedPSF( psf, fusion.getDownsampledModels().get( view ) ) );

				//DisplayImage.getImagePlusInstance( viewPsfs.get( viewPsfs.size() - 1 ), false, "psf " + Group.pvid( view ), 0, 1 ).show();
			}

			// compute the PSF for a group by averaging over the minimal size of all inputs
			// the sizes can be different if the transformations are not tranlations but affine.
			// they should, however, not differ significantly but only combine views that have
			// basically the same transformation (e.g. angle 0 vs 180, or before after correction of chromatic abberations)
			psfs.put( virtualView, (ArrayImg< FloatType, ? >)PSFCombination.computeAverageImage( viewPsfs, new ArrayImgFactory< FloatType >(), false ) );

			//DisplayImage.getImagePlusInstance( psfs.get( virtualView ), false, "psf " + virtualView, 0, 1 ).show();
		}

		if ( sameSizeForAll )
		{
			final long[] maxDim = new long[ psfs.values().iterator().next().numDimensions() ];

			for ( final ArrayImg< FloatType, ? > psf : psfs.values() )
				for ( int d = 0; d < maxDim.length; ++d )
					maxDim[ d ] = Math.max( maxDim[ d ], psf.dimension( d ) );

			for ( final Group< V > virtualView : psfs.keySet() )
			{
				final ArrayImg< FloatType, ? > psf = psfs.get( virtualView );
				psfs.put( virtualView, (ArrayImg< FloatType, ? >)PSFCombination.makeSameSize( psf, maxDim ) );
			}
		}

		return psfs;
	}
}
