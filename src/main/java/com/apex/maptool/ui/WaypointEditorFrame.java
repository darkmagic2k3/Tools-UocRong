package com.apex.maptool.ui;

import com.apex.maptool.db.InfoDao.InfoItem;
import com.apex.maptool.model.Marker;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Cửa sổ riêng quản lý waypoint (cổng gate_way) của map đang sửa.
 *
 * Bảng list cổng + form sửa (map đích, type, aX/aY, bX/bY). Đồng bộ 2 chiều với canvas:
 * chọn dòng → highlight + canh cổng trên canvas; click cổng trên canvas → chọn dòng + đổ form.
 *
 * - aX/aY = vị trí cổng trên map hiện tại (đặt bằng click canvas / kéo / nhập số).
 * - bX/bY = vị trí player rớt ở map đích (nhập số hoặc "Chọn điểm rớt" = mở map đích rồi click).
 */
public final class WaypointEditorFrame extends JFrame {

    private final MapCanvasPanel canvas;
    private final List<InfoItem> maps;            // id,name cho dropdown map đích
    private final Consumer<Marker> pickDropHandler; // app xử lý "chọn điểm rớt map đích"
    private final Runnable onChanged;             // báo app updateStatus

    private final DefaultTableModel tableModel;
    private final JTable table;
    private final List<Marker> gateList = new ArrayList<>(); // row index → marker

    private final JComboBox<InfoItem> cbMap;
    private final JComboBox<String> cbType;
    private final JTextField tfAx, tfAy, tfBx, tfBy;
    private final JLabel lblWarn;
    private final JButton btnPick, btnDelete;

    private Marker current;       // cổng đang sửa
    private boolean loading;      // chặn listener khi đổ form

    public WaypointEditorFrame(MapCanvasPanel canvas, List<InfoItem> maps,
                               Consumer<Marker> pickDropHandler, Runnable onChanged) {
        super("Waypoint Editor — cổng gate_way");
        this.canvas = canvas;
        this.maps = maps != null ? maps : new ArrayList<>();
        this.pickDropHandler = pickDropHandler;
        this.onChanged = onChanged;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(560, 460);
        setLayout(new BorderLayout(6, 6));

        // ─── Bảng list cổng ───
        tableModel = new DefaultTableModel(new Object[]{"#", "Map đích", "type", "aX", "aY", "bX", "bY"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || loading) return;
            int row = table.getSelectedRow();
            if (row >= 0 && row < gateList.size()) {
                current = gateList.get(row);
                canvas.selectMarker(current);
                canvas.panToMarker(current);
                fillForm();
            }
        });
        add(new JScrollPane(table), BorderLayout.CENTER);

