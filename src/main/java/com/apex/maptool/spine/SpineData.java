package com.apex.maptool.spine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parse Spine 4.2 skeleton JSON (manual qua Gson JsonObject để chịu format quirks).
 * Hỗ trợ: bones, slots (draw order), skin default (region + mesh), animations (bone timelines).
 */
public final class SpineData {

    public static final class Bone {
        public String name, parent;
        public int parentIdx = -1;
        public float x, y, rotation, scaleX = 1, scaleY = 1, shearX, shearY, length;
    }
    public static final class Slot {
        public String name, bone, attachment;
    }
    public static final class Attachment {
        public String type = "region";   // region | mesh
        public String path;              // atlas region name
        public float x, y, rotation, scaleX = 1, scaleY = 1, width, height;
        // mesh
        public float[] uvs;
        public int[] triangles;
        public float[] vertices;
        public int hull;
    }
    // keyframe single-value (Spine 4.2: rotate/translatex/translatey/scalex/scaley riêng).
    // curve: null=linear, stepped flag, else bezier [cx1,cy1,cx2,cy2] tọa độ TUYỆT ĐỐI (time,value).
    public static final class Key { public float time, value; public boolean stepped; public float[] curve; }
    public static final class BoneTimeline {
        public List<Key> rotate, tx, ty, sx, sy;   // null nếu không có
    }
    public static final class Animation {
        public final Map<String, BoneTimeline> bones = new HashMap<>();
        public float duration;
    }

    public float skelWidth = 100, skelHeight = 100, skelX, skelY;
    public final List<Bone> bones = new ArrayList<>();
    public final Map<String, Integer> boneIdx = new HashMap<>();
    public final List<Slot> slots = new ArrayList<>();
    // slotName → attachmentName → Attachment
    public final Map<String, Map<String, Attachment>> skin = new HashMap<>();
    public final Map<String, Animation> animations = new HashMap<>();

    public static SpineData load(Path jsonFile) throws Exception {
        SpineData d = new SpineData();
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(Files.readString(jsonFile), JsonObject.class);

        // skeleton meta (width/height/x/y cho scale + offset)
        JsonObject sko = obj(root, "skeleton");
        if (sko != null) {
            d.skelWidth = f(sko, "width", 100);
            d.skelHeight = f(sko, "height", 100);
            d.skelX = f(sko, "x", 0);
            d.skelY = f(sko, "y", 0);
        }

        // bones
        for (JsonElement be : arr(root, "bones")) {
            JsonObject o = be.getAsJsonObject();
            Bone b = new Bone();
            b.name = str(o, "name", null);
            b.parent = str(o, "parent", null);
            b.x = f(o, "x", 0); b.y = f(o, "y", 0);
            b.rotation = f(o, "rotation", 0);
            b.scaleX = f(o, "scaleX", 1); b.scaleY = f(o, "scaleY", 1);
            b.shearX = f(o, "shearX", 0); b.shearY = f(o, "shearY", 0);
            b.length = f(o, "length", 0);
            d.boneIdx.put(b.name, d.bones.size());
            d.bones.add(b);
        }
        for (Bone b : d.bones) if (b.parent != null) b.parentIdx = d.boneIdx.getOrDefault(b.parent, -1);

        // slots (draw order)
        for (JsonElement se : arr(root, "slots")) {
            JsonObject o = se.getAsJsonObject();
            Slot s = new Slot();
            s.name = str(o, "name", null);
            s.bone = str(o, "bone", null);
            s.attachment = str(o, "attachment", null);
            d.slots.add(s);
        }

        // skins (4.x = array). lấy "default" (hoặc skin đầu).
        JsonElement skinsEl = root.get("skins");
        if (skinsEl != null && skinsEl.isJsonArray()) {
            for (JsonElement ke : skinsEl.getAsJsonArray()) {
                JsonObject sk = ke.getAsJsonObject();
                JsonObject atts = obj(sk, "attachments");
                if (atts == null) continue;
                for (Map.Entry<String, JsonElement> slotE : atts.entrySet()) {
                    Map<String, Attachment> map = d.skin.computeIfAbsent(slotE.getKey(), k -> new HashMap<>());
                    for (Map.Entry<String, JsonElement> attE : slotE.getValue().getAsJsonObject().entrySet()) {
                        map.put(attE.getKey(), parseAtt(attE.getKey(), attE.getValue().getAsJsonObject()));
                    }
                }
            }
        }

        // animations
        JsonObject anims = obj(root, "animations");
        if (anims != null) {
            for (Map.Entry<String, JsonElement> ae : anims.entrySet()) {
                Animation an = new Animation();
                JsonObject ao = ae.getValue().getAsJsonObject();
                JsonObject bonesO = obj(ao, "bones");
                if (bonesO != null) {
                    for (Map.Entry<String, JsonElement> bo : bonesO.entrySet()) {
                        BoneTimeline tl = new BoneTimeline();
                        JsonObject t = bo.getValue().getAsJsonObject();
                        tl.rotate = parseTimeline(t, "rotate", 0);
                        tl.tx = parseTimeline(t, "translatex", 0);
                        tl.ty = parseTimeline(t, "translatey", 0);
                        tl.sx = parseTimeline(t, "scalex", 1);
                        tl.sy = parseTimeline(t, "scaley", 1);
                        an.duration = Math.max(an.duration, maxTime(tl));
                        an.bones.put(bo.getKey(), tl);
                    }
                }
                d.animations.put(ae.getKey(), an);
            }
        }
        return d;
    }

