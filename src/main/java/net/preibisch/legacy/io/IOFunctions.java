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
package net.preibisch.legacy.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;

import javax.swing.SwingUtilities;

import bdv.export.ProgressWriter;
import bdv.export.ProgressWriterConsole;
import ij.IJ;
import ij.ImagePlus;
import ij.io.Opener;
import ij.process.ImageProcessor;
import mpicbg.models.AffineModel3D;
import net.imglib2.Cursor;
import net.imglib2.FinalRealInterval;
import net.imglib2.RealInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgFactory;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.preibisch.mvrecon.fiji.plugin.resave.ProgressWriterIJ;

public class IOFunctions
{
	/**
	 * Never instantiate this class, it contains only static methods
	 */
	protected IOFunctions() { }
	
	public static boolean printIJLog = true;

	public static Img< FloatType > openAs32Bit( final File file )
	{
		return openAs32Bit( file, new ArrayImgFactory< FloatType >() );
	}

	@SuppressWarnings("unchecked")
	public static ArrayImg< FloatType, ? > openAs32BitArrayImg( final File file )
	{
		return (ArrayImg< FloatType, ? >)openAs32Bit( file, new ArrayImgFactory< FloatType >() );
	}

	public static Img< FloatType > openAs32Bit( final File file, final ImgFactory< FloatType > factory )
	{
		if ( !file.exists() )
			throw new RuntimeException( "File '" + file.getAbsolutePath() + "' does not exisit." );

		final ImagePlus imp = new Opener().openImage( file.getAbsolutePath() );

		if ( imp == null )
			throw new RuntimeException( "File '" + file.getAbsolutePath() + "' coult not be opened." );

		final Img< FloatType > img;

		if ( imp.getStack().getSize() == 1 )
		{
			// 2d
			img = factory.create( new int[]{ imp.getWidth(), imp.getHeight() }, new FloatType() );
			final ImageProcessor ip = imp.getProcessor();

			final Cursor< FloatType > c = img.localizingCursor();
			
			while ( c.hasNext() )
			{
				c.fwd();

				final int x = c.getIntPosition( 0 );
				final int y = c.getIntPosition( 1 );

				c.get().set( ip.getf( x, y ) );
			}

		}
		else
		{
			// >2d
			img = factory.create( new int[]{ imp.getWidth(), imp.getHeight(), imp.getStack().getSize() }, new FloatType() );

			final Cursor< FloatType > c = img.localizingCursor();

			// for efficiency reasons
			final ArrayList< ImageProcessor > ips = new ArrayList< ImageProcessor >();

			for ( int z = 0; z < imp.getStack().getSize(); ++z )
				ips.add( imp.getStack().getProcessor( z + 1 ) );

			while ( c.hasNext() )
			{
				c.fwd();

				final int x = c.getIntPosition( 0 );
				final int y = c.getIntPosition( 1 );
				final int z = c.getIntPosition( 2 );

				c.get().set( ips.get( z ).getf( x, y ) );
			}
		}

		imp.close();

		return img;
	}

	public static void printlnTS() { printlnTS( "" ); }
	public static void printlnTS( final Object object) { printlnTS( object.toString() ); }
	public static void printlnTS( final String string ) 
	{
		println( new Date( System.currentTimeMillis() ) + ": " + string );
	}

	public static void println() { println( "" ); }
	public static void println( final Object object) { println( object.toString() ); }
	public static void println( final String string )
	{
		if ( printIJLog )
		{
			if ( SwingUtilities.isEventDispatchThread() )
				IJ.log( string );
			else
				SwingUtilities.invokeLater( () -> IJ.log( string ) );
		}
		else
			System.out.println( string );
	}

	public static void printErr() { printErr( "" ); }
	public static void printErr( final Object object) { printErr( object.toString() ); }
	public static void printErr( final String string )
	{
		if ( printIJLog )
		{
			if ( SwingUtilities.isEventDispatchThread() )
				IJ.error( string );
			else
				SwingUtilities.invokeLater( () -> IJ.error( string ) );
		}
		else
			System.err.println( string );
	}

	private static ProgressWriterIJ progressWriterIJ = new ProgressWriterIJ();
	private static ProgressWriterConsole progressWriterConsole = new ProgressWriterConsole();
	public static ProgressWriter getProgressWriter()
	{
		if ( printIJLog )
			return progressWriterIJ;
		else
			return progressWriterConsole;
	}
	
	public static String getShortName( final String fileName )
	{
		String shortName = fileName;
		shortName = shortName.replace('\\', '/');
		while (shortName.contains("/"))
			shortName = shortName.substring(shortName.indexOf("/") + 1, shortName.length());
		
		return shortName;
	}

	/*public static boolean[] readSegmentation( final ViewDataBeads[] views, final String directory, final SPIMConfiguration conf )
	{
		boolean readSeg[] = new boolean[views.length];
		boolean readDim[] = new boolean[views.length];
		
		for (int i = 0; i < views.length; i++)
		{
			ViewDataBeads view = views[i];

			readDim[i] = readDim( view, directory );			
			if (!readDim[i]) 
				continue;
			
			readSeg[i] = readSegmentation( view, directory, conf );			
			if (!readSeg[i])
			{
				readDim[i] = false;
				continue;
			}

			if (readSeg[i] && readDim[i])
			{
				IOFunctions.println("Loaded " + view.getBeadStructure().getBeadList().size() + " beads for " + view.shortName + 
								   "[" + view.getImageSize()[0] + "x" + view.getImageSize()[1] + "x" + view.getImageSize()[2] + "]");				
			}
		}

		return readSeg;
	}*/
	
