package com.apex.maptool.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Theme dark + amber (handoff "UocRong Tools v2" + bản bố cục lại "UI Redesign"):
 * nền gần đen #0d0d10, accent vàng hổ phách #f0b429.
 *
 * <p>Mọi token màu/chữ tập trung ở đây; nút màu dựng bằng factory (primary/tint/ghost) vẽ tay.
 * Bản bố cục lại thêm bộ thành phần dùng chung mà bản thiết kế yêu cầu:
 * {@link Chip} · {@link #card} · {@link Segmented} · {@link MenuButton} · {@link Toggle} ·
 * {@link #stepCard} · {@link #icon} (glyph vẽ vector — font bundle KHÔNG có ▾ ✓ ⤢ …).
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
    private static String MONO = "Consolas";   // cột số / đường dẫn / id — để chữ số thẳng hàng

    private Theme() {}

    public static Font font(int size, int style) { return new Font(FONT, style, size); }

    /** Font monospace (giá trị số, đường dẫn, id) — handoff yêu cầu cột số thẳng hàng. */
    public static Font mono(int size, int style) { return new Font(MONO, style, size); }

    /** Font có letter-spacing thật (section header in hoa) — {@code tracking} theo em, vd .18. */
    public static Font tracked(int size, int style, double tracking) {
        Map<TextAttribute, Object> a = new HashMap<>();
        a.put(TextAttribute.TRACKING, tracking);
        return font(size, style).deriveFont(a);
    }

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
            // Consolas có sẵn trên Windows; máy khác thì rơi về logical Monospaced.
            boolean hasMono = false;
            for (String n : ge.getAvailableFontFamilyNames()) if (MONO.equalsIgnoreCase(n)) { hasMono = true; break; }
            if (!hasMono) MONO = Font.MONOSPACED;
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
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("TextField.placeholderForeground", TEXT_DIM);

            // Checkbox / radio: ô vuông bo 4, tick trên nền accent — đúng bản thiết kế
            UIManager.put("CheckBox.icon.style", "filled");
            UIManager.put("CheckBox.icon.borderColor", BORDER);
            UIManager.put("CheckBox.icon.background", BG_INPUT);
            UIManager.put("CheckBox.icon[filled].borderColor", BORDER);
            UIManager.put("CheckBox.icon[filled].background", BG_INPUT);
            for (String k : new String[]{"CheckBox.icon.selectedBorderColor", "CheckBox.icon.checkedBorderColor",
                    "CheckBox.icon[filled].selectedBorderColor", "CheckBox.icon[filled].checkedBorderColor"}) {
                UIManager.put(k, ACCENT);
            }
            for (String k : new String[]{"CheckBox.icon.selectedBackground", "CheckBox.icon.checkedBackground",
                    "CheckBox.icon[filled].selectedBackground", "CheckBox.icon[filled].checkedBackground"}) {
                UIManager.put(k, ACCENT);
            }
            for (String k : new String[]{"CheckBox.icon.checkmarkColor", "CheckBox.icon[filled].checkmarkColor"}) {
                UIManager.put(k, ON_ACCENT);
            }
            UIManager.put("CheckBox.arc", 5);
            UIManager.put("CheckBox.foreground", TEXT_2);
            UIManager.put("CheckBox.background", BG_SURFACE);
            UIManager.put("RadioButton.foreground", TEXT_2);

            // Slider: rãnh 4px BORDER · phần đã kéo ACCENT · núm 14px viền nền
            UIManager.put("Slider.trackWidth", 4);
            UIManager.put("Slider.thumbSize", new Dimension(14, 14));
            UIManager.put("Slider.trackColor", BORDER);
            UIManager.put("Slider.trackValueColor", ACCENT);
            UIManager.put("Slider.thumbColor", ACCENT);
            UIManager.put("Slider.thumbBorderColor", BG_MAIN);
            UIManager.put("Slider.thumbBorderWidth", 2);
            UIManager.put("Slider.hoverThumbColor", ACCENT_HOVER);
            UIManager.put("Slider.pressedThumbColor", ACCENT_HOVER);
            UIManager.put("Slider.focusedColor", alpha(ACCENT, 60));
            UIManager.put("Slider.disabledTrackColor", BORDER_SOFT);
            UIManager.put("Slider.disabledThumbColor", TEXT_DIM);
            UIManager.put("Slider.background", BG_SURFACE);

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
            UIManager.put("TableHeader.font", font(12, Font.BOLD));
            UIManager.put("TableHeader.separatorColor", BORDER_SOFT);
            UIManager.put("TableHeader.bottomSeparatorColor", BORDER_SOFT);
            UIManager.put("TableHeader.height", 32);

            // List
            UIManager.put("List.background", BG_SURFACE);
            UIManager.put("List.foreground", TEXT);
            UIManager.put("List.selectionBackground", ACCENT_SEL);
            UIManager.put("List.selectionForeground", ACCENT_HOVER);
            UIManager.put("List.selectionArc", 8);

            // Tree
            UIManager.put("Tree.background", BG_SURFACE);
            UIManager.put("Tree.foreground", TEXT_2);
            UIManager.put("Tree.selectionBackground", ACCENT_SEL);
            UIManager.put("Tree.selectionForeground", ACCENT_HOVER);
            UIManager.put("Tree.selectionInactiveBackground", ACCENT_SEL);
            UIManager.put("Tree.selectionInactiveForeground", ACCENT_HOVER);
            UIManager.put("Tree.selectionArc", 7);
            UIManager.put("Tree.paintLines", false);
            UIManager.put("Tree.icon.expandedColor", TEXT_DIM);
            UIManager.put("Tree.icon.collapsedColor", TEXT_DIM);

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
            UIManager.put("TabbedPane.tabHeight", 46);
            UIManager.put("TabbedPane.tabSelectionHeight", 3);
            UIManager.put("TabbedPane.font", font(14, Font.BOLD));
            UIManager.put("TabbedPane.contentAreaColor", BG_SURFACE);
            UIManager.put("TabbedPane.contentSeparatorHeight", 1);
            UIManager.put("TabbedPane.showTabSeparators", false);
            UIManager.put("TabbedPane.tabSeparatorsFullHeight", false);
            UIManager.put("TabbedPane.tabInsets", new Insets(0, 14, 0, 14));
            UIManager.put("TabbedPane.tabAreaInsets", new Insets(0, 6, 0, 6));
            UIManager.put("TabbedPane.hoverColor", BG_SURFACE2);
            UIManager.put("TabbedPane.focusColor", BG_SURFACE2);

            // Menu / popup / tooltip
            UIManager.put("PopupMenu.background", BG_SURFACE2);
            UIManager.put("PopupMenu.borderColor", BORDER);
            UIManager.put("MenuItem.background", BG_SURFACE2);
            UIManager.put("MenuItem.foreground", TEXT);
            UIManager.put("MenuItem.selectionBackground", ACCENT_SEL);
            UIManager.put("MenuItem.selectionForeground", ACCENT_HOVER);
            UIManager.put("MenuItem.selectionArc", 7);
            UIManager.put("MenuItem.margin", new Insets(6, 8, 6, 8));
            UIManager.put("CheckBoxMenuItem.background", BG_SURFACE2);
            UIManager.put("CheckBoxMenuItem.foreground", TEXT);
            UIManager.put("CheckBoxMenuItem.selectionBackground", ACCENT_SEL);
            UIManager.put("CheckBoxMenuItem.selectionForeground", ACCENT_HOVER);
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

    // ══════════════════════════════════════════════════════════════════
    // Nút
    // ══════════════════════════════════════════════════════════════════

    /** Nút chính vàng: chữ on-accent, bo góc, hover sáng. */
    public static JButton primary(String text, int h, ActionListener al) {
        JButton b = painted(text, ACCENT, ON_ACCENT, null, true, 8);
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

    /** Nút vuông chỉ có icon vector (nút cửa sổ, ↑ ↓, ⋯). */
    public static JButton iconButton(Icon ic, int w, int h, String tip, ActionListener al) {
        JButton b = painted("", BG_SURFACE2, TEXT_MUTED, BORDER, true, 8);
        b.setIcon(ic);
        lockSize(b, w, h);
        if (tip != null) b.setToolTipText(tip);
        if (al != null) b.addActionListener(al);
        return b;
    }

    /** Nút vuông tint màu chỉ có icon (⟲ đỏ = hoàn tác tất cả). */
    public static JButton iconTint(Icon ic, Color c, int w, int h, String tip, ActionListener al) {
        JButton b = painted("", c, c, c, false, 8);
        b.setIcon(ic);
        lockSize(b, w, h);
        if (tip != null) b.setToolTipText(tip);
        if (al != null) b.addActionListener(al);
        return b;
    }

    /**
     * Khoá kích thước nút theo bề rộng chữ THẬT — handoff §8: nút bị co vài px là nhãn tự ngắt
     * 2 dòng. Dùng cho mọi nút nằm trong BoxLayout/GridBag của thanh công cụ.
     */
    public static <T extends JComponent> T lock(T c) {
        Dimension d = c.getPreferredSize();
        c.setMinimumSize(d);
        c.setMaximumSize(d);
        return c;
    }

    public static <T extends JComponent> T lockSize(T c, int w, int h) {
        Dimension d = new Dimension(w, h);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
        return c;
    }

    /**
     * Ép chiều cao, bề rộng ôm ĐÚNG chữ hiện tại. Bỏ preferredSize cũ trước khi đo — nút đã qua
     * {@link #primary}/{@link #ghost} có preferredSize chốt từ lúc chưa gắn icon/đổi nhãn, giữ lại
     * là nhãn bị cắt thành "Ch…".
     */
    public static <T extends JComponent> T lockH(T c, int h) {
        c.setPreferredSize(null);
        c.setMinimumSize(null);
        c.setMaximumSize(null);
        Dimension d = new Dimension(c.getPreferredSize().width, h);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
        return c;
    }

    private static void sizeH(JButton b, int h) {
        b.setPreferredSize(new Dimension(Math.max(80, b.getPreferredSize().width), h));
    }

    /**
     * Nút vẽ tay. opaque=true → fill đặc (primary/ghost); false → fill màu mờ .1/.2 (tint).
     */
    private static JButton painted(String text, Color fillIn, Color fg, Color border, boolean opaque, int arc) {
        // fill = null nghĩa là "nút thường, không tô màu" → dùng nền surface-2 như ghost().
        // Trước đây để null đi thẳng vào fill.darker()/brighten() ⇒ NPE ngay trong paintComponent,
        // EDT ném exception liên tục và nút không vẽ ra gì (7 nút của Map Editor đang dính).
        final Color fill = (fillIn != null) ? fillIn : BG_SURFACE2;
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

    // ══════════════════════════════════════════════════════════════════
    // Spinner
    // ══════════════════════════════════════════════════════════════════

    /**
     * Spinner số. Mọi nút của tool đều {@code setFocusable(false)} → bấm nút KHÔNG lấy focus
     * khỏi ô spinner, nên số vừa gõ tay chưa bao giờ được commit và {@code getValue()} vẫn trả
     * giá trị cũ (báo "sửa thành công" nhưng field không đổi). Ép commit ngay mỗi ký tự hợp lệ.
     */
    public static JSpinner spin(int value, int min, int max) {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(value, min, max, 1));
        if (sp.getEditor() instanceof JSpinner.DefaultEditor ed
                && ed.getTextField().getFormatter() instanceof javax.swing.text.DefaultFormatter df)
            df.setCommitsOnValidEdit(true);
        return sp;
    }

    /** Đọc số của spinner, commit nốt text đang gõ dở; text hỏng → trả về giá trị cũ và reset ô. */
    public static int spinInt(JSpinner sp) {
        try {
            sp.commitEdit();
        } catch (java.text.ParseException e) {
            if (sp.getEditor() instanceof JSpinner.DefaultEditor ed) ed.getTextField().setValue(sp.getValue());
        }
        return ((Number) sp.getValue()).intValue();
    }

    /** Spinner canh phải + font monospace (cột số của bản thiết kế). */
    public static JSpinner monoSpin(JSpinner sp, int w, int h) {
        if (sp.getEditor() instanceof JSpinner.DefaultEditor ed) {
            ed.getTextField().setFont(mono(13, Font.PLAIN));
            ed.getTextField().setHorizontalAlignment(SwingConstants.RIGHT);
        }
        return lockSize(sp, w, h);
    }

    public static Color brighten(Color c, int d) {
        return new Color(Math.min(255, c.getRed() + d), Math.min(255, c.getGreen() + d), Math.min(255, c.getBlue() + d));
    }

    public static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    // ══════════════════════════════════════════════════════════════════
    // Nhãn / header / khối
    // ══════════════════════════════════════════════════════════════════

    /** Section header: chữ in hoa 11 BOLD, letter-spacing thật. */
    public static JComponent sectionHeader(String title) { return sectionHeader(title, TEXT_DIM); }

    public static JLabel sectionHeader(String title, Color fg) {
        JLabel l = new JLabel(title.toUpperCase());
        l.setForeground(fg);
        l.setFont(tracked(11, Font.BOLD, 0.16));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Nhãn thường theo token (size + màu), không bao giờ xuống dòng. */
    public static JLabel label(String text, int size, int style, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(font(size, style));
        l.setForeground(fg);
        return l;
    }

    public static JLabel monoLabel(String text, int size, Color fg) {
        JLabel l = new JLabel(text);
        l.setFont(mono(size, Font.PLAIN));
        l.setForeground(fg);
        return l;
    }

    /** Khối section: header + content (dùng ở side panel Map Editor). expand=false → khóa chiều cao. */
    public static JPanel section(String title, JComponent content, boolean expand) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        JComponent h = sectionHeader(title);
        h.setBorder(new EmptyBorder(12, 6, 5, 0));
        p.add(h);
        content.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(content);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!expand) p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    /**
     * Panel bo góc (card của bản thiết kế). {@code bg}/{@code border} có thể null.
     * Chiều cao KHÔNG bị khoá — bọc trong {@link #capH} nếu nằm trong BoxLayout dọc.
     */
    public static JPanel card(Color bg, Color border, int arc) {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (bg != null) {
                    g2.setColor(bg);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
                }
                if (border != null) {
                    g2.setColor(border);
                    g2.setStroke(new BasicStroke(1f));
                    g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
                }
                g2.dispose();
            }
        };
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Card mặc định của bản thiết kế: nền BG_SURFACE, viền BORDER_SOFT, bo 10. */
    public static JPanel card() { return card(BG_SURFACE, BORDER_SOFT, 10); }

    /** Card con trong panel (step card / card thuộc tính): nền BG_MAIN, viền BORDER_SOFT, bo 10. */
    public static JPanel innerCard() { return card(BG_MAIN, BORDER_SOFT, 10); }

    /** Khoá chiều cao theo nội dung (để BoxLayout dọc không kéo dãn card). */
    public static <T extends JComponent> T capH(T c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    /** Đường kẻ ngang 1px. */
    public static JComponent hr() {
        JPanel p = new JPanel();
        p.setBackground(DIVIDER);
        p.setPreferredSize(new Dimension(1, 1));
        p.setMinimumSize(new Dimension(1, 1));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Vạch dọc 1px cao {@code h} (ngăn nhóm control trong 1 hàng). */
    public static JComponent vsep(int h) {
        JPanel p = new JPanel();
        p.setBackground(BORDER);
        return lockSize(p, 1, h);
    }

    /** Hàng chip: FlowLayout trái, khoảng cách {@code gap}. */
    public static JPanel chipRow(int gap) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Hàng ngang BoxLayout (thanh công cụ) — không co, không tự xuống dòng. */
    public static JPanel row() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Cột dọc BoxLayout. */
    public static JPanel colBox() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    // ══════════════════════════════════════════════════════════════════
    // Chip
    // ══════════════════════════════════════════════════════════════════

    /** Chip bo tròn. {@code tint == null} → chip trung tính (surface-2 + viền BORDER). */
    public static final class Chip extends JLabel {
        private Color fill, line;
        private final int h;

        public Chip(String text, Color tint, int h, int fontSize, boolean monoFont) {
            super(text);
            this.h = h;
            setOpaque(false);
            setFont(monoFont ? mono(fontSize, Font.PLAIN) : font(fontSize, Font.PLAIN));
            setBorder(new EmptyBorder(0, 10, 0, 10));
            setTint(tint);
        }

        public void setTint(Color c) {
            if (c == null) { fill = BG_SURFACE2; line = BORDER; setForeground(TEXT_2); }
            else { fill = alpha(c, 26); line = alpha(c, 90); setForeground(c); }
            repaint();
        }

        /** Chip xám hẳn (trạng thái TẮT). */
        public void setOff() { fill = BG_SURFACE2; line = BORDER; setForeground(TEXT_DIM); repaint(); }

        @Override public Dimension getPreferredSize() {
            Insets in = getInsets();
            int w = getFontMetrics(getFont()).stringWidth(getText() == null ? "" : getText());
            Icon ic = getIcon();
            if (ic != null) w += ic.getIconWidth() + getIconTextGap();
            return new Dimension(w + in.left + in.right, h);
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 999, 999);
            g2.setColor(line);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 999, 999);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static Chip chip(String text) { return new Chip(text, null, 26, 12, false); }
    public static Chip chip(String text, Color tint) { return new Chip(text, tint, 26, 12, false); }
    public static Chip monoChip(String text) { return new Chip(text, null, 26, 12, true); }

    /**
     * Chip bấm được (công tắc trên khung xem). {@code on} → nền ACCENT_SEL + viền accent;
     * tắt → xám. Có thể đặt màu viền riêng ({@code onTint}) như chip lớp SAU màu tím.
     */
    public static final class ChipButton extends JButton {
        private boolean on;
        private Color onTint = ACCENT;
        private Color dot;
        private final int h;

        public ChipButton(String text, boolean on, int h, ActionListener al) {
            super(text);
            this.h = h;
            this.on = on;
            setFont(font(12, Font.BOLD));
            setBorder(new EmptyBorder(0, 12, 0, 12));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            if (al != null) addActionListener(al);
        }

        public void setOn(boolean v) { on = v; repaint(); }
        public boolean isOn() { return on; }
        public void setOnTint(Color c) { onTint = c; repaint(); }
        /** Chấm vuông 7px trước chữ (chip lớp SAU / TRƯỚC). */
        public void setDot(Color c) { dot = c; setBorder(new EmptyBorder(0, 26, 0, 12)); repaint(); }
        public void setMonoFont() { setFont(mono(12, Font.PLAIN)); }

        @Override public Dimension getPreferredSize() {
            Insets in = getInsets();
            int w = getFontMetrics(getFont()).stringWidth(getText() == null ? "" : getText());
            Icon ic = getIcon();
            if (ic != null) w += ic.getIconWidth() + getIconTextGap();
            return new Dimension(w + in.left + in.right, h);
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hover = getModel().isRollover();
            g2.setColor(on ? ACCENT_SEL : (hover ? BG_HOVER : BG_SURFACE2));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 999, 999);
            g2.setColor(on ? alpha(onTint, 115) : (hover ? brighten(BORDER, 24) : BORDER));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 999, 999);
            if (dot != null) {
                g2.setColor(on ? dot : BORDER);
                g2.fillRoundRect(12, getHeight() / 2 - 4, 7, 7, 2, 2);
            }
            g2.dispose();
            setForeground(on ? (onTint == ACCENT ? ACCENT_HOVER : TEXT_2) : TEXT_DIM);
            super.paintComponent(g);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Segmented control
    // ══════════════════════════════════════════════════════════════════

    /**
     * Bộ chọn dạng phân đoạn: khung BG_INPUT + viền BORDER (bo 8, đệm 3), mỗi đoạn bo 6.
     * {@code strong = true} → đoạn chọn nền ACCENT chữ ON_ACCENT; false → nền ACCENT_SEL chữ ACCENT_HOVER.
     */
    public static final class Segmented extends JPanel {
        private final List<Seg> segs = new ArrayList<>();
        private int sel;
        private IntConsumer onChange;
        private final boolean strong;

        public Segmented(int frameH, int segH, boolean strong, boolean equal, String... labels) {
            this.strong = strong;
            setOpaque(false);
            setLayout(equal ? new GridLayout(1, labels.length, 0, 0) : new BoxLayout(this, BoxLayout.X_AXIS));
            setBorder(new EmptyBorder(3, 3, 3, 3));
            for (int i = 0; i < labels.length; i++) {
                Seg s = new Seg(labels[i], i, segH);
                segs.add(s);
                add(s);
            }
            int w = getPreferredSize().width;
            setPreferredSize(new Dimension(w, frameH));
            setMinimumSize(new Dimension(w, frameH));
            setMaximumSize(equal ? new Dimension(Integer.MAX_VALUE, frameH) : new Dimension(w, frameH));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setAlignmentY(Component.CENTER_ALIGNMENT);
        }

        public void onChange(IntConsumer c) { onChange = c; }
        public int selected() { return sel; }

        public void select(int i) {
            if (i < 0 || i >= segs.size()) return;
            sel = i;
            repaint();
        }

        /** Đổi lựa chọn và BẮN sự kiện (dùng khi code khác cần đồng bộ). */
        public void selectAndFire(int i) {
            if (i == sel || i < 0 || i >= segs.size()) { select(i); return; }
            select(i);
            if (onChange != null) onChange.accept(i);
        }

        public void setLabel(int i, String text) {
            if (i >= 0 && i < segs.size()) { segs.get(i).setText(text); revalidate(); repaint(); }
        }

        public void setSegEnabled(int i, boolean on) {
            if (i >= 0 && i < segs.size()) segs.get(i).setEnabled(on);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(BG_INPUT);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.setColor(BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            g2.dispose();
        }

        private final class Seg extends JButton {
            private final int idx, segH;

            Seg(String text, int idx, int segH) {
                super(text);
                this.idx = idx;
                this.segH = segH;
                // Đoạn thấp (≤28) là bộ chọn nhỏ trong panel hẹp → chữ 12 + đệm 6 cho vừa,
                // không thì nhãn bị cắt thành "Trước pl…" (handoff §8).
                boolean small = segH <= 28;
                setFont(font(small ? 12 : 13, Font.BOLD));
                setBorder(new EmptyBorder(0, small ? 6 : 14, 0, small ? 6 : 14));
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setFocusable(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addActionListener(e -> {
                    if (sel == idx) return;
                    sel = idx;
                    Segmented.this.repaint();
                    if (onChange != null) onChange.accept(idx);
                });
            }

            @Override public Dimension getPreferredSize() {
                Insets in = getInsets();
                int w = getFontMetrics(getFont()).stringWidth(getText() == null ? "" : getText());
                return new Dimension(w + in.left + in.right, segH);
            }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, segH);
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean active = (idx == sel);
                if (active) {
                    g2.setColor(strong ? ACCENT : ACCENT_SEL);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                } else if (getModel().isRollover() && isEnabled()) {
                    g2.setColor(BG_SURFACE2);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                }
                g2.dispose();
                setForeground(!isEnabled() ? TEXT_DIM
                        : active ? (strong ? ON_ACCENT : ACCENT_HOVER) : TEXT_MUTED);
                super.paintComponent(g);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Nút menu bật xuống (thanh công cụ Bố cục Map)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Nút mở {@link JPopupMenu} ngay dưới nó. Mặt nút in kèm trạng thái tóm tắt ({@code 4/6})
     * để không phải mở ra mới biết; {@code active = true} → nền ACCENT_SEL + viền accent.
     */
    public static final class MenuButton extends JButton {
        private String label;
        private String badge = "";
        private boolean active, open;
        private final int h;
        private JPopupMenu menu;

        public MenuButton(String label, int h) {
            this.label = label;
            this.h = h;
            setBorder(new EmptyBorder(0, 12, 0, 12));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addActionListener(e -> {
                if (menu == null) return;
                open = true;
                repaint();
                menu.show(this, 0, getHeight() + 6);
            });
        }

        public void setMenu(JPopupMenu m) {
            menu = m;
            m.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
                @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                    open = false;
                    repaint();
                }
                @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                    open = false;
                    repaint();
                }
            });
        }

        public JPopupMenu menu() { return menu; }

        public void setBadge(String s) { badge = (s == null) ? "" : s; revalidate(); repaint(); }
        public void setLabel(String s) { label = (s == null) ? "" : s; revalidate(); repaint(); }
        public void setActiveState(boolean v) { active = v; repaint(); }

        @Override public Dimension getPreferredSize() {
            Insets in = getInsets();
            int w = getFontMetrics(font(13, Font.BOLD)).stringWidth(label);
            if (!badge.isEmpty()) w += 7 + getFontMetrics(font(12, Font.PLAIN)).stringWidth(badge);
            return new Dimension(w + in.left + in.right + 7 + 9, h);
        }
        @Override public Dimension getMinimumSize() { return getPreferredSize(); }
        @Override public Dimension getMaximumSize() { return getPreferredSize(); }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean lit = active || open;
            boolean hover = getModel().isRollover();
            g2.setColor(lit ? ACCENT_SEL : (hover ? BG_HOVER : BG_SURFACE2));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            g2.setColor(lit ? alpha(ACCENT, 115) : (hover ? brighten(BORDER, 30) : BORDER));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

            int x = getInsets().left;
            Font f1 = font(13, Font.BOLD);
            g2.setFont(f1);
            FontMetrics fm = g2.getFontMetrics();
            int by = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.setColor(lit ? ACCENT_HOVER : TEXT_2);
            g2.drawString(label, x, by);
            x += fm.stringWidth(label);
            if (!badge.isEmpty()) {
                x += 7;
                g2.setFont(font(12, Font.PLAIN));
                g2.setColor(TEXT_MUTED);
                g2.drawString(badge, x, by);
                x += g2.getFontMetrics().stringWidth(badge);
            }
            x += 7;
            paintChevron(g2, x, getHeight() / 2, 9, lit ? ACCENT_HOVER : TEXT_MUTED, false);
            g2.dispose();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Công tắc gạt (toggle)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Công tắc gạt 30×19 — vẫn là {@link JCheckBox} nên mọi listener/isSelected() cũ dùng được.
     */
    public static final class Toggle extends JCheckBox {
        public Toggle(boolean sel) {
            super("", sel);
            setOpaque(false);
            setFocusable(false);
            setBorder(null);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setIcon(new Icon() {
                @Override public int getIconWidth() { return 30; }
                @Override public int getIconHeight() { return 19; }
                @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    boolean on = ((AbstractButton) c).isSelected();
                    g2.setColor(on ? alpha(ACCENT, 40) : BG_INPUT);
                    g2.fillRoundRect(x, y, 30, 19, 999, 999);
                    g2.setColor(on ? alpha(ACCENT, 150) : BORDER);
                    g2.drawRoundRect(x, y, 29, 18, 999, 999);
                    g2.setColor(on ? ACCENT : TEXT_DIM);
                    g2.fillOval(on ? x + 14 : x + 3, y + 3, 13, 13);
                    g2.dispose();
                }
            });
            lockSize(this, 30, 19);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Step card (rail Hào Quang) — header bấm để gập/mở
    // ══════════════════════════════════════════════════════════════════

    /**
     * Thẻ bước có số: header cao 40 (badge tròn 24 · tiêu đề 14 BOLD · tóm tắt 12 · mũi ▾/▴),
     * thân gập/mở được. Bước xong → badge chuyển ✓ xanh.
     */
    public static final class StepCard extends JPanel {
        private final int num;
        private final Header header;
        private final JPanel body = new JPanel();
        private boolean open;
        private boolean done;
        private String summary = "";

        public StepCard(int num, String title, boolean open) {
            this.num = num;
            this.open = open;
            setOpaque(false);
            setLayout(new BorderLayout());
            setAlignmentX(Component.LEFT_ALIGNMENT);
            header = new Header(title);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);
            body.setBorder(new EmptyBorder(10, 12, 12, 12));
            body.setVisible(open);
            add(header, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);
            header.addActionListener(e -> setOpen(!this.open));
        }

        public JPanel body() { return body; }
        public boolean isOpen() { return open; }

        public void setOpen(boolean v) {
            open = v;
            body.setVisible(v);
            header.repaint();
            revalidate();
            Container p = getParent();
            if (p != null) { p.revalidate(); p.repaint(); }
        }

        public void setSummary(String s) { summary = (s == null) ? "" : s; header.repaint(); }
        public void setDone(boolean v) { done = v; header.repaint(); }

        @Override public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(BG_MAIN);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g2.setColor(BORDER_SOFT);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
        }

        private final class Header extends JButton {
            private final String title;

            Header(String title) {
                this.title = title;
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setFocusable(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setPreferredSize(new Dimension(10, 40));
                setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            }

            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int h = getHeight(), w = getWidth();
                if (getModel().isRollover()) {
                    g2.setColor(alpha(BG_SURFACE2, 140));
                    g2.fillRoundRect(1, 1, w - 2, h - 2, 9, 9);
                }
                if (open) {
                    g2.setColor(DIVIDER);
                    g2.drawLine(0, h - 1, w, h - 1);
                }
                // badge tròn 24
                int by = (h - 24) / 2;
                g2.setColor(done ? alpha(GREEN, 36) : (open ? ACCENT_NAV : BG_SURFACE2));
                g2.fillOval(12, by, 24, 24);
                g2.setColor(done ? alpha(GREEN, 115) : (open ? alpha(ACCENT, 115) : BORDER));
                g2.drawOval(12, by, 24, 24);
                if (done) {
                    paintCheck(g2, 24, h / 2, 11, GREEN);
                } else {
                    g2.setFont(font(12, Font.BOLD));
                    String s = String.valueOf(num);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.setColor(open ? ACCENT : TEXT_MUTED);
                    g2.drawString(s, 24 - fm.stringWidth(s) / 2f,
                            h / 2f + (fm.getAscent() - fm.getDescent()) / 2f);
                }
                // tiêu đề
                g2.setFont(font(14, Font.BOLD));
                FontMetrics fm = g2.getFontMetrics();
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.setColor(open ? TEXT : TEXT_2);
                g2.drawString(title, 46, ty);
                int titleEnd = 46 + fm.stringWidth(title);
                // tóm tắt (canh phải, trước mũi tên)
                if (!summary.isEmpty()) {
                    g2.setFont(mono(12, Font.PLAIN));
                    FontMetrics fm2 = g2.getFontMetrics();
                    int sw = fm2.stringWidth(summary);
                    int sx = w - 30 - sw;
                    if (sx > titleEnd + 10) {
                        g2.setColor(done ? GREEN : TEXT_MUTED);
                        g2.drawString(summary, sx, (h + fm2.getAscent() - fm2.getDescent()) / 2);
                    }
                }
                paintChevron(g2, w - 20, h / 2, 9, TEXT_DIM, open);
                g2.dispose();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Icon vector (font bundle KHÔNG có ▾ ✓ ⤢ ⋯ … nên vẽ tay cho chắc)
    // ══════════════════════════════════════════════════════════════════

    public static final String IC_CHEVRON_DOWN = "chevron-down";
    public static final String IC_CHEVRON_UP   = "chevron-up";
    public static final String IC_CHECK        = "check";
    public static final String IC_ARROW_UP     = "arrow-up";
    public static final String IC_ARROW_DOWN   = "arrow-down";
    public static final String IC_EXPAND       = "expand";
    public static final String IC_MINUS        = "minus";
    public static final String IC_MORE         = "more";
    public static final String IC_UNDO         = "undo";
    public static final String IC_RESET        = "reset";
    public static final String IC_PLAY         = "play";
    public static final String IC_PAUSE        = "pause";
    public static final String IC_SEARCH       = "search";
    public static final String IC_PLUS         = "plus";
    public static final String IC_REDO         = "redo";

    /** Icon vector {@code size × size} màu {@code c}. */
    public static Icon icon(String kind, int size, Color c) {
        return new Icon() {
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
            @Override public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                paintGlyph(g2, kind, size, c);
                g2.dispose();
            }
        };
    }

    private static void paintGlyph(Graphics2D g2, String kind, int s, Color c) {
        g2.setColor(c);
        float t = Math.max(1.4f, s / 8f);
        g2.setStroke(new BasicStroke(t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        float m = s / 2f;
        switch (kind) {
            case IC_CHEVRON_DOWN -> chevronPath(g2, m, m, s * 0.28f, false);
            case IC_CHEVRON_UP -> chevronPath(g2, m, m, s * 0.28f, true);
            case IC_CHECK -> {
                Path2D p = new Path2D.Float();
                p.moveTo(s * 0.22f, s * 0.52f);
                p.lineTo(s * 0.43f, s * 0.72f);
                p.lineTo(s * 0.79f, s * 0.29f);
                g2.draw(p);
            }
            case IC_ARROW_UP, IC_ARROW_DOWN -> {
                int dir = IC_ARROW_UP.equals(kind) ? -1 : 1;
                g2.draw(new java.awt.geom.Line2D.Float(m, m - dir * s * 0.30f, m, m + dir * s * 0.30f));
                Path2D p = new Path2D.Float();
                p.moveTo(m - s * 0.22f, m + dir * s * 0.06f);
                p.lineTo(m, m + dir * s * 0.30f);
                p.lineTo(m + s * 0.22f, m + dir * s * 0.06f);
                g2.draw(p);
            }
            case IC_EXPAND -> {
                // mũi tên 2 đầu chéo (⤢) — nét chéo + 2 chóp
                float a = s * 0.24f, b = s * 0.76f, k = s * 0.22f;
                g2.draw(new java.awt.geom.Line2D.Float(a, b, b, a));
                Path2D p = new Path2D.Float();
                p.moveTo(b - k, a); p.lineTo(b, a); p.lineTo(b, a + k);
                p.moveTo(a + k, b); p.lineTo(a, b); p.lineTo(a, b - k);
                g2.draw(p);
            }
            case IC_MINUS -> g2.draw(new java.awt.geom.Line2D.Float(s * 0.22f, m, s * 0.78f, m));
            case IC_PLUS -> {
                g2.draw(new java.awt.geom.Line2D.Float(s * 0.22f, m, s * 0.78f, m));
                g2.draw(new java.awt.geom.Line2D.Float(m, s * 0.22f, m, s * 0.78f));
            }
            case IC_MORE -> {
                float r = Math.max(1.6f, s * 0.09f);
                for (float dx : new float[]{-s * 0.26f, 0, s * 0.26f})
                    g2.fill(new java.awt.geom.Ellipse2D.Float(m + dx - r, m - r, r * 2, r * 2));
            }
            case IC_UNDO, IC_REDO -> {
                boolean redo = IC_REDO.equals(kind);
                float r = s * 0.28f;
                g2.draw(new Arc2D.Float(m - r, m - r * 0.9f, r * 2, r * 2, redo ? 30 : 150, 200, Arc2D.OPEN));
                Path2D p = new Path2D.Float();
                float ax = redo ? m + r : m - r;
                p.moveTo(ax - s * 0.13f, m - r * 0.9f + r - s * 0.16f);
                p.lineTo(ax, m - r * 0.9f + r);
                p.lineTo(ax + s * 0.13f, m - r * 0.9f + r - s * 0.16f);
                g2.draw(p);
            }
            case IC_RESET -> {
                float r = s * 0.28f;
                g2.draw(new Arc2D.Float(m - r, m - r, r * 2, r * 2, 60, 290, Arc2D.OPEN));
                Path2D p = new Path2D.Float();
                p.moveTo(m + r * 0.1f, m - r * 1.05f);
                p.lineTo(m + r * 0.62f, m - r * 0.62f);
                p.lineTo(m + r * 0.08f, m - r * 0.15f);
                g2.draw(p);
            }
            case IC_PLAY -> {
                Path2D p = new Path2D.Float();
                p.moveTo(s * 0.30f, s * 0.22f);
                p.lineTo(s * 0.78f, m);
                p.lineTo(s * 0.30f, s * 0.78f);
                p.closePath();
                g2.fill(p);
            }
            case IC_PAUSE -> {
                float w = s * 0.14f;
                g2.fill(new java.awt.geom.Rectangle2D.Float(s * 0.28f, s * 0.24f, w, s * 0.52f));
                g2.fill(new java.awt.geom.Rectangle2D.Float(s * 0.58f, s * 0.24f, w, s * 0.52f));
            }
            case IC_SEARCH -> {
                float r = s * 0.24f;
                g2.draw(new java.awt.geom.Ellipse2D.Float(s * 0.22f, s * 0.20f, r * 2, r * 2));
                g2.draw(new java.awt.geom.Line2D.Float(s * 0.22f + r * 1.75f, s * 0.20f + r * 1.75f,
                        s * 0.80f, s * 0.80f));
            }
            default -> { }
        }
    }

    private static void chevronPath(Graphics2D g2, float cx, float cy, float r, boolean up) {
        Path2D p = new Path2D.Float();
        float dy = up ? -r * 0.55f : r * 0.55f;
        p.moveTo(cx - r, cy - dy);
        p.lineTo(cx, cy + dy);
        p.lineTo(cx + r, cy - dy);
        g2.draw(p);
    }

    /** Vẽ mũi ▾ / ▴ tại tâm (dùng trong paint tay). */
    public static void paintChevron(Graphics2D g2, int cx, int cy, int size, Color c, boolean up) {
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.setStroke(new BasicStroke(Math.max(1.4f, size / 7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        chevronPath(g, cx, cy, size * 0.42f, up);
        g.dispose();
    }

    /** Vẽ dấu ✓ tại tâm. */
    public static void paintCheck(Graphics2D g2, int cx, int cy, int size, Color c) {
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.setStroke(new BasicStroke(Math.max(1.5f, size / 6f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Float();
        p.moveTo(cx - size * 0.32f, cy + size * 0.02f);
        p.lineTo(cx - size * 0.08f, cy + size * 0.26f);
        p.lineTo(cx + size * 0.34f, cy - size * 0.26f);
        g.draw(p);
        g.dispose();
    }

    /** Ô vuông tick 15–16px của bản thiết kế (vẽ trong renderer cây/menu). */
    public static void paintTickBox(Graphics2D g2, int x, int y, int size, boolean on) {
        Graphics2D g = (Graphics2D) g2.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (on) {
            g.setColor(ACCENT);
            g.fillRoundRect(x, y, size, size, 4, 4);
            paintCheck(g, x + size / 2, y + size / 2, size - 3, ON_ACCENT);
        } else {
            g.setColor(BG_INPUT);
            g.fillRoundRect(x, y, size, size, 4, 4);
            g.setColor(BORDER);
            g.drawRoundRect(x, y, size - 1, size - 1, 4, 4);
        }
        g.dispose();
    }

    // ══════════════════════════════════════════════════════════════════
    // Ô nhập có tiền tố (X −22.38) — nền BG_INPUT bo 7, chữ mono
    // ══════════════════════════════════════════════════════════════════

    /** Bọc field vào khung BG_INPUT bo góc, có nhãn tiền tố mờ ("X" / "Y"). */
    public static JPanel inputWrap(String prefix, JComponent field, int h) {
        JPanel p = card(BG_INPUT, BORDER, 7);
        p.setLayout(new BorderLayout(4, 0));
        p.setBorder(new EmptyBorder(0, 8, 0, 2));
        if (prefix != null && !prefix.isEmpty()) {
            JLabel l = new JLabel(prefix);
            l.setFont(mono(12, Font.PLAIN));
            l.setForeground(TEXT_DIM);
            p.add(l, BorderLayout.WEST);
        }
        stripField(field);
        p.add(field, BorderLayout.CENTER);
        p.setPreferredSize(new Dimension(90, h));
        p.setMinimumSize(new Dimension(40, h));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return p;
    }

    /** Bỏ viền/nền của field để nó "chìm" vào khung {@link #inputWrap}. */
    public static void stripField(JComponent field) {
        field.setOpaque(false);
        field.setBorder(BorderFactory.createEmptyBorder());
        if (field instanceof JSpinner sp) {
            sp.putClientProperty("JComponent.outline", null);
            if (sp.getEditor() instanceof JSpinner.DefaultEditor ed) {
                ed.setBorder(BorderFactory.createEmptyBorder());
                ed.setOpaque(false);
                JTextField tf = ed.getTextField();
                tf.setOpaque(false);
                tf.setBorder(BorderFactory.createEmptyBorder());
                tf.setFont(mono(12, Font.PLAIN));
                tf.setForeground(TEXT);
            }
        } else if (field instanceof JTextField tf) {
            tf.setFont(mono(12, Font.PLAIN));
            tf.setForeground(TEXT);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Legacy aliases (giữ để code cũ compile)
    // ══════════════════════════════════════════════════════════════════

    public static final Color BTN_PRIMARY = ACCENT;
    public static final Color BTN_SUCCESS = GREEN;
    public static final Color BTN_WARN    = ACCENT;
    public static final Color BTN_PURPLE  = PURPLE;
    public static final Color BTN_DANGER  = RED;
    public static final Color TXT_DIM     = TEXT_MUTED;

    /**
     * Nút sidebar. {@code bg == null} = nút "thường": nền surface-2 + viền + chữ sáng (kiểu ghost).
     * KHÔNG được để chữ {@link #ON_ACCENT} (chữ tối, dành cho nền vàng) trên nền tối — mất chữ.
     */
    public static JButton btn(String text, Color bg, ActionListener al) {
        boolean plain = (bg == null);
        JButton b = painted(text, plain ? BG_SURFACE2 : bg, plain ? TEXT_2 : ON_ACCENT,
                plain ? BORDER : null, true, 8);
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
        cb.setFont(font(13, Font.PLAIN));
        cb.setIconTextGap(8);
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

    /** Bấm chuột nhanh (dùng cho chip/label bấm được). */
    public static void onClick(JComponent c, Runnable r) {
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { r.run(); }
        });
    }
}
