package com.apex.maptool.spine;

import com.apex.maptool.config.ToolConfig;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tự kiểm Player Viewer — chạy KHÔNG cần giao diện, KHÔNG đụng file client.
 *
 * <p>Kiểm đúng 3 thứ mà bản Java làm khác bản Python (Python nhờ Node vẽ, bản này tự vẽ) — nghĩa là
 * kiểm bằng ẢNH THẬT chứ không phải "gọi hàm không ném lỗi":
 * <ol>
 *   <li><b>Tắt slot</b> — ẩn 1 slot thì ảnh phải đổi; ẩn HẾT thì ảnh phải sạch trơn bằng nền.</li>
 *   <li><b>Delta bone</b> — kéo Δgóc thì ảnh phải đổi; bỏ delta ra thì ảnh phải về
 *       <b>y hệt từng điểm ảnh</b> so với lúc đầu (không rò trạng thái sang lượt sau).</li>
 *   <li><b>Không hồi quy</b> — skeleton chưa đụng gì phải cho ra ảnh y hệt một skeleton vừa nạp mới,
 *       tức là {@code setBoneDeltas(null)} / {@code setHiddenSlots(null)} trả về đúng đường cũ.</li>
 * </ol>
 * Kèm kiểm timeline có thật sự chạy (t=0 khác t=giữa) và "cỡ thật" cho ra chiều cao hợp lý.
 */
public final class PlayerViewTest {

    private static final int W = 900, H = 700;
    private static final Color BG = new Color(0x10, 0x10, 0x14);
    private static final double PX_PER_WORLD = 150.0, PLAYER_SPINE_SCALE = 0.7;

    private PlayerViewTest() { }

    private static int pass, fail;
    /** Ép chế độ "vừa khung" (bỏ qua SkeletonData.asset) — kiểm nhánh mà ô "Cỡ thật" tắt sẽ chạy. */
    private static boolean forceFit;

    public static int run(String rootArg, int limit) { return run(rootArg, limit, false); }

    public static int run(String rootArg, int limit, boolean fitMode) {
        forceFit = fitMode;
        Path root;
        if (rootArg != null && !rootArg.isEmpty()) {
            root = Path.of(rootArg);
        } else {
            root = new ToolConfig().resourceRoot().resolve("Player");
        }
        if (!Files.isDirectory(root)) {
            System.err.println("[pvtest] không thấy thư mục: " + root);
            return 2;
        }
        List<Path> folders = pickFolders(root, limit);
        if (folders.isEmpty()) {
            System.err.println("[pvtest] không có thư mục Spine nào trong " + root);
            return 2;
        }
        System.out.println("[pvtest] gốc = " + root + "  ·  kiểm " + folders.size() + " skeleton"
                + (forceFit ? "  ·  ÉP chế độ vừa-khung" : "") + "\n");

        for (Path f : folders) check(f);

        System.out.printf("%n[pvtest] %d PASS / %d FAIL%n", pass, fail);
        return fail == 0 ? 0 : 1;
    }

