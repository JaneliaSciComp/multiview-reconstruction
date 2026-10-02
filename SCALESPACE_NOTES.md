# Scale-space DoG in multiview-reconstruction: state, decisions, numbers (2026-10-01)

Companion to `SCALESPACE_TASK.md` (the brief). Branch `scalespace` (core committed as 354f39956, the
separation into its own detection method on 2026-10-02). Implemented: the MVR core, the whole-view driver
and a second Fiji detection method "Scale-space Difference-of-Gaussian"; the single-scale DoG classes
(`DoG`, `DoGParameters`, `DifferenceOfGaussianGUI`) are exactly as in master. Not implemented:
BigStitcher-Spark, the noise-floor threshold, Python (brief sections 5.3, 5.4, 7).

## 1. What exists

| Class / file | What it does |
|---|---|
| `process/interestpointdetection/methods/scalespace/DoGScaleSpace.java` (new) | The scale space: mpicbg's octave bookkeeping (`FloatArray2DScaleOctave`, `FloatArray2DScaleOctaveDoGDetector`) generalized to n-D and lazy blocks. `computeDoGScaleSpace( input, imageInterval, processInterval, mask, params, service )` returns `InterestPointSS` in pixels of octave 0. `buildScaleSpace` (lazy Gaussians/DoGs per octave), `detectOctave` (4D extrema + 4D refinement), `detectFinestLevel`, `detectSpatialExtrema` (legacy path on one level, for tests), `mergeDuplicates`, `octaveInterval` (block partition), `autoOctaves`, `sigmaBase`. |
| `.../scalespace/ScaleSpaceParameters.java` (new) | `sigmaMin, steps, octaves (-1 = auto), threshold, findMin/findMax, detectFinestLevel, localization, min/maxIntensity, imageSigma (0.5), cellSize, combineDistance (0.5), refineMargin (2)`. |
| `.../lazygauss/LazyDoG.java` (new) | Lazy, cached `( gauss2 - gauss1 ) * 1/(k-1)` in the shape of `LazyGauss`. |
| `fiji/spimdata/interestpoints/InterestPointSS.java` (new) | `extends InterestPointValue`: response == intensity, plus `sigma` (full-resolution xy pixels after `correctForDownsampling`). |
| `InterestPointsN5.java` | Writes `response` (FLOAT32, 1xN) and `sigma` (FLOAT64, 1xN) next to `id`/`loc` (Gzip, block 300000) whenever ALL points of a list are `InterestPointSS`; loads them transparently (`getInterestPointsCopy()` returns `InterestPointSS`); static `loadResponses( n5, ipDataset )` / `loadSigmas(...)`; `InterestPointData` DTO carries them. Old labels load unchanged. Mixed lists are rejected. |
| `DownsampleTools.correctForDownsampling` | Takes `List< ? extends InterestPoint >` and additionally scales `InterestPointSS.sigma` by `sqrt( |m00 * m11| )` of the mipmap transform. One call for positions and sigma, for GUI and Spark. |
| `.../scalespace/ScaleSpaceDetectionParameters.java` (new) | `extends InterestPointParameters` (views, imgloader, downsampling = starting position, intensity range, limit of detections) and holds `final ScaleSpaceParameters scaleSpace` — the only place that defines the scale-space defaults. |
| `.../scalespace/ScaleSpace.java` (new) | The driver, mirrors `DoG.java`: per view `openAndDownsample`, `DoGScaleSpace.computeDoGScaleSpace( extendMirrorSingle( img ), img, img, null, p.scaleSpace, service )`, `limitList`, `correctForDownsampling` (positions and sigma). BigStitcher-Spark later calls `DoGScaleSpace.computeDoGScaleSpace` per block directly. |
| `fiji/plugin/interestpointdetection/ScaleSpaceGUI.java` (new) | Second entry of `Interest_Point_Detection.staticAlgorithms` ("Scale-space Difference-of-Gaussian", DoG stays preselected: `defaultAlgorithm = 0`). Extends `DifferenceOfGUI` like the DoG dialog (localization, bead presets, Advanced, Interactive preview at the finest scale, downsampling, min/max, limit of detections) plus `Steps_per_octave`, `Octaves (-1 = as many as the image allows)`, `Detect_finest_level`. Its persisted defaults come from `new ScaleSpaceParameters()`. No CUDA. Params string `DOG-SS s= steps= octaves= finestLevel= t= min= max= downsampleXY= downsampleXYIndex= downsampleZ= minIntensity= maxIntensity=`; `describeParameters` records `detectionMethod=SCALE_SPACE`. |
| `ActionToSparkCli` | `detectionMethod=SCALE_SPACE` is an unsupported value: the recorded detection step is skipped with a comment instead of rendering a single-scale Spark command. |
| `DifferenceOfGUI` | One-token fix: the view-selection dialog of the interactive preview uses the header that is passed in (DoG passes the same string as before). |
| `tests/TestDoGScaleSpace.java`, `tests/TestInterestPointSSSaveLoad.java` (new) | 7 + 7 tests, see section 4. |

