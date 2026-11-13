import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.KDTree;
import net.imglib2.RealPoint;
import net.imglib2.neighborsearch.RadiusNeighborSearchOnKDTree;
import net.preibisch.legacy.mpicbg.PointMatchGeneric;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoint;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.RGLDMMatcher;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.methods.rgldm.DescriptorSearch;

/**
 * RGLDM descriptor-matching benchmark: flat KD-tree vs blocked brute force over point count (or neighbor count) x search radius.
 * Standalone; run through tools/rgldm-bench.sh against the fat jar (java source-file mode, nothing to compile).
 *
 * Output: one TSV row per (size, radius, method) with the median wall time over --repeats runs, plus the candidate
 * count and whether every method returned the same candidate set as the first one.
 */
public class RGLDMBench
{
	public static void main( final String[] args ) throws Exception
	{
		final Map< String, String > opt = parse( args );
		final String xml = need( opt, "xml" );
		final String label = need( opt, "label" );
		final String[] setups = need( opt, "setups" ).split( "," );
		final int[] sizes = ints( opt.getOrDefault( "sizes", "1000,2000,5000,10000,20000,all" ) );
		final double[] radii = doubles( opt.getOrDefault( "radii", "0,50,100,250,500,1000,2000,5000" ) );
		final int[] neighborsList = ints( opt.getOrDefault( "neighbors", "3" ) );
		final int redundancy = Integer.parseInt( opt.getOrDefault( "redundancy", "1" ) );
		final double ratio = Double.parseDouble( opt.getOrDefault( "ratio", "3" ) );
		final int repeats = Integer.parseInt( opt.getOrDefault( "repeats", "3" ) );
		final double maxSeconds = Double.parseDouble( opt.getOrDefault( "maxSeconds", "120" ) );
		final boolean overlapOnly = Boolean.parseBoolean( opt.getOrDefault( "overlapOnly", "true" ) );
		final boolean legacy = Boolean.parseBoolean( opt.getOrDefault( "legacy", "false" ) );
		final String out = opt.getOrDefault( "out", "rgldm-bench.tsv" );

		final ViewId vA = new ViewId( 0, Integer.parseInt( setups[ 0 ] ) ), vB = new ViewId( 0, Integer.parseInt( setups[ 1 ] ) );
		final SpimData2 data = new XmlIoSpimData2().load( new File( xml ).toURI() );

		final Map< ViewId, HashMap< String, Double > > labelMap = new HashMap<>();
		for ( final ViewId v : List.of( vA, vB ) )
		{
			final HashMap< String, Double > m = new HashMap<>();
			m.put( label, 1.0 );
			labelMap.put( v, m );
		}
		final Map< ViewId, HashMap< String, java.util.Collection< InterestPoint > > > ips = TransformationTools.getAllTransformedInterestPoints(
				List.of( vA, vB ), data.getViewRegistrations().getViewRegistrations(), data.getViewInterestPoints().getViewInterestPoints(), labelMap );
		if ( overlapOnly )
		{
			final Set< Group< ViewId > > groups = new HashSet<>();
			groups.add( new Group<>( vA ) );
			groups.add( new Group<>( vB ) );
			TransformationTools.filterForOverlappingInterestPoints( ips, groups, data.getViewRegistrations().getViewRegistrations(), data.getSequenceDescription().getViewDescriptions() );
		}
		final ArrayList< InterestPoint > fullA = new ArrayList<>( ips.get( vA ).get( label ) ), fullB = new ArrayList<>( ips.get( vB ).get( label ) );
		System.out.println( "points after loading: A=" + fullA.size() + " B=" + fullB.size() + " (overlapOnly=" + overlapOnly + ")" );

		final String host = InetAddress.getLocalHost().getHostName(), arch = System.getProperty( "os.arch" );
		final int cores = Runtime.getRuntime().availableProcessors();
		final String javaVersion = System.getProperty( "java.version" ), vector = maxVectorSize();
		final String cpu = cpuName();

		final boolean append = new File( out ).exists();
		try ( PrintWriter w = new PrintWriter( new FileWriter( out, true ) ) )
		{
			if ( !append )
				w.println( String.join( "\t", "host", "cpu", "arch", "cores", "java", "maxVectorSize", "label", "setupA", "setupB", "neighbors", "redundancy",
						"radius", "nA", "nB", "descriptorsA", "descriptorsB", "inRadiusAvg", "inRadiusFraction", "method", "medianMs", "candidates", "sameAsFirst" ) );

			final int parallel = Integer.parseInt( opt.getOrDefault( "parallel", "" + Math.max( 1, Runtime.getRuntime().availableProcessors() - 1 ) ) );

			// one task per (size, neighbors, radius); both methods run back to back inside the task so they see the same load
			final List< int[] > tasks = new ArrayList<>();
			for ( int si = 0; si < sizes.length; ++si )
				for ( int ni = 0; ni < neighborsList.length; ++ni )
					for ( int ri = 0; ri < radii.length; ++ri )
						tasks.add( new int[] { si, ni, ri } );
			System.out.println( tasks.size() + " configurations, " + parallel + " in parallel (each single-threaded)" );

			final Map< Integer, List< InterestPoint >[] > cropped = new HashMap<>();
			for ( int si = 0; si < sizes.length; ++si )
			{
				final ArrayList< InterestPoint > a = new ArrayList<>(), b = new ArrayList<>();
				crop( fullA, fullB, sizes[ si ], a, b );
				@SuppressWarnings( "unchecked" )
				final List< InterestPoint >[] ab = new List[] { a, b };
				cropped.put( si, ab );
			}

			final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool( parallel );
			final List< java.util.concurrent.Future< List< String > > > futures = new ArrayList<>();
			for ( final int[] t : tasks )
				futures.add( pool.submit( () -> runConfig( cropped.get( t[ 0 ] )[ 0 ], cropped.get( t[ 0 ] )[ 1 ], neighborsList[ t[ 1 ] ], redundancy, radii[ t[ 2 ] ], ratio, repeats, maxSeconds, legacy,
						new String[] { host, cpu, arch, "" + cores, javaVersion, vector, label, setups[ 0 ], setups[ 1 ] } ) ) );
			for ( final java.util.concurrent.Future< List< String > > f : futures )
				for ( final String row : f.get() )
				{
					w.println( row );
					w.flush();
					System.out.println( row );
				}
			pool.shutdown();
		}
		System.out.println( "wrote " + out );
	}

