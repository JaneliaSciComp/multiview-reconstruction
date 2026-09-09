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

### Interest Point Storage
- **N5 format** (`InterestPointsN5.java`): modern, scalable backend
- Legacy text-file backend (`InterestPointsTextFileList.java`) was removed
- Stored fields: ID, position (x, y, z), optionally correspondences to other views

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
- `InterestPointTableModel.java` — implements `InterestPointSource`, holds slider state (`pointSizeScale`, `planeThickness`, `distanceFade`, `filterMode`); each setter calls `bdvPopup.updateBDV()`
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

## APGO — Lie-group global optimization (2026-09-09)

A Java port of [bigstream `stitch.py::find_tile_transforms`](https://github.com/JaneliaSciComp/bigstream/blob/499c114/bigstream/stitch.py#L483),
in `...process.interestpointregistration.global.apgo`. Where `GlobalOpt` iterates point matches
through mpicbg's `TileConfiguration`, APGO collapses each link to one pairwise transform and
solves on the affine Lie group: matrix-log init, then Gauss-Newton.

Selectable as `GlobalOptType.APGO`. The transformation model the user selects is used to fit each
link's pairwise transform (`Parameters.pairwiseModel`, a prototype copied per link — any
`Model & Affine3D` works, including `InterpolatedAffineModel3D`). The **solve itself is always
over full affines**, which is intrinsic to the method, so a lower-DOF selection constrains the
links, not the per-view output; APGO logs a note when the two differ. Measured per-link model
against bead max error: affine 8.355, affine+rigid λ=0.01 8.356, rigid 8.583, translation 8.997 —
so the production selection is indistinguishable from pure affine, and full affine is the right
default under `INFORMATION`.

**It beats `GlobalOpt` on this data.** Benchmarked on `20260819_ExpID96_S4` (1678 views, 13370
links, 1.03M correspondences) from the pre-solve state, production config (3 px, 12 inliers,
affine+rigid λ=0.01, splitPoints weight 0.01):

| solver | beads mean / max | splitPoints mean / max | solve |
|---|---|---|---|
| none (pre-solve) | 10.52 / 74.57 | 1.13 / 1.63 | – |
| GlobalOpt | 2.069 / 9.73 | 3.437 / 30.97 | 119 s |
| APGO affine / INFORMATION, `M` only | 2.023 / 8.355 | 1.677 / 8.371 | 3.9 s |
| **APGO affine / INFORMATION, `M/σ²`** | **2.024 / 6.614** | **1.456 / 5.792** | **8.2 s** |

`GlobalOpt` must sacrifice the splitPoints to fit the beads; APGO does not have to.

### The one thing that matters: a scalar weight cannot express directional uncertainty

Overlap regions are thin slabs, so a link constrains its affine columns along the thin axis
hundreds of times more weakly than translation — measured `σ_min/σ_max` of 0.20 for beads and
0.025 for splitPoints, i.e. curvature ratios of 25x and 1540x *within the same link*. A scalar
weight scales the whole 12-D residual uniformly: it can say "trust this link less", never "this
link determines translation but not shear".

`Weighting.INFORMATION` replaces `Σ w‖r‖²` with `Σ rᵀΛr`, where `Λ = I₃ ⊗ M` and
`M = Σᵢ wᵢ[pᵢ;1][pᵢ;1]ᵀ` is the weighted second moment of the link's source points — one 4x4
accumulation per link, no free parameters.

`Λ = JᵀWJ` is the Hessian of the per-link fit, so it is the same quadratic form as
`Σᵢ‖G_a pᵢ − G_b qᵢ‖²` — the collapse is lossless to second order, and match count and label
weights are subsumed because both already live in `M`.

`Parameters.scaleByResidualVariance` (on by default) completes it to the full `Λ = JᵀWJ / σ²` by
dividing each link by its own fit residual. Per-link rms runs 1.46 → 16.6 px for beads against a
median of 2.31, so without it a badly-fitting link is trusted per-point as much as a good one and
sets the worst case. With it, bead max drops 21% (8.36 → 6.61) and splitPoints max 31%
(8.37 → 5.79) while mean and median do not move — it fixes the tail. The ~130x spread it
introduces is far below the 10^4 that makes a *scalar* weight diverge, because it rescales links
without touching the directional structure. `minResidual` (0.5 px) floors it; the smallest
observed rms is 1.0, so on real data it never binds — it exists so an exact synthetic fit does not
divide by zero.

