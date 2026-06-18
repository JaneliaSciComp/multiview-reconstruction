/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2025 Multiview Reconstruction developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.mvrecon.fiji.spimdata.explorer.bdv;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.stream.JsonReader;

import bdv.cache.CacheControl;
import bdv.viewer.ViewerPanel;
import bdv.viewer.ViewerState;
import bdv.viewer.overlay.MultiBoxOverlayRenderer;
import bdv.viewer.overlay.ScaleBarOverlayRenderer;
import bdv.viewer.render.MultiResolutionRenderer;
import bdv.viewer.render.RenderTarget;
import bdv.viewer.render.awt.BufferedImageRenderResult;
import fiji.util.gui.GenericDialogPlus;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.ColorProcessor;
import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.algorithm.gauss3.Gauss3;
import net.imglib2.display.screenimage.awt.ARGBScreenImage;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.list.ListImg;
import net.imglib2.img.list.ListLocalizingCursor;
import net.imglib2.outofbounds.OutOfBoundsBorderFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.fusion.transformed.nonrigid.grid.NumericAffineTransform3D;

public class BDVFlyThrough
{
	final public static ArrayList< AffineTransform3D > viewerTransforms = new ArrayList<>();
	public static boolean skipDialog = false;
	public static String defaultPath = "";
	public static int interpolateSteps = 100;
	public static double defaultSigma = 0;
	public static boolean goBackToInitialTransform = true;
	public static String defaultJSON = new File( "transforms.json" ).getAbsolutePath();

	public static String[] interpolationMethods = new String[] { "Linear",  "Linear with Smoothing", "Cubic Spline" };
	public static int defaultMethod = 1;

	public static boolean defaultScalebar = false;
	public static boolean defaultBoxes = false;
	public static int defaultWidth = 0;

	public static void addCurrentViewerTransform( final ViewerPanel viewer )
	{
		AffineTransform3D currentViewerTransform = new AffineTransform3D();
		viewer.state().getViewerTransform( currentViewerTransform );
		viewerTransforms.add( currentViewerTransform );
		IOFunctions.println( "Added transform: " + currentViewerTransform  + ", #transforms=" + viewerTransforms.size() );
	}

	public static void deleteLastViewerTransform()
	{
		if ( viewerTransforms.size() > 0 )
		{
			viewerTransforms.remove( viewerTransforms.size() - 1 );
			IOFunctions.println( "removed last transform, #transforms=" + viewerTransforms.size() );
		}
	}

	public static void jumpToLastViewerTransform( final ViewerPanel viewer )
	{
		if ( viewerTransforms.size() > 0 )
		{
			viewer.state().setViewerTransform( viewerTransforms.get( viewerTransforms.size() - 1 ) );
			IOFunctions.println( "Jumped to transform " + viewerTransforms.get( viewerTransforms.size() - 1 )  + ", #transforms=" + viewerTransforms.size() );
		}
	}

	public static File getJSONFile()
	{
		final GenericDialogPlus gd = new GenericDialogPlus( "Select JSON file" );
		gd.addFileField( "JSON file", defaultJSON );

		gd.showDialog();

		if ( gd.wasCanceled() )
			return null;
		else
			return new File( defaultJSON = gd.getNextString() );
	}

	public static void loadViewerTransforms() throws IOException
	{
		final File file = getJSONFile();

		if ( file == null )
			return;
		else
			loadViewerTransforms(file);
	}

	public static void loadViewerTransforms( final File file ) throws FileNotFoundException
	{
		final GsonBuilder gsonBuilder = new GsonBuilder().
				registerTypeAdapter(
						AffineTransform3D.class,
						new AffineTransform3DAdapter());
		final Gson gson = gsonBuilder.create();

		final JsonReader reader = new JsonReader( new FileReader( file ) );

		List< AffineTransform3D > transforms = Arrays.asList( gson.fromJson( reader, AffineTransform3D[].class ) );

		IOFunctions.println( "loaded " + transforms.size() + " transforms." );

		/*
		IOFunctions.println( "scaling " + transforms.size() + " transforms." );

		Scale3D scale = new Scale3D( 2, 2, 2 );
		for ( int i = 0; i < transforms.size(); ++i )
			transforms.set(i, transforms.get( i ).preConcatenate( scale ) );
		*/
		viewerTransforms.clear();
		viewerTransforms.addAll( transforms );
	}

