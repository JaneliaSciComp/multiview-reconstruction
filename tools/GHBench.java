import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import net.imglib2.KDTree;
import net.imglib2.neighborsearch.KNearestNeighborSearchOnKDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.geometrichashing.GeometricHasher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSAC;
import mpicbg.models.AffineModel3D;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.LocalCoordinateSystemPointDescriptor;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.exception.NoSuitablePointsException;
import net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SubsetMatcher;

/**
 * Geometric hashing on a real view pair (points from a TSV: view id lx ly lz wx wy wz, see DumpPoints). Compares the
 * previous per-descriptor matching (imglib2 KDTree, 2 nearest descriptor entries, pairs deduplicated) against the
 * current GeometricHasher (owner-aware, through SubsetVectorMatching). Not part of the library.
 *
 *   java -cp target/classes:$(cat cp.txt) tools/GHBench.java points.tsv [redundancy...]
 */
public class GHBench
{
	public static void main( final String[] args ) throws Exception
	{
		final int[] redundancies = args.length > 1 ? java.util.Arrays.stream( args, 1, args.length ).mapToInt( Integer::parseInt ).toArray() : new int[] { 0, 1 };
		final double differenceThreshold = Float.MAX_VALUE;

		final ArrayList< InterestPoint > a = new ArrayList<>(), b = new ArrayList<>();
		try ( BufferedReader r = new BufferedReader( new FileReader( args[ 0 ] ) ) )
		{
			String line;
			while ( ( line = r.readLine() ) != null )
			{
				final String[] f = line.split( "\t" );
				final InterestPoint p = new InterestPoint( Integer.parseInt( f[ 1 ] ), new double[] { Double.parseDouble( f[ 2 ] ), Double.parseDouble( f[ 3 ] ), Double.parseDouble( f[ 4 ] ) } );
				final double[] w = p.getW();
				w[ 0 ] = Double.parseDouble( f[ 5 ] );
				w[ 1 ] = Double.parseDouble( f[ 6 ] );
				w[ 2 ] = Double.parseDouble( f[ 7 ] );
				( f[ 0 ].equals( "A" ) ? a : b ).add( p );
			}
		}
		System.out.println( "points: A=" + a.size() + " B=" + b.size() );

		for ( final int redundancy : redundancies )
			for ( final double ratio : new double[] { 10, 3 } )
			{
				System.out.println( "\n== redundancy " + redundancy + ", ratio " + ratio );
				for ( int repeat = 0; repeat < 2; ++repeat )
				{
					long t0 = System.nanoTime();
					final ArrayList< PointMatchGeneric< InterestPoint > > previous = previousMatching( a, b, redundancy, ratio, differenceThreshold );
					final double previousMs = ( System.nanoTime() - t0 ) / 1e6;

					t0 = System.nanoTime();
					final ArrayList< PointMatchGeneric< InterestPoint > > current = new GeometricHasher< InterestPoint >().extractCorrespondenceCandidates( a, b, differenceThreshold, redundancy, ratio );
					final double currentMs = ( System.nanoTime() - t0 ) / 1e6;

					t0 = System.nanoTime();
					final ArrayList< PointMatchGeneric< InterestPoint > > hybrid = hybridMatching( a, b, redundancy, ratio, differenceThreshold );
					final double hybridMs = ( System.nanoTime() - t0 ) / 1e6;

					final Set< String > prevPairs = pairs( previous ), curPairs = pairs( current );
					final Set< String > both = new HashSet<>( prevPairs );
					both.retainAll( curPairs );

					System.out.printf( java.util.Locale.ROOT,
							"repeat %d: previous %.0f ms, %d candidates -> %d RANSAC inliers | owner-min (current) %.0f ms, %d candidates -> %d inliers | owner-aware per A subset %.0f ms, %d candidates -> %d inliers | previous&current %d, only previous %d, only current %d%n",
							repeat, previousMs, prevPairs.size(), inliers( previous ), currentMs, curPairs.size(), inliers( current ), hybridMs, pairs( hybrid ).size(), inliers( hybrid ), both.size(), prevPairs.size() - both.size(), curPairs.size() - both.size() );
				}
			}
	}

	/** affine RANSAC with the GUI defaults (5 px error, 12 inliers, 10000 iterations), inlier count */
	static int inliers( final ArrayList< PointMatchGeneric< InterestPoint > > candidates )
	{
		final ArrayList< PointMatchGeneric< InterestPoint > > inliers = new ArrayList<>();
		RANSAC.computeRANSAC( new ArrayList<>( candidates ), inliers, new ArrayList<>(), new AffineModel3D(), 5, 0.05, 12, 10000, false, 3, false, s -> false );
		return inliers.size();
	}

	static Set< String > pairs( final ArrayList< PointMatchGeneric< InterestPoint > > matches )
	{
		final Set< String > s = new HashSet<>();
		for ( final PointMatchGeneric< InterestPoint > pm : matches )
			s.add( pm.getPoint1().getId() + ">" + pm.getPoint2().getId() );
		return s;
	}

