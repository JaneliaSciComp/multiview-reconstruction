/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.mvrecon.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import mpicbg.spim.data.generic.sequence.BasicViewSetup;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.FinalVoxelDimensions;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalDimensions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.plugin.Interest_Point_Registration;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.AllToAll;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.IndividualTimepoints;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.PairwiseSetup;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.ReferenceTimepoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.overlap.SimpleBoundingBoxOverlap;

/**
 * The fast path of {@link Interest_Point_Registration#identifySubsets} for "Only compare overlapping views" must define the
 * same pairs as {@code definePairs()} + {@code removeNonOverlappingPairs()} for every registration type. It used to pair all
 * overlapping views regardless of the type, so "Register timepoints individually" compared all timepoints with each other.
 */
public class TestIdentifySubsets
{
	static final int TIMEPOINTS = 3;

	/** three timepoints of three setups: 0 and 1 overlap (shifted by half a width), 2 is far away */
	static SimpleBoundingBoxOverlap< ViewId > overlap( final List< ViewId > views )
	{
		final HashMap< Integer, BasicViewSetup > setups = new HashMap<>();
		final HashMap< ViewId, ViewRegistration > registrations = new HashMap<>();
		final double[] shiftX = { 0, 50, 1000 };
		for ( int s = 0; s < 3; ++s )
			setups.put( s, new BasicViewSetup( s, "" + s, new FinalDimensions( 100, 100, 50 ), new FinalVoxelDimensions( "px", 1, 1, 1 ) ) );
		for ( int t = 0; t < TIMEPOINTS; ++t )
			for ( int s = 0; s < 3; ++s )
			{
				final ViewId view = new ViewId( t, s );
				views.add( view );
				final AffineTransform3D model = new AffineTransform3D();
				model.translate( shiftX[ s ], 0, 0 );
				registrations.put( view, new ViewRegistration( t, s, model ) );
			}
		return new SimpleBoundingBoxOverlap<>( setups, registrations );
	}

	@Test
	public void fastPathDefinesThePairsOfTheRegistrationType()
	{
		final List< ViewId > views = new ArrayList<>();
		final SimpleBoundingBoxOverlap< ViewId > overlap = overlap( views );
		final Set< Group< ViewId > > groups = new HashSet<>();

		// overlapping: setups 0 and 1 with each other at any timepoint, every setup with itself at other timepoints
		// per timepoint: (0,1) only, 3 pairs; all: 15 among setups 0 and 1 plus 3 of setup 2 with itself, 18;
		// reference timepoint 0: the 18 minus those between timepoints 1 and 2 (4 among setups 0 and 1, 1 of setup 2), 13
		check( () -> new IndividualTimepoints( views, groups ), overlap, 3 );
		check( () -> new ReferenceTimepoint( views, groups, 0 ), overlap, 13 );
		check( () -> new AllToAll<>( views, groups ), overlap, 18 );

		// grouped views are not paired with each other: setups 0 and 1 of timepoint 0 in one group
		final Set< Group< ViewId > > grouped = new HashSet<>();
		grouped.add( new Group<>( Arrays.asList( new ViewId( 0, 0 ), new ViewId( 0, 1 ) ) ) );
		check( () -> new IndividualTimepoints( views, grouped ), overlap, 2 );
	}

	static void check( final Supplier< PairwiseSetup< ViewId > > setups, final SimpleBoundingBoxOverlap< ViewId > overlap, final int expectedPairs )
	{
		final PairwiseSetup< ViewId > fast = setups.get();
		new Interest_Point_Registration().identifySubsets( fast, overlap );

		final PairwiseSetup< ViewId > sequential = setups.get();
		sequential.definePairs();
		sequential.removeNonOverlappingPairs( overlap );

		final String name = fast.getClass().getSimpleName();
		assertEquals( expectedPairs, fast.getPairs().size(), name + ": pairs of the fast path" );
		assertEquals( keys( sequential.getPairs() ), keys( fast.getPairs() ), name + ": the fast path must define the pairs of definePairs() + removeNonOverlappingPairs()" );
		if ( fast instanceof IndividualTimepoints )
			for ( final Pair< ViewId, ViewId > pair : fast.getPairs() )
				assertEquals( pair.getA().getTimePointId(), pair.getB().getTimePointId(), name + ": pairs across timepoints" );
	}

	static Set< String > keys( final List< Pair< ViewId, ViewId > > pairs )
	{
		return pairs.stream().map( p -> {
			final String a = p.getA().getTimePointId() + "-" + p.getA().getViewSetupId(), b = p.getB().getTimePointId() + "-" + p.getB().getViewSetupId();
			return a.compareTo( b ) < 0 ? a + "|" + b : b + "|" + a;
		} ).collect( Collectors.toSet() );
	}
}