	public static void saveViewerTransforms() throws IOException
	{
		final File file = getJSONFile();

		if ( file == null )
			return;
		else
			saveViewerTransforms(file);
	}

	public static void saveViewerTransforms( final File file ) throws IOException
	{
		final GsonBuilder gsonBuilder = new GsonBuilder().
				registerTypeAdapter(
						AffineTransform3D.class,
						new AffineTransform3DAdapter());
		final Gson gson = gsonBuilder.create();

		System.out.println( gson.toJson(viewerTransforms) );
		FileWriter w = new FileWriter(file);
		gson.toJson(viewerTransforms, w );
		w.close();

		IOFunctions.println( "saved " + viewerTransforms.size() + " transforms." );
		//gson.to
		//gson.toJson( gson.toJson(transforms ), writer);
	}

	public static void clearAllViewerTransform()
	{
		viewerTransforms.clear();
		IOFunctions.println( "Cleared all transforms." );
	}

	public static void renderScreenshot( final ViewerPanel viewer )
	{
		final ViewerState renderState = viewer.state();
		final int canvasW = viewer.getDisplay().getWidth();
		final int canvasH = viewer.getDisplay().getHeight();

		int width = canvasW;
		int height = canvasH;

		final GenericDialogPlus gd = new GenericDialogPlus( "Select screenshot size" );

		if ( defaultWidth <= 0 )
			defaultWidth = width;

		gd.addNumericField( "Width (current width=" + width + ", height scaled accordingly)", defaultWidth, 0 );
		gd.addCheckbox( "Show_scalebar", defaultScalebar );
		gd.addCheckbox( "Show_boxes", defaultBoxes );
		
		gd.showDialog();
		if ( gd.wasCanceled())
			return;

		width = defaultWidth = (int)Math.round( gd.getNextNumber() );
		height = (int)Math.round( ( (double)width / (double)canvasW ) * height );
		final boolean showScaleBar = defaultScalebar = gd.getNextBoolean();
		final boolean showBoxes = defaultBoxes = gd.getNextBoolean();

		IOFunctions.println( "Making screenshot with resolution: " + width + "x" + height + ", boxes=" + showBoxes + ", scalebar=" + showScaleBar );

		final AffineTransform3D affine = new AffineTransform3D();
		renderState.getViewerTransform( affine );
		affine.set( affine.get( 0, 3 ) - canvasW / 2, 0, 3 );
		affine.set( affine.get( 1, 3 ) - canvasH / 2, 1, 3 );
		affine.scale( ( double ) width / canvasW );
		affine.set( affine.get( 0, 3 ) + width / 2, 0, 3 );
		affine.set( affine.get( 1, 3 ) + height / 2, 1, 3 );
		renderState.setViewerTransform( affine );

		final MyTarget target = new MyTarget( width, height );

		final MultiResolutionRenderer renderer =
				new MultiResolutionRenderer(
						target,
						() -> {},
						new double[] { 1 },
						0,
						1,
						null,
						false,
						new CacheControl.Dummy() );

		renderer.requestRepaint();
		renderer.paint( renderState );

		renderScalebar( showScaleBar ? new ScaleBarOverlayRenderer() : null, target, renderState, width, height );
		renderBoxes( showBoxes ? new MultiBoxOverlayRenderer( width, height ) : null, target, renderState, width, height );

		new ImagePlus( "BDV Screenshot", new ColorProcessor( target.accumulated.image() ) ).show();
	}

