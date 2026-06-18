package net.preibisch.mvrecon.process.fusion.tps;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.FinalInterval;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.fusion.blk.BlkThinPlateSplineFusion;
import net.preibisch.mvrecon.process.fusion.blk.SplitImgLoaderThinPlateSplineFusion;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;

/**
 * This needs a minimal grid size of 2x2x2, otherwise we get 'funny' transformations
 */
public class TestTPSDfieldFusion
{

	static boolean writeDontShow = true;
	static boolean wiggleLandmarks = true;
	static double wiggleAmount = 10;

	public static Map< ViewId, Landmarks > getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final Collection< ViewId > underlyingViewsToProcess,
			final double anisotropyFactor,
			final double downsampling )
	{
		final Map<Integer, Integer> new2oldSetupId = splitImgLoader.new2oldSetupId();
		final Map<Integer, List<Integer>> old2newSetupId = SplitImgLoaderThinPlateSplineFusion.old2newSetupId( new2oldSetupId );

		final Map< ViewId, Landmarks > underlyingViewId2TPSCoefficients = new HashMap<>();

		for ( final ViewId underlyingViewId : underlyingViewsToProcess )
		{
			System.out.println( "\nProcessing underlyingViewId: " + Group.pvid( underlyingViewId ) + ", which was split into " + old2newSetupId.get( underlyingViewId.getViewSetupId() ).size() + " pieces." );

			final Landmarks coeff =
					SplitImgLoaderThinPlateSplineFusion.getCoefficients(splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId, anisotropyFactor, downsampling);

			underlyingViewId2TPSCoefficients.put( underlyingViewId, coeff );

			if( wiggleLandmarks )
				TestTPSFusion.wiggle(coeff.getTargetPoints(), wiggleAmount, new Random(1));

			System.out.println( "source: " + Arrays.deepToString( coeff.getSourcePoints() ) );
			System.out.println( "target: " + Arrays.deepToString( coeff.getTargetPoints() ) );
		}

		return underlyingViewId2TPSCoefficients;
	}


	public static void main( String[] args ) throws SpimDataException
	{
		final SpimData2 data =
				new XmlIoSpimData2().load(
						URI.create("file:/Users/pietzsch/Desktop/data/Janelia/split_dataset/dataset.split.xml"));

		final ViewerImgLoader underlyingImgLoader = SplitImgLoaderThinPlateSplineFusion.getUnderlyingImageLoader(data);

		if ( underlyingImgLoader == null )
			return;

		final SplitViewerImgLoader splitImgLoader = ( SplitViewerImgLoader ) data.getSequenceDescription().getImgLoader();
		final SequenceDescription underlyingSD = splitImgLoader.underlyingSequenceDescription();
		final Map< ViewId, ViewDescription > underlyingViewDescription = underlyingSD.getViewDescriptions();

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

		// get all split ViewIds for the set of underlying ViewIds
		final List<ViewId> splitViewIds =
				SplitImgLoaderThinPlateSplineFusion.splitViewIds( underlyingViewIds, SplitImgLoaderThinPlateSplineFusion.old2newSetupId( splitImgLoader.new2oldSetupId() ) );

		System.out.println( "Split viewIds: " + splitViewIds.size() );

		final double downsampling = Double.NaN;
		final double anisotropyFactor = TransformationTools.getAverageAnisotropyFactor( data, underlyingViewIds );

		final Map< ViewId, Landmarks > underlyingViewLandmarks =
				getCoefficients( splitImgLoader, data.getViewRegistrations().getViewRegistrations(), underlyingViewIds, anisotropyFactor, downsampling );

		// we estimate the bounding box using the split imagel loader, which will be closer to real bounding box
		BoundingBoxMaximal boundingBoxMaximal = new BoundingBoxMaximal( splitViewIds, data );
		BoundingBox boundingBox = boundingBoxMaximal.estimate( "Full Bounding Box" );
		System.out.println( boundingBox );

		if ( !Double.isNaN( anisotropyFactor ) )
		{
			// prepare downsampled boundingbox
			final long[] minBB = boundingBox.minAsLongArray();
			final long[] maxBB = boundingBox.maxAsLongArray();

			minBB[ 2 ] = Math.round( Math.floor( minBB[ 2 ] / anisotropyFactor ) );
			maxBB[ 2 ] = Math.round( Math.ceil( maxBB[ 2 ] / anisotropyFactor ) );

			boundingBox = new BoundingBox( new FinalInterval( minBB, maxBB ) );
			System.out.println( boundingBox );
		}

		new ImageJ();

		final int[] blockSize = { 256, 256, 1 };
		final BlockSupplier< FloatType > blocks = BlkThinPlateSplineFusion.init(
				null,
				underlyingImgLoader,
				underlyingViewIds,
				underlyingViewDescription,
				underlyingViewLandmarks,
				FusionGUI.FusionType.MAX_INTENSITY,
				anisotropyFactor,
				1,
				null,
				null,
				boundingBox,
				new FloatType(),
				blockSize );

		CachedCellImg<FloatType, ?> fused =
				BlockAlgoUtils.cellImg( blocks, boundingBox.dimensionsAsLongArray(), blockSize );

		if( writeDontShow )
		{
			ImagePlus imp = ImageJFunctions.wrap(fused, "fused", Executors.newFixedThreadPool( 8 ));
			IJ.save(imp, "/Users/pietzsch/Desktop/TpsDfieldFusion_8_v03.tif");
			System.out.println("done");
		}
		else
			ImageJFunctions.show( fused, Executors.newFixedThreadPool( 8 ) );
	}
}