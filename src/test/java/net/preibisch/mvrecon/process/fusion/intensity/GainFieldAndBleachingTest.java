package net.preibisch.mvrecon.process.fusion.intensity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalDimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.process.fusion.intensity.mpicbg.FlattenedMatches;

/**
 * The per-voxel gain of {@link FastLinearIntensityMap} (gain field, unbleaching), its storage, the median matcher and
 * the {@link GainField} solve.
 */
public class GainFieldAndBleachingTest {

	private static Coefficients identity() {
		return new Coefficients(new double[][] { { 1.0 }, { 0.0 } }, 1, 1, 1);
	}

	/** 4 x 8 x 1 image of {@code value}, corrected by {@code c}, as float */
	private static float[][] apply(final Coefficients c, final float value) {
		final float[] data = new float[4 * 8];
		java.util.Arrays.fill(data, value);
		final RandomAccessibleInterval<FloatType> in = ArrayImgs.floats(data, 4, 8, 1);
		final RandomAccessibleInterval<FloatType> out = BlockSupplier.of(in)
				.andThen(FastLinearIntensityMap.linearIntensityMap(c, in))
				.toCellImg(in.dimensionsAsLongArray(), 4, 4, 1);
		final float[][] v = new float[8][4];
		final RandomAccess<FloatType> ra = out.randomAccess();
		for (int y = 0; y < 8; ++y)
			for (int x = 0; x < 4; ++x)
				v[y][x] = ra.setPositionAndGet(x, y, 0).get();
		return v;
	}

	@Test
	public void gainFieldScalesAlongItsAxis() {
		final double cy = Math.log(2);
		final float[][] v = apply(identity().withGainField(new double[] { 0, cy, 0 }), 100);
		for (int y = 0; y < 8; ++y) {
			final double u = (y + 0.5) / 8 - 0.5;
			for (int x = 0; x < 4; ++x)
				assertEquals(100 * Math.exp(cy * u), v[y][x], 1e-3, "y=" + y + " x=" + x);
		}
	}

	@Test
	public void bleachedVoxelsAreUnbleachedAboveThresholdOnly() {
		// one earlier view covering rows y < 3 of this image (its pixels = this image's, 4 x 3 x 1)
		final Bleaching b = new Bleaching(0.5, List.of(new AffineTransform3D()), List.of(new FinalDimensions(4, 3, 1)));
		final Coefficients c = identity().withThreshold(50).withBleachFactor(0.5).withBleaching(b);
		final float[][] fg = apply(c, 100), bg = apply(c, 40);
		for (int y = 0; y < 8; ++y) {
			assertEquals(y < 3 ? 200 : 100, fg[y][1], 1e-3, "foreground y=" + y);
			assertEquals(40, bg[y][1], 1e-3, "below the threshold y=" + y);
		}
	}

	@Test
	public void bleachCountMatchesBruteForce() {
		final AffineTransform3D t = new AffineTransform3D();
		t.rotate(2, 0.3);
		t.translate(-5.2, 7.9, 0.4);
		final FinalDimensions dims = new FinalDimensions(20, 13, 5);
		final Bleaching b = new Bleaching(0.2, List.of(t, t.inverse()), List.of(dims, dims));
		final int len = 40;
		final int[] count = new int[len];
		for (long y = -3; y < 20; ++y)
			for (long z = -1; z < 6; ++z) {
				final long[] start = { -7, y, z };
				b.countLine(start, len, count);
				for (int i = 0; i < len; ++i) {
					int expected = 0;
					for (final AffineTransform3D tj : List.of(t, t.inverse())) {
						final double[] q = new double[3];
						tj.apply(new double[] { start[0] + i, y, z }, q);
						boolean in = true;
						for (int d = 0; d < 3; ++d)
							in &= q[d] >= -0.5 && q[d] < dims.dimension(d) - 0.5;
						expected += in ? 1 : 0;
					}
					assertEquals(expected, count[i], "x=" + (start[0] + i) + " y=" + y + " z=" + z);
				}
			}
	}

