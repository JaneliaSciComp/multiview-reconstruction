package net.preibisch.mvrecon.process.flatfield;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

public class GainFieldTest
{
	/**
	 * A chain of 12 tiles with a x2.2 ramp along x (c_x = 0.8), none along y, 10% tile scatter: the fit must keep x,
	 * drop y, and recover c_x. The same constraints through spotlight's gainfield.fit gave identical c, sigma and
	 * status (checked to 1e-15 when this was ported).
	 */
	@Test
	public void recoversRampAndDropsFlatAxis()
	{
		final Random rnd = new Random( 3 );
		final int tiles = 12;
		final double[] u = new double[ tiles ];
		for ( int t = 0; t < tiles; ++t )
			u[ t ] = 0.1 * rnd.nextGaussian();
		final List< GainField.Constraint > cons = new ArrayList<>();
		for ( int a = 0; a < tiles; ++a )
			for ( int b = a + 1; b < Math.min( tiles, a + 4 ); ++b )
			{
				final double[] pos = new double[ 4 ];
				for ( int k = 0; k < 4; ++k )
					pos[ k ] = 0.9 * rnd.nextDouble() - 0.45;
				final double d = 0.8 * ( pos[ 1 ] - pos[ 3 ] ) + u[ a ] - u[ b ] + 0.03 * rnd.nextGaussian();
				cons.add( new GainField.Constraint( a, b, d, 1e4 + 1e6 * rnd.nextDouble(), pos ) );
			}

		final GainField.Fit fit = GainField.fit( cons, new int[ tiles ] );
		final GainField.Field f = fit.fields.get( 0 );
		assertEquals( "kept", fit.status[ 1 ] );
		assertEquals( 0, f.c[ 0 ] );
		assertEquals( 0.8, f.c[ 1 ], 3 * f.sigma[ 1 ] );
	}
}
