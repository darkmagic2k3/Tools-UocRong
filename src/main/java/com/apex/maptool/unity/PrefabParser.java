package com.apex.maptool.unity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse Unity prefab (.prefab YAML multi-doc) → List&lt;MapLayer&gt;.
 *
 * Cách: split block theo "--- !u!&lt;classId&gt; &amp;&lt;fileID&gt;", parse field cần,
 * build transform tree, resolve renderer (MeshRenderer/SpriteRenderer) → texture + world rect.
 *
 * P0: bỏ qua rotation (map 2D layer hiếm xoay). Có xử lý parent scale.
 */
public final class PrefabParser {

    // --- !u!23 &123456
    private static final Pattern BLOCK = Pattern.compile("^--- !u!(\\d+) &(-?\\d+)");
    private static final Pattern FILEID = Pattern.compile("fileID:\\s*(-?\\d+)");
    private static final Pattern GUID = Pattern.compile("guid:\\s*([0-9a-fA-F]{32})");
    private static final Pattern XY = Pattern.compile("x:\\s*(-?[\\d.eE+]+),\\s*y:\\s*(-?[\\d.eE+]+)");

    private static final int CLS_GAMEOBJECT = 1;
    private static final int CLS_TRANSFORM = 4;
    private static final int CLS_MESH_RENDERER = 23;
    private static final int CLS_SPRITE_RENDERER = 212;
    private static final int CLS_BOX_COLLIDER = 61;
    private static final int CLS_EDGE_COLLIDER = 68;

    // ─── node tạm ──────────────────────────────────────────────
    private static final class Tr {
        long goId, fatherId;
        double lpx, lpy, lsx = 1, lsy = 1;
        double qx, qy, qz, qw = 1;  // local rotation quaternion
    }
    private static final class Rend {
        long goId;
        String matGuid;     // MeshRenderer
        String spriteGuid;  // SpriteRenderer
        int sortLayer, sortOrder;
        boolean flipX, flipY;
        boolean enabled = true;
        boolean isSprite;
    }
    private static final class ColEdge {
        long goId; double offx, offy; boolean trigger; List<double[]> pts = new ArrayList<>();
    }
    private static final class ColBox {
        long goId; double offx, offy, sizeX, sizeY; boolean trigger;
    }

    private final Map<Long, Tr> transforms = new HashMap<>();   // anchor → Tr
    private final Map<Long, Tr> trByGo = new HashMap<>();        // goId → Tr
    private final Map<Long, String> goName = new HashMap<>();    // goId → name
    private final Map<Long, Boolean> goActive = new HashMap<>(); // goId → m_IsActive
    private final List<Rend> renderers = new ArrayList<>();
    private final List<ColEdge> colEdges = new ArrayList<>();
    private final List<ColBox> colBoxes = new ArrayList<>();
    private final List<ColliderShape> colliders = new ArrayList<>();

    public List<ColliderShape> colliders() { return colliders; }

    private final GuidIndex guidIndex;
    private final MaterialResolver matResolver;
    private final TextureCache textureCache;
    private final int ppu;

    public PrefabParser(GuidIndex guidIndex, MaterialResolver matResolver, TextureCache textureCache, int ppu) {
        this.guidIndex = guidIndex;
        this.matResolver = matResolver;
        this.textureCache = textureCache;
        this.ppu = ppu;
    }

    public List<MapLayer> parse(Path prefab) throws IOException {
        List<String> lines = Files.readAllLines(prefab);
        parseBlocks(lines);
        buildColliders();
        return buildLayers();
    }

    private void parseBlocks(List<String> lines) {
        int i = 0;
        int n = lines.size();
        while (i < n) {
            Matcher bm = BLOCK.matcher(lines.get(i));
            if (!bm.find()) { i++; continue; }
            int classId = Integer.parseInt(bm.group(1));
            long anchor = Long.parseLong(bm.group(2));
            // gom block tới "---" tiếp theo
            int j = i + 1;
            List<String> body = new ArrayList<>();
            while (j < n && !lines.get(j).startsWith("--- ")) {
                body.add(lines.get(j));
                j++;
            }
            handleBlock(classId, anchor, body);
            i = j;
        }
    }

