package com.apex.maptool.unity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MapSceneWriter — áp các cờ dirty của {@link MapScene.Node} xuống ĐÚNG dòng trong
 * {@link PrefabDocument}. Không re-serialize YAML: mọi dòng không đổi giữ nguyên từng byte.
 *
 * <p>Bảng ánh xạ cờ dirty → field trong prefab:
 * <table>
 *   <tr><th>cờ</th><th>block</th><th>field</th></tr>
 *   <tr><td>{@code dTransform}</td><td>Transform (trAnchor)</td>
 *       <td>m_LocalPosition (giữ z) · m_LocalScale (giữ z) · m_LocalRotation + m_LocalEulerAnglesHint;
 *           phần LẬT ghi vào m_FlipX/m_FlipY của block SpriteRenderer</td></tr>
 *   <tr><td>{@code dSorting}</td><td>renderer (rendAnchor)</td>
 *       <td>m_SortingLayerID (int32 CÓ DẤU) · m_SortingLayer (index) · m_SortingOrder</td></tr>
 *   <tr><td>{@code dActive}</td><td>GameObject (goAnchor)</td><td>m_IsActive</td></tr>
 *   <tr><td>{@code dRendEnabled}</td><td>renderer (rendAnchor)</td><td>m_Enabled</td></tr>
 *   <tr><td>{@code dPoints}</td><td>collider (colAnchor)</td>
 *       <td>EDGE: m_Points (+ m_Offset) · BOX: m_Size + m_Offset</td></tr>
 *   <tr><td>{@code dTexture}</td><td>renderer (rendAnchor)</td>
 *       <td>Sprite: m_Sprite {fileID, guid, type} · Mesh: item đầu của m_Materials</td></tr>
 *   <tr><td>{@code dFx}</td><td>MỌI {@code Fx.anchor} của node (!u!114 / !u!95)</td>
 *       <td>từng entry của {@code Fx.params} — {@code moveSpeed}, {@code _swimSpeed},
 *           {@code _animationName}, {@code direction}…</td></tr>
 * </table>
 *
 * <p><b>Chỉ ghi dòng THỰC SỰ đổi.</b> Mọi field đều so sánh với giá trị đang có trong file
 * (so ở độ chính xác {@code float}) trước khi ghi — tránh chuyện chuẩn hoá vô ích như
 * {@code -0 → 0} làm git diff phình ra (tài liệu 01 §13.9).
 *
 * <p><b>Xử lý LẬT (flip) — điểm dễ sai nhất.</b> Loader gộp "quay 180° quanh X/Y" của quaternion
 * vào {@code flipX/flipY} (giống PrefabParser), nên trong bộ nhớ
 * {@code n.flipX = m_FlipX XOR quatFlipX}. Writer làm ngược lại cho đúng:
 * <ul>
 *   <li>Giữ nguyên quaternion lật (người dùng không đổi góc) → ghi
 *       {@code m_FlipX = n.flipX XOR quatFlipX} ⇒ file trở về đúng ý nghĩa cũ.</li>
 *   <li>Người dùng ĐỔI góc (rotDeg ≠ 0) → buộc phải ghi đè quaternion thành xoay quanh Z thuần,
 *       phần lật chuyển hết sang {@code m_FlipX = n.flipX}, và log rõ việc chuyển đổi này.</li>
 * </ul>
 *
 * <p>Không bao giờ dùng quaternion 180° để biểu diễn lật khi ghi (đúng yêu cầu đặc tả).
 */
public final class MapSceneWriter {

    /** m_Sprite.fileID của sprite ĐƠN — dùng khi gán sprite mới mà không biết internalID. */
    private static final long SPRITE_SINGLE = 21300000L;
    /** Vector2 inline trong giá trị field hiệu ứng: {@code {x: 0, y: -0.2}}. */
    private static final Pattern XY =
            Pattern.compile("x:\\s*(-?[\\d.eE+]+),\\s*y:\\s*(-?[\\d.eE+]+)");
    /** type của tham chiếu sprite (.png) trong YAML. */
    private static final int TYPE_SPRITE = 3;

    // ── classId Unity của các block phải tự sinh khi THÊM đường kẻ mới ──
    private static final int CLS_GAMEOBJECT        = 1;
    private static final int CLS_TRANSFORM         = 4;
    private static final int CLS_EDGE_COLLIDER     = 68;
    private static final int CLS_PLATFORM_EFFECTOR = 251;
    // ── thêm cho node SPINE ──
    private static final int CLS_MESH_FILTER       = 33;
    private static final int CLS_MESH_RENDERER     = 23;
    private static final int CLS_MONOBEHAVIOUR     = 114;
    /** guid script {@code Spine.Unity.SkeletonAnimation} (spine-unity 4.2 trong client này). */
    private static final String SKELETON_ANIM_SCRIPT_GUID = "d247ba06193faa74d9335f5481b2b56c";
    /** Dấu nhận biết block SkeletonAnimation để nhân bản đúng component (không nhầm script khác). */
    private static final String SKELETON_ANIM_CLASS_ID = "spine-unity::Spine.Unity.SkeletonAnimation";

    private MapSceneWriter() { }

    // ─────────────────────────────────────────────────────────────────────
    // Kết quả
    // ─────────────────────────────────────────────────────────────────────

    /** Kết quả 1 lần apply/save — đủ chi tiết để hiện thẳng lên UI cho người dùng kiểm chứng. */
    public static final class Result {
        /** Số node có ít nhất 1 field bị ghi. */
        public int nodesWritten;
        /** Tổng số field bị ghi. */
        public int fieldsWritten;
        /** Số cảnh báo (field không tìm thấy, dữ liệu không hợp lệ…). */
        public int warnings;
        /** File .bak của lần {@link #save} gần nhất (null nếu không ghi file). */
        public Path backup;
        /** Nhật ký từng thay đổi, mỗi dòng 1 việc. */
        public final List<String> log = new ArrayList<>();
        /** Node bị lỗi → {@link #save} KHÔNG xoá cờ dirty của chúng (người dùng còn sửa lại được). */
        public final List<MapScene.Node> failed = new ArrayList<>();

        void info(String s) { log.add(s); }

        void warn(String s) { log.add("[WARN] " + s); warnings++; }

        void error(MapScene.Node n, String s) {
            log.add("[LỖI] " + s);
            warnings++;
            if (n != null && !failed.contains(n)) failed.add(n);
        }

        /** true = không node nào lỗi. */
        public boolean ok() { return failed.isEmpty(); }

