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
package net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

import ij.ImagePlus;
import ij.gui.Roi;
import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveDoG.ValueChange;

/**
 * Tests whether the ROI was changed and will recompute the preview
 * 
 * @author Stephan Preibisch
 */
public class ROIListener implements MouseListener {
	final InteractiveDoG parent;
	final ImagePlus source;

	public ROIListener( final InteractiveDoG parent, final ImagePlus s){
		this.parent = parent;
		this.source = s;
	}

	@Override
	public void mouseClicked(MouseEvent e) {}

	@Override
	public void mouseEntered(MouseEvent e) {}

	@Override
	public void mouseExited(MouseEvent e) {}

	@Override
	public void mousePressed(MouseEvent e) {}

	@Override
	public void mouseReleased(final MouseEvent e) {		
		// here the ROI might have been modified, let's test for that
		final Roi roi = source.getRoi();

		// roi is wrong, clear the screen 
		if (roi == null || roi.getType() != Roi.RECTANGLE){
			source.setRoi( parent.rectangle );
		}

		// TODO: might put the update part for the roi here instead of the updatePreview
		while (parent.isComputing)
		{
			try {
				Thread.sleep( 10 );
			} catch (InterruptedException e1) {}
		}

		parent.updatePreview(ValueChange.ROI);

	}
}
