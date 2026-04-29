#!/usr/bin/env python3
"""
Filter a BigStitcher/SpimData2 XML to keep only specific views.

Filters can be combined with AND logic. All support range syntax (e.g. 0-99).

Usage examples:
  # Keep only channel 0
  python filter_bigstitcher_xml.py -i dataset.xml -o dataset_ch0.xml --channels 0

  # Keep channels 0 and 2
  python filter_bigstitcher_xml.py -i dataset.xml -o out.xml --channels 0 2

  # Keep channel 0, tiles 0-10
  python filter_bigstitcher_xml.py -i dataset.xml -o out.xml --channels 0 --tiles 0-10

  # Keep specific view setup IDs
  python filter_bigstitcher_xml.py -i dataset.xml -o out.xml --views 0 1 5 10

  # Keep views 0-99 using range syntax
  python filter_bigstitcher_xml.py -i dataset.xml -o out.xml --views 0-99 200 300-305

  # Filter by timepoint, channel, and tile
  python filter_bigstitcher_xml.py -i dataset.xml -o out.xml --timepoints 0-5 --channels 0 --tiles 0-9

  # Filter by illumination and angle
  python filter_bigstitcher_xml.py -i dataset.xml -o out.xml --illuminations 0 --angles 0
"""

import argparse
import xml.etree.ElementTree as ET
import sys


def parse_id_spec(specs):
    """Parse ID specifications that can be individual IDs or ranges like '0-99'."""
    ids = set()
    for spec in specs:
        if '-' in spec and not spec.startswith('-'):
            parts = spec.split('-', 1)
            start, end = int(parts[0]), int(parts[1])
            ids.update(range(start, end + 1))
        else:
            ids.add(int(spec))
    return ids


def get_setup_attr(view_setup_elem, attr_name):
    """Extract an attribute id from a ViewSetup element."""
    attrs = view_setup_elem.find('attributes')
    if attrs is not None:
        elem = attrs.find(attr_name)
        if elem is not None:
            return int(elem.text)
    return None


def get_setup_id(view_setup_elem):
    """Extract the id from a ViewSetup element."""
    id_elem = view_setup_elem.find('id')
    return int(id_elem.text) if id_elem is not None else None


def collect_used_attribute_ids(kept_setups, attr_name):
    """Collect the set of attribute IDs used by kept ViewSetups."""
    ids = set()
    for vs in kept_setups:
        ids.add(get_setup_attr(vs, attr_name))
    ids.discard(None)
    return ids


def parse_setup_ids_from_attr(value):
    """Parse possibly comma-separated setup IDs from a StitchingResults attribute."""
    return {int(x.strip()) for x in value.split(',')}


def parse_timepoint_ids_from_attr(value):
    """Parse possibly comma-separated timepoint IDs from a StitchingResults attribute."""
    return {int(x.strip()) for x in value.split(',')}


def should_keep_view(setup, timepoint, keep_setup_ids, keep_timepoint_ids):
    """Check if a view (setup + timepoint) should be kept."""
    if setup is not None and int(setup) not in keep_setup_ids:
        return False
    if keep_timepoint_ids is not None and timepoint is not None and int(timepoint) not in keep_timepoint_ids:
        return False
    return True


def update_timepoints_element(seq_desc, keep_timepoint_ids):
    """Update the Timepoints element to only contain kept timepoints."""
    tp_elem = seq_desc.find('Timepoints')
    if tp_elem is None:
        return

    tp_type = tp_elem.get('type')
    kept_sorted = sorted(keep_timepoint_ids)

    if tp_type == 'range':
        first = tp_elem.find('first')
        last = tp_elem.find('last')
        if first is not None and last is not None:
            orig_first, orig_last = int(first.text), int(last.text)
            orig_all = set(range(orig_first, orig_last + 1))
            actual_kept = sorted(orig_all & keep_timepoint_ids)
            if not actual_kept:
                return
            # If the kept set is still a contiguous range, keep as range
            if actual_kept == list(range(actual_kept[0], actual_kept[-1] + 1)):
                first.text = str(actual_kept[0])
                last.text = str(actual_kept[-1])
            else:
                # Convert to pattern
                tp_elem.set('type', 'pattern')
                tp_elem.remove(first)
                tp_elem.remove(last)
                pattern = ET.SubElement(tp_elem, 'integerpattern')
                pattern.text = ','.join(str(t) for t in actual_kept)

    elif tp_type == 'pattern':
        pattern = tp_elem.find('integerpattern')
        if pattern is not None and pattern.text:
            orig_ids = {int(x.strip()) for x in pattern.text.split(',')}
            actual_kept = sorted(orig_ids & keep_timepoint_ids)
            pattern.text = ','.join(str(t) for t in actual_kept)

    elif tp_type == 'list':
        # Explicit list of timepoint elements
        to_remove = []
        for tp_child in tp_elem:
            if tp_child.text is not None:
                try:
                    if int(tp_child.text.strip()) not in keep_timepoint_ids:
                        to_remove.append(tp_child)
                except ValueError:
                    pass
        for tp_child in to_remove:
            tp_elem.remove(tp_child)


