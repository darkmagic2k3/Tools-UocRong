package com.apex.maptool;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.spine.SpineCharacter;
import com.apex.maptool.spine.SpineData;
import com.apex.maptool.ui.MapLayoutCanvas;
import com.apex.maptool.ui.SnapEngine;
import com.apex.maptool.unity.GuidIndex;
import com.apex.maptool.unity.MapLayer;
import com.apex.maptool.unity.MapScene;
import com.apex.maptool.unity.MapSceneLoader;
import com.apex.maptool.unity.MapSceneWriter;
import com.apex.maptool.unity.MaterialResolver;
import com.apex.maptool.unity.PrefabDocument;
import com.apex.maptool.unity.PrefabParser;
import com.apex.maptool.unity.SortingLayers;
import com.apex.maptool.unity.TextureCache;

import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BỘ TỰ TEST HEADLESS cho chức năng "Bố cục Map".
 *
 * <p>Chạy: {@code java -jar map-editor.jar layouttest [mapId...]} — không mở GUI.
 *
 * <p><b>AN TOÀN — luật bất di bất dịch:</b> KHÔNG bao giờ ghi vào repo client.
 * Mọi prefab được COPY sang thư mục tạm rồi mới thao tác; T11 băm SHA-256 file gốc
 * trước & sau toàn bộ test để CHỨNG MINH file client không đổi 1 byte nào.
 *
 * <p>Danh sách test cho TỪNG map:
 * <pre>
 *   T1  load        — số node, tỉ lệ texture resolve được (≥ 80%)
 *   T2  đối chiếu   — từng renderer khớp PrefabParser cũ (tâm/kích thước/góc/sortKey), sai lệch &lt; 0.01
 *   T3  round-trip  — không sửa gì → file ghi ra giống hệt file gốc từng byte
 *   T4  transform   — đổi tâm world (+3.5, −2.25) → ghi → load lại đúng, CHỈ 1 dòng file đổi
 *   T5  sorting     — Projectile(19)/Map(7)/UI(20): m_SortingLayerID int32 có dấu đúng
 *   T6  sortOrder   — đổi m_SortingOrder → round-trip đúng
 *   T7  đường đất   — kéo đỉnh + chèn đỉnh EdgeCollider2D, các đường KHÁC không đổi
 *   T8  xoá đỉnh    — xoá đỉnh round-trip đúng, chặn không cho xuống dưới 2 đỉnh
 *   T9  m_IsActive  — bật/tắt node → round-trip đúng, CHỈ 1 dòng file đổi
 *   T10 flip        — đổi m_FlipX → round-trip đúng, CHỈ 1 dòng file đổi
 *   T18 thêm đường  — chèn NGUYÊN BLOCK (đất + oneway): anchor mới, m_Children của cha,
 *                     node cũ nguyên vẹn, TẬP KEY của block mới giống hệt block cùng loại đã có
 *   T19 xoá đường   — gỡ nguyên block: hết sạch anchor trong file, node khác nguyên vẹn,
 *                     và biên map (Top/Bottom/Left/Right) BỊ CHẶN không cho xoá
 *   T20 thêm+xoá    — round-trip CẤU TRÚC: thêm rồi xoá phải ra lại file gốc từng byte
 *   T12 backup      — save() trên BẢN COPY tạo .bak đúng bằng nội dung trước khi ghi
 *   T21 hít khít    — gọi thẳng SnapEngine trên hộp bao THẬT của map: khít tuyệt đối &lt; 1e-9,
 *                     xa thì không hít, tắt thì dx = dy = 0, ngưỡng tính theo PX màn hình
 *   T22 biên map    — 4 thanh biên nhận đúng theo fileID trong MapManager (KHÔNG theo tên),
 *                     bị chặn xoá; kéo thanh "left" → m_LocalPosition + cameraBounds() đổi đúng,
 *                     CHỈ 1 dòng file đổi, node khác nguyên vẹn
 *   T23 tham số FX  — đổi 1 tham số số học (moveSpeed / _swimSpeed / timeScale…) → round-trip đúng,
 *                     CHỈ 1 dòng file đổi và ĐÚNG dòng mang tên field đó
 *   T24 nhận diện FX— số hiệu ứng từng loại khớp CHÍNH XÁC với phép đếm độc lập quét text prefab;
 *                     ≥ 80% node Spine trỏ tới thư mục có skeleton + .atlas
 *   T26 Spine nhị phân— NẠP THẬT từng skeleton của map (cả .json lẫn .skel.bytes): ≥ 95% vẽ được,
 *                     mỗi skeleton ≥ 1 bone + ≥ 1 animation, và ≥ 90% tên attachment tra được
 *                     trong .atlas.txt cùng thư mục (bằng chứng parser đọc ĐÚNG TÊN, không ra rác)
 * </pre>
 * Chạy 1 lần cuối: <b>T11</b> (file client nguyên vẹn), <b>T13</b> (vẽ offscreen ra ảnh),
 * <b>T25</b> (vẽ 3 frame ở 3 mốc thời gian → chứng minh hiệu ứng THẬT SỰ động)
 * và <b>T27</b> (vẽ 2 frame map có skeleton .skel.bytes → hình khác nhau và vùng vẽ không rỗng).
 */
public final class LayoutSelfTest {

    /** Bộ map mặc định khi không truyền id: map thường + map có tiền cảnh (4, 13) + map lớn. */
    private static final int[] DEFAULT_MAPS = {1, 4, 13, 37000, 0};

    /** Sai số tuyệt đối cơ bản. File YAML lưu FLOAT 32-bit (~7 chữ số) → phải cộng thêm sai số tương đối. */
    private static final double EPS_ABS = 1e-4;
    private static final double EPS_REL = 2e-6;
    /** Ngưỡng đối chiếu với PrefabParser cũ (theo đặc tả: &lt; 0.01 unit). */
    private static final double EPS_PARSER = 0.01;

    private int pass, fail, skip;

    private ToolConfig cfg;
    private GuidIndex guidIndex;
    private MaterialResolver matResolver;
    private TextureCache texCache;
    private SortingLayers sortLayers;
    private Path work;          // thư mục tạm (KHÔNG nằm trong repo client)
    private Path backupDir;     // thư mục backup cho T12

    /** Scene còn sống của map cuối cùng load được — dùng cho T13 (vẽ offscreen). */
    private MapScene lastScene;
    private int lastSceneMapId;

    /** Scene của map NHIỀU HIỆU ỨNG THẬT NHẤT — dùng cho T25 (vẽ 3 frame kiểm hiệu ứng động). */
    private MapScene fxScene;
    private int fxSceneMapId;
    private int fxSceneCount;

    /** Scene + node của map NHIỀU SKELETON .skel.bytes NHẤT — dùng cho T27 (vẽ thật Spine nhị phân). */
    private MapScene binScene;
    private MapScene.Node binNode;
    private int binSceneMapId;
    private int binSceneCount;

    private LayoutSelfTest() { }

    // ═════════════════════════════════════════════════════════════════════
    // Điểm vào
    // ═════════════════════════════════════════════════════════════════════

    /** Chạy toàn bộ test. Trả về SỐ TEST FAIL (0 = pass hết). */
    public static int run(int[] mapIds) {
        int[] ids = (mapIds == null || mapIds.length == 0) ? DEFAULT_MAPS : mapIds;
        return new LayoutSelfTest().go(ids);
    }

