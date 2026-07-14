package com.apex.maptool.ui;

import com.apex.maptool.db.InfoDao.InfoItem;
import com.apex.maptool.model.Marker;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Cửa sổ picker đối tượng (kiểu NRS MobTable/NpcTable): 2 tab Quái/NPC,
 * bảng Icon + ID + Tên + ô tìm. Click 1 dòng = cầm template → click canvas để đặt.
 */
public final class ObjectPickerFrame extends JFrame {

    private static final int ICON_H = 36;

    private final BiConsumer<Marker.Kind, Integer> onPick;

    public ObjectPickerFrame(List<InfoItem> enemies, List<InfoItem> npcs,
                             Function<Integer, BufferedImage> enemyIcon,
                             Function<Integer, BufferedImage> npcIcon,
                             BiConsumer<Marker.Kind, Integer> onPick) {
        super("Chọn đối tượng — click dòng rồi click map để đặt");
        this.onPick = onPick;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setAlwaysOnTop(true);
        setSize(440, 560);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("👾 Quái", buildTab(enemies, Marker.Kind.ENEMY, enemyIcon));
        tabs.addTab("🧍 NPC", buildTab(npcs, Marker.Kind.NPC, npcIcon));
        add(tabs);
    }

    private JComponent buildTab(List<InfoItem> all, Marker.Kind kind, Function<Integer, BufferedImage> iconFn) {
        Map<Integer, ImageIcon> iconCache = new HashMap<>();   // id → icon scaled (lazy)
        DefaultTableModel model = new DefaultTableModel(new Object[]{"", "ID", "Tên"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                return switch (c) { case 0 -> Icon.class; case 1 -> Integer.class; default -> String.class; };
            }
        };
        List<InfoItem> shown = new ArrayList<>(all);
        Runnable fill = () -> {
            model.setRowCount(0);
            for (InfoItem it : shown)
                model.addRow(new Object[]{icon(iconCache, iconFn, it.id()), it.id(), it.name() == null ? "" : it.name()});
        };
        fill.run();

        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(ICON_H + 8);
        table.getColumnModel().getColumn(0).setMaxWidth(56);
        table.getColumnModel().getColumn(1).setMaxWidth(64);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int row = table.getSelectedRow();
            if (row >= 0 && row < shown.size()) onPick.accept(kind, shown.get(row).id());
        });

        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Tìm tên / id...");
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                String q = search.getText().trim().toLowerCase();
                shown.clear();
                for (InfoItem it : all)
                    if (q.isEmpty() || it.toString().toLowerCase().contains(q)) shown.add(it);
                fill.run();
            }
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        });

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        p.add(search, BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        return p;
    }

    /** Icon scaled cao ICON_H, cache theo id. null nếu không có ảnh. */
    private static ImageIcon icon(Map<Integer, ImageIcon> cache, Function<Integer, BufferedImage> fn, int id) {
        if (cache.containsKey(id)) return cache.get(id);
        ImageIcon ic = null;
        try {
            BufferedImage img = fn != null ? fn.apply(id) : null;
            if (img != null) {
                int w = Math.max(1, img.getWidth() * ICON_H / Math.max(1, img.getHeight()));
                ic = new ImageIcon(img.getScaledInstance(w, ICON_H, Image.SCALE_SMOOTH));
            }
        } catch (Exception ignored) {}
        cache.put(id, ic);
        return ic;
    }
}
