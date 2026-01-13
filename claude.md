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

## Cross-Correlation Computation for Rotated Views

### Problem Statement
Computing accurate cross-correlation values between views with arbitrary affine transformations (especially rotations) requires special handling. Naive approaches that compare axis-aligned bounding boxes in local coordinate systems fail because:

1. **Simple bounding box intersection** in global space can create overlap regions that extend outside actual image bounds when views are rotated, leading to excessive zero-padding
2. **Independent local overlaps** (sampling in each view separately) find regions with different physical extents that don't actually correspond pixel-by-pixel

### Solution: Hybrid Approach with Resampling

The implementation in `ComputeCrossCorrelationPopup.java` uses a hybrid approach combining:

1. **Accurate Overlap Detection** (via `SimpleBoundingBoxOverlap.getLocalOverlapsUsingPixelValidation`):
   - Samples pixels in each view's local space
   - Validates they map to valid pixels in the other view
   - Accounts for rotations and complex transformations
   - Returns tight bounding boxes in each view's local coordinate system

2. **Proper Data Alignment** (via resampling with interpolation):
   - Uses View1's overlap region as the reference coordinate system
   - Transforms View2 into View1's coordinate system using the full transform chain
   - Applies N-linear interpolation for sub-pixel accuracy
   - Uses mirror extension (`extendMirrorSingle`) to handle edge pixels gracefully

### Implementation Details

**Key code section** (lines ~379-520 in ComputeCrossCorrelationPopup.java):

```java
// 1. Find accurate overlaps using pixel validation
RealInterval[] localOverlaps = SimpleBoundingBoxOverlap.getLocalOverlapsUsingPixelValidation(
    vs1.getSize(), vs2.getSize(), m1, m2);
RealInterval localOverlap1 = localOverlaps[0];

// 2. Extract overlap region from View1
RandomAccessibleInterval<FloatType> overlap1 = Views.interval(img1, dsInterval1);

// 3. Transform View2 into View1's coordinate system
AffineTransform3D view2ToView1 = dsM1.inverse();
view2ToView1.concatenate(dsM2);

// 4. Create interpolated, transformed view of img2
RealRandomAccessible<FloatType> interpolated2 = Views.interpolate(
    Views.extendMirrorSingle(img2),
    new NLinearInterpolatorFactory<>());
RealRandomAccessible<FloatType> transformed2 = RealViews.transform(interpolated2, view2ToView1);

// 5. Rasterize over the SAME interval as View1
RandomAccessibleInterval<FloatType> overlap2 = Views.interval(
    Views.raster(transformed2), dsInterval1);

// 6. Compute correlation on perfectly aligned regions
peak.calculateCrossCorr(overlap1, overlap2);
```

### Why This Works

- **Pixel-by-pixel correspondence**: Both overlap regions use the same interval (from View1's coordinate system), ensuring perfect alignment
- **Rotation handling**: The transformation and interpolation properly handle arbitrary rotations without introducing misalignment
- **Edge handling**: Mirror extension prevents low correlation due to zero-padding at boundaries
- **Efficient**: Only processes the actual overlapping region, not entire images

### Results

This approach produces accurate correlation values (typically >0.9 for good alignments) across all view pairs, regardless of relative rotation or transformation complexity.

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
