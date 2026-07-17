package com.apex.maptool;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.ui.MapCanvasPanel;
import com.apex.maptool.unity.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * P0 SPIKE — render map + verify origin/coord.
 *
 * Load Map_{id}.prefab → parse layer → render. Overlay marker enemy/arrive THẬT từ DB
 * (Map1 "Làng Aru") → nếu dot trùng vị trí trên map = origin/coord ĐÚNG.
 */
public class MapToolApp {

    private final ToolConfig cfg = new ToolConfig();
    private final TextureCache textureCache = new TextureCache();
    private GuidIndex guidIndex;
    private MaterialResolver matResolver;
    private MapCanvasPanel canvas;
    private JComboBox<com.apex.maptool.db.InfoDao.InfoItem> mapCombo;

    private com.apex.maptool.db.Db db;
    private com.apex.maptool.db.MapInfoDao mapDao;
    private com.apex.maptool.db.InfoDao infoDao;
    private final com.apex.maptool.db.LocalStore localStore = new com.apex.maptool.db.LocalStore();
    private int currentMapId = 1;
    private JLabel statusLabel;
    private java.util.List<com.apex.maptool.db.InfoDao.InfoItem> mapsCache = new ArrayList<>();
    private final java.util.Map<Integer, String> mapNames = new java.util.HashMap<>();
    private java.util.Map<Integer, java.util.List<com.apex.maptool.model.IncomingDrop>> incomingDrops; // targetMap → drops
    private java.util.Map<Integer, int[]> mapStats = new java.util.HashMap<>(); // mapId → {quái, npc, cổng}
    private com.apex.maptool.ui.WaypointEditorFrame waypointFrame;
    // state cho luồng "chọn điểm rớt ở map đích"
    private java.util.List<com.apex.maptool.model.Marker> pickSavedMarkers;
    private int pickSavedMapId;
    private com.apex.maptool.model.Marker pickGate;

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("test")) {
            new MapToolApp().headlessTest(args.length > 1 ? parseInt(args[1], 1) : 1);
            return;
        }
        if (args.length > 0 && args[0].equals("dbtest")) {
            new MapToolApp().dbTest(args.length > 1 ? parseInt(args[1], 1) : 1);
            return;
        }
        if (args.length > 1 && args[0].equals("cols")) {
            new MapToolApp().cols(args[1]);
            return;
        }
        if (args.length > 0 && args[0].equals("edx")) {
            new MapToolApp().enemyDiag();
            return;
        }
        if (args.length > 0 && args[0].equals("gatedump")) {
            new MapToolApp().gateDump();
            return;
        }
        if (args.length > 0 && args[0].equals("shoptest")) {
            new MapToolApp().shopTest(args.length > 1 ? parseInt(args[1], -1) : -1);
            return;
        }
        if (args.length > 0 && args[0].equals("equip")) {   // mở thẳng Equip Editor (debug/chụp UI)
            SwingUtilities.invokeLater(() -> {
                com.apex.maptool.ui.Theme.apply();
                try {
                    ToolConfig ec = new ToolConfig();
                    warmUpDb(ec);
                    var f = new com.apex.maptool.ui.EquipEditorFrame(
                            new com.apex.maptool.db.EquipDao(new com.apex.maptool.db.Db(ec)),
                            new com.apex.maptool.db.AttrNames(ec.serverRepo()));
                    f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    f.setLocationRelativeTo(null);
                    f.setVisible(true);
                } catch (Exception e) { e.printStackTrace(); System.exit(1); }
            });
            return;
        }
        if (args.length > 0 && args[0].equals("shop")) {   // mở thẳng Shop Editor (debug/chụp UI)
            SwingUtilities.invokeLater(() -> {
                com.apex.maptool.ui.Theme.apply();
                try {
                    ToolConfig sc = new ToolConfig();
                    warmUpDb(sc);
                    var sdb = new com.apex.maptool.db.Db(sc);
                    var sInfo = new com.apex.maptool.db.InfoDao(sdb);
                    var npcList = sInfo.npcs();
                    var tex = new TextureCache();
                    var sr = new SpriteResolver(sc, tex);
                    java.util.Map<Integer, Integer> mm = new java.util.HashMap<>();
                    for (var n : npcList) mm.put(n.id(), n.spinId());
                    sr.setNpcModelMap(mm);
                    var f = new com.apex.maptool.ui.ShopEditorFrame(
                            new com.apex.maptool.db.ShopDao(sdb), npcList, sr::npc,
                            new com.apex.maptool.db.EquipDao(sdb), new com.apex.maptool.db.AttrNames(sc.serverRepo()));
                    f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    f.setLocationRelativeTo(null);
                    f.setVisible(true);
                } catch (Exception e) { e.printStackTrace(); System.exit(1); }
            });
            return;
        }
        // arg đầu là số (map id) → vào thẳng Map Editor (giữ workflow run.bat 1 cũ)
        if (args.length > 0 && args[0].matches("-?\\d+")) {
            SwingUtilities.invokeLater(() -> new MapToolApp().start(args));
            return;
        }
        SwingUtilities.invokeLater(MapToolApp::launchLauncher);
    }

    /** Vỏ app kiểu NRS: sidebar chọn tool + desktop MDI. */
    private static void launchLauncher() {
        com.apex.maptool.ui.Theme.apply();
        ToolConfig cfg = new ToolConfig();
        warmUpDb(cfg);   // mở sẵn pool DB ở luồng nền → bấm tool lần đầu không chờ
        var mf = new com.apex.maptool.ui.MainFrame(cfg, () -> new MapToolApp().startEmbedded());
        mf.setVisible(true);
        SwingUtilities.invokeLater(mf::openDefaultTool);   // mở sẵn Shop như demo
    }

    /** Warm-up pool DB ở luồng nền (connection remote mất ~3.5s để mở — trả trước, ngoài EDT). */
    private static void warmUpDb(ToolConfig cfg) {
        Thread t = new Thread(() -> new com.apex.maptool.db.Db(cfg).warmUp(), "db-warmup");
        t.setDaemon(true);
        t.start();
    }

    /** Diag: enemy id ↔ spin_id ↔ icon/spine nào tồn tại. */
    private void enemyDiag() {
        try {
            var db = new com.apex.maptool.db.Db(cfg);
            java.nio.file.Path assets = cfg.assetsRoot();
            try (var c = db.open(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT id, spin_id, name FROM enemy_info ORDER BY id LIMIT 15")) {
                while (rs.next()) {
                    int id = rs.getInt("id"), spin = rs.getInt("spin_id");
                    String name = rs.getString("name");
                    boolean icoId = java.nio.file.Files.exists(assets.resolve("Textures/GamePlay/IconEnemy/" + id + ".png"));
                    boolean icoSpin = java.nio.file.Files.exists(assets.resolve("Textures/GamePlay/IconEnemy/" + spin + ".png"));
                    boolean spineSpin = java.nio.file.Files.exists(assets.resolve("AssetBundles/Resource/Enemy/" + spin));
                    boolean spineId = java.nio.file.Files.exists(assets.resolve("AssetBundles/Resource/Enemy/" + id));
                    System.out.printf("id=%d spin=%d '%s' | iconById=%s iconBySpin=%s | spineById=%s spineBySpin=%s%n",
                            id, spin, name, icoId, icoSpin, spineId, spineSpin);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    /** Dump Gate_Prefab: layer sprite + collider (để vẽ cổng 1:1 như client). */
    private void gateDump() {
        try {
            guidIndex = new GuidIndex();
            guidIndex.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            matResolver = new MaterialResolver(guidIndex);
            PrefabParser p = new PrefabParser(guidIndex, matResolver, textureCache, cfg.pixelsPerUnit());
            Path gp = cfg.assetsRoot().resolve("AssetBundles/Resource/Button/Gate_Prefab.prefab");
            System.out.println("[GateDump] parse: " + gp);
            List<MapLayer> layers = p.parse(gp);
            System.out.println("[GateDump] layers=" + layers.size());
            for (MapLayer l : layers)
                System.out.printf("[L] '%s' c(%.2f,%.2f) sz(%.2f,%.2f) tex=%s%n",
                        l.debugName, l.cx, l.cy, l.w, l.h, l.texture != null ? l.texture.getFileName() : "NULL");
            System.out.println("[GateDump] colliders=" + p.colliders().size());
            for (com.apex.maptool.unity.ColliderShape c : p.colliders()) {
                double minx = 1e9, maxx = -1e9, miny = 1e9, maxy = -1e9;
                for (double[] pt : c.pts) { minx = Math.min(minx, pt[0]); maxx = Math.max(maxx, pt[0]); miny = Math.min(miny, pt[1]); maxy = Math.max(maxy, pt[1]); }
                System.out.printf("[C] %s '%s' trigger=%s w=%.2f h=%.2f x[%.2f..%.2f] y[%.2f..%.2f]%n",
                        c.kind, c.name, c.isTrigger, maxx - minx, maxy - miny, minx, maxx, miny, maxy);
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    /** Headless verify shop: load types + items 1 tab (mặc định tab đầu), in ra (KHÔNG ghi gì). */
    private void shopTest(int tabId) {
        try {
            var dao = new com.apex.maptool.db.ShopDao(new com.apex.maptool.db.Db(cfg));
            var types = dao.types();
            System.out.println("[ShopTest] types=" + types.size());
            int target = tabId > 0 ? tabId : (types.isEmpty() ? -1 : types.get(0).id);
            if (target > 0) {
                var items = dao.items(target);
                System.out.println("[ShopTest] items tab " + target + " = " + items.size());
                for (int i = 0; i < Math.min(10, items.size()); i++) {
                    var it = items.get(i);
                    System.out.println("[ShopTest]   ID=" + it.id + " slot=" + it.shopSlot + " info=" + it.infoId
                            + " price=" + it.priceJson + " limit=" + it.limit + "/" + it.limitType + " class=" + it.clazz);
                }
            }
            System.out.println("[ShopTest] itemInfos=" + dao.itemInfos().size());
        } catch (Exception e) {
            System.err.println("[ShopTest] FAIL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** In tên column của 1 table. */
    private void cols(String table) {
        try {
            var db = new com.apex.maptool.db.Db(cfg);
            try (var c = db.open(); var st = c.createStatement();
                 var rs = st.executeQuery("SELECT * FROM " + table + " LIMIT 1")) {
                var md = rs.getMetaData();
                System.out.println("[Cols] " + table + ":");
                for (int i = 1; i <= md.getColumnCount(); i++)
                    System.out.println("  " + md.getColumnName(i) + " (" + md.getColumnTypeName(i) + ")");
            }
        } catch (Exception e) {
            System.err.println("[Cols] FAIL: " + e.getMessage());
        }
    }

    /** Headless verify DB: connect + load map markers, in ra. */
    private void dbTest(int mapId) {
        try {
            var db = new com.apex.maptool.db.Db(cfg);
            var dao = new com.apex.maptool.db.MapInfoDao(db);
            System.out.println("[DbTest] connect " + cfg.dbUrl());
            var row = dao.load(mapId);
            System.out.println("[DbTest] map " + mapId + " exists=" + row.exists + " name=" + row.name
                    + " markers=" + row.markers.size());
            java.util.Map<com.apex.maptool.model.Marker.Kind, Integer> cnt = new java.util.HashMap<>();
            for (var m : row.markers) cnt.merge(m.kind, 1, Integer::sum);
            System.out.println("[DbTest] by kind: " + cnt);
            for (var m : row.markers) {
                System.out.println("[DbTest]   " + m.kind + " id=" + m.mainId()
                        + " server(" + m.serverX() + "," + m.serverY() + ") raw=" + m.raw);
            }
        } catch (Exception e) {
            System.err.println("[DbTest] FAIL: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Headless verify: build index + parse map, in stats. Không mở GUI. */
    private void headlessTest(int mapId) {
        try {
            guidIndex = new GuidIndex();
            System.out.println("[Test] guid index (scan " + cfg.assetsRoot() + " nếu chưa cache)...");
            guidIndex.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            matResolver = new MaterialResolver(guidIndex);
            Path prefab = cfg.mapPrefab(mapId);
            System.out.println("[Test] parse: " + prefab);
            PrefabParser parser = new PrefabParser(guidIndex, matResolver, textureCache, cfg.pixelsPerUnit());
            List<MapLayer> layers = parser.parse(prefab);
            System.out.println("[Test] === RESULT ===");
            System.out.println("[Test] layers resolved: " + layers.size());
            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            int withTex = 0;
            for (MapLayer l : layers) {
                if (l.texture != null) withTex++;
                minX = Math.min(minX, l.cx - l.w / 2); maxX = Math.max(maxX, l.cx + l.w / 2);
                minY = Math.min(minY, l.cy - l.h / 2); maxY = Math.max(maxY, l.cy + l.h / 2);
            }
            System.out.println("[Test] layers with texture: " + withTex + "/" + layers.size());
            System.out.printf("[Test] bounds unity: x[%.1f..%.1f] y[%.1f..%.1f]%n", minX, maxX, minY, maxY);
            System.out.printf("[Test] bounds server: x[%.0f..%.0f] y[%.0f..%.0f]%n",
                    minX * cfg.pixelsPerUnit(), maxX * cfg.pixelsPerUnit(),
                    minY * cfg.pixelsPerUnit(), maxY * cfg.pixelsPerUnit());
            for (MapLayer l : layers) {
                System.out.printf("[L] '%s' c(%.2f,%.2f) sz(%.2f,%.2f) x[%.2f..%.2f] sort=%d tex=%s%n",
                        l.debugName, l.cx, l.cy, l.w, l.h, l.cx - l.w / 2, l.cx + l.w / 2, l.sortKey,
                        l.texture != null ? l.texture.getFileName() : "NULL");
            }
            System.out.println("[Test] === COLLIDERS ===");
            for (com.apex.maptool.unity.ColliderShape c : parser.colliders()) {
                double minx = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, miny = Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
                for (double[] p : c.pts) { minx = Math.min(minx, p[0]); maxx = Math.max(maxx, p[0]); miny = Math.min(miny, p[1]); maxy = Math.max(maxy, p[1]); }
                System.out.printf("[C] %s %s trigger=%s pts=%d x[%.2f..%.2f] y[%.2f..%.2f]%n",
                        c.kind, c.name, c.isTrigger, c.pts.length, minx, maxx, miny, maxy);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void start(String[] args) {
        com.apex.maptool.ui.Theme.apply();
        if (!initCore()) return;
        JFrame frame = new JFrame("UocRongOnline Tools — Map Editor");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1400, 850);
        frame.setContentPane(buildRoot());
        frame.setVisible(true);
        int startId = args.length > 0 ? parseInt(args[0], 1) : 1;
        selectMapCombo(startId);
        loadMap(startId);
    }

    /** Mở Map Editor thành cửa sổ con trong desktop MDI (MainFrame kiểu NRS). */
    public JInternalFrame startInDesktop(JDesktopPane desktop) {
        if (!initCore()) return null;
        JInternalFrame inf = new JInternalFrame("Map Editor", true, true, true, true);
        inf.setFrameIcon(null);
        inf.setContentPane(buildRoot());
        inf.setSize(1150, 760);
        desktop.add(inf);
        inf.setVisible(true);
        try { inf.setMaximum(true); } catch (Exception ignored) {}
        selectMapCombo(1);
        loadMap(1);
        return inf;
    }

    /** Map editor dạng CARD nhúng thẳng vào vỏ app (CardLayout — không dùng MDI). */
    public JComponent startEmbedded() {
        if (!initCore()) return null;
        JPanel root = buildRoot();
        selectMapCombo(1);
        loadMap(1);
        return root;
    }

    /** Init nặng: GUID index (fatal) + DB (non-fatal). Trả false nếu không chạy được. */
    private boolean initCore() {
        try {
            guidIndex = new GuidIndex();
            System.out.println("[App] guid index (scan " + cfg.assetsRoot() + " nếu chưa cache)...");
            guidIndex.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            matResolver = new MaterialResolver(guidIndex);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "Build GUID index fail:\n" + e.getMessage() +
                    "\n\nCheck client.repo trong config.properties:\n" + cfg.clientRepo(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
        try {
            db = new com.apex.maptool.db.Db(cfg);
            mapDao = new com.apex.maptool.db.MapInfoDao(db);
            infoDao = new com.apex.maptool.db.InfoDao(db);
            mapsCache = infoDao.maps();
            System.out.println("[App] DB OK, maps=" + mapsCache.size());
            try {
                incomingDrops = mapDao.loadAllIncomingDrops();
                System.out.println("[App] incoming drops: target maps=" + incomingDrops.size());
            } catch (Exception ex) { System.err.println("[App] incoming drops fail: " + ex.getMessage()); }
            try {
                mapStats = mapDao.loadMapStats();
                System.out.println("[App] map stats: " + mapStats.size());
            } catch (Exception ex) { System.err.println("[App] map stats fail: " + ex.getMessage()); }
        } catch (Exception e) {
            System.err.println("[App] DB fail: " + e.getMessage());
            JOptionPane.showMessageDialog(null,
                    "DB connect fail (vẫn xem map được, không đặt/ghi marker):\n" + e.getMessage(),
                    "DB warning", JOptionPane.WARNING_MESSAGE);
        }
        return true;
    }

    /** Dựng toàn bộ UI map editor (canvas + toolbar + side + status) — dùng cho cả JFrame lẫn JInternalFrame. */
    private JPanel buildRoot() {
        canvas = new MapCanvasPanel(textureCache, cfg.pixelsPerUnit());
        SpriteResolver spriteResolver = new SpriteResolver(cfg, textureCache);
        // map enemy_info.id → spin_id (visual id) cho icon đúng
        try {
            if (infoDao != null) {
                java.util.Map<Integer, Integer> spinMap = new java.util.HashMap<>();
                java.util.Map<Integer, Integer> typeMap = new java.util.HashMap<>();
                for (var it : infoDao.enemies()) { spinMap.put(it.id(), it.spinId()); typeMap.put(it.id(), it.type()); enemyNames.put(it.id(), it.name()); }
                for (var it : infoDao.npcs()) npcNames.put(it.id(), it.name());
                spriteResolver.setEnemySpinMap(spinMap);
                spriteResolver.setEnemyTypeMap(typeMap);
                java.util.Map<Integer, Integer> npcModelMap = new java.util.HashMap<>();
                for (var it : infoDao.npcs()) npcModelMap.put(it.id(), it.spinId()); // spinId field = model_id
                spriteResolver.setNpcModelMap(npcModelMap);
                System.out.println("[App] enemy spin map: " + spinMap.size() + ", npc model map: " + npcModelMap.size());
            }
        } catch (Exception e) {
            System.err.println("[App] build spin map fail: " + e.getMessage());
        }
        this.sprites = spriteResolver;
        canvas.setSpriteResolver(spriteResolver);
        canvas.setOnChange(this::updateStatus);
        for (var it : mapsCache) if (it.name() != null) mapNames.put(it.id(), it.name());
        canvas.setMapNames(mapNames);
        // collider Gate_Prefab (click 4×1 + chạm 4×4) → vẽ cổng 1:1 như client
        try {
            PrefabParser gp = new PrefabParser(guidIndex, matResolver, textureCache, cfg.pixelsPerUnit());
            gp.parse(cfg.assetsRoot().resolve("AssetBundles/Resource/Button/Gate_Prefab.prefab"));
            canvas.setGatewayColliders(gp.colliders());
            System.out.println("[App] gate colliders: " + gp.colliders().size());
        } catch (Exception e) { System.err.println("[App] gate colliders fail: " + e.getMessage()); }
        canvas.setOnSelect(m -> {
            if (waypointFrame != null && waypointFrame.isDisplayable() && waypointFrame.isVisible())
                waypointFrame.onCanvasSelect(m);
            if (markerListFrame != null && markerListFrame.isDisplayable() && markerListFrame.isVisible())
                markerListFrame.onCanvasSelect(m);
        });
        canvas.setOnReload(() -> {   // sau undo/redo: nạp lại bảng cổng + status
            if (waypointFrame != null && waypointFrame.isDisplayable()) waypointFrame.refresh();
            if (markerListFrame != null && markerListFrame.isDisplayable()) markerListFrame.refresh();
            updateStatus();
        });
        canvas.setOnIncomingChange(this::updateStatus);   // kéo điểm rơi vào → cập nhật trạng thái
        JPanel root = new JPanel(new BorderLayout());
        root.add(canvas, BorderLayout.CENTER);
        root.add(buildToolbar(), BorderLayout.NORTH);
        root.add(buildSidePanel(), BorderLayout.EAST);
        root.add(buildStatusBar(), BorderLayout.SOUTH);
        return root;
    }

    private JComponent buildSidePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        panel.add(com.apex.maptool.ui.Theme.section("Hiển thị", buildDisplayPanel(), false));
        panel.add(Box.createVerticalStrut(10));
        panel.add(com.apex.maptool.ui.Theme.section("Đối tượng", buildObjectsPanel(), false));
        panel.add(Box.createVerticalStrut(10));
        panel.add(com.apex.maptool.ui.Theme.section("Cổng · Waypoint", buildGatePanel(), false));
        panel.add(Box.createVerticalStrut(10));
        panel.add(com.apex.maptool.ui.Theme.section("Shop", buildShopPanel(), false));
        panel.add(Box.createVerticalStrut(10));
        panel.add(com.apex.maptool.ui.Theme.section("Thao tác", buildActionsPanel(), false));
        panel.add(Box.createVerticalStrut(10));
        panel.add(com.apex.maptool.ui.Theme.section("Chú thích", buildLegendPanel(), false));
        panel.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(panel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setPreferredSize(new Dimension(300, 0));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(70, 70, 90)));
        return sp;
    }

    private JComponent buildDisplayPanel() {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 3));
        p.setOpaque(false);
        p.add(com.apex.maptool.ui.Theme.check("Map", true, canvas::setShowLayers));
        p.add(com.apex.maptool.ui.Theme.check("Collider", true, canvas::setShowColliders));
        p.add(com.apex.maptool.ui.Theme.check("Trigger", false, canvas::setShowTriggers));
        p.add(com.apex.maptool.ui.Theme.check("Vùng nhìn", true, canvas::setClipToView));
        p.add(com.apex.maptool.ui.Theme.check("Sky", true, canvas::setSkyBg));
        p.add(com.apex.maptool.ui.Theme.check("Ẩn BG", false, canvas::setHideBg));
        p.add(com.apex.maptool.ui.Theme.check("Vùng cổng", true, canvas::setShowGatewayRange));
        p.add(com.apex.maptool.ui.Theme.check("Spine", true, canvas::setUseSpine));
        p.add(com.apex.maptool.ui.Theme.check("Đi lại", true, canvas::setPatrol));
        p.add(com.apex.maptool.ui.Theme.check("Điểm rơi vào", true, canvas::setShowIncomingDrops));
        p.add(com.apex.maptool.ui.Theme.check("Đường NPC", true, canvas::setShowNpcLine));
        p.add(com.apex.maptool.ui.Theme.check("Lưới ô", false, canvas::setShowGrid));
        JPanel sizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        sizeRow.setOpaque(false);
        sizeRow.add(new JLabel("Cỡ marker"));
        JSpinner sp = new JSpinner(new SpinnerNumberModel(0.75, 0.1, 5.0, 0.05));
        sp.setPreferredSize(new Dimension(60, 26));
        sp.addChangeListener(e -> canvas.setMarkerScale((Double) sp.getValue()));
        sizeRow.add(sp);
        p.add(sizeRow);
        JPanel npcRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        npcRow.setOpaque(false);
        npcRow.add(new JLabel("NPC cao"));
        JSpinner spNpc = new JSpinner(new SpinnerNumberModel(0.35, 0.0, 3.0, 0.05));
        spNpc.setPreferredSize(new Dimension(60, 26));
        spNpc.addChangeListener(e -> canvas.setNpcLineOffset((Double) spNpc.getValue()));
        npcRow.add(spNpc);
        p.add(npcRow);
        JPanel gridRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        gridRow.setOpaque(false);
        gridRow.add(new JLabel("Cỡ ô"));
        JSpinner spGrid = new JSpinner(new SpinnerNumberModel(1.0, 0.25, 10.0, 0.25));
        spGrid.setPreferredSize(new Dimension(60, 26));
        spGrid.addChangeListener(e -> canvas.setGridSize((Double) spGrid.getValue()));
        gridRow.add(spGrid);
        p.add(gridRow);
        return p;
    }

    private com.apex.maptool.ui.ObjectPickerFrame objectPicker;
    private com.apex.maptool.ui.MarkerListFrame markerListFrame;
    private SpriteResolver sprites;
    private final java.util.Map<Integer, String> enemyNames = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> npcNames = new java.util.HashMap<>();

    private JComponent buildObjectsPanel() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 5));
        p.setOpaque(false);
        p.add(com.apex.maptool.ui.Theme.btn("👾  Chọn Quái / NPC…", com.apex.maptool.ui.Theme.BTN_PRIMARY, e -> openObjectPicker()));
        p.add(com.apex.maptool.ui.Theme.btn("🤖  Rải quái tự động…", com.apex.maptool.ui.Theme.BTN_SUCCESS, e -> autoPlaceDialog()));
        p.add(com.apex.maptool.ui.Theme.btn("📋  Danh sách đối tượng…", null, e -> openMarkerList()));
        p.add(com.apex.maptool.ui.Theme.btn("⏹  Dừng đặt (Esc)", null,
                e -> { canvas.clearPlaceMode(); updateStatus(); }));
        return p;
    }

    /** Rải quái tự động: chọn quái + số lượng + cách đều → tool tự đặt đều trên ground (canh giữa map). */
    private void autoPlaceDialog() {
        java.util.List<com.apex.maptool.db.InfoDao.InfoItem> ens = new ArrayList<>();
        try { if (infoDao != null) ens.addAll(infoDao.enemies()); } catch (Exception ex) { /* ignored */ }
        if (ens.isEmpty()) { JOptionPane.showMessageDialog(null, "Chưa kết nối DB (không có list quái)", "Lỗi", JOptionPane.ERROR_MESSAGE); return; }

        JComboBox<com.apex.maptool.db.InfoDao.InfoItem> cbEnemy = new JComboBox<>(ens.toArray(new com.apex.maptool.db.InfoDao.InfoItem[0]));
        JSpinner spCount = new JSpinner(new SpinnerNumberModel(5, 1, 50, 1));
        JSpinner spGap = new JSpinner(new SpinnerNumberModel(3.0, 0.5, 30.0, 0.5));
        JCheckBox cbRange = new JCheckBox("Kéo chọn vùng rải trên map", true);
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Quái:"));        form.add(cbEnemy);
        form.add(new JLabel("Số lượng:"));    form.add(spCount);
        form.add(new JLabel("Cách đều (unit):")); form.add(spGap);
        form.add(cbRange);                    form.add(new JLabel("(bỏ tick = rải cả map)"));
        int ok = JOptionPane.showConfirmDialog(null, form, "🤖 Rải quái tự động", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        var it = (com.apex.maptool.db.InfoDao.InfoItem) cbEnemy.getSelectedItem();
        if (it == null) return;
        int count = (Integer) spCount.getValue();
        if (cbRange.isSelected()) {
            // kéo chọn vùng chữ nhật trên canvas → thả chuột là rải (chia đều trong vùng, đúng tầng)
            canvas.setRangePickMode((x0, y0, x1, y1) -> {
                int placed = canvas.autoPlaceEnemiesInRect(it.id(), count, x0, y0, x1, y1);
                updateStatus();
                statusLabel.setText("Đã rải " + placed + " quái '" + it.name() + "' trong vùng — Ctrl+Z hoàn tác");
            });
            canvas.requestFocusInWindow();
        } else {
            int placed = canvas.autoPlaceEnemies(it.id(), count, (Double) spGap.getValue());
            updateStatus();
            JOptionPane.showMessageDialog(null,
                    "Đã rải " + placed + " quái '" + it.name() + "' cách đều " + spGap.getValue() + " unit trên ground."
                            + "\nChưa ưng → Ctrl+Z hoàn tác cả đợt.",
                    "Rải quái", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Danh sách đối tượng + khóa chọn (chỉ kéo vật đã chọn — chống kéo nhầm). */
    private void openMarkerList() {
        if (markerListFrame == null || !markerListFrame.isDisplayable()) {
            markerListFrame = new com.apex.maptool.ui.MarkerListFrame(canvas,
                    enemyNames::get, npcNames::get, mapNames::get,
                    id -> sprites != null ? sprites.enemy(id) : null,
                    id -> sprites != null ? sprites.npc(id) : null);
            markerListFrame.setLocationRelativeTo(null);
        }
        markerListFrame.refresh();
        markerListFrame.applyLock();
        markerListFrame.setVisible(true);
        markerListFrame.toFront();
    }

    /** Mở cửa sổ picker đối tượng (kiểu NRS): chọn dòng → cầm template → click map đặt. */
    private void openObjectPicker() {
        if (objectPicker == null || !objectPicker.isDisplayable()) {
            java.util.List<com.apex.maptool.db.InfoDao.InfoItem> ens = new ArrayList<>(), nps = new ArrayList<>();
            try {
                if (infoDao != null) { ens.addAll(infoDao.enemies()); nps.addAll(infoDao.npcs()); }
            } catch (Exception ex) { System.err.println("[App] load picker info fail: " + ex.getMessage()); }
            objectPicker = new com.apex.maptool.ui.ObjectPickerFrame(ens, nps,
                    id -> sprites != null ? sprites.enemy(id) : null,
                    id -> sprites != null ? sprites.npc(id) : null,
                    (kind, id) -> {
                        canvas.setPlaceMode(kind, id, 0);
                        updateStatus();
                        canvas.requestFocusInWindow();
                    });
            objectPicker.setLocationRelativeTo(null);
        }
        objectPicker.setVisible(true);
        objectPicker.toFront();
    }

    private JComponent buildGatePanel() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 5));
        p.setOpaque(false);
        p.add(com.apex.maptool.ui.Theme.btn("🚪  Waypoint Editor", com.apex.maptool.ui.Theme.BTN_SUCCESS, e -> openWaypointEditor()));
        p.add(com.apex.maptool.ui.Theme.btn("➕  Đặt nhanh cổng qua map", null, e -> placeGateway()));
        p.add(com.apex.maptool.ui.Theme.btn("💾  Lưu điểm rơi vào", com.apex.maptool.ui.Theme.BTN_PURPLE, e -> saveIncomingDrops()));
        return p;
    }

    private com.apex.maptool.ui.ShopEditorFrame shopFrame;

    private JComponent buildShopPanel() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 5));
        p.setOpaque(false);
        p.add(com.apex.maptool.ui.Theme.btn("🛒  Shop Editor", com.apex.maptool.ui.Theme.BTN_WARN, e -> openShopEditor()));
        return p;
    }

    private void openShopEditor() {
        if (db == null) { JOptionPane.showMessageDialog(null, "Chưa kết nối DB", "Lỗi", JOptionPane.ERROR_MESSAGE); return; }
        if (shopFrame == null || !shopFrame.isDisplayable()) {
            java.util.List<com.apex.maptool.db.InfoDao.InfoItem> npcList = new ArrayList<>();
            try { if (infoDao != null) npcList = infoDao.npcs(); } catch (Exception ignored) {}
            shopFrame = new com.apex.maptool.ui.ShopEditorFrame(new com.apex.maptool.db.ShopDao(db),
                    npcList, id -> sprites != null ? sprites.npc(id) : null);
            shopFrame.setLocationRelativeTo(null);
        }
        shopFrame.setVisible(true);
        shopFrame.toFront();
    }

    private JComponent buildActionsPanel() {
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 5));
        p.setOpaque(false);
        p.add(com.apex.maptool.ui.Theme.btn("🗑  Xóa chọn", com.apex.maptool.ui.Theme.BTN_DANGER, e -> canvas.deleteSelected()));
        p.add(com.apex.maptool.ui.Theme.btn("📂  Mở file", null, e -> openFile()));
        p.add(com.apex.maptool.ui.Theme.btn("💾  Lưu file", com.apex.maptool.ui.Theme.BTN_PRIMARY, e -> saveFile()));
        p.add(com.apex.maptool.ui.Theme.btn("★  Lưu DB", com.apex.maptool.ui.Theme.BTN_WARN, e -> saveDb()));
        return p;
    }

    private JComponent buildLegendPanel() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 0));
        p.setOpaque(false);
        p.add(com.apex.maptool.ui.Theme.legendRow(new Color(255, 40, 40), "Đường đi (ground) / Quái"));
        p.add(com.apex.maptool.ui.Theme.legendRow(new Color(80, 180, 255), "Oneway / Điểm đứng"));
        p.add(com.apex.maptool.ui.Theme.legendRow(new Color(255, 140, 0), "Cổng / Trigger"));
        p.add(com.apex.maptool.ui.Theme.legendRow(new Color(60, 220, 90), "NPC"));
        p.add(com.apex.maptool.ui.Theme.legendRow(new Color(225, 80, 255), "Điểm rơi vào (từ map khác)"));
        p.add(com.apex.maptool.ui.Theme.legendRow(new Color(20, 20, 20), "Tường biên"));
        return p;
    }

    /** Status bar dưới cùng: trạng thái đặt + đếm marker. */
    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 3));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(70, 70, 90)));
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(com.apex.maptool.ui.Theme.TXT_DIM);
        bar.add(statusLabel);
        return bar;
    }

    private void placeGateway() {
        int targetMap;
        if (mapsCache != null && !mapsCache.isEmpty()) {
            // dropdown chọn map đích (id — tên), khỏi gõ số
            Object choice = JOptionPane.showInputDialog(null, "Chọn map đích của cổng:", "Cổng qua map",
                    JOptionPane.QUESTION_MESSAGE, null, mapsCache.toArray(), mapsCache.get(0));
            if (choice == null) return;
            targetMap = ((com.apex.maptool.db.InfoDao.InfoItem) choice).id();
        } else {
            String input = JOptionPane.showInputDialog(null, "Map đích (mapId):", "Cổng qua map", JOptionPane.QUESTION_MESSAGE);
            if (input == null) return;
            targetMap = parseInt(input.trim(), -1);
            if (targetMap < 0) return;
        }
        canvas.setPlaceMode(com.apex.maptool.model.Marker.Kind.GATEWAY, targetMap, 0);
        updateStatus();
        canvas.requestFocusInWindow();
    }

    private void saveFile() {
        try {
            localStore.save(currentMapId, canvas.getEditMarkers());
            JOptionPane.showMessageDialog(null,
                    "Lưu file local OK (KHÔNG đụng DB):\n" + localStore.path(currentMapId)
                    + "\n\n" + canvas.getEditMarkers().size() + " marker. Mở lại bằng 'Mở file local'.",
                    "Lưu file", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Lưu file fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openFile() {
        try {
            var ms = localStore.load(currentMapId);
            if (ms == null) {
                JOptionPane.showMessageDialog(null, "Chưa có file local cho map " + currentMapId, "Mở file", JOptionPane.WARNING_MESSAGE);
                return;
            }
            canvas.setEditMarkers(ms);
            updateStatus();
            JOptionPane.showMessageDialog(null, "Mở file local map " + currentMapId + " (" + ms.size() + " marker).", "Mở file", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Mở file fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveDb() {
        if (mapDao == null) { JOptionPane.showMessageDialog(null, "Chưa kết nối DB", "Lỗi", JOptionPane.ERROR_MESSAGE); return; }
        try {
            mapDao.save(currentMapId, canvas.getEditMarkers());
            JOptionPane.showMessageDialog(null, "Đã lưu map " + currentMapId + " (" + canvas.getEditMarkers().size() + " marker).\nBackup ở thư mục backup/.", "Lưu OK", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Lưu fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Lưu các điểm rơi vào đã kéo (dirty) về DB của từng map nguồn (sửa bX/bY cổng tương ứng). */
    private void saveIncomingDrops() {
        if (mapDao == null) { JOptionPane.showMessageDialog(null, "Chưa kết nối DB", "Lỗi", JOptionPane.ERROR_MESSAGE); return; }
        var dirty = canvas.getIncomingDrops().stream().filter(d -> d.dirty).toList();
        if (dirty.isEmpty()) {
            JOptionPane.showMessageDialog(null, "Không có điểm rơi vào nào thay đổi.", "Lưu điểm rơi vào", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int ok = 0;
        StringBuilder err = new StringBuilder();
        for (var d : dirty) {
            try {
                mapDao.updateGatewayDrop(d.srcMapId, d.gateIndex, d.serverX, d.serverY);
                d.dirty = false;
                ok++;
            } catch (Exception ex) {
                err.append("\n• map ").append(d.srcMapId).append(": ").append(ex.getMessage());
            }
        }
        canvas.repaint();
        updateStatus();
        JOptionPane.showMessageDialog(null,
                "Đã lưu " + ok + "/" + dirty.size() + " điểm rơi vào (ghi bX/bY về DB map nguồn, có backup)."
                        + (err.length() > 0 ? "\nLỗi:" + err : ""),
                "Lưu điểm rơi vào", err.length() > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateStatus() {
        if (statusLabel == null) return;
        var ms = canvas.getEditMarkers();
        long en = ms.stream().filter(m -> m.kind == com.apex.maptool.model.Marker.Kind.ENEMY).count();
        long np = ms.stream().filter(m -> m.kind == com.apex.maptool.model.Marker.Kind.NPC).count();
        long gw = ms.stream().filter(m -> m.kind == com.apex.maptool.model.Marker.Kind.GATEWAY).count();
        String pm = canvas.placeMode() == null ? "—" : canvas.placeMode().toString();
        long dirtyDrops = canvas.getIncomingDrops().stream().filter(d -> d.dirty).count();
        statusLabel.setText("Đặt: " + pm + "    |    Quái " + en + " · NPC " + np + " · Cổng " + gw
                + (dirtyDrops > 0 ? "    |    ⚠ " + dirtyDrops + " điểm rơi chưa lưu" : ""));
        if (waypointFrame != null && waypointFrame.isDisplayable() && waypointFrame.isVisible())
            waypointFrame.syncCurrent();
        if (markerListFrame != null && markerListFrame.isDisplayable() && markerListFrame.isVisible())
            markerListFrame.sync();
    }

    private JToolBar buildToolbar() {
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(70, 70, 90)),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)));
        JLabel lblMap = new JLabel("Map  ");
        lblMap.setFont(lblMap.getFont().deriveFont(Font.BOLD));
        tb.add(lblMap);
        // lọc map theo nội dung (đếm từ mapStats — quét 1 lần lúc start)
        JComboBox<String> cbFilter = new JComboBox<>(new String[]{"Tất cả", "Có quái", "Có NPC", "Có cổng", "Trống"});
        cbFilter.setMaximumSize(new Dimension(110, 30));
        cbFilter.setFocusable(false);
        tb.add(cbFilter);
        tb.add(Box.createHorizontalStrut(4));
        mapCombo = new JComboBox<>();
        mapCombo.setEditable(true);   // chọn từ list (id — tên) hoặc gõ id
        for (var it : mapsCache) mapCombo.addItem(it);
        mapCombo.setMaximumSize(new Dimension(300, 30));
        mapCombo.setPreferredSize(new Dimension(260, 28));
        // dropdown hiện kèm số lượng: "id — tên   Q5 N2 C3"
        mapCombo.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof com.apex.maptool.db.InfoDao.InfoItem ii) {
                    int[] s = mapStats.get(ii.id());
                    if (s != null) setText(ii + "   ·  Q" + s[0] + " N" + s[1] + " C" + s[2]);
                }
                return c;
            }
        });
        cbFilter.addActionListener(e -> {
            int f = cbFilter.getSelectedIndex();
            Object keep = mapCombo.getSelectedItem();
            mapCombo.removeAllItems();
            for (var it : mapsCache) {
                int[] s = mapStats.getOrDefault(it.id(), new int[3]);
                boolean ok = switch (f) {
                    case 1 -> s[0] > 0;                       // có quái
                    case 2 -> s[1] > 0;                       // có NPC
                    case 3 -> s[2] > 0;                       // có cổng
                    case 4 -> s[0] == 0 && s[1] == 0 && s[2] == 0; // trống
                    default -> true;
                };
                if (ok) mapCombo.addItem(it);
            }
            if (keep != null) mapCombo.setSelectedItem(keep);
        });
        tb.add(mapCombo);
        tb.add(Box.createHorizontalStrut(6));
        tb.add(com.apex.maptool.ui.Theme.btn("Load", com.apex.maptool.ui.Theme.BTN_PRIMARY, e -> loadMap(selectedMapId())));
        tb.add(Box.createHorizontalStrut(4));
        tb.add(com.apex.maptool.ui.Theme.btn("Fit", null, e -> canvas.fitView()));
        tb.addSeparator(new Dimension(16, 24));
        tb.add(com.apex.maptool.ui.Theme.btn("↶ Undo", null, e -> canvas.undo()));
        tb.add(Box.createHorizontalStrut(4));
        tb.add(com.apex.maptool.ui.Theme.btn("↷ Redo", null, e -> canvas.redo()));
        tb.add(Box.createHorizontalGlue());   // đẩy nhóm lưu sang phải
        tb.add(com.apex.maptool.ui.Theme.btn("💾 Lưu file", com.apex.maptool.ui.Theme.BTN_PRIMARY, e -> saveFile()));
        tb.add(Box.createHorizontalStrut(4));
        tb.add(com.apex.maptool.ui.Theme.btn("★ Lưu DB", com.apex.maptool.ui.Theme.BTN_WARN, e -> saveDb()));
        return tb;
    }

    /** Map id đang chọn ở combo toolbar (chọn item id—tên, hoặc gõ id). */
    private int selectedMapId() {
        Object sel = mapCombo.getSelectedItem();
        if (sel instanceof com.apex.maptool.db.InfoDao.InfoItem ii) return ii.id();
        return parseInt(String.valueOf(sel), currentMapId);
    }

    /** Chọn map id trên combo (nếu có trong list), không thì gõ id (combo editable). */
    private void selectMapCombo(int id) {
        for (int i = 0; i < mapCombo.getItemCount(); i++)
            if (mapCombo.getItemAt(i).id() == id) { mapCombo.setSelectedItem(mapCombo.getItemAt(i)); return; }
        mapCombo.setSelectedItem(String.valueOf(id));
    }

    private void loadMap(int mapId) {
        if (!renderMapGeometry(mapId)) return;
        canvas.setMarkers(new ArrayList<>()); // tạm ẩn verify marker (đứng/quái)
        currentMapId = mapId;
        if (mapCombo != null) selectMapCombo(mapId);
        String nm = mapNames.get(mapId);
        canvas.setMapLabel("Map " + mapId + (nm != null ? " — " + nm : ""));
        // điểm rơi VÀO map này từ cổng các map khác (kéo được để sửa bX/bY map nguồn)
        if (incomingDrops != null) {
            java.util.List<com.apex.maptool.model.IncomingDrop> list = new ArrayList<>();
            for (var d : incomingDrops.getOrDefault(mapId, java.util.List.of()))
                if (!(d.serverX == 0 && d.serverY == 0)) list.add(d);   // bX/bY chưa set → bỏ
            canvas.setIncomingDrops(list);
        }
        // Ưu tiên file local đang sửa dở (nếu có) → hỏi user
        boolean loadedLocal = false;
        if (localStore.exists(mapId)) {
            int ch = JOptionPane.showConfirmDialog(null,
                    "Map " + mapId + " có bản lưu local (đang sửa dở).\nMở bản LOCAL? (Không = load từ DB)",
                    "Có file local", JOptionPane.YES_NO_OPTION);
            if (ch == JOptionPane.YES_OPTION) {
                try {
                    var ms = localStore.load(mapId);
                    canvas.setEditMarkers(ms != null ? ms : new ArrayList<>());
                    loadedLocal = true;
                    System.out.println("[App] loaded LOCAL markers: " + (ms == null ? 0 : ms.size()));
                } catch (Exception ex) {
                    System.err.println("[App] load local fail: " + ex.getMessage());
                }
            }
        }
        // Không load local → load từ DB
        if (!loadedLocal && mapDao != null) {
            try {
                var row = mapDao.load(mapId);
                canvas.setEditMarkers(row.markers);
                System.out.println("[App] DB markers: " + row.markers.size());
            } catch (Exception ex) {
                System.err.println("[App] load DB markers fail: " + ex.getMessage());
                canvas.setEditMarkers(new ArrayList<>());
            }
        }
        if (waypointFrame != null && waypointFrame.isDisplayable()) waypointFrame.refresh();
        if (markerListFrame != null && markerListFrame.isDisplayable()) markerListFrame.refresh();
        updateStatus();
        SwingUtilities.invokeLater(canvas::fitView);
    }

    /** Parse prefab map → set layer + collider lên canvas (KHÔNG đụng marker). */
    private boolean renderMapGeometry(int mapId) {
        Path prefab = cfg.mapPrefab(mapId);
        System.out.println("[App] render map prefab: " + prefab);
        try {
            PrefabParser parser = new PrefabParser(guidIndex, matResolver, textureCache, cfg.pixelsPerUnit());
            List<MapLayer> layers = parser.parse(prefab);
            System.out.println("[App] layers: " + layers.size() + " colliders: " + parser.colliders().size());
            canvas.setLayers(layers);
            canvas.setColliders(parser.colliders());
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null,
                    "Load map " + mapId + " fail:\n" + e.getMessage() + "\nPrefab: " + prefab,
                    "Error", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    // ─── Waypoint Editor ───────────────────────────────────────
    private void openWaypointEditor() {
        if (waypointFrame == null || !waypointFrame.isDisplayable()) {
            waypointFrame = new com.apex.maptool.ui.WaypointEditorFrame(
                    canvas, mapsCache, this::startPickDropPoint, this::updateStatus);
            waypointFrame.setLocationRelativeTo(null);
        }
        waypointFrame.refresh();
        waypointFrame.setVisible(true);
        waypointFrame.toFront();
    }

    /** Bắt đầu chọn điểm rớt (bX/bY) ở map đích: render map đích, vào pick-mode. */
    private void startPickDropPoint(com.apex.maptool.model.Marker gate) {
        if (gate == null) return;
        int target = gate.mainId();
        pickGate = gate;
        pickSavedMarkers = canvas.getEditMarkers();
        pickSavedMapId = currentMapId;
        if (!renderMapGeometry(target)) { pickGate = null; return; }
        // nạp cổng của map đích (chỉ để xem bối cảnh — pick-mode chặn sửa/kéo)
        java.util.List<com.apex.maptool.model.Marker> targetGates = new ArrayList<>();
        try {
            java.util.List<com.apex.maptool.model.Marker> ms = null;
            if (localStore.exists(target)) ms = localStore.load(target);
            else if (mapDao != null) ms = mapDao.load(target).markers;
            if (ms != null) for (var mk : ms)
                if (mk.kind == com.apex.maptool.model.Marker.Kind.GATEWAY) targetGates.add(mk);
        } catch (Exception ex) { System.err.println("[App] load target gates fail: " + ex.getMessage()); }
        canvas.setEditMarkers(targetGates); // hiện cổng map đích
        canvas.setPickMode(true, this::onPickedDrop, this::cancelPick);
        int obx = gate.getInt("bX"), oby = gate.getInt("bY");   // điểm rơi cũ (bX/bY)
        int ppu = cfg.pixelsPerUnit();
        canvas.setPickReference(!(obx == 0 && oby == 0), obx / (double) ppu, oby / (double) ppu);
        SwingUtilities.invokeLater(canvas::fitView);
    }

    private void onPickedDrop(int serverX, int serverY) {
        if (pickGate != null) {
            if (pickSavedMarkers != null) canvas.pushUndoOf(pickSavedMarkers); // undo cho lần set bX/bY
            pickGate.setInt("bX", serverX);
            pickGate.setInt("bY", serverY);
        }
        finishPick();
    }

    private void cancelPick() { finishPick(); }

    private void finishPick() {
        canvas.setPickMode(false, null, null);
        renderMapGeometry(pickSavedMapId);
        currentMapId = pickSavedMapId;
        canvas.setEditMarkers(pickSavedMarkers != null ? pickSavedMarkers : new ArrayList<>());
        if (pickGate != null) canvas.selectMarker(pickGate);
        SwingUtilities.invokeLater(canvas::fitView);
        if (waypointFrame != null && waypointFrame.isDisplayable()) waypointFrame.refresh();
        pickGate = null;
        pickSavedMarkers = null;
        updateStatus();
    }

    /**
     * Marker verify P0 — data THẬT từ DB. Chỉ Map1 (Làng Aru) có sẵn để verify.
     * server coord → unity = /PPU.
     */
    private List<MapCanvasPanel.VMarker> verifyMarkers(int mapId) {
        List<MapCanvasPanel.VMarker> ms = new ArrayList<>();
        int ppu = cfg.pixelsPerUnit();
        if (mapId == 1) {
            // list_enemies Map1: infoId1, spawnCx {-500,-200,100,400,700}, spawnCy 60
            int[] ex = {-500, -200, 100, 400, 700};
            for (int sx : ex) {
                ms.add(new MapCanvasPanel.VMarker(sx / (double) ppu, 60 / (double) ppu, Color.RED, "quái"));
            }
            // list_arrive_position Map1: {-837, 60}
            ms.add(new MapCanvasPanel.VMarker(-837 / (double) ppu, 60 / (double) ppu, Color.CYAN, "đứng"));
        }
        return ms;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
