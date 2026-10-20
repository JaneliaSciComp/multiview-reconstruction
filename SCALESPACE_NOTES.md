# Scale-space DoG in multiview-reconstruction: state, decisions, numbers (2026-10-01, updated 2026-10-19)

Companion to `SCALESPACE_TASK.md` (the brief). Branch `scalespace` (core committed as 354f39956, the
separation into its own detection method on 2026-10-02). Implemented: the MVR core, the whole-view driver
and a second Fiji detection method "Scale-space Difference-of-Gaussian"; since 2026-10-19 one `InterestPoint`
class with response, sigma and intensity for both methods and filtered (virtual) labels. The single-scale DoG
algorithm (`DoGImgLib2`, `DoG`) detects exactly as master, it only stores the attributes in addition. Not implemented:
BigStitcher-Spark, the noise-floor threshold, Python (brief sections 5.3, 5.4, 7).

## 1. What exists

| Class / file | What it does |
|---|---|
| `process/interestpointdetection/methods/scalespace/DoGScaleSpace.java` (new) | The scale space: mpicbg's octave bookkeeping (`FloatArray2DScaleOctave`, `FloatArray2DScaleOctaveDoGDetector`) generalized to n-D and lazy blocks. `computeDoGScaleSpace( input, imageInterval, processInterval, mask, params, service )` returns `InterestPointSS` in pixels of octave 0. `buildScaleSpace` (lazy Gaussians/DoGs per octave), `detectOctave` (4D extrema + 4D refinement), `detectFinestLevel`, `detectSpatialExtrema` (legacy path on one level, for tests), `mergeDuplicates`, `octaveInterval` (block partition), `autoOctaves`, `sigmaBase`. For the interactive preview (2026-10-05): `computeScaleSpaceCandidates( ..., maxCandidates, service )` returns `Candidates { regular, finest, threshold, sigmaMax, octaves }`, i.e. all unmerged peaks down to one threshold, minima and maxima, the finest level without exclusion, and `filterCandidates( candidates, t, finestLevel, findMin, findMax, localization, combineDistance )` gives the result of `computeScaleSpacePeaks` at any `t >= candidates.threshold` without computing anything again. For that `ScaleSpacePeak` carries `isMax` (the extremum type as `findPeaks` reports it, the refined value can have either sign), `finest` and `level1Key` (the integer level-1 position a peak was found at, which decides whether a finest-level candidate is excluded at a given threshold). Verified identical to direct runs on the simulated beads (test) and on ds1 at (2, 2, 1): 10074 / 564 peaks with / without the finest level, both localizations. Multi-threshold entry point for Spark and the driver (2026-10-07): `computeDoGScaleSpace( input, imageInterval, processInterval, mask, p, double[] thresholds, service )` = candidates down to min( thresholds ) (no cap, throws if the floor were raised) + `filterCandidates` per threshold, one list of `InterestPointSS` per threshold with cloned positions (`toInterestPoints`); identical to one run per threshold up to the initial pre-filter min/3 vs t/3 (identical on the simulated beads and on ds1: 12097 / 10074 / 7049 points at 0.004 / 0.008 / 0.016, in order). |
| `.../scalespace/ScaleSpaceParameters.java` (new) | `sigmaMin (1.6, the initial blur, Lowe), steps (4), octaves (-1 = auto), threshold, findMin/findMax, detectFinestLevel, localization, min/maxIntensity, imageSigma (0.5), anisotropy (per dimension relative to x, null = isotropic in pixels), cellSize, combineDistance (0.5), refineMargin (2), fitFinestSize (true), finestFitLevels (4), finestFitMaxResidual (0.5), finestFallbackSigma (1.4)`, copy constructor. The single source of the defaults. |
| `.../lazygauss/LazyDoG.java` (new) | Lazy, cached `( gauss2 - gauss1 ) * 1/(k-1)` in the shape of `LazyGauss`. |
| `fiji/spimdata/interestpoints/InterestPoint.java` | (2026-10-19) One class for both methods: `response` (final, the signed refined DoG value), `sigma` (mutable, rescaled by `correctForDownsampling`), `intensity` (mutable, set by the detector), NaN = unknown; all three are **floats** throughout (fields, constructor, getters, setters, N5; Stephan: millions of points of split views must stay small), the detectors cast their doubles once; filter bounds are compared at float precision. `derive( id, l )` keeps the attributes (used by `TransformationTools.applyTransformation`, `SplittingTools`, `ThinOut`, `RelativeThinOut`, `CreateInterestPointsFromCorrespondences`), `newInstance` does not (centroids), `clone` keeps them. `InterestPointValue` and `InterestPointSS` are gone; `Localization` always stores the value as response (no `keepIntensity`), `DoGImgLib2.detectPeaks` sets the sigma of the detection and the intensity, `DoGScaleSpace.toInterestPoints( peaks, input )` the intensity. |
| `fiji/spimdata/interestpoints/InterestPointFilter.java` (new, 2026-10-19) | A range per attribute (|response|, intensity, sigma; bounds inclusive, NaN = unbounded), `accept`/`filter` (a NaN attribute the filter needs throws), canonical string for the XML `response=0.004..;sigma=1.5..3` (`toString`/`fromString`), label suffix `_r0.004`, `_r0.004-0.01`, `_i200`, `_s1.5-3` (`suffix`), `describe( base )` for the params string, `describeParameters` for the history, the dialog grammar `parseEntries( "0.004, 0.01-0.02", axis )` / `parseRange`, `plain( double )`. |
| `fiji/spimdata/interestpoints/VirtualFilteredInterestPointsN5.java` (new, 2026-10-19) | `extends InterestPointsN5`: a label without points of its own that filters its base label (looked up in the owning `ViewInterestPointLists` at every access, so an unsaved or re-detected base is seen as it is; a missing base throws) on the fly, keeps the base ids, has its own correspondences (inherited N5 code), is read-only (`setInterestPoints` throws), can be chained (`maxChainLength` 16); `saveInterestPoints` writes a marker group `interestpoints` with `type=filtered`, `base`, `filter` instead of the datasets (readers that do not know filtered labels fail instead of loading an empty label), `getInterestPointsCopy` verifies the marker against the XML once (a mismatch throws). `create( basePath, viewId, label, base, filter )` for a new one; `copyTo` keeps it virtual (see below). The type checks that remain in MVR: the XML writer (two attributes), `addInterestPointList` (owner), `dependentLabels`, `InterestPointData.from` (the DTO factory); nothing outside MVR needs one. |
| `XmlIoViewInterestPoints`, `XmlKeysInterestPoints`, `InterestPoints.instantiatefromXML( baseDir, info, base, filter )`, `ViewInterestPointLists.addInterestPointList` | The label element of a filtered label carries `base="beads"` and `filter="response=0.004.."`; `addInterestPointList` hooks the owner of a filtered label. |
| `InterestPointTools` | `readIntensities( image, points )` (the shared helper: raw image, linear interpolation, at the detection resolution, what BigStitcher-Spark's `--storeIntensities` computes), `addFilteredInterestPoints( data, label, base, filter, views )`, `addFilteredInterestPointsCopy` (a plain copy of the passing points, ids kept), `dependentLabels( vipl, label )`; `limitList` sorts by |response| and throws on NaN. |
| `fiji/plugin/Filter_Detections.java` (new), `RemoveDetectionsPopup` | "New filtered interest points ..." in "Manage Interest Points": base label, `Response_range`, `Intensity_range`, `Sigma_range` (`a` or `a-b`, empty = none), `New_label` (empty = base + suffix), `Store_as_plain_label`; records `filter-interestpoints` (`recordFilterAction`, also used by the detection). |
| `InterestPoints.copyTo( basePath, viewId, label, labelMapping, pointMapping, copyCorrespondences )` | (2026-10-19) Recreates a label elsewhere without the caller knowing its kind: `InterestPointsN5` copies its points through the point mapping (null drops a point), `VirtualFilteredInterestPointsN5` stays a filter of the mapped base label (params adapted to the new base name). Correspondences follow the points (`CorrespondenceTools.mapCorrespondences`): the ones of dropped points are dropped, the detection ids follow renumbered points, the corresponding label follows the label mapping; the other end (corresponding view and id) is not checked, so a caller that drops points must only copy correspondences when the other views keep the corresponding points. `SpimData2Tools.reduceSpimData2` (identity mapping, correspondences copied, params now copied too) and `SplittingTools` (`l -> l + "_split"`, the points inside the interval shifted to its origin, no correspondences) use it, so neither contains a type check. |
| `InterestPointExplorerPanel.delete` | Deleting a label deletes its filtered labels, too (`dependentLabels`, logged). |
| `fiji/spimdata/explorer/interestpoint/InteractiveInterestPointFilter.java` (new, 2026-10-19), `InterestPointExplorerPanel`, `InterestPointTableModel` | Right-click "Filter interactively ..." in the interest point explorer (a right-click now also selects the row): one JFreeChart histogram per attribute (|response|, intensity, sigma; log10 binning by default for response and intensity when all values are > 0) of the points of the label in the selected views, each with a bdv `RangeSlider` and min/max fields (knob at the end / empty field = no bound, knob positions rounded to 3 significant digits), the current range marked in the chart, counts per axis and in total. The points drawn in BigDataViewer follow the filter live (`InterestPointTableModel.setPreviewFilter`, applied lazily in `getLocalCoordinates`; `InterestPointFilter.acceptKnown` = a point without the attribute does not pass, no exception). Every change is remembered as the defaults of `Filter_Detections` (`remember`: `defaultBaseLabel` by name, `defaultResponse/Intensity/Sigma` in the dialog grammar, an open lower bound written as 0), so "New filtered interest points ..." is prefilled for any views; "Create filtered label ..." asks for selected vs. all views with the label, the new label and plain copy, then calls `Filter_Detections.create` (`createLabel( views, name, copy )` headless). The window is built from `( SpimData2, label, views, preview consumer, refresh callback )`, the explorer passes its table model and panel. One window at a time, closed with the explorer. Verified on the ds1 copy without showing it (scratchpad runner `Ds1Interactive`: packed frame painted to PNG; the sigma and response ranges typed into the window give exactly the point counts of the existing labels `ss-filter_s1-2`, `_s2-4`, `_r0.008`, `_r0.016`; the created virtual label has the window's filter in all 3 views; a legacy label without attributes shows disabled axes; a constant attribute such as the sigma of a single-scale DoG is shown as one bar and cannot be filtered). JFreeChart quirk: the counts are auto-ranged over the x range the domain axis shows when the data changes, so `rebin` sets the x range before refilling the dataset. Linked histograms (2026-10-20, Stephan's request): the checkbox "Link histograms" (off by default, Stephan's choice; `defaultLinked` keeps the last choice) makes every histogram count only the points that pass the bounds of the other two axes, so narrowing one range shows the distribution of what is left in the others; the own bounds never thin the own histogram, the count line reads "a of b in range (b of c pass the other bounds)", the y axis follows the reduced counts (an all-zero histogram is shown at [0, 1] instead of JFreeChart's 1e-8). Built for speed: the window keeps one flat `float[]` per axis plus a bin index and a pass flag per point and drops the point objects after construction; a change is one pass for the pass flags of the moved axis (`inRange`), one pass per axis for the histogram and the points in range together (`histogram( bin, flags, own, counts )`, the three axes in parallel) and one for the total (`countPassing`); the chart is updated in place (`XYSeries.updateByIndex` with notifications off) and only when the counts changed. Measured with 2M synthetic points (one view): 13 ms per slider tick linked, 7 ms unlinked, 14 ms for a log toggle (rebin), 0.6 s to build the window; ds1 `beads-dog` (12536 points): the intensity histogram under response >= 0.0263 holds exactly the 5028 points that pass, the constant-sigma bar shrinks with them. `setRange( axis, min, max )`, `setLinked`, `getHistogramCounts`, `getCountText`, `getSummaryText` drive the window from code; `TestInteractiveInterestPointFilter.testWindowLinked` builds the window (never shown) where a display is available and is skipped headless, the counting itself is tested without a window (`testBin`, `testInRange`, `testLinkedCounts`). |
| `InterestPointsN5.java` | Writes `response`, `sigma` and `intensity` (FLOAT32, 1xN each; `sigma` was FLOAT64 until 2026-10-19 and such datasets are still read) next to `id`/`loc` (Gzip, block 300000) for EVERY label, NaN where unknown; loads them transparently (missing datasets of old labels give NaN, a size mismatch throws); static `loadResponses( n5, ipDataset )` / `loadSigmas(...)` / `loadIntensities(...)`; `InterestPointData` DTO carries them, and for a filtered label its base label and filter instead (`isVirtual`), so `saveInterestPointDataStatic` writes the datasets or the marker and the Spark save path treats both kinds alike. |
| `DownsampleTools.correctForDownsampling` | Additionally scales the sigma of every point that has one by `sqrt( |m00 * m11| )` of the mipmap transform. One call for positions and sigma, for GUI and Spark. |
| `.../scalespace/ScaleSpaceDetectionParameters.java` (new) | `extends InterestPointParameters` (views, imgloader, intensity range, limit of detections) plus `long[] downsampling` = the starting resolution in x, y, z (the inherited `downsampleXY/downsampleZ` are not used), `anisotropyZ` (z voxel / x voxel of the raw data, NaN = calibration) and `final ScaleSpaceParameters scaleSpace` — the only place that defines the scale-space defaults. |
| `.../scalespace/ScaleSpace.java` (new) | The driver, mirrors `DoG.java`: per view `openAndDownsample`, `DoGScaleSpace.computeDoGScaleSpace( extendMirrorSingle( img ), img, img, null, p.scaleSpace, service )`, `limitList`, `correctForDownsampling` (positions and sigma). BigStitcher-Spark later calls `DoGScaleSpace.computeDoGScaleSpace` per block directly. Returns plain `InterestPoint`s with response, sigma and intensity (the multi-threshold overloads of 2026-10-07 are gone, see "Filtered labels"). The per-view executor is shut down in a `finally`. |
| `fiji/plugin/interestpointdetection/ScaleSpaceGUI.java` (new) | Second entry of `Interest_Point_Detection.staticAlgorithms` ("Scale-space Difference-of-Gaussian", DoG stays preselected: `defaultAlgorithm = 0`). Extends `DifferenceOfGUI` like the DoG dialog (localization, min/max, limit of detections), but "Interest_point_specification" has only "Advanced ..." and "Interactive ..." (2026-10-07, no bead presets; `specificationChoice`, `defaultSpecification` = Interactive, via the overridable hooks `specificationChoices`/`defaultSpecificationIndex`/`dispatchSpecification` of `DifferenceOfGUI`, which keep the DoG dialog unchanged). The main dialog asks only for `Starting_resolution (downsampling x, y, z)` (drop-down of the precomputed levels of the first view with the resulting voxel size, default the second level, last entry "Manually (powers of two) ..." -> `queryManualResolution`), `Anisotropy_z` (default the calibration ratio z/x of the first view, remembered while the calibration stays the same) and `Initial_blur sigma (px at the starting resolution, Lowe: 1.6)`; they are added and queried in the downsampling hooks `addDownsamplingParameters`/`queryDownsamplingParameters` (2026-10-05) because `DifferenceOfGUI` opens the Advanced/Interactive sub-dialogs right after them and the preview needs the values. Everything the preview can set is also in "Advanced values": `Threshold`, `Steps_per_octave`, `Octaves (-1 = as many as the image allows)`, `Finest_structures`, `Find_minima`, `Find_maxima`. Filtered labels (2026-10-19): the main dialog of both methods ends with `Filtered_labels_by_response (|DoG response|)`, `Filtered_labels_by_intensity (image units)` and, for the scale space only (`hasSigmaFilter`), `Filtered_labels_by_sigma (px at full resolution)` (empty by default and never remembered; entries `a` or `a-b`, comma separated, one label each, `DifferenceOfGUI.queryFilters` / `InterestPointFilter.parseEntries`, `InterestPointDetectionGUI.getFilters`); `Interest_Point_Detection` stores the detection as the label and adds one `VirtualFilteredInterestPointsN5` per filter (`label + filter.suffix()`, e.g. `beads_r0.004`, `beads_s1.5-3`) with `InterestPointTools.addFilteredInterestPoints`, one `detect-interestpoints` record for the label and one `filter-interestpoints` record per filtered label (so removing a label removes its record). The finest structures (detectFinestLevel) are NOT in the main dialog (2026-10-05): only the interactive preview sets them; without a choice yet they are off at full resolution (1, 1, 1) and on at any downsampling, afterwards the last choice is kept (`lastDetectFinestLevel`, `defaultDetectFinestLevel( downsampling )`). The presets no longer touch sigma; `addAddtionalParameters` is empty. Done in the preview sets threshold, finest structures, minima/maxima, steps per octave, octaves, the starting resolution (also as the default of the next dialog) and the intensity range. Its persisted defaults come from `new ScaleSpaceParameters()`. No CUDA. Params string `DOG-SS s= steps= octaves= finestLevel= t= min= max= downsampleX= downsampleY= downsampleZ= anisotropy= minIntensity= maxIntensity=`; `describeParameters` records `detectionMethod=SCALE_SPACE`, `downsampleXY` (when x == y) / `downsampleZ` and `anisotropy`. |
| `ActionToSparkCli` | `detectionMethod=SCALE_SPACE` is an unsupported value: the recorded detection step is skipped with a comment instead of rendering a single-scale Spark command. `filter-interestpoints` has no recipe yet, it renders as `# unknown action: filter-interestpoints` until the Spark command exists. |
| `fiji/plugin/interestpointdetection/interactive/InteractiveAnisotropy.java` (new, 2026-10-03) | Self-contained BDV tool to estimate the anisotropy: opens one view on its raw pixel grid (all pyramid levels via `MultiResolutionSource`/`MultiResolutionTools.createVolatileRAIs`) as a side view fitted to the viewer after the card panel is laid out (`fitSideView`, xz as shift+Y, the whole stack at the current anisotropy), with a BDV card holding one `BoundedValueSlider` (z / xy voxel at full resolution, initial = calibration ratio, bounds 0.25 .. max( 5, 2 x calibration ), lower bound >= 0, presets "0 .. 10" and "calibration / 2 .. x 2"); the slider sets the fixed transform diag(1, 1, a) of the `TransformedSource` and repaints, so the volume is rescaled in z live. "Done" (or closing the window) logs the value, keeps it in `InteractiveAnisotropy.lastAnisotropy` and pre-fills `Anisotropy_z` of the scale-space dialog (`ScaleSpaceGUI.defaultAnisotropyZ`, remembered while the calibration stays the same). Started from the explorer popup `EstimateAnisotropyPopup` ("Estimate Anisotropy (BDV) ...", first selected view, section Display/Verify) or `main( dataset.xml [setupId] [tpId] )`. |
| `fiji/plugin/interestpointdetection/interactive/BoundedValueSlider.java` (new, 2026-10-05) | The brightness slider of BigDataViewer for one value as a reusable Swing panel: slider (round-knob look of `bdv.ui.rangeslider.RangeSliderUI`), small value textbox, the bounds stacked min above max; clicking the bounds or right-clicking opens "set bounds ...", caller presets and "narrow bounds around the current value"; a value typed outside the bounds extends them; optionally logarithmic (linear in log( value ), bounds > 0). Listener `valueChanged` / `boundsChanged`. Used by `InteractiveAnisotropy` and `InteractiveScaleSpace`. |
| `fiji/plugin/interestpointdetection/interactive/InteractiveScaleSpace.java` (new, 2026-10-05) | The real interactive preview of the scale space (replaces the legacy single-scale `InteractiveDoG` for this method). A `Session` holds the view, the dialog's parameters and the result; each window opens the view at a starting resolution exactly as the detection does (`DownsampleTools.openAndDownsample`, `ScaleSpace.anisotropy`), computes the candidates once (`computeScaleSpaceCandidates`, down to the slider's lower bound, default 0.001, at most 500k) inside a rectangle ROI in x and y (default 200 x 200 in the middle of the image, `defaultRoiSize`) and a z range around the current slice that follows from the 16M voxel budget for the area of the rectangle (`defaultZHalf`, at least 16 slices each way; no field for it); drawing another rectangle or leaving the computed z range while browsing (slice observer, 300 ms delay) computes again by itself, so 5000-slice stacks are never processed as a whole) in a worker thread, and draws the detections of the current threshold / flags as circles on the ImagePlus: radius = sqrt(3) sigma projected onto the slice with the z anisotropy, hue from red (initial blur) to blue (coarsest level, legend with octave ticks), pink (`finestColor`, a block left of the ramp in the legend) for finest-level-only detections, dashed for minima, strokes of one screen pixel at any zoom (ImageJ does not scale a stroke set with `Roi.setStroke`); at most 5000 circles per slice, binned per slice. The finest-level detections are no extremum in scale, so sigmaMin is only an upper bound of their scale; their sigma is therefore **fitted** (2026-10-05, `DoGScaleSpace.fitFinestSizes`, on by default via `fitFinestSize`): the response of a Gaussian blob of size b (x pixels, isotropic in physical units) at the center of DoG level i is C * ( prod_d ( b_d^2 + t_{i+1,d}^2 )^(-1/2) - prod_d ( b_d^2 + t_{i,d}^2 )^(-1/2) ) / ( k - 1 ) with t_{i,d} = max( sigma_i / aniso_d, imageSigma ) the total blur of the Gaussian levels (`totalSigmaOctave0`, the clamped z regime included); C is linear, b is found by a grid search over [0, 2.5 sigmaMin] with a parabolic refinement (`fitBlobSize`) from the n-linearly interpolated responses of the first `finestFitLevels` = 4 DoG levels (sigma 1.35, 1.6, 1.9, 2.26) at the refined position; the stored sigma is then b * sqrt( 2 / n ), the scale of maximal response of that blob (the same meaning as for the other points), capped at sigmaMin; if the relative residual is above 0.5 or b runs into the end of the range the point gets `finestFallbackSigma` = 1.4 (Stephan's best guess, "below the sampled scale", also capped at sigmaMin). The same code runs in the real detection (`detectFinestLevel` is shared), so the N5 `sigma` of finest-level points is the fitted or fallback value, scaled to full resolution like all sigmas. Synthetic 3d blobs of 0.5 / 1.0 / 1.5 px: fitted sigma 0.55 / 0.91 / 1.25 for expected 0.41 / 0.82 / 1.22 (the finest levels overestimate small sizes a bit, the discrete-kernel bias at sigma ~1 px). ds1 at (2, 2, 1), t = 0.008: 9532 finest-level points, 1532 unreliable (now 1.4), fitted sigma quantiles 5/25/50/75/95% = 0.0 / 0.74 / 1.12 / 1.45 / 1.6 (most of them are texture inside the sample, not beads). The circles are drawn with radius sqrt(3) sigma, at least 1 px (`minRadius`). While computing, the controls and the image window are disabled, the image shows "computing the scale space ..." as a text overlay and ImageJ's progress bar runs (`computeScaleSpaceCandidates( ..., IJ::showProgress )`, per octave). Controls: threshold (`BoundedValueSlider`, logarithmic, bounds 0.001 .. 0.3, lowering the lower bound recomputes), one row with "Finest structures" (= detectFinestLevel), "Steps/octave" and "Octaves" (all = as many as the image allows; both change the pyramid, so they compute again; inherited by windows opened from the drop-down, adopted by Done) and "Maxima" / "Minima" (details as tooltips, the legend explains itself as a tooltip, too), a drop-down of the resolution levels that opens another window at the selected one right away (same threshold / flags, the rectangle and the central slice scaled to that resolution, `scaledRoi`; the drop-down then shows this window's level again), Done (adopts this window's threshold, flags and starting resolution, closes all), Cancel (all); closing one window closes only it, the last one without Done cancels. No sigma in the preview. The intensity range is the user's (dialog, or "same min & max for all views"), otherwise every window computes the exact min/max of its own image, exactly as the detection does per view (2026-10-06); nothing is handed back to the dialog. |
| `DifferenceOfGUI` | One-token fix: the view-selection dialog of the interactive preview uses the header that is passed in (DoG passes the same string as before). |
| `tests/TestDoGScaleSpace.java`, `tests/TestInterestPointStorage.java`, `tests/TestInterestPointFilter.java`, `tests/TestScaleSpaceGUI.java` (new) | See section 4. |

## 2. Conventions (decided with Stephan, 2026-09-30/10-01)

- **Starting position**: octave 0 is the image at the chosen resolution level (`ScaleSpaceDetectionParameters.downsampling`); every further octave halves all three axes.
- **Anisotropy** (2026-10-02): one number `A` = z voxel / x voxel of the raw data (default: the calibration ratio of the first view, editable upwards because the PSF blurs z more). Per view `ScaleSpace.anisotropy( vd, mipmapTransform, A )` gives the voxel size of the opened image per dimension relative to x, `aniso = { 1, (vy*sy)/(vx*sx), (A*sz)/sx }` (s = diagonal of the mipmap transform), and the sigma of every level in dimension d is `sigma_i / aniso[d]` so that the Gaussians are isotropic in physical units (`sigmaMin` stays the x pixel sigma; z may be better resolved than x after downsampling, then `aniso[z] < 1` and z is blurred more). The blur the input of octave o carries per dimension has the closed form `in_0 = imageSigma`, `in_o = max( sigma_0 / aniso[d], imageSigma / 2^o )`; applied is `sqrt( max( 0, (sigma_i/aniso[d])^2 - in_o^2 ) )`, i.e. where the target is below the blur the image carries nothing is applied in that dimension (the finest levels then act slice-wise in z; such a DoG level has ~2/3 of the 3D response). Halos and the octave count are per dimension (few z slices no longer limit the octaves when the z sigmas are small). `anisotropy == null` is bit-identical to the pixel-isotropic code. The stored sigma stays the x sigma; the z sigma is `sigma / A` in full-resolution z pixels. The merge radius and the interactive preview remain pixel-isotropic.
- **Initial blur** (2026-10-05): `sigmaMin` is Lowe's initial blur, default 1.6 px at the starting resolution (was 1.8 = the legacy DoG default). It is a normal field of the main dialog, but not a parameter of the interactive preview; the starting resolution and the threshold are what the user tunes. Consequence: the bit-identical legacy level (steps 4, finest level) is only reproduced if 1.8 is typed in. The ds1 numbers in section 3 were measured with 1.8.
- **Levels**: Gaussians `sigma_i = sigmaMin * k^(i-1)`, i = 0..steps+2, k = 2^(1/steps); DoG `d_i = ( g_(i+1) - g_i ) / ( k - 1 )`, i = 0..steps+1; extrema possible on levels 1..steps. So **level 1 is sigmaMin = today's `s`**, and `d_1` is exactly the single-scale DoG (with steps = 4 even bit-identical, same float chain as `DoGImgLib2.computeSigmas`). Level 0 (`sigmaMin / k`) exists only as the scale neighbour.
- **Hand-over**: the level `steps` (sigma = 2 * sigma_0) is decimated by taking every second pixel (no offset, no extra blur, as mpicbg); pixel p of octave o sits at `2^o * p` in octave 0. The input of octave o >= 1 is materialized where it is needed; everything else is lazy (`LazyGauss`, `LazyDoG`, cached cells).
- **Block partition**: `octaveInterval( block, o ) = [ ceil( min / 2^o ), ceil( ( max + 1 ) / 2^o ) - 1 ]`; adjacent blocks stay adjacent at every octave (no gaps, no duplicates). Octave count and domains always derive from the whole image, never from the block. The halo of coarser octaves is implicit (cells are computed where touched); it compounds to ~92 px per side at base resolution for 3 octaves (section 1.11 of the plan), which only matters for Spark blocks, not for whole views.
- **Intensity range** (2026-10-06/07, Stephan): min/max come from the user or are computed from each image itself, never from a preview. `DoGScaleSpace` requires them (it throws on NaN) and never computes statistics over the image, so a block never scans the whole view; the driver `ScaleSpace` computes `FusionTools.minMax` of the opened view when the user set none (the per-view behaviour of the legacy driver), the preview computes it per window. The legacy preview of `DifferenceOfGUI.getImagePlusForInteractive` used to write the approximate range of the previewed view into the dialog, which then fixed it for the detection of all views; it now keeps the fields NaN (the preview still normalizes with the approximate range, the single-scale `InteractiveDoG` falls back to the display range). The scale-space preview computes the exact range of every opened image.
- **Filtered labels** (2026-10-19, replaces the multi-threshold export of 2026-10-07/08, which detected once and stored one full copy of the points per threshold as `label_t<threshold>`): every detection stores response, sigma and intensity, and a filtered label (`VirtualFilteredInterestPointsN5`) filters its base label on the fly whenever its points are requested, keeps the ids and has its own correspondences, so registrations on several thresholds (or sigma bands, or intensity ranges) can be compared side by side without duplicating points, and a filter of a filter works. One grammar for the dialogs, the XML and the suffixes: an entry `a` keeps values >= a, `a-b` keeps a <= value <= b (the response as |response|; units: response in normalized DoG units, intensity in raw image units, sigma in full-resolution x pixels), comma separated entries give one label each; the XML attribute is the canonical `response=0.004..;sigma=1.5..3`, the suffix `_r0.004`, `_r0.004-0.01`, `_i200`, `_s1.5-3`. The dialogs offer the fields in both methods (sigma only in the scale space), always empty by default. A filtered label is read-only (thin out, split, from-correspondences read it and make plain labels), deleting its base in the explorer deletes it, too, re-detecting the base keeps it valid (it filters whatever the base is now), a missing base is an error, old labels without the attributes are refused by a filter that needs one. The multi-threshold overloads (`DoGImgLib2.computeDoG( ..., double[] thresholds, ... )`, `DoGScaleSpace.computeDoGScaleSpace( ..., thresholds, ... )`, the drivers' `perThreshold` methods, the suffix API of `InterestPointDetectionGUI`/`DifferenceOfGUI`, `Number_of_thresholds`) are removed again; the `removeDuplicates` fix of 2026-10-08 stays (it keeps a point unless an earlier kept one is within 0.001 px; the old loop never visited the last point). ds1 observation from the multi-threshold runs (2026-10-07): the counts saturate at ~0.0002, below 0.001 only finest-level points are added.
- **Peaks**: non-strict extremum over the 80 neighbours in (x, y, z, scale) via the existing `DoGImgLib2.findPeaks` on a `Views.stack` of the DoG levels; 4D quadratic refinement via imglib2 `SubpixelLocalization` (same settings as `Localization.computeQuadraticLocalization`, failed fits are returned and handled, see below). Threshold semantics as today: prefilter `t/3`, refined `|value| > t`.
- **Stored sigma** = lower Gaussian sigma of the DoG level, `sigmaMin * k^(level-1) * 2^octave`, in full-resolution xy pixels (geometric mean of the x and y scale of the mipmap transform). Response = signed refined DoG value (negative for bright blobs, as the legacy DoG's value). The legacy DoG stores its sigma, too (the dialog's sigma, scaled to full resolution like all sigmas). Intensity (2026-10-19, both methods) = the raw image value at the refined position, linearly interpolated, at the resolution of the detection (`InterestPointTools.readIntensities`; one read per point, negligible): what BigStitcher-Spark's `--storeIntensities` computes.
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

### 3.4 Anisotropy on ds1 (2026-10-02)

ds1 copy, s = 1.8, t = 0.008, steps 4, finest level on, starting resolution (2, 2, 1). The ds1 XML calibration
is 1.1 x 1.1 x 3.512 um (the true voxel size is 0.45 x 0.45 x 2.0 um, see the brief), so the calibration
anisotropy is 3.19 and at (2, 2, 1) the z voxel is 1.6x the x voxel: the finest level is blurred with
(1.8, 1.8, 1.13) px instead of (1.8, 1.8, 1.8) px. `anisotropy=2.0` reproduces the pixel-isotropic run.

| anisotropy A | z sigma of level 1 (px) | points view 0 | stored beads within 0.5 px | within 1 px | within 2 px | median distance |
|---|---|---|---|---|---|---|
| 2.0 (pixel-isotropic, as before) | 1.8 | 4167 | 0.997 | 1.000 | 1.000 | 0.00 |
| 3.19 (calibration, the default) | 1.13 | 6785 | 0.24 | 0.57 | 0.83 | 0.88 |

Reading: with physically isotropic Gaussians the finest level is a different filter than the stored label's
(z is smoothed with 2.3 um instead of 3.6 um), so the stored beads are only partially reproduced (83% within
2 px, positions shift in z) and 60% more points pass the same threshold (less z smoothing suppresses less
noise, and beads that merged in z before separate). This is the intended physics, not a regression; the
threshold and the "same as today" comparison are only meaningful at the same anisotropy. With the true
calibration (4.44) the z sigma of the finest level would be 0.81 px.

Sampling limit (same as the finest-octave effect in 3.2/3.3): where the z sigma of a level drops below ~1 px,
the point-sampled Gaussian kernels overestimate the response by 10-20%; `testAnisotropy` therefore verifies
the anisotropy handling with sigmaMin 3 (z sigmas >= 1.5 px at the detecting octave), where the responses of
physically identical blobs on isotropic and 2x anisotropic grids agree within 1%, the sigmas within 1%, while
pixel-isotropic Gaussians select a 20-24% smaller scale for the same blobs.

## 4. Verification

- `TestDoGScaleSpace`: `testAnisotropy` (physically identical blobs on isotropic and 2x anisotropic grids), `testAutoOctavesAnisotropic` (a thin
  stack allows more octaves with small z sigmas), `testSigmaDiffAnisotropic` (anisotropy 1 bit-identical to isotropic, the closed form of
  the input blur incl. the clamped regime, the hand-over recurrence); partition arithmetic and tiling; with steps = 4 the Gaussian levels 1 and 2 and
  DoG level 1 of octave 0 are **bit-identical** to `computeDoG`'s images (with a different cell size), and
  `detectSpatialExtrema( octave 0, level 1 )` gives the **identical** 162 peaks of the simulated beads
  (after applying the legacy duplicate filter); synthetic blobs of sigma 2, 4, 8, 16 are each found once
  in successive octaves with positions within 0.1 * 2^o px, sigma / blob-sigma constant within 2%,
  responses within 4% for sigmaMin 1.5 (at sigmaMin 1.0 the finest octave overestimates the response by
  ~10%: point-sampled Gaussian kernels at sigma ~1 px, confirmed against the continuous solution in
  Python); processing in blocks (incl. odd boundaries) gives identical peaks to the whole image; with the
  finest level 98.8% of the legacy simulated-bead detections are reproduced within 0.5 px, without it 0;
  the driver equals the shared helper plus `correctForDownsampling`.
- `TestInterestPointStorage` (2026-10-19, was `TestInterestPointSSSaveLoad`): N5 round trip of all three attributes (dtypes, shapes,
  blocks, Gzip, values, index alignment with `id`), plain points stored and loaded as NaN, old-style labels (only `id`/`loc`) load with
  NaN and are refused by a filter, length mismatches rejected, empty lists, DTO round trip, full pipeline save/load via `XmlIoSpimData2`;
  filtered labels: on an unsaved base, chained, ids and attributes kept, read-only, refused by `InterestPointData.from`, own
  correspondences, the XML attributes and the N5 marker (no `loc`/`id`, a plain `InterestPointsN5` on the path loads nothing), the XML
  round trip, a re-detected base, a removed base (error), a detached label (error), a marker that disagrees (error), `dependentLabels`, the
  plain copy (`addFilteredInterestPointsCopy`), `reduceSpimData2` keeps it filtered and the copy saves/loads; `testVirtualDTO`: the Spark
  save path writes the marker and the correspondences of a filtered label and the datasets of its base through one loop;
  `testCopyTo`: splitting-style (points inside an interval, shifted, labels renamed, no correspondences) and copy-style (identity,
  other base path, correspondences) copies of a plain and a filtered label.
- `TestInterestPointFilter`: the range grammar, the dialog entries (sorted, unique, malformed -> null), suffixes, the canonical string,
  descriptions, `accept` (|response|, bands, NaN throws) and the attributes of `InterestPoint` (clone/derive keep them, newInstance not).
- ds1 end to end (2026-10-01): detect -> `InterestPointTools.addInterestPoints` -> `XmlIoSpimData2.save` -> reload gives the points with
  response and sigma for all 18 views, `interestpoints.n5/.../{id,loc,response,sigma}` present.
- ds1 end to end (2026-10-19, headless runner on the working copy, 3 views at (2, 2, 1)): legacy DoG s = 1.8, t = 0.004 (1.3 s) stored as
  `beads-dog` with the filtered labels `_r0.008`, `_r0.016`, `_i100`; scale space s = 1.6, steps 4, t = 0.004 (4.2 s) stored as `ss-filter`
  with `_r0.008`, `_r0.016`, `_s1-2`, `_s2-4`. After save and reload: every point of both base labels has finite response, sigma and
  intensity (DoG sigma 3.6 = 1.8 x 2 everywhere; scale-space sigma 0 .. 18.4, intensity 0 .. 255 raw), every filtered label equals the
  manual filter of its base (view 0: DoG 4583 -> 3921 / 2903 / 278, scale space 12097 -> 10074 / 7049 / 2861 / 6315; the response
  counts are the ones of the old multi-threshold runs plus the duplicate fix), counts monotone in the threshold, the XML carries
  `base`/`filter` attributes and one `filter-interestpoints` record per filtered label, the N5 has the marker (type `filtered`, no `loc`)
  and the correspondences group, fake correspondences set on `beads-dog_r0.008` before the save survive (561) while the base has none,
  the old plain label `beads` loads with NaN attributes and is refused by a response filter, the old scale-space label `beads-ss` loads
  with response/sigma and NaN intensity, removing a base plus its dependants (`dependentLabels`) and saving removes XML entries and N5 groups.
  Full suite: 64 tests pass (`mvn -o clean test`). Not done: clicking through the dialogs in Fiji.
- `TestInteractiveAnisotropy`: the headless parts of the BDV anisotropy tool (fixed transform, calibration ratio, raw multi-resolution levels); the window itself was
  checked by screenshots on ds1 (card with slider, volume rescaled in z between anisotropy 1 and 8).
- `testCandidatesMatchDirectRun` (2026-10-05): the candidates of the simulated beads (threshold 0.001, minima and maxima, cap off) filtered at
  0.004 and 0.02, with and without the finest level, maxima only, equal direct runs peak by peak in order (position, value, sigma, octave,
  type, finest flag, key) for localization 1 and 0; flags and keys are set as documented; the cap (10) raises the threshold. The same
  comparison on ds1 at (2, 2, 1), threshold 0.008: 10074 (finest on) and 564 (off) peaks, identical sets for both localizations.
- `testFitBlobSize` (2026-10-05): model responses are recovered exactly (isotropic and anisotropic), synthetic 3d Gaussian blobs of 0.5 / 1.0 / 1.5 px are
  finest-level detections whose fitted sigma is within 0.2 / 15% of b * sqrt( 2 / 3 ); with no residual accepted all of them get the fallback 1.4,
  a fallback above sigmaMin is capped at sigmaMin.
- `testIntensity` (2026-10-19): unit blobs give an intensity of ~1 (within 0.05) in the scale space and in the legacy DoG (which stores
  sigma 4.0 for every point), all attributes finite, `readIntensities` interpolates linearly (a ramp image); `TestInterestPointDetection.assertDoGAttributes`:
  every DoG detection of the simulated beads has |response| > threshold, sigma = 1.1 * 2 (the dialog's sigma at downsampling 2) and a
  positive finite intensity, the counts 163 / 92 / 60 and the medians are unchanged. `TestScaleSpaceGUI`: the two specification entries,
  no filters by default, sigma filters only in the scale space. (`testMultiThreshold` and `TestDoGMultiThreshold` of 2026-10-07/08 are gone with the feature; their
  ds1 numbers for reference: 0.004 / 0.008 / 0.016 gave 4582 / 3920 / 2902 DoG points and 12097 / 10074 / 7049 scale-space points at (2, 2, 1).)
- `TestInteractiveScaleSpace`: colors of the scales, circle radius and projection, binning per slice, the default ROI budget, the shared slider
  (log mapping, extending / clamping bounds, lower bound 0 only in linear mode), the session without windows. The windows were checked by
  screenshots on ds1 (DISPLAY=:1): the control frame, the "computing" overlay with the disabled image window, the colored overlay on the
  middle slice (pink finest-level circles on beads, red to cyan ones on coarser structures, one-pixel strokes also zoomed out), the second window opened from the drop-down with the scaled rectangle (200 x 200 at (2, 2, 1) -> 400 x 400 at (1, 1, 1)), the automatic recomputation when a slice outside the computed z range is shown (a 512 x 512 rectangle gives z 11..73, slice 3 -> z 0..33, text overlay meanwhile), threshold and finest-level changes update the counts and the overlay, a second window
  at (1, 1, 1) opens with the current values (495k candidates, ROI shrunk to 441 x 441 x 86), Done in it adopts (1, 1, 1), both close.
- Not done: clicking through the Fiji dialog (the GUI path was exercised headlessly: `testGUIRegistered` runs
  `ScaleSpaceGUI.findInterestPoints` and compares with `ScaleSpace.findInterestPoints`; `testDriver` compares the
  driver with `DoGScaleSpace.computeDoGScaleSpace`).

## 5. Known limits, left as they are

- Legacy quirks kept as the reference: BigStitcher-Spark never applies its +-1 block expansion (2-voxel seams between blocks);
  Spark's `--overlappingOnly` uses the current view's mipmap transform for the partner view.
- Empty lists write empty `response`/`sigma`/`intensity` datasets (length 0, 1-D) and load as empty lists.
- Points for matching (`GroupedInterestPoint`, `LinkedInterestPoint`) carry no attributes; derived labels keep them (`derive`).
- pcmatch's `write_interest_points` deletes only `loc`/`id` before it rewrites a label: the attribute datasets of an MVR label it
  overwrites keep their old length and MVR then refuses the label (size mismatch, loud); pcmatch should delete the whole
  `interestpoints` group. pcmatch and BigStitcher-Spark do not know filtered labels (no `loc`/`id`, a marker of type `filtered`): they
  fail on them, which is intended until they resolve `base`/`filter` from the XML. The Spark save path (`InterestPointData`) carries
  filtered labels as base label + filter and writes their marker.
- `ActionHistory.removeByResultRef( "interestpoints:" + label )` also drops registration records whose first label was that label (as before).
- Response comparability across `steps`: a unit blob gives -0.493 (k = 2^(1/3)) vs -0.509 (k = 2^(1/4)),
  so a threshold keeps its meaning within ~3%.
- The intensity range is always taken from the whole image (never from a block) when not provided.

## 6. Next steps (from the brief)

1. BigStitcher-Spark `detect-interestpoints --scaleSpace --steps --octaves [--finestLevel]`: call
   `DoGScaleSpace.computeDoGScaleSpace( input, viewInterval, blockInterval, mask, params, service )` per block, carry response, sigma and
   intensity through the temporary N5 and the block merge (radius `combineDistance * 2^octave`, keep the higher |response|),
   `DownsampleTools.correctForDownsampling` once per view, params string `DOG-SS (Spark) ...`; the legacy Spark detection stores the
   attributes, too (`InterestPointTools.readIntensities` replaces its own `--storeIntensities` code, the sigma is the dialog's).
   Consider the halo alternative (blur the raw image per octave) if the compounding halo hurts.
2. A Spark `filter-interestpoints` command that creates filtered labels like `Filter_Detections.create` (a driver-side XML edit plus
   marker writes, no job; and `--filters` on `detect-interestpoints` like the dialogs), then the `ActionToSparkCli` recipe for
   `filter-interestpoints`. Match-time filters only if still wanted (a filtered label does the same).
3. Noise-floor `thresholdMin` (global 1.4826 * MAD of the finest DoG level over a voxel sample of all views).
4. pcmatch: `load_interest_points_ext` (response, sigma, intensity), `DOG-SS` branch in `dog_sigma_fullres_px`, filtered labels
   (resolve `base`/`filter` from the XML), delete the whole `interestpoints` group when rewriting a label, the sweep (brief section 7).
