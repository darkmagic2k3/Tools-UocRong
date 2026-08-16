package com.apex.maptool.spine;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;

/**
 * Render MỘT skeleton ra PNG để soi bằng mắt — dùng nghiệm thu những thứ chỉ nhìn mới thấy
 * (mảng đen do sai blend mode, ảnh lệch, mesh méo).
 *
 * <p>Nền vẽ là **xanh trời**, không phải trắng hay trong suốt: additive/screen cộng vào nền, nền
 * trắng thì cháy hết còn nền trong suốt thì không thấy gì. Xanh trời cũng đúng bối cảnh thật của
 * tia nắng trên map.
 *
 * <p>{@code noBlend = true} ép mọi slot về blend normal — để dựng lại đúng ảnh SAI của bản cũ mà
 * so, chứ không phải nhớ lại bằng mô tả.
 */
public final class SpineSnap {

    private SpineSnap() { }

    public static int run(String folder, String outPng, float t, boolean noBlend) {
        try {
            SpineCharacter sc = SpineCharacter.load(Paths.get(folder));
            if (sc == null) {
                System.err.println("[spinesnap] không nạp được skeleton ở " + folder);
                return 3;
            }
            int nAdd = 0, nScr = 0, nMul = 0;
            for (SpineData.Slot s : sc.data.slots) {
                switch (s.blend) {
                    case SpineData.BLEND_ADDITIVE -> nAdd++;
                    case SpineData.BLEND_SCREEN -> nScr++;
                    case SpineData.BLEND_MULTIPLY -> nMul++;
                    default -> { }
                }
            }
            if (noBlend) for (SpineData.Slot s : sc.data.slots) s.blend = SpineData.BLEND_NORMAL;

            int w = 900, h = 620;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            // nền xanh trời (thấy được cả phần cộng sáng lẫn phần bị đè đen)
            g.setColor(new Color(0x5FB8E8));
            g.fillRect(0, 0, w, h);

            SpineData.Animation anim = sc.data.animations.values().stream().findFirst().orElse(null);
            // Canh theo HỘP BAO HÌNH THẬT, không theo skeleton.width/height (hay thiếu → 100×100).
            boolean real = sc.renderFit(g, w, h, 30, anim);
            if (t > 0) {                       // muốn xem đúng mốc t thì pose lại rồi vẽ đè
                g.setColor(new Color(0x5FB8E8));
                g.fillRect(0, 0, w, h);
                sc.skeleton.pose(anim, t);
                double[] b = sc.renderer.worldBounds(sc.data, sc.skeleton);
                if (b == null) b = new double[]{sc.data.skelX, sc.data.skelY,
                        sc.data.skelX + sc.data.skelWidth, sc.data.skelY + sc.data.skelHeight};
                double s = Math.max(0.001, Math.min((w - 60.0) / Math.max(1e-3, b[2] - b[0]),
                                                    (h - 60.0) / Math.max(1e-3, b[3] - b[1])));
                sc.renderer.draw(g, sc.data, sc.skeleton,
                        w / 2.0 - (b[0] + b[2]) / 2 * s, h / 2.0 + (b[1] + b[3]) / 2 * s, s, 1.0);
            }
            g.dispose();
            ImageIO.write(img, "png", new File(outPng));

            System.out.println("[spinesnap] " + folder
                    + " | slot=" + sc.data.slots.size() + " (additive=" + nAdd + " screen=" + nScr + " multiply=" + nMul + ")"
                    + (noBlend ? " [ÉP VỀ NORMAL]" : "")
                    + " | anim=" + (anim == null ? "-" : "có") + " t=" + t + " → " + outPng);
            return 0;
        } catch (Exception e) {
            System.err.println("[spinesnap] lỗi: " + e);
            e.printStackTrace();
            return 1;
        }
    }
}