The evidence for the information matrix itself is that **the DOF trend reverses**. Per-link max
bead error:

| | affine (12) | rigid (6) | translation (3) |
|---|---|---|---|
| scalar `UNIFORM` | 49.79 | 23.35 | 17.24 |
| `INFORMATION` | **8.35** | 8.56 | 8.98 |

Extra parameters are noise when the objective cannot say which ones a link measured, and signal
when it can. Nothing else changed between those two rows.

### Performance: two fixes worth 166 s → 3.8 s

- **Never use `Tile.findConnectedTile`.** It answers "which tile owns the other end of this match"
  by scanning every match of every connected tile for an identity hit — O(partners × their
  matches). At 2M matches that was ~40 s, most of the runtime. A tile's own matches always carry
  `p1` on that tile (`PointMatch.flip` keeps the `Point` objects, only swapping roles), so one
  `IdentityHashMap<Point,Integer>` pass answers it in O(1).
- **Do not over-converge the inner solve.** CG was hitting its 10000-iteration cap every time — a
  graph Laplacian's condition number grows with diameter², and the information anisotropy
  multiplies it, so a tight tolerance is unreachable *and* unnecessary for a Gauss-Newton inner
  step. Quality is flat from 50 to 2500 iterations. Same story outside: `convergenceThreshold`
  stops GN after 1 iteration here and does not fire on synthetic data that needs all 10.

The matrix-log initialization alone already beats `GlobalOpt` (2.021 / 8.674 in 2.9 s).

### Gotchas

- **`Model.fit(Collection)` reads `p1.getL()` against `p2.getW()`.** `Tile.apply()` overwrites
  `getW()`, so fitting after another solver has run silently uses *its* output. Build fresh
  `Point`s from `getL()` on both sides.
- **Split-boundary correspondences are coplanar**, so `AffineModel3D.fit` throws
  `IllDefinedDataPointsException`. Without an affine→rigid→translation fallback every such link is
  silently dropped.
- **One bad link poisons everything** — all views are coupled through the incidence matrix, so a
  single non-finite `log T` turns the whole solution into NaN with no other symptom. Validate
  determinant and finiteness at fit time.
- **`XmlIoSpimData2.saveWithFilename` takes a bare file name**, assembled against `basePath`. An
  absolute path silently builds a nested `<basePath>/Volumes/.../dataset.xml` tree and copies
  `interestpoints.n5` into it.
- **`GlobalOpt` is not deterministic** with a plain affine model: three runs on identical input
  gave max 20.9 / 14.4 / 36.2 px. The production rigid-regularized model is much tighter
  (25.3 / 27.3 / 27.6). Report it as a range over repeats; APGO is bit-identical.

### Multi-consensus: no solver consumes it

`RANSAC` multi-consensus mode (GUI checkbox → `RANSACParameters`) flattens all consensus sets into
`PairwiseResult.getInliers()` with a parallel `getInlierSetIds()`. **`InterestPointMatchCreator`
never reads the set ids**, so `GlobalOpt` and APGO both receive the sets merged and fit one
relative transform to their union — a least-squares compromise between incompatible models,
weighted by set size. `MaxErrorLinkRemoval` buckets per partner tile, so its only lever is
dropping the whole link.

The ids are persisted (`Interest_Point_Registration:391` → v2 N5, 4xN) and consumed **only** by
`process/splitting/*`. The intended pipeline is: detect multi-consensus at match time → persist →
`ConsensusSetCriterion` cuts tiles where sets disagree → re-register, now single-consensus. Note
`LoadCorrespondencesPairwise` reads the ids and discards them, so a "Load Correspondences" run
cannot carry the signal forward — it is available exactly once, at match time.

### Method notes for re-running this

