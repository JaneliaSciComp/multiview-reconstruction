import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import mpicbg.models.PointMatch;
import mpicbg.models.RansacState;
import mpicbg.models.RigidModel3D;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPointsZarrStore;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.ransac.RANSAC;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.DescriptorSearch;

/**
 * Multi-consensus RANSAC before (fixed iteration count) vs after (adaptive stop) on identical candidate sets of every
 * matched pair of one label: consensus set sizes, overlap of the final inlier sets, time.
 *
 *   java -cp <mvrecon fat jar> tools/RansacAB.java <dataset.xml> <label> <threads> [neighbors redundancy ratio radius]
 */
public class RansacAB
{
	record Result( List< Integer > sizes, Set< Long > inliers, double seconds ) {}

	public static void main( final String[] args ) throws Exception
	{
		final SpimData2 data = new XmlIoSpimData2().load( new File( args[ 0 ] ).toURI() );
		final String label = args[ 1 ];
		final int threads = Integer.parseInt( args[ 2 ] );
		final int neighbors = args.length > 3 ? Integer.parseInt( args[ 3 ] ) : 3, redundancy = args.length > 4 ? Integer.parseInt( args[ 4 ] ) : 0;
		final double ratio = args.length > 5 ? Double.parseDouble( args[ 5 ] ) : 3, radius = args.length > 6 ? Double.parseDouble( args[ 6 ] ) : 100;

		// pairs = every view pair that has stored correspondences for the label
		final InterestPointsZarrStore store = InterestPointsZarrStore.get( data.getBasePathURI() );
		final TreeSet< String > seen = new TreeSet<>();
		final List< int[] > pairs = new ArrayList<>();
		for ( final ViewDescription vd : data.getSequenceDescription().getViewDescriptions().values() )
		{
			if ( !vd.isPresent() ) continue;
			for ( final Pair< ViewId, String > partner : store.correspondingViews( InterestPointsZarrStore.Key.of( vd, label ) ) )
			{
				final int a = Math.min( vd.getViewSetupId(), partner.getA().getViewSetupId() ), b = Math.max( vd.getViewSetupId(), partner.getA().getViewSetupId() );
				if ( seen.add( a + "-" + b ) ) pairs.add( new int[] { a, b } );
			}
		}

		final Set< ViewId > views = new TreeSet<>();
		for ( final int[] p : pairs ) { views.add( new ViewId( 0, p[ 0 ] ) ); views.add( new ViewId( 0, p[ 1 ] ) ); }
		final Map< ViewId, HashMap< String, Double > > lm = new HashMap<>();
		for ( final ViewId v : views ) { final HashMap< String, Double > m = new HashMap<>(); m.put( label, 1.0 ); lm.put( v, m ); }
		final Map< ViewId, HashMap< String, Collection< InterestPoint > > > loaded =
				TransformationTools.getAllInterestPoints( new ArrayList<>( views ), data.getViewRegistrations().getViewRegistrations(), data.getViewInterestPoints().getViewInterestPoints(), lm, true, threads );
		System.out.println( pairs.size() + " pairs, " + views.size() + " views loaded" );

		final ExecutorService pool = Executors.newFixedThreadPool( threads );
		final List< Future< ArrayList< PointMatchGeneric< InterestPoint > > > > cf = new ArrayList<>();
		for ( final int[] p : pairs )
			cf.add( pool.submit( () -> {
				final ViewId vA = new ViewId( 0, p[ 0 ] ), vB = new ViewId( 0, p[ 1 ] );
				final Map< ViewId, HashMap< String, Collection< InterestPoint > > > ips = new HashMap<>();
				for ( final ViewId v : List.of( vA, vB ) ) { final HashMap< String, Collection< InterestPoint > > m = new HashMap<>(); m.put( label, new ArrayList<>( loaded.get( v ).get( label ) ) ); ips.put( v, m ); }
				final Set< Group< ViewId > > groups = new HashSet<>(); groups.add( new Group<>( vA ) ); groups.add( new Group<>( vB ) );
				// own copies: getTransform() calls updateModel() on the registration, which races when pairs sharing a view run concurrently
				final Map< ViewId, ViewRegistration > regs = new HashMap<>();
				for ( final ViewId v : List.of( vA, vB ) ) { final ViewRegistration r = data.getViewRegistrations().getViewRegistration( v ); regs.put( v, new ViewRegistration( r.getTimePointId(), r.getViewSetupId(), new ArrayList<>( r.getTransformList() ) ) ); }
				TransformationTools.filterForOverlappingInterestPoints( ips, groups, regs, data.getSequenceDescription().getViewDescriptions(), 1 );
				final ArrayList< InterestPoint > a = new ArrayList<>( ips.get( vA ).get( label ) ), b = new ArrayList<>( ips.get( vB ).get( label ) );
				if ( a.size() < neighbors + redundancy + 1 || b.size() < neighbors + redundancy + 1 ) // same guard as RGLDMPairwise: "Not enough detections to match"
					return new ArrayList<>();
				return new RGLDMMatcher< InterestPoint >().extractCorrespondenceCandidates( a, b, neighbors, redundancy, ratio, Double.MAX_VALUE, radius > 0, radius, DescriptorSearch.AUTO );
			} ) );
		final List< ArrayList< PointMatchGeneric< InterestPoint > > > cands = new ArrayList<>();
		for ( final Future< ArrayList< PointMatchGeneric< InterestPoint > > > f : cf ) cands.add( f.get() );
		System.out.println( "candidates computed" );

		final List< Result > before = phase( pool, cands, false ), after = phase( pool, cands, true );
		pool.shutdown();

		int same = 0; long sb = 0, sa = 0, ib = 0, ia = 0; double tb = 0, ta = 0;
		final List< Double > jac = new ArrayList<>();
		for ( int i = 0; i < pairs.size(); ++i )
		{
			final Result b = before.get( i ), x = after.get( i );
			final Set< Long > u = new HashSet<>( b.inliers ); u.addAll( x.inliers );
			final Set< Long > in = new HashSet<>( b.inliers ); in.retainAll( x.inliers );
			final double j = u.isEmpty() ? 1 : (double)in.size() / u.size(); jac.add( j );
			if ( b.sizes.size() == x.sizes.size() ) ++same;
			sb += b.sizes.size(); sa += x.sizes.size(); ib += b.inliers.size(); ia += x.inliers.size(); tb += b.seconds; ta += x.seconds;
			System.out.printf( "%3d <-> %3d  cand %6d | fixed %6.1fs %-30s | adaptive %6.1fs %-30s | inlier Jaccard %.3f%n", pairs.get( i )[ 0 ], pairs.get( i )[ 1 ], cands.get( i ).size(), b.seconds, b.sizes, x.seconds, x.sizes, j );
		}
		Collections.sort( jac );
		System.out.printf( "%nSUMMARY: %d pairs, same set count in %d; sets fixed %d adaptive %d; inliers fixed %d adaptive %d; RANSAC time sum fixed %.0f s adaptive %.0f s; inlier Jaccard min %.3f median %.3f%n",
				pairs.size(), same, sb, sa, ib, ia, tb, ta, jac.get( 0 ), jac.get( jac.size() / 2 ) );
	}