def filter_xml(input_path, output_path, keep_setup_ids, keep_timepoint_ids):
    """Filter a BigStitcher XML keeping only the specified view setup IDs and timepoints."""
    tree = ET.parse(input_path)
    root = tree.getroot()

    # --- SequenceDescription ---
    seq_desc = root.find('SequenceDescription')
    view_setups_elem = seq_desc.find('ViewSetups')

    # Remove ViewSetups not in keep set, collect kept elements
    kept_setup_elems = []
    to_remove = []
    for vs in view_setups_elem.findall('ViewSetup'):
        sid = get_setup_id(vs)
        if sid not in keep_setup_ids:
            to_remove.append(vs)
        else:
            kept_setup_elems.append(vs)
    for vs in to_remove:
        view_setups_elem.remove(vs)

    # Collect which attribute IDs are actually used by the kept setups
    used_channels = collect_used_attribute_ids(kept_setup_elems, 'channel')
    used_tiles = collect_used_attribute_ids(kept_setup_elems, 'tile')
    used_illuminations = collect_used_attribute_ids(kept_setup_elems, 'illumination')
    used_angles = collect_used_attribute_ids(kept_setup_elems, 'angle')

    # Filter Attributes sections
    attr_tag_map = {
        'channel': ('Channel', used_channels),
        'tile': ('Tile', used_tiles),
        'illumination': ('Illumination', used_illuminations),
        'angle': ('Angle', used_angles),
    }
    for attrs_elem in view_setups_elem.findall('Attributes'):
        attr_name = attrs_elem.get('name')
        if attr_name in attr_tag_map:
            child_tag, used_ids = attr_tag_map[attr_name]
            to_remove = []
            for child in attrs_elem.findall(child_tag):
                child_id = child.find('id')
                if child_id is not None and int(child_id.text) not in used_ids:
                    to_remove.append(child)
            for child in to_remove:
                attrs_elem.remove(child)

    # Filter Timepoints
    if keep_timepoint_ids is not None:
        update_timepoints_element(seq_desc, keep_timepoint_ids)

    # Filter MissingViews
    missing_views = seq_desc.find('MissingViews')
    if missing_views is not None and len(missing_views) > 0:
        to_remove = []
        for mv in missing_views.findall('MissingView'):
            if not should_keep_view(mv.get('setup'), mv.get('timepoint'),
                                    keep_setup_ids, keep_timepoint_ids):
                to_remove.append(mv)
        for mv in to_remove:
            missing_views.remove(mv)

    # --- ViewRegistrations ---
    view_regs = root.find('ViewRegistrations')
    if view_regs is not None:
        to_remove = []
        for vr in view_regs.findall('ViewRegistration'):
            if not should_keep_view(vr.get('setup'), vr.get('timepoint'),
                                    keep_setup_ids, keep_timepoint_ids):
                to_remove.append(vr)
        for vr in to_remove:
            view_regs.remove(vr)

    # --- ViewInterestPoints ---
    vips = root.find('ViewInterestPoints')
    if vips is not None and len(vips) > 0:
        to_remove = []
        for vip in vips.findall('ViewInterestPointsFile'):
            if not should_keep_view(vip.get('setup'), vip.get('timepoint'),
                                    keep_setup_ids, keep_timepoint_ids):
                to_remove.append(vip)
        for vip in to_remove:
            vips.remove(vip)

    # --- PointSpreadFunctions ---
    psfs = root.find('PointSpreadFunctions')
    if psfs is not None and len(psfs) > 0:
        to_remove = []
        for psf in psfs.findall('BoundingBoxDefinition'):
            if not should_keep_view(psf.get('setup'), psf.get('timepoint'),
                                    keep_setup_ids, keep_timepoint_ids):
                to_remove.append(psf)
        for psf in to_remove:
            psfs.remove(psf)

    # --- StitchingResults ---
    stitching = root.find('StitchingResults')
    if stitching is not None and len(stitching) > 0:
        to_remove = []
        for pr in stitching.findall('PairwiseResult'):
            setups_a = parse_setup_ids_from_attr(pr.get('view_setup_a', ''))
            setups_b = parse_setup_ids_from_attr(pr.get('view_setup_b', ''))
            if not setups_a.issubset(keep_setup_ids) or not setups_b.issubset(keep_setup_ids):
                to_remove.append(pr)
            elif keep_timepoint_ids is not None:
                tps_a = parse_timepoint_ids_from_attr(pr.get('tp_a', ''))
                tps_b = parse_timepoint_ids_from_attr(pr.get('tp_b', ''))
                if not tps_a.issubset(keep_timepoint_ids) or not tps_b.issubset(keep_timepoint_ids):
                    to_remove.append(pr)
        for pr in to_remove:
            stitching.remove(pr)

    # --- IntensityAdjustments ---
    intensity = root.find('IntensityAdjustments')
    if intensity is not None and len(intensity) > 0:
        to_remove = []
        for ia in intensity:
            if not should_keep_view(ia.get('setup'), ia.get('timepoint'),
                                    keep_setup_ids, keep_timepoint_ids):
                to_remove.append(ia)
        for ia in to_remove:
            intensity.remove(ia)

    # Write output
    ET.indent(tree, space='  ')
    tree.write(output_path, encoding='UTF-8', xml_declaration=True)

    return len(kept_setup_elems)


