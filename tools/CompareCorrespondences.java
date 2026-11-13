import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.CorrespondingInterestPoints;
import net.preibisch.mvrecon.fiji.spimdata.interestpoints.InterestPoints;

/** Compare the correspondences of one label between two datasets: java -cp <mvrecon fat jar> CompareCorrespondences.java a.xml b.xml label */
public class CompareCorrespondences
{
	public static void main( final String[] args ) throws Exception
	{
		final SpimData2 a = new XmlIoSpimData2().load( new File( args[ 0 ] ).toURI() ), b = new XmlIoSpimData2().load( new File( args[ 1 ] ).toURI() );
		final String label = args[ 2 ];
		long totalA = 0, totalB = 0;
		int views = 0, differing = 0;
		final List< String > diffs = new ArrayList<>();
		for ( final ViewDescription vd : a.getSequenceDescription().getViewDescriptions().values() )
		{
			if ( !vd.isPresent() )
				continue;
			final int nA = count( a, vd, label ), nB = count( b, vd, label );
			if ( nA < 0 || nB < 0 )
				continue;
			++views;
			totalA += nA;
			totalB += nB;
			if ( nA != nB )
			{
				++differing;
				diffs.add( "  setup " + vd.getViewSetupId() + ": " + nA + " vs " + nB );
			}
		}
		System.out.println( "label " + label + ", " + views + " views" );
		System.out.println( "correspondences: " + args[ 0 ] + " = " + totalA + "   " + args[ 1 ] + " = " + totalB );
		System.out.println( "views with different counts: " + differing );
		diffs.stream().limit( 20 ).forEach( System.out::println );
	}

	static int count( final SpimData2 data, final ViewId v, final String label )
	{
		final InterestPoints ip = data.getViewInterestPoints().getViewInterestPointLists( v ).getInterestPointList( label );
		if ( ip == null )
			return -1;
		final Collection< CorrespondingInterestPoints > c = ip.getCorrespondingInterestPointsCopy();
		return c == null ? 0 : c.size();
	}
}
