package com.apex.maptool.db;

import com.apex.maptool.model.Marker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Lưu/đọc marker ra FILE LOCAL (work/map_{id}.json) — test/sửa trong tool, KHÔNG đụng DB.
 * Format giống DB: {listEnemies, listNpcs, listArrivePosition, listGateWay}.
 */
public final class LocalStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private Path file(int mapId) {
        return Paths.get("work", "map_" + mapId + ".json");
    }

    public boolean exists(int mapId) { return Files.exists(file(mapId)); }
    public Path path(int mapId) { return file(mapId).toAbsolutePath(); }

    /** Mọi map đang có bản nháp local (tăng dần theo id). Rỗng nếu chưa lưu file nào. */
    public List<Integer> draftMapIds() {
        List<Integer> out = new ArrayList<>();
        Path dir = Paths.get("work");
        if (!Files.isDirectory(dir)) return out;
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (!n.startsWith("map_") || !n.endsWith(".json")) continue;
                try {
                    out.add(Integer.parseInt(n.substring(4, n.length() - 5)));
                } catch (NumberFormatException ignored) { /* file lạ trong work/ — bỏ qua */ }
            }
        } catch (Exception e) {
            System.err.println("[LocalStore] quét bản nháp fail: " + e.getMessage());
        }
        out.sort(Integer::compareTo);
        return out;
    }

    public void save(int mapId, List<Marker> markers) throws Exception {
        JsonObject root = new JsonObject();
        root.add("listEnemies", group(markers, Marker.Kind.ENEMY));
        root.add("listNpcs", group(markers, Marker.Kind.NPC));
        root.add("listArrivePosition", group(markers, Marker.Kind.ARRIVE));
        root.add("listGateWay", group(markers, Marker.Kind.GATEWAY));
        Path f = file(mapId);
        Files.createDirectories(f.getParent());
        Files.writeString(f, GSON.toJson(root));
    }

    /** Đọc lại marker từ file local. null nếu chưa có file. */
    public List<Marker> load(int mapId) throws Exception {
        Path f = file(mapId);
        if (!Files.exists(f)) return null;
        JsonObject root = GSON.fromJson(Files.readString(f), JsonObject.class);
        List<Marker> out = new ArrayList<>();
        read(out, root, "listEnemies", Marker.Kind.ENEMY);
        read(out, root, "listNpcs", Marker.Kind.NPC);
        read(out, root, "listArrivePosition", Marker.Kind.ARRIVE);
        read(out, root, "listGateWay", Marker.Kind.GATEWAY);
        return out;
    }

    private static JsonArray group(List<Marker> markers, Marker.Kind kind) {
        JsonArray arr = new JsonArray();
        for (Marker m : markers) if (m.kind == kind) arr.add(m.raw);
        return arr;
    }

    private static void read(List<Marker> out, JsonObject root, String key, Marker.Kind kind) {
        if (!root.has(key) || !root.get(key).isJsonArray()) return;
        for (JsonElement e : root.getAsJsonArray(key)) {
            if (e.isJsonObject()) out.add(new Marker(kind, e.getAsJsonObject()));
        }
    }
}
