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
package net.preibisch.mvrecon.fiji.spimdata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import mpicbg.spim.data.generic.base.Entity;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicViewDescription;
import mpicbg.spim.data.sequence.TimePoint;
import mpicbg.spim.data.sequence.ViewId;
import mpicbg.spim.data.sequence.ViewSetup;

public class SpimDataTools {

	public static List<Entity> getInstancesOfAttribute(AbstractSequenceDescription<?, ?, ?> seq, Class<? extends Entity> cl) {
		Set<Entity> res = new HashSet<>();
		for (BasicViewDescription<?> vd : seq.getViewDescriptions().values()) {

				if (cl.equals(TimePoint.class))
					res.add(vd.getTimePoint());
				else if (cl.equals(ViewSetup.class))
					res.add(vd.getViewSetup());
				else
					res.add(vd.getViewSetup().getAttribute(cl));
		}

		ArrayList<Entity> resSorted = new ArrayList<>(res);
		Entity.sortById(resSorted);
		return resSorted;
	}

	public static List<BasicViewDescription<?>> getFilteredViewDescriptions(AbstractSequenceDescription<?, ?, ?> seq,
			Map<Class<? extends Entity>, List<? extends Entity>> filters )
	{
		return getFilteredViewDescriptions( seq, filters, true );
	}
	public static List<BasicViewDescription<?>> getFilteredViewDescriptions(AbstractSequenceDescription<?, ?, ?> seq,
			Map<Class<? extends Entity>, List<? extends Entity>> filters, final boolean filterMissing) {
		ArrayList<BasicViewDescription<?>> res = new ArrayList<>();
		for (BasicViewDescription<?> vd : seq.getViewDescriptions().values()) {

			if (filterMissing && seq.getMissingViews() != null && seq.getMissingViews().getMissingViews().contains( vd ))
				continue;

			boolean matches = true;
			for (Class<? extends Entity> cl : filters.keySet()) {

				if (cl.equals(TimePoint.class)) {
					if (!filters.get(cl).contains(vd.getTimePoint()))
						matches = false;
				}
				else
				{
					Entity e = vd.getViewSetup().getAttribute(cl);
					if (!filters.get(cl).contains(e))
						matches = false;
				}
			}
			if (matches)
				res.add(vd);
		}

		return res;
	}

	/**
	 * get a Comparator to sort ViewDescriptions by the ID of the Attribute of
	 * class cl
	 * 
	 * @param cl - the Entity class to compare by
	 * @return - Comparator comparing by id of Entity class cl
	 */
	public static Comparator<BasicViewDescription<?>> getVDComparator(final Class<? extends Entity> cl) {
		return new Comparator<BasicViewDescription<?>>() {

			@Override
			public int compare(BasicViewDescription<?> o1, BasicViewDescription<?> o2) {
				if (cl == ViewSetup.class)
					return o1.getViewSetupId() - o2.getViewSetupId();
				else if (cl == TimePoint.class)
					return o1.getTimePointId() - o2.getTimePointId();
				else
					return o1.getViewSetup().getAttribute(cl).getId() - o2.getViewSetup().getAttribute(cl).getId();
			}
		};
	}

	/**
	 * get a Comparator of Lists of ViewDescriptions the first elements of the
	 * lists are compared by the ID of their Attribute of class cl (it is a good
	 * idea to sort the lists themselves first)
	 * 
	 * @param cl - the Entity class to compare by
	 * @return - Comparator comparing by id of Entity class cl of first element of VD list
	 */
	public static Comparator<List<BasicViewDescription<?>>> getVDListComparator(final Class<? extends Entity> cl) {
		return new Comparator<List<BasicViewDescription<?>>>() {

			@Override
			public int compare(List<BasicViewDescription<?>> o1, List<BasicViewDescription<?>> o2) {
				return getVDComparator(cl).compare(o1.get(0), o2.get(0));
			}
		};
	}

}
