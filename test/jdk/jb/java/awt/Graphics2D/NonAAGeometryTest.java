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
 * @summary Non-antialiased geometry on a GPU pipeline is pixel exact:
 *          every pixel is either the background or the fill colour, with or
 *          without multisampling (Skia), and the pixels chosen agree with the
 *          software loops up to the edge pixels where the two rasterization
 *          rules differ. With multisampling, Graphite multisamples every
 *          draw of a pass as soon as one draw in it needs it (an antialiased
 *          general path rasterized by Skia, the last column of the sheet) and
 *          would give the edges of rotated fills, lines and fractional blits
 *          four coverage levels.
 * @requires (os.family == "mac" | os.family == "windows")
 * @modules java.desktop/sun.java2d
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.msaa=1 -Dsun.java2d.uiScale=1 NonAAGeometryTest skia
 * @run main/othervm -Dsun.java2d.skia=true -Dsun.java2d.skia.msaa=4 -Dsun.java2d.skia.shapes=skia -Dsun.java2d.uiScale=1 NonAAGeometryTest skia
 * @run main/othervm -Dsun.java2d.metal=true -Dsun.java2d.uiScale=1 NonAAGeometryTest metal
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import sun.java2d.SurfaceData;

public class NonAAGeometryTest {

    private static final int CELL = 64;
    private static final int BACKGROUND = 0xFFFFFF;
    private static final int FILL = 0x2040C0;
    // edge pixels where Skia's pixel-centre rule and the software loops may pick differently
    private static final int EDGE_TOLERANCE = 200;

    public static void main(String[] args) throws Exception {
        String pipeline = args[0];
        GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration();
        AffineTransform[] transforms = {
            new AffineTransform(),
            AffineTransform.getTranslateInstance(0.5, 0.5),
            AffineTransform.getScaleInstance(1.5, 1.5),
            AffineTransform.getRotateInstance(0.3, CELL / 2.0, CELL / 2.0),
            AffineTransform.getShearInstance(0.3, 0),
        };
        int shapes = 6;   // plus the antialiased column that is not checked
        int w = CELL * (shapes + 1);
        int h = CELL * transforms.length;
        VolatileImage image = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
        if (!pipelineActive(pipeline, image)) {
            return;
        }
        BufferedImage reference = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = reference.createGraphics();
        try {
            paint(g, transforms, shapes);
        } finally {
            g.dispose();
        }
        g = image.createGraphics();
        try {
            paint(g, transforms, shapes);
        } finally {
            g.dispose();
        }
        BufferedImage actual = image.getSnapshot();
        if (image.contentsLost()) {
            throw new RuntimeException("Volatile image contents were lost");
        }
        StringBuilder failures = new StringBuilder();
        for (int row = 0; row < transforms.length; row++) {
            for (int col = 0; col < shapes; col++) {
                int partial = 0;
                int differing = 0;
                for (int y = row * CELL; y < (row + 1) * CELL; y++) {
                    for (int x = col * CELL; x < (col + 1) * CELL; x++) {
                        int q = actual.getRGB(x, y) & 0xffffff;
                        if (q != BACKGROUND && q != FILL) {
                            partial++;
                        }
                        if (q != (reference.getRGB(x, y) & 0xffffff)) {
                            differing++;
                        }
                    }
                }
                System.out.println("shape " + col + " transform " + row + ": " + partial
                        + " pixels of a third colour, " + differing + " pixels differ from software");
                if (partial > 0 || differing > EDGE_TOLERANCE) {
                    failures.append("shape ").append(col).append(" transform ").append(row)
                            .append(" (").append(partial).append(" partial, ").append(differing)
                            .append(" differing) ");
                }
            }
        }
        if (failures.length() > 0) {
            throw new RuntimeException("Non-antialiased geometry is not pixel exact: " + failures);
        }
        System.out.println("PASSED");
    }

    private static void paint(Graphics2D g, AffineTransform[] transforms, int shapes) {
        g.setColor(new Color(BACKGROUND));
        g.fillRect(0, 0, CELL * (shapes + 1), CELL * transforms.length);
        BufferedImage source = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
        Graphics2D sg = source.createGraphics();
        sg.setColor(new Color(FILL));
        sg.fillRect(0, 0, 24, 24);
        sg.dispose();
        for (int row = 0; row < transforms.length; row++) {
            // an antialiased general path in the same pass, so that a multisampled pass is multisampled
            Graphics2D ag = (Graphics2D) g.create(shapes * CELL, row * CELL, CELL, CELL);
            try {
                ag.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                ag.setColor(new Color(FILL));
                Path2D star = new Path2D.Float();
                for (int i = 0; i < 10; i++) {
                    double r = i % 2 == 0 ? 26 : 11;
                    double a = i * Math.PI / 5;
                    if (i == 0) {
                        star.moveTo(32 + r * Math.sin(a), 32 - r * Math.cos(a));
                    } else {
                        star.lineTo(32 + r * Math.sin(a), 32 - r * Math.cos(a));
                    }
                }
                star.closePath();
                ag.fill(star);
            } finally {
                ag.dispose();
            }
            for (int col = 0; col < shapes; col++) {
                Graphics2D cg = (Graphics2D) g.create(col * CELL, row * CELL, CELL, CELL);
                try {
                    cg.transform(transforms[row]);
                    cg.setColor(new Color(FILL));
                    switch (col) {
                        case 0 -> cg.fillRect(12, 12, 30, 24);
                        case 1 -> cg.drawRect(12, 12, 30, 24);
                        case 2 -> cg.drawLine(8, 10, 50, 40);
                        case 3 -> {
                            cg.setStroke(new BasicStroke(5f));
                            cg.drawLine(8, 40, 50, 10);
                        }
                        case 4 -> cg.fillPolygon(new int[] { 10, 50, 30 }, new int[] { 48, 44, 10 }, 3);
                        case 5 -> cg.drawImage(source, 14, 14, null);
                        default -> throw new IllegalStateException();
                    }
                } finally {
                    cg.dispose();
                }
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
        int g = Math.abs(((p >> 8) & 0xff) - ((q >> 8) & 0xff));
        int b = Math.abs((p & 0xff) - (q & 0xff));
        return Math.max(r, Math.max(g, b));
    }
}
