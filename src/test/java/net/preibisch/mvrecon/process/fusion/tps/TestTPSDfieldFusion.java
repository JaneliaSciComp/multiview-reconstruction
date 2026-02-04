package net.preibisch.mvrecon.process.fusion.tps;

import static net.imglib2.algorithm.blocks.dfield.DisplacementFieldTransform.displacementFieldAffine;
import static net.imglib2.util.Util.safeInt;
import static net.imglib2.view.fluent.RandomAccessibleIntervalView.Extension.zero;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import bdv.ViewerImgLoader;
import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.Dimensions;
import net.imglib2.FinalInterval;
import net.imglib2.FinalRealInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealInterval;
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
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.DoubleArray;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.iterator.IntervalIterator;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.DisplacementFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.RealTransformRealRandomAccessible;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.realtransform.inverse.WrappedIterativeInvertibleRealTransform;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Cast;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.ValuePair;
import net.imglib2.view.Views;
import net.imglib2.view.fluent.RandomAccessibleIntervalView.Extension;
import net.imglib2.view.fluent.RandomAccessibleView.Interpolation;
import net.imglib2.view.fluent.RealRandomAccessibleView;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.fusion.blk.BlkThinPlateSplineFusion;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointregistration.TransformationTools;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.splitting.SplittingTools;

/**
 * This needs a minimal grid size of 2x2x2, otherwise we get 'funny' transformations
 */
public class TestTPSDfieldFusion
{
	
	static boolean writeDontShow = true;
	
