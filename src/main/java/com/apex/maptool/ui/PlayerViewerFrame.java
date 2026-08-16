package com.apex.maptool.ui;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.spine.SpineCharacter;
import com.apex.maptool.spine.SpineData;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Player Viewer — bản Java của {@code tools/honma-composer/player_viewer.py}.
 *
 * <p>Nạp MỘT thư mục Spine bất kỳ ({@code Player/{id}}, {@code HonMa/{id}}, {@code Effect/…}) rồi:
 * <ul>
 *   <li><b>Animation</b> — chọn skin, chọn animation, tua timeline, Chạy/Dừng, Lặp, tốc độ 0.1×→2×;</li>
 *   <li><b>Slot</b> — bật/tắt từng slot (vũ khí, đầu, hào quang, fx skill…);</li>
 *   <li><b>Bone</b> — kéo Δgóc / Δx / Δy / ×scaleX / ×scaleY của từng xương, thấy ngay tại chỗ.</li>
 * </ul>
 *
 * <h2>Bố cục (bản "UI Redesign")</h2>
 * <pre>
 *  thẻ nguồn Spine (1 hàng đường dẫn + 1 hàng chip trạng thái)
 *  ├ 272  Duyệt nhanh — lưới ô id chia theo dải, có ô tìm
 *  ├ auto khung xem   — thanh công cụ 1 hàng (kèm nhóm XEM THỬ mượn từ tab Hào Quang)
 *  └ 452  inspector   — Animation | Slot | Bone | Hào Quang
 * </pre>
 * Nhóm <i>Xem thử</i> (Chạy · FPS · Lặp · Nằm SAU player) và 2 công tắc ẩn/hiện lớp nằm NGAY trên
 * khung xem — nơi mắt đang nhìn — thay vì nằm trong rail phải.
 *
 * <h2>Khác bản Python ở ĐƯỜNG VẼ, không khác ở KẾT QUẢ</h2>
 * Bản Python đẩy lệnh qua tiến trình Node ({@code node-renderer/render.js}, spine-ts + CanvasKit)
 * rồi đọc PNG về; bản này pose &amp; vẽ thẳng bằng runtime Spine trong app — cùng runtime đã đối
 * chiếu từng số với spine-csharp của client (909/926 skeleton khớp tuyệt đối). Nhờ vậy không cần
 * Node, không ghi file tạm, và tua timeline là thấy liền.
 *
 * <p>Ba điểm ngữ nghĩa bám đúng {@code render.js} để hai bản cho ra CÙNG một hình:
 * <ol>
 *   <li>delta bone cộng/nhân vào pose local <b>sau</b> animation và <b>trước</b> constraint
 *       ({@code SpineSkeleton.setBoneDeltas});</li>
 *   <li>tắt slot = đặt alpha slot về 0, không phải bỏ qua lúc vẽ ({@code setHiddenSlots});</li>
 *   <li>"cỡ thật" = {@code native_px × scale(SkeletonData.asset) × 0.7 × 150 × zoom} —
 *       {@code 0.7} là localScale của GameObject "Spine" trong {@code Player.prefab}, có nó thì tỉ lệ
 *       player so với hào quang mới khớp game.</li>
 * </ol>
 */
public final class PlayerViewerFrame extends JFrame {

    // ── Hằng "cỡ thật" (giữ nguyên tên + giá trị của player_viewer.py) ──
    private static final double PX_PER_WORLD = 150.0;
    /** localScale của GameObject "Spine" trong {@code Player.prefab} — aura KHÔNG nhân số này. */
    public static final double PLAYER_SPINE_SCALE = 0.7;

    // ── Màu khung xem (bản thiết kế) ──
    private static final Color CANVAS_BG    = new Color(0x0b, 0x0b, 0x0e);
    private static final Color GRID_COLOR   = new Color(255, 255, 255, 9);    // rgba(255,255,255,.035)
    private static final Color AXIS_COLOR   = new Color(96, 165, 250, 115);   // rgba(96,165,250,.45)
    private static final Color GROUND_COLOR = new Color(52, 211, 153, 89);    // rgba(52,211,153,.35)
    private static final int GRID_STEP = 48;

    /** Cột theo bản thiết kế. */
    private static final int COL_BROWSE = 272;
    private static final int COL_INSPECTOR = 452;

    /** Thư mục gốc trong {@code Assets/AssetBundles/Resource} có chứa skeleton (bỏ Map*, Button, Popup…). */
    private static final String[] ROOTS = {
        "Player", "HonMa", "Enemy", "Npc", "PhuKien", "Pet", "Rong",
        "Effect", "HaoQuang", "MountFly", "Phithuyen", "Projectile", "CayDauThan", "DanhHieu"};

    /** Các dải id của lưới "Duyệt nhanh" (nhãn · cận dưới · cận trên). */
    private static final long[][] ID_RANGES = {{0, 99}, {100, 599}, {600, 5999}, {6000, Long.MAX_VALUE}};
    private static final String[] ID_RANGE_LABELS = {"0 – 99", "100 – 599", "600 – 5999", "6000+"};

    private final ToolConfig cfg;

    // ── trạng thái skeleton đang xem ──
    private Path folder;
    private SpineCharacter sc;
    /** {@code null} = thư mục KHÔNG phải nhân vật (không có SkeletonData.asset) ⇒ xem kiểu vừa khung. */
    private Float importScale;
    private final Set<String> hiddenSlots = new LinkedHashSet<>();
    private final Map<String, float[]> boneDeltas = new LinkedHashMap<>();
    private String selectedBone;

    // ── widget: thẻ nguồn ──
    private final JTextField txtFolder = new JTextField();
    private final JPanel chipRow = Theme.chipRow(8);
    private final Theme.Chip chState = new Theme.Chip("Chưa nạp thư mục Spine", null, 26, 12, false);
    private final Theme.Chip chSlot  = Theme.chip("— slot");
    private final Theme.Chip chBone  = Theme.chip("— bone");
    private final Theme.Chip chAnim  = Theme.chip("— animation");
    private final Theme.Chip chSkin  = Theme.chip("— skin");
    private final Theme.Chip chScale = Theme.monoChip("SkeletonData scale —");

    private final PvCanvas canvas = new PvCanvas();

    // ── widget: duyệt nhanh ──
    private final JComboBox<String> cbRoot = new JComboBox<>(ROOTS);
    private final JTextField txtRootFilter = new JTextField();
    private final JLabel lbFolderCount = Theme.label("0 mục", 12, Font.PLAIN, Theme.TEXT_DIM);
    private final IdGrid idGrid = new IdGrid();

    private final JComboBox<String> cbSkin = new JComboBox<>();
    private final JComboBox<String> cbAnim = new JComboBox<>();
    private final JButton btnPlay = Theme.tint("Chạy", Theme.GREEN, 30, null);
    private final JCheckBox chkLoop = new JCheckBox("Lặp", true);
    private final JSlider slTime = new JSlider(0, 1000, 0);
    private final JLabel lbTime = new JLabel("t = 0.00 / 0.00 s");
    private final JSlider slSpeed = new JSlider(10, 200, 100);
    private final JLabel lbSpeed = new JLabel("1.00×");

    private final JTextField txtSlotFilter = new JTextField();
    private final JPanel pnSlots = new JPanel();
    private final JLabel lbSlotCount = new JLabel("—");

    private final JTextField txtBoneFilter = new JTextField();
    private final DefaultListModel<String> mdlBones = new DefaultListModel<>();
    private final JList<String> lstBones = new JList<>(mdlBones);
    private final JLabel lbBoneName = new JLabel("(chưa chọn bone)");
    private final DefaultListModel<String> mdlOverrides = new DefaultListModel<>();
    private final JList<String> lstOverrides = new JList<>(mdlOverrides);
    private Knob knRot, knDx, knDy, knSx, knSy;

    private final JCheckBox chkRealSize = new JCheckBox("Cỡ thật như game", true);
    private final JLabel lbZoom = Theme.monoLabel("100%", 13, Theme.TEXT);

