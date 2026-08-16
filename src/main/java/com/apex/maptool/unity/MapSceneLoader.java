package com.apex.maptool.unity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MapSceneLoader — đọc 1 prefab map của client → {@link MapScene} đầy đủ để vẽ và sửa.
 *
 * <p>Nguyên tắc:
 * <ul>
 *   <li>Tạo node cho MỌI GameObject có Transform (kể cả node nhóm rỗng như {@code Layer_0},
 *       {@code Layer_Collider}) vì cây hierarchy của UI cần chúng.</li>
 *   <li>Bỏ qua block {@code stripped} và {@code PrefabInstance (!u!1001)} — chỉ ĐẾM lại để UI
 *       cảnh báo "map này có N nested prefab không hiển thị/không sửa được".</li>
 *   <li>Toán transform/pivot chép nguyên {@code PrefabParser} (xem {@link MapScene}).</li>
 *   <li>MeshRenderer CHỈ vẽ được khi MeshFilter cùng GameObject có {@code m_Mesh.fileID = 10210}
 *       (quad 1×1). 113 MeshRenderer còn lại là Spine {@code SkeletonAnimation} — nếu dán
 *       texture của material lên đó sẽ ra nguyên tấm atlas trên map (bug của parser cũ).</li>
 * </ul>
 *
 * <h2>NODE SPINE ĐƯỢC XỬ LÝ THẾ NÀO (đọc kỹ trước khi sửa canvas)</h2>
 * <ul>
 *   <li>{@code hasRenderer = true}, {@code isSprite = false}, {@code isQuadMesh = false},
 *       {@code sortLayerIdx/sortOrder/sortLayerId} đọc y như mọi renderer khác — nhờ vậy node
 *       Spine VẪN tham gia đúng thứ tự vẽ và phép "trước/sau player".</li>
 *   <li>{@code baseW = baseH = 0} và {@code texture = null} (GIỮ NGUYÊN như bản cũ) — cố tình,
 *       để không phá {@code LayoutSelfTest} T1/T2 và để mọi vòng lặp cũ tự bỏ qua nó.</li>
 *   <li>Dấu hiệu nhận biết cho canvas: <b>{@code n.fx == EffectKind.SPINE}</b> (hoặc
 *       {@code n.hasFx(SPINE)}). Gặp node này thì VẼ BẰNG SPINE
 *       ({@code SpineCharacter.load(n.fxFolder)}), KHÔNG dán {@code n.texGuid}
 *       (guid đó là material atlas).</li>
 *   <li>{@code n.fxFolder} = thư mục chứa {@code .json} + {@code .atlas.txt}, suy ra từ
 *       {@code skeletonDataAsset.guid} → GuidIndex → {@code *_SkeletonData.asset} → thư mục cha.</li>
 * </ul>
 */
public final class MapSceneLoader {

    // ── classId Unity ──
    private static final int CLS_GAMEOBJECT      = 1;
    private static final int CLS_TRANSFORM       = 4;
    private static final int CLS_MESH_RENDERER   = 23;
    private static final int CLS_MESH_FILTER     = 33;
    private static final int CLS_ANIMATOR        = 95;
    private static final int CLS_BOX_COLLIDER    = 61;
    private static final int CLS_EDGE_COLLIDER   = 68;
    private static final int CLS_MONOBEHAVIOUR   = 114;
    private static final int CLS_RECT_TRANSFORM  = 224;
    private static final int CLS_PLATFORM_EFFECTOR = 251;
    private static final int CLS_SPRITE_RENDERER = 212;
    private static final int CLS_PREFAB_INSTANCE = 1001;

    /** m_Mesh trỏ tới quad built-in 1×1 unit (tâm ở gốc). */
    private static final long MESH_QUAD = 10210L;
    /** m_Sprite.fileID của sprite ĐƠN (khác giá trị này = sub-sprite trong sprite sheet). */
    private static final long SPRITE_SINGLE = 21300000L;
    /** guid script MapManager.cs — dùng lấy _layerBGSky/_layersBG/_offsetLayers + 4 biên map. */
    private static final String MAPMANAGER_GUID = "af78feca514ed524381d1e274f4973dd";