	static List< String > runConfig( final List< InterestPoint > a, final List< InterestPoint > b, final int neighbors, final int redundancy, final double radius,
			final double ratio, final int repeats, final double maxSeconds, final boolean legacy, final String[] prefix )
	{
		final List< String > rows = new ArrayList<>();
		if ( a.size() < neighbors + redundancy + 2 || b.size() < neighbors + redundancy + 2 )
			return rows;
		final int subsets = subsets( neighbors, redundancy );
		final boolean limit = radius > 0;
		final double inRadius = limit ? averageInRadius( a, b, radius ) : b.size();
		final RGLDMMatcher< InterestPoint > matcher = new RGLDMMatcher<>();

		final List< DescriptorSearch > methods = new ArrayList<>( List.of( DescriptorSearch.FLAT_KDTREE, DescriptorSearch.BLOCKED_BRUTE_FORCE ) );
		if ( legacy )
			methods.add( DescriptorSearch.LEGACY );

		Set< String > first = null;
		for ( final DescriptorSearch method : methods )
		{
			final double[] ms = new double[ repeats ];
			ArrayList< PointMatchGeneric< InterestPoint > > result = null;
			int done = 0;
			for ( int r = 0; r < repeats; ++r )
			{
				final long t0 = System.nanoTime();
				result = matcher.extractCorrespondenceCandidates( a, b, neighbors, redundancy, ratio, Double.MAX_VALUE, limit, radius, method );
				ms[ r ] = ( System.nanoTime() - t0 ) / 1e6;
				++done;
				if ( ms[ r ] > maxSeconds * 1000 )
					break;
			}
			final double median = median( Arrays.copyOf( ms, done ) );
			final Set< String > pairs = pairs( result );
			final boolean same = ( first == null ) || first.equals( pairs );
			if ( first == null )
				first = pairs;
			rows.add( String.join( "\t", String.join( "\t", prefix ), "" + neighbors, "" + redundancy,
					fmt( radius ), "" + a.size(), "" + b.size(), "" + a.size() * subsets, "" + b.size() * subsets, fmt( inRadius ), fmt( inRadius / b.size() ),
					method.name(), fmt( median ), "" + result.size(), "" + same ) );
		}
		return rows;
	}

