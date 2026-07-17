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
    private static final Font F12 = Theme.font(13, Font.PLAIN);
    private static final Font F13 = Theme.font(13, Font.BOLD);
    private static final Color GREEN = Theme.GREEN;
    private static final Color BLUE = Theme.BLUE;
    private static final Color RED = Theme.RED;
    private static final Color ORANGE = Theme.ACCENT;
    private static final Color CP_BLUE = Theme.BLUE;
    private static final Color CP_GREEN = Theme.GREEN;
    private static final String[] LIMIT_TYPES = {"0 - Không", "1 - Ngày", "2 - Tuần", "3 - Tháng"};
    private static final String[] CLASSES = {"-1 - Tất cả", "0 - Trái Đất", "1 - Namek", "2 - Xayda"};

    private final ShopDao dao;
    private final List<ItemInfo> itemInfos = new ArrayList<>();
    private final java.util.Map<Integer, String> itemNames = new java.util.HashMap<>();
    private final java.util.Map<Integer, String> itemSearchKeys = new java.util.HashMap<>(); // id → tên bỏ dấu (lọc nhanh, không normalize mỗi keystroke)
    private List<ShopType> types = new ArrayList<>();
    private java.util.Map<Integer, Integer> itemCounts = new java.util.HashMap<>();

    // ── Option (chỉ số) của item: buff trong equip_info theo infoId ──
    private final com.apex.maptool.db.EquipDao equipDao;   // null = tính năng option tắt
    private final com.apex.maptool.db.AttrNames attrs;     // tên option (enum ItemAttribute)
    private final java.util.Map<Integer, com.apex.maptool.db.EquipDao.EquipInfo> equipByInfoId = new java.util.HashMap<>();

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
    private final List<Integer> itemRowPriceKey = new ArrayList<>();   // loại tiền (item id) của từng dòng — cho icon cột Giá
    private final List<ShopItem> clipboard = new ArrayList<>();
    private boolean isFiltering;
    private boolean isRebuilding;   // chặn listener cboTabItem chạy khi đang dựng lại list tab (tránh reload thừa)

    public ShopEditorFrame(ShopDao dao) { this(dao, java.util.List.of(), null); }

    public ShopEditorFrame(ShopDao dao, List<com.apex.maptool.db.InfoDao.InfoItem> npcs,
                           java.util.function.IntFunction<java.awt.image.BufferedImage> npcIconFn) {
        this(dao, npcs, npcIconFn, null, null);
    }

    public ShopEditorFrame(ShopDao dao, List<com.apex.maptool.db.InfoDao.InfoItem> npcs,
                           java.util.function.IntFunction<java.awt.image.BufferedImage> npcIconFn,
                           com.apex.maptool.db.EquipDao equipDao, com.apex.maptool.db.AttrNames attrs) {
        super("UR Tools - Quản lý Shop");
        this.dao = dao;
        this.equipDao = equipDao;
        this.attrs = attrs;
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
        status.setForeground(new Color(0xe0, 0xc9, 0x8a));
        status.setFont(Theme.font(12, Font.PLAIN));
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
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
        note.setForeground(Theme.TEXT_MUTED);
        note.setFont(Theme.font(12, Font.PLAIN));
        form.add(note, c);

        JPanel left = new JPanel(new BorderLayout());
        left.add(form, BorderLayout.NORTH);   // width + viền phải đặt ở vScroll (preferred height phải để tự nhiên cho viewport)

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

        p.add(vScroll(left), BorderLayout.WEST);
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
            loadAll();   // async, tự giữ lại dòng đang chọn
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
        cboTabItem.addActionListener(e -> { if (!isRebuilding) refillItemTable(); });
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
        cboItemTemplate.setRenderer(ItemIcons.listRenderer(v -> v instanceof ItemInfo ii ? ii.id() : -1));
        addInput(form, c, y++, "Item", cboItemTemplate);
        addSep(form, c, y++);

        styleInput(cboCurrency); styleInput(txtPrice);
        cboCurrency.setRenderer(ItemIcons.listRenderer(ShopEditorFrame::leadingId));
        addInput(form, c, y++, "Loại tiền", cboCurrency);
        addInput(form, c, y++, "Giá", txtPrice);
        c.gridx = 0; c.gridy = y++; c.gridwidth = 3; c.weightx = 1;
        JLabel priceNote = new JLabel("<html>💡 Muốn bán bằng 2 loại tiền — tạo 2 dòng<br>item riêng (mỗi dòng 1 loại tiền).</html>");
        priceNote.setForeground(Theme.TEXT_MUTED);
        priceNote.setFont(Theme.font(12, Font.PLAIN));
        priceNote.setOpaque(true);
        priceNote.setBackground(Theme.BG_SURFACE2);
        priceNote.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        form.add(priceNote, c);
        c.gridwidth = 1;
        addSep(form, c, y++);

        styleInput(spSlot); styleInput(cboLimitType); styleInput(spLimit); styleInput(cboClass);
        addInput(form, c, y++, "Slot", spSlot);
        addInput(form, c, y++, "Kỳ giới hạn", cboLimitType);
        addInput(form, c, y++, "Lượt/kỳ (0=∞)", spLimit);
        addInput(form, c, y++, "Hành tinh", cboClass);
        addSep(form, c, y++);

        // Nút chính "Thêm item mới" (vàng) → mở panel thêm item đầy đủ
        c.gridx = 0; c.gridy = y++; c.gridwidth = 3; c.weightx = 1;
        form.add(Theme.primary("＋  Thêm item mới", 38, e -> openAddItemDialog()), c);
        addSep(form, c, y++);

        c.gridx = 0; c.gridy = y++; c.gridwidth = 3; c.weightx = 1;
        JPanel row1 = new JPanel(new GridLayout(1, 3, 6, 0)); row1.setOpaque(false);
        row1.add(Theme.tint("Thêm", GREEN, 34, e -> addItem()));
        row1.add(Theme.tint("Sửa", BLUE, 34, e -> editItem()));
        row1.add(Theme.tint("Xóa", RED, 34, e -> deleteItems()));
        form.add(row1, c);
        c.gridy = y++;
        JPanel row2 = new JPanel(new GridLayout(1, 3, 6, 0)); row2.setOpaque(false);
        row2.add(Theme.ghost("⧉ Copy", 34, e -> copyItems()));
        row2.add(Theme.ghost("📋 Paste", 34, e -> pasteItems()));
        row2.add(Theme.ghost("↻ Reload", 34, e -> loadAll()));
        form.add(row2, c);
        c.gridy = y;
        JPanel row3 = new JPanel(new GridLayout(1, 1, 6, 0)); row3.setOpaque(false);
        row3.add(Theme.tint("⚙ Option (chỉ số) item", Theme.PURPLE, 36, e -> openOptionDialog()));
        form.add(row3, c);

        JPanel left = new JPanel(new BorderLayout());
        left.add(form, BorderLayout.NORTH);   // width + viền phải đặt ở vScroll (preferred height phải để tự nhiên cho viewport)

        tblItem = makeTable();
        tblItem.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        modelItem = roModel("ID", "Tên item", "Giá", "Slot", "Giới hạn", "Hành tinh", "Option");
        tblItem.setModel(modelItem);
        // khoá width cột phụ — cột "Tên item" ăn hết phần thừa khi maximize
        var cm = tblItem.getColumnModel();
        cm.getColumn(0).setMaxWidth(60);
        cm.getColumn(1).setPreferredWidth(300);
        cm.getColumn(2).setMinWidth(140);   // Giá: "99,000,000 Vàng" / "10 Exp"
        cm.getColumn(2).setMaxWidth(200);
        cm.getColumn(3).setMaxWidth(50);    // Slot
        cm.getColumn(4).setMaxWidth(95);    // Giới hạn
        cm.getColumn(5).setMaxWidth(85);    // Hành tinh
        cm.getColumn(6).setMinWidth(90);    // Option (chỉ số) — số option của item
        cm.getColumn(6).setMaxWidth(150);
        cm.getColumn(0).setCellRenderer(new CellR(SwingConstants.CENTER, s -> Theme.TEXT_MUTED, F12, false));
        // Tên item: icon (IconItem/<infoId>.png) + chữ đậm
        cm.getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                setFont(Theme.font(13, Font.BOLD));
                setForeground(sel ? Theme.ACCENT_HOVER : Theme.TEXT);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                setIcon(row >= 0 && row < itemRows.size() ? ItemIcons.get(itemRows.get(row).infoId) : null);
                setIconTextGap(8);
                return this;
            }
        });
        // Giá: icon loại tiền (Vàng/Ngọc/vật phẩm) + số, màu theo loại
        cm.getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, foc, row, col);
                setHorizontalAlignment(SwingConstants.RIGHT);
                setHorizontalTextPosition(SwingConstants.LEFT);   // icon nằm SAU số
                setIconTextGap(6);
                setFont(Theme.font(13, Font.BOLD));
                String s = v == null ? "" : v.toString();
                setForeground(s.contains("Ngọc") ? Theme.PRICE_GEM : s.contains("Vàng") ? Theme.PRICE_GOLD : Theme.TEXT_2);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                setIcon(row >= 0 && row < itemRowPriceKey.size() ? ItemIcons.get(itemRowPriceKey.get(row)) : null);
                return this;
            }
        });
        cm.getColumn(3).setCellRenderer(new CellR(SwingConstants.CENTER, s -> Theme.TEXT_2, F12, false));
        cm.getColumn(4).setCellRenderer(new CellR(SwingConstants.LEFT, s -> Theme.TEXT_DIM, F12, false));
        cm.getColumn(5).setCellRenderer(new CellR(SwingConstants.LEFT, s -> Theme.TEXT_2, F12, false));
        cm.getColumn(6).setCellRenderer(new CellR(SwingConstants.LEFT,
                s -> "—".equals(s) ? Theme.TEXT_DIM : Theme.PURPLE, Theme.font(12, Font.PLAIN), false));
        tblItem.addMouseListener(click(this::selectItem));
        tblItem.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) openOptionDialog(); }
        });
        tblItem.addKeyListener(keyUp(this::selectItem));
        tblItem.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE) deleteItems();
            }
        });

        p.add(vScroll(left), BorderLayout.WEST);
        p.add(new JScrollPane(tblItem), BorderLayout.CENTER);
        return p;
    }

    private ShopType curTab() { return (ShopType) cboTabItem.getSelectedItem(); }

    /** Gõ "Tìm" → rebuild combo item (match id/tên bỏ dấu) + bung popup (kiểu NRS). */
    private void filterItemCombo() {
        isFiltering = true;
        String q = stripAccent(txtFindItem.getText().trim().toLowerCase());
        // Dựng model 1 lần rồi setModel (thay vì addItem từng cái → tránh event churn mỗi phần tử),
        // match bằng key bỏ dấu TÍNH SẴN (không normalize hàng trăm item mỗi keystroke → hết lag khi gõ).
        DefaultComboBoxModel<ItemInfo> model = new DefaultComboBoxModel<>();
        for (ItemInfo ii : itemInfos) {
            if (q.isEmpty() || String.valueOf(ii.id()).contains(q)
                    || itemSearchKeys.getOrDefault(ii.id(), "").contains(q))
                model.addElement(ii);
        }
        cboItemTemplate.setModel(model);
        isFiltering = false;
        if (model.getSize() > 0) {
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
        // Chỉ dựng lại combo khi đang lọc — click dòng lúc ô Tìm rỗng thì combo đã đủ item,
        // không rebuild 900+ item mỗi lần chọn dòng (đây là nguồn lag khi click bảng item).
        if (!txtFindItem.getText().isEmpty()) {
            txtFindItem.setText("");
            filterItemCombo();
        }
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
    /** Holder cho 1 lần load DB (chạy ở luồng nền). */
    private static final class ShopData {
        List<ItemInfo> infos;
        java.util.Map<Integer, Integer> counts;
        List<ShopType> types;
        List<com.apex.maptool.db.EquipDao.EquipInfo> equips;   // chỉ số item (equip_info) — null nếu tắt option
    }

    /** Load toàn bộ shop Ở LUỒNG NỀN (SwingWorker) → không đơ UI. Populate lại trên EDT khi xong. */
    private void loadAll() {
        getContentPane().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        final int keepTabRow = tblTab.getSelectedRow();
        final Object keepCombo = cboTabItem.getSelectedItem();
        new SwingWorker<ShopData, Void>() {
            @Override protected ShopData doInBackground() throws Exception {
                ShopData d = new ShopData();
                d.infos = dao.itemInfos();
                d.counts = dao.itemCounts();
                d.types = dao.types();
                if (equipDao != null) d.equips = equipDao.all();   // chỉ số option của item
                return d;
            }
            @Override protected void done() {
                try {
                    applyLoaded(get(), keepTabRow, keepCombo);
                } catch (Exception e) {
                    warn("Load shop fail:\n" + rootMsg(e));
                } finally {
                    getContentPane().setCursor(Cursor.getDefaultCursor());
                }
            }
        }.execute();
    }

    /** Đổ dữ liệu đã load vào UI (chạy trên EDT). */
    private void applyLoaded(ShopData d, int keepTabRow, Object keepCombo) {
        itemInfos.clear();
        itemInfos.addAll(d.infos);
        itemNames.clear();
        itemSearchKeys.clear();
        for (ItemInfo ii : itemInfos) {
            itemNames.put(ii.id(), ii.name());
            itemSearchKeys.put(ii.id(), stripAccent(String.valueOf(ii.name()).toLowerCase()));
        }
        itemCounts = d.counts;
        types = d.types;

        equipByInfoId.clear();
        if (d.equips != null)
            for (var e : d.equips) equipByInfoId.put(e.id, e);

        modelTab.setRowCount(0);
        for (ShopType t : types)
            modelTab.addRow(new Object[]{t.id, nv(t.shopName), npcsLabel(t.npcsJson),
                    itemCounts.getOrDefault(t.id, 0), t.missionReq});
        if (keepTabRow >= 0 && keepTabRow < modelTab.getRowCount())
            tblTab.getSelectionModel().setSelectionInterval(keepTabRow, keepTabRow);

        isRebuilding = true;             // dựng lại combo tab mà không kích refillItemTable() mỗi addItem
        cboTabItem.removeAllItems();
        for (ShopType t : types) cboTabItem.addItem(t);
        if (keepCombo != null) cboTabItem.setSelectedItem(keepCombo);
        isRebuilding = false;

        filterItemCombo();
        refillItemTable();
    }

    /** Lấy message gốc (bóc ExecutionException của SwingWorker). */
    private static String rootMsg(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() != null ? c.getMessage() : c.toString();
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
        itemRowPriceKey.clear();
        modelItem.setRowCount(0);
        ShopType t = curTab();
        if (t == null) return;
        try {
            for (ShopItem it : dao.items(t.id)) {
                itemRows.add(it);
                itemRowPriceKey.add(firstPriceKey(it.priceJson));
                modelItem.addRow(new Object[]{it.id, itemLabel(it.infoId),
                        priceText(it.priceJson), it.shopSlot, limitLabel(it), classLabel(it.clazz),
                        optionSummary(it.infoId)});
            }
        } catch (Exception e) { warn("Load items fail:\n" + e.getMessage()); }
    }

    /** Loại tiền (key = item id) của pair đầu trong price JSON — -1 nếu hỏng. */
    private static int firstPriceKey(String priceJson) {
        try {
            JsonArray arr = GSON.fromJson(priceJson, JsonArray.class);
            if (arr != null && !arr.isEmpty() && arr.get(0).isJsonObject())
                return arr.get(0).getAsJsonObject().get("key").getAsInt();
        } catch (Exception ignored) {}
        return -1;
    }

    /** Parse id đứng đầu chuỗi "71 - Thỏi Vàng" — cho icon combo loại tiền. */
    private static int leadingId(Object v) {
        try { return Integer.parseInt(String.valueOf(v).split(" - ")[0].trim()); }
        catch (Exception e) { return -1; }
    }

    /** Cập nhật lại cột Option của bảng item (sau khi sửa equip_info). */
    private void refreshOptionColumn() {
        for (int i = 0; i < itemRows.size() && i < modelItem.getRowCount(); i++)
            modelItem.setValueAt(optionSummary(itemRows.get(i).infoId), i, 6);
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

    // ═══════════════ OPTION (chỉ số) của item — equip_info theo infoId ═══════════════

    /** Tóm tắt số option của item cho cột "Option" bảng item. */
    private String optionSummary(int infoId) {
        if (equipDao == null) return "…";
        var e = equipByInfoId.get(infoId);
        if (e == null) return "—";                       // không phải trang bị → không có option
        int n = parseOptions(e.infoBuff).size();
        return n == 0 ? "0 chỉ số" : n + " chỉ số";
    }

    /** Parse info_buff "type-value" / "type-value-bonus" (; ngăn) → list {type,value,bonus}. */
    private static List<int[]> parseOptions(String buff) {
        List<int[]> out = new ArrayList<>();
        if (buff == null || buff.isBlank()) return out;
        for (String part : buff.split(";")) {
            String[] tv = part.trim().split("-");
            if (tv.length < 2) continue;
            try {
                int type = Integer.parseInt(tv[0].trim());
                int value = Integer.parseInt(tv[1].trim());
                int bonus = tv.length >= 3 ? Integer.parseInt(tv[2].trim()) : 0;
                out.add(new int[]{type, value, bonus});
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** list {type,value,bonus} → "type-value" / "type-value-bonus" (giữ đúng format equip_info). */
    private static String serializeOptions(List<int[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (int[] tv : rows) {
            if (sb.length() > 0) sb.append(';');
            sb.append(tv[0]).append('-').append(tv[1]);
            if (tv[2] > 0) sb.append('-').append(tv[2]);
        }
        return sb.toString();
    }

    /** Tên gọn của option (cắt template "HP + #" → "HP"). */
    private String optName(int type) {
        String d = attrs != null ? attrs.raw(type) : null;
        if (d == null) return "option " + type;
        int cut = d.length();
        for (String sep : new String[]{" +", " :", ":", "+", "(", "#"}) {
            int i = d.indexOf(sep);
            if (i > 0) cut = Math.min(cut, i);
        }
        return d.substring(0, cut).trim();
    }

    /** Kết quả hiển thị của 1 option: "5 %" / "+2300" (+bonus nếu có). */
    private String optResult(int[] tv) {
        String s = attrs != null ? attrs.describe(tv[0], tv[1]) : String.valueOf(tv[1]);
        if (tv[2] > 0) s += "  (±" + tv[2] + ")";
        return s;
    }

    /** Mở dialog xem/sửa option (chỉ số) của item đang chọn — ghi vào equip_info. */
    private void openOptionDialog() {
        if (equipDao == null || attrs == null) {
            warn("Tính năng Option chưa bật (thiếu kết nối equip_info / tên chỉ số ItemAttribute).");
            return;
        }
        int r = tblItem.getSelectedRow();
        if (r < 0 || r >= itemRows.size()) { warn("Chọn 1 item trong bảng trước rồi bấm Option."); return; }
        int infoId = itemRows.get(r).infoId;
        openOptionDialogFor(infoId);
    }

    /** Mở dialog option cho 1 infoId. Chưa có equip_info → tạo placeholder (sẽ INSERT khi lưu). */
    private void openOptionDialogFor(int infoId) {
        if (equipDao == null || attrs == null) {
            warn("Tính năng Option chưa bật (thiếu kết nối equip_info / tên chỉ số ItemAttribute).");
            return;
        }
        var eqInfo = equipByInfoId.get(infoId);
        boolean isNew = eqInfo == null;
        if (isNew) {                       // item chưa có bảng chỉ số → cho tạo mới
            eqInfo = new com.apex.maptool.db.EquipDao.EquipInfo();
            eqInfo.id = infoId;
            eqInfo.name = itemNames.get(infoId);
            eqInfo.infoBuff = "";
        }
        new OptionDialog(infoId, eqInfo, isNew).setVisible(true);
    }

    /**
     * Dialog chọn option ĐẦY ĐỦ: list (tìm theo tên/id) + nhập luôn Giá trị / Bonus±
     * + xem trước Kết quả — chọn xong là có số, không phải sửa lại trên bảng.
     * preType != null → prefill (dùng khi double-click đổi option đang có).
     * Trả {type, value, bonus} hoặc null nếu hủy.
     */
    private int[] pickOptionFull(Component parentComp, Integer preType, int preVal, int preBonus) {
        var entries = attrs.entries();
        if (entries.isEmpty()) { warn("Không đọc được danh sách chỉ số (ItemAttribute) từ server repo."); return null; }
        Window owner = parentComp instanceof Window w ? w : SwingUtilities.getWindowAncestor(parentComp);
        JDialog dlg = new JDialog(owner, "Chọn option (chỉ số)", Dialog.ModalityType.APPLICATION_MODAL);

        DefaultListModel<String> lm = new DefaultListModel<>();
        List<Integer> ids = new ArrayList<>();
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Tìm option theo tên/id...");
        Runnable refill = () -> {
            String q = stripAccent(search.getText().trim().toLowerCase());
            lm.clear(); ids.clear();
            for (var en : entries) {
                String label = en.getKey() + " - " + en.getValue();
                if (q.isEmpty() || stripAccent(label.toLowerCase()).contains(q)) { lm.addElement(label); ids.add(en.getKey()); }
            }
        };
        refill.run();
        JList<String> list = new JList<>(lm);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        search.addKeyListener(new KeyAdapter() { @Override public void keyReleased(KeyEvent e) {
            refill.run();
            if (!lm.isEmpty()) list.setSelectedIndex(0);
        } });

        // ── khu nhập giá trị + preview kết quả (phần "chưa có" bạn yêu cầu) ──
        JTextField txtVal = new JTextField(String.valueOf(preVal), 7);
        JTextField txtBonus = new JTextField(String.valueOf(preBonus), 7);
        txtVal.setFont(Theme.font(14, Font.BOLD));
        txtBonus.setFont(Theme.font(14, Font.BOLD));
        JLabel preview = new JLabel("—");
        preview.setForeground(Theme.GREEN);
        preview.setFont(Theme.font(13, Font.BOLD));

        Runnable updatePreview = () -> {
            int idx = list.getSelectedIndex();
            if (idx < 0 || idx >= ids.size()) { preview.setText("— chọn 1 option ở trên —"); return; }
            int type = ids.get(idx);
            int v = parseIntSafe(txtVal.getText());
            int b = parseIntSafe(txtBonus.getText());
            preview.setText(attrs.describe(type, v) + (b > 0 ? "  (±" + b + ")" : ""));
        };
        list.addListSelectionListener(e -> updatePreview.run());
        KeyAdapter kv = new KeyAdapter() { @Override public void keyReleased(KeyEvent e) { updatePreview.run(); } };
        txtVal.addKeyListener(kv);
        txtBonus.addKeyListener(kv);

        // prefill khi sửa option đang có
        if (preType != null) {
            int pos = ids.indexOf(preType);
            if (pos >= 0) { list.setSelectedIndex(pos); list.ensureIndexIsVisible(pos); }
        }
        updatePreview.run();

        JPanel valRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        valRow.setOpaque(false);
        JLabel lv = new JLabel("Giá trị"); lv.setForeground(Theme.TEXT_MUTED);
        JLabel lb = new JLabel("Bonus±"); lb.setForeground(Theme.TEXT_MUTED);
        JLabel lk = new JLabel("   Kết quả:"); lk.setForeground(Theme.TEXT_MUTED);
        valRow.add(lv); valRow.add(txtVal);
        valRow.add(lb); valRow.add(txtBonus);
        valRow.add(lk); valRow.add(preview);
        JPanel valBox = new JPanel(new BorderLayout());
        valBox.setBackground(Theme.BG_SURFACE2);
        valBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        valBox.add(valRow, BorderLayout.CENTER);

        final int[][] result = {null};
        Runnable accept = () -> {
            int idx = list.getSelectedIndex();
            if (idx < 0 || idx >= ids.size()) { warn("Chọn 1 option trong danh sách trước."); return; }
            result[0] = new int[]{ids.get(idx), parseIntSafe(txtVal.getText()), parseIntSafe(txtBonus.getText())};
            dlg.dispose();
        };
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) accept.run(); }
        });

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        btns.setOpaque(false);
        btns.add(Theme.ghost("Hủy", 34, e -> dlg.dispose()));
        btns.add(Theme.primary("✓ OK", 34, e -> accept.run()));

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(valBox, BorderLayout.NORTH);
        south.add(btns, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));
        p.add(search, BorderLayout.NORTH);
        p.add(new JScrollPane(list), BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);

        dlg.setContentPane(p);
        dlg.setSize(620, 560);
        dlg.setLocationRelativeTo(owner);
        dlg.setVisible(true);   // block tới khi đóng
        return result[0];
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim().replace(",", "").replace(".", "")); }
        catch (Exception e) { return 0; }
    }

    /** Bảng option trong dialog: ID | Tên | Giá trị | Bonus± | Kết quả. Giá trị/Bonus sửa trực tiếp. */
    private final class OptionTableModel extends DefaultTableModel {
        private final List<int[]> rows = new ArrayList<>();   // {type, value, bonus}
        OptionTableModel() { super(new Object[]{"ID", "Tên option", "Giá trị", "Bonus±", "Kết quả"}, 0); }

        @Override public boolean isCellEditable(int r, int c) { return c == 2 || c == 3; }

        @Override public void setValueAt(Object v, int r, int c) {
            if ((c == 2 || c == 3) && r < rows.size()) {
                try { rows.get(r)[c - 1] = Integer.parseInt(String.valueOf(v).trim()); }
                catch (NumberFormatException ignored) { return; }
                super.setValueAt(v, r, c);
                super.setValueAt(optResult(rows.get(r)), r, 4);
                return;
            }
            super.setValueAt(v, r, c);
        }

        void setFrom(String buff) {
            rows.clear(); setRowCount(0);
            for (int[] tv : parseOptions(buff)) addOption(tv[0], tv[1], tv[2]);
        }
        void addOption(int type) { addOption(type, 0, 0); }
        private void addOption(int type, int value, int bonus) {
            int[] tv = {type, value, bonus};
            rows.add(tv);
            addRow(new Object[]{type, optName(type), value, bonus, optResult(tv)});
        }
        void remove(int r) { if (r >= 0 && r < rows.size()) { rows.remove(r); removeRow(r); } }
        void setType(int r, int type) {
            if (r >= 0 && r < rows.size()) {
                rows.get(r)[0] = type;
                super.setValueAt(type, r, 0);
                super.setValueAt(optName(type), r, 1);
                super.setValueAt(optResult(rows.get(r)), r, 4);
            }
        }
        /** {type,value,bonus} của dòng r (copy). */
        int[] rowAt(int r) { return rows.get(r).clone(); }
        /** Ghi đè cả dòng (đổi option + giá trị + bonus từ picker). */
        void setRow(int r, int type, int value, int bonus) {
            if (r < 0 || r >= rows.size()) return;
            int[] tv = rows.get(r);
            tv[0] = type; tv[1] = value; tv[2] = bonus;
            super.setValueAt(type, r, 0);
            super.setValueAt(optName(type), r, 1);
            super.setValueAt(value, r, 2);
            super.setValueAt(bonus, r, 3);
            super.setValueAt(optResult(tv), r, 4);
        }
        String serialize() { return serializeOptions(rows); }
    }

    private final class OptionDialog extends JDialog {
        private final int infoId;
        private final com.apex.maptool.db.EquipDao.EquipInfo eqInfo;
        private final boolean isNew;
        private final OptionTableModel model = new OptionTableModel();
        private final JTable table = new JTable(model);

        OptionDialog(int infoId, com.apex.maptool.db.EquipDao.EquipInfo eqInfo, boolean isNew) {
            super(ShopEditorFrame.this, "Option (chỉ số) — " + itemLabel(infoId), true);
            this.infoId = infoId;
            this.eqInfo = eqInfo;
            this.isNew = isNew;
            model.setFrom(eqInfo.infoBuff);
            build();
            // rộng rãi mặc định (không phải kéo tay) — co lại nếu màn hình nhỏ
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
            setSize(Math.min(1000, scr.width - 120), Math.min(680, scr.height - 120));
            setLocationRelativeTo(null);
        }

        private void build() {
            JPanel p = new JPanel(new BorderLayout(8, 8));
            p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            table.setFont(F12);
            table.setRowHeight(30);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.getTableHeader().setFont(F13);
            var cm = table.getColumnModel();
            cm.getColumn(0).setMaxWidth(56);
            cm.getColumn(1).setPreferredWidth(260);
            cm.getColumn(2).setMaxWidth(110);
            cm.getColumn(3).setMaxWidth(100);
            cm.getColumn(4).setPreferredWidth(340);   // Kết quả — cột dài nhất
            table.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && table.columnAtPoint(e.getPoint()) <= 1) {
                        int r = table.getSelectedRow();
                        if (r < 0) return;
                        int[] cur = model.rowAt(r);
                        int[] res = pickOptionFull(OptionDialog.this, cur[0], cur[1], cur[2]);   // prefill giá trị đang có
                        if (res != null) model.setRow(r, res[0], res[1], res[2]);
                    }
                }
            });

            JLabel note = new JLabel("<html>⚠ Sửa ở đây thay đổi <b style='color:#ffc94d'>chỉ số GỐC</b> của item (equip_info id " + infoId + ")"
                    + (isNew ? " — <b style='color:#7ec8ff'>item CHƯA có bảng chỉ số, lưu sẽ TẠO MỚI</b>." : " — áp dụng MỌI NƠI item xuất hiện.")
                    + "<br>Double-click cột ID/Tên để đổi option · sửa Giá trị/Bonus ngay trên bảng → xong <b style='color:#ffc94d'>RESTART</b> server.</html>");
            note.setForeground(new Color(0xe0, 0xc9, 0x8a));
            note.setFont(F12);
            JPanel warn = warnBox(note);

            JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            btns.setOpaque(false);
            btns.add(Theme.tint("＋ Thêm", GREEN, 34, e -> {
                int[] res = pickOptionFull(OptionDialog.this, null, 0, 0);
                if (res != null) model.addOption(res[0], res[1], res[2]);
            }));
            btns.add(Theme.tint("－ Xóa", RED, 34, e -> { int r = table.getSelectedRow(); if (r >= 0) model.remove(r); }));
            JPanel btnWrap = new JPanel(new BorderLayout());
            btnWrap.setOpaque(false);
            btnWrap.add(btns, BorderLayout.WEST);
            JPanel save = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            save.setOpaque(false);
            save.add(Theme.primary("💾 Lưu", 34, e -> save()));
            btnWrap.add(save, BorderLayout.EAST);

            p.add(warn, BorderLayout.NORTH);
            p.add(new JScrollPane(table), BorderLayout.CENTER);
            p.add(btnWrap, BorderLayout.SOUTH);
            setContentPane(p);
        }

        private void save() {
            if (table.isEditing() && table.getCellEditor() != null) table.getCellEditor().stopCellEditing();
            String infoBuff = model.serialize();
            try {
                // upsert: có row → update info_buff; chưa có → INSERT row mới (id, name, info_buff)
                equipDao.upsertOptions(infoId, eqInfo.name != null ? eqInfo.name : itemNames.get(infoId), infoBuff);
                eqInfo.infoBuff = infoBuff;              // cập nhật cache
                equipByInfoId.put(infoId, eqInfo);       // item mới → thêm vào cache để cột Option hiện đúng
                refreshOptionColumn();
                info("Lưu option thành công!" + (isNew ? "\n(đã tạo bảng chỉ số mới cho item)" : "")
                        + "\n(RESTART game server để áp dụng)");
            } catch (Exception ex) {
                warn("Lưu option fail:\n" + rootMsg(ex));
            }
        }
    }

    // ═══════════════ THÊM ITEM MỚI (panel đầy đủ) ═══════════════
    private void openAddItemDialog() {
        if (types.isEmpty()) { warn("Chưa load được tab shop. Bấm ↻ Reload rồi thử lại."); return; }
        new AddItemDialog().setVisible(true);
    }

    /** Picker chọn 1 item từ itemInfos (search + list). Trả ItemInfo hoặc null. */
    private ItemInfo pickItemInfo(Component parent, String title) {
        DefaultListModel<ItemInfo> lm = new DefaultListModel<>();
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Tìm tên/id...");
        Runnable refill = () -> {
            String q = stripAccent(search.getText().trim().toLowerCase());
            lm.clear();
            for (ItemInfo ii : itemInfos)
                if (q.isEmpty() || String.valueOf(ii.id()).contains(q) || itemSearchKeys.getOrDefault(ii.id(), "").contains(q))
                    lm.addElement(ii);
        };
        refill.run();
        search.addKeyListener(new KeyAdapter() { @Override public void keyReleased(KeyEvent e) { refill.run(); } });
        JList<ItemInfo> list = new JList<>(lm);
        list.setCellRenderer(ItemIcons.listRenderer(v -> v instanceof ItemInfo ii ? ii.id() : -1));
        list.setFixedCellHeight(32);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(search, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(440, 380));
        p.add(sp, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(parent, p, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return ok == JOptionPane.OK_OPTION ? list.getSelectedValue() : null;
    }

    /** Panel thêm item mới đầy đủ: tab · item · loại bán (tiền tệ / vật phẩm id) · giá · slot · giới hạn · hành tinh · option. */
    private final class AddItemDialog extends JDialog {
        private final JComboBox<ShopType> cboTab = new JComboBox<>();
        private final JTextField txtSearch = new JTextField();
        private final JComboBox<ItemInfo> cboItem = new JComboBox<>();
        private final JRadioButton rbMoney = new JRadioButton("Tiền tệ (Vàng / Ngọc / Hồng ngọc)", true);
        private final JRadioButton rbItem = new JRadioButton("Vật phẩm (dùng item id làm giá)");
        private final JComboBox<String> cboMoney = new JComboBox<>(CURRENCY_BASE);
        private int currencyItemId = -1;
        private final JLabel lblCurItem = new JLabel("(chưa chọn)");
        private final JTextField txtPrice = new JTextField();
        private final JSpinner spSlot = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
        private final JComboBox<String> cboLimit = new JComboBox<>(LIMIT_TYPES);
        private final JSpinner spLimit = new JSpinner(new SpinnerNumberModel(0, 0, 99999, 1));
        private final JComboBox<String> cboClass = new JComboBox<>(CLASSES);
        private final OptionTableModel optModel = new OptionTableModel();
        private final JTable optTable = new JTable(optModel);
        private final CardLayout curCards = new CardLayout();
        private final JPanel curPanel = new JPanel(curCards);

        AddItemDialog() {
            super(ShopEditorFrame.this, "➕ Thêm item mới vào Shop", false);   // non-modal → vẫn xem bảng khi thêm
            build();
            // rộng rãi mặc định — co lại nếu màn hình nhỏ
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();
            setSize(Math.min(1120, scr.width - 120), Math.min(660, scr.height - 120));
            setLocationRelativeTo(null);
        }

        private void build() {
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 6, 4, 6);
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            int y = 0;

            for (ShopType t : types) cboTab.addItem(t);
            if (curTab() != null) cboTab.setSelectedItem(curTab());
            addRow(form, c, y++, "Tab shop", cboTab);

            txtSearch.putClientProperty("JTextField.placeholderText", "Gõ tên/id lọc item...");
            txtSearch.addKeyListener(new KeyAdapter() { @Override public void keyReleased(KeyEvent e) { filterItems(); } });
            addRow(form, c, y++, "Tìm item", txtSearch);
            filterItems();
            cboItem.setRenderer(ItemIcons.listRenderer(v -> v instanceof ItemInfo ii ? ii.id() : -1));
            addRow(form, c, y++, "Item bán", cboItem);
            sepRow(form, c, y++);

            ButtonGroup bgroup = new ButtonGroup();
            bgroup.add(rbMoney); bgroup.add(rbItem);
            rbMoney.setOpaque(false); rbItem.setOpaque(false);
            rbMoney.setFocusable(false); rbItem.setFocusable(false);
            JPanel typeRow = new JPanel(new GridLayout(2, 1, 0, 2));
            typeRow.setOpaque(false);
            typeRow.add(rbMoney); typeRow.add(rbItem);
            addRow(form, c, y++, "Loại bán", typeRow);

            JPanel moneyCard = new JPanel(new BorderLayout());
            moneyCard.setOpaque(false);
            cboMoney.setRenderer(ItemIcons.listRenderer(ShopEditorFrame::leadingId));
            moneyCard.add(cboMoney, BorderLayout.CENTER);
            JPanel itemCard = new JPanel(new BorderLayout(6, 0));
            itemCard.setOpaque(false);
            JButton btnPickCur = new JButton("Chọn vật phẩm...");
            btnPickCur.setFocusable(false);
            btnPickCur.addActionListener(e -> {
                ItemInfo sel = pickItemInfo(AddItemDialog.this, "Chọn vật phẩm làm giá");
                if (sel != null) {
                    currencyItemId = sel.id();
                    lblCurItem.setText(sel.id() + " - " + nv(sel.name()));
                    lblCurItem.setIcon(ItemIcons.get(sel.id()));
                }
            });
            itemCard.add(lblCurItem, BorderLayout.CENTER);
            itemCard.add(btnPickCur, BorderLayout.EAST);
            curPanel.add(moneyCard, "money");
            curPanel.add(itemCard, "item");
            rbMoney.addActionListener(e -> curCards.show(curPanel, "money"));
            rbItem.addActionListener(e -> curCards.show(curPanel, "item"));
            addRow(form, c, y++, "Đơn vị giá", curPanel);

            addRow(form, c, y++, "Giá / SL", txtPrice);
            JLabel hint = new JLabel("<html><span style='color:#9a9ab0'>Shop bán tiền: chọn Vàng/Ngọc. "
                    + "Shop đổi đồ: chọn Vật phẩm → item id làm giá.</span></html>");
            hint.setFont(F12);
            c.gridx = 0; c.gridy = y++; c.gridwidth = 2; c.weightx = 1; form.add(hint, c); c.gridwidth = 1;
            sepRow(form, c, y++);

            addRow(form, c, y++, "Slot", spSlot);
            addRow(form, c, y++, "Kỳ giới hạn", cboLimit);
            addRow(form, c, y++, "Lượt/kỳ (0=∞)", spLimit);
            addRow(form, c, y++, "Hành tinh", cboClass);

            JPanel leftWrap = new JPanel(new BorderLayout());
            leftWrap.setOpaque(false);
            leftWrap.add(form, BorderLayout.NORTH);
            leftWrap.setPreferredSize(new Dimension(470, 0));

            // Cột phải — Option (chỉ số)
            JLabel optLbl = new JLabel("<html>Option (chỉ số) — <b>không bắt buộc</b> (ghi vào equip_info):</html>");
            optLbl.setForeground(Theme.TEXT_2);
            optLbl.setFont(F12);
            optTable.setFont(F12); optTable.setRowHeight(30); optTable.getTableHeader().setFont(F12);
            optTable.getColumnModel().getColumn(0).setMaxWidth(44);
            optTable.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && optTable.columnAtPoint(e.getPoint()) <= 1) {
                        int r = optTable.getSelectedRow(); if (r < 0) return;
                        int[] cur = optModel.rowAt(r);
                        int[] res = pickOptionFull(AddItemDialog.this, cur[0], cur[1], cur[2]);
                        if (res != null) optModel.setRow(r, res[0], res[1], res[2]);
                    }
                }
            });
            JPanel optBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            optBtns.setOpaque(false);
            optBtns.add(Theme.tint("＋ Option", GREEN, 34, e -> {
                int[] res = pickOptionFull(AddItemDialog.this, null, 0, 0);
                if (res != null) optModel.addOption(res[0], res[1], res[2]);
            }));
            optBtns.add(Theme.tint("－ Xóa", RED, 34, e -> { int r = optTable.getSelectedRow(); if (r >= 0) optModel.remove(r); }));
            JPanel right = new JPanel(new BorderLayout(0, 8));
            right.setOpaque(false);
            right.add(optLbl, BorderLayout.NORTH);
            right.add(new JScrollPane(optTable), BorderLayout.CENTER);
            right.add(optBtns, BorderLayout.SOUTH);

            JPanel body = new JPanel(new BorderLayout(24, 0));
            body.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
            body.add(leftWrap, BorderLayout.WEST);
            body.add(right, BorderLayout.CENTER);

            JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            south.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SOFT));
            south.add(Theme.ghost("Đóng", 36, e -> dispose()));
            south.add(Theme.primary("✓ Thêm vào Shop", 36, e -> addToShop()));

            JPanel root = new JPanel(new BorderLayout());
            root.add(body, BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);
            setContentPane(root);
        }

        private void filterItems() {
            String q = stripAccent(txtSearch.getText().trim().toLowerCase());
            DefaultComboBoxModel<ItemInfo> m = new DefaultComboBoxModel<>();
            for (ItemInfo ii : itemInfos)
                if (q.isEmpty() || String.valueOf(ii.id()).contains(q) || itemSearchKeys.getOrDefault(ii.id(), "").contains(q))
                    m.addElement(ii);
            cboItem.setModel(m);
            if (m.getSize() > 0) cboItem.setSelectedIndex(0);
        }

        private int moneyKey() {
            String s = String.valueOf(cboMoney.getSelectedItem());
            try { return Integer.parseInt(s.split(" - ")[0].trim()); } catch (Exception e) { return 1; }
        }

        private void addToShop() {
            ShopType tab = (ShopType) cboTab.getSelectedItem();
            ItemInfo item = (ItemInfo) cboItem.getSelectedItem();
            if (tab == null) { warn("Chọn tab shop."); return; }
            if (item == null) { warn("Chọn item bán."); return; }
            int key;
            if (rbItem.isSelected()) {
                if (currencyItemId <= 0) { warn("Bán bằng vật phẩm: bấm 'Chọn vật phẩm...' để chọn item id làm giá."); return; }
                key = currencyItemId;
            } else key = moneyKey();
            long value;
            try { value = Long.parseLong(txtPrice.getText().trim().replace(",", "").replace(".", "")); }
            catch (Exception e) { value = -1; }
            if (value <= 0) { warn("Giá phải là số > 0 (giá rỗng = item FREE trong game)."); return; }

            if (optTable.isEditing() && optTable.getCellEditor() != null) optTable.getCellEditor().stopCellEditing();

            ShopItem it = new ShopItem();
            it.shopTypeId = tab.id;
            it.infoId = item.id();
            JsonArray arr = new JsonArray();
            arr.add(pair(key, value));
            it.priceJson = GSON.toJson(arr);
            it.shopSlot = (Integer) spSlot.getValue();
            it.limitType = cboLimit.getSelectedIndex();
            it.limit = (Integer) spLimit.getValue();
            it.clazz = cboClass.getSelectedIndex() - 1;
            try {
                dao.insertItem(it);
                if (equipDao != null && optModel.getRowCount() > 0) {
                    String buff = optModel.serialize();
                    equipDao.upsertOptions(item.id(), item.name(), buff);
                    var e = equipByInfoId.get(item.id());
                    if (e == null) {
                        e = new com.apex.maptool.db.EquipDao.EquipInfo();
                        e.id = item.id(); e.name = item.name();
                        equipByInfoId.put(item.id(), e);
                    }
                    e.infoBuff = buff;
                }
                // nếu thêm vào đúng tab đang xem → chuyển combo & refill để thấy ngay
                if (curTab() == null || curTab().id != tab.id) cboTabItem.setSelectedItem(tab);
                refillAfterWrite();
                txtPrice.setText("");
                optModel.setFrom("");
                info("Đã thêm '" + item + "' vào tab " + tab + "!\nCó thể thêm item tiếp hoặc bấm Đóng.");
            } catch (Exception ex) { warn("Thêm fail:\n" + rootMsg(ex)); }
        }

        private void addRow(JPanel form, GridBagConstraints c, int y, String label, JComponent field) {
            c.gridwidth = 1;
            c.gridx = 0; c.gridy = y; c.weightx = 0; form.add(lbl(label), c);
            c.gridx = 1; c.weightx = 1; form.add(field, c);
        }
        private void sepRow(JPanel form, GridBagConstraints c, int y) {
            c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.weightx = 1; form.add(new JSeparator(), c); c.gridwidth = 1;
        }
    }

    // ─── UI helpers ─────────────────
    /** Nút tint theo Theme dark+amber (nền màu mờ + viền + chữ màu). */
    private static JButton mkBtn(String text, Color bg, ActionListener a) {
        return Theme.tint(text, bg, 34, a);
    }

    private static JTable makeTable() {
        JTable t = new JTable();
        t.setFont(Theme.font(13, Font.PLAIN));
        t.setRowHeight(33);                                  // dòng thoáng theo demo
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setFont(Theme.font(11, Font.BOLD));
        t.getTableHeader().setForeground(Theme.TEXT_MUTED);
        t.setShowGrid(false);
        t.setShowHorizontalLines(true);
        t.setGridColor(Theme.DIVIDER);
        t.setIntercellSpacing(new Dimension(0, 1));
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

    /** Bọc panel vào scroll DỌC (cửa sổ thấp thì cuộn form, không cắt nút). */
    private static JScrollPane vScroll(JComponent inner) {
        JScrollPane sp = new JScrollPane(inner,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SOFT));
        sp.setPreferredSize(new Dimension(332, 10));   // BorderLayout WEST chỉ dùng width
        sp.getVerticalScrollBar().setUnitIncrement(14);
        sp.getViewport().setBackground(Theme.BG_SURFACE);
        return sp;
    }

    /** Box cảnh báo amber tint (nền amber .08, viền amber). */
    private static JPanel warnBox(JComponent content) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(0x24, 0x1f, 0x17));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x6a, 0x54, 0x22)),
                BorderFactory.createEmptyBorder(9, 12, 9, 12)));
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    /** Renderer ô bảng: căn + màu theo nội dung + font; accentSel=true → chữ vàng khi chọn dòng. */
    private static final class CellR extends DefaultTableCellRenderer {
        private final java.util.function.Function<String, Color> colorFn;
        private final Font font;
        private final boolean accentSel;
        CellR(int align, java.util.function.Function<String, Color> colorFn, Font font, boolean accentSel) {
            setHorizontalAlignment(align);
            this.colorFn = colorFn; this.font = font; this.accentSel = accentSel;
        }
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, foc, r, c);
            if (font != null) setFont(font);
            String s = v == null ? "" : v.toString();
            Color col = colorFn != null ? colorFn.apply(s) : Theme.TEXT;
            if (sel && accentSel) col = Theme.ACCENT_HOVER;
            setForeground(col);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            return this;
        }
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    /** Label form: 14px màu muted (theo handoff). */
    private static JLabel lbl(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.font(13, Font.PLAIN));
        l.setForeground(Theme.TEXT_MUTED);
        return l;
    }

    /** Input/select: cao 40px, chữ 15 (theo handoff 42px). */
    private static void styleInput(JComponent comp) {
        comp.setFont(Theme.font(13, Font.PLAIN));
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
