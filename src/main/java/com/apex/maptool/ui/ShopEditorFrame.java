package com.apex.maptool.ui;

import com.apex.maptool.db.ShopDao;
import com.apex.maptool.db.ShopDao.ItemInfo;
import com.apex.maptool.db.ShopDao.ShopItem;
import com.apex.maptool.db.ShopDao.ShopType;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Shop Editor — clone pattern NRS ShopNewScr:
 * JTabbedPane [Tab cửa hàng | Item] · mỗi tab = form TRÁI cố định + bảng PHẢI ·
 * auto-save DB sau mỗi thao tác · nút màu 110×38 · tìm = lọc combo item.
 *
 * Map sang UocRongOnline: shop_type_config = tab (KHÔNG thêm/xóa — id gắn enum client),
 * price = 3 ô Vàng/Ngọc/Hồng ngọc (0 = không dùng) → JSON pairs.
 */
public final class ShopEditorFrame extends JFrame {

    private static final Gson GSON = new Gson();
    private static final Font F12 = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font F13 = new Font("SansSerif", Font.BOLD, 13);
    private static final Color GREEN = new Color(46, 139, 87);
    private static final Color BLUE = new Color(70, 130, 180);
    private static final Color RED = new Color(205, 92, 92);
    private static final Color ORANGE = new Color(210, 140, 30);
    private static final Color CP_BLUE = new Color(80, 120, 200);
    private static final Color CP_GREEN = new Color(60, 160, 100);
    private static final String[] LIMIT_TYPES = {"0 - Không", "1 - Ngày", "2 - Tuần", "3 - Tháng"};
    private static final String[] CLASSES = {"-1 - Tất cả", "0 - Trái Đất", "1 - Namek", "2 - Saiyan"};

    private final ShopDao dao;
    private final List<ItemInfo> itemInfos = new ArrayList<>();
    private final java.util.Map<Integer, String> itemNames = new java.util.HashMap<>();
    private List<ShopType> types = new ArrayList<>();
    private java.util.Map<Integer, Integer> itemCounts = new java.util.HashMap<>();

    // NPC: list (id, tên) + icon — để chọn list thay vì gõ id
    private final List<com.apex.maptool.db.InfoDao.InfoItem> npcs;
    private final java.util.function.IntFunction<java.awt.image.BufferedImage> npcIconFn;
    private final java.util.Map<Integer, ImageIcon> npcIconCache = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> npcNames = new java.util.HashMap<>();

    // ── tab "Tab cửa hàng" ──
    private JTable tblTab;
    private DefaultTableModel modelTab;
    private final JTextField txtTabName = new JTextField(8);
    private final JTextField txtMission = new JTextField(8);
    private final JTextField txtNpcs = new JTextField(8);   // read-only: hiện TÊN, chọn qua dialog
    private final List<Integer> selNpcIds = new ArrayList<>();
    private final JLabel lblTabId = new JLabel("-");
    private int selTabIdx = -1;

    // ── tab "Item" ──
    private JComboBox<ShopType> cboTabItem;
    private final JTextField txtFindItem = new JTextField(8);
    private JComboBox<ItemInfo> cboItemTemplate;
    // tiền tệ = item id bất kỳ (server trừ item theo key) — list phổ biến theo enum ItemId server
    private static final String[] CURRENCY_BASE = {
            "1 - Vàng", "2 - Ngọc xanh", "3 - Hồng ngọc", "4 - Exp",
            "5 - Điểm tiềm năng", "6 - Điểm năng động", "7 - Guild capsule"};
    private final JComboBox<String> cboCurrency = new JComboBox<>(CURRENCY_BASE);
    private final JTextField txtPrice = new JTextField(8);
    private final JSpinner spSlot = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
    private final JComboBox<String> cboLimitType = new JComboBox<>(LIMIT_TYPES);
    private final JSpinner spLimit = new JSpinner(new SpinnerNumberModel(0, 0, 99999, 1));
    private final JComboBox<String> cboClass = new JComboBox<>(CLASSES);
    private JTable tblItem;
    private DefaultTableModel modelItem;
    private final List<ShopItem> itemRows = new ArrayList<>();
    private final List<ShopItem> clipboard = new ArrayList<>();
    private boolean isFiltering;

    public ShopEditorFrame(ShopDao dao) { this(dao, java.util.List.of(), null); }

