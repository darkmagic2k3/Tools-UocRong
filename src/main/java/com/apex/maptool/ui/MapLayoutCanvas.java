package com.apex.maptool.ui;

import com.apex.maptool.spine.SpineCharacter;
import com.apex.maptool.spine.SpineData;
import com.apex.maptool.unity.MapScene;
import com.apex.maptool.unity.TextureCache;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * MapLayoutCanvas — canvas vẽ + chỉnh sửa trực tiếp 1 {@link MapScene} (chức năng "Bố cục Map").
 *
 * <p>Hệ toạ độ chép nguyên {@code MapCanvasPanel}: world = unity unit, y hướng LÊN, màn hình lật y.
 * {@code scale} = px/unit, {@code originX/originY} = vị trí màn hình của world (0,0).
 * Toạ độ server = unity × ppu (100).
 *
 * <p>Vẽ sprite dùng đúng chuỗi AffineTransform của canvas cũ:
 * {@code translate(tâm màn hình) → rotate(-góc) → scale(dw/imgW·flip, dh/imgH·flip) → translate(-imgW/2, -imgH/2)}
 * — {@code MapScene.worldCenter()} đã tính pivot nên bước cuối chỉ dời ảnh về giữa.
 *
 * <p>ĐIỂM QUAN TRỌNG NHẤT — "trước/sau player": player của chính mình nằm ở
 * sorting layer index 18 (LocalPlayer), sortingOrder = 1 (xem doc 03 §1). Nên node map được coi là
 * TIỀN CẢNH (che player) khi {@code sortLayerIdx > 18} hoặc {@code sortLayerIdx == 18 && sortOrder > 1}.
 * Bật {@link #setPreviewPlayer(boolean)} → canvas vẽ hình người mẫu CHÈN GIỮA hai nhóm đó, nên nhìn
 * là biết ngay cái gì che player, cái gì bị player che.
 *
 * <p>Tương tác: xem {@link #shortcutHelp()}.
 */
public final class MapLayoutCanvas extends JPanel {

    /**
     * Chế độ chuột: chọn / di chuyển sprite / sửa đường kẻ (EdgeCollider2D) /
     * VẼ đường kẻ mới (click từng điểm).
     */
    public enum EditMode { SELECT, MOVE, LINE, DRAW_LINE }

    // ── Hằng số ──
    /** sortingOrder thân player (UnitRenderer: {@code sortingOrder = 1 + orderLayer}, orderLayer = 0). */
    private static final int PLAYER_BODY_ORDER = 1;
    /** Kích thước hình người mẫu (unit) — khớp capsule player trong client. */
    private static final double PLAYER_W = 0.8, PLAYER_H = 1.6;
    private static final int UNDO_MAX = 60;
    private static final int HIT_PX = 7;        // dung sai bắt handle/đỉnh (px màn hình)
    private static final int ALPHA_HIT = 8;     // pixel alpha ≤ 8 coi như trong suốt → không bắt

    // ── Màu ──
    private static final Color COL_GROUND  = new Color(255, 60, 60);    // đường đất (physLayer 6)
    private static final Color COL_ONEWAY  = new Color(80, 180, 255);   // oneway (physLayer 19)
    private static final Color COL_TRIGGER = new Color(255, 140, 0);    // trigger
    private static final Color COL_OTHER   = new Color(150, 150, 165);  // còn lại
    private static final Color COL_FRONT   = new Color(167, 139, 250);  // tím = trước player
    private static final Color COL_SNAP    = new Color(255, 64, 190);   // đường gióng "hít khít"
    private static final Color COL_BOUND   = new Color(255, 208, 64);   // 4 thanh biên map
    private static final Color COL_CAM     = new Color(64, 232, 128);   // khung camera kết quả
    private static final Color COL_FX      = new Color(120, 220, 255);  // node có hiệu ứng

    // ── Camera client (doc 08 §A.2: Gameplay.unity orthographic size 8.1) ──
    /** Nửa chiều cao khung nhìn camera (unit). */
    private static final double CAM_HALF_Y = 8.1;
    /** Tỉ lệ màn hình CHẬT nhất phổ biến (20:9) → nửa bề ngang = 8.1 × 20/9 = 18.0 unit. */
    private static final double CAM_ASPECT = 20.0 / 9.0;

    /** Nhịp timer hiệu ứng NHANH NHẤT (~30 fps). CHỈ chạy khi bật "Chạy hiệu ứng". */
    private static final int FX_TIMER_MS = 33;
    /** Nhịp CHẬM NHẤT khi máy không kịp vẽ (~7 fps) — dưới mức này thì nhìn thành giật hình. */
    private static final int FX_TIMER_MAX_MS = 140;
    /** Spine nhỏ hơn ngần này px thì bỏ vẽ (giống cull 0.4 px của sprite). */
    private static final double SPINE_MIN_PX = 3.0;

    // ─────────────────────────────────────────────────────────────────────
    // State
    // ─────────────────────────────────────────────────────────────────────

    private final TextureCache tex;
    private final int ppu;

    private MapScene scene;
    private MapScene.Node selected;
    private Consumer<MapScene.Node> onSelect;
    private Runnable onChange;

    // view
    private double scale = 8.0;
    private double originX = 400, originY = 400;

    // toggle hiển thị
    private boolean showSprites = true;
    private boolean showColliders = true;
    private boolean showGrid = false;
    private boolean showOnlyGround = false;
    private boolean showInactive = true;    // vẽ mờ node đã tắt (m_IsActive = 0)
    private boolean markFront = false;      // badge "TRƯỚC PLAYER"
    private boolean previewPlayer = false;
    private double gridSize = 1.0;
    private boolean snap = false;
    private int playerLayerIndex = 18;
    private EditMode mode = EditMode.SELECT;

    // ── Y1: HÍT KHÍT (nam châm) ──
    /** Bộ máy hình học; canvas chỉ lo dựng danh sách hình + vẽ đường gióng. */
    private final SnapEngine snapEng = new SnapEngine();
    /** Các hình đích — dựng ĐÚNG 1 LẦN lúc bắt đầu kéo (vật khác không di chuyển trong lúc kéo). */
    private List<SnapEngine.Rect> snapOthers = new ArrayList<>();
    /** Mốc lẻ trục X/Y: đỉnh đường kẻ đất trong khung nhìn + 4 thanh biên map. */
    private double[] snapExtraX = new double[0], snapExtraY = new double[0];
    /** Đường gióng đang hiện (chỉ trong lúc kéo). */
    private List<SnapEngine.Target> guideX = List.of(), guideY = List.of();
    /** Dòng HUD mô tả cú hít gần nhất (null = không hít). */
    private String snapHint;

    // ── Y2: 4 THANH BIÊN MAP ──
    private boolean showBounds = true;
    private boolean editBounds = false;
    /** Thanh biên đang kéo: 0 trái · 1 phải · 2 đáy · 3 đỉnh (−1 = không). */
    private int draggingBound = -1;
    private int hoverBound = -1;
    /** Vị trí gốc transform của thanh biên lúc bắt đầu kéo (world). */
    private double bndOrigX, bndOrigY;
    /** Dòng HUD khi kéo biên (giá trị mới + khung camera mới). */
    private String boundHint;
    /** Đang kéo CẢ node đường kẻ (không phải từng đỉnh). */
    private boolean draggingWhole;
    private double wholeOrigX, wholeOrigY;

    // ── Y3: HIỆU ỨNG ──
    private boolean showEffects = true;         // vẽ Spine + mô phỏng script
    private boolean playEffects = false;        // đang CHẠY (timer bật)
    private boolean showEffectBadges = true;    // nhãn loại hiệu ứng trên node
    private double effectSpeed = 1.0;
    /** Đồng hồ hiệu ứng (giây, đã nhân tốc độ). 0 = tư thế tĩnh. */
    private double fxTime = 0;
    private long fxLastNano;
    private Timer fxTimer;

    // chuột
    private double mouseWx, mouseWy;
    private Point lastPan;              // điểm neo khi pan
    private boolean spaceDown;
    private boolean draggingNode;       // kéo di chuyển sprite
    private double dragStartWx, dragStartWy;
    private double dragOrigCx, dragOrigCy;
    private int dragHandle = -1;        // handle scale đang kéo (-1 = không)
    private double hSx0, hSy0, hW0, hH0, hAx, hAy, hAngRad;   // trạng thái lúc bắt đầu kéo handle
    private int hDirX, hDirY;
    private boolean pushedThisDrag;

    // sửa đường kẻ
    private MapScene.Node lineNode;     // node collider đang chọn đỉnh
    private int lineVertex = -1;        // index đỉnh đang chọn
    private boolean draggingVertex;
    private int hoverVertex = -1;
    private MapScene.Node hoverNode;

    // vẽ đường mới (EditMode.DRAW_LINE)
    /** Các đỉnh (WORLD) người dùng đã click nhưng CHƯA chốt thành đường. */
    private final List<double[]> draftPts = new ArrayList<>();
    /** Nơi trả kết quả khi chốt đường (null = người dùng huỷ bằng Esc). */
    private Consumer<double[][]> onDrawDone;
    /** Chế độ đang dùng trước khi vào DRAW_LINE — vẽ xong/huỷ thì trả về đúng chế độ đó. */
    private EditMode modeBeforeDraw = EditMode.SELECT;

    // undo/redo
    private final Deque<SceneSnap> undo = new ArrayDeque<>();
    private final Deque<SceneSnap> redo = new ArrayDeque<>();
    /** Bắn khi CẤU TRÚC scene đổi (undo/redo có thêm/bớt node) → frame dựng lại cây. */
    private Runnable onStructure;

    /** Cache ảnh đã cắt sub-sprite (khoá = đường dẫn + rect). */
    private final Map<String, BufferedImage> cropCache = new HashMap<>();

    // ── Cache Spine / Animator: TĨNH (dùng chung mọi canvas, mọi lần nạp map) ──
    // Load 1 skeleton mất 13–97 ms và Map8 có 37 node/17 skeleton ⇒ BẮT BUỘC cache theo THƯ MỤC.
    // SPINE_BAD nhớ cả lần load HỎNG (thiếu atlas / skeleton lỗi) để không thử lại + không spam log.
    private static final Map<Path, SpineCharacter> SPINE_CACHE = new HashMap<>();
    private static final Set<Path> SPINE_BAD = new HashSet<>();
    /** Hộp bao skeleton {minX, minY, maxX, maxY} theo UNITY UNIT (đã × assetScale), tính 1 lần. */
    private static final Map<Path, double[]> SPINE_BOX = new HashMap<>();
    /** Clip sprite-swap của Animator, khoá = file .controller. */
    private static final Map<Path, AnimClip> ANIM_CACHE = new HashMap<>();
    private static final Set<Path> ANIM_BAD = new HashSet<>();

    public MapLayoutCanvas(TextureCache tex, int ppu) {
        this.tex = tex;
        this.ppu = ppu > 0 ? ppu : 100;
        setBackground(Theme.BG_APP);
        setFocusable(true);
        setupMouse();
        setupKeys();
    }

    // ─────────────────────────────────────────────────────────────────────
    // API ngoài
    // ─────────────────────────────────────────────────────────────────────

    public void setScene(MapScene s) {
        this.scene = s;
        this.selected = null;
        this.lineNode = null;
        this.lineVertex = -1;
        draftPts.clear();
        onDrawDone = null;
        if (mode == EditMode.DRAW_LINE) setEditMode(modeBeforeDraw);
        undo.clear();
        redo.clear();
        // dọn mọi trạng thái kéo/hít của map cũ (tham chiếu node cũ là treo)
        draggingWhole = false;
        draggingBound = -1;
        hoverBound = -1;
        boundHint = null;
        snapOthers = new ArrayList<>();
        snapExtraX = new double[0];
        snapExtraY = new double[0];
        clearGuides();
        repaint();
    }

    public MapScene scene() { return scene; }

    /** Chọn node từ ngoài (cây hierarchy) — KHÔNG bắn lại onSelect để tránh vòng lặp. */
    public void setSelected(MapScene.Node n) {
        selected = n;
        if (n != null && n.colKind == MapScene.ColKind.EDGE) lineNode = n;
        lineVertex = -1;
        repaint();
    }

    public MapScene.Node selected() { return selected; }

    public void setOnSelect(Consumer<MapScene.Node> c) { onSelect = c; }
    public void setOnChange(Runnable r) { onChange = r; }

    /**
     * Báo cho frame biết CẤU TRÚC scene vừa đổi (undo/redo thao tác thêm/xoá đường) để dựng lại
     * cây hierarchy — {@code onChange} không đủ vì nó chỉ nói "có field đổi".
     */
    public void setOnStructure(Runnable r) { onStructure = r; }

    /** Canh toàn map vào giữa khung nhìn. Gọi trong {@code SwingUtilities.invokeLater} sau khi load. */
    public void fitView() {
        if (scene == null || getWidth() <= 0) {
            originX = Math.max(1, getWidth()) / 2.0;
            originY = Math.max(1, getHeight()) / 2.0;
            scale = 8.0;
            repaint();
            return;
        }
        double[] b = scene.bounds();
        double w = Math.max(1e-3, b[2] - b[0]), h = Math.max(1e-3, b[3] - b[1]);
        int pw = Math.max(1, getWidth() - 40), ph = Math.max(1, getHeight() - 40);
        scale = Math.max(0.05, Math.min(pw / w, ph / h));
        double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
        originX = getWidth() / 2.0 - cx * scale;
        originY = getHeight() / 2.0 + cy * scale;
        repaint();
    }

    /** Mức zoom hiện tại (px màn hình trên 1 unit). */
    public double zoom() { return scale; }

    /** Đặt mức zoom (px/unit) mà GIỮ NGUYÊN điểm world đang nằm giữa khung nhìn. */
    public void setZoom(double pxPerUnit) {
        double z = Math.max(0.05, Math.min(400, pxPerUnit));
        double cxu = ux(getWidth() / 2.0), cyu = uy(getHeight() / 2.0);
        scale = z;
        originX = getWidth() / 2.0 - cxu * scale;
        originY = getHeight() / 2.0 + cyu * scale;
        repaint();
    }

    /** Canh 1 node ra giữa khung nhìn (giữ nguyên zoom) — dùng khi click cây hierarchy. */
    public void panTo(MapScene.Node n) {
        if (scene == null || n == null) return;
        double[] c = (n.hasRenderer && n.baseW > 0) ? scene.worldCenter(n)
                : MapScene.applyPoint(scene.worldMatrix(n), 0, 0);
        originX = getWidth() / 2.0 - c[0] * scale;
        originY = getHeight() / 2.0 + c[1] * scale;
        repaint();
    }

    public void setShowSprites(boolean v) { showSprites = v; repaint(); }
    public void setShowColliders(boolean v) { showColliders = v; repaint(); }
    public void setShowGrid(boolean v) { showGrid = v; repaint(); }
    /** Chỉ hiện đường đất/oneway (ẩn hết sprite) — để chỉnh đường kẻ cho dễ. */
    public void setShowOnlyGround(boolean v) { showOnlyGround = v; repaint(); }
    /** Hiện cả phần đã tắt (m_IsActive = 0) dưới dạng mờ. */
    public void setShowInactive(boolean v) { showInactive = v; repaint(); }
    /** Tô viền tím + badge "TRƯỚC PLAYER" cho node vẽ đè lên player. */
    public void setMarkFront(boolean v) { markFront = v; repaint(); }
    public void setGridSize(double u) {
        gridSize = Math.max(0.05, u);
        snapEng.setGrid(snap, gridSize);
        repaint();
    }

    public void setSnap(boolean v) { snap = v; snapEng.setGrid(v, gridSize); }
    public boolean snap() { return snap; }
    public double gridSize() { return gridSize; }

    // ── Y1: HÍT KHÍT ──

    /**
     * Bật/tắt HÍT KHÍT (nam châm mép/tâm). Tắt ⇒ quay về hành vi cũ: chỉ hít lưới nếu
     * {@link #setSnap(boolean)} đang bật. Giữ <b>Alt</b> khi kéo = tạm tắt cho một lần kéo.
     */
    public void setSnapEnabled(boolean v) { snapEng.setEnabled(v); repaint(); }

    /** Đang bật hít khít không. */
    public boolean snapEnabled() { return snapEng.enabled(); }

    /** Ngưỡng hít theo PIXEL màn hình (2..20). Zoom càng gần thì ngưỡng theo unit càng nhỏ. */
    public void setSnapThresholdPx(double px) { snapEng.setThresholdPx(px); }

    /** Bộ máy hít khít (frame dùng để đọc/ghi thiết lập nâng cao). */
    public SnapEngine snapEngine() { return snapEng; }

    // ── Y2: BIÊN MAP ──

    /** Vẽ 4 thanh biên map + khung camera kết quả. */
    public void setShowBounds(boolean v) { showBounds = v; repaint(); }
    public boolean showBounds() { return showBounds; }

    /**
     * Cho phép KÉO 4 thanh biên (trái/phải chỉ đổi X, trên/dưới chỉ đổi Y — khoá trục còn lại;
     * giữ <b>Shift</b> để kéo được cả trục phụ). Bật cái này cũng tự bật hiển thị biên.
     */
    public void setEditBounds(boolean v) {
        editBounds = v;
        if (v) showBounds = true;
        hoverBound = -1;
        repaint();
    }

    public boolean editBounds() { return editBounds; }

    // ── Y3: HIỆU ỨNG ──

    /** Vẽ hiệu ứng (Spine + mô phỏng chuyển động). Tắt ⇒ node Spine biến mất khỏi canvas. */
    public void setShowEffects(boolean v) { showEffects = v; repaint(); }
    public boolean showEffects() { return showEffects; }

    /** Alias của {@link #setShowEffects(boolean)} — Spine là hiệu ứng chiếm đa số. */
    public void setShowSpine(boolean v) { setShowEffects(v); }

    /**
     * CHẠY hiệu ứng: bật timer {@value #FX_TIMER_MS} ms; tắt thì {@code stop()} hẳn để không đốt CPU.
     * Khi tắt, Spine vẫn được vẽ ở tư thế TĨNH (t = 0) để còn nhìn/chọn được.
     */
    public void setPlayEffects(boolean v) {
        if (playEffects == v) return;
        playEffects = v;
        if (v) {
            fxLastNano = System.nanoTime();
            if (fxTimer == null) {
                fxTimer = new Timer(FX_TIMER_MS, e -> tickEffects());
                fxTimer.setCoalesce(true);
            }
            fxTimer.start();
        } else if (fxTimer != null) {
            fxTimer.stop();
        }
        repaint();
    }

    public boolean playEffects() { return playEffects; }

    /** Hệ số tốc độ phát hiệu ứng (0.1 … 5). */
    public void setEffectSpeed(double v) { effectSpeed = Math.max(0.01, Math.min(20, v)); }
    public double effectSpeed() { return effectSpeed; }

    /** Đưa đồng hồ hiệu ứng về 0 (mọi node quay lại tư thế/vị trí gốc). */
    public void resetEffectTime() { fxTime = 0; repaint(); }

    /**
     * Đặt THẲNG đồng hồ hiệu ứng (giây) — dùng để TUA tới một thời điểm bất kỳ, và để bộ tự test
     * vẽ được nhiều frame ở các mốc thời gian khác nhau mà không phải chờ timer chạy thật.
     */
    public void setEffectTime(double t) {
        fxTime = Math.max(0, t);
        repaint();
    }

    /** Đồng hồ hiệu ứng hiện tại (giây, đã nhân tốc độ). */
    public double effectTime() { return fxTime; }

    /** Hiện nhãn loại hiệu ứng ("Spine", "Nước chảy"…) trên từng node. */
    public void setShowEffectBadges(boolean v) { showEffectBadges = v; repaint(); }
    public boolean showEffectBadges() { return showEffectBadges; }

    /** Gỡ canvas khỏi cửa sổ (đóng màn Bố cục Map) ⇒ DỪNG timer, không để chạy nền đốt CPU. */
    @Override public void removeNotify() {
        if (fxTimer != null) fxTimer.stop();
        super.removeNotify();
    }

    /** Gắn lại vào cửa sổ ⇒ chạy tiếp nếu người dùng vẫn đang bật "chạy hiệu ứng". */
    @Override public void addNotify() {
        super.addNotify();
        if (playEffects && fxTimer != null) {
            fxLastNano = System.nanoTime();
            fxTimer.start();
        }
    }

    /**
     * Nhịp timer: cộng dồn thời gian THEO ĐỒNG HỒ THẬT × tốc độ, rồi vẽ lại.
     *
     * <p><b>Tự giãn nhịp theo sức máy.</b> Map nặng (Map8: 37 node Spine) mất ~50 ms một khung, mà
     * timer cố định 33 ms thì lệnh vẽ dồn nhanh hơn tốc độ vẽ ⇒ EDT bận 100% ⇒ <b>cả tool</b> giật
     * chứ không riêng hiệu ứng: kéo chuột, bấm nút, mở menu đều trễ. Giãn nhịp ra ~2× thời gian vẽ
     * để chừa hơn nửa thời gian cho thao tác của người dùng. Map nhẹ vẽ 5 ms thì nhịp vẫn là 33 ms,
     * không đổi gì. Đồng hồ hiệu ứng cộng theo thời gian THẬT nên hiệu ứng vẫn chạy đúng tốc độ,
     * chỉ là ít khung hình hơn.
     */
    private void tickEffects() {
        long now = System.nanoTime();
        double dt = (now - fxLastNano) / 1_000_000_000.0;
        fxLastNano = now;
        if (dt < 0 || dt > 0.5) dt = FX_TIMER_MS / 1000.0;      // máy treo/ngủ → bỏ bước nhảy
        fxTime += dt * effectSpeed;
        if (fxTimer != null) {
            int want = (int) Math.max(FX_TIMER_MS, Math.min(FX_TIMER_MAX_MS, lastPaintMs * 2));
            if (want != fxTimer.getDelay()) fxTimer.setDelay(want);
        }
        if (isShowing()) repaint();
    }

    /** Thời gian vẽ khung hình gần nhất (ms) — dùng để giãn nhịp timer hiệu ứng. */
    private double lastPaintMs;

    /** Thời gian vẽ khung hình gần nhất (ms), cho thanh trạng thái / đo đạc. */
    public double lastPaintMs() { return lastPaintMs; }

    public void setEditMode(EditMode m) {
        EditMode want = (m == null) ? EditMode.SELECT : m;
        // Bấm nút chế độ khác trong lúc đang vẽ = HUỶ, và phải báo lại frame (trả UI về như cũ).
        if (mode == EditMode.DRAW_LINE && want != EditMode.DRAW_LINE && onDrawDone != null) {
            Consumer<double[][]> cb = onDrawDone;
            onDrawDone = null;
            draftPts.clear();
            applyMode(want);
            cb.accept(null);
            return;
        }
        applyMode(want);
    }

    private void applyMode(EditMode m) {
        mode = m;
        if (mode != EditMode.DRAW_LINE) {
            draftPts.clear();
            onDrawDone = null;
        }
        setCursor(Cursor.getPredefinedCursor(switch (mode) {
            case MOVE -> Cursor.MOVE_CURSOR;
            case LINE, DRAW_LINE -> Cursor.CROSSHAIR_CURSOR;
            default -> Cursor.DEFAULT_CURSOR;
        }));
        repaint();
    }

    public EditMode editMode() { return mode; }

    // ── Vẽ đường kẻ mới ──

    /**
     * Bật/tắt chế độ VẼ ĐƯỜNG MỚI: người dùng click từng điểm trên canvas (hít lưới nếu đang bật),
     * <b>Enter</b> hoặc <b>double-click</b> = chốt, <b>Backspace</b> = bỏ điểm cuối, <b>Esc</b> = huỷ.
     *
     * @param on     true = vào chế độ vẽ (xoá mọi điểm nháp cũ), false = thoát và huỷ.
     * @param onDone gọi đúng 1 lần khi kết thúc: mảng đỉnh WORLD (≥ 2 đỉnh) nếu chốt,
     *               hoặc <b>null</b> nếu người dùng huỷ — frame dựa vào đó để trả lại UI như cũ.
     */
    public void setDrawLineMode(boolean on, Consumer<double[][]> onDone) {
        if (!on) { cancelDraw(); return; }
        if (mode == EditMode.DRAW_LINE) cancelDraw();      // huỷ lượt vẽ đang dở (nếu có)
        modeBeforeDraw = mode;
        applyMode(EditMode.DRAW_LINE);
        draftPts.clear();
        onDrawDone = onDone;
        requestFocusInWindow();                // để Enter/Esc/Backspace tới được canvas
        repaint();
    }

    /** Đang trong chế độ vẽ đường mới? */
    public boolean drawLineMode() { return mode == EditMode.DRAW_LINE; }

    /** Chốt đường đang vẽ (cần ≥ 2 đỉnh — Unity yêu cầu vậy với EdgeCollider2D). */
    private void finishDraw() {
        if (mode != EditMode.DRAW_LINE || draftPts.size() < 2) return;
        double[][] pts = draftPts.toArray(new double[0][]);
        Consumer<double[][]> cb = onDrawDone;
        draftPts.clear();
        onDrawDone = null;
        setEditMode(modeBeforeDraw);
        if (cb != null) cb.accept(pts);
        repaint();
    }

    /** Huỷ đường đang vẽ và trả chế độ cũ; báo lại frame bằng {@code onDone.accept(null)}. */
    private void cancelDraw() {
        boolean drawing = (mode == EditMode.DRAW_LINE);
        Consumer<double[][]> cb = onDrawDone;
        draftPts.clear();
        onDrawDone = null;
        if (drawing) setEditMode(modeBeforeDraw);
        if (cb != null) cb.accept(null);
        repaint();
    }

    /** Index sorting layer của player (LocalPlayer = 18) — để phân loại trước/sau player. */
    public void setPlayerLayerIndex(int idx) { playerLayerIndex = idx; repaint(); }
    public int playerLayerIndex() { return playerLayerIndex; }

    /** Vẽ hình người mẫu đứng trên đường đất gần con trỏ, ĐÚNG thứ tự vẽ của player. */
    public void setPreviewPlayer(boolean v) { previewPlayer = v; repaint(); }
    public boolean previewPlayer() { return previewPlayer; }

    /** Node đang chọn đỉnh (chế độ LINE) — frame dùng để hiện thông tin đỉnh. */
    public MapScene.Node lineNode() { return lineNode; }
    public int lineVertex() { return lineVertex; }

    public String shortcutHelp() {
        return "Chuột trái: chọn/kéo · Chuột phải hoặc Space+trái: pan · Lăn: zoom quanh con trỏ · "
             + "Shift khi kéo: khoá trục (kéo handle = giữ tỉ lệ) · Alt khi kéo: TẠM TẮT hít khít · Mũi tên: nhích · "
             + "Chế độ ĐƯỜNG KẺ: kéo ĐỈNH để bẻ, kéo giữa ĐOẠN để dời cả đường, double-click trên đoạn = chèn đỉnh, Delete = xoá đỉnh · "
             + "Sửa biên: kéo 4 thanh TRÁI/PHẢI/TRÊN/DƯỚI (Shift = mở khoá trục phụ) · "
             + "Chế độ VẼ ĐƯỜNG MỚI: click từng điểm, Enter/double-click = xong, Backspace = bỏ điểm cuối, Esc = huỷ · "
             + "Ctrl+Z hoàn tác · Ctrl+Y làm lại · F canh khung · Esc bỏ chọn";
    }

    // ─────────────────────────────────────────────────────────────────────
    // Toạ độ
    // ─────────────────────────────────────────────────────────────────────

    private double sx(double ux) { return originX + ux * scale; }
    private double sy(double uy) { return originY - uy * scale; }
    private double ux(double screenX) { return (screenX - originX) / scale; }
    private double uy(double screenY) { return (originY - screenY) / scale; }

    /** Toạ độ world (unit) của TÂM khung nhìn — chỗ đặt mặc định khi thêm phần tử mới. */
    public double[] viewCenterWorld() {
        return new double[]{ux(getWidth() / 2.0), uy(getHeight() / 2.0)};
    }

    /** Hít lưới nếu đang bật snap. */
    private double sn(double v) { return snap ? Math.round(v / gridSize) * gridSize : v; }

    // ─────────────────────────────────────────────────────────────────────
    // Phân loại trước/sau player
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Node này có vẽ ĐÈ LÊN player không? So theo CẶP (sorting layer, sorting order) với (18, 1) —
     * bắt buộc so cả order vì Map15/Map9000/Map9004 dùng layer 18 + order 1000/10000 làm tiền cảnh.
     */
    public boolean isFrontOfPlayer(MapScene.Node n) {
        if (n == null || !n.hasRenderer) return false;
        if (n.sortLayerIdx != playerLayerIndex) return n.sortLayerIdx > playerLayerIndex;
        return n.sortOrder > PLAYER_BODY_ORDER;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Vẽ
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        long t0 = System.nanoTime();
        try {
            paintBody(g);
        } finally {
            // Trung bình trượt: một khung lẻ chậm (vd lần đầu nạp ảnh) không được làm timer tụt hẳn.
            double ms = (System.nanoTime() - t0) / 1e6;
            lastPaintMs = (lastPaintMs <= 0) ? ms : lastPaintMs * 0.7 + ms * 0.3;
        }
    }

    private void paintBody(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setFont(Theme.font(12, Font.PLAIN));

        // nền khung xem = BG_PANEL đặc (bản thiết kế), không còn gradient
        g2.setColor(Theme.BG_PANEL);
        g2.fillRect(0, 0, getWidth(), getHeight());

        if (scene == null) {
            g2.setColor(Theme.TEXT_DIM);
            g2.drawString("Chưa nạp map nào — chọn map ở thanh trên rồi bấm \"Nạp map\".", 20, 30);
            g2.dispose();
            return;
        }

        if (showGrid) drawGrid(g2);

        List<MapScene.Node> order = drawOrder();
        if (showSprites && !showOnlyGround) {
            if (previewPlayer) {
                // Chèn hình người mẫu ĐÚNG chỗ player nằm trong thứ tự vẽ:
                // node "sau player" vẽ trước → player → node "trước player" vẽ sau (che player).
                for (MapScene.Node n : order) if (!isFrontOfPlayer(n)) drawNode(g2, n);
                drawPlayerGhost(g2);
                for (MapScene.Node n : order) if (isFrontOfPlayer(n)) drawNode(g2, n);
            } else {
                for (MapScene.Node n : order) drawNode(g2, n);
            }
        } else if (previewPlayer) {
            drawPlayerGhost(g2);
        }

        if (markFront || previewPlayer) drawFrontBadges(g2, order);
        if (showColliders || showOnlyGround) drawColliders(g2);
        if (showBounds) drawBounds(g2);
        drawDraftLine(g2);
        drawSelection(g2);
        if (showEffectBadges) drawEffectBadges(g2);
        drawSnapGuides(g2);
        drawHud(g2);
        g2.dispose();
    }

    /**
     * Danh sách node vẽ được, sắp theo sortKey tăng dần (nhỏ vẽ trước = nằm dưới).
     *
     * <p>Node <b>Spine</b> có {@code baseW = baseH = 0} (loader cố tình giữ vậy) nên phải cho lọt
     * qua bằng cờ riêng — nhờ đi CHUNG danh sách này mà Spine vẫn được vẽ đúng thứ tự sorting
     * layer/order so với sprite thường.
     */
    private List<MapScene.Node> drawOrder() {
        List<MapScene.Node> out = new ArrayList<>();
        for (MapScene.Node n : scene.nodes()) {
            boolean sprite = n.hasRenderer && n.baseW > 0 && n.baseH > 0;
            if (!sprite && !isSpineNode(n)) continue;
            if (!showInactive && (!scene.activeInHierarchy(n) || !n.rendEnabled)) continue;
            out.add(n);
        }
        out.sort((a, b) -> Long.compare(scene.sortKey(a), scene.sortKey(b)));   // stable → giữ thứ tự file khi hoà
        return out;
    }

    /** Node này vẽ bằng Spine (có SkeletonAnimation và KHÔNG có quad sprite riêng). */
    private boolean isSpineNode(MapScene.Node n) {
        return showEffects && n.baseW <= 0 && n.fx != null && n.hasFx(MapScene.EffectKind.SPINE);
    }

    /** Ảnh của node (đã cắt sub-sprite nếu có). null = không vẽ được. */
    private BufferedImage imageOf(MapScene.Node n) {
        if (n.texture == null) return null;
        BufferedImage full = tex.image(n.texture);
        if (full == null) return null;
        if (n.subRect == null) return full;
        String key = n.texture + "#" + n.subRect[0] + "," + n.subRect[1] + "," + n.subRect[2] + "," + n.subRect[3];
        BufferedImage c = cropCache.get(key);
        if (c == null && !cropCache.containsKey(key)) {
            try {
                c = full.getSubimage(n.subRect[0], n.subRect[1], n.subRect[2], n.subRect[3]);
            } catch (Exception e) {
                c = full;   // rect hỏng → dùng cả tấm còn hơn không vẽ
            }
            cropCache.put(key, c);
        }
        return c;
    }

    private void drawNode(Graphics2D g2, MapScene.Node n) {
        boolean off = !scene.activeInHierarchy(n) || !n.rendEnabled;
        if (off && !showInactive) return;

        if (isSpineNode(n)) { drawSpineNode(g2, n, off); return; }

        double[] c = scene.worldCenter(n);
        double[] s = scene.worldSize(n);
        // HIỆU ỨNG CHUYỂN ĐỘNG chỉ làm LỆCH CHỖ VẼ — tuyệt đối không đụng px/py nên không sinh dirty.
        double[] fo = fxOffset(n);
        c[0] += fo[0];
        c[1] += fo[1];
        double dw = s[0] * scale, dh = s[1] * scale;
        if (dw < 0.4 || dh < 0.4) return;                        // nhỏ hơn nửa pixel → bỏ
        if (!onScreen(c[0], c[1], Math.max(dw, dh))) return;     // ngoài khung nhìn → bỏ (nhanh hơn)

        double ang = scene.worldAngleDeg(n);
        BufferedImage img = imageOf(n);
        // Animator sprite-swap (thác nước / bọt nước): đổi hẳn ảnh theo frame. Kích thước lấy theo
        // ẢNH FRAME (các frame có thể khác cỡ nhau) × scale tích luỹ, neo tại tâm sprite gốc.
        BufferedImage frame = animatorFrame(n);
        if (frame != null) {
            double[] wm = scene.worldMatrix(n);
            double k = Math.hypot(wm[0], wm[1]);
            s = new double[]{frame.getWidth() / (double) ppu * k, frame.getHeight() / (double) ppu * k};
            dw = s[0] * scale;
            dh = s[1] * scale;
            img = frame;
        }
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            if (n.texMissing) drawMissingBox(g2, c, s, ang);
            return;
        }

        // alpha: node ColorGround có m_Color.a = 0 → trong client không hiện, tool cũng không vẽ đè.
        double a = n.alpha;
        if (off) a *= 0.24;                                      // node tắt → mờ ~60/255
        if (a <= 0.02) return;

        Composite old = g2.getComposite();
        if (a < 0.999) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, a))));
        AffineTransform at = new AffineTransform();
        at.translate(sx(c[0]), sy(c[1]));
        if (ang != 0) at.rotate(Math.toRadians(-ang));           // world CCW → screen CW (y lật)
        at.scale(dw / img.getWidth() * (n.flipX ? -1 : 1), dh / img.getHeight() * (n.flipY ? -1 : 1));
        at.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
        try { g2.drawImage(img, at, null); } catch (Exception ignored) { }
        g2.setComposite(old);
    }

    /** Khung đỏ nét đứt cho renderer thiếu texture (không resolve được guid). */
    private void drawMissingBox(Graphics2D g2, double[] c, double[] s, double ang) {
        double[][] q = quadCorners(c, s, ang);
        g2.setColor(Theme.alpha(Theme.RED, 170));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4, 4}, 0));
        for (int i = 0; i < 4; i++) {
            double[] p = q[i], nx = q[(i + 1) % 4];
            g2.drawLine((int) sx(p[0]), (int) sy(p[1]), (int) sx(nx[0]), (int) sy(nx[1]));
        }
        g2.setStroke(new BasicStroke(1f));
    }

    /** 4 góc world của khung sprite (đã xoay). Thứ tự: BL, BR, TR, TL. */
    private static double[][] quadCorners(double[] c, double[] s, double angDeg) {
        double a = Math.toRadians(angDeg);
        double cos = Math.cos(a), sin = Math.sin(a);
        double hw = s[0] / 2, hh = s[1] / 2;
        double[][] loc = {{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}};
        double[][] out = new double[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = c[0] + loc[i][0] * cos - loc[i][1] * sin;
            out[i][1] = c[1] + loc[i][0] * sin + loc[i][1] * cos;
        }
        return out;
    }

    private boolean onScreen(double wx, double wy, double diagPx) {
        double px = sx(wx), py = sy(wy), m = diagPx + 40;
        return px > -m && py > -m && px < getWidth() + m && py < getHeight() + m;
    }

    /** Viền tím mờ + badge cho node vẽ đè lên player. */
    private void drawFrontBadges(Graphics2D g2, List<MapScene.Node> order) {
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1.4f));
        g2.setFont(Theme.font(10, Font.BOLD));
        for (MapScene.Node n : order) {
            if (!isFrontOfPlayer(n)) continue;
            double[] c = scene.worldCenter(n);
            double[] s = scene.worldSize(n);
            if (s[0] <= 1e-6 || s[1] <= 1e-6) continue;      // node Spine (không có quad) → bỏ viền
            if (!onScreen(c[0], c[1], Math.max(s[0], s[1]) * scale)) continue;
            double[][] q = quadCorners(c, s, scene.worldAngleDeg(n));
            g2.setColor(Theme.alpha(COL_FRONT, 150));
            for (int i = 0; i < 4; i++) {
                double[] p = q[i], nx = q[(i + 1) % 4];
                g2.drawLine((int) sx(p[0]), (int) sy(p[1]), (int) sx(nx[0]), (int) sy(nx[1]));
            }
            int lx = (int) sx(c[0] - s[0] / 2), ly = (int) sy(c[1] + s[1] / 2);
            g2.setColor(Theme.alpha(new Color(0, 0, 0), 170));
            g2.fillRect(lx, ly - 13, 92, 13);
            g2.setColor(COL_FRONT);
            g2.drawString("TRƯỚC PLAYER", lx + 3, ly - 3);
        }
        g2.setStroke(old);
        g2.setFont(Theme.font(12, Font.PLAIN));
    }

    // ── Collider ──

    private Color colliderColor(MapScene.Node n) {
        if (n.isTrigger) return COL_TRIGGER;
        if (n.physLayer == 6) return COL_GROUND;      // đường player đứng
        if (n.physLayer == 19) return COL_ONEWAY;     // oneway (nhảy xuyên từ dưới lên)
        return COL_OTHER;
    }

    /** Node này có phải đường đi của player (ground/oneway) không — dùng cho showOnlyGround. */
    private static boolean isWalkLine(MapScene.Node n) {
        return n.colKind == MapScene.ColKind.EDGE && !n.isTrigger && (n.physLayer == 6 || n.physLayer == 19);
    }

    private void drawColliders(Graphics2D g2) {
        Stroke old = g2.getStroke();
        boolean lineMode = (mode == EditMode.LINE);
        g2.setFont(Theme.font(11, Font.PLAIN));
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind == MapScene.ColKind.NONE) continue;
            if (showOnlyGround && !isWalkLine(n)) continue;
            if (!scene.activeInHierarchy(n) && !showInactive) continue;

            double[][] pts = scene.worldPoints(n);
            if (pts.length == 0) continue;
            boolean sel = (n == selected) || (n == lineNode);
            boolean dim = !scene.activeInHierarchy(n) || !n.colEnabled;
            boolean fresh = n.dNew;      // đường vừa tạo trong phiên, CHƯA ghi vào prefab
            Color col = colliderColor(n);
            if (dim) col = Theme.alpha(col, 70);

            g2.setColor(sel ? Theme.ACCENT : col);
            // đường MỚI vẽ nét đứt để phân biệt với đường đã có trong file
            g2.setStroke(fresh
                    ? new BasicStroke(sel ? 2.4f : 2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                            1f, new float[]{7, 5}, 0)
                    : new BasicStroke(sel ? 2.2f : (isWalkLine(n) ? 1.6f : 1f)));
            int cnt = pts.length;
            if (n.colKind == MapScene.ColKind.BOX) {
                for (int i = 0; i < cnt; i++) {
                    double[] a = pts[i], b = pts[(i + 1) % cnt];
                    g2.drawLine((int) sx(a[0]), (int) sy(a[1]), (int) sx(b[0]), (int) sy(b[1]));
                }
            } else {
                for (int i = 0; i < cnt - 1; i++) {
                    double[] a = pts[i], b = pts[i + 1];
                    g2.drawLine((int) sx(a[0]), (int) sy(a[1]), (int) sx(b[0]), (int) sy(b[1]));
                }
                // đỉnh: ô vuông — to hơn ở chế độ ĐƯỜNG KẺ để kéo cho dễ
                int r = lineMode ? 4 : 2;
                for (int i = 0; i < cnt; i++) {
                    int px = (int) sx(pts[i][0]), py = (int) sy(pts[i][1]);
                    boolean hot = (n == lineNode && i == lineVertex);
                    boolean hov = (n == hoverNode && i == hoverVertex);
                    g2.setColor(hot ? Theme.ACCENT_HOVER : hov ? Theme.ACCENT : (sel ? Theme.ACCENT : col));
                    g2.fillRect(px - r, py - r, r * 2, r * 2);
                    if (hot) {
                        g2.setColor(Theme.ON_ACCENT);
                        g2.drawRect(px - r - 2, py - r - 2, r * 2 + 4, r * 2 + 4);
                    }
                }
                // số thứ tự đỉnh (chỉ khi zoom đủ gần, khỏi rối)
                if (lineMode && scale > 14 && (sel || isWalkLine(n))) {
                    g2.setColor(Theme.alpha(Theme.TEXT_2, 190));
                    for (int i = 0; i < cnt; i++)
                        g2.drawString(String.valueOf(i), (int) sx(pts[i][0]) + 5, (int) sy(pts[i][1]) - 5);
                }
            }
            // nhãn tên ở đỉnh đầu
            g2.setColor(sel ? Theme.ACCENT : Theme.alpha(col, 210));
            String tail = n.hasPlatformEffector ? " [oneway]" : "";
            if (n.isTrigger) tail += " [trigger]";
            if (fresh) tail += " [MỚI]";
            g2.drawString(n.name + tail, (int) sx(pts[0][0]) + 5, (int) sy(pts[0][1]) - 4);
        }
        g2.setStroke(old);
        g2.setFont(Theme.font(12, Font.PLAIN));
    }

    /**
     * Đường ĐANG VẼ (chế độ DRAW_LINE): polyline vàng nét đứt qua các điểm đã click + đoạn "cao su"
     * nối điểm cuối tới con trỏ (đã hít lưới nếu bật), kèm số thứ tự từng đỉnh.
     */
    private void drawDraftLine(Graphics2D g2) {
        if (mode != EditMode.DRAW_LINE) return;
        Stroke old = g2.getStroke();
        double mx = sn(mouseWx), my = sn(mouseWy);

        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{6, 4}, 0));
        g2.setColor(Theme.ACCENT);
        for (int i = 0; i < draftPts.size() - 1; i++) {
            double[] a = draftPts.get(i), b = draftPts.get(i + 1);
            g2.drawLine((int) sx(a[0]), (int) sy(a[1]), (int) sx(b[0]), (int) sy(b[1]));
        }
        if (!draftPts.isEmpty()) {
            double[] last = draftPts.get(draftPts.size() - 1);
            g2.setColor(Theme.alpha(Theme.ACCENT, 130));
            g2.drawLine((int) sx(last[0]), (int) sy(last[1]), (int) sx(mx), (int) sy(my));
        }

        g2.setStroke(new BasicStroke(1.4f));
        g2.setFont(Theme.font(11, Font.BOLD));
        for (int i = 0; i < draftPts.size(); i++) {
            double[] p = draftPts.get(i);
            int x = (int) sx(p[0]), y = (int) sy(p[1]);
            g2.setColor(Theme.BG_APP);
            g2.fillRect(x - 4, y - 4, 8, 8);
            g2.setColor(Theme.ACCENT);
            g2.drawRect(x - 4, y - 4, 8, 8);
            g2.drawString(String.valueOf(i), x + 7, y - 6);
        }
        // chữ thập tại vị trí sẽ đặt điểm tiếp theo
        int cx = (int) sx(mx), cy = (int) sy(my);
        g2.setColor(Theme.alpha(Theme.ACCENT, 170));
        g2.drawLine(cx - 9, cy, cx + 9, cy);
        g2.drawLine(cx, cy - 9, cx, cy + 9);
        g2.setStroke(old);
        g2.setFont(Theme.font(12, Font.PLAIN));
    }

    // ── Selection + handle ──

    private void drawSelection(Graphics2D g2) {
        if (selected == null) return;
        Stroke old = g2.getStroke();
        if (!selected.hasRenderer || selected.baseW <= 0 || selected.baseH <= 0) {
            // node nhóm (Layer_Collider, Edge…) → vẽ chữ thập tại gốc transform
            double[] o = MapScene.applyPoint(scene.worldMatrix(selected), 0, 0);
            int x = (int) sx(o[0]), y = (int) sy(o[1]);
            g2.setColor(Theme.ACCENT);
            g2.setStroke(new BasicStroke(1.5f));
            // node Spine: thêm KHUNG theo hộp bao skeleton để thấy rõ đang chọn cái gì
            if (isSpineNode(selected)) {
                double[] b = spineWorldBox(selected);
                int bx = (int) sx(b[0]), by = (int) sy(b[3]);
                g2.drawRect(bx, by, Math.max(2, (int) sx(b[2]) - bx), Math.max(2, (int) sy(b[1]) - by));
            }
            g2.drawLine(x - 10, y, x + 10, y);
            g2.drawLine(x, y - 10, x, y + 10);
            g2.drawOval(x - 5, y - 5, 10, 10);
            g2.setStroke(old);
            return;
        }
        double[] c = scene.worldCenter(selected);
        double[] s = scene.worldSize(selected);
        double ang = scene.worldAngleDeg(selected);
        double[][] q = quadCorners(c, s, ang);

        g2.setColor(Theme.ACCENT);
        g2.setStroke(new BasicStroke(1.8f));
        for (int i = 0; i < 4; i++) {
            double[] p = q[i], nx = q[(i + 1) % 4];
            g2.drawLine((int) sx(p[0]), (int) sy(p[1]), (int) sx(nx[0]), (int) sy(nx[1]));
        }
        // 8 handle góc/cạnh
        for (int h = 0; h < 8; h++) {
            double[] w = handleWorld(selected, h);
            if (w == null) continue;
            int x = (int) sx(w[0]), y = (int) sy(w[1]);
            g2.setColor(Theme.BG_APP);
            g2.fillRect(x - 4, y - 4, 8, 8);
            g2.setColor(h == dragHandle ? Theme.ACCENT_HOVER : Theme.ACCENT);
            g2.drawRect(x - 4, y - 4, 8, 8);
        }

        // Nhãn "Sprite_2 · L7 #6" NGAY TRÊN khung chọn (handoff §7.4) — biết đang cầm cái gì mà
        // không phải liếc sang panel phải.
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        for (double[] p : q) { minX = Math.min(minX, sx(p[0])); minY = Math.min(minY, sy(p[1])); }
        String lbl = selected.name + (selected.hasRenderer
                ? " · L" + selected.sortLayerIdx + " #" + selected.sortOrder : "");
        g2.setFont(Theme.mono(11, Font.PLAIN));
        int lw = g2.getFontMetrics().stringWidth(lbl) + 14;
        int lx = Math.max(2, (int) minX), ly = (int) minY - 26;
        if (ly < 2) ly = (int) minY + 6;
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Theme.ACCENT_SEL);
        g2.fillRoundRect(lx, ly, lw, 20, 5, 5);
        g2.setColor(Theme.alpha(Theme.ACCENT, 128));
        g2.drawRoundRect(lx, ly, lw - 1, 19, 5, 5);
        g2.setColor(Theme.ACCENT_HOVER);
        g2.drawString(lbl, lx + 7, ly + 14);
        g2.setStroke(old);
    }

    /** Hướng của handle h: {hx, hy} ∈ {-1,0,1}, bỏ (0,0). */
    private static int[] handleDir(int h) {
        return switch (h) {
            case 0 -> new int[]{-1, -1};
            case 1 -> new int[]{0, -1};
            case 2 -> new int[]{1, -1};
            case 3 -> new int[]{-1, 0};
            case 4 -> new int[]{1, 0};
            case 5 -> new int[]{-1, 1};
            case 6 -> new int[]{0, 1};
            default -> new int[]{1, 1};
        };
    }

    /** Vị trí world của handle h trên node n. */
    private double[] handleWorld(MapScene.Node n, int h) {
        double[] c = scene.worldCenter(n);
        double[] s = scene.worldSize(n);
        if (s[0] <= 0 || s[1] <= 0) return null;
        int[] d = handleDir(h);
        double a = Math.toRadians(scene.worldAngleDeg(n));
        double cos = Math.cos(a), sin = Math.sin(a);
        double lx = d[0] * s[0] / 2, ly = d[1] * s[1] / 2;
        return new double[]{c[0] + lx * cos - ly * sin, c[1] + lx * sin + ly * cos};
    }

    // ── Player mẫu ──

    /**
     * Vẽ hình người mẫu 0.8×1.6 unit đứng trên đường đất gần con trỏ nhất.
     * Vị trí vẽ nằm GIỮA hai nhóm "sau player" và "trước player" (xem paintComponent) nên
     * user thấy ngay phần nào che player.
     */
    private void drawPlayerGhost(Graphics2D g2) {
        double[] foot = groundNear(mouseWx, mouseWy);
        double fx = (foot != null) ? foot[0] : mouseWx;
        double fy = (foot != null) ? foot[1] : mouseWy;
        int x = (int) sx(fx), yFoot = (int) sy(fy);
        int w = (int) Math.max(4, PLAYER_W * scale), h = (int) Math.max(8, PLAYER_H * scale);

        g2.setColor(Theme.alpha(Theme.BLUE, 120));
        g2.fillRoundRect(x - w / 2, yFoot - h, w, h, Math.max(3, w / 3), Math.max(3, w / 3));
        g2.setColor(Theme.BLUE);
        g2.setStroke(new BasicStroke(1.8f));
        g2.drawRoundRect(x - w / 2, yFoot - h, w, h, Math.max(3, w / 3), Math.max(3, w / 3));
        // chân bám đất
        g2.setColor(Theme.alpha(Theme.BLUE, 200));
        g2.drawLine(x - w / 2 - 4, yFoot, x + w / 2 + 4, yFoot);
        g2.setFont(Theme.font(10, Font.BOLD));
        g2.setColor(Color.WHITE);
        g2.drawString("PLAYER", x - 21, yFoot - h - 4);
        g2.setFont(Theme.font(12, Font.PLAIN));
        g2.setStroke(new BasicStroke(1f));
    }

    /**
     * Điểm mặt đất gần (wx, wy) nhất trên các đường Ground(6)/Oneway(19) — nội suy trong đoạn
     * chứa wx, chọn tầng có |Δy| nhỏ nhất. null nếu tại x đó không có đường nào.
     */
    private double[] groundNear(double wx, double wy) {
        double bestY = Double.NaN, bestD = Double.MAX_VALUE;
        for (MapScene.Node n : scene.nodes()) {
            if (!isWalkLine(n) || !scene.activeInHierarchy(n)) continue;
            double[][] p = scene.worldPoints(n);
            for (int i = 0; i < p.length - 1; i++) {
                double ax = p[i][0], ay = p[i][1], bx = p[i + 1][0], by = p[i + 1][1];
                double lo = Math.min(ax, bx), hi = Math.max(ax, bx);
                if (wx < lo || wx > hi || Math.abs(bx - ax) < 1e-9) continue;
                double y = ay + (wx - ax) / (bx - ax) * (by - ay);
                double d = Math.abs(y - wy);
                if (d < bestD) { bestD = d; bestY = y; }
            }
        }
        return Double.isNaN(bestY) ? null : new double[]{wx, bestY};
    }

    // ── Lưới + HUD ──

    private void drawGrid(Graphics2D g2) {
        double step = gridSize;
        while (step * scale < 8) step *= 2;                  // zoom xa → tự gộp ô cho khỏi rối
        double x0 = ux(0), x1 = ux(getWidth()), y0 = uy(getHeight()), y1 = uy(0);
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Theme.alpha(Theme.BORDER, 120));
        for (double gx = Math.floor(x0 / step) * step; gx <= x1; gx += step) {
            int px = (int) sx(gx);
            g2.drawLine(px, 0, px, getHeight());
        }
        for (double gy = Math.floor(y0 / step) * step; gy <= y1; gy += step) {
            int py = (int) sy(gy);
            g2.drawLine(0, py, getWidth(), py);
        }
        // trục X = 0 / Y = 0 rõ hơn
        g2.setColor(Theme.alpha(Theme.ACCENT, 110));
        g2.setStroke(new BasicStroke(1.4f));
        int ox = (int) sx(0), oy = (int) sy(0);
        g2.drawLine(ox, 0, ox, getHeight());
        g2.drawLine(0, oy, getWidth(), oy);
        g2.setStroke(old);
    }

    /**
     * Thẻ thông tin nổi ở góc dưới TRÁI + 2 chip ở góc dưới PHẢI (handoff §7.4).
     *
     * <p>Bản cũ nhồi cả {@code "chọn: Sprite_2 · layer 7 / order 6 · sau player"} vào thẻ này —
     * phần đó đã chuyển sang panel phải để <b>SỬA ĐƯỢC</b>, không chỉ đọc. Thẻ ở đây chỉ còn số
     * liệu bám con trỏ + các dòng nhắc tạm thời (hít khít, kéo biên, vẽ đường).
     */
    private void drawHud(Graphics2D g2) {
        java.util.List<String[]> rows = new ArrayList<>();     // {nhãn, giá trị, màu}
        rows.add(new String[]{"chuột", String.format(java.util.Locale.US, "%.2f, %.2f unit", mouseWx, mouseWy), "t2"});
        rows.add(new String[]{"server", (int) Math.round(mouseWx * ppu) + ", " + (int) Math.round(mouseWy * ppu), "t2"});
        if (playEffects) {
            rows.add(new String[]{"hiệu ứng",
                    String.format(java.util.Locale.US, "t = %.2fs · ×%.2f", fxTime, effectSpeed), "green"});
        }
        MapScene.Node vn = (lineVertex >= 0) ? lineNode : hoverNode;
        int vi = (lineVertex >= 0) ? lineVertex : hoverVertex;
        if (vn != null && vi >= 0) {
            double[][] p = scene.worldPoints(vn);
            if (vi < p.length) {
                rows.add(new String[]{"đỉnh", String.format(java.util.Locale.US, "#%d/%d · %.2f, %.2f",
                        vi, p.length - 1, p[vi][0], p[vi][1]), "blue"});
            }
        }
        if (mode == EditMode.DRAW_LINE) {
            rows.add(new String[]{"vẽ đường", draftPts.size() + " điểm · Enter xong · Esc huỷ", "accent"});
        }
        if (previewPlayer) rows.add(new String[]{"xem trước", "phần TÍM = vẽ đè lên player", "front"});
        if (boundHint != null) rows.add(new String[]{"biên", boundHint, "bound"});
        if (snapHint != null) rows.add(new String[]{"hít", snapHint, "snap"});

        String modeTxt = switch (mode) {
            case SELECT -> editBounds ? "BIÊN MAP" : "CHỌN";
            case MOVE -> "DI CHUYỂN";
            case LINE -> "ĐƯỜNG KẺ";
            case DRAW_LINE -> "VẼ ĐƯỜNG MỚI";
        };
        String title = "Map " + scene.mapId();
        String chipTxt = "chế độ " + modeTxt;
        String zoomTxt = String.format(java.util.Locale.US, "zoom %.1f px/u", scale);

        Font fLab = Theme.font(12, Font.PLAIN), fVal = Theme.mono(12, Font.PLAIN);
        FontMetrics fmL = g2.getFontMetrics(fLab), fmV = g2.getFontMetrics(fVal);
        int labW = 64;
        int need = 0;
        for (String[] r : rows) need = Math.max(need, labW + 10 + fmV.stringWidth(r[1]));
        int headW = g2.getFontMetrics(Theme.font(13, Font.BOLD)).stringWidth(title) + 8
                + fmL.stringWidth(chipTxt) + 22 + 16 + g2.getFontMetrics(Theme.mono(11, Font.PLAIN)).stringWidth(zoomTxt);
        int w = Math.min(Math.max(getWidth() - 32, 200), Math.max(330, Math.max(need, headW) + 28));
        int h = 12 + 18 + 9 + 1 + 9 + rows.size() * 17 + 3;
        int x = 16, y = getHeight() - 74 - h;
        if (y < 10) y = 10;

        g2.setColor(new Color(10, 10, 13, 240));
        g2.fillRoundRect(x, y, w, h, 10, 10);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(x, y, w - 1, h - 1, 10, 10);

        int cy = y + 12;
        g2.setFont(Theme.font(13, Font.BOLD));
        g2.setColor(Theme.TEXT);
        g2.drawString(title, x + 14, cy + 14);
        int cx = x + 14 + g2.getFontMetrics().stringWidth(title) + 8;
        int chipW = fmL.stringWidth(chipTxt) + 16;
        g2.setColor(Theme.ACCENT_SEL);
        g2.fillRoundRect(cx, cy + 1, chipW, 20, 999, 999);
        g2.setColor(Theme.alpha(Theme.ACCENT, 102));
        g2.drawRoundRect(cx, cy + 1, chipW - 1, 19, 999, 999);
        g2.setFont(Theme.font(11, Font.BOLD));
        g2.setColor(Theme.ACCENT_HOVER);
        g2.drawString(chipTxt, cx + 8, cy + 15);
        g2.setFont(Theme.mono(11, Font.PLAIN));
        g2.setColor(Theme.TEXT_DIM);
        g2.drawString(zoomTxt, x + w - 14 - g2.getFontMetrics().stringWidth(zoomTxt), cy + 14);

        cy += 18 + 9;
        g2.setColor(Theme.DIVIDER);
        g2.drawLine(x + 14, cy, x + w - 14, cy);
        cy += 9 + 12;
        for (String[] r : rows) {
            g2.setFont(fLab);
            g2.setColor(Theme.TEXT_DIM);
            g2.drawString(r[0], x + 14, cy);
            g2.setFont(fVal);
            g2.setColor(switch (r[2]) {
                case "green" -> COL_FX;
                case "blue" -> Theme.BLUE;
                case "accent" -> Theme.ACCENT;
                case "front" -> COL_FRONT;
                case "bound" -> COL_BOUND;
                case "snap" -> COL_SNAP;
                default -> Theme.TEXT_2;
            });
            g2.drawString(clipTo(g2, r[1], w - labW - 28), x + 14 + labW, cy);
            cy += 17;
        }

        drawCornerChips(g2);
    }

    /** 2 chip nhắc ở góc dưới PHẢI: biên map + số vùng trigger. */
    private void drawCornerChips(Graphics2D g2) {
        int box = 0;
        for (MapScene.Node n : scene.nodes()) if (n.colKind == MapScene.ColKind.BOX) box++;
        String[] texts = {"Left / Right / Top / Bottom = biên map", box + " trigger"};
        g2.setFont(Theme.font(12, Font.PLAIN));
        FontMetrics fm = g2.getFontMetrics();
        int x = getWidth() - 16, y = getHeight() - 14 - 28;
        for (int i = texts.length - 1; i >= 0; i--) {
            int w = fm.stringWidth(texts[i]) + 20;
            x -= w;
            g2.setColor(new Color(10, 10, 13, 230));
            g2.fillRoundRect(x, y, w, 28, 999, 999);
            g2.setColor(Theme.BORDER);
            g2.drawRoundRect(x, y, w - 1, 27, 999, 999);
            g2.setColor(Theme.TEXT_MUTED);
            g2.drawString(texts[i], x + 10, y + 19);
            x -= 8;
        }
    }

    private static String clipTo(Graphics2D g2, String s, int max) {
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(s) <= max) return s;
        for (int i = s.length() - 1; i > 1; i--) {
            String t = s.substring(0, i) + "…";
            if (fm.stringWidth(t) <= max) return t;
        }
        return "…";
    }

    private static String fmt(double v) {
        String s = String.format(java.util.Locale.US, "%.2f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Y3 — HIỆU ỨNG: Spine · mô phỏng script · Animator sprite-swap
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Skeleton của node Spine, lấy từ cache TĨNH theo THƯ MỤC ({@code n.fxFolder} do loader suy ra
     * từ {@code skeletonDataAsset.guid}).
     *
     * <p>{@link SpineCharacter#load} đọc được CẢ {@code .json} lẫn {@code .skel.bytes} nhị phân,
     * nên null ở đây nghĩa là skeleton HỎNG THẬT (thiếu atlas/PNG, file lỗi, guid trỏ sai thư mục)
     * — chứ không còn là "chưa hỗ trợ nhị phân". Lần hỏng được nhớ lại trong {@link #SPINE_BAD}
     * nên chỉ thử ĐÚNG 1 LẦN và chỉ log 1 dòng.
     */
    private static SpineCharacter spineOf(MapScene.Node n) {
        Path f = (n == null) ? null : n.fxFolder;
        if (f == null || SPINE_BAD.contains(f)) return null;
        SpineCharacter sc = SPINE_CACHE.get(f);
        if (sc != null) return sc;
        sc = SpineCharacter.load(f);
        if (sc == null) {
            SPINE_BAD.add(f);
            System.out.println("[MapLayoutCanvas] không đọc được skeleton (thiếu atlas/PNG hoặc file hỏng): " + f);
            return null;
        }
        SPINE_CACHE.put(f, sc);
        return sc;
    }

    /**
     * Hộp bao skeleton {@code {minX, minY, maxX, maxY}} theo UNITY UNIT (đã nhân {@code assetScale}),
     * tính ĐÚNG 1 LẦN mỗi skeleton rồi cache.
     *
     * <p>Lấy hợp của hai nguồn vì không nguồn nào đủ một mình: (1) vị trí world của các bone ở
     * setup pose — đúng với skeleton nhiều bone như CayDua (91 bone) mà JSON lại THIẾU
     * {@code skeleton.width/height}; (2) khung {@code skeleton.x/y/width/height} trong JSON — đúng
     * với skeleton 1 bone như haiau (106×59). Suy biến cả hai ⇒ hộp mặc định 1.5 unit.
     */
    private static double[] spineBox(SpineCharacter sc, Path key) {
        double[] cached = SPINE_BOX.get(key);
        if (cached != null) return cached;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        try {
            sc.skeleton.pose(null, 0f);                    // setup pose
            for (int i = 0; i < sc.skeleton.wx.length; i++) {
                minX = Math.min(minX, sc.skeleton.wx[i]); maxX = Math.max(maxX, sc.skeleton.wx[i]);
                minY = Math.min(minY, sc.skeleton.wy[i]); maxY = Math.max(maxY, sc.skeleton.wy[i]);
            }
        } catch (Exception ignored) { }
        SpineData d = sc.data;
        if (d.skelWidth > 0 && d.skelHeight > 0) {
            minX = Math.min(minX, d.skelX); maxX = Math.max(maxX, d.skelX + d.skelWidth);
            minY = Math.min(minY, d.skelY); maxY = Math.max(maxY, d.skelY + d.skelHeight);
        }
        double[] box;
        if (minX > maxX || maxX - minX < 1e-6 || maxY - minY < 1e-6) {
            box = new double[]{-0.75, 0, 0.75, 1.5};       // không đoán được → hộp giữ chỗ
        } else {
            float s = sc.assetScale;
            box = new double[]{minX * s, minY * s, maxX * s, maxY * s};
        }
        SPINE_BOX.put(key, box);
        return box;
    }

    /** Hộp bao WORLD {minX, minY, maxX, maxY} của node Spine (đã cộng lệch hiệu ứng). */
    private double[] spineWorldBox(MapScene.Node n) {
        double[] m = scene.worldMatrix(n);
        double[] o = MapScene.applyPoint(m, 0, 0);
        double[] fo = fxOffset(n);
        double kx = Math.hypot(m[0], m[1]), ky = Math.hypot(m[2], m[3]);
        SpineCharacter sc = spineOf(n);
        double[] b = (sc != null && n.fxFolder != null) ? spineBox(sc, n.fxFolder)
                                                        : new double[]{-0.75, 0, 0.75, 1.5};
        double cx = o[0] + fo[0], cy = o[1] + fo[1];
        return new double[]{cx + b[0] * kx, cy + b[1] * ky, cx + b[2] * kx, cy + b[3] * ky};
    }

    /**
     * Vẽ 1 node Spine đúng vị trí / scale / xoay của nó.
     *
     * <p>{@code sss} bên trong {@code SpineCharacter.render} = {@code heightPx / skelHeight}, mà ta
     * truyền {@code heightPx = worldHeight() × kx × zoom = skelHeight × assetScale × kx × zoom}
     * ⇒ {@code sss = assetScale × kx × zoom} — ĐÚNG tỉ lệ kể cả khi JSON thiếu width/height.
     */
    private void drawSpineNode(Graphics2D g2, MapScene.Node n, boolean off) {
        double[] m = scene.worldMatrix(n);
        double[] o = MapScene.applyPoint(m, 0, 0);
        double[] fo = fxOffset(n);
        double wx = o[0] + fo[0], wy = o[1] + fo[1];
        double kx = Math.hypot(m[0], m[1]);
        SpineCharacter sc = spineOf(n);
        if (sc == null) {                                   // skeleton hỏng thật → ô vuông giữ chỗ
            if (onScreen(wx, wy, 2 * scale)) drawSpinePlaceholder(g2, n, wx, wy, kx);
            return;
        }
        // Cull theo HỘP BAO THẬT, không theo heightPx: JSON của CayDua thiếu skeleton.width/height
        // nên worldHeight() trả 1.0 unit trong khi cây thật cao ~6 unit (tài liệu 06 §C.5 mục 2).
        double[] wb = spineWorldBox(n);
        double diag = Math.max(wb[2] - wb[0], wb[3] - wb[1]) * scale;
        if (diag < SPINE_MIN_PX) return;                    // quá nhỏ → bỏ (mesh-clip rất tốn CPU)
        if (!onScreen((wb[0] + wb[2]) / 2, (wb[1] + wb[3]) / 2, diag)) return;
        double heightPx = sc.worldHeight() * kx * scale;     // tham số của render(): sss = heightPx / skelHeight

        double px = sx(wx), py = sy(wy);
        String an = scene.fxParam(n, "_animationName", null);
        SpineData.Animation anim = (an != null && !an.isEmpty()) ? sc.data.animations.get(an) : null;
        if (anim == null) anim = sc.pickAnim(an);           // rỗng / tên lạ → anim đầu tiên
        double ts = scene.fxParamD(n, "timeScale", 1);
        if (ts <= 0) ts = 1;
        boolean loop = scene.fxParamD(n, "loop", 1) != 0;
        boolean flipX = scene.fxParamD(n, "initialFlipX", 0) != 0;
        boolean flipY = scene.fxParamD(n, "initialFlipY", 0) != 0;
        if (n.fxMotion() == MapScene.EffectKind.FISH_SWIM && fishFacingLeft(n)) flipX = !flipX;

        double t = playEffects ? fxTime : 0;
        // loop = 0: client dừng ở frame cuối, còn render() luôn lặp ⇒ tự kẹp lại trước khi gọi.
        if (!loop && anim != null && anim.duration > 0) t = Math.min(t, anim.duration / ts - 1e-4);

        Composite oc = g2.getComposite();
        AffineTransform at = g2.getTransform();
        try {
            if (off) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
            double ang = scene.worldAngleDeg(n);
            if (Math.abs(ang) > 1e-6) g2.rotate(Math.toRadians(-ang), px, py);
            if (flipY) { g2.translate(0, py); g2.scale(1, -1); g2.translate(0, -py); }
            sc.render(g2, px, py, heightPx, (float) t, anim, flipX, (float) ts);
        } catch (Exception ignored) {
            // skeleton lỗi giữa chừng → bỏ qua node này, KHÔNG làm hỏng cả khung hình
        } finally {
            g2.setTransform(at);
            g2.setComposite(oc);
        }
    }

    /**
     * Ô vuông giữ chỗ + tên skeleton cho Spine KHÔNG ĐỌC ĐƯỢC. Từ khi có bộ đọc nhị phân,
     * chỗ này chỉ còn dùng cho hỏng thật (thiếu atlas/PNG, file lỗi) nên nhãn ghi "(lỗi)".
     */
    private void drawSpinePlaceholder(Graphics2D g2, MapScene.Node n, double wx, double wy, double kx) {
        double[] b = spineWorldBox(n);
        int x0 = (int) sx(b[0]), x1 = (int) sx(b[2]);
        int y0 = (int) sy(b[3]), y1 = (int) sy(b[1]);
        Stroke old = g2.getStroke();
        g2.setColor(Theme.alpha(COL_FX, 120));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{5, 4}, 0));
        g2.drawRect(x0, y0, Math.max(2, x1 - x0), Math.max(2, y1 - y0));
        g2.setStroke(old);
        if (scale > 4) {
            g2.setFont(Theme.font(10, Font.PLAIN));
            g2.setColor(Theme.alpha(COL_FX, 200));
            g2.drawString((n.fxName != null ? n.fxName : "Spine") + " (lỗi)", x0 + 3, y0 - 3);
            g2.setFont(Theme.font(12, Font.PLAIN));
        }
    }

    // ── Mô phỏng hiệu ứng CHUYỂN ĐỘNG (chỉ lệch chỗ vẽ, KHÔNG đụng dữ liệu node) ──

    private static final double[] NO_OFFSET = {0, 0};

    /**
     * Độ lệch WORLD của node tại thời điểm {@link #fxTime} do hiệu ứng chuyển động gây ra.
     * LUÔN trả {0,0} khi đang tắt "chạy hiệu ứng" (t = 0) ⇒ canvas tĩnh vẽ đúng vị trí thật.
     *
     * <p>Công thức chép từ chính file C# của client (tài liệu 06 §B).
     */
    private double[] fxOffset(MapScene.Node n) {
        if (!showEffects || !playEffects || n == null || n.fxList.isEmpty()) return NO_OFFSET;
        double t = fxTime;
        return switch (n.fxMotion()) {
            case WATER_WAVE -> waterWaveOffset(n, t);
            case FISH_SWIM  -> fishSwimOffset(n, t);
            case WAVE_WASH  -> waveWashOffset(n, t);
            default         -> NO_OFFSET;     // WATER2D chỉ cuộn UV, không đổi transform
        };
    }

    /**
     * WaterWaveMover — "dòng nước chảy": Horizontal thì +X mãi tới {@code endPoint} rồi nhảy về
     * {@code startPoint} ± {@code randomOffset}; Vertical thì −Y. Lấy hiệu 2 vị trí (t và 0) nên
     * độ lệch tại t = 0 LUÔN bằng 0, node đứng đúng chỗ thật.
     */
    private double[] waterWaveOffset(MapScene.Node n, double t) {
        MapScene.Node sN = scene.byTr(fileIdOf(scene.fxParam(n, "startPoint", null)));
        MapScene.Node eN = scene.byTr(fileIdOf(scene.fxParam(n, "endPoint", null)));
        if (sN == null || eN == null) return NO_OFFSET;
        double[] sp = scene.worldOrigin(sN), ep = scene.worldOrigin(eN), cur = scene.worldOrigin(n);
        double speed = scene.fxParamD(n, "moveSpeed", 2);
        double rnd = scene.fxParamD(n, "randomOffset", 0);
        boolean vertical = scene.fxParamD(n, "movementDirection", 0) != 0;
        if (speed <= 0) return NO_OFFSET;

        double span = vertical ? (sp[1] - ep[1]) : (ep[0] - sp[0]);
        if (span <= 1e-4) return NO_OFFSET;                 // start/end đặt ngược → coi như đứng yên
        double u0 = vertical ? (sp[1] - cur[1]) : (cur[0] - sp[0]);
        double u = u0 + speed * t;
        long c0 = (long) Math.floor(u0 / span), c1 = (long) Math.floor(u / span);
        double d = (u - c1 * span) - (u0 - c0 * span) + jitter(n.goAnchor, c1 - c0, rnd);
        return vertical ? new double[]{0, -d} : new double[]{d, 0};
    }

    /**
     * FishSwim — bơi ngang kiểu tam giác (ping-pong) quanh vị trí gốc với biên {@code _swimRange},
     * cộng nhấp nhô {@code sin(t·_bobSpeed)·_bobAmplitude} theo trục Y.
     */
    private double[] fishSwimOffset(MapScene.Node n, double t) {
        double sp = scene.fxParamD(n, "_swimSpeed", 0);
        double range = scene.fxParamD(n, "_swimRange", 0);
        double amp = scene.fxParamD(n, "_bobAmplitude", 0);
        double bsp = scene.fxParamD(n, "_bobSpeed", 1);
        double dx = (sp > 0 && range > 0) ? fishX(sp, range, scene.fxParamD(n, "_startMovingRight", 1) != 0, t) : 0;
        double dy = (amp != 0) ? Math.sin(t * bsp) * amp : 0;
        return new double[]{dx, dy};
    }

    /** Vị trí X (lệch so với gốc) của cá tại thời điểm t — tam giác chu kỳ {@code 4·range/speed}. */
    private static double fishX(double speed, double range, boolean startRight, double t) {
        double period = 4 * range;
        double u = (speed * t + (startRight ? 0 : 2 * range)) % period;
        if (u < 0) u += period;
        if (u <= range) return u;                       // đi phải
        if (u <= 3 * range) return 2 * range - u;       // quay lại, đi trái
        return u - 4 * range;                           // đi phải về gốc
    }

    /** Cá đang bơi sang TRÁI không (để lật hình) — cùng công thức với {@link #fishX}. */
    private boolean fishFacingLeft(MapScene.Node n) {
        double sp = scene.fxParamD(n, "_swimSpeed", 0);
        double range = scene.fxParamD(n, "_swimRange", 0);
        if (sp <= 0 || range <= 0) return false;
        boolean startRight = scene.fxParamD(n, "_startMovingRight", 1) != 0;
        double t = playEffects ? fxTime : 0;
        double period = 4 * range;
        double u = (sp * t + (startRight ? 0 : 2 * range)) % period;
        if (u < 0) u += period;
        boolean movingRight = !(u > range && u <= 3 * range);
        boolean artRight = scene.fxParamD(n, "_artFacesRight", 1) != 0;
        return artRight != movingRight;                 // C#: sign = (_artFacesRight == _movingRight) ? +1 : −1
    }

    /**
     * WaveWash — sóng vỗ bờ: dịch theo {@code direction.normalized × distance × wash(t/period % 1)}.
     * Đây là LOCAL position nên phải đưa qua ma trận của CHA mới ra lệch world.
     */
    private double[] waveWashOffset(MapScene.Node n, double t) {
        double dist = scene.fxParamD(n, "distance", 0);
        double period = scene.fxParamD(n, "period", 0);
        if (dist == 0 || period <= 1e-4) return NO_OFFSET;
        double[] dir = vec2Of(scene.fxParam(n, "direction", null));
        double len = Math.hypot(dir[0], dir[1]);
        if (len < 1e-6) return NO_OFFSET;
        double phase = (t / period) % 1.0;
        if (phase < 0) phase += 1;
        double w = washCurve(phase, scene.fxParamD(n, "attackStart", 0),
                             scene.fxParamD(n, "riseDuration", 0.25),
                             scene.fxParamD(n, "releaseStart", 0.5));
        double ox = dir[0] / len * dist * w, oy = dir[1] / len * dist * w;
        double[] pm = scene.parentMatrix(n);
        return new double[]{pm[0] * ox + pm[2] * oy, pm[1] * ox + pm[3] * oy};
    }

    /** {@code WaveWash.Evaluate} nguyên văn: lên bằng smoothStep, giữ đỉnh, rồi rút xuống. */
    private static double washCurve(double t, double attackStart, double riseDuration, double releaseStart) {
        double riseEnd = Math.min(attackStart + riseDuration, releaseStart);
        if (t < attackStart) return 0;
        if (t < riseEnd) {
            double span = riseEnd - attackStart;
            return span > 1e-4 ? smoothStep(0, 1, (t - attackStart) / span) : 1;
        }
        if (t < releaseStart) return 1;
        double fall = 1 - releaseStart;
        return fall > 1e-4 ? smoothStep(1, 0, (t - releaseStart) / fall) : 0;
    }

    private static double smoothStep(double a, double b, double x) {
        double u = Math.max(0, Math.min(1, x));
        return a + (b - a) * u * u * (3 - 2 * u);
    }

    /**
     * Nhiễu "ngẫu nhiên" TẤT ĐỊNH cho mỗi chu kỳ của WaterWaveMover — cùng node + cùng chu kỳ thì
     * luôn ra cùng giá trị, nên xem lại/tua lại không bị giật lung tung. Chu kỳ 0 = 0 (giữ đúng chỗ).
     */
    private static double jitter(long seed, long cycle, double amp) {
        if (amp <= 0 || cycle == 0) return 0;
        long h = seed * 1315423911L + cycle * 2654435761L;
        h ^= (h >>> 31);
        h *= 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        double u = ((h >>> 11) / (double) (1L << 53)) * 2 - 1;   // −1 … +1
        return u * amp;
    }

    /** Bóc {@code fileID} trong chuỗi thô kiểu {@code "{fileID: 123456}"} (0 = không có). */
    private static long fileIdOf(String raw) {
        if (raw == null) return 0;
        int i = raw.indexOf("fileID:");
        if (i < 0) return 0;
        int j = i + 7;
        while (j < raw.length() && raw.charAt(j) == ' ') j++;
        int k = j;
        if (k < raw.length() && raw.charAt(k) == '-') k++;
        while (k < raw.length() && Character.isDigit(raw.charAt(k))) k++;
        try {
            return Long.parseLong(raw.substring(j, k));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Bóc {@code {x: 0, y: -0.2}} thành {x, y}. */
    private static double[] vec2Of(String raw) {
        double[] out = {0, 0};
        if (raw == null) return out;
        out[0] = num(raw, "x:");
        out[1] = num(raw, "y:");
        return out;
    }

    private static double num(String raw, String key) {
        int i = raw.indexOf(key);
        if (i < 0) return 0;
        int j = i + key.length();
        while (j < raw.length() && raw.charAt(j) == ' ') j++;
        int k = j;
        while (k < raw.length() && "+-.eE0123456789".indexOf(raw.charAt(k)) >= 0) k++;
        try {
            return Double.parseDouble(raw.substring(j, k));
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Animator sprite-swap (thác nước / bọt nước) ──

    /**
     * 1 clip đổi frame đọc từ cặp {@code .controller} + {@code .anim} (tài liệu 06 §D).
     * Không cần parse state-machine: cả 3 controller trang trí map đều 1 layer / 1 state / 0 tham số.
     */
    private static final class AnimClip {
        double[] times;      // mốc thời gian từng frame (giây, tăng dần)
        Path[] frames;       // file .png tương ứng
        double stopTime = 1; // m_StopTime của clip
        double speed = 1;    // m_Speed của AnimatorState
    }

    /** Ảnh frame hiện tại của Animator trên node (null = không mô phỏng được → vẽ tĩnh). */
    private BufferedImage animatorFrame(MapScene.Node n) {
        if (!showEffects || n.texture == null || !n.isSprite) return null;
        MapScene.Fx f = n.fxOf(MapScene.EffectKind.ANIMATOR);
        if (f == null || f.folder == null) return null;
        if ("0".equals(f.params.get("m_Enabled"))) return null;      // Animator bị tắt trong prefab
        AnimClip c = animClip(f.folder);
        if (c == null || c.frames.length == 0) return null;
        double t = playEffects ? fxTime : 0;
        double tt = (c.stopTime > 1e-6) ? ((t * c.speed) % c.stopTime) : 0;
        if (tt < 0) tt += c.stopTime;
        int idx = 0;
        for (int i = 0; i < c.times.length; i++) {
            if (c.times[i] <= tt + 1e-9) idx = i; else break;    // PPtr là STEP, không nội suy
        }
        return tex.image(c.frames[idx]);
    }

    /** Đọc + cache clip của 1 file .controller (hỏng thì nhớ luôn để khỏi đọc lại). */
    private static AnimClip animClip(Path controller) {
        if (ANIM_BAD.contains(controller)) return null;
        AnimClip c = ANIM_CACHE.get(controller);
        if (c != null) return c;
        c = readAnimClip(controller);
        if (c == null) { ANIM_BAD.add(controller); return null; }
        ANIM_CACHE.put(controller, c);
        return c;
    }

    /**
     * .controller → guid clip + m_Speed → file .anim (tra guid qua các file .meta CÙNG THƯ MỤC) →
     * {@code m_PPtrCurves} → danh sách (time, .png). Thiếu bất cứ khâu nào ⇒ null (vẽ tĩnh + nhãn).
     */
    private static AnimClip readAnimClip(Path controller) {
        try {
            Path dir = controller.getParent();
            if (dir == null) return null;
            Map<String, Path> byGuid = guidMapOf(dir);

            String motion = null;
            double speed = 1;
            for (String l : Files.readAllLines(controller)) {
                String t = l.trim();
                if (t.startsWith("m_Speed:")) {
                    try { speed = Double.parseDouble(t.substring(8).trim()); } catch (Exception ignored) { }
                } else if (t.startsWith("m_Motion:")) {
                    String g = guidIn(t);
                    if (g != null) { motion = g; break; }        // state ĐẦU TIÊN có clip
                }
            }
            if (motion == null) return null;
            Path anim = byGuid.get(motion);
            if (anim == null || !Files.isRegularFile(anim)) return null;

            List<Double> times = new ArrayList<>();
            List<Path> frames = new ArrayList<>();
            double stop = 0;
            boolean inCurves = false;
            double pending = Double.NaN;
            for (String l : Files.readAllLines(anim)) {
                String t = l.trim();
                if (t.startsWith("m_StopTime:")) {
                    try { stop = Double.parseDouble(t.substring(11).trim()); } catch (Exception ignored) { }
                    continue;
                }
                if (t.startsWith("m_PPtrCurves:")) { inCurves = !t.endsWith("[]"); continue; }
                if (!inCurves) continue;
                if (l.startsWith("  m_") || (!l.isEmpty() && l.charAt(0) != ' ')) { inCurves = false; continue; }
                if (t.startsWith("- time:")) {
                    pending = parseD(t.substring(7));
                } else if (t.startsWith("time:")) {
                    pending = parseD(t.substring(5));
                } else if (t.startsWith("value:")) {
                    String g = guidIn(t);
                    Path png = (g != null) ? byGuid.get(g) : null;
                    if (png != null && !Double.isNaN(pending)) { times.add(pending); frames.add(png); }
                    pending = Double.NaN;
                }
            }
            if (frames.size() < 2) return null;                  // 1 frame thì mô phỏng cũng như tĩnh

            AnimClip c = new AnimClip();
            c.times = new double[times.size()];
            for (int i = 0; i < times.size(); i++) c.times[i] = times.get(i);
            c.frames = frames.toArray(new Path[0]);
            c.speed = (speed > 0) ? speed : 1;
            c.stopTime = (stop > 1e-6) ? stop : (c.times[c.times.length - 1] + 1.0 / 12);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** guid → file, quét mọi {@code *.meta} trong ĐÚNG thư mục đó (không đệ quy). */
    private static Map<String, Path> guidMapOf(Path dir) {
        Map<String, Path> out = new HashMap<>();
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String fn = p.getFileName().toString();
                if (!fn.endsWith(".meta")) continue;
                for (String l : Files.readAllLines(p)) {
                    String t = l.trim();
                    if (t.startsWith("guid:")) {
                        out.put(t.substring(5).trim().toLowerCase(Locale.ROOT),
                                p.resolveSibling(fn.substring(0, fn.length() - 5)));
                        break;
                    }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static String guidIn(String line) {
        int i = line.indexOf("guid:");
        if (i < 0) return null;
        int j = i + 5;
        while (j < line.length() && line.charAt(j) == ' ') j++;
        int k = j;
        while (k < line.length() && Character.isLetterOrDigit(line.charAt(k))) k++;
        return (k - j == 32) ? line.substring(j, k).toLowerCase(Locale.ROOT) : null;
    }

    private static double parseD(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // ── Nhãn loại hiệu ứng ──

    /** Nhãn ngắn của hiệu ứng chính trên node (null = không hiện). */
    private static String fxBadge(MapScene.Node n) {
        return switch (n.fx) {
            case SPINE      -> "Spine" + (n.fxName != null && !n.fxName.isEmpty() ? " · " + n.fxName : "");
            case ANIMATOR   -> "» Animator";
            case WATER_WAVE -> "Nước chảy";
            case FISH_SWIM  -> "Cá bơi";
            case WAVE_WASH  -> "Sóng vỗ";
            case WATER2D    -> "Cuộn nước";
            default         -> null;
        };
    }

    /**
     * Nhãn nhỏ trên từng node hiệu ứng. Chống rối bằng LƯỚI CHIẾM CHỖ: mỗi ô ~96×16 px chỉ cho
     * đúng 1 nhãn (Map13 có 27 node hiệu ứng chen nhau ở mức zoom toàn map), và tối đa 80 nhãn.
     */
    private void drawEffectBadges(Graphics2D g2) {
        if (!showEffects || scale < 2) return;
        g2.setFont(Theme.font(10, Font.BOLD));
        Set<Long> taken = new HashSet<>();
        int drawn = 0;
        for (MapScene.Node n : scene.nodes()) {
            if (drawn >= 80) break;
            if (n.fx == null || !n.fx.real()) continue;
            if (!showInactive && !scene.activeInHierarchy(n)) continue;
            String txt = fxBadge(n);
            if (txt == null) continue;
            double bx, by;
            if (isSpineNode(n)) {
                double[] b = spineWorldBox(n);
                bx = b[0];
                by = b[3];
            } else {
                double[] c = scene.worldCenter(n);
                double[] s = scene.worldSize(n);
                double[] fo = fxOffset(n);
                bx = c[0] + fo[0] - s[0] / 2;
                by = c[1] + fo[1] + s[1] / 2;
            }
            if (!onScreen(bx, by, 40)) continue;
            int x = (int) sx(bx), y = (int) sy(by);
            if (!taken.add((long) (x / 96) * 100000L + (y / 16))) continue;   // ô này đã có nhãn
            int w = g2.getFontMetrics().stringWidth(txt) + 8;
            g2.setColor(new Color(0, 0, 0, 165));
            g2.fillRoundRect(x, y - 14, w, 13, 5, 5);
            g2.setColor(Theme.alpha(COL_FX, 235));
            g2.drawString(txt, x + 4, y - 4);
            drawn++;
        }
        g2.setFont(Theme.font(12, Font.PLAIN));
    }

    // ═════════════════════════════════════════════════════════════════════
    // Y2 — 4 THANH BIÊN MAP + khung camera
    // ═════════════════════════════════════════════════════════════════════

    /** Tên hiển thị của 4 biên theo chỉ số 0..3. */
    private static final String[] BOUND_NAME = {"TRÁI", "PHẢI", "DƯỚI", "TRÊN"};
    private static final String[] BOUND_KEY = {"left", "right", "bottom", "top"};

    /** Node của thanh biên thứ {@code i} (0 trái · 1 phải · 2 đáy · 3 đỉnh). */
    private MapScene.Node boundNode(int i) {
        return (i < 0 || i > 3 || scene == null) ? null : scene.boundsEdge(BOUND_KEY[i]);
    }

    /**
     * Vẽ 4 thanh biên (đậm 3 px, có nhãn + toạ độ), khung camera kết quả (xanh lá nét đứt) và
     * vùng tâm camera bị kẹp ở tỉ lệ 20:9 — nhìn là biết ngay kéo vào có làm vỡ camera không.
     */
    private void drawBounds(Graphics2D g2) {
        double[] cb = scene.cameraBounds();
        if (cb == null) return;
        Stroke old = g2.getStroke();
        int W = getWidth(), H = getHeight();

        // khung camera kết quả
        int x0 = (int) sx(cb[0]), x1 = (int) sx(cb[2]);
        int y0 = (int) sy(cb[3]), y1 = (int) sy(cb[1]);
        g2.setColor(Theme.alpha(COL_CAM, 190));
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{9, 6}, 0));
        g2.drawRect(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));

        // vùng tâm camera bị kẹp (20:9) — hẹp hơn nghĩa là camera còn lia được
        double halfX = CAM_HALF_Y * CAM_ASPECT;
        double iw = (cb[2] - cb[0]) - 2 * halfX, ih = (cb[3] - cb[1]) - 2 * CAM_HALF_Y;
        if (iw > 0 && ih > 0) {
            g2.setColor(Theme.alpha(COL_CAM, 70));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{3, 5}, 0));
            int ix0 = (int) sx(cb[0] + halfX), ix1 = (int) sx(cb[2] - halfX);
            int iy0 = (int) sy(cb[3] - CAM_HALF_Y), iy1 = (int) sy(cb[1] + CAM_HALF_Y);
            g2.drawRect(Math.min(ix0, ix1), Math.min(iy0, iy1), Math.abs(ix1 - ix0), Math.abs(iy1 - iy0));
        }

        // 4 thanh
        g2.setFont(Theme.font(11, Font.BOLD));
        for (int i = 0; i < 4; i++) {
            double v = switch (i) { case 0 -> cb[0]; case 1 -> cb[2]; case 2 -> cb[1]; default -> cb[3]; };
            boolean hot = (draggingBound == i) || (hoverBound == i && editBounds);
            g2.setColor(hot ? Theme.ACCENT : Theme.alpha(COL_BOUND, editBounds ? 235 : 165));
            g2.setStroke(new BasicStroke(hot ? 4f : 3f));
            String lbl = BOUND_NAME[i] + "  " + fmt(v) + " u  (" + Math.round(v * ppu) + ")";
            if (i < 2) {
                int px = (int) sx(v);
                g2.drawLine(px, 0, px, H);
                g2.drawString(lbl, px + 6, 190 + i * 16);   // dưới hộp HUD, khỏi bị che
            } else {
                int py = (int) sy(v);
                g2.drawLine(0, py, W, py);
                g2.drawString(lbl, 12 + (i - 2) * 190, py - 6);
            }
        }

        // kích thước + cảnh báo
        double w = cb[2] - cb[0], h = cb[3] - cb[1];
        String size = String.format(java.util.Locale.US, "camera: %.2f × %.2f u  (%d × %d server)",
                w, h, Math.round(w * ppu), Math.round(h * ppu));
        boolean bad = w < 2 * halfX || h < 2 * CAM_HALF_Y;
        g2.setColor(bad ? Theme.RED : Theme.alpha(COL_CAM, 220));
        g2.setFont(Theme.font(11, Font.BOLD));
        g2.drawString(size + (bad ? "  ! HẸP HƠN MÀN HÌNH 20:9 → camera sẽ giật!" : ""),
                Math.min(x0, x1) + 6, Math.min(y0, y1) + 14);
        g2.setStroke(old);
        g2.setFont(Theme.font(12, Font.PLAIN));
    }

    /** Thanh biên nằm trong {@value #HIT_PX} px quanh điểm màn hình (−1 = không trúng). */
    private int pickBound(int mx, int my) {
        if (scene == null) return -1;
        double[] cb = scene.cameraBounds();
        if (cb == null) return -1;
        int best = -1;
        double bd = HIT_PX + 2;
        double[] d = {Math.abs(sx(cb[0]) - mx), Math.abs(sx(cb[2]) - mx),
                      Math.abs(sy(cb[1]) - my), Math.abs(sy(cb[3]) - my)};
        for (int i = 0; i < 4; i++) if (d[i] < bd) { bd = d[i]; best = i; }
        return best;
    }

    /** Dòng HUD khi kéo biên: giá trị mới + kích thước khung camera ngay lập tức. */
    private void updateBoundHint(int i, double v) {
        double[] cb = scene.cameraBounds();
        String s = BOUND_NAME[i] + " = " + fmt(v) + " u (" + Math.round(v * ppu) + " server)";
        if (cb != null) {
            double w = cb[2] - cb[0], h = cb[3] - cb[1];
            s += String.format(java.util.Locale.US, "   ·   camera %.2f × %.2f u (%d × %d)",
                    w, h, Math.round(w * ppu), Math.round(h * ppu));
            if (w < 2 * CAM_HALF_Y * CAM_ASPECT || h < 2 * CAM_HALF_Y) s += "   ! HẸP HƠN 20:9!";
        }
        boundHint = s;
    }

    // ═════════════════════════════════════════════════════════════════════
    // Y1 — HÍT KHÍT: dựng ngữ cảnh, áp kết quả, vẽ đường gióng
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Dựng danh sách hình đích + mốc phụ cho lần kéo sắp tới. Gọi ĐÚNG 1 LẦN ở {@code mousePressed}
     * (trong lúc kéo các vật khác đứng yên nên không cần dựng lại mỗi frame).
     *
     * <p>Bỏ node Spine / node có hiệu ứng chuyển động ra khỏi danh sách: chúng tự di chuyển lúc
     * chạy game nên hít vào chúng là vô nghĩa (tài liệu 08 §B.2).
     */
    private void beginSnapContext(MapScene.Node moving) {
        List<SnapEngine.Rect> rs = new ArrayList<>();
        List<Double> ex = new ArrayList<>(), ey = new ArrayList<>();
        // Mốc phụ chỉ lấy quanh vật đang kéo: đường kẻ đất có hàng trăm đỉnh, gom hết thì mốc nào
        // cũng "gần" và đường gióng nhảy loạn.
        double[] mc = (moving == null) ? new double[]{mouseWx, mouseWy}
                : (moving.hasRenderer && moving.baseW > 0 ? scene.worldCenter(moving) : scene.worldOrigin(moving));
        final double NEAR = 40;
        for (MapScene.Node n : scene.nodes()) {
            if (n == moving) continue;
            if (!scene.activeInHierarchy(n)) continue;
            if (n.hasRenderer && n.baseW > 0 && n.baseH > 0 && n.alpha > 0.02
                    && n.fxMotion() == MapScene.EffectKind.NONE && !n.hasFx(MapScene.EffectKind.SPINE)) {
                double[] c = scene.worldCenter(n);
                double[] s = scene.worldSize(n);
                double[] r = aabb(c[0], c[1], s, scene.worldAngleDeg(n));
                rs.add(new SnapEngine.Rect(r[0], r[1], r[2], r[3], n.name));
            }
            // đỉnh đường kẻ đất/oneway → mốc phụ (dán mép sprite lên đúng đường player đi)
            if (isWalkLine(n) && ex.size() < 600) {
                for (double[] p : scene.worldPoints(n)) {
                    if (Math.abs(p[0] - mc[0]) > NEAR || Math.abs(p[1] - mc[1]) > NEAR) continue;
                    ex.add(p[0]);
                    ey.add(p[1]);
                }
            }
        }
        double[] cb = scene.cameraBounds();
        if (cb != null) {
            ex.add(cb[0]); ex.add(cb[2]);
            ey.add(cb[1]); ey.add(cb[3]);
        }
        snapOthers = rs;
        snapExtraX = toArray(ex);
        snapExtraY = toArray(ey);
    }

    private static double[] toArray(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** Hộp bao world (AABB) của hình chữ nhật tâm (cx,cy), cỡ s, xoay {@code angDeg}. */
    private static double[] aabb(double cx, double cy, double[] s, double angDeg) {
        double a = Math.toRadians(angDeg);
        double c = Math.abs(Math.cos(a)), si = Math.abs(Math.sin(a));
        double hw = (s[0] * c + s[1] * si) / 2, hh = (s[0] * si + s[1] * c) / 2;
        return new double[]{cx - hw, cy - hh, cx + hw, cy + hh};
    }

    private void clearGuides() {
        guideX = List.of();
        guideY = List.of();
        snapHint = null;
    }

    /**
     * Ghi lại đường gióng + dòng HUD của kết quả hít. CỘNG DỒN chứ không ghi đè, vì kéo handle góc
     * gọi hai lần (một lần cho trục X, một lần cho trục Y) và cả hai đều cần hiện đường gióng.
     */
    private void takeGuides(SnapEngine.Result r) {
        if (!r.hit()) return;
        if (r.hitX()) guideX = r.guidesX();
        if (r.hitY()) guideY = r.guidesY();
        StringBuilder sb = new StringBuilder();
        if (r.hitX()) sb.append(guideX.get(0).label()).append(String.format(java.util.Locale.US, " (lệch %.3f)", r.dx()));
        if (r.hitX() && r.hitY()) sb.append("   ·   ");
        if (r.hitY()) sb.append(guideY.get(0).label()).append(String.format(java.util.Locale.US, " (lệch %.3f)", r.dy()));
        snapHint = (snapHint == null) ? "♦ hít khít: " + sb : snapHint + "   ·   " + sb;
    }

    /**
     * Hít cho TÂM node đang kéo. Alt hoặc tắt hít khít ⇒ trả về hành vi cũ (chỉ hít lưới).
     *
     * @return {x, y} world đã hít
     */
    private double[] snapCenter(MapScene.Node n, double cx, double cy, boolean alt) {
        clearGuides();
        if (alt || !snapEng.enabled()) return new double[]{sn(cx), sn(cy)};
        boolean box = n != null && n.hasRenderer && n.baseW > 0 && n.baseH > 0;
        SnapEngine.Result r;
        if (box) {
            double[] s = scene.worldSize(n);
            double[] b = aabb(cx, cy, s, scene.worldAngleDeg(n));
            r = snapEng.snapRect(new SnapEngine.Rect(b[0], b[1], b[2], b[3], ""),
                    snapOthers, snapExtraX, snapExtraY, scale);
        } else {
            r = snapEng.snapPoint(cx, cy, snapOthers, snapExtraX, snapExtraY, scale);
        }
        takeGuides(r);
        return new double[]{cx + r.dx(), cy + r.dy()};
    }

    /** Hít cho 1 ĐIỂM (kéo đỉnh đường kẻ). */
    private double[] snapPoint(double wx, double wy, boolean alt) {
        clearGuides();
        if (alt || !snapEng.enabled()) return new double[]{sn(wx), sn(wy)};
        SnapEngine.Result r = snapEng.snapPoint(wx, wy, snapOthers, snapExtraX, snapExtraY, scale);
        takeGuides(r);
        return new double[]{wx + r.dx(), wy + r.dy()};
    }

    /** Hít cho 1 CẠNH đang kéo (thanh biên map / handle scale). Trả về toạ độ đã hít. */
    private double snapEdgeCoord(double coord, boolean horizontal, boolean alt) {
        if (alt || !snapEng.enabled()) return coord;
        SnapEngine.Result r = snapEng.snapEdge(coord, horizontal, snapOthers, scale);
        takeGuides(r);
        return coord + (horizontal ? r.dx() : r.dy());
    }

    /**
     * Đường gióng cạnh: đường 1 px màu hồng kéo dài qua CẢ HAI vật + nhãn nhỏ nói rõ mép nào khít
     * mép nào ("mép phải ↔ mép trái · Lop_2_7 (3)").
     */
    private void drawSnapGuides(Graphics2D g2) {
        if (guideX.isEmpty() && guideY.isEmpty()) return;
        Stroke old = g2.getStroke();
        g2.setStroke(new BasicStroke(1f));
        g2.setFont(Theme.font(10, Font.BOLD));
        for (SnapEngine.Target t : guideX) {
            int x = (int) sx(t.value());
            int ya, yb;
            if (Math.abs(t.to() - t.from()) < 1e-9) { ya = 0; yb = getHeight(); }
            else { ya = (int) sy(t.to()) - 20; yb = (int) sy(t.from()) + 20; }
            g2.setColor(Theme.alpha(COL_SNAP, 230));
            g2.drawLine(x, ya, x, yb);
            g2.drawLine(x - 3, ya, x + 3, ya);
            g2.drawLine(x - 3, yb, x + 3, yb);
            drawGuideLabel(g2, t.label(), x + 5, Math.max(14, ya + 12));
        }
        for (SnapEngine.Target t : guideY) {
            int y = (int) sy(t.value());
            int xa, xb;
            if (Math.abs(t.to() - t.from()) < 1e-9) { xa = 0; xb = getWidth(); }
            else { xa = (int) sx(t.from()) - 20; xb = (int) sx(t.to()) + 20; }
            g2.setColor(Theme.alpha(COL_SNAP, 230));
            g2.drawLine(xa, y, xb, y);
            g2.drawLine(xa, y - 3, xa, y + 3);
            g2.drawLine(xb, y - 3, xb, y + 3);
            drawGuideLabel(g2, t.label(), Math.max(4, xa + 6), y - 4);
        }
        g2.setStroke(old);
        g2.setFont(Theme.font(12, Font.PLAIN));
    }

    private void drawGuideLabel(Graphics2D g2, String s, int x, int y) {
        int w = g2.getFontMetrics().stringWidth(s) + 8;
        g2.setColor(new Color(0, 0, 0, 185));
        g2.fillRoundRect(x, y - 11, w, 13, 5, 5);
        g2.setColor(COL_SNAP);
        g2.drawString(s, x + 4, y - 1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hit-test
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Điểm world → toạ độ UV trong khung sprite (u sang phải, v hướng lên, 0..1 = trong khung).
     * Đã khử flip để tra đúng pixel trên ảnh gốc.
     */
    private double[] uvOf(MapScene.Node n, double wx, double wy) {
        double[] c = scene.worldCenter(n);
        double[] s = scene.worldSize(n);
        if (s[0] <= 1e-9 || s[1] <= 1e-9) return null;
        double a = Math.toRadians(scene.worldAngleDeg(n));
        double cos = Math.cos(a), sin = Math.sin(a);
        double dx = wx - c[0], dy = wy - c[1];
        double lx = dx * cos + dy * sin;        // nghịch đảo phép xoay
        double ly = -dx * sin + dy * cos;
        double u = lx / s[0] + 0.5;
        double v = ly / s[1] + 0.5;
        if (u < 0 || u > 1 || v < 0 || v > 1) return null;
        if (n.flipX) u = 1 - u;
        if (n.flipY) v = 1 - v;
        return new double[]{u, v};
    }

    /**
     * Node có renderer NẰM TRÊN CÙNG tại (wx, wy): duyệt NGƯỢC thứ tự vẽ. Có texture thì test theo
     * pixel alpha (> 8) để không bắt nhầm vùng trong suốt; không vẽ được thì chỉ test khung.
     */
    private MapScene.Node pickNode(double wx, double wy) {
        List<MapScene.Node> order = drawOrder();
        for (int i = order.size() - 1; i >= 0; i--) {
            MapScene.Node n = order.get(i);
            if (isSpineNode(n)) {                                     // Spine: bắt theo HỘP BAO
                double[] b = spineWorldBox(n);
                if (wx >= b[0] && wx <= b[2] && wy >= b[1] && wy <= b[3]) return n;
                continue;
            }
            double[] uv = uvOf(n, wx, wy);
            if (uv == null) continue;
            BufferedImage img = imageOf(n);
            if (img == null || n.alpha <= 0.02) return n;             // không có ảnh → bắt theo khung
            int px = (int) (uv[0] * img.getWidth());
            int py = (int) ((1 - uv[1]) * img.getHeight());           // ảnh Java: y từ trên xuống
            px = Math.max(0, Math.min(img.getWidth() - 1, px));
            py = Math.max(0, Math.min(img.getHeight() - 1, py));
            int alpha = (img.getRGB(px, py) >>> 24) & 0xff;
            if (alpha > ALPHA_HIT) return n;
        }
        return null;
    }

    /** Đỉnh collider gần điểm màn hình nhất trong bán kính HIT_PX. Trả {nodeIndex→node, idx} qua mảng. */
    private Object[] pickVertex(double wx, double wy) {
        double tol = HIT_PX / scale;
        MapScene.Node bn = null;
        int bi = -1;
        double bd = tol;
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind != MapScene.ColKind.EDGE) continue;
            if (showOnlyGround && !isWalkLine(n)) continue;
            if (!scene.activeInHierarchy(n) && !showInactive) continue;
            double[][] p = scene.worldPoints(n);
            for (int i = 0; i < p.length; i++) {
                double d = Math.hypot(p[i][0] - wx, p[i][1] - wy);
                if (d <= bd) { bd = d; bn = n; bi = i; }
            }
        }
        return new Object[]{bn, bi};
    }

    /** Đoạn (segment) collider gần điểm nhất: trả {node, chỉ số đỉnh đầu đoạn} hoặc {null,-1}. */
    private Object[] pickSegment(double wx, double wy) {
        double tol = (HIT_PX + 3) / scale;
        MapScene.Node bn = null;
        int bi = -1;
        double bd = tol;
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind != MapScene.ColKind.EDGE) continue;
            if (showOnlyGround && !isWalkLine(n)) continue;
            if (!scene.activeInHierarchy(n) && !showInactive) continue;
            double[][] p = scene.worldPoints(n);
            for (int i = 0; i < p.length - 1; i++) {
                double d = distToSeg(wx, wy, p[i][0], p[i][1], p[i + 1][0], p[i + 1][1]);
                if (d <= bd) { bd = d; bn = n; bi = i; }
            }
        }
        return new Object[]{bn, bi};
    }

    private static double distToSeg(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay;
        double len2 = vx * vx + vy * vy;
        double t = (len2 < 1e-12) ? 0 : ((px - ax) * vx + (py - ay) * vy) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * vx), py - (ay + t * vy));
    }

    private int pickHandle(int mx, int my) {
        if (selected == null || !selected.hasRenderer || selected.baseW <= 0) return -1;
        for (int h = 0; h < 8; h++) {
            double[] w = handleWorld(selected, h);
            if (w == null) continue;
            if (Math.abs(sx(w[0]) - mx) <= HIT_PX && Math.abs(sy(w[1]) - my) <= HIT_PX) return h;
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Chuột
    // ─────────────────────────────────────────────────────────────────────

    private void setupMouse() {
        MouseAdapter ma = new MouseAdapter() {

            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (scene == null) return;
                double wx = ux(e.getX()), wy = uy(e.getY());
                mouseWx = wx; mouseWy = wy;

                // pan: chuột phải / chuột giữa / Space + trái
                if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)
                        || (spaceDown && SwingUtilities.isLeftMouseButton(e))) {
                    lastPan = e.getPoint();
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                pushedThisDrag = false;

                // 0) VẼ ĐƯỜNG MỚI: mỗi click = 1 đỉnh; click thứ 2 tại chỗ cũ (double-click) = chốt.
                if (mode == EditMode.DRAW_LINE) {
                    if (e.getClickCount() >= 2) finishDraw();
                    else draftPts.add(new double[]{sn(wx), sn(wy)});
                    repaint();
                    return;
                }

                // 1) KÉO THANH BIÊN MAP (khi đang bật "sửa biên") — ưu tiên cao nhất vì thanh phủ
                //    hết màn hình, người dùng bấm trúng là muốn kéo nó.
                if (editBounds) {
                    int bi = pickBound(e.getX(), e.getY());
                    MapScene.Node bn = boundNode(bi);
                    if (bn != null) {
                        draggingBound = bi;
                        double[] o = scene.worldOrigin(bn);
                        bndOrigX = o[0];
                        bndOrigY = o[1];
                        dragStartWx = wx; dragStartWy = wy;
                        beginSnapContext(bn);
                        select(bn);
                        updateBoundHint(bi, (bi < 2) ? o[0] : o[1]);
                        repaint();
                        return;
                    }
                }

                // 2) handle scale của node đang chọn
                int h = pickHandle(e.getX(), e.getY());
                if (h >= 0 && mode != EditMode.LINE) {
                    beginHandleDrag(h, wx, wy);
                    return;
                }

                // 3) chế độ ĐƯỜNG KẺ: bắt đỉnh → kéo đỉnh; trúng ĐOẠN → kéo CẢ đường (đổi Transform)
                if (mode == EditMode.LINE) {
                    Object[] v = pickVertex(wx, wy);
                    if (v[0] != null) {
                        lineNode = (MapScene.Node) v[0];
                        lineVertex = (Integer) v[1];
                        draggingVertex = true;
                        beginSnapContext(lineNode);
                        select(lineNode);
                        repaint();
                        return;
                    }
                    Object[] sg = pickSegment(wx, wy);
                    if (sg[0] != null) {
                        lineNode = (MapScene.Node) sg[0];
                        lineVertex = -1;
                        // bấm vào ĐOẠN (không trúng đỉnh) = kéo cả thanh: đổi vị trí Transform,
                        // KHÔNG đụng m_Points — đúng cách client dùng cho 4 biên map.
                        draggingWhole = true;
                        double[] o = scene.worldOrigin(lineNode);
                        wholeOrigX = o[0];
                        wholeOrigY = o[1];
                        dragStartWx = wx; dragStartWy = wy;
                        beginSnapContext(lineNode);
                        select(lineNode);
                        repaint();
                        return;
                    }
                    lastPan = e.getPoint();     // click vào chỗ trống → pan
                    return;
                }

                // 4) chọn / kéo sprite
                MapScene.Node hit = pickNode(wx, wy);
                if (hit == null) {
                    // vẫn cho chọn collider bằng cách click lên đường
                    Object[] sg = pickSegment(wx, wy);
                    if (sg[0] != null) {
                        lineNode = (MapScene.Node) sg[0];
                        select(lineNode);
                        repaint();
                        return;
                    }
                    if (selected != null) { select(null); repaint(); }
                    lastPan = e.getPoint();
                    return;
                }
                boolean wasSelected = (hit == selected);
                if (!wasSelected) select(hit);
                // MOVE: kéo ngay; SELECT: chỉ kéo node ĐANG chọn (tránh xê dịch nhầm khi chỉ muốn chọn)
                if (mode == EditMode.MOVE || wasSelected) {
                    draggingNode = true;
                    dragStartWx = wx; dragStartWy = wy;
                    double[] c = scene.worldCenter(hit);
                    dragOrigCx = c[0]; dragOrigCy = c[1];
                    beginSnapContext(hit);
                }
                repaint();
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (scene == null) return;
                double wx = ux(e.getX()), wy = uy(e.getY());
                mouseWx = wx; mouseWy = wy;

                if (lastPan != null) {
                    originX += e.getX() - lastPan.x;
                    originY += e.getY() - lastPan.y;
                    lastPan = e.getPoint();
                    repaint();
                    return;
                }
                boolean alt = e.isAltDown();          // Alt = TẠM TẮT hít khít cho lần kéo này

                // ── kéo 1 THANH BIÊN MAP: trái/phải chỉ đổi X, trên/dưới chỉ đổi Y ──
                if (draggingBound >= 0) {
                    MapScene.Node bn = boundNode(draggingBound);
                    if (bn == null) return;
                    if (!pushedThisDrag) { pushUndo(); pushedThisDrag = true; }
                    clearGuides();                               // xoá đường gióng của frame trước
                    boolean vertBar = draggingBound < 2;          // 0/1 = thanh DỌC (đổi X)
                    double nx = bndOrigX, ny = bndOrigY;
                    if (vertBar) {
                        nx = snapEdgeCoord(bndOrigX + (wx - dragStartWx), true, alt);
                        if (e.isShiftDown()) ny = bndOrigY + (wy - dragStartWy);   // Shift = mở khoá trục phụ
                    } else {
                        ny = snapEdgeCoord(bndOrigY + (wy - dragStartWy), false, alt);
                        if (e.isShiftDown()) nx = bndOrigX + (wx - dragStartWx);
                    }
                    scene.setWorldOrigin(bn, nx, ny);
                    updateBoundHint(draggingBound, vertBar ? nx : ny);
                    fireChange();
                    repaint();
                    return;
                }

                if (dragHandle >= 0 && selected != null) { dragHandle(wx, wy, e.isShiftDown(), alt); return; }

                // ── kéo CẢ đường kẻ (đổi Transform, giữ nguyên m_Points) ──
                if (draggingWhole && lineNode != null) {
                    if (!pushedThisDrag) { pushUndo(); pushedThisDrag = true; }
                    double dx = wx - dragStartWx, dy = wy - dragStartWy;
                    if (e.isShiftDown()) {
                        if (Math.abs(dx) >= Math.abs(dy)) dy = 0; else dx = 0;
                    }
                    double[] p = snapCenter(lineNode, wholeOrigX + dx, wholeOrigY + dy, alt);
                    scene.setWorldOrigin(lineNode, p[0], p[1]);
                    fireChange();
                    repaint();
                    return;
                }

                if (draggingVertex && lineNode != null && lineVertex >= 0) {
                    if (!pushedThisDrag) { pushUndo(); pushedThisDrag = true; }
                    double[] p = snapPoint(wx, wy, alt);
                    scene.setColliderWorldPoint(lineNode, lineVertex, p[0], p[1]);
                    fireChange();
                    repaint();
                    return;
                }
                if (draggingNode && selected != null) {
                    if (!pushedThisDrag) { pushUndo(); pushedThisDrag = true; }
                    double dx = wx - dragStartWx, dy = wy - dragStartWy;
                    if (e.isShiftDown()) {                       // Shift = khoá trục
                        if (Math.abs(dx) >= Math.abs(dy)) dy = 0; else dx = 0;
                    }
                    double[] p = snapCenter(selected, dragOrigCx + dx, dragOrigCy + dy, alt);
                    scene.setWorldCenter(selected, p[0], p[1]);
                    fireChange();
                    repaint();
                }
            }

            @Override public void mouseReleased(MouseEvent e) {
                lastPan = null;
                draggingNode = false;
                draggingVertex = false;
                draggingWhole = false;
                draggingBound = -1;
                dragHandle = -1;
                pushedThisDrag = false;
                clearGuides();
                boundHint = null;
                repaint();
            }

            @Override public void mouseMoved(MouseEvent e) {
                if (scene == null) return;
                mouseWx = ux(e.getX());
                mouseWy = uy(e.getY());
                if (mode == EditMode.LINE) {
                    Object[] v = pickVertex(mouseWx, mouseWy);
                    hoverNode = (MapScene.Node) v[0];
                    hoverVertex = (Integer) v[1];
                } else {
                    hoverNode = null;
                    hoverVertex = -1;
                }
                int hb = editBounds ? pickBound(e.getX(), e.getY()) : -1;
                if (hb != hoverBound) {                  // đổi con trỏ cho biết kéo được theo trục nào
                    hoverBound = hb;
                    if (hb < 0) applyMode(mode);
                    else setCursor(Cursor.getPredefinedCursor(
                            hb < 2 ? Cursor.E_RESIZE_CURSOR : Cursor.N_RESIZE_CURSOR));
                }
                repaint();       // HUD bám theo chuột + preview player bám đường đất
            }

            @Override public void mouseClicked(MouseEvent e) {
                if (scene == null || e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) return;
                if (mode != EditMode.LINE) return;
                double wx = ux(e.getX()), wy = uy(e.getY());
                Object[] v = pickVertex(wx, wy);
                if (v[0] != null) return;                        // đang ở sát 1 đỉnh → không chèn
                Object[] sg = pickSegment(wx, wy);
                if (sg[0] == null) return;
                MapScene.Node n = (MapScene.Node) sg[0];
                int i = (Integer) sg[1];
                pushUndo();
                scene.insertColliderPoint(n, i, sn(wx), sn(wy));
                lineNode = n;
                lineVertex = i + 1;
                select(n);
                fireChange();
                repaint();
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double wx = ux(e.getX()), wy = uy(e.getY());
                double factor = e.getPreciseWheelRotation() < 0 ? 1.1 : 1 / 1.1;
                scale = Math.max(0.05, Math.min(4000, scale * factor));
                originX = e.getX() - wx * scale;                  // zoom quanh con trỏ
                originY = e.getY() + wy * scale;
                repaint();
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
    }

    /** Ghi lại trạng thái lúc bắt đầu kéo handle (điểm neo = handle đối diện, giữ CỐ ĐỊNH). */
    private void beginHandleDrag(int h, double wx, double wy) {
        dragHandle = h;
        int[] d = handleDir(h);
        hDirX = d[0]; hDirY = d[1];
        hSx0 = selected.sx; hSy0 = selected.sy;
        double[] s = scene.worldSize(selected);
        double[] c = scene.worldCenter(selected);
        hW0 = s[0]; hH0 = s[1];
        hAngRad = Math.toRadians(scene.worldAngleDeg(selected));
        double cos = Math.cos(hAngRad), sin = Math.sin(hAngRad);
        double lx = -hDirX * hW0 / 2, ly = -hDirY * hH0 / 2;      // handle đối diện, hệ local
        hAx = c[0] + lx * cos - ly * sin;
        hAy = c[1] + lx * sin + ly * cos;
        dragStartWx = wx; dragStartWy = wy;
        pushedThisDrag = false;
        beginSnapContext(selected);
    }

    /**
     * Kéo handle = đổi scale. Chiếu vector (chuột − neo) lên 2 trục local của node để ra hệ số
     * phóng, rồi đặt lại tâm sao cho điểm neo đứng yên.
     *
     * <p>HÍT KHÍT ở đây chỉ khớp CẠNH ĐANG KÉO (không khớp tâm) và chỉ áp cho node KHÔNG xoay —
     * đúng thao tác "dãn tile đất cho vừa khít tile bên cạnh" đã tạo ra Map37000.
     */
    private void dragHandle(double wx, double wy, boolean keepRatio, boolean alt) {
        if (hW0 <= 1e-9 || hH0 <= 1e-9) return;
        if (!pushedThisDrag) { pushUndo(); pushedThisDrag = true; }
        double cos = Math.cos(hAngRad), sin = Math.sin(hAngRad);
        double dx = wx - hAx, dy = wy - hAy;
        double du = dx * cos + dy * sin;      // theo trục u (bề ngang node)
        double dv = -dx * sin + dy * cos;     // theo trục v (bề dọc node)

        double fu = (hDirX != 0) ? du / (hDirX * hW0) : 1;
        double fv = (hDirY != 0) ? dv / (hDirY * hH0) : 1;

        // hít cạnh: neo đứng yên nên cạnh đang kéo = neo + hướng × bề rộng mới ⇒ suy ngược ra hệ số
        clearGuides();
        if (!alt && !keepRatio && Math.abs(hAngRad) < 1e-6) {
            if (hDirX != 0) {
                double edge = hAx + hDirX * hW0 * fu;
                double snapped = snapEdgeCoord(edge, true, false);
                if (Math.abs(snapped - edge) > 1e-9) fu = (snapped - hAx) / (hDirX * hW0);
            }
            if (hDirY != 0) {
                double edge = hAy + hDirY * hH0 * fv;
                double snapped = snapEdgeCoord(edge, false, false);
                if (Math.abs(snapped - edge) > 1e-9) fv = (snapped - hAy) / (hDirY * hH0);
            }
        }
        if (keepRatio) {                      // Shift = giữ tỉ lệ
            double f = (hDirX != 0 && hDirY != 0) ? Math.max(fu, fv) : (hDirX != 0 ? fu : fv);
            fu = (hDirX != 0) ? f : 1;
            fv = (hDirY != 0) ? f : 1;
        }
        fu = Math.max(0.02, fu);              // không cho lật/thu về 0 bằng chuột
        fv = Math.max(0.02, fv);

        selected.sx = hSx0 * fu;
        selected.sy = hSy0 * fv;
        selected.dTransform = true;

        double w1 = hW0 * fu, h1 = hH0 * fv;
        double lx = hDirX * w1 / 2, ly = hDirY * h1 / 2;
        double cx = hAx + lx * cos - ly * sin;
        double cy = hAy + lx * sin + ly * cos;
        scene.setWorldCenter(selected, cx, cy);   // gọi SAU khi đổi sx/sy (pivot phụ thuộc scale)
        fireChange();
        repaint();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Bàn phím
    // ─────────────────────────────────────────────────────────────────────

    private void setupKeys() {
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.VK_SPACE) { spaceDown = true; return; }
                if (e.isControlDown() && k == KeyEvent.VK_Z) { if (e.isShiftDown()) redo(); else undo(); return; }
                if (e.isControlDown() && k == KeyEvent.VK_Y) { redo(); return; }
                if (scene == null) return;

                // Đang VẼ ĐƯỜNG MỚI: chỉ nhận Enter (chốt) / Backspace (bỏ điểm cuối) / Esc (huỷ).
                if (mode == EditMode.DRAW_LINE) {
                    if (k == KeyEvent.VK_ENTER) finishDraw();
                    else if (k == KeyEvent.VK_ESCAPE) cancelDraw();
                    else if (k == KeyEvent.VK_BACK_SPACE || k == KeyEvent.VK_DELETE) {
                        if (!draftPts.isEmpty()) draftPts.remove(draftPts.size() - 1);
                        repaint();
                    } else if (k == KeyEvent.VK_F) {
                        fitView();
                    }
                    return;
                }

                if (k == KeyEvent.VK_DELETE || k == KeyEvent.VK_BACK_SPACE) {
                    if (lineNode != null && lineVertex >= 0) {
                        int before = (lineNode.pts == null) ? 0 : lineNode.pts.length;
                        pushUndo();
                        scene.removeColliderPoint(lineNode, lineVertex);
                        int after = (lineNode.pts == null) ? 0 : lineNode.pts.length;
                        if (after == before) undo.pop();          // không xoá được (còn ≤ 2 đỉnh) → bỏ undo rỗng
                        else {
                            lineVertex = Math.min(lineVertex, after - 1);
                            fireChange();
                        }
                        repaint();
                    }
                    return;
                }
                if (k == KeyEvent.VK_ESCAPE) { lineVertex = -1; select(null); repaint(); return; }
                if (k == KeyEvent.VK_F) { fitView(); return; }

                double step = snap ? gridSize : 0.1;
                if (e.isShiftDown()) step *= 5;
                if (e.isControlDown()) step = 1.0 / ppu;          // Ctrl = nhích 1 đơn vị server
                double dx = 0, dy = 0;
                switch (k) {
                    case KeyEvent.VK_LEFT -> dx = -step;
                    case KeyEvent.VK_RIGHT -> dx = step;
                    case KeyEvent.VK_UP -> dy = step;
                    case KeyEvent.VK_DOWN -> dy = -step;
                    default -> { return; }
                }
                if (mode == EditMode.LINE && lineNode != null && lineVertex >= 0) {
                    double[][] p = scene.worldPoints(lineNode);
                    if (lineVertex < p.length) {
                        pushUndo();
                        scene.setColliderWorldPoint(lineNode, lineVertex, p[lineVertex][0] + dx, p[lineVertex][1] + dy);
                        fireChange();
                        repaint();
                    }
                } else if (selected != null) {
                    pushUndo();
                    double[] c = scene.worldCenter(selected);
                    scene.setWorldCenter(selected, c[0] + dx, c[1] + dy);
                    fireChange();
                    repaint();
                }
            }

            @Override public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_SPACE) spaceDown = false;
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Undo / Redo — snapshot toàn scene (60 bước)
    // ─────────────────────────────────────────────────────────────────────

    /** Ảnh chụp 1 node: mọi trường sửa được + cờ dirty (để hoàn tác trả đúng trạng thái "chưa lưu"). */
    private static final class NodeSnap {
        final double px, py, sx, sy, rot;
        final int sortLayerIdx, sortOrder;
        final long sortLayerId;
        final boolean active, rendEnabled, flipX, flipY;
        final String texGuid;
        final java.nio.file.Path texture;
        final double[][] pts;
        final boolean dT, dS, dA, dP, dTex, dRe, dN, dLB;
        // ── phần CẤU TRÚC: đủ để node bị xoá "sống lại" y như cũ ──
        final String name;
        final int physLayer;
        final boolean hasEffector;
        final long effAnchor;
        final double effRot, effArc;
        final MapScene.ColKind colKind;
        final double offX, offY;
        final long parentTr;
        final long[] childTr;

        NodeSnap(MapScene.Node n) {
            px = n.px; py = n.py; sx = n.sx; sy = n.sy; rot = n.rotDeg;
            sortLayerIdx = n.sortLayerIdx; sortOrder = n.sortOrder; sortLayerId = n.sortLayerId;
            active = n.active; rendEnabled = n.rendEnabled; flipX = n.flipX; flipY = n.flipY;
            texGuid = n.texGuid; texture = n.texture;
            if (n.pts == null) pts = null;
            else {
                pts = new double[n.pts.length][];
                for (int i = 0; i < n.pts.length; i++) pts[i] = n.pts[i].clone();
            }
            dT = n.dTransform; dS = n.dSorting; dA = n.dActive;
            dP = n.dPoints; dTex = n.dTexture; dRe = n.dRendEnabled; dN = n.dNew; dLB = n.dLineBlock;
            name = n.name; physLayer = n.physLayer; hasEffector = n.hasPlatformEffector;
            effAnchor = n.effAnchor; effRot = n.effRotOffset; effArc = n.effSurfaceArc;
            colKind = n.colKind; offX = n.offX; offY = n.offY;
            parentTr = n.parentTr;
            childTr = (n.childTr != null) ? n.childTr.clone() : null;
        }

        void restore(MapScene.Node n) {
            n.px = px; n.py = py; n.sx = sx; n.sy = sy; n.rotDeg = rot;
            n.sortLayerIdx = sortLayerIdx; n.sortOrder = sortOrder; n.sortLayerId = sortLayerId;
            n.active = active; n.rendEnabled = rendEnabled; n.flipX = flipX; n.flipY = flipY;
            n.texGuid = texGuid; n.texture = texture;
            if (pts == null) n.pts = null;
            else {
                n.pts = new double[pts.length][];
                for (int i = 0; i < pts.length; i++) n.pts[i] = pts[i].clone();
            }
            n.dTransform = dT; n.dSorting = dS; n.dActive = dA;
            n.dPoints = dP; n.dTexture = dTex; n.dRendEnabled = dRe; n.dNew = dN; n.dLineBlock = dLB;
            n.name = name; n.physLayer = physLayer; n.hasPlatformEffector = hasEffector;
            n.effAnchor = effAnchor; n.effRotOffset = effRot; n.effSurfaceArc = effArc;
            n.colKind = colKind; n.offX = offX; n.offY = offY;
            n.parentTr = parentTr;
            n.childTr = (childTr != null) ? childTr.clone() : null;
        }
    }

    /**
     * Ảnh chụp 1 BƯỚC của cả scene: CẤU TRÚC (danh sách node + hàng chờ xoá) và field của từng node.
     *
     * <p>Ghép theo VỊ TRÍ trong {@code order} chứ không theo {@code scene.nodes()} nên thêm/xoá node
     * ở giữa vẫn khôi phục đúng. {@code order} giữ THAM CHIẾU tới chính object Node ⇒ node đã bị xoá
     * không bị mất, hoàn tác là quay lại đủ mọi field (kể cả anchor).
     */
    private static final class SceneSnap {
        final List<MapScene.Node> order;
        final List<MapScene.Node> removed;
        final List<NodeSnap> data;

        SceneSnap(List<MapScene.Node> order, List<MapScene.Node> removed, List<NodeSnap> data) {
            this.order = order;
            this.removed = removed;
            this.data = data;
        }
    }

    private SceneSnap snapshot() {
        List<MapScene.Node> order = new ArrayList<>(scene.nodes());
        List<NodeSnap> data = new ArrayList<>(order.size());
        for (MapScene.Node n : order) data.add(new NodeSnap(n));
        return new SceneSnap(order, new ArrayList<>(scene.deletedNodes()), data);
    }

    private void restore(SceneSnap s) {
        List<MapScene.Node> before = new ArrayList<>(scene.nodes());
        scene.restoreNodes(s.order);                       // trả lại đúng danh sách + thứ tự node
        List<MapScene.Node> del = scene.deletedNodes();
        del.clear();
        del.addAll(s.removed);                             // trả lại đúng hàng chờ xoá
        for (int i = 0; i < s.order.size(); i++) s.data.get(i).restore(s.order.get(i));
        scene.index();                                     // parentTr/childTr vừa đổi → dựng lại cây
        syncStructureWithFile(before);

        // bỏ mọi tham chiếu treo tới node không còn trong scene
        List<MapScene.Node> now = scene.nodes();
        if (selected != null && !now.contains(selected)) selected = null;
        if (lineNode != null && !now.contains(lineNode)) lineNode = null;
        hoverNode = null;
        hoverVertex = -1;
        lineVertex = -1;
        draftPts.clear();
    }

    /**
     * Sau khi hoàn tác, đồng bộ lại "bộ nhớ ↔ FILE" cho trường hợp người dùng đã LƯU giữa chừng:
     * <ul>
     *   <li>node biến mất khỏi scene nhưng block ĐÃ nằm trong file → đưa vào hàng chờ XOÁ;</li>
     *   <li>node quay lại scene nhưng block đã bị gỡ khỏi file → bật {@code dNew} để writer CHÈN lại.</li>
     * </ul>
     * Không có bước này thì undo sau khi lưu sẽ làm file và tool lệch nhau âm thầm.
     */
    private void syncStructureWithFile(List<MapScene.Node> before) {
        if (scene.doc() == null) return;
        List<MapScene.Node> now = scene.nodes();
        List<MapScene.Node> del = scene.deletedNodes();
        for (MapScene.Node n : before) {
            if (n.goAnchor == 0 || now.contains(n) || del.contains(n)) continue;
            if (scene.doc().block(n.goAnchor) != null) del.add(n);
        }
        for (MapScene.Node n : now) {
            if (n.colKind != MapScene.ColKind.EDGE || n.goAnchor == 0) continue;
            if (scene.doc().block(n.goAnchor) == null) n.dNew = true;
        }
    }

    /** Lưu trạng thái hiện tại — PHẢI gọi TRƯỚC mỗi thao tác sửa (kể cả thêm/xoá đường). */
    public void pushUndo() {
        if (scene == null) return;
        undo.push(snapshot());
        while (undo.size() > UNDO_MAX) undo.removeLast();
        redo.clear();
    }

    public void undo() {
        if (scene == null || undo.isEmpty()) return;
        redo.push(snapshot());
        restore(undo.pop());
        lineVertex = -1;
        fireChange();
        fireStructure();
        repaint();
    }

    public void redo() {
        if (scene == null || redo.isEmpty()) return;
        undo.push(snapshot());
        restore(redo.pop());
        lineVertex = -1;
        fireChange();
        fireStructure();
        repaint();
    }

    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    /** Số bước hoàn tác đang giữ — nút "Hoàn tác (N bước)" ở chân panel phải in số này. */
    public int undoDepth() { return undo.size(); }

    // ─────────────────────────────────────────────────────────────────────
    // Callback
    // ─────────────────────────────────────────────────────────────────────

    private void select(MapScene.Node n) {
        if (selected == n) return;
        selected = n;
        if (n == null || n.colKind != MapScene.ColKind.EDGE) lineVertex = -1;
        if (onSelect != null) onSelect.accept(n);
    }

    private void fireChange() { if (onChange != null) onChange.run(); }

    private void fireStructure() { if (onStructure != null) onStructure.run(); }
}
