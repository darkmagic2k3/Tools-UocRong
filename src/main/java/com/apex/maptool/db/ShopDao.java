package com.apex.maptool.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Đọc/ghi shop: shop_type_config (tab) + shop_item_config (item trong tab).
 * Ghi NGAY từng thao tác, backup 2 bảng trước mỗi lần ghi, bump version_tracker sau ghi.
 *
 * ⚠ KHÔNG hỗ trợ tạo/xóa shop_type — id gắn cứng enum TypeShop client (1-18).
 * ⚠ `limit` là keyword MySQL → luôn backtick.
 */
public final class ShopDao {
    private static final Gson GSON = new Gson();
    private final Db db;

    public ShopDao(Db db) { this.db = db; }

    public Db db() { return db; }

    // ─── models ────────────────────────────────────────────────
    public static final class ShopType {
        public int id;
        public String shopName;
        public int missionReq;
        public String npcsJson;   // JSON array int "[2,26]"
        @Override public String toString() { return id + " — " + (shopName == null ? "?" : shopName); }
    }

    public static final class ShopItem {
        public int id;            // PK (read-only)
        public int shopTypeId;
        public int shopSlot;
        public int clazz;         // -1 tất cả / 0 TĐ / 1 Namek / 2 Saiyan
        public int infoId;
        public int limitType;     // 0 không / 1 ngày / 2 tuần / 3 tháng
        public int limit;
        public String priceJson;  // [{"key":1,"value":1000}]
    }

    public record ItemInfo(int id, String name) {
        @Override public String toString() { return id + " — " + (name == null || name.isBlank() ? "(no name)" : name); }
    }

