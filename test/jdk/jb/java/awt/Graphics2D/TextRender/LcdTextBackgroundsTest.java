/*
 * Copyright (c) 2026, JetBrains s.r.o.. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/*
 * @test
 * @key headful
 * @summary LCD text on a GPU pipeline against the software loops, on
 *          light, dark and mid-tone backgrounds in several text colours,
 *          with a second and a third string drawn over the first so that
 *          glyphs of two and three lists overlap inside one batch. The blender mode reads the
 *          destination and reproduces the loops' per-channel gamma blend
 *          closely; the two-pass mode folds the gamma correction into the
 *          coverage and is allowed a few levels at glyph edges. Overlapping
 *          glyphs must not change the result beyond that: each glyph has to
 *          be composited on top of the previous one, not added to it.
 *          Metal's blend is up to 43 levels off the loops in single edge
 *          pixels and runs with a wider tolerance.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdTextBackgroundsTest skia 16
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdTextBackgroundsTest skia 32
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 LcdTextBackgroundsTest metal 48
 */

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import sun.java2d.SurfaceData;

public class LcdTextBackgroundsTest {

    private static final int W = 260;
    private static final int H = 40;
    private static final int[] BACKGROUNDS = { 0xFFFFFF, 0x000000, 0x2B2B2B, 0x404040, 0x808080, 0xF2F2F2 };
    private static final int[] COLORS = { 0x000000, 0xFFFFFF, 0xBBBBBB, 0x3030C0, 0xFF8800 };

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int tolerance = Integer.parseInt(args[1]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!pipelineActive(pipeline, gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE))) {
            return;
        }
        StringBuilder failures = new StringBuilder();
        for (int background : BACKGROUNDS) {
            for (int color : COLORS) {
                for (int copies = 1; copies <= 3; copies++) {
                    boolean overlap = copies > 1;
                    BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = reference.createGraphics();
                    try {
                        paint(g, background, color, copies);
                    } finally {
                        g.dispose();
                    }
                    VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
                    g = image.createGraphics();
                    try {
                        paint(g, background, color, copies);
                    } finally {
                        g.dispose();
                    }
                    BufferedImage actual = image.getSnapshot();
                    if (image.contentsLost()) {
                        throw new RuntimeException("Volatile image contents were lost");
                    }
                    int max = 0;
                    int off = 0;
                    for (int y = 0; y < H; y++) {
                        for (int x = 0; x < W; x++) {
                            int d = maxChannelDifference(reference.getRGB(x, y), actual.getRGB(x, y));
                            max = Math.max(max, d);
                            if (d > tolerance) {
                                off++;
                            }
                        }
                    }
                    String what = String.format("%06x on %06x%s", color, background,
                            overlap ? " with " + (copies - 1) + " overlapping" : "");
                    System.out.println(what + ": max " + max + " levels, " + off + " pixels beyond " + tolerance);
                    if (off > 0) {
                        failures.append(what).append(" (max ").append(max).append(") ");
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("LCD text differs from the software loops by more than "
                    + tolerance + " levels: " + failures);
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D g, int background, int color, int copies) {
        g.setColor(new Color(background));
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setColor(new Color(color));
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        g.setFont(font);
        g.drawString("Hamburgefonstiv ffi fl 0123 Wq", 4, 15);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("if (x <= y) { return ffi; }", 4, 32);
        // further glyph lists of the same colour, each one pixel over the
        // previous: glyphs that overlap pending ones go to later layers of the
        // batch, and a third copy overlaps both
        g.setFont(font);
        for (int copy = 1; copy < copies; copy++) {
            g.drawString("Hamburgefonstiv ffi fl 0123 Wq", 4 + copy, 15);
        }
    }

    /** Whether the pipeline named on the run line owns the volatile image's surface; otherwise there is nothing to test. */
    static boolean pipelineActive(String pipeline, VolatileImage image) {
        String surface = SurfaceData.getPrimarySurfaceData(image).getClass().getName();
        boolean active = switch (pipeline) {
            case "skia" -> surface.contains(".skia.");
            case "metal" -> surface.contains(".metal.");
            default -> throw new IllegalArgumentException("unknown pipeline " + pipeline);
        };
        System.out.println("pipeline " + pipeline + ", surface " + surface + (active ? "" : ": nothing to test"));
        return active;
    }

    static int maxChannelDifference(int p, int q) {
        int r = Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff));
        int gr = Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff));
        int b = Math.abs((p & 0xff) - (q & 0xff));
        return Math.max(r, Math.max(gr, b));
    }
}
