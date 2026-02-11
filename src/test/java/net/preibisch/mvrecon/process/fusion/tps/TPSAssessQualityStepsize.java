package net.preibisch.mvrecon.process.fusion.tps;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import ij.ImageJ;
import mpicbg.spim.data.SpimDataException;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.DisplacementFieldTransform;
import net.imglib2.realtransform.RealTransform;
import net.imglib2.realtransform.ThinplateSplineTransform;
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.imglib2.view.composite.Composite;
import net.imglib2.view.composite.GenericComposite;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.XmlIoSpimData2;
import net.preibisch.mvrecon.fiji.spimdata.boundingbox.BoundingBox;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.boundingbox.BoundingBoxMaximal;
import net.preibisch.mvrecon.process.fusion.blk.BlkThinPlateSplineFusion;

public class TPSAssessQualityStepsize
{
	public static void visualize(
			final Interval boundingBox,
			final double[][] source,
			final double[][] target,
			final long[] stepSize )
	{
		visualize(null, boundingBox, source, target, stepSize);
	}

	public static void visualize(
			final Interval sourceImageInterval,
			final Interval boundingBox,
			final double[][] source,
			final double[][] target,
			final long[] stepSize )
	{
		final ThinplateSplineTransform transform = new ThinplateSplineTransform( target, source ); // we go from output to input

		RandomAccessibleInterval<DoubleType> interpField =
				BlkThinPlateSplineFusion.interpolatedField( transform, boundingBox, stepSize );

		final RandomAccessibleInterval<DoubleType> fullField =
				fullDeformationField( transform, boundingBox );

		interpolateTransform( transform, boundingBox );

		if ( sourceImageInterval != null )
		{
			final double[] loc = new double[ 3 ];

			for ( final GenericComposite<DoubleType> gct : Views.collapse( fullField ) )
			{
				loc[ 0 ] = gct.get( 0 ).get();
				loc[ 1 ] = gct.get( 1 ).get();
				loc[ 2 ] = gct.get( 2 ).get();

				if ( !BlkThinPlateSplineFusion.contains3d( sourceImageInterval, loc ) )
				{
					gct.get( 0 ).set( 0 );
					gct.get( 1 ).set( 0 );
					gct.get( 2 ).set( 0 );
				}
			}

			final RandomAccessibleInterval<DoubleType> interpFieldCopy =
					Views.translate( ArrayImgs.doubles( interpField.dimensionsAsLongArray() ), interpField.minAsLongArray() );

			final Cursor< ? extends Composite< DoubleType > > cIn = Views.flatIterable( Views.collapse( interpField ) ).cursor();
			final Cursor< ? extends Composite< DoubleType > > cOut = Views.flatIterable( Views.collapse( interpFieldCopy ) ).cursor();

			while ( cIn.hasNext() )
			{
				final Composite<DoubleType> in = cIn.next();
				final Composite<DoubleType> out = cOut.next();

				loc[ 0 ] = in.get( 0 ).get();
				loc[ 1 ] = in.get( 1 ).get();
				loc[ 2 ] = in.get( 2 ).get();

				if ( BlkThinPlateSplineFusion.contains3d( sourceImageInterval, loc ) )
				{
					out.get( 0 ).set( loc[ 0 ] );
					out.get( 1 ).set( loc[ 1 ] );
					out.get( 2 ).set( loc[ 2 ] );
				}
			}

			interpField = interpFieldCopy;
		}

		ImageJFunctions.show( interpField ).setTitle( "interpolated_"+sourceImageInterval );
		ImageJFunctions.show( fullField ).setTitle( "full_"+sourceImageInterval );
	}

	public static DisplacementFieldTransform interpolateTransform(
			final ThinplateSplineTransform transform,
			final Interval blockInterval )
	{
		System.out.println( Util.printInterval( blockInterval ) );
		final RandomAccessibleInterval<DoubleType> df =
				DisplacementFieldTransform.createDisplacementField( transform, blockInterval, new double[] { 10, 10, 10 } );

		System.out.println( Util.printInterval( df ) );

		final RandomAccessibleInterval<DoubleType> copy =
				Views.translate( ArrayImgs.doubles( df.dimensionsAsLongArray() ), df.minAsLongArray() );

		System.out.println( Util.printInterval( copy ) );

		//SimpleMultiThreading.threadHaltUnClean();
		final Cursor<DoubleType> c = Views.flatIterable( df ).cursor();

		copy.forEach( t -> t.set( c.next() ));

		ImageJFunctions.show( copy );

		final DisplacementFieldTransform t =
				new DisplacementFieldTransform(
						df//, //Views.interval( Views.extendBorder( copy ), blockInterval ),
						);//new double[] { 10, 10, 10 } );

		return t;
	}

	public static RandomAccessibleInterval<DoubleType> fullDeformationField(
			final RealTransform transform,
			final Interval blockInterval )
	{
		final Cursor<Localizable> cursor = Views.flatIterable( Intervals.positions( blockInterval ) ).cursor();

		final RandomAccessibleInterval<DoubleType> xFull = Views.translate( ArrayImgs.doubles( blockInterval.dimensionsAsLongArray() ), blockInterval.minAsLongArray() );
		final RandomAccessibleInterval<DoubleType> yFull = Views.translate( ArrayImgs.doubles( blockInterval.dimensionsAsLongArray() ), blockInterval.minAsLongArray() );
		final RandomAccessibleInterval<DoubleType> zFull = Views.translate( ArrayImgs.doubles( blockInterval.dimensionsAsLongArray() ), blockInterval.minAsLongArray() );

		final double[] loc = new double[ 3 ];

		while ( cursor.hasNext() )
		{
			cursor.next().localize( loc );
			transform.apply( loc, loc );

			xFull.getAt( cursor ).set( loc[ 0 ] );
			yFull.getAt( cursor ).set( loc[ 1 ] );
			zFull.getAt( cursor ).set( loc[ 2 ] );
		}

		return Views.stack( xFull, yFull, zFull );
	}

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

		final HashMap<Integer, List<Integer>> old2newSetupId = BlkThinPlateSplineFusion.old2newSetupId( splitImgLoader.new2oldSetupId() );
		final List< ViewId > splitViewIds = BlkThinPlateSplineFusion.splitViewIds( underlyingViewIds, old2newSetupId );

		// we estimate the bounding box using the split imagel loader, which will be closer to real bounding box
		BoundingBox boundingBox = new BoundingBoxMaximal( splitViewIds, data ).estimate( "Full Bounding Box" );
		System.out.println( boundingBox );

		final HashMap< ViewId, Pair< double[][], double[][] > > coeff =
				TestTPSFusion.getCoefficients( splitImgLoader, data.getViewRegistrations().getViewRegistrations(), underlyingViewIds, Double.NaN, Double.NaN );

		new ImageJ();
		final ViewId v = new ViewId( 0, 0 );

		final Interval slice = new FinalInterval(
				new long[] { boundingBox.min( 0 ), boundingBox.min( 1 ), (boundingBox.min( 2 )+ boundingBox.max( 2 ))/2 },
				new long[] { boundingBox.max( 0 ), boundingBox.max( 1 ), (boundingBox.min( 2 )+ boundingBox.max( 2 ))/2 } );

		visualize( slice, coeff.get( v ).getA(), coeff.get( v ).getB(), new long[] { 10, 10, 10 } );
		/*visualize(
				new FinalInterval( underlyingSD.getViewDescription( v ).getViewSetup().getSize() ),
				slice, coeff.get( v ).getA(), coeff.get( v ).getB(), new long[] { 10, 10, 10 } );*/
	}
}