	static List< Result > phase( final ExecutorService pool, final List< ArrayList< PointMatchGeneric< InterestPoint > > > cands, final boolean adaptive ) throws Exception
	{
		final Predicate< RansacState< ?, PointMatch > > stop = adaptive ? RANSAC.adaptive( 0.999 ) : s -> false;
		final List< Future< Result > > fs = new ArrayList<>();
		for ( final ArrayList< PointMatchGeneric< InterestPoint > > c : cands )
			fs.add( pool.submit( () -> {
				final ArrayList< PointMatchGeneric< InterestPoint > > inl = new ArrayList<>(); final ArrayList< Integer > sets = new ArrayList<>();
				final long t0 = System.nanoTime();
				RANSAC.computeRANSAC( c, inl, sets, new RigidModel3D(), 5, 0.05, 12, 10000, true, 3.0, true, stop );
				final double s = ( System.nanoTime() - t0 ) / 1e9;
				final TreeMap< Integer, Integer > m = new TreeMap<>(); for ( final int x : sets ) m.merge( x, 1, Integer::sum );
				final Set< Long > ids = new HashSet<>(); for ( final PointMatchGeneric< InterestPoint > pm : inl ) ids.add( ( (long)pm.getPoint1().getId() << 32 ) | ( pm.getPoint2().getId() & 0xffffffffL ) );
				return new Result( new ArrayList<>( m.values() ), ids, s );
			} ) );
		final List< Result > out = new ArrayList<>(); for ( final Future< Result > f : fs ) out.add( f.get() ); return out;
	}
}
