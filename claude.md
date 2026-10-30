# Multi-View Reconstruction — Project Notes

## Project Context

Multi-view reconstruction combines multiple images of the same specimen taken from different angles/positions/illuminations into a single 3D representation. This project focuses on microscopy data, particularly **SPIM (Selective Plane Illumination Microscopy)** / light-sheet microscopy.

### Core Concepts

#### Views and Timepoints
- **View**: A single image acquisition from a specific angle, position, or illumination direction
- **ViewId**: (timepoint, viewSetup) — identifies a specific view at a specific time
- **ViewSetup**: configuration/angle for a particular view
- **Timepoint**: time index in time-lapse acquisitions

#### Interest Points
- Distinctive features detected in each view (beads, nuclei, high-contrast structures)
- Used as landmarks to establish correspondences between views
- Each interest point has: **local coordinates** (within its view), **detection ID**, and **3D position** (x, y, z)

#### Correspondences
- A match between interest points in different views representing the same physical location
- Shared **correspondence ID** across views
- Essential for computing inter-view transformations
- The InterestPointExplorer GUI is for visual inspection of these matches

#### Coordinate Systems
- **Local**: per-view coordinate system
- **Global**: shared world coordinate system
- **Local-to-Global Transform**: `AffineTransform3D` between them
- **Registration**: the process of computing those transforms from correspondences

#### Reconstruction Workflow
1. **Detect** — find features in each view
2. **Match** — find correspondences between views
3. **Register** — compute transformations to align views
4. **Fuse** — combine aligned views into a single high-quality image

### Technologies
- **ImgLib2** — N-D image processing
- **BigDataViewer (BDV)** — interactive volumetric viewer
- **SPIM Data** — XML-based multi-view dataset format
- **N5 / Zarr** — chunked, compressed n-D array storage
- **Fiji/ImageJ** — plugin host
- Java 21, Maven (`mvn compile`)

### Package Layout
- `net.preibisch.mvrecon.fiji.spimdata.interestpoints` — core IP data structures
- `net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint` — IP GUI
- `net.preibisch.mvrecon.process.interestpointregistration` — registration
- `net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation` — pairwise setup, subsets
- `net.preibisch.mvrecon.process.splitting` — oct-tree image splitting

### Interest Point Storage (Zarr v3 store, 2026-10)

All interest points of a dataset live in `interestpoints.zarr` next to the XML, one self-contained group per (label, timepoint):
- `InterestPointsZarr extends InterestPoints`: one per (view, label), what the XML and all callers use (`InterestPoints.newInstance`,
  `instantiatefromXML`; the XML text stays `tpId_X_viewSetupId_Y/label`). `VirtualFilteredInterestPoints` (a filtered label) extends it.
