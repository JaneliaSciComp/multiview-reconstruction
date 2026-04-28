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
- Java 8, Maven (`mvn compile`)

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
- Build: `mvn compile`. Java 8.
