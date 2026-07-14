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
 * Đọc/ghi equip_info — template chỉ số trang bị (gồm cải trang).
 * info_buff/random_buff format chuỗi "type-value;type-value" (type = enum ItemAttribute server).
 * Ghi có backup + bump version_tracker.
 */
public final class EquipDao {
    private static final Gson GSON = new Gson();
    private final Db db;

    public EquipDao(Db db) { this.db = db; }

    public static final class EquipInfo {
        public int id;
        public String name;
        public int type;
        public int maxStar, maxLevel, time, rank;
        public String infoBuff, randomBuff;
    }

    public List<EquipInfo> all() throws SQLException {
        List<EquipInfo> out = new ArrayList<>();
        String sql = "SELECT id, name, type, max_star, max_level, time, `rank`, info_buff, random_buff FROM equip_info ORDER BY id";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                EquipInfo e = new EquipInfo();
                e.id = rs.getInt("id");
                e.name = rs.getString("name");
                e.type = rs.getInt("type");
                e.maxStar = rs.getInt("max_star");
                e.maxLevel = rs.getInt("max_level");
                e.time = rs.getInt("time");
                e.rank = rs.getInt("rank");
                e.infoBuff = rs.getString("info_buff");
                e.randomBuff = rs.getString("random_buff");
                out.add(e);
            }
        }
        return out;
    }

    /** Sửa chỉ số + star/level/hạn. Backup + bump version. */
    public void update(int id, String infoBuff, String randomBuff, int maxStar, int maxLevel, int time) throws SQLException {
        backup();
        String sql = "UPDATE equip_info SET info_buff=?, random_buff=?, max_star=?, max_level=?, time=?, updated_at=NOW() WHERE id=?";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, infoBuff);
            ps.setString(2, randomBuff == null || randomBuff.isBlank() ? null : randomBuff);
            ps.setInt(3, maxStar);
            ps.setInt(4, maxLevel);
            ps.setInt(5, time);
            ps.setInt(6, id);
            System.out.println("[EquipDao] update " + id + " rows=" + ps.executeUpdate());
        }
        bumpVersion();
    }

    private void backup() {
        try (Connection c = db.open(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM equip_info")) {
            JsonArray rows = new JsonArray();
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                JsonObject o = new JsonObject();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    o.addProperty(md.getColumnLabel(i), v == null ? null : v.toString());
                }
                rows.add(o);
            }
            Path dir = Paths.get("backup");
            if (!Files.exists(dir)) Files.createDirectories(dir);
            Path f = dir.resolve("equip_info_" + System.currentTimeMillis() + ".bak.json");
            Files.writeString(f, GSON.toJson(rows));
            System.out.println("[EquipDao] backup → " + f.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("[EquipDao] backup fail: " + e.getMessage());
        }
    }

    private void bumpVersion() {
        String sql = "INSERT INTO version_tracker (table_name, version, last_op, last_changed_at) VALUES ('equip_info',?,?,NOW())"
                + " ON DUPLICATE KEY UPDATE version=VALUES(version), last_op=VALUES(last_op), last_changed_at=NOW()";
        try (Connection c = db.open(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis() / 1000);
            ps.setString(2, "map-editor-tool");
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[EquipDao] bump version fail: " + e.getMessage());
        }
    }
}