    private static void check(Path folder) {
        String tag = folder.getParent().getFileName() + "/" + folder.getFileName();
        SpineCharacter sc = SpineCharacter.load(folder);
        if (sc == null) { bad(tag, "không nạp được skeleton"); return; }
        Float imp = forceFit ? null : SpineCharacter.importScale(folder);

        int nSlot = sc.data.slots.size(), nBone = sc.data.bones.size(), nAnim = sc.data.animations.size();
        if (nSlot == 0 || nBone == 0) { bad(tag, "slot/bone rỗng"); return; }

        SpineData.Animation anim = sc.data.animations.values().stream().findFirst().orElse(null);

        // ── 1. tìm MỐC THỜI GIAN có hình ─────────────────────────────────────
        // KHÔNG mặc định t=0: hiệu ứng kiểu flipbook (Huyt_sao, Ngu) chưa gắn attachment nào ở
        // frame đầu nên t=0 trống là ĐÚNG của asset, không phải lỗi. Trống ở MỌI mốc mới là lỗi.
        float[] ts = sampleTimes(anim);
        float tBase = Float.NaN;
        for (float t : ts) {
            if (ink(snap(sc, anim, t, imp)) > 0) { tBase = t; break; }
        }
        if (Float.isNaN(tBase)) { bad(tag, "không vẽ ra gì ở BẤT KỲ mốc thời gian nào"); return; }
        BufferedImage base = snap(sc, anim, tBase, imp);

        // ── 2. không hồi quy: skeleton vừa nạp mới phải cho ảnh y hệt ────────
        SpineCharacter fresh = SpineCharacter.load(folder);
        BufferedImage freshImg = snap(fresh, fresh.data.animations.get(nameOf(sc, anim)), tBase, imp);
        if (diff(base, freshImg) != 0) { bad(tag, "ảnh khác skeleton nạp mới ⇒ có trạng thái rò"); return; }

        // ── 3. timeline có chạy (hiệu ứng TĨNH là hợp lệ ⇒ chỉ ghi chú) ─────
        boolean moves = false;
        for (float t : ts) {
            if (t != tBase && diff(base, snap(sc, anim, t, imp)) > 0) { moves = true; break; }
        }
        snap(sc, anim, tBase, imp);   // tua về mốc gốc cho các bước sau so đúng

        // ── 4. tắt 1 slot ⇒ ảnh đổi; tắt hết ⇒ sạch nền ─────────────────────
        String firstVisible = null;
        for (SpineData.Slot s : sc.data.slots) {
            Set<String> one = new LinkedHashSet<>(List.of(s.name));
            sc.skeleton.setHiddenSlots(one);
            if (diff(base, snap(sc, anim, tBase, imp)) > 0) { firstVisible = s.name; break; }
        }
        if (firstVisible == null) { bad(tag, "ẩn slot nào cũng KHÔNG đổi ảnh ⇒ tắt slot không ăn"); return; }

        Set<String> all = new LinkedHashSet<>();
        for (SpineData.Slot s : sc.data.slots) all.add(s.name);
        sc.skeleton.setHiddenSlots(all);
        if (ink(snap(sc, anim, tBase, imp)) != 0) { bad(tag, "ẩn HẾT slot mà vẫn còn hình"); return; }

        sc.skeleton.setHiddenSlots(null);
        if (diff(base, snap(sc, anim, tBase, imp)) != 0) { bad(tag, "bật lại slot KHÔNG về ảnh gốc"); return; }

        // ── 5. delta bone ⇒ ảnh đổi; bỏ ra ⇒ về y hệt ───────────────────────
        String movedBone = null;
        for (SpineData.Bone b : sc.data.bones) {
            Map<String, float[]> d = new LinkedHashMap<>();
            d.put(b.name, new float[]{30f, 0, 0, 1, 1});      // Δgóc 30°
            sc.skeleton.setBoneDeltas(d);
            if (diff(base, snap(sc, anim, tBase, imp)) > 0) { movedBone = b.name; break; }
        }
        if (movedBone == null) { bad(tag, "xoay bone nào cũng KHÔNG đổi ảnh ⇒ delta bone không ăn"); return; }

        sc.skeleton.setBoneDeltas(null);
        if (diff(base, snap(sc, anim, tBase, imp)) != 0) { bad(tag, "bỏ delta bone KHÔNG về ảnh gốc"); return; }

        // ── 6. "cỡ thật" phải ra chiều cao nhìn được ─────────────────────────
        int[] box = bbox(base);
        int hPx = box[3] - box[1];
        if (imp != null && (hPx < 20 || hPx > H * 4)) {
            bad(tag, "cỡ thật ra chiều cao vô lý: " + hPx + "px");
            return;
        }

        pass++;
        System.out.printf("  OK %-22s slot=%-3d bone=%-3d anim=%-3d skin=%d  %-16s t=%.2f%s cao=%dpx  (ẩn '%s', xoay '%s')%n",
                tag, nSlot, nBone, nAnim, sc.data.skins.size(),
                imp == null ? "vừa-khung" : String.format("scale=%.6g", imp),
                tBase, moves ? " " : " [TĨNH] ", hPx, firstVisible, movedBone);
    }

    /** 10 mốc thời gian rải đều trong animation (không có anim ⇒ chỉ mốc 0 = setup pose). */
    private static float[] sampleTimes(SpineData.Animation anim) {
        if (anim == null || anim.duration <= 0.001f) return new float[]{0f};
        float[] ts = new float[10];
        for (int i = 0; i < 10; i++) ts[i] = anim.duration * i / 10f;
        return ts;
    }

