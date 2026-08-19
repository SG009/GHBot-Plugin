package dev.ghbot.web;

import dev.ghbot.builder.VoxelModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * v0.21.34 — BlockGPT-style build preview image. Renders a VoxelModel as a
 * 2D isometric PNG server-side (no deps, uses java.awt), so the chat console
 * can show an inline image of the staged build + the 3D viewer link.
 */
public final class BuildPreviewImage {

    private static final int CELL = 6;              // px per block on the iso plane
    private static final int TOP_H = 5;             // top face vertical rise
    private static final int SIDE_W = CELL;         // side face width

    private BuildPreviewImage() {}

    /** Render a model to a PNG (isometric, color blocks, transparent bg). */
    public static byte[] render(VoxelModel model) {
        if (model == null || model.size() == 0) return null;
        // bounding box
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<Long, String> e : model.entries()) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        int w = maxX - minX + 1, d = maxZ - minZ + 1, h = maxY - minY + 1;

        // iso projection: screenW = (w+d)*SIDE_W, screenH = (w+d)*TOP_H + h*CELL + margin
        int m = 24;
        int W = (w + d) * SIDE_W + m * 2;
        int H = (w + d) * TOP_H + h * CELL + m * 2;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

        // project (x,y,z) → screen. Iso diamond: sx = (x-z)*SIDE_W/2, sy = (x+z)*TOP_H/2 - y*CELL
        // offset so the model is centered + at the bottom
        int baseSx = W / 2;
        int baseSy = H - m - h * CELL;

        // collect visible cells: iterate blocks sorted by depth (x+z then y)
        java.util.List<Map.Entry<Long, String>> es = new java.util.ArrayList<>();
        model.entries().forEach(es::add);
        es.sort((a, b) -> {
            int ax = VoxelModel.xOf(a.getKey()), az = VoxelModel.zOf(a.getKey()), ay = VoxelModel.yOf(a.getKey());
            int bx = VoxelModel.xOf(b.getKey()), bz = VoxelModel.zOf(b.getKey()), by = VoxelModel.yOf(b.getKey());
            int sa = (ax + az) * 100 + ay, sb = (bx + bz) * 100 + by;
            return Integer.compare(sa, sb);
        });

        for (var e : es) {
            int x = VoxelModel.xOf(e.getKey()), y = VoxelModel.yOf(e.getKey()), z = VoxelModel.zOf(e.getKey());
            int[] col = colorOf(e.getValue());
            int sx = baseSx + (x - z) * SIDE_W / 2;
            int sy = baseSy + (x + z) * TOP_H / 2 - y * CELL;

            // top face
            int[] top = shade(col, 1.12);
            polygon(g, top,
                    sx, sy,
                    sx + SIDE_W, sy + TOP_H / 2,
                    sx, sy + TOP_H,
                    sx - SIDE_W, sy + TOP_H / 2);
            // right side (darker)
            int[] side = shade(col, 0.75);
            polygon(g, side,
                    sx, sy + TOP_H,
                    sx + SIDE_W, sy + TOP_H / 2,
                    sx + SIDE_W, sy + TOP_H / 2 + CELL,
                    sx, sy + TOP_H + CELL);
            // left side (darkest)
            int[] dark = shade(col, 0.55);
            polygon(g, dark,
                    sx, sy + TOP_H,
                    sx, sy + TOP_H + CELL,
                    sx - SIDE_W, sy + TOP_H / 2 + CELL,
                    sx - SIDE_W, sy + TOP_H / 2);
        }
        g.dispose();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e2) {
            return null;
        }
    }

    private static void polygon(java.awt.Graphics2D g, int[] c, int... pts) {
        g.setColor(new java.awt.Color(c[0], c[1], c[2]));
        g.fillPolygon(new int[]{pts[0], pts[2], pts[4], pts[6]},
                new int[]{pts[1], pts[3], pts[5], pts[7]}, 4);
        g.setColor(new java.awt.Color(0, 0, 0, 60));
        g.drawPolygon(new int[]{pts[0], pts[2], pts[4], pts[6]},
                new int[]{pts[1], pts[3], pts[5], pts[7]}, 4);
    }

    private static int[] shade(int[] c, double f) {
        return new int[]{(int) Math.min(255, c[0] * f), (int) Math.min(255, c[1] * f), (int) Math.min(255, c[2] * f)};
    }

    /** Block name → base color (expanded map). */
    private static int[] colorOf(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.contains("deepslate")) return new int[]{90, 90, 100};
        if (n.contains("basalt")) return new int[]{75, 75, 90};
        if (n.contains("obsidian")) return new int[]{35, 30, 45};
        if (n.contains("magma")) return new int[]{190, 90, 30};
        if (n.contains("bone")) return new int[]{220, 212, 180};
        if (n.contains("redstone_lamp") || n.contains("glowstone") || n.contains("shroomlight")) return new int[]{255, 220, 140};
        if (n.contains("redstone")) return new int[]{190, 40, 30};
        if (n.contains("gold")) return new int[]{240, 200, 60};
        if (n.contains("diamond")) return new int[]{95, 230, 215};
        if (n.contains("iron")) return new int[]{210, 210, 215};
        if (n.contains("emerald")) return new int[]{70, 215, 120};
        if (n.contains("coal") || n.contains("blackstone")) return new int[]{40, 40, 45};
        if (n.contains("campfire") || n.contains("torch") || n.contains("lantern")) return new int[]{230, 150, 50};
        if (n.contains("log") || n.contains("wood") || n.contains("planks") || n.contains("fence") || n.contains("door")) {
            if (n.contains("spruce")) return new int[]{95, 70, 45};
            if (n.contains("birch")) return new int[]{200, 190, 160};
            if (n.contains("dark")) return new int[]{65, 48, 32};
            if (n.contains("jungle")) return new int[]{110, 80, 50};
            if (n.contains("acacia")) return new int[]{165, 105, 65};
            return new int[]{175, 130, 75};
        }
        if (n.contains("leaves") || n.contains("moss")) {
            if (n.contains("spruce")) return new int[]{45, 95, 45};
            if (n.contains("birch")) return new int[]{130, 170, 90};
            if (n.contains("dark")) return new int[]{55, 90, 45};
            return new int[]{80, 130, 55};
        }
        if (n.contains("glass")) return new int[]{200, 232, 255};
        if (n.contains("water")) return new int[]{70, 120, 210};
        if (n.contains("lava")) return new int[]{230, 110, 30};
        if (n.contains("sand")) return new int[]{220, 205, 160};
        if (n.contains("stone_brick") || n.contains("stone")) return new int[]{125, 130, 140};
        if (n.contains("cobblestone") || n.contains("mossy")) return new int[]{120, 125, 120};
        if (n.contains("quartz") || n.contains("concrete") && n.contains("white")) return new int[]{236, 236, 236};
        if (n.contains("brick")) return new int[]{150, 85, 75};
        if (n.contains("prismarine")) return new int[]{85, 180, 170};
        if (n.contains("slime") || n.contains("honey")) return new int[]{160, 200, 120};
        if (n.contains("wool") || n.contains("carpet")) {
            if (n.contains("red")) return new int[]{190, 60, 60};
            if (n.contains("blue")) return new int[]{70, 100, 190};
            if (n.contains("green")) return new int[]{90, 160, 90};
            if (n.contains("black")) return new int[]{40, 40, 40};
            return new int[]{210, 210, 210};
        }
        return new int[]{150, 120, 200};   // default purple-ish
    }
}
