package net.preibisch.mvrecon.process.fusion.tps;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI.FusionType;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.fusion.blk.BlkThinPlateSplineFusion;

public class TestBlkThinPlateSplineFusion
{

	public static void main( String[] args ) throws SpimDataException
	{
		final SpimData2 data =
				new XmlIoSpimData2().load(
						URI.create("file:/Users/preibischs/SparkTest/Stitching/dataset.split.xml") );

		final ViewerImgLoader underlyingImgLoader = BlkThinPlateSplineFusion.getUnderlyingImageLoader(data);

		if ( underlyingImgLoader == null )
			return;

		final SplitViewerImgLoader splitImgLoader = ( SplitViewerImgLoader ) data.getSequenceDescription().getImgLoader();
		final SequenceDescription underlyingSD = splitImgLoader.underlyingSequenceDescription();

		// get all underlying ViewIds with channelId == 0
		final List< ViewId > underlyingViewIds =
				underlyingSD.getViewDescriptions().values().stream()
				.filter( vd -> vd.isPresent() )
				.filter( vd -> vd.getViewSetup().getChannel().getId() == 0 /*&& vd.getViewSetupId() == 0*/ )
				.map( vd -> new ViewId(vd.getTimePointId(), vd.getViewSetupId() ) )
				.collect( Collectors.toList() );

		if ( underlyingViewIds.size() == 0 )
		{
			System.out.println( "No views remaining. stopping." );
			return;
		}

		final Map<Integer, List<Integer>> old2newSetupId = BlkThinPlateSplineFusion.old2newSetupId( splitImgLoader.new2oldSetupId() );
		final List< ViewId > splitViewIds = BlkThinPlateSplineFusion.splitViewIds( underlyingViewIds, old2newSetupId );

		// we estimate the bounding box using the split imagel loader, which will be closer to real bounding box
		Interval boundingBox = new BoundingBoxMaximal( splitViewIds, data ).estimate( "Full Bounding Box" );
		System.out.println( boundingBox );

		// for testing
		//boundingBox = Intervals.createMinMax( -496, -719, 870, 482, 707, 1129 );

		new ImageJ();

		final int[] blockSize = new int[] { 128, 128, 4 };
		final int intervalExpansion = 10;

		final BlockSupplier<UnsignedByteType> supplier = BlkThinPlateSplineFusion.init(
				(i,o) -> { o.set( Math.round( i.get() )); },
				splitImgLoader,
				splitViewIds,
				data.getViewRegistrations().getViewRegistrations(), // already adjusted for anisotropy
				data.getSequenceDescription().getViewDescriptions(),
				FusionType.CLOSEST_PIXEL_WINS,
				intervalExpansion,
				Double.NaN,
				1,
				null,
				null,
				boundingBox, // already adjusted for anisotropy???
				new UnsignedByteType(),
				blockSize );

		ImageJFunctions.show(
				BlockAlgoUtils.cellImg( supplier, boundingBox.dimensionsAsLongArray(), blockSize ),
				Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() ) );
	}
}