    private int go(int[] mapIds) {
        long t0 = System.currentTimeMillis();
        System.out.println("════════════════════════════════════════════════════════════");
        System.out.println(" TỰ TEST 'Bố cục Map' — headless, KHÔNG ghi vào repo client");
        System.out.println(" map: " + join(mapIds));
        System.out.println("════════════════════════════════════════════════════════════");

        try {
            cfg = new ToolConfig();
        } catch (Exception e) {
            System.out.println("[FAIL] khởi tạo ToolConfig: " + e);
            System.out.println("=== KẾT QUẢ: 0 PASS / 1 FAIL ===");
            return 1;
        }

        // ── thư mục làm việc tạm ──
        try {
            work = Files.createTempDirectory("uocrong-layouttest-");
            backupDir = work.resolve("backup");
        } catch (IOException e) {
            System.out.println("[FAIL] không tạo được thư mục tạm: " + e);
            System.out.println("=== KẾT QUẢ: 0 PASS / 1 FAIL ===");
            return 1;
        }
        System.out.println("[info] thư mục tạm: " + work);
        System.out.println("[info] client repo: " + cfg.clientRepo());

        // ── GUID index (đọc cache nếu có → nhanh) ──
        texCache = new TextureCache();
        guidIndex = new GuidIndex();
        try {
            long g0 = System.currentTimeMillis();
            boolean fromCache = guidIndex.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            System.out.println("[info] GuidIndex: " + guidIndex.size() + " guid · "
                    + (fromCache ? "từ cache" : "quét mới") + " · " + (System.currentTimeMillis() - g0) + "ms");
        } catch (Exception e) {
            System.out.println("[WARN] GuidIndex lỗi (" + e + ") → test texture sẽ fail");
        }
        matResolver = new MaterialResolver(guidIndex);

        // ── bảng sorting layer ──
        String cr = cfg.clientRepo();
        sortLayers = SortingLayers.load((cr == null || cr.isBlank()) ? null : Path.of(cr));
        System.out.println("[info] SortingLayers: " + sortLayers.all().size() + " layer · "
                + (sortLayers.loadedFromFile() ? "đọc TagManager.asset" : "BẢNG DỰ PHÒNG")
                + " · player index = " + sortLayers.playerIndex());

        // ── T11: chụp vân tay file client TRƯỚC khi làm gì ──
        Map<Integer, String[]> before = new LinkedHashMap<>();   // mapId → {sha256, size, lastModified}
        for (int id : mapIds) {
            Path p = cfg.mapPrefab(id);
            if (Files.exists(p)) before.put(id, fingerprint(p));
        }

        // ── chạy test từng map ──
        for (int id : mapIds) {
            System.out.println();
            System.out.println("──────── MAP " + id + " ────────");
            Path src = cfg.mapPrefab(id);
            if (!Files.exists(src)) {
                skip("Map" + id, "không thấy prefab " + src);
                continue;
            }
            try {
                runMap(id, src);
            } catch (Throwable t) {
                bad("Map" + id + " (ngoại lệ không lường trước)", t.toString());
                t.printStackTrace(System.out);
            }
        }

        // ── T11 + T13 chạy 1 lần ở cuối ──
        System.out.println();
        System.out.println("──────── TEST CHUNG ────────");
        t11ClientUntouched(before);
        t13OffscreenPaint();
        t25EffectFrames();
        t27SpineBinaryPaint();

        long ms = System.currentTimeMillis() - t0;
        System.out.println();
        System.out.println("=== KẾT QUẢ: " + pass + " PASS / " + fail + " FAIL"
                + (skip > 0 ? " / " + skip + " SKIP" : "") + " · " + ms + "ms ===");
        if (fail == 0) System.out.println("=== TẤT CẢ TEST ĐỀU ĐẠT — chức năng Bố cục Map nghiệm thu OK ===");
        else System.out.println("=== CÓ TEST HỎNG — xem dòng [FAIL] phía trên ===");
        System.out.println("[info] file tạm của lần chạy này: " + work);
        return fail;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Toàn bộ test của 1 map
    // ═════════════════════════════════════════════════════════════════════

    private void runMap(int mapId, Path src) throws IOException {
        // Bản copy "sạch" — MỌI test đọc từ đây, không đụng file client.
        Path copy = work.resolve("Map_" + mapId + ".prefab");
        Files.copy(src, copy, StandardCopyOption.REPLACE_EXISTING);

        MapScene scene = load(copy, mapId);
        if (scene == null) {
            bad("T1 load Map" + mapId, "MapSceneLoader.load ném lỗi");
            return;
        }
        lastScene = scene;
        lastSceneMapId = mapId;
        int nFx = scene.realEffectNodes().size();
        if (nFx > fxSceneCount) { fxScene = scene; fxSceneMapId = mapId; fxSceneCount = nFx; }

        t1Load(mapId, scene);
        t2CompareParser(mapId, scene, copy);
        t3RoundTrip(mapId, copy);
        t4Transform(mapId, copy);
        t5Sorting(mapId, copy);
        t6SortOrder(mapId, copy);
        t7GroundLine(mapId, copy);
        t8RemovePoint(mapId, copy);
        t9Active(mapId, copy);
        t10Flip(mapId, copy);
        t14Rotate(mapId, copy);
        t15Scale(mapId, copy);
        t16RendEnabled(mapId, copy);
        t17Texture(mapId, copy);
        t18AddLine(mapId, copy);
        t19RemoveLine(mapId, copy);
        t20AddThenRemove(mapId, copy);
        t28LineBlock(mapId, copy);
        t21Snap(mapId, scene);
        t22Bounds(mapId, copy);
        t23FxParam(mapId, copy);
        t24FxDetect(mapId, scene, copy);
        t26SpineBinary(mapId, scene);
        t12Backup(mapId, src);
    }

    // ───────────────────────────── T1 ─────────────────────────────

    private void t1Load(int mapId, MapScene scene) {
        String name = "T1 load Map" + mapId;
        int nodes = scene.nodes().size();
        int rend = 0, needTex = 0, gotTex = 0, edges = 0, ground = 0, oneway = 0;
        for (MapScene.Node n : scene.nodes()) {
            if (n.hasRenderer) rend++;
            // Chỉ tính node ĐÁNG LẼ phải có texture: sprite có guid, hoặc mesh quad có material.
            if (n.hasRenderer && n.texGuid != null && (n.isSprite || n.isQuadMesh)) {
                needTex++;
                if (n.texture != null && !n.texMissing) gotTex++;
            }
            if (n.colKind == MapScene.ColKind.EDGE) {
                edges++;
                if (n.physLayer == 6) ground++;
                if (n.physLayer == 19) oneway++;
            }
        }
        double ratio = needTex == 0 ? 0 : gotTex * 100.0 / needTex;
        String stat = nodes + " node · " + rend + " renderer · texture " + gotTex + "/" + needTex
                + " (" + fmt1(ratio) + "%) · edge collider " + edges
                + " (đất " + ground + ", oneway " + oneway + ")";
        if (nodes <= 0) { bad(name, "0 node — " + stat); return; }
        if (gotTex <= 0) { bad(name, "0 renderer có texture — " + stat); return; }
        if (ratio < 80.0) { bad(name, "tỉ lệ texture resolve " + fmt1(ratio) + "% < 80% — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T2 ─────────────────────────────

    /**
     * Đối chiếu với PrefabParser cũ (Map Editor đang chạy tốt) → chứng minh toán transform mới ĐÚNG.
     *
     * <p>Chỉ so những renderer mà 2 bên tính GIỐNG CÁCH NHAU: sprite nguyên ảnh (không cắt sub-sprite,
     * m_DrawMode = Simple) và mesh quad. Sub-sprite / sliced / Spine là phần MapSceneLoader làm
     * TỐT HƠN parser cũ nên loại khỏi phép so (vẫn in số lượng để người đọc log biết).
     */
    private void t2CompareParser(int mapId, MapScene scene, Path copy) {
        String name = "T2 đối chiếu PrefabParser Map" + mapId;
        List<MapLayer> layers;
        try {
            layers = new PrefabParser(guidIndex, matResolver, texCache, cfg.pixelsPerUnit()).parse(copy);
        } catch (Exception e) {
            bad(name, "PrefabParser.parse lỗi: " + e);
            return;
        }

        List<MapScene.Node> cmp = new ArrayList<>();
        int exSub = 0, exSliced = 0, exSpine = 0, exMissing = 0;
        for (MapScene.Node n : scene.nodes()) {
            if (!n.hasRenderer || !n.rendEnabled || !scene.activeInHierarchy(n)) continue;
            if (n.texture == null) continue;
            if (n.texMissing) { exMissing++; continue; }
            if (!n.isSprite && !n.isQuadMesh) { exSpine++; continue; }
            if (n.subRect != null) { exSub++; continue; }
            if (n.drawMode != 0) { exSliced++; continue; }
            if (n.baseW <= 0 || n.baseH <= 0) continue;
            cmp.add(n);
        }

        boolean[] used = new boolean[layers.size()];
        int matched = 0;
        String firstMiss = null;
        double[] bbN = emptyBounds(), bbL = emptyBounds();
        for (MapScene.Node n : cmp) {
            double[] c = scene.worldCenter(n);
            double[] s = scene.worldSize(n);
            double ang = scene.worldAngleDeg(n);
            long key = scene.sortKey(n);
            int hit = -1;
            for (int i = 0; i < layers.size(); i++) {
                if (used[i]) continue;
                MapLayer l = layers.get(i);
                if (!Objects.equals(l.texture, n.texture)) continue;
                if (Math.abs(l.cx - c[0]) > EPS_PARSER || Math.abs(l.cy - c[1]) > EPS_PARSER) continue;
                if (Math.abs(l.w - s[0]) > EPS_PARSER || Math.abs(l.h - s[1]) > EPS_PARSER) continue;
                if (Math.abs(angDiff(l.angleDeg, ang)) > EPS_PARSER) continue;
                if (l.sortKey != key) continue;
                if (l.flipX != n.flipX || l.flipY != n.flipY) continue;
                hit = i;
                break;
            }
            if (hit < 0) {
                if (firstMiss == null) {
                    firstMiss = "'" + n.name + "' tâm(" + fmt3(c[0]) + ", " + fmt3(c[1]) + ") kích thước("
                            + fmt3(s[0]) + " × " + fmt3(s[1]) + ") góc " + fmt3(ang) + "° sortKey " + key;
                }
                continue;
            }
            used[hit] = true;
            matched++;
            MapLayer l = layers.get(hit);
            grow(bbN, c[0] - s[0] / 2, c[1] - s[1] / 2);
            grow(bbN, c[0] + s[0] / 2, c[1] + s[1] / 2);
            grow(bbL, l.cx - l.w / 2, l.cy - l.h / 2);
            grow(bbL, l.cx + l.w / 2, l.cy + l.h / 2);
        }

        double dbb = 0;
        for (int i = 0; i < 4; i++) dbb = Math.max(dbb, Math.abs(bbN[i] - bbL[i]));
        String stat = "khớp " + matched + "/" + cmp.size() + " renderer (parser cũ có " + layers.size()
                + " layer; loại khỏi phép so: sub-sprite " + exSub + ", sliced " + exSliced
                + ", spine " + exSpine + ", texture hỏng " + exMissing + ")"
                + " · bbox lệch tối đa " + fmt3(dbb) + " unit"
                + " · bbox scene " + box(scene.bounds());
        if (cmp.isEmpty()) { bad(name, "không có renderer nào so được — " + stat); return; }
        if (matched != cmp.size()) { bad(name, "lệch toán transform ở " + (cmp.size() - matched)
                + " renderer, ví dụ " + firstMiss + " — " + stat); return; }
        if (dbb > EPS_PARSER) { bad(name, "bbox lệch " + fmt3(dbb) + " ≥ " + EPS_PARSER + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T3 ─────────────────────────────

    private void t3RoundTrip(int mapId, Path copy) throws IOException {
        String name = "T3 round-trip không sửa gì Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        Path out = work.resolve("Map_" + mapId + ".t3.prefab");
        MapSceneWriter.Result r = MapSceneWriter.saveAs(scene, sortLayers, out);
        byte[] a = Files.readAllBytes(copy), b = Files.readAllBytes(out);
        if (r.fieldsWritten != 0) { bad(name, "writer ghi " + r.fieldsWritten + " field dù không sửa gì"); return; }
        if (scene.dirty()) { bad(name, "scene dirty ngay sau khi load"); return; }
        if (a.length != b.length) { bad(name, "kích thước đổi " + a.length + " → " + b.length + " byte"); return; }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) { bad(name, "khác byte tại offset " + i); return; }
        }
        ok(name, a.length + " byte giống hệt file gốc (0 field ghi)");
    }

    // ───────────────────────────── T4 ─────────────────────────────

    private void t4Transform(int mapId, Path copy) throws IOException {
        String name = "T4 transform Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        // Chọn node CÓ CHUỖI CHA SÂU NHẤT (kiểm tra được cả phép nghịch đảo ma trận cha).
        MapScene.Node best = null;
        int bestDepth = -1;
        for (MapScene.Node n : scene.nodes()) {
            if (!n.hasRenderer || n.baseW <= 0 || n.baseH <= 0) continue;
            double[] m = scene.worldMatrix(n);
            if (Math.abs(m[0] * m[3] - m[1] * m[2]) < 1e-9) continue;   // scale 0 → không nghịch đảo được
            int d = depth(scene, n);
            if (d > bestDepth) { bestDepth = d; best = n; }
        }
        if (best == null) { skip(name, "map không có renderer nào hợp lệ"); return; }

        double[] c0 = scene.worldCenter(best);
        double tx = c0[0] + 3.5, ty = c0[1] - 2.25;
        scene.setWorldCenter(best, tx, ty);
        Path out = work.resolve("Map_" + mapId + ".t4.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);

        MapScene back = load(out, mapId);
        if (back == null) { bad(name, "load lại file đã ghi lỗi"); return; }
        MapScene.Node n2 = back.byTr(best.trAnchor);
        if (n2 == null) { bad(name, "không tìm lại được node &" + best.trAnchor); return; }
        double[] c1 = back.worldCenter(n2);
        int[] d = lineDiff(copy, out);
        String stat = "'" + best.name + "' (sâu " + bestDepth + " cấp) tâm ("
                + fmt3(c0[0]) + ", " + fmt3(c0[1]) + ") → mong đợi (" + fmt3(tx) + ", " + fmt3(ty)
                + "), đọc lại (" + fmt3(c1[0]) + ", " + fmt3(c1[1]) + ") · " + d[0] + " dòng file đổi";
        if (!near(c1[0], tx) || !near(c1[1], ty)) {
            bad(name, "tâm sai lệch (" + fmt6(c1[0] - tx) + ", " + fmt6(c1[1] - ty) + ") — " + stat);
            return;
        }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T5 ─────────────────────────────

    /** Đổi sorting layer = đổi "trước/sau player" — phải ghi ĐỦ m_SortingLayerID (int32 có dấu) + m_SortingLayer. */
    private void t5Sorting(int mapId, Path copy) throws IOException {
        String name = "T5 sorting trước/sau player Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        MapScene.Node target = firstRenderer(scene);
        if (target == null) { skip(name, "map không có renderer"); return; }

        long tr = target.trAnchor;
        int idx0 = target.sortLayerIdx;
        StringBuilder sb = new StringBuilder("node '" + target.name + "': layer " + idx0);

        // 19 Projectile (index > 18 LocalPlayer → VẼ ĐÈ LÊN player)
        Path p19 = work.resolve("Map_" + mapId + ".t5a.prefab");
        MapScene s19 = setLayerAndReload(scene, tr, 19, p19, mapId);
        if (s19 == null) { bad(name, "ghi/đọc lại bước Projectile lỗi"); return; }
        MapScene.Node n19 = s19.byTr(tr);
        String raw19 = rawSorting(s19, n19, "m_SortingLayerID:");
        String rawIdx19 = rawSorting(s19, n19, "m_SortingLayer:");
        sb.append(" → 19 (Projectile), file m_SortingLayerID: ").append(raw19)
          .append(", m_SortingLayer: ").append(rawIdx19);
        if (n19 == null || n19.sortLayerIdx != 19) { bad(name, "sortLayerIdx != 19 — " + sb); return; }
        if (SortingLayers.toUnsignedId((int) n19.sortLayerId) != 1117496207L) {
            bad(name, "uniqueID unsigned = " + SortingLayers.toUnsignedId((int) n19.sortLayerId)
                    + " ≠ 1117496207 — " + sb);
            return;
        }
        if (!"1117496207".equals(raw19)) { bad(name, "m_SortingLayerID trong file = " + raw19 + " ≠ 1117496207 — " + sb); return; }
        if (!"19".equals(rawIdx19)) { bad(name, "m_SortingLayer trong file = " + rawIdx19 + " ≠ 19 — " + sb); return; }
        if (19 <= sortLayers.playerIndex()) { bad(name, "bảng layer sai: Projectile phải > LocalPlayer"); return; }

        // 7 Map (index < 18 → nằm SAU player)
        Path p7 = work.resolve("Map_" + mapId + ".t5b.prefab");
        MapScene s7 = setLayerAndReload(s19, tr, 7, p7, mapId);
        if (s7 == null) { bad(name, "ghi/đọc lại bước Map lỗi"); return; }
        MapScene.Node n7 = s7.byTr(tr);
        String raw7 = rawSorting(s7, n7, "m_SortingLayerID:");
        sb.append(" → 7 (Map), file m_SortingLayerID: ").append(raw7);
        if (n7 == null || n7.sortLayerIdx != 7) { bad(name, "sortLayerIdx != 7 — " + sb); return; }
        if (!"1799343905".equals(raw7)) { bad(name, "m_SortingLayerID = " + raw7 + " ≠ 1799343905 — " + sb); return; }

        // 20 UI — uniqueID 3774101189 KHÔNG lọt int32 dương → file phải ghi -520866107
        Path p20 = work.resolve("Map_" + mapId + ".t5c.prefab");
        MapScene s20 = setLayerAndReload(s7, tr, 20, p20, mapId);
        if (s20 == null) { bad(name, "ghi/đọc lại bước UI lỗi"); return; }
        MapScene.Node n20 = s20.byTr(tr);
        String raw20 = rawSorting(s20, n20, "m_SortingLayerID:");
        sb.append(" → 20 (UI), file m_SortingLayerID: ").append(raw20)
          .append(" (unsigned ").append(n20 == null ? "?" : SortingLayers.toUnsignedId((int) n20.sortLayerId)).append(")");
        if (n20 == null || n20.sortLayerIdx != 20) { bad(name, "sortLayerIdx != 20 — " + sb); return; }
        if (!"-520866107".equals(raw20)) { bad(name, "m_SortingLayerID = " + raw20 + " ≠ -520866107 — " + sb); return; }
        if (SortingLayers.toUnsignedId((int) n20.sortLayerId) != 3774101189L) {
            bad(name, "uniqueID unsigned = " + SortingLayers.toUnsignedId((int) n20.sortLayerId)
                    + " ≠ 3774101189 — " + sb);
            return;
        }
        ok(name, sb.toString());
    }

    /** Đổi sorting layer index của node rồi ghi ra file mới và load lại. */
    private MapScene setLayerAndReload(MapScene scene, long trAnchor, int idx, Path out, int mapId) throws IOException {
        MapScene.Node n = scene.byTr(trAnchor);
        if (n == null) return null;
        n.sortLayerIdx = idx;
        n.dSorting = true;
        MapSceneWriter.saveAs(scene, sortLayers, out);
        return load(out, mapId);
    }

    /** Đọc THẲNG giá trị trong file YAML của block renderer (không qua model). */
    private static String rawSorting(MapScene scene, MapScene.Node n, String key) {
        if (scene == null || n == null || n.rendAnchor == 0) return "?";
        PrefabDocument.Block b = scene.doc().block(n.rendAnchor);
        if (b == null) return "?";
        String v = scene.doc().getScalar(b, key);
        return v == null ? "?" : v.trim();
    }

    // ───────────────────────────── T6 ─────────────────────────────

    private void t6SortOrder(int mapId, Path copy) throws IOException {
        String name = "T6 thứ tự lớp (m_SortingOrder) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        MapScene.Node t = firstRenderer(scene);
        if (t == null) { skip(name, "map không có renderer"); return; }

        int old = t.sortOrder, want = old + 37;
        t.sortOrder = want;
        t.dSorting = true;
        Path out = work.resolve("Map_" + mapId + ".t6.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);

        MapScene back = load(out, mapId);
        MapScene.Node n2 = (back == null) ? null : back.byTr(t.trAnchor);
        String raw = rawSorting(back, n2, "m_SortingOrder:");
        String stat = "'" + t.name + "' m_SortingOrder " + old + " → " + want + ", file ghi " + raw;
        if (n2 == null) { bad(name, "không tìm lại được node — " + stat); return; }
        if (n2.sortOrder != want) { bad(name, "đọc lại = " + n2.sortOrder + " — " + stat); return; }
        if (!String.valueOf(want).equals(raw)) { bad(name, "file ghi " + raw + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T7 ─────────────────────────────

    /** Đường kẻ mà player đứng = EdgeCollider2D trên GameObject có m_Layer 6 (Ground). */
    private void t7GroundLine(int mapId, Path copy) throws IOException {
        String name = "T7 đường player đứng Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        MapScene.Node line = null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind == MapScene.ColKind.EDGE && n.physLayer == 6 && n.pts != null && n.pts.length >= 2) {
                line = n;
                break;
            }
        }
        if (line == null) { skip(name, "map không có EdgeCollider2D nào ở physics layer 6 (Ground)"); return; }

        // Ảnh chụp m_Points của MỌI edge collider KHÁC → sau khi ghi phải y nguyên.
        Map<Long, double[][]> othersBefore = snapshotEdges(scene, line.colAnchor);

        double[][] wp = scene.worldPoints(line);
        int n0 = line.pts.length;
        double ex = wp[0][0] + 1.5, ey = wp[0][1] - 0.75;
        scene.setColliderWorldPoint(line, 0, ex, ey);
        // chèn 1 đỉnh vào GIỮA đoạn 0-1 (toạ độ tính lại sau khi đã kéo đỉnh 0)
        double[][] wp2 = scene.worldPoints(line);
        double mx = (wp2[0][0] + wp2[1][0]) / 2, my = (wp2[0][1] + wp2[1][1]) / 2;
        scene.insertColliderPoint(line, 0, mx, my);

        Path out = work.resolve("Map_" + mapId + ".t7.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);
        MapScene back = load(out, mapId);
        if (back == null) { bad(name, "load lại lỗi"); return; }
        MapScene.Node l2 = back.byTr(line.trAnchor);
        if (l2 == null) { bad(name, "không tìm lại được node đường"); return; }
        double[][] wp3 = back.worldPoints(l2);
        String stat = "'" + line.name + "' " + n0 + " → " + l2.pts.length + " đỉnh · đỉnh 0 mong đợi ("
                + fmt3(ex) + ", " + fmt3(ey) + "), đọc lại (" + fmt3(wp3[0][0]) + ", " + fmt3(wp3[0][1]) + ")"
                + " · đỉnh giữa (" + fmt3(mx) + ", " + fmt3(my) + ") → (" + fmt3(wp3[1][0]) + ", " + fmt3(wp3[1][1]) + ")";
        if (l2.pts.length != n0 + 1) { bad(name, "số đỉnh sai — " + stat); return; }
        if (!near(wp3[0][0], ex) || !near(wp3[0][1], ey)) { bad(name, "đỉnh 0 lệch — " + stat); return; }
        if (!near(wp3[1][0], mx) || !near(wp3[1][1], my)) { bad(name, "đỉnh chèn lệch — " + stat); return; }

        Map<Long, double[][]> othersAfter = snapshotEdges(back, l2.colAnchor);
        String diff = compareEdges(othersBefore, othersAfter);
        if (diff != null) { bad(name, "đường KHÁC bị đổi: " + diff + " — " + stat); return; }
        ok(name, stat + " · " + othersBefore.size() + " đường khác giữ nguyên");
    }

    private static Map<Long, double[][]> snapshotEdges(MapScene scene, long exceptCol) {
        Map<Long, double[][]> out = new LinkedHashMap<>();
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind != MapScene.ColKind.EDGE || n.pts == null) continue;
            if (n.colAnchor == exceptCol) continue;
            double[][] cp = new double[n.pts.length][];
            for (int i = 0; i < n.pts.length; i++) cp[i] = n.pts[i].clone();
            out.put(n.colAnchor, cp);
        }
        return out;
    }

    /** null = giống hệt; ngược lại trả mô tả chỗ khác đầu tiên. */
    private static String compareEdges(Map<Long, double[][]> a, Map<Long, double[][]> b) {
        if (a.size() != b.size()) return "số đường " + a.size() + " → " + b.size();
        for (Map.Entry<Long, double[][]> e : a.entrySet()) {
            double[][] y = b.get(e.getKey());
            if (y == null) return "mất collider &" + e.getKey();
            double[][] x = e.getValue();
            if (x.length != y.length) return "&" + e.getKey() + " số đỉnh " + x.length + " → " + y.length;
            for (int i = 0; i < x.length; i++) {
                if (x[i][0] != y[i][0] || x[i][1] != y[i][1]) {
                    return "&" + e.getKey() + " đỉnh " + i + " (" + x[i][0] + ", " + x[i][1] + ") → ("
                            + y[i][0] + ", " + y[i][1] + ")";
                }
            }
        }
        return null;
    }

    // ───────────────────────────── T8 ─────────────────────────────

    private void t8RemovePoint(int mapId, Path copy) throws IOException {
        String name = "T8 xoá đỉnh đường Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        MapScene.Node line = null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind == MapScene.ColKind.EDGE && n.pts != null && n.pts.length >= 3) { line = n; break; }
        }
        if (line == null) { skip(name, "map không có EdgeCollider2D nào ≥ 3 đỉnh"); return; }

        int n0 = line.pts.length;
        // toạ độ world mong đợi CÒN LẠI sau khi xoá đỉnh 1
        double[][] wpAll = scene.worldPoints(line);
        List<double[]> expect = new ArrayList<>();
        for (int i = 0; i < wpAll.length; i++) if (i != 1) expect.add(wpAll[i]);

        scene.removeColliderPoint(line, 1);
        Path out = work.resolve("Map_" + mapId + ".t8.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);
        MapScene back = load(out, mapId);
        MapScene.Node l2 = (back == null) ? null : back.byTr(line.trAnchor);
        if (l2 == null) { bad(name, "load lại lỗi"); return; }
        if (l2.pts.length != n0 - 1) {
            bad(name, "'" + line.name + "' số đỉnh " + n0 + " → " + l2.pts.length + " (mong đợi " + (n0 - 1) + ")");
            return;
        }
        double[][] wpBack = back.worldPoints(l2);
        for (int i = 0; i < expect.size(); i++) {
            if (!near(wpBack[i][0], expect.get(i)[0]) || !near(wpBack[i][1], expect.get(i)[1])) {
                bad(name, "đỉnh " + i + " lệch: mong đợi (" + fmt3(expect.get(i)[0]) + ", " + fmt3(expect.get(i)[1])
                        + "), đọc lại (" + fmt3(wpBack[i][0]) + ", " + fmt3(wpBack[i][1]) + ")");
                return;
            }
        }

        // Chặn dưới 2 đỉnh (Unity yêu cầu EdgeCollider2D ≥ 2 điểm).
        MapScene s2 = load(copy, mapId);
        MapScene.Node l3 = (s2 == null) ? null : s2.byTr(line.trAnchor);
        if (l3 == null) { bad(name, "load lại lần 2 lỗi"); return; }
        for (int i = 0; i < 200 && l3.pts.length > 0; i++) s2.removeColliderPoint(l3, 0);
        if (l3.pts.length != 2) {
            bad(name, "xoá liên tục còn " + l3.pts.length + " đỉnh — phải dừng đúng ở 2");
            return;
        }
        ok(name, "'" + line.name + "' " + n0 + " → " + l2.pts.length + " đỉnh, các đỉnh còn lại khớp world; "
                + "xoá liên tục dừng đúng ở 2 đỉnh");
    }

    // ───────────────────────────── T9 ─────────────────────────────

    private void t9Active(int mapId, Path copy) throws IOException {
        String name = "T9 bật/tắt node (m_IsActive) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        MapScene.Node t = null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.hasRenderer && n.active) { t = n; break; }
        }
        if (t == null && !scene.nodes().isEmpty()) t = scene.nodes().get(0);
        if (t == null) { skip(name, "map không có node"); return; }

        boolean want = !t.active;
        t.active = want;
        t.dActive = true;
        Path out = work.resolve("Map_" + mapId + ".t9.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);
        MapScene back = load(out, mapId);
        MapScene.Node n2 = (back == null) ? null : back.byTr(t.trAnchor);
        int[] d = lineDiff(copy, out);
        String stat = "'" + t.name + "' m_IsActive " + (!want ? 1 : 0) + " → " + (want ? 1 : 0)
                + ", đọc lại " + (n2 == null ? "?" : (n2.active ? 1 : 0)) + " · " + d[0] + " dòng file đổi";
        if (n2 == null) { bad(name, "không tìm lại được node — " + stat); return; }
        if (n2.active != want) { bad(name, "giá trị sai — " + stat); return; }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T10 ─────────────────────────────

    private void t10Flip(int mapId, Path copy) throws IOException {
        String name = "T10 lật ngang (m_FlipX) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        // Chọn SpriteRenderer KHÔNG lật bằng quaternion 180° (|qw| ≥ 0.1) → đúng 1 dòng m_FlipX đổi.
        MapScene.Node t = null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.hasRenderer && n.isSprite && n.rendAnchor != 0 && Math.abs(n.qw) >= 0.1) { t = n; break; }
        }
        if (t == null) { skip(name, "map không có SpriteRenderer phù hợp"); return; }

        boolean want = !t.flipX;
        t.flipX = want;
        t.dTransform = true;    // writer ghi m_FlipX/m_FlipY chung với nhánh transform
        Path out = work.resolve("Map_" + mapId + ".t10.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);
        MapScene back = load(out, mapId);
        MapScene.Node n2 = (back == null) ? null : back.byTr(t.trAnchor);
        String raw = rawSorting(back, n2, "m_FlipX:");
        int[] d = lineDiff(copy, out);
        String stat = "'" + t.name + "' m_FlipX " + (want ? 0 : 1) + " → " + (want ? 1 : 0)
                + ", file ghi " + raw + ", đọc lại " + (n2 == null ? "?" : (n2.flipX ? 1 : 0))
                + " · " + d[0] + " dòng file đổi";
        if (n2 == null) { bad(name, "không tìm lại được node — " + stat); return; }
        if (n2.flipX != want) { bad(name, "giá trị sai — " + stat); return; }
        if (!String.valueOf(want ? 1 : 0).equals(raw)) { bad(name, "file ghi " + raw + " — " + stat); return; }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T14 ─────────────────────────────

    /**
     * Góc xoay (m_LocalRotation + m_LocalEulerAnglesHint).
     * Chỉ chọn node có quaternion xoay THUẦN quanh Z (|qx|,|qy| ≈ 0 và |qw| ≥ 0.1) — node lật
     * 180° quanh X/Y do writer cố tình giữ nguyên quaternion nên không dùng để test được.
     */
    private void t14Rotate(int mapId, Path copy) throws IOException {
        String name = "T14 góc xoay (m_LocalRotation) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        MapScene.Node t = null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.hasRenderer && n.isSprite && n.rendAnchor != 0 && n.trAnchor != 0
                    && Math.abs(n.qw) >= 0.1 && Math.abs(n.qx) < 1e-4 && Math.abs(n.qy) < 1e-4) {
                t = n; break;
            }
        }
        if (t == null) { skip(name, "map không có node xoay thuần quanh Z"); return; }

        double from = t.rotDeg;
        double want = from + 30.0;              // writer sẽ chuẩn hoá về (−180, 180]
        t.rotDeg = want;
        t.dTransform = true;
        Path out = work.resolve("Map_" + mapId + ".t14.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);

        MapScene back = load(out, mapId);
        MapScene.Node n2 = (back == null) ? null : back.byTr(t.trAnchor);
        // đọc THẲNG quaternion trong file rồi quy ngược ra góc
        double[] q = rawVec(back, t.trAnchor, "m_LocalRotation:");
        double fileDeg = (q == null) ? Double.NaN
                : Math.toDegrees(2.0 * Math.atan2(q[2], q[3]));
        int[] d = lineDiff(copy, out);
        String stat = "'" + t.name + "' góc " + fmt3(from) + "° → " + fmt3(want)
                + "°, file ghi " + (q == null ? "?" : fmt3(fileDeg) + "°")
                + ", đọc lại " + (n2 == null ? "?" : fmt3(n2.rotDeg) + "°")
                + " · " + d[0] + " dòng file đổi";
        if (n2 == null) { bad(name, "không tìm lại được node — " + stat); return; }
        if (q == null) { bad(name, "file không có m_LocalRotation — " + stat); return; }
        if (Math.abs(q[0]) > 1e-9 || Math.abs(q[1]) > 1e-9) {
            bad(name, "quaternion ghi ra còn thành phần X/Y (x=" + fmt6(q[0]) + ", y=" + fmt6(q[1])
                    + ") — " + stat); return;
        }
        if (angDiff(fileDeg, want) > 1e-3) { bad(name, "góc trong file lệch — " + stat); return; }
        if (angDiff(n2.rotDeg, want) > 1e-3) { bad(name, "đọc lại lệch — " + stat); return; }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        // 1 dòng m_LocalRotation, +1 nữa nếu block có m_LocalEulerAnglesHint
        if (d[0] < 1 || d[0] > 2) { bad(name, "phải 1–2 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T15 ─────────────────────────────

    /** Phóng to/thu nhỏ (m_LocalScale) — giữ nguyên z. */
    private void t15Scale(int mapId, Path copy) throws IOException {
        String name = "T15 phóng to/thu nhỏ (m_LocalScale) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        MapScene.Node t = firstRenderer(scene);
        if (t == null) { skip(name, "map không có renderer"); return; }

        double fx = t.sx, fy = t.sy;
        double wx = fx * 1.5, wy = fy * 0.5;
        t.sx = wx; t.sy = wy;
        t.dTransform = true;
        Path out = work.resolve("Map_" + mapId + ".t15.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);

        MapScene back = load(out, mapId);
        MapScene.Node n2 = (back == null) ? null : back.byTr(t.trAnchor);
        double[] s = rawVec(back, t.trAnchor, "m_LocalScale:");
        int[] d = lineDiff(copy, out);
        String stat = "'" + t.name + "' scale (" + fmt3(fx) + ", " + fmt3(fy) + ") → ("
                + fmt3(wx) + ", " + fmt3(wy) + "), đọc lại "
                + (n2 == null ? "?" : "(" + fmt3(n2.sx) + ", " + fmt3(n2.sy) + ")")
                + " · " + d[0] + " dòng file đổi";
        if (n2 == null) { bad(name, "không tìm lại được node — " + stat); return; }
        if (s == null) { bad(name, "file không có m_LocalScale — " + stat); return; }
        if (!near(n2.sx, wx) || !near(n2.sy, wy)) { bad(name, "giá trị sai — " + stat); return; }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T16 ─────────────────────────────

    /** Tắt/bật riêng renderer (m_Enabled) — khác với tắt cả GameObject ở T9. */
    private void t16RendEnabled(int mapId, Path copy) throws IOException {
        String name = "T16 tắt/bật renderer (m_Enabled) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        MapScene.Node t = firstRenderer(scene);
        if (t == null) { skip(name, "map không có renderer"); return; }

        boolean want = !t.rendEnabled;
        t.rendEnabled = want;
        t.dRendEnabled = true;
        Path out = work.resolve("Map_" + mapId + ".t16.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);

        MapScene back = load(out, mapId);
        MapScene.Node n2 = (back == null) ? null : back.byTr(t.trAnchor);
        String raw = rawSorting(back, n2, "m_Enabled:");
        int[] d = lineDiff(copy, out);
        String stat = "'" + t.name + "' m_Enabled " + (want ? 0 : 1) + " → " + (want ? 1 : 0)
                + ", file ghi " + raw + ", đọc lại " + (n2 == null ? "?" : (n2.rendEnabled ? 1 : 0))
                + " · " + d[0] + " dòng file đổi";
        if (n2 == null) { bad(name, "không tìm lại được node — " + stat); return; }
        if (n2.rendEnabled != want) { bad(name, "giá trị sai — " + stat); return; }
        if (!String.valueOf(want ? 1 : 0).equals(raw)) { bad(name, "file ghi " + raw + " — " + stat); return; }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T17 ─────────────────────────────

    /**
     * Đổi ảnh của 1 SpriteRenderer (m_Sprite): lấy guid của sprite KHÁC đang có sẵn trong map
     * để chắc chắn guid hợp lệ và giải được ra texture khi load lại.
     */
    private void t17Texture(int mapId, Path copy) throws IOException {
        String name = "T17 đổi ảnh sprite (m_Sprite) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        // CHỈ lấy sprite mà GuidIndex giải được ra .png thật (n.texture != null): vài map (ví dụ
        // Map204) tham chiếu ảnh đã bị xoá khỏi repo client — chọn nhầm ảnh đó thì test báo hỏng
        // trong khi lỗi nằm ở DỮ LIỆU client chứ không phải ở writer.
        MapScene.Node a = null, b = null;
        for (MapScene.Node n : scene.nodes()) {
            if (!n.hasRenderer || !n.isSprite || n.rendAnchor == 0 || n.texGuid == null) continue;
            if (n.texture == null) continue;
            if (a == null) { a = n; continue; }
            if (!n.texGuid.equalsIgnoreCase(a.texGuid)) { b = n; break; }
        }
        if (a == null || b == null) { skip(name, "map không có 2 sprite khác guid giải được ra ảnh thật"); return; }

        String fromGuid = a.texGuid;
        a.texGuid = b.texGuid;
        a.spriteFileId = b.spriteFileId;      // giữ đúng sub-sprite nếu ảnh nguồn là atlas
        a.dTexture = true;
        Path out = work.resolve("Map_" + mapId + ".t17.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);

        MapScene back = load(out, mapId);
        MapScene.Node n2 = (back == null) ? null : back.byTr(a.trAnchor);
        int[] d = lineDiff(copy, out);
        String stat = "'" + a.name + "' ảnh " + shortGuid(fromGuid) + " → " + shortGuid(b.texGuid)
                + " (lấy của '" + b.name + "'), đọc lại "
                + (n2 == null ? "?" : shortGuid(n2.texGuid))
                + " · " + d[0] + " dòng file đổi";
        if (n2 == null) { bad(name, "không tìm lại được node — " + stat); return; }
        if (n2.texGuid == null || !n2.texGuid.equalsIgnoreCase(b.texGuid)) {
            bad(name, "guid sai — " + stat); return;
        }
        if (n2.texture == null) { bad(name, "load lại không giải được ra texture — " + stat); return; }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        ok(name, stat);
    }

    /** Đọc THẲNG 1 vector trong block bất kỳ (theo anchor) của file YAML. */
    private static double[] rawVec(MapScene scene, long anchor, String key) {
        if (scene == null || anchor == 0) return null;
        PrefabDocument.Block b = scene.doc().block(anchor);
        return (b == null) ? null : scene.doc().getVec(b, key);
    }

    private static String shortGuid(String g) {
        return (g == null || g.length() < 8) ? String.valueOf(g) : g.substring(0, 8) + "…";
    }

    // ───────────────────────────── T18 ─────────────────────────────

    /**
     * THÊM ĐƯỜNG KẺ MỚI — khác hẳn T4…T17: ở đây writer phải CHÈN NGUYÊN BLOCK YAML
     * (GameObject + Transform + EdgeCollider2D, thêm PlatformEffector2D nếu là oneway)
     * chứ không patch dòng có sẵn. Chạy 2 lượt: đường đất (layer 6) và đường oneway (layer 19).
     */
    private void t18AddLine(int mapId, Path copy) throws IOException {
        t18One(mapId, copy, MapScene.LineKind.GROUND, "Test_Ground", "t18a");
        t18One(mapId, copy, MapScene.LineKind.ONEWAY, "Test_Oneway", "t18b");
        t18Multi(mapId, copy);
    }

    /**
     * 1 lượt thêm đường. Kiểm tra đủ 8 điểm:
     * <pre>
     *   a) node mới đọc lại đúng: tên, physics layer, EdgeCollider2D, 2 đỉnh, toạ độ WORLD khớp
     *   b) Transform CHA có anchor Transform mới trong m_Children (đọc THẲNG file text)
     *   c) 3–4 anchor mới đều DUY NHẤT trong file (đếm dòng header "--- !u!… &anchor")
     *   d) số node = số node cũ + 1
     *   e) MỌI node cũ còn nguyên (đối chiếu goAnchor + worldCenter)
     *   f) file vẫn parse được bằng PrefabParser CŨ, số layer không đổi
     *   g) ONEWAY: có block !u!251 trỏ đúng GameObject mới, m_UsedByEffector = 1
     *   h) TẬP KEY cấp 1 của block EdgeCollider2D MỚI giống HỆT block EdgeCollider2D ĐÃ CÓ
     *      trong cùng file — bằng chứng mạnh nhất cho việc Unity deserialize được.
     * </pre>
     */
    private void t18One(int mapId, Path copy, MapScene.LineKind kind, String nodeName, String suffix)
            throws IOException {
        boolean oneway = (kind == MapScene.LineKind.ONEWAY);
        int wantLayer = oneway ? MapScene.LAYER_ONEWAY : MapScene.LAYER_GROUND;
        String name = "T18 thêm đường " + (oneway ? "ONEWAY" : "ĐẤT") + " mới Map" + mapId;

        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        MapScene.Node parent = pickLineParent(scene);
        if (parent == null) { skip(name, "map không có node nào làm cha được"); return; }

        int n0 = scene.nodes().size();
        Map<Long, double[]> centers0 = snapshotCenters(scene);
        int layers0 = parserLayerCount(copy);

        double[][] wpts = {{-5, 2}, {5, 2}};
        MapScene.Node line = scene.addEdgeLine(parent, nodeName, kind, wpts);
        if (line == null) { bad(name, "addEdgeLine trả null"); return; }
        long goA = line.goAnchor, trA = line.trAnchor, colA = line.colAnchor, effA = line.effAnchor;

        Path out = work.resolve("Map_" + mapId + "." + suffix + ".prefab");
        MapSceneWriter.Result wr = MapSceneWriter.saveAs(scene, sortLayers, out);
        if (!wr.ok()) { bad(name, "writer báo lỗi: " + String.join(" | ", wr.log)); return; }

        MapScene back = load(out, mapId);
        if (back == null) { bad(name, "load lại file đã ghi lỗi"); return; }
        MapScene.Node n2 = back.byGo(goA);
        String stat = "'" + nodeName + "' dưới cha '" + parent.name + "' · anchor GO &" + goA
                + " Tr &" + trA + " Col &" + colA + (oneway ? " Eff &" + effA : "")
                + " · node " + n0 + " → " + back.nodes().size();

        // (a) node mới đọc lại đúng
        if (n2 == null) { bad(name, "không tìm lại được GameObject mới &" + goA + " — " + stat); return; }
        if (!nodeName.equals(n2.name)) { bad(name, "tên đọc lại '" + n2.name + "' — " + stat); return; }
        if (n2.physLayer != wantLayer) {
            bad(name, "m_Layer = " + n2.physLayer + " ≠ " + wantLayer + " — " + stat); return;
        }
        if (n2.colKind != MapScene.ColKind.EDGE) {
            bad(name, "collider đọc lại là " + n2.colKind + " ≠ EDGE — " + stat); return;
        }
        if (n2.pts == null || n2.pts.length != 2) {
            bad(name, "số đỉnh = " + (n2.pts == null ? "null" : n2.pts.length) + " ≠ 2 — " + stat); return;
        }
        double[][] wp = back.worldPoints(n2);
        for (int i = 0; i < 2; i++) {
            if (!near(wp[i][0], wpts[i][0]) || !near(wp[i][1], wpts[i][1])) {
                bad(name, "đỉnh " + i + " world mong đợi (" + fmt3(wpts[i][0]) + ", " + fmt3(wpts[i][1])
                        + "), đọc lại (" + fmt3(wp[i][0]) + ", " + fmt3(wp[i][1]) + ")"
                        + " · local ghi ra (" + fmt6(n2.pts[i][0]) + ", " + fmt6(n2.pts[i][1]) + ") — " + stat);
                return;
            }
        }
        stat += " · đỉnh world (" + fmt3(wp[0][0]) + ", " + fmt3(wp[0][1]) + ")…("
                + fmt3(wp[1][0]) + ", " + fmt3(wp[1][1]) + ")";

        // (b) m_Children của Transform CHA — đọc THẲNG text file, không qua model
        List<Long> kids = fileIdListInFile(out, parent.trAnchor, "m_Children:");
        if (!kids.contains(trA)) {
            bad(name, "m_Children của Transform cha &" + parent.trAnchor + " (" + kids.size()
                    + " con) KHÔNG chứa &" + trA + " → Unity sẽ bỏ rơi node — " + stat);
            return;
        }
        if (n2.parentTr != parent.trAnchor) {
            bad(name, "m_Father đọc lại &" + n2.parentTr + " ≠ &" + parent.trAnchor + " — " + stat); return;
        }

        // (b2) m_Component của GameObject mới — Unity dựng component THEO DANH SÁCH NÀY, không
        // theo chiều ngược m_GameObject. Loader của tool đi chiều ngược nên sai ở đây nó KHÔNG
        // phát hiện được, phải đọc thẳng file mới bắt được.
        List<Long> comps = fileIdListInFile(out, goA, "m_Component:");
        String compStat = "m_Component = " + comps;
        if (comps.isEmpty() || comps.get(0) != trA) {
            bad(name, "m_Component[0] phải là Transform &" + trA + " (Unity bắt buộc) — "
                    + compStat + " — " + stat);
            return;
        }
        if (!comps.contains(colA)) {
            bad(name, "m_Component thiếu EdgeCollider2D &" + colA + " → Unity bỏ collider — "
                    + compStat + " — " + stat);
            return;
        }
        if (oneway && !comps.contains(effA)) {
            bad(name, "m_Component thiếu PlatformEffector2D &" + effA + " → mất tính oneway — "
                    + compStat + " — " + stat);
            return;
        }
        if (comps.size() != (oneway ? 3 : 2)) {
            bad(name, "m_Component có " + comps.size() + " mục (mong đợi " + (oneway ? 3 : 2)
                    + ") — " + compStat + " — " + stat);
            return;
        }

        // (c) anchor mới phải DUY NHẤT trong file
        long[] fresh = oneway ? new long[]{goA, trA, colA, effA} : new long[]{goA, trA, colA};
        for (long a : fresh) {
            int cnt = countAnchorHeaders(out, a);
            if (cnt != 1) { bad(name, "anchor &" + a + " xuất hiện " + cnt + " lần trong file — " + stat); return; }
        }
        for (int i = 0; i < fresh.length; i++) {
            for (int j = i + 1; j < fresh.length; j++) {
                if (fresh[i] == fresh[j]) { bad(name, "2 anchor mới trùng nhau: &" + fresh[i] + " — " + stat); return; }
            }
        }

        // (d) số node tăng đúng 1
        if (back.nodes().size() != n0 + 1) {
            bad(name, "số node " + n0 + " → " + back.nodes().size() + " (mong đợi " + (n0 + 1) + ") — " + stat);
            return;
        }

        // (e) mọi node CŨ còn nguyên
        String moved = compareCenters(centers0, back, 0);
        if (moved != null) { bad(name, "node cũ bị ảnh hưởng: " + moved + " — " + stat); return; }

        // (f) PrefabParser cũ vẫn đọc được, số layer không đổi
        int layers1 = parserLayerCount(out);
        if (layers1 < 0) { bad(name, "PrefabParser CŨ không parse được file mới — " + stat); return; }
        if (layers1 != layers0) {
            bad(name, "số layer PrefabParser " + layers0 + " → " + layers1 + " — " + stat); return;
        }
        stat += " · PrefabParser cũ vẫn đọc " + layers1 + " layer";

        // (g) ONEWAY: block PlatformEffector2D + m_UsedByEffector
        PrefabDocument doc2 = back.doc();
        PrefabDocument.Block colB = doc2.block(n2.colAnchor);
        int usedByEff = (colB == null) ? -1 : doc2.getInt(colB, "m_UsedByEffector:", -1);
        if (oneway) {
            PrefabDocument.Block eb = effectorBlock(doc2, goA);
            if (eb == null) {
                bad(name, "KHÔNG có block PlatformEffector2D (!u!251) nào trỏ tới GameObject &" + goA + " — " + stat);
                return;
            }
            if (eb.classId != 251) { bad(name, "block effector classId " + eb.classId + " ≠ 251 — " + stat); return; }
            if (!n2.hasPlatformEffector) { bad(name, "loader không nhận ra node là oneway — " + stat); return; }
            if (usedByEff != 1) {
                bad(name, "m_UsedByEffector = " + usedByEff + " ≠ 1 → PlatformEffector2D vô hiệu — " + stat);
                return;
            }
            stat += " · effector !u!251 &" + eb.anchor + ", m_UsedByEffector = 1";
        } else {
            if (usedByEff != 0) { bad(name, "đường ĐẤT nhưng m_UsedByEffector = " + usedByEff + " — " + stat); return; }
        }

        // (h) TẬP KEY của block mới == tập key của block cùng loại ĐÃ CÓ trong file
        String keyChk = compareBlockKeys(doc2, 68, colA, "EdgeCollider2D");
        if (keyChk != null) { bad(name, keyChk + " — " + stat); return; }
        long refCol = anchorOfClass(doc2, 68, colA);
        stat += " · tập key EdgeCollider2D "
                + (refCol == 0 ? "(file không có block mẫu để so)" : "khớp block mẫu &" + refCol);
        if (oneway) {
            String effChk = compareBlockKeys(doc2, 251, effAnchorInFile(doc2, goA), "PlatformEffector2D");
            if (effChk != null) { bad(name, effChk + " — " + stat); return; }
        }
        // GameObject + Transform mới cũng phải cùng schema với block cùng loại có sẵn
        String goChk = compareBlockKeys(doc2, 1, goA, "GameObject");
        if (goChk != null) { bad(name, goChk + " — " + stat); return; }
        String trChk = compareBlockKeys(doc2, 4, trA, "Transform");
        if (trChk != null) { bad(name, trChk + " — " + stat); return; }

        ok(name, stat);
    }

    /**
     * THÊM 2 ĐƯỜNG TRONG CÙNG 1 PHIÊN (chưa lưu ở giữa) — đúng kịch bản người dùng vẽ liên tục
     * mấy đường rồi mới bấm Lưu. Bắt được 2 lỗi mà lượt thêm 1 đường KHÔNG bắt được:
     * cấp trùng anchor (newAnchor phải nhớ cả anchor đã cấp mà chưa chèn), và tính sai chỗ chèn
     * cho block thứ 2 sau khi block thứ nhất đã làm lệch chỉ số dòng.
     */
    private void t18Multi(int mapId, Path copy) throws IOException {
        String name = "T18 thêm 2 đường cùng lúc Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        MapScene.Node parent = pickLineParent(scene);
        if (parent == null) { skip(name, "map không có node nào làm cha được"); return; }

        int n0 = scene.nodes().size();
        Map<Long, double[]> centers0 = snapshotCenters(scene);
        int layers0 = parserLayerCount(copy);

        double[][] wa = {{-8, -1}, {-2, -1}};
        double[][] wb = {{2, 3}, {8, 3}, {11, 0.5}};
        MapScene.Node a = scene.addEdgeLine(parent, "Test_Multi_A", MapScene.LineKind.GROUND, wa);
        MapScene.Node b = scene.addEdgeLine(parent, "Test_Multi_B", MapScene.LineKind.ONEWAY, wb);
        if (a == null || b == null) { bad(name, "addEdgeLine trả null (a=" + a + ", b=" + b + ")"); return; }

        Path out = work.resolve("Map_" + mapId + ".t18c.prefab");
        MapSceneWriter.Result wr = MapSceneWriter.saveAs(scene, sortLayers, out);
        if (!wr.ok()) { bad(name, "writer báo lỗi: " + String.join(" | ", wr.log)); return; }

        MapScene back = load(out, mapId);
        if (back == null) { bad(name, "load lại file đã ghi lỗi"); return; }
        String stat = "'Test_Multi_A' (&" + a.goAnchor + ") + 'Test_Multi_B' oneway (&" + b.goAnchor
                + ") dưới cha '" + parent.name + "' · node " + n0 + " → " + back.nodes().size();

        if (back.nodes().size() != n0 + 2) {
            bad(name, "số node phải là " + (n0 + 2) + " — " + stat); return;
        }
        // 7 anchor mới (3 + 4 vì B là oneway) phải ĐÔI MỘT KHÁC NHAU và mỗi cái xuất hiện đúng 1 lần
        long[] all = {a.goAnchor, a.trAnchor, a.colAnchor,
                      b.goAnchor, b.trAnchor, b.colAnchor, b.effAnchor};
        for (int i = 0; i < all.length; i++) {
            if (all[i] == 0) { bad(name, "anchor thứ " + i + " chưa được cấp (= 0) — " + stat); return; }
            int cnt = countAnchorHeaders(out, all[i]);
            if (cnt != 1) { bad(name, "anchor &" + all[i] + " xuất hiện " + cnt + " lần — " + stat); return; }
            for (int j = i + 1; j < all.length; j++) {
                if (all[i] == all[j]) { bad(name, "2 anchor mới TRÙNG nhau: &" + all[i] + " — " + stat); return; }
            }
        }
        List<Long> kids = fileIdListInFile(out, parent.trAnchor, "m_Children:");
        if (!kids.contains(a.trAnchor) || !kids.contains(b.trAnchor)) {
            bad(name, "m_Children của cha (" + kids.size() + " con) thiếu &" + a.trAnchor
                    + " hoặc &" + b.trAnchor + " — " + stat);
            return;
        }
        // toạ độ world của CẢ HAI đường
        MapScene.Node a2 = back.byGo(a.goAnchor), b2 = back.byGo(b.goAnchor);
        if (a2 == null || b2 == null) { bad(name, "không tìm lại được 1 trong 2 node mới — " + stat); return; }
        String pe = checkWorldPoints(back, a2, wa);
        if (pe != null) { bad(name, "đường A: " + pe + " — " + stat); return; }
        pe = checkWorldPoints(back, b2, wb);
        if (pe != null) { bad(name, "đường B: " + pe + " — " + stat); return; }
        if (b2.physLayer != MapScene.LAYER_ONEWAY || !b2.hasPlatformEffector) {
            bad(name, "đường B không phải oneway (layer " + b2.physLayer
                    + ", effector " + b2.hasPlatformEffector + ") — " + stat);
            return;
        }
        if (a2.physLayer != MapScene.LAYER_GROUND || a2.hasPlatformEffector) {
            bad(name, "đường A không phải đường đất thuần (layer " + a2.physLayer
                    + ", effector " + a2.hasPlatformEffector + ") — " + stat);
            return;
        }
        String moved = compareCenters(centers0, back, 0);
        if (moved != null) { bad(name, "node cũ bị ảnh hưởng: " + moved + " — " + stat); return; }
        int layers1 = parserLayerCount(out);
        if (layers1 != layers0) {
            bad(name, "số layer PrefabParser " + layers0 + " → " + layers1 + " — " + stat); return;
        }
        ok(name, stat + " · 7 anchor mới đều duy nhất · cả 2 đường đúng toạ độ world"
                + " · PrefabParser cũ vẫn đọc " + layers1 + " layer");
    }

    /** null = mọi đỉnh khớp toạ độ world mong đợi; ngược lại mô tả đỉnh đầu tiên lệch. */
    private static String checkWorldPoints(MapScene scene, MapScene.Node n, double[][] want) {
        if (n.pts == null || n.pts.length != want.length) {
            return "số đỉnh " + (n.pts == null ? "null" : n.pts.length) + " ≠ " + want.length;
        }
        double[][] got = scene.worldPoints(n);
        for (int i = 0; i < want.length; i++) {
            if (!near(got[i][0], want[i][0]) || !near(got[i][1], want[i][1])) {
                return "đỉnh " + i + " mong đợi (" + fmt3(want[i][0]) + ", " + fmt3(want[i][1])
                        + "), đọc lại (" + fmt3(got[i][0]) + ", " + fmt3(got[i][1]) + ")";
            }
        }
        return null;
    }

    // ───────────────────────────── T19 ─────────────────────────────

    /**
     * XOÁ ĐƯỜNG KẺ — gỡ nguyên block khỏi file. Kèm phép thử NGƯỢC: biên map
     * (Top/Bottom/Left/Right — MapManager giữ qua {@code _edgeColTop/Btm/Left/Right})
     * PHẢI bị chặn không cho xoá.
     */
    private void t19RemoveLine(int mapId, Path copy) throws IOException {
        String name = "T19 xoá đường kẻ Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        // Đường KHÔNG quan trọng: EdgeCollider2D, không có node con, và canRemove() cho phép.
        MapScene.Node victim = null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind != MapScene.ColKind.EDGE) continue;
            if (!scene.children(n).isEmpty()) continue;
            if (!scene.canRemove(n)) continue;
            victim = n;
            break;
        }
        if (victim == null) { skip(name, "map không có đường kẻ nào xoá được (đều bị tham chiếu)"); return; }

        int n0 = scene.nodes().size();
        long goA = victim.goAnchor, trA = victim.trAnchor, colA = victim.colAnchor;
        long parentTr = victim.parentTr;
        // Mọi anchor sẽ biến mất: GameObject + tất cả component của nó (kể cả component mà
        // model không giữ anchor như PlatformEffector2D / MonoBehaviour).
        List<Long> gone = new ArrayList<>();
        gone.add(goA);
        for (PrefabDocument.Block b : scene.doc().blocksOfGameObject(goA)) {
            if (!gone.contains(b.anchor)) gone.add(b.anchor);
        }
        for (long x : new long[]{trA, colA}) if (x != 0 && !gone.contains(x)) gone.add(x);
        Map<Long, double[]> centers0 = snapshotCenters(scene);
        Map<Long, double[][]> edges0 = snapshotEdges(scene, colA);
        int layers0 = parserLayerCount(copy);

        if (!scene.removeNode(victim)) {
            bad(name, "removeNode trả false dù canRemove == true: " + scene.whyCannotRemove(victim));
            return;
        }
        Path out = work.resolve("Map_" + mapId + ".t19.prefab");
        MapSceneWriter.Result wr = MapSceneWriter.saveAs(scene, sortLayers, out);
        if (!wr.ok()) { bad(name, "writer báo lỗi: " + String.join(" | ", wr.log)); return; }

        MapScene back = load(out, mapId);
        if (back == null) { bad(name, "load lại file đã ghi lỗi"); return; }
        String stat = "'" + victim.name + "' (&" + goA + ", " + gone.size() + " block, cha &" + parentTr
                + ") · node " + n0 + " → " + back.nodes().size();

        // (a) node biến mất, tổng số node giảm đúng 1
        if (back.byGo(goA) != null || back.byTr(trA) != null) {
            bad(name, "node vẫn còn sau khi xoá — " + stat); return;
        }
        if (back.nodes().size() != n0 - 1) {
            bad(name, "số node giảm không đúng 1 (mong đợi " + (n0 - 1) + ") — " + stat); return;
        }

        // (b) m_Children của cha không còn anchor Transform đã xoá
        if (parentTr != 0) {
            List<Long> kids = fileIdListInFile(out, parentTr, "m_Children:");
            if (kids.contains(trA)) {
                bad(name, "m_Children của cha &" + parentTr + " VẪN còn &" + trA + " → fileID mồ côi — " + stat);
                return;
            }
        }

        // (c) không còn DÒNG nào trong file nhắc tới anchor đã xoá
        for (long a : gone) {
            List<String> hit = linesMentioning(out, a);
            if (!hit.isEmpty()) {
                bad(name, "file còn " + hit.size() + " dòng nhắc anchor &" + a
                        + ", ví dụ: " + hit.get(0).trim() + " — " + stat);
                return;
            }
        }

        // (d) các node khác giữ nguyên tâm world + m_Points
        String moved = compareCenters(centers0, back, goA);
        if (moved != null) { bad(name, "node khác bị ảnh hưởng: " + moved + " — " + stat); return; }
        String diff = compareEdges(edges0, snapshotEdges(back, 0));
        if (diff != null) { bad(name, "đường kẻ khác bị đổi: " + diff + " — " + stat); return; }

        // (e) PrefabParser cũ vẫn đọc được, số layer không đổi (đường kẻ không có renderer)
        int layers1 = parserLayerCount(out);
        if (layers1 < 0) { bad(name, "PrefabParser CŨ không parse được file sau khi xoá — " + stat); return; }
        if (layers1 != layers0) { bad(name, "số layer PrefabParser " + layers0 + " → " + layers1 + " — " + stat); return; }

        // ── phép thử NGƯỢC: biên map PHẢI bị chặn ──
        MapScene s2 = load(copy, mapId);
        if (s2 == null) { bad(name, "load lại lần 2 lỗi — " + stat); return; }
        MapScene.Node keep = pickProtectedNode(s2);
        if (keep == null) {
            ok(name, stat + " · " + edges0.size() + " đường khác nguyên vẹn"
                    + " · PrefabParser cũ vẫn đọc " + layers1 + " layer"
                    + " · [không có node biên/MapManager để thử phép chặn]");
            return;
        }
        String why = s2.whyCannotRemove(keep);
        if (why == null || why.isEmpty()) {
            bad(name, "node BIÊN MAP '" + keep.name + "' (&" + keep.goAnchor + ") lại cho phép xoá"
                    + " → sẽ hỏng map — " + stat);
            return;
        }
        if (s2.canRemove(keep)) { bad(name, "canRemove('" + keep.name + "') == true dù có lý do chặn — " + stat); return; }
        if (s2.removeNode(keep)) { bad(name, "removeNode('" + keep.name + "') vẫn xoá được — " + stat); return; }

        ok(name, stat + " · " + edges0.size() + " đường khác nguyên vẹn"
                + " · PrefabParser cũ vẫn đọc " + layers1 + " layer"
                + " · CHẶN xoá '" + keep.name + "': " + shorten(why, 110));
    }

    // ───────────────────────────── T20 ─────────────────────────────

    /**
     * ROUND-TRIP CẤU TRÚC: thêm 1 đường → ghi → đọc lại → xoá CHÍNH đường đó → ghi.
     * File cuối phải giống hệt file gốc TỪNG BYTE (chèn và xoá block là 2 phép nghịch đảo nhau).
     * Nếu không giống byte thì đối chiếu tiếp số dòng + tập hợp dòng để chỉ rõ khác ở đâu.
     */
    private void t20AddThenRemove(int mapId, Path copy) throws IOException {
        String name = "T20 thêm rồi xoá (round-trip cấu trúc) Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        MapScene.Node parent = pickLineParent(scene);
        if (parent == null) { skip(name, "map không có node nào làm cha được"); return; }

        MapScene.Node line = scene.addEdgeLine(parent, "Test_RoundTrip",
                MapScene.LineKind.GROUND, new double[][]{{-3.25, 1.5}, {0, 1.5}, {4.75, -2}});
        if (line == null) { bad(name, "addEdgeLine trả null"); return; }
        long goA = line.goAnchor;

        Path mid = work.resolve("Map_" + mapId + ".t20a.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, mid);

        MapScene s2 = load(mid, mapId);
        if (s2 == null) { bad(name, "load lại file sau khi THÊM lỗi"); return; }
        MapScene.Node l2 = s2.byGo(goA);
        if (l2 == null) { bad(name, "không tìm lại được node vừa thêm &" + goA); return; }
        if (!s2.removeNode(l2)) { bad(name, "không xoá được node vừa thêm: " + s2.whyCannotRemove(l2)); return; }

        Path out = work.resolve("Map_" + mapId + ".t20b.prefab");
        MapSceneWriter.saveAs(s2, sortLayers, out);

        byte[] a = Files.readAllBytes(copy), b = Files.readAllBytes(out);
        int midLines = Files.readAllLines(mid, StandardCharsets.UTF_8).size();
        int srcLines = Files.readAllLines(copy, StandardCharsets.UTF_8).size();
        String stat = "thêm 3 đỉnh → file " + srcLines + " → " + midLines + " dòng, xoá → "
                + Files.readAllLines(out, StandardCharsets.UTF_8).size() + " dòng"
                + " · " + a.length + " byte gốc / " + b.length + " byte cuối";
        if (java.util.Arrays.equals(a, b)) { ok(name, stat + " · GIỐNG HỆT file gốc từng byte"); return; }

        // Không giống byte → chỉ rõ khác ở đâu (tập hợp dòng + số dòng).
        List<String> la = Files.readAllLines(copy, StandardCharsets.UTF_8);
        List<String> lb = Files.readAllLines(out, StandardCharsets.UTF_8);
        if (la.size() != lb.size()) { bad(name, "số dòng " + la.size() + " → " + lb.size() + " — " + stat); return; }
        List<String> sa = new ArrayList<>(la), sb = new ArrayList<>(lb);
        Collections.sort(sa);
        Collections.sort(sb);
        if (!sa.equals(sb)) {
            String ex = "?";
            for (int i = 0; i < sa.size(); i++) {
                if (!sa.get(i).equals(sb.get(i))) { ex = "'" + sa.get(i).trim() + "' ↔ '" + sb.get(i).trim() + "'"; break; }
            }
            bad(name, "tập hợp dòng KHÁC nhau, ví dụ " + ex + " — " + stat);
            return;
        }
        int[] d = lineDiff(copy, out);
        bad(name, "cùng tập hợp dòng nhưng THỨ TỰ khác ở " + d[0] + " dòng — " + stat);
    }

    // ───────────────────────────── T28 ─────────────────────────────

    /**
     * ĐỔI KIỂU CHẶN của một đường kẻ (player đi xuyên qua theo chiều nào) — 4 bước liên tiếp
     * trên CÙNG một đường, mỗi bước ghi ra file rồi NẠP LẠI đối chiếu:
     * <pre>
     *   a) ĐẤT → "nhảy xuyên từ DƯỚI lên": m_Layer 6→19, CHÈN block !u!251 + nối vào m_Component
     *      của GameObject, m_UsedByEffector 0→1, xoay 0°, cung 90°
     *   b) → "chặn đi LÊN": ĐÚNG 1 dòng file đổi (m_RotationalOffset 0 → 180)
     *   c) → "không chặn": cung 90° → 0°
     *   d) → về ĐẤT: GỠ sạch block !u!251 + dòng m_Component ⇒ file phải GIỐNG HỆT bản gốc
     *      từng byte (bằng chứng bật rồi tắt không để lại rác)
     * </pre>
     */
    private void t28LineBlock(int mapId, Path copy) throws IOException {
        String name = "T28 đổi kiểu chặn đường kẻ Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        MapScene.Node t = null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind == MapScene.ColKind.EDGE && !n.hasPlatformEffector
                    && n.physLayer == MapScene.LAYER_GROUND) { t = n; break; }
        }
        if (t == null) { skip(name, "map không có đường đất (layer 6) nào"); return; }
        long goA = t.goAnchor, colA = t.colAnchor;
        String stat = "'" + t.name + "' &" + goA;

        // (a) ĐẤT → oneway nhảy từ dưới lên
        scene.setLineBlock(t, MapScene.LineBlock.ONEWAY_UP);
        Path pa = work.resolve("Map_" + mapId + ".t28a.prefab");
        MapSceneWriter.Result wa = MapSceneWriter.saveAs(scene, sortLayers, pa);
        if (!wa.ok()) { bad(name, "writer lỗi (a): " + String.join(" | ", wa.log)); return; }
        MapScene sa = load(pa, mapId);
        MapScene.Node na = (sa == null) ? null : sa.byGo(goA);
        if (na == null) { bad(name, "mất node sau bước a — " + stat); return; }
        if (!na.hasPlatformEffector || na.physLayer != MapScene.LAYER_ONEWAY
                || na.lineBlock() != MapScene.LineBlock.ONEWAY_UP) {
            bad(name, "bước a sai: layer " + na.physLayer + " · effector " + na.hasPlatformEffector
                    + " · kiểu " + na.lineBlock() + " — " + stat);
            return;
        }
        String used = rawOf(sa, colA, "m_UsedByEffector:");
        if (!"1".equals(used)) {
            bad(name, "bước a: m_UsedByEffector = " + used
                    + " (phải là 1, không thì PlatformEffector2D VÔ HIỆU) — " + stat);
            return;
        }
        int ec = effCount(sa, goA);
        if (ec != 1) { bad(name, "bước a: " + ec + " block !u!251 (phải đúng 1) — " + stat); return; }
        if (!componentListHas(sa, goA, na.effAnchor)) {
            bad(name, "bước a: m_Component của GameObject KHÔNG có effector &" + na.effAnchor
                    + " ⇒ Unity bỏ rơi component — " + stat);
            return;
        }

        // (b) → chặn đi lên (chỉ xoay cung 180°): đúng 1 dòng đổi
        sa.setLineBlock(na, MapScene.LineBlock.ONEWAY_DOWN);
        Path pb = work.resolve("Map_" + mapId + ".t28b.prefab");
        MapSceneWriter.saveAs(sa, sortLayers, pb);
        MapScene sb = load(pb, mapId);
        MapScene.Node nb = (sb == null) ? null : sb.byGo(goA);
        int[] d = lineDiff(pa, pb);
        if (nb == null || nb.lineBlock() != MapScene.LineBlock.ONEWAY_DOWN) {
            bad(name, "bước b sai kiểu: " + (nb == null ? "mất node" : nb.lineBlock()) + " — " + stat);
            return;
        }
        if (d[1] != d[2] || d[0] != 1) {
            bad(name, "bước b phải đúng 1 dòng đổi, thực tế " + d[0]
                    + " (dòng " + d[1] + " → " + d[2] + ") — " + stat);
            return;
        }

        // (c) → không chặn chiều nào (cung 0°)
        sb.setLineBlock(nb, MapScene.LineBlock.PASS);
        Path pc = work.resolve("Map_" + mapId + ".t28c.prefab");
        MapSceneWriter.saveAs(sb, sortLayers, pc);
        MapScene s3 = load(pc, mapId);
        MapScene.Node nc = (s3 == null) ? null : s3.byGo(goA);
        if (nc == null || nc.lineBlock() != MapScene.LineBlock.PASS || nc.effSurfaceArc > 1e-6) {
            bad(name, "bước c sai: " + (nc == null ? "mất node"
                    : nc.lineBlock() + " · cung " + nc.effSurfaceArc) + " — " + stat);
            return;
        }

        // (d) → về ĐẤT: gỡ sạch, file phải trùng bản gốc từng byte
        s3.setLineBlock(nc, MapScene.LineBlock.SOLID);
        Path pd = work.resolve("Map_" + mapId + ".t28d.prefab");
        MapSceneWriter.saveAs(s3, sortLayers, pd);
        MapScene s4 = load(pd, mapId);
        MapScene.Node nd = (s4 == null) ? null : s4.byGo(goA);
        if (nd == null || nd.hasPlatformEffector || nd.physLayer != MapScene.LAYER_GROUND) {
            bad(name, "bước d sai: " + (nd == null ? "mất node"
                    : "layer " + nd.physLayer + " · effector " + nd.hasPlatformEffector) + " — " + stat);
            return;
        }
        if (effCount(s4, goA) != 0) {
            bad(name, "bước d còn " + effCount(s4, goA) + " block !u!251 — " + stat);
            return;
        }
        stat += " · dòng file " + Files.readAllLines(copy, StandardCharsets.UTF_8).size() + " → "
                + Files.readAllLines(pa, StandardCharsets.UTF_8).size() + " → "
                + Files.readAllLines(pd, StandardCharsets.UTF_8).size();
        byte[] a = Files.readAllBytes(copy), b = Files.readAllBytes(pd);
        if (!java.util.Arrays.equals(a, b)) {
            int[] dd = lineDiff(copy, pd);
            bad(name, "bật rồi tắt oneway KHÔNG về đúng file gốc: " + dd[0] + " dòng khác ("
                    + dd[1] + " → " + dd[2] + ") — " + stat);
            return;
        }
        ok(name, stat + " · bật/tắt oneway round-trip GIỐNG HỆT file gốc từng byte");
    }

    /** Giá trị thô của 1 key trong block {@code anchor} của scene vừa nạp. */
    private static String rawOf(MapScene s, long anchor, String key) {
        if (s == null || s.doc() == null) return "?";
        PrefabDocument.Block b = s.doc().block(anchor);
        return (b == null) ? "?" : String.valueOf(s.doc().getScalar(b, key));
    }

    /** Số block PlatformEffector2D (!u!251) đang gắn vào GameObject {@code goA}. */
    private static int effCount(MapScene s, long goA) {
        if (s == null || s.doc() == null) return -1;
        int c = 0;
        for (PrefabDocument.Block b : s.doc().blocksOfGameObject(goA)) if (b.classId == 251) c++;
        return c;
    }

    /** m_Component của GameObject có trỏ tới component {@code compAnchor} không. */
    private static boolean componentListHas(MapScene s, long goA, long compAnchor) {
        if (s == null || s.doc() == null || compAnchor == 0) return false;
        PrefabDocument doc = s.doc();
        PrefabDocument.Block gb = doc.block(goA);
        if (gb == null) return false;
        int ln = doc.findLine(gb, "m_Component:");
        if (ln < 0) return false;
        List<String> lines = doc.lines();
        String needle = "fileID: " + compAnchor + "}";
        for (int i = ln + 1; i < lines.size() && lines.get(i).trim().startsWith("- "); i++) {
            if (lines.get(i).contains(needle)) return true;
        }
        return false;
    }

    // ───────────────────────────── T21 (Y1 hít khít) ─────────────────────────────

    /**
     * HÍT KHÍT — gọi THẲNG {@link SnapEngine} (headless, không cần GUI) trên DỮ LIỆU THẬT của map.
     *
     * <p>Danh sách hình đích dựng y hệt canvas lúc bắt đầu kéo: hộp bao world (AABB) của mọi node
     * có renderer đang bật, bỏ node Spine / node tự chuyển động. Sau đó thả 1 hình chữ nhật cạnh
     * mép PHẢI của một hình thật rồi kiểm 4 điều:
     * <pre>
     *   (a) lệch 0.02 unit (ngưỡng 0.08) → hít về KHÍT tuyệt đối, hở &lt; 1e-9 unit
     *   (b) lệch 5    unit               → KHÔNG hít, dx = 0
     *   (c) tắt hít khít                 → dx = dy = 0 dù đang sát mép
     *   (d) ngưỡng tính theo PX màn hình → cùng 0.02 unit: zoom 100 px/u hít, zoom 1000 px/u không
     * </pre>
     *
     * <p>Hình mốc phải "CÔ LẬP" (không hình nào khác có mốc X trong 1 unit quanh 6 vị trí phép thử
     * dùng tới) — nếu không, một hình khác có thể thắng và số đo mất ý nghĩa.
     */
    private void t21Snap(int mapId, MapScene scene) {
        String name = "T21 hít khít (SnapEngine) Map" + mapId;
        List<SnapEngine.Rect> rs = snapRects(scene);
        if (rs.size() < 2) { skip(name, "chỉ có " + rs.size() + " hình có kích thước — không đủ để thử hít"); return; }

        final double W = 4.0;                                   // bề rộng hình đang kéo
        final double FAR = 5.0;                                 // độ lệch của phép thử (b)
        final double[] probes = {0, W / 2, W, FAR, FAR + W / 2, FAR + W};
        // Bán kính cô lập 0.3 unit ≫ ngưỡng hít 0.08 unit (8 px ở zoom 100 px/u) + độ lệch 0.02
        // ⇒ không mốc nào khác lọt vào tầm hít, nhưng vẫn đủ lỏng để map dày đặc cũng tìm được hình.
        SnapEngine.Rect ref = null;
        for (SnapEngine.Rect cand : byAreaDesc(rs)) {
            boolean isolated = true;
            for (double d : probes) {
                if (!isolatedX(rs, cand, cand.maxX() + d, 0.3)) { isolated = false; break; }
            }
            if (isolated) { ref = cand; break; }
        }
        if (ref == null) { skip(name, "map quá dày đặc, không có hình nào đủ cô lập để đo hít chính xác"); return; }

        double h = Math.max(0.5, Math.min(ref.h() * 0.5, 4.0));
        double y0 = ref.cy() - h / 2, y1 = ref.cy() + h / 2;
        final double GAP = 0.02;
        SnapEngine.Rect nearR = new SnapEngine.Rect(ref.maxX() + GAP, y0, ref.maxX() + GAP + W, y1, "__T21__");
        SnapEngine.Rect farR  = new SnapEngine.Rect(ref.maxX() + FAR, y0, ref.maxX() + FAR + W, y1, "__T21__");

        SnapEngine eng = new SnapEngine();
        eng.setThresholdPx(SnapEngine.DEFAULT_THRESHOLD_PX);    // 8 px
        final double ZOOM_IN = 100.0, ZOOM_FAR = 1000.0;        // tol 0.08 unit / 0.008 unit

        StringBuilder sb = new StringBuilder("mốc '" + ref.name() + "' mép phải x = " + fmt3(ref.maxX())
                + " (" + rs.size() + " hình thật) · ngưỡng 8 px");
        if (Math.abs(eng.tolUnit(ZOOM_IN) - 0.08) > 1e-12) {
            bad(name, "tolUnit(100 px/u) = " + fmt6(eng.tolUnit(ZOOM_IN)) + " ≠ 0.08 — " + sb);
            return;
        }

        // (a) lệch 0.02 → phải hít KHÍT
        SnapEngine.Result ra = eng.snapRect(nearR, rs, null, null, ZOOM_IN);
        double leftAfter = nearR.minX() + ra.dx();
        sb.append(" · (a) lệch 0.02 → dx ").append(fmt6(ra.dx()));
        if (!ra.hitX()) { bad(name, "(a) lệch 0.02 unit < tol 0.08 mà KHÔNG hít — " + sb); return; }
        if (Math.abs(leftAfter - ref.maxX()) > 1e-9) {
            bad(name, "(a) hít xong còn hở " + fmt6(leftAfter - ref.maxX()) + " unit (yêu cầu < 1e-9) — " + sb);
            return;
        }
        sb.append(" (hở ").append(String.format(Locale.US, "%.1e", Math.abs(leftAfter - ref.maxX())))
          .append(" unit, mốc '").append(ra.guidesX().get(0).label()).append("')");

        // (b) lệch 5 unit → KHÔNG hít
        SnapEngine.Result rb = eng.snapRect(farR, rs, null, null, ZOOM_IN);
        sb.append(" · (b) lệch 5 → dx ").append(fmt6(rb.dx()));
        if (rb.hitX() || rb.dx() != 0) { bad(name, "(b) lệch 5 unit ≫ tol 0.08 mà vẫn hít — " + sb); return; }

        // (c) tắt hít khít → không dịch gì
        eng.setEnabled(false);
        SnapEngine.Result rc = eng.snapRect(nearR, rs, null, null, ZOOM_IN);
        sb.append(" · (c) tắt → dx ").append(fmt6(rc.dx())).append(" dy ").append(fmt6(rc.dy()));
        if (rc.dx() != 0 || rc.dy() != 0 || rc.hit()) { bad(name, "(c) đã TẮT hít khít mà vẫn dịch — " + sb); return; }
        eng.setEnabled(true);

        // (d) ngưỡng tính theo PX: cùng độ lệch, zoom càng gần thì ngưỡng unit càng nhỏ
        SnapEngine.Result rd = eng.snapRect(nearR, rs, null, null, ZOOM_FAR);
        sb.append(" · (d) zoom 1000 px/u (tol ").append(fmt3(eng.tolUnit(ZOOM_FAR))).append(") → dx ")
          .append(fmt6(rd.dx()));
        if (rd.hitX() || rd.dx() != 0) {
            bad(name, "(d) zoom 1000 px/u ⇒ tol 0.008 < độ lệch 0.02 mà vẫn hít — " + sb);
            return;
        }
        ok(name, sb.toString());
    }

    /** Hộp bao world của các node có thể hít vào — dựng y hệt {@code MapLayoutCanvas.beginSnapContext}. */
    private static List<SnapEngine.Rect> snapRects(MapScene scene) {
        List<SnapEngine.Rect> rs = new ArrayList<>();
        for (MapScene.Node n : scene.nodes()) {
            if (!n.hasRenderer || n.baseW <= 0 || n.baseH <= 0 || n.alpha <= 0.02) continue;
            if (!scene.activeInHierarchy(n)) continue;
            // node Spine / node tự chuyển động lúc chạy game ⇒ hít vào chúng là vô nghĩa
            if (n.fxMotion() != MapScene.EffectKind.NONE || n.hasFx(MapScene.EffectKind.SPINE)) continue;
            double[] c = scene.worldCenter(n), s = scene.worldSize(n);
            if (s[0] <= 0 || s[1] <= 0) continue;
            double a = Math.toRadians(scene.worldAngleDeg(n));
            double ca = Math.abs(Math.cos(a)), sa = Math.abs(Math.sin(a));
            double hw = (s[0] * ca + s[1] * sa) / 2, hh = (s[0] * sa + s[1] * ca) / 2;
            rs.add(new SnapEngine.Rect(c[0] - hw, c[1] - hh, c[0] + hw, c[1] + hh, n.name));
        }
        return rs;
    }

    /** Bản sao đã sắp theo DIỆN TÍCH giảm dần (hình to = mốc dễ nhìn, hay được kéo dán vào nhất). */
    private static List<SnapEngine.Rect> byAreaDesc(List<SnapEngine.Rect> rs) {
        List<SnapEngine.Rect> out = new ArrayList<>(rs);
        out.sort((p, q) -> Double.compare(q.w() * q.h(), p.w() * p.h()));
        return out;
    }

    /**
     * Quanh toạ độ {@code x} (bán kính {@code gap}) KHÔNG có mốc X nào của bất kỳ hình nào —
     * trừ đúng mép phải của {@code self} (là mốc mà phép thử CHỜ ĐỢI hít vào).
     */
    private static boolean isolatedX(List<SnapEngine.Rect> all, SnapEngine.Rect self, double x, double gap) {
        double expect = self.maxX();
        for (SnapEngine.Rect r : all) {
            double[] marks = {r.minX(), r.cx(), r.maxX()};
            for (double m : marks) {
                if (r == self && Math.abs(m - expect) < 1e-12) continue;
                if (Math.abs(m - x) < gap) return false;
            }
        }
        return true;
    }

    // ───────────────────────────── T22 (Y2 biên map) ─────────────────────────────

    /**
     * 4 THANH BIÊN MAP — thứ quyết định KHUNG CAMERA của client.
     *
     * <p>Nhận diện phải đi theo 4 fileID {@code _edgeColTop/_edgeColBtm/_edgeColLeft/_edgeColRight}
     * trong block MapManager (đọc THẲNG text prefab ở đây, KHÔNG qua loader) chứ không theo tên;
     * cả 4 phải bị CHẶN xoá. Sau đó kéo thanh "left" đi (−7.5, +1.25) và kiểm:
     * m_LocalPosition ghi đúng, {@code cameraBounds()} đổi đúng (kéo left chỉ đổi minX — y của nó
     * KHÔNG ảnh hưởng khung), CHỈ 1 dòng file đổi, mọi node khác đứng yên.
     */
    private void t22Bounds(int mapId, Path copy) throws IOException {
        String name = "T22 kéo thanh biên map Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        List<String> ls = Files.readAllLines(copy, StandardCharsets.UTF_8);
        long[] want = rawMapManagerEdges(ls);        // {top, bottom, left, right}
        if (want == null) { skip(name, "prefab không có block MapManager → map này không có 4 thanh biên"); return; }

        String[] dirs = {"top", "bottom", "left", "right"};
        MapScene.Node[] es = new MapScene.Node[4];
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (want[i] == 0) { skip(name, "MapManager để trống _edgeCol" + dirs[i] + " (fileID 0)"); return; }
            es[i] = scene.boundsEdge(dirs[i]);
            if (es[i] == null) { bad(name, "không tìm được biên " + dirs[i] + " (MapManager trỏ &" + want[i] + ")"); return; }
            if (es[i].trAnchor != want[i]) {
                bad(name, "biên " + dirs[i] + " nhận nhầm node '" + es[i].name + "' (&" + es[i].trAnchor
                        + "), MapManager trỏ &" + want[i]);
                return;
            }
            if (!scene.isBoundsEdge(es[i])) { bad(name, "isBoundsEdge('" + es[i].name + "') = false"); return; }
            if (scene.canRemove(es[i])) { bad(name, "biên " + dirs[i] + " ('" + es[i].name + "') VẪN cho xoá"); return; }
            if (sb.length() > 0) sb.append(", ");
            sb.append(dirs[i]).append("='").append(es[i].name).append("'");
        }
        // node thường KHÔNG được nhận nhầm là biên
        MapScene.Node plain = firstRenderer(scene);
        if (plain != null && scene.isBoundsEdge(plain)) {
            bad(name, "node thường '" + plain.name + "' bị nhận nhầm là thanh biên"); return;
        }

        double[] cb0 = scene.cameraBounds();
        if (cb0 == null) { bad(name, "cameraBounds() = null dù đủ 4 biên — " + sb); return; }

        Map<Long, double[]> before = snapshotCenters(scene);
        MapScene.Node left = es[2];
        Set<Long> ignore = subtreeGos(scene, left);
        double[] o0 = scene.worldOrigin(left);
        double nx = o0[0] - 7.5, ny = o0[1] + 1.25;
        scene.setWorldOrigin(left, nx, ny);

        double[] cbMem = scene.cameraBounds();
        if (cbMem == null || !near(cbMem[0], nx) || !near(cbMem[1], cb0[1])
                || !near(cbMem[2], cb0[2]) || !near(cbMem[3], cb0[3])) {
            bad(name, "cameraBounds() sau khi kéo left = " + box(cbMem) + ", mong đợi minX = " + fmt3(nx)
                    + " và 3 cạnh kia giữ nguyên " + box(cb0));
            return;
        }

        Path out = work.resolve("Map_" + mapId + ".t22.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);
        MapScene back = load(out, mapId);
        if (back == null) { bad(name, "load lại file đã ghi lỗi"); return; }
        MapScene.Node left2 = back.byTr(left.trAnchor);
        if (left2 == null) { bad(name, "không tìm lại được thanh biên left &" + left.trAnchor); return; }

        // m_LocalPosition đọc THẲNG trong file phải đúng bằng px/py đã tính
        double[] lp = rawVec(back, left.trAnchor, "m_LocalPosition:");
        double[] w2 = back.worldOrigin(left2);
        double[] cb2 = back.cameraBounds();
        int[] d = lineDiff(copy, out);
        String stat = "MapManager trỏ đúng 4 node (" + sb + "), cả 4 bị CHẶN xoá · kéo left ("
                + fmt3(o0[0]) + ", " + fmt3(o0[1]) + ") → (" + fmt3(nx) + ", " + fmt3(ny) + ")"
                + " · m_LocalPosition trong file (" + (lp == null ? "?" : fmt3(lp[0]) + ", " + fmt3(lp[1])) + ")"
                + " · khung camera " + box(cb0) + " → " + box(cb2)
                + " (server " + Math.round(cb2 == null ? 0 : cb2[0] * cfg.pixelsPerUnit()) + " … "
                + Math.round(cb2 == null ? 0 : cb2[2] * cfg.pixelsPerUnit()) + ")"
                + " · " + d[0] + " dòng file đổi";

        if (lp == null || !near(lp[0], left.px) || !near(lp[1], left.py)) {
            bad(name, "m_LocalPosition trong file sai, mong đợi (" + fmt3(left.px) + ", " + fmt3(left.py) + ") — " + stat);
            return;
        }
        if (!near(w2[0], nx) || !near(w2[1], ny)) {
            bad(name, "vị trí world đọc lại (" + fmt3(w2[0]) + ", " + fmt3(w2[1]) + ") ≠ mong đợi — " + stat);
            return;
        }
        if (cb2 == null || !near(cb2[0], nx) || !near(cb2[1], cb0[1])
                || !near(cb2[2], cb0[2]) || !near(cb2[3], cb0[3])) {
            bad(name, "khung camera đọc lại " + box(cb2) + " ≠ mong đợi — " + stat);
            return;
        }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        String bad2 = compareCentersExcept(before, back, ignore);
        if (bad2 != null) { bad(name, "node khác bị xê dịch: " + bad2 + " — " + stat); return; }
        ok(name, stat);
    }

    /** goAnchor của node và TOÀN BỘ con cháu (kéo cha thì con đi theo — không tính là "xê dịch sai"). */
    private static Set<Long> subtreeGos(MapScene scene, MapScene.Node root) {
        Set<Long> out = new HashSet<>();
        if (root == null) return out;
        List<MapScene.Node> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty() && out.size() < 100000) {
            MapScene.Node n = stack.remove(stack.size() - 1);
            if (!out.add(n.goAnchor)) continue;
            stack.addAll(scene.children(n));
        }
        return out;
    }

    /** Như {@link #compareCenters} nhưng bỏ qua CẢ MỘT TẬP node (node bị kéo + con cháu). */
    private static String compareCentersExcept(Map<Long, double[]> before, MapScene back, Set<Long> ignore) {
        for (Map.Entry<Long, double[]> e : before.entrySet()) {
            long go = e.getKey();
            if (ignore.contains(go)) continue;
            MapScene.Node n = back.byGo(go);
            if (n == null) return "MẤT node &" + go;
            double[] c = back.worldCenter(n);
            double[] c0 = e.getValue();
            if (!near(c[0], c0[0]) || !near(c[1], c0[1])) {
                return "node '" + n.name + "' (&" + go + ") tâm (" + fmt3(c0[0]) + ", " + fmt3(c0[1])
                        + ") → (" + fmt3(c[0]) + ", " + fmt3(c[1]) + ")";
            }
        }
        return null;
    }

    // ───────────────────────────── T23 (Y3 tham số hiệu ứng) ─────────────────────────────

    /** Các tham số SỐ mà người dùng hay chỉnh nhất, xếp theo mức ưu tiên khi chọn node để thử. */
    private static final String[] FX_NUM_KEYS = {
            "moveSpeed", "_swimSpeed", "timeScale", "_swimRange", "distance", "period",
            "_bobSpeed", "_bobAmplitude", "randomOffset", "riseDuration", "zSpacing"
    };

    /** Cờ hệ thống Unity — không đem ra thử vì không phải "tham số hiệu ứng" người dùng chỉnh. */
    private static final Set<String> FX_TEST_BAN = Set.of(
            "m_Enabled", "m_ObjectHideFlags", "m_EditorHideFlags", "m_UpdateMode",
            "m_CullingMode", "m_LayerCount", "serializedVersion");

    /**
     * Đổi 1 THAM SỐ SỐ HỌC của hiệu ứng rồi ghi ra file: giá trị phải round-trip đúng, file CHỈ đổi
     * 1 dòng, và dòng đổi đó phải mang ĐÚNG TÊN FIELD (chứng minh writer patch đúng chỗ, không
     * ghi nhầm sang field khác cùng block).
     */
    private void t23FxParam(int mapId, Path copy) throws IOException {
        String name = "T23 sửa tham số hiệu ứng Map" + mapId;
        MapScene scene = load(copy, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }

        MapScene.Node t = null;
        String key = null;
        double old = 0;
        outer:
        for (String k : FX_NUM_KEYS) {
            for (MapScene.Node n : scene.nodes()) {
                if (n.fx == MapScene.EffectKind.NONE) continue;
                String v = scene.fxParam(n, k, null);
                if (v == null || v.isEmpty()) continue;
                try {
                    old = Double.parseDouble(v.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                t = n; key = k;
                break outer;
            }
        }
        // Không có tham số "quen mặt" nào (map chỉ còn script lạ) → lấy BẤT KỲ field số học nào,
        // miễn không phải cờ hệ thống của Unity. Vẫn kiểm được đúng đường ghi của writer.
        if (t == null) {
            outer2:
            for (MapScene.Node n : scene.nodes()) {
                for (MapScene.Fx f : n.fxList) {
                    for (Map.Entry<String, String> e : f.params.entrySet()) {
                        if (FX_TEST_BAN.contains(e.getKey())) continue;
                        String v = (e.getValue() == null) ? "" : e.getValue().trim();
                        if (v.isEmpty()) continue;
                        try {
                            old = Double.parseDouble(v);
                        } catch (NumberFormatException ex) {
                            continue;
                        }
                        t = n; key = e.getKey();
                        break outer2;
                    }
                }
            }
        }
        if (t == null) {
            skip(name, "map không có node hiệu ứng nào mang tham số số học (" + scene.effectNodes().size()
                    + " node hiệu ứng)");
            return;
        }

        MapScene.EffectKind owner = MapScene.EffectKind.NONE;
        for (MapScene.Fx f : t.fxList) if (f.params.containsKey(key)) { owner = f.kind; break; }

        String nv = String.format(Locale.US, "%.4f", old + 1.75);
        scene.setFxParam(t, key, nv);
        if (!t.dFx) { bad(name, "setFxParam không bật cờ dFx cho '" + t.name + "'." + key); return; }

        Path out = work.resolve("Map_" + mapId + ".t23.prefab");
        MapSceneWriter.saveAs(scene, sortLayers, out);
        MapScene back = load(out, mapId);
        if (back == null) { bad(name, "load lại file đã ghi lỗi"); return; }
        MapScene.Node n2 = back.byGo(t.goAnchor);
        if (n2 == null) { bad(name, "không tìm lại được node &" + t.goAnchor); return; }

        double got = back.fxParamD(n2, key, Double.NaN);
        int[] d = lineDiff(copy, out);
        String diffLine = firstDiffLine(copy, out).trim();
        String stat = "'" + t.name + "' · " + owner + "." + key + " " + fmt3(old) + " → " + nv
                + ", đọc lại " + fmt3(got) + " · " + d[0] + " dòng file đổi · dòng đổi: \"" + shorten(diffLine, 60) + "\"";

        if (!near(got, old + 1.75)) { bad(name, "round-trip sai: " + fmt6(got) + " ≠ " + fmt6(old + 1.75) + " — " + stat); return; }
        if (d[1] != d[2]) { bad(name, "số dòng file đổi " + d[1] + " → " + d[2] + " — " + stat); return; }
        if (d[0] != 1) { bad(name, "phải đúng 1 dòng đổi, thực tế " + d[0] + " — " + stat); return; }
        if (!diffLine.startsWith(key + ":")) { bad(name, "dòng đổi KHÔNG phải field " + key + " — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T24 (Y3 nhận diện hiệu ứng) ─────────────────────────────

    /** guid script hiệu ứng — chép nguyên từ prefab client, KHÔNG lấy hằng của MapSceneLoader. */
    private static final String G_SPINE      = "d247ba06193faa74d9335f5481b2b56c";
    private static final String G_WATER_WAVE = "aede8610f78337840ba00de3173959eb";
    private static final String G_FISH_SWIM  = "63b0e6cbf2189d84ea346ce5bfbab639";
    private static final String G_WAVE_WASH  = "6cb6519367b3a91439d8f407fa642f01";
    private static final String G_WATER2D    = "8829f965957cc3046a2feba774f2db42";
    private static final String G_MAPMANAGER = "af78feca514ed524381d1e274f4973dd";

    /**
     * Số hiệu ứng từng loại mà loader nhận ra phải KHỚP CHÍNH XÁC với phép đếm ĐỘC LẬP quét text
     * prefab (bám guid {@code m_Script} và block {@code !u!95}), tính cả luật "component của
     * GameObject stripped thì bỏ".
     *
     * <p>Riêng Spine còn kiểm ≥ 80% node trỏ được tới THƯ MỤC skeleton thật có file skeleton
     * ({@code .json} HOẶC {@code .skel.bytes}) + {@code .atlas}/{@code .atlas.txt} — thiếu là canvas
     * không vẽ được cây lắc lư. (Nạp THẬT được hay không thì T26 mới đo.)
     */
    private void t24FxDetect(int mapId, MapScene scene, Path copy) throws IOException {
        String name = "T24 nhận diện hiệu ứng Map" + mapId;
        List<String> ls = Files.readAllLines(copy, StandardCharsets.UTF_8);
        Map<MapScene.EffectKind, Integer> want = rawEffectCounts(ls);
        Map<MapScene.EffectKind, Integer> got = scene.effectCounts();

        List<String> wrong = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (MapScene.EffectKind k : MapScene.EffectKind.values()) {
            if (k == MapScene.EffectKind.NONE) continue;
            int a = want.getOrDefault(k, 0), b = got.getOrDefault(k, 0);
            total += a;
            if (a != b) wrong.add(k + " grep " + a + " ≠ tool " + b);
            if (a > 0 || b > 0) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(k).append(' ').append(b);
            }
        }
        if (sb.length() == 0) sb.append("KHÔNG có");
        String stat = sb + " (tổng " + total + " component / " + scene.effectNodes().size() + " node)";
        if (!wrong.isEmpty()) { bad(name, "đếm lệch: " + String.join(", ", wrong) + " — " + stat); return; }

        // Spine: thư mục skeleton phải TỒN TẠI và có đủ atlas + file skeleton.
        int spine = 0, resolved = 0, json = 0, binary = 0;
        String firstMiss = null;
        for (MapScene.Node n : scene.nodes()) {
            for (MapScene.Fx f : n.fxList) {
                if (f.kind != MapScene.EffectKind.SPINE) continue;
                spine++;
                int kind = (f.folder == null) ? 0 : spineFolderKind(f.folder);
                if (kind == 2) { resolved++; json++; }
                else if (kind == 1) { resolved++; binary++; }
                else if (firstMiss == null) {
                    firstMiss = "'" + n.name + "' → " + (f.folder == null
                            ? "(không tra được guid " + shortGuid(f.assetGuid) + ")"
                            : f.folder.getFileName() + " thiếu atlas hoặc file skeleton");
                }
            }
        }
        if (spine > 0) {
            double pc = resolved * 100.0 / spine;
            stat += " · Spine tra được thư mục skeleton " + resolved + "/" + spine + " (" + fmt1(pc) + "%)"
                    + " — dạng .json " + json + ", dạng nhị phân .skel.bytes " + binary
                    + " (cả hai đều vẽ được, xem T26)";
            if (pc < 80.0) { bad(name, "chỉ " + fmt1(pc) + "% node Spine tra được thư mục skeleton (< 80%), ví dụ "
                    + firstMiss + " — " + stat); return; }
        }
        ok(name, stat);
    }

    /**
     * NGUỒN skeleton của thư mục Spine:
     * {@code 2} = có {@code .json} + atlas · {@code 1} = chỉ có {@code .skel/.skel.bytes} + atlas
     * (Spine NHỊ PHÂN, đọc bằng {@code SpineBinary}) · {@code 0} = không phải thư mục skeleton.
     *
     * <p>Đa số hiệu ứng trong {@code Assets/Textures/eff/**} của client là nhị phân, còn Spine của
     * map ({@code AssetBundles/Resource/**}) là JSON — nên phép đo tách riêng 2 con số. Cả hai
     * đều VẼ ĐƯỢC; con số chỉ để biết đường nào đang gánh bao nhiêu.
     */
    private static int spineFolderKind(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return 0;
        boolean json = false, bin = false, atlas = false;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String f = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (f.endsWith(".json")) json = true;
                else if (f.endsWith(".skel") || f.endsWith(".skel.bytes")) bin = true;
                if (f.endsWith(".atlas") || f.endsWith(".atlas.txt")) atlas = true;
            }
        } catch (IOException e) {
            return 0;
        }
        if (!atlas) return 0;
        return json ? 2 : (bin ? 1 : 0);
    }

    // ───────────────────────────── T26 (Spine nhị phân) ─────────────────────────────

    /** Cache skeleton dùng chung T26/T27 — nạp 1 skeleton mất 13–97 ms, nhiều node trỏ CHUNG 1 thư mục. */
    private final Map<Path, SpineCharacter> spineCache = new HashMap<>();
    private final Set<Path> spineBad = new HashSet<>();

    private SpineCharacter spineOf(Path folder) {
        if (folder == null || spineBad.contains(folder)) return null;
        SpineCharacter sc = spineCache.get(folder);
        if (sc != null) return sc;
        sc = SpineCharacter.load(folder);
        if (sc == null) { spineBad.add(folder); return null; }
        spineCache.put(folder, sc);
        return sc;
    }

    /**
     * NẠP THẬT từng skeleton của map bằng đúng đường mà canvas đi ({@link SpineCharacter#load}) —
     * test chốt cho bộ đọc {@code .skel.bytes}: trước đây 76/99 thư mục skeleton của game là nhị
     * phân nên canvas chỉ vẽ được ô giữ chỗ.
     *
     * <p>Đạt khi:
     * <ul>
     *   <li>≥ 95% node Spine của map nạp được skeleton (map không có Spine ⇒ [SKIP]);</li>
     *   <li>mọi skeleton nạp được phải có ≥ 1 bone và ≥ 1 animation — rỗng nghĩa là parser
     *       chạy hết file mà không lấy được gì;</li>
     *   <li>≥ 90% tên attachment (region + mesh) của skin tra được trong {@code .atlas.txt} cùng
     *       thư mục. Đây là BẰNG CHỨNG MẠNH rằng bảng chuỗi / {@code ReadStringRef} đọc đúng chứ
     *       không ra rác — chuỗi lệch 1 byte là tên sẽ không khớp region nào.</li>
     * </ul>
     *
     * <p>{@code _animationName} ghi trong prefab mà skeleton không có ⇒ chỉ in {@code [WARN]}:
     * đó là lỗi DỮ LIỆU của client (client tự fallback anim đầu), không phải lỗi tool.
     */
    private void t26SpineBinary(int mapId, MapScene scene) {
        String name = "T26 Spine Map" + mapId;
        int total = 0, drawn = 0, bin = 0, json = 0, binDrawn = 0;
        int attTotal = 0, attOk = 0;
        List<String> miss = new ArrayList<>();      // không nạp được → vẽ ô giữ chỗ
        List<String> empty = new ArrayList<>();     // nạp được nhưng rỗng bone/anim
        List<String> attMiss = new ArrayList<>();   // tên attachment không có trong atlas
        Set<Path> attDone = new HashSet<>();        // mỗi thư mục chỉ đếm attachment 1 lần
        // ứng viên cho T27: node nhị phân + số attachment tra được atlas của thư mục đó
        Map<MapScene.Node, Path> binNodes = new LinkedHashMap<>();
        Map<Path, Integer> folderScore = new HashMap<>();

        for (MapScene.Node n : scene.nodes()) {
            for (MapScene.Fx f : n.fxList) {
                if (f.kind != MapScene.EffectKind.SPINE) continue;
                total++;
                int kind = (f.folder == null) ? 0 : spineFolderKind(f.folder);
                if (kind == 2) json++;
                else if (kind == 1) bin++;

                SpineCharacter sc = spineOf(f.folder);
                if (sc == null) {
                    if (miss.size() < 3) miss.add("'" + n.name + "' → " + (f.folder == null
                            ? "(guid " + shortGuid(f.assetGuid) + " không tra được thư mục)"
                            : f.folder.getFileName() + " nạp không được"));
                    continue;
                }
                drawn++;
                if (kind == 1) {
                    binDrawn++;
                    binNodes.putIfAbsent(n, f.folder);
                }

                // (a) parser phải ra dữ liệu thật, không phải khung rỗng
                if (sc.data.bones.isEmpty() || sc.data.animations.isEmpty()) {
                    empty.add(f.folder.getFileName() + " (" + sc.data.bones.size() + " bone / "
                            + sc.data.animations.size() + " anim)");
                }

                // (b) tên animation prefab yêu cầu phải CÓ THẬT — sai là lỗi dữ liệu client
                String an = f.params.get("_animationName");
                if (an != null && !an.isBlank() && !sc.data.animations.containsKey(an)) {
                    System.out.println("[WARN] " + name + ": node '" + n.name + "' đặt _animationName='"
                            + an + "' nhưng skeleton " + f.folder.getFileName() + " chỉ có "
                            + new TreeSet<>(sc.data.animations.keySet())
                            + " — lỗi DỮ LIỆU client, KHÔNG tính FAIL");
                }

                // (c) tên attachment đối chiếu atlas — chỉ region/mesh mới cần region trong atlas
                // (bản .json còn giữ cả path/clipping/boundingbox, những loại đó không có ảnh).
                if (attDone.add(f.folder)) {
                    for (Map<String, SpineData.Attachment> bySlot : sc.data.skin.values()) {
                        for (SpineData.Attachment a : bySlot.values()) {
                            if (a == null || a.path == null) continue;
                            if (!"region".equals(a.type) && !"mesh".equals(a.type)) continue;
                            attTotal++;
                            if (sc.atlas.region(a.path) != null) {
                                attOk++;
                                folderScore.merge(f.folder, 1, Integer::sum);
                            } else if (attMiss.size() < 3) {
                                attMiss.add(f.folder.getFileName() + ":" + a.path);
                            }
                        }
                    }
                }
            }
        }

        if (total == 0) { skip(name, "map không có node Spine nào"); return; }
        // T27 lấy map nhiều skeleton nhị phân nhất, và trong map đó lấy node có NHIỀU MẢNH ẢNH nhất
        // (thư mục lắm attachment tra được atlas) — chắc chắn có gì đó để nhìn, không vớ phải
        // hiệu ứng trong suốt kiểu 'water_glow'.
        if (binDrawn > binSceneCount && !binNodes.isEmpty()) {
            MapScene.Node best = null;
            int bestScore = -1;
            for (Map.Entry<MapScene.Node, Path> e : binNodes.entrySet()) {
                int sc2 = folderScore.getOrDefault(e.getValue(), 0);
                if (sc2 > bestScore) { bestScore = sc2; best = e.getKey(); }
            }
            binScene = scene;
            binNode = best;
            binSceneMapId = mapId;
            binSceneCount = binDrawn;
        }

        int unknown = total - bin - json;
        String stat = drawn + "/" + total + " vẽ thật (" + (total - drawn) + " giữ chỗ)"
                + " · nguồn: " + bin + " nhị phân, " + json + " json"
                + (unknown > 0 ? ", " + unknown + " không rõ" : "");
        double attPc = (attTotal == 0) ? 100.0 : attOk * 100.0 / attTotal;
        if (attTotal > 0) stat += " · tên attachment khớp atlas " + attOk + "/" + attTotal
                + " (" + fmt1(attPc) + "%)";

        double pc = drawn * 100.0 / total;
        if (pc < 95.0) {
            bad(name, "chỉ " + fmt1(pc) + "% node Spine vẽ thật được (< 95%), ví dụ "
                    + String.join(", ", miss) + " — " + stat);
            return;
        }
        if (!empty.isEmpty()) {
            bad(name, "skeleton nạp được nhưng RỖNG (thiếu bone hoặc animation): "
                    + String.join(", ", empty) + " — " + stat);
            return;
        }
        if (attPc < 90.0) {
            bad(name, "chỉ " + fmt1(attPc) + "% tên attachment tra được trong atlas (< 90%) — parser"
                    + " nhiều khả năng đọc lệch bảng chuỗi, ví dụ " + String.join(", ", attMiss)
                    + " — " + stat);
            return;
        }
        ok(name, stat);
    }

    // ───────── quét THÔ text prefab (phép đếm ĐỘC LẬP, không đụng PrefabDocument/loader) ─────────

    /** 1 block YAML đọc thẳng từ text: {@code --- !u!<cls> &<anchor>[ stripped]}. */
    private static final class RawBlk {
        int cls;
        long anchor;
        boolean stripped;
        int start, end;
        long go;
        String scriptGuid;
    }

    private static final Pattern RAW_HDR = Pattern.compile("^--- !u!(\\d+) &(-?\\d+)(\\s+stripped)?\\s*$");
    private static final Pattern RAW_GUID = Pattern.compile("guid:\\s*([0-9a-fA-F]{32})");

    /** Bổ đôi file prefab thành danh sách block, kèm {@code m_GameObject} và guid {@code m_Script}. */
    private static List<RawBlk> scanRaw(List<String> ls) {
        List<RawBlk> out = new ArrayList<>();
        RawBlk cur = null;
        for (int i = 0; i < ls.size(); i++) {
            String l = ls.get(i);
            if (l.startsWith("--- !u!")) {
                if (cur != null) cur.end = i;
                Matcher m = RAW_HDR.matcher(l.trim());
                if (m.matches()) {
                    cur = new RawBlk();
                    cur.cls = Integer.parseInt(m.group(1));
                    cur.anchor = Long.parseLong(m.group(2));
                    cur.stripped = m.group(3) != null;
                    cur.start = i;
                    out.add(cur);
                } else {
                    cur = null;
                }
                continue;
            }
            if (cur == null) continue;
            String t = l.trim();
            if (t.startsWith("m_GameObject:")) {
                Matcher f = FILEID_PAT.matcher(t);
                if (f.find()) cur.go = Long.parseLong(f.group(1));
            } else if (t.startsWith("m_Script:")) {
                Matcher g = RAW_GUID.matcher(t);
                if (g.find()) cur.scriptGuid = g.group(1).toLowerCase(Locale.ROOT);
            }
        }
        if (cur != null) cur.end = ls.size();
        return out;
    }

    /** anchor các GameObject "sống": không stripped VÀ có Transform (không có thì loader không dựng node). */
    private static Set<Long> liveGameObjects(List<RawBlk> bs) {
        Set<Long> go = new HashSet<>(), withTr = new HashSet<>();
        for (RawBlk b : bs) {
            if (b.stripped) continue;
            if (b.cls == 1) go.add(b.anchor);
            else if (b.cls == 4 || b.cls == 224) withTr.add(b.go);
        }
        go.retainAll(withTr);
        return go;
    }

    /** Đếm component hiệu ứng theo loại — hoàn toàn bằng grep text, để đối chiếu với loader. */
    private static Map<MapScene.EffectKind, Integer> rawEffectCounts(List<String> ls) {
        List<RawBlk> bs = scanRaw(ls);
        Set<Long> live = liveGameObjects(bs);
        Map<MapScene.EffectKind, Integer> out = new LinkedHashMap<>();
        for (RawBlk b : bs) {
            if (b.stripped || !live.contains(b.go)) continue;
            MapScene.EffectKind k;
            if (b.cls == 95) {
                k = MapScene.EffectKind.ANIMATOR;
            } else if (b.cls == 114) {
                String g = (b.scriptGuid == null) ? "" : b.scriptGuid;
                if (G_MAPMANAGER.equals(g)) continue;               // MapManager KHÔNG phải hiệu ứng
                k = switch (g) {
                    case G_SPINE      -> MapScene.EffectKind.SPINE;
                    case G_WATER_WAVE -> MapScene.EffectKind.WATER_WAVE;
                    case G_FISH_SWIM  -> MapScene.EffectKind.FISH_SWIM;
                    case G_WAVE_WASH  -> MapScene.EffectKind.WAVE_WASH;
                    case G_WATER2D    -> MapScene.EffectKind.WATER2D;
                    default           -> MapScene.EffectKind.OTHER;
                };
            } else {
                continue;
            }
            out.merge(k, 1, Integer::sum);
        }
        return out;
    }

    /**
     * 4 fileID biên map {@code {top, bottom, left, right}} đọc THẲNG trong block MapManager —
     * null nếu prefab không có MapManager.
     */
    private static long[] rawMapManagerEdges(List<String> ls) {
        for (RawBlk b : scanRaw(ls)) {
            if (b.stripped || b.cls != 114 || !G_MAPMANAGER.equals(b.scriptGuid)) continue;
            long[] out = new long[4];
            String[] keys = {"_edgeColTop:", "_edgeColBtm:", "_edgeColLeft:", "_edgeColRight:"};
            for (int i = b.start + 1; i < b.end && i < ls.size(); i++) {
                String t = ls.get(i).trim();
                for (int k = 0; k < 4; k++) {
                    if (!t.startsWith(keys[k])) continue;
                    Matcher m = FILEID_PAT.matcher(t);
                    if (m.find()) out[k] = Long.parseLong(m.group(1));
                }
            }
            return out;
        }
        return null;
    }

    /** Dòng ĐẦU TIÊN khác nhau giữa 2 file, lấy theo bản B (rỗng nếu giống hệt). */
    private static String firstDiffLine(Path a, Path b) throws IOException {
        List<String> la = Files.readAllLines(a, StandardCharsets.UTF_8);
        List<String> lb = Files.readAllLines(b, StandardCharsets.UTF_8);
        int n = Math.min(la.size(), lb.size());
        for (int i = 0; i < n; i++) if (!la.get(i).equals(lb.get(i))) return lb.get(i);
        return "";
    }

    // ───────────── tiện ích riêng cho T18/T19/T20 (thay đổi CẤU TRÚC) ─────────────

    private static final Pattern FILEID_PAT = Pattern.compile("fileID:\\s*(-?\\d+)");

    /**
     * Node dùng làm CHA cho đường kẻ mới: ưu tiên cha của một EdgeCollider2D đã có
     * (thường là node nhóm chứa toàn bộ collider), không có thì lấy root — prefab luôn có
     * đúng 1 root nên KHÔNG bao giờ tạo node mồ côi.
     */
    private static MapScene.Node pickLineParent(MapScene scene) {
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind != MapScene.ColKind.EDGE) continue;
            MapScene.Node p = scene.parent(n);
            if (p != null) return p;
        }
        return scene.roots().isEmpty() ? null : scene.roots().get(0);
    }

    /**
     * Node KHÔNG ĐƯỢC PHÉP xoá để thử phép chặn: biên map Top/Bottom/Left/Right, hoặc node
     * được MapManager giữ làm lớp nền (_layersBG / _layerBGSky).
     */
    private static MapScene.Node pickProtectedNode(MapScene scene) {
        for (MapScene.Node n : scene.nodes()) {
            String nm = (n.name == null) ? "" : n.name.trim();
            if (nm.equalsIgnoreCase("Top") || nm.equalsIgnoreCase("Bottom")
                    || nm.equalsIgnoreCase("Left") || nm.equalsIgnoreCase("Right")) {
                return n;
            }
        }
        for (MapScene.Node n : scene.nodes()) {
            if (n.trAnchor != 0 && (n.trAnchor == scene.skyLayerTr() || scene.bgLayerTrs().contains(n.trAnchor))) {
                return n;
            }
        }
        return null;
    }

    /** goAnchor → tâm world, chụp trước khi sửa để chứng minh node cũ không bị xê dịch. */
    private static Map<Long, double[]> snapshotCenters(MapScene scene) {
        Map<Long, double[]> out = new LinkedHashMap<>();
        for (MapScene.Node n : scene.nodes()) {
            if (n.goAnchor == 0) continue;
            out.put(n.goAnchor, scene.worldCenter(n));
        }
        return out;
    }

    /** null = mọi node trong ảnh chụp còn nguyên; ngược lại mô tả node đầu tiên sai/mất. */
    private static String compareCenters(Map<Long, double[]> before, MapScene back, long ignoreGo) {
        for (Map.Entry<Long, double[]> e : before.entrySet()) {
            long go = e.getKey();
            if (go == ignoreGo) continue;
            MapScene.Node n = back.byGo(go);
            if (n == null) return "MẤT node &" + go;
            double[] c = back.worldCenter(n);
            double[] c0 = e.getValue();
            if (!near(c[0], c0[0]) || !near(c[1], c0[1])) {
                return "node '" + n.name + "' (&" + go + ") tâm (" + fmt3(c0[0]) + ", " + fmt3(c0[1])
                        + ") → (" + fmt3(c[0]) + ", " + fmt3(c[1]) + ")";
            }
        }
        return null;
    }

    /** Số layer mà PrefabParser CŨ đọc được (-1 = parse lỗi). */
    private int parserLayerCount(Path prefab) {
        try {
            return new PrefabParser(guidIndex, matResolver, texCache, cfg.pixelsPerUnit()).parse(prefab).size();
        } catch (Exception e) {
            System.out.println("[WARN] PrefabParser lỗi trên " + prefab.getFileName() + ": " + e);
            return -1;
        }
    }

    /** anchor của dòng header {@code --- !u!<cls> &<anchor>[ stripped]}; -1 nếu không phải header. */
    private static long headerAnchor(String line) {
        String t = line.trim();
        if (!t.startsWith("--- !u!")) return -1;
        int amp = t.indexOf('&');
        if (amp < 0) return -1;
        String rest = t.substring(amp + 1).trim();
        int sp = rest.indexOf(' ');
        if (sp >= 0) rest = rest.substring(0, sp);
        try { return Long.parseLong(rest); } catch (NumberFormatException e) { return -1; }
    }

    /** Số dòng header khai báo anchor này (phải đúng 1 — 0 = không có, ≥ 2 = TRÙNG anchor). */
    private static int countAnchorHeaders(Path file, long anchor) throws IOException {
        int c = 0;
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (headerAnchor(l) == anchor) c++;
        }
        return c;
    }

    /**
     * Các fileID của list nhiều dòng (m_Children / m_Component) trong block &anchor — đọc THẲNG
     * text file, KHÔNG đi qua PrefabDocument/loader, để test không "tự chấm bài chính mình".
     */
    private static List<Long> fileIdListInFile(Path file, long anchor, String key) throws IOException {
        List<String> ls = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Long> out = new ArrayList<>();
        String head = "  " + (key.endsWith(":") ? key : key + ":");
        int i = 0;
        while (i < ls.size() && headerAnchor(ls.get(i)) != anchor) i++;
        for (i++; i < ls.size(); i++) {
            if (headerAnchor(ls.get(i)) >= 0) break;                  // sang block khác
            if (!ls.get(i).startsWith(head)) continue;
            if (ls.get(i).trim().endsWith("[]")) return out;          // list rỗng
            for (int j = i + 1; j < ls.size(); j++) {
                if (!ls.get(j).startsWith("  - ")) break;
                Matcher m = FILEID_PAT.matcher(ls.get(j));
                if (m.find()) out.add(Long.parseLong(m.group(1)));
            }
            return out;
        }
        return out;
    }

    /** Các dòng chứa CHÍNH XÁC số anchor (không phải một phần của số dài hơn). */
    private static List<String> linesMentioning(Path file, long anchor) throws IOException {
        List<String> hits = new ArrayList<>();
        if (anchor == 0) return hits;
        String needle = String.valueOf(anchor);
        for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int at = l.indexOf(needle);
            while (at >= 0) {
                boolean lb = (at == 0) || !Character.isDigit(l.charAt(at - 1));
                int e = at + needle.length();
                boolean rb = (e >= l.length()) || !Character.isDigit(l.charAt(e));
                if (lb && rb) { hits.add(l); break; }
                at = l.indexOf(needle, at + 1);
            }
        }
        return hits;
    }

    /** Tập key cấp 1 (đúng indent 2, bỏ item "- ") của 1 block. null = không có block. */
    private static Set<String> blockKeys(PrefabDocument doc, long anchor) {
        PrefabDocument.Block b = (anchor == 0) ? null : doc.block(anchor);
        if (b == null || b.start < 0) return null;
        Set<String> out = new TreeSet<>();
        List<String> ls = doc.lines();
        int to = Math.min(b.end, ls.size());
        for (int i = b.start + 1; i < to; i++) {
            String l = ls.get(i);
            if (l.length() < 3 || l.charAt(0) != ' ' || l.charAt(1) != ' ' || l.charAt(2) == ' ') continue;
            if (l.startsWith("  - ")) continue;
            int c = l.indexOf(':');
            if (c < 0) continue;
            out.add(l.substring(2, c).trim());
        }
        return out;
    }

    /** anchor block đầu tiên thuộc classId (bỏ stripped và anchor cần loại). 0 = không có. */
    private static long anchorOfClass(PrefabDocument doc, int classId, long except) {
        for (PrefabDocument.Block b : doc.blocksOfClass(classId)) {
            if (b.stripped || b.start < 0 || b.anchor == except) continue;
            return b.anchor;
        }
        return 0;
    }

    /** anchor block PlatformEffector2D (!u!251) gắn trên GameObject &goAnchor (0 = không có). */
    private static long effAnchorInFile(PrefabDocument doc, long goAnchor) {
        PrefabDocument.Block b = effectorBlock(doc, goAnchor);
        return (b == null) ? 0 : b.anchor;
    }

    private static PrefabDocument.Block effectorBlock(PrefabDocument doc, long goAnchor) {
        for (PrefabDocument.Block b : doc.blocksOfGameObject(goAnchor)) {
            if (b.classId == 251) return b;
        }
        return null;
    }

    /**
     * So TẬP KEY cấp 1 của block MỚI với một block CÙNG LOẠI đã có sẵn trong chính file đó.
     * Đây là bằng chứng mạnh nhất cho việc Unity deserialize được block do tool sinh ra.
     * null = khớp (hoặc file không có block mẫu nào để so → bỏ qua).
     */
    private static String compareBlockKeys(PrefabDocument doc, int classId, long newAnchor, String label) {
        Set<String> mine = blockKeys(doc, newAnchor);
        if (mine == null) return "không tìm thấy block " + label + " &" + newAnchor + " trong file đã ghi";
        long ref = anchorOfClass(doc, classId, newAnchor);
        Set<String> other = blockKeys(doc, ref);
        if (other == null || other.isEmpty()) return null;      // file không có block mẫu → không so được
        if (mine.equals(other)) return null;
        Set<String> missing = new TreeSet<>(other);
        missing.removeAll(mine);
        Set<String> extra = new TreeSet<>(mine);
        extra.removeAll(other);
        return "TẬP KEY block " + label + " mới (&" + newAnchor + ", " + mine.size() + " key) KHÁC block mẫu &"
                + ref + " (" + other.size() + " key): thiếu " + missing + ", thừa " + extra;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ───────────────────────────── T12 ─────────────────────────────

    /** save() = backup + ghi đè. Test chạy trên BẢN COPY RIÊNG, tuyệt đối không đụng file client. */
    private void t12Backup(int mapId, Path src) throws IOException {
        String name = "T12 backup trước khi ghi đè Map" + mapId;
        Path victim = work.resolve("Map_" + mapId + ".t12-ban-copy.prefab");
        Files.copy(src, victim, StandardCopyOption.REPLACE_EXISTING);
        byte[] beforeBytes = Files.readAllBytes(victim);

        MapScene scene = load(victim, mapId);
        if (scene == null) { bad(name, "load lỗi"); return; }
        MapScene.Node t = null;
        for (MapScene.Node n : scene.nodes()) if (n.hasRenderer) { t = n; break; }
        if (t == null && !scene.nodes().isEmpty()) t = scene.nodes().get(0);
        if (t == null) { skip(name, "map không có node"); return; }
        t.active = !t.active;
        t.dActive = true;

        MapSceneWriter.Result r = MapSceneWriter.save(scene, sortLayers, backupDir);
        byte[] afterBytes = Files.readAllBytes(victim);
        String stat = "backup " + (r.backup == null ? "KHÔNG TẠO" : r.backup.getFileName().toString())
                + " · " + r.fieldsWritten + " field ghi";
        if (r.backup == null || !Files.exists(r.backup)) { bad(name, "không tạo được file .bak — " + stat); return; }
        byte[] bak = Files.readAllBytes(r.backup);
        if (!java.util.Arrays.equals(bak, beforeBytes)) {
            bad(name, "nội dung .bak (" + bak.length + " byte) ≠ file trước khi ghi (" + beforeBytes.length + " byte) — " + stat);
            return;
        }
        if (java.util.Arrays.equals(afterBytes, beforeBytes)) { bad(name, "file đích không hề thay đổi — " + stat); return; }
        if (!r.backup.getFileName().toString().matches("Map_.*\\.prefab\\.\\d{8}-\\d{6}\\.bak")) {
            bad(name, "tên backup sai quy ước Map_{id}.prefab.{yyyyMMdd-HHmmss}.bak — " + stat);
            return;
        }
        if (scene.dirty()) { bad(name, "cờ dirty chưa được xoá sau save() — " + stat); return; }
        ok(name, stat + " · nội dung .bak khớp 100% bản trước khi ghi (" + bak.length + " byte)");
    }

    // ───────────────────────────── T11 ─────────────────────────────

    private void t11ClientUntouched(Map<Integer, String[]> before) {
        String name = "T11 an toàn — file prefab CLIENT không đổi";
        if (before.isEmpty()) { skip(name, "không có prefab nào để kiểm tra"); return; }
        List<String> broken = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String[]> e : before.entrySet()) {
            Path p = cfg.mapPrefab(e.getKey());
            String[] now = fingerprint(p);
            String[] old = e.getValue();
            if (!old[0].equals(now[0]) || !old[1].equals(now[1])) {
                broken.add("Map" + e.getKey() + " sha " + old[0] + " → " + now[0]);
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append("Map").append(e.getKey()).append(' ').append(old[0], 0, 8).append('/').append(old[1]).append("B");
        }
        if (!broken.isEmpty()) { bad(name, "FILE CLIENT BỊ SỬA: " + String.join("; ", broken)); return; }
        ok(name, before.size() + " file nguyên vẹn (sha256/size khớp): " + sb);
    }

    // ───────────────────────────── T13 ─────────────────────────────

    /** Vẽ canvas ra ảnh offscreen — chứng minh pipeline render chạy thật, không chỉ parse. */
    private void t13OffscreenPaint() {
        String name = "T13 vẽ offscreen MapLayoutCanvas";
        if (lastScene == null) { skip(name, "chưa map nào load được"); return; }
        if (GraphicsEnvironment.isHeadless()) { skip(name, "JVM chạy headless — bỏ qua, KHÔNG tính FAIL"); return; }

        final int w = 1200, h = 800;
        final String[] err = new String[1];
        final int[] diffPx = new int[1];
        Runnable job = () -> {
            try {
                MapLayoutCanvas c = new MapLayoutCanvas(texCache, cfg.pixelsPerUnit());
                c.setSize(w, h);
                c.setScene(lastScene);
                c.setShowSprites(true);
                c.setShowColliders(true);
                c.setShowGrid(true);
                c.setPlayerLayerIndex(sortLayers.playerIndex());
                c.doLayout();
                c.fitView();
                BufferedImage img = paintTo(c, w, h);

                // Ảnh đối chứng: canvas RỖNG (chỉ nền chuyển sắc + 1 dòng chữ).
                MapLayoutCanvas empty = new MapLayoutCanvas(texCache, cfg.pixelsPerUnit());
                empty.setSize(w, h);
                empty.doLayout();
                BufferedImage ref = paintTo(empty, w, h);

                int diff = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (img.getRGB(x, y) != ref.getRGB(x, y)) diff++;
                    }
                }
                diffPx[0] = diff;
                Path png = work.resolve("Map_" + lastSceneMapId + ".t13.png");
                javax.imageio.ImageIO.write(img, "png", png.toFile());
            } catch (Throwable t) {
                err[0] = t.toString();
            }
        };
        try {
            javax.swing.SwingUtilities.invokeAndWait(job);
        } catch (Exception e) {
            bad(name, "chạy trên EDT lỗi: " + e);
            return;
        }
        if (err[0] != null) { bad(name, "ném ngoại lệ khi vẽ: " + err[0]); return; }
        String stat = "Map" + lastSceneMapId + " · " + diffPx[0] + " pixel khác ảnh nền trống (" + (w * h)
                + " px khung) · ảnh lưu " + work.resolve("Map_" + lastSceneMapId + ".t13.png").getFileName();
        if (diffPx[0] < 1000) { bad(name, "chỉ " + diffPx[0] + " pixel được vẽ (< 1000) — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T25 ─────────────────────────────

    /**
     * VẼ HIỆU ỨNG — mở rộng T13: bật "chạy hiệu ứng" rồi vẽ 3 frame ở 3 mốc thời gian khác nhau
     * trên map NHIỀU HIỆU ỨNG THẬT NHẤT. Yêu cầu: không ném ngoại lệ (Spine/atlas hỏng là lộ ngay)
     * và ít nhất 2 frame KHÁC NHAU — chứng minh hiệu ứng thật sự ĐỘNG chứ không phải ảnh tĩnh.
     *
     * <p>Zoom vào một node hiệu ứng (40 px/unit) vì ở mức "canh cả map" độ dịch chuyển của nước/cá
     * chỉ vài phần pixel, nhìn không ra khác biệt.
     */
    private void t25EffectFrames() {
        String name = "T25 vẽ hiệu ứng offscreen (3 mốc thời gian)";
        if (fxScene == null || fxSceneCount == 0) { skip(name, "không map nào trong đợt test có hiệu ứng thật"); return; }
        if (GraphicsEnvironment.isHeadless()) { skip(name, "JVM chạy headless — bỏ qua, KHÔNG tính FAIL"); return; }

        // Ưu tiên node CHUYỂN ĐỘNG (nước chảy / cá bơi / sóng vỗ), không có thì lấy node Spine.
        MapScene.Node focus = null;
        for (MapScene.Node n : fxScene.realEffectNodes()) {
            if (n.fxMotion() != MapScene.EffectKind.NONE) { focus = n; break; }
        }
        if (focus == null) {
            for (MapScene.Node n : fxScene.realEffectNodes()) {
                if (n.hasFx(MapScene.EffectKind.SPINE)) { focus = n; break; }
            }
        }
        if (focus == null) focus = fxScene.realEffectNodes().get(0);

        final int w = 900, h = 640;
        final double[] times = {0.0, 1.37, 4.91};
        final MapScene.Node target = focus;
        final String[] err = new String[1];
        final int[] diff = new int[3];       // frame0↔1, frame1↔2, frame0↔2
        Runnable job = () -> {
            try {
                MapLayoutCanvas c = new MapLayoutCanvas(texCache, cfg.pixelsPerUnit());
                c.setSize(w, h);
                c.setScene(fxScene);
                c.setShowSprites(true);
                c.setShowColliders(false);
                c.setShowEffects(true);
                c.setShowEffectBadges(false);      // nhãn tĩnh, không tính là "hiệu ứng động"
                c.setPlayerLayerIndex(sortLayers.playerIndex());
                c.doLayout();
                c.fitView();
                c.setZoom(40);
                c.panTo(target);
                c.setPlayEffects(true);

                BufferedImage[] fr = new BufferedImage[3];
                for (int i = 0; i < 3; i++) {
                    c.setEffectTime(times[i]);
                    fr[i] = paintTo(c, w, h);
                    Path png = work.resolve("Map_" + fxSceneMapId + ".t25-f" + i + ".png");
                    javax.imageio.ImageIO.write(fr[i], "png", png.toFile());
                }
                c.setPlayEffects(false);           // tắt timer, không để chạy nền đốt CPU
                diff[0] = pixelDiff(fr[0], fr[1]);
                diff[1] = pixelDiff(fr[1], fr[2]);
                diff[2] = pixelDiff(fr[0], fr[2]);
            } catch (Throwable t) {
                err[0] = t + (t.getCause() != null ? " ← " + t.getCause() : "");
            }
        };
        try {
            javax.swing.SwingUtilities.invokeAndWait(job);
        } catch (Exception e) {
            bad(name, "chạy trên EDT lỗi: " + e);
            return;
        }
        if (err[0] != null) { bad(name, "ném ngoại lệ khi vẽ hiệu ứng: " + err[0]); return; }

        int max = Math.max(diff[0], Math.max(diff[1], diff[2]));
        String stat = "Map" + fxSceneMapId + " (" + fxSceneCount + " node hiệu ứng thật, zoom 40 px/u quanh '"
                + target.name + "') · t = 0 / 1.37 / 4.91 s · pixel khác nhau: 0↔1 " + diff[0]
                + ", 1↔2 " + diff[1] + ", 0↔2 " + diff[2] + " · ảnh lưu Map_" + fxSceneMapId + ".t25-f*.png";
        if (max < 50) { bad(name, "3 frame gần như giống hệt (khác nhiều nhất " + max
                + " px < 50) — hiệu ứng KHÔNG chạy — " + stat); return; }
        ok(name, stat);
    }

    // ───────────────────────────── T27 ─────────────────────────────

    /**
     * VẼ THẬT SKELETON NHỊ PHÂN QUA CANVAS — bằng chứng cuối cùng cho bộ đọc {@code .skel.bytes}:
     * T26 mới chỉ chứng minh PARSE được, còn đây là cả đường ống
     * {@code SpineBinary → SpineData → SpineSkeleton.pose → SpineRenderer → Graphics2D}.
     *
     * <p>Vẽ 3 ảnh trên map nhiều skeleton nhị phân nhất, zoom 40 px/unit quanh 1 node nhị phân:
     * <ul>
     *   <li>{@code f0} — hiệu ứng BẬT, t = 0 s</li>
     *   <li>{@code f1} — hiệu ứng BẬT, t = 2.13 s</li>
     *   <li>{@code fOff} — hiệu ứng TẮT (node Spine bị loại khỏi thứ tự vẽ ⇒ không có 1 pixel Spine nào)</li>
     * </ul>
     * Đạt khi {@code f0 ≠ fOff} (VÙNG VẼ KHÔNG RỖNG — đúng phần pixel do Spine tạo ra, không lẫn
     * nền/sprite) và {@code f0 ≠ f1} (2 frame KHÁC NHAU ⇒ timeline thật sự chạy).
     */
    private void t27SpineBinaryPaint() {
        String name = "T27 vẽ thật Spine nhị phân (canvas)";
        if (binScene == null || binNode == null) {
            skip(name, "không map nào trong đợt test có skeleton .skel.bytes nạp được");
            return;
        }
        if (GraphicsEnvironment.isHeadless()) { skip(name, "JVM chạy headless — bỏ qua, KHÔNG tính FAIL"); return; }

        final int w = 900, h = 640;
        final MapScene.Node target = binNode;
        final String[] err = new String[1];
        final int[] dSpine = new int[1];      // f0 ↔ fOff = số pixel do Spine vẽ ra
        final int[] dTime = new int[1];       // f0 ↔ f1   = số pixel đổi theo thời gian
        Runnable job = () -> {
            try {
                MapLayoutCanvas c = new MapLayoutCanvas(texCache, cfg.pixelsPerUnit());
                c.setSize(w, h);
                c.setScene(binScene);
                c.setShowSprites(true);
                c.setShowColliders(false);
                c.setShowGrid(false);
                c.setShowEffectBadges(false);      // nhãn tĩnh — không được tính là "vẽ được Spine"
                c.setPlayerLayerIndex(sortLayers.playerIndex());
                c.doLayout();
                c.fitView();
                c.setZoom(40);
                c.panTo(target);
                c.setShowEffects(true);
                c.setPlayEffects(true);

                c.setEffectTime(0.0);
                BufferedImage f0 = paintTo(c, w, h);
                c.setEffectTime(2.13);
                BufferedImage f1 = paintTo(c, w, h);
                c.setShowEffects(false);           // Spine biến mất hoàn toàn khỏi khung hình
                c.setEffectTime(0.0);
                BufferedImage fOff = paintTo(c, w, h);
                c.setPlayEffects(false);           // tắt timer, không để chạy nền đốt CPU

                javax.imageio.ImageIO.write(f0, "png", work.resolve("Map_" + binSceneMapId + ".t27-f0.png").toFile());
                javax.imageio.ImageIO.write(f1, "png", work.resolve("Map_" + binSceneMapId + ".t27-f1.png").toFile());
                javax.imageio.ImageIO.write(fOff, "png", work.resolve("Map_" + binSceneMapId + ".t27-off.png").toFile());
                dSpine[0] = pixelDiff(f0, fOff);
                dTime[0] = pixelDiff(f0, f1);
            } catch (Throwable t) {
                err[0] = t + (t.getCause() != null ? " ← " + t.getCause() : "");
            }
        };
        try {
            javax.swing.SwingUtilities.invokeAndWait(job);
        } catch (Exception e) {
            bad(name, "chạy trên EDT lỗi: " + e);
            return;
        }
        if (err[0] != null) { bad(name, "ném ngoại lệ khi vẽ: " + err[0]); return; }

        String stat = "Map" + binSceneMapId + " (" + binSceneCount + " node .skel.bytes, zoom 40 px/u quanh '"
                + target.name + "') · vùng Spine vẽ ra " + dSpine[0] + " px · khác nhau giữa t=0 và t=2.13 s "
                + dTime[0] + " px · ảnh lưu Map_" + binSceneMapId + ".t27-*.png";
        if (dSpine[0] < 200) {
            bad(name, "bật/tắt hiệu ứng chỉ lệch " + dSpine[0] + " px (< 200) — skeleton nhị phân"
                    + " KHÔNG vẽ ra pixel nào — " + stat);
            return;
        }
        if (dTime[0] < 50) {
            bad(name, "2 frame gần như giống hệt (khác " + dTime[0] + " px < 50) — timeline nhị phân"
                    + " không chạy — " + stat);
            return;
        }
        ok(name, stat);
    }

    /** Số pixel khác nhau giữa 2 ảnh cùng kích thước. */
    private static int pixelDiff(BufferedImage a, BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) n++;
            }
        }
        return n;
    }

    private static BufferedImage paintTo(MapLayoutCanvas c, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            c.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Tiện ích
    // ═════════════════════════════════════════════════════════════════════

    private MapScene load(Path prefab, int mapId) {
        try {
            return new MapSceneLoader(guidIndex, matResolver, texCache, cfg.pixelsPerUnit()).load(prefab, mapId);
        } catch (Exception e) {
            System.out.println("[WARN] load " + prefab.getFileName() + " lỗi: " + e);
            return null;
        }
    }

    private static MapScene.Node firstRenderer(MapScene scene) {
        for (MapScene.Node n : scene.nodes()) {
            if (n.hasRenderer && n.rendAnchor != 0 && n.texture != null) return n;
        }
        for (MapScene.Node n : scene.nodes()) {
            if (n.hasRenderer && n.rendAnchor != 0) return n;
        }
        return null;
    }

    /** Số cấp cha của node (root = 0). */
    private static int depth(MapScene scene, MapScene.Node n) {
        int d = 0;
        MapScene.Node cur = n;
        while (cur != null && cur.parentTr != 0 && d < 256) {
            cur = scene.byTr(cur.parentTr);
            d++;
        }
        return d;
    }

    /** So 2 file theo dòng → {số dòng khác, số dòng file A, số dòng file B}. */
    private static int[] lineDiff(Path a, Path b) throws IOException {
        List<String> la = Files.readAllLines(a, StandardCharsets.UTF_8);
        List<String> lb = Files.readAllLines(b, StandardCharsets.UTF_8);
        int n = Math.min(la.size(), lb.size()), diff = 0;
        for (int i = 0; i < n; i++) if (!la.get(i).equals(lb.get(i))) diff++;
        diff += Math.abs(la.size() - lb.size());
        return new int[]{diff, la.size(), lb.size()};
    }

    /** {sha256 hex, size, lastModified} — dùng chứng minh file client không bị sửa. */
    private static String[] fingerprint(Path p) {
        try {
            byte[] data = Files.readAllBytes(p);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte x : d) sb.append(String.format("%02x", x));
            return new String[]{sb.toString(), String.valueOf(data.length),
                    String.valueOf(Files.getLastModifiedTime(p).toMillis())};
        } catch (Exception e) {
            return new String[]{"lỗi:" + e, "-1", "-1"};
        }
    }

    /** Sai số cho phép: tuyệt đối 1e-4 + tương đối 2e-6 (file YAML lưu float 32-bit ~7 chữ số). */
    private static boolean near(double got, double want) {
        return Math.abs(got - want) <= Math.max(EPS_ABS, Math.abs(want) * EPS_REL);
    }

    private static double angDiff(double a, double b) {
        double d = (a - b) % 360.0;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    private static double[] emptyBounds() {
        return new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    }

    private static void grow(double[] bb, double x, double y) {
        bb[0] = Math.min(bb[0], x);
        bb[1] = Math.min(bb[1], y);
        bb[2] = Math.max(bb[2], x);
        bb[3] = Math.max(bb[3], y);
    }

    private static String box(double[] b) {
        if (b == null || b.length < 4) return "?";
        return "(" + fmt3(b[0]) + ", " + fmt3(b[1]) + ")…(" + fmt3(b[2]) + ", " + fmt3(b[3]) + ")";
    }

    private static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(a[i]); }
        return sb.toString();
    }

    private static String fmt1(double v) { return String.format(Locale.US, "%.1f", v); }
    private static String fmt3(double v) { return String.format(Locale.US, "%.3f", v); }
    private static String fmt6(double v) { return String.format(Locale.US, "%.6f", v); }

    private void ok(String name, String detail) {
        pass++;
        System.out.println("[PASS] " + name + (detail == null || detail.isEmpty() ? "" : ": " + detail));
    }

    private void bad(String name, String reason) {
        fail++;
        System.out.println("[FAIL] " + name + ": " + reason);
    }

    private void skip(String name, String reason) {
        skip++;
        System.out.println("[SKIP] " + name + ": " + reason);
    }
}
