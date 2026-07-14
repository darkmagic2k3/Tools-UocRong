package com.apex.maptool.model;

import com.google.gson.JsonObject;

/**
 * Marker editable. Source-of-truth = `raw` JsonObject (giữ field lạ → round-trip không mất data).
 * Tọa độ server đọc/ghi vào raw theo field tương ứng kind.
 *
 * ENEMY:   {infoId, spawnCx, spawnCy}
 * NPC:     {id, requiredMission, x, y}
 * ARRIVE:  {x, y}
 * GATEWAY: {mapId, aX, aY, bX, bY, type}  (aX/aY = cổng map hiện tại; bX/bY = spawn map đích)
 */
public final class Marker {
    public enum Kind { ENEMY, NPC, ARRIVE, GATEWAY }

    public final Kind kind;
    public final JsonObject raw;

    public Marker(Kind kind, JsonObject raw) {
        this.kind = kind;
        this.raw = raw;
    }

    /** Tạo marker mới với tọa độ server (x,y). */
    public static Marker create(Kind kind, int serverX, int serverY) {
        JsonObject o = new JsonObject();
        switch (kind) {
            case ENEMY -> { o.addProperty("infoId", 0); o.addProperty("spawnCx", serverX); o.addProperty("spawnCy", serverY); }
            case NPC -> { o.addProperty("id", 0); o.addProperty("requiredMission", 0); o.addProperty("x", serverX); o.addProperty("y", serverY); }
            case ARRIVE -> { o.addProperty("x", serverX); o.addProperty("y", serverY); }
            case GATEWAY -> { o.addProperty("mapId", 0); o.addProperty("aX", serverX); o.addProperty("aY", serverY); o.addProperty("bX", 0); o.addProperty("bY", 0); o.addProperty("type", 0); }
        }
        return new Marker(kind, o);
    }

    private String fx() { return switch (kind) { case ENEMY -> "spawnCx"; case GATEWAY -> "aX"; default -> "x"; }; }
    private String fy() { return switch (kind) { case ENEMY -> "spawnCy"; case GATEWAY -> "aY"; default -> "y"; }; }

    public int serverX() { return getInt(fx()); }
    public int serverY() { return getInt(fy()); }
    public void setServerX(int v) { raw.addProperty(fx(), v); }
    public void setServerY(int v) { raw.addProperty(fy(), v); }

    /** id chính: ENEMY infoId, NPC id. */
    public int mainId() {
        return switch (kind) {
            case ENEMY -> getInt("infoId");
            case NPC -> getInt("id");
            case GATEWAY -> getInt("mapId");
            default -> 0;
        };
    }
    public void setMainId(int v) {
        switch (kind) {
            case ENEMY -> raw.addProperty("infoId", v);
            case NPC -> raw.addProperty("id", v);
            case GATEWAY -> raw.addProperty("mapId", v);
            default -> {}
        }
    }

    public int getInt(String key) {
        return raw.has(key) && !raw.get(key).isJsonNull() ? raw.get(key).getAsInt() : 0;
    }
    public void setInt(String key, int v) { raw.addProperty(key, v); }
}
