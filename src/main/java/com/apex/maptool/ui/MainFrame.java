package com.apex.maptool.ui;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.db.Db;
import com.apex.maptool.db.ShopDao;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Vỏ app dark + amber (handoff "UI Redesign"): sidebar 240 (brand + 2 nút hệ thống 1 hàng +
 * nav sections + 2 nút NGUY HIỂM ghim chân + footer DB) · top bar 56 (tiêu đề / ngữ cảnh / chip
 * trạng thái + 2 nút cửa sổ 32×32) · main CARD (CardLayout — mỗi tool 1 card full-khung, KHÔNG
 * dùng MDI/JInternalFrame vì maximize nhiều frame trong JDesktopPane xung đột nhau gây biến dạng).
 *
 * <p>Đổi so với bản trước (theo handoff §5): 4 nút hệ thống to xếp dọc chiếm 1/3 sidebar → 2 nút
 * hay dùng gộp 1 hàng ở trên, 2 nút phá huỷ (Đóng tool / Thoát app) đẩy xuống sát footer để KHÔNG
 * đứng cạnh nút thường; nút cửa sổ ở top bar thu từ 104px chữ còn ô vuông 32.
 */
public final class MainFrame extends JFrame {

    /** Chiều rộng sidebar theo bản thiết kế. */
    private static final int SIDE_W = 240;
    /** Chiều cao top bar theo bản thiết kế. */
    private static final int TOP_H = 56;

    private final ToolConfig cfg;
    private final Supplier<JComponent> mapEditorFactory;
    private JLabel topTitle, topSlash, topContext;
    private Theme.Chip topChip;
    private final Map<String, JButton> navItems = new LinkedHashMap<>();
    private String activeNav = "";

    // main = chồng card: "empty" (watermark) + mỗi tool 1 card
    private final CardLayout cards = new CardLayout();
    private final JPanel stack = new JPanel(cards);
    private final Map<String, JComponent> toolCards = new LinkedHashMap<>();
    private String currentTool = null;

    // Tài nguyên Unity cho tool "Bố cục Map" — dựng LAZY 1 lần rồi dùng lại (quét GUID rất tốn thời gian).
    private com.apex.maptool.unity.GuidIndex layoutGuid;
    private com.apex.maptool.unity.MaterialResolver layoutMat;
    private com.apex.maptool.unity.TextureCache layoutTex;

