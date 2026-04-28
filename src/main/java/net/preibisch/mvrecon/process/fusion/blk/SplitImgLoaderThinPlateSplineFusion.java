package net.preibisch.mvrecon.process.fusion.blk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import bdv.ViewerImgLoader;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.registration.ViewRegistration;
import mpicbg.spim.data.registration.ViewTransform;
import mpicbg.spim.data.sequence.SequenceDescription;
import mpicbg.spim.data.sequence.ViewDescription;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.Interval;
import net.imglib2.algorithm.blocks.BlockSupplier;
import net.imglib2.converter.Converter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.preibisch.mvrecon.fiji.plugin.fusion.FusionGUI;
import net.preibisch.mvrecon.fiji.spimdata.SpimData2;
import net.preibisch.mvrecon.fiji.spimdata.imgloaders.splitting.SplitViewerImgLoader;
import net.preibisch.mvrecon.process.fusion.tps.Landmarks;
import net.preibisch.mvrecon.process.fusion.intensity.Coefficients;
import net.preibisch.mvrecon.process.fusion.transformed.TransformVirtual;
import net.preibisch.mvrecon.process.interestpointregistration.pairwise.constellation.grouping.Group;
import net.preibisch.mvrecon.process.splitting.SplittingTools;

public class SplitImgLoaderThinPlateSplineFusion
{
	public static < T extends RealType< T > & NativeType< T > > BlockSupplier< T > init(
			final Converter< FloatType, T > converter,
			final SplitViewerImgLoader splitImgLoader,
			final Collection< ? extends ViewId > splitViewIdsInput,
			final Map< ViewId, ViewRegistration > splitViewRegistrations, // already adjusted for anisotropy
			final Map< ViewId, ? extends BasicViewDescription< ? > > splitViewDescriptions,
			final FusionGUI.FusionType fusionType,
			final int intervalExpansion,
			final double anisotropyFactor,
			final int interpolationMethod,
			final Map< Integer, Integer > fusionMap, // old setupId > new setupId for fusion order, only makes sense with FusionType.FIRST_LOW or FusionType.FIRST_HIGH
			final Map< ViewId, Coefficients > intensityAdjustmentCoefficients, // from underlying viewids
			final Interval fusionInterval,  // already adjusted for anisotropy???
			final T type,
			final int[] blockSize )
	{
		// assemble all underlying viewIds (which will expand the list of splitViews to all the underlying viewids consist of)
		final List< ViewId > underlyingViewIds = underlyingViewIds( splitViewIdsInput, splitImgLoader.new2oldSetupId() );
		final Map<Integer, List<Integer>> old2newSetupId = old2newSetupId( splitImgLoader.new2oldSetupId() );
		final List< ViewId > splitViewIds = splitViewIds( underlyingViewIds, old2newSetupId );

		// TODO: do something more like this:
		//  // go through the views and check if they are all 2-dimensional
		//     final boolean is2d = is2d( viewIds, viewDescriptions );
		//  and reuse is2d for Overlap numDimensions (see BlkAffineFusion)
		if ( BlkAffineFusion.is2d( splitViewIds, splitViewDescriptions ) )
			throw new UnsupportedOperationException( "BlkThinPlateSplineFusion: 2D fusion not supported." );

		final ViewerImgLoader underlyingImgLoader = splitImgLoader.getUnderlyingImgLoader();
		final SequenceDescription underlyingSD = splitImgLoader.underlyingSequenceDescription();
		final Map< ViewId, ViewDescription > underlyingViewDescription = underlyingSD.getViewDescriptions();

		// the coefficients (source > target) for each underlying view, used to construct the TPS
		final Map< ViewId, Landmarks > underlyingViewLandmarks = new HashMap<>();
		for ( final ViewId underlyingViewId : underlyingViewIds )
		{
			// ignore downsampling for now
			final Landmarks landmarks = getCoefficients( splitImgLoader, old2newSetupId, splitViewRegistrations, underlyingViewId, Double.NaN, Double.NaN );
			underlyingViewLandmarks.put( underlyingViewId, landmarks );

			// TODO: we may want to put extra coefficients in the overlapping areas based on "real correspondences"
		}

		final Comparator< ViewId > fusionOrder = Comparator.comparingInt( c -> fusionMap.get( c.getViewSetupId() ) );

		return BlkThinPlateSplineFusion.init(
				converter,
				underlyingImgLoader,
				underlyingViewIds,
				underlyingViewDescription,
				underlyingViewLandmarks,
				fusionType,
				anisotropyFactor,
				interpolationMethod,
				fusionOrder,
				intensityAdjustmentCoefficients,
				fusionInterval,
				type,
				blockSize );
	}

