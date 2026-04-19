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
package net.preibisch.mvrecon.process.fusion.intensity;

import bdv.util.Bdv;
import bdv.util.BdvFunctions;
import bdv.util.BdvSource;
import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import mpicbg.spim.data.SpimData;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.MultiResolutionSetupImgLoader;
import mpicbg.spim.data.sequence.SetupImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.util.Cast;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import util.URITools;

public class ApplyCoefficientsPlayground {

	public static void main(String[] args) throws SpimDataException, URISyntaxException {

		final URI xml = new URI("file:/Users/pietzsch/Desktop/data/Janelia/keller-shadingcorrected/dataset.xml");
		final XmlIoSpimData2 io = new XmlIoSpimData2();
		final SpimData2 spimData = io.load(xml);

		final Tile tile = new Tile(spimData, new ViewId(0, 0));
//		System.out.println("type = " + tile.getImage(0).getType().getClass());

		final RandomAccessibleInterval<UnsignedShortType> image = tile.getImage(0);

		final BdvSource source0 = BdvFunctions.show(image, "image");
		source0.setDisplayRange(0, 2000);
		source0.setDisplayRangeBounds(0, 2000);


		final URI uri = new File("/Users/pietzsch/Desktop/coefficients.n5").toURI();
		final N5Reader n5Reader = URITools.instantiateN5Reader(StorageFormat.N5, uri);
		final String dataset = "coefficients";
		final Coefficients coefficients = CoefficientsIO.load(n5Reader, dataset);
		n5Reader.close();

		final RandomAccessibleInterval<UnsignedShortType> corrected = BlockSupplier.of(image)
				.andThen(FastLinearIntensityMap.linearIntensityMap(coefficients, image))
				.toCellImg(image.dimensionsAsLongArray(), 64);

		final BdvSource source1 = BdvFunctions.show(corrected, "corrected", Bdv.options().addTo(source0));
		source1.setDisplayRange(0, 2000);
		source1.setDisplayRangeBounds(0, 2000);
	}


	private static class Tile {

		/**
		 * Image dimensions (at full resolution)
		 */
		final Dimensions dimensions;

		/**
		 * Image-to-world transform (at full resolution)
		 */
		final AffineTransform3D model;

		private final MultiResolutionSetupImgLoader<?> setupImgLoader;

		private final int timepointId;

		Tile(final SpimData spimData, final ViewId view) {
			final ImgLoader imgLoader = spimData.getSequenceDescription().getImgLoader();
			final SetupImgLoader<?> sil = imgLoader.getSetupImgLoader(view.getViewSetupId());
			if (!(sil instanceof MultiResolutionSetupImgLoader)) {
				throw new IllegalArgumentException();
			}
			setupImgLoader = Cast.unchecked(sil);
			timepointId = view.getTimePointId();
			this.dimensions = setupImgLoader.getImageSize(timepointId);
			this.model = spimData.getViewRegistrations().getViewRegistration(view).getModel();
		}

		/**
		 * Get the subsampling factors, indexed by resolution level and dimension.
		 * For example, a subsampling factor of 2 means the respective resolution
		 * level is scaled by 0.5 in the respective dimension.
		 *
		 * @return subsampling factors, indexed by resolution level and dimension.
		 */
		double[][] getMipmapResolutions() {
			return setupImgLoader.getMipmapResolutions();
		}

		/**
		 * Get the transformation from coordinates of the sub-sampled image of a a
		 * resolution level to coordinates of the full resolution image. The array
		 * of transforms is indexed by resolution level.
		 *
		 * @return array with one transformation for each mipmap level.
		 */
		AffineTransform3D[] getMipmapTransforms() {
			return setupImgLoader.getMipmapTransforms();
		}

		/**
		 * Get number of resolution levels.
		 *
		 * @return number of resolution levels.
		 */
		int numMipmapLevels() {
			return setupImgLoader.numMipmapLevels();
		}

		<T extends RealType<T>> RandomAccessibleInterval<T> getImage(final int mipmapLevel) {
			return Cast.unchecked(setupImgLoader.getImage(timepointId, mipmapLevel));
		}
	}
}
