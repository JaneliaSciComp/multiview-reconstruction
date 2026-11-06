package net.preibisch.mvrecon.process.fusion.intensity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.integer.UnsignedShortType;

/**
 * The optional {@link Coefficients#threshold()}: voxels below it must be left alone,
 * and it has to survive a round trip through N5.
 */
public class CoefficientsThresholdTest {

	/** one region, dst = 2 * src */
	private static Coefficients doubling() {
		return new Coefficients(new double[][] { { 2.0 }, { 0.0 } }, 1, 1, 1);
	}

	private static RandomAccessibleInterval<UnsignedShortType> image() {
		return ArrayImgs.unsignedShorts(new short[] { 50, 200, 99, 100 }, 4, 1, 1);
	}

	private static short[] apply(final Coefficients coefficients) {
		final RandomAccessibleInterval<UnsignedShortType> in = image();
		final RandomAccessibleInterval<UnsignedShortType> out = BlockSupplier.of(in)
				.andThen(FastLinearIntensityMap.linearIntensityMap(coefficients, in))
				.toCellImg(in.dimensionsAsLongArray(), 4);

		final short[] values = new short[4];
		final net.imglib2.RandomAccess<UnsignedShortType> access = out.randomAccess();
		for (int x = 0; x < 4; ++x) {
			access.setPosition(new long[] { x, 0, 0 });
			values[x] = (short) access.get().get();
		}
		return values;
	}

	@Test
	public void noThresholdCorrectsEveryVoxel() {
		final short[] values = apply(doubling());
		assertEquals(100, values[0]);
		assertEquals(400, values[1]);
		assertEquals(198, values[2]);
		assertEquals(200, values[3]);
	}

	@Test
	public void thresholdLeavesBackgroundUnchanged() {
		final short[] values = apply(doubling().withThreshold(100));
		assertEquals(50, values[0], "below the threshold, must be untouched");
		assertEquals(400, values[1], "above the threshold, must be corrected");
		assertEquals(99, values[2], "just below the threshold, must be untouched");
		assertEquals(200, values[3], "exactly at the threshold, must be corrected");
	}

	@Test
	public void defaultIsNoThreshold() {
		assertTrue(Double.isNaN(doubling().threshold()));
	}

	@Test
	public void thresholdSurvivesN5RoundTrip(@TempDir final Path tmp) {
		final String path = tmp.resolve("coefficients.n5").toString();

		try (final N5FSWriter writer = new N5FSWriter(path)) {
			CoefficientsIO.save(doubling().withThreshold(123.5), writer, "with");
			CoefficientsIO.save(doubling(), writer, "without");
		}

		try (final N5FSReader reader = new N5FSReader(path)) {
			assertEquals(123.5, CoefficientsIO.load(reader, "with").threshold());
			assertTrue(Double.isNaN(CoefficientsIO.load(reader, "without").threshold()));
		}
	}
}