	/** per A subset vector: C+1 nearest descriptor entries folded to distinct B owners, ratio test between best and second owner, union over A subsets */
	static ArrayList< PointMatchGeneric< InterestPoint > > hybridMatching( final ArrayList< InterestPoint > a, final ArrayList< InterestPoint > b, final int redundancy, final double ratio, final double differenceThreshold )
	{
		final int numSubsets = SubsetMatcher.computePD( 3 + redundancy, 3, 1 ).length;
		final ArrayList< LocalCoordinateSystemPointDescriptor< InterestPoint > > descsA = descriptors( a, redundancy ), descsB = descriptors( b, redundancy );
		final KDTree< LocalCoordinateSystemPointDescriptor< InterestPoint > > lookUp = new KDTree<>( descsB, descsB );
		final KNearestNeighborSearchOnKDTree< LocalCoordinateSystemPointDescriptor< InterestPoint > > nn = new KNearestNeighborSearchOnKDTree<>( lookUp, numSubsets + 1 );
		final Set< String > seen = new HashSet<>();
		final ArrayList< PointMatchGeneric< InterestPoint > > pairs = new ArrayList<>();
		for ( final LocalCoordinateSystemPointDescriptor< InterestPoint > d : descsA )
		{
			nn.search( d );
			InterestPoint bestOwner = null;
			double best = Double.MAX_VALUE, second = Double.MAX_VALUE;
			for ( int i = 0; i <= numSubsets; ++i )
			{
				final LocalCoordinateSystemPointDescriptor< InterestPoint > e = nn.getSampler( i ).get();
				final double dist = d.descriptorDistance( e );
				if ( bestOwner == null ) { best = dist; bestOwner = e.getBasisPoint(); }
				else if ( e.getBasisPoint() != bestOwner ) { second = dist; break; }
			}
			if ( bestOwner != null && second < Double.MAX_VALUE && best < differenceThreshold && best * ratio <= second && seen.add( d.getBasisPoint().getId() + ">" + bestOwner.getId() ) )
				pairs.add( new PointMatchGeneric<>( d.getBasisPoint(), bestOwner ) );
		}
		return pairs;
	}

	/** the matching as it was before: 2 nearest descriptor entries, ratio test between them, pairs deduplicated */
	static ArrayList< PointMatchGeneric< InterestPoint > > previousMatching( final ArrayList< InterestPoint > a, final ArrayList< InterestPoint > b, final int redundancy, final double ratio, final double differenceThreshold )
	{
		final ArrayList< LocalCoordinateSystemPointDescriptor< InterestPoint > > descsA = descriptors( a, redundancy ), descsB = descriptors( b, redundancy );
		final KDTree< LocalCoordinateSystemPointDescriptor< InterestPoint > > lookUp = new KDTree<>( descsB, descsB );
		final KNearestNeighborSearchOnKDTree< LocalCoordinateSystemPointDescriptor< InterestPoint > > nn = new KNearestNeighborSearchOnKDTree<>( lookUp, 2 );
		final Set< String > seen = new HashSet<>();
		final ArrayList< PointMatchGeneric< InterestPoint > > pairs = new ArrayList<>();
		for ( final LocalCoordinateSystemPointDescriptor< InterestPoint > d : descsA )
		{
			nn.search( d );
			final double best = d.descriptorDistance( nn.getSampler( 0 ).get() ), second = d.descriptorDistance( nn.getSampler( 1 ).get() );
			final InterestPoint pA = d.getBasisPoint(), pB = nn.getSampler( 0 ).get().getBasisPoint();
			if ( best < differenceThreshold && best * ratio <= second && seen.add( pA.getId() + ">" + pB.getId() ) )
				pairs.add( new PointMatchGeneric<>( pA, pB ) );
		}
		return pairs;
	}

	static ArrayList< LocalCoordinateSystemPointDescriptor< InterestPoint > > descriptors( final ArrayList< InterestPoint > points, final int redundancy )
	{
		final KDTree< InterestPoint > tree = new KDTree<>( points, points );
		final KNearestNeighborSearchOnKDTree< InterestPoint > nn = new KNearestNeighborSearchOnKDTree<>( tree, 3 + redundancy + 1 );
		final int[][] subsets = SubsetMatcher.computePD( 3 + redundancy, 3, 1 );
		final ArrayList< LocalCoordinateSystemPointDescriptor< InterestPoint > > descs = new ArrayList<>();
		for ( final InterestPoint p : points )
		{
			nn.search( p );
			for ( final int[] subset : subsets )
			{
				final ArrayList< InterestPoint > neighbors = new ArrayList<>();
				for ( final int n : subset )
					neighbors.add( nn.getSampler( n ).get() );
				try { descs.add( new LocalCoordinateSystemPointDescriptor<>( p, neighbors, false ) ); }
				catch ( final NoSuitablePointsException e ) {}
			}
		}
		return descs;
	}
}
