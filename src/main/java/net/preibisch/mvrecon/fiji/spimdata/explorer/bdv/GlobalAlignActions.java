/*
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
package net.preibisch.mvrecon.fiji.spimdata.explorer.bdv;

import java.awt.Component;
import java.awt.Point;
import java.util.function.Consumer;

import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.Actions;
import org.scijava.ui.behaviour.util.InputActionBindings;

import bdv.BigDataViewer;
import bdv.ui.keymap.Keymap;
import bdv.viewer.AbstractViewerPanel;
import bdv.viewer.AbstractViewerPanel.AlignPlane;
import bdv.viewer.NavigationActions;
import bdv.viewer.ViewerPanel;
import bdv.viewer.animate.RotationAnimator;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.LinAlgHelpers;

/**
 * shift+X/Y/Z that align the viewer with the GLOBAL coordinate system.
 *
 * BigDataViewer's own align ({@link AbstractViewerPanel#align(AlignPlane)}, installed by
 * {@link NavigationActions#installAlignPlaneActions}) rotates the viewer into the local coordinate system of the
 * CURRENT source: as soon as a view carries a rotation in its registration, shift+X/Y/Z follow its rotated axes.
 * In the explorer all views live in one global coordinate system and that is the one to look along, so the three
 * shortcuts are rebound here to the global axes. BDV's source-aligned version stays available on ctrl+shift+X/Y/Z.
 *
 * This works because {@link InputActionBindings} chains its maps in reverse order of addition: the map installed
 * here comes after BDV's "navigation" map and wins for the same key strokes. The global actions carry BDV's action
 * names, so a keymap that remaps "align XY plane" remaps them too.
 *
 * @author Stephan Preibisch
 */
public class GlobalAlignActions
{
	public static final String ID = "mvr-global-align";

	public static final String ALIGN_XY_PLANE_SOURCE = "align XY plane of the current source";
	public static final String ALIGN_ZY_PLANE_SOURCE = "align ZY plane of the current source";
	public static final String ALIGN_XZ_PLANE_SOURCE = "align XZ plane of the current source";

	public static final String[] ALIGN_XY_PLANE_SOURCE_KEYS = new String[] { "ctrl shift Z" };
	public static final String[] ALIGN_ZY_PLANE_SOURCE_KEYS = new String[] { "ctrl shift X" };
	public static final String[] ALIGN_XZ_PLANE_SOURCE_KEYS = new String[] { "ctrl shift Y", "ctrl shift A" };

	/** the duration of the rotation in ms, as in BDV's align */
	public static final long DURATION = 300;

	public static void install( final BigDataViewer bdv )
	{
		final ViewerPanel viewer = bdv.getViewer();
		final Keymap keymap = bdv.getKeymapManager().getForwardSelectedKeymap();

		final Actions actions = create( keymap.getConfig(), viewer.getOptionValues().is2D(), plane -> alignGlobal( viewer, plane ), viewer::align );
		actions.install( bdv.getViewerFrame().getKeybindings(), ID );

		keymap.updateListeners().add( () -> actions.updateKeyConfig( keymap.getConfig() ) );
	}

	/**
	 * The actions without a viewer (for tests): the global alignment under BDV's names and keys, BDV's alignment
	 * to the current source under {@link #ALIGN_XY_PLANE_SOURCE} etc. on ctrl+shift. As in BDV, a 2-D viewer
	 * only gets the XY plane.
	 */
	public static Actions create(
			final InputTriggerConfig config,
			final boolean is2D,
			final Consumer< AlignPlane > alignGlobal,
			final Consumer< AlignPlane > alignSource )
	{
		final Actions actions = new Actions( config, "bdv", "navigation" );

		actions.runnableAction( () -> alignGlobal.accept( AlignPlane.XY ), NavigationActions.ALIGN_XY_PLANE, NavigationActions.ALIGN_XY_PLANE_KEYS );
		actions.runnableAction( () -> alignSource.accept( AlignPlane.XY ), ALIGN_XY_PLANE_SOURCE, ALIGN_XY_PLANE_SOURCE_KEYS );

		if ( !is2D )
		{
			actions.runnableAction( () -> alignGlobal.accept( AlignPlane.ZY ), NavigationActions.ALIGN_ZY_PLANE, NavigationActions.ALIGN_ZY_PLANE_KEYS );
			actions.runnableAction( () -> alignGlobal.accept( AlignPlane.XZ ), NavigationActions.ALIGN_XZ_PLANE, NavigationActions.ALIGN_XZ_PLANE_KEYS );
			actions.runnableAction( () -> alignSource.accept( AlignPlane.ZY ), ALIGN_ZY_PLANE_SOURCE, ALIGN_ZY_PLANE_SOURCE_KEYS );
			actions.runnableAction( () -> alignSource.accept( AlignPlane.XZ ), ALIGN_XZ_PLANE_SOURCE, ALIGN_XZ_PLANE_SOURCE_KEYS );
		}

		return actions;
	}

	/**
	 * Rotates the viewer so that the given plane of the GLOBAL coordinate system faces the screen, about the mouse
	 * position if the mouse is over the display and about the center of the display otherwise (as BDV's align).
	 */
	public static void alignGlobal( final ViewerPanel viewer, final AlignPlane plane )
	{
		final Component display = viewer.getDisplayComponent();
		final Point mouse = display.getMousePosition();
		final double centerX = mouse == null ? display.getWidth() / 2.0 : mouse.x;
		final double centerY = mouse == null ? display.getHeight() / 2.0 : mouse.y;

		viewer.setTransformAnimator( new RotationAnimator( viewer.state().getViewerTransform(), centerX, centerY, globalOrientation( plane ), DURATION ) );
	}

	/** the viewer orientation (quaternion) that looks at the plane of the global coordinate system */
	public static double[] globalOrientation( final AlignPlane plane )
	{
		// BDV: qTarget = inverse( qSource * qAlign ) with qSource the rotation of the current source; here qSource = 1
		final double[] q = new double[ 4 ];
		LinAlgHelpers.quaternionInvert( plane.qAlign, q );
		return q;
	}

	/** where the rotation of {@link #alignGlobal} ends */
	public static AffineTransform3D aligned( final AffineTransform3D viewerTransform, final double centerX, final double centerY, final AlignPlane plane )
	{
		return new RotationAnimator( viewerTransform, centerX, centerY, globalOrientation( plane ), DURATION ).get( 1.0 );
	}
}
