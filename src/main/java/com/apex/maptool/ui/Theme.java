package com.apex.maptool.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * Theme dark + amber (handoff "UocRong Tools v2"): nền gần đen #0d0d10, accent vàng hổ phách #f0b429.
 * Mọi token màu/chữ tập trung ở đây; nút màu dựng bằng factory (primary/tint/ghost) vẽ tay.
 */
public final class Theme {

    // ── Design tokens (màu) ──
    public static final Color BG_APP      = new Color(0x06, 0x06, 0x08);
    public static final Color BG_MAIN     = new Color(0x0d, 0x0d, 0x10);
    public static final Color BG_PANEL    = new Color(0x0a, 0x0a, 0x0d);
    public static final Color BG_SURFACE  = new Color(0x12, 0x12, 0x16);
    public static final Color BG_SURFACE2 = new Color(0x17, 0x17, 0x1c);
    public static final Color BG_INPUT    = new Color(0x1a, 0x1a, 0x20);
    public static final Color BG_ZEBRA    = new Color(0x15, 0x15, 0x1a);
    public static final Color BG_HOVER    = new Color(0x1c, 0x1c, 0x23);
    public static final Color BORDER      = new Color(0x2a, 0x2a, 0x33);
    public static final Color BORDER_SOFT = new Color(0x26, 0x26, 0x2e);
    public static final Color DIVIDER     = new Color(0x1c, 0x1c, 0x22);
    public static final Color TEXT        = new Color(0xf0, 0xf0, 0xf4);
    public static final Color TEXT_2      = new Color(0xc6, 0xc6, 0xd0);
    public static final Color TEXT_MUTED  = new Color(0x8b, 0x8b, 0x98);
    public static final Color TEXT_DIM    = new Color(0x5c, 0x5c, 0x68);
    public static final Color ACCENT      = new Color(0xf0, 0xb4, 0x29);
    public static final Color ACCENT_HOVER= new Color(0xff, 0xc9, 0x4d);
    public static final Color ACCENT_SEL  = new Color(0x2f, 0x27, 0x18);  // dòng chọn (accent .13 trên nền tối)
    public static final Color ACCENT_NAV  = new Color(0x22, 0x1e, 0x11);  // nav active (accent .12)
    public static final Color ON_ACCENT   = new Color(0x15, 0x10, 0x04);
    public static final Color GREEN       = new Color(0x34, 0xd3, 0x99);
    public static final Color RED         = new Color(0xf8, 0x71, 0x71);
    public static final Color BLUE        = new Color(0x60, 0xa5, 0xfa);
    public static final Color PURPLE      = new Color(0xa7, 0x8b, 0xfa);
    public static final Color PRICE_GEM   = new Color(0x7d, 0xd3, 0xfc);
    public static final Color PRICE_GOLD  = ACCENT;

    private static String FONT = "Segoe UI";   // đổi thành Be Vietnam Pro nếu load được từ resources

    private Theme() {}

    public static Font font(int size, int style) { return new Font(FONT, style, size); }

