/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.lwawt.macosx;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.security.PrivilegedAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import static javax.accessibility.AccessibleContext.ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_CARET_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_STATE_PROPERTY;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_TABLE_MODEL_CHANGED;
import static javax.accessibility.AccessibleContext.ACCESSIBLE_TEXT_PROPERTY;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import sun.awt.AWTAccessor;


class CAccessible extends CFRetainedResource implements Accessible {
    private final static AtomicReference<Timer> timer = new AtomicReference<Timer>();
    private final static int SELECTED_CHILDREN_MILI_SECONDS_DEFAULT = 100;
    private static int SELECTED_CHILDREN_MILI_SECONDS;

    static {
        AtomicInteger selectedCildrenMiliSecondsRef = new AtomicInteger();
        java.security.AccessController.doPrivileged(
                new PrivilegedAction<Void>() {
                    @Override
                    public Void run() {
                        selectedCildrenMiliSecondsRef.set(
                                Integer.getInteger("sun.lwawt.macosx.CAccessible.selectedChildrenMiliSeconds",
                                        SELECTED_CHILDREN_MILI_SECONDS_DEFAULT));
                        return null;
                    }
                });
        int scms = selectedCildrenMiliSecondsRef.get();
        SELECTED_CHILDREN_MILI_SECONDS = scms >= 0 ? scms : SELECTED_CHILDREN_MILI_SECONDS_DEFAULT;
    }

    public static CAccessible getCAccessible(final Accessible a) {
        if (a == null) return null;
        AccessibleContext context = a.getAccessibleContext();
        AWTAccessor.AccessibleContextAccessor accessor
                = AWTAccessor.getAccessibleContextAccessor();
        final CAccessible cachedCAX = (CAccessible) accessor.getNativeAXResource(context);
        if (cachedCAX != null) {
            return cachedCAX;
        }
        final CAccessible newCAX = new CAccessible(a);
        accessor.setNativeAXResource(context, newCAX);
        return newCAX;
    }

    @Override
    protected native void nativeCFRelease(final long ptr, final boolean disposeOnAppKitThread);

    private static native void unregisterFromCocoaAXSystem(long ptr);
    private static native void valueChanged(long ptr);
    private static native void selectedTextChanged(long ptr);
    private static native void selectionChanged(long ptr);
    private static native void menuOpened(long ptr);
    private static native void menuClosed(long ptr);
    private static native void menuItemSelected(long ptr);
    private static native void tableContentCacheClear(long ptr);

    private Accessible accessible;

    private AccessibleContext activeDescendant;

    private CAccessible(final Accessible accessible) {
        super(0L, true); // real pointer will be poked in by native

        if (accessible == null) throw new NullPointerException();
        this.accessible = accessible;

        if (accessible instanceof Component) {
            addNotificationListeners((Component)accessible);
        }
    }

    @Override
    protected synchronized void dispose() {
        if (ptr != 0) unregisterFromCocoaAXSystem(ptr);
        super.dispose();
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        return accessible != null ? accessible.getAccessibleContext() : null;
    }

    public void addNotificationListeners(Component c) {
        if (c instanceof Accessible) {
            AccessibleContext ac = ((Accessible)c).getAccessibleContext();
            ac.addPropertyChangeListener(new AXChangeNotifier());
        }
        if (c instanceof JProgressBar) {
            JProgressBar pb = (JProgressBar) c;
            pb.addChangeListener(new AXProgressChangeNotifier());
        } else if (c instanceof JSlider) {
            JSlider slider = (JSlider) c;
            slider.addChangeListener(new AXProgressChangeNotifier());
        }
    }


    private class AXChangeNotifier implements PropertyChangeListener {

        @Override
        public void propertyChange(PropertyChangeEvent e) {
            String name = e.getPropertyName();
            if ( ptr != 0 ) {
                Object newValue = e.getNewValue();
                Object oldValue = e.getOldValue();
                if (name.compareTo(ACCESSIBLE_CARET_PROPERTY) == 0) {
                    selectedTextChanged(ptr);
                } else if (name.compareTo(ACCESSIBLE_TEXT_PROPERTY) == 0 ) {
                    valueChanged(ptr);
                } else if (name.compareTo(ACCESSIBLE_SELECTION_PROPERTY) == 0 ) {
                    Timer newTimer = new Timer(SELECTED_CHILDREN_MILI_SECONDS, e1 -> selectionChanged(ptr));
                    newTimer.setRepeats(false);
                    Timer oldTimer = timer.getAndSet(newTimer);
                    if (oldTimer != null) {
                        oldTimer.stop();
                    }
                    newTimer.start();
                } else if (name.compareTo(ACCESSIBLE_TABLE_MODEL_CHANGED) == 0) {
                    valueChanged(ptr);
                    if (CAccessible.getSwingAccessible(CAccessible.this) != null) {
                        Accessible a = CAccessible.getSwingAccessible(CAccessible.this);
                        AccessibleContext ac = a.getAccessibleContext();
                        if ((ac != null) && (ac.getAccessibleRole() == AccessibleRole.TABLE)) {
                            tableContentCacheClear(ptr);
                        }
                    }
                } else if (name.compareTo(ACCESSIBLE_ACTIVE_DESCENDANT_PROPERTY) == 0 ) {
                    if (newValue == null || newValue instanceof AccessibleContext) {
                        activeDescendant = (AccessibleContext)newValue;
                    }
                } else if (name.compareTo(ACCESSIBLE_STATE_PROPERTY) == 0) {
                    AccessibleContext thisAC = accessible.getAccessibleContext();
                    AccessibleRole thisRole = thisAC.getAccessibleRole();
                    Accessible parentAccessible = thisAC.getAccessibleParent();
                    AccessibleRole parentRole = null;
                    if (parentAccessible != null) {
                        parentRole = parentAccessible.getAccessibleContext().getAccessibleRole();
                    }
                    if (thisRole == AccessibleRole.COMBO_BOX) {
                        selectionChanged(ptr);
                    }
                    // At least for now don't handle combo box menu state changes.
                    // This may change when later fixing issues which currently
                    // exist for combo boxes, but for now the following is only
                    // for JPopupMenus, not for combobox menus.
                    //if (parentRole != AccessibleRole.COMBO_BOX) {
                        if (thisRole == AccessibleRole.POPUP_MENU) {
                            if ( newValue != null &&
                                 ((AccessibleState)newValue) == AccessibleState.VISIBLE ) {
                                    menuOpened(ptr);
                            } else if ( oldValue != null &&
                                        ((AccessibleState)oldValue) == AccessibleState.VISIBLE ) {
                                menuClosed(ptr);
                            }
                        } else if (thisRole == AccessibleRole.MENU_ITEM) {
                            if ( newValue != null &&
                                 ((AccessibleState)newValue) == AccessibleState.FOCUSED ) {
                                menuItemSelected(ptr);
                            }
                        //}
                    }
                }
            }
        }
    }

    private class AXProgressChangeNotifier implements ChangeListener {
        @Override
        public void stateChanged(ChangeEvent e) {
            if (ptr != 0) valueChanged(ptr);
        }
    }

    static Accessible getSwingAccessible(final Accessible a) {
        return (a instanceof CAccessible) ? ((CAccessible)a).accessible : a;
    }

    static AccessibleContext getActiveDescendant(final Accessible a) {
        return (a instanceof CAccessible) ? ((CAccessible)a).activeDescendant : null;
    }

}