	/** shrink a box around the center of A until it holds about {@code size} A points; B is cut with the same box (size <= 0: everything) */
	static void crop( final List< InterestPoint > fullA, final List< InterestPoint > fullB, final int size, final List< InterestPoint > a, final List< InterestPoint > b )
	{
		if ( size <= 0 || size >= fullA.size() )
		{
			a.addAll( fullA );
			b.addAll( fullB );
			return;
		}
		final double[] min = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE }, max = { -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };
		for ( final InterestPoint p : fullA )
			for ( int d = 0; d < 3; ++d )
			{
				min[ d ] = Math.min( min[ d ], p.getW()[ d ] );
				max[ d ] = Math.max( max[ d ], p.getW()[ d ] );
			}
		double lo = 0, hi = 1;
		for ( int it = 0; it < 40; ++it )
		{
			final double s = ( lo + hi ) / 2;
			if ( countInside( fullA, min, max, s ) < size ) lo = s; else hi = s;
		}
		for ( final InterestPoint p : fullA ) if ( inside( p, min, max, hi ) ) a.add( p );
		for ( final InterestPoint p : fullB ) if ( inside( p, min, max, hi ) ) b.add( p );
	}

	static int countInside( final List< InterestPoint > pts, final double[] min, final double[] max, final double s )
	{
		int n = 0;
		for ( final InterestPoint p : pts ) if ( inside( p, min, max, s ) ) ++n;
		return n;
	}

	static boolean inside( final InterestPoint p, final double[] min, final double[] max, final double s )
	{
		for ( int d = 0; d < 3; ++d )
		{
			final double c = ( min[ d ] + max[ d ] ) / 2, h = ( max[ d ] - min[ d ] ) / 2 * s;
			if ( p.getW()[ d ] < c - h || p.getW()[ d ] > c + h ) return false;
		}
		return true;
	}

	static double averageInRadius( final List< InterestPoint > a, final List< InterestPoint > b, final double radius )
	{
		final List< RealPoint > pos = new ArrayList<>();
		final List< Integer > ids = new ArrayList<>();
		for ( int i = 0; i < b.size(); ++i ) { pos.add( new RealPoint( b.get( i ).getW() ) ); ids.add( i ); }
		final RadiusNeighborSearchOnKDTree< Integer > rs = new RadiusNeighborSearchOnKDTree<>( new KDTree<>( ids, pos ) );
		long total = 0;
		for ( final InterestPoint p : a ) { rs.search( new RealPoint( p.getW() ), radius, false ); total += rs.numNeighbors(); }
		return (double)total / a.size();
	}

	static int subsets( final int n, final int r ) { return net.preibisch.mvrecon.process.pointcloud.pointdescriptor.matcher.SubsetMatcher.computePD( n + r, n, 0 ).length; }

	static Set< String > pairs( final List< PointMatchGeneric< InterestPoint > > m )
	{
		final Set< String > s = new HashSet<>();
		for ( final PointMatchGeneric< InterestPoint > pm : m ) s.add( pm.getPoint1().getId() + ">" + pm.getPoint2().getId() );
		return s;
	}

	static double median( final double[] v ) { Arrays.sort( v ); return v.length % 2 == 1 ? v[ v.length / 2 ] : ( v[ v.length / 2 - 1 ] + v[ v.length / 2 ] ) / 2; }
	static String fmt( final double d ) { return String.format( java.util.Locale.ROOT, "%.3f", d ); }

	static String maxVectorSize()
	{
		try
		{
			final com.sun.management.HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean( com.sun.management.HotSpotDiagnosticMXBean.class );
			return bean.getVMOption( "MaxVectorSize" ).getValue();
		}
		catch ( final Throwable t ) { return "?"; }
	}

	static String cpuName()
	{
		try
		{
			final String os = System.getProperty( "os.name" ).toLowerCase();
			final ProcessBuilder pb = os.contains( "mac" ) ? new ProcessBuilder( "sysctl", "-n", "machdep.cpu.brand_string" ) : new ProcessBuilder( "sh", "-c", "grep -m1 'model name' /proc/cpuinfo | cut -d: -f2" );
			final Process p = pb.start();
			final String s = new String( p.getInputStream().readAllBytes() ).trim();
			return s.isEmpty() ? "?" : s.replace( '\t', ' ' );
		}
		catch ( final Throwable t ) { return "?"; }
	}

	static Map< String, String > parse( final String[] args )
	{
		final Map< String, String > m = new LinkedHashMap<>();
		for ( int i = 0; i < args.length; ++i )
			if ( args[ i ].startsWith( "--" ) )
				m.put( args[ i ].substring( 2 ), ( i + 1 < args.length && !args[ i + 1 ].startsWith( "--" ) ) ? args[ ++i ] : "true" );
		return m;
	}
	static String need( final Map< String, String > m, final String k ) { if ( !m.containsKey( k ) ) throw new IllegalArgumentException( "missing --" + k ); return m.get( k ); }
	static int[] ints( final String s ) { return Arrays.stream( s.split( "," ) ).mapToInt( x -> x.equals( "all" ) ? 0 : Integer.parseInt( x.trim() ) ).toArray(); }
	static double[] doubles( final String s ) { return Arrays.stream( s.split( "," ) ).mapToDouble( x -> Double.parseDouble( x.trim() ) ).toArray(); }
}