    /** Load font Be Vietnam Pro bundle trong jar (handoff yêu cầu — hỗ trợ tiếng Việt đẹp). */
    private static void loadFonts() {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            boolean any = false;
            for (String w : new String[]{"Regular", "Medium", "SemiBold", "Bold", "ExtraBold"}) {
                try (java.io.InputStream in = Theme.class.getResourceAsStream("/fonts/BeVietnamPro-" + w + ".ttf")) {
                    if (in != null) { ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in)); any = true; }
                }
            }
            if (any) {
                FONT = "Be Vietnam Pro";
                System.out.println("[Theme] font Be Vietnam Pro registered");
            }
        } catch (Exception e) {
            System.err.println("[Theme] load font fail (dùng Segoe UI): " + e.getMessage());
        }
    }

    /** Gọi 1 lần trước khi dựng UI. */
    public static void apply() {
        try {
            loadFonts();
            FlatDarkLaf.setup();
            UIManager.put("@accentColor", "#f0b429");
            UIManager.put("defaultFont", font(13, Font.PLAIN));

            UIManager.put("Panel.background", BG_SURFACE);   // panel mặc định = nền card (inner window)
            UIManager.put("Panel.foreground", TEXT);
            UIManager.put("OptionPane.background", BG_SURFACE);
            UIManager.put("OptionPane.messageForeground", TEXT);

            // Inputs
            for (String k : new String[]{"TextField", "FormattedTextField", "PasswordField", "TextArea", "ComboBox", "Spinner"}) {
                UIManager.put(k + ".background", BG_INPUT);
                UIManager.put(k + ".foreground", TEXT);
            }
            UIManager.put("ComboBox.buttonBackground", BG_INPUT);
            UIManager.put("ComboBox.selectionBackground", ACCENT_SEL);
            UIManager.put("ComboBox.selectionForeground", ACCENT_HOVER);
            UIManager.put("Component.borderColor", BORDER);
            UIManager.put("Component.focusedBorderColor", ACCENT);
            UIManager.put("TextComponent.arc", 9);
            UIManager.put("Component.arc", 9);
            UIManager.put("Button.arc", 9);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("TextField.placeholderForeground", TEXT_DIM);

            // Table
            UIManager.put("Table.background", BG_SURFACE);
            UIManager.put("Table.foreground", TEXT);
            UIManager.put("Table.alternateRowColor", BG_ZEBRA);
            UIManager.put("Table.gridColor", DIVIDER);
            UIManager.put("Table.selectionBackground", ACCENT_SEL);
            UIManager.put("Table.selectionForeground", ACCENT_HOVER);
            UIManager.put("Table.rowHeight", 33);            // dòng thoáng vừa phải
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Table.showVerticalLines", false);
            UIManager.put("TableHeader.background", BG_SURFACE2);
            UIManager.put("TableHeader.foreground", TEXT_MUTED);
            UIManager.put("TableHeader.font", font(11, Font.BOLD));
            UIManager.put("TableHeader.separatorColor", BORDER_SOFT);
            UIManager.put("TableHeader.bottomSeparatorColor", BORDER_SOFT);
            UIManager.put("TableHeader.height", 32);

            // List
            UIManager.put("List.background", BG_SURFACE);
            UIManager.put("List.foreground", TEXT);
            UIManager.put("List.selectionBackground", ACCENT_SEL);
            UIManager.put("List.selectionForeground", ACCENT_HOVER);
            UIManager.put("List.selectionArc", 8);

            // Scroll
            UIManager.put("ScrollPane.background", BG_MAIN);
            UIManager.put("Viewport.background", BG_SURFACE);
            UIManager.put("ScrollBar.track", BG_MAIN);
            UIManager.put("ScrollBar.thumb", BORDER);
            UIManager.put("ScrollBar.width", 12);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("ScrollBar.showButtons", false);

            // Tabs
            UIManager.put("TabbedPane.background", BG_SURFACE);
            UIManager.put("TabbedPane.underlineColor", ACCENT);
            UIManager.put("TabbedPane.selectedForeground", ACCENT_HOVER);
            UIManager.put("TabbedPane.foreground", TEXT_MUTED);
            UIManager.put("TabbedPane.tabHeight", 38);
            UIManager.put("TabbedPane.tabSelectionHeight", 3);
            UIManager.put("TabbedPane.font", font(13, Font.BOLD));
            UIManager.put("TabbedPane.contentAreaColor", BG_SURFACE);

            // Menu / popup / tooltip
            UIManager.put("PopupMenu.background", BG_SURFACE2);
            UIManager.put("ToolTip.background", BG_SURFACE2);
            UIManager.put("ToolTip.foreground", TEXT);
            UIManager.put("Separator.foreground", BORDER_SOFT);
            UIManager.put("TitlePane.background", BG_PANEL);
            UIManager.put("TitlePane.foreground", TEXT_MUTED);
            UIManager.put("TitlePane.unifiedBackground", true);
        } catch (Throwable t) {
            System.err.println("[Theme] FlatLaf fail: " + t.getMessage());
        }
    }

    // ── Button factories (vẽ tay để khớp handoff) ──
    /** Nút chính vàng: chữ on-accent, bo góc, hover sáng. */
    public static JButton primary(String text, int h, ActionListener al) {
        JButton b = painted(text, ACCENT, ON_ACCENT, null, true, 10);
        b.setFont(font(13, Font.BOLD));
        sizeH(b, h);
        if (al != null) b.addActionListener(al);
        return b;
    }

    /** Nút tint (green/red/blue/purple): nền màu mờ + viền màu + chữ màu. */
    public static JButton tint(String text, Color c, int h, ActionListener al) {
        JButton b = painted(text, c, c, c, false, 8);
        b.setFont(font(13, Font.BOLD));
        sizeH(b, h);
        if (al != null) b.addActionListener(al);
        return b;
    }

    /** Nút ghost: nền surface-2 + viền, hover sáng viền. */
    public static JButton ghost(String text, int h, ActionListener al) {
        JButton b = painted(text, BG_SURFACE2, TEXT_2, BORDER, true, 8);
        b.setFont(font(13, Font.BOLD));
        sizeH(b, h);
        if (al != null) b.addActionListener(al);
        return b;
    }

    private static void sizeH(JButton b, int h) {
        b.setPreferredSize(new Dimension(Math.max(80, b.getPreferredSize().width), h));
    }

    /**
     * Nút vẽ tay. opaque=true → fill đặc (primary/ghost); false → fill màu mờ .1/.2 (tint).
     */
    private static JButton painted(String text, Color fill, Color fg, Color border, boolean opaque, int arc) {
        JButton b = new JButton(text) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean hover = getModel().isRollover();
                boolean press = getModel().isPressed();
                Color f;
                if (opaque) {
                    f = press ? fill.darker() : hover ? brighten(fill, 22) : fill;
                } else {
                    int a = press ? 64 : hover ? 51 : 26;   // ~.25/.2/.1
                    f = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), a);
                }
                g2.setColor(f);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                if (border != null) {
                    g2.setColor(opaque ? (hover ? brighten(border, 30) : border)
                            : new Color(border.getRed(), border.getGreen(), border.getBlue(), hover ? 140 : 90));
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
                g2.dispose();
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

    public static Color brighten(Color c, int d) {
        return new Color(Math.min(255, c.getRed() + d), Math.min(255, c.getGreen() + d), Math.min(255, c.getBlue() + d));
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    // ── Section header sidebar: chữ dim in hoa, letter-spacing ──
    public static JComponent sectionHeader(String title) {
        JLabel l = new JLabel(spaced(title.toUpperCase()));
        l.setForeground(TEXT_DIM);
        l.setFont(font(10, Font.BOLD));
        l.setBorder(new EmptyBorder(12, 6, 5, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static String spaced(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { sb.append(s.charAt(i)); if (i < s.length() - 1) sb.append(' '); }
        return sb.toString();
    }

    /** Khối section: header + content (dùng ở side panel Map Editor). expand=false → khóa chiều cao. */
    public static JPanel section(String title, JComponent content, boolean expand) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.add(sectionHeader(title));
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(content);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!expand) p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    // ── Legacy aliases (giữ để code cũ compile) ──
    public static final Color BTN_PRIMARY = ACCENT;
    public static final Color BTN_SUCCESS = GREEN;
    public static final Color BTN_WARN    = ACCENT;
    public static final Color BTN_PURPLE  = PURPLE;
    public static final Color BTN_DANGER  = RED;
    public static final Color TXT_DIM     = TEXT_MUTED;

    public static JButton btn(String text, Color bg, ActionListener al) {
        JButton b = painted(text, bg, ON_ACCENT, null, true, 9);
        b.setFont(font(14, Font.BOLD));
        b.setPreferredSize(new Dimension(b.getPreferredSize().width, 34));
        if (al != null) b.addActionListener(al);
        return b;
    }

    public static JCheckBox check(String text, boolean sel, java.util.function.Consumer<Boolean> setter) {
        JCheckBox cb = new JCheckBox(text, sel);
        cb.setFocusable(false);
        cb.setOpaque(false);
        cb.setForeground(TEXT_2);
        cb.addActionListener(e -> setter.accept(cb.isSelected()));
        return cb;
    }

    public static JComponent legendRow(Color c, String t) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        r.setOpaque(false);
        JPanel sw = new JPanel();
        sw.setBackground(c);
        sw.setPreferredSize(new Dimension(13, 13));
        sw.setBorder(BorderFactory.createLineBorder(BORDER));
        r.add(sw);
        JLabel l = new JLabel(t);
        l.setForeground(TEXT_MUTED);
        l.setFont(font(12, Font.PLAIN));
        r.add(l);
        return r;
    }
}