## 2. Conventions (decided with Stephan, 2026-09-30/10-01)

- **Starting position**: octave 0 is the image at the chosen downsampling (`downsampleXY/downsampleZ`); every further octave halves all three axes. The anisotropy chosen by the starting position stays constant.
- **Levels**: Gaussians `sigma_i = sigmaMin * k^(i-1)`, i = 0..steps+2, k = 2^(1/steps); DoG `d_i = ( g_(i+1) - g_i ) / ( k - 1 )`, i = 0..steps+1; extrema possible on levels 1..steps. So **level 1 is sigmaMin = today's `s`**, and `d_1` is exactly the single-scale DoG (with steps = 4 even bit-identical, same float chain as `DoGImgLib2.computeSigmas`). Level 0 (`sigmaMin / k`) exists only as the scale neighbour.
- **Hand-over**: the level `steps` (sigma = 2 * sigma_0) is decimated by taking every second pixel (no offset, no extra blur, as mpicbg); pixel p of octave o sits at `2^o * p` in octave 0. The input of octave o >= 1 is materialized where it is needed; everything else is lazy (`LazyGauss`, `LazyDoG`, cached cells).
- **Block partition**: `octaveInterval( block, o ) = [ ceil( min / 2^o ), ceil( ( max + 1 ) / 2^o ) - 1 ]`; adjacent blocks stay adjacent at every octave (no gaps, no duplicates). Octave count and domains always derive from the whole image, never from the block. The halo of coarser octaves is implicit (cells are computed where touched); it compounds to ~92 px per side at base resolution for 3 octaves (section 1.11 of the plan), which only matters for Spark blocks, not for whole views.
- **Peaks**: non-strict extremum over the 80 neighbours in (x, y, z, scale) via the existing `DoGImgLib2.findPeaks` on a `Views.stack` of the DoG levels; 4D quadratic refinement via imglib2 `SubpixelLocalization` (same settings as `Localization.computeQuadraticLocalization`, failed fits are returned and handled, see below). Threshold semantics as today: prefilter `t/3`, refined `|value| > t`.
- **Stored sigma** = lower Gaussian sigma of the DoG level, `sigmaMin * k^(level-1) * 2^octave`, in full-resolution xy pixels (geometric mean of the x and y scale of the mipmap transform). Response = signed refined DoG value (negative for bright blobs, like `InterestPointValue.intensity` today).
- **Duplicates**: one KD-tree pass over all octaves, radius `combineDistance * 2^max( octave_a, octave_b )` in base pixels, keep the higher |response|, same sign only; ids 0..N-1 assigned afterwards, sorted by |response| descending.
- **Auto octaves**: as long as every dimension of the octave is larger than the longest Gaussian kernel of that octave (mpicbg rule), at least 1. ds1 at ds (2,2,1): 2 octaves (z = 86 slices limits it); 512x320x2900: 4.

## 3. Two findings during implementation (to discuss)

### 3.1 Sub-resolution beads are not scale extrema -> `detectFinestLevel`

A point-like structure (smaller than the finest level) has a DoG response that keeps growing towards
finer scales (for a delta it goes like sigma^-3), so it is never an interior extremum in scale. On ds1
pure Lowe semantics found 1-4% of the stored beads (table below). Close bead pairs are a second case:
at the next coarser level the response between the two beads is stronger than each bead's own, so
neither is an extremum in scale and the scale space reports one merged blob instead.

