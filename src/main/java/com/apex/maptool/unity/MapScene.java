package com.apex.maptool.unity;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MapScene — scene của 1 map prefab được nạp vào bộ nhớ (MUTABLE) + cờ dirty để ghi ngược.
 *
 * <p>Mỗi {@link Node} = 1 GameObject có Transform trong prefab, giữ đủ anchor (fileID) của
 * GameObject / Transform / Renderer / Collider nên {@code MapSceneWriter} patch thẳng được
 * đúng dòng trong {@link PrefabDocument} mà không cần re-serialize YAML.
 *
 * <p>TOÁN TRANSFORM: chép nguyên từ {@code PrefabParser} (worldMatrix / localMatrix / mul /
 * applyPoint) để Map Layout vẽ TRÙNG KHÍT với Map Editor cũ. Khác biệt duy nhất: node lưu
 * {@code rotDeg} (góc Z, độ) thay cho quaternion — loader đã quy đổi sẵn, và trường hợp
 * quay 180° quanh X/Y (Unity dùng làm "lật") thì loader đặt {@code rotDeg = 0} rồi bật
 * {@code flipX}/{@code flipY}, y hệt PrefabParser dòng 242–246.
 *
 * <p>Hệ toạ độ: world = unity unit, y hướng LÊN. Ma trận affine 2×3 lưu dạng
 * {@code [a, b, c, d, e, f]} với {@code point → (a*x + c*y + e, b*x + d*y + f)}.
 */
public final class MapScene {

    /** Loại collider gắn trên node. */
    public enum ColKind { NONE, EDGE, BOX }

    /**
     * Loại đường kẻ khi TẠO MỚI:
     * <ul>
     *   <li>{@code GROUND} — đường đất player đứng: {@code m_Layer: 6} + EdgeCollider2D.</li>
     *   <li>{@code ONEWAY} — sàn nhảy xuyên từ dưới lên: {@code m_Layer: 19} + EdgeCollider2D
     *       ({@code m_UsedByEffector: 1}) + PlatformEffector2D (!u!251).</li>
     * </ul>
     */
    public enum LineKind { GROUND, ONEWAY }

    /**
     * KIỂU CHẶN của một đường kẻ — quyết định player đi xuyên qua được theo chiều nào.
     *
     * <p>Client đọc thuần vật lý Unity: {@code m_Layer} quyết định đường có nằm trong
     * {@code OnewayLayer} hay không ({@code ActorHandler.CheckCollisions}), còn
     * {@code PlatformEffector2D} quyết định va chạm theo hướng nào bị bỏ. Riêng "bấm Xuống để rơi
     * xuyên" ({@code ActorHandler.HandleDropOneway}) CHỈ chạy khi player đang đứng trên
     * <b>layer 19</b>.
     */
    public enum LineBlock {
        /** Đặc: chặn cả trên lẫn dưới — đường đất thường (layer 6, KHÔNG có PlatformEffector2D). */
        SOLID("Đặc — chặn cả 2 chiều", LAYER_GROUND, 0, 90),
        /** Nhảy XUYÊN từ dưới lên, đứng được ở trên, bấm Xuống để rơi (layer 19, xoay 0°). */
        ONEWAY_UP("Nhảy xuyên từ DƯỚI lên", LAYER_ONEWAY, 0, 90),
        /** Chặn đi LÊN, từ trên đi xuống thì lọt qua (layer 19, xoay 180°). */
        ONEWAY_DOWN("Chặn đi LÊN, xuống thì lọt", LAYER_ONEWAY, 180, 90),
        /** Không chặn chiều nào — chỉ để đánh dấu (layer 19, cung mặt chặn 0°). */
        PASS("Không chặn — xuyên cả 2", LAYER_ONEWAY, 0, 0);

        public final String label;
        public final int layer;
        public final double rot;
        public final double arc;

        LineBlock(String label, int layer, double rot, double arc) {
            this.label = label;
            this.layer = layer;
            this.rot = rot;
            this.arc = arc;
        }

        /** Có cần block PlatformEffector2D không. */
        public boolean needsEffector() { return this != SOLID; }
    }

    /**
     * Loại HIỆU ỨNG gắn trên node (khảo sát thật 156 prefab — tài liệu 06 §A.2):
     * <ul>
     *   <li>{@code SPINE} — {@code Spine.Unity.SkeletonAnimation} (113 cái): cây lắc lư, sóng,
     *       gió, cá, khói… Node kiểu này có MeshRenderer nhưng MeshFilter {@code m_Mesh = 0}.</li>
     *   <li>{@code ANIMATOR} — {@code !u!95} (16 cái): thác nước / bọt nước, đổi frame sprite.</li>
     *   <li>{@code WATER_WAVE} — {@code WaterWaveMover.cs} (163 cái): dòng nước chảy.</li>
     *   <li>{@code FISH_SWIM} — {@code FishSwim.cs} (15 cái): cá / cua / hải âu bơi.</li>
     *   <li>{@code WAVE_WASH} — {@code WaveWash.cs} (4 cái): sóng vỗ bờ.</li>
     *   <li>{@code WATER2D} — {@code Water2DScript.cs} (1 cái): cuộn UV mặt nước.</li>
     *   <li>{@code OTHER} — MonoBehaviour KHÁC (EdgeOutSide, Tutorial…): không vẽ được, chỉ để
     *       UI báo "node này còn script X" cho người dùng biết mà không xoá nhầm.</li>
     * </ul>
     */
    public enum EffectKind {
        NONE, SPINE, ANIMATOR, WATER_WAVE, FISH_SWIM, WAVE_WASH, WATER2D, OTHER;

        /** Hiệu ứng THẬT (vẽ / chạy được) — loại NONE và OTHER. */
        public boolean real() { return this != NONE && this != OTHER; }

        /** Hiệu ứng chỉ làm DỊCH CHUYỂN node (canvas cộng offset khi vẽ, không đụng px/py). */
        public boolean motion() {
            return this == WATER_WAVE || this == FISH_SWIM || this == WAVE_WASH || this == WATER2D;
        }

        /** Nhãn tiếng Việt để hiện lên UI. */
        public String label() {
            return switch (this) {
                case SPINE      -> "Spine (cây lắc lư / sóng / cá)";
                case ANIMATOR   -> "Animator (đổi frame ảnh)";
                case WATER_WAVE -> "Dòng nước chảy (WaterWaveMover)";
                case FISH_SWIM  -> "Cá bơi (FishSwim)";
                case WAVE_WASH  -> "Sóng vỗ bờ (WaveWash)";
                case WATER2D    -> "Cuộn mặt nước (Water2DScript)";
                case OTHER      -> "Script khác";
                default         -> "—";
            };
        }
    }

    /**
     * 1 COMPONENT hiệu ứng gắn trên node — 1 block {@code !u!114 MonoBehaviour} hoặc
     * {@code !u!95 Animator}.
     *
     * <p>Một GameObject có thể mang NHIỀU hiệu ứng cùng lúc (đo thật: 13 node vừa Spine vừa
     * FishSwim — toàn bộ cá/hải âu; 1 node Spine + Animator ở Map13; 1 node Spine + WaveWash ở
     * Map8001), nên node giữ cả {@link Node#fxList}. Các field phẳng {@code fx/fxAnchor/…}
     * của Node trỏ tới hiệu ứng CHÍNH (xem {@link #fxRank}).
     */
    public static final class Fx {
        /** Loại hiệu ứng. */
        public EffectKind kind = EffectKind.NONE;
        /** anchor block !u!114 (hoặc !u!95 với Animator) — writer patch đúng dòng ở đây. */
        public long anchor;
        /** guid của {@code m_Script} (null với Animator). */
        public String scriptGuid;
        /** SPINE: guid {@code skeletonDataAsset} · ANIMATOR: guid {@code m_Controller}. */
        public String assetGuid;
        /** Tên hiển thị: tên skeleton / tên .controller / tên lớp C#. */
        public String name;
        /** SPINE: THƯ MỤC chứa .json + .atlas.txt (đưa thẳng cho SpineCharacter.load) ·
         *  ANIMATOR: đường dẫn FILE .controller · còn lại: null. */
        public Path folder;
        /** Tên field YAML → giá trị THÔ (giữ nguyên thứ tự trong file). */
        public final LinkedHashMap<String, String> params = new LinkedHashMap<>();

        @Override public String toString() {
            return kind + (name != null ? "(" + name + ")" : "") + " &" + anchor;
        }
    }

    /** Thứ tự ưu tiên chọn hiệu ứng CHÍNH: vẽ được (Spine) &gt; chuyển động &gt; Animator &gt; khác. */
    private static int fxRank(EffectKind k) {
        if (k == EffectKind.SPINE) return 0;
        if (k != null && k.motion()) return 1;
        if (k == EffectKind.ANIMATOR) return 2;
        if (k == EffectKind.OTHER) return 3;
        return 9;
    }