    private final Timer ticker = new Timer(33, e -> tick());
    private long lastTickNs;
    private double animTime;
    private boolean playing;
    /** Chặn vòng lặp sự kiện khi code tự đặt giá trị widget. */
    private boolean suspend;

    private JTabbedPane tabs;
    private HaoQuangPanel hq;
    private HqHost hqHost;
    private MainFrame.Shell shell;

    public PlayerViewerFrame(ToolConfig cfg) {
        super("Player Viewer");
        this.cfg = cfg;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1680, 980);   // chỉ dùng khi chạy JFrame riêng (CLI debug); trong app là card của MainFrame

        hqHost = new HqHost();
        hq = new HaoQuangPanel(hqHost);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBackground(Theme.BG_MAIN);
        content.setBorder(new EmptyBorder(16, 20, 18, 20));
        content.add(buildSourceCard(), BorderLayout.NORTH);

        JPanel cols = new JPanel(new BorderLayout(12, 0));
        cols.setOpaque(false);
        cols.add(buildBrowser(), BorderLayout.WEST);
        cols.add(buildCanvasPanel(), BorderLayout.CENTER);
        cols.add(buildSidePanel(), BorderLayout.EAST);
        content.add(cols, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG_MAIN);
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);

        wireEvents();
        reloadFolderList();

        Path def = cfg.resourceRoot().resolve("Player").resolve("1");
        if (Files.isDirectory(def)) {
            txtFolder.setText(def.toString());
            SwingUtilities.invokeLater(() -> load(def));
        }
    }

    // ═════════════════════════ vỏ app ═════════════════════════
    public void setShell(MainFrame.Shell s) { shell = s; }

    /** Đẩy ngữ cảnh hiện tại lên top bar của vỏ app. */
    public void pushShell() {
        if (shell == null) return;
        int i = (tabs == null) ? 0 : tabs.getSelectedIndex();
        String tab = (tabs == null || i < 0) ? "" : tabs.getTitleAt(i);
        shell.update(tab, null, false);
    }

    // ═════════════════════════ THẺ NGUỒN SPINE ═════════════════════════
    private JComponent buildSourceCard() {
        JPanel card = Theme.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel r1 = Theme.row();
        JLabel l = Theme.label("Thư mục Spine", 13, Font.BOLD, Theme.TEXT_MUTED);
        r1.add(Theme.lock(l));
        r1.add(Box.createHorizontalStrut(10));
        txtFolder.setFont(Theme.mono(13, Font.PLAIN));
        txtFolder.setForeground(Theme.TEXT_2);
        txtFolder.setPreferredSize(new Dimension(200, 36));
        txtFolder.setMinimumSize(new Dimension(120, 36));
        txtFolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        r1.add(txtFolder);
        r1.add(Box.createHorizontalStrut(10));
        r1.add(Theme.lock(Theme.ghost("Chọn…", 36, e -> browse())));
        r1.add(Box.createHorizontalStrut(10));
        JButton bLoad = Theme.primary("Nạp", 36, e -> load(Paths.get(txtFolder.getText().trim())));
        bLoad.setPreferredSize(new Dimension(
                bLoad.getFontMetrics(bLoad.getFont()).stringWidth("Nạp") + 44, 36));
        r1.add(Theme.lock(bLoad));
        r1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        card.add(r1);
        card.add(Box.createVerticalStrut(10));

        chipRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        rebuildChips();
        card.add(chipRow);
        return Theme.capH(card);
    }

    private void rebuildChips() {
        chipRow.removeAll();
        chipRow.add(chState);
        chipRow.add(chSlot);
        chipRow.add(chBone);
        chipRow.add(chAnim);
        chipRow.add(chSkin);
        chipRow.add(chScale);
        chipRow.revalidate();
        chipRow.repaint();
    }

    // ═════════════════════════ CỘT TRÁI: duyệt nhanh ═════════════════════════
    private JComponent buildBrowser() {
        JPanel p = Theme.card();
        p.setLayout(new BorderLayout());
        p.setPreferredSize(new Dimension(COL_BROWSE, 10));
        p.setMinimumSize(new Dimension(COL_BROWSE, 10));

        JPanel head = Theme.colBox();
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                new EmptyBorder(12, 12, 10, 12)));

        JPanel titleRow = Theme.row();
        titleRow.add(Theme.sectionHeader("Duyệt nhanh", Theme.TEXT_MUTED));
        titleRow.add(Box.createHorizontalGlue());
        titleRow.add(lbFolderCount);
        head.add(Theme.capH(titleRow));
        head.add(Box.createVerticalStrut(9));

        cbRoot.setFont(Theme.font(13, Font.BOLD));
        cbRoot.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        cbRoot.setPreferredSize(new Dimension(120, 34));
        cbRoot.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(cbRoot);
        head.add(Box.createVerticalStrut(9));

        txtRootFilter.putClientProperty("JTextField.placeholderText", "Tìm theo tên hoặc id…");
        txtRootFilter.putClientProperty("JTextField.leadingIcon",
                Theme.icon(Theme.IC_SEARCH, 14, Theme.TEXT_DIM));
        txtRootFilter.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        txtRootFilter.setPreferredSize(new Dimension(120, 34));
        txtRootFilter.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(txtRootFilter);
        p.add(head, BorderLayout.NORTH);

        JScrollPane sp = new JScrollPane(idGrid,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(new EmptyBorder(8, 8, 0, 4));
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /**
     * Lưới ô id chia theo dải (0–99 · 100–599 · 600–5999 · 6000+ · Khác). Mỗi dải là 1 {@link JList}
     * riêng xếp dọc trong cùng khung cuộn — cách rẻ nhất mà vẫn chọn được bằng bàn phím.
     */
    private final class IdGrid extends JPanel implements Scrollable {
        private final List<JList<String>> lists = new ArrayList<>();
        private boolean guard;

        IdGrid() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        void setItems(List<String> names) {
            String keep = selectedName();
            lists.clear();
            removeAll();
            List<List<String>> buckets = new ArrayList<>();
            for (int i = 0; i <= ID_RANGES.length; i++) buckets.add(new ArrayList<>());
            for (String n : names) {
                long id = idOf(n);
                int b = ID_RANGES.length;                        // mặc định: "Khác" (tên chữ)
                if (id >= 0) {
                    for (int i = 0; i < ID_RANGES.length; i++) {
                        if (id >= ID_RANGES[i][0] && id <= ID_RANGES[i][1]) { b = i; break; }
                    }
                }
                buckets.get(b).add(n);
            }
            boolean first = true;
            for (int i = 0; i < buckets.size(); i++) {
                List<String> b = buckets.get(i);
                if (b.isEmpty()) continue;
                String label = (i < ID_RANGE_LABELS.length) ? ID_RANGE_LABELS[i] : "Khác";
                JLabel h = Theme.sectionHeader(label, Theme.TEXT_DIM);
                h.setFont(Theme.tracked(11, Font.BOLD, 0.14));
                h.setBorder(BorderFactory.createCompoundBorder(
                        first ? BorderFactory.createEmptyBorder()
                              : BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                        new EmptyBorder(first ? 4 : 8, 8, 6, 8)));
                add(Theme.capH(h));
                first = false;

                DefaultListModel<String> m = new DefaultListModel<>();
                for (String s : b) m.addElement(s);
                // getMaximumSize() mặc định của JList = preferredSize (ComponentUI trả về nó) nên
                // BoxLayout chỉ cho list rộng 48px ⇒ HORIZONTAL_WRAP ra ĐÚNG 1 cột. Mở khoá bề rộng,
                // vẫn khoá chiều cao theo nội dung.
                JList<String> list = new JList<>(m) {
                    @Override public Dimension getMaximumSize() {
                        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
                    }
                };
                list.setLayoutOrientation(JList.HORIZONTAL_WRAP);
                list.setVisibleRowCount(-1);
                list.setFixedCellWidth(48);
                list.setFixedCellHeight(36);
                list.setOpaque(false);
                list.setBorder(new EmptyBorder(0, 4, 8, 4));
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setCellRenderer(new IdCell());
                list.setAlignmentX(Component.LEFT_ALIGNMENT);
                list.addListSelectionListener(e -> {
                    if (e.getValueIsAdjusting() || guard) return;
                    String s = list.getSelectedValue();
                    if (s == null) return;
                    guard = true;
                    for (JList<String> other : lists) if (other != list) other.clearSelection();
                    guard = false;
                    openFolderName(s);
                });
                lists.add(list);
                add(list);
            }
            revalidate();
            repaint();
            if (keep != null) select(keep);
        }

        String selectedName() {
            for (JList<String> l : lists) if (l.getSelectedValue() != null) return l.getSelectedValue();
            return null;
        }

        void select(String name) {
            guard = true;
            for (JList<String> l : lists) {
                int idx = -1;
                for (int i = 0; i < l.getModel().getSize(); i++) {
                    if (l.getModel().getElementAt(i).equals(name)) { idx = i; break; }
                }
                if (idx >= 0) { l.setSelectedIndex(idx); l.ensureIndexIsVisible(idx); }
                else l.clearSelection();
            }
            guard = false;
        }

        // Scrollable: bám bề rộng khung cuộn để JList HORIZONTAL_WRAP tự tính số cột.
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height; }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Ô id: bo 7, cao 30, chữ monospace 13; ô chọn = nền ACCENT_SEL + viền ACCENT. */
    private static final class IdCell extends JLabel implements ListCellRenderer<String> {
        private boolean sel;

        IdCell() {
            setOpaque(false);
            setHorizontalAlignment(SwingConstants.CENTER);
            setFont(Theme.mono(13, Font.PLAIN));
            setBorder(new EmptyBorder(0, 0, 0, 4));
        }

        @Override public Component getListCellRendererComponent(JList<? extends String> list, String value,
                                                                int index, boolean isSelected, boolean focus) {
            setText(value);
            sel = isSelected;
            setFont(Theme.mono(13, isSelected ? Font.BOLD : Font.PLAIN));
            setForeground(isSelected ? Theme.ACCENT_HOVER : Theme.TEXT_2);
            return this;
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 4, h = getHeight() - 6;
            g2.setColor(sel ? Theme.ACCENT_SEL : Theme.BG_SURFACE2);
            g2.fillRoundRect(0, 3, w, h, 7, 7);
            g2.setColor(sel ? Theme.ACCENT : Theme.BORDER);
            g2.drawRoundRect(0, 3, w - 1, h - 1, 7, 7);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private void openFolderName(String name) {
        Path f = cfg.resourceRoot().resolve((String) cbRoot.getSelectedItem()).resolve(name);
        txtFolder.setText(f.toString());
        load(f);
    }

    // ═════════════════════════ GIỮA: khung xem ═════════════════════════
    /** Chip công tắc lớp + chip mẹo chuột nổi trong khung xem. */
    private JLayeredPane canvasLayer;
    private JPanel overlayBar;
    private final Theme.Chip hintChip =
            new Theme.Chip("lăn chuột = phóng · kéo = dời lớp đang chọn", null, 30, 12, false);

    private JComponent buildCanvasPanel() {
        JPanel p = Theme.card(Theme.BG_PANEL, Theme.BORDER_SOFT, 10);
        p.setLayout(new BorderLayout());
        p.setBorder(new EmptyBorder(1, 1, 1, 1));
        p.add(buildCanvasBar(), BorderLayout.NORTH);

        overlayBar = Theme.row();
        overlayBar.setOpaque(false);
        hintChip.setForeground(Theme.TEXT_MUTED);
        layoutOverlay(false);

        canvasLayer = new JLayeredPane();
        canvasLayer.setOpaque(false);
        canvasLayer.add(canvas, JLayeredPane.DEFAULT_LAYER);
        canvasLayer.add(overlayBar, JLayeredPane.PALETTE_LAYER);
        canvasLayer.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int w = canvasLayer.getWidth(), h = canvasLayer.getHeight();
                canvas.setBounds(0, 0, w, h);
                int bh = Math.max(30, overlayBar.getPreferredSize().height);
                overlayBar.setBounds(16, h - bh - 14, Math.max(0, w - 32), bh);
            }
        });
        p.add(canvasLayer, BorderLayout.CENTER);
        return p;
    }

    /**
     * Thanh công cụ khung xem — MỘT hàng: cỡ thật · về giữa · zoom │ Chạy · FPS · Lặp │ Nằm SAU player.
     * 4 widget cuối là widget THẬT của {@link HaoQuangPanel} (giữ nguyên listener, chỉ đổi nơi add).
     */
    private JComponent buildCanvasBar() {
        JPanel bar = Theme.row();
        bar.setOpaque(true);
        bar.setBackground(Theme.BG_SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                new EmptyBorder(0, 14, 0, 14)));
        bar.setPreferredSize(new Dimension(10, 50));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));

        chkRealSize.setOpaque(false);
        chkRealSize.setForeground(Theme.TEXT_2);
        chkRealSize.setFont(Theme.font(13, Font.PLAIN));
        chkRealSize.setFocusable(false);
        chkRealSize.setIconTextGap(8);
        chkRealSize.setToolTipText("Bật: to/nhỏ đúng tỉ lệ game (đọc scale trong SkeletonData.asset). "
                + "Tắt: phóng cho vừa khung — dùng khi skeleton không phải nhân vật.");
        bar.add(Theme.lock(chkRealSize));
        bar.add(Box.createHorizontalStrut(10));
        bar.add(Theme.vsep(20));
        bar.add(Box.createHorizontalStrut(10));

        JButton bCenter = Theme.ghost("Về giữa", 30, e -> { canvas.resetView(); canvas.repaint(); });
        bar.add(Theme.lockH(bCenter, 30));
        bar.add(Box.createHorizontalStrut(10));

        JPanel zoomBox = Theme.card(Theme.BG_INPUT, Theme.BORDER, 7);
        zoomBox.setLayout(new BorderLayout());
        zoomBox.setBorder(new EmptyBorder(0, 10, 0, 10));
        zoomBox.add(lbZoom, BorderLayout.CENTER);
        Theme.lockSize(zoomBox, 62, 30);
        bar.add(zoomBox);
        bar.add(Box.createHorizontalStrut(10));
        bar.add(Theme.vsep(20));
        bar.add(Box.createHorizontalStrut(10));

        // ── nhóm XEM THỬ mượn từ tab Hào Quang ──
        bar.add(Theme.lockH(hq.previewButton(), 30));
        bar.add(Box.createHorizontalStrut(10));
        bar.add(Theme.lockSize(hq.fpsSpinner(), 86, 30));
        bar.add(Box.createHorizontalStrut(10));
        bar.add(Theme.lock(hq.loopCheck()));
        bar.add(Box.createHorizontalGlue());
        bar.add(hq.behindChip());

        // Bản thiết kế đo ở khung xem ~890px (1920). Khung hẹp hơn (1600×900) thì rút nhãn và DỜI
        // chip "Nằm SAU player" xuống hàng chip trong khung xem — vẫn ĐÚNG 1 hàng, không control
        // nào bị cắt (handoff §8), thay vì để BoxLayout tràn ra ngoài mép.
        bar.addComponentListener(new java.awt.event.ComponentAdapter() {
            private Boolean last;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                boolean tight = bar.getWidth() < 720;
                if (last != null && last == tight) return;
                last = tight;
                chkRealSize.setText(tight ? "Cỡ thật" : "Cỡ thật như game");
                Theme.lock(chkRealSize);
                hq.behindChip().setText(tight ? "Sau player" : "Nằm SAU player");
                hintChip.setText(tight ? "lăn = phóng" : "lăn chuột = phóng · kéo = dời lớp đang chọn");
                layoutOverlay(tight);
                if (!tight) bar.add(hq.behindChip());   // add() tự gỡ khỏi overlay
                bar.revalidate();
                bar.repaint();
            }
        });
        return bar;
    }

    /** Xếp lại hàng chip trong khung xem; {@code tight} → nhận thêm chip "Sau player" từ thanh trên. */
    private void layoutOverlay(boolean tight) {
        if (overlayBar == null) return;
        overlayBar.removeAll();
        overlayBar.add(hq.chipBack());
        overlayBar.add(Box.createHorizontalStrut(8));
        overlayBar.add(hq.chipFront());
        if (tight) {
            overlayBar.add(Box.createHorizontalStrut(8));
            overlayBar.add(hq.behindChip());            // add() tự gỡ khỏi thanh khung xem
        }
        overlayBar.add(Box.createHorizontalGlue());
        overlayBar.add(hintChip);
        overlayBar.revalidate();
        overlayBar.repaint();
    }

    // ═════════════════════════ PHẢI: 4 tab ═════════════════════════
    private JComponent buildSidePanel() {
        JPanel p = Theme.card();
        p.setLayout(new BorderLayout());
        p.setPreferredSize(new Dimension(COL_INSPECTOR, 10));
        p.setMinimumSize(new Dimension(COL_INSPECTOR, 10));

        tabs = new JTabbedPane();
        tabs.setOpaque(false);
        tabs.setBackground(Theme.BG_SURFACE);
        tabs.addTab("Animation", buildTabAnimation());
        tabs.addTab("Slot", buildTabSlots());
        tabs.addTab("Bone", buildTabBones());
        tabs.addTab("Hào Quang", hq);
        tabs.addChangeListener(e -> {
            hq.setTabActive(tabs.getSelectedIndex() == 3);
            pushShell();
        });
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildTabAnimation() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.BG_SURFACE);
        p.setBorder(new EmptyBorder(12, 12, 12, 12));

        p.add(labeledRow("Skin", cbSkin));
        p.add(Box.createVerticalStrut(8));
        p.add(labeledRow("Animation", cbAnim));
        p.add(Box.createVerticalStrut(12));

        JPanel ctl = Theme.row();
        ctl.add(Theme.lockH(btnPlay, 34));
        ctl.add(Box.createHorizontalStrut(8));
        // Nhãn CHỮ, không phải icon: font Be Vietnam Pro không có glyph ▶ ⏮ … nên nút chỉ-icon
        // hiện ra ô trống (Swing không tự rơi sang font khác cho font vật lý) — icon vector thì được.
        ctl.add(Theme.lockH(Theme.ghost("Về đầu", 34,
                e -> { animTime = 0; syncTimeWidgets(); canvas.repaint(); }), 34));
        ctl.add(Box.createHorizontalStrut(10));
        chkLoop.setOpaque(false);
        chkLoop.setForeground(Theme.TEXT_2);
        chkLoop.setFont(Theme.font(13, Font.PLAIN));
        chkLoop.setIconTextGap(8);
        chkLoop.setFocusable(false);
        ctl.add(Theme.lock(chkLoop));
        ctl.add(Box.createHorizontalGlue());
        ctl.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        p.add(ctl);
        p.add(Box.createVerticalStrut(10));

        lbTime.setForeground(Theme.TEXT_MUTED);
        lbTime.setFont(Theme.mono(12, Font.PLAIN));
        lbTime.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Theme.capH(lbTime));
        slTime.setOpaque(false);
        slTime.setAlignmentX(Component.LEFT_ALIGNMENT);
        slTime.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        p.add(slTime);
        p.add(Box.createVerticalStrut(12));

        JPanel sr = Theme.row();
        sr.add(Theme.label("Tốc độ chạy (0.1× → 2×)", 13, Font.PLAIN, Theme.TEXT_2));
        sr.add(Box.createHorizontalGlue());
        lbSpeed.setForeground(Theme.ACCENT);
        lbSpeed.setFont(Theme.mono(13, Font.BOLD));
        sr.add(lbSpeed);
        sr.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        p.add(sr);
        slSpeed.setOpaque(false);
        slSpeed.setAlignmentX(Component.LEFT_ALIGNMENT);
        slSpeed.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        p.add(slSpeed);
        p.add(Box.createVerticalStrut(6));
        JPanel rs = Theme.row();
        rs.add(Theme.lockH(Theme.ghost("Về 1.00×", 30, e -> slSpeed.setValue(100)), 30));
        rs.add(Box.createHorizontalGlue());
        rs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        p.add(rs);

        p.add(Box.createVerticalGlue());
        return p;
    }

    private JComponent buildTabSlots() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.BG_SURFACE);
        p.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        txtSlotFilter.putClientProperty("JTextField.placeholderText", "Lọc slot…");
        txtSlotFilter.setPreferredSize(new Dimension(100, 34));
        top.add(txtSlotFilter, BorderLayout.NORTH);
        lbSlotCount.setForeground(Theme.TEXT_MUTED);
        lbSlotCount.setFont(Theme.font(12, Font.PLAIN));
        top.add(lbSlotCount, BorderLayout.SOUTH);

        pnSlots.setLayout(new BoxLayout(pnSlots, BoxLayout.Y_AXIS));
        pnSlots.setBackground(Theme.BG_MAIN);
        pnSlots.setBorder(new EmptyBorder(6, 8, 6, 8));
        JScrollPane sp = new JScrollPane(pnSlots);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER_SOFT));
        sp.getViewport().setBackground(Theme.BG_MAIN);

        JPanel bulk = Theme.row();
        bulk.add(Theme.lockH(Theme.ghost("Bật cả", 32, e -> setAllSlots(true)), 32));
        bulk.add(Box.createHorizontalStrut(8));
        bulk.add(Theme.lockH(Theme.ghost("Tắt cả", 32, e -> setAllSlots(false)), 32));
        bulk.add(Box.createHorizontalGlue());

        p.add(top, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        p.add(bulk, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildTabBones() {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBackground(Theme.BG_SURFACE);
        p.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        txtBoneFilter.putClientProperty("JTextField.placeholderText", "Tìm bone (vd: tay, chan)…");
        txtBoneFilter.setPreferredSize(new Dimension(100, 34));
        top.add(txtBoneFilter, BorderLayout.NORTH);
        lstBones.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        lstBones.setFont(Theme.font(13, Font.PLAIN));
        JScrollPane spb = new JScrollPane(lstBones);
        spb.setPreferredSize(new Dimension(0, 170));
        spb.setBorder(BorderFactory.createLineBorder(Theme.BORDER_SOFT));
        top.add(spb, BorderLayout.CENTER);

        JPanel mid = Theme.colBox();
        lbBoneName.setForeground(Theme.ACCENT);
        lbBoneName.setFont(Theme.font(13, Font.BOLD));
        lbBoneName.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(Theme.capH(lbBoneName));
        JLabel note = Theme.label("delta cộng SAU animation — anim vẫn chạy, chỉ lệch thêm",
                12, Font.PLAIN, Theme.TEXT_DIM);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        mid.add(Theme.capH(note));
        mid.add(Box.createVerticalStrut(6));
        // Nhãn thuần chữ Việt: font bundle không có Δ (và cả ▶ ⟳ ✔ →) nên ký hiệu bị rơi mất.
        knRot = addKnob(mid, "Xoay thêm",  -180, 180, 0,   1.0, "%.0f°");
        knDx  = addKnob(mid, "Dời X",      -200, 200, 0,   1.0, "%.0f");
        knDy  = addKnob(mid, "Dời Y",      -200, 200, 0,   1.0, "%.0f");
        knSx  = addKnob(mid, "× ScaleX",     10, 300, 100, 0.01, "%.2f");
        knSy  = addKnob(mid, "× ScaleY",     10, 300, 100, 0.01, "%.2f");
        mid.add(Box.createVerticalStrut(6));
        JPanel rb = Theme.row();
        rb.add(Theme.lockH(Theme.ghost("Reset bone này", 32, e -> resetBone(selectedBone)), 32));
        rb.add(Box.createHorizontalStrut(8));
        rb.add(Theme.lockH(Theme.tint("Reset tất cả", Theme.RED, 32, e -> resetAllBones()), 32));
        rb.add(Box.createHorizontalGlue());
        rb.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        mid.add(rb);
        mid.add(Box.createVerticalGlue());

        JPanel bot = new JPanel(new BorderLayout(0, 6));
        bot.setOpaque(false);
        JLabel ol = Theme.sectionHeader("Bone đang chỉnh", Theme.TEXT_MUTED);
        bot.add(ol, BorderLayout.NORTH);
        lstOverrides.setFont(Theme.mono(12, Font.PLAIN));
        JScrollPane spo = new JScrollPane(lstOverrides);
        spo.setPreferredSize(new Dimension(0, 90));
        spo.setBorder(BorderFactory.createLineBorder(Theme.BORDER_SOFT));
        bot.add(spo, BorderLayout.CENTER);

        p.add(top, BorderLayout.NORTH);
        p.add(mid, BorderLayout.CENTER);
        p.add(bot, BorderLayout.SOUTH);
        return p;
    }

    /** Một hàng "nhãn + slider + ô số" cho tab Bone. */
    private Knob addKnob(JPanel host, String label, int min, int max, int init, double mul, String fmt) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JLabel l = Theme.label(label, 13, Font.PLAIN, Theme.TEXT_2);
        l.setPreferredSize(new Dimension(78, 24));
        JSlider s = new JSlider(min, max, init);
        s.setOpaque(false);
        s.setFocusable(false);
        JLabel v = new JLabel(String.format(fmt, init * mul));
        v.setForeground(Theme.TEXT);
        v.setFont(Theme.mono(13, Font.PLAIN));
        v.setPreferredSize(new Dimension(58, 24));
        v.setHorizontalAlignment(SwingConstants.RIGHT);
        row.add(l, BorderLayout.WEST);
        row.add(s, BorderLayout.CENTER);
        row.add(v, BorderLayout.EAST);
        host.add(row);
        Knob k = new Knob(s, v, mul, fmt, init);
        s.addChangeListener(e -> {
            v.setText(String.format(fmt, s.getValue() * mul));
            if (!suspend) onBoneKnob();
        });
        return k;
    }

    private static JComponent labeledRow(String label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        JLabel l = Theme.label(label, 13, Font.PLAIN, Theme.TEXT_2);
        l.setPreferredSize(new Dimension(78, 24));
        row.add(l, BorderLayout.WEST);
        field.setPreferredSize(new Dimension(120, 34));
        row.add(field, BorderLayout.CENTER);
        return row;
    }

    /** Slider + ô số + hệ số quy đổi (slider là số nguyên, giá trị thật = value × mul). */
    private record Knob(JSlider slider, JLabel value, double mul, String fmt, int neutral) {
        double val() { return slider.getValue() * mul; }
        void set(double real) { slider.setValue((int) Math.round(real / mul)); }
        void reset() { slider.setValue(neutral); }
    }

    // ═════════════════════════ SỰ KIỆN ═════════════════════════
    private void wireEvents() {
        cbRoot.addActionListener(e -> reloadFolderList());
        txtRootFilter.getDocument().addDocumentListener(new Doc(this::refreshFolderList));

        cbSkin.addActionListener(e -> {
            if (suspend || sc == null) return;
            sc.data.setSkin((String) cbSkin.getSelectedItem());
            canvas.repaint();
        });
        cbAnim.addActionListener(e -> {
            if (suspend) return;
            animTime = 0;
            syncTimeWidgets();
            canvas.repaint();
        });
        btnPlay.addActionListener(e -> togglePlay());
        slTime.addChangeListener(e -> {
            if (suspend) return;
            animTime = slTime.getValue() / 1000.0;
            lbTime.setText(String.format("t = %.2f / %.2f s", animTime, duration()));
            canvas.repaint();
        });
        slSpeed.addChangeListener(e -> lbSpeed.setText(String.format("%.2f×", slSpeed.getValue() / 100.0)));

        txtSlotFilter.getDocument().addDocumentListener(new Doc(this::rebuildSlotChecks));

        txtBoneFilter.getDocument().addDocumentListener(new Doc(this::refreshBoneList));
        lstBones.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            selectBone(lstBones.getSelectedValue());
        });
        lstOverrides.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String line = lstOverrides.getSelectedValue();
            if (line == null) return;
            String name = line.substring(0, Math.max(0, line.indexOf(':')));
            selectBone(name);
            lstBones.setSelectedValue(name, true);
        });

        chkRealSize.addActionListener(e -> { canvas.resetView(); canvas.repaint(); });
    }

    private void browse() {
        String cur = txtFolder.getText().trim();
        Path init = cur.isEmpty() ? cfg.resourceRoot() : Paths.get(cur).getParent();
        JFileChooser fc = new JFileChooser(init != null && Files.isDirectory(init) ? init.toFile()
                : cfg.resourceRoot().toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Thư mục Spine (Player/{id} hoặc HonMa/{id})");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File d = fc.getSelectedFile();
        txtFolder.setText(d.getAbsolutePath());
        load(d.toPath());
    }

    // ═════════════════════════ NẠP SKELETON ═════════════════════════
    /**
     * Số thứ tự lượt nạp. Nạp chạy ở luồng nền nên hai lượt có thể chồng nhau (mở tool là tự nạp
     * Player/1, người dùng bấm ngay folder khác) — lượt CŨ về sau sẽ đè lượt MỚI nếu không đánh số.
     */
    private int loadSeq;

    private void load(Path f) {
        if (f == null || !Files.isDirectory(f)) {
            error("Không thấy thư mục:\n" + f);
            return;
        }
        stopPlay();
        final int seq = ++loadSeq;
        chState.setText("Đang nạp " + f.getFileName() + "…");
        chState.setTint(null);
        chipRow.revalidate();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<SpineCharacter, Void>() {
            Float scale;
            @Override protected SpineCharacter doInBackground() {
                scale = SpineCharacter.importScale(f);
                return SpineCharacter.load(f);
            }
            @Override protected void done() {
                if (seq != loadSeq) return;              // đã có lượt nạp mới hơn ⇒ bỏ kết quả cũ
                setCursor(Cursor.getDefaultCursor());
                SpineCharacter loaded;
                try { loaded = get(); } catch (Exception ex) { loaded = null; }
                if (loaded == null) {
                    chState.setText("LỖI · thiếu file Spine (.json/.skel.bytes + .atlas.txt)");
                    chState.setTint(Theme.RED);
                    chipRow.revalidate();
                    error("Thư mục thiếu file Spine:\n" + f
                            + "\n\nCần có 1 skeleton (*.json hoặc *.skel.bytes) + 1 *.atlas.txt + *.png.");
                    return;
                }
                folder = f;
                sc = loaded;
                importScale = scale;
                onLoaded();
            }
        }.execute();
    }

    private void onLoaded() {
        suspend = true;
        hiddenSlots.clear();
        boneDeltas.clear();
        selectedBone = null;
        sc.skeleton.setBoneDeltas(null);
        sc.skeleton.setHiddenSlots(null);

        // Skin — "(tất cả)" chỉ có nghĩa khi skeleton thật sự nhiều skin
        cbSkin.removeAllItems();
        List<String> skins = sc.data.skinNames();
        if (sc.data.hasMultipleSkins()) cbSkin.addItem(SpineData.SKIN_ALL);
        for (String s : skins) cbSkin.addItem(s);
        if (cbSkin.getItemCount() > 0) cbSkin.setSelectedIndex(0);
        sc.data.setSkin((String) cbSkin.getSelectedItem());
        cbSkin.setEnabled(cbSkin.getItemCount() > 1);

        // Animation — HashMap nên phải tự sắp, không thì thứ tự đổi mỗi lần chạy
        cbAnim.removeAllItems();
        cbAnim.addItem("(setup pose)");
        List<String> anims = new ArrayList<>(sc.data.animations.keySet());
        anims.sort(String.CASE_INSENSITIVE_ORDER);
        for (String a : anims) cbAnim.addItem(a);
        cbAnim.setSelectedIndex(0);

        animTime = 0;
        suspend = false;
        syncTimeWidgets();

        rebuildSlotChecks();
        refreshBoneList();
        refreshOverrides();
        updateKnobs(null);
        lbBoneName.setText("(chưa chọn bone)");

        canvas.resetView();
        canvas.repaint();

        String root = String.valueOf(cbRoot.getSelectedItem());
        chState.setText("Nạp OK · " + root + "/" + folder.getFileName());
        chState.setTint(Theme.GREEN);
        chState.setIcon(Theme.icon(Theme.IC_CHECK, 12, Theme.GREEN));
        chState.setIconTextGap(6);
        chSlot.setText(sc.data.slots.size() + " slot");
        chBone.setText(sc.data.bones.size() + " bone");
        chAnim.setText(sc.data.animations.size() + " animation");
        chSkin.setText(sc.data.skins.size() + " skin");
        chScale.setText(importScale != null
                ? String.format("SkeletonData scale %.8f", importScale)
                : "không có SkeletonData.asset — xem vừa khung");
        chScale.setForeground(Theme.TEXT_MUTED);
        chipRow.revalidate();
        chipRow.repaint();
    }

    // ═════════════════════════ ANIMATION ═════════════════════════
    private SpineData.Animation currentAnim() {
        if (sc == null) return null;
        Object sel = cbAnim.getSelectedItem();
        if (sel == null || ((String) sel).startsWith("(")) return null;
        return sc.data.animations.get((String) sel);
    }

    private double duration() {
        SpineData.Animation a = currentAnim();
        return (a == null || a.duration <= 0) ? 1.0 : a.duration;
    }

    private void syncTimeWidgets() {
        boolean old = suspend;
        suspend = true;
        double dur = duration();
        slTime.setMaximum((int) Math.round(dur * 1000));
        slTime.setValue((int) Math.round(animTime * 1000));
        lbTime.setText(String.format("t = %.2f / %.2f s", animTime, dur));
        suspend = old;
    }

    private void togglePlay() {
        if (playing) stopPlay();
        else startPlay();
    }

    private void startPlay() {
        if (currentAnim() == null) {
            info("Chọn animation trước (đang ở \"(setup pose)\").");
            return;
        }
        playing = true;
        btnPlay.setText("Dừng");
        lastTickNs = System.nanoTime();
        ticker.start();
    }

    private void stopPlay() {
        playing = false;
        btnPlay.setText("Chạy");
        ticker.stop();
    }

    /** Nhịp phát: cộng thời gian thực × tốc độ, vòng lại hoặc dừng ở cuối. */
    private void tick() {
        if (!playing) return;
        if (!canvas.isShowing()) { stopPlay(); return; }   // card bị đóng → khỏi vẽ vào hư không
        long now = System.nanoTime();
        double dt = (now - lastTickNs) / 1e9 * (slSpeed.getValue() / 100.0);
        lastTickNs = now;
        double dur = duration();
        animTime += dt;
        if (animTime >= dur) {
            if (chkLoop.isSelected()) animTime %= dur;
            else { animTime = dur; stopPlay(); }
        }
        syncTimeWidgets();
        canvas.repaint();
    }

    // ═════════════════════════ SLOT ═════════════════════════
    private void rebuildSlotChecks() {
        pnSlots.removeAll();
        if (sc == null) { pnSlots.revalidate(); pnSlots.repaint(); return; }
        String q = txtSlotFilter.getText().trim().toLowerCase();
        int shown = 0;
        for (SpineData.Slot s : sc.data.slots) {
            if (!q.isEmpty() && !s.name.toLowerCase().contains(q)) continue;
            JCheckBox cb = new JCheckBox(s.name, !hiddenSlots.contains(s.name));
            cb.setOpaque(false);
            cb.setFocusable(false);
            cb.setForeground(Theme.TEXT_2);
            cb.setFont(Theme.font(13, Font.PLAIN));
            cb.setIconTextGap(8);
            cb.setAlignmentX(Component.LEFT_ALIGNMENT);
            cb.addActionListener(e -> {
                if (cb.isSelected()) hiddenSlots.remove(s.name);
                else hiddenSlots.add(s.name);
                pushSlots();
            });
            pnSlots.add(cb);
            shown++;
        }
        lbSlotCount.setText(String.format("hiện %d/%d slot%s",
                sc.data.slots.size() - hiddenSlots.size(), sc.data.slots.size(),
                q.isEmpty() ? "" : "  ·  lọc ra " + shown));
        pnSlots.revalidate();
        pnSlots.repaint();
    }

    private void setAllSlots(boolean on) {
        if (sc == null) return;
        hiddenSlots.clear();
        if (!on) for (SpineData.Slot s : sc.data.slots) hiddenSlots.add(s.name);
        pushSlots();
        rebuildSlotChecks();
    }

    private void pushSlots() {
        if (sc == null) return;
        sc.skeleton.setHiddenSlots(hiddenSlots);
        lbSlotCount.setText(String.format("hiện %d/%d slot",
                sc.data.slots.size() - hiddenSlots.size(), sc.data.slots.size()));
        canvas.repaint();
    }

    // ═════════════════════════ BONE ═════════════════════════
    private void refreshBoneList() {
        mdlBones.clear();
        if (sc == null) return;
        String q = txtBoneFilter.getText().trim().toLowerCase();
        for (SpineData.Bone b : sc.data.bones) {
            if (q.isEmpty() || b.name.toLowerCase().contains(q)) mdlBones.addElement(b.name);
        }
    }

    private void selectBone(String name) {
        if (name == null || name.isEmpty()) return;
        selectedBone = name;
        lbBoneName.setText("Bone: " + name);
        updateKnobs(boneDeltas.get(name));
    }

    private void updateKnobs(float[] d) {
        boolean old = suspend;
        suspend = true;
        if (d == null) { knRot.reset(); knDx.reset(); knDy.reset(); knSx.reset(); knSy.reset(); }
        else { knRot.set(d[0]); knDx.set(d[1]); knDy.set(d[2]); knSx.set(d[3]); knSy.set(d[4]); }
        suspend = old;
    }

    /** Kéo slider → gom 5 giá trị; toàn trung tính thì XOÁ hẳn khỏi map (như bản Python). */
    private void onBoneKnob() {
        if (sc == null || selectedBone == null) return;
        float rot = (float) knRot.val(), dx = (float) knDx.val(), dy = (float) knDy.val();
        float sx = (float) knSx.val(), sy = (float) knSy.val();
        boolean neutral = Math.abs(rot) < 1e-6 && Math.abs(dx) < 1e-6 && Math.abs(dy) < 1e-6
                && Math.abs(sx - 1) < 1e-6 && Math.abs(sy - 1) < 1e-6;
        if (neutral) boneDeltas.remove(selectedBone);
        else boneDeltas.put(selectedBone, new float[]{rot, dx, dy, sx, sy});
        pushBones();
    }

    private void resetBone(String name) {
        if (name == null) return;
        boneDeltas.remove(name);
        updateKnobs(null);
        pushBones();
    }

    private void resetAllBones() {
        boneDeltas.clear();
        updateKnobs(null);
        pushBones();
    }

    private void pushBones() {
        if (sc == null) return;
        sc.skeleton.setBoneDeltas(boneDeltas);
        refreshOverrides();
        canvas.repaint();
    }

    private void refreshOverrides() {
        mdlOverrides.clear();
        for (Map.Entry<String, float[]> e : boneDeltas.entrySet()) {
            float[] d = e.getValue();
            StringBuilder sb = new StringBuilder(e.getKey()).append(": ");
            List<String> parts = new ArrayList<>();
            if (Math.abs(d[0]) > 1e-6) parts.add(String.format("góc=%.0f", d[0]));
            if (Math.abs(d[1]) > 1e-6) parts.add(String.format("x=%.0f", d[1]));
            if (Math.abs(d[2]) > 1e-6) parts.add(String.format("y=%.0f", d[2]));
            if (Math.abs(d[3] - 1) > 1e-6) parts.add(String.format("sx=%.2f", d[3]));
            if (Math.abs(d[4] - 1) > 1e-6) parts.add(String.format("sy=%.2f", d[4]));
            mdlOverrides.addElement(sb.append(String.join(", ", parts)).toString());
        }
    }

    // ═════════════════════════ DUYỆT THƯ MỤC ═════════════════════════
    private List<String> folderNames = new ArrayList<>();

    private void reloadFolderList() {
        String rootName = (String) cbRoot.getSelectedItem();
        if (rootName == null) return;
        Path root = cfg.resourceRoot().resolve(rootName);
        lbFolderCount.setText("đang quét…");
        idGrid.setItems(List.of());
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() {
                List<String> out = new ArrayList<>();
                if (!Files.isDirectory(root)) return out;
                try (Stream<Path> s = Files.list(root)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (!Files.isDirectory(p)) continue;
                        if (hasSpine(p)) out.add(p.getFileName().toString());
                    }
                } catch (Exception ignored) { }
                out.sort(PlayerViewerFrame::compareIdAware);
                return out;
            }
            @Override protected void done() {
                try { folderNames = get(); } catch (Exception ex) { folderNames = new ArrayList<>(); }
                refreshFolderList();
            }
        }.execute();
    }

    private void refreshFolderList() {
        String q = txtRootFilter.getText().trim().toLowerCase();
        List<String> shown = new ArrayList<>();
        for (String n : folderNames) {
            if (q.isEmpty() || n.toLowerCase().contains(q)) shown.add(n);
        }
        idGrid.setItems(shown);
        lbFolderCount.setText(shown.size() + " mục"
                + (q.isEmpty() || shown.size() == folderNames.size() ? "" : " / " + folderNames.size()));
    }

    /** Thư mục có đủ bộ Spine (1 skeleton + 1 atlas) mới đáng hiện. */
    private static boolean hasSpine(Path dir) {
        boolean skel = false, atlas = false;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString().toLowerCase();
                if (n.endsWith(".json") || n.endsWith(".skel.bytes") || n.endsWith(".skel")) skel = true;
                else if (n.endsWith(".atlas.txt") || n.endsWith(".atlas")) atlas = true;
                if (skel && atlas) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** "2" đứng trước "10" (id là số), tên chữ thì so bình thường. */
    private static int compareIdAware(String a, String b) {
        boolean na = isId(a), nb = isId(b);
        if (na && nb) return Long.compare(Long.parseLong(a), Long.parseLong(b));
        if (na != nb) return na ? -1 : 1;
        return a.compareToIgnoreCase(b);
    }

    private static boolean isId(String s) {
        return !s.isEmpty() && s.length() <= 18 && s.chars().allMatch(Character::isDigit);
    }

    /** id số của tên thư mục, −1 nếu là tên chữ. */
    private static long idOf(String s) {
        return isId(s) ? Long.parseLong(s) : -1;
    }

    /** Cầu nối cho tab Hào Quang — nó cần biết nhân vật đang xem to nhỏ thế nào trên khung. */
    private final class HqHost implements HaoQuangPanel.Host {
        @Override public Float importScale() { return importScale; }
        @Override public double unitPerSkeleton() { return canvas.dispScale(); }
        /**
         * px-màn-hình trên 1 world unit. Suy NGƯỢC từ tỉ lệ player đang hiển thị thay vì lấy hằng
         * {@code PX_PER_WORLD × zoom}: nhờ vậy khi tắt "Cỡ thật" (chế độ vừa khung) aura vẫn giữ
         * đúng tỉ lệ so với nhân vật, thay vì to nhỏ lung tung như bản Python.
         */
        @Override public double pxPerWorldUnit() {
            if (importScale != null && importScale > 0) {
                return canvas.dispScale() / (importScale * PLAYER_SPINE_SCALE);
            }
            return PX_PER_WORLD;
        }
        @Override public Path currentSpineFolder() { return folder; }
        @Override public void repaintCanvas() { canvas.repaint(); }
    }

    // ═════════════════════════ CANVAS ═════════════════════════
    /**
     * Vẽ skeleton đang chọn. Pose → vẽ đi liền một mạch trên EDT (bất biến mà
     * {@code SpineSkeleton} yêu cầu — buffer pose là scratch dùng chung của skeleton).
     */
    private final class PvCanvas extends JComponent {
        private double zoom = 1.0;
        private double panX, panY;
        private Point drag;
        private int dragBtn;
        /** Hộp bao dùng để canh "vừa khung" — tính MỘT LẦN rồi giữ, không thì animation làm rung khung. */
        private double[] fitBox;

        /**
         * Hộp bao của hình thật ở thời điểm đang xem; skeleton chưa vẽ ra gì (flipbook đầu clip)
         * thì dò thêm vài mốc thời gian, cùng đường mà {@code PlayerViewTest} dùng.
         * Bí quá mới rơi về khung {@code skeleton.x/y/width/height} của JSON.
         */
        private double[] fitBox() {
            if (fitBox != null) return fitBox;
            SpineData.Animation an = currentAnim();
            double dur = duration();
            double[] b = null;
            for (int i = 0; i < 10 && b == null; i++) {
                float t = (i == 0) ? (float) animTime : (float) (dur * i / 10.0);
                sc.skeleton.pose(an, t);
                b = sc.renderer.worldBounds(sc.data, sc.skeleton);
            }
            if (b == null || b[2] - b[0] < 1e-6 || b[3] - b[1] < 1e-6) {
                b = new double[]{sc.data.skelX, sc.data.skelY,
                                 sc.data.skelX + sc.data.skelWidth, sc.data.skelY + sc.data.skelHeight};
            }
            fitBox = b;
            return b;
        }

        PvCanvas() {
            setOpaque(true);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    drag = e.getPoint();
                    dragBtn = e.getButton();
                }
                @Override public void mouseReleased(MouseEvent e) { drag = null; }
                @Override public void mouseDragged(MouseEvent e) {
                    if (drag == null) return;
                    int ddx = e.getX() - drag.x, ddy = e.getY() - drag.y;
                    drag = e.getPoint();
                    // Đang căn chỉnh hào quang: chuột TRÁI dời lớp aura đang chọn (đúng thao tác của
                    // bản Python); chuột giữa/phải vẫn dời khung nhìn.
                    if (auraOn() && dragBtn == MouseEvent.BUTTON1) {
                        double u = Math.max(1e-6, dispScale());
                        hq.dragBy(ddx / u, ddy / u);
                    } else {
                        panX += ddx;
                        panY += ddy;
                    }
                    repaint();
                }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    double f = (e.getWheelRotation() < 0) ? 1.1 : 1 / 1.1;
                    double nz = Math.max(0.05, Math.min(8.0, zoom * f));
                    // giữ điểm dưới con trỏ đứng yên khi phóng
                    double ax = anchorX(), ay = anchorY();
                    panX += (e.getX() - ax) * (1 - nz / zoom);
                    panY += (e.getY() - ay) * (1 - nz / zoom);
                    zoom = nz;
                    lbZoom.setText(Math.round(zoom * 100) + "%");
                    repaint();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener(ma);
        }

        void resetView() {
            zoom = 1.0;
            panX = panY = 0;
            fitBox = null;
            lbZoom.setText("100%");
        }

        private boolean realSize() { return chkRealSize.isSelected() && importScale != null; }

        /** px-màn-hình trên 1 đơn vị skeleton. */
        private double dispScale() {
            if (sc == null) return 1;
            if (realSize()) return importScale * PLAYER_SPINE_SCALE * PX_PER_WORLD * zoom;
            double[] b = fitBox();
            double w = Math.max(1e-3, b[2] - b[0]), h = Math.max(1e-3, b[3] - b[1]);
            return Math.max(0.01, Math.min((getWidth() - 40) / w, (getHeight() - 40) / h)) * zoom;
        }

        /** Gốc skeleton (0,0) rơi vào đâu trên màn hình. */
        private double anchorX() {
            if (sc == null) return getWidth() / 2.0;
            if (realSize()) return getWidth() / 2.0 + panX;
            double[] b = fitBox();
            return getWidth() / 2.0 - (b[0] + b[2]) / 2 * dispScale() + panX;
        }

        private double anchorY() {
            if (sc == null) return getHeight() * 0.78;
            if (realSize()) return getHeight() * 0.78 + panY;
            double[] b = fitBox();
            return getHeight() / 2.0 + (b[1] + b[3]) / 2 * dispScale() + panY;   // world Y-up → màn hình Y-down
        }

        /** Có đang vẽ overlay hào quang không (tab đang mở + đã nạp frame). */
        private boolean auraOn() { return hq != null && hq.active && hq.hasFrames(); }

        private void drawPlayer(Graphics2D g2, double ax, double ay, double s) {
            sc.skeleton.pose(currentAnim(), (float) animTime);
            sc.renderer.draw(g2, sc.data, sc.skeleton, ax, ay, s, 1.0);
        }

        /**
         * Vẽ 1 lớp hào quang. Neo theo <b>PIVOT của sprite đặt tại gốc spine</b> (chân nhân vật) —
         * đúng như game, KHÔNG phải theo tâm ảnh. Cỡ tính từ PPU: {@code px-sprite / PPU} = world
         * unit, nhân {@code pxPerWorldUnit} ra px-màn-hình ⇒ nhìn thấy sao thì trong game đúng vậy.
         *
         * @param ox,oy offset của lớp, đơn vị SKELETON (giống thanh Offset và thao tác kéo chuột)
         */
        private void drawAura(Graphics2D g2, java.awt.image.BufferedImage img, double[] piv, Double ppu,
                              double layerScale, double ox, double oy, double ax, double ay, double unit) {
            if (img == null) return;
            double sa = (ppu != null && ppu > 0) ? hqHost.pxPerWorldUnit() / ppu : unit;
            sa *= hq.scaleBoth * layerScale;
            if (sa <= 0) return;
            int dw = Math.max(1, (int) Math.round(img.getWidth() * sa));
            int dh = Math.max(1, (int) Math.round(img.getHeight() * sa));
            double tx = ax + ox * unit, ty = ay + oy * unit;
            g2.drawImage(img, (int) Math.round(tx - piv[0] * dw),
                    (int) Math.round(ty - (1 - piv[1]) * dh), dw, dh, null);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                int w = getWidth(), h = getHeight();
                g2.setColor(CANVAS_BG);
                g2.fillRect(0, 0, w, h);
                g2.setColor(GRID_COLOR);
                for (int x = 0; x < w; x += GRID_STEP) g2.drawLine(x, 0, x, h);
                for (int y = 0; y < h; y += GRID_STEP) g2.drawLine(0, y, w, y);

                if (sc == null) {
                    g2.setColor(Theme.TEXT_DIM);
                    g2.setFont(Theme.font(13, Font.PLAIN));
                    g2.drawString("Chọn một ô id ở cột trái, hoặc bấm \"Chọn…\" để mở thư mục Spine", 24, h / 2);
                    return;
                }

                double ax = anchorX(), ay = anchorY(), s = dispScale();
                g2.setColor(GROUND_COLOR);
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(0, (int) ay, w, (int) ay);
                g2.setColor(AXIS_COLOR);
                g2.drawLine((int) ax, 0, (int) ax, h);

                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                if (!auraOn()) {
                    drawPlayer(g2, ax, ay, s);
                } else if (hq.twoLayer && hq.frontImage() != null) {
                    // 3 lớp đúng như game: aura SAU → nhân vật → aura TRƯỚC
                    if (!hq.hideBack) drawAura(g2, hq.backImage(), hq.backPivot, hq.backPpu,
                            hq.backScale, hq.dx, hq.dy, ax, ay, s);
                    drawPlayer(g2, ax, ay, s);
                    if (!hq.hideFront) drawAura(g2, hq.frontImage(), hq.frontPivot, hq.frontPpu,
                            hq.frontScale, hq.fdx, hq.fdy, ax, ay, s);
                } else if (hq.behind) {
                    if (!hq.hideBack) drawAura(g2, hq.backImage(), hq.backPivot, hq.backPpu,
                            hq.backScale, hq.dx, hq.dy, ax, ay, s);
                    drawPlayer(g2, ax, ay, s);
                } else {
                    drawPlayer(g2, ax, ay, s);
                    if (!hq.hideBack) drawAura(g2, hq.backImage(), hq.backPivot, hq.backPpu,
                            hq.backScale, hq.dx, hq.dy, ax, ay, s);
                }
            } catch (Exception ex) {
                // Swing NUỐT exception trong paint — không tự in ra thì lỗi vẽ biến thành "màn hình
                // trống" không rõ nguyên nhân (đúng bẫy đã dính với Theme.btn null).
                System.err.println("[PlayerViewer] lỗi vẽ: " + ex);
                ex.printStackTrace();
            } finally {
                g2.dispose();
            }
        }
    }

    // ═════════════════════════ TIỆN ═════════════════════════
    /** DocumentListener gọn cho ô lọc. */
    private record Doc(Runnable r) implements javax.swing.event.DocumentListener {
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }

    // ── Móc cho CLI chụp ảnh giao diện (lệnh {@code player}) — không dùng trong luồng người dùng ──
    /** Chọn tab theo chỉ số: 0 = Animation, 1 = Slot, 2 = Bone, 3 = Hào Quang. */
    public void selectTab(int i) {
        if (tabs != null && i >= 0 && i < tabs.getTabCount()) tabs.setSelectedIndex(i);
    }

    /** Chọn animation theo tên (không có tên đó ⇒ giữ nguyên) và chọn luôn bone đầu tiên khớp lọc. */
    public void selectAnim(String name) {
        if (name == null) return;
        for (int i = 0; i < cbAnim.getItemCount(); i++) {
            if (name.equalsIgnoreCase(cbAnim.getItemAt(i))) { cbAnim.setSelectedIndex(i); return; }
        }
    }

    /** Nạp thẳng một thư mục (CLI dùng). */
    public void loadFolder(String path) {
        if (path == null || path.isEmpty()) return;
        txtFolder.setText(path);
        load(Paths.get(path));
    }

    /** Bật/tắt ô "Cỡ thật như game" (CLI dùng để soi nhánh vừa-khung). */
    public void setRealSize(boolean on) {
        chkRealSize.setSelected(on);
        canvas.resetView();
        canvas.repaint();
    }

    /** Mở tab Hào Quang, chọn hệ và nạp aura (CLI dùng để chụp đúng màn cần soi). */
    public void debugHaoQuang(String mode, String back, String front) {
        selectTab(3);
        if (mode != null && !mode.isEmpty()) hq.debugMode(mode);
        hq.debugLoad(back, front);
        hq.setTabActive(true);
    }

    /** Bấm "Chạy" (CLI dùng để chụp một khung giữa animation, kiểm luôn đường Timer). */
    public void startPlayPublic() { startPlay(); }

    /** Chọn bone theo tên để ảnh chụp có sẵn slider (CLI dùng). */
    public void selectBonePublic(String name) {
        selectBone(name);
        lstBones.setSelectedValue(name, true);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Player Viewer", JOptionPane.INFORMATION_MESSAGE);
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Lỗi", JOptionPane.ERROR_MESSAGE);
    }
}
