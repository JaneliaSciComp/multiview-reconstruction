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