	public static List<ViewId> underlyingViewIds( final Collection< ? extends ViewId > splitViewIds, final Map< Integer, Integer > new2oldSetupId )
	{
		return splitViewIds.stream()
				.map( splitViewId ->
					new ViewId( splitViewId.getTimePointId(), new2oldSetupId.get( splitViewId.getViewSetupId() ) ) )
				.distinct()
				.collect( Collectors.toList());
	}

	public static Map<Integer, List<Integer>> old2newSetupId( final Map<Integer, Integer> new2oldSetupId )
	{
		final Map< Integer, List< Integer > > old2newSetupId = new HashMap<>();

		new2oldSetupId.forEach( ( k, v ) -> old2newSetupId.computeIfAbsent( v, newKey -> new ArrayList<>() ).add( k ) );

		return old2newSetupId;
	}

	public static List<ViewId> splitViewIds( final Collection< ? extends ViewId > underlyingViewIds, final Map< Integer, List<Integer>> old2newSetupId )
	{
		return underlyingViewIds.stream().flatMap( underlyingViewId ->
			old2newSetupId.get( underlyingViewId.getViewSetupId() ).stream().map( splitSetupId ->
				new ViewId( underlyingViewId.getTimePointId(), splitSetupId ) )
		).collect( Collectors.toList());
	}

	public static ViewerImgLoader getUnderlyingImageLoader( final SpimData2 data )
	{
		if ( SplitViewerImgLoader.class.isInstance( data.getSequenceDescription().getImgLoader() ) )
			return ( ( SplitViewerImgLoader ) data.getSequenceDescription().getImgLoader() ).getUnderlyingImgLoader();
		else
			return null;
	}

	public static Landmarks getCoefficients(
			final SplitViewerImgLoader splitImgLoader,
			final Map<Integer, List<Integer>> old2newSetupId,
			final Map<ViewId, ViewRegistration> splitRegMap,
			final ViewId underlyingViewId,
			final double anisotropyFactor,
			final double downsampling )
	{
		final List<Integer> splitSetupIds = old2newSetupId.get( underlyingViewId.getViewSetupId() );

		final double[][] source = new double[3][splitSetupIds.size()];
		final double[][] target = new double[3][splitSetupIds.size()];

		for ( int i = 0; i < splitSetupIds.size(); ++i )
		{
			final int splitViewSetupId = splitSetupIds.get( i );
			final ViewId splitViewId = new ViewId( underlyingViewId.getTimePointId(), splitViewSetupId );

			//System.out.println( "\tProcessing splitViewId: " + Group.pvid( splitViewId ) + ":" );

			final ViewRegistration vr = splitRegMap.get( splitViewId );
			final List< ViewTransform > vrList = vr.getTransformList();

			// just making sure this is the split transform
			if ( !vrList.get( vrList.size() - 1).getName().equals( SplittingTools.IMAGE_SPLITTING_NAME) )
				throw new RuntimeException( "First transformation is not " + SplittingTools.IMAGE_SPLITTING_NAME + " for " + Group.pvid( splitViewId ) + ", stopping." );

			// this transformation puts the Zero-Min View of the underlying image where it actually is
			final ViewTransform splitTransform = vrList.get( vrList.size() - 1);
			//System.out.println( "\t" + SplittingTools.IMAGE_SPLITTING_NAME + " transformation: " + splitTransform );

			// get the remaining model
			vr.updateModel();
			final AffineTransform3D model = vr.getModel().copy();

			// preserve anisotropy
			if ( !Double.isNaN( anisotropyFactor ) )
				TransformVirtual.scaleTransform( model, new double[] { 1.0, 1.0, 1.0/anisotropyFactor } );

			// downsampling
			if ( !Double.isNaN( downsampling ) )
				TransformVirtual.scaleTransform( model, 1.0 / downsampling );

			// create a point in the middle of the Zero-Min View and the corresponding point in the global output space
			final Interval splitInterval = splitImgLoader.newSetupId2Interval().get( splitViewSetupId );

			final double[] p = new double[] { splitInterval.dimension( 0 ) / 2.0, splitInterval.dimension( 1 ) / 2.0, splitInterval.dimension( 2 ) / 2.0 };
			final double[] q = new double[ p.length ];
			model.apply( p, q );

			for ( int d = 0; d < p.length; ++d )
			{
				p[ d ] += splitTransform.asAffine3D().get(d, 3); // add the translation offsets of each split view
				source[ d ][ i ] = p[ d ];
				target[ d ][ i ] = q[ d ];
			}

			//System.out.println( "\tCenter point: " + Arrays.toString( p ) + " maps into global output space to: " + Arrays.toString( q ) );
		}

		return new Landmarks( source, target );
	}
}