	/*
	public static void writeSegmentation( final ViewDataBeads[] views, final String directory )
	{
		for (ViewDataBeads view : views)
			writeSegmentation(view, directory);			
	}

	public static void writeRegistration( final ViewDataBeads[] views, final String directory )
	{
		for (ViewDataBeads view : views)
		{
			String fileName = directory + view.shortName + ".registration";
			writeSingleRegistration( view, fileName );			
		}		
	}
	*/
		
	public static void reWriteRegistrationFile( final File file, final AffineModel3D newModel, final AffineModel3D oldModel, final AffineModel3D preConcatenated )
	{
		try 
		{
			// read the old file
			final ArrayList< String > content = new ArrayList< String >();
			final BufferedReader in = TextFileAccess.openFileRead( file );
			
			while ( in.ready() )
				content.add( in.readLine().trim() );

			in.close();
			
			// over-write the old file
			final PrintWriter out = TextFileAccess.openFileWrite( file );
			
			// get the model parameters
			final double[] matrixNew = newModel.getMatrix( null );
			
			for ( final String entry : content )
			{
				if (entry.startsWith("m00:"))
					out.println( "m00: " + matrixNew[ 0 ] );
				else if (entry.startsWith("m01:"))
					out.println( "m01: " + matrixNew[ 1 ] );
				else if (entry.startsWith("m02:"))
					out.println( "m02: " + matrixNew[ 2 ] );
				else if (entry.startsWith("m03:"))
					out.println( "m03: " + matrixNew[ 3 ] );
				else if (entry.startsWith("m10:"))
					out.println( "m10: " + matrixNew[ 4 ] );
				else if (entry.startsWith("m11:"))
					out.println( "m11: " + matrixNew[ 5 ] );
				else if (entry.startsWith("m12:"))
					out.println( "m12: " + matrixNew[ 6 ] );
				else if (entry.startsWith("m13:"))
					out.println( "m13: " + matrixNew[ 7 ] );
				else if (entry.startsWith("m20:"))
					out.println( "m20: " + matrixNew[ 8 ] );
				else if (entry.startsWith("m21:"))
					out.println( "m21: " + matrixNew[ 9 ] );
				else if (entry.startsWith("m22:"))
					out.println( "m22: " + matrixNew[ 10 ] );
				else if (entry.startsWith("m23:"))
					out.println( "m23: " + matrixNew[ 11 ] );
				else if (entry.startsWith("model:"))
					out.println( "model: AffineModel3D" );
				else
					out.println( entry );
			}
			
			// save the old models, just in case
			final double[] matrixOld = oldModel.getMatrix( null );
			final double[] matrixConcat = preConcatenated.getMatrix( null );

			out.println();
			out.println( "Previous model: " + Util.printCoordinates( matrixOld ) );
			out.println( "Pre-concatenated model: " + Util.printCoordinates( matrixConcat ) );
			
			out.close();
		} 
		catch (IOException e) 
		{
			IJ.log( "Cannot find file: " + file.getAbsolutePath() + ": " + e );
			// TODO Auto-generated catch block
			e.printStackTrace();
			return;
		}	
	}
	
	public static AffineModel3D getModelFromFile( final File file )
	{
		final AffineModel3D model = new AffineModel3D();
				
		try 
		{
			final BufferedReader in = TextFileAccess.openFileRead( file );
			
			// get 12 entry double array
			final double m[] = new double[ 12 ];
			
			// the default if nothing is written
			String savedModel = "AffineModel3D";

			while ( in.ready() )
			{
				String entry = in.readLine().trim();
				
				if (entry.startsWith("m00:"))
					m[ 0 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m01:"))
					m[ 1 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m02:"))
					m[ 2 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m03:"))
					m[ 3 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m10:"))
					m[ 4 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m11:"))
					m[ 5 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m12:"))
					m[ 6 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m13:"))
					m[ 7 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m20:"))
					m[ 8 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m21:"))
					m[ 9 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m22:"))
					m[ 10 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("m23:"))
					m[ 11 ] = Double.parseDouble(entry.substring(5, entry.length()));
				else if (entry.startsWith("model:"))
					savedModel = entry.substring(7, entry.length()).trim();
			}

			in.close();
			
			if ( !savedModel.equals("AffineModel3D") )
				IOFunctions.println( "Warning: Loading a '" + savedModel + "' as AffineModel3D!" );
				
			model.set( m[ 0 ], m[ 1 ], m[ 2 ], m[ 3 ], m[ 4 ], m[ 5 ], m[ 6 ], m[ 7 ], m[ 8 ], m[ 9 ], m[ 10 ], m[ 11 ] );
			
		} 
		catch (IOException e) 
		{
			IJ.log( "Cannot find file: " + file.getAbsolutePath() + ": " + e );
			// TODO Auto-generated catch block
			e.printStackTrace();
			return null;
		}
		
		return model;
	}

	public static String printRealInterval( final RealInterval interval )
	{
		String out = "(Interval empty)";

		if ( interval == null || interval.numDimensions() == 0 )
			return out;

		out = "[" + interval.realMin( 0 );

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + interval.realMin( i );

		out += "] -> [" + interval.realMax( 0 );

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + interval.realMax( i );

		out += "], dimensions (" + ( interval.realMax( 0 ) - interval.realMin( 0 ) );

		for ( int i = 1; i < interval.numDimensions(); i++ )
			out += ", " + ( interval.realMax( i ) - interval.realMin( i ) );

		out += ")";

		return out;
	}
}