`ScaleSpaceParameters.detectFinestLevel` (default true) therefore
additionally keeps ALL spatial extrema of level 1, i.e. exactly today's single-scale detections at
sigmaMin, refined in space only (legacy-identical) and reported with sigma = sigmaMin (an upper bound of
their scale). Level-1 extrema in space and scale whose 4D fit fails fall back to the same path. With the
flag off the detector is pure Lowe/mpicbg. A first version only kept finest-level peaks that dominate
the next coarser level; that excluded the close pairs (81% instead of 100%), so it was dropped.

### 3.2 `steps` decides whether today's label is reproduced

The finest DoG band is `( G( k * sigmaMin ) - G( sigmaMin ) )`, and the legacy detector uses k = 2^(1/4).
With steps = 3 (k = 2^(1/3)) the band is broader, close pairs merge and weak beads shift relative to the
threshold. **Decision (2026-10-01): default `steps = 4`** (`ScaleSpaceParameters`, the only hard-coded default; `ScaleSpaceDetectionParameters` and the `ScaleSpaceGUI` defaults take it from there).
Cost of steps 4 vs 3: 7 instead of 6 Gaussians and 6 instead of 5 DoG levels per octave.

### ds1 numbers (copy `~/Documents/pcmatch_data/ds1_scalespace`, s = 1.8, t = 0.008, ds match-z = 2,2,1, intensity 16..255)

The legacy run reproduces the stored `beads` label exactly in count (3926 / 3013 / 3734 / 2756 / 1626 / 126 for
view setups 0..5). "ref<0.5" = fraction of stored beads with a scale-space point within 0.5 px (full-res pixels),
"medDist" = median distance of a stored bead to its nearest scale-space point. The copy holds the labels
`beads-ss` (steps 3, finest level), `beads-ss-s4` (steps 4, finest level), `beads-ss-s3` (steps 3, pure Lowe).

steps 4, finest level on (the new default):

| view | legacy | scale space | octave 0 | octave 1 | ref<0.5 | ref<1 | ref<2 | medDist |
|---|---|---|---|---|---|---|---|---|
| 0 | 3926 | 4167 | 4136 | 31 | 0.997 | 1.000 | 1.000 | 0.000 |
| 1 | 3013 | 3185 | 3158 | 27 | 0.995 | 1.000 | 1.000 | 0.000 |
| 2 | 3734 | 3979 | 3944 | 35 | 0.995 | 0.999 | 0.999 | 0.000 |
| 3 | 2756 | 2949 | 2915 | 34 | 0.995 | 0.999 | 0.999 | 0.000 |
| 4 | 1626 | 1716 | 1706 | 10 | 0.991 | 0.998 | 0.998 | 0.000 |
| 5 | 126 | 136 | 136 | 0 | 0.992 | 1.000 | 1.000 | 0.000 |

steps 3, finest level on:

| view | legacy | scale space | octave 0 | octave 1 | ref<0.5 | ref<1 | ref<2 | medDist |
|---|---|---|---|---|---|---|---|---|
| 0 | 3926 | 3694 | 3667 | 27 | 0.810 | 0.866 | 0.882 | 0.181 |
| 1 | 3013 | 2790 | 2769 | 21 | 0.806 | 0.860 | 0.875 | 0.174 |
| 2 | 3734 | 3551 | 3522 | 29 | 0.818 | 0.873 | 0.888 | 0.173 |
| 3 | 2756 | 2562 | 2537 | 25 | 0.787 | 0.848 | 0.865 | 0.181 |
| 4 | 1626 | 1510 | 1501 | 9 | 0.790 | 0.857 | 0.882 | 0.164 |
| 5 | 126 | 115 | 115 | 0 | 0.762 | 0.833 | 0.849 | 0.196 |

pure Lowe (finest level off), steps 4 / steps 3:

| view | legacy | scale space (4) | ref<1 (4) | scale space (3) | ref<1 (3) |
|---|---|---|---|---|---|
| 0 | 3926 | 303 | 0.017 | 309 | 0.039 |
| 1 | 3013 | 234 | 0.021 | 220 | 0.038 |
| 2 | 3734 | 323 | 0.021 | 328 | 0.046 |
| 3 | 2756 | 257 | 0.022 | 253 | 0.045 |
| 4 | 1626 | 133 | 0.026 | 127 | 0.046 |
| 5 | 126 | 14 | 0.040 | 15 | 0.071 |

