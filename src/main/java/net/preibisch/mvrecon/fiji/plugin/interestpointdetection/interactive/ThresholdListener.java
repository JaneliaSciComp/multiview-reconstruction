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

public class ThresholdListener implements ChangeListener {
	final InteractiveDoG parent;
	final JTextField text;
	final JSlider slider;
	final float min, max;
	final float log1001 = (float) Math.log10(1001);

	public ThresholdListener(
			final InteractiveDoG parent,
			final JTextField text, final float min, final float max, final JSlider slider) {
		this.parent = parent;
		this.text = text;
		this.min = min;
		this.max = max;
		this.slider = slider;
	}

	@Override
	public void stateChanged(final ChangeEvent event) {
		parent.params.threshold = min + ((log1001 - (float) Math.log10(1001 - slider.getValue())) / log1001) * (max - min);
				
		text.setText(HelperFunctions.formatValue(parent.params.threshold, 5));

		// filtering only, cheap; the preview computes on the event thread, so this can only be re-entered by an update it triggers itself
		if (!parent.isComputing)
			parent.updatePreview(ValueChange.THRESHOLD);
	}
}
