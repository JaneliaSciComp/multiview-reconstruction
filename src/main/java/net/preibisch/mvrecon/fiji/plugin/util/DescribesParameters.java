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
package net.preibisch.mvrecon.fiji.plugin.util;

import java.util.Collections;
import java.util.Map;

/**
 * Implemented by GUI classes (interest-point detection, pairwise registration, …) that can report
 * their parameters as neutral key/value pairs for the action-history feature, using keys that the
 * BigStitcher-Spark translator maps to CLI flags (e.g. "sigma" -&gt; -s, "type" -&gt; --type).
 *
 * <p>Default: no parameters. Implementers with CLI-translatable params should override.</p>
 */
public interface DescribesParameters
{
	default Map<String,String> describeParameters()
	{
		return Collections.emptyMap();
	}
}