Of the pure-Lowe points, about 92% (finest on) / 50% (finest off, steps 3) have a stored bead within 1 px;
the rest are coarser blobs (octave 1 sigma ~7-11 full-res px) and merged pairs.

Timing (whole views 256x256x86 at ds 2, 128 threads): legacy ~0.1 s per view, scale space ~0.7 s per view
(~6x; 6-7 Gaussians instead of 2, plus octave 1 at 1/8 of the voxels).

### 3.3 Pure Lowe at full resolution: are beads scale extrema then? (2026-10-01, Stephan's question)

Runs with `detectFinestLevel = false`, steps = 4, t = 0.008, octaves auto, on the working copies (labels
`lowe-ds<ds>-s<sigmaMin>`). Reference = the stored beads (ds1) or the steps-4 label at ds 2 (IP, equals the
legacy beads to 99.7%). "ref<1" = fraction of reference beads with a scale-space point within 1 px,
"ss near ref" = fraction of scale-space points within 1 px of a reference bead.

| data | ds | sigmaMin (full-res px) | points / view (ref) | ref<0.5 | ref<1 | ref<2 | ss near ref | sigma q10 / q50 / q90 |
|---|---|---|---|---|---|---|---|---|
| ds1 | 1 | 1.8 | 870 (3926) | 0.01 | 0.04 | 0.09 | 0.18 | 1.9 / 2.5 / 3.7 |
| ds1 | 1 | 1.4 | 900 (3926) | 0.01 | 0.03 | 0.07 | 0.11 | 1.4 / 2.0 / 4.3 |
| ds1 | 1 | 1.0 | 7300 (3926) | 0.06 | 0.22 | 0.51 | 0.12 | 1.84 / 1.93 / 2.8 |
| ds1 | 1 | 0.7 | 33000 (3926) | 0.05 | 0.24 | 0.67 | 0.03 | 1.409 / 1.436 / 1.463 |
| ds1 | 2 | 2.0 (1.0 at ds 2) | 1900 (3926) | 0.08 | 0.20 | 0.32 | 0.41 | 2.7 / 3.9 / 5.9 |
| ds1 | 2 | 2.6 (1.3 at ds 2) | 660 (3926) | 0.03 | 0.07 | 0.11 | 0.46 | 2.9 / 4.1 / 5.7 |
| IP | 1 | 1.4 | 1700 (3500) | 0.08 | 0.20 | 0.32 | 0.42 | 1.4 / 1.9 / 3.1 |
| IP | 1 | 1.0 | 3800 (3500) | 0.13 | 0.32 | 0.49 | 0.29 | 1.2 / 2.0 / 2.8 |
| IP | 1 | 0.7 | 13000 (3500) | 0.34 | 0.59 | 0.86 | 0.15 | 0.7 / 1.45 / 2.0 |

Reading:

- With sigmaMin >= 1.4 px (well-sampled kernels) only 3-4% (ds1) and 16-23% (IP, bigger pixels) of the
  beads are extrema in scale. The beads are at or below the pixel size, their DoG response keeps growing
  towards finer scales, there is no scale optimum to find. Scale selection is the wrong tool for them;
  `detectFinestLevel` is the right one.
- sigmaMin < ~1 px is NOT meaningful without upsampling: the Gaussian that produces the first level of
  every octave >= 1 then has sigma_diff = sqrt( sigma_1^2 - sigma_0^2 ) < 0.5 px, and the sampled kernel
  (e.g. [0.03, 0.94, 0.03] for 0.38 px) is nearly an identity. The lowest DoG level of the octave is ~0,
  so every spatial extremum of level 1 that beats level 2 passes as a "scale extremum". Visible as the
  pile-up at octave 1, level 1.0-1.3: ds1 sigmaMin 0.7 has 93% of 33000 points at sigma 1.41-1.46 px.
  The same artifact (weaker) drives the sigmaMin 1.0 rows (83% of the points in octave 1 at the lowest
  level). This is exactly why Lowe doubles the image and uses sigma_0 = 1.6 in the doubled image
  (= 0.8 original pixels, kernels >= 1.25 px) and why mpicbg's default initialSigma is 1.6.
- Doubling (an "octave -1" as starting position, 2x linear upsampling with intrinsic blur 1.0 in the new
  grid) would make sigmaMin down to ~0.8 original px meaningful and recover the beads whose optimum lies
  above that (on ds1 roughly the 20-50% seen at sigmaMin 1.0, without the artifact); truly sub-resolution
  beads would still not be scale extrema. Not implemented; easy to add on top of the starting-position
  design if wanted.
