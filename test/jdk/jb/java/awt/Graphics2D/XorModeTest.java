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
 * @summary XOR mode on a GPU pipeline: fills, lines and image blits are
 *          XORed with the destination like the software loops do it
 *          (dst ^ ((color ^ xorColor) * coverage) for colours, dst ^ pixel ^
 *          xorColor for images). Text under XOR is left out: the software
 *          loops XOR it with binary coverage, the GPU pipelines with their
 *          antialiased coverage.
 *          Metal has no run line: its XOR differs from the loops across
 *          whole fills.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 XorModeTest skia
 */

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import sun.java2d.SurfaceData;

public class XorModeTest {

    private static final int W = 200;
    private static final int H = 120;

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
        int differing = 0;
        int max = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int d = maxChannelDifference(reference.getRGB(x, y), actual.getRGB(x, y));
                if (d > 0) {
                    differing++;
                }
                max = Math.max(max, d);
            }
        }
        System.out.println(differing + " pixels differ from the software loops, max " + max + " levels");
        if (differing > 0) {
            throw new RuntimeException("XOR rendering differs from the software loops: "
                    + differing + " pixels, max " + max + " levels");
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D g) {
        // a background with a dark band, so that the XOR result depends on the destination
        g.setColor(new Color(0xF0F0F0));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(0x203040));
        g.fillRect(0, 40, W, 40);
        BufferedImage source = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
        Graphics2D sg = source.createGraphics();
        sg.setColor(new Color(255, 200, 0));
        sg.fillRect(0, 0, 40, 40);
        sg.setColor(new Color(0, 120, 255, 128));
        sg.fillOval(8, 8, 24, 24);
        sg.dispose();

        g.setXORMode(new Color(0x00FF80));
        g.setColor(new Color(0xC03030));
        g.fillRect(10, 20, 50, 80);
        g.fillOval(70, 20, 50, 80);
        g.drawRect(130, 20, 50, 80);
        g.drawLine(10, 10, 190, 110);
        g.setStroke(new BasicStroke(5f));
        g.drawLine(190, 10, 10, 110);
        g.setStroke(new BasicStroke(1f));
        g.fillPolygon(new int[] { 20, 60, 40 }, new int[] { 100, 100, 60 }, 3);
        g.drawImage(source, 140, 70, null);
        // an opaque source image: dst ^ pixel ^ xorColor
        BufferedImage opaque = new BufferedImage(30, 30, BufferedImage.TYPE_INT_RGB);
        sg = opaque.createGraphics();
        sg.setColor(new Color(0x4080C0));
        sg.fillRect(0, 0, 30, 30);
        sg.dispose();
        g.drawImage(opaque, 100, 2, null);
        // XOR applied twice restores the destination
        g.fillRect(150, 100, 40, 15);
        g.fillRect(150, 100, 40, 15);
        g.setPaintMode();
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
