package com.apex.maptool.db;

import com.apex.maptool.model.Marker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Xuất FILE cho người khác dùng — đối lập với {@link MapInfoDao} (ghi thẳng DB) và
 * {@link com.apex.maptool.unity.MapSceneWriter} (ghi thẳng prefab).
 *
 * <p>Hai đường ra, hai người nhận khác nhau:
 * <ul>
 *   <li><b>SQL</b> {@code UPDATE map_info_config} — gửi dev server import rồi restart server game.
 *       Cần khi máy đang ngồi KHÔNG nối được DB đích (vd sửa ở nhà, DB nằm sau VPN của team).</li>
 *   <li><b>NPCData.json</b> — client Unity đọc ở {@code Assets/Resources/NPCData.json}, không phải DB.
 *       Đây là bản sao NPC phía client nên <b>đổi NPC trong DB mà quên xuất file này là client lệch</b>.</li>
 * </ul>
 *
 * <p>Lớp này không đụng UI cũng không đụng kết nối — chỉ nhận marker, trả chuỗi.
 */
public final class MapExport {

    private static final Gson MIN = new Gson();                                   // JSON gọn cho SQL
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private MapExport() { }

    /** Khối chú thích đầu file SQL — nhắc backup và nhắc restart, hai thứ hay quên nhất. */
    public static String sqlHeader() {
        return """
                -- ============================================
                -- Ước Rồng Map Editor — xuất SQL (%s)
                -- Chạy trên DB `nro`. NHỚ BACKUP TRƯỚC:
                --   mysqldump -uroot nro map_info_config > backup_map_info_config.sql
                -- Đổi xong phải RESTART SERVER GAME mới ăn.
                -- ============================================

                """.formatted(STAMP.format(LocalDateTime.now()));
    }