	public static void record( final ViewerPanel viewer )
	{
		if ( viewerTransforms.size() < 2 )
		{
			IOFunctions.println( "At least two transformations are required. Stopping. (Press 'a' while the BigStitcher window is in focus to define them, you will get a confirmation in the Log window)" );
			return;
		}

		final ViewerState renderState = viewer.state();
		final int canvasW = viewer.getDisplay().getWidth();
		final int canvasH = viewer.getDisplay().getHeight();

		int width = canvasW;
		int height = canvasH;

		final ArrayList< AffineTransform3D > viewerTransformsLocal = new ArrayList<>();

		for ( int i = 0; i < viewerTransforms.size(); ++i )
			viewerTransformsLocal.add( viewerTransforms.get( i ).copy() );

		if ( !skipDialog )
		{
			if ( defaultWidth <= 0 )
				defaultWidth = width;

			final GenericDialogPlus gd = new GenericDialogPlus( "Options for movie recording" );
			gd.addDirectoryField( "Movie directory", defaultPath, 45 );
			gd.addNumericField( "Width (current width=" + width + ", height scaled accordingly)", defaultWidth, 0 );
			gd.addNumericField( "Interpolation steps between keypoints", interpolateSteps, 0 );
			gd.addChoice( "Transformation_interpolation method", interpolationMethods, interpolationMethods[ defaultMethod ] );
			gd.addCheckbox( "Go_back to initial transform", goBackToInitialTransform );
			gd.addMessage( "" );
			gd.addCheckbox( "Show_scalebar", defaultScalebar );
			gd.addCheckbox( "Show_boxes", defaultBoxes );

			gd.showDialog();
			if ( gd.wasCanceled())
				return;

			defaultPath = gd.getNextString();
			width = defaultWidth = (int)Math.round( gd.getNextNumber() );
			height = (int)Math.round( ( (double)width / (double)canvasW ) * height );
			interpolateSteps = (int)Math.round( gd.getNextNumber() );
			defaultMethod = gd.getNextChoiceIndex();
			goBackToInitialTransform = gd.getNextBoolean();
			defaultScalebar = gd.getNextBoolean();
			defaultBoxes = gd.getNextBoolean();

			if ( defaultMethod == 1 )
			{
				final GenericDialogPlus gd2 = new GenericDialogPlus( "Smoothing option" );
				gd2.addNumericField( "Sigma", defaultSigma, 1 );

				gd2.showDialog();
				if ( gd2.wasCanceled())
					return;

				defaultSigma = Math.max( 0, gd2.getNextNumber() );
			}

			if ( goBackToInitialTransform )
				viewerTransformsLocal.add( viewerTransformsLocal.get( 0 ).copy() );
		}

		IOFunctions.println( "Recording images for " + viewerTransformsLocal.size() + " transforms, interpolated with " + interpolateSteps + " steps using '" + interpolationMethods[ defaultMethod ] + "' in between to directory " + defaultPath );

		AffineTransform3D affine = new AffineTransform3D();
		renderState.getViewerTransform( affine );
		affine.set( affine.get( 0, 3 ) - canvasW / 2, 0, 3 );
		affine.set( affine.get( 1, 3 ) - canvasH / 2, 1, 3 );
		affine.scale( ( double ) width / canvasW );
		affine.set( affine.get( 0, 3 ) + width / 2, 0, 3 );
		affine.set( affine.get( 1, 3 ) + height / 2, 1, 3 );
		renderState.setViewerTransform( affine );

		final ScaleBarOverlayRenderer scalebar = defaultScalebar ? new ScaleBarOverlayRenderer() : null;
		final MultiBoxOverlayRenderer boxRender = defaultBoxes ? new MultiBoxOverlayRenderer( width, height ) : null;

		final MyTarget target = new MyTarget( width, height );

		final MultiResolutionRenderer renderer =
				new MultiResolutionRenderer(
						target,
						() -> {},
						new double[] { 1 },
						0,
						1,
						null,
						false,
						new CacheControl.Dummy() );

		final ArrayList< AffineTransform3D > transforms = interpolateTransforms( viewerTransformsLocal, defaultMethod == 2, defaultSigma, interpolateSteps );

		IJ.showProgress( 0.0 );

		final File dir = new File( defaultPath, "movie" );

		if ( !dir.exists() )
		{
			IOFunctions.println( "Creating directory: " + dir.getAbsolutePath() );
			dir.mkdirs();
		}

		for ( int i = 0; i < transforms.size(); ++i )
		{
			target.clear();

			IOFunctions.println( (i+1) + "/" + transforms.size() + ": " + transforms.get( i ) );

			affine = transforms.get( i );
			affine.set( affine.get( 0, 3 ) - canvasW / 2, 0, 3 );
			affine.set( affine.get( 1, 3 ) - canvasH / 2, 1, 3 );
			affine.scale( ( double ) width / canvasW );
			affine.set( affine.get( 0, 3 ) + width / 2, 0, 3 );
			affine.set( affine.get( 1, 3 ) + height / 2, 1, 3 );
			renderState.setViewerTransform( affine );

			//renderState.setViewerTransform( transforms.get( i ) );

			renderer.requestRepaint();
			renderer.paint( renderState );

			renderScalebar( scalebar, target, renderState, width, height );
			renderBoxes( boxRender, target, renderState, width, height );

			try
			{
				final File file = new File( String.format( "%s/img-%05d.png", dir, i ) );
				IOFunctions.println( "Writing file: " + file.getAbsolutePath() );

				ImagePlus imp = new ImagePlus( "BDV Screenshot", new ColorProcessor( target.accumulated.image() ) );
				new FileSaver( imp ).saveAsPng( file.getAbsolutePath() );
				//ImageIO.write( target.accumulated.image(), "png", file ); // writes only white images
			}
			catch ( Exception e )
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			IJ.showProgress( (double)(i+1)/(double)transforms.size() );
		}

		IJ.showProgress( 1.0 );

		renderState.setViewerTransform( transforms.get( 0 ) );

		IOFunctions.println( "Done" );
	}