    private static Attachment parseAtt(String key, JsonObject o) {
        Attachment a = new Attachment();
        a.type = str(o, "type", "region");
        a.path = str(o, "path", str(o, "name", key));
        a.x = f(o, "x", 0); a.y = f(o, "y", 0);
        a.rotation = f(o, "rotation", 0);
        a.scaleX = f(o, "scaleX", 1); a.scaleY = f(o, "scaleY", 1);
        a.width = f(o, "width", 0); a.height = f(o, "height", 0);
        if (a.type.equals("mesh")) {
            a.uvs = farr(o, "uvs");
            a.triangles = iarr(o, "triangles");
            a.vertices = farr(o, "vertices");
            a.hull = (int) f(o, "hull", 0);
        }
        return a;
    }

    /** Parse 1 timeline single-value (rotate/translatex/...). null nếu không có. */
    private static List<Key> parseTimeline(JsonObject t, String name, float def) {
        if (t == null || !t.has(name) || !t.get(name).isJsonArray()) return null;
        List<Key> out = new ArrayList<>();
        for (JsonElement ke : t.getAsJsonArray(name)) {
            JsonObject o = ke.getAsJsonObject();
            Key k = new Key();
            k.time = f(o, "time", 0);
            k.value = f(o, "value", def);
            JsonElement curve = o.get("curve");
            if (curve != null) {
                if (curve.isJsonPrimitive() && curve.getAsJsonPrimitive().isString()
                        && curve.getAsString().equals("stepped")) {
                    k.stepped = true;
                } else if (curve.isJsonArray()) {
                    JsonArray ca = curve.getAsJsonArray();
                    k.curve = new float[ca.size()];
                    for (int ci = 0; ci < ca.size(); ci++) k.curve[ci] = ca.get(ci).getAsFloat();
                }
            }
            out.add(k);
        }
        return out;
    }

    private static float maxTime(BoneTimeline tl) {
        return Math.max(Math.max(lastTime(tl.rotate), Math.max(lastTime(tl.tx), lastTime(tl.ty))),
                        Math.max(lastTime(tl.sx), lastTime(tl.sy)));
    }
    private static float lastTime(List<Key> keys) {
        return (keys == null || keys.isEmpty()) ? 0 : keys.get(keys.size() - 1).time;
    }

    // ─── json helpers ──────────────────────────────────────────
    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
    private static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }
    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }
    private static float f(JsonObject o, String k, float def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsFloat() : def;
    }
    private static float[] farr(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return null;
        JsonArray a = o.getAsJsonArray(k);
        float[] r = new float[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsFloat();
        return r;
    }
    private static int[] iarr(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return null;
        JsonArray a = o.getAsJsonArray(k);
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
        return r;
    }
}
