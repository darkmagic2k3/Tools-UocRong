package com.apex.maptool.ui;

import com.apex.maptool.db.GiftcodeDao;
import com.apex.maptool.db.GiftcodeDao.GiftCode;
import com.apex.maptool.db.ShopDao.ItemInfo;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giftcode Editor — form TRÁI (code + quà + giới hạn) + bảng PHẢI (toàn bộ code trong DB gateway).
 *
 * 2 cách thêm:
 *  - "➕ Thêm 1 code": thêm đúng 1 code (gõ tay hoặc để trống → tự random không trùng).
 *  - "▶ Auto random liên tục": toggle — sinh code random KHÔNG TRÙNG liên tục (~6 code/s)
 *    tới khi bấm ⏹ Dừng. Check trùng qua set code nhớ sẵn + PK của bảng (dup → sinh lại).
 *
 * Sau thêm/xóa tool tự gọi gateway /home/reload (gateway cache code trong RAM).
 */
public final class GiftcodeEditorFrame extends JFrame {

    private static final char[] CODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();
    private static final SecureRandom RND = new SecureRandom();
    private static final int AUTO_DELAY_MS = 150;   // nhịp sinh code auto (~6 code/s, không dội DB)
    private static final String[] TYPES = {"0 - Thường", "1 - Code ngày (daily)", "2 - VIP theo đợt"};
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm");

    private final GiftcodeDao dao;

    // item template cho picker quà
    private final List<ItemInfo> itemInfos = new ArrayList<>();
    private final Map<Integer, String> itemNames = new HashMap<>();
    private final Map<Integer, String> itemSearchKeys = new HashMap<>();

    // ── form trái ──
    private final JTextField txtCode = new JTextField(8);
    private final JTextField txtPrefix = new JTextField(8);
    private final JSpinner spRandLen = Theme.spin(8, 4, 16);
    private final List<long[]> gifts = new ArrayList<>();   // [infoId, soLuong]
    private final GiftTableModel giftModel = new GiftTableModel();
    private final JSpinner spMax = Theme.spin(1, 0, 999_999);
    private final JTextField txtServers = new JTextField("1", 8);
    private final JTextField txtStart = new JTextField(8);
    private final JTextField txtEnd = new JTextField(8);
    private final JComboBox<String> cboType = new JComboBox<>(TYPES);
    private final JSpinner spIndex = Theme.spin(0, 0, 999);

    private JButton btnAdd, btnAuto, btnDelete, btnReloadGw, btnRefresh, btnCopy;
    private final JLabel lblAutoCount = new JLabel(" ");
    private final JLabel lblStatus = new JLabel(" ");

    // ── bảng phải ──
    private final JTextField txtFind = new JTextField(8);
    private JTable tbl;
    private DefaultTableModel model;
    private final List<GiftCode> allRows = new ArrayList<>();
    private final List<GiftCode> viewRows = new ArrayList<>();

    // ── trạng thái auto/trùng ──
    private final Set<String> knownCodes = ConcurrentHashMap.newKeySet();   // EDT + worker cùng đụng
    private final List<String> sessionCodes = new ArrayList<>();            // code tạo trong phiên (copy)
    private SwingWorker<Void, GiftCode> autoWorker;
    private volatile boolean autoStop;
    private int autoCount;

    public GiftcodeEditorFrame(GiftcodeDao dao) {
        super("UR Tools - Giftcode");
        this.dao = dao;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1200, 680);

        long now = System.currentTimeMillis();
        txtStart.setText(FMT.format(new java.util.Date(now)));
        txtEnd.setText(FMT.format(new java.util.Date(now + 7L * 24 * 3600 * 1000)));

        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        p.add(vScroll(buildForm()), BorderLayout.WEST);
        p.add(buildTablePane(), BorderLayout.CENTER);
        add(p, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        loadAll();
    }

    // ═══════════════ FORM TRÁI ═══════════════
    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = gbc();
        int y = 0;

