package com.apex.maptool.spine;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Vẽ skeleton đã pose lên Graphics2D (screen space).
 * Mapping: screenX = mx + spineX*sss ; screenY = my - spineY*sss  (spine Y-up → screen Y-down).
 * Region attachment: crop atlas (rotate/trim/pma) → vẽ quad qua affine. Mesh: vẽ từng tam giác.
 *
 * <p>Renderer chỉ ĐỌC kết quả pose từ {@link SpineSkeleton} — thứ tự vẽ ({@code sk.drawOrder()}),
 * attachment đang gắn ({@code sk.slotAtt}), deform ({@code sk.meshWorldVertices}) và alpha slot
 * ({@code sk.slotAlpha}) — nên KHÔNG bao giờ đụng vào dữ liệu setup dùng chung trong
 * {@link SpineData}. Xem javadoc {@link SpineSkeleton} về chống đâm dữ liệu.
 *
 * <p>HIỆU NĂNG: mọi mảng dùng khi vẽ ({@link #wvBuf}, {@link #sxBuf}, {@link #syBuf}) là buffer
 * của renderer, cấp phát 1 lần rồi nới dần — vòng lặp vẽ 30fps với hàng chục skeleton không sinh
 * rác. {@link #triPath} / {@link #triXf} cũng tái dùng thay vì {@code new} mỗi TAM GIÁC.
 */
public final class SpineRenderer {

    /**
     * Bật/tắt hoà màu additive·screen·multiply. Tắt = vẽ SRC_OVER hết (nhanh hơn nhiều nhưng hiệu
     * ứng sáng sẽ ra mảng đen). Dùng để ĐO chi phí blend và làm lối thoát khi máy yếu.
     */
    public static volatile boolean BLEND_ENABLED = true;

    private final SpineAtlas atlas;
    private final Map<String, BufferedImage> regionCache = new HashMap<>();

    // ── buffer tái dùng (renderer dùng chung với skeleton: pose→draw đồng bộ, 1 luồng) ──
    private float[] wvBuf = new float[0];     // world vertex (x,y xen kẽ) của 1 mesh
    private double[] sxBuf = new double[0], syBuf = new double[0];   // toạ độ màn hình
    private final java.awt.geom.Path2D.Double triPath = new java.awt.geom.Path2D.Double();
    private final AffineTransform triXf = new AffineTransform();
    private final double[] corner = new double[6];   // TL,TR,BL của region attachment

    public SpineRenderer(SpineAtlas atlas) { this.atlas = atlas; }

    public void draw(Graphics2D g2, SpineData data, SpineSkeleton sk, double mx, double my, double sss) {
        draw(g2, data, sk, mx, my, sss, 1.0);
    }

    /** signX = -1 → lật ngang (quái quay mặt hướng đi). */
    public void draw(Graphics2D g2, SpineData data, SpineSkeleton sk, double mx, double my, double sss, double signX) {
        this.sssX = sss * signX;
        this.sssY = sss;
        drawInternal(g2, data, sk, mx, my);
    }

    private double sssX, sssY;

    // ── CLIP: đọc MỘT LẦN mỗi skeleton, không phải mỗi tam giác (xem drawTexTriangle) ──
    /** Clip của {@code g2} lúc bắt đầu vẽ skeleton này — mốc để khôi phục và để kiểm bao hàm. */
    private java.awt.Shape baseClip;
    /** Khung bao của {@link #baseClip} — loại sớm tam giác nằm hẳn ngoài màn hình. */
    private double clipX0, clipY0, clipX1, clipY1;
    /** Đã đụng vào clip của {@code g2} chưa (chỉ khi đó mới phải trả lại {@link #baseClip}). */
    private boolean clipDirty;

    /**
     * Duyệt slot theo THỨ TỰ VẼ do pose() quyết ({@code sk.drawOrder()} — không có draw order
     * timeline thì đúng 0..n-1, y hệt hành vi cũ) và áp ALPHA của slot cho cả cụm hình của slot đó.
     *
     * <p>Vì sao chỉ alpha mà không tô màu: đo trên asset thật (79 skeleton nhị phân của game)
     * có 325 timeline màu thì <b>0</b> timeline đổi RGB, 325 đổi alpha và 317 chạm alpha = 0 —
     * nhấp nháy/hiện-mờ là do ALPHA. Tô màu RGB thì phải sinh ảnh mới cho từng sắc độ (rất đắt,
     * phá cache region) nên không làm; {@code sk.slotRed/Green/Blue()} vẫn có sẵn nếu sau này cần.
     */
    private void drawInternal(Graphics2D g2, SpineData data, SpineSkeleton sk, double mx, double my) {
        baseClip = g2.getClip();
        clipDirty = false;
        java.awt.Rectangle cb = g2.getClipBounds();
        if (cb == null) { clipX0 = clipY0 = -1e9; clipX1 = clipY1 = 1e9; }
        else { clipX0 = cb.x; clipY0 = cb.y; clipX1 = cb.x + cb.width; clipY1 = cb.y + cb.height; }

        int[] ord = sk.drawOrder();
        int ns = data.slots.size();
        try {
            for (int k = 0; k < ord.length; k++) {
                int si = ord[k];
                if (si < 0 || si >= ns) continue;
                SpineData.Slot slot = data.slots.get(si);
                // Attachment ĐANG gắn = do pose() quyết (setup, hoặc slot attachment timeline tại time t).
                String attName = (si < sk.slotAtt.length) ? sk.slotAtt[si] : slot.attachment;
                if (attName == null) continue;
                Map<String, SpineData.Attachment> atts = data.skin.get(slot.name);
                if (atts == null) continue;
                SpineData.Attachment att = atts.get(attName);
                if (att == null) continue;
                boolean mesh = att.type.equals("mesh");
                if (!mesh && !att.type.equals("region")) continue;

                float alpha = sk.slotAlpha(si);
                if (alpha <= 0.004f) continue;                  // trong suốt hoàn toàn ⇒ khỏi vẽ

                // Slot có blend riêng (additive/screen/multiply) phải đi qua BlendComposite, kể cả
                // khi alpha = 1 — với mấy chế độ đó màu đen nghĩa là trong suốt, vẽ SRC_OVER là ra
                // mảng đen. Slot normal thì giữ nguyên đường cũ (nhanh hơn nhiều).
                Composite oldComp = g2.getComposite();
                boolean pushed = false;
                if (slot.blend != SpineData.BLEND_NORMAL && BLEND_ENABLED) {
                    pushBlend(g2, slot.blend, alpha, oldComp);
                    pushed = true;
                } else if (alpha < 1f) {
                    pushAlpha(g2, alpha, oldComp);
                    pushed = true;
                }
                try {
                    if (mesh) {
                        drawMesh(g2, sk, si, att, mx, my);      // mỗi tam giác tự đặt clip của nó
                    } else {
                        // Region vẽ NGUYÊN ảnh nên phải thấy clip GỐC, không phải clip của tam
                        // giác cuối cùng mà mesh trước đó để lại.
                        if (clipDirty) { g2.setClip(baseClip); clipDirty = false; }
                        drawRegion(g2, data, sk, slot, att, mx, my);
                    }
                } finally {
                    if (pushed) g2.setComposite(oldComp);
                }
            }
        } finally {
            // Trả clip về đúng như lúc nhận — kể cả khi ném giữa chừng, nếu không thì clip của
            // 1 tam giác sẽ rò ra ngoài và nuốt mất phần còn lại của khung hình.
            if (clipDirty) { g2.setClip(baseClip); clipDirty = false; }
        }
    }

    /**
     * Đặt AlphaComposite = alpha slot NHÂN với composite đang có, trả về composite cũ để khôi phục.
     * Phải nhân chứ không đè: {@code MapLayoutCanvas.drawSpineNode} đặt sẵn alpha 0.30 khi vẽ node
     * "tắt/lớp khác" — đè lên là node mờ bỗng hiện rõ.
     */
    private static void pushAlpha(Graphics2D g2, float alpha, Composite old) {
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp01(foldAlpha(alpha, old))));
    }

    /** Đặt composite hoà màu Spine (additive/screen/multiply), gộp cả alpha sẵn có của {@code g2}. */
    private static void pushBlend(Graphics2D g2, int blend, float alpha, Composite old) {
        g2.setComposite(new BlendComposite(blend, clamp01(foldAlpha(alpha, old))));
    }

    /** Nhân alpha của slot với alpha mà người gọi đã đặt sẵn (node "tắt/lớp khác" để 0.30). */
    private static float foldAlpha(float alpha, Composite old) {
        if (old instanceof AlphaComposite ac && ac.getRule() == AlphaComposite.SRC_OVER) return alpha * ac.getAlpha();
        return alpha;
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /** Region attachment: 3 góc → affine → vẽ ảnh. */
    private void drawRegion(Graphics2D g2, SpineData data, SpineSkeleton sk, SpineData.Slot slot,
                            SpineData.Attachment att, double mx, double my) {
        Integer bi = data.boneIdx.get(slot.bone);
        if (bi == null) return;
        int i = bi;

        BufferedImage img = region(att.path);
        if (img == null) return;

        float hx = att.width / 2f * att.scaleX;
        float hy = att.height / 2f * att.scaleY;
        float cos = cos(att.rotation), sin = sin(att.rotation);

        // 3 góc đủ cho affine: TL(-hx,+hy) TR(+hx,+hy) BL(-hx,-hy)
        corner(sk, i, att, -hx, hy, cos, sin, mx, my, 0);
        corner(sk, i, att, hx, hy, cos, sin, mx, my, 2);
        corner(sk, i, att, -hx, -hy, cos, sin, mx, my, 4);
        double tlx = corner[0], tly = corner[1], trx = corner[2], tryy = corner[3];
        double blx = corner[4], bly = corner[5];

        int iw = img.getWidth(), ih = img.getHeight();
        // affine: img(0,0)→TL, (iw,0)→TR, (0,ih)→BL
        triXf.setTransform((trx - tlx) / iw, (tryy - tly) / iw, (blx - tlx) / ih, (bly - tly) / ih, tlx, tly);
        try { g2.drawImage(img, triXf, null); } catch (Exception ignored) {}
    }

    /**
     * Mesh attachment: world vertex do {@link SpineSkeleton#meshWorldVertices} tính (ĐÃ áp deform
     * nếu pose() có) → đổi sang toạ độ màn hình → vẽ từng textured triangle.
     */
    private void drawMesh(Graphics2D g2, SpineSkeleton sk, int slotIndex,
                          SpineData.Attachment att, double mx, double my) {
        if (att.uvs == null || att.triangles == null || att.vertices == null) return;
        BufferedImage img = region(att.path);
        if (img == null) return;
        int iw = img.getWidth(), ih = img.getHeight();
        int vCount = att.uvs.length / 2;
        if (wvBuf.length < vCount * 2) wvBuf = new float[vCount * 2];
        if (sxBuf.length < vCount) { sxBuf = new double[vCount]; syBuf = new double[vCount]; }

        int got = sk.meshWorldVertices(slotIndex, att, wvBuf);
        if (got < vCount) return;                       // dữ liệu mesh hỏng ⇒ bỏ slot, không vẽ nham nhở

        double[] sx = sxBuf, sy = syBuf;
        for (int v = 0; v < vCount; v++) {
            sx[v] = mx + wvBuf[v * 2] * sssX;
            sy[v] = my - wvBuf[v * 2 + 1] * sssY;
        }

        for (int t = 0; t + 2 < att.triangles.length; t += 3) {
            int a = att.triangles[t], b = att.triangles[t + 1], c = att.triangles[t + 2];
            if (a < 0 || b < 0 || c < 0 || a >= vCount || b >= vCount || c >= vCount) continue;
            drawTexTriangle(g2, img,
                    sx[a], sy[a], sx[b], sy[b], sx[c], sy[c],
                    att.uvs[a * 2] * iw, att.uvs[a * 2 + 1] * ih,
                    att.uvs[b * 2] * iw, att.uvs[b * 2 + 1] * ih,
                    att.uvs[c * 2] * iw, att.uvs[c * 2 + 1] * ih);
        }
    }

    /**
     * Hộp bao {@code {minX, minY, maxX, maxY}} của HÌNH THẬT SỰ ĐƯỢC VẼ, theo toạ độ world của
     * skeleton (Y-up, CHƯA nhân tỉ lệ màn hình). Gọi SAU {@link SpineSkeleton#pose}.
     *
     * <p>Duyệt đúng tập slot mà {@link #drawInternal} sẽ vẽ (cùng thứ tự vẽ, cùng phép loại slot
     * trong suốt) nên hộp này là hộp của ảnh thật, không phải ước lượng. Cần vì
     * {@code skeleton.width/height} trong JSON <b>hay thiếu</b> — thiếu thì {@link SpineData} để
     * mặc định 100×100, canh theo đó là vật thể bay hẳn ra ngoài khung và người dùng thấy canvas trống.
     *
     * @return {@code null} nếu không có gì để vẽ (vd flipbook chưa gắn attachment ở thời điểm này).
     */
    public double[] worldBounds(SpineData data, SpineSkeleton sk) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean any = false;
        int[] ord = sk.drawOrder();
        int ns = data.slots.size();
        for (int k = 0; k < ord.length; k++) {
            int si = ord[k];
            if (si < 0 || si >= ns) continue;
            SpineData.Slot slot = data.slots.get(si);
            String attName = (si < sk.slotAtt.length) ? sk.slotAtt[si] : slot.attachment;
            if (attName == null) continue;
            Map<String, SpineData.Attachment> atts = data.skin.get(slot.name);
            if (atts == null) continue;
            SpineData.Attachment att = atts.get(attName);
            if (att == null) continue;
            if (sk.slotAlpha(si) <= 0.004f) continue;

            if (att.type.equals("mesh")) {
                if (att.uvs == null || att.vertices == null) continue;
                int vCount = att.uvs.length / 2;
                if (wvBuf.length < vCount * 2) wvBuf = new float[vCount * 2];
                if (sk.meshWorldVertices(si, att, wvBuf) < vCount) continue;
                for (int v = 0; v < vCount; v++) {
                    double x = wvBuf[v * 2], y = wvBuf[v * 2 + 1];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
                any = true;
            } else if (att.type.equals("region")) {
                Integer bi = data.boneIdx.get(slot.bone);
                if (bi == null) continue;
                int i = bi;
                float hx = att.width / 2f * att.scaleX, hy = att.height / 2f * att.scaleY;
                float cos = cos(att.rotation), sin = sin(att.rotation);
                for (int c = 0; c < 4; c++) {
                    float cx = (c == 0 || c == 3) ? -hx : hx;
                    float cy = (c < 2) ? hy : -hy;
                    float lx = att.x + (cx * cos - cy * sin);
                    float ly = att.y + (cx * sin + cy * cos);
                    double x = sk.a[i] * lx + sk.b[i] * ly + sk.wx[i];
                    double y = sk.c[i] * lx + sk.d[i] * ly + sk.wy[i];
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
                any = true;
            }
        }
        return any ? new double[]{minX, minY, maxX, maxY} : null;
    }

    /**
     * Vẽ 1 triangle có texture: affine map uv→screen + clip.
     *
     * <p>HAI MẸO HIỆU NĂNG ở đây gánh phần lớn chi phí vẽ Spine (Map8: 37 skeleton, ~4900 tam
     * giác/frame → từ 120 ms xuống 52 ms), KẾT QUẢ PIXEL KHÔNG ĐỔI:
     * <ol>
     *   <li><b>Loại sớm theo khung clip.</b> Tam giác nằm hẳn ngoài clip thì dù có vẽ cũng không
     *       ra pixel nào ⇒ thoát trước khi kịp dựng Path2D + Region (thứ đắt nhất).</li>
     *   <li><b>KHÔNG {@code getClip()/setClip(old)} mỗi tam giác.</b> Bản cũ mỗi tam giác đều
     *       {@code old = g2.getClip(); g2.clip(tri); …; g2.setClip(old)}. Khi node Spine BỊ XOAY
     *       ({@code MapLayoutCanvas.drawSpineNode} gọi {@code g2.rotate}) thì clip trong toạ độ
     *       người dùng là HÌNH CHỮ NHẬT XOAY — không còn là {@code Rectangle} — nên mỗi
     *       {@code setClip} phải rasterize lại cả hình: đo được 54,5 ms/frame chỉ riêng việc đó
     *       trên Map8 (Map13 không xoay nên chỉ 0,1 ms).<br>
     *       Nay {@link #baseClip} đọc 1 lần mỗi skeleton; tam giác NẰM TRỌN trong base clip thì
     *       {@code setClip(tri)} thẳng (giao với base là chính nó ⇒ y hệt kết quả cũ), còn tam
     *       giác cắt mép base mới đi đường an toàn {@code setClip(base) + clip(tri)}.
     *       {@code Shape.contains(x,y,w,h)} trả false khi không chắc ⇒ nghi ngờ là tự động rơi
     *       về đường an toàn, không bao giờ vẽ lem ra ngoài clip của người gọi.</li>
     * </ol>
     */
    private void drawTexTriangle(Graphics2D g2, BufferedImage img,
                                 double sx0, double sy0, double sx1, double sy1, double sx2, double sy2,
                                 double u0, double v0, double u1, double v1, double u2, double v2) {
        double bx0 = Math.min(sx0, Math.min(sx1, sx2)), bx1 = Math.max(sx0, Math.max(sx1, sx2));
        double by0 = Math.min(sy0, Math.min(sy1, sy2)), by1 = Math.max(sy0, Math.max(sy1, sy2));
        if (bx1 < clipX0 || bx0 > clipX1 || by1 < clipY0 || by0 > clipY1) return;   // ngoài màn hình

        double den = u0 * (v1 - v2) - u1 * (v0 - v2) + u2 * (v0 - v1);
        if (Math.abs(den) < 1e-9) return;
        // affine: s = M * uv. Solve a,c,e (for x) and b,d,f (for y).
        double a = (sx0 * (v1 - v2) - sx1 * (v0 - v2) + sx2 * (v0 - v1)) / den;
        double c = (u0 * (sx1 - sx2) - u1 * (sx0 - sx2) + u2 * (sx0 - sx1)) / den;
        double e = (u0 * (v1 * sx2 - v2 * sx1) - u1 * (v0 * sx2 - v2 * sx0) + u2 * (v0 * sx1 - v1 * sx0)) / den;
        double b = (sy0 * (v1 - v2) - sy1 * (v0 - v2) + sy2 * (v0 - v1)) / den;
        double d = (u0 * (sy1 - sy2) - u1 * (sy0 - sy2) + u2 * (sy0 - sy1)) / den;
        double f = (u0 * (v1 * sy2 - v2 * sy1) - u1 * (v0 * sy2 - v2 * sy0) + u2 * (v0 * sy1 - v1 * sy0)) / den;

        // triPath/triXf tái dùng — mesh có hàng trăm tam giác/frame, new mỗi cái là rác thuần tuý.
        triPath.reset();
        triPath.moveTo(sx0, sy0); triPath.lineTo(sx1, sy1); triPath.lineTo(sx2, sy2); triPath.closePath();
        if (baseClip == null || baseClip.contains(bx0, by0, bx1 - bx0, by1 - by0)) {
            g2.setClip(triPath);                       // trọn trong base ⇒ giao = chính nó
        } else {
            g2.setClip(baseClip);                      // cắt mép base ⇒ phải giao thật
            g2.clip(triPath);
        }
        clipDirty = true;                              // drawInternal sẽ trả clip về baseClip
        triXf.setTransform(a, b, c, d, e, f);
        try { g2.drawImage(img, triXf, null); }
        catch (Exception ignored) {}
    }

    /** 1 góc của region attachment → ghi (x,y) màn hình vào {@link #corner}[out], [out+1]. */
    private void corner(SpineSkeleton sk, int i, SpineData.Attachment att,
                        float cx, float cy, float cos, float sin, double mx, double my, int out) {
        float lx = att.x + (cx * cos - cy * sin);
        float ly = att.y + (cx * sin + cy * cos);
        float wxp = sk.a[i] * lx + sk.b[i] * ly + sk.wx[i];
        float wyp = sk.c[i] * lx + sk.d[i] * ly + sk.wy[i];
        corner[out] = mx + wxp * sssX;
        corner[out + 1] = my - wyp * sssY;
    }

    /** Crop region từ page: un-rotate (rotate), pad trim về origW×origH, un-premultiply. Cache. */
    private BufferedImage region(String name) {
        if (regionCache.containsKey(name)) return regionCache.get(name);
        SpineAtlas.Region r = atlas.region(name);
        BufferedImage out = null;
        if (r != null && atlas.page != null) {
            try {
                BufferedImage sub;
                if (r.rotate) {
                    // page footprint = (h × w) rotated; crop rồi xoay CW → (w × h)
                    sub = atlas.page.getSubimage(r.x, r.y, r.h, r.w);
                    sub = rotateCW(sub);
                } else {
                    sub = atlas.page.getSubimage(r.x, r.y, r.w, r.h);
                }
                int ow = Math.max(r.w, Math.round(r.origW));
                int oh = Math.max(r.h, Math.round(r.origH));
                out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = out.createGraphics();
                int px = Math.round(r.offX);
                int py = Math.round(oh - r.offY - r.h); // offY tính từ đáy → top-left y
                g.drawImage(sub, px, py, null);
                g.dispose();
                if (atlas.pma) unpremultiply(out);
            } catch (Exception e) {
                System.err.println("[SpineRenderer] region '" + name + "' fail: " + e.getMessage());
            }
        }
        regionCache.put(name, out);
        return out;
    }

    private static BufferedImage rotateCW(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(h, w, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                dst.setRGB(h - 1 - y, x, src.getRGB(x, y));
        return dst;
    }

    private static void unpremultiply(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24);
                if (a == 0 || a == 255) continue;
                int rr = (argb >> 16) & 0xff, gg = (argb >> 8) & 0xff, bb = argb & 0xff;
                rr = Math.min(255, rr * 255 / a);
                gg = Math.min(255, gg * 255 / a);
                bb = Math.min(255, bb * 255 / a);
                img.setRGB(x, y, (a << 24) | (rr << 16) | (gg << 8) | bb);
            }
        }
    }

    private static final float DEG = (float) (Math.PI / 180.0);
    private static float cos(float deg) { return (float) Math.cos(deg * DEG); }
    private static float sin(float deg) { return (float) Math.sin(deg * DEG); }
}