	@Test
	public void bleachFactorAndGainFieldSurviveN5RoundTrip(@TempDir final Path tmp) {
		final String path = tmp.resolve("c.n5").toString();
		try (N5FSWriter w = new N5FSWriter(path)) {
			CoefficientsIO.save(identity().withThreshold(7).withBleachFactor(0.25).withGainField(new double[] { 0.1, -0.5, 0 }), w, "c");
			CoefficientsIO.save(identity(), w, "plain");
		}
		try (N5FSReader r = new N5FSReader(path)) {
			final Coefficients c = CoefficientsIO.load(r, "c");
			assertEquals(7, c.threshold());
			assertEquals(0.25, c.bleachFactor());
			assertArrayEquals(new double[] { 0.1, -0.5, 0 }, c.gainField());
			final Coefficients plain = CoefficientsIO.load(r, "plain");
			assertEquals(0, plain.bleachFactor());
			assertEquals(null, plain.gainField());
		}
	}

	@Test
	public void unbleachFunctionIsTheSameFormula() {
		final UnbleachFunction f = UnbleachFunction.bleachFactor(0.2);
		final Bleaching b = new Bleaching(0.2, List.of(), List.of());
		assertEquals(100, f.unbleach(100, 0));
		assertEquals(100 * b.gain(2), f.unbleach(100, 2), 1e-9);
	}

	@Test
	public void medianFilterReportsTheRatioOfMedians() {
		final Random rnd = new Random(1);
		final FlattenedMatches m = new FlattenedMatches(1, 1001);
		for (int i = 0; i < 1001; ++i) {
			final double p = 50 + 1000 * rnd.nextDouble();
			// a bright tail in q that would pull a least-squares scale, but not the medians
			m.put(p, i % 10 == 0 ? 5 * p : 0.6 * p, 1);
		}
		m.flip();
		final List<mpicbg.models.PointMatch> out = new ArrayList<>();
		new MedianIntensityMatchingFilter().filter(m, out);
		assertEquals(2, out.size());
		// the tail moves the median of q a little (0.65), a least-squares scale through all pairs a lot (~1.04)
		assertEquals(0.6, GainField.scale(out), 0.07);
		final List<mpicbg.models.PointMatch> all = new ArrayList<>();
		for (int i = 0; i < m.size(); ++i)
			all.add(new net.preibisch.mvrecon.process.fusion.intensity.mpicbg.PointMatch1D(
					new net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D(m.p()[0][i]),
					new net.preibisch.mvrecon.process.fusion.intensity.mpicbg.Point1D(m.q()[0][i]), 1));
		assertEquals(1.04, GainField.scale(all), 0.1);
	}

	@Test
	public void gainFieldRecoversARampAlongAChainAndDropsAxesWithoutLeverArm() {
		// 20 tiles stacked along y, each overlap at u_y = +0.45 in the upper and -0.45 in the lower one, with small
		// jitter in x / z (lever arms ~0.1, as in a real chain); true ramp c_y = 0.5, per-tile log gains ~ N(0, 0.05)
		final Random rnd = new Random(7);
		final int n = 20;
		final double cy = 0.5;
		final double[] t = new double[n];
		for (int i = 0; i < n; ++i)
			t[i] = 0.05 * rnd.nextGaussian();
		final List<GainField.Constraint> cons = new ArrayList<>();
		for (int i = 0; i + 1 < n; ++i) {
			final double[] ua = { 0.1 * rnd.nextGaussian(), 0.45, 0.05 * rnd.nextGaussian() };
			final double[] ub = { ua[0] + 0.1 * rnd.nextGaussian(), -0.45, ua[2] + 0.05 * rnd.nextGaussian() };
			final double d = cy * (ua[1] - ub[1]) + t[i] - t[i + 1] + 0.01 * rnd.nextGaussian();
			cons.add(new GainField.Constraint(new ViewId(0, i), new ViewId(0, i + 1), d, 1000, ua, ub));
		}
		final GainField.Result r = GainField.fit(cons, 0.01);
		assertEquals(cy, r.c[1], 0.05, r.toString());
		assertEquals("kept", r.status[1], r.toString());
		assertNotEquals("kept", r.status[0], r.toString());
		assertNotEquals("kept", r.status[2], r.toString());
		// and the overlaps are explained: corrected log ratios ~ 0 up to the noise
		for (final GainField.Constraint c : cons) {
			final double res = c.d - r.c[1] * (c.ua[1] - c.ub[1]) - Math.log(r.gains.get(c.a)) + Math.log(r.gains.get(c.b));
			assertEquals(0, res, 0.03, "residual " + c.a.getViewSetupId());
		}
	}
}