        // Code + nút random 1 phát vào ô
        JPanel codeRow = new JPanel(new BorderLayout(4, 0));
        codeRow.setOpaque(false);
        styleInput(txtCode);
        txtCode.putClientProperty("JTextField.placeholderText", "để trống = tự random");
        codeRow.add(txtCode, BorderLayout.CENTER);
        JButton btnDice = new JButton("🎲");
        btnDice.setToolTipText("Random 1 code vào ô");
        btnDice.setPreferredSize(new Dimension(44, 34));
        btnDice.setFocusPainted(false);
        btnDice.addActionListener(e -> {
            try { txtCode.setText(nextUniqueCode(prefix(), randLen())); }
            catch (IllegalStateException ex) { warn(ex.getMessage()); }
        });
        codeRow.add(btnDice, BorderLayout.EAST);
        addInput(form, c, y++, "Code", codeRow);

        addInput(form, c, y++, "Prefix (auto)", txtPrefix);
        txtPrefix.putClientProperty("JTextField.placeholderText", "vd tet2026- (tùy chọn)");
        addInput(form, c, y++, "Độ dài random", spRandLen);
        addSep(form, c, y++);

        // Quà tặng: bảng nhỏ + nút thêm/xóa
        c.gridx = 0; c.gridy = y++; c.gridwidth = 3; c.weightx = 1;
        JLabel lq = lbl("QUÀ TẶNG (item nhận khi nhập code)");
        lq.setFont(Theme.font(11, Font.BOLD));
        form.add(lq, c);

