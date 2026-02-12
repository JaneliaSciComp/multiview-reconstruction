package net.preibisch.mvrecon.process.fusion.blk.tps;

import net.imglib2.realtransform.ThinplateSplineTransform;

/**
 * Landmarks (corresponding source and target coordinates) that can be used
 * to construct a {@link ThinplateSplineTransform}.
 */
public final class Landmarks
{

	private final double[][] sourcePoints;

	private final double[][] targetPoints;

	private final int n;

	private final int numPoints;

	public Landmarks( final double[][] sourcePoints, final double[][] targetPoints )
	{
		this.sourcePoints = sourcePoints;
		this.targetPoints = targetPoints;

		n = sourcePoints.length;
		if ( n != targetPoints.length )
			throw new IllegalArgumentException( "dimensionality of source and target points doesn't match" );
		if ( n < 1 )
			throw new IllegalArgumentException( "points must be at least 1D" );

		numPoints = sourcePoints[ 0 ].length;
		for ( int d = 0; d < n; ++d )
			if ( sourcePoints[ d ].length != numPoints || targetPoints[ d ].length != numPoints )
				throw new IllegalArgumentException( "number of points doesn't match" );
	}

	public int numDimensions()
	{
		return n;
	}

	public int getNumPoints()
	{
		return numPoints;
	}

	/**
	 * Get the source point coordinates. The returned array contains at
	 * {@code[d][i]} the coordinate of the {@code i}-th source point in the
	 * {@code d}-th dimensions.
	 *
	 * @return source point coordinates
	 */
	public double[][] getSourcePoints()
	{
		return sourcePoints;
	}

	/**
	 * Get the target point coordinates. The returned array contains at
	 * {@code[d][i]} the coordinate of the {@code i}-th target point in the
	 * {@code d}-th dimensions.
	 *
	 * @return target point coordinates
	 */
	public double[][] getTargetPoints()
	{
		return targetPoints;
	}
}