def main():
    parser = argparse.ArgumentParser(
        description='Filter a BigStitcher/SpimData2 XML to keep only specific views. '
                    'Attribute filters are combined with AND logic. All support range syntax (e.g. 0-99).',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    parser.add_argument('-i', '--input', required=True, help='Input BigStitcher XML file')
    parser.add_argument('-o', '--output', required=True, help='Output filtered XML file')

    parser.add_argument('--channels', type=str, nargs='+', metavar='ID',
                        help='Channel IDs to keep (e.g. --channels 0 or --channels 0 2)')
    parser.add_argument('--tiles', type=str, nargs='+', metavar='ID',
                        help='Tile IDs to keep (e.g. --tiles 0-10)')
    parser.add_argument('--angles', type=str, nargs='+', metavar='ID',
                        help='Angle IDs to keep (e.g. --angles 0)')
    parser.add_argument('--illuminations', type=str, nargs='+', metavar='ID',
                        help='Illumination IDs to keep (e.g. --illuminations 0)')
    parser.add_argument('--timepoints', type=str, nargs='+', metavar='ID',
                        help='Timepoint IDs to keep (e.g. --timepoints 0-5)')
    parser.add_argument('--views', type=str, nargs='+', metavar='ID',
                        help='View setup IDs to keep directly (e.g. --views 0 1 5 or --views 0-99)')

    args = parser.parse_args()

    has_any_filter = any(v is not None for v in [
        args.channels, args.tiles, args.angles, args.illuminations,
        args.timepoints, args.views
    ])
    if not has_any_filter:
        parser.error('At least one filter must be specified '
                     '(--channels, --tiles, --angles, --illuminations, --timepoints, or --views)')

    print(f"Reading {args.input} ...")
    tree = ET.parse(args.input)
    root = tree.getroot()
    seq_desc = root.find('SequenceDescription')
    view_setups_elem = seq_desc.find('ViewSetups')
    all_setups = view_setups_elem.findall('ViewSetup')

    # Start with all setup IDs, then intersect with each filter
    keep_ids = {get_setup_id(vs) for vs in all_setups}

    # Attribute-based filters (AND logic)
    attr_filters = {
        'channel': args.channels,
        'tile': args.tiles,
        'angle': args.angles,
        'illumination': args.illuminations,
    }
    for attr_name, filter_specs in attr_filters.items():
        if filter_specs is not None:
            filter_ids = parse_id_spec(filter_specs)
            matching = {get_setup_id(vs) for vs in all_setups
                        if get_setup_attr(vs, attr_name) in filter_ids}
            keep_ids &= matching
            print(f"  {attr_name} filter {sorted(filter_ids)}: {len(matching)} matching, {len(keep_ids)} after intersection")

    # Direct view ID filter
    if args.views is not None:
        view_ids = parse_id_spec(args.views)
        keep_ids &= view_ids
        print(f"  views filter: {len(keep_ids)} after intersection")

    print(f"Keeping {len(keep_ids)} view setups")

    # Timepoint filter (separate dimension)
    keep_timepoint_ids = None
    if args.timepoints is not None:
        keep_timepoint_ids = parse_id_spec(args.timepoints)
        print(f"  timepoints filter: keeping {sorted(keep_timepoint_ids)}")

    if not keep_ids:
        print("Error: no matching view setups found. Nothing to write.", file=sys.stderr)
        sys.exit(1)

    n_kept = filter_xml(args.input, args.output, keep_ids, keep_timepoint_ids)
    print(f"Wrote {args.output} with {n_kept} view setups")


if __name__ == '__main__':
    main()