        JTable tblGift = new JTable(giftModel);
        tblGift.setRowHeight(30);
        tblGift.setShowGrid(false);
        tblGift.setShowHorizontalLines(true);
        tblGift.setGridColor(Theme.DIVIDER);
        tblGift.getColumnModel().getColumn(0).setPreferredWidth(190);
        tblGift.getColumnModel().getColumn(1).setMaxWidth(78);
        tblGift.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int col) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, col);
                setIcon(r < gifts.size() ? ItemIcons.get((int) gifts.get(r)[0]) : null);
                setIconTextGap(6);
                return this;
            }
        });
        JScrollPane spGift = new JScrollPane(tblGift);
        spGift.setPreferredSize(new Dimension(10, 122));
        c.gridy = y++;
        form.add(spGift, c);

        c.gridy = y++;
        JPanel giftBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        giftBtns.setOpaque(false);
        giftBtns.add(mkBtn("＋ Thêm quà", Theme.GREEN, e -> addGift()));
        giftBtns.add(mkBtn("－ Bỏ quà", Theme.RED, e -> {
            int r = tblGift.getSelectedRow();
            if (r >= 0 && r < gifts.size()) { gifts.remove(r); giftModel.fireTableDataChanged(); }
        }));
        form.add(giftBtns, c);
        addSep(form, c, y++);

        addInput(form, c, y++, "Lượt dùng max", spMax);
        ((JSpinner.DefaultEditor) spMax.getEditor()).getTextField().setToolTipText("0 = không giới hạn lượt");
        addInput(form, c, y++, "Server", txtServers);
        txtServers.setToolTipText("'1' hoặc '1,2' — để trống = mọi server");
        addInput(form, c, y++, "Bắt đầu", txtStart);
        addInput(form, c, y++, "Hết hạn", txtEnd);
        txtStart.setToolTipText("yyyy-MM-dd HH:mm — để trống = ngay bây giờ");
        txtEnd.setToolTipText("yyyy-MM-dd HH:mm — để trống = 2037-01-01 (coi như vĩnh viễn)");
        addInput(form, c, y++, "Loại code", cboType);
        addInput(form, c, y++, "Đợt (index)", spIndex);
        addSep(form, c, y++);

        // Hành động chính
        c.gridx = 0; c.gridy = y++; c.gridwidth = 3;
        btnAdd = Theme.primary("➕ Thêm 1 code", 38, e -> addOne());
        form.add(btnAdd, c);

        c.gridy = y++;
        btnAuto = mkBtn("▶ Auto random liên tục", Theme.GREEN, e -> toggleAuto());
        btnAuto.setPreferredSize(new Dimension(10, 38));
        form.add(btnAuto, c);

        c.gridy = y++;
        lblAutoCount.setForeground(Theme.TEXT_MUTED);
        lblAutoCount.setFont(Theme.font(12, Font.PLAIN));
        form.add(lblAutoCount, c);

        c.gridy = y++;
        btnCopy = mkBtn("📋 Copy code đã tạo (phiên này)", Theme.PURPLE, e -> copySession());
        form.add(btnCopy, c);
        addSep(form, c, y++);

        c.gridx = 0; c.gridy = y++; c.gridwidth = 3;   // addSep đã reset gridwidth=1 — không set lại là hàng này rơi vào cột label
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        btnReloadGw = mkBtn("🔄 Reload GW", Theme.BLUE, e -> reloadGatewayAsync(true));
        btnDelete = mkBtn("🗑 Xóa", Theme.RED, e -> deleteSelected());
        btnRefresh = mkBtn("↻ Tải lại", Theme.ACCENT, e -> loadAll());
        row.add(btnReloadGw);
        row.add(btnDelete);
        row.add(btnRefresh);
        form.add(row, c);

        c.gridy = y;
        JLabel note = new JLabel("<html>⚠ Gateway cache code trong RAM —<br>tool tự reload sau khi thêm/xóa.</html>");
        note.setForeground(Theme.TEXT_MUTED);
        note.setFont(Theme.font(12, Font.PLAIN));
        form.add(note, c);

        JPanel left = new JPanel(new BorderLayout());
        left.add(form, BorderLayout.NORTH);
        return left;
    }

    /** Model bảng quà: cột SL sửa trực tiếp. */
    private final class GiftTableModel extends DefaultTableModel {
        GiftTableModel() { super(new String[]{"Item", "SL"}, 0); }
        @Override public int getRowCount() { return gifts == null ? 0 : gifts.size(); }
        @Override public boolean isCellEditable(int r, int c) { return c == 1; }
        @Override public Object getValueAt(int r, int c) {
            long[] g = gifts.get(r);
            return c == 0 ? g[0] + " — " + itemNames.getOrDefault((int) g[0], "?") : g[1];
        }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c != 1) return;
            try {
                long q = Long.parseLong(String.valueOf(v).trim());
                if (q >= 1) gifts.get(r)[1] = q;
            } catch (NumberFormatException ignored) {}
            fireTableRowsUpdated(r, r);
        }
    }

    private void addGift() {
        if (itemInfos.isEmpty()) { warn("Chưa load được danh sách item. Bấm ↻ Tải lại rồi thử."); return; }
        ItemInfo ii = pickItemInfo(this, "Chọn item quà");
        if (ii == null) return;
        for (long[] g : gifts)
            if (g[0] == ii.id()) { warn("Item đã có trong danh sách quà — sửa SL trực tiếp."); return; }
        gifts.add(new long[]{ii.id(), 1});
        giftModel.fireTableDataChanged();
    }

    /** Picker chọn 1 item (search + list icon) — same pattern ShopEditorFrame. */
    private ItemInfo pickItemInfo(Component parent, String title) {
        DefaultListModel<ItemInfo> lm = new DefaultListModel<>();
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", "Tìm tên/id...");
        Runnable refill = () -> {
            String q = stripAccent(search.getText().trim());
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

    // ═══════════════ BẢNG PHẢI ═══════════════
    private JComponent buildTablePane() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);

        styleInput(txtFind);
        txtFind.putClientProperty("JTextField.placeholderText", "Tìm code...");
        txtFind.addKeyListener(new KeyAdapter() { @Override public void keyReleased(KeyEvent e) { refillTable(); } });
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(txtFind, BorderLayout.CENTER);
        p.add(top, BorderLayout.NORTH);

        tbl = new JTable();
        tbl.setFont(Theme.font(13, Font.PLAIN));
        tbl.setRowHeight(33);
        tbl.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tbl.getTableHeader().setFont(Theme.font(11, Font.BOLD));
        tbl.getTableHeader().setForeground(Theme.TEXT_MUTED);
        tbl.setShowGrid(false);
        tbl.setShowHorizontalLines(true);
        tbl.setGridColor(Theme.DIVIDER);
        tbl.setIntercellSpacing(new Dimension(0, 1));
        model = new DefaultTableModel(new String[]{"Code", "Quà", "Dùng/Max", "Server", "Bắt đầu", "Hết hạn", "Loại"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        tbl.setModel(model);
        tbl.getColumnModel().getColumn(0).setPreferredWidth(150);
        tbl.getColumnModel().getColumn(1).setPreferredWidth(310);
        tbl.getColumnModel().getColumn(2).setMaxWidth(90);
        tbl.getColumnModel().getColumn(3).setMaxWidth(70);
        tbl.getColumnModel().getColumn(4).setPreferredWidth(120);
        tbl.getColumnModel().getColumn(5).setPreferredWidth(120);
        tbl.getColumnModel().getColumn(6).setMaxWidth(70);
        DefaultTableCellRenderer codeR = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                setFont(Theme.font(13, Font.BOLD));
                setForeground(sel ? Theme.ACCENT_HOVER : Theme.ACCENT);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                return this;
            }
        };
        tbl.getColumnModel().getColumn(0).setCellRenderer(codeR);

        p.add(new JScrollPane(tbl), BorderLayout.CENTER);
        return p;
    }

    private JComponent buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.BG_SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        JLabel hint = new JLabel("⚠ Code ghi vào nro_gateway.gift_code — gateway cache RAM nên tool tự gọi /home/reload sau thêm/xóa");
        hint.setForeground(new Color(0xe0, 0xc9, 0x8a));
        hint.setFont(Theme.font(12, Font.PLAIN));
        bar.add(hint, BorderLayout.WEST);
        lblStatus.setFont(Theme.font(12, Font.BOLD));
        lblStatus.setForeground(Theme.TEXT_MUTED);
        bar.add(lblStatus, BorderLayout.EAST);
        return bar;
    }

    // ═══════════════ LOAD ═══════════════
    private void loadAll() {
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        btnRefresh.setEnabled(false);
        new SwingWorker<Object[], Void>() {
            @Override protected Object[] doInBackground() throws Exception {
                List<GiftCode> codes = dao.list();
                List<ItemInfo> items = itemInfos.isEmpty() ? dao.itemInfos() : null;
                return new Object[]{codes, items};
            }
            @SuppressWarnings("unchecked")
            @Override protected void done() {
                setCursor(Cursor.getDefaultCursor());
                btnRefresh.setEnabled(true);
                try {
                    Object[] r = get();
                    List<GiftCode> codes = (List<GiftCode>) r[0];
                    List<ItemInfo> items = (List<ItemInfo>) r[1];
                    if (items != null) {
                        itemInfos.clear();
                        itemInfos.addAll(items);
                        for (ItemInfo ii : items) {
                            itemNames.put(ii.id(), ii.name());
                            itemSearchKeys.put(ii.id(), stripAccent(ii.name() == null ? "" : ii.name()));
                        }
                    }
                    allRows.clear();
                    allRows.addAll(codes);
                    knownCodes.clear();
                    for (GiftCode g : codes) knownCodes.add(g.code);
                    refillTable();
                    status("✔ " + codes.size() + " code", Theme.GREEN);
                } catch (Exception ex) {
                    warn("Load giftcode fail:\n" + ex.getMessage());
                }
            }
        }.execute();
    }

    private void refillTable() {
        String q = txtFind.getText().trim().toLowerCase();
        model.setRowCount(0);
        viewRows.clear();
        for (GiftCode g : allRows) {
            if (!q.isEmpty() && !g.code.toLowerCase().contains(q)) continue;
            viewRows.add(g);
            model.addRow(rowOf(g));
        }
    }

    /** Chèn code mới lên ĐẦU bảng không rebuild 19k dòng (auto mode gọi liên tục). */
    private void prependRow(GiftCode g) {
        allRows.add(0, g);
        String q = txtFind.getText().trim().toLowerCase();
        if (q.isEmpty() || g.code.toLowerCase().contains(q)) {
            viewRows.add(0, g);
            model.insertRow(0, rowOf(g));
        }
    }

    private Object[] rowOf(GiftCode g) {
        return new Object[]{
                g.code,
                giftLabel(g.listGiftStr),
                g.used() + " / " + (g.max <= 0 ? "∞" : g.max),
                g.listServerStr == null || g.listServerStr.isBlank() ? "Tất cả" : g.listServerStr,
                g.timeStart == null ? "-" : FMT.format(g.timeStart),
                g.timeEnd == null ? "-" : FMT.format(g.timeEnd),
                g.typeCode == 1 ? "Ngày" : g.typeCode == 2 ? "VIP" : "Thường"};
    }

    /** "259-99;260-99" → "Đá x99; Ngọc x99" (id không tên → giữ id). */
    private String giftLabel(String s) {
        if (s == null || s.isBlank()) return "-";
        StringBuilder sb = new StringBuilder();
        for (String part : s.split(";")) {
            String[] kv = part.split("-");
            if (kv.length != 2) continue;
            if (sb.length() > 0) sb.append("; ");
            String name = null;
            try { name = itemNames.get(Integer.parseInt(kv[0])); } catch (NumberFormatException ignored) {}
            sb.append(name != null && !name.isBlank() ? name : ("#" + kv[0])).append(" x").append(kv[1]);
        }
        return sb.length() == 0 ? s : sb.toString();
    }

    // ═══════════════ THÊM 1 CODE ═══════════════
    private void addOne() {
        GiftCode g;
        try { g = fromForm(); } catch (IllegalArgumentException ex) { warn(ex.getMessage()); return; }
        String manual = txtCode.getText().trim().toLowerCase();
        if (!manual.isEmpty() && !manual.matches("[a-z0-9_-]{2,20}")) {
            warn("Code chỉ gồm chữ thường/số/gạch, 2-20 ký tự (cột DB varchar(20)): " + manual);
            return;
        }
        try {
            if (manual.isEmpty()) {
                g.code = nextUniqueCode(prefix(), randLen());
                while (!dao.tryInsert(g)) {          // PK trùng (code có sẵn dưới DB) → sinh code khác
                    knownCodes.add(g.code);
                    g.code = nextUniqueCode(prefix(), randLen());
                }
            } else {
                g.code = manual;
                if (knownCodes.contains(g.code) || !dao.tryInsert(g)) {
                    warn("Code đã tồn tại: " + g.code);
                    return;
                }
            }
        } catch (Exception ex) {
            warn("Thêm code fail:\n" + ex.getMessage());
            return;
        }
        knownCodes.add(g.code);
        sessionCodes.add(g.code);
        prependRow(g);
        txtCode.setText("");
        copyToClipboard(g.code);
        status("✔ Đã thêm " + g.code + " (đã copy)", Theme.GREEN);
        reloadGatewayAsync(false);
    }

    // ═══════════════ AUTO RANDOM LIÊN TỤC ═══════════════
    private void toggleAuto() {
        if (autoWorker != null) {           // đang chạy → dừng
            autoStop = true;
            btnAuto.setEnabled(false);      // chờ worker thoát (done() bật lại)
            return;
        }
        final GiftCode proto;
        try { proto = fromForm(); } catch (IllegalArgumentException ex) { warn(ex.getMessage()); return; }
        final String pre = prefix();
        final int len = randLen();
        if (pre.length() + len > 20) {
            warn("Prefix + độ dài random vượt 20 ký tự (cột code varchar(20)) — rút ngắn prefix hoặc giảm độ dài.");
            return;
        }
        autoStop = false;
        autoCount = 0;
        setAutoUi(true);
        autoWorker = new SwingWorker<>() {
            private Exception err;
            @Override protected Void doInBackground() {
                while (!autoStop) {
                    try {
                        GiftCode g = proto.copyNoCode();
                        g.code = nextUniqueCode(pre, len);
                        knownCodes.add(g.code);
                        if (dao.tryInsert(g)) publish(g);
                        Thread.sleep(AUTO_DELAY_MS);
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Exception ex) {
                        err = ex;
                        break;
                    }
                }
                return null;
            }
            @Override protected void process(List<GiftCode> chunk) {
                for (GiftCode g : chunk) {
                    sessionCodes.add(g.code);
                    prependRow(g);
                }
                autoCount += chunk.size();
                lblAutoCount.setText("Đã tạo: " + autoCount + " code");
            }
            @Override protected void done() {
                autoWorker = null;
                setAutoUi(false);
                if (err != null) warn("Auto dừng vì lỗi:\n" + err.getMessage());
                status("⏹ Auto dừng — đã tạo " + autoCount + " code", Theme.ACCENT);
                if (autoCount > 0) reloadGatewayAsync(false);
            }
        };
        autoWorker.execute();
    }

    private void setAutoUi(boolean running) {
        btnAuto.setText(running ? "⏹ Dừng auto" : "▶ Auto random liên tục");
        btnAuto.setEnabled(true);
        btnAdd.setEnabled(!running);
        btnDelete.setEnabled(!running);
        btnRefresh.setEnabled(!running);
        btnReloadGw.setEnabled(!running);
        if (running) {
            lblAutoCount.setText("Đã tạo: 0 code");
            status("▶ Đang sinh code random...", Theme.GREEN);
        }
    }

    // ═══════════════ XÓA / COPY / RELOAD GATEWAY ═══════════════
    private void deleteSelected() {
        int[] rows = tbl.getSelectedRows();
        if (rows.length == 0) { warn("Chọn code trong bảng để xóa."); return; }
        List<GiftCode> del = new ArrayList<>();
        for (int r : rows) if (r < viewRows.size()) del.add(viewRows.get(r));
        int ok = JOptionPane.showConfirmDialog(this,
                "Xóa " + del.size() + " code khỏi DB gateway?\n(người chơi sẽ không nhập được nữa)",
                "Xóa giftcode", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        try {
            for (GiftCode g : del) {
                dao.delete(g.code);
                knownCodes.remove(g.code);
                allRows.remove(g);
            }
        } catch (Exception ex) {
            warn("Xóa fail:\n" + ex.getMessage());
        }
        refillTable();
        status("🗑 Đã xóa " + del.size() + " code", Theme.RED);
        reloadGatewayAsync(false);
    }

    private void copySession() {
        if (sessionCodes.isEmpty()) { warn("Phiên này chưa tạo code nào."); return; }
        copyToClipboard(String.join("\n", sessionCodes));
        info("Đã copy " + sessionCodes.size() + " code vào clipboard (mỗi dòng 1 code).");
    }

    /** Gọi gateway /home/reload nền. showDialog=true (bấm tay) → báo kết quả bằng dialog. */
    private void reloadGatewayAsync(boolean showDialog) {
        status("🔄 Reload gateway...", Theme.BLUE);
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception { return dao.reloadGateway(); }
            @Override protected void done() {
                try {
                    get();
                    status("✔ Gateway đã nhận code mới", Theme.GREEN);
                    if (showDialog) info("Gateway reload OK — code mới dùng được ngay.");
                } catch (Exception ex) {
                    String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    status("✘ Reload gateway fail", Theme.RED);
                    if (showDialog) warn("Reload gateway fail:\n" + msg);
                    else System.err.println("[Giftcode] reload gateway fail: " + msg);
                }
            }
        }.execute();
    }

    // ═══════════════ FORM → MODEL ═══════════════
    /** Đọc form thành GiftCode (chưa gán code). Sai input → IllegalArgumentException tiếng Việt. */
    private GiftCode fromForm() {
        GiftCode g = new GiftCode();
        if (gifts.isEmpty()) throw new IllegalArgumentException("Chưa chọn quà — bấm '＋ Thêm quà'.");
        StringBuilder sb = new StringBuilder();
        for (long[] it : gifts) {
            if (sb.length() > 0) sb.append(';');
            sb.append(it[0]).append('-').append(it[1]);
        }
        if (sb.length() > 255) throw new IllegalArgumentException("Danh sách quà quá dài (>255 ký tự) — bớt item.");
        g.listGiftStr = sb.toString();
        g.listPlayerIdStr = "";
        String sv = txtServers.getText().trim();
        if (!sv.isEmpty() && !sv.matches("\\d+(,\\d+)*"))
            throw new IllegalArgumentException("Server sai format — nhập '1' hoặc '1,2' (trống = mọi server).");
        g.listServerStr = sv;
        g.max = Theme.spinInt(spMax);
        // cột timestamp NOT NULL: trống → mặc định (bắt đầu = bây giờ, hết hạn = 2037 ~ vĩnh viễn)
        g.timeStart = parseTs(txtStart.getText(), "Bắt đầu");
        if (g.timeStart == null) g.timeStart = new java.sql.Timestamp(System.currentTimeMillis());
        g.timeEnd = parseTs(txtEnd.getText(), "Hết hạn");
        if (g.timeEnd == null) g.timeEnd = java.sql.Timestamp.valueOf("2037-01-01 00:00:00");
        if (g.timeEnd.before(g.timeStart))
            throw new IllegalArgumentException("'Hết hạn' đứng trước 'Bắt đầu'.");
        g.typeCode = cboType.getSelectedIndex();
        g.indexCode = Theme.spinInt(spIndex);
        return g;
    }

    private static java.sql.Timestamp parseTs(String s, String label) {
        s = s.trim();
        if (s.isEmpty()) return null;
        for (String f : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"}) {
            SimpleDateFormat df = new SimpleDateFormat(f);
            df.setLenient(false);
            try { return new java.sql.Timestamp(df.parse(s).getTime()); } catch (ParseException ignored) {}
        }
        throw new IllegalArgumentException("'" + label + "' sai format — dùng yyyy-MM-dd HH:mm (vd 2026-07-20 00:00).");
    }

    // ═══════════════ RANDOM CODE ═══════════════
    private String prefix() { return txtPrefix.getText().trim().toLowerCase(); }
    private int randLen() { return Theme.spinInt(spRandLen); }

    /** Sinh code prefix+random chưa có trong knownCodes (nhớ cả code DB + code vừa tạo). */
    private String nextUniqueCode(String prefix, int len) {
        if (prefix.length() + len > 20)
            throw new IllegalStateException("Prefix + độ dài random vượt 20 ký tự (cột code varchar(20)) — rút ngắn prefix hoặc giảm độ dài.");
        for (int tries = 0; tries < 2000; tries++) {
            StringBuilder sb = new StringBuilder(prefix);
            for (int i = 0; i < len; i++) sb.append(CODE_CHARS[RND.nextInt(CODE_CHARS.length)]);
            String c = sb.toString();
            if (!knownCodes.contains(c)) return c;
        }
        throw new IllegalStateException("Không sinh được code không trùng — tăng 'Độ dài random'.");
    }

    // ═══════════════ helpers ═══════════════
    private static JButton mkBtn(String text, Color bg, java.awt.event.ActionListener a) {
        return Theme.tint(text, bg, 34, a);
    }

    private static JScrollPane vScroll(JComponent inner) {
        JScrollPane sp = new JScrollPane(inner,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SOFT));
        sp.setPreferredSize(new Dimension(332, 10));
        sp.getVerticalScrollBar().setUnitIncrement(14);
        sp.getViewport().setBackground(Theme.BG_SURFACE);
        return sp;
    }

    private static GridBagConstraints gbc() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 8, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static JLabel lbl(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Theme.font(13, Font.PLAIN));
        l.setForeground(Theme.TEXT_MUTED);
        return l;
    }

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

    private static String stripAccent(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('đ', 'd').replace('Đ', 'D').toLowerCase();
    }

    private static void copyToClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    private void status(String msg, Color c) {
        lblStatus.setText(msg);
        lblStatus.setForeground(c);
    }

    private void info(String msg) { JOptionPane.showMessageDialog(this, msg, "UR Tools - Giftcode", JOptionPane.INFORMATION_MESSAGE); }
    private void warn(String msg) { JOptionPane.showMessageDialog(this, msg, "UR Tools - Giftcode", JOptionPane.WARNING_MESSAGE); }
}