    private void handleBlock(int classId, long anchor, List<String> body) {
        switch (classId) {
            case CLS_GAMEOBJECT -> {
                long go = anchor; // GameObject anchor = chính nó; renderer/transform ref qua m_GameObject
                String name = findStringField(body, "m_Name:");
                if (name != null) goName.put(go, name);
                goActive.put(go, findIntField(body, "m_IsActive:", 1) != 0);
            }
            case CLS_TRANSFORM -> {
                Tr tr = new Tr();
                tr.goId = findFileId(body, "m_GameObject:");
                tr.fatherId = findFileId(body, "m_Father:");
                double[] lp = findXY(body, "m_LocalPosition:");
                if (lp != null) { tr.lpx = lp[0]; tr.lpy = lp[1]; }
                double[] ls = findXY(body, "m_LocalScale:");
                if (ls != null) { tr.lsx = ls[0]; tr.lsy = ls[1]; }
                double[] q = findQuat(body, "m_LocalRotation:");
                if (q != null) { tr.qx = q[0]; tr.qy = q[1]; tr.qz = q[2]; tr.qw = q[3]; }
                transforms.put(anchor, tr);
                if (tr.goId != 0) trByGo.put(tr.goId, tr);
            }
            case CLS_MESH_RENDERER -> {
                Rend r = new Rend();
                r.isSprite = false;
                r.goId = findFileId(body, "m_GameObject:");
                r.matGuid = findFirstMaterialGuid(body);
                r.sortLayer = findIntField(body, "m_SortingLayer:", 0);
                r.sortOrder = findIntField(body, "m_SortingOrder:", 0);
                r.enabled = findIntField(body, "m_Enabled:", 1) != 0;
                if (r.goId != 0 && r.matGuid != null) renderers.add(r);
            }
            case CLS_SPRITE_RENDERER -> {
                Rend r = new Rend();
                r.isSprite = true;
                r.goId = findFileId(body, "m_GameObject:");
                r.spriteGuid = findFieldGuid(body, "m_Sprite:");
                r.sortLayer = findIntField(body, "m_SortingLayer:", 0);
                r.sortOrder = findIntField(body, "m_SortingOrder:", 0);
                r.flipX = findIntField(body, "m_FlipX:", 0) != 0;
                r.flipY = findIntField(body, "m_FlipY:", 0) != 0;
                r.enabled = findIntField(body, "m_Enabled:", 1) != 0;
                if (r.goId != 0 && r.spriteGuid != null) renderers.add(r);
            }
            case CLS_EDGE_COLLIDER -> {
                if (findIntField(body, "m_Enabled:", 1) == 0) break;
                ColEdge c = new ColEdge();
                c.goId = findFileId(body, "m_GameObject:");
                double[] off = findXY(body, "m_Offset:");
                if (off != null) { c.offx = off[0]; c.offy = off[1]; }
                c.trigger = findIntField(body, "m_IsTrigger:", 0) != 0;
                c.pts = findPointsList(body);
                if (c.goId != 0 && !c.pts.isEmpty()) colEdges.add(c);
            }
            case CLS_BOX_COLLIDER -> {
                if (findIntField(body, "m_Enabled:", 1) == 0) break;
                ColBox c = new ColBox();
                c.goId = findFileId(body, "m_GameObject:");
                double[] off = findXY(body, "m_Offset:");
                if (off != null) { c.offx = off[0]; c.offy = off[1]; }
                double[] sz = findXY(body, "m_Size:");
                if (sz != null) { c.sizeX = sz[0]; c.sizeY = sz[1]; }
                c.trigger = findIntField(body, "m_IsTrigger:", 0) != 0;
                if (c.goId != 0 && c.sizeX > 0) colBoxes.add(c);
            }
            default -> { /* ignore */ }
        }
    }

