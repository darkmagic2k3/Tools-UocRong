package com.apex.maptool.ui;

import com.apex.maptool.model.IncomingDrop;
import com.apex.maptool.model.Marker;
import com.apex.maptool.spine.SpineCharacter;
import com.apex.maptool.unity.ColliderShape;
import com.apex.maptool.unity.MapLayer;
import com.apex.maptool.unity.SpriteResolver;
import com.apex.maptool.unity.TextureCache;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Canvas vẽ map (unity units) + origin axes + marker verify.
 * Pan: kéo chuột. Zoom: lăn chuột (quanh con trỏ).
 *
 * Coord: world = unity units. server = world * PPU. screen y lật (unity y-up).
 */
public class MapCanvasPanel extends JPanel {

    /** Marker verify P0: vị trí unity + màu + nhãn. */
    public record VMarker(double ux, double uy, Color color, String label) {}

    private final TextureCache tex;
    private final int ppu;
    private List<MapLayer> layers = new ArrayList<>();
    private List<ColliderShape> colliders = new ArrayList<>();
    private boolean showColliders = true;
    private boolean showLayers = true;
    private boolean hideBg = false;
    private boolean showTriggers = false;   // ẩn collider cam (trigger) — tạm
    private boolean clipToView = true;       // chỉ paint trong khung tường (vùng player thấy)
    private final List<VMarker> markers = new ArrayList<>();
    private List<Marker> editMarkers = new ArrayList<>();
    private Marker selected;
    private SpriteResolver sprites;
    private double markerScale = 0.75;         // multiplier cỡ nhân vật (khớp Unity)
    private boolean useSpine = true;
    private boolean patrol = true;            // quái dao động qua lại (Walk)
    private float animTime = 0;
    private final long startNano = System.nanoTime();
    private static final double PATROL_RANGE = 2.5; // unit ± quanh spawn

    public void setSpriteResolver(SpriteResolver s) { sprites = s; }
    public void setUseSpine(boolean v) { useSpine = v; repaint(); }
    public void setPatrol(boolean v) { patrol = v; repaint(); }
    public void setMarkerScale(double s) { markerScale = Math.max(0.1, s); repaint(); }

    private double scale = 8.0;     // px per unit (zoom)
    private double originX = 400;   // screen px của world (0,0)
    private double originY = 400;
    private Point lastDrag;
    private Point2DWorld mouseWorld = new Point2DWorld(0, 0);

    private boolean skyBg = true;

    public MapCanvasPanel(TextureCache tex, int ppu) {
        this.tex = tex;
        this.ppu = ppu;
        setBackground(new Color(40, 40, 48));
        setupMouse();
        // animation clock ~25fps
        new javax.swing.Timer(40, e -> {
            animTime = (System.nanoTime() - startNano) / 1_000_000_000f;
            if (useSpine) repaint();
        }).start();
    }

    public void setSkyBg(boolean v) { skyBg = v; repaint(); }

    public void setLayers(List<MapLayer> layers) {
        this.layers = layers;
        repaint();
    }

    public void setMarkers(List<VMarker> ms) {
        markers.clear();
        markers.addAll(ms);
        repaint();
    }

    public void setColliders(List<ColliderShape> cs) {
        this.colliders = cs;
        repaint();
    }

    // ─── Edit markers ──────────────────────────────────────────
    public void setEditMarkers(List<Marker> ms) {
        this.editMarkers = ms;
        this.selected = null;
        repaint();
    }
    public List<Marker> getEditMarkers() { return editMarkers; }
    public Marker getSelected() { return selected; }

    // ─── Undo / Redo (Ctrl+Z, Ctrl+Y) ──────────────────────────
    private final java.util.Deque<List<Marker>> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<List<Marker>> redoStack = new java.util.ArrayDeque<>();
    private static final int UNDO_MAX = 80;
    private Runnable onReload;
    private boolean pushedThisDrag;

    public void setOnReload(Runnable r) { onReload = r; }

