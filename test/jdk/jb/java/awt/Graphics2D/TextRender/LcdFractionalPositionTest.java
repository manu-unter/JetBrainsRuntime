/*
 * Copyright 2026 JetBrains s.r.o.
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
 */

/*
 * @test
 * @key headful
 * @summary LCD text with fractional metrics on a GPU pipeline lands where the
 *          software loops put it. The loops move a sub-pixel-positioned glyph
 *          by a sixth of a pixel minus a half before flooring its position and
 *          choosing the third of a pixel its image is shifted by; a pipeline
 *          that floors the raw position draws every glyph a third of a pixel
 *          to a whole pixel to the right and below. Pixels where two software
 *          renderings disagree with each other (the loops are not reproducible
 *          to the pixel in this mode) are left out of the comparison.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=blend -Dsun.java2d.uiScale=1 LcdFractionalPositionTest skia 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=twopass -Dsun.java2d.uiScale=1 LcdFractionalPositionTest skia 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.lcd=accumulate -Dsun.java2d.uiScale=1 LcdFractionalPositionTest skia 32
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 LcdFractionalPositionTest metal 48
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
import java.io.File;
import javax.imageio.ImageIO;
import sun.java2d.SurfaceData;

public class LcdFractionalPositionTest {

    private static final int W = 320;
    private static final int H = 120;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int tolerance = Integer.parseInt(args[1]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        if (!LcdTextBackgroundsTest.pipelineActive(pipeline, image)) {
            return;
        }
        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        BufferedImage reference2 = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (BufferedImage r : new BufferedImage[] { reference, reference2 }) {
            Graphics2D g = r.createGraphics();
            try {
                paint(g);
            } finally {
                g.dispose();
            }
        }
        Graphics2D g = image.createGraphics();
        try {
            paint(g);
        } finally {
            g.dispose();
        }
        BufferedImage actual = image.getSnapshot();
        if (image.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        String dump = System.getProperty("test.out");
        if (dump != null) {
            ImageIO.write(reference, "png", new File(dump, "fractional-reference.png"));
            ImageIO.write(reference2, "png", new File(dump, "fractional-reference2.png"));
            ImageIO.write(actual, "png", new File(dump, "fractional-" + pipeline + ".png"));
        }
        int max = 0;
        int off = 0;
        int ink = 0;
        int missing = 0;
        int unstable = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int p = reference.getRGB(x, y);
                if (p != reference2.getRGB(x, y)) {
                    unstable++;   // the loops disagree with themselves here
                    continue;
                }
                int q = actual.getRGB(x, y);
                int d = LcdTextBackgroundsTest.maxChannelDifference(p, q);
                max = Math.max(max, d);
                if (d > tolerance) {
                    off++;
                }
                boolean inkReference = (p & 0xffffff) != 0xffffff;
                if (inkReference) {
                    ink++;
                    if ((q & 0xffffff) == 0xffffff) {
                        missing++;
                    }
                }
            }
        }
        System.out.println("max " + max + " levels, " + off + " pixels beyond " + tolerance + ", "
                + missing + " of " + ink + " ink pixels missing, " + unstable + " pixels unstable in the loops");
        if (off > 0) {
            throw new RuntimeException("LCD text with fractional metrics differs from the software loops: "
                    + off + " pixels beyond " + tolerance + " levels (max " + max + ")");
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setColor(Color.BLACK);
        g.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));
        // every third of a pixel, and positions the sheet found: the text is
        // proportionally spaced so each glyph lands on its own fraction
        float[] xs = { 4f, 4.25f, 4.4f, 4.5f, 4.7f, 4.9f };
        for (int i = 0; i < xs.length; i++) {
            g.drawString("Hamburgefonstiv ffi fl Wq AVfi 0123", xs[i], 15 + i * 18 + (i % 2) * 0.5f);
        }
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        g.drawString("if (x <= y) { return ffi; } // 0.33", 4.33f, 115);
    }
}
