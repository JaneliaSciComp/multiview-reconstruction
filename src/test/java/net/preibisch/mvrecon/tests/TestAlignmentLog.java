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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import mpicbg.models.AffineModel3D;
import mpicbg.models.Tile;
import mpicbg.models.TranslationModel3D;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.AlignmentLog;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.PairwiseResult;

/**
 * "Restrict alignment log outputs" ({@link AlignmentLog#restricted}): the per-view transformation lines and
 * the per-pair correspondence lines disappear, the summary stays.
 */
public class TestAlignmentLog
{
	@Test
	public void restrictedPrintsSummariesOnly()
	{
		final boolean ijLog = IOFunctions.printIJLog;
		final boolean restricted = AlignmentLog.restricted;
		final int capViews = TransformationTools.maxPerViewTransformLog, capPairs = PairwiseResult.maxPerPairCorrLog;
		try
		{
			IOFunctions.printIJLog = false; // IOFunctions.println goes to System.out
			TransformationTools.maxPerViewTransformLog = 100;
			PairwiseResult.maxPerPairCorrLog = 100;

			AlignmentLog.restricted = false;
			final List< String > verbose = capture( TestAlignmentLog::emit );
			// "Transformation Models:", three views, the summary, two pairs
			assertEquals( 1 + 3 + 1 + 2, verbose.size(), String.join( "\n", verbose ) );
			assertTrue( verbose.get( 0 ).endsWith( "Transformation Models:" ) );
			assertTrue( verbose.get( 4 ).startsWith( "Transformation Models summary: 3 view(s); 2 identity, 1 non-identity" ) );
			assertEquals( 2, verbose.stream().filter( l -> l.contains( "Loaded 5 correspondences" ) ).count() );

			AlignmentLog.restricted = true;
			final List< String > summaries = capture( TestAlignmentLog::emit );
			assertEquals( 1, summaries.size(), String.join( "\n", summaries ) );
			assertTrue( summaries.get( 0 ).startsWith( "Transformation Models summary: 3 view(s); 2 identity, 1 non-identity" ) );
		}
		finally
		{
			IOFunctions.printIJLog = ijLog;
			AlignmentLog.restricted = restricted;
			TransformationTools.maxPerViewTransformLog = capViews;
			PairwiseResult.maxPerPairCorrLog = capPairs;
		}
	}

	/** the two-round global optimization of {@link TestGlobalOptTwoRound} in both modes: same result, no per-tile, per-group or per-iteration lines when restricted */
	@Test
	public void restrictedGlobalOptimization()
	{
		final boolean ijLog = IOFunctions.printIJLog;
		final boolean restricted = AlignmentLog.restricted;
		try
		{
			IOFunctions.printIJLog = false;
			final List< HashMap< ViewId, TranslationModel3D > > results = new ArrayList<>();

			AlignmentLog.restricted = false;
			final List< String > verbose = capture( () -> results.add( TestGlobalOptTwoRound.computeGlobalOptTwoRound() ) );
			AlignmentLog.restricted = true;
			final List< String > summaries = capture( () -> results.add( TestGlobalOptTwoRound.computeGlobalOptTwoRound() ) );

			assertEquals( results.get( 0 ).keySet(), results.get( 1 ).keySet() );
			for ( final ViewId view : results.get( 0 ).keySet() )
				assertArrayEquals( results.get( 0 ).get( view ).getTranslation(), results.get( 1 ).get( view ).getTranslation(), 1e-9 );

			assertTrue( verbose.stream().anyMatch( l -> l.contains( "): Fixing " ) ), String.join( "\n", verbose ) );
			assertTrue( verbose.stream().anyMatch( l -> l.startsWith( "Group 0: [" ) ), String.join( "\n", verbose ) );
			assertTrue( verbose.stream().anyMatch( l -> l.endsWith( "Transformation Models:" ) ), String.join( "\n", verbose ) );

			assertTrue( summaries.stream().noneMatch( l -> l.contains( "): Fixing " ) || l.startsWith( "Group " ) || l.endsWith( "Transformation Models:" )
					|| l.contains( "Removed link from" ) || l.contains( "max error = " ) || l.contains( "starting solve" )
					|| l.matches( "\\d+: .*" ) /* mpicbg's per-iteration line */ ), String.join( "\n", summaries ) );
			assertTrue( verbose.stream().anyMatch( l -> l.matches( "\\d+: .*" ) ), String.join( "\n", verbose ) );
			assertTrue( summaries.stream().anyMatch( l -> l.contains( "): Fixed 1 tile(s)" ) ), String.join( "\n", summaries ) );
			assertTrue( summaries.stream().anyMatch( l -> l.contains( "(dis)connected groups." ) ), String.join( "\n", summaries ) );
			assertTrue( summaries.stream().anyMatch( l -> l.contains( "Transformation Models summary" ) ), String.join( "\n", summaries ) );
			assertTrue( summaries.stream().anyMatch( l -> l.contains( "Avg Error" ) ), String.join( "\n", summaries ) );
			assertTrue( summaries.size() < verbose.size() );
		}
		finally
		{
			IOFunctions.printIJLog = ijLog;
			AlignmentLog.restricted = restricted;
		}
	}

	/** three views (one translated), then two pairwise results as the matching reports them */
	static void emit()
	{
		final List< ViewId > views = new ArrayList<>();
		final HashMap< ViewId, Tile< AffineModel3D > > map = new HashMap<>();
		for ( int i = 0; i < 3; ++i )
		{
			final ViewId view = new ViewId( 0, i );
			final AffineModel3D model = new AffineModel3D();
			if ( i == 1 )
				model.set( 1, 0, 0, 10, 0, 1, 0, 0, 0, 0, 1, 0 );
			views.add( view );
			map.put( view, new Tile<>( model ) );
		}
		TransformationTools.printAndSummarizeTransformations( views, map );

		PairwiseResult.resetLogCounters();
		for ( int i = 0; i < 2; ++i )
		{
			final PairwiseResult< InterestPoint > result = new PairwiseResult<>( true );
			result.setDescription( "[view 0 >>> view " + ( i + 1 ) + "]" );
			result.setResult( System.currentTimeMillis(), "Loaded 5 correspondences" );
		}
	}

	static List< String > capture( final Runnable r )
	{
		final PrintStream out = System.out;
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		System.setOut( new PrintStream( buffer, true ) );
		try
		{
			r.run();
		}
		finally
		{
			System.setOut( out );
		}
		return Arrays.stream( buffer.toString( StandardCharsets.UTF_8 ).split( "\\R" ) ).filter( l -> !l.isEmpty() ).collect( Collectors.toList() );
	}
}