    /** physics layer "Ground" trong ProjectSettings/TagManager.asset của client. */
    public static final int LAYER_GROUND = 6;
    /** physics layer "Oneway". */
    public static final int LAYER_ONEWAY = 19;

    // Vị trí phần tử trong ma trận affine 2×3 — đặt tên cho dễ đọc.
    private static final double[] IDENTITY = {1, 0, 0, 1, 0, 0};

    /** Guard chống vòng lặp cha–con vô hạn (prefab hỏng). Giống PrefabParser. */
    private static final int CHAIN_GUARD = 256;

    // ─────────────────────────────────────────────────────────────────────
    // Node
    // ─────────────────────────────────────────────────────────────────────

    /** 1 GameObject (có Transform) trong prefab + renderer/collider của nó. */
    public static final class Node {

        // ── anchor các block trong file (0 = không có) ──
        public long goAnchor;      // !u!1  GameObject
        public long trAnchor;      // !u!4  Transform (hoặc !u!224 RectTransform)
        public long rendAnchor;    // !u!212 SpriteRenderer hoặc !u!23 MeshRenderer
        /** !u!33 MeshFilter — chỉ node Spine mới cần (spine-unity đổ lưới vào đây lúc chạy). */
        public long mfAnchor;
        public long colAnchor;     // !u!68 EdgeCollider2D hoặc !u!61 BoxCollider2D
        public long effAnchor;     // !u!251 PlatformEffector2D (0 = không có)
        public long parentTr;      // m_Father → anchor Transform cha (0 = root)

        // ── GameObject ──
        /** Tên hiển thị: m_Name, rỗng → "(không tên)". */
        public String name;
        /** m_Name nguyên bản (có thể rỗng) — dùng khi cần ghi lại/so khớp. */
        public String rawName;
        public boolean active = true;   // m_IsActive
        public int physLayer;           // m_Layer (0 Default, 6 Ground, 14 Wall, 17 ColorGround, 19 Oneway…)
        /**
         * m_Layer LÚC NẠP — mốc để trả về đúng layer gốc khi tắt oneway. Không có nó thì đường
         * biên map (Wall 14) bật rồi tắt oneway sẽ tụt xuống Ground 6 mà chẳng ai báo.
         */
        public int origPhysLayer = -1;
        public String tag = "Untagged"; // m_TagString

        // ── Transform LOCAL ──
        public double px, py;           // m_LocalPosition x/y
        public double sx = 1, sy = 1;   // m_LocalScale x/y
        public double rotDeg;           // góc Z (độ, CCW) — đã bỏ trường hợp flip 180° quanh X/Y
        /** Quaternion gốc (giữ nguyên để writer không phá dữ liệu khi chỉ đổi vị trí). */
        public double qx, qy, qz, qw = 1;
        /** Thứ tự con theo m_Children (anchor Transform) — dùng dựng cây đúng thứ tự Unity. */
        public long[] childTr;

        // ── Renderer ──
        public boolean hasRenderer;
        public boolean isSprite;            // true = SpriteRenderer(212), false = MeshRenderer(23)
        public boolean rendEnabled = true;  // m_Enabled
        public boolean flipX, flipY;        // m_FlipX/m_FlipY (đã gộp flip suy từ quaternion)
        /** guid sprite (SpriteRenderer) hoặc guid material (MeshRenderer). */
        public String texGuid;
        /** Đường dẫn .png đã resolve (null = không có/không tìm thấy). */
        public Path texture;
        public int sortLayerIdx;            // m_SortingLayer (index 0..20)
        public int sortOrder;               // m_SortingOrder
        public long sortLayerId;            // m_SortingLayerID (int32 CÓ DẤU, lưu trong long)
        public double pivotX = 0.5, pivotY = 0.5;   // pivot sprite (0..1)
        public double baseW, baseH;         // kích thước LOCAL (unity unit) chưa nhân scale

        // ── Renderer: thông tin phụ để vẽ đúng (không nằm trong hợp đồng tối thiểu) ──
        /** MeshRenderer có MeshFilter m_Mesh = 10210 (quad 1×1) → mới được vẽ. Spine thì false. */
        public boolean isQuadMesh;
        /** true = có tham chiếu texture nhưng KHÔNG resolve được → canvas vẽ khung đỏ. */
        public boolean texMissing;
        /** m_DrawMode: 0 Simple (dùng size PNG), 1 Sliced, 2 Tiled (dùng m_Size). */
        public int drawMode;
        /** alpha của m_Color (141 sprite "ColorGround" có a = 0 → không vẽ đè). */
        public double alpha = 1;
        /** m_Sprite.fileID: 21300000 = sprite đơn, khác = sub-sprite trong sprite sheet. */
        public long spriteFileId;
        /** Vùng cắt sub-sprite trong PNG theo toạ độ ẢNH JAVA {x, y, w, h} (null = cả tấm). */
        public int[] subRect;

        // ── Collider ──
        public ColKind colKind = ColKind.NONE;
        /** m_Points của EdgeCollider2D — toạ độ LOCAL của chính GameObject (chưa cộng offset). */
        public double[][] pts;
        public double offX, offY;       // m_Offset
        public double boxW, boxH;       // m_Size của BoxCollider2D
        public boolean isTrigger;
        public boolean colEnabled = true;
        /** Có PlatformEffector2D (!u!251) → đây là đường "oneway" (nhảy xuyên từ dưới lên). */
        public boolean hasPlatformEffector;
        /**
         * {@code m_RotationalOffset} của PlatformEffector2D: xoay CUNG MẶT CHẶN.
         * 0 = mặt chặn hướng LÊN (đứng được ở trên, nhảy từ dưới lên thì xuyên qua);
         * 180 = mặt chặn hướng XUỐNG (chặn đi lên, từ trên xuống thì lọt).
         */
        public double effRotOffset;
        /**
         * {@code m_SurfaceArc} của PlatformEffector2D: bề rộng cung mặt chặn (độ).
         * 90 = mặc định Unity; 0 = KHÔNG chặn hướng nào (đi xuyên cả 2 chiều).
         */
        public double effSurfaceArc = 90;

        // ── HIỆU ỨNG ──
        // Mọi component hiệu ứng của node (0..n). Các field phẳng bên dưới là bản sao "tiện tay"
        // của phần tử CHÍNH trong list này (rank nhỏ nhất) — dùng cho UI/canvas khỏi phải duyệt.
        public final List<Fx> fxList = new ArrayList<>();

        /** Loại hiệu ứng CHÍNH (NONE = node không có hiệu ứng nào). */
        public EffectKind fx = EffectKind.NONE;
        /** anchor block của hiệu ứng CHÍNH (0 = không có). */
        public long fxAnchor;
        /** guid {@code m_Script} của hiệu ứng chính. */
        public String fxScriptGuid;
        /** guid asset của hiệu ứng chính (skeletonDataAsset / m_Controller). */
        public String fxAssetGuid;
        /** Tên hiển thị của hiệu ứng chính. */
        public String fxName;
        /**
         * Tham số của hiệu ứng CHÍNH: tên field YAML → giá trị thô, GIỮ ĐÚNG THỨ TỰ trong file.
         * CÙNG object với {@code fxList.get(chính).params} nên sửa bên nào cũng như nhau.
         * Node không có hiệu ứng ⇒ map RỖNG (không bao giờ null, tránh NPE bên UI).
         */
        public LinkedHashMap<String, String> fxParams = new LinkedHashMap<>();
        /** SPINE: thư mục .json/.atlas.txt · ANIMATOR: file .controller · còn lại null. */
        public Path fxFolder;

        /** Có hiệu ứng loại {@code k} không (kể cả khi nó không phải hiệu ứng chính). */
        public boolean hasFx(EffectKind k) {
            for (Fx f : fxList) if (f.kind == k) return true;
            return false;
        }

        /** Component hiệu ứng loại {@code k} (null nếu không có). */
        public Fx fxOf(EffectKind k) {
            for (Fx f : fxList) if (f.kind == k) return f;
            return null;
        }

        /**
         * Loại hiệu ứng CHUYỂN ĐỘNG của node ({@code WATER_WAVE/FISH_SWIM/WAVE_WASH/WATER2D}),
         * {@code NONE} nếu không có. Tách riêng vì 13/15 node FishSwim ĐỒNG THỜI là Spine —
         * canvas phải VẼ bằng Spine nhưng DỊCH CHUYỂN theo FishSwim.
         */
        public EffectKind fxMotion() {
            for (Fx f : fxList) if (f.kind.motion()) return f.kind;
            return EffectKind.NONE;
        }