- `InterestPointsZarrStore`: one per dataset dir (`InterestPointsZarrStore.get( baseDir )`): groups, staging, commit, chunk cache.
- `InterestPointsN5` is static-only: the N5 layout (`interestpoints.n5/tpId_X_viewSetupId_Y/label`) that BigStitcher-Spark's
  temporary block lists and test fixtures still use; `InterestPointsN5ToZarr` reads unconverted N5 entries (read-only fallback
  of the handles), moves the entries of selected views into the store (`legacyLabels` / `legacyLists` / `moveIntoStore`: the
  ViewSetup Explorer's right-click menu Filter/Manage Interest Points > "Resave as ZARR store", multi-threaded, one (label,
  timepoint) group per batch and commit, grayed out when the selected views have nothing old, "ALL labels" when several;
  the N5 is kept) and converts a dataset (`java -cp <fat jar> ...interestpoints.InterestPointsN5ToZarr <dataset.xml>`, which
  verifies and deletes the N5). The XML save moves nothing by itself (Stephan, 2026-10-26: an explicit action, not a side effect).
  Old labels (points without response/sigma/intensity, NaN) are detected again via Filter/Manage Interest Points > "Re-detect
  interest points with attributes" (`fiji.plugin.interestpointdetection.StoredDetectionParameters.parse/preset`: the label's
  parameters string sets the static defaults of the detection dialog, label included via `presetLabel`, and the detection
  replaces the label; otherwise the dialog suggests a label none of the selected views has, `Interest_Point_Detection.suggestLabel`:
  beads, beads2, beads3, a trailing number increased; Stephan, 2026-10-27).
  Since 2026-10-27 the strings also record `localization=`, `groupTiles=`, `groupIllums=`, `thresholdType=` (+ `noiseFactor=`),
  built in `DifferenceOfGUI.commonParameters()`; a stored grouping wins over the explorer's when a label is detected again. A
  string without `anisotropy=` is preset with what its detection used (`effectiveAnisotropy`: downsampleXY / downsampleZ for the
  old and the Spark DoG, the calibration ratio for the early scale space), not the dialog's estimate.
  BigStitcher-Spark's branch `scalespace-pr108` (commit 484b16a of 2026-10-24 plus 2026-10-27) runs on the store: detection
  tasks `stagePoints`, split-images tasks `saveStaged`, the driver's XML save commits (`InterestPointTools.addInterestPointEntries`
  registers a detected label, after `removeReferences` for an existing one), matching commits through
  `saveInterestPointsInParallel`, clear-interestpoints deletes through the lists; its per-block scratch stays an N5 group
  `interestpoints.n5/spark_tmp_*` and the container is removed when it holds nothing else; its "(Spark)" strings carry the
  `commonParameters()` tail. Build it against `mvn install -DskipTests` of this repo (main + tests jar).
  "Only in regions overlapping other views" (2026-10-27, `process.interestpointdetection.OverlappingRegions`): a checkbox of the
  detection dialog; partners = one view per other selected volume (tile, angle) of the same timepoint, the view's own channel
  preferred, whose boxes intersect; regions inside another are dropped (a neighbour tile gives one strip, not one per channel);
  the detection cells are no larger than the region (`OverlappingRegions.cellSize`, same result, a thin strip costs no whole cells);
  one detection call per region (DoG: region + 1 px, the view's intensity range once; scale space: whole image as imageInterval),
  the lists combined at 0.5 px (`combine`, new ids). `InterestPointParameters.regions` null = whole view, unchanged. Key
  `overlappingOnly=` in the strings, `overlappingOnly`/`onlyCompareOverlapTiles` in the history; BigStitcher-Spark calls the same statics.

```
interestpoints.zarr/zarr.json            root: interestpoints = "2.0.0"
<label>/tp<T>/zarr.json                  group: label, timepoint, generation K          (label percent-encoded for the file system)
<label>/tp<T>/gK/points   FLOAT32 [6, N]  x, y, z, response, sigma, intensity (NaN = unknown), all setups in setup order
<label>/tp<T>/gK/id       INT32   [1, N]
<label>/tp<T>/gK/corr     INT32   [3, M]  own detection, partner detection, consensus set; per setup, grouped by partner
<label>/tp<T>/gK/entries  INT64   [6, E]  setup, kind (0 plain, 1 filtered), points offset, count, corr offset, count
<label>/tp<T>/gK/partners INT64   [6, P]  setup, partner tp, setup, label id (attr partnerLabels), offset, count
staging/<millis>_<nanos>_<rnd>.stage     one per Spark task, folded in by the next commit
```
Sharded arrays, raw + crc32c, 65,536 rows per inner chunk, 1,048,576 per shard (statics `defaultChunkRows/defaultShardRows`,
a group keeps its grid). Zarr order reverses the dimensions (`[6, N]` appears as shape `[N, 6]`). Positions are float32 (local
full-resolution pixels, exact to ~0.004 px at 32k px); `Point.l` stays double in memory.

**Writing.** The XML save (`XmlIoSpimData2.saveInterestPointsInParallel`) opens a batch, the lists stage in memory, one `commit()`
writes every (label, timepoint) that changed: new arrays `gK+1` (kept entries copied chunk by chunk, staged ones appended), the
flip of the group's `generation` under a lock file after re-reading the generation (a commit always starts from the generation on
disk, so a JVM that staged earlier merges onto others' commits; a commit that lands inside another's write window is refused, nothing
overwritten, the changes stay staged), then `gK` is deleted. No append, no compaction: a commit costs the (label, timepoint), never the dataset. Each
(view, label) owns its correspondence list (a symmetric match is stored twice), as in the N5 layout. Spark executors never
commit: `InterestPointsZarr.saveStaged( lists )` / `stagePoints(...)` write one staging file per task, the driver's XML save
folds them. Deletes (`deleteInterestPoints` = the whole entry) are staged until the next commit; a group without entries is removed.
Entries still in `interestpoints.n5` stay there across saves (a save stages only modified lists; changed correspondences of such
an entry pull its points along in the commit); "Resave as ZARR store" or the command-line conversion moves them unchanged, NaN
attributes included. `InterestPointsZarrStore.missingEntries( keys )` is the batched "is it in the store" lookup: never call
`hasEntry` per key over many keys, it relists the staging directory and re-reads the group's generation for every key it lacks.

**Reading.** staged → staging file → the group's index (cached per JVM, its generation compared with the disk at most every
`generationCheckMillis` = 2 s, so a group another JVM rewrote is picked up; a missing chunk triggers one reload). An XML entry
with data in neither the store nor the N5 throws; a plain list stored under a filtered label's name, or the other way round,
throws. Decoded chunks are cached per store, bounded by `chunkCacheBytes` (256 MB, `-Dmvr.interestpoints.cacheBytes=0` = off);
it matters on Spark executors whose tasks re-create their lists, not in Fiji.

