package com.apex.maptool.db;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Load palette: enemy_info + npc_info → list (id, name, spinId).
 */
public final class InfoDao {

    public record InfoItem(int id, String name, int spinId, int type) {
        public InfoItem(int id, String name, int spinId) { this(id, name, spinId, 0); }
        @Override public String toString() {
            return id + " — " + (name == null || name.isBlank() ? "(no name)" : name);
        }
    }

    private final Db db;
    public InfoDao(Db db) { this.db = db; }

    public List<InfoItem> enemies() throws SQLException {
        List<InfoItem> out = new ArrayList<>();
        // enemy_info: id, spin_id (hình), name, type (TypeEnemy → patrol rule)
        String sql = "SELECT id, spin_id, name, type FROM enemy_info ORDER BY id";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(new InfoItem(rs.getInt("id"), rs.getString("name"), rs.getInt("spin_id"), rs.getInt("type")));
        }
        return out;
    }

    public List<InfoItem> npcs() throws SQLException {
        List<InfoItem> out = new ArrayList<>();
        // npcs (table thật, list_npcs.id ref): id, name, model_id (visual). spinId field = model_id.
        String sql = "SELECT id, name, model_id FROM npcs ORDER BY id";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(new InfoItem(rs.getInt("id"), rs.getString("name"), rs.getInt("model_id")));
        }
        return out;
    }

    /** List map (id, name) cho dropdown chọn map đích của cổng. */
    public List<InfoItem> maps() throws SQLException {
        List<InfoItem> out = new ArrayList<>();
        String sql = "SELECT id, name FROM map_info_config ORDER BY id";
        try (Connection c = db.open(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(new InfoItem(rs.getInt("id"), rs.getString("name"), 0));
        }
        return out;
    }
}
