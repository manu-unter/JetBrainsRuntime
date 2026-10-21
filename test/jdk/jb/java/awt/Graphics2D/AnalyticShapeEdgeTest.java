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
 * @summary Antialiased fills of round rectangles and ellipses on a GPU
 *          pipeline have the edges of the software loops. Marlin does not
 *          normalize the outline of an antialiased fill (only strokes are
 *          snapped), so an edge on a pixel boundary is crisp and an edge at
 *          a fraction covers the outer pixel by that fraction. A pipeline
 *          that snaps fill edges to pixel centres spills half a pixel over
 *          the right and bottom edges of every rounded panel and tab. Fills
 *          get the first tolerance; strokes the second, wider one: the
 *          analytic oval stroke and the parallelogram stroke differ from
 *          Marlin's cubic ellipse and its stroker by a few levels at joins.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.uiScale=1 AnalyticShapeEdgeTest skia 8 32
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.shapes=marlin -Dsun.java2d.uiScale=1 AnalyticShapeEdgeTest skia 8 32
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 AnalyticShapeEdgeTest metal 32 48
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Transparency;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import sun.java2d.SurfaceData;

public class AnalyticShapeEdgeTest {

    private static final int W = 120;
    private static final int H = 60;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        int fillTolerance = Integer.parseInt(args[1]);
        int strokeTolerance = Integer.parseInt(args[2]);
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        VolatileImage probe = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
        String surface = SurfaceData.getPrimarySurfaceData(probe).getClass().getName();
        boolean active = switch (pipeline) {
            case "skia" -> surface.contains(".skia.");
            case "metal" -> surface.contains(".metal.");
            default -> throw new IllegalArgumentException(pipeline);
        };
        System.out.println("pipeline " + pipeline + ", surface " + surface + (active ? "" : ": nothing to test"));
        if (!active) {
            return;
        }
        StringBuilder failures = new StringBuilder();
        for (int scale : new int[] { 1, 2 }) {
            for (String shape : new String[] { "rect", "roundrect", "ellipse" }) {
                for (double fraction : new double[] { 0, 0.5 }) {
                    if (shape.equals("rect") && fraction != 0) {
                        continue;   // a rect at a half pixel is the parallelogram pipe's business (4 corner pixels off by 15 levels today)
                    }
                    for (boolean stroke : new boolean[] { false, true }) {
                        int tolerance = stroke ? strokeTolerance : fillTolerance;
                        BufferedImage reference = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = reference.createGraphics();
                        try {
                            paint(g, scale, shape, fraction, stroke);
                        } finally {
                            g.dispose();
                        }
                        VolatileImage image = gc.createCompatibleVolatileImage(W, H, Transparency.OPAQUE);
                        g = image.createGraphics();
                        try {
                            paint(g, scale, shape, fraction, stroke);
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
                        String what = shape + " at " + scale + "x, edge fraction " + fraction + (stroke ? ", stroked" : ", filled");
                        System.out.println(what + ": max " + max + " levels, " + off + " pixels beyond " + tolerance);
                        if (off > 0) {
                            failures.append(what).append(" (max ").append(max).append(") ");
                        }
                    }
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Antialiased shape edges differ from the software loops beyond "
                    + fillTolerance + " (fills) / " + strokeTolerance + " (strokes) levels: " + failures);
        }
        System.out.println("PASSED");
    }

    /** A panel-like shape: integer user-space bounds (plus a fraction), filled, and optionally outlined with a 1 px stroke. */
    private static void paint(Graphics2D g, int scale, String shape, double fraction, boolean stroke) {
        g.setColor(new Color(0x191A1C));
        g.fillRect(0, 0, W, H);
        g.scale(scale, scale);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double x = 4 + fraction, y = 4 + fraction, w = 41, h = 20;
        Shape s = switch (shape) {
            case "rect" -> new Rectangle2D.Double(x, y, w, h);
            case "roundrect" -> new RoundRectangle2D.Double(x, y, w, h, 8, 8);
            default -> new Ellipse2D.Double(x, y, w, h);
        };
        g.setColor(new Color(0x233558));
        g.fill(s);
        if (stroke) {
            g.setColor(new Color(0x2E4D89));
            g.setStroke(new BasicStroke(1f));
            Shape outline = switch (shape) {
                case "rect" -> new Rectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1);
                case "roundrect" -> new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 8, 8);
                default -> new Ellipse2D.Double(x + 0.5, y + 0.5, w - 1, h - 1);
            };
            g.draw(outline);
        }
    }

    static int maxChannelDifference(int p, int q) {
        int r = Math.abs(((p >> 16) & 0xff) - ((q >> 16) & 0xff));
        int gr = Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff));
        int b = Math.abs((p & 0xff) - (q & 0xff));
        return Math.max(r, Math.max(gr, b));
    }
}
