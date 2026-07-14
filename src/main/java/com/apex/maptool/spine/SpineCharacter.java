package com.apex.maptool.spine;

import java.awt.Graphics2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 1 nhân vật Spine: data + atlas + renderer + skeleton. Tự tìm .json/.atlas.txt/.png trong folder.
 * render() pose theo anim Idle tại time t rồi vẽ.
 */
public final class SpineCharacter {
    public final SpineData data;
    public final SpineAtlas atlas;
    public final SpineRenderer renderer;
    public final SpineSkeleton skeleton;
    public float assetScale = 0.01f;   // SkeletonDataAsset.scale (Spine unit → Unity unit)

    private SpineCharacter(SpineData data, SpineAtlas atlas) {
        this.data = data;
        this.atlas = atlas;
        this.renderer = new SpineRenderer(atlas);
        this.skeleton = new SpineSkeleton(data);
    }

    /** Chiều cao thật (world unit) = skelHeight × assetScale. (ratio SetSize ~0.9 → user tune Cỡ). */
    public float worldHeight() { return data.skelHeight * assetScale; }

    /** Load từ folder (chứa .json + .atlas.txt + .png). null nếu thiếu file. */
    public static SpineCharacter load(Path folder) {
        try {
            if (!Files.isDirectory(folder)) return null;
            Path json = null, atlasTxt = null;
            try (Stream<Path> s = Files.list(folder)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.endsWith(".json")) json = p;
                    else if (n.endsWith(".atlas.txt") || n.endsWith(".atlas")) atlasTxt = p;
                }
            }
            if (json == null || atlasTxt == null) return null;
            SpineData data = SpineData.load(json);
            SpineAtlas atlas = SpineAtlas.load(atlasTxt);
            SpineCharacter sc = new SpineCharacter(data, atlas);
            sc.assetScale = readAssetScale(folder);
            return sc;
        } catch (Exception e) {
            System.err.println("[SpineCharacter] load " + folder + " fail: " + e.getMessage());
            return null;
        }
    }

    /** Đọc 'scale:' từ SkeletonData.asset (Unity YAML). Default 0.01 nếu không thấy. */
    private static float readAssetScale(Path folder) {
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString().toLowerCase();
                if (n.endsWith(".asset")) {
                    for (String line : Files.readAllLines(p)) {
                        String t = line.trim();
                        if (t.startsWith("scale:")) {
                            try { return Float.parseFloat(t.substring(6).trim()); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0.01f;
    }

    /** Chọn anim theo tên ưu tiên (vd ["Walk","Idle"]), fallback anim đầu. */
    public SpineData.Animation pickAnim(String... prefer) {
        for (String n : prefer) {
            if (n != null && data.animations.containsKey(n)) return data.animations.get(n);
        }
        return data.animations.values().stream().findFirst().orElse(null);
    }

    public float skelHeight() { return data.skelHeight; }

    /**
     * Vẽ tại (mx,my) screen (chân nhân vật), cao heightPx px. t = giây. anim = animation muốn chạy.
     */
    public void render(Graphics2D g2, double mx, double my, double heightPx, float t,
                       SpineData.Animation anim, boolean flipX, float timeScale) {
        float dur = anim != null ? anim.duration : 0;
        float tt = (dur > 0) ? ((t * timeScale) % dur) : 0;
        skeleton.pose(anim, tt);
        double sss = heightPx / Math.max(1f, data.skelHeight);
        renderer.draw(g2, data, skeleton, mx, my, sss, flipX ? -1.0 : 1.0);
    }
}
