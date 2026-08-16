package com.apex.maptool;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.ui.MapCanvasPanel;
import com.apex.maptool.unity.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                    var edb = new com.apex.maptool.db.Db(ec);
                    var f = new com.apex.maptool.ui.EquipEditorFrame(
                            new com.apex.maptool.db.EquipDao(edb),
                            new com.apex.maptool.db.AttrNames(ec.serverRepo(), edb));
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
                            new com.apex.maptool.db.EquipDao(sdb), new com.apex.maptool.db.AttrNames(sc.serverRepo(), sdb));
                    f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    f.setLocationRelativeTo(null);
                    f.setVisible(true);
                } catch (Exception e) { e.printStackTrace(); System.exit(1); }
            });
            return;
        }
        if (args.length > 0 && args[0].equals("layout")) {   // mở thẳng Bố cục Map (debug/chụp UI)
            int layoutMapId = args.length > 1 ? parseInt(args[1], 1) : 1;
            SwingUtilities.invokeLater(() -> {
                com.apex.maptool.ui.Theme.apply();
                try {
                    ToolConfig lc = new ToolConfig();
                    GuidIndex gi = new GuidIndex();
                    gi.buildOrLoad(lc.assetsRoot(), lc.guidCacheFile());
                    java.util.List<com.apex.maptool.db.InfoDao.InfoItem> lmaps;
                    try {
                        lmaps = new com.apex.maptool.db.InfoDao(new com.apex.maptool.db.Db(lc)).maps();
                    } catch (Throwable ex) {   // DB lỗi (kể cả thiếu driver/HikariCP trên classpath)
                        // → vẫn mở tool, chỉ thiếu tên map. Bắt Throwable vì chạy bằng
                        // "java -cp target/classes" (không có dependency) sẽ ném NoClassDefFoundError.
                        System.err.println("[layout] danh sách map fail: " + ex);
                        lmaps = new ArrayList<>();
                    }
                    var f = new com.apex.maptool.ui.MapLayoutEditorFrame(
                            lc, gi, new MaterialResolver(gi), new TextureCache(), lmaps);
                    f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                    f.setLocationRelativeTo(null);
                    f.setVisible(true);
                    f.loadMap(layoutMapId);

                    // layout <mapId> snap=<png> [sel=<tênNode>] → chọn node rồi chụp và thoát
                    String snap = null, sel = null;
                    for (String a : args) {
                        if (a.startsWith("snap=")) snap = a.substring(5);
                        else if (a.startsWith("sel=")) sel = a.substring(4);
                    }
                    final String selName = sel;
                    if (snap != null) {
                        final String png = snap;
                        new javax.swing.Timer(2500, ev -> {
                            ((javax.swing.Timer) ev.getSource()).stop();
                            try {
                                // loadMap chạy BẤT ĐỒNG BỘ (SwingWorker) → phải chọn node SAU khi
                                // nó xong, chọn ngay sau loadMap thì scene còn rỗng.
                                if (selName != null) {
                                    String got = f.selectNodeByName(selName);
                                    System.out.println("[layout] chọn node: "
                                            + (got != null ? got : "KHÔNG THẤY '" + selName + "'"));
                                }
                                java.awt.Container cp = f.getContentPane();
                                java.awt.Component target = cp;
                                // crop=right → chụp RIÊNG panel thông tin bên phải ở chiều cao ĐẦY
                                // ĐỦ (cửa sổ bị kẹp theo màn hình nên mục cuối luôn ngoài khung).
                                if (java.util.Arrays.asList(args).contains("crop=right")) {
                                    // Nửa phải của cửa sổ + nội dung CAO nhất. Chỉ lấy "phải nhất"
                                    // là dính mấy scroll pane rỗng cao 1px nằm sát mép.
                                    int mid = cp.getLocationOnScreen().x + cp.getWidth() / 2;
                                    JScrollPane best = null;
                                    int bestH = 0;
                                    for (JScrollPane sp : allScrolls(cp, new ArrayList<>())) {
                                        java.awt.Component v = sp.getViewport().getView();
                                        if (v == null || !sp.isShowing()) continue;
                                        if (sp.getLocationOnScreen().x < mid) continue;
                                        int hh = v.getPreferredSize().height;
                                        if (hh > bestH) { bestH = hh; best = sp; }
                                    }
                                    if (best != null && best.getViewport().getView() instanceof JComponent v) {
                                        v.setSize(v.getPreferredSize());
                                        layoutDeep(v);
                                        target = v;
                                    }
                                }
                                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                                        Math.max(1, target.getWidth()), Math.max(1, target.getHeight()),
                                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                                java.awt.Graphics2D g = img.createGraphics();
                                target.printAll(g);
                                g.dispose();
                                javax.imageio.ImageIO.write(img, "png", new java.io.File(png));
                                System.out.println("[layout] snap " + img.getWidth() + "x" + img.getHeight() + " → " + png);
                            } catch (Exception ex) { ex.printStackTrace(); }
                            System.exit(0);
                        }).start();
                    }
                } catch (Exception e) { e.printStackTrace(); System.exit(1); }
            });
            return;
        }
        if (args.length > 1 && args[0].equals("skelsheet")) {   // bảng thumbnail skeleton (soi ảnh xem trước)
            new MapToolApp().skelSheet(args);
            return;
        }
        if (args.length > 1 && args[0].equals("layoutbench")) { // ĐO thời gian vẽ 1 khung hình
            new MapToolApp().layoutBench(args);
            return;
        }
        if (args.length > 1 && args[0].equals("spineadd")) {    // tự kiểm THÊM NODE SPINE (không đụng client)
            new MapToolApp().spineAddTest(args);
            return;
        }
        if (args.length > 1 && args[0].equals("spineswap")) {   // tự kiểm ĐỔI SKELETON (không đụng client)
            new MapToolApp().spineSwapTest(args);
            return;
        }
        if (args.length > 2 && args[0].equals("spinesnap")) {   // render 1 skeleton ra PNG để soi mắt
            // spinesnap <thưMụcSpine> <out.png> [t=<giây>] [noblend]
            float t = 0f;
            boolean noBlend = false;
            for (int i = 3; i < args.length; i++) {
                if (args[i].startsWith("t=")) t = Float.parseFloat(args[i].substring(2));
                else if (args[i].equals("noblend")) noBlend = true;
                else if (args[i].equals("generic")) com.apex.maptool.spine.BlendComposite.FORCE_GENERIC = true;
            }
            System.exit(com.apex.maptool.spine.SpineSnap.run(args[1], args[2], t, noBlend));
            return;
        }
        if (args.length > 1 && args[0].equals("uisnap")) {   // chụp giao diện Map Editor ra PNG
            final String outPng = args[1];
            SwingUtilities.invokeLater(() -> {
                try {
                    com.apex.maptool.ui.Theme.apply();
                    // uisnap <png> [map=<id>] [w=<px>] [h=<px>] [side]  — token CÓ TÊN cho khỏi lẫn
                    int snapMap = 1, snapW = 1400, snapH = 900;
                    for (int i = 2; i < args.length; i++) {
                        String a = args[i];
                        if (a.startsWith("map=")) snapMap = parseInt(a.substring(4), 1);
                        else if (a.startsWith("w=")) snapW = parseInt(a.substring(2), 1400);
                        else if (a.startsWith("h=")) snapH = parseInt(a.substring(2), 900);
                    }
                    JComponent root = new MapToolApp().startEmbedded(snapMap);
                    if (root == null) { System.err.println("[uisnap] startEmbedded trả null"); System.exit(1); }
                    JFrame f = new JFrame("uisnap");
                    f.setContentPane(root);
                    f.setSize(snapW, snapH);
                    f.setVisible(true);                       // phải hiện thật thì layout mới đúng
                    // Chờ layout + vẽ xong 1 nhịp rồi mới chụp, không thì ra ảnh trắng.
                    new javax.swing.Timer(1500, ev -> {
                        ((javax.swing.Timer) ev.getSource()).stop();
                        try {
                            // "side" = chụp RIÊNG thanh bên ở chiều cao ĐẦY ĐỦ. Cần vì cửa sổ bị
                            // kẹp theo chiều cao màn hình nên mấy mục cuối luôn nằm ngoài khung.
                            JComponent target = root;
                            if (java.util.Arrays.asList(args).contains("side")) {
                                JScrollPane sp = findTallestScroll(root, null);
                                if (sp != null && sp.getViewport().getView() instanceof JComponent v) {
                                    v.setSize(v.getPreferredSize());
                                    layoutDeep(v);
                                    target = v;
                                }
                            }
                            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                                    Math.max(1, target.getWidth()), Math.max(1, target.getHeight()),
                                    java.awt.image.BufferedImage.TYPE_INT_RGB);
                            java.awt.Graphics2D g = img.createGraphics();
                            target.printAll(g);
                            g.dispose();
                            javax.imageio.ImageIO.write(img, "png", new java.io.File(outPng));
                            System.out.println("[uisnap] " + img.getWidth() + "x" + img.getHeight() + " → " + outPng);
                        } catch (Exception ex) { ex.printStackTrace(); }
                        System.exit(0);
                    }).start();
                } catch (Exception e) { e.printStackTrace(); System.exit(1); }
            });
            return;
        }
        if (args.length > 2 && args[0].equals("mapexport")) {   // dựng file xuất từ DB (CHỈ ĐỌC)
            new MapToolApp().mapExportCli(args);
            return;
        }
        if (args.length > 3 && args[0].equals("spinedump")) {   // đối chiếu pose xương với spine-csharp thật
            System.exit(com.apex.maptool.spine.SpineDump.run(args[1], args[2], args[3]));
            return;
        }
        if (args.length > 0 && args[0].equals("hqtest")) {   // tự test bộ ghi asset Hào Quang (CHỈ ĐỌC client)
            System.exit(com.apex.maptool.unity.HaoQuangTest.run(args.length > 1 ? args[1] : null));
            return;
        }
        if (args.length > 0 && args[0].equals("pvtest")) {   // tự test Player Viewer (headless, CHỈ ĐỌC)
            // pvtest [thưMụcGốc] [sốLượng] [fit]  — mặc định <resource>/Player, 12 skeleton
            String pvRoot = args.length > 1 ? args[1] : null;
            int pvN = args.length > 2 ? parseInt(args[2], 12) : 12;
            boolean pvFit = java.util.Arrays.asList(args).contains("fit");
            System.exit(com.apex.maptool.spine.PlayerViewTest.run(pvRoot, pvN, pvFit));
            return;
        }
        if (args.length > 0 && args[0].equals("player")) {   // mở thẳng Player Viewer (debug / chụp UI)
            // player [png=<file>] [wait=<ms>] [tab=0|1|2] [anim=<tên>] [bone=<tên>]
            //   — có png thì chụp giao diện rồi thoát (tab/anim/bone chỉ để chụp đúng chỗ cần soi)
            final String pvPng = argVal(args, "png=", null);
            final int pvWait = parseInt(argVal(args, "wait=", "2500"), 2500);
            final int pvTab = parseInt(argVal(args, "tab=", "0"), 0);
            final String pvAnim = argVal(args, "anim=", null);
            final String pvBone = argVal(args, "bone=", null);
            SwingUtilities.invokeLater(() -> {
                com.apex.maptool.ui.Theme.apply();
                try {
                    var pf = new com.apex.maptool.ui.PlayerViewerFrame(new ToolConfig());
                    pf.setLocationRelativeTo(null);
                    pf.setVisible(true);
                    if (pvPng == null) return;
                    // Thư mục phải đặt SAU lượt nạp mặc định mà constructor đã xếp hàng, nếu không
                    // lượt mặc định (mới hơn) sẽ thắng đúng theo bộ đánh số chống-đè của tool.
                    final String pvFolder = argVal(args, "folder=", null);
                    new javax.swing.Timer(Math.max(100, pvWait / 6), ev -> {
                        ((javax.swing.Timer) ev.getSource()).stop();
                        if (pvFolder != null) pf.loadFolder(pvFolder);
                        if (argVal(args, "fit=", null) != null) pf.setRealSize(false);
                    }).start();
                    // Chọn sau khi skeleton nạp xong (SwingWorker) → hẹn ở nửa thời gian chờ.
                    new javax.swing.Timer(Math.max(200, pvWait / 2), ev -> {
                        ((javax.swing.Timer) ev.getSource()).stop();
                        pf.selectTab(pvTab);
                        pf.selectAnim(pvAnim);
                        if (pvBone != null) pf.selectBonePublic(pvBone);
                        String hqMode = argVal(args, "hqmode=", null);
                        if (hqMode != null) {
                            pf.debugHaoQuang(hqMode, argVal(args, "hqback=", null), argVal(args, "hqfront=", null));
                        }
                        if (argVal(args, "play=", null) != null) pf.startPlayPublic();
                    }).start();
                    // Chờ SwingWorker nạp xong skeleton + vẽ 1 nhịp, không thì chụp ra khung rỗng.
                    new javax.swing.Timer(pvWait, ev -> {
                        ((javax.swing.Timer) ev.getSource()).stop();
                        try {
                            java.awt.Component t = pf.getContentPane();
                            var img = new java.awt.image.BufferedImage(Math.max(1, t.getWidth()),
                                    Math.max(1, t.getHeight()), java.awt.image.BufferedImage.TYPE_INT_RGB);
                            java.awt.Graphics2D g = img.createGraphics();
                            t.printAll(g);
                            g.dispose();
                            javax.imageio.ImageIO.write(img, "png", new java.io.File(pvPng));
                            System.out.println("[player] " + img.getWidth() + "x" + img.getHeight() + " → " + pvPng);
                        } catch (Exception ex) { ex.printStackTrace(); }
                        System.exit(0);
                    }).start();
                } catch (Exception e) { e.printStackTrace(); System.exit(1); }
            });
            return;
        }
        if (args.length > 0 && args[0].equals("layouttest")) {   // tự test Bố cục Map (headless, KHÔNG đụng file client)
            int[] ids = new int[Math.max(0, args.length - 1)];
            for (int i = 1; i < args.length; i++) ids[i - 1] = parseInt(args[i], 1);
            System.exit(com.apex.maptool.LayoutSelfTest.run(ids));
            return;
        }
        // arg đầu là số (map id) → vào thẳng Map Editor (giữ workflow run.bat 1 cũ)
        if (args.length > 0 && args[0].matches("-?\\d+")) {
            SwingUtilities.invokeLater(() -> new MapToolApp().start(args));
            return;
        }
        if (args.length > 1 && args[0].equals("appsnap")) {   // chụp NGUYÊN vỏ app (kiểm thanh bên)
            // appsnap <png> [wait=<ms>] [tool=map|shop|layout|equip|gift|player] [size=1600x900]
            SwingUtilities.invokeLater(() -> snapApp(args[1],
                    parseInt(argVal(args, "wait=", "4000"), 4000), argVal(args, "tool=", null),
                    argVal(args, "size=", null)));
            return;
        }
        SwingUtilities.invokeLater(MapToolApp::launchLauncher);
    }

    /**
     * Chụp cả cửa sổ chính ra PNG rồi thoát. Có vì "app chạy được" KHÔNG chứng minh giao diện lành —
     * Swing nuốt exception lúc vẽ, nút hỏng chỉ hiện ra ô trống (đúng cái bẫy {@code Theme.btn(null)}).
     */
    private static void snapApp(String png, int waitMs, String tool, String size) {
        com.apex.maptool.ui.Theme.apply();
        ToolConfig cfg = new ToolConfig();
        var mf = new com.apex.maptool.ui.MainFrame(cfg, () -> new MapToolApp().startEmbedded());
        // size=1600x900 → ép đúng cỡ cần soi (checklist handoff: 1600×900 và 1920×1080)
        if (size != null && size.matches("\\d+x\\d+")) {
            String[] wh = size.split("x");
            mf.setExtendedState(java.awt.Frame.NORMAL);
            mf.setMinimumSize(new java.awt.Dimension(200, 200));
            mf.setSize(parseInt(wh[0], 1600), parseInt(wh[1], 900));
            mf.setLocationRelativeTo(null);
        }
        mf.setVisible(true);
        if (tool != null) SwingUtilities.invokeLater(() -> mf.openTool(tool));
        new javax.swing.Timer(waitMs, ev -> {
            ((javax.swing.Timer) ev.getSource()).stop();
            try {
                java.awt.Component t = mf.getContentPane();
                var img = new java.awt.image.BufferedImage(Math.max(1, t.getWidth()),
                        Math.max(1, t.getHeight()), java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = img.createGraphics();
                t.printAll(g);
                g.dispose();
                javax.imageio.ImageIO.write(img, "png", new java.io.File(png));
                System.out.println("[appsnap] " + img.getWidth() + "x" + img.getHeight() + " → " + png);
            } catch (Exception ex) { ex.printStackTrace(); }
            System.exit(0);
        }).start();
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

    /** Mọi JScrollPane trong cây component. */
    private static List<JScrollPane> allScrolls(Component c, List<JScrollPane> out) {
        if (c instanceof JScrollPane sp) out.add(sp);
        if (c instanceof Container ct) for (Component k : ct.getComponents()) allScrolls(k, out);
        return out;
    }

    /** JScrollPane có nội dung CAO nhất trong cây — chính là thanh bên của Map Editor. */
    private static JScrollPane findTallestScroll(Component c, JScrollPane best) {
        if (c instanceof JScrollPane sp && sp.getViewport().getView() != null) {
            int h = sp.getViewport().getView().getPreferredSize().height;
            if (best == null || h > best.getViewport().getView().getPreferredSize().height) best = sp;
        }
        if (c instanceof Container ct) for (Component k : ct.getComponents()) best = findTallestScroll(k, best);
        return best;
    }

    /** Ép layout cả cây con sau khi đổi size thủ công (printAll không tự làm). */
    private static void layoutDeep(Component c) {
        c.doLayout();
        if (c instanceof Container ct) for (Component k : ct.getComponents()) layoutDeep(k);
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

    /**
     * Headless CHỈ ĐỌC: dựng file xuất thẳng từ DB, không mở GUI, không ghi DB.
     * Dùng để đối chiếu bản xuất với file thật của client trước khi tin nút bấm trên UI.
     *
     * <pre>
     * mapexport npcdata &lt;fileRa&gt;
     * mapexport sql &lt;mapId&gt; &lt;fileRa&gt;
     * </pre>
     */
    private void mapExportCli(String[] args) {
        try {
            var db = new com.apex.maptool.db.Db(cfg);
            var dao = new com.apex.maptool.db.MapInfoDao(db);
            String mode = args[1];
            if (mode.equals("npcdata")) {
                var all = new java.util.TreeMap<>(dao.loadAllNpcs());
                boolean merge = args.length > 3 && args[3].equals("merge");
                String json;
                if (merge) {
                    Path cf = clientNpcDataFile();
                    String oldJson = (cf != null && Files.exists(cf)) ? Files.readString(cf) : null;
                    if (oldJson == null) { System.err.println("[mapexport] không thấy NPCData.json client để gộp"); System.exit(3); }
                    var d = com.apex.maptool.db.MapExport.diffNpcData(oldJson, all);
                    System.out.println("[mapexport] đối chiếu client: giống " + d.same()
                            + " · khác " + d.changed().size() + " · chỉ-client " + d.onlyOld()
                            + " (mất " + d.lostNpc() + " NPC nếu ghi đúng-y-DB) · chỉ-DB " + d.onlyNew());
                    json = com.apex.maptool.db.MapExport.npcDataJsonMerged(all, oldJson);
                } else {
                    json = com.apex.maptool.db.MapExport.npcDataJson(all);
                }
                Files.writeString(Paths.get(args[2]), json, java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("[mapexport] NPCData.json ← DB" + (merge ? " + GỘP client" : "") + ": "
                        + all.size() + " map DB, "
                        + com.apex.maptool.db.MapExport.countMapsWithNpc(all) + " map có NPC → " + args[2]);
            } else if (mode.equals("sql")) {
                int mapId = parseInt(args[2], 1);
                var row = dao.load(mapId);
                String sql = com.apex.maptool.db.MapExport.sqlHeader()
                        + com.apex.maptool.db.MapExport.sqlForMap(mapId, row.name, row.markers);
                java.nio.file.Files.writeString(java.nio.file.Paths.get(args[3]), sql,
                        java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("[mapexport] SQL map " + mapId + " (" + row.markers.size() + " marker) → " + args[3]);
            } else if (mode.equals("verify")) {
                // Round-trip: DB → marker → JSON xuất ra. Phải BẰNG cột gốc, nếu không là xuất SQL
                // gây mất field. Chạy trên MỌI map để chắc, không lấy mẫu.
                var gson = new com.google.gson.Gson();
                int nOk = 0, nBad = 0, nEmpty = 0;
                for (int mapId : dao.loadMapNames().keySet().stream().sorted().toList()) {
                    var raw = dao.loadRawColumns(mapId);
                    if (raw.isEmpty()) continue;
                    var row = dao.load(mapId);
                    var pairs = new String[][]{
                            {"list_npcs", com.apex.maptool.db.MapInfoDao.toJsonPublic(row.markers, com.apex.maptool.model.Marker.Kind.NPC)},
                            {"list_enemies", com.apex.maptool.db.MapInfoDao.toJsonPublic(row.markers, com.apex.maptool.model.Marker.Kind.ENEMY)},
                            {"list_gate_way", com.apex.maptool.db.MapInfoDao.toJsonPublic(row.markers, com.apex.maptool.model.Marker.Kind.GATEWAY)},
                            {"list_arrive_position", com.apex.maptool.db.MapInfoDao.toJsonPublic(row.markers, com.apex.maptool.model.Marker.Kind.ARRIVE)},
                    };
                    for (String[] p : pairs) {
                        String before = raw.get(p[0]);
                        if (before == null || before.isBlank()) { if (!p[1].equals("[]")) { nBad++; System.out.println("  map " + mapId + " " + p[0] + ": cột rỗng nhưng xuất ra " + p[1]); } else nEmpty++; continue; }
                        var a = gson.fromJson(before, com.google.gson.JsonElement.class);
                        var b = gson.fromJson(p[1], com.google.gson.JsonElement.class);
                        if (a.equals(b)) nOk++;
                        else { nBad++; System.out.println("  map " + mapId + " " + p[0] + " LỆCH\n    DB : " + before + "\n    ra : " + p[1]); }
                    }
                }
                System.out.println("[mapexport verify] cột khớp=" + nOk + " · cột rỗng=" + nEmpty + " · LỆCH=" + nBad);
                if (nBad > 0) System.exit(1);
            } else {
                System.err.println("[mapexport] mode lạ: " + mode);
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("[mapexport] FAIL: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Tự kiểm ĐỔI SKELETON, headless, KHÔNG ĐỤNG file client:
     * nạp map → đổi skeleton của node Spine đầu tiên (hoặc node chỉ định) → ghi ra file TẠM →
     * nạp lại file tạm → đối chiếu guid/tên/thư mục có đúng đã đổi không.
     *
     * <pre>spineswap &lt;mapId&gt; [tênSkeletonMới] [tênNode]</pre>
     */
    private void spineSwapTest(String[] args) {
        try {
            int mapId = parseInt(args[1], 13);
            String wantSkel = args.length > 2 ? args[2] : null;
            String wantNode = args.length > 3 ? args[3] : null;

            GuidIndex gi = new GuidIndex();
            gi.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            var cat = new com.apex.maptool.unity.SpineCatalog(gi, cfg.assetsRoot());
            System.out.println("[spineswap] danh mục skeleton: " + cat.size());

            var loader = new MapSceneLoader(gi, new MaterialResolver(gi), new TextureCache(),
                    cfg.pixelsPerUnit());
            Path prefab = cfg.mapPrefab(mapId);
            MapScene scene = loader.load(prefab, mapId);

            MapScene.Node node = null;
            for (MapScene.Node n : scene.nodes()) {
                if (!n.hasFx(MapScene.EffectKind.SPINE)) continue;
                if (wantNode == null || n.name.contains(wantNode)) { node = n; break; }
            }
            if (node == null) { System.err.println("[spineswap] map " + mapId + " không có node Spine nào"); System.exit(3); }

            var oldFx = node.fxOf(MapScene.EffectKind.SPINE);
            String oldGuid = oldFx.assetGuid, oldName = oldFx.name;
            System.out.println("[spineswap] node '" + node.name + "' đang dùng '" + oldName + "' guid=" + oldGuid);

            com.apex.maptool.unity.SpineCatalog.Entry target = null;
            for (var e : cat.all()) {
                if (e.skeletonGuid.equalsIgnoreCase(oldGuid)) continue;          // phải là cái KHÁC
                if (wantSkel != null && !e.name.equalsIgnoreCase(wantSkel)) continue;
                if (e.materialGuid == null) continue;                            // chọn cái đủ material
                target = e; break;
            }
            if (target == null) { System.err.println("[spineswap] không tìm được skeleton đích"); System.exit(3); }
            System.out.println("[spineswap] đổi sang '" + target.name + "' guid=" + target.skeletonGuid
                    + " material=" + target.materialGuid + " (" + target.shortPath + ")");

            String err = scene.setSkeleton(node, target);
            if (err != null) { System.err.println("[spineswap] setSkeleton fail: " + err); System.exit(1); }

            Path tmp = java.nio.file.Files.createTempFile("spineswap-", ".prefab");
            var res = com.apex.maptool.unity.MapSceneWriter.saveAs(scene, null, tmp);
            for (String l : res.log) System.out.println("    " + l);
            System.out.println("[spineswap] ghi ra " + tmp + " · field=" + res.fieldsWritten
                    + " · cảnh báo=" + res.warnings + " · lỗi=" + res.failed.size());

            // ĐỌC LẠI file vừa ghi — đây mới là bằng chứng, không tin mỗi "đã ghi xong"
            MapScene re = loader.load(tmp, mapId);
            MapScene.Node back = null;
            for (MapScene.Node n : re.nodes()) if (n.goAnchor == node.goAnchor) { back = n; break; }
            if (back == null) { System.err.println("[spineswap] đọc lại KHÔNG thấy node"); System.exit(1); }
            var backFx = back.fxOf(MapScene.EffectKind.SPINE);
            boolean okGuid = target.skeletonGuid.equalsIgnoreCase(backFx.assetGuid);
            boolean okName = target.name.equals(backFx.name);
            boolean okFolder = backFx.folder != null && backFx.folder.equals(target.folder);
            System.out.println("[spineswap] đọc lại: guid=" + backFx.assetGuid + " (" + (okGuid ? "ĐẠT" : "HỎNG") + ")"
                    + " · tên='" + backFx.name + "' (" + (okName ? "ĐẠT" : "HỎNG") + ")"
                    + " · thư mục " + (okFolder ? "ĐẠT" : "HỎNG: " + backFx.folder));

            // và phải VẼ được bằng skeleton mới
            var sc = com.apex.maptool.spine.SpineCharacter.load(backFx.folder);
            System.out.println("[spineswap] nạp skeleton mới để vẽ: " + (sc != null ? "ĐẠT (bone=" + sc.data.bones.size() + ")" : "HỎNG"));

            java.nio.file.Files.deleteIfExists(tmp);
            boolean all = okGuid && okName && okFolder && sc != null && res.ok();
            System.out.println("[spineswap] ===> " + (all ? "TẤT CẢ ĐẠT" : "CÓ MỤC HỎNG"));
            System.exit(all ? 0 : 1);
        } catch (Exception e) {
            System.err.println("[spineswap] FAIL: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Tự kiểm THÊM NODE SPINE, headless, KHÔNG ĐỤNG file client:
     * nạp map → thêm node Spine mới → ghi ra file TẠM → nạp lại → đối chiếu node có thật,
     * đúng skeleton, đúng cha, và vẽ được.
     *
     * <pre>spineadd &lt;mapId&gt; [tênSkeleton] [tênNodeCha]</pre>
     */
    private void spineAddTest(String[] args) {
        try {
            int mapId = parseInt(args[1], 13);
            String wantSkel = args.length > 2 ? args[2] : null;
            String wantParent = args.length > 3 ? args[3] : null;

            GuidIndex gi = new GuidIndex();
            gi.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            var cat = new com.apex.maptool.unity.SpineCatalog(gi, cfg.assetsRoot());
            var loader = new MapSceneLoader(gi, new MaterialResolver(gi), new TextureCache(), cfg.pixelsPerUnit());
            MapScene scene = loader.load(cfg.mapPrefab(mapId), mapId);
            int nodesBefore = scene.nodes().size();

            MapScene.Node parent = null;
            for (MapScene.Node n : scene.nodes()) {
                if (n.trAnchor == 0) continue;
                if (wantParent != null) { if (n.name != null && n.name.contains(wantParent)) { parent = n; break; } }
                else if (n.parentTr == 0) { parent = n; break; }        // root
            }
            if (parent == null) { System.err.println("[spineadd] không tìm được node cha"); System.exit(3); }

            com.apex.maptool.unity.SpineCatalog.Entry skel = null;
            for (var e : cat.all()) {
                if (e.materialGuid == null) continue;
                if (wantSkel != null && !e.name.equalsIgnoreCase(wantSkel)) continue;
                skel = e; break;
            }
            if (skel == null) { System.err.println("[spineadd] không tìm được skeleton"); System.exit(3); }
            System.out.println("[spineadd] cha='" + parent.name + "' · skeleton='" + skel.name + "' (" + skel.shortPath + ")");

            var added = scene.addSpineNode(parent, null, skel, "animation", 3.5, 2.0, 1.0);
            if (added == null) { System.err.println("[spineadd] addSpineNode trả null"); System.exit(1); }
            System.out.println("[spineadd] tạo node '" + added.name + "' anchors go=" + added.goAnchor
                    + " tr=" + added.trAnchor + " mf=" + added.mfAnchor + " rend=" + added.rendAnchor
                    + " fx=" + added.fxAnchor);

            Path tmp = java.nio.file.Files.createTempFile("spineadd-", ".prefab");
            var res = com.apex.maptool.unity.MapSceneWriter.saveAs(scene, null, tmp);
            for (String l : res.log) System.out.println("    " + l);
            System.out.println("[spineadd] ghi ra " + tmp + " · field=" + res.fieldsWritten
                    + " · cảnh báo=" + res.warnings + " · lỗi=" + res.failed.size());

            // ĐỌC LẠI — bằng chứng thật
            MapScene re = loader.load(tmp, mapId);
            MapScene.Node back = null;
            for (MapScene.Node n : re.nodes()) if (n.goAnchor == added.goAnchor) { back = n; break; }
            boolean okExist = back != null;
            boolean okCount = re.nodes().size() == nodesBefore + 1;
            boolean okSkel = false, okParent = false, okDraw = false, okRend = false;
            if (back != null) {
                var bf = back.fxOf(MapScene.EffectKind.SPINE);
                okSkel = bf != null && skel.skeletonGuid.equalsIgnoreCase(bf.assetGuid);
                okParent = back.parentTr == parent.trAnchor;
                okRend = back.hasRenderer && !back.isSprite;
                if (bf != null && bf.folder != null) {
                    okDraw = com.apex.maptool.spine.SpineCharacter.load(bf.folder) != null;
                }
                System.out.println("[spineadd] đọc lại: tên='" + back.name + "'"
                        + " · skeleton=" + (bf == null ? "(không có Fx SPINE)" : bf.name + "/" + bf.assetGuid)
                        + " · cha=&" + back.parentTr + " (mong &" + parent.trAnchor + ")"
                        + " · renderer=" + (back.hasRenderer ? (back.isSprite ? "Sprite" : "Mesh") : "KHÔNG"));
            }
            // cha phải THẬT SỰ liệt kê con mới trong m_Children
            boolean okChildRef = false;
            for (MapScene.Node n : re.nodes()) {
                if (n.trAnchor != parent.trAnchor) continue;
                for (long ch : n.childTr) if (ch == added.trAnchor) { okChildRef = true; break; }
            }

            System.out.println("[spineadd] node tồn tại=" + ok(okExist) + " · số node +1=" + ok(okCount)
                    + " · đúng skeleton=" + ok(okSkel) + " · đúng cha=" + ok(okParent)
                    + " · có trong m_Children của cha=" + ok(okChildRef)
                    + " · là MeshRenderer=" + ok(okRend) + " · vẽ được=" + ok(okDraw));
            java.nio.file.Files.deleteIfExists(tmp);
            boolean all = okExist && okCount && okSkel && okParent && okChildRef && okRend && okDraw && res.ok();
            System.out.println("[spineadd] ===> " + (all ? "TẤT CẢ ĐẠT" : "CÓ MỤC HỎNG"));
            System.exit(all ? 0 : 1);
        } catch (Exception e) {
            System.err.println("[spineadd] FAIL: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static String ok(boolean b) { return b ? "ĐẠT" : "HỎNG"; }

    /**
     * Dựng BẢNG THUMBNAIL skeleton bằng ĐÚNG đường vẽ của khung xem trước trong hộp thoại chọn —
     * để nhìn thấy ngay cái nào vẽ hỏng, thay vì mở tool click từng cái.
     *
     * <pre>skelsheet &lt;out.png&gt; [số=48] [bỏQua=0] [lọc]</pre>
     */
    private void skelSheet(String[] args) {
        try {
            String out = args[1];
            int count = args.length > 2 ? parseInt(args[2], 48) : 48;
            int skip = args.length > 3 ? parseInt(args[3], 0) : 0;
            String filter = args.length > 4 ? args[4] : null;

            GuidIndex gi = new GuidIndex();
            gi.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            var cat = new com.apex.maptool.unity.SpineCatalog(gi, cfg.assetsRoot());
            var list = cat.search(filter);
            System.out.println("[sheet] danh mục " + cat.size() + " · sau lọc " + list.size());

            int cell = 150, cols = 8, pad = 18;
            int n = Math.min(count, Math.max(0, list.size() - skip));
            int rows = (n + cols - 1) / cols;
            java.awt.image.BufferedImage sheet = new java.awt.image.BufferedImage(
                    cols * cell, Math.max(1, rows) * (cell + pad), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D sg = sheet.createGraphics();
            sg.setColor(new java.awt.Color(0x14141A));
            sg.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            sg.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));

            int empty = 0, failed = 0;
            for (int i = 0; i < n; i++) {
                var e = list.get(skip + i);
                int cx = (i % cols) * cell, cy = (i / cols) * (cell + pad);
                java.awt.image.BufferedImage th =
                        new java.awt.image.BufferedImage(cell, cell, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = th.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new java.awt.Color(0x2A2A33));
                g.fillRect(0, 0, cell, cell);
                boolean realBox = false, loaded = false;
                try {
                    var sc = com.apex.maptool.spine.SpineCharacter.load(e.folder);
                    if (sc != null) {
                        loaded = true;
                        var anim = sc.data.animations.values().stream().findFirst().orElse(null);
                        realBox = sc.renderFit(g, cell, cell, 10, anim);
                    }
                } catch (Exception ignored) { }
                g.dispose();
                if (!loaded) failed++;
                else if (!realBox) empty++;
                sg.drawImage(th, cx, cy, null);
                sg.setColor(loaded ? (realBox ? new java.awt.Color(0xB8C0CC) : new java.awt.Color(0xF0B429))
                                   : new java.awt.Color(0xFF6B6B));
                String nm = e.name.length() > 22 ? e.name.substring(0, 21) + "…" : e.name;
                sg.drawString(nm, cx + 4, cy + cell + 13);
            }
            sg.dispose();
            javax.imageio.ImageIO.write(sheet, "png", new java.io.File(out));
            System.out.println("[sheet] " + n + " skeleton → " + out
                    + " · không nạp được=" + failed + " · hộp bao rỗng (dùng hộp khai báo)=" + empty);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("[sheet] FAIL: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * ĐO thời gian vẽ 1 khung hình của Bố cục Map, có/không hoà màu blend, để biết lag nằm ở đâu
     * thay vì đoán.
     *
     * <pre>layoutbench &lt;mapId&gt; [sốKhung=30]</pre>
     */
    private void layoutBench(String[] args) {
        try {
            int mapId = parseInt(args[1], 8);
            int frames = args.length > 2 ? parseInt(args[2], 30) : 30;
            GuidIndex gi = new GuidIndex();
            gi.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
            var texCache = new TextureCache();
            var loader = new MapSceneLoader(gi, new MaterialResolver(gi), texCache, cfg.pixelsPerUnit());
            MapScene scene = loader.load(cfg.mapPrefab(mapId), mapId);

            int nSpine = 0, nBlendSlot = 0;
            for (MapScene.Node n : scene.nodes()) {
                if (!n.hasFx(MapScene.EffectKind.SPINE)) continue;
                nSpine++;
                var f = n.fxOf(MapScene.EffectKind.SPINE);
                if (f == null || f.folder == null) continue;
                var sc = com.apex.maptool.spine.SpineCharacter.load(f.folder);
                if (sc == null) continue;
                for (var s : sc.data.slots) if (s.blend != com.apex.maptool.spine.SpineData.BLEND_NORMAL) nBlendSlot++;
            }
            System.out.println("[bench] map " + mapId + ": " + scene.nodes().size() + " node · "
                    + nSpine + " node Spine · " + nBlendSlot + " slot có blend đặc biệt");

            final int w = 1200, h = 800;
            final double[][] res = new double[2][];
            Runnable job = () -> {
                var c = new com.apex.maptool.ui.MapLayoutCanvas(texCache, cfg.pixelsPerUnit());
                c.setSize(w, h);
                c.setScene(scene);
                c.setShowSprites(true);
                c.doLayout();
                c.fitView();
                c.setPlayEffects(true);
                for (int mode = 0; mode < 2; mode++) {
                    com.apex.maptool.spine.SpineRenderer.BLEND_ENABLED = (mode == 0);
                    java.awt.image.BufferedImage img =
                            new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    // 5 khung làm nóng (JIT + cache ảnh) rồi mới đo
                    for (int i = 0; i < 5; i++) { c.setEffectTime(i * 0.11); paintOnce(c, img); }
                    double[] ms = new double[frames];
                    for (int i = 0; i < frames; i++) {
                        c.setEffectTime(i * 0.037);
                        long t0 = System.nanoTime();
                        paintOnce(c, img);
                        ms[i] = (System.nanoTime() - t0) / 1e6;
                    }
                    res[mode] = ms;
                }
                com.apex.maptool.spine.SpineRenderer.BLEND_ENABLED = true;
            };
            if (java.awt.GraphicsEnvironment.isHeadless()) job.run();
            else SwingUtilities.invokeAndWait(job);

            for (int mode = 0; mode < 2; mode++) {
                double[] ms = res[mode].clone();
                java.util.Arrays.sort(ms);
                double sum = 0; for (double v : ms) sum += v;
                System.out.printf(java.util.Locale.ROOT,
                        "[bench] blend=%-3s  trung bình %6.1f ms  ·  giữa %6.1f ms  ·  xấu nhất %6.1f ms  ⇒ %4.1f fps%n",
                        mode == 0 ? "BẬT" : "TẮT", sum / ms.length, ms[ms.length / 2], ms[ms.length - 1],
                        1000.0 / (sum / ms.length));
            }
            System.exit(0);
        } catch (Exception e) {
            System.err.println("[bench] FAIL: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void paintOnce(com.apex.maptool.ui.MapLayoutCanvas c, java.awt.image.BufferedImage img) {
        java.awt.Graphics2D g = img.createGraphics();
        try { c.paint(g); } finally { g.dispose(); }
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
    public JComponent startEmbedded() { return startEmbedded(1); }

    /** Như trên nhưng mở sẵn map chỉ định (uisnap dùng để chụp đúng map cần soi). */
    public JComponent startEmbedded(int mapId) {
        if (!initCore()) return null;
        JPanel root = buildRoot();
        selectMapCombo(mapId);
        loadMap(mapId);
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
        panel.add(com.apex.maptool.ui.Theme.section("Xuất file cho team", buildExportPanel(), false));
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

    /**
     * Xuất FILE — dùng khi KHÔNG ghi thẳng được: máy không nối tới DB đích, hoặc thứ cần sửa nằm
     * ở client chứ không ở DB. Ghi thẳng DB thì đã có nút "★ Lưu DB" ở mục Thao tác.
     */
    private JComponent buildExportPanel() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 5));
        p.setOpaque(false);
        p.add(com.apex.maptool.ui.Theme.btn("⬆  SQL map đang mở", null, e -> exportSqlCurrent()));
        p.add(com.apex.maptool.ui.Theme.btn("⬆  SQL mọi bản nháp", null, e -> exportSqlAllDrafts()));
        p.add(com.apex.maptool.ui.Theme.btn("🧩  NPCData.json (client)",
                com.apex.maptool.ui.Theme.BTN_PURPLE, e -> exportNpcData()));
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

    // ─── Xuất file ─────────────────────────────────────────────
    /** Nhớ thư mục lần xuất trước — xuất SQL rồi xuất NPCData thường vào cùng một chỗ. */
    private static java.io.File lastExportDir;

    /**
     * Hộp thoại lưu + ghi UTF-8. Trả true nếu đã ghi.
     * Tự thêm đuôi nếu người dùng gõ tên trống đuôi, và hỏi lại khi đè file có sẵn.
     */
    private boolean saveTextFile(String suggestedName, String content, String title) {
        JFileChooser fc = new JFileChooser(lastExportDir);
        fc.setDialogTitle(title);
        fc.setSelectedFile(new java.io.File(lastExportDir, suggestedName));
        if (fc.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return false;
        java.io.File f = fc.getSelectedFile();
        int dot = suggestedName.lastIndexOf('.');
        String ext = dot > 0 ? suggestedName.substring(dot) : "";
        if (!ext.isEmpty() && !f.getName().toLowerCase().endsWith(ext)) f = new java.io.File(f.getPath() + ext);
        if (f.exists()) {
            int ch = JOptionPane.showConfirmDialog(null, "Đè file đã có?\n" + f.getPath(),
                    "File đã tồn tại", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ch != JOptionPane.YES_OPTION) return false;
        }
        try {
            java.nio.file.Files.writeString(f.toPath(), content, java.nio.charset.StandardCharsets.UTF_8);
            lastExportDir = f.getParentFile();
            return true;
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Ghi file fail:\n" + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void exportSqlCurrent() {
        var ms = canvas.getEditMarkers();
        String sql = com.apex.maptool.db.MapExport.sqlHeader()
                + com.apex.maptool.db.MapExport.sqlForMap(currentMapId, mapNames.get(currentMapId), ms);
        if (saveTextFile("uocrong_map_" + currentMapId + ".sql", sql, "Xuất SQL — map " + currentMapId)) {
            JOptionPane.showMessageDialog(null,
                    "Đã xuất SQL cho map " + currentMapId + " (" + ms.size() + " marker).\n\n"
                    + "Dev server chạy file này rồi RESTART server game mới ăn.",
                    "Xuất SQL", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * SQL cho MỌI map đang sửa dở: các bản nháp trong {@code work/} + map đang mở.
     * Map đang mở lấy bản TRONG RAM (đè bản nháp trên đĩa) vì đó mới là thứ đang nhìn thấy.
     */
    private void exportSqlAllDrafts() {
        java.util.TreeMap<Integer, java.util.List<com.apex.maptool.model.Marker>> byMap = new java.util.TreeMap<>();
        for (int id : localStore.draftMapIds()) {
            try {
                var ms = localStore.load(id);
                if (ms != null) byMap.put(id, ms);
            } catch (Exception ex) {
                System.err.println("[App] đọc nháp map " + id + " fail: " + ex.getMessage());
            }
        }
        byMap.put(currentMapId, canvas.getEditMarkers());

        StringBuilder sb = new StringBuilder(com.apex.maptool.db.MapExport.sqlHeader());
        for (var e : byMap.entrySet()) {
            sb.append(com.apex.maptool.db.MapExport.sqlForMap(e.getKey(), mapNames.get(e.getKey()), e.getValue()));
            sb.append('\n');
        }
        if (saveTextFile("uocrong_map_update.sql", sb.toString(), "Xuất SQL — " + byMap.size() + " map")) {
            JOptionPane.showMessageDialog(null,
                    "Đã xuất SQL cho " + byMap.size() + " map: " + byMap.keySet() + "\n\n"
                    + "(gồm mọi bản nháp trong work/ và map đang mở)\n"
                    + "Dev server chạy xong phải RESTART server game.",
                    "Xuất SQL", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * NPCData.json cho client Unity ({@code Assets/Resources/NPCData.json}).
     *
     * <p>Phải gom NPC của TẤT CẢ map chứ không riêng map đang mở — file này client đọc trọn gói,
     * xuất thiếu map là NPC map đó biến mất. Nền lấy từ DB, rồi đè bằng bản nháp local và bản
     * đang sửa trong RAM.
     */
    private void exportNpcData() {
        if (mapDao == null) { JOptionPane.showMessageDialog(null, "Chưa kết nối DB", "Lỗi", JOptionPane.ERROR_MESSAGE); return; }
        setCursorBusy(true);
        new SwingWorker<java.util.Map<Integer, java.util.List<com.apex.maptool.model.Marker>>, Void>() {
            @Override protected java.util.Map<Integer, java.util.List<com.apex.maptool.model.Marker>> doInBackground() throws Exception {
                var all = new java.util.TreeMap<>(mapDao.loadAllNpcs());       // nền: DB
                for (int id : localStore.draftMapIds()) {                     // đè: bản nháp
                    try {
                        var ms = localStore.load(id);
                        if (ms != null) all.put(id, ms);
                    } catch (Exception ignored) { }
                }
                all.put(currentMapId, canvas.getEditMarkers());               // đè: bản đang mở
                return all;
            }
            @Override protected void done() {
                setCursorBusy(false);
                try {
                    finishNpcData(get());
                } catch (Exception ex) {
                    Throwable c = ex.getCause() != null ? ex.getCause() : ex;
                    JOptionPane.showMessageDialog(null, "Xuất NPCData fail:\n" + c, "Lỗi", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** File NPCData.json của client theo config (có thể chưa tồn tại). */
    private Path clientNpcDataFile() {
        String repo = cfg.clientRepo();
        if (repo == null || repo.isBlank()) return null;
        return Paths.get(repo, "Assets", "Resources", "NPCData.json");
    }

    /**
     * Đối chiếu với file client đang có RỒI mới cho ghi.
     *
     * <p>Bắt buộc phải có bước này: đo trên client thật, 12 map (1500, 8000-8004, 10000-10005, 36000)
     * có NPC ở file client mà `map_info_config` không quản. Xuất "đúng theo DB" rồi ghi đè là mất
     * 20 NPC không kèn không trống. Nên mặc định là **giữ lại** mấy map đó, và muốn xuất đúng-y-DB
     * thì phải chọn có ý thức.
     */
    private void finishNpcData(java.util.Map<Integer, java.util.List<com.apex.maptool.model.Marker>> all) throws Exception {
        Path clientFile = clientNpcDataFile();
        String oldJson = (clientFile != null && Files.exists(clientFile)) ? Files.readString(clientFile) : null;

        String json;
        String note;
        if (oldJson != null) {
            var d = com.apex.maptool.db.MapExport.diffNpcData(oldJson, all);
            StringBuilder msg = new StringBuilder("<html><b>So với NPCData.json đang có trong client:</b><br><br>");
            msg.append("• Giống hệt: <b>").append(d.same()).append("</b> map<br>");
            msg.append("• Khác nội dung: <b>").append(d.changed().size()).append("</b> map<br>");
            msg.append("• Map mới có thêm: <b>").append(d.onlyNew().size()).append("</b>")
               .append(d.onlyNew().isEmpty() ? "" : " (" + d.onlyNew() + ")").append("<br>");
            if (d.anyLoss()) {
                msg.append("<br><font color='#ff6b6b'><b>⚠ ").append(d.onlyOld().size())
                   .append(" map có NPC ở file client mà DB không có</b></font><br>")
                   .append(d.onlyOld()).append("<br>")
                   .append("Ghi đúng-y-DB sẽ <b>xoá ").append(d.lostNpc()).append(" NPC</b> của mấy map đó.<br>");
            }
            msg.append("</html>");

            String[] opts = d.anyLoss()
                    ? new String[]{"Giữ NPC của map DB không quản (khuyên dùng)", "Xuất đúng y DB", "Huỷ"}
                    : new String[]{"Xuất", "Huỷ"};
            int ch = JOptionPane.showOptionDialog(null, new JLabel(msg.toString()), "NPCData.json",
                    JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
            if (ch < 0 || ch == opts.length - 1) return;               // Huỷ / đóng cửa sổ
            boolean merge = d.anyLoss() && ch == 0;
            json = merge ? com.apex.maptool.db.MapExport.npcDataJsonMerged(all, oldJson)
                         : com.apex.maptool.db.MapExport.npcDataJson(all);
            note = merge ? "Đã GIỮ NPC của " + d.onlyOld().size() + " map DB không quản." : "Xuất đúng y DB.";
        } else {
            json = com.apex.maptool.db.MapExport.npcDataJson(all);
            note = "Không tìm thấy NPCData.json trong client để đối chiếu"
                    + (clientFile == null ? " (chưa khai client.repo)" : ":\n" + clientFile);
        }

        int nMap = com.apex.maptool.db.MapExport.countMapsWithNpc(all);
        // Ghi thẳng vào client (có backup) hay lưu ra chỗ khác?
        if (clientFile != null) {
            String[] where = {"Ghi thẳng vào client (backup .bak)", "Lưu ra file khác…", "Huỷ"};
            int w = JOptionPane.showOptionDialog(null,
                    "<html>" + note.replace("\n", "<br>") + "<br><br>Ghi vào đâu?<br><code>"
                    + clientFile + "</code></html>",
                    "NPCData.json", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, where, where[0]);
            if (w == 2 || w < 0) return;
            if (w == 0) {
                if (Files.exists(clientFile)) {
                    Path bak = clientFile.resolveSibling("NPCData.json.bak_"
                            + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date()));
                    Files.copy(clientFile, bak);
                    System.out.println("[App] backup NPCData → " + bak);
                }
                Files.createDirectories(clientFile.getParent());
                Files.writeString(clientFile, json, java.nio.charset.StandardCharsets.UTF_8);
                JOptionPane.showMessageDialog(null,
                        "Đã ghi thẳng vào client (" + nMap + " map có NPC).\n" + clientFile
                        + "\n\nMở Unity cho re-import.",
                        "Xuất NPCData.json", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
        if (saveTextFile("NPCData.json", json, "Xuất NPCData.json cho client")) {
            JOptionPane.showMessageDialog(null,
                    note + "\n\nĐã xuất " + nMap + " map có NPC.\n"
                    + "Chép đè vào <client>/Assets/Resources/NPCData.json rồi cho Unity re-import.",
                    "Xuất NPCData.json", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void setCursorBusy(boolean busy) {
        java.awt.Window w = SwingUtilities.getWindowAncestor(canvas);
        if (w != null) w.setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
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

    /** Giá trị của token CÓ TÊN dạng {@code khoá=giá trị} trong argv (không có ⇒ {@code def}). */
    private static String argVal(String[] args, String key, String def) {
        for (String a : args) if (a.startsWith(key)) return a.substring(key.length());
        return def;
    }
}