    private void buildColliders() {
        for (ColEdge c : colEdges) {
            Tr tr = trByGo.get(c.goId);
            if (tr == null) continue;
            if (!activeInHierarchy(tr)) continue;   // collider GO inactive → bỏ
            double[] m = worldMatrix(tr);           // gồm rotation+scale+pos
            double[][] pts = new double[c.pts.size()][2];
            for (int i = 0; i < c.pts.size(); i++) {
                double px = c.pts.get(i)[0] + c.offx, py = c.pts.get(i)[1] + c.offy;
                pts[i] = applyPoint(m, px, py);
            }
            colliders.add(new ColliderShape(ColliderShape.Kind.EDGE, pts, c.trigger,
                    goName.getOrDefault(c.goId, "edge")));
        }
        for (ColBox c : colBoxes) {
            Tr tr = trByGo.get(c.goId);
            if (tr == null) continue;
            if (!activeInHierarchy(tr)) continue;   // collider GO inactive → bỏ
            double[] m = worldMatrix(tr);
            double hw = c.sizeX / 2.0, hh = c.sizeY / 2.0;
            // 4 góc local (gồm offset) → transform (rotation+scale+pos)
            double[][] pts = {
                applyPoint(m, c.offx - hw, c.offy - hh),
                applyPoint(m, c.offx + hw, c.offy - hh),
                applyPoint(m, c.offx + hw, c.offy + hh),
                applyPoint(m, c.offx - hw, c.offy + hh)
            };
            colliders.add(new ColliderShape(ColliderShape.Kind.BOX, pts, c.trigger,
                    goName.getOrDefault(c.goId, "box")));
        }
        System.out.println("[PrefabParser] colliders: " + colliders.size()
                + " (edge=" + colEdges.size() + " box=" + colBoxes.size() + ")");
    }

    /** Parse list "m_Points:" → các dòng "- {x:..,y:..}". */
    private static List<double[]> findPointsList(List<String> body) {
        List<double[]> pts = new ArrayList<>();
        boolean in = false;
        for (String l : body) {
            String t = l.trim();
            if (t.startsWith("m_Points:")) { in = true; continue; }
            if (in) {
                if (t.startsWith("- {")) {
                    Matcher m = XY.matcher(t);
                    if (m.find()) pts.add(new double[]{parse(m.group(1)), parse(m.group(2))});
                } else if (!t.isEmpty()) {
                    break; // hết list
                }
            }
        }
        return pts;
    }

