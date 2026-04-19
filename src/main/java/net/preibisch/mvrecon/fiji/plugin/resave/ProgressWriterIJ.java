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
package net.preibisch.mvrecon.fiji.plugin.resave;

import bdv.export.ProgressWriter;
import ij.IJ;
import ij.io.LogStream;

import java.io.PrintStream;

public class ProgressWriterIJ implements ProgressWriter
{
	protected final PrintStream out;

	protected final PrintStream err;

	public ProgressWriterIJ()
	{
		out = new LogStream();
		err = new LogStream();
	}

	@Override
	public PrintStream out()
	{
		return out;
	}

	@Override
	public PrintStream err()
	{
		return err;
	}

	@Override
	public void setProgress( final double completionRatio )
	{
		IJ.showProgress( completionRatio );
	}
}