    public ShopEditorFrame(ShopDao dao, List<com.apex.maptool.db.InfoDao.InfoItem> npcs,
                           java.util.function.IntFunction<java.awt.image.BufferedImage> npcIconFn) {
        super("UR Tools - Quản lý Shop");
        this.dao = dao;
        this.npcs = npcs != null ? npcs : java.util.List.of();
        this.npcIconFn = npcIconFn;
        for (var n : this.npcs) npcNames.put(n.id(), n.name());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1100, 650);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(new Font("SansSerif", Font.BOLD, 13));
        tabs.addTab("  Tab cửa hàng  ", buildTabPane());
        tabs.addTab("  Item  ", buildItemPane());
        add(tabs, BorderLayout.CENTER);

        JLabel status = new JLabel("  ⚠ Auto-save: mỗi Thêm/Sửa/Xóa ghi thẳng DB (backup ở backup/) — sửa xong cần RESTART game server");
        status.setForeground(new Color(255, 190, 90));
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(70, 70, 90)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        add(status, BorderLayout.SOUTH);

        loadAll();
    }

    // ═══════════════ TAB 1: Tab cửa hàng ═══════════════
    private JComponent buildTabPane() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int y = 0;
        c.gridx = 0; c.gridy = y; form.add(lbl("Tab ID"), c);
        c.gridx = 1; c.weightx = 1; lblTabId.setFont(F13); form.add(lblTabId, c);
        y++;
        addInput(form, c, y++, "Tên tab", txtTabName);
        addInput(form, c, y++, "Nhiệm vụ", txtMission);
        txtNpcs.setEditable(false);
        JPanel npcRow = new JPanel(new BorderLayout(4, 0));
        npcRow.setOpaque(false);
        styleInput(txtNpcs);
        npcRow.add(txtNpcs, BorderLayout.CENTER);
        JButton btnPickNpc = new JButton("…");
        btnPickNpc.setPreferredSize(new Dimension(40, 34));
        btnPickNpc.setFocusPainted(false);
        btnPickNpc.addActionListener(e -> pickNpcs());
        npcRow.add(btnPickNpc, BorderLayout.EAST);
        addInput(form, c, y++, "NPC mở", npcRow);
        c.gridx = 0; c.gridy = y++; c.gridwidth = 3; c.weightx = 1;
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.add(mkBtn("Sửa", BLUE, e -> editTab()));
        btns.add(mkBtn("↻ Reload", ORANGE, e -> loadAll()));
        form.add(btns, c);
        c.gridy = y; c.gridwidth = 3;
        JLabel note = new JLabel("<html>⚠ Không thêm/xóa tab — id gắn cứng<br>enum TypeShop phía client.</html>");
        note.setForeground(new Color(160, 160, 175));
        note.setFont(F12);
        form.add(note, c);

        JPanel left = new JPanel(new BorderLayout());
        left.add(form, BorderLayout.NORTH);
        left.setPreferredSize(new Dimension(320, 0));

        tblTab = makeTable();
        modelTab = roModel("ID", "Tên tab", "NPC", "Items", "Nhiệm vụ");
        tblTab.setModel(modelTab);
        tblTab.getColumnModel().getColumn(0).setMaxWidth(54);
        tblTab.getColumnModel().getColumn(1).setPreferredWidth(160);
        tblTab.getColumnModel().getColumn(2).setPreferredWidth(230);   // NPC tên dài
        tblTab.getColumnModel().getColumn(3).setMaxWidth(60);
        leftAlign(tblTab, 1);
        leftAlign(tblTab, 2);
        tblTab.addMouseListener(click(this::selectTab));
        tblTab.addKeyListener(keyUp(this::selectTab));

        p.add(left, BorderLayout.WEST);
        p.add(new JScrollPane(tblTab), BorderLayout.CENTER);
        return p;
    }

    private void selectTab() {
        selTabIdx = tblTab.getSelectedRow();
        if (selTabIdx < 0 || selTabIdx >= types.size()) return;
        ShopType t = types.get(selTabIdx);
        lblTabId.setText(String.valueOf(t.id));
        txtTabName.setText(nv(t.shopName));
        txtMission.setText(String.valueOf(t.missionReq));
        selNpcIds.clear();
        selNpcIds.addAll(parseNpcIds(t.npcsJson));
        txtNpcs.setText(npcsLabel(t.npcsJson));
    }

    /** Dialog chọn NPC từ list (icon + tên + tìm, multi-select). */
    private void pickNpcs() {
        if (npcs.isEmpty()) { warn("Không có danh sách NPC (DB chưa kết nối lúc mở?)"); return; }
        DefaultListModel<com.apex.maptool.db.InfoDao.InfoItem> model = new DefaultListModel<>();
        List<com.apex.maptool.db.InfoDao.InfoItem> shown = new ArrayList<>(npcs);
        Runnable fill = () -> { model.clear(); for (var n : shown) model.addElement(n); };
        fill.run();
        JList<com.apex.maptool.db.InfoDao.InfoItem> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setFixedCellHeight(34);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                Component c = super.getListCellRendererComponent(l, v, i, sel, foc);
                if (v instanceof com.apex.maptool.db.InfoDao.InfoItem n) {
                    setText(n.id() + " - " + nv(n.name()));
                    setIcon(npcIcon(n.id()));
                }
                return c;
            }
        });
        // pre-select theo npc đang gán
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) if (selNpcIds.contains(shown.get(i).id())) idx.add(i);
        list.setSelectedIndices(idx.stream().mapToInt(Integer::intValue).toArray());

        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Tìm tên / id...");
        search.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) {
                String q = stripAccent(search.getText().trim().toLowerCase());
                shown.clear();
                for (var n : npcs)
                    if (q.isEmpty() || String.valueOf(n.id()).contains(q)
                            || stripAccent(String.valueOf(n.name()).toLowerCase()).contains(q)) shown.add(n);
                fill.run();
            }
        });
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(search, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(330, 380));
        p.add(sp, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(this, p, "Chọn NPC mở shop (giữ Ctrl chọn nhiều)",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        selNpcIds.clear();
        for (var n : list.getSelectedValuesList()) selNpcIds.add(n.id());
        JsonArray arr = new JsonArray();
        for (int id : selNpcIds) arr.add(id);
        txtNpcs.setText(npcsLabel(GSON.toJson(arr)));
    }

    private ImageIcon npcIcon(int id) {
        if (npcIconCache.containsKey(id)) return npcIconCache.get(id);
        ImageIcon ic = null;
        try {
            var img = npcIconFn != null ? npcIconFn.apply(id) : null;
            if (img != null) {
                int w = Math.max(1, img.getWidth() * 28 / Math.max(1, img.getHeight()));
                ic = new ImageIcon(img.getScaledInstance(w, 28, Image.SCALE_SMOOTH));
            }
        } catch (Exception ignored) {}
        npcIconCache.put(id, ic);
        return ic;
    }

    private static List<Integer> parseNpcIds(String json) {
        List<Integer> out = new ArrayList<>();
        try {
            JsonArray a = GSON.fromJson(json, JsonArray.class);
            if (a != null) for (var e : a) out.add(e.getAsInt());
        } catch (Exception ignored) {}
        return out;
    }

    /** "[2,26]" → "Bunma (2), Dende (26)". */
    private String npcsLabel(String json) {
        List<Integer> ids = parseNpcIds(json);
        if (ids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(", ");
            String nm = npcNames.get(id);
            sb.append(nm != null ? nm + " (" + id + ")" : "NPC " + id);
        }
        return sb.toString();
    }

    private void editTab() {
        if (selTabIdx < 0 || selTabIdx >= types.size()) { warn("Chọn 1 tab trong bảng trước."); return; }
        ShopType t = types.get(selTabIdx);
        if (selNpcIds.isEmpty()
                && JOptionPane.showConfirmDialog(this, "Chưa gán NPC nào — shop sẽ KHÔNG mở được trong game. Vẫn lưu?",
                "UR Tools - Xác nhận", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        JsonArray arr = new JsonArray();
        for (int id : selNpcIds) arr.add(id);
        try {
            t.shopName = txtTabName.getText().trim();
            t.missionReq = Integer.parseInt(txtMission.getText().trim());
            t.npcsJson = GSON.toJson(arr);
            dao.updateType(t);
            int keep = selTabIdx;
            loadAll();
            tblTab.getSelectionModel().setSelectionInterval(keep, keep);
            info("Sửa tab thành công!");
        } catch (NumberFormatException ex) { warn("Nhiệm vụ phải là số."); }
        catch (Exception ex) { warn("Lưu fail:\n" + ex.getMessage()); }
    }

    // ═══════════════ TAB 2: Item ═══════════════
    private JComponent buildItemPane() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int y = 0;

        cboTabItem = new JComboBox<>();
        styleInput(cboTabItem);
        cboTabItem.addActionListener(e -> refillItemTable());
        addInput(form, c, y++, "Tab", cboTabItem);
        addSep(form, c, y++);

        txtFindItem.putClientProperty("JTextField.placeholderText", "Gõ tên/id để lọc...");
        styleInput(txtFindItem);
        txtFindItem.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) { filterItemCombo(); }
        });
        addInput(form, c, y++, "Tìm", txtFindItem);

        cboItemTemplate = new JComboBox<>();
        styleInput(cboItemTemplate);
        addInput(form, c, y++, "Item", cboItemTemplate);
        addSep(form, c, y++);

        styleInput(cboCurrency); styleInput(txtPrice);
        addInput(form, c, y++, "Loại tiền", cboCurrency);
        addInput(form, c, y++, "Giá", txtPrice);
        c.gridx = 0; c.gridy = y++; c.gridwidth = 3; c.weightx = 1;
        JLabel priceNote = new JLabel("<html>Muốn bán bằng 2 loại tiền → tạo 2 dòng<br>item riêng (mỗi dòng 1 loại tiền).</html>");
        priceNote.setForeground(new Color(160, 160, 175));
        priceNote.setFont(F12);
        form.add(priceNote, c);
        c.gridwidth = 1;
        addSep(form, c, y++);

        styleInput(spSlot); styleInput(cboLimitType); styleInput(spLimit); styleInput(cboClass);
        addInput(form, c, y++, "Slot", spSlot);
        addInput(form, c, y++, "Kỳ giới hạn", cboLimitType);
        addInput(form, c, y++, "Lượt/kỳ (0=∞)", spLimit);
        addInput(form, c, y++, "Hành tinh", cboClass);
        addSep(form, c, y++);

        c.gridx = 0; c.gridy = y++; c.gridwidth = 3; c.weightx = 1;
        JPanel row1 = new JPanel(new GridLayout(1, 3, 6, 0));
        row1.add(mkBtn("Thêm", GREEN, e -> addItem()));
        row1.add(mkBtn("Sửa", BLUE, e -> editItem()));
        row1.add(mkBtn("Xóa", RED, e -> deleteItems()));
        form.add(row1, c);
        c.gridy = y;
        JPanel row2 = new JPanel(new GridLayout(1, 3, 6, 0));
        row2.add(mkBtn("📋 Copy", CP_BLUE, e -> copyItems()));
        row2.add(mkBtn("📋 Paste", CP_GREEN, e -> pasteItems()));
        row2.add(mkBtn("↻ Reload", ORANGE, e -> loadAll()));
        form.add(row2, c);

        JPanel left = new JPanel(new BorderLayout());
        left.add(form, BorderLayout.NORTH);
        left.setPreferredSize(new Dimension(370, 0));

        tblItem = makeTable();
        tblItem.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        modelItem = roModel("ID", "Tên item", "Giá", "Slot", "Giới hạn", "Hành tinh");
        tblItem.setModel(modelItem);
        // khoá width cột phụ — cột "Tên item" ăn hết phần thừa khi maximize
        var cm = tblItem.getColumnModel();
        cm.getColumn(0).setMaxWidth(60);
        cm.getColumn(1).setPreferredWidth(320);
        cm.getColumn(2).setMinWidth(150);   // Giá: "99,000,000 Vàng" / "10 Exp"
        cm.getColumn(2).setMaxWidth(210);
        cm.getColumn(3).setMaxWidth(50);    // Slot
        cm.getColumn(4).setMaxWidth(95);    // Giới hạn
        cm.getColumn(5).setMaxWidth(95);    // Hành tinh
        leftAlign(tblItem, 1);
        rightAlign(tblItem, 2);
        tblItem.addMouseListener(click(this::selectItem));
        tblItem.addKeyListener(keyUp(this::selectItem));
        tblItem.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) deleteItems();
            }
        });

        p.add(left, BorderLayout.WEST);
        p.add(new JScrollPane(tblItem), BorderLayout.CENTER);
        return p;
    }

    private ShopType curTab() { return (ShopType) cboTabItem.getSelectedItem(); }

    /** Gõ "Tìm" → rebuild combo item (match id/tên bỏ dấu) + bung popup (kiểu NRS). */
    private void filterItemCombo() {
        isFiltering = true;
        String q = stripAccent(txtFindItem.getText().trim().toLowerCase());
        cboItemTemplate.removeAllItems();
        for (ItemInfo ii : itemInfos) {
            if (q.isEmpty() || String.valueOf(ii.id()).contains(q)
                    || stripAccent(String.valueOf(ii.name()).toLowerCase()).contains(q))
                cboItemTemplate.addItem(ii);
        }
        isFiltering = false;
        if (cboItemTemplate.getItemCount() > 0) {
            cboItemTemplate.setSelectedIndex(0);
            if (!q.isEmpty()) cboItemTemplate.showPopup();
        }
    }

    private static String stripAccent(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('đ', 'd').replace('Đ', 'D');
    }

    private void selectItem() {
        int r = tblItem.getSelectedRow();
        if (r < 0 || r >= itemRows.size()) return;
        ShopItem it = itemRows.get(r);
        txtFindItem.setText("");
        filterItemCombo();
        for (int i = 0; i < cboItemTemplate.getItemCount(); i++)
            if (cboItemTemplate.getItemAt(i).id() == it.infoId) { cboItemTemplate.setSelectedIndex(i); break; }
        // pair đầu → combo + giá. Key lạ (tiền = item bất kỳ) → tự thêm entry vào combo.
        try {
            JsonArray arr = GSON.fromJson(it.priceJson, JsonArray.class);
            if (arr != null && !arr.isEmpty()) {
                JsonObject o = arr.get(0).getAsJsonObject();
                selectCurrency(o.get("key").getAsInt());
                txtPrice.setText(String.valueOf(o.get("value").getAsLong()));
            } else txtPrice.setText("");
        } catch (Exception e) { txtPrice.setText(""); }
        spSlot.setValue(Math.max(0, it.shopSlot));
        cboLimitType.setSelectedIndex(it.limitType >= 0 && it.limitType <= 3 ? it.limitType : 0);
        spLimit.setValue(Math.max(0, it.limit));
        cboClass.setSelectedIndex(Math.min(3, Math.max(0, it.clazz + 1)));
    }

    /** price JSON → "99,000,000 Vàng" / "10 Exp" (mọi loại tiền; nhiều pair nối " + "). */
    private String priceText(String json) {
        try {
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null || arr.isEmpty()) return "⚠ FREE!";
            StringBuilder sb = new StringBuilder();
            for (var e : arr) {
                JsonObject o = e.getAsJsonObject();
                if (sb.length() > 0) sb.append(" + ");
                sb.append(String.format("%,d", o.get("value").getAsLong()))
                        .append(' ').append(currencyName(o.get("key").getAsInt()));
            }
            return sb.toString();
        } catch (Exception e) { return "⚠ JSON hỏng"; }
    }

    /** Chọn loại tiền theo key; key chưa có trong combo → thêm entry "key - tên item". */
    private void selectCurrency(int key) {
        String prefix = key + " - ";
        for (int i = 0; i < cboCurrency.getItemCount(); i++)
            if (cboCurrency.getItemAt(i).startsWith(prefix)) { cboCurrency.setSelectedIndex(i); return; }
        String nm = itemNames.get(key);
        cboCurrency.addItem(prefix + (nm != null ? nm : "?"));
        cboCurrency.setSelectedIndex(cboCurrency.getItemCount() - 1);
    }

    private int currencyKey() {
        String s = String.valueOf(cboCurrency.getSelectedItem());
        try { return Integer.parseInt(s.split(" - ")[0].trim()); } catch (Exception e) { return 1; }
    }

    /** Tên loại tiền theo key (enum phổ biến → item_info → "tiền k"). */
    private String currencyName(int key) {
        for (String s : CURRENCY_BASE) if (s.startsWith(key + " - ")) return s.substring(s.indexOf(" - ") + 3);
        String nm = itemNames.get(key);
        return nm != null ? nm : "tiền " + key;
    }

    /** combo + ô giá → price JSON 1 pair (convention: 1 row = 1 loại tiền). */
    private String buildPrice() throws IllegalArgumentException {
        String s = txtPrice.getText().trim().replace(",", "").replace(".", "");
        long v;
        try { v = Long.parseLong(s); } catch (NumberFormatException e) { v = -1; }
        if (v <= 0)
            throw new IllegalArgumentException("Giá phải là số > 0 (giá rỗng = item FREE trong game!)");
        JsonArray arr = new JsonArray();
        arr.add(pair(currencyKey(), v));
        return GSON.toJson(arr);
    }

    private static JsonObject pair(int key, long value) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("value", value);
        return o;
    }

    private ShopItem buildItemFromForm(ShopItem base) throws IllegalArgumentException {
        ItemInfo sel = (ItemInfo) cboItemTemplate.getSelectedItem();
        if (sel == null) throw new IllegalArgumentException("Chưa chọn item template.");
        ShopItem it = base != null ? base : new ShopItem();
        it.shopTypeId = curTab().id;
        it.infoId = sel.id();
        it.priceJson = buildPrice();
        it.shopSlot = (Integer) spSlot.getValue();
        it.limitType = cboLimitType.getSelectedIndex();
        it.limit = (Integer) spLimit.getValue();
        it.clazz = cboClass.getSelectedIndex() - 1;
        return it;
    }

    private void addItem() {
        if (curTab() == null) return;
        try {
            dao.insertItem(buildItemFromForm(null));
            refillAfterWrite();
            info("Thêm item thành công!");
        } catch (IllegalArgumentException ex) { warn(ex.getMessage()); }
        catch (Exception ex) { warn("Thêm fail:\n" + ex.getMessage()); }
    }

    private void editItem() {
        int r = tblItem.getSelectedRow();
        if (r < 0 || r >= itemRows.size()) { warn("Chọn 1 dòng trong bảng trước."); return; }
        try {
            dao.updateItem(buildItemFromForm(itemRows.get(r)));
            refillAfterWrite();
            info("Sửa item thành công!");
        } catch (IllegalArgumentException ex) { warn(ex.getMessage()); }
        catch (Exception ex) { warn("Sửa fail:\n" + ex.getMessage()); }
    }

    private void deleteItems() {
        int[] rows = tblItem.getSelectedRows();
        if (rows.length == 0) { warn("Chọn dòng cần xóa."); return; }
        if (JOptionPane.showConfirmDialog(this, "Xóa " + rows.length + " item khỏi tab này?",
                "UR Tools - Xác nhận", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
        try {
            for (int r : rows) if (r >= 0 && r < itemRows.size()) dao.deleteItem(itemRows.get(r).id);
            refillAfterWrite();
        } catch (Exception ex) { warn("Xóa fail:\n" + ex.getMessage()); }
    }

    private void copyItems() {
        int[] rows = tblItem.getSelectedRows();
        if (rows.length == 0) { warn("Chọn dòng cần copy."); return; }
        clipboard.clear();
        for (int r : rows) if (r >= 0 && r < itemRows.size()) clipboard.add(itemRows.get(r));
        info("Đã copy " + clipboard.size() + " item. Chuyển tab rồi Paste.");
    }

    private void pasteItems() {
        if (curTab() == null) return;
        if (clipboard.isEmpty()) { warn("Clipboard trống — Copy trước."); return; }
        try {
            int n = 0;
            for (ShopItem src : clipboard) {
                ShopItem it = new ShopItem();
                it.shopTypeId = curTab().id;
                it.infoId = src.infoId;
                it.priceJson = src.priceJson;
                it.shopSlot = src.shopSlot;
                it.limitType = src.limitType;
                it.limit = src.limit;
                it.clazz = src.clazz;
                dao.insertItem(it);
                n++;
            }
            refillAfterWrite();
            info("Đã paste " + n + " item vào tab " + curTab().id + "!");
        } catch (Exception ex) { warn("Paste fail:\n" + ex.getMessage()); }
    }

    // ─── load/refill ───────────────────────────────────────────
    private void loadAll() {
        try {
            itemInfos.clear();
            itemInfos.addAll(dao.itemInfos());
            itemNames.clear();
            for (ItemInfo ii : itemInfos) itemNames.put(ii.id(), ii.name());
            itemCounts = dao.itemCounts();
            types = dao.types();
        } catch (Exception e) { warn("Load shop fail:\n" + e.getMessage()); return; }

        modelTab.setRowCount(0);
        for (ShopType t : types)
            modelTab.addRow(new Object[]{t.id, nv(t.shopName), npcsLabel(t.npcsJson),
                    itemCounts.getOrDefault(t.id, 0), t.missionReq});

        Object keep = cboTabItem.getSelectedItem();
        cboTabItem.removeAllItems();
        for (ShopType t : types) cboTabItem.addItem(t);
        if (keep != null) cboTabItem.setSelectedItem(keep);

        filterItemCombo();
        refillItemTable();
    }

    private void refillAfterWrite() {
        try { itemCounts = dao.itemCounts(); } catch (Exception ignored) {}
        refillItemTable();
        // cập nhật cột Items bảng tab
        for (int i = 0; i < types.size() && i < modelTab.getRowCount(); i++)
            modelTab.setValueAt(itemCounts.getOrDefault(types.get(i).id, 0), i, 3);
    }

    private void refillItemTable() {
        itemRows.clear();
        modelItem.setRowCount(0);
        ShopType t = curTab();
        if (t == null) return;
        try {
            for (ShopItem it : dao.items(t.id)) {
                itemRows.add(it);
                modelItem.addRow(new Object[]{it.id, itemLabel(it.infoId),
                        priceText(it.priceJson), it.shopSlot, limitLabel(it), classLabel(it.clazz)});
            }
        } catch (Exception e) { warn("Load items fail:\n" + e.getMessage()); }
    }

    private String itemLabel(int infoId) {
        String nm = itemNames.get(infoId);
        return nm != null ? infoId + " - " + nm : infoId + " - ⚠ KHÔNG TỒN TẠI";
    }

    private static String money(long v) { return v > 0 ? String.format("%,d", v) : "-"; }

    private static String limitLabel(ShopItem it) {
        if (it.limit <= 0 || it.limitType <= 0) return "-";
        return it.limit + "/" + switch (it.limitType) { case 1 -> "ngày"; case 2 -> "tuần"; case 3 -> "tháng"; default -> "?"; };
    }

    private static String classLabel(int c) {
        return switch (c) { case -1 -> "Tất cả"; case 0 -> "Trái Đất"; case 1 -> "Namek"; case 2 -> "Saiyan"; default -> String.valueOf(c); };
    }

    private static String nv(String s) { return s == null ? "" : s; }

    // ─── UI helpers (theo NRS mkBtn/makeTable) ─────────────────
    private static JButton mkBtn(String text, Color bg, ActionListener a) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFont(new Font("SansSerif", Font.BOLD, 14));
        b.setFocusPainted(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setPreferredSize(new Dimension(110, 38));
        b.setMargin(new Insets(6, 12, 6, 12));
        b.addActionListener(a);
        return b;
    }

    private static JTable makeTable() {
        JTable t = new JTable();
        t.setFont(F12);
        t.setRowHeight(26);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setFont(F13);
        t.setShowGrid(true);
        t.setGridColor(new Color(60, 60, 60));
        return t;
    }

    private static DefaultTableModel roModel(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private static void leftAlign(JTable t, int col) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.LEFT);
        t.getColumnModel().getColumn(col).setCellRenderer(r);
    }

    private static void rightAlign(JTable t, int col) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.RIGHT);
        t.getColumnModel().getColumn(col).setCellRenderer(r);
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 6, 5, 6);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static JLabel lbl(String s) {
        JLabel l = new JLabel(s);
        l.setFont(F13);
        return l;
    }

    private static void styleInput(JComponent comp) {
        comp.setFont(F13);
        comp.setPreferredSize(new Dimension(200, 34));
    }

    private void addInput(JPanel form, GridBagConstraints c, int y, String label, JComponent input) {
        if (input instanceof JTextField tf && tf.getPreferredSize().height < 34) styleInput(tf);
        c.gridwidth = 1;
        c.gridx = 0; c.gridy = y; c.weightx = 0;
        form.add(lbl(label), c);
        c.gridx = 1; c.weightx = 1;
        form.add(input, c);
    }

    private static void addSep(JPanel form, GridBagConstraints c, int y) {
        c.gridx = 0; c.gridy = y; c.gridwidth = 3; c.weightx = 1;
        form.add(new JSeparator(), c);
        c.gridwidth = 1;
    }

    private MouseAdapter click(Runnable r) {
        return new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { r.run(); } };
    }

    private KeyAdapter keyUp(Runnable r) {
        return new KeyAdapter() { @Override public void keyReleased(KeyEvent e) { r.run(); } };
    }

    private void info(String msg) { JOptionPane.showMessageDialog(this, msg, "UR Tools - Thông báo", JOptionPane.INFORMATION_MESSAGE); }
    private void warn(String msg) { JOptionPane.showMessageDialog(this, msg, "UR Tools - Thông báo", JOptionPane.WARNING_MESSAGE); }
}
