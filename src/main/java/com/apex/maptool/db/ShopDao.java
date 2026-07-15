package com.apex.maptool.db;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Đọc/ghi shop từ 1 BẢNG duy nhất {@code shop} (server đã gộp: mỗi dòng = 1 tab shop,
 * cột {@code items} = JSON mảng item của tab, cột {@code npcs} = JSON mảng id NPC).
 *
 * Bảng cũ shop_type_config + shop_item_config server giữ làm backup, KHÔNG còn dùng để load.
 * Server parse cột items theo entity ShopItemJson: {@code id, infoId, shopSlot, clazz,
 * limitType, limit, name, prices[{key,value}]}.
 *
 * Public API (ShopType/ShopItem + CRUD) giữ nguyên để {@code ShopEditorFrame} không đổi.
 * Item không còn PK auto-increment: id nằm trong JSON, tool tự sinh id (max toàn shop +1).
 *
 * ⚠ KHÔNG hỗ trợ tạo/xóa tab — id gắn cứng enum TypeShop client.
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
        public int id;            // id item trong JSON (client gửi khi mua)
        public int shopTypeId;    // = shop.id chứa item
        public int shopSlot;
        public int clazz;         // -1 tất cả / 0 TĐ / 1 Namek / 2 Saiyan
        public int infoId;
        public int limitType;     // 0 không / 1 ngày / 2 tuần / 3 tháng
        public int limit;
        public String priceJson;  // [{"key":1,"value":1000}]
        public String name;       // tên item lưu kèm cho dễ đọc (server bỏ qua)
    }

    public record ItemInfo(int id, String name) {
        @Override public String toString() { return id + " — " + (name == null || name.isBlank() ? "(no name)" : name); }
    }

    // ─── load ──────────────────────────────────────────────────
    public List<ShopType> types() throws SQLException {
        List<ShopType> out = new ArrayList<>();
        String sql = "SELECT id, shop_name, mission_req, npcs FROM shop ORDER BY id";
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
        List<ShopItem> out;
        try (Connection c = db.open()) {
            out = parseItems(shopTypeId, rawItems(c, shopTypeId));
        }
        out.sort(Comparator.comparingInt((ShopItem s) -> s.shopSlot).thenComparingInt(s -> s.id));
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

    /** Số item mỗi tab (1 query, đếm phần tử JSON của cột items). */
    public java.util.Map<Integer, Integer> itemCounts() throws SQLException {
        java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
        String sql = "SELECT id, items FROM shop";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.put(rs.getInt("id"), safeArray(rs.getString("items")).size());
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
        String sql = "UPDATE shop SET shop_name=?, mission_req=?, npcs=?, updated_at=NOW() WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, t.shopName);
            ps.setInt(2, t.missionReq);
            ps.setString(3, t.npcsJson);
            ps.setInt(4, t.id);
            System.out.println("[ShopDao] updateType " + t.id + " rows=" + ps.executeUpdate());
        }
        bumpVersion("shop");
    }

    /** Thêm item mới vào cột items của tab. Trả id sinh ra (max toàn shop + 1). */
    public int insertItem(ShopItem it) throws SQLException {
        validate(it);
        backupShopTables();
        try (Connection c = db.open()) {
            JsonArray items = readItemsArray(c, it.shopTypeId);
            it.id = nextItemId(c);
            items.add(toJson(it, itemName(c, it.infoId)));
            writeItems(c, it.shopTypeId, items);
            System.out.println("[ShopDao] insertItem → ID=" + it.id + " tab=" + it.shopTypeId);
        }
        bumpVersion("shop");
        return it.id;
    }

    /** Sửa item trong cột items của tab (tìm theo id). */
    public void updateItem(ShopItem it) throws SQLException {
        validate(it);
        backupShopTables();
        try (Connection c = db.open()) {
            JsonArray items = readItemsArray(c, it.shopTypeId);
            boolean found = false;
            for (int i = 0; i < items.size(); i++) {
                if (!items.get(i).isJsonObject()) continue;
                if (optInt(items.get(i).getAsJsonObject(), "id", -1) == it.id) {
                    items.set(i, toJson(it, itemName(c, it.infoId)));
                    found = true;
                    break;
                }
            }
            if (!found) throw new SQLException("Không tìm thấy item ID=" + it.id + " trong tab " + it.shopTypeId);
            writeItems(c, it.shopTypeId, items);
            System.out.println("[ShopDao] updateItem " + it.id + " tab=" + it.shopTypeId);
        }
        bumpVersion("shop");
    }

    /** Xóa item khỏi shop chứa nó (id item là duy nhất toàn shop → dò mọi tab). */
    public void deleteItem(int id) throws SQLException {
        backupShopTables();
        try (Connection c = db.open()) {
            int shopId = -1;
            JsonArray items = null;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT id, items FROM shop")) {
                while (rs.next()) {
                    JsonArray a = safeArray(rs.getString("items"));
                    for (JsonElement e : a) {
                        if (e.isJsonObject() && optInt(e.getAsJsonObject(), "id", -1) == id) {
                            shopId = rs.getInt("id");
                            items = a;
                            break;
                        }
                    }
                    if (shopId >= 0) break;
                }
            }
            if (shopId < 0) throw new SQLException("Không tìm thấy item ID=" + id + " trong bất kỳ tab nào.");
            JsonArray kept = new JsonArray();
            for (JsonElement e : items)
                if (!(e.isJsonObject() && optInt(e.getAsJsonObject(), "id", -1) == id)) kept.add(e);
            writeItems(c, shopId, kept);
            System.out.println("[ShopDao] deleteItem " + id + " tab=" + shopId + " rows=" + (items.size() - kept.size()));
        }
        bumpVersion("shop");
    }

    // ─── JSON items helpers ────────────────────────────────────
    private static int optInt(JsonObject o, String key, int def) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() ? e.getAsInt() : def;
    }

    private static String optStr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() ? e.getAsString() : null;
    }

    /** Parse cột items JSON → list ShopItem (bỏ qua phần tử hỏng). */
    private static List<ShopItem> parseItems(int shopId, String itemsJson) {
        List<ShopItem> out = new ArrayList<>();
        for (JsonElement e : safeArray(itemsJson)) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            ShopItem it = new ShopItem();
            it.id = optInt(o, "id", 0);
            it.shopTypeId = shopId;
            it.infoId = optInt(o, "infoId", 0);
            it.shopSlot = optInt(o, "shopSlot", 0);
            it.clazz = optInt(o, "clazz", -1);
            it.limitType = optInt(o, "limitType", 0);
            it.limit = optInt(o, "limit", 0);
            it.name = optStr(o, "name");
            JsonElement prices = o.get("prices");
            it.priceJson = prices != null && !prices.isJsonNull() ? GSON.toJson(prices) : "[]";
            out.add(it);
        }
        return out;
    }

    /** ShopItem → JSON object khớp entity ShopItemJson của server. */
    private static JsonObject toJson(ShopItem it, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("id", it.id);
        o.addProperty("infoId", it.infoId);
        o.addProperty("shopSlot", it.shopSlot);
        o.addProperty("clazz", it.clazz);
        o.addProperty("limitType", it.limitType);
        o.addProperty("limit", it.limit);
        o.addProperty("name", name != null ? name : (it.name != null ? it.name : ""));
        o.add("prices", GSON.fromJson(it.priceJson, JsonArray.class));
        return o;
    }

    /** Parse chuỗi JSON thành JsonArray (rỗng nếu null/hỏng). */
    private static JsonArray safeArray(String json) {
        if (json == null || json.isBlank()) return new JsonArray();
        try {
            JsonArray a = GSON.fromJson(json, JsonArray.class);
            return a != null ? a : new JsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    private static String rawItems(Connection c, int shopId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT items FROM shop WHERE id=?")) {
            ps.setInt(1, shopId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    private static JsonArray readItemsArray(Connection c, int shopId) throws SQLException {
        return safeArray(rawItems(c, shopId));
    }

    private static void writeItems(Connection c, int shopId, JsonArray items) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE shop SET items=?, updated_at=NOW() WHERE id=?")) {
            ps.setString(1, GSON.toJson(items));
            ps.setInt(2, shopId);
            ps.executeUpdate();
        }
    }

    /** id item mới = max id toàn bộ item mọi tab + 1 (client gửi id này khi mua → phải duy nhất). */
    private static int nextItemId(Connection c) throws SQLException {
        int max = 0;
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT items FROM shop")) {
            while (rs.next())
                for (JsonElement e : safeArray(rs.getString(1)))
                    if (e.isJsonObject()) max = Math.max(max, optInt(e.getAsJsonObject(), "id", 0));
        }
        return max + 1;
    }

    private static String itemName(Connection c, int infoId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM item_info_config WHERE id=?")) {
            ps.setInt(1, infoId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
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
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) throw new SQLException("price phần tử không phải object: " + e);
            JsonObject o = e.getAsJsonObject();
            if (!o.has("key") || !o.has("value")) throw new SQLException("price thiếu key/value: " + o);
            if (o.get("value").getAsLong() <= 0) throw new SQLException("price value phải > 0: " + o);
        }
    }

    /** Dump bảng shop ra backup/shop_{ts}.bak.json trước khi ghi. */
    private void backupShopTables() {
        try (Connection c = db.open()) {
            JsonObject root = new JsonObject();
            JsonArray rows = new JsonArray();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM shop")) {
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
            root.add("shop", rows);
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
