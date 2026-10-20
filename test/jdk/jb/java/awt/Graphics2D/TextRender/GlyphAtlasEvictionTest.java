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
 * @summary A frame that needs more distinct glyphs than the glyph atlas
 *          has cells evicts cells in the middle of glyph lists. The flush
 *          that precedes an eviction must not lose the rest of the list:
 *          every glyph of such a frame has to be drawn, and drawn like the
 *          software loops draw it, in grayscale and in LCD mode.
 *          Metal's grayscale glyphs sit up to 45 levels off the loops and
 *          its LCD glyphs of the largest sizes lose single pixels, so Metal
 *          runs the grayscale mode with a wider tolerance.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 GlyphAtlasEvictionTest skia gray 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 GlyphAtlasEvictionTest skia lcd 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 GlyphAtlasEvictionTest skia lcd 32
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 GlyphAtlasEvictionTest metal gray 48
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

public class GlyphAtlasEvictionTest {

    private static final int W = 1400;
    private static final int H = 1000;
    // 94 printable ASCII glyphs in 32 sizes and two weights: about three
    // times the 1,024 cells of the 1024x1024 atlas of 32x32 cells
    private static final int SIZES = 32;
    private static final int FRAMES = 3;   // the later frames draw with a full atlas

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        boolean lcd = args[1].equals("lcd");
        int tolerance = Integer.parseInt(args[2]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = reference.createGraphics();
        try {
            paint(g, lcd);
        } finally {
            g.dispose();
        }
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        if (!pipelineActive(pipeline, image)) {
            return;
        }
        BufferedImage actual = null;
        for (int frame = 0; frame < FRAMES; frame++) {
            g = image.createGraphics();
            try {
                paint(g, lcd);
            } finally {
                g.dispose();
            }
            actual = image.getSnapshot();
        }
        if (image.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        int missing = 0;
        int off = 0;
        int max = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = reference.getRGB(x, y);
                int q = actual.getRGB(x, y);
                int d = maxChannelDifference(p, q);
                if (d > tolerance) {
                    off++;
                }
                max = Math.max(max, d);
                if ((p & 0xff) < 128 && (q & 0xff) == 0xff) {
                    missing++;   // ink in the reference, background in the image
                }
            }
        }
        System.out.println((lcd ? "LCD" : "grayscale") + ": " + missing + " pixels of ink missing, "
                + off + " pixels more than " + tolerance + " levels off, max " + max);
        if (missing > 0 || off > 0) {
            throw new RuntimeException((lcd ? "LCD" : "Grayscale")
                    + " text with glyph atlas evictions differs from the software loops: "
                    + missing + " pixels of ink missing, " + off + " pixels more than "
                    + tolerance + " levels off");
        }
        System.out.println("PASSED");
    }

    /** Draws the alphabet in every size, so that the frame needs more cells than the atlas has. */
    private static void paint(Graphics2D g, boolean lcd) {
        StringBuilder sb = new StringBuilder();
        for (char c = 33; c < 127; c++) {
            sb.append(c);
        }
        String alphabet = sb.toString();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                               : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        int y = 20;
        for (int i = 0; i < SIZES; i++) {
            Font font = new Font(Font.SANS_SERIF, i % 2 == 0 ? Font.PLAIN : Font.BOLD, 8 + i);
            g.setFont(font);
            g.drawString(alphabet.substring(0, 60), 10, y);
            g.drawString(alphabet.substring(34), 10, y + font.getSize() + 2);
            y += 2 * font.getSize() + 8;
            if (y > H - 20) {
                break;
            }
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
