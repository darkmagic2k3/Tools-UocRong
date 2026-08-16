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
 *
 * <p><b>PNG NHỎ HƠN KHUNG KHAI BÁO</b>: {@code size:W,H} ở đầu file là khung THAM CHIẾU lúc pack,
 * còn file PNG trên đĩa nhiều khi đã bị co về power-of-two (Unity "Non-Power of 2 → ToNearest").
 * Đo thật trong client: <b>54/100</b> atlas dưới {@code Assets/Textures} lệch kiểu này
 * (vd {@code CayThong} khai báo 284×360 nhưng PNG 256×256) — crop theo toạ độ gốc sẽ ném
 * "outside of Raster" và skeleton KHÔNG vẽ được gì.
 * Bản gốc spine-unity không dính vì nó lấy UV chuẩn hoá theo {@code page.width/height} rồi để GPU
 * lấy mẫu, còn ta crop bằng pixel ⇒ phải tự QUY ĐỔI mọi toạ độ region về kích thước PNG THẬT.
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
        int declW = 0, declH = 0;                 // khung khai báo 'size:' của page ĐẦU TIÊN
        // page props (có ':') tới region đầu tiên
        while (i < lines.size()) {
            String l = lines.get(i);
            if (!l.contains(":")) break; // hết page props → region name
            String t = l.trim();
            if (t.startsWith("pma:")) a.pma = t.substring(4).trim().equalsIgnoreCase("true");
            else if (t.startsWith("size:")) {
                String[] p = t.substring(5).split(",");
                if (p.length >= 2) { declW = pi(p[0]); declH = pi(p[1]); }
            }
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
        a.fitToPage(declW, declH);
        return a;
    }

    /**
     * Quy đổi toạ độ mọi region từ khung khai báo {@code declW×declH} về kích thước PNG THẬT,
     * rồi KẸP lại cho chắc chắn nằm trong ảnh (tránh {@code getSubimage} ném "outside of Raster",
     * hậu quả là cả skeleton không vẽ được gì).
     *
     * <p>Hệ số 2 trục có thể KHÁC nhau (Unity co từng trục về power-of-two riêng). Với region
     * {@code rotate:90}, ảnh nằm nghiêng trong page nên trục X LOGIC của nó chạy theo trục Y của
     * page — phải đổi chỗ hệ số, nếu không cây/thác sẽ méo theo một chiều.
     */
    private void fitToPage(int declW, int declH) {
        if (page == null) return;
        int pw = page.getWidth(), ph = page.getHeight();
        float sx = (declW > 0 && pw != declW) ? pw / (float) declW : 1f;
        float sy = (declH > 0 && ph != declH) ? ph / (float) declH : 1f;
        for (Region r : regions.values()) {
            if (sx != 1f || sy != 1f) {
                float lx = r.rotate ? sy : sx;    // hệ số cho trục X LOGIC của region
                float ly = r.rotate ? sx : sy;    // hệ số cho trục Y LOGIC
                r.x = Math.round(r.x * sx);
                r.y = Math.round(r.y * sy);
                r.w = Math.max(1, Math.round(r.w * lx));
                r.h = Math.max(1, Math.round(r.h * ly));
                r.offX *= lx; r.origW *= lx;
                r.offY *= ly; r.origH *= ly;
            }
            // kẹp footprint (vùng chiếm trong page — rotate thì đổi chỗ w/h) vào trong ảnh
            r.x = Math.max(0, Math.min(r.x, pw - 1));
            r.y = Math.max(0, Math.min(r.y, ph - 1));
            int fw = r.rotate ? r.h : r.w, fh = r.rotate ? r.w : r.h;
            fw = Math.max(1, Math.min(fw, pw - r.x));
            fh = Math.max(1, Math.min(fh, ph - r.y));
            if (r.rotate) { r.h = fw; r.w = fh; } else { r.w = fw; r.h = fh; }
            if (r.origW < r.w) r.origW = r.w;
            if (r.origH < r.h) r.origH = r.h;
        }
    }

    public Region region(String name) { return regions.get(name); }

    private static int pi(String s) { return (int) Float.parseFloat(s.trim()); }
    private static float pf(String s) { return Float.parseFloat(s.trim()); }
}