**Per-point arrays and matching on them (embeddings, 2026-10-29).** A writer may keep rows about the store's points next to the
generations, keyed by point id: `<label>/tp<T>/arrays/<name>/zarr.json` (`columns` D, `dtype` float32, the writer's `meta` with
`model.fingerprint`) and `setup<S>/{id, values}` (int32 [N, 1], float32 [N, D]; pcmatch's `embed` writes one 64-D unit vector
per point as `embedding` (named `descriptor` until 2026-10-29); further tables in `setup<S>` are ignored, pcmatch wrote a `loc` table until 2026-10-29). Java only
reads them, `InterestPointArrays` (`attributes`, `read`, `join`, `vectors( lists, label, name )`): a filtered label reads its base
label's rows; rows are joined by id, and the array must have exactly one row per point of the (base) label in the view (another
number of rows, or a point without a row = the label was detected again and the ids name other points: the view fails with "run
pcmatch embed again"; no positions are compared, Stephan's decision of 2026-10-29: a re-detection changes the number of points;
the count is the base label's, so pcmatch embeds all points of the base even when asked for a filtered label); a NaN row = no
vector. A fresh N5 reader per read (the writer swaps a setup's tables in place). A commit leaves `arrays/` alone, removing the label removes it with its timepoint
group. The matcher on these rows is `pairwise.methods.embeddings.EmbeddingsMatcher` (KD-tree radius query in the registered frame,
inclusive at the radius, so the tree is asked for `Math.nextUp( radius )` and the squared distance tested; one cosine per pair
accumulated in double; a single neighbour is a candidate if cos > smin (the minimal cosine similarity, 0.3 by default; a dialog slider and `--minSimilarity` since 2026-10-29 late); otherwise best/second: candidate if cos > smin and
(1 - best) < ratio x (1 - second), ratio 0.85; one candidate per point of A at most, direction matters) wrapped by
`EmbeddingsPairwise( rp, EmbeddingsParameters( model, searchRadius, ratio, smin, arrayName ), viewInterestPoints )`: it reads the
vectors through the labels' handles (the count check needs the base label's points; note that the lists a matcher gets carry
the registered coordinates, `applyTransformation` derives a point whose `getL()` is global), checks that both views' arrays have one width and one `model.fingerprint`, drops
points without a vector, skips the pair when fewer than max(model, RANSAC) min matches of either view have a partner within the
radius, then RANSAC as BigStitcher-Spark's LOAD_CANDIDATES. GUI: `EmbeddingsGUI` ("Embeddings (stored per-point vectors, pcmatch)":
model, "Limit search radius" + radius (off by default, Stephan: it works great without; 500 px when on, as the geometric descriptor dialog; off = every point of the other view is a possible partner, `Double.POSITIVE_INFINITY`, no KD-tree), ratio, a choice of the stored embeddings the selected labels have (`PairwiseGUI.prepareQuery` runs before
`addQuery` with the views and labels, `InterestPointArrays.names`; none = the dialog cannot be completed), RANSAC; grouped views
refused); Spark `match-interestpoints -m EMBEDDINGS [--searchRadius R] [--descriptorRatio 0.85] [--arrayName x]` (no radius = no limit,
as for PRECISE_TRANSLATION; `--arrayName` only when the labels have several arrays, the one array they have is used otherwise: it is the registration's uncertainty, about 20 px
for a well pre-registered dataset, 300-500 for large translations); the action history maps `searchRadius`, `descriptorRatio` and
`arrayName` (`ActionToSparkCli`). Verified 2026-10-29 against pcmatch's reference candidates on
`~/Documents/pcmatch_data/ds1_java_matching` (1,825 at R = 20, 3,769 at R = 300, id for id over the 11 pairs) and the embryo pair of
`embryo_detect` (filtered label `ss-iso_r0.008`, rows under the base `ss-iso`, points still in the N5: 60,341 candidates at R = 500,
none stale; reading 0.2 s, matching 6-8 s, RANSAC 190 s at 10,000 iterations on 60k candidates). Tests: `TestInterestPointArrays`,
`TestEmbeddingsMatcher`, `TestEmbeddingsPairwise`; BigStitcher-Spark `TestEmbeddings`.

Design and the staging format follow PR 108 (Lila, 2026-09); its global append-only arrays with compaction were replaced by
per-(label, timepoint) groups (Stephan, 2026-10-23: fewer moving parts; whole-label changes dominate; a few test views go into
a separate label). Test: `TestInterestPointsZarr`, `TestInterestPointStorage`.

## InterestPointExplorer GUI

Visualizes interest points + correspondences over BDV. Used for visualization, correspondence inspection, registration QC, and interactive analysis.

### Screen vs World Space
- **World**: 3D global coords
- **Screen**: 2D + depth after the viewer transform. `gPos[0]`, `gPos[1]` = x/y on screen; `gPos[2]` = signed distance from current viewing plane (drives coloring/filtering).

### Visualization Strategy
- Points on the **current viewing plane** (within plane thickness) appear RED
- Points farther from the plane fade with exponential decay
- **Filter mode**: only points within plane thickness are drawn (perf optimization for dense data)
- **Color coding**: each view gets a distinct color; matched correspondences share colors

### Coloring Formulas
- Per-view hue: `0.15 + (viewSetupId * 0.11) % 0.40` (range yellow-green → cyan), sat 0.7, brightness 0.8
- Correspondence-shared hue (overrides per-view when matched): `0.15 + (corrId * 0.17) % 0.70`, sat 0.8, brightness 0.9
- Current-plane highlight: RED for `|gPos[2]| < planeThickness` (when not in filter mode)

### Sliders (range 0–100, paired with editable text fields)

All three sliders have text fields that:
- Display values with 2-decimal precision
- Accept values **outside** the slider range (slider position clamps; model accepts the typed value)
- Update on Enter **or** focus loss

| Slider | Default | Formula | Notes |
|--------|---------|---------|-------|
| Point Size | 30 | `scale = 10^((s-30)/85)`, pixel = `scale * 3.0` | range ≈ 1.3–20 px on slider |
| Plane Thickness | 50 | `100 * (s/100)^5` | s=0 → 0, s=50 → 3.13, s=100 → 100 |
| Distance Fade | 50 | `(s/100)^3` | ≥ 1.0 enters filter mode |

