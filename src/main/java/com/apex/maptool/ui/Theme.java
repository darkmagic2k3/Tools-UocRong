package com.apex.maptool.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionListener;

/**
 * Theme tập trung (kiểu NRO NrsTheme): FlatLaf dark + UIManager polish + factory component đồng nhất.
 * Mọi style UI sửa Ở ĐÂY, không rải rác từng file.
 */
public final class Theme {

    public static final Color ACCENT      = new Color(0, 180, 230);   // cyan accent
    public static final Color BTN_PRIMARY = new Color(0, 122, 204);   // xanh dương — action chính
    public static final Color BTN_SUCCESS = new Color(46, 140, 80);   // xanh lá — mở editor/OK
    public static final Color BTN_WARN    = new Color(196, 124, 31);  // cam — lưu DB (nguy hiểm nhẹ)
    public static final Color BTN_PURPLE  = new Color(140, 82, 170);  // tím — thao tác cross-map
    public static final Color BTN_DANGER  = new Color(190, 60, 60);   // đỏ — xóa
    public static final Color TXT_DIM     = new Color(160, 160, 175);

    private Theme() {}

    /** Gọi 1 lần trước khi dựng UI. */
    public static void apply() {
        try {
            com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme.setup();
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("ProgressBar.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("ScrollBar.width", 11);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("Table.rowHeight", 26);
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("List.selectionArc", 8);
            UIManager.put("TabbedPane.selectedBackground", new Color(50, 45, 75));
            UIManager.put("TitlePane.unifiedBackground", true);
            UIManager.put("ToolBar.separatorColor", new Color(90, 90, 110));
        } catch (Throwable t) {
            System.err.println("[Theme] FlatLaf fail: " + t.getMessage());
        }
    }

    /** Header section phẳng kiểu IDE: chữ accent in hoa nhỏ + đường kẻ. */
    public static JComponent sectionHeader(String title) {
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(title.toUpperCase());
        l.setForeground(ACCENT);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        p.add(l, BorderLayout.WEST);
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(80, 80, 100));
        JPanel sepWrap = new JPanel(new GridBagLayout());
        sepWrap.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        sepWrap.add(sep, g);
        p.add(sepWrap, BorderLayout.CENTER);
        p.setBorder(new EmptyBorder(4, 2, 4, 2));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Khối section: header + content, spacing chuẩn. expand=false → khoá chiều cao tự nhiên. */
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

    /** Nút màu đồng nhất: cao 30, chữ trắng bold, không focus ring. bg=null → nút thường. */
    public static JButton btn(String text, Color bg, ActionListener al) {
        JButton b = new JButton(text);
        if (bg != null) {
            b.setBackground(bg);
            b.setForeground(Color.WHITE);
            b.setFont(b.getFont().deriveFont(Font.BOLD));
        }
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(b.getPreferredSize().width, 30));
        if (al != null) b.addActionListener(al);
        return b;
    }

    /** Checkbox gọn không focus ring. */
    public static JCheckBox check(String text, boolean sel, java.util.function.Consumer<Boolean> setter) {
        JCheckBox cb = new JCheckBox(text, sel);
        cb.setFocusable(false);
        cb.setOpaque(false);
        cb.addActionListener(e -> setter.accept(cb.isSelected()));
        return cb;
    }

    /** Ô màu chú thích. */
    public static JComponent legendRow(Color c, String t) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        r.setOpaque(false);
        JPanel sw = new JPanel();
        sw.setBackground(c);
        sw.setPreferredSize(new Dimension(13, 13));
        sw.setBorder(BorderFactory.createLineBorder(new Color(70, 70, 90)));
        r.add(sw);
        JLabel l = new JLabel(t);
        l.setForeground(TXT_DIM);
        l.setFont(l.getFont().deriveFont(12f));
        r.add(l);
        return r;
    }
}