	public static HashMap< ViewId, Pair< double[][], double[][] > > getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final Collection< ViewId > underlyingViewsToProcess,
			final double anisotropyFactor,
			final double downsampling )
	{
		final HashMap<Integer, Integer> new2oldSetupId = splitImgLoader.new2oldSetupId();
		final HashMap<Integer, List<Integer>> old2newSetupId = BlkThinPlateSplineFusion.old2newSetupId( new2oldSetupId );

		final HashMap< ViewId, Pair< double[][], double[][] > > underlyingViewId2TPSCoefficients = new HashMap<>();

		for ( final ViewId underlyingViewId : underlyingViewsToProcess )
		{
			System.out.println( "\nProcessing underlyingViewId: " + Group.pvid( underlyingViewId ) + ", which was split into " + old2newSetupId.get( underlyingViewId.getViewSetupId() ).size() + " pieces." );

			final Pair<double[][], double[][]> coeff =
					BlkThinPlateSplineFusion.getCoefficients(splitImgLoader, old2newSetupId, splitRegMap, underlyingViewId, anisotropyFactor, downsampling);

			underlyingViewId2TPSCoefficients.put( underlyingViewId, coeff );

			System.out.println( "source: " + Arrays.deepToString( coeff.getA() ) );
			System.out.println( "target: " + Arrays.deepToString( coeff.getB() ) );
		}

		return underlyingViewId2TPSCoefficients;
	}


	public static void main( String[] args ) throws SpimDataException
	{
		final SpimData2 data = 
				new XmlIoSpimData2().load(
						URI.create("file:/home/john/data/bigstitcher/split_dataset/dataset.split.xml"));

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

		HashMap<ViewId, Dimensions> idToDimensions = boundingBoxMaximal.dimensions;

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

		// show a single first image transformed
		/*
		final ThinplateSplineTransform transform = new ThinplateSplineTransform(
				coeff.get( new ViewId( 0, 0 )).getB(),
				coeff.get( new ViewId( 0, 0 )).getA() );

		final RandomAccessibleInterval img = underlyingImgLoader.getSetupImgLoader( 0 ).getImage( 0 );
		final RealRandomAccessibleView interp = 
				img.view().extend(Extension.zero()).interpolate(Interpolation.clampingNLinear());
		final RandomAccessibleInterval< UnsignedByteType > tformedImg =
				new RealTransformRealRandomAccessible<>(interp, transform).realView().raster().interval(boundingBox);
		ImageJFunctions.show( img );
		ImageJFunctions.show(tformedImg);
		*/

		// use BlockSupplier
		final TPSMaxFusionBlockSupplier tpsSupplier = new TPSMaxFusionBlockSupplier( boundingBox, coeff, idToDimensions, underlyingImgLoader );

		CachedCellImg<FloatType, ?> fused =
				BlockAlgoUtils.cellImg( tpsSupplier, boundingBox.dimensionsAsLongArray(), new int[] { 256, 256, 1 } );

		if( writeDontShow )
		{
			ImagePlus imp = ImageJFunctions.wrap(fused, "fused", Executors.newFixedThreadPool( 8 ));
			IJ.save(imp, "TpsDfieldFusion.tif");
			System.out.println("done");
		}
		else
			ImageJFunctions.show( fused, Executors.newFixedThreadPool( 8 ) );
	}

	private static class TPSMaxFusionBlockSupplier extends AbstractBlockSupplier< FloatType >
	{
		final BoundingBox boundingBox;
		final HashMap< ViewId, Pair< double[][], double[][] > > coeff;
		final BasicImgLoader imgLoader;

		final HashMap< ViewId, BlockSupplier<FloatType> > transformed;
		private HashMap<ViewId, Dimensions> idToDimensions;

		public TPSMaxFusionBlockSupplier(
				final BoundingBox boundingBox,
				final HashMap< ViewId, Pair< double[][], double[][] > > coeff,
				final HashMap< ViewId, Dimensions > idToDimensions,
				final BasicImgLoader imgLoader )
		{
			this.boundingBox = boundingBox;
			this.coeff = coeff;
			this.idToDimensions = idToDimensions;
			this.imgLoader = imgLoader;
			this.transformed = new HashMap<>();

			final double[] spacing = {8,8,8}; // TODO make a parameter
	
			this.coeff.forEach( ( v,c ) -> {
				
				// from rendered to original
				final ThinplateSplineTransform transform = new ThinplateSplineTransform(
						// we go from output to input
						c.getB(),
						c.getA() );
				
				// from original to rendered
				RealTransform invTransform = new WrappedIterativeInvertibleRealTransform<>(transform).inverse();
				
				// dimensions of the viewId in original space
				final Dimensions viewIdBoundingBox = idToDimensions.get(v);
				final FinalInterval origInterval = new FinalInterval( viewIdBoundingBox);
				
				// we need rendered space
				final Interval transformedInterval = corners(invTransform, origInterval);

				
				// smaller interval over which to rasterize the TPS
				Interval downsampledTransformInterval = downsample(transformedInterval, spacing);
				
				System.out.println( "view id itvl: " + origInterval);
				System.out.println( "transformed itvl: " + transformedInterval);
				System.out.println( "down transformed itvl: " + downsampledTransformInterval);

				// estimate the bounding box of the TPS
				// then downsample the resulting interval

				double[] offset = transformedInterval.minAsDoubleArray(); // use this when creating displacement field?
				final RandomAccessibleInterval<DoubleType> dfieldV = DisplacementFieldTransform.createDisplacementField(
						transform, downsampledTransformInterval, spacing, offset);

				final ArrayImg<DoubleType, DoubleArray> dfieldImg = ArrayImgs.doubles(dfieldV.dimensionsAsLongArray());
				for (int i = 0; i < viewIdBoundingBox.numDimensions(); i++) {
					final double f = spacing[i];
					LoopBuilder.setImages(dfieldV.view().slice(0, i), dfieldImg.view().slice(0, i))
							.forEachPixel((x, y) -> y.set(x.get() / f));
				}

				final RandomAccessibleInterval img = imgLoader.getSetupImgLoader( v.getViewSetupId() ).getImage( v.getTimePointId() );
	
				final Scale3D transformFromSource = new Scale3D(spacing);
				final DisplacementField< DoubleType > dfield = new DisplacementField<>(
						BlockSupplier.of( dfieldImg ), spacing, new double[] { 0, 0, 0 } );

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

			transformed.forEach( (v,blocks) -> {

				final ArrayImg<FloatType, ?> img = BlockAlgoUtils.arrayImg( blocks, blockInterval );
				final ArrayCursor<FloatType> c = img.cursor();
				final float[] fdest = Cast.unchecked( dest );

				for ( int x = 0; x < len; ++x )
					fdest[ x ] = Math.max( c.next().getRealFloat(), fdest[ x ] );
			});
		}

		@Override
		public BlockSupplier<FloatType> independentCopy() { return new TPSMaxFusionBlockSupplier( boundingBox, coeff, idToDimensions, imgLoader); }

		private static final FloatType type = new FloatType();

		@Override
		public FloatType getType() { return type; }

		@Override
		public int numDimensions() { return 3; }
	}
	
	public static Interval corners( RealTransform xfm, Interval interval )
	{
		if( xfm == null )
			return new FinalInterval( interval );

		int nd = interval.numDimensions();
		double[] pt = new double[ nd ];
		double[] ptxfm = new double[ nd ];

		long[] min = new long[ nd ];
		long[] max = new long[ nd ];
		Arrays.fill( min, Long.MAX_VALUE );
		Arrays.fill( max, Long.MIN_VALUE );

		long[] unitInterval = new long[ nd ];
		Arrays.fill( unitInterval, 2 );

		IntervalIterator it = new IntervalIterator( unitInterval );
		while( it.hasNext() )
		{
			it.fwd();
			for( int d = 0; d < nd; d++ )
			{
				if( it.getLongPosition( d ) == 0 )
					pt[ d ] = interval.realMin( d );
				else
					pt[ d ] = interval.realMax( d );
			}

			xfm.apply( pt, ptxfm );

			for( int d = 0; d < nd; d++ )
			{
				long lo = (long)Math.floor( ptxfm[d] );
				long hi = (long)Math.ceil( ptxfm[d] );

				if( lo < min[ d ])
					min[ d ] = lo;

				if( hi > max[ d ])
					max[ d ] = hi;
			}
		}
		return new FinalInterval( min, max );
	}
	
	
	public static Interval downsample(final Interval itvl, final double[] downsample ) {

		final long[] dims = IntStream.of(0, 1, 2).mapToLong( i -> {
			return (long)Math.ceil(itvl.dimension(i) / downsample[i]);
		}).toArray();

		return new FinalInterval(dims);
	}


}