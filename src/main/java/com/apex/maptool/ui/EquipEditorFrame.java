package com.apex.maptool.ui;

import com.apex.maptool.db.AttrNames;
import com.apex.maptool.db.EquipDao;
import com.apex.maptool.db.EquipDao.EquipInfo;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Equip Editor — chỉnh CHỈ SỐ trang bị (equip_info: cải trang, áo, quần...).
 * Buff sửa bằng BẢNG DÒNG: chọn buff từ list (tên đầy đủ + tìm) + gõ giá trị,
 * không phải gõ chuỗi raw "type-value;type-value".
 *
 * Tên buff parse từ source server (ItemAttribute.java) — server thêm buff tool tự theo.
 * Ghi DB có backup + bump version_tracker. ⚠ Sửa xong RESTART game server.
 */
public final class EquipEditorFrame extends JFrame {

    private static final Font F12 = Theme.font(13, Font.PLAIN);
    private static final Font F13 = Theme.font(13, Font.BOLD);
    private static final Color GREEN = Theme.GREEN;
    private static final Color BLUE = Theme.BLUE;
    private static final Color RED = Theme.RED;
    private static final Color ORANGE = Theme.ACCENT;

    private final EquipDao dao;
    private final AttrNames attrs;

    private final List<EquipInfo> all = new ArrayList<>();
    private final List<EquipInfo> shown = new ArrayList<>();
    private JTable tblEquip;
    private DefaultTableModel modelEquip;
    private final JTextField txtFind = new JTextField(8);
    private final JLabel lblId = new JLabel("-");
    private final JLabel lblName = new JLabel("-");

    // 2 bảng buff: mỗi dòng {type, value} — cột Giá trị edit trực tiếp
    private final BuffTableModel mainBuff = new BuffTableModel();
    private final BuffTableModel randomBuff = new BuffTableModel();
    private JTable tblMain, tblRandom;
    private final JSpinner spStar = Theme.spin(0, 0, 99);
    private final JSpinner spLevel = Theme.spin(0, 0, 99);
    private final JSpinner spTime = Theme.spin(0, 0, 36500);

    public EquipEditorFrame(EquipDao dao, AttrNames attrs) {
        super("UR Tools - Chỉ số trang bị");
        this.dao = dao;
        this.attrs = attrs;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1180, 660);

