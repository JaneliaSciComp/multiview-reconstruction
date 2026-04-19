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
package net.preibisch.mvrecon.process.deconvolution.init;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;

import net.imglib2.img.Img;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import net.preibisch.legacy.io.IOFunctions;
import net.preibisch.mvrecon.process.deconvolution.DeconView;
import net.preibisch.mvrecon.process.fusion.FusionTools;

public class PsiInitFromFile implements PsiInit
{
	final File psiStartFile;
	PsiInit init2; // for max and avg

	/**
	 * @param psiStartFile - what is used as init for the deconvolved image (loaded from disc)
	 * @param precise - if avg and max[] of input are computed approximately or precise
	 */
	public PsiInitFromFile( final File psiStartFile, final boolean precise )
	{
		this.psiStartFile = psiStartFile;

		if ( precise )
		{
			final PsiInitAvgPrecise init2 = new PsiInitAvgPrecise();
			init2.setImgToAvg( false );
			this.init2 = init2;
		}
		else
		{
			final PsiInitAvgApprox init2 = new PsiInitAvgApprox();
			init2.setImgToAvg( false );
			this.init2 = init2;
		}
	}
	
	@Override
	public boolean runInitialization(
			final Img< FloatType > psi,
			final List< DeconView > views,
			final ExecutorService service )
	{
		try
		{
			final Img< FloatType > input = IOFunctions.openAs32Bit( psiStartFile, new CellImgFactory<>() );

			for ( int d = 0; d < psi.numDimensions(); ++d )
				if ( input.dimension( d ) != psi.dimension( d ) )
				{
					IOFunctions.println( "Image dimensions do not match: " + Util.printInterval( input ) + " != " + Util.printInterval( psi ) );
					return false;
				}

			FusionTools.copyImg( Views.zeroMin( input ), Views.zeroMin( psi ), service );

			IOFunctions.println( "File: " + psiStartFile.getAbsolutePath() + " copied onto PSI for init, now approx computing of avg & max." );

			return init2.runInitialization( psi, views, service );
		} 
		catch ( RuntimeException e )
		{
			IOFunctions.println( "Cannot load init file: " + psiStartFile.getAbsolutePath() + ": " + e );
			return false;
		}
	}

	@Override
	public double getAvg() { return init2.getAvg(); }

	@Override
	public float[] getMax() { return init2.getMax(); }
}
