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
 * @summary A frame with more distinct glyphs than the glyph atlas has cells
 *          must not cost much more than a frame that fits: the atlas evicts
 *          cells whose last use is already submitted without stalling or
 *          flushing per glyph. The frame time of 1,500 distinct glyphs is
 *          compared with that of 600; the software loops and the Metal
 *          pipeline sit at 2 to 3 times, a flush per evicted glyph at
 *          hundreds. The limit is wider on Windows, where the Dawn upload
 *          path costs more per row than Metal's and a paravirtualised GPU
 *          measured 8 to 11 times.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 GlyphAtlasEvictionThroughputTest skia gray
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 GlyphAtlasEvictionThroughputTest skia lcd
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 GlyphAtlasEvictionThroughputTest metal gray
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
import java.util.Arrays;
import sun.java2d.SurfaceData;

public class GlyphAtlasEvictionThroughputTest {

    private static final int W = 1600;
    private static final int H = 1000;
    private static final int FITTING = 600;
    private static final int EVICTING = 1500;
    private static final double MAX_RATIO =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows") ? 16.0 : 6.0;
    private static final int WARMUP = 20;
    private static final int FRAMES = 60;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        boolean lcd = args[1].equals("lcd");
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        if (!pipelineActive(pipeline, image)) {
            return;
        }
        double fitting = medianFrameMillis(image, FITTING, lcd);
        double evicting = medianFrameMillis(image, EVICTING, lcd);
        double ratio = evicting / fitting;
        System.out.printf("%s: %d glyphs %.2f ms, %d glyphs %.2f ms, ratio %.1f%n",
                          lcd ? "LCD" : "grayscale", FITTING, fitting, EVICTING, evicting, ratio);
        if (ratio > MAX_RATIO) {
            throw new RuntimeException("A frame that evicts atlas cells costs " + String.format("%.1f", ratio)
                    + " times a frame that fits (limit " + MAX_RATIO + ")");
        }
        System.out.println("PASSED");
    }

    private static double medianFrameMillis(VolatileImage image, int distinct, boolean lcd) {
        StringBuilder sb = new StringBuilder();
        for (char c = 33; c < 127; c++) {
            sb.append(c);
        }
        String alphabet = sb.toString();
        int sizes = (distinct + 93) / 94;
        Font[] fonts = new Font[sizes];
        for (int i = 0; i < sizes; i++) {
            fonts[i] = new Font(Font.SANS_SERIF, i % 2 == 0 ? Font.PLAIN : Font.BOLD, 9 + i);
        }
        BufferedImage sync = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        long[] times = new long[FRAMES];
        for (int frame = 0; frame < WARMUP + FRAMES; frame++) {
            long start = System.nanoTime();
            Graphics2D g = image.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, W, H);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                   lcd ? RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB
                                       : RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(Color.BLACK);
                int y = 30;
                int drawn = 0;
                for (int i = 0; i < sizes && drawn < distinct; i++) {
                    g.setFont(fonts[i]);
                    int n = Math.min(94, distinct - drawn);
                    g.drawString(alphabet.substring(0, Math.min(47, n)), 10, y);
                    if (n > 47) {
                        g.drawString(alphabet.substring(47, n), 10, y + fonts[i].getSize() + 2);
                    }
                    drawn += n;
                    y += 2 * fonts[i].getSize() + 6;
                    if (y > H - 20) {
                        y = 30;
                    }
                }
            } finally {
                g.dispose();
            }
            // completes the frame: a one-pixel readback
            Graphics2D sg = sync.createGraphics();
            try {
                sg.drawImage(image, 0, 0, 1, 1, 5, 5, 6, 6, null);
            } finally {
                sg.dispose();
            }
            sync.getRGB(0, 0);
            if (frame >= WARMUP) {
                times[frame - WARMUP] = System.nanoTime() - start;
            }
        }
        Arrays.sort(times);
        return times[FRAMES / 2] / 1e6;
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
}
