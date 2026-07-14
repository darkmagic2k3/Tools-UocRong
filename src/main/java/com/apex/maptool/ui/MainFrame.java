package com.apex.maptool.ui;

import com.apex.maptool.config.ToolConfig;
import com.apex.maptool.db.Db;
import com.apex.maptool.db.ShopDao;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Function;

/**
 * Vỏ app kiểu NRS Tools: sidebar trái (chọn tool) + JDesktopPane MDI.
 * Tool mở thành JInternalFrame bên trong desktop.
 */
public final class MainFrame extends JFrame {

    private static final Color SIDEBAR_BG = new Color(24, 22, 38);
    private static final Color SIDE_TXT = new Color(205, 205, 220);

    private final ToolConfig cfg;
    private final JDesktopPane desktop;
    private final Function<JDesktopPane, JInternalFrame> mapEditorFactory;
    private JInternalFrame mapInf, shopInf, equipInf;

    public MainFrame(ToolConfig cfg, Function<JDesktopPane, JInternalFrame> mapEditorFactory) {
        super("UocRongOnline Tools");
        this.cfg = cfg;
        this.mapEditorFactory = mapEditorFactory;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1380, 840);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        desktop = new JDesktopPane() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, new Color(30, 27, 48), 0, getHeight(), new Color(18, 16, 30)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                // watermark giữa desktop khi chưa mở tool
                if (getAllFrames().length == 0) {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    String s = "UocRongOnline Tools";
                    g2.setFont(getFont().deriveFont(Font.BOLD, 34f));
                    int w = g2.getFontMetrics().stringWidth(s);
                    g2.setColor(new Color(255, 255, 255, 18));
                    g2.drawString(s, (getWidth() - w) / 2, getHeight() / 2);
                    String s2 = "Chọn tool ở thanh bên trái";
                    g2.setFont(getFont().deriveFont(13f));
                    int w2 = g2.getFontMetrics().stringWidth(s2);
                    g2.setColor(new Color(255, 255, 255, 30));
                    g2.drawString(s2, (getWidth() - w2) / 2, getHeight() / 2 + 28);
                }
            }
        };
        add(buildSidebar(), BorderLayout.WEST);
        add(desktop, BorderLayout.CENTER);
    }

    // ─── sidebar ───────────────────────────────────────────────
    private JComponent buildSidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(SIDEBAR_BG);
        side.setPreferredSize(new Dimension(212, 0));
        side.setBorder(new EmptyBorder(18, 10, 12, 10));

        JLabel brand = new JLabel("APEX GAMES");
        brand.setForeground(new Color(130, 130, 150));
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 10f));
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.setBorder(new EmptyBorder(0, 8, 0, 0));
        JLabel title = new JLabel("UR TOOLS");
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setBorder(new EmptyBorder(0, 8, 0, 0));
        JPanel accent = new JPanel();
        accent.setBackground(Theme.ACCENT);
        accent.setMaximumSize(new Dimension(46, 3));
        accent.setAlignmentX(Component.LEFT_ALIGNMENT);

        side.add(brand);
        side.add(title);
        side.add(Box.createVerticalStrut(6));
        JPanel accentWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        accentWrap.setOpaque(false);
        accentWrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        accentWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
        accentWrap.add(accent);
        side.add(accentWrap);
        side.add(Box.createVerticalStrut(22));

        side.add(group("EDITORS"));
        side.add(sideBtn("🗺", "Map Editor", this::openMapEditor));
        side.add(sideBtn("🛒", "Shop Editor", this::openShopEditor));
        side.add(sideBtn("⚔", "Chỉ số trang bị", this::openEquipEditor));
        side.add(Box.createVerticalStrut(16));
        side.add(group("CỬA SỔ"));
        side.add(sideBtn("🗗", "Xếp gọn cửa sổ", this::tileWindows));
        side.add(Box.createVerticalGlue());
        side.add(sideBtn("⛶", "Toàn màn hình", this::toggleFullscreen));
        side.add(sideBtn("✖", "Thoát", () -> System.exit(0)));

        JLabel ver = new JLabel("v0.1.0 · " + cfg.dbUrl().replace("jdbc:mysql://", "").replaceAll("[/?].*", ""));
        ver.setForeground(new Color(105, 105, 125));
        ver.setFont(ver.getFont().deriveFont(10f));
        ver.setAlignmentX(Component.LEFT_ALIGNMENT);
        ver.setBorder(new EmptyBorder(10, 8, 0, 0));
        side.add(ver);
        return side;
    }

    private JComponent group(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(new Color(120, 120, 145));
        l.setFont(l.getFont().deriveFont(Font.BOLD, 10f));
        l.setBorder(new EmptyBorder(4, 8, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Nút sidebar phẳng: hover sáng bo góc (kiểu NRS), icon + chữ, full-width. */
    private JComponent sideBtn(String icon, String text, Runnable onClick) {
        JButton b = new JButton("  " + icon + "   " + text) {
            @Override protected void paintComponent(Graphics g) {
                if (getModel().isRollover() || getModel().isPressed()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(255, 255, 255, getModel().isPressed() ? 38 : 24));
                    g2.fillRoundRect(2, 2, getWidth() - 4, getHeight() - 4, 10, 10);
                    g2.dispose();
                }
                super.paintComponent(g);
            }
        };
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.setForeground(SIDE_TXT);
        b.setFont(b.getFont().deriveFont(13.5f));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        b.addActionListener(e -> onClick.run());
        return b;
    }

    // ─── mở tool ───────────────────────────────────────────────
    private void openMapEditor() {
        if (mapInf != null && !mapInf.isClosed()) { focus(mapInf); return; }
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingUtilities.invokeLater(() -> {
            try {
                mapInf = mapEditorFactory.apply(desktop);
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
        });
    }

    private void openShopEditor() {
        if (shopInf != null && !shopInf.isClosed()) { focus(shopInf); return; }
        try {
            Db db = new Db(cfg);
            // NPC list + icon cho picker "NPC mở"
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
            ShopEditorFrame se = new ShopEditorFrame(new ShopDao(db), npcs, npcIcon); // JFrame ẩn — mượn content
            JInternalFrame inf = new JInternalFrame("Shop Editor", true, true, true, true);
            inf.setFrameIcon(null);
            inf.setContentPane(se.getContentPane());
            int w = Math.min(1240, desktop.getWidth() - 30), h = Math.min(700, desktop.getHeight() - 30);
            inf.setSize(w, h);
            desktop.add(inf);
            inf.setLocation(Math.max(0, (desktop.getWidth() - w) / 2), Math.max(0, (desktop.getHeight() - h) / 2));
            inf.setVisible(true);
            focus(inf);
            shopInf = inf;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openEquipEditor() {
        if (equipInf != null && !equipInf.isClosed()) { focus(equipInf); return; }
        try {
            var dao = new com.apex.maptool.db.EquipDao(new Db(cfg));
            var attrs = new com.apex.maptool.db.AttrNames(cfg.serverRepo());
            EquipEditorFrame ee = new EquipEditorFrame(dao, attrs);   // JFrame ẩn — mượn content
            JInternalFrame inf = new JInternalFrame("Chỉ số trang bị", true, true, true, true);
            inf.setFrameIcon(null);
            inf.setContentPane(ee.getContentPane());
            int w = Math.min(1240, desktop.getWidth() - 30), h = Math.min(700, desktop.getHeight() - 30);
            inf.setSize(w, h);
            desktop.add(inf);
            inf.setLocation(Math.max(0, (desktop.getWidth() - w) / 2), Math.max(0, (desktop.getHeight() - h) / 2));
            inf.setVisible(true);
            focus(inf);
            equipInf = inf;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Kết nối DB fail:\n" + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void focus(JInternalFrame inf) {
        try { inf.setIcon(false); inf.setSelected(true); inf.toFront(); } catch (Exception ignored) {}
    }

    /** Xếp các cửa sổ con cạnh nhau. */
    private void tileWindows() {
        JInternalFrame[] frames = desktop.getAllFrames();
        if (frames.length == 0) return;
        int cols = (int) Math.ceil(Math.sqrt(frames.length));
        int rows = (int) Math.ceil(frames.length / (double) cols);
        int w = desktop.getWidth() / cols, h = desktop.getHeight() / rows;
        for (int i = 0; i < frames.length; i++) {
            try { frames[i].setMaximum(false); frames[i].setIcon(false); } catch (Exception ignored) {}
            frames[i].setBounds((i % cols) * w, (i / cols) * h, w, h);
        }
    }

    private void toggleFullscreen() {
        boolean max = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        setExtendedState(max ? NORMAL : MAXIMIZED_BOTH);
    }
}