        // ── LEFT: form buff ──
        JPanel left = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        txtFind.setFont(F13);
        txtFind.setPreferredSize(new Dimension(200, 32));
        txtFind.putClientProperty("JTextField.placeholderText", "Gõ tên/id để lọc...");
        txtFind.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) { refillEquipTable(); }
        });
        c.gridx = 0; c.gridy = y; c.weightx = 0; left.add(mkLbl("Tìm"), c);
        c.gridx = 1; c.weightx = 1; left.add(txtFind, c);
        y++;
        c.gridx = 0; c.gridy = y; c.weightx = 0; left.add(mkLbl("ID / Tên"), c);
        JPanel idName = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        idName.setOpaque(false);
        lblId.setFont(F13);
        lblId.setForeground(Theme.ACCENT);
        lblName.setFont(F13);
        idName.add(lblId);
        idName.add(lblName);
        c.gridx = 1; c.weightx = 1; left.add(idName, c);
        y++;

        // Star / Lv / Hạn — 1 hàng 3 ô, nhãn rõ
        c.gridx = 0; c.gridy = y; c.weightx = 0; left.add(mkLbl("Giới hạn"), c);
        JPanel slh = new JPanel(new GridLayout(1, 3, 8, 0));
        slh.setOpaque(false);
        slh.add(spinnerWithLabel("Star", spStar));
        slh.add(spinnerWithLabel("Lv", spLevel));
        slh.add(spinnerWithLabel("Ngày", spTime));
        c.gridx = 1; c.weightx = 1; left.add(slh, c);
        y++;

        tblMain = buffTable(mainBuff);
        tblRandom = buffTable(randomBuff);
        y = addBuffBlock(left, c, y, "BUFF CHÍNH", tblMain, mainBuff);
        y = addBuffBlock(left, c, y, "BUFF RANDOM (game chọn 1 khi tạo đồ)", tblRandom, randomBuff);

        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.weightx = 1; c.weighty = 0; c.fill = GridBagConstraints.HORIZONTAL;
        JPanel act = new JPanel(new GridLayout(1, 2, 6, 0));
        act.setOpaque(false);
        act.add(mkBtn("💾 Sửa (lưu DB)", BLUE, () -> save()));
        act.add(mkBtn("↻ Reload", ORANGE, () -> load()));
        left.add(act, c);

        JPanel leftWrap = new JPanel(new BorderLayout());
        leftWrap.add(left, BorderLayout.CENTER);   // form GIÃN THEO CHIỀU CAO (bảng buff cao khi maximize)
        leftWrap.setPreferredSize(new Dimension(440, 0));
        leftWrap.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 0));

        // ── CENTER: bảng equip ──
        tblEquip = new JTable();
        tblEquip.setFont(F12);
        tblEquip.setRowHeight(32);
        tblEquip.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tblEquip.getTableHeader().setFont(Theme.font(11, Font.BOLD));
        tblEquip.getTableHeader().setForeground(Theme.TEXT_MUTED);
        tblEquip.setShowGrid(false);
        tblEquip.setShowHorizontalLines(true);
        tblEquip.setGridColor(Theme.DIVIDER);
        tblEquip.setIntercellSpacing(new Dimension(0, 1));
        modelEquip = new DefaultTableModel(new Object[]{"ID", "Tên", "Chỉ số", "Random", "Star", "Lv", "Hạn(ngày)"}, 0) {
            @Override public boolean isCellEditable(int r, int col) { return false; }
        };
        tblEquip.setModel(modelEquip);
        var cm = tblEquip.getColumnModel();
        cm.getColumn(0).setMaxWidth(64);
        cm.getColumn(1).setPreferredWidth(170);
        cm.getColumn(2).setPreferredWidth(330);
        cm.getColumn(3).setPreferredWidth(180);
        cm.getColumn(4).setMaxWidth(46);
        cm.getColumn(5).setMaxWidth(46);
        cm.getColumn(6).setMaxWidth(74);
        DefaultTableCellRenderer lr = new DefaultTableCellRenderer();
        lr.setHorizontalAlignment(SwingConstants.LEFT);
        cm.getColumn(1).setCellRenderer(lr);
        cm.getColumn(2).setCellRenderer(lr);
        cm.getColumn(3).setCellRenderer(lr);
        tblEquip.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { selectEquip(); }
        });
        tblEquip.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) { selectEquip(); }
        });

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        root.add(leftWrap, BorderLayout.WEST);
        root.add(new JScrollPane(tblEquip), BorderLayout.CENTER);
        add(root, BorderLayout.CENTER);

        JLabel status = new JLabel("  ⚠ Ghi thẳng DB (backup ở backup/) — sửa xong RESTART game server. Option: "
                + attrs.sourceLabel() + ".");
        status.setForeground(new Color(0xe0, 0xc9, 0x8a));
        status.setFont(Theme.font(12, Font.PLAIN));
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        add(status, BorderLayout.SOUTH);

        load();
    }

    /** Block 1 bảng buff + nút thêm/xóa dòng. Bảng GIÃN DỌC khi maximize (weighty). */
    private int addBuffBlock(JPanel left, GridBagConstraints c, int y, String title, JTable table, BuffTableModel model) {
        c.gridx = 0; c.gridy = y++; c.gridwidth = 2; c.weightx = 1;
        JLabel l = new JLabel(title);
        l.setForeground(Theme.ACCENT);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 11f));
        left.add(l, c);
        c.gridy = y++;
        c.weighty = 0.5;                          // chia đều chiều cao thừa cho 2 bảng buff
        c.fill = GridBagConstraints.BOTH;
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(0, 150));
        left.add(sp, c);
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = y++;
        JPanel btns = new JPanel(new GridLayout(1, 2, 6, 0));
        btns.setOpaque(false);
        btns.add(mkBtn("＋ Thêm buff", GREEN, () -> addBuffRow(model)));
        btns.add(mkBtn("－ Xóa dòng", RED, () -> {
            int r = table.getSelectedRow();
            if (r >= 0) model.remove(r);
        }));
        left.add(btns, c);
        c.gridwidth = 1;
        return y;
    }

    private JTable buffTable(BuffTableModel model) {
        JTable t = new JTable(model);
        t.setFont(F12);
        t.setRowHeight(26);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setFont(F13);
        t.getColumnModel().getColumn(1).setMaxWidth(80);
        t.getColumnModel().getColumn(2).setMaxWidth(70);
        // double-click cột Buff → đổi buff (mở picker)
        t.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && t.columnAtPoint(e.getPoint()) == 0) {
                    int r = t.getSelectedRow();
                    if (r < 0) return;
                    Integer type = pickBuff();
                    if (type != null) model.setType(r, type);
                }
            }
        });
        return t;
    }

    private void addBuffRow(BuffTableModel model) {
        Integer type = pickBuff();
        if (type != null) model.add(type, 0);
    }

    /** Dialog chọn buff: list tên đầy đủ + tìm (bỏ dấu). */
    private Integer pickBuff() {
        var entries = attrs.entries();
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(this, attrs.errorText(),
                    "UR Tools - Thông báo", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        DefaultListModel<String> model = new DefaultListModel<>();
        List<Integer> ids = new ArrayList<>();
        Runnable[] refill = new Runnable[1];
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Tìm buff...");
        refill[0] = () -> {
            String q = stripAccent(search.getText().trim().toLowerCase());
            model.clear();
            ids.clear();
            for (var en : entries) {
                String label = en.getKey() + " - " + en.getValue();
                if (q.isEmpty() || stripAccent(label.toLowerCase()).contains(q)) {
                    model.addElement(label);
                    ids.add(en.getKey());
                }
            }
        };
        refill[0].run();
        search.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) { refill[0].run(); }
        });
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(search, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(560, 420));
        p.add(sp, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(this, p, "Chọn buff", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        int idx = list.getSelectedIndex();
        return (ok == JOptionPane.OK_OPTION && idx >= 0) ? ids.get(idx) : null;
    }

    // ─── data ──────────────────────────────────────────────────
    /** Load equip_info Ở LUỒNG NỀN (SwingWorker) → không đơ UI khi mở/Reload. */
    private void load() {
        getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<List<EquipInfo>, Void>() {
            @Override protected List<EquipInfo> doInBackground() throws Exception {
                return dao.all();
            }
            @Override protected void done() {
                try {
                    all.clear();
                    all.addAll(get());
                    refillEquipTable();
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(EquipEditorFrame.this,
                            "Load equip_info fail:\n" + rootMsg(e), "Lỗi", JOptionPane.ERROR_MESSAGE);
                } finally {
                    getContentPane().setCursor(Cursor.getDefaultCursor());
                }
            }
        }.execute();
    }

    /** Lấy message gốc (bóc ExecutionException của SwingWorker). */
    private static String rootMsg(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    private void refillEquipTable() {
        String q = stripAccent(txtFind.getText().trim().toLowerCase());
        shown.clear();
        modelEquip.setRowCount(0);
        for (var e : all) {
            if (!q.isEmpty() && !String.valueOf(e.id).contains(q)
                    && !stripAccent(String.valueOf(e.name).toLowerCase()).contains(q)) continue;
            shown.add(e);
            modelEquip.addRow(new Object[]{e.id, nv(e.name), explain(e.infoBuff), explain(e.randomBuff),
                    e.maxStar, e.maxLevel, e.time > 0 ? e.time : "-"});
        }
    }

    private void selectEquip() {
        int r = tblEquip.getSelectedRow();
        if (r < 0 || r >= shown.size()) return;
        var e = shown.get(r);
        lblId.setText(String.valueOf(e.id));
        lblName.setText(nv(e.name));
        spStar.setValue(Math.max(0, e.maxStar));
        spLevel.setValue(Math.max(0, e.maxLevel));
        spTime.setValue(Math.max(0, e.time));
        mainBuff.setFrom(e.infoBuff);
        randomBuff.setFrom(e.randomBuff);
    }

    /** Spinner kèm nhãn nhỏ phía trước. */
    private static JComponent spinnerWithLabel(String label, JSpinner sp) {
        JPanel p = new JPanel(new BorderLayout(3, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setFont(F12);
        p.add(l, BorderLayout.WEST);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private void save() {
        int r = tblEquip.getSelectedRow();
        if (r < 0 || r >= shown.size()) {
            JOptionPane.showMessageDialog(this, "Chọn 1 trang bị trong bảng trước.", "UR Tools - Thông báo", JOptionPane.WARNING_MESSAGE);
            return;
        }
        var e = shown.get(r);
        String info = mainBuff.serialize();
        String rnd = randomBuff.serialize();
        if (info.isBlank() && JOptionPane.showConfirmDialog(this,
                "Buff chính trống — trang bị sẽ KHÔNG có chỉ số. Vẫn lưu?",
                "UR Tools - Xác nhận", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        try {
            int star = Theme.spinInt(spStar), level = Theme.spinInt(spLevel), time = Theme.spinInt(spTime);
            dao.update(e.id, info, rnd, star, level, time);
            e.infoBuff = info;
            e.randomBuff = rnd.isBlank() ? null : rnd;
            e.maxStar = star;
            e.maxLevel = level;
            e.time = time;
            refillEquipTable();
            JOptionPane.showMessageDialog(this, "Sửa chỉ số thành công!\n(restart game server để áp dụng)",
                    "UR Tools - Thông báo", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Lưu fail:\n" + ex.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String explain(String buff) {
        if (buff == null || buff.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String part : buff.split(";")) {
            String[] tv = part.trim().split("-");
            if (tv.length < 2) continue;
            try {
                int type = Integer.parseInt(tv[0].trim());
                long val = Long.parseLong(tv[1].trim());
                if (sb.length() > 0) sb.append("; ");
                sb.append(attrs.describe(type, val));
            } catch (NumberFormatException ignored) {}
        }
        return sb.toString();
    }

    private static String stripAccent(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }

    private static String nv(String s) { return s == null ? "" : s; }

    private static JLabel mkLbl(String s) {
        JLabel l = new JLabel(s);
        l.setFont(F13);
        return l;
    }

    /** Nút tint theo Theme dark+amber. */
    private static JButton mkBtn(String text, Color bg, Runnable r) {
        return Theme.tint(text, bg, 34, e -> r.run());
    }

    /**
     * Bảng buff {type, value, bonus}: Buff (double-click đổi) | Giá trị | Bonus.
     * Bonus = khoảng random thêm (server: chỉ số = value + random(0..bonus)). 0 = không random.
     */
    private final class BuffTableModel extends DefaultTableModel {
        private final List<int[]> rows = new ArrayList<>();   // {type, value, bonus}

        BuffTableModel() { super(new Object[]{"Buff (double-click để đổi)", "Giá trị", "Bonus±"}, 0); }

        @Override public boolean isCellEditable(int r, int c) { return c == 1 || c == 2; }

        @Override public void setValueAt(Object v, int r, int c) {
            if ((c == 1 || c == 2) && r < rows.size()) {
                try { rows.get(r)[c] = Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException ignored) {}
            }
            super.setValueAt(v, r, c);
        }

        void setFrom(String buff) {
            rows.clear();
            setRowCount(0);
            if (buff != null && !buff.isBlank()) {
                for (String part : buff.split(";")) {
                    String[] tv = part.trim().split("-");
                    if (tv.length < 2) continue;
                    try {
                        int bonus = tv.length >= 3 ? Integer.parseInt(tv[2].trim()) : 0;
                        add(Integer.parseInt(tv[0].trim()), Integer.parseInt(tv[1].trim()), bonus);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        void add(int type, int value) { add(type, value, 0); }

        void add(int type, int value, int bonus) {
            rows.add(new int[]{type, value, bonus});
            addRow(new Object[]{buffName(type), value, bonus});
        }

        void remove(int r) {
            if (r >= 0 && r < rows.size()) {
                rows.remove(r);
                removeRow(r);
            }
        }

        void setType(int r, int type) {
            if (r >= 0 && r < rows.size()) {
                rows.get(r)[0] = type;
                super.setValueAt(buffName(type), r, 0);
            }
        }

        /** bonus > 0 → "type-value-bonus", không thì "type-value" (giữ đúng format gốc). */
        String serialize() {
            StringBuilder sb = new StringBuilder();
            for (int[] tv : rows) {
                if (sb.length() > 0) sb.append(';');
                sb.append(tv[0]).append('-').append(tv[1]);
                if (tv[2] > 0) sb.append('-').append(tv[2]);
            }
            return sb.toString();
        }

        private String buffName(int type) {
            String d = attrs.raw(type);
            return d != null ? type + " - " + d : "buff " + type;
        }
    }
}