        /** Danh sách loại hiệu ứng đang có trên node (thứ tự như trong file). */
        public List<EffectKind> fxKinds() {
            List<EffectKind> out = new ArrayList<>(fxList.size());
            for (Fx f : fxList) out.add(f.kind);
            return out;
        }

        // ── dirty flags (writer chỉ patch đúng field được bật) ──
        public boolean dTransform;   // px/py/sx/sy/rotDeg
        public boolean dSorting;     // sortLayerIdx/sortLayerId/sortOrder
        public boolean dActive;      // m_IsActive
        public boolean dPoints;      // m_Points
        public boolean dTexture;     // m_Sprite / m_Materials[0]
        public boolean dRendEnabled; // m_Enabled của renderer
        /** Tham số hiệu ứng ({@link #fxParams} của bất kỳ {@link Fx} nào) đã bị sửa. */
        public boolean dFx;
        /** Node MỚI tạo trong phiên này — writer phải CHÈN block chứ không patch dòng. */
        public boolean dNew;
        /** Đổi skeleton của node Spine — writer ghi {@code skeletonDataAsset} + {@code m_Materials}. */
        public boolean dSkeleton;
        /**
         * Đổi KIỂU CHẶN của đường kẻ — writer ghi {@code m_Layer} (GameObject),
         * {@code m_UsedByEffector} (collider) và CHÈN/GỠ block PlatformEffector2D kèm
         * {@code m_RotationalOffset} / {@code m_SurfaceArc}.
         */
        public boolean dLineBlock;

        /**
         * guid material của skeleton MỚI ({@code *_Material.mat}). Ghi kèm {@link #fxAssetGuid}
         * mỗi khi {@link #dSkeleton} bật — thiếu nó thì node vẽ bằng atlas của skeleton CŨ.
         * null = không tìm thấy material ⇒ writer cảnh báo thay vì ghi bừa.
         */
        public String skelMatGuid;

        public boolean isDirty() {
            return dNew || dTransform || dSorting || dActive || dPoints || dTexture
                    || dRendEnabled || dFx || dSkeleton || dLineBlock;
        }

        /** Xoá mọi cờ dirty (gọi sau khi ghi file thành công). */
        public void clearDirty() {
            dNew = dTransform = dSorting = dActive = dPoints = dTexture = dRendEnabled = false;
            dFx = false;
            dSkeleton = false;
            dLineBlock = false;
        }

        /** Đường player đứng: physics layer 6 (Ground) + EdgeCollider2D không phải trigger. */
        public boolean isGroundLine() {
            return colKind == ColKind.EDGE && !isTrigger && physLayer == 6;
        }

        /** Đường oneway: có PlatformEffector2D (đáng tin hơn xét theo layer — xem doc 03 §6). */
        public boolean isOnewayLine() {
            return colKind == ColKind.EDGE && hasPlatformEffector;
        }

        /**
         * Kiểu chặn hiện tại của đường kẻ, suy từ layer + PlatformEffector2D.
         * Node không phải EdgeCollider2D thì trả {@link LineBlock#SOLID}.
         */
        public LineBlock lineBlock() {
            if (!hasPlatformEffector) return LineBlock.SOLID;
            if (effSurfaceArc <= 0.001) return LineBlock.PASS;
            // Unity xoay cung theo chiều CCW; 180 (±45) = cung quay xuống ⇒ chặn từ dưới lên.
            double a = ((effRotOffset % 360) + 360) % 360;
            return (a > 135 && a < 225) ? LineBlock.ONEWAY_DOWN : LineBlock.ONEWAY_UP;
        }

