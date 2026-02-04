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
package net.preibisch.mvrecon.fiji.spimdata.imgloaders.flatfield;

import static mpicbg.spim.data.XmlKeys.IMGLOADER_FORMAT_ATTRIBUTE_NAME;
import static mpicbg.spim.data.XmlKeys.IMGLOADER_TAG;
import static mpicbg.spim.data.XmlKeys.TIMEPOINTS_TIMEPOINT_TAG;
import static mpicbg.spim.data.XmlKeys.VIEWSETUP_TAG;

import java.io.File;
import java.net.URI;
import java.util.Map;

import org.janelia.saalfeldlab.n5.universe.StorageFormat;
import org.jdom2.DataConversionException;
import org.jdom2.Element;

import bdv.ViewerImgLoader;
import mpicbg.spim.data.SpimDataInstantiationException;
import mpicbg.spim.data.XmlHelpers;
import mpicbg.spim.data.generic.sequence.AbstractSequenceDescription;
import mpicbg.spim.data.generic.sequence.BasicImgLoader;
import mpicbg.spim.data.generic.sequence.ImgLoaderIo;
import mpicbg.spim.data.generic.sequence.ImgLoaders;
import mpicbg.spim.data.generic.sequence.XmlIoBasicImgLoader;
import mpicbg.spim.data.sequence.ViewId;
import net.imglib2.util.Pair;

/**
 * XML I/O handler for ViewerFlatfieldCorrectionWrappedImgLoader.
 *
 * Registers format "spimreconstruction.wrapped.flatfield.viewer" for
 * ViewerImgLoader-based flatfield correction wrappers.
 */
