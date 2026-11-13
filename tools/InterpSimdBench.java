import jdk.incubator.vector.*;
import java.util.Random;

// run: java --add-modules jdk.incubator.vector tools/InterpSimdBench.java
// trilinear interpolation over runs of consecutive source offsets (as TpsPositionField.ImageBlocks): scalar loop vs Vector API
public class Interp {
  static final VectorSpecies<Float> F = FloatVector.SPECIES_PREFERRED;

  static void scalar(float[] src, int ss0, int ss1, int o, float[] w0, float[] w1, float[] w2, float[] d, int line, int i0, int j) {
    for (int i = i0; i < j; ++i) {
      final float r0 = w0[i], r1 = w1[i], r2 = w2[i];
      final float a000 = src[o + i], a001 = src[o + i + 1], a010 = src[o + i + ss0], a011 = src[o + i + ss0 + 1];
      final float a100 = src[o + i + ss1], a101 = src[o + i + ss1 + 1], a110 = src[o + i + ss1 + ss0], a111 = src[o + i + ss1 + ss0 + 1];
      d[line + i] = a000 + r0 * (-a000 + a001) + r1 * ((-a000 + a010) + r0 * (a000 - a001 - a010 + a011))
          + r2 * ((-a000 + a100) + r0 * (a000 - a001 - a100 + a101) + r1 * ((a000 - a010 - a100 + a110) + r0 * (-a000 + a001 + a010 - a011 + a100 - a101 - a110 + a111)));
    }
  }

  static void vector(float[] src, int ss0, int ss1, int o, float[] w0, float[] w1, float[] w2, float[] d, int line, int i0, int j) {
    int i = i0;
    for (; i <= j - F.length(); i += F.length()) {
      final FloatVector r0 = FloatVector.fromArray(F, w0, i), r1 = FloatVector.fromArray(F, w1, i), r2 = FloatVector.fromArray(F, w2, i);
      final FloatVector a000 = FloatVector.fromArray(F, src, o + i), a001 = FloatVector.fromArray(F, src, o + i + 1);
      final FloatVector a010 = FloatVector.fromArray(F, src, o + i + ss0), a011 = FloatVector.fromArray(F, src, o + i + ss0 + 1);
      final FloatVector a100 = FloatVector.fromArray(F, src, o + i + ss1), a101 = FloatVector.fromArray(F, src, o + i + ss1 + 1);
      final FloatVector a110 = FloatVector.fromArray(F, src, o + i + ss1 + ss0), a111 = FloatVector.fromArray(F, src, o + i + ss1 + ss0 + 1);
      // same operations and order as scalar (no fma), so bit-identical
      final FloatVector t0 = a000.neg().add(a001);
      final FloatVector t1 = a000.neg().add(a010).add(r0.mul(a000.sub(a001).sub(a010).add(a011)));
      final FloatVector t2 = a000.neg().add(a100).add(r0.mul(a000.sub(a001).sub(a100).add(a101)))
          .add(r1.mul(a000.sub(a010).sub(a100).add(a110).add(r0.mul(a000.neg().add(a001).add(a010).sub(a011).add(a100).sub(a101).sub(a110).add(a111)))));
      a000.add(r0.mul(t0)).add(r1.mul(t1)).add(r2.mul(t2)).intoArray(d, line + i);
    }
    scalar(src, ss0, ss1, o, w0, w1, w2, d, line, i, j);
  }

  // weights written just before they are used, as by the offsets pass in ImageBlocks
  static void fill(float[] w0, float[] w1, float[] w2, int l) {
    for (int i = 0; i < w0.length; ++i) { final double v = (l * 31 + i) * 0.0137; w0[i] = (float) (v - (int) v); w1[i] = (float) (v * 0.5 - (int) (v * 0.5)); w2[i] = (float) (v * 0.25 - (int) (v * 0.25)); }
  }

  public static void main(String[] a) {
    final int ss0 = 70, ss1 = 70 * 70, n = 64, lines = 4096;
    final Random rnd = new Random(1);
    final float[] src = new float[ss1 * 70];
    for (int i = 0; i < src.length; ++i) src[i] = rnd.nextFloat() * 1000;
    final float[] w0 = new float[n], w1 = new float[n], w2 = new float[n];
    for (int i = 0; i < n; ++i) { w0[i] = rnd.nextFloat(); w1[i] = rnd.nextFloat(); w2[i] = rnd.nextFloat(); }
    final int[] base = new int[lines];
    for (int l = 0; l < lines; ++l) base[l] = ((l / 64) % 64) * ss1 + (l % 64) * ss0 + 2;
    final float[] ds = new float[n * lines], dv = new float[n * lines];
    System.out.println("species " + F + " (" + F.length() + " floats)");
    for (int rep = 0; rep < 8; ++rep) {
      long t0 = System.nanoTime();
      for (int k = 0; k < 20; ++k) for (int l = 0; l < lines; ++l) { fill(w0, w1, w2, l); scalar(src, ss0, ss1, base[l], w0, w1, w2, ds, l * n, 0, n); }
      long t1 = System.nanoTime();
      for (int k = 0; k < 20; ++k) for (int l = 0; l < lines; ++l) { fill(w0, w1, w2, l); vector(src, ss0, ss1, base[l], w0, w1, w2, dv, l * n, 0, n); }
      long t2 = System.nanoTime();
      final double vox = 20.0 * lines * n;
      System.out.printf("scalar %.2f ns/voxel  vector %.2f ns/voxel  identical %b%n", (t1 - t0) / vox, (t2 - t1) / vox, java.util.Arrays.equals(ds, dv));
    }
  }
}