    // ─── load ──────────────────────────────────────────────────
    public List<ShopType> types() throws SQLException {
        List<ShopType> out = new ArrayList<>();
        String sql = "SELECT id, shop_name, mission_req, npcs FROM shop_type_config ORDER BY id";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ShopType t = new ShopType();
                t.id = rs.getInt("id");
                t.shopName = rs.getString("shop_name");
                t.missionReq = rs.getInt("mission_req");
                t.npcsJson = rs.getString("npcs");
                out.add(t);
            }
        }
        return out;
    }

    public List<ShopItem> items(int shopTypeId) throws SQLException {
        List<ShopItem> out = new ArrayList<>();
        String sql = "SELECT ID, shop_type_id, shop_slot, `class`, info_id, limit_type, `limit`, price"
                + " FROM shop_item_config WHERE shop_type_id=? ORDER BY shop_slot, ID";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, shopTypeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ShopItem it = new ShopItem();
                    it.id = rs.getInt("ID");
                    it.shopTypeId = rs.getInt("shop_type_id");
                    it.shopSlot = rs.getInt("shop_slot");
                    it.clazz = rs.getInt("class");
                    it.infoId = rs.getInt("info_id");
                    it.limitType = rs.getInt("limit_type");
                    it.limit = rs.getInt("limit");
                    it.priceJson = rs.getString("price");
                    out.add(it);
                }
            }
        }
        return out;
    }

    /** Template item cho picker (id + tên). */
    public List<ItemInfo> itemInfos() throws SQLException {
        List<ItemInfo> out = new ArrayList<>();
        String sql = "SELECT id, name FROM item_info_config ORDER BY id";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(new ItemInfo(rs.getInt("id"), rs.getString("name")));
        }
        return out;
    }

    /** Số item mỗi tab (1 query). */
    public java.util.Map<Integer, Integer> itemCounts() throws SQLException {
        java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
        String sql = "SELECT shop_type_id, COUNT(*) n FROM shop_item_config GROUP BY shop_type_id";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.put(rs.getInt("shop_type_id"), rs.getInt("n"));
        }
        return out;
    }

    public boolean itemInfoExists(int infoId) throws SQLException {
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM item_info_config WHERE id=?")) {
            ps.setInt(1, infoId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    // ─── ghi (backup trước, bump version sau) ──────────────────
    /** Sửa tab: shop_name + mission_req + npcs (validate JSON array trước khi gọi). */
    public void updateType(ShopType t) throws SQLException {
        backupShopTables();
        String sql = "UPDATE shop_type_config SET shop_name=?, mission_req=?, npcs=?, updated_at=NOW() WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.shopName);
            ps.setInt(2, t.missionReq);
            ps.setString(3, t.npcsJson);
            ps.setInt(4, t.id);
            System.out.println("[ShopDao] updateType " + t.id + " rows=" + ps.executeUpdate());
        }
        bumpVersion("shop_type_config");
    }

    /** Thêm item mới vào tab. Trả ID sinh ra. */
    public int insertItem(ShopItem it) throws SQLException {
        validate(it);
        backupShopTables();
        String sql = "INSERT INTO shop_item_config (shop_type_id, shop_slot, `class`, info_id, limit_type, `limit`, price, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,NOW(),NOW())";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            fill(ps, it);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                int id = rs.next() ? rs.getInt(1) : -1;
                System.out.println("[ShopDao] insertItem → ID=" + id);
                bumpVersion("shop_item_config");
                return id;
            }
        }
    }

    public void updateItem(ShopItem it) throws SQLException {
        validate(it);
        backupShopTables();
        String sql = "UPDATE shop_item_config SET shop_type_id=?, shop_slot=?, `class`=?, info_id=?, limit_type=?, `limit`=?, price=?, updated_at=NOW() WHERE ID=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            fill(ps, it);
            ps.setInt(8, it.id);
            System.out.println("[ShopDao] updateItem " + it.id + " rows=" + ps.executeUpdate());
        }
        bumpVersion("shop_item_config");
    }

    public void deleteItem(int id) throws SQLException {
        backupShopTables();
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement("DELETE FROM shop_item_config WHERE ID=?")) {
            ps.setInt(1, id);
            System.out.println("[ShopDao] deleteItem " + id + " rows=" + ps.executeUpdate());
        }
        bumpVersion("shop_item_config");
    }

    private static void fill(PreparedStatement ps, ShopItem it) throws SQLException {
        ps.setInt(1, it.shopTypeId);
        ps.setInt(2, it.shopSlot);
        ps.setInt(3, it.clazz);
        ps.setInt(4, it.infoId);
        ps.setInt(5, it.limitType);
        ps.setInt(6, it.limit);
        ps.setString(7, it.priceJson);
    }

    /** Giá hỏng/rỗng = item FREE trong game → chặn cứng tại đây. */
    private static void validate(ShopItem it) throws SQLException {
        JsonArray arr;
        try {
            arr = GSON.fromJson(it.priceJson, JsonArray.class);
        } catch (Exception e) {
            throw new SQLException("price không phải JSON hợp lệ: " + it.priceJson);
        }
        if (arr == null || arr.isEmpty())
            throw new SQLException("price rỗng → item sẽ FREE trong game. Cần ≥1 cặp {key,value}.");
        for (var e : arr) {
            if (!e.isJsonObject()) throw new SQLException("price phần tử không phải object: " + e);
            JsonObject o = e.getAsJsonObject();
            if (!o.has("key") || !o.has("value")) throw new SQLException("price thiếu key/value: " + o);
            if (o.get("value").getAsLong() <= 0) throw new SQLException("price value phải > 0: " + o);
        }
    }

    /** Dump 2 bảng shop ra backup/shop_{ts}.bak.json trước khi ghi. */
    private void backupShopTables() {
        try (Connection c = db.open()) {
            JsonObject root = new JsonObject();
            for (String table : new String[]{"shop_type_config", "shop_item_config"}) {
                JsonArray rows = new JsonArray();
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        JsonObject o = new JsonObject();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            Object v = rs.getObject(i);
                            o.addProperty(md.getColumnLabel(i), v == null ? null : v.toString());
                        }
                        rows.add(o);
                    }
                }
                root.add(table, rows);
            }
            Path dir = Paths.get("backup");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path f = dir.resolve("shop_" + System.currentTimeMillis() + ".bak.json");
            Files.writeString(f, GSON.toJson(root));
            System.out.println("[ShopDao] backup → " + f.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ShopDao] backup fail: " + e.getMessage());
        }
    }

    /** Báo client config đổi (client so version để tải lại). */
    private void bumpVersion(String table) {
        String sql = "INSERT INTO version_tracker (table_name, version, last_op, last_changed_at) VALUES (?,?,?,NOW())"
                + " ON DUPLICATE KEY UPDATE version=VALUES(version), last_op=VALUES(last_op), last_changed_at=NOW()";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setLong(2, System.currentTimeMillis() / 1000);
            ps.setString(3, "map-editor-tool");
            ps.executeUpdate();
            System.out.println("[ShopDao] bump version " + table);
        } catch (Exception e) {
            System.err.println("[ShopDao] bump version fail (" + table + "): " + e.getMessage());
        }
    }
}
