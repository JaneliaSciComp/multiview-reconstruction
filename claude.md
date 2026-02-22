# InterestPointExplorer GUI - Development Summary

## Overview
This document summarizes the recent enhancements to the InterestPointExplorer GUI system, specifically the interest point overlay rendering and interactive controls in BigDataViewer (BDV).

## Project Context: Multi-View Reconstruction

### What is Multi-View Reconstruction?

Multi-view reconstruction is the process of combining multiple images of the same specimen taken from different views (angles/positions) into a single, high-quality 3D representation. This project focuses on microscopy data, particularly **SPIM (Selective Plane Illumination Microscopy)**, also known as light-sheet microscopy.

### Key Concepts

#### Views and Timepoints
- **View**: A single image acquisition from a specific angle, position, or illumination direction
- **ViewId**: Combination of timepoint and view setup (identifies a specific view at a specific time)
- **ViewSetup**: The configuration/angle for a particular view
- **Timepoint**: Time index in time-lapse acquisitions

#### Interest Points
- **Interest Points**: Distinctive features detected in each view (e.g., beads, nuclei, or high-contrast structures)
- Used as landmarks to establish correspondences between views
- Each interest point has:
  - **Local coordinates**: Position within its own view's coordinate system
  - **Detection ID**: Unique identifier within the view
  - **Location**: 3D position (x, y, z)

#### Correspondences
- **Correspondence**: A match between interest points in different views that represent the same physical location
- **Correspondence ID**: Shared identifier for matched points across views
- Essential for computing transformations between views
- The InterestPointExplorer allows visual inspection of these correspondences

#### Coordinate Systems and Transformations
- **Local coordinates**: Interest point positions in each view's own coordinate system
- **Global coordinates**: Common world coordinate system shared by all views
- **Local-to-Global Transform**: AffineTransform3D that converts from view-specific to world coordinates
- **Registration**: Process of computing these transformations to align views

#### The Reconstruction Workflow
1. **Interest Point Detection**: Find distinctive features in each view
2. **Interest Point Matching**: Find correspondences between views
3. **Registration**: Compute transformations to align views based on correspondences
4. **Fusion**: Combine the aligned views into a single high-quality image

### Role of InterestPointExplorer

The InterestPointExplorer GUI serves several purposes:
- **Visualization**: Display interest points overlaid on the image data in BigDataViewer
- **Inspection**: Examine correspondences between views
- **Quality Control**: Verify that matching and registration are working correctly
- **Interactive Analysis**: Filter and highlight specific correspondences

### Technical Details

#### Screen Space vs World Space
- **World space**: 3D coordinates in the global coordinate system
- **Screen space**: 2D+depth coordinates after applying the viewer transform
  - `gPos[0]`, `gPos[1]`: x, y screen position
  - `gPos[2]`: Distance from the current viewing plane (used for coloring/filtering)

#### Visualization Strategy
- Points on the **current viewing plane** (within plane thickness) appear RED
- Points **farther away** fade based on distance (exponential decay)
- **Filter mode**: Only show points on current plane (performance optimization)
- **Color coding**: Different views get different colors, matched correspondences share colors

### Data Storage

#### Interest Point Storage
The project supports multiple storage backends for interest points:
- **N5 format**: Modern, scalable format (InterestPointsN5.java)
- **Text files**: Legacy format (InterestPointsTextFileList.java - recently deleted)
- Interest points are stored as:
  - ID (unique identifier)
  - Position (x, y, z coordinates)
  - Optionally: correspondences to other views

#### Recent Changes (separateMatchSolve branch)
The current branch is working towards separating correspondence handling:
- Changed abstract method from returning `List<InterestPoint>` to `Map<ID, InterestPoint>`
- This allows more efficient lookups when working with correspondences
- Breaking change that requires updates throughout the codebase

### Project Organization

#### Package Structure
- `net.preibisch.mvrecon.fiji.spimdata.interestpoints`: Core interest point data structures
- `net.preibisch.mvrecon.fiji.spimdata.explorer.interestpoint`: GUI components for exploration
- Integration with **Fiji/ImageJ** ecosystem
- Uses **BigDataViewer** (BDV) for 3D visualization

#### Key Technologies
- **ImgLib2**: N-dimensional image processing library
- **BigDataViewer**: Interactive viewer for large volumetric datasets
- **SPIM Data**: XML-based format for multi-view datasets
- **N5**: Chunked, compressed n-dimensional array storage

## Key Features Implemented

### 1. Interest Point Coloring System

#### Per-View Color Variation
- Each view gets a distinct color shade using HSB color space
- Hue varies from yellow-green through green to cyan (0.15-0.55)
- Formula: `hue = 0.15f + (viewSetupId * 0.11f) % 0.40f`
- Consistent saturation (0.7) and brightness (0.8)

#### Correspondence-Based Coloring
- When interest points are matched between views, they get a shared color
- Overrides per-view coloring when correspondence exists
- Formula: `hue = 0.15f + (corrId * 0.17f) % 0.70f`
- Higher saturation (0.8) and brightness (0.9) for visibility