In normal fade mode: `alpha = 255 * exp(-distance * fadeFactor * 0.3)`.
In filter mode (fade ≥ 1.0): background tints light red, only points within plane thickness draw, all alpha = 255.

### Shapes
- 0: filled circle (default, standard IPs)
- 1: `+` cross (first view in 2-view correspondence) — drawn 2× base size
- 2: `×` diagonal (second view in 2-view correspondence) — drawn 2× base size

### Key Files
- `InterestPointOverlay.java` — `InterestPointSource` interface, `getColor()`, `drawOverlays()`, `drawCross()`, `drawDiagonalCross()`
- `InterestPointTableModel.java` — implements `InterestPointSource`, holds slider state (`pointSizeScale`, `planeThickness`, `distanceFade`, `filterMode`); each setter calls `bdvPopup.updateBDV()`. Column "Attributes" (`LabelAttributes`, `computeAttributes`): which of DoG response / intensity / sigma the points of a label store (NaN = unknown), short in the cell ("resp, int, sigma", "none (old)", "int only", "mixed"), spelled out in the tooltip; after a "+" the embeddings of the label (its per-point arrays: `withArrays`, `InterestPointArrays.list`/`rows`; a filtered label shows its base's): "embedding 64-D" when every selected view has one row per point of the base label, "(1/4 views)" otherwise, the array's name only when it is not `InterestPointArrays.EMBEDDING` ("embedding 'descriptor' 64-D"). An old label (none, some, or views that differ) and an incomplete array are drawn in dark red by `MyRenderer` with a tooltip that says what is missing
- `InterestPointExplorerPanel.java` — slider + text-field UI
- `src/main/resources/mvr/InterestPointHelp.html` — F1 help content

### `InterestPointSource` Interface (essence)
```java
HashMap<? extends ViewId, ? extends Collection<? extends RealLocalizable>> getLocalCoordinates(int t);
void   getLocalToGlobalTransform(ViewId v, int t, AffineTransform3D out);
int    getCorrespondenceColorId(ViewId v, int detectionId, int t);
int    getShapeType(ViewId v, int t);
double getDistanceFade();
boolean isFilterMode();
double getPointSizeScale();
double getPlaneThickness();
```

### Lesson: Text-Field ↔ Slider Circular Updates

When a text field types a value outside the slider's clamped range, naively updating the slider triggers its `ChangeListener`, which then overwrites the typed value. Fix:

1. Apply typed value to **model first** (so the model holds the unclamped value).
2. Compute clamped slider position.
3. **Temporarily detach all `ChangeListener`s** from the slider, call `setValue`, then re-attach.
4. Trigger updates on both `ActionListener` (Enter) **and** `FocusListener.focusLost`.

### Lesson: Filter-Mode Transparency Bug

Plane thickness "stopped working" above ~10 px in filter mode because exponential decay was still applied — points farther than ~10 px were nearly invisible. Fix: when `isFilterMode()`, skip decay and set `alpha = 255` for all rendered points (and skip points outside plane thickness entirely as a perf optimization).

### Lesson: F1 Help Focus

F1 didn't fire until the user clicked the table because the panel wasn't focusable. Fix: `setFocusable(true)`, request focus on mouse enter/press, attach the F1 listener to **both** panel and table, and accept both `KeyEvent.VK_F1` and keycode 112 (some envs differ).

## Registration & Subset Detection

`PairwiseSetup` (`...pairwise.constellation.PairwiseSetup`) sets up pairwise view comparisons. Workflow:
1. `definePairs()` — create all pairs to compare
2. `removeNonOverlappingPairs()` — optional filtering
3. `reorderPairs()` — optional consistent ordering
4. `detectSubsets()` — connected-components grouping
5. `sortSubsets()` — optional ordering

### `detectSubsets` Algorithm
Iterates pairs, maintaining two parallel `ArrayList`s: `vSets` (HashSets of views) and `pairSets` (lists of pairs). For each pair (a, b):
- Neither view in any set → new set with both views and the pair
- Only `a` in a set → add `b` and the pair to that set
- Only `b` in a set → add `a` and the pair to that set
- Both in the same set → add the pair
- Both in different sets → **merge** the two sets

After pair iteration: views not in any pair get their own singleton subsets, then groups force-merge subsets containing grouped views, then precursors are converted into `Subset` objects.

### Lesson: `detectSubsets` Merge Bug (2025-12-03)

When both views were already in **different** sets, the original code called `mergeSets()` but **forgot to add the triggering pair** to the merged set. Result: the pair connecting two previously-separate subsets was silently dropped.

```java
else // both present in different sets
{
    mergeSets(vSets, pairSets, i1, i2);
    pairSets.get(pairSets.size() - 1).add(pair);  // FIX: was missing
}
```

`mergeSets` removes the old sets and **appends the merged set at the end** of both `vSets` and `pairSets` — so the merged set is always at index `pairSets.size() - 1`. The Javadoc and inline comments now document this invariant.

**Why it was hard to spot**: every other branch of the conditional correctly added the pair. The merge branch was the only one that didn't, and the bug only manifested when a pair connected previously-disconnected groups.

## Performance: Large Datasets (100K+ Views)

The registration workflow was optimized for 100K+ views. Key bottlenecks were in `TransformationTools.java` and `Subset.java`.

### Optimizations

#### 1. Parallel Interest-Point Loading (`TransformationTools.getAllInterestPoints`)
Was sequential. Now uses `ForkJoinPool(Threads.numThreads())` + `parallelStream()` + `Collectors.toConcurrentMap()`. No fallback — if parallel I/O fails, sequential would too.

#### 2. Pre-Computed Group Membership (`filterForOverlappingInterestPoints`)
Was: `for (Group g : groups) if (g.contains(a) && g.contains(b)) skip;` — O(g) per pair.
Now: pre-compute a `HashSet<Pair<ViewId,ViewId>> sameGroupPairs` once (O(g·k²) where k = avg group size), then O(1) lookup per pair.

#### 3. Parallel Overlap Filtering
Outer loop over views is now a `parallelStream()` inside a `ForkJoinPool`. Each view's `overlappingPoints` list is independent.

#### 4. Optimized `Subset.getGroupedPairs`
Was O(p · g²) (iterated all groups × groups for each pair).
Now: build `Map<V, List<Integer>> viewToGroupIndices` once, then for each pair iterate only `groupsA × groupsB` (O(p · k²) with k ≈ 1–2 typical groups per view). Uses canonical (min, max) ordering to dedupe pairs without bidirectional `HashSet` checks.

### Complexity Summary

| Operation | Before | After |
|-----------|--------|-------|
| Interest-point loading | O(n) sequential | O(n / threads) parallel |
| Group-membership check | O(g) per pair | O(1) lookup |
| Overlap filtering | O(n) sequential | O(n / threads) parallel |
| `getGroupedPairs` | O(p · g²) | O(p · k²) where k ≈ 1–2 |

### Potential Next Optimization
`LoadCorrespondencesPairwise.match()` calls `ipA.getCorrespondingInterestPointsCopy()`, which lazily opens an N5 reader, reads attrs, opens the dataset, and iterates correspondences — per pair. Even with `computePairs()` parallelized, each pair triggers I/O. A bulk parallel pre-load before `computePairs()` would amortize this.

## RGLDM Descriptor Matching (2026-09)

- `RGLDMMatcher` compared every descriptor pair through `SubsetMatcher`/`SquareDistance`, allocating 16 lists per pair.
  That distance is a nearest-neighbor problem in which every descriptor contributes one 9-D "subset vector" per 3-subset of
  its neighbors; `SubsetVectorMatching` solves it exactly (same candidates, `RGLDMMatcherTest`). No radius: `FlatKDTree`
  (primitive arrays, component-major leaves; when pruning fails it degrades into a vectorized scan instead of pointer
  chasing, so it also beats brute force at 4-5 neighbors; at 6+ brute force wins). Radius: 3-D radius query on B, then
  brute force over the in-radius points, or the tree with an owner filter once the radius admits a large share of B
  (thresholds in `preferTree`, e.g. radius 2000 on ExpID99). Ball tree, PCA rotation, projection window and a 3-D
  partial-distance index were all measured and are not better: the descriptors' intrinsic dimension is the full dimension.
  `DescriptorSearch` (GUI pulldown, `RGLDMParameters`, `--descriptorSearch` in BigStitcher-Spark) selects the strategy,
  LEGACY keeps the old loop.
- Benchmarks (not in the build): `tools/rgldm-bench.sh` + `rgldm-chart.py` (decision maps `tools/rgldm-decision*.svg`),
  `tools/match_benchmark.sh` (Spark A/B of the match stage via the pipeline script); numbers in `tools/bench-*.tsv` and the benchmark logs on the share. Never judge interest point reads
  through the Mac SMB mount ("Bad file descriptor"); run on a node.
- Layout: `RGLDMMatcher` builds the descriptors and dispatches; the old loop is `rgldm.legacy.RGLDMLegacy` (reference
  implementation for the test), the fast paths are `rgldm.subsetvector` (`SubsetVectorMatching` public, the strategies
  `FlatTreeSearch` / `BruteForceSearch` and `BestMatches` package-private).
- Geometric hashing (2026-10-10): `GeometricHasher` takes the second-best from a *different* B point. With redundancy r
  every point has C = (3+r choose 3) descriptors in one tree, and the C+1 nearest descriptors of a query always contain
  one of another point, so the per-descriptor ratio test is no longer vetoed by another descriptor of the nearest point
  (RGLDM's C+1 construction, applied per descriptor). Synthetic clouds, n=2000, 5 px jitter, r=2: before 174 candidates /
  152 correct, now 179 / 155; identical at r=0. Folding the C queries of an A point into one min-over-all-pairs distance
  per B point (RGLDM's definition) was tried and is much worse for these 6-D descriptors: the second-best point also gets
  the minimum over C² pairs and the ratio test then rejects most true matches at realistic noise (34 / 33 in the same
  setting). `GeometricHasherTest` checks the implementation against a brute-force evaluation of the definition at 0.3
  and 5 px jitter. Both matchers compare squared descriptor distances, so `ratioOfDistance` is a ratio of squared
  distances (RGLDM default 3, GH default 10).
- FRGLDM (`fastrgldm` package, `FRGLDMGUI`, `--method FAST_TRANSLATION` in BigStitcher-Spark) was removed on 2026-09-30
  together with its descriptor `TranslationInvariantLocalCoordinateSystemPointDescriptor`, whose `localize(double[])` had
  filled only 6 of 9 components since 2017. RGLDM in AUTO mode is faster and exact. BigStitcher-Spark's `scalespace-pr108`
  (mvr 10.0.0-SNAPSHOT) no longer references the removed classes; a BigStitcher-Spark branch that still offers `--method
  FAST_TRANSLATION` must map it to RGLDM/AUTO (or drop it) before it bumps its multiview-reconstruction dependency past 9.0.13.

## BDV Performance — Use Batch APIs

### Lesson: Per-Source Calls Don't Scale

Opening a 12,903-view dataset in Data_Explorer used to take **6+ minutes** before the GUI appeared. Per-source visibility/coloring calls each fire event listeners (~28 ms × 12K sources = ~6 minutes of pure event dispatch).

Switching to BDV's batch APIs brought it to **<1 s**.

### `ViewerState` Batch API (visibility)
Recommended by BDV's author Tobias Pietzsch.
- Access via `bdv.getViewer().state()`
- `state.setSourcesActive(Collection<SourceAndConverter>, boolean)` — batch activate/deactivate
- Use `synchronized(state)` when iterating sources

### `ConverterSetups` API (coloring)
- Access via `bdv.getViewerFrame().getConverterSetups()` — **not** `bdv.getSetupAssignments()`
- `cs.getConverterSetup(SourceAndConverter)` → call `setup.setColor(ARGBType)`

### Pattern
```java
// Visibility
ViewerState state = bdv.getViewer().state();
List<SourceAndConverter<?>> active = new ArrayList<>();
synchronized (state) {
    BDVUtils.forEachAbstractSpimSource(state.getSources(), (soc, src) -> {
        if (activeSetupIds.contains(src.getSetupId())) active.add(soc);
    });
}
List<SourceAndConverter<?>> inactive = new ArrayList<>(state.getSources());
inactive.removeAll(active);
state.setSourcesActive(inactive, false);
state.setSourcesActive(active, true);

// Coloring
ConverterSetups cs = bdv.getViewerFrame().getConverterSetups();
synchronized (state) {
    for (SourceAndConverter<?> soc : state.getSources()) {
        ConverterSetup s = cs.getConverterSetup(soc);
        if (s != null) s.setColor(color);
    }
}
```

### `util/BDVTools.java`
Consolidated batch helpers: `setFusedModeSimple`, `setVisibleSourcesBatch`, `whiteSourcesBatch`, `colorSourcesBatch`, `colorByFactors`, `resetBDVManualTransformations`, `getBDVTimePointIndex`, `getBDVSourceIndex`. Older per-source helpers (`whiteSources`, `sameColorSources`) kept for compatibility but deprecated.

### Avoid
- `bdv.getSetupAssignments().getConverterSetups()` — old API
- `VisibilityAndGrouping.setSourceActive(int, boolean)` — per-source, slow
- Direct `new N5ZarrWriter(...)` — use `URITools.instantiateN5Writer()`

References:
- [`ViewerState`](https://github.com/bigdataviewer/bigdataviewer-core/blob/master/src/main/java/bdv/viewer/ViewerState.java)
- [`ConverterSetups`](https://github.com/bigdataviewer/bigdataviewer-core/blob/master/src/main/java/bdv/viewer/ConverterSetups.java)

## N5 / Zarr Export

### Two Workflows
- **Fusion Export** (`ExportN5Api.java`): blends multiple views into a single fused volume per timepoint/channel, or one combined 5D OME-ZARR `[x,y,z,c,t]` container. Used for final visualization and downstream analysis.
- **Resave** (`Resave_N5Api.java`): per-view re-export to N5 / HDF5 / Zarr **preserving the original view structure**. Multi-resolution pyramids per view, BDV-compatible XML metadata. For Zarr, **always** uses 5D OME-ZARR (3D expanded to `[x,y,z,c=1,t=1]`). Used for converting legacy formats or producing cloud-compatible copies.

**Key distinction**: fusion = one fused volume; resaving = separate per-view volumes.

### Lesson: Always Use `URITools.instantiateN5Writer`
Direct `new N5ZarrWriter(path)` is missing GsonBuilder configuration (including `CoordinateTransformationAdapter`), correct boolean flags for Zarr compatibility, and proper URI handling.

```java
// Correct
N5Writer writer = util.URITools.instantiateN5Writer(
    StorageFormat.ZARR, outputPath.toURI());
```

### Zarr v3 Sharding

Sharding groups multiple inner blocks (e.g., 32³) into larger shards (e.g., 128³) stored in a single file, reducing metadata overhead for cloud storage. Shards must be written as **complete units**.

#### Critical: `Grid.create(dimensions, computeBlockSize, blockSize)`
- `computeBlockSize`: write granularity (size of each write op)
- `blockSize`: inner chunk size in metadata
- **With sharding**: `computeBlockSize = shardSize`, `blockSize` = inner chunk size
- **Without sharding**: `computeBlockSize = blockSize`

#### `MultiResolutionLevelInfo` (`N5ApiTools.java`)
Holds per-level metadata: dimensions, blockSize, downsampling factors, dataset path, dataType, and `shardSize` (null = no sharding).

**Shard size is constant across all pyramid levels** (s0, s1, s2, …). Do not scale with downsampling factors.

For OME-ZARR, sharding metadata expands 3D → 5D `[x,y,z,c,t]`.

#### Lesson: Inverted Ternary Bug (5D OME-ZARR Sharding)
Was: `(useSharding) ? null : shardSize5D` — the operator was inverted, so sharding mode passed `null`. Fix: `(useSharding) ? shardSize5D : null`.

#### Lesson: Downsampled-Levels Shard Size
Originally s1+ levels passed `blockSize` instead of `shardSize` to `assembleJobs()`. `Grid.create()` then made multiple small blocks instead of shard-sized ones, scattering data to wrong positions inside shards. Symptom: "wild" visualization — top-left quadrant for z<64, bottom-right for z≥64. Fix: pass `mrInfo.shardSize` (resave) or `this.shardSize` (fusion) when sharding.

#### Lesson: Zarr v3 Dimensions Reading
`getAttribute(DIMENSIONS_KEY)` returns `null` in Zarr v3 because dimensions live in `zarr.json` as `shape`, not as a separate attribute. Use `getDatasetAttributes(ds).getDimensions()` instead.

#### Lesson: Sharding State Leaked Into HDF5/N5 Export (2026-09-03)
`ExportN5Api.useSharding` defaults to `defaultUseSharding` (true) and was only assigned inside the OME-ZARR dialog branch of `queryParameters()`. For HDF5/N5 it stayed true, `shardSize` was computed for any format, and `setupMultiResolutionPyramid()` created the dataset with `blockSize = shardSize` (e.g. 256x256x128) while blocks were addressed in units of the real block size (64x64x32). HDF5 failed with `selection + offset not within extent` for every block whose grid offset times the shard size left the volume. Fix: reset `useSharding=false`/`shardSize=null` right after the format is chosen, compute `shardSize` only for `StorageFormat.ZARR`, and guard `exportImage()` the same way.

#### Lesson: HDF5 Cannot Delete Empty Blocks at the Boundary (2026-09-03)
`N5Utils.saveNonEmptyBlock()` asks the writer to `deleteBlock()` for chunks that contain only the default value. `N5HDF5Writer.deleteChunk()` (n5-hdf5 3.0.0) emulates deletion by writing a zero block of the **full** block size, which at the dataset boundary fails with `selection + offset not within extent`. It only shows up when a boundary chunk of a downsampled level (or a resaved view) is entirely background, e.g. one channel not covering a corner of the fused volume. Fix: `N5ApiTools.saveNonEmptyBlock()` wraps the call and uses `N5Utils.saveBlock()` (all blocks, cropped) when the writer is an `N5HDF5Writer`. Use this helper instead of `N5Utils.saveNonEmptyBlock()` directly. Upstream fix would be to crop the empty block in `deleteChunk()`.

#### ZARR v2 Support
All ZARR (v3) format checks are paired with `|| StorageFormat.ZARR2` (11 sites: `Resave_N5Api`, `ExportN5Api`, `N5ApiTools`). v3-specific features (sharding dialogs/code paths) are intentionally excluded for v2.

### Gotchas Summary
1. `N5Utils.saveBlock()` **doesn't buffer** — we must hand it complete shard-sized chunks
2. Always use `URITools.instantiateN5Writer()`
3. OME-ZARR sharding metadata must be 5D-expanded
4. Don't scale `shardSize` across pyramid levels
5. `computeBlockSize = shardSize` is critical for shard-aware writing

### Test Status
`TestN5Zarr` multi-resolution sharding tests fail with NPE at `PaddedRawBlockCodec.encode()` (via `writeDownsampledBlock` → `N5Utils.saveNonEmptyBlock`). Production GUI export works. Hypothesis: test-specific parameter init issue.

## 4D / 5D OME-ZARR Import

Axis order: TCZYX (in metadata) / XYZCT (data dimensions, reversed).

`OMEZARREntry.indices`:
- 3D: `null` or `[]`
- 4D: `[channelId]`
- 5D: `[channelId, timepointId]`

`AllenOMEZarrLoader.extract3DVolume()` slices dims `3 .. 3+len-1` from the back:
```java
RandomAccessibleInterval<T> out = omeZarrVolume;
for (int d = 3 + indices.length - 1; d >= 3; --d)
    out = Views.hyperSlice(out, d, indices[d - 3]);
```

### Lesson: Slice Dimension for 4D
`AllenOMEZarrLoader.java:119` — for 4D, slice **dimension 3**, not 4. (The 5D code path slices 4 then 3, but 4D only has dimensions 0..3.)

### `OMEZARR.java` Behavior
- Splits sizeC and sizeT init (4D has only sizeC)
- Allows 4D in dimension checks
- Smart fallbacks:
  - 4D with sizeC=1 → treat as 3D with pattern-based channels
  - 5D with sizeC=1 and sizeT=1 → treat as 3D with pattern-based views

Use case: multi-channel single-timepoint microscopy (e.g., multi-color fluorescence).

**Export not implemented** — import only. Fusion export creates 5D `[x,y,z,c=1,t=1]` which works for round-trips.

### XML Examples
```xml
<zgroup setup="0" tp="0" path="dataset.zarr" indicies="[0]"/>      <!-- 4D, channel 0 -->
<zgroup setup="1" tp="0" path="dataset.zarr" indicies="[1]"/>      <!-- 4D, channel 1 -->
<zgroup setup="0" tp="0" path="dataset.zarr" indicies="[0 0]"/>    <!-- 5D, c=0 t=0 -->
<zgroup setup="0" tp="1" path="dataset.zarr" indicies="[0 1]"/>    <!-- 5D, c=0 t=1 -->
```

## Oct-Tree Adaptive Image Splitting

Adaptively subdivides large images into tiles based on interest-point correspondence density. Regions with many correspondences (potential conflicts / multiple consensus sets) are split into smaller tiles; regions with few correspondences stay large. Useful for very large datasets where registration quality varies spatially.

### Files (`net.preibisch.mvrecon.process.splitting`)
- `SplitOctTree.java` — main algorithm
- `OctTreeSplitCriterion.java` — interface
- `CrossViewCorrespondenceCriterion.java` — simple count-based
- `ConsensusSetCriterion.java` — multi-consensus RANSAC-aware
- `SplitDistributeEvenly.java` — alternative uniform grid splitting
- `SplittingTools.java` — entry point utilities

### `OctTreeSplitCriterion` Interface
```java
List<SplitCorrespondence> loadCorrespondences(ViewId viewId);   // once per view
boolean shouldSplit(List<SplitCorrespondence> corrs);
default boolean canMerge(List<SplitCorrespondence> corrs);      // default: !shouldSplit
```

`SplitCorrespondence`:
- `double[] location` — local coords (for spatial partitioning)
- `int detectionId` — unique within the view
- `String corrViewKey` — `"timepointId_setupId"`
- `int consensusSetId` — RANSAC consensus set, or -1 for single-consensus mode

### Criteria
- **CrossViewCorrespondenceCriterion**: split when unique cross-view detections > threshold (default 20)
- **ConsensusSetCriterion** (multi-consensus): stop splitting when **either** unique detections ≤ threshold (default 12) **or** all corresponding views have correspondences from only one consensus set. Continue only if detections > threshold AND any view has > 1 consensus set.
  - Tolerance modes: `TOLERANCE_NONE`, `TOLERANCE_PERCENTAGE` (X% from other sets allowed), `TOLERANCE_COUNT` (N from other sets allowed)

**Why multi-consensus matters**: when RANSAC finds multiple transformation models in a view pair, that region likely contains parts of the sample that moved differently. Splitting isolates them for separate registration.

### Algorithm

#### Recursive Correspondence Partitioning
O(n log n) instead of naive O(n × intervals):
1. Load correspondences once per view at the start
2. As intervals split, partition correspondences (don't re-query)
3. Per dimension at split point `p` with overlap `minStepSize`:
   - `loc[d] < p - minStepSize` → lower child only
   - `loc[d] >= p + minStepSize` → upper child only
   - else → **both** children (overlap region)

#### Tile Overlap
All tiles overlap by `minStepSize` to support fake corresponding-point generation at boundaries and proper stitching post-processing.

#### Block Re-Merging
After splitting, adjacent blocks can be merged back if `canMerge` permits:
1. Full merge: 8 octants → parent
2. Half-space merges: 4 + 4 along each dim
3. Quadrant merges: 2 + 2 + 2 + 2
4. Individual octant merges within each octant

#### `minSplitLevels`
Force minimum split operations regardless of correspondence count:
- 0: fully adaptive (may not split at all)
- 1: at least once (up to 8 tiles)
- 2: at least twice (up to 64 tiles)

Validated upfront against tile-size constraints to prevent impossible configurations.

### Static vs Instance
- **Instance**: sequential, accumulates statistics. `splitter.setCurrentContext(viewId, tpId); splitter.split(input)`.
- **Static**: parallel-safe, returns `SplitStatistics`. `SplitOctTree.splitStatic(input, viewId, criterion, minStepSize, minSizeMultiplier, enableMerge, minSplitLevels)`.

### GUI
Standard `GenericDialog` flow:
```java
SplitOctTree.setupGUI(gd, data, minStepSize);  // criterion + parameters
gd.showDialog();
SplitOctTree splitter = SplitOctTree.queryGUI(gd, data, minStepSize);
```
Features: per-dimension min tile-size sliders (X, Y, Z), criterion choice, IP-label multi-select, tolerance mode for multi-consensus.

### Default Grouping for Split Datasets
When opening a Split dataset in Data Explorer:
- **Group Tiles** is ON by default
- **Group Illuminations** is OFF by default

Detected via `isSplitDataset()` checking for `SplitViewerImgLoader` / `SplitMultiResolutionImgLoader`.

## House Rules

- **Never commit without explicit user consent.**
- Branch state: read `git status` / `git log` — don't rely on stale notes here.
- Build: `mvn compile`. Java 21 (`maven-enforcer-plugin` requires JDK 21+).