    /**
     * Một câu {@code UPDATE map_info_config} cho 1 map.
     *
     * <p>JSON ghi ra là {@code Marker.raw} NGUYÊN VẸN — đúng bằng thứ {@link MapInfoDao#save} ghi vào
     * DB, nên chạy file SQL này hay bấm "Lưu DB" đều cho cùng một kết quả. (Bản HTML cũ dựng lại
     * object chỉ với các field nó biết ⇒ field lạ trong DB bị mất; ở đây thì không.)
     */
    public static String sqlForMap(int mapId, String mapName, List<Marker> markers) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- Map ").append(mapId);
        if (mapName != null && !mapName.isBlank()) sb.append(" — ").append(mapName);
        sb.append('\n');
        sb.append("UPDATE map_info_config SET\n");
        sb.append("  list_npcs = '").append(esc(MapInfoDao.toJson(markers, Marker.Kind.NPC))).append("',\n");
        sb.append("  list_enemies = '").append(esc(MapInfoDao.toJson(markers, Marker.Kind.ENEMY))).append("',\n");
        sb.append("  list_gate_way = '").append(esc(MapInfoDao.toJson(markers, Marker.Kind.GATEWAY))).append("',\n");
        sb.append("  list_arrive_position = '").append(esc(MapInfoDao.toJson(markers, Marker.Kind.ARRIVE))).append("'\n");
        sb.append("WHERE id = ").append(mapId).append(";\n");
        return sb.toString();
    }

    /** Escape chuỗi cho literal SQL của MySQL: chỉ nháy đơn (JSON không sinh backslash trần). */
    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /**
     * {@code NPCData.json} cho client: {@code {"maps":[{"mapId":N,"npcs":[{x,y,npcChatId,requiredMission}]}]}}.
     *
     * <p>⚠ Tên field phía client là <b>{@code npcChatId}</b> chứ không phải {@code id} như trong DB —
     * đặt sai tên là NPC biến mất im lặng, không có lỗi nào cả.
     *
     * <p>Map không có NPC nào thì KHÔNG xuất (giữ đúng hành vi bản cũ, file gọn hơn nhiều).
     *
     * @param npcsByMap mapId → marker của map đó (lọc NPC tại đây, truyền cả list cũng được)
     */
    public static String npcDataJson(Map<Integer, List<Marker>> npcsByMap) {
        JsonArray maps = new JsonArray();
        npcsByMap.keySet().stream().sorted().forEach(mapId -> {
            JsonArray npcs = new JsonArray();
            for (Marker m : npcsByMap.get(mapId)) {
                if (m.kind != Marker.Kind.NPC) continue;
                JsonObject o = new JsonObject();
                o.addProperty("x", m.serverX());
                o.addProperty("y", m.serverY());
                o.addProperty("npcChatId", m.mainId());
                o.addProperty("requiredMission", m.getInt("requiredMission"));
                npcs.add(o);
            }
            if (npcs.isEmpty()) return;
            JsonObject md = new JsonObject();
            md.addProperty("mapId", mapId);
            md.add("npcs", npcs);
            maps.add(md);
        });
        JsonObject root = new JsonObject();
        root.add("maps", maps);
        return PRETTY.toJson(root);
    }

    // ── ĐỐI CHIẾU VỚI FILE CLIENT ĐANG CÓ ───────────────────────────────────
    /**
     * Kết quả so bản sắp ghi với {@code NPCData.json} hiện có trong client.
     *
     * @param onlyOld  map CHỈ có trong file client — ghi đè thẳng là <b>mất sạch NPC của chúng</b>
     * @param onlyNew  map mới xuất hiện (map mới thêm vào DB)
     * @param changed  map có ở cả hai nhưng danh sách NPC khác nhau
     * @param same     số map giống hệt
     * @param lostNpc  tổng số NPC sẽ mất nếu ghi đè mà không giữ {@code onlyOld}
     */
    public record NpcDiff(List<Integer> onlyOld, List<Integer> onlyNew, List<Integer> changed,
                          int same, int lostNpc) {
        public boolean anyLoss() { return lostNpc > 0; }
    }

    /**
     * So bản sắp xuất với nội dung {@code NPCData.json} đang có.
     *
     * <p><b>Vì sao cần:</b> đo trên client thật — file client có <b>12 map</b> (1500, 8000-8004,
     * 10000-10005, 36000) mà `map_info_config` KHÔNG có NPC nào. NPC của mấy map đó sống hoàn toàn
     * ở phía client. Xuất "đúng theo DB" rồi ghi đè là **xoá 20 NPC** mà không một lời cảnh báo —
     * đúng cái bẫy bản HTML cũ mắc phải. Nên trước khi ghi phải cho người dùng thấy mình sắp mất gì.
     */
    public static NpcDiff diffNpcData(String oldJson, Map<Integer, List<Marker>> newData) {
        Map<Integer, List<String>> old = parseNpcData(oldJson);
        Map<Integer, List<String>> neu = new java.util.TreeMap<>();
        for (var e : newData.entrySet()) {
            List<String> keys = new java.util.ArrayList<>();
            for (Marker m : e.getValue()) if (m.kind == Marker.Kind.NPC) keys.add(npcKey(m));
            if (!keys.isEmpty()) { java.util.Collections.sort(keys); neu.put(e.getKey(), keys); }
        }
        List<Integer> onlyOld = new java.util.ArrayList<>(), onlyNew = new java.util.ArrayList<>(),
                changed = new java.util.ArrayList<>();
        int same = 0, lost = 0;
        for (var e : old.entrySet()) {
            List<String> b = neu.get(e.getKey());
            if (b == null) { onlyOld.add(e.getKey()); lost += e.getValue().size(); }
            else if (b.equals(e.getValue())) same++;
            else changed.add(e.getKey());
        }
        for (Integer k : neu.keySet()) if (!old.containsKey(k)) onlyNew.add(k);
        return new NpcDiff(onlyOld, onlyNew, changed, same, lost);
    }

    /**
     * Như {@link #npcDataJson(Map)} nhưng GIỮ LẠI những map chỉ có trong file cũ
     * (client-only, DB không quản). Đây là đường an toàn: không map nào bị mất NPC.
     */
    public static String npcDataJsonMerged(Map<Integer, List<Marker>> newData, String oldJson) {
        Map<Integer, JsonArray> merged = new java.util.TreeMap<>();
        for (var e : parseNpcRaw(oldJson).entrySet()) merged.put(e.getKey(), e.getValue());
        for (var e : newData.entrySet()) {
            JsonArray npcs = new JsonArray();
            for (Marker m : e.getValue()) {
                if (m.kind != Marker.Kind.NPC) continue;
                npcs.add(npcObj(m));
            }
            if (npcs.isEmpty()) continue;           // DB không có NPC ⇒ giữ nguyên bản cũ nếu có
            merged.put(e.getKey(), npcs);
        }
        JsonArray maps = new JsonArray();
        for (var e : merged.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            JsonObject md = new JsonObject();
            md.addProperty("mapId", e.getKey());
            md.add("npcs", e.getValue());
            maps.add(md);
        }
        JsonObject root = new JsonObject();
        root.add("maps", maps);
        return PRETTY.toJson(root);
    }

    private static JsonObject npcObj(Marker m) {
        JsonObject o = new JsonObject();
        o.addProperty("x", m.serverX());
        o.addProperty("y", m.serverY());
        o.addProperty("npcChatId", m.mainId());
        o.addProperty("requiredMission", m.getInt("requiredMission"));
        return o;
    }

    private static String npcKey(Marker m) {
        return m.serverX() + "," + m.serverY() + "," + m.mainId() + "," + m.getInt("requiredMission");
    }

    /** {@code NPCData.json} → mapId → khoá NPC đã sắp xếp (để so bằng nhau). */
    private static Map<Integer, List<String>> parseNpcData(String json) {
        Map<Integer, List<String>> out = new java.util.TreeMap<>();
        for (var e : parseNpcRaw(json).entrySet()) {
            List<String> keys = new java.util.ArrayList<>();
            for (var el : e.getValue()) {
                JsonObject o = el.getAsJsonObject();
                keys.add(gi(o, "x") + "," + gi(o, "y") + "," + gi(o, "npcChatId") + "," + gi(o, "requiredMission"));
            }
            java.util.Collections.sort(keys);
            out.put(e.getKey(), keys);
        }
        return out;
    }

    /** {@code NPCData.json} → mapId → mảng npc NGUYÊN VẸN (giữ field lạ nếu client có thêm). */
    private static Map<Integer, JsonArray> parseNpcRaw(String json) {
        Map<Integer, JsonArray> out = new java.util.TreeMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonObject root = MIN.fromJson(json, JsonObject.class);
            if (root == null || !root.has("maps") || !root.get("maps").isJsonArray()) return out;
            for (var el : root.getAsJsonArray("maps")) {
                if (!el.isJsonObject()) continue;
                JsonObject md = el.getAsJsonObject();
                if (!md.has("mapId") || !md.has("npcs") || !md.get("npcs").isJsonArray()) continue;
                out.put(md.get("mapId").getAsInt(), md.getAsJsonArray("npcs"));
            }
        } catch (Exception e) {
            System.err.println("[MapExport] đọc NPCData.json cũ fail: " + e.getMessage());
        }
        return out;
    }

    private static int gi(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    }

    /** Số map thật sự được ghi vào NPCData.json (map không NPC bị bỏ) — để báo lại cho người dùng. */
    public static int countMapsWithNpc(Map<Integer, List<Marker>> npcsByMap) {
        int n = 0;
        for (List<Marker> ms : npcsByMap.values()) {
            for (Marker m : ms) if (m.kind == Marker.Kind.NPC) { n++; break; }
        }
        return n;
    }

    /** Dùng trong test/CLI: JSON gọn của 1 nhóm marker (giống hệt chuỗi ghi vào cột DB). */
    static String min(Object o) { return MIN.toJson(o); }
}
