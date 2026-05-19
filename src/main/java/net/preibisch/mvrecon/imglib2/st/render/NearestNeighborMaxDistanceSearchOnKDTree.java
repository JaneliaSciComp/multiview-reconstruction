package net.preibisch.mvrecon.imglib2.st.render;

import net.preibisch.mvrecon.imglib2.st.render.util.SimpleRealLocalizable;
import net.preibisch.mvrecon.imglib2.st.render.util.SimpleSampler;

import net.imglib2.KDTree;
import net.imglib2.RealLocalizable;
import net.imglib2.Sampler;
import net.imglib2.neighborsearch.NearestNeighborSearchOnKDTree;

public class NearestNeighborMaxDistanceSearchOnKDTree< T > extends NearestNeighborSearchOnKDTree< T >
{
	final KDTree< T > tree;          // shadow of super (now private)
	final T outofbounds;
	final SimpleSampler< T > oobsSampler;
	final double[] pos;              // current query position; shadow of super (now private)
	final SimpleRealLocalizable position;
	final double maxSqDistance, maxDistance;

	Sampler< T > value;
	RealLocalizable point;
	double newbestSquDistance;

	public NearestNeighborMaxDistanceSearchOnKDTree( final KDTree< T > tree, final T outofbounds, final double maxDistance )
	{
		super( tree );

		this.tree = tree;
		this.pos = new double[ tree.numDimensions() ];
		this.oobsSampler = new SimpleSampler< T >( outofbounds );
		this.position = new SimpleRealLocalizable( pos );
		this.maxDistance = maxDistance;
		this.maxSqDistance = maxDistance * maxDistance;
		this.outofbounds = outofbounds;
	}

	@Override
	public void search( final RealLocalizable p )
	{
		super.search( p );
		p.localize( pos );

		final double sq = super.getSquareDistance();
		if ( sq > maxSqDistance )
		{
			value = oobsSampler;
			point = position;
			newbestSquDistance = 0;
		}
		else
		{
			value = super.getSampler();
			point = super.getPosition();
			newbestSquDistance = sq;
		}
	}

	@Override
	public Sampler< T > getSampler()
	{
		return value;
	}

	@Override
	public RealLocalizable getPosition()
	{
		return point;
	}

	@Override
	public double getSquareDistance()
	{
		return newbestSquDistance;
	}

	@Override
	public double getDistance()
	{
		return Math.sqrt( newbestSquDistance );
	}

	@Override
	public NearestNeighborMaxDistanceSearchOnKDTree< T > copy()
	{
		// Note: the original hot-knife copy copied the package/private bestPoint and bestSquDistance
		// of the super class. In current imglib2 those are private; rely on lazy re-population
		// via the next search(...) call instead. Caller must call search() on the returned copy
		// before using it (same as fresh instances).
		final NearestNeighborMaxDistanceSearchOnKDTree< T > copy = new NearestNeighborMaxDistanceSearchOnKDTree< T >( tree, outofbounds, maxDistance );
		copy.newbestSquDistance = newbestSquDistance;
		copy.point = point;
		copy.value = value;
		return copy;
	}
}
