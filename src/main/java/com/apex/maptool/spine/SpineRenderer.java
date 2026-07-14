package com.apex.maptool.spine;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Vẽ skeleton đã pose lên Graphics2D (screen space).
 * Mapping: screenX = mx + spineX*sss ; screenY = my - spineY*sss  (spine Y-up → screen Y-down).
 * Region attachment: crop atlas (rotate/trim/pma) → vẽ quad qua affine. Mesh: bỏ qua (v1).
 */
public final class SpineRenderer {

    private final SpineAtlas atlas;
    private final Map<String, BufferedImage> regionCache = new HashMap<>();

    public SpineRenderer(SpineAtlas atlas) { this.atlas = atlas; }

    public void draw(Graphics2D g2, SpineData data, SpineSkeleton sk, double mx, double my, double sss) {
        draw(g2, data, sk, mx, my, sss, 1.0);
    }

    /** signX = -1 → lật ngang (quái quay mặt hướng đi). */
    public void draw(Graphics2D g2, SpineData data, SpineSkeleton sk, double mx, double my, double sss, double signX) {
        this.sssX = sss * signX;
        this.sssY = sss;
        drawInternal(g2, data, sk, mx, my, sss);
    }

    private double sssX, sssY;

    private void drawInternal(Graphics2D g2, SpineData data, SpineSkeleton sk, double mx, double my, double sss) {
        for (SpineData.Slot slot : data.slots) {
            if (slot.attachment == null) continue;
            Map<String, SpineData.Attachment> atts = data.skin.get(slot.name);
            if (atts == null) continue;
            SpineData.Attachment att = atts.get(slot.attachment);
            if (att == null) continue;

            if (att.type.equals("mesh")) { drawMesh(g2, data, sk, slot, att, mx, my, sss); continue; }
            if (!att.type.equals("region")) continue;

            Integer bi = data.boneIdx.get(slot.bone);
            if (bi == null) continue;
            int i = bi;

            BufferedImage img = region(att.path);
            if (img == null) continue;

            float hx = att.width / 2f * att.scaleX;
            float hy = att.height / 2f * att.scaleY;
            float cos = cos(att.rotation), sin = sin(att.rotation);

            // 3 góc đủ cho affine: TL(-hx,+hy) TR(+hx,+hy) BL(-hx,-hy)
            double[] tl = corner(sk, i, att, -hx, hy, cos, sin, mx, my, sss);
            double[] tr = corner(sk, i, att, hx, hy, cos, sin, mx, my, sss);
            double[] bl = corner(sk, i, att, -hx, -hy, cos, sin, mx, my, sss);

            int iw = img.getWidth(), ih = img.getHeight();
            // affine: img(0,0)→TL, (iw,0)→TR, (0,ih)→BL
            double m00 = (tr[0] - tl[0]) / iw;
            double m10 = (tr[1] - tl[1]) / iw;
            double m01 = (bl[0] - tl[0]) / ih;
            double m11 = (bl[1] - tl[1]) / ih;
            AffineTransform at = new AffineTransform(m00, m10, m01, m11, tl[0], tl[1]);
            try { g2.drawImage(img, at, null); } catch (Exception ignored) {}
        }
    }

