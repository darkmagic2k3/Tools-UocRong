package com.apex.maptool.spine;

import java.awt.Graphics2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 1 nhân vật Spine: data + atlas + renderer + skeleton.
 * Tự tìm skeleton (.json HOẶC .skel.bytes nhị phân) + .atlas.txt + .png trong folder.
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

    /**
     * Load từ folder (chứa skeleton + .atlas.txt + .png). null nếu thiếu file.
     *
     * <p>Skeleton nhận CẢ HAI dạng, ưu tiên đúng thứ tự này:
     * <ol>
     *   <li>{@code *.json} — đường đã chạy ổn định từ đầu, giữ nguyên để khỏi rủi ro hồi quy.
     *       (Thư mục {@code wave_kamehouse/bien2} có file tên {@code *.skel.json} — vẫn là JSON,
     *       nhánh này bắt trước nên đúng.)</li>
     *   <li>{@code *.skel.bytes} / {@code *.skel} — NHỊ PHÂN Spine 4.x, đọc bằng
     *       {@link SpineData#loadBinary} (76/76 skeleton hiệu ứng của game là dạng này, đều v4.2.43).</li>
     * </ol>
     * Trả về CÙNG một {@link SpineData} nên atlas / skeleton / renderer không phân biệt nguồn.
     */
    public static SpineCharacter load(Path folder) {
        try {
            if (!Files.isDirectory(folder)) return null;
            Path json = null, skel = null, atlasTxt = null;
            try (Stream<Path> s = Files.list(folder)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.endsWith(".json")) json = p;
                    else if (n.endsWith(".skel.bytes") || n.endsWith(".skel")) skel = p;
                    else if (n.endsWith(".atlas.txt") || n.endsWith(".atlas")) atlasTxt = p;
                }
            }
            if (atlasTxt == null || (json == null && skel == null)) return null;
            boolean binary = (json == null);
            SpineData data = binary ? SpineData.loadBinary(skel) : SpineData.load(json);
            SpineAtlas atlas = SpineAtlas.load(atlasTxt);
            SpineCharacter sc = new SpineCharacter(data, atlas);
            sc.assetScale = readAssetScale(folder);
            if (binary) {
                String ver = SpineBinary.peekVersion(skel);
                System.out.println("[SpineCharacter] nạp NHỊ PHÂN " + skel.getFileName()
                        + " (v" + (ver == null ? "?" : ver) + ")"
                        + " bones=" + data.bones.size() + " anims=" + data.animations.size());
            }
            return sc;
        } catch (Exception e) {
            System.err.println("[SpineCharacter] load " + folder + " fail: " + e.getMessage());
            return null;
        }
    }

    /**
     * Đọc {@code scale:} từ {@code <Tên>_SkeletonData.asset} (Unity YAML) — spine-unity sinh file này
     * cho CẢ skeleton .json lẫn .skel.bytes (đã kiểm file thật: {@code caytre/Tre_SkeletonData.asset}
     * có đúng dòng {@code scale: 0.01}).
     *
     * <p>Vòng 1 bám đúng tên chuẩn; vòng 2 NỚI RỘNG sang mọi {@code *.asset} khác trong thư mục
     * (giữ y hành vi cũ) phòng khi thư mục đặt tên khác. Không thấy dòng nào ⇒ mặc định 0.01.
     */
    private static float readAssetScale(Path folder) {
        List<Path> assets = new ArrayList<>();
        try (Stream<Path> s = Files.list(folder)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (p.getFileName().toString().toLowerCase().endsWith(".asset")) assets.add(p);
            }
        } catch (Exception ignored) { }
        for (Path p : assets) {
            if (!p.getFileName().toString().toLowerCase().endsWith("_skeletondata.asset")) continue;
            Float v = scaleIn(p);
            if (v != null) return v;
        }
        for (Path p : assets) {
            Float v = scaleIn(p);
            if (v != null) return v;
        }
        return 0.01f;
    }

    /**
     * Scale import của {@code SkeletonData.asset} — bản NULL-ĐƯỢC, port đúng
     * {@code hao_quang_export.read_skeleton_import_scale} của tool Python.
     *
     * <p>Khác {@link #readAssetScale} ở chỗ <b>không</b> mặc định 0.01: {@code null} nghĩa là thư mục
     * này KHÔNG phải nhân vật (hồn ma / skill / hiệu ứng rời không có SkeletonData.asset) — Player
     * Viewer dựa vào đúng chỗ đó để chọn "cỡ thật như game" hay "vừa khung".
     *
     * <p>Tìm đúng tên {@code SkeletonData.asset} trước; không có mới quét {@code *.asset} nào chứa
     * {@code skeletonJSON:} (chặt hơn {@link #readAssetScale} — {@code *_Atlas.asset} không lọt).
     */
    public static Float importScale(Path folder) {
        try {
            if (folder == null || !Files.isDirectory(folder)) return null;
            List<Path> cands = new ArrayList<>();
            Path exact = folder.resolve("SkeletonData.asset");
            if (Files.isRegularFile(exact)) cands.add(exact);
            if (cands.isEmpty()) {
                try (Stream<Path> s = Files.list(folder)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (!p.getFileName().toString().toLowerCase().endsWith(".asset")) continue;
                        if (Files.readString(p).contains("skeletonJSON:")) { cands.add(p); break; }
                    }
                }
            }
            for (Path p : cands) {
                Float v = scaleIn(p);
                if (v != null) return v;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Giá trị dòng {@code scale:} đầu tiên trong 1 file .asset (null = không có / đọc lỗi). */
    private static Float scaleIn(Path asset) {
        try {
            for (String line : Files.readAllLines(asset)) {
                String t = line.trim();
                if (t.startsWith("scale:")) {
                    try { return Float.parseFloat(t.substring(6).trim()); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return null;
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
     * Vẽ skeleton VỪA KHUNG {@code w×h} (chừa lề {@code pad}), canh theo <b>hộp bao hình THẬT SỰ
     * ĐƯỢC VẼ</b> ({@link SpineRenderer#worldBounds}).
     *
     * <p>KHÔNG canh theo {@code skeleton.width/height} của file: rất nhiều skeleton không khai hai
     * số đó, {@link SpineData} để mặc định 100×100 ⇒ hình phóng sai cỡ rồi bay hẳn ra ngoài khung,
     * người xem thấy ảnh cụt/vỡ hoặc ô trống. Đã sai đúng chỗ này ở khung xem trước của
     * "Đổi skeleton" và ở {@code SpineSnap}, nên gom về một hàm cho khỏi sai lần nữa.
     *
     * <p>Cũng DÒ NHIỀU MỐC THỜI GIAN: nhiều hiệu ứng tắt sạch attachment ở giây 0 nên hộp bao rỗng;
     * lấy mốc ĐẦU TIÊN vẽ ra được thứ gì đó.
     *
     * @return false nếu không mốc nào vẽ ra hình (vẫn vẽ bằng hộp khai báo để còn thấy thứ gì đó)
     */
    public boolean renderFit(Graphics2D g2, int w, int h, double pad, SpineData.Animation anim) {
        double dur = (anim != null && anim.duration > 0) ? anim.duration : 0;
        double[] b = null;
        float tUsed = 0f;
        for (int i = 0; i < 10 && b == null; i++) {
            tUsed = (float) (dur * i / 10.0);
            skeleton.pose(anim, tUsed);
            b = renderer.worldBounds(data, skeleton);
        }
        boolean real = b != null && b[2] - b[0] > 1e-6 && b[3] - b[1] > 1e-6;
        if (!real) {
            b = new double[]{data.skelX, data.skelY, data.skelX + data.skelWidth, data.skelY + data.skelHeight};
            skeleton.pose(anim, tUsed);
        }
        double s = Math.max(0.001, Math.min((w - pad * 2) / Math.max(1e-3, b[2] - b[0]),
                                            (h - pad * 2) / Math.max(1e-3, b[3] - b[1])));
        renderer.draw(g2, data, skeleton,
                w / 2.0 - (b[0] + b[2]) / 2 * s, h / 2.0 + (b[1] + b[3]) / 2 * s, s, 1.0);
        return real;
    }

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