    public MainFrame(ToolConfig cfg, Supplier<JComponent> mapEditorFactory) {
        super("UocRongOnline Tools");
        this.cfg = cfg;
        this.mapEditorFactory = mapEditorFactory;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        // Bản thiết kế dựng ở 1920×1080 — mở đúng cỡ đó nếu màn hình cho phép, chật hơn thì
        // phóng to hết cỡ (3 cột 272/452 + khung xem chỉ đủ chỗ từ ~1600 trở lên).
        Rectangle sc = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1920, sc.width), Math.min(1080, sc.height));
        setMinimumSize(new Dimension(1280, 760));
        setLocationRelativeTo(null);
        if (sc.width < 1600) setExtendedState(MAXIMIZED_BOTH);
        getContentPane().setBackground(Theme.BG_APP);
        setLayout(new BorderLayout());

        stack.setOpaque(false);
        stack.add(buildEmptyCard(), "empty");

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Theme.BG_MAIN);
        center.add(buildTopBar(), BorderLayout.NORTH);
        center.add(stack, BorderLayout.CENTER);

        add(buildSidebar(), BorderLayout.WEST);
        add(center, BorderLayout.CENTER);
    }

    /** Mở tool mặc định (Shop) sau khi cửa sổ hiện — như demo. */
    public void openDefaultTool() {
        openShopEditor();
    }

    /** Mở tool theo khoá nav (dùng cho lệnh {@code appsnap} chụp đúng tool cần soi). */
    public void openTool(String key) {
        switch (key) {
            case "map" -> openMapEditor();
            case "shop" -> openShopEditor();
            case "layout" -> openMapLayoutEditor();
            case "equip" -> openEquipEditor();
            case "gift" -> openGiftcodeEditor();
            case "player" -> openPlayerViewer();
            default -> openShopEditor();
        }
    }

    /** Card trống: watermark khi chưa mở tool. */
    private JComponent buildEmptyCard() {
        return new JPanel() {
            { setOpaque(false); }
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setColor(Theme.BG_MAIN);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                String s = "Ước Rồng Tools";
                g2.setFont(Theme.font(30, Font.BOLD));
                int w = g2.getFontMetrics().stringWidth(s);
                g2.setColor(new Color(255, 255, 255, 16));
                g2.drawString(s, (getWidth() - w) / 2, getHeight() / 2);
                String s2 = "Chọn chức năng ở thanh bên trái";
                g2.setFont(Theme.font(13, Font.PLAIN));
                int w2 = g2.getFontMetrics().stringWidth(s2);
                g2.setColor(new Color(255, 255, 255, 28));
                g2.drawString(s2, (getWidth() - w2) / 2, getHeight() / 2 + 26);
            }
        };
    }

    // ═══════════════ TOP BAR ═══════════════
    private JComponent buildTopBar() {
        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBackground(Theme.BG_PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                new EmptyBorder(0, 24, 0, 20)));
        bar.setPreferredSize(new Dimension(0, TOP_H));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, TOP_H));

        topTitle = Theme.label("Ước Rồng Tools", 16, Font.BOLD, Theme.TEXT);
        topSlash = Theme.label("/", 15, Font.PLAIN, Theme.BORDER);
        topContext = Theme.label("", 13, Font.PLAIN, Theme.TEXT_MUTED);
        topChip = new Theme.Chip("", null, 24, 12, false);
        topSlash.setVisible(false);
        topChip.setVisible(false);

        bar.add(topTitle);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(topSlash);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(topContext);
        bar.add(Box.createHorizontalStrut(14));
        bar.add(topChip);
        bar.add(Box.createHorizontalGlue());
        bar.add(Theme.iconButton(Theme.icon(Theme.IC_EXPAND, 15, Theme.TEXT_MUTED), 32, 32,
                "Phóng to cửa sổ", e -> zoomActive()));
        bar.add(Box.createHorizontalStrut(8));
        bar.add(Theme.iconButton(Theme.icon(Theme.IC_MINUS, 15, Theme.TEXT_MUTED), 32, 32,
                "Thu nhỏ cửa sổ", e -> restoreActive()));
        return bar;
    }

    /** Ngữ cảnh sau dấu "/" trên top bar ("Hào Quang", "Map 1 — Làng Aru"). */
    public void setTopContext(String ctx) {
        boolean has = (ctx != null && !ctx.isBlank());
        topContext.setText(has ? ctx : "");
        topSlash.setVisible(has);
        topContext.getParent().revalidate();
        topContext.getParent().repaint();
    }

    /** Chip trạng thái trên top bar ("chưa sửa gì" / "3 phần tử SỬA CHƯA LƯU"). */
    public void setTopStatus(String text, boolean warn) {
        boolean has = (text != null && !text.isBlank());
        topChip.setVisible(has);
        if (has) {
            topChip.setText(text);
            topChip.setTint(warn ? Theme.ACCENT : null);
        }
        topChip.getParent().revalidate();
        topChip.getParent().repaint();
    }

    // ═══════════════ SIDEBAR ═══════════════
    private JComponent buildSidebar() {
        JPanel content = Theme.colBox();

        // ── Brand ──
        JLabel brand = new JLabel("APEX GAMES");
        brand.setForeground(Theme.ACCENT);
        brand.setFont(Theme.tracked(11, Font.BOLD, 0.22));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(Theme.capH(brand));
        content.add(Box.createVerticalStrut(6));

        JPanel title = Theme.row();
        JLabel t1 = Theme.label("ƯỚC RỒNG", 19, Font.BOLD, Theme.TEXT);
        JLabel t2 = Theme.label("TOOLS", 19, Font.BOLD, Theme.ACCENT);
        title.add(t1);
        title.add(Box.createHorizontalStrut(7));
        title.add(t2);
        title.add(Box.createHorizontalGlue());
        content.add(Theme.capH(title));
        content.add(Box.createVerticalStrut(9));

        JPanel underline = new JPanel();
        underline.setBackground(Theme.ACCENT);
        underline.setAlignmentX(Component.LEFT_ALIGNMENT);
        Theme.lockSize(underline, 44, 3);
        content.add(underline);
        content.add(Box.createVerticalStrut(20));

        // ── 2 nút hệ thống hay dùng: 1 hàng ──
        JPanel sysRow = new JPanel(new GridLayout(1, 2, 8, 0));
        sysRow.setOpaque(false);
        sysRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        sysRow.add(sideSmall("Toàn màn hình", Theme.BG_SURFACE2, Theme.TEXT_2, Theme.BORDER,
                "Bật/tắt toàn màn hình", this::toggleFullscreen));
        sysRow.add(sideSmall("Khởi động lại", Theme.BG_SURFACE2, Theme.TEXT_2, Theme.BORDER,
                "Dựng lại giao diện (giữ kết nối DB)", this::restartApp));
        sysRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        sysRow.setPreferredSize(new Dimension(SIDE_W, 34));
        content.add(sysRow);
        content.add(Box.createVerticalStrut(22));

        // ── Nav sections ──
        content.add(navSection("DATABASE"));
        content.add(navItem("mysql", "MySQL Config", this::openMysqlConfig));
        content.add(Box.createVerticalStrut(16));
        content.add(navSection("EDITORS"));
        content.add(navItem("map", "Map Editor", this::openMapEditor));
        content.add(navGap());
        content.add(navItem("shop", "Shop", this::openShopEditor));
        content.add(navGap());
        content.add(navItem("layout", "Bố cục Map", this::openMapLayoutEditor));
        content.add(navGap());
        content.add(navItem("equip", "Chỉ số trang bị", this::openEquipEditor));
        content.add(navGap());
        content.add(navItem("gift", "Giftcode", this::openGiftcodeEditor));
        content.add(Box.createVerticalStrut(16));
        content.add(navSection("SPINE"));
        content.add(navItem("player", "Player Viewer", this::openPlayerViewer));

        // ── Chân: 2 nút PHÁ HUỶ tách hẳn khỏi nút thường + footer DB ──
        JPanel bottom = Theme.colBox();
        JPanel dangerRow = new JPanel(new GridLayout(1, 2, 8, 0));
        dangerRow.setOpaque(false);
        dangerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        dangerRow.add(sideSmallTint("Đóng tool", Theme.RED, "Đóng tool đang mở", this::closeActiveTool));
        dangerRow.add(sideSmallTint("Thoát app", Theme.RED, "Thoát hẳn ứng dụng", () -> System.exit(0)));
        dangerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        dangerRow.setPreferredSize(new Dimension(SIDE_W, 32));
        bottom.add(dangerRow);
        bottom.add(Box.createVerticalStrut(12));
        bottom.add(Theme.hr());
        bottom.add(Box.createVerticalStrut(12));

        JPanel footer = Theme.row();
        JPanel dot = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.GREEN);
                g2.fillOval(0, 0, 7, 7);
                g2.dispose();
            }
        };
        dot.setOpaque(false);
        dot.setAlignmentY(Component.CENTER_ALIGNMENT);
        Theme.lockSize(dot, 7, 7);
        JLabel ver = Theme.monoLabel("v0.1.0 · " + dbHost(), 12, Theme.TEXT_DIM);
        footer.add(dot);
        footer.add(Box.createHorizontalStrut(7));
        footer.add(ver);
        footer.add(Box.createHorizontalGlue());
        bottom.add(Theme.capH(footer));

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.BG_PANEL);
        side.setPreferredSize(new Dimension(SIDE_W, 0));
        side.setMinimumSize(new Dimension(SIDE_W, 0));
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.DIVIDER),
                new EmptyBorder(20, 14, 16, 14)));
        side.add(content, BorderLayout.NORTH);
        side.add(bottom, BorderLayout.SOUTH);
        return side;
    }

    private static Component navGap() { return Box.createVerticalStrut(2); }

    private static JComponent navSection(String title) {
        JLabel l = Theme.sectionHeader(title, Theme.TEXT_DIM);
        l.setFont(Theme.tracked(11, Font.BOLD, 0.2));
        l.setBorder(new EmptyBorder(0, 0, 8, 0));
        return Theme.capH(l);
    }

    /** Nút hệ thống nhỏ (1 trong 2 ô của hàng trên). */
    private JComponent sideSmall(String text, Color fill, Color fg, Color border, String tip, Runnable onClick) {
        JButton b = flatBtn(text, fill, fg, border, Theme.ACCENT, 8, false);
        b.setFont(Theme.font(12, Font.BOLD));
        b.setToolTipText(tip);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** Nút PHÁ HUỶ (tint đỏ, chân sidebar). */
    private JComponent sideSmallTint(String text, Color c, String tip, Runnable onClick) {
        JButton b = flatBtn(text, Theme.alpha(c, 23), c, Theme.alpha(c, 90), c, 8, true);
        b.setFont(Theme.font(12, Font.BOLD));
        b.setToolTipText(tip);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** Item nav: chữ 13, dòng cao 36; active → nền accent-nav + thanh trái 3px accent + chữ accent. */
    private JComponent navItem(String key, String text, Runnable onClick) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean active = key.equals(activeNav);
                if (active) {
                    g2.setColor(Theme.ACCENT_NAV);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.setColor(Theme.ACCENT);
                    g2.fillRoundRect(0, 6, 3, getHeight() - 12, 2, 2);
                } else if (getModel().isRollover()) {
                    g2.setColor(Theme.BG_SURFACE2);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }
                g2.dispose();
                setForeground(active ? Theme.ACCENT_HOVER : Theme.TEXT_2);
                setFont(Theme.font(13, active ? Font.BOLD : Font.PLAIN));
                super.paintComponent(g);
            }
        };
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setForeground(Theme.TEXT_2);
        b.setFont(Theme.font(13, Font.PLAIN));
        b.setBorder(new EmptyBorder(0, 12, 0, 12));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        b.setPreferredSize(new Dimension(10, 36));
        b.addActionListener(e -> onClick.run());
        navItems.put(key, b);
        return b;
    }

    private void setActiveNav(String key) {
        activeNav = key;
        for (JButton b : navItems.values()) b.repaint();
    }

    /** Nút phẳng bo góc vẽ tay dùng chung (ghost / tint đỏ). */
    private static JButton flatBtn(String text, Color fill, Color fg, Color border, Color hoverColor,
                                   int arc, boolean tintFill) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean hover = getModel().isRollover();
                boolean press = getModel().isPressed();
                Color f = fill;
                if (tintFill) {
                    int a = press ? 60 : hover ? 46 : fill.getAlpha();
                    f = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), a);
                } else if (press) f = Theme.brighten(fill, 8);
                g2.setColor(f);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                Color bc = border;
                if (!tintFill && hover && hoverColor != null) bc = hoverColor;
                if (bc != null) {
                    g2.setColor(bc);
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
                g2.dispose();
                if (!tintFill && hover && hoverColor != null) setForeground(hoverColor);
                else setForeground(fg);
                super.paintComponent(g);
            }
        };
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setForeground(fg);
        // Lề mặc định của FlatLaf là 14px mỗi bên → 2 ô rộng 102px không đủ chỗ cho "Toàn màn hình"
        // và nhãn bị cắt thành "Toàn mà…". Handoff §8: nhãn KHÔNG được cắt/xuống dòng.
        b.setBorder(new EmptyBorder(0, 3, 0, 3));
        b.setMargin(new Insets(0, 3, 0, 3));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private String dbHost() {
        return cfg.dbUrl().replace("jdbc:mysql://", "").replaceAll("[/?].*", "");
    }

    // ═══════════════ QUẢN LÝ CARD TOOL ═══════════════
    /**
     * Bọc content tool. {@code padded = true} → thêm lề + viền card (các tool CHƯA bố cục lại);
     * false → tool tự lo lề 16/20/18 và viền panel như bản thiết kế (Player Viewer, Bố cục Map).
     */
    private static JComponent wrapCard(JComponent content, boolean padded) {
        if (!padded) {
            JPanel plain = new JPanel(new BorderLayout());
            plain.setBackground(Theme.BG_MAIN);
            plain.add(content, BorderLayout.CENTER);
            return plain;
        }
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Theme.BG_SURFACE);
        card.setBorder(BorderFactory.createLineBorder(Theme.BORDER_SOFT));
        card.add(content, BorderLayout.CENTER);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setBackground(Theme.BG_MAIN);
        wrap.setBorder(new EmptyBorder(16, 20, 18, 20));
        wrap.add(card, BorderLayout.CENTER);
        return wrap;
    }

    /** Hiện card tool đã có (hoặc vừa thêm) + cập nhật nav/tiêu đề/ngữ cảnh. */
    private void showTool(String key, String title, String ctx) {
        cards.show(stack, key);
        currentTool = key;
        setActiveNav(key);
        if (topTitle != null) topTitle.setText(title);
        setTopContext(ctx);
        setTopStatus(null, false);
        // card mới add vào container ĐANG hiển thị → bắt buộc re-layout, không thì bị cắt/lệch
        stack.revalidate();
        stack.repaint();
    }

    private void closeActiveTool() {
        if (currentTool == null) { info("Không có tool nào đang mở."); return; }
        JComponent card = toolCards.remove(currentTool);
        if (card != null) stack.remove(card);
        currentTool = null;
        setActiveNav("");
        if (topTitle != null) topTitle.setText("Ước Rồng Tools");
        setTopContext(null);
        setTopStatus(null, false);
        cards.show(stack, "empty");
        stack.revalidate();
        stack.repaint();
    }

    // ═══════════════ ACTIONS ═══════════════
    private void restartApp() {
        int ok = JOptionPane.showConfirmDialog(this,
                "Khởi động lại giao diện tool?\n(kết nối DB giữ nguyên, các tool đang mở sẽ đóng)",
                "Khởi động lại", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        MainFrame mf = new MainFrame(cfg, mapEditorFactory);
        mf.setVisible(true);
        SwingUtilities.invokeLater(mf::openDefaultTool);
        dispose();
    }

    private void zoomActive() {
        setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
    }

    private void restoreActive() {
        setExtendedState(NORMAL);
    }

    private void toggleFullscreen() {
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        setExtendedState(max ? NORMAL : MAXIMIZED_BOTH);
    }

    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Ước Rồng Tools", JOptionPane.INFORMATION_MESSAGE);
    }

    // ═══════════════ MySQL Config ═══════════════
    private void openMysqlConfig() {
        JTextField txtUrl = new JTextField(cfg.dbUrl(), 32);
        JTextField txtUser = new JTextField(cfg.dbUser(), 18);
        JPasswordField txtPass = new JPasswordField(cfg.dbPass(), 18);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 6, 5, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        cfgRow(form, c, y++, "JDBC URL", txtUrl);
        cfgRow(form, c, y++, "User", txtUser);
        cfgRow(form, c, y++, "Password", txtPass);

        JLabel status = new JLabel(" ");
        status.setFont(Theme.font(12, Font.PLAIN));
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.weightx = 1;
        form.add(status, c);

        JButton btnTest = Theme.tint("Test kết nối", Theme.BLUE, 32, e -> {
            status.setForeground(Theme.TEXT_MUTED);
            status.setText("Đang kết nối...");
            String url = txtUrl.getText().trim(), user = txtUser.getText().trim(), pass = new String(txtPass.getPassword());
            new SwingWorker<Object[], Void>() {
                @Override protected Object[] doInBackground() {
                    long t0 = System.currentTimeMillis();
                    try {
                        Class.forName("com.mysql.cj.jdbc.Driver");
                        try (java.sql.Connection cc = java.sql.DriverManager.getConnection(url, user, pass)) {
                            cc.isValid(3);
                            return new Object[]{true, "Kết nối OK (" + (System.currentTimeMillis() - t0) + "ms)"};
                        }
                    } catch (Exception ex) { return new Object[]{false, "Fail: " + ex.getMessage()}; }
                }
                @Override protected void done() {
                    try {
                        Object[] r = get();
                        status.setForeground((Boolean) r[0] ? Theme.GREEN : Theme.RED);
                        status.setText((String) r[1]);
                    } catch (Exception ignored) {}
                }
            }.execute();
        });

        JPanel wrap = new JPanel(new BorderLayout(0, 8));
        wrap.add(form, BorderLayout.CENTER);
        JPanel testRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        testRow.add(btnTest);
        wrap.add(testRow, BorderLayout.SOUTH);

        Object[] opts = {"Lưu (cần khởi động lại)", "Đóng"};
        int res = JOptionPane.showOptionDialog(this, wrap, "MySQL Config",
                JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[1]);
        if (res == 0) {
            try {
                cfg.saveDb(txtUrl.getText().trim(), txtUser.getText().trim(), new String(txtPass.getPassword()));
                info("Đã lưu config.properties.\nBấm \"Khởi động lại\" để áp dụng kết nối mới.");
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Lưu fail:\n" + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static void cfgRow(JPanel form, GridBagConstraints c, int y, String label, JComponent field) {
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = y; c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        form.add(field, c);
    }

    // ═══════════════ MỞ TOOL (card) ═══════════════
    private void openMapEditor() {
        if (toolCards.containsKey("map")) { showTool("map", "Map Editor", null); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingUtilities.invokeLater(() -> {
            try {
                JComponent content = mapEditorFactory.get();
                if (content == null) return;
                JComponent card = wrapCard(content, true);
                toolCards.put("map", card);
                stack.add(card, "map");
                showTool("map", "Map Editor", null);
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
        });
    }

    private void openShopEditor() {
        if (toolCards.containsKey("shop")) { showTool("shop", "Quản lý Shop", null); return; }
        try {
            Db db = new Db(cfg);
            java.util.List<com.apex.maptool.db.InfoDao.InfoItem> npcs = new java.util.ArrayList<>();
            java.util.function.IntFunction<java.awt.image.BufferedImage> npcIcon = id -> null;
            try {
                var infoDao = new com.apex.maptool.db.InfoDao(db);
                npcs = infoDao.npcs();
                var tex = new com.apex.maptool.unity.TextureCache();
                var sr = new com.apex.maptool.unity.SpriteResolver(cfg, tex);
                java.util.Map<Integer, Integer> modelMap = new java.util.HashMap<>();
                for (var n : npcs) modelMap.put(n.id(), n.spinId());
                sr.setNpcModelMap(modelMap);
                npcIcon = sr::npc;
            } catch (Exception ex) { System.err.println("[MainFrame] npc list fail: " + ex.getMessage()); }
            var equipDao = new com.apex.maptool.db.EquipDao(db);
            // db → đọc option từ bảng buff_info (danh sách thật, có cả option mới); source enum chỉ dự phòng
            var attrs = new com.apex.maptool.db.AttrNames(cfg.serverRepo(), db);
            ShopEditorFrame se = new ShopEditorFrame(new ShopDao(db), npcs, npcIcon, equipDao, attrs); // JFrame ẩn — mượn content
            JComponent card = wrapCard((JComponent) se.getContentPane(), true);
            toolCards.put("shop", card);
            stack.add(card, "shop");
            showTool("shop", "Quản lý Shop", null);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openGiftcodeEditor() {
        if (toolCards.containsKey("gift")) { showTool("gift", "Giftcode", null); return; }
        try {
            var dao = new com.apex.maptool.db.GiftcodeDao(new Db(cfg), cfg.gatewayUrl());
            GiftcodeEditorFrame gf = new GiftcodeEditorFrame(dao);   // JFrame ẩn — mượn content
            JComponent card = wrapCard((JComponent) gf.getContentPane(), true);
            toolCards.put("gift", card);
            stack.add(card, "gift");
            showTool("gift", "Giftcode", null);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Bố cục Map: sửa thẳng prefab client (layout / BG / sorting trước-sau player / đường player đứng).
     * Nặng lúc mở lần đầu (dựng GUID index + đọc DB) → chạy trong SwingWorker + con trỏ chờ,
     * KHÔNG dựng trên EDT kẻo app đứng hình vài giây.
     */
    private void openMapLayoutEditor() {
        if (toolCards.containsKey("layout")) { showTool("layout", "Bố cục Map", layoutCtx); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<java.util.List<com.apex.maptool.db.InfoDao.InfoItem>, Void>() {
            @Override protected java.util.List<com.apex.maptool.db.InfoDao.InfoItem> doInBackground() throws Exception {
                if (layoutGuid == null) {   // lần đầu quét Assets (~vài giây), lần sau đọc guid-index.cache
                    var gi = new com.apex.maptool.unity.GuidIndex();
                    gi.buildOrLoad(cfg.assetsRoot(), cfg.guidCacheFile());
                    layoutTex = new com.apex.maptool.unity.TextureCache();
                    layoutMat = new com.apex.maptool.unity.MaterialResolver(gi);
                    layoutGuid = gi;
                }
                try {
                    return new com.apex.maptool.db.InfoDao(new Db(cfg)).maps();   // chỉ để lấy TÊN map
                } catch (Exception ex) {   // DB lỗi → vẫn mở tool (xem/sửa prefab bình thường, chỉ thiếu tên map)
                    System.err.println("[MainFrame] danh sách map fail: " + ex.getMessage());
                    return new java.util.ArrayList<>();
                }
            }
            @Override protected void done() {
                try {
                    // JFrame ẩn — mượn content (giống Shop/Equip/Giftcode)
                    MapLayoutEditorFrame lf = new MapLayoutEditorFrame(cfg, layoutGuid, layoutMat, layoutTex, get());
                    lf.setShell((ctx, st, warn) -> {
                        layoutCtx = ctx;
                        if ("layout".equals(currentTool)) { setTopContext(ctx); setTopStatus(st, warn); }
                    });
                    JComponent card = wrapCard((JComponent) lf.getContentPane(), false);
                    toolCards.put("layout", card);
                    stack.add(card, "layout");
                    showTool("layout", "Bố cục Map", layoutCtx);
                    lf.pushShell();
                } catch (Exception e) {
                    Throwable c = (e.getCause() != null) ? e.getCause() : e;
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "Mở Bố cục Map fail:\n" + c, "Lỗi", JOptionPane.ERROR_MESSAGE);
                } finally {
                    setCursor(Cursor.getDefaultCursor());
                }
            }
        }.execute();
    }

    /** Ngữ cảnh gần nhất của 2 tool đã bố cục lại (giữ lại để quay lại card là hiện đúng). */
    private String layoutCtx, playerCtx;

    /** Tool báo ngược lên vỏ app: ngữ cảnh sau dấu "/" + chip trạng thái. */
    public interface Shell {
        void update(String context, String status, boolean warn);
    }

    /**
     * Player Viewer: xem 1 thư mục Spine bất kỳ (Player/{id}, HonMa…) — đổi skin/animation, tua
     * timeline, bật/tắt slot, kéo delta bone. Chỉ đọc file client, KHÔNG cần DB.
     */
    private void openPlayerViewer() {
        if (toolCards.containsKey("player")) { showTool("player", "Player Viewer", playerCtx); return; }
        try {
            PlayerViewerFrame pv = new PlayerViewerFrame(cfg);   // JFrame ẩn — mượn content
            pv.setShell((ctx, st, warn) -> {
                playerCtx = ctx;
                if ("player".equals(currentTool)) { setTopContext(ctx); setTopStatus(st, warn); }
            });
            JComponent card = wrapCard((JComponent) pv.getContentPane(), false);
            toolCards.put("player", card);
            stack.add(card, "player");
            showTool("player", "Player Viewer", playerCtx);
            pv.pushShell();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Mở Player Viewer fail:\n" + e, "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openEquipEditor() {
        if (toolCards.containsKey("equip")) { showTool("equip", "Chỉ số trang bị", null); return; }
        try {
            Db edb = new Db(cfg);
            var dao = new com.apex.maptool.db.EquipDao(edb);
            var attrs = new com.apex.maptool.db.AttrNames(cfg.serverRepo(), edb);
            EquipEditorFrame ee = new EquipEditorFrame(dao, attrs);   // JFrame ẩn — mượn content
            JComponent card = wrapCard((JComponent) ee.getContentPane(), true);
            toolCards.put("equip", card);
            stack.add(card, "equip");
            showTool("equip", "Chỉ số trang bị", null);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }
}