        @Override public String toString() {
            return "Result[node=" + nodesWritten + ", field=" + fieldsWritten
                    + ", cảnh báo=" + warnings + ", lỗi=" + failed.size()
                    + (backup != null ? ", backup=" + backup.getFileName() : "") + "]";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // API
    // ─────────────────────────────────────────────────────────────────────

    /** Áp mọi node dirty vào {@link PrefabDocument}. KHÔNG ghi file, KHÔNG xoá cờ dirty. */
    public static Result apply(MapScene scene, SortingLayers layers) {
        Result r = new Result();
        if (scene == null || scene.doc() == null) {
            r.warn("scene rỗng — không có gì để ghi");
            return r;
        }
        PrefabDocument doc = scene.doc();
        SortingLayers sl = (layers != null) ? layers : SortingLayers.fallback();
        String fn = fileName(scene);

        // ── Pha 1: XOÁ node (gỡ block) — làm TRƯỚC để dòng/anchor không bị lệch ──
        // (bọc ArrayList vì danh sách bị sửa trong lúc duyệt ⇒ tránh ConcurrentModificationException)
        for (MapScene.Node n : new ArrayList<>(scene.deletedNodes())) {
            applyRemoveNode(doc, fn, n, r);
        }
        // ── Pha 2: CHÈN node mới (thêm đường kẻ) ──
        for (MapScene.Node n : new ArrayList<>(scene.newNodes())) {
            applyNewNode(doc, fn, n, r);
        }

        // ── Pha 3: các field thường ──
        for (MapScene.Node n : new ArrayList<>(scene.nodes())) {
            if (n == null || !n.isDirty()) continue;
            int before = r.fieldsWritten;

            if (n.dTransform)   applyTransform(doc, fn, n, r);
            if (n.dSorting)     applySorting(doc, sl, fn, n, r);
            if (n.dActive)      applyActive(doc, fn, n, r);
            if (n.dRendEnabled) applyRendEnabled(doc, fn, n, r);
            if (n.dTexture)     applyTexture(doc, fn, n, r);
            if (n.dSkeleton)    applySkeleton(doc, fn, n, r);
            if (n.dFx)          applyEffect(doc, fn, n, r);
            // Đổi kiểu chặn CHÈN/GỠ block → đổi số dòng; node MỚI đã được dựng đủ ở pha 2 rồi.
            if (n.dLineBlock && !n.dNew) applyLineBlock(doc, fn, n, r);
            // m_Points làm ĐỔI SỐ DÒNG → để cuối. (PrefabDocument tự reindex và cập nhật
            // start/end của Block TẠI CHỖ nên các Block đang cầm vẫn dùng được sau đó.)
            if (n.dPoints)      applyCollider(doc, fn, n, r);

            if (r.fieldsWritten > before) r.nodesWritten++;
        }
        if (r.fieldsWritten == 0) r.info(fn + ": không có thay đổi thực sự nào");
        return r;
    }

    /**
     * {@link #apply} + backup + ghi đè file gốc + xoá cờ dirty.
     * Node bị lỗi (xem {@link Result#failed}) KHÔNG bị xoá cờ dirty.
     */
    public static Result save(MapScene scene, SortingLayers layers, Path backupDir) throws IOException {
        Result r = apply(scene, layers);
        if (scene == null || scene.doc() == null) return r;
        PrefabDocument doc = scene.doc();
        String fn = fileName(scene);

        if (doc.dirty()) {
            doc.save(backupDir);
            r.backup = doc.lastBackup();
            r.info(fn + ": đã ghi đè file gốc" + (r.backup != null ? " · backup " + r.backup : ""));
        } else {
            r.info(fn + ": file không đổi → KHÔNG ghi đè, không tạo backup");
        }
        clearApplied(scene, r);
        return r;
    }

    /** {@link #apply} + ghi ra file KHÁC (self-test — không đụng file client). Giữ nguyên cờ dirty. */
    public static Result saveAs(MapScene scene, SortingLayers layers, Path target) throws IOException {
        Result r = apply(scene, layers);
        if (scene == null || scene.doc() == null) return r;
        scene.doc().saveAs(target);
        r.info(fileName(scene) + ": ghi bản sao → " + target);
        return r;
    }

    /** Xoá cờ dirty của các node đã áp thành công (bỏ qua node trong {@link Result#failed}). */
    private static void clearApplied(MapScene scene, Result r) {
        for (MapScene.Node n : new ArrayList<>(scene.nodes())) {
            if (n != null && !r.failed.contains(n)) n.clearDirty();
        }
        // node đã xoá xong thì bỏ khỏi hàng chờ; node lỗi giữ lại để người dùng thử lại
        scene.deletedNodes().removeIf(n -> !r.failed.contains(n));
    }

    // ─────────────────────────────────────────────────────────────────────
    // THAY ĐỔI CẤU TRÚC: chèn / xoá nguyên block YAML (thêm & xoá đường kẻ)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * CHÈN 3 block (GameObject + Transform + EdgeCollider2D) — thêm block thứ 4
     * (PlatformEffector2D) nếu là đường oneway — rồi nối Transform mới vào {@code m_Children}
     * của Transform CHA. Quên bước cuối là Unity sẽ bỏ rơi node ngay khi mở prefab.
     *
     * <p>Thân block được NHÂN BẢN từ một block cùng loại ĐANG CÓ trong chính file đó
     * ({@link PrefabDocument#copyBlockBody}) rồi mới thay các field cần thiết — an toàn hơn
     * template hằng vì luôn khớp {@code serializedVersion} của file. Chỉ khi file không có block
     * mẫu nào (vd Map_37000 không có node layer 6) mới rơi về template hằng ở cuối lớp này.
     *
     * <p>IDEMPOTENT: {@code apply()} có thể được gọi nhiều lần trên cùng scene
     * ({@code saveAs} không xoá cờ dirty) nên nếu block đã tồn tại thì bỏ qua.
     */
    private static void applyNewNode(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        if (n == null) return;
        if (n.hasFx(MapScene.EffectKind.SPINE)) { applyNewSpineNode(doc, fn, n, r); return; }
        if (n.goAnchor == 0 || n.trAnchor == 0 || n.colAnchor == 0) {
            r.error(n, tag(fn, n) + " thiếu anchor (go/tr/col) → không tạo được block");
            return;
        }
        if (doc.block(n.goAnchor) != null) return;          // đã chèn ở lần apply trước
        if (n.colKind != MapScene.ColKind.EDGE) {
            r.error(n, tag(fn, n) + " chỉ hỗ trợ tạo mới EdgeCollider2D (đường kẻ)");
            return;
        }
        if (n.pts == null || n.pts.length < 2) {
            r.error(n, tag(fn, n) + " EdgeCollider2D chỉ có "
                    + (n.pts == null ? 0 : n.pts.length) + " đỉnh (Unity cần ≥ 2) → KHÔNG tạo");
            return;
        }
        if (n.parentTr == 0) {      // .prefab chỉ được có 1 root — xem MapScene.addEdgeLine
            r.error(n, tag(fn, n) + " không có Transform cha → sẽ tạo ROOT THỨ 2 làm hỏng prefab"
                    + " → KHÔNG chèn");
            return;
        }
        PrefabDocument.Block parentTb = (n.parentTr != 0) ? doc.block(n.parentTr) : null;
        if (n.parentTr != 0 && parentTb == null) {
            r.error(n, tag(fn, n) + " không tìm thấy block Transform cha &" + n.parentTr
                    + " → không chèn (tránh tạo node mồ côi)");
            return;
        }

        boolean oneway = n.hasPlatformEffector || n.physLayer == MapScene.LAYER_ONEWAY;
        if (oneway && n.effAnchor == 0) n.effAnchor = doc.newAnchor();
        long effA = oneway ? n.effAnchor : 0;

        // Chèn ngay TRƯỚC block !u!1 đầu tiên có anchor lớn hơn — giữ bất biến "GameObject tăng dần"
        // của Unity (tài liệu 04 §5.3), tránh diff khổng lồ khi ai đó mở prefab bằng Unity Editor.
        int at = doc.insertPointForGameObject(n.goAnchor);
        int blocks = 0;
        at = insertOne(doc, CLS_GAMEOBJECT, n.goAnchor, "GameObject", goBody(doc, n, effA), at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn block GameObject thất bại"); return; }
        blocks++;
        at = insertOne(doc, CLS_TRANSFORM, n.trAnchor, "Transform", trBody(doc, n), at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn block Transform thất bại"); return; }
        blocks++;
        at = insertOne(doc, CLS_EDGE_COLLIDER, n.colAnchor, "EdgeCollider2D", colBody(doc, n, oneway), at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn block EdgeCollider2D thất bại"); return; }
        blocks++;
        if (oneway) {
            at = insertOne(doc, CLS_PLATFORM_EFFECTOR, effA, "PlatformEffector2D", effBody(doc, n), at);
            if (at < 0) { r.error(n, tag(fn, n) + " chèn block PlatformEffector2D thất bại"); return; }
            blocks++;
        }

        if (parentTb != null && !doc.addChildRef(parentTb, n.trAnchor)) {
            r.error(n, tag(fn, n) + " KHÔNG thêm được vào m_Children của Transform cha &"
                    + n.parentTr + " — Unity sẽ bỏ rơi node này");
        }

        r.info(tag(fn, n) + " THÊM đường kẻ " + (oneway ? "ONEWAY (layer 19)" : "ĐẤT (layer 6)")
                + " · " + n.pts.length + " đỉnh · " + blocks + " block"
                + " · anchor GO &" + n.goAnchor + ", Transform &" + n.trAnchor
                + ", Collider &" + n.colAnchor + (oneway ? ", Effector &" + effA : "")
                + " · cha &" + n.parentTr);
        r.fieldsWritten += blocks;
        r.nodesWritten++;
    }

    /**
     * ĐỔI SKELETON của 1 node Spine: ghi guid mới vào {@code skeletonDataAsset} của
     * {@code SkeletonAnimation} VÀ vào phần tử đầu của {@code m_Materials} trong MeshRenderer.
     *
     * <p><b>Hai guid phải đi cùng nhau.</b> {@code skeletonDataAsset} quyết định hình dạng/animation,
     * còn {@code m_Materials[0]} quyết định ATLAS đem vẽ. Đổi mỗi cái đầu là node lấy lưới của
     * skeleton mới nhưng lấy ảnh của skeleton cũ ⇒ hình rác, mà Unity không báo lỗi gì.
     *
     * <p>Không có material mới (thư mục skeleton thiếu {@code *_Material.mat}) thì CHỈ cảnh báo và
     * vẫn ghi skeleton — vì spine-unity còn gán material lúc chạy từ atlas asset, nên đa số trường
     * hợp vẫn chạy; nhưng phải nói ra để người dùng biết mà kiểm trong Unity.
     */
    private static void applySkeleton(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        MapScene.Fx f = n.fxOf(MapScene.EffectKind.SPINE);
        if (f == null || f.anchor == 0) {
            r.error(n, tag(fn, n) + " đổi skeleton: không tìm thấy component SkeletonAnimation");
            return;
        }
        PrefabDocument.Block sb = doc.block(f.anchor);
        if (sb == null) {
            r.error(n, tag(fn, n) + " đổi skeleton: mất block SkeletonAnimation &" + f.anchor);
            return;
        }
        if (f.assetGuid == null) {
            r.error(n, tag(fn, n) + " đổi skeleton: thiếu guid skeleton mới");
            return;
        }
        if (!doc.setGuid(sb, "skeletonDataAsset:", f.assetGuid)) {
            r.error(n, tag(fn, n) + " đổi skeleton: không thấy dòng skeletonDataAsset");
            return;
        }
        int wrote = 1;

        if (n.skelMatGuid != null && n.rendAnchor != 0) {
            PrefabDocument.Block rb = doc.block(n.rendAnchor);
            if (rb != null && doc.setFirstListGuid(rb, "m_Materials:", n.skelMatGuid)) {
                wrote++;
            } else {
                r.warn(tag(fn, n) + " đổi skeleton: KHÔNG ghi được m_Materials — node sẽ vẽ bằng"
                        + " atlas của skeleton CŨ, kiểm lại trong Unity");
            }
        } else if (n.skelMatGuid == null) {
            r.warn(tag(fn, n) + " đổi skeleton: skeleton mới không có *_Material.mat cạnh nó"
                    + " → giữ nguyên material cũ, kiểm lại trong Unity");
        }

        r.info(tag(fn, n) + " ĐỔI SKELETON → '" + f.name + "'"
                + " · skeletonDataAsset=" + f.assetGuid
                + (wrote > 1 ? " · material=" + n.skelMatGuid : " · material GIỮ NGUYÊN"));
        r.fieldsWritten += wrote;
    }

    /**
     * CHÈN node SPINE mới: 5 block (GameObject + Transform + MeshFilter + MeshRenderer +
     * MonoBehaviour SkeletonAnimation) rồi nối Transform vào {@code m_Children} của cha.
     *
     * <p>MeshFilter/MeshRenderer để TRỐNG lưới ({@code m_Mesh: {fileID: 0}}) — spine-unity tự đổ
     * lưới lúc chạy. Thứ bắt buộc đúng là {@code m_Materials[0]} phải là material của CHÍNH
     * skeleton đó, và {@code m_Script} phải là guid của {@code SkeletonAnimation}.
     *
     * <p>IDEMPOTENT như {@link #applyNewNode}: block đã tồn tại thì bỏ qua.
     */
    private static void applyNewSpineNode(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        MapScene.Fx f = n.fxOf(MapScene.EffectKind.SPINE);
        if (f == null || f.assetGuid == null) {
            r.error(n, tag(fn, n) + " thêm Spine: thiếu skeleton");
            return;
        }
        if (n.goAnchor == 0 || n.trAnchor == 0 || n.mfAnchor == 0 || n.rendAnchor == 0 || f.anchor == 0) {
            r.error(n, tag(fn, n) + " thêm Spine: thiếu anchor (go/tr/mf/rend/fx)");
            return;
        }
        if (doc.block(n.goAnchor) != null) return;              // đã chèn ở lần apply trước
        if (n.parentTr == 0) {
            r.error(n, tag(fn, n) + " thêm Spine: không có Transform cha → sẽ tạo ROOT THỨ 2 làm hỏng prefab");
            return;
        }
        PrefabDocument.Block parentTb = doc.block(n.parentTr);
        if (parentTb == null) {
            r.error(n, tag(fn, n) + " thêm Spine: không thấy block Transform cha &" + n.parentTr);
            return;
        }
        if (n.skelMatGuid == null) {
            r.warn(tag(fn, n) + " thêm Spine: skeleton '" + f.name + "' không có *_Material.mat"
                    + " → MeshRenderer để material rỗng, kiểm lại trong Unity");
        }

        int at = doc.insertPointForGameObject(n.goAnchor);
        int blocks = 0;
        at = insertOne(doc, CLS_GAMEOBJECT, n.goAnchor, "GameObject", spineGoBody(doc, n, f.anchor), at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn GameObject thất bại"); return; }
        blocks++;
        at = insertOne(doc, CLS_TRANSFORM, n.trAnchor, "Transform", trBody(doc, n), at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn Transform thất bại"); return; }
        blocks++;
        at = insertOne(doc, CLS_MESH_FILTER, n.mfAnchor, "MeshFilter", meshFilterBody(doc, n), at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn MeshFilter thất bại"); return; }
        blocks++;
        at = insertOne(doc, CLS_MESH_RENDERER, n.rendAnchor, "MeshRenderer", meshRendererBody(doc, n), at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn MeshRenderer thất bại"); return; }
        blocks++;
        List<String> sa = skeletonAnimBody(doc, n, f);
        at = insertOne(doc, CLS_MONOBEHAVIOUR, f.anchor, "MonoBehaviour", sa, at);
        if (at < 0) { r.error(n, tag(fn, n) + " chèn SkeletonAnimation thất bại"); return; }
        blocks++;

        if (!doc.addChildRef(parentTb, n.trAnchor)) {
            r.error(n, tag(fn, n) + " KHÔNG thêm được vào m_Children của cha &" + n.parentTr
                    + " — Unity sẽ bỏ rơi node này");
        }

        r.info(tag(fn, n) + " THÊM node Spine '" + f.name + "'"
                + " · " + blocks + " block · skeleton=" + f.assetGuid
                + " · material=" + (n.skelMatGuid == null ? "(rỗng)" : n.skelMatGuid)
                + " · anim='" + n.fxParams.getOrDefault("_animationName", "") + "'"
                + " · cha &" + n.parentTr);
        r.fieldsWritten += blocks;
        r.nodesWritten++;
    }

    private static List<String> spineGoBody(PrefabDocument doc, MapScene.Node n, long fxAnchor) {
        List<String> body = template(doc, CLS_GAMEOBJECT, "GameObject", GO_TEMPLATE);
        List<String> comp = new ArrayList<>();
        comp.add("component: {fileID: " + n.trAnchor + "}");     // Transform LUÔN đứng đầu
        comp.add("component: {fileID: " + n.mfAnchor + "}");
        comp.add("component: {fileID: " + n.rendAnchor + "}");
        comp.add("component: {fileID: " + fxAnchor + "}");
        setBodyList(body, "m_Component:", comp);
        clearPrefabRefs(body);
        setBodyScalar(body, "m_Layer:", String.valueOf(n.physLayer));
        setBodyScalar(body, "m_Name:", yamlName(n.rawName != null ? n.rawName : n.name));
        setBodyScalar(body, "m_TagString:", "Untagged");
        setBodyScalar(body, "m_Icon:", "{fileID: 0}");
        setBodyScalar(body, "m_NavMeshLayer:", "0");
        setBodyScalar(body, "m_StaticEditorFlags:", "0");
        setBodyScalar(body, "m_IsActive:", n.active ? "1" : "0");
        return body;
    }

    private static List<String> meshFilterBody(PrefabDocument doc, MapScene.Node n) {
        List<String> body = template(doc, CLS_MESH_FILTER, "MeshFilter", MESH_FILTER_TEMPLATE);
        clearPrefabRefs(body);
        setBodyScalar(body, "m_GameObject:", "{fileID: " + n.goAnchor + "}");
        setBodyScalar(body, "m_Mesh:", "{fileID: 0}");           // spine-unity đổ lưới lúc chạy
        return body;
    }

    private static List<String> meshRendererBody(PrefabDocument doc, MapScene.Node n) {
        // Nhân bản MeshRenderer của node SPINE đang có trong file nếu tìm được — nó đã đúng mọi cờ
        // (không đổ bóng, sorting…); không có thì lấy MeshRenderer bất kỳ, cuối cùng mới tới template.
        List<String> body = template(doc, CLS_MESH_RENDERER, "MeshRenderer", MESH_RENDERER_TEMPLATE);
        clearPrefabRefs(body);
        setBodyScalar(body, "m_GameObject:", "{fileID: " + n.goAnchor + "}");
        setBodyScalar(body, "m_Enabled:", "1");
        setBodyScalar(body, "m_SortingLayerID:", String.valueOf(n.sortLayerId));
        setBodyScalar(body, "m_SortingLayer:", String.valueOf(n.sortLayerIdx));
        setBodyScalar(body, "m_SortingOrder:", String.valueOf(n.sortOrder));
        setBodyList(body, "m_Materials:", n.skelMatGuid == null ? null
                : List.of("{fileID: 2100000, guid: " + n.skelMatGuid + ", type: 2}"));
        return body;
    }

    /**
     * Thân {@code SkeletonAnimation}. Ưu tiên NHÂN BẢN block SkeletonAnimation đang có trong chính
     * file (khớp đúng phiên bản spine-unity của dự án, không sót field nào), chỉ khi map chưa có
     * node Spine nào mới dùng template hằng.
     */
    private static List<String> skeletonAnimBody(PrefabDocument doc, MapScene.Node n, MapScene.Fx f) {
        List<String> body = null;
        for (PrefabDocument.Block b : doc.blocksOfClass(CLS_MONOBEHAVIOUR)) {
            if (b.stripped || b.start < 0) continue;
            String id = doc.getScalar(b, "m_EditorClassIdentifier:");
            if (id == null || !id.trim().equals(SKELETON_ANIM_CLASS_ID)) continue;
            body = doc.copyBlockBody(b);
            if (!body.isEmpty()) break;
            body = null;
        }
        if (body == null) body = new ArrayList<>(java.util.Arrays.asList(SKELETON_ANIM_TEMPLATE));

        clearPrefabRefs(body);
        setBodyScalar(body, "m_GameObject:", "{fileID: " + n.goAnchor + "}");
        setBodyScalar(body, "m_Enabled:", "1");
        setBodyScalar(body, "m_EditorHideFlags:", "0");
        setBodyScalar(body, "m_Script:",
                "{fileID: 11500000, guid: " + SKELETON_ANIM_SCRIPT_GUID + ", type: 3}");
        setBodyScalar(body, "m_Name:", "");
        setBodyScalar(body, "m_EditorClassIdentifier:", SKELETON_ANIM_CLASS_ID);
        setBodyScalar(body, "skeletonDataAsset:",
                "{fileID: 11400000, guid: " + f.assetGuid + ", type: 2}");
        setBodyScalar(body, "initialSkinName:", "");
        setBodyScalar(body, "_animationName:", yamlName(n.fxParams.getOrDefault("_animationName", "")));
        setBodyScalar(body, "loop:", "1");
        setBodyScalar(body, "timeScale:", "1");
        return body;
    }

    /** Chèn 1 block tại {@code at}; trả dòng chèn tiếp theo, hoặc -1 nếu thất bại. */
    private static int insertOne(PrefabDocument doc, int classId, long anchor, String type,
                                 List<String> body, int at) {
        if (body == null) return -1;
        PrefabDocument.Block b = doc.insertBlock(classId, anchor, type, body, at);
        return (b != null) ? at + 2 + body.size() : -1;
    }

    /**
     * XOÁ trọn 1 node: gỡ {@code - {fileID:}} khỏi {@code m_Children} của cha, rồi xoá block
     * GameObject + MỌI block component của nó (Transform, collider, effector, MonoBehaviour…).
     *
     * <p>Không tự quyết định được/không được xoá — việc đó do {@link MapScene#canRemove} chặn
     * từ trước (nó quét tham chiếu thật bằng {@link PrefabDocument#blocksReferencing}).
     * IDEMPOTENT: block đã bị xoá ở lần apply trước thì không làm gì nữa.
     */
    private static void applyRemoveNode(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        if (n == null || n.goAnchor == 0) return;

        List<PrefabDocument.Block> kill = new ArrayList<>();
        PrefabDocument.Block gb = doc.block(n.goAnchor);
        if (gb != null && gb.start >= 0) kill.add(gb);
        for (PrefabDocument.Block b : doc.blocksOfGameObject(n.goAnchor)) {
            if (!kill.contains(b)) kill.add(b);
        }
        for (long a : new long[]{n.trAnchor, n.colAnchor, n.effAnchor, n.rendAnchor}) {
            if (a == 0) continue;
            PrefabDocument.Block b = doc.block(a);
            if (b != null && b.start >= 0 && !kill.contains(b)) kill.add(b);
        }
        if (kill.isEmpty()) return;          // đã xoá rồi → không làm gì (idempotent)

        // Gỡ khỏi m_Children của cha TRƯỚC (cần block cha còn sống). Cha cũng đang bị xoá
        // (xoá cả cây con) thì bỏ qua — không cảnh báo vì đó là chuyện bình thường.
        PrefabDocument.Block pb = (n.parentTr != 0) ? doc.block(n.parentTr) : null;
        boolean unlinked = (pb != null && pb.start >= 0) && doc.removeChildRef(pb, n.trAnchor);

        int cnt = 0;
        StringBuilder ids = new StringBuilder();
        for (PrefabDocument.Block b : kill) {
            String desc = "!u!" + b.classId + " &" + b.anchor;
            if (doc.removeBlock(b)) {
                cnt++;
                if (ids.length() > 0) ids.append(", ");
                ids.append(desc);
            }
        }
        r.info(tag(fn, n) + " XOÁ đường kẻ · " + cnt + " block (" + ids + ")"
                + (unlinked ? " · đã gỡ khỏi m_Children của cha &" + n.parentTr
                            : " · cha không còn (bị xoá cùng)"));
        r.fieldsWritten += cnt;
        r.nodesWritten++;
    }

    // ── dựng thân block ────────────────────────────────────────────────────

    private static List<String> goBody(PrefabDocument doc, MapScene.Node n, long effA) {
        List<String> body = template(doc, CLS_GAMEOBJECT, "GameObject", GO_TEMPLATE);
        List<String> comp = new ArrayList<>();
        comp.add("component: {fileID: " + n.trAnchor + "}");     // Transform LUÔN đứng đầu
        comp.add("component: {fileID: " + n.colAnchor + "}");
        if (effA != 0) comp.add("component: {fileID: " + effA + "}");
        setBodyList(body, "m_Component:", comp);
        clearPrefabRefs(body);
        setBodyScalar(body, "m_Layer:", String.valueOf(n.physLayer));
        setBodyScalar(body, "m_Name:", yamlName(n.rawName != null ? n.rawName : n.name));
        setBodyScalar(body, "m_TagString:",
                (n.tag != null && !n.tag.isEmpty()) ? yamlName(n.tag) : "Untagged");
        setBodyScalar(body, "m_Icon:", "{fileID: 0}");
        setBodyScalar(body, "m_NavMeshLayer:", "0");
        setBodyScalar(body, "m_StaticEditorFlags:", "0");
        setBodyScalar(body, "m_IsActive:", n.active ? "1" : "0");
        return body;
    }

    private static List<String> trBody(PrefabDocument doc, MapScene.Node n) {
        List<String> body = template(doc, CLS_TRANSFORM, "Transform", TR_TEMPLATE);
        clearPrefabRefs(body);
        setBodyScalar(body, "m_GameObject:", "{fileID: " + n.goAnchor + "}");
        setBodyScalar(body, "m_LocalRotation:", "{x: 0, y: 0, z: 0, w: 1}");
        setBodyScalar(body, "m_LocalPosition:",
                "{x: " + PrefabDocument.fmt(n.px) + ", y: " + PrefabDocument.fmt(n.py) + ", z: 0}");
        setBodyScalar(body, "m_LocalScale:",
                "{x: " + PrefabDocument.fmt(n.sx) + ", y: " + PrefabDocument.fmt(n.sy) + ", z: 1}");
        setBodyScalar(body, "m_ConstrainProportionsScale:", "0");
        setBodyList(body, "m_Children:", null);                  // → "  m_Children: []"
        setBodyScalar(body, "m_Father:", "{fileID: " + n.parentTr + "}");
        setBodyScalar(body, "m_LocalEulerAnglesHint:", "{x: 0, y: 0, z: 0}");
        return body;
    }

    private static List<String> colBody(PrefabDocument doc, MapScene.Node n, boolean oneway) {
        List<String> body = template(doc, CLS_EDGE_COLLIDER, "EdgeCollider2D", EDGE_TEMPLATE);
        clearPrefabRefs(body);
        setBodyScalar(body, "m_GameObject:", "{fileID: " + n.goAnchor + "}");
        setBodyScalar(body, "m_Enabled:", n.colEnabled ? "1" : "0");
        setBodyScalar(body, "m_IsTrigger:", n.isTrigger ? "1" : "0");
        // Quên m_UsedByEffector = 1 thì PlatformEffector2D vô hiệu ⇒ đường oneway thành đường đất.
        setBodyScalar(body, "m_UsedByEffector:", oneway ? "1" : "0");
        setBodyScalar(body, "m_CompositeOperation:", "0");
        setBodyScalar(body, "m_CompositeOrder:", "0");
        setBodyScalar(body, "m_Offset:",
                "{x: " + PrefabDocument.fmt(n.offX) + ", y: " + PrefabDocument.fmt(n.offY) + "}");
        setBodyScalar(body, "m_EdgeRadius:", "0");
        List<String> items = new ArrayList<>();
        for (double[] p : n.pts) {
            items.add("{x: " + PrefabDocument.fmt(p[0]) + ", y: " + PrefabDocument.fmt(p[1]) + "}");
        }
        setBodyList(body, "m_Points:", items);
        setBodyScalar(body, "m_AdjacentStartPoint:", "{x: 0, y: 0}");
        setBodyScalar(body, "m_AdjacentEndPoint:", "{x: 0, y: 0}");
        setBodyScalar(body, "m_UseAdjacentStartPoint:", "0");
        setBodyScalar(body, "m_UseAdjacentEndPoint:", "0");
        return body;
    }

    private static List<String> effBody(PrefabDocument doc, MapScene.Node n) {
        List<String> body = template(doc, CLS_PLATFORM_EFFECTOR, "PlatformEffector2D", EFFECTOR_TEMPLATE);
        clearPrefabRefs(body);
        setBodyScalar(body, "m_GameObject:", "{fileID: " + n.goAnchor + "}");
        setBodyScalar(body, "m_Enabled:", "1");
        setBodyScalar(body, "m_UseOneWay:", "1");
        // CHIỀU CHẶN: 0 = mặt chặn hướng lên (nhảy từ dưới xuyên qua) · 180 = chặn đi lên.
        // Cung 0° = không chặn hướng nào (đi xuyên cả 2 chiều).
        setBodyScalar(body, "m_RotationalOffset:", PrefabDocument.fmt(n.effRotOffset));
        setBodyScalar(body, "m_SurfaceArc:", PrefabDocument.fmt(n.effSurfaceArc));
        return body;
    }

    /**
     * {@code dLineBlock} — đổi KIỂU CHẶN của 1 đường kẻ đã có trong file:
     * <ul>
     *   <li>{@code m_Layer} của GameObject: 6 Ground ↔ 19 Oneway (client lọc theo layer này);</li>
     *   <li>{@code m_UsedByEffector} của EdgeCollider2D — quên nó thì effector VÔ HIỆU,
     *       đường oneway lại thành đường đất;</li>
     *   <li>CHÈN block {@code PlatformEffector2D} (!u!251) + nối vào {@code m_Component} của
     *       GameObject, hoặc GỠ cả hai khi về đường đặc;</li>
     *   <li>{@code m_RotationalOffset} / {@code m_SurfaceArc} — chiều và bề rộng mặt chặn.</li>
     * </ul>
     * Mọi thao tác đều LẤY LẠI block theo anchor sau mỗi lần đổi số dòng (reindex làm Block cũ lệch).
     */
    private static void applyLineBlock(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        if (n.colKind != MapScene.ColKind.EDGE) {
            r.error(n, tag(fn, n) + " không phải EdgeCollider2D → bỏ qua đổi kiểu chặn");
            return;
        }
        PrefabDocument.Block gb = doc.block(n.goAnchor);
        PrefabDocument.Block cb = doc.block(n.colAnchor);
        if (gb == null || cb == null) {
            r.error(n, tag(fn, n) + " thiếu block GameObject &" + n.goAnchor
                    + " hoặc EdgeCollider2D &" + n.colAnchor);
            return;
        }
        int wrote = 0;
        boolean oneway = n.hasPlatformEffector;

        if (doc.setScalar(gb, "m_Layer:", String.valueOf(n.physLayer))) wrote++;
        if (doc.setScalar(cb, "m_UsedByEffector:", oneway ? "1" : "0")) wrote++;

        PrefabDocument.Block eb = (n.effAnchor != 0) ? doc.block(n.effAnchor) : null;
        if (oneway && eb == null) {
            if (n.effAnchor == 0) n.effAnchor = doc.newAnchor();
            gb = doc.block(n.goAnchor);
            if (gb == null || !doc.addComponentRef(gb, n.effAnchor)) {
                r.error(n, tag(fn, n) + " không nối được PlatformEffector2D vào m_Component");
                return;
            }
            cb = doc.block(n.colAnchor);                       // reindex xong → lấy lại mốc chèn
            eb = doc.insertBlock(CLS_PLATFORM_EFFECTOR, n.effAnchor, "PlatformEffector2D",
                    effBody(doc, n), cb != null ? cb.end : doc.lines().size());
            if (eb == null) {
                r.error(n, tag(fn, n) + " chèn block PlatformEffector2D thất bại");
                return;
            }
            wrote++;
            r.info(tag(fn, n) + " THÊM PlatformEffector2D &" + n.effAnchor);
        } else if (!oneway && eb != null) {
            gb = doc.block(n.goAnchor);
            if (gb != null) doc.removeComponentRef(gb, n.effAnchor);
            eb = doc.block(n.effAnchor);
            if (eb != null && doc.removeBlock(eb)) wrote++;
            r.info(tag(fn, n) + " GỠ PlatformEffector2D &" + n.effAnchor);
            n.effAnchor = 0;
            eb = null;
        }

        if (eb != null) {
            eb = doc.block(n.effAnchor);
            if (eb != null) {
                if (doc.setScalar(eb, "m_UseOneWay:", "1")) wrote++;
                if (doc.setScalar(eb, "m_RotationalOffset:", PrefabDocument.fmt(n.effRotOffset))) wrote++;
                if (doc.setScalar(eb, "m_SurfaceArc:", PrefabDocument.fmt(n.effSurfaceArc))) wrote++;
            }
        }

        if (wrote > 0) {
            r.info(tag(fn, n) + " kiểu chặn → " + n.lineBlock().label
                    + " (layer " + n.physLayer + (oneway
                        ? ", xoay " + PrefabDocument.fmt(n.effRotOffset)
                          + "°, cung " + PrefabDocument.fmt(n.effSurfaceArc) + "°"
                        : ", không effector") + ")");
        }
        r.fieldsWritten += wrote;
    }

    /**
     * Thân block mẫu: ưu tiên NHÂN BẢN block cùng loại đang có trong CHÍNH file (khớp
     * serializedVersion của file đó); không có thì dùng template hằng đã đối chiếu với
     * 1760/1760 block !u!68 và 549/549 block !u!251 của 156 map.
     */
    private static List<String> template(PrefabDocument doc, int classId, String typeName, String[] fallback) {
        for (PrefabDocument.Block b : doc.blocksOfClass(classId)) {
            if (b.stripped || b.start < 0 || !typeName.equals(b.type)) continue;
            List<String> body = doc.copyBlockBody(b);
            if (!body.isEmpty()) return body;
        }
        return new ArrayList<>(java.util.Arrays.asList(fallback));
    }

    /** Block do tool tạo LUÔN là object riêng của prefab này, không thuộc nested prefab nào. */
    private static void clearPrefabRefs(List<String> body) {
        setBodyScalar(body, "m_ObjectHideFlags:", "0");
        setBodyScalar(body, "m_CorrespondingSourceObject:", "{fileID: 0}");
        setBodyScalar(body, "m_PrefabInstance:", "{fileID: 0}");
        setBodyScalar(body, "m_PrefabAsset:", "{fileID: 0}");
    }

    /** Ghi lại giá trị của key ở indent 2 trong thân block đang dựng. false = block mẫu thiếu key. */
    private static boolean setBodyScalar(List<String> body, String key, String value) {
        String k = key.endsWith(":") ? key : key + ":";
        for (int i = 0; i < body.size(); i++) {
            if (!body.get(i).startsWith("  " + k)) continue;
            body.set(i, "  " + k + " " + value);
            return true;
        }
        return false;
    }

    /**
     * Thay TOÀN BỘ list nhiều dòng dưới key (m_Component / m_Children / m_Points).
     * Item nhận vào KHÔNG kèm "- " (hàm tự thêm, indent 2 đúng kiểu Unity).
     * items rỗng/null → ghi inline {@code "  key: []"}.
     */
    private static boolean setBodyList(List<String> body, String key, List<String> items) {
        String k = key.endsWith(":") ? key : key + ":";
        int at = -1;
        for (int i = 0; i < body.size(); i++) {
            if (body.get(i).startsWith("  " + k)) { at = i; break; }
        }
        if (at < 0) return false;
        int end = at + 1;
        while (end < body.size() && body.get(end).startsWith("  - ")) end++;
        List<String> repl = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            repl.add("  " + k + " []");
        } else {
            repl.add("  " + k);
            for (String it : items) repl.add("  - " + it);
        }
        body.subList(at, end).clear();
        body.addAll(at, repl);
        return true;
    }

    /** Bọc nháy đơn khi tên có ký tự làm hỏng YAML (Unity cũng làm vậy). */
    private static String yamlName(String s) {
        String v = (s == null) ? "" : s;
        if (v.isEmpty()) return "''";
        boolean needQuote = v.contains(": ") || v.endsWith(":") || v.contains(" #")
                || !v.equals(v.trim())
                || "-?:,[]{}#&*!|>'\"%@`".indexOf(v.charAt(0)) >= 0;
        return needQuote ? "'" + v.replace("'", "''") + "'" : v;
    }

    // ── template hằng (chỉ dùng khi file KHÔNG có block mẫu cùng loại) ──
    // Chép nguyên văn từ Map_1.prefab; schema đồng nhất 100 % trên 156 map (tài liệu 04 §1).

    private static final String[] GO_TEMPLATE = {
        "  m_ObjectHideFlags: 0",
        "  m_CorrespondingSourceObject: {fileID: 0}",
        "  m_PrefabInstance: {fileID: 0}",
        "  m_PrefabAsset: {fileID: 0}",
        "  serializedVersion: 6",
        "  m_Component:",
        "  - component: {fileID: 0}",
        "  m_Layer: 6",
        "  m_Name: Ground",
        "  m_TagString: Untagged",
        "  m_Icon: {fileID: 0}",
        "  m_NavMeshLayer: 0",
        "  m_StaticEditorFlags: 0",
        "  m_IsActive: 1",
    };

    private static final String[] TR_TEMPLATE = {
        "  m_ObjectHideFlags: 0",
        "  m_CorrespondingSourceObject: {fileID: 0}",
        "  m_PrefabInstance: {fileID: 0}",
        "  m_PrefabAsset: {fileID: 0}",
        "  m_GameObject: {fileID: 0}",
        "  serializedVersion: 2",
        "  m_LocalRotation: {x: 0, y: 0, z: 0, w: 1}",
        "  m_LocalPosition: {x: 0, y: 0, z: 0}",
        "  m_LocalScale: {x: 1, y: 1, z: 1}",
        "  m_ConstrainProportionsScale: 0",
        "  m_Children: []",
        "  m_Father: {fileID: 0}",
        "  m_LocalEulerAnglesHint: {x: 0, y: 0, z: 0}",
    };

    // ── Template dự phòng cho node SPINE (chỉ dùng khi file CHƯA có block cùng loại để nhân bản) ──
    private static final String[] MESH_FILTER_TEMPLATE = {
        "  m_ObjectHideFlags: 0",
        "  m_CorrespondingSourceObject: {fileID: 0}",
        "  m_PrefabInstance: {fileID: 0}",
        "  m_PrefabAsset: {fileID: 0}",
        "  m_GameObject: {fileID: 0}",
        "  m_Mesh: {fileID: 0}",
    };

    private static final String[] MESH_RENDERER_TEMPLATE = {
        "  m_ObjectHideFlags: 0",
        "  m_CorrespondingSourceObject: {fileID: 0}",
        "  m_PrefabInstance: {fileID: 0}",
        "  m_PrefabAsset: {fileID: 0}",
        "  m_GameObject: {fileID: 0}",
        "  m_Enabled: 1",
        "  m_CastShadows: 1",
        "  m_ReceiveShadows: 1",
        "  m_DynamicOccludee: 1",
        "  m_StaticShadowCaster: 0",
        "  m_MotionVectors: 1",
        "  m_LightProbeUsage: 1",
        "  m_ReflectionProbeUsage: 1",
        "  m_RayTracingMode: 2",
        "  m_RayTraceProcedural: 0",
        "  m_RenderingLayerMask: 1",
        "  m_RendererPriority: 0",
        "  m_Materials:",
        "  - {fileID: 0}",
        "  m_StaticBatchInfo:",
        "    firstSubMesh: 0",
        "    subMeshCount: 0",
        "  m_StaticBatchRoot: {fileID: 0}",
        "  m_ProbeAnchor: {fileID: 0}",
        "  m_LightProbeVolumeOverride: {fileID: 0}",
        "  m_ScaleInLightmap: 1",
        "  m_ReceiveGI: 1",
        "  m_PreserveUVs: 0",
        "  m_IgnoreNormalsForChartDetection: 0",
        "  m_ImportantGI: 0",
        "  m_StitchLightmapSeams: 1",
        "  m_SelectedEditorRenderState: 3",
        "  m_MinimumChartSize: 4",
        "  m_AutoUVMaxDistance: 0.5",
        "  m_AutoUVMaxAngle: 89",
        "  m_LightmapParameters: {fileID: 0}",
        "  m_SortingLayerID: 0",
        "  m_SortingLayer: 0",
        "  m_SortingOrder: 0",
        "  m_AdditionalVertexStreams: {fileID: 0}",
    };

    /**
     * Thân {@code Spine.Unity.SkeletonAnimation} chép nguyên từ một node Spine THẬT của
     * {@code Map_13.prefab} (spine-unity 4.2 của client này). Chỉ dùng khi map chưa có node Spine
     * nào để nhân bản — có block thật trong file thì luôn ưu tiên nhân bản, vì template hằng sẽ lỗi
     * thời ngay khi ai đó nâng cấp spine-unity.
     */
    private static final String[] SKELETON_ANIM_TEMPLATE = {
        "  m_ObjectHideFlags: 0",
        "  m_CorrespondingSourceObject: {fileID: 0}",
        "  m_PrefabInstance: {fileID: 0}",
        "  m_PrefabAsset: {fileID: 0}",
        "  m_GameObject: {fileID: 0}",
        "  m_Enabled: 1",
        "  m_EditorHideFlags: 0",
        "  m_Script: {fileID: 11500000, guid: " + SKELETON_ANIM_SCRIPT_GUID + ", type: 3}",
        "  m_Name: ",
        "  m_EditorClassIdentifier: " + SKELETON_ANIM_CLASS_ID,
        "  skeletonDataAsset: {fileID: 0}",
        "  initialSkinName: ",
        "  fixPrefabOverrideViaMeshFilter: 2",
        "  initialFlipX: 0",
        "  initialFlipY: 0",
        "  updateWhenInvisible: 3",
        "  separatorSlotNames: []",
        "  zSpacing: 0",
        "  useClipping: 1",
        "  immutableTriangles: 0",
        "  pmaVertexColors: 1",
        "  clearStateOnDisable: 0",
        "  tintBlack: 0",
        "  singleSubmesh: 0",
        "  fixDrawOrder: 0",
        "  addNormals: 0",
        "  calculateTangents: 0",
        "  maskInteraction: 0",
        "  maskMaterials:",
        "    materialsMaskDisabled: []",
        "    materialsInsideMask: []",
        "    materialsOutsideMask: []",
        "  disableRenderingOnOverride: 1",
        "  physicsPositionInheritanceFactor: {x: 1, y: 1}",
        "  physicsRotationInheritanceFactor: 1",
        "  physicsMovementRelativeTo: {fileID: 0}",
        "  updateTiming: 1",
        "  unscaledTime: 0",
        "  _animationName: ",
        "  loop: 1",
        "  timeScale: 1",
    };

    private static final String[] EDGE_TEMPLATE = {
        "  m_ObjectHideFlags: 0",
        "  m_CorrespondingSourceObject: {fileID: 0}",
        "  m_PrefabInstance: {fileID: 0}",
        "  m_PrefabAsset: {fileID: 0}",
        "  m_GameObject: {fileID: 0}",
        "  m_Enabled: 1",
        "  serializedVersion: 3",
        "  m_Density: 1",
        "  m_Material: {fileID: 0}",
        "  m_IncludeLayers:",
        "    serializedVersion: 2",
        "    m_Bits: 0",
        "  m_ExcludeLayers:",
        "    serializedVersion: 2",
        "    m_Bits: 0",
        "  m_LayerOverridePriority: 0",
        "  m_ForceSendLayers:",
        "    serializedVersion: 2",
        "    m_Bits: 4294967295",
        "  m_ForceReceiveLayers:",
        "    serializedVersion: 2",
        "    m_Bits: 4294967295",
        "  m_ContactCaptureLayers:",
        "    serializedVersion: 2",
        "    m_Bits: 4294967295",
        "  m_CallbackLayers:",
        "    serializedVersion: 2",
        "    m_Bits: 4294967295",
        "  m_IsTrigger: 0",
        "  m_UsedByEffector: 0",
        "  m_CompositeOperation: 0",
        "  m_CompositeOrder: 0",
        "  m_Offset: {x: 0, y: 0}",
        "  m_EdgeRadius: 0",
        "  m_Points:",
        "  - {x: -1, y: 0}",
        "  - {x: 1, y: 0}",
        "  m_AdjacentStartPoint: {x: 0, y: 0}",
        "  m_AdjacentEndPoint: {x: 0, y: 0}",
        "  m_UseAdjacentStartPoint: 0",
        "  m_UseAdjacentEndPoint: 0",
    };

    private static final String[] EFFECTOR_TEMPLATE = {
        "  m_ObjectHideFlags: 0",
        "  m_CorrespondingSourceObject: {fileID: 0}",
        "  m_PrefabInstance: {fileID: 0}",
        "  m_PrefabAsset: {fileID: 0}",
        "  m_GameObject: {fileID: 0}",
        "  m_Enabled: 1",
        "  m_UseColliderMask: 1",
        "  m_ColliderMask:",
        "    serializedVersion: 2",
        "    m_Bits: 4294967295",
        "  m_RotationalOffset: 0",
        "  m_UseOneWay: 1",
        "  m_UseOneWayGrouping: 0",
        "  m_SurfaceArc: 90",
        "  m_UseSideFriction: 0",
        "  m_UseSideBounce: 0",
        "  m_SideArc: 1",
    };

    // ─────────────────────────────────────────────────────────────────────
    // dTransform → Transform (+ m_FlipX/m_FlipY của SpriteRenderer)
    // ─────────────────────────────────────────────────────────────────────

    private static void applyTransform(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        PrefabDocument.Block tb = doc.block(n.trAnchor);
        if (tb == null) {
            r.error(n, tag(fn, n) + " không tìm thấy block Transform &" + n.trAnchor);
            return;
        }

        // ── vị trí (giữ nguyên z) ──
        double[] pos = doc.getVec(tb, "m_LocalPosition:");
        if (pos == null) {
            r.warn(tag(fn, n) + " không có m_LocalPosition → bỏ qua vị trí");
        } else if (!sameF(pos[0], n.px) || !sameF(pos[1], n.py)) {
            if (doc.setVec2(tb, "m_LocalPosition:", n.px, n.py)) {
                chg(r, fn, n, "m_LocalPosition", xy(pos[0], pos[1]), xy(n.px, n.py));
            }
        }

        // ── scale (giữ nguyên z) ──
        double[] scl = doc.getVec(tb, "m_LocalScale:");
        if (scl == null) {
            r.warn(tag(fn, n) + " không có m_LocalScale → bỏ qua scale");
        } else if (!sameF(scl[0], n.sx) || !sameF(scl[1], n.sy)) {
            if (doc.setVec2(tb, "m_LocalScale:", n.sx, n.sy)) {
                chg(r, fn, n, "m_LocalScale", xy(scl[0], scl[1]), xy(n.sx, n.sy));
            }
        }

        // ── xoay ──
        // Cờ lật mà LOADER đã suy ra từ quaternion (|qw|<0.1 và |qy|/|qx|>0.9).
        boolean quatFlipX = Math.abs(n.qw) < 0.1 && Math.abs(n.qy) > 0.9;
        boolean quatFlipY = Math.abs(n.qw) < 0.1 && Math.abs(n.qx) > 0.9;
        boolean quatFlip = quatFlipX || quatFlipY;

        // Góc mà LOADER đã suy ra từ quaternion trong file — CÔNG THỨC PHẢI Y HỆT MapSceneLoader.
        // So với góc này (chứ không so quaternion) mới biết người dùng CÓ ĐỔI GÓC hay không.
        double loadedDeg = (Math.abs(n.qx) < 0.1 && Math.abs(n.qy) < 0.1)
                ? Math.toDegrees(2.0 * Math.atan2(n.qz, n.qw)) : 0.0;
        // Người dùng KHÔNG đổi góc → giữ nguyên dòng m_LocalRotation, tuyệt đối không ghi lại.
        // Đây là điều kiện sống còn: quaternion trong file có thể mang thông tin mà rotDeg KHÔNG
        // biểu diễn được — lật 180° quanh X/Y (416 transform), nghiêng nhỏ quanh X (Map108 có
        // {x: 0.008726558} = 1°), hay số âm-không "-0". Ghi đè rotDeg lên chúng là mất dữ liệu.
        boolean keepQuat = angDiff(loadedDeg, n.rotDeg) <= 1e-3;

        if (!keepQuat) {
            if (doc.setQuatZ(tb, "m_LocalRotation:", n.rotDeg)) {
                chg(r, fn, n, "m_LocalRotation (+Hint)", PrefabDocument.fmt(loadedDeg) + "°",
                        PrefabDocument.fmt(normAngle(n.rotDeg)) + "°");
                if (quatFlip) {
                    r.info(tag(fn, n) + " quaternion lật 180° bị thay bằng xoay Z"
                            + " → phần lật chuyển sang m_FlipX/m_FlipY");
                } else if (Math.abs(n.qx) > 1e-4 || Math.abs(n.qy) > 1e-4) {
                    r.warn(tag(fn, n) + " quaternion cũ có thành phần xoay quanh X/Y"
                            + " (x=" + PrefabDocument.fmt(n.qx) + ", y=" + PrefabDocument.fmt(n.qy)
                            + ") — đổi góc Z sẽ XOÁ phần nghiêng này");
                }
                // đồng bộ quaternion trong bộ nhớ để lần apply sau không ghi lại nữa
                double half = Math.toRadians(normAngle(n.rotDeg)) / 2.0;
                n.qx = 0; n.qy = 0; n.qz = Math.sin(half); n.qw = Math.cos(half);
            } else {
                r.warn(tag(fn, n) + " không có m_LocalRotation → bỏ qua góc xoay");
            }
        }

        // ── lật: m_FlipX/m_FlipY chỉ tồn tại trên SpriteRenderer ──
        // keepQuat  → quaternion vẫn gánh phần lật ⇒ trả ngược XOR của loader.
        // !keepQuat → quaternion đã thành xoay Z thuần ⇒ m_Flip* gánh toàn bộ.
        boolean fileFlipX = keepQuat ? (n.flipX ^ quatFlipX) : n.flipX;
        boolean fileFlipY = keepQuat ? (n.flipY ^ quatFlipY) : n.flipY;

        PrefabDocument.Block rb = (n.rendAnchor != 0) ? doc.block(n.rendAnchor) : null;
        if (rb != null && n.isSprite) {
            writeBool(doc, rb, "m_FlipX:", fileFlipX, fn, n, r);
            writeBool(doc, rb, "m_FlipY:", fileFlipY, fn, n, r);
        } else if (!keepQuat && quatFlip) {
            r.warn(tag(fn, n) + " không phải SpriteRenderer nhưng quaternion lật đã bị ghi đè"
                    + " → hình có thể bị lật ngược, hãy kiểm tra lại");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // dSorting → renderer (trước/sau player + thứ tự lớp)
    // ─────────────────────────────────────────────────────────────────────

    private static void applySorting(PrefabDocument doc, SortingLayers sl, String fn,
                                     MapScene.Node n, Result r) {
        PrefabDocument.Block rb = (n.rendAnchor != 0) ? doc.block(n.rendAnchor) : null;
        if (rb == null) {
            r.error(n, tag(fn, n) + " không có renderer → không đổi được sorting");
            return;
        }
        SortingLayers.Layer lay = sl.byIndex(n.sortLayerIdx);
        if (lay == null) {
            r.error(n, tag(fn, n) + " sorting layer index " + n.sortLayerIdx
                    + " không hợp lệ (bảng có " + sl.all().size() + " layer) → bỏ qua node này");
            return;
        }
        int wantId = SortingLayers.toSignedId(lay.uniqueId());

        int curId  = doc.getInt(rb, "m_SortingLayerID:", 0);
        int curIdx = doc.getInt(rb, "m_SortingLayer:", 0);
        int curOrd = doc.getInt(rb, "m_SortingOrder:", 0);

        if (curId != wantId) {
            if (doc.setScalar(rb, "m_SortingLayerID:", String.valueOf(wantId))) {
                chg(r, fn, n, "m_SortingLayerID", String.valueOf(curId), String.valueOf(wantId));
            } else {
                r.warn(tag(fn, n) + " không có m_SortingLayerID");
            }
        }
        if (curIdx != n.sortLayerIdx) {
            if (doc.setScalar(rb, "m_SortingLayer:", String.valueOf(n.sortLayerIdx))) {
                SortingLayers.Layer old = sl.byIndex(curIdx);
                chg(r, fn, n, "m_SortingLayer",
                        curIdx + " (" + (old != null ? old.name() : "?") + ")",
                        n.sortLayerIdx + " (" + lay.name() + ", " + viTri(n.sortLayerIdx, sl.playerIndex()) + ")");
            } else {
                r.warn(tag(fn, n) + " không có m_SortingLayer");
            }
        }
        if (curOrd != n.sortOrder) {
            if (doc.setScalar(rb, "m_SortingOrder:", String.valueOf(n.sortOrder))) {
                chg(r, fn, n, "m_SortingOrder", String.valueOf(curOrd), String.valueOf(n.sortOrder));
            } else {
                r.warn(tag(fn, n) + " không có m_SortingOrder");
            }
        }
        n.sortLayerId = wantId;   // đồng bộ bộ nhớ với file
    }

    // ─────────────────────────────────────────────────────────────────────
    // dActive / dRendEnabled
    // ─────────────────────────────────────────────────────────────────────

    private static void applyActive(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        PrefabDocument.Block gb = doc.block(n.goAnchor);
        if (gb == null) {
            r.error(n, tag(fn, n) + " không tìm thấy block GameObject &" + n.goAnchor);
            return;
        }
        writeBool(doc, gb, "m_IsActive:", n.active, fn, n, r);
    }

    private static void applyRendEnabled(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        // CẢNH BÁO: m_Enabled có trong RẤT nhiều loại block (collider, effector, MonoBehaviour…)
        // → chỉ được tìm trong PHẠM VI block renderer, đúng anchor.
        PrefabDocument.Block rb = (n.rendAnchor != 0) ? doc.block(n.rendAnchor) : null;
        if (rb == null) {
            r.error(n, tag(fn, n) + " không có renderer → không đổi được m_Enabled");
            return;
        }
        writeBool(doc, rb, "m_Enabled:", n.rendEnabled, fn, n, r);
    }

    // ─────────────────────────────────────────────────────────────────────
    // dPoints → collider (đường kẻ player đứng / vùng box)
    // ─────────────────────────────────────────────────────────────────────

    private static void applyCollider(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        if (n.colKind == MapScene.ColKind.NONE || n.colAnchor == 0) {
            r.warn(tag(fn, n) + " không có collider → bỏ qua m_Points");
            return;
        }
        PrefabDocument.Block cb = doc.block(n.colAnchor);
        if (cb == null) {
            r.error(n, tag(fn, n) + " không tìm thấy block collider &" + n.colAnchor);
            return;
        }

        // m_Offset dùng chung cho cả EDGE lẫn BOX.
        double[] off = doc.getVec(cb, "m_Offset:");
        if (off != null && (!sameF(off[0], n.offX) || !sameF(off[1], n.offY))
                && doc.setVec2(cb, "m_Offset:", n.offX, n.offY)) {
            chg(r, fn, n, "m_Offset", xy(off[0], off[1]), xy(n.offX, n.offY));
        }

        if (n.colKind == MapScene.ColKind.BOX) {
            double[] sz = doc.getVec(cb, "m_Size:");
            if (sz == null) {
                r.warn(tag(fn, n) + " BoxCollider2D không có m_Size");
            } else if (!sameF(sz[0], n.boxW) || !sameF(sz[1], n.boxH)) {
                if (doc.setVec2(cb, "m_Size:", n.boxW, n.boxH)) {
                    chg(r, fn, n, "m_Size", xy(sz[0], sz[1]), xy(n.boxW, n.boxH));
                }
            }
            return;
        }

        // EDGE — Unity yêu cầu TỐI THIỂU 2 điểm, dưới ngưỡng là hỏng collider.
        if (n.pts == null || n.pts.length < 2) {
            r.error(n, tag(fn, n) + " EdgeCollider2D chỉ còn "
                    + (n.pts == null ? 0 : n.pts.length) + " điểm (Unity cần ≥ 2) → KHÔNG ghi");
            return;
        }
        double[][] cur = doc.getPointList(cb, "m_Points:");
        if (samePts(cur, n.pts)) return;
        if (doc.setPointList(cb, "m_Points:", n.pts)) {
            chg(r, fn, n, "m_Points", cur.length + " điểm", n.pts.length + " điểm");
        } else {
            r.warn(tag(fn, n) + " không có m_Points trong block collider");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // dTexture → sprite / material
    // ─────────────────────────────────────────────────────────────────────

    private static void applyTexture(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        PrefabDocument.Block rb = (n.rendAnchor != 0) ? doc.block(n.rendAnchor) : null;
        if (rb == null) {
            r.error(n, tag(fn, n) + " không có renderer → không đổi được ảnh");
            return;
        }
        if (n.isSprite) {
            applySprite(doc, rb, fn, n, r);
        } else {
            applyMaterial(doc, rb, fn, n, r);
        }
    }

    /** SpriteRenderer: ghi lại nguyên dòng {@code m_Sprite: {fileID: …, guid: …, type: 3}}. */
    private static void applySprite(PrefabDocument doc, PrefabDocument.Block rb, String fn,
                                    MapScene.Node n, Result r) {
        if (doc.findLine(rb, "m_Sprite:") < 0) {
            r.warn(tag(fn, n) + " block renderer không có m_Sprite");
            return;
        }
        String curGuid = doc.getGuid(rb, "m_Sprite:");
        long curId = doc.getFileId(rb, "m_Sprite:");

        if (n.texGuid == null || n.texGuid.isEmpty()) {          // gỡ sprite
            if (curGuid == null && curId == 0) return;
            if (doc.setScalar(rb, "m_Sprite:", "{fileID: 0}")) {
                chg(r, fn, n, "m_Sprite", shortGuid(curGuid), "(rỗng)");
            }
            return;
        }
        // Giữ nguyên internalID của sub-sprite nếu node đang trỏ tới sub-sprite; ngược lại
        // dùng 21300000 (sprite đơn) — đúng 99,2 % trường hợp trong corpus.
        long wantId = (n.spriteFileId != 0) ? n.spriteFileId : SPRITE_SINGLE;
        if (n.texGuid.equalsIgnoreCase(curGuid) && wantId == curId) return;

        String val = "{fileID: " + wantId + ", guid: " + n.texGuid + ", type: " + TYPE_SPRITE + "}";
        if (doc.setScalar(rb, "m_Sprite:", val)) {
            chg(r, fn, n, "m_Sprite", shortGuid(curGuid), shortGuid(n.texGuid));
            n.spriteFileId = wantId;
        }
    }

    /**
     * MeshRenderer (lớp BG parallax): đổi guid của MATERIAL đầu tiên trong m_Materials.
     * KHÔNG bao giờ sửa file .mat — 86/141 material dùng chung nhiều map (tài liệu 01 §5).
     */
    private static void applyMaterial(PrefabDocument doc, PrefabDocument.Block rb, String fn,
                                      MapScene.Node n, Result r) {
        if (n.texGuid == null || n.texGuid.isEmpty()) {
            r.warn(tag(fn, n) + " material guid rỗng → bỏ qua");
            return;
        }
        int at = doc.findLine(rb, "m_Materials:");
        if (at < 0) {
            r.warn(tag(fn, n) + " block MeshRenderer không có m_Materials");
            return;
        }
        List<String> ls = doc.lines();
        int end = Math.min(rb.end, ls.size());
        for (int i = at + 1; i < end; i++) {
            String l = ls.get(i);
            if (!l.startsWith("  - ")) break;                    // hết list
            String cur = firstGuid(l);
            if (cur == null) continue;                           // item không có guid → thử item sau
            if (n.texGuid.equalsIgnoreCase(cur)) return;         // đã đúng
            if (doc.setGuidInLine(i, n.texGuid)) {
                chg(r, fn, n, "m_Materials[0]", shortGuid(cur), shortGuid(n.texGuid));
            }
            return;
        }
        r.warn(tag(fn, n) + " m_Materials rỗng hoặc không có item nào mang guid");
    }

    // ─────────────────────────────────────────────────────────────────────
    // dFx → tham số hiệu ứng (Spine / Animator / WaterWaveMover / FishSwim / WaveWash / Water2D)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Ghi ngược {@code Fx.params} vào ĐÚNG block hiệu ứng của node.
     *
     * <p>Đi qua TẤT CẢ {@link MapScene.Fx} của node (13 node cá vừa là Spine vừa là FishSwim →
     * {@code timeScale} nằm ở block SkeletonAnimation còn {@code _swimSpeed} ở block FishSwim).
     *
     * <p><b>Chỉ ghi field THỰC SỰ đổi</b>: so giá trị trong bộ nhớ với giá trị ĐANG CÓ TRONG FILE
     * (số so ở độ chính xác float nên {@code 2} ≡ {@code 2.0}, Vector2 so từng thành phần).
     * Nhờ vậy {@code apply()} gọi lại nhiều lần cũng không ghi thêm dòng nào —
     * đúng nguyên tắc "1 field đổi = 1 dòng đổi".
     *
     * <p>Vector2 ({@code direction}, {@code speed}, {@code physicsPositionInheritanceFactor}) ghi
     * bằng {@link PrefabDocument#setVec2} để giữ nguyên z/w; mọi kiểu khác (số, bool 0/1, chuỗi,
     * {@code {fileID: …}}, {@code {fileID: …, guid: …, type: …}}) ghi thẳng bằng
     * {@link PrefabDocument#setScalar}.
     */
    private static void applyEffect(PrefabDocument doc, String fn, MapScene.Node n, Result r) {
        if (n.fxList.isEmpty()) {
            r.error(n, tag(fn, n) + " không có component hiệu ứng nào → không ghi được tham số");
            return;
        }
        for (MapScene.Fx fx : n.fxList) {
            if (fx.anchor == 0 || fx.params.isEmpty()) continue;
            PrefabDocument.Block b = doc.block(fx.anchor);
            if (b == null || b.start < 0) {
                r.error(n, tag(fn, n) + " không tìm thấy block hiệu ứng " + fx.kind + " &" + fx.anchor);
                continue;
            }
            for (java.util.Map.Entry<String, String> e : fx.params.entrySet()) {
                String key = e.getKey() + ":";
                String want = (e.getValue() == null) ? "" : e.getValue().trim();
                String cur = doc.getScalar(b, key);
                if (cur == null) {
                    r.warn(tag(fn, n) + " block " + fx.kind + " không có field " + e.getKey()
                            + " → bỏ qua (không tự thêm dòng mới vào prefab)");
                    continue;
                }
                cur = cur.trim();
                if (sameFxValue(cur, want)) continue;

                double[] vw = vec2Of(want), vc = vec2Of(cur);
                boolean written = (vw != null && vc != null)
                        ? doc.setVec2(b, key, vw[0], vw[1])
                        : doc.setScalar(b, key, want);
                if (written) {
                    chg(r, fn, n, fx.kind + "." + e.getKey(), show(cur), show(want));
                } else {
                    r.warn(tag(fn, n) + " ghi field " + e.getKey() + " của " + fx.kind + " thất bại");
                }
            }
        }
    }

    /** Vector2 inline {@code {x: …, y: …}} trong chuỗi (null nếu không phải). */
    private static double[] vec2Of(String v) {
        if (v == null || !v.startsWith("{")) return null;    // phải là map inline {x: …, y: …}
        Matcher m = XY.matcher(v);
        if (!m.find()) return null;
        try {
            return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 2 giá trị field hiệu ứng có COI NHƯ BẰNG NHAU không: giống hệt chuỗi, hoặc cùng là Vector2
     * bằng nhau, hoặc cùng là số bằng nhau ở độ chính xác float ({@code "2"} ≡ {@code "2.0"}).
     */
    private static boolean sameFxValue(String cur, String want) {
        String a = (cur == null) ? "" : cur.trim();
        String b = (want == null) ? "" : want.trim();
        if (a.equals(b)) return true;
        double[] va = vec2Of(a), vb = vec2Of(b);
        if (va != null && vb != null) return sameF(va[0], vb[0]) && sameF(va[1], vb[1]);
        Double da = num(a), db = num(b);
        return da != null && db != null && sameF(da, db);
    }

    private static Double num(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private static String show(String v) {
        return (v == null || v.isEmpty()) ? "(rỗng)" : v;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tiện ích
    // ─────────────────────────────────────────────────────────────────────

    /** Ghi field 0/1 trong ĐÚNG block truyền vào; chỉ ghi khi khác giá trị hiện có. */
    private static void writeBool(PrefabDocument doc, PrefabDocument.Block b, String key,
                                  boolean want, String fn, MapScene.Node n, Result r) {
        if (doc.findLine(b, key) < 0) {
            r.warn(tag(fn, n) + " không có " + key.replace(":", "") + " trong block " + b.type);
            return;
        }
        boolean cur = doc.getInt(b, key, want ? 1 : 0) != 0;
        if (cur == want) return;
        if (doc.setScalar(b, key, want ? "1" : "0")) {
            chg(r, fn, n, key.replace(":", ""), cur ? "1" : "0", want ? "1" : "0");
        }
    }

    private static void chg(Result r, String fn, MapScene.Node n, String field, String from, String to) {
        r.log.add(tag(fn, n) + " " + field + " " + from + " → " + to);
        r.fieldsWritten++;
    }

    private static String tag(String fn, MapScene.Node n) {
        return fn + ": node '" + (n.name != null ? n.name : "?") + "'";
    }

    private static String fileName(MapScene scene) {
        Path p = scene.prefab();
        return (p != null && p.getFileName() != null) ? p.getFileName().toString() : "prefab";
    }

    private static String xy(double x, double y) {
        return "(" + PrefabDocument.fmt(x) + ", " + PrefabDocument.fmt(y) + ")";
    }

    private static String shortGuid(String g) {
        if (g == null || g.isEmpty()) return "(rỗng)";
        return g.length() <= 8 ? g : g.substring(0, 8) + "…";
    }

    /** guid 32 hex đầu tiên trong dòng (null nếu không có). */
    private static String firstGuid(String line) {
        int at = line.indexOf("guid:");
        if (at < 0) return null;
        int i = at + 5;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        int j = i;
        while (j < line.length() && isHex(line.charAt(j))) j++;
        return (j - i == 32) ? line.substring(i, j) : null;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** So sánh ở độ chính xác FLOAT — đúng bằng độ chính xác Unity ghi ra file. */
    private static boolean sameF(double a, double b) {
        return (float) a == (float) b;
    }

    private static boolean samePts(double[][] a, double[][] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null || b[i] == null || a[i].length < 2 || b[i].length < 2) return false;
            if (!sameF(a[i][0], b[i][0]) || !sameF(a[i][1], b[i][1])) return false;
        }
        return true;
    }

    /** Chênh lệch 2 góc (độ) có tính vòng 360° — luôn ≥ 0 và ≤ 180. */
    private static double angDiff(double a, double b) {
        double d = Math.abs(normAngle(a) - normAngle(b)) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    /** Đưa góc về (−180, 180] cho khớp cách PrefabDocument.setQuatZ chuẩn hoá. */
    private static double normAngle(double deg) {
        double a = deg % 360.0;
        if (a > 180.0) a -= 360.0;
        if (a <= -180.0) a += 360.0;
        return a;
    }

    /** Nhãn "trước / ngang / sau player" cho log sorting. */
    private static String viTri(int idx, int playerIdx) {
        if (idx > playerIdx) return "TRƯỚC player";
        if (idx == playerIdx) return "NGANG player";
        return "sau player";
    }
}
