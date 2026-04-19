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

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;

public class PsiInitFromRAIFactory implements PsiInitFactory
{
	final RandomAccessibleInterval< FloatType > psiRAI;
	final boolean precise;

	/**
	 * @param psiRAI - what is used as init for the deconvolved image
	 * @param precise - if avg and max[] of input are computed approximately or precise
	 */
	public PsiInitFromRAIFactory( final RandomAccessibleInterval< FloatType > psiRAI, final boolean precise )
	{
		this.psiRAI = psiRAI;
		this.precise = precise;
	}

	@Override
	public PsiInitFromRAI createPsiInitialization()
	{
		return new PsiInitFromRAI( psiRAI, precise );
	}
}
