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
import bdv.util.BdvOverlay;
import bdv.util.BdvStackSource;
import java.awt.Color;
import java.awt.Graphics2D;
import java.io.Serializable;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.util.ColorStream;
import net.imglib2.realtransform.AffineTransform3D;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;

public class VisualizeCoefficients {

	static class CoefficientsOverlay extends BdvOverlay {

		private final TileInfo[] tileInfos;
		private final Color[] colors;

		public CoefficientsOverlay(final TileInfo... tileInfos) {
			this.tileInfos = tileInfos;

			colors = new Color[tileInfos.length];
			final DistinctColorStream cs = new DistinctColorStream();
			Arrays.setAll(colors, i -> cs.getNextColor());
		}

		@Override
		protected void draw(final Graphics2D graphics) {
			final AffineTransform3D transform = new AffineTransform3D();

			for (int i = 0; i < tileInfos.length; i++) {
				final TileInfo tileInfo = tileInfos[i];
				final Color color = colors[i];

				info.getViewerTransform(transform);
				transform.concatenate(tileInfo.model);
				transform.concatenate(tileInfo.coeffCenterTransform);

				final double[] lPos = new double[3];
				final double[] gPos = new double[3];
				for (int lx = 0; lx < tileInfo.numCoeffs[0]; ++lx) {
					lPos[0] = lx;
					for (int ly = 0; ly < tileInfo.numCoeffs[1]; ++ly) {
						lPos[1] = ly;
						for (int lz = 0; lz < tileInfo.numCoeffs[2]; ++lz) {
							lPos[2] = lz;
							transform.apply(lPos, gPos);
							final double size = getPointSize(gPos);
							final int x = (int) (gPos[0] - 0.5 * size);
							final int y = (int) (gPos[1] - 0.5 * size);
							final int w = (int) size;
							graphics.setColor(getColor(gPos, color));
							graphics.fillOval(x, y, w, w);

						}
					}
				}
			}
		}

		private Color getColor(final double[] gPos, final Color col) {
			int alpha = 255 - (int) Math.round(Math.abs(gPos[2]));
			if (alpha < 64)
				alpha = 64;
			return new Color(col.getRed(), col.getGreen(), col.getBlue(), alpha);
		}

		private double getPointSize(final double[] gPos) {
			final double maxdist = 100;
			final double maxsize = 10;
			final double minsize = 2;
			return Math.max(maxsize * (maxdist - Math.abs(gPos[2])) / maxdist, minsize);
		}
	}

	public static void main(String[] args) throws Exception {
		final URI xml = new URI("file:/Users/pietzsch/Desktop/data/Janelia/keller-shadingcorrected/dataset.xml");
		final XmlIoSpimData2 io = new XmlIoSpimData2();
		final SpimData2 spimData = io.load(xml);

		final List<BdvStackSource<?>> stackSources = BdvFunctions.show(spimData, Bdv.options());
		final Bdv bdv = stackSources.get(0);

		final TileInfo[] tiles = spimData.getSequenceDescription()
				.getViewSetupsOrdered()
				.stream()
				.map(v -> new ViewId(0, v.getId()))
				.map(viewId -> new TileInfo(new int[] {8, 8, 8}, spimData, viewId))
				.toArray(TileInfo[]::new);
		final BdvOverlay overlay = new CoefficientsOverlay(Arrays.copyOf(tiles, 3));
		BdvFunctions.showOverlay(overlay, "coefficients", Bdv.options().addTo(bdv));
	}

	/**
	 * Generate a stream of `random' saturated RGB colors with all colors being maximally distinct from each other.
	 *
	 * This variant of Stephan's implementation (see {@link ColorStream}) maintains a color index
	 * within each instance so that multiple instances can be used within one JVM.
	 *
	 * @author Eric Trautman
	 */
	static class DistinctColorStream
			extends ColorStream
			implements Serializable {

		private long index;

		public DistinctColorStream() {
			this.index = -1;
		}

		/**
		 * Copied from {@link ColorStream#get}.
		 */
		public int getNextArgb() {
			++index;
			double x = goldenRatio * index;
			x -= ( long )x;
			x *= 6.0;
			final int k = ( int )x;
			final int l = k + 1;
			final double u = x - k;
			final double v = 1.0 - u;

			final int r = interpolate( rs, k, l, u, v );
			final int g = interpolate( gs, k, l, u, v );
			final int b = interpolate( bs, k, l, u, v );

			return argb( r, g, b );
		}

		public Color getNextColor() {
			return new Color(getNextArgb());
		}

	}
}
