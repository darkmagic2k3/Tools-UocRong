package com.apex.maptool.spine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Parse Spine 4.2 skeleton JSON (manual qua Gson JsonObject để chịu format quirks).
 * Hỗ trợ: bones, slots (draw order), skin default (region + mesh), animations (bone timelines).
 */
public final class SpineData {

    /** {@code Inherit} của spine-runtime — ĐÚNG thứ tự ordinal (bản nhị phân ghi bằng chỉ số). */
    public static final int INHERIT_NORMAL = 0, INHERIT_ONLY_TRANSLATION = 1,
            INHERIT_NO_ROTATION_OR_REFLECTION = 2, INHERIT_NO_SCALE = 3, INHERIT_NO_SCALE_OR_REFLECTION = 4;

    /** Tên trong JSON → hằng {@code INHERIT_*} (SkeletonJson parse không phân biệt hoa thường). */
    static int inheritOf(String s) {
        if (s == null) return INHERIT_NORMAL;
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "onlytranslation": return INHERIT_ONLY_TRANSLATION;
            case "norotationorreflection": return INHERIT_NO_ROTATION_OR_REFLECTION;
            case "noscale": return INHERIT_NO_SCALE;
            case "noscaleorreflection": return INHERIT_NO_SCALE_OR_REFLECTION;
            default: return INHERIT_NORMAL;
        }
    }

    public static final class Bone {
        public String name, parent;
        public int parentIdx = -1;
        public float x, y, rotation, scaleX = 1, scaleY = 1, shearX, shearY, length;
        /**
         * Kiểu thừa kế transform từ bone cha. Đo trên client: 57176/57178 bone là
         * {@link #INHERIT_NORMAL}, đúng 2 bone {@code noScale} — nhưng vẫn phải giữ vì
         * IK hai-xương THOÁT NGAY khi bone không Normal ({@code IkConstraint.cs:239}).
         */
        public int inherit = INHERIT_NORMAL;
    }

    // ── IK CONSTRAINT ───────────────────────────────────────────────────────
    /**
     * Dữ liệu setup của 1 IK constraint ({@code IkConstraintData.cs}). Đo trên client:
     * 1564 constraint hai-xương + 25 một-xương, {@code mix} luôn = 1, 1441 cái có
     * {@code softness != 0}, 1423 cái bật {@code stretch} — nên KHÔNG được cắt bớt nhánh nào.
     *
     * <p>⚠ Mặc định {@code mix}: JSON là <b>1</b> ({@code SkeletonJson.cs:197}) còn trường của
     * {@code IkConstraintData} là <b>0</b> — bản nhị phân dựa vào mặc định 0 đó khi thiếu cờ.
     * Mỗi bộ đọc tự đặt đúng giá trị của mình, đừng "thống nhất" lại.
     */
    public static final class Ik {
        public String name;
        public int order;
        /** Chỉ số bone bị ràng buộc: 1 phần tử = IK một xương, 2 phần tử = IK hai xương. */
        public int[] bones = new int[0];
        public int target = -1;
        public float mix, softness;
        public int bendDirection = 1;
        public boolean compress, stretch, uniform;
    }

    public final List<Ik> iks = new ArrayList<>();
    public final Map<String, Integer> ikIdx = new HashMap<>();

    /** 1 key của IK timeline. {@code mix}/{@code softness} nội suy, 3 trường còn lại NHẢY BẬC. */
    public static final class IkKey {
        public float time, mix = 1, softness;
        public int bendDirection = 1;
        public boolean compress, stretch;
        public boolean stepped;
        /** null = linear; 8 float: 4 đầu cho {@code mix}, 4 sau cho {@code softness} (toạ độ tuyệt đối). */
        public float[] curve;
        /** Mẫu bezier dựng sẵn: 18 số cho mix rồi 18 số cho softness. */
        public float[] bez;
    }

    /** Timeline của MỘT IK constraint (chỉ số trong {@link #iks}). */
    public static final class IkTimeline {
        public int ik = -1;
        public final List<IkKey> keys = new ArrayList<>();
    }
    /** {@code BlendMode} của spine-runtime — ĐÚNG thứ tự ordinal (bản nhị phân ghi bằng chỉ số). */
    public static final int BLEND_NORMAL = 0, BLEND_ADDITIVE = 1, BLEND_MULTIPLY = 2, BLEND_SCREEN = 3;

    static int blendOf(String s) {
        if (s == null) return BLEND_NORMAL;
        switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "additive": return BLEND_ADDITIVE;
            case "multiply": return BLEND_MULTIPLY;
            case "screen": return BLEND_SCREEN;
            default: return BLEND_NORMAL;
        }
    }

    public static final class Slot {
        public String name, bone, attachment;
        /**
         * Cách hoà màu của slot. Đo trên client: <b>841 slot additive · 15 screen · 3 multiply</b>
         * trải trên 390 skeleton (tia nắng, sóng biển, danh hiệu, màn login…). Bỏ qua trường này là
         * mấy hiệu ứng đó vẽ ra <b>mảng đen</b> — vì với additive/screen thì đen = trong suốt.
         */
        public int blend = BLEND_NORMAL;
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

        /** Số ĐỈNH của mesh (uvs = u,v xen kẽ). 0 nếu không phải mesh. */
        public int vertexCount() { return uvs == null ? 0 : uvs.length / 2; }

        /**
         * Mesh CÓ weight hay không — phân biệt bằng ĐỘ DÀI như {@code SpineRenderer.drawMesh}:
         * không weight thì {@code vertices} đúng bằng {@code uvs} (x,y cho mỗi đỉnh),
         * có weight thì dài hơn vì giữ dạng phẳng {@code [n, bone,vx,vy,w, ...]}.
         */
        public boolean weighted() {
            return uvs != null && vertices != null && vertices.length != uvs.length;
        }

        /**
         * Số float của MỘT frame deform — bám {@code SkeletonBinary.cs:1038}
         * {@code deformLength = weighted ? (vertices.Length / 3) << 1 : vertices.Length}.
         *
         * <p>C# cất mesh có weight thành {@code bones[]} + {@code vertices[] = (vx,vy,w) × I} nên
         * {@code vertices.Length = 3I}. Tool giữ dạng PHẲNG {@code [n, bone,vx,vy,w, ...]} nên
         * {@code vertices.length = V + 4I} ⇒ số bone-entry {@code I = (vertices.length - V) / 4}
         * và {@code deformLength = 2I}. TUYỆT ĐỐI không lấy thẳng độ dài mảng phẳng.
         */
        public int deformLength() {
            if (vertices == null) return 0;
            if (!weighted()) return vertices.length;
            return ((vertices.length - vertexCount()) / 4) * 2;
        }
    }
    // keyframe single-value (Spine 4.2: rotate/translatex/translatey/scalex/scaley riêng).
    // curve: null=linear, stepped flag, else bezier [cx1,cy1,cx2,cy2] tọa độ TUYỆT ĐỐI (time,value).
    public static final class Key {
        public float time, value; public boolean stepped; public float[] curve;
        /** Mẫu bezier dựng sẵn (9 điểm × 2 số). Xem {@code SpineSkeleton.bezierPoints}. */
        public float[] bez;
    }
    public static final class BoneTimeline {
        public List<Key> rotate, tx, ty, sx, sy;   // null nếu không có
        /**
         * Shear theo thời gian. 26/926 skeleton của client có (102 timeline) — bỏ qua là bone lệch
         * ngay từ chính nó rồi kéo lệch cả nhánh con (đo trên {@code Enemy/31}, {@code Enemy/1170}).
         */
        public List<Key> shx, shy;
    }
    /**
     * 1 key của SLOT ATTACHMENT TIMELINE: tại {@code time} thì slot đổi sang attachment {@code name}
     * ({@code null} = tháo ảnh ra). Rất nhiều hiệu ứng của game (gió, bụi, quái lật hình) để
     * {@code slot.attachment} SETUP = null rồi bật ảnh bằng timeline này — không đọc thì slot đó
     * vĩnh viễn rỗng và cả skeleton vẽ ra trắng trơn.
     */
    public static final class AttKey { public float time; public String name; }

    // ── DEFORM (biến dạng lưới) ─────────────────────────────────────────────
    /**
     * 1 key của DEFORM TIMELINE — giữ NGUYÊN dạng thô của file (cả nhị phân lẫn JSON đều lưu
     * đúng dạng này): chỉ đoạn float THẬT SỰ thay đổi, bắt đầu từ chỉ số {@link #offset}.
     *
     * <p><b>Ngữ nghĩa giá trị</b> (theo {@code SkeletonBinary.cs:1045-1062} / {@code SkeletonJson.cs:1091-1120}):
     * trong FILE luôn là ĐỘ LỆCH so với setup pose. Sau khi bung ra mảng đầy đủ dài
     * {@link Attachment#deformLength()} (xem {@link #expandInto}):
     * <ul>
     *   <li>mesh KHÔNG weight → toạ độ local TUYỆT ĐỐI (x,y xen kẽ) vì phải cộng thêm
     *       {@code att.vertices} ⇒ lúc vẽ THAY THẾ {@code att.vertices};</li>
     *   <li>mesh CÓ weight → vẫn là ĐỘ LỆCH (dx,dy) cộng vào {@code vx,vy} của TỪNG BONE-ENTRY
     *       (không phải từng đỉnh) trước khi nhân ma trận xương.</li>
     * </ul>
     *
     * <p>{@link #offset} là CHỈ SỐ FLOAT (không phải chỉ số đỉnh). {@code vertices.length == 0}
     * ⇒ frame này = setup pose hoàn toàn.
     */
    public static final class DeformKey {
        public float time;
        public int offset;
        public float[] vertices = EMPTY_F;
        public boolean stepped;
        /** null = linear; [cx1,cy1,cx2,cy2] toạ độ TUYỆT ĐỐI (time, percent) — percent chạy 0→1. */
        public float[] curve;
        /** Mẫu bezier dựng sẵn (9 điểm × 2 số). */
        public float[] bez;

        /**
         * Bung key này ra mảng deform ĐẦY ĐỦ (dài {@code att.deformLength()}) đúng như C# làm ngay
         * lúc đọc file. {@code out} phải do người gọi cấp phát 1 lần rồi tái dùng (tránh GC churn).
         * {@code att} = attachment mà timeline nhắm tới (cần để biết weighted + setup vertices).
         */
        public void expandInto(float[] out, Attachment att) {
            if (out == null) return;
            java.util.Arrays.fill(out, 0f);
            if (vertices != null && vertices.length > 0 && offset >= 0) {
                int n = Math.min(vertices.length, out.length - offset);
                if (n > 0) System.arraycopy(vertices, 0, out, offset, n);
            }
            // mesh KHÔNG weight: cộng setup vertices để thành toạ độ tuyệt đối (C# 1059-1062).
            if (att != null && att.vertices != null && !att.weighted()) {
                int n = Math.min(out.length, att.vertices.length);
                for (int i = 0; i < n; i++) out[i] += att.vertices[i];
            }
        }
    }

    /** DEFORM của MỘT attachment trong MỘT slot (của MỘT skin). Key đã tăng dần theo time. */
    public static final class DeformTimeline {
        public String skin, slot, attachment;
        public final List<DeformKey> keys = new ArrayList<>();
    }

    // ── MÀU SLOT ────────────────────────────────────────────────────────────
    /** Loại timeline màu (đúng hằng số của {@code SkeletonBinary.cs} dòng 51-52). */
    public static final int SLOT_RGBA = 1, SLOT_RGB = 2, SLOT_RGBA2 = 3, SLOT_RGB2 = 4, SLOT_ALPHA = 5;

    /**
     * 1 key màu slot. Kênh nào timeline không lái thì giữ 1 (trắng / đục hoàn toàn):
     * RGB/RGB2 không có alpha ⇒ {@code a = 1}; ALPHA không có màu ⇒ {@code r=g=b=1}.
     * Màu "dark" của RGBA2/RGB2 bị bỏ (model không có two-color tint).
     */
    public static final class ColorKey {
        public float time;
        public float r = 1, g = 1, b = 1, a = 1;
        public boolean stepped;
        /** null = linear; ngược lại 4 float cho MỖI KÊNH của {@link ColorTimeline#type} (xem {@link ColorTimeline#curveOf}). */
        public float[] curve;
        /** Mẫu bezier dựng sẵn cho 4 kênh RGBA, mỗi kênh 18 số (kênh nào không có thì bỏ trống). */
        public float[] bez;
        /** Bit i = kênh RGBA thứ i THẬT SỰ có bezier (dựng cùng lúc với {@link #bez}). */
        public int bezMask;
    }

    /** Timeline màu của MỘT slot. 1 slot có thể có nhiều timeline (vd RGB riêng, ALPHA riêng). */
    public static final class ColorTimeline {
        public String slot;
        public int type = SLOT_RGBA;
        public final List<ColorKey> keys = new ArrayList<>();

        /**
         * 4 float bezier của kênh RGBA thứ {@code rgbaChannel} (0=r,1=g,2=b,3=a) trong key {@code k},
         * hoặc null nếu key này linear/stepped HOẶC timeline không lái kênh đó (kênh đứng yên ⇒
         * nội suy kiểu gì cũng ra cùng giá trị).
         */
        public float[] curveOf(ColorKey k, int rgbaChannel) {
            if (k == null || k.curve == null || rgbaChannel < 0 || rgbaChannel > 3) return null;
            int ch;
            switch (type) {
                case SLOT_RGBA: case SLOT_RGBA2: ch = rgbaChannel; break;             // r,g,b,a[,dark…]
                case SLOT_RGB:  case SLOT_RGB2:  ch = rgbaChannel < 3 ? rgbaChannel : -1; break;
                case SLOT_ALPHA: ch = rgbaChannel == 3 ? 0 : -1; break;
                default: ch = -1;
            }
            if (ch < 0 || (ch + 1) * 4 > k.curve.length) return null;
            return new float[]{k.curve[ch * 4], k.curve[ch * 4 + 1], k.curve[ch * 4 + 2], k.curve[ch * 4 + 3]};
        }
    }

    // ── THỨ TỰ VẼ ───────────────────────────────────────────────────────────
    /**
     * 1 key draw order (nhảy bậc, KHÔNG nội suy). {@code order[i]} = chỉ số SLOT SETUP được vẽ ở
     * lượt thứ {@code i} (offset trong file đã được giải thành hoán vị đầy đủ lúc parse).
     * {@code order == null} ⇒ về đúng thứ tự setup.
     */
    public static final class DrawOrderKey { public float time; public int[] order; }

    /**
     * Giải danh sách offset thành hoán vị đầy đủ: {@code order[i]} = chỉ số slot setup vẽ ở lượt i
     * (thuật toán {@code unchanged[]} của {@code SkeletonBinary.cs:1161-1190} — dùng chung cho
     * cả đường nhị phân lẫn JSON).
     * Trả về null nếu dữ liệu vô lý (chỉ số ngoài phạm vi / không tăng dần) ⇒ người gọi bỏ qua
     * key đó thay vì làm hỏng cả file.
     */
    static int[] resolveDrawOrder(int[] slotIndexes, int[] offsets, int slotCount) {
        int n = slotIndexes.length;
        if (n > slotCount) return null;
        int[] order = new int[slotCount];
        Arrays.fill(order, -1);
        int[] unchanged = new int[slotCount - n];
        int originalIndex = 0, unchangedIndex = 0;
        for (int i = 0; i < n; i++) {
            int slotIndex = slotIndexes[i];
            if (slotIndex < originalIndex || slotIndex >= slotCount) return null;
            while (originalIndex != slotIndex) unchanged[unchangedIndex++] = originalIndex++;
            int target = originalIndex + offsets[i];
            if (target < 0 || target >= slotCount || order[target] != -1) return null;
            order[target] = originalIndex++;
        }
        while (originalIndex < slotCount) unchanged[unchangedIndex++] = originalIndex++;
        for (int i = slotCount - 1; i >= 0; i--)
            if (order[i] == -1) {
                if (unchangedIndex <= 0) return null;
                order[i] = unchanged[--unchangedIndex];
            }
        return order;
    }

    static final float[] EMPTY_F = new float[0];

    public static final class Animation {
        public final Map<String, BoneTimeline> bones = new HashMap<>();
        /** slotName → danh sách key đổi attachment (tăng dần theo time). Rỗng = slot giữ nguyên setup. */
        public final Map<String, List<AttKey>> slotAtt = new HashMap<>();
        /** Deform lưới — mỗi phần tử là 1 (skin, slot, attachment). Rỗng = animation không biến dạng lưới. */
        public final List<DeformTimeline> deforms = new ArrayList<>();
        /** Màu/alpha slot theo thời gian. Rỗng = mọi slot giữ màu setup. */
        public final List<ColorTimeline> colors = new ArrayList<>();
        /** Đổi thứ tự vẽ theo thời gian (tăng dần theo time). Rỗng = luôn dùng thứ tự setup. */
        public final List<DrawOrderKey> drawOrder = new ArrayList<>();
        /** IK theo thời gian (mix/softness/bend). Rỗng = mọi IK giữ tham số setup. */
        public final List<IkTimeline> ikTimelines = new ArrayList<>();
        public float duration;
    }

    public float skelWidth = 100, skelHeight = 100, skelX, skelY;
    public final List<Bone> bones = new ArrayList<>();
    public final Map<String, Integer> boneIdx = new HashMap<>();
    public final List<Slot> slots = new ArrayList<>();
    /**
     * Skin ĐANG dùng, đã hoà phẳng: {@code slotName → attachmentName → Attachment}.
     * Renderer/skeleton chỉ đọc map này — dựng lại bằng {@link #setSkin}, KHÔNG sửa tay.
     */
    public final Map<String, Map<String, Attachment>> skin = new HashMap<>();
    public final Map<String, Animation> animations = new HashMap<>();

    // ── SKIN (cải trang) ────────────────────────────────────────────────────
    /**
     * Skin ảo "gộp tất cả" — hoà MỌI skin lại, skin sau đè skin trước. Đây là chế độ MẶC ĐỊNH
     * sau khi load, đúng bằng hành vi của bản chưa tách skin, nên mọi caller cũ
     * ({@code MapLayoutCanvas}, {@code SpineCharacter.render}…) không đổi lấy một pixel.
     */
    public static final String SKIN_ALL = "(tất cả)";

    /** skinName → slotName → attachmentName → Attachment. Thứ tự khoá = thứ tự trong file. */
    public final Map<String, Map<String, Map<String, Attachment>>> skins = new LinkedHashMap<>();

    /** Tên skin đang hiển thị ({@link #SKIN_ALL} hoặc một khoá của {@link #skins}). */
    public String activeSkin = SKIN_ALL;

    /** Tên các skin theo thứ tự file (KHÔNG gồm {@link #SKIN_ALL}). */
    public List<String> skinNames() { return new ArrayList<>(skins.keySet()); }

    /** Có nhiều hơn 1 skin thật ⇒ đáng cho người dùng chọn (cải trang, biến thể quái…). */
    public boolean hasMultipleSkins() { return skins.size() > 1; }

    /**
     * Ghi 1 attachment vào skin có tên {@code skinName}. Cả bộ đọc JSON lẫn nhị phân đều đi qua
     * đây để hai đường cho ra cùng một mô hình.
     */
    void addSkinAttachment(String skinName, String slotName, String attName, Attachment att) {
        skins.computeIfAbsent(skinName == null ? "default" : skinName, k -> new LinkedHashMap<>())
             .computeIfAbsent(slotName, k -> new HashMap<>())
             .put(attName, att);
    }

    /** Gọi khi đọc xong toàn bộ skin: dựng view phẳng ban đầu = gộp tất cả (hành vi cũ). */
    void finishSkins() { setSkin(SKIN_ALL); }

    /**
     * Đổi skin đang hiển thị và dựng lại {@link #skin}.
     *
     * <p>Ngữ nghĩa bám {@code Skeleton.SetSkin} của spine-runtime: tra attachment ở skin đang chọn
     * TRƯỚC, không có thì rơi về skin {@code default}. Ở đây hoà sẵn thành một map phẳng — nền là
     * {@code default}, rồi skin chọn phủ lên — nên renderer tra một lần là ra, không phải thử 2 map.
     *
     * @return false nếu không có skin tên đó (giữ nguyên skin cũ).
     */
    public boolean setSkin(String name) {
        if (name == null) name = SKIN_ALL;
        boolean all = SKIN_ALL.equals(name);
        if (!all && !skins.containsKey(name)) return false;

        skin.clear();
        if (all) {
            for (Map<String, Map<String, Attachment>> s : skins.values()) overlay(s);
        } else {
            Map<String, Map<String, Attachment>> def = skins.get("default");
            if (def != null) overlay(def);
            if (!"default".equals(name)) overlay(skins.get(name));
        }
        activeSkin = name;
        return true;
    }

    /** Phủ một skin lên view phẳng {@link #skin} (attachment cùng tên thì bản phủ thắng). */
    private void overlay(Map<String, Map<String, Attachment>> src) {
        if (src == null) return;
        for (Map.Entry<String, Map<String, Attachment>> e : src.entrySet()) {
            skin.computeIfAbsent(e.getKey(), k -> new HashMap<>()).putAll(e.getValue());
        }
    }

    /**
     * Đọc skeleton NHỊ PHÂN {@code .skel} / {@code .skel.bytes} (Spine 4.x) — ủy quyền cho
     * {@link SpineBinary#load}. Trả về CÙNG một {@link SpineData} như {@link #load(Path)} bản JSON,
     * nên SpineAtlas / SpineSkeleton / SpineRenderer / SpineCharacter không cần đụng gì.
     */
    public static SpineData loadBinary(Path skel) throws IOException {
        return SpineBinary.load(skel);
    }

    /**
     * Đọc skeleton {@code .json} (Spine 4.x). Ủy quyền cho lớp lồng {@link Json} — MỌI thứ đụng
     * tới gson nằm trong đó để bản thân {@code SpineData} không tham chiếu gson dòng nào.
     *
     * <p>Lý do: JVM verify TOÀN BỘ lớp lúc link, nên nếu {@code SpineData} có code gson thì chỉ
     * {@code new SpineData()} trong {@link SpineBinary} cũng đòi gson trên classpath — bộ đọc nhị
     * phân vốn không cần gson lại chết khi chạy bằng {@code java -cp target/classes}.
     */
    public static SpineData load(Path jsonFile) throws Exception {
        return Json.load(jsonFile);
    }

    /** Toàn bộ phần đọc JSON (phần duy nhất phụ thuộc gson). */
    private static final class Json {

    static SpineData load(Path jsonFile) throws Exception {
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
            // Spine 4.2 ghi khoá "inherit"; 4.1 trở về trước ghi "transform" — nhận cả hai.
            b.inherit = inheritOf(str(o, "inherit", str(o, "transform", null)));
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
            s.blend = blendOf(str(o, "blend", null));
            d.slots.add(s);
        }

        // IK constraints (SkeletonJson.cs 179-206). mix mặc định 1, bendPositive mặc định true.
        for (JsonElement ie : arr(root, "ik")) {
            if (!ie.isJsonObject()) continue;
            JsonObject o = ie.getAsJsonObject();
            Ik ik = new Ik();
            ik.name = str(o, "name", null);
            ik.order = (int) f(o, "order", 0);
            JsonArray bn = arr(o, "bones");
            List<Integer> bi = new ArrayList<>();
            for (JsonElement be : bn) {
                Integer idx = d.boneIdx.get(be.getAsString());
                if (idx != null) bi.add(idx);
            }
            ik.bones = new int[bi.size()];
            for (int i = 0; i < bi.size(); i++) ik.bones[i] = bi.get(i);
            Integer tg = d.boneIdx.get(str(o, "target", null));
            ik.target = (tg == null) ? -1 : tg;
            ik.mix = f(o, "mix", 1);
            ik.softness = f(o, "softness", 0);
            ik.bendDirection = bool(o, "bendPositive", true) ? 1 : -1;
            ik.compress = bool(o, "compress", false);
            ik.stretch = bool(o, "stretch", false);
            ik.uniform = bool(o, "uniform", false);
            // Thiếu bone/target ⇒ bỏ hẳn: giữ lại chỉ tổ làm updateCache tham chiếu vào hư không.
            if (ik.target < 0 || ik.bones.length == 0 || ik.name == null) continue;
            d.ikIdx.put(ik.name, d.iks.size());
            d.iks.add(ik);
        }

        // skins — GIỮ TÁCH TỪNG SKIN (cải trang có hàng chục skin trong 1 file).
        // Spine 4.x: mảng [{name, attachments:{…}}…]. Spine 3.8: object {tênSkin: {…}}.
        JsonElement skinsEl = root.get("skins");
        if (skinsEl != null && skinsEl.isJsonArray()) {
            for (JsonElement ke : skinsEl.getAsJsonArray()) {
                if (!ke.isJsonObject()) continue;
                JsonObject sk = ke.getAsJsonObject();
                readSkin(d, str(sk, "name", "default"), obj(sk, "attachments"));
            }
        } else if (skinsEl != null && skinsEl.isJsonObject()) {
            for (Map.Entry<String, JsonElement> se : skinsEl.getAsJsonObject().entrySet()) {
                readSkin(d, se.getKey(), se.getValue().isJsonObject() ? se.getValue().getAsJsonObject() : null);
            }
        }
        d.finishSkins();

        // animations
        JsonObject anims = obj(root, "animations");
        if (anims != null) {
            for (Map.Entry<String, JsonElement> ae : anims.entrySet()) {
                Animation an = new Animation();
                JsonObject ao = ae.getValue().getAsJsonObject();
                // slot timelines: "attachment" (đổi ảnh) + màu rgba/rgb/alpha/rgba2/rgb2
                // (màu "dark" của hai-màu vẫn bỏ — model không có two-color tint)
                JsonObject slotsO = obj(ao, "slots");
                if (slotsO != null) {
                    for (Map.Entry<String, JsonElement> so : slotsO.entrySet()) {
                        JsonObject s = so.getValue().isJsonObject() ? so.getValue().getAsJsonObject() : null;
                        if (s == null) continue;
                        if (s.has("attachment") && s.get("attachment").isJsonArray()) {
                            List<AttKey> keys = new ArrayList<>();
                            for (JsonElement ke : s.getAsJsonArray("attachment")) {
                                JsonObject o = ke.getAsJsonObject();
                                AttKey k = new AttKey();
                                k.time = f(o, "time", 0);
                                k.name = str(o, "name", null);
                                keys.add(k);
                            }
                            if (!keys.isEmpty()) {
                                an.slotAtt.put(so.getKey(), keys);
                                an.duration = Math.max(an.duration, keys.get(keys.size() - 1).time);
                            }
                        }
                        // Màu slot: rgba / rgb / alpha / rgba2 / rgb2 (SkeletonJson.cs 621-808).
                        parseColor(an, so.getKey(), s, "rgba", SLOT_RGBA);
                        parseColor(an, so.getKey(), s, "rgb", SLOT_RGB);
                        parseColor(an, so.getKey(), s, "alpha", SLOT_ALPHA);
                        parseColor(an, so.getKey(), s, "rgba2", SLOT_RGBA2);
                        parseColor(an, so.getKey(), s, "rgb2", SLOT_RGB2);
                    }
                }
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
                        // Spine 4.2 ghi dạng GỘP "translate"/"scale" (1 key có cả x lẫn y) song song
                        // với dạng tách "translatex"/"translatey". Bỏ dạng gộp là cây đứng im:
                        // Wave_KameHouse.skel.json có 48 timeline "translate" và 0 "translatex".
                        XY tr = parseXY(t, "translate", 0);
                        if (tr != null) { if (tl.tx == null) tl.tx = tr.x; if (tl.ty == null) tl.ty = tr.y; }
                        XY sc = parseXY(t, "scale", 1);
                        if (sc != null) { if (tl.sx == null) tl.sx = sc.x; if (tl.sy == null) tl.sy = sc.y; }
                        tl.shx = parseTimeline(t, "shearx", 0);
                        tl.shy = parseTimeline(t, "sheary", 0);
                        XY sh = parseXY(t, "shear", 0);
                        if (sh != null) { if (tl.shx == null) tl.shx = sh.x; if (tl.shy == null) tl.shy = sh.y; }
                        an.duration = Math.max(an.duration, maxTime(tl));
                        an.bones.put(bo.getKey(), tl);
                    }
                }
                // Deform: Spine 4.x gói trong "attachments" (tên timeline "deform" nằm BÊN TRONG),
                // Spine 3.8/4.0 để thẳng khoá "deform" — đọc cả 2 cho chắc (SkeletonJson.cs 1075-1134).
                parseAttachmentTimelines(an, obj(ao, "attachments"), true);
                parseAttachmentTimelines(an, obj(ao, "deform"), false);
                parseDrawOrder(d, an, ao);
                parseIkTimelines(d, an, ao);
                // duration = mốc thời gian LỚN NHẤT của MỌI timeline trong animation, không chỉ
                // timeline bone mà model giữ lại (bản nhị phân cũng làm y hệt qua dur()).
                // Không quét sâu là hiệu ứng chỉ có timeline màu/attachment — vd Gio.json —
                // sẽ ra duration 0 ⇒ SpineCharacter.render kẹp t=0 và hiệu ứng ĐỨNG IM.
                an.duration = Math.max(an.duration, maxTimeDeep(ao));
                d.animations.put(ae.getKey(), an);
            }
        }
        return d;
    }

    /**
     * Đọc 1 timeline màu slot của JSON. Giá trị màu là chuỗi hex: {@code "color"} 8 ký tự (rgba)
     * hoặc 6 ký tự (rgb), {@code "light"}/{@code "dark"} cho hai-màu, riêng {@code alpha} dùng
     * {@code "value"} (số). Kênh nào timeline không lái thì để 1 (SkeletonJson.cs 621-808).
     */
    static void parseColor(Animation an, String slotName, JsonObject s, String name, int type) {
        if (s == null || !s.has(name) || !s.get(name).isJsonArray()) return;
        JsonArray arr = s.getAsJsonArray(name);
        if (arr.size() == 0) return;
        ColorTimeline ct = new ColorTimeline();
        ct.slot = slotName;
        ct.type = type;
        for (JsonElement ke : arr) {
            if (!ke.isJsonObject()) continue;
            JsonObject o = ke.getAsJsonObject();
            ColorKey k = new ColorKey();
            k.time = f(o, "time", 0);
            switch (type) {
                case SLOT_ALPHA:
                    // BẪY: thiếu "value" ⇒ 0 (SkeletonJson.cs 703 gọi ReadTimeline với defaultValue = 0),
                    // KHÔNG phải 1 — Spine bỏ khoá khi giá trị đúng bằng mặc định của timeline.
                    k.a = f(o, "value", 0);
                    break;
                case SLOT_RGB: {
                    String c = str(o, "color", null);
                    k.r = hex(c, 0); k.g = hex(c, 1); k.b = hex(c, 2);
                    break;
                }
                case SLOT_RGBA2: {   // light = rgba 8 ký tự, dark = rgb 6 ký tự (bỏ dark)
                    String c = str(o, "light", null);
                    k.r = hex(c, 0); k.g = hex(c, 1); k.b = hex(c, 2); k.a = hex(c, 3);
                    break;
                }
                case SLOT_RGB2: {
                    String c = str(o, "light", null);
                    k.r = hex(c, 0); k.g = hex(c, 1); k.b = hex(c, 2);
                    break;
                }
                default: {           // SLOT_RGBA
                    String c = str(o, "color", null);
                    k.r = hex(c, 0); k.g = hex(c, 1); k.b = hex(c, 2); k.a = hex(c, 3);
                }
            }
            readCurve(o, k);
            ct.keys.add(k);
            an.duration = Math.max(an.duration, k.time);
        }
        if (!ct.keys.isEmpty()) an.colors.add(ct);
    }

    /** 1 cặp hex của chuỗi màu → 0..1. Thiếu/hỏng → 1 (giữ nguyên kênh). */
    static float hex(String s, int idx) {
        if (s == null || s.length() < (idx + 1) * 2) return 1;
        try {
            return Integer.parseInt(s.substring(idx * 2, idx * 2 + 2), 16) / 255f;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** {@code "curve"} chung cho ColorKey: "stepped" hoặc mảng 4 float / kênh (toạ độ tuyệt đối). */
    static void readCurve(JsonObject o, ColorKey k) {
        JsonElement curve = o.get("curve");
        if (curve == null) return;
        if (curve.isJsonPrimitive() && curve.getAsJsonPrimitive().isString()) {
            if (curve.getAsString().equals("stepped")) k.stepped = true;
        } else if (curve.isJsonArray()) {
            JsonArray ca = curve.getAsJsonArray();
            k.curve = new float[ca.size()];
            for (int ci = 0; ci < ca.size(); ci++) k.curve[ci] = ca.get(ci).getAsFloat();
        }
    }

    /**
     * Deform trong JSON. {@code nested = true} → dạng Spine 4.x:
     * {@code attachments.<skin>.<slot>.<att>.deform = [key…]} (ngang hàng "sequence");
     * {@code nested = false} → dạng cũ {@code deform.<skin>.<slot>.<att> = [key…]}.
     * Thiếu khoá: time=0, offset=0, không có "vertices" ⇒ frame = setup pose, curve = linear.
     */
    static void parseAttachmentTimelines(Animation an, JsonObject root, boolean nested) {
        if (root == null) return;
        for (Map.Entry<String, JsonElement> skinE : root.entrySet()) {
            if (!skinE.getValue().isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> slotE : skinE.getValue().getAsJsonObject().entrySet()) {
                if (!slotE.getValue().isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> attE : slotE.getValue().getAsJsonObject().entrySet()) {
                    JsonArray keys;
                    if (nested) {
                        JsonObject tls = attE.getValue().isJsonObject() ? attE.getValue().getAsJsonObject() : null;
                        if (tls == null || !tls.has("deform") || !tls.get("deform").isJsonArray()) continue;
                        keys = tls.getAsJsonArray("deform");
                    } else {
                        if (!attE.getValue().isJsonArray()) continue;
                        keys = attE.getValue().getAsJsonArray();
                    }
                    if (keys.size() == 0) continue;
                    DeformTimeline dt = new DeformTimeline();
                    dt.skin = skinE.getKey();
                    dt.slot = slotE.getKey();
                    dt.attachment = attE.getKey();
                    for (JsonElement ke : keys) {
                        if (!ke.isJsonObject()) continue;
                        JsonObject o = ke.getAsJsonObject();
                        DeformKey k = new DeformKey();
                        k.time = f(o, "time", 0);
                        k.offset = (int) f(o, "offset", 0);
                        float[] vs = farr(o, "vertices");
                        k.vertices = vs == null ? EMPTY_F : vs;
                        JsonElement curve = o.get("curve");
                        if (curve != null) {
                            if (curve.isJsonPrimitive() && curve.getAsJsonPrimitive().isString()) {
                                if (curve.getAsString().equals("stepped")) k.stepped = true;
                            } else if (curve.isJsonArray()) {
                                JsonArray ca = curve.getAsJsonArray();
                                k.curve = new float[ca.size()];
                                for (int ci = 0; ci < ca.size(); ci++) k.curve[ci] = ca.get(ci).getAsFloat();
                            }
                        }
                        dt.keys.add(k);
                        an.duration = Math.max(an.duration, k.time);
                    }
                    if (!dt.keys.isEmpty()) an.deforms.add(dt);
                }
            }
        }
    }

    /**
     * {@code drawOrder = [{time, offsets:[{slot,offset}…]}…]} → hoán vị đầy đủ
     * (thuật toán {@code unchanged[]} của SkeletonJson.cs 1157-1190). Thiếu "offsets" ⇒ về setup.
     */
    static void parseDrawOrder(SpineData d, Animation an, JsonObject ao) {
        if (ao == null || !ao.has("drawOrder") || !ao.get("drawOrder").isJsonArray()) return;
        int slotCount = d.slots.size();
        Map<String, Integer> slotIdx = new HashMap<>();
        for (int i = 0; i < slotCount; i++) slotIdx.put(d.slots.get(i).name, i);
        for (JsonElement ke : ao.getAsJsonArray("drawOrder")) {
            if (!ke.isJsonObject()) continue;
            JsonObject o = ke.getAsJsonObject();
            DrawOrderKey k = new DrawOrderKey();
            k.time = f(o, "time", 0);
            if (o.has("offsets") && o.get("offsets").isJsonArray()) {
                JsonArray offs = o.getAsJsonArray("offsets");
                int[] si = new int[offs.size()], off = new int[offs.size()];
                boolean ok = offs.size() <= slotCount;
                for (int i = 0; ok && i < offs.size(); i++) {
                    JsonObject oo = offs.get(i).isJsonObject() ? offs.get(i).getAsJsonObject() : null;
                    Integer idx = oo == null ? null : slotIdx.get(str(oo, "slot", null));
                    if (idx == null) { ok = false; break; }
                    si[i] = idx;
                    off[i] = (int) f(oo, "offset", 0);
                }
                if (!ok) continue;
                k.order = resolveDrawOrder(si, off, slotCount);
                if (k.order == null) continue;
            }
            an.drawOrder.add(k);
            an.duration = Math.max(an.duration, k.time);
        }
    }

    /**
     * IK timeline trong JSON: {@code "ik": { tênConstraint: [ {time, mix, softness, bendPositive,
     * compress, stretch, curve} … ] }} (SkeletonJson.cs 888-940).
     * {@code mix} thiếu ⇒ 1, {@code bendPositive} thiếu ⇒ true. {@code curve} = "stepped" hoặc
     * 8 float (4 cho mix, 4 cho softness).
     */
    static void parseIkTimelines(SpineData d, Animation an, JsonObject ao) {
        JsonObject iko = obj(ao, "ik");
        if (iko == null) return;
        for (Map.Entry<String, JsonElement> e : iko.entrySet()) {
            Integer idx = d.ikIdx.get(e.getKey());
            if (idx == null || !e.getValue().isJsonArray()) continue;
            IkTimeline tl = new IkTimeline();
            tl.ik = idx;
            for (JsonElement ke : e.getValue().getAsJsonArray()) {
                if (!ke.isJsonObject()) continue;
                JsonObject o = ke.getAsJsonObject();
                IkKey k = new IkKey();
                k.time = f(o, "time", 0);
                k.mix = f(o, "mix", 1);
                k.softness = f(o, "softness", 0);
                k.bendDirection = bool(o, "bendPositive", true) ? 1 : -1;
                k.compress = bool(o, "compress", false);
                k.stretch = bool(o, "stretch", false);
                JsonElement curve = o.get("curve");
                if (curve != null) {
                    if (curve.isJsonPrimitive() && curve.getAsJsonPrimitive().isString()) {
                        if (curve.getAsString().equals("stepped")) k.stepped = true;
                    } else if (curve.isJsonArray()) {
                        JsonArray ca = curve.getAsJsonArray();
                        k.curve = new float[ca.size()];
                        for (int ci = 0; ci < ca.size(); ci++) k.curve[ci] = ca.get(ci).getAsFloat();
                    }
                }
                tl.keys.add(k);
                an.duration = Math.max(an.duration, k.time);
            }
            if (!tl.keys.isEmpty()) an.ikTimelines.add(tl);
        }
    }

    /**
     * Mọi attachment của MỘT skin: {@code atts = {tênSlot: {tênAttachment: {…}}}}.
     * Bỏ qua phần tử sai kiểu thay vì ném — vài file cải trang có slot rỗng {@code {}}.
     */
    static void readSkin(SpineData d, String skinName, JsonObject atts) {
        if (atts == null) return;
        for (Map.Entry<String, JsonElement> slotE : atts.entrySet()) {
            if (!slotE.getValue().isJsonObject()) continue;
            for (Map.Entry<String, JsonElement> attE : slotE.getValue().getAsJsonObject().entrySet()) {
                if (!attE.getValue().isJsonObject()) continue;
                d.addSkinAttachment(skinName, slotE.getKey(), attE.getKey(),
                        parseAtt(attE.getKey(), attE.getValue().getAsJsonObject()));
            }
        }
    }

    static Attachment parseAtt(String key, JsonObject o) {
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
    static List<Key> parseTimeline(JsonObject t, String name, float def) {
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

    /** Cặp danh sách key x/y tách ra từ 1 timeline gộp ("translate"/"scale"). */
    static final class XY { List<Key> x = new ArrayList<>(), y = new ArrayList<>(); }

    /**
     * Parse timeline GỘP 2 giá trị của JSON: mỗi key có {@code x}/{@code y} riêng (thiếu = {@code def})
     * và {@code curve} là 8 số — 4 số đầu cho x, 4 số sau cho y (toạ độ tuyệt đối, giống bản nhị phân).
     * null nếu animation không có timeline này.
     */
    static XY parseXY(JsonObject t, String name, float def) {
        if (t == null || !t.has(name) || !t.get(name).isJsonArray()) return null;
        XY out = new XY();
        for (JsonElement ke : t.getAsJsonArray(name)) {
            JsonObject o = ke.getAsJsonObject();
            float time = f(o, "time", 0);
            Key kx = new Key(); kx.time = time; kx.value = f(o, "x", def);
            Key ky = new Key(); ky.time = time; ky.value = f(o, "y", def);
            JsonElement curve = o.get("curve");
            if (curve != null) {
                if (curve.isJsonPrimitive() && curve.getAsJsonPrimitive().isString()
                        && curve.getAsString().equals("stepped")) {
                    kx.stepped = true; ky.stepped = true;
                } else if (curve.isJsonArray()) {
                    JsonArray ca = curve.getAsJsonArray();
                    if (ca.size() >= 4) kx.curve = slice4(ca, 0);
                    if (ca.size() >= 8) ky.curve = slice4(ca, 4);
                }
            }
            out.x.add(kx); out.y.add(ky);
        }
        return out;
    }

    static float[] slice4(JsonArray a, int off) {
        return new float[]{a.get(off).getAsFloat(), a.get(off + 1).getAsFloat(),
                           a.get(off + 2).getAsFloat(), a.get(off + 3).getAsFloat()};
    }

    /** Mốc {@code "time"} lớn nhất ở BẤT KỲ đâu trong cây JSON của 1 animation. */
    static float maxTimeDeep(JsonElement el) {
        float max = 0;
        if (el != null && el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue();
                if (e.getKey().equals("time") && v.isJsonPrimitive()) {
                    try { max = Math.max(max, v.getAsFloat()); } catch (Exception ignored) { }
                } else {
                    max = Math.max(max, maxTimeDeep(v));
                }
            }
        } else if (el != null && el.isJsonArray()) {
            for (JsonElement v : el.getAsJsonArray()) max = Math.max(max, maxTimeDeep(v));
        }
        return max;
    }

    static float maxTime(BoneTimeline tl) {
        return Math.max(
                Math.max(Math.max(lastTime(tl.rotate), Math.max(lastTime(tl.tx), lastTime(tl.ty))),
                         Math.max(lastTime(tl.sx), lastTime(tl.sy))),
                Math.max(lastTime(tl.shx), lastTime(tl.shy)));
    }
    static float lastTime(List<Key> keys) {
        return (keys == null || keys.isEmpty()) ? 0 : keys.get(keys.size() - 1).time;
    }

    // ─── json helpers ──────────────────────────────────────────
    static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }
    static JsonObject obj(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }
    static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }
    static float f(JsonObject o, String k, float def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsFloat() : def;
    }
    static boolean bool(JsonObject o, String k, boolean def) {
        try {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
        } catch (Exception e) { return def; }
    }
    static float[] farr(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return null;
        JsonArray a = o.getAsJsonArray(k);
        float[] r = new float[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsFloat();
        return r;
    }
    static int[] iarr(JsonObject o, String k) {
        if (!o.has(k) || !o.get(k).isJsonArray()) return null;
        JsonArray a = o.getAsJsonArray(k);
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsInt();
        return r;
    }
    }
}
