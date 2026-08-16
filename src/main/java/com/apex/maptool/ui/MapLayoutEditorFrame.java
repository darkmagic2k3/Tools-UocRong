package com.apex.maptool.ui;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.db.InfoDao;
import com.apex.maptool.spine.SpineData;
import com.apex.maptool.unity.GuidIndex;
import com.apex.maptool.unity.MapScene;
import com.apex.maptool.unity.MapSceneLoader;
import com.apex.maptool.unity.MapSceneWriter;
import com.apex.maptool.unity.MaterialResolver;
import com.apex.maptool.unity.SortingLayers;
import com.apex.maptool.unity.TextureCache;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableModelEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MapLayoutEditorFrame — UI đầy đủ của chức năng "Bố cục Map": nạp NGUYÊN 1 map từ prefab client,
 * chỉnh layout / nền / thứ tự vẽ / đường kẻ player đứng, rồi ghi đè lại đúng dòng trong .prefab.
 *
 * <p>Bố cục (dark + amber, mọi màu lấy từ {@link Theme}):
 * <pre>
 *  ┌ TOOLBAR (2 hàng): chọn map · Nạp · Canh khung │ Hoàn tác/Làm lại │ chế độ │ … │ LƯU · JSON · Nạp lại
 *  │                   hiện: Sprite/Collider/Lưới/Chỉ đường đất/Xem trước Player/Phần đã tắt · cỡ lưới · hít lưới
 *  ├────────────┬──────────────────────────────┬────────────────────────┐
 *  │ CÂY phần tử │        MapLayoutCanvas        │      INSPECTOR         │
 *  │ (checkbox   │  (vẽ + kéo thả + sửa đỉnh)    │  Thông tin / Biến đổi  │
 *  │  m_IsActive)│                               │  Thứ tự vẽ / Đường kẻ  │
 *  └────────────┴──────────────────────────────┴────────────────────────┘
 *  └ STATUS: số node · renderer · đường kẻ · thay đổi chưa lưu · cảnh báo nested prefab
 * </pre>
 *
 * <p><b>Trước/sau player</b> là thứ nằm ở nhóm "Thứ tự vẽ": player dùng sorting layer
 * <b>LocalPlayer (index 18)</b>, nên layer &gt; 18 = vẽ ĐÈ lên nhân vật (tiền cảnh), &lt; 18 = nằm sau.
 * Combo "Vị trí so với player" tự chọn layer hợp lý (trước → Projectile 19, sau → Map 7) và luôn
 * hiện nhãn cảnh báo bằng tiếng Việt để người dùng biết đang sửa cái gì.
 *
 * <p>JFrame này thường KHÔNG được hiện: {@code MainFrame} mượn {@link #getContentPane()} nhét vào
 * CardLayout. Vì vậy mọi dialog đều lấy owner qua {@link #owner()} (window ancestor thật) và phím tắt
 * đăng ký ở {@code root} với {@code WHEN_IN_FOCUSED_WINDOW} để còn hoạt động sau khi bị reparent.
 */
public final class MapLayoutEditorFrame extends JFrame {

    // ── Hằng số ──
    /** Tên physics layer (m_Layer của GameObject) — index 3 Unity bỏ trống. */
    private static final String[] PHYS_LAYERS = {
            "Default", "TransparentFX", "Ignore Raycast", "(trống)", "Water", "UI", "Ground",
            "RemotePlayer", "KenMabu", "ItemPickUp", "Enemy", "NPC", "ItemOnGround", "Particle",
            "Wall", "Pet", "LocalPlayer", "ColorGround", "Minimap", "Oneway", "OnClickPlayer", "VeTinh"
    };
    /** sortingOrder thân player (UnitRenderer) — cùng layer thì order > 1 mới che được player. */
    private static final int PLAYER_BODY_ORDER = 1;
    private static final int BTN_H = 32;

    // ── Khung camera client (Scenes/Gameplay.unity + CameraFollow.GetEdgeScreen) ──
    /** {@code orthographic size} của camera gameplay = nửa CHIỀU CAO khung nhìn (unit). */
    private static final double CAM_HALF_Y = 8.1;
    /** Nửa chiều RỘNG khung nhìn ở 16:9 = orthoSize × aspect. */
    private static final double CAM_HALF_X_16_9 = CAM_HALF_Y * 16.0 / 9.0;      // 14.40
    /** Nửa chiều RỘNG ở 20:9 — máy "dài" nhất phổ biến, chật nhất ⇒ dùng làm ngưỡng chặn. */
    private static final double CAM_HALF_X_20_9 = CAM_HALF_Y * 20.0 / 9.0;      // 18.00
    /** Bề rộng tối thiểu của biên map (unit) để camera 20:9 không bị giật. */
    private static final double CAM_MIN_W = CAM_HALF_X_20_9 * 2;                // 36.00
    /** Chiều cao tối thiểu của biên map (unit). */
    private static final double CAM_MIN_H = CAM_HALF_Y * 2;                     // 16.20

    /**
     * Chú thích tiếng Việt cho từng field hiệu ứng (tên field GỐC trong prefab → nghĩa).
     * Nguồn: khảo sát code C# thật trong {@code Assets/Scripts/Features/Ingame/MapManager}.
     */
    private static final Map<String, String> FX_DESC = new LinkedHashMap<>();
    /** Field kiểu bool 0/1 → vẽ bằng checkbox. */
    private static final java.util.Set<String> FX_BOOL = java.util.Set.of(
            "m_Enabled", "loop", "initialFlipX", "initialFlipY", "_startMovingRight", "_artFacesRight",
            "useClipping", "immutableTriangles", "pmaVertexColors", "tintBlack", "singleSubmesh",
            "fixDrawOrder", "addNormals", "calculateTangents", "clearStateOnDisable",
            "disableRenderingOnOverride", "unscaledTime", "m_ApplyRootMotion", "m_HasTransformHierarchy");
    /**
     * Field KHÔNG cho sửa (sửa là vỡ prefab / vô nghĩa với tool). Vẫn hiện để người dùng thấy
     * giá trị thật, nhưng ở dạng chỉ đọc.
     */
    private static final java.util.Set<String> FX_LOCKED = java.util.Set.of(
            "fixPrefabOverrideViaMeshFilter", "updateWhenInvisible", "separatorSlotNames",
            "maskInteraction", "maskMaterials", "physicsPositionInheritanceFactor",
            "physicsRotationInheritanceFactor", "physicsMovementRelativeTo", "updateTiming",
            "m_CullingMode", "m_UpdateMode", "m_StabilizeFeet", "m_WarningMessage",
            "m_AllowConstantClipSamplingOptimization", "m_KeepAnimatorStateOnDisable",
            "m_WriteDefaultValuesOnDisable", "m_LinearVelocityBlending", "m_AnimatePhysics",
            "m_HasTransformHierarchy", "m_ApplyRootMotion", "m_Layers", "m_Avatar");

    static {
        // WaterWaveMover — dòng nước chảy
        FX_DESC.put("movementDirection", "hướng chảy: 0 = ngang (sang phải), 1 = dọc (xuống dưới)");
        FX_DESC.put("startPoint", "mốc BẮT ĐẦU — Transform tên StartPoint (kéo node đó để đổi)");
        FX_DESC.put("endPoint", "mốc KẾT THÚC — Transform tên EndPoint (kéo node đó để đổi)");
        FX_DESC.put("randomOffset", "biên ngẫu nhiên khi quay về mốc đầu (unit)");
        FX_DESC.put("moveSpeed", "tốc độ chảy (unit/giây)");
        // FishSwim — cá / cua / hải âu
        FX_DESC.put("_swimSpeed", "tốc độ bơi ngang (unit/giây)");
        FX_DESC.put("_swimRange", "nửa quãng bơi mỗi phía (tổng quãng = 2×)");
        FX_DESC.put("_startMovingRight", "bắt đầu bơi sang phải");
        FX_DESC.put("_artFacesRight", "hình gốc đang quay sang phải");
        FX_DESC.put("_visualToFlip", "node cần lật khi đổi hướng (0 = chính nó)");
        FX_DESC.put("_bobAmplitude", "biên nhấp nhô lên xuống (0 = tắt)");
        FX_DESC.put("_bobSpeed", "tốc độ nhấp nhô");
        // WaveWash — sóng vỗ bờ
        FX_DESC.put("direction", "hướng tràn của sóng (vector, sẽ được chuẩn hoá)");
        FX_DESC.put("distance", "quãng tràn tối đa (unit)");
        FX_DESC.put("period", "chu kỳ 1 nhịp sóng (giây)");
        FX_DESC.put("attackStart", "thời điểm bắt đầu dâng (0…1 của chu kỳ)");
        FX_DESC.put("riseDuration", "thời gian dâng tới đỉnh (0.01…1)");
        FX_DESC.put("releaseStart", "thời điểm bắt đầu rút (0…1)");
        // Water2DScript
        FX_DESC.put("speed", "tốc độ cuộn UV mặt nước (x, y)");
        // Spine SkeletonAnimation
        FX_DESC.put("_animationName", "tên animation sẽ phát (đọc từ file .json của skeleton)");
        FX_DESC.put("loop", "lặp vô hạn");
        FX_DESC.put("timeScale", "nhân tốc độ phát (1 = bình thường)");
        FX_DESC.put("initialSkinName", "skin khởi đầu (để trống = skin mặc định)");
        FX_DESC.put("initialFlipX", "lật ngang lúc khởi tạo");
        FX_DESC.put("initialFlipY", "lật dọc lúc khởi tạo");
        FX_DESC.put("zSpacing", "khoảng cách Z giữa các slot (thường để 0)");
        FX_DESC.put("skeletonDataAsset", "file *_SkeletonData.asset của skeleton");
        // Animator
        FX_DESC.put("m_Controller", "file .controller đang phát");
        FX_DESC.put("m_Enabled", "bật/tắt component hiệu ứng này");
        FX_DESC.put("m_Speed", "nhân tốc độ của Animator");
    }

    private static final Pattern GUID_PAT = Pattern.compile("guid:\\s*([0-9a-fA-F]{32})");
    private static final Pattern PIVOT_PAT =
            Pattern.compile("spritePivot:\\s*\\{x:\\s*(-?[\\d.eE+]+),\\s*y:\\s*(-?[\\d.eE+]+)");
    private static final Pattern MAP_ID_PAT = Pattern.compile("^\\s*(\\d+)");
    /** Vector2 trong prefab: {@code {x: 0, y: -0.2}}. */
    private static final Pattern FX_XY_PAT =
            Pattern.compile("x:\\s*(-?[\\d.eE+-]+),\\s*y:\\s*(-?[\\d.eE+-]+)");
    private static final Pattern FX_FILEID_PAT = Pattern.compile("fileID:\\s*(-?\\d+)");

    // ── Phụ thuộc ──
    private final ToolConfig cfg;
    private GuidIndex guidIndex;               // có thể null → tự dựng ở lần nạp đầu
    private MaterialResolver matResolver;
    private final TextureCache texCache;
    private final SortingLayers layers;
    private final int ppu;

    // ── Dữ liệu ──
    private final List<InfoDao.InfoItem> allMaps = new ArrayList<>();
    private final List<String> mapItems = new ArrayList<>();      // "1 - Rừng nguyên sinh"
    private MapScene scene;
    private int curMapId = -1;

    // ── UI: khung ──
    private final JPanel root = new JPanel(new BorderLayout());
    private final MapLayoutCanvas canvas;
    /** Cột theo bản thiết kế (handoff §4.3). */
    private static final int COL_TREE = 308, COL_INSPECTOR = 352;
    /** Dòng mẹo chuột ở thanh dưới — 1 dòng, thay 3 dòng chữ 11px chen nhau của bản cũ. */
    private static final String MOUSE_HINT =
            "Chuột phải hoặc Space+trái: pan · Lăn: zoom · Shift: khoá trục · Alt: tạm tắt hít khít";

    // ── UI: toolbar (1 hàng + 3 menu bật xuống) ──
    private final JComboBox<String> cboMap = new JComboBox<>();
    private JSpinner spGrid;
    private JButton btnUndo, btnSave;
    private JMenuItem miRedo;
    private Theme.Segmented segMode;
    private Theme.MenuButton mbShow, mbGrid, mbFx, mbMore;
    /** Đang ở chế độ "Biên map" (đoạn thứ 4 của bộ chọn Chế độ). */
    private boolean modeBounds;

    // ── UI: cây hierarchy ──
    private final JTextField txtFind = new JTextField();
    private final JTree tree = new JTree();
    private DefaultTreeModel treeModel;
    private final Map<MapScene.Node, DefaultMutableTreeNode> treeIndex = new IdentityHashMap<>();
    private final JLabel lbTreeCount = Theme.label("—", 12, Font.PLAIN, Theme.TEXT_DIM);

    // ── UI: inspector ──
    private final JLabel lbName = valLabel("—");
    private final JLabel lbType = valLabel("—");
    private final JLabel lbPhys = valLabel("—");
    private final JLabel lbTag = valLabel("—");
    private final JLabel lbTex = valLabel("—");
    private final JLabel lbThumb = new JLabel();
    private JCheckBox chkActive, chkRendOn;
    private JSpinner spX, spY, spSX, spSY, spRot;
    private final JLabel lbServer = new JLabel(" ");
    private JCheckBox chkFlipX, chkFlipY;
    private final JComboBox<SortingLayers.Layer> cboLayer = new JComboBox<>();
    private JSpinner spOrder;
    private JButton btnOrderUp, btnOrderDown, btnOrderTop, btnOrderBottom;
    private final JLabel lbSortWarn = flowLabel();
    private JTable tblPts;
    private DefaultTableModel modelPts;
    /** 2 nút CẤU TRÚC: tạo mới / xoá hẳn 1 đường kẻ (khác hẳn nhóm nút sửa đỉnh bên trên). */
    private JButton btnNewLine, btnDelLine;
    /** Kiểu chặn của đường kẻ đang chọn (đặc / oneway lên / chặn lên / không chặn). */
    private final JComboBox<MapScene.LineBlock> cboBlock = new JComboBox<>();
    private final JLabel lbLineKind = flowLabel();
    private JPanel secTransform, secSort, secLine, secBg, secInfo;
    private JButton btnTex;
    private final JLabel lbBgInfo = flowLabel();

    // ── UI: nội dung 3 menu bật xuống của thanh công cụ ──
    /** Menu "Hiện" — 6 lớp vẽ (đếm trên mặt nút) + "Hiện cả phần đã tắt". */
    private JCheckBoxMenuItem miSprite, miCollider, miGrid, miGround, miPreview, miBounds, miInactive;
    /** Menu "Lưới & hít" + "Hiệu ứng". */
    private JCheckBoxMenuItem miSnapGrid, miSnapEdge, miPlayFx, miShowFx, miFxBadge;
    private JSpinner spSnapPx, spFxSpeed;

    // ── UI: inspector — khối "Biên map (vùng camera)" (Y2) ──
    private JPanel secEdge;
    private JSpinner spEdgeL, spEdgeR, spEdgeT, spEdgeB;
    private final JLabel lbCamSize = flowLabel();
    private final JLabel lbCamWarn = flowLabel();
    private final JLabel lbEdgeMiss = flowLabel();

    // ── UI: inspector — khối "Hiệu ứng" (Y3) ──
    private JPanel secFx;
    /** Phần thân của khối hiệu ứng — dựng LẠI mỗi lần chọn node vì mỗi loại có bộ field khác nhau. */
    private final JPanel fxBody = new JPanel();
    private final JLabel lbFxHead = new JLabel(" ");
    private JButton btnFxPreview, btnFxDefault;

    /** Bảng "Hiệu ứng trong map" — giữ lại để bấm nút lần 2 chỉ đưa cửa sổ cũ lên trước. */
    private JDialog dlgFx;
    private DefaultTableModel modelFx;
    private JTable tblFx;
    private final List<MapScene.Node> fxRows = new ArrayList<>();
    private JComboBox<String> cboFxFilter;

    /** Tham số hiệu ứng LÚC NẠP (theo từng component) — dùng cho nút "Về mặc định". */
    private final Map<MapScene.Fx, Map<String, String>> fxOrig = new IdentityHashMap<>();
    /** Khung camera LÚC NẠP {minX,minY,maxX,maxY} — để hộp thoại Lưu in được "cũ → mới". */
    private double[] camOrig;
    /** Tên animation đọc được từ file .json của 1 thư mục Spine (đọc 1 lần rồi nhớ). */
    private final Map<Path, List<String>> spineAnims = new HashMap<>();

    // ── UI: thanh dưới (1 hàng chip) ──
    /** Dòng GIỮA thanh dưới: mẹo chuột, và cũng là nơi in thông báo tức thời của mọi thao tác. */
    private final JLabel lbStatus = new JLabel(MOUSE_HINT);
    /** Cùng một nhãn với {@link #lbStatus} — giữ tên cũ cho các chỗ đang gọi. */
    private final JLabel lbHint = lbStatus;
    private final Theme.Chip chNodes = Theme.chip("— phần tử");
    private final Theme.Chip chRend = Theme.chip("— renderer");
    private final Theme.Chip chLines = Theme.chip("— đường kẻ");
    private final Theme.Chip chBox = Theme.chip("— vùng box");
    private final Theme.Chip chFx = Theme.chip("— hiệu ứng");
    private final Theme.Chip chDirty = new Theme.Chip("chưa sửa gì", null, 26, 12, false);
    private final Theme.Chip lbWarnNested = new Theme.Chip("", Theme.ACCENT, 26, 12, false);

    // ── UI: panel phải (MỚI) ──
    private JTabbedPane inspTabs;
    private Theme.Segmented segFront;
    private final JLabel lbGroup = Theme.monoLabel("—", 12, Theme.TEXT_DIM);
    private final JLabel lbZoneCount = Theme.monoLabel("0 đường · 0 box", 12, Theme.TEXT_DIM);
    private JLabel lbFxNone;
    private JPanel layerRows;
    private final Map<Integer, JLabel> layerCountLabels = new LinkedHashMap<>();

    /** Vỏ app (top bar) — null khi chạy JFrame riêng. */
    private MainFrame.Shell shell;

    /**
     * Node mà inspector ĐANG hiển thị. Mọi ô/nút bên phải ghi vào node NÀY (không hỏi lại canvas)
     * — nếu đọc {@code canvas.selected()} thì lúc canvas và inspector lệch nhau (ví dụ đổi node
     * bằng cây trong khi chuột vẫn giữ node cũ) sẽ sửa nhầm phần tử.
     */
    private MapScene.Node curNode;
    /** Chặn vòng lặp: đang đổ dữ liệu node → UI thì listener KHÔNG được ghi ngược vào model. */
    private boolean syncing;
    /** Gộp undo cho spinner/bảng: gõ liên tục trong 900 ms chỉ tính 1 bước. */
    private Object lastUndoSrc;
    private long lastUndoAt;

    // ─────────────────────────────────────────────────────────────────────
    // Khởi tạo
    // ─────────────────────────────────────────────────────────────────────

    public MapLayoutEditorFrame(ToolConfig cfg, GuidIndex guidIndex, MaterialResolver matResolver,
                                TextureCache texCache, List<InfoDao.InfoItem> maps) {
        super("UR Tools - Bố cục Map");
        this.cfg = (cfg != null) ? cfg : new ToolConfig();
        this.guidIndex = guidIndex;
        this.matResolver = matResolver;
        this.texCache = (texCache != null) ? texCache : new TextureCache();
        this.ppu = this.cfg.pixelsPerUnit();
        this.layers = SortingLayers.load(Paths.get(this.cfg.clientRepo()));

        this.canvas = new MapLayoutCanvas(this.texCache, ppu);
        canvas.setPlayerLayerIndex(layers.playerIndex());
        canvas.setPreferredSize(new Dimension(900, 620));

        fillMapList(maps);
        buildUi();

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(root);
        setSize(1900, 1010);   // chỉ dùng khi chạy JFrame riêng (CLI debug); trong app là card của MainFrame
    }

    /** Danh sách map: ưu tiên DB; DB hỏng/rỗng → quét thư mục Resource/Map* của client. */
    private void fillMapList(List<InfoDao.InfoItem> maps) {
        if (maps != null) allMaps.addAll(maps);
        if (allMaps.isEmpty()) allMaps.addAll(scanMapsOnDisk());
        allMaps.sort((a, b) -> Integer.compare(a.id(), b.id()));
        for (InfoDao.InfoItem m : allMaps) {
            String nm = (m.name() != null && !m.name().isBlank()) ? m.name() : ("Map " + m.id());
            mapItems.add(m.id() + " - " + nm);
        }
        if (mapItems.isEmpty()) mapItems.add("1 - Map 1");
    }

    /** Quét {@code Assets/AssetBundles/Resource/Map{id}/Map_{id}.prefab} khi không có DB. */
    private List<InfoDao.InfoItem> scanMapsOnDisk() {
        List<InfoDao.InfoItem> out = new ArrayList<>();
        Path resRoot = cfg.resourceRoot();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(resRoot, "Map*")) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                String nm = p.getFileName().toString();
                if (!nm.matches("Map\\d+")) continue;
                int id = Integer.parseInt(nm.substring(3));
                if (Files.exists(p.resolve("Map_" + id + ".prefab"))) out.add(new InfoDao.InfoItem(id, "Map " + id, 0));
            }
        } catch (Exception e) {
            System.err.println("[MapLayout] quét thư mục map lỗi: " + e.getMessage());
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Dựng UI
    // ─────────────────────────────────────────────────────────────────────

    private void buildUi() {
        root.setBackground(Theme.BG_MAIN);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(Theme.BG_MAIN);
        content.setBorder(new EmptyBorder(16, 20, 18, 20));
        content.add(buildToolbar(), BorderLayout.NORTH);

        JPanel canvasCard = Theme.card(Theme.BG_PANEL, Theme.BORDER_SOFT, 10);
        canvasCard.setLayout(new BorderLayout());
        canvasCard.setBorder(new EmptyBorder(1, 1, 1, 1));
        canvasCard.add(canvas, BorderLayout.CENTER);

        JPanel cols = new JPanel(new BorderLayout(12, 0));
        cols.setOpaque(false);
        cols.add(buildTreePanel(), BorderLayout.WEST);
        cols.add(canvasCard, BorderLayout.CENTER);
        cols.add(buildInspector(), BorderLayout.EAST);
        content.add(cols, BorderLayout.CENTER);

        content.add(buildStatusBar(), BorderLayout.SOUTH);
        root.add(content, BorderLayout.CENTER);

        // Canh khung lần đầu (lúc dựng chưa biết width — kể cả khi bị MainFrame mượn content).
        root.addComponentListener(new ComponentAdapter() {
            private boolean done;
            @Override public void componentResized(ComponentEvent e) {
                if (done || root.getWidth() < 700) return;
                done = true;
                if (scene != null) SwingUtilities.invokeLater(canvas::fitView);
            }
        });

        installShortcuts();
        canvas.setOnSelect(this::onCanvasSelect);
        canvas.setOnChange(this::onCanvasChange);
        canvas.setOnStructure(this::afterStructuralChange);   // undo/redo có thêm/bớt node
        updateInspector(null);
        updateStatus();
    }

    // ── Toolbar ──

    /**
     * Thanh công cụ MỘT hàng cao 60 (handoff §7.1):
     * <pre>map ▾ · Nạp │ [Chọn|Di chuyển|Đường kẻ|Biên map] │ Hiện 4/6 ▾ · Lưới &amp; hít ▾ · Hiệu ứng ▾
     *                                          ⟶ giãn ⟵                Xuất JSON · Lưu vào prefab · ⋯</pre>
     * Bản cũ có <b>3 hàng ~34 control</b> nằm phẳng. Ba {@link JPopupMenu} gom hết phần ít dùng,
     * còn mặt nút vẫn in trạng thái tóm tắt ({@code 4/6}) nên không phải mở ra mới biết.
     */
    private JComponent buildToolbar() {
        JPanel bar = Theme.card();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(new EmptyBorder(0, 14, 0, 14));
        bar.setPreferredSize(new Dimension(10, 60));
        bar.setMinimumSize(new Dimension(10, 60));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));

        cboMap.setModel(new DefaultComboBoxModel<>(mapItems.toArray(new String[0])));
        cboMap.setEditable(true);                       // gõ để lọc / gõ thẳng id
        cboMap.setFont(Theme.font(13, Font.BOLD));
        cboMap.setToolTipText("Gõ id hoặc tên để lọc, Enter để nạp");
        setupMapFilter();
        bar.add(Theme.lockSize(cboMap, 212, 36));
        bar.add(Box.createHorizontalStrut(10));
        JButton bLoad = Theme.primary("Nạp", 36, e -> loadSelectedMap());
        bar.add(Theme.lockSize(bLoad, bLoad.getFontMetrics(bLoad.getFont()).stringWidth("Nạp") + 40, 36));
        bar.add(Box.createHorizontalStrut(10));
        bar.add(Theme.vsep(24));
        bar.add(Box.createHorizontalStrut(10));

        segMode = new Theme.Segmented(36, 30, true, false, "Chọn", "Di chuyển", "Đường kẻ", "Biên map");
        segMode.onChange(this::applyMode);
        segMode.setToolTipText("Chế độ chuột trên khung xem");
        bar.add(segMode);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(Theme.vsep(24));
        bar.add(Box.createHorizontalStrut(10));

        mbShow = new Theme.MenuButton("Hiện", 36);
        mbShow.setMenu(buildShowMenu());
        mbShow.setToolTipText("Bật/tắt từng lớp vẽ trên khung xem");
        bar.add(mbShow);
        bar.add(Box.createHorizontalStrut(8));
        mbGrid = new Theme.MenuButton("Lưới & hít", 36);
        mbGrid.setMenu(buildGridMenu());
        mbGrid.setToolTipText("Cỡ lưới · hít lưới · hít khít + ngưỡng");
        bar.add(mbGrid);
        bar.add(Box.createHorizontalStrut(8));
        mbFx = new Theme.MenuButton("Hiệu ứng", 36);
        mbFx.setMenu(buildFxMenu());
        mbFx.setToolTipText("Chạy / hiện / nhãn hiệu ứng · bảng hiệu ứng · thêm Spine");
        bar.add(mbFx);

        bar.add(Box.createHorizontalGlue());

        final JButton btnJson = Theme.ghost("Xuất JSON", 36, e -> doExportJson());
        btnJson.setToolTipText("Xuất bố cục hiện tại ra JSON trong backup/prefab (để đối chiếu/khôi phục tay)");
        bar.add(Theme.lockH(btnJson, 36));
        bar.add(Box.createHorizontalStrut(8));
        btnSave = Theme.primary("Lưu vào prefab", 36, e -> doSave());
        btnSave.setToolTipText("Ghi đè file .prefab trong client (tự backup trước) — Ctrl+S");
        bar.add(Theme.lockSize(btnSave,
                btnSave.getFontMetrics(btnSave.getFont()).stringWidth("Lưu vào prefab") + 40, 36));
        bar.add(Box.createHorizontalStrut(8));

        // Bản thiết kế đo ở 1920. Cửa sổ hẹp hơn (checklist: 1600×900) thì RÚT NHÃN để hàng vẫn
        // đúng 1 hàng và không control nào bị cắt — thay vì để BoxLayout tràn ra ngoài mép phải.
        bar.addComponentListener(new ComponentAdapter() {
            private Boolean last;
            @Override public void componentResized(ComponentEvent e) {
                boolean tight = bar.getWidth() < 1380;
                if (last != null && last == tight) return;
                last = tight;
                btnJson.setText(tight ? "JSON" : "Xuất JSON");
                Theme.lockH(btnJson, 36);
                btnSave.setText(tight ? "Lưu" : "Lưu vào prefab");
                Theme.lockSize(btnSave,
                        btnSave.getFontMetrics(btnSave.getFont()).stringWidth(btnSave.getText()) + 40, 36);
                mbGrid.setLabel(tight ? "Lưới" : "Lưới & hít");
                Theme.lockSize(cboMap, tight ? 176 : 212, 36);
                bar.revalidate();
                bar.repaint();
            }
        });
        JPopupMenu more = buildMoreMenu();
        JButton bMore = Theme.iconButton(Theme.icon(Theme.IC_MORE, 16, Theme.TEXT_MUTED), 36, 36,
                "Canh khung · khớp biên · làm lại · hoàn tác tất cả", null);
        bMore.addActionListener(e -> more.show(bMore,
                bMore.getWidth() - more.getPreferredSize().width, bMore.getHeight() + 6));
        bar.add(bMore);

        // đẩy mặc định xuống canvas (menu item không tự bắn sự kiện lúc dựng)
        canvas.setSnapEnabled(true);
        canvas.setSnapThresholdPx(8);
        canvas.setPlayEffects(true);
        canvas.setEffectSpeed(1.0);
        canvas.setShowEffects(true);
        canvas.setShowEffectBadges(false);
        canvas.setShowBounds(false);
        canvas.setEditBounds(false);
        refreshShowBadge();
        return bar;
    }

    /** Đổi chế độ chuột theo đoạn được chọn (đoạn 4 = kéo 4 thanh biên map). */
    private void applyMode(int i) {
        modeBounds = (i == 3);
        canvas.setEditBounds(modeBounds);
        if (modeBounds) {
            // 4 thanh biên là thứ MapManager.Init() đọc để CameraFollow.SetBound ⇒ kéo chúng = đổi
            // VÙNG CAMERA của client. Chuột vẫn ở chế độ chọn, canvas chỉ ưu tiên bắt 4 thanh đó.
            canvas.setEditMode(MapLayoutCanvas.EditMode.SELECT);
            canvas.setShowBounds(true);
            if (miBounds != null) miBounds.setSelected(true);
            refreshShowBadge();
        } else {
            canvas.setEditMode(switch (i) {
                case 1 -> MapLayoutCanvas.EditMode.MOVE;
                case 2 -> MapLayoutCanvas.EditMode.LINE;
                default -> MapLayoutCanvas.EditMode.SELECT;
            });
        }
        updateEdgeVisibility();
        focusCanvas();
    }

    // ── 4 menu bật xuống của thanh công cụ ──

    private JPopupMenu buildShowMenu() {
        JPopupMenu m = popup();
        m.add(menuHeader("Lớp vẽ"));
        miSprite = checkItem("Sprite", true, v -> { canvas.setShowSprites(v); refreshShowBadge(); });
        miCollider = checkItem("Collider", true, v -> { canvas.setShowColliders(v); refreshShowBadge(); });
        miGrid = checkItem("Lưới", false, v -> { canvas.setShowGrid(v); refreshShowBadge(); });
        miGround = checkItem("Chỉ đường đất", false, v -> { canvas.setShowOnlyGround(v); refreshShowBadge(); });
        miPreview = checkItem("Xem trước Player", false, v -> {
            canvas.setPreviewPlayer(v);
            canvas.setMarkFront(v);
            refreshShowBadge();
        });
        miBounds = checkItem("Biên map", false, v -> {
            canvas.setShowBounds(v);
            // tắt hiển thị trong lúc đang ở chế độ kéo biên = thanh vô hình mà vẫn kéo được → thoát luôn
            if (!v && modeBounds) {
                modeBounds = false;
                canvas.setEditBounds(false);
                canvas.setEditMode(MapLayoutCanvas.EditMode.SELECT);
                segMode.select(0);
                updateEdgeVisibility();
            }
            refreshShowBadge();
        });
        miPreview.setToolTipText("Vẽ hình người mẫu đứng trên đường đất gần con trỏ, đúng thứ tự vẽ của player");
        miBounds.setToolTipText("Vẽ 4 thanh biên + khung camera mà client sẽ dùng"
                + " (đoạn “Biên map” ở bộ Chế độ mới là chế độ KÉO)");
        for (JCheckBoxMenuItem it : new JCheckBoxMenuItem[]{miSprite, miCollider, miGrid, miGround, miPreview, miBounds})
            m.add(it);
        m.addSeparator();
        miInactive = checkItem("Hiện cả phần đã tắt", true, v -> canvas.setShowInactive(v));
        m.add(miInactive);
        return m;
    }

    private JPopupMenu buildGridMenu() {
        JPopupMenu m = popup();
        spGrid = dspin(1.0, 0.05, 0.01, 100);
        spGrid.addChangeListener(e -> canvas.setGridSize(dval(spGrid)));
        m.add(menuRow("Cỡ lưới", Theme.lockSize(Theme.monoSpin(spGrid, 96, 30), 96, 30)));
        miSnapGrid = checkItem("Hít lưới", false, v -> canvas.setSnap(v));
        m.add(miSnapGrid);
        m.addSeparator();
        miSnapEdge = checkItem("Hít khít (dính mép phần tử khác)", true, v -> canvas.setSnapEnabled(v));
        miSnapEdge.setToolTipText("Kéo phần tử là tự dính mép/tâm phần tử khác — ghép tile không hở khe.");
        m.add(miSnapEdge);
        spSnapPx = Theme.spin(8, 2, 40);
        spSnapPx.setToolTipText("Cách bao nhiêu pixel MÀN HÌNH thì bắt đầu hút (zoom càng gần càng chỉnh được tinh)");
        spSnapPx.addChangeListener(e -> canvas.setSnapThresholdPx(Theme.spinInt(spSnapPx)));
        m.add(menuRow("ngưỡng (px)", Theme.lockSize(Theme.monoSpin(spSnapPx, 96, 30), 96, 30)));
        m.add(menuNote("Giữ Alt khi kéo = tạm tắt hít khít"));
        return m;
    }

    private JPopupMenu buildFxMenu() {
        JPopupMenu m = popup();
        miPlayFx = checkItem("Chạy hiệu ứng", true, v -> canvas.setPlayEffects(v));
        miPlayFx.setToolTipText("Cây lắc lư / dòng nước chảy / cá bơi chuyển động thật."
                + " Tắt là dừng đồng hồ (không tốn CPU).");
        m.add(miPlayFx);
        spFxSpeed = dspin(1.0, 0.1, 0.1, 3.0);
        spFxSpeed.setToolTipText("Nhân tốc độ xem trước (không ghi vào prefab)");
        spFxSpeed.addChangeListener(e -> canvas.setEffectSpeed(dval(spFxSpeed)));
        m.add(menuRow("tốc độ", Theme.lockSize(Theme.monoSpin(spFxSpeed, 96, 30), 96, 30)));
        m.add(plainItem("Reset — chạy lại từ giây 0", () -> { canvas.resetEffectTime(); focusCanvas(); }));
        m.addSeparator();
        miShowFx = checkItem("Hiện hiệu ứng", true, v -> canvas.setShowEffects(v));
        miShowFx.setToolTipText("Tắt = ẩn hẳn Spine + hiệu ứng nước cho dễ nhìn phần ảnh map");
        m.add(miShowFx);
        miFxBadge = checkItem("Nhãn hiệu ứng", false, v -> canvas.setShowEffectBadges(v));
        miFxBadge.setToolTipText("Hiện tên loại hiệu ứng ngay trên canvas để dễ tìm");
        m.add(miFxBadge);
        m.addSeparator();
        m.add(plainItem("Hiệu ứng trong map…", this::showFxList));
        m.add(plainItem("Thêm Spine…", this::addSpineNode));
        return m;
    }

    private JPopupMenu buildMoreMenu() {
        JPopupMenu m = popup();
        m.add(plainItem("Canh khung (F)", () -> { canvas.fitView(); focusCanvas(); }));
        m.add(plainItem("Khớp biên vào ảnh map", this::fitEdgesToMap));
        m.addSeparator();
        miRedo = plainItem("Làm lại (Ctrl+Y)", () -> canvas.redo());
        m.add(miRedo);
        m.addSeparator();
        JMenuItem rv = plainItem("Hoàn tác tất cả — bỏ mọi thay đổi chưa lưu", this::doRevertAll);
        rv.setForeground(Theme.RED);
        m.add(rv);
        return m;
    }

    /** Mặt nút "Hiện" luôn in n/6 lớp vẽ đang bật; có lớp bị tắt → nút sáng ACCENT_SEL. */
    private void refreshShowBadge() {
        if (mbShow == null) return;
        int on = 0;
        for (JCheckBoxMenuItem it : new JCheckBoxMenuItem[]{miSprite, miCollider, miGrid, miGround, miPreview, miBounds})
            if (it != null && it.isSelected()) on++;
        mbShow.setBadge(on + "/6");
        mbShow.setActiveState(on < 6);
    }

    // ── tiện dựng menu ──

    private static JPopupMenu popup() {
        JPopupMenu m = new JPopupMenu();
        m.setBackground(Theme.BG_SURFACE2);
        m.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER), new EmptyBorder(8, 8, 8, 8)));
        return m;
    }

    private static JComponent menuHeader(String s) {
        JLabel l = Theme.sectionHeader(s, Theme.TEXT_DIM);
        l.setFont(Theme.tracked(11, Font.BOLD, 0.14));
        l.setBorder(new EmptyBorder(6, 8, 6, 8));
        return l;
    }

    private static JComponent menuNote(String s) {
        JLabel l = Theme.label(s, 12, Font.PLAIN, Theme.TEXT_DIM);
        l.setBorder(new EmptyBorder(8, 8, 4, 8));
        return l;
    }

    /** Một dòng "nhãn + ô số" trong menu (không đóng menu khi bấm). */
    private static JComponent menuRow(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(4, 8, 4, 8));
        p.add(Theme.label(label, 13, Font.PLAIN, Theme.TEXT_2), BorderLayout.WEST);
        p.add(field, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return p;
    }

    /** Mục checkbox GIỮ MENU MỞ (bật/tắt 3–4 lớp liền một lượt). */
    private JCheckBoxMenuItem checkItem(String text, boolean sel, java.util.function.Consumer<Boolean> setter) {
        JCheckBoxMenuItem it = new JCheckBoxMenuItem(text, sel) {
            @Override public void processMouseEvent(MouseEvent e, MenuElement[] path, MenuSelectionManager mgr) {
                if (e.getID() == MouseEvent.MOUSE_RELEASED && contains(e.getPoint())) {
                    doClick(0);
                    setArmed(true);
                } else {
                    super.processMouseEvent(e, path, mgr);
                }
            }
        };
        it.setFont(Theme.font(13, Font.PLAIN));
        it.setBorder(new EmptyBorder(7, 8, 7, 8));
        it.setIconTextGap(9);
        it.addActionListener(e -> { setter.accept(it.isSelected()); focusCanvas(); });
        return it;
    }

    private JMenuItem plainItem(String text, Runnable r) {
        JMenuItem it = new JMenuItem(text);
        it.setFont(Theme.font(13, Font.PLAIN));
        it.setBorder(new EmptyBorder(7, 8, 7, 8));
        it.addActionListener(e -> r.run());
        return it;
    }

    /** Gõ trong combo map → lọc danh sách; Enter → nạp luôn. */
    private void setupMapFilter() {
        Component ed = cboMap.getEditor().getEditorComponent();
        if (!(ed instanceof JTextField tf)) return;
        tf.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                int k = e.getKeyCode();
                if (k == KeyEvent.VK_ENTER) { loadSelectedMap(); return; }
                if (k == KeyEvent.VK_UP || k == KeyEvent.VK_DOWN || k == KeyEvent.VK_LEFT
                        || k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_TAB) return;
                String q = tf.getText().trim().toLowerCase();
                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                for (String s : mapItems) if (q.isEmpty() || s.toLowerCase().contains(q)) model.addElement(s);
                syncing = true;                       // setModel bắn actionPerformed → chặn
                cboMap.setModel(model);
                cboMap.getEditor().setItem(tf.getText());
                syncing = false;
                if (model.getSize() > 0 && !q.isEmpty() && !cboMap.isPopupVisible()) cboMap.showPopup();
                tf.requestFocusInWindow();
            }
        });
    }

    // ── Cây hierarchy ──

    private JComponent buildTreePanel() {
        JPanel p = Theme.card();
        p.setLayout(new BorderLayout());
        p.setPreferredSize(new Dimension(COL_TREE, 10));
        p.setMinimumSize(new Dimension(COL_TREE, 10));

        JPanel top = Theme.colBox();
        top.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                new EmptyBorder(12, 12, 10, 12)));
        JPanel head = Theme.row();
        head.add(Theme.sectionHeader("Phần tử map", Theme.TEXT_MUTED));
        head.add(Box.createHorizontalGlue());
        head.add(lbTreeCount);
        top.add(Theme.capH(head));
        top.add(Box.createVerticalStrut(9));
        txtFind.putClientProperty("JTextField.placeholderText", "Tìm phần tử, layer, order…");
        txtFind.putClientProperty("JTextField.leadingIcon", Theme.icon(Theme.IC_SEARCH, 14, Theme.TEXT_DIM));
        txtFind.setAlignmentX(Component.LEFT_ALIGNMENT);
        txtFind.setPreferredSize(new Dimension(120, 34));
        txtFind.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        txtFind.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) { rebuildTree(); }
        });
        top.add(txtFind);
        p.add(top, BorderLayout.NORTH);

        treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("(chưa nạp map)"));
        tree.setModel(treeModel);
        tree.setRowHeight(32);
        tree.setBackground(Theme.BG_SURFACE);
        tree.setOpaque(false);
        tree.setShowsRootHandles(true);
        // Gốc giả ("Map_1") trùng luôn với node gốc THẬT của scene → giấu đi cho khỏi lặp 2 dòng.
        tree.setRootVisible(false);
        tree.setBorder(new EmptyBorder(2, 0, 6, 0));
        // Thụt lề CỐ ĐỊNH 16px/cấp → renderer tính đúng bề rộng còn lại để badge thẳng một cột phải.
        if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI bu) {
            bu.setLeftChildIndent(8);
            bu.setRightChildIndent(8);
        }
        tree.setCellRenderer(new NodeRenderer());
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setToolTipText("Click ô vuông = bật/tắt (m_IsActive) · click dòng = chọn · double-click = đưa ra giữa canvas");
        tree.addTreeSelectionListener(e -> {
            if (syncing) return;
            MapScene.Node n = nodeOfPath(tree.getSelectionPath());
            canvas.setSelected(n);
            updateInspector(n);
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { treeClick(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) return;
                MapScene.Node n = nodeOfPath(tree.getPathForLocation(e.getX(), e.getY()));
                if (n != null) canvas.panTo(n);
            }
        });
        JScrollPane sp = new JScrollPane(tree,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(new EmptyBorder(6, 6, 6, 2));
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /** Bấm vào ô vuông đầu dòng = bật/tắt GameObject (m_IsActive). */
    private void treeClick(MouseEvent e) {
        int row = tree.getRowForLocation(e.getX(), e.getY());
        if (row < 0) return;
        Rectangle r = tree.getRowBounds(row);
        TreePath tp = tree.getPathForRow(row);
        MapScene.Node n = nodeOfPath(tp);
        if (n == null || r == null) return;
        int dx = e.getX() - r.x;
        if (dx < 4 || dx > 27) return;                   // ngoài ô tick 15px (x = 8…23 trong dòng)
        canvas.pushUndo();
        n.active = !n.active;
        n.dActive = true;
        treeModel.nodeChanged((DefaultMutableTreeNode) tp.getLastPathComponent());
        if (curNode == n) {
            syncing = true;
            chkActive.setSelected(n.active);
            syncing = false;
        }
        canvas.repaint();
        updateStatus();
    }

    private static MapScene.Node nodeOfPath(TreePath tp) {
        if (tp == null) return null;
        Object last = tp.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode d)) return null;
        return (d.getUserObject() instanceof MapScene.Node n) ? n : null;
    }

    /** Dựng lại cây theo scene + ô lọc. */
    private void rebuildTree() {
        String q = txtFind.getText().trim().toLowerCase();
        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode(
                scene == null ? "(chưa nạp map)" : ("Map_" + scene.mapId()));
        treeIndex.clear();
        if (scene == null) {
            lbTreeCount.setText("chưa nạp map");
        } else {
            int groups = 0;
            for (MapScene.Node n : scene.nodes()) if (!scene.children(n).isEmpty()) groups++;
            lbTreeCount.setText(scene.nodes().size() + " phần tử · " + groups + " nhóm");
        }
        if (scene != null) for (MapScene.Node n : scene.roots()) addTreeNode(rootNode, n, q);
        syncing = true;
        treeModel.setRoot(rootNode);
        syncing = false;

        TreePath rp = new TreePath(rootNode.getPath());
        tree.expandPath(rp);
        if (!q.isEmpty()) {
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);   // lọc → bung hết cho dễ thấy
        } else {
            for (int i = 0; i < rootNode.getChildCount(); i++)
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) rootNode.getChildAt(i)).getPath()));
        }
    }

    /** Thêm node + con (đệ quy); trả false nếu cả nhánh không khớp bộ lọc. */
    private boolean addTreeNode(DefaultMutableTreeNode parent, MapScene.Node n, String q) {
        DefaultMutableTreeNode tn = new DefaultMutableTreeNode(n);
        boolean self = q.isEmpty() || (n.name != null && n.name.toLowerCase().contains(q));
        boolean kid = false;
        for (MapScene.Node c : scene.children(n)) kid |= addTreeNode(tn, c, q);
        if (!self && !kid) return false;
        parent.add(tn);
        treeIndex.put(n, tn);
        return true;
    }

    /**
     * Renderer 1 dòng cây (handoff §7.2): {@code [☑ 15px] Tên … [badge L7 · #6 căn PHẢI]}.
     * Bản cũ nối chuỗi {@code "SPR L7#5"} dính liền sau tên nên mắt phải đọc từng dòng; badge căn
     * phải cho quét theo cột. Nhóm hiện SỐ PHẦN TỬ CON; nhóm đang mở nền {@code BG_ZEBRA}.
     */
    private final class NodeRenderer implements javax.swing.tree.TreeCellRenderer {
        private final Row row = new Row();

        @Override public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel,
                                                                boolean exp, boolean leaf, int r, boolean focus) {
            Object uo = (value instanceof DefaultMutableTreeNode d) ? d.getUserObject() : null;
            int depth = (value instanceof DefaultMutableTreeNode d2) ? d2.getLevel() : 0;
            // BasicTreeUI: rowX = totalChildIndent × (depth + depthOffset); totalChildIndent = 8+8.
            int off = t.isRootVisible() ? (t.getShowsRootHandles() ? 1 : 0)
                                        : (t.getShowsRootHandles() ? 0 : -1);
            row.width = Math.max(90, t.getWidth() - 16 * (depth + off) - 8);
            row.sel = sel;
            row.expanded = exp;
            if (!(uo instanceof MapScene.Node n)) {
                row.set(String.valueOf(uo), "", true, true, true, Theme.TEXT_DIM);
                return row;
            }
            boolean group = !leaf;
            boolean live = scene != null && scene.activeInHierarchy(n);
            row.set(n.name, rowBadge(n, group), group, n.active, live, badgeColor(n));
            return row;
        }
    }

    /** Badge phải: renderer → {@code L7 · #6}; đường/vùng → loại; nhóm → số con. */
    private String rowBadge(MapScene.Node n, boolean group) {
        if (n.hasRenderer) return "L" + n.sortLayerIdx + " · #" + n.sortOrder;
        if (n.colKind == MapScene.ColKind.EDGE) {
            String k = n.hasPlatformEffector ? "ONEWAY" : n.physLayer == 6 ? "ĐẤT" : "EDGE";
            return k + " " + (n.pts == null ? 0 : n.pts.length);
        }
        if (n.colKind == MapScene.ColKind.BOX) return "BOX";
        if (n.fx != MapScene.EffectKind.NONE) return kindShort(n.fx);
        if (group && scene != null) return String.valueOf(scene.children(n).size());
        return "";
    }

    /** Một dòng cây vẽ tay — nền/thanh chọn/ô tick/tên/badge đúng token bản thiết kế. */
    private static final class Row extends JComponent {
        String name = "", badge = "";
        boolean sel, group, checked, live, expanded;
        Color badgeFg = Theme.TEXT_MUTED;
        int width = 200;

        void set(String name, String badge, boolean group, boolean checked, boolean live, Color badgeFg) {
            this.name = (name == null) ? "" : name;
            this.badge = (badge == null) ? "" : badge;
            this.group = group;
            this.checked = checked;
            this.live = live;
            this.badgeFg = badgeFg;
        }

        @Override public Dimension getPreferredSize() { return new Dimension(width, 32); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (sel) {
                g2.setColor(Theme.ACCENT_SEL);
                g2.fillRoundRect(0, 1, w - 1, h - 2, 7, 7);
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(0, 5, 3, h - 10, 2, 2);
            } else if (group && expanded) {
                g2.setColor(Theme.BG_ZEBRA);
                g2.fillRoundRect(0, 1, w - 1, h - 2, 7, 7);
            }
            Theme.paintTickBox(g2, 8, (h - 15) / 2, 15, checked);

            // badge căn PHẢI (đo trước để biết chỗ còn lại cho tên)
            int badgeX = w - 6;
            if (!badge.isEmpty()) {
                g2.setFont(Theme.mono(11, Font.PLAIN));
                FontMetrics bfm = g2.getFontMetrics();
                if (group) {
                    int bw = bfm.stringWidth(badge);
                    badgeX = w - 8 - bw;
                    g2.setColor(Theme.TEXT_DIM);
                    g2.drawString(badge, badgeX, (h + bfm.getAscent() - bfm.getDescent()) / 2);
                } else {
                    int bw = bfm.stringWidth(badge) + 14;
                    badgeX = w - 6 - bw;
                    int by = (h - 20) / 2;
                    g2.setColor(sel ? Theme.alpha(Theme.ACCENT, 41) : Theme.BG_INPUT);
                    g2.fillRoundRect(badgeX, by, bw, 20, 5, 5);
                    g2.setColor(sel ? Theme.alpha(Theme.ACCENT, 102) : Theme.BORDER_SOFT);
                    g2.drawRoundRect(badgeX, by, bw - 1, 19, 5, 5);
                    g2.setColor(sel ? Theme.ACCENT_HOVER : badgeFg);
                    g2.drawString(badge, badgeX + 7, (h + bfm.getAscent() - bfm.getDescent()) / 2);
                }
            }

            int nameX = 31;
            g2.setFont(Theme.font(13, (group || sel) ? Font.BOLD : Font.PLAIN));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(sel ? Theme.ACCENT_HOVER : live ? Theme.TEXT_2 : Theme.TEXT_DIM);
            g2.drawString(clip(fm, name, Math.max(10, badgeX - nameX - 8)), nameX,
                    (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }

        private static String clip(FontMetrics fm, String s, int max) {
            if (fm.stringWidth(s) <= max) return s;
            for (int i = s.length() - 1; i > 1; i--) {
                String t = s.substring(0, i) + "…";
                if (fm.stringWidth(t) <= max) return t;
            }
            return "…";
        }
    }

    private String badgeText(MapScene.Node n) {
        StringBuilder sb = new StringBuilder();
        if (n.hasRenderer) {
            sb.append(n.isSprite ? "SPR" : "MESH");
            sb.append(" L").append(n.sortLayerIdx).append('#').append(n.sortOrder);
            if (canvas.isFrontOfPlayer(n)) sb.append(" ▲trước");
        }
        if (n.colKind == MapScene.ColKind.EDGE) {
            if (sb.length() > 0) sb.append("  ");
            sb.append(n.hasPlatformEffector ? "ONEWAY" : n.physLayer == 6 ? "ĐẤT" : "EDGE");
            sb.append('(').append(n.pts == null ? 0 : n.pts.length).append(')');
        } else if (n.colKind == MapScene.ColKind.BOX) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("BOX");
        }
        if (n.fx != MapScene.EffectKind.NONE) {
            if (sb.length() > 0) sb.append("  ");
            sb.append("✨").append(kindShort(n.fx));
        }
        if (sb.length() == 0) sb.append("nhóm");
        return sb.toString();
    }

    private Color badgeColor(MapScene.Node n) {
        if (n.colKind == MapScene.ColKind.EDGE) return n.physLayer == 6 ? Theme.RED : Theme.BLUE;
        if (n.colKind == MapScene.ColKind.BOX) return Theme.ACCENT;
        if (!n.hasRenderer) return Theme.TEXT_DIM;
        if (canvas.isFrontOfPlayer(n)) return Theme.PURPLE;
        return n.isSprite ? Theme.TEXT_MUTED : Theme.PRICE_GEM;
    }

    // ── Inspector ──

    /**
     * Panel phải 352 (handoff §7.3) — 3 tab + CHÂN GHIM hoàn tác.
     * Bản cũ nhồi "chọn: Sprite_2 · layer 7 / order 6 · sau player" vào thẻ nổi trên canvas (chỉ
     * ĐỌC được); ở đây nó thành card SỬA ĐƯỢC.
     */
    private JComponent buildInspector() {
        JPanel p = Theme.card();
        p.setLayout(new BorderLayout());
        p.setPreferredSize(new Dimension(COL_INSPECTOR, 10));
        p.setMinimumSize(new Dimension(COL_INSPECTOR, 10));

        inspTabs = new JTabbedPane();
        inspTabs.setOpaque(false);
        inspTabs.setBackground(Theme.BG_SURFACE);
        inspTabs.addTab("Thuộc tính", buildPropsTab());
        inspTabs.addTab("Layer", buildLayerTab());
        inspTabs.addTab("Hiệu ứng", buildFxTab());
        p.add(inspTabs, BorderLayout.CENTER);
        p.add(buildUndoFooter(), BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildPropsTab() {
        JPanel inner = new ScrollCol();
        inner.setBorder(new EmptyBorder(12, 12, 12, 12));

        secSort = buildSelectedCard();          // card 1 — vật thể đang chọn
        inner.add(secSort);
        inner.add(Box.createVerticalStrut(10));

        // Khối ĐƯỜNG KẺ đặt ngay dưới card 1 (không phải cuối trang cuộn) vì khi đã chọn 1 đường
        // thì đó chính là thứ đang sửa; node không phải đường kẻ thì ẨN HẲN cho panel gọn đúng
        // như bản thiết kế.
        secLine = sect("Đường kẻ (EdgeCollider2D)", buildLineBox());
        secEdge = sect("Biên map (vùng camera)", buildEdgeBox());
        secLine.setVisible(false);
        secEdge.setVisible(false);        // chỉ hiện ở chế độ Biên map hoặc khi chọn 1 trong 4 thanh
        inner.add(secLine);
        inner.add(Box.createVerticalStrut(10));
        inner.add(secEdge);
        inner.add(Box.createVerticalStrut(10));

        inner.add(buildZoneCard());             // card 2 — vùng & đường kẻ
        inner.add(Box.createVerticalStrut(10));
        inner.add(buildSortLayerCard());        // card 3 — nguồn sorting layer
        inner.add(Box.createVerticalStrut(10));

        secInfo = sect("Chi tiết phần tử", buildInfoBox());
        secTransform = sect("Biến đổi (scale · góc · lật)", buildTransformBox());
        secBg = sect("Nền / ảnh", buildBgBox());
        for (JPanel s : new JPanel[]{secInfo, secTransform, secBg}) {
            inner.add(s);
            inner.add(Box.createVerticalStrut(10));
        }
        inner.add(Box.createVerticalGlue());
        return scroll(inner);
    }

    /** Card 1 — vật thể đang chọn: Sorting · Order (+↑↓) · Vị trí · So với player. */
    private JPanel buildSelectedCard() {
        JPanel card = Theme.innerCard();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel head = Theme.row();
        JPanel dot = new JPanel();
        dot.setBackground(Theme.ACCENT);
        Theme.lockSize(dot, 7, 7);
        head.add(dot);
        head.add(Box.createHorizontalStrut(8));
        lbName.setFont(Theme.font(14, Font.BOLD));
        lbName.setForeground(Theme.TEXT);
        head.add(lbName);
        head.add(Box.createHorizontalGlue());
        head.add(lbGroup);
        card.add(Theme.capH(head));
        card.add(Box.createVerticalStrut(10));

        // Sorting
        cboLayer.removeAllItems();
        for (SortingLayers.Layer l : layers.all()) cboLayer.addItem(l);
        cboLayer.setFont(Theme.mono(13, Font.PLAIN));
        cboLayer.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int idx,
                                                                    boolean sel, boolean foc) {
                super.getListCellRendererComponent(list, v, idx, sel, foc);
                if (v instanceof SortingLayers.Layer l) {
                    boolean player = l.index() == layers.playerIndex();
                    setText(l.index() + " — " + l.name() + (player ? "  ← layer của PLAYER" : ""));
                    if (!sel) setForeground(player ? Theme.ACCENT
                            : l.index() > layers.playerIndex() ? Theme.PURPLE : Theme.TEXT_2);
                }
                return this;
            }
        });
        cboLayer.addActionListener(e -> {
            MapScene.Node n = curNode;
            if (syncing || n == null || !n.hasRenderer) return;
            Object v = cboLayer.getSelectedItem();
            if (!(v instanceof SortingLayers.Layer l) || l.index() == n.sortLayerIdx) return;
            canvas.pushUndo();
            setLayerIdx(n, l.index());
            afterSortChange(n);
        });
        card.add(propRow("Sorting", cboLayer, 32));
        card.add(Box.createVerticalStrut(8));

        // Order + 2 nút vuông
        spOrder = Theme.spin(0, -32768, 32767);
        spOrder.addChangeListener(e -> {
            MapScene.Node n = curNode;
            if (syncing || n == null || !n.hasRenderer) return;
            int v = Theme.spinInt(spOrder);
            if (v == n.sortOrder) return;
            pushUndoCoalesced(spOrder);
            n.sortOrder = v;
            n.dSorting = true;
            afterSortChange(n);
        });
        JPanel ord = Theme.row();
        ord.add(Theme.lockSize(Theme.monoSpin(spOrder, 74, 32), 74, 32));
        ord.add(Box.createHorizontalStrut(8));
        btnOrderUp = Theme.iconButton(Theme.icon(Theme.IC_ARROW_UP, 14, Theme.TEXT_2), 36, 32,
                "Lên trên 1 bậc trong cùng sorting layer", e -> nudgeOrder(1));
        btnOrderDown = Theme.iconButton(Theme.icon(Theme.IC_ARROW_DOWN, 14, Theme.TEXT_2), 36, 32,
                "Xuống dưới 1 bậc trong cùng sorting layer", e -> nudgeOrder(-1));
        ord.add(btnOrderUp);
        ord.add(Box.createHorizontalStrut(8));
        ord.add(btnOrderDown);
        ord.add(Box.createHorizontalGlue());
        card.add(propRow("Order", ord, 32));
        card.add(Box.createVerticalStrut(6));

        JPanel ext = Theme.row();
        ext.add(Box.createHorizontalStrut(92));
        btnOrderTop = Theme.ghost("Lên đầu", 28, e -> extremeOrder(true));
        btnOrderBottom = Theme.ghost("Xuống cuối", 28, e -> extremeOrder(false));
        ext.add(Theme.lockH(btnOrderTop, 28));
        ext.add(Box.createHorizontalStrut(8));
        ext.add(Theme.lockH(btnOrderBottom, 28));
        ext.add(Box.createHorizontalGlue());
        ext.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        card.add(ext);
        card.add(Box.createVerticalStrut(8));

        // Vị trí X / Y
        spX = dspin(0, 0.1, -1e6, 1e6);
        spY = dspin(0, 0.1, -1e6, 1e6);
        spX.addChangeListener(e -> applyPos(spX));
        spY.addChangeListener(e -> applyPos(spY));
        JPanel pos = Theme.row();
        pos.add(Theme.inputWrap("X", spX, 32));
        pos.add(Box.createHorizontalStrut(8));
        pos.add(Theme.inputWrap("Y", spY, 32));
        card.add(propRow("Vị trí", pos, 32));
        card.add(Box.createVerticalStrut(8));

        // So với player (segmented) — vẫn ghi qua cboFront để giữ nguyên logic cũ
        segFront = new Theme.Segmented(32, 26, false, true, "Sau player", "Trước player");
        segFront.onChange(i -> {
            MapScene.Node n = curNode;
            if (syncing || n == null || !n.hasRenderer) return;
            applyFrontBack(n, i == 1);
        });
        card.add(propRow("So với player", segFront, 32));
        card.add(Box.createVerticalStrut(8));

        lbSortWarn.setFont(Theme.font(12, Font.PLAIN));
        lbSortWarn.setForeground(Theme.TEXT_MUTED);
        lbSortWarn.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(Theme.capH(lbSortWarn));
        return Theme.capH(card);
    }

    /** Hàng "nhãn 82 | control giãn" của card thuộc tính. */
    private static JPanel propRow(String label, JComponent field, int h) {
        JPanel r = Theme.row();
        JLabel l = Theme.label(label, 13, Font.PLAIN, Theme.TEXT_MUTED);
        Theme.lockSize(l, 82, h);
        r.add(l);
        r.add(Box.createHorizontalStrut(10));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        if (field.getPreferredSize().height != h)
            field.setPreferredSize(new Dimension(field.getPreferredSize().width, h));
        // CO ĐƯỢC: combo sorting layer có preferredSize theo mục dài nhất; không cho co thì cả cột
        // rộng ra quá 352 và bị cắt mất mép phải.
        field.setMinimumSize(new Dimension(40, h));
        r.add(field);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return r;
    }

    /** Card 2 — vùng &amp; đường kẻ: đếm + 2 nút tạo. */
    private JPanel buildZoneCard() {
        JPanel card = Theme.innerCard();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(12, 12, 12, 12));
        JPanel head = Theme.row();
        head.add(Theme.label("Vùng & đường kẻ", 13, Font.BOLD, Theme.TEXT));
        head.add(Box.createHorizontalGlue());
        head.add(lbZoneCount);
        card.add(Theme.capH(head));
        card.add(Box.createVerticalStrut(10));
        JPanel g = new JPanel(new GridLayout(1, 2, 8, 0));
        g.setOpaque(false);
        g.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnNewLine = Theme.ghost("Thêm đường kẻ", 32, e -> newLine());
        btnNewLine.setToolTipText("Tạo 1 EdgeCollider2D mới (đường đất hoặc oneway) rồi vẽ trực tiếp trên canvas");
        JButton bSpine = Theme.ghost("Thêm Spine…", 32, e -> addSpineNode());
        bSpine.setToolTipText("Đặt thêm cây / hiệu ứng Spine vào map."
                + " Chọn sẵn 1 nhóm trên cây bên trái để node mới nằm trong nhóm đó.");
        g.add(btnNewLine);
        g.add(bSpine);
        g.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        card.add(g);
        return Theme.capH(card);
    }

    /** Card 3 — nguồn bảng sorting layer (dời từ thanh công cụ xuống). */
    private JPanel buildSortLayerCard() {
        JPanel card = Theme.innerCard();
        card.setLayout(new BoxLayout(card, BoxLayout.X_AXIS));
        card.setBorder(new EmptyBorder(9, 12, 9, 12));
        card.add(Theme.label("Sorting layer", 13, Font.BOLD, Theme.TEXT_2));
        card.add(Box.createHorizontalGlue());
        card.add(Theme.monoLabel(layers.loadedFromFile()
                ? "TagManager · " + layers.all().size() + " layer"
                : "BẢNG DỰ PHÒNG · " + layers.all().size() + " layer", 12,
                layers.loadedFromFile() ? Theme.TEXT_DIM : Theme.ACCENT));
        card.add(Box.createHorizontalStrut(8));
        JLabel chev = new JLabel(Theme.icon(Theme.IC_CHEVRON_DOWN, 11, Theme.TEXT_DIM));
        card.add(chev);
        card.setToolTipText("Bấm để xem toàn bộ bảng sorting layer (tab Layer)");
        Theme.onClick(card, () -> inspTabs.setSelectedIndex(1));
        return Theme.capH(card);
    }

    /** Tab "Layer" — toàn bộ bảng sorting layer + số phần tử đang nằm ở từng layer. */
    private JComponent buildLayerTab() {
        JPanel inner = new ScrollCol();
        inner.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel card = Theme.innerCard();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(12, 12, 12, 12));
        JPanel head = Theme.row();
        head.add(Theme.sectionHeader("Bảng sorting layer", Theme.TEXT_MUTED));
        head.add(Box.createHorizontalGlue());
        head.add(Theme.monoLabel(layers.loadedFromFile() ? "TagManager.asset" : "dự phòng", 12,
                layers.loadedFromFile() ? Theme.TEXT_DIM : Theme.ACCENT));
        card.add(Theme.capH(head));
        card.add(Box.createVerticalStrut(8));

        layerRows = Theme.colBox();
        int pi = layers.playerIndex();
        for (SortingLayers.Layer l : layers.all()) {
            JPanel r = Theme.row();
            r.setBorder(new EmptyBorder(5, 6, 5, 6));
            boolean player = (l.index() == pi);
            JLabel idx = Theme.monoLabel(String.valueOf(l.index()), 12,
                    player ? Theme.ACCENT : l.index() > pi ? Theme.PURPLE : Theme.TEXT_DIM);
            Theme.lockSize(idx, 26, 18);
            r.add(idx);
            r.add(Theme.label(l.name() + (player ? "  ← player" : ""), 13, player ? Font.BOLD : Font.PLAIN,
                    player ? Theme.ACCENT : l.index() > pi ? Theme.PURPLE : Theme.TEXT_2));
            r.add(Box.createHorizontalGlue());
            JLabel cnt = Theme.monoLabel("0", 12, Theme.TEXT_DIM);
            r.add(cnt);
            layerCountLabels.put(l.index(), cnt);
            r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            layerRows.add(r);
        }
        card.add(layerRows);
        inner.add(Theme.capH(card));
        inner.add(Box.createVerticalStrut(10));
        JLabel note = flowLabel();
        note.setText(W + "Layer &gt; " + pi + " ⇒ phần tử vẽ ĐÈ lên nhân vật (tiền cảnh)."
                + " Cùng layer thì order &gt; " + PLAYER_BODY_ORDER + " mới che được player.</html>");
        note.setFont(Theme.font(12, Font.PLAIN));
        note.setForeground(Theme.TEXT_DIM);
        inner.add(note);
        inner.add(Box.createVerticalGlue());
        return scroll(inner);
    }

    /** Tab "Hiệu ứng" — tham số hiệu ứng của phần tử đang chọn. */
    private JComponent buildFxTab() {
        JPanel inner = new ScrollCol();
        inner.setBorder(new EmptyBorder(12, 12, 12, 12));
        lbFxNone = flowLabel();
        lbFxNone.setText(W + "Chọn một phần tử CÓ hiệu ứng (Spine, nước chảy, cá bơi…)"
                + " để chỉnh tham số ở đây.</html>");
        lbFxNone.setFont(Theme.font(13, Font.PLAIN));
        lbFxNone.setForeground(Theme.TEXT_DIM);
        inner.add(lbFxNone);
        secFx = sect("Hiệu ứng của phần tử đang chọn", buildFxBox());
        secFx.setVisible(false);          // chỉ hiện khi node đang chọn CÓ hiệu ứng
        inner.add(secFx);
        inner.add(Box.createVerticalStrut(10));
        JButton bList = Theme.ghost("Bảng hiệu ứng trong map…", 32, e -> showFxList());
        bList.setToolTipText("Bảng liệt kê MỌI hiệu ứng của map — bấm 1 dòng là nhảy tới đúng chỗ");
        bList.setAlignmentX(Component.LEFT_ALIGNMENT);
        inner.add(Theme.lockH(bList, 32));
        inner.add(Box.createVerticalGlue());
        return scroll(inner);
    }

    /** Chân panel phải (GHIM): hoàn tác 1 bước + ô đỏ hoàn tác tất cả, cách xa nút Lưu. */
    private JComponent buildUndoFooter() {
        JPanel p = Theme.colBox();
        p.setOpaque(true);
        p.setBackground(Theme.BG_SURFACE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                new EmptyBorder(10, 12, 10, 12)));
        JPanel r = Theme.row();
        btnUndo = Theme.ghost("Hoàn tác (0 bước)", 38, e -> canvas.undo());
        btnUndo.setIcon(Theme.icon(Theme.IC_UNDO, 14, Theme.TEXT_2));
        btnUndo.setIconTextGap(8);
        btnUndo.setToolTipText("Hoàn tác 1 bước (Ctrl+Z)");
        btnUndo.setPreferredSize(new Dimension(10, 38));
        btnUndo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        r.add(btnUndo);
        r.add(Box.createHorizontalStrut(8));
        r.add(Theme.iconTint(Theme.icon(Theme.IC_RESET, 16, Theme.RED), Theme.RED, 44, 38,
                "Hoàn tác TẤT CẢ — nạp lại prefab từ đĩa, bỏ mọi thay đổi chưa lưu", e -> doRevertAll()));
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        p.add(r);
        p.add(Box.createVerticalStrut(6));
        JLabel note = Theme.label("Hoàn tác tất cả nằm riêng, cách xa nút Lưu.", 12, Font.PLAIN, Theme.TEXT_DIM);
        note.setHorizontalAlignment(SwingConstants.CENTER);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        p.add(note);
        return p;
    }

    /**
     * Cột nội dung của panel phải: BÁM ĐÚNG bề rộng khung cuộn. JPanel thường thì viewport lấy
     * preferredSize (combo "18 — LocalPlayer ← layer của PLAYER" kéo cột rộng ra) ⇒ cắt mất cột phải.
     */
    private static final class ScrollCol extends JPanel implements Scrollable {
        ScrollCol() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Ẩn/hiện 1 khối trong panel phải (chỉ revalidate khi trạng thái thật sự đổi). */
    private static void showSec(JPanel sec, boolean on) {
        if (sec == null || sec.isVisible() == on) return;
        sec.setVisible(on);
        Container p = sec.getParent();
        if (p != null) { p.revalidate(); p.repaint(); }
    }

    /** Nhãn nhiều dòng trong cột hẹp: chiều cao tự nở theo nội dung đã xuống dòng. */
    private static JLabel flowLabel() {
        JLabel l = new JLabel(" ") {
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Mở đầu chuỗi HTML có ép bề rộng — không có nó thì JLabel không bao giờ tự xuống dòng. */
    private static final String W = "<html><body style='width:258px'>";

    private static JScrollPane scroll(JComponent inner) {
        JScrollPane sp = new JScrollPane(inner,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    /** Khối phụ có tiêu đề, dạng card con (nền BG_MAIN, viền BORDER_SOFT, bo 10). */
    private static JPanel sect(String title, JComponent content) {
        JPanel p = Theme.innerCard();
        p.setLayout(new BorderLayout(0, 8));
        p.setBorder(new EmptyBorder(10, 12, 12, 12));
        p.add(Theme.sectionHeader(title, Theme.TEXT_MUTED), BorderLayout.NORTH);
        content.setOpaque(false);
        p.add(content, BorderLayout.CENTER);
        return Theme.capH(p);
    }

    private JComponent buildInfoBox() {
        JPanel g = grid();
        GridBagConstraints c = gbc();
        int y = 0;
        // "Tên" đã nằm ở đầu card "vật thể đang chọn" — không lặp lại ở đây.
        addRow(g, c, y++, "Loại", lbType);
        addRow(g, c, y++, "Physics layer", lbPhys);
        addRow(g, c, y++, "Tag", lbTag);
        addRow(g, c, y++, "Ảnh", lbTex);
        lbThumb.setPreferredSize(new Dimension(72, 72));
        lbThumb.setHorizontalAlignment(SwingConstants.CENTER);
        lbThumb.setBorder(BorderFactory.createLineBorder(Theme.BORDER_SOFT));
        addRow(g, c, y++, "Xem trước", lbThumb);

        chkActive = Theme.check("Bật phần tử này (m_IsActive)", true, v -> {
            MapScene.Node n = curNode;
            if (syncing || n == null) return;
            canvas.pushUndo();
            n.active = v;
            n.dActive = true;
            refreshTreeRow(n);
            canvas.repaint();
            updateStatus();
        });
        chkRendOn = Theme.check("Hiện hình (renderer m_Enabled)", true, v -> {
            MapScene.Node n = curNode;
            if (syncing || n == null) return;
            canvas.pushUndo();
            n.rendEnabled = v;
            n.dRendEnabled = true;
            canvas.repaint();
            updateStatus();
        });
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; c.weightx = 1;
        g.add(chkActive, c);
        c.gridy = y++;
        g.add(chkRendOn, c);
        c.gridwidth = 1;
        return g;
    }

    private JComponent buildTransformBox() {
        JPanel g = grid();
        GridBagConstraints c = gbc();
        int y = 0;
        // X/Y đã nằm ở card "vật thể đang chọn" (handoff §7.3) — khối này chỉ còn scale/góc/lật.
        spSX = dspin(1, 0.05, -1000, 1000);
        spSY = dspin(1, 0.05, -1000, 1000);
        spRot = dspin(0, 1, -3600, 3600);
        spSX.addChangeListener(e -> applyScale(spSX));
        spSY.addChangeListener(e -> applyScale(spSY));
        spRot.addChangeListener(e -> {
            MapScene.Node n = curNode;
            if (syncing || n == null) return;
            pushUndoCoalesced(spRot);
            n.rotDeg = dval(spRot);
            n.dTransform = true;
            canvas.repaint();
            updateStatus();
        });
        lbServer.setFont(Theme.mono(12, Font.PLAIN));
        lbServer.setForeground(Theme.TEXT_DIM);
        addRow(g, c, y++, "Toạ độ server", lbServer);
        addRow(g, c, y++, "Scale X", spSX);
        addRow(g, c, y++, "Scale Y", spSY);
        addRow(g, c, y++, "Góc xoay (°)", spRot);

        chkFlipX = Theme.check("Lật ngang (Flip X)", false, v -> applyFlip(true, v));
        chkFlipY = Theme.check("Lật dọc (Flip Y)", false, v -> applyFlip(false, v));
        JPanel fl = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        fl.setOpaque(false);
        fl.add(chkFlipX);
        fl.add(Box.createHorizontalStrut(10));
        fl.add(chkFlipY);
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; c.weightx = 1;
        g.add(fl, c);
        JLabel hint = flowLabel();
        hint.setText(W + "Kéo trên canvas hoặc dùng phím mũi tên để nhích (Ctrl = 1 đơn vị server).</html>");
        hint.setFont(Theme.font(11, Font.PLAIN));
        hint.setForeground(Theme.TEXT_DIM);
        c.gridy = y++;
        g.add(hint, c);
        c.gridwidth = 1;
        return g;
    }

    private JComponent buildLineBox() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);

        // ── KIỂU CHẶN: player đi xuyên qua đường này theo chiều nào ──
        JPanel head = Theme.colBox();
        for (MapScene.LineBlock b : MapScene.LineBlock.values()) cboBlock.addItem(b);
        cboBlock.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int idx,
                                                                    boolean sel, boolean foc) {
                super.getListCellRendererComponent(list, v, idx, sel, foc);
                if (v instanceof MapScene.LineBlock b) {
                    setText(b.label);
                    if (!sel) setForeground(b == MapScene.LineBlock.SOLID ? Theme.RED
                            : b == MapScene.LineBlock.PASS ? Theme.TEXT_DIM : Theme.BLUE);
                }
                return this;
            }
        });
        cboBlock.setToolTipText("<html><b>Đặc</b> — đường đất thường: đứng được, không xuyên qua"
                + " được chiều nào.<br><b>Nhảy xuyên từ DƯỚI lên</b> — sàn oneway: nhảy từ dưới"
                + " xuyên qua, đứng được ở trên, bấm Xuống để rơi xuống.<br>"
                + "<b>Chặn đi LÊN</b> — trần chặn bay/nhảy lên; từ trên đi xuống thì lọt qua.<br>"
                + "<b>Không chặn</b> — chỉ để đánh dấu, không cản gì.</html>");
        cboBlock.addActionListener(e -> applyLineBlock());
        head.add(propRow("Kiểu chặn", cboBlock, 32));
        head.add(Box.createVerticalStrut(6));
        lbLineKind.setFont(Theme.font(12, Font.PLAIN));
        lbLineKind.setForeground(Theme.TEXT_MUTED);
        head.add(lbLineKind);
        p.add(head, BorderLayout.NORTH);

        modelPts = new DefaultTableModel(new String[]{"STT", "X (unit)", "Y (unit)", "X (sv)", "Y (sv)"}, 0) {
            @Override public boolean isCellEditable(int r, int col) { return col == 1 || col == 2; }
            @Override public Class<?> getColumnClass(int col) {
                return (col == 1 || col == 2) ? Double.class : Integer.class;
            }
        };
        modelPts.addTableModelListener(e -> {
            if (syncing || e.getType() != TableModelEvent.UPDATE) return;
            onPointEdited(e.getFirstRow(), e.getColumn());
        });
        tblPts = new JTable(modelPts);
        tblPts.setFont(Theme.font(12, Font.PLAIN));
        tblPts.setRowHeight(24);
        tblPts.setShowGrid(false);
        tblPts.setShowHorizontalLines(true);
        tblPts.setGridColor(Theme.DIVIDER);
        tblPts.setIntercellSpacing(new Dimension(0, 1));
        tblPts.getTableHeader().setFont(Theme.font(11, Font.BOLD));
        tblPts.getTableHeader().setForeground(Theme.TEXT_MUTED);
        tblPts.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tblPts.getColumnModel().getColumn(0).setMaxWidth(42);
        tblPts.getColumnModel().getColumn(3).setMaxWidth(70);
        tblPts.getColumnModel().getColumn(4).setMaxWidth(70);
        JScrollPane sp = new JScrollPane(tblPts);
        sp.setPreferredSize(new Dimension(240, 170));
        sp.getViewport().setBackground(Theme.BG_SURFACE);
        p.add(sp, BorderLayout.CENTER);

        JPanel btns = new JPanel(new GridLayout(3, 2, 8, 8));
        btns.setOpaque(false);
        btns.add(Theme.ghost("Thêm đỉnh", 32, e -> addVertex()));
        btns.add(Theme.tint("Xoá đỉnh", Theme.RED, 32, e -> delVertex()));
        btns.add(Theme.ghost("Đảo chiều", 32, e -> reverseLine()));
        btns.add(Theme.ghost("San phẳng Y", 32, e -> flattenY()));
        btnDelLine = Theme.tint("Xoá đường này", Theme.RED, 32, e -> deleteLine());
        btnDelLine.setToolTipText("Gỡ hẳn GameObject đường kẻ đang chọn khỏi prefab (chỉ ghi khi bấm Lưu)");
        btns.add(btnDelLine);
        btns.add(Box.createHorizontalGlue());
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildBgBox() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        btnTex = Theme.ghost("🖼 Đổi ảnh nền / sprite…", 32, e -> changeTexture());
        p.add(btnTex, BorderLayout.NORTH);
        lbBgInfo.setFont(Theme.font(11, Font.PLAIN));
        lbBgInfo.setForeground(Theme.TEXT_DIM);
        p.add(lbBgInfo, BorderLayout.CENTER);
        return p;
    }

    // ── Biên map / vùng camera (Y2) ──

    /**
     * 4 ô số của 4 thanh biên. Client chỉ đọc <b>X của Trái/Phải</b> và <b>Y của Trên/Dưới</b>
     * ({@code MapManager.Init()} → {@code CameraFollow.SetBound}), nên mỗi thanh chỉ cho sửa đúng
     * 1 trục — trục còn lại kéo trên canvas (nó chỉ ảnh hưởng tường va chạm, không ảnh hưởng camera).
     */
    private JComponent buildEdgeBox() {
        JPanel g = grid();
        GridBagConstraints c = gbc();
        int y = 0;

        spEdgeL = dspin(0, 0.1, -1e6, 1e6);
        spEdgeR = dspin(0, 0.1, -1e6, 1e6);
        spEdgeT = dspin(0, 0.1, -1e6, 1e6);
        spEdgeB = dspin(0, 0.1, -1e6, 1e6);
        spEdgeL.addChangeListener(e -> applyEdge("left", spEdgeL, true));
        spEdgeR.addChangeListener(e -> applyEdge("right", spEdgeR, true));
        spEdgeT.addChangeListener(e -> applyEdge("top", spEdgeT, false));
        spEdgeB.addChangeListener(e -> applyEdge("bottom", spEdgeB, false));
        addRow(g, c, y++, "Trái — X (unit)", spEdgeL);
        addRow(g, c, y++, "Phải — X (unit)", spEdgeR);
        addRow(g, c, y++, "Trên — Y (unit)", spEdgeT);
        addRow(g, c, y++, "Dưới — Y (unit)", spEdgeB);

        lbCamSize.setFont(Theme.font(11, Font.PLAIN));
        lbCamSize.setForeground(Theme.TEXT_2);
        addRow(g, c, y++, "Khung camera", lbCamSize);

        JButton btnFit = Theme.ghost("⤢ Khớp vào ảnh map", 30, e -> fitEdgesToMap());
        btnFit.setToolTipText("Tự tính 4 biên ôm vừa toàn bộ ảnh map (nới thêm nếu hẹp hơn màn hình 20:9)");
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; c.weightx = 1;
        g.add(btnFit, c);

        lbEdgeMiss.setFont(Theme.font(11, Font.BOLD));
        lbEdgeMiss.setForeground(Theme.RED);
        c.gridy = y++;
        g.add(lbEdgeMiss, c);

        lbCamWarn.setFont(Theme.font(11, Font.PLAIN));
        lbCamWarn.setForeground(Theme.TEXT_MUTED);
        c.gridy = y++;
        g.add(lbCamWarn, c);
        c.gridwidth = 1;
        return g;
    }

    /** Sửa 1 ô số biên → dời GỐC TRANSFORM của thanh đó (world → local do MapScene lo). */
    private void applyEdge(String which, JSpinner src, boolean axisX) {
        if (syncing || scene == null) return;
        MapScene.Node n = scene.boundsEdge(which);
        if (n == null) return;
        double v = dval(src);
        double[] o = scene.worldOrigin(n);
        if (Math.abs((axisX ? o[0] : o[1]) - v) < 1e-9) return;
        pushUndoCoalesced(src);
        if (axisX) scene.setWorldOrigin(n, v, o[1]);
        else scene.setWorldOrigin(n, o[0], v);
        boolean old = syncing;
        syncing = true;
        fillEdgeInfo();
        if (curNode == n) fillTransform(n);
        syncing = old;
        refreshTreeRow(n);
        canvas.repaint();
        updateStatus();
    }

    /** Hiện khối "Biên map" khi đang ở chế độ Biên map HOẶC đang chọn 1 trong 4 thanh. */
    private void updateEdgeVisibility() {
        if (secEdge == null) return;
        boolean modeOn = modeBounds;
        boolean selEdge = (scene != null && curNode != null && scene.isBoundsEdge(curNode));
        boolean show = (scene != null) && (modeOn || selEdge);
        if (show) {
            boolean old = syncing;
            syncing = true;
            try {
                fillEdge();
            } finally {
                syncing = old;
            }
        }
        if (secEdge.isVisible() == show) return;
        secEdge.setVisible(show);
        Container p = secEdge.getParent();
        if (p != null) { p.revalidate(); p.repaint(); }
    }

    private void fillEdge() {
        fillEdgeSpin(spEdgeL, "left", true);
        fillEdgeSpin(spEdgeR, "right", true);
        fillEdgeSpin(spEdgeT, "top", false);
        fillEdgeSpin(spEdgeB, "bottom", false);
        fillEdgeInfo();
    }

    private void fillEdgeSpin(JSpinner sp, String which, boolean axisX) {
        MapScene.Node n = (scene != null) ? scene.boundsEdge(which) : null;
        sp.setEnabled(n != null);
        if (n == null) return;
        double[] o = scene.worldOrigin(n);
        sp.setValue(round4(axisX ? o[0] : o[1]));
    }

    /** Nhãn kích thước khung camera + toàn bộ cảnh báo tiếng Việt khi biên bị thu nhỏ. */
    private void fillEdgeInfo() {
        List<String> miss = new ArrayList<>();
        if (scene != null) {
            if (scene.boundsEdge("left") == null) miss.add("Trái");
            if (scene.boundsEdge("right") == null) miss.add("Phải");
            if (scene.boundsEdge("top") == null) miss.add("Trên");
            if (scene.boundsEdge("bottom") == null) miss.add("Dưới");
        }
        lbEdgeMiss.setText(miss.isEmpty() ? " "
                : W + "⚠ Map THIẾU thanh biên: <b>" + String.join(", ", miss) + "</b>"
                  + " — client sẽ lỗi khi MapManager.Init() đọc _edgeCol…, phải thêm bên Unity.</html>");

        double[] b = (scene != null) ? scene.cameraBounds() : null;
        if (b == null) {
            lbCamSize.setText("— (chưa đủ 4 thanh biên)");
            lbCamWarn.setText(" ");
            return;
        }
        double w = b[2] - b[0], h = b[3] - b[1];
        lbCamSize.setText(W + "<b>" + fmt(w) + " × " + fmt(h) + "</b> unit"
                + " &nbsp;·&nbsp; server <b>" + Math.round(w * ppu) + " × " + Math.round(h * ppu) + "</b>"
                + "<br>góc dưới-trái (" + fmt(b[0]) + ", " + fmt(b[1]) + ")"
                + " · trên-phải (" + fmt(b[2]) + ", " + fmt(b[3]) + ")"
                + "<br>server (" + Math.round(b[0] * ppu) + ", " + Math.round(b[1] * ppu) + ")"
                + " → (" + Math.round(b[2] * ppu) + ", " + Math.round(b[3] * ppu) + ")</html>");

        List<String> hard = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        if (w < CAM_MIN_W) hard.add("Bề rộng " + fmt(w) + " &lt; " + fmt(CAM_MIN_W)
                + " unit → máy 20:9 sẽ có minBound &gt; maxBound ⇒ <b>camera giật/nhảy hai bên</b>.");
        else if (w < CAM_MIN_W + 2) soft.add("Bề rộng sát ngưỡng 20:9 (" + fmt(CAM_MIN_W)
                + " unit) — máy màn hình dài hơn nữa sẽ hỏng.");
        if (h < CAM_MIN_H) hard.add("Chiều cao " + fmt(h) + " &lt; " + fmt(CAM_MIN_H)
                + " unit (2 × orthoSize 8.1) → <b>camera giật theo chiều dọc</b>.");

        double[] gy = groundYRange();
        if (gy != null) {
            if (b[1] > gy[0] + 1e-6) hard.add("Biên <b>Dưới</b> (" + fmt(b[1]) + ") CAO hơn chỗ thấp nhất"
                    + " của đường đất (" + fmt(gy[0]) + ") → ActorHandler liên tục kéo player lên"
                    + " <i>Trên − 2</i> (nhân vật bị teleport lên trời).");
            if (b[3] - gy[1] < 2) soft.add("Biên <b>Trên</b> chỉ cách đỉnh đường đất " + fmt(b[3] - gy[1])
                    + " unit — player bay/spawn bị kẹp ở <i>Trên − 2</i> nên có thể chui vào đất.");
        }

        StringBuilder sb = new StringBuilder(W);
        for (String s : hard) sb.append("⛔ ").append(s).append("<br>");
        for (String s : soft) sb.append("⚠ ").append(s).append("<br>");
        sb.append("• Thu nhỏ biên có thể làm <b>quái / NPC / điểm spawn / cổng chuyển map</b> lưu trong DB")
          .append(" (bảng map_info_config) nằm NGOÀI vùng camera — người chơi thấy mà không tới được.<br>")
          .append("• Kéo Trái/Phải còn đổi cổng nào bị coi là “cổng trái / cổng phải”")
          .append(" (MapManager.FindNearestGateIndexes).<br>")
          .append("• 4 số trên là toạ độ WORLD; tool tự quy đổi sang local khi ghi vào prefab.</html>");
        lbCamWarn.setText(sb.toString());
        lbCamWarn.setForeground(!hard.isEmpty() ? Theme.RED : !soft.isEmpty() ? Theme.ACCENT : Theme.TEXT_MUTED);
    }

    /** {@code {yThấpNhất, yCaoNhất}} của mọi đường đất (physics layer 6), null nếu map không có. */
    private double[] groundYRange() {
        if (scene == null) return null;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        boolean any = false;
        for (MapScene.Node n : scene.nodes()) {
            if (n.colKind != MapScene.ColKind.EDGE || n.physLayer != MapScene.LAYER_GROUND) continue;
            if (!scene.activeInHierarchy(n)) continue;
            for (double[] p : scene.worldPoints(n)) {
                lo = Math.min(lo, p[1]);
                hi = Math.max(hi, p[1]);
                any = true;
            }
        }
        return any ? new double[]{lo, hi} : null;
    }

    /** Bounding box world của MỌI ảnh map đang bật (bỏ collider — biên/đường kẻ dài hơn map rất nhiều). */
    private double[] spriteBounds() {
        if (scene == null) return null;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (MapScene.Node n : scene.nodes()) {
            if (!n.hasRenderer || n.baseW <= 0 || n.baseH <= 0) continue;
            if (!scene.activeInHierarchy(n)) continue;
            double[] c = scene.worldCenter(n);
            double[] s = scene.worldSize(n);
            minX = Math.min(minX, c[0] - Math.abs(s[0]) / 2);
            maxX = Math.max(maxX, c[0] + Math.abs(s[0]) / 2);
            minY = Math.min(minY, c[1] - Math.abs(s[1]) / 2);
            maxY = Math.max(maxY, c[1] + Math.abs(s[1]) / 2);
            any = true;
        }
        return any ? new double[]{minX, minY, maxX, maxY} : null;
    }

    /** "Khớp vào ảnh map": 4 biên ôm vừa bounding box ảnh, nới ra nếu hẹp hơn khung camera 20:9. */
    private void fitEdgesToMap() {
        if (scene == null) { msg("Chưa nạp map nào.", JOptionPane.WARNING_MESSAGE); return; }
        double[] bb = spriteBounds();
        if (bb == null) {
            msg("Map này không có phần tử ảnh nào để canh biên theo.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        MapScene.Node nl = scene.boundsEdge("left"), nr = scene.boundsEdge("right");
        MapScene.Node nt = scene.boundsEdge("top"), nb = scene.boundsEdge("bottom");
        if (nl == null || nr == null || nt == null || nb == null) {
            msg("Map thiếu thanh biên (" + (nl == null ? "Trái " : "") + (nr == null ? "Phải " : "")
                    + (nt == null ? "Trên " : "") + (nb == null ? "Dưới" : "") + ") → không canh được.",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        double cx = (bb[0] + bb[2]) / 2, cy = (bb[1] + bb[3]) / 2;
        double w = Math.max(bb[2] - bb[0], CAM_MIN_W);
        double h = Math.max(bb[3] - bb[1], CAM_MIN_H);
        double nlx = cx - w / 2, nrx = cx + w / 2, nby = cy - h / 2, nty = cy + h / 2;

        double[] old = scene.cameraBounds();
        String cur = (old == null) ? "(chưa đủ 4 thanh)"
                : fmt(old[0]) + " … " + fmt(old[2]) + "  ×  " + fmt(old[1]) + " … " + fmt(old[3]);
        boolean widened = (bb[2] - bb[0]) < CAM_MIN_W || (bb[3] - bb[1]) < CAM_MIN_H;
        int ans = JOptionPane.showConfirmDialog(owner(),
                "<html>Đặt lại 4 thanh biên theo ảnh map:<br><br>"
                        + "<b>Hiện tại:</b> " + cur + "<br>"
                        + "<b>Sau khi khớp:</b> " + fmt(nlx) + " … " + fmt(nrx) + "  ×  "
                        + fmt(nby) + " … " + fmt(nty) + "<br>"
                        + "(rộng " + fmt(w) + " × cao " + fmt(h) + " unit · server "
                        + Math.round(w * ppu) + " × " + Math.round(h * ppu) + ")<br><br>"
                        + (widened ? "⚠ Ảnh map nhỏ hơn khung camera 20:9 nên biên đã được NỚI RA cho đủ "
                                     + fmt(CAM_MIN_W) + " × " + fmt(CAM_MIN_H) + " unit.<br><br>" : "")
                        + "Chỉ đổi VỊ TRÍ 4 thanh, không đụng m_Points. Ctrl+Z lấy lại được.</html>",
                "Khớp biên map vào ảnh", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) return;

        canvas.pushUndo();
        scene.setWorldOrigin(nl, nlx, scene.worldOrigin(nl)[1]);
        scene.setWorldOrigin(nr, nrx, scene.worldOrigin(nr)[1]);
        scene.setWorldOrigin(nt, scene.worldOrigin(nt)[0], nty);
        scene.setWorldOrigin(nb, scene.worldOrigin(nb)[0], nby);
        boolean o = syncing;
        syncing = true;
        fillEdge();
        if (curNode != null) fillTransform(curNode);
        syncing = o;
        for (MapScene.Node n : new MapScene.Node[]{nl, nr, nt, nb}) refreshTreeRow(n);
        canvas.repaint();
        updateStatus();
        lbStatus.setText("Đã khớp 4 thanh biên vào ảnh map — CHƯA ghi vào file,"
                + " bấm \"💾 Lưu vào prefab\" để ghi (Ctrl+Z để bỏ).");
        lbStatus.setForeground(Theme.ACCENT);
    }

    // ── Hiệu ứng (Y3) ──

    private JComponent buildFxBox() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        lbFxHead.setFont(Theme.font(12, Font.BOLD));
        lbFxHead.setForeground(Theme.ACCENT);
        p.add(lbFxHead, BorderLayout.NORTH);

        fxBody.setLayout(new BoxLayout(fxBody, BoxLayout.Y_AXIS));
        fxBody.setOpaque(false);
        p.add(fxBody, BorderLayout.CENTER);

        JPanel btns = new JPanel(new GridLayout(1, 2, 6, 6));
        btns.setOpaque(false);
        btnFxPreview = Theme.primary("▶ Xem thử", 30, e -> previewFx());
        btnFxPreview.setToolTipText("Chạy lại hiệu ứng từ giây 0 để xem ngay kết quả vừa sửa");
        btnFxDefault = Theme.ghost("↺ Về mặc định", 30, e -> resetFx());
        btnFxDefault.setToolTipText("Trả mọi tham số hiệu ứng của phần tử này về giá trị lúc nạp map");
        btns.add(btnFxPreview);
        btns.add(btnFxDefault);
        p.add(btns, BorderLayout.SOUTH);
        return p;
    }

    /** Dựng lại thân khối hiệu ứng theo node đang chọn (mỗi loại hiệu ứng có bộ field riêng). */
    private void fillFx(MapScene.Node n) {
        fxBody.removeAll();
        if (n == null || scene == null || n.fx == MapScene.EffectKind.NONE) {
            lbFxHead.setText(" ");
            fxBody.revalidate();
            fxBody.repaint();
            return;
        }
        StringBuilder h = new StringBuilder("<html>");
        for (int i = 0; i < n.fxList.size(); i++) {
            MapScene.Fx f = n.fxList.get(i);
            if (i > 0) h.append("<br>");
            h.append(kindShort(f.kind)).append(" — ").append(f.name == null ? "?" : f.name);
        }
        lbFxHead.setText(h.append("</html>").toString());
        for (MapScene.Fx f : n.fxList) fxBody.add(fxGroup(n, f));
        fxBody.revalidate();
        fxBody.repaint();
    }

    /** 1 component hiệu ứng = 1 nhóm field (node cá vừa Spine vừa FishSwim thì có 2 nhóm). */
    private JComponent fxGroup(MapScene.Node n, MapScene.Fx f) {
        JPanel g = grid();
        g.setBackground(Theme.BG_SURFACE2);
        g.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SOFT),
                new EmptyBorder(6, 0, 2, 0)));
        GridBagConstraints c = gbc();
        int y = 0;

        JLabel t = new JLabel(f.kind.label());
        t.setFont(Theme.font(11, Font.BOLD));
        t.setForeground(f.kind.real() ? Theme.PURPLE : Theme.TEXT_DIM);
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; c.weightx = 1;
        g.add(t, c);
        if (f.folder != null) {
            JLabel pth = descLabel(f.folder.toString());
            pth.setToolTipText(f.folder.toString());
            c.gridy = y++;
            g.add(pth, c);
        } else if (f.kind == MapScene.EffectKind.SPINE) {
            JLabel w = descLabel("⚠ Không tìm thấy file skeleton → chỉ sửa được tham số, không xem trước được.");
            w.setForeground(Theme.ACCENT);
            c.gridy = y++;
            g.add(w, c);
        }
        if (f.kind == MapScene.EffectKind.SPINE) {
            JButton swap = Theme.tint("🔄 Đổi skeleton…", Theme.PURPLE, 28, e -> chooseSkeleton(n, f));
            swap.setToolTipText("Thay skeleton của phần tử này bằng skeleton khác trong client"
                    + " (ghi cả skeletonDataAsset lẫn material).");
            c.gridx = 0; c.gridy = y++; c.gridwidth = 2; c.weightx = 1;
            g.add(swap, c);
        }
        c.gridwidth = 1;

        for (Map.Entry<String, String> e : new LinkedHashMap<>(f.params).entrySet()) {
            addRow(g, c, y++, e.getKey(), fxWidget(n, f, e.getKey(), e.getValue()));
            String d = FX_DESC.get(e.getKey());
            if (d == null) continue;
            c.gridx = 1; c.gridy = y++; c.gridwidth = 1; c.weightx = 1;
            g.add(descLabel(d), c);
        }
        return g;
    }

    /**
     * Chọn widget hợp với KIỂU của field: {fileID}/field cấm → chỉ đọc · {x,y} → 2 ô số ·
     * {@code _animationName} của Spine → combo lấy từ file .json · 0/1 → checkbox · số → ô số ·
     * còn lại → ô chữ.
     */
    private JComponent fxWidget(MapScene.Node n, MapScene.Fx f, String key, String raw) {
        String v = (raw == null) ? "" : raw.trim();

        if (FX_LOCKED.contains(key) || v.startsWith("{fileID:") || v.startsWith("[") || v.startsWith("- ")) {
            String show = v.isEmpty() ? "(trống)" : v;
            if (v.startsWith("{fileID:")) {
                MapScene.Node ref = scene.byTr(fileIdOf(v));
                if (ref != null) show = v + "  → " + ref.name;
            }
            JLabel l = descLabel(show);
            l.setToolTipText("Field này không nên sửa bằng tay ở đây (dễ vỡ prefab).");
            return l;
        }

        Matcher xy = FX_XY_PAT.matcher(v);
        if (v.startsWith("{") && xy.find()) {
            double vx = num(xy.group(1)), vy = num(xy.group(2));
            JPanel p = new JPanel(new GridLayout(1, 2, 4, 0));
            p.setOpaque(false);
            JSpinner sx = dspin(round4(vx), 0.01, -1e6, 1e6);
            JSpinner sy = dspin(round4(vy), 0.01, -1e6, 1e6);
            Runnable push = () -> onFxEdit(n, key, "{x: " + fmt(dval(sx)) + ", y: " + fmt(dval(sy)) + "}", sx);
            sx.addChangeListener(e -> push.run());
            sy.addChangeListener(e -> push.run());
            p.add(sx);
            p.add(sy);
            return p;
        }

        if (key.equals("_animationName") && f.kind == MapScene.EffectKind.SPINE) {
            List<String> anims = spineAnimations(f);
            JComboBox<String> cb = new JComboBox<>();
            cb.setEditable(anims.isEmpty());        // đọc được skeleton → chỉ cho chọn tên CÓ THẬT
            for (String a : anims) cb.addItem(a);
            if (!anims.contains(v)) cb.addItem(v);
            cb.setSelectedItem(v);
            cb.setToolTipText(anims.isEmpty()
                    ? "Không đọc được danh sách animation của skeleton — gõ tay tên animation."
                    : anims.size() + " animation đọc từ file skeleton (.json hoặc .skel.bytes)");
            cb.addActionListener(e -> {
                Object s = cb.getSelectedItem();
                onFxEdit(n, key, (s == null) ? "" : s.toString().trim(), cb);
            });
            return cb;
        }

        if (FX_BOOL.contains(key) && (v.equals("0") || v.equals("1"))) {
            JCheckBox cb = new JCheckBox(v.equals("1") ? "bật" : "tắt", v.equals("1"));
            cb.setOpaque(false);
            cb.setFocusable(false);
            cb.setForeground(Theme.TEXT_2);
            cb.addActionListener(e -> {
                cb.setText(cb.isSelected() ? "bật" : "tắt");
                onFxEdit(n, key, cb.isSelected() ? "1" : "0", cb);
            });
            return cb;
        }

        if (isNumber(v)) {
            boolean unit01 = key.equals("attackStart") || key.equals("riseDuration") || key.equals("releaseStart");
            JSpinner sp = dspin(round4(num(v)), unit01 ? 0.05 : 0.1, -1e6, 1e6);
            sp.addChangeListener(e -> onFxEdit(n, key, fmt(dval(sp)), sp));
            return sp;
        }

        JTextField tf = new JTextField(v);
        tf.setPreferredSize(new Dimension(160, 28));
        tf.addActionListener(e -> onFxEdit(n, key, tf.getText().trim(), tf));
        tf.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                onFxEdit(n, key, tf.getText().trim(), tf);
            }
        });
        return tf;
    }

    /** Ghi 1 tham số hiệu ứng vào scene (bật cờ dFx) rồi vẽ lại canvas. */
    private void onFxEdit(MapScene.Node n, String key, String val, Object src) {
        if (syncing || scene == null || n == null) return;
        if (val.equals(scene.fxParam(n, key, null))) return;      // không đổi → khỏi ghi undo
        pushUndoCoalesced(src);
        scene.setFxParam(n, key, val);
        canvas.repaint();
        updateStatus();
        refreshTreeRow(n);
    }

    private void previewFx() {
        canvas.resetEffectTime();
        canvas.setPlayEffects(true);
        if (miPlayFx != null) miPlayFx.setSelected(true);
        if (curNode != null) canvas.panTo(curNode);
        focusCanvas();
    }

    /** Trả mọi field hiệu ứng của node đang chọn về giá trị lúc nạp map. */
    private void resetFx() {
        MapScene.Node n = curNode;
        if (scene == null || n == null || n.fxList.isEmpty()) return;
        List<String> ch = fxChanges(n);
        if (ch.isEmpty()) {
            msg("Phần tử này chưa sửa tham số hiệu ứng nào.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        canvas.pushUndo();
        for (MapScene.Fx f : n.fxList) {
            Map<String, String> o = fxOrig.get(f);
            if (o == null) continue;
            for (Map.Entry<String, String> e : o.entrySet()) scene.setFxParam(n, e.getKey(), e.getValue());
        }
        if (fxChanges(n).isEmpty()) n.dFx = false;                // về đúng như cũ → hết "chưa lưu"
        updateInspector(n);
        canvas.repaint();
        updateStatus();
        lbStatus.setText("Đã trả " + ch.size() + " tham số hiệu ứng của '" + n.name + "' về mặc định.");
        lbStatus.setForeground(Theme.TEXT_MUTED);
    }

    /** Tham số hiệu ứng đã đổi so với lúc nạp, dạng {@code "moveSpeed: 2 → 3.5"}. */
    private List<String> fxChanges(MapScene.Node n) {
        List<String> out = new ArrayList<>();
        if (n == null) return out;
        for (MapScene.Fx f : n.fxList) {
            Map<String, String> o = fxOrig.get(f);
            for (Map.Entry<String, String> e : f.params.entrySet()) {
                String old = (o == null) ? null : o.get(e.getKey());
                if (old == null || old.equals(e.getValue())) continue;
                out.add(e.getKey() + ": " + (old.isEmpty() ? "(trống)" : old)
                        + " → " + (e.getValue().isEmpty() ? "(trống)" : e.getValue()));
            }
        }
        return out;
    }

    /** Chụp tham số hiệu ứng ngay sau khi nạp map — mốc cho nút "Về mặc định" và hộp thoại Lưu. */
    private void snapshotFx() {
        fxOrig.clear();
        camOrig = null;
        if (scene == null) return;
        camOrig = scene.cameraBounds();
        for (MapScene.Node n : scene.nodes())
            for (MapScene.Fx f : n.fxList) fxOrig.put(f, new LinkedHashMap<>(f.params));
    }

    /**
     * Tên animation của thư mục skeleton — nhận CẢ {@code .json} lẫn {@code .skel.bytes} nhị phân
     * (ưu tiên .json y như {@link com.apex.maptool.spine.SpineCharacter#load}). Rỗng = không đọc được.
     */
    private List<String> spineAnimations(MapScene.Fx f) {
        if (f == null || f.folder == null || !Files.isDirectory(f.folder)) return List.of();
        return spineAnims.computeIfAbsent(f.folder, dir -> {
            Path json = null, skel = null;
            try (java.util.stream.Stream<Path> st = Files.list(dir)) {
                for (Path p : st.toList()) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.endsWith(".json")) json = p;
                    else if (n.endsWith(".skel.bytes") || n.endsWith(".skel")) skel = p;
                }
            } catch (Exception e) {
                System.err.println("[MapLayout] liệt kê thư mục Spine lỗi (" + dir + "): " + e.getMessage());
                return List.of();
            }
            try {
                if (json == null && skel == null) return List.of();
                SpineData d = (json != null) ? SpineData.load(json) : SpineData.loadBinary(skel);
                List<String> out = new ArrayList<>(d.animations.keySet());
                java.util.Collections.sort(out);
                return out;
            } catch (Exception e) {
                System.err.println("[MapLayout] đọc animation Spine lỗi (" + dir + "): " + e.getMessage());
                return List.of();
            }
        });
    }

    private static String kindShort(MapScene.EffectKind k) {
        return switch (k) {
            case SPINE      -> "Spine";
            case ANIMATOR   -> "Animator";
            case WATER_WAVE -> "Nước chảy";
            case FISH_SWIM  -> "Cá bơi";
            case WAVE_WASH  -> "Sóng vỗ";
            case WATER2D    -> "Mặt nước";
            case OTHER      -> "Script khác";
            default         -> "—";
        };
    }

    private static JLabel descLabel(String s) {
        JLabel l = new JLabel("<html>" + s + "</html>");
        l.setFont(Theme.font(10, Font.PLAIN));
        l.setForeground(Theme.TEXT_DIM);
        return l;
    }

    private static long fileIdOf(String v) {
        Matcher m = FX_FILEID_PAT.matcher(v);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ── Bảng "Hiệu ứng trong map" ──

    /**
     * Cửa sổ liệt kê MỌI hiệu ứng của map (không modal — để vừa xem bảng vừa sửa trên canvas).
     * Bấm 1 dòng = chọn node đó và đưa ra giữa màn hình.
     */
    private void showFxList() {
        if (scene == null) { msg("Chưa nạp map nào.", JOptionPane.WARNING_MESSAGE); return; }
        if (dlgFx == null) buildFxDialog();
        fillFxList();
        dlgFx.setVisible(true);
        dlgFx.toFront();
    }

    private void buildFxDialog() {
        dlgFx = new JDialog(owner(), "Hiệu ứng trong map", Dialog.ModalityType.MODELESS);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.BG_SURFACE);
        p.setBorder(new EmptyBorder(10, 12, 12, 12));

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.X_AXIS));
        top.setOpaque(false);
        top.add(dimLabel("Lọc theo loại"));
        top.add(Box.createHorizontalStrut(6));
        cboFxFilter = new JComboBox<>();
        cboFxFilter.setMaximumSize(new Dimension(240, 30));
        cboFxFilter.setPreferredSize(new Dimension(240, 30));
        cboFxFilter.addActionListener(e -> { if (!syncing) fillFxList(); });
        top.add(cboFxFilter);
        top.add(Box.createHorizontalStrut(10));
        top.add(Theme.ghost("↻ Cập nhật", 30, e -> fillFxList()));
        top.add(Box.createHorizontalGlue());
        p.add(top, BorderLayout.NORTH);

        modelFx = new DefaultTableModel(
                new String[]{"Tên", "Loại", "Asset", "Vị trí (x, y)", "Sorting", "So với player"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tblFx = new JTable(modelFx);
        tblFx.setFont(Theme.font(12, Font.PLAIN));
        tblFx.setRowHeight(24);
        tblFx.setShowGrid(false);
        tblFx.setShowHorizontalLines(true);
        tblFx.setGridColor(Theme.DIVIDER);
        tblFx.getTableHeader().setFont(Theme.font(11, Font.BOLD));
        tblFx.getTableHeader().setForeground(Theme.TEXT_MUTED);
        tblFx.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblFx.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || syncing) return;
            int r = tblFx.getSelectedRow();
            if (r < 0 || r >= fxRows.size()) return;
            MapScene.Node n = fxRows.get(r);
            canvas.setSelected(n);
            canvas.panTo(n);
            onCanvasSelect(n);
        });
        JScrollPane sp = new JScrollPane(tblFx);
        sp.getViewport().setBackground(Theme.BG_SURFACE);
        sp.setPreferredSize(new Dimension(880, 420));
        p.add(sp, BorderLayout.CENTER);

        JLabel hint = new JLabel("Bấm 1 dòng để chọn + nhảy tới phần tử đó."
                + " Sửa tham số ở khối “Hiệu ứng” bên phải canvas.");
        hint.setFont(Theme.font(11, Font.PLAIN));
        hint.setForeground(Theme.TEXT_DIM);
        p.add(hint, BorderLayout.SOUTH);

        dlgFx.setContentPane(p);
        dlgFx.pack();
        dlgFx.setLocationRelativeTo(owner());
    }

    private void fillFxList() {
        if (modelFx == null || scene == null) return;
        List<MapScene.Node> all = scene.effectNodes();

        syncing = true;
        try {
            // combo lọc: chỉ liệt kê loại CÓ THẬT trong map này
            Object keep = cboFxFilter.getSelectedItem();
            List<String> kinds = new ArrayList<>();
            kinds.add("Tất cả (" + all.size() + " phần tử)");
            for (Map.Entry<MapScene.EffectKind, Integer> e : scene.effectCounts().entrySet())
                if (e.getValue() > 0) kinds.add(kindShort(e.getKey()) + " (" + e.getValue() + ")");
            cboFxFilter.setModel(new DefaultComboBoxModel<>(kinds.toArray(new String[0])));
            cboFxFilter.setSelectedItem(kinds.contains(String.valueOf(keep)) ? keep : kinds.get(0));

            String sel = String.valueOf(cboFxFilter.getSelectedItem());
            String want = (sel.indexOf(" (") > 0) ? sel.substring(0, sel.indexOf(" (")) : sel;
            boolean allKinds = want.startsWith("Tất cả");

            modelFx.setRowCount(0);
            fxRows.clear();
            for (MapScene.Node n : all) {
                List<String> ks = new ArrayList<>();
                for (MapScene.EffectKind k : n.fxKinds()) ks.add(kindShort(k));
                if (!allKinds && !ks.contains(want)) continue;
                double[] o = scene.worldOrigin(n);
                modelFx.addRow(new Object[]{
                        n.name,
                        String.join(" + ", ks),
                        n.fxName == null ? "—" : n.fxName,
                        "(" + fmt(o[0]) + ", " + fmt(o[1]) + ")",
                        n.hasRenderer ? ("L" + n.sortLayerIdx + " #" + n.sortOrder) : "—",
                        !n.hasRenderer ? "—" : isFront(n) ? "▲ trước player" : "sau player"});
                fxRows.add(n);
            }
        } finally {
            syncing = false;
        }
        dlgFx.setTitle("Hiệu ứng trong map " + scene.mapId() + " — " + fxRows.size() + " phần tử");
    }

    // ── Status bar ──

    /**
     * Thanh dưới cao 44 (handoff §7.5): số liệu thành CHIP · giữa 1 dòng mẹo chuột (cũng là nơi in
     * thông báo tức thời) · phải chip cảnh báo. Bản cũ là 3 dòng chữ 11px chen nhau.
     */
    private JComponent buildStatusBar() {
        JPanel bar = Theme.card();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(new EmptyBorder(0, 14, 0, 14));
        bar.setPreferredSize(new Dimension(10, 44));
        bar.setMinimumSize(new Dimension(10, 44));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

        for (Theme.Chip c : new Theme.Chip[]{chNodes, chRend, chLines, chBox, chFx, chDirty}) {
            bar.add(c);
            bar.add(Box.createHorizontalStrut(8));
        }
        bar.add(Box.createHorizontalGlue());
        lbStatus.setFont(Theme.font(12, Font.PLAIN));
        lbStatus.setForeground(Theme.TEXT_DIM);
        lbStatus.setToolTipText("<html>" + canvas.shortcutHelp().replace(" · ", "<br>· ") + "</html>");
        bar.add(lbStatus);
        bar.add(Box.createHorizontalGlue());
        lbWarnNested.setVisible(false);
        bar.add(Box.createHorizontalStrut(8));
        bar.add(lbWarnNested);
        return bar;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Nạp map
    // ─────────────────────────────────────────────────────────────────────

    private void loadSelectedMap() {
        int id = selectedMapId();
        if (id <= 0) {
            msg("Không đọc được id map từ ô chọn map.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        loadMap(id);
    }

    private int selectedMapId() {
        Object sel = cboMap.getEditor().getItem();
        String s = (sel != null) ? sel.toString() : String.valueOf(cboMap.getSelectedItem());
        Matcher m = MAP_ID_PAT.matcher(s == null ? "" : s);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /**
     * Nạp toàn bộ prefab của map {@code mapId} (chạy nền, con trỏ chờ).
     * Có thay đổi chưa lưu → hỏi trước khi bỏ.
     */
    public void loadMap(int mapId) {
        Path prefab = cfg.mapPrefab(mapId);
        if (!Files.exists(prefab)) {
            msg("Không thấy file prefab của map " + mapId + ":\n" + prefab.toAbsolutePath()
                    + "\n\nKiểm tra client.repo trong config.properties:\n" + cfg.clientRepo(),
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (scene != null && scene.dirty()) {
            int ans = JOptionPane.showConfirmDialog(owner(),
                    "Map hiện tại còn " + dirtyCount() + " phần tử sửa chưa lưu.\nNạp map khác sẽ MẤT các thay đổi đó. Tiếp tục?",
                    "Còn thay đổi chưa lưu", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ans != JOptionPane.YES_OPTION) return;
        }
        selectComboMap(mapId);
        root.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        lbStatus.setText("Đang nạp map " + mapId + " …");

        new SwingWorker<MapScene, Void>() {
            @Override protected MapScene doInBackground() throws Exception {
                ensureGuidIndex();
                return new MapSceneLoader(guidIndex, matResolver, texCache, ppu).load(prefab, mapId);
            }

            @Override protected void done() {
                try {
                    MapScene s = get();
                    scene = s;
                    curMapId = mapId;
                    canvas.setScene(s);
                    snapshotFx();               // mốc "mặc định" của mọi tham số hiệu ứng
                    spineAnims.clear();
                    rebuildTree();
                    if (dlgFx != null && dlgFx.isVisible()) fillFxList();
                    updateInspector(null);
                    updateStatus();
                    lbStatus.setText(MOUSE_HINT);          // trả dòng giữa về mẹo chuột
                    lbStatus.setForeground(Theme.TEXT_DIM);
                    SwingUtilities.invokeLater(() -> { canvas.fitView(); focusCanvas(); });
                } catch (Exception ex) {
                    scene = null;
                    fxOrig.clear();
                    canvas.setScene(null);
                    rebuildTree();
                    updateStatus();
                    msg("Nạp map " + mapId + " thất bại:\n" + rootMsg(ex)
                            + "\n\nFile: " + prefab.toAbsolutePath(), JOptionPane.ERROR_MESSAGE);
                } finally {
                    root.setCursor(Cursor.getDefaultCursor());
                }
            }
        }.execute();
    }

    /** GuidIndex/MaterialResolver riêng của Bố cục Map (lần 2 đọc guid-index.cache nên nhanh). */
    private void ensureGuidIndex() throws Exception {
        if (guidIndex == null) {
            GuidIndex gi = new GuidIndex();
            gi.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            guidIndex = gi;
        }
        if (matResolver == null) matResolver = new MaterialResolver(guidIndex);
    }

    private void selectComboMap(int mapId) {
        for (String s : mapItems) {
            if (s.startsWith(mapId + " ")) {
                syncing = true;
                cboMap.setModel(new DefaultComboBoxModel<>(mapItems.toArray(new String[0])));
                cboMap.setSelectedItem(s);
                syncing = false;
                return;
            }
        }
        syncing = true;
        cboMap.getEditor().setItem(String.valueOf(mapId));
        syncing = false;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Đồng bộ chọn / thay đổi
    // ─────────────────────────────────────────────────────────────────────

    private void onCanvasSelect(MapScene.Node n) {
        DefaultMutableTreeNode tn = (n != null) ? treeIndex.get(n) : null;
        syncing = true;
        if (tn != null) {
            TreePath tp = new TreePath(tn.getPath());
            tree.setSelectionPath(tp);
            tree.scrollPathToVisible(tp);
        } else {
            tree.clearSelection();
        }
        syncing = false;
        updateInspector(n);
    }

    /** Canvas báo có sửa (kéo chuột, phím, undo…) → cập nhật số liệu trên inspector. */
    private void onCanvasChange() {
        MapScene.Node n = canvas.selected();
        if (n != curNode) { updateInspector(n); return; }   // canvas tự đổi node (click trên canvas)
        syncing = true;
        if (n != null) {
            fillTransform(n);
            fillSorting(n);
            if (!tblPts.isEditing()) fillPoints(n);
        }
        // kéo thanh biên trên canvas → 4 ô số + khung camera phải chạy theo ngay
        if (secEdge != null && secEdge.isVisible()) fillEdge();
        syncing = false;
        if (n != null) refreshTreeRow(n);
        updateStatus();
    }

    /**
     * Sau undo/redo/thêm/xoá đường: danh sách node có thể ĐÃ ĐỔI (node mới xuất hiện, node cũ biến
     * mất) nên phải dựng lại CẢ cây — chỉ {@code tree.repaint()} thì {@code treeIndex} còn giữ node
     * mồ côi và node mới không có dòng nào trong cây.
     */
    private void afterStructuralChange() {
        rebuildTree();
        onCanvasSelect(canvas.selected());    // canvas là nguồn sự thật sau undo/redo
        updateStatus();
        focusCanvas();
    }

    private void refreshTreeRow(MapScene.Node n) {
        DefaultMutableTreeNode tn = treeIndex.get(n);
        if (tn != null) treeModel.nodeChanged(tn);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inspector: đổ dữ liệu
    // ─────────────────────────────────────────────────────────────────────

    private void updateInspector(MapScene.Node n) {
        syncing = true;
        try {
            boolean has = (n != null && scene != null);
            curNode = has ? n : null;
            boolean rend = has && n.hasRenderer;
            boolean edge = has && n.colKind == MapScene.ColKind.EDGE;
            enableAll(secInfo, has);
            enableAll(secTransform, has);
            enableAll(secSort, has);
            enableAll(secLine, edge);
            enableAll(secBg, rend);
            showSec(secLine, edge);           // ẩn hẳn khi không phải đường kẻ (gọn như bản thiết kế)
            showSec(secBg, rend);
            // Trong card "vật thể đang chọn", chỉ nhóm THỨ TỰ VẼ mới cần renderer; ô Vị trí thì
            // phần tử nào cũng dời được (nhóm, đường kẻ, thanh biên…).
            cboLayer.setEnabled(rend);
            spOrder.setEnabled(rend);
            for (JButton b : new JButton[]{btnOrderUp, btnOrderDown, btnOrderTop, btnOrderBottom})
                if (b != null) b.setEnabled(rend);
            segFront.setSegEnabled(0, rend);
            segFront.setSegEnabled(1, rend);
            // "Thêm đường kẻ" chỉ cần đã nạp map, "Xoá cả đường này" cần đang chọn 1 EdgeCollider2D.
            btnNewLine.setEnabled(scene != null && !canvas.drawLineMode());
            btnDelLine.setEnabled(has && n.colKind == MapScene.ColKind.EDGE);

            // 2 khối MỚI: chỉ HIỆN khi dùng tới (ẩn hẳn cho gọn thay vì làm mờ)
            fillFx(has ? n : null);
            boolean fx = has && n.fx != MapScene.EffectKind.NONE;
            if (secFx.isVisible() != fx) {
                secFx.setVisible(fx);
                if (lbFxNone != null) lbFxNone.setVisible(!fx);
                if (secFx.getParent() != null) { secFx.getParent().revalidate(); secFx.getParent().repaint(); }
            }
            updateEdgeVisibility();

            if (!has) {
                lbName.setText("(chưa chọn phần tử nào)");
                lbGroup.setText("—");
                lbType.setText("—");
                lbPhys.setText("—");
                lbTag.setText("—");
                lbTex.setText("—");
                lbThumb.setIcon(null);
                lbServer.setText(" ");
                lbSortWarn.setText(" ");
                lbLineKind.setText(" ");
                lbBgInfo.setText(" ");
                modelPts.setRowCount(0);
                return;
            }

            lbName.setText(n.name);
            lbName.setToolTipText("GameObject &" + n.goAnchor + " · Transform &" + n.trAnchor);
            MapScene.Node par = scene.parent(n);
            lbGroup.setText(par != null && par.name != null ? par.name : "(gốc map)");
            lbType.setText(typeText(n));
            lbPhys.setText(n.physLayer + " · " + physName(n.physLayer));
            lbTag.setText(n.tag == null ? "Untagged" : n.tag);
            lbTex.setText(n.texture != null ? n.texture.getFileName().toString()
                    : (n.texGuid != null && !n.texGuid.isEmpty() ? "guid " + n.texGuid.substring(0, 8) + "… (không tìm thấy file)" : "—"));
            lbTex.setToolTipText(n.texture != null ? n.texture.toString() : null);
            lbThumb.setIcon(thumbOf(n));
            chkActive.setSelected(n.active);
            chkRendOn.setSelected(n.rendEnabled);
            chkRendOn.setEnabled(n.hasRenderer);

            fillTransform(n);
            fillSorting(n);
            fillPoints(n);
            fillBg(n);
        } finally {
            syncing = false;
        }
    }

    private void fillTransform(MapScene.Node n) {
        double[] c = scene.worldCenter(n);
        spX.setValue(round4(c[0]));
        spY.setValue(round4(c[1]));
        spSX.setValue(round4(n.sx));
        spSY.setValue(round4(n.sy));
        spRot.setValue(round4(n.rotDeg));
        lbServer.setText("(" + Math.round(c[0] * ppu) + ", " + Math.round(c[1] * ppu) + ")"
                + "   ·   local (" + fmt(n.px) + ", " + fmt(n.py) + ")");
        chkFlipX.setSelected(n.flipX);
        chkFlipY.setSelected(n.flipY);
        chkFlipX.setEnabled(n.hasRenderer && n.isSprite);
        chkFlipY.setEnabled(n.hasRenderer && n.isSprite);
    }

    private void fillSorting(MapScene.Node n) {
        if (!n.hasRenderer) {
            lbSortWarn.setText(W + "Không có renderer<br>⇒ không có thứ tự vẽ.</html>");
            lbSortWarn.setForeground(Theme.TEXT_DIM);
            return;
        }
        SortingLayers.Layer l = layers.byIndex(n.sortLayerIdx);
        if (l != null) cboLayer.setSelectedItem(l);
        spOrder.setValue(n.sortOrder);
        segFront.select(isFront(n) ? 1 : 0);
        updateSortWarn(n);
    }

    private void updateSortWarn(MapScene.Node n) {
        int pi = layers.playerIndex();
        SortingLayers.Layer pl = layers.byIndex(pi);
        String plName = (pl != null) ? pl.name() : "LocalPlayer";
        // Ngắt dòng đặt tay + KHÔNG dùng ▲ ▼ ⚠ ⇒ (font bundle không có, ra ô trống).
        if (n.sortLayerIdx > pi) {
            lbSortWarn.setText(W + "Layer " + n.sortLayerIdx + " &gt; " + pi + " (" + plName + ")<br>"
                    + "⟶ phần tử VẼ ĐÈ lên nhân vật (tiền cảnh).</html>");
            lbSortWarn.setForeground(Theme.PURPLE);
        } else if (n.sortLayerIdx == pi) {
            lbSortWarn.setText(W + "CÙNG layer với player (" + pi + " " + plName + ").<br>"
                    + "order &gt; " + PLAYER_BODY_ORDER + " = trước player,<br>"
                    + "order ≤ " + PLAYER_BODY_ORDER + " = sau player.<br>"
                    + "Hiện order = " + n.sortOrder + ".</html>");
            lbSortWarn.setForeground(Theme.ACCENT);
        } else {
            lbSortWarn.setText(W + "Layer " + n.sortLayerIdx + " &lt; " + pi + " (" + plName + ")<br>"
                    + "⟶ nhân vật đi ĐÈ LÊN phần tử này (hậu cảnh).</html>");
            lbSortWarn.setForeground(Theme.TEXT_MUTED);
        }
    }

    private void fillPoints(MapScene.Node n) {
        modelPts.setRowCount(0);
        if (n.colKind != MapScene.ColKind.EDGE) {
            lbLineKind.setText(W + (n.colKind == MapScene.ColKind.BOX
                    ? "Phần tử này là BoxCollider2D (vùng), không phải đường kẻ."
                    : "Phần tử này không có đường kẻ.") + "</html>");
            lbLineKind.setForeground(Theme.TEXT_DIM);
            return;
        }
        cboBlock.setSelectedItem(n.lineBlock());
        lbLineKind.setText(lineKindText(n));
        lbLineKind.setForeground(switch (n.lineBlock()) {
            case ONEWAY_UP, ONEWAY_DOWN -> Theme.BLUE;
            case PASS -> Theme.TEXT_DIM;
            default -> Theme.TEXT_2;
        });
        for (double[] p : scene.worldPoints(n)) {
            modelPts.addRow(new Object[]{modelPts.getRowCount(), round4(p[0]), round4(p[1]),
                    (int) Math.round(p[0] * ppu), (int) Math.round(p[1] * ppu)});
        }
        int lv = (canvas.lineNode() == n) ? canvas.lineVertex() : -1;
        if (lv >= 0 && lv < tblPts.getRowCount()) tblPts.setRowSelectionInterval(lv, lv);
    }

    /** Dòng giải thích dưới combo "Kiểu chặn": nó có nghĩa gì trong game + số liệu thô. */
    private String lineKindText(MapScene.Node n) {
        String nm = (n.name == null) ? "" : n.name;
        boolean edge = nm.equalsIgnoreCase("Top") || nm.equalsIgnoreCase("Bottom")
                || nm.equalsIgnoreCase("Left") || nm.equalsIgnoreCase("Right");
        String raw = "layer " + n.physLayer + " · " + physName(n.physLayer)
                + (n.hasPlatformEffector
                    ? "<br>effector xoay " + fmt(n.effRotOffset) + "° · cung " + fmt(n.effSurfaceArc) + "°"
                    : " · không effector");
        // Ngắt dòng bằng <br> ĐẶT TAY: JLabel chỉ tự xuống dòng khi CSS width được áp, mà điều đó
        // phụ thuộc container — đặt tay thì cột hẹp 352px không bao giờ bị cắt chữ.
        String what = switch (n.lineBlock()) {
            case SOLID -> n.physLayer == 6
                    ? "Player ĐỨNG được trên đường này.<br>Chặn cả từ trên lẫn từ dưới."
                    : "Chặn cả 2 chiều.<br>Layer " + n.physLayer + " nên client KHÔNG coi là sàn oneway.";
            case ONEWAY_UP -> "Nhảy từ DƯỚI lên là xuyên qua.<br>Đứng được ở trên, bấm Xuống để rơi xuyên.";
            case ONEWAY_DOWN -> "CHẶN đi lên (như trần nhà).<br>Từ trên đi xuống thì lọt qua.";
            case PASS -> "Không chặn chiều nào.<br>Chỉ còn tác dụng đánh dấu.";
        };
        return W + (edge ? "<b>BIÊN MAP (" + nm + ")</b> — giới hạn camera.<br>"
                + "Đổi kiểu chặn ở đây KHÔNG đổi vùng camera.<br>" : "") + what
                + "<br><span style='color:#5c5c68'>" + raw + "</span></html>";
    }

    /** Combo "Kiểu chặn" đổi → ghi vào scene (chưa đụng file, có undo). */
    private void applyLineBlock() {
        MapScene.Node n = curNode;
        if (syncing || scene == null || n == null || n.colKind != MapScene.ColKind.EDGE) return;
        if (!(cboBlock.getSelectedItem() instanceof MapScene.LineBlock b)) return;
        if (n.lineBlock() == b) return;
        canvas.pushUndo();
        if (!scene.setLineBlock(n, b)) return;
        syncing = true;
        lbLineKind.setText(lineKindText(n));
        syncing = false;
        refreshTreeRow(n);
        canvas.repaint();
        updateStatus();
        lbStatus.setText("Đường '" + n.name + "' → " + b.label
                + " — CHƯA ghi vào file, bấm \"Lưu vào prefab\" để ghi (Ctrl+Z để bỏ).");
        lbStatus.setForeground(Theme.ACCENT);
    }

    private void fillBg(MapScene.Node n) {
        if (!n.hasRenderer) {
            lbBgInfo.setText(W + "Chọn 1 phần tử có ảnh (SpriteRenderer / MeshRenderer) để đổi ảnh.</html>");
            btnTex.setEnabled(false);
            return;
        }
        // Spine cũng là MeshRenderer nhưng m_Materials là material ATLAS của skeleton — đổi .mat ở đây
        // là hỏng hình. Muốn đổi hình phải đổi skeletonDataAsset (khối "Hiệu ứng").
        if (n.fx == MapScene.EffectKind.SPINE) {
            btnTex.setEnabled(false);
            lbBgInfo.setText(W + "Đây là node <b>Spine</b> — hình do skeleton vẽ ra, KHÔNG đổi bằng"
                    + " .png/.mat được. Muốn đổi hình thì đổi <b>skeletonDataAsset</b> ở khối"
                    + " “Hiệu ứng”.</html>");
            return;
        }
        btnTex.setEnabled(true);
        boolean bg = isBgNode(n);
        btnTex.setText(n.isSprite ? "🖼 Đổi ảnh (.png)…" : "🖼 Đổi material nền (.mat)…");
        lbBgInfo.setText(W + "" + (bg ? "Phần tử thuộc nhóm NỀN / parallax. " : "")
                + (n.isSprite
                    ? "SpriteRenderer → chọn file .png trong Assets của client (tool tự đọc guid từ .png.meta)."
                    : "MeshRenderer (lớp nền) → phải chọn file <b>.mat</b>, KHÔNG phải .png, vì prefab lưu guid material.")
                + "</html>");
    }

    /** Node thuộc nhóm nền/parallax? (MeshRenderer, hoặc nằm dưới Layer_0..Layer_3 / _layersBG). */
    private boolean isBgNode(MapScene.Node n) {
        if (n == null || scene == null) return false;
        // Spine cũng là MeshRenderer nhưng KHÔNG phải lớp nền → loại ra trước
        if (n.fx == MapScene.EffectKind.SPINE) return false;
        if (n.hasRenderer && !n.isSprite) return true;
        int guard = 0;
        for (MapScene.Node c = n; c != null && guard++ < 64; c = scene.parent(c)) {
            if (c.trAnchor == scene.skyLayerTr() || scene.bgLayerTrs().contains(c.trAnchor)) return true;
            if (c.name != null && c.name.matches("(?i)layer_[0-3]")) return true;
        }
        return false;
    }

    private String typeText(MapScene.Node n) {
        StringBuilder sb = new StringBuilder();
        if (n.hasRenderer) sb.append(n.isSprite ? "SpriteRenderer" : "MeshRenderer" + (n.isQuadMesh ? " (quad nền)" : " (Spine/khác)"));
        if (n.colKind == MapScene.ColKind.EDGE) sb.append(sb.length() > 0 ? " + " : "").append("EdgeCollider2D");
        if (n.colKind == MapScene.ColKind.BOX) sb.append(sb.length() > 0 ? " + " : "").append("BoxCollider2D");
        if (n.hasPlatformEffector) sb.append(" + PlatformEffector2D");
        for (MapScene.Fx f : n.fxList) {
            sb.append(sb.length() > 0 ? " + " : "").append(kindShort(f.kind));
            if (f.name != null && !f.name.isEmpty()) sb.append(" (").append(f.name).append(')');
        }
        if (sb.length() == 0) sb.append("GameObject nhóm (không có hình)");
        return sb.toString();
    }

    private static String physName(int idx) {
        return (idx >= 0 && idx < PHYS_LAYERS.length) ? PHYS_LAYERS[idx] : "?";
    }

    /** Ảnh thu nhỏ ≤ 68 px của node (đã cắt sub-sprite nếu có). */
    private Icon thumbOf(MapScene.Node n) {
        if (n.texture == null) return null;
        BufferedImage img = texCache.image(n.texture);
        if (img == null) return null;
        try {
            if (n.subRect != null) img = img.getSubimage(n.subRect[0], n.subRect[1], n.subRect[2], n.subRect[3]);
        } catch (Exception ignored) { /* rect hỏng → dùng cả tấm */ }
        int w = img.getWidth(), h = img.getHeight();
        if (w <= 0 || h <= 0) return null;
        double k = Math.min(68.0 / w, 68.0 / h);
        int dw = Math.max(1, (int) Math.round(w * k)), dh = Math.max(1, (int) Math.round(h * k));
        return new ImageIcon(img.getScaledInstance(dw, dh, Image.SCALE_SMOOTH));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Inspector: hành động sửa
    // ─────────────────────────────────────────────────────────────────────

    private void applyPos(JSpinner src) {
        MapScene.Node n = curNode;
        if (syncing || n == null) return;
        pushUndoCoalesced(src);
        scene.setWorldCenter(n, dval(spX), dval(spY));
        syncing = true;
        double[] c = scene.worldCenter(n);
        lbServer.setText("(" + Math.round(c[0] * ppu) + ", " + Math.round(c[1] * ppu) + ")"
                + "   ·   local (" + fmt(n.px) + ", " + fmt(n.py) + ")");
        if (!tblPts.isEditing()) fillPoints(n);
        syncing = false;
        canvas.repaint();
        updateStatus();
    }

    private void applyScale(JSpinner src) {
        MapScene.Node n = curNode;
        if (syncing || n == null) return;
        pushUndoCoalesced(src);
        n.sx = dval(spSX);
        n.sy = dval(spSY);
        n.dTransform = true;
        canvas.repaint();
        updateStatus();
    }

    private void applyFlip(boolean x, boolean v) {
        MapScene.Node n = curNode;
        if (syncing || n == null) return;
        canvas.pushUndo();
        if (x) n.flipX = v; else n.flipY = v;
        n.dTransform = true;
        canvas.repaint();
        updateStatus();
    }

    /** Đổi sorting layer + đồng bộ m_SortingLayerID (int32 CÓ DẤU của uniqueID). */
    private void setLayerIdx(MapScene.Node n, int idx) {
        SortingLayers.Layer l = layers.byIndex(idx);
        if (l == null) return;
        n.sortLayerIdx = l.index();
        n.sortLayerId = SortingLayers.toSignedId(l.uniqueId());
        n.dSorting = true;
    }

    /** Cùng cách phân loại với canvas: layer > player, hoặc cùng layer nhưng order > 1. */
    private boolean isFront(MapScene.Node n) {
        if (n == null || !n.hasRenderer) return false;
        int pi = layers.playerIndex();
        if (n.sortLayerIdx != pi) return n.sortLayerIdx > pi;
        return n.sortOrder > PLAYER_BODY_ORDER;
    }

    /** Combo "Vị trí so với player": trước → Projectile (19), sau → Map (7). */
    private void applyFrontBack(MapScene.Node n, boolean front) {
        if (front == isFront(n)) return;
        canvas.pushUndo();
        int pi = layers.playerIndex();
        if (front) {
            SortingLayers.Layer proj = layers.byName("Projectile");
            int idx = (proj != null) ? proj.index() : Math.min(pi + 1, layers.all().size() - 1);
            if (n.sortLayerIdx <= pi) setLayerIdx(n, idx);
            if (n.sortLayerIdx == pi && n.sortOrder <= PLAYER_BODY_ORDER) {
                n.sortOrder = PLAYER_BODY_ORDER + 1;      // cùng layer player → phải hơn order thân player
                n.dSorting = true;
            }
        } else {
            SortingLayers.Layer mp = layers.byName("Map");
            int idx = (mp != null) ? mp.index() : Math.max(0, pi - 1);
            if (n.sortLayerIdx >= pi) setLayerIdx(n, idx);
        }
        afterSortChange(n);
    }

    private void nudgeOrder(int d) {
        MapScene.Node n = curNode;
        if (n == null || !n.hasRenderer) return;
        canvas.pushUndo();
        n.sortOrder += d;
        n.dSorting = true;
        afterSortChange(n);
    }

    /** Lên đầu / xuống cuối TRONG CÙNG sorting layer (order lớn nhất + 1 / nhỏ nhất − 1). */
    private void extremeOrder(boolean top) {
        MapScene.Node n = curNode;
        if (n == null || !n.hasRenderer || scene == null) return;
        int best = n.sortOrder;
        for (MapScene.Node o : scene.nodes()) {
            if (!o.hasRenderer || o.sortLayerIdx != n.sortLayerIdx) continue;
            best = top ? Math.max(best, o.sortOrder) : Math.min(best, o.sortOrder);
        }
        canvas.pushUndo();
        n.sortOrder = top ? best + 1 : best - 1;
        n.dSorting = true;
        afterSortChange(n);
    }

    private void afterSortChange(MapScene.Node n) {
        syncing = true;
        fillSorting(n);
        syncing = false;
        refreshTreeRow(n);
        canvas.repaint();
        updateStatus();
    }

    // ── Đường kẻ ──

    private void onPointEdited(int row, int col) {
        MapScene.Node n = curNode;
        if (n == null || n.colKind != MapScene.ColKind.EDGE) return;
        if (row < 0 || row >= modelPts.getRowCount() || (col != 1 && col != 2)) return;
        double x = num(modelPts.getValueAt(row, 1));
        double y = num(modelPts.getValueAt(row, 2));
        pushUndoCoalesced(tblPts);
        scene.setColliderWorldPoint(n, row, x, y);
        syncing = true;
        modelPts.setValueAt((int) Math.round(x * ppu), row, 3);
        modelPts.setValueAt((int) Math.round(y * ppu), row, 4);
        syncing = false;
        canvas.repaint();
        updateStatus();
    }

    /** Thêm đỉnh: chèn vào GIỮA đoạn sau đỉnh đang chọn (cuối danh sách → nối dài thêm 1 unit). */
    private void addVertex() {
        MapScene.Node n = curNode;
        if (n == null || n.colKind != MapScene.ColKind.EDGE) return;
        double[][] wp = scene.worldPoints(n);
        if (wp.length == 0) return;
        int at = tblPts.getSelectedRow();
        if (at < 0) at = wp.length - 1;
        double nx, ny;
        if (at < wp.length - 1) {
            nx = (wp[at][0] + wp[at + 1][0]) / 2;
            ny = (wp[at][1] + wp[at + 1][1]) / 2;
        } else {
            double dx = (wp.length >= 2) ? wp[at][0] - wp[at - 1][0] : 1;
            double dy = (wp.length >= 2) ? wp[at][1] - wp[at - 1][1] : 0;
            double len = Math.hypot(dx, dy);
            if (len < 1e-6) { dx = 1; dy = 0; len = 1; }
            nx = wp[at][0] + dx / len;
            ny = wp[at][1] + dy / len;
        }
        canvas.pushUndo();
        scene.insertColliderPoint(n, at, nx, ny);
        syncing = true;
        fillPoints(n);
        syncing = false;
        int sel = Math.min(at + 1, tblPts.getRowCount() - 1);
        if (sel >= 0) tblPts.setRowSelectionInterval(sel, sel);
        canvas.repaint();
        updateStatus();
        refreshTreeRow(n);
    }

    private void delVertex() {
        MapScene.Node n = curNode;
        if (n == null || n.colKind != MapScene.ColKind.EDGE || n.pts == null) return;
        int row = tblPts.getSelectedRow();
        if (row < 0) { msg("Chọn 1 dòng đỉnh trong bảng trước đã.", JOptionPane.INFORMATION_MESSAGE); return; }
        if (n.pts.length <= 2) {
            msg("EdgeCollider2D của Unity cần TỐI THIỂU 2 đỉnh → không xoá thêm được.", JOptionPane.WARNING_MESSAGE);
            return;
        }
        canvas.pushUndo();
        scene.removeColliderPoint(n, row);
        syncing = true;
        fillPoints(n);
        syncing = false;
        canvas.repaint();
        updateStatus();
        refreshTreeRow(n);
    }

    /** Đảo chiều đường (đổi hướng pháp tuyến — quan trọng với oneway). */
    private void reverseLine() {
        MapScene.Node n = curNode;
        if (n == null || n.colKind != MapScene.ColKind.EDGE || n.pts == null || n.pts.length < 2) return;
        canvas.pushUndo();
        double[][] p = n.pts;
        for (int i = 0, j = p.length - 1; i < j; i++, j--) { double[] t = p[i]; p[i] = p[j]; p[j] = t; }
        n.dPoints = true;
        syncing = true;
        fillPoints(n);
        syncing = false;
        canvas.repaint();
        updateStatus();
    }

    /** San phẳng Y: các đỉnh được chọn (≥ 2 dòng) hoặc TẤT CẢ đỉnh về cùng Y của đỉnh đầu tiên. */
    private void flattenY() {
        MapScene.Node n = curNode;
        if (n == null || n.colKind != MapScene.ColKind.EDGE) return;
        double[][] wp = scene.worldPoints(n);
        if (wp.length < 2) return;
        int[] rows = tblPts.getSelectedRows();
        if (rows.length < 2) {
            int ans = JOptionPane.showConfirmDialog(owner(),
                    "Chưa chọn ≥ 2 dòng → sẽ san phẳng TOÀN BỘ " + wp.length + " đỉnh của đường '" + n.name
                            + "' về cùng độ cao Y = " + fmt(wp[0][1]) + ".\nTiếp tục?",
                    "San phẳng Y", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (ans != JOptionPane.YES_OPTION) return;
            rows = new int[wp.length];
            for (int i = 0; i < wp.length; i++) rows[i] = i;
        }
        double targetY = wp[rows[0]][1];
        canvas.pushUndo();
        for (int r : rows) if (r >= 0 && r < wp.length) scene.setColliderWorldPoint(n, r, wp[r][0], targetY);
        syncing = true;
        fillPoints(n);
        syncing = false;
        canvas.repaint();
        updateStatus();
    }

    // ── Thêm / xoá HẲN 1 đường kẻ (thay đổi CẤU TRÚC prefab) ──

    /** 1 dòng trong combo "Đặt dưới phần tử": node nhóm + nhãn thụt lề theo độ sâu. */
    private static final class ParentItem {
        final MapScene.Node node;
        final String label;

        ParentItem(MapScene.Node node, String label) { this.node = node; this.label = label; }

        @Override public String toString() { return label; }
    }

    /**
     * Các node có thể làm CHA của đường mới: node "nhóm" (không có hình, không có collider) —
     * đúng chỗ Unity vẫn để Layer_Collider/Edge. Prefab bắt buộc chỉ có 1 root nên KHÔNG cho
     * chọn "không có cha" (Transform m_Father = 0 sẽ thành root thứ 2 ⇒ Unity bỏ rơi node).
     */
    private List<ParentItem> parentCandidates() {
        List<ParentItem> out = new ArrayList<>();
        if (scene == null) return out;
        for (MapScene.Node r : scene.roots()) collectParents(r, 0, out);
        if (out.isEmpty()) {                       // prefab lạ: không có node nhóm nào → lấy root
            for (MapScene.Node r : scene.roots()) out.add(new ParentItem(r, r.name));
        }
        return out;
    }

    private void collectParents(MapScene.Node n, int depth, List<ParentItem> out) {
        if (n == null || depth > 12) return;
        if (!n.hasRenderer && n.colKind == MapScene.ColKind.NONE) {
            out.add(new ParentItem(n, "  ".repeat(depth) + n.name
                    + "   (" + scene.children(n).size() + " phần tử con)"));
        }
        for (MapScene.Node c : scene.children(n)) collectParents(c, depth + 1, out);
    }

    /** Cha mặc định: cha của đường đang chọn → node tên Layer_Collider → mục đầu danh sách. */
    private ParentItem defaultParent(List<ParentItem> cands) {
        MapScene.Node p = (curNode != null) ? scene.parent(curNode) : null;
        for (ParentItem it : cands) if (it.node == p) return it;
        for (ParentItem it : cands)
            if (it.node.name != null && it.node.name.equalsIgnoreCase("Layer_Collider")) return it;
        return cands.isEmpty() ? null : cands.get(0);
    }

    /**
     * "➕ Thêm đường mới": hỏi LOẠI (đất/oneway) + TÊN + NODE CHA, rồi chuyển canvas sang chế độ
     * VẼ — người dùng click từng điểm, Enter/double-click để chốt, Esc để huỷ.
     * Đường chỉ tồn tại trong bộ nhớ (vẽ nét đứt + nhãn MỚI) cho tới khi bấm "Lưu vào prefab".
     */
    private void newLine() {
        if (scene == null) { msg("Chưa nạp map nào — hãy nạp map trước.", JOptionPane.WARNING_MESSAGE); return; }
        List<ParentItem> cands = parentCandidates();
        if (cands.isEmpty()) {
            msg("Prefab này không có phần tử nào để làm cha cho đường mới.", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JComboBox<MapScene.LineBlock> cboKind = new JComboBox<>(MapScene.LineBlock.values());
        cboKind.setPreferredSize(new Dimension(400, 30));
        cboKind.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object v, int idx,
                                                                    boolean sel, boolean foc) {
                super.getListCellRendererComponent(list, v, idx, sel, foc);
                if (v instanceof MapScene.LineBlock b) {
                    setText(b.label + "   (layer " + b.layer
                            + (b.needsEffector() ? " + effector xoay " + fmt(b.rot) + "°" : "") + ")");
                }
                return this;
            }
        });
        JTextField txtName = new JTextField("Ground_moi");
        txtName.setPreferredSize(new Dimension(400, 30));
        JComboBox<ParentItem> cboParent = new JComboBox<>(cands.toArray(new ParentItem[0]));
        cboParent.setPreferredSize(new Dimension(400, 30));
        ParentItem def = defaultParent(cands);
        if (def != null) cboParent.setSelectedItem(def);
        cboKind.addActionListener(e -> {
            String cur = txtName.getText().trim();
            if (cur.isEmpty() || cur.equals("Ground_moi") || cur.equals("Oneway_moi"))
                txtName.setText(cboKind.getSelectedIndex() == 0 ? "Ground_moi" : "Oneway_moi");
        });

        JPanel g = grid();
        g.setBackground(Theme.BG_SURFACE);
        GridBagConstraints c = gbc();
        int y = 0;
        addRow(g, c, y++, "Kiểu chặn", cboKind);
        addRow(g, c, y++, "Tên GameObject", txtName);
        addRow(g, c, y++, "Đặt dưới phần tử", cboParent);
        JLabel note = new JLabel("<html>Bấm OK rồi <b>click từng điểm</b> trên canvas để vẽ đường"
                + " (tối thiểu 2 điểm).<br><b>Enter</b> hoặc <b>double-click</b> = xong ·"
                + " Backspace = bỏ điểm cuối · Esc = huỷ.<br>Đường mới chỉ ghi vào file .prefab khi"
                + " bạn bấm <b>Lưu vào prefab</b>.<br>Kiểu chặn đổi lại được bất cứ lúc nào ở khối"
                + " “Đường kẻ” bên phải.</html>");
        note.setFont(Theme.font(12, Font.PLAIN));
        note.setForeground(Theme.TEXT_MUTED);
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.weightx = 1;
        g.add(note, c);

        int ans = JOptionPane.showConfirmDialog(owner(), g, "Thêm đường kẻ mới",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) return;

        Object pi = cboParent.getSelectedItem();
        if (!(pi instanceof ParentItem item)) return;
        MapScene.LineBlock block = (cboKind.getSelectedItem() instanceof MapScene.LineBlock b)
                ? b : MapScene.LineBlock.SOLID;
        boolean oneway = block.needsEffector();
        String nm = safeName(txtName.getText(), oneway);
        MapScene.LineKind kind = oneway ? MapScene.LineKind.ONEWAY : MapScene.LineKind.GROUND;

        // Nút đổi nhãn để thấy rõ app ĐANG CHỜ mình click trên canvas (trước đây chỉ mờ đi).
        btnNewLine.setEnabled(false);
        btnNewLine.setText("Đang vẽ… Enter = xong");
        Theme.lockH(btnNewLine, 32);
        lbHint.setText("VẼ ĐƯỜNG MỚI '" + nm + "': click từng điểm trên canvas · "
                + "Enter hoặc double-click = xong · Backspace = bỏ điểm cuối · Esc = huỷ");
        lbHint.setForeground(Theme.ACCENT);
        canvas.setDrawLineMode(true, pts -> finishNewLine(pts, nm, kind, block, item.node));
    }

    /**
     * Tên GameObject chỉ nên là ASCII (Unity ghi thẳng vào m_Name, dấu tiếng Việt làm YAML phải
     * quote và khó tra cứu) → thay mọi ký tự lạ bằng "_".
     */
    private static String safeName(String raw, boolean oneway) {
        String s = (raw == null) ? "" : raw.trim();
        s = s.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        while (s.startsWith("_")) s = s.substring(1);
        if (s.isEmpty()) s = oneway ? "Oneway_moi" : "Ground_moi";
        return s;
    }

    /** Canvas vẽ xong ({@code worldPts != null}) hoặc người dùng huỷ ({@code worldPts == null}). */
    private void finishNewLine(double[][] worldPts, String name, MapScene.LineKind kind,
                               MapScene.LineBlock block, MapScene.Node parent) {
        lbHint.setText(MOUSE_HINT);
        lbHint.setForeground(Theme.TEXT_DIM);
        btnNewLine.setText("Thêm đường kẻ");
        Theme.lockH(btnNewLine, 32);
        if (scene == null || worldPts == null) {        // huỷ (Esc / đổi chế độ giữa chừng)
            updateInspector(curNode);
            updateStatus();
            return;
        }
        canvas.pushUndo();                              // PHẢI trước khi đổi cấu trúc
        MapScene.Node n = scene.addEdgeLine(parent, name, kind, worldPts);
        if (n == null) {
            msg("Không tạo được đường mới (cần tối thiểu 2 đỉnh hợp lệ).", JOptionPane.ERROR_MESSAGE);
            updateStatus();
            return;
        }
        scene.setLineBlock(n, block);                   // chiều chặn (node mới ⇒ ghi thẳng lúc chèn)
        rebuildTree();
        canvas.setSelected(n);
        onCanvasSelect(n);
        canvas.repaint();
        updateStatus();
        lbStatus.setText("Đã thêm đường '" + n.name + "' (" + worldPts.length + " đỉnh · "
                + block.label + ") dưới '" + (parent != null ? parent.name : "?")
                + "' — CHƯA ghi vào file, bấm \"Lưu vào prefab\" để ghi (Ctrl+Z để bỏ).");
        lbStatus.setForeground(Theme.ACCENT);
    }

    /**
     * "🗑 Xoá đường này": gỡ hẳn GameObject của đường kẻ đang chọn khỏi prefab.
     * {@link MapScene#whyCannotRemove} quét THẬT tham chiếu trong file nên biên map Top/Bottom/
     * Left/Right hay lớp nền bị MapManager giữ sẽ bị chặn kèm lý do cụ thể.
     */
    private void deleteLine() {
        MapScene.Node n = curNode;
        if (scene == null || n == null || n.colKind != MapScene.ColKind.EDGE) {
            msg("Hãy chọn 1 đường kẻ (EdgeCollider2D) trên canvas hoặc trong cây trước đã.",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String why = scene.whyCannotRemove(n);
        if (why != null) {
            msg("KHÔNG xoá được đường '" + n.name + "':\n\n" + why, JOptionPane.WARNING_MESSAGE);
            return;
        }
        int kids = scene.children(n).size();
        int ans = JOptionPane.showConfirmDialog(owner(),
                "Xoá đường kẻ '" + n.name + "' (" + (n.pts == null ? 0 : n.pts.length) + " đỉnh"
                        + (n.hasPlatformEffector ? ", oneway" : "")
                        + (kids > 0 ? ", kèm " + kids + " phần tử con" : "") + ")?\n\n"
                        + "Chỉ ghi vào prefab khi bạn bấm \"💾 Lưu vào prefab\" (có backup trước).\n"
                        + "Bấm Ctrl+Z là lấy lại được ngay nếu xoá nhầm.",
                "Xoá đường kẻ", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans != JOptionPane.YES_OPTION) return;

        canvas.pushUndo();
        if (!scene.removeNode(n)) {
            msg("Xoá thất bại — node không còn trong scene hoặc đang bị tham chiếu.",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        curNode = null;
        canvas.setSelected(null);
        rebuildTree();
        updateInspector(null);
        canvas.repaint();
        updateStatus();
        lbStatus.setText("Đã xoá đường '" + n.name + "' khỏi scene — CHƯA ghi vào file,"
                + " bấm \"💾 Lưu vào prefab\" để ghi (Ctrl+Z để lấy lại).");
        lbStatus.setForeground(Theme.ACCENT);
    }

    // ── Đổi ảnh / material ──

    /**
     * Chọn node đầu tiên có tên chứa {@code part} (không phân biệt hoa thường) — cho tự kiểm/chụp
     * màn hình khỏi phải click tay. Trả tên node đã chọn, null nếu không thấy.
     */
    public String selectNodeByName(String part) {
        if (scene == null || part == null) return null;
        for (MapScene.Node n : scene.nodes()) {
            if (n.name != null && n.name.toLowerCase(java.util.Locale.ROOT)
                    .contains(part.toLowerCase(java.util.Locale.ROOT))) {
                canvas.setSelected(n);
                updateInspector(n);
                return n.name;
            }
        }
        return null;
    }

    // ─── Đổi skeleton của node Spine ────────────────────────────────────────
    /** Danh mục skeleton dựng LAZY từ guid index (333 cái) — dựng 1 lần rồi dùng lại. */
    private com.apex.maptool.unity.SpineCatalog spineCatalog;

    private com.apex.maptool.unity.SpineCatalog catalog() {
        if (spineCatalog == null) {
            try {
                ensureGuidIndex();
            } catch (Exception e) {
                System.err.println("[MapLayout] dựng guid index cho danh mục skeleton fail: " + e.getMessage());
            }
            spineCatalog = new com.apex.maptool.unity.SpineCatalog(guidIndex, cfg.assetsRoot());
            System.out.println("[MapLayout] danh mục skeleton: " + spineCatalog.size());
        }
        return spineCatalog;
    }

    /**
     * Hộp thoại chọn skeleton mới cho node Spine: ô tìm + danh sách + XEM TRƯỚC.
     *
     * <p>Có xem trước vì chọn cây/hiệu ứng bằng cái tên như {@code 1004} hay {@code Layer19} thì
     * không ai đoán nổi đó là hình gì — chọn mù rồi phải mở Unity kiểm là mất cả buổi.
     */
    private void chooseSkeleton(MapScene.Node n, MapScene.Fx f) {
        var sel = pickSkeleton("Đổi skeleton — '" + n.name + "' (đang dùng: "
                + (f.name == null ? "?" : f.name) + ")", f.assetGuid);
        if (sel == null || sel.skeletonGuid.equalsIgnoreCase(f.assetGuid)) return;

        String err = scene.setSkeleton(n, sel);
        if (err != null) { msg("Không đổi được: " + err, JOptionPane.ERROR_MESSAGE); return; }
        // Cache skeleton của canvas khoá theo THƯ MỤC nên đổi sang thư mục khác là tự nạp cái mới —
        // không phải xoá cache, và skeleton cũ vẫn còn đó cho node khác đang dùng.
        canvas.repaint();
        updateInspector(n);
        lbStatus.setText("Đã đổi skeleton của '" + n.name + "' → '" + sel.name + "'"
                + (sel.materialGuid == null ? " (KHÔNG có material — kiểm lại trong Unity)" : "")
                + " — CHƯA ghi vào file, bấm 💾 Lưu vào prefab.");
    }

    /**
     * THÊM node Spine mới vào map: chọn skeleton → đặt vào GIỮA khung nhìn, làm con của node đang
     * chọn (không chọn gì thì làm con của root).
     *
     * <p>Đặt ở giữa khung nhìn thay vì bắt click toạ độ: kéo thả sẵn đã chạy tốt, thêm một chế độ
     * "click để đặt" chỉ tổ thêm trạng thái mà không tiện hơn.
     */
    private void addSpineNode() {
        if (scene == null) { msg("Chưa nạp map nào.", JOptionPane.WARNING_MESSAGE); return; }
        MapScene.Node parent = canvas.selected();
        if (parent == null || parent.trAnchor == 0) {
            for (MapScene.Node n : scene.nodes()) if (n.parentTr == 0) { parent = n; break; }
        }
        if (parent == null) { msg("Không tìm được node cha trong map.", JOptionPane.ERROR_MESSAGE); return; }

        var sel = pickSkeleton("Thêm Spine mới — đặt vào nhóm '" + parent.name + "'", null);
        if (sel == null) return;

        // Cỡ mặc định: cho skeleton cao khoảng 2 unit. Để scale 1 thì cây có thể cao vài chục unit
        // (tuỳ assetScale của từng bộ) — người dùng mở ra thấy một khối khổng lồ, không hiểu vì sao.
        double scale = 1;
        String animName = "";
        try {
            var sc = com.apex.maptool.spine.SpineCharacter.load(sel.folder);
            if (sc != null) {
                double hUnit = sc.worldHeight();
                if (hUnit > 0.0001) scale = 2.0 / hUnit;
                var first = sc.data.animations.keySet().stream().findFirst().orElse(null);
                if (first != null) animName = first;
            }
        } catch (Exception ignored) { }

        double[] c = canvas.viewCenterWorld();
        MapScene.Node added = scene.addSpineNode(parent, null, sel, animName, c[0], c[1], scale);
        if (added == null) {
            msg("Không thêm được node Spine (thiếu cha hợp lệ hoặc skeleton lỗi).", JOptionPane.ERROR_MESSAGE);
            return;
        }
        afterStructuralChange();
        canvas.setSelected(added);
        updateInspector(added);
        lbStatus.setText("Đã thêm '" + added.name + "' vào '" + parent.name + "'"
                + " · anim='" + animName + "' · cỡ " + String.format(java.util.Locale.ROOT, "%.3f", scale)
                + " — kéo trên canvas để đặt đúng chỗ, rồi 💾 Lưu vào prefab.");
    }

    /** Hộp thoại chọn skeleton dùng chung cho "đổi" và "thêm mới". null = người dùng huỷ. */
    private com.apex.maptool.unity.SpineCatalog.Entry pickSkeleton(String title, String currentGuid) {
        var cat = catalog();
        if (cat.size() == 0) { msg("Không tìm thấy skeleton nào trong client.", JOptionPane.WARNING_MESSAGE); return null; }

        DefaultListModel<com.apex.maptool.unity.SpineCatalog.Entry> model = new DefaultListModel<>();
        JList<com.apex.maptool.unity.SpineCatalog.Entry> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(18);

        JLabel preview = new JLabel("", SwingConstants.CENTER);
        preview.setPreferredSize(new Dimension(260, 260));
        preview.setOpaque(true);
        preview.setBackground(new Color(0x2A2A33));
        preview.setForeground(Theme.TEXT_DIM);
        JLabel info = descLabel(" ");

        JTextField search = new JTextField();
        Runnable refill = () -> {
            model.clear();
            for (var e : cat.search(search.getText())) model.addElement(e);
            if (!model.isEmpty()) list.setSelectedIndex(0);
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refill.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refill.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refill.run(); }
        });
        list.addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            var sel = list.getSelectedValue();
            preview.setIcon(null);
            if (sel == null) { preview.setText(""); info.setText(" "); return; }
            info.setText("<html>" + sel.shortPath + (sel.materialGuid == null
                    ? "<br><font color='#f0b429'>⚠ không có *_Material.mat cạnh skeleton</font>" : "") + "</html>");
            java.awt.image.BufferedImage img = renderSkeletonPreview(sel, 256, 256);
            if (img != null) { preview.setIcon(new ImageIcon(img)); preview.setText(""); }
            else preview.setText("(không vẽ được)");
        });
        refill.run();
        // đang dùng cái nào thì chọn sẵn cái đó
        var cur = cat.byGuid(currentGuid);
        if (cur != null) list.setSelectedValue(cur, true);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(search, BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(430, 380));
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(preview, BorderLayout.NORTH);
        right.add(info, BorderLayout.CENTER);

        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.add(left, BorderLayout.CENTER);
        body.add(right, BorderLayout.EAST);

        int ok = JOptionPane.showConfirmDialog(this, body, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return (ok == JOptionPane.OK_OPTION) ? list.getSelectedValue() : null;
    }

    /**
     * Vẽ thử 1 skeleton ra ảnh cho hộp thoại chọn. null nếu không nạp được.
     *
     * <p><b>Canh khung theo HỘP BAO HÌNH THẬT</b> ({@code SpineRenderer.worldBounds}), KHÔNG theo
     * {@code skeleton.width/height} của file: rất nhiều skeleton không khai hai số đó, {@link
     * com.apex.maptool.spine.SpineData} để mặc định 100×100, canh theo đó là hình phóng sai cỡ rồi
     * bay ra ngoài khung — người dùng thấy ảnh cụt/vỡ hoặc ô trống.
     *
     * <p>Và phải DÒ NHIỀU MỐC THỜI GIAN: khối hiệu ứng thường tắt hết attachment ở giây 0 nên hộp
     * bao rỗng; lấy mốc đầu tiên vẽ ra thứ gì đó.
     */
    private java.awt.image.BufferedImage renderSkeletonPreview(
            com.apex.maptool.unity.SpineCatalog.Entry e, int w, int h) {
        try {
            if (!e.drawable()) return null;
            var sc = com.apex.maptool.spine.SpineCharacter.load(e.folder);
            if (sc == null) return null;

            var anim = sc.data.animations.values().stream().findFirst().orElse(null);
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new Color(0x2A2A33));
            g.fillRect(0, 0, w, h);
            sc.renderFit(g, w, h, 14, anim);
            g.dispose();
            return img;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Chọn file ảnh mới trong Assets của client → lấy guid từ file {@code .meta} bên cạnh.
     * SpriteRenderer nhận .png; MeshRenderer (lớp nền) nhận .mat vì prefab lưu guid MATERIAL.
     */
    private void changeTexture() {
        MapScene.Node n = curNode;
        if (n == null || !n.hasRenderer) return;
        boolean mesh = !n.isSprite;
        Path assets = cfg.assetsRoot().toAbsolutePath().normalize();
        Path start = (n.texture != null && Files.exists(n.texture)) ? n.texture.getParent() : assets;

        JFileChooser fc = new JFileChooser(start != null ? start.toFile() : null);
        fc.setDialogTitle(mesh ? "Chọn material nền (.mat) trong Assets client"
                : "Chọn ảnh (.png) trong Assets client");
        fc.setFileFilter(mesh ? new FileNameExtensionFilter("Material Unity (*.mat)", "mat")
                : new FileNameExtensionFilter("Ảnh PNG (*.png)", "png"));
        if (fc.showOpenDialog(owner()) != JFileChooser.APPROVE_OPTION) return;

        Path f = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!f.startsWith(assets)) {
            msg("File phải nằm TRONG thư mục Assets của client:\n" + assets, JOptionPane.ERROR_MESSAGE);
            return;
        }
        Path meta = f.resolveSibling(f.getFileName() + ".meta");
        String guid = readGuid(meta);
        if (guid == null) {
            msg("Không đọc được guid trong file .meta:\n" + meta, JOptionPane.ERROR_MESSAGE);
            return;
        }
        canvas.pushUndo();
        n.texGuid = guid;
        n.dTexture = true;
        if (mesh) {
            n.texture = (matResolver != null) ? matResolver.resolveTexture(guid) : null;
        } else {
            n.texture = f;
            n.subRect = null;        // ảnh mới = nguyên tấm
            n.spriteFileId = 0;      // writer sẽ ghi fileID sprite đơn 21300000
            n.texMissing = false;
            double[] pv = readPivot(meta);
            if (pv != null) { n.pivotX = pv[0]; n.pivotY = pv[1]; }
            int[] sz = texCache.size(f);
            if (sz != null && sz.length >= 2 && sz[0] > 0 && n.drawMode == 0) {
                n.baseW = sz[0] / (double) ppu;    // drawMode Simple → kích thước lấy từ PNG
                n.baseH = sz[1] / (double) ppu;
            }
        }
        updateInspector(n);
        canvas.repaint();
        updateStatus();
    }

    private static String readGuid(Path meta) {
        try {
            if (!Files.exists(meta)) return null;
            for (String l : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
                Matcher m = GUID_PAT.matcher(l);
                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            System.err.println("[MapLayout] đọc .meta lỗi: " + e.getMessage());
        }
        return null;
    }

    private static double[] readPivot(Path meta) {
        try {
            if (!Files.exists(meta)) return null;
            for (String l : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
                Matcher m = PIVOT_PAT.matcher(l);
                if (m.find()) return new double[]{Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2))};
            }
        } catch (Exception ignored) { /* không có pivot → giữ pivot cũ */ }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lưu / xuất / nạp lại
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Thư mục backup: LUÔN là {@code <thư mục tool>/backup/prefab}.
     * Không dùng thẳng CWD vì nếu chạy jar từ chỗ khác (double-click trong target/, shortcut
     * đặt "Start in" khác) thì bản backup của file client sẽ rơi vào chỗ không ai ngờ tới.
     * Ưu tiên thư mục cha của jar (tool root), rồi cạnh jar, cuối cùng mới tới CWD.
     */
    private Path backupDir() {
        Path root = null;
        try {
            Path p = Paths.get(MapLayoutEditorFrame.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path jarDir = Files.isDirectory(p) ? p : p.getParent();   // vd <tool>/target
            if (jarDir != null) {
                // chạy từ target/map-editor.jar hoặc target/classes → lùi lên tool root
                Path up = jarDir.getFileName() != null
                        && jarDir.getFileName().toString().equals("classes")
                        ? jarDir.getParent() : jarDir;
                if (up != null && up.getFileName() != null
                        && up.getFileName().toString().equals("target")) {
                    root = up.getParent();
                } else {
                    root = up;
                }
            }
        } catch (Exception ignore) {
            // không xác định được vị trí jar → rơi về CWD phía dưới
        }
        if (root == null) root = Paths.get("");
        return root.resolve("backup").resolve("prefab").toAbsolutePath().normalize();
    }

    /** Số phần tử đang chờ ghi = node có cờ dirty + node đang chờ XOÁ khỏi file. */
    private int dirtyCount() {
        if (scene == null) return 0;
        int c = 0;
        for (MapScene.Node n : scene.nodes()) if (n.isDirty()) c++;
        return c + scene.deletedNodes().size();
    }

    /** Số đường kẻ MỚI thêm trong phiên (chưa có block trong file). */
    private int newLineCount() {
        if (scene == null) return 0;
        int c = 0;
        for (MapScene.Node n : scene.nodes()) if (n.dNew) c++;
        return c;
    }

    /**
     * Dòng tóm tắt thay đổi BIÊN MAP (null nếu không đụng thanh nào). Tách riêng vì 4 thanh này
     * quyết định vùng camera của cả map — người dùng phải thấy con số cũ → mới trước khi ghi đè.
     */
    private String boundsChangeText() {
        if (scene == null) return null;
        boolean any = false;
        for (String w : new String[]{"left", "right", "top", "bottom"}) {
            MapScene.Node e = scene.boundsEdge(w);
            if (e != null && e.dTransform) { any = true; break; }
        }
        if (!any) return null;
        double[] b = scene.cameraBounds();
        if (b == null) return "• BIÊN MAP: đã dời thanh biên (map thiếu thanh nên chưa tính được khung camera).";
        StringBuilder sb = new StringBuilder("• BIÊN MAP / VÙNG CAMERA: ");
        if (camOrig != null) {
            sb.append("trái ").append(fmt(camOrig[0])).append(" → ").append(fmt(b[0]))
              .append("  ·  phải ").append(fmt(camOrig[2])).append(" → ").append(fmt(b[2]))
              .append("  ·  dưới ").append(fmt(camOrig[1])).append(" → ").append(fmt(b[1]))
              .append("  ·  trên ").append(fmt(camOrig[3])).append(" → ").append(fmt(b[3]));
        } else {
            sb.append("trái ").append(fmt(b[0])).append(" · phải ").append(fmt(b[2]))
              .append(" · dưới ").append(fmt(b[1])).append(" · trên ").append(fmt(b[3]));
        }
        double w = b[2] - b[0], h = b[3] - b[1];
        sb.append("\n    khung mới: ").append(fmt(w)).append(" × ").append(fmt(h))
          .append(" unit (server ").append(Math.round(w * ppu)).append(" × ")
          .append(Math.round(h * ppu)).append(")");
        if (w < CAM_MIN_W) sb.append("\n    ⛔ HẸP hơn màn hình 20:9 (cần ≥ ").append(fmt(CAM_MIN_W))
                .append(" unit) — camera sẽ giật hai bên!");
        if (h < CAM_MIN_H) sb.append("\n    ⛔ THẤP hơn khung camera (cần ≥ ").append(fmt(CAM_MIN_H))
                .append(" unit) — camera sẽ giật theo chiều dọc!");
        sb.append("\n    ⚠ Kiểm tra lại toạ độ quái / NPC / cổng trong DB có còn nằm trong biên mới không.");
        return sb.toString();
    }

    /** Liệt kê thay đổi ĐANG CHỜ (đọc cờ dirty, KHÔNG đụng vào PrefabDocument). */
    private List<String> changeSummary() {
        List<String> out = new ArrayList<>();
        if (scene == null) return out;
        // Biên map đứng RIÊNG một dòng đầu: nó là thứ đổi vùng camera của cả map, không phải 1 node lẻ.
        String cam = boundsChangeText();
        if (cam != null) out.add(cam);
        for (MapScene.Node n : scene.nodes()) {
            if (!n.isDirty()) continue;
            boolean isEdge = scene.isBoundsEdge(n);
            StringBuilder sb = new StringBuilder("• ").append(isEdge ? "[BIÊN MAP] " : "")
                    .append(n.name).append(": ");
            List<String> parts = new ArrayList<>();
            if (n.dNew) {
                MapScene.Node p = scene.parent(n);
                parts.add("THÊM ĐƯỜNG MỚI "
                        + (n.hasPlatformEffector || n.physLayer == 19 ? "ONEWAY (layer 19 + PlatformEffector2D)"
                                                                     : "ĐẤT (layer 6)")
                        + " · " + (n.pts == null ? 0 : n.pts.length) + " đỉnh · dưới '"
                        + (p != null ? p.name : "gốc prefab") + "'");
            }
            if (n.dTransform) parts.add("vị trí/scale/xoay → local (" + fmt(n.px) + ", " + fmt(n.py)
                    + ") scale (" + fmt(n.sx) + ", " + fmt(n.sy) + ") góc " + fmt(n.rotDeg) + "°"
                    + (n.flipX ? " lậtX" : "") + (n.flipY ? " lậtY" : ""));
            if (n.dSorting) {
                SortingLayers.Layer l = layers.byIndex(n.sortLayerIdx);
                parts.add("thứ tự vẽ → layer " + n.sortLayerIdx + " (" + (l != null ? l.name() : "?") + ", "
                        + (isFront(n) ? "TRƯỚC player" : "sau player") + ") order " + n.sortOrder);
            }
            if (n.dActive) parts.add(n.active ? "BẬT phần tử" : "TẮT phần tử");
            if (n.dRendEnabled) parts.add(n.rendEnabled ? "bật renderer" : "tắt renderer");
            if (n.dPoints && !n.dNew) parts.add("đường kẻ → " + (n.pts == null ? 0 : n.pts.length) + " đỉnh");
            if (n.dTexture) parts.add("đổi ảnh → " + (n.texGuid == null ? "(rỗng)" : n.texGuid.substring(0, 8) + "…"));
            if (n.dFx) {
                List<String> ch = fxChanges(n);
                parts.add("hiệu ứng " + kindShort(n.fx) + " → "
                        + (ch.isEmpty() ? "(đã sửa tham số)" : String.join(", ", ch)));
            }
            out.add(sb.append(String.join(" · ", parts)).toString());
        }
        // node đã gỡ khỏi scene, đang chờ writer xoá block trong file
        for (MapScene.Node n : scene.deletedNodes()) {
            out.add("• " + n.name + ": XOÁ HẲN khỏi prefab (GameObject + Transform + collider"
                    + (n.hasPlatformEffector ? " + PlatformEffector2D" : "")
                    + " · gỡ luôn khỏi m_Children của cha)");
        }
        return out;
    }

    private void doSave() {
        if (scene == null) { msg("Chưa nạp map nào.", JOptionPane.WARNING_MESSAGE); return; }
        List<String> chg = changeSummary();
        if (chg.isEmpty()) {
            msg("Không có thay đổi nào để lưu.", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int added = newLineCount();
        int removed = scene.deletedNodes().size();
        String struct = (added == 0 && removed == 0) ? ""
                : "<br><br><b>Thay đổi CẤU TRÚC: thêm " + added + " đường · xoá " + removed + " đường.</b>"
                  + "<br>⚠ Đường đi của quái/bot/boss chạy theo mask bên SERVER (mask/map_"
                  + scene.mapId() + ".bin) — sửa đường bên client xong nhớ đồng bộ lại mask server.";
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);
        JLabel warn = new JLabel("<html><b>Sẽ GHI ĐÈ file prefab trong client:</b><br>"
                + scene.prefab().toAbsolutePath() + "<br><br>"
                + "Bản gốc được backup trước vào:<br>" + backupDir()
                + struct
                + "<br><br>Danh sách " + chg.size() + " phần tử thay đổi:</html>");
        warn.setForeground(Theme.ACCENT);
        warn.setFont(Theme.font(12, Font.PLAIN));
        p.add(warn, BorderLayout.NORTH);
        p.add(textArea(String.join("\n", chg), 700, 300), BorderLayout.CENTER);

        int ans = JOptionPane.showConfirmDialog(owner(), p, "Xác nhận ghi đè prefab",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans != JOptionPane.YES_OPTION) return;

        root.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        try {
            MapSceneWriter.Result r = MapSceneWriter.save(scene, layers, backupDir());
            StringBuilder sb = new StringBuilder();
            sb.append("Đã ghi ").append(r.fieldsWritten).append(" field trên ")
              .append(r.nodesWritten).append(" phần tử.\n");
            if (r.backup != null) sb.append("Backup: ").append(r.backup.toAbsolutePath()).append('\n');
            else sb.append("Không tạo backup (file không đổi).\n");
            if (r.warnings > 0) sb.append("Cảnh báo: ").append(r.warnings).append('\n');
            if (!r.ok()) sb.append("LỖI ở ").append(r.failed.size())
                    .append(" phần tử — các phần tử này VẪN còn cờ chưa lưu.\n");
            sb.append('\n').append(String.join("\n", r.log));

            JPanel res = new JPanel(new BorderLayout(0, 8));
            res.setOpaque(false);
            JLabel head = new JLabel(r.ok()
                    ? "<html><b>Lưu xong.</b> Mở lại Unity để thấy thay đổi (Reimport nếu cần).</html>"
                    : "<html><b>Lưu xong nhưng có lỗi</b> — xem nhật ký bên dưới.</html>");
            head.setForeground(r.ok() ? Theme.GREEN : Theme.RED);
            res.add(head, BorderLayout.NORTH);
            res.add(textArea(sb.toString(), 720, 340), BorderLayout.CENTER);
            JOptionPane.showMessageDialog(owner(), res, "Kết quả lưu prefab",
                    r.ok() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        } catch (Exception e) {
            msg("Ghi prefab thất bại:\n" + rootMsg(e), JOptionPane.ERROR_MESSAGE);
        } finally {
            root.setCursor(Cursor.getDefaultCursor());
            snapshotFx();          // giá trị vừa ghi trở thành "mặc định" mới
            updateStatus();
            tree.repaint();
            canvas.repaint();      // cờ dNew đã tắt → đường mới thôi vẽ nét đứt "MỚI"
        }
    }

    /** Xuất toàn bộ bố cục ra JSON (chỉ để đối chiếu/khôi phục tay — không phải file Unity đọc được). */
    private void doExportJson() {
        if (scene == null) { msg("Chưa nạp map nào.", JOptionPane.WARNING_MESSAGE); return; }
        try {
            JsonObject o = new JsonObject();
            o.addProperty("mapId", scene.mapId());
            o.addProperty("prefab", String.valueOf(scene.prefab()));
            o.addProperty("exportedAt", LocalDateTime.now().toString());
            o.addProperty("playerSortingLayer", layers.playerIndex());
            double[] cb = scene.cameraBounds();
            if (cb != null) {
                JsonObject jb = new JsonObject();
                jb.addProperty("left", cb[0]);
                jb.addProperty("bottom", cb[1]);
                jb.addProperty("right", cb[2]);
                jb.addProperty("top", cb[3]);
                jb.addProperty("width", cb[2] - cb[0]);
                jb.addProperty("height", cb[3] - cb[1]);
                o.add("cameraBounds", jb);
            }
            JsonArray arr = new JsonArray();
            for (MapScene.Node n : scene.nodes()) {
                JsonObject j = new JsonObject();
                j.addProperty("go", n.goAnchor);
                j.addProperty("tr", n.trAnchor);
                j.addProperty("name", n.name);
                j.addProperty("active", n.active);
                j.addProperty("physLayer", n.physLayer);
                j.addProperty("px", n.px);
                j.addProperty("py", n.py);
                j.addProperty("sx", n.sx);
                j.addProperty("sy", n.sy);
                j.addProperty("rotDeg", n.rotDeg);
                if (n.hasRenderer) {
                    j.addProperty("renderer", n.isSprite ? "sprite" : "mesh");
                    j.addProperty("enabled", n.rendEnabled);
                    j.addProperty("flipX", n.flipX);
                    j.addProperty("flipY", n.flipY);
                    j.addProperty("sortLayer", n.sortLayerIdx);
                    j.addProperty("sortOrder", n.sortOrder);
                    j.addProperty("frontOfPlayer", isFront(n));
                    if (n.texGuid != null) j.addProperty("texGuid", n.texGuid);
                }
                if (n.colKind != MapScene.ColKind.NONE) {
                    j.addProperty("collider", n.colKind.name());
                    j.addProperty("oneway", n.hasPlatformEffector);
                    if (n.pts != null) {
                        JsonArray pa = new JsonArray();
                        for (double[] pt : n.pts) {
                            JsonArray e = new JsonArray();
                            e.add(pt[0]);
                            e.add(pt[1]);
                            pa.add(e);
                        }
                        j.add("points", pa);
                    }
                }
                if (!n.fxList.isEmpty()) {
                    JsonArray fa = new JsonArray();
                    for (MapScene.Fx f : n.fxList) {
                        JsonObject jf = new JsonObject();
                        jf.addProperty("kind", f.kind.name());
                        jf.addProperty("name", f.name);
                        if (f.assetGuid != null) jf.addProperty("assetGuid", f.assetGuid);
                        JsonObject jp = new JsonObject();
                        for (Map.Entry<String, String> e : f.params.entrySet()) jp.addProperty(e.getKey(), e.getValue());
                        jf.add("params", jp);
                        fa.add(jf);
                    }
                    j.add("effects", fa);
                }
                arr.add(j);
            }
            o.add("nodes", arr);

            Path dir = backupDir();
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path out = dir.resolve("Map_" + scene.mapId() + ".layout." + stamp + ".json");
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.write(out, gson.toJson(o).getBytes(StandardCharsets.UTF_8));
            msg("Đã xuất JSON:\n" + out, JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            msg("Xuất JSON thất bại:\n" + rootMsg(e), JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Nạp lại prefab từ đĩa → bỏ mọi thay đổi chưa lưu. */
    private void doRevertAll() {
        if (scene == null) { msg("Chưa nạp map nào.", JOptionPane.WARNING_MESSAGE); return; }
        int d = dirtyCount();
        int ans = JOptionPane.showConfirmDialog(owner(),
                (d == 0 ? "Không có thay đổi nào, vẫn nạp lại map từ đĩa?"
                        : "Bỏ TẤT CẢ " + d + " thay đổi chưa lưu và nạp lại map " + curMapId + " từ đĩa?"),
                "Hoàn tác tất cả", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans != JOptionPane.YES_OPTION) return;
        scene = null;                 // bỏ qua cảnh báo "còn thay đổi" ở loadMap
        canvas.setScene(null);
        loadMap(curMapId > 0 ? curMapId : selectedMapId());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Status + phím tắt
    // ─────────────────────────────────────────────────────────────────────

    private void updateStatus() {
        if (scene == null) {
            chNodes.setText("— phần tử");
            chRend.setText("— renderer");
            chLines.setText("— đường kẻ");
            chBox.setText("— vùng box");
            chFx.setText("— hiệu ứng");
            chDirty.setText("chưa nạp map");
            chDirty.setTint(null);
            lbTreeCount.setText("chưa nạp map");
            lbWarnNested.setVisible(false);
            if (btnUndo != null) btnUndo.setEnabled(false);
            if (miRedo != null) miRedo.setEnabled(false);
            pushShell();
            return;
        }
        int rend = 0, edge = 0, box = 0, dirty = 0;
        Map<Integer, Integer> byLayer = new LinkedHashMap<>();
        for (MapScene.Node n : scene.nodes()) {
            if (n.hasRenderer) {
                rend++;
                byLayer.merge(n.sortLayerIdx, 1, Integer::sum);
            }
            if (n.colKind == MapScene.ColKind.EDGE) edge++;
            else if (n.colKind == MapScene.ColKind.BOX) box++;
            if (n.isDirty()) dirty++;
        }
        int added = newLineCount();
        int del = scene.deletedNodes().size();
        chNodes.setText(scene.nodes().size() + " phần tử");
        chRend.setText(rend + " renderer");
        chLines.setText(edge + " đường kẻ");
        chBox.setText(box + " vùng box");
        chFx.setText(scene.effectNodes().size() + " hiệu ứng");
        chFx.setToolTipText(fxSummaryText().replaceFirst("^\\s*·\\s*", ""));
        lbZoneCount.setText(edge + " đường · " + box + " box");

        boolean clean = (dirty == 0 && del == 0 && added == 0);
        String d = clean ? "chưa sửa gì"
                : dirty + " sửa chưa lưu" + (added > 0 ? " · +" + added + " đường mới" : "")
                  + (del > 0 ? " · " + del + " chờ xoá" : "");
        chDirty.setText(d);
        chDirty.setTint(clean ? null : Theme.ACCENT);

        String w = "";
        if (scene.nestedPrefabCount() > 0) w = scene.nestedPrefabCount() + " nested prefab";
        if (scene.strippedCount() > 0) w += (w.isEmpty() ? "" : " · ") + scene.strippedCount() + " block stripped";
        lbWarnNested.setVisible(!w.isEmpty());
        if (!w.isEmpty()) {
            lbWarnNested.setText(w);
            lbWarnNested.setToolTipText("Nested prefab không hiển thị / không sửa được ở đây");
        }
        for (Map.Entry<Integer, JLabel> e : layerCountLabels.entrySet())
            e.getValue().setText(String.valueOf(byLayer.getOrDefault(e.getKey(), 0)));

        if (btnUndo != null) {
            btnUndo.setEnabled(canvas.canUndo());
            btnUndo.setText("Hoàn tác (" + canvas.undoDepth() + " bước)");
        }
        if (miRedo != null) miRedo.setEnabled(canvas.canRedo());
        if (btnSave != null) btnSave.setEnabled(true);
        if (chNodes.getParent() != null) { chNodes.getParent().revalidate(); chNodes.getParent().repaint(); }
        pushShell();
    }

    /** Vỏ app (top bar): ngữ cảnh "Map 1 — Làng Aru" + chip trạng thái sửa. */
    public void setShell(MainFrame.Shell s) { shell = s; }

    public void pushShell() {
        if (shell == null) return;
        String ctx = (scene == null) ? "chưa nạp map"
                : "Map " + scene.mapId() + " — " + mapName(scene.mapId());
        boolean clean = (scene == null) || (!scene.dirty() && scene.deletedNodes().isEmpty());
        shell.update(ctx, scene == null ? null : (clean ? "chưa sửa gì" : chDirty.getText()), !clean);
    }

    private String mapName(int id) {
        for (InfoDao.InfoItem m : allMaps) {
            if (m.id() == id) return (m.name() == null || m.name().isBlank()) ? ("Map " + id) : m.name();
        }
        return "Map " + id;
    }

    /** Đoạn "hiệu ứng: N (Spine a · Nước chảy b · …)" trên status bar. */
    private String fxSummaryText() {
        if (scene == null) return "";
        int n = scene.effectNodes().size();
        if (n == 0) return "   ·   hiệu ứng: 0";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<MapScene.EffectKind, Integer> e : scene.effectCounts().entrySet())
            if (e.getValue() > 0) parts.add(kindShort(e.getKey()) + " " + e.getValue());
        return "   ·   hiệu ứng: " + n + (parts.isEmpty() ? "" : " (" + String.join(" · ", parts) + ")");
    }

    /**
     * Ctrl+S lưu · Ctrl+Z hoàn tác · Ctrl+Y làm lại (uỷ quyền cho canvas).
     * Đăng ký ở {@code root} với WHEN_IN_FOCUSED_WINDOW để còn chạy khi MainFrame mượn content pane.
     * Khi canvas đang giữ focus thì KeyListener của canvas đã xử lý Ctrl+Z/Y rồi → bỏ qua ở đây
     * (nếu không sẽ hoàn tác 2 bước 1 lần).
     */
    private void installShortcuts() {
        InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = root.getActionMap();
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), "ml-save");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), "ml-undo");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, mask), "ml-redo");
        am.put("ml-save", action(this::doSave));
        am.put("ml-undo", action(() -> {
            if (canvas.isFocusOwner()) return;
            canvas.undo();
        }));
        am.put("ml-redo", action(() -> {
            if (canvas.isFocusOwner()) return;
            canvas.redo();
        }));
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        };
    }

    private void focusCanvas() { canvas.requestFocusInWindow(); }

    // ─────────────────────────────────────────────────────────────────────
    // Tiện ích UI
    // ─────────────────────────────────────────────────────────────────────

    /** Window thật đang chứa UI (MainFrame khi nhúng, chính frame này khi chạy riêng). */
    private Window owner() {
        Window w = SwingUtilities.getWindowAncestor(root);
        return (w != null) ? w : (isDisplayable() ? this : null);
    }

    private void msg(String text, int type) {
        JOptionPane.showMessageDialog(owner(), text, "Bố cục Map", type);
    }

    private static JScrollPane textArea(String text, int w, int h) {
        JTextArea ta = new JTextArea(text);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setFont(Theme.font(12, Font.PLAIN));
        ta.setBackground(Theme.BG_INPUT);
        ta.setForeground(Theme.TEXT);
        ta.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ta);
        sp.setPreferredSize(new Dimension(w, h));
        return sp;
    }

    private static JPanel grid() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(true);
        return p;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 2, 3, 2);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int y, String label, JComponent field) {
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = y; c.weightx = 0;
        JLabel l = new JLabel(label);
        l.setFont(Theme.font(12, Font.PLAIN));
        l.setForeground(Theme.TEXT_MUTED);
        form.add(l, c);
        c.gridx = 1; c.weightx = 1;
        form.add(field, c);
    }

    private static JLabel valLabel(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.font(12, Font.BOLD));
        l.setForeground(Theme.TEXT);
        return l;
    }

    private static JLabel dimLabel(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.font(12, Font.BOLD));
        l.setForeground(Theme.TEXT_DIM);
        return l;
    }

    /** Spinner số thực — ép commit mỗi ký tự hợp lệ (bẫy focus của nút, xem Theme.spin). */
    private static JSpinner dspin(double val, double step, double min, double max) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(val, min, max, step));
        JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, "#0.####");
        sp.setEditor(ed);
        if (ed.getTextField().getFormatter() instanceof javax.swing.text.DefaultFormatter df)
            df.setCommitsOnValidEdit(true);
        sp.setPreferredSize(new Dimension(120, 30));
        return sp;
    }

    /** Đọc spinner double, commit nốt text đang gõ dở; hỏng → trả giá trị cũ. */
    private static double dval(JSpinner sp) {
        try {
            sp.commitEdit();
        } catch (java.text.ParseException e) {
            if (sp.getEditor() instanceof JSpinner.DefaultEditor ed) ed.getTextField().setValue(sp.getValue());
        }
        return ((Number) sp.getValue()).doubleValue();
    }

    private static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim().replace(',', '.')); }
        catch (Exception e) { return 0; }
    }

    private static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

    private static String fmt(double v) {
        String s = String.format(java.util.Locale.US, "%.4f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Bật lại chuỗi CONTAINER cha của {@code c} (tới trước {@code stopAt}) sau khi
     * {@link #enableAll} tắt lây — nếu không, nút con dù {@code setEnabled(true)} vẫn nằm trong
     * panel đã tắt và có thể không nhận được click.
     */
    private static void enableParents(Component c, Container stopAt) {
        if (c == null) return;
        for (Container p = c.getParent(); p != null && p != stopAt; p = p.getParent()) p.setEnabled(true);
    }

    private static void enableAll(Container c, boolean on) {
        if (c == null) return;
        for (Component comp : c.getComponents()) {
            comp.setEnabled(on);
            if (comp instanceof Container cc) enableAll(cc, on);
        }
    }

    private void pushUndoCoalesced(Object src) {
        long now = System.currentTimeMillis();
        if (src != null && src == lastUndoSrc && now - lastUndoAt < 900) { lastUndoAt = now; return; }
        canvas.pushUndo();
        lastUndoSrc = src;
        lastUndoAt = now;
    }

    private static String rootMsg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return (m == null || m.isBlank()) ? c.toString() : m;
    }
}