    /** Mesh attachment: tính world vertex (weighted/non-weighted) → vẽ textured triangle. */
    private void drawMesh(Graphics2D g2, SpineData data, SpineSkeleton sk, SpineData.Slot slot,
                          SpineData.Attachment att, double mx, double my, double sss) {
        if (att.uvs == null || att.triangles == null || att.vertices == null) return;
        BufferedImage img = region(att.path);
        if (img == null) return;
        int iw = img.getWidth(), ih = img.getHeight();
        int vCount = att.uvs.length / 2;
        double[] sx = new double[vCount], sy = new double[vCount];

        boolean weighted = att.vertices.length != vCount * 2;
        if (!weighted) {
            Integer bi = data.boneIdx.get(slot.bone);
            if (bi == null) return;
            int i = bi;
            for (int v = 0; v < vCount; v++) {
                float lx = att.vertices[v * 2], ly = att.vertices[v * 2 + 1];
                float wxp = sk.a[i] * lx + sk.b[i] * ly + sk.wx[i];
                float wyp = sk.c[i] * lx + sk.d[i] * ly + sk.wy[i];
                sx[v] = mx + wxp * sssX; sy[v] = my - wyp * sssY;
            }
        } else {
            int idx = 0;
            for (int v = 0; v < vCount; v++) {
                int n = (int) att.vertices[idx++];
                double wx = 0, wy = 0;
                for (int k = 0; k < n; k++) {
                    int bone = (int) att.vertices[idx++];
                    float vx = att.vertices[idx++], vy = att.vertices[idx++], wt = att.vertices[idx++];
                    if (bone < 0 || bone >= sk.a.length) continue;
                    double pwx = sk.a[bone] * vx + sk.b[bone] * vy + sk.wx[bone];
                    double pwy = sk.c[bone] * vx + sk.d[bone] * vy + sk.wy[bone];
                    wx += pwx * wt; wy += pwy * wt;
                }
                sx[v] = mx + wx * sssX; sy[v] = my - wy * sssY;
            }
        }

        for (int t = 0; t + 2 < att.triangles.length; t += 3) {
            int a = att.triangles[t], b = att.triangles[t + 1], c = att.triangles[t + 2];
            drawTexTriangle(g2, img,
                    sx[a], sy[a], sx[b], sy[b], sx[c], sy[c],
                    att.uvs[a * 2] * iw, att.uvs[a * 2 + 1] * ih,
                    att.uvs[b * 2] * iw, att.uvs[b * 2 + 1] * ih,
                    att.uvs[c * 2] * iw, att.uvs[c * 2 + 1] * ih);
        }
    }

    /** Vẽ 1 triangle có texture: affine map uv→screen + clip. */
    private void drawTexTriangle(Graphics2D g2, BufferedImage img,
                                 double sx0, double sy0, double sx1, double sy1, double sx2, double sy2,
                                 double u0, double v0, double u1, double v1, double u2, double v2) {
        double den = u0 * (v1 - v2) - u1 * (v0 - v2) + u2 * (v0 - v1);
        if (Math.abs(den) < 1e-9) return;
        // affine: s = M * uv. Solve a,c,e (for x) and b,d,f (for y).
        double a = (sx0 * (v1 - v2) - sx1 * (v0 - v2) + sx2 * (v0 - v1)) / den;
        double c = (u0 * (sx1 - sx2) - u1 * (sx0 - sx2) + u2 * (sx0 - sx1)) / den;
        double e = (u0 * (v1 * sx2 - v2 * sx1) - u1 * (v0 * sx2 - v2 * sx0) + u2 * (v0 * sx1 - v1 * sx0)) / den;
        double b = (sy0 * (v1 - v2) - sy1 * (v0 - v2) + sy2 * (v0 - v1)) / den;
        double d = (u0 * (sy1 - sy2) - u1 * (sy0 - sy2) + u2 * (sy0 - sy1)) / den;
        double f = (u0 * (v1 * sy2 - v2 * sy1) - u1 * (v0 * sy2 - v2 * sy0) + u2 * (v0 * sy1 - v1 * sy0)) / den;

        java.awt.geom.Path2D.Double tri = new java.awt.geom.Path2D.Double();
        tri.moveTo(sx0, sy0); tri.lineTo(sx1, sy1); tri.lineTo(sx2, sy2); tri.closePath();
        java.awt.Shape old = g2.getClip();
        g2.clip(tri);
        try { g2.drawImage(img, new AffineTransform(a, b, c, d, e, f), null); }
        catch (Exception ignored) {}
        g2.setClip(old);
    }

    private double[] corner(SpineSkeleton sk, int i, SpineData.Attachment att,
                            float cx, float cy, float cos, float sin, double mx, double my, double sss) {
        float lx = att.x + (cx * cos - cy * sin);
        float ly = att.y + (cx * sin + cy * cos);
        float wxp = sk.a[i] * lx + sk.b[i] * ly + sk.wx[i];
        float wyp = sk.c[i] * lx + sk.d[i] * ly + sk.wy[i];
        return new double[]{mx + wxp * sssX, my - wyp * sssY};
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