- The legacy detector at full resolution with s = 0.7 found 656k points per view (noise) and its O(N^2)
  duplicate filter needed 35 min per view; the scale space needed 2 s. A noise floor (brief 5.1) matters
  as soon as sigmaMin gets small.

## 4. Verification

- `TestDoGScaleSpace`: partition arithmetic and tiling; with steps = 4 the Gaussian levels 1 and 2 and
  DoG level 1 of octave 0 are **bit-identical** to `computeDoG`'s images (with a different cell size), and
  `detectSpatialExtrema( octave 0, level 1 )` gives the **identical** 162 peaks of the simulated beads
  (after applying the legacy duplicate filter); synthetic blobs of sigma 2, 4, 8, 16 are each found once
  in successive octaves with positions within 0.1 * 2^o px, sigma / blob-sigma constant within 2%,
  responses within 4% for sigmaMin 1.5 (at sigmaMin 1.0 the finest octave overestimates the response by
  ~10%: point-sampled Gaussian kernels at sigma ~1 px, confirmed against the continuous solution in
  Python); processing in blocks (incl. odd boundaries) gives identical peaks to the whole image; with the
  finest level 98.8% of the legacy simulated-bead detections are reproduced within 0.5 px, without it 0;
  the driver equals the shared helper plus `correctForDownsampling`.
- `TestInterestPointSSSaveLoad`: N5 round trip (dtypes, shapes, blocks, Gzip, values, `instanceof`,
  index alignment with `id`), plain lists unchanged, old-style labels unchanged, mixed lists rejected,
  empty lists, DTO round trip, full pipeline save/load via `XmlIoSpimData2` with a `DOG-SS` params string.
- Full MVR suite: 32 tests pass. ds1 end to end: detect -> `InterestPointTools.addInterestPoints` ->
  `XmlIoSpimData2.save` -> reload gives `InterestPointSS` for all 18 views, `interestpoints.n5/.../{id,loc,response,sigma}` present.
- Not done: clicking through the Fiji dialog (the GUI path was exercised headlessly: `testGUIRegistered` runs
  `ScaleSpaceGUI.findInterestPoints` and compares with `ScaleSpace.findInterestPoints`; `testDriver` compares the
  driver with `DoGScaleSpace.computeDoGScaleSpace`).

## 5. Known limits, left as they are

- Legacy quirks kept as the reference: the `computeDoG` duplicate filter drops its last point; BigStitcher-Spark
  never applies its +-1 block expansion (2-voxel seams between blocks); Spark's `--overlappingOnly`
  uses the current view's mipmap transform for the partner view.
- Empty `InterestPointSS` lists write no `response`/`sigma` datasets and load as plain empty lists.
- Derived labels (`_split`, thin-out, from-correspondences, `TransformationTools.applyTransformation`)
  create plain `InterestPoint`s; response/sigma remain available by id via `loadResponses`/`loadSigmas`.
- Response comparability across `steps`: a unit blob gives -0.493 (k = 2^(1/3)) vs -0.509 (k = 2^(1/4)),
  so a threshold keeps its meaning within ~3%.
- The intensity range is always taken from the whole image (never from a block) when not provided.

## 6. Next steps (from the brief)

1. BigStitcher-Spark `detect-interestpoints --scaleSpace --steps --octaves [--finestLevel]`: call
   `DoGScaleSpace.computeDoGScaleSpace( input, viewInterval, blockInterval, mask, params, service )` per block, carry response/sigma
   through the temporary N5 and the block merge (radius `combineDistance * 2^octave`, keep the higher
   |response|), `DownsampleTools.correctForDownsampling` once per view, params string `DOG-SS (Spark) ...`.
   Consider the halo alternative (blur the raw image per octave) if the compounding halo hurts.
2. Match-time filters `--minResponse --sigmaMin --sigmaMax` and a `filter-interestpoints` command.
3. Noise-floor `thresholdMin` (global 1.4826 * MAD of the finest DoG level over a voxel sample of all views).
4. pcmatch: `load_interest_points_ext`, `DOG-SS` branch in `dog_sigma_fullres_px`, the sweep (brief section 7).