    private List<MapLayer> buildLayers() {
        System.out.println("[PrefabParser] renderers tổng: " + renderers.size()
                + " (mesh+sprite). transforms: " + transforms.size());
        List<MapLayer> layers = new ArrayList<>();
        for (Rend r : renderers) {
            if (!r.enabled) { System.out.println("[skip] disabled go=" + r.goId + " '" + goName.get(r.goId) + "'"); continue; }
            Tr tr = trByGo.get(r.goId);
            if (tr == null) { System.out.println("[skip] no-transform go=" + r.goId + " '" + goName.get(r.goId) + "' (sprite=" + r.isSprite + ")"); continue; }
            if (!activeInHierarchy(tr)) continue;   // GO/ancestor inactive trong prefab → bỏ

            double[] m = worldMatrix(tr);
            double scaleX = Math.hypot(m[0], m[1]);
            double scaleY = Math.hypot(m[2], m[3]);
            double angleDeg = Math.toDegrees(Math.atan2(m[1], m[0]));

            // 180° quanh X/Y = flip (Z rotation đã vào matrix). qY=180→flipX, qX=180→flipY.
            boolean flipX = r.flipX, flipY = r.flipY;
            if (Math.abs(tr.qw) < 0.1) {
                if (Math.abs(tr.qy) > 0.9) flipX = !flipX;
                if (Math.abs(tr.qx) > 0.9) flipY = !flipY;
            }

            Path tex;
            double w, h, cx, cy;
            if (r.isSprite) {
                tex = guidIndex.resolve(r.spriteGuid);
                int[] dim = textureCache.size(tex);
                double sw = dim[0] / (double) ppu;   // local size (chưa scale)
                double sh = dim[1] / (double) ppu;
                // PIVOT: transform origin = điểm pivot. Center sprite (local) lệch theo pivot.
                double[] piv = spritePivot(tex);
                double lcx = (0.5 - piv[0]) * sw;
                double lcy = (0.5 - piv[1]) * sh;
                double[] wc = applyPoint(m, lcx, lcy);  // center world (đã gồm rotation+scale)
                cx = wc[0]; cy = wc[1];
                w = sw * scaleX;
                h = sh * scaleY;
            } else {
                tex = matResolver.resolveTexture(r.matGuid);
                double[] wc = applyPoint(m, 0, 0);  // quad center = transform origin
                cx = wc[0]; cy = wc[1];
                w = scaleX;  // quad base 1 unit
                h = scaleY;
            }
            if (tex == null) {
                System.out.println("[PrefabParser] miss texture cho " + goName.getOrDefault(r.goId, "?")
                        + " (guid=" + (r.isSprite ? r.spriteGuid : r.matGuid) + ")");
                continue;
            }
            long sortKey = (long) r.sortLayer * 1_000_000L + r.sortOrder;
            layers.add(new MapLayer(tex, cx, cy, w, h, angleDeg, sortKey, flipX, flipY,
                    goName.getOrDefault(r.goId, "?")));
        }
        layers.sort(Comparator.comparingLong(l -> l.sortKey));
        return layers;
    }

