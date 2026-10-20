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
 * @summary Dense LCD text: many lines in one colour, each drawn twice with
 *          a one-pixel shift, so that thousands of glyphs overlap a pending
 *          glyph and go to the same mesh layer. The layer must hold them all
 *          (its vertex indices are 16-bit) and every glyph must be drawn
 *          like the software loops draw it.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdTextLayersTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdTextLayersTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 LcdTextLayersTest skia
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

public class LcdTextLayersTest {

    private static final int W = 1400;
    private static final int H = 1000;
    private static final int TOLERANCE = 32;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        if (!pipelineActive(pipeline, image)) {
            return;
        }
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = reference.createGraphics();
        try {
            paint(g);
        } finally {
            g.dispose();
        }
        g = image.createGraphics();
        try {
            paint(g);
        } finally {
            g.dispose();
        }
        BufferedImage actual = image.getSnapshot();
        if (image.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        int off = 0;
        int missing = 0;
        int max = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = reference.getRGB(x, y);
                int q = actual.getRGB(x, y);
                int d = maxChannelDifference(p, q);
                max = Math.max(max, d);
                if (d > TOLERANCE) {
                    off++;
                }
                if ((p & 0xff) < 128 && (q & 0xff) == 0xff) {
                    missing++;
                }
            }
        }
        System.out.println(off + " pixels beyond " + TOLERANCE + " levels, " + missing + " pixels of ink missing, max " + max);
        if (off > 0 || missing > 0) {
            throw new RuntimeException("Dense LCD text differs from the software loops: " + off
                    + " pixels beyond " + TOLERANCE + " levels, " + missing + " pixels of ink missing");
        }
        System.out.println("PASSED");
    }

    /** 100 lines of 200 glyphs, each line twice: 40,000 glyphs in one batch, half of them overlapping. */
    private static void paint(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 9));
        g.setColor(new Color(0x303030));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append((char) ('a' + i % 26));
        }
        String line = sb.toString();
        for (int row = 0; row < 100; row++) {
            int y = 12 + row * 10;
            g.drawString(line, 4, y);
            g.drawString(line, 5, y);
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
        int g = Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff));
        int b = Math.abs((p & 0xff) - (q & 0xff));
        return Math.max(r, Math.max(g, b));
    }
}