#### Current Plane Highlighting
- Points within plane thickness show in RED (not in filter mode)
- Distance from viewing plane calculated in screen space: `Math.abs(gPos[2])`

### 2. Interactive Sliders with Text Fields

All three sliders have editable text fields that:
- Display actual values with 2 decimal precision (`%.2f`)
- Accept values OUTSIDE slider range
- Update on Enter key press OR when focus is lost
- Slider position clamps to valid range while model accepts any value

#### Point Size Slider (Range: 0-100, default: 30)
- **Formula**: `scale = 10^((sliderValue-30)/85)`
- **Actual pixel size**: `scale * 3.0`
- **Range**: 1.3 - 20.0 pixels (slider range)
- **Text field**: Shows pixel size, accepts any positive value
- **Implementation**: InterestPointExplorerPanel.java:149-216

#### Plane Thickness Slider (Range: 0-100, default: 50)
- **Formula**: `thickness = 100 * (sliderValue/100)^5`
- **Mapping**: slider=0 → 0, slider=50 → 3.13, slider=100 → 100
- **Purpose**: Controls red highlighting threshold and filter mode cutoff
- **Text field**: Shows thickness value, accepts any positive value
- **Implementation**: InterestPointExplorerPanel.java:218-283

#### Distance Fade Slider (Range: 0-100, default: 50)
- **Formula**: `fadeFactor = (sliderValue/100)^3`
- **Range**: 0.0 (no fade) to 1.0+ (filter mode)
- **Filter mode**: When fadeFactor ≥ 1.0
  - Background turns light red
  - Only renders points within plane thickness
  - All points fully opaque (alpha=255)
- **Normal mode**: Exponential transparency decay
  - Formula: `alpha = 255 * exp(-distance * fadeFactor * 0.3)`
- **Text field**: Shows fade factor, accepts any positive value
- **Implementation**: InterestPointExplorerPanel.java:285-400

### 3. Shape Rendering System

Three shape types for interest points:

#### Circle (shapeType = 0, default)
- Filled oval
- Used for standard interest points

#### Cross (shapeType = 1)
- Plus sign (+)
- Horizontal and vertical lines
- 2x larger than base size
- Used for first view in 2-view correspondences

#### Diagonal Cross (shapeType = 2)
- X shape (×)
- Diagonal lines at 45°
- 2x larger than base size
- Used for second view in 2-view correspondences

**Implementation**: InterestPointOverlay.java:137-149, 207-221

### 4. Filter Mode Optimization

When Distance Fade ≥ 1.0:
- Performance optimization: skips rendering points outside plane thickness
- All points within plane thickness rendered at full opacity
- Fixed bug where exponential decay made points invisible beyond ~10 pixels

**Implementation**: InterestPointOverlay.java:101-106, 191-193

## File Structure

### Core Files

#### InterestPointOverlay.java
`src/main/java/net/preibisch/mvrecon/fiji/spimdata/explorer/interestpoint/InterestPointOverlay.java`

**Key interface**:
```java
public static interface InterestPointSource {
    HashMap<? extends ViewId, ? extends Collection<? extends RealLocalizable>> getLocalCoordinates(int timepointIndex);
    void getLocalToGlobalTransform(ViewId viewId, int timepointIndex, AffineTransform3D transform);
    int getCorrespondenceColorId(ViewId viewId, int detectionId, int timepointIndex);
    int getShapeType(ViewId viewId, int timepointIndex);
    double getDistanceFade();
    boolean isFilterMode();
    double getPointSizeScale();
    double getPlaneThickness();
}
```

**Key methods**:
- `getColor()`: Lines 91-130 - Color calculation with transparency
- `drawOverlays()`: Lines 165-225 - Main rendering loop
- `drawCross()`: Line 137 - Plus sign rendering
- `drawDiagonalCross()`: Line 144 - X shape rendering

#### InterestPointTableModel.java
`src/main/java/net/preibisch/mvrecon/fiji/spimdata/explorer/interestpoint/InterestPointTableModel.java`

Implements InterestPointSource interface, stores parameters:
- `pointSizeScale`: Default 1.0
- `planeThickness`: Default 3.0
- `distanceFade`: Default 0.125
- `filterMode`: Boolean flag

Each setter calls `bdvPopup.updateBDV()` to trigger redraw.

#### InterestPointExplorerPanel.java
`src/main/java/net/preibisch/mvrecon/fiji/spimdata/explorer/interestpoint/InterestPointExplorerPanel.java`

Contains all slider and text field UI components. Key sections:
- Lines 146-216: Point Size slider + text field
- Lines 218-283: Plane Thickness slider + text field
- Lines 285-400: Distance Fade slider + text field

## Text Field Update Pattern

Critical pattern to prevent circular updates:

```java
final ActionListener textFieldListener = new ActionListener() {
    @Override
    public void actionPerformed(ActionEvent e) {
        try {
            double value = Double.parseDouble(textField.getText());

            // 1. Apply to model FIRST
            tableModel.setValue(value);

            // 2. Calculate slider position (clamped)
            int sliderValue = calculateInverseFormula(value);
            sliderValue = Math.max(0, Math.min(100, sliderValue));

            // 3. Temporarily remove ChangeListeners
            ChangeListener[] listeners = slider.getChangeListeners();
            for (ChangeListener listener : listeners)
                slider.removeChangeListener(listener);

            // 4. Set slider value
            slider.setValue(sliderValue);

            // 5. Re-add listeners
            for (ChangeListener listener : listeners)
                slider.addChangeListener(listener);
        } catch (NumberFormatException ex) {
            // Reset to current model value
        }
    }
};

// Trigger on Enter AND focus lost
textField.addActionListener(textFieldListener);
textField.addFocusListener(new FocusAdapter() {
    @Override
    public void focusLost(FocusEvent e) {
        textFieldListener.actionPerformed(null);
    }
});
```

This pattern ensures:
- Typed values apply to model even if outside slider range
- Slider shows clamped position
- No circular update loops

## Recent Commits

### Master Branch
1. `34bff636` - Fix F1 help focus issue in InterestPointExplorer
2. `8994af77` - Add comprehensive F1 help window for InterestPointExplorer

### splitCorr Branch (Previous Work)
1. `5ffb2c2b` - Change text field precision from 1 to 2 decimal digits
2. `55c09e4c` - Add editable text fields to sliders and fix filter mode transparency
3. `acd3c93e` - Format slider labels with smaller font and two-line layout
4. `30883cbf` - Add plane thickness slider with power scaling
5. `982364ac` - Add point size slider with exponential scaling
6. `c1115605` - Add distance fade slider with exponential scaling and filter mode
7. `e41ea3a8` - Add correspondence-based coloring and cross shapes for 2-view mode
8. `d58f6ad3` - Add per-view color variation in interest point overlay

### 5. F1 Help Window System

#### Comprehensive Help Documentation
A complete help system was added to the InterestPointExplorer:

**Help Content** (InterestPointHelp.html):
- Table columns and their meanings (#Detections, #Corresponding, #Correspondences)
- Point Size and Plane Thickness slider controls with mathematical formulas
- Distance Fade slider and filter mode
- Color coding system for visualization
- Interactive 3-state selection cycling (all corresponding → inter-visible → deselect)
- Cell clicking and editing capabilities
- Context menu operations (delete)
- Common workflows (detection quality inspection, correspondence analysis, cleanup)
- Best practices and tips
- Technical details including scaling formulas and data structures

**Implementation** (InterestPointExplorerPanel.java):
- F1 key listener attached to both table and panel
- Supports both `KeyEvent.VK_F1` and keyCode `112`
- Panel made focusable with `setFocusable(true)`
- Auto-focus on mouse enter/press for immediate F1 functionality
- Help dialog displayed with `HelpDialog` from BDV tools

**User Experience**:
- Window title shows "(Press F1 for help)"
- F1 works immediately when hovering or clicking anywhere in the window
- No need to click on table rows first

**Files**:
- `src/main/resources/mvr/InterestPointHelp.html` (219 lines)
- `src/main/java/net/preibisch/mvrecon/fiji/spimdata/explorer/interestpoint/InterestPointExplorerPanel.java`
- `src/main/java/net/preibisch/mvrecon/fiji/spimdata/explorer/interestpoint/InterestPointExplorer.java`

**Commits**:
- `8994af77` - Initial help window implementation
- `34bff636` - Fixed focus issue for immediate F1 response

## Important Notes

- **Never commit without explicit user consent**
- Current branch: `master` (recent F1 help work)
- Previous work branch: `splitCorr` (slider implementations)
- Main branch: `master`
- Build system: Maven (`mvn compile`)
- Java version: 8

## Common Issues Fixed

### Filter Mode Transparency Bug
**Problem**: Plane thickness appeared to stop working above ~10 pixels in filter mode
**Cause**: Exponential decay formula still being applied, making points nearly invisible
**Fix**: Disable distance fade when `isFilterMode()` is true, set alpha=255 for all points
**Commit**: `55c09e4c`

### Text Field Circular Update Bug
**Problem**: Typed values were overwritten by slider's ChangeListener
**Cause**: Setting slider position triggered ChangeListener which updated text field
**Fix**: Temporarily remove/re-add ChangeListeners when updating slider from text field
**Commit**: `55c09e4c`

### F1 Help Focus Issue
**Problem**: F1 key didn't work until user clicked on the interest point table
**Cause**: Panel wasn't focusable and didn't have focus by default
**Fix**:
- Made panel focusable with `setFocusable(true)`
- Added mouse listeners to auto-request focus on mouse enter/press
- F1 key listener attached to both panel and table
**Commits**: `8994af77`, `34bff636`

## Registration and Subset Detection

### PairwiseSetup and Subset Detection

The `PairwiseSetup` class handles the setup of pairwise view comparisons for registration:
- Located in `net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.PairwiseSetup`
- Main workflow:
  1. `definePairs()` - Creates all pairs that need to be compared
  2. `removeNonOverlappingPairs()` - Filters out non-overlapping pairs (optional)
  3. `reorderPairs()` - Orders pairs consistently (optional)
  4. `detectSubsets()` - Identifies disconnected subsets for independent registration
  5. `sortSubsets()` - Sorts subsets and pairs within them (optional)

#### detectSubsets Algorithm

The `detectSubsets()` static method (lines 320-415) groups views into subsets based on pairwise comparisons:

**Input**:
- `views`: All views to be registered
- `pairs`: List of view pairs that need to be compared
- `groups`: Groups of views that should be transformed together

**Algorithm**:
1. **Build subset-precursors** (lines 332-375): Iterate through pairs and group views:
   - If neither view is in any set: create new set with both views and the pair
   - If one view is in a set: add the other view and the pair to that set
   - If both views are in the same set: add the pair to that set
   - If both views are in different sets: merge the sets
2. **Add singleton views** (lines 377-397): Views not in any pair get their own subset
3. **Merge by groups** (lines 399-401): If groups exist, merge subsets containing grouped views
4. **Create final subsets** (lines 403-412): Convert precursors to `Subset` objects

**Critical Implementation Details**:
- Uses two parallel ArrayLists: `vSets` (HashSets of views) and `pairSets` (lists of pairs)
- `setId()` method finds which set contains a given view (returns -1 if not found)
- When merging sets, the merged set is **always added at the end** (index `pairSets.size() - 1`)

### detectSubsets Merge Bug and Fix (2025-12-03)

**Problem**: A pair of ViewIds was present in the main pairs list but missing from the subset's pair list, even when only one subset existed. Specifically, pair (tpId=0, setupId=265) <=> (tpId=0, setupId=278) was being dropped.

**Root Cause**: In the `detectSubsets()` method at line 372-374, when both views of a pair were already present in different sets, the code would merge those sets but **never add the pair that triggered the merge**:

```java
else // both are present in different sets, the sets need to be merged
{
    mergeSets( vSets, pairSets, i1, i2 );
    // BUG: The pair that caused this merge was never added!
}
```

All other branches of the conditional properly added the current pair:
- Line 347: New set creation → adds pair
- Line 358: One view exists → adds pair
- Line 364: Other view exists → adds pair
- Line 369: Both in same set → adds pair
- Line 373: Both in different sets → **MISSING: forgot to add pair!**

**The Fix** (PairwiseSetup.java:376-377):
```java
else // both are present in different sets, the sets need to be merged
{
    mergeSets( vSets, pairSets, i1, i2 );
    // The merged set is now at the end, add the current pair to it
    pairSets.get( pairSets.size() - 1 ).add( pair );
}
```

**Why `pairSets.size() - 1`?**
The `mergeSets()` method (lines 522-553):
1. Collects all pairs and views from sets being merged
2. Removes the old sets from the lists
3. **Adds the merged set at the END** of both vSets and pairSets

Therefore, after merging, the new merged set is always at index `pairSets.size() - 1`.

**Additional Documentation**:
- Enhanced Javadoc for `mergeSets()` (lines 510-521) to document that merged sets end at the end
- Added inline comments explaining each section of the merge operation
- Added comment at the fix location explaining why the pair is added there

**Impact**: This bug could cause registration to fail or produce incorrect results when:
- Multiple disconnected view groups existed initially
- A pair connected two previously separate groups (triggering a merge)
- That connecting pair would be lost, potentially leaving the subsets disconnected in the registration graph

**Testing**: User had comprehensive debug output in `Interest_Point_Registration.identifySubsets()` that traced pairs through each step and confirmed the pair was present before `detectSubsets()` but missing from the resulting subset.

**Files Modified**:
- `src/main/java/net/preibisch/mvrecon/process/interestpointregistration/pairwise/constellation/PairwiseSetup.java`

## N5/Zarr Export and Multi-Resolution Pyramids

### Fusion vs Resaving

The project supports two distinct export workflows:

**Fusion Export** (ExportN5Api.java):
- Combines multiple views into a single fused image volume
- Creates new image data by blending/averaging overlapping views
- Output: Single volume per timepoint/channel or combined 5D OME-ZARR container
- Used for final visualization and downstream analysis
- Supports 3D separate containers or 5D single container OME-ZARR export

**Resaving** (Resave_N5Api.java):
- Re-exports raw multi-view data to different format (N5/HDF5/Zarr)
- Preserves original view structure - each view stays separate
- Creates multi-resolution pyramids for each view
- Output: BDV-compatible format with XML metadata
- Always uses 5D OME-ZARR format when exporting to Zarr (expanding 3D to [x,y,z,c=1,t=1])
- Used for converting legacy formats or creating cloud-compatible versions

**Key difference**: Fusion creates one fused volume, resaving creates separate per-view volumes.

### Zarr v3 Sharding Support

**Sharding Concept**: Zarr v3 introduces sharding to group multiple small blocks (e.g., 32³) into larger shards (e.g., 128³). This reduces metadata overhead for cloud storage by storing multiple blocks in a single file.

**Critical Requirement**: Shards must be written as complete units. When sharding is enabled, the write granularity (computeBlockSize) must equal the shard size, not the block size.

#### Grid.create() Pattern

The N5 API uses `Grid.create(dimensions, computeBlockSize, blockSize)` where:
- **computeBlockSize**: Controls write granularity (how large each write operation is)
- **blockSize**: Inner chunk size stored in metadata
- **When sharding**: `computeBlockSize = shardSize`, `blockSize` = inner chunk size
- **Without sharding**: `computeBlockSize = blockSize`

#### N5Writer Initialization

**Critical**: N5ZarrWriter must be initialized via `URITools.instantiateN5Writer()`, not direct instantiation:

```java
// CORRECT:
N5Writer writer = util.URITools.instantiateN5Writer(
    org.janelia.saalfeldlab.n5.universe.StorageFormat.ZARR,
    outputPath.toURI()
);

// WRONG (causes issues):
N5Writer writer = new N5ZarrWriter(outputPath.getAbsolutePath());
```

The proper initialization includes:
- GsonBuilder with CoordinateTransformationAdapter
- Correct boolean flags for Zarr compatibility
- Proper URI path handling

**Implementation**: util/URITools.java:271

#### Multi-Resolution Pyramid Structure

**MultiResolutionLevelInfo Class** (N5ApiTools.java:195+):
- Contains all metadata for one pyramid level
- **Key fields**: dimensions, blockSize, downsampling factors, dataset path, dataType
- **Added field**: `shardSize` (null if sharding not used)

**Shard Size Policy**:
- Shard size remains **constant across all resolution levels** (s0, s1, s2...)
- Does not scale with downsampling factors
- Applied to all levels when enabled

#### Export Implementation

**setupMultiResolutionPyramid()** (N5ApiTools.java:227-335):
- Creates datasets for all resolution levels
- Passes shardSize to each MultiResolutionLevelInfo
- For Zarr with sharding: uses ZarrDatasetAttributes with proper codec chain

**writeDownsampledBlock()** (N5ApiTools.java:585-610):
- Reads from previous level, downsamples, writes to current level
- Must handle shard-aware writing when sharding is enabled

#### Known Issues

**5D OME-ZARR Sharding Bug** (Fixed):
- **Location**: ExportN5Api.java:237
- **Problem**: Inverted ternary operator `(useSharding) ? null : shardSize5D`
- **Fix**: Corrected to `(useSharding) ? shardSize5D : null`
- OME-ZARR uses 5D format: `[x, y, z, channels, timepoints]`

**Test NullPointerException** (Ongoing):
- **Symptom**: TestN5Zarr multi-resolution tests fail with NPE at `PaddedRawBlockCodec.encode()`
- **Location**: Triggered from N5ApiTools.writeDownsampledBlock() → N5Utils.saveNonEmptyBlock()
- **Status**: GUI export (ExportN5Api) works correctly; issue appears test-specific
- **Hypothesis**: Incorrect parameter initialization in test setup

#### Key Files

**TestN5Zarr.java** (src/test/java/):
- Tests N5/Zarr export with real fusion data
- Tests multi-resolution pyramids with/without sharding
- Uses TestFusion.testFusion() to generate BlockSupplier test data
- **Test methods**: testZarrV2NoSharding, testZarrV3WithSharding, testVariousShardSizes, testN5IgnoresSharding

**N5ApiTools.java** (src/main/java/):
- MultiResolutionLevelInfo class with shardSize field
- setupMultiResolutionPyramid() creates pyramid datasets
- assembleJobs() generates grid blocks for parallel writing
- writeDownsampledBlock() handles downsampling between levels

**ExportN5Api.java** (src/main/java/):
- Production export code used by GUI
- exportImage() method handles full export pipeline
- Uses mrInfo.shardSize for shard-aware writing

#### Technical Gotchas

1. **N5Utils.saveBlock() doesn't buffer**: We must write complete shard-sized chunks ourselves
2. **Proper initialization required**: Direct N5ZarrWriter instantiation lacks proper GsonBuilder setup
3. **5D expansion for OME-ZARR**: Sharding metadata must be expanded from 3D to 5D format
4. **Constant shard size**: Don't scale shardSize with downsampling factors across pyramid levels
5. **computeBlockSize = shardSize**: Critical for correct shard-aware writing

#### Recent Fixes (2026-01-06)

**Zarr v3 Dimensions Reading Fix**:
- **Problem**: `writeDownsampledBlock5dOMEZARR()` used `getAttribute(DIMENSIONS_KEY)` which returns null for Zarr v3
- **Fix**: Use `getDatasetAttributes(dataset).getDimensions()` instead (N5ApiTools.java:658)
- **Reason**: Zarr v3 stores dimensions as "shape" in zarr.json, not as a separate attribute
- **Commit**: 977cb9f3

**Shard Size for Downsampled Levels Fix**:
- **Problem**: Downsampled levels (s1+) used `blockSize` instead of `shardSize` for `computeBlockSize` when calling `assembleJobs()`
- **Impact**: Grid.create() created multiple small blocks instead of shard-sized blocks, causing data written to wrong positions within shards
- **Symptom**: "Wild" visualization - top-left quadrant for z<64, bottom-right for z≥64
- **Fix**: Pass `mrInfo.shardSize` (resave) or `this.shardSize` (fusion) as computeBlockSize when sharding enabled
  - Resave_N5Api.java:304
  - ExportN5Api.java:588
- **Commit**: 977cb9f3

**ZARR2 (v2) Support**:
- **Added**: Comprehensive `|| StorageFormat.ZARR2` checks alongside all ZARR (v3) format checks
- **Locations**: 11 total - Resave_N5Api.java (2), ExportN5Api.java (8), N5ApiTools.java (1)
- **Intentionally excluded**: Zarr v3-specific features (sharding dialogs/code)
- **Commit**: dfbf31aa
**4D (XYZC) OME-ZARR Support** (2026-01-06):
- **Added**: Full import support for 4D OME-ZARR (spatial dimensions + channels, no time)
- **Key Changes**:
  1. **AllenOMEZarrLoader.java:119** - Fixed critical bug: slice dimension 3 (not 4) for 4D data
  2. **OMEZARR.java** - Multiple changes to enable 4D import:
     - Lines 280-288: Split sizeC and sizeT initialization (4D has only sizeC)
     - Lines 311-322: Allow 4D in dimension check, add smart 4D→3D fallback for sizeC=1
     - Lines 349-374: Updated UI messages to handle 4D with specific branches
     - Lines 603-604: Timepoints initialization - for 4D, only 1 timepoint
     - Lines 784-830: OMEZARREntry creation with proper indices:
       - 4D: single-element array `[channelId]`
       - 5D: two-element array `[channelId, timepointId]`
- **Dimension Mapping**: [0,1,2]=XYZ (spatial), [3]=C (channels), [4]=T (time, 5D only)
- **Smart Fallbacks**:
  - 4D with sizeC=1 → treated as 3D with pattern-based channels
  - 5D with sizeC=1 and sizeT=1 → treated as 3D with pattern-based views
- **Use Cases**: Multi-channel single-timepoint microscopy data (e.g., multi-color fluorescence)
- **Export**: Not implemented - import only. Fusion export creates 5D with `[x,y,z,c=1,t=1]` which works.
- **Files Modified**:
  - `src/main/java/net/preibisch/mvrecon/fiji/spimdata/imgloaders/AllenOMEZarrLoader.java`
  - `src/main/java/net/preibisch/mvrecon/fiji/datasetmanager/OMEZARR.java`

### 4D OME-ZARR Technical Details

**Axis Order**: TCZYX (metadata) or XYZCT (data dimensions, reversed)

**higherDimensionIndicies Behavior**:
- **3D data**: `null` or `[]` (empty array)
- **4D data**: `[channelId]` (single element)
- **5D data**: `[channelId, timepointId]` (two elements)

**extract3DVolume() Method** (AllenOMEZarrLoader.java:111-136):
```java
// For 4D with indices=[c]: extracts dimension 3 at index c
// For 5D with indices=[c,t]: extracts dimensions 3 and 4 at indices c and t
RandomAccessibleInterval< T > out = omeZarrVolume;
for ( int d = 3 + higherDimensionIndicies.length - 1; d >= 3; --d )
    out = Views.hyperSlice( out, d, higherDimensionIndicies[ d - 3 ] );
```

This loop automatically handles variable-length indices:
- Length 1 (4D): Slices dimension 3 once
- Length 2 (5D): Slices dimensions 4 then 3

**Import Workflow for 4D**:
1. OMEZARR class scans directories and detects 4D OME-ZARR files
2. Reads sizeC from dimension[3] (no sizeT for 4D)
3. Creates ViewSetups - one per channel
4. Creates single timepoint (t=0) automatically
5. For each ViewSetup, creates OMEZARREntry with `indices=[channelId]`
6. AllenOMEZarrLoader uses indices to extract channel slice when loading

**Example 4D Structure**:
```
dataset.zarr/
  ├── zarr.json       # 4D metadata: axes = [C, Z, Y, X], shape = [3, 100, 512, 512]
  ├── 0/              # Level 0 (full resolution)
  └── 1/              # Level 1 (downsampled)
```

**Example XML Entry for 4D**:
```xml
<zgroup setup="0" tp="0" path="dataset.zarr" indicies="[0]"/>  <!-- Channel 0 -->
<zgroup setup="1" tp="0" path="dataset.zarr" indicies="[1]"/>  <!-- Channel 1 -->
<zgroup setup="2" tp="0" path="dataset.zarr" indicies="[2]"/>  <!-- Channel 2 -->
```

**Comparison with 5D**:
```xml
<zgroup setup="0" tp="0" path="dataset.zarr" indicies="[0 0]"/>  <!-- Channel 0, Time 0 -->
<zgroup setup="1" tp="1" path="dataset.zarr" indicies="[0 1]"/>  <!-- Channel 0, Time 1 -->
```

## BigDataViewer Performance Optimization

### Problem: Slow Data_Explorer Opening for Large Datasets

Opening datasets with ~12,000+ views took 6+ minutes before the GUI appeared.

**Root Cause**: BDV source visibility and coloring operations used per-source API calls, each triggering event listeners (~28ms per call × 12,000 sources = 6 minutes).

### Solution: Use BDV's Batch APIs

**ViewerState Batch API** (recommended by BDV author Tobias Pietzsch):
- `state.setSourcesActive(Collection<SourceAndConverter>, boolean)` - batch activate/deactivate
- Access via `bdv.getViewer().state()`
- Thread-safe with `synchronized(state)` when iterating sources

**ConverterSetups API for Coloring**:
- Access via `bdv.getViewerFrame().getConverterSetups()` (NOT `bdv.getSetupAssignments()`)
- `converterSetups.getConverterSetup(SourceAndConverter)` - get setup for a source
- Then call `setup.setColor(ARGBType)` on each

### Key Files

**util/BDVTools.java** - Consolidated BDV helper methods:
```java
// Display Mode
setFusedModeSimple(BigDataViewer, AbstractSpimData)

// Source Visibility (batch API)
setVisibleSources(ViewerState, Collection<SourceAndConverter>)
setVisibleSourcesBatch(BigDataViewer, Set<Integer> activeSetupIds)

// Source Coloring (via ConverterSetups)
whiteSourcesBatch(BigDataViewer)
colorSourcesBatch(BigDataViewer, long colorOffset)
colorByFactors(BigDataViewer, AbstractSpimData, Set<Class>, long colorOffset)

// Legacy (deprecated, keep for compatibility)
whiteSources(List<ConverterSetup>)
sameColorSources(List<ConverterSetup>, r, g, b, a)

// Transform Control
resetBDVManualTransformations(BigDataViewer)

// Index Utilities
getBDVTimePointIndex(TimePoint, AbstractSpimData)
getBDVSourceIndex(BasicViewSetup, AbstractSpimData)
```

### Implementation Pattern

```java
// CORRECT: Batch API for visibility
final ViewerState state = bdv.getViewer().state();
final List<SourceAndConverter<?>> active = new ArrayList<>();
synchronized (state) {
    BDVUtils.forEachAbstractSpimSource(
        state.getSources(),
        (soc, source) -> {
            if (activeSetupIds.contains(source.getSetupId()))
                active.add(soc);
        });
}
final List<SourceAndConverter<?>> inactive = new ArrayList<>(state.getSources());
inactive.removeAll(active);
state.setSourcesActive(inactive, false);  // Batch deactivate
state.setSourcesActive(active, true);     // Batch activate

// CORRECT: ConverterSetups API for coloring
final ConverterSetups converterSetups = bdv.getViewerFrame().getConverterSetups();
final ViewerState state = bdv.getViewer().state();
synchronized (state) {
    for (SourceAndConverter<?> soc : state.getSources()) {
        ConverterSetup cs = converterSetups.getConverterSetup(soc);
        if (cs != null)
            cs.setColor(color);
    }
}

// WRONG: Per-source calls (slow!)
for (int i = 0; i < numSources; i++)
    vag.setSourceActive(i, active[i]);  // ~28ms per call!
```

### Performance Results

- **Before**: ~6 minutes to open dataset with 12,903 views
- **After**: <1 second

### API Reference (BDV Core)

- **ViewerState**: https://github.com/bigdataviewer/bigdataviewer-core/blob/master/src/main/java/bdv/viewer/ViewerState.java
- **ConverterSetups**: https://github.com/bigdataviewer/bigdataviewer-core/blob/master/src/main/java/bdv/viewer/ConverterSetups.java

### Deprecated APIs (Avoid)

- `bdv.getSetupAssignments().getConverterSetups()` - old API
- `VisibilityAndGrouping.setSourceActive(int, boolean)` - per-source, slow
- Direct instantiation of N5ZarrWriter - use `URITools.instantiateN5Writer()`

## Oct-Tree Adaptive Image Splitting

### Overview

The oct-tree splitting system adaptively subdivides large images into smaller tiles based on interest point correspondence density. This is useful for processing very large datasets where registration quality varies across the image - regions with many correspondences (potential conflicts) are split into smaller tiles, while regions with few correspondences remain large.

### Key Files

```
src/main/java/net/preibisch/mvrecon/process/splitting/
├── SplitOctTree.java                    # Main splitting algorithm
├── OctTreeSplitCriterion.java           # Interface for split criteria
├── CrossViewCorrespondenceCriterion.java # Simple correspondence count criterion
├── ConsensusSetCriterion.java           # Multi-consensus RANSAC set criterion
├── SplitDistributeEvenly.java           # Uniform grid splitting (alternative)
└── SplittingTools.java                  # Entry point utilities
```

### Split Criteria

#### OctTreeSplitCriterion Interface (OctTreeSplitCriterion.java)

The interface that all split criteria implement:

```java
public interface OctTreeSplitCriterion {
    // Load correspondences for a view (called once per view)
    List<SplitCorrespondence> loadCorrespondences(ViewId viewId);

    // Decide whether to split based on correspondences in current region
    boolean shouldSplit(List<SplitCorrespondence> correspondences);

    // Check if regions can be merged (default: !shouldSplit)
    default boolean canMerge(List<SplitCorrespondence> correspondences);
}
```

**SplitCorrespondence Structure**:
- `double[] location` - Detection position in local coordinates (for spatial partitioning)
- `int detectionId` - Unique ID within the view (for counting unique detections)
- `String corrViewKey` - Key identifying corresponding view: "timepointId_setupId"
- `int consensusSetId` - RANSAC consensus set ID (-1 for single-consensus mode)

#### CrossViewCorrespondenceCriterion

Simple criterion that counts unique cross-view corresponding detections:
- **Splits when**: unique detections > threshold (default: 20)
- **Use case**: Basic splitting based on correspondence density

#### ConsensusSetCriterion (Multi-Consensus)

Advanced criterion that considers RANSAC consensus sets:
- **Stops splitting when EITHER**:
  1. Unique detections ≤ threshold (default: 12), OR
  2. All corresponding views have correspondences from only ONE consensus set
- **Continues splitting only if**: detections > threshold AND any view has >1 consensus set

**Tolerance Modes**:
- `TOLERANCE_NONE` - Any correspondence from another set triggers split
- `TOLERANCE_PERCENTAGE` - Allow up to X% from other sets
- `TOLERANCE_COUNT` - Allow up to N correspondences from other sets

**Why This Matters**: When multi-consensus RANSAC finds multiple transformation models in a view pair, it indicates the region contains parts of the sample that moved differently. Splitting isolates these regions for separate registration.

### Splitting Algorithm (SplitOctTree.java)

#### Recursive Correspondence Partitioning

The algorithm uses O(n log n) recursive partitioning instead of O(n × intervals) naive approach:

1. **Load correspondences once** per view at the start
2. **Partition correspondences** as intervals are split (not re-query)
3. **Overlap handling**: Correspondences in overlap regions go to BOTH adjacent children

```java
// Partition logic for each dimension
if (corr.location[d] < splitPoint - minStepSize)
    belongsTo[d] = 0;  // lower only
else if (corr.location[d] >= splitPoint + minStepSize)
    belongsTo[d] = 1;  // upper only
else
    belongsTo[d] = 2;  // overlap - both children
```

#### Tile Overlap

All tiles overlap by `minStepSize` to support:
- Fake corresponding points generation at tile boundaries
- Proper stitching after per-tile processing

#### Block Re-Merging

After splitting, adjacent blocks can be merged back if their combined metric is below threshold:
1. **Full merge**: All 8 octants → parent (if canMerge succeeds)
2. **Half-space merges**: Try merging along each dimension (4+4 octants)
3. **Quadrant merges**: Try merging quadrants (2+2+2+2 octants)
4. **Individual octant merges**: Merge children within each octant

#### Minimum Split Levels

Force a minimum number of split operations regardless of correspondence count:
- `minSplitLevels=0` - Fully adaptive (may not split at all)
- `minSplitLevels=1` - Always split at least once (up to 8 tiles)
- `minSplitLevels=2` - Always split twice (up to 64 tiles)

Validated upfront against tile size constraints to prevent impossible configurations.

### Static vs Instance Methods

The splitting supports both sequential and parallel execution:

```java
// Instance method (sequential, accumulates statistics)
SplitOctTree splitter = new SplitOctTree(...);
splitter.setCurrentContext(viewId, timepointId);
ArrayList<Interval> intervals = splitter.split(input);

// Static method (parallel-safe, returns SplitStatistics)
SplitStatistics result = SplitOctTree.splitStatic(
    input, viewId, criterion, minStepSize, minSizeMultiplier, enableMerge, minSplitLevels
);
```

### GUI Integration

The splitting system integrates with the standard GenericDialog workflow:

```java
// In plugin setup
GenericDialog gd = new GenericDialog("Split Settings");
SplitOctTree.setupGUI(gd, data, minStepSize);  // Adds criterion selection + parameters
gd.showDialog();
SplitOctTree splitter = SplitOctTree.queryGUI(gd, data, minStepSize);
```

Features:
- Per-dimension minimum tile size sliders (X, Y, Z independently)
- Criterion selection (Cross-view correspondences vs Multi-consensus sets)
- Interest point label multi-select
- Tolerance mode configuration for multi-consensus

### Performance Optimizations

1. **Recursive partitioning**: O(n log n) instead of O(n × intervals)
2. **Cached correspondence loading**: Load once per view, partition as needed
3. **Parallel splitting**: Static methods enable parallel stream processing
4. **Pre-validation**: minSplitLevels validated against tile size before splitting

### Default Grouping for Split Datasets

When opening a Split dataset in the Data Explorer:
- **"Group Tiles"** is enabled by default
- **"Group Illuminations"** is disabled by default

Detection via `isSplitDataset()` method checking for `SplitViewerImgLoader` or `SplitMultiResolutionImgLoader`.