	protected static void renderScalebar( final ScaleBarOverlayRenderer scalebar, final MyTarget target, final ViewerState renderState, final int width, final int height )
	{
		if ( scalebar != null )
		{
			final Graphics2D g2 = target.accumulated.image().createGraphics();
			g2.setClip( 0, 0, width, height );
			scalebar.setViewerState( renderState );
			scalebar.paint( g2 );
		}
	}

	protected static void renderBoxes( final MultiBoxOverlayRenderer boxRender, final MyTarget target, final ViewerState renderState, final int width, final int height )
	{
		if ( boxRender != null )
		{
			final Graphics2D g2 = target.accumulated.image().createGraphics();
			g2.setClip( 0, 0, width, height );
			boxRender.setViewerState( renderState );
			boxRender.paint( g2 );
		}
	}

	public static ArrayList< AffineTransform3D > interpolateTransforms( final ArrayList< AffineTransform3D > steps, final boolean useSpline, final double sigma, final int interpolateSteps )
	{
		if ( steps.size() == 1 )
			return steps;
		else
		{
			final ArrayList< AffineTransform3D > interpolated = new ArrayList<>();

			if ( useSpline )
			{
				final ArrayList< RealPoint > transforms = new ArrayList<>();

				for ( final AffineTransform3D transform : steps )
					transforms.add( new RealPoint( transform.getRowPackedCopy() ) );

				final MonotoneCubicSpline spline = MonotoneCubicSpline.createMonotoneCubicSpline( transforms );

				final RealPoint p = new RealPoint( transforms.get( 0 ).numDimensions() );
				final double[] matrix = new double[ p.numDimensions() ];

				for ( int i = 0; i < transforms.size() - 1; ++i )
					for ( int f = 0; f < interpolateSteps; ++f )
					{
						final double x = i + (double)f / (double)interpolateSteps;

						spline.interpolate( x, p );

						for ( int d = 0; d < matrix.length; ++d )
							matrix[ d ] = p.getDoublePosition( d );

						final AffineTransform3D interp = new AffineTransform3D();
						interp.set( matrix );
						interpolated.add( interp );
					}
			}
			else
			{
				for ( int i = 0; i < steps.size() - 1; ++i )
				{
					final AffineTransform3D first = steps.get( i );
					final AffineTransform3D second = steps.get( i + 1 );
	
					final double[] a = first.getRowPackedCopy();
					final double[] b = second.getRowPackedCopy();
					
					for ( int j = 0; j <= interpolateSteps; ++j )
					{
						final double ratioA = 1.0 - (double)j/(double)interpolateSteps;
						final double ratioB = (double)j/(double)interpolateSteps;
	
						final double[] c = new double[ a.length ];
	
						for ( int k = 0; k < a.length; ++k )
							c[ k ] = a[ k ] * ratioA + b[ k ] * ratioB;
	
						final AffineTransform3D interp = new AffineTransform3D();
						interp.set( c );
						interpolated.add( interp );
					}
				}
			}

			if ( sigma > 0 )
			{
				IOFunctions.println( "Smoothing " + interpolated.size() + " transforms with sigma=" + sigma );

				final ListImg< NumericAffineTransform3D > transformImg = new ListImg< NumericAffineTransform3D >( new long[]{ interpolated.size() }, new NumericAffineTransform3D( new AffineTransform3D() ) );
				final ListLocalizingCursor< NumericAffineTransform3D > it = transformImg.localizingCursor();

				for ( final AffineTransform3D model : interpolated )
				{
					it.fwd();
					it.set( new NumericAffineTransform3D( model.copy() ) );
				}

				RandomAccessibleInterval< NumericAffineTransform3D > expanded = 
						Views.expand( transformImg, new OutOfBoundsBorderFactory<>(), Gauss3.halfkernelsizes( new double[] { sigma } )[ 0 ] );

				Gauss3.gauss( sigma, expanded /*Views.extendBorder( transformImg )*/, transformImg );

				interpolated.clear();
				for ( final NumericAffineTransform3D model : Views.iterable( expanded ) )
					interpolated.add( model.getTransform().copy() );

				IOFunctions.println( "New #transforms=" + interpolated.size() + " (extended due to kernelsize)" );

				/*
				it.reset();
				for ( int i = 0; i < interpolated.size(); ++i )
					interpolated.set( i, it.next().getTransform().copy() ); // could be a native type, so copy in necessary
				*/
			}

			return interpolated;
		}
	}

