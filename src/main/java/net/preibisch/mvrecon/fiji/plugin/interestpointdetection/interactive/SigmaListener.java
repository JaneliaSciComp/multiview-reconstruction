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

import java.awt.Label;
import java.awt.Scrollbar;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;

import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveDoG.ValueChange;

public class SigmaListener implements AdjustmentListener {
	final InteractiveDoG parent;
	final Label label;
	final float min, max;
	final int scrollbarSize;

	final Scrollbar sigmaScrollbar1;

	public SigmaListener(
			final InteractiveDoG parent,
			final Label label, final float min, final float max,
			final int scrollbarSize,
			final Scrollbar sigmaScrollbar1) {
		this.parent = parent;
		this.label = label;
		this.min = min;
		this.max = max;
		this.scrollbarSize = scrollbarSize;

		this.sigmaScrollbar1 = sigmaScrollbar1;
	}

	@Override
	public void adjustmentValueChanged(final AdjustmentEvent event) {
		parent.params.sigma = HelperFunctions.computeValueFromScrollbarPosition(event.getValue(), min, max, scrollbarSize);
		label.setText("Sigma 1 = " + String.format(java.util.Locale.US, "%.3f", parent.params.sigma));

		// Real time change of the radius
		// if ( !event.getValueIsAdjusting() )
		{
			while (parent.isComputing) {
				try {
					Thread.sleep( 10 );
				} catch (InterruptedException e) {}
			}
			parent.updatePreview(ValueChange.SIGMA);
		}
	}
}
