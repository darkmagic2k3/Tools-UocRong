package com.apex.maptool.ui;

import com.apex.maptool.model.IncomingDrop;
import com.apex.maptool.model.Marker;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Danh sách MỌI đối tượng di chuyển được (quái/NPC/cổng/điểm rơi vào):
 * lọc theo loại + tìm tên + icon + sort cột + xóa. Chọn dòng → highlight + canh giữa canvas.
 * "Khóa chọn" → CHỈ vật đã chọn mới kéo được (chống kéo nhầm).
 */
public final class MarkerListFrame extends JFrame {

    private static final int ICON_H = 24;

    private final MapCanvasPanel canvas;
    private final IntFunction<String> enemyName, npcName, mapName;
    private final IntFunction<BufferedImage> enemyIcon, npcIcon;
    private final Map<String, ImageIcon> iconCache = new HashMap<>();

    private final DefaultTableModel model;
    private final JTable table;
    private final JCheckBox cbLock;
    private final JComboBox<String> cbType;
    private final JTextField search;
    private final JLabel lblCount;
    private final List<Object> rows = new ArrayList<>();   // Marker | IncomingDrop theo dòng MODEL
    private boolean loading;

    public MarkerListFrame(MapCanvasPanel canvas,
                           IntFunction<String> enemyName, IntFunction<String> npcName, IntFunction<String> mapName,
                           IntFunction<BufferedImage> enemyIcon, IntFunction<BufferedImage> npcIcon) {
        super("Danh sách đối tượng — chọn rồi mới kéo");
        this.canvas = canvas;
        this.enemyName = enemyName;
        this.npcName = npcName;
        this.mapName = mapName;
        this.enemyIcon = enemyIcon;
        this.npcIcon = npcIcon;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setAlwaysOnTop(true);
        setSize(520, 560);

        model = new DefaultTableModel(new Object[]{"", "Loại", "Tên", "X", "Y"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                return switch (c) { case 0 -> Icon.class; case 3, 4 -> Integer.class; default -> String.class; };
            }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(ICON_H + 6);
        table.setAutoCreateRowSorter(true);   // click header để sort
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setMaxWidth(74);
        table.getColumnModel().getColumn(3).setMaxWidth(72);
        table.getColumnModel().getColumn(4).setMaxWidth(72);
        // màu chữ "Loại" theo legend
        table.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                comp.setForeground(switch (String.valueOf(v)) {
                    case "Quái" -> new Color(255, 110, 110);
                    case "NPC" -> new Color(110, 230, 130);
                    case "Cổng" -> new Color(255, 170, 70);
                    case "Rơi vào" -> new Color(230, 140, 255);
                    default -> comp.getForeground();
                });
                return comp;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || loading) return;
            Object o = selectedRowObject();
            if (o instanceof Marker m) {
                canvas.selectMarker(m);
                canvas.panToServer(m.serverX(), m.serverY());
            } else if (o instanceof IncomingDrop d) {
                canvas.selectIncoming(d);
                canvas.panToServer(d.serverX, d.serverY);
            }
        });

        // ─── thanh lọc trên ───
        cbType = new JComboBox<>(new String[]{"Tất cả", "Quái", "NPC", "Cổng", "Rơi vào"});
        cbType.setFocusable(false);
        cbType.addActionListener(e -> refresh());
        search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Tìm tên / id...");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refresh(); }
            @Override public void removeUpdate(DocumentEvent e) { refresh(); }
            @Override public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        JButton btnDel = Theme.btn("🗑 Xóa", Theme.BTN_DANGER, e -> deleteSelected());

        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.add(cbType, BorderLayout.WEST);
        filterRow.add(search, BorderLayout.CENTER);
        filterRow.add(btnDel, BorderLayout.EAST);

        cbLock = new JCheckBox("🔒 Khóa chọn — chỉ kéo được vật đã chọn trong list", true);
        cbLock.setFocusable(false);
        cbLock.addActionListener(e -> canvas.setSelectLock(cbLock.isSelected()));
        lblCount = new JLabel(" ");
        lblCount.setForeground(Theme.TXT_DIM);
        JPanel lockRow = new JPanel(new BorderLayout());
        lockRow.add(cbLock, BorderLayout.WEST);
        lockRow.add(lblCount, BorderLayout.EAST);

        JPanel top = new JPanel(new GridLayout(0, 1, 0, 4));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(filterRow);
        top.add(lockRow);
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // đóng cửa sổ → tắt khóa (tránh canvas "không kéo được gì" khó hiểu)
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { canvas.setSelectLock(false); }
        });
    }

    private Object selectedRowObject() {
        int vr = table.getSelectedRow();
        if (vr < 0) return null;
        int mr = table.convertRowIndexToModel(vr);
        return (mr >= 0 && mr < rows.size()) ? rows.get(mr) : null;
    }

    private void deleteSelected() {
        Object o = selectedRowObject();
        if (o instanceof Marker m) {
            canvas.selectMarker(m);
            canvas.deleteSelected();
            refresh();
        } else if (o instanceof IncomingDrop) {
            JOptionPane.showMessageDialog(this,
                    "Điểm rơi vào thuộc cổng của MAP KHÁC — xóa cổng đó ở map nguồn.",
                    "Không xóa được", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Bật khóa theo trạng thái checkbox (gọi khi mở cửa sổ). */
    public void applyLock() { canvas.setSelectLock(cbLock.isSelected()); }

    /** Nạp lại bảng theo filter loại + tìm kiếm. */
    public void refresh() {
        loading = true;
        Object keep = selectedRowObject();
        rows.clear();
        model.setRowCount(0);
        int type = cbType.getSelectedIndex();
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        long nQ = 0, nN = 0, nC = 0, nR = 0;
        for (Marker m : canvas.getEditMarkers()) {
            if (m.kind == Marker.Kind.ARRIVE) continue;
            switch (m.kind) { case ENEMY -> nQ++; case NPC -> nN++; case GATEWAY -> nC++; default -> {} }
            boolean typeOk = switch (type) {
                case 1 -> m.kind == Marker.Kind.ENEMY;
                case 2 -> m.kind == Marker.Kind.NPC;
                case 3 -> m.kind == Marker.Kind.GATEWAY;
                case 4 -> false;
                default -> true;
            };
            if (!typeOk) continue;
            String nm = nameOf(m);
            if (!q.isEmpty() && !nm.toLowerCase().contains(q)) continue;
            rows.add(m);
            model.addRow(new Object[]{iconOf(m), kindLabel(m.kind), nm, m.serverX(), m.serverY()});
        }
        for (IncomingDrop d : canvas.getIncomingDrops()) {
            nR++;
            if (type != 0 && type != 4) continue;
            String nm = mapName.apply(d.srcMapId);
            String label = "← " + (nm != null ? nm : ("map " + d.srcMapId));
            if (!q.isEmpty() && !label.toLowerCase().contains(q)) continue;
            rows.add(d);
            model.addRow(new Object[]{null, "Rơi vào", label, d.serverX, d.serverY});
        }
        lblCount.setText("Q" + nQ + " · N" + nN + " · C" + nC + " · R" + nR + "  ");
        int idx = keep == null ? -1 : rows.indexOf(keep);
        if (idx >= 0) {
            int vr = table.convertRowIndexToView(idx);
            if (vr >= 0) table.getSelectionModel().setSelectionInterval(vr, vr);
        }
        loading = false;
    }

    /** Cập nhật nhẹ toạ độ (khi kéo) — rebuild nếu cấu trúc đổi. */
    public void sync() {
        int expect = 0;
        for (Marker m : canvas.getEditMarkers()) if (m.kind != Marker.Kind.ARRIVE) expect++;
        expect += canvas.getIncomingDrops().size();
        // bảng đang lọc → rows ≤ expect; chỉ rebuild khi tổng đổi (thêm/xóa)
        if (lastTotal != expect) { lastTotal = expect; refresh(); return; }
        loading = true;
        for (int i = 0; i < rows.size(); i++) {
            Object o = rows.get(i);
            if (o instanceof Marker m) {
                model.setValueAt(m.serverX(), i, 3);
                model.setValueAt(m.serverY(), i, 4);
            } else if (o instanceof IncomingDrop d) {
                model.setValueAt(d.serverX, i, 3);
                model.setValueAt(d.serverY, i, 4);
            }
        }
        loading = false;
    }
    private int lastTotal = -1;

    /** Highlight dòng theo marker được chọn trên canvas (khi không khóa). */
    public void onCanvasSelect(Marker m) {
        if (m == null) return;
        int idx = rows.indexOf(m);
        if (idx >= 0) {
            loading = true;
            int vr = table.convertRowIndexToView(idx);
            if (vr >= 0) {
                table.getSelectionModel().setSelectionInterval(vr, vr);
                table.scrollRectToVisible(table.getCellRect(vr, 0, true));
            }
            loading = false;
        }
    }

    private static String kindLabel(Marker.Kind k) {
        return switch (k) { case ENEMY -> "Quái"; case NPC -> "NPC"; case GATEWAY -> "Cổng"; default -> k.toString(); };
    }

    private String nameOf(Marker m) {
        int id = m.mainId();
        String nm = switch (m.kind) {
            case ENEMY -> enemyName.apply(id);
            case NPC -> npcName.apply(id);
            case GATEWAY -> mapName.apply(id);
            default -> null;
        };
        String prefix = (m.kind == Marker.Kind.GATEWAY) ? "→ " : "";
        return prefix + (nm != null ? nm + " (" + id + ")" : String.valueOf(id));
    }

    private ImageIcon iconOf(Marker m) {
        if (m.kind != Marker.Kind.ENEMY && m.kind != Marker.Kind.NPC) return null;
        String key = m.kind + ":" + m.mainId();
        if (iconCache.containsKey(key)) return iconCache.get(key);
        ImageIcon ic = null;
        try {
            BufferedImage img = (m.kind == Marker.Kind.ENEMY) ? enemyIcon.apply(m.mainId()) : npcIcon.apply(m.mainId());
            if (img != null) {
                int w = Math.max(1, img.getWidth() * ICON_H / Math.max(1, img.getHeight()));
                ic = new ImageIcon(img.getScaledInstance(w, ICON_H, Image.SCALE_SMOOTH));
            }
        } catch (Exception ignored) {}
        iconCache.put(key, ic);
        return ic;
    }
}