        @Override public String toString() {
            return name + " (go=" + goAnchor + ")";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────

    private final PrefabDocument doc;
    private final int mapId;
    private final Path prefab;

    private final List<Node> nodes = new ArrayList<>();
    private final Map<Long, Node> byGo = new HashMap<>();
    private final Map<Long, Node> byTr = new HashMap<>();
    private final Map<Long, List<Node>> childMap = new HashMap<>();
    private final List<Node> roots = new ArrayList<>();
    /** Node đã gỡ khỏi {@link #nodes} và ĐANG CHỜ writer xoá block trong file. */
    private final List<Node> deleted = new ArrayList<>();

    // ── thống kê / thông tin phụ do loader điền ──
    private int nestedPrefabCount;   // số !u!1001 PrefabInstance (không hiển thị/không sửa được)
    private int strippedCount;       // số block "stripped"
    private long skyLayerTr;         // MapManager._layerBGSky → anchor Transform
    private final List<Long> bgLayerTrs = new ArrayList<>();   // MapManager._layersBG
    private final List<double[]> bgOffsets = new ArrayList<>(); // MapManager._offsetLayers
    // MapManager._edgeColTop/_edgeColBtm/_edgeColLeft/_edgeColRight → anchor TRANSFORM (0 = không có).
    // Đây là THAM CHIẾU THẬT đọc từ file, KHÔNG đoán theo tên (xem #boundsEdge).
    private long edgeTrTop, edgeTrBtm, edgeTrLeft, edgeTrRight;

    public MapScene(PrefabDocument doc, int mapId) {
        this.doc = doc;
        this.mapId = mapId;
        this.prefab = (doc != null) ? doc.file() : null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Build (loader dùng)
    // ─────────────────────────────────────────────────────────────────────

    /** Thêm node (chỉ loader gọi, trước khi {@link #index()}). */
    public void addNode(Node n) { nodes.add(n); }

    /** Dựng lại bảng tra byGo/byTr + cây cha-con. Gọi sau khi thêm hết node. */
    public void index() {
        byGo.clear(); byTr.clear(); childMap.clear(); roots.clear();
        for (Node n : nodes) {
            if (n.goAnchor != 0) byGo.put(n.goAnchor, n);
            if (n.trAnchor != 0) byTr.put(n.trAnchor, n);
        }
        for (Node n : nodes) {
            Node p = (n.parentTr != 0) ? byTr.get(n.parentTr) : null;
            if (p == null) roots.add(n);          // cha = 0 hoặc cha bị stripped → coi như root
            else childMap.computeIfAbsent(p.trAnchor, k -> new ArrayList<>()).add(n);
        }
        // Sắp con theo đúng thứ tự m_Children của Unity (list ở trên đang theo thứ tự block trong file).
        for (Node p : nodes) {
            List<Node> cs = childMap.get(p.trAnchor);
            if (cs == null || cs.size() < 2 || p.childTr == null || p.childTr.length == 0) continue;
            Map<Long, Integer> order = new HashMap<>();
            for (int i = 0; i < p.childTr.length; i++) order.put(p.childTr[i], i);
            cs.sort((a, b) -> Integer.compare(
                    order.getOrDefault(a.trAnchor, Integer.MAX_VALUE),
                    order.getOrDefault(b.trAnchor, Integer.MAX_VALUE)));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // THÊM / XOÁ đường kẻ (thay đổi CẤU TRÚC — writer sẽ chèn/xoá nguyên block YAML)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Tạo 1 đường kẻ mới (EdgeCollider2D) làm CON của {@code parent}, toạ độ đỉnh theo WORLD.
     *
     * <p>Việc thực hiện:
     * <ul>
     *   <li>Cấp anchor mới cho GameObject / Transform / EdgeCollider2D (+ PlatformEffector2D
     *       nếu {@code ONEWAY}) bằng {@link PrefabDocument#newAnchor()} — chưa đụng vào file.</li>
     *   <li>Đặt gốc Transform tại TRỌNG TÂM các đỉnh (quy về không gian của cha) cho hợp lý,
     *       rồi quy các đỉnh về LOCAL bằng chính {@link #setColliderWorldPoint} đã có test T7
     *       — không tự viết phép quy đổi mới.</li>
     *   <li>Bật {@code dNew} để {@code MapSceneWriter} biết phải CHÈN BLOCK, không patch dòng.</li>
     * </ul>
     *
     * @param parent  node cha (thường là {@code Layer_Collider}); null ⇒ node thành root.
     * @param name    tên GameObject; rỗng ⇒ tự sinh.
     * @param kind    {@link LineKind#GROUND} hoặc {@link LineKind#ONEWAY}.
     * @param worldPts toạ độ world các đỉnh, TỐI THIỂU 2 (yêu cầu của EdgeCollider2D).
     * @return node vừa tạo, hoặc null nếu tham số không hợp lệ / scene không gắn file.
     */
    public Node addEdgeLine(Node parent, String name, LineKind kind, double[][] worldPts) {
        if (doc == null) return null;
        // .prefab BẮT BUỘC đúng 1 root: node không cha sẽ ghi ra m_Father: {fileID: 0} → root thứ 2
        // → Unity không mở được prefab. UI đã không cho chọn "không cha", nhưng chặn cứng ở đây
        // để mọi lối gọi khác (batch/CLI/test) cũng an toàn.
        if (parent == null || parent.trAnchor == 0) return null;
        if (worldPts == null || worldPts.length < 2) return null;   // Unity cần ≥ 2 đỉnh
        for (double[] w : worldPts) if (w == null || w.length < 2) return null;
        LineKind k = (kind != null) ? kind : LineKind.GROUND;
        boolean oneway = (k == LineKind.ONEWAY);

        Node n = new Node();
        n.goAnchor = doc.newAnchor();
        n.trAnchor = doc.newAnchor();
        n.colAnchor = doc.newAnchor();
        n.effAnchor = oneway ? doc.newAnchor() : 0;
        n.parentTr = (parent != null) ? parent.trAnchor : 0;

        String nm = (name == null || name.trim().isEmpty())
                ? (oneway ? "Oneway_moi" : "Ground_moi") : name.trim();
        n.rawName = nm;
        n.name = nm;
        n.active = true;
        n.physLayer = oneway ? LAYER_ONEWAY : LAYER_GROUND;
        n.origPhysLayer = LAYER_GROUND;         // tắt oneway trên đường MỚI → về đường đất
        n.tag = oneway ? "Oneway" : "Untagged";

        n.sx = 1; n.sy = 1; n.rotDeg = 0;
        n.qx = 0; n.qy = 0; n.qz = 0; n.qw = 1;
        n.childTr = new long[0];

        n.hasRenderer = false;      // không có renderer ⇒ canvas vẽ dạng đường + chữ thập
        n.baseW = 0; n.baseH = 0;

        n.colKind = ColKind.EDGE;
        n.colEnabled = true;
        n.isTrigger = false;
        n.offX = 0; n.offY = 0;
        n.hasPlatformEffector = oneway;

        // Gốc transform = trọng tâm các đỉnh, quy về KHÔNG GIAN CỦA CHA (giống cách Unity đặt).
        double[] invP = inverse(parent != null ? worldMatrix(parent) : IDENTITY.clone());
        double cx = 0, cy = 0;
        for (double[] w : worldPts) {
            double[] p = applyPoint(invP, w[0], w[1]);
            cx += p[0]; cy += p[1];
        }
        n.px = cx / worldPts.length;
        n.py = cy / worldPts.length;

        n.pts = new double[worldPts.length][];
        for (int i = 0; i < worldPts.length; i++) n.pts[i] = new double[]{0, 0};

        nodes.add(n);
        if (parent != null) parent.childTr = appendLong(parent.childTr, n.trAnchor);
        index();                                    // để worldMatrix(n) đi đúng chuỗi cha

        for (int i = 0; i < worldPts.length; i++) {
            setColliderWorldPoint(n, i, worldPts[i][0], worldPts[i][1]);
        }
        n.dNew = true;
        n.dTransform = false;    // block mới sẽ được ghi kèm giá trị cuối, khỏi patch lại
        return n;
    }

    /**
     * Đổi KIỂU CHẶN của một đường kẻ (EdgeCollider2D) — cho player đi xuyên theo chiều nào.
     *
     * <p>Chỉ đổi TRONG BỘ NHỚ; {@code MapSceneWriter} ghi vào prefab khi bấm Lưu:
     * {@code m_Layer} của GameObject · {@code m_UsedByEffector} của collider · CHÈN hoặc GỠ block
     * {@code PlatformEffector2D} (!u!251) kèm {@code m_RotationalOffset} / {@code m_SurfaceArc}.
     *
     * <p>Node MỚI ({@code dNew}) thì không bật cờ {@code dLineBlock} — writer dựng block từ đầu
     * theo đúng các field này rồi.
     *
     * @return true nếu có đổi thật (kiểu mới khác kiểu đang có).
     */
    public boolean setLineBlock(Node n, LineBlock b) {
        if (n == null || b == null || n.colKind != ColKind.EDGE) return false;
        if (n.lineBlock() == b) return false;
        // Về ĐẶC: chỉ hạ 19 → 6. Đường đang ở layer LẠ (Wall 14, ColorGround 17… — hay gặp ở 4 thanh
        // biên map) thì GIỮ NGUYÊN, không thì đổi qua đổi lại là mất layer gốc mà không ai báo.
        if (b == LineBlock.SOLID) {
            if (n.physLayer == LAYER_ONEWAY) {
                n.physLayer = (n.origPhysLayer > 0 && n.origPhysLayer != LAYER_ONEWAY)
                        ? n.origPhysLayer : LAYER_GROUND;
            }
        } else {
            n.physLayer = b.layer;
        }
        n.hasPlatformEffector = b.needsEffector();
        n.effRotOffset = b.rot;
        n.effSurfaceArc = b.arc;
        if (n.hasPlatformEffector && n.effAnchor == 0) n.effAnchor = doc.newAnchor();
        if (!n.dNew) n.dLineBlock = true;
        return true;
    }

    /**
     * THÊM một node Spine mới (cây, hiệu ứng…) vào map, làm con của {@code parent}.
     *
     * <p>Node Spine trong prefab gồm <b>5 block</b>: GameObject + Transform + MeshFilter +
     * MeshRenderer + MonoBehaviour(SkeletonAnimation). MeshFilter/MeshRenderer để TRỐNG lưới —
     * spine-unity tự đổ lưới vào lúc chạy; điều bắt buộc là {@code m_Materials[0]} phải trỏ đúng
     * material của skeleton, nếu không node vẽ ra bằng atlas của skeleton khác.
     *
     * <p>{@code worldX/worldY} là toạ độ THẾ GIỚI; hàm tự quy về không gian của cha (giống
     * {@link #addEdgeLine}) nên gọi từ UI chỉ cần đưa chỗ người dùng click.
     *
     * @param scale scale local đặt cho Transform (UI tính sẵn theo chiều cao thật của skeleton)
     * @return node vừa tạo, hoặc null nếu tham số không hợp lệ
     */
    public Node addSpineNode(Node parent, String name, SpineCatalog.Entry skel, String animName,
                             double worldX, double worldY, double scale) {
        if (doc == null || skel == null || skel.skeletonGuid == null) return null;
        // .prefab bắt buộc đúng 1 root — node không cha sẽ thành root thứ 2 làm hỏng file.
        if (parent == null || parent.trAnchor == 0) return null;

        Node n = new Node();
        n.goAnchor = doc.newAnchor();
        n.trAnchor = doc.newAnchor();
        n.mfAnchor = doc.newAnchor();
        n.rendAnchor = doc.newAnchor();
        long fxAnchor = doc.newAnchor();
        n.parentTr = parent.trAnchor;

        String nm = (name == null || name.trim().isEmpty())
                ? ("Spine GameObject (" + skel.name + ")") : name.trim();
        n.rawName = nm;
        n.name = nm;
        n.active = true;
        n.physLayer = 0;                    // Default — node trang trí, không va chạm
        n.tag = "Untagged";

        double s = (scale > 0) ? scale : 1;
        n.sx = s; n.sy = s; n.rotDeg = 0;
        n.qx = 0; n.qy = 0; n.qz = 0; n.qw = 1;
        n.childTr = new long[0];

        n.hasRenderer = true;
        n.isSprite = false;                 // Spine là MeshRenderer
        n.isQuadMesh = false;               // KHÔNG phải quad nền ⇒ canvas vẽ bằng skeleton
        n.baseW = 0; n.baseH = 0;           // hình do skeleton quyết, không có kích thước sprite
        n.colKind = ColKind.NONE;
        // Thừa hưởng lớp vẽ của cha để node mới nằm đúng tầng cảnh, khỏi chui ra trước/sau lung tung
        n.sortLayerIdx = parent.sortLayerIdx;
        n.sortLayerId = parent.sortLayerId;
        n.sortOrder = parent.sortOrder;

        Fx f = new Fx();
        f.kind = EffectKind.SPINE;
        f.anchor = fxAnchor;
        f.assetGuid = skel.skeletonGuid;
        f.name = skel.name;
        f.folder = skel.folder;
        f.params.put("_animationName", (animName == null) ? "" : animName);
        f.params.put("loop", "1");
        f.params.put("timeScale", "1");
        n.fxList.add(f);
        n.fx = EffectKind.SPINE;
        n.fxAnchor = fxAnchor;
        n.fxAssetGuid = skel.skeletonGuid;
        n.fxName = skel.name;
        n.fxFolder = skel.folder;
        n.fxParams = f.params;
        n.skelMatGuid = skel.materialGuid;

        nodes.add(n);
        parent.childTr = appendLong(parent.childTr, n.trAnchor);
        index();                            // để worldMatrix(n) đi đúng chuỗi cha

        // Quy toạ độ thế giới về không gian CỦA CHA (Transform lưu local).
        double[] invP = inverse(worldMatrix(parent));
        double[] p = applyPoint(invP, worldX, worldY);
        n.px = p[0];
        n.py = p[1];

        n.dNew = true;
        n.dTransform = false;               // block mới ghi kèm giá trị cuối, khỏi patch lại
        return n;
    }

    /** Có được phép xoá node này không (xem {@link #whyCannotRemove}). */
    public boolean canRemove(Node n) { return whyCannotRemove(n) == null; }

    /**
     * Lý do KHÔNG được xoá node (null = xoá được).
     *
     * <p>Không đoán theo tên: quét THẬT mọi block trong file có tham chiếu tới bất kỳ anchor nào
     * của node (và con cháu) bằng {@link PrefabDocument#blocksReferencing}. Bỏ qua 2 nguồn
     * tham chiếu hợp lệ mà writer tự xử lý được: block của chính node/con cháu, và
     * {@code m_Children} của Transform CHA.
     *
     * <p>Nhờ vậy các node như biên map {@code Top/Bottom/Left/Right} (bị MapManager giữ qua
     * {@code _edgeColTop/_edgeColBtm/_edgeColLeft/_edgeColRight}), lớp nền {@code _layersBG},
     * {@code _layerBGSky}, {@code _edgeOutside}, hay Transform đang là {@code m_TransformParent}
     * của một PrefabInstance đều tự động bị CẤM XOÁ.
     */
    public String whyCannotRemove(Node n) {
        if (n == null) return "Chưa chọn node nào để xoá.";
        if (doc == null) return "Scene không gắn với file prefab nên không xoá được.";
        if (!nodes.contains(n)) return "Node này không còn trong scene.";
        // Tool chỉ hỗ trợ XOÁ ĐƯỜNG KẺ. Xoá cụm khác (vd Layer_Ground) sẽ cuốn theo cả chục
        // sprite con — muốn ẩn phần nào thì bỏ tick m_IsActive trên cây, đừng xoá.
        if (n.colKind != ColKind.EDGE)
            return "Chỉ xoá được ĐƯỜNG KẺ (EdgeCollider2D). Node '" + n.name
                    + "' không phải đường kẻ — muốn ẩn thì bỏ tick ở cây 'Các phần của map'.";

        List<Node> sub = subtree(n);
        Set<Long> own = new HashSet<>();
        for (Node s : sub) {
            for (long a : anchorsOf(s)) if (a != 0) own.add(a);
            // MỌI component của cùng GameObject cũng bị xoá theo ⇒ tham chiếu từ chúng là hợp lệ.
            // (Bắt buộc: node cũ có thể còn component mà Node không giữ anchor — vd MonoBehaviour
            //  phụ — nên phải quét THẬT theo m_GameObject chứ không chỉ dựa vào anchorsOf.)
            if (s.goAnchor != 0) {
                for (PrefabDocument.Block b : doc.blocksOfGameObject(s.goAnchor)) own.add(b.anchor);
            }
        }
        long parentTr = n.parentTr;      // dòng m_Children của cha — writer tự gỡ khi xoá

        for (Node s : sub) {
            if (s.goAnchor == 0 || doc.block(s.goAnchor) == null) continue;  // chưa có trong file
            for (long a : anchorsOf(s)) {
                if (a == 0) continue;
                for (PrefabDocument.Block b : doc.blocksReferencing(a)) {
                    if (own.contains(b.anchor)) continue;        // block của chính node/con cháu
                    if (b.anchor == parentTr) continue;          // m_Children của cha
                    return "Node '" + s.name + "' đang bị block " + b.type + " (!u!" + b.classId
                            + " &" + b.anchor + ") tham chiếu tới fileID " + a
                            + ". Thường là MapManager giữ biên map (Top/Bottom/Left/Right), lớp nền"
                            + " (_layersBG/_layerBGSky) hoặc nested prefab — xoá sẽ HỎNG MAP.";
                }
            }
        }
        return null;
    }

    /**
     * Xoá node khỏi scene KÈM toàn bộ con cháu, và ghi chúng vào {@link #deletedNodes()} để
     * writer gỡ block trong file. Node chưa từng được ghi ra file (mới tạo trong phiên) chỉ bị
     * bỏ khỏi bộ nhớ, không vào danh sách chờ xoá.
     *
     * @return false nếu node null / không thuộc scene / bị {@link #canRemove} chặn.
     */
    public boolean removeNode(Node n) {
        if (n == null || !nodes.contains(n)) return false;
        if (!canRemove(n)) return false;

        List<Node> sub = subtree(n);       // thứ tự: node gốc trước, rồi con cháu
        Node p = parent(n);
        if (p != null) p.childTr = removeLong(p.childTr, n.trAnchor);
        for (Node s : sub) {
            nodes.remove(s);
            boolean inFile = (doc != null && s.goAnchor != 0 && doc.block(s.goAnchor) != null);
            if (inFile && !deleted.contains(s)) deleted.add(s);
        }
        index();
        return true;
    }

    /**
     * Thay NGUYÊN danh sách node bằng {@code list} rồi {@link #index()} lại — dùng cho UNDO
     * thao tác CẤU TRÚC (thêm/xoá đường kẻ) trong {@code MapLayoutCanvas}.
     *
     * <p>Node là object MUTABLE dùng chung: ảnh chụp undo chỉ giữ THAM CHIẾU nên node bị xoá
     * vẫn "sống" trong ảnh chụp và quay lại đầy đủ mọi field (kể cả anchor) khi hoàn tác.
     * Người gọi tự khôi phục field của từng node (px/py/pts/childTr…) TRƯỚC hoặc SAU rồi
     * gọi {@link #index()} một lần nữa nếu có đụng tới {@code parentTr}/{@code childTr}.
     */
    public void restoreNodes(List<Node> list) {
        nodes.clear();
        if (list != null) for (Node n : list) if (n != null) nodes.add(n);
        index();
    }

    /** Các node MỚI tạo chưa có block trong file (writer phải CHÈN). */
    public List<Node> newNodes() {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) if (n.dNew) out.add(n);
        return out;
    }

    /** Các node đang CHỜ XOÁ khỏi file (writer phải gỡ block). List sống — sửa được. */
    public List<Node> deletedNodes() { return deleted; }

    /** Node n + toàn bộ con cháu (node gốc đứng đầu). */
    private List<Node> subtree(Node n) {
        List<Node> out = new ArrayList<>();
        ArrayDeque<Node> stack = new ArrayDeque<>();
        stack.push(n);
        int guard = 0;
        while (!stack.isEmpty() && guard++ < CHAIN_GUARD * CHAIN_GUARD) {
            Node cur = stack.pop();
            if (cur == null || out.contains(cur)) continue;
            out.add(cur);
            List<Node> cs = children(cur);
            for (int i = cs.size() - 1; i >= 0; i--) stack.push(cs.get(i));
        }
        return out;
    }

    /** Mọi anchor của 1 node (0 = không có). */
    private static long[] anchorsOf(Node n) {
        return new long[]{n.goAnchor, n.trAnchor, n.colAnchor, n.effAnchor, n.rendAnchor};
    }

    private static long[] appendLong(long[] arr, long v) {
        long[] src = (arr != null) ? arr : new long[0];
        long[] out = new long[src.length + 1];
        System.arraycopy(src, 0, out, 0, src.length);
        out[src.length] = v;
        return out;
    }

    private static long[] removeLong(long[] arr, long v) {
        if (arr == null || arr.length == 0) return new long[0];
        long[] tmp = new long[arr.length];
        int k = 0;
        for (long a : arr) if (a != v) tmp[k++] = a;
        long[] out = new long[k];
        System.arraycopy(tmp, 0, out, 0, k);
        return out;
    }

    void setNestedPrefabCount(int v) { nestedPrefabCount = v; }
    void setStrippedCount(int v) { strippedCount = v; }
    void setSkyLayerTr(long v) { skyLayerTr = v; }
    void addBgLayerTr(long v) { bgLayerTrs.add(v); }
    void addBgOffset(double x, double y) { bgOffsets.add(new double[]{x, y}); }

    /** Loader điền anchor Transform của 4 biên map lấy từ block MonoBehaviour MapManager. */
    void setEdgeTr(String which, long trAnchor) {
        if (which == null || trAnchor == 0) return;
        switch (which.trim().toLowerCase(Locale.ROOT)) {
            case "top" -> edgeTrTop = trAnchor;
            case "bottom", "btm", "bot" -> edgeTrBtm = trAnchor;
            case "left" -> edgeTrLeft = trAnchor;
            case "right" -> edgeTrRight = trAnchor;
            default -> { /* khoá lạ → bỏ qua */ }
        }
    }

    /**
     * Gắn 1 component hiệu ứng vào node và cập nhật lại các field phẳng {@code fx*}
     * (loader gọi). Hiệu ứng CHÍNH = rank nhỏ nhất, hoà thì cái gặp TRƯỚC trong file thắng.
     */
    void addFx(Node n, Fx f) {
        if (n == null || f == null || f.kind == null || f.kind == EffectKind.NONE) return;
        n.fxList.add(f);
        Fx best = null;
        for (Fx x : n.fxList) {
            if (best == null || fxRank(x.kind) < fxRank(best.kind)) best = x;
        }
        n.fx = best.kind;
        n.fxAnchor = best.anchor;
        n.fxScriptGuid = best.scriptGuid;
        n.fxAssetGuid = best.assetGuid;
        n.fxName = best.name;
        n.fxFolder = best.folder;
        n.fxParams = best.params;          // CÙNG object → sửa bên nào cũng như nhau
    }

    // ─────────────────────────────────────────────────────────────────────
    // Truy vấn
    // ─────────────────────────────────────────────────────────────────────

    public List<Node> nodes() { return nodes; }
    public Node byGo(long goAnchor) { return byGo.get(goAnchor); }
    public Node byTr(long trAnchor) { return byTr.get(trAnchor); }
    public List<Node> roots() { return roots; }

    public List<Node> children(Node n) {
        if (n == null) return Collections.emptyList();
        List<Node> cs = childMap.get(n.trAnchor);
        return cs != null ? cs : Collections.emptyList();
    }

    /** Node cha (null nếu là root). */
    public Node parent(Node n) {
        return (n == null || n.parentTr == 0) ? null : byTr.get(n.parentTr);
    }

    public int mapId() { return mapId; }
    public Path prefab() { return prefab; }
    public PrefabDocument doc() { return doc; }

    public int nestedPrefabCount() { return nestedPrefabCount; }
    public int strippedCount() { return strippedCount; }
    /** anchor Transform của MapManager._layerBGSky (0 = không có). */
    public long skyLayerTr() { return skyLayerTr; }
    /** anchor Transform các lớp MapManager._layersBG (rỗng ⇒ parallax TẮT hoàn toàn). */
    public List<Long> bgLayerTrs() { return bgLayerTrs; }
    /** MapManager._offsetLayers — hệ số parallax, cùng index với {@link #bgLayerTrs()}. */
    public List<double[]> bgOffsets() { return bgOffsets; }

    /**
     * Đổi skeleton của một node Spine sang {@code target}.
     *
     * <p>Cập nhật NGAY mô hình trong RAM (guid, tên, thư mục) để canvas vẽ lại bằng skeleton mới
     * mà không phải nạp lại map, đồng thời bật cờ để writer ghi xuống prefab. Ghi cả guid
     * material — xem {@link Node#skelMatGuid}.
     *
     * @return chuỗi mô tả lỗi, hoặc null nếu đổi được.
     */
    public String setSkeleton(Node n, SpineCatalog.Entry target) {
        if (n == null || target == null) return "chưa chọn node hoặc skeleton";
        Fx f = n.fxOf(EffectKind.SPINE);
        if (f == null) return "node này không phải Spine (không có SkeletonAnimation)";
        if (f.anchor == 0) return "không tìm thấy block SkeletonAnimation của node";
        if (n.rendAnchor == 0) return "node không có MeshRenderer để đổi material";
        if (target.skeletonGuid == null) return "skeleton đích thiếu guid";

        f.assetGuid = target.skeletonGuid;
        f.name = target.name;
        f.folder = target.folder;
        if (n.fx == EffectKind.SPINE) {          // các field phẳng là bản sao của hiệu ứng chính
            n.fxAssetGuid = target.skeletonGuid;
            n.fxName = target.name;
            n.fxFolder = target.folder;
        }
        n.skelMatGuid = target.materialGuid;
        n.dSkeleton = true;
        return null;
    }

    /** Có node nào đang sửa chưa lưu? (kể cả thay đổi CẤU TRÚC: thêm/xoá đường) */
    public boolean dirty() {
        if (!deleted.isEmpty()) return true;
        for (Node n : nodes) if (n.isDirty()) return true;
        return false;
    }

    /** Xoá cờ dirty toàn scene + danh sách chờ xoá (writer gọi sau khi save). */
    public void clearDirty() {
        for (Node n : nodes) n.clearDirty();
        deleted.clear();
    }

    /** GO active + mọi ancestor active? (m_IsActive = 0 ở bất kỳ cấp → inactive). */
    public boolean activeInHierarchy(Node n) {
        Node cur = n;
        int guard = 0;
        while (cur != null && guard++ < CHAIN_GUARD) {
            if (!cur.active) return false;
            cur = (cur.parentTr != 0) ? byTr.get(cur.parentTr) : null;
        }
        return true;
    }

    /** Thứ tự vẽ: sorting layer index nhân 1e6 rồi cộng order (tăng dần = vẽ sau = nằm trên). */
    public long sortKey(Node n) {
        return (long) n.sortLayerIdx * 1_000_000L + n.sortOrder;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Toán transform (chép y hệt PrefabParser)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Affine 2×3 world của node: {@code [a, b, c, d, e, f]}, gồm position + rotation Z + scale
     * qua toàn bộ chuỗi cha. (180° quanh X/Y = flip, xử lý riêng bằng flipX/flipY.)
     */
    public double[] worldMatrix(Node n) {
        if (n == null) return IDENTITY.clone();
        ArrayDeque<Node> chain = new ArrayDeque<>();
        Node cur = n;
        int guard = 0;
        while (cur != null && guard++ < CHAIN_GUARD) {
            chain.push(cur);
            cur = (cur.parentTr != 0) ? byTr.get(cur.parentTr) : null;
        }
        double[] m = IDENTITY.clone();
        for (Node t : chain) {   // root → leaf
            m = mul(m, localMatrix(t));
        }
        return m;
    }

    /** Ma trận world của CHA (identity nếu là root) — dùng để tính ngược toạ độ local. */
    public double[] parentMatrix(Node n) {
        Node p = parent(n);
        return (p == null) ? IDENTITY.clone() : worldMatrix(p);
    }

    /** T * R(Z) * S của riêng node. */
    public static double[] localMatrix(Node t) {
        double ang = Math.toRadians(t.rotDeg);
        double cos = Math.cos(ang), sin = Math.sin(ang);
        return new double[]{
            cos * t.sx,  sin * t.sx,   // a, b
            -sin * t.sy, cos * t.sy,   // c, d
            t.px, t.py                 // e, f
        };
    }

    /** Compose 2×3: m ∘ n (áp n trước, rồi m). */
    public static double[] mul(double[] m, double[] n) {
        return new double[]{
            m[0] * n[0] + m[2] * n[1],          // a
            m[1] * n[0] + m[3] * n[1],          // b
            m[0] * n[2] + m[2] * n[3],          // c
            m[1] * n[2] + m[3] * n[3],          // d
            m[0] * n[4] + m[2] * n[5] + m[4],   // e
            m[1] * n[4] + m[3] * n[5] + m[5]    // f
        };
    }

    public static double[] applyPoint(double[] m, double x, double y) {
        return new double[]{m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
    }

    /**
     * Nghịch đảo affine 2×3. {@code inverse(m) ∘ m == identity}.
     *
     * <p>TEST NHẨM round-trip (làm tay, không cần chạy):
     * <pre>
     *   m = T(10,5) * R(90°) * S(2,3)
     *     → a = cos90*2 = 0, b = sin90*2 = 2, c = -sin90*3 = -3, d = cos90*3 = 0, e = 10, f = 5
     *     → m = [0, 2, -3, 0, 10, 5]
     *   det = a*d - b*c = 0*0 - 2*(-3) = 6
     *   ia = d/det = 0        ib = -b/det = -1/3
     *   ic = -c/det = 0.5     id = a/det = 0
     *   ie = -(ia*e + ic*f) = -(0*10 + 0.5*5) = -2.5
     *   if = -(ib*e + id*f) = -(-1/3*10 + 0)  = 10/3
     *   Kiểm tra điểm p = (1, 1):
     *     applyPoint(m, 1, 1)      = (0*1 + (-3)*1 + 10, 2*1 + 0*1 + 5) = (7, 7)
     *     applyPoint(inv, 7, 7)    = (0*7 + 0.5*7 - 2.5, -1/3*7 + 0*7 + 10/3)
     *                              = (3.5 - 2.5, -7/3 + 10/3) = (1, 1)  ✔ khớp p
     *   Kiểm tra gốc: applyPoint(inv, e, f) = applyPoint(inv, 10, 5)
     *                              = (0*10 + 0.5*5 - 2.5, -10/3 + 10/3) = (0, 0)  ✔
     * </pre>
     *
     * <p>det ≈ 0 (scale = 0 — có thật trong prefab: m_LocalScale {x: 0, y: 0}) → không nghịch đảo
     * được, trả về ma trận chỉ nghịch đảo phần tịnh tiến để tool không văng NaN.
     */
    public static double[] inverse(double[] m) {
        double det = m[0] * m[3] - m[1] * m[2];
        if (Math.abs(det) < 1e-12) return new double[]{1, 0, 0, 1, -m[4], -m[5]};
        double ia = m[3] / det, ib = -m[1] / det, ic = -m[2] / det, id = m[0] / det;
        double ie = -(ia * m[4] + ic * m[5]);
        double iff = -(ib * m[4] + id * m[5]);
        return new double[]{ia, ib, ic, id, ie, iff};
    }

    // ─────────────────────────────────────────────────────────────────────
    // World của renderer
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Lệch tâm sprite so với gốc transform, tính trong hệ LOCAL (chưa xoay/scale).
     * Gốc transform nằm ở PIVOT của sprite ⇒ tâm ảnh lệch (0.5 − pivot) × kích thước.
     * Mesh quad và node không renderer có pivot 0.5/0.5 nên offset = 0.
     */
    private static double[] pivotOffset(Node n) {
        return new double[]{(0.5 - n.pivotX) * n.baseW, (0.5 - n.pivotY) * n.baseH};
    }

    /** Tâm sprite/quad trong world (đã tính pivot + rotation + scale của cả chuỗi cha). */
    public double[] worldCenter(Node n) {
        double[] m = worldMatrix(n);
        double[] o = pivotOffset(n);
        return applyPoint(m, o[0], o[1]);
    }

    /** Kích thước world {w, h} (base × scale tích luỹ). */
    public double[] worldSize(Node n) {
        double[] m = worldMatrix(n);
        return new double[]{n.baseW * Math.hypot(m[0], m[1]), n.baseH * Math.hypot(m[2], m[3])};
    }

    /** Góc xoay tổng cộng trong world (độ, CCW) — dùng khi vẽ. */
    public double worldAngleDeg(Node n) {
        double[] m = worldMatrix(n);
        return Math.toDegrees(Math.atan2(m[1], m[0]));
    }

    /**
     * Đưa TÂM sprite của node tới đúng (wx, wy) world bằng cách tính ngược ra px/py LOCAL.
     *
     * <p>Cách tính (đúng cả khi cha có xoay/scale):
     * <pre>
     *   world(center) = P ∘ L ∘ pivotOffset          với P = ma trận world của CHA
     *                                                     L = T(px,py) * R(rotDeg) * S(sx,sy)
     *   L ∘ o = RS·o + (px, py)                      RS = phần xoay+scale của L (bỏ tịnh tiến)
     *   ⇒ RS·o + (px, py) = inverse(P) · (wx, wy)
     *   ⇒ (px, py) = inverse(P)·(wx, wy) − RS·o
     * </pre>
     * Tức: đưa điểm world về KHÔNG GIAN CỦA CHA, rồi trừ phần bù pivot đã xoay/scale theo
     * local matrix của chính node.
     */
    public void setWorldCenter(Node n, double wx, double wy) {
        if (n == null) return;
        double[] inv = inverse(parentMatrix(n));
        double[] inParent = applyPoint(inv, wx, wy);          // vị trí cần đặt, trong hệ của cha

        double[] o = pivotOffset(n);
        double ang = Math.toRadians(n.rotDeg);
        double cos = Math.cos(ang), sin = Math.sin(ang);
        // RS · o  (RS = [cos*sx, sin*sx, -sin*sy, cos*sy], không tịnh tiến)
        double rx = cos * n.sx * o[0] + (-sin * n.sy) * o[1];
        double ry = sin * n.sx * o[0] + (cos * n.sy) * o[1];

        n.px = inParent[0] - rx;
        n.py = inParent[1] - ry;
        n.dTransform = true;
    }

    /** Dịch tâm node theo delta world (tiện cho kéo chuột / phím mũi tên). */
    public void moveWorld(Node n, double dx, double dy) {
        if (n == null) return;
        double[] c = worldCenter(n);
        setWorldCenter(n, c[0] + dx, c[1] + dy);
    }

    // ─────────────────────────────────────────────────────────────────────
    // GỐC TRANSFORM (khác worldCenter: KHÔNG tính lệch pivot của sprite)
    // ─────────────────────────────────────────────────────────────────────

    /*
     * Vì sao phải có bộ hàm này bên cạnh worldCenter/setWorldCenter:
     *   - 4 thanh biên map, node Spine, node nhóm rỗng đều có baseW = baseH = 0 ⇒ worldCenter
     *     trùng gốc transform, dùng hàm nào cũng như nhau; NHƯNG
     *   - client đọc THẲNG "transform.position" của chúng (MapManager.SetBound, WaterWaveMover
     *     startPoint/endPoint, gốc skeleton Spine) nên phải nói rõ ta đang chỉnh GỐC TRANSFORM,
     *     không phải tâm hình. Với node CÓ sprite lệch pivot, hai thứ này KHÁC NHAU.
     */

    /** Vị trí world của GỐC Transform (đúng {@code transform.position} bên Unity). */
    public double[] worldOrigin(Node n) {
        return applyPoint(worldMatrix(n), 0, 0);
    }

    /**
     * Đặt GỐC Transform của node về đúng (wx, wy) world bằng cách tính ngược ra px/py LOCAL
     * (đúng cả khi cha có xoay/scale). Bật {@code dTransform}.
     */
    public void setWorldOrigin(Node n, double wx, double wy) {
        if (n == null) return;
        double[] p = applyPoint(inverse(parentMatrix(n)), wx, wy);
        n.px = p[0];
        n.py = p[1];
        n.dTransform = true;
    }

    /** Dịch chuyển node theo delta WORLD (giữ nguyên xoay/scale). Bật {@code dTransform}. */
    public void translateNode(Node n, double dwx, double dwy) {
        if (n == null) return;
        double[] o = worldOrigin(n);
        setWorldOrigin(n, o[0] + dwx, o[1] + dwy);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4 THANH BIÊN MAP → khung camera
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Node biên map theo hướng {@code which} = {@code "top"|"bottom"|"left"|"right"}
     * (nhận cả {@code "btm"}), null nếu map không có.
     *
     * <p>Nguồn tin CHÍNH: 4 fileID {@code _edgeColTop/_edgeColBtm/_edgeColLeft/_edgeColRight}
     * đọc THẬT từ block MonoBehaviour MapManager (chúng là anchor của TRANSFORM, đã kiểm chứng
     * trên prefab). Chỉ khi map không có MapManager (hoặc field để {@code {fileID: 0}}) mới
     * FALLBACK dò theo tên GameObject — ưu tiên node có EdgeCollider2D.
     */
    public Node boundsEdge(String which) {
        if (which == null) return null;
        String w = which.trim().toLowerCase(Locale.ROOT);
        long tr = switch (w) {
            case "top" -> edgeTrTop;
            case "bottom", "btm", "bot" -> edgeTrBtm;
            case "left" -> edgeTrLeft;
            case "right" -> edgeTrRight;
            default -> 0L;
        };
        Node byRef = (tr != 0) ? byTr.get(tr) : null;
        if (byRef != null) return byRef;

        String[] names = switch (w) {
            case "top" -> new String[]{"top"};
            case "bottom", "btm", "bot" -> new String[]{"bottom", "btm", "bot"};
            case "left" -> new String[]{"left"};
            case "right" -> new String[]{"right"};
            default -> new String[0];
        };
        Node loose = null;
        for (Node c : nodes) {
            String nm = (c.rawName != null ? c.rawName : "").trim().toLowerCase(Locale.ROOT);
            for (String x : names) {
                if (!nm.equals(x)) continue;
                if (c.colKind == ColKind.EDGE) return c;      // đúng "thanh" có collider
                if (loose == null) loose = c;
            }
        }
        return loose;
    }

    /**
     * Node này có phải 1 trong 4 biên map không. Map CÓ MapManager thì tin tuyệt đối vào
     * tham chiếu (không đoán tên) — node tên "Top" nhưng MapManager không giữ ⇒ false.
     */
    public boolean isBoundsEdge(Node n) {
        if (n == null || n.trAnchor == 0) return false;
        boolean hasRef = (edgeTrTop | edgeTrBtm | edgeTrLeft | edgeTrRight) != 0;
        if (hasRef) {
            return n.trAnchor == edgeTrTop || n.trAnchor == edgeTrBtm
                    || n.trAnchor == edgeTrLeft || n.trAnchor == edgeTrRight;
        }
        return n == boundsEdge("top") || n == boundsEdge("bottom")
                || n == boundsEdge("left") || n == boundsEdge("right");
    }

    /**
     * Khung camera mà client sẽ dùng: {@code {minX, minY, maxX, maxY}} theo ĐÚNG công thức
     * {@code MapManager.Init()}:
     * <pre>
     *   _cameraMain.SetBound(new Vector2(_edgeColLeft.position.x, _edgeColBtm.position.y),
     *                        new Vector2(_edgeColRight.position.x, _edgeColTop.position.y));
     * </pre>
     * Tức CHỈ lấy x của trái/phải và y của dưới/trên — kéo thanh Left lên xuống KHÔNG đổi gì.
     * Trả null khi thiếu bất kỳ biên nào. Toạ độ server = giá trị này × ppu (100).
     *
     * <p>Không tự sắp lại min/max: nếu người dùng kéo Left sang phải Right thì kết quả đảo
     * ngược ĐÚNG như client sẽ nhận — để UI cảnh báo được.
     */
    public double[] cameraBounds() {
        Node l = boundsEdge("left"), r = boundsEdge("right");
        Node b = boundsEdge("bottom"), t = boundsEdge("top");
        if (l == null || r == null || b == null || t == null) return null;
        return new double[]{worldOrigin(l)[0], worldOrigin(b)[1],
                            worldOrigin(r)[0], worldOrigin(t)[1]};
    }

    // ─────────────────────────────────────────────────────────────────────
    // HIỆU ỨNG — truy vấn & sửa tham số
    // ─────────────────────────────────────────────────────────────────────

    /** Mọi node có ít nhất 1 hiệu ứng (kể cả {@code OTHER}), theo thứ tự trong file. */
    public List<Node> effectNodes() {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) if (n.fx != EffectKind.NONE) out.add(n);
        return out;
    }

    /** Node có hiệu ứng THẬT (vẽ/chạy được) — bỏ {@code OTHER}. */
    public List<Node> realEffectNodes() {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            for (Fx f : n.fxList) {
                if (f.kind.real()) { out.add(n); break; }
            }
        }
        return out;
    }

    /** Đếm node theo từng loại hiệu ứng (1 node có 2 hiệu ứng được tính ở CẢ HAI loại). */
    public Map<EffectKind, Integer> effectCounts() {
        Map<EffectKind, Integer> out = new LinkedHashMap<>();
        for (EffectKind k : EffectKind.values()) if (k != EffectKind.NONE) out.put(k, 0);
        for (Node n : nodes) {
            for (Fx f : n.fxList) out.merge(f.kind, 1, Integer::sum);
        }
        return out;
    }

    /**
     * Sửa 1 tham số hiệu ứng. Key là TÊN FIELD YAML y hệt trong prefab
     * ({@code moveSpeed}, {@code _swimSpeed}, {@code _animationName}, {@code direction}…),
     * value là giá trị THÔ sẽ ghi thẳng vào file ({@code "3.5"}, {@code "{x: 0, y: -0.2}"}).
     *
     * <p>Tự tìm ĐÚNG component đang có key đó (node cá vừa Spine vừa FishSwim thì
     * {@code _swimSpeed} vào block FishSwim, {@code timeScale} vào block SkeletonAnimation).
     * Key chưa từng có ⇒ ghi vào hiệu ứng CHÍNH (writer sẽ cảnh báo nếu file không có field đó).
     * Chỉ bật {@code dFx} khi giá trị THỰC SỰ khác.
     */
    public void setFxParam(Node n, String key, String val) {
        if (n == null || key == null || key.isEmpty()) return;
        String k = key.endsWith(":") ? key.substring(0, key.length() - 1) : key;
        String v = (val == null) ? "" : val.trim();
        for (Fx f : n.fxList) {
            if (!f.params.containsKey(k)) continue;
            if (v.equals(f.params.get(k))) return;             // không đổi → khỏi bẩn cờ dirty
            f.params.put(k, v);
            n.dFx = true;
            return;
        }
        if (n.fxList.isEmpty()) return;                         // node không có hiệu ứng
        n.fxParams.put(k, v);
        n.dFx = true;
    }

    /** Giá trị THÔ của 1 tham số hiệu ứng (tìm trong MỌI component của node). */
    public String fxParam(Node n, String key, String def) {
        if (n == null || key == null) return def;
        String k = key.endsWith(":") ? key.substring(0, key.length() - 1) : key;
        for (Fx f : n.fxList) {
            String v = f.params.get(k);
            if (v != null) return v;
        }
        return def;
    }

    /** Tham số hiệu ứng dạng SỐ (rỗng / không phải số → {@code def}). */
    public double fxParamD(Node n, String key, double def) {
        String v = fxParam(n, key, null);
        if (v == null || v.isEmpty()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Collider
    // ─────────────────────────────────────────────────────────────────────

    /** Các đỉnh collider quy về world (EDGE = polyline mở, BOX = 4 góc). */
    public double[][] worldPoints(Node n) {
        if (n == null || n.colKind == ColKind.NONE) return new double[0][];
        double[] m = worldMatrix(n);
        if (n.colKind == ColKind.BOX) {
            double hw = n.boxW / 2.0, hh = n.boxH / 2.0;
            return new double[][]{
                applyPoint(m, n.offX - hw, n.offY - hh),
                applyPoint(m, n.offX + hw, n.offY - hh),
                applyPoint(m, n.offX + hw, n.offY + hh),
                applyPoint(m, n.offX - hw, n.offY + hh)
            };
        }
        if (n.pts == null) return new double[0][];
        double[][] out = new double[n.pts.length][];
        for (int i = 0; i < n.pts.length; i++) {
            out[i] = applyPoint(m, n.pts[i][0] + n.offX, n.pts[i][1] + n.offY);
        }
        return out;
    }

    /** world → local của chính node, đã trừ m_Offset ⇒ đúng giá trị ghi vào m_Points. */
    private double[] toLocalPoint(Node n, double wx, double wy) {
        double[] inv = inverse(worldMatrix(n));
        double[] l = applyPoint(inv, wx, wy);
        return new double[]{l[0] - n.offX, l[1] - n.offY};
    }

    /** Kéo đỉnh thứ i của EdgeCollider2D tới (wx, wy) world. */
    public void setColliderWorldPoint(Node n, int i, double wx, double wy) {
        if (n == null || n.colKind != ColKind.EDGE || n.pts == null) return;
        if (i < 0 || i >= n.pts.length) return;
        n.pts[i] = toLocalPoint(n, wx, wy);
        n.dPoints = true;
    }

    /**
     * Chèn 1 đỉnh mới NGAY SAU đỉnh {@code afterIdx} (dùng -1 để chèn vào đầu).
     * Cho phép "bẻ" thêm điểm giữa 1 đoạn của đường đất.
     */
    public void insertColliderPoint(Node n, int afterIdx, double wx, double wy) {
        if (n == null || n.colKind != ColKind.EDGE) return;
        double[][] old = (n.pts != null) ? n.pts : new double[0][];
        int at = afterIdx + 1;
        if (at < 0) at = 0;
        if (at > old.length) at = old.length;
        double[][] nw = new double[old.length + 1][];
        System.arraycopy(old, 0, nw, 0, at);
        nw[at] = toLocalPoint(n, wx, wy);
        System.arraycopy(old, at, nw, at + 1, old.length - at);
        n.pts = nw;
        n.dPoints = true;
    }

    /** Xoá đỉnh thứ idx. EdgeCollider2D cần TỐI THIỂU 2 điểm → dưới 2 thì không cho xoá. */
    public void removeColliderPoint(Node n, int idx) {
        if (n == null || n.colKind != ColKind.EDGE || n.pts == null) return;
        if (idx < 0 || idx >= n.pts.length) return;
        if (n.pts.length <= 2) return;   // Unity yêu cầu ≥ 2 điểm
        double[][] nw = new double[n.pts.length - 1][];
        System.arraycopy(n.pts, 0, nw, 0, idx);
        System.arraycopy(n.pts, idx + 1, nw, idx, n.pts.length - idx - 1);
        n.pts = nw;
        n.dPoints = true;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tiện ích
    // ─────────────────────────────────────────────────────────────────────

    /** Bounding box world của mọi renderer + collider đang active: {minX, minY, maxX, maxY}. */
    public double[] bounds() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (Node n : nodes) {
            if (!activeInHierarchy(n)) continue;
            if (n.hasRenderer && n.baseW > 0 && n.baseH > 0) {
                double[] c = worldCenter(n);
                double[] s = worldSize(n);
                minX = Math.min(minX, c[0] - s[0] / 2); maxX = Math.max(maxX, c[0] + s[0] / 2);
                minY = Math.min(minY, c[1] - s[1] / 2); maxY = Math.max(maxY, c[1] + s[1] / 2);
                any = true;
            }
            if (n.colKind != ColKind.NONE) {
                for (double[] p : worldPoints(n)) {
                    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                    any = true;
                }
            }
        }
        if (!any) return new double[]{-10, -10, 10, 10};
        return new double[]{minX, minY, maxX, maxY};
    }
}