@ImgLoaderIo(format = "spimreconstruction.wrapped.flatfield.viewer", type = ViewerFlatfieldCorrectionWrappedImgLoader.class)
public class XmlIoViewerFlatfieldCorrectionWrappedImgLoader
		implements XmlIoBasicImgLoader<ViewerFlatfieldCorrectionWrappedImgLoader> {
	public final static String WRAPPED_IMGLOADER_TAG = "WrappedImgLoader";
	public final static String FLATFIELDS_TAG = "FlatFields";
	public final static String FLATFIELD_TAG = "FlatField";
	public final static String BRIGHTIMG_TAG = "BrightImg";
	public final static String DARKIMG_TAG = "DarkImg";
	public final static String ACTIVE_TAG = "Active";
	public final static String CACHED_TAG = "Cached";
	public final static String FORMAT_ATTR = "format";
	public final static String DATASET_ATTR = "dataset";

	@Override
	public ViewerFlatfieldCorrectionWrappedImgLoader fromXml(Element elem, File basePath,
			AbstractSequenceDescription<?, ?, ?> sequenceDescription) {
		return fromXml(elem, basePath == null ? null : basePath.toURI(), sequenceDescription);
	}

	@Override
	public ViewerFlatfieldCorrectionWrappedImgLoader fromXml(Element elem, URI basePathURI,
			AbstractSequenceDescription<?, ?, ?> sequenceDescription) {
		Element wrappedImgLoaderEl = elem.getChild(WRAPPED_IMGLOADER_TAG).getChild(IMGLOADER_TAG);
		XmlIoBasicImgLoader<?> xmlIoWrapped;
		try {
			xmlIoWrapped = ImgLoaders
					.createXmlIoForFormat(wrappedImgLoaderEl.getAttributeValue(IMGLOADER_FORMAT_ATTRIBUTE_NAME));
		} catch (SpimDataInstantiationException e) {
			e.printStackTrace();
			return null;
		}

		boolean cached = false;
		boolean active = false;
		try {
			cached = elem.getAttribute(CACHED_TAG).getBooleanValue();
			active = elem.getAttribute(ACTIVE_TAG).getBooleanValue();
		} catch (DataConversionException e) {
			e.printStackTrace();
		}

		BasicImgLoader wrappedImgLoader = xmlIoWrapped.fromXml(wrappedImgLoaderEl, basePathURI, sequenceDescription);

		// Verify wrapped loader is a ViewerImgLoader
		if (!(wrappedImgLoader instanceof ViewerImgLoader)) {
			System.err.println("ViewerFlatfieldCorrectionWrappedImgLoader requires a ViewerImgLoader, but got: "
					+ wrappedImgLoader.getClass().getName());
			return null;
		}

		ViewerFlatfieldCorrectionWrappedImgLoader res =
				new ViewerFlatfieldCorrectionWrappedImgLoader((ViewerImgLoader) wrappedImgLoader, cached);

		Element flatfields = elem.getChild(FLATFIELDS_TAG);
		for (Element flatfield : flatfields.getChildren()) {
			int tp = Integer.parseInt(flatfield.getAttributeValue(TIMEPOINTS_TIMEPOINT_TAG));
			int vs = Integer.parseInt(flatfield.getAttributeValue(VIEWSETUP_TAG));
			ViewId viewId = new ViewId(tp, vs);

			FlatfieldImageInfo brightInfo = parseFlatfieldImageInfo(flatfield, BRIGHTIMG_TAG, basePathURI);
			FlatfieldImageInfo darkInfo = parseFlatfieldImageInfo(flatfield, DARKIMG_TAG, basePathURI);

			if (brightInfo != null)
				res.setBrightImage(viewId, brightInfo);
			if (darkInfo != null)
				res.setDarkImage(viewId, darkInfo);
		}

		res.setActive(active);
		return res;
	}

	@Override
	public Element toXml(ViewerFlatfieldCorrectionWrappedImgLoader imgLoader, File basePath) {
		return toXml(imgLoader, basePath == null ? null : basePath.toURI());
	}

	@Override
	public Element toXml(ViewerFlatfieldCorrectionWrappedImgLoader imgLoader, URI basePathURI) {
		final Map<ViewId, Pair<FlatfieldImageInfo, FlatfieldImageInfo>> infoMap = imgLoader.getInfoMap();

		final Element wholeElem = new Element(IMGLOADER_TAG);
		wholeElem.setAttribute(IMGLOADER_FORMAT_ATTRIBUTE_NAME,
				this.getClass().getAnnotation(ImgLoaderIo.class).format());
		final Element wrappedIL = new Element(WRAPPED_IMGLOADER_TAG);

		wholeElem.setAttribute(ACTIVE_TAG, Boolean.toString(imgLoader.isActive()));
		wholeElem.setAttribute(CACHED_TAG, Boolean.toString(imgLoader.isCached()));

		try {
			@SuppressWarnings({"rawtypes"})
			XmlIoBasicImgLoader loaderIO = ImgLoaders
					.createXmlIoForImgLoaderClass(imgLoader.getWrappedImgLoader().getClass());
			@SuppressWarnings("unchecked")
			Element wrappedInner = loaderIO.toXml(imgLoader.getWrappedImgLoader(), basePathURI);
			wrappedIL.addContent(wrappedInner);
		} catch (SpimDataInstantiationException e) {
			e.printStackTrace();
			return null;
		}

		final Element elFlatfields = new Element(FLATFIELDS_TAG);

		for (ViewId vid : infoMap.keySet()) {
			final Pair<FlatfieldImageInfo, FlatfieldImageInfo> infos = infoMap.get(vid);
			if (infos == null || (infos.getA() == null && infos.getB() == null))
				continue;

			final Element elFlatfield = new Element(FLATFIELD_TAG);
			elFlatfield.setAttribute(TIMEPOINTS_TIMEPOINT_TAG, Integer.toString(vid.getTimePointId()));
			elFlatfield.setAttribute(VIEWSETUP_TAG, Integer.toString(vid.getViewSetupId()));

			if (infos.getA() != null)
				elFlatfield.addContent(createFlatfieldImageElement(BRIGHTIMG_TAG, infos.getA(), basePathURI));
			if (infos.getB() != null)
				elFlatfield.addContent(createFlatfieldImageElement(DARKIMG_TAG, infos.getB(), basePathURI));

			elFlatfields.addContent(elFlatfield);
		}

		wholeElem.addContent(wrappedIL);
		wholeElem.addContent(elFlatfields);
		return wholeElem;
	}

	/**
	 * Parse a flatfield image element (BrightImg or DarkImg) into a FlatfieldImageInfo.
	 *
	 * @param parent parent element containing the image element
	 * @param tag the tag name (BRIGHTIMG_TAG or DARKIMG_TAG)
	 * @param basePathURI base path for resolving relative URIs
	 * @return FlatfieldImageInfo, or null if element doesn't exist
	 */
	protected static FlatfieldImageInfo parseFlatfieldImageInfo(Element parent, String tag, URI basePathURI) {
		Element imgElement = parent.getChild(tag);
		if (imgElement == null)
			return null;

		URI uri = XmlHelpers.loadPathURI(parent, tag, basePathURI);
		if (uri == null)
			return null;

		String formatStr = imgElement.getAttributeValue(FORMAT_ATTR);
		String dataset = imgElement.getAttributeValue(DATASET_ATTR);

		StorageFormat format = FlatfieldImageLoader.parseFormat(formatStr);
		return new FlatfieldImageInfo(uri, format, dataset);
	}

	/**
	 * Create an XML element for a flatfield image with format and dataset attributes.
	 *
	 * @param tag the tag name (BRIGHTIMG_TAG or DARKIMG_TAG)
	 * @param info the flatfield image info
	 * @param basePathURI base path for creating relative URIs
	 * @return the XML element
	 */
	protected static Element createFlatfieldImageElement(String tag, FlatfieldImageInfo info, URI basePathURI) {
		Element el = XmlHelpers.pathElementURI(tag, info.getUri(), basePathURI);
		el.setAttribute(FORMAT_ATTR, FlatfieldImageLoader.formatToString(info.getFormat()));
		if (info.getDataset() != null && !info.getDataset().isEmpty())
			el.setAttribute(DATASET_ATTR, info.getDataset());
		return el;
	}
}
