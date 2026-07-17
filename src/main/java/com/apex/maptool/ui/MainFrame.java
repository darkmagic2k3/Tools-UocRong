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
 * Vỏ app dark + amber (handoff UocRong Tools v2): sidebar (brand + nút hệ thống + nav sections)
 * + top bar + main CARD (CardLayout — mỗi tool 1 card full-khung, KHÔNG dùng MDI/JInternalFrame
 * vì maximize nhiều frame trong JDesktopPane xung đột nhau gây biến dạng).
 */
public final class MainFrame extends JFrame {

    private final ToolConfig cfg;
    private final Supplier<JComponent> mapEditorFactory;
    private JLabel topTitle;
    private final Map<String, JButton> navItems = new LinkedHashMap<>();
    private String activeNav = "";

    // main = chồng card: "empty" (watermark) + mỗi tool 1 card
    private final CardLayout cards = new CardLayout();
    private final JPanel stack = new JPanel(cards);
    private final Map<String, JComponent> toolCards = new LinkedHashMap<>();
    private String currentTool = null;

    public MainFrame(ToolConfig cfg, Supplier<JComponent> mapEditorFactory) {
        super("UocRongOnline Tools");
        this.cfg = cfg;
        this.mapEditorFactory = mapEditorFactory;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1400, 860);
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG_APP);
        setLayout(new BorderLayout());

        stack.setOpaque(false);
        stack.add(buildEmptyCard(), "empty");

        // card bọc trong padding → tool trông như card có lề (demo: padding 20/24)
        JPanel deskWrap = new JPanel(new BorderLayout());
        deskWrap.setBackground(Theme.BG_MAIN);
        deskWrap.setBorder(new EmptyBorder(18, 24, 22, 24));
        deskWrap.add(stack, BorderLayout.CENTER);

        JPanel center = new JPanel(new BorderLayout());
        center.setBackground(Theme.BG_MAIN);
        center.add(buildTopBar(), BorderLayout.NORTH);
        center.add(deskWrap, BorderLayout.CENTER);

        add(buildSidebar(), BorderLayout.WEST);
        add(center, BorderLayout.CENTER);
    }

    /** Mở tool mặc định (Shop) sau khi cửa sổ hiện — như demo. */
    public void openDefaultTool() {
        openShopEditor();
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
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.BG_PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                new EmptyBorder(0, 24, 0, 20)));
        bar.setPreferredSize(new Dimension(0, 52));

        topTitle = new JLabel("Ước Rồng Tools");
        topTitle.setForeground(Theme.TEXT);
        topTitle.setFont(Theme.font(15, Font.BOLD));
        bar.add(topTitle, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        right.setOpaque(false);
        right.add(topGhost("⤢  Phóng to", Theme.BLUE, this::zoomActive));
        right.add(topGhost("–  Thu nhỏ", Theme.ACCENT, this::restoreActive));
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JButton topGhost(String text, Color hover, Runnable onClick) {
        JButton b = flatBtn(text, Theme.BG_SURFACE2, Theme.TEXT_2, Theme.BORDER, hover, 8, false);
        b.setFont(Theme.font(12, Font.BOLD));
        b.setPreferredSize(new Dimension(104, 32));
        b.addActionListener(e -> onClick.run());
        return b;
    }

    // ═══════════════ SIDEBAR ═══════════════
    private JComponent buildSidebar() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);

        // Brand
        JLabel brand = new JLabel(spacedUpper("APEX GAMES"));
        brand.setForeground(Theme.ACCENT);
        brand.setFont(Theme.font(10, Font.BOLD));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(brand);
        content.add(Box.createVerticalStrut(3));
        JLabel t1 = new JLabel("ƯỚC RỒNG");
        t1.setForeground(Theme.TEXT);
        t1.setFont(Theme.font(18, Font.BOLD));
        t1.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(t1);
        JLabel t2 = new JLabel("TOOLS");
        t2.setForeground(Theme.ACCENT);
        t2.setFont(Theme.font(18, Font.BOLD));
        t2.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(t2);
        content.add(Box.createVerticalStrut(8));
        JPanel underline = new JPanel();
        underline.setBackground(Theme.ACCENT);
        underline.setMaximumSize(new Dimension(44, 3));
        underline.setPreferredSize(new Dimension(44, 3));
        underline.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(underline);
        content.add(Box.createVerticalStrut(16));

        // Nút hệ thống
        content.add(sysBtn("⛶", "Full Screen", Theme.ACCENT, false, this::toggleFullscreen));
        content.add(Box.createVerticalStrut(6));
        content.add(sysBtn("↻", "Restart App", Theme.GREEN, false, this::restartApp));
        content.add(Box.createVerticalStrut(6));
        content.add(sysBtn("✕", "Close Tool", Theme.RED, true, this::closeActiveTool));
        content.add(Box.createVerticalStrut(6));
        content.add(sysBtn("⏻", "Exit App", Theme.RED, true, () -> System.exit(0)));
        content.add(Box.createVerticalStrut(4));

        // Nav sections
        content.add(Theme.sectionHeader("DATABASE"));
        content.add(navItem("mysql", "⚙", "MySQL Config", this::openMysqlConfig));
        content.add(Theme.sectionHeader("EDITORS"));
        content.add(navItem("map", "🗺", "Map Editor", this::openMapEditor));
        content.add(navItem("shop", "🛒", "Shop", this::openShopEditor));
        content.add(navItem("equip", "⚔", "Chỉ số trang bị", this::openEquipEditor));
        content.add(navItem("gift", "🎁", "Giftcode", this::openGiftcodeEditor));

        // Footer DB status
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        footer.setOpaque(false);
        JLabel dot = new JLabel("●");
        dot.setForeground(Theme.GREEN);
        dot.setFont(Theme.font(9, Font.PLAIN));
        JLabel ver = new JLabel("v0.1.0 · " + dbHost());
        ver.setForeground(Theme.TEXT_DIM);
        ver.setFont(Theme.font(11, Font.PLAIN));
        footer.add(dot);
        footer.add(ver);

        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.BG_PANEL);
        side.setPreferredSize(new Dimension(228, 0));
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.DIVIDER),
                new EmptyBorder(16, 12, 14, 12)));
        side.add(content, BorderLayout.NORTH);
        side.add(footer, BorderLayout.SOUTH);
        return side;
    }

    /** Nút hệ thống (icon + chữ, căn trái). danger=true → red tint; else ghost hover đổi màu accent. */
    private JComponent sysBtn(String icon, String text, Color hover, boolean danger, Runnable onClick) {
        JButton b = flatBtn("   " + icon + "    " + text,
                danger ? Theme.alpha(Theme.RED, 22) : Theme.BG_SURFACE2,
                danger ? Theme.RED : Theme.TEXT_2,
                danger ? Theme.alpha(Theme.RED, 90) : Theme.BORDER,
                hover, 9, danger);
        b.setFont(Theme.font(13, Font.BOLD));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        b.setPreferredSize(new Dimension(10, 36));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** Item nav: icon + chữ; active → nền accent-nav + viền trái accent + chữ accent. */
    private JComponent navItem(String key, String icon, String text, Runnable onClick) {
        JButton b = new JButton("   " + icon + "    " + text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean active = key.equals(activeNav);
                if (active) {
                    g2.setColor(Theme.ACCENT_NAV);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g2.setColor(Theme.ACCENT);
                    g2.fillRoundRect(0, 4, 3, getHeight() - 8, 3, 3);
                } else if (getModel().isRollover()) {
                    g2.setColor(Theme.BG_SURFACE2);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }
                g2.dispose();
                setForeground(active ? Theme.ACCENT_HOVER : Theme.TEXT_2);
                super.paintComponent(g);
            }
        };
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setForeground(Theme.TEXT_2);
        b.setFont(Theme.font(13, Font.PLAIN));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        b.setPreferredSize(new Dimension(10, 34));
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
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    private static String spacedUpper(String s) {
        s = s.toUpperCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { sb.append(s.charAt(i)); if (i < s.length() - 1) sb.append(' '); }
        return sb.toString();
    }

    private String dbHost() {
        return cfg.dbUrl().replace("jdbc:mysql://", "").replaceAll("[/?].*", "");
    }

    // ═══════════════ QUẢN LÝ CARD TOOL ═══════════════
    /** Bọc content tool trong card có viền mảnh (inner window như demo). */
    private static JComponent wrapCard(JComponent content) {
        JPanel card = new JPanel(new BorderLayout());
        card.setBackground(Theme.BG_SURFACE);
        card.setBorder(BorderFactory.createLineBorder(Theme.BORDER_SOFT));
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    /** Hiện card tool đã có (hoặc vừa thêm) + cập nhật nav/tiêu đề. */
    private void showTool(String key, String title) {
        cards.show(stack, key);
        currentTool = key;
        setActiveNav(key);
        if (topTitle != null) topTitle.setText(title);
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
        cards.show(stack, "empty");
        stack.revalidate();
        stack.repaint();
    }

    // ═══════════════ ACTIONS ═══════════════
    private void restartApp() {
        int ok = JOptionPane.showConfirmDialog(this,
                "Khởi động lại giao diện tool?\n(kết nối DB giữ nguyên, các tool đang mở sẽ đóng)",
                "Restart App", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
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

        JButton btnTest = Theme.tint("🔌 Test kết nối", Theme.BLUE, 32, e -> {
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
                            return new Object[]{true, "✔ Kết nối OK (" + (System.currentTimeMillis() - t0) + "ms)"};
                        }
                    } catch (Exception ex) { return new Object[]{false, "✘ Fail: " + ex.getMessage()}; }
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

        Object[] opts = {"💾 Lưu (cần Restart)", "Đóng"};
        int res = JOptionPane.showOptionDialog(this, wrap, "MySQL Config",
                JOptionPane.YES_NO_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[1]);
        if (res == 0) {
            try {
                cfg.saveDb(txtUrl.getText().trim(), txtUser.getText().trim(), new String(txtPass.getPassword()));
                info("Đã lưu config.properties.\n⚠ Bấm Restart App để áp dụng kết nối mới.");
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
        if (toolCards.containsKey("map")) { showTool("map", "🗺 Map Editor"); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingUtilities.invokeLater(() -> {
            try {
                JComponent content = mapEditorFactory.get();
                if (content == null) return;
                JComponent card = wrapCard(content);
                toolCards.put("map", card);
                stack.add(card, "map");
                showTool("map", "🗺 Map Editor");
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
        });
    }

    private void openShopEditor() {
        if (toolCards.containsKey("shop")) { showTool("shop", "🛒 Quản lý Shop"); return; }
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
            var attrs = new com.apex.maptool.db.AttrNames(cfg.serverRepo());
            ShopEditorFrame se = new ShopEditorFrame(new ShopDao(db), npcs, npcIcon, equipDao, attrs); // JFrame ẩn — mượn content
            JComponent card = wrapCard((JComponent) se.getContentPane());
            toolCards.put("shop", card);
            stack.add(card, "shop");
            showTool("shop", "🛒 Quản lý Shop");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openGiftcodeEditor() {
        if (toolCards.containsKey("gift")) { showTool("gift", "🎁 Giftcode"); return; }
        try {
            var dao = new com.apex.maptool.db.GiftcodeDao(new Db(cfg), cfg.gatewayUrl());
            GiftcodeEditorFrame gf = new GiftcodeEditorFrame(dao);   // JFrame ẩn — mượn content
            JComponent card = wrapCard((JComponent) gf.getContentPane());
            toolCards.put("gift", card);
            stack.add(card, "gift");
            showTool("gift", "🎁 Giftcode");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openEquipEditor() {
        if (toolCards.containsKey("equip")) { showTool("equip", "⚔ Chỉ số trang bị"); return; }
        try {
            var dao = new com.apex.maptool.db.EquipDao(new Db(cfg));
            var attrs = new com.apex.maptool.db.AttrNames(cfg.serverRepo());
            EquipEditorFrame ee = new EquipEditorFrame(dao, attrs);   // JFrame ẩn — mượn content
            JComponent card = wrapCard((JComponent) ee.getContentPane());
            toolCards.put("equip", card);
            stack.add(card, "equip");
            showTool("equip", "⚔ Chỉ số trang bị");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }
}
