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
 * @summary A surface that is flushed or disposed while LCD text drawn to it
 *          is still queued in a glyph batch: the batch has to be drawn into
 *          that surface before the pipeline forgets it, in every LCD mode.
 *          The accumulating mode composites the coverage onto the current
 *          destination from a hook, so it is the one that notices if the
 *          destination has already been dropped.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 LcdTextDisposeTest skia
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 LcdTextDisposeTest metal
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

public class LcdTextDisposeTest {

    private static final int W = 200;
    private static final int H = 40;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        if (!pipelineActive(pipeline, gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE))) {
            return;
        }
        for (int round = 0; round < 300; round++) {
            // text queued, then the image flushed: the queued batch meets a surface on its way out
            VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            Graphics2D g = image.createGraphics();
            try {
                paint(g, round);
            } finally {
                g.dispose();
            }
            image.flush();
            // text queued, then the image dropped for the disposer
            image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
            g = image.createGraphics();
            try {
                paint(g, round);
            } finally {
                g.dispose();
            }
            image = null;
            System.gc();
        }
        // and one that is read back: the pipeline is still in order
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        Graphics2D g = image.createGraphics();
        try {
            paint(g, 0);
        } finally {
            g.dispose();
        }
        BufferedImage actual = image.getSnapshot();
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        g = reference.createGraphics();
        try {
            paint(g, 0);
        } finally {
            g.dispose();
        }
        int off = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (maxChannelDifference(reference.getRGB(x, y), actual.getRGB(x, y)) > 48) {
                    off++;
                }
            }
        }
        if (off > 0) {
            throw new RuntimeException("LCD text after flushed and disposed surfaces differs from the software loops: "
                    + off + " pixels beyond 48 levels");
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D g, int round) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        g.setColor(new Color(0x202020 + round));
        g.drawString("Hamburgefonstiv " + round, 4, 15);
        g.drawString("Hamburgefonstiv " + round, 5, 31);
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
