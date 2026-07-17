package com.apex.maptool.ui;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Icon item đọc từ thư mục ngoài {@code IconItem/<itemId>.png} (export từ client, đã bỏ .meta).
 * Decode 1 lần → scale 26px → cache; id không có ảnh cache luôn vào missing (không dò đĩa lại).
 */
public final class ItemIcons {

    private static final int SIZE = 26;   // khớp row bảng 33px / combo
    private static final Path DIR = resolveDir();
    private static final Map<Integer, ImageIcon> CACHE = new HashMap<>();
    private static final Set<Integer> MISSING = new HashSet<>();

    private ItemIcons() {}

    /** Icon đã scale của item id — null nếu không có ảnh. */
    public static synchronized ImageIcon get(int id) {
        if (DIR == null || MISSING.contains(id)) return null;
        ImageIcon ic = CACHE.get(id);
        if (ic != null) return ic;
        Path f = DIR.resolve(id + ".png");
        if (!Files.exists(f)) { MISSING.add(id); return null; }
        try {
            BufferedImage img = ImageIO.read(f.toFile());
            if (img == null) { MISSING.add(id); return null; }
            int w = Math.max(1, img.getWidth() * SIZE / Math.max(1, img.getHeight()));
            BufferedImage scaled = new BufferedImage(w, SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, w, SIZE, null);
            g.dispose();
            ic = new ImageIcon(scaled);
            CACHE.put(id, ic);
            return ic;
        } catch (Exception e) {
            MISSING.add(id);
            return null;
        }
    }

    /** Tìm thư mục IconItem: CWD → cạnh jar → cha của jar (giống cách tìm config.properties). */
    private static Path resolveDir() {
        List<Path> cands = new ArrayList<>();
        cands.add(Paths.get("IconItem"));
        try {
            Path p = Paths.get(ItemIcons.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jarDir = Files.isDirectory(p) ? p : p.getParent();
            if (jarDir != null) {
                cands.add(jarDir.resolve("IconItem"));
                if (jarDir.getParent() != null) cands.add(jarDir.getParent().resolve("IconItem"));
            }
        } catch (Exception ignored) {}
        for (Path c : cands)
            if (Files.isDirectory(c)) {
                System.out.println("[ItemIcons] dùng " + c.toAbsolutePath());
                return c;
            }
        System.err.println("[ItemIcons] không thấy thư mục IconItem → không hiện icon item");
        return null;
    }

    /** Renderer combo/list ItemInfo kèm icon (dùng chung cho picker). */
    public static DefaultListCellRenderer listRenderer(java.util.function.ToIntFunction<Object> idOf) {
        return new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                super.getListCellRendererComponent(l, v, i, sel, foc);
                setIcon(v != null ? get(idOf.applyAsInt(v)) : null);
                setIconTextGap(8);
                setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
                return this;
            }
        };
    }
}
