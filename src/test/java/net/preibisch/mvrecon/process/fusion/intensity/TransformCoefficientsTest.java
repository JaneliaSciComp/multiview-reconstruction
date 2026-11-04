package net.preibisch.mvrecon.process.fusion.intensity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TransformCoefficientsTest {

	/**
	 * 1x1x2 grid over a 4x1x4 image: z slab 0 has a = 1, slab 1 has a = 3. Past the last coefficient center in x the
	 * line must be padded with this row's coefficient, not row 0's.
	 */
	@Test
	public void rightPaddingStaysInTheCurrentRow() {
		final Coefficients coefficients = new Coefficients(new double[][] { { 1, 3 }, { 0, 0 } }, 1, 1, 2);
		final TransformCoefficients t = TransformCoefficients.create(new net.imglib2.FinalDimensions(4, 1, 4), coefficients);
		final float[] a = new float[4];
		t.line(new long[] { 0, 0, 3 }, 4, 0, a); // z = 3 is slab 1's center
		for (int x = 0; x < 4; ++x)
			assertEquals(3, a[x], 1e-6, "x = " + x);
	}
}