    // ── guid script HIỆU ỨNG (đối chiếu 156 prefab, tài liệu 06 §A.2) ──
    /** Spine.Unity.SkeletonAnimation — 113 cái: cây lắc lư / sóng / gió / cá. */
    private static final String GUID_SPINE      = "d247ba06193faa74d9335f5481b2b56c";
    /** WaterWaveMover.cs — 163 cái: dòng nước chảy. */
    private static final String GUID_WATER_WAVE = "aede8610f78337840ba00de3173959eb";
    /** FishSwim.cs — 15 cái: cá / cua / hải âu bơi. */
    private static final String GUID_FISH_SWIM  = "63b0e6cbf2189d84ea346ce5bfbab639";
    /** WaveWash.cs — 4 cái (chỉ Map8001): sóng vỗ bờ. */
    private static final String GUID_WAVE_WASH  = "6cb6519367b3a91439d8f407fa642f01";
    /** Water2DScript.cs — 1 cái (Map204): cuộn UV mặt nước. */
    private static final String GUID_WATER2D    = "8829f965957cc3046a2feba774f2db42";

    /**
     * Key KHÔNG đưa vào {@code fxParams}: header chung của mọi block Unity, không phải tham số
     * của hiệu ứng và sửa vào là hỏng file. ({@code m_Enabled} thì GIỮ — đó chính là công tắc
     * "bật/tắt hiệu ứng"; {@code m_Controller} của Animator cũng giữ để đổi controller.)
     */
    private static final Set<String> FX_SKIP_KEYS = Set.of(
            "m_ObjectHideFlags", "m_CorrespondingSourceObject", "m_PrefabInstance", "m_PrefabAsset",
            "m_GameObject", "m_EditorHideFlags", "m_Script", "m_Name", "m_EditorClassIdentifier",
            "serializedVersion");

    private static final Pattern GUID   = Pattern.compile("guid:\\s*([0-9a-fA-F]{32})");
    private static final Pattern FILEID = Pattern.compile("fileID:\\s*(-?\\d+)");
    private static final Pattern XY     = Pattern.compile("x:\\s*(-?[\\d.eE+]+),\\s*y:\\s*(-?[\\d.eE+]+)");
    private static final Pattern ALPHA  = Pattern.compile("a:\\s*(-?[\\d.eE+]+)");

    private final GuidIndex guidIndex;
    private final MaterialResolver matResolver;
    private final TextureCache texCache;
    private final int ppu;

    /** Cache pivot theo file .png (đọc .png.meta khá tốn I/O). */
    private final Map<Path, double[]> pivotCache = new HashMap<>();