        // ─── Toolbar trên: thêm/xóa ───
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 5));
        JButton btnAdd = Theme.btn("➕  Thêm cổng (click map)", Theme.BTN_SUCCESS, e -> startPlace());
        btnDelete = Theme.btn("🗑  Xóa cổng", Theme.BTN_DANGER, e -> deleteCurrent());
        top.add(btnAdd);
        top.add(btnDelete);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(70, 70, 90)));
        add(top, BorderLayout.NORTH);

        // ─── Form sửa (phải) ───
        cbMap = new JComboBox<>(this.maps.toArray(new InfoItem[0]));
        cbType = new JComboBox<>(new String[]{"0 — Click", "1 — Chạm tự động"});
        tfAx = new JTextField(7); tfAy = new JTextField(7);
        tfBx = new JTextField(7); tfBy = new JTextField(7);
        lblWarn = new JLabel(" ");
        lblWarn.setForeground(new Color(200, 60, 60));
        btnPick = Theme.btn("📍  Chọn điểm rớt ở map đích", Theme.BTN_PURPLE,
                e -> { if (current != null && pickDropHandler != null) pickDropHandler.accept(current); });

        cbMap.addActionListener(e -> {
            if (loading || current == null) return;
            InfoItem it = (InfoItem) cbMap.getSelectedItem();
            if (it != null && it.id() != current.mainId()) { canvas.pushUndo(); current.setMainId(it.id()); afterEdit(); }
        });
        cbType.addActionListener(e -> {
            if (loading || current == null) return;
            int t = cbType.getSelectedIndex();
            if (t != current.getInt("type")) { canvas.pushUndo(); current.setInt("type", t); afterEdit(); }
        });
        bindInt(tfAx, "aX");
        bindInt(tfAy, "aY");
        bindInt(tfBx, "bX");
        bindInt(tfBy, "bY");

        add(buildForm(), BorderLayout.EAST);

        // Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) — undo/redo cả khi focus ở cửa sổ này
        JComponent rp = getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control Z"), "wpUndo");
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control shift Z"), "wpRedo");
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("control Y"), "wpRedo");
        rp.getActionMap().put("wpUndo", new AbstractAction() { public void actionPerformed(ActionEvent e) { canvas.undo(); } });
        rp.getActionMap().put("wpRedo", new AbstractAction() { public void actionPerformed(ActionEvent e) { canvas.redo(); } });

        setFormEnabled(false);
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setPreferredSize(new Dimension(250, 0));
        form.setBorder(BorderFactory.createTitledBorder("Cổng đang chọn"));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 4, 3, 4);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        int y = 0;
        addRow(form, g, y++, "Map đích:", cbMap);
        addRow(form, g, y++, "Loại (type):", cbType);
        addRow(form, g, y++, "aX (cổng):", tfAx);
        addRow(form, g, y++, "aY (cổng):", tfAy);
        addRow(form, g, y++, "bX (rớt):", tfBx);
        addRow(form, g, y++, "bY (rớt):", tfBy);

        g.gridx = 0; g.gridy = y++; g.gridwidth = 2;
        form.add(btnPick, g);
        g.gridy = y++;
        form.add(lblWarn, g);
        // đẩy lên trên
        g.gridy = y; g.weighty = 1; g.fill = GridBagConstraints.BOTH;
        form.add(Box.createGlue(), g);
        return form;
    }

    private static void addRow(JPanel p, GridBagConstraints g, int y, String label, JComponent field) {
        g.gridy = y; g.gridwidth = 1; g.weightx = 0;
        g.gridx = 0; p.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1; p.add(field, g);
    }

    /** Gán giá trị ô số vào marker (Enter hoặc rời ô). */
    private void bindInt(JTextField tf, String key) {
        Runnable apply = () -> {
            if (loading || current == null) return;
            try {
                int v = Integer.parseInt(tf.getText().trim());
                if (current.getInt(key) == v) return;        // không đổi → bỏ
                canvas.pushUndo();
                current.setInt(key, v);
                afterEdit();
            } catch (NumberFormatException ignored) { fillForm(); } // sai → khôi phục
        };
        tf.addActionListener(e -> apply.run());
        tf.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { apply.run(); }
        });
    }

    private void startPlace() {
        InfoItem it = (InfoItem) cbMap.getSelectedItem();
        int targetMap = it != null ? it.id() : 0;
        canvas.setPlaceMode(Marker.Kind.GATEWAY, targetMap, 0);
        canvas.requestFocusInWindow();
        JOptionPane.showMessageDialog(this,
                "Click lên map để đặt cổng mới (map đích = " + targetMap + ").\nSau khi đặt, chỉnh map đích/loại/điểm rớt ở form bên phải.",
                "Đặt cổng", JOptionPane.INFORMATION_MESSAGE);
    }

    private void deleteCurrent() {
        if (current == null) return;
        canvas.selectMarker(current);
        canvas.deleteSelected();
        current = null;
        refresh();
    }

    /** Sau khi sửa marker: cập nhật canvas + bảng + báo app. */
    private void afterEdit() {
        canvas.repaint();
        refreshTableKeepSelection();
        warnIfNoDrop();
        if (onChanged != null) onChanged.run();
    }

    /** Click cổng trên canvas → chọn dòng tương ứng + đổ form. */
    public void onCanvasSelect(Marker m) {
        if (m == null || m.kind != Marker.Kind.GATEWAY) {
            // giữ nguyên nếu chọn marker khác kind
            return;
        }
        current = m;
        int idx = gateList.indexOf(m);
        if (idx < 0) { refresh(); idx = gateList.indexOf(m); }
        if (idx >= 0) {
            loading = true;
            table.getSelectionModel().setSelectionInterval(idx, idx);
            table.scrollRectToVisible(table.getCellRect(idx, 0, true));
            loading = false;
        }
        fillForm();
    }

    /** Nạp lại bảng từ canvas (gọi khi load map / thêm / xóa / pick xong). */
    public void refresh() {
        loading = true;
        gateList.clear();
        tableModel.setRowCount(0);
        for (Marker m : canvas.getEditMarkers()) {
            if (m.kind == Marker.Kind.GATEWAY) gateList.add(m);
        }
        for (int i = 0; i < gateList.size(); i++) addTableRow(i, gateList.get(i));
        loading = false;
        // giữ selection nếu current còn trong list
        int idx = current == null ? -1 : gateList.indexOf(current);
        if (idx >= 0) table.getSelectionModel().setSelectionInterval(idx, idx);
        else { current = null; setFormEnabled(false); }
        fillForm();
    }

    private void refreshTableKeepSelection() {
        int sel = table.getSelectedRow();
        loading = true;
        for (int i = 0; i < gateList.size(); i++) {
            Marker m = gateList.get(i);
            tableModel.setValueAt(i, i, 0);
            tableModel.setValueAt(mapLabel(m.mainId()), i, 1);
            tableModel.setValueAt(m.getInt("type"), i, 2);
            tableModel.setValueAt(m.getInt("aX"), i, 3);
            tableModel.setValueAt(m.getInt("aY"), i, 4);
            tableModel.setValueAt(m.getInt("bX"), i, 5);
            tableModel.setValueAt(m.getInt("bY"), i, 6);
        }
        if (sel >= 0 && sel < gateList.size()) table.getSelectionModel().setSelectionInterval(sel, sel);
        loading = false;
    }

    /** Cập nhật nhẹ giá trị cổng đang chọn (gọi khi kéo cổng trên canvas). Không rebuild/đổi selection. */
    public void syncCurrent() {
        if (loading || current == null) return;
        int idx = gateList.indexOf(current);
        if (idx < 0) return;
        loading = true;
        tableModel.setValueAt(mapLabel(current.mainId()), idx, 1);
        tableModel.setValueAt(current.getInt("type"), idx, 2);
        tableModel.setValueAt(current.getInt("aX"), idx, 3);
        tableModel.setValueAt(current.getInt("aY"), idx, 4);
        tableModel.setValueAt(current.getInt("bX"), idx, 5);
        tableModel.setValueAt(current.getInt("bY"), idx, 6);
        if (!tfAx.hasFocus()) tfAx.setText(String.valueOf(current.getInt("aX")));
        if (!tfAy.hasFocus()) tfAy.setText(String.valueOf(current.getInt("aY")));
        if (!tfBx.hasFocus()) tfBx.setText(String.valueOf(current.getInt("bX")));
        if (!tfBy.hasFocus()) tfBy.setText(String.valueOf(current.getInt("bY")));
        warnIfNoDrop();
        loading = false;
    }

    private void addTableRow(int i, Marker m) {
        tableModel.addRow(new Object[]{
                i, mapLabel(m.mainId()), m.getInt("type"),
                m.getInt("aX"), m.getInt("aY"), m.getInt("bX"), m.getInt("bY")
        });
    }

    private void fillForm() {
        loading = true;
        boolean has = current != null;
        setFormEnabled(has);
        if (has) {
            selectMapInCombo(current.mainId());
            cbType.setSelectedIndex(current.getInt("type") == 1 ? 1 : 0);
            tfAx.setText(String.valueOf(current.getInt("aX")));
            tfAy.setText(String.valueOf(current.getInt("aY")));
            tfBx.setText(String.valueOf(current.getInt("bX")));
            tfBy.setText(String.valueOf(current.getInt("bY")));
            warnIfNoDrop();
        } else {
            tfAx.setText(""); tfAy.setText(""); tfBx.setText(""); tfBy.setText("");
            lblWarn.setText(" ");
        }
        loading = false;
    }

    private void warnIfNoDrop() {
        if (current != null && current.getInt("bX") == 0 && current.getInt("bY") == 0)
            lblWarn.setText("⚠ bX/bY = 0 → rớt tại (0,0) map đích");
        else
            lblWarn.setText(" ");
    }

    private void setFormEnabled(boolean on) {
        cbMap.setEnabled(on); cbType.setEnabled(on);
        tfAx.setEnabled(on); tfAy.setEnabled(on); tfBx.setEnabled(on); tfBy.setEnabled(on);
        btnPick.setEnabled(on); btnDelete.setEnabled(on);
    }

    private void selectMapInCombo(int id) {
        for (int i = 0; i < cbMap.getItemCount(); i++) {
            if (cbMap.getItemAt(i).id() == id) { cbMap.setSelectedIndex(i); return; }
        }
        cbMap.setSelectedItem(null); // map đích không có trong list
    }

    private String mapLabel(int id) {
        for (InfoItem it : maps) if (it.id() == id) {
            String nm = it.name();
            return id + (nm == null || nm.isBlank() ? "" : " " + nm);
        }
        return String.valueOf(id);
    }
}
