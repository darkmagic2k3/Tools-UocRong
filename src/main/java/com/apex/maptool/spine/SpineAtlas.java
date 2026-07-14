package com.apex.maptool.spine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse libGDX atlas (.atlas.txt) của Spine 4.2 + load page PNG.
 * Region: bounds:x,y,w,h | offsets:offX,offY,origW,origH | rotate:90/true/false
 */
public final class SpineAtlas {

    public static final class Region {
        public String name;
        public int x, y, w, h;          // vùng trong atlas page
        public boolean rotate;          // stored rotated 90° CW trong atlas
        public float offX, offY;        // offset trim (gốc dưới-trái vùng nguyên)
        public float origW, origH;      // kích thước nguyên (chưa trim)
    }

    public BufferedImage page;
    public boolean pma;                 // premultiplied alpha
    public final Map<String, Region> regions = new HashMap<>();

    public static SpineAtlas load(Path atlasFile) throws Exception {
        SpineAtlas a = new SpineAtlas();
        List<String> lines = Files.readAllLines(atlasFile);
        int i = 0;
        // dòng 0 = tên page png
        String pageName = lines.get(i++).trim();
        a.page = ImageIO.read(atlasFile.resolveSibling(pageName).toFile());
        // page props (có ':') tới region đầu tiên
        while (i < lines.size()) {
            String l = lines.get(i);
            if (!l.contains(":")) break; // hết page props → region name
            String t = l.trim();
            if (t.startsWith("pma:")) a.pma = t.substring(4).trim().equalsIgnoreCase("true");
            i++;
        }
        // regions
        while (i < lines.size()) {
            String name = lines.get(i++).trim();
            if (name.isEmpty()) continue;
            Region r = new Region();
            r.name = name;
            // đọc props tới region kế (dòng không ':')
            while (i < lines.size() && lines.get(i).contains(":")) {
                String[] kv = lines.get(i++).trim().split(":", 2);
                String k = kv[0].trim(), v = kv[1].trim();
                switch (k) {
                    case "bounds" -> {
                        String[] p = v.split(",");
                        r.x = pi(p[0]); r.y = pi(p[1]); r.w = pi(p[2]); r.h = pi(p[3]);
                    }
                    case "offsets" -> {
                        String[] p = v.split(",");
                        r.offX = pf(p[0]); r.offY = pf(p[1]); r.origW = pf(p[2]); r.origH = pf(p[3]);
                    }
                    case "rotate" -> r.rotate = v.equals("90") || v.equalsIgnoreCase("true");
                    // xy/size (format cũ) fallback
                    case "xy" -> { String[] p = v.split(","); r.x = pi(p[0]); r.y = pi(p[1]); }
                    case "size" -> { String[] p = v.split(","); r.w = pi(p[0]); r.h = pi(p[1]); }
                    case "orig" -> { String[] p = v.split(","); r.origW = pf(p[0]); r.origH = pf(p[1]); }
                    case "offset" -> { String[] p = v.split(","); r.offX = pf(p[0]); r.offY = pf(p[1]); }
                    default -> {}
                }
            }
            if (r.origW == 0) r.origW = r.w;
            if (r.origH == 0) r.origH = r.h;
            a.regions.put(name, r);
        }
        return a;
    }

    public Region region(String name) { return regions.get(name); }

    private static int pi(String s) { return (int) Float.parseFloat(s.trim()); }
    private static float pf(String s) { return Float.parseFloat(s.trim()); }
}