    private final Map<Path, double[]> pivotCache = new HashMap<>();

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
                        if (m.find()) custom = new double[]{parse(m.group(1)), parse(m.group(2))};
                    }
                }
                if (alignment == 9 && custom != null) piv = custom;
                else if (alignment >= 0) piv = alignmentToPivot(alignment);
            }
        } catch (IOException ignored) {}
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

    /** GO active + mọi ancestor active? (m_IsActive=0 ở bất kỳ cấp → inactive). */
    private boolean activeInHierarchy(Tr tr) {
        Tr cur = tr;
        int guard = 0;
        while (cur != null && guard++ < 256) {
            Boolean a = goActive.get(cur.goId);
            if (a != null && !a) return false;
            cur = (cur.fatherId != 0) ? transforms.get(cur.fatherId) : null;
        }
        return true;
    }

    /** Walk father chain → world center + world scale (bỏ rotation). DEPRECATED — dùng worldMatrix. */
    private double[] worldPosScale(Tr tr) {
        double[] m = worldMatrix(tr);
        return new double[]{m[4], m[5], Math.hypot(m[0], m[1]), Math.hypot(m[2], m[3])};
    }

    /**
     * Affine 2x3 world của transform: [a c e ; b d f]. point→(a*x+c*y+e, b*x+d*y+f).
     * Gồm position + rotation Z + scale qua cả father chain.
     * (180° quanh X/Y = flip, xử lý riêng; matrix chỉ lấy rotation Z.)
     */
    private double[] worldMatrix(Tr tr) {
        ArrayDeque<Tr> chain = new ArrayDeque<>();
        Tr cur = tr;
        int guard = 0;
        while (cur != null && guard++ < 256) {
            chain.push(cur);
            cur = (cur.fatherId != 0) ? transforms.get(cur.fatherId) : null;
        }
        double[] m = {1, 0, 0, 1, 0, 0}; // identity
        for (Tr t : chain) { // root → leaf
            m = mul(m, localMatrix(t));
        }
        return m;
    }

    private static double[] localMatrix(Tr t) {
        // rotation Z (chỉ khi không phải flip X/Y). quaternion (0,0,z,w) → ang=2*atan2(z,w).
        double ang = 0;
        if (Math.abs(t.qx) < 0.1 && Math.abs(t.qy) < 0.1) {
            ang = 2.0 * Math.atan2(t.qz, t.qw);
        }
        double cos = Math.cos(ang), sin = Math.sin(ang);
        // T * R * S
        return new double[]{
            cos * t.lsx,  sin * t.lsx,   // a, b
            -sin * t.lsy, cos * t.lsy,   // c, d
            t.lpx, t.lpy                  // e, f
        };
    }

    /** Compose 2x3: m ∘ n (áp n trước, rồi m). */
    private static double[] mul(double[] m, double[] n) {
        return new double[]{
            m[0] * n[0] + m[2] * n[1],          // a
            m[1] * n[0] + m[3] * n[1],          // b
            m[0] * n[2] + m[2] * n[3],          // c
            m[1] * n[2] + m[3] * n[3],          // d
            m[0] * n[4] + m[2] * n[5] + m[4],   // e
            m[1] * n[4] + m[3] * n[5] + m[5]    // f
        };
    }

    private static double[] applyPoint(double[] m, double x, double y) {
        return new double[]{m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
    }

    // ─── field helpers ─────────────────────────────────────────
    private static String findStringField(List<String> body, String key) {
        for (String l : body) {
            String t = l.trim();
            if (t.startsWith(key)) return t.substring(key.length()).trim();
        }
        return null;
    }

    private static long findFileId(List<String> body, String key) {
        for (String l : body) {
            if (l.trim().startsWith(key)) {
                Matcher m = FILEID.matcher(l);
                if (m.find()) return Long.parseLong(m.group(1));
            }
        }
        return 0;
    }

    private static int findIntField(List<String> body, String key, int def) {
        for (String l : body) {
            String t = l.trim();
            if (t.startsWith(key)) {
                String v = t.substring(key.length()).trim();
                try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
            }
        }
        return def;
    }

    private static double[] findXY(List<String> body, String key) {
        for (String l : body) {
            if (l.trim().startsWith(key)) {
                Matcher m = XY.matcher(l);
                if (m.find()) return new double[]{parse(m.group(1)), parse(m.group(2))};
            }
        }
        return null;
    }

    private static final Pattern QUAT = Pattern.compile(
            "x:\\s*(-?[\\d.eE+]+),\\s*y:\\s*(-?[\\d.eE+]+),\\s*z:\\s*(-?[\\d.eE+]+),\\s*w:\\s*(-?[\\d.eE+]+)");

    private static double[] findQuat(List<String> body, String key) {
        for (String l : body) {
            if (l.trim().startsWith(key)) {
                Matcher m = QUAT.matcher(l);
                if (m.find()) return new double[]{parse(m.group(1)), parse(m.group(2)), parse(m.group(3)), parse(m.group(4))};
            }
        }
        return null;
    }

    /** guid trong dòng cùng key, vd m_Sprite: {fileID:.., guid:.., type:..}. */
    private static String findFieldGuid(List<String> body, String key) {
        for (String l : body) {
            if (l.trim().startsWith(key)) {
                Matcher m = GUID.matcher(l);
                if (m.find()) return m.group(1);
            }
        }
        return null;
    }

    /** m_Materials: -> dòng "- {fileID:.., guid:.., type:2}" đầu tiên. */
    private static String findFirstMaterialGuid(List<String> body) {
        boolean in = false;
        for (String l : body) {
            String t = l.trim();
            if (t.startsWith("m_Materials:")) { in = true; continue; }
            if (in) {
                if (t.startsWith("- ")) {
                    Matcher m = GUID.matcher(t);
                    if (m.find()) return m.group(1);
                } else if (!t.isEmpty() && !t.startsWith("-")) {
                    // hết list materials
                    break;
                }
            }
        }
        return null;
    }

    private static double parse(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }
}
