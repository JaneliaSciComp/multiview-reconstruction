/*-
 * #%L
 * Software for the reconstruction of multi-view microscopic acquisitions
 * like Selective Plane Illumination Microscopy (SPIM) Data.
 * %%
 * Copyright (C) 2012 - 2026 Multiview Reconstruction developers.
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

import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import net.preibisch.mvrecon.fiji.plugin.interestpointdetection.interactive.InteractiveDoG.ValueChange;

public class SigmaListener implements ChangeListener {
	final InteractiveDoG parent;
	final JTextField text;
	final float min, max;
	final int scrollbarSize;

	final JSlider sigmaScrollbar1;

	public SigmaListener(
			final InteractiveDoG parent,
			final JTextField text, final float min, final float max,
			final int scrollbarSize,
			final JSlider sigmaScrollbar1) {
		this.parent = parent;
		this.text = text;
		this.min = min;
		this.max = max;
		this.scrollbarSize = scrollbarSize;

		this.sigmaScrollbar1 = sigmaScrollbar1;
	}

	@Override
	public void stateChanged(final ChangeEvent event) {
		parent.params.sigma = HelperFunctions.computeValueFromScrollbarPosition(sigmaScrollbar1.getValue(), min, max, scrollbarSize);
		text.setText(HelperFunctions.formatValue(parent.params.sigma, 3));

		// the DoG is computed again (on the event thread, so this can only be re-entered by an update it triggers itself)
		if (!parent.isComputing)
			parent.updatePreview(ValueChange.SIGMA);
	}
}