    private List<Marker> snapshot(List<Marker> src) {
        List<Marker> copy = new ArrayList<>(src.size());
        for (Marker m : src)
            copy.add(new Marker(m.kind, com.google.gson.JsonParser.parseString(m.raw.toString()).getAsJsonObject()));
        return copy;
    }
    /** Lưu trạng thái hiện tại vào undo — gọi TRƯỚC khi đổi marker. */
    public void pushUndo() { pushUndoOf(editMarkers); }
    /** Lưu snapshot của 1 list cụ thể (vd list map gốc khi pick điểm rớt). */
    public void pushUndoOf(List<Marker> list) {
        undoStack.push(snapshot(list));
        while (undoStack.size() > UNDO_MAX) undoStack.removeLast();
        redoStack.clear();
    }
    public void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.push(snapshot(editMarkers));
        editMarkers = undoStack.pop();
        selected = null;
        if (onReload != null) onReload.run();
        repaint();
    }
    public void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.push(snapshot(editMarkers));
        editMarkers = redoStack.pop();
        selected = null;
        if (onReload != null) onReload.run();
        repaint();
    }

    private Marker.Kind placeKind;   // null = không ở chế độ đặt
    private int placeId;
    private int placeExtra;          // npc requiredMission / gateway mapId
    private boolean snapGround = true;
    private Runnable onChange;       // callback cập nhật UI ngoài
    private Consumer<Marker> onSelect;                 // báo khi selection đổi (click canvas)
    private boolean pickMode;                          // chọn 1 điểm (vd điểm rớt map đích)
    private BiConsumer<Integer, Integer> pickCallback; // (serverX, serverY) khi pick
    private Runnable pickCancel;                       // gọi khi hủy pick (Esc)
    private Map<Integer, String> mapNames = Collections.emptyMap(); // id→tên map cho nhãn cổng
    private boolean showGatewayRange = true;       // vẽ vùng cổng (collider Gate_Prefab — 1:1 client)
    private double[] gateClickBox;                  // {cx,cy,w,h} vùng click (root Gate_Prefab)
    private double[] gateTouchBox;                  // {cx,cy,w,h} vùng chạm (EnterJoinmap) base
    // độ lệch vùng chạm theo mép (PHẢI khớp client EdgeOutSide.InitGateWay: Left=-N, Right=+N)
    private static final double GATE_DURATION_OFFSET = 4.2;

    public void setOnChange(Runnable r) { onChange = r; }
    public void setShowGatewayRange(boolean v) { showGatewayRange = v; repaint(); }

    /** Nạp collider Gate_Prefab → tách box click + box chạm (để vẽ cổng 1:1 client). */
    public void setGatewayColliders(List<ColliderShape> c) {
        gateClickBox = null; gateTouchBox = null;
        if (c != null) for (ColliderShape s : c) {
            if (s.pts == null || s.pts.length == 0) continue;
            boolean enter = s.name != null && s.name.toLowerCase().contains("enter");
            if (enter) gateTouchBox = bbox(s); else gateClickBox = bbox(s);
        }
        repaint();
    }

    private static double[] bbox(ColliderShape s) {
        double minx = 1e9, maxx = -1e9, miny = 1e9, maxy = -1e9;
        for (double[] p : s.pts) { minx = Math.min(minx, p[0]); maxx = Math.max(maxx, p[0]); miny = Math.min(miny, p[1]); maxy = Math.max(maxy, p[1]); }
        return new double[]{(minx + maxx) / 2, (miny + maxy) / 2, maxx - minx, maxy - miny};
    }
    public void setOnSelect(Consumer<Marker> c) { onSelect = c; }
    public void setMapNames(Map<Integer, String> m) { mapNames = (m == null) ? Collections.emptyMap() : m; repaint(); }

    private boolean pickHasOld;          // có điểm rơi cũ (bX/bY) để hiện không
    private double pickOldX, pickOldY;   // điểm rơi cũ (unit)

    /** Bật/tắt chế độ chọn 1 điểm. cb nhận (serverX, serverY); cancel chạy khi Esc. */
    public void setPickMode(boolean on, BiConsumer<Integer, Integer> cb, Runnable cancel) {
        pickMode = on; pickCallback = cb; pickCancel = cancel;
        if (on) { placeKind = null; selected = null; }
        if (!on) pickHasOld = false;
        setCursor(Cursor.getPredefinedCursor(on ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
    }
    public boolean isPickMode() { return pickMode; }

    /** Điểm rơi cũ (bX/bY của cổng) để hiện khi đang chọn điểm rơi mới. */
    public void setPickReference(boolean hasOld, double oldUx, double oldUy) {
        pickHasOld = hasOld; pickOldX = oldUx; pickOldY = oldUy; repaint();
    }

    /** Chọn marker từ ngoài (vd click dòng bảng cổng). */
    public void selectMarker(Marker m) { selected = m; selectedIncoming = null; repaint(); }

    /** Chọn điểm rơi vào từ ngoài (bảng danh sách). */
    public void selectIncoming(IncomingDrop d) { selectedIncoming = d; selected = null; repaint(); }

    // Khóa chọn: bật → canvas KHÔNG đổi selection khi click; chỉ vật ĐÃ chọn (từ list) mới kéo được
    private boolean selectLock;
    public void setSelectLock(boolean v) { selectLock = v; repaint(); }
    public boolean isSelectLock() { return selectLock; }

    /** Canh 1 điểm (unit) ra giữa canvas. */
    public void panTo(double ux, double uy) {
        originX = getWidth() / 2.0 - ux * scale;
        originY = getHeight() / 2.0 + uy * scale;
        repaint();
    }

    /** Canh 1 điểm (toạ độ server) ra giữa canvas. */
    public void panToServer(int sx, int sy) { panTo(sx / (double) ppu, sy / (double) ppu); }

    /** Canh marker ra giữa canvas (giữ nguyên zoom). */
    public void panToMarker(Marker m) {
        if (m == null) return;
        double ux = m.serverX() / (double) ppu, uy = m.serverY() / (double) ppu;
        originX = getWidth() / 2.0 - ux * scale;
        originY = getHeight() / 2.0 + uy * scale;
        repaint();
    }
    public void setSnapGround(boolean v) { snapGround = v; }
    public void setPlaceMode(Marker.Kind kind, int id, int extra) {
        placeKind = kind; placeId = id; placeExtra = extra;
    }
    public void clearPlaceMode() { placeKind = null; }
    public Marker.Kind placeMode() { return placeKind; }

    public void deleteSelected() {
        if (selected != null) {
            pushUndo();
            editMarkers.remove(selected);
            selected = null;
            fireChange();
            repaint();
        }
    }

    public void setShowColliders(boolean v) { showColliders = v; repaint(); }
    public void setShowLayers(boolean v) { showLayers = v; repaint(); }
    public void setHideBg(boolean v) { hideBg = v; repaint(); }
    public void setShowTriggers(boolean v) { showTriggers = v; repaint(); }
    public void setClipToView(boolean v) { clipToView = v; repaint(); }

    /**
     * Bounds vùng nhìn: Left/Right wall → X bounds; Top/Bottom wall → Y bounds.
     * (KHÔNG bbox toàn điểm — Top/Bottom trải full width sẽ ra box sai).
     * null nếu thiếu tường.
     */
    private double[] viewBounds() {
        Double leftX = null, rightX = null, topY = null, bottomY = null;
        for (ColliderShape c : colliders) {
            if (c.isTrigger || c.pts.length == 0) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            // coord đại diện của line
            double avgX = 0, avgY = 0;
            for (double[] p : c.pts) { avgX += p[0]; avgY += p[1]; }
            avgX /= c.pts.length; avgY /= c.pts.length;
            if (n.contains("left")) leftX = avgX;
            else if (n.contains("right")) rightX = avgX;
            else if (n.contains("top")) topY = avgY;
            else if (n.contains("bottom")) bottomY = avgY;
        }
        if (leftX == null || rightX == null || topY == null || bottomY == null) return null;
        return new double[]{Math.min(leftX, rightX), Math.min(bottomY, topY),
                            Math.max(leftX, rightX), Math.max(bottomY, topY)};
    }

    /** BG = sky/núi/mây (sprite nền nhỏ hơn vùng đi → trông "cắt"). Heuristic theo tên. */
    private static boolean isBgLayer(MapLayer l) {
        String n = l.debugName == null ? "" : l.debugName.toLowerCase();
        return n.contains("bg") || n.startsWith("may") || n.contains("nui")
                || n.contains("lop") || n.contains("cloud") || n.contains("sky");
    }

    /** Fit view: căn origin giữa panel, scale theo bounds layer. */
    public void fitView() {
        if (layers.isEmpty()) {
            originX = getWidth() / 2.0;
            originY = getHeight() / 2.0;
            scale = 8.0;
            repaint();
            return;
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (MapLayer l : layers) {
            minX = Math.min(minX, l.cx - l.w / 2);
            maxX = Math.max(maxX, l.cx + l.w / 2);
            minY = Math.min(minY, l.cy - l.h / 2);
            maxY = Math.max(maxY, l.cy + l.h / 2);
        }
        double w = Math.max(1, maxX - minX), h = Math.max(1, maxY - minY);
        int pw = Math.max(1, getWidth() - 40), ph = Math.max(1, getHeight() - 40);
        scale = Math.min(pw / w, ph / h);
        // center của bounds → giữa panel
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
        originX = getWidth() / 2.0 - cx * scale;
        originY = getHeight() / 2.0 + cy * scale;
        repaint();
    }

    // ─── transform ─────────────────────────────────────────────
    private double sx(double ux) { return originX + ux * scale; }
    private double sy(double uy) { return originY - uy * scale; }
    private double ux(double screenX) { return (screenX - originX) / scale; }
    private double uy(double screenY) { return (originY - screenY) / scale; }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // nền trời gradient (giống Unity scene view) thay nền đen
        if (skyBg) {
            GradientPaint sky = new GradientPaint(
                    0, 0, new Color(135, 206, 235),
                    0, getHeight(), new Color(198, 226, 235));
            g2.setPaint(sky);
            g2.fillRect(0, 0, getWidth(), getHeight());
        }

        // layers (đã sort ascending: nhỏ vẽ trước) — clip vào vùng nhìn (khung tường)
        if (showLayers) {
            Shape oldClip = g2.getClip();
            double[] vb = clipToView ? viewBounds() : null;
            if (vb != null) {
                int cl = (int) Math.round(sx(vb[0]));
                int ct = (int) Math.round(sy(vb[3]));   // maxY = top
                int cr = (int) Math.round(sx(vb[2]));
                int cbtm = (int) Math.round(sy(vb[1])); // minY = bottom
                g2.setClip(cl, ct, cr - cl, cbtm - ct);
            }
            for (MapLayer l : layers) {
                if (hideBg && isBgLayer(l)) continue;
                BufferedImage img = tex.image(l.texture);
                if (img == null) continue;
                double dw = l.w * scale;
                double dh = l.h * scale;
                double scx = sx(l.cx);   // screen center
                double scy = sy(l.cy);
                AffineTransform at = new AffineTransform();
                at.translate(scx, scy);
                // world CCW → screen CW (Y lật) → -angle
                if (l.angleDeg != 0) at.rotate(Math.toRadians(-l.angleDeg));
                double scaleX = dw / img.getWidth() * (l.flipX ? -1 : 1);
                double scaleY = dh / img.getHeight() * (l.flipY ? -1 : 1);
                at.scale(scaleX, scaleY);
                at.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0); // pivot center
                try { g2.drawImage(img, at, null); } catch (Exception ignored) {}
            }
            g2.setClip(oldClip);
        }

        if (showColliders) drawColliders(g2);
        if (showGrid) drawGrid(g2);
        if (showNpcLine) drawNpcLine(g2);
        drawMarkers(g2);
        drawEditMarkers(g2);
        drawPlaceGhost(g2);
        if (showIncomingDrops && !pickMode) drawIncomingDrops(g2);
        if (draggingMarker) drawGuides(g2);
        if (pickMode) drawPickOverlay(g2);
        drawHud(g2);
        if (pickMode) drawPickBanner(g2);
        if (rangePickMode) drawRangeOverlay(g2);
        drawFlash(g2);
        g2.dispose();
    }

    private void drawColliders(Graphics2D g2) {
        Stroke old = g2.getStroke();
        for (ColliderShape c : colliders) {
            if (c.isTrigger && !showTriggers) continue;   // ẩn trigger (cam)
            Color col = colliderColor(c);
            g2.setColor(col);
            // line mảnh 1 tia cho dễ nhìn
            g2.setStroke(new BasicStroke(1f));
            int n = c.pts.length;
            if (c.kind == ColliderShape.Kind.BOX) {
                for (int i = 0; i < n; i++) {
                    double[] a = c.pts[i], b = c.pts[(i + 1) % n];
                    g2.drawLine((int) sx(a[0]), (int) sy(a[1]), (int) sx(b[0]), (int) sy(b[1]));
                }
            } else {
                for (int i = 0; i < n - 1; i++) {
                    double[] a = c.pts[i], b = c.pts[i + 1];
                    g2.drawLine((int) sx(a[0]), (int) sy(a[1]), (int) sx(b[0]), (int) sy(b[1]));
                }
                g2.setStroke(new BasicStroke(1f));
                for (double[] p : c.pts) {
                    int px = (int) sx(p[0]), py = (int) sy(p[1]);
                    g2.fillRect(px - 2, py - 2, 4, 4);
                }
            }
            // nhãn tên collider ở điểm đầu
            if (c.pts.length > 0 && c.name != null) {
                int lx = (int) sx(c.pts[0][0]), ly = (int) sy(c.pts[0][1]);
                g2.setColor(col);
                g2.drawString(c.name, lx + 4, ly - 3);
            }
        }
        g2.setStroke(old);
    }

    private Color colliderColor(ColliderShape c) {
        if (c.isTrigger) return new Color(255, 140, 0);          // trigger = cam
        String nm = c.name == null ? "" : c.name.toLowerCase();
        if (nm.contains("ground") && !nm.contains("color")) return new Color(255, 40, 40);  // đường đi = ĐỎ
        if (nm.contains("oneway")) return new Color(80, 180, 255);  // platform 1 chiều = xanh dương
        if (nm.contains("left") || nm.contains("right") || nm.contains("top") || nm.contains("bottom"))
            return new Color(0, 0, 0);                           // tường biên = ĐEN
        return new Color(0, 200, 120);                           // còn lại = xanh
    }

    private void drawOriginAxes(Graphics2D g2) {
        g2.setColor(new Color(255, 255, 255, 90));
        int ox = (int) sx(0), oy = (int) sy(0);
        g2.drawLine(ox, 0, ox, getHeight());   // trục Y (x=0)
        g2.drawLine(0, oy, getWidth(), oy);     // trục X (y=0)
        g2.setColor(new Color(255, 230, 80));
        g2.fillOval(ox - 4, oy - 4, 8, 8);
        g2.drawString("(0,0)", ox + 6, oy - 6);
    }

    private void drawMarkers(Graphics2D g2) {
        for (VMarker m : markers) {
            int x = (int) sx(m.ux()), y = (int) sy(m.uy());
            g2.setColor(m.color());
            g2.fillOval(x - 5, y - 5, 10, 10);
            g2.setColor(Color.WHITE);
            g2.drawOval(x - 5, y - 5, 10, 10);
            if (m.label() != null) {
                g2.setColor(m.color());
                g2.drawString(m.label(), x + 7, y - 7);
            }
        }
    }

    private void drawEditMarkers(Graphics2D g2) {
        Map<Marker, double[]> gwTouch = (showGatewayRange && gateTouchBox != null) ? computeGatewayTouch() : null;
        for (Marker m : editMarkers) {
            if (m.kind == Marker.Kind.ARRIVE) continue;   // không hiện điểm đứng (vẫn giữ data khi lưu)
            double ux = m.serverX() / (double) ppu, uy = m.serverY() / (double) ppu;
            int x = (int) sx(ux), y = (int) sy(uy);
            Color col = markerColor(m.kind);

            // 0) Cổng: vẽ vùng collider Gate_Prefab 1:1 client (click cố định + chạm theo duration/SetGateLine)
            if (m.kind == Marker.Kind.GATEWAY && showGatewayRange && gateClickBox != null) {
                boolean sel = (m == selected);
                // vùng click (root) — cố định tại aX/aY
                drawGateBox(g2, ux, uy, gateClickBox, sel,
                        new Color(255, 205, 90, sel ? 60 : 36), sel ? Color.YELLOW : new Color(255, 195, 75));
                // vùng chạm (EnterJoinmap) — đã tính duration x-offset + SetGateLine merge
                double[] tb = (gwTouch != null) ? gwTouch.get(m) : null;
                if (tb == null) tb = gateTouchBox;
                int topYpx = (int) sy(uy + tb[1] + tb[3] / 2);
                drawGateBox(g2, ux, uy, tb, sel,
                        new Color(255, 140, 0, sel ? 60 : 38), sel ? Color.YELLOW : new Color(255, 140, 0));
                g2.setColor(col);
                g2.fillOval(x - 3, y - 3, 6, 6);            // tâm aX/aY (gốc cổng)
                g2.drawString(gatewayLabel(m), x + 6, topYpx - 4);
                continue;
            }

            // 1) Spine animated (ưu tiên) — nhân vật thật + animation
            SpineCharacter spc = null;
            if (useSpine && sprites != null) {
                if (m.kind == Marker.Kind.ENEMY) spc = sprites.spineEnemy(m.mainId());
                else if (m.kind == Marker.Kind.NPC) spc = sprites.spineNpc(m.mainId());
            }
            if (spc != null) {
                double worldH = spc.worldHeight();                       // = skelHeight × assetScale (real units)
                double ratio = (m.kind == Marker.Kind.ENEMY) ? 0.9 : 1.0; // enemy SetSize(0.9)
                double heightPx = worldH * ratio * markerScale * scale;
                double dX = x, dY = y;
                boolean flip = false;
                com.apex.maptool.spine.SpineData.Animation anim;

                float timeScale = 1f;
                if (m.kind == Marker.Kind.ENEMY) {
                    double[] mv = patrol ? enemyMove(m, ux, uy) : null; // {gx, gy, dir, walking} | null=đứng
                    if (mv == null) {
                        anim = spc.pickAnim("Idle", "Walk");
                    } else {
                        dX = sx(mv[0]); dY = sy(mv[1]); flip = mv[2] < 0;
                        boolean walking = mv[3] > 0.5;
                        anim = walking ? spc.pickAnim("Walk", "Run", "Idle") : spc.pickAnim("Idle", "Walk");
                        if (walking) timeScale = 0.5f; // Unity chạy Walk 0.5x
                    }
                } else {
                    anim = spc.pickAnim("Idle", "Walk");
                }

                spc.render(g2, dX, dY, heightPx, animTime, anim, flip, timeScale);
                if (m == selected) {
                    g2.setColor(Color.YELLOW);
                    g2.setStroke(new BasicStroke(2f));
                    int hp = (int) heightPx;
                    g2.drawRect((int) dX - hp / 2, (int) dY - hp, hp, hp);
                }
                g2.setColor(col);
                g2.fillOval(x - 3, y - 3, 6, 6); // chấm gốc spawn (cố định)
                continue;
            }

            // 2) Icon 2D (enemy IconEnemy; npc NpcBigIcon) — fallback dot
            BufferedImage icon = null;
            if (sprites != null) {
                if (m.kind == Marker.Kind.ENEMY) icon = sprites.enemy(m.mainId());
                else if (m.kind == Marker.Kind.NPC) icon = sprites.npc(m.mainId());
            }
            if (icon != null) {
                // Kích thước world thật → scale theo zoom. Chân icon tại spawn.
                int ih = Math.max(8, (int) (1.5 * markerScale * scale));
                int iw = ih * icon.getWidth() / Math.max(1, icon.getHeight());
                g2.drawImage(icon, x - iw / 2, y - ih, iw, ih, null);
                if (m == selected) {
                    g2.setColor(Color.YELLOW);
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRect(x - iw / 2 - 2, y - ih - 2, iw + 4, ih + 4);
                }
                g2.setColor(col);
                g2.fillOval(x - 2, y - 2, 4, 4); // chấm gốc spawn
                continue;
            }

            int r = 6;
            g2.setColor(col);
            g2.fillOval(x - r, y - r, r * 2, r * 2);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(x - r, y - r, r * 2, r * 2);
            if (m == selected) {
                g2.setColor(Color.YELLOW);
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(x - r - 4, y - r - 4, (r + 4) * 2, (r + 4) * 2);
            }
            g2.setColor(col);
            String lbl = switch (m.kind) {
                case ENEMY -> "Q" + m.mainId();
                case NPC -> "N" + m.mainId();
                case ARRIVE -> "Đứng";
                case GATEWAY -> gatewayLabel(m);
            };
            g2.drawString(lbl, x + r + 2, y - r);
            // Lưu ý: bX/bY là điểm rớt ở MAP ĐÍCH (map khác) → không vẽ trên canvas map hiện tại.
        }
    }

    private static Color markerColor(Marker.Kind k) {
        return switch (k) {
            case ENEMY -> new Color(255, 60, 60);
            case NPC -> new Color(60, 220, 90);
            case ARRIVE -> new Color(60, 200, 255);
            case GATEWAY -> new Color(255, 150, 40);
        };
    }

    /** Snap Y theo Ground collider tại x (unity). Trả uy ground, hoặc giữ nguyên nếu không tìm. */
    private double snapYToGround(double ux, double fallbackUy) {
        ColliderShape ground = null;
        for (ColliderShape c : colliders) {
            if (c.isTrigger) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            if (n.contains("ground") && !n.contains("color")) { ground = c; break; }
        }
        if (ground == null || ground.pts.length < 2) return fallbackUy;
        // tìm segment chứa ux, nội suy y
        for (int i = 0; i < ground.pts.length - 1; i++) {
            double ax = ground.pts[i][0], ay = ground.pts[i][1];
            double bx = ground.pts[i + 1][0], by = ground.pts[i + 1][1];
            double lo = Math.min(ax, bx), hi = Math.max(ax, bx);
            if (ux >= lo && ux <= hi && Math.abs(bx - ax) > 1e-6) {
                double t = (ux - ax) / (bx - ax);
                return ay + t * (by - ay);
            }
        }
        return fallbackUy;
    }

    /**
     * Vị trí + hướng quái đang patrol (khớp Unity ReturnPosMoveEnemy). null = đứng im.
     * Trả {unityX, unityY, dir(+1/-1)}.
     */
    private double[] enemyMove(Marker m, double ux, double uy) {
        if (sprites == null) return null;
        int id = m.mainId();
        if (sprites.enemyStatic(id)) return null;

        if (sprites.enemyFly(id)) {
            double[] cy = patrolCycle(ux - 2.0, ux + 2.0, m.serverX());
            double xRel = cy[0] - ux;
            return new double[]{cy[0], uy - Math.abs(xRel) / 2.0, cy[1], cy[2]};
        }

        int step = sprites.enemyStep(id);
        double minX = Double.NaN, maxX = Double.NaN; int cnt = 0;
        for (int i = -step; i <= step; i++) {
            double gx = ux + i;
            double gy = groundYNear(gx, uy);
            if (!Double.isNaN(gy)) {
                minX = Double.isNaN(minX) ? gx : Math.min(minX, gx);
                maxX = Double.isNaN(maxX) ? gx : Math.max(maxX, gx);
                cnt++;
            }
        }
        if (cnt <= 1) return null; // ≤1 điểm → đứng im
        double[] cy = patrolCycle(minX, maxX, m.serverX());
        double gy = groundYNear(cy[0], uy);
        return new double[]{cy[0], Double.isNaN(gy) ? uy : gy, cy[1], cy[2]};
    }

    /**
     * Chu kỳ patrol Unity: đi minX→maxX → NGHỈ (idle) → đi về → NGHỈ. {x, dir(+/-), walking(1/0)}.
     */
    private double[] patrolCycle(double minX, double maxX, long seed) {
        double R = maxX - minX;
        if (R < 0.01) return new double[]{minX, 1, 0};
        double walkSpeed = 1.1;                    // unit/sec (chậm hơn, giống Unity)
        double walkDur = Math.max(0.4, R / walkSpeed);
        double idleDur = 1.6;                       // nghỉ 1.6s mỗi đầu
        double cycle = 2 * walkDur + 2 * idleDur;
        double phase0 = (Math.abs(seed) % 997) / 997.0 * cycle;
        double tt = (animTime + phase0) % cycle;
        if (tt < walkDur)                      return new double[]{minX + R * (tt / walkDur), 1, 1};
        tt -= walkDur;
        if (tt < idleDur)                      return new double[]{maxX, 1, 0};
        tt -= idleDur;
        if (tt < walkDur)                      return new double[]{maxX - R * (tt / walkDur), -1, 1};
        return new double[]{minX, -1, 0};
    }

    /**
     * Y mặt đất/oneway tại x, GẦN refY (raycast xuống từ refY+1 range 3 như Unity).
     * Chọn surface cao nhất trong [refY-2, refY+1]. NaN nếu không có → không nhảy xuống tầng khác.
     */
    private double groundYNear(double ux, double refY) {
        double top = refY + 1, bottom = refY - 2;
        double best = Double.NaN;
        for (ColliderShape c : colliders) {
            if (c.isTrigger) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            if (!(n.contains("ground") && !n.contains("color")) && !n.contains("oneway")) continue;
            for (int i = 0; i < c.pts.length - 1; i++) {
                double ax = c.pts[i][0], ay = c.pts[i][1], bx = c.pts[i + 1][0], by = c.pts[i + 1][1];
                double lo = Math.min(ax, bx), hi = Math.max(ax, bx);
                if (ux >= lo && ux <= hi && Math.abs(bx - ax) > 1e-6) {
                    double t = (ux - ax) / (bx - ax);
                    double y = ay + t * (by - ay);
                    if (y <= top && y >= bottom && (Double.isNaN(best) || y > best)) best = y;
                }
            }
        }
        return best;
    }

    private Marker markerAt(double ux, double uy) {
        double tol = 10 / scale; // 10px tolerance → unity
        Marker best = null; double bestD = tol;
        for (Marker m : editMarkers) {
            if (m.kind == Marker.Kind.ARRIVE) continue;   // arrive ẩn → không chọn được
            double mx = m.serverX() / (double) ppu, my = m.serverY() / (double) ppu;
            double d = Math.hypot(mx - ux, my - uy);
            if (d <= bestD) { bestD = d; best = m; }
        }
        return best;
    }

    private void fireChange() { if (onChange != null) onChange.run(); }
    private void fireSelect() { if (onSelect != null) onSelect.accept(selected); }

    private String gatewayLabel(Marker m) {
        int id = m.mainId();
        String nm = mapNames.get(id);
        String tip = (m.getInt("type") == 1) ? " (chạm)" : "";
        return "→ " + (nm != null ? nm + " (" + id + ")" : "map " + id) + tip;
    }

    /** Vẽ 1 box (axis-aligned) tại gốc cổng (ux,uy) + offset box{cx,cy,w,h} (unit). */
    private void drawGateBox(Graphics2D g2, double ux, double uy, double[] b, boolean sel, Color fill, Color stroke) {
        int lpx = (int) sx(ux + b[0] - b[2] / 2), rpx = (int) sx(ux + b[0] + b[2] / 2);
        int tpy = (int) sy(uy + b[1] + b[3] / 2), bpy = (int) sy(uy + b[1] - b[3] / 2);
        int rx = Math.min(lpx, rpx), ry = Math.min(tpy, bpy);
        int rw = Math.abs(rpx - lpx), rh = Math.abs(bpy - tpy);
        g2.setColor(fill);
        g2.fillRect(rx, ry, rw, rh);
        g2.setColor(stroke);
        g2.setStroke(new BasicStroke(sel ? 2.5f : 1.2f));
        g2.drawRect(rx, ry, rw, rh);
    }

    /** Điểm đại diện tường biên Trái/Phải (x,y unit). null nếu thiếu. */
    private double[] leftRightWall() {
        Double lx = null, ly = null, rx = null, ry = null;
        for (ColliderShape c : colliders) {
            if (c.isTrigger || c.pts.length == 0) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            double ax = 0, ay = 0;
            for (double[] p : c.pts) { ax += p[0]; ay += p[1]; }
            ax /= c.pts.length; ay /= c.pts.length;
            if (n.contains("left")) { lx = ax; ly = ay; }
            else if (n.contains("right")) { rx = ax; ry = ay; }
        }
        if (lx == null || rx == null) return null;
        return new double[]{lx, ly, rx, ry};
    }

    /**
     * Tính box vùng chạm từng cổng — port 1:1 MapManager.UpdateGateWarp:
     * duration L/R/C (FindNearestGateIndexes) → dịch localPos.x ±2; cổng trùng mapId → SetGateLine nối collider.
     * Trả marker → {cx,cy,w,h} (unit, offset so với gốc aX/aY).
     */
    private Map<Marker, double[]> computeGatewayTouch() {
        Map<Marker, double[]> out = new HashMap<>();
        List<Marker> gws = new ArrayList<>();
        for (Marker m : editMarkers) if (m.kind == Marker.Kind.GATEWAY) gws.add(m);
        if (gws.isEmpty()) return out;
        double bcx = gateTouchBox[0], bcy = gateTouchBox[1], bw = gateTouchBox[2], bh = gateTouchBox[3];

        // FindNearestGateIndexes (bỏ qua type==0)
        int leftIndex = -1, rightIndex = -1;
        double[] wall = leftRightWall();
        if (wall != null) {
            if (gws.size() == 1) {
                Marker g = gws.get(0);
                if (g.getInt("type") != 0) {
                    double gx = g.serverX() / (double) ppu;
                    if (Math.abs(gx - wall[0]) < Math.abs(gx - wall[2])) leftIndex = 0; else rightIndex = 0;
                }
            } else {
                double minL = Double.MAX_VALUE, minR = Double.MAX_VALUE;
                for (int i = 0; i < gws.size(); i++) {
                    Marker g = gws.get(i);
                    if (g.getInt("type") == 0) continue;
                    double gx = g.serverX() / (double) ppu, gy = g.serverY() / (double) ppu;
                    double dL = Math.hypot(wall[0] - gx, wall[1] - gy);
                    double dR = Math.hypot(wall[2] - gx, wall[3] - gy);
                    if (dL < minL) { minL = dL; leftIndex = i; }
                    if (dR < minR) { minR = dR; rightIndex = i; }
                }
            }
        }
        // 1 cổng vừa gần mép trái nhất VỪA gần mép phải nhất (vd map chỉ 1 cổng chạm)
        // → chọn mép GẦN HƠN, tránh luôn bị Left.
        if (leftIndex != -1 && leftIndex == rightIndex) {
            Marker g = gws.get(leftIndex);
            double gx = g.serverX() / (double) ppu, gy = g.serverY() / (double) ppu;
            double dL = Math.hypot(wall[0] - gx, wall[1] - gy), dR = Math.hypot(wall[2] - gx, wall[3] - gy);
            if (dL <= dR) rightIndex = -1; else leftIndex = -1;
        }
        double[] dur = new double[gws.size()];
        for (int i = 0; i < gws.size(); i++)
            dur[i] = (i == leftIndex) ? -GATE_DURATION_OFFSET : (i == rightIndex) ? GATE_DURATION_OFFSET : 0;

        // UpdateGateWarp loop: _edgeOutside.Add trước, rồi check trùng idTele → SetGateLine
        List<Integer> idMaps = new ArrayList<>();
        List<Marker> edge = new ArrayList<>();
        List<Double> edgeDur = new ArrayList<>();
        for (int i = 0; i < gws.size(); i++) {
            Marker g = gws.get(i);
            double ux = g.serverX() / (double) ppu;
            double cx = bcx + dur[i];        // InitGateWay: enter.localPos.x = duration
            double w = bw;
            int idTele = g.mainId();
            edge.add(g);
            edgeDur.add(dur[i]);
            int idx = idMaps.indexOf(idTele);
            if (idx < 0) {
                idMaps.add(idTele);
            } else {
                Marker first = edge.get(idx);                  // _edgeOutside[idMaps.IndexOf(idTele)]
                double pointA = ux;                            // thisGate.transform.position.x
                double pointB = first.serverX() / (double) ppu + bcx + edgeDur.get(idx); // firstGate.enter.position.x
                double length = pointB - pointA;
                w = Math.abs(length) + 10.0;                   // EnterEdgeOutSide.SetGateLine: |length| + _offset(10)
                cx = bcx + dur[i] + length / 2.0;              // offset.x += half
            }
            out.put(g, new double[]{cx, bcy, w, bh});
        }
        return out;
    }

    private String mapLabel = "";
    public void setMapLabel(String s) { mapLabel = s == null ? "" : s; repaint(); }

    // điểm rơi VÀO map hiện tại (bX/bY của cổng map khác target vào đây) — kéo được để sửa bX/bY map nguồn
    private boolean showIncomingDrops = true;
    private List<IncomingDrop> incomingDrops = new ArrayList<>();
    private IncomingDrop selectedIncoming;
    private boolean draggingIncoming;
    private Runnable onIncomingChange;
    public void setShowIncomingDrops(boolean v) { showIncomingDrops = v; repaint(); }
    public void setIncomingDrops(List<IncomingDrop> pts) {
        incomingDrops = pts != null ? pts : new ArrayList<>(); selectedIncoming = null; repaint();
    }
    public void setOnIncomingChange(Runnable r) { onIncomingChange = r; }
    public List<IncomingDrop> getIncomingDrops() { return incomingDrops; }

    private IncomingDrop incomingAt(double ux, double uy) {
        if (!showIncomingDrops) return null;
        double tol = 10 / scale; IncomingDrop best = null; double bd = tol;
        for (IncomingDrop d : incomingDrops) {
            double dist = Math.hypot(d.serverX / (double) ppu - ux, d.serverY / (double) ppu - uy);
            if (dist <= bd) { bd = dist; best = d; }
        }
        return best;
    }

    private void drawIncomingDrops(Graphics2D g2) {
        SpineCharacter pc = (sprites != null) ? sprites.spinePlayer() : null;
        for (IncomingDrop d : incomingDrops) {
            double ux = d.serverX / (double) ppu, uy = d.serverY / (double) ppu;
            boolean sel = (d == selectedIncoming);
            if (pc != null) drawPlayerSpine(g2, ux, uy, pc, sel ? 0.85f : 0.5f);
            int x = (int) sx(ux), y = (int) sy(uy);
            g2.setColor(d.dirty ? new Color(255, 170, 60) : new Color(225, 80, 255)); // cam = đã sửa chưa lưu
            g2.fillOval(x - 4, y - 4, 8, 8);
            g2.setColor(sel ? Color.YELLOW : Color.WHITE);
            g2.setStroke(new BasicStroke(sel ? 2f : 1f));
            g2.drawOval(x - 5, y - 5, 10, 10);
            int src = d.srcMapId;
            String nm = mapNames.get(src);
            g2.setColor(new Color(230, 140, 255));
            g2.drawString("← " + (nm != null ? nm + " (" + src + ")" : "map " + src) + (d.dirty ? " *" : ""), x + 7, y - 5);
        }
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 175));
        g2.fillRect(4, 4, 360, 78);
        int sxv = (int) Math.round(mouseWorld.x * ppu);
        int syv = (int) Math.round(mouseWorld.y * ppu);
        g2.setColor(new Color(140, 215, 255));
        g2.drawString(mapLabel, 12, 20);
        g2.setColor(Color.WHITE);
        g2.drawString(String.format("unity: (%.2f, %.2f)   zoom: %.1f px/unit", mouseWorld.x, mouseWorld.y, scale), 12, 38);
        g2.drawString(String.format("server (×%d): (%d, %d)", ppu, sxv, syv), 12, 54);
        g2.drawString("layers: " + layers.size() + "   [drag=pan · wheel=zoom · Ctrl+Z undo]", 12, 70);
    }

    /** Overlay khi chọn điểm rơi: nhân vật player (spine) tại con trỏ + tại điểm rơi cũ, kèm toạ độ. */
    private void drawPickOverlay(Graphics2D g2) {
        SpineCharacter pc = (sprites != null) ? sprites.spinePlayer() : null;
        // điểm rơi CŨ (bX/bY hiện tại của cổng)
        if (pickHasOld) {
            if (pc != null) drawPlayerSpine(g2, pickOldX, pickOldY, pc, 0.4f);
            int ox = (int) sx(pickOldX), oy = (int) sy(pickOldY);
            g2.setColor(new Color(120, 200, 255));
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawLine(ox - 7, oy, ox + 7, oy);
            g2.drawLine(ox, oy - 7, ox, oy + 7);
            g2.drawString(String.format("rơi CŨ (%d, %d)", (int) Math.round(pickOldX * ppu), (int) Math.round(pickOldY * ppu)), ox + 8, oy - 6);
        }
        // điểm rơi MỚI tại con trỏ
        if (pc != null) drawPlayerSpine(g2, mouseWorld.x, mouseWorld.y, pc, 1f);
        int cx = (int) sx(mouseWorld.x), cy = (int) sy(mouseWorld.y);
        g2.setColor(new Color(255, 235, 120));
        g2.fillOval(cx - 3, cy - 3, 6, 6);
        g2.drawString(String.format("rơi MỚI (%d, %d)", (int) Math.round(mouseWorld.x * ppu), (int) Math.round(mouseWorld.y * ppu)), cx + 8, cy + 16);
    }

    private void drawPlayerSpine(Graphics2D g2, double ux, double uy, SpineCharacter pc, float alpha) {
        double heightPx = pc.worldHeight() * markerScale * scale;
        int fx = (int) sx(ux), fy = (int) sy(uy);
        Composite old = g2.getComposite();
        if (alpha < 1f) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        var anim = pc.pickAnim("Idle", "idle", "Walk", "stand");
        pc.render(g2, fx, fy, heightPx, animTime, anim, false, 1f);
        g2.setComposite(old);
    }

    private void drawPickBanner(Graphics2D g2) {
        String msg = "CHỌN ĐIỂM RỚT ở map đích — click để chọn, Esc để hủy";
        Font old = g2.getFont();
        g2.setFont(old.deriveFont(Font.BOLD, 14f));
        int w = g2.getFontMetrics().stringWidth(msg);
        int cx = Math.max(8, (getWidth() - w) / 2);
        g2.setColor(new Color(0, 0, 0, 190));
        g2.fillRect(cx - 12, 8, w + 24, 30);
        g2.setColor(new Color(255, 220, 80));
        g2.drawString(msg, cx, 28);
        g2.setFont(old);
    }

    private boolean draggingMarker = false;
    private final List<double[]> activeGuides = new ArrayList<>(); // {0=dọc/1=ngang, coordUnit} khi kéo

    /**
     * Snap kéo theo smart-guide, căn theo CẢ Ô VÙNG cổng (mép/tâm box click + box chạm),
     * không chỉ điểm gốc. Trả {originX, originY} unit.
     */
    private double[] snapWithGuides(Marker self, double rx, double ry) {
        activeGuides.clear();
        double thr = 9 / scale;
        // điểm tham chiếu của marker (offset so với gốc): X = mép/tâm box, Y = mép/tâm box
        List<Double> relX = new ArrayList<>(), relY = new ArrayList<>();
        relX.add(0.0); relY.add(0.0);            // chính điểm gốc
        if (self != null && self.kind == Marker.Kind.GATEWAY && gateClickBox != null) {
            addBoxRel(relX, relY, gateClickBox);
            double[] tb = touchBoxFor(self);
            addBoxRel(relX, relY, tb != null ? tb : gateTouchBox);
        }
        List<Double> tgX = guideTargetsX(self);
        List<Double> tgY = guideTargetsY(self, rx);

        Double originX = null, lineX = null; double bx = thr;
        for (double off : relX) for (double t : tgX) {
            double d = Math.abs((rx + off) - t);
            if (d < bx) { bx = d; originX = t - off; lineX = t; }
        }
        Double originY = null, lineY = null; double by = thr;
        for (double off : relY) for (double t : tgY) {
            double d = Math.abs((ry + off) - t);
            if (d < by) { by = d; originY = t - off; lineY = t; }
        }
        if (lineX != null) activeGuides.add(new double[]{0, lineX});
        if (lineY != null) activeGuides.add(new double[]{1, lineY});
        return new double[]{originX != null ? originX : rx, originY != null ? originY : ry};
    }

    /** Thêm mép-trái/tâm/mép-phải (X) và mép-dưới/tâm/mép-trên (Y) của box{cx,cy,w,h} vào danh sách offset. */
    private static void addBoxRel(List<Double> relX, List<Double> relY, double[] b) {
        if (b == null) return;
        relX.add(b[0] - b[2] / 2); relX.add(b[0]); relX.add(b[0] + b[2] / 2);
        relY.add(b[1] - b[3] / 2); relY.add(b[1]); relY.add(b[1] + b[3] / 2);
    }

    private double[] touchBoxFor(Marker m) {
        if (gateTouchBox == null) return null;
        double[] t = computeGatewayTouch().get(m);
        return t != null ? t : gateTouchBox;
    }

    /** Các đường gióng X: tâm map + tâm đoạn ground/oneway + X gốc marker khác. */
    private List<Double> guideTargetsX(Marker self) {
        List<Double> out = new ArrayList<>();
        double[] vb = viewBounds();
        if (vb != null) out.add((vb[0] + vb[2]) / 2);
        for (Marker m : editMarkers) {
            if (m == self || m.kind == Marker.Kind.ARRIVE) continue;
            out.add(m.serverX() / (double) ppu);
        }
        for (ColliderShape c : colliders) {
            if (c.isTrigger) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            if (!(n.contains("ground") && !n.contains("color")) && !n.contains("oneway")) continue;
            for (int i = 0; i < c.pts.length - 1; i++) out.add((c.pts[i][0] + c.pts[i + 1][0]) / 2);
        }
        return out;
    }

    /** Các đường gióng Y: tâm map + mặt ground/oneway tại x + Y gốc marker khác. */
    private List<Double> guideTargetsY(Marker self, double rx) {
        List<Double> out = new ArrayList<>();
        double[] vb = viewBounds();
        if (vb != null) out.add((vb[1] + vb[3]) / 2);
        for (Marker m : editMarkers) {
            if (m == self || m.kind == Marker.Kind.ARRIVE) continue;
            out.add(m.serverY() / (double) ppu);
        }
        for (ColliderShape c : colliders) {
            if (c.isTrigger) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            if (!(n.contains("ground") && !n.contains("color")) && !n.contains("oneway")) continue;
            for (int i = 0; i < c.pts.length - 1; i++) {
                double ax = c.pts[i][0], ay = c.pts[i][1], bx = c.pts[i + 1][0], by = c.pts[i + 1][1];
                double lo = Math.min(ax, bx), hi = Math.max(ax, bx);
                if (rx >= lo && rx <= hi && Math.abs(bx - ax) > 1e-6)
                    out.add(ay + (rx - ax) / (bx - ax) * (by - ay));
            }
        }
        return out;
    }

    private void drawGuides(Graphics2D g2) {
        if (activeGuides.isEmpty()) return;
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{6, 4}, 0));
        g2.setColor(new Color(255, 0, 200, 190));
        for (double[] gd : activeGuides) {
            if (gd[0] == 0) { int X = (int) sx(gd[1]); g2.drawLine(X, 0, X, getHeight()); }
            else { int Y = (int) sy(gd[1]); g2.drawLine(0, Y, getWidth(), Y); }
        }
        g2.setStroke(old);
    }

    private static final double FLY_PLACE_HEIGHT = 2.5;   // quái bay rải tự động: cao trên ground (unit)

    // ─── chọn vùng rải (kéo chữ nhật) ──────────────────────────
    private boolean rangePickMode;
    private double rangeStartX = Double.NaN, rangeStartY, rangeCurX, rangeCurY;
    private RectCallback rangeCallback;

    /** Callback vùng chữ nhật (unit): x0<x1, y0<y1. */
    public interface RectCallback { void accept(double x0, double y0, double x1, double y1); }

    /** Bật chế độ kéo-chọn-vùng chữ nhật. cb nhận (x0,y0,x1,y1) unit khi thả chuột. Esc hủy. */
    public void setRangePickMode(RectCallback cb) {
        rangePickMode = true; rangeCallback = cb; rangeStartX = Double.NaN;
        placeKind = null; selected = null;
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        repaint();
    }
    private void exitRangePick() {
        rangePickMode = false; rangeCallback = null; rangeStartX = Double.NaN;
        setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        repaint();
    }

    private void drawRangeOverlay(Graphics2D g2) {
        String msg = "KÉO chọn VÙNG rải (chữ nhật) — thả chuột để rải, Esc hủy";
        Font of = g2.getFont();
        g2.setFont(of.deriveFont(Font.BOLD, 14f));
        int w = g2.getFontMetrics().stringWidth(msg);
        int cx = Math.max(8, (getWidth() - w) / 2);
        g2.setColor(new Color(0, 90, 30, 200));
        g2.fillRect(cx - 12, 8, w + 24, 30);
        g2.setColor(new Color(180, 255, 190));
        g2.drawString(msg, cx, 28);
        g2.setFont(of);
        if (!Double.isNaN(rangeStartX)) {
            int a = (int) sx(Math.min(rangeStartX, rangeCurX));
            int b = (int) sx(Math.max(rangeStartX, rangeCurX));
            int t = (int) sy(Math.max(rangeStartY, rangeCurY));
            int btm = (int) sy(Math.min(rangeStartY, rangeCurY));
            g2.setColor(new Color(60, 220, 90, 50));
            g2.fillRect(a, t, b - a, btm - t);
            g2.setColor(new Color(60, 220, 90));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(a, t, b - a, btm - t);
        }
    }

    /**
     * Rải count quái CHIA ĐỀU trong vùng chữ nhật. Quái đất: dính ground NẰM TRONG vùng
     * (chọn đúng tầng); ground ngoài vùng → bỏ điểm. Quái bay: đặt tại tâm Y vùng.
     */
    public int autoPlaceEnemiesInRect(int infoId, int count, double x0, double y0, double x1, double y1) {
        if (x1 <= x0) return 0;
        pushUndo();
        int placed = 0;
        boolean fly = sprites != null && sprites.enemyFly(infoId);
        double midY = (y0 + y1) / 2;
        double spacing = count > 1 ? (x1 - x0) / (count - 1) : 0;
        for (int i = 0; i < count; i++) {
            double gx = (count > 1) ? x0 + i * spacing : (x0 + x1) / 2;
            double gy;
            if (fly) {
                gy = midY;                              // bay → tâm Y vùng chọn
            } else {
                gy = groundYAtNearest(gx, midY);        // đất → ground gần tâm vùng
                if (Double.isNaN(gy) || gy < y0 || gy > y1) continue; // không có/ngoài vùng → bỏ
            }
            Marker m = Marker.create(Marker.Kind.ENEMY, (int) Math.round(gx * ppu), (int) Math.round(gy * ppu));
            m.setMainId(infoId);
            editMarkers.add(m);
            placed++;
        }
        if (placed == 0) undoStack.pop();
        fireChange();
        repaint();
        return placed;
    }

    /**
     * Rải quái TỰ ĐỘNG: cách đều spacing (unit) trên ground, từ tường trái → phải (margin 2u).
     * Điểm không có đất → bỏ qua. 1 undo cho cả đợt. Trả số quái đã đặt.
     */
    public int autoPlaceEnemies(int infoId, int count, double spacingUnit) {
        double[] vb = viewBounds();
        double x0, x1;
        if (vb != null) { x0 = vb[0] + 2; x1 = vb[2] - 2; }
        else if (!colliders.isEmpty()) {
            x0 = Double.MAX_VALUE; x1 = -Double.MAX_VALUE;
            for (ColliderShape c : colliders) for (double[] p : c.pts) { x0 = Math.min(x0, p[0]); x1 = Math.max(x1, p[0]); }
            x0 += 2; x1 -= 2;
        } else return 0;
        pushUndo();
        int placed = 0;
        boolean fly = sprites != null && sprites.enemyFly(infoId);
        // canh giữa: tổng bề rộng dùng = (count-1)*spacing, bắt đầu từ giữa trừ nửa
        double width = (count - 1) * spacingUnit;
        double start = Math.max(x0, (x0 + x1) / 2 - width / 2);
        for (int i = 0; i < count; i++) {
            double gx = start + i * spacingUnit;
            if (gx > x1) break;
            double gy = groundYAtNearest(gx, 0);
            if (Double.isNaN(gy)) continue;          // không có đất → bỏ điểm
            if (fly) gy += FLY_PLACE_HEIGHT;         // quái bay → lơ lửng trên ground
            Marker m = Marker.create(Marker.Kind.ENEMY, (int) Math.round(gx * ppu), (int) Math.round(gy * ppu));
            m.setMainId(infoId);
            editMarkers.add(m);
            placed++;
        }
        if (placed == 0) { undoStack.pop(); }        // không đặt gì → bỏ undo rỗng
        fireChange();
        repaint();
        return placed;
    }

    /** Phím "đặt/kéo tự do": Shift hoặc Alt (Alt trên Windows hay bị window-menu chiếm → thêm Shift). */
    private static boolean isFreeKey(MouseEvent e) {
        return e.isShiftDown() || e.isAltDown() || e.isAltGraphDown();
    }

    private boolean snapKind(Marker.Kind k) {
        return snapGround && (k == Marker.Kind.ENEMY || k == Marker.Kind.NPC || k == Marker.Kind.ARRIVE);
    }

    /**
     * Y mặt đất (ground/oneway) tại x, chọn tầng GẦN refY nhất (map nhiều tầng).
     * NaN nếu không có đất tại x — dùng để CHẶN đặt quái/NPC lơ lửng.
     */
    private double groundYAtNearest(double ux, double refY) {
        double best = Double.NaN, bestD = Double.MAX_VALUE;
        for (ColliderShape c : colliders) {
            if (c.isTrigger) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            if (!(n.contains("ground") && !n.contains("color")) && !n.contains("oneway")) continue;
            for (int i = 0; i < c.pts.length - 1; i++) {
                double ax = c.pts[i][0], ay = c.pts[i][1], bx = c.pts[i + 1][0], by = c.pts[i + 1][1];
                double lo = Math.min(ax, bx), hi = Math.max(ax, bx);
                if (ux >= lo && ux <= hi && Math.abs(bx - ax) > 1e-6) {
                    double y = ay + (ux - ax) / (bx - ax) * (by - ay);
                    double d = Math.abs(y - refY);
                    if (d < bestD) { bestD = d; best = y; }
                }
            }
        }
        return best;
    }

    // lưới ô vuông hỗ trợ đặt quái/NPC (chỉ hiển thị, không ép snap)
    private boolean showGrid = false;
    private double gridSize = 1.0;   // unit / ô
    public void setShowGrid(boolean v) { showGrid = v; repaint(); }
    public void setGridSize(double v) { gridSize = Math.max(0.1, v); repaint(); }

    /** Grid bật → X hít ĐƯỜNG KẺ DỌC gần nhất, Y giữ tự nhiên (ground/tự do). */
    private double[] gridSnap(double ux, double uy) {
        if (!showGrid) return new double[]{ux, uy};
        return new double[]{Math.round(ux / gridSize) * gridSize, uy};
    }

    private void drawGrid(Graphics2D g2) {
        double step = gridSize;
        while (step * scale < 8) step *= 2;          // zoom xa → tự gộp ô, khỏi rối
        double[] vb = viewBounds();
        double x0 = ux(0), x1 = ux(getWidth()), y1 = uy(0), y0 = uy(getHeight());
        if (vb != null) { x0 = Math.max(x0, vb[0]); x1 = Math.min(x1, vb[2]); y0 = Math.max(y0, vb[1]); y1 = Math.min(y1, vb[3]); }
        int sxA = (int) sx(x0), sxB = (int) sx(x1), syA = (int) sy(y1), syB = (int) sy(y0);
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1f));
        for (double gx = Math.floor(x0 / step) * step; gx <= x1; gx += step) {
            boolean major = Math.abs(Math.IEEEremainder(gx, step * 5)) < 1e-6;
            g2.setColor(new Color(255, 255, 255, major ? 150 : 80));
            int px = (int) sx(gx);
            g2.drawLine(px, syA, px, syB);
        }
        for (double gy = Math.floor(y0 / step) * step; gy <= y1; gy += step) {
            boolean major = Math.abs(Math.IEEEremainder(gy, step * 5)) < 1e-6;
            g2.setColor(new Color(255, 255, 255, major ? 150 : 80));
            int py = (int) sy(gy);
            g2.drawLine(sxA, py, sxB, py);
        }
        g2.setStroke(old);
    }

    // đường đặt NPC: ground dịch lên offset — NPC đặt được trên 2 đường (ground hoặc đường này)
    private boolean showNpcLine = true;
    private double npcLineOffset = 0.35;  // unit
    public void setShowNpcLine(boolean v) { showNpcLine = v; repaint(); }
    public void setNpcLineOffset(double v) { npcLineOffset = Math.max(0, v); repaint(); }

    /** Vẽ đường đặt NPC (nét đứt xanh lá) = mọi segment ground/oneway dịch lên npcLineOffset. */
    private void drawNpcLine(Graphics2D g2) {
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{7, 5}, 0));
        g2.setColor(new Color(60, 220, 90, 170));
        for (ColliderShape c : colliders) {
            if (c.isTrigger) continue;
            String n = c.name == null ? "" : c.name.toLowerCase();
            if (!(n.contains("ground") && !n.contains("color")) && !n.contains("oneway")) continue;
            for (int i = 0; i < c.pts.length - 1; i++) {
                g2.drawLine((int) sx(c.pts[i][0]), (int) sy(c.pts[i][1] + npcLineOffset),
                            (int) sx(c.pts[i + 1][0]), (int) sy(c.pts[i + 1][1] + npcLineOffset));
            }
        }
        g2.setStroke(old);
    }

    /** Bóng mờ preview vật sắp đặt bám con trỏ (place mode) — vị trí đúng như lúc click đặt thật. */
    private void drawPlaceGhost(Graphics2D g2) {
        if (placeKind == null) return;
        double[] gs = gridSnap(mouseWorld.x, mouseWorld.y);
        double wx = gs[0], wy = gs[1], fy = wy;
        boolean fly = placeKind == Marker.Kind.ENEMY && sprites != null && sprites.enemyFly(placeId);
        boolean ok = true;
        if ((placeKind == Marker.Kind.ENEMY && !fly) || placeKind == Marker.Kind.NPC) {
            double gy = groundYAtNearest(wx, wy);
            if (Double.isNaN(gy)) ok = false;
            else if (placeKind == Marker.Kind.NPC && showNpcLine
                    && Math.abs(wy - (gy + npcLineOffset)) < Math.abs(wy - gy)) fy = gy + npcLineOffset;
            else fy = gy;
        }
        int x = (int) sx(wx), y = (int) sy(fy);
        Composite oldCmp = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ok ? 0.55f : 0.35f));
        if (placeKind == Marker.Kind.GATEWAY && gateClickBox != null) {
            drawGateBox(g2, wx, fy, gateClickBox, false,
                    new Color(255, 205, 90, 60), new Color(255, 195, 75));
            if (gateTouchBox != null)
                drawGateBox(g2, wx, fy, gateTouchBox, false,
                        new Color(255, 140, 0, 50), new Color(255, 140, 0));
        } else {
            SpineCharacter spc = null;
            if (sprites != null) {
                if (placeKind == Marker.Kind.ENEMY) spc = sprites.spineEnemy(placeId);
                else if (placeKind == Marker.Kind.NPC) spc = sprites.spineNpc(placeId);
            }
            if (spc != null) {
                double ratio = (placeKind == Marker.Kind.ENEMY) ? 0.9 : 1.0;
                spc.render(g2, x, y, spc.worldHeight() * ratio * markerScale * scale, animTime,
                        spc.pickAnim("Idle", "Walk"), false, 1f);
            } else {
                BufferedImage icon = null;
                if (sprites != null) {
                    if (placeKind == Marker.Kind.ENEMY) icon = sprites.enemy(placeId);
                    else if (placeKind == Marker.Kind.NPC) icon = sprites.npc(placeId);
                }
                if (icon != null) {
                    int ih = Math.max(8, (int) (1.5 * markerScale * scale));
                    int iw = ih * icon.getWidth() / Math.max(1, icon.getHeight());
                    g2.drawImage(icon, x - iw / 2, y - ih, iw, ih, null);
                } else {
                    g2.setColor(markerColor(placeKind));
                    g2.fillOval(x - 6, y - 6, 12, 12);
                }
            }
        }
        g2.setComposite(oldCmp);
        if (!ok) {   // không có đất tại đây → gạch đỏ báo không đặt được
            g2.setColor(new Color(255, 70, 70));
            g2.setStroke(new BasicStroke(2f));
            g2.drawLine(x - 8, y - 8, x + 8, y + 8);
            g2.drawLine(x - 8, y + 8, x + 8, y - 8);
        }
    }

    private String flashMsg;
    private long flashUntil;
    private void flash(String msg) {
        flashMsg = msg;
        flashUntil = System.currentTimeMillis() + 1800;
        Toolkit.getDefaultToolkit().beep();
        repaint();
    }
    private void drawFlash(Graphics2D g2) {
        if (flashMsg == null || System.currentTimeMillis() > flashUntil) return;
        Font old = g2.getFont();
        g2.setFont(old.deriveFont(Font.BOLD, 14f));
        int w = g2.getFontMetrics().stringWidth(flashMsg);
        int cx = Math.max(8, (getWidth() - w) / 2);
        g2.setColor(new Color(120, 20, 20, 210));
        g2.fillRect(cx - 12, getHeight() - 46, w + 24, 30);
        g2.setColor(Color.WHITE);
        g2.drawString(flashMsg, cx, getHeight() - 26);
        g2.setFont(old);
    }

    /**
     * Đặt server coord cho marker tại world (ux,uy). Quái/NPC/Arrive BẮT BUỘC trên đất:
     * không có ground tại x → trả false (không đổi vị trí).
     */
    private boolean applyPos(Marker m, double ux, double uy) {
        double fy = uy;
        boolean fly = m.kind == Marker.Kind.ENEMY && sprites != null && sprites.enemyFly(m.mainId());
        if (snapKind(m.kind) && !fly) {           // quái BAY đặt tự do, không bắt dính đất
            double gy = groundYAtNearest(ux, uy);
            if (Double.isNaN(gy)) return false;   // không có đất → từ chối
            // NPC: 2 đường — ground hoặc đường NPC (ground + offset). Snap đường gần chuột hơn.
            if (m.kind == Marker.Kind.NPC && showNpcLine
                    && Math.abs(uy - (gy + npcLineOffset)) < Math.abs(uy - gy)) {
                fy = gy + npcLineOffset;
            } else {
                fy = gy;
            }
        }
        m.setServerX((int) Math.round(ux * ppu));
        m.setServerY((int) Math.round(fy * ppu));
        return true;
    }

    // ─── mouse ─────────────────────────────────────────────────
    private void setupMouse() {
        setFocusable(true);
        MouseAdapter ma = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                double wx = ux(e.getX()), wy = uy(e.getY());
                if (placeKind != null) { double[] gs = gridSnap(wx, wy); wx = gs[0]; wy = gs[1]; } // grid bật → đặt tâm ô
                if (rangePickMode && SwingUtilities.isLeftMouseButton(e)) {
                    rangeStartX = wx; rangeStartY = wy; rangeCurX = wx; rangeCurY = wy; repaint(); return;
                }
                if (pickMode && SwingUtilities.isLeftMouseButton(e)) {
                    double[] gs = gridSnap(wx, wy);   // grid bật → điểm rơi cũng căn ô
                    int sxv = (int) Math.round(gs[0] * ppu), syv = (int) Math.round(gs[1] * ppu);
                    if (pickCallback != null) pickCallback.accept(sxv, syv);
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && placeKind != null) {
                    Marker m = Marker.create(placeKind, 0, 0);
                    if (placeKind == Marker.Kind.ENEMY || placeKind == Marker.Kind.NPC || placeKind == Marker.Kind.GATEWAY)
                        m.setMainId(placeId);
                    if (placeKind == Marker.Kind.NPC) m.setInt("requiredMission", placeExtra);
                    if (isFreeKey(e)) {                       // giữ Shift/Alt = đặt TỰ DO (bỏ ràng buộc đất)
                        m.setServerX((int) Math.round(wx * ppu));
                        m.setServerY((int) Math.round(wy * ppu));
                    } else if (!applyPos(m, wx, wy)) { flash("Không có đất tại đây — giữ Shift để đặt tự do"); return; }
                    pushUndo();
                    editMarkers.add(m);
                    selected = m;
                    if (placeKind == Marker.Kind.GATEWAY) placeKind = null; // cổng đặt 1 lần → sửa tiếp ở form
                    fireChange();
                    fireSelect();
                    repaint();
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (selectLock) {
                        // khóa chọn: CHỈ vật đã chọn (từ list) mới kéo; click vật khác = bỏ qua (pan)
                        Marker hit = markerAt(wx, wy);
                        if (hit != null && hit == selected) {
                            draggingMarker = true; pushedThisDrag = false; repaint(); return;
                        }
                        IncomingDrop inc = incomingAt(wx, wy);
                        if (inc != null && inc == selectedIncoming) {
                            draggingIncoming = true; repaint(); return;
                        }
                    } else {
                        Marker hit = markerAt(wx, wy);
                        if (hit != null) {
                            selected = hit; selectedIncoming = null; draggingMarker = true; pushedThisDrag = false; fireChange(); fireSelect(); repaint(); return;
                        }
                        IncomingDrop inc = incomingAt(wx, wy);
                        if (inc != null) {
                            selectedIncoming = inc; selected = null; draggingIncoming = true; fireSelect(); repaint(); return;
                        }
                        selected = null; selectedIncoming = null; fireChange(); fireSelect();
                    }
                }
                lastDrag = e.getPoint(); // pan
            }
            @Override public void mouseReleased(MouseEvent e) {
                if (rangePickMode && !Double.isNaN(rangeStartX)) {
                    double a = Math.min(rangeStartX, rangeCurX), b = Math.max(rangeStartX, rangeCurX);
                    double yA = Math.min(rangeStartY, rangeCurY), yB = Math.max(rangeStartY, rangeCurY);
                    RectCallback cb = rangeCallback;
                    exitRangePick();
                    if (cb != null && b - a > 0.01) cb.accept(a, yA, b, yB);
                    return;
                }
                lastDrag = null; draggingMarker = false; draggingIncoming = false; activeGuides.clear(); repaint();
            }
            @Override public void mouseDragged(MouseEvent e) {
                if (rangePickMode && !Double.isNaN(rangeStartX)) {
                    rangeCurX = ux(e.getX()); rangeCurY = uy(e.getY()); repaint(); return;
                }
                if (draggingMarker && selected != null) {
                    if (!pushedThisDrag) { pushUndo(); pushedThisDrag = true; } // 1 undo cho cả lần kéo
                    double[] sp = showGrid ? gridSnap(ux(e.getX()), uy(e.getY()))   // grid bật → ưu tiên ô
                                           : snapWithGuides(selected, ux(e.getX()), uy(e.getY()));
                    if (isFreeKey(e)) {                   // Shift/Alt = kéo tự do
                        selected.setServerX((int) Math.round(sp[0] * ppu));
                        selected.setServerY((int) Math.round(sp[1] * ppu));
                    } else {
                        applyPos(selected, sp[0], sp[1]); // false = ra ngoài đất → marker đứng yên
                    }
                    mouseWorld = new Point2DWorld(sp[0], sp[1]);
                    fireChange();
                    repaint();
                    return;
                }
                if (draggingIncoming && selectedIncoming != null) {
                    double[] sp = showGrid ? gridSnap(ux(e.getX()), uy(e.getY()))
                                           : snapWithGuides(null, ux(e.getX()), uy(e.getY()));
                    selectedIncoming.serverX = (int) Math.round(sp[0] * ppu);
                    selectedIncoming.serverY = (int) Math.round(sp[1] * ppu);
                    selectedIncoming.dirty = true;
                    mouseWorld = new Point2DWorld(sp[0], sp[1]);
                    if (onIncomingChange != null) onIncomingChange.run();
                    repaint();
                    return;
                }
                if (lastDrag != null) {
                    originX += e.getX() - lastDrag.x;
                    originY += e.getY() - lastDrag.y;
                    lastDrag = e.getPoint();
                    repaint();
                }
            }
            @Override public void mouseMoved(MouseEvent e) {
                mouseWorld = new Point2DWorld(ux(e.getX()), uy(e.getY()));
                repaint();
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double wx = ux(e.getX()), wy = uy(e.getY());
                double factor = e.getPreciseWheelRotation() < 0 ? 1.1 : 1 / 1.1;
                scale *= factor;
                originX = e.getX() - wx * scale;
                originY = e.getY() + wy * scale;
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Z) { if (e.isShiftDown()) redo(); else undo(); return; }
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Y) { redo(); return; }
                if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) deleteSelected();
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    if (rangePickMode) { exitRangePick(); }
                    else if (pickMode) { Runnable c = pickCancel; setPickMode(false, null, null); if (c != null) c.run(); }
                    else { clearPlaceMode(); fireChange(); }
                }
            }
        });
    }

    private record Point2DWorld(double x, double y) {}
}
