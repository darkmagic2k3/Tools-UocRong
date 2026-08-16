package com.apex.maptool.db;

import com.apex.maptool.model.IncomingDrop;
import com.apex.maptool.model.Marker;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read/write map_info_config. Mỗi list 1 column JSON (Gson). Preserve field lạ qua JsonObject.
 */
public final class MapInfoDao {
    private static final Gson GSON = new Gson();
    private final Db db;

    public MapInfoDao(Db db) { this.db = db; }

    public static final class MapRow {
        public int id;
        public String name;
        public final List<Marker> markers = new ArrayList<>();
        public boolean exists;
    }

    /** Load 1 map → markers (4 column). */
    public MapRow load(int mapId) throws SQLException {
        MapRow row = new MapRow();
        row.id = mapId;
        String sql = "SELECT name, list_enemies, list_npcs, list_arrive_position, list_gate_way FROM map_info_config WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    row.exists = true;
                    row.name = rs.getString("name");
                    addMarkers(row.markers, rs.getString("list_enemies"), Marker.Kind.ENEMY);
                    addMarkers(row.markers, rs.getString("list_npcs"), Marker.Kind.NPC);
                    addMarkers(row.markers, rs.getString("list_arrive_position"), Marker.Kind.ARRIVE);
                    addMarkers(row.markers, rs.getString("list_gate_way"), Marker.Kind.GATEWAY);
                }
            }
        }
        return row;
    }

    /**
     * Quét list_gate_way của MỌI map → gom theo map ĐÍCH.
     * Trả: targetMapId → list {srcMapId, bX, bY} (điểm player rơi VÀO map đích).
     */
    public Map<Integer, List<IncomingDrop>> loadAllIncomingDrops() throws SQLException {
        Map<Integer, List<IncomingDrop>> out = new HashMap<>();
        String sql = "SELECT id, list_gate_way FROM map_info_config";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                int src = rs.getInt("id");
                String json = rs.getString("list_gate_way");
                if (json == null || json.isBlank()) continue;
                try {
                    JsonArray arr = GSON.fromJson(json, JsonArray.class);
                    if (arr == null) continue;
                    for (int i = 0; i < arr.size(); i++) {
                        if (!arr.get(i).isJsonObject()) continue;
                        JsonObject o = arr.get(i).getAsJsonObject();
                        int target = jint(o, "mapId"), bx = jint(o, "bX"), by = jint(o, "bY");
                        out.computeIfAbsent(target, k -> new ArrayList<>()).add(new IncomingDrop(src, i, bx, by));
                    }
                } catch (Exception ex) {
                    System.err.println("[MapInfoDao] parse gateway map " + src + " fail: " + ex.getMessage());
                }
            }
        }
        return out;
    }

    /**
     * NPC của MỌI map (1 query) — nguồn để dựng {@code NPCData.json} cho client.
     * Map nào cột rỗng thì vẫn có khoá với list rỗng, để bên gọi biết map đó tồn tại.
     */
    public Map<Integer, List<Marker>> loadAllNpcs() throws SQLException {
        Map<Integer, List<Marker>> out = new HashMap<>();
        String sql = "SELECT id, list_npcs FROM map_info_config";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                List<Marker> ms = new ArrayList<>();
                addMarkers(ms, rs.getString("list_npcs"), Marker.Kind.NPC);
                out.put(rs.getInt("id"), ms);
            }
        }
        return out;
    }

    /** 4 cột JSON THÔ của 1 map — để đối chiếu round-trip (xuất SQL rồi chạy lại phải là no-op). */
    public Map<String, String> loadRawColumns(int mapId) throws SQLException {
        Map<String, String> out = new HashMap<>();
        String sql = "SELECT list_npcs, list_enemies, list_gate_way, list_arrive_position FROM map_info_config WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return out;
                for (String col : new String[]{"list_npcs", "list_enemies", "list_gate_way", "list_arrive_position"}) {
                    out.put(col, rs.getString(col));
                }
            }
        }
        return out;
    }

    /** Tên map theo id (cho chú thích trong file SQL). */
    public Map<Integer, String> loadMapNames() throws SQLException {
        Map<Integer, String> out = new HashMap<>();
        try (Connection c = db.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM map_info_config")) {
            while (rs.next()) out.put(rs.getInt("id"), rs.getString("name"));
        }
        return out;
    }

    /** Đếm nhanh quái/NPC/cổng của MỌI map (1 query). Trả mapId → {nEnemy, nNpc, nGate}. */
    public Map<Integer, int[]> loadMapStats() throws SQLException {
        Map<Integer, int[]> out = new HashMap<>();
        String sql = "SELECT id, list_enemies, list_npcs, list_gate_way FROM map_info_config";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.put(rs.getInt("id"), new int[]{
                        arrSize(rs.getString("list_enemies")),
                        arrSize(rs.getString("list_npcs")),
                        arrSize(rs.getString("list_gate_way"))});
            }
        }
        return out;
    }

    private static int arrSize(String json) {
        if (json == null || json.isBlank()) return 0;
        try {
            JsonArray a = GSON.fromJson(json, JsonArray.class);
            return a == null ? 0 : a.size();
        } catch (Exception e) { return 0; }
    }

    private static int jint(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    }

    /** Sửa bX/bY của 1 cổng (gateIndex) trong list_gate_way của map nguồn → ghi DB (backup trước). */
    public void updateGatewayDrop(int srcMapId, int gateIndex, int bX, int bY) throws SQLException {
        String json;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT list_gate_way FROM map_info_config WHERE id=?")) {
            ps.setInt(1, srcMapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("map " + srcMapId + " không tồn tại");
                json = rs.getString("list_gate_way");
            }
        }
        JsonArray arr = (json == null || json.isBlank()) ? new JsonArray() : GSON.fromJson(json, JsonArray.class);
        if (arr == null || gateIndex < 0 || gateIndex >= arr.size() || !arr.get(gateIndex).isJsonObject())
            throw new SQLException("cổng index " + gateIndex + " không hợp lệ ở map " + srcMapId);
        JsonObject g = arr.get(gateIndex).getAsJsonObject();
        g.addProperty("bX", bX);
        g.addProperty("bY", bY);

        backup(srcMapId);
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE map_info_config SET list_gate_way=? WHERE id=?")) {
            ps.setString(1, GSON.toJson(arr));
            ps.setInt(2, srcMapId);
            int n = ps.executeUpdate();
            System.out.println("[MapInfoDao] update drop map " + srcMapId + " cổng " + gateIndex
                    + " → (" + bX + "," + bY + ") rows=" + n);
        }
    }

    private static void addMarkers(List<Marker> out, String json, Marker.Kind kind) {
        if (json == null || json.isBlank()) return;
        try {
            JsonArray arr = GSON.fromJson(json, JsonArray.class);
            if (arr == null) return;
            for (JsonElement e : arr) {
                if (e.isJsonObject()) out.add(new Marker(kind, e.getAsJsonObject()));
            }
        } catch (Exception ex) {
            System.err.println("[MapInfoDao] parse " + kind + " fail: " + ex.getMessage());
        }
    }

    /**
     * Save: UPDATE 3 column (enemies/npcs/arrive) + gateway. Backup row trước khi ghi.
     * KHÔNG đụng meta + các column khác.
     */
    public void save(int mapId, List<Marker> markers) throws SQLException {
        String enemies = toJson(markers, Marker.Kind.ENEMY);
        String npcs = toJson(markers, Marker.Kind.NPC);
        String arrive = toJson(markers, Marker.Kind.ARRIVE);
        String gateway = toJson(markers, Marker.Kind.GATEWAY);

        backup(mapId); // dump row hiện tại ra file trước khi ghi

        String sql = "UPDATE map_info_config SET list_enemies=?, list_npcs=?, list_arrive_position=?, list_gate_way=? WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, enemies);
            ps.setString(2, npcs);
            ps.setString(3, arrive);
            ps.setString(4, gateway);
            ps.setInt(5, mapId);
            int n = ps.executeUpdate();
            System.out.println("[MapInfoDao] save map " + mapId + " — rows=" + n);
        }
    }

    /** Bản public của {@link #toJson} cho lệnh tự kiểm round-trip ngoài package. */
    public static String toJsonPublic(List<Marker> markers, Marker.Kind kind) { return toJson(markers, kind); }

    /** Package-private để {@link MapExport} dựng SQL ra ĐÚNG chuỗi mà {@link #save} ghi vào DB. */
    static String toJson(List<Marker> markers, Marker.Kind kind) {
        JsonArray arr = new JsonArray();
        for (Marker m : markers) if (m.kind == kind) arr.add(m.raw);
        return GSON.toJson(arr);
    }

    /** Dump row hiện tại ra file backup (tránh mất data nếu ghi sai). */
    private void backup(int mapId) {
        String sql = "SELECT * FROM map_info_config WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return;
                ResultSetMetaData md = rs.getMetaData();
                JsonObject o = new JsonObject();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    o.addProperty(md.getColumnLabel(i), v == null ? null : v.toString());
                }
                Path dir = Paths.get("backup");
                if (!Files.exists(dir)) Files.createDirectories(dir);
                Path f = dir.resolve("map_" + mapId + "_" + System.currentTimeMillis() + ".bak.json");
                Files.writeString(f, GSON.toJson(o));
                System.out.println("[MapInfoDao] backup → " + f.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("[MapInfoDao] backup fail: " + e.getMessage());
        }
    }
}