    public MapSceneLoader(GuidIndex guidIndex, MaterialResolver matResolver, TextureCache texCache, int ppu) {
        this.guidIndex = guidIndex;
        this.matResolver = matResolver;
        this.texCache = texCache;
        this.ppu = ppu > 0 ? ppu : 100;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Load
    // ─────────────────────────────────────────────────────────────────────

    public MapScene load(Path prefab, int mapId) throws IOException {
        long t0 = System.currentTimeMillis();
        PrefabDocument doc = new PrefabDocument(prefab);
        MapScene scene = new MapScene(doc, mapId);

        // ── Pass 1: phân loại block ──
        Map<Long, PrefabDocument.Block> goBlk = new HashMap<>();     // anchor GameObject → block
        Map<Long, Long> meshOfGo = new HashMap<>();                  // goAnchor → m_Mesh.fileID
        // goAnchor → block PlatformEffector2D (cần CẢ anchor lẫn m_RotationalOffset/m_SurfaceArc
        // thì UI mới sửa được chiều chặn và writer mới gỡ đúng block).
        Map<Long, PrefabDocument.Block> effectorGo = new HashMap<>();
        List<PrefabDocument.Block> trBlocks = new ArrayList<>();
        List<PrefabDocument.Block> rendBlocks = new ArrayList<>();
        List<PrefabDocument.Block> colBlocks = new ArrayList<>();
        // Block hiệu ứng: MỌI MonoBehaviour (trừ MapManager) + MỌI Animator, giữ NGUYÊN thứ tự
        // xuất hiện trong file để hiệu ứng nào đứng trước thì được ưu tiên khi hoà rank.
        List<PrefabDocument.Block> fxBlocks = new ArrayList<>();
        PrefabDocument.Block mapManager = null;
        int nested = 0, stripped = 0;

        for (PrefabDocument.Block b : doc.blocks()) {
            if (b.stripped) { stripped++; continue; }   // thiếu m_Name/m_Father/m_LocalPosition → bỏ
            switch (b.classId) {
                case CLS_GAMEOBJECT -> goBlk.put(b.anchor, b);
                case CLS_TRANSFORM, CLS_RECT_TRANSFORM -> trBlocks.add(b);
                case CLS_SPRITE_RENDERER, CLS_MESH_RENDERER -> rendBlocks.add(b);
                case CLS_MESH_FILTER -> meshOfGo.put(doc.getFileId(b, "m_GameObject:"), doc.getFileId(b, "m_Mesh:"));
                case CLS_EDGE_COLLIDER, CLS_BOX_COLLIDER -> colBlocks.add(b);
                case CLS_PLATFORM_EFFECTOR -> effectorGo.putIfAbsent(doc.getFileId(b, "m_GameObject:"), b);
                case CLS_MONOBEHAVIOUR -> {
                    boolean isMgr = MAPMANAGER_GUID.equalsIgnoreCase(String.valueOf(doc.getGuid(b, "m_Script:")));
                    if (isMgr && mapManager == null) mapManager = b;
                    if (!isMgr) fxBlocks.add(b);
                }
                case CLS_ANIMATOR -> fxBlocks.add(b);
                case CLS_PREFAB_INSTANCE -> nested++;
                default -> { /* 222 CanvasRenderer, 1029 DefaultAsset, 251 effector… bỏ qua */ }
            }
        }
        scene.setNestedPrefabCount(nested);
        scene.setStrippedCount(stripped);

        // ── Pass 2: node từ Transform + GameObject ──
        for (PrefabDocument.Block tb : trBlocks) {
            long goA = doc.getFileId(tb, "m_GameObject:");
            PrefabDocument.Block gb = goBlk.get(goA);
            if (gb == null) continue;    // GameObject stripped hoặc thiếu → không dựng node

            MapScene.Node n = new MapScene.Node();
            n.trAnchor = tb.anchor;
            n.goAnchor = goA;
            n.parentTr = doc.getFileId(tb, "m_Father:");

            double[] lp = doc.getVec(tb, "m_LocalPosition:");
            if (lp != null && lp.length >= 2) { n.px = lp[0]; n.py = lp[1]; }
            double[] ls = doc.getVec(tb, "m_LocalScale:");
            if (ls != null && ls.length >= 2) { n.sx = ls[0]; n.sy = ls[1]; }
            double[] q = doc.getVec(tb, "m_LocalRotation:");
            if (q != null && q.length >= 4) { n.qx = q[0]; n.qy = q[1]; n.qz = q[2]; n.qw = q[3]; }
            // Rotation Z chỉ hợp lệ khi KHÔNG phải quay 180° quanh X/Y (Unity dùng để "lật").
            // Y hệt PrefabParser.localMatrix.
            n.rotDeg = (Math.abs(n.qx) < 0.1 && Math.abs(n.qy) < 0.1)
                    ? Math.toDegrees(2.0 * Math.atan2(n.qz, n.qw)) : 0.0;

            n.rawName = nz(doc.getScalar(gb, "m_Name:"));
            n.name = n.rawName.isEmpty() ? "(không tên)" : n.rawName;
            n.active = doc.getInt(gb, "m_IsActive:", 1) != 0;
            n.physLayer = doc.getInt(gb, "m_Layer:", 0);
            n.origPhysLayer = n.physLayer;      // mốc để tắt oneway trả về đúng layer gốc
            String tag = doc.getScalar(gb, "m_TagString:");
            if (tag != null && !tag.isEmpty()) n.tag = tag;
            n.childTr = readFileIdList(doc, tb, "m_Children:");
            PrefabDocument.Block eff = effectorGo.get(goA);
            n.hasPlatformEffector = (eff != null);
            if (eff != null) {
                n.effAnchor = eff.anchor;
                n.effRotOffset = doc.getDouble(eff, "m_RotationalOffset:", 0);
                n.effSurfaceArc = doc.getDouble(eff, "m_SurfaceArc:", 90);
            }

            scene.addNode(n);
        }
        scene.index();

        // ── Pass 3: renderer ──
        int missTex = 0, spineSkipped = 0;
        for (PrefabDocument.Block rb : rendBlocks) {
            MapScene.Node n = scene.byGo(doc.getFileId(rb, "m_GameObject:"));
            if (n == null || n.hasRenderer) continue;   // 1 GameObject chỉ lấy renderer đầu tiên

            n.hasRenderer = true;
            n.rendAnchor = rb.anchor;
            n.isSprite = (rb.classId == CLS_SPRITE_RENDERER);
            n.rendEnabled = doc.getInt(rb, "m_Enabled:", 1) != 0;
            n.sortLayerId = doc.getInt(rb, "m_SortingLayerID:", 0);   // int32 CÓ DẤU
            n.sortLayerIdx = doc.getInt(rb, "m_SortingLayer:", 0);
            n.sortOrder = doc.getInt(rb, "m_SortingOrder:", 0);

            if (n.isSprite) {
                loadSprite(doc, rb, n);
                if (n.texMissing) missTex++;
            } else {
                boolean quad = meshOfGo.getOrDefault(n.goAnchor, 0L) == MESH_QUAD;
                n.isQuadMesh = quad;
                n.texGuid = firstMaterialGuid(doc, rb);
                n.pivotX = 0.5; n.pivotY = 0.5;         // quad Unity: tâm ở gốc transform
                if (quad) {
                    n.texture = (n.texGuid != null) ? matResolver.resolveTexture(n.texGuid) : null;
                    n.baseW = 1; n.baseH = 1;           // quad base 1×1 unit (KHÔNG chia ppu)
                    if (n.texture == null) { n.texMissing = true; missTex++; }
                } else {
                    // Spine SkeletonAnimation (m_Mesh = 0): có material atlas nhưng KHÔNG phải hình map.
                    n.baseW = 0; n.baseH = 0;
                    spineSkipped++;
                }
            }

            // flip suy từ quaternion 180° quanh X/Y — chép PrefabParser dòng 242-246.
            if (Math.abs(n.qw) < 0.1) {
                if (Math.abs(n.qy) > 0.9) n.flipX = !n.flipX;
                if (Math.abs(n.qx) > 0.9) n.flipY = !n.flipY;
            }
        }

        // ── Pass 4: collider ──
        for (PrefabDocument.Block cb : colBlocks) {
            MapScene.Node n = scene.byGo(doc.getFileId(cb, "m_GameObject:"));
            if (n == null || n.colKind != MapScene.ColKind.NONE) continue;

            n.colAnchor = cb.anchor;
            n.colEnabled = doc.getInt(cb, "m_Enabled:", 1) != 0;
            n.isTrigger = doc.getInt(cb, "m_IsTrigger:", 0) != 0;
            double[] off = doc.getVec(cb, "m_Offset:");
            if (off != null && off.length >= 2) { n.offX = off[0]; n.offY = off[1]; }

            if (cb.classId == CLS_EDGE_COLLIDER) {
                n.colKind = MapScene.ColKind.EDGE;
                double[][] pts = doc.getPointList(cb, "m_Points:");
                n.pts = (pts != null) ? pts : new double[0][];
            } else {
                n.colKind = MapScene.ColKind.BOX;
                double[] sz = doc.getVec(cb, "m_Size:");
                if (sz != null && sz.length >= 2) { n.boxW = sz[0]; n.boxH = sz[1]; }
            }
        }

        // ── Pass 5: MapManager (BG parallax + 4 biên map) ──
        if (mapManager != null) {
            scene.setSkyLayerTr(doc.getFileId(mapManager, "_layerBGSky:"));
            long[] bg = readFileIdList(doc, mapManager, "_layersBG:");
            if (bg != null) for (long id : bg) scene.addBgLayerTr(id);
            double[][] offs = doc.getPointList(mapManager, "_offsetLayers:");
            if (offs != null) for (double[] o : offs) scene.addBgOffset(o[0], o.length > 1 ? o[1] : 0);
            // 4 fileID này là anchor TRANSFORM (đã kiểm chứng: !u!4 &1024740488381353296 = node "Top"
            // của Map13) — vị trí của chúng quyết định khung camera, xem MapScene.cameraBounds().
            scene.setEdgeTr("top",    doc.getFileId(mapManager, "_edgeColTop:"));
            scene.setEdgeTr("bottom", doc.getFileId(mapManager, "_edgeColBtm:"));
            scene.setEdgeTr("left",   doc.getFileId(mapManager, "_edgeColLeft:"));
            scene.setEdgeTr("right",  doc.getFileId(mapManager, "_edgeColRight:"));
        }

        // ── Pass 6: HIỆU ỨNG (Spine / Animator / script C#) ──
        int fxOrphan = 0;
        for (PrefabDocument.Block fb : fxBlocks) {
            MapScene.Node n = scene.byGo(doc.getFileId(fb, "m_GameObject:"));
            if (n == null) { fxOrphan++; continue; }     // component của GameObject stripped
            MapScene.Fx fx = readFx(doc, fb);
            if (fx != null) scene.addFx(n, fx);
        }

        System.out.printf("[MapSceneLoader] Map%d: %d node · renderer thiếu texture %d · MeshRenderer "
                        + "không quad %d · nested prefab %d · stripped %d · %dms%n",
                mapId, scene.nodes().size(), missTex, spineSkipped, nested, stripped,
                System.currentTimeMillis() - t0);
        System.out.println("[MapSceneLoader] Map" + mapId + ": hiệu ứng " + fxSummary(scene)
                + (fxOrphan > 0 ? " · bỏ " + fxOrphan + " component mồ côi (GameObject stripped)" : ""));
        return scene;
    }

    // ─────────────────────────────────────────────────────────────────────
    // HIỆU ỨNG
    // ─────────────────────────────────────────────────────────────────────

    /** Dòng tổng kết "SPINE 22 · WATER_WAVE 0 · …" — chỉ in loại có ít nhất 1 cái. */
    private static String fxSummary(MapScene scene) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Map.Entry<MapScene.EffectKind, Integer> e : scene.effectCounts().entrySet()) {
            if (e.getValue() == 0) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.getKey()).append(' ').append(e.getValue());
            total += e.getValue();
        }
        if (total == 0) return "KHÔNG có";
        return sb + " (tổng " + total + " component / " + scene.effectNodes().size() + " node)";
    }

    /**
     * Đọc 1 block hiệu ứng thành {@link MapScene.Fx}. Nhận diện theo guid {@code m_Script}
     * (block {@code !u!95} thì đương nhiên là Animator); script lạ ⇒ {@code OTHER} kèm tên
     * lớp/tên file .cs để UI báo "node này còn script X".
     */
    private MapScene.Fx readFx(PrefabDocument doc, PrefabDocument.Block b) {
        MapScene.Fx fx = new MapScene.Fx();
        fx.anchor = b.anchor;

        if (b.classId == CLS_ANIMATOR) {
            fx.kind = MapScene.EffectKind.ANIMATOR;
            fx.assetGuid = doc.getGuid(b, "m_Controller:");
            Path ctrl = (guidIndex != null && fx.assetGuid != null) ? guidIndex.resolve(fx.assetGuid) : null;
            fx.folder = ctrl;                                  // ANIMATOR: chính FILE .controller
            fx.name = (ctrl != null) ? stripExt(ctrl.getFileName().toString()) : "(controller thiếu)";
        } else {
            fx.scriptGuid = doc.getGuid(b, "m_Script:");
            String g = (fx.scriptGuid == null) ? "" : fx.scriptGuid.toLowerCase();
            fx.kind = switch (g) {
                case GUID_SPINE      -> MapScene.EffectKind.SPINE;
                case GUID_WATER_WAVE -> MapScene.EffectKind.WATER_WAVE;
                case GUID_FISH_SWIM  -> MapScene.EffectKind.FISH_SWIM;
                case GUID_WAVE_WASH  -> MapScene.EffectKind.WAVE_WASH;
                case GUID_WATER2D    -> MapScene.EffectKind.WATER2D;
                default              -> MapScene.EffectKind.OTHER;
            };
            fx.name = scriptName(doc, b, fx.scriptGuid);
            if (fx.kind == MapScene.EffectKind.SPINE) {
                fx.assetGuid = doc.getGuid(b, "skeletonDataAsset:");
                // guid → …/CayDua_SkeletonData.asset → THƯ MỤC cha (có .json + .atlas.txt + .png)
                Path asset = (guidIndex != null && fx.assetGuid != null) ? guidIndex.resolve(fx.assetGuid) : null;
                if (asset != null) {
                    fx.folder = asset.getParent();
                    String nm = stripExt(asset.getFileName().toString());
                    if (nm.endsWith("_SkeletonData")) nm = nm.substring(0, nm.length() - "_SkeletonData".length());
                    fx.name = nm;
                } else {
                    fx.name = "(skeleton thiếu)";
                }
            }
        }
        readFxParams(doc, b, fx.params);
        return fx;
    }

    /**
     * Tên lớp C# của MonoBehaviour: ưu tiên {@code m_EditorClassIdentifier}
     * ({@code "Assembly-CSharp::FishSwim"} → {@code FishSwim},
     * {@code "spine-unity::Spine.Unity.SkeletonAnimation"} → {@code SkeletonAnimation});
     * để trống (WaterWaveMover, Water2DScript…) thì tra tên file {@code .cs} theo guid.
     */
    private String scriptName(PrefabDocument doc, PrefabDocument.Block b, String scriptGuid) {
        String id = doc.getScalar(b, "m_EditorClassIdentifier:");
        if (id != null && !id.trim().isEmpty()) {
            String s = id.trim();
            int sep = s.lastIndexOf("::");
            if (sep >= 0) s = s.substring(sep + 2);
            int dot = s.lastIndexOf('.');
            if (dot >= 0) s = s.substring(dot + 1);
            if (!s.isEmpty()) return s;
        }
        Path cs = (guidIndex != null && scriptGuid != null) ? guidIndex.resolve(scriptGuid) : null;
        if (cs != null && cs.getFileName() != null) return stripExt(cs.getFileName().toString());
        return "(script thiếu)";
    }

    /**
     * Bóc MỌI field 1 dòng ở indent 2 của block vào {@code out} (giữ nguyên thứ tự file).
     *
     * <p>CỐ TÌNH BỎ QUA key mở đầu một khối con ({@code maskMaterials:} + 3 dòng indent 4) hoặc
     * một list nhiều dòng ({@code _layersBG:} + các dòng {@code "  - "}) — writer chỉ patch được
     * "1 field = 1 dòng", đưa mấy khoá đó vào là ghi hỏng file. Key inline như
     * {@code separatorSlotNames: []} thì VẪN nhận (nó đúng 1 dòng).
     */
    private static void readFxParams(PrefabDocument doc, PrefabDocument.Block b, Map<String, String> out) {
        List<String> ls = doc.lines();
        int end = Math.min(b.end, ls.size());
        for (int i = b.start + 1; i < end; i++) {
            String l = ls.get(i);
            if (l.length() < 3 || !l.startsWith("  ") || l.charAt(2) == ' ') continue;  // chỉ indent ĐÚNG 2
            int c = l.indexOf(':');
            if (c < 3) continue;
            String key = l.substring(2, c);
            if (!isIdent(key) || FX_SKIP_KEYS.contains(key)) continue;
            String val = l.substring(c + 1).trim();
            if (val.isEmpty() && i + 1 < end) {
                String nx = ls.get(i + 1);
                if (nx.startsWith("    ") || nx.startsWith("  - ")) continue;   // khối con / list
            }
            out.putIfAbsent(key, val);
        }
    }

    /** Tên field YAML hợp lệ (chữ/số/gạch dưới, không mở đầu bằng số). */
    private static boolean isIdent(String s) {
        if (s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!Character.isLetter(c0) && c0 != '_') return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /** Bỏ phần mở rộng cuối cùng của tên file ("Thac_1.controller" → "Thac_1"). */
    private static String stripExt(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return (dot > 0) ? fileName.substring(0, dot) : fileName;
    }

    // ─────────────────────────────────────────────────────────────────────
    // SpriteRenderer
    // ─────────────────────────────────────────────────────────────────────

    private void loadSprite(PrefabDocument doc, PrefabDocument.Block rb, MapScene.Node n) {
        n.flipX = doc.getInt(rb, "m_FlipX:", 0) != 0;
        n.flipY = doc.getInt(rb, "m_FlipY:", 0) != 0;
        n.drawMode = doc.getInt(rb, "m_DrawMode:", 0);
        n.alpha = readAlpha(doc, rb);
        n.spriteFileId = doc.getFileId(rb, "m_Sprite:");
        n.texGuid = doc.getGuid(rb, "m_Sprite:");

        if (n.texGuid == null) {          // m_Sprite: {fileID: 0} — 141 cái trong corpus
            n.baseW = 0; n.baseH = 0;
            return;
        }
        n.texture = guidIndex.resolve(n.texGuid);

        int pngW = 0, pngH = 0;
        if (n.texture != null) {
            int[] d = texCache.size(n.texture);
            pngW = d[0]; pngH = d[1];
        }
        if (pngW <= 0 || pngH <= 0) {     // 88 tham chiếu sprite hỏng → placeholder 1×1
            n.texMissing = true;
            n.baseW = 1; n.baseH = 1;
            n.pivotX = 0.5; n.pivotY = 0.5;
            return;
        }

        int cropW = pngW, cropH = pngH;
        double[] piv = null;
        if (n.spriteFileId != SPRITE_SINGLE && n.spriteFileId != 0) {
            // Sub-sprite: cắt theo entry trong spriteSheet.sprites có internalID khớp.
            double[] sub = subSpriteInfo(n.texture, n.spriteFileId);
            if (sub != null) {
                int rx = (int) sub[0], ry = (int) sub[1], rw = (int) sub[2], rh = (int) sub[3];
                // rect.y của Unity tính từ ĐÁY ảnh lên, Java tính từ ĐỈNH xuống → lật.
                int jy = pngH - ry - rh;
                if (rw > 0 && rh > 0 && rx >= 0 && jy >= 0 && rx + rw <= pngW && jy + rh <= pngH) {
                    n.subRect = new int[]{rx, jy, rw, rh};
                    cropW = rw; cropH = rh;
                    piv = new double[]{sub[4], sub[5]};
                }
            }
        }
        if (piv == null) piv = spritePivot(n.texture);
        n.pivotX = piv[0]; n.pivotY = piv[1];

        n.baseW = cropW / (double) ppu;
        n.baseH = cropH / (double) ppu;

        // m_DrawMode 0 (Simple) → m_Size là RÁC, phải dùng kích thước PNG.
        // m_DrawMode 1/2 (Sliced/Tiled) → m_Size mới là kích thước world thật (trước localScale).
        if (n.drawMode != 0) {
            double[] sz = doc.getVec(rb, "m_Size:");
            if (sz != null && sz.length >= 2 && sz[0] > 0 && sz[1] > 0) {
                n.baseW = sz[0]; n.baseH = sz[1];
            }
        }
    }

    private static double readAlpha(PrefabDocument doc, PrefabDocument.Block b) {
        int at = doc.findLine(b, "m_Color:");
        if (at < 0) return 1;
        List<String> ls = doc.lines();
        if (at >= ls.size()) return 1;
        Matcher m = ALPHA.matcher(ls.get(at));
        return m.find() ? parse(m.group(1), 1) : 1;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Pivot sprite (chép PrefabParser.spritePivot / alignmentToPivot)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Đọc pivot sprite từ .png.meta → [px, py] (0..1, (0.5,0.5)=center, (0.5,0)=bottom-center).
     * alignment (Unity SpriteAlignment): 0=Center 1=TopLeft 2=TopCenter 3=TopRight
     * 4=LeftCenter 5=RightCenter 6=BottomLeft 7=BottomCenter 8=BottomRight 9=Custom.
     */
    private double[] spritePivot(Path png) {
        if (png == null) return new double[]{0.5, 0.5};
        double[] cached = pivotCache.get(png);
        if (cached != null) return cached;
        double[] piv = new double[]{0.5, 0.5};
        Path meta = png.resolveSibling(png.getFileName().toString() + ".meta");
        try {
            if (Files.exists(meta)) {
                int alignment = -1;
                double[] custom = null;
                for (String raw : Files.readAllLines(meta)) {
                    String t = raw.trim();
                    if (alignment < 0 && t.startsWith("alignment:")) {
                        try { alignment = Integer.parseInt(t.substring("alignment:".length()).trim()); } catch (Exception ignored) {}
                    } else if (custom == null && t.startsWith("spritePivot:")) {
                        Matcher m = XY.matcher(t);
                        if (m.find()) custom = new double[]{parse(m.group(1), 0.5), parse(m.group(2), 0.5)};
                    }
                }
                if (alignment == 9 && custom != null) piv = custom;
                else if (alignment >= 0) piv = alignmentToPivot(alignment);
            }
        } catch (IOException ignored) { }
        pivotCache.put(png, piv);
        return piv;
    }

    private static double[] alignmentToPivot(int a) {
        return switch (a) {
            case 1 -> new double[]{0.0, 1.0};   // TopLeft
            case 2 -> new double[]{0.5, 1.0};   // TopCenter
            case 3 -> new double[]{1.0, 1.0};   // TopRight
            case 4 -> new double[]{0.0, 0.5};   // LeftCenter
            case 5 -> new double[]{1.0, 0.5};   // RightCenter
            case 6 -> new double[]{0.0, 0.0};   // BottomLeft
            case 7 -> new double[]{0.5, 0.0};   // BottomCenter
            case 8 -> new double[]{1.0, 0.0};   // BottomRight
            default -> new double[]{0.5, 0.5};  // Center / unknown
        };
    }

    /**
     * Tìm entry sub-sprite trong .png.meta theo internalID.
     * Trả {rectX, rectY, rectW, rectH, pivotX, pivotY} (rect theo toạ độ UNITY — y từ đáy lên),
     * null nếu không tìm thấy.
     */
    private double[] subSpriteInfo(Path png, long internalId) {
        Path meta = png.resolveSibling(png.getFileName().toString() + ".meta");
        if (!Files.exists(meta)) return null;
        try {
            boolean inSprites = false;
            double rx = 0, ry = 0, rw = 0, rh = 0;
            int align = 0;
            double[] custom = null;
            for (String raw : Files.readAllLines(meta)) {
                String t = raw.trim();
                if (t.startsWith("sprites:")) { inSprites = true; continue; }
                if (!inSprites) continue;
                if (t.startsWith("- ")) {                       // sang entry mới → reset
                    rx = ry = rw = rh = 0; align = 0; custom = null;
                    t = t.substring(2).trim();
                }
                if (t.startsWith("x: ")) rx = parse(t.substring(3).trim(), 0);
                else if (t.startsWith("y: ")) ry = parse(t.substring(3).trim(), 0);
                else if (t.startsWith("width: ")) rw = parse(t.substring(7).trim(), 0);
                else if (t.startsWith("height: ")) rh = parse(t.substring(8).trim(), 0);
                else if (t.startsWith("alignment: ")) {
                    try { align = Integer.parseInt(t.substring(11).trim()); } catch (Exception ignored) {}
                } else if (t.startsWith("pivot:")) {
                    Matcher m = XY.matcher(t);
                    if (m.find()) custom = new double[]{parse(m.group(1), 0.5), parse(m.group(2), 0.5)};
                } else if (t.startsWith("internalID: ")) {
                    long id;
                    try { id = Long.parseLong(t.substring(12).trim()); } catch (Exception e) { continue; }
                    if (id != internalId) continue;
                    double[] piv = (align == 9 && custom != null) ? custom : alignmentToPivot(align);
                    return new double[]{rx, ry, rw, rh, piv[0], piv[1]};
                }
            }
        } catch (IOException ignored) { }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helper đọc list nhiều dòng (PrefabDocument chỉ có getPointList cho list {x,y})
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Đọc list "- {fileID: N}" ngay dưới key (m_Children / _layersBG / _layersBG1…).
     * Trả mảng rỗng khi key viết inline "[]", null khi không có key.
     */
    private static long[] readFileIdList(PrefabDocument doc, PrefabDocument.Block b, String key) {
        int at = doc.findLine(b, key);
        if (at < 0) return null;
        List<String> ls = doc.lines();
        String head = ls.get(at);
        if (head.trim().endsWith("[]")) return new long[0];
        List<Long> out = new ArrayList<>();
        int end = Math.min(b.end, ls.size());
        for (int i = at + 1; i < end; i++) {
            String l = ls.get(i);
            if (!l.startsWith("  - ")) break;         // hết list (dòng key tiếp theo ở indent 2)
            Matcher m = FILEID.matcher(l);
            if (m.find()) out.add(Long.parseLong(m.group(1)));
        }
        long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /** guid của material đầu tiên trong "m_Materials:" của block renderer. */
    private static String firstMaterialGuid(PrefabDocument doc, PrefabDocument.Block b) {
        int at = doc.findLine(b, "m_Materials:");
        if (at < 0) return null;
        List<String> ls = doc.lines();
        int end = Math.min(b.end, ls.size());
        for (int i = at + 1; i < end; i++) {
            String l = ls.get(i);
            if (!l.startsWith("  - ")) break;
            Matcher m = GUID.matcher(l);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static double parse(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }
}
