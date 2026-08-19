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
package net.preibisch.mvrecon.fiji.spimdata.actionhistory;

public class XmlKeysActionHistory
{
	public static final String ACTION_HISTORY_TAG    = "ActionHistory";
	public static final String ACTION_TAG            = "Action";
	public static final String ATTR_ACTION_ID        = "id";
	public static final String ATTR_TIMESTAMP        = "timestamp";
	public static final String ATTR_MVRECON_CLASS    = "mvreconClass";
	public static final String ATTR_RESULT_REF       = "resultRef";
	public static final String PARAM_TAG             = "param";
	public static final String ATTR_PARAM_KEY        = "key";
	public static final String ATTR_PARAM_VALUE      = "value";
	public static final String VIEW_TAG              = "view";
	public static final String ATTR_VIEW_TP          = "tp";
	public static final String ATTR_VIEW_SETUP       = "setup";

	private XmlKeysActionHistory() {}
}