- **Score per label, never pooled.** 10503 of the 13370 pairs are `splitPoints`, which start out
  already consistent (max 1.63 px before any solve), so pooled percentiles mostly measure the
  tiling glue and hide the registration. This produced a wrong conclusion once.
- **Benchmark from the pre-solve state**, stripping the leading `AffineModel3D regularized...`
  transform — otherwise you are asking each solver to improve on an already optimal answer.
  Both solvers are insensitive to the starting point: also stripping `Stitching Transform`
  (baseline 45.2 / 151.6) changes the answers by <0.01 px.
- **Cache the pairwise results.** `-Dapgo.cache=/path/pairs.bin` in `BenchmarkAPGO`; assembling
  them costs ~2.5 min over SMB and reloads in 0.2 s. Do this before debugging numerics.
- **Validate against the real reference.** `~/basic2/apgo_reference.py` runs bigstream's
  `find_tile_transforms` verbatim on a bipartite grid exported by `APGOReferenceExport`; Java and
  bigstream agree to 3.2e-13. Plus exact-recovery on synthetic data (1.6e-13), which is what
  catches a wrong sign or slot convention. `~/basic2` has numpy 1.26 / scipy 1.12.
- Compare **displacement of points**, never raw matrix entries — linear terms of ~0.02 against
  translations of ~10 in one threshold is meaningless.

### Every approximation is measured, and none of them matter

Audited on the hardest available starting point (solve *and* stitching stripped, bead baseline
45.2 mean / 151.6 max). Effect on bead error:

| approximation | how tested | effect |
|---|---|---|
| `dexp⁻¹` omitted from the Jacobian | implemented exactly, checked against finite differences | 0.02 px max |
| BCH + linearization of the step | step damping 1.0 → 0.125 with 8x the iterations | **0.0005 px** |
| CG stopped at 250 iterations | swept 50 → 2500 | 0.05 px |
| Gauss-Newton stopped adaptively | swept 0 → 10 iterations | 0.02 px vs full |
| Tikhonov prior 1e-6 | swept 1e-9 → 1e-2 | flat to 1e-4, biased only at 1e-2 |
| `Λ = I₃ ⊗ M` assumes the linear part is I | deviation measured at 2.7%; ±5/20/50% weight jitter | ≤0.01 px |
| `Mat4` exp/log truncation | round trip over 1000 random affines | 7.6e-14 |

Total spread across every setting: ~0.1 px on max and ~0.002 px on mean, against a 1.3 px max gap
to `GlobalOpt`. The conclusion rests on none of them. `Parameters.stepDamping` and `tikhonov`
remain as diagnostics for re-running this — halve the step and double the iterations, and if
second-order truncation mattered the answer would improve.

The `dexp⁻¹` row deserves a note, since it is the one that looks like it should matter. The
`dexp` the reference applies, to the residual, is provably a no-op: it is applied to `r` itself
and `dexp_x(x) = x` because `ad_x(x) = 0` (measured: 5e-16). The correction only bites on the
*solved* step, which is not parallel to `r` once many links compromise —
`Parameters.jacobianDexpOrder` implements it there properly, and it is worth 0.02 px. It scales
with `‖r‖`, so it would matter on data whose initial misalignment is large.

These are all *local* sensitivity tests around the found solution; they show nothing is biasing
the answer, not that no distant better optimum exists. The best evidence for the latter is that
two starting points differing 4x in initial error converge to within 0.003 px (see method notes).

### Measured dead ends — do not retry

Scalar weighting by match count or by fit residual (both diverge — any heterogeneous scalar drives
the weighted Laplacian near-singular); affine regularized toward rigid/translation per link (the
optimum sits at the boundary, never beating pure translation); per-label discrete DOF (a two-level
approximation of the information matrix, underperforms plain translation); beads-only links (5
disconnected components, 93 unconstrained views — the splitPoints carry the connectivity).

## House Rules

- **Never commit without explicit user consent.**
- Branch state: read `git status` / `git log` — don't rely on stale notes here.
- Build: `mvn compile`. Java 21 (`maven-enforcer-plugin` requires JDK 21+).