    private static String nameOf(SpineCharacter sc, SpineData.Animation a) {
        if (a == null) return null;
        for (Map.Entry<String, SpineData.Animation> e : sc.data.animations.entrySet()) {
            if (e.getValue() == a) return e.getKey();
        }
        return null;
    }

    private static void bad(String tag, String why) {
        fail++;
        System.out.println("  ✘ " + tag + " — " + why);
    }

    /** Vẽ đúng công thức của {@code PlayerViewerFrame.PvCanvas} (zoom = 1, chưa dời). */
    private static BufferedImage snap(SpineCharacter sc, SpineData.Animation anim, float t, Float imp) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        sc.skeleton.pose(anim, t);
        double s, ax, ay;
        if (imp != null) {
            s = imp * PLAYER_SPINE_SCALE * PX_PER_WORLD;
            ax = W / 2.0;
            ay = H * 0.78;
        } else {   // "vừa khung": canh theo hộp bao HÌNH THẬT, y như PlayerViewerFrame.PvCanvas.fitBox()
            double[] b = sc.renderer.worldBounds(sc.data, sc.skeleton);
            if (b == null || b[2] - b[0] < 1e-6 || b[3] - b[1] < 1e-6) {
                b = new double[]{sc.data.skelX, sc.data.skelY,
                                 sc.data.skelX + sc.data.skelWidth, sc.data.skelY + sc.data.skelHeight};
            }
            s = Math.max(0.01, Math.min((W - 40.0) / Math.max(1e-3, b[2] - b[0]),
                                        (H - 40.0) / Math.max(1e-3, b[3] - b[1])));
            ax = W / 2.0 - (b[0] + b[2]) / 2 * s;
            ay = H / 2.0 + (b[1] + b[3]) / 2 * s;
            sc.skeleton.pose(anim, t);   // worldBounds đọc pose hiện tại — pose lại cho chắc trạng thái
        }
        sc.renderer.draw(g, sc.data, sc.skeleton, ax, ay, s, 1.0);
        g.dispose();
        return img;
    }

    private static int ink(BufferedImage a) {
        int bg = BG.getRGB(), n = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != bg) n++;
        return n;
    }

    private static int diff(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++)
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
        return n;
    }

    /** {x0,y0,x1,y1} của vùng có hình (bằng nền hết ⇒ {0,0,0,0}). */
    private static int[] bbox(BufferedImage a) {
        int bg = BG.getRGB();
        int x0 = a.getWidth(), y0 = a.getHeight(), x1 = 0, y1 = 0;
        boolean any = false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) == bg) continue;
                any = true;
                if (x < x0) x0 = x;
                if (y < y0) y0 = y;
                if (x > x1) x1 = x;
                if (y > y1) y1 = y;
            }
        }
        return any ? new int[]{x0, y0, x1, y1} : new int[]{0, 0, 0, 0};
    }

    /** Lấy tối đa {@code limit} thư mục con có đủ bộ Spine, ưu tiên id nhỏ cho dễ đối chiếu. */
    private static List<Path> pickFolders(Path root, int limit) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                if (Files.isDirectory(p) && hasSpine(p)) out.add(p);
            }
        } catch (Exception ignored) { }
        out.sort((a, b) -> {
            String x = a.getFileName().toString(), y = b.getFileName().toString();
            boolean nx = x.chars().allMatch(Character::isDigit) && x.length() <= 18 && !x.isEmpty();
            boolean ny = y.chars().allMatch(Character::isDigit) && y.length() <= 18 && !y.isEmpty();
            if (nx && ny) return Long.compare(Long.parseLong(x), Long.parseLong(y));
            if (nx != ny) return nx ? -1 : 1;
            return x.compareToIgnoreCase(y);
        });
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static boolean hasSpine(Path dir) {
        boolean skel = false, atlas = false;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString().toLowerCase();
                if (n.endsWith(".json") || n.endsWith(".skel.bytes") || n.endsWith(".skel")) skel = true;
                else if (n.endsWith(".atlas.txt") || n.endsWith(".atlas")) atlas = true;
                if (skel && atlas) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
