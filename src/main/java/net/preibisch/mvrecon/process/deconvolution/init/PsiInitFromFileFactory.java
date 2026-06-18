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

public class PsiInitFromFileFactory implements PsiInitFactory
{
	final File psiStartFile;
	final boolean precise;

	/**
	 * @param psiStartFile - what is used as init for the deconvolved image (loaded from disc)
	 * @param precise - if avg and max[] of input are computed approximately or precise
	 */
	public PsiInitFromFileFactory( final File psiStartFile, final boolean precise )
	{
		this.psiStartFile = psiStartFile;
		this.precise = precise;
	}

	@Override
	public PsiInitFromFile createPsiInitialization()
	{
		return new PsiInitFromFile( psiStartFile, precise );
	}
}