	public static class MyTarget implements RenderTarget< BufferedImageRenderResult >
	{
		final int width, height;
		final ARGBScreenImage accumulated;
		final BufferedImageRenderResult renderResult = new BufferedImageRenderResult();

		public MyTarget( final int width, final int height )
		{
			this.width = width;
			this.height = height;
			this.accumulated  = new ARGBScreenImage( width, height );
		}

		public void clear()
		{
			for ( final ARGBType acc : accumulated )
				acc.setZero();
		}

		@Override
		public BufferedImageRenderResult getReusableRenderResult()
		{
			return renderResult;
		}

		@Override
		public BufferedImageRenderResult createRenderResult()
		{
			return new BufferedImageRenderResult();
		}

		@Override
		public void setRenderResult( final BufferedImageRenderResult renderResult )
		{
			final BufferedImage bufferedImage = renderResult.getBufferedImage();
			final Img< ARGBType > argbs = ArrayImgs.argbs( ( ( DataBufferInt ) bufferedImage.getData().getDataBuffer() ).getData(), width, height );
			final Cursor< ARGBType > c = argbs.cursor();
			for ( final ARGBType acc : accumulated )
			{
				final int current = acc.get();
				final int in = c.next().get();
				acc.set( ARGBType.rgba(
						Math.max( ARGBType.red( in ), ARGBType.red( current ) ),
						Math.max( ARGBType.green( in ), ARGBType.green( current ) ),
						Math.max( ARGBType.blue( in ), ARGBType.blue( current ) ),
						Math.max( ARGBType.alpha( in ), ARGBType.alpha( current ) )	) );
			}
		}

		@Override
		public final int getWidth()
		{
			return width;
		}

		@Override
		public int getHeight()
		{
			return height;
		}
	}

	public static class AffineTransform3DAdapter implements JsonDeserializer<AffineTransform3D>, JsonSerializer<AffineTransform3D> {

		@Override
		public AffineTransform3D deserialize(
				final JsonElement json,
				final Type typeOfT,
				final JsonDeserializationContext context) throws JsonParseException {

			final JsonArray array = json.getAsJsonArray();
			final double[] values = new double[array.size()];
			for (int i = 0; i < values.length; ++i)
				values[i] = array.get(i).getAsDouble();
			final AffineTransform3D affine = new AffineTransform3D();
			affine.set(values);
			return affine;
		}

		@Override
		public final JsonElement serialize(
				final AffineTransform3D affine,
				final Type typeOfSrc,
				final JsonSerializationContext context) {

			return context.serialize(affine.getRowPackedCopy());
		}
	}
}
