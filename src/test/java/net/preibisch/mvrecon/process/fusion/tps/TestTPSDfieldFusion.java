package net.preibisch.mvrecon.process.fusion.tps;

import static net.imglib2.algorithm.blocks.dfield.DisplacementFieldTransform.displacementFieldAffine;
import static net.imglib2.util.Util.safeInt;
import static net.imglib2.view.fluent.RandomAccessibleIntervalView.Extension.zero;

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
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.sequence.ImgLoader;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Dimensions;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.blocks.AbstractBlockSupplier;
import net.imglib2.algorithm.blocks.BlockAlgoUtils;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.algorithm.blocks.convert.Convert;
import net.imglib2.algorithm.blocks.dfield.DisplacementField;
import net.imglib2.algorithm.blocks.transform.Transform;
import net.imglib2.blocks.BlockInterval;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.img.array.ArrayCursor;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.fusion.blk.BlkThinPlateSplineFusion;
import net.preibisch.mvrecon.process.fusion.blk.tps.SampleTPS;
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

	public static HashMap< ViewId, Pair< double[][], double[][] > > getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final Collection< ViewId > underlyingViewsToProcess,
			final double anisotropyFactor,
			final double downsampling )
	{
		final Map<Integer, Integer> new2oldSetupId = splitImgLoader.new2oldSetupId();
		final Map<Integer, List<Integer>> old2newSetupId = BlkThinPlateSplineFusion.old2newSetupId( new2oldSetupId );

		final HashMap< ViewId, Pair< double[][], double[][] > > underlyingViewId2TPSCoefficients = new HashMap<>();

		for ( final ViewId underlyingViewId : underlyingViewsToProcess )
		{
			System.out.println( "\nProcessing underlyingViewId: " + Group.pvid( underlyingViewId ) + ", which was split into " + old2newSetupId.get( underlyingViewId.getViewSetupId() ).size() + " pieces." );

			final Pair<double[][], double[][]> coeff =
					BlkThinPlateSplineFusion.getCoefficients(splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId, anisotropyFactor, downsampling);

			underlyingViewId2TPSCoefficients.put( underlyingViewId, coeff );

			if( wiggleLandmarks )
				TestTPSFusion.wiggle(coeff.getB(), wiggleAmount, new Random(1));

			System.out.println( "source: " + Arrays.deepToString( coeff.getA() ) );
			System.out.println( "target: " + Arrays.deepToString( coeff.getB() ) );
		}

		return underlyingViewId2TPSCoefficients;
	}


	public static void main( String[] args ) throws SpimDataException
	{
		final SpimData2 data =
				new XmlIoSpimData2().load(
						URI.create("file:/Users/pietzsch/Desktop/data/Janelia/split_dataset/dataset.split.xml"));

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

		// get all split ViewIds for the set of underlying ViewIds
		final List<ViewId> splitViewIds =
				BlkThinPlateSplineFusion.splitViewIds( underlyingViewIds, BlkThinPlateSplineFusion.old2newSetupId( splitImgLoader.new2oldSetupId() ) );

		System.out.println( "Split viewIds: " + splitViewIds.size() );

		final double downsampling = Double.NaN;
		final double anisotropyFactor = TransformationTools.getAverageAnisotropyFactor( data, underlyingViewIds );

		final HashMap< ViewId, Pair< double[][], double[][] > > coeff =
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

		// use BlockSupplier
		final double[] downsamplingFactors = {8,8,8};
		final TPSMaxFusionBlockSupplier tpsSupplier = new TPSMaxFusionBlockSupplier( boundingBox, downsamplingFactors, coeff, underlyingImgLoader );

		CachedCellImg<FloatType, ?> fused =
				BlockAlgoUtils.cellImg( tpsSupplier, boundingBox.dimensionsAsLongArray(), new int[] { 256, 256, 1 } );

		if( writeDontShow )
		{
			ImagePlus imp = ImageJFunctions.wrap(fused, "fused", Executors.newFixedThreadPool( 8 ));
			IJ.save(imp, "/Users/pietzsch/Desktop/TpsDfieldFusion_8_v03.tif");
			System.out.println("done");
		}
		else
			ImageJFunctions.show( fused, Executors.newFixedThreadPool( 8 ) );
	}

	private static class TPSMaxFusionBlockSupplier extends AbstractBlockSupplier< FloatType >
	{
		final BoundingBox boundingBox;
		final BasicImgLoader imgLoader;

		final HashMap< ViewId, Pair< double[][], double[][] > > coeff;
		final HashMap< ViewId, BlockSupplier<FloatType> > transformed;
		final HashMap< ViewId, Interval > transformedIntervals;

		private TPSMaxFusionBlockSupplier(TPSMaxFusionBlockSupplier supplier)
		{
			this.boundingBox = supplier.boundingBox;
			this.coeff = supplier.coeff;
			this.imgLoader = supplier.imgLoader;
			this.transformedIntervals = supplier.transformedIntervals;
			this.transformed = new HashMap<>();
			supplier.transformed.forEach( ( viewId, blockSupplier ) -> this.transformed.put( viewId, blockSupplier.independentCopy() ) );
		}

		public TPSMaxFusionBlockSupplier(
				final BoundingBox boundingBox,
				final double[] downsamplingFactors,
				final HashMap< ViewId, Pair< double[][], double[][] > > coeff,
				final BasicImgLoader imgLoader )
		{
			this.boundingBox = boundingBox;
			this.coeff = coeff;
			this.imgLoader = imgLoader;
			this.transformed = new HashMap<>();
			this.transformedIntervals = new HashMap<>();

			this.coeff.forEach( ( v,c ) -> {

				// from rendered to original
				final ThinplateSplineTransform transform = new ThinplateSplineTransform(
						// we go from output to input
						c.getB(),
						c.getA() );

				// dimensions of the viewId in original space
				final Dimensions viewIdBoundingBox = getDimensions( imgLoader, v );
				final FinalInterval origInterval = new FinalInterval( viewIdBoundingBox );

				final SampleTPS sampledTPS = SampleTPS.sample( transform, origInterval, downsamplingFactors );
				final AffineTransform3D transformFromSource = sampledTPS.transformFromSource;
				final DisplacementField< DoubleType > dfield = sampledTPS.dfield;
				transformedIntervals.put( v, sampledTPS.transformedInterval );

				final RandomAccessibleInterval img = imgLoader.getSetupImgLoader( v.getViewSetupId() ).getImage( v.getTimePointId() );

				final BlockSupplier< FloatType > blocks = BlockSupplier
						.of( img.view().extend(zero()) )
						.andThen(Convert.convert(new FloatType()))
						.andThen( displacementFieldAffine( transformFromSource, dfield, Transform.Interpolation.NLINEAR ) );

				transformed.put(v, blocks);
			});

		}

		@Override
		public void copy( final Interval interval, final Object dest )
		{
			final BlockInterval blockInterval =
					BlockInterval.asBlockInterval(
							Intervals.translate( interval, boundingBox.minAsLongArray() ) );

			final int[] size = blockInterval.size();
			final int len = safeInt( Intervals.numElements( size ) );
			final float[] fdest = Cast.unchecked( dest );

			transformed.forEach( (v,blocks) -> {

				final Interval transformedInterval = transformedIntervals.get( v );
				if ( !Intervals.isEmpty( Intervals.intersect( transformedInterval, blockInterval ) ) )
				{
					final ArrayImg<FloatType, ?> img = BlockAlgoUtils.arrayImg( blocks, blockInterval );
					final ArrayCursor<FloatType> c = img.cursor();
					for ( int x = 0; x < len; ++x )
						fdest[ x ] = Math.max( c.next().getRealFloat(), fdest[ x ] );
				}

			});
		}

		@Override
		public BlockSupplier<FloatType> independentCopy() { return new TPSMaxFusionBlockSupplier(this); }

		private static final FloatType type = new FloatType();

		@Override
		public FloatType getType() { return type; }

		@Override
		public int numDimensions() { return 3; }
	}

	private static Dimensions getDimensions( final BasicImgLoader imgLoader, final ViewId viewId )
	{
		final int setup = viewId.getViewSetupId();
		final int timepoint = viewId.getTimePointId();
		if ( imgLoader instanceof ImgLoader )
			return ( ( ImgLoader ) imgLoader ).getSetupImgLoader( setup ).getImageSize( timepoint );
		else
			return new FinalDimensions( imgLoader.getSetupImgLoader( setup ).getImage( timepoint ) );
	}
}